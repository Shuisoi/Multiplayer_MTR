package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Coupling/uncoupling groundwork: composition list ops + mass-weighted aggregation. */
public final class MmtrCompositionTests {

	private static ConsistType type(String id, double maxKmh, double traction, double service, double mass) {
		return new ConsistType(id, "", ConsistType.ControlMode.NOTCHED, 7, 8, maxKmh, traction, service, 1.5, maxKmh, 0, 0, 0, 0.1, 0.4, 0.15, 0.1, 0, mass);
	}

	private static ConsistType wagon(String id, double mass) {
		return new ConsistType(id, "", ConsistType.ControlMode.NOTCHED, 7, 8, 100, 0, 0.5, 1.2, 100, 0.05, 0, 0, 0.1, 0.4, 0.15, 0.1, 0, mass);
	}

	@Test
	public void coupleUncoupleAndSplit() {
		final MmtrComposition train = new MmtrComposition(new MmtrComposition.Unit("loco", type("loco", 100, 0.3, 0.7, 2)));
		train.couple(new MmtrComposition.Unit("w1", wagon("w1", 1)));
		train.couple(new MmtrComposition.Unit("w2", wagon("w2", 1), false));
		assertEquals(3, train.size());
		assertEquals(4.0, train.massTotal(), 1e-9);
		assertFalse(train.unit(2).isPowered(), "w2 is a dead trailer");

		// Split after the loco -> loco alone + a 2-wagon tail.
		final MmtrComposition tail = train.splitAfter(0);
		assertEquals(1, train.size());
		assertEquals(2, tail.size());
		assertEquals("w1", tail.unit(0).getId());
		assertEquals("w2", tail.unit(1).getId());
		assertEquals(2.0, train.massTotal(), 1e-9);
		assertEquals(2.0, tail.massTotal(), 1e-9);

		// Recouple the tail back.
		train.couple(tail);
		assertEquals(3, train.size());
		assertEquals(4.0, train.massTotal(), 1e-9);

		assertEquals("w2", train.uncoupleLast().getId());
		assertEquals(2, train.size());
		assertThrows(IllegalStateException.class, () -> new MmtrComposition().uncoupleLast(), "empty composition cannot uncouple");
		assertThrows(IndexOutOfBoundsException.class, () -> train.splitAfter(5), "bad split index must throw");
	}

	@Test
	public void deadTrailingMassReducesTraction() {
		// Locomotive: mass 2, traction 0.3 -> pulling a mass-1 wagon halves the accel to 0.2.
		final MmtrComposition train = new MmtrComposition(new MmtrComposition.Unit("loco", type("loco", 100, 0.3, 0.7, 2)));
		train.couple(new MmtrComposition.Unit("w1", wagon("w1", 1), false));
		final DriveOutput out = train.aggregate(ControlState.zero().setThrottleNotch(7), 0);
		assertEquals(0.2, out.getAccelerationMetersPerSecondSquared(), 1e-9, "2/3 of the standalone accel over 3 total mass");
	}

	@Test
	public void twoPoweredUnitsKeepFullTraction() {
		// Two identical powered EMUs coupled: each pulls the whole, mass-weighted accel stays a0.
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
		assertTrue(coast.getAccelerationMetersPerSecondSquared() > -0.5, "light resistance at this speed");
	}

	@Test
	public void resistanceWeightedByMass() {
		final ConsistType wagonHeavy = wagon("w", 3);
		final MmtrComposition train = new MmtrComposition(new MmtrComposition.Unit("w", wagonHeavy));
		// 0.05 (A term) at any speed -> weighted resistance still 0.05 for a single unit.
		assertEquals(0.05, train.resistance(0), 1e-9);
	}
}