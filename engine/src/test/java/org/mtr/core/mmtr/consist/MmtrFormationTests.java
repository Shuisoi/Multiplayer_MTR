package org.mtr.core.mmtr.consist;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C1: the coupling unit / coupling endpoint / formation data model.
 *
 * <p>These tests pin the four structural properties the coupling design rests on: a unit is
 * indivisible (U1/U6 — an 8-car EMU has no seam to cut at), every wagon boundary is a coupler
 * (the "logically independent, physically merged" wagon model), the connection state is derived
 * from the chain so a coupler can never have two partners (U3), and coupling/uncoupling is
 * length-exact (U2: the seam gains {@code head.padding2 + tail.padding1}).</p>
 */
public final class MmtrFormationTests {

	private static final double EPSILON = 1e-9;

	private static ObjectArrayList<MmtrUnitCar> list(MmtrUnitCar... cars) {
		final ObjectArrayList<MmtrUnitCar> out = new ObjectArrayList<>();
		for (final MmtrUnitCar car : cars) {
			out.add(car);
		}
		return out;
	}

	/** An 8-car high-speed set: one indivisible unit, 20 m cars with 0.5 m coupler gaps. */
	private static MmtrUnit emu8(String unitId) {
		final ObjectArrayList<MmtrUnitCar> cars = new ObjectArrayList<>();
		for (int i = 0; i < 8; i++) {
			cars.add(new MmtrUnitCar(unitId + "_car_" + i, 20, 0.5, 0.5, true, "emu_8_notched"));
		}
		return new MmtrUnit(unitId, MmtrUnit.UnitClass.EMU, cars);
	}

	/** A single unpowered wagon, 12 m, no coupler gap. */
	private static MmtrUnit wagon(String unitId) {
		return new MmtrUnit(unitId, MmtrUnit.UnitClass.WAGON, list(MmtrUnitCar.of(unitId + "_car", 12)));
	}

	private static MmtrFormation wagonRake() {
		return MmtrFormation.of(wagon("w1"), wagon("w2"), wagon("w3"), wagon("w4"), wagon("w5"), wagon("w6"), wagon("w7"), wagon("w8"));
	}

	@Test
	public void emuIsOneIndivisibleUnit() {
		final MmtrFormation formation = MmtrFormation.of(emu8("emu_1"));
		assertEquals(1, formation.unitCount());
		assertEquals(8, formation.carCount());
		assertEquals(0, formation.seamCount(), "an EMU has couplers only at its two ends");
		assertEquals(167, formation.lengthM(), EPSILON);
		assertThrows(IllegalArgumentException.class, () -> formation.splitAfterSeam(0), "there is no seam inside an EMU");
	}

	@Test
	public void wagonRakeIsEightSingleCarUnits() {
		final MmtrFormation formation = wagonRake();
		assertEquals(8, formation.unitCount(), "logically independent: eight manifest entries");
		assertEquals(8, formation.carCount());
		assertEquals(7, formation.seamCount(), "physically merged: every wagon boundary is a coupler");
		assertEquals(96, formation.lengthM(), EPSILON);
	}

	@Test
	public void everyWagonBoundaryCanBeCutAndRejoined() {
		final MmtrFormation formation = wagonRake();
		for (int seam = 0; seam < formation.seamCount(); seam++) {
			final ObjectObjectImmutablePair<MmtrFormation, MmtrFormation> split = formation.splitAfterSeam(seam);
			assertEquals(seam + 1, split.left().carCount(), "head keeps the cars in front of seam " + seam);
			assertEquals(8 - seam - 1, split.right().carCount(), "tail keeps the cars behind seam " + seam);
			final MmtrFormation rejoined = split.left().couple(split.right());
			assertEquals(formation.carCount(), rejoined.carCount());
			assertEquals(formation.lengthM(), rejoined.lengthM(), EPSILON);
			assertEquals(formation.cars(), rejoined.cars(), "cut then rejoined restores the same car order");
		}
	}

	@Test
	public void couplingAddsTheSeamPadding() {
		final MmtrUnit head = new MmtrUnit("head", MmtrUnit.UnitClass.LOCO, list(
			new MmtrUnitCar("h1", 10, 0.5, 0.7, true, "freight_air"),
			new MmtrUnitCar("h2", 10, 0.5, 0.7, true, "freight_air")));
		final MmtrUnit tail = new MmtrUnit("tail", MmtrUnit.UnitClass.WAGON, list(
			new MmtrUnitCar("t1", 12, 0.4, 0.6, false, null)));
		final MmtrFormation headFormation = MmtrFormation.of(head);
		final MmtrFormation tailFormation = MmtrFormation.of(tail);

		assertEquals(21.2, headFormation.lengthM(), EPSILON, "outward paddings of the standalone formation are not counted");
		assertEquals(12.0, tailFormation.lengthM(), EPSILON);
		assertEquals(1.1, MmtrFormation.couplingPaddingM(headFormation, tailFormation), EPSILON);

		final MmtrFormation merged = headFormation.couple(tailFormation);
		assertEquals(3, merged.carCount());
		assertEquals(1, merged.seamCount());
		assertEquals(34.3, merged.lengthM(), EPSILON);
		assertEquals(
			headFormation.lengthM() + tailFormation.lengthM() + MmtrFormation.couplingPaddingM(headFormation, tailFormation),
			merged.lengthM(),
			EPSILON,
			"U2: coupling length is exactly the sum plus the seam padding");
	}

	@Test
	public void doubleUnitEmuJoinsWithOneSeam() {
		final MmtrFormation merged = MmtrFormation.of(emu8("emu_1")).couple(MmtrFormation.of(emu8("emu_2")));
		assertEquals(2, merged.unitCount());
		assertEquals(16, merged.carCount(), "8+8 reversible working");
		assertEquals(1, merged.seamCount());
		assertEquals(335, merged.lengthM(), EPSILON);
		assertTrue(merged.isCouplerFree(merged.firstUnit().couplerA()));
		assertTrue(merged.isCouplerFree(merged.lastUnit().couplerB()));
		assertFalse(merged.isCouplerFree(merged.seamHeadCoupler(0)), "the inner couplers are connected");
		assertFalse(merged.isCouplerFree(merged.seamTailCoupler(0)));
	}

	@Test
	public void seamArcCountsFromTheAEnd() {
		final MmtrFormation formation = wagonRake();
		for (int seam = 0; seam < formation.seamCount(); seam++) {
			assertEquals(seam + 1, formation.carIndexAfterSeam(seam));
			assertEquals(12 * (seam + 1), formation.seamArcM(seam), EPSILON);
		}
	}

	@Test
	public void couplerIsFreeOnlyAtTheFormationEnds() {
		final MmtrUnit first = wagon("w1");
		final MmtrUnit second = wagon("w2");
		final MmtrFormation formation = MmtrFormation.of(first, second);
		assertTrue(formation.isCouplerFree(first.couplerA()));
		assertFalse(formation.isCouplerFree(first.couplerB()));
		assertFalse(formation.isCouplerFree(second.couplerA()));
		assertTrue(formation.isCouplerFree(second.couplerB()));
		assertFalse(formation.isCouplerFree(null));

		final MmtrFormation single = MmtrFormation.of(wagon("solo"));
		assertTrue(single.isCouplerFree(single.firstUnit().couplerA()));
		assertTrue(single.isCouplerFree(single.firstUnit().couplerB()));
	}

	@Test
	public void cutRejectsSeamOutsideTheRange() {
		final MmtrFormation emu = MmtrFormation.of(emu8("emu_1"));
		assertThrows(IllegalArgumentException.class, () -> emu.splitAfterSeam(-1));
		assertThrows(IllegalArgumentException.class, () -> emu.splitAfterSeam(0));

		final MmtrFormation rake = MmtrFormation.of(wagon("w1"), wagon("w2"));
		assertThrows(IllegalArgumentException.class, () -> rake.splitAfterSeam(-1));
		assertThrows(IllegalArgumentException.class, () -> rake.splitAfterSeam(1));
		assertThrows(IllegalArgumentException.class, () -> rake.seamArcM(1));
	}

	@Test
	public void couplerIdentityIsTheUnitAndEnd() {
		final MmtrCoupler coupler = new MmtrCoupler("u1", MmtrCoupler.End.A, MmtrCoupler.Type.HAULED, true);
		final MmtrCoupler sameEndpoint = new MmtrCoupler("u1", MmtrCoupler.End.A, MmtrCoupler.Type.POWERED_CONSIST, false);
		assertEquals(coupler, sameEndpoint, "type and air state do not change the endpoint's identity");
		assertEquals(coupler.hashCode(), sameEndpoint.hashCode());
		assertNotEquals(coupler, new MmtrCoupler("u1", MmtrCoupler.End.B, MmtrCoupler.Type.HAULED, true));
		assertNotEquals(coupler, new MmtrCoupler("u2", MmtrCoupler.End.A, MmtrCoupler.Type.HAULED, true));
	}

	@Test
	public void unitAndCarRejectBadInput() {
		assertThrows(IllegalArgumentException.class, () -> new MmtrUnitCar("", 10, 0, 0, false, null));
		assertThrows(IllegalArgumentException.class, () -> new MmtrUnitCar("car", 0, 0, 0, false, null));
		assertThrows(IllegalArgumentException.class, () -> new MmtrUnitCar("car", -1, 0, 0, false, null));
		assertThrows(IllegalArgumentException.class, () -> new MmtrUnitCar("car", 10, -0.1, 0, false, null));
		assertThrows(IllegalArgumentException.class, () -> new MmtrUnitCar("car", 10, 0, -0.1, false, null));
		assertThrows(IllegalArgumentException.class, () -> new MmtrUnit("", MmtrUnit.UnitClass.WAGON, list(MmtrUnitCar.of("c", 10))));
		assertThrows(IllegalArgumentException.class, () -> new MmtrUnit("u", MmtrUnit.UnitClass.WAGON, new ObjectArrayList<>()));
		assertThrows(IllegalArgumentException.class, () -> MmtrFormation.of());
		assertThrows(IllegalArgumentException.class, () -> MmtrFormation.of(wagon("w1"), wagon("w1")), "unit ids must be unique in a formation");
	}
}
