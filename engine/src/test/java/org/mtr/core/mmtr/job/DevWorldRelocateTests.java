package org.mtr.core.mmtr.job;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Depot;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2-Core: terminal cross-side relocation on the real yard (consist moves 1 -> 2 by re-birth). */
public final class DevWorldRelocateTests {
	private static final Path DEV_MTR_ROOT = Paths.get("C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/saves/新的世界/mtr");
	private static final long SIDING_1 = 7707385017525480299L;
	private static final long SIDING_2 = -8058689381957463612L;

	@Test
	public void realYardRelocatesConsistFromSiding1ToSiding2() {
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(DEV_MTR_ROOT), "dev world save not present - skipping");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, DEV_MTR_ROOT, false);
		Depot.generateDepots(sim, new ObjectArrayList<>(sim.depots));
		for (int i = 0; i < 300; i++) { sim.step(1000); }
		sim.mmtrJobsMode = true;
		sim.sidings.forEach(s -> { if (s.getId() == SIDING_1 || s.getId() == SIDING_2) { s.clearParkedVehicles(); } });

		final MmtrConsistJob job = new MmtrConsistJob();
		job.jobId = "REL-1TO2";
		job.depotId = 1;
		job.sidingId = SIDING_1;
		job.startTimeOfDayMs = 2_000;
		job.repeatDaily = false;
		final MmtrCarSpec loco = new MmtrCarSpec();
		loco.vehicleId = "loco"; loco.length = 10; loco.width = 2; loco.capacity = 100;
		loco.bogie1Position = 0; loco.bogie2Position = 5; loco.couplingPadding1 = 0.5; loco.couplingPadding2 = 0.5;
		job.cars.add(loco);
		final MmtrJobStep to2 = new MmtrJobStep();
		to2.stepId = "toSiding2"; to2.type = MmtrJobStep.StepType.MOVE_TO;
		to2.targetId = SIDING_2; to2.dueTimeOfDayMs = 900_000;
		job.steps.add(to2);

		final ObjectArrayList<MmtrConsistJob> jobs = new ObjectArrayList<>();
		jobs.add(job);
		final MmtrJobScheduler scheduler = MmtrJobScheduler.create(jobs);
		sim.mmtrJobScheduler = scheduler;

		for (int second = 0; second < 600; second++) {
			sim.step(1000);
			final MmtrJobScheduler.JobState st = scheduler.stateOf("REL-1TO2");
			if (second % 40 == 0 || st == MmtrJobScheduler.JobState.DONE || st == MmtrJobScheduler.JobState.FAILED) {
				System.out.println("[REL] t=" + second + " state=" + st + " step=" + scheduler.stepIndexOf("REL-1TO2") + " fail=" + scheduler.failureOf("REL-1TO2"));
			}
			if (st == MmtrJobScheduler.JobState.DONE || st == MmtrJobScheduler.JobState.FAILED) { break; }
		}
		assertEquals(MmtrJobScheduler.JobState.DONE, scheduler.stateOf("REL-1TO2"), "fail=" + scheduler.failureOf("REL-1TO2"));
		final boolean[] parkedOn2 = {false};
		sim.sidings.forEach(s -> s.iterateVehicles(v -> { if (s.getId() == SIDING_2 && !v.getIsOnRoute()) { parkedOn2[0] = true; } }));
		assertTrue(parkedOn2[0], "the consist must end parked on siding 2");
	}
	@Test
	public void realYardMultiHopRelocationS1toS2toS1() {
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(DEV_MTR_ROOT), "dev world save not present - skipping");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, DEV_MTR_ROOT, false);
		Depot.generateDepots(sim, new ObjectArrayList<>(sim.depots));
		for (int i = 0; i < 300; i++) { sim.step(1000); }
		sim.mmtrJobsMode = true;
		sim.sidings.forEach(s -> { if (s.getId() == SIDING_1 || s.getId() == SIDING_2) { s.clearParkedVehicles(); } });

		final MmtrConsistJob job = new MmtrConsistJob();
		job.jobId = "HOP-1-2-1";
		job.depotId = 1;
		job.sidingId = SIDING_1;
		job.startTimeOfDayMs = 2_000;
		job.repeatDaily = false;
		final MmtrCarSpec loco = new MmtrCarSpec();
		loco.vehicleId = "loco"; loco.length = 10; loco.width = 2; loco.capacity = 100;
		loco.bogie1Position = 0; loco.bogie2Position = 5; loco.couplingPadding1 = 0.5; loco.couplingPadding2 = 0.5;
		job.cars.add(loco);
		final MmtrJobStep to2 = new MmtrJobStep();
		to2.stepId = "to2"; to2.type = MmtrJobStep.StepType.MOVE_TO; to2.targetId = SIDING_2; to2.dueTimeOfDayMs = 600_000;
		job.steps.add(to2);
		final MmtrJobStep back1 = new MmtrJobStep();
		back1.stepId = "back1"; back1.type = MmtrJobStep.StepType.MOVE_TO; back1.targetId = SIDING_1; back1.dueTimeOfDayMs = 1_200_000;
		job.steps.add(back1);

		final ObjectArrayList<MmtrConsistJob> jobs = new ObjectArrayList<>();
		jobs.add(job);
		final MmtrJobScheduler scheduler = MmtrJobScheduler.create(jobs);
		sim.mmtrJobScheduler = scheduler;

		for (int second = 0; second < 900; second++) {
			sim.step(1000);
			final MmtrJobScheduler.JobState st = scheduler.stateOf("HOP-1-2-1");
			if (second % 60 == 0 || st == MmtrJobScheduler.JobState.DONE || st == MmtrJobScheduler.JobState.FAILED) {
				System.out.println("[HOP] t=" + second + " state=" + st + " step=" + scheduler.stepIndexOf("HOP-1-2-1") + " fail=" + scheduler.failureOf("HOP-1-2-1"));
			}
			if (st == MmtrJobScheduler.JobState.DONE || st == MmtrJobScheduler.JobState.FAILED) { break; }
		}
		assertEquals(MmtrJobScheduler.JobState.DONE, scheduler.stateOf("HOP-1-2-1"), "fail=" + scheduler.failureOf("HOP-1-2-1"));
		assertEquals(2, scheduler.stepIndexOf("HOP-1-2-1"));
		final boolean[] parkedOn1 = {false};
		sim.sidings.forEach(s -> s.iterateVehicles(v -> { if (s.getId() == SIDING_1 && !v.getIsOnRoute()) { parkedOn1[0] = true; } }));
		assertTrue(parkedOn1[0], "after the multi-hop the consist must be parked back on siding 1");
	}

	@Test
	public void realYardArrivalMakeupMergesWagonOnSiding2() {
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(DEV_MTR_ROOT), "dev world save not present - skipping");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, DEV_MTR_ROOT, false);
		Depot.generateDepots(sim, new ObjectArrayList<>(sim.depots));
		for (int i = 0; i < 300; i++) { sim.step(1000); }
		sim.mmtrJobsMode = true;
		sim.sidings.forEach(s -> { if (s.getId() == SIDING_1 || s.getId() == SIDING_2) { s.clearParkedVehicles(); s.setVehicleCars(new ObjectArrayList<>()); } });

		final MmtrConsistJob wagon = new MmtrConsistJob();
		wagon.jobId = "W-S2";
		wagon.depotId = 1;
		wagon.sidingId = SIDING_2;
		wagon.startTimeOfDayMs = 1_000;
		wagon.repeatDaily = false;
		final MmtrCarSpec flat = new MmtrCarSpec();
		flat.vehicleId = "flatcar"; flat.length = 10; flat.width = 2; flat.capacity = 100;
		flat.bogie1Position = 0; flat.bogie2Position = 5; flat.couplingPadding1 = 0.5; flat.couplingPadding2 = 0.5;
		wagon.cars.add(flat);

		final MmtrConsistJob loco = new MmtrConsistJob();
		loco.jobId = "M-S1";
		loco.depotId = 1;
		loco.sidingId = SIDING_1;
		loco.startTimeOfDayMs = 20_000;
		loco.repeatDaily = false;
		final MmtrCarSpec engine = new MmtrCarSpec();
		engine.vehicleId = "loco"; engine.length = 10; engine.width = 2; engine.capacity = 100;
		engine.bogie1Position = 0; engine.bogie2Position = 5; engine.couplingPadding1 = 0.5; engine.couplingPadding2 = 0.5;
		loco.cars.add(engine);
		final MmtrJobStep to2 = new MmtrJobStep();
		to2.stepId = "to2"; to2.type = MmtrJobStep.StepType.MOVE_TO; to2.targetId = SIDING_2; to2.dueTimeOfDayMs = 900_000;
		loco.steps.add(to2);

		final ObjectArrayList<MmtrConsistJob> jobs = new ObjectArrayList<>();
		jobs.add(wagon);
		jobs.add(loco);
		final MmtrJobScheduler scheduler = MmtrJobScheduler.create(jobs);
		sim.mmtrJobScheduler = scheduler;

		for (int second = 0; second < 700; second++) {
			sim.step(1000);
			final MmtrJobScheduler.JobState st = scheduler.stateOf("M-S1");
			if (second % 60 == 0 || st == MmtrJobScheduler.JobState.DONE || st == MmtrJobScheduler.JobState.FAILED) {
				System.out.println("[MERGE] t=" + second + " state=" + st + " step=" + scheduler.stepIndexOf("M-S1") + " cars=" + scheduler.carsOf("M-S1") + " fail=" + scheduler.failureOf("M-S1"));
			}
			if (st == MmtrJobScheduler.JobState.DONE || st == MmtrJobScheduler.JobState.FAILED) { break; }
		}
		assertEquals(MmtrJobScheduler.JobState.DONE, scheduler.stateOf("M-S1"), "fail=" + scheduler.failureOf("M-S1"));
		assertEquals(1, scheduler.stepIndexOf("M-S1"));
		assertEquals(2, scheduler.carsOf("M-S1"), "arrival make-up must merge loco + wagon into 2 cars");
		assertEquals(MmtrJobScheduler.JobState.DONE, scheduler.stateOf("W-S2"), "wagon source consumed");
		final int[] parkedOn2 = {0};
		sim.sidings.forEach(s -> s.iterateVehicles(v -> { if (s.getId() == SIDING_2 && !v.getIsOnRoute()) { parkedOn2[0]++; } }));
		assertTrue(parkedOn2[0] == 1, "exactly one merged consist parks on siding 2");
	}

}