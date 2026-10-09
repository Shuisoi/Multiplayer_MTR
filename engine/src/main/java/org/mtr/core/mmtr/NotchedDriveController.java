package org.mtr.core.mmtr;

import org.mtr.core.mmtr.brake.BrakeCar;
import org.mtr.core.mmtr.brake.BrakeCommand;
import org.mtr.core.mmtr.brake.BrakeModel;
import org.mtr.core.mmtr.physics.ElectricBrakeSpec;
import org.mtr.core.mmtr.physics.PneumaticBrakeSpec;
import org.mtr.core.mmtr.physics.TrainPhysics;

/**
 * Notched ("有级") controller: throttle and brake are discrete notches mapped onto the
 * consist's performance envelope. Brake input overrides traction whenever brake > 0.
 *
 * <p>档位 → **力**：牵引力 = 全牵引力 × 比例（功率同步缩放），加速度由 {@link TrainPhysics}
 * 除以惯性质量得到（notes/235）。</p>
 *
 * <h2>制动：只有气压口径一条路（notes/270/376）</h2>
 *
 * <p>档位折成 {@link BrakeCommand} 丢进共用的 {@link BrakeModel}
 * （列车管 → 分配阀 → 缸压 → 逐车力 → 黏着截断），与三手柄/无级用的是同一套逻辑 ——
 * 于是"换一种操纵方式"不再意味着"换一套制动模型"。**旧的比例制动力（{@code 档/档数 × 全制动力}）
 * 已删除**：车底的气压口径是必填项（{@link ConsistType} 构造器保证非空）。</p>
 */
public final class NotchedDriveController implements DriveController, BrakeCarrier, AirBrakeStateful {

	private final BrakeModel brakeModel = new BrakeModel("有级");
	/** notes/379：上一拍的牵引力与电制动力（HUD/镜像读数，服务端算好发下去）。 */
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
		final TrainPhysics physics = type.getPhysics();
		final int throttle = clamp(control.getThrottleNotch(), 0, type.getPowerNotches());
		final int brake = clamp(control.getBrakeNotch(), 0, type.getBrakeNotches());
		// notes/376：**只有这一条路** —— 车底一定有气压口径（ConsistType 构造器保证），
		// 旧的比例制动力分支（air == null）已整段删除。
		return computePneumatic(control, type, physics, type.getBrakes(), throttle, brake, speedMetersPerSecond, dtMillis);
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
		/*
		 * notes/379（用户口径 2026-10-03「B1,B2 是电再生制动逻辑」）：**电制动先吃饱，机械补缺口**。
		 *
		 * <p>以前有级/无级这条路上 {@code availableElectricN} 恒为 0 ⇒ 车底就算配了电制动也用不上，
		 * 低档（B1/B2）全是空气闸、缸压与真车不符。现在把整列的可用电制动力交给共用制动系统：
		 * 它替掉**动力车自己**那份机械制动（拖车的空气闸不动 —— 它们没有电机），于是
		 * "缸压 = 司机诉求" 只在电制动不够时才成立。</p>
		 */
		final ElectricBrakeSpec electric = type.getElectricBrake();
		final double availableElectricN = electric == null || !air.isBlendingEnabled()
			? 0 : Math.max(0, electric.effortN(speedMetersPerSecond));
		brakeModel.step(air, equivalentCar, BrakeCommand.ofRatio(demandRatio, 0, emergency),
			speedMetersPerSecond, availableElectricN, dt);

		final double pneumaticForceN = emergency ? brakeModel.getEmergencyForceN() : brakeModel.getPneumaticForceN();
		// 电制动替掉的那一份要**加回来**（EP 只削不加：总制动力 = 诉求，只是气/电的分配变了）。
		final double electricForceN = emergency ? 0 : brakeModel.getBlendedElectricN();
		// 牵引联锁：闸没排空就不许牵引（真车口径；逐车时按全列最大缸压判）
		final boolean tractionAllowed = brake == 0 && !emergency && !brakeModel.isPneumaticHolding();
		final double tractionForceN = tractionAllowed && throttle > 0
			? physics.tractiveEffortN((double) throttle / type.getPowerNotches(), speedMetersPerSecond) : 0;
		final double brakingForceN = physics.adhesionLimitedBrakingForceN(pneumaticForceN, electricForceN, speedMetersPerSecond,
			air.isWspEnabled(), air.getWheelSlipMu());
		// 制动灯看**真的在出制动力**，不看"合加速度为负"（惰行时运行阻力也让加速度为负，不能点灯）
		final boolean braking = emergency || pneumaticForceN > 1 || electricForceN > 1 || brakeModel.isPneumaticHolding();
		// notes/379：读数（HUD 的"电机"行与"制动力（电）"）：牵引与电制动分开报，气那份来自模型。
		lastTractionForceN = tractionForceN;
		lastElectricBrakeForceN = electricForceN;
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
		lastTractionForceN = 0;
		lastElectricBrakeForceN = 0;
		brakeModel.reset();
	}

	private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
