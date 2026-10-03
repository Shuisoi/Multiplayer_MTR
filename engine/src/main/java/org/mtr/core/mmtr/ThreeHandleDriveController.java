package org.mtr.core.mmtr;

import org.mtr.core.mmtr.physics.PneumaticBrakeSpec;
import org.mtr.core.mmtr.physics.TrainPhysics;

/**
 * 三手柄机车控制器：**定速巡航（AFB） + 双向油门（牵引/电阻制动） + 气制动**（规格见
 * {@link ThreeHandleSpec} 与 docs/01-设计/驾驶输入与控制模型.md）。
 *
 * <h2>合成优先权（每拍从上到下第一条命中）</h2>
 *
 * <ol>
 *   <li>紧急（制动手柄 EB 位，或保护层给的 {@code emergency}）→ 紧急减速度，电阻制动立刻封锁，
 *       牵引按**增速上限**回落（notes/265：方向与保护动作都不豁免，回落期间残余牵引进合力）；</li>
 *   <li>气制动离开"运行"位 → 气制动生效，**AFB 暂停**（设定值保留，回"运行"位即恢复）；</li>
 *   <li>AFB 生效（定速 &gt; 0 且气制动在"运行"位）→ 由它调牵引/电阻制动，牵引受**油门手柄上限**约束；</li>
 *   <li>否则按油门手柄：正侧牵引、负侧电阻制动、中央惰行。</li>
 * </ol>
 *
 * <h2>两处刻意不做的事</h2>
 *
 * <ul>
 *   <li><b>电阻制动低速衰减后不自动补气</b>：真车部分车型会自动补，这里留给司机 —— 这正是"两根手柄要配合"的玩法；</li>
 *   <li><b>AFB 不碰气制动</b>：AFB 只调电力/电阻制动，减速到停由司机拉制动手柄。</li>
 * </ul>
 *
 * <h2>制动气压口径（notes/266）</h2>
 *
 * <p>车底带 {@link PneumaticBrakeSpec} 时（= 配置里写了 bar 键），气路走<b>真 bar 语义</b>：
 * 档位 → 列车管目标（5.2 / 4.6 / … / 3.5 bar）→ 充排气速率 → 分配阀 → 缸压（bar）→ 轮周力
 * （缸簧死区 + 闸片摩擦随速衰减）。没有这份规格的车底**逐位走旧的归一化模型**（`brakeRatios` +
 * 0..1 缸压），这就是零回归的开关。</p>
 *
 * <p>对外的管压/缸压读数仍然是 0..1（`getPipePressure()` = bar / 充风稳定值），
 * 另有 {@link #getPipeBar()} / {@link #getCylinderBar()} 给 HUD 与日志用。</p>
 *
 * <h2>牵引力增速控制（notes/265）</h2>
 *
 * <p>牵引力**上升与下降**都受一个斜率上限约束（{@link ThreeHandleSpec#getTractionRampNPerSecond()}，
 * 缺省 30 kN/s）—— 用来消掉"一推杆就满推力"的冲力。它落在**力**上而不是比例上（同一个比例的力在
 * 恒力矩段与恒功率段差好几倍），状态量是 {@link #getAppliedTractiveEffortN()}。</p>
 *
 * <p>两条后果写在明处：① 司机回杆/AFB 削力/拉闸/紧急都要按同一斜率回落，回落期间**残余牵引仍在推车**，
 * 所以合力由 {@link TrainPhysics#netAccelerationMps2} 四力同号相加（不再按分支丢掉残余牵引）；
 * ② 上一拍的力是**控制器状态**，客户端镜像从 0 起步（上车/重连后的头几秒与服务端略有出入）。配 0 = 不限速。</p>
 *
 * <p>气制动状态（管压/缸压）按 {@link ConsistType} 的建压/缓解速率演进，并通过
 * {@link AirBrakeStateful} 暴露给镜像种子 —— 与既有 {@link AirBrakeController} 同一套口径。</p>
 */
public final class ThreeHandleDriveController implements DriveController, AirBrakeStateful, BrakeCarrier {

	/** 紧急制动的排/建压速率（闸瓦/闸缸都按最快的来）。 */
	private static final double EMERGENCY_PIPE_RATE_PER_SECOND = 5.0;
	private static final double EMERGENCY_CYLINDER_RATE_PER_SECOND = 5.0;

	/**
	 * 缸压还剩**常用制动力**的这么多比例时，就认为"闸还压着"：这期间不许出牵引。
	 *
	 * <p>为什么要有这条（2026-09-23 现场：定速停不住、一直往上冲）：手制动柄回到"运行"之后
	 * 缸压要按 {@code airBrakeReleaseRatePerSecond} 慢慢排空（默认 0.25/s ⇒ 满缸压要 4 s）。
	 * 原来只看**手柄位置**判优先权，于是这 4 秒里"闸还满着、牵引已经满上"，而**残余制动力又被丢掉**
	 * （加速那一支只按牵引算）—— 实测 5 s 内从 50 km/h 冲到 72.7 km/h，AFB 还因为缸压没排空而
	 * 处于让位状态，一路冲到设定速度以上才收得住。真车有牵引联锁，不会允许这种组合。</p>
	 */
	private static final double PNEUMATIC_INTERLOCK_FORCE_RATIO = 0.01;

	private double pipePressure = 1.0;
	private double brakeCylinderPressure = 0.0;

	/**
	 * **制动模型宿主**（notes/270）：管压→分配阀→缸压→电空混合→逐车力，全部在 {@link org.mtr.core.mmtr.brake.BrakeModel}
	 * 里，**与操纵方式无关**（这个控制器只负责把三根手柄折成 {@link org.mtr.core.mmtr.brake.BrakeCommand}）。
	 *
	 * <p>它同时是**连挂接口**的载体：{@link #encodeAirState()} / {@link #applyAirStateString} /
	 * {@link #seedAirStateAfterCoupling} 让两列车合并/切分时逐车管压与缸压跟着走 —— 有级/无级控制器
	 * 挂的是同一件东西，所以这套接口对所有操纵方式都成立。</p>
	 *
	 * <p>没配气压口径的车底：模型不接管，本控制器走旧的归一化模型（零回归）。</p>
	 */
	private final org.mtr.core.mmtr.brake.BrakeModel brakeModel = new org.mtr.core.mmtr.brake.BrakeModel("三手柄");

	/** 手柄规格缺失时的退化控制器（有状态：它自己那份制动模型的管压/缸压要跨拍保留）。 */
	private final NotchedDriveController notchedFallback = new NotchedDriveController();

	/**
	 * 驱动延迟（一阶惯性）的**已施加**比例：杆位 → 轮周力之间有一个 τ（规格 §2：电气 50–150 ms）。
	 *
	 * <p>只滤"电传动"这一对比例（牵引 / 电阻制动）——气制动自己有建压/缓解速率，不重复滤波。
	 * 紧急制动**不滤**（真车 Schnellbremsung 是直接切最大力）。</p>
	 */
	private double appliedTractionRatio;
	private double appliedRheostaticRatio;

	/**
	 * 牵引力增速控制（notes/265）：**当前真正施加的轮周牵引力**（N，牵引为正）。
	 *
	 * <p>比例（{@code appliedTractionRatio}）与力<b>不是</b>一回事：同一个比例在恒力矩段与恒功率段
	 * 对应的力差着好几倍，而 30 kN/s 是**力**的口径。所以增速限制必须落在力上 —— 先把目标比例折成
	 * 目标力（{@link TrainPhysics#tractiveEffortN}），再把已施加的力朝它推进，一步最多
	 * {@code tractionRampNPerSecond × dt}。</p>
	 */
	private double appliedTractiveEffortN;
	/** 最近一拍**整列气制动力**（N）—— 镜像给客户端做 HUD 的「制动力（气）」（notes/269）。 */
	private double lastPneumaticBrakeForceN;

	// 最近一拍的实际输出比例（诊断/测试/HUD 用；不参与物理）
	private double lastTractionRatio;
	private double lastRheostaticRatio;
	private boolean lastAfbActive;

	@Override
	public DriveOutput compute(ControlState control, ConsistType type, double speedMetersPerSecond, long dtMillis) {
		final ThreeHandleSpec spec = type.getHandles();
		if (spec == null) {
			// 规格缺失（车型配错/镜像坏了）：退回有级控制器。绝不静默变成"车不动" —— 那是最难查的一类现场。
			// 退化实例要**留着**：有级控制器现在也持有制动模型（它的管压/缸压是有状态的）。
			return notchedFallback.compute(control, type, speedMetersPerSecond, dtMillis);
		}

		final double dt = Math.max(1, dtMillis) / 1000.0;
		final TrainPhysics physics = type.getPhysics();
		final double rampNPerSecond = spec.getTractionRampNPerSecond();
		final PneumaticBrakeSpec air = type.getBrakes();
		final int brakePosition = spec.clampBrakePosition(control.getBrakeNotch());
		final boolean emergency = control.isEmergency() || spec.isEmergencyPosition(brakePosition);

		if (emergency) {
			/*
			 * 紧急也走**同一个制动系统**：逐车快排 + 紧急限压 + 紧急力锚（notes/267/268/270）——
			 * 于是"尾车的紧急制动也滞后"这一条对所有编组都成立（单车就是"只有一节车"）。
			 */
			final double emergencyAnchorN;
			if (!brakeModel.step(air, equivalentBrakeCar(type), org.mtr.core.mmtr.brake.BrakeCommand.notched(brakePosition, spec.getBrakePositionCount(), 0, true),
					speedMetersPerSecond, 0, dt)) {
				stepAir(type, spec, 1.0, true, dt);
				emergencyAnchorN = physics.getBrake().emergencyForceN(speedMetersPerSecond);
			} else {
				emergencyAnchorN = brakeModel.getEmergencyForceN();
			}
			/*
			 * 紧急也按增速上限**回落**（用户口径 2026-09-25：「牵引力上或下…全部 30 kN/s」——
			 * 方向不豁免，保护动作也不豁免）。
			 *
			 * <p>代价写在明处：回落这几秒里牵引力**真的还在推车**，所以下面把残余牵引从紧急制动力里
			 * 减掉（{@link TrainPhysics#netAccelerationMps2}）。旧写法只算制动那一支，等于"牵引还在出力、
			 * 却不计入合力"，凭空多出能量。要立刻切零就把 {@code tractionRampNPerSecond} 配成 0（一拍到位）。</p>
			 */
			final double tractionForceN = rampTractionTowards(0, rampNPerSecond, dt);
			lastTractionRatio = ratioForEffortN(physics, tractionForceN, speedMetersPerSecond);
			lastRheostaticRatio = 0;
			lastAfbActive = false;
			// notes/267：紧急也过黏着截断（干轨/湿轨对 BR101 的 138 kN 不生效，落叶/油污才真的截）
			lastPneumaticBrakeForceN = emergencyAnchorN;
			final double limitedEmergencyN = air == null ? emergencyAnchorN
				: physics.adhesionLimitedBrakingForceN(emergencyAnchorN, 0, speedMetersPerSecond, air.isWspEnabled(), air.getWheelSlipMu());
			return new DriveOutput(
				physics.netAccelerationMps2(tractionForceN, limitedEmergencyN, speedMetersPerSecond),
				true, true, pipePressure, brakeCylinderPressure);
		}

		final int driveHandle = spec.clampDriveHandle(control.getDriveHandle());
		double tractionRatio = spec.tractionRatio(driveHandle);
		double rheostaticRatio = spec.rheostaticRatio(driveHandle);

		final double serviceForceN = type.getBrake().getServiceForceN();
		final double inertiaKg = physics.effectiveMassKg();

		/*
		 * AFB（用户 2026-09-23 最终口径，"按牵引力来看"）：
		 *   ① **动力手柄决定当前输出的力**（手动档位就是司机要的牵引力）；
		 *   ② **AFB 只能削减，不能提升功率**：`手柄 0 ⇒ 牵引 0` —— 上车手柄在关闭位时车**不许自己往前开**；
		 *   ③ 达到/超过设定速度时它**反向**：先上电阻制动，电阻制动不够的那部分**由它补气制动**；
		 *   ④ 补气**与气制动手柄耦合而不是合并**：谁要得多听谁的（取大），司机的手柄照旧管着自己那一份。
		 * 缸压没排空时牵引一律为 0（牵引联锁），这条同时也实现了"司机一拉闸就没有牵引"。
		 *
		 * <p>`afbUsesHandleAsCap=false` 是**非符合口径**的历史模式（AFB 自己加牵引，手柄只当参考）：
		 * 只剩"上车手柄关闭也要能定速"那一个用途，缺省已改为 true。</p>
		 */
		final double speedError = lastAfbTargetMps(control, spec) - speedMetersPerSecond;
		// AFB 只要定速 > 0 就一直在岗：司机拉气制动时它不再"让位"（用户 2026-09-23「与气制动手柄**耦合**」），
		// 出力由下面的牵引联锁与"取大"的缸压目标自己让路。
		final boolean afbOn = control.getCruiseSpeedKmh() > 0;
		double afbCylinderTarget = 0;
		if (afbOn) {
			if (speedError > spec.getAfbBrakeThresholdMps()) {
				/*
				 * 低于设定速度：只**削减**手柄那份力（`afbGainPerMps` 越大削得越晚、保速越硬）。
				 *
				 * <p>手柄在关闭/电阻制动侧 ⇒ 手柄那份力是 0 ⇒ **AFB 也给 0**：AFB 不提升功率
				 * （用户 2026-09-23：「AFB 只能限制功率，不能提升功率，为什么现在一上车手柄为 0 就会直接往前开」）。
				 * 起步必须由司机推手柄给力 —— 这是刻意的：定速的作用是**限速/保速**，不是自动驾驶起步。</p>
				 */
				final double afbDemand = Math.min(1, spec.getAfbGainPerMps() * speedError);
				final double handleTraction = spec.tractionRatio(driveHandle);
				tractionRatio = spec.isAfbUsesHandleAsCap() ? Math.min(handleTraction, afbDemand) : afbDemand;
				rheostaticRatio = spec.rheostaticRatio(driveHandle);
			} else if (speedError < -spec.getAfbBrakeThresholdMps()) {
				// ③ 反向：先电阻制动，再按"要的减速度"补气
				final double requiredDecelMps2 = spec.getAfbBrakeDecelPerMps() * -speedError;
				final double requiredForceN = requiredDecelMps2 * inertiaKg;
				tractionRatio = 0;
				rheostaticRatio = Math.max(spec.rheostaticRatio(driveHandle), 1.0);
				final double rheostaticForceAvailableN = spec.getRheostaticBrakeForceN() * spec.rheostaticFade(speedMetersPerSecond);
				final double pneumaticForceGapN = Math.max(0, requiredForceN - rheostaticForceAvailableN);
				afbCylinderTarget = Math.min(1, pneumaticForceGapN / serviceForceN);
				// 电阻制动只要它帮得上的那部分：缺口为 0 时别白给满电阻制动（否则会过冲下冲）
				rheostaticRatio = Math.max(spec.rheostaticRatio(driveHandle), Math.min(1, requiredForceN / Math.max(1, rheostaticForceAvailableN)));
			} else {
				// 带内：惰行（手柄若在电阻制动侧仍按司机的手柄施加）
				tractionRatio = 0;
			}
			lastAfbActive = true;
		} else {
			lastAfbActive = false;
		}

		/*
		 * **气压口径**（notes/266）：档位 → 列车管目标(bar) → 管压按充排气速率演进 → 分配阀（倍率/灵敏限）
		 * → 缸压按建/缓解速率追目标 → 力。旧口径（没有 bar 键的车底）走下面那支，逐位不变。
		 *
		 * <p>注意分配阀看的是**当前**管压（不是目标值）：真车也是管子先掉、缸压才建 —— 这就是"全常用
		 * 建压 ≈ 2 s 排风 + 2.9 s 建压 ≈ 5 s"的来源（规格 §5 的 3–6 s）。</p>
		 *
		 * <p>notes/267 起这里多了一层**电空混合**：管压那份是"司机诉求"，电制动先吃饱、机械补缺口，
		 * EP 阀**只削不加**（机械永不超过管压那份）—— 于是总力 = 司机诉求不变，只是分配随速度变。</p>
		 *
		 * <p>notes/270 起这一整段搬进了 {@link org.mtr.core.mmtr.brake.BrakeSystem}（与操纵方式无关）：
		 * 这里只把三根手柄折成 {@link org.mtr.core.mmtr.brake.BrakeCommand}。于是有级/无级/将来的 ATO
		 * 接同一份气路与力的逻辑；单车就是"只有一节车"的车列，不再有"单机一条路、编组另一条路"的分叉。</p>
		 */
		final double pneumaticForceN;
		boolean cylinderAboveInterlock;
		double blendElectricForceN = 0;
		// 电制动**可用力**（只有动力车那部分会被它替掉）；混合关掉就不给可用力 ⇒ 自动电制动不参与
		final double availableElectricN = air != null && air.isBlendingEnabled()
			? Math.max(0, spec.rheostaticEffortN(speedMetersPerSecond)) : 0;
		if (!brakeModel.step(air, equivalentBrakeCar(type),
				org.mtr.core.mmtr.brake.BrakeCommand.notched(brakePosition, spec.getBrakePositionCount(), afbCylinderTarget, false),
				speedMetersPerSecond, availableElectricN, dt)) {
			// 没配气压口径的车底：旧的归一化气路模型，逐位不变
			stepAir(type, spec, Math.max(spec.brakeRatio(brakePosition), afbCylinderTarget), false, dt);
			pneumaticForceN = type.getBrake().serviceForceN(brakeCylinderPressure);
			cylinderAboveInterlock = pneumaticForceN > type.getBrake().getServiceForceN() * PNEUMATIC_INTERLOCK_FORCE_RATIO;
		} else {
			// 车头进归一化读数（HUD/日志看司机那一节），联锁另看全列最大缸压
			syncReadingsFromModel();
			pneumaticForceN = brakeModel.getPneumaticForceN();
			blendElectricForceN = brakeModel.getBlendedElectricN();
			cylinderAboveInterlock = brakeModel.isPneumaticHolding();
		}
		/*
		 * 牵引联锁（两条都要）：
		 *   ① 手柄**要求**气制动（离开"运行"位）—— 刚拉到 8 档那一拍缸压还是 0，不能让它偷一会儿牵引；
		 *   ② 缸压还没排空（手柄已回"运行"、或 AFB 自己补的气）—— 残余缸压是真在刹车，见下面的注释。
		 * 逐车管压时 ② 按**全列最大缸压**判（notes/268）：尾车还压着闸的时候也不许牵引。
		 */
		final boolean pneumaticDemanded = brakePosition > ThreeHandleSpec.runningPosition();
		final boolean pneumaticHolding = pneumaticDemanded || cylinderAboveInterlock;
		/*
		 * 镜像给客户端做 HUD 的「制动力（气）」（notes/269）：**整列**的数（逐车求和），不是"车头缸压折算"——
		 * 电空混合把机车自己那份削掉之后，车头缸压反算会少掉拖车仍在出的那几十 kN。
		 */
		lastPneumaticBrakeForceN = pneumaticForceN;
		if (pneumaticHolding) {
			tractionRatio = 0;
		}

		lastTractionRatio = tractionRatio;
		lastRheostaticRatio = rheostaticRatio;

		/*
		 * 驱动延迟（规格 §2）：杆位 → 轮周力是一阶惯性，电气传动 τ ≈ 50–150 ms。
		 *
		 * <p>只对**力的建立**加 τ：**切除是保护动作、真车是快的**（切牵引/切电制动立刻生效）。
		 * 这条不对称很关键 —— 两边都加 τ 时，AFB 的"牵引↔电阻制动"切换会多出一段相位滞后，
		 * 实测把保速回路推成了极限环（定速 20 掉到 14 km/h、定速 100 掉到 91）。</p>
		 */
		final double tractionLagMillis = spec.getTractionLagMillis();
		if (tractionLagMillis > 0 && dtMillis < tractionLagMillis) {
			final double alpha = Math.max(0, dtMillis / tractionLagMillis);
			appliedTractionRatio = tractionRatio > appliedTractionRatio
				? appliedTractionRatio + (tractionRatio - appliedTractionRatio) * alpha
				: tractionRatio;
			appliedRheostaticRatio = rheostaticRatio > appliedRheostaticRatio
				? appliedRheostaticRatio + (rheostaticRatio - appliedRheostaticRatio) * alpha
				: rheostaticRatio;
			tractionRatio = appliedTractionRatio;
			rheostaticRatio = appliedRheostaticRatio;
			lastTractionRatio = tractionRatio;
			lastRheostaticRatio = rheostaticRatio;
		} else {
			appliedTractionRatio = tractionRatio;
			appliedRheostaticRatio = rheostaticRatio;
		}

		// 力必须在 AFB 定完比例之后再算 —— AFB 会改写 rheostaticRatio（先算就会用司机手柄那份，
		// 于是"定速该上电阻制动"变成"比例写了 1.0、力还是 0"）。
		// notes/266：电制动改走三段式（低速淡出 + 恒功率上限），只在气压口径的车底上生效。
		final double driverElectricForceN = air == null
			? spec.getRheostaticBrakeForceN() * rheostaticRatio * spec.rheostaticFade(speedMetersPerSecond)
			: spec.rheostaticEffortN(speedMetersPerSecond) * rheostaticRatio;
		/*
		 * notes/267：混合用的自动电制动与**司机电阻制动手柄**取大不相加 —— 电机只有一台：
		 * 司机拉着手柄、同时又拉气制动时，电制动不该被算两遍（规格模块三的口径）。
		 */
		final double rheostaticForceN = Math.max(driverElectricForceN, blendElectricForceN);
		if (air != null) {
			// HUD 的"电机"行由 lastRheostaticRatio 反算 ⇒ 必须把混合吃进去的那份也折进去，
			// 否则"电机"行会比"制动力（电）"小（两行对不上是本仓最恨的一类现场）。
			final double availableN = spec.rheostaticEffortN(speedMetersPerSecond);
			lastRheostaticRatio = availableN <= 0 ? 0 : Math.min(1, rheostaticForceN / availableN);
		}

		/*
		 * **牵引力增速控制**（notes/265，用户口径 2026-09-25：牵引力上或下都是 30 kN/s）。
		 *
		 * <p>把比例折成**力**再限斜率：30 kN/s 是力的口径，而同一个比例在恒力矩段与恒功率段对应的力
		 * 差着好几倍（比例限速会在高速上放大成上百 kN/s）。方向不豁免 —— 起步不再"一推杆就满推力"
		 * （这正是要消掉的冲力），司机回杆 / AFB 削力 / 牵引联锁也都按同一斜率回落。</p>
		 */
		final double targetTractiveEffortN = physics.tractiveEffortN(tractionRatio, speedMetersPerSecond);
		final double appliedTractiveEffortN = rampTractionTowards(targetTractiveEffortN, rampNPerSecond, dt);
		// 比例是**一次齐次**的（F(r,v) = r·F(1,v)），所以反解无损：HUD/日志/镜像看到的仍是"实际出力"。
		lastTractionRatio = ratioForEffortN(physics, appliedTractiveEffortN, speedMetersPerSecond);

		/*
		 * **合成的制动力**（notes/267）：气 + 电先合成**一个数**，再整体过黏着截断（规格模块四）——
		 * 截断是"整列车能传多少"的事，分别截气与电会把同一份黏着算两遍。
		 *
		 * <p>四条力同号相加（notes/265）：牵引增速控制让"残余牵引"与"正在建立的制动"能同时存在，
		 * 旧的三分支写法（牵引 / 制动 / 惰行 各算一支）会把残余牵引整段丢掉。</p>
		 */
		final double brakingForceN = air == null
			? pneumaticForceN + rheostaticForceN
			: physics.adhesionLimitedBrakingForceN(pneumaticForceN, rheostaticForceN, speedMetersPerSecond,
				air.isWspEnabled(), air.getWheelSlipMu());
		if (air != null && !air.isWspEnabled() && physics.isBrakingAdhesionLimited(pneumaticForceN, rheostaticForceN, speedMetersPerSecond)) {
			logWheelSlipOnce(speedMetersPerSecond, pneumaticForceN + rheostaticForceN, brakingForceN);
		}
		final double acceleration = physics.netAccelerationMps2(appliedTractiveEffortN, brakingForceN, speedMetersPerSecond);

		final boolean brakeLamp = brakeCylinderPressure > 0.01 || rheostaticForceN > 1;
		return new DriveOutput(acceleration, brakeLamp, false, pipePressure, brakeCylinderPressure);
	}

	/** 无 WSP 抱死断崖的自白（节流 2 s）：力突然掉到动摩擦那一档，必须说出来而不是让人猜为什么刹不住。 */
	private static long lastWheelSlipLogMillis;

	private static void logWheelSlipOnce(double speedMetersPerSecond, double demandN, double actualN) {
		final long now = System.currentTimeMillis();
		if (now - lastWheelSlipLogMillis < 2000) {
			return;
		}
		lastWheelSlipLogMillis = now;
		System.out.println("[MMTR-BRK] 黏着拉穿且无 WSP：车轮抱死，制动力 " + Math.round(demandN / 1000) + " kN → "
			+ Math.round(actualN / 1000) + " kN（动摩擦），速度 " + Math.round(speedMetersPerSecond * 3.6) + " km/h（notes/267）");
	}

	/**
	 * **力 → 缸压(bar)**（④的逆，含缸簧与闸片衰减）：电空混合时"机械补缺口"要用它。
	 *
	 * <pre>
	 *   F = F锚 · (P_bc − P_spring)/(P_max − P_spring) · κ(v)   ⇒   P_bc = P_spring + (F/(F锚·κ)) · (P_max − P_spring)
	 * </pre>
	 */
	private static double cylinderBarForForce(org.mtr.core.mmtr.physics.BrakeSpec brake, double forceN, double speedMetersPerSecond) {
		if (forceN <= 0) {
			// 一点力都不要 ⇒ EP 阀把缸压放空（不是"停在缸簧那 0.3 bar"：那会显示成"还在压着闸"）
			return 0;
		}
		final double fullForceN = brake.getServiceForceN() * brake.frictionFactor(speedMetersPerSecond);
		final double ratio = fullForceN <= 0 ? 1 : Math.max(0, Math.min(1, forceN / fullForceN));
		return brake.getCylinderSpringBar() + ratio * (brake.getCylinderMaxBar() - brake.getCylinderSpringBar());
	}

	/**
	 * 牵引力增速控制（notes/265）：把**已施加的轮周牵引力**朝目标推进，一步最多走
	 * {@code rampNPerSecond × dt}（上升与下降同一个上限）。
	 *
	 * @param targetEffortN  目标轮周牵引力（N）
	 * @param rampNPerSecond 增速上限（N/s）；{@code <= 0} = 不限速（一拍到位，旧口径）
	 * @return 这一拍**真正施加**的牵引力（N）
	 */
	private double rampTractionTowards(double targetEffortN, double rampNPerSecond, double dt) {
		final double maxStep = rampNPerSecond * dt;
		if (maxStep <= 0) {
			appliedTractiveEffortN = Math.max(0, targetEffortN);
		} else {
			final double delta = targetEffortN - appliedTractiveEffortN;
			appliedTractiveEffortN += Math.max(-maxStep, Math.min(maxStep, delta));
		}
		return appliedTractiveEffortN;
	}

	/**
	 * 把"实际施加的牵引力"折回**等效手柄比例**（HUD / 日志 / 镜像诊断读的是比例）：比例在
	 * {@link org.mtr.core.mmtr.physics.TractionSpec#effortN} 里是一次齐次的（{@code F(r,v) = r · F(1,v)}），
	 * 所以 {@code r = F / F(1,v)} 反解无损；这一点的满牵引为 0（无动力车底）时返回 0。
	 */
	private static double ratioForEffortN(TrainPhysics physics, double effortN, double speedMetersPerSecond) {
		final double fullEffortN = physics.tractiveEffortN(1, speedMetersPerSecond);
		return fullEffortN <= 0 ? 0 : Math.max(0, Math.min(1, effortN / fullEffortN));
	}

	/**
	 * 制动缸追目标缸压比例（建压/缓解有速率），管压跟着镜像 —— 与 {@link AirBrakeController} 同一套口径。
	 *
	 * @param targetRatio 目标缸压比例 = **气制动手柄诉求与 AFB 补气诉求里大的那个**（"耦合而非合并"：
	 *                    司机的手柄照旧管着自己那一份，AFB 只是在它之上要得更多时才抬目标）
	 */
	private void stepAir(ConsistType type, ThreeHandleSpec spec, double targetRatio, boolean emergency, double dt) {
		if (emergency) {
			pipePressure = Math.max(0, pipePressure - EMERGENCY_PIPE_RATE_PER_SECOND * dt);
			brakeCylinderPressure = Math.min(1, brakeCylinderPressure + EMERGENCY_CYLINDER_RATE_PER_SECOND * dt);
			return;
		}
		final double target = Math.max(0, Math.min(1, targetRatio));
		if (target > brakeCylinderPressure) {
			brakeCylinderPressure = Math.min(target, brakeCylinderPressure + type.getAirBrakeApplyRatePerSecond() * dt);
		} else {
			brakeCylinderPressure = Math.max(target, brakeCylinderPressure - type.getAirBrakeReleaseRatePerSecond() * dt);
		}
		if (target <= 0) {
			pipePressure = Math.min(1, pipePressure + type.getAirPipeChargeRatePerSecond() * dt);
		} else {
			pipePressure = Math.max(1 - target, pipePressure - type.getAirPipeDischargeRatePerSecond() * target * dt);
		}
	}

	/** AFB 的目标速度（m/s）；定速关闭时返回当前速度（⇒ 误差 0 ⇒ 什么都不做）。 */
	private static double lastAfbTargetMps(ControlState control, ThreeHandleSpec spec) {
		return control.getCruiseSpeedKmh() > 0 ? spec.clampCruiseKmh(control.getCruiseSpeedKmh()) / 3.6 : 0;
	}

	/**
	 * **设置这一列车的逐车制动描述**（notes/270，"连挂形式"的接入口）：由 {@code Vehicle} 从编组视图装配
	 * （服务端、多节编组）。不调用 = 单车：用整列等效锚造一节虚拟车。
	 *
	 * <p>连挂/解挂之后车列变了 ⇒ 调这个方法重建；**要保住气压状态**就紧接着
	 * {@link #applyAirStateString}(旧状态串)，或按连挂规则用 {@link #seedAirStateAfterCoupling}。</p>
	 */
	public void setBrakeCars(java.util.List<org.mtr.core.mmtr.brake.BrakeCar> cars) {
		brakeModel.setCars(cars);
	}

	@Override
	public org.mtr.core.mmtr.brake.BrakeModel getBrakeModel() {
		return brakeModel;
	}

	/** 单车时用的**整列等效锚**这一节车（编组时由 {@code Vehicle} 给逐车描述，这里只服务单车）。 */
	private static org.mtr.core.mmtr.brake.BrakeCar equivalentBrakeCar(ConsistType type) {
		return new org.mtr.core.mmtr.brake.BrakeCar(type.getBrake().getServiceForceN(),
			type.getBrake().getEmergencyForceN(), true, null);
	}

	/** 这一拍是不是在按**逐车管压**跑（诊断/测试用）。 */
	public boolean isPerCarBrakePipe() {
		return brakeModel.isPerCar();
	}

	/** 第 {@code index} 节车的缸压（bar）。 */
	public double getCylinderBar(int index) {
		return brakeModel.getCylinderBar(index);
	}

	/** 第 {@code index} 节车的管压（bar）。 */
	public double getPipeBar(int index) {
		return brakeModel.getPipeBar(index);
	}

	/** 把制动模型的归一化读数取回本控制器的对外字段（管压 / 缸压）。 */
	private void syncReadingsFromModel() {
		pipePressure = brakeModel.getPipePressure();
		brakeCylinderPressure = brakeModel.getBrakeCylinderPressure();
	}

	/** 这一拍**列车管压力**（bar）；旧口径车底返回 0（那一路没有 bar 语义，读数走归一化字段）。 */
	public double getPipeBar() { return brakeModel.getPipeBar(); }

	/** 这一拍**制动缸压力**（bar）；旧口径车底返回 0。 */
	public double getCylinderBar() { return brakeModel.getCylinderBar(); }

	// ---- 连挂接口（notes/270）----------------------------------------------------------------------

	/** 逐车气路状态串（{@code 管压比例,缸压比例;…}）：与 {@code MmtrComposition.encodeAirStates} 同格式。 */
	public String encodeAirState() {
		return brakeModel.encodeState();
	}

	/** 从状态串恢复（解挂切分 / 镜像种子 / 连挂后保住原状态）。 */
	public void applyAirStateString(String airState) {
		brakeModel.applyState(airState);
		syncReadingsFromModel();
	}

	/** 连挂之后：新挂上来的无动力车按管压 0/缸压 0 起（随后自己充风），动力车自带风源。 */
	public void seedAirStateAfterCoupling(int firstAddedCarIndex) {
		brakeModel.seedAfterCoupling(firstAddedCarIndex);
		syncReadingsFromModel();
	}

	@Override
	public double getPipePressure() { return pipePressure; }

	@Override
	public double getBrakeCylinderPressure() { return brakeCylinderPressure; }

	@Override
	public void setState(double pipePressure, double brakeCylinderPressure) {
		this.pipePressure = Math.max(0, Math.min(1, pipePressure));
		this.brakeCylinderPressure = Math.max(0, Math.min(1, brakeCylinderPressure));
		// 镜像种子是归一化的：按 bar 口径折回去（客户端与服务端的气压状态必须同一套语义）
		brakeModel.applyState(this.pipePressure + "," + this.brakeCylinderPressure);
	}

	public double getLastTractionRatio() { return lastTractionRatio; }
	public double getLastRheostaticRatio() { return lastRheostaticRatio; }
	public boolean isAfbActive() { return lastAfbActive; }

	/**
	 * 这一拍**真正施加**的轮周牵引力（N）—— 牵引力增速控制的状态量（notes/265）。
	 *
	 * <p>HUD/日志的"实际出力"由它与速度一起算（{@code Vehicle.mmtrLiveMotorForceN}），所以它必须
	 * 就是施加出去的那一份力，而不是手柄诉求的那一份。</p>
	 */
	public double getAppliedTractiveEffortN() { return appliedTractiveEffortN; }

	/**
	 * 这一拍**整列气制动力**（N，正值 = 在刹车）—— 镜像给客户端做 HUD 的"制动力（气）"（notes/269）。
	 *
	 * <p>逐车求和（不是"车头缸压折算"）；电空混合把机车自己那份削掉了的话，这里也会少掉那一份
	 * （这才是"实际在出多少气制动力"，而不是"管压对应多少"）。</p>
	 */
	public double getLastPneumaticBrakeForceN() { return lastPneumaticBrakeForceN; }

	@Override
	public void reset() {
		pipePressure = 1.0;
		brakeCylinderPressure = 0.0;
		lastTractionRatio = 0;
		lastRheostaticRatio = 0;
		lastAfbActive = false;
		appliedTractionRatio = 0;
		appliedRheostaticRatio = 0;
		appliedTractiveEffortN = 0;
		lastPneumaticBrakeForceN = 0;
		brakeModel.reset();
	}
}
