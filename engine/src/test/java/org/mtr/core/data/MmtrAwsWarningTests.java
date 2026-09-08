package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
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
 * Signal S3 (AWS): the point-style warning state machine for live manual driving on AWS-band
 * rails (<= 100 km/h). While the train runs inside the trigger lead of a restricted boundary -
 * an occupied rail ahead (block stop, S1) or a slower rail to be braced for (S2) - the warning
 * sounds (WARN). The driver acknowledges through ControlState.acknowledge (one-shot); an
 * unacknowledged warning that outlives the 3 s window while the train is STILL MOVING becomes a
 * SPAD emergency stop through the existing protection channel (10 s lock). Auto runs drive
 * themselves and never see driver warnings. Manual overspeed on AWS rails stays unforced (S2
 * semantics) - the warning is about restricted boundaries, not speed.
 */
public final class MmtrAwsWarningTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
		+ "}";
	private static final long OBSTACLE_VEHICLE_ID = 999_999_002L;

	private static Rail through(Position p1, Position p2, long speedLimitKmh) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			speedLimitKmh, speedLimitKmh, false, false, true, false, true, TransportMode.TRAIN);
	}

	/**
	 * All-AWS straight line: Y (yard rail, rear -20 .. mouth -8, 12 m, 40 km/h) -> A (-8..142,
	 * 150 m, 40 km/h) -> B (142..342, 200 m, 40 km/h). A foreign train occupies B (injected
	 * segment 20..40 m from B's ordered start every tick), so the block stop sits epsilon short of
	 * the A/B node; the warning lead (75 m) therefore engages while the manual train still runs on
	 * A, 150 m of runway for the 3 s acknowledgement window to expire mid-run.
	 */
	private static final class AwsNet {
		final Simulator sim;
		final Position rear = new Position(-20, 0, 0);
		final Position mouth = new Position(-8, 0, 0);
		final Rail yRail;
		final Rail aRail;
		final Rail bRail;
		final Depot depot;
		final Siding siding;
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = new ObjectArrayList<>();

		AwsNet(String savePath) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			yRail = Rail.newSidingRail(rear, Angle.fromAngle(0), mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			final Position aEnd = new Position(142, 0, 0);
			aRail = through(mouth, aEnd, 40);
			bRail = through(aEnd, new Position(342, 0, 0), 40);
			depot = new Depot(TransportMode.TRAIN, sim);
			siding = new Siding(rear, mouth, 12, TransportMode.TRAIN, sim);
			depot.setName("Yard");
			depot.setCorners(new Position(-25, -3, -3), new Position(350, 3, 3));
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

		/** Occupies B (20..40 m from its ordered start) for the whole tick, then simulates. */
		void tickWithOccupiedB() {
			trees.removeFirst();
			trees.add(new Object2ObjectAVLTreeMap<>());
			final Position orderedP1 = bRail.getPosition1().compareTo(bRail.getPosition2()) <= 0 ? bRail.getPosition1() : bRail.getPosition2();
			final Position orderedP2 = orderedP1 == bRail.getPosition1() ? bRail.getPosition2() : bRail.getPosition1();
			Data.put(trees.get(1), orderedP1, orderedP2,
				vehiclePosition -> {
					final VehiclePosition newVehiclePosition = vehiclePosition == null ? new VehiclePosition() : vehiclePosition;
					newVehiclePosition.addSegment(20, 40, OBSTACLE_VEHICLE_ID);
					return newVehiclePosition;
				}, Object2ObjectAVLTreeMap::new);
			siding.simulateVehicles(1000, trees);
		}
	}

	@Test
	public void unacknowledgedOccupancyWarningTriggersSpadWhileStillMoving() {
		final AwsNet n = new AwsNet("build/mmtr-aws-spad");
		final Vehicle v = n.spawn();
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(entities);
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(3).setReverser(1), driver).apply(n.sim);
		assertTrue(v.isMmtrManualOverride(), "drive command must hold the override");

		// Run: the warning must engage inside the lead of the occupied B and, unacknowledged, turn
		// into a SPAD while the train is still moving - before it ever boards B.
		boolean warned = false;
		boolean spad = false;
		int warnTicks = 0;
		for (int i = 0; i < 500 && !spad; i++) {
			n.tickWithOccupiedB();
			if (v.isMmtrAwsWarningPending()) {
				if (!warned) {
					warned = true;
					assertTrue(v.getSpeed() > 1e-9, "warning engages while the train is running");
					assertEquals(40, v.getMmtrCurrentSpeedLimitKmh(), "warning only on AWS-band rails");
				}
				warnTicks++;
			}
			if (v.isMmtrProtectionFromSync()) {
				spad = true;
			}
		}
		assertTrue(warned, "AWS warning must engage before the occupied rail");
		assertTrue(spad, "unacknowledged warning must SPAD while the train is still moving");
		assertTrue(warnTicks >= 3, "the warning window (3 s) elapsed before the SPAD, ticks=" + warnTicks);
		assertFalse(v.isMmtrAwsWarningPending(), "SPAD resolves the unacknowledged warning");
		assertTrue(v.isMmtrAwsWarningAcknowledged(), "state machine lands in acknowledged (post-SPAD)");
		assertTrue(n.aRail.getHexId().equals(v.getMmtrMotionWalker().railHex()) || n.yRail.getHexId().equals(v.getMmtrMotionWalker().railHex()),
			"SPAD happened before the train could board the occupied rail B, rail=" + v.getMmtrMotionWalker().railHex());
	}

	@Test
	public void acknowledgedWarningStopsAtTheBoundaryWithoutSpad() {
		final AwsNet n = new AwsNet("build/mmtr-aws-ack");
		final Vehicle v = n.spawn();
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(entities);
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(3).setReverser(1), driver).apply(n.sim);

		boolean acked = false;
		boolean stoppedAtBoundary = false;
		boolean spad = false;
		for (int i = 0; i < 500 && !stoppedAtBoundary; i++) {
			n.tickWithOccupiedB();
			if (v.isMmtrAwsWarningPending() && !acked) {
				// The driver acknowledges while the train is still running (one-shot press).
				new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(3).setReverser(1).setAcknowledge(true), driver).apply(n.sim);
				acked = true;
			}
			if (v.isMmtrProtectionFromSync()) {
				spad = true;
			}
			stoppedAtBoundary = acked && v.getSpeed() == 0 && n.aRail.getHexId().equals(v.getMmtrMotionWalker().railHex()) && v.getRailProgress() > 140.0;
		}
		assertTrue(acked, "warning engaged and was acknowledged");
		assertFalse(spad, "acknowledged warning never SPADs");
		assertTrue(v.isMmtrAwsWarningAcknowledged(), "indicator stays acknowledged");
		assertTrue(stoppedAtBoundary, "the S1 occupancy stop holds the train at the A/B node, rail=" + v.getMmtrMotionWalker().railHex() + " progress=" + v.getRailProgress());
		assertEquals(n.aRail.getHexId(), v.getMmtrMotionWalker().railHex(), "never boarded the occupied B rail");
		// While the restriction persists, the acknowledged warning does not re-time into another SPAD.
		for (int i = 0; i < 30; i++) {
			n.tickWithOccupiedB();
		}
		assertFalse(v.isMmtrProtectionFromSync(), "no second SPAD while parked acknowledged at the boundary");
		assertTrue(v.isMmtrAwsWarningAcknowledged(), "indicator stays up while the restriction persists");
	}

	@Test
	public void autoRunNeverSeesDriverWarnings() {
		final AwsNet n = new AwsNet("build/mmtr-aws-auto");
		final Vehicle v = n.spawn();
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(12.0 + 150.0 + 60.0, false); // 60 m into the occupied B

		boolean stoppedAtBoundary = false;
		for (int i = 0; i < 500 && !stoppedAtBoundary; i++) {
			n.tickWithOccupiedB();
			assertFalse(v.isMmtrAwsWarningPending(), "auto runs never raise driver warnings");
			assertFalse(v.isMmtrAwsWarningAcknowledged(), "auto runs never acknowledge");
			assertFalse(v.isMmtrProtectionFromSync(), "auto is blocked softly, never SPADs on occupancy");
			stoppedAtBoundary = v.getSpeed() == 0 && n.aRail.getHexId().equals(v.getMmtrMotionWalker().railHex()) && v.getRailProgress() > 140.0;
		}
		assertTrue(stoppedAtBoundary, "auto occupancy stop holds at the A/B node, rail=" + v.getMmtrMotionWalker().railHex() + " progress=" + v.getRailProgress());
	}
}
