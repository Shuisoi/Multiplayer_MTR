package org.mtr.core.mmtr;

/**
 * Notched ("有级") controller: throttle and brake are discrete notches mapped onto the
 * consist's performance envelope. Brake input overrides traction whenever brake > 0.
 */
public final class NotchedDriveController implements DriveController {

	@Override
	public DriveOutput compute(ControlState control, ConsistType type, double speedMetersPerSecond, long dtMillis) {
		if (control.isEmergency()) {
			return new DriveOutput(-type.getEmergencyDecelerationMps2(), true, true, 0, 1);
		}

		final int throttle = clamp(control.getThrottleNotch(), 0, type.getPowerNotches());
		final int brake = clamp(control.getBrakeNotch(), 0, type.getBrakeNotches());

		if (brake > 0) {
			final double ratio = (double) brake / type.getBrakeNotches();
			final double decel = type.getServiceBrakeDecelerationMps2() * ratio;
			final boolean lamp = decel > 0.01;
			return new DriveOutput(-decel, lamp, false, 1, ratio);
		}

		if (throttle > 0) {
			final double ratio = (double) throttle / type.getPowerNotches();
			final double maxSpeed = type.getMaxSpeedMetersPerSecond();
			// simple linear taper toward top speed; full notch holds speed at max
			final double speedRatio = Math.max(0, 1 - speedMetersPerSecond / maxSpeed);
			final double accel = type.getTractionAccelerationMps2() * ratio * speedRatio;
			return new DriveOutput(accel, false, false, 1, 0);
		}

		return DriveOutput.coast();
	}

	@Override
	public void reset() {
		// stateless
	}

	private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
