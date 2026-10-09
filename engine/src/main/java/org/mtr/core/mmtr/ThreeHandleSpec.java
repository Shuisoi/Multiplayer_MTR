package org.mtr.core.mmtr;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;
import org.mtr.core.mmtr.physics.ElectricBrakeSpec;
import org.mtr.core.mmtr.physics.PneumaticBrakeSpec;

/**
 * 三手柄机车的操纵规格（"定速巡航 + 双向油门 + 气制动"），从服务端 {@link ConsistType} 的 JSON 读入，
 * 也负责编成一条紧凑字符串供车辆快照镜像给客户端（客户端没有 consist-types.json）。
 *
 * <h2>为什么规格独立成一个类</h2>
 *
 * <p>三根手柄的**位置表**（哪些位置存在、每个位置给多少力）是车型数据，不是控制器状态；
 * 把它塞进 {@link ConsistType} 的标量参数里会让"位置→力"的换算散落在控制器各处。
 * 这里集中成一张表，控制器只做查表 + 合成，测试也只针对这张表。</p>
 *
 * <h2>位置定义（用户口径，见 docs/01-设计/驾驶输入与控制模型.md §3）</h2>
 *
 * <ul>
 *   <li><b>油门手柄</b> {@code driveHandle} ∈ [-97, +97]：0 = 关闭；±1 = 最小（{@code driveMinRatio}）；
 *       +2…+97 = 5%…100% 牵引；-2…-97 = 5%…100% 电阻制动（两侧镜像）。</li>
 *   <li><b>制动手柄</b>：0 = 运行（缓解），然后 1A、1B、2…8，最后一位 = EB（紧急）。</li>
 *   <li><b>定速巡航</b>：0…160 km/h，步长 5（33 个定位）。</li>
 * </ul>
 *
 * <p>除位置表以外，这里还放**手柄之后的控制律参数**：驱动延迟 {@code tractionLagMillis}（一阶惯性 τ）
 * 与**牵引力增速上限** {@code tractionRampNPerSecond}（缺省 30 kN/s，见 notes/265）—— 它们与位置表一样
 * 是"这型车怎么出力"的数据，随同一条镜像串同步给客户端。</p>
 */
public final class ThreeHandleSpec {

	/** 油门手柄离中央的极限位置（+97 / -97）；+2 起是 5%，+97 是 100%。 */
	public static final int DRIVE_HANDLE_MAX = 97;
	/** 中央位置：关闭（无牵引、无电阻制动）。 */
	public static final int DRIVE_HANDLE_CLOSED = 0;
	/** 速度百分比网格的起点（"最小"档之后的第一档）。 */
	public static final int FIRST_PERCENT = 5;
	/** 出厂制动手柄位置名（下标即 {@code brakeNotch}）。 */
	public static final String[] DEFAULT_BRAKE_POSITIONS = {"运行", "1A", "1B", "2", "3", "4", "5", "6", "7", "8", "EB"};
	/**
	 * 出厂**牵引力增速上限**（N/s）= **30 kN/s**（用户口径 2026-09-25）：
	 * 牵引力**上升与下降**都按这个斜率走，即"每秒只能变化 30 kN"。
	 */
	public static final double DEFAULT_TRACTION_RAMP_N_PER_SECOND = 30_000;
	/**
	 * 出厂制动缸目标比例，与 {@link #DEFAULT_BRAKE_POSITIONS} 一一对应。
	 *
	 * <p>锚点来自 DB 制动重量表（BR 101，84 t 车底）：8 档 = R = 143%（制动重量 120 t）为全常用制动，
	 * EB = R+E = 200%（168 t）；位置 2…8 按等比（等步长）从 0.20 升到 1.00，1A/1B 保留真车弱档。
	 * 于是 emergencyBrakeForceN / serviceBrakeForceN = 200/143 ≈ 1.399。见 notes/246。</p>
	 */
	public static final double[] DEFAULT_BRAKE_RATIOS = {0, 0.05, 0.12, 0.20, 0.3333, 0.4667, 0.60, 0.7333, 0.8667, 1.00, 1.00};

	private final double driveMinRatio;
	private final int drivePercentSteps;
	private final double rheostaticBrakeForceN;
	private final double rheostaticFadeKmh;
	private final int cruiseMaxKmh;
	private final int cruiseStepKmh;
	private final double afbGainPerMps;
	private final boolean afbUsesHandleAsCap;
	private final double afbBrakeDecelPerMps;
	private final double afbBrakeThresholdMps;
	/** 驱动延迟（一阶惯性，ms）：杆位 → 轮周力的滞后。规格 §2：电气传动 50–150 ms，柴油机可达数秒。 */
	private final double tractionLagMillis;
	/** 牵引力增速上限（N/s）：牵引力上升**与下降**的斜率上限；{@code 0} = 不限速（一拍到位）。 */
	private final double tractionRampNPerSecond;
	/**
	 * 电制动（电阻/再生）**最大回馈功率**（W）：超过 {@code P/B_max} 的速度后电制动力按 {@code P/v} 掉
	 * （notes/266，规格模块二）。{@code 0} = 不限（旧口径：一路给到 {@link #rheostaticBrakeForceN}）。
	 */
	private final double rheostaticMaxPowerW;
	/**
	 * notes/379：电阻/回生制动的**三段式曲线 + 低速切除**集中在一个可复用的口径里
	 * （{@link ElectricBrakeSpec}），与车底自己的电制动是同一套公式 —— 免得两处各写一遍淡入/恒功率。
	 * 本规格里这份的切除速度取"满力速度的一半"（notes/266 的老口径，逐位不变）。
	 */
	private final ElectricBrakeSpec electricBrake;
	/**
	 * **气压与闸片口径**（notes/266）：列车管级位表 + 分配阀 + 充排气速率 + 闸片摩擦。
	 * {@code null} = 这份车底继续走旧的归一化模型（零回归）。
	 */
	private final @Nullable PneumaticBrakeSpec brakes;
	private final String[] brakePositions;
	private final double[] brakeRatios;

	public ThreeHandleSpec(double driveMinRatio, int drivePercentSteps, double rheostaticBrakeForceN,
		double rheostaticFadeKmh, int cruiseMaxKmh, int cruiseStepKmh, double afbGainPerMps, boolean afbUsesHandleAsCap,
		String[] brakePositions, double[] brakeRatios) {
		this(driveMinRatio, drivePercentSteps, rheostaticBrakeForceN, rheostaticFadeKmh, cruiseMaxKmh, cruiseStepKmh,
			afbGainPerMps, afbUsesHandleAsCap, brakePositions, brakeRatios, 0.5, 0.05, 100);
	}

	/**
	 * @param afbGainPerMps         AFB 牵引增益（1/(m/s)）：低于设定速度时牵引 = clamp(增益 × 速度差, 0, 1)
	 * @param afbBrakeDecelPerMps   AFB 高出设定速度时**要的减速度**（(m/s²)/(m/s) = 1/s）：a = 增益 × 超出量，
	 *                              先由电阻制动出，缺口由**补气**补上（与气制动手柄耦合，见控制器）
	 * @param afbBrakeThresholdMps  AFB 的门限带宽（m/s）：带内惰行，免得在设定速度附近反复点刹
	 */
	public ThreeHandleSpec(double driveMinRatio, int drivePercentSteps, double rheostaticBrakeForceN,
		double rheostaticFadeKmh, int cruiseMaxKmh, int cruiseStepKmh, double afbGainPerMps, boolean afbUsesHandleAsCap,
		String[] brakePositions, double[] brakeRatios, double afbBrakeDecelPerMps, double afbBrakeThresholdMps) {
		this(driveMinRatio, drivePercentSteps, rheostaticBrakeForceN, rheostaticFadeKmh, cruiseMaxKmh, cruiseStepKmh,
			afbGainPerMps, afbUsesHandleAsCap, brakePositions, brakeRatios, afbBrakeDecelPerMps, afbBrakeThresholdMps, 100);
	}

	/**
	 * @param tractionLagMillis 驱动延迟（一阶惯性，ms）：杆位 → 轮周力。规格 §2 给电气传动 50–150 ms。
	 *                          {@code 0} = 不滤波（老口径 / 用例里要"第一拍即稳态"时用）。
	 */
	public ThreeHandleSpec(double driveMinRatio, int drivePercentSteps, double rheostaticBrakeForceN,
		double rheostaticFadeKmh, int cruiseMaxKmh, int cruiseStepKmh, double afbGainPerMps, boolean afbUsesHandleAsCap,
		String[] brakePositions, double[] brakeRatios, double afbBrakeDecelPerMps, double afbBrakeThresholdMps, double tractionLagMillis) {
		this(driveMinRatio, drivePercentSteps, rheostaticBrakeForceN, rheostaticFadeKmh, cruiseMaxKmh, cruiseStepKmh,
			afbGainPerMps, afbUsesHandleAsCap, brakePositions, brakeRatios, afbBrakeDecelPerMps, afbBrakeThresholdMps,
			tractionLagMillis, DEFAULT_TRACTION_RAMP_N_PER_SECOND);
	}

	/**
	 * @param tractionRampNPerSecond **牵引力增速上限**（N/s）：牵引力上升与下降的斜率上限，缺省
	 *                              {@link #DEFAULT_TRACTION_RAMP_N_PER_SECOND}（= 30 kN/s）。{@code 0} = 不限速
	 *                              （一拍到位，等价于旧口径 —— 用例与"要立刻看到稳态力"的场合用它）。
	 *                              见 notes/265 与 {@code docs/01-设计/列车纵向动力学-指标与模型.md} §2。
	 */
	public ThreeHandleSpec(double driveMinRatio, int drivePercentSteps, double rheostaticBrakeForceN,
		double rheostaticFadeKmh, int cruiseMaxKmh, int cruiseStepKmh, double afbGainPerMps, boolean afbUsesHandleAsCap,
		String[] brakePositions, double[] brakeRatios, double afbBrakeDecelPerMps, double afbBrakeThresholdMps,
		double tractionLagMillis, double tractionRampNPerSecond) {
		this(driveMinRatio, drivePercentSteps, rheostaticBrakeForceN, rheostaticFadeKmh, cruiseMaxKmh, cruiseStepKmh,
			afbGainPerMps, afbUsesHandleAsCap, brakePositions, brakeRatios, afbBrakeDecelPerMps, afbBrakeThresholdMps,
			tractionLagMillis, tractionRampNPerSecond, 0, null);
	}

	/**
	 * @param rheostaticMaxPowerW 电制动最大回馈功率（W）：{@code 0} = 不限（旧口径）。
	 * @param brakes              **气压与闸片口径**（notes/266）；{@code null} = 旧归一化模型。
	 */
	public ThreeHandleSpec(double driveMinRatio, int drivePercentSteps, double rheostaticBrakeForceN,
		double rheostaticFadeKmh, int cruiseMaxKmh, int cruiseStepKmh, double afbGainPerMps, boolean afbUsesHandleAsCap,
		String[] brakePositions, double[] brakeRatios, double afbBrakeDecelPerMps, double afbBrakeThresholdMps,
		double tractionLagMillis, double tractionRampNPerSecond, double rheostaticMaxPowerW,
		@Nullable PneumaticBrakeSpec brakes) {
		this.driveMinRatio = driveMinRatio > 0 ? driveMinRatio : 0.02;
		this.drivePercentSteps = drivePercentSteps > 0 ? drivePercentSteps : 96;
		this.rheostaticBrakeForceN = Math.max(0, rheostaticBrakeForceN);
		this.rheostaticFadeKmh = Math.max(1, rheostaticFadeKmh);
		this.cruiseMaxKmh = Math.max(cruiseStepKmh, cruiseMaxKmh);
		this.cruiseStepKmh = cruiseStepKmh > 0 ? cruiseStepKmh : 5;
		this.afbGainPerMps = afbGainPerMps > 0 ? afbGainPerMps : 1.0;
		this.afbUsesHandleAsCap = afbUsesHandleAsCap;
		this.afbBrakeDecelPerMps = afbBrakeDecelPerMps > 0 ? afbBrakeDecelPerMps : 0.5;
		this.afbBrakeThresholdMps = afbBrakeThresholdMps >= 0 ? afbBrakeThresholdMps : 0.05;
		this.tractionLagMillis = Math.max(0, tractionLagMillis);
		this.tractionRampNPerSecond = Math.max(0, tractionRampNPerSecond);
		this.rheostaticMaxPowerW = Math.max(0, rheostaticMaxPowerW);
		// notes/379：曲线口径收敛到 ElectricBrakeSpec（默认切除 = 满力速度的一半，与 notes/266 逐位相同）。
		this.electricBrake = new ElectricBrakeSpec(this.rheostaticBrakeForceN, this.rheostaticMaxPowerW, this.rheostaticFadeKmh);
		this.brakes = brakes;
		this.brakePositions = brakePositions == null || brakePositions.length == 0 ? DEFAULT_BRAKE_POSITIONS : brakePositions;
		this.brakeRatios = brakeRatios == null || brakeRatios.length == 0 ? DEFAULT_BRAKE_RATIOS.clone() : brakeRatios;
	}

	/** 出厂规格：BR101 这类机车（见 docs 与 config-example/consist-types.json）。 */
	public static ThreeHandleSpec defaults() {
		return new ThreeHandleSpec(0.02, 96, 54_000, 10, 160, 5, 1.0, true, DEFAULT_BRAKE_POSITIONS, DEFAULT_BRAKE_RATIOS, 0.5, 0.05, 100);
	}

	// ---- 位置 → 力 ---------------------------------------------------------------------------------

	/**
	 * 油门手柄的**牵引**比例：0 = 关闭；{@code p == 1} = 最小档；{@code p >= 2} = 5%…100%。
	 * 负值（电阻制动侧）返回 0。
	 */
	public double tractionRatio(int driveHandle) {
		final int p = driveHandle;
		if (p <= 0) {
			return 0;
		}
		if (p == 1) {
			return driveMinRatio;
		}
		return Math.min(1, (p - 2 + FIRST_PERCENT) / 100.0);
	}

	/**
	 * 油门手柄的**电阻制动**比例：与牵引侧完全镜像（0 = 关闭；{@code p == -1} = 最小档；
	 * {@code p <= -2} = 5%…100%）。
	 */
	public double rheostaticRatio(int driveHandle) {
		return tractionRatio(-driveHandle);
	}

	/**
	 * 电阻制动的低速衰减：**在 {@code rheostaticFadeKmh} 及以上满力，往下降到其一半时线性归零，
	 * 再低就完全失效**（真车电阻制动在低转速下建立不了力矩，会整段切掉；想停住必须用气制动）。
	 *
	 * <p>衰减带取 [fade/2, fade]（默认 5–10 km/h）是有意的：单纯的 {@code a ∝ v} 会让列车以指数律
	 * 渐近停住，"电阻制动停不住车"这条就变成一句空话 —— 而它正是"两根手柄要配合"的根据。</p>
	 */
	public double rheostaticFade(double speedMetersPerSecond) {
		return electricBrake.fadeFactor(speedMetersPerSecond);
	}

	/**
	 * **电制动可用力**（牛顿，满比例时）：三段式（notes/266，规格模块二）。
	 *
	 * <pre>
	 *   v &lt; v_fade            → 0（低速切除淡出带，由 {@link #rheostaticFade} 给形状）
	 *   v_fade ≤ v ≤ v_base   → B_max
	 *   v &gt; v_base            → min(B_max, P_regen / v)      ← 本项是 notes/266 新增
	 * </pre>
	 *
	 * <p>{@code rheostaticMaxPowerW = 0} 时退化为旧口径（一路给 {@code B_max} × 淡出）。BR101：
	 * 150 kN / 6.4 MW ⇒ 折点 153.6 km/h，200 km/h 时只剩 115 kN。</p>
	 */
	public double rheostaticEffortN(double speedMetersPerSecond) {
		return electricBrake.effortN(speedMetersPerSecond);
	}

	/** 电制动恒功率段的折点速度（m/s）：{@code P/B_max}；不限功率或无力时为 0。 */
	public double rheostaticBreakpointMetersPerSecond() {
		return electricBrake.breakpointMetersPerSecond();
	}

	/** 制动手柄位置的制动缸目标比例；越界按两端钳。 */
	public double brakeRatio(int brakePosition) {
		return brakeRatios[clamp(brakePosition, 0, brakeRatios.length - 1)];
	}

	/** 是否紧急位（最后一位 = EB）。 */
	public boolean isEmergencyPosition(int brakePosition) {
		return brakePosition >= brakeRatios.length - 1;
	}

	public String brakePositionLabel(int brakePosition) {
		final int index = clamp(brakePosition, 0, brakePositions.length - 1);
		return index < brakePositions.length ? brakePositions[index] : String.valueOf(index);
	}

	/** 制动手柄"运行"位（缓解）的下标。 */
	public static int runningPosition() {
		return 0;
	}

	// ---- 钳位 -------------------------------------------------------------------------------------

	public int clampDriveHandle(int value) {
		return clamp(value, -DRIVE_HANDLE_MAX, DRIVE_HANDLE_MAX);
	}

	public int clampBrakePosition(int value) {
		return clamp(value, 0, brakeRatios.length - 1);
	}

	/** 定速值钳到 [0, cruiseMax]，并对齐到步长网格。 */
	public int clampCruiseKmh(int value) {
		final int clamped = clamp(value, 0, cruiseMaxKmh);
		return Math.round((float) clamped / cruiseStepKmh) * cruiseStepKmh;
	}

	// ---- 配置读写 ---------------------------------------------------------------------------------

	public static ThreeHandleSpec fromJson(JsonObject json, double fallbackRheostaticForceN) {
		return fromJson(json, fallbackRheostaticForceN, PneumaticBrakeSpec.fromJsonOrNull(json));
	}

	/**
	 * notes/376：气压口径由调用方（{@link ConsistType#fromJson}）解析一次后传进来 —— 同一份文档不再解析两份口径，
	 * 车底与手柄规格共用**同一个** {@link PneumaticBrakeSpec} 实例。
	 */
	public static ThreeHandleSpec fromJson(JsonObject json, double fallbackRheostaticForceN, @Nullable PneumaticBrakeSpec brakes) {
		return new ThreeHandleSpec(
			getDouble(json, "driveMinRatio", 0.02),
			getInt(json, "drivePercentSteps", 96),
			getDouble(json, "rheostaticBrakeForceN", fallbackRheostaticForceN),
			getDouble(json, "rheostaticFadeKmh", 10),
			getInt(json, "cruiseMaxKmh", 160),
			getInt(json, "cruiseStepKmh", 5),
			getDouble(json, "afbGainPerMps", 1.0),
			getBoolean(json, "afbUsesHandleAsCap", true),
			DEFAULT_BRAKE_POSITIONS,
			parseRatios(getString(json, "brakeRatios", "")),
			getDouble(json, "afbBrakeDecelPerMps", 0.5),
			getDouble(json, "afbBrakeThresholdMps", 0.05),
			getDouble(json, "tractionLagMillis", 100),
			// 牵引力增速上限（notes/265）：缺省 30 kN/s；显式写 0 = 不限速（旧口径）。
			getDouble(json, "tractionRampNPerSecond", DEFAULT_TRACTION_RAMP_N_PER_SECOND),
			// 电制动高速恒功率上限（notes/266）：缺省 0 = 不限（旧口径）。
			getDouble(json, "rheostaticMaxPowerW", 0),
			brakes
		);
	}

	/**
	 * 编成快照镜像用的紧凑字符串（字段顺序即构造顺序，见 {@link #decode}）。
	 * 只有车型变化时才会变，所以不必每帧传数组。
	 */
	public String encode() {
		final StringBuilder ratios = new StringBuilder();
		for (int i = 0; i < brakeRatios.length; i++) {
			if (i > 0) {
				ratios.append(',');
			}
			ratios.append(trim(brakeRatios[i]));
		}
		return "TH;" + trim(driveMinRatio) + ';' + drivePercentSteps + ';' + trim(rheostaticBrakeForceN) + ';'
			+ trim(rheostaticFadeKmh) + ';' + cruiseMaxKmh + ';' + cruiseStepKmh + ';' + trim(afbGainPerMps) + ';'
			+ (afbUsesHandleAsCap ? 1 : 0) + ';' + ratios + ';' + trim(afbBrakeDecelPerMps) + ';' + trim(afbBrakeThresholdMps) + ';' + trim(tractionLagMillis)
			+ ';' + trim(tractionRampNPerSecond)
			// notes/266：电制动恒功率上限 + 整段气压/闸片口径（最后一个字段是 PB 串；没有就空着）
			+ ';' + trim(rheostaticMaxPowerW) + ';' + (brakes == null ? "" : brakes.encode());
	}

	/** 解 {@link #encode()}；任何残缺都退回缺省值（镜像坏了不能让客户端起不来）。 */
	public static ThreeHandleSpec decode(String text) {
		if (text == null || text.isEmpty()) {
			return null;
		}
		final String[] parts = text.split(";", -1);
		if (parts.length < 10 || !"TH".equals(parts[0])) {
			return null;
		}
		try {
			return new ThreeHandleSpec(
				Double.parseDouble(parts[1]),
				Integer.parseInt(parts[2]),
				Double.parseDouble(parts[3]),
				Double.parseDouble(parts[4]),
				Integer.parseInt(parts[5]),
				Integer.parseInt(parts[6]),
				Double.parseDouble(parts[7]),
				!"0".equals(parts[8]),
				DEFAULT_BRAKE_POSITIONS,
				parseRatios(parts[9]),
				// 新加的两个字段（定速的电阻制动增益 / 门限）：旧镜像串没有它们 ⇒ 用缺省值
				parts.length > 10 ? Double.parseDouble(parts[10]) : 1.0,
				parts.length > 11 ? Double.parseDouble(parts[11]) : 0.05,
				// 驱动延迟（新字段）：旧镜像串没有它 ⇒ 缺省 100 ms
				parts.length > 12 ? Double.parseDouble(parts[12]) : 100,
				// 牵引力增速上限（notes/265）：旧镜像串没有它 ⇒ 缺省 30 kN/s
				parts.length > 13 ? Double.parseDouble(parts[13]) : DEFAULT_TRACTION_RAMP_N_PER_SECOND,
				// 电制动恒功率上限（notes/266，新字段）：旧串没有它 ⇒ 0 = 不限
				parts.length > 14 ? Double.parseDouble(parts[14]) : 0,
				// 气压/闸片口径（notes/266，最后一字段）：旧串没有它 ⇒ null = 旧归一化模型
				parts.length > 15 ? PneumaticBrakeSpec.decode(parts[15]) : null
			);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static double[] parseRatios(String text) {
		if (text == null || text.trim().isEmpty()) {
			return DEFAULT_BRAKE_RATIOS.clone();
		}
		final String[] parts = text.split(",");
		final double[] ratios = new double[parts.length];
		for (int i = 0; i < parts.length; i++) {
			try {
				ratios[i] = Math.max(0, Math.min(1, Double.parseDouble(parts[i].trim())));
			} catch (NumberFormatException e) {
				ratios[i] = 0;
			}
		}
		return ratios;
	}

	public double getDriveMinRatio() { return driveMinRatio; }
	public int getDrivePercentSteps() { return drivePercentSteps; }
	public double getRheostaticBrakeForceN() { return rheostaticBrakeForceN; }
	public double getRheostaticFadeKmh() { return rheostaticFadeKmh; }
	public int getCruiseMaxKmh() { return cruiseMaxKmh; }
	public int getCruiseStepKmh() { return cruiseStepKmh; }
	public double getAfbGainPerMps() { return afbGainPerMps; }
	public boolean isAfbUsesHandleAsCap() { return afbUsesHandleAsCap; }
	/** AFB 高出设定速度时**要的减速度**（(m/s²)/(m/s) = 1/s）：a = 增益 × 超出量。 */
	public double getAfbBrakeDecelPerMps() { return afbBrakeDecelPerMps; }
	/** AFB 的门限带宽（m/s）：带内惰行（不再动电阻制动），免得在设定速度附近反复点刹。 */
	public double getAfbBrakeThresholdMps() { return afbBrakeThresholdMps; }
	/** 驱动延迟（一阶惯性，ms）：杆位 → 轮周力；0 = 不滤波。 */
	public double getTractionLagMillis() { return tractionLagMillis; }
	/**
	 * **牵引力增速上限**（N/s）：牵引力上升与下降的斜率上限（缺省 30 kN/s，{@code 0} = 不限速）。
	 * 见 notes/265。
	 */
	public double getTractionRampNPerSecond() { return tractionRampNPerSecond; }
	/** 电制动最大回馈功率（W）：{@code 0} = 不限（旧口径）。 */
	public double getRheostaticMaxPowerW() { return rheostaticMaxPowerW; }

	/** notes/379：这份手柄规格带的电制动口径（{@code null} = 没有电制动）。 */
	public ElectricBrakeSpec getElectricBrake() { return electricBrake.isPresent() ? electricBrake : null; }
	/** **气压与闸片口径**（notes/266）；{@code null} = 这份车底走旧归一化模型。 */
	public @Nullable PneumaticBrakeSpec getBrakes() { return brakes; }
	public int getBrakePositionCount() { return brakeRatios.length; }

	/** 打印用（日志/诊断）：把一根油门手柄的位置说成人话。 */
	public String describeDriveHandle(int driveHandle) {
		final int p = clampDriveHandle(driveHandle);
		if (p == DRIVE_HANDLE_CLOSED) {
			return "关闭";
		}
		final String side = p > 0 ? "牵引" : "电阻制动";
		if (p == 1 || p == -1) {
			return side + " 最小";
		}
		return side + " " + Math.round((Math.abs(p) - 2 + FIRST_PERCENT)) + "%";
	}

	private static String trim(double value) {
		if (value == Math.rint(value)) {
			return String.valueOf((long) value);
		}
		return String.valueOf(Math.round(value * 1e6) / 1e6);
	}

	private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

	private static String getString(JsonObject json, String key, String fallback) {
		final JsonElement element = json.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsString();
	}

	private static int getInt(JsonObject json, String key, int fallback) {
		final JsonElement element = json.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsInt();
	}

	private static double getDouble(JsonObject json, String key, double fallback) {
		final JsonElement element = json.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
	}

	private static boolean getBoolean(JsonObject json, String key, boolean fallback) {
		final JsonElement element = json.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
	}
}
