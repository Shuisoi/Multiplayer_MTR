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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000}]"
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

	/**
	 * **经由点（路径点）网**：咽喉处两条引入线，短的先够着、长的才从经由点 V 过。
	 *
	 * <pre>
	 * 库股 -32 ──→ 口 -20 ─┬─ rShort（1 跳）─────────────→ 汇 J 60 ── rTarget ──→ 140
	 *                       └─ rVia1 ──→ V(20,0,20) ── rVia2 ──↑
	 * </pre>
	 *
	 * <p>现实对应（本图现场）：回库车的两条引入线都能到库，前向 BFS 挑**跳数少**的那条（= 出库方向那条），
	 * 于是逆向开进咽喉、与出库车互堵。经由点 V 就是"必须走这条"的写法。</p>
	 */
	private static final class ViaNet {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-run-planner-via"), false);
		final Position yardBack = new Position(-32, 0, 0);
		final Position mouth = new Position(-20, 0, 0);
		final Position via = new Position(20, 0, 20);
		final Position junction = new Position(60, 0, 0);
		final Rail yardRail = Rail.newSidingRail(yardBack, Angle.fromAngle(0), mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Rail rShort = through(mouth, junction);
		final Rail rVia1 = through(mouth, via);
		final Rail rVia2 = through(via, junction);
		final Rail rTarget = through(junction, new Position(140, 0, 0));
		final Depot depot = new Depot(TransportMode.TRAIN, sim);
		final Siding siding = new Siding(yardBack, mouth, 12, TransportMode.TRAIN, sim);
		final BranchStore store = new BranchStore();

		ViaNet() {
			depot.setName("Yard");
			depot.setCorners(new Position(-40, -5, -5), new Position(-10, 5, 5));
			sim.rails.add(yardRail);
			sim.rails.add(rShort);
			sim.rails.add(rVia1);
			sim.rails.add(rVia2);
			sim.rails.add(rTarget);
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
			assertNotNull(vehicle, "motion vehicle must spawn");
			return vehicle;
		}
	}

	private static ObjectArrayList<String> vias(String... nodeKeys) {
		final ObjectArrayList<String> out = new ObjectArrayList<>();
		java.util.Collections.addAll(out, nodeKeys);
		return out;
	}

	/**
	 * 不带经由点时照旧走最短那条（**回归闸门**：加了经由点这条路，不能顺手改了老行为）。
	 * 带经由点时，进路必须真的穿过那个节点 —— 而且不是停在它上面（"穿过"，不是"到站"）。
	 */
	@Test
	public void plannerRoutesThroughTheViaNodeOnlyWhenTheStepAsksForIt() {
		final ViaNet n = new ViaNet();
		final Vehicle v = n.spawn();

		final MmtrRunPlanner.Plan plain = MmtrRunPlanner.planToRail(n.sim, v, n.rTarget.getHexId(), 0.5);
		assertTrue(plain.feasible, "plain plan must still be feasible: " + plain.reason);
		assertFalse(plain.nodes.contains(n.via), "without a via the short corridor is planned (老行为不变)");
		assertTrue(plain.viaNodes.isEmpty(), "plain plan reports no via");

		final MmtrRunPlanner.Plan viaPlan = MmtrRunPlanner.planToRail(n.sim, v, n.rTarget.getHexId(), 0.5, vias("20,0,20"));
		assertTrue(viaPlan.feasible, "via plan must be feasible: " + viaPlan.reason);
		assertTrue(viaPlan.nodes.contains(n.via), "the planned node chain must contain the via node");
		assertEquals(1, viaPlan.viaNodes.size(), "the plan reports the via it passes");
		assertTrue(viaPlan.routeRailHexes.contains(n.rVia1.getHexId()) && viaPlan.routeRailHexes.contains(n.rVia2.getHexId()),
			"the via corridor rails are on the route, in order: " + viaPlan.routeRailHexes);
		// 咽喉那处岔：两条进路给出**不同的腿**——这就是"走哪条引入线"在岔位层的样子。
		assertNotEquals(plain.forkOps.get(0)[4], viaPlan.forkOps.get(0)[4], "the mouth fork is thrown to the other leg for the via route");
		// 停车点仍在目标轨上：经由点是"穿过"，不是"停下来"。
		assertEquals(n.rTarget.getHexId(), viaPlan.stopRailHex, "the stop stays on the target rail, not on the via");
		assertEquals(0.5, viaPlan.stopFraction, 1e-9, "stop fraction is unchanged by the via");
		assertEquals(plain.stopCumulativeM + n.rVia1.railMath.getLength() + n.rVia2.railMath.getLength() - n.rShort.railMath.getLength(),
			viaPlan.stopCumulativeM, 1e-6, "the via detour is counted once in walker space");
	}

	/** 经由点不只是画在计划上：把计划自臂出去，车真的会从那条引入线开到目标轨上。 */
	@Test
	public void plannerDrivesTheAutoRunThroughTheViaNode() {
		final ViaNet n = new ViaNet();
		final Vehicle v = n.spawn();

		final MmtrRunPlanner.Plan plan = MmtrRunPlanner.planToRail(n.sim, v, n.rTarget.getHexId(), 0.5, vias("20,0,20"));
		assertTrue(plan.feasible, "via plan must be feasible: " + plan.reason);

		MmtrRunPlanner.applyForkOps(plan, n.store);
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(plan.stopCumulativeM, false);
		tickUntil(n.siding, v::isMmtrMotionStoppedAtTarget, 4000);
		assertEquals(plan.stopCumulativeM, v.getRailProgress(), 0.05, "auto run stopped exactly at the planned stop");
		assertEquals(n.rTarget.getHexId(), v.getMmtrMotionWalker().railHex(), "the run reached the target rail along the via corridor");
	}

	/**
	 * **已经越过的不再要求**：车辆自臂是每 tick 按当前位置重规划的，若经由点被当成"永远在前方"，
	 * 车一过它就会规划失败。判据是"它还是不是前方的事"——车所在的轨以它为端点就算已达成。
	 */
	@Test
	public void plannerDropsAViaTheTrainIsAlreadyOn() {
		final ViaNet n = new ViaNet();
		final Vehicle v = n.spawn();

		// 先不带经由点地把车开到**以经由点为端点的那根轨**上（现实中：车已经进了那条引入线）。
		final MmtrRunPlanner.Plan toCorridor = MmtrRunPlanner.planToRail(n.sim, v, n.rVia2.getHexId(), 0.5);
		assertTrue(toCorridor.feasible, "run onto the corridor rail: " + toCorridor.reason);
		MmtrRunPlanner.applyForkOps(toCorridor, n.store);
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(toCorridor.stopCumulativeM, false);
		tickUntil(n.siding, v::isMmtrMotionStoppedAtTarget, 4000);
		assertEquals(n.rVia2.getHexId(), v.getMmtrMotionWalker().railHex(), "the train now rides the rail that ends at the via node");

		final MmtrRunPlanner.Plan after = MmtrRunPlanner.planToRail(n.sim, v, n.rTarget.getHexId(), 0.5, vias("20,0,20"));
		assertTrue(after.feasible, "re-planning after the via must not fail: " + after.reason);
		assertTrue(after.viaNodes.isEmpty(), "a via the train already reached is no longer required");
	}

	/**
	 * **经由点被甩在目标之后时不绕圈**：从经由点找目标，只会找到被它甩在身后的那一端 ——
	 * 于是 BFS 绕全网一圈再回来。前向进路不许折返，绕圈必然要掉头，所以判不可行并说清原因
	 * （"宁可这一步失败"：现场那 25 分钟互堵正是"看着能走、其实走不通"的进路放出去的结果）。
	 */
	@Test
	public void plannerRefusesAViaThatLiesBehindTheTarget() {
		final ViaNet n = new ViaNet();
		final Vehicle v = n.spawn();

		// 目标就是经由点自己所在的那根轨：车已经在口上，目标是"往这条路里开到头"，
		// 于是"必须经过 V"只剩下绕一圈回来这一种解释。
		final MmtrRunPlanner.Plan loop = MmtrRunPlanner.planToRail(n.sim, v, n.rVia1.getHexId(), 1.0, vias("20,0,20"));
		assertFalse(loop.feasible, "a via behind the target must not be planned as a lap of the whole network");
		assertTrue(loop.reason.contains("经由点"), "reason names the via: " + loop.reason);
	}

	/** 图上定位不到的经由点：**报错**，绝不悄悄降级成"不带经由点"（那等于把逆行放回去）。 */
	@Test
	public void plannerRefusesAnUnknownViaNode() {
		final ViaNet n = new ViaNet();
		final Vehicle v = n.spawn();

		final MmtrRunPlanner.Plan unknown = MmtrRunPlanner.planToRail(n.sim, v, n.rTarget.getHexId(), 0.5, vias("999,0,999"));
		assertFalse(unknown.feasible, "an unknown via must be infeasible, never silently ignored");
		assertTrue(unknown.reason.contains("经由点"), "reason names the via problem: " + unknown.reason);

		final MmtrRunPlanner.Plan malformed = MmtrRunPlanner.planToRail(n.sim, v, n.rTarget.getHexId(), 0.5, vias("20,0"));
		assertFalse(malformed.feasible, "a malformed via must be infeasible too");
	}

	@Test
	public void plannerReportsInfeasibleCases() {		final Net n = new Net();
		final Vehicle v = n.spawn();

		final MmtrRunPlanner.Plan missing = MmtrRunPlanner.planToRail(n.sim, v, "deadbeef", 0.5);
		assertFalse(missing.feasible, "missing target rail must be infeasible");
		assertTrue(missing.reason.contains("not found"), "reason names the missing rail");

		final MmtrRunPlanner.Plan sameRail = MmtrRunPlanner.planToRail(n.sim, v, n.yardRail.getHexId(), 0.5);
		assertFalse(sameRail.feasible, "target on the current rail must be infeasible for the planner");
		assertTrue(sameRail.reason.contains("already on"), "reason explains the current-rail case");
	}
}
