package org.mtr.core.mmtr;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 尽头换向 (terminal flip / 换端): a train that must reverse at a real 人字 turnout cannot fold
 * 180° at the crossing - it drives onto the dead-end lead (the near-straight arm), stops at the
 * buffer, changes ends, and runs back out onto the return track. Layout abstracts the real
 * (-147,-169) junction: C = arrival diagonal, D = dead-end lead straight ahead (死岔), U = the
 * return track continuing the same line on the far side of the junction, with J the crossing.
 * <pre>
 *        F(0,10)  dead end of D
 *        | D
 *   C ---J(0,0)   arrival from (-10,0)
 *        | U (target)
 *        (0,-20)
 * </pre>
 */
public final class MmtrDeadEndFlipTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	private static Rail rail(Position p1, Position p2) {
		final float deg = (float) Math.toDegrees(Math.atan2(p2.getZ() - p1.getZ(), p2.getX() - p1.getX()));
		return Rail.newRail(p1, Angle.fromAngle(deg), p2, Angle.fromAngle(deg + 180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static final class FlipNet {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-flip"), false);
		final Position junction = new Position(0, 0, 0);
		final Position deadEndPos = new Position(0, 0, 10);   // F: far end of the dead-end lead
		final Position returnFar = new Position(0, 0, -20);   // far end of the return track
		final Position arrivalFar = new Position(-10, 0, 0);  // far end of the arrival diagonal
		final Rail deadLead = rail(junction, deadEndPos);     // D
		final Rail returnRail = rail(junction, returnFar);    // U (target)
		final Rail arrivalRail = rail(arrivalFar, junction);  // C

		FlipNet() {
			sim.rails.add(deadLead);
			sim.rails.add(returnRail);
			sim.rails.add(arrivalRail);
			sim.sync();
			final int[] degree = {0};
			sim.positionsToRail.get(deadEndPos).forEach((other, r) -> degree[0]++);
			assertEquals(1, degree[0], "dead-end lead far end must be a true dead end");
		}
	}

	@Test
	public void deadEndFlipRunsTheTrainBackOntoTheReturnTrack() {
		final FlipNet net = new FlipNet();
		final double lenD = net.deadLead.railMath.getLength();
		final double lenU = net.returnRail.railMath.getLength();

		// The train has already entered the dead-end lead and stands 5 m from its dead end.
		final MmtrMotionWalker walker = MmtrMotionWalker.start(net.sim, net.deadLead, net.junction, new BranchStore(), null);
		walker.advance(5);
		assertEquals(5, walker.offsetM(), 1e-6);

		// Drive to the dead end: the walker must come to rest at the end of the line.
		walker.advance(lenD - 5);
		assertTrue(walker.endOfLine(), "walker must rest at the dead end");
		assertEquals(lenD, walker.distanceM(), 1e-6);

		// Flip (换端): legal only with the head at the far node.
		assertTrue(walker.flipDirection(), "flip must be legal at the dead end");
		assertFalse(walker.endOfLine(), "after the flip the walker must move again");
		assertEquals(0, walker.offsetM(), 1e-6);
		assertEquals(2, walker.legCount(), "arrival leg + flip return leg");

		// Run back to the junction and elect the return track (operator branch 0 = straight).
		final BranchStore store = new BranchStore();
		store.set(net.junction.getX(), net.junction.getY(), net.junction.getZ(), net.deadLead.getHexId(), 0);
		final MmtrMotionWalker back = MmtrMotionWalker.start(net.sim, net.deadLead, net.junction, store, null);
		back.advance(5);
		back.advance(lenD - 5);
		assertTrue(back.flipDirection());
		back.advance(lenD + 3); // back to the junction (lenD) and 3 m up the return track
		assertEquals(net.returnRail.getHexId(), back.railHex(), "after the flip the train must continue onto the return track");
		assertEquals(3, back.offsetM(), 1e-6);
		assertEquals(2 * lenD + 3, back.distanceM(), 1e-6, "distance accumulates monotonically through the flip");
		assertEquals(3, back.legCount(), "arrival half, flip return leg, return-track leg");

		// The junction sees three rails (arrival + lead + return); the lead's far end is a dead end,
		// which is exactly the corridor the 尽头换向 probe needs.
		final int[] degree = {0};
		net.sim.positionsToRail.get(net.junction).forEach((other, r) -> degree[0]++);
		assertEquals(3, degree[0]);
		assertTrue(lenU > 0);
	}
}

