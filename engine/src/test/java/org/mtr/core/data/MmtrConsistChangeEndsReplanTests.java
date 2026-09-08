package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.MmtrRunPlanner;
import org.mtr.core.mmtr.consist.MmtrCabState;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B7.5: after 换端 the armed run is stale by construction — its fork requests, planned fork ops,
 * fork distances and stop target were all computed for the old direction of travel. The vehicle must
 * drop them and plan again from the new leading end. The yard here has track on BOTH sides (a mouth
 * fork ahead, a rear chain behind), so both directions are physically plannable.
 */
public final class MmtrConsistChangeEndsReplanTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
		+ "}";

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static final class Net {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-consist-replan"), false);
		final Position yardBack = new Position(-32, 0, 0);
		final Position yardMouth = new Position(-20, 0, 0);
		final Rail yardRail = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Rail rX = through(yardMouth, new Position(60, 0, 0));
		final Rail rY = through(yardMouth, new Position(60, 0, 14));
		final Rail rearChain = through(yardBack, new Position(-60, 0, 0));
		final Depot depot = new Depot(TransportMode.TRAIN, sim);
		final Siding siding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);
		final BranchStore store = new BranchStore();

		Net() {
			depot.setName("Yard");
			depot.setCorners(new Position(-40, -5, -5), new Position(-10, 5, 5));
			sim.rails.add(yardRail);
			sim.rails.add(rX);
			sim.rails.add(rY);
			sim.rails.add(rearChain);
			sim.depots.add(depot);
			sim.sidings.add(siding);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			siding.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			assertTrue(depot.savedRails.contains(siding), "siding must attach to the depot yard");
			siding.tick();
		}

		Vehicle spawn() {
			final Vehicle vehicle = siding.spawnMmtrConsistVehicle(siding.mmtrConsistWalkerFromYard(null, store, null), MmtrCabState.Cab.CAB_A);
			assertNotNull(vehicle, "consist seam must spawn");
			return vehicle;
		}
	}

	private static ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> positions() {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vp = new ObjectArrayList<>();
		vp.add(new Object2ObjectAVLTreeMap<>());
		vp.add(new Object2ObjectAVLTreeMap<>());
		return vp;
	}

	@Test
	public void changeEndsDropsTheStaleRunAndReplansTheOtherWay() {
		final Net n = new Net();
		final Vehicle v = n.spawn();
		// Arm a run out of the yard (the mouth fork needs a grant, so the plan really holds a point).
		final MmtrRunPlanner.Plan out = MmtrRunPlanner.planToRail(n.sim, v, n.rX.getHexId(), 1.0);
		assertTrue(out.feasible, "outbound plan must be feasible: " + out.reason);
		assertFalse(out.forkOps.isEmpty(), "the mouth fork is on the outbound route");
		assertTrue(v.armMmtrPointRun(n.sim, out));
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(out.stopCumulativeM, false);
		assertTrue(v.isMmtrMotionAuto(), "the outbound run is armed");
		assertEquals("v" + v.getId(), n.sim.mmtrPointAuthority.holder(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId()), "the consist holds the mouth fork");

		// 换端: the crew changes cabs while the consist stands in the yard.
		assertTrue(v.changeEndsMmtrMotion());
		assertEquals(MmtrCabState.Cab.CAB_B, v.getMmtrConsistWalker().cabs().activeCab());
		assertEquals(MmtrCabState.End.B, v.getMmtrConsistWalker().cabs().leadingEnd(), "the consist now faces the buffer");
		assertFalse(v.isMmtrMotionAuto(), "the stale outbound run must be dropped");
		assertNull(n.sim.mmtrPointAuthority.holder(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId()), "its fork hold must be released");

		// Re-plan the other way: the rear chain is now ahead of the consist.
		final MmtrRunPlanner.Plan back = MmtrRunPlanner.planToRail(n.sim, v, n.rearChain.getHexId(), 1.0);
		assertTrue(back.feasible, "the new direction must plan: " + back.reason);
		assertTrue(back.stopCumulativeM > v.getRailProgress(), "the new stop lies ahead in the new direction, not behind");
		assertTrue(v.armMmtrPointRun(n.sim, back));
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(back.stopCumulativeM, false);

		// The consist drives itself the new way.
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vp = positions();
		final double before = v.getRailProgress();
		boolean onRearChain = false;
		for (int i = 0; i < 600 && !onRearChain; i++) {
			vp.set(0, vp.get(1));
			vp.set(1, new Object2ObjectAVLTreeMap<>());
			v.simulate(1000, vp, null);
			onRearChain = n.rearChain.getHexId().equals(v.getMmtrConsistWalker().currentRailHex());
		}
		assertTrue(onRearChain, "the consist runs the re-planned route to the rear chain, progress " + v.getRailProgress());
		assertTrue(v.getRailProgress() > before, "distance only grows");
	}
}
