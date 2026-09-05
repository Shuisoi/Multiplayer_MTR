package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the SimRail-style driver occupation lock rules. */
public class MmtrDriveAccessTests {

	private static final UUID DRIVER_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
	private static final UUID DRIVER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
	private static final UUID PASSENGER = UUID.fromString("00000000-0000-0000-0000-00000000000c");

	@Test
	public void noOverrideAnyCabDriverCanTakeControl() {
		assertTrue(MmtrDriveAccess.canControl(true, false, null, DRIVER_A, false));
		assertTrue(MmtrDriveAccess.canControl(true, false, DRIVER_A, DRIVER_B, false));
	}

	@Test
	public void nonDriverCannotControl() {
		assertFalse(MmtrDriveAccess.canControl(false, false, null, PASSENGER, false));
		assertFalse(MmtrDriveAccess.canControl(false, true, DRIVER_A, PASSENGER, true));
		assertFalse(MmtrDriveAccess.canControl(true, true, DRIVER_A, DRIVER_B, true));
	}

	@Test
	public void holderKeepsControlWhileRiding() {
		assertTrue(MmtrDriveAccess.canControl(true, true, DRIVER_A, DRIVER_A, true));
		// A second cab driver cannot steal control while the holder still rides.
		assertFalse(MmtrDriveAccess.canControl(true, true, DRIVER_A, DRIVER_B, true));
	}

	@Test
	public void takeoverAllowedWhenHolderLeftCab() {
		assertTrue(MmtrDriveAccess.canControl(true, true, DRIVER_A, DRIVER_B, false));
	}

	@Test
	public void autoReleaseWhenHolderLeaves() {
		assertTrue(MmtrDriveAccess.shouldAutoRelease(true, DRIVER_A, false));
		assertFalse(MmtrDriveAccess.shouldAutoRelease(true, DRIVER_A, true));
		assertFalse(MmtrDriveAccess.shouldAutoRelease(false, DRIVER_A, false));
		assertFalse(MmtrDriveAccess.shouldAutoRelease(true, null, false));
	}
}
