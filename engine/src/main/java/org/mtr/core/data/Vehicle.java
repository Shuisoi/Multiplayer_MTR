package org.mtr.core.data;

import it.unimi.dsi.fastutil.booleans.BooleanBooleanImmutablePair;
import com.google.gson.JsonObject;
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
import org.mtr.core.mmtr.signal.MmtrSectionGeometry;
import org.mtr.core.mmtr.signal.MmtrSectionService;
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
	 * MMTR: 上一次解析出来的"操纵车底由哪节车说话"（{@link org.mtr.core.mmtr.MmtrCarTypeResolver}）。
	 * null 表示还没解析过 —— 用 null 而不是空串作初值，才能让"谁都没解析出来（用维度缺省）"这一次也真的解析。
	 */
	private @Nullable String mmtrResolvedCarTypeKey;
	/** 上一次解析时的车节数：廉价判据，见 {@link #mmtrRefreshConsistTypeFromCars()}。 */
	private int mmtrResolvedCarCount = -1;
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
	/**
	 * **停车锚点**（notes/155 现场）：停车点所在的那根轨 + 它在轨内的比例（0 = 车头进入这轨的那一端，
	 * 1 = 远端）。空 = 没有锚点，退回"只按 {@link #mmtrMotionStopTargetM} 累计里程"的老口径。
	 *
	 * <p>为什么要它：累计里程是"自臂那一刻的位置 + 算出来的进路长度"，而进路长度会变（让位后重规划、
	 * 岔位换了、绕了另一条）—— 实测同一根站台轨两次自臂给出 1298 m / 1375 m 两个停车点，
	 * 车于是穿过站台又开了 77 m。锚点是世界里的位置，重算多少遍都不动。</p>
	 */
	private String mmtrMotionStopRailHex = "";
	private double mmtrMotionStopFraction = -1;
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

	/**
	 * 只读出口：车辆此刻**正在申请**的那一组道岔（节点, 进向, 腿号）。
	 *
	 * <p>给 {@code interlock} 诊断用 —— "进路要求什么"与"车在申请什么"是排查道岔等待的两半，
	 * 只有两半都看得见，才不用靠猜（notes/134 的教训：界面上看不见的状态修不好）。</p>
	 */
	public ObjectArrayList<String[]> getMmtrPendingPointOps() {
		return new ObjectArrayList<>(mmtrPendingPointOps);
	}
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
	 * 停车点与"车头实际停住的位置"之间的允许误差（米）。闭塞停车点与本车停车点几乎重合时
	 * （实测差 0.02 m），按"已经到点"处理，不许在闭塞点干等（见 {@code simulateMmtrMotion} 里的说明）。
	 */
	private static final double MMTR_ARRIVAL_EPS_M = 0.5;
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
	private static final double MMTR_AWS_TRIGGER_LEAD_M = 75.0;	/**
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
	 * "我正在用这处道岔"（按着的正是自己计划要的位、车还压在岔轨上）时的**让位兜底**：这么久还没动就让。
	 *
	 * <p>取 3 分钟而不是 20 秒：20 秒是"正常等待（前车在过岔）"的量级，而"在用"的车往前走一步就要几秒到
	 * 十几秒 —— 用 20 秒去劝退它，结果是位置每 20 秒换一次手、两班车都不动（2026-09-17 现场）。
	 * 留这个兜底是为了保住"最终总有人让"这条活性（在用 ≠ 一定能走：它也可能被闭塞或信号扣住）。</p>
	 */
	private static final long MMTR_IN_USE_HOLD_MILLIS = 3L * MILLIS_PER_MINUTE;
	/**
	 * 闭塞区间 v2 (S3): the arc step used to read the movement's heading on its current rail when asking
	 * the directional section model which block it is in. Small enough to stay inside a rail, big enough
	 * that a sampled two-arc curve gives a usable direction.
	 */
	private static final double MMTR_SECTION_ARC_EPS_M = 0.05;
	private static long mmtrLastTurnoutWaitLogMillis;
	/** 本车从什么时候开始等道岔（0 = 没在等）；等太久就按 {@code MmtrPointAuthority#shouldYieldForOthers} 让位。 */
	private long mmtrTurnoutWaitSinceMillis;
	/** 让位窗口到什么时候：窗口里连申请都不发，确保对方能拿到位置。 */
	private long mmtrTurnoutYieldUntilMillis;
	/** 当前任务的动作**做过了没有**（换端这类原地动作一次任务只做一次）。 */
	private boolean mmtrTaskActionDone;
	/** 站台作业这次**已经开始停站了没有**（用来只打一次"开门停站"的日志；门可能是进站时就开着的）。 */
	private boolean mmtrStationServiceAnnounced;
	/** "被岔挡住、按计划补申请"这条自救日志的节流（它每 tick 都会成立）。 */
	private long mmtrLastForkSelfHealLogMillis;

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
		// 换了任务就重新开始记"动作做过了没有"（notes/150）
		mmtrTaskActionDone = false;
		mmtrStationServiceAnnounced = false;
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
	 * T5: 本车当前任务的**计划时刻**（{@code MmtrTask.earliestMs}，没设就用 {@code dueMs}）。
	 *
	 * <p>作业单步骤实例化任务时带上时刻（{@code MmtrJobScheduler} → {@code mission.attachTask}），
	 * 这里把它交给进路与道岔申请，用于**冲突裁决**：计划早的先走。
	 * 没有任务、或时刻没设（0）⇒ 无计划 ⇒ 退回**到达序**（行为与修前一致）。</p>
	 */
	private long mmtrPlannedMillis() {
		final org.mtr.core.mmtr.task.MmtrTask task = mmtrMission == null ? null : mmtrMission.getTask();
		if (task == null) {
			return Long.MAX_VALUE;
		}
		final long planned = task.earliestMs > 0 ? task.earliestMs : task.dueMs;
		return planned > 0 ? planned : Long.MAX_VALUE;
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
		/*
		 * **已经上电、却长时间不动的车也要让位**（notes/149 第二轮现场）。
		 *
		 * 第一版把让位只放在"自臂失败"那条路上 —— 于是真正按着道岔的那台车（它自臂成功了、`motionAuto=true`，
		 * 卡在后面的信号/道岔上不动）**从来不进那个分支**，只有别的车在让，僵局照旧。
		 * 现在的判据是"停着不动 + 手里还按着道岔"：停着的车本来就不在用那处道岔，放掉它、让要用的车先过。
		 */
		if (motionMission && data instanceof final Simulator stuckSimulator) {
			mmtrYieldTurnoutsWhenStuck(stuckSimulator);
		}
		/*
		 * **被岔挡住时，照计划自己补一条申请**（notes/155 §17 现场）。
		 *
		 * 现场：车停在信号前一动不动十几分钟，进路是 SET、前方区间也没别人，而道岔层里
		 * **没有任何属于它的申请**（`holder` 空）—— 它的进路明明从那处岔上过。
		 * 手工发一条 {@code point-req} 立刻就走（实测：735 m → 1115 m）。
		 */
		if (motionMission && data instanceof final Simulator forkSimulator) {
			mmtrRequestPointWhenHeldAtFork(forkSimulator);
		}
		/*
		 * **任务语义的执行者**（notes/150）。
		 *
		 * 任务不只是一张"开到某个目标"的指令：换端（CHANGE_ENDS）是**原地动作**，没有目的地，
		 * 得有人在车停稳之后把司机台翻到另一端。修前没有人做这件事 —— 任务被造出来、被挂到车上、
		 * 被日志打出来，就是没人执行它的动作（`task.kind()` 在计划包之外只被用来判"是不是站台目标"）。
		 */
		mmtrRunInPlaceTaskAction(mission);
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
				/*
				 * notes/155：站台作业按**计划给的停留**，不是引擎默认那几秒 —— 否则"停留 30s"
				 * 这条配置等于没有（车到站闪一下就走的根就在这里）。
				 *
				 * 而**不带停站作业的任务**（`DRIVE_TO_PLATFORM` 那类）到站就该算完成：它的语义就是
				 * "到站停稳"（见 {@code DriveToPlatformTask} 的类注释），停留是**下一步**的活。
				 * 修前一律给 5 秒默认停留，于是每一站都白停 5 秒 —— 十站一趟就是 50 秒，
				 * 计划里的到达/发车时刻被整体推后，越跑越晚。
				 */
				final org.mtr.core.mmtr.task.MmtrTask targetTask = mission.getTask();
				final long targetDwell = targetTask == null ? MMTR_MISSION_DWELL_MILLIS
					: (targetTask instanceof final org.mtr.core.mmtr.task.StationServiceTask targetService
						? targetService.effectiveDwellMillis(MMTR_MISSION_DWELL_MILLIS) : 0L);
				if (!isMoving() && data.getCurrentMillis() - mmtrMissionTargetArrivedMillis >= targetDwell) {
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
			mmtrMotionStopRailHex = "";         // 锚点跟着目标一起清（陈旧的锚点会"校正"出幽灵停车点）
			mmtrMotionStopFraction = -1;
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
				requestPendingForksAtomically(simulator.mmtrPointAuthority, mmtrPointOwner, data.getCurrentMillis() + MMTR_POINT_REQUEST_MILLIS, mmtrPlannedMillis());
			}
		}

		// S5: keep the published route's state in step with the turnout authority EVERY tick, not only
		// while the run is armed - a fork whose grant expires, or that an operator parks while the train
		// is still waiting to arm, must drop the route to PENDING so the signal protecting it returns
		// to danger instead of showing proceed for a movement the interlocking no longer has set.
		// By vehicle id (refresh is a no-op when the train has no route), so a surgery-rebuilt object
		// still maintains the route its predecessor published.
		if (data instanceof final Simulator routeSimulator) {
			routeSimulator.mmtrRoutes.refresh(getId(), routeSimulator.mmtrPointAuthority, mmtrPendingPointOps);
		}
	}

	/**
	 * **立刻**把当前任务自臂出去（规划进路 + 申请道岔 + 挂起点），不等下一 tick 的 {@code mmtrMissionTick}。
	 *
	 * <h3>为什么要这个入口（2026-09-17 现场问："为什么道岔请求慢半拍、不是换向后马上完成"）</h3>
	 * <p>tick 内的顺序是：**车辆走行 → … → 作业调度器**（{@code Simulator.tick}）。换端这个动作、
	 * 以及"该步完成"都发生在**车辆走行**里，而**下一步的挂载发生在同一 tick 更靠后的调度器里** ——
	 * 于是"新计划的规划 + 道岔申请"只能等到**下一 tick** 车辆再走一遍时、由 {@code mmtrMissionTick}
	 * 的自臂分支去做。现场看起来就是："换向已经做完（cab 翻了），道岔却慢半拍才申请、才扳"。</p>
	 *
	 * <p>调度器挂完任务后直接调这个入口，整条链就在同一 tick 里闭合：
	 * 换端 → 该步完成 → 挂下一步 → 规划进路 → 申请道岔 → （{@code Simulator} 在本 tick 末再同步一次位置）
	 * 世界上那道岔跟着动。</p>
	 *
	 * <p>判据与 {@code mmtrMissionTick} 里那条自臂分支**逐字一致**，所以从调度器调用不会做出
	 * 车辆自己不会做的事；幂等：已经自臂过时 {@code mmtrMotionAuto} 已为真，直接返回 false。</p>
	 *
	 * @return 是否真的做了一次自臂
	 */
	public boolean mmtrArmActiveMissionNow(Simulator simulator) {
		final MmtrMission mission = mmtrMission;
		if (mission == null || mmtrMotionWalker == null || mmtrMotionAuto || mmtrMotionStopTargetM >= 0
			|| mission.getState() == MmtrMission.State.AT_TARGET || mission.isTerminal()
			|| !mmtrMissionNeedsRouteSetup(mission)) {
			return false;
		}
		if (mission.getExecutor() == MmtrMission.Executor.PLAYER && mmtrPlayerRoutePublished) {
			return false;
		}
		mmtrMotionSelfArmMission(simulator, mission);
		return true;
	}

	/**
	 * MMTR (L3, slice 9): self-arm an AUTOPILOT mission on this motion vehicle - resolve the target
	 * platform/siding rail by id, plan the run (MmtrRunPlanner), preset the en-route turnouts into the
	 * authoritative store and arm the auto step-run (doors for passenger service). Infeasible targets
	 * fail the mission with the reason, so task owners observe the failure through the mission.
	 */
	private void mmtrMotionSelfArmMission(Simulator simulator, MmtrMission mission) {
		final long targetSidingId = mission.getTargetSidingId();
		/*
		 * **原地动作**（换端等）：没有要去的地方 —— 车就在它的目的轨上。
		 *
		 * 于是这里不规划、不申请道岔，只把任务生命周期推进到"到点"；真正动手的是
		 * {@link #mmtrRunInPlaceTaskAction}（在 tick 里、到点停稳之后执行一次）。
		 * 修前这条路直接 fail（"motion missions need an explicit target"），
		 * 而计划里的换端步骤本来就没有目标 ⇒ 交路一到终点就停住（notes/150）。
		 */
		if (mission.isInPlace()) {
			/*
			 * 原地动作里**唯一有前提**的一种：站台作业要求"车真的停在这个站台上"（notes/155）。
			 *
			 * 没有这条的话，"原地"就等于"在哪儿都开门"：上一步进站被撤活、车还卡在半路时，
			 * 下一步的站台作业会在半路上开一次门、停 30 秒、报"本站服务完成"—— 比不停更坏。
			 * 不在站台上就**落到下面的正常规划路**，照常开一趟过去（到了还是这个任务的停留）。
			 */
			if (!(mission.getTask() instanceof org.mtr.core.mmtr.task.StationServiceTask)
				|| mmtrStandsOnRail(simulator, mission.getTargetSidingId())) {
				if (mission.getState() == MmtrMission.State.ASSIGNED) {
					mission.dispatch();
				}
				if (mission.getState() == MmtrMission.State.DISPATCHED && speed <= 1e-9) {
					mission.atTarget();
					mmtrMissionTargetArrivedMillis = data.getCurrentMillis();
				}
				return;
			}
		}
		/*
		 * 目的地有两种写法：
		 *   ① 站台/股道对象（targetSidingId）—— 历史口径，按 id 反查它所在的那根图轨；
		 *   ② **轨目标**（mission.targetRailHex）—— 折返/换端点用"正规轨道"表达（用户的现场口径：
		 *      折返就是开到某根正规轨的尽头换端，不必把线路定义成股道），直接按 hex 找轨。
		 */
		final Rail targetRail;
		final double stopFraction;
		if (mission.hasTargetRail()) {
			targetRail = MmtrRunPlanner.findRailByHex(simulator, mission.getTargetRailHex());
			stopFraction = mission.getTargetRailFraction();
			if (targetRail == null) {
				mission.fail("target rail " + mission.getTargetRailHex() + " does not exist in the rail graph");
				return;
			}
		} else {
			if (targetSidingId == 0) {
				mission.fail("motion missions need an explicit target platform/siding id");
				return;
			}
			targetRail = MmtrRunPlanner.findSavedRailRail(simulator, targetSidingId);
			stopFraction = 1.0;
			if (targetRail == null) {
				mission.fail("target siding " + targetSidingId + " has no graph rail");
				return;
			}
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
		final MmtrRunPlanner.Plan plan = MmtrRunPlanner.planToRail(simulator, this, targetRail.getHexId(), stopFraction);
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
			/*
			 * **等太久就让位**（notes/149 现场）：道岔的持有关系跨 tick 存活，而释放只发生在越岔/换路时 ——
			 * 一台自己也不动的车会一直按着某个位置，另一台要互斥位置的车永远等不到，几台车一起僵在咽喉里
			 * （现场：库里六台车一台都出不去）。道岔只有一个位置，要解环必须有一方先退：本车等过
			 * 由 {@code MmtrPointAuthority#shouldYieldForOthers} 判定就把自己在道岔层的全部痕迹放掉，并**静默一小会儿**
			 * 让对方先把位置拿走、把路走完；之后本车重新规划、重新申请。
			 */
			if (mmtrTurnoutWaitSinceMillis == 0) {
				mmtrTurnoutWaitSinceMillis = now;
			}
			if (org.mtr.core.mmtr.point.MmtrPointAuthority.shouldYieldForOthers(now, mmtrTurnoutWaitSinceMillis, mmtrTurnoutYieldUntilMillis)) {
				if (!simulator.mmtrPointAuthority.someoneHasPriorityOver(mmtrPointOwner)) {
					/*
					 * **没有比我更该走的车 ⇒ 不让**（notes/155 §13 现场）。
					 *
					 * <p>只按"等够了"就让位会让领先车把自己刚拿到的位置也放掉：后车拿到、它再申请、
					 * 再让 —— 现场每 20 秒一轮的"让位"日志就是这么刷出来的，几台车谁也走不了。
					 * 道岔只有一个位置，该退的是**计划时刻更晚**的那一方（与通行优先权同一口径）。</p>
					 *
					 * <p>这一支**不打日志**：它每 tick 都可能成立，打出来就是刷屏；
					 * "在等哪一处道岔、谁挡着"由下面那条周期性日志负责。</p>
					 */
				} else {
					// "退得干净"这一层权限层早就有（releaseAll：逐进向持有 + 两处排队 + 物理位置一起放，放了立刻递补）；
					// 缺的是**什么时候退**这条策略 —— 就是这里这一句。
					simulator.mmtrPointAuthority.releaseAll(mmtrPointOwner);
					releaseMmtrPointRequests();
					mmtrTurnoutYieldUntilMillis = now + org.mtr.core.mmtr.point.MmtrPointAuthority.MMTR_TURNOUT_YIELD_MILLIS;
					mmtrTurnoutWaitSinceMillis = 0;
					System.out.println("[MMTR-PT] 让位：车 " + getId() + " 等道岔超过 " + (org.mtr.core.mmtr.point.MmtrPointAuthority.MMTR_TURNOUT_YIELD_MILLIS / 1000)
						+ " 秒，且挡着计划更早的车 —— 放掉自己在道岔层的持有与排队（" + (org.mtr.core.mmtr.point.MmtrPointAuthority.MMTR_TURNOUT_YIELD_MILLIS / 1000) + " 秒后再申请）");
					return;
				}
			}
			if (now < mmtrTurnoutYieldUntilMillis) {
				return;   // 让位窗口里：连申请都不发，确保对方能拿到位置
			}
			if (now - mmtrLastTurnoutWaitLogMillis >= MMTR_TURNOUT_WAIT_LOG_INTERVAL_MILLIS) {
				mmtrLastTurnoutWaitLogMillis = now;
				// Name the blocking point (operator park / other holder / queue): a wait that never ends
				// is only diagnosable from the log if the log says WHICH point and WHO holds it.
				System.out.println("[MMTR-MSG] motion mission " + mission.getKind() + " waiting for turnout authority on rail " + plan.targetRailHex
					+ " - " + org.mtr.core.mmtr.MmtrRunPlanner.describeForkWait(mmtrPendingPointOps, simulator.mmtrPointAuthority, mmtrPointOwner));
			}
			return;
		}
		mmtrTurnoutWaitSinceMillis = 0;
		mmtrTurnoutYieldUntilMillis = 0;
		if (mission.getExecutor() == MmtrMission.Executor.AUTOPILOT) {
			setMmtrMotionAuto(true);
			setMmtrMotionStopTarget(plan.stopCumulativeM, plan.stopRailHex, plan.stopFraction, mission.getKind() == MmtrMission.Kind.PASSENGER);
			System.out.println("[MMTR-MSG] motion mission " + mission.getKind() + " self-armed to rail " + plan.targetRailHex + " stop @" + Math.round(plan.stopCumulativeM) + "m"
				+ (plan.stopRailHex.isEmpty() ? "" : "（锚点 " + plan.stopRailHex.substring(0, 8) + " × " + Math.round(plan.stopFraction * 100.0) / 100.0 + "）"));
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
	 * 本车是不是**正踩在这条轨上**（按 id 找站台/股道的轨，见 {@code MmtrRunPlanner.findSavedRailRail}）。
	 *
	 * <p>用途（notes/155）：站台作业开门的**前提**。不判这一条，"原地"就退化成"在哪儿都开门"。</p>
	 */
	private boolean mmtrStandsOnRail(Simulator simulator, long railOrSidingId) {
		if (railOrSidingId == 0 || mmtrMotionWalker == null) {
			return false;
		}
		final Rail rail = org.mtr.core.mmtr.MmtrRunPlanner.findSavedRailRail(simulator, railOrSidingId);
		return rail != null && rail.getHexId().equals(mmtrMotionWalker.railHex());
	}

	/**
	 * **原地动作**的执行者：到点停稳之后，按任务类型动手（目前只有换端）。
	 *
	 * <p>为什么要有它（notes/150）：计划层会造出 {@code CHANGE_ENDS}（换端）这样的步骤，
	 * 但**全引擎没有任何地方按任务类型执行动作** —— 换端只是被造出来、挂着、打日志。
	 * 结果就是"车到了终点就停在那里"，下一趟（要往回开）永远不成立。
	 * 这里的口径与作业单那条路一致（{@code MmtrJobScheduler.completeChangeEndsStep}）：
	 * **只在停稳时**换端，调的是同一个 {@link #changeEndsMmtrMotion()}。</p>
	 *
	 * <p>一次任务只动手一次（{@code mmtrTaskActionDone} 闩），换端本身会把进路/停车目标清掉
	 * 并让任务重新自臂 —— 不清闩的话车会在同一站反复换端。</p>
	 */
	private void mmtrRunInPlaceTaskAction(MmtrMission mission) {
		if (isClientside || mission == null || mission.isTerminal()) {
			return;
		}
		final org.mtr.core.mmtr.task.MmtrTask task = mission.getTask();
		if (task == null || !task.inPlace() || mission.getState() != MmtrMission.State.AT_TARGET) {
			return;
		}
		if (speed > 1e-9) {
			return;   // 还没停稳（换端本来就只能在停稳时做）
		}
		/*
		 * 闩的语义是"**这个任务的动作已经完成**"，所以它必须挡在**动作开始之后**，不能挡在方法入口：
		 * 第一版把 `mmtrTaskActionDone` 放进入口那个 if，站台作业的"停够关门"那条分支就永远不可达 ——
		 * 开门那一 tick 把闩置上，之后每 tick 都在入口返回（**实测现场效果一致**，因为任务到点完成时
		 * tick 里还有一次关门的兜底，但代码里那行是死的，读代码的人会以为门是"这条分支关的"）。
		 */
		if (mmtrTaskActionDone) {
			return;
		}
		switch (task.kind()) {
			/*
			 * **站台作业**（notes/155）：计划里的"停留 30s"原来**没有任何地方读**，站台作业形同虚设 ——
			 * 车到站只是"开往站台"那一步到点停了一下、下一步立刻派出去，现场看起来就是"站台不停"。
			 * 现在到点停稳就**开门 → 按计划停留 → 关门**；时长取"计划给的"与"引擎默认"的大者。
			 *
			 * 判"门已经开了没"用的是**门自己的状态**（`mmtrDoorsOpen`），不是闩 —— 闩只在**关门**时置上：
			 * 开着门的那几十秒里本方法每 tick 都要再进来一次，才有机会关门。
			 */
			case STATION_SERVICE -> {
				final long dwell = task instanceof final org.mtr.core.mmtr.task.StationServiceTask service
					? service.effectiveDwellMillis(MMTR_MISSION_DWELL_MILLIS) : MMTR_MISSION_DWELL_MILLIS;
				if (!mmtrStationServiceAnnounced) {
					mmtrStationServiceAnnounced = true;
					if (!vehicleExtraData.mmtrDoorsOpen()) {
						vehicleExtraData.openDoors();   // 进站时已经开着的（客运任务自开）就不重复开
					}
					System.out.println("[MMTR-PLAN] 执行任务动作：站台开门停站 → 车 " + getId()
						+ "（" + task.taskId + "，计划停留 " + (dwell / 1000) + "s）");
				} else if (data.getCurrentMillis() - mmtrMissionTargetArrivedMillis >= dwell) {
					vehicleExtraData.closeDoors();   // 停够就关门（任务随后由 tick 正常完成）
					mmtrTaskActionDone = true;
					System.out.println("[MMTR-PLAN] 执行任务动作：站台停够关门 → 车 " + getId()
						+ "（" + task.taskId + "，停了 " + (dwell / 1000) + "s）");
				}
			}
			case CHANGE_ENDS -> {
				if (changeEndsMmtrMotion()) {
					mmtrTaskActionDone = true;
					System.out.println("[MMTR-PLAN] 执行任务动作：换端 → 车 " + getId()
						+ "（" + task.taskId + "，现在 "
						+ (getMmtrConsistWalker() == null ? "?" : getMmtrConsistWalker().cabs().activeCab()) + " 端在前）");
				} else {
					// 现在做不了（不是 B 系编组 / 走行子系统没接）：留给下一 tick 再试，别静默
					System.out.println("[MMTR-PLAN] 换端做不了（车 " + getId() + "）：需要 B 系编组与走行子系统");
					mmtrTaskActionDone = true;   // 同一任务只报一次，避免刷屏
				}
			}
			default -> mmtrTaskActionDone = true;   // 其余类型目前没有额外动作
		}
	}

	/**
	 * 停着不动的车：手里还按着道岔就**让位**（notes/149 第二轮现场）。
	 *
	 * <p>它停着 = 它现在不用那处道岔；而道岔只有一个位置，按着不放就会把要过岔的车堵死。
	 * 让它退出来、静默一会儿（同 {@link org.mtr.core.mmtr.point.MmtrPointAuthority#MMTR_TURNOUT_YIELD_MILLIS}），
	 * 自己的进路也随之作废、下一 tick 重新规划（位置已经不是它的了，旧计划不能再用）。</p>
	 *
	 * <p>与"自臂失败时的让位"共用同一套计时与阈值：判据统一为"等/停了够久 + 不在让位窗口里"。</p>
	 */
	/**
	 * **停着不动、且前方岔口需要一次决策时，按计划把这处岔申请下来**（notes/155 §17 现场）。
	 *
	 * <p>为什么要有这一句：现场那辆车停在 735 m 的信号前十几分钟 —— 进路 SET、前方区间没别人、
	 * 道岔也没人锁，但道岔层里**没有它的任何申请**（{@code holder=""}），于是走行的岔口选举失败
	 * （{@code wouldHaltAtForkOn} 为真）→ 区间不开 → 车不动。手工发一条 {@code point-req} 立刻就走了
	 * （实测 735 m → 1115 m），说明缺的就是这一次申请：**计划知道要过哪根轨，却没人把它翻译成申请**。</p>
	 *
	 * <p>判据三条，缺一不可：①停着不动；②下一根轨的远端岔口"需要决策"（走行自己说了算）；③
	 * **计划里确实从那处岔接着走**（{@code plannedRailAfter}）—— 计划不走那里的车绝不去抢岔。</p>
	 */
	private void mmtrRequestPointWhenHeldAtFork(Simulator simulator) {
		if (isMoving() || mmtrMotionWalker == null || mmtrMotionPlan == null || mmtrPointOwner.isEmpty()) {
			return;
		}
		/*
		 * **不能用 {@code peekNextRail()}**：它的契约就是"该车会停/到头时返回 null" ——
		 * 而"被岔挡住"恰恰是这种情形，于是第一版在这里一律提前返回、什么都不做（现场实测：
		 * 自救日志一行都没有）。改用"当前轨 + 前方节点 + 计划说接着走哪根轨"三件事实。
		 */
		final org.mtr.core.data.Rail currentRail = mmtrMotionWalker.currentRail();
		/*
		 * **必须用方向感知的节点**（notes/171）：编组走行体的 {@code aheadNode()/enteredFromPosition()}
		 * 是脊线 A→B 的两个端点 —— 车反向（A 端在前）行驶时它们与行车方向**正好相反**。
		 * 读裸值会让下面那次"按计划补申请"问错节点：腿必然解不出来（leg<0），于是每 5 秒打一行
		 * "计划要的腿不在岔口腿表里"的假警报，而真正该发的申请一次都没发出去。
		 */
		final Position forkNode = org.mtr.core.mmtr.MmtrRunPlanner.travelAheadNode(mmtrMotionWalker);
		final Position entryNode = org.mtr.core.mmtr.MmtrRunPlanner.travelEntryNode(mmtrMotionWalker);
		if (currentRail == null || forkNode == null) {
			return;
		}
		final String desiredHex = org.mtr.core.mmtr.MmtrRunPlanner.plannedRailAfter(mmtrMotionPlan, currentRail.getHexId());
		final org.mtr.core.data.Rail desired = org.mtr.core.mmtr.MmtrRunPlanner.railByHex(simulator, desiredHex);
		if (desired == null) {
			return;   // 计划不从这里走：不乱扳岔（要报的话见下面的"计划要的腿不在表里"那一支）
		}
		final java.util.Map<Position, org.mtr.core.data.Rail> neighbors = simulator.positionsToRail.get(forkNode);
		if (neighbors == null || neighbors.size() < 2) {
			return;   // 不是岔口：没有决策要申请
		}
		final int leg = org.mtr.core.mmtr.MmtrRunPlanner.legIndexForRail(simulator, entryNode, forkNode, currentRail, desired);
		if (leg < 0) {
			mmtrLogForkSelfHeal("按计划补申请：车 " + getId() + " 被 " + org.mtr.core.mmtr.signal.MmtrJunctionState.nodeKey(forkNode)
				+ " 挡住，但计划要的腿不在岔口腿表里 —— 需要重规划");
			return;
		}
		final org.mtr.core.mmtr.point.MmtrPointAuthority authority = simulator.mmtrPointAuthority;
		if (authority.isGrantedTo(forkNode.getX(), forkNode.getY(), forkNode.getZ(), currentRail.getHexId(), mmtrPointOwner)) {
			return;   // 已经拿到了：不用每 tick 重复申请
		}
		final org.mtr.core.mmtr.point.MmtrPointAuthority.Result result = simulator.mmtrPointRequest(
			forkNode.getX(), forkNode.getY(), forkNode.getZ(), currentRail.getHexId(), mmtrPointOwner, leg,
			data.getCurrentMillis() + MMTR_POINT_REQUEST_MILLIS);
		if (result == org.mtr.core.mmtr.point.MmtrPointAuthority.Result.GRANTED) {
			mmtrLogForkSelfHeal("按计划补申请：车 " + getId() + " 被 " + org.mtr.core.mmtr.signal.MmtrJunctionState.nodeKey(forkNode)
				+ " 挡住，申请第 " + leg + " 条腿 → 已授予");
		} else {
			mmtrLogForkSelfHeal("按计划补申请：车 " + getId() + " 被 " + org.mtr.core.mmtr.signal.MmtrJunctionState.nodeKey(forkNode)
				+ " 挡住，申请第 " + leg + " 条腿 → " + result + "（" + authority.lastWaitReason(mmtrPointOwner) + "）");
		}
	}

	/** 自救日志的节流（同一件事最多每 {@link #MMTR_TURNOUT_WAIT_LOG_INTERVAL_MILLIS} 说一次）。 */	private void mmtrLogForkSelfHeal(String message) {
		final long now = System.currentTimeMillis();
		if (now - mmtrLastForkSelfHealLogMillis < MMTR_TURNOUT_WAIT_LOG_INTERVAL_MILLIS) {
			return;
		}
		mmtrLastForkSelfHealLogMillis = now;
		System.out.println("[MMTR-PT] " + message);
	}

	private void mmtrYieldTurnoutsWhenStuck(Simulator simulator) {
		final long now = System.currentTimeMillis();
		if (isMoving()) {
			mmtrTurnoutWaitSinceMillis = 0;   // 动着就是在用，别让
			return;
		}
		final boolean holdsSomething = !simulator.mmtrPointAuthority.physicalHoldNodesOf(mmtrPointOwner).isEmpty()
			|| !mmtrPendingPointOps.isEmpty();
		if (!holdsSomething) {
			mmtrTurnoutWaitSinceMillis = 0;
			return;
		}
		if (mmtrTurnoutWaitSinceMillis == 0) {
			mmtrTurnoutWaitSinceMillis = now;
			return;
		}
		if (!org.mtr.core.mmtr.point.MmtrPointAuthority.shouldYieldForOthers(now, mmtrTurnoutWaitSinceMillis, mmtrTurnoutYieldUntilMillis)) {
			return;
		}
		/*
		 * **我按着的正是自己计划要的位、而且人就压在这处道岔的轨上 ⇒ 我是"在用"的那一方，不让**
		 * （2026-09-17 现场修：北端折返咽喉两班车互让到死）。
		 *
		 * <h3>现场读数</h3>
		 * <p>车 B 在 36 m 正线轨上、按着位置 0（**正是它自己要的位**：它要直着开进 31 m 折返段），
		 * 车 A 在斜线上排队要位置 1。而下面那条"有人排在我按着的位置后面 ⇒ 我就让"的破环规则，
		 * 让 B 每 20 秒放一次、A 拿到 1；A 又按同一条判据在 20 秒后放出去、B 再拿回 0 ——
		 * **位置每 20 秒换一次手，两班车谁也没动**（日志里 {@code 让位（停着不动）} 每 20 秒一行，
		 * 刷了十几分钟）。而这时候正确行为是确定的：B 只要往前走一步就出清了，A 与它互斥、只能等；
		 * 把"正在用"的车劝退，等于把唯一能解开这个环的动作取消掉。</p>
		 *
		 * <h3>为什么还留一个很长的兜底</h3>
		 * <p>"在用"不等于"一定能走"：它也可能被前方的闭塞或信号扣住。所以超过
		 * {@link #MMTR_IN_USE_HOLD_MILLIS} 还是不动的话照旧让位 —— 保留"最终总有人让"这条活性，
		 * 只是不再每 20 秒空转一次。</p>
		 */
		if (now - mmtrTurnoutWaitSinceMillis < MMTR_IN_USE_HOLD_MILLIS && mmtrHoldsThePositionItsOwnPlanNeeds(simulator)) {
			return;
		}
		if (!simulator.mmtrPointAuthority.someoneHasPriorityOver(mmtrPointOwner)) {
			/*
			 * 停着的车本来该给"计划更早"的车让位；可如果**等着的车计划都比我晚**，那就是我该先走 ——
			 * 这时候放掉手里刚拿到的位置只会制造振荡（notes/155 §13 现场）。
			 *
			 * 这条**不打日志**：它每 tick 都会成立，打出来就是刷屏（"等哪一处道岔、谁挡着"那条
			 * 周期性日志已经把现场说清楚了）。
			 *
			 * <p><b>但"没人比我更早"不等于"我不该让"</b>（2026-09-16 现场修）：作业单里的循环车都没有
			 * 计划时刻（priority 全是一样的 {@code Long.MAX_VALUE}），于是这条早退让**所有车都不让** ——
			 * 现场就是三班车在 (-176,-253) 那处道岔上互相扣死（一辆压在岔股轨上、一辆按着位置 0、
			 * 一辆在队列里），`point why` 原话 {@code queue=v…@0} 明明白白排着队，却谁也不退。
			 * 所以补一条确定性的破环规则：**有人排在我按着的位置后面等着** ⇒ 我让（等待时长足够时）。</p>
			 */
			if (!someoneQueuedBehindMyHold(simulator)) {
				return;
			}
		}
		simulator.mmtrPointAuthority.releaseAll(mmtrPointOwner);
		releaseMmtrPointRequests();
		// 位置不在了，旧进路也不能再算数：让任务下一 tick 重新规划（自臂会重新排一次）
		mmtrMotionAuto = false;
		mmtrMotionStopTargetM = -1;
		mmtrTurnoutYieldUntilMillis = now + org.mtr.core.mmtr.point.MmtrPointAuthority.MMTR_TURNOUT_YIELD_MILLIS;
		mmtrTurnoutWaitSinceMillis = 0;
		System.out.println("[MMTR-PT] 让位（停着不动）：车 " + getId() + " 挡着计划更早的车，放掉自己在道岔层的持有与排队，" + (org.mtr.core.mmtr.point.MmtrPointAuthority.MMTR_TURNOUT_YIELD_MILLIS / 1000)
			+ " 秒后重新规划并申请");
	}

	/**
	 * 我按着的某处道岔上，是否**有别人在排队等着"另一位"**（{@code point why} 里那串 {@code queue=v…@0}）。
	 *
	 * <p>用它做"没人比我更早时要不要让位"的判据：别人已经排在我按着的位置后面等着了，说明我挡着它 ——
	 * 停着的车本来就不在用那处道岔，让出去环就解开了；没人排队时不让（避免"放掉又申请"的振荡）。</p>
	 *
	 * <h3>但**要同一位的**不算（2026-09-17 现场修：北段咽喉两班车轮流让位到死）</h3>
	 * <p>现场：持有者按着位置 0，队列里排着两班 —— 一辆要 **1**、一辆要 **0**。要 0 的那班与持有者
	 * **位置相容**：按着 0 一点没挡着它（它过不去的原因是**车体压在它前面那段区间里**，不是道岔位置）。
	 * 原来这里只要"队列里有人"就让位，于是两班车**轮流**让位（日志每 20–40 秒一行，
	 * {@code -544964743732613521} 与 {@code -5764088690191233245} 交替刷），谁也没走成。</p>
	 *
	 * <p>判据收紧为：**队列里有人要的是与我不同的位** —— 那才是真的在争这处道岔的两个位置。</p>
	 */
	private boolean someoneQueuedBehindMyHold(Simulator simulator) {
		for (final long[] node : simulator.mmtrPointAuthority.physicalHoldNodesOf(mmtrPointOwner)) {
			final int myPosition = simulator.mmtrPointAuthority.physicalPosition(node[0], node[1], node[2]);
			for (final String queued : simulator.mmtrPointAuthority.physicalQueueSnapshot(node[0], node[1], node[2])) {
				if (queued.startsWith(mmtrPointOwner + "@")) {
					continue;   // 我自己排的队不算
				}
				final int at = queued.lastIndexOf('@');
				final int queuedPosition = at < 0 || at + 1 >= queued.length()
					? Integer.MIN_VALUE : parseQueuePosition(queued.substring(at + 1));
				if (queuedPosition != Integer.MIN_VALUE && queuedPosition != myPosition) {
					return true;   // 有人要**另一位** ⇒ 我按着的位确实挡着它
				}
			}
		}
		return false;
	}

	private static int parseQueuePosition(String text) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			return Integer.MIN_VALUE;
		}
	}

	/**
	 * 我手里按着的某处道岔上，是不是有"**我按着的正是自己计划要的位、且车还压在这处岔轨上**"的那一处
	 * —— 也就是"我正在用这处道岔"（见 {@link #mmtrYieldTurnoutsWhenStuck} 里的让位豁免）。
	 *
	 * <p>需求位置按**本车自己的计划**算（同一节点只取最先要过的那一程，与 {@code armMmtrPointRun} 同一口径）；
	 * 没有计划、或这处节点不是道岔时 {@link Integer#MIN_VALUE}，判据自然不成立。</p>
	 */
	private boolean mmtrHoldsThePositionItsOwnPlanNeeds(Simulator simulator) {
		if (mmtrMotionPlan == null || mmtrPointOwner.isEmpty()) {
			return false;
		}
		final org.mtr.core.mmtr.point.MmtrPointAuthority authority = simulator.mmtrPointAuthority;
		for (final long[] node : authority.physicalHoldNodesOf(mmtrPointOwner)) {
			final int demand = mmtrDemandAtNode(authority, node);
			if (demand != Integer.MIN_VALUE && authority.holdsThePositionItNeeds(node[0], node[1], node[2], mmtrPointOwner, demand)) {
				return true;
			}
		}
		return false;
	}

	/** 本车计划在这处道岔上要的位置（同一节点取最先要过的那一程；没计划/不是道岔 = MIN_VALUE）。 */
	private int mmtrDemandAtNode(org.mtr.core.mmtr.point.MmtrPointAuthority authority, long[] node) {
		for (final String[] op : mmtrMotionPlan.forkOps) {
			if (Long.parseLong(op[0]) != node[0] || Long.parseLong(op[1]) != node[1] || Long.parseLong(op[2]) != node[2]) {
				continue;
			}
			return authority.turnoutDemand(node[0], node[1], node[2], op[3], Integer.parseInt(op[4]));
		}
		return Integer.MIN_VALUE;
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
		/*
		 * **一处道岔在一趟里只申请"最先要过的那一程"**（notes/137）。
		 *
		 * <p>折返（牵出—推进）会让同一处道岔出现在进路里两次，两程要**互斥的两个位置**。若两程都进
		 * 申请集，后申请的那一程会把它自己的位按上（物理位置只认最后一个需求），而进路判定看的是
		 * **最先要过的那一程** —— 于是"手里按着 1、进路需要 0"，谁也不动：车永远停在自己的出发信号前。
		 * 同一节点只收**第一程**（forkOps 按行进次序）；它越岔之后，第二程自然进入窗口再申请。</p>
		 */
		final java.util.HashSet<String> nearestPassPerNode = new java.util.HashSet<>();
		for (int j = 0; j < plan.forkOps.size(); j++) {
			final String[] op = plan.forkOps.get(j);
			final double forkAbsM = j < plan.forkMeters.size() ? plan.forkMeters.get(j) : Double.NaN;
			final double remainingM = forkAbsM - distanceNow;
			if (remainingM <= 0 || remainingM > MMTR_APPROACH_LOCK_METERS) {
				continue; // already crossed or not yet in the approach window
			}
			if (!nearestPassPerNode.add(op[0] + "," + op[1] + "," + op[2])) {
				continue; // 同一处道岔的**后一程**：等前一程过了再申请
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
		/*
		 * T1（notes/136 §3）：**旧计划按下的位置必须先放掉**。
		 *
		 * <p>持有只在"跨过岔口"或"任务终态"时释放，所以一份被替换掉的计划会把它的位置留在道岔上。
		 * 新计划要的是另一位时，这处道岔就谁也扳不动了：进路判 PENDING（物理位置 ≠ 本车要的位），
		 * 而意图扳岔被"有人物理持有"挡住 —— 持有者正是它自己。现场的样子是"车停在出发信号前不动"。
		 * 这里在申请新的一组之前先放掉**新计划不要的**那些位（同一位保留，不churn、不丢队列位置），
		 * 新计划要的那几位由紧随其后的原子申请按新需要重新拿。</p>
		 */
		mmtrReleaseStaleHoldsForPlan(simulator, authority, owner, plan);
		if (mmtrMotionWalker != null) {
			mmtrMotionWalker.setPointAuthority(authority, owner);
		}
		// S5: publish the movement as a first-class 进路 (route) BEFORE requesting the turnouts, so the
		// signal layer sees PENDING (danger) while the points are being taken and SET the moment every
		// one of them is held - the signal can never show proceed for a route the interlocking has not
		// set. A shunt (调车) keeps its own kind: it is authorised by a subsidiary aspect, not a main one.
		final org.mtr.core.mmtr.route.MmtrRoute publishedRoute = new org.mtr.core.mmtr.route.MmtrRoute(
			getId(), owner,
			mmtrRouteKindOf(mmtrMission, simulator.mmtrShuntAuthorities.active(getId()) != null),
			plan.routeRailHexes, plan.forkOps, plan.targetRailHex, data.getCurrentMillis());
		// T5: 把任务的**计划时刻**交给进路 —— 冲突裁决的第一档（计划早的先走）。
		publishedRoute.setPlannedMillis(mmtrPlannedMillis());
		mmtrRoute = simulator.mmtrRoutes.request(publishedRoute);
		final boolean allForksGranted = requestPendingForksAtomically(authority, owner, data.getCurrentMillis() + MMTR_POINT_REQUEST_MILLIS, mmtrPlannedMillis());
		simulator.mmtrRoutes.refresh(getId(), authority, mmtrPendingPointOps);
		return allForksGranted;
	}

	/**
	 * notes/136 §3：把"新计划在每处道岔上要的位置"整理出来，交给
	 * {@link Simulator#mmtrReleaseStalePhysicalHolds} 放掉旧计划留下的、新计划已经不要的那些位。
	 *
	 * <p>判据只取**新计划自己**的腿号 → 位置（{@code turnoutDemand}），所以"计划没变"时一个都不放：
	 * 列车等联锁时每 tick 都会重新走一遍 {@code armMmtrPointRun}，那期间不能把已经拿到的位丢掉。</p>
	 */
	private void mmtrReleaseStaleHoldsForPlan(Simulator simulator, org.mtr.core.mmtr.point.MmtrPointAuthority authority, String owner, MmtrRunPlanner.Plan plan) {
		if (plan.forkOps.isEmpty()) {
			return;
		}
		final java.util.HashMap<String, Integer> wanted = new java.util.HashMap<>();
		for (final String[] op : plan.forkOps) {
			final String nodeKey = op[0] + "," + op[1] + "," + op[2];
			if (wanted.containsKey(nodeKey)) {
				continue;   // 同一处道岔走两次（折返）：**最先要过的那一程**说了算（notes/137）
			}
			final int demand = authority.turnoutDemand(Long.parseLong(op[0]), Long.parseLong(op[1]), Long.parseLong(op[2]), op[3], Integer.parseInt(op[4]));
			if (demand != Integer.MIN_VALUE) {
				wanted.put(nodeKey, demand);
			}
		}
		simulator.mmtrReleaseStalePhysicalHolds(owner, wanted);
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
	private boolean requestPendingForksAtomically(org.mtr.core.mmtr.point.MmtrPointAuthority authority, String owner, long untilMillis, long priorityMillis) {
		if (mmtrPendingPointOps.isEmpty()) {
			return true;
		}
		return authority.requestAtomically(mmtrPendingPointOps, owner, untilMillis, priorityMillis)
			== org.mtr.core.mmtr.point.MmtrPointAuthority.Result.GRANTED;
	}

	/**
	 * P3 (approach locking): while the auto run is armed, keep the pending request set equal to
	 * **the forks this train needs right now** — rebuilt from the plan every call, not accumulated.
	 *
	 * <p>Far forks are deliberately NOT requested: the leading train must get the point first when it
	 * arrives (the approach window). The one exception is ①: while held at the signal in front of a
	 * block whose far end is an unset turnout the request must still be out, because the train may be a
	 * whole block short of it (up to 187 m in the dev world) and the window would never open.</p>
	 *
	 * <h3>为什么是"重建"而不是"只增不减"</h3>
	 * <p>折返（牵出—推进）让同一处道岔在计划里出现两次，两程要互斥的两个位置。累积式的申请集会把
	 * 两程**都**留在里面（第一程在窗口内时进过一次，之后即使已经越过、或者已经轮到第二程，它还在），
	 * 而权限层按"最先要过的那一程"裁决 —— 于是一个**已经过时的**条目会把当前真正需要的那个挡住，
	 * 车停在自己的出发信号前（notes/137 §1b：现场就是这么卡的）。每 tick 按计划重建 = 申请集永远
	 * 只有"此刻该申请的、每处道岔一条"。</p>
	 */
	private void replenishForkRequests(Simulator simulator) {
		if (mmtrMotionPlan == null || mmtrMotionWalker == null || mmtrMotionPlan.forkOps.isEmpty()) {
			return;
		}
		final double distanceNow = mmtrMotionWalker.distanceM();
		/*
		 * **"车已经不在那条进向轨上了" = 已越过**（2026-09-17 现场修：换端之后车卡在折返咽喉）。
		 *
		 * <h3>现场读数</h3>
		 * <pre>
		 * [interlock] rail=…FEDF(斜线) route=MAIN/**PENDING** rails=4 forks=2
		 *   PENDING: 物理道岔 -176,-60,-253 被 v-8012883479216375661 按在位置 0，本车需要位置 1
		 * 计划 entryRail = 31 m 折返轨；车早已开过那处道岔、现在人在斜线上
		 * </pre>
		 * <p>它的计划是"从 31 m 折返轨出发 → 斜线 → A 线 → 站1/1"，而车**已经越过**那处道岔了。
		 * 但"已越过"这件事只有走行体上报（{@code drainCrossedPointKeys}）才会被记上，这一次没记上
		 * （换端之后车体几何/空间口径整体反过来，走行体的越过上报与计划里的 {@code forkMeters}
		 * 不再可比）—— 于是那条 fork 每 tick 都被重新申请，而它按着的位在**别的车**手里 ⇒
		 * 进路判 PENDING 永远不 SET ⇒ 车停在斜线上不动，尽管它前面那条路（A 线）是空的。</p>
		 *
		 * <h3>判据</h3>
		 * <p>用计划自己的轨序（{@code routeRailHexes}，第 0 项 = 规划时车所在的那根轨）：
		 * fork 的进向轨（{@code op[3]}）在轨序里的位置**早于**车当前轨的位置 ⇒ 那处岔在身后 = 已越过。
		 * 这与"距离"无关，所以不怕换端之后距离口径翻转；查不到（车不在计划轨序里、或轨序里没有那根轨）
		 * 就退回原来的距离判据，行为不变。</p>
		 *
		 * <p>按"已越过"处理的两件事都要做：①记进路（{@code markForkCrossed}）—— 进路层才不会继续
		 * 要求那处岔；②向权限层报一次"这处岔我过了"（{@code mmtrPointRelease}）—— 把自己在那里的
		 * 持有与排队放掉，否则别的车还要在一个**已经没人需要**的队列位置后面等下去。</p>
		 */
		final int currentRailIndex = planRailIndexOf(mmtrMotionPlan, mmtrMotionWalker.railHex());
		final ObjectArrayList<String[]> rebuilt = new ObjectArrayList<>();
		final java.util.HashSet<String> nearestPassPerNode = new java.util.HashSet<>();
		for (int j = 0; j < mmtrMotionPlan.forkOps.size(); j++) {
			final String[] op = mmtrMotionPlan.forkOps.get(j);
			final double forkAbsM = j < mmtrMotionPlan.forkMeters.size() ? mmtrMotionPlan.forkMeters.get(j) : Double.NaN;
			final double remainingM = forkAbsM - distanceNow;
			/*
			 * 退出条件是**"已越过"**，不是"距离已经走完"。
			 *
			 * <p>道岔的持有一致保留到**车尾出清**（notes/101 ③：车尾清岔 + 10 m 清限才释放），
			 * 而进路的"已越过"标记也是那一刻才写的。若在这里按 {@code remainingM <= 0} 把它踢出申请集，
			 * 就会出现一段"既没被申请、也还没算越过"的空窗：物理位置冻在别人（其实是自己上一程）按的
			 * 那一位上，进路判 PENDING，车头正压在岔上不动 —— 实测就是这么卡住的
			 * （车头 (-211.5,-158.5) 正贴着节点 -212,-60,-159）。</p>
			 */
			if (isMmtrForkCrossed(op)) {
				continue; // 已越过（车尾出清）：crossing 时已释放，也不再申请
			}
			if (currentRailIndex >= 0) {
				final int viaIndex = planRailIndexOf(mmtrMotionPlan, op[3]);
				if (viaIndex >= 0 && viaIndex < currentRailIndex) {
					markMmtrForkBehindAsCrossed(simulator, op);
					continue;   // 身后的岔：进路不再要求它，自己在那里也不再持有/排队
				}
			}
			/*
			 * **同一处道岔只申请最先要过的那一程**（notes/137）：折返的两程要互斥的两个位置，
			 * 而一处道岔只有一个位置。plan 的 forkOps 按行进次序，所以"第一个未越过的"就是最近那一程。
			 */
			if (!nearestPassPerNode.add(op[0] + "," + op[1] + "," + op[2])) {
				continue;
			}
			if (remainingM <= MMTR_APPROACH_LOCK_METERS || mmtrSectionAuthorityHold) {
				rebuilt.add(op.clone());
			}
			if (mmtrSectionAuthorityHold) {
				break; // just the next fork ahead
			}
		}
		mmtrPendingPointOps.clear();
		mmtrPendingPointOps.addAll(rebuilt);
	}

	/** 这一程的岔是不是已经被越过（进路登记表记着越过的键，见 {@code MmtrRoute.markForkCrossed}）。 */
	private boolean isMmtrForkCrossed(String[] op) {
		final org.mtr.core.mmtr.route.MmtrRoute route = mmtrRoute;
		return route != null && route.isForkCrossed(op);
	}

	/**
	 * 把一处**已经在身后的**岔按"已越过"处理：记进路 + 向权限层报一次"我过了这处岔"。
	 *
	 * <p>用"车已经不在那条进向轨上"作为判据（见 {@link #replenishForkRequests} 的说明）：
	 * 车既已不在那里，它在权限层按着的位与排队的位置都该放掉，进路层也不该再要求那处岔。</p>
	 */
	private void markMmtrForkBehindAsCrossed(Simulator simulator, String[] op) {
		if (mmtrRoute != null) {
			// 进路的越过键是字符串（节点 + "|" + 进向轨），与 drainMmtrCrossedPoints 同一口径
			mmtrRoute.markForkCrossed(op[0] + "," + op[1] + "," + op[2] + "|" + op[3]);
		}
		if (!mmtrPointOwner.isEmpty()) {
			simulator.mmtrPointRelease(Long.parseLong(op[0]), Long.parseLong(op[1]), Long.parseLong(op[2]), op[3], mmtrPointOwner);
		}
	}

	/** 某根轨在计划的轨序（{@code routeRailHexes}）里的下标；不在里面 = -1。 */
	private static int planRailIndexOf(MmtrRunPlanner.Plan plan, @org.jspecify.annotations.Nullable String railHex) {
		if (plan == null || railHex == null || railHex.isEmpty()) {
			return -1;
		}
		for (int i = 0; i < plan.routeRailHexes.size(); i++) {
			if (railHex.equals(plan.routeRailHexes.get(i))) {
				return i;
			}
		}
		return -1;
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
				markMmtrSignalBlock(leg);
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
	 * 这条腿所在的轨上登记"本车列正占着"——**只走原版 MTR 的每轨通道**（{@code isBlocked}，
	 * 那是 MTR 自己的闭塞语义，游戏内原版信号仍按它走）。
	 *
	 * <p>这里原来还有另一半：B3b 的"每区间一个预留信号色"（沿 {@code MmtrSectionService.TrackSpan}
	 * 逐段 {@code mmtrReserveSignalColor}）。它随 v1 整层删除（notes/166 R4）：MMTR 的**区间占用**现在
	 * 只有一份来源 —— Level 1 轨道区间读占用树，且带 {@code excludeVehicleId} 能排除本车自己。
	 * 颜色通道认不出这团影子是谁的，正是"列车被自己的影子扣住"的载体（notes/112 §4、notes/152）。</p>
	 */
	private void markMmtrSignalBlock(PathData leg) {
		leg.getRail().isBlocked(id, Rail.BlockReservation.CURRENTLY_RESERVE);
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
		/*
		 * 停车锚点校正（notes/155）：车头一进入锚点那根轨，就把"估算的停车里程"换成**量出来的** ——
		 * 锚点在脚下这根轨上，剩余里程 = 轨长×比例 − 当前偏移，与进路怎么绕无关。
		 * 这一步必须在**本 tick 推进之前**做，否则跨过站台那一 tick 用的还是估算值。
		 */
		mmtrResolveStopAnchor();
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
			double remaining = brakeTargetM - mmtrMotionWalker.distanceM();
			/*
			 * 有锚点、且车头已经在锚点那根轨上时，**以锚点为准**算"还剩多少"（notes/155）：
			 * 累计里程是算出来的（会偏），锚点是量出来的。少了这一句，累计里程偏长时车会冲过停车点，
			 * 偏短时又会提前停死（然后在站台外干等）。
			 */
			final double toAnchorM = mmtrRemainingToStopAnchor();
			if (toAnchorM < Double.MAX_VALUE) {
				remaining = Math.min(remaining, toAnchorM);
			}
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
			/*
			 * 到位判据（notes/155）：**累计里程到了 或 锚点到了**。
			 *
			 * <p>锚点那一句是关键：累计里程是估的（"当时的位置 + 算出来的进路长度"）——
			 * 估长了车会冲过站台（现场 1298 m 的目标把车带到 1375 m），估短了车会提前停死。
			 * 锚点是"车头在哪根轨、轨上多深"，没有估算成分，所以停得准；
			 * 锚点轨上的硬夹紧（见下面的 remaining 计算）保证车头不会越过它。</p>
			 */
			final boolean reachedStop = railProgress >= mmtrMotionStopTargetM - 1e-6 || mmtrReachedStopAnchor();
			if (stopTargetActive && reachedStop) {
				speed = 0;
				mmtrMotionArriveAtStopTarget();
			} else if (mmtrBlockStopM < Double.MAX_VALUE / 2 && railProgress >= mmtrBlockStopM - 1e-6) {
				/*
				 * Arrived exactly at the occupancy stop (rail ahead occupied): rest and wait for it
				 * to clear - not a terminal state, never opens doors, never reports a task arrival.
				 *
				 * **但停车点就在眼前/已经在身后时不许在这儿等**（2026-09-16 实测）：闭塞停车点是"车头前方
				 * 那段区间被别人占了"的位置，它可能落在本车自己的停车点**之后一点点**（实测：目标 784.4m、
				 * 闭塞停车点 784.38m）。这时车停在闭塞点上，`mmtrBlockedWaiting` 只会在"刹车目标比车头更远"
				 * 时自动解除 —— 而刹车目标 = min(停车点, 闭塞点) 已经在身后 ⇒ 永远不解除，车就停在自己的终点上
				 * 无限等一个根本不需要通过的闭塞，任务也永远收不到"到点"（1↔3 站循环的最后一步就死在这里）。
				 */
				speed = 0;
				if (stopTargetActive && mmtrMotionStopTargetM - railProgress <= MMTR_ARRIVAL_EPS_M) {
					mmtrMotionArriveAtStopTarget();
				} else if (!mmtrBlockedWaiting) {
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
			final boolean stopTargetConsumed = stopTargetActive && mmtrMotionStopTargetM - mmtrMotionWalker.distanceM() <= 1e-6;
			if (stopTargetConsumed && !mmtrMotionStopRailHex.isEmpty() && !mmtrReachedStopAnchor() && !mmtrBlockedWaiting) {
				/*
				 * **锚点没到、累计里程却说到了**（notes/155）：里程是估的，估短了车就提前停死在半路
				 * （站台外十几米）。不许在这儿干等 —— 放掉停车目标与 auto，任务下一 tick 会用当前位置
				 * 重新自臂，重算一条到锚点的进路。
				 */
				System.out.println("[MMTR-DRV] 停车里程估短了（车停在 " + Math.round(mmtrMotionWalker.distanceM())
					+ "m，锚点在 " + mmtrMotionStopRailHex + " 上）—— 放掉目标重新规划");				mmtrMotionAuto = false;
				mmtrMotionStopTargetM = -1;
				mmtrMotionStopRailHex = "";
				mmtrMotionStopFraction = -1;
				mmtrMotionStoppedAtTarget = false;
			} else if (stopTargetConsumed) {
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

	/**
	 * 把司机控制状态折算成 **legacy 单手柄读数**（`powerLevel`）：MTR 自带的仪表与部分 HUD 仍读它，
	 * 三手柄机车必须给出等价的"正=牵引 / 负=制动"，否则仪表永远显示 N。
	 *
	 * <p>优先权与控制器的合成顺序一致：紧急 &gt; 气制动位置 &gt; 油门手柄（牵引 / 电阻制动）。
	 * 非三手柄车底沿用原来的档位折算，一个字节不改。</p>
	 */
	private int mmtrLegacyPowerLevelFromControl(ControlState control) {
		final org.mtr.core.mmtr.ThreeHandleSpec handles = mmtrConsistType == null ? null : mmtrConsistType.getHandles();
		if (handles == null) {
			return control.getThrottleNotch() > 0 ? control.getThrottleNotch() : control.getBrakeNotch() > 0 ? -control.getBrakeNotch() : 0;
		}
		final int brakePosition = handles.clampBrakePosition(control.getBrakeNotch());
		if (control.isEmergency() || handles.isEmergencyPosition(brakePosition)) {
			return -MAX_POWER_LEVEL - 1;
		}
		if (brakePosition > org.mtr.core.mmtr.ThreeHandleSpec.runningPosition()) {
			return -Math.max(1, Math.min(MAX_POWER_LEVEL, brakePosition));
		}
		final int driveHandle = handles.clampDriveHandle(control.getDriveHandle());
		final double tractionRatio = handles.tractionRatio(driveHandle);
		if (tractionRatio > 0) {
			return Math.max(1, (int) Math.round(tractionRatio * MAX_POWER_LEVEL));
		}
		final double rheostaticRatio = handles.rheostaticRatio(driveHandle);
		return rheostaticRatio > 0 ? -Math.max(1, (int) Math.round(rheostaticRatio * MAX_POWER_LEVEL)) : 0;
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
		setMmtrMotionStopTarget(cumulativeDistanceM, "", -1, openDoors);
	}

	/**
	 * 带**停车锚点**的自臂（notes/155）：除了累计里程（刹车的粗略目标），还给出"停在哪根轨、轨上多深处"。
	 *
	 * <p>到位判据以锚点为准（见 {@link #mmtrReachedStopAnchor()}）：累计里程算偏了也不会停错地方 ——
	 * 停偏了现场就是"车冲过站台才开门"。{@code stopRailHex} 为空时退回只按累计里程的老口径
	 * （既有调用点/用例不受影响）。</p>
	 */
	public void setMmtrMotionStopTarget(double cumulativeDistanceM, String stopRailHex, double stopFraction, boolean openDoors) {
		if (isClientside || mmtrMotionWalker == null) {
			return;
		}
		final boolean wasStoppedAtTarget = mmtrMotionStoppedAtTarget;
		mmtrMotionStopTargetM = cumulativeDistanceM;
		mmtrMotionStopRailHex = stopRailHex == null ? "" : stopRailHex;
		mmtrMotionStopFraction = stopFraction;
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

	/**
	 * 车头是不是**已经站在停车锚点上**（notes/155）：在锚点那根轨上，且轨内偏移到了锚点比例处。
	 *
	 * <p>没有锚点时恒 false（调用方退回累计里程判据）。判据只看"车头在哪、走了多深"——
	 * 与进路怎么绕、累计里程估得准不准**无关**，所以重规划不会把停车点挪走。</p>
	 *
	 * <p><b>先看还有没有停车目标</b>：目标被清掉（任务完成/换端/收回）之后锚点会留在字段里，
	 * 那种"陈旧的锚点"绝不能再说话 —— 现场实测它会每 tick 把已经清掉的目标又"校正"回一个停车点
	 * （日志刷屏，而且等于凭空给车派了个停车目标）。</p>
	 */
	private boolean mmtrReachedStopAnchor() {
		if (mmtrMotionStopTargetM < 0 || mmtrMotionStopRailHex.isEmpty() || mmtrMotionStopFraction < 0 || mmtrMotionWalker == null) {
			return false;
		}
		if (!mmtrMotionStopRailHex.equals(mmtrMotionWalker.railHex())) {
			return false;
		}
		final double anchorOffset = mmtrMotionStopFraction * mmtrMotionWalker.currentRailLengthM();
		return mmtrTravelledOnRailM() >= anchorOffset - 1e-6;
	}

	/**
	 * 车头在**当前轨上已经走了多少米**（从它进入这根轨的那一端量起，按行车方向）。
	 *
	 * <p>为什么不能直接用 {@code walker.offsetM()}：锚点的比例是"**从进站端算起**、1.0 = 远端"
	 * （见 {@link org.mtr.core.mmtr.MmtrRunPlanner#planToRail} 的口径），而编组走行体
	 * （{@code MmtrConsistWalker}）的 {@code offsetM()} 是**沿脊线 A→B 量的弧长**：车朝 A 端开时它是递减的
	 * （还剩多少米，而不是已经走了多少米）。两者混用会让 {@code distanceM() - offsetM() + 比例×轨长}
	 * 每 tick 多算一个"剩余距离" ⇒ 停车点被"按锚点校正"一路往前推、车冲过站台
	 * （2026-09-16 实测：1↔3 站循环在 3 站 1 台冲过站台约 10 m，日志里连着三次 {@code 停车点按锚点校正} 都是往前推）。
	 * {@link org.mtr.core.mmtr.MmtrRunPlanner#remainingToAheadNodeM} 是引擎里既有的方向感知口径，这里照它换算。</p>
	 */
	private double mmtrTravelledOnRailM() {
		return Math.max(0, mmtrMotionWalker.currentRailLengthM() - org.mtr.core.mmtr.MmtrRunPlanner.remainingToAheadNodeM(mmtrMotionWalker));
	}

	/** @return 当前这次自臂有没有停车锚点（诊断用；空 = 只按累计里程）。 */
	public boolean hasMmtrMotionStopAnchor() {
		return !mmtrMotionStopRailHex.isEmpty() && mmtrMotionStopFraction >= 0;
	}

	/** 车头在这根轨上时，到停车锚点还有多少米（不在锚点轨上返回 {@code Double.MAX_VALUE}）。 */	private double mmtrRemainingToStopAnchor() {
		if (mmtrMotionStopTargetM < 0 || mmtrMotionStopRailHex.isEmpty() || mmtrMotionStopFraction < 0 || mmtrMotionWalker == null
			|| !mmtrMotionStopRailHex.equals(mmtrMotionWalker.railHex())) {
			return Double.MAX_VALUE;
		}
		return mmtrMotionStopFraction * mmtrMotionWalker.currentRailLengthM() - mmtrTravelledOnRailM();
	}

	/**
	 * 车头已经站在锚点那根轨上时，把停车目标换成**量出来的**累计里程（notes/155）。
	 *
	 * <p>自臂时给的累计里程 = "当时的位置 + 算出来的进路长度"，与真实走行差多少全看那次估算；
	 * 而锚点（哪根轨、轨上多深）一旦脚踩上去就是精确的：{@code 本轨起点累计里程 = 当前累计 − 当前偏移}，
	 * 加上"轨长 × 比例"就是锚点的累计里程。换过之后到位判据仍然是那一句
	 * {@code railProgress >= 目标}，但目标已经不再带估算误差。</p>
	 */
	private void mmtrResolveStopAnchor() {
		if (mmtrMotionStopTargetM < 0 || mmtrMotionStopRailHex.isEmpty() || mmtrMotionStopFraction < 0 || mmtrMotionWalker == null
			|| !mmtrMotionStopRailHex.equals(mmtrMotionWalker.railHex())) {
			return;
		}
		final double exactM = mmtrMotionWalker.distanceM() - mmtrTravelledOnRailM()
			+ mmtrMotionStopFraction * mmtrMotionWalker.currentRailLengthM();
		if (exactM <= mmtrMotionWalker.distanceM() + 1e-9 || Math.abs(exactM - mmtrMotionStopTargetM) <= 1e-9) {
			return;   // 已经过了锚点，或本来就一样：不动
		}
		System.out.println("[MMTR-DRV] 停车点按锚点校正 " + Math.round(mmtrMotionStopTargetM * 100.0) / 100.0
			+ "m → " + Math.round(exactM * 100.0) / 100.0 + "m（在 " + mmtrMotionStopRailHex + " 上，比例 "
			+ Math.round(mmtrMotionStopFraction * 100.0) / 100.0 + "）");
		mmtrMotionStopTargetM = exactM;
		mmtrRunStopTarget = exactM;
		vehicleExtraData.mmtrMarkSyncDirty();
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
		// 车底是**按车**解析的（车型映射 / 车厢声明，见 MmtrCarTypeResolver），不是按维度：
		// 连挂或解挂改了车列 ⇒ "说话的车"可能换了 ⇒ 返回 true 并作废缓存的控制器。
		final boolean carTypeChanged = !isClientside && mmtrRefreshConsistTypeFromCars();
		if (mmtrDriveController != null && !carTypeChanged) {
			return true;
		}
		if (isClientside) {
			mmtrConsistType = createMirrorConsistTypeFromSync();
		}
		if (mmtrConsistType != null) {
			mmtrDriveController = switch (mmtrConsistType.getControlMode()) {
				case NOTCHED -> new org.mtr.core.mmtr.NotchedDriveController();
				case STEPLESS -> new org.mtr.core.mmtr.SteplessDriveController();
				case AIR_BRAKE -> new org.mtr.core.mmtr.AirBrakeController();
				case THREE_HANDLE -> new org.mtr.core.mmtr.ThreeHandleDriveController();
				default -> null;
			};
			// 车型一确定就把手柄规格写进镜像：否则"还没人开过"的车客户端拿不到位置表
			// （客户端没有 consist-types.json，只能靠这条字符串知道自己有几档油门、制动几位）。
			if (!isClientside) {
				final String specText = mmtrConsistType.getHandles() == null ? "" : mmtrConsistType.getHandles().encode();
				if (!specText.equals(mmtrHandleSpec)) {
					mmtrHandleSpec = specText;
					vehicleExtraData.mmtrMarkSyncDirty();
				}
			}
			if (isClientside && mmtrDriveController instanceof final org.mtr.core.mmtr.AirBrakeStateful airBrakeStateful) {
				// Seed the fresh mirror controller with the authoritative air state from the snapshot.
				// (按能力判断而不是按具体类：三手柄控制器同样带气制动状态。)
				airBrakeStateful.setState(mmtrPipePressure, mmtrBrakeCylinderPressure);
			}
		} else {
			mmtrDriveController = null;
		}
		return mmtrDriveController != null;
	}

	/**
	 * 服务端：按本车车列解析操纵车底（{@link org.mtr.core.mmtr.MmtrCarTypeResolver}）。
	 *
	 * @return 说话的车换人了（车列变了）⇒ 调用方必须重建控制器与编组视图
	 */
	private boolean mmtrRefreshConsistTypeFromCars() {
		if (!(data instanceof final Simulator simulator) || simulator.mmtrConsistTypes == null) {
			return false;
		}
		final int carCount = vehicleExtraData.immutableVehicleCars.size();
		// 便宜的车列变化判据：连挂/解挂/重建编组都会改**车节数**，所以先比一个 int 就退出 ——
		// 这个方法在每 tick 的驱动路径上，解析一次要拼字符串，不能无条件跑。
		// （已知边界：同样长度的两车"换车"不会触发重解；那种情形在本仓只出现在手工改模板，配置改动本来就要重启。）
		if (mmtrResolvedCarTypeKey != null && carCount == mmtrResolvedCarCount) {
			return false;
		}
		mmtrResolvedCarCount = carCount;
		final org.mtr.core.mmtr.MmtrCarTypeResolver.Resolution resolution = org.mtr.core.mmtr.MmtrCarTypeResolver.resolve(
			vehicleExtraData.immutableVehicleCars, simulator.mmtrConsistTypes, simulator.mmtrDefaultConsistTypeId);
		if (resolution.key().equals(mmtrResolvedCarTypeKey)) {
			return false;
		}
		mmtrResolvedCarTypeKey = resolution.key();
		mmtrConsistType = resolution.consistTypeId() == null ? null : simulator.mmtrConsistTypes.get(resolution.consistTypeId());
		// 说话的车换了 ⇒ 控制器（含气制动状态）与编组视图都要重建，否则会拿旧参数继续跑。
		mmtrDriveController = null;
		mmtrComposition = null;
		return true;
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
			mmtrMassRatio,
			// 三手柄规格走紧凑字符串镜像（位置表是数组，逐帧传不划算；只有车型变化时它才变）。
			org.mtr.core.mmtr.ThreeHandleSpec.decode(mmtrHandleSpec)
		);
	}

	/** Client-side: rebuild the authoritative ControlState from the mirrored snapshot fields. */
	private ControlState createMirrorControlStateFromSync() {
		return new ControlState()
			.setThrottleNotch((int) mmtrThrottleNotch).setBrakeNotch((int) mmtrBrakeNotch).setReverser((int) mmtrReverser)
			.setDriveHandle((int) mmtrDriveHandle).setCruiseSpeedKmh((int) mmtrCruiseKmh)
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
			mmtrDriveHandle = mmtrActiveControl.getDriveHandle();
			mmtrCruiseKmh = mmtrActiveControl.getCruiseSpeedKmh();
			mmtrReverser = mmtrActiveControl.getReverser();
			mmtrThrottleAxis = mmtrActiveControl.getThrottleAxis();
			mmtrBrakeAxis = mmtrActiveControl.getBrakeAxis();
			mmtrEmergency = mmtrActiveControl.isEmergency();
		}
		if (mmtrConsistType != null) {
			mmtrPowerNotches = mmtrConsistType.getPowerNotches();
			mmtrBrakeNotches = mmtrConsistType.getBrakeNotches();
			mmtrHandleSpec = mmtrConsistType.getHandles() == null ? "" : mmtrConsistType.getHandles().encode();
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
		if (mmtrDriveController instanceof final org.mtr.core.mmtr.AirBrakeStateful airBrakeStateful) {
			mmtrPipePressure = airBrakeStateful.getPipePressure();
			mmtrBrakeCylinderPressure = airBrakeStateful.getBrakeCylinderPressure();
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
	/** 三手柄：油门手柄位置（±97，0 = 关闭）。非三手柄车底恒为 0。 */
	public int getMmtrDriveHandleFromSync() { return (int) mmtrDriveHandle; }
	/** 三手柄：定速巡航设定值（km/h，0 = 关闭）。 */
	public int getMmtrCruiseKmhFromSync() { return (int) mmtrCruiseKmh; }
	/**
	 * 三手柄：本车操纵规格的紧凑字符串（客户端没有 consist-types.json，靠它把位置表镜像过来）。
	 * 空串 = 本车不是三手柄车底。
	 */
	public String getMmtrHandleSpecFromSync() { return mmtrHandleSpec == null ? "" : mmtrHandleSpec; }
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
					vehicleExtraData.setPowerLevel(mmtrLegacyPowerLevelFromControl(mmtrControl));
				}
				updateMmtrSyncFields();
				if (speed != mmtrSpeed || mmtrDistanceTravelled > 0) {
					org.mtr.core.mmtr.MmtrTrace.log("[MMTR-DRV] mode=" + mmtrConsistType.getControlMode() + " throttle=" + mmtrControl.getThrottleNotch() + " brake=" + mmtrControl.getBrakeNotch() + " drive=" + mmtrControl.getDriveHandle() + " afb=" + mmtrControl.getCruiseSpeedKmh() + " speed=" + speed + "->" + mmtrSpeed + " dist=" + mmtrResult.distanceMeters + " prot=" + mmtrProtectionNow);
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
			/*
			 * 每辆车每脏 tick **只做一次**这一份（notes/174）：原来它是在"每辆车 × 每个可见客户端"的
			 * 循环里做的，而那份数据（快照与补丁）与客户端**无关** —— 深拷贝又是 JSON 往返（60 µs/次）。
			 * 现在一份共享给所有可见客户端，并且只在静态字段没变时退化成几百字节的稀疏补丁。
			 */
			final JsonObject syncPayload = needsUpdate ? mmtrBuildSyncPayload(0) : null;
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
					client.update(this, needsUpdate, 0, syncPayload);
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
				final int pathUpdateIndex = transportMode.continuousMovement ? 0 : Math.max(0, index + 1);
				// notes/174: 这一份每辆车每脏 tick 只做一次，且静态没变时退化成稀疏补丁（原来每客户端一次 3.5 KB）。
				final JsonObject syncPayload = needsUpdate ? mmtrBuildSyncPayload(pathUpdateIndex) : null;
				simulator.clients.forEach(client -> {
					final Position position = client.getPosition();
					final double updateRadius = client.getUpdateRadius();
					if ((minMaxPositions[0] == null || minMaxPositions[1] == null) ? siding.area.inArea(position, updateRadius) : Utilities.isBetween(position, minMaxPositions[0], minMaxPositions[1], updateRadius) || !closeToDepot() && vehicleExtraData.hasRidingEntity(client.uuid)) {
						client.update(this, needsUpdate, pathUpdateIndex, syncPayload);
					}
				});
			}

			vehicleExtraData.setRoutePlatformInfo(siding.area, currentIndex);
		}
	}

	/**
	 * **一辆车这一拍该发什么**（notes/174）：整份快照（静态变了 / 第一次）还是稀疏补丁（只有动态字段变了）。
	 *
	 * <p>判据与"上一次发出去的那一份"比（每辆车记一份，所有客户端共用同一条流），
	 * 白名单见 {@link org.mtr.core.operation.VehicleSyncPatch#isDynamicKey}：**没列到的一律当静态**，
	 * 于是"漏字段"的后果是"多发一份快照"（慢一点），而不是"客户端那个字段永远不更新"（静默错）。
	 * 另外每 {@link org.mtr.core.operation.VehicleSyncPatch#FULL_RESYNC_MILLIS} 毫秒强制整份一次兜底。</p>
	 *
	 * @return 补丁（只含变化的字段）；{@code null} = 发整份快照；空对象 = 其实没有变化
	 */
	private @Nullable JsonObject mmtrBuildSyncPayload(int pathUpdateIndex) {
		if (!Boolean.parseBoolean(System.getProperty("mmtr.sync.patches", "true")) || data.getCurrentMillis() >= mmtrSyncFullResendAtMillis) {
			mmtrSyncLastSent = null;
		}

		final JsonObject current = new JsonObject();
		current.add("vehicle", Utilities.getJsonObjectFromData(this));
		current.add("data", Utilities.getJsonObjectFromData(vehicleExtraData.copy(pathUpdateIndex)));
		final JsonObject patch = org.mtr.core.operation.VehicleSyncPatch.patchOf(mmtrSyncLastSent, current);
		mmtrSyncLastSent = current;
		if (patch == null) {
			// 发了整份 ⇒ 下一次兜底重算从现在开始
			mmtrSyncFullResendAtMillis = data.getCurrentMillis() + org.mtr.core.operation.VehicleSyncPatch.FULL_RESYNC_MILLIS;
		}
		return patch;
	}

	/**
	 * 发给客户端的**上一次那一份**（快照或补丁算出来的基准）与"下一次强制整份"的时刻（notes/174）。
	 * 只在模拟线程上用。
	 */
	private @Nullable JsonObject mmtrSyncLastSent;
	private long mmtrSyncFullResendAtMillis;

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
	 * <h3>MMTR 走行中的车**不再走这一道闸**（2026-09-17 现场：北段 S1/S2 双向占用测试班对向扣死）</h3>
	 * <p>这是 MTR 原版的每轨闭塞：车到信号区段前先看**整段**之后是否清空，不清就停；预留走
	 * {@code Rail.isBlocked} 的 {@code signalColors} 通道。它的粒度是**一根轨 / 一个颜色组**，
	 * **不分行车方向** —— 于是"两列车即将在单线上对向相遇"时两边都认为前方被占，
	 * **互相把对方停住**（用户现场原话："MTR 老逻辑会让即将碰上的列车互斥停下"）。</p>
	 *
	 * <p>而 MMTR 的两层区间模型（L1 轨道区间 + L2 行车区间，带 {@code excludeVehicleId}、带方向、
	 * 带岔区净空 10 m、带敌对进路表）**本来就是这一层的替代品**，而且是唯一带方向的。
	 * 两套并行只剩一种结果：MMTR 判"可以走"、老逻辑判"停下"，车停在一个**没有任何读数能解释**的位置
	 * （{@code authority.reason} 写着"绿灯：无约束"，车却不动）。所以：</p>
	 * <ul>
	 *   <li><b>MMTR 走行中的车</b>（有走行体、由 MMTR 规划与授权）⇒ 本闸**不参与停车判定**，
	 *       停车只由 MMTR 的区间占用 / 授权 / 岔区净空 / 敌对进路决定；</li>
	 *   <li><b>其余（原版/旧路径的车）</b>⇒ 逐位保持 MTR 原语义，行为不变。</li>
	 * </ul>
	 * <p>预留本身（{@code CURRENTLY_RESERVE}）**保留**：游戏内原版信号仍按那条颜色通道显示占用，
	 * 这里只关掉"它能不能让车停下"这一半。</p>
	 *
	 * @return if the vehicle should stop
	 */
	private boolean checkAndBlockSignal(int currentIndex, ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions, boolean reserveRail, boolean secondPass) {
		if (isMmtrMotion()) {
			// 有走行体 = MMTR 在开这列车：区间/授权层说了算（见上面说明）；预留照旧，停车不参与
			vehicleExtraData.immutablePath.get(currentIndex).isSignalBlocked(id, Rail.BlockReservation.CURRENTLY_RESERVE);
			return false;
		}
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
			|| boundaryM < Double.MAX_VALUE / 2 && boundaryM - mmtrMotionWalker.distanceM() <= MMTR_AWS_TRIGGER_LEAD_M + 1e-9;		if (!restricted) {
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
		final Position node = org.mtr.core.mmtr.MmtrRunPlanner.travelAheadNode(mmtrMotionWalker);
		if (node == null) {
			return null;
		}
		final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<Position, Rail> neighbours = simulator.positionsToRail.get(node);
		if (neighbours == null || neighbours.size() < 3) {
			return null;
		}
		/*
		 * **道岔那三条轨整根都算"侧面防护"**（2026-09-16 现场修：北端两班车相持）。
		 *
		 * <p>原来只看"节点起 {@link #MMTR_JUNCTION_CLEARANCE_M} m"这条窗。窗只有 10 m，而岔轨有 31~36 m：
		 * 另一班车只要**走深一点**（>10 m）就落在窗外 ⇒ 本节判"岔区干净" ⇒ 我从另一条腿开进去 ⇒
		 * 两班车各占一条腿、互相把对方的出路堵死（现场：一班压在岔股上、一班按着正线，谁也走不了）。
		 *
		 * <p>真道岔的几何是**一个整体**：只要道岔的岔轨上还有别的车，"从别的腿进岔"就该被拦在岔前
		 * （这就是联锁的侧面防护）。三条排除，缺一条都会误挡：
		 * ① 我自己现在这条轨不算；② **我接下来要走的那条轨不算**（那是"跟车"，距离由正常闭塞管）；
		 * ③ **登记过的股道/站台不算**（车停在自己股道里是"停着"而不是"占着岔" —— 少了这条，
		 * 两列车都出不了库：实测把 {@code MmtrRouteConflictTests} 的两条用例挂掉）。</p>
		 *
		 * <p>只影响**行车**（本车自己的停车点），不动灯色/显示口径 —— 显示那边仍按 10 m 净空窗判
		 * （否则会退化成"车在岔轨上灯就永远红"，用户已经报过这个现象）。</p>
		 */
		final boolean modelledTurnout = simulator.mmtrTurnout(node.getX(), node.getY(), node.getZ()) != null;
		final Rail nextRailForExclusion = mmtrMotionWalker.peekNextRail();
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
		final MmtrSectionService.TrackSpan current = simulator.mmtrSections.trackSpanAt(rail.getHexId(), Math.max(0, Math.min(railLength - 1e-6, headArc)));
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
			// 排除三条（见上面的说明）：我脚下这条、我接下来要走的那条（跟车）、登记过的股道/站台（停着）。
			if (other == rail || other == nextRailForExclusion || other.isSiding() || other.isPlatform()) {
				continue;
			}
			// The clearance window is the first MMTR_JUNCTION_CLEARANCE_M metres of every rail meeting at
			// the node, measured from the node (each rail's own ordered-1 arc space); at a real turnout
			// (modelled) the WHOLE rail counts, so a train that has driven 10+ m in cannot be met head-on
			// by a movement entering from another leg.
			final double nodeArc = MmtrSectionGeometry.arcOfNode(other, node);
			if (Double.isNaN(nodeArc)) {
				continue;
			}
			final double windowFrom = modelledTurnout ? 0 : nodeArc <= 1e-9 ? 0 : Math.max(0, otherLength - MMTR_JUNCTION_CLEARANCE_M);
			final double windowTo = modelledTurnout ? otherLength : nodeArc <= 1e-9 ? Math.min(otherLength, MMTR_JUNCTION_CLEARANCE_M) : otherLength;
			if (windowTo - windowFrom > 1e-9 && blockHasExternalOccupancy(other, windowFrom, windowTo, vehiclePositions)) {
				// Walker-space distance to the ahead node：从车头到前方节点还有多少米（方向感知口径）。
				final double toNode = Math.max(0, railLength - mmtrTravelledOnRailM());
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
		final MmtrSectionService.TrackSpan current = simulator.mmtrSections.trackSpanAt(rail.getHexId(), Math.max(0, Math.min(railLength - 1e-6, headArc)));
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
			entryArc = MmtrSectionGeometry.arcOfNode(entryRail, mmtrMotionWalker.aheadNode());
			if (Double.isNaN(entryArc)) {
				return null;
			}
		} else {
			entryRail = rail;
			entryArc = towardHigherArc ? current.arcToM : current.arcFromM;
		}
		final MmtrSectionService.TrackSpan entrySection = simulator.mmtrSections.trackSpanAt(entryRail.getHexId(), entryArc);
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
		final org.mtr.core.mmtr.signal.MmtrSectionService service = simulator.mmtrSections;
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

		final MmtrSectionService.TrackSpan current = simulator.mmtrSections.trackSpanAt(rail.getHexId(), Math.max(0, Math.min(railLength - 1e-6, headArc)));
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
			final MmtrSectionService.TrackSpan next = simulator.mmtrSections.trackSpanAt(rail.getHexId(), probeArc);
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
		final double entryArc = MmtrSectionGeometry.arcOfNode(nextRail, mmtrMotionWalker.aheadNode());
		if (Double.isNaN(entryArc)) {
			return null;
		}
		final double nextLength = nextRail.railMath.getLength();
		final MmtrSectionService.TrackSpan next = simulator.mmtrSections.trackSpanAt(nextRail.getHexId(), Math.max(0, Math.min(nextLength - 1e-6, entryArc)));
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