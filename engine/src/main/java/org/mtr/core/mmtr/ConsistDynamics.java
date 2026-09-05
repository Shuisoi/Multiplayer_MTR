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

	/**
	 * Linear resistance (simplified Davis substitute) subtracted from acceleration.
	 * @param speedMetersPerSecond current signed speed (magnitude used)
	 */
	public static double resistance(double speedMetersPerSecond, double resistanceCoefficient) {
		final double v = Math.abs(speedMetersPerSecond);
		return resistanceCoefficient * v; // N per (kg*m/s) -> m/s^2 coefficient form, small
	}
}
