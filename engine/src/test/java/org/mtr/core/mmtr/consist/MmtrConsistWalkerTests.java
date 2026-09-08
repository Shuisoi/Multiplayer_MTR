package org.mtr.core.mmtr.consist;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.mmtr.consist.MmtrCabState.Cab;
import org.mtr.core.mmtr.consist.MmtrConsistBody.OccupiedSegment;
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
 * B3: the double-ended walker on a real (synthetic) rail graph. The headline case is
 * {@link #changeEndsMovesNothing()}: 换端 flips the manned cab and must leave every car, the
 * occupancy and the spine exactly where they were — the old walker turned the whole train 180°
 * because it had no way to express that.
 */
public final class MmtrConsistWalkerTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static Rail diverge(Position node, Position far) {
		return Rail.newRail(node, Angle.fromAngle(45), far, Angle.fromAngle(225), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/** A straight 3-rail line: nA -r0- nB -r1- nC -r2- nD. */
	private static final class Line {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-consist-walker-line"), false);
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

		double l2() { return r2.railMath.getLength(); }
	}

	/** Yard fork: nIn -rIn- node0 -{rStraight | rDiverge}. */
	private static final class Fork {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-consist-walker-fork"), false);
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

	private static MmtrConsistWalker placedOnLine(Line line, double aEndOffsetM, double[] cars) {
		final MmtrConsistWalker walker = MmtrConsistWalker.place(line.sim, new BranchStore(), line.r0, line.nA, aEndOffsetM, cars, null);
		assertNotNull(walker, "placement must succeed on a straight line");
		return walker;
	}

	@Test
	public void placementExtendsTheSpineOverAsManyRailsAsTheBodyNeeds() {
		final Line line = new Line();
		final MmtrConsistWalker walker = placedOnLine(line, line.l0() - 6, new double[]{8, 8});
		assertEquals(2, walker.body().legCount(), "16 m body starting 6 m before the node spans two rails");
		assertEquals(line.l0() + line.l1(), walker.body().spineLengthM(), 1e-6);
		assertEquals(line.l0() - 6, walker.body().aEndArcM(), 1e-6);
		assertEquals(line.l0() + 10, walker.body().bEndArcM(), 1e-6);
		assertEquals(line.l0() - 2, walker.body().carCenterArcM(0), 1e-6);
		assertEquals(line.l0() + 6, walker.body().carCenterArcM(1), 1e-6);
		final ObjectArrayList<OccupiedSegment> occupancy = walker.occupancy();
		assertEquals(2, occupancy.size());
		assertEquals(line.r0.getHexId(), occupancy.get(0).railHex());
		assertEquals(line.r1.getHexId(), occupancy.get(1).railHex());
	}

	@Test
	public void advanceSlidesTheWholeBodyAndGrowsTheSpineAhead() {
		final Line line = new Line();
		final MmtrConsistWalker walker = placedOnLine(line, line.l0() - 6, new double[]{8, 8});
		assertTrue(walker.insertKey(Cab.CAB_B, true, true), "the driver takes the B-end cab: travel toward the B end");
		assertTrue(walker.advance(15));
		assertEquals(15, walker.distanceM(), 1e-6);
		// The body needed r2 before it could finish the run, and r0 is now fully behind it: trimmed.
		assertEquals(2, walker.body().legCount(), "trimming keeps the spine bounded");
		assertEquals(9, walker.body().aEndArcM(), 1e-6);
		assertEquals(25, walker.body().bEndArcM(), 1e-6);
		assertEquals(13, walker.body().carCenterArcM(0), 1e-6);
		assertEquals(21, walker.body().carCenterArcM(1), 1e-6);
		final ObjectArrayList<OccupiedSegment> occupancy = walker.occupancy();
		assertEquals(2, occupancy.size());
		assertEquals(9, occupancy.get(0).fromM(), 1e-6);
		assertEquals(20, occupancy.get(0).toM(), 1e-6);
		assertEquals(0, occupancy.get(1).fromM(), 1e-6);
		assertEquals(5, occupancy.get(1).toM(), 1e-6);
		assertEquals(line.r2.getHexId(), occupancy.get(1).railHex());
	}

	@Test
	public void changeEndsMovesNothing() {
		final Line line = new Line();
		final MmtrConsistWalker walker = placedOnLine(line, line.l0() - 6, new double[]{8, 8});
		walker.insertKey(Cab.CAB_B, true, true);
		walker.advance(15);
		final double aEnd = walker.body().aEndArcM();
		final double bEnd = walker.body().bEndArcM();
		final double[] carCentres = walker.body().carCenterArcMs();
		final int legs = walker.body().legCount();
		final ObjectArrayList<OccupiedSegment> occupancy = walker.occupancy();
		final double distance = walker.distanceM();
		assertEquals(MmtrCabState.End.B, walker.cabs().leadingEnd(), "the B-end cab leads with the B end");

		assertTrue(walker.changeEnds(true), "the crew changes ends while the train stands");

		assertEquals(MmtrCabState.End.A, walker.cabs().leadingEnd(), "the other cab now leads");
		assertEquals(aEnd, walker.body().aEndArcM(), 1e-9, "I1: the A end did not move");
		assertEquals(bEnd, walker.body().bEndArcM(), 1e-9, "I1: the B end did not move");
		assertEquals(carCentres[0], walker.body().carCenterArcM(0), 1e-9, "I1: car 0 did not move");
		assertEquals(carCentres[1], walker.body().carCenterArcM(1), 1e-9, "I1: car 1 did not move");
		assertEquals(legs, walker.body().legCount(), "I2: the spine is untouched");
		assertEquals(distance, walker.distanceM(), 1e-9);
		final ObjectArrayList<OccupiedSegment> after = walker.occupancy();
		assertEquals(occupancy.size(), after.size());
		for (int i = 0; i < after.size(); i++) {
			assertEquals(occupancy.get(i).railHex(), after.get(i).railHex());
			assertEquals(occupancy.get(i).fromM(), after.get(i).fromM(), 1e-9);
			assertEquals(occupancy.get(i).toM(), after.get(i).toM(), 1e-9);
		}
	}

	@Test
	public void afterChangeEndsTheConsistRunsTheOtherWayAndDistanceStillGrows() {
		final Line line = new Line();
		final MmtrConsistWalker walker = placedOnLine(line, line.l0() - 6, new double[]{8, 8});
		walker.insertKey(Cab.CAB_B, true, true);
		walker.advance(15);
		final double distanceBefore = walker.distanceM();
		assertTrue(walker.changeEnds(true));
		assertTrue(walker.advance(5));
		assertEquals(distanceBefore + 5, walker.distanceM(), 1e-6, "I3: distance is cumulative, never negative");
		assertEquals(4, walker.body().aEndArcM(), 1e-6, "the A end now leads and moves toward the A side");
		assertEquals(20, walker.body().bEndArcM(), 1e-6);
		assertEquals(8, walker.body().carCenterArcM(0), 1e-6);
		assertEquals(16, walker.body().carCenterArcM(1), 1e-6);
	}

	@Test
	public void changeEndsRequiresAStandingTrain() {
		final Line line = new Line();
		final MmtrConsistWalker walker = placedOnLine(line, 2, new double[]{8, 8});
		assertFalse(walker.changeEnds(true), "unmanned: nobody to change ends");
		walker.insertKey(Cab.CAB_A, true, true);
		assertFalse(walker.changeEnds(false), "the driver cannot walk through a moving train");
		assertEquals(Cab.CAB_A, walker.cabs().activeCab());
	}

	@Test
	public void anUnmannedConsistNeverMoves() {
		final Line line = new Line();
		final MmtrConsistWalker walker = placedOnLine(line, 2, new double[]{8, 8});
		assertFalse(walker.advance(10), "no key, no movement");
		assertEquals(0, walker.distanceM(), 1e-9);
		assertEquals(2, walker.body().aEndArcM(), 1e-9);
		assertNull(walker.leadingRailHex(), "an unmanned consist has no leading end");
	}

	@Test
	public void anUnsetForkHaltsAtTheNodeAndAnOperatorBranchReleasesIt() {
		final Fork fork = new Fork();
		final BranchStore store = new BranchStore();
		final MmtrConsistWalker walker = MmtrConsistWalker.place(fork.sim, store, fork.rIn, fork.nIn, 2, new double[]{4}, null);
		assertNotNull(walker);
		walker.insertKey(Cab.CAB_B, true, true);
		assertTrue(walker.advance(1000), "it moves up to the fork");
		assertTrue(walker.haltedAtAuthority(), "an unset fork is never auto-elected");
		assertFalse(walker.endOfLine());
		assertEquals(fork.lIn() - 6, walker.distanceM(), 1e-6, "it consumed exactly the run up to the node");
		assertEquals(fork.lIn(), walker.body().bEndArcM(), 1e-6, "the leading face rests on the node");
		// The operator throws the points: branch 0 = the straight leg.
		store.set(fork.node0.getX(), fork.node0.getY(), fork.node0.getZ(), fork.rIn.getHexId(), 0);
		assertTrue(walker.advance(5), "the same train now continues through the fork");
		assertFalse(walker.haltedAtAuthority());
		assertEquals(fork.rStraight.getHexId(), walker.leadingRailHex());
	}

	@Test
	public void aDeadEndSetsEndOfLineAndStopsConsumption() {
		final Line line = new Line();
		final MmtrConsistWalker walker = MmtrConsistWalker.place(line.sim, new BranchStore(), line.r2, line.nC, line.l2() - 8, new double[]{4}, null);
		assertNotNull(walker);
		walker.insertKey(Cab.CAB_B, true, true);
		assertTrue(walker.advance(1000));
		assertTrue(walker.endOfLine(), "r2 ends at nD with nothing beyond");
		assertEquals(4, walker.distanceM(), 1e-6, "it consumed the 4 m left before the buffer");
		assertEquals(line.l2(), walker.body().bEndArcM(), 1e-6);
	}

	@Test
	public void changeEndsAtTheDeadEndRunsBackOnTheSameRail() {
		final Line line = new Line();
		final MmtrConsistWalker walker = MmtrConsistWalker.place(line.sim, new BranchStore(), line.r2, line.nC, line.l2() - 8, new double[]{4}, null);
		assertNotNull(walker);
		assertTrue(walker.insertKey(Cab.CAB_B, true, true), "the B-end cab leads toward the buffer");
		assertTrue(walker.advance(1000));
		assertTrue(walker.endOfLine(), "the consist rests against the buffer");
		assertEquals(4, walker.distanceM(), 1e-6);

		// 换端 at the terminal: the crew changes cabs and the same consist runs back out.
		assertTrue(walker.changeEnds(true));
		assertTrue(walker.advance(6), "the other cab drives the consist back");
		assertEquals(10, walker.distanceM(), 1e-6, "distance keeps accumulating, never reverses");
		assertEquals(line.r2.getHexId(), walker.leadingRailHex(), "still on the terminal rail");
		assertEquals(line.l2() - 10, walker.body().aEndArcM(), 1e-6, "the A end has run back 6 m from the buffer-resting position");
		assertEquals(1, walker.occupancy().size());
	}

	@Test
	public void placementRefusesWhenTheBodyNeedsAnUnsetFork() {
		final Fork fork = new Fork();
		// A 16 m body starting 6 m before the node cannot be placed: the fork ahead has no authority.
		assertNull(MmtrConsistWalker.place(fork.sim, new BranchStore(), fork.rIn, fork.nIn, fork.lIn() - 6, new double[]{8, 8}, null));
	}

	@Test
	public void placementRefusesWhenTheBodyDoesNotFitBeforeADeadEnd() {
		final Line line = new Line();
		// r2 is the last rail: a 16 m body cannot fit in its 4 remaining metres.
		assertNull(MmtrConsistWalker.place(line.sim, new BranchStore(), line.r2, line.nC, line.l2() - 4, new double[]{8, 8}, null));
	}
}
