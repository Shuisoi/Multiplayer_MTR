package org.mtr.core.mmtr;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;
import org.mtr.core.mmtr.physics.CouplerSpec;
import org.mtr.core.mmtr.physics.BrakeSpec;
import org.mtr.core.mmtr.physics.PneumaticBrakeSpec;
import org.mtr.core.mmtr.physics.RunningResistanceSpec;
import org.mtr.core.mmtr.physics.TractionSpec;
import org.mtr.core.mmtr.physics.TrainPhysics;

/**
 * ConsistType ("车底/编组类")：描述一列车**怎么被操纵**与它的**纵向力学**，由服务端 JSON 载入
 * （不进存档）。
 *
 * <h2>力学参数是"力"，不是"加速度"（notes/235）</h2>
 *
 * <p>旧的 6 个加速度常数（{@code tractionAccelerationMps2 / serviceBrakeDecelerationMps2 /
 * emergencyDecelerationMps2 / tractionBreakpointKmh / resistanceA,B,C}）已**删除**：
 * 它们把"质量多少、装机功率多少"这些真车就有的量从模型里抹掉了，多挂一节车谁都算不出手感该变什么。
 * 现在这里是 {@link TractionSpec}（牵引力/功率）+ {@link BrakeSpec}（制动力）+
 * {@link RunningResistanceSpec}（牛顿阻力）+ 车列质量与回转质量系数 λ，
 * 由 {@link TrainPhysics} 合成为加速度（正向半）—— 包线（反向半）在
 * {@code org.mtr.core.mmtr.physics.DynamicsEnvelope}。</p>
 *
 * <h2>配置（{@code consist-types.json}）</h2>
 *
 * <pre>
 * { "id": "br101_three_handle", "controlMode": "THREE_HANDLE",
 *   "massKg": 82000, "rotatingMassFactor": 1.06,
 *   "maxTractiveEffortN": 200000, "maxPowerW": 1900000,
 *   "serviceBrakeForceN": 120000, "emergencyBrakeForceN": 200000,
 *   "resistanceAN": 1500, "resistanceBN": 20, "resistanceCN": 3.0 }
 * </pre>
 *
 * <p>旧键**不再被读取**；一旦出现，{@link #fromJson} 会打一条状态类日志点名（不静默）。</p>
 */
public final class ConsistType {

	public enum ControlMode { DEFAULT, NOTCHED, STEPLESS, AIR_BRAKE, THREE_HANDLE }

	/**
	 * 配置缺失（没解析出车底）时的兜底力学：**MMTR 自己的一节通用车**，不是旧的 MTR 加减速常数。
	 * 60 t / λ1.06 / 100 kN / 600 kW / 制动 60-90 kN / 阻力 900+12v+1.8v²。
	 * 出现它意味着配置有问题，调用方必须**说出来**（见 {@code Vehicle.mmtrPhysics()}）。
	 */
	public static final TrainPhysics FALLBACK_PHYSICS = new TrainPhysics(
		60_000, 1.06,
		new TractionSpec(100_000, 600_000),
		new BrakeSpec(60_000, 90_000),
		new RunningResistanceSpec(900, 12, 1.8)
	);

	/** 兜底车底的 id：**不是**旧 MTR 常数，是 MMTR 自己的一节通用车。 */
	public static final String FALLBACK_ID = "__mmtr_fallback__";

	/**
	 * 配置缺失（没解析出车底）时**真正跑起来的那份**：一节通用车（NOTCHED 手柄、无三手柄规格），
	 * 力学就是 {@link #FALLBACK_PHYSICS}。
	 *
	 * <p>为什么要有它（notes/235 §4）：删掉"没有车底类型 ⇒ 退回 MTR 常数"的退路之后，缺配置的车
	 * 不能变成"既没有牵引也没有制动"的死车 —— 那会让现场排查从"看日志"变成"猜为什么不动"。
	 * 这里给一份**能开、但明确不是真车**的物理，同时 {@code Vehicle} 必须把
	 * "本车没有车底配置，正在用通用车力学"打到日志里（限频）。{@link #isFallback()} 是判据。</p>
	 */
	public static final ConsistType FALLBACK = new ConsistType(
		FALLBACK_ID, "（缺配置：通用车）", ControlMode.NOTCHED, 7, 8, 120,
		60_000, 1.06, 100_000, 600_000, 60_000, 90_000, 900, 12, 1.8,
		0.37, false,
		0.1, 0.4, 0.15, 0.1, 0, null);

	/** 已删除的旧键：出现在配置里就点名（把"我按旧文档配的"变成一条看得见的日志）。 */
	private static final String[] REMOVED_KEYS = {
		"tractionAccelerationMps2", "serviceBrakeDecelerationMps2", "emergencyDecelerationMps2",
		"tractionBreakpointKmh", "resistanceA", "resistanceB", "resistanceC", "massRatio",
		"rheostaticBrakeDecelerationMps2"
	};

	private final String id;
	private final String name;
	private final ControlMode controlMode;
	private final int powerNotches;
	private final int brakeNotches;
	private final double maxSpeedKmh;
	private final double manualMaxSpeedKmh;
	private final double massKg;
	private final double rotatingMassFactor;
	/**
	 * **载重能力**（kg，notes/274 片 4）：车底给"能装多少"，车卡给"装了多少比例"。
	 *
	 * <p>逐车质量 {@code m = massKg + loadRatio × payloadKg}；载重**只影响质量**（用户口径 2026-09-26，
	 * 决定 2）——惯性、黏着法向力随之变，制动锚不动（空重车调整阀两段动作明确不做，见设计文档 §6）。</p>
	 */
	private final double payloadKg;
	/**
	 * **车钩口径**（notes/277 片 7）：自由间隙 / 多段刚度 / 迟滞阻尼。{@code null} = **刚性车列**
	 * （没有车钩这回事，逐位等于片 7 之前）。
	 *
	 * <p>折中版只建"机车 ↔ 车列"这一个钩（见 {@link org.mtr.core.mmtr.physics.CouplerDynamics}），
	 * 它取自**说话那节车**的车底（车头挂车列的那个钩）。</p>
	 */
	private final @Nullable CouplerSpec coupler;
	private final TractionSpec traction;
	private final BrakeSpec brake;
	private final RunningResistanceSpec resistance;
	/** 轮轨黏着（规格 §4）：牵引力上限 = μ_eff · m · g。 */
	private final org.mtr.core.mmtr.physics.AdhesionSpec adhesion;
	/** 上面这些的组合（构造一次；控制器/自动巡航/监控共用同一份）。 */
	private final TrainPhysics physics;
	private final double airPipeChargeRatePerSecond;
	private final double airPipeDischargeRatePerSecond;
	private final double airBrakeApplyRatePerSecond;
	private final double airBrakeReleaseRatePerSecond;
	/** 三手柄机车的操纵规格；只有 {@link ControlMode#THREE_HANDLE} 下存在。 */
	private final @Nullable ThreeHandleSpec handles;
	/**
	 * **制动系统口径**（气压/分配阀/闸片/黏着/混合，notes/266–270）：**任何操纵方式都能配** ——
	 * 它是车底的属性，不属于某一种手柄。没配（没写 bar 键）= 旧归一化模型。
	 *
	 * <p>三手柄车底上它与 {@code handles.getBrakes()} 是**同一个对象**（镜像串走 handles 那条路）；
	 * 有级/无级车底则只有这里（客户端不跑物理，不需要镜像它，见 notes/270）。</p>
	 */
	private final @Nullable PneumaticBrakeSpec brakes;

	/**
	 * 灯光开关**有没有"关闭"这一档**（车底配置 {@code lightSwitch: "LOCO"|"MU"}，缺省 {@code "MU"}）。
	 *
	 * <p>机车多一档"关闭"是为了"连挂时把被挂那一端的尾灯灭掉"；动车组是三档（尾 / 日 / 夜）。
	 * 判据与档位循环全在 {@link org.mtr.core.mmtr.MmtrLightSwitch}（纯函数 + 真值表）。</p>
	 */
	private final boolean lightOffPosition;

	public ConsistType(String id, String name, ControlMode controlMode, int powerNotches, int brakeNotches,
		double maxSpeedKmh, double massKg, double rotatingMassFactor,
		double maxTractiveEffortN, double maxPowerW, double serviceBrakeForceN, double emergencyBrakeForceN,
		double resistanceAN, double resistanceBN, double resistanceCN,
		double adhesionMuMax, boolean sanding,
		double airPipeChargeRatePerSecond, double airPipeDischargeRatePerSecond,
		double airBrakeApplyRatePerSecond, double airBrakeReleaseRatePerSecond,
		double manualMaxSpeedKmh, @Nullable ThreeHandleSpec handles) {
		this(id, name, controlMode, powerNotches, brakeNotches, maxSpeedKmh, massKg, rotatingMassFactor,
			maxTractiveEffortN, maxPowerW, serviceBrakeForceN, emergencyBrakeForceN,
			resistanceAN, resistanceBN, resistanceCN, adhesionMuMax, sanding,
			airPipeChargeRatePerSecond, airPipeDischargeRatePerSecond, airBrakeApplyRatePerSecond, airBrakeReleaseRatePerSecond,
			manualMaxSpeedKmh, handles, handles == null ? null : handles.getBrakes());
	}

	/**
	 * 镜像/轻量构造：只要手柄规格 + 灯光开关档数（客户端镜像 {@code createMirrorConsistTypeFromSync}
	 * 与用例走它，见 notes/352）。
	 */
	public ConsistType(String id, String name, ControlMode controlMode, int powerNotches, int brakeNotches,
		double maxSpeedKmh, double massKg, double rotatingMassFactor,
		double maxTractiveEffortN, double maxPowerW, double serviceBrakeForceN, double emergencyBrakeForceN,
		double resistanceAN, double resistanceBN, double resistanceCN,
		double adhesionMuMax, boolean sanding,
		double airPipeChargeRatePerSecond, double airPipeDischargeRatePerSecond,
		double airBrakeApplyRatePerSecond, double airBrakeReleaseRatePerSecond,
		double manualMaxSpeedKmh, @Nullable ThreeHandleSpec handles, boolean lightOffPosition) {
		this(id, name, controlMode, powerNotches, brakeNotches, maxSpeedKmh, massKg, rotatingMassFactor,
			maxTractiveEffortN, maxPowerW, serviceBrakeForceN, emergencyBrakeForceN,
			resistanceAN, resistanceBN, resistanceCN, adhesionMuMax, sanding,
			airPipeChargeRatePerSecond, airPipeDischargeRatePerSecond, airBrakeApplyRatePerSecond, airBrakeReleaseRatePerSecond,
			manualMaxSpeedKmh, handles, handles == null ? null : handles.getBrakes(), 0, null, lightOffPosition);
	}

	/**
	 * @param brakes 制动系统口径（notes/270）：与操纵方式无关，见 {@link #getBrakes()}。
	 */
	public ConsistType(String id, String name, ControlMode controlMode, int powerNotches, int brakeNotches,
		double maxSpeedKmh, double massKg, double rotatingMassFactor,
		double maxTractiveEffortN, double maxPowerW, double serviceBrakeForceN, double emergencyBrakeForceN,
		double resistanceAN, double resistanceBN, double resistanceCN,
		double adhesionMuMax, boolean sanding,
		double airPipeChargeRatePerSecond, double airPipeDischargeRatePerSecond,
		double airBrakeApplyRatePerSecond, double airBrakeReleaseRatePerSecond,
		double manualMaxSpeedKmh, @Nullable ThreeHandleSpec handles,
		@Nullable PneumaticBrakeSpec brakes) {
		this(id, name, controlMode, powerNotches, brakeNotches, maxSpeedKmh, massKg, rotatingMassFactor,
			maxTractiveEffortN, maxPowerW, serviceBrakeForceN, emergencyBrakeForceN,
			resistanceAN, resistanceBN, resistanceCN, adhesionMuMax, sanding,
			airPipeChargeRatePerSecond, airPipeDischargeRatePerSecond, airBrakeApplyRatePerSecond, airBrakeReleaseRatePerSecond,
			manualMaxSpeedKmh, handles, brakes, 0, null, false);
	}

	/**
	 * @param payloadKg **载重能力**（kg，notes/274 片 4）：{@code 0}（缺省）⇒ 逐位等于"没有载重这回事"的老口径。
	 * @param coupler   **车钩口径**（notes/277 片 7）：{@code null} = 刚性车列。
	 */
	public ConsistType(String id, String name, ControlMode controlMode, int powerNotches, int brakeNotches,
		double maxSpeedKmh, double massKg, double rotatingMassFactor,
		double maxTractiveEffortN, double maxPowerW, double serviceBrakeForceN, double emergencyBrakeForceN,
		double resistanceAN, double resistanceBN, double resistanceCN,
		double adhesionMuMax, boolean sanding,
		double airPipeChargeRatePerSecond, double airPipeDischargeRatePerSecond,
		double airBrakeApplyRatePerSecond, double airBrakeReleaseRatePerSecond,
		double manualMaxSpeedKmh, @Nullable ThreeHandleSpec handles,
		@Nullable PneumaticBrakeSpec brakes, double payloadKg, @Nullable CouplerSpec coupler) {
		this(id, name, controlMode, powerNotches, brakeNotches,
			maxSpeedKmh, massKg, rotatingMassFactor, maxTractiveEffortN, maxPowerW, serviceBrakeForceN, emergencyBrakeForceN,
			resistanceAN, resistanceBN, resistanceCN, adhesionMuMax, sanding, airPipeChargeRatePerSecond,
			airPipeDischargeRatePerSecond, airBrakeApplyRatePerSecond, airBrakeReleaseRatePerSecond,
			manualMaxSpeedKmh, handles, brakes, payloadKg, coupler, false);
	}

	/**
	 * @param lightOffPosition 灯光开关有没有"关闭"这一档（车底配置 {@code lightSwitch: "LOCO"}）。
	 *                         缺省（旧签名）走 {@code false} = 动车组三档，既有调用点逐位不变。
	 */
	public ConsistType(String id, String name, ControlMode controlMode, int powerNotches, int brakeNotches,
		double maxSpeedKmh, double massKg, double rotatingMassFactor,
		double maxTractiveEffortN, double maxPowerW, double serviceBrakeForceN, double emergencyBrakeForceN,
		double resistanceAN, double resistanceBN, double resistanceCN,
		double adhesionMuMax, boolean sanding,
		double airPipeChargeRatePerSecond, double airPipeDischargeRatePerSecond,
		double airBrakeApplyRatePerSecond, double airBrakeReleaseRatePerSecond,
		double manualMaxSpeedKmh, @Nullable ThreeHandleSpec handles,
		@Nullable PneumaticBrakeSpec brakes, double payloadKg, @Nullable CouplerSpec coupler, boolean lightOffPosition) {
		this.id = id;
		this.name = name;
		this.controlMode = controlMode;
		this.powerNotches = Math.max(1, powerNotches);
		this.brakeNotches = Math.max(1, brakeNotches);
		this.maxSpeedKmh = Math.max(1, maxSpeedKmh);
		this.manualMaxSpeedKmh = manualMaxSpeedKmh > 0 ? manualMaxSpeedKmh : this.maxSpeedKmh;
		this.massKg = Math.max(1, massKg);
		this.rotatingMassFactor = Math.max(1, rotatingMassFactor);
		this.payloadKg = Math.max(0, payloadKg);
		this.coupler = coupler;
		this.traction = new TractionSpec(maxTractiveEffortN, maxPowerW);
		/*
		 * 制动力的换算口径（notes/266/270）：气压/闸片规格是**车底的属性**（任何操纵方式都能配），
		 * 由构造器传进来。没有它 = 旧归一化模型（缸压比例线性、闸片不随速衰减）—— 换模型与改手感分开做。
		 */
		final PneumaticBrakeSpec pneumatic = brakes == null && handles != null ? handles.getBrakes() : brakes;
		this.brakes = pneumatic;
		this.brake = pneumatic == null
			? new BrakeSpec(serviceBrakeForceN, emergencyBrakeForceN)
			: new BrakeSpec(serviceBrakeForceN, emergencyBrakeForceN, pneumatic.getCylinderMaxBar(), pneumatic.getCylinderSpringBar(),
				pneumatic.isPadFadeEnabled(), pneumatic.getPadMu0(), pneumatic.getPadMuSlopePerKmh(), pneumatic.getPadMuFloor());
		this.resistance = new RunningResistanceSpec(resistanceAN, resistanceBN, resistanceCN);
		this.adhesion = new org.mtr.core.mmtr.physics.AdhesionSpec(adhesionMuMax, org.mtr.core.mmtr.physics.AdhesionSpec.DEFAULT_CRITICAL_SLIP, sanding);
		this.physics = new TrainPhysics(this.massKg, this.rotatingMassFactor, traction, brake, resistance, adhesion);
		this.airPipeChargeRatePerSecond = airPipeChargeRatePerSecond;
		this.airPipeDischargeRatePerSecond = airPipeDischargeRatePerSecond;
		this.airBrakeApplyRatePerSecond = airBrakeApplyRatePerSecond;
		this.airBrakeReleaseRatePerSecond = airBrakeReleaseRatePerSecond;
		this.handles = handles;
		this.lightOffPosition = lightOffPosition;
	}

	public String getId() { return id; }
	public String getName() { return name; }
	public ControlMode getControlMode() { return controlMode; }
	public int getPowerNotches() { return powerNotches; }
	public int getBrakeNotches() { return brakeNotches; }
	public double getMaxSpeedKmh() { return maxSpeedKmh; }
	public double getMaxSpeedMetersPerSecond() { return maxSpeedKmh / 3.6; }
	public double getManualMaxSpeedMetersPerSecond() { return manualMaxSpeedKmh / 3.6; }
	public org.mtr.core.mmtr.physics.AdhesionSpec getAdhesion() { return adhesion; }
	public double getMassKg() { return massKg; }
	public double getRotatingMassFactor() { return rotatingMassFactor; }

	/** **载重能力**（kg，notes/274 片 4）：车底给"能装多少"，车卡给"装了多少比例"。 */
	public double getPayloadKg() { return payloadKg; }

	/** **车钩口径**（notes/277 片 7）：{@code null} = 刚性车列（没有车钩这回事）。 */
	public @Nullable CouplerSpec getCoupler() { return coupler; }

	/** 这个载重比例下的**总质量**（kg）：{@code 整备 + 比例 × 载重能力}（比例夹在 0…1）。 */
	public double loadedMassKg(double loadRatio) {
		return massKg + Math.max(0, Math.min(1, loadRatio)) * payloadKg;
	}

	/** 这个载重比例下的**惯性质量** {@code λ·m}（不分配对象 —— 逐车求和那条路每 tick 都在跑）。 */
	public double effectiveMassForLoadKg(double loadRatio) {
		return rotatingMassFactor * loadedMassKg(loadRatio);
	}
	public TractionSpec getTraction() { return traction; }
	public BrakeSpec getBrake() { return brake; }
	public RunningResistanceSpec getResistance() { return resistance; }
	public double getAirPipeChargeRatePerSecond() { return airPipeChargeRatePerSecond; }
	public double getAirPipeDischargeRatePerSecond() { return airPipeDischargeRatePerSecond; }
	public double getAirBrakeApplyRatePerSecond() { return airBrakeApplyRatePerSecond; }
	public double getAirBrakeReleaseRatePerSecond() { return airBrakeReleaseRatePerSecond; }
	public @Nullable ThreeHandleSpec getHandles() { return handles; }

	/**
	 * **制动系统口径**（气压/分配阀/闸片/黏着/混合，notes/266–270）：与操纵方式无关的车底属性。
	 * {@code null} = 这份车底走旧归一化模型。
	 */
	public @Nullable PneumaticBrakeSpec getBrakes() { return brakes; }

	/**
	 * 灯光开关有没有"关闭"这一档（车底配置 {@code lightSwitch: "LOCO"}）。
	 * 客户端拿镜像里的 {@code mmtrLightLoco} 读同一个答案（它没有 consist-types.json）。
	 */
	public boolean hasMmtrLightOffPosition() { return lightOffPosition; }

	/** @return 这份车底是不是"配置缺失的兜底"（判据只有这一个：调用方一律用它，别比 name/牛顿数）。 */
	public boolean isFallback() { return FALLBACK_ID.equals(id); }

	/** 本车底的纵向力学（正向半的唯一入口）。 */
	public TrainPhysics getPhysics() { return physics; }

	/**
	 * **拖车兜底**（notes/247）：车厢没有"车型 → 车底"映射时，只借来"说话那节车"的**质量 / λ / 制动 / 阻力 / 气路**，
	 * **不借牵引**（{@code F_max = P = 0}），操纵语义与 id 原样保留（id 不变，调用方仍能按 id 认它）。
	 *
	 * <p>为什么必须有它：现场 BR 101 + 2×p1（p1 既没声明车底、{@code carTypeIds} 里也没有它）被当成
	 * **三台机车** —— 质量 252 t 只是变重，但牵引 900 kN / 19.2 MW、常用制动 450 kN 全是三倍，
	 * 实测起步约 3 m/s²、145 km/h 时仍有 1.5 m/s²（用户口径"加速度很不正常"）。凡"借来的车底"一律不给牵引：
	 * 一列 N 节同型车拿到 N 倍功率永远不会是想要的结果（真要 MU 就在 {@code carTypeIds} 里显式配）。</p>
	 */
	public ConsistType asHauledTrailer() {
		return new ConsistType(id, name + "（拖车兜底）", controlMode, powerNotches, brakeNotches,
			maxSpeedKmh, massKg, rotatingMassFactor, 0, 0,
			brake.getServiceForceN(), brake.getEmergencyForceN(),
			resistance.getAN(), resistance.getBN(), resistance.getCN(),
			adhesion.usableMuMax(), adhesion.isSanding(),
			airPipeChargeRatePerSecond, airPipeDischargeRatePerSecond, airBrakeApplyRatePerSecond, airBrakeReleaseRatePerSecond,
			manualMaxSpeedKmh, handles, null, payloadKg, coupler, lightOffPosition);
	}

	/**
	 * **把载重折进质量**（notes/274 片 4）：返回一份"整备质量已含载重、载重能力归零"的车底。
	 *
	 * <p>折进去而不是留着比例：{@code getMassKg()} 是日志、镜像（客户端按它跑同一份物理）与用例都在读的数，
	 * 留着比例会出现"表说 40 t、物理按 50 t 跑"。{@code loadRatio = 0} 时原样返回（零开销、零回归）。</p>
	 */
	public ConsistType withLoad(double loadRatio) {
		final double loaded = loadedMassKg(loadRatio);
		if (Math.abs(loaded - massKg) <= 1e-9) {
			return this;
		}
		return new ConsistType(id, name, controlMode, powerNotches, brakeNotches, maxSpeedKmh, loaded, rotatingMassFactor,
			traction.getMaxTractiveEffortN(), traction.getMaxPowerW(),
			brake.getServiceForceN(), brake.getEmergencyForceN(),
			resistance.getAN(), resistance.getBN(), resistance.getCN(),
			adhesion.usableMuMax(), adhesion.isSanding(),
			airPipeChargeRatePerSecond, airPipeDischargeRatePerSecond, airBrakeApplyRatePerSecond, airBrakeReleaseRatePerSecond,
			manualMaxSpeedKmh, handles, brakes, 0, coupler, lightOffPosition);
	}

	/** 这份车底自己能不能出力（牵引力或功率有一个非零就算）—— 选"说话的车"时用。 */
	public boolean canPull() {
		return traction.getMaxTractiveEffortN() > 0 || traction.getMaxPowerW() > 0;
	}

	public static ConsistType fromJson(JsonObject json) {
		final String modeText = getString(json, "controlMode", "DEFAULT").toUpperCase();
		ControlMode mode;
		try {
			mode = ControlMode.valueOf(modeText);
		} catch (IllegalArgumentException e) {
			mode = ControlMode.DEFAULT;
		}
		final String parsedId = getString(json, "id", "unnamed");
		warnAboutRemovedKeys(json, parsedId);
		/*
		 * 灯光开关档数（notes/352）：`lightSwitch` = "LOCO"（多一档"关闭"）/"MU"（三档，缺省）。
		 * 写错的值**点名**而不是静默按 MU 走 —— 与 REMOVED_KEYS 同一条纪律：配置错误必须看得见。
		 */
		final String lightSwitchText = getString(json, MmtrLightSwitch.JSON_KEY, MmtrLightSwitch.VALUE_MU);
		if (!MmtrLightSwitch.isKnownConfigValue(lightSwitchText)) {
			System.out.println("[MMTR-CFG] 车底 " + parsedId + " 的 " + MmtrLightSwitch.JSON_KEY + "='" + lightSwitchText
				+ "' 不认识（只认 " + MmtrLightSwitch.VALUE_LOCO + " / " + MmtrLightSwitch.VALUE_MU
				+ "）—— 按 " + MmtrLightSwitch.VALUE_MU + "（三档：尾灯/近光/远光）处理，见 " + MmtrLightSwitch.class.getSimpleName());
		}
		final boolean lightOffPosition = MmtrLightSwitch.parseOffPosition(lightSwitchText);
		final boolean threeHandle = mode == ControlMode.THREE_HANDLE;
		final double massKg = getDouble(json, "massKg", FALLBACK_PHYSICS.getMassKg());
		// λ 缺省取 1.0（"不修正回转质量"的中性值）：真车数据该显式写出来，不该被一个估计值悄悄改掉手感。
		final double rotatingMassFactor = getDouble(json, "rotatingMassFactor", 1.0);
		final double serviceBrakeForceN = resolveServiceBrakeForceN(json, parsedId, massKg, rotatingMassFactor);
		final double emergencyBrakeForceN = resolveEmergencyBrakeForceN(json, parsedId, massKg, rotatingMassFactor, serviceBrakeForceN);
		return new ConsistType(
			parsedId,
			getString(json, "name", ""),
			mode,
			// 三手柄车底的档位数就是手柄量程本身（±96 档 + 关闭；制动 11 个位置），缺省跟着模式走。
			getInt(json, "powerNotches", threeHandle ? ThreeHandleSpec.DRIVE_HANDLE_MAX : 7),
			getInt(json, "brakeNotches", threeHandle ? ThreeHandleSpec.DEFAULT_BRAKE_POSITIONS.length : 8),
			getDouble(json, "maxSpeedKmh", 120),
			massKg,
			rotatingMassFactor,
			getDouble(json, "maxTractiveEffortN", FALLBACK_PHYSICS.getTraction().getMaxTractiveEffortN()),
			getDouble(json, "maxPowerW", FALLBACK_PHYSICS.getTraction().getMaxPowerW()),
			serviceBrakeForceN,
			emergencyBrakeForceN,
			// 阻力缺省 0（"没写就没有"）：写一个凭空的阻力会让每一条既有曲线的量级都悄悄变一点。
			getDouble(json, "resistanceAN", 0),
			getDouble(json, "resistanceBN", 0),
			getDouble(json, "resistanceCN", 0),
			// 黏着（规格 §4）：干轨 0.37 / 湿轨 0.20 / 落叶 0.07；撒砂 ×1.30
			getDouble(json, "adhesionMuMax", 0.37),
			getBoolean(json, "sanding", false),
			getDouble(json, "airPipeChargeRatePerSecond", 0.1),
			getDouble(json, "airPipeDischargeRatePerSecond", 0.4),
			getDouble(json, "airBrakeApplyRatePerSecond", 0.15),
			getDouble(json, "airBrakeReleaseRatePerSecond", 0.1),
			getDouble(json, "manualMaxSpeedKmh", 0),
			// 电阻制动上限缺省跟随常用制动（不填也能跑）；只有三手柄模式才建手柄规格。
			threeHandle ? ThreeHandleSpec.fromJson(json, serviceBrakeForceN) : null,
			// notes/270：**制动系统口径与操纵方式无关** —— 任何模式写了 bar 键都能用新气压模型
			// （有级/无级车底也走它；三手柄车底与上面的 handles.getBrakes() 是同一个对象）。
			PneumaticBrakeSpec.fromJsonOrNull(json),
			// notes/274 片 4：**载重能力** —— 缺省 0（"没有载重这回事"，老配置逐位不变）。
			getDouble(json, "payloadKg", 0),
			// notes/277 片 7：**车钩口径** —— 缺省 null（刚性车列，逐位等于片 7 之前）。
			CouplerSpec.fromJsonOrNull(json),
			// notes/352：灯光开关档数（机车的"关闭"档）。
			lightOffPosition
		);
	}

	/**
	 * **常用制动力（N）的标定源**（notes/266，用户 2026-09-25 定为 UIC 制动重率）：
	 *
	 * <pre>
	 *   λ = brakeWeightTonnes / 车底质量 × 100%      a = 0.0065λ + 0.12      F = m_equiv · a
	 * </pre>
	 *
	 * <p>三条优先级写死：① 没有 {@code brakeWeightTonnes} ⇒ 用显式 {@code serviceBrakeForceN}（旧口径）；
	 * ② 有制动重量且**没**写显式力 ⇒ UIC 反推；③ 两者都写 ⇒ **显式牛顿优先**并点名（UIC 只作对账）
	 * —— 第三种是给"特例车"和既有用例留的出口，静默二选一是这个仓最恨的一类现场。</p>
	 */
	private static double resolveServiceBrakeForceN(JsonObject json, String id, double massKg, double rotatingMassFactor) {
		final double fallback = FALLBACK_PHYSICS.getBrake().getServiceForceN();
		final double brakeWeightTonnes = getDouble(json, "brakeWeightTonnes", 0);
		if (brakeWeightTonnes <= 0) {
			return getDouble(json, "serviceBrakeForceN", fallback);
		}
		final double derived = massKg * Math.max(1, rotatingMassFactor) * BrakeSpec.uicDecelerationMps2(brakeWeightTonnes, massKg);
		if (json.has("serviceBrakeForceN")) {
			final double explicit = getDouble(json, "serviceBrakeForceN", fallback);
			System.out.println("[MMTR-CFG] 车底 " + id + " 同时写了 serviceBrakeForceN=" + Math.round(explicit / 1000) + "kN 与 brakeWeightTonnes="
				+ brakeWeightTonnes + "t（UIC 反推 " + Math.round(derived / 1000) + "kN）—— **以显式牛顿为准**，UIC 只作对账（notes/266）");
			return explicit;
		}
		return derived;
	}

	/**
	 * **紧急制动力（N）**：与常用同一套口径。缺 {@code emergencyBrakeWeightTonnes} 时按 R+E/R = **1.4**
	 * 估（BR101：168/120 = 1.4）；旧口径（没有制动重量）仍按 {@code serviceBrakeForceN × 1.5}。
	 */
	private static double resolveEmergencyBrakeForceN(JsonObject json, String id, double massKg, double rotatingMassFactor, double serviceBrakeForceN) {
		final double brakeWeightTonnes = getDouble(json, "brakeWeightTonnes", 0);
		if (brakeWeightTonnes <= 0) {
			return getDouble(json, "emergencyBrakeForceN", serviceBrakeForceN * 1.5);
		}
		final double emergencyTonnes = getDouble(json, "emergencyBrakeWeightTonnes", brakeWeightTonnes * 1.4);
		final double derived = massKg * Math.max(1, rotatingMassFactor) * BrakeSpec.uicDecelerationMps2(emergencyTonnes, massKg);
		if (json.has("emergencyBrakeForceN")) {
			final double explicit = getDouble(json, "emergencyBrakeForceN", serviceBrakeForceN * 1.5);
			System.out.println("[MMTR-CFG] 车底 " + id + " 同时写了 emergencyBrakeForceN=" + Math.round(explicit / 1000) + "kN 与 emergencyBrakeWeightTonnes="
				+ emergencyTonnes + "t（UIC 反推 " + Math.round(derived / 1000) + "kN）—— **以显式牛顿为准**（notes/266）");
			return explicit;
		}
		return derived;
	}

	/**
	 * 旧键点名（限频由日志系统本身负责；一条车底只该配一次，刷屏风险很低）。
	 * 为什么要有它：删键之后"按旧文档配的车底"会静默退回缺省值 —— 那正是本仓最恨的一类现场。
	 */
	private static void warnAboutRemovedKeys(JsonObject json, String id) {
		final StringBuilder found = new StringBuilder();
		for (final String key : REMOVED_KEYS) {
			if (json.has(key)) {
				if (found.length() > 0) {
					found.append('、');
				}
				found.append(key);
			}
		}
		if (found.length() > 0) {
			System.out.println("[MMTR-CFG] 车底 " + id + " 用了已删除的旧加减速度键（" + found + "）—— 它们不再被读取，"
				+ "请改用 massKg / rotatingMassFactor / maxTractiveEffortN / maxPowerW / serviceBrakeForceN / emergencyBrakeForceN / "
				+ "resistanceAN / resistanceBN / resistanceCN（见 notes/235）");
		}
	}

	private static String getString(JsonObject json, String key, String fallback) {
		final JsonElement element = json.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsString();
	}

	private static int getInt(JsonObject json, String key, int fallback) {
		final JsonElement element = json.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsInt();
	}

	private static boolean getBoolean(JsonObject json, String key, boolean fallback) {
		final JsonElement element = json.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
	}

	private static double getDouble(JsonObject json, String key, double fallback) {
		final JsonElement element = json.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
	}
}
