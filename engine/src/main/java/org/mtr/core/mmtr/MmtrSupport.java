package org.mtr.core.mmtr;

/**
 * Conversion helpers between the legacy single-handle power level (engine internal,
 * per-millisecond units) and the MMTR control model (SI units).
 */
public final class MmtrSupport {

	/** The legacy handle uses -(MAX_POWER_LEVEL + 1) as its emergency position (engine uses -8). */
	public static final int LEGACY_EMERGENCY_POWER_LEVEL = -8;

	private MmtrSupport() {
	}

	/** SI acceleration (m/s^2) -> engine internal (m/ms^2). */
	public static double siAccelerationToInternal(double metersPerSecondSquared) {
		return metersPerSecondSquared * 1e-6;
	}

	/** Engine internal speed (m/ms) -> SI (m/s). */
	public static double internalSpeedToSi(double metersPerMillisecond) {
		return metersPerMillisecond * 1000.0;
	}

	/** SI speed (m/s) -> engine internal (m/ms). */
	public static double siSpeedToInternal(double metersPerSecond) {
		return metersPerSecond * 0.001;
	}

	/**
	 * Maps the legacy combined power handle onto the unified {@link ControlState}.
	 * The handle ranges from -8 (emergency) ... -1 (service brake) 0 (coast) 1..7 (power).
	 */
	public static ControlState controlFromLegacyPowerLevel(int powerLevel, int powerNotches, int brakeNotches) {
		final ControlState state = new ControlState();
		if (powerLevel <= LEGACY_EMERGENCY_POWER_LEVEL) {
			state.setEmergency(true);
		} else if (powerLevel > 0) {
			state.setThrottleNotch(Math.min(powerLevel, powerNotches));
		} else if (powerLevel < 0) {
			state.setBrakeNotch(Math.min(-powerLevel, brakeNotches));
		}
		return state;
	}
}
