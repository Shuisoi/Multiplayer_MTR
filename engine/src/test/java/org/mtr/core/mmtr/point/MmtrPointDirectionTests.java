package org.mtr.core.mmtr.point;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.mmtr.point.MmtrPoint.Form;
import org.mtr.core.mmtr.point.MmtrPoint.LegKind;
import org.mtr.core.mmtr.point.MmtrPoint.MmtrPointLeg;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 (direction-aware turnout abstraction): discovery classifies continuations per approach
 * direction instead of "every other rail, top-2 by cosine". A T junction (horizontal line met by a
 * vertical rail) must expose exactly left/right - no straight; an X crossing lists the straight
 * continuation first and the perpendicular rails with left/right kinds (no fake two-way fork claim);
 * a pure pass-through is not a fork; a standard split keeps the straight as branch0. Ordering is
 * deterministic (straight > left > right > other, cosine inside a kind).
 */
public final class MmtrPointDirectionTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static MmtrPoint pointAt(Simulator sim, long x, long y, long z, Rail via) {
		for (final MmtrPoint point : MmtrPoint.discoverDirectionAware(sim)) {
			if (point.nodeX == x && point.nodeY == y && point.nodeZ == z && point.viaRailHex.equals(via.getHexId())) {
				return point;
			}
		}
		return null;
	}

	private static boolean hasKind(MmtrPoint point, LegKind kind) {
		for (final MmtrPointLeg leg : point.legs) {
			if (leg.kind == kind) {
				return true;
			}
		}
		return false;
	}

	private static MmtrPointLeg legOf(MmtrPoint point, LegKind kind) {
		for (final MmtrPointLeg leg : point.legs) {
			if (leg.kind == kind) {
				return leg;
			}
		}
		return null;
	}

	@Test
	public void teeJunctionFromVerticalApproachIsLeftOrRightOnly() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-point-tee"), false);
		final Position node = new Position(0, 0, 0);
		final Rail v = through(new Position(0, 0, 10), node);   // vertical approach from +z
		final Rail rL = through(node, new Position(-10, 0, 0));  // horizontal west half
		final Rail rR = through(node, new Position(10, 0, 0));   // horizontal east half
		sim.rails.add(v);
		sim.rails.add(rL);
		sim.rails.add(rR);
		sim.sync();

		final MmtrPoint point = pointAt(sim, 0, 0, 0, v);
		assertNotNull(point, "T junction must be discovered from the vertical approach");
		assertEquals(2, point.legs.size(), "exactly two continuations: left and right");
		assertEquals(Form.TEE, point.form, "no straight continuation -> TEE form");
		assertFalse(hasKind(point, LegKind.STRAIGHT), "vertical into horizontal has no straight");
		assertTrue(hasKind(point, LegKind.LEFT) && hasKind(point, LegKind.RIGHT), "both turn kinds present");
		// Deterministic order: LEFT (rank 1) before RIGHT (rank 2); LEFT points to the +x half here.
		assertEquals(rR.getHexId(), point.branch0Hex(), "branch0 = left leg (the +x rail under this geometry)");
		assertEquals(rL.getHexId(), point.branch1Hex(), "branch1 = right leg (the -x rail)");
		assertEquals(rR.getHexId(), legOf(point, LegKind.LEFT).railHex, "left leg rail identity");
	}

	@Test
	public void xCrossingListsStraightFirstAndPerpendicularsAsTurns() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-point-x"), false);
		final Position node = new Position(0, 0, 0);
		final Rail w = through(new Position(-10, 0, 0), node);
		final Rail e = through(node, new Position(10, 0, 0));
		final Rail n = through(new Position(0, 0, -10), node);
		final Rail s = through(node, new Position(0, 0, 10));
		sim.rails.add(w);
		sim.rails.add(e);
		sim.rails.add(n);
		sim.rails.add(s);
		sim.sync();

		// From the west approach: straight (east) + both perpendicular ends, three legs, MULTI form.
		final MmtrPoint fromW = pointAt(sim, 0, 0, 0, w);
		assertNotNull(fromW, "X crossing discovered from the west approach");
		assertEquals(3, fromW.legs.size(), "straight + two perpendicular continuations");
		assertEquals(Form.MULTI, fromW.form, "three or more continuations -> MULTI");
		assertEquals(e.getHexId(), fromW.branch0Hex(), "the straight-through continuation is leg 0");
		assertEquals(LegKind.STRAIGHT, fromW.legs.get(0).kind, "leg 0 is the straight");
		assertTrue(hasKind(fromW, LegKind.LEFT) && hasKind(fromW, LegKind.RIGHT), "perpendicular rails are classified left/right, not as a second straight");

		// From the north approach the roles rotate: south is straight, west/east are turns.
		final MmtrPoint fromN = pointAt(sim, 0, 0, 0, n);
		assertNotNull(fromN, "X crossing discovered from the north approach");
		assertEquals(3, fromN.legs.size(), "three continuations from north too");
		assertEquals(s.getHexId(), fromN.branch0Hex(), "south is the straight from the north approach");
	}

	@Test
	public void passThroughIsNotAFork() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-point-pass"), false);
		final Position node = new Position(0, 0, 0);
		final Rail w = through(new Position(-10, 0, 0), node);
		final Rail e = through(node, new Position(10, 0, 0));
		sim.rails.add(w);
		sim.rails.add(e);
		sim.sync();

		final MmtrPoint point = pointAt(sim, 0, 0, 0, w);
		assertNotNull(point, "pass-through node discovered");
		assertEquals(1, point.legs.size(), "single continuation");
		assertEquals(Form.PASS_THROUGH, point.form, "one continuation is not a switch");
		assertEquals(LegKind.STRAIGHT, point.legs.get(0).kind, "continuation is straight");
		assertNull(point.branch1Hex(), "no branch1 on a pass-through");
	}

	@Test
	public void standardSplitKeepsStraightAsBranch0AndIsDeterministic() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-point-split"), false);
		final Position node = new Position(0, 0, 0);
		final Rail w = through(new Position(-10, 0, 0), node);
		final Rail e = through(node, new Position(10, 0, 0));
		final Rail d = through(node, new Position(10, 0, 6)); // diverging at an angle
		sim.rails.add(w);
		sim.rails.add(e);
		sim.rails.add(d);
		sim.sync();

		final MmtrPoint point = pointAt(sim, 0, 0, 0, w);
		assertNotNull(point, "standard split discovered");
		assertEquals(2, point.legs.size(), "two continuations");
		assertEquals(Form.FORK, point.form, "straight + divergence -> FORK");
		assertEquals(e.getHexId(), point.branch0Hex(), "straight stays branch0");
		assertEquals(d.getHexId(), point.branch1Hex(), "diverging rail is branch1");
		// Repeated discovery is stable (no map-iteration ordering flips).
		final MmtrPoint again = pointAt(sim, 0, 0, 0, w);
		for (int i = 0; i < point.legs.size(); i++) {
			assertEquals(point.legs.get(i).railHex, again.legs.get(i).railHex, "stable leg order across rediscovery");
		}
	}

	@Test
	public void humanShapeTurnoutExcludesCrossArmAndTurnBackReaches() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-point-human"), false);
		final Position node = new Position(0, 0, 0);
		// 人字 wye: stem continues up-right; the two arms go back-down-left / back-down-right so the
		// far end of the OTHER arm lies behind a vehicle arriving from one arm.
		final Rail stem = through(node, new Position(16, 0, -12));
		final Rail armA = through(new Position(-17, 0, 10), node);   // arrival side A
		final Rail armB = through(new Position(-23, 0, -10), node);  // other arm (behind A's heading)
		sim.rails.add(stem);
		sim.rails.add(armA);
		sim.rails.add(armB);
		sim.sync();

		final MmtrPoint fromA = pointAt(sim, 0, 0, 0, armA);
		assertNotNull(fromA, "wye discovered from arm A");
		assertEquals(1, fromA.legs.size(), "from one arm only the stem is reachable (人字: 从左只能到上, not 右)");
		assertEquals(stem.getHexId(), fromA.legs.get(0).railHex, "the stem is the only forward continuation");
	}

	@Test
	public void collinearTurnBackRailIsNotAForwardContinuation() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-point-return"), false);
		final Position node = new Position(0, 0, 0);
		final Rail approach = through(new Position(-20, 0, 0), node);
		final Rail forward = through(node, new Position(20, 0, 0));
		final Rail backward = through(node, new Position(-30, 0, 0)); // same line BEHIND the node
		sim.rails.add(approach);
		sim.rails.add(forward);
		sim.rails.add(backward);
		sim.sync();

		final MmtrPoint point = pointAt(sim, 0, 0, 0, approach);
		assertNotNull(point, "discovered from the west approach");
		assertEquals(1, point.legs.size(), "the collinear turn-back rail is not offered as a continuation");
		assertEquals(forward.getHexId(), point.legs.get(0).railHex, "only the forward rail continues");
	}
}
