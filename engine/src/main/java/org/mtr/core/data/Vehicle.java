package org.mtr.core.data;

import it.unimi.dsi.fastutil.booleans.BooleanBooleanImmutablePair;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleDoubleImmutablePair;
import it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongObjectImmutablePair;
import it.unimi.dsi.fastutil.objects.*;
import lombok.extern.log4j.Log4j2;
import org.jspecify.annotations.Nullable;
import org.mtr.core.generated.data.VehicleSchema;
import org.mtr.core.mmtr.ConsistDynamics;
import org.mtr.core.mmtr.ConsistType;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.DriveController;
import org.mtr.core.mmtr.DriveOutput;
import org.mtr.core.mmtr.MmtrComposition;
import org.mtr.core.mmtr.MmtrDriveAccess;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.mmtr.MmtrProtection;
import org.mtr.core.mmtr.MmtrRegime;
import org.mtr.core.mmtr.MmtrRunPlanner;
import org.mtr.core.mmtr.MmtrSupport;
import org.mtr.core.mmtr.consist.MmtrCabState;
import org.mtr.core.mmtr.consist.MmtrConsistBody;
import org.mtr.core.mmtr.consist.MmtrConsistWalker;
import org.mtr.core.mmtr.segment.MmtrMotionPosition;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.mmtr.signal.MmtrBlockService;
import org.mtr.core.mmtr.signal.MmtrMovementAuthority;
import org.mtr.core.mmtr.signal.MmtrShuntAuthority;
import org.mtr.core.path.SidingPathFinder;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.mtr.core.tool.Vector;

import java.util.UUID;

/**
 * A single train / boat / cable car / airplane consist running along a {@link Siding}.
 *
 * <p>Lives both server-side (where it owns its motion physics, signal blocks and door state)
 * and client-side (where it is replayed from sync snapshots) — see {@link #isClientside}. The
 * physics step is split into {@link #simulateMoving}, {@link #simulateStopped} and
 * {@link #simulateInDepot} depending on the vehicle's state at the start of the tick.</p>
 */
@Log4j2
public class Vehicle extends VehicleSchema implements Utilities {

	/**
	 * The amount of time to check for a blocked status again after detecting a blocked status.
	 */
	private long stoppingCooldown;
	private long deviation;
	private double deviationSpeedAdjustment;
	/**
	 * The time until the vehicle switches from manual to automatic
	 */
	private long manualCooldown;
	private long doorCooldown;
	private boolean atoOverride;
	/**
	 * Last simulator timestamp at which this vehicle moved; used by jam detection.
	 */
	private long lastMovementMillis;

	public final VehicleExtraData vehicleExtraData;

	/**
	 * MMTR: lazily resolved consist type + controller for vehicles driven with the MMTR control
	 * model (server policy set on the {@link Simulator}). Null keeps the legacy power-handle path.
	 */
	private @Nullable ConsistType mmtrConsistType;
	private @Nullable DriveController mmtrDriveController;
	/**
	 * MMTR: explicit control override. Only engaged by the future input layer that sends a real
	 * ControlState (keyboard/HID). Until then manual driving follows the legacy single handle so
	 * the game stays fully playable (equivalent to having no consist-type policy).
	 */
	private boolean mmtrManualOverride;
	private @Nullable ControlState mmtrActiveControl;
	/**
	 * MMTR: uuid of the driver currently holding the explicit override (occupation lock).
	 * Set/cleared together with {@link #mmtrManualOverride}; kept {@code null} on the legacy
	 * no-identity path so that path keeps its old behaviour.
	 */
	private @Nullable UUID mmtrDriverUuid;
	/**
	 * MMTR: per-car composition used when the consist runs the AIR_BRAKE model (one pipe/cylinder
	 * state per car, equalised along the train). Server persists it across ticks; mirrored clients
	 * rebuild it from the latest snapshot (seeded via {@code mmtrAirState}).
	 */
	private @Nullable MmtrComposition mmtrComposition;
	private String mmtrLastAirSeed = "";
	/**
	 * MMTR (server): remaining hold time (ms) after an overrun/SPAD protection emergency stop,
	 * before control is released again (SCR-style lock). Mirrored clients just follow the
	 * synced {@code mmtrProtection} flag and do not count down locally.
	 */
	private long mmtrProtectionLockRemaining;
	/**
	 * MMTR: the task/mission currently assigned to this train (consist). Owned by the train —
	 * players/AI only execute or read it. Null when the consist is idle/unscheduled.
	 */
	private @Nullable MmtrMission mmtrMission;
	/**
	 * MMTR (server): when the current mission entered AT_TARGET; used to complete after a dwell.
	 */
	private long mmtrMissionTargetArrivedMillis;
	/**
	 * C10: whether this mission already changed ends once because the route could not be planned in the
	 * direction the train happened to face. Guards against flipping back and forth on an impossible
	 * target - a second failure is reported to the mission owner.
	 */
	private boolean mmtrMissionFlippedForTarget;
	/**
	 * MMTR (L3, server): live Motion-Core run mode. When non-null this vehicle's RUNNING motion is
	 * decided per tick by the walker — (segment, offset), fork branches elected live from the current
	 * BranchStore/task at each node — instead of a pre-baked whole-journey path. {@link #railProgress}
	 * is the walker's cumulative distance and {@link #mmtrMotionLegs} is the growing ordered leg
	 * shadow (cumulative PathData) that the legacy render/occupancy helpers walk. Clientside mirrors
	 * never engage this mode (they keep replaying the synced legacy path).
	 */
	private @Nullable MmtrMotionPosition mmtrMotionWalker;
	/** Motion-mode leg shadow: cumulative PathData list, refreshed when the walker boards a new rail. */
	private final ObjectArrayList<PathData> mmtrMotionLegs = new ObjectArrayList<>();
	/** Walker leg count at the last shadow refresh (detects newly boarded rails). */
	private int mmtrMotionLegCount;
	/**
	 * MMTR (L3): cumulative stop target for the current motion run (m in walker distance space);
	 * -1 = no stop target (free run). When set, the vehicle auto service-brakes and comes to rest
	 * exactly at the target, opens the doors if requested, and holds until a NEW control is applied.
	 */
	private double mmtrMotionStopTargetM = -1;
	private boolean mmtrMotionStoppedAtTarget;
	private boolean mmtrMotionStopOpenDoors;
	/**
	 * Signal S1: nearest stop point forced by external occupancy ahead of this motion vehicle,
	 * in walker distance space (m); {@code Double.MAX_VALUE} = no occupancy constraint. Re-computed
	 * every server tick from the shared vehiclePositions occupancy trees BEFORE the run integrates.
	 */
	private double mmtrBlockStopM = Double.MAX_VALUE;
	/**
	 * ①: the current block stop comes from the "block ahead ends at an unset turnout" rule, not from
	 * occupancy. While it is set, the approach-locking window is extended to the next fork ahead (see
	 * {@link #replenishForkRequests}): a train held at the signal before a long block would otherwise
	 * never come within the 120 m request window of the turnout it is waiting for.
	 */
	private boolean mmtrSectionAuthorityHold = false;
	/** T3: 车被**行车许可**扣住（红灯 / 自己的进路没设好）—— 与"区间出口是未设道岔"是两种不同的等待。 */
	private boolean mmtrSignalAuthorityHold = false;
	private String mmtrSignalAuthorityReason = "";

	/** Short reason for the block-stop log line (the operator reads these in the server log). */
	private String mmtrBlockStopReason() {
		if (mmtrSignalAuthorityHold) {
			return mmtrSignalAuthorityReason;
		}
		return mmtrSectionAuthorityHold ? "block ahead ends at an unset turnout" : "block ahead occupied";
	}
	/**
	 * Signal S1: this vehicle is parked at its occupancy stop point (blocked by an occupied rail
	 * ahead). Traction is suppressed while it stands; the flag clears automatically once the block
	 * stop disappears (the rail ahead emptied), resuming auto runs / manual control.
	 */
	private boolean mmtrBlockedWaiting;
	/** Signal S3 (AWS): warning state machine state (MMTR_AWS_*). Only live manual driving on AWS-band rails. */
	private int mmtrAwsState = MMTR_AWS_NONE;
	/** Signal S3 (AWS): tick time accumulated while the warning is unacknowledged (window check). */
	private long mmtrAwsWarnElapsedMillis;
	/** Signal S3 (AWS): an acknowledgement intent arrived via {@link #applyMmtrControl} (point-press semantics). */
	private boolean mmtrAwsAckQueued;
	/** applyMmtrControl() sequence; a changed sequence while stopped at a target = the driver's continue. */
	private int mmtrControlApplySeq;
	private int mmtrMotionArrivalControlSeq = -1;
	/** Last client push for a waiting motion vehicle (1 s cadence keeps stopped mirrors corrected). */
	private long mmtrMotionLastClientPushMillis;
	/** P3: the plan whose en-route forks this vehicle requested through the turnout authority
	 * (refreshed every tick while the mission run is armed); null when no auto plan is active. */
	private MmtrRunPlanner.Plan mmtrMotionPlan;
	/**
	 * S5: the live 进路 of this train (rails + turnouts + SET/PENDING state), created when a motion
	 * mission arms and released when it goes terminal. The signal layer (A2) reads it through
	 * {@code Simulator.mmtrRoutes} to decide whether a proceed aspect may be shown for a rail.
	 */
	private org.mtr.core.mmtr.route.MmtrRoute mmtrRoute;
	/** 尽头换向 (terminal flip): whether the armed plan's dead-end flip already happened. Plans
	 * without a flip point are born "done"; armMmtrPointRun resets it when the new plan flips. */
	private boolean mmtrMotionFlipDone = true;
	/**
	 * REV: a reverser direction change was requested while the consist was rolling and is waiting for
	 * the stand that lets it be applied (traction is cut in the meantime).
	 */
	private boolean mmtrReverserPending;
	/** P3: authority owner key of this vehicle's requests (mission runs), reset on release. */
	private String mmtrPointOwner = "";
	/** P3: en-route forks still ahead of this vehicle (not yet crossed); refreshed while armed so a
	 * crossed fork is never re-requested (its hold was already released at the crossing). */
	private final ObjectArrayList<String[]> mmtrPendingPointOps = new ObjectArrayList<>();
	/** Turnout authority request/refresh window. */
	private static final long MMTR_POINT_REQUEST_MILLIS = 10L * MILLIS_PER_MINUTE;
	/** Approach-locking window (m): forks are only requested once the head is within this distance -
	 * a following train never pre-occupies the points the leading train still needs (英铁 approach
	 * locking); sized to cover the braking approach of the slowest local band. */
	private static final double MMTR_APPROACH_LOCK_METERS = 120.0;/**
	 * MMTR (L3): unmanned auto run (task/ATO foundation). While armed and a stop target is active,
	 * the vehicle drives itself (cruise at the auto notch, service-brake envelope to the exact stop
	 * target, doors per the stop request) without any driver override; arming the NEXT stop target
	 * while stopped departs automatically (step-run). An active manual override always wins; when it
	 * is released the auto run resumes.
	 */
	private boolean mmtrMotionAuto;
	@Nullable
	private final Siding siding;
	/**
	 * If a vehicle is clientside, don't open the doors or start up automatically. Always wait for a socket update instead.
	 */
	private final boolean isClientside;

	public static final int MAX_POWER_LEVEL = 7;
	public static final int POWER_LEVEL_RATIO = 5;
	public static final int DOOR_MOVE_TIME = 3200;
	private static final int DOOR_DELAY = 1000;
	/**
	 * Vehicles that do not move for this long while on-route are treated as jammed.
	 */
	private static final long JAM_THRESHOLD = 5 * MILLIS_PER_MINUTE;
	/**
	 * MMTR: hold time (ms) after an overrun/SPAD protection emergency stop before the driver can
	 * take control again (SCR/TPWS-style lock).
	 */
	private static final long MMTR_PROTECTION_LOCK_MS = 10_000;
	/**
	 * MMTR: fixed integration sub-step for the longitudinal model. Server ticks and client frames
	 * split their elapsed time into these fine steps so stiff dynamics (air brake, coupler slack
	 * later) stay stable and the client mirror integrates identically.
	 */
	private static final long MMTR_INTEGRATION_SUB_STEP_MS = 10;
	/**
	 * MMTR: how long a mission stays AT_TARGET (dwell for passengers) before completing.
	 */
	private static final long MMTR_MISSION_DWELL_MILLIS = 5000;
	/**
	 * Signal S1: stop margin in front of an external occupancy face (tail of a same-direction train
	 * or head of an oncoming one), m, in walker distance space.
	 */
	private static final double MMTR_BLOCK_TAIL_GAP_M = 2.0;
	/**
	 * C3b: how close an authorised coupling movement may draw up to the train it is coupling to.
	 * The block rule's 2 m tail gap is a following distance for running moves; a coupling movement
	 * has to close to coupler length, but never touch - this is the stop the driver's "prepared to
	 * stop at sight" resolves to.
	 */
	private static final double MMTR_COUPLER_GAP_M = 0.3;
	/**
	 * Signal S1: when the next rail (block) is externally occupied the vehicle stops AT the current
	 * rail's end node without crossing it; advance is capped epsilon short of the node so the walker
	 * never boards the occupied rail while the block service still holds it.
	 */
	private static final double MMTR_BLOCK_NODE_EPS_M = 0.001;
	/**
	 * ② 岔区清限 (junction clearance): how far past a junction node a consist must be before the node
	 * counts as clear. A section boundary sits ON the node, so without this margin a consist whose tail
	 * has just left the node would no longer hold the junction, and a movement approaching from another
	 * leg could be admitted straight into its side (侧面防护). 10 m is a tunable model value - real
	 * clearance points sit where the diverging tracks are far enough apart for the longest vehicle.
	 */
	public static final double MMTR_JUNCTION_CLEARANCE_M = 10.0;
	/**
	 * Signal S3/A3 (AWS): the warning triggers while the train runs inside this lead distance of the
	 * signal it is about to pass (a non-green aspect) or of a restricted boundary (an occupancy stop
	 * / a slower rail to be braced for). Real AWS magnets sit ~200 yd (183 m) before the signal; the
	 * engine keeps the lead small so short test rails still exercise the state machine (constant,
	 * tunable).
	 */
	private static final double MMTR_AWS_TRIGGER_LEAD_M = 75.0;
	/**
	 * Signal S3/A3 (AWS): driver acknowledgement window before an unacknowledged warning becomes a
	 * SPAD emergency stop. A3 aligned this with the real semantics (2.5 s; UK AWS warning cancels in
	 * ~2.5-3 s), counted in vehicle tick time so tests stay clock-free.
	 */
	private static final long MMTR_AWS_ACK_WINDOW_MILLIS = 2500;
	/** Signal S3 (AWS) warning state machine: no warning active. */
	private static final int MMTR_AWS_NONE = 0;
	/** Signal S3 (AWS) warning state machine: warning sounding, awaiting driver acknowledgement. */
	private static final int MMTR_AWS_WARN = 1;
	/** Signal S3 (AWS) warning state machine: acknowledged - the yellow/black indicator stays up until the restriction clears. */
	private static final int MMTR_AWS_ACKED = 2;
	/** Minimum gap between two "waiting for turnout authority" reports, ms. */
	private static final long MMTR_TURNOUT_WAIT_LOG_INTERVAL_MILLIS = 5000;
	/**
	 * 闭塞区间 v2 (S3): the arc step used to read the movement's heading on its current rail when asking
	 * the directional section model which block it is in. Small enough to stay inside a rail, big enough
	 * that a sampled two-arc curve gives a usable direction.
	 */
	private static final double MMTR_SECTION_ARC_EPS_M = 0.05;
	private static long mmtrLastTurnoutWaitLogMillis;

	public Vehicle(VehicleExtraData vehicleExtraData, @Nullable Siding siding, TransportMode transportMode, Data data) {
		super(transportMode, data);
		this.siding = siding;
		this.vehicleExtraData = vehicleExtraData;
		this.isClientside = !(data instanceof Simulator);
	}

	public Vehicle(VehicleExtraData vehicleExtraData, @Nullable Siding siding, ReaderBase readerBase, Data data) {
		super(readerBase, data);
		this.siding = siding;
		this.vehicleExtraData = vehicleExtraData;
		this.isClientside = !(data instanceof Simulator);
		updateData(readerBase);
	}

	/**
	 * Internal-only: the single-argument constructor used by
	 * {@link org.mtr.core.operation.VehicleUpdate} when reconstructing a vehicle from a network
	 * update. Wraps the read in a fresh {@link ClientData} so the resulting vehicle has somewhere
	 * to look up cached references. <strong>Do not call from user code.</strong>
	 */
	@Deprecated
	@SuppressWarnings("DeprecatedIsStillUsed")
	public Vehicle(ReaderBase readerBase) {
		this(new VehicleExtraData(readerBase), null, readerBase, new ClientData());
	}

	@Override
	public boolean isValid() {
		return true;
	}

	public boolean isMoving() {
		return speed != 0;
	}

	/** Current speed in engine internal units (m/ms). */
	public double getSpeed() {
		return speed;
	}

	/** Current distance along the path (m) measured at the vehicle's head. */
	public double getRailProgress() {
		return railProgress;
	}

	/** Debug/test read access: remaining time until the vehicle falls back out of manual mode. */
	public long getManualCooldownMillis() {
		return manualCooldown;
	}

	/** Debug/test read access: door animation/cooldown time left. */
	public long getDoorCooldownMillis() {
		return doorCooldown;
	}

	/** Whether the vehicle is currently driven manually (server-side semantics). */
	public boolean isCurrentlyManual() {
		if (isClientside) {
			log.warn("Vehicle#isCurrentlyManual should only be called on the server side!");
		}
		// MMTR: while an operator / AI controller holds the explicit override the train stays manual
		// - MTR's "manual-to-automatic" hand-back must not hijack an in-progress human (or future AI)
		// drive. Release returns it to whatever the (empty) stock state implies.
		return mmtrManualOverride || (!atoOverride && manualCooldown > 0);
	}

	/**
	 * Server-side autopilot / headless seam: engages the manual control path without a riding
	 * player, so a mission's AUTOPILOT executor can drive the consist directly (manual sidings).
	 * No-op on clientside or when the vehicle does not allow manual driving.
	 */
	public void engageManualAutopilot(long manualToAutomaticMillis) {
		if (isClientside || !vehicleExtraData.getIsManualAllowed()) {
			return;
		}
		atoOverride = false;
		manualCooldown = Math.max(0, manualToAutomaticMillis);
	}

	/**
	 * MMTR: assign a task/mission to this train. Only one mission is active at a time;
	 * assigning over an existing active mission fails (callers should cancel first).
	 */
	public boolean setMmtrMission(@Nullable MmtrMission mission) {
		if (mission == null) {
			mmtrMission = null;
			return true;
		}
		if (mmtrMission != null && !mmtrMission.isTerminal()) {
			return false;
		}
		mmtrMission = mission;
		mmtrMissionFlippedForTarget = false;
		return true;
	}

	public @Nullable MmtrMission getMmtrMission() {
		return mmtrMission;
	}

	/**
	 * MMTR (server): drive this train headlessly for an AUTOPILOT mission on a manual-allowed
	 * consist, mirroring exactly what a real driver does (doors closed, manual engaged, full
	 * throttle). Refreshing manual cooldown each tick keeps the autopilot engaged.
	 */
	public void engageMissionAutopilot() {
		if (isClientside || !vehicleExtraData.getIsManualAllowed()) {
			return;
		}
		vehicleExtraData.closeDoors();
		engageManualAutopilot(vehicleExtraData.getManualToAutomaticTime());
		vehicleExtraData.setPowerLevel(MAX_POWER_LEVEL);
	}

	/** T4: 玩家执行的任务，进路是否已经发布过（它不设 auto / 停车目标，所以那两个当不了标志）。 */
	private boolean mmtrPlayerRoutePublished = false;

	/**
	 * T4: 这列车的任务是否**由本车自己**推进"进路与道岔"这一层（规划 + 发布进路 + 申请道岔）。
	 *
	 * <p>修前这条路的门是 {@code executor == AUTOPILOT && mmtrMotionAuto}：于是**玩家执行的任务
	 * 根本不发布进路、不申请道岔**（{@code Vehicle} 里两处申请点都进不去）。后果是玩家开车执行任务，
	 * 到第一处道岔就撞上"位置停在默认 0、没有任何东西给它授权"的物理闸门 —— 任务做不下去。</p>
	 *
	 * <p>现在玩家执行的任务走同一条路；区别只在于**不接管油门**（见 {@link #mmtrMotionSelfArmMission}）：
	 * 司机自己开，联锁照样为他设进路、扳道岔、给信号。这就是"玩家任务接入联锁"的全部含义。</p>
	 */
	private boolean mmtrMissionDrivesItsOwnRoute(MmtrMission mission) {
		return mission.getExecutor() == MmtrMission.Executor.PLAYER
			|| mission.getExecutor() == MmtrMission.Executor.AUTOPILOT && mmtrMotionAuto;
	}

	/**
	 * T4 ③：进路的**类型**由**任务类型**决定，而不是从"此刻有没有调车授权"反推。
	 *
	 * <p>反推的问题不是它答错了，而是它**不稳定**：授权一到/一走，同一条 movement 的类型就翻，
	 * 于是 {@code MmtrRouteRegistry.request} 认不出"这还是同一条进路"（{@code sameMovement} 比 kind），
	 * 每翻一次就换一个新对象 —— 信号层与运营台手里的那个对象被churn 掉。</p>
	 *
	 * <p>**与设计文档字面的一处偏离**（写清楚免得被当成漏做）：设计说"不再反推"，本实现保留了
	 * "有调车授权 ⇒ 调车进路"这一条。理由是 {@code Kind.SHUNT} 的语义就是"这列车是由**副显示**
	 * 授权的，主灯不许清"（{@code MmtrRoute} 的注释），而副显示授权恰恰**定义**了调车进路 ——
	 * 它不是旁证，是判据本身。所以：作业类型是调车 ⇒ 调车；作业是客运但拿了副显示授权 ⇒ 也按调车
	 * （安全侧：宁可不清主灯）。两者都成立时答案一致，既有用例不受影响。</p>
	 */
	static org.mtr.core.mmtr.route.MmtrRoute.Kind mmtrRouteKindOf(@Nullable MmtrMission mission, boolean hasShuntAuthority) {
		if (mission != null && mission.getKind() == MmtrMission.Kind.MANEUVER) {
			return org.mtr.core.mmtr.route.MmtrRoute.Kind.SHUNT;
		}
		return hasShuntAuthority ? org.mtr.core.mmtr.route.MmtrRoute.Kind.SHUNT : org.mtr.core.mmtr.route.MmtrRoute.Kind.MAIN;
	}

	/**
	 * T4: 引擎该不该为这个任务**规划并发布进路**（自动车与玩家车都要）。
	 *
	 * <p>注意与 {@link #mmtrMissionDrivesItsOwnRoute} 的分工：**自臂发生在 auto 打开之前**，
	 * 所以这里不能要求 {@code mmtrMotionAuto}（一开始写成同一个判据，八条既有用例立刻红 ——
	 * 自动车根本没机会自臂）。那一个判据管的是"进路已发布、正在推进，需要每 tick 续期"。</p>
	 */
	private static boolean mmtrMissionNeedsRouteSetup(MmtrMission mission) {
		return mission.getExecutor() == MmtrMission.Executor.AUTOPILOT || mission.getExecutor() == MmtrMission.Executor.PLAYER;
	}

	/**
	 * MMTR (server): advance the active mission state machine from observed train state.
	 * Missions are attached to the train; the executor (AUTOPILOT / PLAYER / AI) only reads or
	 * drives, so the lifecycle advances whether a driver is present or not.
	 */
	public void mmtrMissionTick() {
		if (isClientside || mmtrMission == null) {
			return;
		}
		final MmtrMission mission = mmtrMission;
		final boolean motionMission = mmtrMotionWalker != null;
		// Motion-mode missions self-execute: whoever attached the mission (mission control op, job
		// scheduler, periodic source) does not need to arm anything - the vehicle resolves the target
		// platform/siding rail, plans the run and arms the auto step-run itself on the next tick.
		// Missions armed eagerly by the ops layer (auto already on) are left alone.
		if (motionMission && mmtrMissionNeedsRouteSetup(mission) && mission.getState() != MmtrMission.State.AT_TARGET && !mission.isTerminal()
			&& !mmtrMotionAuto && mmtrMotionStopTargetM < 0 && data instanceof final Simulator simulator
			&& !(mission.getExecutor() == MmtrMission.Executor.PLAYER && mmtrPlayerRoutePublished)) {
			mmtrMotionSelfArmMission(simulator, mission);
		}
		switch (mission.getState()) {
			case ASSIGNED:
				// The consist started moving (left the depot / began its run) => dispatched.
				if (isMoving()) {
					mission.dispatch();
				}
				break;
			case DISPATCHED:
				// Motion-mode missions arrive when the armed stop target is reached exactly;
				// legacy-path missions use their path/platform stop semantics.
				if (motionMission ? isMmtrMotionStoppedAtTarget() : isStoppedAtMissionTarget()) {
					mission.atTarget();
					mmtrMissionTargetArrivedMillis = data.getCurrentMillis();
					if (!motionMission && mission.getExecutor() == MmtrMission.Executor.AUTOPILOT && vehicleExtraData.getIsManualAllowed()) {
						// An AUTOPILOT mission drives headlessly: release the throttle on arrival so the
						// consist settles at the target (platform dwell or depot terminal) instead of
						// re-departing on the repeating depot path.
						vehicleExtraData.setPowerLevel(0);
					}
				}
				break;
			case AT_TARGET:
				// Complete after a dwell at the target while stationary (passengers board/alight).
				if (!isMoving() && data.getCurrentMillis() - mmtrMissionTargetArrivedMillis >= MMTR_MISSION_DWELL_MILLIS) {
					mission.complete();
				}
				break;
			default:
				break;
		}
		// A terminal motion mission (complete / failed / canceled) hands the vehicle back to idle:
		// auto run off, stop target cleared, doors closed - the consist rests where it is. Its P3
		// turnout authority requests (un-crossed forks) are released so queued trains can proceed.
		if (motionMission && mission.isTerminal()) {
			releaseMmtrPointRequests();
			mmtrPlayerRoutePublished = false;   // T4: 任务结束，玩家任务的自臂闩一并清掉
			mmtrMotionAuto = false;
			mmtrMotionStopTargetM = -1;
			mmtrMotionStoppedAtTarget = false;
			mmtrMotionStopOpenDoors = false;
			mmtrMotionArrivalControlSeq = -1;
			mmtrRunStopTarget = -1;
			mmtrMotionFlipDone = true;
			vehicleExtraData.closeDoors();
			vehicleExtraData.mmtrMarkSyncDirty();
		}

		// P3: while an auto mission run is armed, replenish the forks that just entered the
		// approach window (英铁 approach locking: a point is only requested once the train is near
		// it, so a following train never pre-occupies the forks the leading train still needs),
		// then refresh the grant/queue windows of the pending forks every tick so a slow run or a
		// long lock wait never lets the requests expire mid-route; crossed forks were released at
		// the crossing and must not be re-requested.
		if (motionMission && mmtrMissionDrivesItsOwnRoute(mission) && !mission.isTerminal()
			&& data instanceof final Simulator simulator && !mmtrPointOwner.isEmpty()) {
			replenishForkRequests(simulator);
			if (!mmtrPendingPointOps.isEmpty()) {
				requestPendingForksAtomically(simulator.mmtrPointAuthority, mmtrPointOwner, data.getCurrentMillis() + MMTR_POINT_REQUEST_MILLIS);
			}
		}

		// S5: keep the published route's state in step with the turnout authority EVERY tick, not only
		// while the run is armed - a fork whose grant expires, or that an operator parks while the train
		// is still waiting to arm, must drop the route to PENDING so the signal protecting it returns
		// to danger instead of showing proceed for a movement the interlocking no longer has set.
		// By vehicle id (refresh is a no-op when the train has no route), so a surgery-rebuilt object
		// still maintains the route its predecessor published.
		if (data instanceof final Simulator routeSimulator) {
			routeSimulator.mmtrRoutes.refresh(getId(), routeSimulator.mmtrPointAuthority);
		}
	}

	/**
	 * MMTR (L3, slice 9): self-arm an AUTOPILOT mission on this motion vehicle - resolve the target
	 * platform/siding rail by id, plan the run (MmtrRunPlanner), preset the en-route turnouts into the
	 * authoritative store and arm the auto step-run (doors for passenger service). Infeasible targets
	 * fail the mission with the reason, so task owners observe the failure through the mission.
	 */
	private void mmtrMotionSelfArmMission(Simulator simulator, MmtrMission mission) {
		final long targetSidingId = mission.getTargetSidingId();
		if (targetSidingId == 0) {
			mission.fail("motion missions need an explicit target platform/siding id");
			return;
		}
		final Rail targetRail = MmtrRunPlanner.findSavedRailRail(simulator, targetSidingId);
		if (targetRail == null) {
			mission.fail("target siding " + targetSidingId + " has no graph rail");
			return;
		}
		if (targetRail.getHexId().equals(mmtrMotionWalker.railHex())) {
			// Already standing on the target rail: the movement is complete. This is the normal end of a
			// consist job's cross-track run - the approach stopped at the coupler gap and the surgery (or
			// the automatic couplers) absorbed the rake that stood there, so the target rail is now ours.
			if (mission.getState() == MmtrMission.State.ASSIGNED) {
				mission.dispatch();
			}
			if (mission.getState() == MmtrMission.State.DISPATCHED) {
				mission.atTarget();
			}
			if (mission.getState() == MmtrMission.State.AT_TARGET) {
				mission.complete();
			}
			return;
		}
		final MmtrRunPlanner.Plan plan = MmtrRunPlanner.planToRail(simulator, this, targetRail.getHexId(), 1.0);
		final MmtrConsistWalker selfArmConsistWalker = getMmtrConsistWalker();
		if (!plan.feasible && speed <= 1e-9 && !mmtrMissionFlippedForTarget && selfArmConsistWalker != null) {
			// C10 反向行驶: the plan could not be made in the direction the train happens to face - the
			// classic case is a stub siding whose only way out is behind the train. Flip the consist's
			// direction of travel (the REV mechanism: same manned cab, tail-first) ONCE per mission and
			// let the self-arm below run again next tick with the new orientation. The flag stops an
			// A/B/A loop: a second failure is reported.
			mmtrMissionFlippedForTarget = true;
			applyMmtrTravelReversed(!selfArmConsistWalker.travelReversed());
			mmtrMotionLegCount = mmtrMotionWalker.legCount();
			refreshMmtrMotionLegs();
			System.out.println("[MMTR-MSG] motion mission could not be planned facing this way (" + plan.reason + ") - reversed the travel direction on " + mmtrMotionWalker.railHex() + " and retrying");
			return;
		}
		if (!plan.feasible) {
			mission.fail(plan.reason);
			return;
		}
		if (mission.needsShuntAuthority() && mmtrMotionWalker.railHex() != null) {
			// C10: authorise the WHOLE planned route, not just its target rail - a task-driven shunt may
			// cross rails other stock occupies on the way, and S1 must not stop it mid-route.
			final org.mtr.core.mmtr.signal.MmtrShuntAuthority existing = simulator.mmtrShuntAuthorities.active(getId());
			final boolean sameRoute = existing != null && existing.getRouteRailHexes().size() == plan.routeRailHexes.size()
				&& existing.getTargetRailHex().equals(plan.targetRailHex);
			simulator.mmtrShuntAuthorities.grantRoute(getId(), mmtrMotionWalker.railHex(), plan.targetRailHex, plan.routeRailHexes,
				org.mtr.core.mmtr.signal.MmtrShuntAuthority.Kind.SUBSIDIARY_SHUNT, 0, 15 * 60 * 1000L);
			if (!sameRoute) {
				System.out.println("[MMTR-MSG] 调车进路授权覆盖 " + plan.routeRailHexes.size() + " 条轨（" + plan.targetRailHex + " 为目标）");
			}
		}
		if (!armMmtrPointRun(simulator, plan)) {
			// Feasible but a fork is operator-locked or held by another train: the mission stays
			// ASSIGNED and this self-arm retries every tick until the grants land (operator unlock
			// / the other train's release); nothing auto-elects around a busy point. Report the wait
			// at most every few seconds - several waiting trains would otherwise log every tick.
			final long now = System.currentTimeMillis();
			if (now - mmtrLastTurnoutWaitLogMillis >= MMTR_TURNOUT_WAIT_LOG_INTERVAL_MILLIS) {
				mmtrLastTurnoutWaitLogMillis = now;
				// Name the blocking point (operator park / other holder / queue): a wait that never ends
				// is only diagnosable from the log if the log says WHICH point and WHO holds it.
				System.out.println("[MMTR-MSG] motion mission " + mission.getKind() + " waiting for turnout authority on rail " + plan.targetRailHex
					+ " - " + org.mtr.core.mmtr.MmtrRunPlanner.describeForkWait(mmtrPendingPointOps, simulator.mmtrPointAuthority, mmtrPointOwner));
			}
			return;
		}
		if (mission.getExecutor() == MmtrMission.Executor.AUTOPILOT) {
			setMmtrMotionAuto(true);
			setMmtrMotionStopTarget(plan.stopCumulativeM, mission.getKind() == MmtrMission.Kind.PASSENGER);
			System.out.println("[MMTR-MSG] motion mission " + mission.getKind() + " self-armed to rail " + plan.targetRailHex + " stop @" + Math.round(plan.stopCumulativeM) + "m");
		} else {
			// T4: 玩家执行 —— **只发布进路与授权，不接管油门**。司机自己开，联锁替他设进路/扳道岔/给信号；
			// 引擎只观测（位置、门、停车点），到点由任务状态机照常推进。
			System.out.println("[MMTR-MSG] motion mission " + mission.getKind() + " (PLAYER) 进路已发布到 rail " + plan.targetRailHex
				+ "，道岔已申请；油门留给司机");
			// 玩家任务不设 auto / 停车目标，所以那两个不能当"已自臂"的标志 —— 单独记一个闩，
			// 否则每 tick 都会重规划一遍（幂等，但日志会刷屏）。
			mmtrPlayerRoutePublished = true;
		}
	}

	/**
	 * P3: request every en-route turnout of a feasible plan through the simulator's point authority
	 * under this vehicle's owner key and wire the walker to it. Idempotent: re-requesting refreshes
	 * grant/queue windows while the run is armed (approach locking stays alive). Returns whether all
	 * forks are currently granted to this vehicle.
	 */
	public boolean armMmtrPointRun(Simulator simulator, MmtrRunPlanner.Plan plan) {
		mmtrMotionPlan = plan;
		mmtrMotionFlipDone = plan.flipRailHex.isEmpty();
		mmtrPendingPointOps.clear();
		// Approach locking (英铁): only forks inside the approach window are requested now; forks
		// further ahead join the pending set via replenishForkRequests as the run approaches them,
		// so a following train never occupies the points the leading train still needs.
		final double distanceNow = mmtrMotionWalker == null ? 0 : mmtrMotionWalker.distanceM();
		for (int j = 0; j < plan.forkOps.size(); j++) {
			final String[] op = plan.forkOps.get(j);
			final double forkAbsM = j < plan.forkMeters.size() ? plan.forkMeters.get(j) : Double.NaN;
			final double remainingM = forkAbsM - distanceNow;
			if (remainingM <= 0 || remainingM > MMTR_APPROACH_LOCK_METERS) {
				continue; // already crossed or not yet in the approach window
			}
			mmtrPendingPointOps.add(op.clone());
		}
		// The walker elects operator branches (道岔人工位) BEFORE authority grants, and the engine
		// presets every fork to branch 0 by default. An auto-planned run must clear the operator
		// rows of ITS OWN forks so the granted (planned) leg is the one the walker crosses; the
		// row comes back through mmtrEnsurePointDefaults only when the rail set changes, and a
		// human can still block a mission with an authority lock instead.
		for (final String[] op : plan.forkOps) {
			simulator.mmtrPointBranches.set(Long.parseLong(op[0]), Long.parseLong(op[1]), Long.parseLong(op[2]), op[3], -1);
		}
		simulator.persistMmtrPointBranches();
		final String owner = "v" + getId();
		mmtrPointOwner = owner;
		final org.mtr.core.mmtr.point.MmtrPointAuthority authority = simulator.mmtrPointAuthority;
		if (mmtrMotionWalker != null) {
			mmtrMotionWalker.setPointAuthority(authority, owner);
		}
		// S5: publish the movement as a first-class 进路 (route) BEFORE requesting the turnouts, so the
		// signal layer sees PENDING (danger) while the points are being taken and SET the moment every
		// one of them is held - the signal can never show proceed for a route the interlocking has not
		// set. A shunt (调车) keeps its own kind: it is authorised by a subsidiary aspect, not a main one.
		mmtrRoute = simulator.mmtrRoutes.request(new org.mtr.core.mmtr.route.MmtrRoute(
			getId(), owner,
			mmtrRouteKindOf(mmtrMission, simulator.mmtrShuntAuthorities.active(getId()) != null),
			plan.routeRailHexes, plan.forkOps, plan.targetRailHex, data.getCurrentMillis()));
		final boolean allForksGranted = requestPendingForksAtomically(authority, owner, data.getCurrentMillis() + MMTR_POINT_REQUEST_MILLIS);
		simulator.mmtrRoutes.refresh(getId(), authority);
		return allForksGranted;
	}

	/**
	 * T1b: request every fork this train is currently approaching **as one atomic set**
	 * ({@link org.mtr.core.mmtr.point.MmtrPointAuthority#requestAtomically}).
	 *
	 * <p>The old call ({@code MmtrRunPlanner.requestForkOps}) walked the pending set point by point and
	 * kept whatever it managed to take when a later point was refused - hold-and-wait, and with the
	 * per-tick window refresh it never timed out: two trains needing the same two points in opposite
	 * orders deadlocked permanently. All-or-nothing means a refused set leaves this train holding
	 * nothing, so no cycle of holds can form.</p>
	 *
	 * <p>The set is "what is inside the approach window right now", so a far point is still never
	 * pre-occupied - the approach-locking property is untouched; only the granularity of the
	 * acquisition changed.</p>
	 *
	 * @return whether the whole set is currently granted to this owner
	 */
	private boolean requestPendingForksAtomically(org.mtr.core.mmtr.point.MmtrPointAuthority authority, String owner, long untilMillis) {
		if (mmtrPendingPointOps.isEmpty()) {
			return true;
		}
		return authority.requestAtomically(mmtrPendingPointOps, owner, untilMillis)
			== org.mtr.core.mmtr.point.MmtrPointAuthority.Result.GRANTED;
	}

	/**
	 * P3 (approach locking): while the auto run is armed, add the plan forks that just entered the
	 * approach window to the pending request set (idempotent - never re-adds, crossed forks are
	 * drained separately). Far forks are deliberately NOT requested: the leading train must get
	 * the point first when it arrives.
	 */
	private void replenishForkRequests(Simulator simulator) {
		if (mmtrMotionPlan == null || mmtrMotionWalker == null || mmtrMotionPlan.forkOps.isEmpty()) {
			return;
		}
		final double distanceNow = mmtrMotionWalker.distanceM();
		for (int j = 0; j < mmtrMotionPlan.forkOps.size(); j++) {
			final String[] op = mmtrMotionPlan.forkOps.get(j);
			final double forkAbsM = j < mmtrMotionPlan.forkMeters.size() ? mmtrMotionPlan.forkMeters.get(j) : Double.NaN;
			final double remainingM = forkAbsM - distanceNow;
			if (remainingM <= 0 || remainingM > MMTR_APPROACH_LOCK_METERS) {
				// ①: while held at the signal before the block whose far end is an unset turnout, the
				// request must still be out - the train may be a whole block (up to 187 m in the dev
				// world) short of it, so the 120 m approach window would never open and the run would
				// wait forever. Only the NEXT fork is requested this way (never the whole route).
				if (!mmtrSectionAuthorityHold || remainingM <= 0 || pendingContainsFork(op)) {
					continue;
				}
			} else if (pendingContainsFork(op)) {
				continue;
			}
			mmtrPendingPointOps.add(op.clone());
			if (mmtrSectionAuthorityHold) {
				return; // just the next fork ahead
			}
		}
	}

	private boolean pendingContainsFork(String[] op) {
		for (final String[] p : mmtrPendingPointOps) {
			if (p[0].equals(op[0]) && p[1].equals(op[1]) && p[2].equals(op[2]) && p[3].equals(op[3])) {
				return true;
			}
		}
		return false;
	}

	/** Drop crossed forks from the pending request set (their holds were released at the crossing). */
	private void drainMmtrCrossedPoints() {
		if (mmtrPointOwner.isEmpty() || mmtrMotionWalker == null || mmtrPendingPointOps.isEmpty()) {
			return;
		}
		final ObjectArrayList<String> crossed = mmtrMotionWalker.drainCrossedPointKeys();
		if (crossed.isEmpty()) {
			return;
		}
		mmtrPendingPointOps.removeIf(op -> crossed.contains(op[0] + "," + op[1] + "," + op[2] + "|" + op[3]));
		// S5: route locking releases sectionally - a crossed turnout no longer has to be held for the
		// route, so the route stays SET over the rails ahead of the train.
		if (mmtrRoute != null) {
			crossed.forEach(mmtrRoute::markForkCrossed);
		}
	}

	/** P3: drop every turnout request this vehicle holds/queued (terminal missions, yard reset). */
	public void releaseMmtrPointRequests() {
		if (!mmtrPointOwner.isEmpty()) {
			final org.mtr.core.mmtr.point.MmtrPointAuthority authority = data instanceof final Simulator simulator ? simulator.mmtrPointAuthority : null;
			if (authority != null) {
				authority.releaseAll(mmtrPointOwner);
			}
			mmtrPointOwner = "";
		}
		if (mmtrMotionWalker != null) {
			mmtrMotionWalker.setPointAuthority(null, null);
		}
		mmtrPendingPointOps.clear();
		mmtrMotionPlan = null;
		// S5: a released movement drops its route - the signal layer must stop reading it as set. The
		// release is by vehicle id, never gated on this object's field: after a coupling surgery the
		// live Vehicle is a NEW object and the route belongs to the train, not to the Java object.
		if (data instanceof final Simulator routeSimulator) {
			routeSimulator.mmtrRoutes.release(getId());
		}
		mmtrRoute = null;
	}

	/**
	 * Whether the train is stationary at its mission target: either stopped on the platform whose
	 * id equals the target (PASSENGER service) or stopped at the end of the route (target 0 =
	 * terminal / freight destination).
	 */
	private boolean isStoppedAtMissionTarget() {
		final long targetSidingId = mmtrMission == null ? 0 : mmtrMission.getTargetSidingId();
		if (isMoving() || !getIsOnRoute()) {
			return false;
		}
		if (targetSidingId == 0) {
			// Terminal / freight run: the consist either stopped at the far end of the path or
			// has been wrapped back into its depot slot after finishing the run.
			return railProgress >= vehicleExtraData.getTotalDistance() - 1 || closeToDepot();
		}
		return vehicleExtraData.getThisPlatformId() == targetSidingId;
	}

	public boolean getIsOnRoute() {
		return railProgress > vehicleExtraData.getDefaultPosition();
	}

	public boolean getReversed() {
		return reversed;
	}

	public boolean closeToDepot() {
		return !getIsOnRoute() || railProgress < vehicleExtraData.getTotalVehicleLength() + vehicleExtraData.getRailLength();
	}

	public void initVehiclePositions(Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> vehiclePositions) {
		// Motion-mode vehicles seed their occupancy from the live walker shadow each tick instead.
		if (mmtrMotionWalker == null) {
			writeVehiclePositions(Utilities.getIndexFromConditionalList(vehicleExtraData.immutablePath, railProgress), vehiclePositions);
		}
	}

	public void simulate(long millisElapsed, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions, @Nullable Long2ObjectOpenHashMap<LongObjectImmutablePair<Vehicle>> vehicleTimesAlongRoute) {
		// MMTR: release the explicit override as soon as its driver no longer rides as a cab
		// driver (occupation lock), so a stale ControlState never keeps a consist moving and a
		// new driver can take over.
		if (!isClientside && MmtrDriveAccess.shouldAutoRelease(mmtrManualOverride, mmtrDriverUuid, mmtrDriverUuid != null && hasMmtrDriverRiding(mmtrDriverUuid) && holdsMmtrCabKey(mmtrDriverUuid))) {
			releaseMmtrManualOverride();
		}

		// MMTR (server): protection lock countdown after an overrun/SPAD emergency stop.
		if (!isClientside && mmtrProtection && speed <= 0) {
			mmtrProtectionLockRemaining -= millisElapsed;
			if (mmtrProtectionLockRemaining <= 0) {
				mmtrProtection = false;
				mmtrProtectionLockRemaining = 0;
				System.out.println("[MMTR-DRV] protection lock cleared");
			}
		}

		final int currentIndex;
		final BooleanBooleanImmutablePair containsDriverAndDoorOverride = vehicleExtraData.containsDriverAndDoorOverride();
		manualCooldown = vehicleExtraData.getIsManualAllowed() && containsDriverAndDoorOverride.leftBoolean() ? vehicleExtraData.getManualToAutomaticTime() : Math.max(0, manualCooldown - millisElapsed);
		doorCooldown = vehicleExtraData.getDoorMultiplier() > 0 || containsDriverAndDoorOverride.rightBoolean() ? DOOR_MOVE_TIME + DOOR_DELAY : Math.max(0, doorCooldown - millisElapsed);

		// MMTR: an active operator / AI controller (explicit override) keeps the train in manual
		// control - never let the stock ATO hand-back or auto platform-stopping take over mid-drive.
		if (mmtrManualOverride) {
			manualCooldown = Math.max(1, manualCooldown);
			atoOverride = false;
		}

		// MMTR (L3): live Motion-Core run vehicles (see {@link #engageMmtrMotion}) tick their own
		// segment+offset state machine; the legacy baked-path on-route/stopped/depot dispatch does not
		// apply while engaged.
		final boolean mmtrMotionMode = !isClientside && mmtrMotionWalker != null;
		// Client mirrors of motion vehicles replay along the synced leg shadow: they must never treat
		// the shadow end as a baked journey end - they hold at the armed stop target and at the shadow
		// end (authority halt / end of line) until the next server update extends or re-arms the run.
		if (mmtrMotionMirror && railProgress > 0) {
			if (mmtrRunStopTarget >= 0 && railProgress >= mmtrRunStopTarget - 1e-3) {
				speed = 0;
			} else if (mmtrRunTotalDistance > 0 && railProgress >= mmtrRunTotalDistance - 1e-6) {
				speed = 0;
			}
		}

		if (mmtrMotionMode) {
			simulateMmtrMotion(millisElapsed, vehiclePositions);
			currentIndex = 0;
		} else if (getIsOnRoute()) {
			if (!mmtrMotionMirror && vehicleExtraData.getRepeatIndex2() == 0 && railProgress >= vehicleExtraData.getTotalDistance() - (vehicleExtraData.getRailLength() - vehicleExtraData.getTotalVehicleLength()) / 2) {
				// If the route does not repeat infinitely and the vehicle is reaching the end
				currentIndex = 0;
				if (!isClientside) {
					vehicleExtraData.setPowerLevel(Math.min(vehicleExtraData.getPowerLevel(), -1));
				}
				vehicleExtraData.passengers.forEach(ObjectArraySet::clear);
				simulateInDepot();
			} else {
				// If the vehicle is on route normally
				currentIndex = Utilities.getIndexFromConditionalList(vehicleExtraData.immutablePath, railProgress);
				if (speed <= 0) {
					// If the vehicle is stopped (at a platform or waiting for a signal)
					speed = 0;
					simulateStopped(millisElapsed, vehiclePositions, currentIndex);
				} else {
					// If the vehicle is moving normally
					simulateMoving(millisElapsed, vehiclePositions, currentIndex);
				}
			}
		} else {
			currentIndex = 0;
			// MMTR: a client mirror of a motion vehicle must NOT run the depot simulation. It would
			// overwrite the synced railProgress with the siding's default position
			// ((railLength + trainLength) / 2) and clear reversed on every frame, so a multi-car
			// consist is placed relative to a railProgress that no longer matches the synced path -
			// every car then falls outside the path and the whole train collapses onto one point.
			if (!mmtrMotionMirror) {
				simulateInDepot();
			}
		}

		stoppingCooldown = Math.max(0, stoppingCooldown - millisElapsed);

		if (vehiclePositions != null && vehiclePositions.size() > 1) {
			if (mmtrMotionMode) {
				writeMmtrMotionVehiclePositions(vehiclePositions.get(1));
			} else {
				writeVehiclePositions(currentIndex, vehiclePositions.get(1));
			}
		}

		// MMTR signal display (server-authoritative): a running motion train registers the rails it
		// occupies into the standard rail signal-block channel (under the per-rail MMTR reserved
		// signal color). Rail#tick1 diffs the holds and pushes SignalBlockUpdates to every nearby
		// client, so the in-game signal lights protecting those rails turn red for all players -
		// independent of each client's locally simulated vehicles. Legacy path vehicles already do
		// this via writeVehiclePositions; motion vehicles need the explicit call here.
		if (!isClientside && mmtrMotionWalker != null && !mmtrMotionLegs.isEmpty()) {
			final int headLegIndex = indexInMmtrMotionLegs(railProgress);
			final double tailProgress = railProgress - vehicleExtraData.getTotalVehicleLength();
			for (int index = headLegIndex; index >= 0; index--) {
				final PathData leg = mmtrMotionLegs.get(index);
				if (tailProgress > leg.getEndDistance()) {
					break;
				}
				markMmtrSignalBlock(leg, index == headLegIndex, tailProgress);
			}
		}

		if (vehicleTimesAlongRoute != null && !mmtrMotionMode) {
			final long timeAlongRoute = getTimeAlongRoute(railProgress);
			if (timeAlongRoute > 0) {
				vehicleTimesAlongRoute.put(departureIndex, new LongObjectImmutablePair<>(timeAlongRoute, this));
			}
		}

		if (!isClientside) {
			if (data instanceof final Simulator simulator) {
				// Remove entities that have dismounted
				vehicleExtraData.removeRidingEntitiesIf(vehicleRidingEntity -> !simulator.isRiding(vehicleRidingEntity.uuid, id));

				// Check jam status
				if (simulator.getCurrentMillis() - lastMovementMillis >= JAM_THRESHOLD) {
					simulator.markRouteJammed(vehicleExtraData.getPreviousRouteId());
					simulator.markRouteJammed(vehicleExtraData.getThisRouteId());
					simulator.markRouteJammed(vehicleExtraData.getNextRouteId());
				}
			}

			// Update the manual state for the client
			vehicleExtraData.setIsCurrentlyManual(isCurrentlyManual());

			// MMTR: keep the mission alive. An AUTOPILOT mission on a manual-allowed consist is
			// driven headlessly (refresh the manual seam so it never times out), then the mission
			// state machine advances from observed train state (moved / at target / dwell done).
			// Motion-mode missions run through the auto step-run instead - no legacy manual seam.
			if (mmtrMission != null && !mmtrMission.isTerminal() && mmtrMission.getState() != MmtrMission.State.AT_TARGET && mmtrMission.getExecutor() == MmtrMission.Executor.AUTOPILOT && vehicleExtraData.getIsManualAllowed() && mmtrMotionWalker == null) {
				engageMissionAutopilot();
			}
			mmtrMissionTick();
		}
	}

	public void startUp(long newDepartureIndex, long newSidingDepartureTime) {
		if (isClientside) {
			log.warn("Vehicle#startUp should only be called on the server side!");
		}

		vehicleExtraData.closeDoors();
		lastMovementMillis = data.getCurrentMillis();

		// Ensure doors are closed before starting up
		if (doorCooldown == 0) {
			departureIndex = newDepartureIndex;
			sidingDepartureTime = newSidingDepartureTime;
			railProgress += Siding.ACCELERATION_DEFAULT;
			elapsedDwellTime = 0;
			speed = Siding.ACCELERATION_DEFAULT;
			atoOverride = false;
			vehicleExtraData.setSpeedTarget(speed);
			setNextStoppingIndex();

			// Calculate deviation speed adjustment
			updateDeviation();
			if (deviation > 0 && nextStoppingIndexAto < vehicleExtraData.immutablePath.size() - 1 && siding != null && siding.getDelayedVehicleSpeedIncreasePercentage() > 0) {
				final double endRailProgress = vehicleExtraData.immutablePath.get((int) nextStoppingIndexAto).getEndDistance();
				final double distance = endRailProgress - railProgress;
				final double scheduledDuration = getTimeAlongRoute(endRailProgress) - getTimeAlongRoute(railProgress);
				final double expectedDuration = Math.max(1, scheduledDuration - deviation);
				final double averageSpeed = distance / scheduledDuration;
				final double expectedSpeed = distance / expectedDuration;
				deviationSpeedAdjustment = Math.min(expectedSpeed / averageSpeed, siding.getDelayedVehicleSpeedIncreasePercentage() / 100F + 1);
			} else {
				deviationSpeedAdjustment = 1;
			}
		}
	}

	public long getDepartureIndex() {
		return departureIndex;
	}

	public ObjectArrayList<ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<BogiePosition>>> getVehicleCarsAndPositions() {
		final ObjectArrayList<ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<BogiePosition>>> vehicleCarsAndPositions = new ObjectArrayList<>();
		double checkRailProgress = railProgress - (reversed ? vehicleExtraData.getTotalVehicleLength() : 0);

		for (int i = 0; i < vehicleExtraData.immutableVehicleCars.size(); i++) {
			final VehicleCar vehicleCar = vehicleExtraData.immutableVehicleCars.get(i);
			checkRailProgress += (reversed ? 1 : -1) * vehicleCar.getCouplingPadding1(i == 0);
			final double halfLength = vehicleCar.getLength() / 2;
			final ObjectArrayList<BogiePosition> bogiePositionsList = new ObjectArrayList<>();
			final DoubleArrayList overrideY = new DoubleArrayList(); // For airplanes, don't nosedive when descending
			bogiePositionsList.add(getBogiePositions(checkRailProgress + (reversed ? 1 : -1) * (halfLength + vehicleCar.getBogie1Position()), overrideY));

			if (!vehicleCar.hasOneBogie) {
				bogiePositionsList.add(getBogiePositions(checkRailProgress + (reversed ? 1 : -1) * (halfLength + vehicleCar.getBogie2Position()), overrideY));
			}

			vehicleCarsAndPositions.add(new ObjectObjectImmutablePair<>(vehicleCar, bogiePositionsList));
			checkRailProgress += (reversed ? 1 : -1) * vehicleCar.getTotalLength(true, false);
		}

		return vehicleCarsAndPositions;
	}

	@Nullable
	public PositionAndTiltAngle getHeadPositionAndTiltAngle() {
		return getPositionAndTiltAngle(railProgress, new DoubleArrayList());
	}

	void updateRidingEntities(ObjectArrayList<VehicleRidingEntity> vehicleRidingEntities) {
		if (!isClientside && data instanceof final Simulator simulator) {
			final ObjectOpenHashSet<UUID> uuidToRemove = new ObjectOpenHashSet<>();
			final ObjectOpenHashSet<VehicleRidingEntity> vehicleRidingEntitiesToAdd = new ObjectOpenHashSet<>();

			vehicleRidingEntities.forEach(vehicleRidingEntity -> {
				uuidToRemove.add(vehicleRidingEntity.uuid);

				if (vehicleRidingEntity.isOnVehicle()) {
					vehicleRidingEntitiesToAdd.add(vehicleRidingEntity);
					simulator.ride(vehicleRidingEntity.uuid, id);
				} else {
					simulator.stopRiding(vehicleRidingEntity.uuid);
				}

				if (vehicleExtraData.getIsManualAllowed() && vehicleRidingEntity.isDriver()) {
					final int powerLevel = vehicleExtraData.getPowerLevel();
					if (vehicleRidingEntity.manualToggleDoors()) {
						if (speed > 0) {
							vehicleExtraData.closeDoors();
						} else {
							vehicleExtraData.toggleDoors();
						}
					}

					if (vehicleRidingEntity.manualToggleAto()) {
						atoOverride = speed > 0 && !atoOverride;
					}

					if (vehicleRidingEntity.manualAccelerate()) {
						vehicleExtraData.setPowerLevel(Math.min(powerLevel + 1, MAX_POWER_LEVEL));
						atoOverride = false;
					} else if (vehicleRidingEntity.manualBrake()) {
						vehicleExtraData.setPowerLevel(Math.max(powerLevel - 1, -MAX_POWER_LEVEL - 1));
						atoOverride = false;
					}
				}
			});

			vehicleExtraData.removeRidingEntitiesIf(vehicleRidingEntity -> uuidToRemove.contains(vehicleRidingEntity.uuid));
			vehicleExtraData.addRidingEntities(vehicleRidingEntitiesToAdd);
		}
	}

	/**
	 * B3b: register this consist's hold on ONE leg's rail. A rail that is not split carries a single
	 * MMTR colour, so the legacy whole-rail reservation ({@link Rail#isBlocked}, which also keeps the
	 * MTR block semantics for the legacy path) is exactly right. A rail split by a wayside signal
	 * carries one colour per SECTION instead, and only the sections the consist actually stands on are
	 * reserved - otherwise a train in the far section would still close the near one and the display
	 * could never count sections.
	 */
	private void markMmtrSignalBlock(PathData leg, boolean isHeadLeg, double tailProgress) {
		final Rail legRail = leg.getRail();
		final ObjectArrayList<org.mtr.core.mmtr.signal.MmtrBlockService.Block> sections =
			data instanceof final Simulator simulator ? simulator.mmtrBlocks.blocksOf(legRail.getHexId()) : null;
		if (sections == null || sections.size() <= 1) {
			legRail.isBlocked(id, Rail.BlockReservation.CURRENTLY_RESERVE);
			return;
		}
		final double legLength = leg.getEndDistance() - leg.getStartDistance();
		if (legLength <= 0) {
			return;
		}
		final double headOffset = isHeadLeg ? Utilities.clampSafe(railProgress - leg.getStartDistance(), 0, legLength) : legLength;
		final double tailOffset = Utilities.clampSafe(tailProgress - leg.getStartDistance(), 0, legLength);
		// The leg runs from its entry node; convert the two offsets into the rail's ordered-1 arc space.
		final double headArc = leg.reversePositions ? legLength - headOffset : headOffset;
		final double tailArc = leg.reversePositions ? legLength - tailOffset : tailOffset;
		final double arcFrom = Math.min(headArc, tailArc);
		final double arcTo = Math.max(headArc, tailArc);
		for (final org.mtr.core.mmtr.signal.MmtrBlockService.Block section : sections) {
			if (section.arcToM > arcFrom + 1e-9 && section.arcFromM < arcTo - 1e-9) {
				legRail.mmtrReserveSignalColor(id, section.signalColor);
			}
		}
	}

	long getSidingDepartureTime() {
		return sidingDepartureTime;
	}

	private void simulateInDepot() {
		railProgress = vehicleExtraData.getDefaultPosition();
		reversed = false;
		speed = 0;
		nextStoppingIndexAto = 0;
		nextStoppingIndexManual = 0;
		departureIndex = -1;
		sidingDepartureTime = -1;
		vehicleExtraData.closeDoors();

		if (!isClientside && isCurrentlyManual() && !mmtrProtection && (vehicleExtraData.getPowerLevel() > 0 || isMmtrRequestingPower())) {
			startUp(-1, data.getCurrentMillis());
		}
	}

	private void simulateStopped(long millisElapsed, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions, int currentIndex) {
		if (isClientside) {
			return;
		}

		final PathData pathData = Utilities.getElement(vehicleExtraData.immutablePath, currentIndex);
		if (pathData == null) {
			return;
		}

		vehicleExtraData.setStoppingPoint(railProgress);
		stoppingCooldown = 0;

		if (isCurrentlyManual()) {
			lastMovementMillis = data.getCurrentMillis();
			if (railProgress == pathData.getStartDistance()) {
				// Stopped behind a node
				final PathData currentPathData = Utilities.getElement(vehicleExtraData.immutablePath, currentIndex - 1);
				final PathData nextPathData = Utilities.getElement(vehicleExtraData.immutablePath, vehicleExtraData.getRepeatIndex2() > 0 && currentIndex >= vehicleExtraData.getRepeatIndex2() ? vehicleExtraData.getRepeatIndex1() : currentIndex);
				final boolean isOpposite = currentPathData != null && nextPathData != null && currentPathData.isOppositeRail(nextPathData);
				final double nextStartDistance = nextPathData == null ? 0 : nextPathData.getStartDistance() + (isOpposite ? vehicleExtraData.getTotalVehicleLength() : 0);

				if (!mmtrProtection && (vehicleExtraData.getPowerLevel() > 0 || isMmtrRequestingPower()) && railBlockedDistance(currentIndex, nextStartDistance, 0, vehiclePositions, true, false) < 0) {
					if (doorCooldown == 0) {
						railProgress = nextStartDistance;
						if (isOpposite) {
							reversed = !reversed;
						}
					}
					startUp(departureIndex, sidingDepartureTime);
				}
			} else {
				// Stopped anywhere else
				if (!mmtrProtection && (vehicleExtraData.getPowerLevel() > 0 || isMmtrRequestingPower()) && railBlockedDistance(currentIndex, railProgress, 0, vehiclePositions, true, false) < 0) {
					startUp(departureIndex, sidingDepartureTime);
				}
			}
		} else {
			// MTR timetable/ATO auto driving removed (auto rebuilt on Motion/tasks): an unmanned
			// consist stopped at a stop does not auto-dwell / open doors / auto-restart. It only resumes
			// when a driver (ControlState / mmtrManualOverride) or a task/mission drives it.
		}
	}

	/**
	 * MMTR (L3): one tick of the live Motion-Core run state machine (server). The existing cab control
	 * (a ControlState via {@link #applyMmtrControl}) drives the MMTR physics model exactly like the
	 * legacy path branch; the integrated distance advances the embedded {@link MmtrMotionWalker},
	 * which moves the consist by (segment, offset) and elects each next rail at the node from the
	 * CURRENT turnout state / task (an unset fork halts and waits — the next tick re-asks, so flipping
	 * the branch makes the same vehicle continue). Speed is zeroed the moment the walker can no longer
	 * consume distance (authority halt / end of line). Platform dwell / doors / signals are later
	 * slices; doors stay closed while running.
	 */
	private void simulateMmtrMotion(long millisElapsed, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions) {
		mmtrMotionMirror = true;
		// REV 换向器: a direction change requested while rolling is held back (traction is cut) until the
		// consist is at a stand, where it is applied without moving anything - exactly the interlock a
		// real reverser has, and the only way a flip cannot teleport the motion.
		if (mmtrReverserPending && speed <= 1e-9) {
			mmtrReverserPending = false;
			applyMmtrTravelReversed(mmtrActiveControl != null && mmtrActiveControl.getReverser() < 0);
		}
		final ControlState control = mmtrActiveControl;
		final boolean overridden = mmtrManualOverride && control != null;
		final boolean consistBody = mmtrMotionWalker instanceof MmtrConsistWalker;
		// A consist-body train runs whichever way the reverser points (R1); the legacy single-point
		// walker has no reverse and keeps the old rule (reverser must be forward).
		final boolean forwardRequested = control != null && (consistBody ? control.getReverser() != 0 : control.getReverser() > 0);
		final boolean wantPower = overridden && forwardRequested && !mmtrReverserPending && control.getThrottleNotch() > 0 && !(control.getBrakeNotch() > 0 || control.isEmergency());
		final boolean braking = overridden && (control.getBrakeNotch() > 0 || control.isEmergency());
		final boolean stopTargetActive = mmtrMotionStopTargetM >= 0;

		// Signal S1: re-derive the occupancy stop every tick from the shared occupancy trees (server
		// authority). The effective stop of this tick is the nearer of the armed stop target and the
		// occupancy block stop. While parked AT the block stop the waiting flag clears itself only
		// once that stop moves past the head / disappears - auto runs then resume to their target and
		// manual drivers regain traction.
		mmtrBlockStopM = computeMmtrBlockStopM(vehiclePositions);
		// 尽头换向: while a planned dead-end flip is still pending, the dead end itself is a brake
		// target (the train must come to rest there before changing ends); it leaves the brake set
		// the moment the flip has happened, so the run accelerates away toward the real stop target.
		final double planFlipM = !mmtrMotionFlipDone && mmtrMotionPlan != null && !mmtrMotionPlan.flipRailHex.isEmpty() ? mmtrMotionPlan.flipCumulativeM : Double.MAX_VALUE;
		final double brakeTargetM = Math.min(Math.min(stopTargetActive ? mmtrMotionStopTargetM : Double.MAX_VALUE, mmtrBlockStopM), planFlipM);
		if (mmtrBlockedWaiting && brakeTargetM - mmtrMotionWalker.distanceM() > 1e-3) {
			mmtrBlockedWaiting = false;
			System.out.println("[MMTR-SIG] occupancy block cleared - " + (stopTargetActive ? "auto resumes to stop target " + Math.round(mmtrMotionStopTargetM * 100.0) / 100.0 + "m" : "manual control resumes"));
		}

		// Stopped exactly at the armed stop target: hold there. Doors stay open when the stop asked
		// for it; a FRESH control application (driver pushes again / task re-commands) closes the
		// doors and starts the next run from the same spot.
		if (mmtrMotionStoppedAtTarget) {
			speed = 0;
			if (mmtrMotionStopOpenDoors) {
				vehicleExtraData.openDoors();
			}
			if (overridden && wantPower && mmtrControlApplySeq != mmtrMotionArrivalControlSeq) {
				vehicleExtraData.closeDoors();
				mmtrMotionStopTargetM = -1;
				mmtrMotionStoppedAtTarget = false;
				mmtrMotionArrivalControlSeq = -1;
				mmtrRunStopTarget = -1;
				vehicleExtraData.mmtrMarkSyncDirty();
				System.out.println("[MMTR-DRV] motion departed stop target");
			}
			if (!isClientside) {
				vehicleExtraData.setPowerLevel(0);
				vehicleExtraData.setSpeedTarget(0);
				updateMmtrSyncFields();
			}
			return;
		}

		// Signal S3 (AWS): live manual driving on AWS-band rails gets the point-style warning state
		// machine (restricted boundary ahead -> WARN -> acknowledge / SPAD). Runs before the drive
		// chain so a fresh SPAD suppresses traction this very tick.
		tickMmtrAwsWarning(millisElapsed);

		// MMTR: only a moving train closes its doors (MTR's safety rule). Closing them every tick also
		// reverted a crew door command on the next tick, so a standing train could never be opened -
		// neither from the platform interact key nor from the cabin door key.
		if (speed != 0) {
			vehicleExtraData.closeDoors();
		}
		final double previousSpeed = speed;
		final double remainingToBrake = brakeTargetM < Double.MAX_VALUE / 2 ? brakeTargetM - mmtrMotionWalker.distanceM() : Double.MAX_VALUE;
		final boolean brakeTargetActive = brakeTargetM < Double.MAX_VALUE / 2;
		// Unmanned auto run: drives itself toward the armed stop target while no cab override is held.
		final boolean autoActive = mmtrMotionAuto && !mmtrManualOverride && stopTargetActive && !mmtrMotionStoppedAtTarget && !mmtrBlockedWaiting && remainingToBrake > 1e-6;
		if (autoActive || overridden) {
			// Resolve the consist type / controller lazily like the driver path does - the auto
			// planner and the LZB supervision read the traction/brake parameters and the AIR_BRAKE
			// split from the type.
			tryInitMmtrController();
		}
		final int autoNotch = mmtrConsistType != null ? Math.max(1, Math.min(4, mmtrConsistType.getPowerNotches())) : 4;
		// Service-brake envelope against the EFFECTIVE stop (armed stop target or the occupancy block
		// stop, whichever is nearer). Occupancy braking overrides a driver's own traction but the
		// driver's own braking (incl. emergency) stays stronger and takes over the branch below.
		final boolean autoBraking = brakeTargetActive && !(overridden && braking) && speed > 0 && remainingToBrake > 0 && remainingToBrake < 0.5 * speed * speed / Math.max(mmtrMotionServiceDecelPerMs(), 1e-12);

		double integratedDistance = 0;
		final boolean airBrakeConsist = mmtrConsistType != null && mmtrConsistType.getControlMode() == ConsistType.ControlMode.AIR_BRAKE;
		if (mmtrProtection) {
			// Signal S3: motion-mode SPAD execution (unacknowledged AWS warning / overrun). Emergency
			// brake overrides any traction; the 10 s lock countdown lives in simulate().
			final double emergencyPerMs = mmtrConsistType != null ? MmtrSupport.siAccelerationToInternal(mmtrConsistType.getEmergencyDecelerationMps2()) : vehicleExtraData.getDeceleration() * 2e-3;
			speed = Math.max(0, speed - emergencyPerMs * millisElapsed);
			integratedDistance = speed * millisElapsed;
		} else if (mmtrBlockedWaiting) {
			// Parked exactly at the occupancy stop point: traction is suppressed (never creep into
			// the occupied rail); the flag clears at the top of a later tick once the block opens.
			speed = 0;
		} else if (autoBraking) {
			// Service-brake to an exact rest at the effective stop (constant-decel law; the trailing
			// clamp below trims the last sub-tick remainder). Driver traction is overridden inside the
			// braking envelope, like an ATO stop; the driver's own emergency brake stays stronger.
			final double brakeDelta = Math.min(0.5 * speed * speed / Math.max(remainingToBrake, 1e-3), mmtrMotionServiceDecelPerMs()) * millisElapsed;
			speed = Math.max(0, speed - brakeDelta);
			integratedDistance = speed > 0 ? speed * millisElapsed : 0;
		} else if (autoActive && !airBrakeConsist) {
			// Signal S2: deterministic auto cruise under the per-segment rail speed limit (directional,
			// read live from the rail the walker stands on). Cruise target = min(rail limit, consist
			// ceiling). A SLOWER rail ahead (peeked - same elect contract as the walker) is braced for
			// with the service-brake envelope so the train crosses the node at (about) that rail's
			// limit instead of over-running it; residual overspeed after boarding a slower rail decays
			// at service deceleration. (Air-brake consists keep the controller path below.)
			final double accelPerMs = mmtrConsistType != null ? MmtrSupport.siAccelerationToInternal(mmtrConsistType.getTractionAccelerationMps2()) : vehicleExtraData.getAcceleration() * 1e-3;
			final double decelPerMs = mmtrMotionServiceDecelPerMs();
			final double cruiseCap = Math.min(mmtrCurrentRailLimitPerMs(), mmtrConsistType != null ? kmhToInternal(mmtrConsistType.getMaxSpeedKmh()) : vehicleExtraData.getMaxManualSpeed());
			final Rail nextRailForLimit = mmtrMotionWalker.peekNextRail();
			final double nextLimitMms = nextRailForLimit == null ? -1 : nextRailForLimit.getSpeedLimitMetersPerMillisecond(mmtrMotionWalker.aheadNode());
			final boolean bracingForSlowerRail = nextLimitMms > 0 && nextLimitMms < cruiseCap - 1e-12 && speed > nextLimitMms;
			if (bracingForSlowerRail) {
				// A slower rail is ahead. Inside the service-brake envelope (the deceleration needed
				// to arrive at the node at the slower rail's limit would exceed the service rate) the
				// train brakes with the exact-decel law; OUTSIDE the envelope it keeps cruising - the
				// envelope engages on its own as the node approaches.
				final double toNodeM = mmtrMotionWalker.currentRailLengthM() - mmtrMotionWalker.offsetM();
				// Look one tick ahead: the deceleration needed to arrive at the node at the slower
				// rail's limit AFTER this tick's travel. Engaging a tick early absorbs the
				// cruise-to-brake discrete step so the node is crossed at (about) the slower limit.
				final double needDecel = 0.5 * (speed * speed - nextLimitMms * nextLimitMms) / Math.max(toNodeM - speed * millisElapsed, 1e-3);
				if (needDecel > decelPerMs * 0.98) {
					speed = Math.max(nextLimitMms, speed - Math.min(needDecel, decelPerMs) * millisElapsed);
				} else {
					// Outside the envelope: cruise normally (the cap still applies below).
					speed = Math.min(cruiseCap, speed + accelPerMs * millisElapsed);
				}
			} else if (speed > cruiseCap) {
				// Over the cruise cap (e.g. just boarded a slower rail): service decay back to it.
				speed = Math.max(cruiseCap, speed - decelPerMs * millisElapsed);
			} else {
				speed = Math.min(cruiseCap, speed + accelPerMs * millisElapsed);
			}
			integratedDistance = speed * millisElapsed;
		} else if (overridden && !airBrakeConsist && mmtrConsistType != null && getMmtrRegime() == MmtrRegime.LZB) {
			// Signal S4 (LZB): continuous speed supervision for manual driving on the high-speed
			// band (>= 101 km/h rails). Unlike AWS (advisory), the LZB ceiling is ENFORCED: the
			// driver's traction may never push the train past min(current rail limit, consist
			// ceiling) - overspeed decays at service deceleration (Zwangsbremsung-style, gentle
			// first) - and a slower rail ahead is braced for with the service envelope so the
			// train crosses the node at (about) the slower rail's limit. The driver's own braking
			// (incl. emergency) stays stronger than the supervision. Air-brake consists keep the
			// controller path below.
			final double accelPerMs = mmtrConsistType != null ? MmtrSupport.siAccelerationToInternal(mmtrConsistType.getTractionAccelerationMps2()) : vehicleExtraData.getAcceleration() * 1e-3;
			final double decelPerMs = mmtrMotionServiceDecelPerMs();
			final double emergencyPerMs = mmtrConsistType != null ? MmtrSupport.siAccelerationToInternal(mmtrConsistType.getEmergencyDecelerationMps2()) : vehicleExtraData.getDeceleration() * 2e-3;
			final double lzbCeiling = Math.min(mmtrCurrentRailLimitPerMs(), kmhToInternal(mmtrConsistType.getMaxSpeedKmh()));
			final Rail nextRailLzb = mmtrMotionWalker.peekNextRail();
			final double nextLimitLzb = nextRailLzb == null ? -1 : nextRailLzb.getSpeedLimitMetersPerMillisecond(mmtrMotionWalker.aheadNode());
			if (braking) {
				speed = Math.max(0, speed - (control.isEmergency() ? emergencyPerMs : decelPerMs) * millisElapsed);
			} else if (nextLimitLzb > 0 && nextLimitLzb < lzbCeiling - 1e-12 && speed > nextLimitLzb) {
				final double toNodeM = mmtrMotionWalker.currentRailLengthM() - mmtrMotionWalker.offsetM();
				// One-tick look-ahead (same rule as the auto planner): engage the envelope before
				// this tick's travel crosses the braking point so the node is crossed at the slower
				// rail's limit instead of being overshot by the cruise-to-brake discrete step.
				final double needDecel = 0.5 * (speed * speed - nextLimitLzb * nextLimitLzb) / Math.max(toNodeM - speed * millisElapsed, 1e-3);
				if (needDecel > decelPerMs * 0.98) {
					speed = Math.max(nextLimitLzb, speed - Math.min(needDecel, decelPerMs) * millisElapsed);
				} else {
					speed = Math.min(lzbCeiling, speed + accelPerMs * millisElapsed);
				}
			} else if (speed > lzbCeiling) {
				speed = Math.max(lzbCeiling, speed - decelPerMs * millisElapsed);
			} else if (wantPower) {
				speed = Math.min(lzbCeiling, speed + accelPerMs * millisElapsed);
			} else {
				speed = Math.max(0, speed - decelPerMs * 0.1 * millisElapsed); // coast
			}
			integratedDistance = speed * millisElapsed;
		} else if ((overridden || autoActive) && tryInitMmtrController() && mmtrConsistType != null && mmtrDriveController != null) {
			// Driver (any consist) and auto air-brake consists run the fixed sub-step ConsistDynamics
			// integration (auto feeds a synthesized cruise ControlState; air-brake physics stay on the
			// per-car composition; an active cab override feeds the driver's own state).
			final ConsistType mmtrType = mmtrConsistType;
			final ControlState mmtrState = overridden ? control : new ControlState().setThrottleNotch(autoNotch).setReverser(1);
			final boolean useCompositionAir = mmtrType.getControlMode() == ConsistType.ControlMode.AIR_BRAKE;
			final MmtrComposition mmtrCompositionNow = useCompositionAir ? getMmtrComposition() : null;
			final double mmtrStartSpeedSi = MmtrSupport.internalSpeedToSi(speed);
			final ConsistDynamics.SpeedDistance mmtrResult = ConsistDynamics.advance(mmtrStartSpeedSi, mmtrType, millisElapsed, MMTR_INTEGRATION_SUB_STEP_MS, (siSpeed, stepMillis) -> {
				if (mmtrCompositionNow != null) {
					return mmtrCompositionNow.stepAir(mmtrState, siSpeed, stepMillis);
				}
				return mmtrDriveController.compute(mmtrState, mmtrType, siSpeed, stepMillis);
			});
			speed = MmtrSupport.siSpeedToInternal(mmtrResult.speedMetersPerSecond);
			// 火车不能倒车: the walker only moves forward - any negative speed from a controller is
			// clamped away defensively (braking/coasting already stay non-negative in ConsistDynamics).
			speed = Math.max(0, speed);
			integratedDistance = mmtrResult.distanceMeters;
			if (mmtrCompositionNow != null) {
				mmtrAirState = MmtrComposition.encodeAirStates(mmtrCompositionNow);
				mmtrPipePressure = mmtrCompositionNow.averagePipePressure();
				mmtrBrakeCylinderPressure = mmtrCompositionNow.averageCylinderPressure();
			}
		} else if (overridden) {
			// No consist-type policy: linear legacy-style integration from the driver's ControlState
			// notches. Stored VED values are SI (m/s^2) scaled by 1e-3; the internal per-ms rate is
			// SI * 1e-6, so the per-tick speed change is value * 1e-3 * millisElapsed (m/ms).
			final double accelPerMs = vehicleExtraData.getAcceleration() * 1e-3;
			final double decelPerMs = vehicleExtraData.getDeceleration() * 1e-3;
			if (braking) {
				speed = Math.max(0, speed - decelPerMs * millisElapsed);
			} else if (wantPower) {
				speed = Math.min(vehicleExtraData.getMaxManualSpeed(), speed + accelPerMs * millisElapsed);
			} else {
				speed = Math.max(0, speed - decelPerMs * 0.1 * millisElapsed); // coast-down
			}
			integratedDistance = speed * millisElapsed;
		} else if (speed > 0) {
			// Override released mid-run: service-brake to rest (occupation safety).
			speed = Math.max(0, speed - vehicleExtraData.getDeceleration() * 1e-3 * millisElapsed);
			integratedDistance = speed * millisElapsed;
		}

		if (brakeTargetActive && !mmtrMotionStoppedAtTarget) {
			final double remaining = brakeTargetM - mmtrMotionWalker.distanceM();
			if (remaining <= 1e-6) {
				integratedDistance = 0;
				speed = 0;
			} else if (integratedDistance > remaining) {
				integratedDistance = remaining; // land exactly on the effective stop (target or block)
			}
		}

		// C3a: a 调车授权 movement runs at the shunt speed limit - enforced like the LZB ceiling
		// (traction may not hold the train above it; the excess decays at service deceleration).
		final org.mtr.core.mmtr.signal.MmtrShuntAuthority shuntAuthority = getMmtrShuntAuthority();
		if (shuntAuthority != null) {
			final double shuntCap = kmhToInternal(shuntAuthority.getSpeedLimitKmh());
			if (speed > shuntCap) {
				speed = Math.max(shuntCap, speed - mmtrMotionServiceDecelPerMs() * millisElapsed);
				integratedDistance = speed * millisElapsed;
			}
		}

		if (integratedDistance > 0) {
			final double before = mmtrMotionWalker.distanceM();
			mmtrMotionWalker.advance(integratedDistance);
			// P3: forks crossed inside this advance had their authority holds released at the
			// crossing - drop them from the pending refresh set so they are never re-requested.
			drainMmtrCrossedPoints();
			if (mmtrMotionWalker.legCount() > mmtrMotionLegCount) {
				refreshMmtrMotionLegs();
				mmtrMotionLegCount = mmtrMotionWalker.legCount();
			}
			railProgress = mmtrMotionWalker.distanceM();
			// B7.2b: the consist mirror path is anchored to railProgress, so it must follow every tick
			// (the legacy leg shadow only changes when a new rail is boarded).
			if (mmtrMotionWalker instanceof MmtrConsistWalker) {
				refreshMmtrMotionLegs();
			}
			final double consumed = railProgress - before;
			if (stopTargetActive && railProgress >= mmtrMotionStopTargetM - 1e-6) {
				speed = 0;
				mmtrMotionArriveAtStopTarget();
			} else if (mmtrBlockStopM < Double.MAX_VALUE / 2 && railProgress >= mmtrBlockStopM - 1e-6) {
				// Arrived exactly at the occupancy stop (rail ahead occupied): rest and wait for it
				// to clear - not a terminal state, never opens doors, never reports a task arrival.
				speed = 0;
				if (!mmtrBlockedWaiting) {
					mmtrBlockedWaiting = true;
					System.out.println("[MMTR-SIG] motion stopped at block stop " + Math.round(mmtrBlockStopM * 100.0) / 100.0 + "m (" + mmtrBlockStopReason() + ", waiting)");
				}
			} else if (consumed < integratedDistance - 1e-9) {
				speed = 0;
				if (previousSpeed > 1e-9) {
					if (mmtrMotionWalker.atTarget()) {
						System.out.println("[MMTR-DRV] motion arrived at task target rail " + mmtrMotionWalker.railHex() + " (offset " + Math.round(mmtrMotionWalker.offsetM() * 100.0) / 100.0 + "m)");
					} else {
						// Authority halt at an unset fork / end of line: cannot consume the whole
						// integrated distance — come to rest and wait (a fresh advance re-asks the node).
						System.out.println("[MMTR-DRV] motion authority halt on " + mmtrMotionWalker.railHex() + " at " + Math.round(mmtrMotionWalker.offsetM() * 100.0) / 100.0 + "m (awaiting operator/task)");
					}
				}
			} else {
				lastMovementMillis = data.getCurrentMillis();
				org.mtr.core.mmtr.MmtrTrace.log("[MMTR-DRV] motion seg=" + mmtrMotionWalker.railHex() + " offset=" + Math.round(mmtrMotionWalker.offsetM() * 100.0) / 100.0 + " dist=" + Math.round(consumed * 1000.0) / 1000.0 + " speed=" + speed);
			}
		} else if (speed == 0 && brakeTargetActive && brakeTargetM - mmtrMotionWalker.distanceM() <= 1e-6) {
			if (stopTargetActive && mmtrMotionStopTargetM - mmtrMotionWalker.distanceM() <= 1e-6) {
				mmtrMotionArriveAtStopTarget();
			} else if (!mmtrBlockedWaiting) {
				// Already resting exactly at the block stop (e.g. the advance was clamped to zero
				// because the block point was reached inside this tick): enter the waiting state.
				mmtrBlockedWaiting = true;
				System.out.println("[MMTR-SIG] motion resting at block stop " + Math.round(mmtrBlockStopM * 100.0) / 100.0 + "m (" + mmtrBlockStopReason() + ", waiting)");
			}
		}

		// 尽头换向 (terminal flip): the armed plan turns the run around at the dead end of the flip
		// rail. Once the walker has come to rest exactly there (head at the rail's far node), change
		// ends and let the auto run continue back out to the real stop target.
		if (!mmtrMotionFlipDone && speed == 0 && mmtrMotionPlan != null && !mmtrMotionPlan.flipRailHex.isEmpty()
			&& mmtrMotionWalker != null && mmtrMotionWalker.railHex().equals(mmtrMotionPlan.flipRailHex)
			&& mmtrMotionWalker.distanceM() >= mmtrMotionPlan.flipCumulativeM - 1e-3) {
			if (mmtrMotionWalker.changeEnds(speed == 0)) {
				mmtrMotionFlipDone = true;
				mmtrMotionLegCount = mmtrMotionWalker.legCount();
				refreshMmtrMotionLegs();
				mmtrBlockedWaiting = false;
				System.out.println("[MMTR-DRV] flip 换端 at dead end of " + mmtrMotionWalker.railHex() + " - continuing to stop target " + Math.round(mmtrMotionStopTargetM * 100.0) / 100.0 + "m");
			} else {
				System.out.println("[MMTR-DRV] planned flip on " + mmtrMotionPlan.flipRailHex + " but the walker is not at its dead end");
				mmtrMotionFlipDone = true; // do not retry forever; the run is stopped and observable
			}
		}

		if (!isClientside) {
			final int displayPower = mmtrProtection ? MmtrSupport.LEGACY_EMERGENCY_POWER_LEVEL : mmtrBlockedWaiting ? 0 : overridden ? (wantPower ? control.getThrottleNotch() : braking ? -Math.max(1, control.getBrakeNotch()) : 0) : (autoActive ? autoNotch : 0);
			vehicleExtraData.setPowerLevel(displayPower);
			vehicleExtraData.setSpeedTarget(speed);
			updateMmtrSyncFields();
		}
	}

	/** Marks the exact arrival at the armed stop target: rest, doors per the stop request, hold. */
	private void mmtrMotionArriveAtStopTarget() {
		speed = 0;
		mmtrMotionStoppedAtTarget = true;
		mmtrMotionArrivalControlSeq = mmtrControlApplySeq;
		vehicleExtraData.closeDoors();
		if (mmtrMotionStopOpenDoors) {
			vehicleExtraData.openDoors();
		}
		System.out.println("[MMTR-DRV] motion arrived at stop target " + Math.round(mmtrMotionStopTargetM * 100.0) / 100.0 + "m (doors " + (mmtrMotionStopOpenDoors ? "open" : "closed") + ")");
	}

	/** Service deceleration in internal units (m/ms per ms); falls back to the VED value scaled to SI. */
	private double mmtrMotionServiceDecelPerMs() {
		final double siMps2 = mmtrConsistType != null ? mmtrConsistType.getServiceBrakeDecelerationMps2() : vehicleExtraData.getDeceleration() * 1000.0;
		return siMps2 * 1e-6;
	}

	/** Enables/disables the MMTR explicit control path (used by the future input layer). */
	public void setMmtrManualOverride(boolean enabled) { mmtrManualOverride = enabled; }

	/**
	 * Applies an explicit separated ControlState from the MMTR input layer (throttle/brake/
	 * reverser/axes), without a driver identity (legacy no-identity path for tests/tools).
	 */
	public void applyMmtrControl(ControlState controlState) {
		applyMmtrControl(controlState, null);
	}

	/**
	 * Applies an explicit separated ControlState from the MMTR input layer on behalf of
	 * {@code driverUuid}. Enables the explicit control path and records the driver occupation
	 * lock. Passing {@code null} control releases the override.
	 */
	public void applyMmtrControl(@Nullable ControlState controlState, @Nullable UUID driverUuid) {
		if (controlState == null) {
			releaseMmtrManualOverride();
			return;
		}
		final boolean wasOverride = mmtrManualOverride;
		mmtrActiveControl = controlState.copy();
		// Server-authoritative input guard: clamp whatever the client sent before storing/mirroring.
		MmtrDriveAccess.sanitize(mmtrActiveControl);
		// REV 换向器: on a consist-body train the reverser SELECTS the direction of travel (tail-first
		// while the same cab stays manned). The change takes effect at a stand; while rolling it is held
		// as pending and traction is cut until the consist stops, so the motion never teleports. A
		// legacy single-point train has no reverse and keeps the historical behaviour (reverser <= 0
		// simply gives no traction).
		if (mmtrMotionWalker instanceof final org.mtr.core.mmtr.consist.MmtrConsistWalker consistWalker) {
			final boolean reversed = mmtrActiveControl.getReverser() < 0;
			if (speed <= 1e-9) {
				mmtrReverserPending = false;
				applyMmtrTravelReversed(reversed);
			} else {
				mmtrReverserPending = consistWalker.travelReversed() != reversed;
			}
		}
		mmtrDriverUuid = driverUuid;
		mmtrManualOverride = true;
		// Signal S3 (AWS): a driver acknowledgement press is a one-shot intent - queue it for the
		// warning state machine and clear it from the stored control state (no repeat semantics).
		// Only a press made WHILE THE WARNING IS SHOWING counts: a press with no warning up (a driver
		// testing the key, or an operator command) must not be remembered and silently cancel the next
		// warning - that turned the 2.5 s window into a no-op (实机 2026-09-09: warning then
		// "acknowledged" on the very next tick, no SPAD).
		if (mmtrActiveControl.isAcknowledge()) {
			if (mmtrAwsState == MMTR_AWS_WARN) {
				mmtrAwsAckQueued = true;
			}
			mmtrActiveControl.setAcknowledge(false);
		}
		if (!wasOverride && driverUuid != null) {
			System.out.println("[MMTR-DRV] driver=" + driverUuid + " engaged override T" + controlState.getThrottleNotch() + " B" + controlState.getBrakeNotch() + " R" + controlState.getReverser());
		}
		mmtrControlApplySeq++;
	}

	/** Releases the MMTR explicit override (occupation lock) and neutralises the legacy HUD power. */	public void releaseMmtrManualOverride() {
		if (!mmtrManualOverride && !mmtrActive) {
			return;
		}
		final UUID releasedDriver = mmtrDriverUuid;
		mmtrActiveControl = null;
		mmtrManualOverride = false;
		mmtrDriverUuid = null;
		mmtrActive = false;
		mmtrMode = "";
		mmtrDriver = "";
		vehicleExtraData.setPowerLevel(0);
		if (releasedDriver != null) {
			System.out.println("[MMTR-DRV] driver=" + releasedDriver + " released override (auto)");
		}
	}

	/** @return true when this vehicle runs in live Motion-Core mode (L3). */
	public boolean isMmtrMotion() {
		return mmtrMotionWalker != null;
	}

	/** @return the live Motion-Core positional source when this vehicle runs in motion mode, else {@code null}. */
	@Nullable
	public MmtrMotionPosition getMmtrMotionWalker() {
		return mmtrMotionWalker;
	}

	/** @return the consist-body walker when this vehicle runs on the B-series consist model, else {@code null}. */
	@Nullable
	public MmtrConsistWalker getMmtrConsistWalker() {
		return mmtrMotionWalker instanceof final MmtrConsistWalker consistWalker ? consistWalker : null;
	}

	/**
	 * C3a: the live 调车授权 (subsidiary-aspect authority) of this train, or {@code null}. Only the
	 * server owns authorities; client mirrors always report {@code null}.
	 */
	public MmtrShuntAuthority getMmtrShuntAuthority() {
		if (isClientside || !(data instanceof final Simulator simulator)) {
			return null;
		}
		return simulator.mmtrShuntAuthorities.active(id);
	}

	/**
	 * MMTR (L3, server): arms a precise stop for the current motion run: the vehicle auto
	 * service-brakes and comes to rest with its head exactly at {@code cumulativeDistanceM} (metres
	 * from the run start, i.e. walker distance space), opens the doors when {@code openDoors}, and
	 * holds there until a fresh control is applied (see {@link #applyMmtrControl}). Pass -1 to clear
	 * (free run). Only meaningful while {@link #isMmtrMotion()}.
	 */
	public void setMmtrMotionStopTarget(double cumulativeDistanceM, boolean openDoors) {
		if (isClientside || mmtrMotionWalker == null) {
			return;
		}
		final boolean wasStoppedAtTarget = mmtrMotionStoppedAtTarget;
		mmtrMotionStopTargetM = cumulativeDistanceM;
		mmtrMotionStopOpenDoors = openDoors;
		mmtrMotionStoppedAtTarget = false;
		mmtrMotionArrivalControlSeq = -1;
		mmtrRunStopTarget = cumulativeDistanceM;
		vehicleExtraData.mmtrMarkSyncDirty();
		// Auto step-run: arming the next stop target while stopped = depart automatically (the task
		// owns the dwell time and re-arms when it is done). Manual holds still need a fresh control.
		if (mmtrMotionAuto && wasStoppedAtTarget && cumulativeDistanceM >= 0) {
			vehicleExtraData.closeDoors();
			System.out.println("[MMTR-DRV] motion auto-departing to next stop target " + Math.round(cumulativeDistanceM * 100.0) / 100.0 + "m");
		}
	}

	/** @return true when the vehicle is stopped exactly at its armed motion stop target. */
	public boolean isMmtrMotionStoppedAtTarget() {
		return mmtrMotionStoppedAtTarget;
	}

	/** @return true when the vehicle runs unmanned (auto step-run) in motion mode. */
	public boolean isMmtrMotionAuto() {
		return mmtrMotionAuto;
	}

	/**
	 * MMTR (L3, server): enables/disables the unmanned auto step-run for this motion vehicle. While
	 * enabled, arming a stop target drives the vehicle to it automatically; arming the next target
	 * while stopped departs automatically. A manual override (cab driver) always wins while active.
	 */
	public void setMmtrMotionAuto(boolean auto) {
		if (isClientside) {
			return;
		}
		mmtrMotionAuto = auto;
		System.out.println("[MMTR-DRV] motion auto run " + (auto ? "enabled" : "disabled"));
	}

	/**
	 * MMTR (L3, server-only): switches this vehicle to live Motion-Core run mode. The walker becomes
	 * the motion authority: every tick the vehicle advances it by the physically integrated distance;
	 * the walker crosses nodes by the CURRENT turnout/task state (an unset fork halts and waits, never
	 * auto), and railProgress/render/occupancy follow the walker plus its growing leg shadow. The
	 * legacy baked-path state machine is bypassed while engaged. Call with the walker seeded on the
	 * rail the consist stands on. Clientside mirrors must never engage this mode.
	 */
	public void engageMmtrMotion(@Nullable MmtrMotionWalker walker) {
		if (walker != null) {
			// ③ 车尾清岔: the walker releases a crossed point only once this consist's tail has cleared it.
			walker.setTailLengthM(vehicleExtraData.getTotalVehicleLength());
		}
		engageMmtrMotionPosition(walker);
	}

	/**
	 * B7.2a (加性): engage the consist-body motion model. The train is a {@code MmtrConsistWalker}
	 * (double-ended body + manned cab); the vehicle ticks it exactly like the legacy walker because
	 * both implement {@link org.mtr.core.mmtr.segment.MmtrMotionPosition}. The engine inserts its own
	 * <em>system key</em> so a staged consist has a leading end before any crew boards; a crew key
	 * later displaces it ({@link #enterMmtrCab(MmtrCabState.Cab, java.util.UUID)}).
	 */
	public void engageMmtrConsistMotion(MmtrConsistWalker walker, MmtrCabState.Cab cab) {
		if (walker != null && cab != MmtrCabState.Cab.NONE) {
			walker.insertSystemKey(cab, true);
		}
		engageMmtrMotionPosition(walker);
		updateMmtrCabSyncFields();
	}

	/**
	 * B7.6: the crew takes a cab (inserts the key). Legal only on the consist model and only while the
	 * consist stands — a driver cannot walk into a cab on a moving train. The caller (game side) has
	 * already checked that the player really stands at that cab and holds a driver key.
	 */
	public boolean enterMmtrCab(MmtrCabState.Cab cab) {
		return enterMmtrCab(cab, null);
	}

	/**
	 * B7.6: the crew takes a cab, identified by {@code crewUuid} ({@code null} = operator command).
	 *
	 * <p>Taking a cab releases the engine's system key — a staged consist waiting for a crew must
	 * never lock its driver out — and drops any auto run that was armed under that system key: the
	 * crew is now driving by hand (the same reason 换端 clears the armed run).</p>
	 */
	public boolean enterMmtrCab(MmtrCabState.Cab cab, @Nullable UUID crewUuid) {
		final MmtrConsistWalker consistWalker = getMmtrConsistWalker();
		if (consistWalker == null || !consistWalker.insertKey(cab, speed == 0, true, crewUuid)) {
			return false;
		}
		if (consistWalker.cabs().isCrewKey()) {
			releaseMmtrPointRequests();
			mmtrMotionPlan = null;
			mmtrMotionAuto = false;
			mmtrMotionStopTargetM = -1;
			mmtrMotionStoppedAtTarget = false;
			mmtrMotionStopOpenDoors = false;
			mmtrMotionArrivalControlSeq = -1;
			mmtrRunStopTarget = -1;
		}
		updateMmtrCabSyncFields();
		return true;
	}

	/**
	 * C6: the crew takes the cab at {@code carIndex}, facing {@code towardA}. A double-ended
	 * locomotive has a cab at EACH end of the same car, and a coupled formation can have cabs inside
	 * it, so the cab is identified by (car, facing) — {@link MmtrCabState.Cab#CAB_A}/{@code CAB_B}
	 * remain the two named ends. On a legacy (non consist-body) train only the named ends exist.
	 *
	 * @return whether the cab is now manned by the crew
	 */
	public boolean enterMmtrCabAtCar(int carIndex, boolean towardA, @Nullable UUID crewUuid) {
		final MmtrConsistWalker consistWalker = getMmtrConsistWalker();
		if (consistWalker == null) {
			return enterMmtrCab(towardA ? MmtrCabState.Cab.CAB_A : MmtrCabState.Cab.CAB_B, crewUuid);
		}
		final MmtrConsistBody body = consistWalker.body();
		if (carIndex < 0 || carIndex >= body.carCount() || speed != 0) {
			return false;
		}
		final double arcM = towardA ? body.carStartArcM(carIndex) : body.carEndArcM(carIndex);
		if (consistWalker.cabs().isManned() && !consistWalker.cabs().isSystemKey() && !consistWalker.cabs().isHeldBy(crewUuid)) {
			return false; // another crew member holds the key
		}
		if (!consistWalker.cabs().insertKeyAtArc(arcM, towardA, true, true, crewUuid)) {
			return false;
		}
		releaseMmtrPointRequests();
		mmtrMotionPlan = null;
		mmtrMotionAuto = false;
		mmtrMotionStopTargetM = -1;
		mmtrMotionStoppedAtTarget = false;
		mmtrMotionStopOpenDoors = false;
		mmtrMotionArrivalControlSeq = -1;
		mmtrRunStopTarget = -1;
		updateMmtrCabSyncFields();
		return true;
	}

	/**
	 * B7.6: the crew leaves the cab (pulls the key). Always legal — if the consist is still rolling it
	 * simply loses traction and the motion branch brakes it to a stand (§3.3).
	 */
	public boolean leaveMmtrCab() {
		return leaveMmtrCab(null);
	}

	/** @see #leaveMmtrCab() — {@code crewUuid} can only pull the key it holds ({@code null} = operator). */
	public boolean leaveMmtrCab(@Nullable UUID crewUuid) {
		final MmtrConsistWalker consistWalker = getMmtrConsistWalker();
		if (consistWalker == null || !consistWalker.removeKey(crewUuid)) {
			return false;
		}
		updateMmtrCabSyncFields();
		return true;
	}

	/** @return the manned cab of a consist-body vehicle, or {@code NONE}. */
	public MmtrCabState.Cab getMmtrActiveCab() {
		final MmtrConsistWalker consistWalker = getMmtrConsistWalker();
		return consistWalker == null ? MmtrCabState.Cab.NONE : consistWalker.cabs().activeCab();
	}

	/**
	 * S5: the live 进路 of this train. The registry is the source of truth, not the field: a coupling
	 * surgery rebuilds the Vehicle object (MmtrCoupleSurgery creates a merged Vehicle from JSON), so a
	 * route published by the pre-surgery object would otherwise be invisible to the new one - and leak
	 * (实机 2026-09-09: a SET shunt route with a COMPLETE mission).
	 */
	public org.mtr.core.mmtr.route.@Nullable MmtrRoute getMmtrRoute() {
		return data instanceof final Simulator simulator ? simulator.mmtrRoutes.route(getId()) : mmtrRoute;
	}

	/** @return who holds the key ("NONE" / "SYSTEM" / "CREW") of a consist-body vehicle. */
	public MmtrCabState.KeyHolder getMmtrCabKeyHolder() {
		final MmtrConsistWalker consistWalker = getMmtrConsistWalker();
		return consistWalker == null ? MmtrCabState.KeyHolder.NONE : consistWalker.cabs().keyHolder();
	}

	/**
	 * Whether {@code uuid} may drive this consist from the cab: a consist-body train is only
	 * controllable by the crew member whose key is actually in the cab (the engine's placeholder key
	 * drives nobody). Legacy path vehicles without a cab model keep the old riding-driver rule.
	 */
	public boolean holdsMmtrCabKey(@Nullable UUID uuid) {
		final MmtrConsistWalker consistWalker = getMmtrConsistWalker();
		return consistWalker == null || consistWalker.cabs().isHeldBy(uuid);
	}

	/** Mirrors the cab state into the synced vehicle fields so clients/ops see who holds the key. */
	private void updateMmtrCabSyncFields() {
		final MmtrCabState.Cab cab = getMmtrActiveCab();
		mmtrActiveCab = cab.name();
		mmtrCabKeyHolder = getMmtrCabKeyHolder().name();
		final MmtrConsistWalker consistWalker = getMmtrConsistWalker();
		final UUID crew = consistWalker == null ? null : consistWalker.cabs().crewUuid();
		mmtrCabCrew = crew == null ? "" : crew.toString();
		// C6: the cab's identity as a crew-facing name: car index (1-based) + end. A double-ended
		// locomotive has both cabs in one car, so "3A"/"3B" is what the HUD and the interact prompt use.
		if (consistWalker == null || !consistWalker.cabs().isManned()) {
			mmtrCabCarIndex = 0;
			mmtrCabEnd = "";
			mmtrCabArcM = 0;
		} else {
			final MmtrConsistBody body = consistWalker.body();
			final double arcM = Double.isNaN(consistWalker.cabs().cabArcM())
					? (cab == MmtrCabState.Cab.CAB_B ? body.lengthM() : 0)
					: consistWalker.cabs().cabArcM();
			mmtrCabArcM = arcM;
			// A cab sitting exactly on a car boundary belongs to the car it is the END of: the A-end cab
			// of car i is at the boundary with car i-1, the B-end cab of car i at the boundary with
			// car i+1. Nudge the probe by the facing so a double-ended loco names both cabs by car.
			final double probeArcM = cab == MmtrCabState.Cab.CAB_A ? arcM + 1e-4 : arcM - 1e-4;
			mmtrCabCarIndex = body.carIndexAtArcM(probeArcM) + 1;
			mmtrCabEnd = cab == MmtrCabState.Cab.CAB_B ? "B" : "A";
		}
		vehicleExtraData.mmtrMarkSyncDirty();
	}

	/**
	 * REV 换向器: point the consist the other way without changing the manned cab. The body does not
	 * move (I1) and the geometry is untouched (I2) — the walker just leads with its other end. Like
	 * 换端, everything the armed run holds was computed for the old direction and is dropped.
	 */
	private void applyMmtrTravelReversed(boolean reversed) {
		if (!(mmtrMotionWalker instanceof final org.mtr.core.mmtr.consist.MmtrConsistWalker consistWalker)
				|| !consistWalker.setTravelReversed(reversed)) {
			return;
		}
		releaseMmtrPointRequests();
		mmtrMotionPlan = null;
		mmtrMotionFlipDone = true;
		mmtrMotionAuto = false;
		mmtrMotionStopTargetM = -1;
		mmtrMotionStoppedAtTarget = false;
		mmtrMotionStopOpenDoors = false;
		mmtrMotionArrivalControlSeq = -1;
		mmtrRunStopTarget = -1;
		// The mirror path is oriented by the direction of travel: re-publish it (same rails, opposite
		// order, same railProgress) so the client re-renders the consist in place.
		syncMmtrConsistMirror();
		System.out.println("[MMTR-DRV] 换向器: " + (reversed ? "车尾在前（反向行驶）" : "车头在前"));
	}

	/** 换端 (change ends) for a motion vehicle: legal only at a stand, and only on the consist model
	 * (the legacy walker has no cab). The train does not move — see the B-series design invariants. */
	public boolean changeEndsMmtrMotion() {
		if (isClientside || mmtrMotionWalker == null) {
			return false;
		}
		if (!mmtrMotionWalker.changeEnds(speed == 0)) {
			return false;
		}
		// B7.5: everything the armed run holds - the en-route fork requests, the planned fork ops and
		// their distances, the flip point and the stop target - was computed for the OLD direction of
		// travel and is meaningless now. Drop it; the mission self-arm path (mmtrMissionTick) re-plans
		// from the new leading end, and a manual driver simply continues by hand.
		releaseMmtrPointRequests();
		mmtrMotionPlan = null;
		mmtrMotionFlipDone = true;
		mmtrMotionAuto = false;
		mmtrMotionStopTargetM = -1;
		mmtrMotionStoppedAtTarget = false;
		mmtrMotionStopOpenDoors = false;
		mmtrMotionArrivalControlSeq = -1;
		mmtrRunStopTarget = -1;
		// B7.2b: the mirror path is oriented by the manned cab, so re-publish it (same rails, opposite
		// order, anchored to the same railProgress) - the client re-renders the consist in place.
		syncMmtrConsistMirror();
		updateMmtrCabSyncFields();
		return true;
	}

	/**
	 * B7.2b: keep the mirrored motion payload consistent with the consist body — path order (tail →
	 * head), the {@code reversed} car-list flag and the synced VED path. No-op for the legacy walker.
	 */
	private void syncMmtrConsistMirror() {
		if (mmtrMotionWalker instanceof final MmtrConsistWalker consistWalker) {
			reversed = consistWalker.mirrorReversed();
			mmtrMotionLegCount = consistWalker.legCount();
			refreshMmtrMotionLegs();
			vehicleExtraData.mmtrMarkSyncDirty();
		}
	}

	private void engageMmtrMotionPosition(@Nullable MmtrMotionPosition walker) {
		if (isClientside) {
			log.warn("Vehicle#engageMmtrMotion is server-side only; ignoring on clientside mirror");
			return;
		}
		releaseMmtrManualOverride();
		mmtrMotionWalker = walker;
		mmtrMotionLegCount = 0;
		mmtrMotionLegs.clear();
		mmtrProtection = false;
		mmtrProtectionLockRemaining = 0;
		mmtrBlockStopM = Double.MAX_VALUE;
		mmtrBlockedWaiting = false;
		mmtrAwsState = MMTR_AWS_NONE;
		mmtrAwsWarnElapsedMillis = 0;
		mmtrAwsAckQueued = false;
		atoOverride = false;
		vehicleExtraData.closeDoors();
		departureIndex = -1;
		sidingDepartureTime = -1;
		reversed = false;
		speed = 0;
		mmtrMotionStopTargetM = -1;
		mmtrMotionStoppedAtTarget = false;
		mmtrMotionStopOpenDoors = false;
		mmtrMotionArrivalControlSeq = -1;
		mmtrMotionPlan = null;
		mmtrMotionFlipDone = true;
		if (walker == null) {
			railProgress = vehicleExtraData.getDefaultPosition();
			mmtrMotionMirror = false;
			mmtrRunTotalDistance = 0;
			mmtrRunStopTarget = -1;
		} else {
			mmtrMotionMirror = true;
			refreshMmtrMotionLegs();
			mmtrMotionLegCount = walker.legCount();
			railProgress = walker.distanceM();
			syncMmtrConsistMirror();
		}
	}

	/** Rebuild the motion leg shadow from the walker's recorded legs (cumulative PathData) and mirror it
	 * into the synced VED path / run fields so client VehicleUpdates carry the rails this vehicle runs on. */
	private void refreshMmtrMotionLegs() {
		mmtrMotionLegs.clear();
		if (mmtrMotionWalker instanceof final MmtrConsistWalker consistWalker) {
			// B7.2b: tail -> head, anchored so the leading face sits exactly at railProgress (distanceM).
			// The car-list direction has to be re-derived HERE as well: these legs are rebuilt on every
			// moving tick, and a cab change or a reverser flip changes the direction of travel without
			// going through syncMmtrConsistMirror - with a stale flag the client laid the car list on
			// the wrong end, so the whole consist looked like it had turned around (the wagon "ran to
			// the front").
			reversed = consistWalker.mirrorReversed();
			mmtrMotionLegs.addAll(consistWalker.buildMirrorLegs());
		} else if (mmtrMotionWalker != null) {
			mmtrMotionLegs.addAll(mmtrMotionWalker.buildLegs());
		}
		vehicleExtraData.mmtrSetSyncPath(mmtrMotionLegs);
		mmtrRunTotalDistance = mmtrMotionLegs.isEmpty() ? 0 : mmtrMotionLegs.get(mmtrMotionLegs.size() - 1).getEndDistance();
		// The sync-path copy filter keeps segments up to the stopping point; a live run has no baked
		// stop, so leave it far ahead so every client update carries the full leg shadow.
		vehicleExtraData.setStoppingPoint(Double.MAX_VALUE / 4);
		vehicleExtraData.mmtrMarkSyncDirty();
	}

	/** @return the uuid currently holding the MMTR explicit override, or {@code null} */
	@Nullable
	public UUID getMmtrDriverUuid() {
		return mmtrDriverUuid;
	}

	public boolean isMmtrManualOverride() {
		return mmtrManualOverride;
	}

	/** @return whether this vehicle currently holds an explicit MMTR manual override (server). */
	public boolean isMmtrOverrideActive() {
		return mmtrManualOverride;
	}

	/**
	 * Server-authoritative driver check: may {@code uuid} take/keep MMTR control right now?
	 * A {@code null} uuid keeps the legacy semantic of "some cab driver is present" so older
	 * no-identity callers (tests/tools) keep working.
	 */
	public boolean canTakeMmtrControl(@Nullable UUID uuid) {
		// T4 准入闸门：无任务不得操纵（策略开关；默认关，见 MmtrDriveAccess.taskAdmitsDriving）。
		if (!MmtrDriveAccess.taskAdmitsDriving(data instanceof final Simulator simulator && simulator.mmtrRequireTaskToDrive,
			mmtrMission != null && !mmtrMission.isTerminal())) {
			return false;
		}
		if (uuid == null) {
			final boolean[] anyDriverRiding = {false};
			vehicleExtraData.iterateRidingEntities(vehicleRidingEntity -> {
				if (vehicleRidingEntity.isDriver()) {
					anyDriverRiding[0] = true;
				}
			});
			return anyDriverRiding[0];
		}
		final boolean senderIsRidingDriver = hasMmtrDriverRiding(uuid) && holdsMmtrCabKey(uuid);
		final boolean holderStillRiding = mmtrDriverUuid == null || hasMmtrDriverRiding(mmtrDriverUuid);
		return MmtrDriveAccess.canControl(senderIsRidingDriver, mmtrManualOverride, mmtrDriverUuid, uuid, holderStillRiding);
	}

	private boolean hasMmtrDriverRiding(UUID uuid) {
		final boolean[] found = {false};
		vehicleExtraData.iterateRidingEntities(vehicleRidingEntity -> {
			if (vehicleRidingEntity.isDriver() && vehicleRidingEntity.uuid.equals(uuid)) {
				found[0] = true;
			}
		});
		return found[0];
	}

	/** True when explicit MMTR control requests traction (used to allow departing from a stop). */
	public boolean isMmtrRequestingPower() {
		return mmtrManualOverride && mmtrActiveControl != null && mmtrActiveControl.getThrottleNotch() > 0;
	}

	/**
	 * Lazily resolves the MMTR consist type + controller.
	 * <ul>
	 *   <li>Server: from the simulator's server-side policy (ConsistTypeRegistry).</li>
	 *   <li>Client: rebuilt from the mmtr parameters mirrored in the latest vehicle snapshot, so
	 *       every client simulates exactly the same longitudinal physics as the server
	 *       (identical model, authoritative ControlState, seeded air-brake state).</li>
	 * </ul>
	 * Returns true when MMTR control is active (never for DEFAULT mode).
	 */
	private boolean tryInitMmtrController() {
		if (mmtrDriveController != null) {
			return true;
		}
		if (!isClientside) {
			if (data instanceof Simulator simulator && simulator.mmtrConsistTypes != null && simulator.mmtrDefaultConsistTypeId != null) {
				mmtrConsistType = simulator.mmtrConsistTypes.get(simulator.mmtrDefaultConsistTypeId);
			}
		} else {
			mmtrConsistType = createMirrorConsistTypeFromSync();
		}
		if (mmtrConsistType != null) {
			mmtrDriveController = switch (mmtrConsistType.getControlMode()) {
				case NOTCHED -> new org.mtr.core.mmtr.NotchedDriveController();
				case STEPLESS -> new org.mtr.core.mmtr.SteplessDriveController();
				case AIR_BRAKE -> new org.mtr.core.mmtr.AirBrakeController();
				default -> null;
			};
			if (isClientside && mmtrDriveController instanceof final org.mtr.core.mmtr.AirBrakeController airBrakeController) {
				// Seed the fresh mirror controller with the authoritative air state from the snapshot.
				airBrakeController.setState(mmtrPipePressure, mmtrBrakeCylinderPressure);
			}
		}
		return mmtrDriveController != null;
	}

	/** Client-side: rebuild the ConsistType mirrored in the latest vehicle snapshot. */
	private @Nullable ConsistType createMirrorConsistTypeFromSync() {
		if (mmtrMode == null || mmtrMode.isEmpty()) {
			return null;
		}
		final ConsistType.ControlMode mode;
		try {
			mode = ConsistType.ControlMode.valueOf(mmtrMode);
		} catch (IllegalArgumentException e) {
			return null;
		}
		if (mode == ConsistType.ControlMode.DEFAULT) {
			return null;
		}
		return new ConsistType(
			"mirror", "", mode,
			(int) mmtrPowerNotches, (int) mmtrBrakeNotches,
			mmtrMaxSpeedKmh, mmtrTractionAccelerationMps2, mmtrServiceBrakeDecelerationMps2,
			mmtrEmergencyDecelerationMps2, mmtrTractionBreakpointKmh, mmtrResistanceA, mmtrResistanceB, mmtrResistanceC,
			mmtrAirPipeChargeRatePerSecond, mmtrAirPipeDischargeRatePerSecond,
			mmtrAirBrakeApplyRatePerSecond, mmtrAirBrakeReleaseRatePerSecond, mmtrManualMaxSpeedKmh,
			mmtrMassRatio
		);
	}

	/** Client-side: rebuild the authoritative ControlState from the mirrored snapshot fields. */
	private ControlState createMirrorControlStateFromSync() {
		return new ControlState()
			.setThrottleNotch((int) mmtrThrottleNotch).setBrakeNotch((int) mmtrBrakeNotch).setReverser((int) mmtrReverser)
			.setThrottleAxis(mmtrThrottleAxis).setBrakeAxis(mmtrBrakeAxis).setEmergency(mmtrEmergency);
	}

	/**
	 * Returns (and lazily builds) the per-car composition used by the AIR_BRAKE model: one unit per
	 * vehicle car, each carrying its own powered flag and its own ConsistType (C2). A hauled wagon
	 * therefore contributes mass and brake-pipe volume but no traction.
	 */
	@Nullable
	private MmtrComposition getMmtrComposition() {
		if (mmtrComposition == null && mmtrConsistType != null) {
			final ConsistTypeRegistry registry = data instanceof final Simulator simulator ? simulator.mmtrConsistTypes : null;
			mmtrComposition = MmtrComposition.fromVehicleCars(vehicleExtraData.immutableVehicleCars, registry, mmtrConsistType);
		}
		return mmtrComposition;
	}

	/**
	 * C4b: the rail interval this vehicle currently stands on (rail hex + rail-local start/end of its
	 * body), or {@code null} when the geometry is not known (legacy baked stock). Used by the yard to
	 * tell two trains stabled at different positions of one rail (legal, uncoupling produces it) from
	 * overlapping duplicates (culled).
	 */
	public record RailSpan(String railHex, double startM, double endM) {
	}

	public @Nullable RailSpan mmtrRailSpan() {
		if (mmtrMotionWalker == null) {
			return null;
		}
		// C5b: an unmanned consist body still occupies its rail - reference the body, not the cab.
		final Rail rail;
		final double leadingOffset;
		final boolean towardExit;
		if (mmtrMotionWalker instanceof final MmtrConsistWalker consistWalker) {
			rail = consistWalker.referenceRail();
			leadingOffset = consistWalker.referenceOffsetM();
			towardExit = consistWalker.travelsTowardB();
		} else {
			rail = mmtrMotionWalker.currentRail();
			leadingOffset = mmtrMotionWalker.offsetM();
			towardExit = true;
		}
		if (rail == null) {
			return null;
		}
		final double railLength = rail.railMath.getLength();
		final double clampedLeadingOffset = Math.min(railLength, Math.max(0, leadingOffset));
		// C5b: the body extends BEHIND the leading face along the direction of travel — toward larger
		// rail offsets when the consist drives toward its B end, toward smaller ones otherwise.
		final double otherEndOffset = towardExit ? clampedLeadingOffset - vehicleExtraData.getTotalVehicleLength() : clampedLeadingOffset + vehicleExtraData.getTotalVehicleLength();
		return new RailSpan(rail.getHexId(), Math.max(0, Math.min(clampedLeadingOffset, otherEndOffset)), Math.min(railLength, Math.max(clampedLeadingOffset, otherEndOffset)));
	}

	/**
	 * Server-side: seed this vehicle's per-car air state from a string produced by
	 * {@link MmtrComposition#encodeAirStates} (used by the coupling surgery, which splits the pipe
	 * state of the two halves).
	 */
	public void mmtrApplyAirStateString(String airState) {
		final MmtrComposition composition = getMmtrComposition();
		if (composition != null && airState != null && !airState.isEmpty()) {
			composition.applyAirStateString(airState);
			mmtrAirState = MmtrComposition.encodeAirStates(composition);
		}
	}

	/** C4b: the current per-unit air state string ({@code pipe,cyl;...}); empty when no composition. */
	public String mmtrAirStateSnapshot() {
		final MmtrComposition composition = getMmtrComposition();
		return composition == null ? (mmtrAirState == null ? "" : mmtrAirState) : MmtrComposition.encodeAirStates(composition);
	}

	/**
	 * C4: after a coupling merge, the newly attached cars' air pipe starts EMPTY (the rake was not
	 * connected to a running compressor): unpowered added units are seeded to pipe 0 / cylinder 0 and
	 * charge up at the consist type's {@code airPipeChargeRatePerSecond} on the following ticks. A
	 * powered added unit brings its own compressor, so it keeps a charged pipe.
	 *
	 * @param firstAddedUnitIndex index of the first unit that came from the coupled-on train
	 */
	public void mmtrSeedAirStateAfterCoupling(int firstAddedUnitIndex) {
		final MmtrComposition composition = getMmtrComposition();
		if (composition == null) {
			return;
		}
		for (int i = Math.max(0, firstAddedUnitIndex); i < composition.size(); i++) {
			if (!composition.unit(i).isPowered()) {
				composition.unit(i).setAirState(0, 0);
			}
		}
		mmtrAirState = MmtrComposition.encodeAirStates(composition);
	}

	/**
	 * Server-side: writes the current MMTR drive state + consist parameters into the synced
	 * vehicle fields so clients can mirror the physics and show the authoritative state.
	 */
	private void updateMmtrSyncFields() {
		mmtrActive = mmtrManualOverride && mmtrConsistType != null;
		mmtrMode = mmtrConsistType == null ? "" : mmtrConsistType.getControlMode().name();
		mmtrDriver = mmtrDriverUuid == null ? "" : mmtrDriverUuid.toString();
		// Cab/key ownership (who may drive) is mirrored every time the drive state is published; the
		// enter/leave/change-ends paths mark the vehicle dirty themselves, so no extra update is sent.
		final MmtrConsistWalker cabWalker = getMmtrConsistWalker();
		mmtrActiveCab = getMmtrActiveCab().name();
		mmtrCabKeyHolder = getMmtrCabKeyHolder().name();
		mmtrCabCrew = cabWalker == null || cabWalker.cabs().crewUuid() == null ? "" : cabWalker.cabs().crewUuid().toString();
		// C3a: the subsidiary-aspect authority the driver's display shows (main head stays red).
		final org.mtr.core.mmtr.signal.MmtrShuntAuthority shunt = getMmtrShuntAuthority();
		final String shuntKind = shunt == null ? "" : shunt.getKind().name();
		if (!shuntKind.equals(mmtrShuntAuthority)) {
			vehicleExtraData.mmtrMarkSyncDirty(); // a granted/expired authority must reach the client HUD
		}
		mmtrShuntAuthority = shuntKind;
		mmtrShuntSpeedLimitKmh = shunt == null ? 0 : shunt.getSpeedLimitKmh();
		mmtrShuntRemainingS = shunt == null ? 0 : Math.round(shunt.remainingMillis(data.getCurrentMillis()) / 1000.0);
		if (mmtrActiveControl != null) {
			mmtrThrottleNotch = mmtrActiveControl.getThrottleNotch();
			mmtrBrakeNotch = mmtrActiveControl.getBrakeNotch();
			mmtrReverser = mmtrActiveControl.getReverser();
			mmtrThrottleAxis = mmtrActiveControl.getThrottleAxis();
			mmtrBrakeAxis = mmtrActiveControl.getBrakeAxis();
			mmtrEmergency = mmtrActiveControl.isEmergency();
		}
		if (mmtrConsistType != null) {
			mmtrPowerNotches = mmtrConsistType.getPowerNotches();
			mmtrBrakeNotches = mmtrConsistType.getBrakeNotches();
			mmtrMaxSpeedKmh = mmtrConsistType.getMaxSpeedKmh();
			mmtrManualMaxSpeedKmh = mmtrConsistType.getManualMaxSpeedMetersPerSecond() * 3.6;
			mmtrTractionAccelerationMps2 = mmtrConsistType.getTractionAccelerationMps2();
			mmtrServiceBrakeDecelerationMps2 = mmtrConsistType.getServiceBrakeDecelerationMps2();
			mmtrEmergencyDecelerationMps2 = mmtrConsistType.getEmergencyDecelerationMps2();
			mmtrTractionBreakpointKmh = mmtrConsistType.getTractionBreakpointKmh();
			mmtrResistanceA = mmtrConsistType.getResistanceA();
			mmtrResistanceB = mmtrConsistType.getResistanceB();
			mmtrResistanceC = mmtrConsistType.getResistanceC();
			mmtrAirPipeChargeRatePerSecond = mmtrConsistType.getAirPipeChargeRatePerSecond();
			mmtrAirPipeDischargeRatePerSecond = mmtrConsistType.getAirPipeDischargeRatePerSecond();
			mmtrAirBrakeApplyRatePerSecond = mmtrConsistType.getAirBrakeApplyRatePerSecond();
			mmtrAirBrakeReleaseRatePerSecond = mmtrConsistType.getAirBrakeReleaseRatePerSecond();
			mmtrMassRatio = mmtrConsistType.getMassRatio();
		}
		if (mmtrDriveController instanceof final org.mtr.core.mmtr.AirBrakeController airBrakeController) {
			mmtrPipePressure = airBrakeController.getPipePressure();
			mmtrBrakeCylinderPressure = airBrakeController.getBrakeCylinderPressure();
		}
		// Signal display fields (HUD): AWS warning state, occupancy hold and the current per-rail
		// directional speed limit follow the live internal state into the mirror payload.
		mmtrAwsWarningPending = mmtrAwsState == MMTR_AWS_WARN;
		mmtrAwsWarningAcknowledged = mmtrAwsState == MMTR_AWS_ACKED;
		mmtrBlockHeld = mmtrBlockedWaiting;
		mmtrSpeedLimitKmh = Math.round(mmtrCurrentRailLimitPerMs() * 3600.0);
		// Signal S4 (LZB) cab display: supervision band flag, enforced ceiling, cab target speed
		// (0 = stop target ahead) and distance to that target, mirrored from the same values the
		// driving supervision enforces against - the client HUD reads exactly what the train obeys.
		mmtrLzbSupervising = getMmtrLzbCeilingKmh() > 0;
		mmtrLzbCeilingKmh = getMmtrLzbCeilingKmh();
		mmtrLzbTargetKmh = getMmtrLzbTargetKmh();
		mmtrLzbTargetDistanceM = getMmtrLzbTargetDistanceM();
	}

	/** Server-side: (re)evaluate overrun/SPAD protection ahead of the {@code stoppingPoint}. */
	private boolean evaluateMmtrProtection(double stoppingPoint) {
		if (mmtrProtection) {
			return true;
		}
		// C3a: under a 调车授权 the protection layer must not judge a SPAD/overrun - the movement is
		// authorised to close up to the train standing in the occupied section, so "past the stopping
		// point" is exactly what the signal permitted. (An emergency already engaged before the grant
		// keeps its own lock countdown; only NEW judgements are suspended.)
		final org.mtr.core.mmtr.signal.MmtrShuntAuthority authority = getMmtrShuntAuthority();
		if (authority != null && !authority.enforcesProtection()) {
			return false;
		}
		// Same emergency envelope as the legacy path (Siding.MAX_ACCELERATION * 2, m/ms^2).
		if (MmtrProtection.requiresProtection(speed, stoppingPoint - railProgress, Siding.MAX_ACCELERATION * 2)) {
			mmtrProtection = true;
			mmtrProtectionLockRemaining = MmtrProtection.LOCK_MILLIS;
			System.out.println("[MMTR-DRV] overrun protection engaged (past stopping point or cannot stop in time)");
			return true;
		}
		return false;
	}

	/** Public getters for the client HUD / mirror overlay (values from the last snapshot). */
	public boolean isMmtrActiveFromSync() { return mmtrActive; }
	public String getMmtrModeFromSync() { return mmtrMode == null ? "" : mmtrMode; }
	public String getMmtrDriverFromSync() { return mmtrDriver == null ? "" : mmtrDriver; }
	public int getMmtrThrottleFromSync() { return (int) mmtrThrottleNotch; }
	public int getMmtrBrakeFromSync() { return (int) mmtrBrakeNotch; }
	public int getMmtrReverserFromSync() { return (int) mmtrReverser; }
	public boolean isMmtrProtectionFromSync() { return mmtrProtection; }
	public boolean isMmtrEmergencyFromSync() { return mmtrEmergency; }
	/** Signal S3 (AWS): an unacknowledged point warning is mirrored (client HUD). */
	public boolean isMmtrAwsWarningPendingFromSync() { return mmtrAwsWarningPending; }
	/** Signal S3 (AWS): the acknowledged-warning indicator state is mirrored (client HUD). */
	public boolean isMmtrAwsWarningAcknowledgedFromSync() { return mmtrAwsWarningAcknowledged; }
	/** Signal S1: the vehicle is parked at an occupancy block stop (client HUD). */
	public boolean isMmtrBlockHeldFromSync() { return mmtrBlockHeld; }
	/** Signal S2: current per-rail directional speed limit in km/h, mirrored (client HUD); 0 = legacy run. */
	public long getMmtrSpeedLimitKmhFromSync() { return mmtrSpeedLimitKmh; }
	/** Signal S4 (LZB): whether continuous LZB supervision is live on this train (mirrored). */
	public boolean isMmtrLzbSupervisingFromSync() { return mmtrLzbSupervising; }
	/** Signal S4 (LZB): the enforced speed ceiling in km/h (mirrored); 0 = no supervision. */
	public long getMmtrLzbCeilingKmhFromSync() { return mmtrLzbCeilingKmh; }
	/** Signal S4 (LZB): cab target speed in km/h (mirrored); 0 = stop at the target ahead. */
	public long getMmtrLzbTargetKmhFromSync() { return mmtrLzbTargetKmh; }
	/** Signal S4 (LZB): distance to the cab target in metres (mirrored); -1 = no bounded target. */
	public double getMmtrLzbTargetDistanceMFromSync() { return mmtrLzbTargetDistanceM; }
	/** 钥匙归属: the manned cab from the last snapshot ("NONE" / "CAB_A" / "CAB_B"). */
	public String getMmtrActiveCabFromSync() { return mmtrActiveCab == null ? "" : mmtrActiveCab; }
	/** 钥匙归属: who holds the key ("NONE" / "SYSTEM" / "CREW") from the last snapshot. */
	public String getMmtrCabKeyHolderFromSync() { return mmtrCabKeyHolder == null ? "" : mmtrCabKeyHolder; }
	/** 钥匙归属: the crew member whose key is in the cab (empty for a system key), from the snapshot. */
	public String getMmtrCabCrewFromSync() { return mmtrCabCrew == null ? "" : mmtrCabCrew; }
	/** C6: 1-based car index of the manned cab from the snapshot (0 = no cab). */
	public int getMmtrCabCarIndexFromSync() { return (int) mmtrCabCarIndex; }
	/** C6: the manned cab's end from the snapshot ("A" / "B" / ""). */
	public String getMmtrCabEndFromSync() { return mmtrCabEnd == null ? "" : mmtrCabEnd; }
	/** C6: arc of the manned cab from the formation's A end (mirrored). */
	public double getMmtrCabArcMFromSync() { return mmtrCabArcM; }
	/** C6: the crew-facing cab name ("3A", "8B", …) or "" when unmanned. */
	public String getMmtrCabNameFromSync() {
		return getMmtrCabCarIndexFromSync() == 0 || getMmtrCabEndFromSync().isEmpty() ? "" : getMmtrCabCarIndexFromSync() + getMmtrCabEndFromSync();
	}
	/** C3a: the subsidiary-aspect authority from the last snapshot ("" / "SUBSIDIARY_SHUNT" / "CALLING_ON"). */
	public String getMmtrShuntAuthorityFromSync() { return mmtrShuntAuthority == null ? "" : mmtrShuntAuthority; }
	/** C3a: the authorised movement's speed limit in km/h (mirrored); 0 = no authority. */
	public double getMmtrShuntSpeedLimitKmhFromSync() { return mmtrShuntSpeedLimitKmh; }
	/** C3a: seconds left on the authority (mirrored); 0 = no authority. */
	public double getMmtrShuntRemainingSFromSync() { return mmtrShuntRemainingS; }

	private void simulateMoving(long millisElapsed, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions, int currentIndex) {
		// Tracks the distance
		final double stoppingPoint;
		// Tracks the speed
		final double speedTarget;
		// Tracks the acceleration
		final int powerLevel;

		if (isClientside) {
			stoppingPoint = vehicleExtraData.getStoppingPoint();
			speedTarget = vehicleExtraData.getSpeedTarget();
			powerLevel = vehicleExtraData.getPowerLevel();
		} else {
			lastMovementMillis = data.getCurrentMillis();
			final double safeStoppingDistance = 0.5 * speed * speed / vehicleExtraData.getDeceleration() * (isCurrentlyManual() ? POWER_LEVEL_RATIO : 1); // when on manual mode, check for blocked rails on the lowest deceleration (B1)
			final double hardStoppingDistance = 0.5 * speed * speed / (Siding.MAX_ACCELERATION * 2);

			// Set the stopping point
			if (transportMode.continuousMovement) {
				stoppingPoint = Double.MAX_VALUE;
				if (vehicleExtraData.immutablePath.get(currentIndex).getDwellTime() > 0) {
					vehicleExtraData.openDoors();
				} else {
					vehicleExtraData.closeDoors();
				}
			} else {
				final double pathStoppingPoint = getPathStoppingPoint();
				if (stoppingCooldown > 0) {
					stoppingPoint = Math.min(vehicleExtraData.getStoppingPoint(), pathStoppingPoint);
				} else {
					final double railBlockedDistance = railBlockedDistance(currentIndex, railProgress, safeStoppingDistance, vehiclePositions, true, false);
					if (railBlockedDistance < 0) {
						stoppingPoint = pathStoppingPoint;
					} else {
						// Set the stopping point to the blocked position
						stoppingPoint = Math.min(railBlockedDistance + railProgress, pathStoppingPoint);
						stoppingCooldown = 1000;
					}
				}
			}

			// Set the power level and speed target
			if (stoppingPoint - railProgress < (isCurrentlyManual() ? hardStoppingDistance : safeStoppingDistance)) {
				// If blocked ahead, slow down (using normal deceleration for automatic and emergency brake for manual)
				speedTarget = -1;
				powerLevel = Math.min(vehicleExtraData.getPowerLevel(), isCurrentlyManual() && hardStoppingDistance > 2 ? -MAX_POWER_LEVEL - 1 : -POWER_LEVEL_RATIO);
				atoOverride = true;
			} else {
				if (isCurrentlyManual()) {
					if (speed > vehicleExtraData.getMaxManualSpeed()) {
						// Slow down if above the max manual speed
						speedTarget = vehicleExtraData.getMaxManualSpeed();
						powerLevel = -POWER_LEVEL_RATIO;
					} else {
						powerLevel = vehicleExtraData.getPowerLevel();
						speedTarget = powerLevel > 0 ? vehicleExtraData.getMaxManualSpeed() : (powerLevel < 0 ? 0 : speed);
					}
				} else {
					final double upcomingSlowerSpeed = Siding.getUpcomingSlowerSpeed(vehicleExtraData.immutablePath, currentIndex, railProgress, speed, vehicleExtraData.getDeceleration());
					if (upcomingSlowerSpeed >= 0 && upcomingSlowerSpeed < speed) {
						speedTarget = upcomingSlowerSpeed * deviationSpeedAdjustment;
						powerLevel = -POWER_LEVEL_RATIO;
					} else {
						speedTarget = vehicleExtraData.immutablePath.get(currentIndex).getSpeedLimitMetersPerMillisecond() * deviationSpeedAdjustment;
						powerLevel = Double.compare(speedTarget, speed) * POWER_LEVEL_RATIO;
					}
				}
			}

			// Sync to the client
			vehicleExtraData.setStoppingPoint(stoppingPoint);
			vehicleExtraData.setSpeedTarget(speedTarget);
			vehicleExtraData.setPowerLevel(powerLevel);
		}

		// Distance covered inside the MMTR sub-stepped integration (set when the mmtr branch runs).
		double mmtrDistanceTravelled = -1;

		// Set speed
		if (speedTarget < 0) {
			final double stoppingDistance = stoppingPoint - railProgress;
			speed = stoppingDistance <= 0 ? Siding.ACCELERATION_DEFAULT : Math.max(speed - (0.5 * speed * speed / stoppingDistance) * millisElapsed, Siding.ACCELERATION_DEFAULT);
		} else if (tryInitMmtrController() && mmtrConsistType != null && mmtrDriveController != null && (!isClientside ? mmtrManualOverride && isCurrentlyManual() : mmtrActive)) {
			// MMTR explicit control model: drive from the separated ControlState sent by the input
			// layer (throttle notch 0..N, brake notch, axes). No legacy single-handle mapping.
			// Mirrored client-side too (same controller, same authoritative ControlState + seeded
			// air-brake state from the snapshot) so every client simulates identical physics.
			final boolean mmtrProtectionNow;
			final ControlState mmtrControl;
			if (!isClientside) {
				mmtrProtectionNow = evaluateMmtrProtection(stoppingPoint);
				mmtrControl = mmtrActiveControl == null ? new ControlState() : mmtrActiveControl;
			} else {
				mmtrProtectionNow = mmtrProtection;
				mmtrControl = createMirrorControlStateFromSync();
			}
			// Fixed sub-step integration shared verbatim by the server and mirrored clients, so
			// the physics stay identical (and stiff dynamics stable) regardless of dt. During
			// overrun/SPAD protection the provider always requests emergency braking.
			final boolean mmtrProtectionActive = mmtrProtectionNow;
			final ConsistType mmtrType = mmtrConsistType;
			final ControlState mmtrState = mmtrControl;
			// AIR_BRAKE driving uses the per-car composition (one pipe/cylinder per car, train-pipe
			// equalisation along the consist). Mirrored clients seed their composition from the
			// synced mmtrAirState whenever a fresh snapshot arrives.
			final boolean useCompositionAir = mmtrType.getControlMode() == ConsistType.ControlMode.AIR_BRAKE;
			final MmtrComposition mmtrCompositionNow = useCompositionAir ? getMmtrComposition() : null;
			if (isClientside && mmtrCompositionNow != null && !mmtrAirState.isEmpty() && !mmtrLastAirSeed.equals(mmtrAirState)) {
				mmtrCompositionNow.applyAirStateString(mmtrAirState);
				mmtrLastAirSeed = mmtrAirState;
			}
			final double mmtrStartSpeedSi = MmtrSupport.internalSpeedToSi(speed);
			final MmtrComposition compForIntegration = mmtrCompositionNow;
			final ConsistDynamics.SpeedDistance mmtrResult = ConsistDynamics.advance(mmtrStartSpeedSi, mmtrType, millisElapsed, MMTR_INTEGRATION_SUB_STEP_MS, (siSpeed, stepMillis) -> {
				if (mmtrProtectionActive && siSpeed > 0) {
					return new DriveOutput(-mmtrType.getEmergencyDecelerationMps2(), true, true, 0, 1);
				}
				if (compForIntegration != null) {
					return compForIntegration.stepAir(mmtrState, siSpeed, stepMillis);
				}
				return mmtrDriveController.compute(mmtrState, mmtrType, siSpeed, stepMillis);
			});
			final double mmtrSpeed = MmtrSupport.siSpeedToInternal(mmtrResult.speedMetersPerSecond);
			mmtrDistanceTravelled = mmtrResult.distanceMeters;
			if (compForIntegration != null) {
				// Publish per-car air state (mirror seed) + averages for the legacy HUD fields.
				mmtrAirState = MmtrComposition.encodeAirStates(compForIntegration);
				mmtrPipePressure = compForIntegration.averagePipePressure();
				mmtrBrakeCylinderPressure = compForIntegration.averageCylinderPressure();
			}
			if (!isClientside) {
				// Keep the legacy HUD in sync: show throttle positive, brake negative, coast at zero
				// (emergency during protection). Also refresh the mirrored snapshot fields.
				if (mmtrProtectionNow) {
					vehicleExtraData.setPowerLevel(-MAX_POWER_LEVEL - 1);
				} else {
					vehicleExtraData.setPowerLevel(mmtrControl.getThrottleNotch() > 0 ? mmtrControl.getThrottleNotch()
						: mmtrControl.getBrakeNotch() > 0 ? -mmtrControl.getBrakeNotch() : 0);
				}
				updateMmtrSyncFields();
				if (speed != mmtrSpeed || mmtrDistanceTravelled > 0) {
					org.mtr.core.mmtr.MmtrTrace.log("[MMTR-DRV] mode=" + mmtrConsistType.getControlMode() + " throttle=" + mmtrControl.getThrottleNotch() + " brake=" + mmtrControl.getBrakeNotch() + " speed=" + speed + "->" + mmtrSpeed + " dist=" + mmtrResult.distanceMeters + " prot=" + mmtrProtectionNow);
				}
			}
			speed = mmtrSpeed;
		} else {
			if (powerLevel > 0) {
				speed = Math.min(speed + vehicleExtraData.getAcceleration() * powerLevel / POWER_LEVEL_RATIO * millisElapsed, speedTarget);
			} else if (powerLevel < 0) {
				speed = Math.max(speed + (powerLevel < -MAX_POWER_LEVEL ? -Siding.MAX_ACCELERATION * 2 : vehicleExtraData.getDeceleration() * powerLevel / POWER_LEVEL_RATIO) * millisElapsed, speedTarget);
			} else {
				speed = speedTarget;
			}
		}

		// Set rail progress (mmtr branch carries its own sub-stepped trapezoidal distance)
		railProgress += mmtrDistanceTravelled >= 0 ? mmtrDistanceTravelled : speed * millisElapsed;
		if (railProgress >= stoppingPoint) {
			railProgress = stoppingPoint;
			speed = 0;
			vehicleExtraData.setSpeedTarget(0);
			updateDeviation();
			if (!isClientside) {
				atoOverride = false;
				vehicleExtraData.setPowerLevel(Math.min(vehicleExtraData.getPowerLevel(), -1));
			}
		} else if (vehicleExtraData.getRepeatIndex2() > 0 && railProgress >= vehicleExtraData.getTotalDistance()) {
			railProgress = vehicleExtraData.immutablePath.get(vehicleExtraData.getRepeatIndex1()).getStartDistance() + railProgress - vehicleExtraData.getTotalDistance();
		}
	}

	/**
	 * Gets the stopping point of the path (a platform, a turnback, or the end of the route).
	 *
	 * @return the position (not the index) of the stop
	 */
	private double getPathStoppingPoint() {
		final double stoppingPointByStoppingIndex;
		final PathData pathDataAto = Utilities.getElement(vehicleExtraData.immutablePath, (int) nextStoppingIndexAto);
		final boolean pastAtoStoppingPoint = pathDataAto != null && railProgress > pathDataAto.getEndDistance();
		final int nextStoppingIndex = (int) (isCurrentlyManual() || pastAtoStoppingPoint ? nextStoppingIndexManual : nextStoppingIndexAto);

		if (nextStoppingIndex >= vehicleExtraData.immutablePath.size() - 1) {
			// Set the stopping point to the end of the whole journey
			stoppingPointByStoppingIndex = vehicleExtraData.getTotalDistance() - (vehicleExtraData.getRepeatIndex2() > 0 ? 0 : (vehicleExtraData.getRailLength() - vehicleExtraData.getTotalVehicleLength()) / 2);
		} else {
			// Set the stopping point to the next expected platform or turnback
			stoppingPointByStoppingIndex = vehicleExtraData.immutablePath.get(nextStoppingIndex).getEndDistance();
		}

		if (pastAtoStoppingPoint) {
			setNextStoppingIndex();
		}

		return stoppingPointByStoppingIndex;
	}

	// public isCurrentlyManual() defined above; kept private once no longer used? remove entirely


	private void setNextStoppingIndex() {
		nextStoppingIndexAto = vehicleExtraData.immutablePath.size() - 1;
		nextStoppingIndexManual = nextStoppingIndexAto;
		vehicleExtraData.setStoppingPoint(vehicleExtraData.getTotalDistance());
		for (int i = Utilities.getIndexFromConditionalList(vehicleExtraData.immutablePath, railProgress); i < vehicleExtraData.immutablePath.size(); i++) {
			final PathData pathData = vehicleExtraData.immutablePath.get(i);
			if (pathData.getDwellTime() > 0) {
				if (vehicleExtraData.getIsManualAllowed()) {
					nextStoppingIndexAto = Math.min(nextStoppingIndexAto, i);
					// Find the next turnback
					if (i < vehicleExtraData.immutablePath.size() - 1 && vehicleExtraData.immutablePath.get(i + 1).isOppositeRail(pathData)) {
						nextStoppingIndexManual = i;
						vehicleExtraData.setStoppingPoint(pathData.getEndDistance());
						break;
					}
				} else {
					nextStoppingIndexAto = i;
					nextStoppingIndexManual = i;
					vehicleExtraData.setStoppingPoint(pathData.getEndDistance());
					break;
				}
			}
		}
	}

	/**
	 * Motion-mode occupancy footprint over the walker's growing leg shadow (same blocked-bounds
	 * bookkeeping as {@link #writeVehiclePositions}, minus signal reservations and client pushes,
	 * which motion mode does not handle yet). Other vehicles block on this footprint through the
	 * shared vehiclePositions maps.
	 */
	private void writeMmtrMotionVehiclePositions(Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> vehiclePositions) {
		if (mmtrMotionLegs.isEmpty()) {
			return;
		}
		// C3b: parked (stabled) stock registers its footprint too. The old `getIsOnRoute()` guard hid
		// yard stock from the occupancy tree, so a coupling movement drawing up to a rake - or any
		// other train entering the siding - saw an empty section and could drive straight through it.
		if (vehiclePositions != null) {
			if (mmtrMotionWalker instanceof final MmtrConsistWalker consistWalker) {
				// C5: a consist body writes its footprint from the BODY, in each rail's own coordinate.
				// The leg shadow is anchored at the walker's start (tail-anchored for a consist body),
				// so writing it here put the segment in a different distance space than every reader
				// uses - which is why an authorised loco closed up 2.5 m short instead of 0.3 m.
				writeMmtrConsistBodyOccupancy(consistWalker, vehiclePositions);
			} else {
				int index = indexInMmtrMotionLegs(railProgress);
				while (index >= 0) {
					final PathData pathData = mmtrMotionLegs.get(index);
					if (railProgress - vehicleExtraData.getTotalVehicleLength() > pathData.getEndDistance()) {
						break;
					}
					// C3b: every leg the body actually reaches is written. The legacy "skip the first leg"
					// guard hid a stabled train's whole footprint (its body sits on leg 0 and nowhere
					// else), which is exactly the state a coupling movement has to see.
					final DoubleDoubleImmutablePair blockedBounds = getBlockedBounds(pathData, railProgress - vehicleExtraData.getTotalVehicleLength(), railProgress - 0.01);
					if (blockedBounds.rightDouble() - blockedBounds.leftDouble() > 0.01) {
						final Position position1 = pathData.getOrderedPosition1();
						final Position position2 = pathData.getOrderedPosition2();
						Data.put(vehiclePositions, position1, position2, vehiclePosition -> {
							final VehiclePosition newVehiclePosition = vehiclePosition == null ? new VehiclePosition() : vehiclePosition;
							newVehiclePosition.addSegment(blockedBounds.leftDouble(), blockedBounds.rightDouble(), id);
							return newVehiclePosition;
						}, Object2ObjectAVLTreeMap::new);
					}
					index--;
				}
				writeMmtrStandingFootprint(vehiclePositions);
			}
		}

		// MMTR (L3): push this motion vehicle to nearby clients on EVERY tick like the legacy path
		// writer. A throttled cadence is not enough: other response cycles (signals etc.) fire
		// between pushes, and a response that misses this vehicle's keep/update removes the client
		// mirror (visible as create -> remove oscillation). Payloads only go out when dirty / new;
		// the per-tick call keeps the vehicle in the keep set continuously.
		if (siding != null && siding.area != null && data instanceof final Simulator simulator && !simulator.clients.isEmpty()) {
			final boolean needsUpdate = vehicleExtraData.checkForUpdate();
			final long now = data.getCurrentMillis();
			final boolean logPush = needsUpdate || now - mmtrMotionLastClientPushMillis >= 1000;
			final @Nullable Position[] minMaxPositions = {null, null};
			int index = indexInMmtrMotionLegs(railProgress);
			while (index >= 0) {
				final PathData pathData = mmtrMotionLegs.get(index);
				final Position position1 = pathData.getOrderedPosition1();
				final Position position2 = pathData.getOrderedPosition2();
				minMaxPositions[0] = Position.getMin(minMaxPositions[0], Position.getMin(position1, position2));
				minMaxPositions[1] = Position.getMax(minMaxPositions[1], Position.getMax(position1, position2));
				if (railProgress - vehicleExtraData.getTotalVehicleLength() > pathData.getEndDistance()) {
					break;
				}
				index--;
			}
			simulator.clients.forEach(client -> {
				final Position clientPosition = client.getPosition();
				final double updateRadius = client.getUpdateRadius();
				if ((minMaxPositions[0] == null || minMaxPositions[1] == null) ? siding.area.inArea(clientPosition, updateRadius) : Utilities.isBetween(clientPosition, minMaxPositions[0], minMaxPositions[1], updateRadius) || !closeToDepot() && vehicleExtraData.hasRidingEntity(client.uuid)) {
					client.update(this, needsUpdate, 0);
					if (logPush) {
						org.mtr.core.mmtr.MmtrTrace.log("[MMTR-SYNC] push vehicle " + id + " dirty=" + needsUpdate + " client=" + client.uuid + " progress=" + Math.round(railProgress) + " legs=" + mmtrMotionLegs.size() + " radius=" + updateRadius + " pos=" + clientPosition.getX() + "," + clientPosition.getZ());
					}
				}
			});
			if (logPush) {
				mmtrMotionLastClientPushMillis = now;
			}
		}
	}

	/**
	 * C3b: register the body interval of a train that stands on a rail but whose leg shadow does not
	 * reach behind it. The leg shadow starts where the walker was placed, so a train parked at the
	 * yard (walker distance 0) has its whole body <em>before</em> the shadow and the loop above writes
	 * nothing - the stabled rake would be invisible and an authorised coupling movement would drive
	 * through it. The interval is written on the rail the leading end stands on, in the same
	 * ordered-position distance space every other writer and reader uses.
	 */
	private void writeMmtrStandingFootprint(Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> vehiclePositions) {
		final double bodyLength = vehicleExtraData.getTotalVehicleLength();
		// Only when the tail is outside the leg shadow; otherwise the loop above already covered it.
		if (mmtrMotionWalker == null || railProgress - bodyLength >= 0) {
			return;
		}
		final Rail rail = mmtrMotionWalker.currentRail();
		final Position entry = mmtrMotionWalker.enteredFromPosition();
		if (rail == null || entry == null) {
			return;
		}
		final double railLength = rail.railMath.getLength();
		final double headOffset = Math.min(railLength, Math.max(0, mmtrMotionWalker.offsetM()));
		final double tailOffset = Math.max(0, headOffset - bodyLength);
		final Position position1 = rail.getPosition1();
		final Position position2 = rail.getPosition2();
		final Position orderedPosition1 = position1.compareTo(position2) <= 0 ? position1 : position2;
		final Position orderedPosition2 = orderedPosition1 == position1 ? position2 : position1;
		final boolean fromOrderedPosition1 = orderedPosition1.equals(entry);
		final double startDistance = fromOrderedPosition1 ? tailOffset : railLength - headOffset;
		final double endDistance = fromOrderedPosition1 ? headOffset : railLength - tailOffset;
		if (endDistance - startDistance <= 0.01) {
			return;
		}
		Data.put(vehiclePositions, orderedPosition1, orderedPosition2, vehiclePosition -> {
			final VehiclePosition newVehiclePosition = vehiclePosition == null ? new VehiclePosition() : vehiclePosition;
			newVehiclePosition.addSegment(startDistance, endDistance, id);
			return newVehiclePosition;
		}, Object2ObjectAVLTreeMap::new);
	}

	/**
	 * C5: write a consist body's footprint into the shared occupancy tree, per rail, in that rail's
	 * own distance space (measured from its ordered position 1) — the space every reader uses.
	 */
	private void writeMmtrConsistBodyOccupancy(MmtrConsistWalker consistWalker, Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> vehiclePositions) {
		final org.mtr.core.mmtr.consist.MmtrConsistBody body = consistWalker.body();
		final double aEnd = body.aEndArcM();
		final double bEnd = aEnd + body.lengthM();
		for (int i = 0; i < body.legCount(); i++) {
			final org.mtr.core.mmtr.consist.MmtrConsistBody.SpineLeg leg = body.leg(i);
			final double legStart = body.legStartArcM(i);
			final double fromM = Math.max(aEnd, legStart) - legStart;
			final double toM = Math.min(bEnd, legStart + leg.lengthM()) - legStart;
			if (toM - fromM <= 0.01) {
				continue;
			}
			final Rail rail = data.railIdMap.get(leg.railHex());
			if (rail == null) {
				continue;
			}
			final Position position1 = rail.getPosition1();
			final Position position2 = rail.getPosition2();
			final Position orderedPosition1 = position1.compareTo(position2) <= 0 ? position1 : position2;
			final Position orderedPosition2 = orderedPosition1 == position1 ? position2 : position1;
			final double railLength = rail.railMath.getLength();
			// The leg's coordinate runs entryNode -> exitNode; readers use ordered-position 1 space.
			final boolean fromOrderedPosition1 = orderedPosition1.equals(leg.entryNode());
			final double startM = fromOrderedPosition1 ? fromM : railLength - toM;
			final double endM = fromOrderedPosition1 ? toM : railLength - fromM;
			Data.put(vehiclePositions, orderedPosition1, orderedPosition2, vehiclePosition -> {
				final VehiclePosition newVehiclePosition = vehiclePosition == null ? new VehiclePosition() : vehiclePosition;
				newVehiclePosition.addSegment(startM, endM, id);
				return newVehiclePosition;
			}, Object2ObjectAVLTreeMap::new);
		}
	}

	/**
	 * C5: this train's frame on the rail it stands on — {@code [progressM, bodyLengthM]} where
	 * {@code progressM} is the leading face measured along the DIRECTION OF TRAVEL. Two trains heading
	 * the same way are therefore directly comparable: the one with the larger progress is physically
	 * ahead, and the coupler gap between them is {@code progressAhead - bodyLengthAhead - progressBehind}.
	 */
	public double[] mmtrTravelFrame() {
		if (mmtrMotionWalker == null) {
			return null;
		}
		final Rail rail;
		final double leadingOffset;
		final boolean towardExit;
		if (mmtrMotionWalker instanceof final MmtrConsistWalker consistWalker) {
			rail = consistWalker.referenceRail();
			leadingOffset = consistWalker.referenceOffsetM();
			towardExit = consistWalker.travelsTowardB();
		} else {
			rail = mmtrMotionWalker.currentRail();
			leadingOffset = mmtrMotionWalker.offsetM();
			towardExit = true;
		}
		if (rail == null) {
			return null;
		}
		final double railLength = rail.railMath.getLength();
		final double clampedLeadingOffset = Math.max(0, Math.min(railLength, leadingOffset));
		return new double[]{towardExit ? clampedLeadingOffset : railLength - clampedLeadingOffset, vehicleExtraData.getTotalVehicleLength()};
	}

	/** Index of the leg whose cumulative range contains {@code progress} (last leg when beyond). */
	private int indexInMmtrMotionLegs(double progress) {		for (int i = 0; i < mmtrMotionLegs.size(); i++) {
			if (mmtrMotionLegs.get(i).getEndDistance() > progress) {
				return i;
			}
		}
		return Math.max(0, mmtrMotionLegs.size() - 1);
	}

	/** Motion-mode leg shadow, or the legacy baked path when not in motion mode (single lookup chokepoint). */
	private java.util.List<PathData> motionOrLegacyPath() {
		return mmtrMotionWalker == null ? vehicleExtraData.immutablePath : mmtrMotionLegs;
	}

	/**
	 * Indicate which portions of each path segment are occupied by this vehicle. Also check if the vehicle needs to send a socket update:
	 * <ul>
	 * <li>Entered a client's view radius</li>
	 * <li>Left a client's view radius</li>
	 * <li>Started moving</li>
	 * <li>New stopping index or blocked rail</li>
	 * </ul>
	 */
	private void writeVehiclePositions(int currentIndex, Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> vehiclePositions) {
		final @Nullable Position[] minMaxPositions = {null, null};
		int index = currentIndex;

		while (index >= 0) {
			final PathData pathData = vehicleExtraData.immutablePath.get(index);
			final Position position1 = pathData.getOrderedPosition1();
			final Position position2 = pathData.getOrderedPosition2();
			minMaxPositions[0] = Position.getMin(minMaxPositions[0], Position.getMin(position1, position2));
			minMaxPositions[1] = Position.getMax(minMaxPositions[1], Position.getMax(position1, position2));

			if (railProgress - vehicleExtraData.getTotalVehicleLength() > pathData.getEndDistance()) {
				break;
			}

			if (!transportMode.continuousMovement) {
				final DoubleDoubleImmutablePair blockedBounds = getBlockedBounds(pathData, railProgress - vehicleExtraData.getTotalVehicleLength(), railProgress - 0.01);
				if (blockedBounds.rightDouble() - blockedBounds.leftDouble() > 0.01) {
					if (getIsOnRoute() && index > 0) {
						Data.put(vehiclePositions, position1, position2, vehiclePosition -> {
							final VehiclePosition newVehiclePosition = vehiclePosition == null ? new VehiclePosition() : vehiclePosition;
							newVehiclePosition.addSegment(blockedBounds.leftDouble(), blockedBounds.rightDouble(), id);
							return newVehiclePosition;
						}, Object2ObjectAVLTreeMap::new);
						pathData.isSignalBlocked(id, Rail.BlockReservation.CURRENTLY_RESERVE);
					}
				}
			}

			index--;
		}

		if (siding != null) {
			if (siding.area != null && data instanceof final Simulator simulator) {
				final boolean needsUpdate = vehicleExtraData.checkForUpdate();
				// MMTR: clients now mirror the same mmtr physics from the snapshot fields, so the
				// stock dirty-driven sync cadence (needsUpdate on state/power changes) is accurate
				// enough — no per-tick authoritative push hack required anymore.
				// TODO for continuous movement, maybe only send the path once rather than sending the entire path for each vehicle
				final int pathUpdateIndex = transportMode.continuousMovement ? 0 : Math.max(0, index + 1);
				simulator.clients.forEach(client -> {
					final Position position = client.getPosition();
					final double updateRadius = client.getUpdateRadius();
					if ((minMaxPositions[0] == null || minMaxPositions[1] == null) ? siding.area.inArea(position, updateRadius) : Utilities.isBetween(position, minMaxPositions[0], minMaxPositions[1], updateRadius) || !closeToDepot() && vehicleExtraData.hasRidingEntity(client.uuid)) {
						client.update(this, needsUpdate, pathUpdateIndex);
					}
				});
			}

			vehicleExtraData.setRoutePlatformInfo(siding.area, currentIndex);
		}
	}

	/**
	 * Checks if the rails ahead are clear up to a certain point (in terms of other vehicles or signals).
	 *
	 * @return the distance until the rail is blocked or -1 if there is nothing in front
	 */
	private double railBlockedDistance(int currentIndex, double checkRailProgress, double checkDistance, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions, boolean reserveRail, boolean secondPass) {
		int index = currentIndex;

		while (vehiclePositions != null && index < vehicleExtraData.immutablePath.size()) {
			final PathData pathData = vehicleExtraData.immutablePath.get(index);
			final double checkRailProgressEnd = checkRailProgress + checkDistance + transportMode.stoppingSpace;

			if (pathData.getStartDistance() >= checkRailProgressEnd) {
				return -1;
			}

			final double blockedStartOffset = Math.max(0, pathData.getStartDistance() - checkRailProgress);

			if (checkAndBlockSignal(index, vehiclePositions, reserveRail, secondPass)) {
				return blockedStartOffset;
			} else if (Utilities.isIntersecting(pathData.getStartDistance(), pathData.getEndDistance(), checkRailProgress, checkRailProgressEnd)) {
				final DoubleDoubleImmutablePair blockedBounds = getBlockedBounds(pathData, checkRailProgress, checkRailProgressEnd);
				for (int i = 0; i < 2; i++) {
					final VehiclePosition vehiclePosition = Data.tryGet(vehiclePositions.get(i), pathData.getOrderedPosition1(), pathData.getOrderedPosition2());
					if (vehiclePosition != null) {
						final double closestOverlap = vehiclePosition.getClosestOverlap(blockedBounds.leftDouble(), blockedBounds.rightDouble(), pathData.reversePositions, id);
						if (closestOverlap >= 0) {
							return Math.max(0, blockedStartOffset + closestOverlap - transportMode.stoppingSpace);
						}
					}
				}
			}

			index++;
		}

		return -1;
	}

	/**
	 * If a signal block is encountered, first check if the path after the entire block is clear. If so, reserve the signal block.
	 *
	 * @return if the vehicle should stop
	 */
	private boolean checkAndBlockSignal(int currentIndex, ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions, boolean reserveRail, boolean secondPass) {
		final PathData firstPathData = vehicleExtraData.immutablePath.get(currentIndex);

		if (secondPass) {
			return firstPathData.isSignalBlocked(id, Rail.BlockReservation.DO_NOT_RESERVE);
		} else {
			final IntAVLTreeSet signalColors = firstPathData.getSignalColors();
			int index = currentIndex + 1;

			while (!signalColors.isEmpty() && index < vehicleExtraData.immutablePath.size()) {
				final PathData pathData = vehicleExtraData.immutablePath.get(index);

				if (pathData.getSignalColors().intStream().noneMatch(signalColors::contains)) {
					// Only reserve the signal block after checking if the path after the signal block is clear, not before!
					final double railBlockedDistance = railBlockedDistance(index, pathData.getStartDistance(), vehicleExtraData.getTotalVehicleLength(), vehiclePositions, false, true);
					return railBlockedDistance >= 0 && railBlockedDistance < vehicleExtraData.getTotalVehicleLength() || firstPathData.isSignalBlocked(id, reserveRail ? Rail.BlockReservation.PRE_RESERVE : Rail.BlockReservation.DO_NOT_RESERVE);
				}

				index++;
			}

			return false;
		}
	}

	private long getTimeAlongRoute(double checkRailProgress) {
		// Subtract 1 from railProgress for rounding errors
		return siding == null ? 0 : (long) Math.floor(siding.getTimeAlongRoute(checkRailProgress - (speed == 0 ? 1 : 0)) + elapsedDwellTime);
	}

	private void updateDeviation() {
		deviation = transportMode.continuousMovement || siding == null ? 0 : Utilities.circularDifference(data.getCurrentMillis() - sidingDepartureTime, getTimeAlongRoute(railProgress), siding.getRepeatInterval(MILLIS_PER_DAY));
	}

	@Nullable
	private PositionAndTiltAngle getPositionAndTiltAngle(double value, DoubleArrayList overrideY) {
		final java.util.List<PathData> path = motionOrLegacyPath();
		final PathData pathData = Utilities.getElement(path, Utilities.getIndexFromConditionalList(path, value));
		if (pathData == null) {
			return null;
		} else {
			final PositionAndTiltAngle positionAndTiltAngle = pathData.getPositionAndTiltAngle(data, value - pathData.getStartDistance());
			if (transportMode == TransportMode.AIRPLANE && pathData.getSpeedLimitKilometersPerHour() == SidingPathFinder.AIRPLANE_SPEED && pathData.isDescending()) {
				if (overrideY.isEmpty()) {
					overrideY.add(positionAndTiltAngle.position.y());
					return positionAndTiltAngle;
				} else {
					return new PositionAndTiltAngle(new Vector(positionAndTiltAngle.position.x(), overrideY.getDouble(0), positionAndTiltAngle.position.z()), positionAndTiltAngle.tiltAngle);
				}
			} else {
				return positionAndTiltAngle;
			}
		}
	}

	private BogiePosition getBogiePositions(double value, DoubleArrayList overrideY) {
		final double lowerBound = railProgress - vehicleExtraData.getTotalVehicleLength();
		final double clampedValue = Utilities.clampSafe(value, lowerBound, railProgress);
		final double value1;
		final double value2;
		final double clamp = Utilities.clampSafe(Math.min(Math.abs(clampedValue - lowerBound), Math.abs(clampedValue - railProgress)), 0.1, 1);
		value1 = Utilities.clampSafe(clampedValue + (reversed ? -clamp : clamp), lowerBound, railProgress - 0.001);
		value2 = Utilities.clampSafe(clampedValue - (reversed ? -clamp : clamp), lowerBound, railProgress - 0.001);
		final PositionAndTiltAngle positionAndTiltAngle1 = getPositionAndTiltAngle(value1, overrideY);
		final PositionAndTiltAngle positionAndTiltAngle2 = getPositionAndTiltAngle(value2, overrideY);
		return positionAndTiltAngle1 == null || positionAndTiltAngle2 == null ? new BogiePosition(new PositionAndTiltAngle(new Vector(value1, 0, 0), 0), new PositionAndTiltAngle(new Vector(value2, 0, 0), 0)) : new BogiePosition(positionAndTiltAngle1, positionAndTiltAngle2);
	}

	/** km/h -> engine internal speed (m/ms). */
	private static double kmhToInternal(double speedKilometersPerHour) {
		return speedKilometersPerHour / 3600.0;
	}

	/**
	 * Signal S2: the speed limit (m/ms) of the rail the walker currently stands on, for the
	 * direction the vehicle travels (from the walker's entry node toward its ahead node); 0 = that
	 * direction is unreachable (never driven). Rail limits are directional per MTR data.
	 */
	private double mmtrCurrentRailLimitPerMs() {
		if (mmtrMotionWalker == null) {
			return 0;
		}
		// MmtrMotionPosition.currentRail() is @Nullable: the consist walker reports no rail while no
		// cab is manned (a parked train with no key inserted) and a legacy walker has none before it
		// boards one. Both are normal states, so "no current rail" means "no limit to read" rather
		// than a crash - an NPE here aborts the whole Simulator.tick, which is what blanked every
		// vehicle and rail on the client.
		final Rail currentRail = mmtrMotionWalker.currentRail();
		if (currentRail == null) {
			return 0;
		}
		return currentRail.getSpeedLimitMetersPerMillisecond(mmtrMotionWalker.enteredFromPosition());
	}

	/** Signal S2: current per-segment rail speed limit in km/h (0 = not running / unreachable). */
	public long getMmtrCurrentSpeedLimitKmh() {
		return Math.round(mmtrCurrentRailLimitPerMs() * 3600.0);
	}

	/**
	 * Signal S2/v3: the regime of the rail currently being traversed - AWS (≤ 100 km/h, point
	 * warning + driver acknowledgement) or LZB (≥ 101 km/h, continuous supervision). Derived per
	 * travel direction from the rail's own speed limit.
	 */
	public MmtrRegime getMmtrRegime() {
		return MmtrRegime.fromSpeedLimitKmh(getMmtrCurrentSpeedLimitKmh());
	}

	/**
	 * Signal S4 (LZB): the enforced speed ceiling of the current supervision - min(current rail
	 * limit, consist ceiling) in km/h; 0 = no supervision active (not on the LZB band / legacy).
	 */
	public long getMmtrLzbCeilingKmh() {
		if (mmtrMotionWalker == null || getMmtrRegime() != MmtrRegime.LZB || mmtrConsistType == null) {
			return 0;
		}
		final double ceilingMms = Math.min(mmtrCurrentRailLimitPerMs(), kmhToInternal(mmtrConsistType.getMaxSpeedKmh()));
		return Math.round(ceilingMms * 3600.0);
	}

	/**
	 * Signal S4 (LZB): the cab target speed in km/h - 0 when the train must stop (occupancy block
	 * ahead), else the lower of the next rail's limit and the current ceiling; 0 when idle.
	 */
	public long getMmtrLzbTargetKmh() {
		if (mmtrMotionWalker == null || getMmtrRegime() != MmtrRegime.LZB) {
			return 0;
		}
		if (mmtrBlockStopM < Double.MAX_VALUE / 2) {
			return 0;
		}
		final Rail nextRailLzb = mmtrMotionWalker.peekNextRail();
		if (nextRailLzb != null) {
			final double nextLimitMms = nextRailLzb.getSpeedLimitMetersPerMillisecond(mmtrMotionWalker.aheadNode());
			final double ceilingMms = mmtrCurrentRailLimitPerMs();
			if (nextLimitMms > 0 && nextLimitMms < ceilingMms - 1e-12) {
				return Math.round(nextLimitMms * 3600.0);
			}
		}
		return getMmtrLzbCeilingKmh();
	}

	/**
	 * Signal S4 (LZB): distance to the current LZB target in metres (the occupancy block stop or
	 * the node where the slower rail begins); -1 = no bounded target ahead.
	 */
	public double getMmtrLzbTargetDistanceM() {
		if (mmtrMotionWalker == null || getMmtrRegime() != MmtrRegime.LZB) {
			return -1;
		}
		if (mmtrBlockStopM < Double.MAX_VALUE / 2) {
			return Math.max(0, mmtrBlockStopM - mmtrMotionWalker.distanceM());
		}
		final Rail nextRailLzb = mmtrMotionWalker.peekNextRail();
		if (nextRailLzb != null) {
			final double nextLimitMms = nextRailLzb.getSpeedLimitMetersPerMillisecond(mmtrMotionWalker.aheadNode());
			final double ceilingMms = mmtrCurrentRailLimitPerMs();
			if (nextLimitMms > 0 && nextLimitMms < ceilingMms - 1e-12) {
				return mmtrMotionWalker.currentRailLengthM() - mmtrMotionWalker.offsetM();
			}
		}
		return -1;
	}

	/** Signal S3 (AWS): an unacknowledged point warning is currently active (HUD/display read). */
	public boolean isMmtrAwsWarningPending() {
		return mmtrAwsState == MMTR_AWS_WARN;
	}

	/** Signal S3 (AWS): the last warning was acknowledged - the indicator stays up until the restriction clears. */
	public boolean isMmtrAwsWarningAcknowledged() {
		return mmtrAwsState == MMTR_AWS_ACKED;
	}

	/**
	 * Signal S3 (AWS): per-tick warning state machine for LIVE manual driving on AWS-band rails
	 * (≤ 100 km/h; auto runs drive themselves and need no driver warning). A "restricted boundary"
	 * is an occupied rail ahead (block stop) or a slower rail to be braced for; while the train
	 * runs inside the trigger lead of that boundary the warning sounds (WARN). The driver's
	 * acknowledgement (ControlState.acknowledge, one-shot) moves the machine to ACKED - the
	 * yellow/black indicator stays up until the restriction clears. An unacknowledged warning that
	 * outlives the window while the train is STILL MOVING becomes a SPAD emergency stop through the
	 * existing protection channel (10 s lock, mirrored); once parked (occupancy wait) the warning
	 * holds without re-timing - no punishment for an already-safe stand.
	 */
	private void tickMmtrAwsWarning(long millisElapsed) {
		// C3a: under a 调车授权 (subsidiary aspect) AWS is suppressed - the signal has just authorised
		// this movement into the occupied section, so a warning horn would be wrong. This is the real
		// AWS rule, not an omission.
		final org.mtr.core.mmtr.signal.MmtrShuntAuthority authority = getMmtrShuntAuthority();
		if (authority != null && authority.suppressesAws()) {
			if (mmtrAwsState != MMTR_AWS_NONE) {
				mmtrAwsState = MMTR_AWS_NONE;
				mmtrAwsWarnElapsedMillis = 0;
				mmtrAwsAckQueued = false;
				System.out.println("[MMTR-AWS] warning suppressed by 调车授权 " + authority.getKind());
			}
			return;
		}
		if (mmtrMotionWalker == null || mmtrAwsState == MMTR_AWS_NONE && !mmtrManualOverride) {
			return;
		}
		final boolean awsBand = getMmtrRegime() == MmtrRegime.AWS;
		final boolean liveManual = mmtrManualOverride && speed > 1e-9;
		if (!awsBand || !mmtrManualOverride || mmtrProtection) {
			if (mmtrAwsState != MMTR_AWS_NONE && !mmtrProtection) {
				mmtrAwsState = MMTR_AWS_NONE;
				mmtrAwsWarnElapsedMillis = 0;
				mmtrAwsAckQueued = false;
				System.out.println("[MMTR-AWS] warning cleared (band/driver left)");
			}
			return;
		}
		// A3: the trigger is the SIGNAL the train is about to pass, not a raw distance. The aspect of
		// the rail it is entering (route-aware since A2) decides: red / single / double yellow -> warn,
		// green -> clear. That is what makes a caution two blocks ahead (single/double yellow) warn at
		// all - the old rule only saw the occupancy stop inside the lead. A speed-limit start ahead (a
		// slower next rail) stays a trigger, like a real AWS speed board, and the S1 occupancy stop
		// stays one too so a train parked at a block boundary keeps its acknowledged indication.
		final Simulator awsSimulator = data instanceof final Simulator simulator ? simulator : null;
		final Rail nextRailAws = mmtrMotionWalker.peekNextRail();
		final String signalRailHex = nextRailAws == null ? mmtrMotionWalker.railHex() : nextRailAws.getHexId();
		final Position signalEntryNode = nextRailAws == null ? mmtrMotionWalker.enteredFromPosition() : mmtrMotionWalker.aheadNode();
		final org.mtr.core.mmtr.signal.MmtrSignalAspect.Aspect signalAspect = awsSimulator == null || signalRailHex == null
			? org.mtr.core.mmtr.signal.MmtrSignalAspect.Aspect.GREEN
			: awsSimulator.mmtrSignalAspectView().aspectFrom(signalRailHex, signalEntryNode, id);
		final double remainingToSignalM = MmtrRunPlanner.remainingToAheadNodeM(mmtrMotionWalker);
		final boolean signalInLead = signalAspect != org.mtr.core.mmtr.signal.MmtrSignalAspect.Aspect.GREEN && remainingToSignalM <= MMTR_AWS_TRIGGER_LEAD_M + 1e-9;
		// Restricted boundary ahead: nearest of the occupancy block stop and a slower next rail.
		double boundaryM = mmtrBlockStopM < Double.MAX_VALUE / 2 ? mmtrBlockStopM : Double.MAX_VALUE;
		if (nextRailAws != null) {
			final double nextLimitMms = nextRailAws.getSpeedLimitMetersPerMillisecond(mmtrMotionWalker.aheadNode());
			final double currentLimitMms = mmtrCurrentRailLimitPerMs();
			if (nextLimitMms > 0 && nextLimitMms < currentLimitMms - 1e-12) {
				boundaryM = Math.min(boundaryM, mmtrMotionWalker.distanceM() + remainingToSignalM);
			}
		}
		final boolean restricted = signalInLead
			|| boundaryM < Double.MAX_VALUE / 2 && boundaryM - mmtrMotionWalker.distanceM() <= MMTR_AWS_TRIGGER_LEAD_M + 1e-9;
		if (!restricted) {
			// A press with no warning showing is not an acknowledgement (see applyMmtrControl).
			mmtrAwsAckQueued = false;
			if (mmtrAwsState != MMTR_AWS_NONE) {
				mmtrAwsState = MMTR_AWS_NONE;
				mmtrAwsWarnElapsedMillis = 0;
				System.out.println("[MMTR-AWS] warning cleared (restriction gone)");
			}
			return;
		}
		if (mmtrAwsState == MMTR_AWS_NONE) {
			mmtrAwsState = MMTR_AWS_WARN;
			mmtrAwsWarnElapsedMillis = 0;
			mmtrAwsAckQueued = false;
			System.out.println("[MMTR-AWS] warning on " + mmtrMotionWalker.railHex() + " at " + Math.round(mmtrMotionWalker.distanceM() * 10.0) / 10.0 + "m - signal " + signalAspect + " on " + signalRailHex + " in " + Math.round(remainingToSignalM * 10.0) / 10.0 + "m, boundary at " + Math.round(boundaryM * 10.0) / 10.0 + "m");
			return; // the acknowledgement window starts counting on the NEXT tick
		}
		if (mmtrAwsAckQueued) {
			mmtrAwsAckQueued = false;
			if (mmtrAwsState == MMTR_AWS_WARN) {
				mmtrAwsState = MMTR_AWS_ACKED;
				System.out.println("[MMTR-AWS] acknowledged");
			}
		} else if (mmtrAwsState == MMTR_AWS_WARN) {
			mmtrAwsWarnElapsedMillis += millisElapsed;
			if (mmtrAwsWarnElapsedMillis >= MMTR_AWS_ACK_WINDOW_MILLIS && liveManual) {
				// Unacknowledged and still moving: SPAD through the existing emergency channel.
				mmtrProtection = true;
				mmtrProtectionLockRemaining = MMTR_PROTECTION_LOCK_MS;
				mmtrAwsState = MMTR_AWS_ACKED;
				System.out.println("[MMTR-AWS] unacknowledged warning - SPAD emergency engaged");
			}
		}
	}

	/**
	 * Signal S1: nearest stop point (m, walker distance space) forced by OTHER vehicles' occupancy
	 * ahead of this motion vehicle, or {@code Double.MAX_VALUE} when nothing blocks. Two rules:
	 * (1) on the CURRENT rail the closest external occupancy face inside the window from the head to
	 * the rail end stops the vehicle {@code MMTR_BLOCK_TAIL_GAP_M} short of that face (exact-interval
	 * following - works for a same-direction tail ahead and for an oncoming head alike); on a rail
	 * covered by a 调车授权 the same rule applies with {@code MMTR_COUPLER_GAP_M}, so an authorised
	 * coupling movement draws up to the train it is coupling to instead of through it;
	 * (2) if the NEXT rail (the one the walker would elect after this rail - pure look-ahead, no
	 * crossing side effects) carries ANY external occupancy, the vehicle stops at this rail's end
	 * node (one occupied rail = one closed block, AWS-style section interlocking; the epsilon keeps
	 * the walker from boarding the occupied rail). Queries read the same shared occupancy trees the
	 * legacy path branch uses, keyed by the ordered rail endpoints.
	 */
	private double computeMmtrBlockStopM(@Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions) {
		if (mmtrMotionWalker == null || vehiclePositions == null || mmtrMotionLegs.isEmpty()) {
			return Double.MAX_VALUE;
		}
		// C3a 调车授权: under a subsidiary aspect the authorised movement may enter the occupied
		// section (permissive working) - the rails it covers are exempt from the whole-rail block
		// rule. C3b: on an authorised rail the occupancy face AHEAD still stops the movement, but at
		// coupler distance instead of the running tail gap - that is what "draw up to the train you
		// are coupling to" means, and it is what keeps the loco from driving through the rake.
		final org.mtr.core.mmtr.signal.MmtrShuntAuthority authority = getMmtrShuntAuthority();
		double stop = Double.MAX_VALUE;
		// (1) external occupancy face on the current rail, from the head up to the end of the rail
		final int index = indexInMmtrMotionLegs(railProgress);
		final PathData segment = mmtrMotionLegs.get(index);
		final boolean currentRailAuthorized = authority != null && authority.covers(segment.getRail() == null ? null : segment.getRail().getHexId());
		final double gap = currentRailAuthorized ? MMTR_COUPLER_GAP_M : MMTR_BLOCK_TAIL_GAP_M;
		if (mmtrMotionWalker instanceof final MmtrConsistWalker consistWalker) {
			// C5: a consist body's footprint lives in each rail's own coordinate, so the window has to
			// be built there too (the leg shadow is anchored at the walker's start, not at the rail).
			stop = Math.min(stop, mmtrConsistBodyBlockStop(consistWalker, vehiclePositions, gap));
		} else if (railProgress < segment.getEndDistance() - 1e-9) {
			final DoubleDoubleImmutablePair bounds = getBlockedBounds(segment, railProgress, segment.getEndDistance());
			for (int i = 0; i < vehiclePositions.size(); i++) {
				final VehiclePosition vehiclePosition = Data.tryGet(vehiclePositions.get(i), segment.getOrderedPosition1(), segment.getOrderedPosition2());
				if (vehiclePosition != null) {
					final double overlap = vehiclePosition.getClosestOverlap(bounds.leftDouble(), bounds.rightDouble(), segment.reversePositions, id);
					if (overlap >= 0) {
						stop = Math.min(stop, railProgress + Math.max(0, overlap - gap));
					}
				}
			}
		}
		// (2) next SECTION occupied = that block is closed: stop epsilon short of its boundary (the
		// signal protecting it, or the rail end when the section ends there). B2: sections are cut by
		// the wayside signals reading each rail (MmtrBlockService), so a train may run up to the signal
		// instead of halting at the start of the rail that carries the occupied section.
		final Rail nextRail = mmtrMotionWalker.peekNextRail();
		final Double sectionStopM = nextSectionStopM(vehiclePositions);
		if (sectionStopM != null) {
			stop = Math.min(stop, sectionStopM);
		}
		// (3) ① 区间式信号与道岔配合: the block ahead ends at a turnout the movement cannot be cleared
		// through (no operator branch, no grant, no target match). Real interlocking holds the train at
		// the signal BEFORE that block; without this the train runs the whole block and parks at the
		// points, occupying the block and hiding why the signal is red. The stop is the same boundary
		// as (2) - the signal at the end of the section the train is standing in.
		mmtrSectionAuthorityHold = false;
		final Double authorityStopM = nextSectionAuthorityStopM();
		if (authorityStopM != null) {
			mmtrSectionAuthorityHold = true;
			stop = Math.min(stop, authorityStopM);
		}
		// (4) ② 岔区清限 / 侧面防护: the node the train is about to cross is a junction whose clearance
		// zone is fouled by ANOTHER train (a consist whose tail is still inside the zone). A section
		// boundary sits on the node, so the section test alone would call the junction clear the moment
		// the other train's tail leaves it - and a movement entering from a third leg could be driven
		// into that train's side. Hold at this rail's end node instead.
		final Double junctionStopM = junctionClearanceStopM(vehiclePositions);
		if (junctionStopM != null) {
			stop = Math.min(stop, junctionStopM);
		}
		// (5) T3 —— **行车许可**（红灯 / 自己的进路没设好）：这是"信号第一次真正控车"。
		//
		// 前四条规则都是**占用与几何**：同轨遮挡、下一区间被占、区间出口是选不出腿的道岔、岔区清限。
		// 灯色此前不进任何停车规则（全仓库唯一"影响行车"的消费者是 AWS 告警），所以红灯只是一块显示屏。
		// 这一条把 MmtrMovementAuthority 的目标接进同一条 min 链：目标速度 0 ⇒ 停在目标前 ε。
		//
		// 与 (2) 的分工：(2) 管**占用类**红灯（下一区间被占），本条补的是**非占用类** ——
		// 进路 PENDING（含被物理道岔挡；T1 之后这个状态才可信）与联锁造成的红灯。
		mmtrSignalAuthorityHold = false;
		mmtrSignalAuthorityReason = "";
		final org.mtr.core.mmtr.signal.MmtrMovementAuthority movementAuthority = mmtrMovementAuthority();
		if (movementAuthority != null && movementAuthority.mustStop() && mmtrMotionWalker != null) {
			// 许可的距离是"从当前位置起算"的；min 链要的是走行空间的绝对里程 —— 与 (3)(4) 同一套换算。
			final double targetM = mmtrMotionWalker.distanceM() + movementAuthority.targetDistanceM - MMTR_BLOCK_NODE_EPS_M;
			if (targetM < stop) {
				stop = Math.max(0, targetM);
				mmtrSignalAuthorityHold = true;
				mmtrSignalAuthorityReason = movementAuthority.reason;
			}
		}
		return stop;
	}

	/**
	 * T3: this train's 行车许可 right now, or null when it has no live motion position.
	 *
	 * <p>放成一个方法，是为了让 feed / 诊断 / 停车规则读**同一份**结论 —— notes/114 的教训是
	 * 同一个问题在两处各算一遍，迟早各说各话。</p>
	 */
	public @Nullable MmtrMovementAuthority mmtrMovementAuthority() {
		// 读**登记表**而不是本车字段：登记表才是进路的权威来源（手术重建对象、手工发布进路等路径
		// 都会写它），本车字段只是它的一个影子。
		return data instanceof final Simulator simulator
			? MmtrMovementAuthority.forVehicle(simulator, mmtrMotionWalker, getId(), simulator.mmtrRoutes.route(getId()))
			: null;
	}

	/**
	 * ②: the walker-space stop point that holds the train at its current rail's end node when the node
	 * ahead is a junction (>= 3 rails) whose clearance zone is occupied by another vehicle, or null when
	 * the node is a plain joint / dead end, or the zone is clear.
	 */
	private @Nullable Double junctionClearanceStopM(@Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions) {
		if (vehiclePositions == null || mmtrMotionWalker == null || mmtrMotionLegs.isEmpty() || !(data instanceof final Simulator simulator)) {
			return null;
		}
		final Position node = mmtrMotionWalker.aheadNode();
		if (node == null) {
			return null;
		}
		final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<Position, Rail> neighbours = simulator.positionsToRail.get(node);
		if (neighbours == null || neighbours.size() < 3) {
			return null;
		}
		// The train must actually be about to CROSS the node (its section ends at the rail end); a
		// mid-rail signal boundary is not a junction crossing.
		final int index = indexInMmtrMotionLegs(railProgress);
		final PathData segment = mmtrMotionLegs.get(index);
		final Rail rail = segment.getRail();
		if (rail == null) {
			return null;
		}
		final double railLength = rail.railMath.getLength();
		final double legLength = segment.getEndDistance() - segment.getStartDistance();
		final double headOffsetInLeg = Math.max(0, Math.min(legLength, railProgress - segment.getStartDistance()));
		final double headArc = segment.reversePositions ? legLength - headOffsetInLeg : headOffsetInLeg;
		final MmtrBlockService.Block current = simulator.mmtrBlocks.blockAt(rail.getHexId(), Math.max(0, Math.min(railLength - 1e-6, headArc)));
		if (current == null) {
			return null;
		}
		final boolean towardHigherArc = !segment.reversePositions;
		final boolean boundaryAtRailEnd = towardHigherArc ? current.arcToM >= railLength - 1e-9 : current.arcFromM <= 1e-9;
		if (!boundaryAtRailEnd) {
			return null;
		}
		for (final Rail other : neighbours.values()) {
			final double otherLength = other.railMath.getLength();
			if (otherLength <= 0) {
				continue;
			}
			// The clearance window is the first MMTR_JUNCTION_CLEARANCE_M metres of every rail meeting at
			// the node, measured from the node (each rail's own ordered-1 arc space).
			final double nodeArc = MmtrBlockService.arcOfNode(other, node);
			if (Double.isNaN(nodeArc)) {
				continue;
			}
			final double windowFrom = nodeArc <= 1e-9 ? 0 : Math.max(0, otherLength - MMTR_JUNCTION_CLEARANCE_M);
			final double windowTo = nodeArc <= 1e-9 ? Math.min(otherLength, MMTR_JUNCTION_CLEARANCE_M) : otherLength;
			if (windowTo - windowFrom > 1e-9 && blockHasExternalOccupancy(other, windowFrom, windowTo, vehiclePositions)) {
				// Walker-space distance to the ahead node: offsetM is measured from the entry node
				// toward the node being approached (ordered-arc space flips for a reverse-running leg).
				final double toNode = railLength - mmtrMotionWalker.offsetM();
				return mmtrMotionWalker.distanceM() + Math.max(0, toNode - MMTR_BLOCK_NODE_EPS_M);
			}
		}
		return null;
	}

	/**
	 * ①: the walker-space stop point that holds the train at the boundary of the section it is about to
	 * enter when THAT section ends at a turnout the movement cannot be cleared through, or null when the
	 * block ahead is clear of that condition.
	 *
	 * <p>Only a section that reaches its rail's far node carries the junction: a block that ends at an
	 * intermediate signal has its own head further on, and the train may enter it and stop at that
	 * signal instead. A train already inside the block that ends at the unset turnout keeps the old
	 * behaviour (it draws up to the points and waits there) - the signal it passed is behind it.</p>
	 */
	private @Nullable Double nextSectionAuthorityStopM() {
		if (mmtrMotionWalker == null || mmtrMotionLegs.isEmpty() || !(data instanceof final Simulator simulator)) {
			return null;
		}
		final int index = indexInMmtrMotionLegs(railProgress);
		final PathData segment = mmtrMotionLegs.get(index);
		final Rail rail = segment.getRail();
		if (rail == null) {
			return null;
		}
		final double railLength = rail.railMath.getLength();
		if (railLength <= 0) {
			return null;
		}
		final double legLength = segment.getEndDistance() - segment.getStartDistance();
		final double headOffsetInLeg = Math.max(0, Math.min(legLength, railProgress - segment.getStartDistance()));
		final double headArc = segment.reversePositions ? legLength - headOffsetInLeg : headOffsetInLeg;
		final MmtrBlockService.Block current = simulator.mmtrBlocks.blockAt(rail.getHexId(), Math.max(0, Math.min(railLength - 1e-6, headArc)));
		if (current == null) {
			return null;
		}
		final boolean towardHigherArc = !segment.reversePositions;
		final boolean boundaryAtRailEnd = towardHigherArc ? current.arcToM >= railLength - 1e-9 : current.arcFromM <= 1e-9;
		final Rail entryRail;
		final double entryArc;
		if (boundaryAtRailEnd) {
			entryRail = mmtrMotionWalker.peekNextRail();
			if (entryRail == null) {
				return null;
			}
			entryArc = MmtrBlockService.arcOfNode(entryRail, mmtrMotionWalker.aheadNode());
			if (Double.isNaN(entryArc)) {
				return null;
			}
		} else {
			entryRail = rail;
			entryArc = towardHigherArc ? current.arcToM : current.arcFromM;
		}
		final MmtrBlockService.Block entrySection = simulator.mmtrBlocks.blockAt(entryRail.getHexId(), entryArc);
		if (entrySection == null) {
			return null;
		}
		// 调车授权 (permissive working) over the block being entered keeps the pre-① behaviour: the
		// authorised movement may draw up to the points.
		final org.mtr.core.mmtr.signal.MmtrShuntAuthority authority = getMmtrShuntAuthority();
		if (authority != null && authority.covers(entryRail.getHexId())) {
			return null;
		}
		final double entryRailLength = entryRail.railMath.getLength();
		final boolean entrySectionEndsAtRailEnd = towardHigherArc ? entrySection.arcToM >= entryRailLength - 1e-9 : entrySection.arcFromM <= 1e-9;
		if (!entrySectionEndsAtRailEnd || !mmtrMotionWalker.wouldHaltAtForkOn(entryRail)) {
			return null;
		}
		final double toBoundary = towardHigherArc ? current.arcToM - headArc : headArc - current.arcFromM;
		return mmtrMotionWalker.distanceM() + Math.max(0, toBoundary - MMTR_BLOCK_NODE_EPS_M);
	}

	/**
	 * 闭塞区间 v2 (S3): the walker-space stop forced by the movement's <strong>directional section</strong>
	 * being occupied, or null when the directional model has nothing to say about this position.
	 *
	 * <p>A v2 section is what one lamp protects, walked the way the lamp faces, so it normally spans
	 * several rails. The movement may run its own section out while that section is clear; when another
	 * train is inside it, the movement holds at the section boundary - the next lamp - instead of at the
	 * end of the current rail (v1's only possible boundary).</p>
	 *
	 * <p>Null is the normal answer on the 99 of 134 rails in the dev world that no lamp reaches: the
	 * caller then falls back to the v1 per-rail rule, so <strong>unsignalled line keeps the pre-v2
	 * behaviour exactly</strong>. This is deliberate - replacing the rule outright would leave most of
	 * the network with no occupancy stop at all.</p>
	 */
	private @Nullable Double directionalSectionStopM(@Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions, Rail rail, double headArc) {
		if (vehiclePositions == null || !(data instanceof final Simulator simulator)) {
			return null;
		}		final double railLength = rail.railMath.getLength();
		if (railLength <= MMTR_SECTION_ARC_EPS_M) {
			return null;
		}
		final double clampedArc = Math.max(0, Math.min(railLength, headArc));
		final double aheadArc = Math.max(0, Math.min(railLength, clampedArc + MMTR_SECTION_ARC_EPS_M));
		final org.mtr.core.data.RailMath railMath = rail.railMath;
		final double dx = railMath.getPosition(aheadArc, false).x() - railMath.getPosition(clampedArc, false).x();
		final double dz = railMath.getPosition(aheadArc, false).z() - railMath.getPosition(clampedArc, false).z();
		final double norm = Math.sqrt(dx * dx + dz * dz);
		if (norm < 1e-9) {
			return null;
		}
		final double headingX = dx / norm;
		final double headingZ = dz / norm;
		final org.mtr.core.mmtr.signal.MmtrDirectionalBlockService service = simulator.mmtrDirectionalBlocks;
		// Exclude THIS vehicle's own footprint: a train's body shadow is stored under its own id, and when
		// the shadow's anchor sits at or ahead of the head, counting it as "occupied ahead" puts the stop
		// point at the train's own feet - the throttle then does nothing and the train can never move far
		// enough to rewrite the shadow (measured live, notes/112 §4: a train at offset 5.46 on a 43 m rail
		// wrote [5.5, 37.5) under its own id and was deadlocked at a 0.04 m block stop).
		final double toBoundaryM = service.sectionBoundaryAheadM(rail.getHexId(), clampedArc, headingX, headingZ, vehiclePositions, id);
		if (toBoundaryM == Double.MAX_VALUE || toBoundaryM <= 0) {
			return null;
		}
		return mmtrMotionWalker.distanceM() + Math.max(0, toBoundaryM - MMTR_BLOCK_NODE_EPS_M);
	}

	/**
	 * B2: the walker-space stop point forced by the NEXT block section ahead being occupied, or null
	 * when nothing ahead is blocked (or the movement is authorised over that section's rail).
	 *
	 * <p>The section ahead is the next one on the current rail when a signal splits it, otherwise the
	 * first section of the rail the walker would elect - which reproduces the pre-B2 "next rail closed"
	 * rule exactly on rails with no signals (the synthetic nets and most yard tracks).</p>
	 *
	 * <p>S3: the <strong>directional</strong> section model is consulted first
	 * ({@link #directionalSectionStopM}); it only answers on rails a lamp actually reaches, so this v1
	 * rule remains the fallback for unsignalled line.</p>
	 */
	private @Nullable Double nextSectionStopM(@Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions) {
		if (vehiclePositions == null || mmtrMotionWalker == null || mmtrMotionLegs.isEmpty() || !(data instanceof final Simulator simulator)) {
			return null;
		}
		final int index = indexInMmtrMotionLegs(railProgress);
		final PathData segment = mmtrMotionLegs.get(index);
		final Rail rail = segment.getRail();
		if (rail == null) {
			return null;
		}
		final double railLength = rail.railMath.getLength();
		if (railLength <= 0) {
			return null;
		}
		final double legLength = segment.getEndDistance() - segment.getStartDistance();
		final double headOffsetInLeg = Math.max(0, Math.min(legLength, railProgress - segment.getStartDistance()));
		final double headArc = segment.reversePositions ? legLength - headOffsetInLeg : headOffsetInLeg;

		// S3: directional sections first (a lamp's own block, crossing rail ends).
		final org.mtr.core.mmtr.signal.MmtrShuntAuthority directionalAuthority = getMmtrShuntAuthority();
		if (directionalAuthority == null || !directionalAuthority.covers(rail.getHexId())) {
			final Double directionalStop = directionalSectionStopM(vehiclePositions, rail, headArc);
			if (directionalStop != null) {
				return directionalStop;
			}
		}

		final MmtrBlockService.Block current = simulator.mmtrBlocks.blockAt(rail.getHexId(), Math.max(0, Math.min(railLength - 1e-6, headArc)));
		if (current == null) {
			return null;
		}
		final boolean towardHigherArc = !segment.reversePositions;
		final double boundaryArc = towardHigherArc ? current.arcToM : current.arcFromM;
		final boolean boundaryAtRailEnd = towardHigherArc ? current.arcToM >= railLength - 1e-9 : current.arcFromM <= 1e-9;
		final org.mtr.core.mmtr.signal.MmtrShuntAuthority authority = getMmtrShuntAuthority();

		if (!boundaryAtRailEnd) {
			// The signal splits this rail: the section beyond it is the next block on the same rail.
			final double probeArc = Math.max(0, Math.min(railLength - 1e-6, boundaryArc + (towardHigherArc ? 1e-6 : -1e-6)));
			final MmtrBlockService.Block next = simulator.mmtrBlocks.blockAt(rail.getHexId(), probeArc);
			if (next == null || next == current) {
				return null;
			}
			if (authority != null && authority.covers(rail.getHexId())) {
				return null; // permissive working: the authorised movement may enter the occupied section
			}
			if (!blockHasExternalOccupancy(rail, next.arcFromM, next.arcToM, vehiclePositions)) {
				return null;
			}
			final double toBoundary = Math.abs(boundaryArc - headArc);
			return mmtrMotionWalker.distanceM() + Math.max(0, toBoundary - MMTR_BLOCK_NODE_EPS_M);
		}

		// The section ends at the rail end: the next section is on the elected next rail.
		final Rail nextRail = mmtrMotionWalker.peekNextRail();
		if (nextRail == null) {
			return null;
		}
		if (authority != null && authority.covers(nextRail.getHexId())) {
			return null;
		}
		final double entryArc = org.mtr.core.mmtr.signal.MmtrBlockService.arcOfNode(nextRail, mmtrMotionWalker.aheadNode());
		if (Double.isNaN(entryArc)) {
			return null;
		}
		final double nextLength = nextRail.railMath.getLength();
		final MmtrBlockService.Block next = simulator.mmtrBlocks.blockAt(nextRail.getHexId(), Math.max(0, Math.min(nextLength - 1e-6, entryArc)));
		if (next == null || !blockHasExternalOccupancy(nextRail, next.arcFromM, next.arcToM, vehiclePositions)) {
			return null;
		}
		final double toNode = mmtrMotionWalker.currentRailLengthM() - mmtrMotionWalker.offsetM();
		return mmtrMotionWalker.distanceM() + Math.max(0, toNode - MMTR_BLOCK_NODE_EPS_M);
	}

	/** Whether any OTHER vehicle occupies part of the arc window [orderedFromM, orderedToM] of {@code rail}. */
	private boolean blockHasExternalOccupancy(Rail rail, double orderedFromM, double orderedToM, ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions) {
		if (orderedToM - orderedFromM <= 1e-9) {
			return false;
		}
		final Position position1 = rail.getPosition1();
		final Position position2 = rail.getPosition2();
		final Position orderedPosition1 = position1.compareTo(position2) <= 0 ? position1 : position2;
		final Position orderedPosition2 = orderedPosition1 == position1 ? position2 : position1;
		for (int i = 0; i < vehiclePositions.size(); i++) {
			final VehiclePosition vehiclePosition = Data.tryGet(vehiclePositions.get(i), orderedPosition1, orderedPosition2);
			if (vehiclePosition != null && vehiclePosition.getClosestOverlap(orderedFromM, orderedToM, false, id) >= 0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * C5: rule (1) for a consist body — the distance (in walker space) to the nearest external
	 * occupancy face ahead of the leading end on the rail it stands on. The window is built in the
	 * rail's own ordered-position coordinate, which is where a consist body writes its footprint.
	 */
	private double mmtrConsistBodyBlockStop(MmtrConsistWalker consistWalker, ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions, double gapM) {
		final Rail rail = consistWalker.referenceRail();
		final Position entry = consistWalker.enteredFromPosition();
		if (rail == null || entry == null) {
			return Double.MAX_VALUE;
		}
		final double railLength = rail.railMath.getLength();
		final double leadingOffset = Math.max(0, Math.min(railLength, consistWalker.referenceOffsetM()));
		// The body's leg coordinate runs entryNode -> exitNode, i.e. from the A side toward the B side.
		// The consist travels toward its manned cab: CAB_A leads with the A end, so the leading face
		// moves toward the leg's ENTRY (decreasing offset); CAB_B leads toward the EXIT. (The walker's
		// aheadNode() is the spine's B-side node and says nothing about the direction of travel.)
		final boolean towardExit = consistWalker.travelsTowardB();
		final Position position1 = rail.getPosition1();
		final Position position2 = rail.getPosition2();
		final Position orderedPosition1 = position1.compareTo(position2) <= 0 ? position1 : position2;
		final Position orderedPosition2 = orderedPosition1 == position1 ? position2 : position1;
		final boolean legIsOrdered1 = orderedPosition1.equals(entry);
		double nearestGapM = Double.MAX_VALUE;
		for (int i = 0; i < vehiclePositions.size(); i++) {
			final VehiclePosition vehiclePosition = Data.tryGet(vehiclePositions.get(i), orderedPosition1, orderedPosition2);
			if (vehiclePosition == null) {
				continue;
			}
			for (final double[] segment : vehiclePosition.segmentsExcluding(id)) {
				// Convert the stored (ordered-position-1) interval into the leg's own coordinate.
				final double segmentStartLeg = legIsOrdered1 ? segment[0] : railLength - segment[1];
				final double segmentEndLeg = legIsOrdered1 ? segment[1] : railLength - segment[0];
				final double gap;
				if (towardExit) {
					gap = segmentStartLeg >= leadingOffset - 1e-6 ? segmentStartLeg - leadingOffset : Double.MAX_VALUE;
				} else {
					gap = segmentEndLeg <= leadingOffset + 1e-6 ? leadingOffset - segmentEndLeg : Double.MAX_VALUE;
				}
				if (gap < nearestGapM) {
					nearestGapM = gap;
				}
			}
		}
		return nearestGapM == Double.MAX_VALUE ? Double.MAX_VALUE : consistWalker.distanceM() + Math.max(0, nearestGapM - gapM);
	}

	private static DoubleDoubleImmutablePair getBlockedBounds(PathData pathData, double lowerRailProgress, double upperRailProgress) {
		final double distanceFromStart = Utilities.clampSafe(lowerRailProgress, pathData.getStartDistance(), pathData.getEndDistance()) - pathData.getStartDistance();
		final double distanceToEnd = pathData.getEndDistance() - Utilities.clampSafe(upperRailProgress, pathData.getStartDistance(), pathData.getEndDistance());
		return new DoubleDoubleImmutablePair(pathData.reversePositions ? distanceToEnd : distanceFromStart, pathData.getEndDistance() - pathData.getStartDistance() - (pathData.reversePositions ? distanceFromStart : distanceToEnd));
	}

	public record BogiePosition(PositionAndTiltAngle positionAndTiltAngle1, PositionAndTiltAngle positionAndTiltAngle2) {
	}

	public record PositionAndTiltAngle(Vector position, double tiltAngle) {
	}
}