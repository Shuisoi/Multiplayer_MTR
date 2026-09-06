package org.mtr.core.mmtr;

import org.jspecify.annotations.Nullable;

import java.util.UUID;
import org.mtr.core.mmtr.ControlState;

/**
 * Server-authoritative rules for who may take explicit (MMTR) control of a consist.
 *
 * <p>Implements a SimRail-style occupation lock: a consist has at most one active MMTR driver,
 * and control commands are only honoured when they come from the player currently occupying a
 * cab driver seat of that consist. A new driver may take over once the previous driver no longer
 * rides as a driver (dismounted, switched to another vehicle, or disconnected).</p>
 */
public final class MmtrDriveAccess {

	private MmtrDriveAccess() {
	}


	/**
	 * Hard bounds for values arriving over the wire (a hostile/buggy client must not push the
	 * physics past sane ranges). Controllers clamp again to their ConsistType notch counts;
	 * this is the outer, type-independent guard.
	 */
	public static final int MAX_NOTCH = 16;

	/**
	 * Server-side sanitisation of an incoming ControlState (client -> server). Notches, reverser
	 * and HID axes are clamped to valid ranges before they are stored or mirrored; emergency
	 * remains a plain boolean.
	 */
	public static void sanitize(ControlState state) {
		state.setThrottleNotch(clamp(state.getThrottleNotch(), 0, MAX_NOTCH));
		state.setBrakeNotch(clamp(state.getBrakeNotch(), 0, MAX_NOTCH));
		state.setReverser(clamp(state.getReverser(), -1, 1));
		state.setThrottleAxis(clamp(state.getThrottleAxis(), -1, 1));
		state.setBrakeAxis(clamp(state.getBrakeAxis(), -1, 1));
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(value, max));
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(value, max));
	}
	/**
	 * @param senderIsRidingDriver    whether {@code sender} currently occupies a cab driver seat
	 *                                of the consist
	 * @param overrideActive          whether an MMTR manual override is currently held
	 * @param currentDriver           the driver currently holding the override ({@code null} when none)
	 * @param sender                  the player trying to control the consist
	 * @param currentDriverStillRiding whether the current holder still occupies a cab driver seat
	 * @return {@code true} when the control command from {@code sender} should be applied
	 */
	public static boolean canControl(boolean senderIsRidingDriver, boolean overrideActive, @Nullable UUID currentDriver, @Nullable UUID sender, boolean currentDriverStillRiding) {
		if (sender == null || !senderIsRidingDriver) {
			return false;
		}
		if (!overrideActive || currentDriver == null) {
			return true;
		}
		if (currentDriver.equals(sender)) {
			return true;
		}
		// The current holder left the cab: allow a (new) cab driver to take over.
		return !currentDriverStillRiding;
	}

	/**
	 * An MMTR override should be dropped automatically when its driver no longer occupies a cab
	 * driver seat, otherwise stale notch state keeps driving a consist nobody is controlling.
	 */
	public static boolean shouldAutoRelease(boolean overrideActive, @Nullable UUID currentDriver, boolean currentDriverStillRiding) {
		return overrideActive && currentDriver != null && !currentDriverStillRiding;
	}
}