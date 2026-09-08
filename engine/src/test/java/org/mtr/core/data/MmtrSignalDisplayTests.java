package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MMTR signal display channel (server-authoritative): every rail carries its own reserved MMTR
 * signal color; a running motion train registers the rails it occupies as CURRENTLY_RESERVE under
 * that color through the standard rail signal-block machinery, which Rail#tick1 diffs and pushes
 * to clients as SignalBlockUpdates - the in-game MTR signal lights then turn red for every client.
 */
public final class MmtrSignalDisplayTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
		+ "}";

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	@Test
	public void railsAreSeededWithTheirOwnReservedSignalColor() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-sigdisplay-seed"), false);
		final Position mouth = new Position(-8, 0, 0);
		final Rail yardRail = Rail.newSidingRail(new Position(-20, 0, 0), Angle.fromAngle(0), mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Rail aRail = through(mouth, new Position(40, 0, 0));
		sim.rails.add(yardRail);
		sim.rails.add(aRail);
		sim.sync();

		sim.mmtrEnsureSignalColors();
		assertTrue(yardRail.getSignalColors().contains((int) yardRail.mmtrSignalColor()), "yard rail carries its MMTR color");
		assertTrue(aRail.getSignalColors().contains((int) aRail.mmtrSignalColor()), "mainline rail carries its MMTR color");
		assertFalse(yardRail.getSignalColors().contains((int) aRail.mmtrSignalColor()), "per-rail colors do not spread (unique per rail)");
		// Idempotent re-seed.
		sim.mmtrEnsureSignalColors();
		assertTrue(yardRail.getSignalColors().size() <= 1, "no duplicate color rows on re-seed");
	}

	@Test
	public void runningMotionTrainRegistersItsOccupiedRailsAsCurrentlyBlocked() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-sigdisplay-occupy"), false);
		final Position rear = new Position(-20, 0, 0);
		final Position mouth = new Position(-8, 0, 0);
		final Rail yardRail = Rail.newSidingRail(rear, Angle.fromAngle(0), mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Rail aRail = through(mouth, new Position(60, 0, 0));
		final Depot depot = new Depot(TransportMode.TRAIN, sim);
		final Siding siding = new Siding(rear, mouth, 12, TransportMode.TRAIN, sim);
		depot.setName("Yard");
		depot.setCorners(new Position(-25, -3, -3), new Position(65, 3, 3));
		sim.rails.add(yardRail);
		sim.rails.add(aRail);
		sim.depots.add(depot);
		sim.sidings.add(siding);
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
		siding.setVehicleCars(cars);
		sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
		sim.mmtrDefaultConsistTypeId = "emu";
		sim.sync();
		siding.tick();
		sim.mmtrEnsureSignalColors();

		final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, new BranchStore(), null);
		assertNotNull(walker, "yard walker must resolve");
		final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
		assertNotNull(vehicle, "motion seam must spawn");

		// One tick with the parked (not yet driven) train on the yard rail: the occupancy hold must
		// land under the rail's MMTR color in the standard currently-blocked set.
		siding.simulateVehicles(1000, null);
		final boolean[] blocked = {false};
		yardRail.iterateCurrentlyBlockedSignalColors(color -> {
			if (color == yardRail.mmtrSignalColor()) {
				blocked[0] = true;
			}
		});
		assertTrue(blocked[0], "occupied yard rail carries its MMTR color in the currently-blocked set");

		// The neighbour rail stays clean (one color per rail, no recursive spread).
		final boolean[] neighbourBlocked = {false};
		aRail.iterateCurrentlyBlockedSignalColors(color -> neighbourBlocked[0] = true);
		assertFalse(neighbourBlocked[0], "unoccupied neighbour rail carries no blocked colors");
	}
}
