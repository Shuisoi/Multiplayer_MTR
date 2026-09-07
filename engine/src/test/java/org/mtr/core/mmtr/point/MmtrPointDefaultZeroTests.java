package org.mtr.core.mmtr.point;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hard default 0 (所有道岔默认 0): real servers preset every turnout fork to operator branch 0 on
 * boot / rail changes. The seeding is rails-signature gated: once seeded, an operator clearing a
 * fork (✕设 / branch -1) stays unset until the track changes or the server restarts. Engine tests
 * never enable the flag (synthetic authority/mission semantics stay untouched).
 */
public final class MmtrPointDefaultZeroTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static Simulator forkNet(String path) {
		final Simulator simulator = new Simulator("test", new String[]{"test"}, Paths.get(path), false);
		final Position node = new Position(0, 0, 0);
		simulator.rails.add(through(new Position(-20, 0, 0), node));
		simulator.rails.add(through(node, new Position(20, 0, 0)));
		simulator.rails.add(through(node, new Position(20, 0, 14)));
		simulator.sync();
		return simulator;
	}

	@Test
	public void flagOffNeverSeeds() {
		final Simulator simulator = forkNet("build/mmtr-default-off");
		simulator.mmtrEnsurePointDefaults(); // flag defaults to false in tests
		assertEquals(0, simulator.mmtrPointBranches.branches.size(), "engine tests keep unset forks unset");
	}

	@Test
	public void flagOnSeedsEveryForkToZeroAndStaysGatedAfterClearing() {
		final Simulator simulator = forkNet("build/mmtr-default-on");
		simulator.mmtrDefaultPointsZero = true;
		simulator.mmtrEnsurePointDefaults();
		assertEquals(3, simulator.mmtrPointBranches.branches.size(), "every (node, via) fork row at the wye node is preset to 0");
		final org.mtr.core.data.Position mouth = new Position(0, 0, 0);
		String viaHex = "";
		for (final org.mtr.core.data.Rail rail : simulator.rails) {
			viaHex = rail.getHexId();
			break;
		}
		assertTrue(simulator.mmtrPointBranches.contains(mouth.getX(), mouth.getY(), mouth.getZ(), viaHex), "operator branch preset exists");
		assertEquals(0, simulator.mmtrPointBranches.get(mouth.getX(), mouth.getY(), mouth.getZ(), viaHex), "default branch is 0");

		// Operator clears the fork (✕设 = branch -1): the rails signature did not change, so the
		// gated seeding must NOT silently re-set it while the server keeps running.
		assertTrue(simulator.mmtrSetPoint(mouth.getX(), mouth.getY(), mouth.getZ(), viaHex, -1), "operator clears the fork");
		assertFalse(simulator.mmtrPointBranches.contains(mouth.getX(), mouth.getY(), mouth.getZ(), viaHex), "cleared fork is unset");
		simulator.mmtrEnsurePointDefaults();
		assertFalse(simulator.mmtrPointBranches.contains(mouth.getX(), mouth.getY(), mouth.getZ(), viaHex), "no re-seed while the rail set is unchanged");
		assertEquals(2, simulator.mmtrPointBranches.branches.size(), "the other two fork rows of the same node stay seeded");
	}

	@Test
	public void railChangesReseedTheNewFork() {
		final Simulator simulator = forkNet("build/mmtr-default-reseed");
		simulator.mmtrDefaultPointsZero = true;
		simulator.mmtrEnsurePointDefaults();
		assertEquals(3, simulator.mmtrPointBranches.branches.size(), "first fork rows seeded");

		// A second turnout appears on the network: its fork rows must be seeded to 0 by the next check.
		final Position node2 = new Position(20, 0, 14);
		simulator.rails.add(through(node2, new Position(40, 0, 14)));
		simulator.rails.add(through(node2, new Position(40, 0, 28)));
		simulator.sync();
		simulator.mmtrEnsurePointDefaults();
		assertEquals(6, simulator.mmtrPointBranches.branches.size(), "the new turnout's fork rows got their default 0");
		assertTrue(simulator.mmtrPointBranches.branches.keySet().stream().anyMatch(key -> key.startsWith("20,0,14|")), "the new node's forks are present");
	}
}
