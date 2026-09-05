package org.mtr.core.mmtr;

/**
 * Release-Lap-Apply ("降保升") automatic air-brake controller.
 *
 * <p>State machine over train-pipe pressure ({@code pipePressure}, 0..1 = charged) and
 * brake-cylinder pressure ({@code brakeCylinderPressure}, 0..1 = fully applied):</p>
 * <ul>
 *   <li>brakeNotch == 0 -> RELEASE: pipe charges toward 1, cylinder releases toward 0</li>
 *   <li>brakeNotch mid  -> LAP/APPLY: pipe drops proportional to notch, cylinder rises</li>
 *   <li>emergency       -> pipe vents fast, cylinder to full</li>
 * </ul>
 * Braking force is proportional to cylinder pressure up to service maximum.
 */
public final class AirBrakeController implements DriveController {

	private double pipePressure = 1.0;
	private double brakeCylinderPressure = 0.0;

	@Override
	public DriveOutput compute(ControlState control, ConsistType type, double speedMetersPerSecond, long dtMillis) {
		final double dt = Math.max(1, dtMillis) / 1000.0;

		if (control.isEmergency()) {
			pipePressure = Math.max(0, pipePressure - 5.0 * dt);
			brakeCylinderPressure = Math.min(1, brakeCylinderPressure + 5.0 * dt);
		} else {
			final int brake = Math.max(0, Math.min(control.getBrakeNotch(), type.getBrakeNotches()));
			if (brake == 0) {
				// RELEASE
				pipePressure = Math.min(1, pipePressure + type.getAirPipeChargeRatePerSecond() * dt);
				brakeCylinderPressure = Math.max(0, brakeCylinderPressure - type.getAirBrakeReleaseRatePerSecond() * dt);
			} else {
				// LAP/APPLY: bigger notch -> faster pipe drop and stronger cylinder
				final double ratio = (double) brake / type.getBrakeNotches();
				pipePressure = Math.max(0, pipePressure - type.getAirPipeDischargeRatePerSecond() * ratio * dt);
				brakeCylinderPressure = Math.min(1, brakeCylinderPressure + type.getAirBrakeApplyRatePerSecond() * ratio * dt);
			}
		}

		final double decel = type.getServiceBrakeDecelerationMps2() * brakeCylinderPressure;
		final boolean lamp = brakeCylinderPressure > 0.01;
		return new DriveOutput(-decel, lamp, control.isEmergency(), pipePressure, brakeCylinderPressure);
	}

	public double getPipePressure() { return pipePressure; }
	public double getBrakeCylinderPressure() { return brakeCylinderPressure; }

	/** Seeds the air-brake state (used when mirroring the server's controller on a client). */
	public void setState(double pipePressure, double brakeCylinderPressure) {
		this.pipePressure = Math.max(0, Math.min(1, pipePressure));
		this.brakeCylinderPressure = Math.max(0, Math.min(1, brakeCylinderPressure));
	}

	@Override
	public void reset() {
		pipePressure = 1.0;
		brakeCylinderPressure = 0.0;
	}
}
