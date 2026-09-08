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

	/** Current car count of the job's consist (spec list), falling back to the authored job cars. */
	/** Operator control: pause freezes the job; resume continues it (human-in-the-loop). */
	public boolean pause(String jobId) {
		final JobInstance instance = ensureInstance(jobId);
		if (instance == null) {
			return false;
		}
		instance.paused = true;
		return true;
	}

	public boolean resume(String jobId) {
		final JobInstance instance = ensureInstance(jobId);
		if (instance == null) {
			return false;
		}
		instance.paused = false;
		return true;
	}

	/** Human takeover: the AI stops auto-starting outbound legs until released back to autopilot. */
	public boolean humanTakeover(String jobId) {
		final JobInstance instance = ensureInstance(jobId);
		if (instance == null) {
			return false;
		}
		instance.humanHold = true;
		return true;
	}

	public boolean isPaused(String jobId) {
		final JobInstance instance = ensureInstance(jobId);
		return instance != null && instance.paused;
	}

	public boolean isHumanHeld(String jobId) {
		final JobInstance instance = ensureInstance(jobId);
		return instance != null && instance.humanHold;
	}

	public boolean releaseToAutopilot(String jobId) {
		final JobInstance instance = ensureInstance(jobId);
		if (instance == null) {
			return false;
		}
		instance.humanHold = false;
		return true;
	}

	@Nullable
	private JobInstance ensureInstance(String jobId) {
		boolean known = false;
		for (final MmtrConsistJob job : jobs) {
			if (job.jobId.equals(jobId)) {
				known = true;
				break;
			}
		}
		return known ? instances.computeIfAbsent(jobId, key -> new JobInstance(jobOf(jobId))) : null;
	}

	/** Mark a siding touched by this instance so a loop reset can fully clean it. */
	private static void markVisited(JobInstance instance, long sidingId) {
		if (!instance.visitedSidings.contains(sidingId)) {
			instance.visitedSidings.add(sidingId);
		}
	}

	/** Loop automation: after DONE/FAILED, wait one period, fully clean every touched siding, then restart. */
	private void loopAdvance(JobInstance instance, long currentMillis, Simulator simulator) {
		final long period = instance.job.loopEveryMs > 0 ? Math.max(1_000, instance.job.loopEveryMs) : 60_000;
		if (instance.nextCycleAtMs == 0) {
			instance.nextCycleAtMs = currentMillis + period;
			return;
		}
		if (currentMillis < instance.nextCycleAtMs) {
			return;
		}
		markVisited(instance, instance.job.sidingId);
		// A returned consist rests on its yard siding with its motion state still flagged "on
		// route" (it drove there, it did not respawn there), so clearParkedVehicles never removes
		// it and the next spawn would share the rail with it. Delete the old consist physically
		// wherever it stands (yard or, for a FAILED round, anywhere on the network) so the next
		// cycle starts from a clean siding with one fresh parked spawn.
		if (instance.vehicleId != 0) {
			simulator.deleteMmtrVehicle(instance.vehicleId);
		}
		for (final Long sidingId : instance.visitedSidings) {
			final Siding siding = findSiding(simulator, sidingId);
			if (siding != null) {
				siding.clearParkedVehicles();
				siding.setVehicleCars(new ObjectArrayList<>());
			}
		}
		instance.cyclesDone++;
		instance.state = JobState.PENDING;
		instance.stepIndex = 0;
		instance.vehicleId = 0;
		instance.curSidingId = 0;
		instance.carsPlaced = false;
		instance.mergedPlaced = false;
		instance.consumed = false;
		instance.started = false;
		instance.awaitingStart = true;
		instance.autoDepartureKickLogged = false;
		instance.spawnCars.clear();
		instance.fleetCars.clear();
		instance.nextCycleAtMs = 0;
		instance.lastLoopResetAtMillis = currentMillis;
		// A looping make-up job must re-arm its COUPLE source on every cycle, otherwise the source
		// stays consumed and the next make-up fails with "already coupled into another job".
		final JobInstance source = coupleTargetInstance(instance);
		if (source != null && source != instance && source.consumed) {
			source.state = JobState.PENDING;
			source.stepIndex = 0;
			source.vehicleId = 0;
			source.curSidingId = 0;
			source.carsPlaced = false;
			source.mergedPlaced = false;
			source.consumed = false;
			source.started = false;
			source.awaitingStart = true;
			source.autoDepartureKickLogged = false;
			source.spawnCars.clear();
			source.fleetCars.clear();
			source.nextCycleAtMs = 0;
			System.out.println("[MMTR-JOB] loop re-armed couple source " + source.job.jobId + " for " + instance.job.jobId);
		}
		System.out.println("[MMTR-JOB] loop " + instance.job.jobId + " cycle " + instance.cyclesDone + " restarted");
	}

	/** If the job starts with a COUPLE step, its referenced source job instance, else null. */
	@Nullable
	private JobInstance coupleTargetInstance(JobInstance instance) {
		if (instance.job.steps.isEmpty() || instance.job.steps.get(0).type != MmtrJobStep.StepType.COUPLE) {
			return null;
		}
		final String targetJobId = instance.job.steps.get(0).targetJobId;
		if (targetJobId == null || targetJobId.isEmpty()) {
			return null;
		}
		for (final MmtrConsistJob job : jobs) {
			if (job.jobId.equals(targetJobId)) {
				return instances.computeIfAbsent(targetJobId, key -> new JobInstance(job));
			}
		}
		return null;
	}

	public int cyclesOf(String jobId) {
		final JobInstance instance = instances.get(jobId);
		return instance == null ? 0 : instance.cyclesDone;
	}

	public int carsOf(String jobId) {
		final JobInstance instance = instances.get(jobId);
		if (instance != null && !instance.fleetCars.isEmpty()) {
			return instance.fleetCars.size();
		}
		for (final MmtrConsistJob job : jobs) {
			if (job.jobId.equals(jobId)) {
				return job.cars.size();
			}
		}
		return -1;
	}

	public void tick(long currentMillis, Simulator simulator) {
			if (anchor == Long.MIN_VALUE) {
			anchor = currentMillis;
		}
		final long dayTime = (currentMillis - anchor) % Utilities.MILLIS_PER_DAY;
		for (final MmtrConsistJob job : jobs) {
			final JobInstance instance = instances.computeIfAbsent(job.jobId, key -> new JobInstance(job));
			if (instance.paused) {
				continue; // operator pause: freeze advancement (no spawning, no deadlines)
			}
			switch (instance.state) {
				case PENDING -> pending(instance, currentMillis, dayTime, simulator);
				case RUNNING -> running(instance, currentMillis, dayTime, simulator);
				default -> {
					if (instance.job.loop && !instance.paused && (instance.state == JobState.DONE || instance.state == JobState.FAILED)) {
						loopAdvance(instance, currentMillis, simulator);
					}
				}
			}
		}
	}

	private static final long SPAWN_GRACE_MILLIS = 30_000;

	private void pending(JobInstance instance, long currentMillis, long dayTime, Simulator simulator) {
		if (instance.consumed) {
			instance.state = JobState.DONE; // source stock was merged into another job's make-up
			return;
		}
		if (dayTime < instance.job.startTimeOfDayMs) {
			return;
		}
		// Coupling make-up start: the job begins by collecting another job's parked stock on the
		// same yard siding (its first step is COUPLE). We must NOT spawn our own stock separately -
		// the merged formation replaces the target's parked consist via the yard surgery primitive.
		final boolean startsWithCouple = instance.stepIndex == 0 && !instance.job.steps.isEmpty()
			&& instance.job.steps.get(0).type == MmtrJobStep.StepType.COUPLE;
		if (startsWithCouple && !instance.mergedPlaced) {
			switch (tryCoupleStart(instance, dayTime, simulator)) {
				case WAIT:
				case FAILED:
					return;
				case DONE:
					// COUPLE is the job's only step: still spawn the merged consist so the yard
					// shows the coupled stock (the job is done but the formation stays parked for
					// later yard ops / subsequent jobs).
					if (!instance.carsPlaced && !instance.spawnCars.isEmpty()) {
						if (placeCars(simulator, instance)) {
							instance.carsPlaced = true;
							System.out.println("[MMTR-JOB] coupled stock parked from " + instance.job.jobId + " cars=" + instance.spawnCars.size());
						} else {
							fail(instance, "could not place coupled stock on siding");
							return;
						}
					}
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
				// The effective template is spawnCars after a make-up, plain job.cars otherwise -
				// always install it so the engine can spawn the (merged) parked consist.
				if (instance.spawnCars.isEmpty() && instance.job.cars.isEmpty()) {
					fail(instance, "job defines no rolling stock");
					return;
				}
				if (!placeCars(simulator, instance)) {
					fail(instance, "could not place job rolling stock on siding");
					return;
				}
				instance.carsPlaced = true;
				final int placedCars = instance.spawnCars.isEmpty() ? instance.job.cars.size() : instance.spawnCars.size();
				System.out.println("[MMTR-JOB] placed " + placedCars + " car(s) from job " + instance.job.jobId);
			}
			final boolean graceExpired = instance.lastLoopResetAtMillis > 0
				? currentMillis - instance.lastLoopResetAtMillis > SPAWN_GRACE_MILLIS
				: dayTime > instance.job.startTimeOfDayMs + SPAWN_GRACE_MILLIS;
			if (graceExpired) {
				fail(instance, "stock never spawned on siding");
			}
			return;
		}
		instance.vehicleId = vehicle.getId();
		instance.autoDepartureKickLogged = false;
		markVisited(instance, curSiding(instance));
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
			instance.fleetCars.addAll(instance.spawnCars.isEmpty() ? instance.job.cars : instance.spawnCars);
		}
		// Yard ops run while the consist is still parked on its own siding and before any mission /
		// ATO departure: UNCOUPLE cuts trailing cars off (head keeps the job, tail stays in the yard
		// as the next stock source). A second COUPLE here is not a supported formation (another job's
		// stock cannot share this siding at the same time).
		while (instance.stepIndex < instance.job.steps.size()) {
			final MmtrJobStep yardStep = instance.job.steps.get((int) instance.stepIndex);
			if (yardStep.type == MmtrJobStep.StepType.UNCOUPLE) {
				if (deadlineExpired(instance, yardStep, currentMillis, dayTime)) {
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
		if (!startOutbound(instance, simulator)) {
			return;
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
		if (target.vehicleId != 0) {
			// The engine keeps at most one parked vehicle per siding: make-up must happen BEFORE the
			// source job spawns its stock, so the merged formation is engine-spawned once (single
			// spawn keeps the full service path). A COUPLE against already-standing stock is a
			// scheduling-order error.
			fail(instance, "coupling target job '" + targetJobId + "' stock is already standing on the yard - schedule the COUPLE before that job's spawn time");
			return CoupleOutcome.FAILED;
		}
		// Compose the source job's cars into this job's own spawn template (this job's cars first).
		instance.spawnCars.clear();
		instance.spawnCars.addAll(instance.job.cars);
		instance.spawnCars.addAll(target.job.cars);
		instance.mergedPlaced = true;
		target.consumed = true;
		target.state = JobState.DONE;
		instance.stepIndex = 1; // the COUPLE step completed with the make-up composition
		System.out.println("[MMTR-JOB] make-up: coupled " + targetJobId + " stock into " + instance.job.jobId + " spawn template cars=" + instance.spawnCars.size());
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

	@Nullable
	private static Vehicle findParkedOnSiding(Simulator simulator, long sidingId) {
		final Vehicle[] found = {null};
		simulator.sidings.forEach(siding -> {
			if (found[0] == null && siding.getId() == sidingId) {
				siding.iterateVehicles(vehicle -> {
					if (found[0] == null && !vehicle.getIsOnRoute()) {
						found[0] = vehicle;
					}
				});
			}
		});
		return found[0];
	}

	/** The first parked, mission-idle vehicle on this job's siding that no other job has claimed. */
	@Nullable
	private Vehicle findFreeParkedVehicle(Simulator simulator, JobInstance self) {
		final Vehicle[] found = {null};
		simulator.sidings.forEach(siding -> {
			if (found[0] != null || siding.getId() != curSiding(self)) {
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

	private static long curSiding(JobInstance instance) {
		return instance.curSidingId == 0 ? instance.job.sidingId : instance.curSidingId;
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
		final Siding yard = findSiding(simulator, curSiding(instance));
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
		// The rebuilt head may keep running the job: an outbound step after the cut makes the head
		// depart on a service while the detached tail respawns as parked yard stock - the realistic
		// "pull away and leave the tail behind" shunt sequence.
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

	/** Loop-aware deadline: within a loop cycle the due is relative to the cycle start. */
	private boolean deadlineExpired(JobInstance instance, MmtrJobStep step, long currentMillis, long dayTime) {
		if (instance.lastLoopResetAtMillis > 0) {
			long relativeDue = step.dueTimeOfDayMs - instance.job.startTimeOfDayMs;
			if (relativeDue <= 0) {
				relativeDue = step.dueTimeOfDayMs;
			}
			return currentMillis - instance.lastLoopResetAtMillis > relativeDue;
		}
		return dayTime > instance.deadlineOf(step);
	}

	private void running(JobInstance instance, long currentMillis, long dayTime, Simulator simulator) {
		// Yard / parked phases run while the consist stands on its own siding: yard ops (UNCOUPLE),
		// return-to-yard MOVE_TO completion ("退库") and (re)departures after a return. Platform
		// movement in between is advanced by the mode-specific logic at the bottom.
		while (instance.stepIndex < instance.job.steps.size()) {
			final MmtrJobStep step = instance.job.steps.get((int) instance.stepIndex);
			if (deadlineExpired(instance, step, currentMillis, dayTime)) {
				fail(instance, "step " + step.stepId + " missed deadline (due " + step.dueTimeOfDayMs + ")");
				return;
			}
			final Vehicle vehicle = findVehicle(simulator, instance.vehicleId);
			if (vehicle == null) {
				fail(instance, "consist vanished mid-job");
				return;
			}
			final boolean parkedOnYard = vehicleParkedOnYard(instance, vehicle, simulator);
			if (step.type == MmtrJobStep.StepType.UNCOUPLE) {
				if (!parkedOnYard) {
					fail(instance, "UNCOUPLE must run while parked on the yard siding (step " + step.stepId + ")");
					return;
				}
				if (!executeUncouple(instance, simulator)) {
					return;
				}
				instance.awaitingStart = true;
				continue;
			}
			if (step.type == MmtrJobStep.StepType.COUPLE) {
				fail(instance, "COUPLE runs only as the first step of a make-up job (step " + step.stepId + ")");
				return;
			}
			if (!parkedOnYard) {
				break; // en route: platform movement is advanced below
			}
			if (step.type == MmtrJobStep.StepType.MOVE_TO && step.targetId == curSiding(instance)) {
				// 退库: the consist has returned to its own yard siding - the return step completes.
				System.out.println("[MMTR-JOB] MOVE_TO done back at yard siding " + step.targetId);
				instance.stepIndex++;
				instance.awaitingStart = true;
				continue;
			}
			// Cross-side auto-move (relocation / arrival make-up to ANOTHER siding) is OFFLINE as of the
			// Motion-Core cleanup: it was a "re-birth at destination", not a real drive. Cross-track moves
			// will be re-implemented by Motion Core live driving (segment+offset + turnout authority).
			if (step.type == MmtrJobStep.StepType.MOVE_TO && step.targetId != curSiding(instance)
				&& findSiding(simulator, step.targetId) != null) {
				fail(instance, "cross-track auto-move is offline (step " + step.stepId + " targets another siding " + step.targetId + ")");
				return;
			}
			// Parked with a movement step next: first departure or re-departure after a return.
			if (step.type == MmtrJobStep.StepType.MOVE_TO || step.type == MmtrJobStep.StepType.SERVE) {
				if (instance.awaitingStart || !instance.started) {
					instance.awaitingStart = false;
					if (!startOutbound(instance, simulator)) {
						return;
					}
				}
			}
			// AUTO: the engine's startUp no-ops while the spawn-time door cooldown is active, so a
			// single call can leave a service permanently parked in the yard. Re-issue it every tick
			// until the consist is actually on route.
			if (!kickParkedAutoDeparture(instance, simulator)) {
				return;
			}
			break;
		}
		if (instance.stepIndex >= instance.job.steps.size()) {
			instance.state = JobState.DONE;
			return;
		}
		// Step progress on the current leg: mission end (MANUAL, incl. while parked back at the yard)
		// or platform arrivals / dwell departures (AUTO, only while en route).
		if (instance.mode == Mode.MANUAL) {
			advanceManual(instance, simulator);
		} else {
			final Vehicle vehicle = findVehicle(simulator, instance.vehicleId);
			if (vehicle != null && !vehicleParkedOnYard(instance, vehicle, simulator)) {
				advanceAuto(instance, simulator);
			}
		}
	}

	/** Whether the given vehicle currently stands parked on this job's own yard siding. */
	private static boolean vehicleParkedOnYard(JobInstance instance, Vehicle vehicle, Simulator simulator) {
		if (vehicle.getIsOnRoute()) {
			return false;
		}
		final Siding yard = findSiding(simulator, curSiding(instance));
		return yard != null && yard.getVehicleById(vehicle.getId()) != null;
	}

	/**
	 * Start the current (parked) consist on its outbound service/mission once. Sets the instance
	 * state to RUNNING; leaves it FAILED when the mode-specific start failed.
	 */
	private boolean startOutbound(JobInstance instance, Simulator simulator) {
		final Vehicle current = findVehicle(simulator, instance.vehicleId);
		if (instance.humanHold || current != null && current.isCurrentlyManual()) {
			// A human is at the controls (operator hold or an in-cab driver): the AI yields - it
			// never auto-starts, and step completion keeps following position/door events.
			return true;
		}
		instance.started = true;
		instance.awaitingStart = false;
		if (instance.mode == Mode.MANUAL) {
			runManualStep(instance, simulator);
		} else {
			if (!startAutoService(instance, simulator)) {
				return false;
			}
		}
		if (instance.state == JobState.FAILED) {
			return false;
		}
		instance.state = JobState.RUNNING;
		return true;
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
		if (step.type == MmtrJobStep.StepType.SERVE) {
			// Passenger dwell is part of the PASSENGER arrival mission: the SERVE step is a dwell
			// gate at the platform - advanceManual completes it once the consist rests there.
			return;
		}
		final long targetId = step.targetId;
		if (targetId == 0) {
			fail(instance, "step " + step.stepId + " needs an explicit platform/siding target for a Motion-Core drive");
			return;
		}
		// PASSENGER when the step serves a platform (doors + dwell at the stop), MANEUVER for a
		// plain relocation (yard return). Motion-mode missions self-arm every tick (route plan,
		// turnout grants, stop target), so no legacy autopilot seam is engaged for them.
		final MmtrMission.Kind kind = isPlatform(simulator, targetId) ? MmtrMission.Kind.PASSENGER : MmtrMission.Kind.MANEUVER;
		final MmtrMission mission = new MmtrMission(vehicle.getId(), kind, instance.job.sidingId, targetId, simulator.getCurrentMillis());
		if (!vehicle.setMmtrMission(mission)) {
			fail(instance, "could not attach mission for step " + step.stepId);
			return;
		}
		System.out.println("[MMTR-JOB] manual step " + step.stepId + " -> " + kind + " target " + targetId + " vehicle=" + vehicle.getId());
		if (!vehicle.isMmtrMotion()) {
			vehicle.engageMissionAutopilot();
		}
	}

	private static boolean isPlatform(Simulator simulator, long targetId) {
		final boolean[] found = {false};
		simulator.platforms.forEach(platform -> {
			if (platform.getId() == targetId) {
				found[0] = true;
			}
		});
		return found[0];
	}

	private void advanceManual(JobInstance instance, Simulator simulator) {
		final Vehicle vehicle = findVehicle(simulator, instance.vehicleId);
		if (vehicle == null) {
			fail(instance, "consist vanished mid-job");
			return;
		}
		final MmtrJobStep step = instance.stepIndex < instance.job.steps.size() ? instance.job.steps.get((int) instance.stepIndex) : null;
		if (step != null && step.type == MmtrJobStep.StepType.SERVE) {
			// Dwell gate: the consist completed its passenger arrival (doors cycled); the step
			// closes once it rests at the target platform.
			if (!vehicle.isMoving() && vehicle.vehicleExtraData.getThisPlatformId() == step.targetId) {
				System.out.println("[MMTR-JOB] SERVE done at platform " + step.targetId);
				instance.stepIndex++;
			}
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

	/**
	 * AUTO re-kick: while the current step is a main-line/platform move and the consist is still
	 * parked at the yard (not yet on route), keep calling {@link Vehicle#startUp} so the spawn-time
	 * door cooldown cannot strand the service. Yard cross-side relocations are excluded - they use
	 * the relocate/rebuild path instead of a depot departure.
	 */
	private boolean kickParkedAutoDeparture(JobInstance instance, Simulator simulator) {
		if (instance.mode != Mode.AUTO || instance.humanHold || !instance.started || instance.vehicleId == 0) {
			return true;
		}
		final MmtrJobStep step = instance.stepIndex < instance.job.steps.size() ? instance.job.steps.get((int) instance.stepIndex) : null;
		if (step == null || step.type != MmtrJobStep.StepType.MOVE_TO && step.type != MmtrJobStep.StepType.SERVE) {
			return true;
		}
		if (findSiding(simulator, step.targetId) != null) {
			return true; // yard cross-side moves use relocation, not a depot departure
		}
		final Vehicle vehicle = findVehicle(simulator, instance.vehicleId);
		if (vehicle == null || vehicle.getIsOnRoute() || vehicle.isMoving()) {
			return true;
		}
		if (!instance.autoDepartureKickLogged) {
			instance.autoDepartureKickLogged = true;
			System.out.println("[MMTR-JOB] auto re-kick " + instance.job.jobId + " vehicle=" + vehicle.getId() + " (parked, not yet on route)");
		}
		vehicle.startUp(0, instance.startAbs);
		return true;
	}

	private boolean startAutoService(JobInstance instance, Simulator simulator) {
		final Siding siding = findSiding(simulator, curSiding(instance));
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

	/** Place the job's (possibly make-up composed) rolling-stock template so the engine spawns it. */
	private static boolean placeCars(Simulator simulator, JobInstance instance) {
		final Siding siding = findSiding(simulator, instance.job.sidingId);
		if (siding == null) {
			return false;
		}
		final ObjectArrayList<MmtrCarSpec> spawn = instance.spawnCars.isEmpty() ? instance.job.cars : instance.spawnCars;
		final ObjectArrayList<org.mtr.core.data.VehicleCar> cars = new ObjectArrayList<>();
		for (final MmtrCarSpec spec : spawn) {
			cars.add(toVehicleCar(spec));
		}
		// MMTR: a leftover parked vehicle (e.g. spawned at boot from the siding's persisted template
		// before this job ran, or a previous session's orphan) must not occupy the only parking slot
		// and block the job formation. Drop unowned parked stock so the engine can spawn ours.
		siding.clearParkedVehicles();
		siding.setVehicleCars(cars);
		// Arm the siding's manual spawn (the same flag the rolling-stock manifest sets): without it
		// the engine's siding tick never generates the parked consist, so a loop-restarted job dies
		// with "stock never spawned on siding".
		siding.mmtrManualSpawn = true;
		siding.mmtrSessionSpawned = false;
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
		/** True once the COUPLE make-up has composed the source stock into this job's spawn template. */
		boolean mergedPlaced;
		/** Effective spawn car list after a make-up composition (empty = plain job.cars). */
		final ObjectArrayList<MmtrCarSpec> spawnCars = new ObjectArrayList<>();
		/** The formation this job currently operates (spec list): spawn stock, merged make-up or post-cut head. */
		final ObjectArrayList<MmtrCarSpec> fleetCars = new ObjectArrayList<>();
		/** Whether the consist has departed at least once (distinguishes an initial park from a return). */
		boolean started;
		/** True when a parked consist may need a (re)departure (after a return or a yard op). */
		boolean awaitingStart = true;
		/** Log gate for the AUTO parked re-kick (reset per spawn/loop cycle). */
		boolean autoDepartureKickLogged;
		boolean paused;
		boolean humanHold;
			long curSidingId;
		long nextCycleAtMs;
		int cyclesDone;
		long lastLoopResetAtMillis;
		final ObjectArrayList<Long> visitedSidings = new ObjectArrayList<>();
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