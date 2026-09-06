package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Coupling/uncoupling groundwork: composition list ops + mass-weighted aggregation + per-unit air. */
public final class MmtrCompositionTests {

	private static ConsistType type(String id, double maxKmh, double traction, double service, double mass) {
		return new ConsistType(id, "", ConsistType.ControlMode.NOTCHED, 7, 8, maxKmh, traction, service, 1.5, maxKmh, 0, 0, 0, 0.1, 0.4, 0.15, 0.1, 0, mass);
	}

	private static ConsistType wagon(String id, double mass) {
		return new ConsistType(id, "", ConsistType.ControlMode.NOTCHED, 7, 8, 100, 0, 0.5, 1.2, 100, 0.05, 0, 0, 0.1, 0.4, 0.15, 0.1, 0, mass);
	}

	private static MmtrComposition freightTrain(int wagons) {
		final ConsistType loco = type("loco", 100, 0.3, 0.7, 2);
		final MmtrComposition train = new MmtrComposition(new MmtrComposition.Unit("loco", loco));
		for (int i = 0; i < wagons; i++) {
			train.couple(new MmtrComposition.Unit("w" + (i + 1), wagon("w" + (i + 1), 1), false));
		}
		return train;
	}

	@Test
	public void coupleUncoupleAndSplit() {
		final MmtrComposition train = new MmtrComposition(new MmtrComposition.Unit("loco", type("loco", 100, 0.3, 0.7, 2)));
		train.couple(new MmtrComposition.Unit("w1", wagon("w1", 1)));
		train.couple(new MmtrComposition.Unit("w2", wagon("w2", 1), false));
		assertEquals(3, train.size());
		assertEquals(4.0, train.massTotal(), 1e-9);
		assertFalse(train.unit(2).isPowered(), "w2 is a dead trailer");

		final MmtrComposition tail = train.splitAfter(0);
		assertEquals(1, train.size());
		assertEquals(2, tail.size());
		assertEquals("w1", tail.unit(0).getId());
		assertEquals("w2", tail.unit(1).getId());

		train.couple(tail);
		assertEquals(3, train.size());
		assertEquals("w2", train.uncoupleLast().getId());
		assertEquals(2, train.size());
		assertThrows(IllegalStateException.class, () -> new MmtrComposition().uncoupleLast(), "empty composition cannot uncouple");
		assertThrows(IndexOutOfBoundsException.class, () -> train.splitAfter(5), "bad split index must throw");
	}

	@Test
	public void deadTrailingMassReducesTraction() {
		final MmtrComposition train = new MmtrComposition(new MmtrComposition.Unit("loco", type("loco", 100, 0.3, 0.7, 2)));
		train.couple(new MmtrComposition.Unit("w1", wagon("w1", 1), false));
		final DriveOutput out = train.aggregate(ControlState.zero().setThrottleNotch(7), 0);
		assertEquals(0.2, out.getAccelerationMetersPerSecondSquared(), 1e-9, "2/3 of the standalone accel over 3 total mass");
	}

	@Test
	public void twoPoweredUnitsKeepFullTraction() {
		final MmtrComposition train = new MmtrComposition(new MmtrComposition.Unit("e1", type("e1", 100, 0.5, 0.9, 1)));
		train.couple(new MmtrComposition.Unit("e2", type("e2", 100, 0.5, 0.9, 1)));
		final DriveOutput out = train.aggregate(ControlState.zero().setThrottleNotch(7), 0);
		assertEquals(0.5, out.getAccelerationMetersPerSecondSquared(), 1e-9);
	}

	@Test
	public void brakingAndCoastAreMassWeightedAndNeverPositive() {
		final MmtrComposition train = new MmtrComposition(new MmtrComposition.Unit("loco", type("loco", 100, 0.3, 0.7, 2)));
		train.couple(new MmtrComposition.Unit("w1", wagon("w1", 1), false));
		final DriveOutput brake = train.aggregate(ControlState.zero().setBrakeNotch(8), 10);
		assertTrue(brake.getAccelerationMetersPerSecondSquared() < -0.5, "braking must decelerate");
		assertTrue(brake.isBrakeLamp());
		final DriveOutput emergency = train.aggregate(ControlState.zero().setEmergency(true), 10);
		assertTrue(emergency.getAccelerationMetersPerSecondSquared() < brake.getAccelerationMetersPerSecondSquared(), "emergency brakes harder than service");
		final DriveOutput coast = train.aggregate(ControlState.zero(), 20);
		assertTrue(coast.getAccelerationMetersPerSecondSquared() < 0, "coast decays with resistance");
	}

	@Test
	public void resistanceWeightedByMass() {
		final MmtrComposition train = new MmtrComposition(new MmtrComposition.Unit("w", wagon("w", 3)));
		assertEquals(0.05, train.resistance(0), 1e-9);
	}

	@Test
	public void emptyPipeChargesFromFrontAfterCoupling() {
		final MmtrComposition train = freightTrain(1);
		train.unit(1).setAirState(0, 0);
		assertEquals(0.0, train.unit(1).getPipePressure(), 1e-9, "newly coupled wagon starts with empty pipe");
		for (int i = 0; i < 300; i++) {
			train.stepAir(ControlState.zero(), 0, 50);
		}
		assertTrue(train.unit(0).getPipePressure() > 0.99, "loco pipe stays charged");
		assertTrue(train.unit(1).getPipePressure() > 0.9, "empty wagon pipe must charge through the train pipe");
		assertTrue(train.unit(1).getBrakeCylinderPressure() < 0.01, "cylinder must stay released while pipe is charged");
	}

	@Test
	public void serviceBrakePropagatesFrontToRear() {
		final MmtrComposition train = freightTrain(3);
		for (int i = 0; i < 12; i++) {
			train.stepAir(ControlState.zero().setBrakeNotch(8), 10, 50);
		}
		final double frontPipe = train.unit(0).getPipePressure();
		final double rearPipe = train.unit(3).getPipePressure();
		assertTrue(frontPipe < 0.995, "front pipe should have dropped");
		assertTrue(frontPipe < rearPipe, "front pipe must drop ahead of the rear (gradient), front=" + frontPipe + " rear=" + rearPipe);
		assertTrue(train.unit(0).getBrakeCylinderPressure() >= train.unit(3).getBrakeCylinderPressure() - 1e-9, "front cylinder responds first");
	}

	@Test
	public void repeatedAirBrakingIsMonotonicAndStable() {
		final MmtrComposition train = freightTrain(4);
		final ControlState apply = ControlState.zero().setBrakeNotch(8);
		double speed = 6;
		for (int i = 0; i < 600; i++) {
			final DriveOutput out = train.stepAir(apply, speed, 50);
			assertTrue(Double.isFinite(out.getAccelerationMetersPerSecondSquared()), "output must be finite");
			speed = Math.max(0, speed + out.getAccelerationMetersPerSecondSquared() * 0.05);
			assertTrue(speed <= 6.0 + 1e-9, "speed must never exceed the starting speed while braking");
			for (int u = 0; u < train.size(); u++) {
				assertTrue(train.unit(u).getPipePressure() >= 0 && train.unit(u).getPipePressure() <= 1, "pipe bounded");
				assertTrue(train.unit(u).getBrakeCylinderPressure() >= 0 && train.unit(u).getBrakeCylinderPressure() <= 1, "cylinder bounded");
			}
		}
		assertTrue(speed < 0.5, "30 s of full service air brake must stop the freight, got " + speed);
	}

	@Test
	public void airModelIsDeterministic() {
		final ControlState apply = ControlState.zero().setBrakeNotch(8);
		final MmtrComposition a = freightTrain(2);
		final MmtrComposition b = freightTrain(2);
		double speedA = 15;
		double speedB = 15;
		for (int i = 0; i < 100; i++) {
			speedA += a.stepAir(apply, speedA, 50).getAccelerationMetersPerSecondSquared() * 0.05;
			speedB += b.stepAir(apply, speedB, 50).getAccelerationMetersPerSecondSquared() * 0.05;
		}
		assertEquals(speedA, speedB, 0, "identical runs must be identical");
		for (int u = 0; u < a.size(); u++) {
			assertEquals(a.unit(u).getPipePressure(), b.unit(u).getPipePressure(), 0);
			assertEquals(a.unit(u).getBrakeCylinderPressure(), b.unit(u).getBrakeCylinderPressure(), 0);
		}
	}

	@Test
	public void emergencyStopsFasterThanServiceBrake() {
		final MmtrComposition trainA = freightTrain(3);
		final MmtrComposition trainB = freightTrain(3);
		double serviceSpeed = 25;
		double emergencySpeed = 25;
		for (int i = 0; i < 300; i++) {
			serviceSpeed += trainA.stepAir(ControlState.zero().setBrakeNotch(8), serviceSpeed, 50).getAccelerationMetersPerSecondSquared() * 0.05;
			emergencySpeed += trainB.stepAir(ControlState.zero().setEmergency(true), emergencySpeed, 50).getAccelerationMetersPerSecondSquared() * 0.05;
		}
		assertTrue(emergencySpeed < serviceSpeed, "emergency must stop faster than full service (em=" + emergencySpeed + " svc=" + serviceSpeed + ")");
	}

	@Test
	public void uncoupleKeepsIndependentAirState() {
		final MmtrComposition train = freightTrain(2);
		for (int i = 0; i < 300; i++) {
			train.stepAir(ControlState.zero().setBrakeNotch(8), 10, 50);
		}
		final double headPipe = train.unit(0).getPipePressure();
		final double tailPipe = train.unit(1).getPipePressure();
		final double tailCylinder = train.unit(1).getBrakeCylinderPressure();
		final MmtrComposition tail = train.splitAfter(0);
		train.resetAirState();
		assertEquals(1.0, train.unit(0).getPipePressure(), 1e-12);
		assertEquals(tailPipe, tail.unit(0).getPipePressure(), 1e-12, "the uncoupled tail must keep its own pipe state unchanged");
		assertTrue(tailCylinder > 0.01, "tail should have an applied cylinder before uncoupling");
		assertTrue(tail.unit(0).getBrakeCylinderPressure() >= tailCylinder - 1e-9, "uncoupled unit keeps its cylinder");
		assertTrue(headPipe < 1.0 - 1e-9, "head had dropped its pipe before the split");
	}

	@Test
	public void airStateEncodeDecodeRoundTrip() {
		final MmtrComposition source = freightTrain(2);
		source.unit(0).setAirState(0.4, 0.6);
		source.unit(1).setAirState(0.9, 0.1);
		source.unit(2).setAirState(0.2, 0.8);
		final String encoded = MmtrComposition.encodeAirStates(source);

		final MmtrComposition copy = freightTrain(2);
		copy.applyAirStateString(encoded);
		assertEquals(source.unit(0).getPipePressure(), copy.unit(0).getPipePressure(), 1e-12);
		assertEquals(source.unit(0).getBrakeCylinderPressure(), copy.unit(0).getBrakeCylinderPressure(), 1e-12);
		assertEquals(source.unit(1).getPipePressure(), copy.unit(1).getPipePressure(), 1e-12);
		assertEquals(source.unit(2).getBrakeCylinderPressure(), copy.unit(2).getBrakeCylinderPressure(), 1e-12);

		copy.applyAirStateString("not-a-number;x");
		assertEquals(0.9, copy.unit(1).getPipePressure(), 1e-12);
	}
}