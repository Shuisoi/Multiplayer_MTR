package org.mtr.core.mmtr;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.VehicleCar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** World-layer coupling/uncoupling core: car-list merge/split, lengths and guards. */
public final class MmtrCouplingTests {

	private static VehicleCar car(String id) {
		return new VehicleCar(id, 10, 2, 100, 0, 5, 0.5, 0.5);
	}

	private static ObjectArrayList<VehicleCar> cars(String... ids) {
		final ObjectArrayList<VehicleCar> list = new ObjectArrayList<>();
		for (final String id : ids) {
			list.add(car(id));
		}
		return list;
	}

	@Test
	public void mergePreservesOrderAndLength() {
		final ObjectArrayList<VehicleCar> head = cars("loco", "w1");
		final ObjectArrayList<VehicleCar> tail = cars("w2", "w3", "w4");
		final ObjectArrayList<VehicleCar> merged = MmtrCoupling.mergeCars(head, tail);
		assertEquals(5, merged.size());
		assertEquals("loco", merged.get(0).getVehicleId());
		assertEquals("w4", merged.get(4).getVehicleId());
		// Merging adds the coupler padding between the two cuts, so the combined length must be
		// strictly longer than either side.
		assertTrue(MmtrCoupling.totalLength(merged) > MmtrCoupling.totalLength(head), "merged train is longer than the head");
		assertTrue(MmtrCoupling.totalLength(merged) > MmtrCoupling.totalLength(tail), "merged train is longer than the tail");
	}

	@Test
	public void splitRestoresOriginalFormation() {
		final ObjectArrayList<VehicleCar> all = cars("loco", "w1", "w2", "w3");
		for (int cut = 0; cut < 3; cut++) {
			final ObjectObjectImmutablePair<ObjectArrayList<VehicleCar>, ObjectArrayList<VehicleCar>> pair = MmtrCoupling.splitCars(all, cut);
			assertEquals(cut + 1, pair.left().size(), "head size at cut " + cut);
			assertEquals(4 - cut - 1, pair.right().size(), "tail size at cut " + cut);
			assertTrue(MmtrCoupling.totalLength(pair.left()) + MmtrCoupling.totalLength(pair.right()) < MmtrCoupling.totalLength(all), "splitting removes the coupler between the two cuts");
			// Recouple: length and order must be exactly the original formation again.
			assertEquals(MmtrCoupling.totalLength(all), MmtrCoupling.totalLength(MmtrCoupling.mergeCars(pair.left(), pair.right())), 1e-9, "re-coupled length equals the original");
			final ObjectArrayList<VehicleCar> recoupled = MmtrCoupling.mergeCars(pair.left(), pair.right());
			for (int i = 0; i < all.size(); i++) {
				assertEquals(all.get(i).getVehicleId(), recoupled.get(i).getVehicleId(), "car order must survive a round trip");
			}
		}
	}

	@Test
	public void cannotCoupleAcrossTracksWhileMovingOrMidRoute() {
		assertFalse(MmtrCoupling.canCoupleAtDepot(1, 2, true, true, 0, 0, 2, 2, 10, true, true).allowed, "different sidings rejected");
		assertFalse(MmtrCoupling.canCoupleAtDepot(1, 1, false, true, 0, 0, 2, 2, 10, true, true).allowed, "mid-route rejected");
		assertFalse(MmtrCoupling.canCoupleAtDepot(1, 1, true, true, 0.001, 0, 2, 2, 10, true, true).allowed, "moving rejected");
	}

	@Test
	public void couplingGuardCapacityAndManualRules() {
		assertTrue(MmtrCoupling.canCoupleAtDepot(1, 1, true, true, 0, 0, 2, 3, 10, true, true).allowed, "2+3 into a 10-car siding is fine");
		assertFalse(MmtrCoupling.canCoupleAtDepot(1, 1, true, true, 0, 0, 8, 3, 10, true, true).allowed, "over capacity rejected");
		assertFalse(MmtrCoupling.canCoupleAtDepot(1, 1, true, true, 0, 0, 2, 2, 10, true, false).allowed, "manual siding required");
	}

	@Test
	public void uncoupleGuardNeedsBothSidesNonEmpty() {
		assertTrue(MmtrCoupling.canUncouple(4, 2).allowed);
		assertFalse(MmtrCoupling.canUncouple(4, -1).allowed);
		assertFalse(MmtrCoupling.canUncouple(4, 3).allowed, "must leave a car on the tail");
		assertFalse(MmtrCoupling.canUncouple(1, 0).allowed, "single-car trains cannot be cut");
	}
}