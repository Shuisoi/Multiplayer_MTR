package org.mtr.core.mmtr;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * ConsistType ("车底/编组类"): server-side configuration that describes how a train set is
 * driven and its longitudinal performance. Loaded from JSON (not persisted in the world).
 */
public final class ConsistType {

	public enum ControlMode { DEFAULT, NOTCHED, STEPLESS, AIR_BRAKE }

	private final String id;
	private final String name;
	private final ControlMode controlMode;
	private final int powerNotches;
	private final int brakeNotches;
	private final double maxSpeedKmh;
	private final double tractionAccelerationMps2; // full-notch standstill acceleration
	private final double serviceBrakeDecelerationMps2;
	private final double emergencyDecelerationMps2;
	private final double tractionBreakpointKmh;
	private final double resistanceA;
	private final double resistanceB;
	private final double resistanceC;
	private final double airPipeChargeRatePerSecond;   // 0..1 per second toward charged
	private final double airPipeDischargeRatePerSecond; // full-service drop rate 0..1/s
	private final double airBrakeApplyRatePerSecond;    // brake-cylinder build 0..1/s
	private final double airBrakeReleaseRatePerSecond;  // brake-cylinder release 0..1/s

	public ConsistType(String id, String name, ControlMode controlMode, int powerNotches, int brakeNotches,
		double maxSpeedKmh, double tractionAccelerationMps2, double serviceBrakeDecelerationMps2,
		double emergencyDecelerationMps2, double tractionBreakpointKmh, double resistanceA, double resistanceB, double resistanceC,
		double airPipeChargeRatePerSecond, double airPipeDischargeRatePerSecond,
		double airBrakeApplyRatePerSecond, double airBrakeReleaseRatePerSecond) {
		this.id = id;
		this.name = name;
		this.controlMode = controlMode;
		this.powerNotches = Math.max(1, powerNotches);
		this.brakeNotches = Math.max(1, brakeNotches);
		this.maxSpeedKmh = Math.max(1, maxSpeedKmh);
		this.tractionAccelerationMps2 = tractionAccelerationMps2;
		this.serviceBrakeDecelerationMps2 = serviceBrakeDecelerationMps2;
		this.emergencyDecelerationMps2 = emergencyDecelerationMps2;
		this.tractionBreakpointKmh = tractionBreakpointKmh;
		this.resistanceA = resistanceA;
		this.resistanceB = resistanceB;
		this.resistanceC = resistanceC;
		this.airPipeChargeRatePerSecond = airPipeChargeRatePerSecond;
		this.airPipeDischargeRatePerSecond = airPipeDischargeRatePerSecond;
		this.airBrakeApplyRatePerSecond = airBrakeApplyRatePerSecond;
		this.airBrakeReleaseRatePerSecond = airBrakeReleaseRatePerSecond;
	}

	public String getId() { return id; }
	public String getName() { return name; }
	public ControlMode getControlMode() { return controlMode; }
	public int getPowerNotches() { return powerNotches; }
	public int getBrakeNotches() { return brakeNotches; }
	public double getMaxSpeedKmh() { return maxSpeedKmh; }
	public double getMaxSpeedMetersPerSecond() { return maxSpeedKmh / 3.6; }
	public double getTractionAccelerationMps2() { return tractionAccelerationMps2; }
	public double getServiceBrakeDecelerationMps2() { return serviceBrakeDecelerationMps2; }
	public double getEmergencyDecelerationMps2() { return emergencyDecelerationMps2; }
	public double getTractionBreakpointKmh() { return tractionBreakpointKmh; }
	public double getResistanceA() { return resistanceA; }
	public double getResistanceB() { return resistanceB; }
	public double getResistanceC() { return resistanceC; }
	public double getAirPipeChargeRatePerSecond() { return airPipeChargeRatePerSecond; }
	public double getAirPipeDischargeRatePerSecond() { return airPipeDischargeRatePerSecond; }
	public double getAirBrakeApplyRatePerSecond() { return airBrakeApplyRatePerSecond; }
	public double getAirBrakeReleaseRatePerSecond() { return airBrakeReleaseRatePerSecond; }

	public static ConsistType fromJson(JsonObject json) {
		final String modeText = getString(json, "controlMode", "DEFAULT").toUpperCase();
		ControlMode mode;
		try {
			mode = ControlMode.valueOf(modeText);
		} catch (IllegalArgumentException e) {
			mode = ControlMode.DEFAULT;
		}
		return new ConsistType(
			getString(json, "id", "unnamed"),
			getString(json, "name", ""),
			mode,
			getInt(json, "powerNotches", 7),
			getInt(json, "brakeNotches", 8),
			getDouble(json, "maxSpeedKmh", 120),
			getDouble(json, "tractionAccelerationMps2", 0.5),
			getDouble(json, "serviceBrakeDecelerationMps2", 0.8),
			getDouble(json, "emergencyDecelerationMps2", 1.5),
			getDouble(json, "tractionBreakpointKmh", maxSpeedDefaultKmh(json)),
			getDouble(json, "resistanceA", 0),
			getDouble(json, "resistanceB", 0),
			getDouble(json, "resistanceC", 0),
			getDouble(json, "airPipeChargeRatePerSecond", 0.1),
			getDouble(json, "airPipeDischargeRatePerSecond", 0.4),
			getDouble(json, "airBrakeApplyRatePerSecond", 0.15),
			getDouble(json, "airBrakeReleaseRatePerSecond", 0.1)
		);
	}

	private static double maxSpeedDefaultKmh(JsonObject json) { return getDouble(json, "maxSpeedKmh", 120); }

	private static String getString(JsonObject json, String key, String fallback) {
		final JsonElement element = json.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsString();
	}

	private static int getInt(JsonObject json, String key, int fallback) {
		final JsonElement element = json.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsInt();
	}

	private static double getDouble(JsonObject json, String key, double fallback) {
		final JsonElement element = json.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
	}
}