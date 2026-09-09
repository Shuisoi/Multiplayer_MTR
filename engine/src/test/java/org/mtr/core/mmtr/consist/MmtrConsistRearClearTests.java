package org.mtr.core.mmtr.consist;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.mmtr.consist.MmtrCabState.Cab;
import org.mtr.core.mmtr.point.MmtrPointAuthority;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B5: rear-clear. A turnout hold belongs to the <em>train</em>, so it must survive until the
 * consist's tail has left the point — the old walker released on the head crossing, which frees the
 * point while the train's own rear is still standing on it.
 */
public final class MmtrConsistRearClearTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String OWNER = "train-1";
	private static final long WINDOW = 100_000L;

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static Rail diverge(Position node, Position far) {
		return Rail.newRail(node, Angle.fromAngle(45), far, Angle.fromAngle(225), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static final class Fork {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-consist-rearclear"), false);
		final Position nIn = new Position(-40, 0, 0);
		final Position node0 = new Position(-20, 0, 0);
		final Rail rIn = through(nIn, node0);
		final Rail rStraight = through(node0, new Position(0, 0, 0));
		final Rail rDiverge = diverge(node0, new Position(0, 0, 12));

		Fork() {
			sim.rails.add(rIn);
			sim.rails.add(rStraight);
			sim.rails.add(rDiverge);
			sim.sync();
		}

		double lIn() { return rIn.railMath.getLength(); }

		MmtrConsistWalker walkerWithAuthority(MmtrPointAuthority authority) {
			final MmtrConsistWalker walker = MmtrConsistWalker.place(sim, new BranchStore(), rIn, nIn, lIn() - 8, new double[]{6}, null);
			assertNotNull(walker, "a 6 m consist fits on rIn with its front 2 m before the fork");
			assertTrue(walker.insertKey(Cab.CAB_B, true, true));
			walker.setPointAuthority(authority, OWNER);
			return walker;
		}
	}

	@Test
	public void theHoldSurvivesUntilTheTailHasClearedThePoint() {
		final Fork fork = new Fork();
		final MmtrPointAuthority authority = new MmtrPointAuthority(() -> 0L);
		final MmtrConsistWalker walker = fork.walkerWithAuthority(authority);
		assertEquals(MmtrPointAuthority.Result.GRANTED, authority.request(fork.node0.getX(), fork.node0.getY(), fork.node0.getZ(), fork.rIn.getHexId(), OWNER, 0, WINDOW));

		// Front crosses the node onto the granted leg; the rear is still 5 m short of the point.
		assertTrue(walker.advance(3));
		assertEquals(2, walker.body().legCount(), "the spine grew through the point");
		assertEquals(fork.rStraight.getHexId(), walker.body().leg(1).railHex(), "the grant was honoured: the straight leg was elected");
		assertEquals(1, walker.pendingReleaseCount());
		assertTrue(authority.isGrantedTo(fork.node0.getX(), fork.node0.getY(), fork.node0.getZ(), fork.rIn.getHexId(), OWNER), "head crossed, tail has not");

		// Travel on: the tail is still short of the node.
		assertTrue(walker.advance(4.9));
		assertTrue(authority.isGrantedTo(fork.node0.getX(), fork.node0.getY(), fork.node0.getZ(), fork.rIn.getHexId(), OWNER), "the tail is still on the point");

		// One more push: the tail clears the node, but ② 岔区清限 keeps the point held while any part of
		// the consist is still inside the junction's clearance zone (10 m past the node).
		assertTrue(walker.advance(0.2));
		assertTrue(authority.isGrantedTo(fork.node0.getX(), fork.node0.getY(), fork.node0.getZ(), fork.rIn.getHexId(), OWNER), "the tail cleared the node but is still inside the clearance zone");
		assertEquals(1, walker.pendingReleaseCount());

		// Traveling the clearance margin releases it.
		assertTrue(walker.advance(org.mtr.core.data.Vehicle.MMTR_JUNCTION_CLEARANCE_M));
		assertFalse(authority.isGrantedTo(fork.node0.getX(), fork.node0.getY(), fork.node0.getZ(), fork.rIn.getHexId(), OWNER), "clearance-clear released the point");
		assertEquals(0, walker.pendingReleaseCount());
	}

	@Test
	public void aGrantAtTheDivergingLegTakesTheDivergingRail() {
		final Fork fork = new Fork();
		final MmtrPointAuthority authority = new MmtrPointAuthority(() -> 0L);
		final MmtrConsistWalker walker = fork.walkerWithAuthority(authority);
		assertEquals(MmtrPointAuthority.Result.GRANTED, authority.request(fork.node0.getX(), fork.node0.getY(), fork.node0.getZ(), fork.rIn.getHexId(), OWNER, 1, WINDOW));
		assertTrue(walker.advance(3));
		assertEquals(fork.rDiverge.getHexId(), walker.leadingRailHex(), "leg 1 is the diverging rail");
	}

	@Test
	public void aWiredWalkerStillNeverAutoElectsAnUnrequestedFork() {
		final Fork fork = new Fork();
		final MmtrPointAuthority authority = new MmtrPointAuthority(() -> 0L);
		final MmtrConsistWalker walker = fork.walkerWithAuthority(authority);
		assertTrue(walker.advance(100));
		assertTrue(walker.haltedAtAuthority(), "no request, no grant: the consist waits at the points");
		assertEquals(2, walker.distanceM(), 1e-6, "it consumed exactly the 2 m between its front and the node");
		assertEquals(0, walker.pendingReleaseCount(), "nothing was crossed, so nothing is pending");
	}
}
