package org.mtr.core.mmtr;

import org.mtr.core.mmtr.brake.BrakeCar;
import org.mtr.core.mmtr.brake.BrakeCommand;
import org.mtr.core.mmtr.brake.BrakeModel;
import org.mtr.core.mmtr.physics.PneumaticBrakeSpec;
import org.mtr.core.mmtr.physics.TrainPhysics;

/**
 * Stepless ("无级") controller driven by HID axes. Throttle axis selects a target speed
 * (position = fraction of max speed) approached at the consist's traction limit; brake axis
 * applies proportional service braking. Notch inputs are ignored by this mode.
 *
 * <p>轴值 → **比例 → 力**，再除以惯性质量得加速度（notes/235）。</p>
 *
 * <h2>气压口径（notes/270）</h2>
 *
 * <p>配了 bar 键的车底：制动轴直接就是 {@link BrakeCommand#ofRatio} 的归一化诉求（无级手柄天然连续），
 * 走共用的 {@link BrakeModel} —— 与三手柄/有级同一个列车管 → 缸压 → 逐车力的模型。
 * 没配 bar 键的车底逐位不变。</p>
 */
public final class SteplessDriveController implements DriveController, BrakeCarrier, AirBrakeStateful {

	private final BrakeModel brakeModel = new BrakeModel("无级");
	/** 上一拍的牵引比例（诊断/镜像用；无级手柄没有"档位"这个量）。 */
	private double lastThrottleRatio;

	@Override
	public DriveOutput compute(ControlState control, ConsistType type, double speedMetersPerSecond, long dtMillis) {
		final TrainPhysics physics = type.getPhysics();
		final double throttleAxis = Math.max(0, control.getThrottleAxis());
		final double brakeAxis = Math.max(0, control.getBrakeAxis());
		final PneumaticBrakeSpec air = type.getBrakes();

		if (air != null) {
			return computePneumatic(control, type, physics, air, throttleAxis, brakeAxis, speedMetersPerSecond, dtMillis);
		}
		lastThrottleRatio = throttleAxis;

		if (control.isEmergency()) {
			return new DriveOutput(-physics.emergencyDecelerationMps2(speedMetersPerSecond), true, true, 0, 1);
		}

		if (brakeAxis > 0.001) {
			final double decel = physics.serviceBrakeDecelerationMps2(brakeAxis, speedMetersPerSecond);
			return new DriveOutput(-decel, decel > 0.01, false, 1, brakeAxis);
		}

		if (throttleAxis > 0.001) {
			final double maxSpeed = type.getMaxSpeedMetersPerSecond();
			final double targetSpeed = throttleAxis * maxSpeed;
			final double delta = targetSpeed - speedMetersPerSecond;
			// approach the selected speed, but never exceed the physical tractive envelope
			final double physical = physics.tractionAccelerationMps2(throttleAxis, speedMetersPerSecond);
			final double approach = Math.max(0, delta * 0.25);
			final double accel = Math.min(physical, approach);
			return new DriveOutput(accel, false, false, 1, 0);
		}

		return new DriveOutput(-physics.coastDecelerationMps2(speedMetersPerSecond), false, false, 1, 0);
	}

	/** bar 口径那一支：制动轴 = 连续诉求（0…1），走共用 {@link BrakeModel}；牵引同样受缸压联锁。 */
	private DriveOutput computePneumatic(ControlState control, ConsistType type, TrainPhysics physics, PneumaticBrakeSpec air,
			double throttleAxis, double brakeAxis, double speedMetersPerSecond, long dtMillis) {
		final double dt = Math.max(1, dtMillis) / 1000.0;
		final boolean emergency = control.isEmergency();
		final BrakeCar equivalentCar = new BrakeCar(type.getBrake().getServiceForceN(), type.getBrake().getEmergencyForceN(), true, null);
		// 制动轴 0…1 = **常用制动**诉求：折进级位表的常用范围（表尾的紧急级要保护层显式给，notes/270）
		brakeModel.step(air, equivalentCar, BrakeCommand.ofRatio(brakeAxis * air.getServiceDemandLimit(), 0, emergency),
			speedMetersPerSecond, 0, dt);

		final double pneumaticForceN = emergency ? brakeModel.getEmergencyForceN() : brakeModel.getPneumaticForceN();
		final boolean tractionAllowed = brakeAxis <= 0.001 && !emergency && !brakeModel.isPneumaticHolding();
		// 定速逼近与物理牵引包络取小（与旧支同一个口径），再折成力交给合力那一层
		final double targetSpeed = throttleAxis * type.getMaxSpeedMetersPerSecond();
		final double approachAccel = Math.max(0, (targetSpeed - speedMetersPerSecond) * 0.25);
		final double tractionForceN = tractionAllowed && throttleAxis > 0.001
			? Math.min(physics.tractiveEffortN(throttleAxis, speedMetersPerSecond), approachAccel * physics.effectiveMassKg()) : 0;
		lastThrottleRatio = throttleAxis;
		final double brakingForceN = physics.adhesionLimitedBrakingForceN(pneumaticForceN, 0, speedMetersPerSecond,
			air.isWspEnabled(), air.getWheelSlipMu());
		// 制动灯看**真的在出制动力**，不看"合加速度为负"（惰行时运行阻力也让加速度为负）
		final boolean braking = emergency || pneumaticForceN > 1 || brakeModel.isPneumaticHolding();
		return new DriveOutput(physics.netAccelerationMps2(tractionForceN, brakingForceN, speedMetersPerSecond),
			braking, emergency, brakeModel.getPipePressure(), brakeModel.getBrakeCylinderPressure());
	}

	@Override
	public BrakeModel getBrakeModel() {
		return brakeModel;
	}

	// 气制动读数/镜像种子都走同一个模型（notes/270）
	@Override
	public double getPipePressure() {
		return brakeModel.getPipePressure();
	}

	@Override
	public double getBrakeCylinderPressure() {
		return brakeModel.getBrakeCylinderPressure();
	}

	@Override
	public void setState(double pipePressure, double brakeCylinderPressure) {
		brakeModel.setState(pipePressure, brakeCylinderPressure);
	}

	/** 上一拍的牵引比例（0…1）。 */
	public double getLastThrottleRatio() {
		return lastThrottleRatio;
	}

	@Override
	public void reset() {
		lastThrottleRatio = 0;
		brakeModel.reset();
	}
}
