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
}