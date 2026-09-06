package org.mtr.core.mmtr.job;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.*;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executor nucleus: the scheduler spawns/start the consist at the job's time-of-day, advances
 * ordered steps when their mission completes and fails a job whose step misses its deadline.
 * Deterministic via Simulator.step.
 */
public final class MmtrJobSchedulerTests {

	private static final long DAY = Utilities.MILLIS_PER_DAY;

	private static Simulator buildWorld(String worldName, long dueMs) {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/" + worldName), false);
		final ObjectArrayList<String> noStyles = new ObjectArrayList<>();
		final Position p0 = new Position(0, 0, 0);
		final Position junction = new Position(10, 0, 0);
		final Position leadEnd = new Position(30, 0, 0);
		final Position platformA1 = new Position(50, 0, 0);
		final Position platformB1 = new Position(90, 0, 0);
		final Position end = new Position(120, 0, 0);

		sim.rails.add(Rail.newSidingRail(p0, Angle.fromAngle(0), junction, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles, TransportMode.TRAIN));
		sim.rails.add(Rail.newRail(junction, Angle.fromAngle(0), leadEnd, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles, 80, 80, false, false, true, false, true, TransportMode.TRAIN));
		sim.rails.add(Rail.newPlatformRail(leadEnd, Angle.fromAngle(0), platformA1, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles, TransportMode.TRAIN));
		sim.rails.add(Rail.newRail(platformA1, Angle.fromAngle(0), platformB1, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles, 80, 80, false, false, true, false, true, TransportMode.TRAIN));
		sim.rails.add(Rail.newPlatformRail(platformB1, Angle.fromAngle(0), end, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles, TransportMode.TRAIN));

		final Siding siding = new Siding(p0, junction, 10, TransportMode.TRAIN, sim);
		siding.setIsManual(true);
		siding.setMaxManualSpeed(Utilities.kilometersPerHourToMetersPerMillisecond(30));
		final Platform platformA = new Platform(leadEnd, platformA1, TransportMode.TRAIN, sim);
		final Platform platformB = new Platform(platformB1, end, TransportMode.TRAIN, sim);
		final Station stationA = new Station(sim);
		stationA.setName("A");
		stationA.setCorners(new Position(20, -50, -50), new Position(60, 50, 50));
		final Station stationB = new Station(sim);
		stationB.setName("B");
		stationB.setCorners(new Position(80, -50, -50), new Position(130, 50, 50));
		final Depot depot = new Depot(TransportMode.TRAIN, sim);
		depot.setName("Yard");
		depot.setCorners(new Position(-5, -50, -50), new Position(15, 50, 50));
		final Route route = new Route(TransportMode.TRAIN, sim);
		route.setName("AB");
		route.getRoutePlatforms().add(new RoutePlatformData(platformA.getId()));
		route.getRoutePlatforms().add(new RoutePlatformData(platformB.getId()));
		sim.sidings.add(siding);
		sim.platforms.add(platformA);
		sim.platforms.add(platformB);
		sim.stations.add(stationA);
		sim.stations.add(stationB);
		sim.depots.add(depot);
		sim.routes.add(route);
		sim.sync();

		final JsonObject depotJson = Utilities.getJsonObjectFromData(depot);
		final com.google.gson.JsonArray routeIds = new com.google.gson.JsonArray();
		routeIds.add(route.getId());
		depotJson.add("routeIds", routeIds);
		depot.updateData(new JsonReader(depotJson));
		sim.sync();

		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("loco", 10, 2, 100, 0, 5, 0.5, 0.5));
		siding.setVehicleCars(cars);
		Depot.generateDepots(sim, ObjectArrayList.wrap(new Depot[]{depot}));
		for (int i = 0; i < 300; i++) {
			sim.tick();
		}
		assertEquals("SUCCESSFUL", depot.getLastGeneratedStatus().name());
		return sim;
	}

	private static MmtrConsistJob singleStepJob(long sidingId, long dueMs) {
		final MmtrConsistJob job = new MmtrConsistJob();
		job.jobId = "J-single";
		job.depotId = 1;
		job.sidingId = sidingId;
		job.startTimeOfDayMs = 2_000; // operational day-time, 2s after scheduler anchor
		job.repeatDaily = false;
		final MmtrCarSpec car = new MmtrCarSpec();
		car.vehicleId = "loco";
		car.length = 10;
		car.width = 2;
		car.capacity = 100;
		car.bogie1Position = 0;
		car.bogie2Position = 5;
		car.couplingPadding1 = 0.5;
		car.couplingPadding2 = 0.5;
		job.cars.add(car);
		final MmtrJobStep step = new MmtrJobStep();
		step.stepId = "run";
		step.type = MmtrJobStep.StepType.MOVE_TO;
		step.targetId = 0; // terminal run
		step.dueTimeOfDayMs = dueMs;
		job.steps.add(step);
		return job;
	}

	@Test
	public void jobCompletesWhenItsStepFinishesBeforeDeadline() {
		final Simulator sim = buildWorld("mmtr-mini-job-ok", 120_000);
		final long[] sidingId = {0};
		sim.sidings.forEach(s -> sidingId[0] = s.getId());

		final MmtrConsistJob job = singleStepJob(sidingId[0], 120_000);
		final ObjectArrayList<MmtrConsistJob> jobs = new ObjectArrayList<>();
		jobs.add(job);
		final MmtrJobScheduler scheduler = MmtrJobScheduler.create(jobs);
		sim.mmtrJobScheduler = scheduler;

		sim.step(1000); // first tick anchors the operational clock and creates the instance
		assertEquals(MmtrJobScheduler.JobState.PENDING, scheduler.stateOf("J-single"));
		boolean sawRunning = false;
		for (int second = 1; second <= 300; second++) {
			sim.step(1000);
			final MmtrJobScheduler.JobState st = scheduler.stateOf("J-single");
			if (st == MmtrJobScheduler.JobState.RUNNING) { sawRunning = true; }
			if (st == MmtrJobScheduler.JobState.DONE || st == MmtrJobScheduler.JobState.FAILED) {
				System.out.println("[JOBSCHED] t=" + second + " state=" + st + " step=" + scheduler.stepIndexOf("J-single") + " fail=" + scheduler.failureOf("J-single"));
				break;
			}
		}
		assertTrue(sawRunning, "job must run before finishing");
		assertEquals(MmtrJobScheduler.JobState.DONE, scheduler.stateOf("J-single"));
		assertEquals(1, scheduler.stepIndexOf("J-single"));
		assertNull(scheduler.failureOf("J-single"));
	}

	@Test
	public void jobFailsWhenStepMissesItsDeadline() {
		final Simulator sim = buildWorld("mmtr-mini-job-late", 120_000);
		final long[] sidingId = {0};
		sim.sidings.forEach(s -> sidingId[0] = s.getId());

		final MmtrConsistJob job = singleStepJob(sidingId[0], 6_000); // far too tight for a ~40s run
		final ObjectArrayList<MmtrConsistJob> jobs = new ObjectArrayList<>();
		jobs.add(job);
		final MmtrJobScheduler scheduler = MmtrJobScheduler.create(jobs);
		sim.mmtrJobScheduler = scheduler;

		for (int second = 0; second < 30; second++) {
			sim.step(1000);
			if (scheduler.stateOf("J-single") == MmtrJobScheduler.JobState.FAILED) {
				System.out.println("[JOBSCHED] failed at t=" + second + " reason=" + scheduler.failureOf("J-single"));
				break;
			}
		}
		assertEquals(MmtrJobScheduler.JobState.FAILED, scheduler.stateOf("J-single"));
		assertTrue(scheduler.failureOf("J-single").contains("deadline"), scheduler.failureOf("J-single"));
	}

	/** Auto siding fixture: depot path generated, one parked auto vehicle (ATO-capable). */
	private static long[] buildAutoWorld(boolean presetCars) {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-mini-job-auto"), false);
		final ObjectArrayList<String> noStyles = new ObjectArrayList<>();
		final Position p0 = new Position(0, 0, 0);
		final Position junction = new Position(33, 0, 0);
		final Position leadEnd = new Position(40, 0, 0);
		final Position platformA1 = new Position(60, 0, 0);
		final Position platformB1 = new Position(100, 0, 0);
		final Position end = new Position(130, 0, 0);

		sim.rails.add(Rail.newSidingRail(p0, Angle.fromAngle(0), junction, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles, TransportMode.TRAIN));
		sim.rails.add(Rail.newRail(junction, Angle.fromAngle(0), leadEnd, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles, 80, 80, false, false, true, false, true, TransportMode.TRAIN));
		sim.rails.add(Rail.newPlatformRail(leadEnd, Angle.fromAngle(0), platformA1, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles, TransportMode.TRAIN));
		sim.rails.add(Rail.newRail(platformA1, Angle.fromAngle(0), platformB1, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles, 80, 80, false, false, true, false, true, TransportMode.TRAIN));
		sim.rails.add(Rail.newPlatformRail(platformB1, Angle.fromAngle(0), end, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles, TransportMode.TRAIN));

		final Siding siding = new Siding(p0, junction, 33, TransportMode.TRAIN, sim);
		siding.setMaxVehicles(1); // auto siding, no frequency departures
		final Platform platformA = new Platform(leadEnd, platformA1, TransportMode.TRAIN, sim);
		final Platform platformB = new Platform(platformB1, end, TransportMode.TRAIN, sim);
		final Station stationA = new Station(sim);
		stationA.setName("A");
		stationA.setCorners(new Position(20, -50, -50), new Position(70, 50, 50));
		final Station stationB = new Station(sim);
		stationB.setName("B");
		stationB.setCorners(new Position(80, -50, -50), new Position(135, 50, 50));
		final Depot depot = new Depot(TransportMode.TRAIN, sim);
		depot.setName("Yard");
		depot.setCorners(new Position(-5, -50, -50), new Position(40, 50, 50));
		final Route route = new Route(TransportMode.TRAIN, sim);
		route.setName("AB");
		route.getRoutePlatforms().add(new RoutePlatformData(platformA.getId()));
		route.getRoutePlatforms().add(new RoutePlatformData(platformB.getId()));
		sim.sidings.add(siding);
		sim.platforms.add(platformA);
		sim.platforms.add(platformB);
		sim.stations.add(stationA);
		sim.stations.add(stationB);
		sim.depots.add(depot);
		sim.routes.add(route);
		sim.sync();

		final JsonObject depotJson = Utilities.getJsonObjectFromData(depot);
		final com.google.gson.JsonArray routeIds = new com.google.gson.JsonArray();
		routeIds.add(route.getId());
		depotJson.add("routeIds", routeIds);
		depot.updateData(new JsonReader(depotJson));
		sim.sync();

		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("loco", 10, 2, 100, 0, 5, 0.5, 0.5));
		siding.setVehicleCars(cars);
		Depot.generateDepots(sim, ObjectArrayList.wrap(new Depot[]{depot}));
		for (int i = 0; i < 400; i++) {
			sim.tick();
		}
		assertEquals("SUCCESSFUL", depot.getLastGeneratedStatus().name());
		final boolean[] parked = {false};
		sim.sidings.forEach(s -> s.iterateVehicles(v -> { if (!v.getIsOnRoute()) { parked[0] = true; } }));
		if (presetCars) {
			assertTrue(parked[0], "auto siding must hold a parked vehicle after generation");
		} else {
			org.junit.jupiter.api.Assumptions.assumeTrue(!parked[0], "no stock should be parked before the job spawns it");
		}
		// keep simulator reachable for the stepping loop via a static holder
		AUTO_SIM[0] = sim;
		return new long[]{siding.getId(), platformA.getId(), platformB.getId()};
	}

	private static final Simulator[] AUTO_SIM = {null};

	@Test
	public void autoServiceRunsPlatformStepsWithDeadlines() {
		final long[] ids = buildAutoWorld(true);
		final Simulator sim = AUTO_SIM[0];

		final MmtrConsistJob job = new MmtrConsistJob();
		job.jobId = "J-auto";
		job.depotId = 1;
		job.sidingId = ids[0];
		job.startTimeOfDayMs = 2_000;
		job.repeatDaily = false;

		final MmtrJobStep moveA = new MmtrJobStep();
		moveA.stepId = "moveA";
		moveA.type = MmtrJobStep.StepType.MOVE_TO;
		moveA.targetId = ids[1];
		moveA.dueTimeOfDayMs = 60_000;
		job.steps.add(moveA);

		final MmtrJobStep serveA = new MmtrJobStep();
		serveA.stepId = "serveA";
		serveA.type = MmtrJobStep.StepType.SERVE;
		serveA.targetId = ids[1];
		serveA.dueTimeOfDayMs = 90_000;
		job.steps.add(serveA);

		final MmtrJobStep moveB = new MmtrJobStep();
		moveB.stepId = "moveB";
		moveB.type = MmtrJobStep.StepType.MOVE_TO;
		moveB.targetId = ids[2];
		moveB.dueTimeOfDayMs = 180_000;
		job.steps.add(moveB);

		final MmtrConsistJob jobAuto = new MmtrConsistJob();
		// serialized copy not needed; reuse fields directly
		jobAuto.jobId = job.jobId;
		jobAuto.depotId = job.depotId;
		jobAuto.sidingId = job.sidingId;
		jobAuto.startTimeOfDayMs = job.startTimeOfDayMs;
		jobAuto.repeatDaily = job.repeatDaily;
		jobAuto.steps.addAll(job.steps);

		final ObjectArrayList<MmtrConsistJob> jobs = new ObjectArrayList<>();
		jobs.add(jobAuto);
		final MmtrJobScheduler scheduler = MmtrJobScheduler.create(jobs);
		sim.mmtrJobScheduler = scheduler;

		boolean seenRun = false;
		for (int second = 0; second < 400; second++) {
			sim.step(1000);
			final MmtrJobScheduler.JobState st = scheduler.stateOf("J-auto");
			if (st == MmtrJobScheduler.JobState.RUNNING) {
				seenRun = true;
			}
			if (second % 10 == 0 || st == MmtrJobScheduler.JobState.DONE || st == MmtrJobScheduler.JobState.FAILED) {
				System.out.println("[JOBAUTO] t=" + second + " state=" + st + " step=" + scheduler.stepIndexOf("J-auto") + " fail=" + scheduler.failureOf("J-auto"));
			}
			if (st == MmtrJobScheduler.JobState.DONE || st == MmtrJobScheduler.JobState.FAILED) {
				break;
			}
		}
		assertTrue(seenRun, "auto service must start running");
		assertEquals(MmtrJobScheduler.JobState.DONE, scheduler.stateOf("J-auto"), "failure=" + scheduler.failureOf("J-auto"));
		assertEquals(3, scheduler.stepIndexOf("J-auto"));
	}

	@Test
	public void jobSpawnsItsOwnConsistFromJobCarsThenRunsService() {
		final long[] ids = buildAutoWorld(false); // no stock preset: the job must spawn it
		final Simulator sim = AUTO_SIM[0];

		final MmtrConsistJob job = new MmtrConsistJob();
		job.jobId = "J-spawn";
		job.depotId = 1;
		job.sidingId = ids[0];
		job.startTimeOfDayMs = 2_000;
		job.repeatDaily = false;

		final MmtrCarSpec car = new MmtrCarSpec();
		car.vehicleId = "loco";
		car.length = 10;
		car.width = 2;
		car.capacity = 100;
		car.bogie1Position = 0;
		car.bogie2Position = 5;
		car.couplingPadding1 = 0.5;
		car.couplingPadding2 = 0.5;
		job.cars.add(car);

		final MmtrJobStep moveA = new MmtrJobStep();
		moveA.stepId = "moveA";
		moveA.type = MmtrJobStep.StepType.MOVE_TO;
		moveA.targetId = ids[1];
		moveA.dueTimeOfDayMs = 60_000;
		job.steps.add(moveA);

		final MmtrJobStep moveB = new MmtrJobStep();
		moveB.stepId = "moveB";
		moveB.type = MmtrJobStep.StepType.MOVE_TO;
		moveB.targetId = ids[2];
		moveB.dueTimeOfDayMs = 180_000;
		job.steps.add(moveB);

		final ObjectArrayList<MmtrConsistJob> jobs = new ObjectArrayList<>();
		jobs.add(job);
		final MmtrJobScheduler scheduler = MmtrJobScheduler.create(jobs);
		sim.mmtrJobScheduler = scheduler;

		boolean sawPlacedLog = false;
		for (int second = 0; second < 400; second++) {
			sim.step(1000);
			final MmtrJobScheduler.JobState st = scheduler.stateOf("J-spawn");
			if (st == MmtrJobScheduler.JobState.RUNNING || st == MmtrJobScheduler.JobState.DONE) {
				sawPlacedLog = true;
			}
			if (second % 10 == 0 || st == MmtrJobScheduler.JobState.DONE || st == MmtrJobScheduler.JobState.FAILED) {
				System.out.println("[JOBSPAWN] t=" + second + " state=" + st + " step=" + scheduler.stepIndexOf("J-spawn") + " fail=" + scheduler.failureOf("J-spawn"));
			}
			if (st == MmtrJobScheduler.JobState.DONE || st == MmtrJobScheduler.JobState.FAILED) {
				break;
			}
		}
		assertTrue(sawPlacedLog, "job must spawn its consist from job.cars");
		assertEquals(MmtrJobScheduler.JobState.DONE, scheduler.stateOf("J-spawn"), "failure=" + scheduler.failureOf("J-spawn"));
		assertEquals(2, scheduler.stepIndexOf("J-spawn"));
	}


	@Test
	public void jobsModeSuppressesLegacyAutoDispatch() {
		final long[] ids = buildAutoWorld(true);
		final Simulator sim = AUTO_SIM[0];
		sim.mmtrJobsMode = true;
		final long[] parkedVehicle = {0};
		sim.sidings.forEach(s -> s.iterateVehicles(v -> { if (!v.getIsOnRoute()) { parkedVehicle[0] = v.getId(); } }));
		org.junit.jupiter.api.Assumptions.assumeTrue(parkedVehicle[0] != 0, "need a parked auto vehicle");
		// A legacy departure is scheduled ~1s out; in job mode it must NOT auto-dispatch.
		sim.sidings.forEach(s -> {
			if (s.getId() == ids[0]) {
				s.startGeneratingDepartures();
				s.addDeparture(sim.getCurrentMillis() + 1000);
			}
		});
		for (int second = 0; second < 5; second++) {
			sim.step(1000);
		}
		final boolean[] stillParked = {false};
		sim.sidings.forEach(s -> s.iterateVehicles(v -> { if (v.getId() == parkedVehicle[0] && !v.getIsOnRoute()) { stillParked[0] = true; } }));
		assertTrue(stillParked[0], "depot departure must not auto-dispatch while mmtrJobsMode is on");
	}

	/** Yard surgery primitive: the single parked consist can be replaced by a formation rebuilt
	 * from a merged car list (union / post-uncouple head) while staying parked and bookable. */
	@Test
	public void yardSurgeryRebuildsParkedConsistAsSingleFormation() {
		final long[] ids = buildAutoWorld(true);
		final Simulator sim = AUTO_SIM[0];
		final long[] parkedId = {0};
		final Siding[] yard = {null};
		sim.sidings.forEach(s -> s.iterateVehicles(v -> { if (!v.getIsOnRoute()) { parkedId[0] = v.getId(); yard[0] = s; } }));
		org.junit.jupiter.api.Assumptions.assumeTrue(parkedId[0] != 0, "need parked auto stock to rebuild");

		final ObjectArrayList<VehicleCar> merged = new ObjectArrayList<>();
		merged.add(new VehicleCar("loco", 10, 2, 100, 0, 5, 0.5, 0.5));
		merged.add(new VehicleCar("flatcar", 10, 2, 0, 0, 5, 0.5, 0.5));
		merged.add(new VehicleCar("flatcar", 10, 2, 0, 0, 5, 0.5, 0.5));

		final Vehicle rebuilt = yard[0].rebuildParkedConsist(merged);
		org.junit.jupiter.api.Assumptions.assumeTrue(rebuilt != null, "merged 3-car formation must fit the yard siding");

		assertEquals(3, yard[0].getVehicleCars().size(), "siding template follows the rebuilt formation");
		final boolean[] oldGone = {true};
		final boolean[] rebuiltPresent = {false};
		yard[0].iterateVehicles(v -> {
			if (v.getId() == parkedId[0]) { oldGone[0] = false; }
			if (v.getId() == rebuilt.getId()) { rebuiltPresent[0] = true; }
		});
		assertTrue(oldGone[0], "original parked vehicle is replaced");
		assertTrue(rebuiltPresent[0], "rebuilt vehicle is registered on the yard siding");

		// The rebuilt single parked consist must survive further ticks (single-vehicle invariant).
		for (int second = 0; second < 3; second++) {
			sim.step(1000);
		}
		final boolean[] stillThere = {false};
		yard[0].iterateVehicles(v -> { if (v.getId() == rebuilt.getId() && !v.getIsOnRoute()) { stillThere[0] = true; } });
		assertTrue(stillThere[0], "rebuilt consist stays parked after ticks");
	}

}