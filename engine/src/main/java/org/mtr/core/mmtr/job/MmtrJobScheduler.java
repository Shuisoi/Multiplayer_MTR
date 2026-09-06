package org.mtr.core.mmtr.job;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Vehicle;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;

/**
 * Executes {@link MmtrConsistJob}s against the live simulator. One job = one consist for one
 * (operational) day: at the job's spawn time-of-day the consist already standing on the job's
 * siding starts executing its ordered steps. Each step must complete before its own
 * time-of-day deadline; missing a deadline fails the job.
 *
 * <p>Time base: the engine's operational clock — {@code dayTime(t) = (t - anchor) mod DAY}.
 * Real-server in-game-clock mapping is layered on later (web editor shows HH:MM).</p>
 *
 * <p>Step driving (round 1): MOVE_TO is executed through the mission engine's headless autopilot
 * to the end of the consist's generated path (terminal). Per-platform ATO stops, SERVE door
 * work and COUPLE/UNCOUPLE attach their step semantics in the next executor rounds.</p>
 */
public final class MmtrJobScheduler {

	public enum JobState { PENDING, RUNNING, DONE, FAILED }

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
			final JobInstance instance = instances.computeIfAbsent(job.jobId, key -> new JobInstance(job, dayTime));
			switch (instance.state) {
				case PENDING -> startIfDue(instance, dayTime, simulator);
				case RUNNING -> advanceRunning(instance, currentMillis, dayTime, simulator);
				default -> {
				}
			}
		}
	}

	private void startIfDue(JobInstance instance, long dayTime, Simulator simulator) {
		final MmtrConsistJob job = instance.job;
		if (dayTime < job.startTimeOfDayMs) {
			return;
		}
		final Vehicle vehicle = findIdleVehicle(simulator, job.sidingId);
		if (vehicle == null) {
			fail(instance, "no idle stock on siding at spawn time");
			return;
		}
		instance.vehicleId = vehicle.getId();
		runStep(instance, simulator);
	}

	private void advanceRunning(JobInstance instance, long currentMillis, long dayTime, Simulator simulator) {
		if (instance.stepIndex >= instance.job.steps.size()) {
			instance.state = JobState.DONE;
			return;
		}
		final MmtrJobStep step = instance.job.steps.get((int) instance.stepIndex);
		if (dayTime > instance.deadlineOf(step)) {
			fail(instance, "step " + step.stepId + " missed deadline (due " + step.dueTimeOfDayMs + ")");
			return;
		}
		final Vehicle vehicle = findVehicle(simulator, instance.vehicleId);
		if (vehicle == null) {
			fail(instance, "consist vanished mid-job");
			return;
		}
		final MmtrMission mission = vehicle.getMmtrMission();
		if (mission != null && mission.isTerminal()) {
			if (mission.getState() == MmtrMission.State.FAILED || mission.getState() == MmtrMission.State.CANCELED) {
				fail(instance, "mission for step " + step.stepId + " " + mission.getState());
			} else {
				// Step completed: advance to the next step (or finish the diagram).
				instance.stepIndex++;
				if (instance.stepIndex >= instance.job.steps.size()) {
					instance.state = JobState.DONE;
				} else {
					runStep(instance, simulator);
				}
			}
		}
	}

	private void runStep(JobInstance instance, Simulator simulator) {
		final MmtrJobStep step = instance.job.steps.get((int) instance.stepIndex);
		final Vehicle vehicle = findVehicle(simulator, instance.vehicleId);
		if (vehicle == null) {
			fail(instance, "consist vanished before step " + step.stepId);
			return;
		}
		// Round 1 driving: headless autopilot mission to the end of the consist's current path.
		// Step kind semantics (per-platform stops / doors / coupling) land with the executor
		// rounds that drive ATO stops.
		final MmtrMission mission = new MmtrMission(vehicle.getId(), step.type == MmtrJobStep.StepType.SERVE ? MmtrMission.Kind.PASSENGER : MmtrMission.Kind.MANEUVER, instance.job.sidingId, 0, simulator.getCurrentMillis());
		if (!vehicle.setMmtrMission(mission)) {
			fail(instance, "could not attach mission for step " + step.stepId);
			return;
		}
		vehicle.engageMissionAutopilot();
		instance.state = JobState.RUNNING;
	}

	private static void fail(JobInstance instance, String reason) {
		instance.state = JobState.FAILED;
		instance.failureReason = reason;
	}

	private static Vehicle findIdleVehicle(Simulator simulator, long sidingId) {
		final Vehicle[] found = {null};
		simulator.sidings.forEach(siding -> {
			if (found[0] != null || siding.getId() != sidingId) {
				return;
			}
			siding.iterateVehicles(vehicle -> {
				if (found[0] == null && !vehicle.getIsOnRoute() && vehicle.vehicleExtraData.getIsManualAllowed()) {
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

	private static final class JobInstance {

		final MmtrConsistJob job;
		JobState state = JobState.PENDING;
		int stepIndex;
		long vehicleId;
		@Nullable String failureReason;
		/** Spawn-time of-day that started this instance (used for wrap-safe deadlines). */
		final long startDayTime;

		JobInstance(MmtrConsistJob job, long currentDayTime) {
			this.job = job;
			startDayTime = currentDayTime;
		}

		/** Absolute (day-anchored) deadline of a step, wrapping to the next day if before the start. */
		long deadlineOf(MmtrJobStep step) {
			long deadline = step.dueTimeOfDayMs;
			// Steps run in order after the spawn time; a deadline earlier in the day belongs to
			// the next operational day.
			if (deadline < startDayTime) {
				deadline += Utilities.MILLIS_PER_DAY;
			}
			return deadline;
		}
	}
}
