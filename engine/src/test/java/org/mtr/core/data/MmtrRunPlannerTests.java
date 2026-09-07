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
