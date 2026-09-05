package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client-mirror equivalence: rebuilding a ConsistType from the mirrored (snapshot) numbers must
 * reproduce the exact same parameters and therefore the exact same physics integration as the
 * server's ConsistType. Air-brake state seeding must hand over the controller state exactly.
 */
public final class MmtrMirrorTests {

	private static final String JSON = """
		{
		  "consistTypes": [
		    {"id":"emu","name":"EMU","controlMode":"NOTCHED","powerNotches":7,"brakeNotches":8,
		     "maxSpeedKmh":120,"manualMaxSpeedKmh":110,"tractionAccelerationMps2":0.6,"serviceBrakeDecelerationMps2":0.9,"emergencyDecelerationMps2":1.5,
		     "tractionBreakpointKmh":40,"resistanceA":0.02,"resistanceB":0.0001,"resistanceC":0.000002,
		     "airPipeChargeRatePerSecond":0.1,"airPipeDischargeRatePerSecond":0.4,
		     "airBrakeApplyRatePerSecond":0.15,"airBrakeReleaseRatePerSecond":0.1,"massRatio":2},
		    {"id":"freight","name":"Freight","controlMode":"AIR_BRAKE","powerNotches":8,"brakeNotches":3,
		     "maxSpeedKmh":100,"tractionAccelerationMps2":0.3,"serviceBrakeDecelerationMps2":0.7,"emergencyDecelerationMps2":1.2,
		     "airPipeChargeRatePerSecond":0.12,"airPipeDischargeRatePerSecond":0.5,
		     "airBrakeApplyRatePerSecond":0.2,"airBrakeReleaseRatePerSecond":0.08,"massRatio":3}
		  ]
		}
		""";

	/** Mirrors every ConsistType field the snapshot carries. */
	private static ConsistType mirror(ConsistType source) {
		return new ConsistType(
			"mirror", "", source.getControlMode(),
			source.getPowerNotches(), source.getBrakeNotches(),
			source.getMaxSpeedKmh(), source.getTractionAccelerationMps2(), source.getServiceBrakeDecelerationMps2(),
			source.getEmergencyDecelerationMps2(), source.getTractionBreakpointKmh(), source.getResistanceA(),
			source.getResistanceB(), source.getResistanceC(), source.getAirPipeChargeRatePerSecond(),
			source.getAirPipeDischargeRatePerSecond(), source.getAirBrakeApplyRatePerSecond(),
			source.getAirBrakeReleaseRatePerSecond(), source.getManualMaxSpeedMetersPerSecond() * 3.6,
			source.getMassRatio()
		);
	}

	@Test
	public void mirroredParametersEqualServerParameters() {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(JSON);
		for (final String id : new String[]{"emu", "freight"}) {
			final ConsistType source = registry.get(id);
			final ConsistType copy = mirror(source);
			assertEquals(source.getControlMode(), copy.getControlMode(), id);
			assertEquals(source.getPowerNotches(), copy.getPowerNotches(), id);
			assertEquals(source.getBrakeNotches(), copy.getBrakeNotches(), id);
			assertEquals(source.getMaxSpeedKmh(), copy.getMaxSpeedKmh(), 1e-9, id);
			assertEquals(source.getTractionAccelerationMps2(), copy.getTractionAccelerationMps2(), 1e-9, id);
			assertEquals(source.getServiceBrakeDecelerationMps2(), copy.getServiceBrakeDecelerationMps2(), 1e-9, id);
			assertEquals(source.getEmergencyDecelerationMps2(), copy.getEmergencyDecelerationMps2(), 1e-9, id);
			assertEquals(source.getTractionBreakpointKmh(), copy.getTractionBreakpointKmh(), 1e-9, id);
			assertEquals(source.getResistanceA(), copy.getResistanceA(), 1e-12, id);
			assertEquals(source.getResistanceB(), copy.getResistanceB(), 1e-12, id);
			assertEquals(source.getResistanceC(), copy.getResistanceC(), 1e-12, id);
			assertEquals(source.getManualMaxSpeedMetersPerSecond(), copy.getManualMaxSpeedMetersPerSecond(), 1e-9, id);
			assertEquals(source.getMassRatio(), copy.getMassRatio(), 1e-12, id);
		}
	}

	@Test
	public void identicalPhysicsForServerAndMirrorConsist() {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(JSON);
		final ConsistType source = registry.get("emu");
		final ConsistType copy = mirror(source);
		final NotchedDriveController serverController = new NotchedDriveController();
		final NotchedDriveController mirrorController = new NotchedDriveController();
		final ControlState control = ControlState.zero().setThrottleNotch(5);
		double serverSpeed = 0;
		double mirrorSpeed = 0;
		for (int i = 0; i < 300; i++) {
			serverSpeed = ConsistDynamics.step(serverSpeed, serverController.compute(control, source, serverSpeed, 100), source, 100);
			mirrorSpeed = ConsistDynamics.step(mirrorSpeed, mirrorController.compute(control, copy, mirrorSpeed, 100), copy, 100);
		}
		assertEquals(serverSpeed, mirrorSpeed, 1e-12, "mirror must integrate identically");
		assertTrue(serverSpeed > 5, "expected the consist to have accelerated");
	}

	@Test
	public void airBrakeStateSeedingHandsOffExactly() {
		final ConsistType freight = ConsistTypeRegistry.parse(JSON).get("freight");
		final ControlState power = ControlState.zero().setThrottleNotch(4);
		final NotchedDriveController powerController = new NotchedDriveController();
		double speed = 0;
		for (int i = 0; i < 200; i++) {
			speed = ConsistDynamics.step(speed, powerController.compute(power, freight, speed, 100), freight, 100);
		}
		// Server controller accumulates air state while braking...
		final AirBrakeController server = new AirBrakeController();
		final ControlState brake = ControlState.zero().setBrakeNotch(2);
		for (int i = 0; i < 120; i++) {
			server.compute(brake, freight, speed, 100);
		}
		final double serverPipe = server.getPipePressure();
		final double serverCylinder = server.getBrakeCylinderPressure();

		// ...a freshly rebuilt mirror controller seeded from the snapshot state continues identically.
		final AirBrakeController mirror = new AirBrakeController();
		mirror.setState(serverPipe, serverCylinder);
		for (int i = 0; i < 60; i++) {
			final DriveOutput a = server.compute(brake, freight, speed, 100);
			final DriveOutput b = mirror.compute(brake, freight, speed, 100);
			assertEquals(a.getAccelerationMetersPerSecondSquared(), b.getAccelerationMetersPerSecondSquared(), 1e-12, "mirror must follow the server controller");
		}
		assertEquals(server.getPipePressure(), mirror.getPipePressure(), 1e-12);
		assertEquals(server.getBrakeCylinderPressure(), mirror.getBrakeCylinderPressure(), 1e-12);
	}
}