package org.mtr.core.mmtr;

import org.mtr.core.mmtr.brake.BrakeCar;
import org.mtr.core.mmtr.brake.BrakeCommand;
import org.mtr.core.mmtr.brake.BrakeModel;
import org.mtr.core.mmtr.physics.ElectricBrakeSpec;
import org.mtr.core.mmtr.physics.PneumaticBrakeSpec;
import org.mtr.core.mmtr.physics.TrainPhysics;

/**
 * Stepless ("无级") controller driven by HID axes. Throttle axis selects a target speed
 * (position = fraction of max speed) approached at the consist's traction limit; brake axis
 * applies proportional service braking. Notch inputs are ignored by this mode.
 *
 * <p>轴值 → **比例 → 力**，再除以惯性质量得加速度（notes/235）。</p>
 *
 * <h2>气压口径（notes/270/376）</h2>
 *
 * <p>制动轴直接就是 {@link BrakeCommand#ofRatio} 的归一化诉求（无级手柄天然连续），
 * 走共用的 {@link BrakeModel} —— 与三手柄/有级同一个列车管 → 缸压 → 逐车力的模型。
 * **旧的比例制动力分支（没有 bar 键就退回比例 × 全制动力）已删除**：气压口径是车底必填项。</p>
 */
public final class SteplessDriveController implements DriveController, BrakeCarrier, AirBrakeStateful {

	private final BrakeModel brakeModel = new BrakeModel("无级");
	/** 上一拍的牵引比例（诊断/镜像用；无级手柄没有"档位"这个量）。 */
	private double lastThrottleRatio;
	/** notes/379：上一拍的牵引力与电制动力（HUD/镜像读数）。 */
	private double lastTractionForceN;
	private double lastElectricBrakeForceN;

	@Override
	public double getLastTractionForceN() {
		return lastTractionForceN;
	}

	@Override
	public double getLastElectricBrakeForceN() {
		return lastElectricBrakeForceN;
	}

	@Override
	public DriveOutput compute(ControlState control, ConsistType type, double speedMetersPerSecond, long dtMillis) {
		final double throttleAxis = Math.max(0, control.getThrottleAxis());
		final double brakeAxis = Math.max(0, control.getBrakeAxis());
		lastThrottleRatio = throttleAxis;
		return computePneumatic(control, type, type.getPhysics(), type.getBrakes(), throttleAxis, brakeAxis, speedMetersPerSecond, dtMillis);
	}

	/** bar 口径那一支：制动轴 = 连续诉求（0…1），走共用 {@link BrakeModel}；牵引同样受缸压联锁。 */
	private DriveOutput computePneumatic(ControlState control, ConsistType type, TrainPhysics physics, PneumaticBrakeSpec air,
			double throttleAxis, double brakeAxis, double speedMetersPerSecond, long dtMillis) {
		final double dt = Math.max(1, dtMillis) / 1000.0;
		final boolean emergency = control.isEmergency();
		final BrakeCar equivalentCar = new BrakeCar(type.getBrake().getServiceForceN(), type.getBrake().getEmergencyForceN(), true, null);
		// 制动轴 0…1 = **常用制动**诉求：折进级位表的常用范围（表尾的紧急级要保护层显式给，notes/270）
		// notes/379：电制动先吃饱（与有级同一条路）—— 车底配了电制动就有得混合，没配则为 0。
		final ElectricBrakeSpec electric = type.getElectricBrake();
		final double availableElectricN = electric == null || !air.isBlendingEnabled()
			? 0 : Math.max(0, electric.effortN(speedMetersPerSecond));
		brakeModel.step(air, equivalentCar, BrakeCommand.ofRatio(brakeAxis * air.getServiceDemandLimit(), 0, emergency),
			speedMetersPerSecond, availableElectricN, dt);

		final double pneumaticForceN = emergency ? brakeModel.getEmergencyForceN() : brakeModel.getPneumaticForceN();
		// 电制动替掉的那一份加回来（EP 只削不加 ⇒ 总制动力不变，变的是气/电分配）
		final double electricForceN = emergency ? 0 : brakeModel.getBlendedElectricN();
		final boolean tractionAllowed = brakeAxis <= 0.001 && !emergency && !brakeModel.isPneumaticHolding();
		// 定速逼近与物理牵引包络取小（与旧支同一个口径），再折成力交给合力那一层
		final double targetSpeed = throttleAxis * type.getMaxSpeedMetersPerSecond();
		final double approachAccel = Math.max(0, (targetSpeed - speedMetersPerSecond) * 0.25);
		final double tractionForceN = tractionAllowed && throttleAxis > 0.001
			? Math.min(physics.tractiveEffortN(throttleAxis, speedMetersPerSecond), approachAccel * physics.effectiveMassKg()) : 0;
		lastThrottleRatio = throttleAxis;
		final double brakingForceN = physics.adhesionLimitedBrakingForceN(pneumaticForceN, electricForceN, speedMetersPerSecond,
			air.isWspEnabled(), air.getWheelSlipMu());
		// 制动灯看**真的在出制动力**，不看"合加速度为负"（惰行时运行阻力也让加速度为负）
		final boolean braking = emergency || pneumaticForceN > 1 || electricForceN > 1 || brakeModel.isPneumaticHolding();
		lastTractionForceN = tractionForceN;
		lastElectricBrakeForceN = electricForceN;
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
		lastTractionForceN = 0;
		lastElectricBrakeForceN = 0;
		brakeModel.reset();
	}
}
