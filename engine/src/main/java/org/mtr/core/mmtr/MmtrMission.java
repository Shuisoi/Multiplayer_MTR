package org.mtr.core.mmtr;

import org.jspecify.annotations.Nullable;
import org.mtr.core.mmtr.task.MmtrTask;

import java.util.UUID;

/**
 * A mission assigned to a Train (consist). The mission is owned by the train entity — players and
 * AI are only optional executors that read/execute it; with no executor the autopilot drives the
 * same semantics.
 */
public final class MmtrMission {

	public enum Kind { PASSENGER, FREIGHT, MANEUVER }
	public enum State { ASSIGNED, DISPATCHED, AT_TARGET, COMPLETE, FAILED, CANCELED }
	public enum Executor { AUTOPILOT, PLAYER, AI }

	private final long trainVehicleId;
	private final Kind kind;
	private final long startSidingId;
	private final long targetSidingId;
	private final long assignedMillis;
	private State state = State.ASSIGNED;
	private Executor executor = Executor.AUTOPILOT;
	private @Nullable UUID executorPlayer;
	private @Nullable String failureReason;
	/** The task this mission executes (作业单步骤 → 任务实例); null for legacy ad-hoc dispatches. */
	private @Nullable MmtrTask task;
	/**
	 * C10: this movement is a 调车 shunt. When the vehicle plans it, the engine grants a ROUTE-wide
	 * 调车授权 (every rail of the plan), so S1 does not stop it on a rail another train occupies on the
	 * way to the target - and the coupling gate has the authority it requires.
	 */
	private boolean needsShuntAuthority;

	public void setNeedsShuntAuthority(boolean value) {
		needsShuntAuthority = value;
	}

	public boolean needsShuntAuthority() {
		return needsShuntAuthority;
	}

	public MmtrMission(long trainVehicleId, Kind kind, long startSidingId, long targetSidingId, long assignedMillis) {
		this.trainVehicleId = trainVehicleId;
		this.kind = kind;
		this.startSidingId = startSidingId;
		this.targetSidingId = targetSidingId;
		this.assignedMillis = assignedMillis;
	}

	/**
	 * **轨目标**：这次任务的目的地是一根**正规轨道**（而不是某个站台/股道对象），折返换端就是这么表达的。
	 * 非空时车辆侧直接把它交给 {@code MmtrRunPlanner.planToRail}，不再按站台/股道 id 反查轨道。
	 *
	 * @param railHex      图轨 hex（任一端写法都能给，车辆侧会归一）
	 * @param stopFraction 停车点比例（0 = 进站端，1 = 这根轨的远端/尽头，按行车方向）
	 */
	public void setTargetRail(String railHex, double stopFraction) {
		targetRailHex = railHex == null ? "" : railHex.trim();
		targetRailFraction = Math.max(0.0, Math.min(1.0, stopFraction));
	}

	public String getTargetRailHex() {
		return targetRailHex;
	}

	public double getTargetRailFraction() {
		return targetRailFraction;
	}

	/** Whether this mission goes to a raw rail target instead of a platform/siding object. */
	public boolean hasTargetRail() {
		return !targetRailHex.isEmpty();
	}

	private String targetRailHex = "";
	private double targetRailFraction = 1.0;

	/**
	 * **经由点（路径点）**：这次任务的进路必须**依次穿过**这些图节点（世界坐标 {@code "x,y,z"}）。
	 *
	 * <p>作业单步骤的 {@code viaNodes} 原样搬到任务上；车辆自臂时交给
	 * {@code MmtrRunPlanner.planToRail(…, viaNodeKeys)}。为什么必须落到任务上：规划发生在**车辆侧**
	 * （{@link Vehicle#mmtrMotionSelfArmMission} 每 tick 按当前位置重规划），而"回库车走哪条引入线"
	 * 是这一步的编排属性 —— 离了任务，车辆无从知道。</p>
	 */
	private final it.unimi.dsi.fastutil.objects.ObjectArrayList<String> targetViaNodes = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();

	public void setTargetViaNodes(it.unimi.dsi.fastutil.objects.ObjectArrayList<String> viaNodes) {
		targetViaNodes.clear();
		if (viaNodes != null) {
			targetViaNodes.addAll(viaNodes);
		}
	}

	/** 经由点（可能为空：绝大多数步骤没有）。 */
	public it.unimi.dsi.fastutil.objects.ObjectArrayList<String> getTargetViaNodes() {
		return targetViaNodes;
	}

	/**
	 * Attach the task definition this mission executes (where/when/what for timetable/interlocking).
	 *
	 * <p>同时**翻译成"模板 + 参数"**（用户口径：「主任务和子任务分离，并将任务目标分离」）：
	 * 任务类型只说"要做哪一类事"，{@link MmtrTaskTemplate} 说"这类事由哪几件基础操作组成"，
	 * 而目标是**变量**（由 {@code Vehicle} 用引擎对象解析出人话名字后挂上，因为名字住在站台/股道对象上）。</p>
	 */
	public void attachTask(@Nullable MmtrTask task) {
		this.task = task;
		if (task instanceof final org.mtr.core.mmtr.task.StationServiceTask service) {
			template = MmtrTaskTemplate.STOP_AND_SERVE;
			plannedDwellMillis = Math.max(0, service.dwellMs);
		} else if (task instanceof org.mtr.core.mmtr.task.DriveToPlatformTask) {
			template = MmtrTaskTemplate.DRIVE_TO;
			plannedDwellMillis = 0;
		}
	}

	/**
	 * **这一趟挂在作业表的哪一步上**（作业号 / 第几步 / 共几步 / 这一步的人话说明）。
	 *
	 * <h3>为什么要把它摆在 mission 上</h3>
	 * <p>"玩家与作业表相连"要求三件事同时成立：玩家知道自己在做哪一步、联锁照样为他设进路、
	 * 做完了这一趟能算完成。前两件已经分别有 {@code Vehicle}（进路）与驾驶层（操作），
	 * 而"知道在做哪一步"与"算不算完成"都缺一个**任务身份** —— 它既不属于进路也不属于车，
	 * 属于**这一次执行**。摆在 mission 上是唯一不重复的一份：车辆侧同步字段与 HUD 都从这里读。</p>
	 *
	 * @param note 人话说明（作业单步骤自己的 {@code note}，例如"去程到 1 站 1 台"）——
	 *             直接发给客户端当提示用，引擎不替它改写
	 */
	public void attachJobStep(String jobId, int stepIndex, int stepCount, @Nullable String note) {
		attachJobStep(jobId, stepIndex, stepCount, note, "");
	}

	/**
	 * 同上，并把作业单的**服务等级**（{@code 通勤/区域/城际/高铁}）一起带上 —— 抢同一处道岔时的
	 * 优先级要读它（{@link org.mtr.core.mmtr.point.MmtrTrainPriority}，用户 2026-09-27）。
	 */
	public void attachJobStep(String jobId, int stepIndex, int stepCount, @Nullable String note, @Nullable String serviceClass) {
		this.jobId = jobId == null ? "" : jobId;
		this.jobStepIndex = stepIndex;
		this.jobStepCount = stepCount;
		this.jobStepNote = note == null ? "" : note;
		this.serviceClass = serviceClass == null ? "" : serviceClass;
	}

	public String getJobId() {
		return jobId;
	}

	/** 这条作业单的服务等级（空 = 没写 = 通勤）。 */
	public String getServiceClass() {
		return serviceClass;
	}

	/** 0 起的步号；{@code -1} = 这一步不属于任何作业表。 */
	public int getJobStepIndex() {
		return jobStepIndex;
	}

	public int getJobStepCount() {
		return jobStepCount;
	}

	public String getJobStepNote() {
		return jobStepNote;
	}

	private String jobId = "";
	private int jobStepIndex = -1;
	private int jobStepCount;
	private String jobStepNote = "";
	/** 作业单的服务等级（{@code 通勤/区域/城际/高铁}，空 = 没写）—— 道岔/进路裁决读它。 */
	private String serviceClass = "";

	/*
	 * ============================ 子任务（主任务 → 基础操作） ============================
	 *
	 * 用户口径（2026-09-21）：
	 *
	 * > 「任务系统应该将主任务和子任务分离，并且将任务目标分离…主任务：停站乘降 —3站1台；
	 * >   子任务：1.停在3站1台 2.开门 3.等待上下客 4.关门。其中 3站1台则为变量，任务为一对象。
	 * >   同理主任务 开往——车厂987654股道1；子任务：1.停在车厂987654股道1。
	 * >   这样拆一个任务到基础的操作以简化逻辑判定。」
	 *
	 * 于是这一层只保留三样东西：**模板**（要做哪几件基础操作）、**目标**（变量）、
	 * **展开出来的清单**（基础操作 + 每条的达成状态）。判定全在 {@code Vehicle}（有观测的那一方）。
	 */

	/** 主任务模板（开往 / 停站乘降 / …）—— 由作业调度器从步骤类型翻译过来。 */
	private MmtrTaskTemplate template = MmtrTaskTemplate.NONE;
	/** **任务目标**（变量：站台 / 股道 / 车站 / 轨 hex，带人话名字）。 */
	private MmtrTaskTarget target = MmtrTaskTarget.none();
	/** 这一步的基础操作清单（顺序 = 必须依次达成）；空 = 原地动作那类，没有可拆的基础操作。 */
	private final it.unimi.dsi.fastutil.objects.ObjectArrayList<MmtrSubTask> subTasks = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
	/** 展开时用的等待时长（毫秒）—— {@link MmtrSubTask.Kind#WAIT_PASSENGERS} 读它。 */
	private long subTaskDwellMillis;
	/** 计划给的停留（毫秒，0 = 计划没给）—— 展开时与引擎默认取大者。 */
	private long plannedDwellMillis;
	/** 子任务状态每次变化都 +1：客户端把它回传，用来做**双向确认**（对不上就是两边看到的不一样）。 */
	private long subTaskRevision;
	/** 客户端确认过几次（人工按键或镜像自动确认）。 */
	private int subTaskAcks;
	/** 实际到站时刻（引擎时钟，毫秒）；{@code -1} = 还没到。 */
	private long stationArrivalMillis = -1;
	/** 实际出站时刻（门关好、这一步作业做完）—— 任务完成的时刻就是它。 */
	private long stationDepartureMillis = -1;

	/** 记下这次执行的**模板与目标**（作业调度器在派车时从步骤翻译过来）。 */
	public void attachTaskShape(@Nullable MmtrTaskTemplate template, @Nullable MmtrTaskTarget target) {
		if (template != null) {
			this.template = template;
		}
		if (target != null) {
			this.target = target;
		}
	}

	public MmtrTaskTemplate getTemplate() {
		return template;
	}

	/** **任务目标**（变量）—— 子任务文案与到站判定都从它取"停在哪儿 / 开往哪儿"。 */
	public MmtrTaskTarget getTarget() {
		return target;
	}

	/** 计划给的停留时长（毫秒；0 = 计划没给，用引擎默认）。 */
	public void setPlannedDwellMillis(long millis) {
		plannedDwellMillis = Math.max(0, millis);
	}

	/**
	 * **按"模板 + 目标"展开基础操作清单**（幂等：链已存在就返回 false）。
	 *
	 * @param engineDefaultDwellMillis 引擎默认等待时长（模板里的"等待上下客"取它；计划给了更长的取计划那条）
	 * @return 是否真的建了链
	 */
	public boolean ensureSubTasks(long engineDefaultDwellMillis) {
		if (!subTasks.isEmpty()) {
			return false;
		}
		final long dwell = plannedDwellMillis > 0 ? Math.max(plannedDwellMillis, engineDefaultDwellMillis) : engineDefaultDwellMillis;
		subTasks.addAll(template.expand(target, dwell));
		if (subTasks.isEmpty()) {
			return false;
		}
		subTaskDwellMillis = dwell;
		bumpSubTaskRevision();
		return true;
	}

	public it.unimi.dsi.fastutil.objects.ObjectArrayList<MmtrSubTask> subTasks() {
		return subTasks;
	}

	/** 这一步有没有子任务链（没有 = 老口径：到点停够就算完成）。 */
	public boolean hasSubTasks() {
		return !subTasks.isEmpty();
	}

	/** 这条链要求的停留（毫秒）；引擎默认与计划停留取大者。 */
	public long subTaskDwellMillis() {
		return subTaskDwellMillis;
	}

	public long subTaskRevision() {
		return subTaskRevision;
	}

	public int subTaskAcks() {
		return subTaskAcks;
	}

	public void bumpSubTaskRevision() {
		subTaskRevision++;
	}

	/** 记一次客户端确认（双向确认的上行那一半）。 */
	public void recordSubTaskAck() {
		subTaskAcks++;
	}

	/** 链上第一条还没达成的基础操作（全部达成时返回 null）—— 这就是"现在该做什么"。 */
	@Nullable
	public MmtrSubTask currentSubTask() {
		for (final MmtrSubTask subTask : subTasks) {
			if (!subTask.isDone()) {
				return subTask;
			}
		}
		return null;
	}

	/**
	 * 链上的第一条（**"到没到目标"就是它**）——
	 * 任务状态机的前进判据与子任务判据共用这一条，于是"到站"只有一处定义。
	 */
	@Nullable
	public MmtrSubTask firstSubTask() {
		return subTasks.isEmpty() ? null : subTasks.get(0);
	}

	/** @return 达成的基础操作条数 */
	public int subTasksDoneCount() {
		int count = 0;
		for (final MmtrSubTask subTask : subTasks) {
			if (subTask.isDone()) {
				count++;
			}
		}
		return count;
	}

	/** 全部基础操作都达成了没有（没有链时恒 false —— 调用方用 {@link #hasSubTasks()} 分开判）。 */
	public boolean allSubTasksDone() {
		if (subTasks.isEmpty()) {
			return false;
		}
		for (final MmtrSubTask subTask : subTasks) {
			if (!subTask.isDone()) {
				return false;
			}
		}
		return true;
	}

	/** 线上一格：{@code STOP_AT_TARGET:DONE:A:停在 3站1台;…}（见 {@link MmtrSubTask#encode}）。 */
	public String encodeSubTasks() {
		final StringBuilder builder = new StringBuilder();
		for (final MmtrSubTask subTask : subTasks) {
			if (builder.length() > 0) {
				builder.append(';');
			}
			builder.append(subTask.encode());
		}
		return builder.toString();
	}

	/** 给 HUD/日志用的一行人话：{@code ✔停在 3站1台 ▶开门 ·等待上下客 20s ·关门}。 */
	public String describeSubTasks(long now) {
		final StringBuilder builder = new StringBuilder();
		for (final MmtrSubTask subTask : subTasks) {
			if (builder.length() > 0) {
				builder.append(' ');
			}
			builder.append(subTask.describe());
		}
		return builder.toString();
	}

	/**
	 * **现在该做什么**（给司机的一句提示，含**当前那条基础操作的人话**与实时进度）。
	 *
	 * <p>话从引擎出、按原话下发（与作业步骤的 note 同一个规矩）：客户端不拼中文、不判状态。
	 * 实时进度（"还差 5s"）只在这里出现，链上的文案保持稳定 —— 否则每一秒都在推全量字符串。</p>
	 */
	public String subTaskHint(long now) {
		if (subTasks.isEmpty()) {
			return "";
		}
		final MmtrSubTask current = currentSubTask();
		if (current == null) {
			return "本步作业完成（" + target.label() + "，已到站 " + describeSecondsUntil(now, stationArrivalMillis) + "）";
		}
		return switch (current.kind()) {
			case STOP_AT_TARGET -> mmtrStopHint(current);
			case OPEN_DOORS -> "开门：" + (current.state() == MmtrSubTask.State.PENDING ? "等车停稳" : "请按开门键");
			case WAIT_PASSENGERS -> "等待上下客（" + target.label() + "）：还差 "
				+ Math.max(0, Math.round((subTaskDwellMillis - current.elapsedMillis(now)) / 1000.0)) + "s";
			case CLOSE_DOORS -> "关门：请按关门键";
		};
	}

	/** 到站那一条的提示：目标**指名道姓**（"停在 3站1台"），并说清宽松口径。 */
	private String mmtrStopHint(MmtrSubTask current) {
		if (current.state() == MmtrSubTask.State.PENDING) {
			return "出发前往 " + target.label();
		}
		return "进站：把车停在 " + target.label() + (target.kind() == MmtrTaskTarget.Kind.PLATFORM
			? "（停稳即可，不必对准停车点）" : "（停稳即可）");
	}

	private static String describeSecondsUntil(long now, long millis) {
		return millis < 0 ? "?" : Math.round(Math.max(0, now - millis) / 1000.0) + "s";
	}

	/** 到站确认（宽松判据达成的那一刻记一次，只记第一次）。 */
	public boolean markStationArrival(long now) {
		if (stationArrivalMillis >= 0) {
			return false;
		}
		stationArrivalMillis = now;
		return true;
	}

	public long getStationArrivalMillis() {
		return stationArrivalMillis;
	}

	public boolean hasStationArrival() {
		return stationArrivalMillis >= 0;
	}

	/** 出站确认（门关好、这一步做完）。 */
	public boolean markStationDeparture(long now) {
		if (stationDepartureMillis >= 0) {
			return false;
		}
		stationDepartureMillis = now;
		return true;
	}

	public long getStationDepartureMillis() {
		return stationDepartureMillis;
	}

	/**
	 * **原地动作的目的轨**（notes/150）。
	 *
	 * <p>换端这类任务按设计没有目标对象（车一动不动），可任务生命周期又需要"有没有到位"这件事 ——
	 * 于是派车时把**车此刻所在的那根轨**记下来当目的轨：车已经在目标轨上 ⇒ 立刻"到位"，
	 * 生命周期照常（已派→到点→完成），真正动手的是车辆侧的原地动作执行器。</p>
	 */
	private String inPlaceTargetRailHex = "";

	public void setInPlaceTargetRailHex(String railHex) {
		inPlaceTargetRailHex = railHex == null ? "" : railHex;
	}

	/** 这是一次**原地动作**（没有目的地：目的轨就是车现在所在的那根轨）。 */
	public boolean isInPlace() {
		return task != null && task.inPlace();
	}

	public String getInPlaceTargetRailHex() {
		return inPlaceTargetRailHex;
	}

	@Nullable
	public MmtrTask getTask() {
		return task;
	}

	public long getTrainVehicleId() { return trainVehicleId; }
	public Kind getKind() { return kind; }
	public long getStartSidingId() { return startSidingId; }
	public long getTargetSidingId() { return targetSidingId; }
	public long getAssignedMillis() { return assignedMillis; }
	public State getState() { return state; }
	public Executor getExecutor() { return executor; }
	public @Nullable UUID getExecutorPlayer() { return executorPlayer; }
	public @Nullable String getFailureReason() { return failureReason; }

	public boolean isTerminal() {
		return state == State.COMPLETE || state == State.FAILED || state == State.CANCELED;
	}

	public boolean dispatch() {
		return transition(State.DISPATCHED);
	}

	public boolean atTarget() {
		return transition(State.AT_TARGET);
	}

	public boolean complete() {
		return transition(State.COMPLETE);
	}

	public boolean fail(String reason) {
		if (state == State.FAILED) {
			return false;
		}
		failureReason = reason;
		return transition(State.FAILED);
	}

	public boolean cancel() {
		return transition(State.CANCELED);
	}

	/** Hands the driving execution to a player/AI (or back to the autopilot). The mission is unchanged. */
	public boolean setExecutor(Executor executor, @Nullable UUID playerUuid) {
		if (isTerminal()) {
			return false;
		}
		if (executor == Executor.PLAYER && playerUuid == null) {
			return false;
		}
		if (executor != Executor.PLAYER && playerUuid != null) {
			playerUuid = null;
		}
		this.executor = executor;
		this.executorPlayer = playerUuid;
		return true;
	}

	private boolean transition(State next) {
		final boolean allowed = switch (state) {
			case ASSIGNED -> next == State.DISPATCHED || next == State.FAILED || next == State.CANCELED;
			case DISPATCHED -> next == State.AT_TARGET || next == State.FAILED || next == State.CANCELED;
			case AT_TARGET -> next == State.COMPLETE || next == State.FAILED || next == State.CANCELED;
			default -> false;
		};
		if (allowed) {
			state = next;
		}
		return allowed;
	}
}
