package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public final class DriveControllerTests {

	private static final String SAMPLE_JSON = "{"
		+ "  \"consistTypes\": ["
		+ "    {\"id\":\"emu\",\"name\":\"EMU\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "     \"maxSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5},"
		+ "    {\"id\":\"lr\",\"name\":\"LR\",\"controlMode\":\"STEPLESS\",\"maxSpeedKmh\":80,"
		+ "     \"tractionAccelerationMps2\":0.7,\"serviceBrakeDecelerationMps2\":1.0,\"emergencyDecelerationMps2\":1.6},"
		+ "    {\"id\":\"freight\",\"name\":\"Freight\",\"controlMode\":\"AIR_BRAKE\",\"powerNotches\":8,\"brakeNotches\":3,"
		+ "     \"maxSpeedKmh\":100,\"tractionAccelerationMps2\":0.3,\"serviceBrakeDecelerationMps2\":0.7,\"emergencyDecelerationMps2\":1.2,"
		+ "     \"airPipeChargeRatePerSecond\":0.12,\"airPipeDischargeRatePerSecond\":0.5,"
		+ "     \"airBrakeApplyRatePerSecond\":0.2,\"airBrakeReleaseRatePerSecond\":0.08}"
		+ "  ]"
		+ "}";

	@Test
	public void testRegistryParsesSample() {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(SAMPLE_JSON);
		assertTrue(registry.contains("emu"));
		assertTrue(registry.contains("lr"));
		assertTrue(registry.contains("freight"));
		assertEquals(ConsistType.ControlMode.NOTCHED, registry.get("emu").getControlMode());
		assertEquals(7, registry.get("emu").getPowerNotches());
		assertEquals(80 / 3.6, registry.get("lr").getMaxSpeedMetersPerSecond(), 1e-9);
	}

	@Test
	public void testRegistryDefaultsFallback() {
		final ConsistType type = ConsistType.fromJson(new com.google.gson.JsonObject());
		assertEquals(ConsistType.ControlMode.DEFAULT, type.getControlMode());
		assertEquals(7, type.getPowerNotches());
		assertTrue(type.getTractionAccelerationMps2() > 0);
	}

	@Test
	public void testDefaultControllerMatchesLegacyBehaviour() {
		final ConsistType type = ConsistTypeRegistry.parse(SAMPLE_JSON).get("emu");
		final DefaultDriveController controller = new DefaultDriveController();
		// emergency brakes hard
		final ControlState emergency = ControlState.zero().setEmergency(true);
		assertEquals(-1.5, controller.compute(emergency, type, 0, 100).getAccelerationMetersPerSecondSquared(), 1e-9);
		// any brake -> service deceleration
		final ControlState braking = ControlState.zero().setBrakeNotch(1);
		assertEquals(-0.9, controller.compute(braking, type, 10, 100).getAccelerationMetersPerSecondSquared(), 1e-9);
		// throttle accelerates at standstill
		final ControlState throttle = ControlState.zero().setThrottleNotch(1);
		assertEquals(0.6, controller.compute(throttle, type, 0, 100).getAccelerationMetersPerSecondSquared(), 1e-9);
		// above top speed -> coast (no further acceleration)
		final double overSpeed = type.getMaxSpeedMetersPerSecond() + 1;
		assertEquals(0, controller.compute(throttle, type, overSpeed, 100).getAccelerationMetersPerSecondSquared(), 1e-9);
		// neutral -> coast
		assertEquals(0, controller.compute(ControlState.zero(), type, 5, 100).getAccelerationMetersPerSecondSquared(), 1e-9);
	}

	@Test
	public void testNotchedControllerMonotonicAndBounded() {
		final ConsistType type = ConsistTypeRegistry.parse(SAMPLE_JSON).get("emu");
		final NotchedDriveController controller = new NotchedDriveController();
		double previous = -1;
		for (int notch = 0; notch <= 7; notch++) {
			final double accel = controller.compute(ControlState.zero().setThrottleNotch(notch), type, 0, 100)
				.getAccelerationMetersPerSecondSquared();
			assertTrue(accel >= previous, "accel should not decrease with notch at standstill");
			previous = accel;
		}
		assertEquals(0.6, previous, 1e-9); // full notch at standstill == traction limit
		// braking at full notch -> service deceleration, brake overrides throttle
		final ControlState both = ControlState.zero().setThrottleNotch(7).setBrakeNotch(8);
		final DriveOutput output = controller.compute(both, type, 10, 100);
		assertEquals(-0.9, output.getAccelerationMetersPerSecondSquared(), 1e-9);
		assertTrue(output.isBrakeLamp());
		// out-of-range notches are clamped
		final double clamped = controller.compute(ControlState.zero().setBrakeNotch(99), type, 10, 100)
			.getAccelerationMetersPerSecondSquared();
		assertEquals(-0.9, clamped, 1e-9);
	}

	@Test
	public void testControlStateNotchStepsClamp() {
		final ControlState state = ControlState.zero();
		state.stepThrottle(5, 7);
		state.stepThrottle(5, 7); // would be 10 -> clamp to 7
		assertEquals(7, state.getThrottleNotch());
		state.stepThrottle(-99, 7);
		assertEquals(0, state.getThrottleNotch());
		state.stepBrake(-3, 8); // stays 0
		assertEquals(0, state.getBrakeNotch());
		state.stepReverser(5, -1, 1);
		assertEquals(1, state.getReverser());
	}

	@Test
	public void testSteplessController() {
		final ConsistType type = ConsistTypeRegistry.parse(SAMPLE_JSON).get("lr");
		final SteplessDriveController controller = new SteplessDriveController();
		// brake axis proportional
		final DriveOutput halfBrake = controller.compute(ControlState.zero().setBrakeAxis(0.5), type, 10, 100);
		assertEquals(-0.5, halfBrake.getAccelerationMetersPerSecondSquared(), 1e-9);
		// throttle axis below current speed accelerates; bounded by traction limit
		final DriveOutput power = controller.compute(ControlState.zero().setThrottleAxis(1.0), type, 0, 100);
		assertTrue(power.getAccelerationMetersPerSecondSquared() > 0 && power.getAccelerationMetersPerSecondSquared() <= 0.7 + 1e-9);
		// above target speed -> coast (no negative from throttle)
		final DriveOutput atTarget = controller.compute(ControlState.zero().setThrottleAxis(0.5), type, type.getMaxSpeedMetersPerSecond(), 100);
		assertTrue(atTarget.getAccelerationMetersPerSecondSquared() >= 0);
		// idle -> coast
		assertEquals(0, controller.compute(ControlState.zero(), type, 5, 100).getAccelerationMetersPerSecondSquared(), 1e-9);
	}

	@Test
	public void testAirBrakeReleaseLapApplyCycle() {
		final ConsistType type = ConsistTypeRegistry.parse(SAMPLE_JSON).get("freight");
		final AirBrakeController controller = new AirBrakeController();
		assertEquals(1.0, controller.getPipePressure(), 1e-9);

		// APPLY full brake notch -> cylinder rises toward 1, decel reaches ~service max
		final ControlState apply = ControlState.zero().setBrakeNotch(3);
		for (int i = 0; i < 120; i++) {
			controller.compute(apply, type, 10, 100);
		}
		assertTrue(controller.getBrakeCylinderPressure() > 0.95, "cylinder should be near full after sustained apply");
		assertTrue(controller.getPipePressure() < 0.05, "pipe should be nearly vented");
		final DriveOutput applied = controller.compute(apply, type, 10, 100);
		assertEquals(-0.7, applied.getAccelerationMetersPerSecondSquared(), 0.05);
		assertTrue(applied.isBrakeLamp());

		// RELEASE (notch 0) -> cylinder releases
		final ControlState release = ControlState.zero().setBrakeNotch(0);
		for (int i = 0; i < 200; i++) {
			controller.compute(release, type, 10, 100);
		}
		assertTrue(controller.getBrakeCylinderPressure() < 0.05, "cylinder should release");
		assertTrue(controller.getPipePressure() > 0.95, "pipe should recharge");

		// emergency vents fast & applies full
		controller.reset();
		final ControlState emergency = ControlState.zero().setEmergency(true);
		for (int i = 0; i < 40; i++) {
			controller.compute(emergency, type, 10, 100);
		}
		assertTrue(controller.getBrakeCylinderPressure() > 0.99);
		assertTrue(controller.getPipePressure() < 0.01);
		final DriveOutput emOut = controller.compute(emergency, type, 10, 100);
		assertTrue(emOut.isEmergencyBrake());
	}
}
