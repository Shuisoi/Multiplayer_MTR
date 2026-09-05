package org.mtr.core.mmtr;

/**
 * Longitudinal integration for a consist along its rail path (the math that the Vehicle tick
 * will consume once controllers are wired in).
 *
 * <p>Kept deliberately small and pure so it can be unit-tested headless and reused verbatim
 * inside {@code Vehicle.simulateMoving} later.</p>
 */
public final class ConsistDynamics {

	private ConsistDynamics() {
	}

	/**
	 * Advances signed speed by one tick under a controller output.
	 *
	 * @param speedMetersPerSecond current signed speed (positive = forward along the path)
	 * @param output               controller output
	 * @param type                 consist performance envelope (max speed, reverser semantics)
	 * @param dtMillis             tick length
	 * @return the new signed speed
	 */
	public static double step(double speedMetersPerSecond, DriveOutput output, ConsistType type, long dtMillis) {
		final double dt = Math.max(1, dtMillis) / 1000.0;
		double speed = speedMetersPerSecond;

		final double acceleration = output.getAccelerationMetersPerSecondSquared();
		speed += acceleration * dt;

		// never move backwards from braking or from coasting; controller must not produce a
		// negative request unless the consist is legitimately reversing (handled by reverser later)
		if (output.isEmergencyBrake() || output.isBrakeLamp()) {
			speed = Math.max(0, speed);
		} else {
			speed = Math.max(0, speed);
		}

		final double maxSpeed = type.getMaxSpeedMetersPerSecond();
		speed = Math.min(speed, maxSpeed);
		return speed;
	}

	/**
	 * Distance travelled in one tick given start and end speed (trapezoidal).
	 */
	public static double distanceTravelled(double startSpeed, double endSpeed, long dtMillis) {
		return (startSpeed + endSpeed) / 2.0 * (Math.max(1, dtMillis) / 1000.0);
	}

	/** Result of a sub-stepped integration: final speed and total distance covered. */
	public static final class SpeedDistance {
		public final double speedMetersPerSecond;
		public final double distanceMeters;

		public SpeedDistance(double speedMetersPerSecond, double distanceMeters) {
			this.speedMetersPerSecond = speedMetersPerSecond;
			this.distanceMeters = distanceMeters;
		}
	}

	/**
	 * Produces the controller output for one integration sub-step.
	 */
	@FunctionalInterface
	public interface OutputProvider {
		DriveOutput compute(double speedMetersPerSecond, long dtMillis);
	}

	/**
	 * Advances speed over {@code dtMillis} using fixed sub-steps of at most
	 * {@code subStepMillis}. Stateful controllers (e.g. air-brake pipe/cylinder) are integrated
	 * at the fine granularity, which keeps stiff dynamics stable and identical on the server and
	 * on mirrored clients regardless of the outer tick/frame length. The remaining (non-divisible)
	 * tail is consumed as its own shorter sub-step so the total simulated time is exact.
	 *
	 * @return final speed and the trapezoidal distance covered
	 */
	public static SpeedDistance advance(double speedMetersPerSecond, ConsistType type, long dtMillis, long subStepMillis, OutputProvider output) {
		final long subStep = Math.max(1, subStepMillis);
		double speed = speedMetersPerSecond;
		double distance = 0;
		long remaining = Math.max(0, dtMillis);
		while (remaining > 0) {
			final long stepMillis = Math.min(subStep, remaining);
			remaining -= stepMillis;
			final double startSpeed = speed;
			speed = step(speed, output.compute(speed, stepMillis), type, stepMillis);
			distance += distanceTravelled(startSpeed, speed, stepMillis);
		}
		return new SpeedDistance(speed, distance);
	}

	/**
	 * Linear resistance (simplified Davis substitute) subtracted from acceleration.
	 * @param speedMetersPerSecond current signed speed (magnitude used)
	 */
	public static double resistance(double speedMetersPerSecond, double resistanceCoefficient) {
		final double v = Math.abs(speedMetersPerSecond);
		return resistanceCoefficient * v; // N per (kg*m/s) -> m/s^2 coefficient form, small
	}
}