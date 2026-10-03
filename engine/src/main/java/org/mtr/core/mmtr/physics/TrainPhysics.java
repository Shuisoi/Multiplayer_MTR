package org.mtr.core.mmtr.physics;

/**
 * **一列车的纵向力学**（牛顿口径）：车列质量与回转质量系数 + 牵引能力 + 制动能力 + 运行阻力。
 *
 * <h2>这一层是"正向半"的唯一入口</h2>
 *
 * <p>控制器 / 自动巡航 / LZB / 停点包线都问它要加速度，而不是各自去乘一个加速度常数：</p>
 *
 * <pre>
 *   F_net  = F_牵引(手柄, v) − F_制动(缸压) − R(v) ± F_坡道
 *   a      = F_net / (λ · m)
 * </pre>
 *
 * <p>"反向半"（由还剩多少米反解所需减速度）在 {@link DynamicsEnvelope}，两者互为逆运算。</p>
 *
 * <h2>与旧模型的关系（迁移口径）</h2>
 *
 * <p>旧模型给的是 {@code A₀ / v_bp / 制动减速度 / 阻力 A,B,C}（全是加速度量纲）。取同一份数据、
 * 任选一个质量 {@code m₀} 令 {@code F_max = m₀·A₀}、{@code P = F_max·v_bp}、{@code F_制动 = m₀·a_制动}、
 * {@code A_N = m₀·A}… 再令参考质量恰为 {@code m₀} 时，**每一条曲线逐点相同** ——
 * 于是"换模型"与"改手感"可以分开做（notes/235 的迁移配方）。</p>
 *
 * <p>单位恒为 SI（N、kg、W、m/s、m/s²）；引擎内部 m/ms 由调用方换算。</p>
 */
public final class TrainPhysics {

	/** 标准重力加速度（m/s²）。 */
	public static final double GRAVITY = 9.81;

	private final double massKg;
	private final double rotatingMassFactor;
	private final TractionSpec traction;
	private final BrakeSpec brake;
	private final RunningResistanceSpec resistance;
	/** 轮轨黏着（规格 §4）：牵引力的物理上限是 μ_eff · N，不是电机能给多少。 */
	private final AdhesionSpec adhesion;

	public TrainPhysics(double massKg, double rotatingMassFactor, TractionSpec traction, BrakeSpec brake, RunningResistanceSpec resistance) {
		this(massKg, rotatingMassFactor, traction, brake, resistance, AdhesionSpec.DRY);
	}

	public TrainPhysics(double massKg, double rotatingMassFactor, TractionSpec traction, BrakeSpec brake, RunningResistanceSpec resistance, AdhesionSpec adhesion) {
		this.massKg = Math.max(1, massKg);
		// λ < 1 物理上不存在（回转质量只让惯性更大）；给 1 兜底，配置该被校验时由上层报出来。
		this.rotatingMassFactor = Math.max(1, rotatingMassFactor);
		this.traction = traction;
		this.brake = brake;
		this.resistance = resistance;
		this.adhesion = adhesion == null ? AdhesionSpec.DRY : adhesion;
	}

	public double getMassKg() { return massKg; }
	public double getRotatingMassFactor() { return rotatingMassFactor; }
	public TractionSpec getTraction() { return traction; }
	public BrakeSpec getBrake() { return brake; }
	public RunningResistanceSpec getResistance() { return resistance; }
	public AdhesionSpec getAdhesion() { return adhesion; }

	/** 惯性质量 {@code λ·m}：牛顿 → 加速度时除的就是它。 */
	public double effectiveMassKg() {
		return rotatingMassFactor * massKg;
	}

	/** 法向正压力 {@code N = m · g}（整列重量）——黏着上限用的就是它。 */
	public double normalForceN() {
		return massKg * GRAVITY;
	}

	/** 黏着能传下去的最大轮周力（N）：{@code μ_eff · m · g}。 */
	public double adhesionLimitedEffortN() {
		return adhesion.maxTractiveEffortN(normalForceN());
	}

	/**
	 * 牵引力（牛顿）：**电机能给多少**与**轮轨能传多少**取小。
	 *
	 * <p>注：这一版只做"力被黏着上限截住"（于是湿轨/落叶上满手柄也拿不到 300 kN）；
	 * 轮对角速度状态、蠕滑率与 ASG（锁 1.5%–2.5% 滑差）在下一步 —— 那时才会真的看到轮子空转。</p>
	 */
	public double tractiveEffortN(double ratio, double speedMetersPerSecond) {
		return Math.min(traction.effortN(ratio, speedMetersPerSecond), adhesionLimitedEffortN());
	}

	/** 运行阻力（牛顿，恒为正的阻力大小）。 */
	public double resistanceForceN(double speedMetersPerSecond) {
		return resistance.forceN(speedMetersPerSecond);
	}

	/**
	 * **制动力的黏着上限**（N，notes/267，规格模块四）：{@code μ_brake(v)·m·g}。
	 *
	 * <p>轮轨能传下去的最大制动力就这么多 —— 超过它的部分不管司机拉多少档都传不到轨面上。
	 * 用的是 Curtius-Kniffler 的**制动**曲线（与牵引侧的峰值黏着不是同一条），天气/落叶/撒砂
	 * 仍通过 {@link AdhesionSpec#usableMuMax()} 取小生效。</p>
	 */
	public double brakingAdhesionLimitN(double speedMetersPerSecond) {
		return adhesion.brakingLimitN(normalForceN(), speedMetersPerSecond);
	}

	/**
	 * **制动合力受黏着截断后的实际值**（N，notes/267）。
	 *
	 * <ul>
	 *   <li>没超限 ⇒ 原样返回；</li>
	 *   <li>超限且**有 WSP**（默认）⇒ 钳到 {@link #brakingAdhesionLimitN}（真车点刹保持峰值微滑）；</li>
	 *   <li>超限且**没 WSP** ⇒ 断崖到动摩擦 {@code wheelSlipMu·m·g}（车轮抱死，力反而更小）。</li>
	 * </ul>
	 *
	 * @param pneumaticForceN 气制动力（N）
	 * @param rheostaticForceN 电制动力（N）
	 * @param wspEnabled      WSP 开关
	 * @param wheelSlipMu     抱死后的动摩擦系数（无 WSP 时用）
	 */
	public double adhesionLimitedBrakingForceN(double pneumaticForceN, double rheostaticForceN, double speedMetersPerSecond,
		boolean wspEnabled, double wheelSlipMu) {
		final double demandN = Math.max(0, pneumaticForceN) + Math.max(0, rheostaticForceN);
		final double limitN = brakingAdhesionLimitN(speedMetersPerSecond);
		if (demandN <= limitN) {
			return demandN;
		}
		return wspEnabled ? limitN : Math.min(limitN, Math.max(0, wheelSlipMu) * normalForceN());
	}

	/** 这一拍是不是**被黏着截断**了（诊断/HUD 标记用）。 */
	public boolean isBrakingAdhesionLimited(double pneumaticForceN, double rheostaticForceN, double speedMetersPerSecond) {
		return Math.max(0, pneumaticForceN) + Math.max(0, rheostaticForceN) > brakingAdhesionLimitN(speedMetersPerSecond);
	}

	/** 坡道附加力（牛顿，**正 = 帮助前进**）：{@code i} 是千分坡度（上坡为正数，返回负值）。 */
	public double gradeForceN(double gradientPermille) {
		return -effectiveMassKg() * 9.80665 * gradientPermille / 1000.0;
	}

	/**
	 * **合成净加速度**（m/s²，牵引为正）：{@code 牵引力 − 气制动力 − 电阻制动力 − 运行阻力}，一次算清。
	 *
	 * <h2>为什么需要它（notes/265 牵引力增速控制）</h2>
	 *
	 * <p>牵引力从 2026-09-25 起**不再"要么满、要么零"**：它按增速上限（缺省 30 kN/s）爬升与回落。
	 * 于是"正在回落的残余牵引"与"正在建立的制动力"会在同一拍同时存在 —— 此时**四条力必须同号相加**，
	 * 否则残余牵引会被整段丢掉（等于凭空多出能量：车带着满牵引却没有牵引做的功）。旧的写法是先判
	 * 分支（牵引 / 制动 / 惰行）再只算那一支，只在"牵引必为 0 或制动必为 0"时才对。</p>
	 *
	 * <p>增速关闭（速率 0 ⇒ 一拍到位）时，这个式子与旧的三支逐一等价（见
	 * {@link #tractionAccelerationMps2} 与 {@link #brakingDecelerationMps2} 的委托）。</p>
	 *
	 * @param tractiveEffortN 当前**真正施加**的轮周牵引力（N，负值按 0）
	 * @param pneumaticForceN 气制动力（N，负值按 0）
	 * @param rheostaticForceN 电阻/电制动力（N，负值按 0）
	 */
	public double netAccelerationMps2(double tractiveEffortN, double pneumaticForceN, double rheostaticForceN, double speedMetersPerSecond) {
		return netAccelerationMps2(tractiveEffortN, Math.max(0, pneumaticForceN) + Math.max(0, rheostaticForceN), speedMetersPerSecond);
	}

	/**
	 * **合成净加速度**（m/s²，牵引为正）：{@code (牵引力 − 制动力 − 运行阻力) / λm}。
	 *
	 * <p>制动力已经合成为**一个数**（notes/267 起是"气 + 电"再经黏着截断的结果），所以这个入口只收一个
	 * {@code brakingForceN} —— 截断是整列车的事，不能分别截气与电。</p>
	 */
	public double netAccelerationMps2(double tractiveEffortN, double brakingForceN, double speedMetersPerSecond) {
		return (Math.max(0, tractiveEffortN) - Math.max(0, brakingForceN)
			- resistanceForceN(speedMetersPerSecond)) / effectiveMassKg();
	}

	/**
	 * **牵引净加速度**（m/s²，已减运行阻力）：控制器直接用它（notes/234 起旧 {@code MmtrPhysics} 已删除），
	 * 控制器直接用它。
	 */
	public double tractionAccelerationMps2(double ratio, double speedMetersPerSecond) {
		return netAccelerationMps2(tractiveEffortN(ratio, speedMetersPerSecond), 0, 0, speedMetersPerSecond);
	}

	/** **常用制动减速度大小**（m/s²，已加运行阻力）：缸压比例 {@code cylinderPressure}（0..1）。 */
	public double serviceBrakeDecelerationMps2(double cylinderPressure, double speedMetersPerSecond) {
		return (brake.serviceForceN(cylinderPressure, speedMetersPerSecond) + resistanceForceN(speedMetersPerSecond)) / effectiveMassKg();
	}

	/** **紧急制动减速度大小**（m/s²，已加运行阻力）。 */
	public double emergencyDecelerationMps2(double speedMetersPerSecond) {
		return (brake.emergencyForceN(speedMetersPerSecond) + resistanceForceN(speedMetersPerSecond)) / effectiveMassKg();
	}

	/**
	 * **合成制动减速度大小**（m/s²）：气制动力 + 电阻制动力 + 运行阻力，全部相加后除以惯性质量。
	 * 三手柄控制器用它把两根手柄的力合成一次（notes/235）。
	 */
	public double brakingDecelerationMps2(double pneumaticForceN, double rheostaticForceN, double speedMetersPerSecond) {
		return -netAccelerationMps2(0, pneumaticForceN, rheostaticForceN, speedMetersPerSecond);
	}

	/** **惰行减速度大小**（m/s²）：只剩运行阻力。 */
	public double coastDecelerationMps2(double speedMetersPerSecond) {
		return resistanceForceN(speedMetersPerSecond) / effectiveMassKg();
	}

	/**
	 * 平衡速度（m/s）：这个手柄比例下牵引力恰好等于阻力 —— 配置合理性的参考量
	 * （若平衡速度低于线路限速，说明这列车在这条线上跑不到限速，是数据问题而不是 bug）。
	 *
	 * @return 没有平衡点（无动力 / 阻力恒为 0）时返回 0
	 */
	public double balancingSpeedMps2(double ratio) {
		if (traction.effortN(ratio, 0) <= 0) {
			return 0;
		}
		double low = 0;
		double high = Math.max(1, traction.breakpointMetersPerSecond());
		// 先把上界撑到"牵引已经拉不过阻力"为止（平衡点可能在折点之外很远，尤其是阻力很小的时候）。
		for (int i = 0; i < 40 && high < 400 && tractiveEffortN(ratio, high) > resistanceForceN(high); i++) {
			high *= 1.5;
		}
		if (tractiveEffortN(ratio, high) > resistanceForceN(high)) {
			return 0; // 在 400 m/s 内都不平衡（阻力配得极小）：不假装有答案
		}
		// 折点以上 F 随 1/v 降、R 随 v² 涨 ⇒ 差值单调减，二分足够且与曲线形状无关。
		for (int i = 0; i < 80; i++) {
			final double mid = (low + high) / 2;
			if (tractiveEffortN(ratio, mid) - resistanceForceN(mid) > 0) {
				low = mid;
			} else {
				high = mid;
			}
		}
		return low;
	}
}
