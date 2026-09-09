package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.MmtrRunPlanner;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3 slice 6: MmtrRunPlanner — the engine-side run planner for MOVE_TO-style steps. Given a live
 * motion vehicle and a target rail, it BFS-plans the graph route, decides every en-route turnout
 * operator (same cos ranking the walker uses) and computes the exact cumulative stop distance in the
 * vehicle's own walker space; ops then just arm auto + stop target and the vehicle runs itself there.
 */
public final class MmtrRunPlannerTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
		+ "}";

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/**
	 * Yard YR (-32..-20) -> mouth fork at (-20): {rX straight to (60,0,0) | rY diverge to (60,0,14)}
	 * -> second fork at (60,0,0): {rP straight to (140,0,0) | rQ diverge to (140,0,14)}.
	 */
	private static final class Net {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-run-planner"), false);
		final Position yardBack = new Position(-32, 0, 0);
		final Position yardMouth = new Position(-20, 0, 0);
		final Position node60 = new Position(60, 0, 0);
		final Rail yardRail = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Rail rX = through(yardMouth, node60);
		final Rail rY = through(yardMouth, new Position(60, 0, 14));
		final Rail rP = through(node60, new Position(140, 0, 0));
		final Rail rQ = through(node60, new Position(140, 0, 14));
		final Depot depot = new Depot(TransportMode.TRAIN, sim);
		final Siding siding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);
		final BranchStore store = new BranchStore();

		Net() {
			depot.setName("Yard");
			depot.setCorners(new Position(-40, -5, -5), new Position(-10, 5, 5));
			sim.rails.add(yardRail);
			sim.rails.add(rX);
			sim.rails.add(rY);
			sim.rails.add(rP);
			sim.rails.add(rQ);
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
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, store, null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			return vehicle;
		}
	}

	/**
	 * Real-yard regression (P3 real-machine): the yard rail CONTINUES behind the parked rail into
	 * more track (like the real depot yard, whose rear leads into other leads). The planner BFS is
	 * undirected, so a target reachable only behind the parked rail must NOT be planned "backwards"
	 * - the walker can never reverse. Forward-only routes stay feasible.
	 */
	private static final class RearNet {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-run-planner-rear"), false);
		final Position yardBack = new Position(-32, 0, 0);
		final Position yardMouth = new Position(-20, 0, 0);
		final Rail yardRail = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Rail rX = through(yardMouth, new Position(60, 0, 0));
		final Rail rY = through(yardMouth, new Position(60, 0, 14));
		final Rail rearChain = through(yardBack, new Position(-60, 0, 0)); // track continues behind the parked rail
		final Rail rearPlatformRail = Rail.newPlatformRail(new Position(-60, 0, 0), Angle.fromAngle(0), new Position(-100, 0, 0), Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Depot depot = new Depot(TransportMode.TRAIN, sim);
		final Siding siding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);
		final Station rearStation = new Station(sim);
		final Platform rearPlatform = new Platform(new Position(-60, 0, 0), new Position(-100, 0, 0), TransportMode.TRAIN, sim);
		final BranchStore store = new BranchStore();

		RearNet() {
			depot.setName("Yard");
			depot.setCorners(new Position(-40, -5, -5), new Position(-10, 5, 5));
			rearStation.setName("Rear");
			rearStation.setCorners(new Position(-110, -5, -5), new Position(-50, 5, 5));
			sim.rails.add(yardRail);
			sim.rails.add(rX);
			sim.rails.add(rY);
			sim.rails.add(rearChain);
			sim.rails.add(rearPlatformRail);
			sim.depots.add(depot);
			sim.sidings.add(siding);
			sim.stations.add(rearStation);
			sim.platforms.add(rearPlatform);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			siding.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			assertTrue(depot.savedRails.contains(siding), "siding must attach to the depot yard");
			assertTrue(rearStation.savedRails.contains(rearPlatform), "rear platform must attach to its station");
			siding.tick();
		}

		Vehicle spawn() {
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, store, null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			return vehicle;
		}
	}

	@Test
	public void plannerReachesTheYardRearOnlyThroughAnExplicitReversal() {
		final RearNet n = new RearNet();
		final Vehicle v = n.spawn();

		// The rear platform is only reachable by going back out of the yard's rear end. The forward
		// search must never quietly route the parked train backwards; since C10 the engine may plan it,
		// but ONLY as an explicit 牵出—推进 reversal - the plan carries the flip point, so the train
		// stops, changes ends and runs back instead of "driving" backwards.
		final MmtrRunPlanner.Plan behind = MmtrRunPlanner.planToRail(n.sim, v, n.rearPlatformRail.getHexId(), 0.5);
		assertTrue(behind.feasible, "the rear is reachable through an explicit reversal: " + behind.reason);
		assertFalse(behind.flipRailHex.isEmpty(), "a backwards target must be planned as a reversal, never a silent backwards run");
		assertTrue(behind.flipCumulativeM > 0, "the reversal point is planned in walker space");

		// The forward target stays perfectly feasible and needs no reversal (regression guard).
		final MmtrRunPlanner.Plan ahead = MmtrRunPlanner.planToRail(n.sim, v, n.rX.getHexId(), 0.5);
		assertTrue(ahead.feasible, "forward target still planned: " + ahead.reason);
		assertTrue(ahead.flipRailHex.isEmpty(), "a forward target must not invent a reversal");
		assertEquals(1, ahead.forkOps.size(), "one en-route turnout (the yard mouth)");
	}

	private static void tickUntil(Siding siding, java.util.function.BooleanSupplier condition, int maxTicks) {
		for (int i = 0; i < maxTicks && !condition.getAsBoolean(); i++) {
			siding.simulateVehicles(1000, null);
		}
		assertTrue(condition.getAsBoolean(), "condition not met within " + maxTicks + " ticks");
	}

	@Test
	public void plannerDrivesAutoRunOntoDivergingBranchRail() {
		final Net n = new Net();
		final Vehicle v = n.spawn();
		final double fraction = 0.6;

		final MmtrRunPlanner.Plan plan = MmtrRunPlanner.planToRail(n.sim, v, n.rY.getHexId(), fraction);
		assertTrue(plan.feasible, "plan to the diverging rail must be feasible: " + plan.reason);
		assertEquals(1, plan.forkOps.size(), "one en-route turnout (the yard mouth)");
		assertEquals("1", plan.forkOps.get(0)[4], "the diverging rail needs operator 1 at the mouth fork");
		final double expectedStop = v.getMmtrMotionWalker().distanceM()
			+ (n.yardRail.railMath.getLength() - v.getMmtrMotionWalker().offsetM())
			+ fraction * n.rY.railMath.getLength();
		assertEquals(expectedStop, plan.stopCumulativeM, 1e-6, "stop distance in walker space");

		MmtrRunPlanner.applyForkOps(plan, n.store);
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(plan.stopCumulativeM, true);
		tickUntil(n.siding, v::isMmtrMotionStoppedAtTarget, 3000);
		assertEquals(plan.stopCumulativeM, v.getRailProgress(), 0.05, "auto run stopped exactly at the planned stop");
		assertEquals(n.rY.getHexId(), v.getMmtrMotionWalker().railHex(), "vehicle arrived on the diverging rail");
		assertTrue(v.vehicleExtraData.getDoorMultiplier() > 0, "stop opened doors");
		assertFalse(v.isMmtrManualOverride(), "planner-driven run needed no driver");
	}

	@Test
	public void plannerChainsBothForksToDeeperStraightRail() {
		final Net n = new Net();
		final Vehicle v = n.spawn();
		final double fraction = 0.5;

		final MmtrRunPlanner.Plan plan = MmtrRunPlanner.planToRail(n.sim, v, n.rP.getHexId(), fraction);
		assertTrue(plan.feasible, "plan through both forks must be feasible: " + plan.reason);
		assertEquals(2, plan.forkOps.size(), "two en-route turnouts (mouth + node60)");
		final double expectedStop = v.getMmtrMotionWalker().distanceM()
			+ (n.yardRail.railMath.getLength() - v.getMmtrMotionWalker().offsetM())
			+ n.rX.railMath.getLength()
			+ fraction * n.rP.railMath.getLength();
		assertEquals(expectedStop, plan.stopCumulativeM, 1e-6, "stop distance spans yard + rX + fraction of rP");

		MmtrRunPlanner.applyForkOps(plan, n.store);
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(plan.stopCumulativeM, false);
		tickUntil(n.siding, v::isMmtrMotionStoppedAtTarget, 4000);
		assertEquals(plan.stopCumulativeM, v.getRailProgress(), 0.05, "auto run stopped exactly at the planned stop");
		assertEquals(n.rP.getHexId(), v.getMmtrMotionWalker().railHex(), "vehicle arrived on the deep straight rail");
		assertFalse(v.vehicleExtraData.getDoorMultiplier() > 0, "stop was requested without doors");
		assertEquals(0, v.getSpeed(), 1e-9, "resting at the planned stop");
	}


	/**
	 * P3 TEE mainline: the yard rail runs straight into the mouth node at (0,0,0) where the line
	 * continues ONLY at 90 degrees - rL along +Z, rR along -Z, no straight continuation. Both
	 * continuations have the same cosine (0): legacy top-2 ordering could not express them
	 * deterministically. The planner must map them onto the ordered legs (0 = left, 1 = right) and
	 * the preset must drive the auto run onto the exact planned rail.
	 */
	private static final class TeeNet {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-run-planner-tee"), false);
		final Position yardBack = new Position(-12, 0, 0);
		final Position node = new Position(0, 0, 0);
		final Rail yardRail = Rail.newSidingRail(yardBack, Angle.fromAngle(0), node, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Rail rL = newZ(node, new Position(0, 0, 30)); // +Z (left of an eastbound approach)
		final Rail rR = newZ(node, new Position(0, 0, -30)); // -Z (right)
		final Depot depot = new Depot(TransportMode.TRAIN, sim);
		final Siding siding = new Siding(yardBack, node, 12, TransportMode.TRAIN, sim);
		final BranchStore store = new BranchStore();

		private static Rail newZ(Position p1, Position p2) {
			final boolean plus = p2.getZ() > p1.getZ();
			return Rail.newRail(p1, plus ? Angle.fromAngle(90) : Angle.fromAngle(270), p2, plus ? Angle.fromAngle(270) : Angle.fromAngle(90), Rail.Shape.QUADRATIC, 0, NO_STYLES,
				80, 80, false, false, true, false, true, TransportMode.TRAIN);
		}

		TeeNet() {
			depot.setName("Yard");
			depot.setCorners(new Position(-20, -5, -5), new Position(0, 5, 5));
			sim.rails.add(yardRail);
			sim.rails.add(rL);
			sim.rails.add(rR);
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
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, store, null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			return vehicle;
		}
	}

	@Test
	public void plannerPresetsTeeJunctionLegIndexesFromOrderedLegs() {
		final TeeNet n = new TeeNet();
		final Vehicle v = n.spawn();

		final MmtrRunPlanner.Plan left = MmtrRunPlanner.planToRail(n.sim, v, n.rL.getHexId(), 0.5);
		assertTrue(left.feasible, "plan onto the left TEE leg must be feasible: " + left.reason);
		assertEquals(1, left.forkOps.size(), "one en-route turnout (the yard mouth)");
		assertEquals("0", left.forkOps.get(0)[4], "the +Z leg is ordered leg 0 (left of the approach)");

		final MmtrRunPlanner.Plan right = MmtrRunPlanner.planToRail(n.sim, v, n.rR.getHexId(), 0.5);
		assertTrue(right.feasible, "plan onto the right TEE leg must be feasible: " + right.reason);
		assertEquals("1", right.forkOps.get(0)[4], "the -Z leg is ordered leg 1 (right)");
	}

	@Test
	public void plannerDrivesAutoRunThroughTeeOntoPlannedLeg() {
		final TeeNet n = new TeeNet();
		final Vehicle v = n.spawn();
		final double fraction = 0.5;

		final MmtrRunPlanner.Plan plan = MmtrRunPlanner.planToRail(n.sim, v, n.rL.getHexId(), fraction);
		assertTrue(plan.feasible, "plan through the TEE must be feasible: " + plan.reason);
		final double expectedStop = v.getMmtrMotionWalker().distanceM()
			+ (n.yardRail.railMath.getLength() - v.getMmtrMotionWalker().offsetM())
			+ fraction * n.rL.railMath.getLength();
		assertEquals(expectedStop, plan.stopCumulativeM, 1e-6, "stop distance in walker space");

		MmtrRunPlanner.applyForkOps(plan, n.store);
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(plan.stopCumulativeM, true);
		tickUntil(n.siding, v::isMmtrMotionStoppedAtTarget, 3000);
		assertEquals(plan.stopCumulativeM, v.getRailProgress(), 0.05, "auto run stopped exactly at the planned stop");
		assertEquals(n.rL.getHexId(), v.getMmtrMotionWalker().railHex(), "vehicle turned onto the planned +Z leg at the TEE");
		assertTrue(v.vehicleExtraData.getDoorMultiplier() > 0, "stop opened doors");
		assertFalse(v.isMmtrManualOverride(), "planner-driven run needed no driver");
	}

	@Test
	public void plannerReportsInfeasibleCases() {
		final Net n = new Net();
		final Vehicle v = n.spawn();

		final MmtrRunPlanner.Plan missing = MmtrRunPlanner.planToRail(n.sim, v, "deadbeef", 0.5);
		assertFalse(missing.feasible, "missing target rail must be infeasible");
		assertTrue(missing.reason.contains("not found"), "reason names the missing rail");

		final MmtrRunPlanner.Plan sameRail = MmtrRunPlanner.planToRail(n.sim, v, n.yardRail.getHexId(), 0.5);
		assertFalse(sameRail.feasible, "target on the current rail must be infeasible for the planner");
		assertTrue(sameRail.reason.contains("already on"), "reason explains the current-rail case");
	}
}
