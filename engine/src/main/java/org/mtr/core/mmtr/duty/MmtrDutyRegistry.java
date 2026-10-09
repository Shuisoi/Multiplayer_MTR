package org.mtr.core.mmtr.duty;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Vehicle;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.mmtr.plan.MmtrPlanDispatcher;
import org.mtr.core.simulation.Simulator;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * **玩家值守状态机**（notes/408 §2，S1 是引擎那一半）。
 *
 * <h2>它解决什么</h2>
 * <p>在它之前，"谁在开哪趟车"散在三个各管一段的地方：作业单的司机绑定
 * （{@code MmtrJobScheduler.humanHold/driverUuid}）、驾驶室钥匙（{@code Vehicle} 的驾驶室乘务员记录）、
 * 任务执行者（{@code MmtrMission.executor}）。三处合起来也答不出**"玩家 X 现在是什么态"** ——
 * 钥匙在手里 ≠ 驾驶权在手里（notes/407 那个洞就是这个形状），而"我认领了某趟车、正在站台上等它"
 * 更是**一处都没记**。</p>
 *
 * <p>于是这里给每个玩家**至多一条**值守记录（{@link Duty}），六个状态是一条线、没有旁路：
 * {@code IDLE → WAITING|ABOARD → DRIVING ⇄ DRIVING_EXIT_ARMED → RELEASED → IDLE}。</p>
 *
 * <h2>三条铁律（notes/408 §2.3，逐条对应下面的代码）</h2>
 * <ol>
 *   <li><b>{@code DRIVING} 只能由引擎的接管成功进入</b>：{@link #takeoverFor} 是唯一入口，
 *       它调的是 {@link Simulator#mmtrJobTakeover(long, UUID)}，本类**一条判据都不自己写**
 *       （客户端复刻一遍引擎的判据，迟早当着司机的面互相矛盾 —— notes/215）。</li>
 *   <li><b>交接只在停稳时发生</b>：推进判据是 {@link Vehicle#mmtrDutyStoppedAtTarget()}
 *       （= 车停稳 **且** 有车节在目标站上），只在 {@link #tick} 里读。{@code ABOARD} 这个态存在的理由
 *       就是"人到了、车还在动"这个真实且常见的窗口。</li>
 *   <li><b>每次迁移落一条 {@code [MMTR-DUTY]} 日志</b>，带"谁 / 哪趟车 / 从哪个态到哪个态 / 为什么"；
 *       而且界面上那句话（{@link Duty#stateWord()}）与日志的措辞来自**同一个枚举**
 *       （{@link #stateWordOf}），否则"指示"就只是另一个人写的故事。</li>
 * </ol>
 *
 * <h2>引擎动不了玩家</h2>
 * <p>"把人送到车上 / 送到站台上"是游戏侧才能做的事，所以引擎只排一条 {@link PendingBoard} 待办
 * （{@link #pendingBoards()}）：游戏端每拍取走执行，执行完回调 {@link #arriveAndBoard}。
 * 本类因此在没有游戏端时也完全可跑（单测就是这么跑的）。</p>
 */
public final class MmtrDutyRegistry {

	public enum State { IDLE, WAITING, ABOARD, DRIVING, DRIVING_EXIT_ARMED, RELEASED }

	/**
	 * 只读视图：一次状态变化的**快照**。
	 *
	 * <p>换态时换对象、不原地改字段 —— 界面（PDA / HUD）拿着一条记录画一帧时，
	 * 不会画到"半新半旧"的一行（态是新的、原因是旧的）。</p>
	 */
	public static final class Duty {
		private final UUID playerUuid;
		private final String playerName;
		private final State state;
		private final long vehicleId;
		private final String jobId;
		private final String cabSpec;
		/**
		 * 这个驾驶室是**调用方显式给的**（派车指令 {@code duty assign} 的 {@code <驾驶室>} 参数），
		 * 还是引擎自己挑的（{@link Vehicle#mmtrPreferredCabSpec()}）。
		 *
		 * <h3>为什么必须记这一位，而不是"看它等不等于引擎挑的那个"</h3>
		 * <p>{@code cabSpec} 是一个**可空串**的字段（空串 = 不是编组体车 / 还没上车），所以
		 * "给了空串"与"没给"在值上分不开 —— 而 {@link #refreshPassiveFields} 每拍都会用
		 * {@code cabSpecOf(vehicleId)} 刷这一格。没有这一位的话，显式派到 {@code 1A} 的人会在
		 * 引擎的偏好值（例如方向一变就成了 {@code 1B}）下一次刷新时被**悄悄挪到另一端**，
		 * 于是"记住的驾驶室"与"实际用的驾驶室"分叉 —— notes/409 的 ① 明确不许这样。</p>
		 */
		private final boolean cabSpecExplicit;
		private final long waitPlatformId;
		private final long sinceMillis;
		private final String reason;
		/**
		 * **这次上车是不是"直接传送"**（{@code --now} / 面板"直接上车"；{@code false} = 站台接站）。
		 *
		 * <p>它不是给人看的字段，而是**交接判据的分岔**（用户口径 2026-10-09：交接时段按上车方式分）：
		 * 直接传送的人由引擎送进驾驶室，**没有"自己走进来拿钥匙"这个动作**，钥匙一直停在自动运行的
		 * 占位钥匙（SYSTEM）上 —— 拿钥匙当判据会让值守永远卡在 {@code ABOARD}（实机 19:57–20:01）。
		 * 这一条的交接时段是**车站停稳、门已开**（{@link #tickAboard}）。
		 * 站台接站的人是自己按 G 上车的，钥匙在他手里才是"他确实在那间驾驶室里"的现有证据，
		 * 判据照旧，而且那一条要先"把上一个玩家弹出车厢"。</p>
		 */
		private final boolean direct;

		/** 老签名（引擎自己挑驾驶室）：转调带 {@code cabSpecExplicit} 的那一个，值取 {@code false}。 */
		private Duty(UUID playerUuid, String playerName, State state, long vehicleId, String jobId, String cabSpec,
				long waitPlatformId, long sinceMillis, String reason) {
			this(playerUuid, playerName, state, vehicleId, jobId, cabSpec, false, waitPlatformId, sinceMillis, reason, false);
		}

		private Duty(UUID playerUuid, String playerName, State state, long vehicleId, String jobId, String cabSpec,
				boolean cabSpecExplicit, long waitPlatformId, long sinceMillis, String reason) {
			this(playerUuid, playerName, state, vehicleId, jobId, cabSpec, cabSpecExplicit, waitPlatformId, sinceMillis, reason, false);
		}

		private Duty(UUID playerUuid, String playerName, State state, long vehicleId, String jobId, String cabSpec,
				boolean cabSpecExplicit, long waitPlatformId, long sinceMillis, String reason, boolean direct) {
			this.playerUuid = playerUuid;
			this.playerName = playerName == null ? "" : playerName;
			this.state = state;
			this.vehicleId = vehicleId;
			this.jobId = jobId == null ? "" : jobId;
			this.cabSpec = cabSpec == null ? "" : cabSpec;
			this.cabSpecExplicit = cabSpecExplicit && !this.cabSpec.isEmpty();
			this.waitPlatformId = waitPlatformId;
			this.sinceMillis = sinceMillis;
			this.reason = reason == null ? "" : reason;
			this.direct = direct;
		}

		public UUID playerUuid() {
			return playerUuid;
		}

		/** 可空串（引擎不知道名字时）—— 游戏端用 {@code --name=} 把名字填上。 */
		public String playerName() {
			return playerName;
		}

		public State state() {
			return state;
		}

		/** 0 = 还没绑车。 */
		public long vehicleId() {
			return vehicleId;
		}

		/** 车次号（作业单名，如 {@code 00104}）；可空串。 */
		public String jobId() {
			return jobId;
		}

		/** 驾驶室写法 {@code <车节><A|B>}（例如 {@code 1A}）；可空串 = 不是编组体车 / 还没上车。 */
		public String cabSpec() {
			return cabSpec;
		}

		/**
		 * 这个驾驶室是**显式指定的**（派车指令给的、或他自己上车时选的那一间），而不是引擎按行进方向
		 * 挑的 —— 见字段上的注释：显式的值会被 {@link #refreshPassiveFields} 保护，不会被引擎的偏好值刷掉。
		 */
		public boolean cabSpecExplicit() {
			return cabSpecExplicit;
		}

		/**
		 * 这次上车是**直接传送**（{@code --now}）还是站台接站（{@code --wait}）—— 见字段上的注释：
		 * 它决定 {@link #tickAboard} 用哪一条交接判据，也决定游戏端要不要先"把上一个玩家弹出车厢"。
		 */
		public boolean direct() {
			return direct;
		}

		/** 站台接站：在哪个站台等（0 = 不适用）。 */
		public long waitPlatformId() {
			return waitPlatformId;
		}

		public long sinceMillis() {
			return sinceMillis;
		}

		/** 为什么停在这个态（人话，界面直接用）。 */
		public String reason() {
			return reason;
		}

		/** 界面词：空闲 / 等待接站 / 已上车·未获驾驶权 / 运转中 / 运转中·下一站退出 / 已退出。 */
		public String stateWord() {
			return stateWordOf(state);
		}
	}

	/** 引擎要对某个玩家做的待办：**三种含义不同**，见 {@link Kind}。 */
	public static final class PendingBoard {

		/**
		 * 这条待办要游戏端做什么。
		 *
		 * <p>第三种 {@link #EJECT} 是用户口径 2026-10-09 加上的："在车站接车时**先将上个玩家弹出车厢**后，
		 * 另一边玩家进入驾驶室" —— 引擎能收回驾驶室钥匙（{@code Vehicle.leaveMmtrCab}），
		 * 但**动不了玩家**，所以"把人弄下车"只能排一条待办交给游戏端。</p>
		 */
		public enum Kind {
			/** "直接上车"：把玩家**传送**到那趟车上并进驾驶室，然后回调 {@code arriveAndBoard}。 */
			DIRECT_BOARD,
			/** "站台接站"：**不传送任何人**，只播一句"车到站了，可以上车接管了"。 */
			NOTIFY_ARRIVED,
			/** **弹出车厢**：驾驶室已经交给别人了（钥匙已被引擎收回），请把这个人弄下车。 */
			EJECT
		}

		private final UUID playerUuid;
		private final long vehicleId;
		private final long platformId;
		private final Kind kind;

		private PendingBoard(UUID playerUuid, long vehicleId, long platformId, boolean direct) {
			this(playerUuid, vehicleId, platformId, direct ? Kind.DIRECT_BOARD : Kind.NOTIFY_ARRIVED);
		}

		private PendingBoard(UUID playerUuid, long vehicleId, long platformId, Kind kind) {
			this.playerUuid = playerUuid;
			this.vehicleId = vehicleId;
			this.platformId = platformId;
			this.kind = kind;
		}

		/** 把 {@code playerUuid} **弹出车厢**（用户口径：站台接车先把上一个玩家弹出车厢）。 */
		static PendingBoard eject(UUID playerUuid, long vehicleId) {
			return new PendingBoard(playerUuid, vehicleId, 0, Kind.EJECT);
		}

		public UUID playerUuid() {
			return playerUuid;
		}

		public long vehicleId() {
			return vehicleId;
		}

		/** 站台接站时 = 他认领的是哪个站台的下一停站；0 = 直接上车 / 弹出车厢。 */
		public long platformId() {
			return platformId;
		}

		public Kind kind() {
			return kind;
		}

		/** 这条待办是"把这个人弹出车厢"（见 {@link Kind#EJECT}）。 */
		public boolean isEject() {
			return kind == Kind.EJECT;
		}

		/**
		 * {@code true} = "直接上车"：游戏端应当把这个玩家**传送到那趟车上**并进驾驶室，
		 * 然后回调 {@link #arriveAndBoard}。
		 *
		 * <p>{@code false} = "站台接站"：**不要传送任何人**。玩家是自己走到站台上等的
		 * （他本来就在站台上，引擎既不知道也管不了他站在哪儿），所以这条待办的唯一作用是
		 * **让游戏端给那个玩家播一条话**："00104 已到 XX站1台，可以上车接管了"。
		 * 他按 G 上车、钥匙变成他的之后，引擎在下一 tick 自己就会把 {@code WAITING/ABOARD} 推到
		 * {@code DRIVING}（{@link #tickWaiting} 的"钥匙在他手里"那条出口）。</p>
		 */
		public boolean direct() {
			return kind == Kind.DIRECT_BOARD;
		}
	}

	/**
	 * **PDA 车次列表的一行 = 场上一趟车**（notes/408 §3 的列表页）。
	 *
	 * <h2>为什么这份列表必须由引擎算</h2>
	 * <p>游戏侧那份 {@code MinecraftClientData.vehicles} 是**按玩家位置同步**的镜像
	 * （见 {@code PacketMmtrBoardPlayer} 的类注释）：玩家站在几百格外时，那辆车根本不在镜像里。
	 * 所以"面板只显示附近的车次"这个 bug 的根因不在界面，而在**数据来源** ——
	 * 引擎里车辆的枚举是全局的（车挂在股道上），"场上有哪些车次"只有它答得出来。</p>
	 *
	 * <h2>字段口径</h2>
	 * <p>{@code dutyState} / {@code dutyCrew} 来自 {@link #operatorOf}（**引擎此刻**谁占着这趟车），
	 * 不是车上的 {@code mmtrDutyState} 镜像字段 —— 后者在"有人正在站台上等它"（{@code WAITING}）时
	 * 刻意是空串（见 {@link #pushDutySync}），而面板的按钮可用性要按**真实占用**判（§2.2 那张表）。
	 * 其余字段读车上的镜像（与 HUD 读的是同一份），一个字都不在客户端那边现算。</p>
	 */
	public static final class VehicleRow {
		private final long vehicleId;
		private final String jobId;
		private final String dutyState;
		private final String dutyCrew;
		private final String taskNote;
		private final String nextStation;
		private final double speedKmh;
		private final double distanceToStopM;
		private final int dutyWaiting;

		private VehicleRow(long vehicleId, String jobId, String dutyState, String dutyCrew, String taskNote,
				String nextStation, double speedKmh, double distanceToStopM, int dutyWaiting) {
			this.vehicleId = vehicleId;
			this.jobId = jobId == null ? "" : jobId;
			this.dutyState = dutyState == null ? "" : dutyState;
			this.dutyCrew = dutyCrew == null ? "" : dutyCrew;
			this.taskNote = taskNote == null ? "" : taskNote;
			this.nextStation = nextStation == null ? "" : nextStation;
			this.speedKmh = speedKmh;
			this.distanceToStopM = distanceToStopM;
			this.dutyWaiting = dutyWaiting;
		}

		public long vehicleId() {
			return vehicleId;
		}

		/** 车次号（作业单名，如 {@code 00104}）；列表里**不会有空串**（没有车次的车不收）。 */
		public String jobId() {
			return jobId;
		}

		/** 引擎状态名（{@link State#name()}）；空串 = 这趟车无人值守。 */
		public String dutyState() {
			return dutyState;
		}

		/** 值守人的 uuid 字符串；空串 = 无人（与 {@link #dutyState()} 一起读）。 */
		public String dutyCrew() {
			return dutyCrew;
		}

		/** 这一步的人话（{@code mmtrTaskNote}）；空串 = 没有在跑的一步。 */
		public String taskNote() {
			return taskNote;
		}

		/** 下一站站名；空串 = 后面不再有站台作业。 */
		public String nextStation() {
			return nextStation;
		}

		/** km/h（引擎内部是 m/ms，这里乘 3600 —— 界面不要自己做单位换算）。 */
		public double speedKmh() {
			return speedKmh;
		}

		/** 到停车点的距离（m）；{@code < 0} = 本趟没有停车目标（如实发 -1，不假装是 0 m）。 */
		public double distanceToStopM() {
			return distanceToStopM;
		}

		/** 几个玩家认领了这趟车（0 = 没人认领；口径见 {@code Vehicle.getMmtrDutyWaitingFromSync()}）。 */
		public int dutyWaiting() {
			return dutyWaiting;
		}
	}

	/** 界面词表：**只此一份** —— HUD / PDA / 动作栏播报 / 日志全从这里取。 */
	public static String stateWordOf(State state) {
		return switch (state) {
			case IDLE -> "空闲";
			case WAITING -> "等待接站";
			case ABOARD -> "已上车·未获驾驶权";
			case DRIVING -> "运转中";
			case DRIVING_EXIT_ARMED -> "运转中·下一站退出";
			case RELEASED -> "已退出";
		};
	}

	private final Simulator simulator;
	/**
	 * 驾驶室写法 {@code <车节序号><A|B>}（1 起）。
	 *
	 * <p>与 {@code MmtrCommandExecutor} 里 {@code cab <id> <车节><A|B>} 那一支**同一个写法**
	 * （{@code ^(\d*)([ab])$}，大小写不敏感）—— 两处若各写一套，就会出现在指令里能进的驾驶室
	 * 在派车里被拒（或反过来）这种谁也解释不清的现场。</p>
	 */
	private static final java.util.regex.Pattern CAB_SPEC_PATTERN =
		java.util.regex.Pattern.compile("^(\\d+)([ab])$", java.util.regex.Pattern.CASE_INSENSITIVE);
	private final HashMap<UUID, Duty> duties = new HashMap<>();
	/**
	 * 站台接站的待办按车索引：{@link #tick} 每拍都要看"这列车到站时有没有人在等它"。
	 * 线性扫全部值守在几十个玩家的规模下也无所谓，但它是每拍都跑的路径，按车分桶更直白。
	 */
	private final Long2ObjectOpenHashMap<ObjectArrayList<Duty>> waitingByVehicle = new Long2ObjectOpenHashMap<>();
	private final ObjectArrayList<PendingBoard> pendingBoards = new ObjectArrayList<>();
	/**
	 * **已经在"这一站"通知过他上车了**（键 = 玩家）。
	 *
	 * <h3>为什么需要这张闩（2026-10-09 实机：14 秒刷了 262 行同一句话）</h3>
	 * <p>第一版 {@code tickWaiting} 的守卫是"待办还在不在"（{@code !hasPendingBoard(uuid)}）——
	 * 它假定待办会一直在那儿直到有人处理。但游戏端**必须每拍消费掉它**
	 * （{@code MmtrDutyBoardWatch}：播一句话然后无论成败都调 {@link #markBoardDone}；不这样清，
	 * 待办就永远躺着、每一拍都重复播报）。两个都对的机制撞在一起就成了死循环：</p>
	 * <pre>
	 *   引擎：没待办 → 排队 + 记日志 → 游戏端：消费 + markBoardDone → 引擎：又没待办 → 又排队 + 又记日志 → …
	 * </pre>
	 * <p>于是玩家动作栏每秒被刷 ~19 条同一句话。所以"通知过没有"必须**由引擎自己按站记住**，
	 * 不能靠"待办还在不在"当证据 —— 待办是**交给游戏端的活**，它的生命周期不归引擎管。</p>
	 *
	 * <p>车一离站（或还没到站）就复位：下一站要重新通知一次（这正是要的行为）。</p>
	 */
	private final it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<UUID> notifiedAtStop = new it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<>();

	public MmtrDutyRegistry(Simulator simulator) {
		this.simulator = simulator;
	}

	@Nullable
	public Duty of(UUID playerUuid) {
		return playerUuid == null ? null : duties.get(playerUuid);
	}

	public List<Duty> all() {
		return new ObjectArrayList<>(duties.values());
	}

	/**
	 * 这趟车现在的值守占用（PDA 车次列表每行读它）。
	 *
	 * <p>一列车可能同时被两个人记着（一个在站台上等、一个在下车路上），所以这里有一个**明确的口径**：
	 * 谁此刻真正占着这趟车，谁就是它的值守人 —— 运转中 &gt; 下一站退出 &gt; 已上车未获权 &gt; 等待接站。
	 * 口径写在这里而不是让每个调用方自己挑，是因为 PDA 的按钮可用性就是读它决定的
	 * （notes/408 §2.2 那张表）。</p>
	 */
	@Nullable
	public Duty operatorOf(long vehicleId) {
		Duty best = null;
		for (final Duty duty : duties.values()) {
			if (duty.vehicleId() == vehicleId && (best == null || occupancyRank(duty.state()) > occupancyRank(best.state()))) {
				best = duty;
			}
		}
		return best;
	}

	/** 一句话（界面与日志**共用同一份编码**）。{@code null} = 空闲。 */
	public String describe(@Nullable Duty duty) {
		if (duty == null) {
			return "空闲（没有认领任何车次）";
		}
		final StringBuilder text = new StringBuilder(duty.stateWord());
		if (!duty.playerName().isEmpty()) {
			text.append(' ').append(duty.playerName());
		}
		if (duty.vehicleId() != 0) {
			text.append(' ').append(duty.jobId().isEmpty() ? "车 " + duty.vehicleId() : duty.jobId());
		}
		if (!duty.cabSpec().isEmpty()) {
			text.append("（驾驶室 ").append(duty.cabSpec()).append('）');
		}
		if (!duty.reason().isEmpty()) {
			text.append("（").append(duty.reason()).append('）');
		}
		return text.toString();
	}

	// ---------------------------------------------------------------- 取数口：PDA 车次列表（**全部车次**）

	/**
	 * **场上有哪些车次** —— 一行一趟车，按车次号排序（列表要有稳定顺序，否则每拍重建会让行与按钮
	 * 在眼皮底下换位置）。
	 *
	 * <h3>枚举方式就是本方法存在的理由</h3>
	 * <p>引擎里**没有全局车辆集合**，车辆挂在股道上，所以照既有写法遍历
	 * {@code simulator.sidings.forEach(siding -> siding.iterateVehicles(...))}
	 * （{@link Simulator#mmtrFindVehicle(long)} 就是这么找车的）。这一句正是
	 * "能看到全部车次"与"只看到附近车次"之间的分界线：它不经过任何按玩家位置裁剪的镜像。</p>
	 *
	 * <p>车次号（{@code mmtrJobId}）为空的车**不收**：车场里的裸车没有车次可显示
	 * （与 PDA 列表页原来那条"车次来自作业单；车库里的无作业车不在这一页"的口径一致）。</p>
	 *
	 * <p>没有认领者时 {@code dutyState} / {@code dutyCrew} 是空串（= 界面词"无人"），
	 * 而不是伪造一个 {@code IDLE} 的人 —— 与 {@link #operatorOf} 同一个口径。</p>
	 */
	public List<VehicleRow> allVehicleRows() {
		final ObjectArrayList<VehicleRow> rows = new ObjectArrayList<>();
		simulator.sidings.forEach(siding -> siding.iterateVehicles(vehicle -> {
			final String jobId = vehicle.getMmtrJobIdFromSync();
			if (jobId.isEmpty()) {
				return;
			}
			final Duty operator = operatorOf(vehicle.getId());
			/*
			 * 到停车点的距离与客户端 `VehicleExtension.getMmtrDistanceToStopTargetM()` 是**同一个算式**
			 * （两边读的就是同一对镜像字段），所以面板上这一列与大屏/卡片上的读数不会分叉。
			 */
			final double stopTargetM = vehicle.getMmtrRunStopTargetFromSync();
			rows.add(new VehicleRow(vehicle.getId(), jobId,
				operator == null ? "" : operator.state().name(),
				operator == null ? "" : operator.playerUuid().toString(),
				vehicle.getMmtrTaskNoteFromSync(),
				vehicle.getMmtrPidNextFromSync(),
				vehicle.getSpeed() * 3600,
				stopTargetM < 0 ? -1 : Math.max(0, stopTargetM - vehicle.getRailProgress()),
				vehicle.getMmtrDutyWaitingFromSync()));
		}));
		rows.sort((a, b) -> a.jobId().compareTo(b.jobId()));
		return rows;
	}

	/**
	 * **车次名（作业单名）→ 场上挂着它的那些车**（notes/409 §0 的 ①：派车按车次名而不是车辆 id）。
	 *
	 * <h3>为什么复用 {@link #allVehicleRows()} 而不是另写一遍枚举</h3>
	 * <p>"场上有哪些车次"只有一份答案，就是 {@link #allVehicleRows()} 那份（它遍历**全部股道**、
	 * 按车上的 {@code mmtrJobId} 镜像过滤 —— 见那里"枚举方式就是本方法存在的理由"那段）。
	 * 这里再遍历一次股道会让两处枚举范围悄悄分叉（面板上列得出来的车次，指令却说找不到），
	 * 所以本方法就是"在那一份名单里按车次名找"。</p>
	 *
	 * <h3>★ 为什么返回**集合**而不是"一个 long + 哨兵 -1"（2026-10-10 单测抓到的真 bug）</h3>
	 * <p>原来的写法是"唯一匹配 ⇒ 车辆 id；{@code 0} = 没有；{@code -1} = 同名多辆"，而
	 * <b>车辆 id 是随机的有符号 long，负数很常见</b>（现场随手就是 {@code -6810673503153593564}）。
	 * 于是"合法的负 id"与"同名多辆"挤在同一个格子里：`assign` 里那句 {@code if (vehicleId < 0)}
	 * 会把一辆**唯一**的车读成"场上有**多辆**车都挂着车次 D-020"，而场上明明只有一辆。
	 * 症状还是**时红时绿**（id 随机 ⇒ 约一半概率落到负数）。</p>
	 *
	 * <p>所以这里把"有没有 / 有几辆"与"是哪一辆"分开：<b>值域里有负数的东西不许拿负数当哨兵</b>。</p>
	 *
	 * @return 匹配到的车辆 id（去重）；**空 = 场上没有这个车次**，长度 &gt; 1 = 同名多辆
	 */
	public List<Long> findVehicleIdsByJobId(@Nullable String jobId) {
		final ObjectArrayList<Long> ids = new ObjectArrayList<>();
		if (jobId == null || jobId.trim().isEmpty()) {
			return ids;
		}
		final String wanted = jobId.trim();
		for (final VehicleRow row : allVehicleRows()) {
			if (row.jobId().equals(wanted) && !ids.contains(row.vehicleId())) {
				ids.add(row.vehicleId());
			}
		}
		return ids;
	}

	/**
	 * 便利口：**唯一**匹配时给那辆车的 id，否则 {@code 0}（没有 / 同名多辆都答 0）。
	 *
	 * <p>需要区分"没有"与"多辆"的调用方请直接用 {@link #findVehicleIdsByJobId(String)}
	 * （{@code assign} 就是那么写的）—— 这里刻意**不再有 -1 这个约定**，理由见上面那段。</p>
	 */
	public long findVehicleIdByJobId(@Nullable String jobId) {
		final List<Long> ids = findVehicleIdsByJobId(jobId);
		return ids.size() == 1 ? ids.get(0) : 0;
	}

	// ---------------------------------------------------------------- 可用性（PDA 用它决定按钮出不出现）

	@Nullable
	public String refusalForDirectBoard(UUID playerUuid, long vehicleId) {
		if (playerUuid == null) {
			return "缺少玩家 uuid";
		}
		final Vehicle vehicle = simulator.mmtrFindVehicle(vehicleId);
		if (vehicle == null) {
			return "找不到车辆 " + vehicleId;
		}
		if (stateOf(playerUuid) == State.DRIVING || stateOf(playerUuid) == State.DRIVING_EXIT_ARMED) {
			return "你已经在运转中 —— 先退出再认领别的车次";
		}
		final String occupied = occupancyRefusal(playerUuid, vehicleId, true);
		if (occupied != null) {
			return occupied;
		}
		if (vehicle.mmtrDutyStoppedAtTarget() && jobIdOf(vehicleId) == null) {
			/*
			 * 车停着、但**没挂在任何作业单上**：位置能上去，驾驶权却交不出去
			 * （{@code mmtrJobTakeover} 的第三道闸）。与其让人上去干坐着，不如在这里说清原因 ——
			 * 这就是"没有作业单的车不能开车"这条既有规矩在值守面的说法。
			 */
			return "车 " + vehicleId + " 没有挂在任何作业单上 —— 上去也拿不到驾驶权";
		}
		return null;
	}

	@Nullable
	public String refusalForPlatformMeet(UUID playerUuid, long vehicleId) {
		if (playerUuid == null) {
			return "缺少玩家 uuid";
		}
		final Vehicle vehicle = simulator.mmtrFindVehicle(vehicleId);
		if (vehicle == null) {
			return "找不到车辆 " + vehicleId;
		}
		if (stateOf(playerUuid) == State.DRIVING || stateOf(playerUuid) == State.DRIVING_EXIT_ARMED) {
			return "你已经在运转中 —— 先退出再认领别的车次";
		}
		final String occupied = occupancyRefusal(playerUuid, vehicleId, false);
		if (occupied != null) {
			return occupied;
		}
		return refusalForPlatformTarget(vehicle);
	}

	/**
	 * "这趟车被别人占着"这一条（两份可用性共用）。
	 *
	 * <p>用户口径（notes/408 §2.2）：有人在开的车**只**允许站台接站，而且只有"下一站退出"那一趟才给。
	 * 这不只是偏好 —— 直接把人塞进一个有人握着油门的驾驶室，等于两个执行者抢同一列车；
	 * 而"下一站退出"那趟到站时位置**真的会空出来**，所以接站成立。</p>
	 *
	 * @param directBoard {@code true} = 直接上车（下一站退出的车也不许）；{@code false} = 站台接站
	 */
	@Nullable
	private String occupancyRefusal(UUID playerUuid, long vehicleId, boolean directBoard) {
		final Duty operator = operatorOf(vehicleId);
		if (operator == null || operator.playerUuid().equals(playerUuid) || operator.state() == State.RELEASED) {
			return null;
		}
		if (operator.state() == State.DRIVING_EXIT_ARMED) {
			return directBoard
				? "这趟车有玩家·下一站退出 —— 只能站台接站（现在上去等于抢他的车）"
				: null;
		}
		return "这趟车已经被别的玩家认领了（" + operator.stateWord() + "）";
	}

	/**
	 * 这趟车**下一停站是不是站台**（notes/408 §6.1 那条"待核实"的落点）。
	 *
	 * <p>站台接站要求"下一站是站台"才成立：非站台目标（回库、折返、轨目标）必须**拒绝**并说明原因 ——
	 * 把人送到一个没有站台能上车的股道上，比拒绝更糟（用户口径是"仅可以选择接站"）。</p>
	 */
	@Nullable
	private String refusalForPlatformTarget(Vehicle vehicle) {
		final MmtrMission mission = vehicle.getMmtrMission();
		if (mission == null) {
			return "车 " + vehicle.getId() + " 现在没有在跑的一步 —— 引擎不知道它下一停站是哪个站台，没法送你去等";
		}
		if (mission.isTerminal()) {
			return "车 " + vehicle.getId() + " 这一步已经做完了（" + mission.getState() + "）—— 引擎还不知道下一步去哪，等它挂上下一步再认领";
		}
		if (mission.hasTargetRail()) {
			return "车 " + vehicle.getId() + " 的下一步是**轨目标**（折返/换端），不是站台 —— 没有站台可以接站";
		}
		if (platformOf(mission.getTargetSidingId()) == null) {
			return "车 " + vehicle.getId() + " 的下一步目标 " + mission.getTargetSidingId()
				+ " 不是站台（是股道/车站）—— 那里上不了车，不能站台接站";
		}
		return null;
	}

	// ---------------------------------------------------------------- 动作（null = 成功，非空 = 拒绝原因）

	/**
	 * {@code 直接上车}：认领这趟车，并请游戏端把玩家送上这列车。
	 *
	 * <p>落到哪个态由**车动不动**决定（notes/408 §3.2）：车在动 ⇒ 先到 {@link State#ABOARD}
	 * （"人到了、油门还不归他"），停稳后由 {@link #tick} 自动接管；车停着 ⇒ 游戏端回调
	 * {@link #arriveAndBoard} 时就可能直接进 {@link State#DRIVING}。</p>
	 *
	 * <h3>这条路为什么**不**需要 {@link #notifiedAtStop} 那张闩（2026-10-09 核对）</h3>
	 * <p>站台接站会刷屏，根因是"通知"这件事在 {@link #tick} 里**每拍重新做一次**，而待办被游戏端
	 * 每拍消费掉 ⇒ 引擎永远看不见"已经通知过"。{@code claimDirect} 不在此列：它是**一次玩家动作**，
	 * 只在动作那一刻跑一次；之后 {@link #tick} 对 {@code ABOARD} 的处理是 {@link #takeoverFor}，
	 * 交接被拒只改原因、**不重新排队**。所以待办在第一次排队之后就一直在队列里
	 * （游戏端处理完调 {@link #markBoardDone} 把它清掉），不存在"引擎每拍重新派送一次"的形状：
	 * 待办从排队到被清掉之间只会被游戏端读到一次，传送也就只发一次。</p>
	 */
	@Nullable
	public String claimDirect(UUID playerUuid, String playerName, long vehicleId) {
		return claimDirect(playerUuid, playerName, vehicleId, "");
	}

	/**
	 * 带**显式驾驶室**的 {@code 直接上车}（notes/409 §0 的 ①：派车要能指定驾驶室编号）。
	 *
	 * @param cabSpec {@code <车节序号><A|B>}（1 起，例如 {@code 1A}）；**空串 = 仍由引擎挑**
	 *                （{@link Vehicle#mmtrPreferredCabSpec()}，也就是旧签名的行为）。
	 *                非空的值会写进记录并**保持**（{@link Duty#cabSpecExplicit()}），
	 *                于是"记住的驾驶室"与游戏端实际用的驾驶室不会分叉。
	 */
	@Nullable
	public String claimDirect(UUID playerUuid, String playerName, long vehicleId, @Nullable String cabSpec) {
		final String refusal = refusalForDirectBoard(playerUuid, vehicleId);
		if (refusal != null) {
			return refusal;
		}
		final Duty previous = duties.get(playerUuid);
		if (previous != null && previous.vehicleId() == vehicleId && previous.state() == State.ABOARD) {
			return "你已经认领过这趟车、正在等它停稳（" + previous.reason() + "）";
		}
		if (previous != null && previous.state() != State.IDLE) {
			// 一个玩家同一时刻至多一个值守（notes/408 §2.1）：换车次就先把旧的放掉。
			releasePlanPlayerDriven(previous, "改认领车 " + vehicleId);
		}
		final Vehicle vehicle = simulator.mmtrFindVehicle(vehicleId);
		final String explicit = cabSpec == null ? "" : cabSpec.trim();
		put(playerUuid, playerName, previous, State.ABOARD, vehicleId, jobIdOf(vehicleId),
			explicit.isEmpty() ? (vehicle == null ? "" : vehicle.mmtrPreferredCabSpec()) : explicit,
			!explicit.isEmpty(), 0,
			vehicle != null && vehicle.mmtrDutyStoppedAtTarget()
				? "车已经停稳，引擎正把你送上车并交驾驶权"
					+ (explicit.isEmpty() ? "" : "（驾驶室 " + explicit + "）")
				: "你已认领这趟车，引擎正把你送上车（车还在动，停稳后才交驾驶权）"
					+ (explicit.isEmpty() ? "" : "（驾驶室 " + explicit + "）"),
			true);
		queueBoard(new PendingBoard(playerUuid, vehicleId, 0, true));
		return null;
	}

	/** {@code 站台接站}：认领这趟车，然后在它**下一停站的站台**上等（人不在车上）。 */
	@Nullable
	public String claimPlatform(UUID playerUuid, String playerName, long vehicleId) {
		return claimPlatform(playerUuid, playerName, vehicleId, "");
	}

	/**
	 * 带**显式驾驶室**的 {@code 站台接站}。
	 *
	 * <p>站台接站的人是自己走到站台上、按 G 上车的（引擎不传送他），所以他最终坐进哪一间仍然由他的
	 * 上车动作决定；这里记下的驾驶室是**派车时指定的那一间** —— 它让"这趟车归他、他该在 {@code 1A}"
	 * 这件事在车到站之前就写进记录，游戏端与面板读到的是同一个值。</p>
	 *
	 * @param cabSpec 同 {@link #claimDirect(UUID, String, long, String)}：空串 = 仍由引擎挑
	 */
	@Nullable
	public String claimPlatform(UUID playerUuid, String playerName, long vehicleId, @Nullable String cabSpec) {
		final String refusal = refusalForPlatformMeet(playerUuid, vehicleId);
		if (refusal != null) {
			return refusal;
		}
		final Vehicle vehicle = simulator.mmtrFindVehicle(vehicleId);
		final MmtrMission mission = vehicle == null ? null : vehicle.getMmtrMission();
		final long platformId = mission == null ? 0 : platformIdOrZero(mission.getTargetSidingId());
		final Duty previous = duties.get(playerUuid);
		if (previous != null && previous.vehicleId() == vehicleId && previous.state() == State.WAITING) {
			return "你已经在这趟车的下一站站台上等着了（站台 " + previous.waitPlatformId() + "）";
		}
		if (previous != null && previous.state() != State.IDLE) {
			releasePlanPlayerDriven(previous, "改认领车 " + vehicleId);
		}
		final String jobId = jobIdOf(vehicleId);
		final String explicit = cabSpec == null ? "" : cabSpec.trim();
		final String cab = explicit.isEmpty()
			? (vehicle == null ? "" : vehicle.mmtrPreferredCabSpec())
			: explicit;
		put(playerUuid, playerName, previous, State.WAITING, vehicleId, jobId, cab, !explicit.isEmpty(), platformId,
			"已认领 " + (jobId == null ? "车 " + vehicleId : jobId) + "，在它的下一停站站台（" + platformId + "）等：车到站停稳后送你上驾驶室"
				+ (explicit.isEmpty() ? "" : "（指定的驾驶室 " + explicit + "）"), false);
		queueBoard(new PendingBoard(playerUuid, vehicleId, platformId, false));
		return null;
	}

	/**
	 * **派车**（notes/409 §0 的 ①，用户口径）：{@code 为某个玩家按「车次名 + 驾驶室编号」认领一趟车}。
	 *
	 * <pre>
	 *   duty assign &lt;玩家uuid&gt; &lt;车次名&gt; &lt;驾驶室&gt; [--wait] [--name=&lt;玩家名&gt;]
	 * </pre>
	 *
	 * <p>它与 {@code claim} 的差别只有两点，但两点都必须在**引擎**里：</p>
	 * <ol>
	 *   <li><b>收车次名（作业单名，如 {@code 00103}）而不是车辆 id</b>：名字 → 车的解析走
	 *       {@link #findVehicleIdByJobId(String)}（= 复用 {@link #allVehicleRows()} 那份"场上有哪些车次"，
	 *       不另发明一套查找）。找不到 / 同名多辆都**拒绝并说清**。</li>
	 *   <li><b>收驾驶室编号</b>：{@link #cabSpecRefusal} 先校验它（车存在、是编组体车、车节序号在范围内），
	 *       通过之后**记进 {@link Duty}** 并跟着 {@code replace(...)} 一路传下去 —— 于是游戏端传送时用的
	 *       就是这一间（{@code MmtrDutyBoardWatch} 读 {@link Duty#cabSpec()}）。</li>
	 * </ol>
	 *
	 * <p>{@code --wait} = 站台接站（{@link #claimPlatform(UUID, String, long, String)}），
	 * 默认 = 直接传送（{@link #claimDirect(UUID, String, long, String)}）—— 两条路的可用性判据一条都不重写，
	 * 全部由既有的 {@code refusalForDirectBoard} / {@code refusalForPlatformMeet} 现问（铁律 ①）。</p>
	 *
	 * @param jobId 车次名（作业单名）；首尾空白会被忽略
	 * @param cabSpec {@code <车节序号><A|B>}（1 起，例如 {@code 1A} / {@code 10B}）；**空串 = 仍由引擎挑**
	 * @return {@code null} = 已派车；非空 = 拒绝原因（**记录不变** —— 校验在写记录之前全部做完）
	 */
	@Nullable
	public String assign(UUID playerUuid, String playerName, @Nullable String jobId, @Nullable String cabSpec, boolean waitAtPlatform) {
		final String wanted = jobId == null ? "" : jobId.trim();
		if (wanted.isEmpty()) {
			return "要给出车次名（作业单名，例如 00103）—— 车次列表请用 duty status / PDA 面板看";
		}
		final List<Long> matchedVehicleIds = findVehicleIdsByJobId(wanted);
		if (matchedVehicleIds.isEmpty()) {
			return "场上没有正在跑的车次 " + wanted + "（车次名来自车上的作业单；面板上列的就是这一份名单）";
		}
		if (matchedVehicleIds.size() > 1) {
			return "场上有**多辆**车都挂着车次 " + wanted + "（车辆 id：" + matchedVehicleIds
				+ "）—— 引擎无法判断派哪一辆；请改用 duty claim <玩家uuid> <车辆id>（车辆 id 在 PDA 面板的车次那一行）";
		}
		/*
		 * ★ 这里**不许**写 `if (vehicleId < 0)`：车辆 id 是随机的有符号 long，负数是常态
		 * （2026-10-10 单测抓到的就是这一句 —— 见 {@link #findVehicleIdsByJobId(String)} 的说明）。
		 * "没有 / 多辆 / 唯一"三态由上面两个 size() 判据说清，车辆 id 原样用。
		 */
		final long vehicleId = matchedVehicleIds.get(0);
		final String cabRefusal = cabSpecRefusal(vehicleId, cabSpec);
		if (cabRefusal != null) {
			return cabRefusal;
		}
		final String explicit = cabSpec == null ? "" : cabSpec.trim();
		return waitAtPlatform
			? claimPlatform(playerUuid, playerName, vehicleId, explicit)
			: claimDirect(playerUuid, playerName, vehicleId, explicit);
	}

	/**
	 * **驾驶室编号的校验**（{@code <车节序号><A|B>}，1 起）。
	 *
	 * <h3>写法与合法性判据照抄既有那一支</h3>
	 * <p>写法取自 {@code MmtrCommandExecutor} 的 {@code cab <id> <车节><A|B>} 那一支
	 * （{@code Pattern.compile("^(\\d*)([ab])$")}，大小写不敏感）；"能不能进"取自
	 * {@link Vehicle#enterMmtrCabAtCar(int, boolean, UUID)} 的两道前置：**必须走编组体走行缝**
	 * （{@link Vehicle#getMmtrConsistWalker()} 不为 {@code null}）且**车节序号在
	 * {@link org.mtr.core.mmtr.consist.MmtrConsistBody#carCount()} 之内**。</p>
	 *
	 * <p>刻意**不**在这里检查"车在不在动 / 驾驶室空不空"：那是进驾驶室那一刻的事
	 * （{@code enterMmtrCab} 自己会判），而派车可能发生在车还在区间里跑的时候
	 * （{@code ABOARD} 这个态存在的理由）。</p>
	 *
	 * @return {@code null} = 合法（或空串 = "仍由引擎挑"，调用方按缺省处理）；非空 = 拒绝原因
	 */
	@Nullable
	public String cabSpecRefusal(long vehicleId, @Nullable String cabSpec) {
		final String text = cabSpec == null ? "" : cabSpec.trim();
		if (text.isEmpty()) {
			return null; // 没指定 = 仍由引擎挑（claimDirect/claimPlatform 的旧行为）
		}
		final Vehicle vehicle = simulator.mmtrFindVehicle(vehicleId);
		if (vehicle == null) {
			return "找不到车辆 " + vehicleId;
		}
		final java.util.regex.Matcher matcher = CAB_SPEC_PATTERN.matcher(text);
		if (!matcher.matches()) {
			return "驾驶室写法不对「" + text + "」—— 要写成 <车节序号><A|B>（1 起，例如 1A / 10B）";
		}
		final org.mtr.core.mmtr.consist.MmtrConsistWalker consistWalker = vehicle.getMmtrConsistWalker();
		if (consistWalker == null) {
			return "车辆 " + vehicleId + " 不是编组体车（没有驾驶室）—— 指定不了驾驶室";
		}
		final int carCount = consistWalker.body().carCount();
		final int carNumber;
		try {
			carNumber = Integer.parseInt(matcher.group(1));
		} catch (NumberFormatException e) {
			return "驾驶室写法不对「" + text + "」—— 车节序号太大（这列车只有 " + carCount + " 节）";
		}
		if (carNumber < 1 || carNumber > carCount) {
			return "驾驶室 " + text + " 的车节序号越界：这列车只有 " + carCount + " 节（合法写法 1A…" + carCount + "B）";
		}
		return null;
	}

	/**
	 * **游戏端把人送上车并进了驾驶室之后回调**；引擎据此进入 {@link State#ABOARD} 或直接 {@link State#DRIVING}。
	 *
	 * <p>为什么不是引擎自己把人"传送"上去就完事：引擎动不了玩家。它只能排待办，
	 * 而"人真的到了"这件事只有游戏端知道 —— 于是"能不能交接"的判据（车停稳、车挂在作业单上）
	 * 在这一刻由引擎现问一次 {@link Simulator#mmtrJobTakeover}。</p>
	 *
	 * <p><b>这条路是"钥匙在他手里"那条判据的唯一例外</b>（见 {@link #tickAboard}）：游戏端刚刚明确报告
	 * "人已经在驾驶室里了"，而钥匙可能还差一拍才写上（{@code enterMmtrCab} 与这次回调是两件事），
	 * 所以这里不再等钥匙 —— 但**只对 {@code direct} 那条路成立**，也就是"直接上车"的传送回调；
	 * 站台接站的人是自己按 G 上车的，走的是 {@code tickWaiting} 里"钥匙在他手里"那条出口。</p>
	 *
	 * @return {@code null} = 已经上车（拿到或暂时没拿到驾驶权都算落地；没拿到时
	 *         {@link Duty#state()} 是 {@link State#ABOARD}、{@link Duty#reason()} 说清为什么）；
	 *         非空 = 拒绝原因（这个玩家根本没有这条值守 / 认领的是别的车 / 车不在了）
	 */
	@Nullable
	public String arriveAndBoard(UUID playerUuid, long vehicleId) {
		final Duty duty = duties.get(playerUuid);
		if (duty == null || duty.state() == State.IDLE) {
			return "这个玩家没有认领任何车次（先 duty claim / PDA 认领）";
		}
		if (duty.vehicleId() != vehicleId) {
			return "这个玩家认领的是车 " + duty.vehicleId() + "，不是 " + vehicleId;
		}
		if (simulator.mmtrFindVehicle(vehicleId) == null) {
			cancel(playerUuid, "车 " + vehicleId + " 已经不在了");
			return "找不到车辆 " + vehicleId;
		}
		if (duty.state() == State.DRIVING || duty.state() == State.DRIVING_EXIT_ARMED || duty.state() == State.RELEASED) {
			return null; // 已经到了 / 已经在开 / 已经退出：重复回调不是错误
		}
		/*
		 * 先落成"人在车上、驾驶权还没到手"这个**事实态**，再试着交接 —— 于是交接被拒时，
		 * 记录停在一个真话上（人在车上），而不是停在 WAITING（人其实已经不在站台了）。
		 */
		final Duty boarded = replace(duty, State.ABOARD, vehicleId, jobIdOf(vehicleId), cabSpecFor(duty, vehicleId),
			duty.cabSpecExplicit(), 0,
			"你已经在这趟车上（正在看能不能现在就把驾驶权交给你）");
		pushDutySync(boarded);
		takeoverFor(playerUuid, boarded);
		return null;
	}

	/** {@code 马上退出}：立刻交还自动（人可能还在车上）。 */
	@Nullable
	public String exitNow(UUID playerUuid) {
		final Duty duty = duties.get(playerUuid);
		if (duty == null || duty.state() == State.IDLE) {
			return "这个玩家现在没有值守（空闲）";
		}
		switch (duty.state()) {
			case WAITING:
				cancel(playerUuid, "取消了站台接站");
				return null;
			case RELEASED:
				return null; // 幂等
			case ABOARD:
				/*
				 * 还没拿到驾驶权就没有"交还"可做，但**位置要让出来** —— 落到已退出，
				 * 别人（以及这列车的自动运行）才不会被一条"占着车却不开"的记录挡住。
				 */
				final Duty abandoned = replace(duty, State.RELEASED, duty.vehicleId(), duty.jobId(), duty.cabSpec(), 0,
					"没有获得驾驶权就退出了");
				pushDutySync(abandoned);
				log(duty, abandoned, abandoned.reason());
				return null;
			default:
				return handBackToAutopilot(duty, "运转中马上退出");
		}
	}

	/**
	 * {@code 下一站退出}：挂上 {@link State#DRIVING_EXIT_ARMED}，到站停稳后由 {@link #tick} 自动交还。
	 *
	 * <p>为什么这是一个**独立状态**而不是一个布尔（notes/408 §2.1）：它同时改变三件事的行为 ——
	 * ① 车次列表里这趟车对别人**只允许接站**；② 到站时引擎必须自动交还；③ 接站的人到站时**有权直接接手**。
	 * 一个布尔会让这三条各自去判，而它们必须是同一个判断。</p>
	 */
	@Nullable
	public String armExitAtNextStop(UUID playerUuid) {
		final Duty duty = duties.get(playerUuid);
		if (duty == null || duty.state() == State.IDLE) {
			return "这个玩家现在没有值守（空闲）";
		}
		switch (duty.state()) {
			case DRIVING_EXIT_ARMED:
				return null; // 幂等
			case WAITING:
			case ABOARD:
				return "还没拿到驾驶权（" + duty.stateWord() + "）—— 没有可以挂的「下一站退出」：先停稳接管、或者直接取消";
			case RELEASED:
				return "已经交还自动了（" + duty.stateWord() + "）";
			default:
				final Duty armed = replace(duty, State.DRIVING_EXIT_ARMED, duty.vehicleId(), duty.jobId(), duty.cabSpec(), 0,
					"已挂「下一站退出」：到站停稳后自动交还，接站的人此时接手");
				pushDutySync(armed);
				log(duty, armed, armed.reason());
				return null;
		}
	}

	/** 下车 / 再认领 / 车没了：把这条值守清掉（回到 {@link State#IDLE}）。 */
	public void cancel(UUID playerUuid, String reason) {
		final Duty duty = duties.get(playerUuid);
		if (duty == null || duty.state() == State.IDLE) {
			return;
		}
		releasePlanPlayerDriven(duty, reason);
		duties.remove(playerUuid);
		notifiedAtStop.remove(playerUuid);
		rebuildWaitingIndex();
		pushDutySync(duty);
		System.out.println("[MMTR-DUTY] " + describe(duty) + " → 空闲（" + reason + "）");
	}

	/**
	 * 一辆车**离开世界**时把认领它的值守全放掉。
	 *
	 * <p>为什么要一个专门的入口、而不是只靠 {@link #tick} 的兜底：值守记录是**按玩家 id 跨 tick 存活**的，
	 * 而"一个玩家同一时刻至多一个值守"意味着一条指向已删车的记录会把这个人**永久挡在别的车次之外**。
	 * 兜底要等下一拍，删除那一刻就清更干净。</p>
	 */
	public void forgetVehicle(long vehicleId, String reason) {
		if (vehicleId == 0) {
			return;
		}
		final ObjectArrayList<UUID> affected = new ObjectArrayList<>();
		for (final Duty duty : duties.values()) {
			if (duty.vehicleId() == vehicleId) {
				affected.add(duty.playerUuid());
			}
		}
		for (final UUID playerUuid : affected) {
			cancel(playerUuid, reason);
		}
	}

	public List<PendingBoard> pendingBoards() {
		return new ObjectArrayList<>(pendingBoards);
	}

	/**
	 * 这条待办游戏端处理完了（**两种都要调**）。
	 *
	 * <p>{@code direct = true} 是"人已经传送上车"的回调之后；{@code direct = false} 是
	 * "那条播报已经给他看了"之后 —— 后一种如果不清掉，待办会一直躺在那儿、每拍重复播报同一句话。</p>
	 */
	public void markBoardDone(UUID playerUuid) {
		pendingBoards.removeIf(pending -> pending.playerUuid().equals(playerUuid));
	}

	/**
	 * **引擎侧每 tick**：到站停稳 → 交接 / 自动交还 / 清理失效认领。由 {@link Simulator} 调用。
	 *
	 * <p>插入点在"车辆走行完、作业调度器还没跑"那一拍（{@code Simulator} 里
	 * {@code MmtrAutoCoupler.tick} 之后）：于是"这一拍到站"对值守与作业调度器是**同一拍**看到的事实，
	 * 不会一边已经进了下一步、另一边还在等上一站。</p>
	 */
	public void tick(long currentMillis) {
		final ObjectArrayList<Duty> snapshot = new ObjectArrayList<>(duties.values());
		for (final Duty duty : snapshot) {
			if (duties.get(duty.playerUuid()) != duty) {
				continue; // 这一拍里已经被别处换掉了（换态 = 换对象）
			}
			switch (duty.state()) {
				case DRIVING, DRIVING_EXIT_ARMED -> tickDriving(duty, currentMillis);
				case ABOARD -> tickAboard(duty, currentMillis);
				case WAITING -> tickWaiting(duty, currentMillis);
				case RELEASED -> tickReleased(duty);
				default -> {
				}
			}
		}
	}

	// ---------------------------------------------------------------- 内部：tick 的四个分支

	private void tickDriving(Duty duty, long currentMillis) {
		final Vehicle vehicle = simulator.mmtrFindVehicle(duty.vehicleId());
		if (vehicle == null) {
			cancel(duty.playerUuid(), "这趟车已经不在了");
			return;
		}
		if (duty.state() != State.DRIVING_EXIT_ARMED || !vehicle.mmtrDutyStoppedAtTarget()) {
			refreshPassiveFields(duty, vehicle, currentMillis); // 没挂退出 / 还没到站：只刷新认领信息
			return;
		}
		handBackAtStop(duty);
	}

	private void tickAboard(Duty duty, long currentMillis) {
		final Vehicle vehicle = simulator.mmtrFindVehicle(duty.vehicleId());
		if (vehicle == null) {
			cancel(duty.playerUuid(), "这趟车已经不在了");
			return;
		}
		if (!vehicle.mmtrDutyStoppedAtTarget()) {
			// 铁律 ②：交接只在停稳时发生。车还在动 ⇒ 人就停在"到了、还没拿到权"这个态上。
			refreshPassiveFields(duty, vehicle, currentMillis);
			return;
		}
		/*
		 * 铁律 ①的另一半（用户口径 2026-09-28）：交接必须是**两件事同时成立** ——
		 * ①值守记录绑的是这趟车；②这趟车的驾驶室钥匙在**这个人**手里。
		 *
		 * <p>只看"车停稳了"就接管是危险的：那等于替一个可能已经走开（或从来没真的坐进驾驶室）的人
		 * 按下接管 —— 而接管会把作业单的司机绑定、任务的执行者、油门一起改掉。
		 * 钥匙是"他确实在这列车驾驶室里"的**现有证据**（玩家进驾驶室时游戏端就写了它），
		 * 所以这里读它，而不是新造一个"人在不在车上"的字段。</p>
		 *
		 * <p>唯一的例外是 {@link #arriveAndBoard}：那一刻游戏端刚刚明确报告"人已经在驾驶室了"，
		 * 钥匙可能还差一拍才写上，所以那条路直接调用 {@link #takeoverFor}（见那里的注释）。</p>
		 */
		/*
		 * ★ 两条不同来源的 ABOARD 用**两条判据**（用户口径 2026-10-09：交接时段按上车方式分）：
		 *
		 *   · **直接传送上车**（{@code --now}，{@link #claimDirect}）：人是引擎自己送进驾驶室的，
		 *     没有"自己走进来拿钥匙"这个动作 —— 钥匙一直停在自动运行的占位钥匙（SYSTEM）上。
		 *     拿钥匙当判据会让值守**永远卡在 ABOARD**（实机 2026-10-09 19:57–20:01：车正常跑、
		 *     人坐在驾驶室里、{@code cabKeyHolder=SYSTEM}、{@code duty status} 永远"已上车·未获驾驶权"）。
		 *     这一条的交接时段就是用户明确指定的那个：**车站停稳、门已开**。
		 *   · **站台接站**（{@code --wait}，{@link #claimPlatform}）：人是自己走到站台、按 G 上车的，
		 *     钥匙在他手里才是"他确实在那间驾驶室里"的现有证据，判据照旧。
		 */
		if (duty.direct()) {
			if (!vehicle.mmtrDoorsOpenForDutyHandover()) {
				refreshPassiveFields(duty, vehicle, currentMillis);
				return;
			}
			/*
			 * **顺序**：先交接、成功了再动驾驶室。反过来（先动钥匙再交接）时，一次被拒的交接会连带
			 * 把自动运行那一份计划清掉（{@code enterMmtrCab} 会清 {@code mmtrMotionPlan} /
			 * 停车点 / auto），于是"交接没成、车也不会自己开了"——那正是本片要修掉的那种僵局。
			 */
			if (takeoverFor(duty.playerUuid(), duty) == null) {
				/*
				 * brief 里"使玩家与列车之间绑定"的那一半：直接传送的人不会走游戏端的
				 * {@code enterMmtrCab}，所以钥匙要么从**上一个司机**手里转过来（车次转派 —— 同一条
				 * "先弹出上一个玩家"的口径），要么从引擎的占位钥匙顶掉。
				 */
				handCabToIncomingCrew(duty, duty.cabSpec());
			}
			return;
		}
		if (!vehicle.holdsMmtrCabKey(duty.playerUuid())) {
			refreshPassiveFields(duty, vehicle, currentMillis);
			return;
		}
		takeoverFor(duty.playerUuid(), duty);
	}

	/**
	 * **把驾驶室交给"这一个"玩家**（用户口径 2026-10-09：
	 * "在车站接车时先将上个玩家弹出车厢后，另一边玩家进入驾驶室"）。
	 *
	 * <h3>为什么"收旧钥匙 → 写新钥匙"必须连在一起</h3>
	 * <p>中间不能留一拍"编组没有占用端"（{@code activeCab == NONE}）：走行器靠占用端决定哪一端是
	 * 车头。所以这里一次做完三件事：</p>
	 * <ol>
	 *   <li>钥匙在**别人**手里时，用**运维口径**收回（{@code crewUuid = null}）—— 一次普通交接不许
	 *       悄悄抢在开车的司机，而"到站接车"是一次明确的操作；同时把他那条值守落到
	 *       {@link State#RELEASED}（别留一条"占着车却不开"的记录挡住别人）。</li>
	 *   <li>排一条 {@link PendingBoard.Kind#EJECT} 待办 —— **物理上把人弄下车只有游戏端能做**。</li>
	 *   <li>把钥匙写进新司机手里（{@link Vehicle#mmtrTakeCabKeyForDuty}）。这一步同时是"站台接站"
	 *       能成立的前提：{@code MmtrCabState.insertKey} **拒绝第二名乘务员**，旧钥匙不收回来，
	 *       新玩家按 G 时永远进不去驾驶室，而 {@link #tickWaiting} 的出口要求"钥匙在他手里"
	 *       ⇒ 站台接车会死在"等一个永远不会空出来的驾驶室"上，而且**一句原因都没有**。</li>
	 * </ol>
	 *
	 * @return {@code true} = 真的从别人手里转过来了（并排了一条"弹出车厢"的待办）
	 */
	private boolean handCabToIncomingCrew(Duty incoming, String cabSpec) {
		final Vehicle vehicle = simulator.mmtrFindVehicle(incoming.vehicleId());
		if (vehicle == null) {
			return false;
		}
		final UUID stale = vehicle.getMmtrCrewUuid();
		final boolean stolen = stale != null && !stale.equals(incoming.playerUuid());
		if (stolen) {
			if (!vehicle.leaveMmtrCab(null)) {
				// 收不回来就如实说一句 —— 否则现场只会看到"新司机上不了车"而没有任何原因。
				log(incoming, incoming, "驾驶室钥匙还在上一个司机（" + stale + "）手里，且收不回来 —— 新司机进不去驾驶室");
				return false;
			}
			final Duty staleDuty = duties.get(stale);
			if (staleDuty != null && staleDuty.vehicleId() == incoming.vehicleId() && staleDuty.state() != State.RELEASED) {
				final Duty released = replace(staleDuty, State.RELEASED, staleDuty.vehicleId(), staleDuty.jobId(),
					staleDuty.cabSpec(), 0, "驾驶室交给新司机（" + incoming.playerName() + "）了，你被弹出车厢");
				pushDutySync(released);
				log(staleDuty, released, released.reason());
			}
			queueBoard(PendingBoard.eject(stale, incoming.vehicleId()));
			log(incoming, incoming, "驾驶室从上一个司机（" + stale + "）转给 " + incoming.playerName()
				+ "：已收回他的钥匙、把钥匙写进新司机，并请游戏端把上一个玩家弹出车厢");
		}
		vehicle.mmtrTakeCabKeyForDuty(cabSpec, incoming.playerUuid());
		return stolen;
	}

	private void tickWaiting(Duty duty, long currentMillis) {
		final Vehicle vehicle = simulator.mmtrFindVehicle(duty.vehicleId());
		if (vehicle == null) {
			cancel(duty.playerUuid(), "这趟车已经不在了");
			return;
		}
		/*
		 * **他自己上车了**（用户口径 2026-09-28）：站台接站**不传送任何人** —— 玩家是自己走到站台上的，
		 * 车到了他按 G 上车，游戏端把驾驶室钥匙写成他的。于是这里不需要任何"他在不在车上"的新判据：
		 * 钥匙在他手里 + 车停稳 ⇒ 事实已经齐了，照常进"人在车上、待交接"这一态。
		 */
		if (vehicle.mmtrDutyStoppedAtTarget() && vehicle.holdsMmtrCabKey(duty.playerUuid())) {
			final Duty boarded = replace(duty, State.ABOARD, duty.vehicleId(), jobIdOf(duty.vehicleId()), cabSpecFor(duty, duty.vehicleId()),
				0, "你已经自己上了这趟车（车停稳、钥匙在你手里），正在把驾驶权交给你");
			pushDutySync(boarded);
			log(duty, boarded, boarded.reason());
			takeoverFor(duty.playerUuid(), boarded);
			return;
		}
		if (!vehicle.mmtrDutyStoppedAtTarget()) {
			// 还没到（或已经开出这一站）⇒ 复位"本站已通知"，于是**下一站**会重新通知一次。
			notifiedAtStop.remove(duty.playerUuid());
			refreshPassiveFields(duty, vehicle, currentMillis);
			return;
		}
		/*
		 * 到站停稳：排一条 PendingBoard，由游戏端给他**播一条话**（不是传送 —— 见
		 * {@link PendingBoard#direct()}）。
		 *
		 * <p>**人还停在 WAITING** —— "已认领、人在站台上等"这个事实直到他真上了车
		 * （{@link #arriveAndBoard}，或上面那条"钥匙在他手里"）才结束。提前改成 ABOARD
		 * 会让界面在"人其实还站在站台上"的那几拍里说"已在车上"，而这几拍恰恰是玩家盯着屏幕等车门的时刻。</p>
		 *
		 * <p>守卫是 {@link #notifiedAtStop}（"本站通知过没有"）而**不是**"待办还在不在"：
		 * 后者会与"游戏端每拍消费待办"撞成死循环（实机 14 秒 262 行同一句话，见那张闩的注释）。
		 * {@code add} 返回 {@code true} = 本站第一次 ⇒ 才排队 + 才记日志。</p>
		 */
		if (notifiedAtStop.add(duty.playerUuid())) {
			queueBoard(new PendingBoard(duty.playerUuid(), duty.vehicleId(), duty.waitPlatformId(), false));
			log(duty, duty, "车到站停稳，通知他可以上车接管了");
		}
		refreshPassiveFields(duty, vehicle, currentMillis);
	}

	/**
	 * 已退出：人可能还在车上，所以**没有**"他下车了"这个信号可读 —— 只能等
	 * {@link #cancel}（下车 / 再认领）或这趟车消失。车没了就自己清掉，别留幽灵值守。
	 */
	private void tickReleased(Duty duty) {
		if (duty.vehicleId() != 0 && simulator.mmtrFindVehicle(duty.vehicleId()) == null) {
			cancel(duty.playerUuid(), "这趟车已经不在了");
		}
	}

	// ---------------------------------------------------------------- 内部：交接（唯一的 DRIVING 入口）

	/**
	 * **唯一进入 {@link State#DRIVING} 的路**（铁律 ①）。判据一条都不在本类里 ——
	 * 全部由 {@link Simulator#mmtrJobTakeover(long, UUID)} 现问：车必须静止、必须有人、必须挂在作业单上。
	 *
	 * @return {@code null} = 已进入 DRIVING；非空 = 拒绝原因（记录停在 {@link State#ABOARD}）
	 */
	@Nullable
	private String takeoverFor(UUID playerUuid, Duty duty) {
		final String refusal = simulator.mmtrJobTakeover(duty.vehicleId(), playerUuid);
		if (refusal != null) {
			/*
			 * 停稳了却交接被拒（车没挂作业单 / 没有调度器 / 车又动了一点点）——**这是要看得见的**，
			 * 否则现场就是"人坐在驾驶室里、界面说已上车未获权、日志一个字都没有"。
			 * 只在**原因变了**的时候说一句，免得每拍刷屏。
			 */
			if (!refusal.equals(duty.reason())) {
				final Duty noted = replace(duty, State.ABOARD, duty.vehicleId(), jobIdOf(duty.vehicleId()), cabSpecFor(duty, duty.vehicleId()),
					0, "停稳了但交接被拒：" + refusal);
				pushDutySync(noted);
				System.out.println("[MMTR-DUTY] " + describe(noted) + " ← 交接被拒（" + refusal + "）");
			}
			return refusal;
		}
		final Duty driving = replace(duty, State.DRIVING, duty.vehicleId(), jobIdOf(duty.vehicleId()), cabSpecFor(duty, duty.vehicleId()), 0,
			"驾驶权已在你手里（引擎替你发布进路、申请道岔与信号；油门与制动是你的）");
		markPlanPlayerDriven(driving.vehicleId(), true);
		pushDutySync(driving);
		log(duty, driving, "接管成功（引擎的 mmtrJobTakeover）");
		return null;
	}

	/**
	 * 挂过"下一站退出"的车到站停稳了（{@link #tick} 的推进点）。
	 *
	 * <p>先试**交给等在站台上的那一位**（notes/408 §2.1 的第 ③ 条：接站的人到站时有权直接接手）——
	 * 成功了就不用还给自动，省掉"自动接一拍再交出去"的那一段空转。</p>
	 */
	private void handBackAtStop(Duty duty) {
		final Duty waiting = firstWaitingFor(duty.vehicleId(), duty.playerUuid());
		if (waiting != null && takeoverFor(waiting.playerUuid(), waiting) == null) {
			final Duty released = replace(duty, State.RELEASED, duty.vehicleId(), duty.jobId(), duty.cabSpec(), 0,
				"到站停稳，驾驶权直接交给接站的"
					+ (waiting.playerName().isEmpty() ? "玩家" : " " + waiting.playerName()));
			releasePlanPlayerDriven(duty, "到站停稳，交给接站的人");
			pushDutySync(released);
			log(duty, released, released.reason());
			/*
			 * 用户口径：**站台接车先把上一个玩家弹出车厢**，另一边玩家进入驾驶室 —— 驾驶权刚交给
			 * 接站的人，这里把钥匙从上一个司机手里转下来（"收旧钥匙 → 写新钥匙"必须连在一起，
			 * 中间留下一拍 {@code activeCab == NONE} 会让编组"没有车头"），并排一条弹出车厢的待办。
			 */
			handCabToIncomingCrew(waiting, waiting.cabSpec());
			return;
		}
		handBackToAutopilot(duty, "到站停稳，自动交还");
	}

	@Nullable
	private String handBackToAutopilot(Duty duty, String why) {
		final Vehicle vehicle = simulator.mmtrFindVehicle(duty.vehicleId());
		if (vehicle == null) {
			cancel(duty.playerUuid(), "这趟车已经不在了");
			return null;
		}
		final String refusal = simulator.mmtrJobRelease(duty.vehicleId());
		/*
		 * notes/411：归还给自动 = 编组必须重新**有头**。
		 *
		 * <p>到站"自动交还"之后，司机的钥匙会被拔走（游戏端 {@code leaveMmtrCab}）。若没人把引擎的
		 * 占位钥匙插回去，走行器就答不出"当前轨"（无人 ⇒ {@code railHex}/{@code peekNextRail} 都是 null），
		 * 自动任务于是既规划不出来又开不动 —— 旧代码还会把任务判 FAILED 进终态，整条线堵死
		 * （现场 00101 / 00106，见 notes/411）。连挂、车场刷车、司机动车那三条路都补了钥匙，
		 * 原来漏的是这一条。</p>
		 *
		 * <p>此处先补一次；如果此刻司机还坐在驾驶室里（插不进去），就交给车辆侧的自愈网
		 * （{@link Vehicle#mmtrEnsureSystemKeyForAuto} 在"无人编组导致规划失败"那一刻再补一次）。</p>
		 */
		vehicle.mmtrEnsureSystemKeyForAuto();
		/*
		 * 此刻司机可能**还坐在驾驶室里**（钥匙还在他手上 ⇒ 上面那次插不进去）。他离开驾驶室那一刻会
		 * 拔钥匙（{@code Vehicle.leaveMmtrCab}），那里也登记同一条兜底；这里再登记一次是因为
		 * 本条路本身也可能先把钥匙收走。兜底网在车停稳后补钥匙（notes/411）。
		 */
		if (vehicle.mmtrAutoMissionLive()) {
			simulator.mmtrAutoKeyWatch.watch(vehicle.getId());
		}
		/*
		 * "车没挂在作业单上"不算失败：人确实把权放掉了，只是本来就没有作业单可还。
		 * 其余原因要在记录里说出来（例如没有调度器），否则现场表现为"按了退出、界面还是运转中"。
		 */
		final String note = refusal == null
			? why
			: (refusal.contains("没有挂在任何作业单上") ? why + "（这趟车本来就没挂作业单）" : why + "（归还被拒：" + refusal + "）");
		final Duty released = replace(duty, State.RELEASED, duty.vehicleId(), duty.jobId(), duty.cabSpec(), 0, note);
		releasePlanPlayerDriven(duty, why);
		pushDutySync(released);
		log(duty, released, note);
		return null;
	}

	// ---------------------------------------------------------------- 内部：记录维护

	private State stateOf(UUID playerUuid) {
		final Duty duty = playerUuid == null ? null : duties.get(playerUuid);
		return duty == null ? State.IDLE : duty.state();
	}

	private void put(UUID playerUuid, @Nullable String playerName, @Nullable Duty previous, State state, long vehicleId,
			@Nullable String jobId, String cabSpec, long waitPlatformId, String reason) {
		put(playerUuid, playerName, previous, state, vehicleId, jobId, cabSpec, false, waitPlatformId, reason);
	}

	/**
	 * @param cabSpecExplicit 这个驾驶室是不是**调用方显式给的**（派车指令指定的那一间）——
	 *                        见 {@link Duty#cabSpecExplicit()}：显式的值不会被
	 *                        {@link #refreshPassiveFields} 用引擎的偏好值刷掉。
	 */
	private void put(UUID playerUuid, @Nullable String playerName, @Nullable Duty previous, State state, long vehicleId,
			@Nullable String jobId, String cabSpec, boolean cabSpecExplicit, long waitPlatformId, String reason) {
		put(playerUuid, playerName, previous, state, vehicleId, jobId, cabSpec, cabSpecExplicit, waitPlatformId, reason, false);
	}

	/**
	 * @param cabSpecExplicit 这个驾驶室是不是**调用方显式给的**（派车指令指定的那一间）——
	 *                        见 {@link Duty#cabSpecExplicit()}：显式的值不会被
	 *                        {@link #refreshPassiveFields} 用引擎的偏好值刷掉。
	 * @param direct          这次上车是不是**直接传送**（{@code --now}）—— 见 {@link Duty#direct()}：
	 *                        它决定 {@code ABOARD} 用哪一条交接判据（停稳开门 / 钥匙在他手里）。
	 */
	private void put(UUID playerUuid, @Nullable String playerName, @Nullable Duty previous, State state, long vehicleId,
			@Nullable String jobId, String cabSpec, boolean cabSpecExplicit, long waitPlatformId, String reason,
			boolean direct) {
		final String name = playerName == null || playerName.isEmpty()
			? (previous == null ? "" : previous.playerName())
			: playerName;
		final Duty duty = new Duty(playerUuid, name, state, vehicleId, jobId, cabSpec, cabSpecExplicit, waitPlatformId,
			simulator.getCurrentMillis(), reason, direct);
		duties.put(playerUuid, duty);
		rebuildWaitingIndex();
		pushDutySync(duty);
		log(previous, duty, reason);
	}

	private Duty replace(Duty duty, State state, long vehicleId, @Nullable String jobId, String cabSpec, long waitPlatformId, String reason) {
		return replace(duty, state, vehicleId, jobId, cabSpec, duty.cabSpecExplicit(), waitPlatformId, reason);
	}

	/**
	 * 换态但**重新决定驾驶室**的那些调用点用这一个：把"这个驾驶室是不是显式指定的"一并传下去，
	 * 否则一次换态就会把派车时指定的驾驶室降级成"引擎挑的"（然后下一拍被刷成另一端）。
	 */
	private Duty replace(Duty duty, State state, long vehicleId, @Nullable String jobId, String cabSpec,
			boolean cabSpecExplicit, long waitPlatformId, String reason) {
		final Duty next = new Duty(duty.playerUuid(), duty.playerName(), state, vehicleId, jobId, cabSpec,
			cabSpecExplicit, waitPlatformId, simulator.getCurrentMillis(), reason, duty.direct());
		duties.put(next.playerUuid(), next);
		rebuildWaitingIndex();
		return next;
	}

	/**
	 * 认领信息里那几个"顺带刷新"的字段（作业号 / 驾驶室写法）。
	 *
	 * <p>不做迁移日志、也不换态 —— 作业表走到下一步时这些字会变，而它**不是**一次值守迁移
	 * （铁律 ③ 说的是状态变化，不是"车次号刷了一下"）。真变了才写镜像，免得每拍推一份补丁。</p>
	 *
	 * <h3>显式指定的驾驶室**不受这里影响**</h3>
	 * <p>派车指令指定的那一间（{@link Duty#cabSpecExplicit()}）是**玩家的选择**，而这里刷的是
	 * "引擎现在认为该进哪一间"（会随行进方向变）。用后者盖掉前者，现场就是"我明明派了 1A，
	 * 人却被塞进 1B"—— 所以显式值原样带过，只有"由引擎挑"的那种才跟着刷。</p>
	 */
	private void refreshPassiveFields(Duty duty, Vehicle vehicle, long currentMillis) {
		final String jobId = jobIdOf(duty.vehicleId());
		final String cabSpec = duty.cabSpecExplicit() ? duty.cabSpec() : cabSpecOf(duty.vehicleId());
		final String jobIdNow = jobId == null ? "" : jobId;
		if (jobIdNow.equals(duty.jobId()) && cabSpec.equals(duty.cabSpec())) {
			return;
		}
		final Duty next = new Duty(duty.playerUuid(), duty.playerName(), duty.state(), duty.vehicleId(), jobIdNow, cabSpec,
			duty.cabSpecExplicit(), duty.waitPlatformId(), currentMillis, duty.reason(), duty.direct());
		duties.put(next.playerUuid(), next);
		rebuildWaitingIndex();
		pushDutySync(next);
	}

	private void rebuildWaitingIndex() {
		waitingByVehicle.clear();
		for (final Duty duty : duties.values()) {
			if (duty.vehicleId() != 0 && duty.state() == State.WAITING) {
				waitingByVehicle.computeIfAbsent(duty.vehicleId(), key -> new ObjectArrayList<>()).add(duty);
			}
		}
	}

	@Nullable
	private Duty firstWaitingFor(long vehicleId, UUID excludePlayer) {
		final ObjectArrayList<Duty> candidates = waitingByVehicle.get(vehicleId);
		if (candidates == null) {
			return null;
		}
		for (final Duty candidate : candidates) {
			if (!candidate.playerUuid().equals(excludePlayer) && candidate.state() == State.WAITING) {
				return candidate;
			}
		}
		return null;
	}

	/** 同一个玩家同一时刻至多一条待办：后一条覆盖前一条（他改主意了）。 */
	private void queueBoard(PendingBoard pending) {
		pendingBoards.removeIf(existing -> existing.playerUuid().equals(pending.playerUuid()));
		pendingBoards.add(pending);
	}

	/**
	 * 这列车上**有没有**这个玩家的待办（游戏端有没有还没处理的活）。
	 *
	 * <p>注意它**不再**用来决定"要不要通知"（那是 {@link #notifiedAtStop} 的活，见那里的实测）——
	 * 现在只剩查询用途，供游戏端/指令核对"我这个人的待办到底还在不在"。</p>
	 */
	private boolean hasPendingBoard(UUID playerUuid) {
		for (final PendingBoard pending : pendingBoards) {
			if (pending.playerUuid().equals(playerUuid)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 这列车此刻有几个人"认领"着它（{@code mmtrDutyWaiting} 同步字段用它）。
	 *
	 * <p>口径：{@link State#RELEASED} 与 {@link State#IDLE} **不算**认领 —— 已退出的人不再占位置，
	 * 否则"下一站退出"到站之后这趟车会永远显示"还有 1 人在等"。</p>
	 */
	private long waitingCount(long vehicleId) {
		long count = 0;
		for (final Duty duty : duties.values()) {
			if (duty.vehicleId() == vehicleId && duty.state() != State.IDLE && duty.state() != State.RELEASED) {
				count++;
			}
		}
		return count;
	}

	// ---------------------------------------------------------------- 内部：同步与查询助手

	/**
	 * 把这条值守写进**车上的镜像字段**（客户端 / PDA 靠它读"这趟车归谁、有没有挂下一站退出"）。
	 *
	 * <p>写的是**此刻真正占着这趟车的那位**（{@link #operatorOf}），而且只有"人在车上"的那几个态
	 * 才把这趟车标成"有人值守"：只有人在站台上等（{@code WAITING}）时不能把它说成有人值守 ——
	 * 那会让别人以为"有人在开"而不敢上车，而这趟车其实还是自动在跑。</p>
	 */
	private void pushDutySync(@Nullable Duty duty) {
		if (duty == null || duty.vehicleId() == 0) {
			return;
		}
		final Vehicle vehicle = simulator.mmtrFindVehicle(duty.vehicleId());
		if (vehicle == null) {
			return;
		}
		final Duty operator = operatorOf(duty.vehicleId());
		final boolean crewOnBoard = operator != null && (operator.state() == State.ABOARD
			|| operator.state() == State.DRIVING || operator.state() == State.DRIVING_EXIT_ARMED);
		vehicle.mmtrSetDutySync(crewOnBoard ? operator.state().name() : "", crewOnBoard ? operator.playerUuid().toString() : "",
			(int) waitingCount(duty.vehicleId()));
	}

	@Nullable
	private String jobIdOf(long vehicleId) {
		return simulator.mmtrJobScheduler == null ? null : simulator.mmtrJobScheduler.jobIdOfVehicle(vehicleId);
	}

	private String cabSpecOf(long vehicleId) {
		final Vehicle vehicle = simulator.mmtrFindVehicle(vehicleId);
		return vehicle == null ? "" : vehicle.mmtrPreferredCabSpec();
	}

	/**
	 * 这条值守现在的驾驶室写法：**显式指定过就还是它**（{@link Duty#cabSpecExplicit()}），
	 * 否则由引擎挑（{@link #cabSpecOf(long)}）。
	 *
	 * <p>换态时重算驾驶室的那些调用点必须走这里，用 {@link #cabSpecOf(long)} 会把派车指定的那一间
	 * 覆盖成引擎的偏好值 —— 那正是"记住的驾驶室与实际用的驾驶室分叉"。</p>
	 */
	private String cabSpecFor(Duty duty, long vehicleId) {
		return duty.cabSpecExplicit() && !duty.cabSpec().isEmpty() ? duty.cabSpec() : cabSpecOf(vehicleId);
	}

	@Nullable
	private Platform platformOf(long id) {
		return id == 0 ? null : simulator.platformIdMap.get(id);
	}

	private long platformIdOrZero(long id) {
		return platformOf(id) == null ? 0 : id;
	}

	/**
	 * P6 ③：**计划层的"编组由玩家开"**。进入 {@link State#DRIVING} 时置位、退出时清掉 ——
	 * 否则"计划层以为 AI 在跑、实例层以为人在跑"，两套派发会互相踩（notes/408 §2.4）。本类只做接线，
	 * 不重复那道"谁在开"的判断。
	 */
	private void markPlanPlayerDriven(long vehicleId, boolean player) {
		final String consistId = consistIdOf(vehicleId);
		if (consistId.isEmpty()) {
			// 查不到就不猜：不是每列车都来自时刻表（作业单是另一套编排）。
			return;
		}
		try {
			simulator.setMmtrPlanPlayerDriven(consistId, player);
		} catch (Throwable e) {
			// 值守是引擎的硬状态，计划层置位失败不能把它的迁移带崩（那会让"人在开车"这件事丢掉记录）。
			System.out.println("[MMTR-DUTY] 计划层" + (player ? "接管" : "归还") + "置位失败（consistId=" + consistId + "）：" + e);
		}
	}

	private void releasePlanPlayerDriven(@Nullable Duty duty, String why) {
		if (duty == null || duty.vehicleId() == 0) {
			return;
		}
		if (duty.state() != State.DRIVING && duty.state() != State.DRIVING_EXIT_ARMED) {
			return; // 没置过位就不要清（清理别人的置位是另一回事）
		}
		markPlanPlayerDriven(duty.vehicleId(), false);
	}

	/**
	 * 这列车现在是哪个编组的**实车**（计划层的 {@code consistId}）；查不到返回空串。
	 *
	 * <p>{@code states[].vehicleId == 0} 表示"编组还没分到实车" —— 那种条目的 consistId 不是这列车的，
	 * 刻意跳过（见 {@link #markPlanPlayerDriven}）。</p>
	 */
	private String consistIdOf(long vehicleId) {
		if (vehicleId == 0) {
			return "";
		}
		for (final MmtrPlanDispatcher dispatcher : simulator.mmtrPlanDispatchers.values()) {
			for (final MmtrPlanDispatcher.WorkingState state : dispatcher.states) {
				if (state.vehicleId == vehicleId && !state.consistId.isEmpty()) {
					return state.consistId;
				}
			}
		}
		return "";
	}

	/** 占用优先级：谁此刻真正占着这趟车（见 {@link #operatorOf}）。 */
	private static int occupancyRank(State state) {
		return switch (state) {
			case DRIVING -> 4;
			case DRIVING_EXIT_ARMED -> 3;
			case ABOARD -> 2;
			case WAITING -> 1;
			default -> 0;
		};
	}

	/**
	 * 迁移日志（铁律 ③）：**谁 / 哪趟车 / 从哪个态到哪个态 / 为什么**。
	 *
	 * <p>格式刻意固定成 {@code [MMTR-DUTY] &lt;玩家&gt; &lt;车次|车id&gt;：&lt;旧态&gt; → &lt;新态&gt;（&lt;为什么&gt;）}，
	 * 于是验收时"日志与镜像字段逐行对得上"是能用眼睛做的（notes/408 §5 的 S1 那一行）。
	 * 态的词从 {@link #stateWordOf} 取 —— 与界面**同一份编码**。</p>
	 *
	 * <p>包内可见（不是 private）是给单测留的观察点：{@link #noticeLogCount} 就是这么数出来的
	 * ——"到站通知只出现一次"这条回归需要**精确计数**，而打印出来的日志在单测里没有可数的出口。</p>
	 */
	static void log(@Nullable Duty from, Duty to, String why) {
		noticeLogCount++;
		final State fromState = from == null ? State.IDLE : from.state();
		final StringBuilder text = new StringBuilder("[MMTR-DUTY] ");
		text.append(to.playerName().isEmpty() ? to.playerUuid().toString() : to.playerName());
		text.append(' ').append(to.jobId().isEmpty() ? (to.vehicleId() == 0 ? "（未绑车）" : "车 " + to.vehicleId()) : to.jobId());
		if (!to.cabSpec().isEmpty()) {
			text.append("（驾驶室 ").append(to.cabSpec()).append('）');
		}
		text.append("：").append(stateWordOf(fromState)).append(" → ").append(stateWordOf(to.state()));
		text.append("（").append(why == null || why.isEmpty() ? "—" : why).append('）');
		System.out.println(text);
	}

	/**
	 * 一共落过多少条 {@code [MMTR-DUTY]} 日志。
	 *
	 * <p><b>只为单测的精确计数存在</b>（"同一站停稳期间通知只出现一次"这条回归 ——
	 * 实机修前 14 秒 262 条，靠读 stdout 是数不准的）。运行时它没有任何读者，
	 * 只是每次迁移加一，代价可以忽略。</p>
	 */
	static long noticeLogCount;
}
