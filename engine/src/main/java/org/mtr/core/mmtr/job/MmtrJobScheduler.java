package org.mtr.core.mmtr.job;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.MmtrCoupleSurgery;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.mmtr.MmtrRunPlanner;
import org.mtr.core.mmtr.segment.MmtrMotionPosition;
import org.mtr.core.mmtr.signal.MmtrShuntAuthority;
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
 * COUPLE/UNCOUPLE are ACTION steps (C9): the approach is its own MOVE_TO step - a cross-track run
 * under a 调车授权 when the target siding is another one - and the action runs as soon as the consist
 * stands. With automatic couplers (C8) the couplers may latch on arrival, so an absorbed target
 * completes the step too.
 */
public final class MmtrJobScheduler {

	public enum JobState { PENDING, RUNNING, DONE, FAILED }

	private enum Mode { MANUAL, AUTO }

	/** How long a task-driven 调车授权 stays live; refreshed every time the step is (re)armed. */
	private static final long SHUNT_AUTHORITY_MILLIS = 15 * 60 * 1000L;

	private final ObjectArrayList<MmtrConsistJob> jobs = new ObjectArrayList<>();
	private final Object2ObjectOpenHashMap<String, JobInstance> instances = new Object2ObjectOpenHashMap<>();
	private long anchor = Long.MIN_VALUE;

	/**
	 * 时间锚点（{@code dayTime = (t - anchor) mod DAY}）。
	 *
	 * <p>给时刻表派发器（P4）共用：两套编排的小时数必须是**同一个意思**，否则运营台上
	 * "作业单 08:00"与"线路 08:00"会差出一段，谁也说不清哪个对。</p>
	 *
	 * @return 锚点；还没 tick 过时返回 {@link Long#MIN_VALUE}
	 */
	public long getAnchor() {
		return anchor;
	}

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

	/** 圈间静置日志的节流：等待期间每 {@link #LOOP_IDLE_LOG_INTERVAL_MILLIS} 报一次倒计时。 */
	private static final long LOOP_IDLE_LOG_INTERVAL_MILLIS = 30_000L;

	/** Loop automation: after DONE/FAILED, wait one period, fully clean every touched siding, then restart. */
	private void loopAdvance(JobInstance instance, long currentMillis, Simulator simulator) {
		final long period = instance.job.loopEveryMs > 0 ? Math.max(1_000, instance.job.loopEveryMs) : 60_000;
		if (instance.nextCycleAtMs == 0) {
			instance.nextCycleAtMs = currentMillis + period;
			/*
			 * **圈间静置必须自己说出来**（2026-09-17 现场：用户盯着折返点看了一分多钟，以为车卡死了）。
			 *
			 * <p>这一圈的最后一步做完之后，调度器要等 {@code loopEveryMs} 才发下一圈；等待期间车**没有进路**
			 * （{@code route} 是空的），因此**也不会申请道岔**。于是现场画面是：车停在折返点、道岔一动不动、
			 * 日志里一个字都没有 —— 从外面看与"卡死"完全一样（用户原话："一直都没人讲"）。</p>
			 *
			 * <p>所以进入等待时打一条**说清"这是静置、不是卡住"**的日志，并在等待期间每
			 * {@link #LOOP_IDLE_LOG_INTERVAL_MILLIS} 报一次倒计时；到点发车的 {@code cycle … restarted}
			 * 那条本来就有。要改这个时长就改作业单的 {@code loopEveryMs}（同一行里写出来，免得又要问人）。</p>
			 */
			instance.nextCycleLoggedAtMs = currentMillis;
			System.out.println("[MMTR-JOB] " + instance.job.jobId + " 一圈跑完，进入圈间静置：等 " + (period / 1000)
				+ " 秒发下一圈（loopEveryMs=" + instance.job.loopEveryMs + "）。"
				+ "期间车没有进路、不会申请道岔 —— 道岔不动是正常的，不是卡住。");
			return;
		}
		if (currentMillis < instance.nextCycleAtMs) {
			// 静置期间每 30 秒报一次倒计时：让"等下一圈"在日志里始终看得见。
			if (currentMillis - instance.nextCycleLoggedAtMs >= LOOP_IDLE_LOG_INTERVAL_MILLIS) {
				instance.nextCycleLoggedAtMs = currentMillis;
				System.out.println("[MMTR-JOB] " + instance.job.jobId + " 圈间静置中：还有 "
					+ Math.max(0, (instance.nextCycleAtMs - currentMillis + 999) / 1000) + " 秒发下一圈");
			}
			return;
		}
		markVisited(instance, instance.job.sidingId);
		/*
		 * 一圈跑完时车停在哪儿，就**从哪儿接着跑**（2026-09-16 现场两轮修正）。
		 *
		 * <p>修前收尾一律"删掉旧车 + 清股道 + 下一圈在库里重生"，于是两种走法都会在画面上变成瞬移：
		 * 最后一步是"回库"时（车已经自己开回本务股道停好了）被删掉重生；最后一步是"在北端 (-176,-222)
		 * 换端"时（车停在北端，本来下一圈该自己往外开）也被删掉重生回库。</p>
		 *
		 * <p>现在只有**本圈失败**（半路停住、卡在别处）才按老办法删车重造；正常跑完就保留这列车、
		 * 只把步骤指针拨回开头 —— 下一圈它从当前位置自己发车。闭环作业（不靠库房重生）因此才成立。</p>
		 */
		final boolean roundFailed = instance.state == JobState.FAILED;
		final boolean keepConsist = instance.vehicleId != 0 && !roundFailed;
		if (!keepConsist && instance.vehicleId != 0) {
			// Delete the old consist physically wherever it stands so the next cycle starts from a
			// clean siding with one fresh parked spawn.
			simulator.deleteMmtrVehicle(instance.vehicleId);
		}
		if (!keepConsist) {
			for (final Long sidingId : instance.visitedSidings) {
				final Siding siding = findSiding(simulator, sidingId);
				if (siding != null) {
					siding.clearParkedVehicles();
					siding.setVehicleCars(new ObjectArrayList<>());
				}
			}
		}
		instance.cyclesDone++;
		instance.state = JobState.PENDING;
		instance.stepIndex = 0;
		instance.vehicleId = keepConsist ? instance.vehicleId : 0;
		instance.curSidingId = keepConsist ? instance.curSidingId : 0;
		instance.carsPlaced = keepConsist;   // 车还在场上：不要再往股道上放一次
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
		// C6/U6: the make-up joint is a real coupler, so the last car of the initiator's group declares
		// it - otherwise a later UNCOUPLE (or a game-side cut) could not separate the two groups again.
		instance.spawnCars.clear();
		instance.spawnCars.addAll(instance.job.cars);
		if (!instance.job.cars.isEmpty() && !target.job.cars.isEmpty()) {
			instance.job.cars.get(instance.job.cars.size() - 1).mmtrCouplerAfter = true;
			instance.spawnCars.get(instance.job.cars.size() - 1).mmtrCouplerAfter = true;
		}
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
	 * C6: a yard UNCOUPLE may only cut where the stock actually has a coupler (design U6). The value in
	 * the job step is the CAR index to cut after — the same semantics as the {@code uncouple <id> <car>}
	 * command and as aiming at a car in the game — so a fixed unit (an EMU rake) can never be cut
	 * internally, and a make-up joint can.
	 *
	 * @return {@code null} when the cut is legal, otherwise the reason to report
	 */
	static @org.jspecify.annotations.Nullable String uncoupleRefusal(ObjectArrayList<MmtrCarSpec> cars, int cutAfterCarIndex) {
		if (cutAfterCarIndex < 0 || cutAfterCarIndex >= cars.size() - 1) {
			return "uncouple cut index " + cutAfterCarIndex + " must leave at least one car on each side (" + cars.size() + " cars)";
		}
		if (!cars.get(cutAfterCarIndex).mmtrCouplerAfter) {
			return "uncouple cut index " + cutAfterCarIndex + " has no coupler after that car（第 " + cutAfterCarIndex + " 节之后没有车钩，切分只认接缝）";
		}
		return null;
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
		final String refusal = uncoupleRefusal(instance.fleetCars, cut);
		if (refusal != null) {
			fail(instance, refusal);
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
			if (step.type == MmtrJobStep.StepType.UNCOUPLE && parkedOnYard) {
				// Yard cut: the classic "pull away and leave the tail behind on this siding" sequence.
				if (!executeUncouple(instance, simulator)) {
					return;
				}
				instance.awaitingStart = true;
				continue;
			}
			if (step.type == MmtrJobStep.StepType.COUPLE || step.type == MmtrJobStep.StepType.UNCOUPLE) {
				// C9: an ACTION step, not a movement. The first COUPLE of a make-up job was already
				// consumed by tryCoupleStart before this loop; a COUPLE after a cross-track MOVE_TO and
				// a cut wherever the consist now stands are performed by advanceManual once it stands.
				break;
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
			// C9: a MOVE_TO to ANOTHER siding is a real cross-track run now: the consist leaves its yard
			// under a 调车授权 and drives itself there with Motion Core live driving (MmtrRunPlanner +
			// mission self-arming). The old "cross-track auto-move is offline" gate predates that
			// machinery and is gone; a target that cannot be reached fails through the mission instead.
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
	 * 车是不是正停在**指定**股道上（用于"一圈跑完时车在不在库里"）：只看股道的车表 ——
	 * 自己开回库的车 {@code getIsOnRoute()} 仍然是 true（它是开过去的，不是重生在那儿的），
	 * 所以不能用"在途"标志判。
	 */
	private static boolean vehicleParkedOnSiding(Simulator simulator, long vehicleId, long sidingId) {
		final Siding siding = findSiding(simulator, sidingId);
		return siding != null && siding.getVehicleById(vehicleId) != null;
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
		if (step.type == MmtrJobStep.StepType.CHANGE_ENDS) {
			// 换端 has no target and no mission: advanceManual performs it once the consist stands.
			return;
		}
		if (step.type == MmtrJobStep.StepType.COUPLE || step.type == MmtrJobStep.StepType.UNCOUPLE) {
			// C9: an action step with no mission of its own - advanceManual performs the surgery as soon
			// as the consist stands (the approach was the preceding MOVE_TO).
			return;
		}
		/*
		 * SERVE（站台作业）必须**自己带一个任务实例**下到车上去，不能当成"车已经停在那儿就算了"。
		 *
		 * <p>修前的口径是"停留是上一步到站任务的一部分，SERVE 只是个停稳闸门"，于是它在
		 * {@code advanceManual} 里被"停稳就算完"直接推过去 —— 而 {@code DriveToPlatformTask} 的停留是 0，
		 * 结果作业单的每一站都是**一闪而过、门开一 tick 就关**（实测：循环作业到 1 站后卡在 SERVE 不动，
		 * 因为那条闸门判的是 {@code thisPlatformId}，而这个字段在 Motion 时代根本没人写）。</p>
		 *
		 * <p>现在照设计走：SERVE → {@link org.mtr.core.mmtr.task.StationServiceTask}（原地动作），
		 * 车的任务执行器负责"开门 → 停够 → 关门"（{@code Vehicle.mmtrRunInPlaceTaskAction}），
		 * 任务在停留结束后完成（{@code Vehicle.mmtrMissionTick} 的 AT_TARGET 分支）。
		 * 车不在那个站台上时，自臂会照常规划一趟开过去（自臂里的站台在场判据），所以这里不需要特判。</p>
		 */
		final long targetId = step.targetId;
		/*
		 * **轨目标**（折返/换端点）：目的地是一根正规轨道而不是站台/股道对象。用户现场口径 ——
		 * "折返就是开到某根正规轨上换端，不可能去定义某条线为换端专用"。这种步骤：
		 *   · kind = MANEUVER（不是客运停站，不开门）；
		 *   · 不带任务实例（任务层的词汇只有站台/股道，轨目标没有对应的 task，硬套会被 validate 拒掉）；
		 *   · 停车点 = 这根轨的远端（fraction 默认 1.0，按行车方向）= 开到头，正好留给下一步换端。
		 */
		final Rail railTarget = org.mtr.core.mmtr.MmtrRunPlanner.findRailByHex(simulator, step.targetRailHex);
		final boolean railTargetStep = step.targetRailHex != null && !step.targetRailHex.trim().isEmpty();
		if (railTargetStep && railTarget == null) {
			fail(instance, "step " + step.stepId + " 的轨目标 " + step.targetRailHex + " 在图里找不到");
			return;
		}
		if (!railTargetStep && targetId == 0) {
			fail(instance, "step " + step.stepId + " needs an explicit platform/siding target for a Motion-Core drive");
			return;
		}
		// PASSENGER when the step serves a platform (doors + dwell at the stop), MANEUVER for a
		// plain relocation (yard return) or a rail target (折返). Motion-mode missions self-arm every
		// tick (route plan, turnout grants, stop target), so no legacy autopilot seam is engaged.
		final boolean targetIsPlatform = !railTargetStep && isPlatform(simulator, targetId);
		final MmtrMission.Kind kind = targetIsPlatform ? MmtrMission.Kind.PASSENGER : MmtrMission.Kind.MANEUVER;
		final boolean shuntNeeded = !railTargetStep && !targetIsPlatform && grantShuntForStep(instance, vehicle, simulator, targetId);
		// Task mapping (作业单步骤 → 任务实例): the mission carries the task definition so the
		// timetable layer and the future interlocking read where/when/what of the running step.
		final org.mtr.core.mmtr.task.MmtrTask task = railTargetStep ? null : org.mtr.core.mmtr.task.MmtrTaskFactory.fromStep(step, targetIsPlatform);
		if (task != null) {
			final String invalid = task.validate();
			if (!invalid.isEmpty()) {
				fail(instance, "step " + step.stepId + " task invalid: " + invalid);
				return;
			}
			// 站台自己配了停留时间（现场 10s）就用它，别一律用引擎默认 5s —— 作业单里的"停站"
			// 就是站台属性说了算，这样站台停留改了不用改作业单。
			if (task instanceof final org.mtr.core.mmtr.task.StationServiceTask service && service.dwellMs <= 0) {
				final Platform platform = simulator.platformIdMap.get(targetId);
				if (platform != null && platform.getDwellTime() > 0) {
					service.dwellMs = platform.getDwellTime();
				}
			}
		}
		final MmtrMission mission = new MmtrMission(vehicle.getId(), kind, instance.job.sidingId, targetId, simulator.getCurrentMillis());
		mission.setNeedsShuntAuthority(shuntNeeded);
		if (railTargetStep) {
			mission.setTargetRail(railTarget.getHexId(), step.targetRailFraction);
		}
		if (task != null) {
			mission.attachTask(task);
		}
		if (!vehicle.setMmtrMission(mission)) {
			fail(instance, "could not attach mission for step " + step.stepId);
			return;
		}
		System.out.println("[MMTR-JOB] manual step " + step.stepId + " -> " + kind + " target "
			+ (railTargetStep ? "轨 " + railTarget.getHexId().substring(0, 8) + "… @" + Math.round(step.targetRailFraction * 100.0) / 100.0 : String.valueOf(targetId))
			+ " vehicle=" + vehicle.getId());
		if (!vehicle.isMmtrMotion()) {
			vehicle.engageMissionAutopilot();
		}
		/*
		 * **同一 tick 内就把新计划自臂出去**（2026-09-17 现场问："为什么道岔请求慢半拍、不是换向后马上完成"）。
		 *
		 * <p>不用等下一 tick 车辆自己走一遍：tick 内的顺序是"车辆走行 → … → 作业调度器"，
		 * 而换端之后的这一步正是在**这一 tick 的调度器里**挂上去的 —— 不在这里顺手自臂，
		 * 规划进路与申请道岔就要等下一 tick（现场可见"换向做完了、道岔慢半拍"）。
		 * 自臂之后再由 {@code Simulator} 在本 tick 末同步一次道岔位置，世界上那道岔当 tick 就动。</p>
		 */
		vehicle.mmtrArmActiveMissionNow(simulator);
	}

	/**
	 * C9: a task-driven move into another siding is a 调车 movement. When that siding already holds
	 * stock - or a COUPLE step follows - grant the consist a temporary SUBSIDIARY_SHUNT authority: S1
	 * then lets it enter the occupied section and draw up to the coupler gap, and the coupling gate
	 * ({@link MmtrCoupleSurgery}) requires a live authority for the movement. An empty target gets no
	 * authority, so ordinary relocations keep their normal protection.
	 */
	private boolean grantShuntForStep(JobInstance instance, Vehicle vehicle, Simulator simulator, long targetSidingId) {
		final MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
		final Rail targetRail = MmtrRunPlanner.findSavedRailRail(simulator, targetSidingId);
		if (walker == null || targetRail == null || targetRail.getHexId().equals(walker.railHex())) {
			return false;
		}
		if (findParkedOnSiding(simulator, targetSidingId) == null && !nextStepIsCouple(instance)) {
			return false;
		}
		simulator.mmtrShuntAuthorities.grant(vehicle.getId(), walker.railHex(), targetRail.getHexId(),
			MmtrShuntAuthority.Kind.SUBSIDIARY_SHUNT, MmtrShuntAuthority.Kind.SUBSIDIARY_SHUNT.getDefaultSpeedLimitKmh(), SHUNT_AUTHORITY_MILLIS);
		System.out.println("[MMTR-JOB] 调车授权 " + instance.job.jobId + " vehicle=" + vehicle.getId() + " -> rail " + targetRail.getHexId() + "（任务驱动的调车进路）");
		return true;
	}

	private boolean nextStepIsCouple(JobInstance instance) {
		final int next = (int) instance.stepIndex + 1;
		return next < instance.job.steps.size() && instance.job.steps.get(next).type == MmtrJobStep.StepType.COUPLE;
	}

	/**
	 * C9: perform the COUPLE action wherever the consist now stands. The target is the stock of the job
	 * named by the step (the normal authoring), else whatever else stands on our rail. An already
	 * absorbed target means the automatic couplers latched on arrival - the step is done either way.
	 *
	 * @return whether the step completed (false = still rolling, or the job was failed)
	 */
	private boolean executeCoupleAtStand(JobInstance instance, Simulator simulator) {
		final MmtrJobStep step = instance.job.steps.get((int) instance.stepIndex);
		final Vehicle vehicle = findVehicle(simulator, instance.vehicleId);
		if (vehicle == null) {
			fail(instance, "COUPLE: consist vanished (step " + step.stepId + ")");
			return false;
		}
		if (vehicle.getSpeed() > 1e-9) {
			return false; // still rolling up - the approach has not finished
		}
		final String targetJobId = step.targetJobId == null ? "" : step.targetJobId.trim();
		final JobInstance targetInstance = targetJobId.isEmpty() ? null : instances.get(targetJobId);
		if (targetInstance != null && targetInstance.vehicleId != 0 && findVehicle(simulator, targetInstance.vehicleId) == null) {
			// C8: the automatic coupler latched the two trains together the moment the approach stopped,
			// so the target's vehicle id is gone (absorbed into ours). Nothing left to do.
			instance.stepIndex++;
			System.out.println("[MMTR-JOB] COUPLE done by the automatic coupler (target job " + targetJobId + " absorbed)");
			return true;
		}
		final Vehicle target = coupleTargetVehicle(instance, vehicle, simulator, targetInstance);
		if (target == null) {
			fail(instance, "COUPLE: no other consist stands in coupler reach (step " + step.stepId + ")");
			return false;
		}
		final MmtrCoupleSurgery.Result result = MmtrCoupleSurgery.couple(simulator, vehicle.getId(), target.getId());
		if (!result.ok()) {
			if (simulator.mmtrFindVehicle(target.getId()) == null) {
				// The surgery's own auto-coupler pass may have completed the merge this tick.
				instance.stepIndex++;
				System.out.println("[MMTR-JOB] COUPLE done by the automatic coupler (target " + target.getId() + " absorbed)");
				return true;
			}
			fail(instance, "COUPLE refused: " + result.reason());
			return false;
		}
		final Vehicle merged = result.vehicle();
		instance.vehicleId = merged.getId();
		refreshFleetCars(instance, merged);
		if (targetInstance != null) {
			targetInstance.vehicleId = merged.getId();
			targetInstance.consumed = true;
			targetInstance.state = JobState.DONE;
		}
		instance.stepIndex++;
		System.out.println("[MMTR-JOB] COUPLE done: " + merged.getId() + " now " + merged.vehicleExtraData.immutableVehicleCars.size() + " 节");
		return true;
	}

	/** C9: whether the consist has drawn up to the train standing on its target siding (coupler reach). */
	private boolean arrivedAtTargetConsist(Vehicle vehicle, Simulator simulator) {
		if (vehicle.getSpeed() > 1e-9) {
			return false;
		}
		final MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
		// A freshly merged consist body has no rail yet for a tick (the surgery re-places the spine), so
		// the walker's rail hex can be null right after a coupling - never dereference it blind.
		final String railHex = walker == null ? null : walker.railHex();
		if (railHex == null) {
			return false;
		}
		final Vehicle[] target = {null};
		simulator.sidings.forEach(siding -> siding.iterateVehicles(other -> {
			if (target[0] == null && other.getId() != vehicle.getId() && other.getSpeed() <= 1e-9 && other.getMmtrMotionWalker() != null
				&& railHex.equals(other.getMmtrMotionWalker().railHex())) {
				target[0] = other;
			}
		}));
		if (target[0] == null) {
			return false;
		}
		final double gap = MmtrCoupleSurgery.couplerGapM(vehicle, target[0]);
		return Double.isFinite(gap) && gap <= MmtrCoupleSurgery.COUPLER_CONTACT_M;
	}

	/**
	 * C8/C9: with automatic couplers the target stock latches on the moment the approach stops, so
	 * nothing is left standing on the target siding. That also completes the movement - the train is
	 * where it was going, and the cars are now part of its own formation.
	 */
	private boolean targetConsistAbsorbed(Vehicle vehicle, Simulator simulator, long targetSidingId) {
		if (vehicle.getSpeed() > 1e-9) {
			return false;
		}
		final MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
		final Rail targetRail = MmtrRunPlanner.findSavedRailRail(simulator, targetSidingId);
		final String railHex = walker == null ? null : walker.railHex();
		if (railHex == null || targetRail == null || !targetRail.getHexId().equals(railHex)) {
			return false;
		}
		final boolean[] other = {false};
		simulator.sidings.forEach(siding -> siding.iterateVehicles(otherVehicle -> {
			if (otherVehicle.getId() != vehicle.getId() && otherVehicle.getMmtrMotionWalker() != null
				&& railHex.equals(otherVehicle.getMmtrMotionWalker().railHex())) {
				other[0] = true;
			}
		}));
		return !other[0];
	}

	/** The consist a COUPLE step couples onto: the named job's stock, else whatever else stands here. */
	@Nullable
	private Vehicle coupleTargetVehicle(JobInstance instance, Vehicle vehicle, Simulator simulator, @Nullable JobInstance targetInstance) {
		if (targetInstance != null && targetInstance.vehicleId != 0) {
			final Vehicle named = findVehicle(simulator, targetInstance.vehicleId);
			if (named != null && named.getId() != vehicle.getId()) {
				return named;
			}
		}
		final MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
		final String railHex = walker == null ? null : walker.railHex();
		if (railHex == null) {
			return null;
		}
		final Vehicle[] found = {null};
		simulator.sidings.forEach(siding -> siding.iterateVehicles(other -> {
			if (found[0] == null && other.getId() != vehicle.getId() && other.getMmtrMotionWalker() != null
				&& railHex.equals(other.getMmtrMotionWalker().railHex())) {
				found[0] = other;
			}
		}));
		return found[0];
	}

	/**
	 * C9: cut the formation after the step's car index wherever it stands (the yard path rebuilds the
	 * head through the siding template; after a cross-track move the consist is not on its yard siding,
	 * so this runs the real surgery and keeps the head half on the job).
	 */
	private boolean executeUncoupleAtStand(JobInstance instance, Simulator simulator) {
		final MmtrJobStep step = instance.job.steps.get((int) instance.stepIndex);
		final Vehicle vehicle = findVehicle(simulator, instance.vehicleId);
		if (vehicle == null) {
			fail(instance, "UNCOUPLE: consist vanished (step " + step.stepId + ")");
			return false;
		}
		if (vehicle.getSpeed() > 1e-9) {
			return false;
		}
		final MmtrCoupleSurgery.Result result = MmtrCoupleSurgery.uncouple(simulator, vehicle.getId(), step.targetIndex);
		if (!result.ok()) {
			fail(instance, "UNCOUPLE refused: " + result.reason());
			return false;
		}
		instance.vehicleId = result.vehicle().getId();
		refreshFleetCars(instance, result.vehicle());
		instance.stepIndex++;
		System.out.println("[MMTR-JOB] UNCOUPLE done: head " + result.vehicle().getId() + " 留作业单，尾段 " + (result.other() == null ? "?" : result.other().getId()));
		return true;
	}

	/** Refresh the job's authored fleet from the formation that actually exists after a surgery. */
	private static void refreshFleetCars(JobInstance instance, Vehicle vehicle) {
		instance.fleetCars.clear();
		for (final VehicleCar car : vehicle.vehicleExtraData.immutableVehicleCars) {
			instance.fleetCars.add(MmtrCarSpec.fromVehicleCar(car));
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
		if (step != null && step.type == MmtrJobStep.StepType.CHANGE_ENDS) {
			if (completeChangeEndsStep(instance, vehicle, step)) {
				if (instance.stepIndex >= instance.job.steps.size()) {
					instance.state = JobState.DONE;
				} else {
					runManualStep(instance, simulator);
				}
			}
			return;
		}
		/*
		 * SERVE 的推进**由它自己的任务完成**（{@link org.mtr.core.mmtr.task.StationServiceTask}：
		 * 开门 → 停够 → 关门 → 任务完成），所以这里不能再"停稳就算完" —— 那条捷径判的是
		 * {@code thisPlatformId}（Motion 时代没人写这个字段，永远为 0），既推不动也对不上"开关门"的语义。
		 * 下面的任务终态分支统一处理它。
		 */
		if (step != null && (step.type == MmtrJobStep.StepType.COUPLE || step.type == MmtrJobStep.StepType.UNCOUPLE)) {
			// C9 action step: the approach (if any) already finished, so run the surgery as soon as the
			// consist stands. executeCoupleAtStand/executeUncoupleAtStand report "not yet" by returning
			// false, so this is re-tried every tick until the formation is at a stand.
			final boolean done = step.type == MmtrJobStep.StepType.COUPLE
				? executeCoupleAtStand(instance, simulator)
				: executeUncoupleAtStand(instance, simulator);
			if (done) {
				if (instance.stepIndex >= instance.job.steps.size()) {
					instance.state = JobState.DONE;
				} else {
					runManualStep(instance, simulator);
				}
			}
			return;
		}
		final MmtrMission mission = vehicle.getMmtrMission();
		/*
		 * C9 的"到位"捷径**只对股道目标成立**：它要表达的是"目标股道的远端在物理上被停着的车列堵住，
		 * 车钩贴上了就算这趟调车走完了"。站台目标没有这回事 —— 站台的到点判据就是本车自己的停车点（锚点），
		 * 用这条捷径会让 MOVE_TO **在车还在半路上**就被判完成（2026-09-16 实测：1↔3 站循环的股道折返步
		 * 在车还压在渡线 (-176,-541)→(-170,-511) 上时就被判到达，于是换端发生在渡线上而不是支线尽头，
		 * 后面的每一步都被带偏）。
		 */
		final boolean targetIsSiding = step != null && findSiding(simulator, step.targetId) != null;
		if (mission != null && targetIsSiding && step.type == MmtrJobStep.StepType.MOVE_TO
			&& (arrivedAtTargetConsist(vehicle, simulator) || targetConsistAbsorbed(vehicle, simulator, step.targetId))) {
			// C9: the planned stop of a cross-track run is the FAR end of the target siding, but the run
			// really ends where the standing rake is - the occupancy face under the 调车授权 stops the
			// consist at the coupler gap. Closed up to the coupler = the movement is complete, so end the
			// mission here instead of waiting for a stop target the rake physically blocks.
			vehicle.setMmtrMotionAuto(false);
			vehicle.setMmtrMotionStopTarget(-1, false);
			if (mission.getState() == MmtrMission.State.DISPATCHED) {
				mission.atTarget();
			}
			if (mission.getState() == MmtrMission.State.AT_TARGET) {
				mission.complete();
			}
			System.out.println("[MMTR-JOB] MOVE_TO reached the consist on siding " + step.targetId + " (closed up to the coupler)");
		}
		if (mission == null || !mission.isTerminal()) {
			return;
		}
		if (mission.getState() != MmtrMission.State.COMPLETE) {
			fail(instance, "mission for step ended " + mission.getState());
			return;
		}
		if (step != null && step.type == MmtrJobStep.StepType.MOVE_TO && findSiding(simulator, step.targetId) != null) {
			// C9: the consist has relocated to that siding (a real cross-track run), so later steps -
			// including a return to the original yard siding - work from where it now stands.
			instance.curSidingId = step.targetId;
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
		if (step.type == MmtrJobStep.StepType.CHANGE_ENDS) {
			// ATO completes 换端 itself once the consist stands (§3.5.1 decision 3).
			if (completeChangeEndsStep(instance, vehicle, step) && instance.stepIndex >= instance.job.steps.size()) {
				instance.state = JobState.DONE;
			}
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

	/**
	 * 换端 step: no drive, no mission, no target. The consist must be at rest (a driver cannot change
	 * cabs on a moving train) and the engine then flips the manned cab — the train itself does not
	 * move. Both executors share this gate: a manual job performs it when the driver has brought the
	 * consist to a stand, an ATO job completes it itself (§3.5.1 decision 3).
	 *
	 * @return whether the step completed on this tick (and stepIndex was advanced)
	 */
	private boolean completeChangeEndsStep(JobInstance instance, Vehicle vehicle, MmtrJobStep step) {
		if (vehicle.getMmtrConsistWalker() == null) {
			fail(instance, "step " + step.stepId + " 换端 requires a consist-body vehicle");
			return false;
		}
		if (vehicle.getSpeed() > 0) {
			return false; // waiting for the stand - not a terminal state
		}
		if (!vehicle.changeEndsMmtrMotion()) {
			fail(instance, "step " + step.stepId + " 换端 refused");
			return false;
		}
		System.out.println("[MMTR-JOB] 换端 at step " + step.stepId + " vehicle=" + vehicle.getId() + " -> cab " + vehicle.getMmtrConsistWalker().cabs().activeCab());
		instance.stepIndex++;
		return true;
	}

	/** Place the job's (possibly make-up composed) rolling-stock template so the engine spawns it. */
	private static boolean placeCars(Simulator simulator, JobInstance instance) {
		final Siding siding = findSiding(simulator, instance.job.sidingId);
		if (siding == null) {
			// An unknown siding id is an authoring error (a typo in the jobs file): list what exists, so
			// the operator can fix the job instead of guessing.
			final StringBuilder known = new StringBuilder();
			simulator.sidings.forEach(s -> known.append(s.getId()).append(' '));
			System.out.println("[MMTR-JOB] job " + instance.job.jobId + " targets unknown siding " + instance.job.sidingId + "; known sidings: " + known);
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
		return spec.toVehicleCar();
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
		/** 圈间静置日志的上一次打印时刻（倒计时节流用，见 {@code loopAdvance}）。 */
		long nextCycleLoggedAtMs;
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