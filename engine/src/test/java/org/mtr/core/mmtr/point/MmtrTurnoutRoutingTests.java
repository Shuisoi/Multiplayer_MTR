package org.mtr.core.mmtr.point;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrNodeRouter;
import org.mtr.core.mmtr.segment.MmtrNodeRouter.Continuation;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Slice B/C at the real rail-graph level: on a synthetic two-branch fork the operator branch
 * (0 = straight, 1 = diverging) decides which rail a train actually continues onto ("搬A走A /
 * 搬B走B"), and a fork with no authority is refused while a single-forward node is not an
 * authority question. Uses actual discovered turnouts over real {@link Rail}s (not fabricated
 * strings), so this is the runtime seam MmtrNodeRouter plugs into for slice A free-drive.
 */
public final class MmtrTurnoutRoutingTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static Rail diverge(Position node, Position far) {
		// 45 deg departure on the diverging leg.
		return Rail.newRail(node, Angle.fromAngle(45), far, Angle.fromAngle(225), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	@Test
	public void realForkFlipChangesElectedRail() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-fork"), false);

		final Position posIn = new Position(-20, 0, 0);
		final Position node = new Position(0, 0, 0);
		final Position posStraight = new Position(20, 0, 0);
		final Position posDiverge = new Position(20, 0, 12);

		final Rail approach = through(posIn, node);
		final Rail straight = through(node, posStraight);
		final Rail diverging = diverge(node, posDiverge);

		sim.rails.add(approach);
		sim.rails.add(straight);
		sim.rails.add(diverging);
		sim.sync();

		final ObjectArrayList<MmtrSwitch> switches = MmtrPointRegistry.discover(sim);
		MmtrSwitch found = null;
		for (final MmtrSwitch sw : switches) {
			if (sw.nodeX == node.getX() && sw.nodeY == node.getY() && sw.nodeZ == node.getZ() && sw.viaRailHex.equals(approach.getHexId())) {
				found = sw;
				break;
			}
		}
		assertNotNull(found, "the (node, approach) turnout should be discovered");
		assertEquals(straight.getHexId(), found.branch0Hex, "straightest continuation = branch0");
		assertEquals(diverging.getHexId(), found.branch1Hex, "diverging continuation = branch1");

		final Continuation fork = new Continuation(found.branch0Hex, found.branch1Hex);

		// Unset fork + no task: the train must stop and await an operator/task (never auto).
		assertNull(MmtrNodeRouter.elect(fork, null, null), "no authority -> must not auto-pick a branch");

		// Flip: branch 0 -> straight ("搬A走A"), branch 1 -> diverging ("搬B走B").
		assertEquals(straight.getHexId(), MmtrNodeRouter.elect(fork, 0, null));
		assertEquals(diverging.getHexId(), MmtrNodeRouter.elect(fork, 1, null));

		// A live task naming the diverging rail overrides a stale operator branch 0.
		assertEquals(diverging.getHexId(), MmtrNodeRouter.elect(fork, 0, diverging.getHexId()));

		// Operator-set persisted store is honoured through the BranchStore overload.
		final BranchStore store = new BranchStore();
		store.set(node.getX(), node.getY(), node.getZ(), approach.getHexId(), 1);
		assertEquals(diverging.getHexId(), MmtrNodeRouter.electFromStore(fork, store, node.getX(), node.getY(), node.getZ(), approach.getHexId(), null));
		store.set(node.getX(), node.getY(), node.getZ(), approach.getHexId(), 0);
		assertEquals(straight.getHexId(), MmtrNodeRouter.electFromStore(fork, store, node.getX(), node.getY(), node.getZ(), approach.getHexId(), null));
	}

	@Test
	public void singleForwardNodeIsNotAnAuthorityQuestion() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-straight"), false);
		final Position posIn = new Position(-20, 0, 0);
		final Position node = new Position(0, 0, 0);
		final Position posOut = new Position(20, 0, 0);

		final Rail via = through(posIn, node);
		final Rail out = through(node, posOut);
		sim.rails.add(via);
		sim.rails.add(out);
		sim.sync();

		// Only two rails meet at the node -> no switch is discovered, and routing must not demand
		// authority for an ordinary through move: single forward continuation just carries on.
		final ObjectArrayList<MmtrSwitch> switches = MmtrPointRegistry.discover(sim);
		boolean anyAtNode = switches.stream().anyMatch(s -> s.nodeX == 0 && s.nodeY == 0 && s.nodeZ == 0);
		assertEquals(false, anyAtNode, "no fork at a through node");
		assertEquals(out.getHexId(), MmtrNodeRouter.elect(Continuation.single(out.getHexId()), null, null), "straight-through needs no authority");
	}
}
