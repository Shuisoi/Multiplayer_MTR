package org.mtr.core.mmtr;

/**
 * Result of one DriveController step: the longitudinal acceleration the consist should
 * follow (positive = accelerate along the running direction) plus status lamps/pressures.
 */
public final class DriveOutput {

	private final double accelerationMetersPerSecondSquared;
	private final boolean brakeLamp;
	private final boolean emergencyBrake;
	private final double trainPipePressure; // 0..1 (air brake modes)
	private final double brakeCylinderPressure; // 0..1

	public DriveOutput(double accelerationMetersPerSecondSquared, boolean brakeLamp, boolean emergencyBrake,
		double trainPipePressure, double brakeCylinderPressure) {
		this.accelerationMetersPerSecondSquared = accelerationMetersPerSecondSquared;
		this.brakeLamp = brakeLamp;
		this.emergencyBrake = emergencyBrake;
		this.trainPipePressure = trainPipePressure;
		this.brakeCylinderPressure = brakeCylinderPressure;
	}

	public static DriveOutput coast() { return new DriveOutput(0, false, false, 1, 0); }

	public double getAccelerationMetersPerSecondSquared() { return accelerationMetersPerSecondSquared; }
	public boolean isBrakeLamp() { return brakeLamp; }
	public boolean isEmergencyBrake() { return emergencyBrake; }
	public double getTrainPipePressure() { return trainPipePressure; }
	public double getBrakeCylinderPressure() { return brakeCylinderPressure; }

	@Override
	public String toString() {
		return "DriveOutput{accel=" + accelerationMetersPerSecondSquared + ", brakeLamp=" + brakeLamp
			+ ", emergency=" + emergencyBrake + ", pipe=" + trainPipePressure + ", bc=" + brakeCylinderPressure + "}";
	}
}
