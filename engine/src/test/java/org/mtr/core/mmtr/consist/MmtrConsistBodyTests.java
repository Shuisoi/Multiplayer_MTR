package org.mtr.core.mmtr.consist;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.mmtr.consist.MmtrConsistBody.SpineLeg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1: the consist body as an oriented interval on the rail graph — the replacement for Motion
 * Core's old single-point position state. These tests pin the two properties the 换端 rewrite
 * depends on: car positions are measured from the A end (so changing the driving cab cannot move a
 * car), and occupancy is a real multi-rail interval (so rear-clear can be expressed later).
 */
public final class MmtrConsistBodyTests {

	private static SpineLeg leg(String hex, double lengthM) {
		return new MmtrConsistBody.SpineLeg(hex, new Position(0, 0, 0), new Position(1, 0, 0), lengthM);
	}

	private static ObjectArrayList<MmtrConsistBody.SpineLeg> spine(String... hexAndLength) {
		final ObjectArrayList<MmtrConsistBody.SpineLeg> out = new ObjectArrayList<>();
		for (int i = 0; i < hexAndLength.length; i += 2) {
			out.add(leg(hexAndLength[i], Double.parseDouble(hexAndLength[i + 1])));
		}
		return out;
	}

	@Test
	public void singleLegBodyPlacesCarsFromTheAEnd() {
		final MmtrConsistBody body = new MmtrConsistBody(spine("R0", "100"), 10, new double[]{20, 20, 20});
		assertEquals(10, body.aEndArcM(), 1e-9);
		assertEquals(70, body.bEndArcM(), 1e-9);
		assertEquals(60, body.lengthM(), 1e-9);
		assertEquals(10, body.carStartArcM(0), 1e-9);
		assertEquals(30, body.carStartArcM(1), 1e-9);
		assertEquals(20, body.carCenterArcM(0), 1e-9);
		assertEquals(40, body.carCenterArcM(1), 1e-9);
		assertEquals(60, body.carCenterArcM(2), 1e-9);
		final ObjectArrayList<MmtrConsistBody.OccupiedSegment> occupancy = body.occupancy();
		assertEquals(1, occupancy.size());
		assertEquals("R0", occupancy.get(0).railHex());
		assertEquals(10, occupancy.get(0).fromM(), 1e-9);
		assertEquals(70, occupancy.get(0).toM(), 1e-9);
	}

	@Test
	public void bodyCrossingThreeRailsReportsThreeOccupiedSegments() {
		// 3 x 10 m rails, a 16 m two-car consist starting 5 m into the first rail.
		final MmtrConsistBody body = new MmtrConsistBody(spine("R0", "10", "R1", "10", "R2", "10"), 5, new double[]{8, 8});
		final ObjectArrayList<MmtrConsistBody.OccupiedSegment> occupancy = body.occupancy();
		assertEquals(3, occupancy.size(), "a body spanning three rails occupies three rails");
		assertEquals("R0", occupancy.get(0).railHex());
		assertEquals(5, occupancy.get(0).fromM(), 1e-9);
		assertEquals(10, occupancy.get(0).toM(), 1e-9);
		assertEquals("R1", occupancy.get(1).railHex());
		assertEquals(0, occupancy.get(1).fromM(), 1e-9);
		assertEquals(10, occupancy.get(1).toM(), 1e-9);
		assertEquals("R2", occupancy.get(2).railHex());
		assertEquals(0, occupancy.get(2).fromM(), 1e-9);
		assertEquals(1, occupancy.get(2).toM(), 1e-9);
		assertEquals(9, body.carCenterArcM(0), 1e-9);
		assertEquals(17, body.carCenterArcM(1), 1e-9);
	}

	@Test
	public void carPositionsAreMeasuredFromTheAEndNotFromTheDrivingDirection() {
		// The body carries no direction at all: 换端 cannot be expressed as an operation on it.
		// This test documents that property - the same body always yields the same car arcs, and
		// the only way to move a car is slideBy (i.e. the train actually moved).
		final MmtrConsistBody body = new MmtrConsistBody(spine("R0", "100"), 20, new double[]{15, 15});
		final double[] before = body.carCenterArcMs();
		assertEquals(27.5, before[0], 1e-9);
		assertEquals(42.5, before[1], 1e-9);
		assertTrue(body.slideBy(0), "a zero slide is a no-op");
		assertEquals(before[0], body.carCenterArcM(0), 1e-9);
		assertEquals(before[1], body.carCenterArcM(1), 1e-9);
	}

	@Test
	public void slideMovesEveryCarAndTheOccupancyByTheSameAmount() {
		final MmtrConsistBody body = new MmtrConsistBody(spine("R0", "10", "R1", "10"), 2, new double[]{6, 6});
		assertEquals(5, body.carCenterArcM(0), 1e-9);
		assertEquals(11, body.carCenterArcM(1), 1e-9);
		assertTrue(body.slideBy(3));
		assertEquals(5, body.aEndArcM(), 1e-9);
		assertEquals(8, body.carCenterArcM(0), 1e-9);
		assertEquals(14, body.carCenterArcM(1), 1e-9);
		final ObjectArrayList<MmtrConsistBody.OccupiedSegment> occupancy = body.occupancy();
		assertEquals(2, occupancy.size());
		assertEquals("R0", occupancy.get(0).railHex());
		assertEquals(5, occupancy.get(0).fromM(), 1e-9);
		assertEquals(10, occupancy.get(0).toM(), 1e-9);
		assertEquals("R1", occupancy.get(1).railHex());
		assertEquals(0, occupancy.get(1).fromM(), 1e-9);
		assertEquals(7, occupancy.get(1).toM(), 1e-9);
	}

	@Test
	public void slidePastTheSpineIsRejectedAndLeavesTheBodyUntouched() {
		final MmtrConsistBody body = new MmtrConsistBody(spine("R0", "10"), 5, new double[]{4});
		assertFalse(body.slideBy(5), "5 m of body at 5 m already touches the far node");
		assertEquals(5, body.aEndArcM(), 1e-9);
		assertFalse(body.slideBy(-6), "cannot slide the A end behind the spine start");
		assertEquals(5, body.aEndArcM(), 1e-9);
	}

	@Test
	public void appendLegAtTheBEndLetsTheBodySlideOn() {
		final MmtrConsistBody body = new MmtrConsistBody(spine("R0", "10"), 6, new double[]{4});
		assertFalse(body.slideBy(1));
		body.appendLeg(leg("R1", 10));
		assertEquals(20, body.spineLengthM(), 1e-9);
		assertTrue(body.slideBy(1));
		assertEquals(7, body.aEndArcM(), 1e-9);
		final ObjectArrayList<MmtrConsistBody.OccupiedSegment> occupancy = body.occupancy();
		assertEquals(2, occupancy.size());
		assertEquals("R1", occupancy.get(1).railHex());
		assertEquals(0, occupancy.get(1).fromM(), 1e-9);
		assertEquals(1, occupancy.get(1).toM(), 1e-9);
	}

	@Test
	public void prependLegAtTheAEndShiftsTheArcOrigin() {
		final MmtrConsistBody body = new MmtrConsistBody(spine("R0", "10"), 2, new double[]{4});
		final double before = body.carCenterArcM(0);
		body.prependLeg(leg("RA", 5));
		assertEquals(15, body.spineLengthM(), 1e-9);
		assertEquals(7, body.aEndArcM(), 1e-9);
		assertEquals(before + 5, body.carCenterArcM(0), 1e-9, "the car did not move; only the arc origin shifted");
		assertEquals("R0", body.occupancy().get(0).railHex(), "the body still stands on R0, the prepended leg is behind it");
	}

	@Test
	public void trimOutsideLegsDropsClearedRailsAndCorrectsTheArcOrigin() {
		// Body sits on R1 + the start of R2; R0 is already behind the A end.
		final MmtrConsistBody body = new MmtrConsistBody(spine("R0", "10", "R1", "10", "R2", "10"), 12, new double[]{10});
		assertEquals(2, body.occupancy().size());
		assertEquals(17, body.carCenterArcM(0), 1e-9);
		assertEquals(1, body.trimOutsideLegs(), "R0 no longer touches the body");
		assertEquals(2, body.legCount());
		assertEquals(2, body.aEndArcM(), 1e-9);
		assertEquals(7, body.carCenterArcM(0), 1e-9, "same physical point, new arc origin");
		assertEquals("R1", body.occupancy().get(0).railHex());
		assertEquals("R2", body.occupancy().get(1).railHex());
		assertEquals(0, body.trimOutsideLegs(), "nothing else to trim");
	}

	@Test
	public void legAtArcResolvesTheRailAndItsOffset() {
		final MmtrConsistBody body = new MmtrConsistBody(spine("R0", "10", "R1", "10"), 4, new double[]{8});
		assertEquals("R0", body.legAtArcM(4).railHex());
		assertEquals(4, body.legOffsetM(4), 1e-9);
		assertEquals("R1", body.legAtArcM(12).railHex());
		assertEquals(2, body.legOffsetM(12), 1e-9);
		assertNotNull(body.legAtArcM(0));
	}

	@Test
	public void aBodyLongerThanItsSpineIsRejected() {
		assertThrows(IllegalStateException.class, () -> new MmtrConsistBody(spine("R0", "10"), 0, new double[]{20}));
	}

	@Test
	public void degenerateInputsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> new MmtrConsistBody(new ObjectArrayList<>(), 0, new double[]{5}));
		assertThrows(IllegalArgumentException.class, () -> new MmtrConsistBody(spine("R0", "10"), 0, new double[0]));
		assertThrows(IllegalArgumentException.class, () -> new MmtrConsistBody(spine("R0", "10"), 0, new double[]{0}));
		assertThrows(IllegalArgumentException.class, () -> new MmtrConsistBody(spine("R0", "0"), 0, new double[]{1}));
	}
}
