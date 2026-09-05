package org.mtr.core.mmtr;

/**
 * Pluggable longitudinal controller for a consist.
 *
 * <p>Pure headless step: takes the unified {@link ControlState}, the {@link ConsistType}
 * performance envelope and the current signed speed, returns the longitudinal acceleration
 * the physics layer should apply this tick. Controllers that carry state (e.g. air-brake
 * pipe/cylinder pressure) keep it in their instance; create one controller per consist.</p>
 */
public interface DriveController {

	DriveOutput compute(ControlState control, ConsistType type, double speedMetersPerSecond, long dtMillis);

	/** Reset any internal state (re-coupling, respawn, mode switch). */
	void reset();
}
