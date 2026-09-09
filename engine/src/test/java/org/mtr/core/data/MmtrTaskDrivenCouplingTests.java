package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.job.MmtrCarSpec;
import org.mtr.core.mmtr.job.MmtrConsistJob;
import org.mtr.core.mmtr.job.MmtrJobScheduler;
import org.mtr.core.mmtr.job.MmtrJobStep;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C9: task-driven shunting - a consist job drives a train ACROSS the yard to another siding and couples
 * the stock standing there.
 *
 * <p>Pinned here: the scheduler no longer refuses a MOVE_TO that targets another siding (the old
 * "cross-track auto-move is offline" gate), the task-driven shunt grants itself the 调车授权 the
 * occupied-section approach and the coupling gate both need, and the COUPLE step completes the job -
 * whether the couplers latched by themselves (C8 automatic) or the surgery had to be run (manual
 * couplers).
 */
public final class MmtrTaskDrivenCouplingTests {

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

	/** Two yard sidings joined by a through rail, plus the scheduler ticking with the sidings. */
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
		long millis;

		Net(String savePath) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			y2 = Rail.newSidingRail(y2Back, Angle.fromAngle(0), y2Mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			x2 = through(y2Mouth, y1Back);
			y1 = Rail.newSidingRail(y1Back, Angle.fromAngle(0), y1Mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			ma = through(y1Mouth, new Position(16, 0, 0));
			final Depot depot = new Depot(TransportMode.TRAIN, sim);
			siding1 = new Siding(y1Back, y1Mouth, 12, TransportMode.TRAIN, sim);
			siding2 = new Siding(y2Back, y2Mouth, 12, TransportMode.TRAIN, sim);
			depot.setName("Task Couple Yard");
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

		Vehicle spawnConsist(Siding siding, ObjectArrayList<VehicleCar> cars) {
			siding.setVehicleCars(cars);
			siding.clearParkedVehicles();
			final org.mtr.core.mmtr.consist.MmtrConsistWalker walker = siding.mmtrConsistWalkerFromYard(null, new BranchStore(), null);
			assertNotNull(walker, "the consist body must fit its yard rail");
			final Vehicle vehicle = siding.spawnMmtrConsistVehicle(walker, org.mtr.core.mmtr.consist.MmtrCabState.Cab.CAB_A);
			assertNotNull(vehicle, "consist-body seam must spawn");
			return vehicle;
		}

		/**
		 * Place a consist body with its A end {@code aEndOffsetM} into {@code rail} from {@code entry}
		 * (the same yard placement the head-on coupling test uses, so the locomotive can stand with its
		 * leading end toward the throat the way a real shunt is made).
		 */
		Vehicle placeConsist(Siding siding, Rail rail, Position entry, double aEndOffsetM, ObjectArrayList<VehicleCar> cars, org.mtr.core.mmtr.consist.MmtrCabState.Cab cab) {
			siding.setVehicleCars(cars);
			siding.clearParkedVehicles();
			final double[] carLengthsM = new double[cars.size()];
			final boolean[] couplerAfter = new boolean[cars.size()];
			for (int i = 0; i < cars.size(); i++) {
				carLengthsM[i] = cars.get(i).getTotalLength(i == 0, i == cars.size() - 1);
				couplerAfter[i] = cars.get(i).getMmtrCouplerAfter();
			}
			final org.mtr.core.mmtr.consist.MmtrConsistWalker walker = org.mtr.core.mmtr.consist.MmtrConsistWalker.place(sim, new BranchStore(), rail, entry, aEndOffsetM, carLengthsM, null,
				org.mtr.core.mmtr.consist.MmtrConsistBody.seamArcMsFrom(aEndOffsetM, carLengthsM, couplerAfter),
				org.mtr.core.mmtr.consist.MmtrConsistBody.seamCarIndexesFrom(carLengthsM, couplerAfter));
			assertNotNull(walker, "the consist body must fit the rail");
			final Vehicle vehicle = siding.spawnMmtrConsistVehicle(walker, cab);
			assertNotNull(vehicle, "the consist body must spawn");
			return vehicle;
		}

		/** One simulated second: vehicles move, the auto-coupler pass runs, then the scheduler advances. */
		void tick() {
			millis += 1000;
			trees.removeFirst();
			trees.add(new Object2ObjectAVLTreeMap<>());
			siding1.simulateVehicles(1000, trees);
			siding2.simulateVehicles(1000, trees);
			org.mtr.core.mmtr.MmtrAutoCoupler.tick(sim);
			if (sim.mmtrJobScheduler != null) {
				sim.mmtrJobScheduler.tick(millis, sim);
			}
		}
	}

	private static ObjectArrayList<VehicleCar> cars(String vehicleId, int count, boolean powered, String consistTypeId, boolean autoCoupler) {
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		for (int i = 0; i < count; i++) {
			final VehicleCar car = new VehicleCar(vehicleId, 2, 1, 10, 0, 1, 0.1, 0.1, powered, consistTypeId);
			car.setMmtrAutoCoupler(autoCoupler);
			cars.add(car);
		}
		return cars;
	}

	private static MmtrCarSpec spec(String vehicleId, boolean powered, String consistTypeId, boolean autoCoupler) {
		final MmtrCarSpec spec = new MmtrCarSpec();
		spec.vehicleId = vehicleId;
		spec.length = 2;
		spec.width = 1;
		spec.capacity = 10;
		spec.bogie1Position = 0;
		spec.bogie2Position = 1;
		spec.couplingPadding1 = 0.1;
		spec.couplingPadding2 = 0.1;
		spec.powered = powered;
		spec.consistTypeId = consistTypeId;
		spec.mmtrAutoCoupler = autoCoupler;
		return spec;
	}

	private static MmtrConsistJob stockJob(String jobId, long sidingId, String carId, int count, boolean powered, String consistTypeId, boolean autoCoupler) {
		final MmtrConsistJob job = new MmtrConsistJob();
		job.jobId = jobId;
		job.sidingId = sidingId;
		job.startTimeOfDayMs = 0;
		job.repeatDaily = false;
		for (int i = 0; i < count; i++) {
			job.cars.add(spec(carId, powered, consistTypeId, autoCoupler));
		}
		return job;
	}

	/** The coupling job: drive to the rake's siding, then couple it. */
	private static MmtrConsistJob couplingJob(String jobId, long fromSidingId, String carId, long targetSidingId, String targetJobId, boolean autoCoupler) {
		final MmtrConsistJob job = stockJob(jobId, fromSidingId, carId, 1, true, "loco", autoCoupler);
		final MmtrJobStep drive = new MmtrJobStep();
		drive.stepId = "drive";
		drive.type = MmtrJobStep.StepType.MOVE_TO;
		drive.targetId = targetSidingId;
		drive.dueTimeOfDayMs = 3_600_000;
		job.steps.add(drive);
		final MmtrJobStep couple = new MmtrJobStep();
		couple.stepId = "couple";
		couple.type = MmtrJobStep.StepType.COUPLE;
		couple.targetJobId = targetJobId;
		couple.dueTimeOfDayMs = 7_200_000;
		job.steps.add(couple);
		return job;
	}

	@Test
	public void aJobDrivesAcrossTheYardAndCouplesTheStandingRake() {
		runShunt(true);
	}

	/** A manual-coupler rake cannot latch by itself, so the COUPLE step runs the real surgery. */
	@Test
	public void aJobCouplesAManualCouplerRakeBySurgery() {
		runShunt(false);
	}

	private void runShunt(boolean autoCoupler) {
		final Net n = new Net("build/mmtr-task-couple-" + (autoCoupler ? "auto" : "manual"));
		// The rake stands just inside y1 with its A end facing the throat; the locomotive stands on y2
		// with its B end (leading) toward the throat, i.e. the way a real shunt is made.
		final Vehicle rake = n.placeConsist(n.siding1, n.y1, n.y1Back, 3.0, cars("wagon", 2, false, "wagon", autoCoupler), org.mtr.core.mmtr.consist.MmtrCabState.Cab.CAB_A);
		final Vehicle loco = n.placeConsist(n.siding2, n.y2, n.y2Back, 9.9, cars("loco", 1, true, "loco", autoCoupler), org.mtr.core.mmtr.consist.MmtrCabState.Cab.CAB_B);

		n.sim.upsertMmtrJob(stockJob("rake", n.siding1.getId(), "wagon", 2, false, "wagon", autoCoupler));
		n.sim.upsertMmtrJob(couplingJob("loco", n.siding2.getId(), "loco", n.siding1.getId(), "rake", autoCoupler));
		assertNotNull(n.sim.mmtrJobScheduler, "the job upsert must attach a scheduler");

		// The approach is a real drive across the yard (cross-track run under a 调车授权), so give it
		// simulated seconds; run until the job itself reports done, not merely until the trains touch -
		// with automatic couplers the latched formation is only the start of the job's last step.
		int ticks = 0;
		while (ticks < 900 && n.sim.mmtrJobScheduler.stateOf("loco") != MmtrJobScheduler.JobState.DONE) {
			n.tick();
			ticks++;
		}

		final Vehicle merged = mergedVehicle(n, rake, loco);
		assertTrue(coupled(n, rake, loco), "the locomotive must have driven over and coupled the rake (ticks=" + ticks + ")");
		assertEquals(MmtrJobScheduler.JobState.DONE, n.sim.mmtrJobScheduler.stateOf("loco"), "the coupling job completed: "
			+ n.sim.mmtrJobScheduler.failureOf("loco") + " / mission "
			+ (merged == null || merged.getMmtrMission() == null ? "-" : merged.getMmtrMission().getState() + " " + merged.getMmtrMission().getFailureReason()));
		assertEquals(2, n.sim.mmtrJobScheduler.stepIndexOf("loco"), "both the drive and the couple step ran");

		// S5 regression (实机 2026-09-09): the surgery builds a NEW Vehicle object, so the route the
		// pre-surgery object published must not survive as an orphan - the live feed showed a SET shunt
		// route on a train whose mission was already COMPLETE. The movement is over: no route, no hold.
		for (int i = 0; i < 5; i++) {
			n.tick();
		}
		assertTrue(n.sim.mmtrRoutes.snapshot().isEmpty(), "no orphan route survives the coupling surgery");
		assertEquals(0, n.sim.mmtrShuntAuthorities.size(), "the shunt authority of both trains is withdrawn");

		// 实机 2026-09-09: a merge of two engine-staged consists left the merged train UNMANNED, so
		// MmtrConsistWalker.railHex() was null and the task layer could never plan for it again
		// ("walker has no current rail / ahead node"). The surgery now stages the placeholder key.
		assertNotNull(merged, "the merged consist exists");
		assertTrue(merged.getMmtrConsistWalker().cabs().isSystemKey(), "the merged consist keeps the engine's placeholder key");
		assertNotNull(merged.getMmtrConsistWalker().railHex(), "a manned consist reports its leading rail");
		final Rail nextTarget = org.mtr.core.mmtr.MmtrRunPlanner.findSavedRailRail(n.sim, n.siding2.getId());
		assertNotNull(nextTarget, "the next mission's target rail resolves");
		final org.mtr.core.mmtr.MmtrRunPlanner.Plan nextPlan = org.mtr.core.mmtr.MmtrRunPlanner.planToRail(n.sim, merged, nextTarget.getHexId(), 1.0);
		assertTrue(nextPlan.feasible, "the merged consist can be given another mission: " + nextPlan.reason);
	}

	private static boolean coupled(Net n, Vehicle rake, Vehicle loco) {
		final Vehicle merged = mergedVehicle(n, rake, loco);
		return merged != null && merged.vehicleExtraData.immutableVehicleCars.size() == 3;
	}

	/** Whichever of the two ids survived the surgery, with the merged 3-car formation. */
	private static Vehicle mergedVehicle(Net n, Vehicle rake, Vehicle loco) {
		final Vehicle survivor = n.sim.mmtrFindVehicle(loco.getId()) != null ? n.sim.mmtrFindVehicle(loco.getId()) : n.sim.mmtrFindVehicle(rake.getId());
		return survivor;
	}
}
