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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闭塞区间 v2 S1: <strong>灯到灯的跨轨有向区间</strong> (pure data, not yet wired to S1 stops or display).
 *
 * <p>The behaviour under test is the whole point of v2: a lamp that stands on a rail end node still
 * creates a boundary, and the section it starts runs <em>across</em> rail boundaries until the next
 * lamp facing the same way - so a long corridor with one lamp is not "one section per 15 m rail".</p>
 *
 * <p>Facing convention (locked here): the engine stores Minecraft's block facing rotation, where
 * 0 = south (+Z), 90 = west (-X), 180 = north (-Z), 270 = east (+X). {@link #EAST} etc. are the lamp
 * angles used by the tests, so a change to the convention fails these tests.</p>
 */
public final class MmtrDirectionalBlockServiceTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final float SOUTH = 0;
	private static final float WEST = 90;
	private static final float NORTH = 180;
	private static final float EAST = 270;

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

	/** The block coordinates a signal block would occupy for the point at {@code arcM} on {@code rail}. */
	private static int[] blockCoordsAt(Rail rail, double arcM) {
		final Vector position = rail.railMath.getPosition(arcM, false);
		return new int[]{(int) Math.floor(position.x()), (int) Math.floor(position.y()), (int) Math.floor(position.z())};
	}

	/** Register an AUTO lamp (no target) at {@code arcM} of {@code rail}, facing {@code angle}. */
	private static String addLamp(Simulator simulator, Rail rail, double arcM, float angle) {
		final int[] coords = blockCoordsAt(rail, arcM);
		// Register directly (not via mmtrSignalOp): the op persists into the save dir and would leak
		// into a later test that reuses the same save path.
		simulator.mmtrSignals.put(coords[0], coords[1], coords[2], angle, 4, "AUTO", "");
		return MmtrSignalRegistry.key(coords[0], coords[1], coords[2]);
	}

	@Test
	public void oneLampOnALongRailMakesTheWholeRailOneSection() {
		final Rail r = rail(new Position(0, 0, 0), new Position(300, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-long", r);
		final String lamp = addLamp(simulator, r, 0, EAST);
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);

		final MmtrDirectionalBlockService.Section section = service.sectionOfSignal(lamp);
		assertNotNull(section, "a lamp standing on the rail's end node still starts a section (v1 discarded it)");
		assertEquals(1, section.spans.size(), "one rail, no further lamp -> one span");
		assertEquals(300, section.lengthM(), 1.0, "the section covers the whole 300 m rail");
		assertTrue(section.endsAtDeadEnd, "with no second lamp the walk runs to the end of the line");
	}

	@Test
	public void aSectionRunsAcrossRailBoundariesUntilTheNextLamp() {
		// Three rails in a row, 100 m each, and ONE lamp at the head: the section must span all three.
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Rail r3 = rail(new Position(200, 0, 0), new Position(300, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-cross", r1, r2, r3);
		final String lamp = addLamp(simulator, r1, 0, EAST);
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);

		final MmtrDirectionalBlockService.Section section = service.sectionOfSignal(lamp);
		assertNotNull(section);
		assertEquals(3, section.spans.size(),
			"the section crosses both rail boundaries - this is what v1 could not express");
		assertEquals(300, section.lengthM(), 1.5, "the section is the whole 300 m corridor, not one 100 m rail");
		assertEquals(r1.getHexId(), section.spans.get(0).railHex);
		assertEquals(r2.getHexId(), section.spans.get(1).railHex);
		assertEquals(r3.getHexId(), section.spans.get(2).railHex);
	}

	@Test
	public void aLampStartsAtTheNodeItStandsOnEvenWhenAHeadRailEndsThere() {
		// A head rail ends at the shared node where the lamp stands. The lamp must NOT be bound to that
		// head rail (the stretch it has already passed): it protects the rail that LEAVES the node along
		// the direction it faces. Getting this wrong gives a zero-length section.
		final Rail head = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail next = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-node-choice", head, next);
		final String lamp = addLamp(simulator, head, 100, EAST);
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);

		final MmtrDirectionalBlockService.Section section = service.sectionOfSignal(lamp);
		assertNotNull(section, "the lamp protects the rail leaving the node, not the one ending there");
		assertEquals(next.getHexId(), section.entryRailHex(), "the protected rail is the one ahead of the node");
		assertEquals(100, section.lengthM(), 1.5, "the full ahead rail, not a zero-length stub");
	}

	@Test
	public void theNextLampFacingTheSameWayEndsTheSection() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Rail r3 = rail(new Position(200, 0, 0), new Position(300, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-two-lamps", r1, r2, r3);
		final String first = addLamp(simulator, r1, 0, EAST);
		final String second = addLamp(simulator, r3, 0, EAST);
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);

		final MmtrDirectionalBlockService.Section section = service.sectionOfSignal(first);
		assertNotNull(section);
		assertEquals(second, section.exitSignalKey, "the next lamp ends the section");
		assertEquals(2, section.spans.size(), "from lamp 1 (r1 start) to lamp 2 (r3 start): r1 and r2");
		assertEquals(r1.getHexId(), section.spans.get(0).railHex);
		assertEquals(r2.getHexId(), section.spans.get(1).railHex);
		assertEquals(200, section.lengthM(), 1.5);
		assertNotNull(service.sectionOfSignal(second), "the second lamp starts its own section");
	}

	@Test
	public void sectionsAreDirectional() {
		// The same rail carries TWO sections, one per direction: an east-facing head at the west end
		// protects the eastbound movement, a west-facing head at the east end protects the westbound one.
		// This is what "directed" buys: the same arc of the same rail is in different sections depending
		// on which way the movement travels.
		final Rail line = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-directional", line);
		simulator.mmtrSignals.put(0, 0, 0, EAST, 4, "BOUND", line.getHexId());
		simulator.mmtrSignals.put(100, 0, 0, WEST, 4, "BOUND", line.getHexId());
		final String eastLamp = MmtrSignalRegistry.key(0, 0, 0);
		final String westLamp = MmtrSignalRegistry.key(100, 0, 0);
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);

		final MmtrDirectionalBlockService.Section eastSection = service.sectionOfSignal(eastLamp);
		final MmtrDirectionalBlockService.Section westSection = service.sectionOfSignal(westLamp);
		assertNotNull(eastSection, "the east-facing head at the west end starts a section");
		assertNotNull(westSection, "the west-facing head at the east end starts a section");
		assertEquals(line.getHexId(), eastSection.entryRailHex());
		assertEquals(line.getHexId(), westSection.entryRailHex());

		// Both cover the rail, but each is only seen by a movement going its way.
		assertEquals(100, eastSection.lengthM(), 1.5);
		assertEquals(100, westSection.lengthM(), 1.5);
		assertEquals(eastSection, service.sectionAt(line.getHexId(), 50, 1, 0), "eastbound -> the east-facing section");
		assertEquals(westSection, service.sectionAt(line.getHexId(), 50, -1, 0), "westbound -> the west-facing section");
		assertNull(service.sectionAt("missing", 50, 1, 0), "unknown rails are safe");
	}

	@Test
	public void aLampFacingAwayFromARailDoesNotBindToIt() {
		// The lamp stands on r1's rail but faces NORTH while the rail runs east-west: it must not be
		// bound to r1 (v1 used nearest-rail-only, so a lamp could bind to a rail behind it).
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-facing-away", r1);
		addLamp(simulator, r1, 50, NORTH);
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);

		assertEquals(0, service.sectionCount(), "a lamp facing across the rail protects no section of it");
	}

	@Test
	public void anExplicitTargetWinsOverGeometricInference() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(0, 0, 0), new Position(0, 0, 100));
		final Simulator simulator = sim("build/mmtr-dirblock-target", r1, r2);
		// A BOUND lamp on r2's line, bound to r1: the target must win, and its section covers r1.
		final int[] coords = blockCoordsAt(r2, 0);
		simulator.mmtrSignals.put(coords[0], coords[1], coords[2], EAST, 4, "BOUND", r1.getHexId());
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);

		final MmtrDirectionalBlockService.Section section = service.sectionOfSignal(
			MmtrSignalRegistry.key(coords[0], coords[1], coords[2]));
		assertNotNull(section, "the explicit target wins");
		assertEquals(r1.getHexId(), section.entryRailHex(), "the bound rail is the one protected");
	}
}
