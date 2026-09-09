package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.VehicleExtraData.MmtrDoorSide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B7.6h: per-side crew door control.
 *
 * <p>Real machines have a door key per side (open only the platform side), and the reported symptom
 * was that the HUD said the doors were open while nothing moved - because MTR only animates a door
 * when it finds a platform block beside it. These tests pin the engine half of the fix: the two sides
 * are independent, the aggregate {@code doorTarget} is "either side open", and a crew command marks
 * the doors manual so the client stops applying its platform rule.</p>
 */
public final class MmtrDoorSideControlTests {

	private static VehicleExtraData data() {
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
		return VehicleExtraData.createWithLegs(1L, 0L, 6, cars, new ObjectArrayList<>(), 0.0004, 0.0004, true, 120, 30000L);
	}

	@Test
	public void theTwoSidesAreIndependent() {
		final VehicleExtraData data = data();
		assertFalse(data.getMmtrDoorLeft());
		assertFalse(data.getMmtrDoorRight());
		assertFalse(data.isMmtrDoorManual(), "a fresh vehicle's doors are not crew controlled");

		assertTrue(data.mmtrSetDoors("toggle", "left"), "the left doors are now open");
		assertTrue(data.getMmtrDoorLeft());
		assertFalse(data.getMmtrDoorRight(), "the right doors stay shut");
		assertTrue(data.isMmtrDoorManual());
		assertTrue(data.getDoorMultiplier() > 0, "the aggregate door target follows either side");
		assertTrue(data.getMmtrDoorOpen(MmtrDoorSide.BOTH));

		assertTrue(data.mmtrSetDoors("toggle", "right"), "now both sides are open");
		assertTrue(data.getMmtrDoorLeft());
		assertTrue(data.getMmtrDoorRight());

		assertTrue(data.mmtrSetDoors("close", "left"), "closing one side leaves the other open");
		assertFalse(data.getMmtrDoorLeft());
		assertTrue(data.getMmtrDoorRight());
		assertTrue(data.getDoorMultiplier() > 0);

		assertFalse(data.mmtrSetDoors("close", "right"), "the last open side closes the aggregate");
		assertFalse(data.getMmtrDoorLeft());
		assertFalse(data.getMmtrDoorRight());
		assertTrue(data.isMmtrDoorManual(), "the crew is still working the doors by hand");
		assertFalse(data.mmtrDoorsOpen());
	}

	@Test
	public void thePlainCommandWorksBothSides() {
		final VehicleExtraData data = data();
		assertTrue(data.mmtrSetDoors("open"));
		assertTrue(data.getMmtrDoorLeft());
		assertTrue(data.getMmtrDoorRight());
		assertTrue(data.isMmtrDoorManual(), "even the plain command is a crew command, so it must show off-platform");
		assertFalse(data.mmtrSetDoors("close"));
		assertFalse(data.getMmtrDoorLeft());
		assertFalse(data.getMmtrDoorRight());
	}

	@Test
	public void theEngineAutomaticDoorsStayAutomatic() {
		// The engine's own door handling (a scheduled stop, or the "doors closed while moving" rule)
		// must NOT set the manual flag: those doors are opened by the platform side, which the client
		// still decides.
		final VehicleExtraData data = data();
		data.openDoors();
		assertTrue(data.getMmtrDoorLeft());
		assertTrue(data.getMmtrDoorRight());
		assertFalse(data.isMmtrDoorManual());
		data.closeDoors();
		assertFalse(data.getMmtrDoorLeft());
		assertFalse(data.getMmtrDoorRight());
		assertFalse(data.isMmtrDoorManual());
		data.toggleDoors();
		assertTrue(data.mmtrDoorsOpen());
		assertFalse(data.isMmtrDoorManual(), "MTR's own driver key keeps the automatic side selection");
	}

	@Test
	public void sideParsingIsForgiving() {
		assertEquals(MmtrDoorSide.LEFT, MmtrDoorSide.parse("left"));
		assertEquals(MmtrDoorSide.LEFT, MmtrDoorSide.parse(" L "));
		assertEquals(MmtrDoorSide.RIGHT, MmtrDoorSide.parse("Right"));
		assertEquals(MmtrDoorSide.RIGHT, MmtrDoorSide.parse("r"));
		assertEquals(MmtrDoorSide.BOTH, MmtrDoorSide.parse(null));
		assertEquals(MmtrDoorSide.BOTH, MmtrDoorSide.parse("both"));
		assertEquals(MmtrDoorSide.BOTH, MmtrDoorSide.parse("nonsense"));
	}
}
