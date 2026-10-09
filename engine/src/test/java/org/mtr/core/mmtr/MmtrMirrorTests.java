package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.physics.TrainPhysics;

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
		     "maxSpeedKmh":120,"manualMaxSpeedKmh":110,"massKg":60000,"rotatingMassFactor":1.06,
		     "maxTractiveEffortN":36000,"maxPowerW":1200000,"serviceBrakeForceN":54000,"emergencyBrakeForceN":90000,
		     "resistanceAN":1200,"resistanceBN":6,"resistanceCN":0.12,
		     "airPipeChargeRatePerSecond":0.1,"airPipeDischargeRatePerSecond":0.4,
		     "airBrakeApplyRatePerSecond":0.15,"airBrakeReleaseRatePerSecond":0.1},
		    {"id":"freight","name":"Freight","controlMode":"AIR_BRAKE","powerNotches":8,"brakeNotches":3,
		     "maxSpeedKmh":100,"massKg":180000,"rotatingMassFactor":1.0,
		     "maxTractiveEffortN":18000,"maxPowerW":400000,"serviceBrakeForceN":126000,"emergencyBrakeForceN":216000,
		     "airPipeChargeRatePerSecond":0.12,"airPipeDischargeRatePerSecond":0.5,
		     "airBrakeApplyRatePerSecond":0.2,"airBrakeReleaseRatePerSecond":0.08}
		  ]
		}
		""";

	/** Mirrors every ConsistType field the snapshot carries（力模型口径，notes/235）。 */
	private static ConsistType mirror(ConsistType source) {
		final TrainPhysics physics = source.getPhysics();
		return new ConsistType(
			"mirror", "", source.getControlMode(),
			source.getPowerNotches(), source.getBrakeNotches(),
			source.getMaxSpeedKmh(),
			physics.getMassKg(), physics.getRotatingMassFactor(),
			physics.getTraction().getMaxTractiveEffortN(), physics.getTraction().getMaxPowerW(),
			physics.getBrake().getServiceForceN(), physics.getBrake().getEmergencyForceN(),
			physics.getResistance().getAN(), physics.getResistance().getBN(), physics.getResistance().getCN(),
			source.getAdhesion().usableMuMax(), source.getAdhesion().isSanding(),
			source.getManualMaxSpeedMetersPerSecond() * 3.6,
			source.getHandles(),
			// notes/376：气压口径是必填项，镜像也必须带上它（客户端 HUD 的 bar 刻度靠它）。
			source.getBrakes(), source.getElectricBrake(), source.getPayloadKg(), source.getCoupler(), source.hasMmtrLightOffPosition()
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
			assertEquals(source.getMassKg(), copy.getMassKg(), 1e-9, id);
			assertEquals(source.getRotatingMassFactor(), copy.getRotatingMassFactor(), 1e-9, id);
			assertEquals(source.getTraction().getMaxTractiveEffortN(), copy.getTraction().getMaxTractiveEffortN(), 1e-9, id);
			assertEquals(source.getTraction().getMaxPowerW(), copy.getTraction().getMaxPowerW(), 1e-9, id);
			assertEquals(source.getBrake().getServiceForceN(), copy.getBrake().getServiceForceN(), 1e-9, id);
			assertEquals(source.getBrake().getEmergencyForceN(), copy.getBrake().getEmergencyForceN(), 1e-9, id);
			assertEquals(source.getResistance().getAN(), copy.getResistance().getAN(), 1e-9, id);
			assertEquals(source.getResistance().getBN(), copy.getResistance().getBN(), 1e-9, id);
			assertEquals(source.getResistance().getCN(), copy.getResistance().getCN(), 1e-9, id);
			assertEquals(source.getManualMaxSpeedMetersPerSecond(), copy.getManualMaxSpeedMetersPerSecond(), 1e-9, id);
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
		final NotchedDriveController server = new NotchedDriveController();
		final ControlState brake = ControlState.zero().setBrakeNotch(2);
		for (int i = 0; i < 120; i++) {
			server.compute(brake, freight, speed, 100);
		}
		final double serverPipe = server.getPipePressure();
		final double serverCylinder = server.getBrakeCylinderPressure();

		/*
		 * ...a freshly rebuilt mirror controller seeded from the snapshot state continues identically.
		 *
		 * notes/376：种子走**气压状态串**（{@code BrakeModel.applyState}，与生产端的 {@code mmtrAirState}
		 * 同一条通道、同一套 bar 语义）。归一化读数（{@code setState}）只是镜像/HUD 那一层，
		 * 在"系统还没建"的那一拍灌不进逐车 bar 状态 —— 所以这里用逐车状态串，并且额外钉住 bar 读数逐位相同。
		 */
		final NotchedDriveController mirror = new NotchedDriveController();
		mirror.getBrakeModel().applyState(server.getBrakeModel().encodeState());
		for (int i = 0; i < 60; i++) {
			final DriveOutput a = server.compute(brake, freight, speed, 100);
			final DriveOutput b = mirror.compute(brake, freight, speed, 100);
			assertEquals(a.getAccelerationMetersPerSecondSquared(), b.getAccelerationMetersPerSecondSquared(), 1e-12, "mirror must follow the server controller");
		}
		assertEquals(server.getPipePressure(), mirror.getPipePressure(), 1e-12);
		assertEquals(serverCylinder, mirror.getBrakeCylinderPressure(), 1e-12);
		assertEquals(server.getBrakeModel().getPipeBar(), mirror.getBrakeModel().getPipeBar(), 1e-12, "bar 读数也要逐位相同");
		assertEquals(server.getBrakeModel().getCylinderBar(), mirror.getBrakeModel().getCylinderBar(), 1e-12, "缸压同理");
	}
}