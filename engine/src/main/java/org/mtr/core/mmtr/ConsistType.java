package org.mtr.core.mmtr;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

/**
 * ConsistType ("车底/编组类"): server-side configuration that describes how a train set is
 * driven and its longitudinal performance. Loaded from JSON (not persisted in the world).
 */
public final class ConsistType {

	public enum ControlMode { DEFAULT, NOTCHED, STEPLESS, AIR_BRAKE, THREE_HANDLE }

	private final String id;
	private final String name;
	private final ControlMode controlMode;
	private final int powerNotches;
	private final int brakeNotches;
	private final double maxSpeedKmh;
	private final double manualMaxSpeedKmh;
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
	/**
	 * Relative mass of the unit (1.0 = nominal). Used when several units are coupled into one
	 * {@link MmtrComposition}: tractive effort / brake force are spread over the total mass, and
	 * running resistance is weighted by each unit's mass. A single unit keeps mass 1.0 so the
	 * legacy/plain behaviour is unchanged.
	 */
	private final double massRatio;
	/**
	 * 三手柄机车的操纵规格（定速巡航 + 双向油门 + 气制动的位置表），只在
	 * {@link ControlMode#THREE_HANDLE} 下存在。其余模式为 {@code null}，行为一个字节不改。
	 */
	private final @Nullable ThreeHandleSpec handles;

	/** Backwards-compatible constructor (mass ratio = 1.0, no three-handle spec). */
	public ConsistType(String id, String name, ControlMode controlMode, int powerNotches, int brakeNotches,
		double maxSpeedKmh, double tractionAccelerationMps2, double serviceBrakeDecelerationMps2,
		double emergencyDecelerationMps2, double tractionBreakpointKmh, double resistanceA, double resistanceB, double resistanceC,
		double airPipeChargeRatePerSecond, double airPipeDischargeRatePerSecond,
		double airBrakeApplyRatePerSecond, double airBrakeReleaseRatePerSecond, double manualMaxSpeedKmh) {
		this(id, name, controlMode, powerNotches, brakeNotches, maxSpeedKmh, tractionAccelerationMps2, serviceBrakeDecelerationMps2,
			emergencyDecelerationMps2, tractionBreakpointKmh, resistanceA, resistanceB, resistanceC, airPipeChargeRatePerSecond,
			airPipeDischargeRatePerSecond, airBrakeApplyRatePerSecond, airBrakeReleaseRatePerSecond, manualMaxSpeedKmh, 1.0, null);
	}

	/** Backwards-compatible constructor (no three-handle spec). */
	public ConsistType(String id, String name, ControlMode controlMode, int powerNotches, int brakeNotches,
		double maxSpeedKmh, double tractionAccelerationMps2, double serviceBrakeDecelerationMps2,
		double emergencyDecelerationMps2, double tractionBreakpointKmh, double resistanceA, double resistanceB, double resistanceC,
		double airPipeChargeRatePerSecond, double airPipeDischargeRatePerSecond,
		double airBrakeApplyRatePerSecond, double airBrakeReleaseRatePerSecond, double manualMaxSpeedKmh, double massRatio) {
		this(id, name, controlMode, powerNotches, brakeNotches, maxSpeedKmh, tractionAccelerationMps2, serviceBrakeDecelerationMps2,
			emergencyDecelerationMps2, tractionBreakpointKmh, resistanceA, resistanceB, resistanceC, airPipeChargeRatePerSecond,
			airPipeDischargeRatePerSecond, airBrakeApplyRatePerSecond, airBrakeReleaseRatePerSecond, manualMaxSpeedKmh, massRatio, null);
	}

	public ConsistType(String id, String name, ControlMode controlMode, int powerNotches, int brakeNotches,
		double maxSpeedKmh, double tractionAccelerationMps2, double serviceBrakeDecelerationMps2,
		double emergencyDecelerationMps2, double tractionBreakpointKmh, double resistanceA, double resistanceB, double resistanceC,
		double airPipeChargeRatePerSecond, double airPipeDischargeRatePerSecond,
		double airBrakeApplyRatePerSecond, double airBrakeReleaseRatePerSecond, double manualMaxSpeedKmh, double massRatio,
		@Nullable ThreeHandleSpec handles) {
		this.id = id;
		this.name = name;
		this.controlMode = controlMode;
		this.powerNotches = Math.max(1, powerNotches);
		this.brakeNotches = Math.max(1, brakeNotches);
		this.maxSpeedKmh = Math.max(1, maxSpeedKmh);
		this.manualMaxSpeedKmh = manualMaxSpeedKmh > 0 ? manualMaxSpeedKmh : this.maxSpeedKmh;
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
		this.massRatio = massRatio > 0 ? massRatio : 1.0;
		this.handles = handles;
	}

	public String getId() { return id; }
	public String getName() { return name; }
	public ControlMode getControlMode() { return controlMode; }
	public int getPowerNotches() { return powerNotches; }
	public int getBrakeNotches() { return brakeNotches; }
	public double getMaxSpeedKmh() { return maxSpeedKmh; }
	public double getMaxSpeedMetersPerSecond() { return maxSpeedKmh / 3.6; }
	public double getManualMaxSpeedMetersPerSecond() { return manualMaxSpeedKmh / 3.6; }
	public double getTractionAccelerationMps2() { return tractionAccelerationMps2; }
	public double getServiceBrakeDecelerationMps2() { return serviceBrakeDecelerationMps2; }
	public double getEmergencyDecelerationMps2() { return emergencyDecelerationMps2; }
	public double getMassRatio() { return massRatio; }
	public double getTractionBreakpointKmh() { return tractionBreakpointKmh; }
	public double getResistanceA() { return resistanceA; }
	public double getResistanceB() { return resistanceB; }
	public double getResistanceC() { return resistanceC; }
	public double getAirPipeChargeRatePerSecond() { return airPipeChargeRatePerSecond; }
	public double getAirPipeDischargeRatePerSecond() { return airPipeDischargeRatePerSecond; }
	public double getAirBrakeApplyRatePerSecond() { return airBrakeApplyRatePerSecond; }
	public double getAirBrakeReleaseRatePerSecond() { return airBrakeReleaseRatePerSecond; }

	/** 三手柄操纵规格；非 {@link ControlMode#THREE_HANDLE} 模式为 {@code null}。 */
	public @Nullable ThreeHandleSpec getHandles() { return handles; }

	public static ConsistType fromJson(JsonObject json) {
		final String modeText = getString(json, "controlMode", "DEFAULT").toUpperCase();
		ControlMode mode;
		try {
			mode = ControlMode.valueOf(modeText);
		} catch (IllegalArgumentException e) {
			mode = ControlMode.DEFAULT;
		}
		final boolean threeHandle = mode == ControlMode.THREE_HANDLE;
		final double serviceBrakeDecelerationMps2 = getDouble(json, "serviceBrakeDecelerationMps2", 0.8);
		return new ConsistType(
			getString(json, "id", "unnamed"),
			getString(json, "name", ""),
			mode,
			// 三手柄车底的档位数就是手柄量程本身（±96 档 + 关闭；制动 11 个位置），缺省值跟着模式走。
			getInt(json, "powerNotches", threeHandle ? ThreeHandleSpec.DRIVE_HANDLE_MAX : 7),
			getInt(json, "brakeNotches", threeHandle ? ThreeHandleSpec.DEFAULT_BRAKE_POSITIONS.length : 8),
			getDouble(json, "maxSpeedKmh", 120),
			getDouble(json, "tractionAccelerationMps2", 0.5),
			serviceBrakeDecelerationMps2,
			getDouble(json, "emergencyDecelerationMps2", 1.5),
			getDouble(json, "tractionBreakpointKmh", maxSpeedDefaultKmh(json)),
			getDouble(json, "resistanceA", 0),
			getDouble(json, "resistanceB", 0),
			getDouble(json, "resistanceC", 0),
			getDouble(json, "airPipeChargeRatePerSecond", 0.1),
			getDouble(json, "airPipeDischargeRatePerSecond", 0.4),
			getDouble(json, "airBrakeApplyRatePerSecond", 0.15),
			getDouble(json, "airBrakeReleaseRatePerSecond", 0.1),
			getDouble(json, "manualMaxSpeedKmh", 0),
			getDouble(json, "massRatio", 1),
			// 电阻制动上限缺省跟随常用制动（不填也能跑）；只有三手柄模式才建规格。
			threeHandle ? ThreeHandleSpec.fromJson(json, serviceBrakeDecelerationMps2) : null
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