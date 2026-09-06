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

	private static final long SPAWN_GRACE_MILLIS = 30_000;

	private void pending(JobInstance instance, long currentMillis, long dayTime, Simulator simulator) {
		if (dayTime < instance.job.startTimeOfDayMs) {
			return;
		}
		// Coupling make-up start: the job begins by collecting another job's parked stock on the
		// same yard siding (its first step is COUPLE). We must NOT spawn our own stock separately -
		// the merged formation replaces the target's parked consist via the yard surgery primitive.
		final boolean startsWithCouple = instance.stepIndex == 0 && !instance.job.steps.isEmpty()
			&& instance.job.steps.get(0).type == MmtrJobStep.StepType.COUPLE;
		if (startsWithCouple) {
			switch (tryCoupleStart(instance, dayTime, simulator)) {
				case WAIT:
				case FAILED:
					return;
				case DONE:
					instance.state = JobState.DONE;
					return;
				default:
					break; // MERGED: continue with the regular claim/start flow below
			}
		}
		final Vehicle vehicle = instance.vehicleId == 0 ? findFreeParkedVehicle(simulator, instance) : findVehicle(simulator, instance.vehicleId);
		if (vehicle == null) {
			// Spawn the consist from the job's rolling-stock template the first time we are due.
			// The engine creates the parked vehicle on its next siding tick; until then we stay
			// pending (a 30s grace protects against a siding that can never spawn).
			if (!instance.carsPlaced) {
				if (instance.job.cars.isEmpty()) {
					fail(instance, "job defines no rolling stock");
					return;
				}
				if (!placeCars(simulator, instance.job)) {
					fail(instance, "could not place job rolling stock on siding");
					return;
				}
				instance.carsPlaced = true;
				System.out.println("[MMTR-JOB] placed " + instance.job.cars.size() + " car(s) from job " + instance.job.jobId);
			}
			if (dayTime > instance.job.startTimeOfDayMs + SPAWN_GRACE_MILLIS) {
				fail(instance, "stock never spawned on siding");
			}
			return;
		}
		instance.vehicleId = vehicle.getId();
		instance.startAbs = anchor + instance.job.startTimeOfDayMs;
		instance.mode = vehicle.vehicleExtraData.getIsManualAllowed() ? Mode.MANUAL : Mode.AUTO;
		if (instance.job.steps.isEmpty()) {
			// Stock-only job (e.g. the trailer consist a later COUPLE make-up collects): spawn, park
			// and wait - never attach a mission to an empty step list.
			instance.state = JobState.DONE;
			System.out.println("[MMTR-JOB] stock-only job " + instance.job.jobId + " parked on siding (available for coupling)");
			return;
		}
		if (instance.fleetCars.isEmpty()) {
			instance.fleetCars.addAll(instance.job.cars);
		}
		// Yard ops run while the consist is still parked on its own siding and before any mission /
		// ATO departure: UNCOUPLE cuts trailing cars off (head keeps the job, tail stays in the yard
		// as the next stock source). A second COUPLE here is not a supported formation (another job's
		// stock cannot share this siding at the same time).
		while (instance.stepIndex < instance.job.steps.size()) {
			final MmtrJobStep yardStep = instance.job.steps.get((int) instance.stepIndex);
			if (yardStep.type == MmtrJobStep.StepType.UNCOUPLE) {
				if (dayTime > instance.deadlineOf(yardStep)) {
					fail(instance, "step " + yardStep.stepId + " missed deadline (due " + yardStep.dueTimeOfDayMs + ")");
					return;
				}
				if (!executeUncouple(instance, simulator)) {
					return; // executeUncouple failed the instance already
				}
			} else if (yardStep.type == MmtrJobStep.StepType.COUPLE) {
				fail(instance, "additional COUPLE at step " + yardStep.stepId + " is not supported (one make-up per job)");
				return;
			} else {
				break;
			}
		}
		if (instance.stepIndex >= instance.job.steps.size()) {
			instance.state = JobState.DONE;
			return;
		}
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

	/** Outcome of a coupling make-up attempt while the job is still pending on its yard siding. */
	private enum CoupleOutcome { WAIT, MERGED, DONE, FAILED }

	/**
	 * COUPLE-as-first-step: collect the target job's parked stock on this siding. The merged
	 * formation (this job's cars first, then the target job's trailers) replaces the target's
	 * parked vehicle through the yard surgery, so the scheduler stays on ONE vehicle.
	 */
	private CoupleOutcome tryCoupleStart(JobInstance instance, long dayTime, Simulator simulator) {
		final MmtrJobStep first = instance.job.steps.get(0);
		final String targetJobId = first.targetJobId == null ? "" : first.targetJobId.trim();
		if (targetJobId.isEmpty() || targetJobId.equals(instance.job.jobId)) {
			fail(instance, "COUPLE step needs a target job id different from this job");
			return CoupleOutcome.FAILED;
		}
		boolean known = false;
		for (final MmtrConsistJob job : jobs) {
			if (job.jobId.equals(targetJobId)) {
				known = true;
				break;
			}
		}
		if (!known) {
			fail(instance, "coupling target job '" + targetJobId + "' is not loaded by the scheduler");
			return CoupleOutcome.FAILED;
		}
		final JobInstance target = instances.computeIfAbsent(targetJobId, key -> new JobInstance(jobOf(targetJobId)));
		if (target == instance) {
			fail(instance, "a job cannot couple onto itself");
			return CoupleOutcome.FAILED;
		}
		if (target.consumed) {
			fail(instance, "coupling target job '" + targetJobId + "' stock was already coupled into another job");
			return CoupleOutcome.FAILED;
		}
		if (target.vehicleId == 0 || target.state == JobState.FAILED) {
			// Target has not spawned (yet): wait for it up to this step's deadline.
			if (dayTime > instance.deadlineOf(first)) {
				fail(instance, "coupling target job '" + targetJobId + "' never spawned before due " + first.dueTimeOfDayMs);
				return CoupleOutcome.FAILED;
			}
			return CoupleOutcome.WAIT;
		}
		final Siding yard = findSiding(simulator, instance.job.sidingId);
		final Vehicle tail = yard == null ? null : yard.getVehicleById(target.vehicleId);
		if (yard == null || tail == null || tail.getIsOnRoute()) {
			if (dayTime > instance.deadlineOf(first)) {
				fail(instance, "coupling target stock is not parked on the yard siding before due " + first.dueTimeOfDayMs);
				return CoupleOutcome.FAILED;
			}
			return CoupleOutcome.WAIT;
		}
		instance.fleetCars.clear();
		instance.fleetCars.addAll(instance.job.cars);
		instance.fleetCars.addAll(target.job.cars);
		final ObjectArrayList<org.mtr.core.data.VehicleCar> merged = toVehicleCars(instance.fleetCars);
		final Vehicle rebuilt = yard.rebuildParkedConsist(merged);
		if (rebuilt == null) {
			fail(instance, "coupled formation does not fit the yard siding (cars=" + merged.size() + ")");
			return CoupleOutcome.FAILED;
		}
		target.consumed = true;
		target.state = JobState.DONE;
		instance.vehicleId = rebuilt.getId();
		instance.startAbs = anchor + instance.job.startTimeOfDayMs;
		instance.stepIndex = 1; // the COUPLE step completed with the merge
		System.out.println("[MMTR-JOB] coupled " + targetJobId + " stock onto " + instance.job.jobId + " -> vehicle " + rebuilt.getId() + " cars=" + merged.size());
		return instance.stepIndex >= instance.job.steps.size() ? CoupleOutcome.DONE : CoupleOutcome.MERGED;
	}

	private MmtrConsistJob jobOf(String jobId) {
		for (final MmtrConsistJob job : jobs) {
			if (job.jobId.equals(jobId)) {
				return job;
			}
		}
		throw new IllegalArgumentException("unknown job " + jobId);
	}

	/** The first parked, mission-idle vehicle on this job's siding that no other job has claimed. */
	@Nullable
	private Vehicle findFreeParkedVehicle(Simulator simulator, JobInstance self) {
		final Vehicle[] found = {null};
		simulator.sidings.forEach(siding -> {
			if (found[0] != null || siding.getId() != self.job.sidingId) {
				return;
			}
			siding.iterateVehicles(vehicle -> {
				if (found[0] == null && !vehicle.getIsOnRoute() && !claimedByOther(vehicle.getId(), self.job.jobId)) {
					final MmtrMission existing = vehicle.getMmtrMission();
					if (existing == null || existing.isTerminal()) {
						found[0] = vehicle;
					}
				}
			});
		});
		return found[0];
	}

	private boolean claimedByOther(long vehicleId, String selfJobId) {
		for (final JobInstance other : instances.values()) {
			if (!other.job.jobId.equals(selfJobId) && !other.consumed && other.vehicleId == vehicleId) {
				return true;
			}
		}
		return false;
	}

	/**
	 * UNCOUPLE on the yard: split the parked consist after {@code targetIndex}. The head keeps the
	 * job (rebuilt through the yard surgery); the cut tail becomes the siding's next stock source -
	 * the engine respawns it as a parked vehicle once the head has cleared the siding, which keeps
	 * the engine's single-parked-vehicle invariant intact.
	 */
	private boolean executeUncouple(JobInstance instance, Simulator simulator) {
		final MmtrJobStep step = instance.job.steps.get((int) instance.stepIndex);
		final int cut = step.targetIndex;
		final Siding yard = findSiding(simulator, instance.job.sidingId);
		final Vehicle current = findVehicle(simulator, instance.vehicleId);
		if (yard == null || current == null || current.getIsOnRoute()) {
			fail(instance, "uncouple requires the consist parked at its yard siding (step " + step.stepId + ")");
			return false;
		}
		if (instance.fleetCars.isEmpty()) {
			instance.fleetCars.addAll(instance.job.cars);
		}
		if (cut < 0 || cut >= instance.fleetCars.size() - 1) {
			fail(instance, "uncouple cut index " + cut + " must leave at least one car on each side (" + instance.fleetCars.size() + " cars)");
			return false;
		}
		final ObjectArrayList<MmtrCarSpec> head = new ObjectArrayList<>(cut + 1);
		final ObjectArrayList<MmtrCarSpec> tail = new ObjectArrayList<>(instance.fleetCars.size() - cut - 1);
		for (int i = 0; i < instance.fleetCars.size(); i++) {
			(i <= cut ? head : tail).add(instance.fleetCars.get(i));
		}
		final ObjectArrayList<org.mtr.core.data.VehicleCar> headCars = toVehicleCars(head);
		final ObjectArrayList<org.mtr.core.data.VehicleCar> tailCars = toVehicleCars(tail);
		if (Siding.getTotalVehicleLength(tailCars) > yard.getRailLength()) {
			fail(instance, "uncoupled tail does not fit the yard siding (step " + step.stepId + ")");
			return false;
		}
		final Vehicle rebuiltHead = yard.rebuildParkedConsist(headCars);
		if (rebuiltHead == null) {
			fail(instance, "uncoupled head does not fit the yard siding (step " + step.stepId + ")");
			return false;
		}
		// The tail is detached as the yard's next stock source: with the head parked the engine
		// spawns nothing; once the head leaves, a fresh parked vehicle is generated from the template.
		yard.setVehicleCars(tailCars);
		instance.fleetCars.clear();
		instance.fleetCars.addAll(head);
		instance.vehicleId = rebuiltHead.getId();
		instance.stepIndex++;
		System.out.println("[MMTR-JOB] uncoupled " + tail.size() + " car(s) off " + instance.job.jobId + " -> head vehicle " + rebuiltHead.getId());
		return true;
	}

	private static ObjectArrayList<org.mtr.core.data.VehicleCar> toVehicleCars(ObjectArrayList<MmtrCarSpec> specs) {
		final ObjectArrayList<org.mtr.core.data.VehicleCar> cars = new ObjectArrayList<>(specs.size());
		for (final MmtrCarSpec spec : specs) {
			cars.add(toVehicleCar(spec));
		}
		return cars;
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
		if (step.type == MmtrJobStep.StepType.COUPLE || step.type == MmtrJobStep.StepType.UNCOUPLE) {
			fail(instance, "COUPLE/UNCOUPLE must run while parked on the yard siding (step " + step.stepId + ")");
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
			fail(instance, "COUPLE/UNCOUPLE must run while parked on the yard siding, not mid-route (step " + step.stepId + ")");
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

	/** Place the job's rolling stock template on the siding so the engine spawns a parked consist. */
	private static boolean placeCars(Simulator simulator, MmtrConsistJob job) {
		final Siding siding = findSiding(simulator, job.sidingId);
		if (siding == null) {
			return false;
		}
		final ObjectArrayList<org.mtr.core.data.VehicleCar> cars = new ObjectArrayList<>();
		for (final MmtrCarSpec spec : job.cars) {
			cars.add(toVehicleCar(spec));
		}
		siding.setVehicleCars(cars);
		return true;
	}

	private static org.mtr.core.data.VehicleCar toVehicleCar(MmtrCarSpec spec) {
		return new org.mtr.core.data.VehicleCar(spec.vehicleId, spec.length, spec.width, spec.capacity, spec.bogie1Position, spec.bogie2Position, spec.couplingPadding1, spec.couplingPadding2);
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
		boolean carsPlaced;
		/** The formation this job currently operates (spec list): spawn stock, merged make-up or post-cut head. */
		final ObjectArrayList<MmtrCarSpec> fleetCars = new ObjectArrayList<>();
		/** This job's stock was merged into another job's consist (no longer stands alone). */
		boolean consumed;
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