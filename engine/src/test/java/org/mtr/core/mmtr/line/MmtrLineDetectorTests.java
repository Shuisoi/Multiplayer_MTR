package org.mtr.core.mmtr.line;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Automatic line detection (线路自动识别): deterministic partition of the rail graph into
 * straightest-continuation strokes. Crossings keep through strokes apart, junctions keep mains
 * continuous and diverging branches become their own lines, loops close into one line, and the
 * partition is stable under re-detection.
 */
public final class MmtrLineDetectorTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static Rail railZ(Position p1, Position p2) {
		final boolean plus = p2.getZ() > p1.getZ();
		return Rail.newRail(p1, plus ? Angle.fromAngle(90) : Angle.fromAngle(270), p2, plus ? Angle.fromAngle(270) : Angle.fromAngle(90), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static Simulator sim(String path, Rail... rails) {
		final Simulator simulator = new Simulator("test", new String[]{"test"}, Paths.get(path), false);
		for (final Rail rail : rails) {
			simulator.rails.add(rail);
		}
		simulator.sync();
		return simulator;
	}

	private static MmtrLineDetector.MmtrLine lineContaining(ObjectArrayList<MmtrLineDetector.MmtrLine> lines, Rail rail) {
		for (final MmtrLineDetector.MmtrLine line : lines) {
			if (line.rails.contains(rail.getHexId())) {
				return line;
			}
		}
		return null;
	}

	@Test
	public void crossingKeepsThroughStrokesApart() {
		final Rail west = through(new Position(-20, 0, 0), new Position(0, 0, 0));
		final Rail east = through(new Position(0, 0, 0), new Position(20, 0, 0));
		final Rail north = railZ(new Position(0, 0, -20), new Position(0, 0, 0));
		final Rail south = railZ(new Position(0, 0, 0), new Position(0, 0, 20));
		final Simulator simulator = sim("build/mmtr-line-cross", west, east, north, south);

		final ObjectArrayList<MmtrLineDetector.MmtrLine> lines = MmtrLineDetector.detect(simulator);
		assertEquals(2, lines.size(), "an X crossing is two through strokes, not one blob");
		final MmtrLineDetector.MmtrLine horizontal = lineContaining(lines, west);
		assertTrue(horizontal != null && horizontal.rails.contains(east.getHexId()), "horizontal through stroke keeps both x rails");
		assertTrue(!horizontal.rails.contains(north.getHexId()) && !horizontal.rails.contains(south.getHexId()), "vertical rails are not stolen by the horizontal stroke");
		final MmtrLineDetector.MmtrLine vertical = lineContaining(lines, north);
		assertTrue(vertical != null && vertical.rails.contains(south.getHexId()), "vertical through stroke keeps both z rails");
		assertEquals(4, lines.stream().mapToInt(line -> line.rails.size()).sum(), "every rail belongs to exactly one line");
	}

	@Test
	public void junctionKeepsTheMainContinuousAndBranchesSplitOff() {
		final Position node = new Position(0, 0, 0);
		final Rail approach = through(new Position(-20, 0, 0), node);
		final Rail straight = through(node, new Position(20, 0, 0));
		final Rail diverge1 = through(node, new Position(20, 0, 14));
		final Rail diverge2 = through(new Position(20, 0, 14), new Position(40, 0, 28));
		final Simulator simulator = sim("build/mmtr-line-fork", approach, straight, diverge1, diverge2);

		final ObjectArrayList<MmtrLineDetector.MmtrLine> lines = MmtrLineDetector.detect(simulator);
		final MmtrLineDetector.MmtrLine main = lineContaining(lines, approach);
		assertTrue(main != null && main.rails.contains(straight.getHexId()), "main stroke runs straight through the junction");
		assertTrue(main.rails.size() == 2 && !main.rails.contains(diverge1.getHexId()), "the diverging branch is not part of the main stroke");
		final MmtrLineDetector.MmtrLine branch = lineContaining(lines, diverge1);
		assertTrue(branch != null && branch.rails.contains(diverge2.getHexId()), "diverging branch continues as its own stroke");
		assertEquals(4, lines.stream().mapToInt(line -> line.rails.size()).sum(), "every rail belongs to exactly one line");
	}

	@Test
	public void closedLoopIsOneLine() {
		final Rail top = through(new Position(0, 0, 0), new Position(20, 0, 0));
		final Rail right = railZ(new Position(20, 0, 0), new Position(20, 0, 20));
		final Rail bottom = through(new Position(20, 0, 20), new Position(0, 0, 20));
		final Rail left = railZ(new Position(0, 0, 20), new Position(0, 0, 0));
		final Simulator simulator = sim("build/mmtr-line-loop", top, right, bottom, left);

		final ObjectArrayList<MmtrLineDetector.MmtrLine> lines = MmtrLineDetector.detect(simulator);
		assertEquals(1, lines.size(), "a closed loop is one continuous line");
		assertEquals(4, lines.get(0).rails.size(), "the loop line owns all four rails");
	}

	@Test
	public void detectionIsDeterministicAndExhaustive() {
		final Position node = new Position(0, 0, 0);
		final Rail w1 = through(new Position(-40, 0, 0), new Position(-20, 0, 0));
		final Rail w2 = through(new Position(-20, 0, 0), node);
		final Rail e1 = through(node, new Position(20, 0, 0));
		final Rail e2 = through(new Position(20, 0, 0), new Position(40, 0, 0));
		final Rail d1 = through(node, new Position(20, 0, 16));
		final Rail n1 = railZ(new Position(0, 0, -20), node);
		final Rail s1 = railZ(node, new Position(0, 0, 20));
		final Simulator simulator = sim("build/mmtr-line-det", w1, w2, e1, e2, d1, n1, s1);

		final ObjectArrayList<MmtrLineDetector.MmtrLine> first = MmtrLineDetector.detect(simulator);
		final ObjectArrayList<MmtrLineDetector.MmtrLine> second = MmtrLineDetector.detect(simulator);

		final Set<String> owned = new HashSet<>();
		for (final MmtrLineDetector.MmtrLine line : first) {
			for (final String hex : line.rails) {
				assertTrue(owned.add(hex), "rail appears in exactly one line");
			}
		}
		assertEquals(7, owned.size(), "all rails are partitioned");
		assertEquals(first.size(), second.size(), "stable line count");
		for (int i = 0; i < first.size(); i++) {
			assertEquals(first.get(i).rails, second.get(i).rails, "stable partition across re-detection: " + first.get(i).id());
		}
	}
}
