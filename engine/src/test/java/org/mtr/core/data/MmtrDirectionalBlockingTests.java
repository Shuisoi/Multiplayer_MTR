package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.signal.MmtrSectionService;
import org.mtr.core.mmtr.signal.MmtrSignalRegistry;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.operation.MmtrDriveControl;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闭塞区间 v2 (S3): the S1 occupancy stop reads the <strong>directional, lamp-to-lamp</strong> section.
 *
 * <p>The behavioural difference against B2 is what these tests pin: a v2 section spans rail boundaries,
 * so a following train holds at the section boundary - the next lamp, which may be several rails away -
 * instead of stopping at every rail end it crosses. On rails no lamp reaches the directional model
 * answers "not my business" and the v1 per-rail rule still applies (covered by
 * {@link MmtrMotionBlockingTests}, which must stay green).</p>
 */
public final class MmtrDirectionalBlockingTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
		+ "}";
	/** MTR facing angles (Minecraft convention: south = 0 / west = 90 / north = 180 / east = 270). */
	private static final float EAST = 270;

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/**
	 * A straight double-rail corridor plus a yard and a parallel stub siding:
	 * <pre>
	 *   YARD (-12..0, z=0) -> RA (0..24) -> RB (24..48) -> RC (48..72)   [the corridor, z=0]
	 *   STUB (0..10, z=4)  -> LNK (10,4)-(10,0) -> joins RA at (10,0,0)   [the second train]
	 * </pre>
	 * One east-facing lamp stands on RC's start node (48,0,0). Because v2 sections run lamp-to-lamp, the
	 * section it starts covers RC only, and the rails behind it are NOT separated by any lamp - which is
	 * exactly the shape v1 could not express.
	 */
	private static final class CorridorNet {
		final Simulator sim;
		final Position yardBack = new Position(-12, 0, 0);
		final Position yardMouth = new Position(0, 0, 0);
		final Rail yard;
		final Rail ra;
		final Rail rb;
		final Rail rc;
		final Rail stub;
		final Rail link;
		final Siding yardSiding;
		final Siding stubSiding;
		/** Stop target 12 m into RC (physical x = 60): yard rear -12, so 72 from the yard rear. */
		final double targetM1 = 12 + 24 + 24 + 12;
		/** Same physical stop for the stub train (its own progress origin). */
		final double targetM2 = 22 + 24 + 24 + 12;
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = new ObjectArrayList<>();

		CorridorNet(String savePath) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			yard = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			ra = through(new Position(0, 0, 0), new Position(24, 0, 0));
			rb = through(new Position(24, 0, 0), new Position(48, 0, 0));
			rc = through(new Position(48, 0, 0), new Position(72, 0, 0));
			// The stub runs parallel to the corridor (z = 4) so its walker can actually leave the siding,
			// and joins RA through a short link at x = 10 - no right-angle dead end.
			stub = Rail.newSidingRail(new Position(0, 0, 4), Angle.fromAngle(0), new Position(10, 0, 4), Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			link = through(new Position(10, 0, 4), new Position(10, 0, 0));
			final Depot depot = new Depot(TransportMode.TRAIN, sim);
			yardSiding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);
			stubSiding = new Siding(new Position(0, 0, 4), new Position(10, 0, 4), 10, TransportMode.TRAIN, sim);
			depot.setName("Corridor Yard");
			depot.setCorners(new Position(-18, -3, -3), new Position(78, 3, 8));
			sim.rails.add(yard);
			sim.rails.add(ra);
			sim.rails.add(rb);
			sim.rails.add(rc);
			sim.rails.add(stub);
			sim.rails.add(link);
			sim.depots.add(depot);
			sim.sidings.add(yardSiding);
			sim.sidings.add(stubSiding);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			yardSiding.setVehicleCars(cars);
			stubSiding.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			yardSiding.tick();
			stubSiding.tick();
			trees.add(new Object2ObjectAVLTreeMap<>());
			trees.add(new Object2ObjectAVLTreeMap<>());
			// One east-facing lamp on the yard mouth: its section runs the whole corridor (RA -> RB -> RC),
			// which is the shape v1 could not express (v1's unit was one rail, so its "block ahead" was
			// always just the next rail).
			sim.mmtrSignals.put(0, 0, 0, EAST, 4, "AUTO", ra.getHexId());
		}

		Vehicle spawn(Siding siding) {
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, new BranchStore(), null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			return vehicle;
		}

		/**
		 * One deterministic 1000 ms tick. The sidings are stepped directly (the convention every other
		 * motion test in this package uses) and the SAME tree list is what the rules read, so an injected
		 * footprint is visible to the order-under test. {@code Simulator.tick()} is NOT used here: it
		 * advances by wall-clock difference, so a tight test loop moves the trains hardly at all.
		 */
		void tick() {
			trees.removeFirst();
			trees.add(new Object2ObjectAVLTreeMap<>());
			yardSiding.simulateVehicles(1000, trees);
			stubSiding.simulateVehicles(1000, trees);
		}

		/** A foreign train standing in RC, the third rail of the lamp's section (empty RA and RB). */
		void tickWithOccupancy() {
			final Position orderedP1 = rc.getPosition1().compareTo(rc.getPosition2()) <= 0 ? rc.getPosition1() : rc.getPosition2();
			final Position orderedP2 = orderedP1 == rc.getPosition1() ? rc.getPosition2() : rc.getPosition1();
			tick();
			// Inject into the PREVIOUS-tick tree (the one the rules read) so the footprint persists like a
			// parked train; the window is deliberately wide so a train creeping at a few metres per tick
			// can never step over it.
			Data.put(trees.get(1), orderedP1, orderedP2,
				vehiclePosition -> {
					final VehiclePosition newVehiclePosition = vehiclePosition == null ? new VehiclePosition() : vehiclePosition;
					newVehiclePosition.addSegment(2, 40, 999_999_002L);
					return newVehiclePosition;
				}, Object2ObjectAVLTreeMap::new);
		}

		void tickUntil(java.util.function.BooleanSupplier condition, int maxTicks) {
			for (int i = 0; i < maxTicks && !condition.getAsBoolean(); i++) {
				tick();
			}
			assertTrue(condition.getAsBoolean(), "condition not met within " + maxTicks + " ticks");
		}
	}

	@Test
	public void theSectionSpansTheWholeCorridorAndTheRailsInsideItAreNotBoundaries() {
		final CorridorNet n = new CorridorNet("build/mmtr-dirblocking-shape");
		final MmtrSectionService service = n.sim.mmtrSections;

		assertEquals(1, service.sectionCount(), "one lamp -> one directional section");
		final MmtrSectionService.Section section = service.sectionOfSignal(MmtrSignalRegistry.key(0, 0, 0));
		assertNotNull(section, "the lamp at the yard mouth starts the section");
		assertEquals(3, section.spans.size(),
			"the section spans RA, RB and RC: a lamp-to-lamp section crosses rail boundaries");
		assertEquals(n.ra.getHexId(), section.entryRailHex());
		assertEquals(n.rc.getHexId(), section.spans.get(2).railHex);
		assertTrue(section.endsAtDeadEnd, "no second lamp in this direction");

		// The rails INSIDE the section carry it, but they are not boundaries: both RA and RB are inside
		// the lamp's block.
		assertEquals(1, service.sectionsOfRail(n.rb.getHexId()).size(), "RB belongs to the lamp's section");
		assertNotNull(service.sectionAt(n.ra.getHexId(), 10, 1, 0), "eastbound on RA is inside the lamp's section");
		assertNotNull(service.sectionAt(n.rb.getHexId(), 10, 1, 0), "eastbound on RB is inside the same section");
		assertNull(service.sectionAt(n.rb.getHexId(), 10, -1, 0), "no westbound section covers the corridor");
	}

	@Test
	public void aTrainIsHeldAtTheLampWhenTheOccupiedStretchIsTwoRailsAhead() {
		final CorridorNet n = new CorridorNet("build/mmtr-dirblocking-hold");
		final Vehicle train = n.spawn(n.yardSiding);
		assertNotNull(train);

		// A foreign train stands in RC - the THIRD rail of the lamp's section, while RA and RB (the rails
		// immediately ahead) stay empty. Our train is still in the yard, which is OUTSIDE that section, so
		// the directional rule speaks as soon as the train is on the section's entry rail: it holds at the
		// occupancy face of the blocked stretch, 2 m into RC.
		train.setMmtrMotionAuto(true);
		train.setMmtrMotionStopTarget(n.targetM1, false);
		for (int i = 0; i < 1500 && train.getRailProgress() < 11.5; i++) {
			n.tickWithOccupancy();
		}

		// railProgress counts from the yard rear (x = -12): the occupancy face at 2 m into RC (x = 50) is
		// 62 m along, and the head stops 2 m short of it - i.e. 12 m from the yard rear, still 38 m before
		// the blocked rail's own entrance. The train roams the clear rails of its section but is never
		// admitted into the occupied stretch.
		assertTrue(train.getSpeed() < 0.01, "the occupied stretch holds the train: speed " + train.getSpeed());
		assertEquals(12.0, train.getRailProgress(), 1.0,
			"it rests at the occupancy face inside its section, never reaching the occupied rail");
		assertFalse(train.isMmtrMotionStoppedAtTarget(), "a blocked stop is not a task arrival");
	}
}
