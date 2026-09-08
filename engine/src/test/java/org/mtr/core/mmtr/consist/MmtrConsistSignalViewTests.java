package org.mtr.core.mmtr.consist;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.mmtr.MmtrRegime;
import org.mtr.core.mmtr.consist.MmtrCabState.Cab;
import org.mtr.core.mmtr.consist.MmtrConsistBody.OccupiedSegment;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B6: what the signalling layer reads off a consist body. The two properties that matter: occupancy
 * is the whole consist (and must not flicker when the crew changes ends), and the AWS/LZB regime is
 * that of the rail the manned cab stands on.
 */
public final class MmtrConsistSignalViewTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	private static Rail rail(Position p1, Position p2, int speedLimitKmh) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			speedLimitKmh, speedLimitKmh, false, false, true, false, true, TransportMode.TRAIN);
	}

	/** r0 at 80 km/h (AWS), r1 at 160 km/h (LZB). */
	private static final class TwoBands {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-consist-signal"), false);
		final Position nA = new Position(-40, 0, 0);
		final Position nB = new Position(-20, 0, 0);
		final Position nC = new Position(0, 0, 0);
		final Rail r0 = rail(nA, nB, 80);
		final Rail r1 = rail(nB, nC, 160);

		TwoBands() {
			sim.rails.add(r0);
			sim.rails.add(r1);
			sim.sync();
		}

		double l0() { return r0.railMath.getLength(); }
	}

	private static MmtrConsistWalker spanningWalker(TwoBands bands) {
		final MmtrConsistWalker walker = MmtrConsistWalker.place(bands.sim, new BranchStore(), bands.r0, bands.nA, bands.l0() - 6, new double[]{8, 8}, null);
		assertNotNull(walker);
		return walker;
	}

	@Test
	public void occupancyIsTheWholeConsistAndSurvivesChangeEnds() {
		final TwoBands bands = new TwoBands();
		final MmtrConsistWalker walker = spanningWalker(bands);
		walker.insertKey(Cab.CAB_A, true, true);
		final ObjectArrayList<OccupiedSegment> before = MmtrConsistSignalView.occupiedSegments(walker);
		assertEquals(2, before.size(), "the 16 m consist straddles the 80/160 boundary");
		assertTrue(walker.changeEnds(true));
		final ObjectArrayList<OccupiedSegment> after = MmtrConsistSignalView.occupiedSegments(walker);
		assertEquals(before.size(), after.size(), "换端 must not change what the train occupies");
		for (int i = 0; i < before.size(); i++) {
			assertEquals(before.get(i).railHex(), after.get(i).railHex());
			assertEquals(before.get(i).fromM(), after.get(i).fromM(), 1e-9);
			assertEquals(before.get(i).toM(), after.get(i).toM(), 1e-9);
		}
	}

	@Test
	public void theRegimeFollowsTheMannedCabNotTheTrain() {
		final TwoBands bands = new TwoBands();
		final MmtrConsistWalker walker = spanningWalker(bands);
		walker.insertKey(Cab.CAB_A, true, true);
		// CAB_A leads with the A end, i.e. the 80 km/h rail -> AWS.
		assertEquals(bands.r0.getHexId(), MmtrConsistSignalView.mannedCabRailHex(walker));
		assertEquals(80, MmtrConsistSignalView.mannedCabSpeedLimitKmh(walker), 1e-6);
		assertEquals(MmtrRegime.AWS, MmtrConsistSignalView.regime(walker));

		// The crew changes ends: the B end leads, so the 160 km/h rail is now the cab's rail -> LZB.
		assertTrue(walker.changeEnds(true));
		assertEquals(bands.r1.getHexId(), MmtrConsistSignalView.mannedCabRailHex(walker));
		assertEquals(160, MmtrConsistSignalView.mannedCabSpeedLimitKmh(walker), 1e-6);
		assertEquals(MmtrRegime.LZB, MmtrConsistSignalView.regime(walker));
	}

	@Test
	public void anUnmannedConsistHasNoCabRailAndFallsBackToAws() {
		final TwoBands bands = new TwoBands();
		final MmtrConsistWalker walker = spanningWalker(bands);
		assertNull(MmtrConsistSignalView.mannedCabRailHex(walker));
		assertEquals(0, MmtrConsistSignalView.mannedCabSpeedLimitKmh(walker), 1e-9);
		assertEquals(MmtrRegime.AWS, MmtrConsistSignalView.regime(walker));
	}
}
