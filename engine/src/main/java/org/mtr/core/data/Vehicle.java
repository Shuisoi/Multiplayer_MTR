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
import org.mtr.core.mmtr.MmtrLightSwitch;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.mmtr.MmtrProtection;
import org.mtr.core.mmtr.MmtrRegime;
import org.mtr.core.mmtr.MmtrRunPlanner;
import org.mtr.core.mmtr.MmtrSupport;
import org.mtr.core.mmtr.consist.MmtrCabState;
import org.mtr.core.mmtr.consist.MmtrConsistBody;
import org.mtr.core.mmtr.consist.MmtrConsistWalker;
import org.mtr.core.mmtr.physics.DynamicsEnvelope;
import org.mtr.core.mmtr.point.MmtrPoint;
import org.mtr.core.mmtr.point.MmtrPointRegistry;
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
	/** 诊断限频（"操纵被拒"与"手柄语义不匹配"各一条，见 notes/216）。 */
	private static final long MMTR_DIAG_LOG_INTERVAL_MILLIS = 2000;
	private String mmtrLastRefusalReason = "";
	private long mmtrLastRefusalLogMillis;
	private String mmtrLastMismatchNote = "";
	private long mmtrLastMismatchLogMillis;
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
	/** notes/369 §8：上一拍写进镜像的那批"没有别的标脏点"的显示读数（见 {@link MmtrDisplayMirror}）。 */
	private @Nullable MmtrDisplayMirror mmtrDisplayMirror;	/**
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
	 * occupancy. While it is set, the approach-locking window is extended to the fork that actually
	 * blocks this movement (see {@link #replenishForkRequests}): a train held at the signal before a
	 * long block would otherwise never come within the 120 m request window of the turnout it waits for.
	 */
	private boolean mmtrSectionAuthorityHold = false;
	/**
	 * **① 扣住本车的那一处道岔**（{@code "x,y,z"}；空 = 不是这条规则扣的）。
	 *
	 * <p>为什么必须记下来（2026-09-26 全天卡死）：① 的判据是"**要进的那个区块的出口**是选不出腿的道岔"，
	 * 而区块可以跨很多根轨（现场：下水 1 台东端的岔股进路只有 80 m，出口却在下一处道岔）。
	 * 只按"最近一处道岔"去申请时，真正把车扣住的那一处永远收不到申请 —— 车不动，它也就永远走不进
	 * 120 m 接近锁闭窗口，谁也不会去扳它（现场读数：一列停在 2659.23 m 一米不动，后面 15 份作业单
	 * 全部卡在出库步）。记下节点键之后，{@link #replenishForkRequests} 能把它一起申请出去。</p>
	 */
	private String mmtrSectionAuthorityHoldNodeKey = "";
	/** T3: 车被**行车许可**扣住（红灯 / 自己的进路没设好）—— 与"区间出口是未设道岔"是两种不同的等待。 */
	private boolean mmtrSignalAuthorityHold = false;
	private String mmtrSignalAuthorityReason = "";
	/**
	 * **司机优先**（用户口径 2026-09-21）：「只要司机能上车，那么什么都阻挡不了他开车」。
	 *
	 * <p>于是行车许可 / 占用 / 岔区这些闸门对**手动车**不再把速度钉成 0（{@code mmtrBlockedWaiting} 那一支
	 * 直接 {@code speed = 0} 是修前的做法，见 notes/217）—— 闯过停车点只**触发紧急制动**
	 * （{@link #mmtrProtection}），而紧急制动**可以按响应键解除**（{@link #applyMmtrControl} 收到
	 * acknowledge 时释放）。唯一还硬停在 0 的是**道岔物理位置不允许该走向**（会脱轨，走行器拒绝推进）。</p>
	 *
	 * <p>{@code mmtrAuthorityReleased} = "当前这一处违界已经放行过"：手动车解除紧急制动后继续往前开，
	 * 同一处停车点不再反复触发（否则一按解除就被立刻再刹一次，永远走不动 —— 修前 AWS SPAD 的死循环形态）。
	 * 它在前方出现**新的**停车点时重新武装。</p>
	 */
	private boolean mmtrAuthorityReleased;
	/**
	 * 当前的 {@link #mmtrProtection} 是不是**司机越界**那一路触发的（而不是 AWS 报警超时的 SPAD）。
	 *
	 * <p>两者共用一个紧急制动通道，但"怎么解除"不同：AWS SPAD 有 10 s 自动解锁（既有行为，一字未改），
	 * 而司机越界是**真车 SCR 的口径** —— 司机不按响应键就一直施加着；按了就放行这一处。
	 * 少了这个标记，"10 s 自动解锁"会顺手把越界也标记成"已放行"，司机于是可以无视红灯一路开过去。</p>
	 */
	private boolean mmtrAuthorityTripped;

	/** Short reason for the block-stop log line (the operator reads these in the server log). */
	private String mmtrBlockStopReason() {
		if (mmtrSignalAuthorityHold) {
			return mmtrSignalAuthorityReason;
		}
		if (mmtrSectionAuthorityHold) {
			// 点名到"是哪一处道岔"：只说 "unset turnout" 时，操作者（和排查的人）无法判断是哪一处、
			// 也不知道该看谁的持有（2026-09-26 现场就为此查了一轮）。
			return "block ahead ends at an unset turnout"
				+ (mmtrSectionAuthorityHoldNodeKey.isEmpty() ? "" : " at " + mmtrSectionAuthorityHoldNodeKey);
		}
		return "block ahead occupied";
	}

	/**
	 * **诊断：这列车此刻被哪一道闸门按住**（空串 = 没被按住）。
	 *
	 * <p>四条停车规则里哪一条先说"停"是完全看不见的 —— 现场只能看到"车不动"，然后靠猜。
	 * 这个 getter 把 {@link #mmtrBlockStopReason()} 那一句（本来就是写给操作者看的）交给诊断工具与
	 * 联锁报告，于是"谁把谁按住"可以一条命令读出来，而不是翻日志推。文本形态仍然是给操作者的，
	 * 不保证稳定，**不要拿它做逻辑判据**。</p>
	 */
	public String getMmtrBlockStopReason() {
		return mmtrBlockedWaiting ? mmtrBlockStopReason() : "";
	}

	/**
	 * **司机视角的"为什么不动"**：车被运动/信号层按住时给出**一句话**理由（镜像给客户端 HUD）。
	 *
	 * <p>为什么需要它（notes/217）：这些闸门（闭塞停车 / 行车许可 / 保护）都跑在司机控制**之前**
	 * （{@code mmtrBlockedWaiting} 那一支直接 {@code speed = 0}），于是"手柄有反应、车一动不动"在游戏里
	 * 完全没有解释 —— 用户实测就是这样报的（"刚才能动了，现在又动不了"）。理由原来只写在服务端日志里，
	 * 而这正是开车的**人**最需要看到的东西。</p>
	 *
	 * <p>返回空串 = 没有被按住。文本刻意短（HUD 一行）；详细理由仍在 {@code [MMTR-SIG]} 日志行里。</p>
	 */
	private String mmtrMotionHoldReason() {
		if (!isClientside && getIsOnRoute() && mmtrMotionWalker == null) {
			/*
			 * notes/235：原版 MTR 的走行路径已删除（"档位 × 固定加减速度"那套）。任何**在路线上却没有走行器**
			 * 的车列都是未被重编组的存量车 —— 它既没有牵引也没有制动。这必须**看得见**：否则现场只会看到
			 * "手柄有反应、车一动不动"，而那正是 2026-09-21 报过的同一个症状。
			 */
			return "遗留路径（无走行器）—— 本车不会动，请用 manifest 重编组（notes/235）";
		}
		if (mmtrProtection) {
			// 司机优先：越界施加的紧急制动是**司机自己能解除的**，所以这里必须把键说给他。
			// （AWS 报警超时的 SPAD 也走这条通道，文案同样告诉他按哪个键解除。）
			return mmtrManualOverride ? "紧急制动（越界/闯灯）—— 按响应键 R 解除" : "紧急保护（超速/闯灯）";
		}
		if (mmtrBlockedWaiting) {
			final String detail = mmtrBlockStopReason() == null ? "" : mmtrBlockStopReason();
			if (detail.contains("进路未设好") || detail.contains("红灯")) {
				return "前方进路未设好（红灯）";
			}
			if (detail.contains("unset turnout")) {
				return "前方道岔未设好";
			}
			return "前方区间被占（闭塞停车）";
		}
		if (speed <= 1e-9 && mmtrMotionStoppedAtTarget) {
			return "已到停车点（站停/等待任务）";
		}
		if (speed <= 1e-9 && mmtrMotionWalker instanceof final org.mtr.core.mmtr.consist.MmtrConsistWalker consistWalker && consistWalker.endOfLine()) {
			return "前方无进路（死端）";
		}
		if (speed <= 1e-9 && mmtrMotionWalker != null && mmtrMotionWalker.atTarget()) {
			return "已到任务目标轨（等待任务）";
		}
		return "";
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
	 * notes/235：**原版 ATO 自动驾驶已删除**，本方法只剩"关好门 + 刷新人工回退计时"这点副作用。
	 *
	 * <p>原来它给自动任务做的事是"手动缝 + 满油门"（{@code powerLevel = MAX_POWER_LEVEL}）——
	 * 那是"档位 × 固定加减速度"模型下的无人驾驶方式。现在无人/自动运行有唯一一条路：
	 * 走行器 + {@code autoNotch} 自臂（{@code mmtrMotionAuto} / {@code MMTR_AUTO_CRUISE}），
	 * 由 Motion-Core 逐 tick 出力。任务侧保留这个调用点只是为了"任务一开始就把门关好"。</p>
	 */
	public void engageMissionAutopilot() {
		if (isClientside || !vehicleExtraData.getIsManualAllowed()) {
			return;
		}
		vehicleExtraData.closeDoors();
		engageManualAutopilot(vehicleExtraData.getManualToAutomaticTime());
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
		/*
		 * **站台作业的子任务链**（2026-09-21 用户口径）：到站停稳 → 开门 → 停够 → 关门。
		 *
		 * 建链放在这里而不是 {@code attachTask}：默认停留是可改的引擎参数（在 {@code Simulator} 上），
		 * 而作业单只给"计划停留"；两者取大者的那一句必须发生在**能看见引擎默认值**的地方。
		 * 幂等，所以每 tick 调一次没有代价。
		 */
		if (motionMission && data instanceof final Simulator subTaskSimulator) {
			/*
			 * **任务目标**（变量）在这里解析：目标的**人话名字**住在站台/股道/车站对象上，
			 * 只有拿到 Simulator 才解析得出来（客户端更没有这些东西）。解析一次、挂住，
			 * 之后子任务文案与到站判定都从它取"停在哪儿"。
			 */
			if (!mission.getTarget().isNamed()) {
				mission.attachTaskShape(null, org.mtr.core.mmtr.MmtrTaskTarget.resolve(subTaskSimulator,
					mission.getTargetSidingId(), mission.getTargetRailHex(), mission.getTargetRailFraction()));
			}
			mission.ensureSubTasks(subTaskSimulator.mmtrSubTaskDwellMillis());
		}
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
				// legacy-path missions use their path/platform stop semantics - except a
				// player-driven station stop, which uses the loose "any car on the platform" rule
				// (see mmtrArrivedAtMissionTarget) because nobody brakes for the driver.
				if (mmtrArrivedAtMissionTarget(mission, motionMission)) {
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
				 * 2026-09-21（用户口径「拆一个任务到基础的操作以简化逻辑判定」）：**每个主任务都有清单**了 ——
				 * "开往" = [停在目标]，"停站乘降" = [停在目标, 开门, 等待上下客, 关门]。
				 * 于是这里只剩一句话：**清单全达成 ⇒ 这一步完成**；不再有"有链走链、没链走老口径"的分叉
				 * （那条分叉正是当天"开往车站那一步永远到不了站"的根因）。
				 * 链里的"等待上下客"自己带时长，"开往"那一步的清单只有一条、到站即达成。
				 */
				if (mission.hasSubTasks()) {
					mmtrTickSubTasks(mission);
					if (mission.allSubTasksDone()) {
						/*
						 * **出站确认**：最后一条基础操作达成 = 这一步真做完了（停站乘降那类就是"门关好"）。
						 * 时刻记在 mission 上（"实际发车时刻"），与进站时刻配对 —— 报点与事后分析都要它。
						 */
						mmtrMarkStationDeparture(mission);
						mission.complete();
					}
				} else if (!isMoving() && data.getCurrentMillis() - mmtrMissionTargetArrivedMillis >= MMTR_MISSION_DWELL_MILLIS) {
					// 没有清单可拆的任务（换端这类原地动作、以及临时任务）：保持老口径（默认停留后完成）
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
			// 子任务的自白节流跟着任务一起清（下一步的"等司机开门"要能再喊一次）
			mmtrSubTaskWarnMillis = 0;
			mmtrSubTaskDoorsWarned = false;
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
		final MmtrRunPlanner.Plan plan = MmtrRunPlanner.planToRail(simulator, this, targetRail.getHexId(), stopFraction, mission.getTargetViaNodes());
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
			/*
			 * T4：玩家执行 —— **只发布进路、不接管油门**，也不给停车点。
			 *
			 * <h3>2026-09-21 改：停车点对玩家撤掉（用户口径「不能干预玩家停车的行为」）</h3>
			 *
			 * <p>修前这里也给停车点，为的是让状态机能走到 AT_TARGET（判据原先只有
			 * {@code isMmtrMotionStoppedAtTarget()} 这一个）。代价是**引擎每 tick 按包线替司机刹车**：
			 * 停车点一进刹车距离，{@code autoBraking} 就压住司机的牵引 —— 司机想再往前挪半米对
			 * 车门，车却被按住。用户明确要求不许干预，所以停车点撤掉、到站判据换成宽松那条
			 * （{@link #mmtrAnyCarOnMissionStation}：站台轨上有车 + 停稳，见 {@link #mmtrArrivedAtMissionTarget}）。
			 * 两件事是一体的：撤掉刹车就必须换判据，否则步骤永远停在 DISPATCHED（这正是当初加停车点的原因）。</p>
			 *
			 * <p>**进路照发**：联锁、道岔、信号一个不少 —— 玩家执行的任务与自动车走同一条路，
			 * 区别只剩"油门与刹车归谁"。</p>
			 */
			System.out.println("[MMTR-MSG] motion mission " + mission.getKind() + " (PLAYER) 进路已发布到 rail " + plan.targetRailHex
				+ "，道岔已申请；**不设停车点**（到站按「站台轨上有车且停稳」判，刹车完全归司机）");
			// 玩家任务不设 auto，所以那个当不了"已自臂"的标志 —— 单独记一个闩，
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

	/*
	 * ============================== 站台作业：进出站判定 + 子任务 ==============================
	 *
	 * 用户口径（2026-09-21）：
	 *
	 * > 「这个东西需要做子任务，比如说到某站台。首先不能干预玩家停车的行为，所以判定可以宽松点，
	 * >   在站台上停车就算完成停车目标，然后按键开门，等段时间，关门。这几个子任务完成后才算任务完成。」
	 *
	 * 三件事在这里落地：
	 * ① **到站**用宽松判据（编组里任意一节车在站台上 + 停稳），不看停车点精确度；
	 * ② 到站之后按链逐条判定（开门 / 停够 / 关门），**每一步都是观测**，不向车发操作指令；
	 * ③ 进站/出站各记一个实际时刻（用于报点与事后分析），并打一条双向确认日志。
	 */

	/** "停稳"的速度阈值（m/ms）：约 0.036 km/h —— 比它慢就算停住了，不必等于 0（物理积分有残差）。 */
	private static final double MMTR_SUB_TASK_STOPPED_SPEED = 1e-5;
	/** 无人掌权时，到站后等司机开门的宽限（超时由引擎代开，见 {@link #mmtrTickSubTasks}）。 */
	private static final long MMTR_SUB_TASK_DRIVER_DOOR_WAIT_MILLIS = 15000;
	/** "等司机开门/关门"这类自白的节流间隔。 */
	private static final long MMTR_SUB_TASK_LOG_INTERVAL_MILLIS = 5000;

	/** 子任务自白的上次时刻（节流用）。 */
	private long mmtrSubTaskWarnMillis;
	/** 这一步有没有已经喊过"门没开/门没关"（每步只喊一次，避免刷屏）。 */
	private boolean mmtrSubTaskDoorsWarned;

	/** 车是不是停稳了（子任务判据用的宽松"停住"，不是 {@code speed == 0}）。 */
	private boolean mmtrStoppedForSubTask() {
		return Math.abs(speed) <= MMTR_SUB_TASK_STOPPED_SPEED;
	}

	/**
	 * **这次任务该用宽松到站判据吗**：玩家执行 + 走行模式。
	 *
	 * <p>只对玩家放宽，理由是用户那句话的**原因**本身：「不能干预玩家停车的行为」——
	 * 引擎不再给玩家的车设停车点（见 {@link #mmtrMotionSelfArmMission}），所以"停准了没有"
	 * 引擎根本没资格判。自动车的停车点是引擎按包线自己刹的，精确判据才是它真实的语义
	 * （换宽松判据会在"被信号按在站台轨上停着"时误判到站）。</p>
	 *
	 * <h3>2026-09-21 实机修：判据原来多要了一个"有子任务链"</h3>
	 *
	 * <p>第一版写成 {@code executor == PLAYER && 有子任务链}，于是只有**站台作业**那一步是宽松的，
	 * 而作业表里"开往某站台"那一步（{@code MOVE_TO}/{@code DriveToPlatformTask}）**没有子任务链**，
	 * 掉回精确判据 {@code isMmtrMotionStoppedAtTarget()} —— 而玩家任务恰恰**没有停车点**，
	 * 那个标志永远不会置位。现场症状：司机把车停在 2 站 1 台上、作业却停在「去程到 2 站 1 台」
	 * 的 DISPATCHED 上不动，**下一步的开关门提示因此永远不来**（用户报的就是这一条）。</p>
	 *
	 * <p>现在只按"是不是玩家在开"分叉 —— 没有停车点的车，"到没到"只能靠观测回答，
	 * 与"这一步有没有子任务"无关。</p>
	 */
	static boolean mmtrArrivalIsLoose(@Nullable MmtrMission mission) {
		return mission != null && mission.getExecutor() == MmtrMission.Executor.PLAYER;
	}

	/**
	 * 到站判据（宽严二选一，见 {@link #mmtrArrivalIsLoose}）—— **任务状态机的前进判据**：
	 * "此刻该不该宣布到站了"。
	 *
	 * <p>与子任务 {@code STOP_AT_TARGET} 的分工：状态机管**宣布时机**（自动车按精确停车点、
	 * 玩家按观测），子任务管**记录事实**（{@link #mmtrObservedAtTarget()}，执行者无关）。
	 * 两者共用同一个观测，但"谁说了算"不同 —— 自动车没到停车点就不许宣布到站
	 * （哪怕已经站在站台轨上、只是被信号按住），而一旦宣布了，子任务记的就是
	 * "车确实停在目标上"这件可观测的事（原地动作那条捷径就是从这里进来的：
	 * 它直接把状态推到 AT_TARGET，没有经过停车点）。</p>
	 *
	 * @param motionMission 本车是不是走行模式（老口径下两种语义不同）
	 */
	private boolean mmtrArrivedAtMissionTarget(MmtrMission mission, boolean motionMission) {
		if (motionMission && mmtrArrivalIsLoose(mission)) {
			return mmtrObservedAtTarget();
		}
		return motionMission ? isMmtrMotionStoppedAtTarget() : isStoppedAtMissionTarget();
	}

	/**
	 * **观测到的"已停在目标"**（与执行者无关）：编组在目标轨上 + 停稳。
	 *
	 * <p>判据两段：①站台/股道/车站目标（以及任务自己的目标轨 hex）—— 占用段与那些轨有交集
	 * （"有车在站上"）；②一个都解析不出来 —— 退回"车头已站在目标轨上"
	 * （{@code walker.atTarget()}），免得目标解析失败变成永远到不了。</p>
	 */
	private boolean mmtrObservedAtTarget() {
		if (!mmtrStoppedForSubTask()) {
			return false;
		}
		if (mmtrAnyCarOnMissionStation()) {
			return true;
		}
		return mmtrMotionWalker != null && mmtrMotionWalker.atTarget();
	}

	/**
	 * **编组里有没有任意一节车停在目标站台上**（宽松到站判据的本体）。
	 *
	 * <p>为什么不是"车头在站台轨上"：站台在物理上是一段轨，一列车停在站台上时，编组跨着好几根轨 ——
	 * 车头早过了站台，车门却正对着站台。所以判据是**占用段与站台轨有交集**，取的是"有车在站上"。</p>
	 *
	 * <p>站台轨从哪来：目标 id 可能是站台、股道，也可能是车站 —— 三种都收（车站取它的全部站台轨）。
	 * 另外把任务自己的目标轨也算进来（折返点之类没有站台对象的目标，靠它兜底），
	 * 这样一个目标对象都没解析出来时不至于"永远到不了站"而卡死。</p>
	 */
	private boolean mmtrAnyCarOnMissionStation() {
		if (mmtrMotionWalker == null || !mmtrStoppedForSubTask() || !(data instanceof final Simulator simulator)) {
			return false;
		}
		final java.util.HashSet<String> stationRails = new java.util.HashSet<>();
		final MmtrMission mission = mmtrMission;
		if (mission != null) {
			final long targetId = mission.getTargetSidingId();
			final org.mtr.core.data.Platform platform = targetId == 0 ? null : simulator.platformIdMap.get(targetId);
			if (platform != null) {
				// 站台 → 它所在的**车站**的全部站台轨：同一站的多站台都算"在站上"（宽松口径要的就是这个）
				final org.mtr.core.data.Station station = platform.area;
				if (station != null && !station.savedRails.isEmpty()) {
					station.savedRails.forEach(savedRail -> addMmtrRailHex(stationRails, savedRail.mmtrGraphRail()));
				} else {
					addMmtrRailHex(stationRails, platform.mmtrGraphRail());
				}
			} else {
				final org.mtr.core.data.Siding siding = targetId == 0 ? null : simulator.sidingIdMap.get(targetId);
				if (siding != null) {
					addMmtrRailHex(stationRails, siding.mmtrGraphRail());
				} else {
					final org.mtr.core.data.Station station = targetId == 0 ? null : simulator.stationIdMap.get(targetId);
					if (station != null) {
						station.savedRails.forEach(savedRail -> addMmtrRailHex(stationRails, savedRail.mmtrGraphRail()));
					}
				}
			}
			addMmtrRailHex(stationRails, org.mtr.core.mmtr.MmtrRunPlanner.findRailByHex(simulator, mission.getTargetRailHex()));
		}
		if (stationRails.isEmpty()) {
			return false;
		}
		/*
		 * 编组体车看**占用段**（任意一节车在这根轨上就算）；单点走行体没有占用段，退回"车头在这根轨上"。
		 * 玩家任务是编组体，走上面那一条；这一句是给老走行体留的正确退化路径，不是死代码。
		 */
		final org.mtr.core.mmtr.consist.MmtrConsistWalker consistWalker = getMmtrConsistWalker();
		if (consistWalker == null) {
			return stationRails.contains(mmtrMotionWalker.railHex());
		}
		for (final org.mtr.core.mmtr.consist.MmtrConsistBody.OccupiedSegment segment : consistWalker.occupancy()) {
			if (stationRails.contains(segment.railHex())) {
				return true;
			}
		}
		return false;
	}

	private static void addMmtrRailHex(java.util.Set<String> into, @Nullable Rail rail) {
		if (rail != null) {
			into.add(rail.getHexId());
		}
	}

	/**
	 * **推进基础操作清单**（用户口径：「拆一个任务到基础的操作以简化逻辑判定」），每条都是"看见什么算什么"：
	 *
	 * <ol>
	 *   <li>{@code STOP_AT_TARGET} **停在目标** —— 与任务状态机的 {@code DISPATCHED→AT_TARGET}
	 *       **共用同一处判据**（{@link #mmtrArrivedAtMissionTarget}）：自动车按精确停车点，玩家按观测
	 *       （站台轨上有车 / 车头站在目标轨上，且停稳）；</li>
	 *   <li>{@code OPEN_DOORS} **开门** —— 门到"开"。**司机在开就等他开**（用户要的"按键开门"是作业的一部分），
	 *       车里没人掌权时引擎代开（自动那一半保留，也免得作业链在没人时永远卡住）；</li>
	 *   <li>{@code WAIT_PASSENGERS} **等待上下客** —— 从开门起计够模板给的时长（默认 20 秒，可改）；</li>
	 *   <li>{@code CLOSE_DOORS} **关门** —— 门回到"关"（同上，司机在就等他关）。</li>
	 * </ol>
	 *
	 * <p><b>链是顺序的</b>：当前这条没达成，后面的不评（否则"门一开就同时算停够"）。</p>
	 *
	 * <p><b>不干预司机</b>：门开着的时候司机想关门就关，引擎不拦；真提前关了，等待照样按时间走完
	 * （宽松口径），只是打一条日志说明"提前关门"，因为那是要被人看见的偏差而不是失败。</p>
	 */
	private void mmtrTickSubTasks(MmtrMission mission) {
		if (isClientside) {
			return;
		}
		final long now = data.getCurrentMillis();
		final boolean onStation = mmtrAnyCarOnMissionStation();
		final boolean stopped = mmtrStoppedForSubTask();
		// 进站确认：AT_TARGET 达成的那一刻状态机已经确认过，这里只**记实际到站时刻**并喊一次
		if (mission.markStationArrival(now)) {
			System.out.println("[MMTR-SUB] 进站确认：车 " + getId() + " 已到 " + mission.getTarget().label()
				+ "（任务 " + mission.getTemplate() + "，执行者 " + mission.getExecutor() + "，"
				+ (stopped ? "停稳" : "未停稳") + "，占用车节在目标轨上=" + onStation + "）");
			vehicleExtraData.mmtrMarkSyncDirty();
		}

		for (final org.mtr.core.mmtr.MmtrSubTask subTask : mission.subTasks()) {
			if (subTask.isDone()) {
				continue;
			}
			if (subTask.state() == org.mtr.core.mmtr.MmtrSubTask.State.PENDING) {
				subTask.markActive(now);
				mission.bumpSubTaskRevision();
				vehicleExtraData.mmtrMarkSyncDirty();
				System.out.println("[MMTR-SUB] ▶ 基础操作开始：" + subTask.kind() + "（车 " + getId() + "，"
					+ mission.describeSubTasks(now) + "）");
			}
			switch (subTask.kind()) {
				case STOP_AT_TARGET -> {
					// **记录事实**（执行者无关）：车确实停在目标上。宣布时机那件事归状态机
					// （自动车按精确停车点、玩家按观测），这里只回答"现在停在目标上吗"
					if (!mmtrObservedAtTarget()) {
						break;
					}
					mmtrCompleteSubTask(mission, subTask, now, "已停在 " + mission.getTarget().label());
					continue;
				}
				case OPEN_DOORS -> {
					/*
					 * 门的读数**每次现读**，不用方法开头那一份：同一 tick 里引擎刚把门开掉，
					 * 缓存的那一份还是"关"，于是等待那一条会立刻报"司机提前关门"（第一版实测的假警报）。
					 */
					if (!vehicleExtraData.mmtrDoorsOpen()) {
						if (mmtrDriverIsOperating(mission)) {
							// 司机在开：门是他的活（用户口径"按键开门"）
							mmtrAnnounceSubTaskWait(now, "等司机开门（按开门键）");
							break;
						}
						if (mission.getExecutor() == MmtrMission.Executor.PLAYER
							&& now - mmtrMissionTargetArrivedMillis < MMTR_SUB_TASK_DRIVER_DOOR_WAIT_MILLIS) {
							// 执行者是司机、但他此刻不在操纵台上：给他一个宽限窗口再兜底
							mmtrAnnounceSubTaskWait(now, "司机不在操纵台，超时将自动开门");
							break;
						}
						vehicleExtraData.openDoors();
						System.out.println("[MMTR-SUB] 引擎开门（"
							+ (mission.getExecutor() == MmtrMission.Executor.PLAYER ? "司机未操作，引擎兜底" : "自动执行")
							+ "，车 " + getId() + "）");
					}
					mmtrCompleteSubTask(mission, subTask, now, "门已开");
					continue;
				}
				case WAIT_PASSENGERS -> {
					if (subTask.elapsedMillis(now) >= mission.subTaskDwellMillis()) {
						mmtrCompleteSubTask(mission, subTask, now, "等待了 " + Math.round(mission.subTaskDwellMillis() / 1000.0) + "s");
						continue;
					}
					if (!vehicleExtraData.mmtrDoorsOpen() && !mmtrSubTaskDoorsWarned) {
						// 司机提前关门：不拦、不改判据（宽松），但必须留下一条可查的记录
						mmtrSubTaskDoorsWarned = true;
						System.out.println("[MMTR-SUB] 司机在等待未满时关了门（车 " + getId() + "，已等 "
							+ Math.round(subTask.elapsedMillis(now) / 1000.0) + "s / 要求 "
							+ Math.round(mission.subTaskDwellMillis() / 1000.0) + "s）—— 按宽松口径仍放行");
					}
				}
				case CLOSE_DOORS -> {
					if (vehicleExtraData.mmtrDoorsOpen()) {
						if (mmtrDriverIsOperating(mission)) {
							mmtrAnnounceSubTaskWait(now, "等司机关门（按关门键）");
							break;
						}
						vehicleExtraData.closeDoors();
						System.out.println("[MMTR-SUB] 引擎关门（"
							+ (mission.getExecutor() == MmtrMission.Executor.PLAYER ? "司机未在操纵台，引擎兜底" : "自动执行")
							+ "，车 " + getId() + "）");
					}
					mmtrCompleteSubTask(mission, subTask, now, "门已关");
					continue;
				}
			}
			// 链是顺序的：当前这条没达成，后面的不评
			break;
		}
	}

	/**
	 * **门归谁管**：司机在开这一步（执行者是 PLAYER **且**此刻手上有操纵权）⇒ 门是他的活，引擎只等；
	 * 其余情形（自动执行 / 司机不在操纵台）⇒ 引擎自己动手。
	 *
	 * <p>这就是用户那句「司机手动开门和自动可以都保留」的判据：同一条链、同一套判定，
	 * 唯一的分叉是"谁动手"，而不是"两套逻辑"。</p>
	 */
	private boolean mmtrDriverIsOperating(MmtrMission mission) {
		return mission.getExecutor() == MmtrMission.Executor.PLAYER && mmtrManualOverride;
	}

	private void mmtrCompleteSubTask(MmtrMission mission, org.mtr.core.mmtr.MmtrSubTask subTask, long now, String why) {
		subTask.markDone(now);
		mission.bumpSubTaskRevision();
		vehicleExtraData.mmtrMarkSyncDirty();
		System.out.println("[MMTR-SUB] ✔ 子任务完成：" + subTask.kind() + " —— " + why + "（车 " + getId() + "，"
			+ mission.subTasksDoneCount() + "/" + mission.subTasks().size() + "，"
			+ mission.describeSubTasks(now) + "，确认 rev " + mission.subTaskRevision() + "）");
	}

	/** "还在等司机某个操作"的自白（节流）。 */
	private void mmtrAnnounceSubTaskWait(long now, String what) {
		if (now - mmtrSubTaskWarnMillis < MMTR_SUB_TASK_LOG_INTERVAL_MILLIS) {
			return;
		}
		mmtrSubTaskWarnMillis = now;
		System.out.println("[MMTR-SUB] " + what + "（车 " + getId() + "）");
	}

	/** **出站确认**：门关好、这一步作业做完 —— 记实际发车时刻，并把两边的确认状态一起打出来。 */
	private void mmtrMarkStationDeparture(MmtrMission mission) {
		final long now = data.getCurrentMillis();
		if (!mission.markStationDeparture(now)) {
			return;
		}
		final long arrival = mission.getStationArrivalMillis();
		System.out.println("[MMTR-SUB] 出站确认：车 " + getId() + " 本步作业完成（作业 " + mission.getJobId()
			+ " 第 " + (mission.getJobStepIndex() + 1) + "/" + mission.getJobStepCount() + " 步，"
			+ (arrival < 0 ? "进站时刻未知" : "实际到站 " + arrival + " → 实际发车 " + now
				+ "，站停 " + Math.round((now - arrival) / 1000.0) + "s")
			+ "；引擎判定 " + mission.describeSubTasks(now)
			+ "；客户端确认 " + mission.subTaskAcks() + " 次，rev " + mission.subTaskRevision() + "）");
		vehicleExtraData.mmtrMarkSyncDirty();
	}

	/**
	 * **双向确认的上行那一半**：客户端说他确认了第 {@code index} 条子任务（{@code revision} 是他看到的那一版）。
	 *
	 * <p>为什么要带 revision：两端"看到的状态"必须能对上。客户端确认的是**它当时显示的那一版**；
	 * 若引擎已经往前走了一版，这一次确认就不该被当成"确认了现在这一版" —— 打一条日志说明对不上，
	 * 而不是静默接受（静默接受会让"双向确认"退化成一句口号）。</p>
	 *
	 * @return 拒绝理由；{@code null} = 接受
	 */
	@Nullable
	public String mmtrConfirmSubTask(int index, long revision, @Nullable UUID crewUuid) {
		final MmtrMission mission = mmtrMission;
		if (mission == null || !mission.hasSubTasks()) {
			return "这一步没有子任务";
		}
		if (mission.getExecutor() != MmtrMission.Executor.PLAYER || mission.getExecutorPlayer() == null
			|| crewUuid == null || !mission.getExecutorPlayer().equals(crewUuid)) {
			return "本步不是由你执行（确认只认当前司机）";
		}
		final org.mtr.core.mmtr.MmtrSubTask subTask = index >= 0 && index < mission.subTasks().size() ? mission.subTasks().get(index) : null;
		if (subTask == null) {
			return "子任务序号越界：" + index;
		}
		final long now = data.getCurrentMillis();
		final boolean fresh = subTask.markDriverAck(now);
		mission.recordSubTaskAck();
		vehicleExtraData.mmtrMarkSyncDirty();
		final boolean revisionMatches = revision == mission.subTaskRevision();
		System.out.println("[MMTR-SUB] 双向确认（客户端→引擎）：车 " + getId() + " 子任务 " + (index + 1) + "/"
			+ mission.subTasks().size() + " " + subTask.kind() + " 状态 " + subTask.state()
			+ "，客户端看到 rev " + revision + "，引擎当前 rev " + mission.subTaskRevision()
			+ (revisionMatches ? "（一致）" : "（**不一致**：客户端显示的可能已经过期）")
			+ (fresh ? "，首次确认" : "，重复确认"));
		return null;
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
				/*
				 * 2026-09-21：站台作业改由**子任务链**执行（到站→开门→停够→关门，见
				 * {@link #mmtrTickSubTasks}），这里不再插手 —— 两个执行者各带一套计时器
				 * 只会互相打架（一个刚关、另一个又开）。链存在时不进这一支；链不存在（老任务/
				 * 单元测试直接构造的 mission）保留下面这条老路子，行为一字不改。
				 */
				if (mission.hasSubTasks()) {
					break;
				}
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
		// T6（2026-09-27）: 再把**服务等级 + 车号**交给进路 —— 现在它是第一档（等级踩头、同级车号小者先），
		// 计划时刻退到第二档。两者一起进敌对进路的裁决（MmtrRouteRegistry#outranks）。
		publishedRoute.setTrainPriority(org.mtr.core.mmtr.point.MmtrTrainPriority.of(
			mmtrMission == null ? "" : mmtrMission.getJobId(),
			mmtrMission == null ? "" : mmtrMission.getServiceClass()));
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
			/*
			 * ① 的例外**不是"最近一处"**（2026-09-26 全天卡死修）。
			 *
			 * <p>原文在这里 `rebuilt.add(...)` 之后直接 {@code break}（注释 "just the next fork ahead"）。
			 * 那等于"① 一成立就只看最近一处道岔"，而 ① 扣住车的原因是**要进的那个区块的出口道岔**：
			 * 出口可能就在下一处道岔，也可能更远（区块能跨好几根轨）。于是真正把车扣住的那一处
			 * 永远不会被申请 —— 现场读数（notes/328 §9）：计划里第二处岔 {@code -200,65,1806 leg 0}
			 * 距车只有 <b>80 m</b>（早就在 120 m 接近锁闭窗口内），却因为前面那处岔先被加进集合、
			 * 循环随即 break，而一次申请都没发出去；那一处停在位置 0（开通的是别人那条进路），
			 * 于是"从岔股开不出去"⇒ ① 一直扣着车，车不动 ⇒ 它更不可能走进窗口（自锁）。
			 * 后面那列车的行车许可同时报"物理道岔 -280,65,1800 被前车按在位置 1、本车需要位置 0"
			 * —— 咽喉两处岔互相扣死，一天里 15 份作业单一份也没出库。</p>
			 *
			 * <p>改成两条并列：窗口内的照旧申请；**扣住本车的那一处**（{@link #mmtrSectionAuthorityHoldNodeKey}）
			 * 无论远近都一起申请（原子组）。第①条里"车可能离它整整一个区块"那句本来就是这个意思，
			 * 这里只是把它落成代码。窗口外、又不是扣住本车的那几处**照旧不申请** —— 接近锁闭的本意不变。</p>
			 */
			final boolean holdFork = mmtrSectionAuthorityHold && !mmtrSectionAuthorityHoldNodeKey.isEmpty()
				&& mmtrSectionAuthorityHoldNodeKey.equals(op[0] + "," + op[1] + "," + op[2]);
			if (remainingM <= MMTR_APPROACH_LOCK_METERS || holdFork) {
				rebuilt.add(op.clone());
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
		// new driver can take over. 钥匙不参与这个判据（2026-09-19）：司机位空了才交还，
		// 而不是"钥匙被拔掉就断牵引"—— 后者会让站着没动的人突然失去制动。
		if (!isClientside && MmtrDriveAccess.shouldAutoRelease(mmtrManualOverride, mmtrDriverUuid, mmtrDriverUuid != null && hasMmtrDriverRiding(mmtrDriverUuid))) {
			releaseMmtrManualOverride();
		}

		// 司机视角的"为什么不动"（notes/217）：这些闸门都跑在司机控制**之前**（闭塞停车那一支直接 speed = 0），
		// 所以必须在每 tick 的入口处重算并通过镜像发给客户端 —— 否则"手柄有反应、车一动不动"在游戏里没有解释。
		if (!isClientside) {
			final String holdReasonNow = mmtrMotionHoldReason();
			if (!holdReasonNow.equals(mmtrHoldReason)) {
				mmtrHoldReason = holdReasonNow;
				vehicleExtraData.mmtrMarkSyncDirty();
			}
			/*
			 * notes/276 片 6：**钉住**（停放 = 钉住）—— 每 tick 重算，与 holdReason 同一处。
			 *
			 * <p>推导而不是搬运：连挂/解挂会改车列（⇒ "整列能不能出力"变），人上车/下车与任务起止也在变
			 * —— 三条里任何一条变了都自动跟上，不存在"标志忘了搬"那种不同步。</p>
			 */
			final boolean pinnedNow = mmtrComputePinned();
			if (pinnedNow != mmtrPinned) {
				mmtrPinned = pinnedNow;
				vehicleExtraData.mmtrMarkSyncDirty();
				// 状态类消息（默认可见）：谁被钉住/解钉是现场最需要看得见的一件事（notes/216/217 的教训）。
				System.out.println("[MMTR-DRV] 车 " + id + (pinnedNow ? " 已钉住（停放）：" : " 已解钉：") + mmtrPinnedReason());
			}
		}

		// MMTR (server): protection lock countdown after an overrun/SPAD emergency stop.
		// 司机越界那一路（mmtrAuthorityTripped）**不自动解锁**：真车 SCR 要司机按响应键才放行，
		// 这里少一个"10 秒后自动放行"的旁路（否则司机按住油门就能无视红灯开过去）。
		if (!isClientside && mmtrProtection && speed <= 0 && !mmtrAuthorityTripped) {
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
		} else if (getIsOnRoute() || (isClientside && mmtrMotionMirror)) {
			/*
			 * notes/235：路线上只剩两种身份 —— 服务端"没有走行器的存量车"（{@link #simulateMoving} 里
			 * 喊一声然后停住）与客户端镜像（同一条 {@link ConsistDynamics} 积分）。
			 * 原版的"到终点就回车场 / 停在停车点等发车"（{@code simulateStopped} + {@code startUp}）已删除。
			 *
			 * <p>★ {@code isClientside && mmtrMotionMirror} 这一支是 2026-10-03 的正面修正：镜像的
			 * 积分原来挂在 {@code getIsOnRoute()} 下面，而"不在路上"在客户端是**常事**（停放、出入段、
			 * 刚被 ② 重建过）。那时镜像既不积分也不下车场模拟（下面那支刻意跳过），位置就完全靠 ①
			 * 每 100 ms 一个的 MOTION 写进来 —— 画面上就是**每秒十级的台阶**（用户报的"位移掉帧"）。
			 * 是运动镜像就必须自己往前走：同一条 {@code ConsistDynamics}、同一批镜像来的手柄输入。</p>
			 */
			currentIndex = Utilities.getIndexFromConditionalList(vehicleExtraData.immutablePath, railProgress);
			simulateMoving(millisElapsed, vehiclePositions, currentIndex);
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
				// Remove entities that have dismounted.
				//
				// The OTHER half of "a stale cab occupant cannot hold the controls for ever" lives in the
				// session bookkeeping, not here: Simulator.removeClient drops the ride registration when a
				// player's session ends, so a session that dies without a dismount (a crash, a killed client)
				// cannot leave a rider - and with it an isDriver flag that keeps the occupation lock held -
				// behind. Doing it here instead, by asking whether the rider still has a client record, was
				// tried and is WRONG: the engine's own offline harness boards riders with no client record at
				// all, and every driver-dependent test (47 of them) collapsed the moment their riding entity
				// was swept away on the next tick.
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
			// 自动运行（"AI 驾驶员"）的灯光：任务在跑、钥匙是引擎的、没人接管 ⇒ 车头白灯、车尾红尾灯
			// （用户口径 2026-10-03；判据与交还见 mmtrTickAutoLights）。
			mmtrTickAutoLights();
		}
	}

	/*
	 * notes/235：**原版发车路径（{@code startUp}）已删除**。
	 *
	 * 这里原来是把车列从车场/停车点"放"到路线上的唯一入口（speed = Siding.ACCELERATION_DEFAULT +
	 * setNextStoppingIndex() + 偏离时刻表的调速），由车场计时、站台停留与司机推油门三处调用。
	 * 它属于被删除的"档位 × 固定加减速度"模型：速度不是发车时给一个初值再靠档位积分，
	 * 而是力模型逐 tick 积出来的。
	 *
	 * 现在**没有"发车"这个动作**：车列要么在车场停着，要么被运行层/任务编成走行体由 Motion-Core 驱动。
	 */


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
		/*
		 * notes/235：这里原来是**原版发车闸门** —— 司机推油门（或 MMTR 请求牵引）就调 {@code startUp}
		 * 把车放到 MTR 的路线上，之后靠"档位 × 固定加减速度"积分。那条路连同 {@code startUp} 一起删除了。
		 *
		 * <p>但"**司机能上车就能开**"是硬规矩（notes/233）：所以这里不做别的，只把"起步"换成
		 * Motion-Core 的**自臂** —— 车列在这一 tick 里被编成走行体，下一 tick 起由力模型驱动。
		 * 编不出来（股道放不下车列、车列模板为空…）就**喊出来**，绝不静默把司机锁在原地。</p>
		 */
		if (!isClientside && isCurrentlyManual() && !mmtrProtection && mmtrMotionWalker == null
			&& (vehicleExtraData.getPowerLevel() > 0 || isMmtrRequestingPower())) {
			mmtrSelfArmForDriver();
		}
	}

	/**
	 * **司机在车场推手柄 ⇒ 就地把车列编成 Motion-Core 走行体**（notes/235：被删除的 {@code startUp}
	 * 发车闸门的替代品）。
	 *
	 * <p>顺序刻意是"先试车场、再试车自己现在的位置"：</p>
	 * <ol>
	 *   <li>{@link Siding#mmtrConsistWalkerFromYard} —— 车列体（双端车体 + 驾驶室），"人开着走"的正路；</li>
	 *   <li>{@link Siding#mmtrMotionWalkerFromYard} —— 单点走行器（老车列的退化形态）；</li>
	 *   <li>{@link #mmtrWalkerFromOwnPath} —— 车场编不出来时（例如车列比股道还长）按**车现在压在哪根轨**
	 *       建走行器：前方每一处道岔按现场位置现场决定，与"未设道岔就跟实际位置走"的口径一致。</li>
	 * </ol>
	 */
	private void mmtrSelfArmForDriver() {
		if (isClientside || mmtrMotionWalker != null) {
			return;
		}
		// 编组会释放"停车时留下的旧操纵"（engageMmtrMotionPosition），可这一次正是**司机本人**要的起步 ——
		// 编完得把他那三根手柄原样放回去，否则"推着油门起步"会在起步的那一 tick 被自己清掉。
		final ControlState control = mmtrActiveControl;
		final UUID driver = mmtrDriverUuid;
		MmtrMotionPosition walker = null;
		String how = null;

		if (siding instanceof final Siding yard) {
			final org.mtr.core.mmtr.consist.MmtrConsistWalker consistWalker = yard.mmtrConsistWalkerFromYard(null, null, null);
			if (consistWalker != null) {
				walker = consistWalker;
				how = "车列体（车场）";
			} else {
				walker = yard.mmtrMotionWalkerFromYard(null, null, null);
				if (walker != null) {
					how = "单点走行器（车场）";
				}
			}
		}
		if (walker == null && data instanceof final Simulator simulator) {
			walker = mmtrWalkerFromOwnPath(simulator);
			if (walker != null) {
				how = "按当前位置（前方道岔按现场位置现场决定）";
			}
		}
		if (walker == null) {
			System.out.println("[MMTR-DRV] 车=" + id + " 司机在车场推手柄但**编不出走行体** —— 本车不会动。"
				+ "（股道=" + (siding == null ? "无" : String.valueOf(siding.getId())) + "；车列可能比股道还长，或车列模板为空）");
			return;
		}
		if (walker instanceof final org.mtr.core.mmtr.consist.MmtrConsistWalker consistWalker) {
			engageMmtrConsistMotion(consistWalker, MmtrCabState.Cab.CAB_A);
		} else if (walker instanceof final MmtrMotionWalker motionWalker) {
			engageMmtrMotion(motionWalker);
		}
		if (control != null) {
			applyMmtrControl(control, driver);
		}
		System.out.println("[MMTR-DRV] 车=" + id + " 司机推手柄 ⇒ 已自臂为 Motion-Core 走行体（" + how + "）");
	}

	/**
	 * 按车列**现在压在哪根轨**建走行器：身后那一端取"与上一段腿共用的节点"，车头继续朝前。
	 *
	 * @return 走行器；连当前轨都认不出来时返回 {@code null}
	 */
	private @Nullable MmtrMotionWalker mmtrWalkerFromOwnPath(Simulator simulator) {
		final int index = Utilities.getIndexFromConditionalList(vehicleExtraData.immutablePath, railProgress);
		final PathData leg = Utilities.getElement(vehicleExtraData.immutablePath, index);
		if (leg == null || leg.getRail() == null) {
			return null;
		}
		final Rail rail = leg.getRail();
		// 注意：{@code Utilities.getElement} 对越界下标是**取模回绕**的（-1 会拿到最后一段腿）——
		// 少一个边界判断，"身后的节点"就会变成"最后一腿与当前腿的共用点"，车头当场掉头。
		final int pathSize = vehicleExtraData.immutablePath.size();
		final PathData previous = index > 0 ? Utilities.getElement(vehicleExtraData.immutablePath, index - 1) : null;
		final PathData next = index + 1 < pathSize ? Utilities.getElement(vehicleExtraData.immutablePath, index + 1) : null;
		Position rear = previous == null || previous.getRail() == null ? null : sharedNode(previous.getRail(), rail);
		if (rear == null && next != null && next.getRail() != null) {
			final Position sharedWithNext = sharedNode(next.getRail(), rail);
			if (sharedWithNext != null) {
				rear = sharedWithNext.equals(rail.getPosition1()) ? rail.getPosition2() : rail.getPosition1();
			}
		}
		if (rear == null) {
			// 认不出前后（单腿路径）：按"车头朝车列 A 端"取远端，起步后再由现场道岔决定。
			rear = reversed ? rail.getPosition1() : rail.getPosition2();
		}
		final double offsetM = Utilities.clampSafe(railProgress - leg.getStartDistance(), 0, rail.railMath.getLength());
		/*
		 * 分支意图：车列**已经带着的那条进路**就是司机/任务要走的路线（每一段腿都是当时按道岔位置选出来的）。
		 * 走行体每过一处岔都要重新选一次，所以这里把"哪一段腿接哪一段"折成**操作位**交给它 —— 否则司机
		 * 在岔前会被"没人给位"扣住（现场表现就是"推着油门停在岔前不动"）。道岔的物理位置闸门照样生效：
		 * 那位只是"想要哪条腿"，开通与否仍由道岔自己说了算（{@code MmtrForkElection} 的闸门）。
		 */
		final MmtrPointRegistry.BranchStore intent = mmtrBranchIntentFromOwnPath(simulator);
		return MmtrMotionWalker.startAtOffset(simulator, rail, rear, offsetM,
			intent == null ? simulator.mmtrPointBranches : intent, null);
	}

	/**
	 * 把车列**现有进路**折成操作位（{@code BranchStore}）：每一处"上一段腿 → 下一段腿"的岔口，
	 * 记下下一段腿在该岔口**有序续行表**里的下标。
	 *
	 * @return 一条都没折出来时 {@code null}（调用方退回模拟器自己那份操作位）
	 */
	private MmtrPointRegistry.@Nullable BranchStore mmtrBranchIntentFromOwnPath(Simulator simulator) {
		final MmtrPointRegistry.BranchStore store = new MmtrPointRegistry.BranchStore();
		boolean any = false;
		for (int i = 0; i + 1 < vehicleExtraData.immutablePath.size(); i++) {
			final Rail from = vehicleExtraData.immutablePath.get(i).getRail();
			final Rail to = vehicleExtraData.immutablePath.get(i + 1).getRail();
			if (from == null || to == null) {
				continue;
			}
			final Position node = sharedNode(from, to);
			if (node == null) {
				continue;
			}
			// 进岔方向：车从 from 的另一端进来，朝 node 走。
			final Position enteredFrom = node.equals(from.getPosition1()) ? from.getPosition2() : from.getPosition1();
			final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<Position, Rail> neighbors = simulator.positionsToRail.get(node);
			if (neighbors == null) {
				continue;
			}
			final ObjectArrayList<MmtrPoint.MmtrPointLeg> legs = MmtrPoint.computeOrderedLegs(
				node, enteredFrom, from, neighbors, simulator.mmtrJunctionLegs.get(node.getX(), node.getY(), node.getZ(), from.getHexId()));
			for (int index = 0; index < legs.size(); index++) {
				if (legs.get(index).railHex.equals(to.getHexId())) {
					store.set(node.getX(), node.getY(), node.getZ(), from.getHexId(), index);
					any = true;
					break;
				}
			}
		}
		return any ? store : null;
	}

	/** 两根轨共用的那个端节点（没有共用端点时 {@code null}）。 */
	private static @Nullable Position sharedNode(Rail a, Rail b) {
		if (a.getPosition1().equals(b.getPosition1()) || a.getPosition1().equals(b.getPosition2())) {
			return a.getPosition1();
		}
		if (a.getPosition2().equals(b.getPosition1()) || a.getPosition2().equals(b.getPosition2())) {
			return a.getPosition2();
		}
		return null;
	}

	/*
	 * notes/235：**{@code simulateStopped}（原版停在停车点等发车）已删除**。
	 *
	 * 它做的两件事现在都不存在了：① 用 legacy 单手柄 powerLevel 判断"司机想走"并把车放出去（{@code startUp}）；
	 * ② 靠 {@code stoppingPoint} / {@code railBlockedDistance} 决定"能不能起步"。停车与起步现在是 Motion-Core
	 * 的停车锚点 + 任务自臂，闭塞由行车许可一条链管。
	 */
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
		final boolean wantPower = overridden && forwardRequested && !mmtrReverserPending
			&& (control.getThrottleNotch() > 0 || control.getDriveHandle() > 0)
			&& !(control.getBrakeNotch() > 0 || control.isEmergency());
		final boolean braking = overridden && (control.getBrakeNotch() > 0 || control.isEmergency());
		final boolean stopTargetActive = mmtrMotionStopTargetM >= 0;

		// Signal S1: re-derive the occupancy stop every tick from the shared occupancy trees (server
		// authority). The effective stop of this tick is the nearer of the armed stop target and the
		// occupancy block stop. While parked AT the block stop the waiting flag clears itself only
		// once that stop moves past the head / disappears - auto runs then resume to their target and
		// manual drivers regain traction.
		mmtrBlockStopM = computeMmtrBlockStopM(vehiclePositions);
		/*
		 * **司机优先**（用户口径 2026-09-21）：「只要司机能上车，那么什么都阻挡不了他开车」。
		 *
		 * <p>两件事在这里落地：①岔口没有人工位/授权/目标时，手动车**跟随道岔当前物理位置**走
		 * （走行器侧，{@code setManualDrive}）；②无人编组的司机要先让编组"有头"（补一把引擎占位钥匙），
		 * 否则走行器在物理上不接受任何推进（{@code currentRail()/railHex()} 为 null）。</p>
		 */
		mmtrMotionWalker.setManualDrive(overridden);
		mmtrAdoptUnmannedConsistForDriver(overridden);
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
		/*
		 * **司机优先**（用户口径 2026-09-21）：手动车的停车点分两类，处置不同。
		 *
		 * <ul>
		 *   <li><b>被授权的接近</b>（调车授权 / 副显示：允许贴到车钩距离）—— 仍由引擎把住：连挂必须
		 *       在这条界限上**停稳**，车钩才咬得上（{@code MmtrAutoCoupler} / {@code MmtrCoupleSurgery}
		 *       的判据就是"停在车钩间隙上"）。这一支保留原来的"制动包线 + 夹紧"。</li>
		 *   <li><b>没被授权的越界</b>（红灯 / 区间被占 / 岔区未清）—— 不再把速度钉成 0，闯过去
		 *       **触发紧急制动**，由司机按响应键解除后继续（真车 SCR/TPWS 的做法）。</li>
		 * </ul>
		 *
		 * <p>自动/无人车两条都不走：{@code mmtrBlockedWaiting → speed = 0} 一个字没改。</p>
		 */
		final boolean driverAuthorisedApproach = overridden && getMmtrShuntAuthority() != null;
		final boolean driverMayOverrun = overridden && !driverAuthorisedApproach;
		if (driverAuthorisedApproach) {
			// 授权到手（副显示放行）：越界触发的紧急制动随之解除 —— 它拦的那个理由已经不存在了。
			mmtrReleaseAuthorityTrip("调车授权放行");
		} else if (driverMayOverrun) {
			mmtrBlockedWaiting = false;
			mmtrTickDriverAuthorityTrip();
		}
		/*
		 * 司机越过未授权界限时**还要不要夹紧**：要 —— 紧急制动负责把车停住（物理），夹紧负责把最后那一小段
		 * 收口到界限上（与自动车同一套收口），否则 1 s 的积分步会把车带到界限**之外**（实测冲过 1.5 m）。
		 * 司机一按响应键放行（mmtrAuthorityReleased）夹紧立刻撤掉 —— 那时他就能开过去（闯区间）。
		 */
		final boolean driverClampActive = driverAuthorisedApproach
			|| driverMayOverrun && mmtrProtection && !mmtrAuthorityReleased;
		final boolean clampActive = !overridden || driverClampActive;

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
				// 玩家任务的自臂闩跟着停车点一起清：下一步（或这一次的重规划）才能再自臂一次。
				mmtrPlayerRoutePublished = false;
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
		// 司机优先（2026-09-21）：包线**不替司机刹车**（闯界由 mmtrProtection 那一支做紧急制动）；
		// 例外是"被授权的接近"（调车授权），那一条界限就是车钩间隙，必须由包线把车停稳在那里。
		final boolean autoBraking = brakeTargetActive && (!overridden || driverAuthorisedApproach) && !(overridden && braking) && speed > 0 && remainingToBrake > 0
			&& remainingToBrake < DynamicsEnvelope.brakingDistance(speed, 0, mmtrMotionServiceDecelPerMs());

		double integratedDistance = 0;
		final boolean airBrakeConsist = mmtrConsistType != null && mmtrConsistType.getControlMode() == ConsistType.ControlMode.AIR_BRAKE;
		if (mmtrPinned) {
			/*
			 * notes/276 片 6：**钉住 ⇒ 位置锁死**（停放 = 钉住，决定 1/6）。
			 *
			 * <p>放在这一串闸门的最前面：闭塞停车与保护制动都是"这列车本来能动"的语义，而钉住是
			 * "它根本不该动"。没有这一支，一节**被声明成无动力**的车（单节不走等效车底那条路）会被
			 * 任务照常开走 —— 准入层只管人不任务（片 2）。</p>
			 */
			speed = 0;
			integratedDistance = 0;
		} else if (mmtrProtection) {
			// Signal S3: motion-mode SPAD execution (unacknowledged AWS warning / overrun). Emergency
			// brake overrides any traction; the 10 s lock countdown lives in simulate().
			final double emergencyPerMs = mmtrEmergencyDecelPerMs();
			speed = Math.max(0, speed - emergencyPerMs * millisElapsed);
			integratedDistance = speed * millisElapsed;
		} else if (mmtrBlockedWaiting && !driverMayOverrun) {
			// Parked exactly at the occupancy stop point: traction is suppressed (never creep into
			// the occupied rail); the flag clears at the top of a later tick once the block opens.
			// 司机优先：这一支对"没被授权的越界"不成立（那一支走 mmtrTickDriverAuthorityTrip）。
			speed = 0;
		} else if (autoBraking) {
			// Service-brake to an exact rest at the effective stop (constant-decel law; the trailing
			// clamp below trims the last sub-tick remainder). Driver traction is overridden inside the
			// braking envelope, like an ATO stop; the driver's own emergency brake stays stronger.
			final double brakeDelta = DynamicsEnvelope.usableDecel(speed, 0, remainingToBrake, mmtrMotionServiceDecelPerMs()) * millisElapsed;
			speed = Math.max(0, speed - brakeDelta);
			integratedDistance = speed > 0 ? speed * millisElapsed : 0;
		} else if (autoActive && !airBrakeConsist) {
			// Signal S2: deterministic auto cruise under the per-segment rail speed limit (directional,
			// read live from the rail the walker stands on). Cruise target = min(rail limit, consist
			// ceiling). A SLOWER rail ahead (peeked - same elect contract as the walker) is braced for
			// with the service-brake envelope so the train crosses the node at (about) that rail's
			// limit instead of over-running it; residual overspeed after boarding a slower rail decays
			// at service deceleration. (Air-brake consists keep the controller path below.)
			final double accelPerMs = MmtrSupport.siAccelerationToInternal(mmtrPhysics().tractionAccelerationMps2(1, MmtrSupport.internalSpeedToSi(speed)));
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
				final double envelopeDistanceM = toNodeM - speed * millisElapsed;
				if (DynamicsEnvelope.requiresBraking(speed, nextLimitMms, envelopeDistanceM, decelPerMs, 0.98)) {
					speed = Math.max(nextLimitMms, speed - DynamicsEnvelope.usableDecel(speed, nextLimitMms, envelopeDistanceM, decelPerMs) * millisElapsed);
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
			/*
			 * notes/338：**零牵引的编组不许把速度积成负数**（2026-09-27 现场）。
			 *
			 * <p>这一支是"自动巡航"，加速度来自整列等效车底的牵引−阻力。整列牵引为 0 时它是负的，
			 * 而这里的写法（{@code min(cruiseCap, speed + a·dt)}）**没有下限**：速度会一直往负方向涨，
			 * 而 {@code integratedDistance = speed·dt < 0} ⇒ 下面的 {@code if (integratedDistance > 0)}
			 * 永不成立 ⇒ 走行体一步都不前进（累计里程恒 0.0 m），同时所有闸门都报"没人拦它"。
			 * 现场读数：库里长编组"倒着加速"（−0.04 → −0.83 m/s），位置一动不动，全链路没有一句日志。</p>
			 *
			 * <p>处置：负速度按 0 处理（车就是**站住不动**，不再假动作），并**说出来**是一次零牵引 ——
			 * "车不动且看不出是谁的锅"正是这一条要消灭的形状。</p>
			 */
			if (speed < 0) {
				mmtrLogZeroTractionIfNeeded();
				speed = 0;
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
			/*
			 * ★ **牵引力必须走控制器**（notes/264）：这一支原来把 `accelPerMs` 算成
			 * `tractionAccelerationMps2(1, v)` —— **满牵引**，手柄只被当成"要不要走"的开关（`wantPower`）。
			 * 于是 ≥101 km/h 的 LZB 段上，20% 与 100% 手柄的加速度**一模一样**（用户 2026-09-23 现场：
			 * 「牵引 20% 和 100% 加速度都是 +1.6」）。同一根杆在 AWS 段给 0.33 / 1.63 m/s²、到 LZB 段却
			 * 都是 1.63 —— 同一列车在两段按两套物理跑，这是这个现场最直接的证据。
			 *
			 * <p>LZB 只该管**天花板与包线**（限速、降速预告、超速回收），不该替司机决定出多少力。
			 * 控制器里已经有"手柄比例 + AFB 只削不力 + 牵引联锁 + 驱动延迟"，所以这里**每拍调一次**它
			 * （空气状态与延迟滤波也跟着推进），只取**牵引侧**做加速项、**制动侧**做惰行/制动项的补充；
			 * 气制动仍由这一支自己的减速律负责（与控制器那份取大者，司机的闸只会更强）。</p>
			 */
			final double controllerAccelMps2 = mmtrLzbControllerAccelMps2(control, millisElapsed);
			final double accelPerMs = MmtrSupport.siAccelerationToInternal(Math.max(0, controllerAccelMps2));
			/** 司机自己那一份减速度（电机负侧 / 气制动）：LZB 的减速律不比它弱。 */
			final double driverDecelPerMs = MmtrSupport.siAccelerationToInternal(Math.max(0, -controllerAccelMps2));
			final double decelPerMs = Math.max(mmtrMotionServiceDecelPerMs(), driverDecelPerMs);
			final double emergencyPerMs = Math.max(mmtrEmergencyDecelPerMs(), driverDecelPerMs);
			final double lzbCeiling = Math.min(mmtrCurrentRailLimitPerMs(), kmhToInternal(mmtrConsistType.getMaxSpeedKmh()));
			/*
			 * **定速在这段同样是天花板**（2026-09-23 现场：定速 20、手柄 97，车在 LZB 段一路涨到 91.5 km/h
			 * 还在涨 —— 因为这一支原来只认线路限速，压根没看定速）。
			 *
			 * LZB 段的手动驾驶走的是"包线 + 天花板"的简化模型（不走控制器），所以这里必须把 AFB 的设定值
			 * 折进天花板：`ceiling = min(线路/车底限速, 定速目标)`。司机设了就不能被线路限速带着跑；
			 * 超过天花板时按**常用制动减速度**回收（司机自己的制动仍然更强）。
			 */
			final double afbCeiling = control == null || control.getCruiseSpeedKmh() <= 0
				? Double.MAX_VALUE
				: kmhToInternal(mmtrConsistType.getHandles() == null ? control.getCruiseSpeedKmh() : mmtrConsistType.getHandles().clampCruiseKmh(control.getCruiseSpeedKmh()));
			final double ceiling = Math.min(lzbCeiling, afbCeiling);
			final Rail nextRailLzb = mmtrMotionWalker.peekNextRail();
			final double nextLimitLzb = nextRailLzb == null ? -1 : nextRailLzb.getSpeedLimitMetersPerMillisecond(mmtrMotionWalker.aheadNode());
			if (braking) {
				speed = Math.max(0, speed - (control.isEmergency() ? emergencyPerMs : decelPerMs) * millisElapsed);
			} else if (nextLimitLzb > 0 && nextLimitLzb < lzbCeiling - 1e-12 && speed > nextLimitLzb) {
				final double toNodeM = mmtrMotionWalker.currentRailLengthM() - mmtrMotionWalker.offsetM();
				// One-tick look-ahead (same rule as the auto planner): engage the envelope before
				// this tick's travel crosses the braking point so the node is crossed at the slower
				// rail's limit instead of being overshot by the cruise-to-brake discrete step.
				final double envelopeDistanceM = toNodeM - speed * millisElapsed;
				if (DynamicsEnvelope.requiresBraking(speed, nextLimitLzb, envelopeDistanceM, decelPerMs, 0.98)) {
					speed = Math.max(nextLimitLzb, speed - DynamicsEnvelope.usableDecel(speed, nextLimitLzb, envelopeDistanceM, decelPerMs) * millisElapsed);
				} else {
					speed = Math.min(ceiling, speed + accelPerMs * millisElapsed);
				}
			} else if (speed > ceiling) {
				speed = Math.max(ceiling, speed - decelPerMs * millisElapsed);
			} else if (wantPower) {
				speed = Math.min(ceiling, speed + accelPerMs * millisElapsed);
			} else {
				speed = Math.max(0, speed - decelPerMs * 0.1 * millisElapsed); // coast
			}
			integratedDistance = speed * millisElapsed;
			// 空气状态跟着控制器走（这一支原来一句都不更新它，于是 HUD 的缸压/管压在这段是冻住的）。
			if (mmtrDriveController instanceof final org.mtr.core.mmtr.AirBrakeStateful airBrakeStateful) {
				mmtrPipePressure = airBrakeStateful.getPipePressure();
				mmtrBrakeCylinderPressure = airBrakeStateful.getBrakeCylinderPressure();
			}
		} else if (overridden || autoActive) {
			/*
			 * notes/235：**纵向物理只剩这一条** —— {@link ConsistDynamics} + 本车的 {@link ConsistType}
			 * （缺配置时是 {@link ConsistType#FALLBACK} 通用车，并已在解析处喊过）。
			 *
			 * <p>原来这里还有一条退路："没有车底类型 ⇒ 拿 {@code vehicleExtraData.getAcceleration()/getDeceleration()}
			 * （MTR 的车场加减速度）线性积分"。它整条删除了 —— 退路一在，"手柄有反应但车不动 / 手感是另一套"
			 * 就永远查不完（notes/216、notes/233 的现场都是这个形状）。</p>
			 */
			if (tryInitMmtrController() && mmtrConsistType != null && mmtrDriveController != null) {
				// Driver (any consist) and auto air-brake consists run the fixed sub-step ConsistDynamics
				// integration (auto feeds a synthesized cruise ControlState; air-brake physics stay on the
				// per-car composition; an active cab override feeds the driver's own state).
				final ConsistType mmtrType = mmtrConsistType;
				final ControlState mmtrState = overridden ? control : new ControlState().setThrottleNotch(autoNotch).setReverser(1);
				final boolean useCompositionAir = mmtrType.getControlMode() == ConsistType.ControlMode.AIR_BRAKE;
				final MmtrComposition mmtrCompositionNow = useCompositionAir ? getMmtrComposition() : null;
				final double mmtrStartSpeedSi = MmtrSupport.internalSpeedToSi(speed);
				final ConsistDynamics.SpeedDistance mmtrResult = ConsistDynamics.advance(mmtrStartSpeedSi, mmtrType, millisElapsed, MMTR_INTEGRATION_SUB_STEP_MS, (siSpeed, stepMillis) -> {
					// notes/277 片 7：控制器输出先过一遍**车钩**（机车 ↔ 车列一个钩；刚性车列原样返回）——
					// 起步时"先冲出去、间隙吃完一顿"就发生在这一层。
					final DriveOutput mmtrStepOutput = mmtrCompositionNow != null
						? mmtrCompositionNow.stepAir(mmtrState, siSpeed, stepMillis)
						: mmtrDriveController.compute(mmtrState, mmtrType, siSpeed, stepMillis);
					return mmtrApplyCoupler(mmtrStepOutput, siSpeed, stepMillis);
				});
				speed = MmtrSupport.siSpeedToInternal(mmtrResult.speedMetersPerSecond);
				// 火车不能倒车: the walker only moves forward - any negative speed from a controller is
				// clamped away defensively (braking/coasting already stay non-negative in ConsistDynamics).
				speed = Math.max(0, speed);
				integratedDistance = mmtrResult.distanceMeters;
				mmtrLogConsistBranch(overridden, control, mmtrDriveController, mmtrResult.distanceMeters);
				if (mmtrCompositionNow != null) {
					mmtrAirState = MmtrComposition.encodeAirStates(mmtrCompositionNow);
					mmtrPipePressure = mmtrCompositionNow.averagePipePressure();
					mmtrBrakeCylinderPressure = mmtrCompositionNow.averageCylinderPressure();
				}
			} else {
				// 理论上不可达（服务端一定有 FALLBACK 车底；客户端没同步到 mmtrMode 的车根本不是 motion 车）。
				// 真到了这里就**喊出来并停住**：绝不静默变成另一套物理。
				speed = 0;
				integratedDistance = 0;
				mmtrLogNoControllerIfNeeded();
			}
		} else if (speed > 0) {
			// 无人掌权又还在滑行：按**本车自己的**常用制动减速度停住（原来是 VED 里的 MTR 减速度常数）。
			speed = Math.max(0, speed - mmtrMotionServiceDecelPerMs() * millisElapsed);
			integratedDistance = speed * millisElapsed;
		}

		/*
		 * 司机优先（2026-09-21）：这一整段"把车停在有效停车点上"的**夹紧**只对自动/无人车成立。
		 *
		 * <p>对司机来说，夹紧比"速度归 0"更隐蔽地拦人：车头被截在停车点上，之后再怎么推手柄都过不去
		 * （越过停车点的判据永远差一点点）。所以司机模式下这里**一条都不做**：闯过去由
		 * {@link #mmtrTickDriverAuthorityTrip()} 触发紧急制动（可解除），停车点的"到了"由下面
		 * "车真的停住"那一条登记。</p>
		 */
		if (brakeTargetActive && !mmtrMotionStoppedAtTarget && clampActive) {
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
				/*
				 * ★ **停车点在身后 ≠ 到了停车点**（2026-09-21 实机："车开不动"）。
				 *
				 * <p>累计停车点与锚点都可能落在车头**后面**（让位后重规划、估计偏短、或者车被人工开过头），
				 * 那时这一支每 tick 把 speed 钉成 0 —— 而"到了"那条路（{@code mmtrMotionArriveAtStopTarget}）
				 * 只认 {@code stopTargetConsumed} 的**累计**判据与"{@code speed == 0}"入口，
				 * 两者都够不着这个分支；于是**司机与自动都推不动它，而且全链路一句日志都没有**
				 * （HUD 的"状态"行也是空的 —— 那里只报 protection/闭塞/到点/尽头，没有"停车点过期"这一种）。</p>
				 *
				 * <p>判据：有效刹车点比车头**落后**超过一个到达容差 ⇒ 判为过期的估算，放掉目标让任务
				 * 重新自臂（与"停车里程估短了"那一支同一个处置），并**把理由打出来**。</p>
				 */
				if (brakeTargetM - mmtrMotionWalker.distanceM() < -MMTR_ARRIVAL_EPS_M) {
					mmtrDiscardStaleStopTarget(brakeTargetM);
				} else {
					integratedDistance = 0;
					speed = 0;
				}
			} else if (integratedDistance > remaining) {
				integratedDistance = remaining; // land exactly on the effective stop (target or block)
			}
		}
		/*
		 * 司机把任务给的停车点开过头了：不钉速度，但要**放掉那份过期的目标**，否则任务永远等不到到点
		 * （放掉之后任务下一 tick 用当前位置重新自臂/重规划，与"停车里程估短了"同一处置）。
		 */
		if (driverMayOverrun && stopTargetActive && !mmtrMotionStoppedAtTarget && mmtrMotionStopTargetM - mmtrMotionWalker.distanceM() < -MMTR_ARRIVAL_EPS_M) {
			mmtrDiscardStaleStopTarget(mmtrMotionStopTargetM);
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
			/*
			 * 司机优先：手动车只有在**真的停住**时才登记为"到点" —— 冲过停车点不再把速度清零
			 * （那是修前"司机开不动"的一个来源），而由 {@link #mmtrDiscardStaleStopTarget} 放掉过期目标。
			 */
			if (stopTargetActive && reachedStop && (!driverMayOverrun || speed <= MMTR_SUB_TASK_STOPPED_SPEED)) {
				speed = 0;
				mmtrMotionArriveAtStopTarget();
			} else if (!driverMayOverrun && mmtrBlockStopM < Double.MAX_VALUE / 2 && railProgress >= mmtrBlockStopM - 1e-6) {
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
			} else if (!mmtrBlockedWaiting && !driverMayOverrun) {
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
			mmtrLogStuckIfNeeded(overridden, wantPower, control, brakeTargetM);
			/*
			 * notes/235：**legacy 单手柄读数（powerLevel）不再由引擎合成**。
			 *
			 * <p>原来是"把三根手柄折算成一个正=牵引/负=制动的 powerLevel"给 MTR 原版仪表读
			 * （{@code MmtrSupport.controlFromLegacyPowerLevel} / {@code mmtrLegacyPowerLevelFromControl}）。
			 * 那套映射连同它代表的原版加减速模型一起删除了 —— 现在唯一的操纵语义就是
			 * {@link ControlState}（三根手柄 + 定速），HUD 也直接读 {@code MmtrDriveInput}。
			 * 镜像字段仍然保留在协议里（旧客户端会读），恒为 0 = 中性。</p>
			 */
			vehicleExtraData.setSpeedTarget(speed);
			updateMmtrSyncFields();
		}
	}

	/**
	 * **过期的停车点**：放掉它并说清理由（见刹车包线里那一段的注释）。
	 *
	 * <p>放掉之后任务会在下一 tick 用当前位置重新自臂（{@code mmtrMotionSelfArmMission}），
	 * 于是"开过头 / 估算偏短"变成一次重规划，而不是把车永久钉死在原地。</p>
	 */
	private void mmtrDiscardStaleStopTarget(double effectiveBrakeTargetM) {
		System.out.println("[MMTR-DRV] 停车点在身后（有效刹车点 " + Math.round(effectiveBrakeTargetM) + "m < 车头 "
			+ Math.round(mmtrMotionWalker.distanceM()) + "m）—— 判为过期的估算，放掉停车目标让任务重新自臂"
			+ "（不放就会每 tick 把车速钉成 0，司机与自动都开不动）");
		mmtrMotionAuto = false;
		mmtrMotionStopTargetM = -1;
		mmtrMotionStopRailHex = "";
		mmtrMotionStopFraction = -1;
		mmtrMotionStoppedAtTarget = false;
		mmtrBlockedWaiting = false;
		mmtrPlayerRoutePublished = false;
	}

	/**
	 * **司机优先（1）：司机在"无人编组"上动车时，引擎先给编组一个头。**
	 *
	 * <p>编组体走行器的一切都挂在"哪个驾驶室被占用"上（{@link MmtrConsistWalker#currentRail()}、
	 * {@link MmtrConsistWalker#railHex()}、{@code advance()} 都要求有人持钥匙）。无人时：
	 * 走行器不接受任何推进、{@code currentRail()} 为 null ⇒ 限速读成 0 ⇒ 规划器也判不可行 ——
	 * 于是"司机明明坐在驾驶室里推手柄，车在物理上一动不动"。</p>
	 *
	 * <p>这里补的是**引擎自己的占位钥匙**（与车场刷车同一把，{@code KeyHolder.SYSTEM}）：车一停稳、
	 * 司机按 G 申领驾驶室就会被乘务员钥匙顶掉，方向也随之由他的座位决定。
	 * 方向不会因此改变：无人时 {@code travelsToward(B)} 与 CAB_A 在岗时同为 false，所以
	 * {@code towardB = !travelReversed} 两边一致（编组不会被这根钥匙扳个头）。</p>
	 */
	private void mmtrAdoptUnmannedConsistForDriver(boolean overridden) {
		if (!overridden || speed > 1e-9 || !(mmtrMotionWalker instanceof final MmtrConsistWalker consistWalker) || consistWalker.cabs().isManned()) {
			return;
		}
		if (consistWalker.insertSystemKey(MmtrCabState.Cab.CAB_A, true)) {
			System.out.println("[MMTR-DRV] 司机在无人编组上动车：引擎补一把占位钥匙（CAB_A）让编组有头（车=" + id
				+ "）；停稳后按 G 申领驾驶室即可换成乘务员钥匙");
		}
	}

	/**
	 * **司机优先（2）：越界 = 施加紧急制动（可按响应键解除），而不是把速度钉成 0。**
	 *
	 * <p>用户口径（2026-09-21）：「过信号闯区间等问题，触发紧急制动就行了，紧急制动也是可按响应键解除的，
	 * 这才是正常逻辑，而不是给车直接速度归 0。只有在道岔没设置对会发生脱轨时才直接按到 0。」</p>
	 *
	 * <p>判据与真车一致：**紧急制动包线**（按紧急减速度算，车已经停不住的那一刻才动手）——
	 * 于是车是"被刹车停住"的，不是"被钉住"的：车头刚好停在界限上（最后那一小段由上面的夹紧收口），
	 * HUD 第一行说明是紧急制动并告诉司机按哪个键。</p>
	 *
	 * <p>解除：司机按响应键（{@link #applyMmtrControl} 的 acknowledge）⇒ 释放紧急制动，并把**这一处**
	 * 界限记为已放行；此后同一处不再触发、也不再夹紧（司机可以照他的意思开过去 = 闯区间），
	 * 直到前方出现**新的**停车点才重新武装。停着不动（没进包线）时**不触发** —— 在红灯前规规矩矩停住的
	 * 司机不该看到"闯信号"。</p>
	 */
	private void mmtrTickDriverAuthorityTrip() {
		if (mmtrMotionWalker == null) {
			return;
		}
		if (mmtrBlockStopM >= Double.MAX_VALUE / 2) {
			// 界限没了（前方区间空了 / 灯绿了 / 岔设好了）：越界触发的紧急制动自动解除。
			mmtrReleaseAuthorityTrip("前方界限已解除");
			mmtrAuthorityReleased = false; // 下一处越界重新武装
			return;
		}
		final double head = mmtrMotionWalker.distanceM();
		final double remaining = mmtrBlockStopM - head;
		if (speed <= MMTR_SUB_TASK_STOPPED_SPEED) {
			// 停着不动不算越界：在红灯前规规矩矩停住的司机不该看到"闯信号"，也不该被施加紧急制动。
			// （他再起步时才重新判 —— 包线一旦不够，紧急制动立刻上来。）
			if (remaining > MMTR_ARRIVAL_EPS_M) {
				mmtrAuthorityReleased = false;
			}
			return;
		}
		final double emergencyPerMs = mmtrEmergencyDecelPerMs();
		// 紧急制动包线：车还能在界限前停住就不干预；停不住（或已经越过了）才施加紧急制动。
		final boolean pastTheLimit = remaining <= MMTR_ARRIVAL_EPS_M;
		final boolean beyondEnvelope = remaining > 0 && DynamicsEnvelope.requiresBraking(speed, 0, remaining, emergencyPerMs, 1.0);
		if (!pastTheLimit && !beyondEnvelope) {
			mmtrAuthorityReleased = false; // 界限还在包线之外：重新武装（放行过的那一处已经落在身后）
			return;
		}
		if (mmtrAuthorityReleased || mmtrProtection) {
			return;
		}
		mmtrProtection = true;
		mmtrAuthorityTripped = true;
		mmtrProtectionLockRemaining = MMTR_PROTECTION_LOCK_MS;
		System.out.println("[MMTR-DRV] 司机越过未授权界限（" + mmtrBlockStopReason() + "，车头 " + Math.round(head)
			+ "m / 界限 " + Math.round(mmtrBlockStopM) + "m）—— 施加紧急制动；按响应键 R 解除后可继续");
	}

	/**
	 * 解除**司机越界**那一路的紧急制动（界限消失 / 授权到手时自动解除；司机按响应键走另一条路）。
	 * AWS 报警超时的 SPAD 不走这里 —— 它有自己的 10 s 自动解锁与状态机。
	 */
	private void mmtrReleaseAuthorityTrip(String why) {
		if (!mmtrAuthorityTripped) {
			return;
		}
		mmtrAuthorityTripped = false;
		mmtrAuthorityReleased = true;
		if (mmtrProtection) {
			mmtrProtection = false;
			mmtrProtectionLockRemaining = 0;
		}
		System.out.println("[MMTR-DRV] 越界紧急制动解除（" + why + "，车=" + id + "）");
	}

	/**
	 * 这一拍**真正施加**的纵向净加速度（m/s²，来自积分器每一步的 {@link DriveOutput}）。
	 *
	 * <p>notes/250：右上角 HUD 的"电机做功"读数用它/用控制器交出的实际比例，而不是另算一套 —— HUD 与
	 * 车辆物理必须是同一份数（"表说在出力、车却不动"是本仓最恨的一类现场）。</p>
	 */
	private double mmtrLastDriveAccelerationMps2;

	/**
	 * **电机当前出力**（N，牵引为正、电阻制动为负）—— 右上角 HUD 的"电机做功"（notes/250）。
	 *
	 * <p>三手柄车底：用控制器交出的**实际施加**比例反算轮周力（含黏着截断、含建力延迟，即"现在真在出多少力"），
	 * 电阻制动按负值计入。其它操纵模式没有单独的比例可读，用这一拍的净加速度 × 惯性质量折算
	 * （含阻力/制动，符号与量级都对）。</p>
	 */
	public double getMmtrMotorForceN() {
		/*
		 * 客户端**读服务端快照**（notes/259）：现场日志证明
		 *   ① 服务端每拍都算对（手柄 41% ⇒ 牵引比 44% ⇒ 122 kN；手柄 63% ⇒ 198 kN）；
		 *   ② 客户端**不跑** `simulateMoving`（我在那条路上加的诊断日志一行都没出现）⇒ 本机控制器的
		 *      `lastTractionRatio` 不会被推进 ⇒ 本机算会**卡住**（用户口径"牵引力卡住的状态"）。
		 * ⇒ 读数只能取快照值；它现在挂在 `VehicleSyncPatch.DYNAMIC_KEYS` 里（notes/257），每拍随补丁到客户端。
		 */
		return isClientside ? vehicleExtraData.getMmtrMotorForceN() : mmtrLiveMotorForceN();
	}


	/** 按**本机**这一刻的控制器比例与速度活算的电机出力（诊断/服务端权威值用）。 */
	private double mmtrLiveMotorForceN() {
		final ConsistType type = mmtrConsistType;
		if (type == null) {
			return 0;
		}
		final double speedSi = MmtrSupport.internalSpeedToSi(speed);
		if (mmtrDriveController instanceof final org.mtr.core.mmtr.ThreeHandleDriveController threeHandle) {
			final org.mtr.core.mmtr.ThreeHandleSpec spec = type.getHandles();
			if (spec != null) {
				final double tractionForceN = type.getPhysics().tractiveEffortN(threeHandle.getLastTractionRatio(), speedSi);
				// notes/266：电制动改走三段式（低速淡出 + 高速恒功率），与控制器里施加的那份力同源。
				final double rheostaticForceN = (spec.getBrakes() == null
					? spec.getRheostaticBrakeForceN() * spec.rheostaticFade(speedSi)
					: spec.rheostaticEffortN(speedSi)) * threeHandle.getLastRheostaticRatio();
				return tractionForceN - rheostaticForceN;
			}
		}
		return mmtrLastDriveAccelerationMps2 * type.getPhysics().effectiveMassKg();
	}

	/** **电机做功**（W）：牵引为正、电阻制动为负（再生/电阻耗能都是电机在过功）＝ 出力 × 速度。 */
	public double getMmtrMotorPowerW() {
		return getMmtrMotorForceN() * MmtrSupport.internalSpeedToSi(speed);
	}

	/**
	 * **列车管压力**（bar）—— 右上角 HUD 的"管压"（notes/266）。
	 *
	 * <p>{@code mmtrPipePressure} 是 0..1 的归一化读数（1 = 充风稳定值），这里按车底的气压规格折成 bar；
	 * 没有气压规格的车底（legacy 模式）按出厂满量程折算 —— 那种口径本来就把"满格"当定压。
	 * 服务端与客户端读的是同一份镜像字段，所以两边的读数不会各说各话。</p>
	 */
	public double getMmtrPipeBar() {
		return mmtrPipePressure * mmtrBarScale(true);
	}

	/** **制动缸压力**（bar）—— 右上角 HUD 的"缸压"（notes/266）。 */
	public double getMmtrCylinderBar() {
		return mmtrBrakeCylinderPressure * mmtrBarScale(false);
	}

	/** 归一化读数 → bar 的满量程：有气压规格就用它，没有就用出厂口径。 */
	private double mmtrBarScale(boolean pipe) {
		final org.mtr.core.mmtr.physics.PneumaticBrakeSpec air = mmtrPneumaticBrakeSpec();
		final org.mtr.core.mmtr.physics.PneumaticBrakeSpec scale = air == null
			? org.mtr.core.mmtr.physics.PneumaticBrakeSpec.defaults() : air;
		return pipe ? scale.getChargedBar() : scale.getCylinderMaxBar();
	}

	/** 这份车底的气压规格；{@code null} = 旧归一化模型（legacy 模式）。 */
	private org.mtr.core.mmtr.physics.PneumaticBrakeSpec mmtrPneumaticBrakeSpec() {
		return mmtrConsistType == null || mmtrConsistType.getHandles() == null ? null : mmtrConsistType.getHandles().getBrakes();
	}

	/**
	 * **这一拍作用在轮周上的气制动力**（N，正值 = 在刹车）—— HUD 的"制动力"里"气"的那一份（notes/266/269）。
	 *
	 * <p>服务端算**整列**的数：三手柄车底逐车求和（含逐车管压与电空混合削掉的那部分），
	 * legacy 车底按缸压比例线性折算。**客户端读镜像**（`mmtrPneumaticBrakeForceN`）——
	 * 逐车管压＋混合之后"车头缸压 × 帧"反算不再等于整列气制动力（车头可能被 EP 阀削到 0，
	 * 而拖车还在刹），现场就是"制动力只显示电制动的"那个 bug。</p>
	 */
	public double getMmtrPneumaticBrakeForceN() {
		if (isClientside) {
			return vehicleExtraData.getMmtrPneumaticBrakeForceN();
		}
		// notes/270：**任何操纵方式**只要在跑气压口径，气制动力都由制动模型逐车求和给出
		// （有级/无级/三手柄同一份读数；legacy 车底不接管 ⇒ 落到下面的按缸压折算）
		if (mmtrDriveController instanceof final org.mtr.core.mmtr.BrakeCarrier carrier && carrier.getBrakeModel().isPneumatic()) {
			return carrier.getBrakeModel().getPneumaticForceN();
		}
		if (mmtrDriveController instanceof final org.mtr.core.mmtr.ThreeHandleDriveController threeHandle) {
			return threeHandle.getLastPneumaticBrakeForceN();
		}
		final ConsistType type = mmtrConsistType;
		return type == null ? 0 : type.getBrake().serviceForceN(mmtrBrakeCylinderPressure);
	}

	/** **这一拍的电制动力**（N，正值 = 在制动）＝ 电机出力的负侧（与"电机"行走同一个读数）。 */
	public double getMmtrElectricBrakeForceN() {
		return Math.max(0, -getMmtrMotorForceN());
	}

	/**
	 * **制动力合计**（N，正值 = 在刹车）＝ 气 + 电，再经**黏着截断**（notes/267，规格模块四）。
	 *
	 * <p>轮轨能传下去的就那么多：干轨/湿轨对 BR101 的常用制动都不成问题，**落叶/油污**才真的截；
	 * 无 WSP 时断崖到动摩擦。legacy 车底（没有气压规格）不做截断 —— 与那些控制器自己的口径一致。</p>
	 *
	 * <p>注：客户端镜像的黏着按**干轨**走（`createMirrorConsistTypeFromSync` 里那两个字段还没进 schema，
	 * 是 S3 的遗留项）⇒ 湿轨/落叶下的截断只有服务端权威值知道，客户端 HUD 会偏乐观。</p>
	 */
	public double getMmtrBrakeForceN() {
		final double rawN = getMmtrPneumaticBrakeForceN() + getMmtrElectricBrakeForceN();
		final ConsistType type = mmtrConsistType;
		final org.mtr.core.mmtr.physics.PneumaticBrakeSpec air = mmtrPneumaticBrakeSpec();
		if (type == null || air == null) {
			return rawN;
		}
		return type.getPhysics().adhesionLimitedBrakingForceN(rawN, 0, MmtrSupport.internalSpeedToSi(speed),
			air.isWspEnabled(), air.getWheelSlipMu());
	}

	/** 这一拍的**制动力黏着上限**（N）—— HUD 用它标"黏着截断"。 */
	public double getMmtrBrakingAdhesionLimitN() {
		final ConsistType type = mmtrConsistType;
		return type == null ? 0 : type.getPhysics().brakingAdhesionLimitN(MmtrSupport.internalSpeedToSi(speed));
	}

	/** 这一拍制动力是不是**被黏着截断**了（notes/267）。 */
	public boolean isMmtrBrakingAdhesionLimited() {
		final ConsistType type = mmtrConsistType;
		if (type == null || mmtrPneumaticBrakeSpec() == null) {
			return false;
		}
		final double rawN = getMmtrPneumaticBrakeForceN() + getMmtrElectricBrakeForceN();
		return rawN > type.getPhysics().brakingAdhesionLimitN(MmtrSupport.internalSpeedToSi(speed)) + 1;
	}

	/**
	 * **手柄诉求的轮周力**（N）：{@code 手柄比例 × 牵引曲线}（含黏着上限），不含 AFB 的削减。
	 *
	 * <p>notes/255：用户 2026-09-23「杆在 20，游戏里显示 20，牵引力不是 300×0.2 = 60 kN」——
	 * 那时车已经在设定速度上，**AFB 把这一份力削到了 ~0**（这正是用户口径"AFB 根据速度来削减"）。
	 * 两个数并排显示，才不会再把"手柄诉求"与"实际出力"当成一回事。</p>
	 *
	 * @param driveHandle 手柄位置（客户端传本地值：司机刚推的那一下要立刻看见，不等包）
	 */
	public double getMmtrHandleDemandForceN(int driveHandle) {
		final ConsistType type = mmtrConsistType;
		final org.mtr.core.mmtr.ThreeHandleSpec spec = type == null ? null : type.getHandles();
		if (spec == null) {
			return 0;
		}
		return type.getPhysics().tractiveEffortN(spec.tractionRatio(spec.clampDriveHandle(driveHandle)), MmtrSupport.internalSpeedToSi(speed));
	}

	/** "推着油门却一动不动"的自白节流。 */
	private long mmtrStuckLogMillis;
	/** notes/252：镜像"电机出力"读数诊断的节流（2 s）。 */
	private long mmtrMirrorDiagMillis;
	/** 走哪一条物理分支的自白节流（有级/遗留 vs 车底类型）。 */
	private long mmtrBranchLogMillis;

	/**
	 * LZB 段（Signal S4）**这一拍的控制器加速度**（m/s²，牵引为正、制动为负）。
	 *
	 * <p>notes/264：LZB 只管天花板，出力必须由控制器决定（手柄比例 + AFB 只削不力 + 牵引联锁 + 驱动延迟）。
	 * 这一支每拍只调一次控制器 —— 于是控制器内部的空气状态与延迟滤波在 LZB 段也照常推进（原来这一支
	 * 根本不碰控制器），{@code mmtrLiveMotorForceN()} 报的力也才与真正施加的力一致
	 * （否则 HUD 会出现"车在 1.6 m/s² 加速、电机却显示 -31 kN"这种自相矛盾的行）。</p>
	 *
	 * <p>控制器拿不到（配置坏了）时退回满牵引并保持这一支原来的行为 —— 绝不静默变成"车不动"。</p>
	 */
	private double mmtrLzbControllerAccelMps2(ControlState control, long millisElapsed) {
		if (mmtrDriveController == null || mmtrConsistType == null) {
			return mmtrPhysics().tractionAccelerationMps2(1, MmtrSupport.internalSpeedToSi(speed));
		}
		return mmtrDriveController.compute(control, mmtrConsistType, MmtrSupport.internalSpeedToSi(speed), millisElapsed)
			.getAccelerationMetersPerSecondSquared();
	}

	/**
	 * **走了车底类型分支**（ConsistDynamics + 控制器）：把手柄、控制器算出的比例与本次距离打出来。
	 *
	 * <p>为什么需要它：2026-09-21 现场"手柄推到底、控制器牵引却只有 2%、车不动"，
	 * 光看 `[MMTR-STUCK]` 分不清"控制器没跑（比例是上一次的残留）"与"控制器跑了但算出 2%"。
	 * 这一条只在**真的走了这一支**时打，两者立刻分开。</p>
	 */
	private void mmtrLogConsistBranch(boolean overridden, @Nullable ControlState control, @Nullable DriveController controller, double distanceM) {
		// 手柄在关闭位但**定速挂着**时也要打：AFB 自己出力，这正是"定速到底给没给牵引"要看的现场
		// （2026-09-23：只设定速、手柄关闭的那一轮日志里一行都没有，白跑一趟）。
		if (!overridden || control == null || control.getDriveHandle() == 0 && control.getCruiseSpeedKmh() <= 0 || isClientside) {
			return;
		}
		final long now = data.getCurrentMillis();
		if (now - mmtrBranchLogMillis < 2000) {
			return;
		}
		mmtrBranchLogMillis = now;
		final String traction = controller instanceof final org.mtr.core.mmtr.ThreeHandleDriveController threeHandle
			? Math.round(threeHandle.getLastTractionRatio() * 100) + "%/电阻" + Math.round(threeHandle.getLastRheostaticRatio() * 100) + "% AFB=" + threeHandle.isAfbActive()
			: controller == null ? "无控制器" : controller.getClass().getSimpleName();
		/*
		 * notes/247：**必须把"这列车在按什么物理跑"打出来**。
		 *
		 * <p>为什么：现场"BR101+2×p1 被当成三台机车"（牵引 3×900 kN、起步 ~3 m/s²）时，日志里
		 * 只有手柄与牵引比，看不出质量/牵引上限 —— 只能靠速度序列反推，代价很大。质量、牵引上限、功率、
		 * 常用制动这四项一打，"借车底把牵引也借来了""挂车质量没进物理"这类问题一眼可见。</p>
		 */
		final org.mtr.core.mmtr.ConsistType physicsType = mmtrConsistType;
		final String physicsText = physicsType == null ? "" : " 质量=" + Math.round(physicsType.getMassKg() / 100) / 10.0 + "t"
			+ " λ=" + Math.round(physicsType.getRotatingMassFactor() * 100) / 100.0
			+ " 牵引=" + Math.round(physicsType.getTraction().getMaxTractiveEffortN() / 100) / 10.0 + "kN"
			+ " 功率=" + Math.round(physicsType.getTraction().getMaxPowerW() / 10000) / 100.0 + "MW"
			+ " 常用制动=" + Math.round(physicsType.getBrake().getServiceForceN() / 100) / 10.0 + "kN"
			+ " 车底=" + physicsType.getId();
		System.out.println("[MMTR-CONSIST] 车底类型分支：车=" + id
			+ " 手柄=" + control.getDriveHandle()
			+ " 制动=" + control.getBrakeNotch() + " 换向=" + control.getReverser()
			+ " 定速=" + control.getCruiseSpeedKmh()
			+ " 控制器=" + (controller == null ? "null" : controller.getClass().getSimpleName())
			+ " 牵引比=" + traction + " 速度=" + speed + " 本次距离=" + Math.round(distanceM * 1000) / 1000.0 + "m"
			// notes/248：把"为什么不走"所需的现场状态一起打出来（缸压/管压/LZB 上限/走行状态）。
			// 上一轮只能靠速度序列反推加速度、靠猜分辨"联锁按住牵引"还是"根本没走控制器"，代价太大。
			+ " 缸压=" + Math.round(mmtrBrakeCylinderPressure * 1000) / 1000.0
			+ " 管压=" + Math.round(mmtrPipePressure * 1000) / 1000.0
			// notes/252：电机出力（活算）+ 写进镜像的那份，两者不一致就是同步问题。
			+ " 电机=" + Math.round(mmtrLiveMotorForceN() / 1000) + "kN"
			+ " 镜像=" + Math.round(vehicleExtraData.getMmtrMotorForceN() / 1000) + "kN"
			// notes/269：整列气制动力（逐车求和）—— HUD 的「制动力（气）」，不是"管压折算"。
			+ " 气制动=" + Math.round(getMmtrPneumaticBrakeForceN() / 1000) + "kN"
			+ " LZB上限=" + getMmtrLzbCeilingKmh()
			+ " 状态=" + getMmtrRegime()
			+ physicsText);
	}

	/**
	 * **连兜底车底都拿不到**（理论上不可达）⇒ 喊出来并停住。
	 *
	 * <p>notes/235 删掉了"没有车底类型 ⇒ 退回 MTR 加减速常数"的退路，服务端一律会解析到
	 * {@link ConsistType#FALLBACK}（通用车），所以走到这里说明客户端的镜像同步出了问题
	 * （{@code mmtrMode} 为空却当成了 motion 车）。这种情形**绝不能静默**：静默就是"手柄没反应"。</p>
	 */
	private void mmtrLogNoControllerIfNeeded() {
		if (isClientside && mmtrNoControllerLogged) {
			return;
		}
		mmtrNoControllerLogged = true;
		System.out.println("[MMTR-CFG] 车=" + id + " 连兜底车底都没有（mmtrMode=" + (mmtrMode == null ? "null" : "'" + mmtrMode + "'")
			+ "，控制器=" + (mmtrDriveController == null ? "null" : mmtrDriveController.getClass().getSimpleName())
			+ "）—— 本车已被钉在 0 速，不会静默换一套物理。请检查镜像快照里的 mmtrMode / mmtrMassKg 等字段");
	}

	/** {@link #mmtrLogNoControllerIfNeeded()} 只报一次的闩。 */
	private boolean mmtrNoControllerLogged;

	/** "服务端收到的操纵"这条日志的限频（与 [MMTR-CONSIST] 同一节奏：2 s）。 */
	private long mmtrControlReceivedLogMillis;

	/**
	 * **服务端到底收到了什么**（限频 2 s）——客户端发→服务端收→控制器算，这条链的中间一环。
	 *
	 * <p>为什么要有它（用户 2026-09-23：「应该从操作是否从客户端来到服务端来检查」）：
	 * "手柄/定速在 HUD 上看得见"与"服务端拿到的是同一个值"是两件事（HID 轴覆盖、座位丢失后
	 * {@code neutraliseHandles()} 清零、包被拒……都会让两边不一致），而现场只能靠日志分清。</p>
	 */
	private void mmtrLogControlReceived(@Nullable UUID driverUuid) {
		if (isClientside || mmtrActiveControl == null) {
			return;
		}
		final long now = data.getCurrentMillis();
		if (now - mmtrControlReceivedLogMillis < 2000) {
			return;
		}
		mmtrControlReceivedLogMillis = now;
		System.out.println("[MMTR-DRV] 收到操纵：车=" + id + " 油门=" + mmtrActiveControl.getDriveHandle()
			+ " 制动=" + mmtrActiveControl.getBrakeNotch() + " 定速=" + mmtrActiveControl.getCruiseSpeedKmh()
			+ " 换向=" + mmtrActiveControl.getReverser() + " 司机=" + (driverUuid == null ? "（无身份）" : driverUuid));
	}

	/**
	 * **司机推着油门却一动不动时，把内情逐项打出来**（每 2 秒一条，只在真的"想走却走不了"时）。
	 *
	 * <p>2026-09-21 实机：车停在原地、司机与自动都推不动，而 HUD 的"状态"行是空的、日志里一句都没有 ——
	 * 因为那个分支不在 {@link #mmtrMotionHoldReason()} 的任何一条判据里。
	 * 这一条只做事后取证：把"距离 / 累计停车点 / 锚点剩余 / 有效刹车点 / 各种闩"摆在一行里，
	 * 下次这种现场不需要再猜。</p>
	 */
	private void mmtrLogStuckIfNeeded(boolean overridden, boolean wantPower, @Nullable ControlState control, double brakeTargetM) {
		if (isClientside || !overridden || speed > 1e-9 || mmtrMotionWalker == null) {
			return;
		}
		/*
		 * 判据刻意**不**只认 {@code wantPower}：那条读的是 {@code control.getThrottleNotch()}，
		 * 而三手柄车底**故意发 0**（牵引看 {@code driveHandle}）—— 上一版据此把它写成哑判据，
		 * 于是在真正需要自白的那台 BR101 上一行都不打（2026-09-21 实机）。
		 * 现在：只要司机**表达了要走**（三根手柄任一非中性、或定了速、或有级油门>0），
		 * 而车不动，就说。
		 */
		final boolean driverWantsMotion = wantPower
			|| control != null && (control.getDriveHandle() != 0 || control.getCruiseSpeedKmh() > 0);
		if (!driverWantsMotion) {
			return;
		}
		final long now = data.getCurrentMillis();
		if (now - mmtrStuckLogMillis < 2000) {
			return;
		}
		mmtrStuckLogMillis = now;
		final double anchorRemaining = mmtrRemainingToStopAnchor();
		final String gate = mmtrMotionHoldReason();
		final String traction = mmtrDriveController instanceof final org.mtr.core.mmtr.ThreeHandleDriveController threeHandle
			? Math.round(threeHandle.getLastTractionRatio() * 100) + "%（电阻制动 " + Math.round(threeHandle.getLastRheostaticRatio() * 100)
				+ "% AFB=" + threeHandle.isAfbActive() + "）"
			: "n/a";
		System.out.println("[MMTR-STUCK] 推着油门却不动："
			+ "司机手柄[油门=" + (control == null ? "?" : control.getDriveHandle())
			+ " 制动=" + (control == null ? "?" : control.getBrakeNotch())
			+ " 换向=" + (control == null ? "?" : control.getReverser())
			+ " 定速=" + (control == null ? "?" : control.getCruiseSpeedKmh()) + "]"
			+ " 控制器牵引=" + traction
			+ " 距离=" + Math.round(mmtrMotionWalker.distanceM()) + "m"
			+ " 累计停车点=" + (mmtrMotionStopTargetM < 0 ? "无" : Math.round(mmtrMotionStopTargetM) + "m")
			+ " 锚点=" + (mmtrMotionStopRailHex.isEmpty() ? "无" : mmtrMotionStopRailHex.substring(0, 8))
			+ " 锚点剩余=" + (anchorRemaining == Double.MAX_VALUE ? "不适用" : Math.round(anchorRemaining * 10) / 10.0 + "m")
			+ " 有效刹车点=" + (brakeTargetM == Double.MAX_VALUE ? "无" : Math.round(brakeTargetM * 10) / 10.0 + "m")
			+ " auto=" + mmtrMotionAuto + " 到点闩=" + mmtrMotionStoppedAtTarget + " 闭塞等待=" + mmtrBlockedWaiting
			+ " 走行器[到目标=" + mmtrMotionWalker.atTarget() + " 尽头=" + mmtrMotionWalker.endOfLine()
			+ " 被权威扣=" + mmtrMotionWalker.haltedAtAuthority() + "]"
			+ " 任务=" + (mmtrMission == null ? "无" : mmtrMission.getState() + "/" + mmtrMission.getExecutor())
			+ " 闸门=" + (gate.isEmpty() ? "（引擎认为自己没被任何东西按住）" : gate));
	}

	/** Marks the exact arrival at the armed stop target: rest, doors per the stop request, hold. */
	private void mmtrMotionArriveAtStopTarget() {		speed = 0;
		mmtrMotionStoppedAtTarget = true;
		mmtrMotionArrivalControlSeq = mmtrControlApplySeq;
		vehicleExtraData.closeDoors();
		if (mmtrMotionStopOpenDoors) {
			vehicleExtraData.openDoors();
		}
		System.out.println("[MMTR-DRV] motion arrived at stop target " + Math.round(mmtrMotionStopTargetM * 100.0) / 100.0 + "m (doors " + (mmtrMotionStopOpenDoors ? "open" : "closed") + ")");
	}

	/**
	 * **本车的纵向力学**（正向半的唯一入口）。
	 *
	 * <p>没有解析出车底类型时退回 {@link ConsistType#FALLBACK_PHYSICS}（MMTR 的一节通用车，
	 * **不是**旧的 MTR 加减速常数 —— 那套已按 notes/235 删除），并且**说出来**：
	 * "按旧文档配的车底"或"carTypeIds 没配"必须看得见，而不是静默变成另一套手感。</p>
	 */
	private org.mtr.core.mmtr.physics.TrainPhysics mmtrPhysics() {
		if (mmtrConsistType != null) {
			return mmtrConsistType.getPhysics();
		}
		if (!mmtrFallbackPhysicsLogged) {
			mmtrFallbackPhysicsLogged = true;
			System.out.println("[MMTR-DRV] 车=" + id + " 没有车底类型（consist-types 里既没匹配到车型、也没配 defaultConsistTypeId）"
				+ " —— 暂用通用车力学（60 t / 100 kN / 600 kW），请修配置");
		}
		return ConsistType.FALLBACK_PHYSICS;
	}

	/** 还没解析出车底时，只报一次的闩。 */
	private boolean mmtrFallbackPhysicsLogged;

	/** 零牵引（整列没有任何一节能出力）已经报过一次的闩（notes/338）。 */
	private boolean mmtrZeroTractionLogged;

	/**
	 * notes/338：**整列零牵引**只报一次，但必须报。
	 *
	 * <p>现场：世界里没装 {@code mmtr-consist-types.json} ⇒ 每节车的车底都解析不出来，全列借缺省车底；
	 * 缺省车底只给**一节**保留牵引，而编组的头车是显式无动力的控制车（Tc–M–M–T…），于是
	 * {@code MmtrComposition.toConsistType} 求和后整列牵引 = 0 —— 车按阻力倒着加速、位置不动、
	 * 任何闸门都不报原因。这一句把"谁的锅"直接说出来（改法就是给车型配上 {@code consistTypeId}／
	 * 把 {@code mmtr-consist-types.json} 放进维度目录）。</p>
	 */
	private void mmtrLogZeroTractionIfNeeded() {
		if (mmtrZeroTractionLogged) {
			return;
		}
		mmtrZeroTractionLogged = true;
		final StringBuilder powered = new StringBuilder();
		for (int i = 0; i < vehicleExtraData.immutableVehicleCars.size(); i++) {
			powered.append(i == 0 ? "" : ",").append(vehicleExtraData.immutableVehicleCars.get(i).getMmtrPowered() ? "M" : "T");
		}
		System.out.println("[MMTR-DRV] 车=" + id + " **整列零牵引**（车节 "
			+ vehicleExtraData.immutableVehicleCars.size() + " 节 [" + powered + "]"
			+ "，车底=" + (mmtrConsistType == null ? "（无）" : mmtrConsistType.getId()) + "）"
			+ " —— 自动巡航只能按阻力积分，车停在原地不动。"
			+ "原因通常是世界目录里没有 mmtr-consist-types.json（每节车都解析不出车底、全列借缺省车底，"
			+ "而借来的那一节必须自己有动力），或者整列全是拖车；notes/338");
	}

	/** 遗留路径上的存量车：只报一次的闩（notes/235）。 */
	private boolean mmtrLegacyOnRouteLogged;

	/**
	 * 这辆车还在 MTR 遗留路径上（在路线上却没有走行器）⇒ 它没有牵引也没有制动。
	 *
	 * <p>notes/235：原版走行路径删除之后，这种情况只可能是"世界里还没被重编组的存量车"。
	 * 只报一次，但**必须报** —— 静默不动正是现场最难查的那种故障。HUD 那边由
	 * {@link #mmtrMotionHoldReason()} 给出同一句话。</p>
	 */
	private void mmtrLogLegacyOnRouteIfNeeded() {
		if (mmtrLegacyOnRouteLogged) {
			return;
		}
		mmtrLegacyOnRouteLogged = true;
		System.out.println("[MMTR-DRV] 车=" + id + " 在 MTR 遗留路径上、没有走行器 —— 原版走行路径已按 notes/235 删除，本车不会动。"
			+ "请重新编组：query depots → manifest add <depotId> <sidingId> <车型…> → vehicle remove --depot=<id> → manifest replay");
	}

	/** 当前速度下的**紧急制动减速度**（内部单位 m/ms²）。 */
	private double mmtrEmergencyDecelPerMs() {
		return MmtrSupport.siAccelerationToInternal(mmtrPhysics().emergencyDecelerationMps2(MmtrSupport.internalSpeedToSi(speed)));
	}

	/** Service deceleration in internal units (m/ms per ms)：全常用制动 + 运行阻力折成的减速度。 */
	private double mmtrMotionServiceDecelPerMs() {
		return MmtrSupport.siAccelerationToInternal(mmtrPhysics().serviceBrakeDecelerationMps2(1, MmtrSupport.internalSpeedToSi(speed)));
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
		// 灯光开关（notes/352/353）：控制包里带的是"司机所在驾驶室那一端"的档位，端由引擎的占用状态决定。
		// **只在这个来源真的扳动了开关时才生效**（边沿语义）—— 否则两个控制来源会以 tick 频率互相覆盖。
		applyMmtrLightSwitch(mmtrActiveControl.getLightSwitch(), driverUuid);
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
			/*
			 * **响应键也是"解除紧急制动"的键**（司机优先，用户口径 2026-09-21）：手动车闯过信号/占用
			 * 触发的紧急制动，由司机自己按键解除后继续开 —— 这正是真车 SCR/TPWS 的做法，而不是
			 * 把车速钉成 0 让他推不动。解除后同一处停车点不再反复触发（见 mmtrAuthorityReleased）。
			 */
			if (mmtrProtection) {
				mmtrProtection = false;
				mmtrProtectionLockRemaining = 0;
				if (mmtrAuthorityTripped) {
					// 司机越界那一路：放行**这一处**界限，此后不再触发也不再夹紧（他可以开过去）。
					mmtrAuthorityReleased = true;
					mmtrAuthorityTripped = false;
				}
				System.out.println("[MMTR-DRV] 紧急制动已由响应键解除（车=" + id + "）—— 司机可继续开车");
			}
			mmtrActiveControl.setAcknowledge(false);
		}
		if (!wasOverride && driverUuid != null) {
			System.out.println("[MMTR-DRV] driver=" + driverUuid + " engaged override T" + controlState.getThrottleNotch() + " B" + controlState.getBrakeNotch() + " R" + controlState.getReverser()
				+ " 三手柄[油门=" + mmtrActiveControl.getDriveHandle() + " 制动=" + mmtrActiveControl.getBrakeNotch() + " 定速=" + mmtrActiveControl.getCruiseSpeedKmh() + "]");
		}
		mmtrLogControlReceived(driverUuid);
		mmtrLogHandleMismatch(mmtrActiveControl);
		/*
		 * 序号在**每一次**控制应用时推进 = "司机又动了一次手柄"（既有契约，用例与客户端都依赖它：
		 * 停在停车点上时再推一次手柄即可发车）。客户端每秒的补发也会推进它 —— 那是**已知的遗留**
		 * 现象（停在停车点时手柄若还留在牵引位，补发包会让车在下一次补发时起步），
		 * 与"司机优先"这一轮无关，见 notes/233 §遗留。
		 */
		mmtrControlApplySeq++;
	}

	/**
	 * 司机推上来的灯光开关档位落到**被占用那一端**（另一端不动）。
	 *
	 * <p>端由引擎的占用状态决定、不是客户端说了算 —— 于是"改别人那一端的灯"在协议上不存在。
	 * 档位再按车底能力钳一次（动车组收到"关闭" ⇒ 尾灯），见 {@link MmtrLightSwitch#sanitize}。</p>
	 *
	 * <h2>⚠️ 这是**边沿型**输入，不能每拍应用一次（2026-10-01 实机）</h2>
	 *
	 * <p>控制包每拍都在发（里面是一个**绝对量**），而灯光开关在真车上、在这套设计里都是**保持型开关**：
	 * "谁扳动了开关谁说话"。一列车上同时存在两个控制来源时（自动运行的 SYSTEM + 坐进驾驶室的玩家），
	 * 按"每拍把当前活动控制状态的档位写一遍"就会让两边的值以 tick 频率互相覆盖。实机现场：</p>
	 *
	 * <pre>
	 * [MMTR-LIGHT] 车=… 灯光开关：A端 = 尾灯
	 * [MMTR-LIGHT] 车=… 灯光开关：A端 = 远光
	 * [MMTR-LIGHT] 车=… 灯光开关：A端 = 尾灯      ← 每秒**正好 20 条**（= 20 TPS，一拍翻一次）
	 * …</pre>
	 *
	 * <p>所以这里记住**每个来源最后一次请求的档位**，只有它变了才真的去扳开关：自动运行那边的档位一直是
	 * 出厂值（尾灯），于是它只在第一次生效；玩家每按一次 L 才产生一次真正的扳动。用户口径"按 L 会连续
	 * 切换灯光档位"就是这条。</p>
	 *
	 * @param driverUuid 这一拍的请求来自谁（{@code null} = 引擎内部/自动运行）
	 */
	private void applyMmtrLightSwitch(int requested, @Nullable UUID driverUuid) {
		final MmtrConsistWalker consistWalker = getMmtrConsistWalker();
		if (consistWalker == null || !consistWalker.cabs().isManned()) {
			return;
		}
		final String source = driverUuid == null ? "" : driverUuid.toString();
		final Integer previous = mmtrLightSwitchRequests.get(source);
		if (previous != null && previous == requested) {
			return;
		}
		mmtrLightSwitchRequests.put(source, requested);
		final boolean hasOff = mmtrConsistType != null && mmtrConsistType.hasMmtrLightOffPosition();
		setMmtrLightSwitch(getMmtrActiveCab() == MmtrCabState.Cab.CAB_B ? MmtrLightSwitch.END_B : MmtrLightSwitch.END_A,
			MmtrLightSwitch.sanitize(requested, hasOff));
	}

	/**
	 * 每个控制来源最后一次"扳动"的档位（见 {@link #applyMmtrLightSwitch}）。
	 *
	 * <p>刻意**不在司机离开时清空**：清空会让"客户端还记着旧档位、隔一拍又发一次同样的值"变成一次新的
	 * 扳动，把 {@code releaseMmtrLightSwitch} 刚落回的尾灯又点亮。客户端的档位跟着镜像走
	 * （{@code MmtrDriveInput.adoptLightCapability}），所以它下一次发来的值本来就是引擎这边的新值。</p>
	 */
	private final java.util.HashMap<String, Integer> mmtrLightSwitchRequests = new java.util.HashMap<>();

	/**
	 * 直接设某一端的灯光开关（{@link MmtrLightSwitch#END_A} / {@link MmtrLightSwitch#END_B}）。
	 *
	 * <p>给引擎内部（司机输入、司机离开时的回落）与将来的**连挂逻辑**用：连挂只需把被挂那一端设成
	 * {@link MmtrLightSwitch#OFF}，那一端的尾灯就真的灭了 —— 那一步不必再改判据（notes/352）。</p>
	 *
	 * @return 真的改了吗（没变就不标脏、不打日志）
	 */
	public boolean setMmtrLightSwitch(int end, int state) {
		final boolean hasOff = mmtrConsistType != null && mmtrConsistType.hasMmtrLightOffPosition();
		final int sanitised = MmtrLightSwitch.sanitize(state, hasOff);
		if (end == MmtrLightSwitch.END_B) {
			if ((int) mmtrLightB == sanitised) {
				return false;
			}
			mmtrLightB = sanitised;
		} else {
			if ((int) mmtrLightA == sanitised) {
				return false;
			}
			mmtrLightA = sanitised;
		}
		vehicleExtraData.mmtrMarkSyncDirty();
		System.out.println("[MMTR-LIGHT] 车=" + id + " 灯光开关：" + (end == MmtrLightSwitch.END_B ? "B" : "A") + "端 = "
			+ MmtrLightSwitch.label(sanitised) + "（" + MmtrLightSwitch.describe((int) mmtrLightA, (int) mmtrLightB, (int) mmtrReverser, hasOff) + "）");
		return true;
	}

	/**
	 * 司机离开（占用锁释放）时把**白灯**落回尾灯（notes/352）。
	 *
	 * <p>开关是保持型的（真车也是），但"没人照看的车两端亮着白前照灯"既不真实、也会一直亮在世界上。
	 * 所以只回落<b>近光/远光</b> ⇒ 尾灯；{@link MmtrLightSwitch#TAIL} 与 {@link MmtrLightSwitch#OFF}
	 * （机车的关闭档 = 连挂灭尾灯）原样保留 —— 换端时那一步也正好把"对面那端原本的白灯"清掉。</p>
	 */
	private void releaseMmtrLightSwitch() {
		if (MmtrLightSwitch.isHeadlight((int) mmtrLightA)) {
			mmtrLightA = MmtrLightSwitch.TAIL;
			vehicleExtraData.mmtrMarkSyncDirty();
		}
		if (MmtrLightSwitch.isHeadlight((int) mmtrLightB)) {
			mmtrLightB = MmtrLightSwitch.TAIL;
			vehicleExtraData.mmtrMarkSyncDirty();
		}
	}

	/*
	 * ---------------------------------------------------------------------------------------------
	 * **自动运行（"AI 驾驶员"）的灯光**：车头白灯、车尾红尾灯（用户口径 2026-10-03
	 * 「AI驾驶员也需要控制车灯开关，并且尾部车灯需要变红」）。
	 *
	 * <p>为什么需要一条引擎侧的规则，而不是"等客户端画的时候猜"：灯光开关是**引擎权威**的
	 * （notes/352：每端一个档位 → 镜像 {@code mmtrLightA}/{@code mmtrLightB} → 所有客户端照着画），
	 * 而拨开关的人只有一个 —— 坐在驾驶室里按 L 的司机。无人那趟车（{@code KeyHolder.SYSTEM}
	 * 的占位钥匙 + AUTOPILOT 任务）没有这个人，于是：
	 * <ul>
	 *   <li>车头一路是暗的（两端都停在出厂值 {@link MmtrLightSwitch#TAIL}）；</li>
	 *   <li>更要紧的是**车尾那一端**：上一次人工驾驶按过 L 的那一端，白灯留在镜像里没人收
	 *       （{@code releaseMmtrLightSwitch} 只在占用锁释放那一刻跑一次，漏了就一直漏）。</li>
	 * </ul>
	 * 于是这里按"AI 也是司机"来办：任务在跑、钥匙是引擎的、没人接管 ⇒ 车头 = 世界时决定的
	 * 近光/远光前照灯，车尾 = 尾灯（**两端都写死**，陈旧的白灯因此每次都会被收掉）。
	 *
	 * <p><b>司机优先</b>：有乘务员钥匙（有人在驾驶室里）时这条规则**一条都不动** —— 灯归他；
	 * 他下车（占用锁释放）之后自动运行若还在，下一 tick 这条规则再把车头点亮。
	 *
	 * <p><b>交还</b>：任务收工 / AI 不再持钥匙时，白灯落回尾灯（"无人照看的车两端都是红标志灯"，
	 * notes/352 的不变量 2）。
	 * ---------------------------------------------------------------------------------------------
	 */

	/** 自动灯光策略上一次真正写下去的**车头端**（{@code 0} = 还没写过 / 已经交还）。 */
	private int mmtrAutoLightFrontEnd;
	/** 自动灯光策略上一次写下去的车头档位（{@link MmtrLightSwitch#LOW} / {@link MmtrLightSwitch#HIGH}）。 */
	private int mmtrAutoLightState;
	/** 自动灯光策略上一次写下去时的行驶方向（REV 一翻，车头/车尾就互换了）。 */
	private boolean mmtrAutoLightReversed;

	/**
	 * 每 tick 判一次"这趟车现在该不该由引擎拨灯、该拨成什么"（服务端；见上面那段注释）。
	 *
	 * <p>边沿型：只有"车头端 / 档位 / 方向"三者之一变了才写镜像（每 tick 都写会让
	 * {@code setMmtrLightSwitch} 的日志与稀疏补丁失去意义）。</p>
	 */
	private void mmtrTickAutoLights() {
		final MmtrConsistWalker consistWalker = getMmtrConsistWalker();
		if (consistWalker == null) {
			return;
		}
		final MmtrMission mission = mmtrMission;
		/*
		 * "AI 在开"的判据（三条都要）：① 车上挂着一个**没结束**的任务，执行者是 AUTOPILOT/AI；
		 * ② 钥匙是引擎那把占位钥匙（没人接手）；③ 没有人工接管（司机推了手柄就没收）。
		 *
		 * 为什么不用 `mmtrMotionAuto`：它在**每次停车点重规划/换向翻转**时都会被清掉再自臂
		 * （现场就是"到站停稳 → 下一段自臂"），拿它当判据会让车头灯每到一站闪一下。
		 */
		final boolean aiDriving = mission != null && !mission.isTerminal()
			&& (mission.getExecutor() == MmtrMission.Executor.AUTOPILOT || mission.getExecutor() == MmtrMission.Executor.AI)
			&& consistWalker.cabs().isSystemKey() && !mmtrManualOverride;
		if (!aiDriving) {
			final boolean wasMine = mmtrAutoLightFrontEnd != 0;
			mmtrAutoLightFrontEnd = 0;
			// **有乘务员钥匙 ⇒ 灯归他**：引擎这条规则一条都不动（司机优先）。
			if (consistWalker.cabs().isCrewKey()) {
				return;
			}
			/*
			 * **有人在开（人工接管）也不许动他的灯**：钥匙可能还是引擎那把 SYSTEM 占位钥匙
			 * （现场：司机在无人编组上推手柄，引擎补一把钥匙让编组有头，见
			 * {@link #mmtrAdoptUnmannedConsistForDriver}），而他刚按 L 拨的档位就是他要的答案。
			 */
			if (mmtrManualOverride) {
				return;
			}
			/*
			 * 无人照看（没有 AI 任务 / 钥匙不在 / 没人接管）⇒ **白灯落回尾灯**（notes/352 的不变量 2
			 * "无人照看的车两端都是红标志灯"）。
			 *
			 * <p>为什么这里每 tick 都判一次、而不是只在"AI 交还"那一刻做：人工驾驶那一次的回落
			 * （{@code releaseMmtrManualOverride} → {@code releaseMmtrLightSwitch}）只要漏一次，
			 * 那盏白灯就**永远**留在镜像里 —— 现场表现正是用户报的"AI 开的车尾灯不红"。
			 * 这条兜底幂等（只有真有白灯时才写字），于是那种陈旧值最多活一 tick。</p>
			 */
			if (wasMine || MmtrLightSwitch.isHeadlight((int) mmtrLightA) || MmtrLightSwitch.isHeadlight((int) mmtrLightB)) {
				final String before = MmtrLightSwitch.describe((int) mmtrLightA, (int) mmtrLightB, (int) mmtrReverser, mmtrLightLoco);
				releaseMmtrLightSwitch();
				System.out.println("[MMTR-LIGHT] 车=" + id + (wasMine ? " 自动运行灯光交还" : " 无人照看：收掉陈旧白灯")
					+ "：两端落回尾灯（原 " + before + "，AI 不再执行任务 / 不再持钥匙）");
			}
			return;
		}
		final int frontEnd = MmtrLightSwitch.autoLeadingEnd(consistWalker.travelsTowardB());
		// 世界时（引擎手上那份来自游戏端 SetTime 的钟）：负值 = 还不知道 ⇒ 按远光那一档（最亮）。
		final int gameHour = data instanceof final Simulator simulator ? simulator.getGameHour() : -1;
		final int state = MmtrLightSwitch.autoHeadlightState(gameHour);
		final boolean reversed = consistWalker.travelReversed();
		if (frontEnd == mmtrAutoLightFrontEnd && state == mmtrAutoLightState && reversed == mmtrAutoLightReversed) {
			return;
		}
		mmtrAutoLightFrontEnd = frontEnd;
		mmtrAutoLightState = state;
		mmtrAutoLightReversed = reversed;
		final int rearEnd = MmtrLightSwitch.otherEnd(frontEnd);
		/*
		 * 镜像换向器：自动运行**在档**，**先于两端开关写**。两个理由：
		 *  ① 判据上：客户端 {@code lampState} 的"换向 N ⇒ 固定红"读的就是它，不先写在档，
		 *     接下来那两条 {@code setMmtrLightSwitch} 的日志里会印出"（换向N：固定红）"这种自相矛盾的读数；
		 *  ② 语义上：自动车从没被 applyMmtrControl 写过这个字段，镜像里恒为 N —— 不写它，
		 *     就算车头开关拨到远光，客户端也照样画成红的。
		 */
		final int reverser = MmtrLightSwitch.autoReverser(reversed);
		if ((int) mmtrReverser != reverser) {
			mmtrReverser = reverser;
			vehicleExtraData.mmtrMarkSyncDirty();
		}
		/*
		 * 两端都写：车尾先写（先生效）。车尾那一端的陈旧白灯（上一次人工驾驶留下的）就是这样被收掉的 ——
		 * 用户看到的就是"AI 开的车尾灯不红"。
		 */
		setMmtrLightSwitch(rearEnd, MmtrLightSwitch.TAIL);
		setMmtrLightSwitch(frontEnd, state);
		System.out.println("[MMTR-LIGHT] 车=" + id + " 自动运行灯光：车头=" + endLabel(frontEnd) + "端 " + MmtrLightSwitch.label(state)
			+ "（世界时 " + gameHour + " 时）· 车尾=" + endLabel(rearEnd) + "端 " + MmtrLightSwitch.label(MmtrLightSwitch.TAIL)
			+ " · 换向=" + (reversed ? "REV（车尾在前）" : "前进"));
	}

	private static String endLabel(int end) {
		return end == MmtrLightSwitch.END_B ? "B" : "A";
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
		releaseMmtrLightSwitch();
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
	 * **计划内接管用**：把"自动车那一份"停车点与自臂闩清掉，让这一步按 PLAYER 口径重新自臂一次。
	 *
	 * <p>交接时车上可能还留着自动车的停车点（那是按"引擎接管油门"算出来的）。不清它，
	 * 玩家任务会带着一份给自动车准备的停车目标继续跑；清掉之后下一 tick 的
	 * {@code mmtrMotionSelfArmMission} 会重新规划、并给出**同样是停车点、但不接管油门**的那一份。</p>
	 */
	public void clearMmtrMotionStopTargetForHandover() {
		if (isClientside) {
			return;
		}
		mmtrMotionStopTargetM = -1;
		mmtrMotionStopRailHex = "";
		mmtrMotionStopFraction = -1;
		mmtrMotionStoppedAtTarget = false;
		mmtrPlayerRoutePublished = false;
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
	 * **传送上车 / 计划内接管时该进哪个驾驶室** —— 由"这列车接下来要往哪边走"决定，
	 * 而不是"第一个可用的驾驶室"。
	 *
	 * <h3>为什么需要它（2026-09-21 实机）</h3>
	 * <p>原先挑的是车节 0 的 A 端（{@code 1A}）。但**方向恰恰是由被占用的驾驶室决定的**：
	 * {@code MmtrConsistWalker.towardB() = cabs.travelsToward(B) != travelReversed}。
	 * 车场刷出来的车带着引擎的 SYSTEM 占位钥匙、方向已经定死；人再坐进另一端的驾驶室，
	 * 结果只有两种，两种都是**司机坐在车尾朝前看**：要么把方向拧反，要么自臂发现规划不出来、
	 * 把换向器翻过去反向行驶（{@code mmtrMotionSelfArmMission} 的 flip 分支）。</p>
	 *
	 * <h3>判据（与自臂用同一条）</h3>
	 * <p>"任务要往哪边走"只有规划器知道，而且**方向是它的输入不是输出**（同一条进路按当前方向规划，
	 * 规划不出来时自臂就翻换向器再试）。所以这里照抄那条判据：先按当前方向规划 ——
	 * 可行 ⇒ 任务方向 = 当前方向；不可行 ⇒ 任务方向 = 当前方向的反面。然后取**那一边端头的驾驶室**，
	 * 并要求换向器归位（驾驶室朝前）。</p>
	 *
	 * @return 驾驶室写法 {@code <车节序号><A|B>}（1 起，例如 {@code 1A} / {@code 1B}）；
	 *         空串 = 不是编组体车（没有驾驶室模型），调用方按"不指定"处理
	 */
	public String mmtrPreferredCabSpec() {
		final MmtrConsistWalker consistWalker = getMmtrConsistWalker();
		if (consistWalker == null || !(data instanceof final Simulator simulator)) {
			return "";
		}
		// 已经有乘务员拿着钥匙：方向由他的驾驶室决定 —— 把人塞到另一端等于把方向拧过来，
		// 那不是"帮他把驾驶室纠正"，那是抢方向盘。
		if (consistWalker.cabs().isCrewKey()) {
			return mmtrCabSpecOf(consistWalker, consistWalker.cabs().activeCab());
		}
		final boolean requiredTowardB = mmtrRequiredTravelTowardB(simulator, consistWalker);
		return mmtrCabSpecOf(consistWalker, requiredTowardB ? MmtrCabState.Cab.CAB_B : MmtrCabState.Cab.CAB_A);
	}

	/**
	 * 这列车接下来**必须朝 B 端跑吗**（{@code travelsTowardB()} 的目标值）。
	 *
	 * <p>没有可判的目标（无任务 / 原地任务 / 尽头换端）时维持现状：那种情形下方向不是问题，
	 * 而"随便翻一个"会让司机坐进反的那一端。</p>
	 */
	private boolean mmtrRequiredTravelTowardB(Simulator simulator, MmtrConsistWalker consistWalker) {
		final boolean currentTowardB = consistWalker.travelsTowardB();
		final Rail targetRail = mmtrMissionTargetRail(simulator);
		if (targetRail == null || targetRail.getHexId().equals(consistWalker.railHex())) {
			return currentTowardB;
		}
		final double stopFraction = mmtrMission != null && mmtrMission.hasTargetRail() ? mmtrMission.getTargetRailFraction() : 1.0;
		// 经由点也带上：判断"该朝哪端跑"用的是**这条进路**能不能成立，忽略经由点会按另一条引入线给出反的结论。
		final MmtrRunPlanner.Plan plan = MmtrRunPlanner.planToRail(simulator, this, targetRail.getHexId(), stopFraction,
			mmtrMission == null ? null : mmtrMission.getTargetViaNodes());
		return plan.feasible ? currentTowardB : !currentTowardB;
	}

	/** 当前任务的**目的轨**（站台/股道 id 或轨目标）；没有可判的目标时 {@code null}。 */
	private org.mtr.core.data.@Nullable Rail mmtrMissionTargetRail(Simulator simulator) {
		final MmtrMission mission = mmtrMission;
		if (mission == null || mission.isTerminal() || mission.isInPlace()) {
			return null;
		}
		if (mission.hasTargetRail()) {
			return MmtrRunPlanner.findRailByHex(simulator, mission.getTargetRailHex());
		}
		final long targetSidingId = mission.getTargetSidingId();
		return targetSidingId == 0 ? null : MmtrRunPlanner.findSavedRailRail(simulator, targetSidingId);
	}

	/** 端头驾驶室的写法：A 端 = 第 1 节的 A；B 端 = 最后一节的 B。 */
	private static String mmtrCabSpecOf(MmtrConsistWalker consistWalker, MmtrCabState.Cab cab) {
		if (cab == MmtrCabState.Cab.CAB_B) {
			return Math.max(1, consistWalker.body().carCount()) + "B";
		}
		if (cab == MmtrCabState.Cab.CAB_A) {
			return "1A";
		}
		return "";
	}

	/**
	 * 交接给司机时把**换向器归位**（驾驶室朝前）。
	 *
	 * <p>自动运行为了走得通会把换向器翻过去（尾在前），那是引擎的事；人接手时要的是"我坐在车头往前看"。
	 * 只在停稳时动 —— 滚行中翻换向器是红线（{@code setTravelReversed} 的注释）。</p>
	 *
	 * @return 是否真的改了方向
	 */
	public boolean mmtrResetTravelDirectionForDriver() {
		final MmtrConsistWalker consistWalker = getMmtrConsistWalker();
		return consistWalker != null && speed <= 1e-9 && consistWalker.setTravelReversed(false);
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
	 * **这列车当前握着驾驶室钥匙的那位乘务员**（编组体车；没有、或只有引擎的 SYSTEM 占位钥匙时 {@code null}）。
	 *
	 * <p>用途是"计划内接管"的身份来源：玩家进驾驶室时就写了这把钥匙（{@code cab <id> <车节><端> <uuid>}），
	 * 所以引擎不必再让人把 uuid 报一遍 —— {@code job take <车id>} 直接问这里"现在是谁在开"。
	 * 与 {@link #getMmtrDriverUuid()}（谁在推手柄）的区别是**时机**：钥匙在"坐进驾驶室"时就有，
	 * 手柄要等第一次动作，而"等待发车时接管"恰恰发生在还没动手柄的时候。</p>
	 */
	public @Nullable UUID getMmtrCrewUuid() {
		final MmtrConsistWalker consistWalker = getMmtrConsistWalker();
		if (consistWalker == null || !consistWalker.cabs().isCrewKey()) {
			return null;
		}
		return consistWalker.cabs().crewUuid();
	}

	/**
	 * Whether {@code uuid} holds the key of this consist's manned cab ("NONE" / "SYSTEM" / "CREW").
	 *
	 * <p><b>不再参与操纵判据</b>（2026-09-19：操作手柄不需要钥匙，见 {@link #canTakeMmtrControl}）。
	 * 留着是因为它回答的是另一个问题——"钥匙在谁手里"，驾驶室界面与运维查询用它，
	 * 而 {@code SYSTEM} 钥匙（自动运行的占位钥匙）与乘务员钥匙的区别也只有这里能问。</p>
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
	 *
	 * <p><b>钥匙不再是操纵的前提</b>（用户口径 2026-09-19：「操作手柄不需要手里握着钥匙」）。
	 * 判据只认"骑在这辆车上、并且是司机位"（{@code isDriver} 由客户端按**沿车长的位置**判，
	 * 见 {@code MmtrDriverSeat}）—— 以前还要求 {@code holdsMmtrCabKey}，于是"人坐在司机位上、
	 * 引擎里放着一把 system 钥匙"的组合按了没反应，正是用户报的那件事。</p>
	 *
	 * <p>仍然拦着的：无任务不得操纵（T4 策略闸门）、以及"一列车上只有一个司机"的占用锁
	 * （{@link MmtrDriveAccess#canControl}：当前持有者还在司机位上时别人抢不走）。</p>
	 */
	public boolean canTakeMmtrControl(@Nullable UUID uuid) {
		return mmtrControlRefusalReason(uuid).isEmpty();
	}

	/**
	 * notes/276 片 6：这一列车现在是不是被**钉住**（停放 = 钉住，决定 1/6）。
	 *
	 * <p>{@code true} ⇒ 位置锁死：本车的推进被跳过（{@code simulate} 的闸门链最前面那一支），
	 * 任何外部推进也进不来（任务要动它得先有任务，而"有任务"本身就不满足钉住）。</p>
	 */
	public boolean isMmtrPinned() {
		return mmtrPinned;
	}

	/** 现在该不该钉住（三条判据见 {@link MmtrDriveAccess#shouldPin}）。 */
	private boolean mmtrComputePinned() {
		if (isClientside) {
			return false;
		}
		final boolean anyDriver = mmtrManualOverride || mmtrAnyDriverRiding();
		final boolean hasLiveTask = mmtrMission != null && !mmtrMission.isTerminal();
		final boolean canPull = data instanceof final Simulator simulator && simulator.mmtrConsistTypes != null
			&& org.mtr.core.mmtr.MmtrCarTypeResolver.anyCarCanPull(vehicleExtraData.immutableVehicleCars, simulator.mmtrConsistTypes,
				simulator.mmtrDefaultConsistTypeId == null ? null : simulator.mmtrConsistTypes.get(simulator.mmtrDefaultConsistTypeId));
		return MmtrDriveAccess.shouldPin(anyDriver, hasLiveTask, canPull);
	}

	/** 有没有人坐在（任何一节车的）司机位上 —— 与 {@code mmtrControlRefusalReason} 里那条同一判据。 */
	private boolean mmtrAnyDriverRiding() {
		final boolean[] anyDriverRiding = {false};
		vehicleExtraData.iterateRidingEntities(vehicleRidingEntity -> {
			if (vehicleRidingEntity.isDriver()) {
				anyDriverRiding[0] = true;
			}
		});
		return anyDriverRiding[0];
	}

	/** 钉住/解钉那句话的理由（日志与诊断用）。 */
	/**
	 * 片 7（notes/277）：**把控制器输出按车钩折一下**（机车 ↔ 车列一个钩，折中版）。
	 *
	 * <p>刚性车列（车底没写车钩键、或编组只有一节）**原样返回** —— 逐位等于片 7 之前。
	 * 有钩时返回的加速度是**车头那节车真正感受到的**：起步时它先按自己的质量冲出去（间隙没吃完），
	 * 间隙吃完那一下掉到刚体值之下（顿），这就是"冲动"。</p>
	 *
	 * <p><b>只在服务端算</b>：车钩口径不在镜像串里（客户端不知道钩的参数），镜像那份仍按刚体积分，
	 * 由权威快照每 tick 校正速度。这是折中版的已知边界，写进 notes/277。</p>
	 */
	private DriveOutput mmtrApplyCoupler(DriveOutput output, double speedMetersPerSecond, long stepMillis) {
		if (isClientside || output == null || mmtrConsistType == null) {
			return output;
		}
		final org.mtr.core.mmtr.physics.CouplerSpec spec = mmtrConsistType.getCoupler();
		final double rakeEffectiveMassKg = spec == null ? 0 : mmtrRakeEffectiveMassKg();
		if (spec == null || rakeEffectiveMassKg <= 0) {
			mmtrCouplerDynamics = null;
			return output;
		}
		if (mmtrCouplerDynamics == null || mmtrCouplerDynamics.getSpec() != spec) {
			mmtrCouplerDynamics = new org.mtr.core.mmtr.physics.CouplerDynamics(spec);
			mmtrCouplerDynamics.reset(speedMetersPerSecond);
		}
		final double acceleration = mmtrCouplerDynamics.step(speedMetersPerSecond, Math.max(1, stepMillis) / 1000.0,
			mmtrLeadEffectiveMassKg(), rakeEffectiveMassKg, output.getAccelerationMetersPerSecondSquared());
		return new DriveOutput(acceleration, output.isBrakeLamp(), output.isEmergencyBrake(),
			output.getTrainPipePressure(), output.getBrakeCylinderPressure());
	}

	/** 车头那一节的惯性质量 {@code λ·m}（含载重）；解析不出编组时退回整列等效车底。 */
	private double mmtrLeadEffectiveMassKg() {
		final MmtrComposition composition = getMmtrComposition();
		if (composition != null && composition.size() > 0) {
			return composition.unit(0).loadedType().getPhysics().effectiveMassKg();
		}
		return mmtrConsistType == null ? 0 : mmtrConsistType.getPhysics().effectiveMassKg();
	}

	/** 后面那串车列的惯性质量 {@code λ·m}（含载重）= 整列 − 车头那一节。 */
	private double mmtrRakeEffectiveMassKg() {
		final MmtrComposition composition = getMmtrComposition();
		if (composition == null || composition.size() < 2) {
			return 0;
		}
		return Math.max(0, composition.totalEffectiveMassKg() - mmtrLeadEffectiveMassKg());
	}

	/** 钉住/解钉那句话的理由（日志与诊断用）。 */
	private String mmtrPinnedReason() {
		if (!(data instanceof final Simulator simulator) || simulator.mmtrConsistTypes == null) {
			return "（没有车底注册表：按「拉不动」处理）";
		}
		final int cars = vehicleExtraData.immutableVehicleCars.size();
		final boolean canPull = org.mtr.core.mmtr.MmtrCarTypeResolver.anyCarCanPull(vehicleExtraData.immutableVehicleCars,
			simulator.mmtrConsistTypes,
			simulator.mmtrDefaultConsistTypeId == null ? null : simulator.mmtrConsistTypes.get(simulator.mmtrDefaultConsistTypeId));
		return "车节数=" + cars + " 司机=" + (mmtrManualOverride || mmtrAnyDriverRiding())
			+ " 任务=" + (mmtrMission != null && !mmtrMission.isTerminal())
			+ " 整列能出力=" + canPull + "（不被连上就钉死在地里，notes/276）";
	}

	/**
	 * 服务端：这次操纵请求**为什么**被拒（空串 = 允许）。
	 *
	 * <p>与 {@link #canTakeMmtrControl} 同源（后者就是"理由为空"），但把理由说出来。
	 * 存在的理由：三种拒绝（不是司机位 / 无任务 / 别人在开）在客户端看起来**一模一样** ——
	 * 都是"按了没反应"，而现场只能靠日志区分（notes/216：用户报"车不动"，先花了一轮才发现是别的原因）。</p>
	 */
	public String mmtrControlRefusalReason(@Nullable UUID uuid) {
		/*
		 * notes/271 片 2：**整列车列没有任何能出力的车底 ⇒ 拒绝掌权**（挂车不是车头）。
		 *
		 * <p>判据与物理层同源（{@code MmtrCarTypeResolver.anyCarCanPull}，同一套 powered 三态规则）：
		 * 单节车不走"按车求和"的等效车底，所以只靠物理层拦不住"一节被声明成无动力的车"仍按它自己的
		 * 车底跑 —— 准入层这一条才是"挂车不能开"的落点。</p>
		 */
		if (!isClientside && data instanceof final Simulator simulatorForPull && simulatorForPull.mmtrConsistTypes != null) {
			final org.mtr.core.mmtr.ConsistType defaultType = simulatorForPull.mmtrDefaultConsistTypeId == null ? null
				: simulatorForPull.mmtrConsistTypes.get(simulatorForPull.mmtrDefaultConsistTypeId);
			if (!org.mtr.core.mmtr.MmtrCarTypeResolver.anyCarCanPull(vehicleExtraData.immutableVehicleCars,
				simulatorForPull.mmtrConsistTypes, defaultType)) {
				return "整列车列没有任何能出力的车底（挂车 / 被挂车列）—— 挂车不能开（notes/271 片 2）";
			}
		}
		// T4 准入闸门：无任务不得操纵（策略开关；默认关，见 MmtrDriveAccess.taskAdmitsDriving）。
		if (!MmtrDriveAccess.taskAdmitsDriving(data instanceof final Simulator simulator && simulator.mmtrRequireTaskToDrive,
			mmtrMission != null && !mmtrMission.isTerminal())) {
			return "无任务不得操纵（T4 闸门 mmtrRequireTaskToDrive 开着）";
		}
		if (uuid == null) {
			final boolean[] anyDriverRiding = {false};
			vehicleExtraData.iterateRidingEntities(vehicleRidingEntity -> {
				if (vehicleRidingEntity.isDriver()) {
					anyDriverRiding[0] = true;
				}
			});
			return anyDriverRiding[0] ? "" : "没有司机在司机位上（无身份操纵路径）";
		}
		if (!hasMmtrDriverRiding(uuid)) {
			return "请求者不在司机位上（ride 包的 isDriver 为假）";
		}
		final boolean holderStillRiding = mmtrDriverUuid == null || hasMmtrDriverRiding(mmtrDriverUuid);
		if (!MmtrDriveAccess.canControl(true, mmtrManualOverride, mmtrDriverUuid, uuid, holderStillRiding)) {
			return "另一名司机正持有操纵权（占用锁；持有者仍在司机位上）";
		}
		return "";
	}

	/**
	 * 服务端：把一次被拒的操纵请求说出来（限频 2 s；理由变了立刻说）。
	 *
	 * <p>刻意**不放在 trace 开关后面**：它是"人正在试图开车但没开成"的状态类消息，默认就该看得见
	 * （同 {@code MmtrTrace} 类注释里对"状态类消息"的规定）。</p>
	 */
	public void mmtrLogControlRefusal(@Nullable UUID uuid, ControlState state) {
		final String reason = mmtrControlRefusalReason(uuid);
		if (reason.isEmpty()) {
			return;
		}
		final long now = System.currentTimeMillis();
		if (reason.equals(mmtrLastRefusalReason) && now - mmtrLastRefusalLogMillis < MMTR_DIAG_LOG_INTERVAL_MILLIS) {
			return;
		}
		mmtrLastRefusalReason = reason;
		mmtrLastRefusalLogMillis = now;
		System.out.println("[MMTR-DRV] 操纵被拒：车=" + id + " 理由=" + reason + "（油门手柄=" + state.getDriveHandle()
			+ " 制动=" + state.getBrakeNotch() + " 定速=" + state.getCruiseSpeedKmh() + " reverser=" + state.getReverser()
			+ " uuid=" + uuid + "）");
	}

	/**
	 * 服务端：客户端送来三手柄语义、但本车底没有三手柄规格时说出来（限频）。
	 *
	 * <p>这是"按了不走"的第二大原因：车底是 NOTCHED/STEPLESS（例如车型没配进 {@code carTypeIds}、
	 * 或世界配置改了但服务端没重启），控制器读的是 {@code throttleNotch}，于是 {@code driveHandle} 被无声忽略。</p>
	 */
	private void mmtrLogHandleMismatch(ControlState state) {
		if (mmtrConsistType == null || mmtrConsistType.getHandles() != null) {
			return;
		}
		if (state.getDriveHandle() == 0 && state.getCruiseSpeedKmh() == 0) {
			return;
		}
		final String note = "本车底是 " + mmtrConsistType.getControlMode() + "（" + mmtrConsistType.getId()
			+ "），没有三手柄规格：接收到的油门手柄/定速不驱动它";
		final long now = System.currentTimeMillis();
		if (note.equals(mmtrLastMismatchNote) && now - mmtrLastMismatchLogMillis < MMTR_DIAG_LOG_INTERVAL_MILLIS) {
			return;
		}
		mmtrLastMismatchNote = note;
		mmtrLastMismatchLogMillis = now;
		System.out.println("[MMTR-DRV] 手柄语义不匹配：车=" + id + " " + note + "（油门手柄=" + state.getDriveHandle()
			+ " 定速=" + state.getCruiseSpeedKmh() + "）");
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

	/**
	 * True when explicit MMTR control requests traction (used to allow departing from a stop).
	 *
	 * <p>2026-09-23：**定速挂上也算"要牵引"** —— AFB 会自己出力，所以"停在车场、只设了定速"的车必须
	 * 能被自臂放出去（否则司机设了定速、手柄不动，车永远停在原地，看起来就是"定速不会动"）。</p>
	 */
	public boolean isMmtrRequestingPower() {
		// 三手柄车底油门在 driveHandle 上（客户端 throttleNotch 恒为 0，见 MmtrDriveInput），
		// 少了后半句，三手柄车在"起步闸门"与被仪表读的功率里就恒等于"没要牵引"。
		return mmtrManualOverride && mmtrActiveControl != null
			&& (mmtrActiveControl.getThrottleNotch() > 0 || mmtrActiveControl.getDriveHandle() > 0
				|| mmtrActiveControl.getCruiseSpeedKmh() > 0);
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
	/**
	 * 片 7 的**车钩动力学**（机车 ↔ 车列一个钩，折中版，notes/277）：车列换人/换编组时作废重建。
	 * {@code null} = 刚性车列（车底没写车钩键，或编组只有一节）。
	 */
	private org.mtr.core.mmtr.physics.CouplerDynamics mmtrCouplerDynamics;

	private boolean tryInitMmtrController() {
		// 车底是**按车**解析的（车型映射 / 车厢声明，见 MmtrCarTypeResolver），不是按维度：
		// 连挂或解挂改了车列 ⇒ "说话的车"可能换了 ⇒ 返回 true 并作废缓存的控制器。
		final boolean carTypeChanged = !isClientside && mmtrRefreshConsistTypeFromCars();
		if (mmtrDriveController != null && !carTypeChanged) {
			return true;
		}
		if (isClientside) {
			mmtrConsistType = createMirrorConsistTypeFromSync();
		} else if (mmtrConsistType == null) {
			/*
			 * notes/235 §4：服务端**永远**要有一份可用的车底 —— 解析不到就用通用车兜底，
			 * 并且已经在 {@link #mmtrRefreshConsistTypeFromCars()} 里喊过（包括"整个维度根本没配
			 * consist-types"这种情形，那时它连注册表都没有、上面那个方法会直接返回）。
			 * 少了这一句，缺配置的车会在下面走到"没有控制器"那一支被钉在 0 速。
			 */
			mmtrConsistType = ConsistType.FALLBACK;
		}
		/*
		 * notes/243 S1：**多节编组 ⇒ 用"按车求和"的等效车底**。
		 *
		 * <p>三个控制器（三手柄/有级/无级）都只拿一个 ConsistType 去算，而解析出来的只是"说话的那节车"。
		 * BR101 + 2×p1 于是按 82 t 算（真实 162 t）：起步/制动都灵一倍、AFB 的补气量也偏小。
		 * 这里把整列折成一份等效车底（质量/λ/牵引/制动/阻力按车求和，操纵语义仍沿用说话那节车），
		 * 于是"多挂一节车"这件事真的改变加速度 —— 车钩多体（S5）之前的过渡形态。</p>
		 */
		if (!isClientside && mmtrConsistType != null && vehicleExtraData.immutableVehicleCars.size() > 1) {
			final MmtrComposition mmtrCompositionForConsist = getMmtrComposition();
			if (mmtrCompositionForConsist != null && mmtrCompositionForConsist.size() > 1) {
				mmtrConsistType = mmtrCompositionForConsist.toConsistType("consist:" + (mmtrResolvedCarTypeKey == null ? "?" : mmtrResolvedCarTypeKey));
			}
		}
		if (mmtrConsistType != null) {
			mmtrDriveController = switch (mmtrConsistType.getControlMode()) {
				case NOTCHED -> new org.mtr.core.mmtr.NotchedDriveController();
				case STEPLESS -> new org.mtr.core.mmtr.SteplessDriveController();
				case AIR_BRAKE -> new org.mtr.core.mmtr.AirBrakeController();
				case THREE_HANDLE -> new org.mtr.core.mmtr.ThreeHandleDriveController();
				default -> null;
			};
			/*
			 * notes/268/270（P5 逐车管压 + 连挂形式）：多节编组时把**逐车制动描述**交给三手柄控制器，
		 * 它才做"压力沿车列往后传"；这一串就是连挂接口的载体（单机/机车+客车/重联/货车都只是不同的列表）。
			 * 服务端独占：客户端镜像没有注册表（每节车解析出的车底是借来的），逐车锚会算错 ——
			 * 客户端 HUD 读的是镜像回来的车头读数（notes/259 的同一套口径）。
			 */
			if (!isClientside && mmtrDriveController instanceof final org.mtr.core.mmtr.BrakeCarrier brakeCarrier) {
				final MmtrComposition composition = getMmtrComposition();
				if (composition != null && composition.size() > 1) {
					brakeCarrier.getBrakeModel().setCars(composition.brakeCars());
				}
				/*
				 * notes/270：控制器重建（换端/车列变动/存档载入）时把上一帧的气路状态灌回新模型，
				 * 否则解挂切分留下的那段"半充气"的车列会在重建瞬间被当作满管 —— 连挂接口的闭环。
				 * （没配 bar 键的车底：模型不接管，这一句是空操作。）
				 */
				if (mmtrAirState != null && !mmtrAirState.isEmpty()) {
					brakeCarrier.getBrakeModel().applyState(mmtrAirState);
				}
			}
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
		/*
		 * notes/235 §4：解析不到车底**不再退回 MTR 的加减速常数**（那条退路已删除），改用
		 * {@link ConsistType#FALLBACK}（一节通用车，能开、但不是真车）——并且必须**说出来**：
		 * 静默用另一套力学正是本仓最恨的一类现场。
		 */
		mmtrConsistType = resolution.consistTypeId() == null ? ConsistType.FALLBACK : simulator.mmtrConsistTypes.get(resolution.consistTypeId());
		if (mmtrConsistType == null) {
			mmtrConsistType = ConsistType.FALLBACK;
		}
		/*
		 * notes/274 片 4：**单节编组也要让载重进物理**。
		 *
		 * <p>多节那条路在 {@code MmtrComposition.toConsistType} 里逐车折载重；单节不走等效车底
		 * （notes/243 S1），所以这里把这一节自己的载重折进它的车底 —— 否则"一节重车"会按整备质量跑。</p>
		 */
		if (vehicleExtraData.immutableVehicleCars.size() == 1) {
			mmtrConsistType = mmtrConsistType.withLoad(vehicleExtraData.immutableVehicleCars.get(0).getMmtrLoadRatio());
		}
		// 车底解析结果是一条**状态类**消息（决定这台车用哪套操纵语义）：默认可见，否则"三手柄键位不驱动它"
		// 这类问题只能靠猜（notes/216）。
		System.out.println("[MMTR-DRV] 车底解析：车=" + id + " 说话的车=" + (resolution.key().isEmpty() ? "（谁都没配）" : resolution.key())
			+ " → " + (mmtrConsistType.isFallback()
				? "**缺配置**：既没匹配到车型、也没配 defaultConsistTypeId —— 本车用通用车力学（60 t / 100 kN / 600 kW），请修 mmtr-consist-types.json"
				: mmtrConsistType.getId() + "/" + mmtrConsistType.getControlMode()));
		// 说话的车换了 ⇒ 控制器（含气制动状态）与编组视图都要重建，否则会拿旧参数继续跑。
		mmtrDriveController = null;
		mmtrComposition = null;
		// notes/277 片 7：车列变了，车钩那个"车列等效速度"也就没意义了 —— 一并作废。
		mmtrCouplerDynamics = null;
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
			mmtrMaxSpeedKmh,
			// 力模型的镜像（notes/235）：客户端用这些数跑**同一份**物理。
			mmtrMassKg, mmtrRotatingMassFactor,
			mmtrMaxTractiveEffortN, mmtrMaxPowerW, mmtrServiceBrakeForceN, mmtrEmergencyBrakeForceN,
			mmtrResistanceAN, mmtrResistanceBN, mmtrResistanceCN,
			// 黏着：镜像里还没有这两个字段（schema 待加）⇒ 客户端镜像先按干轨走；湿轨/撒砂的镜像同步
			// 是 S3 的遗留项（见 notes/245）。
			0.37, false,
			mmtrAirPipeChargeRatePerSecond, mmtrAirPipeDischargeRatePerSecond,
			mmtrAirBrakeApplyRatePerSecond, mmtrAirBrakeReleaseRatePerSecond, mmtrManualMaxSpeedKmh,
			// 三手柄规格走紧凑字符串镜像（位置表是数组，逐帧传不划算；只有车型变化时它才变）。
			org.mtr.core.mmtr.ThreeHandleSpec.decode(mmtrHandleSpec),
			// 灯光开关档数（notes/352）：客户端没有 consist-types.json，靠 mmtrLightLoco 知道本车几档。
			mmtrLightLoco
		);
	}

	/** Client-side: rebuild the authoritative ControlState from the mirrored snapshot fields. */
	private ControlState createMirrorControlStateFromSync() {
		return new ControlState()
			.setThrottleNotch((int) mmtrThrottleNotch).setBrakeNotch((int) mmtrBrakeNotch).setReverser((int) mmtrReverser)
			// 灯光开关（notes/352）：镜像里两端各一份，"我在哪一端"由 mmtrCabEnd 定 —— 客户端据此显示与循环。
			.setLightSwitch(MmtrLightSwitch.switchOfEnd((int) mmtrLightA, (int) mmtrLightB, "B".equals(mmtrCabEnd) ? MmtrLightSwitch.END_B : MmtrLightSwitch.END_A))
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
		final org.mtr.core.mmtr.brake.BrakeModel model = mmtrBarBrakeModel();
		if (model != null) {
			// notes/270：气路状态的权威位置随控制模式而变 —— 配了 bar 键的车底在**制动模型**里（逐车），
			// 其它车底仍在 MmtrComposition 上。两条路走同一个字符串格式，所以这里按"有没有模型"分派，
			// 与具体是哪种操纵方式无关（三手柄 / 有级 / 无级 / 将来的 ATO 都一样）。
			model.applyState(airState);
			final String state = model.encodeState();
			if (!state.isEmpty()) {
				mmtrAirState = state;
				mmtrPipePressure = model.getPipePressure();
				mmtrBrakeCylinderPressure = model.getBrakeCylinderPressure();
			}
			return;
		}
		final MmtrComposition composition = getMmtrComposition();
		if (composition != null && airState != null && !airState.isEmpty()) {
			composition.applyAirStateString(airState);
			mmtrAirState = MmtrComposition.encodeAirStates(composition);
		}
	}

	/**
	 * 本车这一拍的**制动模型**（bar 口径的气路状态持有者）。
	 *
	 * <p>{@code null} = 这份车底没配气压口径（{@code consist-types.json} 里没有 bar 键）或控制器还没建
	 * ⇒ 调用方走旧的 {@link MmtrComposition} 归一化气路。判据用**车底**而不是"模型里有没有系统"：
	 * 后者要跑过一拍才成立，而连挂手术常常发生在"新车还没人开"的那一拍。</p>
	 */
	private org.mtr.core.mmtr.brake.BrakeModel mmtrBarBrakeModel() {
		if (mmtrConsistType == null || mmtrConsistType.getBrakes() == null) {
			return null;
		}
		return mmtrDriveController instanceof final org.mtr.core.mmtr.BrakeCarrier carrier ? carrier.getBrakeModel() : null;
	}

	/** C4b: the current per-unit air state string ({@code pipe,cyl;...}); empty when no composition. */
	public String mmtrAirStateSnapshot() {
		final org.mtr.core.mmtr.brake.BrakeModel model = mmtrBarBrakeModel();
		if (model != null) {
			final String state = model.encodeState();
			if (!state.isEmpty()) {
				return state;
			}
		}
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
		final org.mtr.core.mmtr.brake.BrakeModel model = mmtrBarBrakeModel();
		if (model != null) {
			model.seedAfterCoupling(firstAddedUnitIndex);
			mmtrAirState = model.encodeState();
			return;
		}
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
		/*
		 * 任务提示（"玩家与作业表相连"）：司机要知道自己在做哪一步。
		 *
		 * <p>四个字段都从 mission 上取（作业调度器在建 mission 时就把它挂在 mission 上了），
		 * 引擎在这里**算好原话**发给客户端 —— 客户端不做任何反查（它既没有站台对象也没有作业单）。
		 * 变化时主动标脏：车停在站台上等发车时速度/门都不变，不标脏这几个字就永远推不过去。</p>
		 */
		final org.mtr.core.mmtr.MmtrMission promptMission = mmtrMission;
		final String jobIdNow = promptMission == null ? "" : promptMission.getJobId();
		final String taskNoteNow = promptMission == null ? "" : (promptMission.getJobStepNote().isEmpty()
			? (promptMission.getTask() == null ? "" : promptMission.getTask().note) : promptMission.getJobStepNote());
		final int taskStepNow = promptMission == null ? -1 : promptMission.getJobStepIndex();
		final int taskStepsNow = promptMission == null ? 0 : promptMission.getJobStepCount();
		final String missionStateNow = promptMission == null ? "" : promptMission.getState().name();
		final String missionExecutorNow = promptMission == null ? "" : promptMission.getExecutor().name();
		/*
		 * 子任务链（站台作业：到站停稳 → 开门 → 停够 → 关门）—— 与任务提示同一套下发方式：
		 * 引擎算好清单与"现在该做什么"的原话，客户端只负责画。子任务只写了几行字，但停在站台上的
		 * 那几十秒里速度、门、里程都不变，**不主动标脏这几个字永远推不过去**（同 jobId 那段）。
		 */
		final String subTasksNow = promptMission == null ? "" : promptMission.encodeSubTasks();
		final String subTaskHintNow = promptMission == null ? "" : promptMission.subTaskHint(data.getCurrentMillis());
		final long subTaskRevisionNow = promptMission == null ? 0 : promptMission.subTaskRevision();
		final int subTaskAcksNow = promptMission == null ? 0 : promptMission.subTaskAcks();
		if (!jobIdNow.equals(mmtrJobId) || !taskNoteNow.equals(mmtrTaskNote) || taskStepNow != mmtrTaskStep
			|| taskStepsNow != mmtrTaskSteps || !missionStateNow.equals(mmtrMissionState) || !missionExecutorNow.equals(mmtrMissionExecutor)
			|| !subTasksNow.equals(mmtrSubTasks) || !subTaskHintNow.equals(mmtrSubTaskHint)
			|| subTaskRevisionNow != mmtrSubTaskRevision || subTaskAcksNow != mmtrSubTaskAcks) {
			vehicleExtraData.mmtrMarkSyncDirty();
		}
		mmtrJobId = jobIdNow;
		mmtrTaskNote = taskNoteNow;
		mmtrTaskStep = taskStepNow;
		mmtrTaskSteps = taskStepsNow;
		mmtrMissionState = missionStateNow;
		mmtrMissionExecutor = missionExecutorNow;
		mmtrSubTasks = subTasksNow;
		mmtrSubTaskHint = subTaskHintNow;
		mmtrSubTaskRevision = subTaskRevisionNow;
		mmtrSubTaskAcks = subTaskAcksNow;
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
			final org.mtr.core.mmtr.physics.TrainPhysics physics = mmtrConsistType.getPhysics();
			mmtrPowerNotches = mmtrConsistType.getPowerNotches();
			mmtrBrakeNotches = mmtrConsistType.getBrakeNotches();
			mmtrHandleSpec = mmtrConsistType.getHandles() == null ? "" : mmtrConsistType.getHandles().encode();
			/*
			 * 灯光开关的档数（notes/352）：机车的开关多一档"关闭"（连挂时用来灭掉被挂那一端的尾灯）。
			 * 车底换了（编组/拖车兜底）也要跟着变，顺手把不再合法的档位钳回来（动车组不能停在"关闭"）。
			 */
			mmtrLightLoco = mmtrConsistType.hasMmtrLightOffPosition();
			mmtrLightA = MmtrLightSwitch.sanitize((int) mmtrLightA, mmtrLightLoco);
			mmtrLightB = MmtrLightSwitch.sanitize((int) mmtrLightB, mmtrLightLoco);
			mmtrMaxSpeedKmh = mmtrConsistType.getMaxSpeedKmh();
			mmtrManualMaxSpeedKmh = mmtrConsistType.getManualMaxSpeedMetersPerSecond() * 3.6;
			// 力模型的镜像（notes/235）：客户端拿这几个数跑**同一份**物理。
			mmtrMassKg = physics.getMassKg();
			mmtrRotatingMassFactor = physics.getRotatingMassFactor();
			mmtrMaxTractiveEffortN = physics.getTraction().getMaxTractiveEffortN();
			mmtrMaxPowerW = physics.getTraction().getMaxPowerW();
			mmtrServiceBrakeForceN = physics.getBrake().getServiceForceN();
			mmtrEmergencyBrakeForceN = physics.getBrake().getEmergencyForceN();
			mmtrResistanceAN = physics.getResistance().getAN();
			mmtrResistanceBN = physics.getResistance().getBN();
			mmtrResistanceCN = physics.getResistance().getCN();
			mmtrAirPipeChargeRatePerSecond = mmtrConsistType.getAirPipeChargeRatePerSecond();
			mmtrAirPipeDischargeRatePerSecond = mmtrConsistType.getAirPipeDischargeRatePerSecond();
			mmtrAirBrakeApplyRatePerSecond = mmtrConsistType.getAirBrakeApplyRatePerSecond();
			mmtrAirBrakeReleaseRatePerSecond = mmtrConsistType.getAirBrakeReleaseRatePerSecond();
			/*
			 * notes/235：镜像里的 acceleration / deceleration 换成**本车力模型的真实能力**（SI，m/s²）。
			 * 原来它们是从车场配置抄来的原版常数（所有车一个值）；现在客户端拿它们做信号预留足迹
			 * （padding）与电机音调时，用的就是这列车真正能做到的加减速。
			 */
			final double mirrorSpeedSi = MmtrSupport.internalSpeedToSi(speed);
			vehicleExtraData.setMmtrAccelerationSi(physics.tractionAccelerationMps2(1, mirrorSpeedSi));
			vehicleExtraData.setMmtrDecelerationSi(physics.serviceBrakeDecelerationMps2(1, mirrorSpeedSi));
			// notes/250/257：把"电机现在真的在出多少力"发下去（右上角 HUD 的读数）。在服务端算，客户端读快照 ——
			// 客户端镜像每帧重建、控制器状态留不住，自算会乱跳（现场已复现）。
			//
			// 走**稀疏补丁**（`VehicleSyncPatch.DYNAMIC_KEYS` 里有它）⇒ 每 tick 都能到达客户端，不必标脏发整份；
			// 标脏会强制整份快照、把镜像整个重建一遍（那正是"读数乱跳/不实时"的老根）。
			vehicleExtraData.setMmtrMotorForceN(getMmtrMotorForceN());
			/*
			 * notes/269：**整列气制动力**也要发下去 —— 「制动力 xx kN（气 · 电）」那一行的"气"。
			 *
			 * <p>为什么不能像以前那样由客户端拿"车头缸压 × 制动锚"反算：逐车管压（notes/268）＋电空混合
			 * （notes/267）之后，**车头那一节的缸压可能被 EP 阀削到 0**（电制动替掉了机车自己那份），
			 * 而拖车仍在气制动 —— 现场表现就是用户报的"制动力只显示电制动的"。
			 * 服务端算的是逐车求和（`ThreeHandleDriveController.getLastPneumaticBrakeForceN()`），
			 * 客户端读这一份，两边同一个数（notes/250/257/259 的同一套口径）。</p>
			 */
			vehicleExtraData.setMmtrPneumaticBrakeForceN(getMmtrPneumaticBrakeForceN());
		}
		if (mmtrDriveController instanceof final org.mtr.core.mmtr.AirBrakeStateful airBrakeStateful) {
			mmtrPipePressure = airBrakeStateful.getPipePressure();
			mmtrBrakeCylinderPressure = airBrakeStateful.getBrakeCylinderPressure();
		}
		// Signal display fields (HUD): AWS warning state, occupancy hold and the current per-rail
		// directional speed limit follow the live internal state into the mirror payload.
		mmtrAwsWarningPending = mmtrAwsState == MMTR_AWS_WARN;
		mmtrAwsWarningAcknowledged = mmtrAwsState == MMTR_AWS_ACKED;
		mmtrBlockHeld = mmtrBlockedWaiting || mmtrAuthorityTripped;
		mmtrSpeedLimitKmh = Math.round(mmtrCurrentRailLimitPerMs() * 3600.0);
		// Signal S4 (LZB) cab display: supervision band flag, enforced ceiling, cab target speed
		// (0 = stop target ahead) and distance to that target, mirrored from the same values the
		// driving supervision enforces against - the client HUD reads exactly what the train obeys.
		mmtrLzbSupervising = getMmtrLzbCeilingKmh() > 0;
		mmtrLzbCeilingKmh = getMmtrLzbCeilingKmh();
		mmtrLzbTargetKmh = getMmtrLzbTargetKmh();
		mmtrLzbTargetDistanceM = getMmtrLzbTargetDistanceM();
		/*
		 * notes/369 §8：这批显示读数（停留理由 / 限速 / LZB / 闭塞扣车 / 调车授权倒计时）
		 * **没有别的标脏点** —— 而它们恰好在"车稳稳停着"的那几秒里变（司机盯着 HUD 想知道为什么不动），
		 * 那时速度、门、任务全不变 ⇒ 没有脏拍 ⇒ 字停在旧值。
		 *
		 * <p>逐字段比一遍（十几条指令）比"每隔几秒白扔一份 7.4 KB 整份"便宜得多，
		 * 也比"每个写入点都记得标脏"可靠（推导而不是搬运，与本类 {@code mmtrPinned} 那段同一个口径）。</p>
		 */
		final MmtrDisplayMirror displayMirrorNow = MmtrDisplayMirror.of(this);
		if (!displayMirrorNow.equals(mmtrDisplayMirror)) {
			mmtrDisplayMirror = displayMirrorNow;
			vehicleExtraData.mmtrMarkSyncDirty();
		}
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
		// notes/235：判据里的减速度改成**本车自己的紧急制动能力**（原来是原版的 Siding.MAX_ACCELERATION*2
		// 这个与车无关的常数）—— 空车与重车、单机与长大货物列的可用包线本来就不一样。
		if (MmtrProtection.requiresProtection(speed, stoppingPoint - railProgress, mmtrEmergencyDecelPerMs())) {
			mmtrProtection = true;
			mmtrProtectionLockRemaining = MmtrProtection.LOCK_MILLIS;
			System.out.println("[MMTR-DRV] overrun protection engaged (past stopping point or cannot stop in time)");
			return true;
		}
		return false;
	}

	/** Public getters for the client HUD / mirror overlay (values from the last snapshot). */
	public boolean isMmtrActiveFromSync() { return mmtrActive; }
	/**
	 * 当前的紧急制动是不是**司机越界**那一路触发的（而不是 AWS 报警超时的 SPAD）。
	 * 给诊断/测试用：两者共用紧急制动通道，但"是谁触发的"决定了它会不会 10 s 自动解锁。
	 */
	public boolean isMmtrAuthorityTripped() { return mmtrAuthorityTripped; }
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
	/**
	 * 车为什么不动（司机可读的一句话；空串 = 没有被按住）。镜像字段，见 {@code mmtrMotionHoldReason()}。
	 * 客户端 HUD 用它回答"手柄有反应但车不动"这个最常见的困惑。
	 */
	public String getMmtrHoldReasonFromSync() { return mmtrHoldReason == null ? "" : mmtrHoldReason; }

	/**
	 * **任务提示**（"玩家与作业表相连"的那一半：司机得知道自己在做哪一步）。
	 *
	 * <p>要点：这四项由**引擎**算好、按原话发给客户端，客户端不做任何反查 —— 目标名要从
	 * 站台/股道对象取，作业步骤的人话说明在作业单里，客户端两样都没有（notes/170 §8.4）。</p>
	 */
	public String getMmtrJobIdFromSync() { return mmtrJobId == null ? "" : mmtrJobId; }

	/** 这一步的人话说明（作业单步骤的 {@code note}，例如"去程到 1 站 1 台"）。 */
	public String getMmtrTaskNoteFromSync() { return mmtrTaskNote == null ? "" : mmtrTaskNote; }

	/** 0 起的步号；{@code -1} = 不属于任何作业表。 */
	public int getMmtrTaskStepFromSync() { return (int) mmtrTaskStep; }

	public int getMmtrTaskStepsFromSync() { return (int) mmtrTaskSteps; }

	/** mission 状态（ASSIGNED/DISPATCHED/AT_TARGET/COMPLETE/FAILED）。 */
	public String getMmtrMissionStateFromSync() { return mmtrMissionState == null ? "" : mmtrMissionState; }

	/** 谁在执行（AUTOPILOT/PLAYER/AI）—— HUD 用它回答"现在是我在开还是引擎在开"。 */
	public String getMmtrMissionExecutorFromSync() { return mmtrMissionExecutor == null ? "" : mmtrMissionExecutor; }

	/**
	 * **子任务清单**（`KIND:STATE:ACK;…`）—— 站台作业的 到站停稳 / 开门 / 停够 / 关门。
	 *
	 * <p>为什么把"引擎判定"与"客户端是否确认"编在同一格里：用户要的"明确的双向确认"应该是一条
	 * 能看见的数据，而不是靠两端各自的行为去猜 —— HUD 因此能直接画出 `✔✔`（两边都确认）
	 * 与 `✔-`（引擎说做完了、客户端还没确认）这两种不同的格子。</p>
	 */
	public String getMmtrSubTasksFromSync() { return mmtrSubTasks == null ? "" : mmtrSubTasks; }

	/** **现在该做什么**（引擎算好的一句人话，客户端不拼中文、不判状态）。 */
	public String getMmtrSubTaskHintFromSync() { return mmtrSubTaskHint == null ? "" : mmtrSubTaskHint; }

	/** 子任务状态版本号：客户端确认时回传，用来发现"两端看到的不一样"。 */
	public long getMmtrSubTaskRevisionFromSync() { return mmtrSubTaskRevision; }

	/** 客户端已确认的次数（0 = 还没确认过任何一条）。 */
	public int getMmtrSubTaskAcksFromSync() { return (int) mmtrSubTaskAcks; }

	/**
	 * **水牌 / PID**（notes/354）：班次号 / 本趟终点 / 下一站。
	 *
	 * <p>三项都是作业调度器每 tick 从**作业单**算好写进镜像的（{@code org.mtr.core.mmtr.MmtrPid}）——
	 * 客户端没有作业单，也没有站台对象，只能读这份算好的原话（与任务提示同一个规矩）。
	 * 车不挂在任何在跑的作业单上时三项为空（车场里停着的车不该挂水牌）。</p>
	 */
	public String getMmtrPidServiceFromSync() { return mmtrPidService == null ? "" : mmtrPidService; }

	/** 本趟终点站名（空 = 本趟不停站台，例如最后的回库趟）。 */
	public String getMmtrPidTerminusFromSync() { return mmtrPidTerminus == null ? "" : mmtrPidTerminus; }

	/** 前方下一个停站的车站名（空 = 后面不再有站台作业）。 */
	public String getMmtrPidNextFromSync() { return mmtrPidNext == null ? "" : mmtrPidNext; }

	/**
	 * 服务端：把水牌三项写给这辆车（作业调度器每 tick 调一次，见 {@code MmtrJobScheduler.tick}）。
	 *
	 * <p><b>三项都没变时什么都不做</b> —— 于是"调度器每 tick 都写"不会变成"每 tick 都推一份补丁"：
	 * 只有真的换了一趟（过一次换端）或过了一站（下一站变了）才写镜像并标脏。
	 * 这条不变量是本类唯一需要守的东西，用例直接钉它（同名再写一次 ⇒ 不脏）。</p>
	 */
	public void setMmtrPid(org.mtr.core.mmtr.MmtrPid pid) {
		final org.mtr.core.mmtr.MmtrPid value = pid == null ? org.mtr.core.mmtr.MmtrPid.UNKNOWN : pid;
		if (value.serviceNumber().equals(getMmtrPidServiceFromSync())
			&& value.terminus().equals(getMmtrPidTerminusFromSync())
			&& value.nextStation().equals(getMmtrPidNextFromSync())) {
			return;
		}
		mmtrPidService = value.serviceNumber();
		mmtrPidTerminus = value.terminus();
		mmtrPidNext = value.nextStation();
		vehicleExtraData.mmtrMarkSyncDirty();
	}

	/** 服务端：把这辆车的水牌清掉（不再挂在任何在跑的作业单上）。 */
	public void clearMmtrPid() {
		setMmtrPid(null);
	}
	public int getMmtrReverserFromSync() { return (int) mmtrReverser; }

	/**
	 * 灯光开关：**A 端**那一端驾驶室的档位（{@code MmtrLightSwitch} 的 0/1/2/3 = 关/尾/日/夜）。
	 *
	 * <p>每个驾驶室各一个开关，所以两端各一份；哪一盏灯属于哪一端由**几何**决定
	 * （{@code MmtrVehicleAnchors.engineEndOfSeat}：车体局部 +Z = 引擎的 B 端），渲染侧不再猜行进方向。</p>
	 */
	public int getMmtrLightAFromSync() { return (int) mmtrLightA; }

	/** 灯光开关：**B 端**那一端驾驶室的档位，见 {@link #getMmtrLightAFromSync()}。 */
	public int getMmtrLightBFromSync() { return (int) mmtrLightB; }

	/**
	 * 本车的灯光开关有没有"关闭"这一档（机车 = true，动车组 = false）。
	 *
	 * <p>来自车底配置 {@code lightSwitch: "LOCO"|"MU"}；客户端没有 consist-types.json，
	 * 所以它必须跟 A/B 两端一起镜像下去 —— 否则客户端 HUD 的档位循环与引擎的判据会不一致。</p>
	 */
	public boolean isMmtrLightLocoFromSync() { return mmtrLightLoco; }
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

	/*
	 * --------------------------------------------------------------------------------------------
	 * **运动流（notes/369 ①）的只读出口**
	 *
	 * <p>mod 侧的逐 tick 组帧器（`MmtrVehicleMotionSync`）只从这几个方法读引擎状态 ——
	 * 于是"线格式要什么"在引擎侧有一个明确、可搜索的边界，而不是让 mod 到处掏 private 字段
	 * （那样线格式一改就要在几个包里找调用点）。这些都是**读**：谁写状态仍然只有引擎自己。</p>
	 * --------------------------------------------------------------------------------------------
	 */

	/** 这辆车是不是"运动镜像"（走行器驱动的车；客户端据此知道自己要不要按腿阴影摆车）。 */
	public boolean isMmtrMotionMirrorFromSync() { return mmtrMotionMirror; }

	/** 本次运行的停车目标（m，走行器距离空间；{@code < 0} = 没有目标）。 */
	public double getMmtrRunStopTargetFromSync() { return mmtrRunStopTarget; }

	/** 本次运行的总里程（m，同一坐标空间；{@code <= 0} = 没有路线）。 */
	public double getMmtrRunTotalDistanceFromSync() { return mmtrRunTotalDistance; }

	/** 闭塞/占用停车点（m，同一坐标空间；{@code Double.MAX_VALUE} = 本拍没有扣车）。 */
	public double getMmtrBlockStopM() { return mmtrBlockStopM; }

	/** 停放钉住（"没有司机也没有任务"的防盗位）：镜像字段，司机 HUD 读它。 */
	public boolean isMmtrPinnedFromSync() { return mmtrPinned; }

	/** 门是不是"人工在操作"（B7.6h：司机用 Y/U 自己开门，跳过站台邻近判定）。 */
	public boolean isMmtrDoorManualFromSync() { return vehicleExtraData.isMmtrDoorManual(); }

	/** 任一扇门开着（镜像字段；逐侧的在 {@code VehicleExtraData} 上）。 */
	public boolean isMmtrDoorsOpenFromSync() { return vehicleExtraData.mmtrDoorsOpen(); }

	/** 现在是"手动"（司机在开）还是自动。 */
	public boolean isMmtrCurrentlyManualFromSync() { return vehicleExtraData.getIsCurrentlyManual(); }

	/**
	 * **腿表上线格式**：规范化 hex id + **接入端方向位**（notes/375）。
	 *
	 * <p>v1 只发 hex（没有方向），客户端只能拿"上一根腿的末端"去推接入端；而换端会让整张表反序，
	 * 那时推不出来 —— 客户端只能拒绝，于是阴影停止延长、车被渲染夹在阴影末端不动（用户报的
	 * 「换端从另一边出发，直接不动并瞬移」）。方向位在线上只花 1 bit/腿，换来的是客户端**照抄**：
	 * 而且它让"同一根轨、相反方向"成为**不同的腿**，换端于是必然被 {@code MmtrMotionFrame#legDelta}
	 * 认成"整表替换"而不是"又接了两根"。</p>
	 *
	 * <p>为什么每项是**规范化** hex（两端按 {@link Position#compareTo} 排序）：那是 {@code Data#railIdMap}
	 * 的键，客户端就是拿它查本地轨表的。**不能**用 {@code PathData#getHexId(false)}：那是"按行驶方向"
	 * 写的，反向走一根轨时会与轨表的键不同（现场表现是客户端 {@code 缺轨=} 一直涨、阴影接不上）。
	 * 方向由 {@code entryIsOrdered1} 单独带 —— 规范化 hex 里没有方向。</p>
	 *
	 * <p>{@code entryIsOrdered1} = {@code PathData#reversePositions} 取反：规范化顺序里的第一端
	 * （hex 的前半段）就是接入端 ⇔ 这根腿不是反着写的。</p>
	 */
	public java.util.List<org.mtr.core.mmtr.net.MmtrMotionFrame.Leg> getMmtrMotionLegsForWire() {
		final java.util.List<org.mtr.core.mmtr.net.MmtrMotionFrame.Leg> legs = new java.util.ArrayList<>(mmtrMotionLegs.size());
		mmtrMotionLegs.forEach(pathData -> legs.add(new org.mtr.core.mmtr.net.MmtrMotionFrame.Leg(
			TwoPositionsBase.getHexIdRaw(pathData.getOrderedPosition1(), pathData.getOrderedPosition2()),
			!pathData.reversePositions)));
		return legs;
	}

	/**
	 * 腿表在镜像里程坐标系里的起点（= 第一根腿的 {@code startDistance}），随 {@code LEGS} 一起发。
	 * 换端时客户端靠它把整张表重建到同一坐标系（见 {@code MmtrMotionPosition#mirrorPathAnchorM}）。
	 */
	public double getMmtrMotionPathAnchorM() {
		return mmtrMotionWalker == null ? 0 : mmtrMotionWalker.mirrorPathAnchorM();
	}

	/*
	 * --------------------------------------------------------------------------------------------
	 * **运动流（notes/369 ①）落到镜像**：这三支只在客户端调用（服务端是权威本身，写的是同一批字段，
	 * 但走的是它自己的状态机）。于是"① 谁写哪些字段"这件事在引擎侧也有唯一一处实现。
	 *
	 * <p><b>所有权（谁说话算）</b>：① 写<b>运动类</b>字段 —— 位置/速度、手柄三元组、紧急、
	 * `mmtrActive`/`mmtrMotionMirror`/`reversed`/`mmtrPinned`/`mmtrProtection`/`mmtrBlockHeld`/
	 * `mmtrAuthorityTripped`、三个夹紧量（停车目标/总里程/闭塞停车点）；② 继续拥有<b>门</b>（三面旗 +
	 * `doorTarget`）、`isCurrentlyManual`、AWS 两旗、灯光两端、驾驶室/钥匙、以及全部 HUD 文本与静态块。</p>
	 *
	 * <p>为什么门不搬进 ①：门的状态变化本来就低频（每站一次），而 ② 的整份快照在客户端那条路上
	 * 还负责重建 `HIDDEN_PLAYERS` 与乘客插值 —— 让 ① 也去写门，只会多出一个"两边都能改同一格"的口子。</p>
	 * --------------------------------------------------------------------------------------------
	 */

	/** 客户端侧：把一帧的位置（+可选速度）写进镜像。{@code speed} 为 {@code null} = 这一帧不带速度。 */
	public void mmtrApplySyncMotion(double newRailProgress, @Nullable Double newSpeed) {
		if (!isClientside) {
			return;
		}
		railProgress = newRailProgress;
		if (newSpeed != null) {
			speed = newSpeed.doubleValue();
		}
	}

	/** 客户端侧：把一帧的手柄位域写进镜像（位域定义见 {@code MmtrMotionFrame#packControl}）。 */
	public void mmtrApplySyncControl(int packedControl) {
		if (!isClientside) {
			return;
		}
		mmtrThrottleNotch = org.mtr.core.mmtr.net.MmtrMotionFrame.controlThrottleNotch(packedControl);
		mmtrBrakeNotch = org.mtr.core.mmtr.net.MmtrMotionFrame.controlBrakeNotch(packedControl);
		mmtrDriveHandle = org.mtr.core.mmtr.net.MmtrMotionFrame.controlDriveHandle(packedControl);
		mmtrCruiseKmh = org.mtr.core.mmtr.net.MmtrMotionFrame.controlCruiseKmh(packedControl);
		mmtrReverser = org.mtr.core.mmtr.net.MmtrMotionFrame.controlReverser(packedControl);
		mmtrEmergency = org.mtr.core.mmtr.net.MmtrMotionFrame.controlEmergency(packedControl);
	}

	/**
	 * 客户端侧：把一帧的旗标与三个夹紧量写进镜像。
	 *
	 * <p>刻意**不写**门那三面旗与 `isCurrentlyManual`（见上面那段所有权说明）：它们仍由 ② 权威携带，
	 * ① 里那几个位留着只是为了格式完整 —— 于是"两边都能改同一格"的口子不存在。</p>
	 */
	public void mmtrApplySyncState(int flags, double runStopTarget, double runTotalDistance, double blockStopM) {
		if (!isClientside) {
			return;
		}
		mmtrActive = (flags & org.mtr.core.mmtr.net.MmtrMotionFrame.FLAG_MMTR_ACTIVE) != 0;
		mmtrMotionMirror = (flags & org.mtr.core.mmtr.net.MmtrMotionFrame.FLAG_MOTION_MIRROR) != 0;
		reversed = (flags & org.mtr.core.mmtr.net.MmtrMotionFrame.FLAG_REVERSED) != 0;
		mmtrPinned = (flags & org.mtr.core.mmtr.net.MmtrMotionFrame.FLAG_PINNED) != 0;
		mmtrProtection = (flags & org.mtr.core.mmtr.net.MmtrMotionFrame.FLAG_PROTECTION) != 0;
		mmtrBlockHeld = (flags & org.mtr.core.mmtr.net.MmtrMotionFrame.FLAG_BLOCK_HELD) != 0;
		mmtrAuthorityTripped = (flags & org.mtr.core.mmtr.net.MmtrMotionFrame.FLAG_AUTHORITY_TRIPPED) != 0;
		mmtrRunStopTarget = runStopTarget;
		mmtrRunTotalDistance = runTotalDistance;
		mmtrBlockStopM = blockStopM;
	}
	/** C3a: the subsidiary-aspect authority from the last snapshot ("" / "SUBSIDIARY_SHUNT" / "CALLING_ON"). */
	public String getMmtrShuntAuthorityFromSync() { return mmtrShuntAuthority == null ? "" : mmtrShuntAuthority; }
	/** C3a: the authorised movement's speed limit in km/h (mirrored); 0 = no authority. */
	public double getMmtrShuntSpeedLimitKmhFromSync() { return mmtrShuntSpeedLimitKmh; }
	/** C3a: seconds left on the authority (mirrored); 0 = no authority. */
	public double getMmtrShuntRemainingSFromSync() { return mmtrShuntRemainingS; }

	private void simulateMoving(long millisElapsed, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions, int currentIndex) {
		/*
		 * notes/235：**原版 MTR 的走行路径已删除** —— `speedTarget` / `powerLevel` / `stoppingPoint`
		 * 那一整套（连同"档位 × 固定加减速度"的积分）不再存在。服务端走到这里只可能是"还在遗留路径上的
		 * 存量车"：它不动，并且由 {@link #mmtrMotionHoldReason()} 与日志**说出来**。
		 *
		 * <p>客户端镜像只剩一条物理：与服务端逐点相同的 {@link ConsistDynamics} + 本车的
		 * {@link ConsistType}，输入是快照里的 ControlState 与气制动状态。不是有源车的镜像跟着服务端一起停。</p>
		 */
		if (!isClientside) {
			speed = 0;
			mmtrLogLegacyOnRouteIfNeeded();
			return;
		}
		if (!(tryInitMmtrController() && mmtrConsistType != null && mmtrDriveController != null && (mmtrMotionMirror || mmtrActive))) {
			/*
			 * ★ `mmtrMotionMirror || mmtrActive`（2026-10-03 的正面修正）。
			 *
			 * <p>`mmtrActive` 是**服务端**的口径："有司机握着把手"（{@code updateMmtrSyncFields}：
			 * {@code mmtrActive = mmtrManualOverride && ...}）—— 于是**AI/ATO 驾驶的车永远是 false**，
			 * 而这条判据原来只认它。结果是 AI 车的客户端镜像**根本不积分**：位置完全由 ① 每 100 ms
			 * 一个的 MOTION 抬着走，画面上就是"位移掉帧、出站进站不平滑"（用户 2026-10-03 的原话），
			 * 诊断那一行的 {@code 硬对齐} 会一直≈帧数（实测 10~11/秒，就是这条）。
			 *
			 * <p>"这辆车是运动镜像"（① 的 FLAG_MOTION_MIRROR，随 SLOT/STATE 一起到达）才是镜像该不该
			 * 自己往前走的正确口径：是镜像 ⇒ 用同一条 {@link ConsistDynamics}、同一批镜像来的手柄输入
			 * 积分；不是镜像（遗留烘焙路径的车）⇒ 保持原样不动。手柄输入本来就是服务端每拍算好的
			 * （{@code createMirrorControlStateFromSync} 只用镜像字段），所以 AI 车同样能积分。</p>
			 */
			speed = 0;
			return;
		}

		// Distance covered inside the MMTR sub-stepped integration.
		double mmtrDistanceTravelled = -1;

		{
			// MMTR explicit control model: drive from the separated ControlState sent by the input
			// layer (throttle notch 0..N, brake notch, axes). No legacy single-handle mapping.
			// Mirrored client-side from the snapshot so every client simulates identical physics.
			final boolean mmtrProtectionNow;
			final ControlState mmtrControl;
			mmtrProtectionNow = mmtrProtection;
			mmtrControl = createMirrorControlStateFromSync();
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
				final DriveOutput mmtrStepOutput;
				if (mmtrProtectionActive && siSpeed > 0) {
					mmtrStepOutput = new DriveOutput(-mmtrType.getPhysics().emergencyDecelerationMps2(siSpeed), true, true, 0, 1);
				} else if (compForIntegration != null) {
					mmtrStepOutput = compForIntegration.stepAir(mmtrState, siSpeed, stepMillis);
				} else {
					mmtrStepOutput = mmtrDriveController.compute(mmtrState, mmtrType, siSpeed, stepMillis);
				}
				// notes/250：右上角 HUD 的"电机做功"要读**这一拍真正算出来的**力，不许另外再算一套
				// （另算就会变成"HUD 说在牵引、车却没动"那种最费解的组合）。
				mmtrLastDriveAccelerationMps2 = mmtrStepOutput.getAccelerationMetersPerSecondSquared();
				return mmtrStepOutput;
			});
			final double mmtrSpeed = MmtrSupport.siSpeedToInternal(mmtrResult.speedMetersPerSecond);
			mmtrDistanceTravelled = mmtrResult.distanceMeters;
			if (compForIntegration != null) {
				// Publish per-car air state (mirror seed) + averages for the legacy HUD fields.
				mmtrAirState = MmtrComposition.encodeAirStates(compForIntegration);
				mmtrPipePressure = compForIntegration.averagePipePressure();
				mmtrBrakeCylinderPressure = compForIntegration.averageCylinderPressure();
			}
			speed = mmtrSpeed;
			/*
			 * notes/252：**镜像读数诊断**（用户 2026-09-23「牵引力永远是 300 kN」）。
			 *
			 * <p>"读数不对"有四种可能，光看 HUD 分不出来：① 服务端算错（速度没进去 / 车底没功率）；
			 * ② 快照没送到；③ 客户端车底（镜像 ConsistType）缺功率上限；④ HUD 读的那台车不是这台。
			 * 这一行把**本机活算值 / 快照值 / 车底上限 / 速度**并排打出来，一次就能定位。
			 * 只在司机在出力时打（2 s 一次），`-Dmmtr.trace=true` 才输出。</p>
			 */
			if (org.mtr.core.mmtr.MmtrTrace.isEnabled() && (mmtrState.getDriveHandle() != 0 || mmtrState.getCruiseSpeedKmh() > 0)) {
				final long nowMillis = data.getCurrentMillis();
				if (nowMillis - mmtrMirrorDiagMillis >= 2000) {
					mmtrMirrorDiagMillis = nowMillis;
					org.mtr.core.mmtr.MmtrTrace.log("[MMTR-CL] 电机诊断：车=" + id
						+ " 速度SI=" + Math.round(MmtrSupport.internalSpeedToSi(speed) * 100) / 100.0
						+ " 本机活算=" + Math.round(mmtrLiveMotorForceN() / 1000) + "kN"
						+ " 快照值=" + Math.round(vehicleExtraData.getMmtrMotorForceN() / 1000) + "kN"
						+ " 车底Fmax=" + Math.round(mmtrType.getTraction().getMaxTractiveEffortN() / 1000) + "kN"
						+ " 车底P=" + Math.round(mmtrType.getTraction().getMaxPowerW() / 10000) / 100.0 + "MW"
						+ " 质量=" + Math.round(mmtrType.getMassKg() / 100) / 10.0 + "t");
				}
			}
		}

		// 客户端镜像沿同步来的腿阴影推进。notes/235：原来这里还有"到 stoppingPoint 就钉死在停车点"
		// 那一套（以及 MTR 的重复进路回绕）—— 停车点已经不存在；到点/到尽头由 simulate() 里的
		// mmtrRunStopTarget / mmtrRunTotalDistance 夹紧。
		railProgress += mmtrDistanceTravelled >= 0 ? mmtrDistanceTravelled : speed * millisElapsed;
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
		final JsonObject dataJson = Utilities.getJsonObjectFromData(vehicleExtraData.copy(pathUpdateIndex));
		/*
		 * ★ **运动车的腿表不进 ②**（notes/375）。
		 *
		 * 走行体（`mmtrMotionWalker != null`）每"踏上一根新轨 / 掉一根尾轨"就重写一次这份 path，
		 * 而它不是 `DYNAMIC_KEYS` 里的键 —— 于是那一拍被判成"**静态字段变了**"：
		 *
		 * <ul>
		 *   <li>修 `Client#update` 之前：这些整份对"已经持有镜像的客户端"**根本没发出去**
		 *       （notes/375 §2.4），客户端的几何从此停在旧值 —— 就是"换端后车不动再瞬移"；</li>
		 *   <li>修了之后（2026-10-03 17:0x 实测）：它们**真的发出去了** —— 每 1.5–6 秒一次 7.6 KB 整份，
		 *       客户端重建镜像 ⇒ ① 再重新锚定腿表。8 辆车在场时实测 ~30 KB/s，纯属白花。</li>
		 * </ul>
		 *
		 * <p>而运动车的几何**只有 ① 一个来源**（腿表 + 锚点 + 逐腿方向，见 notes/375）：
		 * 镜像刚建起来那一拍，① 会在**同一个 tick**（`END_SERVER_TICK`，包在 ② 之后）把整表送来
		 * （`Client#tookFullVehicleUpdate`），所以 ② 一个字节的 path 都不必带。
		 * 非运动车（遗留烘焙路径）照旧：它们的 path 是静态数据，靠整份快照送。</p>
		 */
		if (mmtrMotionWalker != null) {
			dataJson.remove("path");
		}
		current.add("data", dataJson);
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
		/*
		 * 司机越界那一路的紧急制动（mmtrAuthorityTripped）**不冻结 AWS 状态机**：那两件事互不相干 ——
		 * 灯该跟着信号走（限制还在 ⇒ 保持已确认；限制消失 ⇒ 灭灯）。修前这里只认 mmtrProtection，
		 * 于是"司机在界限上踩着紧急制动"会让 AWS 灯永远停在已确认（绿信号也灭不掉）。
		 * AWS 报警超时的 SPAD 仍然冻结状态机（既有行为，一字未改）。
		 */
		if (!awsBand || !mmtrManualOverride || mmtrProtection && !mmtrAuthorityTripped) {
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
		} else if (mmtrAwsState == MMTR_AWS_WARN && liveManual) {
			/*
			 * 确认窗口**只在车还在动的时候计时**（这本来就是这段文档写的口径："
			 * once parked (occupancy wait) the warning holds without re-timing - no punishment
			 * for an already-safe stand"）。修前它按 tick 无条件累加，只靠 liveManual 拦 SPAD ——
			 * 于是"在红灯前停过一会儿再起步"会在**起步的第一拍**直接吃到 SPAD（累加值早就超窗了）。
			 */
			mmtrAwsWarnElapsedMillis += millisElapsed;
			if (mmtrAwsWarnElapsedMillis >= MMTR_AWS_ACK_WINDOW_MILLIS) {
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
		// 每次重算都先清掉：这一处扣不扣、扣在哪一处道岔，只由这次判定说了算（陈旧键会让
		// replenishForkRequests 去申请一处早就无关的道岔）。
		mmtrSectionAuthorityHoldNodeKey = "";
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
		/*
		 * **扣住本车的是哪一处道岔**（{@link #mmtrSectionAuthorityHoldNodeKey}）：`wouldHaltAtForkOn`
		 * 判的就是"进这条路走到它的**远端**会不会被岔挡住"，所以那一处 = 这条路远端的那个节点。
		 * 与 {@code MmtrMotionWalker#wouldHaltAtForkOn} 同一套口径（那边同样是"目标就是当前轨时用来向、
		 * 否则用前方节点"），否则记下来的键会指向另一处，申请发错地方。
		 */
		final boolean onCurrentRail = entryRail.getHexId().equals(rail.getHexId());
		final Position haltEntry = onCurrentRail ? mmtrMotionWalker.enteredFromPosition() : mmtrMotionWalker.aheadNode();
		final Position haltFar = haltEntry == null ? null : otherEndOfRail(simulator, haltEntry, entryRail);
		mmtrSectionAuthorityHoldNodeKey = haltFar == null ? "" : haltFar.getX() + "," + haltFar.getY() + "," + haltFar.getZ();
		final double toBoundary = towardHigherArc ? current.arcToM - headArc : headArc - current.arcFromM;
		return mmtrMotionWalker.distanceM() + Math.max(0, toBoundary - MMTR_BLOCK_NODE_EPS_M);
	}

	/**
	 * 图上"轨 {@code rail} 在节点 {@code at} 的另一端"（查不到 = null）。
	 *
	 * <p>与 {@code MmtrRunPlanner#otherEndOf}、{@code MmtrMotionWalker#otherEnd} 同义；本类需要它来把
	 * ① 的"出口道岔"写成节点键（那两处一个是 private，一个在别的类里）。</p>
	 */
	private static @Nullable Position otherEndOfRail(Simulator simulator, Position at, Rail rail) {
		final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<Position, Rail> neighbours = simulator.positionsToRail.get(at);
		if (neighbours == null) {
			return null;
		}
		for (final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap.Entry<Position, Rail> entry : neighbours.object2ObjectEntrySet()) {
			if (entry.getValue() == rail && !entry.getKey().equals(at)) {
				return entry.getKey();
			}
		}
		return null;
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
