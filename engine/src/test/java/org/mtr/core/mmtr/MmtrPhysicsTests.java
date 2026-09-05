package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public final class MmtrPhysicsTests {

	@Test
	public void testConstantPowerTaperBeyondBreakpoint() {
		final ConsistType type = ConsistTypeRegistry.parse(json(45, 0.03, 0.0004, 0.00001)).get("emu");
		final NotchedDriveController controller = new NotchedDriveController();
		final ControlState full = ControlState.zero().setThrottleNotch(7);
		// below breakpoint: ~full a0 minus resistance
		final double low = controller.compute(full, type, 5, 100).getAccelerationMetersPerSecondSquared();
		assertTrue(low > 0.5, "expected strong accel below breakpoint, got " + low);
		// far above breakpoint: constant-power taper cuts traction strongly
		final double high = controller.compute(full, type, 30, 100).getAccelerationMetersPerSecondSquared();
		assertTrue(high < low, "constant-power region must taper traction with speed");
		assertTrue(high >= -0.1, "should not brake hard at high speed under full throttle, got " + high);
	}

	@Test
	public void testCoastDecaysWithResistance() {
		final ConsistType type = ConsistTypeRegistry.parse(json(80, 0.05, 0.001, 0.00002)).get("emu");
		final NotchedDriveController controller = new NotchedDriveController();
		final DriveOutput coast = controller.compute(ControlState.zero(), type, 10, 100);
		assertTrue(coast.getAccelerationMetersPerSecondSquared() < 0, "coasting with resistance must decelerate");
	}

	@Test
	public void testZeroParamsPreserveLegacyBehaviour() {
		final ConsistType type = ConsistTypeRegistry.parse(json(80, 0, 0, 0)).get("emu");
		final NotchedDriveController controller = new NotchedDriveController();
		assertEquals(0, controller.compute(ControlState.zero(), type, 10, 100).getAccelerationMetersPerSecondSquared(), 1e-9);
		final double fullStandstill = controller.compute(ControlState.zero().setThrottleNotch(7), type, 0, 100).getAccelerationMetersPerSecondSquared();
		assertEquals(0.6, fullStandstill, 1e-9);
	}

	private static String json(double breakpointKmh, double a, double b, double c) {
		return "{\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
			+ "\"maxSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5,"
			+ "\"tractionBreakpointKmh\":" + breakpointKmh + ",\"resistanceA\":" + a + ",\"resistanceB\":" + b + ",\"resistanceC\":" + c + "}]}";
	}
}
