package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless driving scenarios: run a controller + ConsistDynamics integration over time and
 * assert physically sensible train behaviour (accelerate, coast, service stop, emergency
 * stop, no backwards creep). This is the same integration math that will be embedded in the
 * Vehicle tick; keeping it pure lets us verify it without starting Minecraft.
 */
public final class SimulatedRunTests {

	private static final String JSON = "{"
		+ "  \"consistTypes\": ["
		+ "    {\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "     \"maxSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000},"
		+ "    {\"id\":\"freight\",\"controlMode\":\"AIR_BRAKE\",\"powerNotches\":8,\"brakeNotches\":3,"
		+ "     \"maxSpeedKmh\":100,\"massKg\":60000,\"maxTractiveEffortN\":18000,\"serviceBrakeForceN\":42000,\"emergencyBrakeForceN\":72000,"
		+ "     \"airPipeChargeRatePerSecond\":0.12,\"airPipeDischargeRatePerSecond\":0.5,"
		+ "     \"airBrakeApplyRatePerSecond\":0.2,\"airBrakeReleaseRatePerSecond\":0.08}"
		+ "  ]"
		+ "}";

	private static final long DT_MS = 100;

	private static double runNotched(ConsistType type, ControlState control, double maxTimeSeconds) {
		final NotchedDriveController controller = new NotchedDriveController();
		double speed = 0;
		int steps = (int) (maxTimeSeconds * 1000 / DT_MS);
		for (int i = 0; i < steps; i++) {
			final DriveOutput out = controller.compute(control, type, speed, DT_MS);
			speed = ConsistDynamics.step(speed, out, type, DT_MS);
		}
		return speed;
	}

	@Test
	public void testNotchedAccelerateCoastStop() {
		final ConsistType emu = ConsistTypeRegistry.parse(JSON).get("emu");
		final NotchedDriveController controller = new NotchedDriveController();
		double speed = 0;
		// 10 s at notch 3/7
		for (int i = 0; i < 100; i++) {
			speed = ConsistDynamics.step(speed, controller.compute(ControlState.zero().setThrottleNotch(3), emu, speed, DT_MS), emu, DT_MS);
		}
		assertTrue(speed > 1.0 && speed <= emu.getMaxSpeedMetersPerSecond(), "expected modest speed, got " + speed);
		final double speedBeforeCoast = speed;
		// coast 5 s: speed must hold
		for (int i = 0; i < 50; i++) {
			speed = ConsistDynamics.step(speed, controller.compute(ControlState.zero(), emu, speed, DT_MS), emu, DT_MS);
		}
		assertEquals(speedBeforeCoast, speed, 1e-9, "coasting must hold speed");
		// service brake full: stop, never negative
		for (int i = 0; i < 300 && speed > 0.01; i++) {
			speed = ConsistDynamics.step(speed, controller.compute(ControlState.zero().setBrakeNotch(8), emu, speed, DT_MS), emu, DT_MS);
			assertTrue(speed >= 0, "speed must never go negative");
		}
		assertTrue(speed <= 0.01, "service brake must stop the train, got " + speed);
	}

	@Test
	public void testEmergencyStopsFasterThanService() {
		final ConsistType emu = ConsistTypeRegistry.parse(JSON).get("emu");
		final double serviceTimeToStop = timeToStopNotched(emu, ControlState.zero().setBrakeNotch(8));
		final double emergencyTimeToStop = timeToStopNotched(emu, ControlState.zero().setEmergency(true));
		assertTrue(emergencyTimeToStop < serviceTimeToStop, "emergency must stop faster than service brake");
	}

	private static double timeToStopNotched(ConsistType type, ControlState control) {
		final NotchedDriveController controller = new NotchedDriveController();
		double speed = 33.33; // ~120 km/h
		int steps = 0;
		while (speed > 0.01 && steps < 1000) {
			final DriveOutput out = controller.compute(control, type, speed, DT_MS);
			speed = ConsistDynamics.step(speed, out, type, DT_MS);
			steps++;
		}
		return steps * DT_MS / 1000.0;
	}

	@Test
	public void testAirBrakeRunStopsAndNeverBackwards() {
		final ConsistType freight = ConsistTypeRegistry.parse(JSON).get("freight");
		final AirBrakeController controller = new AirBrakeController();
		// accelerate at notch 4 for a while
		final NotchedDriveController power = new NotchedDriveController();
		double speed = 0;
		for (int i = 0; i < 200; i++) { // 20 s
			speed = ConsistDynamics.step(speed, power.compute(ControlState.zero().setThrottleNotch(4), freight, speed, DT_MS), freight, DT_MS);
		}
		assertTrue(speed > 2, "freight should have accelerated, got " + speed);

		// apply air brake (notch 3) until stopped
		final ControlState apply = ControlState.zero().setBrakeNotch(3);
		int steps = 0;
		double prev = speed;
		while (speed > 0.01 && steps < 2000) {
			final DriveOutput out = controller.compute(apply, freight, speed, DT_MS);
			speed = ConsistDynamics.step(speed, out, freight, DT_MS);
			assertTrue(speed <= prev + 1e-12, "speed must never increase while braking");
			assertTrue(speed >= 0, "speed must never go negative");
			prev = speed;
			steps++;
		}
		assertTrue(speed <= 0.01, "air brake should stop the freight consist, got " + speed);
		assertTrue(controller.getBrakeCylinderPressure() > 0.5, "brake cylinder should be engaged after stopping");
	}

	@Test
	public void testSteplessReachesTargetApproximately() {
		final ConsistType lr = ConsistType.fromJson(jsonObject("STEPLESS", 80, 42_000, 60_000));
		final SteplessDriveController controller = new SteplessDriveController();
		double speed = 0;
		final ControlState full = ControlState.zero().setThrottleAxis(1.0);
		// long run at full axis -> approach max speed asymptotically
		for (int i = 0; i < 3000; i++) { // 300 s
			speed = ConsistDynamics.step(speed, controller.compute(full, lr, speed, DT_MS), lr, DT_MS);
		}
		assertTrue(speed > lr.getMaxSpeedMetersPerSecond() * 0.99, "stepless should reach near max speed, got " + speed);
		assertTrue(speed <= lr.getMaxSpeedMetersPerSecond() + 1e-9, "never exceed max speed");
	}

	/** 力模型口径（notes/235）：质量 60 t、λ=1，牵引/制动给**牛顿**。 */
	private static com.google.gson.JsonObject jsonObject(String mode, double maxKmh, double tractiveEffortN, double serviceBrakeForceN) {
		final com.google.gson.JsonObject json = new com.google.gson.JsonObject();
		json.addProperty("id", "lr");
		json.addProperty("controlMode", mode);
		json.addProperty("maxSpeedKmh", maxKmh);
		json.addProperty("massKg", 60_000);
		json.addProperty("rotatingMassFactor", 1.0);
		json.addProperty("maxTractiveEffortN", tractiveEffortN);
		json.addProperty("maxPowerW", tractiveEffortN * maxKmh / 3.6);
		json.addProperty("serviceBrakeForceN", serviceBrakeForceN);
		json.addProperty("emergencyBrakeForceN", serviceBrakeForceN * 1.5);
		return json;
	}
}