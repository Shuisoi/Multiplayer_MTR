package org.mtr.core.mmtr;

/**
 * Stepless ("无级") controller driven by HID axes. Throttle axis selects a target speed
 * (position = fraction of max speed) approached at the consist's traction limit; brake axis
 * applies proportional service braking. Notch inputs are ignored by this mode.
 */
public final class SteplessDriveController implements DriveController {

	@Override
	public DriveOutput compute(ControlState control, ConsistType type, double speedMetersPerSecond, long dtMillis) {
		if (control.isEmergency()) {
			return new DriveOutput(-type.getEmergencyDecelerationMps2(), true, true, 0, 1);
		}

		final double throttleAxis = Math.max(0, control.getThrottleAxis());
		final double brakeAxis = Math.max(0, control.getBrakeAxis());

		if (brakeAxis > 0.001) {
			final double decel = type.getServiceBrakeDecelerationMps2() * brakeAxis;
			return new DriveOutput(-decel, decel > 0.01, false, 1, brakeAxis);
		}

		if (throttleAxis > 0.001) {
			final double maxSpeed = type.getMaxSpeedMetersPerSecond();
			final double targetSpeed = throttleAxis * maxSpeed;
			final double delta = targetSpeed - speedMetersPerSecond;
			// approach the selected speed at the consist's traction limit
			final double accel = Math.max(0, Math.min(type.getTractionAccelerationMps2(), delta * 0.25));
			return new DriveOutput(accel, false, false, 1, 0);
		}

		return DriveOutput.coast();
	}

	@Override
	public void reset() {
		// stateless
	}
}
