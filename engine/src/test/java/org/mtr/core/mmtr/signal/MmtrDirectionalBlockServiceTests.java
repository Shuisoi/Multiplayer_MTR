package org.mtr.core.mmtr.signal;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
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

	/**
	 * A straight rail from {@code p1} to {@code p2}. The two end bearings are the ACTUAL bearings of the
	 * p1 -> p2 axis, not 0/180: MTR's {@link Angle} is a bearing measured clockwise from EAST (see
	 * {@code Angle.fromAngle}), and handing it 0/180 for a rail running north-south collapses the rail
	 * (RailMath treats the ends as perpendicular to the axis and yields a zero-length rail), which makes a
	 * test silently exercise nothing.
	 */
	private static Rail rail(Position p1, Position p2) {
		final double bearing = Math.toDegrees(Math.atan2(p2.getZ() - p1.getZ(), p2.getX() - p1.getX()));
		return rail(p1, Angle.fromAngle((float) bearing), p2, Angle.fromAngle((float) (bearing + 180)));
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
		return occupancy(rail, fromM, toM, 1);
	}

	/** As above, with an explicit owner id (so self-exclusion can be exercised). */
	private static ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> occupancy(Rail rail, double fromM, double toM, long vehicleId) {
		final Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> tree = new Object2ObjectAVLTreeMap<>();
		final VehiclePosition vehiclePosition = new VehiclePosition();
		vehiclePosition.addSegment(fromM, toM, vehicleId);
		final Position[] ordered = rail.mmtrOrderedPositions();
		final Object2ObjectAVLTreeMap<Position, VehiclePosition> inner = new Object2ObjectAVLTreeMap<>();
		inner.put(ordered[1], vehiclePosition);
		tree.put(ordered[0], inner);
		return ObjectArrayList.of(tree);
	}

	/**
	 * A train must never be held by ITS OWN body shadow (live defect, notes/112 §4).
	 *
	 * <p>Measured on the dev world: a train parked at offset 5.46 on a 43 m rail had written its own
	 * footprint as [5.5, 37.5) - the shadow's anchor sat ahead of its head - so S1 read a 0.04 m block stop
	 * (the stop point at the train's own feet, where the throttle does nothing) and the AWS rule read its
	 * own shadow as a RED signal ahead and stopped the train dead the moment the driver touched the
	 * throttle. The rule now ignores the asking vehicle's own footprints while still seeing everyone
	 * else's.</p>
	 */
	@Test
	public void aTrainIsNeverHeldByItsOwnFootprint() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(60, 0, 0));
		final Rail r2 = rail(new Position(60, 0, 0), new Position(120, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-self", r1, r2);
		final String lamp = addLamp(simulator, r1, 0, EAST);
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);
		assertNotNull(service.sectionOfSignal(lamp));

		final long me = 4242L;
		// My own footprint covering the very stretch the rule inspects: must NOT block me.
		assertEquals(Double.MAX_VALUE, service.sectionBoundaryAheadM(r1.getHexId(), 10, 1, 0, occupancy(r1, 12, 40, me), me), 1e-9,
			"a train's own footprint is not an obstruction ahead of it");
		assertFalse(service.isOccupied(service.sectionOfSignal(lamp), occupancy(r1, 12, 40, me), me),
			"nor does it make its own block read occupied (the AWS trigger asks the same question)");
		assertEquals(0, service.chainDepth(r1.getHexId(), new Position(0, 0, 0), occupancy(r1, 12, 40, me), key -> false, 3, me), 1e-9,
			"and the aspect chain stays clear for it");

		// Someone ELSE's footprint in the same place still blocks, as it must.
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> other = occupancy(r1, 12, 40, me + 1);
		assertTrue(service.isOccupied(service.sectionOfSignal(lamp), other, me), "another train in my block is still an obstruction");
		assertEquals(1, service.chainDepth(r1.getHexId(), new Position(0, 0, 0), other, key -> false, 3, me),
			"another train in my block still reads red for me");
		// The stop rule also still holds me at that face (12 - the head at 10 is 2 m short of it).
		assertEquals(2.0, service.sectionBoundaryAheadM(r1.getHexId(), 10, 1, 0, other, me), 0.01,
			"a foreign footprint is held at its face, exactly as before");
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

	@Test
	public void atAForkTheSectionCoversEveryLegAndNarrowsToTheRouteWhenOneIsSet() {
		// 岔口多腿 (user ruling 2026-09-10): a lamp at a yard throat protects the WHOLE throat, not just the
		// leg it happens to face. Without a route every forward leg is the same block; with a MAIN route
		// set through the throat the block narrows to that route's own next rail.
		final Rail throat = rail(new Position(0, 0, 0), new Position(50, 0, 0));
		final Rail straight = rail(new Position(50, 0, 0), new Position(100, 0, 0));
		final Rail diverge = rail(new Position(50, 0, 0), new Position(100, 0, 12));
		final Simulator simulator = sim("build/mmtr-dirblock-fork-legs", throat, straight, diverge);
		final String lamp = addLamp(simulator, throat, 0, EAST);
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);

		final MmtrDirectionalBlockService.Section unrouted = service.sectionOfSignal(lamp);
		assertNotNull(unrouted);
		assertEquals(3, unrouted.spans.size(),
			"no route set: the throat block covers the approach AND both legs (they are one block)");

		// A MAIN route through the throat onto the straight leg narrows the walk to that leg.
		final ObjectArrayList<String> rails = new ObjectArrayList<>();
		rails.add(throat.getHexId());
		rails.add(straight.getHexId());
		simulator.mmtrRoutes.request(new org.mtr.core.mmtr.route.MmtrRoute(1L, "test", org.mtr.core.mmtr.route.MmtrRoute.Kind.MAIN,
			rails, null, straight.getHexId(), 0L));
		final MmtrDirectionalBlockService.Section routed = new MmtrDirectionalBlockService(simulator).sectionOfSignal(lamp);
		assertNotNull(routed);
		assertEquals(2, routed.spans.size(), "a set MAIN route narrows the throat block to its own leg");
		assertEquals(straight.getHexId(), routed.spans.get(1).railHex, "and it is the route's leg that is walked");
	}

	// ---------------------------------------------------------------- S6: 水闸区间 (the layer the map draws)

	/** Register a BOUND lamp on {@code rail} at {@code arcM} facing {@code angle}, bound to {@code target}. */
	private static String addBoundLamp(Simulator simulator, Rail rail, double arcM, float angle, Rail target) {
		final int[] coords = blockCoordsAt(rail, arcM);
		simulator.mmtrSignals.put(coords[0], coords[1], coords[2], angle, 4, "BOUND", target.getHexId());
		return MmtrSignalRegistry.key(coords[0], coords[1], coords[2]);
	}

	private static MmtrDirectionalBlockService.GateBlock blockWithLamp(ObjectArrayList<MmtrDirectionalBlockService.GateBlock> blocks, String lamp) {
		for (final MmtrDirectionalBlockService.GateBlock block : blocks) {
			if (block.entryLampKey.equals(lamp)) {
				return block;
			}
		}
		return null;
	}

	/** The block that owns {@code (arcFrom, arcTo)} of {@code rail}, or null - used to prove a clean partition. */
	private static MmtrDirectionalBlockService.GateBlock blockAt(ObjectArrayList<MmtrDirectionalBlockService.GateBlock> blocks, String railHex, double arcM) {
		for (final MmtrDirectionalBlockService.GateBlock block : blocks) {
			for (final MmtrDirectionalBlockService.RailSpan span : block.spans) {
				if (span.railHex.equals(railHex) && arcM >= span.arcFromM - 1e-6 && arcM <= span.arcToM + 1e-6) {
					return block;
				}
			}
		}
		return null;
	}

	/**
	 * 水闸区间 (S6, user definition 2026-09-10): the block layer is bounded by SIGNALS only.
	 *
	 * <p>A block runs from the lamp that faces into it to the next lamp, <em>across</em> rail boundaries -
	 * the node between the two rails is part of the TRACK layer and must never appear in this one. Two
	 * lamps on a three-rail corridor therefore give exactly two blocks, not "one block per rail".</p>
	 */
	@Test
	public void blocksRunLampToLampAndIgnoreRailBoundaries() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Rail r3 = rail(new Position(200, 0, 0), new Position(300, 0, 0));
		final Simulator simulator = sim("build/mmtr-gate-lamp-to-lamp", r1, r2, r3);
		final String first = addLamp(simulator, r1, 0, EAST);
		final String second = addLamp(simulator, r3, 0, EAST);
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);

		final ObjectArrayList<MmtrDirectionalBlockService.GateBlock> blocks = service.gateBlocks();
		assertEquals(2, blocks.size(), "one block per lamp - the rail boundary between r1/r2 is NOT a boundary here");

		final MmtrDirectionalBlockService.GateBlock a = blockWithLamp(blocks, first);
		final MmtrDirectionalBlockService.GateBlock b = blockWithLamp(blocks, second);
		assertNotNull(a, "the first lamp opens a block");
		assertNotNull(b, "the second lamp opens the next one");
		assertEquals(2, a.spans.size(), "the first block covers r1 AND r2: it crosses the node at z=100");
		assertEquals(200, a.lengthM(), 1.5);
		assertFalse(a.endsOpen, "it closes on the next lamp, it does not run out");
		assertEquals(r1.getHexId(), a.spans.get(0).railHex);
		assertEquals(r2.getHexId(), a.spans.get(1).railHex);

		assertEquals(1, b.spans.size(), "the second block is the last rail");
		assertEquals(r3.getHexId(), b.spans.get(0).railHex);
		assertTrue(b.endsOpen, "nothing closes it: the walk ran to the end of the line");

		// The law of the layer: nodes do not cut it. A movement standing mid-way through the first block
		// is in ONE block whichever rail it is on.
		assertEquals(a, blockAt(blocks, r1.getHexId(), 50), "on r1 -> block a");
		assertEquals(a, blockAt(blocks, r2.getHexId(), 50), "across the node on r2 -> still block a");
		assertEquals(b, blockAt(blocks, r3.getHexId(), 50), "past the second lamp -> block b");
	}

	/**
	 * A rail that no walk reaches carries no lamp at all, so it is a block of its own - the
	 * "无信号灯的自己成一个区间" case. It is deliberately NOT merged with its neighbours: merging would
	 * need a node, and nodes belong to the track layer.
	 */
	@Test
	public void anUnguardedRailIsABlockOfItsOwn() {
		final Rail guarded = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		// The siding stands well clear of the guarded rail so the lamp cannot bind to it: this test is
		// about the block layer, not about the 3 m bind tolerance (notes/105 §3.1).
		final Rail siding = rail(new Position(500, 0, 500), new Position(500, 0, 600));		final Simulator simulator = sim("build/mmtr-gate-no-lamp", guarded, siding);
		final String lamp = addLamp(simulator, guarded, 0, EAST);
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);

		final ObjectArrayList<MmtrDirectionalBlockService.GateBlock> blocks = service.gateBlocks();
		assertEquals(2, blocks.size(), "the lamp's block plus the lamp-free rail's own block");

		final MmtrDirectionalBlockService.GateBlock guardedBlock = blockWithLamp(blocks, lamp);
		assertNotNull(guardedBlock);
		assertEquals(guarded.getHexId(), guardedBlock.spans.get(0).railHex);

		final MmtrDirectionalBlockService.GateBlock orphan = blockAt(blocks, siding.getHexId(), 25);
		assertNotNull(orphan, "a rail with no lamp is a block by itself");
		assertTrue(orphan.entryLampKey.isEmpty(), "nobody opens it - there is no lamp on it");
		assertTrue(orphan.endsOpen, "and nothing closes it");
		assertEquals(100, orphan.lengthM(), 1.0, "the whole rail, not a stub");
		// Unguarded blocks need names of their own: the node assignment and the map colours key on the id,
		// so "no lamp" cannot be one shared name for every unguarded block in the world.
		final ObjectOpenHashSet<String> ids = new ObjectOpenHashSet<>();
		for (final MmtrDirectionalBlockService.GateBlock block : blocks) {
			assertTrue(ids.add(block.id), "block id " + block.id + " is not unique");
		}
		assertFalse(orphan.id.isEmpty(), "an unguarded block still carries a stable id");
	}

	/**
	 * The direction of the lamp decides whose cell the track falls in - a lamp only guards the side it
	 * FACES (user: 反向没放灯啊). Two lamps on one 200 m rail therefore give two cells, and the one the
	 * movement has already passed belongs to the lamp BEHIND it.
	 */
	@Test
	public void aLampGuardsTheSideItFaces() {
		// 200 m, not 100: two lamps 50 m apart would land on the same block coordinate and one registry
		// entry would overwrite the other, which is a test-geometry mistake, not a model one.
		final Rail line = rail(new Position(0, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = sim("build/mmtr-gate-facing", line);
		// Mid-rail facing east: it protects the stretch ahead of it.
		final String eastLamp = addBoundLamp(simulator, line, 50, EAST, line);
		// At the far end facing west: it protects the stretch it faces, up to the east-facing head.
		final String westLamp = addBoundLamp(simulator, line, 150, WEST, line);
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);

		final ObjectArrayList<MmtrDirectionalBlockService.GateBlock> blocks = service.gateBlocks();
		final MmtrDirectionalBlockService.GateBlock eastBlock = blockWithLamp(blocks, eastLamp);
		final MmtrDirectionalBlockService.GateBlock westBlock = blockWithLamp(blocks, westLamp);
		assertNotNull(eastBlock, "the east-facing lamp opens a block");
		assertNotNull(westBlock, "the west-facing lamp opens the one it faces");

		assertEquals(50, eastBlock.spans.get(0).arcFromM, 0.5, "the east-facing lamp protects only what is ahead of it");
		assertEquals(150, eastBlock.lengthM(), 1.0, "which is everything east of it: no other head faces east");
		assertEquals(0, westBlock.spans.get(0).arcFromM, 0.5, "the west-facing lamp protects the stretch behind it");
		assertEquals(50, westBlock.lengthM(), 1.0, "which ends where the east-facing head takes over");
		// Same rail, two cells: this is what "directed" means on the map, and it is why the layer is drawn
		// per lamp rather than per rail.
		assertEquals(2, blocks.size());
	}

	/**
	 * The block layer must be a clean division of the line: every metre of every rail belongs to exactly
	 * one block. (This is the property the earlier rail-cut partition failed: it produced 137 fragments
	 * with 64 overlapping pairs on the dev world - notes/112 §3.1, abandoned in S6.)
	 */
	@Test
	public void theBlocksDivideEveryRailWithoutGapsOrOverlaps() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Rail branch = rail(new Position(100, 0, 0), new Position(200, 0, 100));
		final Rail siding = rail(new Position(0, 0, 400), new Position(0, 0, 500));
		final Simulator simulator = sim("build/mmtr-gate-partition", r1, r2, branch, siding);
		addLamp(simulator, r1, 0, EAST);
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);

		final ObjectArrayList<MmtrDirectionalBlockService.GateBlock> blocks = service.gateBlocks();
		final ObjectArrayList<Rail> rails = ObjectArrayList.of(r1, r2, branch, siding);

		for (final Rail rail : rails) {
			final double length = rail.railMath.getLength();
			for (final MmtrDirectionalBlockService.GateBlock block : blocks) {
				for (final MmtrDirectionalBlockService.RailSpan span : block.spans) {
					if (span.railHex.equals(rail.getHexId())) {
						assertTrue(span.arcFromM >= -1e-6 && span.arcToM <= length + 1e-6,
							"a span never runs off its rail: " + span);
					}
				}
			}
			// Walk the rail and ask which block owns each metre: it must always be exactly one.
			for (double arc = 0.05; arc < length; arc += 5) {
				int owners = 0;
				for (final MmtrDirectionalBlockService.GateBlock block : blocks) {
					for (final MmtrDirectionalBlockService.RailSpan span : block.spans) {
						if (span.railHex.equals(rail.getHexId()) && arc >= span.arcFromM - 1e-6 && arc <= span.arcToM + 1e-6) {
							owners++;
						}
					}
				}
				assertEquals(1, owners, "at arc " + Math.round(arc) + " of rail " + rail.getHexId()
					+ " exactly one block must own the track (0 = a gap the map would show as unassigned, 2+ = the overlap defect)");
			}
		}
	}

	/**
	 * A block must name each rail ONCE per direction, whether the walk followed one branch or several.
	 *
	 * <p>The walk follows every leg that keeps the travel direction (岔口多腿), and each branch then walks
	 * the SAME trunk rails, so a shared stretch could be appended once per branch. Measured on the dev
	 * world: 103 of 448 spans were such exact duplicates - one lamp's block claimed 37 rails of which 17
	 * were second copies of the same track - which is what made the 区间图层 draw phantom fragments on top
	 * of each other (notes/113 §3).</p>
	 */
	@Test
	public void aBlockListsEachRailOnlyOnce() {
		final Rail throat = rail(new Position(0, 0, 0), new Position(0, 0, 100));
		final Rail straight = rail(new Position(0, 0, 100), new Position(0, 0, 250));
		final Rail diverge = rail(new Position(0, 0, 100), new Position(40, 0, 250));
		final Simulator simulator = sim("build/mmtr-gate-fork-once", throat, straight, diverge);
		final String lamp = addLamp(simulator, throat, 0, NORTH);
		final MmtrDirectionalBlockService unrouted = new MmtrDirectionalBlockService(simulator);

		// No route set: the throat block is the whole fan (岔口多腿).
		final MmtrDirectionalBlockService.GateBlock fan = blockWithLamp(unrouted.gateBlocks(), lamp);
		assertNotNull(fan, "the lamp opens the throat block");
		assertEquals(3, fan.spans.size(), "the throat and both legs, each named once");
		assertNoDuplicateSpans(fan);

		// With a MAIN route through the throat the block narrows to the route's own leg - and again each
		// rail appears once.
		final ObjectArrayList<String> rails = new ObjectArrayList<>();
		rails.add(throat.getHexId());
		rails.add(diverge.getHexId());
		simulator.mmtrRoutes.request(new org.mtr.core.mmtr.route.MmtrRoute(1L, "test", org.mtr.core.mmtr.route.MmtrRoute.Kind.MAIN,
			rails, null, diverge.getHexId(), 0L));
		final MmtrDirectionalBlockService routed = new MmtrDirectionalBlockService(simulator);
		final MmtrDirectionalBlockService.GateBlock narrowed = blockWithLamp(routed.gateBlocks(), lamp);
		assertNotNull(narrowed);
		assertEquals(2, narrowed.spans.size(), "the route's leg only");
		assertEquals(diverge.getHexId(), narrowed.spans.get(1).railHex);
		assertNoDuplicateSpans(narrowed);
	}

	/** Assert no rail is listed twice in the same travel direction (the phantom-span defect). */
	private static void assertNoDuplicateSpans(MmtrDirectionalBlockService.GateBlock block) {
		final ObjectOpenHashSet<String> seen = new ObjectOpenHashSet<>();
		for (final MmtrDirectionalBlockService.RailSpan span : block.spans) {
			assertTrue(seen.add(span.railHex),
				"rail " + span.railHex + " is listed twice: a shared stretch was added once per branch");
		}
	}

	/**
	 * Every TRACK-layer node belongs to exactly ONE block (user requirement 2026-09-10:
	 * "要求每个轨道层每个节点都都有且只有一个区间层所属").
	 *
	 * <p>A node is a single physical point that several rails meet at, so it needs exactly one owner: the
	 * layer must be a division, not a set of overlapping reaches. This also pins the other half of the same
	 * property - the reaches of two lamps in a ladder used to contain each other (287 overlapping pairs on
	 * the dev world), and each cell is now clipped where the nearer lamp takes over.</p>
	 */
	@Test
	public void everyNodeBelongsToExactlyOneBlock() {
		final Rail throat = rail(new Position(0, 0, 0), new Position(0, 0, 100));
		final Rail straight = rail(new Position(0, 0, 100), new Position(0, 0, 250));
		final Rail diverge = rail(new Position(0, 0, 100), new Position(40, 0, 250));
		final Rail siding = rail(new Position(0, 0, 100), new Position(-60, 0, 250));
		final Simulator simulator = sim("build/mmtr-gate-node-owner", throat, straight, diverge, siding);
		final String lamp = addLamp(simulator, throat, 0, NORTH);
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);

		final Object2ObjectOpenHashMap<String, String> owners = service.nodeOwners();
		// Every endpoint of every rail is a node in this layer's terms, the dead ends included: the throat's
		// two ends, the fork, and the far end of each of the three legs.
		assertEquals(5, owners.size(), "the fork's node plus one far node per leg, plus the throat's near node");
		assertEquals(owners, service.nodeOwners(), "asking twice gives the same answer (no order dependence)");
		for (final java.util.Map.Entry<String, String> entry : owners.entrySet()) {
			assertNotNull(entry.getValue(), "node " + entry.getKey() + " must belong to a block");
		}
		// The throat lamp's cell starts at the throat's near node, so both the near node and the fork node
		// are its: the cell runs across the rail boundary, which is the whole point of the model.
		assertEquals(lamp, owners.get("0,0,0"), "the node under the lamp is the entry of its own cell");
		assertEquals(lamp, owners.get("0,0,100"), "the fork node is inside the lamp's cell, not a boundary of it");
	}

	/**
	 * The game-side BIND tool must resolve the same rail the section model does (notes/111): it used to
	 * re-implement the facing maths with a "the renderer applies a 90 degree offset" assumption, and a
	 * bind tool that disagrees with the model by a quarter turn is how a light ends up bound to a rail
	 * running ACROSS its facing - the dead binding at {@code -163,-60,-189} (notes/105 §3.1).
	 */
	@Test
	public void theBindToolChoosesTheRailTheSectionModelWouldProtect() {
		final Rail westbound = rail(new Position(-50, 0, 0), new Position(0, 0, 0));
		final Rail eastbound = rail(new Position(0, 0, 0), new Position(50, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-bind", westbound, eastbound);

		// A lamp standing on the shared node, facing EAST: it must bind the rail that leaves east.
		// (The op's boolean is "the registry CHANGED", so the assertion is on the resulting BOUND target.)
		simulator.mmtrSignalBindAtNode(0, 0, 0, EAST, 4, 0, 0, 0);
		final MmtrSignalRegistry.SignalEntry registered = simulator.mmtrSignals.get(0, 0, 0);
		assertNotNull(registered, "the bind tool must register the lamp");
		assertEquals(eastbound.getHexId(), registered.target,
			"a lamp facing east binds the eastbound rail, not the one running across it");
		assertEquals("BOUND", registered.mode, "a bind with a target is a covered bind");

		// The other direction of the same node binds the other rail - the two heads are independent.
		simulator.mmtrSignalBindAtNode(0, 0, 0, WEST, 4, 0, 0, 0);
		assertEquals(westbound.getHexId(), simulator.mmtrSignals.get(0, 0, 0).target,
			"a lamp facing west binds the westbound rail");
	}
}
