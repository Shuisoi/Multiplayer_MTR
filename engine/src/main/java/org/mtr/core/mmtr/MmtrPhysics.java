package org.mtr.core.mmtr;

/**
 * Longitudinal physics helpers shared by the MMTR controllers.
 *
 * <p>Units: speeds in m/s, accelerations in m/s^2. The default parameter values keep the
 * behaviour identical to the pre-physics implementation (no breakpoint taper, no resistance),
 * so enabling "physical feel" is purely a ConsistType configuration choice.</p>
 */
public final class MmtrPhysics {

	private MmtrPhysics() {
	}

	/**
	 * Tractive acceleration at the given forward speed for a throttle ratio (0..1):
	 * constant tractive effort up to the breakpoint, constant power (1/v) taper beyond it,
	 * minus rolling/air resistance.
	 */
	public static double tractionAcceleration(ConsistType type, double throttleRatio, double speedMetersPerSecond) {
		final double a0 = type.getTractionAccelerationMps2() * clamp(throttleRatio);
		final double breakpoint = type.getTractionBreakpointKmh() / 3.6;
		final double traction = speedMetersPerSecond > breakpoint ? a0 * breakpoint / speedMetersPerSecond : a0;
		return traction - resistance(type, speedMetersPerSecond);
	}

	/**
	 * Coasting deceleration caused by running resistance (Davis-style A + B*v + C*v^2,
	 * expressed directly in m/s^2 so it can be tuned without masses/forces).
	 */
	public static double resistance(ConsistType type, double speedMetersPerSecond) {
		final double v = Math.max(0, speedMetersPerSecond);
		return type.getResistanceA() + type.getResistanceB() * v + type.getResistanceC() * v * v;
	}

	/** Extra braking magnitude contributed by resistance while moving. */
	public static double brakingResistance(ConsistType type, double speedMetersPerSecond) {
		return resistance(type, speedMetersPerSecond);
	}

	private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
}
