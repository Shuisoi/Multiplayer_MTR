package org.mtr.core.mmtr;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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
	/** 出厂制动缸目标比例，与 {@link #DEFAULT_BRAKE_POSITIONS} 一一对应（1A、1B 刻意明显弱于 2 档）。 */
	public static final double[] DEFAULT_BRAKE_RATIOS = {0, 0.05, 0.12, 0.20, 0.32, 0.44, 0.56, 0.68, 0.80, 1.00, 1.00};

	private final double driveMinRatio;
	private final int drivePercentSteps;
	private final double rheostaticBrakeDecelerationMps2;
	private final double rheostaticFadeKmh;
	private final int cruiseMaxKmh;
	private final int cruiseStepKmh;
	private final double afbGainPerMps;
	private final boolean afbUsesHandleAsCap;
	private final String[] brakePositions;
	private final double[] brakeRatios;

	public ThreeHandleSpec(double driveMinRatio, int drivePercentSteps, double rheostaticBrakeDecelerationMps2,
		double rheostaticFadeKmh, int cruiseMaxKmh, int cruiseStepKmh, double afbGainPerMps, boolean afbUsesHandleAsCap,
		String[] brakePositions, double[] brakeRatios) {
		this.driveMinRatio = driveMinRatio > 0 ? driveMinRatio : 0.02;
		this.drivePercentSteps = drivePercentSteps > 0 ? drivePercentSteps : 96;
		this.rheostaticBrakeDecelerationMps2 = Math.max(0, rheostaticBrakeDecelerationMps2);
		this.rheostaticFadeKmh = Math.max(1, rheostaticFadeKmh);
		this.cruiseMaxKmh = Math.max(cruiseStepKmh, cruiseMaxKmh);
		this.cruiseStepKmh = cruiseStepKmh > 0 ? cruiseStepKmh : 5;
		this.afbGainPerMps = afbGainPerMps > 0 ? afbGainPerMps : 0.15;
		this.afbUsesHandleAsCap = afbUsesHandleAsCap;
		this.brakePositions = brakePositions == null || brakePositions.length == 0 ? DEFAULT_BRAKE_POSITIONS : brakePositions;
		this.brakeRatios = brakeRatios == null || brakeRatios.length == 0 ? DEFAULT_BRAKE_RATIOS.clone() : brakeRatios;
	}

	/** 出厂规格：BR101 这类机车（见 docs 与 config-example/consist-types.json）。 */
	public static ThreeHandleSpec defaults() {
		return new ThreeHandleSpec(0.02, 96, 0.9, 10, 160, 5, 0.15, true, DEFAULT_BRAKE_POSITIONS, DEFAULT_BRAKE_RATIOS);
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
		final double fadeSpeedMps = rheostaticFadeKmh / 3.6;
		final double cutoffSpeedMps = fadeSpeedMps * 0.5;
		return Math.max(0, Math.min(1, (Math.max(0, speedMetersPerSecond) - cutoffSpeedMps) / (fadeSpeedMps - cutoffSpeedMps)));
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

	public static ThreeHandleSpec fromJson(JsonObject json, double fallbackRheostaticDecelerationMps2) {
		return new ThreeHandleSpec(
			getDouble(json, "driveMinRatio", 0.02),
			getInt(json, "drivePercentSteps", 96),
			getDouble(json, "rheostaticBrakeDecelerationMps2", fallbackRheostaticDecelerationMps2),
			getDouble(json, "rheostaticFadeKmh", 10),
			getInt(json, "cruiseMaxKmh", 160),
			getInt(json, "cruiseStepKmh", 5),
			getDouble(json, "afbGainPerMps", 0.15),
			getBoolean(json, "afbUsesHandleAsCap", true),
			DEFAULT_BRAKE_POSITIONS,
			parseRatios(getString(json, "brakeRatios", ""))
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
		return "TH;" + trim(driveMinRatio) + ';' + drivePercentSteps + ';' + trim(rheostaticBrakeDecelerationMps2) + ';'
			+ trim(rheostaticFadeKmh) + ';' + cruiseMaxKmh + ';' + cruiseStepKmh + ';' + trim(afbGainPerMps) + ';'
			+ (afbUsesHandleAsCap ? 1 : 0) + ';' + ratios;
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
				parseRatios(parts[9])
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
	public double getRheostaticBrakeDecelerationMps2() { return rheostaticBrakeDecelerationMps2; }
	public double getRheostaticFadeKmh() { return rheostaticFadeKmh; }
	public int getCruiseMaxKmh() { return cruiseMaxKmh; }
	public int getCruiseStepKmh() { return cruiseStepKmh; }
	public double getAfbGainPerMps() { return afbGainPerMps; }
	public boolean isAfbUsesHandleAsCap() { return afbUsesHandleAsCap; }
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
