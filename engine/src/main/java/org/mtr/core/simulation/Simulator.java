package org.mtr.core.simulation;

import it.unimi.dsi.fastutil.ints.IntIntImmutablePair;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.*;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.*;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.directions.DirectionsFinder;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.SerializedDataBaseWithId;
import org.mtr.core.servlet.MessageQueue;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.core.servlet.QueueObject;
import org.mtr.core.servlet.WebFeed;
import org.mtr.core.servlet.WebRun;
// 优先级（服务等级 + 车号，用户 2026-09-27）：道岔/进路裁决按它定序。
import org.mtr.core.mmtr.point.MmtrTrainPriority;
import org.mtr.core.tool.Utilities;
import org.mtr.legacy.data.LegacyRailLoader;

import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Per-dimension simulation engine — one {@link Simulator} per Minecraft world / dimension.
 *
 * <p>The simulator owns the in-memory graph of stations, platforms, sidings, routes, depots,
 * lifts, rails, homes, landmarks and clients for its dimension, and ticks them forward in
 * one-second slices via {@link #tickUntilCaughtUp()}. State mutation is single-threaded: any
 * cross-thread work (HTTP servlets, embedding mod callbacks) must be marshalled through
 * {@link #run(Runnable)} so it executes on the simulator's own thread.</p>
 *
 * <p>Persistence is delegated to {@link FileLoader} — one per top-level entity type. Saves are
 * incremental (only changed buckets are rewritten) unless {@code useReducedHash} is {@code false}
 * during the final shutdown save.</p>
 */
@Log4j2
public class Simulator extends Data implements Utilities {

	private long lastMillis;
	private boolean autoSave = false;
	private long gameMillis;
	/**
	 * Real-time milliseconds per in-game day or {@code 0} if unknown / paused; default = 20 in-game minutes ≈ Minecraft's vanilla rate.
	 */
	@Getter
	private long gameMillisPerDay = DEFAULT_GAME_MILLIS_PER_DAY;
	/**
	 * Whether the daylight cycle (and therefore the in-game clock) is currently advancing.
	 */
	@Getter
	private boolean isTimeMoving;
	private long lastSetGameMillisMidnight;
	private int currentPassengerDirectionsRequests;

	/**
	 * Connected dashboard / mod clients for this dimension.
	 */
	public final ObjectArraySet<Client> clients = new ObjectArraySet<>();
	/**
	 * MMTR: optional server-side ConsistType definitions and the default consist id used for
	 * vehicles without an explicit type. Null/absent keeps the legacy driving behaviour.
	 */
	public ConsistTypeRegistry mmtrConsistTypes;
	public String mmtrDefaultConsistTypeId;
	/**
	 * MMTR: periodic task sources (timetable-style adapters). Each fires on its own cadence and
	 * attaches a mission to an idle parked train — the task belongs to the consist itself, no
	 * player/AI needed.
	 */
	public final ObjectArrayList<org.mtr.core.mmtr.MmtrPeriodicTaskSource> mmtrPeriodicTaskSources = new ObjectArrayList<>();
	/**
	 * MMTR: consist-job scheduler (web-driven diagrams). Null until a scheduler is attached; it
	 * ticks each simulation tick after vehicle simulation.
	 */
	public org.mtr.core.mmtr.job.MmtrJobScheduler mmtrJobScheduler;
	/** Named consist templates (编组代码 -> 车列), loaded from <save>/mmtr-consist-templates.json. */
	public org.mtr.core.mmtr.job.MmtrConsistTemplateRegistry mmtrConsistTemplates = new org.mtr.core.mmtr.job.MmtrConsistTemplateRegistry();
	private boolean mmtrDepotPathsGenerated;
	/**
	 * MMTR job mode: when true the legacy depot frequency/departure auto-dispatch is disabled -
	 * vehicles only run what MmtrJobScheduler starts (the web diagrams). Default false keeps the
	 * original MTR timetable behaviour until migration is complete.
	 */
	public boolean mmtrJobsMode;
	public org.mtr.core.mmtr.job.MmtrJobRegistry mmtrJobRegistry = new org.mtr.core.mmtr.job.MmtrJobRegistry();
	private java.nio.file.Path mmtrJobsPath;
	/**
	 * P1 时刻表生成器的**输入层**（线路 / 分段密度 / 车底）—— 持久化的只有输入，
	 * 计划（趟次表 + 车底交路）永远是算出来的。见 {@code docs/01-设计/任务系统-线路派生与车底交路-设计.md}。
	 */
	public org.mtr.core.mmtr.plan.MmtrPlanInputs mmtrPlanInputs = new org.mtr.core.mmtr.plan.MmtrPlanInputs();
	private java.nio.file.Path mmtrPlanPath;
	/** 加载/保存时算出来的输入问题（空的 = 通过）；P4 的派发器在非空时拒绝排班。 */
	public final it.unimi.dsi.fastutil.objects.ObjectArrayList<String> mmtrPlanErrors = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
	/**
	 * P4：**每条线路一个派发器**（{@code lineId → MmtrPlanDispatcher}）。
	 *
	 * <p>计划（趟次表 + 交路）是算出来的：输入一变就重建（{@link #mmtrRefreshPlanDispatchers}），
	 * 派发器则带着"派到第几步/分给哪辆车"的状态跨 tick 存活 —— 那份状态不能每次重算，
	 * 否则会重复派发。</p>
	 */
	public final java.util.HashMap<String, org.mtr.core.mmtr.plan.MmtrPlanDispatcher> mmtrPlanDispatchers = new java.util.HashMap<>();
	/** 上一次重建派发器用的输入签名（变了才重建，避免每 tick 重排一整天）。 */
	private String mmtrPlanSignature = "";
	/**
	 * P5：**运行时事件**（临时高峰 / 延误 / 故障 / 降速）。
	 *
	 * <p>不落盘：配置里那份是**规则**，事件实例是"这次运营里发生了什么事"，重启即清空是对的。</p>
	 */
	public final org.mtr.core.mmtr.plan.MmtrEventRegistry mmtrPlanEvents = new org.mtr.core.mmtr.plan.MmtrEventRegistry();
	/**
	 * P6 ④：**手工指派**（{@code assign}）—— {@code [fromConsistId, fromTripId（空=从第一趟起）, toConsistId]}。
	 * 优先于自动排班（§3），并且是**运行时**的（不落盘：它是对"今天这份交路"的人工覆盖，重启即回到自动排班）。
	 */
	public final java.util.ArrayList<String[]> mmtrPlanManualAssignments = new java.util.ArrayList<>();
	/**
	 * P6 ③：**玩家正在开的编组**（接管）。派发器对这些车一步都不派。
	 *
	 * <p>与手工指派一样是**运行时**状态（不落盘、不进签名）：它描述的是"现在谁在开"，
	 * 而不是"计划是什么"。重建派发器之后要重新贴上去（见 {@link #mmtrRefreshPlanDispatchers}）。</p>
	 */
	public final java.util.HashSet<String> mmtrPlanPlayerDriven = new java.util.HashSet<>();

	/** P6 ③ 接管 / 归还：立即生效，并且对之后重建的派发器同样生效。 */
	public boolean setMmtrPlanPlayerDriven(String consistId, boolean player) {
		final boolean changed = player ? mmtrPlanPlayerDriven.add(consistId) : mmtrPlanPlayerDriven.remove(consistId);
		mmtrPlanDispatchers.values().forEach(dispatcher -> dispatcher.setPlayerDriven(consistId, player));
		System.out.println("[MMTR-PLAN] " + (player ? "玩家接管" : "归还给 AI") + "：编组 " + consistId);
		return changed;
	}

	/** 登记一条手工指派（同 from+fromTrip 覆盖）。 */
	public void assignMmtrPlanManually(String fromConsistId, String fromTripId, String toConsistId) {
		mmtrPlanManualAssignments.removeIf(existing -> existing[0].equals(fromConsistId)
			&& (existing[1] == null ? "" : existing[1]).equals(fromTripId == null ? "" : fromTripId));
		mmtrPlanManualAssignments.add(new String[]{fromConsistId, fromTripId == null ? "" : fromTripId, toConsistId});
		mmtrPlanSignature = "";   // 变了 → 下次 tick 重算
	}
	/**
	 * Rolling-stock manifest (车辆生成表): declares which consist each depot siding must carry after
	 * the explicit vehicle reset on every server restart. AI diagram steps are disabled by default;
	 * the manifest + per-vehicle operations own the traffic.
	 */
	public org.mtr.core.mmtr.manifest.MmtrRollingStockManifest mmtrRollingStock = new org.mtr.core.mmtr.manifest.MmtrRollingStockManifest();
	private java.nio.file.Path mmtrManifestPath;
	/** AI diagram step execution (web consist jobs). Off by default; reserved for the future task layer. */
	public boolean mmtrAiJobStepsEnabled;
	/** Operator-set turnout (道岔) branch states, persisted to mmtr-points.json. */
	public org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore mmtrPointBranches = new org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore();
	private java.nio.file.Path mmtrPointsPath;
	/** Authoritative junction leg tables (进向表): (node, via) -> ordered continuation rails. */
	public final org.mtr.core.mmtr.point.MmtrJunctionLegsRegistry.LegsStore mmtrJunctionLegs = new org.mtr.core.mmtr.point.MmtrJunctionLegsRegistry.LegsStore();
	private java.nio.file.Path mmtrJunctionLegsPath;
	/** Wayside signal registry (信号机登记表): placed MTR signal lights participating in the
	 * block/section model, with optional covered binds (which rail/approach a light reads). */
	public final org.mtr.core.mmtr.signal.MmtrSignalRegistry mmtrSignals = new org.mtr.core.mmtr.signal.MmtrSignalRegistry();
	private java.nio.file.Path mmtrSignalsPath;
	/** OP command queue (指令栏): web-pushed commands wait here for the game-side executor
	 * (fabric server) to poll and run (e.g. /signals scan); results come back as log lines. */
	public final java.util.ArrayDeque<String> mmtrCommandQueue = new java.util.ArrayDeque<>();
	public final java.util.ArrayDeque<String> mmtrCommandLog = new java.util.ArrayDeque<>();

	/** Web OP pushes a command; the game-side executor polls {@link #mmtrPollCommand()}. */
	public void mmtrPushCommand(String command) {
		final String cmd = command == null ? "" : command.trim();
		if (!cmd.isEmpty()) {
			mmtrCommandQueue.addLast(cmd);
			mmtrCommandLog.addLast("> " + cmd);
			while (mmtrCommandLog.size() > 200) {
				mmtrCommandLog.removeFirst();
			}
		}
	}

	/** Game-side executor takes the next pending command, or null when idle. */
	public @org.jspecify.annotations.Nullable String mmtrPollCommand() {
		final String cmd = mmtrCommandQueue.pollFirst();
		if (cmd != null) {
			mmtrCommandLog.addLast("… 执行: " + cmd);
			while (mmtrCommandLog.size() > 200) {
				mmtrCommandLog.removeFirst();
			}
		}
		return cmd;
	}

	/** Game-side executor reports a command outcome back into the OP log. */
	public void mmtrCommandResult(String result) {
		if (result != null && !result.isEmpty()) {
			mmtrCommandLog.addLast(result);
			while (mmtrCommandLog.size() > 200) {
				mmtrCommandLog.removeFirst();
			}
		}
	}
	/** P3 turnout authority (multi-level control): auto requests/grants per (node, via) point; the
	 * walker reads manual operator settings (mmtrPointBranches) first and this authority second. */
	public final org.mtr.core.mmtr.point.MmtrPointAuthority mmtrPointAuthority = new org.mtr.core.mmtr.point.MmtrPointAuthority(this::getCurrentMillis)
		.withTurnoutLookup(this::mmtrTurnout)
		.withPositionChangeGuard(this::mmtrPositionChangeBlockedReason)
		.withHolderOccupancy(this::mmtrVehicleOnNodeRails)
		/*
		 * 实际位置：申请要的那一位如果**就是岔现在这一位**，这一趟什么都不用扳 ⇒ 不该排队、也不进净空闸
		 * （见 MmtrPointAuthority#request 里"位置已经就是我要的那一位不排队"那段）。权限层自己只有
		 * "持有者驱动"的位置（没人持有 = 无主），问不出"现在实际在哪一位"，所以要由这里喂进去。
		 */
		.withActualPositionLookup(this::mmtrTurnoutPosition)
		/*
		 * 优先级：**服务等级 + 车号**（用户 2026-09-27："同时抢一个道岔……同级谁车号小谁先走，
		 * 不同级按级别踩头"）。权限层只认 owner 字符串，所以由这里把"车 → 任务 → 作业单"那条链
		 * 翻出来：等级写在作业单上（{@code serviceClass}），车号从作业单号末两位取。
		 */
		.withOwnerPriority(this::mmtrTrainPriority);

	/**
	 * **某列车是不是还压在这处节点的轨上**（占用树答）。权限层用它决定"物理持有窗口到期能不能放位"：
	 * 车还压在这处道岔的轨上时放位 = 允许别人把道岔从它脚下扳走（2026-09-16 现场"两个车顶头"的成因），
	 * 所以那时持有续期而不是释放。
	 */
	private @org.jspecify.annotations.Nullable Boolean mmtrVehicleOnNodeRails(long x, long y, long z, String owner) {
		final long vehicleId = vehicleIdOfOwner(owner);
		if (vehicleId == 0) {
			// 不是车辆 owner（例如测试里的 "vA"/"vB" 标签）：查不出来 ⇒ null（调用方各自保守取舍）。
			return null;
		}
		final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<org.mtr.core.data.Position, org.mtr.core.data.Rail> neighbours =
			positionsToRail.get(new org.mtr.core.data.Position(x, y, z));
		if (neighbours == null) {
			return false;
		}
		final org.mtr.core.data.Position[] orderedScratch = new org.mtr.core.data.Position[2];
		final ObjectArrayList<Object2ObjectAVLTreeMap<org.mtr.core.data.Position, Object2ObjectAVLTreeMap<org.mtr.core.data.Position, org.mtr.core.data.VehiclePosition>>> trees = mmtrOccupancyTrees();
		for (final org.mtr.core.data.Rail rail : neighbours.values()) {
			final org.mtr.core.data.Position[] ordered = rail.mmtrOrderedPositions();
			orderedScratch[0] = ordered[0];
			orderedScratch[1] = ordered[1];
			for (int i = 0; i < trees.size(); i++) {
				final org.mtr.core.data.VehiclePosition footprint = org.mtr.core.mmtr.signal.MmtrSectionService.footprintOn(trees.get(i), orderedScratch);
				if (footprint != null && footprint.footprintIds().contains(vehicleId)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * **改道岔位置之前的净空闸**（授权层问过来的）：另一列车压在岔区上时不许改位置 —— 与人工扳岔、
	 * 意图扳岔读**同一条** {@link org.mtr.core.mmtr.signal.MmtrJunctionState} 判定，所以不会出现
	 * "这盏灯说岔区被占、道岔却照样能扳"。
	 *
	 * <p>为什么必须包括**授权申请**这条路（不只是每 tick 的同步）：位置由持有者决定（T1），
	 * 所以"另一条进路的新持有人"在申请那一步就会把位置改掉 —— 前车跨过岔口后释放持有、尾巴却还压在
	 * 净空区里时，道岔就在它脚下被换位了。</p>
	 *
	 * <p><b>请求方自己压在岔上不算</b>：它按着自己的位（T1），本来就该能改自己的需要（换端/折返），
	 * 否则会把自己锁死；而"从自己车下抽走"这件事由走行侧与人工侧的闸门各自兜住。</p>
	 */
	public @org.jspecify.annotations.Nullable String mmtrPositionChangeBlockedReason(long x, long y, long z, int newPosition, String owner) {
		final String reason = org.mtr.core.mmtr.signal.MmtrJunctionState.blockedThrowReasonExcept(this,
			new org.mtr.core.data.Position(x, y, z), vehicleIdOfOwner(owner));
		return reason == null ? null : reason + "（改位置的请求方 " + owner + "）";
	}

	/** {@code "v123"} → 123（不是这个写法就返回 0 = 不排除任何车）。 */
	private static long vehicleIdOfOwner(@org.jspecify.annotations.Nullable String owner) {
		if (owner == null || !owner.startsWith("v")) {
			return 0;
		}
		try {
			return Long.parseLong(owner.substring(1));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/**
	 * **这列车的优先级**（服务等级 + 车号；用户 2026-09-27）：{@code "v<车辆id>"} → 车 → 当前任务 →
	 * 作业单号与服务等级 → {@link org.mtr.core.mmtr.point.MmtrTrainPriority}。
	 *
	 * <p>为什么要绕这一圈：优先级是**运营属性**（等级写在作业单上、车号写在作业单号里），
	 * 而权限层只认 owner 字符串。这里每次现问，不缓存 —— 车跑完一条作业单换下一条（连挂/换单）时，
	 * 下一次裁决就按新的作业单算。</p>
	 *
	 * @return {@code null} = 问不出（不是车辆 owner / 车不在场上 / 没有任务）⇒ 裁决退回老口径
	 */
	private @org.jspecify.annotations.Nullable MmtrTrainPriority mmtrTrainPriority(String owner) {
		final long vehicleId = vehicleIdOfOwner(owner);
		if (vehicleId == 0) {
			return null;
		}
		final org.mtr.core.data.Vehicle vehicle = mmtrFindVehicle(vehicleId);
		if (vehicle == null) {
			return null;
		}
		final org.mtr.core.mmtr.MmtrMission mission = vehicle.getMmtrMission();
		return mission == null ? null : org.mtr.core.mmtr.point.MmtrTrainPriority.of(mission.getJobId(), mission.getServiceClass());
	}

	/**
	 * T4 准入门槛：**无任务不得操纵**。默认关（引擎单测与工具链在"没有任务"的前提下开车），
	 * 真服务器上打开 —— 与 {@code mmtrDefaultPointsZero} 同一个策略开关模式。
	 */
	public boolean mmtrRequireTaskToDrive = false;
	/** C3a 调车授权 (subsidiary-aspect authority): one train at a time may pass a signal at danger into
	 * an occupied section to couple; the registry is the data plane the vehicle/yard read. */
	public final org.mtr.core.mmtr.signal.MmtrShuntAuthorityRegistry mmtrShuntAuthorities = new org.mtr.core.mmtr.signal.MmtrShuntAuthorityRegistry(this::getCurrentMillis);
	/**
	 * notes/408 S1：**玩家值守状态机**（"玩家 X 现在是什么态"的唯一真源）。
	 *
	 * <p>与 {@link #mmtrPlanPlayerDriven}、{@link #mmtrShuntAuthorities} 一样是**运行时**状态
	 * （不落盘、不进签名）：它描述"现在谁在开"，重启即回到空闲是对的。</p>
	 *
	 * <p>为什么在构造函数里初始化而不是像上面几个那样就地 new：它要 {@code this} 的引用
	 * （每次迁移都要问作业调度器"这车挂在哪条作业单上"、问车辆表"这车还在不在"），
	 * 而就地初始化时 {@code this} 还不能用。</p>
	 */
	public final org.mtr.core.mmtr.duty.MmtrDutyRegistry mmtrDuties;
	/**
	 * notes/411 **钥匙兜底网**：登记"丢过钥匙且还挂着在跑自动任务"的车，按有界重试补回引擎占位钥匙。
	 * 稳态成本 = 每 tick 一次自增 + 一次取模 + 一次 {@code isEmpty}（表空即返回），不做全车队扫描。
	 */
	public final org.mtr.core.mmtr.duty.MmtrAutoKeyWatch mmtrAutoKeyWatch = new org.mtr.core.mmtr.duty.MmtrAutoKeyWatch();
	/** S5 进路登记表 (route registry): the live route object per train (rails + turnouts + SET/PENDING
	 * state), derived from {@link #mmtrPointAuthority}. The signal layer (A2) reads it to decide whether
	 * a proceed aspect may be shown; the ops feed shows it per train. */
	public final org.mtr.core.mmtr.route.MmtrRouteRegistry mmtrRoutes = new org.mtr.core.mmtr.route.MmtrRouteRegistry()
		/*
		 * 敌对进路裁决要判"**谁的车身压在争用的那根轨上**"（"先出清"档，2026-09-17 现场修：
		 * 北段 S1/S2 双向占用测试班三班车互相扣死）。登记表没有 Simulator，所以位置查询由这里喂进去。
		 */
		.withVehicleRailLookup(this::mmtrVehicleRailHex);

	/** 某列车现在压在哪根轨上（找不到车 = {@code null}）；敌对进路"先出清"档只读它。 */
	public @org.jspecify.annotations.Nullable String mmtrVehicleRailHex(long vehicleId) {
		final Vehicle vehicle = mmtrFindVehicle(vehicleId);
		final org.mtr.core.mmtr.segment.MmtrMotionPosition walker = vehicle == null ? null : vehicle.getMmtrMotionWalker();
		return walker == null ? null : walker.railHex();
	}
	/**
	 * 闭塞区间（两层模型，notes/166）：Level 1 轨道区间（无方向、占用判定的唯一单位）＋
	 * Level 2 行车区间（有方向，灯到灯；无灯连通块整块一个大区间）。
	 * 这是**唯一**的区间层 —— v1（逐轨）与 v2（作为独立模型）都已删除，不留回退分支。
	 */
	public final org.mtr.core.mmtr.signal.MmtrSectionService mmtrSections = new org.mtr.core.mmtr.signal.MmtrSectionService(this);
	/** 硬默认 0 (option 3): real servers preset every turnout to operator branch 0. Engines tests keep
	 * this false so authority/mission semantics stay synthetic; {@link org.mtr.core.Main} enables it. */
	public boolean mmtrDefaultPointsZero;
	private String mmtrPointDefaultsSignature = "";

	/**
	 * MMTR health watchdog: produces a periodic health summary (SimRail-style server health):
	 * live vehicle/riding/driver counts, active mmtr overrides, protection states and jammed
	 * routes, so an operator (or an external process) can detect stuck trains early.
	 */
	private static final int MMTR_WATCHDOG_INTERVAL_TICKS = 100;
	/** 日志降噪: the summary is RECOUNTED every 5 s but only PRINTED this often while everything is idle. */
	private static final long MMTR_WATCHDOG_LOG_INTERVAL_MILLIS = 60_000L;
	private int watchdogTickCounter;
	private long watchdogLastCheckAt;
	private long watchdogLastLogAtMillis;
	private int watchdogVehicles;
	private int watchdogRiders;
	private int watchdogDrivers;
	private int watchdogMmtrOverrides;
	private int watchdogProtections;
	private int watchdogJammedRoutes;

	/**
	 * 性能探针的维度标签（notes/337）：一个服务端可以有多个维度，汇总行里必须能分清是谁的。
	 *
	 * <p>探针默认关闭（{@code -Dmmtr.probe=true} / 运行中 {@code probe on}），关着时
	 * {@link org.mtr.core.mmtr.probe.MmtrProbe#on()} 是一次 volatile 读，其余全部跳过。</p>
	 */
	private String mmtrProbeLabel = "dim?";

	/*
	 * ------------------------------------------------ 网页任务的 tick 预算（notes/172）
	 *
	 * 起因是"网页刷新地图会阻碍客户端动作"：HTTP 请求的活儿是被塞回模拟线程、在 tick 里执行的，
	 * 而嵌入式运行时（模组 `useThreadedSimulation=false`）模拟线程**就是 MC 服务端主线程** ——
	 * 于是一次地图刷新能吃掉几百毫秒，等于几个 tick，64 人同时卡。
	 *
	 * 三条一起才成立：
	 *   ① 只读接口走快照（`mmtrWebFeed`）：常规刷新根本不进 tick；
	 *   ② 进 tick 的那些单独排队、**按时间片上上限**（下面这条预算）；
	 *   ③ tick 本来就已经很慢时，整 tick 不再接网页任务 —— 网页有快照兜底（读旧一拍），游戏没有。
	 */
	/** 一 tick 最多给网页任务留的时间片。 */
	private static final long WEB_RUN_BUDGET_NANOS = 2_000_000L;
	/** tick 已经用掉这么多毫秒时，本 tick 不再接网页任务（先把游戏让出来）。 */
	private static final long WEB_RUN_SKIP_TICK_MILLIS = 40L;
	/** 单条网页任务超过这个耗时就要点名（说明有重活又落回 tick 里了）。 */
	private static final long WEB_RUN_WARN_MILLIS = 25L;
	/** 同类点名日志的最小间隔，免得一个慢接口每拍刷一行。 */
	private static final long WEB_RUN_WARN_INTERVAL_MILLIS = 10_000L;
	/**
	 * 一条网页任务超过这个耗时之后，接下来 {@link #WEB_RUN_COOLDOWN_MILLIS} 之内不再接网页任务。
	 *
	 * <p>实测：地图页一拍要三路，其中两路各自要几十到两百毫秒（notes/172 的表）。它们会**连着几个 tick
	 * 各跑一个** —— 玩家看到的是"连续四五个 tick 全在卡"。冷却把它们摊开：一个重活之后先让游戏跑一会儿，
	 * 网页宁可多等半秒（读旧一份快照），也不要连着把 tick 占满。</p>
	 */
	private static final long WEB_RUN_COOLDOWN_TRIGGER_MILLIS = 20L;
	private static final long WEB_RUN_COOLDOWN_MILLIS = 400L;
	/** 快照清扫间隔（tick）：长时间没人看就把发布的那几份丢掉。 */
	private static final int WEB_FEED_SWEEP_TICKS = 1200;
	private static final long NANOS_PER_MILLISECOND = 1_000_000L;

	private final MessageQueue<WebRun> queuedWebRuns = new MessageQueue<>();
	private int webFeedSweepTickCounter;
	private long webRunCount;
	private long webRunLastMillis;
	private long webRunMaxMillis;
	private String webRunMaxLabel = "";
	private int webRunDeferred;
	private long webRunSkippedTicks;
	private long webRunShed;
	private long webRunCooldownUntilMillis;
	private long webRunWarnedAtMillis;

	/**
	 * Stable dimension identifier (e.g. {@code "minecraft/overworld"}).
	 */
	public final String dimension;
	/**
	 * Identifiers of every dimension hosted in the same process — used for cross-dimension routing.
	 */
	public final String[] dimensions;
	/**
	 * Background path-finder for passenger directions queries.
	 */
	public final DirectionsFinder directionsFinder = new DirectionsFinder(this);

	/**
	 * 只读接口的**快照发布器**（notes/172）：网页面板读的是这一份，所以"几个标签、每拍问几次"
	 * 不再等于"tick 里算几次"。由 {@code SystemMapServlet} 的 FEEDS 表决定哪些接口往里发。
	 */
	public final WebFeed mmtrWebFeed = new WebFeed();

	private final FileLoader<Station> fileLoaderStations;
	private final FileLoader<Platform> fileLoaderPlatforms;
	private final FileLoader<Siding> fileLoaderSidings;
	private final FileLoader<Route> fileLoaderRoutes;
	private final FileLoader<Depot> fileLoaderDepots;
	private final FileLoader<Lift> fileLoaderLifts;
	private final FileLoader<Rail> fileLoaderRails;
	private final FileLoader<Home> fileLoaderHomes;
	private final FileLoader<Landmark> fileLoaderLandmarks;
	private final FileLoader<Settings> fileLoaderSettings;
	private final Consumer<Settings> writeSettings;
	private final MessageQueue<Runnable> queuedRuns = new MessageQueue<>();
	private final ObjectImmutableList<ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>>> vehiclePositions;
	private final Object2LongOpenHashMap<UUID> ridingVehicleIds = new Object2LongOpenHashMap<>();
	private final MessageQueue<QueueObject> messageQueueC2S = new MessageQueue<>();
	private final MessageQueue<QueueObject> messageQueueS2C = new MessageQueue<>();
	private final LongOpenHashSet jammedRouteIds = new LongOpenHashSet();

	/**
	 * If the simulation falls more than this many milliseconds behind wall clock, log a notice and
	 * fast-forward in one-second slices until caught up. Picked at two minutes as a balance between
	 * "noisy log on a sluggish host" and "silent multi-hour drift".
	 */
	private static final int SIMULATION_DIFFERENCE_LOGGING_THRESHOLD = 120000;
	private static final int MAX_PASSENGER_DIRECTIONS_REQUESTS = 512;
	/**
	 * Default in-game day length in real-time milliseconds (20 in-game minutes).
	 */
	private static final long DEFAULT_GAME_MILLIS_PER_DAY = 20L * 60 * MILLIS_PER_SECOND;

	/**
	 * Load a dimension from disk and bring its in-memory graph up to a tickable state.
	 *
	 * @param dimension           identifier of the dimension being loaded
	 * @param dimensions          identifiers of every dimension hosted in the same process
	 * @param rootPath            root data directory; per-dimension state lives under {@code rootPath/<dimension>}
	 * @param threadedFileLoading if {@code true}, fan file reads out across a thread pool
	 */
	public Simulator(String dimension, String[] dimensions, Path rootPath, boolean threadedFileLoading) {
		this.dimension = dimension;
		this.dimensions = dimensions;
		mmtrDuties = new org.mtr.core.mmtr.duty.MmtrDutyRegistry(this);

		// Load data
		final Path savePath = rootPath.resolve(dimension);
		final ObjectLongImmutablePair<FileLoaderHolder> fileLoaderHolderAndDuration = Utilities.measureDuration(() -> {
			LegacyRailLoader.load(savePath, rails, threadedFileLoading);
			return new FileLoaderHolder(
				new FileLoader<>(stations, messagePackHelper -> new Station(messagePackHelper, this), savePath, "stations", threadedFileLoading),
				new FileLoader<>(platforms, messagePackHelper -> new Platform(messagePackHelper, this), savePath, "platforms", threadedFileLoading),
				new FileLoader<>(sidings, messagePackHelper -> new Siding(messagePackHelper, this), savePath, "sidings", threadedFileLoading),
				new FileLoader<>(routes, messagePackHelper -> new Route(messagePackHelper, this), savePath, "routes", threadedFileLoading),
				new FileLoader<>(depots, messagePackHelper -> new Depot(messagePackHelper, this), savePath, "depots", threadedFileLoading),
				new FileLoader<>(lifts, messagePackHelper -> new Lift(messagePackHelper, this), savePath, "lifts", threadedFileLoading),
				new FileLoader<>(rails, Rail::new, savePath, "rails", threadedFileLoading),
				new FileLoader<>(homes, messagePackHelper -> new Home(messagePackHelper, this), savePath, "homes", threadedFileLoading),
				new FileLoader<>(landmarks, messagePackHelper -> new Landmark(messagePackHelper, this), savePath, "landmarks", threadedFileLoading)
			);
		});
		fileLoaderStations = fileLoaderHolderAndDuration.left().fileLoaderStations;
		fileLoaderPlatforms = fileLoaderHolderAndDuration.left().fileLoaderPlatforms;
		fileLoaderSidings = fileLoaderHolderAndDuration.left().fileLoaderSidings;
		fileLoaderRoutes = fileLoaderHolderAndDuration.left().fileLoaderRoutes;
		fileLoaderDepots = fileLoaderHolderAndDuration.left().fileLoaderDepots;
		fileLoaderLifts = fileLoaderHolderAndDuration.left().fileLoaderLifts;
		fileLoaderRails = fileLoaderHolderAndDuration.left().fileLoaderRails;
		fileLoaderHomes = fileLoaderHolderAndDuration.left().fileLoaderHomes;
		fileLoaderLandmarks = fileLoaderHolderAndDuration.left().fileLoaderLandmarks;
		log.info("Data loading complete for {} in {} second(s)", dimension, (float) fileLoaderHolderAndDuration.rightLong() / MILLIS_PER_SECOND);
		System.out.println("[MMTR-DBG] loaded stations=" + stations.size() + " platforms=" + platforms.size() + " rails=" + rails.size()
			+ " sidings=" + sidings.size() + " depots=" + depots.size() + " routes=" + routes.size() + " lifts=" + lifts.size() + " for " + dimension);

		/*
		 * 性能探针接线（notes/337）。只做两件事：记住维度标签（汇总行要分得清是谁）、
		 * 没显式指定明细文件时给一个**存档之外**的默认落点。
		 *
		 * <p>落点为什么在存档之外：notes/335 的崩溃与省查都在存档里翻，而探针明细是**可再生**的
		 * 诊断产物，按工作区硬规矩（README「日志不要落在仓库里」）应该自己一处待着；
		 * 这里相对于引擎数据根（{@code <rootPath>}）放一份，路径在启动时的那行 [MMTR-PROBE] 里会打出来。</p>
		 */
		mmtrProbeLabel = dimension; // dimension 形如 minecraft/overworld，汇总行里足够分辨
		if (org.mtr.core.mmtr.probe.MmtrProbe.on() && org.mtr.core.mmtr.probe.MmtrProbe.getFile().isEmpty()) {
			org.mtr.core.mmtr.probe.MmtrProbe.setFile(rootPath.resolve("mmtr-perf-probe.txt").toString());
			System.out.println("[MMTR-PROBE] 探针已开（-Dmmtr.probe=true）；明细追加到 " + rootPath.resolve("mmtr-perf-probe.txt")
				+ "，每 " + org.mtr.core.mmtr.probe.MmtrProbe.getReportIntervalTicks() + " tick 一行汇总；运行中可用 `probe off` 关掉");
		}

		// MMTR: optional server-side consist-type policy at <root>/<dimension>/mmtr-consist-types.json
		final Path mmtrConfigPath = savePath.resolve("mmtr-consist-types.json");
		log.info("MMTR: dimension={}, savePath={}, consist config path={}", dimension, savePath, mmtrConfigPath);
		System.out.println("[MMTR-DBG] dimension=" + dimension + " savePath=" + savePath + " config=" + mmtrConfigPath);
		try {
			if (java.nio.file.Files.exists(mmtrConfigPath)) {
				mmtrConsistTypes = ConsistTypeRegistry.fromFile(mmtrConfigPath);
				mmtrDefaultConsistTypeId = mmtrConsistTypes.getDefaultId();
				if (mmtrDefaultConsistTypeId == null && !mmtrConsistTypes.all().isEmpty()) {
					mmtrDefaultConsistTypeId = mmtrConsistTypes.all().keySet().iterator().next();
				}
				log.info("MMTR consist-type policy loaded for {} (default={})", dimension, mmtrDefaultConsistTypeId);
			} else {
				log.info("MMTR: no consist-type policy at {} -> legacy driving behaviour", mmtrConfigPath);
			}
		} catch (Exception e) {
			log.warn("Failed to load MMTR consist-type policy for {}: {}", dimension, e.getMessage());
		}

		// MMTR: rolling-stock manifest (车辆生成表): read at restart, applied after the vehicle reset.
		mmtrManifestPath = savePath.resolve("mmtr-rolling-stock.json");
		/*
		 * 开机把两个运维标记清掉：它们是**上一轮**的意图，这一轮刚刚开始，没有任何人要求重启或停机。
		 *
		 * 必须清掉而不是"留着也没事"：留着的重启标记会让启动器在这一轮结束后以为"又要重启"，
		 * 于是一轮接一轮地转下去；留着的停机标记会让它在这一轮结束后直接退出。
		 * 两处都清理是刻意的冗余（启动器也清一次）——因为"标记残留"的代价是服务端莫名重启或莫名不启动，
		 * 而这两种症状都极难从现象反推原因。
		 */
		mmtrClearRestartMarker();
		try {
			java.nio.file.Files.deleteIfExists(mmtrStopMarker());
		} catch (Exception e) {
			log.warn("Failed to clear MMTR stop marker for {}: {}", dimension, e.getMessage());
		}
		try {
			if (java.nio.file.Files.exists(mmtrManifestPath)) {
				mmtrRollingStock = org.mtr.core.mmtr.manifest.MmtrRollingStockManifest.fromFile(mmtrManifestPath);
				log.info("MMTR: loaded rolling-stock manifest for {} ({} depot(s), {} siding(s))", dimension, mmtrRollingStock.depots.size(), mmtrRollingStock.sidingEntryCount());
			}
		} catch (Exception e) {
			log.warn("Failed to load MMTR rolling-stock manifest for {}: {}", dimension, e.getMessage());
		}

		// MMTR: operator turnout (道岔) branch states.
		mmtrPointsPath = savePath.resolve("mmtr-points.json");
		mmtrPointBranches = org.mtr.core.mmtr.point.MmtrPointRegistry.loadBranches(mmtrPointsPath);
		// 人工锁（人工搬岔 = 覆盖 + 锁定）跟着存档一起回来，这样重启不会静默解锁。
		for (final String lockKey : org.mtr.core.mmtr.point.MmtrPointRegistry.loadLocks(mmtrPointsPath)) {
			mmtrPointAuthority.restoreLock(lockKey);
		}

		// MMTR: authoritative junction leg tables (进向表) - human/tool authored continuations per
		// (node, via rail). They override geometric auto-detection wherever they exist.
		mmtrJunctionLegsPath = savePath.resolve("mmtr-junction-legs.json");
		final org.mtr.core.mmtr.point.MmtrJunctionLegsRegistry.LegsStore loadedLegs = org.mtr.core.mmtr.point.MmtrJunctionLegsRegistry.load(mmtrJunctionLegsPath);
		loadedLegs.legs.forEach(mmtrJunctionLegs.legs::put);

		// MMTR: wayside signal registry (信号机登记表) - placed signal lights + covered binds.
		mmtrSignalsPath = savePath.resolve("mmtr-signals.json");
		final org.mtr.core.mmtr.signal.MmtrSignalRegistry loadedSignals = org.mtr.core.mmtr.signal.MmtrSignalRegistry.load(mmtrSignalsPath);
		loadedSignals.signals.forEach(mmtrSignals.signals::put);

		// MMTR: web-authored consist jobs (replaces the depot timetable for mmtr-managed stock).
		mmtrJobsPath = savePath.resolve("mmtr-jobs.json");
		try {
			if (java.nio.file.Files.exists(mmtrJobsPath)) {
				mmtrJobRegistry = org.mtr.core.mmtr.job.MmtrJobRegistry.fromFile(mmtrJobsPath);
			}
		} catch (Exception e) {
			log.warn("Failed to load MMTR consist jobs for {}: {}", dimension, e.getMessage());
		}

		// MMTR: named consist templates (编组代码) for job authoring.
		final java.nio.file.Path mmtrTemplatePath = savePath.resolve("mmtr-consist-templates.json");
		try {
			if (java.nio.file.Files.exists(mmtrTemplatePath)) {
				mmtrConsistTemplates = org.mtr.core.mmtr.job.MmtrConsistTemplateRegistry.fromFile(mmtrTemplatePath);
				log.info("MMTR: loaded {} consist template(s) for {}", mmtrConsistTemplates.templates.size(), dimension);
			}
		} catch (Exception e) {
			log.warn("Failed to load MMTR consist templates for {}: {}", dimension, e.getMessage());
		}

		// MMTR P1: 时刻表生成器的输入层（线路 / 分段密度 / 车底）。**持久化的是输入，计划是算出来的**；
		// 输入不对就在这里报（设计 §4.3：别等到高峰才发现车底不够跑）。
		mmtrPlanPath = savePath.resolve("mmtr-plan.json");
		try {
			if (java.nio.file.Files.exists(mmtrPlanPath)) {
				mmtrPlanInputs = org.mtr.core.mmtr.plan.MmtrPlanInputs.fromFile(mmtrPlanPath);
			}
		} catch (Exception e) {
			log.warn("Failed to load MMTR plan inputs for {}: {}", dimension, e.getMessage());
		}
		refreshMmtrPlanErrors();
		if (mmtrPlanInputs.isEmpty()) {
			// 一条都没配 = 这台服务器还没用时刻表（与"配了但有错"分开，见 MmtrPlanInputs#isEmpty）。
			System.out.println("[MMTR-PLAN] 未配置时刻表输入（线路/密度/车底都是空的）—— 生成器未启用");
		} else if (!mmtrPlanErrors.isEmpty()) {
			/*
			 * **加载即报错**（设计 §4.3）必须看得见：引擎自己的 log.info/log.error 不进服务端控制台，
			 * 而操作者看的就是服务端日志。所以问题逐行走 System.out（与 [MMTR-PT]/[MMTR-SIG] 同一套路），
			 * log.error 再留一份给日志文件。
			 */
			System.out.println("[MMTR-PLAN] 计划输入有 " + mmtrPlanErrors.size() + " 处问题，派发器在修好之前不排班：");
			for (final String error : mmtrPlanErrors) {
				System.out.println("[MMTR-PLAN]   - " + error);
				log.error("MMTR plan input problem for {}: {}", dimension, error);
			}
		} else {
			System.out.println("[MMTR-PLAN] " + mmtrPlanInputs.describe());
		}

		if (!mmtrJobRegistry.jobs.isEmpty()) {
			expandMmtrJobTemplates();
			mmtrJobsMode = true; // jobs present => web orchestration owns the traffic
			mmtrAiJobStepsEnabled = true; // scheduler ticks when consist jobs are loaded
			mmtrJobScheduler = org.mtr.core.mmtr.job.MmtrJobScheduler.create(mmtrJobRegistry.jobs);
			log.info("MMTR: loaded {} consist job(s) for {}", mmtrJobRegistry.jobs.size(), dimension);
		}

		// Initialize cache
		sync();
		depots.forEach(Depot::init);
		rails.forEach(Rail::checkMigrationStatus);

		final ObjectArrayList<ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>>> tempVehiclePositions = new ObjectArrayList<>();
		for (int i = 0; i < TransportMode.values().length; i++) {
			final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositionsForTransportMode = new ObjectArrayList<>();
			vehiclePositionsForTransportMode.add(new Object2ObjectAVLTreeMap<>());
			vehiclePositionsForTransportMode.add(new Object2ObjectAVLTreeMap<>());
			tempVehiclePositions.add(vehiclePositionsForTransportMode);
		}
		vehiclePositions = new ObjectImmutableList<>(tempVehiclePositions);
		sidings.forEach(siding -> siding.initVehiclePositions(vehiclePositions.get(siding.getTransportModeOrdinal()).get(1)));
		homes.forEach(home -> home.iteratePassengers(passenger -> passenger.writeVehicleCache(this)));

		// Load settings
		final ObjectArraySet<Settings> settings = new ObjectArraySet<>();
		fileLoaderSettings = new FileLoader<>(settings, Settings::new, savePath, "settings", threadedFileLoading);
		writeSettings = newSettings -> {
			settings.clear();
			settings.add(newSettings);
		};

		// Set the last simulated millis
		setCurrentMillis(Utilities.getElement(new ObjectArrayList<>(settings), 0, new Settings(0)).getLastSimulationMillis());

		// MMTR: explicit rolling-stock reset on restart - clear all trains, then generate per manifest.
		if (!mmtrRollingStock.isEmpty()) {
			mmtrResetAndApplyRollingStock();
		}
	}

	/**
	 * Catch the simulation up to wall clock and log a notice if it had drifted by more than
	 * {@link #SIMULATION_DIFFERENCE_LOGGING_THRESHOLD} milliseconds. If the drift exceeds an hour
	 * the simulator jumps to "one hour ago" instead of replaying the full gap, since replaying
	 * many hours of vehicle motion is rarely useful and is expensive.
	 */
	public void tick() {
		final long totalDifference = System.currentTimeMillis() - getCurrentMillis();
		if (totalDifference >= SIMULATION_DIFFERENCE_LOGGING_THRESHOLD) {
			if (totalDifference > MILLIS_PER_HOUR) {
				// If the simulation is over an hour behind, jump to one hour ago and simulate the last hour
				setCurrentMillis(System.currentTimeMillis() - MILLIS_PER_HOUR);
				sidings.forEach(Siding::clearVehicles);
			}
			final ObjectLongImmutablePair<Integer> ticksAndDuration = Utilities.measureDuration(this::tickUntilCaughtUp);
			log.info(
				"Simulation difference of {}h{}m for {} caught up with {} ticks in {} second(s)",
				totalDifference / MILLIS_PER_SECOND / (MILLIS_PER_HOUR / MILLIS_PER_SECOND), (totalDifference / MILLIS_PER_SECOND / (MILLIS_PER_MINUTE / MILLIS_PER_SECOND)) % (MILLIS_PER_MINUTE / MILLIS_PER_SECOND),
				dimension,
				ticksAndDuration.left(),
				(float) ticksAndDuration.rightLong() / MILLIS_PER_SECOND
			);
		} else {
			tickUntilCaughtUp();
		}
	}

	/**
	 * Schedule a full save on the next tick. Returns immediately.
	 */
	/**
	 * MMTR: upsert a consist job from the web editor; persists it and rebuilds the scheduler.
	 */
	public void upsertMmtrJob(org.mtr.core.mmtr.job.MmtrConsistJob job) {
		mmtrJobRegistry.put(job);
		persistMmtrJobs();
	}

	/**
	 * MMTR: delete a consist job by id; persists and rebuilds the scheduler.
	 * @return whether a job was removed
	 */
	public boolean deleteMmtrJob(String jobId) {
		final boolean removed = mmtrJobRegistry.remove(jobId);
		if (removed) {
			persistMmtrJobs();
		}
		return removed;
	}

	public org.mtr.core.mmtr.job.MmtrJobRegistry getMmtrJobRegistry() {
		return mmtrJobRegistry;
	}

	/**
	 * P1：重新算一遍计划输入的问题清单（加载、每次 upsert 都跑）。
	 *
	 * @return 问题条数（0 = 通过）
	 */
	public int refreshMmtrPlanErrors() {
		mmtrPlanErrors.clear();
		if (mmtrPlanInputs.isEmpty()) {
			return 0;   // 一条都没配 = 没启用，不是"配置有错"（见 MmtrPlanInputs#isEmpty）
		}
		mmtrPlanErrors.addAll(mmtrPlanInputs.validate());
		return mmtrPlanErrors.size();
	}

	/** P1：计划输入（线路 / 分段密度 / 车底）。 */
	public org.mtr.core.mmtr.plan.MmtrPlanInputs getMmtrPlanInputs() {
		return mmtrPlanInputs;
	}

	/**
	 * P1：把一条线路写进输入层并落盘（upsert）。落盘前重算问题清单 ——
	 * **坏配置写进去就报**，而不是让它在内存里悄悄生效（P1 验收 ②）。
	 */
	public void upsertMmtrLine(org.mtr.core.mmtr.plan.MmtrLine line) {
		mmtrPlanInputs.putLine(line);
		persistMmtrPlanInputs();
	}

	/** P1：写一张密度表（同 lineId 覆盖）并落盘。 */
	public void upsertMmtrPattern(org.mtr.core.mmtr.plan.MmtrPattern pattern) {
		mmtrPlanInputs.putPattern(pattern);
		persistMmtrPlanInputs();
	}

	/** P1：写车底、落盘。 */
	public void upsertMmtrFleet(org.mtr.core.mmtr.plan.MmtrFleet fleet) {
		mmtrPlanInputs.fleet = fleet;
		persistMmtrPlanInputs();
	}

	/** P1：删一条线路（连带它的密度表）并落盘。 */
	public boolean deleteMmtrLine(String lineId) {
		final boolean removed = mmtrPlanInputs.removeLine(lineId);
		if (removed) {
			persistMmtrPlanInputs();
		}
		return removed;
	}

	/** P1：落盘 + 重算问题清单（返回问题条数）。 */
	public int persistMmtrPlanInputs() {
		if (mmtrPlanPath != null) {
			mmtrPlanInputs.save(mmtrPlanPath);
		}
		final int problems = refreshMmtrPlanErrors();
		if (problems > 0) {
			System.out.println("[MMTR-PLAN] 计划输入有 " + problems + " 处问题（详见启动日志/接口的 errors）");
		}
		mmtrPlanSignature = "";   // 输入变了 → 下次 tick 重建派发器
		return problems;
	}

	/**
	 * P4：**重建每条线路的派发器**（趟次表 + 交路 + 任务序列）。
	 *
	 * <p>只在输入签名变化时重建：交路是"一整天几百步"的东西，每 tick 重排既浪费又会把
	 * "派到第几步"的状态冲掉（那就是重复派发）。</p>
	 *
	 * @return 重建了几条线路（0 = 没变，不用重建）
	 */
	public int mmtrRefreshPlanDispatchers() {
		final String signature = mmtrPlanSignature();
		if (signature.equals(mmtrPlanSignature)) {
			return 0;
		}
		mmtrPlanSignature = signature;
		if (!mmtrPlanErrors.isEmpty()) {
			/*
			 * 输入有错：**不排班**（设计 §4.3：别让坏配置跑起来），旧班也不留 ——
			 * 否则"配置改坏了"之后旧交路还在跑，看起来像新配置没生效。
			 *
			 * 但旧班**派出去的车要把任务收回来**（notes/149）：清掉派发器只清掉"记忆"，
			 * 世界那台车还在开 —— 现场就是被两台这样的幽灵车堵住车场咽喉的。
			 */
			mmtrReleasePlanMissions(mmtrPlanDispatchers.values());
			mmtrPlanDispatchers.clear();
			return 0;
		}
		int built = 0;
		final long dayTime = mmtrPlanDayTime();
		/*
		 * P5 的冻结边界：**必须在动派发器之前取快照**（notes/147）。
		 *
		 * 第一版是"先 clear 再遍历取 frozen"，于是那张表永远是空的 —— "在途车的当前任务不被重算改动"
		 * 这条验收等于没生效，而代码看起来是接好的。现在改成"先取快照 → 建到局部表 rebuilt →
		 * 最后整体换掉"，顺序不再依赖读代码时的注意力。
		 */
		final java.util.Map<String, Long> frozen = org.mtr.core.mmtr.plan.MmtrPlanAdjustments.frozenSnapshot(mmtrPlanDispatchers.values());
		final java.util.HashMap<String, org.mtr.core.mmtr.plan.MmtrPlanDispatcher> rebuilt = new java.util.HashMap<>();
		for (final org.mtr.core.mmtr.plan.MmtrLine line : mmtrPlanInputs.lines) {
			final org.mtr.core.mmtr.plan.MmtrPattern pattern = mmtrPlanInputs.pattern(line.lineId);
			if (pattern == null) {
				continue;
			}
			final double speedKmh = mmtrPlanInputs.fleet.consists.isEmpty() ? 0 : mmtrPlanInputs.fleet.consists.get(0).maxSpeedKmh;
			final org.mtr.core.mmtr.plan.MmtrTravelTimes times = org.mtr.core.mmtr.plan.MmtrRailTravelTimes.of(this, line, speedKmh);
			/*
			 * P5：**按事件重算**（不是直接生成）—— 于是"正常态"与"有事件"走的是同一条路：
			 * 没有任何事件时，重算的结果与直接生成逐字段相同（这条本身就该是验收的一部分）。
			 */
			final org.mtr.core.mmtr.plan.MmtrPlanAdjustments.Result result = org.mtr.core.mmtr.plan.MmtrPlanAdjustments.recompute(
				line, pattern, mmtrPlanInputs.fleet, times, mmtrPlanEvents.all(), dayTime, frozen);
			org.mtr.core.mmtr.plan.MmtrDiagram diagram = result.diagram;
			/*
			 * P6 ④：**手工指派优先于自动排班** —— 它排在事件重算之后，所以"人说了算"是最后一层；
			 * 每搬一条都带说明（看得见），失败也留一句（不静默）。
			 */
			for (final String[] assignment : mmtrPlanManualAssignments) {
				diagram = org.mtr.core.mmtr.plan.MmtrPlanAdjustments.assignManually(
					diagram, assignment[0], assignment[1], assignment[2], result.notes);
			}
			rebuilt.put(line.lineId, new org.mtr.core.mmtr.plan.MmtrPlanDispatcher(line, diagram));
			// P6 ③：重建之后把"玩家正在开的编组"重新贴上去（接管是运行时状态，不属于计划输入）
			mmtrPlanPlayerDriven.forEach(consistId -> rebuilt.get(line.lineId).setPlayerDriven(consistId, true));
			/*
			 * **换代交接**（notes/149）：把上一代"谁在跑、跑到第几步、正在等哪一步"接到这一代上。
			 *
			 * 不交接的后果是现场实测出来的：新派发器不认识世界里那台"上一代派出去的车"，
			 * 于是它去牵另一台；被忘掉的那台永远停在原地并按 FIFO 继续占着道岔 ——
			 * 两台幽灵车就把车场咽喉堵死，六台车一步都出不去（重建发生在每次改配置/事件重算时）。
			 */
			final org.mtr.core.mmtr.plan.MmtrPlanDispatcher previous = mmtrPlanDispatchers.get(line.lineId);
			// 出库闸门也要接手（notes/155 §17）：重建后闸门从 0 开始 ⇒ 几台车同时出库、在咽喉里互锁
			rebuilt.get(line.lineId).adoptYardDepartureGate(previous);
			final it.unimi.dsi.fastutil.objects.ObjectArrayList<Long> orphans = rebuilt.get(line.lineId).adoptFrom(previous,
				new org.mtr.core.mmtr.plan.MmtrPlanDispatcher.InFlightCheck() {
					@Override
					public boolean vehicleExists(long vehicleId) {
						return mmtrFindVehicle(vehicleId) != null;
					}

					@Override
					public boolean isStillRunning(long vehicleId, String taskId) {
						/*
						 * notes/153：这里问的是"**车上还挂着这一步吗**"，不是"这一步还在跑吗"。
						 *
						 * 差别就是现场那台车：它开到了站台（这一步的移动部分做完了），任务状态已经不是"在跑"，
						 * 于是交接不认它 ⇒ 任务被当成"没人认领"收掉 ⇒ 车空着停在站外，
						 * 而派发器只在车场股道上找车（找不到站外的它）⇒ 这条交路再也分不到车，
						 * 它还杵在咽喉口把后面的车挡在区间外。
						 * 到站只是"这一步跑完了"，任务仍在车上，派发器下一步正要处理它 —— 那就还是它的活。
						 */
						final org.mtr.core.data.Vehicle vehicle = mmtrFindVehicle(vehicleId);
						final org.mtr.core.mmtr.MmtrMission mission = vehicle == null ? null : vehicle.getMmtrMission();
						final org.mtr.core.mmtr.task.MmtrTask task = mission == null ? null : mission.getTask();
						return task != null && task.taskId.equals(taskId);
					}
				});
			for (final long orphan : orphans) {
				final org.mtr.core.data.Vehicle vehicle = mmtrFindVehicle(orphan);
				final org.mtr.core.mmtr.MmtrMission mission = vehicle == null ? null : vehicle.getMmtrMission();
				final String taskId = mission == null || mission.getTask() == null ? "" : mission.getTask().taskId;
				mmtrWithdrawPlanTask(orphan, taskId, "计划里已经没有这一步了");
			}
			built++;
			System.out.println("[MMTR-PLAN] " + diagram + "（走行时间按轨图算）");
			for (final String note : result.notes) {
				System.out.println("[MMTR-PLAN] 事件改计划：" + note);
			}
		}
		/*
		 * 整条线路没了的那些（删线路 / 车底清空 / 输入坏掉）：它派出去的车必须**收回任务**。
		 * 不然这些车会开着一个已经不存在的班走到天涯海角 —— 现场就是被两台这样的车堵住咽喉的。
		 */
		for (final java.util.Map.Entry<String, org.mtr.core.mmtr.plan.MmtrPlanDispatcher> entry : mmtrPlanDispatchers.entrySet()) {
			if (!rebuilt.containsKey(entry.getKey())) {
				mmtrReleasePlanMissions(java.util.List.of(entry.getValue()));
			}
		}
		mmtrPlanDispatchers.clear();
		mmtrPlanDispatchers.putAll(rebuilt);
		mmtrReleaseUnclaimedPlanTasks(rebuilt);
		return built;
	}

	/**
	 * **扫一遍世界**：凡是跑着"这套计划的任务"、但新一代没有认领的车，把任务收回来。
	 *
	 * <p>为什么要有这一遍（notes/149）：换代交接只能接住"上一代记得的那些车"。世界里的任务还有别的来源 ——
	 * 上一次服务端会话留下的（任务是随车落盘的）、车场重建之后换了车列的、以及"计划里已经把这条线删了"
	 * 的。这些车会一直开下去并按 FIFO 占着道岔，把车场咽喉堵死；而派发器这边的账上**什么都没有**，
	 * 从界面上看不出问题在哪。判据：任务 id 形如 {@code 线路/编组/序号}，线路必须是"这一版计划里的线路"，
	 * 且 (车, 任务) 这一对没有被新一代的任何状态认领。作业单（job）的任务不满足这个形状，不会被碰。</p>
	 *
	 * @return 收回了几台车
	 */
	private int mmtrReleaseUnclaimedPlanTasks(java.util.Map<String, org.mtr.core.mmtr.plan.MmtrPlanDispatcher> rebuilt) {
		final java.util.HashSet<String> claimed = new java.util.HashSet<>();
		for (final org.mtr.core.mmtr.plan.MmtrPlanDispatcher dispatcher : rebuilt.values()) {
			for (final org.mtr.core.mmtr.plan.MmtrPlanDispatcher.WorkingState state : dispatcher.states) {
				if (state.vehicleId != 0 && !state.awaitingTaskId.isEmpty()) {
					claimed.add(state.vehicleId + "|" + state.awaitingTaskId);
				}
			}
		}
		final int[] released = {0};
		final org.mtr.core.mmtr.plan.MmtrPlanDispatcher.World world = new MmtrPlanWorld();
		sidings.forEach(siding -> siding.iterateVehicles(vehicle -> {
			final org.mtr.core.mmtr.MmtrMission mission = vehicle.getMmtrMission();
			final org.mtr.core.mmtr.task.MmtrTask task = mission == null ? null : mission.getTask();
			final String taskId = task == null ? "" : task.taskId;
			final int slash = taskId.indexOf('/');
			if (slash <= 0) {
				return;   // 不是"线路/编组/序号"这个形状：作业单/别的来源，不碰
			}
			final String lineId = taskId.substring(0, slash);
			if (!rebuilt.containsKey(lineId) || claimed.contains(vehicle.getId() + "|" + taskId)) {
				return;
			}
			final int before = mmtrPendingPlanReleases.size();
			mmtrWithdrawPlanTask(vehicle.getId(), taskId, "这一版计划没有认领它（" + lineId + " 已不在/已重排）");
			if (mmtrPendingPlanReleases.size() == before) {
				released[0]++;
			}
		}));
		return released[0];
	}

	/**
	 * **收回一个计划任务**（notes/153）：车还在动就先记账、等它停稳再收。
	 *
	 * <p>为什么不能当场收：计划一变（改密度 / 事件重算 / 手工指派），某台车正在跑的那一步可能
	 * 已经不在新计划里了 —— 该收。但它可能正开在咽喉里：当场撤活，车就停在那儿，把后面的车全挡住
	 * （现场实测：一台被撤活的车停在咽喉区间里，后面那台开到站台前被它挡在区间外）。
	 * 与引擎既有的"任务终态才收回"是同一条原则 —— 只是把"终态"换成了"停稳"。</p>
	 */
	private void mmtrWithdrawPlanTask(long vehicleId, String taskId, String why) {
		if (taskId == null || taskId.isEmpty()) {
			return;
		}
		final org.mtr.core.data.Vehicle vehicle = mmtrFindVehicle(vehicleId);
		if (vehicle != null && vehicle.isMoving()) {
			if (mmtrPendingPlanReleases.defer(String.valueOf(vehicleId), taskId, getCurrentMillis())) {
				System.out.println("[MMTR-PLAN] 暂缓收回任务：" + why + " —— 车 " + vehicleId
					+ " 正在走这一步（等它停稳再收，见 notes/153）");
			}
			return;
		}
		if (new MmtrPlanWorld().releaseTask(vehicleId, taskId)) {
			System.out.println("[MMTR-PLAN] 收回任务：" + why + " → 车 " + vehicleId + "（" + taskId + "）");
		}
		mmtrPendingPlanReleases.forget(String.valueOf(vehicleId), taskId);
	}

	/** 每次 tick 问一遍账上那些车：停稳了就把任务收掉（返回收回几台）。 */
	private int mmtrFlushPendingPlanReleases() {
		if (mmtrPendingPlanReleases.size() == 0) {
			return 0;
		}
		final org.mtr.core.mmtr.plan.MmtrPlanDispatcher.World world = new MmtrPlanWorld();
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<String[]> ready = mmtrPendingPlanReleases.claimReleasable(vehicleId -> {
			final org.mtr.core.data.Vehicle vehicle = mmtrFindVehicle(Long.parseLong(vehicleId));
			return vehicle != null && vehicle.isMoving();
		});
		int released = 0;
		for (final String[] entry : ready) {
			final long vehicleId = Long.parseLong(entry[0]);
			final org.mtr.core.data.Vehicle vehicle = mmtrFindVehicle(vehicleId);
			final org.mtr.core.mmtr.MmtrMission mission = vehicle == null ? null : vehicle.getMmtrMission();
			final String running = mission == null || mission.getTask() == null ? "" : mission.getTask().taskId;
			if (running.equals(entry[1]) && world.releaseTask(vehicleId, entry[1])) {
				System.out.println("[MMTR-PLAN] 收回任务（已停稳）：车 " + vehicleId + "（" + entry[1] + "）");
				released++;
			}
		}
		return released;
	}
	/**
	 * 把某一代派发器派出去、但计划里已经不打算继续跑的步**收回来**。
	 *
	 * <p>只碰"确实是这套计划派出去的"那些任务（任务 id 前缀 {@code 线路/编组/}）：作业单（job）、
	 * 玩家自己开的车都不动 —— 判据窄一点，收错车的代价比漏收大得多。</p>
	 *
	 * @return 收回了几台车
	 */
	private int mmtrReleasePlanMissions(java.util.Collection<org.mtr.core.mmtr.plan.MmtrPlanDispatcher> dispatchers) {
		int released = 0;
		final org.mtr.core.mmtr.plan.MmtrPlanDispatcher.World world = new MmtrPlanWorld();
		for (final org.mtr.core.mmtr.plan.MmtrPlanDispatcher dispatcher : dispatchers) {
			for (final org.mtr.core.mmtr.plan.MmtrPlanDispatcher.WorkingState state : dispatcher.states) {
				if (state.vehicleId == 0) {
					continue;
				}
				final org.mtr.core.data.Vehicle vehicle = mmtrFindVehicle(state.vehicleId);
				final org.mtr.core.mmtr.MmtrMission mission = vehicle == null ? null : vehicle.getMmtrMission();
				final String taskId = mission == null || mission.getTask() == null ? "" : mission.getTask().taskId;
				if (taskId.startsWith(dispatcher.lineId + "/" + state.consistId + "/")) {
					mmtrWithdrawPlanTask(state.vehicleId, taskId, dispatcher.lineId + " 不再排这条交路");
					released++;
				}
			}
		}
		return released;
	}

	/**
	 * 网页上的"重新排班"（设计 §9 的 {@code replan}）：**不管输入变没变，强算一次**。
	 *
	 * <p>平时重建由签名把关（输入/事件/指派没变就不重算，省得无谓地打断在途车）；但操作者按下
	 * "重新排班"时想要的是"就按现在这份配置再排一遍" —— 把签名清掉再走同一条路。</p>
	 *
	 * @return 重排了几条线路
	 */
	public int mmtrForceReplan() {
		mmtrPlanSignature = "";
		return mmtrRefreshPlanDispatchers();
	}

	/** P5：现在几点（当日毫秒，与线路密度表同一口径；锚点优先用作业单调度的）。 */
	public long mmtrPlanDayTime() {
		final long schedulerAnchor = mmtrJobScheduler == null ? Long.MIN_VALUE : mmtrJobScheduler.getAnchor();
		final long anchor = schedulerAnchor != Long.MIN_VALUE ? schedulerAnchor : mmtrPlanAnchorForDay();
		return Math.floorMod(getCurrentMillis() - anchor, 86_400_000L);
	}

	/** 输入签名：线路/密度/车底任一改动都会变（用于"变了才重排"）。 */
	private String mmtrPlanSignature() {
		final StringBuilder sig = new StringBuilder();
		for (final org.mtr.core.mmtr.plan.MmtrLine line : mmtrPlanInputs.lines) {
			sig.append(line.lineId).append(':').append(line.stops.size()).append(':').append(line.yardSidingId)
				.append(':').append(line.leadTimeMillis).append(':').append(line.loop).append('|');
		}
		for (final org.mtr.core.mmtr.plan.MmtrPattern pattern : mmtrPlanInputs.patterns) {
			sig.append(pattern.lineId);
			for (final org.mtr.core.mmtr.plan.MmtrPattern.Segment segment : pattern.segments) {
				sig.append('.').append(segment.fromMillis).append('-').append(segment.toMillis).append('-').append(segment.headwayMillis);
			}
			sig.append('|');
		}
		sig.append(mmtrPlanInputs.fleet.consists.size()).append('+').append(mmtrPlanInputs.fleet.spares.size());
		sig.append('#').append(rails.size());
		// P6：手工指派也要进签名（否则"指派了却没生效"）
		for (final String[] assignment : mmtrPlanManualAssignments) {
			sig.append('!').append(assignment[0]).append('>').append(assignment[1]).append('>').append(assignment[2]);
		}
		// P5：事件也要进签名 —— 否则"加了事件"不会触发重算（那是"计划看着没动"的经典原因）
		for (final org.mtr.core.mmtr.plan.MmtrEvent event : mmtrPlanEvents.all()) {
			sig.append('@').append(event.eventId).append(':').append(event.kind()).append(':')
				.append(event.startMillis).append('-').append(event.endMillis).append(':')
				.append(event.targetText).append(':').append(event.severity);
			if (event instanceof final org.mtr.core.mmtr.plan.MmtrEvent.PeakSurge surge) {
				sig.append(':').append(surge.headwayMillis);
			}
			if (event instanceof final org.mtr.core.mmtr.plan.MmtrEvent.Delay delay) {
				sig.append(':').append(delay.delayMillis).append(':').append(delay.strategy);
			}
		}
		return sig.toString();
	}

	/**
	 * P4：走一个 tick 的派发（到点了把下一步挂到具体车列上）。
	 *
	 * <p>与旧的 {@code MmtrPeriodicTaskSource} 同一位置被调用，但语义完全不同：那个是"固定周期挑一辆
	 * 空闲车、目标写 0"；这里是"按交路一步一步派，跨 tick 不重复、拿不到车就原地重试"。</p>
	 */
	public void mmtrTickPlanDispatchers() {
		mmtrRefreshPlanDispatchers();
		mmtrFlushPendingPlanReleases();
		if (mmtrPlanDispatchers.isEmpty()) {
			return;
		}
		final org.mtr.core.mmtr.plan.MmtrPlanDispatcher.World world = new MmtrPlanWorld();
		final long now = getCurrentMillis();
		/*
		 * **当日毫秒**（与线路密度表同一口径）：计划里的 07:00 是 25_200_000，不是纪元毫秒。
		 * 锚点优先用作业单调度器的（两套编排的小时数必须是同一个意思），它还没有锚点时用我们自己的。
		 */
		final long schedulerAnchor = mmtrJobScheduler == null ? Long.MIN_VALUE : mmtrJobScheduler.getAnchor();
		final long anchor = schedulerAnchor != Long.MIN_VALUE ? schedulerAnchor : mmtrPlanAnchorForDay();
		final long dayTime = Math.floorMod(now - anchor, 86_400_000L);
		/*
		 * 日钟基准变了要说一声（notes/147 的现场发现）。
		 *
		 * 锚点优先用作业单调度器的：它**晚一步**才发布锚点时（服务端刚起、作业单还没 tick），
		 * 计划会先用自己定的锚点算 "现在"，等作业单的锚点一到，"现在"就整体平移 ——
		 * 现场实测 00:10 → 00:02（平移了 7.5 分钟）。时刻表本身没变（"07:00 发车"还是 07:00），
		 * 但操作者会看到"车怎么还没动、明明过点了"，所以这里必须留一句话。
		 */
		if (mmtrPlanLastAnchor != Long.MIN_VALUE && mmtrPlanLastAnchor != anchor) {
			System.out.println("[MMTR-PLAN] 日钟基准变了：现在从 " + hhmmText(Math.floorMod(now - mmtrPlanLastAnchor, 86_400_000L))
				+ " 变成 " + hhmmText(dayTime) + "（作业单调度器的锚点接管了）—— 时刻表不变，但「到点没有」跟着变");
		}
		mmtrPlanLastAnchor = anchor;
		mmtrPlanDispatchers.values().forEach(dispatcher -> dispatcher.tick(dayTime, world));
	}

	/** 日志里的 {@code hh:mm:ss}（当日毫秒 → 人看的时间）。 */
	private static String hhmmText(long dayTimeMillis) {
		final long seconds = Math.floorDiv(dayTimeMillis, 1000);
		return String.format("%02d:%02d:%02d", Math.floorDiv(seconds, 3600), Math.floorMod(Math.floorDiv(seconds, 60), 60), Math.floorMod(seconds, 60));
	}

	/** 计划一变就"该收回、但车还在动"的那些任务（notes/153：等它停稳再收，别把车撂在咽喉里）。 */
	private final org.mtr.core.mmtr.plan.MmtrPendingPlanReleases mmtrPendingPlanReleases = new org.mtr.core.mmtr.plan.MmtrPendingPlanReleases();

	/** 上一次用的日钟基准（只在日志里用，见 {@link #mmtrTickPlanDispatchers()}）。 */
	private long mmtrPlanLastAnchor = Long.MIN_VALUE;

	/** 计划派发器自己的"天"锚点（第一次 tick 时定下，与作业单调度器同一套口径）。 */
	private long mmtrPlanAnchor = Long.MIN_VALUE;

	private long mmtrPlanAnchorForDay() {
		if (mmtrPlanAnchor == Long.MIN_VALUE) {
			mmtrPlanAnchor = getCurrentMillis();
		}
		return mmtrPlanAnchor;
	}

	/**
	 * P4 的**世界适配层**：派发器只问三件事（谁空着、它还空着吗、挂这一步）。
	 *
	 * <p>分车按"这条线路车场里的空闲车列"来 —— 车底代码（{@code C1/S1}）是配置里的名字，
	 * 而真正跑的是世界里那几列车；两者的对应留给 P6 的接管/替补协议（那时要按编组代码精确认车）。</p>
	 */
	private final class MmtrPlanWorld implements org.mtr.core.mmtr.plan.MmtrPlanDispatcher.World {

		@Override
		public long[] idleVehiclesForYard(long yardSidingId) {
			final it.unimi.dsi.fastutil.longs.LongArrayList out = new it.unimi.dsi.fastutil.longs.LongArrayList();
			/*
			 * 这条股道所在车辆段的**全部股道**都算"这个车场"：出库车不一定停在出库股道那条线上
			 * （真实车场就是几条存车线共用一个咽喉）。找不到股道时退化成全部股道（配置还没对齐时
			 * 不至于一步都派不出去）。
			 */
			final java.util.List<org.mtr.core.data.Siding> candidates = new java.util.ArrayList<>();
			sidings.forEach(siding -> {
				if (yardSidingId == 0 || siding.getId() == yardSidingId || siding.area != null && containsSiding(siding.area, yardSidingId)) {
					candidates.add(siding);
				}
			});
			if (candidates.isEmpty()) {
				sidings.forEach(candidates::add);
			}
			for (final org.mtr.core.data.Siding siding : candidates) {
				siding.iterateVehicles(vehicle -> {
					if (isVehicleIdle(vehicle.getId()) && !out.contains(vehicle.getId())) {
						out.add(vehicle.getId());
					}
				});
			}
			final long[] sorted = out.toLongArray();
			java.util.Arrays.sort(sorted);
			return sorted;
		}

		@Override
		public boolean isVehicleIdle(long vehicleId) {
			final Vehicle vehicle = mmtrFindVehicle(vehicleId);
			if (vehicle == null || !vehicle.vehicleExtraData.getIsManualAllowed()) {
				return false;
			}
			/*
			 * notes/153：**"在进路上"不等于"忙"**。
			 *
			 * 修前这里还有一条 `vehicle.getIsOnRoute()` —— 而一列**停在站台上**的车照样"在进路上"
			 * （它脚下就是那条进路），于是派发器认定它"被别人占着"：解绑、再去车场股道上找车
			 * （站外的它不在任何股道上，找不到）⇒ 交路开到第一站就到头了；车空着杵在站台/咽喉口，
			 * 还把后面的车挡在区间外（现场实测）。"忙"的正确判据是**手上有没有没跑完的任务**。
			 */
			final org.mtr.core.mmtr.MmtrMission mission = vehicle.getMmtrMission();
			return mission == null || mission.isTerminal();
		}

		/**
		 * 这辆车是不是正在跑**我派的**那一步：任务 id 对得上就算 —— 一趟车的每一步都由同一个 id 前缀
		 * （{@code 线路/编组/序号}）认领，所以"忙"与"忙的是我的活"分得开。
		 */
		@Override
		public boolean isVehicleRunningTask(long vehicleId, String taskId) {
			final Vehicle vehicle = mmtrFindVehicle(vehicleId);
			if (vehicle == null) {
				return false;
			}
			final org.mtr.core.mmtr.MmtrMission mission = vehicle.getMmtrMission();
			if (mission == null || mission.isTerminal()) {
				return false;
			}
			final org.mtr.core.mmtr.task.MmtrTask task = mission.getTask();
			return task != null && task.taskId.equals(taskId);
		}

		@Override
		public boolean dispatchTask(long vehicleId, org.mtr.core.mmtr.task.MmtrTask task) {
			final Vehicle vehicle = mmtrFindVehicle(vehicleId);
			if (vehicle == null || task == null) {
				return false;
			}
			final long targetId = task.targetRef;
			if (!task.dispatchable()) {
				// 没有目标、又不是原地动作 ⇒ 派不出去（原地动作如"换端"没有目标，见 MmtrTask#inPlace）
				return false;
			}
			final boolean targetIsPlatform = task.targetKind.equals(org.mtr.core.mmtr.task.MmtrTask.TARGET_PLATFORM)
				|| task.kind() == org.mtr.core.mmtr.task.MmtrTaskKind.DRIVE_TO_PLATFORM
				|| task.kind() == org.mtr.core.mmtr.task.MmtrTaskKind.STATION_SERVICE;
			/*
			 * 原地动作（换端）：没有要去的地方，目的轨就是**车此刻所在的那根轨** ——
			 * 于是任务生命周期照常走（已到位 → 到点 → 完成），而"动手"由车辆侧的执行器做。
			 */
			final String targetRailHex = task.targetRef == 0
				? (vehicle.getMmtrMotionWalker() == null ? "" : vehicle.getMmtrMotionWalker().railHex())
				: "";
			final org.mtr.core.mmtr.MmtrMission.Kind kind = targetIsPlatform
				? org.mtr.core.mmtr.MmtrMission.Kind.PASSENGER : org.mtr.core.mmtr.MmtrMission.Kind.MANEUVER;
			final org.mtr.core.mmtr.MmtrMission mission = new org.mtr.core.mmtr.MmtrMission(
				vehicleId, kind, vehicle.getMmtrMission() == null ? 0 : vehicle.getMmtrMission().getTargetSidingId(), targetId, getCurrentMillis());
			mission.attachTask(task);
			mission.setInPlaceTargetRailHex(targetRailHex);
			if (!vehicle.setMmtrMission(mission)) {
				return false;
			}
			if (!vehicle.isMmtrMotion()) {
				vehicle.engageMissionAutopilot();
			}
			System.out.println("[MMTR-PLAN] 派车 " + task.describe() + " → 车 " + vehicleId
				+ "（" + task.taskId + "）");
			return true;
		}

		/**
		 * 把这一步从车上收回来（只有"跑的正是在这一步"才收）。
		 *
		 * <p>收回 = 清任务。车会停在原地（不会自己继续开），下一步由新计划重新派 ——
		 * 这正是"计划里已经没有这一步"时该有的样子。</p>
		 */
		@Override
		public boolean releaseTask(long vehicleId, String taskId) {
			final Vehicle vehicle = mmtrFindVehicle(vehicleId);
			if (vehicle == null || taskId == null || taskId.isEmpty()) {
				return false;
			}
			final org.mtr.core.mmtr.MmtrMission mission = vehicle.getMmtrMission();
			final org.mtr.core.mmtr.task.MmtrTask task = mission == null ? null : mission.getTask();
			if (task == null || !task.taskId.equals(taskId)) {
				return false;   // 它跑的不是这一步（作业单/别的计划）：不动
			}
			vehicle.setMmtrMission(null);
			return true;
		}

		/** 车辆段里有没有这条股道（用来把"这个车场"解释成"这个段的全部股道"）。 */
		private boolean containsSiding(org.mtr.core.data.Depot depot, long sidingId) {
			for (final org.mtr.core.data.Siding siding : depot.savedRails) {
				if (siding.getId() == sidingId) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * MMTR rolling-stock reset (车辆生成表 apply): drop every generated train - parked or en route -
	 * on all sidings, clear stale templates, then install the manifest's consist on each declared
	 * depot siding so the engine spawns exactly the configured rolling stock. Runs explicitly once
	 * at startup and is callable on demand (operator reset). Job-mode dispatch stays disabled so the
	 * generated trains simply wait for a human or an AI driver.
	 *
	 * @return number of sidings the manifest installed stock on
	 */
	public int mmtrResetAndApplyRollingStock() {
		sidings.forEach(Siding::clearVehicles);
		sidings.forEach(siding -> {
			siding.setVehicleCars(new ObjectArrayList<>());
			siding.mmtrManualSpawn = false;
			siding.mmtrSessionSpawned = false;
		});
		int placed = 0;
		for (final org.mtr.core.mmtr.manifest.MmtrManifestDepot depotEntry : mmtrRollingStock.depots) {
			Depot depot = null;
			for (final Depot candidate : depots) {
				if (candidate.getId() == depotEntry.depotId) {
					depot = candidate;
					break;
				}
			}
			if (depot == null) {
				System.out.println("[MMTR-MFST] manifest depot " + depotEntry.depotId + " not found - skipped");
				continue;
			}
			for (final org.mtr.core.mmtr.manifest.MmtrManifestSiding sidingEntry : depotEntry.sidings) {
				if (sidingEntry.cars.isEmpty()) {
					continue;
				}
				Siding siding = null;
				for (final Siding candidate : depot.savedRails) {
					if (candidate.getId() == sidingEntry.sidingId) {
						siding = candidate;
						break;
					}
				}
				if (siding == null) {
					System.out.println("[MMTR-MFST] manifest siding " + sidingEntry.sidingId + " not found in depot " + depotEntry.depotId + " - skipped");
					continue;
				}
				final ObjectArrayList<org.mtr.core.data.VehicleCar> cars = new ObjectArrayList<>();
				for (final org.mtr.core.mmtr.job.MmtrCarSpec spec : sidingEntry.cars) {
					cars.add(spec.toVehicleCar());
				}
				if (Siding.getTotalVehicleLength(cars) > siding.getRailLength()) {
					System.out.println("[MMTR-MFST] manifest consist on siding " + sidingEntry.sidingId + " does not fit (rail " + siding.getRailLength() + " m) - skipped");
					continue;
				}
				siding.setVehicleCars(cars);
				siding.mmtrManualSpawn = true;
				siding.mmtrSessionSpawned = false;
				placed++;
			}
		}
		if (placed > 0) {
			// Keep legacy depot auto-dispatch off so generated stock stays parked until operated.
			mmtrJobsMode = true;
		}
		System.out.println("[MMTR-MFST] rolling-stock reset applied: " + placed + " siding(s) staged for spawn on " + dimension);
		return placed;
	}

	public org.mtr.core.mmtr.manifest.MmtrRollingStockManifest getMmtrRollingStock() {
		return mmtrRollingStock;
	}

	/**
	 * MMTR session-level cleanup: remove every generated train (parked or en route) and clear every
	 * siding template so nothing re-seeds. The rolling-stock manifest is untouched - it is applied
	 * again on the next restart (explicit reset).
	 */
	public void mmtrClearAllVehicles() {
		// 车没了，它们在进路/道岔/调车授权上的痕迹由 Siding#clearVehicles 一起放掉
		// （notes/155 §11：不清就留下"幽灵持有者"，把整条咽喉按死）。
		sidings.forEach(Siding::clearVehicles);
		sidings.forEach(siding -> {
			siding.setVehicleCars(new ObjectArrayList<>());
			siding.mmtrManualSpawn = false;
			siding.mmtrSessionSpawned = false;
		});
		System.out.println("[MMTR-MFST] cleared all vehicles + templates on " + dimension);
	}

	/**
	 * 一辆车**离开世界**时把它在外面的持有全部放掉：进路 + 道岔（进向持有/排队/物理位置）+ 调车授权。
	 *
	 * <p>三样东西都是**按持有者 id 跨 tick 存活**的，车主没了它们不会自己消失 —— 于是留下
	 * "幽灵持有者"把咽喉锁死（notes/155 §11）。删除、清车、淘汰车都必须走这一句。</p>
	 */
	public void mmtrReleaseVehicleClaims(long vehicleId) {
		mmtrRoutes.release(vehicleId);
		mmtrPointAuthority.releaseAll("v" + vehicleId);
		mmtrShuntAuthorities.revoke(vehicleId);
		/*
		 * notes/408 S1：**值守记录也要跟着车走**。它是**按玩家 id 跨 tick 存活**的第四样东西 ——
		 * 车主没了它不会自己消失，于是留下"某人正在开一辆不存在的车"这种幽灵值守，
		 * 而那条记录会把这个人**永久挡在别的车次之外**（一个玩家同一时刻至多一个值守）。
		 * {@code MmtrDutyRegistry.tick} 里也有"车没了就清"的兜底，这里多加一道是让**删除那一刻**就干净。
		 */
		mmtrDuties.forgetVehicle(vehicleId, "这趟车已经从世界里删掉了");
	}

	/**
	 * **计划内接管**：把某列车当前挂着的那条作业单交给它的司机（油门给司机、进路与停车点仍由引擎给）。
	 *
	 * <h3>为什么只在静止时允许</h3>
	 * <p>用户口径（2026-09-21）："比如在车辆还在等待发车，到站停站等静止状态时接管"。
	 * 这不只是偏好 —— 车在动时换执行者，进路/道岔持有/protection 都处在"为自动驾驶算出来的"状态，
	 * 交接窗口里的任何一拍都可能在两个执行者之间空转。停在原地交接则是干净的：谁都不用抢。</p>
	 *
	 * <h3>司机是谁</h3>
	 * <p>给了 {@code driverUuid} 就用它（客户端按键那条路自己报自己的 uuid，最权威）；
	 * 没给就从**驾驶室钥匙**读（{@link Vehicle#getMmtrCrewUuid()}）—— 控制台里敲指令时用这一条，
	 * 于是不必让人把 uuid 抄一遍。两者都没有 ⇒ 拒绝（"驾驶室里没人"）。</p>
	 *
	 * @return {@code null} = 接管成功；非空 = 拒绝原因（给操作者看的一句人话）
	 */
	public @org.jspecify.annotations.Nullable String mmtrJobTakeover(long vehicleId, @org.jspecify.annotations.Nullable UUID driverUuid) {
		final Vehicle vehicle = mmtrFindVehicle(vehicleId);
		if (vehicle == null) {
			return "找不到车辆 " + vehicleId;
		}
		if (mmtrJobScheduler == null) {
			return "这局里没有作业单调度器";
		}
		if (vehicle.getSpeed() > 1e-9 || vehicle.isMoving()) {
			return "车还在动 —— 计划内接管只允许在静止状态（等待发车 / 到站停站）时进行";
		}
		final java.util.UUID driver = driverUuid != null ? driverUuid : vehicle.getMmtrCrewUuid();
		if (driver == null) {
			return "这列车没有司机（驾驶室钥匙不在任何人手里）—— 先上车再接管";
		}
		final String jobId = mmtrJobScheduler.jobIdOfVehicle(vehicleId);
		if (jobId == null) {
			return "车 " + vehicleId + " 没有挂在任何作业单上";
		}
		mmtrJobScheduler.humanTakeover(jobId, driver);
		/*
		 * 驾驶室朝向：人接手时要的是"坐在车头往前看"。自动运行为了走得通可能把换向器翻着
		 * （尾在前），那正是"被传送到与行进方向相反的驾驶室"的现场成因之一 —— 交接时归位。
		 * 上面的静止闸门已经保证了这里 speed == 0，符合"只在停稳时翻换向器"的红线。
		 */
		vehicle.mmtrResetTravelDirectionForDriver();
		// 正在跑的那一步也要立刻换执行者：否则这一步仍在等自动车到点，司机开了也不算数。
		final org.mtr.core.mmtr.MmtrMission mission = vehicle.getMmtrMission();
		if (mission != null && !mission.isTerminal()) {
			mission.setExecutor(org.mtr.core.mmtr.MmtrMission.Executor.PLAYER, driver);
		}
		// 停车点可能还是"自动车那一份"：清掉让玩家任务自己按 PLAYER 口径重新自臂一次
		// （不设 auto、**也不设停车点** —— 到站判据换成"站台轨上有车且停稳"，
		//   见 Vehicle#mmtrMotionSelfArmMission 的 PLAYER 分支与 Vehicle#mmtrAnyCarOnMissionStation）。
		vehicle.setMmtrMotionAuto(false);
		vehicle.clearMmtrMotionStopTargetForHandover();
		System.out.println("[MMTR-JOB] 计划内接管：车 " + vehicleId + " 的作业 " + jobId + " 交给司机 " + driver
			+ "（静止交接；进路照发、油门留给司机）");
		/*
		 * notes/409 §4.4：**驾驶权刚换人 ⇒ 上行的位置权威立刻交回服务端**（换的是同一个人就不动他，
		 * 免得把他的上行白打断 3 秒）。交回之后新司机的那一路要从"第一帧"重新走一遍交接
		 * —— 这也正是 §0 里"明确赋予驾驶权利并使玩家与列车绑定"那一刻该发生的事。
		 */
		vehicle.mmtrRecallUploadAuthorityForNewHolder(driver, "驾驶权交接给 " + driver);
		return null;
	}

	/**
	 * 传送上车用：这列车现在该进**哪个驾驶室**（{@code <车节><A|B>}，空串 = 不是编组体车）。
	 *
	 * <p>判据在 {@link Vehicle#mmtrPreferredCabSpec()}；这里只是个按 id 的取数口，让游戏端的
	 * "把玩家送到车上"（{@code /mtr mmtrboard} / 引擎指令栏的 {@code train board}）能问一句
	 * "该坐哪一端"，而不是自己挑第一个驾驶室。</p>
	 */
	public String mmtrPreferredCabSpec(long vehicleId) {
		final Vehicle vehicle = mmtrFindVehicle(vehicleId);
		return vehicle == null ? "" : vehicle.mmtrPreferredCabSpec();
	}

	/** 计划内接管的归还：作业单回到自动执行，车上这一步的执行者也换回 AUTOPILOT。 */	public @org.jspecify.annotations.Nullable String mmtrJobRelease(long vehicleId) {
		final Vehicle vehicle = mmtrFindVehicle(vehicleId);
		if (vehicle == null) {
			return "找不到车辆 " + vehicleId;
		}
		if (mmtrJobScheduler == null) {
			return "这局里没有作业单调度器";
		}
		final String jobId = mmtrJobScheduler.jobIdOfVehicle(vehicleId);
		if (jobId == null) {
			return "车 " + vehicleId + " 没有挂在任何作业单上";
		}
		mmtrJobScheduler.releaseToAutopilot(jobId);
		final org.mtr.core.mmtr.MmtrMission mission = vehicle.getMmtrMission();
		if (mission != null && !mission.isTerminal()) {
			mission.setExecutor(org.mtr.core.mmtr.MmtrMission.Executor.AUTOPILOT, null);
			/*
			 * ★★ 2026-10-09 实机修正：这里原来写的是 `vehicle.setMmtrMotionAuto(true)` —— **那是错的**，
			 * 现场表现就是用户那句话："退出后也需要让AI重新接管啊"。
			 *
			 * <h3>为什么错</h3>
			 * <p>{@code mmtrMotionAuto} 在这个代码库里**不是**"允许自动开"，而是
			 * <b>"自动步进已经武装好了"</b> —— 它由自臂成功那一刻自己置真
			 * （{@code Vehicle.mmtrMotionSelfArmMission}），也是"停车点/进路已经算出来了"的同义词。
			 * 而每 tick 的自臂有一条门（{@code Vehicle} 里
			 * {@code ... && !mmtrMotionAuto && mmtrMotionStopTargetM < 0}）：**已经上电就不要再规划**。</p>
			 *
			 * <p>于是交接一来一回就死锁了：{@link #mmtrJobTakeover} 把"有司机的这一份"清掉
			 * （{@code clearMmtrMotionStopTargetForHandover()} ⇒ 停车点 = -1、
			 * {@code mmtrResetTravelDirectionForDriver()} ⇒ 朝向也可能翻过），
			 * 归还时又把 {@code mmtrMotionAuto} 手动置真 ⇒ 自臂被门挡住 ⇒
			 * <b>执行者已经是 AUTOPILOT、车却没有任何目的地，停在原地</b>。
			 * 实测读数（车 -440788602081002292 / 作业 00101）：归还后 20 秒
			 * {@code speed=0 moving=false executor=AUTOPILOT}，`job status` 里
			 * `行进方向=朝 A 端（领先端=无人）` —— 方向也还停在接管时翻过去的那一边。</p>
			 *
			 * <h3>改法</h3>
			 * <p>不碰那个标志，改调 {@link Vehicle#mmtrArmActiveMissionNow}：它与调度器挂完一步之后
			 * 调的是**同一个入口**（判据逐字一致），会在这一 tick 里规划进路、申请道岔、
			 * 给出停车点，并把朝向按"规划得出来哪一边"归位。它自己的门要求
			 * {@code !mmtrMotionAuto && 停车点 < 0} —— 正是交接之后的状态，所以这一步成立。</p>
			 *
			 * <p>帧任务已经在原地（{@code AT_TARGET}，例如站台作业停着）时它什么都不做，
			 * 由任务状态机自己接着走 —— 这也是它原来的行为。</p>
			 */
			vehicle.mmtrArmActiveMissionNow(this);
		}
		System.out.println("[MMTR-JOB] 归还给自动：车 " + vehicleId + " 的作业 " + jobId);
		/*
		 * notes/409 §4.4：归还 = 司机不再持有驾驶权 ⇒ 上行权威交回服务端（否则"车归自动、位置却还是
		 * 客户端说了算"，退出之后那一段就成了没人管的权威空洞）。
		 */
		vehicle.mmtrRecallUploadAuthority("作业归还自动（" + jobId + "）");
		return null;
	}

	/*
	 * ====================== 站台作业的子任务：停留时长与客户端确认 ======================
	 *
	 * 用户口径（2026-09-21）：「等段时间」的时间**暂定 20 秒，但保留修改接口**，
	 * 并且「司机手动开门和自动可以都保留」。
	 */

	/** 站台作业的默认停留（毫秒）—— 用户暂定 20 秒。作业单给了更长的计划停留时取计划那条。 */
	private long mmtrSubTaskDwellMillis = 20_000;

	public long mmtrSubTaskDwellMillis() {
		return mmtrSubTaskDwellMillis;
	}

	/**
	 * **改站台停留时长的接口**（用户要的那个"修改接口"）。
	 *
	 * <p>已经在做的那一步不受影响（链上的 DWELL 是建链时读的值，改它只影响**之后**建的链）——
	 * 这是刻意的：半路改时间会让"这一站该停多久"在同一个司机眼皮底下变来变去。</p>
	 *
	 * @param seconds 秒；&lt;= 0 或太大时被夹到 [1, 3600]
	 */
	public void mmtrSetSubTaskDwellSeconds(long seconds) {
		mmtrSubTaskDwellMillis = Math.max(1, Math.min(3600, seconds)) * 1000L;
	}

	/** 客户端（司机）确认某一条子任务：上行那一半的双向确认，转发给车辆。 */
	public @org.jspecify.annotations.Nullable String mmtrConfirmSubTask(long vehicleId, int index, long revision, @org.jspecify.annotations.Nullable UUID crewUuid) {
		final Vehicle vehicle = mmtrFindVehicle(vehicleId);
		if (vehicle == null) {
			return "找不到车辆 " + vehicleId;
		}
		return vehicle.mmtrConfirmSubTask(index, revision, crewUuid);
	}

	private Object[] mmtrLinesCache; // {signature, lines}

	/**
	 * Automatic line detection (线路自动识别): deterministic partition of every real rail into
	 * "lines" (straightest-continuation strokes, longest first). Cached against a rail-set
	 * signature so frequent map polling never re-runs the detector; invalidates when rails change.
	 */
	public ObjectArrayList<org.mtr.core.mmtr.line.MmtrLineDetector.MmtrLine> mmtrDetectLines() {
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<String> hexes = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
		for (final org.mtr.core.data.Rail rail : rails) {
			hexes.add(rail.getHexId());
		}
		hexes.sort(null);
		final StringBuilder sig = new StringBuilder().append(hexes.size()).append('|');
		for (final String hex : hexes) {
			sig.append(hex).append(',');
		}
		final String signature = sig.toString();
		if (mmtrLinesCache != null && mmtrLinesCache[0].equals(signature)) {
			return (ObjectArrayList<org.mtr.core.mmtr.line.MmtrLineDetector.MmtrLine>) mmtrLinesCache[1];
		}
		final ObjectArrayList<org.mtr.core.mmtr.line.MmtrLineDetector.MmtrLine> lines = org.mtr.core.mmtr.line.MmtrLineDetector.detect(this);
		mmtrLinesCache = new Object[]{signature, lines};
		return lines;
	}

	/**
	 * Hard default 0: ensure every turnout (fork with 2+ legs) carries an explicit operator branch
	 * 0. Runs on boot / whenever the rail set changes (rails-signature gated, so an operator clearing
	 * a fork with ✕设 keeps it unset until the track changes or the server restarts).
	 */
	public void mmtrEnsurePointDefaults() {
		if (!mmtrDefaultPointsZero) {
			return;
		}
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<String> hexes = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
		for (final org.mtr.core.data.Rail rail : rails) {
			hexes.add(rail.getHexId());
		}
		hexes.sort(null);
		final StringBuilder sig = new StringBuilder().append(hexes.size()).append('|');
		for (final String hex : hexes) {
			sig.append(hex).append(',');
		}
		final String signature = sig.toString();
		if (signature.equals(mmtrPointDefaultsSignature)) {
			return;
		}
		mmtrPointDefaultsSignature = signature;
		final java.util.Set<String> currentForks = new java.util.HashSet<>();
		boolean changed = false;
		for (final org.mtr.core.mmtr.point.MmtrPoint point : org.mtr.core.mmtr.point.MmtrPoint.discoverDirectionAware(this)) {
			if (point.legs.size() < 2) {
				continue;
			}
			currentForks.add(point.nodeX + "," + point.nodeY + "," + point.nodeZ + "|" + point.viaRailHex);
			// **单开道岔不进这里**：它由 refreshMmtrTurnouts 按"节点级位置"统一写三行派生视图
			// （一处道岔一个位置，绝不能一行一行各补 0 —— 那正是三行互相矛盾的来源）。
			if (mmtrTurnout(point.nodeX, point.nodeY, point.nodeZ) != null) {
				continue;
			}
			if (!mmtrPointBranches.contains(point.nodeX, point.nodeY, point.nodeZ, point.viaRailHex)) {
				mmtrPointBranches.set(point.nodeX, point.nodeY, point.nodeZ, point.viaRailHex, 0);
				changed = true;
			}
		}
		// 道岔节点的行由位置派生，也要算作"活的"，否则会被下面的修剪误删
		changed |= refreshMmtrTurnoutRowsForPrune(currentForks);
		// Prune stale operator rows (forks that disappeared with a rail change), then persist once.
		changed |= mmtrPointBranches.branches.keySet().removeIf(key -> !currentForks.contains(key));
		if (changed) {
			persistMmtrPointBranches();
		}
	}

	/**
	 * 把道岔节点派生的三行登记进"活的行"集合，并返回是否有行发生变化。
	 *
	 * <p>没有这一步，道岔的行会被上面的修剪当成"消失的岔口"删掉，下一次走行就会以为这一侧没有续行。</p>
	 */
	private boolean refreshMmtrTurnoutRowsForPrune(java.util.Set<String> currentForks) {
		boolean changed = false;
		for (final org.mtr.core.mmtr.point.MmtrTurnout turnout : mmtrAllTurnouts()) {
			changed |= normalizeTurnoutRows(turnout);
			final int position = mmtrPointBranches.nodePosition(turnout.nodeX, turnout.nodeY, turnout.nodeZ);
			for (final String via : new String[]{turnout.stemRailHex, turnout.farRailHex, turnout.branchRailHex}) {
				if (turnout.continuationFrom(via, position) != null) {
					currentForks.add(turnout.nodeX + "," + turnout.nodeY + "," + turnout.nodeZ + "|" + via);
				}
			}
		}
		return changed;
	}

	private String mmtrSignalColorsSignature = "";

	/*
	 * `mmtrEnsureSignalColors()` 与整条"每区间一个预留信号色"的通道**已删除**（notes/166 R4）。
	 *
	 * 它存在的理由（B3b）：让标准信号方块通道替 MMTR 把**区间占用**送到每个客户端。新模型里
	 * 占用只有一份来源 —— Level 1 轨道区间读**占用树**（{@code MmtrSectionService.isOccupied}，
	 * 且能排除问话列车自己），所以这条影子通道连同 {@code Rail.mmtrEnsureSignalColor*} /
	 * {@code Vehicle.markMmtrSignalBlock} 一并删除。它也是那个"列车被自己的影子扣住"缺陷的载体：
	 * 颜色通道认不出这团影子是谁的（notes/112 §4、notes/152）。
	 */

	/**
	 * ④ 显示层: the live occupancy trees of the train transport mode (the pair S1 reads: current tick
	 * and the previous one), or null before {@link #sync()}. The aspect view uses them to tell whether a
	 * junction's clearance zone is fouled; nothing else should mutate them.
	 */
	public @org.jspecify.annotations.Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> mmtrOccupancyTrees() {
		final int ordinal = org.mtr.core.data.TransportMode.TRAIN.ordinal();
		return vehiclePositions.isEmpty() || ordinal >= vehiclePositions.size() ? null : vehiclePositions.get(ordinal);
	}

	private org.mtr.core.mmtr.signal.@org.jspecify.annotations.Nullable MmtrSignalAspect mmtrSignalAspectView;
	private String mmtrSignalAspectSignature = "";

	/**
	 * A2/A3: the cached signal-aspect view (进路 × 闭塞). Rebuilt when the rail set changes - the
	 * same rails-signature gate the signal colours use - while the route state is read live from
	 * {@link #mmtrRoutes} on every call. Vehicles ask it what the signal they are about to pass
	 * shows; the web feed builds its own (one per request).
	 */
	public org.mtr.core.mmtr.signal.MmtrSignalAspect mmtrSignalAspectView() {
		if (mmtrSignalAspectView == null) {
			mmtrSignalAspectSignature = mmtrRailSetSignature();
			mmtrSignalAspectView = new org.mtr.core.mmtr.signal.MmtrSignalAspect(this, mmtrRoutes);
		}
		return mmtrSignalAspectView;
	}

	/** Rebuild the cached aspect view when the rail set changed (once per tick, after the rail ticks). */
	public void mmtrRefreshSignalAspectView() {
		final String signature = mmtrRailSetSignature();
		if (!signature.equals(mmtrSignalAspectSignature)) {
			mmtrSignalAspectSignature = signature;
			mmtrSignalAspectView = new org.mtr.core.mmtr.signal.MmtrSignalAspect(this, mmtrRoutes);
		}
	}

	/**
	 * 轨图签名（只有轨）。{@code refreshMmtrTurnouts} 用它 —— 道岔是从**轨图**认出来的，
	 * 认完才可能有位置，所以这条签名里绝不能出现道岔（否则就是自己调自己）。
	 *
	 * <h3>记忆化（2026-09-27，逐位相同的加速）</h3>
	 * <p>JFR（10 ms 节拍、4 列车）读数：这一条占 **35% 的采样** —— 它每 tick 都被
	 * {@link #mmtrRailSetSignature()}（信号显示视图的门）与 {@link #refreshMmtrTurnouts()} 各问一次，
	 * 每次都要把 162 根轨的 hex 拼成 ~10 KB 的字符串、把那 162 个 hex 排一遍序、再 {@code toString()}
	 * （拼串与排序占了 40%+17%）。而这些都是**同一份输入**上的重复劳动。</p>
	 *
	 * <p>判据用"轨对象引用逐个相同"而不是哈希：签名只是 {@code rails} 这个列表里**那些对象**的函数
	 * （{@code Rail} 的端点只有构造时赋值，{@code TwoPositionsBase.hexId} 也是记忆化的、全仓没有一处把它置回 null），
	 * 所以同一批对象必然给出同一个字符串。哈希会有碰撞，碰撞就会**漏掉一次重建** —— 那是改行为，不行。</p>
	 */
	private String mmtrRailGraphSignatureCache = "";
	private org.mtr.core.data.Rail[] mmtrRailGraphSignatureRails = new org.mtr.core.data.Rail[0];

	private String mmtrRailGraphSignature() {
		final int railCount = rails.size();
		if (railCount == mmtrRailGraphSignatureRails.length && !mmtrRailGraphSignatureCache.isEmpty()) {
			boolean sameRails = true;
			int index = 0;
			for (final org.mtr.core.data.Rail rail : rails) {
				if (mmtrRailGraphSignatureRails[index++] != rail) {
					sameRails = false;
					break;
				}
			}
			if (sameRails) {
				return mmtrRailGraphSignatureCache;
			}
		}
		final org.mtr.core.data.Rail[] snapshot = rails.toArray(new org.mtr.core.data.Rail[0]);
		final StringBuilder sig = new StringBuilder(railCount * 80 + 8).append(railCount).append('|');
		final ObjectArrayList<String> hexes = new ObjectArrayList<>(railCount);
		for (final org.mtr.core.data.Rail rail : rails) {
			hexes.add(rail.getHexId());
		}
		hexes.sort(null);
		hexes.forEach(hex -> sig.append(hex).append(','));
		final String signature = sig.toString();
		mmtrRailGraphSignatureCache = signature;
		mmtrRailGraphSignatureRails = snapshot;
		return signature;
	}

	/** {@link #mmtrRailSetSignature()} 的记忆化：见那两个字段的用法。 */
	private String mmtrRailSetSignatureCache = "";
	private @org.jspecify.annotations.Nullable String mmtrRailSetSignatureGraph;
	private int[] mmtrRailSetSignaturePositions = new int[0];

	/**
	 * 轨图 + 道岔位置的签名（信号显示视图用它当"要不要重建"的门，每 tick 一次）。
	 *
	 * <p>记忆化与 {@link #mmtrRailGraphSignature()} 同一口径：轨图签名**引用**没变、且每个道岔的位置
	 * 与上次逐个相同 ⇒ 返回同一个字符串实例（内容逐字符相同）。道岔集合与遍历顺序在图签名不变时是稳定的
	 * —— {@code mmtrTurnouts} 只在 {@link #refreshMmtrTurnouts()} 里重建，而它自己就被图签名挡着。</p>
	 */
	private String mmtrRailSetSignature() {
		final String graphSignature = mmtrRailGraphSignature();
		// 道岔位置也进签名：区间"走到哪里为止、哪一段撞在禁行侧"取决于它（信号显示视图因此要失效重建）。
		// 注意这不等于"灯守哪几条轨随位置变" —— 那是被用户否掉的规则，见 notes/115 §7。
		refreshMmtrTurnouts();
		final int turnoutCount = mmtrTurnouts.size();
		boolean samePositions = turnoutCount == mmtrRailSetSignaturePositions.length;
		int index = 0;
		for (final org.mtr.core.mmtr.point.MmtrTurnout turnout : mmtrTurnouts.values()) {
			if (samePositions && mmtrRailSetSignaturePositions[index] != mmtrPointBranches.nodePosition(turnout.nodeX, turnout.nodeY, turnout.nodeZ)) {
				samePositions = false;
			}
			index++;
		}
		if (samePositions && graphSignature == mmtrRailSetSignatureGraph && !mmtrRailSetSignatureCache.isEmpty()) {
			return mmtrRailSetSignatureCache;
		}
		final StringBuilder sig = new StringBuilder(graphSignature);
		final int[] positions = new int[turnoutCount];
		index = 0;
		for (final org.mtr.core.mmtr.point.MmtrTurnout turnout : mmtrTurnouts.values()) {
			final int position = mmtrPointBranches.nodePosition(turnout.nodeX, turnout.nodeY, turnout.nodeZ);
			sig.append('|').append(turnout.key()).append(':').append(position);
			positions[index++] = position;
		}
		final String signature = sig.toString();
		mmtrRailSetSignatureCache = signature;
		mmtrRailSetSignatureGraph = graphSignature;
		mmtrRailSetSignaturePositions = positions;
		return signature;
	}

	/** Discover all turnouts (道岔) on the rail graph with the operator branch states applied. */
	public ObjectArrayList<org.mtr.core.mmtr.point.MmtrSwitch> mmtrDiscoverPoints() {
		final ObjectArrayList<org.mtr.core.mmtr.point.MmtrSwitch> points = org.mtr.core.mmtr.point.MmtrPointRegistry.discover(this);
		for (final org.mtr.core.mmtr.point.MmtrSwitch s : points) {
			s.branch = mmtrPointBranches.get(s.nodeX, s.nodeY, s.nodeZ, s.viaRailHex);
		}
		return points;
	}

	// ---------------------------------------------------------------- 物理道岔（一处一个位置）

	private final java.util.HashMap<String, org.mtr.core.mmtr.point.MmtrTurnout> mmtrTurnouts = new java.util.HashMap<>();
	private String mmtrTurnoutSignature = "";

	/**
	 * 某节点上的**物理道岔**（一处道岔一个位置，两个互斥进路）；不是单开道岔时返回 null。
	 *
	 * <p>缓存按"轨集合签名"失效：世界改画了会自动重认。</p>
	 */
	public org.mtr.core.mmtr.point.MmtrTurnout mmtrTurnout(long x, long y, long z) {
		refreshMmtrTurnouts();
		return mmtrTurnouts.get(x + "," + y + "," + z);
	}

	public ObjectArrayList<org.mtr.core.mmtr.point.MmtrTurnout> mmtrAllTurnouts() {
		refreshMmtrTurnouts();
		final ObjectArrayList<org.mtr.core.mmtr.point.MmtrTurnout> out = new ObjectArrayList<>(mmtrTurnouts.values());
		out.sort((a, b) -> a.key().compareTo(b.key()));
		return out;
	}

	/**
	 * 道岔状态的**廉价签名**（notes/172）：只做整数混合，不分配、不排序、不拼字符串。
	 *
	 * <p>为什么需要它：{@code MmtrSectionService.signature()} 是引擎里最热的查询之一
	 * （一次链走行要算七八遍，`aspectsForAllRails()` 一次请求约 2500 遍），而它原来走
	 * {@link #mmtrAllTurnouts()} —— 那是"分配一个数组 + 拷贝所有值 + 排序 + 排序比较里每次再拼两个 key 字符串"。
	 * 签名只需要"**变了会变**"（只跟本次运行内上一次的值比），不需要顺序确定。</p>
	 */
	public int mmtrTurnoutStateSignature() {
		refreshMmtrTurnouts();
		int hash = mmtrTurnouts.size() * 31 + 1;
		for (final org.mtr.core.mmtr.point.MmtrTurnout turnout : mmtrTurnouts.values()) {
			hash = hash * 31 + Long.hashCode(turnout.nodeX);
			hash = hash * 31 + Long.hashCode(turnout.nodeY);
			hash = hash * 31 + Long.hashCode(turnout.nodeZ);
			hash = hash * 31 + mmtrPointBranches.nodePosition(turnout.nodeX, turnout.nodeY, turnout.nodeZ);
		}
		return hash;
	}

	/** 道岔位置（0 = 正线贯通 / 1 = 岔股开放）。 */
	public int mmtrTurnoutPosition(long x, long y, long z) {
		refreshMmtrTurnouts();
		final org.mtr.core.mmtr.point.MmtrTurnout turnout = mmtrTurnouts.get(x + "," + y + "," + z);
		if (turnout == null) {
			return mmtrPointBranches.nodePosition(x, y, z);
		}
		/*
		 * 人工搬岔 = 覆盖 + 锁定（用户 2026-09-14 的选择）：锁着的时候位置由人工说了算 ——
		 * 物理持有者的授权、行视图的折算都不许把它扳回去，否则"人工优先"只是这一 tick 的假象。
		 */
		if (mmtrPointAuthority.isTurnoutLocked(x, y, z, turnout)) {
			return mmtrPointBranches.nodePosition(x, y, z);
		}
		// T1: 有物理持有者时，位置由它决定 —— "行视图折进位置"这条老路（最后写入者为准）不得把
		// 正在持有这道岔的列车脚下的位置改掉。
		final int physicalHolderPosition = mmtrPointAuthority.physicalPosition(x, y, z);
		if (physicalHolderPosition != org.mtr.core.mmtr.point.MmtrPointAuthority.NO_PHYSICAL_HOLDER) {
			return physicalHolderPosition;
		}
		/*
		 * **行视图折进位置**（最后写入者为准）：老调用方（网页某一行的腿号、旧测试、手工改 mmtr-points.json）
		 * 直接写"某进向的第几条腿"时，这里把能翻译成进路的那个腿采纳为节点位置。
		 *
		 * <p>这样"一个位置 + 三行派生"不会因为写入路径不同而分裂：无论从哪一头写，最终都落到同一个位置。</p>
		 */
		final int current = mmtrPointBranches.nodePosition(x, y, z);
		for (final String via : new String[]{turnout.stemRailHex, turnout.farRailHex, turnout.branchRailHex}) {
			if (!mmtrPointBranches.contains(x, y, z, via)) {
				continue;
			}
			final int position = mmtrTurnoutPositionForLeg(x, y, z, via, mmtrPointBranches.get(x, y, z, via));
			if (position != Integer.MIN_VALUE && position != current) {
				mmtrPointBranches.setNode(x, y, z, position);
				normalizeTurnoutRows(turnout);
				persistMmtrPointBranches();
				return position;
			}
		}
		return current;
	}

	/**
	 * 联锁扳动道岔（单处、立即）：这个道岔上若有授权持有的腿，就把位置扳到它。
	 *
	 * <p>走行在岔前直接调用，所以进路一旦批下来，道岔在**同一步**就位（不必等一个 tick）。</p>
	 */
	public void mmtrSyncTurnoutPositionToGrant(org.mtr.core.mmtr.point.MmtrTurnout turnout) {
		final int current = mmtrPointBranches.nodePosition(turnout.nodeX, turnout.nodeY, turnout.nodeZ);
		// 人工锁着的道岔不跟着授权走（人工搬岔 = 覆盖 + 锁定，用户 2026-09-14）。
		if (mmtrPointAuthority.isTurnoutLocked(turnout.nodeX, turnout.nodeY, turnout.nodeZ, turnout)) {
			return;
		}
		/*
		 * T1: **位置由持有者决定**。从前这里按 {stem, far, branch} 的数组顺序取第一个"有授权能翻译成位置"
		 * 的进向，于是"哪一列车赢"取决于数组下标 —— 两列车从不同进向要求互斥位置时，先出现在数组里的
		 * 那个说了算。现在物理层只有**一个**持有者，位置由它定；没有持有者才退回逐进向的老路
		 * （人工位/默认位，人工随时可以再扳）。
		 */
		final int physical = mmtrPointAuthority.physicalPosition(turnout.nodeX, turnout.nodeY, turnout.nodeZ);
		if (physical != org.mtr.core.mmtr.point.MmtrPointAuthority.NO_PHYSICAL_HOLDER) {
			if (physical != current) {
				mmtrPointBranches.setNode(turnout.nodeX, turnout.nodeY, turnout.nodeZ, physical);
				normalizeTurnoutRows(turnout);
				persistMmtrPointBranches();
			}
			return;
		}
		for (final String via : new String[]{turnout.stemRailHex, turnout.farRailHex, turnout.branchRailHex}) {
			final int granted = mmtrPointAuthority.grantedLeg(turnout.nodeX, turnout.nodeY, turnout.nodeZ, via);
			if (granted < 0) {
				continue;
			}
			final int position = mmtrTurnoutPositionForLeg(turnout.nodeX, turnout.nodeY, turnout.nodeZ, via, granted);
			if (position != Integer.MIN_VALUE && position != current) {
				mmtrPointBranches.setNode(turnout.nodeX, turnout.nodeY, turnout.nodeZ, position);
				normalizeTurnoutRows(turnout);
				persistMmtrPointBranches();
				return;
			}
		}
	}

	private void refreshMmtrTurnouts() {
		// 只用**轨图**签名：道岔本身是从轨图认出来的，把位置算进来就成了自己调自己（无限递归）。
		final String signature = mmtrRailGraphSignature();
		if (signature.equals(mmtrTurnoutSignature)) {
			return;
		}
		mmtrTurnoutSignature = signature;
		mmtrTurnouts.clear();
		positionsToRail.forEach((node, neighbours) -> {
			final org.mtr.core.mmtr.point.MmtrTurnout turnout = org.mtr.core.mmtr.point.MmtrTurnout.resolve(node, neighbours);
			if (turnout != null) {
				mmtrTurnouts.put(turnout.key(), turnout);
			}
		});
		boolean changed = normalizeMmtrPersistedRailHexes();
		for (final org.mtr.core.mmtr.point.MmtrTurnout turnout : mmtrTurnouts.values()) {
			if (!mmtrPointBranches.containsNode(turnout.nodeX, turnout.nodeY, turnout.nodeZ)) {
				/*
				 * 新表型出来的节点：**默认 0（正线/更直那一侧）= 安全侧**。
				 *
				 * <p>用户 2026-09-14 的裁定。从前这里是"从行视图反推"（有人把任一进向扳到岔股 → 位置 1），
				 * 本意是"升级不丢人工设置"；但世界上大量老行是**引擎早期铺的默认行**（每个进向 leg0），
				 * 于是"默认行"被读成了"人工选了岔股" —— 实测 {@code -19,-60,51} 与 {@code -154,-60,-139}
				 * 一表型出来就是位置 1。语义上那两句站不住，而 0 是安全侧（另一侧禁止通行、列车停在岔前），
				 * 所以新表型节点一律 0；确实设过人工位的老岔口，升级后会回到 0 一次，重设即可
				 * （现在人工搬岔还会落锁，见 {@link #mmtrOperatorSetTurnoutPosition}）。</p>
				 */
				mmtrPointBranches.setNode(turnout.nodeX, turnout.nodeY, turnout.nodeZ, org.mtr.core.mmtr.point.MmtrTurnout.NORMAL);
				changed = true;
			}
			changed |= normalizeTurnoutRows(turnout);
		}
		if (changed) {
			persistMmtrPointBranches();
		}
	}

	/**
	 * **存档里的轨 hex 键在载入时归一化**（notes/130 §6b 的遗留）。
	 *
	 * <h3>问题</h3>
	 * <p>同一条实体轨有**两种写法**（互为逆序，取决于这条 Rail 怎么被声明）。网页/接口对外一律用
	 * 规范写法（{@code canonicalHex}），而 {@code mmtr-points.json} 的行（{@code switches[].via}）与
	 * 人工锁（{@code locks[].via}）存的是**引擎内部写法**。同一份存档里读回来是一致的，所以今天不会
	 * 出错；但**世界改画**（把某根轨反向重画）之后，这些键就变成孤儿键、静默失效 ——
	 * 人工锁还在文件里，却锁不住任何东西。</p>
	 *
	 * <h3>为什么放在这里而不是载入的那一刻</h3>
	 * <p>载入时轨图还没建好（{@code positionsToRail} 要等 {@code sync()}），{{@code mmtrResolveRailHex}}
	 * 无从下手。本方法在 {@link #refreshMmtrTurnouts} 的"轨图签名变了"那一刻跑 —— 与信号绑定列表
	 * 的处理方式对齐（它也是在读到轨图之后才解析）。</p>
	 *
	 * @return 是否有键被改写（调用方据此决定要不要落盘）
	 */
	private boolean normalizeMmtrPersistedRailHexes() {
		boolean changed = false;
		// ① 行视图（switches[]）
		final java.util.List<String> rowKeys = new java.util.ArrayList<>(mmtrPointBranches.branches.keySet());
		for (final String rowKey : rowKeys) {
			final String[] parts = rowKey.split("\\|");
			if (parts.length != 2) {
				continue;
			}
			final String[] coords = parts[0].split(",");
			if (coords.length != 3) {
				continue;
			}
			try {
				final long x = Long.parseLong(coords[0]);
				final long y = Long.parseLong(coords[1]);
				final long z = Long.parseLong(coords[2]);
				final String resolved = mmtrResolveRailHex(x, y, z, parts[1]);
				if (resolved != null && !resolved.equals(parts[1])) {
					changed |= mmtrPointBranches.rekey(x, y, z, parts[1], resolved);
				}
			} catch (NumberFormatException ignored) {
				// 脏键：留给 saveBranches 的既有过滤
			}
		}
		// ② 人工锁（locks[]）—— 锁失效是静默的，所以这条比行视图更要紧
		for (final String lockKey : mmtrPointAuthority.locksSnapshot()) {
			final String[] parts = lockKey.split("\\|");
			if (parts.length != 2) {
				continue;
			}
			final String[] coords = parts[0].split(",");
			if (coords.length != 3) {
				continue;
			}
			try {
				final long x = Long.parseLong(coords[0]);
				final long y = Long.parseLong(coords[1]);
				final long z = Long.parseLong(coords[2]);
				final String resolved = mmtrResolveRailHex(x, y, z, parts[1]);
				if (resolved != null && !resolved.equals(parts[1])) {
					changed |= mmtrPointAuthority.rekeyLock(x, y, z, parts[1], resolved);
				}
			} catch (NumberFormatException ignored) {
				// 同上
			}
		}
		return changed;
	}

	/**
	 * 把节点位置翻译回"每个进向一行"的腿号，并顺手把**禁止通行**那一行写成 -1。
	 *
	 * <p>这是"一个位置、三行派生"的唯一写入口：位置是权威，行视图只为了让既有调用方
	 * （{@code MmtrForkElection}、诊断、网页）看到一致的事实。返回值 = 是否有变化。</p>
	 */
	private boolean normalizeTurnoutRows(org.mtr.core.mmtr.point.MmtrTurnout turnout) {
		final int position = mmtrPointBranches.nodePosition(turnout.nodeX, turnout.nodeY, turnout.nodeZ);
		boolean changed = false;
		for (final String via : new String[]{turnout.stemRailHex, turnout.farRailHex, turnout.branchRailHex}) {
			final String allowed = turnout.continuationFrom(via, position);
			final int leg = allowed == null ? -1 : legIndexForRail(turnout, via, allowed);
			if (leg >= 0) {
				if (!mmtrPointBranches.contains(turnout.nodeX, turnout.nodeY, turnout.nodeZ, via)
					|| mmtrPointBranches.get(turnout.nodeX, turnout.nodeY, turnout.nodeZ, via) != leg) {
					mmtrPointBranches.set(turnout.nodeX, turnout.nodeY, turnout.nodeZ, via, leg);
					changed = true;
				}
			} else if (mmtrPointBranches.contains(turnout.nodeX, turnout.nodeY, turnout.nodeZ, via)) {
				// 禁止通行：行也要消失，否则"这一侧有续行"会骗到走行与显示层
				mmtrPointBranches.set(turnout.nodeX, turnout.nodeY, turnout.nodeZ, via, -1);
				changed = true;
			}
		}
		return changed;
	}

	private static int legIndexForRail(org.mtr.core.mmtr.point.MmtrTurnout turnout, String viaRailHex, String railHex) {
		if (railHex.equals(turnout.stemRailHex)) {
			return turnout.stemLeg.getOrDefault(viaRailHex, -1);
		}
		if (railHex.equals(turnout.farRailHex)) {
			return turnout.farLeg.getOrDefault(viaRailHex, -1);
		}
		if (railHex.equals(turnout.branchRailHex)) {
			return turnout.branchLeg.getOrDefault(viaRailHex, -1);
		}
		return -1;
	}

	/**
	 * 设定道岔：入参是"某个进向上的第几条腿"（既有调用方：网页、指令、任务），
	 * 内部**翻译成节点位置**（一处道岔只有两个位置），并把三行派生视图一起刷新。
	 *
	 * @return 是否受理；{@code false} = 物理上不存在这个组合（例如"岔股 → 正线远端"这种交叉）
	 */
	public boolean mmtrSetTurnoutPosition(long x, long y, long z, int position) {
		refreshMmtrTurnouts();
		final org.mtr.core.mmtr.point.MmtrTurnout turnout = mmtrTurnouts.get(x + "," + y + "," + z);
		if (turnout == null) {
			return false;
		}
		mmtrPointBranches.setNode(x, y, z, position == org.mtr.core.mmtr.point.MmtrTurnout.REVERSE
			? org.mtr.core.mmtr.point.MmtrTurnout.REVERSE : org.mtr.core.mmtr.point.MmtrTurnout.NORMAL);
		normalizeTurnoutRows(turnout);
		persistMmtrPointBranches();
		System.out.println("[MMTR-PT] turnout " + turnout.key() + " -> 位置 " + mmtrPointBranches.nodePosition(x, y, z)
			+ "（正线贯通 vs 岔股开放；禁行 = " + turnout.prohibitedRailHex(mmtrPointBranches.nodePosition(x, y, z)).substring(0, 8) + "…）");
		return true;
	}

	/**
	 * **人工搬岔**：设位置并**锁住**这个道岔（用户 2026-09-14 的选择："人工搬岔同时把道岔锁住，
	 * 永久生效直到解锁"；设计原文：人工 operator &gt; 显式任务/进路申请）。
	 *
	 * <p>为什么要锁：不锁的话，在途授权会在下一个 tick 把位置按自己的腿扳回去
	 * （{@link #mmtrSyncTurnoutPositionsToGrants}），人工操作看着像没生效 —— 实测就是这样红掉的
	 * （{@code manualOperatorBranchOutranksTheVehiclesOwnGrant}）。一处道岔只有一个位置，
	 * 所以三个进向一起锁；解锁用 {@code point unlock}，锁随 {@code mmtr-points.json} 落盘。</p>
	 *
	 * <p>被拒时 {@link #mmtrLastOperatorThrowBlockedReason()} 给出可读原因（指令层原话回给操作者）。</p>
	 */
	/**
	 * 上一次人工扳岔被拒的原因（{@code null} = 没被拒）：指令层把这句话原样回给操作者，
	 * 而不是笼统说"设定失败"（现场最需要知道的是"为什么"）。
	 */
	private String mmtrLastOperatorThrowBlockedReason = null;

	public @org.jspecify.annotations.Nullable String mmtrLastOperatorThrowBlockedReason() {
		return mmtrLastOperatorThrowBlockedReason;
	}

	public boolean mmtrOperatorSetTurnoutPosition(long x, long y, long z, int position) {
		/*
		 * 闸门：**车压在岔上就不许扳**（用户 2026-09-14："车压在岔上就拒绝人工扳岔"）。
		 * 把道岔从车下抽走是脱轨级事故；判定与灯显示"岔区净空被占"读同一段代码，
		 * 所以不会出现"灯说被占、道岔照样能扳"这种自相矛盾。
		 */
		final String blocked = org.mtr.core.mmtr.signal.MmtrJunctionState.blockedThrowReason(this, new org.mtr.core.data.Position(x, y, z));
		if (blocked != null) {
			System.out.println("[MMTR-PT] 拒绝人工扳岔 " + x + "," + y + "," + z + "：" + blocked);
			mmtrLastOperatorThrowBlockedReason = blocked;
			return false;
		}
		mmtrLastOperatorThrowBlockedReason = null;
		if (!mmtrSetTurnoutPosition(x, y, z, position)) {
			return false;
		}
		final org.mtr.core.mmtr.point.MmtrTurnout turnout = mmtrTurnouts.get(x + "," + y + "," + z);
		for (final String via : new String[]{turnout.stemRailHex, turnout.farRailHex, turnout.branchRailHex}) {
			mmtrPointAuthority.lock(x, y, z, via);
		}
		persistMmtrPointBranches();
		System.out.println("[MMTR-PT] 人工位已锁定 " + turnout.key() + "（自动进路排队等 point unlock）");
		return true;
	}

	/**
	 * **联锁按意图扳岔**（用户 2026-09-14 的选择 ①）：人工位 / 授权 / 任务目标这条"想走哪条腿"的意图
	 * 一旦确定，就把道岔扳到它要的那一位 —— 与设计 §5.3"玩家不扳岔，联锁扳岔"一致。
	 *
	 * <p>两道闸门：<b>人工锁着的不扳</b>（人工优先，见 {@link #mmtrSetTurnoutPosition}），
	 * <b>别人物理持有也不扳</b>（T1：不许把道岔从列车脚下抽走）。</p>
	 *
	 * <p><b>持有者本人是例外</b>（notes/136 §3）：那道闸门防的是"把道岔从**别人**列车脚下抽走"，
	 * 不是防持有者自己换位。没有这条豁免时，"旧计划把它按在位置 1、新计划要位置 0"会变成死锁 ——
	 * 车的进路判 PENDING（物理位置 ≠ 本车要的位），而扳岔又被"有人持有"挡住，持有者就是它自己，
	 * 谁也解不开（现场：车停在出发信号前不动）。豁免不会丢掉安全性：第三道闸门
	 * （{@link org.mtr.core.mmtr.signal.MmtrJunctionState#blockedThrowReason}：车压在岔区上不扳）
	 * 是独立的一条，照样拦得住。</p>
	 *
	 * @return true = 已经（或本来就在）那一位；false = 现在不能扳，调用方应让列车在岔前等
	 */
	public boolean mmtrThrowTurnoutForIntent(long x, long y, long z, int position) {
		return mmtrThrowTurnoutForIntent(x, y, z, position, null);
	}

	/**
	 * 同上，并说明**是谁在要求**（{@code "v"+车辆id}）。
	 *
	 * @param requesterOwner 请求方；非空时它**自己按着的那处道岔可以自己改位**（见上面的说明）
	 */
	public boolean mmtrThrowTurnoutForIntent(long x, long y, long z, int position, @org.jspecify.annotations.Nullable String requesterOwner) {
		refreshMmtrTurnouts();
		final org.mtr.core.mmtr.point.MmtrTurnout turnout = mmtrTurnouts.get(x + "," + y + "," + z);
		if (turnout == null) {
			return false;
		}
		final String holder = mmtrPointAuthority.physicalHolder(x, y, z);
		if (mmtrPointAuthority.isTurnoutLocked(x, y, z, turnout)
			|| (holder != null && !holder.equals(requesterOwner))) {
			return false;
		}
		// 第三道闸门与人工那条一样：车压在岔上不扳（同一条判定，见 MmtrJunctionState.blockedThrowReason）。
		//
		// 但要**把请求方自己排除在外**（2026-09-27 现场修，与 mmtrSyncTurnoutPositionsToGrants 那两处同一条）：
		// 车开到岔前时车头本来就进了 10 m 净空区，不带请求方的判定于是永远"净空被占" ⇒ 意图扳岔永远失败
		// ⇒ 车停在岔前、灯按"走不出去"红着（诊断里那句"（改位置的请求方 ）"是空的，就是这条路留下的痕迹）。
		// 净空闸的本意是"不许把道岔从**别的**车脚下抽走"，所以排除请求方不削弱安全性。
		if (org.mtr.core.mmtr.signal.MmtrJunctionState.blockedThrowReasonExcept(this,
			new org.mtr.core.data.Position(x, y, z), vehicleIdOfOwner(requesterOwner)) != null) {
			return false;
		}
		final int wanted = position == org.mtr.core.mmtr.point.MmtrTurnout.REVERSE
			? org.mtr.core.mmtr.point.MmtrTurnout.REVERSE : org.mtr.core.mmtr.point.MmtrTurnout.NORMAL;
		/*
		 * 持有者本人要求换位时，**改的是它自己的需求**，不是绕过它写行视图。
		 *
		 * <p>T1 之后"位置由持有者决定"（{@code mmtrTurnoutPosition} 与 {@code mmtrSyncTurnoutPositionToGrant}
		 * 都先读持有者），所以只写 {@code mmtrPointBranches} 的话位置**根本没变** —— 现场的症状正是
		 * "指令说扳过去了、车还是不动"（notes/136 §3）。所以这里把持有者按的位一起改掉，两处保持一致。</p>
		 */
		if (requesterOwner != null && requesterOwner.equals(holder)) {
			mmtrPointAuthority.repointPhysicalHold(x, y, z, requesterOwner, wanted);
		}
		if (mmtrPointBranches.nodePosition(x, y, z) != wanted) {
			mmtrPointBranches.setNode(x, y, z, wanted);
			normalizeTurnoutRows(turnout);
			persistMmtrPointBranches();
			System.out.println("[MMTR-PT] 联锁按意图扳岔 " + turnout.key() + " -> 位置 " + wanted
				+ (requesterOwner == null ? "" : "（请求方 " + requesterOwner + "）"));
		}
		return true;
	}

	/**
	 * **联锁扳动道岔**：某条进路/调车授权持有这个道岔时，道岔位置跟着授权的腿走。
	 *
	 * <p>这是"道岔 × 信号"真正接起来的那一环：进路要岔股 → 道岔扳到 1（正线那一侧随之禁止通行）；
	 * 授权释放后位置留在原地（人工位/默认位，人工随时可以再扳）。每 tick 一次，只在真的变了才落盘。</p>
	 */
	public void mmtrSyncTurnoutPositionsToGrants() {
		refreshMmtrTurnouts();
		if (mmtrTurnouts.isEmpty()) {
			return;
		}
		boolean changed = false;
		for (final org.mtr.core.mmtr.point.MmtrTurnout turnout : mmtrTurnouts.values()) {
			// 人工锁着的道岔不跟着授权走（人工搬岔 = 覆盖 + 锁定，用户 2026-09-14 的选择）。
			if (mmtrPointAuthority.isTurnoutLocked(turnout.nodeX, turnout.nodeY, turnout.nodeZ, turnout)) {
				continue;
			}
			/*
			 * **净空闸按"替谁扳"分别判，不再先做一次不带请求方的判定**（2026-09-16 现场修）。
			 *
			 * <p>原来这里在整个循环开头先判一次 {@code blockedThrowReason}（不带请求方）：只要岔区 10 m 内
			 * 有**任何**车足迹就 {@code continue}。而**车走到岔前时车头本来就进了净空区** —— 于是
			 * "车到了、道岔却永远不同步"，位置永远停在旧的那一位。现场表现正是用户报的那条：
			 * **道岔不会被任务驱动**（灯按"走不出去"红着、车在信号前干等，而 {@code point why} 里那句
			 * "（改位置的请求方 ）"是空的 —— 那就是这条路留下的痕迹）。</p>
			 *
			 * <p>净空闸的本意是"不许把道岔从**别的**车脚下抽走"，所以它必须在知道"替谁扳"之后判，
			 * 并把那列车自己排除在外。下面两条路（物理持有者 / 逐进向授权）现在各自做**带请求方**的判定，
			 * 用的都是同一个口径 {@code blockedThrowReasonExcept}。</p>
			 */
			final int current = mmtrPointBranches.nodePosition(turnout.nodeX, turnout.nodeY, turnout.nodeZ);
			// T1: 物理持有者优先（理由同 mmtrSyncTurnoutPositionToGrant）。
			final int physical = mmtrPointAuthority.physicalPosition(turnout.nodeX, turnout.nodeY, turnout.nodeZ);
			if (physical != org.mtr.core.mmtr.point.MmtrPointAuthority.NO_PHYSICAL_HOLDER) {
				if (physical == current) {
					continue;   // 已经是这一位：没有什么可扳的（也就不该问净空闸）
				}
				final String physicalOwner = mmtrPointAuthority.physicalHolder(turnout.nodeX, turnout.nodeY, turnout.nodeZ);
				if (org.mtr.core.mmtr.signal.MmtrJunctionState.blockedThrowReasonExcept(this,
					new org.mtr.core.data.Position(turnout.nodeX, turnout.nodeY, turnout.nodeZ), vehicleIdOfOwner(physicalOwner)) == null) {
					mmtrPointBranches.setNode(turnout.nodeX, turnout.nodeY, turnout.nodeZ, physical);
					normalizeTurnoutRows(turnout);
					changed = true;
				}
				continue;
			}
			for (final String via : new String[]{turnout.stemRailHex, turnout.farRailHex, turnout.branchRailHex}) {
				final int granted = mmtrPointAuthority.grantedLeg(turnout.nodeX, turnout.nodeY, turnout.nodeZ, via);
				if (granted < 0) {
					continue;
				}
				final int position = mmtrTurnoutPositionForLeg(turnout.nodeX, turnout.nodeY, turnout.nodeZ, via, granted);
				if (position == Integer.MIN_VALUE || position == current) {
					continue;
				}
				/*
				 * **这是替"拿到这条腿授权的那列车"扳的**，所以净空闸要把**它自己**排除在外
				 * （2026-09-16 现场修）：原来这里读的是不带请求方的判定，而列车走到岔前时车头本来就
				 * 进了岔区 ⇒ 判定永远"净空被占" ⇒ 位置永远不跟着授权走 ⇒ 那处道岔的灯按"走不出去"红着、
				 * 车在信号前干等（诊断日志里那句"（改位置的请求方 ）"是空的，就是这条路的痕迹）。
				 */
				final String grantOwner = mmtrPointAuthority.holder(turnout.nodeX, turnout.nodeY, turnout.nodeZ, via);
				if (org.mtr.core.mmtr.signal.MmtrJunctionState.blockedThrowReasonExcept(this,
					new org.mtr.core.data.Position(turnout.nodeX, turnout.nodeY, turnout.nodeZ), vehicleIdOfOwner(grantOwner)) != null) {
					continue;
				}
				mmtrPointBranches.setNode(turnout.nodeX, turnout.nodeY, turnout.nodeZ, position);
				normalizeTurnoutRows(turnout);
				changed = true;
			}
		}
		if (changed) {
			persistMmtrPointBranches();
		}
	}

	/**
	 * **重新规划时放掉"旧计划留下的位置"**（notes/136 §3 的自持有死锁，第 3 条修法）。
	 *
	 * <p>物理位置持有只在两处释放：列车**跨过**岔口时，或任务终态 {@code releaseAll}。于是
	 * "先按了位置 1、还没跨过去、计划又被替换成要位置 0"会让这处道岔谁也扳不动 ——
	 * 进路判 PENDING（物理位置 ≠ 本车要的位）而意图扳岔被"有人持有"挡住，持有者就是它自己。
	 * 车停在出发信号前不动，重启也不会自己好。</p>
	 *
	 * <p>放哪些：本 owner 按着位置的每一处道岔，**新计划要的不是那一位**（或者新计划根本不经过它）
	 * 就放掉。要的是同一位则原样保留 —— 既不churn，也不丢在道岔队列里的 FIFO 位置
	 * （列车等待联锁时会每 tick 重新调用一次本方法）。</p>
	 *
	 * <p>放掉之后由新计划的原子申请按新需要重新拿（{@code Vehicle#armMmtrPointRun} 的顺序就是这样：
	 * 先放旧的，再申请新的，同一次调用内完成，中间没有任何人能插进来）。</p>
	 *
	 * <p><b>车还压在岔区上就不放</b>：与扳岔的第三道闸门同一条判定。它一走，下一次重新规划再放。</p>
	 *
	 * @param wantedPositionsByNodeKey 新计划在每处道岔上要的位置（{@code "x,y,z"} → 0/1）
	 * @return 放掉几处
	 */
	public int mmtrReleaseStalePhysicalHolds(String owner, java.util.Map<String, Integer> wantedPositionsByNodeKey) {
		if (owner == null || owner.isEmpty() || wantedPositionsByNodeKey == null) {
			return 0;
		}
		int released = 0;
		for (final long[] node : mmtrPointAuthority.physicalHoldNodesOf(owner)) {
			final int held = mmtrPointAuthority.physicalPosition(node[0], node[1], node[2]);
			final Integer wanted = wantedPositionsByNodeKey.get(node[0] + "," + node[1] + "," + node[2]);
			if (wanted != null && wanted == held) {
				continue;
			}
			if (org.mtr.core.mmtr.signal.MmtrJunctionState.blockedThrowReason(this,
				new org.mtr.core.data.Position(node[0], node[1], node[2])) != null) {
				continue;
			}
			if (mmtrPointAuthority.releasePhysicalHold(node[0], node[1], node[2], owner)) {
				released++;
				System.out.println("[MMTR-PT] 重新规划：放掉旧计划在道岔 " + node[0] + "," + node[1] + "," + node[2]
					+ " 的位置 " + held + "（新计划" + (wanted == null ? "不经过这里" : "要位置 " + wanted) + "）");
			}
		}
		return released;
	}

	/** 把 (进向, 腿号) 翻译成节点位置；物理上不存在的组合返回 {@link Integer#MIN_VALUE}。 */
	public int mmtrTurnoutPositionForLeg(long x, long y, long z, String viaRailHex, int leg) {
		refreshMmtrTurnouts();
		final org.mtr.core.mmtr.point.MmtrTurnout turnout = mmtrTurnouts.get(x + "," + y + "," + z);
		if (turnout == null) {
			return Integer.MIN_VALUE;
		}
		// 只认物理事实（{@link MmtrTurnout#positionForLeg}）：**"从岔股回根部"就是"把岔股扳通"**。
		// 从前这里对它返回"当前位置"，于是车尾在岔股上的车请求开出时扳不动道岔、被禁行闸门挡在岔前，
		// 永远等不到（S5 队列测试实测）。
		return turnout.positionForLeg(viaRailHex, leg);
	}

	/** Set an operator turnout branch index (0..legs-1 in the ordered-leg model, legacy 0/1 on
	 * two-leg forks) and persist it. A negative branch removes the operator setting (halt at that
	 * fork, never auto). */
	public boolean mmtrSetPoint(long x, long y, long z, String viaRailHex, int branch) {
		refreshMmtrTurnouts();
		mmtrLastOperatorThrowBlockedReason = null;
		// 网页/指令回传的是**规范 hex**（见 mmtrResolveRailHex）：先翻成引擎内部的写法，
		// 否则会静默落到另一把键上（行写了、位置却没动）。
		final String via = mmtrResolveRailHex(x, y, z, viaRailHex);
		if (mmtrTurnouts.containsKey(x + "," + y + "," + z)) {
			// 单开道岔：入参是"某进向上的第几条腿"，翻译成**节点位置**（一处道岔只有两个位置）。
			if (branch < 0) {
				// 取消人工设置 = 回到默认 0（不是"锁在 0"）：这是"没有人工意见"，自动进路照常申请。
				return mmtrSetTurnoutPosition(x, y, z, org.mtr.core.mmtr.point.MmtrTurnout.NORMAL);
			}
			final int position = mmtrTurnoutPositionForLeg(x, y, z, via, branch);
			if (position == Integer.MIN_VALUE) {
				System.out.println("[MMTR-PT] 拒绝 " + x + "," + y + "," + z + " 从 " + shortHex(via)
					+ " 的第 " + branch + " 条腿：这两条进路互斥，物理上不存在（会把列车带上尖轨）");
				return false;
			}
			return mmtrOperatorSetTurnoutPosition(x, y, z, position);
		}
		mmtrPointBranches.set(x, y, z, via, branch);
		if (branch < 0) {
			// 清掉人工位：不锁（语义同单开道岔那条路）。
			persistMmtrPointBranches();
			System.out.println("[MMTR-PT] set switch " + x + "," + y + "," + z + " via " + via + " -> unset");
			return true;
		}
		// 非单开道岔（没有物理模型）：人工位同样要锁住，否则自动申请会把它顶掉。
		mmtrPointAuthority.lock(x, y, z, via);
		persistMmtrPointBranches();
		System.out.println("[MMTR-PT] set switch " + x + "," + y + "," + z + " via " + via + " -> " + branch
			+ "（人工位已锁定，自动进路排队等 point unlock）");
		return true;
	}

	private static String shortHex(String hex) {
		return hex.length() <= 8 ? hex : hex.substring(0, 8) + "…";
	}

	/** Persist the operator branch store to mmtr-points.json (batch clear before a mission arm). */
	public void persistMmtrPointBranches() {
		if (mmtrPointsPath != null) {
			// 人工锁一并落盘：位置本身已经存了，但只存位置的话重启后自动进路会把位置按授权扳回去，
			// "人工优先"就只在当前进程里成立（用户 2026-09-14 的选择：永久生效直到解锁）。
			org.mtr.core.mmtr.point.MmtrPointRegistry.saveBranches(mmtrPointsPath, mmtrPointBranches.branches,
				mmtrPointBranches.nodePositions, mmtrPointAuthority.locksSnapshot());
		}
	}

	// --- P3 turnout authority machine interface (multi-level control) ---

	/** Auto logic requests a leg of an en-route turnout (approach locking). Returns GRANTED/QUEUED. */
	/**
	 * MMTR: upsert one authoritative junction leg table entry (进向表) for (node, via rail).
	 * Empty {@code legHexes} removes the entry (geometry auto-detection takes over again).
	 * @return whether the table changed
	 */
	public boolean mmtrJunctionLegsUpsert(long x, long y, long z, String viaRailHex, it.unimi.dsi.fastutil.objects.ObjectArrayList<String> legHexes) {
		final boolean changed;
		if (legHexes == null || legHexes.isEmpty()) {
			changed = mmtrJunctionLegs.legs.remove(x + "," + y + "," + z + "|" + viaRailHex) != null;
		} else {
			final String key = x + "," + y + "," + z + "|" + viaRailHex;
			final it.unimi.dsi.fastutil.objects.ObjectArrayList<String> prev = mmtrJunctionLegs.legs.get(key);
			changed = prev == null || !prev.equals(legHexes);
			if (changed) {
				mmtrJunctionLegs.legs.put(key, new it.unimi.dsi.fastutil.objects.ObjectArrayList<>(legHexes));
			}
		}
		if (changed && mmtrJunctionLegsPath != null) {
			org.mtr.core.mmtr.point.MmtrJunctionLegsRegistry.save(mmtrJunctionLegsPath, mmtrJunctionLegs.legs);
		}
		return changed;
	}

	/**
	 * MMTR: upsert/remove one wayside signal entry (信号机登记表). Ops:
	 * "set" = register the light (AUTO unless target given -> BOUND), "remove" = delete.
	 * @return whether the registry changed
	 */
	/**
	 * 显式改一盏灯的守轨列表（点选绑定），并落盘。
	 *
	 * <p>与 {@link #mmtrSignalOp} 分开：那个接口是"登记/改朝向/绑单根轨"，这里是"它守哪几根轨"，
	 * 两者写入的字段不同，混在一个 op 字符串里只会让语义越来越绕。</p>
	 *
	 * @param rails 完整的目标列表（整体替换）；空列表 = 清掉人工绑定，回到按几何推断
	 * @return 是否找到并改了这盏灯
	 */
	public boolean mmtrSignalBindRails(int x, int y, int z, java.util.List<String> rails) {
		final boolean changed = mmtrSignals.setBoundRails(x, y, z, rails);
		if (changed && mmtrSignalsPath != null) {
			org.mtr.core.mmtr.signal.MmtrSignalRegistry.save(mmtrSignalsPath, mmtrSignals.signals);
		}
		return changed;
	}

	/**
	 * 删掉一条信号灯登记并落盘（世界扫描发现"那一格已经没有灯了"时用）。
	 *
	 * <p>与 {@link #mmtrSignalOp} 的 remove 区别：那个是走指令通道的通用删除，这个专门给**扫描**
	 * 用，语义是"世界扫描确认它不在了"。分出来是因为调用方只有游戏端扫描一处，
	 * 而且它要的是"删了没有"这个布尔值来写扫描报告。</p>
	 */
	public boolean mmtrSignalRemove(int x, int y, int z) {
		final boolean changed = mmtrSignals.remove(x, y, z);
		if (changed && mmtrSignalsPath != null) {
			org.mtr.core.mmtr.signal.MmtrSignalRegistry.save(mmtrSignalsPath, mmtrSignals.signals);
		}
		return changed;
	}

	/**
	 * 扫描看到"这一格确实有一盏灯"时的**原地刷新**：只改朝向与灯位数，人工绑定一律不动。
	 *
	 * <h3>为什么不能直接复用 {@link #mmtrSignalOp} 的 set</h3>
	 * <p>{@code set} 走的是 {@code MmtrSignalRegistry.put}，那里面是 {@code new SignalEntry(...)}
	 * 整体替换 —— {@code mode} / {@code target} / {@code rails} 三个字段会被一起冲掉。手动扫描偶尔
	 * 跑一次还看不出问题（BOUND 条目被跳过），但**自动刷新是每秒都在跑的**：一盏 AUTO 灯只要被扫到
	 * 一次，人工点选绑定就会被抹一次，而且抹掉之后再也回不来。刷新只该改"世界告诉我们的那两个值"
	 * （朝向、灯位数），其余是人工意图，扫描没有资格改。</p>
	 *
	 * <h3>返回值为什么是"是否新增"</h3>
	 * <p>新增要落盘（否则重启就丢）；只改朝向不落盘 —— 自动刷新每秒都在跑，按"变了就写世界文件"
	 * 会把 {@code mmtr-signals.json} 变成每秒一写的热文件。朝向本来每次启动都会被重新读一遍世界，
	 * 不落盘不会丢信息。</p>
	 *
	 * @return 是否新增了一条登记
	 */
	public boolean mmtrSignalRefresh(int x, int y, int z, float angle, int aspects) {
		final org.mtr.core.mmtr.signal.MmtrSignalRegistry.SignalEntry existing = mmtrSignals.get(x, y, z);
		if (existing == null) {
			return mmtrSignalOp(x, y, z, angle, aspects, "set", "");
		}
		existing.angle = angle;
		existing.aspects = aspects;
		return false;
	}

	/**
	 * 节点的**朝向角**（游戏端扫描上报）：{@code BlockNode.getAngle(state)}，也就是 MTR 在放置节点时
	 * 由玩家朝向决定的那个值（{@code FACING} / {@code IS_22_5} / {@code IS_45} 三个方块属性）。
	 *
	 * <h3>为什么引擎需要它</h3>
	 * <p>引擎的拓扑里节点只有**坐标**（{@code positionsToRail} 的键），没有朝向。而"一盏灯守哪条腿"
	 * 在实测世界里**不能只用灯自己的朝向推出来**：同一个节点上、朝向相对的两盏灯，一盏守北、一盏守南，
	 * 六盏实测灯里四盏"与朝向同向"、两盏"与朝向反向" —— 缺的那个变量就是节点朝向
	 * （原版 MTR 的 {@code RenderSignalBase.getAspectState} 用的正是它）。</p>
	 *
	 * <p>键是节点坐标（{@code x,y,z}）；值为角度（度）。</p>
	 */
	public final java.util.HashMap<String, Float> mmtrNodeAngles = new java.util.HashMap<>();

	/**
	 * 记下一个节点的朝向角（游戏端扫描上行）。
	 *
	 * @return 是否是新值或值变了（调用方据此决定要不要落盘/重算）
	 */
	public boolean mmtrNodeAngleUpsert(long x, long y, long z, float angle) {
		final String key = x + "," + y + "," + z;
		final Float previous = mmtrNodeAngles.get(key);
		if (previous != null && Math.abs(previous - angle) < 1e-3) {
			return false;
		}
		mmtrNodeAngles.put(key, angle);
		return true;
	}

	/** 某个节点的朝向角；没上报过则返回 null。 */
	public @org.jspecify.annotations.Nullable Float mmtrNodeAngle(long x, long y, long z) {
		return mmtrNodeAngles.get(x + "," + y + "," + z);
	}

	public boolean mmtrSignalOp(int x, int y, int z, float angle, int aspects, String op, String target) {
		final boolean changed;
		if ("remove".equalsIgnoreCase(op)) {
			changed = mmtrSignals.signals.remove(org.mtr.core.mmtr.signal.MmtrSignalRegistry.key(x, y, z)) != null;
		} else {
			final String mode = target == null || target.isEmpty() ? "AUTO" : "BOUND";
			final boolean had = mmtrSignals.get(x, y, z) != null;
			mmtrSignals.put(x, y, z, angle, aspects, mode, target);
			changed = !had;
		}
		if (changed && mmtrSignalsPath != null) {
			org.mtr.core.mmtr.signal.MmtrSignalRegistry.save(mmtrSignalsPath, mmtrSignals.signals);
		}
		return changed;
	}

	/**
	 * Covered bind helper (游戏侧工具/扫描上行): given the light position/angle and a clicked rail node,
	 * choose the rail leaving that node whose heading best matches the light's facing, then register the
	 * light BOUND to that rail.
	 *
	 * <p><strong>Single source of truth (闭塞区间 v2).</strong> The rail is chosen by the SAME resolution
	 * the section model uses ({@code MmtrSectionService.resolveProtectedRail}), which resolves by
	 * the lamp's facing angle. An earlier version re-implemented the facing maths here with a
	 * "the renderer applies a 90 degree offset" assumption; a bind tool that disagrees with the model by a
	 * quarter turn is exactly how a light ends up bound to a rail running ACROSS its facing - the dead
	 * binding found at {@code -163,-60,-189} (notes/105 §3.1), which then neither cuts a section nor shows
	 * a trustworthy aspect.</p>
	 *
	 * @return whether a matching rail was found and the light registered
	 */
	public boolean mmtrSignalBindAtNode(int x, int y, int z, float angle, int aspects, long nodeX, long nodeY, long nodeZ) {
		final org.mtr.core.data.Position node = new org.mtr.core.data.Position(nodeX, nodeY, nodeZ);
		final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<org.mtr.core.data.Position, org.mtr.core.data.Rail> neighbours = positionsToRail.get(node);
		if (neighbours == null || neighbours.isEmpty()) {
			return false;
		}
		// Ask the v2 model which rail this lamp protects (by its facing). Fall back to the nearest-heading
		// search only when the model cannot answer (no rail within tolerance of the light).
		final org.mtr.core.mmtr.signal.MmtrSignalRegistry.SignalEntry probe =
			new org.mtr.core.mmtr.signal.MmtrSignalRegistry.SignalEntry(x, y, z, angle, aspects);
		final org.mtr.core.mmtr.signal.MmtrSectionService.ProtectedRail resolved =
			mmtrSections.resolveProtectedRail(probe);
		if (resolved != null) {
			return mmtrSignalOp(x, y, z, angle, aspects, "set", resolved.rail.getHexId());
		}
		final double want = Math.toRadians(angle + 90.0);
		final double[] best = {Double.MAX_VALUE};
		final String[] bestHex = {null};
		neighbours.forEach((far, rail) -> {
			final double bearing = Math.atan2(far.getZ() - node.getZ(), far.getX() - node.getX());
			double diff = bearing - want;
			while (diff > Math.PI) {
				diff -= 2 * Math.PI;
			}
			while (diff < -Math.PI) {
				diff += 2 * Math.PI;
			}
			if (Math.abs(diff) < best[0]) {
				best[0] = Math.abs(diff);
				bestHex[0] = rail.getHexId();
			}
		});
		return bestHex[0] != null && mmtrSignalOp(x, y, z, angle, aspects, "set", bestHex[0]);
	}

	public org.mtr.core.mmtr.point.MmtrPointAuthority.Result mmtrPointRequest(long x, long y, long z, String viaRailHex, String owner, int leg, long untilMillis) {
		final org.mtr.core.mmtr.point.MmtrPointAuthority.Result result = mmtrPointAuthority.request(x, y, z, viaRailHex, owner, leg, untilMillis);
		// 节流：卡死的岔口会被每 tick 重申请，直接打会刷屏并拖住控制台线程（见 shouldLogDiagnostic）
		if (org.mtr.core.mmtr.point.MmtrPointAuthority.shouldLogDiagnostic(
			"req " + owner + "@" + x + "," + y + "," + z, String.valueOf(result))) {
			System.out.println("[MMTR-PT] req " + owner + "@" + x + "," + y + "," + z + " via " + viaRailHex + " leg " + leg + " -> " + result);
		}
		return result;
	}

	/** The train crossed (or gave up on) a point: its hold is consumed and the queue advances. */
	public void mmtrPointRelease(long x, long y, long z, String viaRailHex, String owner) {
		mmtrPointAuthority.passed(x, y, z, viaRailHex, owner);
		if (org.mtr.core.mmtr.point.MmtrPointAuthority.shouldLogDiagnostic(
			"rel " + owner + "@" + x + "," + y + "," + z, "")) {
			System.out.println("[MMTR-PT] rel " + owner + "@" + x + "," + y + "," + z + " via " + viaRailHex);
		}
	}

	/**
	 * Operator parks a point for manual use: auto requests queue until mmtrPointUnlock.
	 *
	 * <p>落盘是必须的：{@link #mmtrUnlockAllPoints()} / {@code point unlock} 之所以"解了又回来"，
	 * 就是因为锁只在内存里动了、存档里没动 —— 见 {@link #mmtrUnlockAllPoints()} 的说明。</p>
	 */
	public void mmtrPointLock(long x, long y, long z, String viaRailHex) {
		final String via = mmtrResolveRailHex(x, y, z, viaRailHex);
		mmtrPointAuthority.lock(x, y, z, via);
		persistMmtrPointBranches();
		System.out.println("[MMTR-PT] lock " + x + "," + y + "," + z + " via " + via);
	}

	/**
	 * 解锁**一处进向**，并**落盘**。
	 *
	 * <p>用户 2026-09-14 的选择是"人工搬岔同时把道岔锁住（**永久生效直到解锁**）"。既然锁是永久的，
	 * 解锁就必须同样永久 —— 只改内存的话，重启后锁会原样回来，用户看到的"解锁"是假的
	 * （现场实测：网页显示 0 处锁闭，而存档里还留着 20 条锁，下一次落盘就会把它们写回去）。</p>
	 */
	public void mmtrPointUnlock(long x, long y, long z, String viaRailHex) {
		final String via = mmtrResolveRailHex(x, y, z, viaRailHex);
		mmtrPointAuthority.unlock(x, y, z, via);
		persistMmtrPointBranches();
		System.out.println("[MMTR-PT] unlock " + x + "," + y + "," + z + " via " + via);
	}

	/**
	 * 解锁**全部**人工锁（含界面上没有对应按钮的那些进向），返回清掉几把，并**落盘**。
	 *
	 * <p>为什么要这个入口：人工搬岔一次锁的是三条进向，而网页/指令是按"进向行"表达的，
	 * 所以"逐个解锁"永远会有解不到的死角；现场表现就是"网页上锁闭是 0，重启后锁全回来了"。</p>
	 */
	public int mmtrUnlockAllPoints() {
		final int cleared = mmtrPointAuthority.clearLocks();
		persistMmtrPointBranches();
		System.out.println("[MMTR-PT] unlock all：清掉 " + cleared + " 把人工锁");
		return cleared;
	}

	/**
	 * 把"任意端点写法的轨 hex"翻成引擎内部用的 {@code getHexId()}（声明顺序）。
	 *
	 * <h3>为什么必须翻一次（用户 2026-09-14 现场报的"这个道岔不会高亮显示道岔状态"）</h3>
	 * <p>一条轨的 hex 是「端点1-端点2」，**哪个端点写在前**取决于这条 Rail 怎么被声明/读出来：
	 * 同一根实体轨，从 A 到 B 画与从 B 到 A 画会得到两个互为逆序的字符串
	 * （见 {@code MmtrSectionService.canonicalHex}）。接口对外一律发**规范写法**
	 * （拓扑接口早就这么做，网页也把收到的 hex 原样发回来），而引擎内部的表（进向行、道岔的
	 * stem/far/branch、授权、锁）用的是 {@code getHexId()}。不翻一次，网页按道岔卡片给出的 hex
	 * 去比对地图上的轨就永远对不上（实测 {@code -19,-60,51} 的两根东向轨正是这种轨：
	 * 道岔卡片说 {@code FFFFFFFFFFFFFFED…}，地图上是 {@code 0000000000000001…}），
	 * 于是"点亮当前开通那条腿"整条功能静默失效。</p>
	 *
	 * @return 引擎内部的写法；找不到就原样返回（调用方按老行为处理）
	 */
	public @org.jspecify.annotations.Nullable String mmtrResolveRailHex(long x, long y, long z, @org.jspecify.annotations.Nullable String railHex) {
		if (railHex == null || railHex.isEmpty()) {
			return railHex;
		}
		final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<org.mtr.core.data.Position, org.mtr.core.data.Rail> neighbours =
			positionsToRail.get(new org.mtr.core.data.Position(x, y, z));
		if (neighbours == null) {
			return railHex;
		}
		final String wanted = org.mtr.core.mmtr.signal.MmtrSectionService.canonicalHex(railHex);
		for (final org.mtr.core.data.Rail rail : neighbours.values()) {
			if (rail.getHexId().equals(railHex)
				|| org.mtr.core.mmtr.signal.MmtrSectionService.canonicalHex(rail.getHexId()).equals(wanted)) {
				return rail.getHexId();
			}
		}
		return railHex;
	}

	/** Release every turnout request held/queued by this owner (terminal missions, resets). */
	public void mmtrPointReleaseAll(String owner) {
		mmtrPointAuthority.releaseAll(owner);
	}


	/**
	 * MMTR vehicle-level operation: delete the train with the given world-unique vehicle id
	 * wherever it stands (parked or en route). Generation (rolling-stock manifest) and deletion are
	 * independent operations keyed by the spawned train's own id.
	 *
	 * @return whether a vehicle with that id existed and was removed
	 */
	public boolean deleteMmtrVehicle(long vehicleId) {
		for (final Siding siding : sidings) {
			if (siding.removeVehicleById(vehicleId)) {
				// A deleted train must not leave a stale route / turnout hold behind: the signal layer
				// would keep showing its route as set over rails nothing runs on any more.
				mmtrReleaseVehicleClaims(vehicleId);
				return true;
			}
		}
		return false;
	}

	/**
	 * B7.6: find a live vehicle by id — OP commands and cab ops address vehicles by their engine id
	 * (the same id the web feeds and the drive command use).
	 */
	@Nullable
	public Vehicle mmtrFindVehicle(long vehicleId) {
		final Vehicle[] found = {null};
		sidings.forEach(siding -> siding.iterateVehicles(vehicle -> {
			if (vehicle.getId() == vehicleId) {
				found[0] = vehicle;
			}
		}));
		return found[0];
	}

	private void persistMmtrJobs() {
		expandMmtrJobTemplates();
		if (!mmtrJobRegistry.jobs.isEmpty()) {
			mmtrJobsMode = true;
			mmtrAiJobStepsEnabled = true; // scheduler ticks as soon as consist jobs exist
		}
		if (mmtrJobsPath != null) {
			mmtrJobRegistry.save(mmtrJobsPath);
		}
		mmtrJobScheduler = org.mtr.core.mmtr.job.MmtrJobScheduler.create(mmtrJobRegistry.jobs);
	}

	/**
	 * Expand jobs that reference a named consist template (车辆代码) but carry no explicit cars.
	 * Authoring then is just 车场/股道 + 编组代码; spawning still uses the concrete car list.
	 */
	private void expandMmtrJobTemplates() {
		for (final org.mtr.core.mmtr.job.MmtrConsistJob job : mmtrJobRegistry.jobs) {
			if (!job.consistId.isEmpty() && job.cars.isEmpty()) {
				final org.mtr.core.mmtr.job.MmtrConsistTemplate template = mmtrConsistTemplates.get(job.consistId);
				if (template != null) {
					for (final org.mtr.core.mmtr.job.MmtrCarSpec spec : template.cars) {
						job.cars.add(spec);
					}
				}
			}
		}
	}


	public void save() {
		autoSave = true;
	}

	/**
	 * Stop ticking and perform a final, non-incremental save.
	 */
	public void stop() {
		save(false);
	}

	/**
	 * Compare {@code millis} to the last-tick / current-tick window, accounting for day-wrap.
	 *
	 * @param millis Milliseconds to check
	 * @return 1 if upcoming, 0 if current, -1 if passed
	 */
	public int matchMillis(long millis) {
		if (Utilities.circularDifference(getCurrentMillis(), millis, MILLIS_PER_DAY) < 0) {
			return 1;
		} else {
			return Utilities.circularDifference(millis, lastMillis, MILLIS_PER_DAY) > 0 ? 0 : -1;
		}
	}

	/**
	 * Fast-forward the listed depots through one full in-game day in one-second slices, leaving
	 * the simulator's clock unchanged. Used by the dashboard "instant deploy" button so depot
	 * vehicles are immediately spawned on every siding.
	 */
	public void instantDeployDepots(ObjectArrayList<Depot> depotsToInstantDeploy) {
		final long oldLastMillis = lastMillis;
		final long oldCurrentMillis = getCurrentMillis();
		for (int i = 0; i < MILLIS_PER_DAY; i += MILLIS_PER_SECOND) {
			lastMillis = getCurrentMillis();
			setCurrentMillis(lastMillis + MILLIS_PER_SECOND);
			depotsToInstantDeploy.forEach(depot -> depot.savedRails.forEach(siding -> siding.simulateVehicles(MILLIS_PER_SECOND, null)));
		}
		lastMillis = oldLastMillis;
		setCurrentMillis(oldCurrentMillis);
	}

	/**
	 * Convenience overload of {@link #instantDeployDepots(ObjectArrayList)} that selects depots by
	 * a name {@code filter} via {@link NameColorDataBase#getDataByName}.
	 *
	 * @param simulator simulator whose depots are scanned (typically {@code this} — kept as a
	 *                  parameter so the method matches the embedding mod's existing signature)
	 * @param filter    case-insensitive name filter
	 */
	public void instantDeployDepotsByName(Simulator simulator, String filter) {
		instantDeployDepots(NameColorDataBase.getDataByName(simulator.depots, filter));
	}

	/**
	 * 中控指令用：在**一条股道**上立刻生成一列车（{@code vehicle spawn} 的落点）。
	 *
	 * <p>走的是引擎自己已有的即时路径：{@link #instantDeployDepots} 就是把 depot 快进一整天来立刻生成车辆，
	 * 这里对单条股道做同一件事（按 1 秒切片推进 {@code Siding.simulateVehicles}）。所以命令返回时车已经
	 * 在世界上，调用方可以直接拿车辆 id 去核对 —— 不必等下一个 tick，也不需要重启。
	 *
	 * <p>顺序上先写编组模板与标记（{@code mmtrManualSpawn} 且 {@code mmtrSessionSpawned=false} 才会触发一次生成），
	 * 再推进；生成成功后 {@code Siding} 自己会把 session 标记置上，所以重复调用不会叠出第二列车。</p>
	 *
	 * @param siding 目标股道
	 * @param cars   编组（每个元素一辆车卡）
	 * @return 生成出来的车辆；股道上有在途车辆、放不下、或走不出站场时返回 null
	 */
	public org.mtr.core.data.Vehicle mmtrSpawnOnSiding(org.mtr.core.data.Siding siding, it.unimi.dsi.fastutil.objects.ObjectArrayList<org.mtr.core.data.VehicleCar> cars) {
		if (siding == null || cars == null || cars.isEmpty()) {
			return null;
		}
		if (org.mtr.core.data.Siding.getTotalVehicleLength(cars) > siding.getRailLength() + 1e-6) {
			return null;
		}
		siding.setVehicleCars(cars);
		siding.mmtrManualSpawn = true;
		siding.mmtrSessionSpawned = false;
		// 与 instantDeployDepots 同一手法：按 1 秒切片推进，直到股道走完它自己的生成周期
		for (int i = 0; i < MILLIS_PER_DAY; i += MILLIS_PER_SECOND) {
			siding.simulateVehicles(MILLIS_PER_SECOND, null);
		}
		// 取这条股道上"在场"的那辆车作为结果。
		// 取这条股道上"在场"的那辆车作为结果。
		// 引擎里没有全局车辆集合：车辆挂在**股道**上，所以枚举方式是 `sidings.forEach(s -> s.iterateVehicles(…))`
		// （`mmtrFindVehicle` 也是这么找的）。归属用 `vehicleExtraData.getSidingId()` 判断，
		// 因为 `Vehicle.siding` 是 private，而 sidingId 是公开且稳定的关联。
		final org.mtr.core.data.Vehicle[] found = {null};
		siding.iterateVehicles(vehicle -> {
			if (vehicle.vehicleExtraData.getSidingId() == siding.getId()) {
				found[0] = vehicle;
			}
		});
		return found[0];
	}

	/**
	 * 中控指令用：把一列车**直接放在指定的轨上**（{@code vehicle spawn --rail=<轨hex>} 的落点）。
	 *
	 * <h3>为什么需要"临时股道"</h3>
	 * <p>引擎里车辆挂在**股道**上：{@code Siding.simulateVehicles} 第一句就是"没有车辆段 ⇒ 清空返回"，
	 * 而车辆段归属是 {@code Data.mapAreasAndSavedRails} 按几何算出来的。所以"在任意一根轨上落车"只能
	 * 给那根轨**临时建一条股道**（一个临时车辆段 + 与轨等长的股道），从而复用引擎自己那条即刻生成路径。</p>
	 *
	 * <p>它是**工具产物**，不是世界里的东西：游戏端不知道这个车辆段，所以这列车只存在于引擎
	 * （网页地图、闭塞计算、占用树都看得到；游戏里看不到）。要一辆游戏里也存在的车，只能在游戏里放。</p>
	 *
	 * @param rail  目标轨（必须在轨图里）
	 * @param cars  编组
	 * @return 生成出来的车辆；轨太短放不下、或走不出站场时返回 null
	 */
	public org.mtr.core.data.@Nullable Vehicle mmtrSpawnOnRail(org.mtr.core.data.Rail rail, it.unimi.dsi.fastutil.objects.ObjectArrayList<org.mtr.core.data.VehicleCar> cars) {
		if (rail == null || cars == null || cars.isEmpty()) {
			return null;
		}
		final double railLength = rail.railMath.getLength();
		if (org.mtr.core.data.Siding.getTotalVehicleLength(cars) > railLength + 1e-6) {
			return null;
		}
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<org.mtr.core.data.Position> ends = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
		final org.mtr.core.data.Position[] ordered = rail.mmtrOrderedPositions();
		ends.add(ordered[0]);
		ends.add(ordered[1]);

		final org.mtr.core.data.Depot depot = new org.mtr.core.data.Depot(org.mtr.core.data.TransportMode.TRAIN, this);
		depot.setName("临时落车");
		// 范围要比包围盒大一点：将来真的 sync 一次时，这条股道的中点仍落在车辆段内，归属不会掉
		final long minX = Math.min(ordered[0].getX(), ordered[1].getX()) - 4;
		final long maxX = Math.max(ordered[0].getX(), ordered[1].getX()) + 4;
		final long minZ = Math.min(ordered[0].getZ(), ordered[1].getZ()) - 4;
		final long maxZ = Math.max(ordered[0].getZ(), ordered[1].getZ()) + 4;
		depot.setCorners(new org.mtr.core.data.Position(minX, ordered[0].getY() - 4, minZ), new org.mtr.core.data.Position(maxX, ordered[0].getY() + 4, maxZ));

		final org.mtr.core.data.Siding siding = new org.mtr.core.data.Siding(ends.get(0), ends.get(1), railLength, org.mtr.core.data.TransportMode.TRAIN, this);
		depot.adoptSiding(siding);
		depots.add(depot);
		sidings.add(siding);
		siding.tick(); // 解析它自己的站场轨（defaultPathData），没有它 simulateVehicles 不会生成
		final org.mtr.core.data.Vehicle vehicle = mmtrSpawnOnSiding(siding, cars);
		if (vehicle == null) {
			// 放不下 / 走不出站场：把临时设施撤掉，别留一个空壳在世界里
			sidings.remove(siding);
			depots.remove(depot);
		}
		return vehicle;
	}

	/** 中控指令用：某条股道上当前有几辆车（{@code query depots} 显示用）。 */
	public int countVehiclesOnSiding(long sidingId) {
		final int[] count = {0};
		sidings.forEach(siding -> siding.iterateVehicles(vehicle -> {
			if (vehicle.vehicleExtraData.getSidingId() == sidingId) {
				count[0]++;
			}
		}));
		return count[0];
	}

	/** 中控指令用：某条股道上那辆车的编组（车上实际挂了几节什么车），没有车时返回空表。 */
	public java.util.List<String> mmtrCarsOnSiding(long sidingId) {
		final java.util.ArrayList<String> out = new java.util.ArrayList<>();
		sidings.forEach(siding -> siding.iterateVehicles(vehicle -> {
			if (vehicle.vehicleExtraData.getSidingId() == sidingId) {
				for (final org.mtr.core.data.VehicleCar car : vehicle.vehicleExtraData.immutableVehicleCars) {
					out.add(car.getVehicleId());
				}
			}
		}));
		return out;
	}

	/** 中控指令入口：执行一条名词打头的指令，返回可核对的结果。 */
	public org.mtr.core.mmtr.command.MmtrCommandDispatcher.Result mmtrExecuteCommand(String command) {
		return org.mtr.core.mmtr.command.MmtrCommandDispatcher.execute(this, command);
	}

	/**
	 * 中控指令用：从磁盘重读列车表（{@code manifest reload}）。
	 *
	 * <p>存在的理由很实际：列车表原先只在启动时读一次，所以"改了文件"必须重启才生效。
	 * 有了这个方法，改文件之后一条指令就能生效。</p>
	 */
	public boolean mmtrReloadRollingStockManifest() {
		try {
			if (mmtrManifestPath == null || !java.nio.file.Files.exists(mmtrManifestPath)) {
				return false;
			}
			final org.mtr.core.mmtr.manifest.MmtrRollingStockManifest reloaded = org.mtr.core.mmtr.manifest.MmtrRollingStockManifest.fromFile(mmtrManifestPath);
			mmtrRollingStock = reloaded;
			System.out.println("[MMTR-MFST] manifest reloaded on demand (" + reloaded.depots.size() + " depot(s), " + reloaded.sidingEntryCount() + " siding(s))");
			return true;
		} catch (Exception e) {
			System.out.println("[MMTR-MFST] manifest reload failed: " + e.getMessage());
			return false;
		}
	}

	/**
	 * 中控指令用：把一条股道写进列车表（热改 + 立刻生成 + 落盘）。
	 *
	 * <p>三条动作缺一不可，否则会出现"配了但车没出来"或"重启后又没了"这类问题：
	 * 先写内存里的表（当前进程立刻可用），再按它生成（拿到车辆 id 可核对），最后落盘（下次启动还在）。</p>
	 *
	 * @param carIds 编组里的车型；为空时沿用该股道已有的模板
	 * @return 生成是否成功
	 */
	public boolean mmtrManifestAddSiding(long depotId, long sidingId, java.util.List<String> carIds) {
		final org.mtr.core.data.Siding siding = org.mtr.core.mmtr.command.MmtrCommandLookup.findSiding(this, sidingId);
		if (siding == null) {
			return false;
		}
		final java.util.List<String> cars = new java.util.ArrayList<>();
		if (carIds == null || carIds.isEmpty()) {
			for (final org.mtr.core.data.VehicleCar car : siding.getVehicleCars()) {
				cars.add(car.getVehicleId());
			}
		} else {
			cars.addAll(carIds);
		}
		if (cars.isEmpty()) {
			return false;
		}
		final String depotName = siding.area == null ? "" : siding.area.getName();
		mmtrRollingStock.putSiding(depotId, depotName, sidingId, siding.getName(), cars, 16);
		persistMmtrRollingStockManifest();
		// 立刻生成：把同一条指令交给统一执行器，于是"热改"与"启动播种"行为必然一致
		final org.mtr.core.mmtr.command.MmtrCommandDispatcher.Result spawnResult = mmtrExecuteCommand(
			"vehicle spawn " + String.join(" ", cars) + " --depot=" + depotId + " --siding=" + sidingId);
		return spawnResult.ok;
	}

	/** 中控指令用：从列车表删条目并落盘。 */
	public boolean mmtrManifestRemove(long depotId, long sidingId) {
		final boolean removed = mmtrRollingStock.remove(depotId, sidingId);
		if (removed) {
			persistMmtrRollingStockManifest();
		}
		return removed;
	}

	/** 把当前列车表写回磁盘（热改之后调用，保证下次启动仍是这份配置）。 */
	public void persistMmtrRollingStockManifest() {
		if (mmtrManifestPath == null) {
			return;
		}
		try {
			mmtrRollingStock.save(mmtrManifestPath);
		} catch (Exception e) {
			System.out.println("[MMTR-MFST] manifest save failed: " + e.getMessage());
		}
	}

	// ---------------------------------------------------------------- 服务端运维（一键重启）

	/**
	 * 重启标记文件的位置（与 {@code scripts/dev-server.ps1} 约定的一致）。
	 *
	 * <p>放在 {@code game/fabric/run/}（启动器的工作目录）：启动器只认这个位置，所以这里必须算准。
	 * 不能靠"往上数几级"——存档路径是 {@code <run>/world/mtr/minecraft/<dimension>/mmtr-rolling-stock.json}，
	 * 层级一旦变（维度名、存档布局）就会算错。这里改成**按目录名找**：从存档路径往上走，
	 * 第一个名为 {@code run} 的目录就是它。</p>
	 */
	private java.nio.file.Path mmtrRestartMarker() {
		java.nio.file.Path current = mmtrManifestPath == null ? null : mmtrManifestPath.toAbsolutePath().getParent();
		while (current != null) {
			final java.nio.file.Path name = current.getFileName();
			if (name != null && name.toString().equals("run")) {
				return current.resolve("mmtr-restart.request");
			}
			current = current.getParent();
		}
		// 找不到 run 目录（例如测试环境用临时路径）：退回到进程工作目录
		return java.nio.file.Paths.get("mmtr-restart.request").toAbsolutePath();
	}

	/** 重启标记的路径（给指令回复显示用）。 */
	public String mmtrRestartMarkerPath() {
		return mmtrRestartMarker().toAbsolutePath().toString();
	}

	/** 请求重启：写标记文件 + 请求优雅停机；启动器看到标记会再拉起来。 */
	public void mmtrRequestRestart(int delaySeconds) {
		try {
			final java.nio.file.Path marker = mmtrRestartMarker();
			if (marker.getParent() != null) {
				java.nio.file.Files.createDirectories(marker.getParent());
			}
			java.nio.file.Files.writeString(marker, "restart requested at " + java.time.Instant.now() + System.lineSeparator()
				+ "服务端会在退出后由启动器（scripts/dev-server.ps1）重新拉起。" + System.lineSeparator());
			System.out.println("[MMTR-SRV] restart marker written: " + marker.toAbsolutePath());
		} catch (Exception e) {
			System.out.println("[MMTR-SRV] failed to write restart marker: " + e.getMessage());
		}
		mmtrRequestShutdown(Math.max(1, delaySeconds));
	}

	/** 请求优雅停机（不写重启标记）。 */
	public void mmtrRequestShutdown(int delaySeconds) {
		mmtrShutdownAtMillis = getCurrentMillis() + Math.max(1, delaySeconds) * 1000L;
		System.out.println("[MMTR-SRV] shutdown requested, will stop in " + delaySeconds + "s");
	}

	/** 清掉可能残留的重启标记（"只停机"时必须做，否则启动器会误判成重启）。 */
	public void mmtrClearRestartMarker() {
		try {
			java.nio.file.Files.deleteIfExists(mmtrRestartMarker());
		} catch (Exception e) {
			System.out.println("[MMTR-SRV] failed to clear restart marker: " + e.getMessage());
		}
	}

	/**
	 * 停机标记文件（与 {@code scripts/dev-server.ps1} 约定）——"是你要我停的"。
	 *
	 * <h3>为什么停机也需要一个标记</h3>
	 * <p>启动器在一轮结束后要判断"该不该再拉一次"。原来只看两件事：有没有重启标记、
	 * 8888 有没有应答。可是 {@code server stop} 之后这两件事都指向"没起来"——8888 当然不应答，
	 * 也没有重启标记——于是启动器把**用户主动停机**当成了"构建失败"去重试，白起了一轮。
	 * 实测就是这么被触发的（日志里"第 1 轮结束：8888 应答=False → 第 1 次重试"）。</p>
	 *
	 * <p>所以意图要在两边都说清楚：重启写重启标记，停机写停机标记。文件是两边都能读、
	 * 也看得见的东西，比"猜日志"可靠。</p>
	 */
	private java.nio.file.Path mmtrStopMarker() {
		return mmtrRestartMarker().resolveSibling("mmtr-stop.request");
	}

	/** 请求停机：写停机标记 + 请求优雅停机；启动器看到它就知道不要再拉起来，也不会当成失败去重试。 */
	public void mmtrRequestStopWithMarker(int delaySeconds) {
		try {
			final java.nio.file.Path marker = mmtrStopMarker();
			if (marker.getParent() != null) {
				java.nio.file.Files.createDirectories(marker.getParent());
			}
			java.nio.file.Files.writeString(marker, "stop requested at " + java.time.Instant.now() + System.lineSeparator()
				+ "这是**主动停机**：启动器不要再拉起来，也不要当成启动失败去重试。" + System.lineSeparator());
			System.out.println("[MMTR-SRV] stop marker written: " + marker.toAbsolutePath());
		} catch (Exception e) {
			System.out.println("[MMTR-SRV] failed to write stop marker: " + e.getMessage());
		}
		mmtrRequestShutdown(Math.max(1, delaySeconds));
	}

	/**
	 * 是否已经到"该停机"的时刻（游戏端每 tick 调用）。
	 *
	 * <p>放在这里而不是直接 {@code System.exit}：停机必须是**优雅**的 —— 存档、断开连接、
	 * 通知客户端都走服务端自己的流程，所以由游戏端拿到这个信号后调用 {@code MinecraftServer.stop(false)}。
	 */
	public boolean mmtrShutdownDue() {
		return mmtrShutdownAtMillis > 0 && getCurrentMillis() >= mmtrShutdownAtMillis;
	}

	/** 停机时刻（0 = 没有停机请求）。 */
	private long mmtrShutdownAtMillis;


	/**
	 * MMTR deterministic stepping: advance the simulation by exactly millisElapsed simulation
	 * milliseconds, independent of the host wall clock. tick() chases the wall clock, so in fast
	 * headless loops most ticks advance 0 ms and physics freezes; this seam is the deterministic
	 * entry point used by tests and future headless task servers. Internally it slices into
	 * one-second steps, matching the engine's own catch-up cadence.
	 */
	public void step(long millisElapsed) {
		while (millisElapsed > 0) {
			final long slice = Math.min(millisElapsed, MILLIS_PER_SECOND);
			tick(slice);
			millisElapsed -= slice;
		}
	}
	/**
	 * @param gameMillis       the number of real-time milliseconds since midnight of the in-game time
	 * @param gameMillisPerDay the total number of real-time milliseconds of one in-game day
	 * @param isTimeMoving     whether the daylight cycle is on
	 */
	public void setGameTime(long gameMillis, long gameMillisPerDay, boolean isTimeMoving) {
		this.gameMillis = gameMillisPerDay > 0 ? gameMillis % gameMillisPerDay : gameMillis;
		this.gameMillisPerDay = gameMillisPerDay;
		this.isTimeMoving = isTimeMoving;
		lastSetGameMillisMidnight = getCurrentMillis() - gameMillis;
	}

	/**
	 * @return milliseconds after epoch of the first midnight in-game
	 */
	public long getMillisOfGameMidnight() {
		return gameMillisPerDay > 0 && isTimeMoving ? Math.max(0, lastSetGameMillisMidnight - lastSetGameMillisMidnight / gameMillisPerDay * gameMillisPerDay) : 0;
	}

	/**
	 * @return the game hour (0-23)
	 */
	public int getGameHour() {
		return getGameHourAt(getCurrentMillis());
	}

	/**
	 * @param simulationMillis target simulation timestamp
	 * @return normalized in-game milliseconds in [0, gameMillisPerDay), projected from current
	 * simulator time if game time is moving
	 */
	public long getGameMillisAt(long simulationMillis) {
		if (gameMillisPerDay <= 0) {
			return 0;
		}

		final long projectedGameMillis = isTimeMoving ? gameMillis + (simulationMillis - getCurrentMillis()) : gameMillis;
		return Math.floorMod(projectedGameMillis, gameMillisPerDay);
	}

	/**
	 * @param simulationMillis target simulation timestamp
	 * @return projected in-game hour (0-23) at {@code simulationMillis}
	 */
	public int getGameHourAt(long simulationMillis) {
		return gameMillisPerDay > 0 ? (int) (getGameMillisAt(simulationMillis) * HOURS_PER_DAY / gameMillisPerDay) : 0;
	}

	/**
	 * Map an in-game day offset (0..{@link Utilities#MILLIS_PER_DAY}) to an absolute simulation
	 * timestamp using the current game-day scale.
	 */
	public long getSimulationMillisAtGameDayOffset(long gameDayOffsetMillis) {
		if (gameMillisPerDay <= 0) {
			return getCurrentMillis();
		}

		return getMillisOfGameMidnight() + Math.floorMod(gameDayOffsetMillis, (long) MILLIS_PER_DAY) * gameMillisPerDay / MILLIS_PER_DAY;
	}

	/**
	 * For in-game schedules, when game time is paused we pin frequency lookup to the current game
	 * hour; when moving, use the iterated schedule hour.
	 */
	public int getScheduleFrequencyHour(int iteratedHour) {
		return isTimeMoving ? iteratedHour : getGameHour();
	}

	/**
	 * Limit how many new passenger direction requests enter CSA per simulation tick to keep latency
	 * stable on very large maps.
	 */
	public boolean tryConsumePassengerDirectionsRequestBudget() {
		if (currentPassengerDirectionsRequests >= MAX_PASSENGER_DIRECTIONS_REQUESTS) {
			return false;
		} else {
			currentPassengerDirectionsRequests++;
			return true;
		}
	}

	/**
	 * Queue a {@link Runnable} to execute on the simulator thread at the start of the next tick.
	 * Used by HTTP servlets and the embedding mod to safely mutate simulator state without
	 * crossing threads.
	 */
	public void run(Runnable runnable) {
		queuedRuns.put(runnable);
	}

	/**
	 * 把一条**网页**任务排到下一次 tick（见 {@link #mmtrProcessWebRuns}）。
	 *
	 * <p>与 {@link #run(Runnable)} 分开是刻意的：那一队里是游戏侧的活（客户端保活、玩家会话清理），
	 * 它们必须在那一 tick 里做完；网页这一队可能很贵（一次地图 JSON 就是几十毫秒），
	 * 所以它单独排队、按时间片跑，并且 tick 已经很慢时整队让开。</p>
	 *
	 * @param label 接口名，只用于慢任务点名日志
	 */
	public void runWeb(String label, Runnable runnable) {
		queuedWebRuns.put(new WebRun(label, runnable));
	}

	/**
	 * 网页排队任务的时间片排空（notes/172）。
	 *
	 * <p>规矩三条：① 每 tick 至少跑一条（否则服务器一忙，网页队列就永远不动，快照也永远刷不新）；
	 * ② 超过 {@link #WEB_RUN_BUDGET_NANOS} 就把剩下的留给下一 tick；③ 这一 tick 自己已经超过
	 * {@link #WEB_RUN_SKIP_TICK_MILLIS} 时一条都不跑 —— 网页那边有快照兜底（读旧一拍），游戏没有。</p>
	 *
	 * <p>队列深度是有界的：同一路接口在窗口内的重复请求在 {@link WebFeed} 里就被合并了，
	 * 所以"开十个标签"不会变成十条排队任务。</p>
	 */
	private void mmtrProcessWebRuns(long tickStartNanos) {
		/*
		 * 三道闸，按"最该让开"的顺序：
		 *   ① 刚跑过一个重活（冷却中）⇒ 整队让开，先把游戏还给玩家；
		 *   ② 这一 tick 自己已经很慢 ⇒ 整队让开；
		 *   ③ 否则按时间片跑，至少一条。
		 * 网页那三路都有快照兜底（读旧一拍），游戏没有 —— 所以让开的永远是网页。
		 */
		final long wallClockMillis = System.currentTimeMillis();
		if (queuedWebRuns.size() > 0 && wallClockMillis < webRunCooldownUntilMillis) {
			webRunShed++;
			webRunDeferred = queuedWebRuns.size();
			org.mtr.core.mmtr.probe.MmtrProbe.hit("web.shed");
			org.mtr.core.mmtr.probe.MmtrProbe.peak("web.queue", webRunDeferred);
			return;
		}

		final long currentNanos = System.nanoTime();
		if ((currentNanos - tickStartNanos) / NANOS_PER_MILLISECOND >= WEB_RUN_SKIP_TICK_MILLIS && queuedWebRuns.size() > 0) {
			webRunSkippedTicks++;
			webRunDeferred = queuedWebRuns.size();
			org.mtr.core.mmtr.probe.MmtrProbe.hit("web.skipSlowTick");
			org.mtr.core.mmtr.probe.MmtrProbe.peak("web.queue", webRunDeferred);
			return;
		}
		org.mtr.core.mmtr.probe.MmtrProbe.peak("web.queue", queuedWebRuns.size());

		final long deadlineNanos = currentNanos + WEB_RUN_BUDGET_NANOS;
		int processed = 0;
		long maxMillis = 0;
		String maxLabel = "";
		while (processed == 0 || System.nanoTime() < deadlineNanos) {
			final WebRun webRun = queuedWebRuns.poll();
			if (webRun == null) {
				break;
			}
			final long runStartNanos = System.nanoTime();
			try {
				webRun.run();
			} catch (Throwable e) {
				// 一条网页任务炸了不许把整个 tick 带走（tick() 外面那个 catch 会中止本 tick 剩下的全部工作）。
				log.error("Web request {} failed while running on the simulator thread", webRun.label, e);
			}
			final long runMillis = (System.nanoTime() - runStartNanos) / NANOS_PER_MILLISECOND;
			webRunCount++;
			webRunLastMillis = runMillis;
			processed++;
			if (runMillis > maxMillis) {
				maxMillis = runMillis;
				maxLabel = webRun.label;
			}
		}

		webRunDeferred = queuedWebRuns.size();
		if (maxMillis > webRunMaxMillis) {
			webRunMaxMillis = maxMillis;
			webRunMaxLabel = maxLabel;
		}
		if (maxMillis >= WEB_RUN_COOLDOWN_TRIGGER_MILLIS) {
			webRunCooldownUntilMillis = System.currentTimeMillis() + WEB_RUN_COOLDOWN_MILLIS;
		}
		if (maxMillis >= WEB_RUN_WARN_MILLIS && getCurrentMillis() - webRunWarnedAtMillis >= WEB_RUN_WARN_INTERVAL_MILLIS) {
			webRunWarnedAtMillis = getCurrentMillis();
			log.warn("网页任务 {} 在模拟线程上花了 {} ms（本 tick 共 {} 条，队列还剩 {}；这一个 tick 里其他所有工作都被它推迟了）", maxLabel, maxMillis, processed, webRunDeferred);
		}
	}

	/** 从快照答出去的请求数（诊断）。 */
	public long getMmtrWebServedFromSnapshot() {
		return mmtrWebFeed.getServedFromSnapshot();
	}

	/** 快照重建次数（诊断）。 */
	public long getMmtrWebPublished() {
		return mmtrWebFeed.getPublished();
	}

	/** 累计在模拟线程上跑过的网页任务数（诊断）。 */
	public long getMmtrWebRunCount() {
		return webRunCount;
	}

	/** 上一条网页任务花了多少毫秒（用例与诊断）。 */
	public long getMmtrWebRunLastMillis() {
		return webRunLastMillis;
	}

	/** 网页任务队列当前深度（诊断）。 */
	public int getMmtrWebQueueSize() {
		return queuedWebRuns.size();
	}

	/** 因为 tick 已经很慢而整队让开的次数（诊断）。 */
	public long getMmtrWebSkippedTicks() {
		return webRunSkippedTicks;
	}

	/** 因为刚跑过一个重活、处在冷却里而整队让开的次数（诊断）。 */
	public long getMmtrWebShed() {
		return webRunShed;
	}

	/**
	 * Enqueue a client-to-server message; processed during the next tick.
	 */
	public void sendMessageC2S(QueueObject queueObject) {
		messageQueueC2S.put(queueObject);
	}

	/**
	 * Push a server-to-client message into the outgoing queue. The optional {@code consumer} is
	 * invoked on the simulator thread when the matching response payload of type
	 * {@code responseDataClass} arrives back from the client.
	 */
	public <T extends SerializedDataBase> void sendMessageS2C(String key, SerializedDataBase data, @Nullable Consumer<T> consumer, @Nullable Class<T> responseDataClass) {
		messageQueueS2C.put(new QueueObject(key, data, consumer == null ? null : responseData -> run(() -> consumer.accept(responseData)), responseDataClass));
	}

	/**
	 * Drain all pending S2C messages and feed each into {@code callback}.
	 */
	public void processMessagesS2C(Consumer<QueueObject> callback) {
		messageQueueS2C.process(callback);
	}

	/**
	 * Drop the {@link Client} record for {@code uuid} - the player has left this dimension or the
	 * server. The record's "already sent" bookkeeping describes the departed session, and a rejoining
	 * player starts from an empty client dataset, so keeping the record left every stationary
	 * vehicle/rail/passenger unsent (trains and rails stayed invisible until something moved).
	 *
	 * @param uuid the player to forget
	 * @return whether a record was actually removed
	 */
	public boolean removeClient(UUID uuid) {
		// A player who leaves is not riding anything any more.
		//
		// This is the other half of Vehicle's per-tick cleanup (`removeRidingEntitiesIf(!isRiding)`): without
		// it, the ride registration survives the session, the riding entity is never removed, and with it the
		// DRIVER flag of a cab the player once occupied survives for ever. Measured in game (2026-09-25): a
		// consist whose cab had been claimed once could not be driven again by anyone, because the occupation
		// lock still counted the ghost as sitting in the driver's seat -
		// "另一名司机正持有操纵权（占用锁；持有者仍在司机位上）" on every single request.
		stopRiding(uuid);
		return clients.removeIf(client -> client.uuid.equals(uuid));
	}

	/**
	 * @return whether the entity {@code uuid} is currently riding {@code vehicleId}
	 */
	public boolean isRiding(UUID uuid, long vehicleId) {
		return ridingVehicleIds.getLong(uuid) == vehicleId;
	}

	/**
	 * Record that the entity {@code uuid} has boarded {@code vehicleId}.
	 */
	public void ride(UUID uuid, long vehicleId) {
		ridingVehicleIds.put(uuid, vehicleId);
	}

	/**
	 * Record that the entity {@code uuid} has dismounted whatever vehicle it was riding.
	 */
	public void stopRiding(UUID uuid) {
		ridingVehicleIds.removeLong(uuid);
	}

	/**
	 * @return whether the route is currently considered jammed for pathfinding purposes.
	 */
	public boolean isRouteJammed(long routeId) {
		return routeId != 0 && jammedRouteIds.contains(routeId);
	}

	/**
	 * Mark a route as jammed for the current tick so CSA/path searches avoid it.
	 */
	public void markRouteJammed(long routeId) {
		if (routeId != 0) {
			jammedRouteIds.add(routeId);
		}
	}

	/**
	 * @param uuid riding entity to look up
	 * @return the next platform of the vehicle being ridden by {@code uuid}, or {@code null} if
	 * the entity is not riding anything or its vehicle has no upcoming platform.
	 */
	@Nullable
	public Platform getNextPlatformOfRidingVehicle(UUID uuid) {
		final @Nullable Platform[] platform = {null};
		sidings.forEach(siding -> siding.iterateVehiclesAndRidingEntities((vehicleExtraData, vehicleRidingEntity) -> {
			if (vehicleRidingEntity.uuid.equals(uuid)) {
				final Platform checkPlatform = platformIdMap.get(vehicleExtraData.getNextPlatformId());
				if (checkPlatform != null) {
					platform[0] = checkPlatform;
				}
			}
		}));
		return platform[0];
	}

	/**
	 * Simulates the system in one-second intervals until the simulation is all caught up
	 *
	 * @return the number of ticks it took
	 */
	private int tickUntilCaughtUp() {
		int ticks = 0;
		while (true) {
			ticks++;
			final long totalDifference = System.currentTimeMillis() - getCurrentMillis();
			if (totalDifference > MILLIS_PER_SECOND) {
				tick(MILLIS_PER_SECOND);
			} else {
				tick(totalDifference);
				return ticks;
			}
		}
	}

	/**
	 * The main simulation tick loop
	 *
	 * @param millisElapsed the number of milliseconds since the last tick
	 */
	private void tick(long millisElapsed) {
		// 本 tick 的起点：网页任务的时间片与"整队让开"都按它算（notes/172）。
		final long tickStartNanos = System.nanoTime();
		// 探针帧起点（notes/337）：关着时是一次 volatile 读，开着时才开始计这一帧。
		org.mtr.core.mmtr.probe.MmtrProbe.frameBegin();
		lastMillis = getCurrentMillis();
		setCurrentMillis(lastMillis + millisElapsed);
		currentPassengerDirectionsRequests = 0;

		try {
			long probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			vehiclePositions.forEach(vehiclePositionsForTransportMode -> {
				if (!vehiclePositionsForTransportMode.isEmpty()) {
					vehiclePositionsForTransportMode.removeFirst();
				}
				vehiclePositionsForTransportMode.add(new Object2ObjectAVLTreeMap<>());
			});
			org.mtr.core.mmtr.probe.MmtrProbe.end("positions.roll", probeT);

			probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			rails.forEach(rail -> rail.tick1(this));
			org.mtr.core.mmtr.probe.MmtrProbe.end("rails.tick1", probeT);
			probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			rails.forEach(rail -> rail.tick2(millisElapsed));
			org.mtr.core.mmtr.probe.MmtrProbe.end("rails.tick2", probeT);
			// MTR depot auto path-generation pipeline removed (auto rebuilt on Motion/tasks): nothing auto-dispatches.

			// Try setting a siding's default path data
			// If a siding doesn't have a rail associated with it, it should be removed from the data set
			probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			final boolean removedSidings = sidings.removeIf(Siding::tick);
			if (removedSidings) {
				sync();
			}
			org.mtr.core.mmtr.probe.MmtrProbe.end("sidings.tick", probeT);

			jammedRouteIds.clear();
			// MTR depot path auto-generation removed (auto rebuilt on Motion/tasks): stock runs on
			// Motion legs, not depot-generated route legs.
			probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			sidings.forEach(siding -> siding.simulateVehicles(millisElapsed, vehiclePositions.get(siding.getTransportModeOrdinal())));
			org.mtr.core.mmtr.probe.MmtrProbe.end("vehicles.simulate", probeT);
			// C8 自动车钩: after the vehicle simulation (the surgery unregisters the trailing train, so
			// it must not run while a siding iterates its vehicles), let trains with automatic couplers
			// latch onto the rake they have drawn up to under a 调车授权.
			probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			org.mtr.core.mmtr.MmtrAutoCoupler.tick(this);
			org.mtr.core.mmtr.probe.MmtrProbe.end("autocoupler", probeT);
			/*
			 * notes/408 S1：**值守状态机**（"到站停稳 → 交接 / 自动交还 / 清理失效认领"）。
			 *
			 * <p>插入点刻意选在这里（车辆走行完、作业调度器还没跑）：于是"这一拍到站"对值守与
			 * 作业调度器是**同一拍**看到的事实 —— 不会一边已经进了下一步、另一边还在等上一站。</p>
			 *
			 * <p>**不跟着 {@code mmtrAiJobStepsEnabled} 那道门**：值守是"玩家与列车"的关系，
			 * 作业调度器是"AI 图表跑不跑"；把值守挂在作业调度器的开关后面，等于
			 * "关掉 AI 排班 ⇒ 玩家也认领不了车"，那两件事没有一点关系。</p>
			 */
			probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			mmtrDuties.tick(getCurrentMillis());
			org.mtr.core.mmtr.probe.MmtrProbe.end("duties", probeT);
			/*
			 * notes/411 钥匙兜底网：表里只会有"刚被拔了钥匙、还挂着在跑自动任务"的车（正常是空的）。
			 * 稳态成本只是一次取模 + 一次 isEmpty —— 判断与重试都在 MmtrAutoKeyWatch 里按 250 ms 节拍做。
			 */
			probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			mmtrAutoKeyWatch.tick(this);
			org.mtr.core.mmtr.probe.MmtrProbe.end("autoKeys", probeT);
			probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			mmtrPeriodicTaskSources.forEach(source -> source.tick(getCurrentMillis(), this));
			org.mtr.core.mmtr.probe.MmtrProbe.end("periodic.sources", probeT);
			// P4：时刻表派发（P 系列）。输入有错/未配置时内部直接返回，不做任何事。
			probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			mmtrTickPlanDispatchers();
			org.mtr.core.mmtr.probe.MmtrProbe.end("plan.dispatchers", probeT);
			probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			mmtrEnsurePointDefaults();
			org.mtr.core.mmtr.probe.MmtrProbe.end("points.defaults", probeT);
			probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			mmtrSyncTurnoutPositionsToGrants();
			org.mtr.core.mmtr.probe.MmtrProbe.end("points.syncPhysical", probeT);
			// 位置队列自愈：净空被挡时排队的那条申请必须**自己**再试（修前只有"持有者释放/过期"两个事件
			// 会推进队列 ⇒ 出现"位置空着、车排第一却永远轮不到"，实测车停了三分钟，只能人工扳岔救）。
			probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			mmtrPointAuthority.retryPhysicalQueues(getCurrentMillis());
			org.mtr.core.mmtr.probe.MmtrProbe.end("points.retryQueues", probeT);
			probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			mmtrRefreshSignalAspectView();
			org.mtr.core.mmtr.probe.MmtrProbe.end("signal.aspectView", probeT);
			if (mmtrJobScheduler != null && mmtrAiJobStepsEnabled) {
				probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
				mmtrJobScheduler.tick(getCurrentMillis(), this);
				org.mtr.core.mmtr.probe.MmtrProbe.end("jobs.scheduler", probeT);
			}
			/*
			 * **同一 tick 内再同步一次道岔位置**（2026-09-17 现场问："为什么道岔请求慢半拍、不是换向后马上完成"）。
			 *
			 * <p>上面那次同步在**车辆走行之后、而在这两步之前**：位置队列的重试（{@code retryPhysicalQueues}）
			 * 与作业调度器（换端之后挂下一步、并在同一 tick 里把新计划申请出去）都发生在它之后 ——
			 * 它们拿到的授予要等到**下一 tick** 才被"跟着授权扳岔"同步到世界上。50 ms 一步，但现场可见：
			 * 换端完成后，道岔的物理位置要慢半拍才动，而车已经在等它了。</p>
			 *
			 * <p>这个方法本身"没变就不动 + 只在真变了才落盘"，重复调用是幂等的，所以直接补一次即可
			 * （不动原来那一次：车辆走行之后立刻同步，是本 tick 里绝大多数授予该有的时机）。</p>
			 */
			probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			mmtrSyncTurnoutPositionsToGrants();
			org.mtr.core.mmtr.probe.MmtrProbe.end("points.syncPhysical2", probeT);
			probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			clients.forEach(client -> client.sendUpdates(this));
			org.mtr.core.mmtr.probe.MmtrProbe.end("clients.sendUpdates", probeT);

			if (autoSave) {
				probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
				save(true);
				org.mtr.core.mmtr.probe.MmtrProbe.end("save.auto", probeT);
				autoSave = false;
			}

			probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			lifts.forEach(lift -> lift.tick(millisElapsed));
			landmarks.forEach(Landmark::tick);
			homes.forEach(Home::tick);
			org.mtr.core.mmtr.probe.MmtrProbe.end("lifts.landmarks.homes", probeT);

			// Process queued runs
			probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			queuedRuns.process(Runnable::run);
			org.mtr.core.mmtr.probe.MmtrProbe.end("queued.runs", probeT);

			/*
			 * 网页排队任务（notes/172）：单独排队、按时间片跑，tick 已经很慢时整队让开。
			 * 放在游戏侧排队任务**之后**：网页慢了只是网页旧一拍，游戏侧的保活/清理不能被网页挡住。
			 */
			probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			mmtrProcessWebRuns(tickStartNanos);
			org.mtr.core.mmtr.probe.MmtrProbe.end("web.runs", probeT);

			// 快照清扫：长时间没人看就把发布的那几份丢掉（大 JSON 不该一直占着内存）
			if (++webFeedSweepTickCounter >= WEB_FEED_SWEEP_TICKS) {
				webFeedSweepTickCounter = 0;
				mmtrWebFeed.sweep();
			}

			// Directions
			directionsFinder.tick();

			// Process messages
			messageQueueC2S.process(queueObject -> queueObject.runCallback(OperationProcessor.process(queueObject.key, queueObject.data, this)));

			// MMTR health watchdog (every ~5 seconds at 20 TPS)
			if (++watchdogTickCounter >= MMTR_WATCHDOG_INTERVAL_TICKS) {
				watchdogTickCounter = 0;
				watchdogHealthCheck();
			}
			/*
			 * 性能探针的窗口汇总（notes/337）。
			 *
			 * <p>放在这里 —— 本 tick 所有工作都做完之后、**在 finally 之前** —— 是有意的：它是
			 * 唯一能看见"这一整帧花了多少"的位置。挂在 watchdog（每 100 tick）上而不是自己数 tick，
			 * 是为了让性能行与 [MMTR-HLTH] 健康行**同拍**：出问题时两张表说的是同一个 5 秒窗口，
			 * 不需要在两份日志之间对时间轴。</p>
			 */
			org.mtr.core.mmtr.probe.MmtrProbe.reportIfDue(mmtrProbeLabel);
		} catch (Throwable e) {
			log.fatal("", e);
		} finally {
			// 异常路径也要收帧：否则"这一帧"永远悬着，后面每一帧的耗时都算错。
			org.mtr.core.mmtr.probe.MmtrProbe.frameEnd();
		}
	}

	/**
	 * MMTR health watchdog: recounts the live simulation state and logs a one-line summary.
	 * Called automatically every {@value #MMTR_WATCHDOG_INTERVAL_TICKS} ticks and callable on
	 * demand (e.g. from an external watchdog process or tests).
	 */
	public void watchdogHealthCheck() {
		final int[] vehicles = {0};
		final int[] riders = {0};
		final int[] drivers = {0};
		final int[] overrides = {0};
		final int[] protections = {0};
		sidings.forEach(siding -> siding.iterateVehicles(vehicle -> {
			vehicles[0]++;
			if (vehicle.isMmtrOverrideActive()) {
				overrides[0]++;
			}
			if (vehicle.isMmtrProtectionFromSync()) {
				protections[0]++;
			}
			vehicle.vehicleExtraData.iterateRidingEntities(vehicleRidingEntity -> {
				if (vehicleRidingEntity.isOnVehicle()) {
					riders[0]++;
					if (vehicleRidingEntity.isDriver()) {
						drivers[0]++;
					}
				}
			});
		}));
		watchdogLastCheckAt = getCurrentMillis();
		watchdogVehicles = vehicles[0];
		watchdogRiders = riders[0];
		watchdogDrivers = drivers[0];
		watchdogMmtrOverrides = overrides[0];
		watchdogProtections = protections[0];
		watchdogJammedRoutes = jammedRouteIds.size();
		// 日志降噪 (notes/77): the counts are refreshed every 5 s for the ops UI and tests, but printing
		// them every 5 s made the heartbeat the last per-tick-ish noise in the real-machine log (three
		// simulators = 36 lines a minute). Print immediately whenever a counter is non-zero - that is
		// the state an operator must see - and otherwise at most once a minute.
		final boolean watchdogInteresting = watchdogRiders > 0 || watchdogDrivers > 0 || watchdogMmtrOverrides > 0 || watchdogProtections > 0 || watchdogJammedRoutes > 0;
		if (watchdogInteresting || getCurrentMillis() - watchdogLastLogAtMillis >= MMTR_WATCHDOG_LOG_INTERVAL_MILLIS) {
			watchdogLastLogAtMillis = getCurrentMillis();
			/*
			 * 网页那一组（notes/172）是这一行里唯一能回答"网页到底花了多少 tick"的字段：
			 *   webHits/webBuilds —— 快照答出去的次数 / 重建次数（前者远大于后者＝快照层真的在挡请求）；
			 *   webMaxMs/webSlow   —— 本窗口内最慢的一条网页任务与它的接口名（0/空 = 网页没进过 tick）；
			 *   webQueue/webSkipped/webShed —— 当前排队深度 / 因为 tick 太慢让开的次数 / 因为刚跑过重活冷却而让开的次数。
			 *
			 * <p>`vehicleSync` 是 notes/174 那套车辆同步协议的**档位**：`patches` = 静态只发一次、
			 * 动态只发变化字段；`full` = 每次发整份快照（`-Dmmtr.sync.patches=false` 的旧行为）。
			 * 为什么放进这一行：引擎自己的 `log.info` 在模组里**看不见**（log4j 的 provider 在
			 * 打包时被重定位/裁掉了，实测服务端日志里只有这一行 System.out 能看见）——
			 * 于是"这一次跑的是哪套协议"必须落在这一行上，否则只能靠抓包反推。</p>
			 */
			System.out.println("[MMTR-HLTH] t=" + getCurrentMillis()
				+ " vehicles=" + watchdogVehicles + " riders=" + watchdogRiders + " drivers=" + watchdogDrivers
				+ " mmtrOverrides=" + watchdogMmtrOverrides + " protections=" + watchdogProtections + " jammedRoutes=" + watchdogJammedRoutes
				+ " webHits=" + mmtrWebFeed.getServedFromSnapshot() + " webBuilds=" + mmtrWebFeed.getPublished()
				+ " webMaxMs=" + webRunMaxMillis + " webSlow=" + (webRunMaxLabel.isEmpty() ? "-" : webRunMaxLabel)
				+ " webQueue=" + webRunDeferred + " webSkipped=" + webRunSkippedTicks + " webShed=" + webRunShed
				+ " vehicleSync=" + (Boolean.parseBoolean(System.getProperty("mmtr.sync.patches", "true")) ? "patches" : "full"));
			webRunMaxMillis = 0;
			webRunMaxLabel = "";
		}
	}

	public long getWatchdogLastCheckAt() { return watchdogLastCheckAt; }
	public int getWatchdogVehicles() { return watchdogVehicles; }
	public int getWatchdogRiders() { return watchdogRiders; }
	public int getWatchdogDrivers() { return watchdogDrivers; }
	public int getWatchdogMmtrOverrides() { return watchdogMmtrOverrides; }
	public int getWatchdogProtections() { return watchdogProtections; }
	public int getWatchdogJammedRoutes() { return watchdogJammedRoutes; }

	private void save(boolean useReducedHash) {
		// Save all data
		final ObjectLongImmutablePair<Boolean> changedAndDuration = Utilities.measureDuration(() -> {
			final boolean changed1 = save(fileLoaderStations, useReducedHash);
			final boolean changed2 = save(fileLoaderPlatforms, useReducedHash);
			final boolean changed3 = save(fileLoaderSidings, useReducedHash);
			final boolean changed4 = save(fileLoaderRoutes, useReducedHash);
			final boolean changed5 = save(fileLoaderDepots, useReducedHash);
			final boolean changed6 = save(fileLoaderLifts, useReducedHash);
			final boolean changed7 = save(fileLoaderRails, useReducedHash);
			final boolean changed8 = save(fileLoaderHomes, useReducedHash);
			final boolean changed9 = save(fileLoaderLandmarks, useReducedHash);
			return changed1 || changed2 || changed3 || changed4 || changed5 || changed6 || changed7 || changed8 || changed9;
		});
		if (changedAndDuration.left() || !useReducedHash) {
			log.info("Save complete for {} in {} second(s)", dimension, (float) changedAndDuration.rightLong() / MILLIS_PER_SECOND);
		}

		// Save settings
		writeSettings.accept(new Settings(getCurrentMillis()));
		if (useReducedHash) {
			fileLoaderSettings.save(false);
		} else {
			save(fileLoaderSettings, false);
		}
	}

	private <T extends SerializedDataBaseWithId> boolean save(FileLoader<T> fileLoader, boolean useReducedHash) {
		final IntIntImmutablePair saveCounts = fileLoader.save(useReducedHash);
		final int changedCount = saveCounts.leftInt();
		if (changedCount > 0) {
			log.info("- Changed {}: {}", fileLoader.key, changedCount);
		}
		final int deletedCount = saveCounts.rightInt();
		if (deletedCount > 0) {
			log.info("- Deleted {}: {}", fileLoader.key, deletedCount);
		}
		return changedCount > 0 || deletedCount > 0;
	}

	private record FileLoaderHolder(
		FileLoader<Station> fileLoaderStations,
		FileLoader<Platform> fileLoaderPlatforms,
		FileLoader<Siding> fileLoaderSidings,
		FileLoader<Route> fileLoaderRoutes,
		FileLoader<Depot> fileLoaderDepots,
		FileLoader<Lift> fileLoaderLifts,
		FileLoader<Rail> fileLoaderRails,
		FileLoader<Home> fileLoaderHomes,
		FileLoader<Landmark> fileLoaderLandmarks
	) {
	}
}
