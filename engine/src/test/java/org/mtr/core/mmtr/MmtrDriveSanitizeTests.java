package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server-authoritative input guard: whatever a client sends over the wire must be clamped before
 * it is stored/mirrored, so a hostile or buggy client cannot push physics past sane ranges.
 */
public final class MmtrDriveSanitizeTests {

	@Test
	public void clampsOutOfRangeNotchesReverserAndAxes() {
		final ControlState hostile = new ControlState()
			.setThrottleNotch(999)
			.setBrakeNotch(-5)
			.setReverser(7)
			.setThrottleAxis(3.5)
			.setBrakeAxis(-2.0)
			.setEmergency(true);

		MmtrDriveAccess.sanitize(hostile);

		assertEquals(MmtrDriveAccess.MAX_NOTCH, hostile.getThrottleNotch(), "throttle notch must be clamped to the upper bound");
		assertEquals(0, hostile.getBrakeNotch(), "brake notch must be clamped to zero");
		assertEquals(1, hostile.getReverser(), "reverser must be clamped to +1");
		assertTrue(hostile.getThrottleAxis() <= 1, "throttle axis must be clamped to 1");
		assertTrue(hostile.getBrakeAxis() >= -1, "brake axis must be clamped to -1");
		assertTrue(hostile.isEmergency(), "emergency is a plain boolean and passes through");
	}

	@Test
	public void leavesValidControlUnchanged() {
		final ControlState valid = new ControlState()
			.setThrottleNotch(5)
			.setBrakeNotch(2)
			.setReverser(-1);
		MmtrDriveAccess.sanitize(valid);
		assertEquals(5, valid.getThrottleNotch());
		assertEquals(2, valid.getBrakeNotch());
		assertEquals(-1, valid.getReverser());
	}
}
