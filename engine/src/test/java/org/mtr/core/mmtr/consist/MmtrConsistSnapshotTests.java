package org.mtr.core.mmtr.consist;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.mmtr.MmtrMotionSnapshot;
import org.mtr.core.mmtr.consist.MmtrCabState.Cab;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B4: the client-facing data plane for a consist body. The old snapshot reported a single point and
 * hard-coded {@code segmentReversed = false}, so 换端 reversed the synced path and the rendered train
 * span 180°. These tests pin the replacement contract: both physical ends are reported, the manned
 * cab is reported separately, and 换端 only swaps the two labels.
 */
public final class MmtrConsistSnapshotTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static final class Line {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-consist-snapshot"), false);
		final Position nA = new Position(-40, 0, 0);
		final Position nB = new Position(-20, 0, 0);
		final Position nC = new Position(0, 0, 0);
		final Rail r0 = through(nA, nB);
		final Rail r1 = through(nB, nC);

		Line() {
			sim.rails.add(r0);
			sim.rails.add(r1);
			sim.sync();
		}

		double l0() { return r0.railMath.getLength(); }
	}

	private static final class Rig {
		final Line line = new Line();
		final MmtrConsistWalker walker;

		Rig() {
			walker = MmtrConsistWalker.place(line.sim, new BranchStore(), line.r0, line.nA, line.l0() - 6, new double[]{8, 8}, null);
			assertNotNull(walker);
		}
	}

	private static MmtrConsistWalker walker() {
		return new Rig().walker;
	}

	@Test
	public void snapshotCarriesBothEndsAndTheMannedCab() {
		final Rig rig = new Rig();
		rig.walker.insertKey(Cab.CAB_A, true, true);
		final MmtrMotionSnapshot snapshot = MmtrMotionSnapshot.ofConsistWalker(rig.walker);
		// A end 14 m into r0, B end 10 m into r1 (the body spans both rails).
		final double expectedRearX = rig.line.r0.railMath.getPosition(rig.line.l0() - 6, false).x();
		final double expectedFrontX = rig.line.r1.railMath.getPosition(10, false).x();
		assertEquals(expectedRearX, snapshot.rearX, 1e-6, "rear end sits on r0");
		assertEquals(expectedFrontX, snapshot.frontX, 1e-6, "the manned A-end cab drives toward the B end, so the B end leads");
		assertEquals("CAB_A", snapshot.activeCab);
		assertTrue(snapshot.cabManned);
		assertTrue(snapshot.moving);
		assertEquals(2, snapshot.cars);
		assertEquals(10, snapshot.segmentOffsetM, 1e-6);
		assertFalse(snapshot.segmentReversed, "r1 runs from nB to nC, i.e. with its own direction");
	}

	@Test
	public void changeEndsOnlySwapsTheTwoLabels() {
		final MmtrConsistWalker walker = walker();
		walker.insertKey(Cab.CAB_A, true, true);
		final MmtrMotionSnapshot before = MmtrMotionSnapshot.ofConsistWalker(walker);
		assertTrue(walker.changeEnds(true));
		final MmtrMotionSnapshot after = MmtrMotionSnapshot.ofConsistWalker(walker);
		assertEquals("CAB_B", after.activeCab, "the other cab now drives");
		assertEquals(before.rearX, after.frontX, 1e-9, "the world point that was the rear is now the front");
		assertEquals(before.rearZ, after.frontZ, 1e-9);
		assertEquals(before.frontX, after.rearX, 1e-9, "the world point that was the front is now the rear");
		assertEquals(before.frontZ, after.rearZ, 1e-9);
		assertTrue(after.cabManned);
	}

	@Test
	public void pathPayloadIsStableAcrossChangeEnds() {
		// The mirror payload must not depend on the direction of travel: this is what stops the
		// client from re-rendering the consist backwards after 换端.
		final MmtrConsistWalker walker = walker();
		walker.insertKey(Cab.CAB_A, true, true);
		final ObjectArrayList<PathData> before = walker.buildPathData();
		assertTrue(walker.changeEnds(true));
		final ObjectArrayList<PathData> after = walker.buildPathData();
		assertEquals(before.size(), after.size());
		for (int i = 0; i < before.size(); i++) {
			assertEquals(before.get(i).getRail().getHexId(), after.get(i).getRail().getHexId(), "leg " + i);
			assertEquals(before.get(i).getOrderedPosition1(), after.get(i).getOrderedPosition1(), "leg " + i);
			assertEquals(before.get(i).getOrderedPosition2(), after.get(i).getOrderedPosition2(), "leg " + i);
			assertEquals(before.get(i).getStartDistance(), after.get(i).getStartDistance(), 1e-9, "leg " + i);
		}
	}

	@Test
	public void anUnmannedConsistReportsNoDirection() {
		final MmtrConsistWalker walker = walker();
		final MmtrMotionSnapshot snapshot = MmtrMotionSnapshot.ofConsistWalker(walker);
		assertEquals("NONE", snapshot.activeCab);
		assertFalse(snapshot.cabManned);
		assertFalse(snapshot.moving);
	}
}
