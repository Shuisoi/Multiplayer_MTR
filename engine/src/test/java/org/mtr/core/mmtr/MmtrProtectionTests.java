package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure overrun/SPAD protection decision and its lock duration.
 */
public final class MmtrProtectionTests {

	// Legacy emergency envelope used by the engine (m/ms^2) ~= 1.2 m/s^2 in these units.
	private static final double EMERGENCY = 0.0000012;

	@Test
	public void stoppedVehicleNeverProtects() {
		assertFalse(MmtrProtection.requiresProtection(0, 100, EMERGENCY));
		assertFalse(MmtrProtection.requiresProtection(-1, 100, EMERGENCY));
	}

	@Test
	public void pastStoppingPointProtectsWhileMoving() {
		assertTrue(MmtrProtection.requiresProtection(0.001, 0, EMERGENCY), "past the stopping point");
		assertTrue(MmtrProtection.requiresProtection(0.001, -5, EMERGENCY));
	}

	@Test
	public void comfortableStopDoesNotProtect() {
		// At 20 m/s (0.02 m/ms), stopping at 0.9 m/s^2 needs 222 m; give 500 m -> no trip.
		assertFalse(MmtrProtection.requiresProtection(0.02, 500, EMERGENCY));
	}

	@Test
	public void cannotStopInTimeProtects() {
		// At 20 m/s with only 100 m left, required decel is 2 m/s^2 > 1.26 m/s^2 -> trip.
		assertTrue(MmtrProtection.requiresProtection(0.02, 100, EMERGENCY));
	}

	@Test
	public void thresholdMarginBoundary() {
		// Exactly at margin (1.05 x emergency) must NOT trip; slightly above must.
		final double speed = 0.02;
		final double distanceForMargin = 0.5 * speed * speed / (EMERGENCY * 1.05);
		assertFalse(MmtrProtection.requiresProtection(speed, distanceForMargin + 0.01, EMERGENCY));
		assertTrue(MmtrProtection.requiresProtection(speed, distanceForMargin - 0.01, EMERGENCY));
	}

	@Test
	public void lockDurationIsTenSeconds() {
		assertTrue(MmtrProtection.LOCK_MILLIS == 10_000L, "lock should be 10 seconds");
	}
}
