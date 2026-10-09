package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.physics.PneumaticBrakeSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixed sub-step integration tests: determinism, exact time consumption, stable braking. */
public final class ConsistDynamicsAdvanceTests {

	private static final String JSON = """
		{
		  "consistTypes": [
		    {"id":"emu","controlMode":"NOTCHED","powerNotches":7,"brakeNotches":8,
		     "maxSpeedKmh":120,"massKg":60000,"maxTractiveEffortN":36000,"serviceBrakeForceN":54000,"emergencyBrakeForceN":90000},
		    {"id":"freight","controlMode":"AIR_BRAKE","powerNotches":8,"brakeNotches":3,
		     "maxSpeedKmh":100,"massKg":60000,"maxTractiveEffortN":18000,"serviceBrakeForceN":42000,"emergencyBrakeForceN":72000,
		     "airPipeChargeRatePerSecond":0.12,"airPipeDischargeRatePerSecond":0.5,
		     "airBrakeApplyRatePerSecond":0.2,"airBrakeReleaseRatePerSecond":0.08}
		  ]
		}
		""";

	private static final long SUB_STEP = 10;

	@Test
	public void advanceIsDeterministicAndConsumesExactTime() {
		final ConsistType emu = ConsistTypeRegistry.parse(JSON).get("emu");
		final NotchedDriveController controller = new NotchedDriveController();
		final ConsistDynamics.OutputProvider provider = (speed, dt) -> controller.compute(ControlState.zero().setThrottleNotch(5), emu, speed, dt);

		final ConsistDynamics.SpeedDistance a = ConsistDynamics.advance(0, emu, 337, SUB_STEP, provider);
		final ConsistDynamics.SpeedDistance b = ConsistDynamics.advance(0, emu, 337, SUB_STEP, provider);
		assertEquals(a.speedMetersPerSecond, b.speedMetersPerSecond, 0, "identical runs must be identical");
		assertEquals(a.distanceMeters, b.distanceMeters, 0, "identical runs must be identical");

		// 337 ms = 10+10+...+7 tail: total time exact, distance roughly v_avg * dt.
		assertTrue(a.speedMetersPerSecond > 0, "should have accelerated");
		final double meanSpeed = a.distanceMeters / (337.0 / 1000.0);
		assertTrue(meanSpeed >= 0 && meanSpeed <= a.speedMetersPerSecond + 1e-9, "mean speed must lie between 0 and final speed");
	}

	@Test
	public void advanceMatchesManualSubStepping() {
		final ConsistType emu = ConsistTypeRegistry.parse(JSON).get("emu");
		final NotchedDriveController controller = new NotchedDriveController();
		double manual = 0;
		long remaining = 337;
		while (remaining > 0) {
			final long step = Math.min(SUB_STEP, remaining);
			remaining -= step;
			manual = ConsistDynamics.step(manual, controller.compute(ControlState.zero().setThrottleNotch(5), emu, manual, step), emu, step);
		}
		final ConsistDynamics.SpeedDistance sub = ConsistDynamics.advance(0, emu, 337, SUB_STEP, (speed, dt) -> controller.compute(ControlState.zero().setThrottleNotch(5), emu, speed, dt));
		assertEquals(manual, sub.speedMetersPerSecond, 1e-12, "advance must be the manual sub-step loop");
	}

	/**
	 * 气压制动的定步长稳定性（notes/376：{@code AirBrakeController} 已删除，AIR_BRAKE 车底走
	 * {@link NotchedDriveController}，逐车气路在它持有的 {@code BrakeModel} 里）。
	 *
	 * <p>钉的还是原来那几条：速度永不为负、制动期间永不加速、管压/缸压有界、30 s 内基本停住。
	 * 读数从"归一化 0…1"换成 **bar**，边界也换成车底自己的充风值 / 紧急限压。</p>
	 */
	@Test
	public void airBrakeBrakingIsStableWithSubSteps() {
		final ConsistType freight = ConsistTypeRegistry.parse(JSON).get("freight");
		final PneumaticBrakeSpec air = freight.getBrakes();
		final NotchedDriveController controller = new NotchedDriveController();
		double speed = 13.0; // ~47 km/h: stopping at ~0.7 m/s^2 needs ~19 s of the 30 s budget
		double prev = speed;
		final ControlState brake = ControlState.zero().setBrakeNotch(3);
		for (int i = 0; i < 600; i++) {
			final ConsistDynamics.SpeedDistance step = ConsistDynamics.advance(speed, freight, 50, SUB_STEP, (s, dt) -> controller.compute(brake, freight, s, dt));
			speed = step.speedMetersPerSecond;
			assertTrue(speed >= 0, "speed must never go negative");
			assertTrue(speed <= prev + 1e-9, "braking must never accelerate");
			prev = speed;
			final double pipeBar = controller.getBrakeModel().getPipeBar();
			final double cylinderBar = controller.getBrakeModel().getCylinderBar();
			assertTrue(pipeBar >= 0 && pipeBar <= air.getChargedBar(),
				"管压必须在 [0, 充风值] 内，实际 " + pipeBar);
			assertTrue(cylinderBar >= 0 && cylinderBar <= air.getCylinderEmergencyBar(),
				"缸压必须在 [0, 紧急限压] 内，实际 " + cylinderBar);
			assertTrue(controller.getPipePressure() >= 0 && controller.getPipePressure() <= 1, "pipe pressure bounded");
			assertTrue(controller.getBrakeCylinderPressure() >= 0 && controller.getBrakeCylinderPressure() <= 1, "brake cylinder bounded");
		}
		System.out.println(String.format("[TEST] 30 s 全常用（3 档）：13 m/s → %.4f m/s（管压 %.2f bar、缸压 %.2f bar）",
			speed, controller.getBrakeModel().getPipeBar(), controller.getBrakeModel().getCylinderBar()));
		assertTrue(speed <= 0.5, "air brake should nearly stop the freight within 30 s, got " + speed);
	}
}