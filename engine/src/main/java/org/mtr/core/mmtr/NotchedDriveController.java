package org.mtr.core.mmtr;

import org.mtr.core.mmtr.brake.BrakeCar;
import org.mtr.core.mmtr.brake.BrakeCommand;
import org.mtr.core.mmtr.brake.BrakeModel;
import org.mtr.core.mmtr.physics.PneumaticBrakeSpec;
import org.mtr.core.mmtr.physics.TrainPhysics;

/**
 * Notched ("有级") controller: throttle and brake are discrete notches mapped onto the
 * consist's performance envelope. Brake input overrides traction whenever brake > 0.
 *
 * <p>档位比例映射到**力**：牵引力 = 全牵引力 × 比例（功率同步缩放），制动力 = 全常用制动力 × 比例；
 * 加速度由 {@link TrainPhysics} 除以惯性质量得到（notes/235）。</p>
 *
 * <h2>气压口径（notes/270）</h2>
 *
 * <p>车底在 {@code consist-types.json} 里配了 bar 键（{@link ConsistType#getBrakes()}）时，制动**不再**走
 * "比例 × 全制动力"，而是把档位折成 {@link BrakeCommand} 丢进共用的 {@link BrakeModel}
 * （列车管 → 分配阀 → 缸压 → 逐车力 → 黏着截断），与三手柄/无级用的是同一套逻辑 ——
 * 于是"换一种操纵方式"不再意味着"换一套制动模型"。没配 bar 键的车底逐位不变。</p>
 */
public final class NotchedDriveController implements DriveController, BrakeCarrier, AirBrakeStateful {

	private final BrakeModel brakeModel = new BrakeModel("有级");

	@Override
	public DriveOutput compute(ControlState control, ConsistType type, double speedMetersPerSecond, long dtMillis) {
		final TrainPhysics physics = type.getPhysics();
		final int throttle = clamp(control.getThrottleNotch(), 0, type.getPowerNotches());
		final int brake = clamp(control.getBrakeNotch(), 0, type.getBrakeNotches());
		final PneumaticBrakeSpec air = type.getBrakes();

		if (air != null) {
			return computePneumatic(control, type, physics, air, throttle, brake, speedMetersPerSecond, dtMillis);
		}

		if (control.isEmergency()) {
			return new DriveOutput(-physics.emergencyDecelerationMps2(speedMetersPerSecond), true, true, 0, 1);
		}

		if (brake > 0) {
			final double ratio = (double) brake / type.getBrakeNotches();
			final double decel = physics.serviceBrakeDecelerationMps2(ratio, speedMetersPerSecond);
			return new DriveOutput(-decel, decel > 0.01, false, 1, ratio);
		}

		if (throttle > 0) {
			final double ratio = (double) throttle / type.getPowerNotches();
			// 恒力矩 → 恒功率（自然折点），减运行阻力；平衡速度由"牵引 vs 阻力"自己长出来。
			return new DriveOutput(physics.tractionAccelerationMps2(ratio, speedMetersPerSecond), false, false, 1, 0);
		}

		return new DriveOutput(-physics.coastDecelerationMps2(speedMetersPerSecond), false, false, 1, 0);
	}

	/**
	 * bar 口径那一支：档位 → 管压级位 → 逐车缸压 → 力（与三手柄共用 {@link BrakeModel}）。
	 *
	 * <p>档位在**本车底自己的档数**上归一化（{@code 档/档数}，与旧口径同一个比例口径），再由气压口径的
	 * 级位表插值成列车管目标 —— 于是"这台车有几档制动"只是配置，模型一行不改。</p>
	 */
	private DriveOutput computePneumatic(ControlState control, ConsistType type, TrainPhysics physics, PneumaticBrakeSpec air,
			int throttle, int brake, double speedMetersPerSecond, long dtMillis) {
		final double dt = Math.max(1, dtMillis) / 1000.0;
		final boolean emergency = control.isEmergency();
		final BrakeCar equivalentCar = new BrakeCar(type.getBrake().getServiceForceN(), type.getBrake().getEmergencyForceN(), true, null);
		/*
		 * 档位比例沿用旧口径（{@code 档/档数}，最后一位 = 全常用），再折进级位表的**常用范围**
		 * （{@link PneumaticBrakeSpec#getServiceDemandLimit}）：表尾那一档是紧急/快排级，有级手柄的最后一位
		 * 不该够到它（紧急由保护层给）。于是"这台车有几档制动"只是配置，模型一行不改。
		 */
		final double demandRatio = (double) brake / Math.max(1, type.getBrakeNotches()) * air.getServiceDemandLimit();
		brakeModel.step(air, equivalentCar, BrakeCommand.ofRatio(demandRatio, 0, emergency),
			speedMetersPerSecond, 0, dt);

		final double pneumaticForceN = emergency ? brakeModel.getEmergencyForceN() : brakeModel.getPneumaticForceN();
		// 牵引联锁：闸没排空就不许牵引（真车口径；逐车时按全列最大缸压判）
		final boolean tractionAllowed = brake == 0 && !emergency && !brakeModel.isPneumaticHolding();
		final double tractionForceN = tractionAllowed && throttle > 0
			? physics.tractiveEffortN((double) throttle / type.getPowerNotches(), speedMetersPerSecond) : 0;
		final double brakingForceN = physics.adhesionLimitedBrakingForceN(pneumaticForceN, 0, speedMetersPerSecond,
			air.isWspEnabled(), air.getWheelSlipMu());
		// 制动灯看**真的在出制动力**，不看"合加速度为负"（惰行时运行阻力也让加速度为负，不能点灯）
		final boolean braking = emergency || pneumaticForceN > 1 || brakeModel.isPneumaticHolding();
		return new DriveOutput(physics.netAccelerationMps2(tractionForceN, brakingForceN, speedMetersPerSecond),
			braking, emergency, brakeModel.getPipePressure(), brakeModel.getBrakeCylinderPressure());
	}

	@Override
	public BrakeModel getBrakeModel() {
		return brakeModel;
	}

	// 气制动读数/镜像种子都走同一个模型（notes/270）：HUD 与客户端镜像不必知道是哪种操纵方式
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

	@Override
	public void reset() {
		brakeModel.reset();
	}

	private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
