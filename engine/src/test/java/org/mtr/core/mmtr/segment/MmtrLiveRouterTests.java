package org.mtr.core.mmtr.segment;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrLiveRouter.Route;
import org.mtr.core.mmtr.segment.MmtrLiveRouter.Status;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Slice A/B live-routing proof: {@link MmtrLiveRouter} walks the real rail graph one node at a
 * time and, at a fork, elects the next rail from the operator branch / task (never auto). Flipping
 * the turnout changes which real rails the route actually follows ("搬A走A / 搬B走B"); an unset fork
 * halts the route awaiting authority; a straight-through node is not an authority question.
 */
public final class MmtrLiveRouterTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static Rail diverge(Position node, Position far) {
		return Rail.newRail(node, Angle.fromAngle(45), far, Angle.fromAngle(225), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	// Perpendicular rail with matching tangents (90/270): an x-axis helper with 0/180 tangents would
	// collapse into a zero-length rail, so vertical continuations must carry their own tangents.
	private static Rail railZ(Position p1, Position p2) {
		final boolean plus = p2.getZ() > p1.getZ();
		return Rail.newRail(p1, plus ? Angle.fromAngle(90) : Angle.fromAngle(270), p2, plus ? Angle.fromAngle(270) : Angle.fromAngle(90), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	// Yard mainline with a real fork: approach -> node0 -> { straight to A, 45deg diverge to B };
	// each branch then continues onward so routing can be observed across multiple nodes.
	private static final class Net {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-live"), false);
		final Position node0 = new Position(0, 0, 0);
		final Rail rIn = through(new Position(-20, 0, 0), node0);
		final Rail rStraight = through(node0, new Position(20, 0, 0));
		final Rail rDiverge = diverge(node0, new Position(20, 0, 12));
		final Rail rBeyondA = through(new Position(20, 0, 0), new Position(40, 0, 0));
		final Rail rBeyondB = diverge(new Position(20, 0, 12), new Position(40, 0, 12));

		Net() {
			sim.rails.add(rIn);
			sim.rails.add(rStraight);
			sim.rails.add(rDiverge);
			sim.rails.add(rBeyondA);
			sim.rails.add(rBeyondB);
			sim.sync();
		}

		BranchStore branch0() {
			final BranchStore store = new BranchStore();
			store.set(node0.getX(), node0.getY(), node0.getZ(), rIn.getHexId(), 0);
			return store;
		}

		BranchStore branch1() {
			final BranchStore store = new BranchStore();
			store.set(node0.getX(), node0.getY(), node0.getZ(), rIn.getHexId(), 1);
			return store;
		}
	}

	@Test
	public void unsetForkHaltsRouteAwaitingAuthority() {
		final Net n = new Net();
		final Route route = MmtrLiveRouter.route(n.sim, n.rIn, new Position(-20, 0, 0), null, new BranchStore(), 20);
		assertEquals(Status.AWAITING_AUTHORITY, route.status, "no authority at a fork -> halt, never auto");
		assertEquals(1, route.railHexOrder.size(), "route stops at the approach rail");
		assertEquals(n.rIn.getHexId(), route.railHexOrder.get(0));
		assertEquals("0,0,0", route.haltNodeKey);
	}

	@Test
	public void branch0WalksStraightBranch() {
		final Net n = new Net();
		final Route route = MmtrLiveRouter.route(n.sim, n.rIn, new Position(-20, 0, 0), null, n.branch0(), 20);
		assertNotEquals(Status.AWAITING_AUTHORITY, route.status);
		assertEquals(n.rIn.getHexId(), route.railHexOrder.get(0));
		assertEquals(n.rStraight.getHexId(), route.railHexOrder.get(1), "branch0 elects the straight rail");
		assertEquals(n.rBeyondA.getHexId(), route.railHexOrder.get(2), "straight-through node continues without authority");
	}

	@Test
	public void branch1WalksDivergeBranch() {
		final Net n = new Net();
		final Route route = MmtrLiveRouter.route(n.sim, n.rIn, new Position(-20, 0, 0), null, n.branch1(), 20);
		assertNotEquals(Status.AWAITING_AUTHORITY, route.status);
		assertEquals(n.rDiverge.getHexId(), route.railHexOrder.get(1), "branch1 elects the diverging rail");
		assertEquals(n.rBeyondB.getHexId(), route.railHexOrder.get(2));
	}

	@Test
	public void flippingTurnoutReversesWalkedBranch() {
		final Net n = new Net();
		assertEquals(n.rStraight.getHexId(), MmtrLiveRouter.route(n.sim, n.rIn, new Position(-20, 0, 0), null, n.branch0(), 20).railHexOrder.get(1));
		assertEquals(n.rDiverge.getHexId(), MmtrLiveRouter.route(n.sim, n.rIn, new Position(-20, 0, 0), null, n.branch1(), 20).railHexOrder.get(1));
	}

	@Test
	public void taskTargetOverridesStaleOperatorBranch() {
		final Net n = new Net();
		// Operator still set to straight (0), but the task targets the diverging platform rail.
		final Route route = MmtrLiveRouter.route(n.sim, n.rIn, new Position(-20, 0, 0), n.rDiverge.getHexId(), n.branch0(), 20);
		assertEquals(n.rDiverge.getHexId(), route.railHexOrder.get(1), "task target wins over a stale operator branch");
	}

	@Test
	public void routeCompletesAtTargetRail() {
		final Net n = new Net();
		final Route route = MmtrLiveRouter.route(n.sim, n.rIn, new Position(-20, 0, 0), n.rBeyondA.getHexId(), n.branch0(), 20);
		assertEquals(Status.AT_TARGET, route.status);
		assertEquals(3, route.railHexOrder.size());
		assertEquals(n.rBeyondA.getHexId(), route.railHexOrder.get(route.railHexOrder.size() - 1));
	}

	// --- MmtrLiveRouter.integrate: decoupled (segment, offset) motion state over a live route ---

	@Test
	public void integrateStopsMidSegmentAtRequestedOffset() {
		final Net n = new Net();
		final MmtrLiveRouter.MmtrMotionPoint p = MmtrLiveRouter.integrate(n.sim, n.rIn, new Position(-20, 0, 0), 5, new BranchStore(), null, 20);
		assertEquals(n.rIn.getHexId(), p.railHex);
		assertEquals(5, p.offsetM, 1e-6);
		assertEquals(MmtrLiveRouter.Status.TRAVELLING, p.status);
	}

	@Test
	public void integrateHaltsAtUnsetForkAwaitingAuthority() {
		final Net n = new Net();
		// 25m > approach(20m) so the train reaches node0 with distance left, but no one set the fork.
		final MmtrLiveRouter.MmtrMotionPoint p = MmtrLiveRouter.integrate(n.sim, n.rIn, new Position(-20, 0, 0), 25, new BranchStore(), null, 20);
		assertEquals(MmtrLiveRouter.Status.AWAITING_AUTHORITY, p.status);
		assertEquals(n.rIn.getHexId(), p.railHex, "stopped on the approach rail at its far end");
		assertEquals(20, p.offsetM, 1e-6);
		assertEquals("0,0,0", p.haltNodeKey);
	}

	@Test
	public void integrateCarriesRemainderOntoElectStraightBranch() {
		final Net n = new Net();
		// branch0 -> straight (20m) then on to rBeyondA (20m): 45m lands 5m into rBeyondA.
		final MmtrLiveRouter.MmtrMotionPoint p = MmtrLiveRouter.integrate(n.sim, n.rIn, new Position(-20, 0, 0), 45, n.branch0(), null, 20);
		assertEquals(MmtrLiveRouter.Status.TRAVELLING, p.status);
		assertEquals(n.rBeyondA.getHexId(), p.railHex);
		assertEquals(5, p.offsetM, 1e-6);
	}

	@Test
	public void integrateFollowsDivergeWhenFlipped() {
		final Net n = new Net();
		// branch1 -> diverge (~23.3m) then rBeyondB: 60m lands mid-way on rBeyondB.
		final MmtrLiveRouter.MmtrMotionPoint p = MmtrLiveRouter.integrate(n.sim, n.rIn, new Position(-20, 0, 0), 60, n.branch1(), null, 20);
		assertEquals(MmtrLiveRouter.Status.TRAVELLING, p.status);
		assertEquals(n.rBeyondB.getHexId(), p.railHex, "flipped turnout sent the train onto the diverging platform branch");
	}

	@Test
	public void integrateReachesTargetRailViaTask() {
		final Net n = new Net();
		// Operator still branch0, but task targets the diverging rail; crossing the fork boards it.
		final MmtrLiveRouter.MmtrMotionPoint p = MmtrLiveRouter.integrate(n.sim, n.rIn, new Position(-20, 0, 0), 60, n.branch0(), n.rDiverge.getHexId(), 20);
		assertEquals(MmtrLiveRouter.Status.AT_TARGET, p.status);
		assertEquals(n.rDiverge.getHexId(), p.railHex);
		assertEquals(0, p.offsetM, 1e-6);
	}


	// --- MmtrMotionWalker: resumable per-tick (segment,offset) engine over a live route ---

	@Test
	public void walkResumesMidRailThenCrossesByAuthority() {
		final Net n = new Net();
		final MmtrMotionWalker w = MmtrMotionWalker.start(n.sim, n.rIn, new Position(-20, 0, 0), n.branch0(), null);
		w.advance(5); // mid-approach
		assertEquals(n.rIn.getHexId(), w.railHex());
		assertEquals(5, w.offsetM(), 1e-6);
		// Continue 18m: 15m to node0 + 3m onto the elected straight rail.
		w.advance(18);
		assertEquals(false, w.haltedAtAuthority());
		assertEquals(n.rStraight.getHexId(), w.railHex(), "crossed the fork onto the straight branch");
		assertEquals(3, w.offsetM(), 1e-6);
	}

	@Test
	public void walkHaltsAtUnsetForkAwaitingAuthority() {
		final Net n = new Net();
		final MmtrMotionWalker w = MmtrMotionWalker.start(n.sim, n.rIn, new Position(-20, 0, 0), new BranchStore(), null);
		w.advance(25);
		assertEquals(true, w.haltedAtAuthority());
		assertEquals(n.rIn.getHexId(), w.railHex());
		assertEquals(20, w.offsetM(), 1e-6, "stopped at the far end of the approach rail, before the unset fork");
	}

	@Test
	public void walkFollowsDivergeWhenFlipped() {
		final Net n = new Net();
		final MmtrMotionWalker w = MmtrMotionWalker.start(n.sim, n.rIn, new Position(-20, 0, 0), n.branch1(), null);
		w.advance(22); // 20m approach + 2m onto the diverging rail
		assertEquals(false, w.haltedAtAuthority());
		assertEquals(n.rDiverge.getHexId(), w.railHex(), "flipped turnout -> train crossed onto the diverging branch");
		assertEquals(2, w.offsetM(), 1e-6);
	}

	@Test
	public void walkStopsWhenTargetRailBoarded() {
		final Net n = new Net();
		final MmtrMotionWalker w = MmtrMotionWalker.start(n.sim, n.rIn, new Position(-20, 0, 0), n.branch0(), n.rBeyondA.getHexId());
		w.advance(45);
		assertEquals(true, w.atTarget());
		assertEquals(n.rBeyondA.getHexId(), w.railHex());
		assertEquals(0, w.offsetM(), 1e-6);
	}


	// --- MmtrMotionDriver: Motion Core actually DRIVES a consist forward over ticks ---

	@Test
	public void driverDrivesAcrossForkOntoStraightBranch() {
		final Net n = new Net();
		final MmtrMotionDriver d = MmtrMotionDriver.start(n.sim, n.rIn, new Position(-20, 0, 0), n.branch0(), null);
		final boolean rest = d.driveToRest(1000, 0.004, 60); // ~4 m/s
		assertEquals(true, rest, "driver should come to rest at end of line");
		assertEquals(n.rBeyondA.getHexId(), d.walker.railHex(), "consist drove through the fork onto the straight branch");
		assertEquals(false, d.haltedAtAuthority());
	}

	@Test
	public void driverDrivesOntoDivergeWhenFlipped() {
		final Net n = new Net();
		final MmtrMotionDriver d = MmtrMotionDriver.start(n.sim, n.rIn, new Position(-20, 0, 0), n.branch1(), null);
		final boolean rest = d.driveToRest(1000, 0.004, 60);
		assertEquals(true, rest);
		assertEquals(n.rBeyondB.getHexId(), d.walker.railHex(), "flipped turnout -> driven consist crossed onto the diverging branch");
	}

	@Test
	public void driverStopsAtUnsetForkAwaitingAuthority() {
		final Net n = new Net();
		final MmtrMotionDriver d = MmtrMotionDriver.start(n.sim, n.rIn, new Position(-20, 0, 0), new BranchStore(), null);
		final boolean rest = d.driveToRest(1000, 0.004, 60);
		assertEquals(true, rest);
		assertEquals(true, d.haltedAtAuthority());
		assertEquals(n.rIn.getHexId(), d.walker.railHex());
		assertEquals(20, d.walker.offsetM(), 1e-6, "stopped at the far end of approach, before the unset fork");
	}

	@Test
	public void driverStopsWhenTargetRailBoarded() {
		final Net n = new Net();
		final MmtrMotionDriver d = MmtrMotionDriver.start(n.sim, n.rIn, new Position(-20, 0, 0), n.branch0(), n.rBeyondA.getHexId());
		final boolean rest = d.driveToRest(1000, 0.004, 60);
		assertEquals(true, rest);
		assertEquals(true, d.atTarget());
		assertEquals(n.rBeyondA.getHexId(), d.walker.railHex());
	}


	@Test
	public void vehiclePathComesFromMotionCoreLegs() {
		final Net n = new Net();
		final MmtrMotionDriver d = MmtrMotionDriver.start(n.sim, n.rIn, new Position(-20, 0, 0), n.branch0(), n.rBeyondA.getHexId());
		d.driveToRest(1000, 0.004, 60);
		assertEquals(true, d.atTarget());
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<org.mtr.core.data.PathData> legs = d.walker.buildLegs();
		assertEquals(3, legs.size(), "via + straight + beyondA legs");

		// A real Vehicle's path carrier can be built straight from the Motion Core legs.
		final org.mtr.core.data.VehicleExtraData ved = org.mtr.core.data.VehicleExtraData.createWithLegs(
			1L, n.node0.getX(), 10, new it.unimi.dsi.fastutil.objects.ObjectArrayList<org.mtr.core.data.VehicleCar>(),
			legs, 0.0008, 0.0008, true, 20, 30000L);
		assertEquals(3, ved.immutablePath.size(), "Vehicle path == Motion Core legs count");
		double prev = -1;
		for (final org.mtr.core.data.PathData pd : ved.immutablePath) {
			assertEquals(true, pd.getEndDistance() > prev, "cumulative distance must increase");
			prev = pd.getEndDistance();
		}
		assertEquals(legs.get(legs.size() - 1).getEndDistance(), ved.immutablePath.get(ved.immutablePath.size() - 1).getEndDistance(), 1e-6);
	}

	// --- 自由开: a spawned train drives freely; at an unset fork it waits for the operator, then continues

	@Test
	public void generatedTrainFreelyDrivesAndOperatorDecidesAtFork() {
		final Net n = new Net();
		final BranchStore store = new BranchStore(); // shared, mutable: operator decides live
		final MmtrMotionDriver d = MmtrMotionDriver.start(n.sim, n.rIn, new Position(-20, 0, 0), store, null);
		// Drive freely: reaches the fork, nobody set it -> halts awaiting the operator (随便开, 到岔口你定).
		final boolean rest1 = d.driveToRest(1000, 0.004, 60);
		assertEquals(true, rest1);
		assertEquals(true, d.haltedAtAuthority(), "free train halts at the unset fork, waiting for the operator");
		assertEquals(20, d.walker.offsetM(), 1e-6);

		// Operator now sets the branch (搬 0 走直) -> the same train continues freely.
		store.set(n.node0.getX(), n.node0.getY(), n.node0.getZ(), n.rIn.getHexId(), 0);
		final boolean rest2 = d.driveToRest(1000, 0.004, 60);
		assertEquals(true, rest2);
		assertEquals(false, d.haltedAtAuthority(), "once the operator decides, the train proceeds");
		assertEquals(n.rBeyondA.getHexId(), d.walker.railHex(), "train crossed the fork onto the straight branch after the operator decided");
	}

	// --- 手动开: a person throttles/brakes the train; it advances by Motion Core and decides forks live

	@Test
	public void manualThrottleDrivesTrainAcrossFork() {
		final Net n = new Net();
		final MmtrMotionDriver d = MmtrMotionDriver.start(n.sim, n.rIn, new Position(-20, 0, 0), n.branch0(), null);
		for (int i = 0; i < 400 && !d.stopped(); i++) {
			d.manualTick(100, true, false, 1e-6, 2e-6, 0.004); // ~1 m/s^2 throttle, 4 m/s cap
		}
		assertEquals(true, d.stopped(), "human throttle should drive the train to the end of the line");
		assertEquals(n.rBeyondA.getHexId(), d.walker.railHex(), "manual drive crossed the fork onto the straight branch");
		assertEquals(false, d.haltedAtAuthority());
	}

	@Test
	public void manualDriveStopsAtUnsetForkForDriverToDecide() {
		final Net n = new Net();
		final BranchStore store = new BranchStore();
		final MmtrMotionDriver d = MmtrMotionDriver.start(n.sim, n.rIn, new Position(-20, 0, 0), store, null);
		for (int i = 0; i < 400 && !d.stopped(); i++) {
			d.manualTick(100, true, false, 1e-6, 2e-6, 0.004);
		}
		assertEquals(true, d.haltedAtAuthority(), "manual train halts at the unset fork (自由开), driver decides next");
		assertEquals(20, d.walker.offsetM(), 1e-6);

		// The driver sets branch 0 and keeps driving the same train manually (drive regardless of rest).
		store.set(n.node0.getX(), n.node0.getY(), n.node0.getZ(), n.rIn.getHexId(), 0);
		for (int i = 0; i < 400; i++) {
			d.manualTick(100, true, false, 1e-6, 2e-6, 0.004);
		}
		assertEquals(false, d.haltedAtAuthority(), "after the driver decides, the same train continues");
		assertEquals(n.rBeyondA.getHexId(), d.walker.railHex());
	}

	@Test
	public void existingCabControlDrivesMotionCoreTrain() {
		final Net n = new Net();
		final MmtrMotionDriver d = MmtrMotionDriver.start(n.sim, n.rIn, new Position(-20, 0, 0), n.branch0(), null);
		// The engine's existing drive control (ControlState, as MmtrDriveControl sends it) drives this train.
		final org.mtr.core.mmtr.ControlState throttle = new org.mtr.core.mmtr.ControlState().setThrottleNotch(1);
		for (int i = 0; i < 400 && !d.stopped(); i++) {
			d.applyControl(throttle, 100, 1e-6, 2e-6, 0.004);
		}
		assertEquals(true, d.stopped());
		assertEquals(n.rBeyondA.getHexId(), d.walker.railHex(), "existing ControlState throttle drives the Motion Core train across the fork");
		// Brake control brings a fresh train to a stand before the fork if we never set it? Use emergency brake:
		final MmtrMotionDriver d2 = MmtrMotionDriver.start(n.sim, n.rIn, new Position(-20, 0, 0), new BranchStore(), null);
		for (int i = 0; i < 200 && !d2.stopped(); i++) {
			d2.applyControl(new org.mtr.core.mmtr.ControlState().setBrakeNotch(1), 100, 1e-6, 2e-6, 0.004);
		}
		assertEquals(false, d2.stopped(), "brake-only never moves the train");
	}

	// --- P3: direction-aware runtime forks - T junction (no straight) and X crossing (multi-leg) ---
	// A T junction met from its stem has exactly two continuations (left/right by the approach
	// direction): operator 0/1 elects them deterministically; an unset T halts. An X crossing keeps
	// the straight as leg 0 and exposes the perpendicular rails at full leg indexes (here: 2), so an
	// operator can pick a continuation the legacy top-2 model could never express.

	@Test
	public void walkerTeeJunctionOperator0GoesLeftOperator1GoesRight() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-live-tee"), false);
		final Position node = new Position(0, 0, 0);
		final Rail via = through(new Position(-20, 0, 0), node); // approach along +x into the stem
		final Rail left = railZ(node, new Position(0, 0, 20));  // +z continuation
		final Rail right = railZ(node, new Position(0, 0, -20)); // -z continuation
		sim.rails.add(via);
		sim.rails.add(left);
		sim.rails.add(right);
		sim.sync();

		final BranchStore store0 = new BranchStore();
		store0.set(node.getX(), node.getY(), node.getZ(), via.getHexId(), 0);
		final MmtrMotionWalker w0 = MmtrMotionWalker.start(sim, via, new Position(-20, 0, 0), store0, null);
		w0.advance(25); // 20m approach + 5m onto the elected continuation
		assertEquals(false, w0.haltedAtAuthority());
		assertEquals(left.getHexId(), w0.railHex(), "operator 0 at a T = the left continuation, deterministically");
		assertEquals(5, w0.offsetM(), 1e-6);

		final BranchStore store1 = new BranchStore();
		store1.set(node.getX(), node.getY(), node.getZ(), via.getHexId(), 1);
		final MmtrMotionWalker w1 = MmtrMotionWalker.start(sim, via, new Position(-20, 0, 0), store1, null);
		w1.advance(25);
		assertEquals(right.getHexId(), w1.railHex(), "operator 1 at a T = the right continuation");

		// Unset T: halt at the stem node, never auto-pick between equal-cosine legs.
		final MmtrMotionWalker wu = MmtrMotionWalker.start(sim, via, new Position(-20, 0, 0), new BranchStore(), null);
		wu.advance(25);
		assertEquals(true, wu.haltedAtAuthority(), "unset T junction halts the train");
		assertEquals(via.getHexId(), wu.railHex());
		assertEquals(20, wu.offsetM(), 1e-6, "stopped exactly at the stem node");

		// P3 manual-first: an operator setting beats even a conflicting task target...
		final BranchStore stale = new BranchStore();
		stale.set(node.getX(), node.getY(), node.getZ(), via.getHexId(), 0);
		final MmtrMotionWalker wt = MmtrMotionWalker.start(sim, via, new Position(-20, 0, 0), stale, right.getHexId());
		wt.advance(25);
		assertEquals(left.getHexId(), wt.railHex(), "manual operator outranks a conflicting task target at the T junction");

		// ...and the legacy target still steers when no operator has set the fork.
		final MmtrMotionWalker wt2 = MmtrMotionWalker.start(sim, via, new Position(-20, 0, 0), new BranchStore(), right.getHexId());
		wt2.advance(25);
		assertEquals(right.getHexId(), wt2.railHex(), "task target elects the right continuation when the fork is unset");
	}

	@Test
	public void walkerXCrossingElectsLegIndexBeyondLegacyBinary() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-live-x"), false);
		final Position node = new Position(0, 0, 0);
		final Rail via = through(new Position(-20, 0, 0), node);   // approach from -x
		final Rail straight = through(node, new Position(20, 0, 0)); // east continuation
		final Rail north = railZ(new Position(0, 0, -20), node);   // -z arm
		final Rail south = railZ(node, new Position(0, 0, 20));    // +z arm
		sim.rails.add(via);
		sim.rails.add(straight);
		sim.rails.add(north);
		sim.rails.add(south);
		sim.sync();
		for (final org.mtr.core.mmtr.point.MmtrPoint.MmtrPointLeg leg : org.mtr.core.mmtr.point.MmtrPoint.computeOrderedLegs(node, new Position(-20, 0, 0), via, sim.positionsToRail.get(node))) {
			System.out.println("[MMTR-PROBE] leg " + leg.kind + " " + leg.railHex + " cos=" + leg.cos);
		}

		// Legs from the -x approach: [straight(east), left(+z), right(-z)] - straight stays 0,
		// and the perpendicular arms are expressible at full indexes 1..2.
		final BranchStore store2 = new BranchStore();
		store2.set(node.getX(), node.getY(), node.getZ(), via.getHexId(), 2);
		final MmtrMotionWalker w2 = MmtrMotionWalker.start(sim, via, new Position(-20, 0, 0), store2, null);
		w2.advance(25);
		assertEquals(false, w2.haltedAtAuthority());
		assertEquals(north.getHexId(), w2.railHex(), "operator 2 elects the third ordered leg across the crossing");

		final BranchStore store1 = new BranchStore();
		store1.set(node.getX(), node.getY(), node.getZ(), via.getHexId(), 1);
		final MmtrMotionWalker w1 = MmtrMotionWalker.start(sim, via, new Position(-20, 0, 0), store1, null);
		w1.advance(25);
		assertEquals(south.getHexId(), w1.railHex(), "operator 1 elects the left perpendicular");

		final BranchStore store0 = new BranchStore();
		store0.set(node.getX(), node.getY(), node.getZ(), via.getHexId(), 0);
		final MmtrMotionWalker w0 = MmtrMotionWalker.start(sim, via, new Position(-20, 0, 0), store0, null);
		w0.advance(25);
		assertEquals(straight.getHexId(), w0.railHex(), "operator 0 still means the straight at a crossing");

		// An out-of-range operator index (3 with three legs) is refused: halt, do not fall back.
		final BranchStore store3 = new BranchStore();
		store3.set(node.getX(), node.getY(), node.getZ(), via.getHexId(), 3);
		final MmtrMotionWalker w3 = MmtrMotionWalker.start(sim, via, new Position(-20, 0, 0), store3, null);
		w3.advance(25);
		assertEquals(true, w3.haltedAtAuthority(), "stale out-of-range operator index never auto-elects");
		assertEquals(via.getHexId(), w3.railHex());
		assertEquals(20, w3.offsetM(), 1e-6);
	}
}