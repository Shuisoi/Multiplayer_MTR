package org.mtr.core.mmtr;

import org.mtr.core.mmtr.physics.TrainPhysics;

/**
 * Baseline controller matching the legacy simple behaviour (full handle = full envelope):
 * used to prove the new pipeline does not change default handling.
 *
 * <p>力学全部来自 {@link TrainPhysics}（**力 → 加速度**），不再有"加速度常数 × 比例"那种算法（notes/235）。</p>
 */
public final class DefaultDriveController implements DriveController {

	@Override
	public DriveOutput compute(ControlState control, ConsistType type, double speedMetersPerSecond, long dtMillis) {
		final TrainPhysics physics = type.getPhysics();
		if (control.isEmergency()) {
			return new DriveOutput(-physics.emergencyDecelerationMps2(speedMetersPerSecond), true, true, 0, 1);
		}
		final boolean brakeRequested = control.getBrakeNotch() > 0 || control.getBrakeAxis() > 0.05;
		if (brakeRequested) {
			return new DriveOutput(-physics.serviceBrakeDecelerationMps2(1, speedMetersPerSecond), true, false, 1, 1);
		}
		final boolean throttleRequested = control.getThrottleNotch() > 0 || control.getThrottleAxis() > 0.05;
		if (throttleRequested) {
			final double maxSpeed = type.getMaxSpeedMetersPerSecond();
			final double capped = speedMetersPerSecond >= maxSpeed ? 0 : physics.tractionAccelerationMps2(1, speedMetersPerSecond);
			return new DriveOutput(capped, false, false, 1, 0);
		}
		return DriveOutput.coast();
	}

	@Override
	public void reset() {
		// stateless
	}
}
