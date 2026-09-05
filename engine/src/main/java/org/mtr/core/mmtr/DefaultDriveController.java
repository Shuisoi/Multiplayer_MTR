package org.mtr.core.mmtr;

/**
 * Baseline controller matching the legacy simple behaviour (acceleration/braking constants):
 * used to prove the new pipeline does not change default handling.
 */
public final class DefaultDriveController implements DriveController {

	@Override
	public DriveOutput compute(ControlState control, ConsistType type, double speedMetersPerSecond, long dtMillis) {
		if (control.isEmergency()) {
			return new DriveOutput(-type.getEmergencyDecelerationMps2(), true, true, 0, 1);
		}
		final boolean brakeRequested = control.getBrakeNotch() > 0 || control.getBrakeAxis() > 0.05;
		if (brakeRequested) {
			return new DriveOutput(-type.getServiceBrakeDecelerationMps2(), true, false, 1, 1);
		}
		final boolean throttleRequested = control.getThrottleNotch() > 0 || control.getThrottleAxis() > 0.05;
		if (throttleRequested) {
			final double maxSpeed = type.getMaxSpeedMetersPerSecond();
			final double capped = speedMetersPerSecond >= maxSpeed ? 0 : type.getTractionAccelerationMps2();
			return new DriveOutput(capped, false, false, 1, 0);
		}
		return DriveOutput.coast();
	}

	@Override
	public void reset() {
		// stateless
	}
}
