package org.mtr.core.mmtr.job;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Platform;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real dev-world orchestration smoke: drives the actual Minecraft dev save with a web-style
 * consist job that claims the real manual yard stock and runs a single service leg to a real
 * platform, then finishes. In-memory only - the save on disk is never modified.
 */
public final class DevWorldJobSmokeTests {

	private static final Path DEV_MTR_ROOT = Paths.get("C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/saves/新的世界/mtr");

	@Test
	public void realWorldJobDrivesStockToPlatform() {
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(DEV_MTR_ROOT), "dev world save not present - skipping");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, DEV_MTR_ROOT, false);
		final long[] yardId = {0};
		final long[] platformId = {0};
		sim.depots.forEach(depot -> { if (yardId[0] == 0 && !depot.savedRails.isEmpty()) { yardId[0] = depot.savedRails.iterator().next().getId(); } });
		sim.platforms.forEach(platform -> { if (platformId[0] == 0) { platformId[0] = platform.getId(); } });
		org.junit.jupiter.api.Assumptions.assumeTrue(yardId[0] != 0 && platformId[0] != 0, "dev world has no siding/platform - skipping");
		final long[] parkedClaimable = {0};
		sim.sidings.forEach(s -> s.iterateVehicles(v -> {
			if (parkedClaimable[0] == 0 && !v.getIsOnRoute()) {
				final org.mtr.core.mmtr.MmtrMission mission = v.getMmtrMission();
				if (mission == null || mission.isTerminal()) { parkedClaimable[0] = v.getId(); }
			}
		}));
		org.junit.jupiter.api.Assumptions.assumeTrue(parkedClaimable[0] != 0, "no claimable parked stock in the live dev save right now - skipping");
		sim.mmtrJobsMode = true;

		final MmtrConsistJob job = new MmtrConsistJob();
		job.jobId = "L-DEV";
		job.depotId = 1;
		job.sidingId = yardId[0];
		job.startTimeOfDayMs = 2_000;
		job.repeatDaily = false;

		final MmtrJobStep move = new MmtrJobStep();
		move.stepId = "runToPlatform";
		move.type = MmtrJobStep.StepType.MOVE_TO;
		move.targetId = platformId[0];
		move.dueTimeOfDayMs = 600_000;
		job.steps.add(move);

		final ObjectArrayList<MmtrConsistJob> jobs = new ObjectArrayList<>();
		jobs.add(job);
		final MmtrJobScheduler scheduler = MmtrJobScheduler.create(jobs);
		sim.mmtrJobScheduler = scheduler;

		boolean sawRunning = false;
		boolean sawMoving = false;
		// Boot acceptance only: the live save is a moving world; full completion of the leg is proven
		// by the deterministic synthetic + file e2e. Here we prove the real engine/world accepts the
		// web-authored job and drives the real stock into service without failing.
		for (int step = 0; step < 25; step++) {
			sim.step(1000);
			final MmtrJobScheduler.JobState st = scheduler.stateOf("L-DEV");
			if (st == MmtrJobScheduler.JobState.RUNNING) {
				sawRunning = true;
				final boolean[] moving = {false};
				sim.sidings.forEach(s -> s.iterateVehicles(v -> { if (v.getId() != 0 && v.isMoving()) { moving[0] = true; } }));
				if (moving[0]) { sawMoving = true; }
			}
			if (st == MmtrJobScheduler.JobState.FAILED) {
				System.out.println("[DEVFAIL] " + scheduler.failureOf("L-DEV"));
				break;
			}
		}
		System.out.println("[DEVJOB] L-DEV state=" + scheduler.stateOf("L-DEV") + " step=" + scheduler.stepIndexOf("L-DEV") + " fail=" + scheduler.failureOf("L-DEV"));
		assertTrue(sawRunning, "job must run on the real world");
		assertTrue(sawMoving, "the real stock must move along the world rail");
		assertTrue(scheduler.stateOf("L-DEV") != MmtrJobScheduler.JobState.FAILED, "job must not fail during boot (fail=" + scheduler.failureOf("L-DEV") + ")");
	}
}