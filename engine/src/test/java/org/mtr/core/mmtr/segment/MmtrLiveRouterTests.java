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
}
