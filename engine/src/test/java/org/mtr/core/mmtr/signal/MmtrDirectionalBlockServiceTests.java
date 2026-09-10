package org.mtr.core.mmtr.signal;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.VehiclePosition;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Vector;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

	// ---------------------------------------------------------------- S2: occupancy and queries

	/** An occupancy tree holding one vehicle footprint on {@code rail} between the two arcs. */
	private static ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> occupancy(Rail rail, double fromM, double toM) {
		final Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> tree = new Object2ObjectAVLTreeMap<>();
		final VehiclePosition vehiclePosition = new VehiclePosition();
		vehiclePosition.addSegment(fromM, toM, 1);
		final Position[] ordered = rail.mmtrOrderedPositions();
		final Object2ObjectAVLTreeMap<Position, VehiclePosition> inner = new Object2ObjectAVLTreeMap<>();
		inner.put(ordered[1], vehiclePosition);
		tree.put(ordered[0], inner);
		return ObjectArrayList.of(tree);
	}

	@Test
	public void occupancyProjectsOntoASectionThatSpansSeveralRails() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-occupancy", r1, r2);
		final String lamp = addLamp(simulator, r1, 0, EAST);
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);
		final MmtrDirectionalBlockService.Section section = service.sectionOfSignal(lamp);
		assertNotNull(section);
		assertEquals(2, section.spans.size(), "the section spans two rails");

		assertFalse(service.isOccupied(section, null), "no train -> not occupied (live trees are empty)");

		// A footprint in the SECOND rail of the section still occupies the whole section.
		assertTrue(service.isOccupied(section, occupancy(r2, 20, 40)),
			"a train standing in the far span occupies the section - this is the cross-rail win");
		// A footprint in the first rail does too.
		assertTrue(service.isOccupied(section, occupancy(r1, 10, 30)), "a train in the near span occupies it");
	}

	@Test
	public void aFootprintOutsideTheSectionDoesNotOccupyIt() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-occupancy-outside", r1);
		final String lamp = addLamp(simulator, r1, 40, EAST);
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);
		final MmtrDirectionalBlockService.Section section = service.sectionOfSignal(lamp);
		assertNotNull(section);
		assertEquals(40, section.entryArcM(), 1.0, "the section starts at the lamp (arc 40)");

		assertFalse(service.isOccupied(section, occupancy(r1, 0, 20)),
			"a train BEHIND the lamp is in the previous section, not this one");
		assertTrue(service.isOccupied(section, occupancy(r1, 50, 70)), "a train ahead of the lamp is in it");
	}

	@Test
	public void theSectionEndIsTheDistanceToItsOwnBoundary() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Rail r3 = rail(new Position(200, 0, 0), new Position(300, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-end-ahead", r1, r2, r3);
		addLamp(simulator, r1, 0, EAST);
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);

		// The one lamp's section covers all three rails (300 m); from arc 50 that is 250 m to its end.
		assertEquals(250, service.sectionEndAheadM(r1.getHexId(), 50, 1, 0), 2.0);
		assertEquals(Double.MAX_VALUE, service.sectionEndAheadM(r1.getHexId(), 50, -1, 0), 1e-9,
			"no section is directed westbound on this rail");
		assertEquals(Double.MAX_VALUE, service.sectionEndAheadM("missing", 50, 1, 0), 1e-9, "unknown rails are safe");
	}

	@Test
	public void sectionsChainThroughConsecutiveLamps() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Rail r3 = rail(new Position(200, 0, 0), new Position(300, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-chain", r1, r2, r3);
		final String first = addLamp(simulator, r1, 0, EAST);
		final String second = addLamp(simulator, r3, 0, EAST);
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);

		final MmtrDirectionalBlockService.Section a = service.sectionOfSignal(first);
		final MmtrDirectionalBlockService.Section b = service.sectionOfSignal(second);
		assertNotNull(a);
		assertNotNull(b);
		assertEquals(b, service.following(a), "the section beyond a lamp is the one that lamp starts");
		assertNull(service.following(b), "nothing follows the last section of the line");

		// While the near section is clear the movement is admitted to it; once occupied it waits for the
		// section beyond - which is exactly the rule S3 will wire into the S1 stop.
		assertEquals(a, service.sectionAhead(r1.getHexId(), 10, 1, 0, ObjectArrayList.of()));
		assertEquals(b, service.sectionAhead(r1.getHexId(), 10, 1, 0, occupancy(r1, 10, 30)));
		assertNull(service.sectionAhead(r1.getHexId(), 10, -1, 0, ObjectArrayList.of()), "no westbound section");
	}

	// ---------------------------------------------------------------- S4: display queries

	@Test
	public void aTrainInsideTheSectionReadsAsRedNotAsBlocksAway() {
		// The point of the display half of v2: the unit of the chain is the LAMP-TO-LAMP section, so a
		// train standing three rails ahead inside the same section means "the block I am about to enter is
		// occupied" = RED. The v1 per-rail chain would call that three blocks away (double yellow).
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Rail r3 = rail(new Position(200, 0, 0), new Position(300, 0, 0));
		final Rail r4 = rail(new Position(300, 0, 0), new Position(400, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-display", r1, r2, r3, r4);
		final String lamp = addLamp(simulator, r1, 0, EAST);
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);

		final MmtrDirectionalBlockService.Section section = service.sectionOfSignal(lamp);
		assertNotNull(section);
		assertEquals(4, section.spans.size(), "the lamp's section runs the whole corridor");

		// The lamp protects r1 entered from its west end.
		assertEquals(section, service.sectionProtecting(r1.getHexId(), new Position(0, 0, 0)),
			"entering r1 from the lamp's node is inside the lamp's section");
		assertNull(service.sectionProtecting(r1.getHexId(), new Position(100, 0, 0)),
			"entering r1 from the other end belongs to the opposite direction, not this section");
		assertNull(service.sectionProtecting("missing", new Position(0, 0, 0)), "unknown rails are safe");

		assertEquals(0, service.chainDepth(r1.getHexId(), new Position(0, 0, 0), ObjectArrayList.of(), key -> false, 3),
			"nothing occupied: the chain is clear");

		// A train on r3 - two rails beyond the next one - is still inside the lamp's own section: depth 1.
		assertEquals(1, service.chainDepth(r1.getHexId(), new Position(0, 0, 0), occupancy(r3, 20, 60), key -> false, 3),
			"a train inside the protected section is the next block, however many rails away it stands");

		assertEquals(1, service.chainDepth(r1.getHexId(), new Position(0, 0, 0), ObjectArrayList.of(),
			key -> key.equals(MmtrJunctionState.nodeKey(new Position(0, 0, 0))), 3),
			"an uncleared junction at the step's boundary restricts the same step (④)");
	}

	@Test
	public void theChainStepsToTheNextLampWhenThereIsOne() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Rail r3 = rail(new Position(200, 0, 0), new Position(300, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-display-chain", r1, r2, r3);
		final String first = addLamp(simulator, r1, 0, EAST);
		final String second = addLamp(simulator, r3, 0, EAST);
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);
		assertEquals(second, service.sectionOfSignal(first).exitSignalKey, "section 1 ends at lamp 2");

		// A train in the SECOND section is one step beyond the first: depth 2 = single yellow.
		assertEquals(2, service.chainDepth(r1.getHexId(), new Position(0, 0, 0), occupancy(r3, 10, 40), key -> false, 3),
			"the next lamp's own block occupied reads as a caution, not as red");
	}
}
