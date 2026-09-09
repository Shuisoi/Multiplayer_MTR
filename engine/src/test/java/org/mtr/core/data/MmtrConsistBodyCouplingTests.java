package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.consist.MmtrCabState.Cab;
import org.mtr.core.mmtr.consist.MmtrConsistBody;
import org.mtr.core.mmtr.consist.MmtrConsistWalker;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.signal.MmtrShuntAuthority.Kind;
import org.mtr.core.operation.MmtrDriveControl;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C5: coupling two CONSIST BODIES (the double-cab model every real MMTR train uses).
 *
 * <p>The body's car lengths and coupler seams are part of {@link MmtrConsistBody}, so a coupling is
 * not just a car-list edit: the body has to be rebuilt with the merged formation and its spine has to
 * cover the trailing train's rails. Pinned here: the A end does not move, the merged body carries all
 * cars, the seam lands at the joint, the whole merged consist reports the occupancy of both trains,
 * and the existing body behaviour (occupancy, change-ends) is untouched.</p>
 */
public final class MmtrConsistBodyCouplingTests {

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
		final Siding siding1;
		final Siding siding2;
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = newTrees();

		Net(String savePath) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			y2 = Rail.newSidingRail(y2Back, Angle.fromAngle(0), y2Mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			x2 = through(y2Mouth, y1Back);
			y1 = Rail.newSidingRail(y1Back, Angle.fromAngle(0), y1Mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			ma = through(y1Mouth, new Position(16, 0, 0));
			final Depot depot = new Depot(TransportMode.TRAIN, sim);
			siding1 = new Siding(y1Back, y1Mouth, 12, TransportMode.TRAIN, sim);
			siding2 = new Siding(y2Back, y2Mouth, 12, TransportMode.TRAIN, sim);
			depot.setName("Consist Couple Yard");
			depot.setCorners(new Position(-35, -3, -3), new Position(20, 3, 3));
			sim.rails.add(y2);
			sim.rails.add(x2);
			sim.rails.add(y1);
			sim.rails.add(ma);
			sim.depots.add(depot);
			sim.sidings.add(siding1);
			sim.sidings.add(siding2);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "loco";
			sim.sync();
			siding1.tick();
			siding2.tick();
		}

		/** Stage {@code cars} on a siding and spawn it as a CONSIST BODY (double-cab model). */
		Vehicle spawnConsist(Siding siding, ObjectArrayList<VehicleCar> cars) {
			siding.setVehicleCars(cars);
			siding.clearParkedVehicles();
			final MmtrConsistWalker walker = siding.mmtrConsistWalkerFromYard(null, new BranchStore(), null);
			assertNotNull(walker, "the consist body must fit its yard rail");
			final Vehicle vehicle = siding.spawnMmtrConsistVehicle(walker, Cab.CAB_A);
			assertNotNull(vehicle, "consist-body seam must spawn");
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

		/** Overlap of any vehicle's occupancy segment on {@code rail} with the window [fromM, railEnd]. */
		double overlapFrom(Rail rail, double fromM) {
			final VehiclePosition vehiclePosition = Data.tryGet(trees.get(1), rail.getPosition1(), rail.getPosition2());
			return vehiclePosition == null ? -1 : vehiclePosition.getClosestOverlap(fromM, rail.railMath.getLength(), false, -1);
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
		assertTrue(v.enterMmtrCab(Cab.CAB_A, driver), "the crew takes the consist-body cab");
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(throttle).setReverser(1), driver).apply(sim);
		assertTrue(v.isMmtrManualOverride(), "the drive command holds the override");
	}

	/** Rake (2 wagons, consist body) stabled on y1; loco (1 car, consist body) drawn up to it. */
	private static final class Approached {
		final Net n = new Net("build/mmtr-consist-body-couple");
		final Vehicle rake;
		final Vehicle loco;

		Approached() {
			rake = n.spawnConsist(n.siding1, cars("wagon", 2, false, "wagon"));
			loco = n.spawnConsist(n.siding2, cars("loco", 1, true, "loco"));
			n.tick();
			n.tick();
			n.sim.mmtrShuntAuthorities.grant(loco.getId(), n.x2.getHexId(), n.y1.getHexId(), Kind.SUBSIDIARY_SHUNT, 0, 10 * 60 * 1000L);
			boardDriver(n.sim, loco, 3);
			n.tickUntil(() -> n.y1.getHexId().equals(loco.getMmtrMotionWalker().railHex()) && loco.getSpeed() == 0, 4000);
		}
	}

	@Test
	public void couplingTwoConsistBodiesRebuildsTheBody() {
		final Approached a = new Approached();
		final MmtrConsistBody rakeBody = a.rake.getMmtrConsistWalker().body();
		final double aEndArcBefore = rakeBody.aEndArcM();
		final String aEndRailBefore = rakeBody.legAtArcM(rakeBody.aEndArcM()).railHex();
		final double aEndOffsetBefore = rakeBody.legOffsetM(rakeBody.aEndArcM());

		final MmtrCoupleSurgery.Result result = MmtrCoupleSurgery.couple(a.n.sim, a.loco.getId(), a.rake.getId());
		assertTrue(result.ok(), result.reason());
		final Vehicle merged = result.vehicle();
		final MmtrConsistWalker mergedWalker = merged.getMmtrConsistWalker();
		assertNotNull(mergedWalker, "the merged train is still a consist body");

		final MmtrConsistBody mergedBody = mergedWalker.body();
		assertEquals(3, mergedBody.carCount(), "2 rake cars + 1 loco car");
		assertEquals(1, mergedBody.seamCount(), "the joint is the only seam");
		assertEquals(4.3, mergedBody.seamArcM(0) - mergedBody.aEndArcM(), 0.05, "the seam sits at the end of the rake's two cars");
		assertEquals(1, mergedBody.carIndexAfterSeam(0), "the cut would keep the rake's 2 cars");
		assertTrue(mergedBody.lengthM() > rakeBody.lengthM(), "the body grew by the coupled-on car");

		// The A end did not move: the merged body starts exactly where the rake started.
		assertEquals(aEndArcBefore, mergedBody.aEndArcM(), 1e-9);
		assertEquals(aEndRailBefore, mergedBody.legAtArcM(mergedBody.aEndArcM()).railHex(), "the A end stays on its rail");
		assertEquals(aEndOffsetBefore, mergedBody.legOffsetM(mergedBody.aEndArcM()), 1e-9, "the A end keeps its offset");

		// The whole merged consist occupies the rail: one segment now covers both the rake's old
		// position and the loco's old position (the body grew toward the B end).
		final ObjectArrayList<MmtrConsistBody.OccupiedSegment> occupancy = mergedBody.occupancy();
		assertEquals(1, occupancy.size(), "the merged 6.4 m consist still fits the 12 m rail, so it is one segment");
		final MmtrConsistBody.OccupiedSegment mergedSegment = occupancy.get(0);
		assertEquals(a.n.y1.getHexId(), mergedSegment.railHex());
		assertTrue(mergedSegment.fromM() <= 4.0, "the merged body still covers the rake's old rear, fromM=" + mergedSegment.fromM());
		assertTrue(mergedSegment.toM() >= 10.0, "the merged body now covers the loco's old position, toM=" + mergedSegment.toM());
		assertEquals(mergedBody.lengthM(), mergedSegment.lengthM(), 0.05, "the occupied slice is the whole body");

		// The merged train keeps standing still and is unmanned (the crew re-takes the leading cab).
		assertEquals(0, merged.getSpeed(), 1e-9);
		assertTrue(!mergedWalker.cabs().isManned(), "after coupling nobody holds the key");
	}
}
