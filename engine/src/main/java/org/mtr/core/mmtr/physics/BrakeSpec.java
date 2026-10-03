package org.mtr.core.mmtr.physics;

/**
 * **制动能力**：全常用制动力 + 全紧急制动力 + 「缸压(bar) → 轮周力」的换算（notes/266）。
 *
 * <h2>标定源（用户 2026-09-25 口径）</h2>
 *
 * <p>力的标定**不再手填牛顿**，而是由 **UIC 制动重率**反推（规格模块五）：</p>
 *
 * <pre>
 *   λ = 制动重量 / 车底质量 × 100%
 *   a = 0.0065·λ + 0.12              （纯制动平均减速度，m/s²）
 *   F = m_equiv · a = m·(1+ρ) · a
 * </pre>
 *
 * <p>BR 101：制动重量 120 t / 84 t ⇒ λ=142.9% ⇒ a=1.0486 ⇒ **F_常用 = 102.2 kN**；
 * R+E 168 t ⇒ λ=200% ⇒ a=1.42 ⇒ **F_紧急 = 138.4 kN**。显式写 {@code serviceBrakeForceN} 仍然优先
 * （特例与既有用例走那条路，见 {@code ConsistType.fromJson}）。</p>
 *
 * <h2>缸压 → 力：弹簧死区 + 闸片摩擦随速衰减</h2>
 *
 * <pre>
 *   F(P_bc, v) = F_service · max(0, P_bc − P_spring) / (P_bc_max − P_spring) · κ(v)
 *   κ(v) = μ_b(v)/μ_b(0) ,  μ_b(v) = clip(μ0 − k_v·v_kmh , μ_floor , μ0)
 * </pre>
 *
 * <p>模块一里那串几何常量（活塞面积 / 杠杆比 / 盘径比 / 闸片数）**折进 {@code F_service}**，
 * 不逐步建模 —— 逐步代出来的 211 kN 与铭牌 150 kN 差 1.4 倍（口径没对齐），
 * 详见 {@code docs/01-设计/制动系统-气压与制动力模型-设计.md} 附录 A。</p>
 *
 * <p>两个口径开关都是**随包配置**开的（{@code padFadeEnabled}、bar 气路键），不写就是旧行为 ——
 * 换模型与改手感分开做（notes/235 的纪律）。</p>
 */
public final class BrakeSpec {

	public static final BrakeSpec NONE = new BrakeSpec(0, 0);

	/** 出厂闸片摩擦系数（低速峰值）：粉末冶金/树脂基闸片 0.38–0.40。 */
	public static final double DEFAULT_PAD_MU0 = 0.39;
	/** 每 km/h 的摩擦系数衰减：0 km/h 0.390 → 200 km/h 0.300（对上规格模块一的 0.30–0.32）。 */
	public static final double DEFAULT_PAD_MU_SLOPE_PER_KMH = 0.00045;
	/** 摩擦系数下限（再快也不低于它）。 */
	public static final double DEFAULT_PAD_MU_FLOOR = 0.28;
	/** 出厂缸压上限（常用全制动）。 */
	public static final double DEFAULT_CYLINDER_MAX_BAR = 3.8;
	/** 出厂缸压复位弹簧（低于它活塞不动作）。 */
	public static final double DEFAULT_CYLINDER_SPRING_BAR = 0.30;

	private final double serviceForceN;
	private final double emergencyForceN;
	private final double cylinderMaxBar;
	private final double cylinderSpringBar;
	private final boolean padFadeEnabled;
	private final double padMu0;
	private final double padMuSlopePerKmh;
	private final double padMuFloor;

	/** 旧口径：只给两个力，缸压按比例线性换算、闸片不随速衰减。 */
	public BrakeSpec(double serviceForceN, double emergencyForceN) {
		this(serviceForceN, emergencyForceN, DEFAULT_CYLINDER_MAX_BAR, DEFAULT_CYLINDER_SPRING_BAR,
			false, DEFAULT_PAD_MU0, DEFAULT_PAD_MU_SLOPE_PER_KMH, DEFAULT_PAD_MU_FLOOR);
	}

	public BrakeSpec(double serviceForceN, double emergencyForceN, double cylinderMaxBar, double cylinderSpringBar,
		boolean padFadeEnabled, double padMu0, double padMuSlopePerKmh, double padMuFloor) {
		this.serviceForceN = Math.max(0, serviceForceN);
		// 紧急制动至少不弱于常用制动（配置写反了也不会出现"EB 比常用还软"）
		this.emergencyForceN = Math.max(Math.max(0, emergencyForceN), this.serviceForceN);
		this.cylinderMaxBar = Math.max(0.01, cylinderMaxBar);
		this.cylinderSpringBar = Math.max(0, Math.min(this.cylinderMaxBar, cylinderSpringBar));
		this.padFadeEnabled = padFadeEnabled;
		this.padMu0 = padMu0 > 0 ? padMu0 : DEFAULT_PAD_MU0;
		this.padMuSlopePerKmh = Math.max(0, padMuSlopePerKmh);
		this.padMuFloor = Math.max(0, Math.min(this.padMu0, padMuFloor));
	}

	/**
	 * **UIC 制动重率反推**（规格模块五，用户 2026-09-25 定为此为标定源）。
	 *
	 * @param brakeWeightTonnes          常用制动重量（车辆铭牌，t；BR101 = 120）
	 * @param emergencyBrakeWeightTonnes 紧急制动重量（R+E，t；BR101 = 168）
	 */
	public static BrakeSpec fromBrakeWeight(double massKg, double rotatingMassFactor,
		double brakeWeightTonnes, double emergencyBrakeWeightTonnes, double cylinderMaxBar, double cylinderSpringBar,
		boolean padFadeEnabled, double padMu0, double padMuSlopePerKmh, double padMuFloor) {
		final double inertiaKg = Math.max(1, massKg) * Math.max(1, rotatingMassFactor);
		final double serviceT = Math.max(0, brakeWeightTonnes);
		final double emergencyT = Math.max(serviceT, emergencyBrakeWeightTonnes);
		return new BrakeSpec(
			inertiaKg * uicDecelerationMps2(serviceT, massKg), inertiaKg * uicDecelerationMps2(emergencyT, massKg),
			cylinderMaxBar, cylinderSpringBar, padFadeEnabled, padMu0, padMuSlopePerKmh, padMuFloor
		);
	}

	/** UIC 平均纯制动减速度（m/s²）：{@code a = 0.0065·λ + 0.12}，λ = 制动重量/车底质量 × 100%。 */
	public static double uicDecelerationMps2(double brakeWeightTonnes, double massKg) {
		if (massKg <= 0) {
			return 0;
		}
		final double lambda = brakeWeightTonnes / (massKg / 1000.0) * 100.0;
		return 0.0065 * lambda + 0.12;
	}

	public double getServiceForceN() { return serviceForceN; }
	public double getEmergencyForceN() { return emergencyForceN; }
	public double getCylinderMaxBar() { return cylinderMaxBar; }
	public double getCylinderSpringBar() { return cylinderSpringBar; }
	public boolean isPadFadeEnabled() { return padFadeEnabled; }
	public double getPadMu0() { return padMu0; }
	public double getPadMuSlopePerKmh() { return padMuSlopePerKmh; }
	public double getPadMuFloor() { return padMuFloor; }

	/** 旧口径：常用制动力（牛顿），缸压**比例** {@code 0..1} 线性给（不做弹簧/衰减）。 */
	public double serviceForceN(double cylinderPressure) {
		return serviceForceN * Math.max(0, Math.min(1, cylinderPressure));
	}

	/** 常用制动力（牛顿）：比例 + 闸片衰减（{@code padFadeEnabled=false} 时与 {@link #serviceForceN(double)} 相同）。 */
	public double serviceForceN(double cylinderPressure, double speedMetersPerSecond) {
		return serviceForceN * Math.max(0, Math.min(1, cylinderPressure)) * frictionFactor(speedMetersPerSecond);
	}

	/**
	 * **缸压(bar) → 常用制动力**（牛顿）：扣缸簧死区、按缸压上限饱和、再乘闸片衰减。
	 *
	 * @param cylinderBar 制动缸表压（bar）
	 * @param speedMetersPerSecond 当前速度（算闸片摩擦用）
	 */
	public double serviceForceNFromCylinderBar(double cylinderBar, double speedMetersPerSecond) {
		final double usableBar = Math.max(0, Math.min(1,
			(cylinderBar - cylinderSpringBar) / (cylinderMaxBar - cylinderSpringBar)));
		return serviceForceN * usableBar * frictionFactor(speedMetersPerSecond);
	}

	/** 紧急制动力（牛顿）：旧口径（不衰减）。 */
	public double emergencyForceN() {
		return emergencyForceN;
	}

	/** 紧急制动力（牛顿）：带闸片衰减（{@code padFadeEnabled=false} 时与 {@link #emergencyForceN()} 相同）。 */
	public double emergencyForceN(double speedMetersPerSecond) {
		return emergencyForceN * frictionFactor(speedMetersPerSecond);
	}

	/** 闸片摩擦随速衰减系数 κ(v) = μ_b(v)/μ_b(0)；未开启时恒为 1。 */
	public double frictionFactor(double speedMetersPerSecond) {
		if (!padFadeEnabled) {
			return 1;
		}
		return padFrictionCoefficient(speedMetersPerSecond) / padMu0;
	}

	/** 闸片摩擦系数 μ_b(v)（未开启衰减时恒为 {@code padMu0}）。 */
	public double padFrictionCoefficient(double speedMetersPerSecond) {
		if (!padFadeEnabled) {
			return padMu0;
		}
		final double vKmh = Math.max(0, speedMetersPerSecond) * 3.6;
		return Math.max(padMuFloor, padMu0 - padMuSlopePerKmh * vKmh);
	}

	/** 这个车底配了制动能力吗（没配的话 `MmtrComposition` 应当报出来，而不是静默变成"刹不住"）。 */
	public boolean hasBrakes() {
		return serviceForceN > 0 || emergencyForceN > 0;
	}
}
