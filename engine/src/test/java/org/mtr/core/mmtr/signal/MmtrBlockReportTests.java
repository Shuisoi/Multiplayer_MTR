package org.mtr.core.mmtr.signal;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Vector;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3: the 闭塞区间 diagnostics report behind the OP command {@code blocks} - the operator-facing answer
 * to "which rails are actually split, and what is each light bound to?".
 *
 * <p>Motivated by the real dev world (2026-09-10): all 8 registered lights stand BESIDE a rail end
 * node (2.06 m lateral, 0 m along the track), so no rail is split there and every rail stays one
 * section. The report has to make that visible - including the binding that silently does nothing
 * because the light stands more than the tolerance off its target rail.</p>
 */
public final class MmtrBlockReportTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	private static Rail rail(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/** The block the curve at {@code arcM} passes through (what a placed signal block sits on). */
	private static int[] blockAt(Rail rail, double arcM) {
		final Vector position = rail.railMath.getPosition(arcM, false);
		return new int[]{(int) Math.floor(position.x()), (int) Math.floor(position.y()), (int) Math.floor(position.z())};
	}

	@Test
	public void theReportNamesSplitRailsNodeLightsAndDeadBindings() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-block-report"), false);
		final Rail longRail = rail(new Position(0, 0, 0), new Position(200, 0, 0));
		final Rail shortRail = rail(new Position(0, 0, 50), new Position(120, 0, 50));
		sim.rails.add(longRail);
		sim.rails.add(shortRail);
		sim.sync();

		// One light in the middle of the long rail: the only thing that splits anything.
		final int[] mid = blockAt(longRail, 100);
		sim.mmtrSignals.put(mid[0], mid[1], mid[2], 0, 2, "set", longRail.getHexId());
		// One light BESIDE the long rail's entry node (2 blocks to the side, 0 m along) - the real
		// placement: it projects onto the node and must NOT split.
		sim.mmtrSignals.put(0, 0, 2, 180, 2, "set", longRail.getHexId());
		// One light nowhere near a rail: AUTO placement finds nothing.
		sim.mmtrSignals.put(500, 0, 0, 0, 2, "set", "");

		final String all = MmtrBlockReport.describeAll(sim);
		assertTrue(all.contains("轨道 2 根 / 区间 3 个 / 被灯切分的轨 1 根"), "the summary counts sections, not rails: " + all);
		assertTrue(all.contains("切分该轨"), "the mid-rail light is reported as splitting: " + all);
		assertTrue(all.contains("贴节点（不切分）"), "the light beside the node is reported as not splitting: " + all);
		assertTrue(all.contains("未绑定"), "the light off every rail is reported as unbound: " + all);
		assertTrue(all.contains("灯共 3 架：贴节点 1 / 切分轨 1 / 未生效 1"), "the per-kind tally: " + all);

		final String one = MmtrBlockReport.describe(sim, longRail.getHexId());
		assertTrue(one.contains("区间=2"), "the split rail has two sections: " + one);
		assertTrue(one.contains("SIGNAL@100.0"), "the signal boundary is named with its arc: " + one);
		assertTrue(one.contains("NODE@0.0") && one.contains("NODE@200.0"), "both end nodes are boundaries: " + one);
		assertTrue(one.contains("区间 [0.0, 100.0) 长=100.0"), "the first section: " + one);
		assertTrue(one.contains("区间 [100.0, 200.0) 长=100.0"), "the second section: " + one);

		final String untouched = MmtrBlockReport.describe(sim, shortRail.getHexId());
		assertTrue(untouched.contains("区间=1"), "a rail without a mid-rail light stays one section: " + untouched);

		assertTrue(MmtrBlockReport.describe(sim, "missing").contains("找不到轨"), "unknown rails are reported, not thrown");
		assertTrue(MmtrBlockReport.describe(sim, null).contains("用法"), "a missing argument prints the usage line");
	}
}
