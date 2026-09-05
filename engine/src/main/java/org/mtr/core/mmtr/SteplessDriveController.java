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
			final double decel = type.getServiceBrakeDecelerationMps2() * brakeAxis + MmtrPhysics.brakingResistance(type, speedMetersPerSecond);
			return new DriveOutput(-decel, decel > 0.01, false, 1, brakeAxis);
		}

		if (throttleAxis > 0.001) {
			final double maxSpeed = type.getMaxSpeedMetersPerSecond();
			final double targetSpeed = throttleAxis * maxSpeed;
			final double delta = targetSpeed - speedMetersPerSecond;
			// approach the selected speed, but never exceed the physical tractive envelope
			final double physical = MmtrPhysics.tractionAcceleration(type, throttleAxis, speedMetersPerSecond);
			final double approach = Math.max(0, delta * 0.25);
			final double accel = Math.min(physical, approach);
			return new DriveOutput(accel, false, false, 1, 0);
		}

		// coasting decays with running resistance (0 by default keeps legacy behaviour)
		return new DriveOutput(-MmtrPhysics.resistance(type, speedMetersPerSecond), false, false, 1, 0);
	}

	@Override
	public void reset() {
		// stateless
	}
}