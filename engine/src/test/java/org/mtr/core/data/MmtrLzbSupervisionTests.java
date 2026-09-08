package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.MmtrRegime;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.operation.MmtrDriveControl;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signal S4 (LZB): continuous speed supervision for MANUAL driving on the high-speed band
 * (>= 101 km/h rails). Unlike AWS (advisory, S2/S3), the LZB ceiling is ENFORCED: traction can
 * never push the train past min(rail limit, consist ceiling) - overspeed decays at service
 * deceleration - and a slower rail ahead is braced for with the service envelope so the train
 * crosses the node at (about) the slower rail's limit. Cab data (ceiling / target speed / target
 * distance) is exposed for the future driver display. Air-brake consists keep the controller path.
 */
public final class MmtrLzbSupervisionTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":300,\"maxManualSpeedKmh\":300,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
		+ "}";
	private static final long OBSTACLE_VEHICLE_ID = 999_999_003L;

	private static double kmh(double speedKilometersPerHour) {
		return speedKilometersPerHour / 3600.0;
	}

	private static Rail through(Position p1, Position p2, long speedLimitKmh) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			speedLimitKmh, speedLimitKmh, false, false, true, false, true, TransportMode.TRAIN);
	}

	/**
	 * Y (yard rail, 12 m, 40 AWS) -> A (1400 m, limit 120 -> LZB band) -> B (200 m, limit 40 ->
	 * AWS band). The consist ceiling is 300, so on A the LZB ceiling is the rail limit (120) and
	 * manual traction must be capped there; approaching B the supervision must brace the train down
	 * to ~40 at the node; after crossing into B (AWS) the manual driver is free again.
	 */
	private static final class LzbNet {
		final Simulator sim;
		final Position rear = new Position(-20, 0, 0);
		final Position mouth = new Position(-8, 0, 0);
		final Rail yRail;
		final Rail aRail;
		final Rail bRail;
		final Depot depot;
		final Siding siding;
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = new ObjectArrayList<>();

		LzbNet(String savePath) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			yRail = Rail.newSidingRail(rear, Angle.fromAngle(0), mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			final Position aEnd = new Position(1392, 0, 0);
			aRail = through(mouth, aEnd, 120);
			bRail = through(aEnd, new Position(1592, 0, 0), 40);
			depot = new Depot(TransportMode.TRAIN, sim);
			siding = new Siding(rear, mouth, 12, TransportMode.TRAIN, sim);
			depot.setName("Yard");
			depot.setCorners(new Position(-25, -3, -3), new Position(1600, 3, 3));
			sim.rails.add(yRail);
			sim.rails.add(aRail);
			sim.rails.add(bRail);
			sim.depots.add(depot);
			sim.sidings.add(siding);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			siding.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			assertTrue(depot.savedRails.contains(siding), "siding must attach to the depot yard");
			siding.tick();
			trees.add(new Object2ObjectAVLTreeMap<>());
			trees.add(new Object2ObjectAVLTreeMap<>());
		}

		Vehicle spawn() {
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, new BranchStore(), null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			return vehicle;
		}

		void tick() {
			trees.removeFirst();
			trees.add(new Object2ObjectAVLTreeMap<>());
			siding.simulateVehicles(1000, trees);
		}
	}

	@Test
	public void manualLzbDriverIsEnforcedToTheRailCeilingAndBracesIntoTheSlowerRail() {
		final LzbNet n = new LzbNet("build/mmtr-lzb-manual");
		final Vehicle v = n.spawn();
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(entities);
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(3).setReverser(1), driver).apply(n.sim);

		double maxOnA = 0;
		double crossingSpeed = Double.MAX_VALUE;
		double maxOnB = 0;
		boolean crossed = false;
		boolean targetBecame40 = false;
		boolean cabDataOnA = false;
		for (int i = 0; i < 2000 && !(crossed && v.getSpeed() == 0 && v.getMmtrMotionWalker().endOfLine()); i++) {
			n.tick();
			final double speed = v.getSpeed();
			if (n.aRail.getHexId().equals(v.getMmtrMotionWalker().railHex())) {
				maxOnA = Math.max(maxOnA, speed);
				if (v.getMmtrRegime() == MmtrRegime.LZB && v.getMmtrLzbCeilingKmh() == 120) {
					cabDataOnA = true;
				}
				// The cab target drops to the slower rail's 40 well before the node, with distance.
				if (v.getMmtrLzbTargetKmh() == 40 && v.getMmtrLzbTargetDistanceM() > 0) {
					targetBecame40 = true;
				}
			} else if (n.bRail.getHexId().equals(v.getMmtrMotionWalker().railHex())) {
				if (!crossed) {
					crossed = true;
					crossingSpeed = speed;
				}
				maxOnB = Math.max(maxOnB, speed);
			}
		}
		assertTrue(cabDataOnA, "cab reports the 120 km/h LZB ceiling on the 120 rail");
		assertTrue(targetBecame40, "cab target drops to 40 with a distance before the slower rail");
		assertTrue(crossed, "manual train crossed onto the slower AWS rail");
		assertTrue(maxOnA > kmh(115), "driver pushed hard on the 120 rail, max=" + maxOnA * 3600.0 + " km/h");
		assertTrue(maxOnA <= kmh(120) + 1e-6, "LZB ceiling enforced: never exceeded 120 on the 120 rail, max=" + maxOnA * 3600.0 + " km/h");
		// The brake envelope is sampled at tick rate (service brake 0.9 m/s^2 per 1 s tick), so the
		// node crossing carries up to ~1 tick of residual deceleration (~3 km/h) above the target.
		assertTrue(crossingSpeed <= kmh(40) + kmh(3), "braced into the 40 rail, crossed at " + crossingSpeed * 3600.0 + " km/h");
		assertTrue(crossingSpeed >= kmh(35), "did not crawl into the slow rail, crossed at " + crossingSpeed * 3600.0 + " km/h");
		// On the AWS rail the manual driver is free again (S2 semantics): the same throttle pushes past 40.
		assertTrue(maxOnB > kmh(45), "AWS band frees the driver again after the LZB section, max=" + maxOnB * 3600.0 + " km/h");
		assertEquals(0, v.getMmtrLzbCeilingKmh(), "no LZB supervision once off the LZB band");
	}

	@Test
	public void manualLzbDriverStopsAtAnOccupiedRailWithZeroCabTarget() {
		final LzbNet n = new LzbNet("build/mmtr-lzb-block");
		final Vehicle v = n.spawn();
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(entities);
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(3).setReverser(1), driver).apply(n.sim);

		// A foreign train occupies B: while the manual LZB run approaches, the cab target is 0 and
		// the S1 occupancy stop holds the train on A, never boarding B.
		boolean sawZeroTarget = false;
		boolean stoppedOnA = false;
		for (int i = 0; i < 2000 && !stoppedOnA; i++) {
			treesRotateAndOccupyB(n);
			if (v.getMmtrLzbTargetKmh() == 0 && v.getMmtrLzbTargetDistanceM() > 0) {
				sawZeroTarget = true;
			}
			stoppedOnA = v.getSpeed() == 0 && v.isMmtrBlockHeldFromSync() && n.aRail.getHexId().equals(v.getMmtrMotionWalker().railHex());
		}
		assertTrue(sawZeroTarget, "cab target 0 with distance while the rail ahead is occupied");
		assertTrue(stoppedOnA, "LZB manual run stops on A at the occupied-rail boundary, rail=" + v.getMmtrMotionWalker().railHex());
		assertEquals(n.aRail.getHexId(), v.getMmtrMotionWalker().railHex(), "never boarded the occupied B rail");
		assertEquals(0, v.getMmtrLzbTargetKmh(), "cab target stays 0 while held");
		assertFalse(v.isMmtrProtectionFromSync(), "soft occupancy stop, no SPAD");
	}

	private void treesRotateAndOccupyB(LzbNet n) {
		n.trees.removeFirst();
		n.trees.add(new Object2ObjectAVLTreeMap<>());
		final Position orderedP1 = n.bRail.getPosition1().compareTo(n.bRail.getPosition2()) <= 0 ? n.bRail.getPosition1() : n.bRail.getPosition2();
		final Position orderedP2 = orderedP1 == n.bRail.getPosition1() ? n.bRail.getPosition2() : n.bRail.getPosition1();
		Data.put(n.trees.get(1), orderedP1, orderedP2,
			vehiclePosition -> {
				final VehiclePosition newVehiclePosition = vehiclePosition == null ? new VehiclePosition() : vehiclePosition;
				newVehiclePosition.addSegment(20, 60, OBSTACLE_VEHICLE_ID);
				return newVehiclePosition;
			}, Object2ObjectAVLTreeMap::new);
		n.siding.simulateVehicles(1000, n.trees);
	}
}
