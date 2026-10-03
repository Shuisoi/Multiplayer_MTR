package org.mtr.core.mmtr.physics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * **列车管气压模型**（bar 口径）：级位表 + 分配阀 + 充排气速率（notes/266，规格见
 * {@code docs/01-设计/制动系统-气压与制动力模型-设计.md}）。
 *
 * <h2>为什么单独一类</h2>
 *
 * <p>旧的 {@code brakeRatios} 是"档位 = 制动缸比例"，管压只是 {@code 1 − 缸压} 的镜像量 —— 它没有
 * bar 语义，也就没有"标准 5.0 / 运行 5.2 / 初制动 4.6 / 全常用 3.5"这些真车口径。真车的气路是：</p>
 *
 * <pre>
 *   司机手柄档位 ──▶ 列车管目标压力(bar) ──充排气速率──▶ 管压 P_pipe
 *                                                      │
 *                                          分配阀（倍率/灵敏限/限压）
 *                                                      ▼
 *                                                  缸压 P_bc ──▶ 制动力（{@link BrakeSpec}）
 * </pre>
 *
 * <p>本类只管**气压**这一层（管压、缸压、时间常数），力的换算在 {@link BrakeSpec}。</p>
 *
 * <h2>出厂口径（BR101 这类机车，用户 2026-09-25）</h2>
 *
 * <ul>
 *   <li>定压（标称）5.0 bar，运行位充风稳定在 **5.2 bar**；</li>
 *   <li>初制动（1A）**4.6 bar**，逐级递减到 8 档（全常用）**3.5 bar**，EB 快排到 3.0 bar；</li>
 *   <li>分配阀：{@code P_bc = clamp(倍率 × (5.2 − P_pipe − 灵敏限), 0, 3.8 bar)}；
 *       缸压低于 **0.30 bar**（复位弹簧）时活塞不动作（算力时才扣，见 {@link BrakeSpec}）。</li>
 * </ul>
 *
 * <p>缺省值刻意让**时间手感**与旧归一化参数一致（充风 0.15/s × 1.7 bar = 0.26 ≈ 0.20；
 * 建压 0.35/s × 3.8 = 1.33 ≈ 1.30；缓解 0.25/s × 3.8 = 0.95），所以换口径不会顺带改掉"建压要多快"。</p>
 */
public final class PneumaticBrakeSpec {

	/** 定压（标称）：表盘与校验用。 */
	private final double nominalBar;
	/** 运行位充风稳定值：司机手柄在"运行"位时管子停的地方。 */
	private final double chargedBar;
	/** 全常用（8 档）管压。 */
	private final double fullServiceBar;
	/** 紧急（EB）管压目标。 */
	private final double emergencyBar;
	/** 级位表：下标 = {@code brakeNotch}（0 = 运行）。长度即档位数。 */
	private final double[] targetsBar;
	private final double chargeBarPerSecond;
	private final double dischargeBarPerSecond;
	/** 缸压上限（常用全制动 / 限压阀）。 */
	private final double cylinderMaxBar;
	/** 缸压复位弹簧：低于它活塞不动作（算力时扣掉）。 */
	private final double cylinderSpringBar;
	/** 紧急位缸压（紧急限压，高于常用上限）。 */
	private final double cylinderEmergencyBar;
	private final double cylinderApplyBarPerSecond;
	private final double cylinderReleaseBarPerSecond;
	private final double emergencyCylinderBarPerSecond;
	/** 分配阀倍率：缸压 = 倍率 ×（管压降 − 灵敏限）。 */
	private final double distributorRatio;
	/** 分配阀灵敏限（bar）：管压降小于它缸压不动（真车"最小减压量"的由来）。 */
	private final double distributorSensitivityBar;
	/**
	 * **列车管沿车列传播的松弛速率**（1/s，notes/268 P5）：第 {@code i} 节管压每秒向它前面那节靠多少比例。
	 *
	 * <p>真车是"压力波 250–300 m/s + 长管容积充排气"，逐节 20 m 车长其实只有 0.07 s —— 真正拖慢长编组的是
	 * **容积与充排气时间**。这里用一条一阶松弛当替身：缺省 5.0/s ⇒ 3 节车尾车滞后约 0.3–0.4 s，
	 * 8 节约 1 s，50 节货车约 10 s（真车量级）。</p>
	 */
	private final double pipePropagationPerSecond;
	/**
	 * 闸片/盘片摩擦是否随速衰减（{@link BrakeSpec} 用）。
	 *
	 * <p>与 bar 气路一起开关：bar 口径的车底默认开（真车就是这样），旧配置不写就没有（零回归）。</p>
	 */
	private final boolean padFadeEnabled;
	private final double padMu0;
	private final double padMuSlopePerKmh;
	private final double padMuFloor;
	/**
	 * **电空混合**（notes/267，规格模块三）：电制动先吃饱、机械补缺口，EP 阀**只削不加**。
	 * 关掉 = 旧行为（缸压只由管压决定，电制动另外相加）。
	 */
	private final boolean blendingEnabled;
	/** **WSP（防滑）**：默认恒开 —— 超黏着时把力钳在上限（真车点刹保持峰值微滑）。 */
	private final boolean wspEnabled;
	/** 无 WSP 抱死后的动摩擦系数（断崖）：0.10–0.15，取中值。 */
	private final double wheelSlipMu;

	/** 出厂级位表（11 位手柄）：运行 … 8 档 … EB。 */
	public static final double[] DEFAULT_TARGETS_BAR = {5.2, 4.6, 4.4, 4.2, 4.05, 3.9, 3.8, 3.7, 3.6, 3.5, 3.0};
	/** 无 WSP 抱死后的动摩擦系数（规格模块四：0.10–0.15，取中值 0.12）。 */
	public static final double DEFAULT_WHEEL_SLIP_MU = 0.12;
	/** 列车管沿车列传播的松弛速率（1/s，notes/268 P5）。 */
	public static final double DEFAULT_PIPE_PROPAGATION_PER_SECOND = 5.0;

	public PneumaticBrakeSpec(double nominalBar, double chargedBar, double fullServiceBar, double emergencyBar,
		double[] targetsBar, double chargeBarPerSecond, double dischargeBarPerSecond,
		double cylinderMaxBar, double cylinderSpringBar, double cylinderEmergencyBar,
		double cylinderApplyBarPerSecond, double cylinderReleaseBarPerSecond, double emergencyCylinderBarPerSecond,
		double distributorRatio, double distributorSensitivityBar) {
		this(nominalBar, chargedBar, fullServiceBar, emergencyBar, targetsBar, chargeBarPerSecond, dischargeBarPerSecond,
			cylinderMaxBar, cylinderSpringBar, cylinderEmergencyBar, cylinderApplyBarPerSecond, cylinderReleaseBarPerSecond,
			emergencyCylinderBarPerSecond, distributorRatio, distributorSensitivityBar,
			true, BrakeSpec.DEFAULT_PAD_MU0, BrakeSpec.DEFAULT_PAD_MU_SLOPE_PER_KMH, BrakeSpec.DEFAULT_PAD_MU_FLOOR);
	}

	public PneumaticBrakeSpec(double nominalBar, double chargedBar, double fullServiceBar, double emergencyBar,
		double[] targetsBar, double chargeBarPerSecond, double dischargeBarPerSecond,
		double cylinderMaxBar, double cylinderSpringBar, double cylinderEmergencyBar,
		double cylinderApplyBarPerSecond, double cylinderReleaseBarPerSecond, double emergencyCylinderBarPerSecond,
		double distributorRatio, double distributorSensitivityBar,
		boolean padFadeEnabled, double padMu0, double padMuSlopePerKmh, double padMuFloor) {
		this(nominalBar, chargedBar, fullServiceBar, emergencyBar, targetsBar, chargeBarPerSecond, dischargeBarPerSecond,
			cylinderMaxBar, cylinderSpringBar, cylinderEmergencyBar, cylinderApplyBarPerSecond, cylinderReleaseBarPerSecond,
			emergencyCylinderBarPerSecond, distributorRatio, distributorSensitivityBar,
			padFadeEnabled, padMu0, padMuSlopePerKmh, padMuFloor, true, true, DEFAULT_WHEEL_SLIP_MU,
			DEFAULT_PIPE_PROPAGATION_PER_SECOND);
	}

	/**
	 * @param blendingEnabled 电空混合（电优先、机械补缺、EP 只削不加）
	 * @param wspEnabled      WSP：超黏着时钳到上限（关掉则断崖到 {@code wheelSlipMu}）
	 * @param wheelSlipMu     抱死后的动摩擦系数（0.10–0.15）
	 * @param pipePropagationPerSecond 列车管沿车列传播的松弛速率（1/s，notes/268 P5）
	 */
	public PneumaticBrakeSpec(double nominalBar, double chargedBar, double fullServiceBar, double emergencyBar,
		double[] targetsBar, double chargeBarPerSecond, double dischargeBarPerSecond,
		double cylinderMaxBar, double cylinderSpringBar, double cylinderEmergencyBar,
		double cylinderApplyBarPerSecond, double cylinderReleaseBarPerSecond, double emergencyCylinderBarPerSecond,
		double distributorRatio, double distributorSensitivityBar,
		boolean padFadeEnabled, double padMu0, double padMuSlopePerKmh, double padMuFloor,
		boolean blendingEnabled, boolean wspEnabled, double wheelSlipMu, double pipePropagationPerSecond) {
		this.nominalBar = Math.max(0, nominalBar);
		this.chargedBar = Math.max(0, chargedBar);
		this.fullServiceBar = Math.max(0, fullServiceBar);
		this.emergencyBar = Math.max(0, emergencyBar);
		this.targetsBar = targetsBar == null || targetsBar.length == 0 ? DEFAULT_TARGETS_BAR.clone() : targetsBar.clone();
		this.chargeBarPerSecond = Math.max(0, chargeBarPerSecond);
		this.dischargeBarPerSecond = Math.max(0, dischargeBarPerSecond);
		this.cylinderMaxBar = Math.max(0.01, cylinderMaxBar);
		this.cylinderSpringBar = Math.max(0, Math.min(this.cylinderMaxBar, cylinderSpringBar));
		this.cylinderEmergencyBar = Math.max(this.cylinderMaxBar, cylinderEmergencyBar);
		this.cylinderApplyBarPerSecond = Math.max(0, cylinderApplyBarPerSecond);
		this.cylinderReleaseBarPerSecond = Math.max(0, cylinderReleaseBarPerSecond);
		this.emergencyCylinderBarPerSecond = Math.max(0, emergencyCylinderBarPerSecond);
		this.distributorRatio = Math.max(0, distributorRatio);
		this.distributorSensitivityBar = Math.max(0, distributorSensitivityBar);
		this.padFadeEnabled = padFadeEnabled;
		this.padMu0 = padMu0 > 0 ? padMu0 : BrakeSpec.DEFAULT_PAD_MU0;
		this.padMuSlopePerKmh = Math.max(0, padMuSlopePerKmh);
		this.padMuFloor = Math.max(0, Math.min(this.padMu0, padMuFloor));
		this.blendingEnabled = blendingEnabled;
		this.wspEnabled = wspEnabled;
		this.wheelSlipMu = Math.max(0, wheelSlipMu);
		this.pipePropagationPerSecond = Math.max(0, pipePropagationPerSecond);
	}

	/** 出厂规格（BR101 口径）。 */
	public static PneumaticBrakeSpec defaults() {
		return new PneumaticBrakeSpec(5.0, 5.2, 3.5, 3.0, DEFAULT_TARGETS_BAR,
			0.20, 0.85, 3.8, 0.30, 4.2, 1.30, 0.95, 2.0, 3.8 / (5.2 - 3.5 - 0.2), 0.20);
	}

	// ---- 级位与分配阀 -----------------------------------------------------------------------------

	/** 某一档的**列车管目标压力**（bar）；越界钳到两端。 */
	public double targetPipeBar(int brakeNotch) {
		return targetsBar[Math.max(0, Math.min(targetsBar.length - 1, brakeNotch))];
	}

	/** 这一档是不是"缓解位"（管压目标 = 充风稳定值）。 */
	public boolean isReleasePosition(int brakeNotch) {
		return targetPipeBar(brakeNotch) >= chargedBar - 1e-9;
	}

	/**
	 * **分配阀**：管压 → 缸压（bar）。
	 *
	 * <pre>
	 *   P_bc = clamp(倍率 × (充风稳定值 − P_pipe − 灵敏限), 0, 缸压上限)
	 * </pre>
	 *
	 * <p>灵敏限 0.2 bar + 缸簧 0.3 bar 一起决定了"最小可施加的常用制动力"（≈20 kN 量级）——
	 * 于是旧表里那种 5% 的弱档在真车气压语义下**不存在**，这是引入 bar 口径最直接的手感变化。</p>
	 */
	public double distributorCylinderBar(double pipeBar) {
		final double drop = chargedBar - pipeBar - distributorSensitivityBar;
		final double raw = distributorRatio * drop;
		// 全常用（ΔP = 1.7）要**正好**顶到限压阀：倍率在配置里是四舍五入过的小数，
		// 差 5e-5 bar 会让"满缸压"永远差一点（联锁/断言全跟着抖），所以这里给一个极小的吸附。
		if (raw >= cylinderMaxBar - 1e-3) {
			return cylinderMaxBar;
		}
		return Math.max(0, Math.min(cylinderMaxBar, raw));
	}

	// ---- 时间常数（每拍一步） ---------------------------------------------------------------------

	/** 管压追目标（bar）：充风按 {@code chargeBarPerSecond}、排风按 {@code dischargeBarPerSecond}。 */
	public double stepPipe(double pipeBar, double targetBar, double dtSeconds) {
		final double target = Math.max(0, Math.min(chargedBar, targetBar));
		final double rate = target > pipeBar ? chargeBarPerSecond : dischargeBarPerSecond;
		final double step = rate * dtSeconds;
		return step >= Math.abs(target - pipeBar) ? target : pipeBar + Math.signum(target - pipeBar) * step;
	}

	/** 紧急位：管压快速排到 {@link #getEmergencyBar()}。 */
	public double stepPipeEmergency(double pipeBar, double dtSeconds) {
		return Math.max(emergencyBar, pipeBar - dischargeBarPerSecond * 3.0 * dtSeconds);
	}

	/**
	 * 缸压追目标（bar）：建压/缓解有速率；紧急位用更快的建压速率。
	 *
	 * @param emergency 紧急（EB / 保护层）：排风最快、缸压直接冲到紧急限压
	 */
	public double stepCylinder(double cylinderBar, double targetBar, boolean emergency, double dtSeconds) {
		final double target = emergency ? cylinderEmergencyBar : Math.max(0, Math.min(cylinderMaxBar, targetBar));
		final double rate = emergency ? emergencyCylinderBarPerSecond : target > cylinderBar ? cylinderApplyBarPerSecond : cylinderReleaseBarPerSecond;
		final double step = rate * dtSeconds;
		return step >= Math.abs(target - cylinderBar) ? target : cylinderBar + Math.signum(target - cylinderBar) * step;
	}

	// ---- 配置读写 ---------------------------------------------------------------------------------

	/**
	 * 从车底 JSON 读；**只要没有一个 bar 口径的键就返回 null**（= 这份车底继续走旧归一化模型，
	 * 零回归）。判据键取 {@code airPipeChargedBar} / {@code airPipeTargetsBar} 之一。
	 */
	public static PneumaticBrakeSpec fromJsonOrNull(JsonObject json) {
		if (!json.has("airPipeChargedBar") && !json.has("airPipeTargetsBar") && !json.has("distributorRatio")) {
			return null;
		}
		final PneumaticBrakeSpec defaults = defaults();
		return new PneumaticBrakeSpec(
			getDouble(json, "airPipeNominalBar", defaults.nominalBar),
			getDouble(json, "airPipeChargedBar", defaults.chargedBar),
			getDouble(json, "airPipeFullServiceBar", defaults.fullServiceBar),
			getDouble(json, "airPipeEmergencyBar", defaults.emergencyBar),
			parseTargets(getString(json, "airPipeTargetsBar", "")),
			getDouble(json, "airPipeChargeBarPerSecond", defaults.chargeBarPerSecond),
			getDouble(json, "airPipeDischargeBarPerSecond", defaults.dischargeBarPerSecond),
			getDouble(json, "brakeCylinderMaxBar", defaults.cylinderMaxBar),
			getDouble(json, "brakeCylinderSpringBar", defaults.cylinderSpringBar),
			getDouble(json, "brakeCylinderEmergencyBar", defaults.cylinderEmergencyBar),
			getDouble(json, "brakeCylinderApplyBarPerSecond", defaults.cylinderApplyBarPerSecond),
			getDouble(json, "brakeCylinderReleaseBarPerSecond", defaults.cylinderReleaseBarPerSecond),
			getDouble(json, "brakeCylinderEmergencyBarPerSecond", defaults.emergencyCylinderBarPerSecond),
			getDouble(json, "distributorRatio", defaults.distributorRatio),
			getDouble(json, "distributorSensitivityBar", defaults.distributorSensitivityBar),
			getBoolean(json, "padFadeEnabled", true),
			getDouble(json, "padMu0", BrakeSpec.DEFAULT_PAD_MU0),
			getDouble(json, "padMuSlopePerKmh", BrakeSpec.DEFAULT_PAD_MU_SLOPE_PER_KMH),
			getDouble(json, "padMuFloor", BrakeSpec.DEFAULT_PAD_MU_FLOOR),
			getBoolean(json, "blendingEnabled", true),
			getBoolean(json, "wspEnabled", true),
			getDouble(json, "wheelSlipMu", DEFAULT_WHEEL_SLIP_MU),
			getDouble(json, "airPipePropagationPerSecond", DEFAULT_PIPE_PROPAGATION_PER_SECOND)
		);
	}

	private static double[] parseTargets(String text) {
		if (text == null || text.trim().isEmpty()) {
			return DEFAULT_TARGETS_BAR.clone();
		}
		final String[] parts = text.split(",");
		final double[] targets = new double[parts.length];
		for (int i = 0; i < parts.length; i++) {
			try {
				targets[i] = Math.max(0, Double.parseDouble(parts[i].trim()));
			} catch (NumberFormatException e) {
				targets[i] = DEFAULT_TARGETS_BAR[Math.min(i, DEFAULT_TARGETS_BAR.length - 1)];
			}
		}
		return targets;
	}

	/**
	 * 编成镜像串的一段（前缀 {@code PB,}）：客户端没有 consist-types.json，气压口径必须随规格串过去，
	 * 否则客户端镜像跑的是另一套气路（本仓最恨的一类现场）。
	 */
	public String encode() {
		final StringBuilder targets = new StringBuilder();
		for (int i = 0; i < targetsBar.length; i++) {
			if (i > 0) {
				targets.append(',');
			}
			targets.append(trim(targetsBar[i]));
		}
		return "PB" + ',' + trim(nominalBar) + ',' + trim(chargedBar) + ',' + trim(fullServiceBar) + ',' + trim(emergencyBar)
			+ ',' + targets + ',' + trim(chargeBarPerSecond) + ',' + trim(dischargeBarPerSecond)
			+ ',' + trim(cylinderMaxBar) + ',' + trim(cylinderSpringBar) + ',' + trim(cylinderEmergencyBar)
			+ ',' + trim(cylinderApplyBarPerSecond) + ',' + trim(cylinderReleaseBarPerSecond) + ',' + trim(emergencyCylinderBarPerSecond)
			+ ',' + trim(distributorRatio) + ',' + trim(distributorSensitivityBar)
			+ ',' + (padFadeEnabled ? 1 : 0) + ',' + trim(padMu0) + ',' + trim(padMuSlopePerKmh) + ',' + trim(padMuFloor)
			+ ',' + (blendingEnabled ? 1 : 0) + ',' + (wspEnabled ? 1 : 0) + ',' + trim(wheelSlipMu)
			+ ',' + trim(pipePropagationPerSecond);
	}

	/** 解 {@link #encode()}；残缺/坏串返回 null（调用方退回旧模型，绝不让客户端起不来）。 */
	public static PneumaticBrakeSpec decode(String text) {
		if (text == null || text.isEmpty()) {
			return null;
		}
		final String[] parts = text.split(",");
		// PB + 4 + 至少 1 个级位 + 18 个标量
		if (parts.length < 24 || !"PB".equals(parts[0])) {
			return null;
		}
		try {
			// 级位表长度不定（档位数可变），从尾部倒着取标量
			final int scalarCount = 18;
			final int targetCount = parts.length - 1 - 4 - scalarCount;
			final double[] targets = new double[targetCount];
			for (int i = 0; i < targetCount; i++) {
				targets[i] = Double.parseDouble(parts[5 + i]);
			}
			int at = 5 + targetCount;
			return new PneumaticBrakeSpec(
				Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]), Double.parseDouble(parts[4]),
				targets,
				Double.parseDouble(parts[at++]), Double.parseDouble(parts[at++]),
				Double.parseDouble(parts[at++]), Double.parseDouble(parts[at++]), Double.parseDouble(parts[at++]),
				Double.parseDouble(parts[at++]), Double.parseDouble(parts[at++]), Double.parseDouble(parts[at++]),
				Double.parseDouble(parts[at++]), Double.parseDouble(parts[at++]),
				!"0".equals(parts[at++]), Double.parseDouble(parts[at++]), Double.parseDouble(parts[at++]), Double.parseDouble(parts[at++]),
				!"0".equals(parts[at++]), !"0".equals(parts[at++]), Double.parseDouble(parts[at++]), Double.parseDouble(parts[at])
			);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public double getNominalBar() { return nominalBar; }
	public double getChargedBar() { return chargedBar; }
	public double getFullServiceBar() { return fullServiceBar; }
	public double getEmergencyBar() { return emergencyBar; }
	public double getChargeBarPerSecond() { return chargeBarPerSecond; }
	public double getDischargeBarPerSecond() { return dischargeBarPerSecond; }
	public double getCylinderMaxBar() { return cylinderMaxBar; }
	public double getCylinderSpringBar() { return cylinderSpringBar; }
	public double getCylinderEmergencyBar() { return cylinderEmergencyBar; }
	public double getCylinderApplyBarPerSecond() { return cylinderApplyBarPerSecond; }
	public double getCylinderReleaseBarPerSecond() { return cylinderReleaseBarPerSecond; }
	public double getEmergencyCylinderBarPerSecond() { return emergencyCylinderBarPerSecond; }
	public double getDistributorRatio() { return distributorRatio; }
	public double getDistributorSensitivityBar() { return distributorSensitivityBar; }
	public boolean isPadFadeEnabled() { return padFadeEnabled; }
	public double getPadMu0() { return padMu0; }
	public double getPadMuSlopePerKmh() { return padMuSlopePerKmh; }
	public double getPadMuFloor() { return padMuFloor; }
	/** 电空混合（电优先、机械补缺、EP 只削不加）；关掉 = 旧行为（两者相加）。 */
	public boolean isBlendingEnabled() { return blendingEnabled; }
	/** WSP：超黏着时钳到上限（关掉则断崖到 {@link #getWheelSlipMu()}）。 */
	public boolean isWspEnabled() { return wspEnabled; }
	/** 抱死后的动摩擦系数（无 WSP 的断崖）。 */
	public double getWheelSlipMu() { return wheelSlipMu; }

	/**
	 * **闸片摩擦系数** μ_b(v)：未开启衰减时恒为 {@link #getPadMu0()}。
	 *
	 * <p>与 {@link BrakeSpec#padFrictionCoefficient} **同源**（两者都由同一份配置键构造，
	 * 见 {@code ConsistType}）：这里给"逐车各自的闸片口径"用（notes/270 的 {@code BrakeCar#spec}），
	 * 那边给整列等效口径与包线用。</p>
	 */
	public double padFrictionCoefficient(double speedMetersPerSecond) {
		if (!padFadeEnabled) {
			return padMu0;
		}
		return Math.max(padMuFloor, padMu0 - padMuSlopePerKmh * Math.max(0, speedMetersPerSecond) * 3.6);
	}

	/** 闸片摩擦衰减系数 κ(v) = μ_b(v)/μ_b(0)；未开启时恒为 1。 */
	public double frictionFactor(double speedMetersPerSecond) {
		return padFadeEnabled ? padFrictionCoefficient(speedMetersPerSecond) / padMu0 : 1;
	}

	/** 列车管沿车列传播的松弛速率（1/s，notes/268 P5 逐车管压）。 */
	public double getPipePropagationPerSecond() { return pipePropagationPerSecond; }
	public int getPositionCount() { return targetsBar.length; }

	/**
	 * **常用制动的归一化上限**（notes/270）：级位表里"全常用"那一档的位置比例。
	 *
	 * <p>表尾是**紧急/快排**级（{@code airPipeEmergencyBar}，比全常用还低）。它不该被"0…1 的连续制动诉求"
	 * 够到 —— 无级手柄推到底、货车最后一档都应该是**全常用**（用户口径：逐级递减到 3.5 bar），
	 * 紧急要由保护层显式给（{@link org.mtr.core.mmtr.brake.BrakeCommand#emergency}）。
	 * 有级/三手柄照旧按级位表逐位走：它们的档位本来就含紧急位，由各自的规格判定。</p>
	 *
	 * <p>默认表 {@code 5.2…3.6,3.5,3.0} ⇒ 返回 {@code 9/10 = 0.9}（比例 0.9 = 3.5 bar）。</p>
	 */
	public double getServiceDemandLimit() {
		final int count = targetsBar.length;
		if (count <= 1) {
			return 1;
		}
		for (int i = count - 1; i >= 0; i--) {
			if (targetsBar[i] >= fullServiceBar - 1e-9) {
				return (double) i / (count - 1);
			}
		}
		return 1;
	}

	/** 诊断：把某一档说成人话（日志/HUD 用）。 */
	public String describe(int brakeNotch) {
		return "管压目标 " + trim(targetPipeBar(brakeNotch)) + " bar（缸压上限 " + trim(cylinderMaxBar) + "）";
	}

	private static String trim(double value) {
		if (value == Math.rint(value)) {
			return String.valueOf((long) value);
		}
		return String.valueOf(Math.round(value * 1e6) / 1e6);
	}

	private static String getString(JsonObject json, String key, String fallback) {
		final JsonElement element = json.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsString();
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
