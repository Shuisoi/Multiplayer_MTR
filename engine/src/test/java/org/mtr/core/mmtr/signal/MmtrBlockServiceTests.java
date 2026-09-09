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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1: the block-section service. Per the user's ruling (2026-09-09) sections are cut by SIGNALS only -
 * no length-based virtual boundaries, a long rail with no signal stays one section; and a signal that
 * sits at an end node does not split anything (its projection IS the node, so the node-binding reading
 * and the geometric reading agree).
 */
public final class MmtrBlockServiceTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	private static Rail rail(Position p1, Position p2) {
		return rail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180));
	}

	private static Rail rail(Position p1, Angle a1, Position p2, Angle a2) {
		return Rail.newRail(p1, a1, p2, a2, Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static Simulator sim(String savePath, Rail... rails) {
		final Simulator simulator = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
		for (final Rail r : rails) {
			simulator.rails.add(r);
		}
		simulator.sync();
		return simulator;
	}

	/** The block coordinates of the point at {@code arcM} along a rail (what a signal block would sit on). */
	private static int[] blockCoordsAt(Rail rail, double arcM) {
		final Vector position = rail.railMath.getPosition(arcM, false);
		return new int[]{(int) Math.floor(position.x()), (int) Math.floor(position.y()), (int) Math.floor(position.z())};
	}

	private static void addSignal(Simulator simulator, Rail rail, double arcM) {
		final int[] coords = blockCoordsAt(rail, arcM);
		// Register directly (not via mmtrSignalOp): the op persists to the save dir, which would leak
		// into the next test run that reuses the same save path.
		simulator.mmtrSignals.put(coords[0], coords[1], coords[2], 0, 2, "set", rail.getHexId());
	}

	@Test
	public void aRailWithoutSignalsIsOneBlock() {
		final Rail r = rail(new Position(0, 0, 0), new Position(120, 0, 0));
		final Simulator simulator = sim("build/mmtr-block-single", r);
		final MmtrBlockService service = new MmtrBlockService(simulator);

		final ObjectArrayList<MmtrBlockService.Block> blocks = service.blocksOf(r.getHexId());
		assertEquals(1, blocks.size(), "no signal -> one section");
		assertEquals(0, blocks.get(0).arcFromM, 1e-6);
		assertEquals(r.railMath.getLength(), blocks.get(0).arcToM, 1e-6, "the section covers the whole rail");
		assertEquals(2, service.boundariesOf(r.getHexId()).size(), "two node boundaries");
		assertTrue(service.boundariesOf(r.getHexId()).stream().allMatch(boundary -> boundary.kind == MmtrBlockService.BoundaryKind.NODE));
		assertEquals(blocks.get(0), service.blockAt(r.getHexId(), r.railMath.getLength() / 2), "the middle of the rail is in it");
		assertNull(service.blockAt(null, 1), "unknown rails are safe");
		assertTrue(service.blocksOf("missing").isEmpty());
	}

	@Test
	public void aBoundSignalSplitsItsRailAtTheProjectedArc() {
		final Rail r = rail(new Position(0, 0, 0), new Position(120, 0, 0));
		final Simulator simulator = sim("build/mmtr-block-signal", r);
		addSignal(simulator, r, 60);
		final MmtrBlockService service = new MmtrBlockService(simulator);

		final ObjectArrayList<MmtrBlockService.Block> blocks = service.blocksOf(r.getHexId());
		assertEquals(2, blocks.size(), "one signal in the middle -> two sections");
		assertEquals(0, blocks.get(0).arcFromM, 1e-6);
		assertEquals(blocks.get(0).arcToM, blocks.get(1).arcFromM, 1e-6, "the sections are contiguous");
		assertEquals(r.railMath.getLength(), blocks.get(1).arcToM, 1e-6);
		assertEquals(60, blocks.get(0).arcToM, 1.5, "the boundary lands on the projected arc");

		final ObjectArrayList<MmtrBlockService.Boundary> boundaries = service.boundariesOf(r.getHexId());
		assertEquals(3, boundaries.size());
		assertEquals(MmtrBlockService.BoundaryKind.SIGNAL, boundaries.get(1).kind, "the middle boundary is the signal");
		assertEquals(blocks.get(0), service.blockAt(r.getHexId(), 10), "before the signal is the first section");
		assertEquals(blocks.get(1), service.blockAt(r.getHexId(), 90), "after the signal is the second section");
	}

	@Test
	public void aLongRailWithoutSignalsStaysOneSection() {
		final Rail r = rail(new Position(0, 0, 0), new Position(300, 0, 0));
		final MmtrBlockService service = new MmtrBlockService(sim("build/mmtr-block-long", r));
		assertEquals(1, service.blocksOf(r.getHexId()).size(),
			"a 300 m rail with no signal is ONE section - sections are cut by signals, not by length");
	}

	@Test
	public void aSignalFartherThanTheToleranceIsIgnored() {
		final Rail r = rail(new Position(0, 0, 0), new Position(120, 0, 0));
		final Simulator simulator = sim("build/mmtr-block-far", r);
		final int[] coords = blockCoordsAt(r, 60);
		simulator.mmtrSignals.put(coords[0], coords[1] + 30, coords[2], 0, 2, "set", r.getHexId());
		assertEquals(1, new MmtrBlockService(simulator).blocksOf(r.getHexId()).size(), "a signal 30 blocks away is not on this rail");
	}

	@Test
	public void aSignalAtAnEndNodeDoesNotSplitTheRail() {
		final Rail r = rail(new Position(0, 0, 0), new Position(120, 0, 0));
		final Simulator simulator = sim("build/mmtr-block-node", r);
		addSignal(simulator, r, 0);
		final MmtrBlockService service = new MmtrBlockService(simulator);
		assertEquals(1, service.blocksOf(r.getHexId()).size(), "a signal at the end node projects onto the node: no split");
		assertEquals(2, service.boundariesOf(r.getHexId()).size());
	}

	@Test
	public void aNewSignalIsPickedUpOnRefresh() {
		final Rail r = rail(new Position(0, 0, 0), new Position(120, 0, 0));
		final Simulator simulator = sim("build/mmtr-block-refresh", r);
		final MmtrBlockService service = new MmtrBlockService(simulator);
		assertEquals(1, service.blocksOf(r.getHexId()).size(), "no signal yet");
		addSignal(simulator, r, 40);
		assertEquals(2, service.blocksOf(r.getHexId()).size(), "the rails+signals signature change rebuilds the cache");
		assertEquals(1, service.railCount());
		assertEquals(2, service.blockCount());
	}

	/**
	 * `signals scan` registers AUTO entries with an EMPTY target (MmtrCommandExecutor passes ""), so a
	 * scanned wayside light must still cut the rail it stands on - otherwise the section feature would
	 * never show up in a real world without hand-binding every light.
	 */
	@Test
	public void anUnboundScannedSignalStillSplitsTheNearestRail() {
		final Rail r = rail(new Position(0, 0, 0), new Position(120, 0, 0));
		final Simulator simulator = sim("build/mmtr-block-auto", r);
		final int[] coords = blockCoordsAt(r, 60);
		simulator.mmtrSignals.put(coords[0], coords[1], coords[2], 0, 2, "set", "");
		final MmtrBlockService service = new MmtrBlockService(simulator);
		assertEquals(2, service.blocksOf(r.getHexId()).size(), "the AUTO light is inferred onto the rail it stands on");
		assertEquals(MmtrBlockService.BoundaryKind.SIGNAL, service.boundariesOf(r.getHexId()).get(1).kind);
	}

	@Test
	public void anUnboundSignalFarFromEveryRailIsIgnored() {
		final Rail r = rail(new Position(0, 0, 0), new Position(120, 0, 0));
		final Simulator simulator = sim("build/mmtr-block-auto-far", r);
		simulator.mmtrSignals.put(60, 30, 0, 0, 2, "set", "");
		assertEquals(1, new MmtrBlockService(simulator).blocksOf(r.getHexId()).size(), "30 blocks away is not on a rail");
	}

	@Test
	public void sectionsOfDifferentRailsStayIndependent() {
		final Rail a = rail(new Position(0, 0, 0), new Position(120, 0, 0));
		final Rail b = rail(new Position(0, 0, 50), new Position(120, 0, 50));
		final Simulator simulator = sim("build/mmtr-block-two-rails", a, b);
		addSignal(simulator, b, 60);
		final MmtrBlockService service = new MmtrBlockService(simulator);
		assertEquals(1, service.blocksOf(a.getHexId()).size(), "rail A has no signal");
		assertEquals(2, service.blocksOf(b.getHexId()).size(), "rail B is split by its signal");
		assertEquals(3, service.blockCount());
		assertEquals(2, service.railCount());
		assertNotNull(service.blockAt(a.getHexId(), 1));
	}

	/**
	 * B2 regression: a rail whose DECLARED position 1 sorts after its position 2 (a rail drawn from
	 * east to west, say) still measures sections from the ordered-first endpoint, because that is where
	 * {@link Rail#railMath} and the shared occupancy trees start. Reading the endpoints off the curve
	 * samples instead does not work: a curved rail shape shifts them to the block centre (a rail from
	 * (-8,0,0) samples as (-7.5, 0, 0.5)), which broke the node lookup for every such rail.
	 */
	@Test
	public void aRailDrawnBackwardsIsCutInOrderedPositionSpace() {
		final Position east = new Position(120, 0, 0);
		final Position west = new Position(0, 0, 0);
		final Rail r = rail(east, Angle.fromAngle(180), west, Angle.fromAngle(0));
		final Simulator simulator = sim("build/mmtr-block-reversed", r);
		addSignal(simulator, r, 60);
		final MmtrBlockService service = new MmtrBlockService(simulator);

		final ObjectArrayList<MmtrBlockService.Block> blocks = service.blocksOf(r.getHexId());
		assertEquals(2, blocks.size(), "the signal splits the rail regardless of drawing direction");
		assertEquals(60, blocks.get(0).arcToM, 1.5, "the split is measured from the ordered-first endpoint");
		assertEquals(0, MmtrBlockService.arcOfNode(r, west), 1e-9, "the ordered-first node is arc 0");
		assertEquals(r.railMath.getLength(), MmtrBlockService.arcOfNode(r, east), 1e-9, "the other node is the rail length");
		assertTrue(Double.isNaN(MmtrBlockService.arcOfNode(r, new Position(60, 0, 0))), "a mid-rail point is not a node");
		assertEquals(blocks.get(0), service.blockAt(r.getHexId(), 10), "arc 10 is the section next to the west node");
		assertEquals(blocks.get(1), service.blockAt(r.getHexId(), 110), "arc 110 is the far section");
	}
}
