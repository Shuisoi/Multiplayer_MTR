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
			.setDriveHandle(-40)
			.setCruiseSpeedKmh(120)
			.setReverser(-1);
		MmtrDriveAccess.sanitize(valid);
		assertEquals(5, valid.getThrottleNotch());
		assertEquals(2, valid.getBrakeNotch());
		assertEquals(-40, valid.getDriveHandle());
		assertEquals(120, valid.getCruiseSpeedKmh());
		assertEquals(-1, valid.getReverser());
	}

	/** 三手柄的两个新字段同样要有外层守卫：a hostile client must not push them out of range. */
	@Test
	public void clampsDriveHandleAndCruiseSpeed() {
		final ControlState hostile = new ControlState()
			.setDriveHandle(9999)
			.setCruiseSpeedKmh(99999);
		MmtrDriveAccess.sanitize(hostile);
		assertEquals(MmtrDriveAccess.MAX_DRIVE_HANDLE, hostile.getDriveHandle(), "油门手柄钳到外层上限");
		assertEquals(MmtrDriveAccess.MAX_CRUISE_KMH, hostile.getCruiseSpeedKmh(), "定速值钳到外层上限");

		final ControlState negative = new ControlState().setDriveHandle(-9999).setCruiseSpeedKmh(-50);
		MmtrDriveAccess.sanitize(negative);
		assertEquals(-MmtrDriveAccess.MAX_DRIVE_HANDLE, negative.getDriveHandle(), "负侧同样钳位");
		assertEquals(0, negative.getCruiseSpeedKmh(), "定速不能为负");
	}
}
