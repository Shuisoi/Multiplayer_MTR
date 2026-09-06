package org.mtr.core.mmtr.job;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;

/**
 * Executes {@link MmtrConsistJob}s against the live simulator. One job = one consist for one
 * (operational) day: at the job's spawn time-of-day the consist standing on the job's siding
 * starts executing its ordered steps, and every step must finish before its own time-of-day
 * deadline (missing one fails the job).
 *
 * <p>Time base: engine operational clock — dayTime(t) = (t - anchor) mod DAY. The web editor
 * shows HH:MM; in-game-clock mapping is layered on later.</p>
 *
 * <p>Two driving modes:
 * <ul>
 *   <li><b>MANUAL</b> (manual siding): the round-1 executor — a headless autopilot mission runs
 *       to the end of the consist's generated path; each job step completes when the mission
 *       does.</li>
 *   <li><b>AUTO</b> (auto siding): at spawn time a single explicit departure is scheduled on the
 *       siding and the consist is started; the engine ATO then stops at every platform on its
 *       path (doors open/close by dwell). The scheduler walks the job's platform steps
 *       (MOVE_TO completes on arrival, SERVE completes on departure from the platform).</li>
 * </ul>
 * COUPLE/UNCOUPLE step semantics arrive with the coupling executor rounds.
 */
public final class MmtrJobScheduler {

	public enum JobState { PENDING, RUNNING, DONE, FAILED }

	private enum Mode { MANUAL, AUTO }

	private final ObjectArrayList<MmtrConsistJob> jobs = new ObjectArrayList<>();
	private final Object2ObjectOpenHashMap<String, JobInstance> instances = new Object2ObjectOpenHashMap<>();
	private long anchor = Long.MIN_VALUE;

	private MmtrJobScheduler(ObjectArrayList<MmtrConsistJob> jobs) {
		this.jobs.addAll(jobs);
	}

	public static MmtrJobScheduler create(ObjectArrayList<MmtrConsistJob> jobs) {
		return new MmtrJobScheduler(jobs);
	}

	public JobState stateOf(String jobId) {
		final JobInstance instance = instances.get(jobId);
		return instance == null ? null : instance.state;
	}

	public int stepIndexOf(String jobId) {
		final JobInstance instance = instances.get(jobId);
		return instance == null ? -1 : instance.stepIndex;
	}

	public String failureOf(String jobId) {
		final JobInstance instance = instances.get(jobId);
		return instance == null ? null : instance.failureReason;
	}

	public void tick(long currentMillis, Simulator simulator) {
		if (anchor == Long.MIN_VALUE) {
			anchor = currentMillis;
		}
		final long dayTime = (currentMillis - anchor) % Utilities.MILLIS_PER_DAY;
		for (final MmtrConsistJob job : jobs) {
			final JobInstance instance = instances.computeIfAbsent(job.jobId, key -> new JobInstance(job));
			switch (instance.state) {
				case PENDING -> pending(instance, currentMillis, dayTime, simulator);
				case RUNNING -> running(instance, currentMillis, dayTime, simulator);
				default -> {
				}
			}
		}
	}

	private void pending(JobInstance instance, long currentMillis, long dayTime, Simulator simulator) {
		if (dayTime < instance.job.startTimeOfDayMs) {
			return;
		}
		final Vehicle vehicle = findParkedVehicle(simulator, instance.job.sidingId);
		if (vehicle == null) {
			fail(instance, "no idle stock on siding at spawn time");
			return;
		}
		instance.vehicleId = vehicle.getId();
		instance.startAbs = anchor + instance.job.startTimeOfDayMs;
		instance.mode = vehicle.vehicleExtraData.getIsManualAllowed() ? Mode.MANUAL : Mode.AUTO;
		if (instance.mode == Mode.MANUAL) {
			runManualStep(instance, simulator);
			instance.state = JobState.RUNNING;
		} else {
			if (!startAutoService(instance, simulator)) {
				return; // startAutoService failed the instance already
			}
			instance.state = JobState.RUNNING;
		}
	}

	private void running(JobInstance instance, long currentMillis, long dayTime, Simulator simulator) {
		if (instance.stepIndex >= instance.job.steps.size()) {
			instance.state = JobState.DONE;
			return;
		}
		final MmtrJobStep step = instance.job.steps.get((int) instance.stepIndex);
		if (dayTime > instance.deadlineOf(step)) {
			fail(instance, "step " + step.stepId + " missed deadline (due " + step.dueTimeOfDayMs + ")");
			return;
		}
		if (instance.mode == Mode.MANUAL) {
			advanceManual(instance, simulator);
		} else {
			advanceAuto(instance, simulator);
		}
	}

	// --- MANUAL mode: single headless mission per job (round-1 semantics) ---

	private void runManualStep(JobInstance instance, Simulator simulator) {
		final MmtrJobStep step = instance.job.steps.get((int) instance.stepIndex);
		final Vehicle vehicle = findVehicle(simulator, instance.vehicleId);
		if (vehicle == null) {
			fail(instance, "consist vanished before step " + step.stepId);
			return;
		}
		final MmtrMission mission = new MmtrMission(vehicle.getId(), step.type == MmtrJobStep.StepType.SERVE ? MmtrMission.Kind.PASSENGER : MmtrMission.Kind.MANEUVER, instance.job.sidingId, 0, simulator.getCurrentMillis());
		if (!vehicle.setMmtrMission(mission)) {
			fail(instance, "could not attach mission for step " + step.stepId);
			return;
		}
		vehicle.engageMissionAutopilot();
	}

	private void advanceManual(JobInstance instance, Simulator simulator) {
		final Vehicle vehicle = findVehicle(simulator, instance.vehicleId);
		if (vehicle == null) {
			fail(instance, "consist vanished mid-job");
			return;
		}
		final MmtrMission mission = vehicle.getMmtrMission();
		if (mission == null || !mission.isTerminal()) {
			return;
		}
		if (mission.getState() != MmtrMission.State.COMPLETE) {
			fail(instance, "mission for step ended " + mission.getState());
			return;
		}
		instance.stepIndex++;
		if (instance.stepIndex >= instance.job.steps.size()) {
			instance.state = JobState.DONE;
		} else {
			runManualStep(instance, simulator);
		}
	}

	// --- AUTO mode: engine ATO runs the generated service; steps track platform visits ---

	private boolean startAutoService(JobInstance instance, Simulator simulator) {
		final Siding siding = findSiding(simulator, instance.job.sidingId);
		final Vehicle vehicle = findVehicle(simulator, instance.vehicleId);
		if (siding == null || vehicle == null) {
			fail(instance, "auto service siding/vehicle unavailable");
			return false;
		}
		// Give the engine one valid departure entry so its bookkeeping keeps the consist while
		// it runs the service, then start it explicitly at the job time.
		siding.startGeneratingDepartures();
		if (!siding.addDeparture(instance.startAbs)) {
			fail(instance, "could not schedule auto departure");
			return false;
		}
		vehicle.startUp(0, instance.startAbs);
		System.out.println("[MMTR-JOB] auto service started vehicle=" + vehicle.getId() + " startAbs=" + instance.startAbs);
		return true;
	}

	private void advanceAuto(JobInstance instance, Simulator simulator) {
		final MmtrJobStep step = instance.job.steps.get((int) instance.stepIndex);
		final Vehicle vehicle = findVehicle(simulator, instance.vehicleId);
		if (vehicle == null) {
			fail(instance, "consist vanished mid-service");
			return;
		}
		if (step.type == MmtrJobStep.StepType.COUPLE || step.type == MmtrJobStep.StepType.UNCOUPLE) {
			fail(instance, "coupling steps not wired to the executor yet");
			return;
		}
		final long platformNow = vehicle.vehicleExtraData.getThisPlatformId();
		final boolean stoppedAtTarget = !vehicle.isMoving() && vehicle.getIsOnRoute() && platformNow == step.targetId;

		if (step.type == MmtrJobStep.StepType.MOVE_TO) {
			if (stoppedAtTarget) {
				System.out.println("[MMTR-JOB] MOVE_TO done at platform " + step.targetId);
				instance.stepIndex++;
			}
		} else if (step.type == MmtrJobStep.StepType.SERVE) {
			// Complete when the consist leaves the platform (doors have run their dwell cycle).
			final boolean atTarget = platformNow == step.targetId;
			if (instance.wasAtTarget && !atTarget) {
				System.out.println("[MMTR-JOB] SERVE done (departed platform " + step.targetId + ")");
				instance.stepIndex++;
			}
			instance.wasAtTarget = atTarget || instance.wasAtTarget && stoppedAtTarget;
		}

		if (instance.stepIndex >= instance.job.steps.size()) {
			instance.state = JobState.DONE;
		}
	}

	private static void fail(JobInstance instance, String reason) {
		instance.state = JobState.FAILED;
		instance.failureReason = reason;
	}

	@Nullable
	private static Vehicle findParkedVehicle(Simulator simulator, long sidingId) {
		final Vehicle[] found = {null};
		simulator.sidings.forEach(siding -> {
			if (found[0] != null || siding.getId() != sidingId) {
				return;
			}
			siding.iterateVehicles(vehicle -> {
				if (found[0] == null && !vehicle.getIsOnRoute()) {
					final MmtrMission existing = vehicle.getMmtrMission();
					if (existing == null || existing.isTerminal()) {
						found[0] = vehicle;
					}
				}
			});
		});
		return found[0];
	}

	@Nullable
	private static Vehicle findVehicle(Simulator simulator, long vehicleId) {
		final Vehicle[] found = {null};
		simulator.sidings.forEach(siding -> siding.iterateVehicles(vehicle -> {
			if (found[0] == null && vehicle.getId() == vehicleId) {
				found[0] = vehicle;
			}
		}));
		return found[0];
	}

	@Nullable
	private static Siding findSiding(Simulator simulator, long sidingId) {
		final Siding[] found = {null};
		simulator.sidings.forEach(siding -> {
			if (siding.getId() == sidingId) {
				found[0] = siding;
			}
		});
		return found[0];
	}

	private static final class JobInstance {

		final MmtrConsistJob job;
		JobState state = JobState.PENDING;
		Mode mode = Mode.MANUAL;
		int stepIndex;
		long vehicleId;
		@Nullable String failureReason;
		long startAbs;
		/** Whether the consist was last seen stopped at the current step's target platform (AUTO SERVE). */
		boolean wasAtTarget;

		JobInstance(MmtrConsistJob job) {
			this.job = job;
		}

		long deadlineOf(MmtrJobStep step) {
			long deadline = step.dueTimeOfDayMs;
			if (deadline < job.startTimeOfDayMs) {
				deadline += Utilities.MILLIS_PER_DAY;
			}
			return deadline;
		}
	}
}
