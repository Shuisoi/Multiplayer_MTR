package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.mmtr.signal.MmtrShuntAuthority.Kind;
import org.mtr.core.operation.MmtrDriveControl;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C4: the coupling surgery on a real world - a locomotive drives up to a stabled rake under a
 * 调车授权 and the two become ONE train.
 *
 * <p>Pinned here: the merged formation is the leading train's cars followed by the trailing train's
 * cars (MTR car 0 = front, so the merged list reads front to back), the merged train keeps the
 * leading train's position and walker (nothing moves), the trailing vehicle is unregistered, the
 * crew is transferred with rebased car indices, the authority is consumed, and every gate refuses a
 * coupling that real rules would refuse.</p>
 */
public final class MmtrCoupleSurgeryTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"loco\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5,\"massRatio\":2.0},"
		+ "{\"id\":\"wagon\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5,\"massRatio\":1.0}]"
		+ "}";

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> newTrees() {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = new ObjectArrayList<>();
		trees.add(new Object2ObjectAVLTreeMap<>());
		trees.add(new Object2ObjectAVLTreeMap<>());
		return trees;
	}

	/** Y2 (loco siding) -> X2 -> Y1 (rake siding) -> MA -> PL. */
	private static final class Net {
		final Simulator sim;
		final Position y2Back = new Position(-30, 0, 0);
		final Position y2Mouth = new Position(-18, 0, 0);
		final Position y1Back = new Position(-12, 0, 0);
		final Position y1Mouth = new Position(0, 0, 0);
		final Rail y2;
		final Rail x2;
		final Rail y1;
		final Rail ma;
		final Rail pl;
		final Depot depot;
		final Siding siding1;
		final Siding siding2;
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = newTrees();

		Net(String savePath) {
			this(savePath, 12);
		}

		/** @param siding1Length the siding's DECLARED length (the physical rail y1 stays 12 m) */
		Net(String savePath, int siding1Length) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			y2 = Rail.newSidingRail(y2Back, Angle.fromAngle(0), y2Mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			x2 = through(y2Mouth, y1Back);
			y1 = Rail.newSidingRail(y1Back, Angle.fromAngle(0), y1Mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			ma = through(y1Mouth, new Position(16, 0, 0));
			pl = through(new Position(16, 0, 0), new Position(60, 0, 0));
			depot = new Depot(TransportMode.TRAIN, sim);
			siding1 = new Siding(y1Back, y1Mouth, siding1Length, TransportMode.TRAIN, sim);
			siding2 = new Siding(y2Back, y2Mouth, 12, TransportMode.TRAIN, sim);
			depot.setName("Couple Yard");
			depot.setCorners(new Position(-35, -3, -3), new Position(65, 3, 3));
			sim.rails.add(y2);
			sim.rails.add(x2);
			sim.rails.add(y1);
			sim.rails.add(ma);
			sim.rails.add(pl);
			sim.depots.add(depot);
			sim.sidings.add(siding1);
			sim.sidings.add(siding2);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "loco";
			sim.sync();
			siding1.tick();
			siding2.tick();
		}

		/** Stage {@code cars} as the stock of a siding, then spawn it in motion mode. */
		Vehicle spawn(Siding siding, ObjectArrayList<VehicleCar> cars) {
			siding.setVehicleCars(cars);
			siding.clearParkedVehicles();
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, new BranchStore(), null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			return vehicle;
		}

		void tick() {
			trees.removeFirst();
			trees.add(new Object2ObjectAVLTreeMap<>());
			siding1.simulateVehicles(1000, trees);
			siding2.simulateVehicles(1000, trees);
		}

		void tickUntil(BooleanSupplier condition, int maxTicks) {
			for (int i = 0; i < maxTicks && !condition.getAsBoolean(); i++) {
				tick();
			}
			assertTrue(condition.getAsBoolean(), "condition not met within " + maxTicks + " ticks");
		}
	}

	private static ObjectArrayList<VehicleCar> cars(String vehicleId, int count, boolean powered, String consistTypeId) {
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		for (int i = 0; i < count; i++) {
			cars.add(new VehicleCar(vehicleId, 2, 1, 10, 0, 1, 0.1, 0.1, powered, consistTypeId));
		}
		return cars;
	}

	private static void boardDriver(Simulator sim, Vehicle v, int throttle) {
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(entities);
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(throttle).setReverser(1), driver).apply(sim);
	}

	/** Rake (2 wagons) stabled on y1; loco (1 car) driven up to it under a 调车授权. */
	private static final class Approached {
		final Net n = new Net("build/mmtr-couple-surgery");
		final Vehicle rake;
		final Vehicle loco;

		Approached() {
			rake = n.spawn(n.siding1, cars("wagon", 2, false, "wagon"));
			loco = n.spawn(n.siding2, cars("loco", 1, true, "loco"));
			n.tick();
			n.tick();
			n.sim.mmtrShuntAuthorities.grant(loco.getId(), n.x2.getHexId(), n.y1.getHexId(), Kind.SUBSIDIARY_SHUNT, 0, 10 * 60 * 1000L);
			boardDriver(n.sim, loco, 3);
			n.tickUntil(() -> n.y1.getHexId().equals(loco.getMmtrMotionWalker().railHex()) && loco.getSpeed() == 0, 4000);
		}
	}

	@Test
	public void couplingMergesTheRakeAndTheLocoIntoOneTrain() {
		final Approached a = new Approached();
		final double rakeHeadBefore = a.rake.getMmtrMotionWalker().offsetM();
		final double locoHeadBefore = a.loco.getMmtrMotionWalker().offsetM();
		assertTrue(locoHeadBefore < rakeHeadBefore, "the loco drew up behind the rake");

		final MmtrCoupleSurgery.Result result = MmtrCoupleSurgery.couple(a.n.sim, a.loco.getId(), a.rake.getId());
		assertTrue(result.ok(), result.reason());
		final Vehicle merged = result.vehicle();
		assertNotNull(merged);		assertEquals(3, result.mergedCarCount(), "2 rake cars + 1 loco car");
		assertEquals(3, merged.vehicleExtraData.immutableVehicleCars.size());

		// Car 0 = the front of the train: the rake leads physically, so its cars come first, then the loco.
		assertEquals("wagon", merged.vehicleExtraData.immutableVehicleCars.get(0).getVehicleId());
		assertEquals("wagon", merged.vehicleExtraData.immutableVehicleCars.get(1).getVehicleId());
		assertEquals("loco", merged.vehicleExtraData.immutableVehicleCars.get(2).getVehicleId());
		assertTrue(merged.vehicleExtraData.immutableVehicleCars.get(2).getMmtrPowered(), "the loco keeps its traction");
		assertFalse(merged.vehicleExtraData.immutableVehicleCars.get(0).getMmtrPowered(), "the wagons stay unpowered");
		assertEquals("wagon", merged.vehicleExtraData.immutableVehicleCars.get(0).getMmtrConsistTypeId());

		// Nothing moved: the merged train stands exactly where the rake stood.
		assertEquals(rakeHeadBefore, merged.getMmtrMotionWalker().offsetM(), 1e-9, "the leading train's position is kept");
		assertEquals(a.n.y1.getHexId(), merged.getMmtrMotionWalker().railHex());
		assertEquals(0, merged.getSpeed(), 1e-9);

		// The two originals are gone as separate trains; the leading train's identity survives as the
		// merged train (clients keep their mirror and just see the formation grow).
		assertSame(merged, a.n.sim.mmtrFindVehicle(a.rake.getId()), "the merged train keeps the leading train's id");
		assertNull(a.n.sim.mmtrFindVehicle(a.loco.getId()), "the loco vehicle is unregistered");
		assertSame(merged, a.n.siding1.getVehicleById(merged.getId()), "the merged train is registered on the leading train's siding");

		// The authority was consumed by the coupling.
		assertNull(a.n.sim.mmtrShuntAuthorities.active(a.loco.getId()), "the 调车授权 is consumed");
		assertFalse(a.n.sim.mmtrShuntAuthorities.allowsCoexistence(a.n.y1.getHexId()), "the section is one train again");
	}

	@Test
	public void theMergedTrainKeepsRunningAndCarriesTheCrew() {
		final Approached a = new Approached();
		final MmtrCoupleSurgery.Result result = MmtrCoupleSurgery.couple(a.n.sim, a.loco.getId(), a.rake.getId());
		assertTrue(result.ok(), result.reason());
		final Vehicle merged = result.vehicle();

		// The driver rode the loco (car 0 of the loco) and now rides merged car 2.
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		merged.vehicleExtraData.iterateRidingEntities(entities::add);
		assertEquals(1, entities.size(), "the crew came across with the merge");
		assertEquals(2, entities.get(0).getRidingCar(), "the loco's car 0 became merged car 2");
		assertTrue(entities.get(0).isDriver());

		// The throttle override itself does not transfer (the driver's cab is now inside the merged
		// formation, and the override is a per-vehicle state). The crew re-applies control and the
		// merged train runs - that is the honest post-coupling workflow.
		assertFalse(merged.isMmtrManualOverride(), "a rebuild does not silently inherit a throttle");
		new MmtrDriveControl(merged.getId(), new ControlState().setThrottleNotch(3).setReverser(1), entities.get(0).uuid).apply(a.n.sim);
		assertTrue(merged.isMmtrManualOverride(), "the crew (still riding as driver) takes control again");
		final double before = merged.getRailProgress();
		for (int i = 0; i < 40; i++) {
			a.n.tick();
		}
		assertTrue(merged.getRailProgress() > before, "the merged train moves under its driver");
	}

	@Test
	public void theGatesRefuseAnIllegalCoupling() {
		// The authority must be live at the moment of coupling: revoking it after the approach stops
		// the move, even with the two trains nose to tail.
		final Approached a = new Approached();
		assertTrue(a.n.sim.mmtrShuntAuthorities.revoke(a.loco.getId()), "the approach left a live authority");
		final MmtrCoupleSurgery.Result noAuthority = MmtrCoupleSurgery.couple(a.n.sim, a.loco.getId(), a.rake.getId());
		assertFalse(noAuthority.ok());
		assertTrue(noAuthority.reason().contains("调车授权"), "reason: " + noAuthority.reason());

		// With a live authority but the trains still on different rails, the couplers cannot touch.
		final Net n1 = new Net("build/mmtr-couple-gate-distance");
		final Vehicle rake1 = n1.spawn(n1.siding1, cars("wagon", 2, false, "wagon"));
		final Vehicle loco1 = n1.spawn(n1.siding2, cars("loco", 1, true, "loco"));
		n1.tick();
		n1.tick();
		boardDriver(n1.sim, loco1, 3);
		n1.tickUntil(() -> n1.x2.getHexId().equals(loco1.getMmtrMotionWalker().railHex()) && loco1.getSpeed() == 0, 4000);
		n1.sim.mmtrShuntAuthorities.grant(loco1.getId(), n1.x2.getHexId(), n1.y1.getHexId(), Kind.SUBSIDIARY_SHUNT, 0, 600_000);
		final MmtrCoupleSurgery.Result tooFar = MmtrCoupleSurgery.couple(n1.sim, loco1.getId(), rake1.getId());
		assertFalse(tooFar.ok());
		assertTrue(tooFar.reason().contains("未接触") || tooFar.reason().contains("同一条轨"), "reason: " + tooFar.reason());

		// Both trains must be stopped: a moving train can never be coupled. (The loco is held at the
		// coupler gap by the occupancy rule, so the rake is the one that rolls - its own direction is
		// away from the loco, which is exactly what the gate must refuse.)
		final Approached b = new Approached();
		assertNotNull(b.n.sim.mmtrShuntAuthorities.active(b.loco.getId()), "the approach left a live authority");
		boardDriver(b.n.sim, b.rake, 4);
		b.n.tickUntil(() -> b.rake.getSpeed() > 1e-9, 50);
		assertTrue(b.rake.getSpeed() > 1e-9, "the rake is rolling");
		final MmtrCoupleSurgery.Result moving = MmtrCoupleSurgery.couple(b.n.sim, b.loco.getId(), b.rake.getId());
		assertFalse(moving.ok());
		assertTrue(moving.reason().contains("停稳"), "reason: " + moving.reason());
	}

	/** Couples the loco onto the rake and returns the merged (3-car) train. */
	private static Vehicle coupled(final Approached a) {
		final MmtrCoupleSurgery.Result result = MmtrCoupleSurgery.couple(a.n.sim, a.loco.getId(), a.rake.getId());
		assertTrue(result.ok(), result.reason());
		return result.vehicle();
	}

	@Test
	public void uncouplingCutsTheFormationAtTheSeam() {
		final Approached a = new Approached();
		final Vehicle merged = coupled(a);
		assertEquals(3, merged.vehicleExtraData.immutableVehicleCars.size());
		assertTrue(merged.vehicleExtraData.immutableVehicleCars.get(1).getMmtrCouplerAfter(), "the joint after the rake is a coupler seam");
		assertFalse(merged.vehicleExtraData.immutableVehicleCars.get(0).getMmtrCouplerAfter(), "the rake has no internal coupler");

		final double headOffsetBefore = merged.getMmtrMotionWalker().offsetM();
		final MmtrCoupleSurgery.Result result = MmtrCoupleSurgery.uncouple(a.n.sim, merged.getId(), 1);
		assertTrue(result.ok(), result.reason());
		final Vehicle head = result.vehicle();
		final Vehicle tail = result.other();
		assertNotNull(head);
		assertNotNull(tail, "the cut produces a second vehicle");

		assertEquals(2, head.vehicleExtraData.immutableVehicleCars.size(), "the head keeps the rake");
		assertEquals(1, tail.vehicleExtraData.immutableVehicleCars.size(), "the tail is the loco");
		assertEquals("loco", tail.vehicleExtraData.immutableVehicleCars.get(0).getVehicleId());
		assertFalse(head.vehicleExtraData.immutableVehicleCars.get(1).getMmtrCouplerAfter(), "the head's new rear end has no coupler");
		assertEquals(headOffsetBefore, head.getMmtrMotionWalker().offsetM(), 1e-9, "the head does not move");

		// The tail stands directly behind the head on the same rail, nose to tail.
		assertEquals(a.n.y1.getHexId(), tail.getMmtrMotionWalker().railHex());
		assertEquals(a.n.y1.getHexId(), head.getMmtrMotionWalker().railHex());
		assertTrue(tail.getMmtrMotionWalker().offsetM() < head.getMmtrMotionWalker().offsetM(), "the tail is behind the head");
		assertEquals(0, head.getSpeed(), 1e-9);
		assertEquals(0, tail.getSpeed(), 1e-9);
		assertEquals(merged.getId(), head.getId(), "the head keeps the original vehicle id");
		assertTrue(tail.getId() != head.getId(), "the tail is a new vehicle");

		// Both halves are parked on one siding and must survive the yard's duplicate rule (they do not
		// overlap, so they are a legal yard state).
		for (int i = 0; i < 60; i++) {
			a.n.tick();
		}
		assertSame(head, a.n.siding1.getVehicleById(head.getId()), "the head half survives");
		assertSame(tail, a.n.siding1.getVehicleById(tail.getId()), "the tail half survives (nose to tail is not a duplicate)");
		assertEquals(2, countVehicles(a.n.sim), "the world holds exactly the two halves");
	}

	/**
	 * 实机反馈 (2026-09-09): a locomotive standing at the siding mouth is already longer than the
	 * siding together with the rake inside it, yet the coupling is legal — a consist body is not
	 * confined to one rail, and the locomotive straddles the mouth on the lead rail. The old
	 * "merged length must fit the siding" gate refused exactly the dev world's 机辆 shunt
	 * (16 m loco + 32 m rake on a 43 m siding). The siding here DECLARES 5 m while its rail is 12 m,
	 * which is the same situation: the merged 6.6 m body stands on the rails, just not inside the
	 * declared siding.
	 */
	@Test
	public void aMergedConsistMayBeLongerThanTheSidingItStandsOn() {
		final Net n = new Net("build/mmtr-couple-long-merge", 5);
		final Vehicle rake = n.spawn(n.siding1, cars("wagon", 2, false, "wagon"));
		final Vehicle loco = n.spawn(n.siding2, cars("loco", 1, true, "loco"));
		n.tick();
		n.tick();
		n.sim.mmtrShuntAuthorities.grant(loco.getId(), n.x2.getHexId(), n.y1.getHexId(), Kind.SUBSIDIARY_SHUNT, 0, 10 * 60 * 1000L);
		boardDriver(n.sim, loco, 3);
		n.tickUntil(() -> n.y1.getHexId().equals(loco.getMmtrMotionWalker().railHex()) && loco.getSpeed() == 0, 4000);

		final double mergedLength = Siding.getTotalVehicleLength(new ObjectArrayList<>(rake.vehicleExtraData.immutableVehicleCars))
				+ Siding.getTotalVehicleLength(new ObjectArrayList<>(loco.vehicleExtraData.immutableVehicleCars));
		assertTrue(mergedLength > n.siding1.getRailLength() + 1e-6,
				"the merged consist is longer than the declared siding (" + mergedLength + " > " + n.siding1.getRailLength() + ")");

		final MmtrCoupleSurgery.Result result = MmtrCoupleSurgery.couple(n.sim, loco.getId(), rake.getId());
		assertTrue(result.ok(), "a body that straddles the siding mouth must still couple: " + result.reason());
		assertEquals(3, result.mergedCarCount(), "2 rake cars + the locomotive");
		assertTrue(Siding.getTotalVehicleLength(new ObjectArrayList<>(result.vehicle().vehicleExtraData.immutableVehicleCars)) > n.siding1.getRailLength(),
				"the merged formation really is longer than the declared siding");
	}

	@Test
	public void cuttingAnywhereElseIsRefused() {		final Approached a = new Approached();
		final Vehicle merged = coupled(a);
		final MmtrCoupleSurgery.Result noSeam = MmtrCoupleSurgery.uncouple(a.n.sim, merged.getId(), 0);
		assertFalse(noSeam.ok());
		assertTrue(noSeam.reason().contains("没有车钩"), "reason: " + noSeam.reason());
		final MmtrCoupleSurgery.Result outOfRange = MmtrCoupleSurgery.uncouple(a.n.sim, merged.getId(), 2);
		assertFalse(outOfRange.ok());
		assertTrue(outOfRange.reason().contains("至少一节"), "reason: " + outOfRange.reason());

		// A fixed unit (a freshly staged rake that was never coupled) has no seam at all.
		final Net n = new Net("build/mmtr-couple-fixed-unit");
		final Vehicle fixedUnit = n.spawn(n.siding1, cars("wagon", 2, false, "wagon"));
		n.tick();
		final MmtrCoupleSurgery.Result fixed = MmtrCoupleSurgery.uncouple(n.sim, fixedUnit.getId(), 0);
		assertFalse(fixed.ok());
		assertTrue(fixed.reason().contains("没有车钩"), "reason: " + fixed.reason());
	}

	@Test
	public void theCrewStaysWithItsOwnCar() {
		final Approached a = new Approached();
		final Vehicle merged = coupled(a);
		// The driver rode the loco (now merged car 2).
		final ObjectArrayList<VehicleRidingEntity> mergedRiders = new ObjectArrayList<>();
		merged.vehicleExtraData.iterateRidingEntities(mergedRiders::add);
		assertEquals(1, mergedRiders.size());
		assertEquals(2, mergedRiders.get(0).getRidingCar());

		final MmtrCoupleSurgery.Result result = MmtrCoupleSurgery.uncouple(a.n.sim, merged.getId(), 1);
		assertTrue(result.ok(), result.reason());
		final ObjectArrayList<VehicleRidingEntity> headRiders = new ObjectArrayList<>();
		result.vehicle().vehicleExtraData.iterateRidingEntities(headRiders::add);
		assertEquals(0, headRiders.size(), "nobody rides the rake half");
		final ObjectArrayList<VehicleRidingEntity> tailRiders = new ObjectArrayList<>();
		result.other().vehicleExtraData.iterateRidingEntities(tailRiders::add);
		assertEquals(1, tailRiders.size(), "the driver stays in the loco");
		assertEquals(0, tailRiders.get(0).getRidingCar(), "the loco's cab is car 0 of the tail again");
		assertTrue(tailRiders.get(0).isDriver());
	}

	private static int countVehicles(Simulator simulator) {
		final int[] count = {0};
		simulator.sidings.forEach(siding -> siding.iterateVehicles(vehicle -> count[0]++));
		return count[0];
	}
}
