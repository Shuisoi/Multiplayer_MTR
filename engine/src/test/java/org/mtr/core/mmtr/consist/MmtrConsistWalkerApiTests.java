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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B7.1: the accessor surface a Vehicle needs when it runs on a consist body instead of the old
 * single-point walker — current rail/node/offset, side-effect-free look-ahead, and the task-target
 * rest. These mirror {@code MmtrMotionWalker}'s contract so the motion branch can be rewired
 * without changing what the rest of the engine observes.
 */
public final class MmtrConsistWalkerApiTests {

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

	private static final class Line {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-consist-api"), false);
		final Position nA = new Position(-40, 0, 0);
		final Position nB = new Position(-20, 0, 0);
		final Position nC = new Position(0, 0, 0);
		final Position nD = new Position(20, 0, 0);
		final Rail r0 = through(nA, nB);
		final Rail r1 = through(nB, nC);
		final Rail r2 = through(nC, nD);

		Line() {
			sim.rails.add(r0);
			sim.rails.add(r1);
			sim.rails.add(r2);
			sim.sync();
		}

		double l0() { return r0.railMath.getLength(); }
		double l1() { return r1.railMath.getLength(); }
	}

	private static final class Fork {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-consist-api-fork"), false);
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
	}

	private static MmtrConsistWalker onLine(Line line, double aEndOffsetM) {
		final MmtrConsistWalker walker = MmtrConsistWalker.place(line.sim, new BranchStore(), line.r0, line.nA, aEndOffsetM, new double[]{4}, null);
		assertNotNull(walker);
		walker.insertKey(Cab.CAB_B, true, true);
		return walker;
	}

	@Test
	public void peekNextRailPredictsTheElectedContinuation() {
		final Line line = new Line();
		final MmtrConsistWalker walker = onLine(line, 2);
		assertEquals(line.r0.getHexId(), walker.currentRailHex());
		assertEquals(line.r1.getHexId(), walker.peekNextRail().getHexId(), "on r0, the next rail is r1");
		assertTrue(walker.advance(line.l0() - 6 + 1), "run onto r1");
		assertEquals(line.r1.getHexId(), walker.currentRailHex());
		assertEquals(line.r2.getHexId(), walker.peekNextRail().getHexId(), "on r1, the next rail is r2");
	}

	@Test
	public void peekReturnsNullAtAnUnsetForkAndTheAdvanceThenHalts() {
		final Fork fork = new Fork();
		final MmtrConsistWalker walker = MmtrConsistWalker.place(fork.sim, new BranchStore(), fork.rIn, fork.nIn, fork.lIn() - 8, new double[]{4}, null);
		assertNotNull(walker);
		walker.insertKey(Cab.CAB_B, true, true);
		assertNull(walker.peekNextRail(), "an unset fork predicts no continuation");
		assertTrue(walker.advance(100));
		assertTrue(walker.haltedAtAuthority(), "and the real advance halts exactly as predicted");
	}

	@Test
	public void peekHonoursAGrantWithoutConsumingIt() {
		final Fork fork = new Fork();
		final MmtrPointAuthority authority = new MmtrPointAuthority(() -> 0L);
		final MmtrConsistWalker walker = MmtrConsistWalker.place(fork.sim, new BranchStore(), fork.rIn, fork.nIn, fork.lIn() - 8, new double[]{4}, null);
		assertNotNull(walker);
		walker.insertKey(Cab.CAB_B, true, true);
		walker.setPointAuthority(authority, OWNER);
		assertEquals(MmtrPointAuthority.Result.GRANTED, authority.request(fork.node0.getX(), fork.node0.getY(), fork.node0.getZ(), fork.rIn.getHexId(), OWNER, 1, WINDOW));
		assertEquals(fork.rDiverge.getHexId(), walker.peekNextRail().getHexId(), "the look-ahead reads the granted leg");
		assertTrue(authority.isGrantedTo(fork.node0.getX(), fork.node0.getY(), fork.node0.getZ(), fork.rIn.getHexId(), OWNER), "peeking must not consume the grant");
		assertTrue(walker.advance(5));
		assertEquals(fork.rDiverge.getHexId(), walker.body().leg(1).railHex(), "the real run takes the same leg");
	}

	@Test
	public void theRunRestsOnBoardingTheTargetRail() {
		final Line line = new Line();
		final MmtrConsistWalker walker = MmtrConsistWalker.place(line.sim, new BranchStore(), line.r0, line.nA, 2, new double[]{4}, line.r2.getHexId());
		assertNotNull(walker);
		walker.insertKey(Cab.CAB_B, true, true);
		assertFalse(walker.atTarget());
		assertTrue(walker.advance(1000));
		assertTrue(walker.atTarget(), "the run rests once the leading end boards the target rail");
		assertEquals(line.r2.getHexId(), walker.currentRailHex());
		assertEquals(0, walker.offsetM(), 1e-6, "it rests at the target rail's entry");
		assertEquals(line.l0() - 6 + line.l1(), walker.distanceM(), 1e-6);
		assertFalse(walker.advance(10), "an arrived consist does not keep going");
	}

	@Test
	public void retargetingAwayResumesTheRun() {
		final Line line = new Line();
		final MmtrConsistWalker walker = MmtrConsistWalker.place(line.sim, new BranchStore(), line.r0, line.nA, 2, new double[]{4}, line.r2.getHexId());
		assertNotNull(walker);
		walker.insertKey(Cab.CAB_B, true, true);
		assertTrue(walker.advance(1000));
		assertTrue(walker.atTarget());
		final double arrived = walker.distanceM();
		walker.setTargetRailHex(null);
		assertFalse(walker.atTarget(), "clearing the target releases the run");
		assertTrue(walker.advance(5));
		assertEquals(arrived + 5, walker.distanceM(), 1e-6);
	}

	@Test
	public void mirrorLegsRunTailToHeadAndAnchorTheHeadAtDistanceM() {
		final Line line = new Line();
		final MmtrConsistWalker walker = onLine(line, 2);
		walker.advance(1);
		final ObjectArrayList<org.mtr.core.data.PathData> legs = walker.buildMirrorLegs();
		assertFalse(legs.get(0).reversePositions, "CAB_B drives toward B, so the tail (A end) starts the path in rail order");
		final org.mtr.core.data.PathData headLeg = legs.get(legs.size() - 1);
		assertEquals(walker.distanceM(), headLeg.getStartDistance() + walker.frontOffsetM(), 1e-6, "the leading face anchors at railProgress");

		// 换端 re-orders the path (same rails, opposite direction) and keeps the same anchoring.
		assertTrue(walker.changeEnds(true));
		final ObjectArrayList<org.mtr.core.data.PathData> flipped = walker.buildMirrorLegs();
		assertEquals(legs.size(), flipped.size());
		assertTrue(flipped.get(0).reversePositions, "now the A end leads, so the B end is the tail and the path runs against the rails");
		final org.mtr.core.data.PathData flippedHead = flipped.get(flipped.size() - 1);
		assertEquals(walker.distanceM(), flippedHead.getStartDistance() + walker.mirrorHeadOffsetM(), 1e-6);
		assertEquals(walker.body().spineLengthM() - walker.body().aEndArcM(), walker.mirrorHeadArcM(), 1e-6, "with the A end leading, the head arc is measured from the B side");
	}

	@Test
	public void accessorsDescribeTheLeadingLeg() {
		final Line line = new Line();
		final MmtrConsistWalker walker = onLine(line, line.l0() - 6);
		assertEquals(line.r0.getHexId(), walker.currentRailHex());
		assertEquals(line.l0(), walker.currentRailLengthM(), 1e-6);
		assertEquals(line.l0() - 2, walker.offsetM(), 1e-6, "the leading face is the B end: aEnd + car length");
		assertEquals(line.nA, walker.enteredFromPosition());
		assertEquals(line.nB, walker.aheadNode());
	}
}
