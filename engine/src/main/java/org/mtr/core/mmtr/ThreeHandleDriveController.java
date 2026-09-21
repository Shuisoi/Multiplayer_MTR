package org.mtr.core.mmtr;

/**
 * 三手柄机车控制器：**定速巡航（AFB） + 双向油门（牵引/电阻制动） + 气制动**（规格见
 * {@link ThreeHandleSpec} 与 docs/01-设计/驾驶输入与控制模型.md）。
 *
 * <h2>合成优先权（每拍从上到下第一条命中）</h2>
 *
 * <ol>
 *   <li>紧急（制动手柄 EB 位，或保护层给的 {@code emergency}）→ 紧急减速度，牵引与电阻制动全封锁；</li>
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
 * <p>气制动状态（管压/缸压）按 {@link ConsistType} 的建压/缓解速率演进，并通过
 * {@link AirBrakeStateful} 暴露给镜像种子 —— 与既有 {@link AirBrakeController} 同一套口径。</p>
 */
public final class ThreeHandleDriveController implements DriveController, AirBrakeStateful {

	/** AFB 只在误差超过这个带宽时才施加电阻制动（免得在设定速度附近反复点刹）。 */
	private static final double AFB_BRAKE_THRESHOLD_MPS = 0.2;
	private static final double EMERGENCY_PIPE_RATE_PER_SECOND = 5.0;
	private static final double EMERGENCY_CYLINDER_RATE_PER_SECOND = 5.0;

	private double pipePressure = 1.0;
	private double brakeCylinderPressure = 0.0;

	// 最近一拍的实际输出比例（诊断/测试/HUD 用；不参与物理）
	private double lastTractionRatio;
	private double lastRheostaticRatio;
	private boolean lastAfbActive;

	@Override
	public DriveOutput compute(ControlState control, ConsistType type, double speedMetersPerSecond, long dtMillis) {
		final ThreeHandleSpec spec = type.getHandles();
		if (spec == null) {
			// 规格缺失（车型配错/镜像坏了）：退回有级控制器。绝不静默变成"车不动" —— 那是最难查的一类现场。
			return new NotchedDriveController().compute(control, type, speedMetersPerSecond, dtMillis);
		}

		final double dt = Math.max(1, dtMillis) / 1000.0;
		final int brakePosition = spec.clampBrakePosition(control.getBrakeNotch());
		final boolean emergency = control.isEmergency() || spec.isEmergencyPosition(brakePosition);
		stepAir(type, spec, brakePosition, emergency, dt);

		if (emergency) {
			lastTractionRatio = 0;
			lastRheostaticRatio = 0;
			lastAfbActive = false;
			return new DriveOutput(-type.getEmergencyDecelerationMps2(), true, true, pipePressure, brakeCylinderPressure);
		}

		final int driveHandle = spec.clampDriveHandle(control.getDriveHandle());
		double tractionRatio = spec.tractionRatio(driveHandle);
		double rheostaticRatio = spec.rheostaticRatio(driveHandle);

		// AFB：气制动一离开"运行"位就让位（司机优先），设定值保留着，回位即恢复。
		lastAfbActive = control.getCruiseSpeedKmh() > 0 && brakePosition == ThreeHandleSpec.runningPosition() && brakeCylinderPressure <= 0.001;
		if (lastAfbActive) {
			final double targetMetersPerSecond = spec.clampCruiseKmh(control.getCruiseSpeedKmh()) / 3.6;
			final double error = targetMetersPerSecond - speedMetersPerSecond;
			// "油门手柄此时只当牵引上限"：手柄在关闭位则 cap = 0，AFB 这一侧推不动车（想加速必须推油门）。
			final double cap = spec.isAfbUsesHandleAsCap() ? tractionRatio : 1.0;
			if (error > 0) {
				tractionRatio = Math.min(cap, spec.getAfbGainPerMps() * error);
			} else if (error < -AFB_BRAKE_THRESHOLD_MPS) {
				tractionRatio = 0;
				rheostaticRatio = Math.max(rheostaticRatio, Math.min(1, spec.getAfbGainPerMps() * -error));
			} else {
				// 带内：惰行（手柄若在电阻制动侧仍按司机的手柄施加，见规格 §5）
				tractionRatio = 0;
			}
		}

		lastTractionRatio = tractionRatio;
		lastRheostaticRatio = rheostaticRatio;

		final double pneumaticDeceleration = type.getServiceBrakeDecelerationMps2() * brakeCylinderPressure;
		final double rheostaticDeceleration = spec.getRheostaticBrakeDecelerationMps2() * rheostaticRatio * spec.rheostaticFade(speedMetersPerSecond);
		// 气制动的"优先权"看手柄位置而不是已经建起来的缸压：刚拉到 8 档的那一拍缸压还是 0，
		// 若按缸压判断，牵引会在建压期间偷偷生效一小会儿（真车不会）。
		final boolean pneumaticDemanded = brakePosition > ThreeHandleSpec.runningPosition();
		if (pneumaticDemanded) {
			// 合成优先权第 2 条：气制动一离开"运行"位就压住牵引（牵引比例如实报 0，别让 HUD/日志撒谎）
			tractionRatio = 0;
			lastTractionRatio = 0;
		}
		final double resistance = MmtrPhysics.brakingResistance(type, speedMetersPerSecond);

		final double acceleration;
		if (pneumaticDemanded || rheostaticDeceleration > 0) {
			acceleration = -(pneumaticDeceleration + rheostaticDeceleration + resistance);
		} else if (tractionRatio > 0) {
			acceleration = MmtrPhysics.tractionAcceleration(type, tractionRatio, speedMetersPerSecond);
		} else {
			acceleration = -resistance;
		}

		final boolean brakeLamp = brakeCylinderPressure > 0.01 || rheostaticDeceleration > 0.01;
		return new DriveOutput(acceleration, brakeLamp, false, pipePressure, brakeCylinderPressure);
	}

	/** 制动缸追目标比例（建压/缓解有速率），管压跟着镜像 —— 与 {@link AirBrakeController} 同一套口径。 */
	private void stepAir(ConsistType type, ThreeHandleSpec spec, int brakePosition, boolean emergency, double dt) {
		if (emergency) {
			pipePressure = Math.max(0, pipePressure - EMERGENCY_PIPE_RATE_PER_SECOND * dt);
			brakeCylinderPressure = Math.min(1, brakeCylinderPressure + EMERGENCY_CYLINDER_RATE_PER_SECOND * dt);
			return;
		}
		final double target = spec.brakeRatio(brakePosition);
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

	@Override
	public double getPipePressure() { return pipePressure; }

	@Override
	public double getBrakeCylinderPressure() { return brakeCylinderPressure; }

	@Override
	public void setState(double pipePressure, double brakeCylinderPressure) {
		this.pipePressure = Math.max(0, Math.min(1, pipePressure));
		this.brakeCylinderPressure = Math.max(0, Math.min(1, brakeCylinderPressure));
	}

	public double getLastTractionRatio() { return lastTractionRatio; }
	public double getLastRheostaticRatio() { return lastRheostaticRatio; }
	public boolean isAfbActive() { return lastAfbActive; }

	@Override
	public void reset() {
		pipePressure = 1.0;
		brakeCylinderPressure = 0.0;
		lastTractionRatio = 0;
		lastRheostaticRatio = 0;
		lastAfbActive = false;
	}
}
