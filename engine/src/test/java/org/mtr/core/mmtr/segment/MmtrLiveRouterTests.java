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

}