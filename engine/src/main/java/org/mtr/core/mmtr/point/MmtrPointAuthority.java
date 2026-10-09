
package org.mtr.core.mmtr.point;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * MMTR turnout authority (道岔多级控制, design R2): a turnout (node + approach rail) is a
 * controlled resource whose next rail is decided by priority - manual operator first (read by the
 * walker from the operator BranchStore), then an explicit auto/task grant issued here, then never
 * auto (halt). Auto logic (missions / route planners) REQUEST a leg index per en-route turnout
 * ahead of arrival ("approach locking"); only one owner is granted per point at a time, competing
 * requests queue FIFO (bounded by the grant window), an operator LOCK parks the point for manual
 * use and queues auto requests until unlocked, and a grant dies with its window so stale holders
 * cannot wedge the network. The walker releases the holder when the train actually crosses the
 * point (or a terminal mission releases all its points); nothing here ever moves a train itself.
 *
 * <p><b>裁决链</b>（同时抢一处道岔时谁先走）：**服务等级/车号**（{@link MmtrTrainPriority}，
 * 用户 2026-09-27 的运营规则：高铁踩通勤的头、同级车号小者先）→ **计划时刻**（notes/151 T5，
 * 计划早者先）→ **入队序**（FIFO）→ owner id（保证判断不对称）→ 防饿死档（等够 5 分钟不再排在新来者
 * 后面）。三处都读同一条链：逐进向队列挑头（{@link #pickNext}）、物理位置队列挑头
 * （{@link #pickNextPhysical}）、以及"该不该让位/能不能收回位置"（{@link #outranks}）。</p>
 */
public final class MmtrPointAuthority {

	public static class Req {
		public final String owner;
		public int leg;
		public long untilMillis;
		/**
		 * T1b 裁决链第一档：**计划时刻优先**（越小越优先）。{@link Long#MAX_VALUE} = 没有计划，
		 * 完全按到达序 —— 所以"没有任务时刻"的运行与修前逐位一致。
		 */
		public long priorityMillis = Long.MAX_VALUE;
		/** T1b 裁决链最后一档：入队时刻，用来算等待时长（**防饿死**）。重复申请不刷新它。 */
		public long enqueuedAtMillis;

		Req(String owner, int leg, long untilMillis) {
			this(owner, leg, untilMillis, Long.MAX_VALUE);
		}

		Req(String owner, int leg, long untilMillis, long priorityMillis) {
			this.owner = owner;
			this.leg = leg;
			this.untilMillis = untilMillis;
			this.priorityMillis = priorityMillis;
		}
	}

	private static final class Holder extends Req {
		Holder(Req req) {
			super(req.owner, req.leg, req.untilMillis, req.priorityMillis);
			this.enqueuedAtMillis = req.enqueuedAtMillis;
		}
	}

	public enum Result {
		GRANTED,
		QUEUED,
		/**
		 * T1: the (approach, leg) this request names is **physically impossible** at this turnout -
		 * e.g. branch -> far end, driving back through the switch blades. No grant and no queueing:
		 * queueing would admit that the combination may become legal later. The caller must reject
		 * the request rather than guess (notes/115 §9, MmtrTurnout#positionForLeg).
		 */
		REJECTED
	}

	/**
	 * T1: the physical turnout at a node, looked up by whoever owns the geometry (the simulator).
	 *
	 * <p>Not wired = the whole physical layer stays inert and every request keeps the old
	 * per-approach semantics, bit for bit. That is what the hand-built authorities in the engine
	 * tests do, so the existing baseline is untouched by construction.</p>
	 */
	public interface TurnoutLookup {
		@Nullable MmtrTurnout turnoutAt(long x, long y, long z);
	}

	/**
	 * **改道岔位置之前的一致性检查**（谁可以做这个判断由外层决定 —— 权限层不认识车辆足迹）。
	 *
	 * <p>唯一的实现是 {@code Simulator}：**另一列车压在岔区上时不许改位置**（与人工扳岔、意图扳岔
	 * 读同一条净空判定）。请求方**自己**压在岔上不算 —— 它按着自己的位（T1）本来就可以改自己的需要，
	 * 否则换端/折返会把自己锁死。</p>
	 *
	 * @return 说清原因（可直接回给操作者）＝ 现在不许改；{@code null} = 可以
	 */
	public interface PositionChangeGuard {
		@Nullable String blockReason(long x, long y, long z, int newPosition, String owner);
	}

	/**
	 * **某列车是不是还压在这处节点的轨上**（占用树答，权限层不认识车辆足迹）。
	 *
	 * <p>用途有两个：① 物理持有窗口到期/被抢时判断"能不能放位"（放位等于允许别人把道岔扳到别的位，
	 * 而车还压在这处道岔的轨上时那样做就是把道岔从它脚下抽走）；② 释放"陈旧持有"（持有者根本不在
	 * 岔轨上、又有人排队等着别的位）。</p>
	 *
	 * <p><b>三态</b>：{@code TRUE} = 确知在岔轨上；{@code FALSE} = 确知不在；{@code null} = 查不出来
	 * （不是车辆 owner，例如测试里的 {@code "vA"} 标签）。两个用途对"查不出来"的取舍相反：
	 * 放位/抢位时按"不在"处理（老语义不变），释放陈旧持有时按"在"处理（宁可不放）——
	 * 少了这个区分，两边的既有用例会互相打架。</p>
	 */
	public interface HolderOccupancy {
		@Nullable Boolean ownerIsOnNodeRails(long x, long y, long z, String owner);
	}

	/** 挂上"持有者还在不在岔轨上"的查询；不挂 = 到期就放位（老语义，测试夹具就是这样）。 */
	public MmtrPointAuthority withHolderOccupancy(@Nullable HolderOccupancy occupancy) {
		this.holderOccupancy = occupancy;
		return this;
	}

	private @Nullable HolderOccupancy holderOccupancy;

	/**
	 * **这处道岔现在实际在哪一位**（不看持有者是谁，只看"位置"这件事本身）。
	 *
	 * <h3>为什么要单独问一次"位置"</h3>
	 * <p>权限层里的 {@link #physicalPosition} 是**持有者驱动**的：没人持有就是
	 * {@link #NO_PHYSICAL_HOLDER}（"无主"）。但"是否需要扳岔"这件事只取决于**道岔现在的位置**，
	 * 与谁持有无关 —— 申请要的那一位如果就是现在的位，那这一趟**什么都不用扳**。</p>
	 *
	 * <p>不挂 = 问不出实际位置（测试夹具），此时退化为"按老语义排队"，既有用例逐位不变。</p>
	 *
	 * @return 位置（0/1），或 {@link #NO_PHYSICAL_HOLDER} = 问不出来
	 */
	public interface ActualPositionLookup {
		int position(long x, long y, long z);
	}

	/** 挂上"这处道岔现在实际在哪一位"的查询（唯一实现是 {@code Simulator.mmtrTurnoutPosition}）。 */
	public MmtrPointAuthority withActualPositionLookup(@Nullable ActualPositionLookup lookup) {
		this.actualPositionLookup = lookup;
		return this;
	}

	private @Nullable ActualPositionLookup actualPositionLookup;

	/**
	 * **这列车属于哪个服务等级、车号多少**（用户 2026-09-27：抢同一处道岔时的优先级）。
	 *
	 * <p>权限层只认 owner 字符串（{@code "v<车辆id>"}），不认识作业单 —— "车 → 任务 → 作业单号 +
	 * 服务等级"那一段由 {@code Simulator} 喂进来（与净空闸/占用查询同一种接法）。</p>
	 *
	 * <p>不挂 = 谁都没有优先级，裁决逐位退回老口径（计划时刻 → 入队序 → owner id）；
	 * 测试夹具就是这样，所以基线不动。</p>
	 */
	public interface OwnerPriority {
		@Nullable MmtrTrainPriority priorityOf(String owner);
	}

	/** 挂上"服务等级 + 车号"的查询（唯一实现是 {@code Simulator}）。 */
	public MmtrPointAuthority withOwnerPriority(@Nullable OwnerPriority priority) {
		this.ownerPriority = priority;
		return this;
	}

	private @Nullable OwnerPriority ownerPriority;

	/** 现问一次优先权（不缓存：作业单换了、车换单了，下一次裁决就按新的算）。 */
	private @Nullable MmtrTrainPriority priorityOf(String owner) {
		return ownerPriority == null ? null : ownerPriority.priorityOf(owner);
	}

	/** 日志里的人话：谁的**服务等级/车号** + 计划时刻（问不出优先级时就只写计划）。 */
	private String describeWaiter(String owner, long priorityMillis) {
		final MmtrTrainPriority priority = priorityOf(owner);
		final String plan = priorityMillis == Long.MAX_VALUE ? "无计划" : "计划 " + priorityMillis;
		return priority == null ? owner + "（" + plan + "）" : owner + "（" + priority.describe() + "，" + plan + "）";
	}

	private int actualPosition(long x, long y, long z) {
		return actualPositionLookup == null ? NO_PHYSICAL_HOLDER : actualPositionLookup.position(x, y, z);
	}

	/** 车还压在岔轨上时，持有窗口一次续这么多（毫秒）；车出清后不再续期，正常释放。 */
	private static final long PHYSICAL_HOLD_EXTENSION_MILLIS = 30_000;

	/** {@link #physicalPosition} answer when nobody currently defines this turnout's position. */
	public static final int NO_PHYSICAL_HOLDER = Integer.MIN_VALUE;

	/** T1b 防饿死：排队等待超过这个时长就提到上一档（不再排在任何新来者后面）。 */
	public static final long MMTR_STARVATION_MILLIS = 5L * 60 * 1000;

	/** owner → 上一次原子申请被拒的原因（人话，见 describeRefusal）。 */
	private final Map<String, String> lastWaitReason = new HashMap<>();

	/** T1: one turnout, one position - who has currently pinned it where. */
	private static final class Physical {
		final String owner;
		final int position;
		long untilMillis;
		/**
		 * **谁更该先走**：请求方当前任务的**计划时刻**（越小越早，{@link Long#MAX_VALUE} = 没有计划）。
		 *
		 * <p>为什么物理层也要它（notes/151，用户裁定）：六台车去同一个车站、计划到达 00:05 / 00:07 / …
		 * 时，**通行优先权属于 00:05 那台**。逐进向的队列早就是这个口径（{@code Req.priorityMillis}），
		 * 但"道岔位置归持有者"这一层没有 —— 晚班车一旦按上位置，早班车只能干等，
		 * 于是几台车在咽喉里互相按着位置、轮流让位又抢回（现场实测的 ping-pong）。</p>
		 */
		final long priorityMillis;

		Physical(String owner, int position, long untilMillis, long priorityMillis) {
			this.owner = owner;
			this.position = position;
			this.untilMillis = untilMillis;
			this.priorityMillis = priorityMillis;
		}
	}

	/**
	 * T1: a mutually exclusive demand waiting **on the turnout** (not on its own approach).
	 *
	 * <p>Queuing per approach is what let two trains from different approaches each hold "their own
	 * row" of the same physical switch - the direction view again, one layer up.</p>
	 */
	private static final class PhysicalReq {
		final String owner;
		String viaRailHex;
		int leg;
		int position;
		long untilMillis;
		/** 计划时刻（越小越早）：物理队列也按它排序，见 {@link #pickNextPhysical}。 */
		long priorityMillis;
		long enqueuedAtMillis;

		PhysicalReq(String owner, String viaRailHex, int leg, int position, long untilMillis, long priorityMillis) {
			this.owner = owner;
			this.viaRailHex = viaRailHex;
			this.leg = leg;
			this.position = position;
			this.untilMillis = untilMillis;
			this.priorityMillis = priorityMillis;
		}
	}

	private final LongSupplier clock;
	private final Map<String, Holder> holders = new HashMap<>();
	private final Map<String, Req> queuedHead = new HashMap<>();
	private final Map<String, ArrayDeque<Req>> queued = new HashMap<>();
	private final java.util.Set<String> locks = new java.util.HashSet<>();
	/** Turnout requests, keyed for releaseAll / diagnostics. */
	public final Map<String, ObjectArrayList<Req>> byOwner = new HashMap<>();
	/** T1: node key ("x,y,z") -> the owner that pinned this turnout's position. */
	private final Map<String, Physical> physicalHolders = new HashMap<>();
	/** T1: node key -> mutually exclusive demands waiting for the turnout. */
	private final Map<String, ArrayDeque<PhysicalReq>> physicalQueued = new HashMap<>();
	private @Nullable TurnoutLookup turnoutLookup;
	private @Nullable PositionChangeGuard positionChangeGuard;

	public MmtrPointAuthority(LongSupplier clock) {
		this.clock = clock;
	}

	/**
	 * 挂上"改位置之前的一致性检查"（净空闸）。不挂 = 这一层不生效（老语义逐位不变，测试夹具就是这样）。
	 */
	public MmtrPointAuthority withPositionChangeGuard(@Nullable PositionChangeGuard guard) {
		this.positionChangeGuard = guard;
		return this;
	}

	/** 现在能不能把这处道岔改成 {@code newPosition}；返回原因 = 不能。 */
	private @Nullable String positionChangeBlockedReason(long x, long y, long z, int newPosition, String owner) {
		return positionChangeGuard == null ? null : positionChangeGuard.blockReason(x, y, z, newPosition, owner);
	}

	/**
	 * T1: attach the physical turnout layer (one turnout, one position). Fluent so the simulator can
	 * wire it into the field initialiser in one line; without it the layer is inert.
	 */
	public MmtrPointAuthority withTurnoutLookup(@Nullable TurnoutLookup lookup) {
		this.turnoutLookup = lookup;
		return this;
	}

	private @Nullable MmtrTurnout turnoutAt(long x, long y, long z) {
		return turnoutLookup == null ? null : turnoutLookup.turnoutAt(x, y, z);
	}

	private static String nodeKey(long x, long y, long z) {
		return x + "," + y + "," + z;
	}

	private static String keyOfNode(String nodeKey, String viaRailHex) {
		return nodeKey + "|" + viaRailHex;
	}

	private static String key(long x, long y, long z, String viaRailHex) {
		return x + "," + y + "," + z + "|" + viaRailHex;
	}

	/**
	 * Auto logic requests a leg of the ordered continuation list at (node, via). GRANTED means this
	 * owner currently holds the point (queued or re-requested requests refresh their window);
	 * QUEUED means the point is taken by another owner or operator-locked - the caller keeps the
	 * request alive by re-requesting and the walker waits at the point until its own grant lands.
	 */
	/**
	 * T1: request a leg, arbitrated through the physical turnout when there is one.
	 *
	 * <p>Order matters. The per-approach machine runs FIRST and unchanged, so "two trains on the same
	 * approach queue FIFO" keeps its exact old meaning; the physical layer only adds a second gate for
	 * the case the old model could not see - two trains from **different** approaches demanding
	 * mutually exclusive positions of one switch. When that happens the per-approach grant just issued
	 * is revoked **in the same call** (never across ticks: a half-held point is how hold-and-wait
	 * starts) and the demand waits on the turnout instead.</p>
	 */
	public Result request(long x, long y, long z, String viaRailHex, String owner, int leg, long untilMillis) {
		return request(x, y, z, viaRailHex, owner, leg, untilMillis, Long.MAX_VALUE);
	}

	/**
	 * 带**计划时刻优先权**的申请（notes/151）。
	 *
	 * @param priorityMillis 这一步的计划时刻（越小越早）。物理位置与逐进向两条队列都按它定序；
	 *                       {@link Long#MAX_VALUE} = 没有计划（旧调用方的行为不变：先到先得）
	 */
	public Result request(long x, long y, long z, String viaRailHex, String owner, int leg, long untilMillis, long priorityMillis) {
		final MmtrTurnout turnout = turnoutAt(x, y, z);
		if (turnout == null) {
			return requestPerApproach(x, y, z, viaRailHex, owner, leg, untilMillis, priorityMillis);
		}
		// 一处道岔只有两个位置：先把"某进向的第几条腿"翻译成**位置需求**。
		final int demand = turnout.positionForLeg(viaRailHex, leg);
		if (demand == Integer.MIN_VALUE) {
			return Result.REJECTED;
		}
		final Result perApproach = requestPerApproach(x, y, z, viaRailHex, owner, leg, untilMillis, priorityMillis);
		if (perApproach != Result.GRANTED) {
			return perApproach;   // 同进向排队 / 人工锁：语义与从前一致，物理层不参与
		}
		final String nk = nodeKey(x, y, z);
		final String k = key(x, y, z, viaRailHex);
		final long now = clock.getAsLong();
		expirePhysical(nk, now);
		final Physical holder = physicalHolders.get(nk);
		/*
		 * **位置空着、但队列里已经有人在等 ⇒ 排到队尾，不许插队**（2026-09-16 现场修）。
		 *
		 * <p>现场：北端咽喉三班车抢一处道岔，持有者被别人"让位"规则劝退之后位置一空，**它自己下一 tick
		 * 立刻重新申请**，而这里看到"没人持有"就直接判给了它 —— 队列里排第一的那班永远等不到，
		 * 现场表现就是"让位日志每 20 秒刷一次、车却谁也不动"（22:29–22:31 实测刷了 5 次）。</p>
		 *
		 * <p>先到先得：空位应当由**队列头**拿（{@link #retryPhysicalQueues} 每 tick 会把它判出去），
		 * 新来的申请只在队列为空时才直接拿位。</p>
		 */
		if (holder == null && physicalQueueHasOtherOwner(nk, owner)) {
			/*
			 * **但"位置已经就是我要的那一位"不排队**（2026-09-16 现场修，北端咽喉实测）。
			 *
			 * <p>现场读数（23:48，每 5 秒一行 QUEUED 刷了两分钟）：{@code 物理位置=0（正线贯通）/
			 * 物理持有者=（没有）/ 等待队列=[vA@1, vB@0]}。vA 那一条是**已经过了岔的车**留下的陈旧申请
			 * （它要岔股 1 出去，而岔现在在正线 0）—— 那一条要"扳一位"，而扳位被净空闸挡下
			 * （挡它的正是 vA 自己压在岔区的足迹，见 {@code 岔区净空被占}）⇒ **队列头永远推不动**；
			 * 同时 vB 要的**正是岔现在这一位**，它排在队尾，于是"没人锁、没人持有、位置也对，就是不给"。</p>
			 *
			 * <p>联锁的道理：**不扳岔就不存在"把道岔从车下抽走"**。一个不需要扳岔的申请与队列里的
			 * 等待者之间没有互相争用的东西 —— 它们争的是岔区这段路，那是闭塞与净空闸各管一层的活，
			 * 不该由"位置队列"来兼职。所以这一位已经在的话直接放行，不进净空闸、也不排队。
			 * 这与上面"位置相容：两列车要同一位就直接给"是同一条规则，只是这里要的是**当前那一位**。</p>
			 *
			 * <p>问不出实际位置时（测试夹具）行为不变：照旧排队。</p>
			 */
			final int actual = actualPosition(x, y, z);
			if (actual == NO_PHYSICAL_HOLDER || actual != demand) {
				enqueuePhysical(nk, owner, viaRailHex, leg, demand, untilMillis, priorityMillis);
				return Result.QUEUED;
			}
		}
		if (holder == null || holder.owner.equals(owner)) {
			/*
			 * 没人定这个位置，或者我本来就定着它 → 位置跟着我走。
			 *
			 * <p>但**改位置**要先过净空闸（{@link PositionChangeGuard}）：另一列车压在岔区上时不许改
			 * —— 那正是"把道岔从车下抽走"。请求方自己压在岔上不算（它按着自己的位，本来就该能改自己
			 * 的需要，否则换端/折返会把自己锁死）。被挡下来时与"互斥"同一处置：**收回刚发出的逐进向
			 * 授权**、改为在道岔上排队，绝不留下"半个持有"（T1b 的不变量）。</p>
			 *
			 * <p><b>"要不要扳一位"才决定问不问闸门</b>（2026-09-16 现场修的后半，与上面那条"不排队"
			 * 是同一次修的两半）：没人持有的时候位置由**世界现在在哪一位**决定 —— 它已经在我要的
			 * 那一位上，这一趟就不扳任何东西，净空闸（"不许把道岔从车下抽走"）也就无从谈起。
			 * 修前这里只看"我有没有持有"，于是"位置本来就对、但岔区上压着别的车"照样被挡下来排队，
			 * 而那种情况下根本没有东西要从谁脚下抽走。问不出实际位置时（测试夹具没挂查找）
			 * 按老语义保守地问闸门，既有用例逐位不变。</p>
			 */
			final boolean throwNeeded = holder != null
				? holder.position != demand
				: (actualPosition(x, y, z) == NO_PHYSICAL_HOLDER || actualPosition(x, y, z) != demand);
			if (throwNeeded && positionChangeBlockedReason(x, y, z, demand, owner) != null) {
				holders.remove(k);
				dropOwnerRequests(k, owner);
				promote(k, now);
				enqueuePhysical(nk, owner, viaRailHex, leg, demand, untilMillis, priorityMillis);
				return Result.QUEUED;
			}
			physicalHolders.put(nk, new Physical(owner, demand, untilMillis, priorityMillis));
			dropPhysicalQueued(nk, owner);
			return Result.GRANTED;
		}
		if (holder.position == demand) {
			// 位置相容：两列车要的是**同一位**，道岔不是它们之间的争用点（它们之间的冲突是
			// 共用轨段/对向，那由闭塞与 T1b 的敌对进路表负责，不归道岔这一层）。
			return Result.GRANTED;
		}
		/*
		 * **已经在岔里的车优先出清**（2026-09-16 现场修：北端两班车相持的最终环）。
		 *
		 * <p>现场读数：{@code 物理位置=0 持有者=vA … 队列=[vB@1]} —— vA 要正线（0）、站在岔外等着；
		 * vB 压在岔轨上、要岔股（1）出去。两边都要对方的位置：vA 因为 vB 占着它的出路线而不动、
		 * vB 因为拿不到 1 而出不去 ⇒ 位置永远不换手，两班车一起冻住（离线 1600 s 实测）。</p>
		 *
		 * <p>联锁的道理：**已经进了岔的车必须能出去**（否则把咽喉锁死）。所以请求方压在岔轨上、
		 * 而按着位置的那班车还在岔外等 ⇒ 位置直接给岔里的这一班（它出清之后位置自然释放，
		 * 队首的岔外车再拿）。这与"车压在岔上不扳"是同一条安全前提的不同侧面：不从车下抽位。</p>
		 */
		final boolean requesterInside = Boolean.TRUE.equals(ownerIsOnNodeRails(nk, owner));
		if (requesterInside && !holderStillOnNodeRails(nk, holder)) {
			System.out.println("[MMTR-PT] 岔内优先出清：把道岔 " + nk + " 的位置从 " + holder.owner + "（在岔外等）交给岔内的 " + owner);
			physicalHolders.put(nk, new Physical(owner, demand, untilMillis, priorityMillis));
			dropPhysicalQueued(nk, owner);
			return Result.GRANTED;
		}
		/*
		 * **优先级更高的车可以收回别人按着的位置**（notes/151 计划时刻；2026-09-27 加上服务等级/车号）。
		 *
		 * 六台车去同一个车站、计划到达 00:05 / 00:07 / … 时，位置该给 00:05 那台；等级不同时按运营规则
		 * "高铁踩通勤的头"、同级车号小的先：
		 *   - 只有**更该先走**（{@link #outranks} 的**真档位**：等级/车号 → 计划更早）才谈得上收回 ——
		 *     否则就是位置来回翻（ping-pong 的来源）；
		 *   - 而且要过**净空闸**：晚班车压在岔区里就不许从它脚下改位（那是把道岔抽走）；
		 *   - 收回之后晚班车排队等（它的进向行还在，位置不在它手里），等它自己再申请时会按优先权排队。
		 *
		 * <h3>2026-10-09：抢位这一档**不许再吃 owner id 那条兜底**</h3>
		 * <p>现场（引擎用例 {@code MmtrRouteConflictTests} 时红时绿）读数：咽喉口一处道岔，前车按住位置 0，
		 * 后车（同样是自动任务、同样**没有**计划时刻）申请位置 1。日志里那一行是</p>
		 * <pre>
		 *   [MMTR-PT] 优先权：把道岔 -20,0,0 的位置从 v1913021474444274896（无计划） 交给更该先走的 v1549095188365414779（无计划）
		 * </pre>
		 * <p>"（无计划）交给（无计划）"—— 两边根本没有优先权差别，{@link #outranks} 走到最后一档
		 * {@code owner.compareTo()} 就判了"后车更该先走"。而 owner 是 {@code "v"+车辆id}，**id 是随机的**：
		 * 于是同一段线路一半的运行里，已经按住的岔位会被一个毫无理由的后车抢走（前车的进路随即变 PENDING
		 * 重算，后车则冲进咽喉、在下一处岔口才被拦下）—— 这是**安全相关的不确定**，不是测试洁癖。</p>
		 * <p>修法：抢位只认"真档位"（{@link #outranksStrictly}）；{@code owner.compareTo} 那条兜底只留给
		 * **让位**判断（那里需要"只有一方让"的不对称性，见 {@link #someoneHasPriorityOver}）。</p>
		 */
		if (outranksStrictly(priorityMillis, owner, holder.priorityMillis, holder.owner)
			&& positionChangeBlockedReason(x, y, z, demand, owner) == null
			&& !holderStillOnNodeRails(nk, holder)) {
			System.out.println("[MMTR-PT] 优先权：把道岔 " + nk + " 的位置从 " + describeWaiter(holder.owner, holder.priorityMillis)
				+ " 交给更该先走的 " + describeWaiter(owner, priorityMillis));
			physicalHolders.put(nk, new Physical(owner, demand, untilMillis, priorityMillis));
			dropPhysicalQueued(nk, owner);
			return Result.GRANTED;
		}
		/*
		 * **车就压在这处道岔的轨上、而且它的计划要从这里过 ⇒ 位置冻结，先到先得**（2026-09-16 现场修：
		 * "北部掉头处两个车顶头"）。
		 *
		 * <p>现场：一班车在 31 m 折返段上换端后要**岔股**去 x=-170，另一班回程车要**正线**直着北上 ——
		 * 两个位置互斥，而"位置"在两班车之间**来回被抢**（优先权那条路各自把对方的位拿走），
		 * 于是两条进路永远 PENDING、谁也过不去，看起来就是两车对死（`point why` 里持有者一会儿 A
		 * 一会儿 B）。灯是对的、道岔模型也是对的，卡的是这条抢位规则。
		 *
		 * <p>现在：谁先拿到位置，只要它的车还压在这处道岔的三条轨上，位置就**只属于它** ——
		 * 后来的车按互斥在道岔上排队（FIFO），等它出清这三条轨（`expirePhysical` 那里一到期就放）
		 * 自然轮到下一个。两端都有等待的地方（回程车等在 36 m 段上、换端车等在这 31 m 段上），
		 * 所以"先到先得"就能把这处咽喉串起来。</p>
		 */
		// 互斥：收回刚发出的逐进向授权，改为在**道岔上**排队。
		holders.remove(k);
		dropOwnerRequests(k, owner);
		promote(k, now);
		enqueuePhysical(nk, owner, viaRailHex, leg, demand, untilMillis, priorityMillis);
		return Result.QUEUED;
	}

	/** 这处道岔的位置队列里，是否有**别人**在等（我自己的排队项、以及当前持有者自己的排队项都不算）。 */
	private boolean physicalQueueHasOtherOwner(String nk, String owner) {
		final ArrayDeque<PhysicalReq> q = physicalQueued.get(nk);
		if (q == null) {
			return false;
		}
		final Physical holder = physicalHolders.get(nk);
		for (final PhysicalReq r : q) {
			if (!r.owner.equals(owner) && (holder == null || !holder.owner.equals(r.owner))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 现在按着这处道岔位置的那列车，是不是**还压在这处道岔的轨上**（是 ⇒ 位置不能从它脚下拿走）。
	 * 查不出来（{@code null}）按"不在"处理 —— 保持既有语义（测试夹具/非车辆持有者照旧可以被抢位）。
	 */
	private boolean holderStillOnNodeRails(String nk, Physical holder) {
		return Boolean.TRUE.equals(ownerIsOnNodeRails(nk, holder.owner));
	}

	/** 同上，但**确知**持有者已不在岔轨上（{@code FALSE}）；查不出来不放位。（目前没有调用方，留作后续钩子。） */
	@SuppressWarnings("unused")
	private boolean holderKnownAwayFromNodeRails(String nk, Physical holder) {
		return Boolean.FALSE.equals(ownerIsOnNodeRails(nk, holder.owner));
	}

	/** 某 owner（{@code "v"+车辆id}）是不是还压在这处道岔的轨上（{@code null} = 查不出来）。 */
	private @Nullable Boolean ownerIsOnNodeRails(String nk, String owner) {
		final long[] node = parseNodeKey(nk);
		return node == null || holderOccupancy == null ? null : holderOccupancy.ownerIsOnNodeRails(node[0], node[1], node[2], owner);
	}

	/**
	 * T1b: acquire a WHOLE set of point demands atomically - all of it, or none of it.
	 *
	 * <p>Why this is the deadlock cure. Before, {@code requestForkOps} walked the set one point at a
	 * time and a failure only set a flag: <strong>it never rolled back</strong> the grants it had already
	 * taken. Combined with the per-tick window refresh (a grant never expires while the mission is
	 * armed), two trains needing the same two points in different orders produced A holds P waiting for
	 * Q, B holds Q waiting for P - a <strong>permanent circular wait</strong>. Atomic acquisition removes
	 * hold-and-wait by construction: fail to take the set and your hands are empty.</p>
	 *
	 * <p>The operational definition of the unit is "the set this train is currently approaching" (the
	 * approach window, ~one throat's worth of points), so a far point is still never pre-occupied - the
	 * approach-locking property the old design wanted. The set is also released as a whole on failure,
	 * including any part of it this owner already held from an earlier tick: partial holding is exactly
	 * what the invariant forbids.</p>
	 *
	 * <p>Implementation is dry-run-then-commit: the whole set is evaluated read-only first, and only if
	 * every member is obtainable are the real requests issued (each then succeeds; single-threaded, and
	 * nothing can interleave inside this call). The defensive rollback below should therefore be
	 * unreachable - it exists so the invariant holds even if that reasoning is ever broken.</p>
	 */
	public Result requestAtomically(@Nullable ObjectArrayList<String[]> ops, String owner, long untilMillis) {
		return requestAtomically(ops, owner, untilMillis, Long.MAX_VALUE);
	}

	/**
	 * As above, with an explicit 计划时刻 priority ({@code priorityMillis}, smaller = earlier).
	 * {@link Long#MAX_VALUE} means "no plan": the wait is then ordered purely by arrival, which is the
	 * pre-T1b behaviour. T5 (timetable pre-planning) is what will actually fill this in.
	 */
	public Result requestAtomically(@Nullable ObjectArrayList<String[]> ops, String owner, long untilMillis, long priorityMillis) {
		if (ops == null || ops.isEmpty()) {
			return Result.GRANTED;
		}		/*
		 * **同一处道岔只认最先要过的那一程**（notes/137）。
		 *
		 * <p>折返（牵出—推进）会让同一处道岔在一次申请里出现两次，两程要**互斥的两个位置**。
		 * 一组自相矛盾的申请如果整组照办，最后那个需求会把它自己的位按上（一处道岔只有一个位置），
		 * 而进路判定看的是**最先要过的那一程** —— 于是"手里按着 1、进路需要 0"，车永远停在自己的
		 * 出发信号前（现场实测）。申请集按行进次序给（最近的在前），所以**第一个说了算**。</p>
		 *
		 * <p>放在这一层是刻意的：调用方（车辆）也做了同样的去重，但"一组申请不许自相矛盾"是权限层
		 * 自己的不变量，不该依赖调用方守规矩。</p>
		 */
		final ObjectArrayList<String[]> effective = new ObjectArrayList<>();
		final java.util.HashSet<String> claimedNodes = new java.util.HashSet<>();
		for (final String[] op : ops) {
			if (claimedNodes.add(op[0] + "," + op[1] + "," + op[2])) {
				effective.add(op);
			}
		}
		final long now = clock.getAsLong();
		for (final String[] op : effective) {
			final long x = Long.parseLong(op[0]);
			final long y = Long.parseLong(op[1]);
			final long z = Long.parseLong(op[2]);
			final String via = op[3];
			final MmtrTurnout turnout = turnoutAt(x, y, z);
			if (turnout != null) {
				final int demand = turnout.positionForLeg(via, Integer.parseInt(op[4]));
				if (demand == Integer.MIN_VALUE) {
					lastWaitReason.put(owner, describeRefusal(op, "组合不存在（positionForLeg 判死）", turnout, demand, now));
					return Result.REJECTED;   // 整组里有物理上不存在的组合 → 整组都不申请
				}
				if (!physicallyGrantableTo(nodeKey(x, y, z), demand, owner, now)) {
					lastWaitReason.put(owner, describeRefusal(op, "道岔位置给不了（物理层/净空闸）", turnout, demand, now));
					releaseSet(effective, owner);
					queueSet(effective, owner, untilMillis, priorityMillis);
					return Result.QUEUED;
				}
			}
			if (!perApproachGrantableTo(key(x, y, z, via), owner, now)) {
				lastWaitReason.put(owner, describeRefusal(op, "进向被别人持有/被人工锁", turnout, -1, now));
				releaseSet(effective, owner);
				queueSet(effective, owner, untilMillis, priorityMillis);
				return Result.QUEUED;
			}
		}
		for (final String[] op : effective) {
			if (request(Long.parseLong(op[0]), Long.parseLong(op[1]), Long.parseLong(op[2]), op[3], owner,
					Integer.parseInt(op[4]), untilMillis, priorityMillis) != Result.GRANTED) {
				lastWaitReason.put(owner, describeRefusal(op, "最后一步授予被拒", turnoutAt(Long.parseLong(op[0]), Long.parseLong(op[1]), Long.parseLong(op[2])), -1, now));
				releaseSet(effective, owner);
				queueSet(effective, owner, untilMillis, priorityMillis);
				return Result.QUEUED;
			}
		}
		lastWaitReason.remove(owner);
		return Result.GRANTED;
	}

	/**
	 * **到底是哪一处、哪一道门挡住了**（notes/149）。
	 *
	 * <p>为什么要记这句话：请求集合是**原子**的（一处不成，整组都不申请），而现场日志只报
	 * "第一处没有持有的道岔" —— 那一处往往只是还没轮到，真正把整组按下去的是集合里**后面**某一处。
	 * 于是操作者看到的是"没人锁、没人持有、就是不给"，而原因（净空闸 / 人工锁 / 位置给不了）
	 * 只有在权限层内部才知道。这里把它记成一句人话，由日志/接口读出去。</p>
	 */
	private String describeRefusal(String[] op, String gate, @Nullable MmtrTurnout turnout, int demand, long now) {
		final long x = Long.parseLong(op[0]);
		final long y = Long.parseLong(op[1]);
		final long z = Long.parseLong(op[2]);
		final StringBuilder sb = new StringBuilder();
		sb.append(gate).append("：point ").append(x).append(',').append(y).append(',').append(z)
			.append(" wantLeg=").append(op[4]);
		if (demand != Integer.MIN_VALUE) {
			sb.append(" needPos=").append(demand);
		}
		sb.append(" | ").append(state(x, y, z, op[3]));
		final String physical = physicalHolder(x, y, z);
		sb.append(" phys=").append(physical == null ? "-" : physical);
		if (turnout != null) {
			sb.append(" 现位=").append(physicalPosition(x, y, z) == NO_PHYSICAL_HOLDER ? "无主" : String.valueOf(physicalPosition(x, y, z)));
			if (demand != Integer.MIN_VALUE) {
				final String blocked = positionChangeBlockedReason(x, y, z, demand, op.length > 5 ? op[5] : "");
				if (blocked != null) {
					sb.append(" 净空闸=").append(blocked);
				}
			}
		}
		return sb.toString();
	}

	/** 上一次原子申请被拒的原因（owner → 一句人话）；已授予/没申请过 = null。 */
	public @Nullable String lastWaitReason(String owner) {
		return lastWaitReason.get(owner);
	}

	/** 只读：这个进向现在能不能给我（没有人工锁、没有别人持有）。 */
	private boolean perApproachGrantableTo(String k, String owner, long now) {
		expireLocked(k, now);
		if (locks.contains(k)) {
			return false;
		}
		final Holder h = holders.get(k);
		return h == null || h.owner.equals(owner);
	}

	/** 只读：这处道岔现在能不能按我要的位置给我（无持有者、是我自己、或位置相容；改位置还要过净空闸）。 */
	private boolean physicallyGrantableTo(String nk, int demand, String owner, long now) {
		expirePhysical(nk, now);
		final Physical holder = physicalHolders.get(nk);
		if (holder == null || holder.owner.equals(owner) || holder.position == demand) {
			// 位置不变（相容）就不算"改位置"，净空闸不参与；真要改一位才问它。
			if (holder != null && holder.position == demand) {
				return true;
			}
			final String[] node = nk.split(",");
			return positionChangeBlockedReason(Long.parseLong(node[0]), Long.parseLong(node[1]), Long.parseLong(node[2]), demand, owner) == null;
		}
		return false;
	}

	/** 原子组的"全无"一半：把本 owner 在这组里持有的**一切**让出去（含它上一 tick 就有的）。 */
	private void releaseSet(ObjectArrayList<String[]> ops, String owner) {
		final long now = clock.getAsLong();
		for (final String[] op : ops) {
			final long x = Long.parseLong(op[0]);
			final long y = Long.parseLong(op[1]);
			final long z = Long.parseLong(op[2]);
			final String k = key(x, y, z, op[3]);
			final Holder h = holders.get(k);
			if (h != null && h.owner.equals(owner)) {
				holders.remove(k);
				dropOwnerRequests(k, owner);
				promote(k, now);
			}
			releasePhysicalIfHolder(x, y, z, owner, now);
		}
	}

	/** 原子组等待时：在**整组每一处**排队（保住 FIFO 位置），幂等刷新窗口。 */
	private void queueSet(ObjectArrayList<String[]> ops, String owner, long untilMillis, long priorityMillis) {
		for (final String[] op : ops) {
			final long x = Long.parseLong(op[0]);
			final long y = Long.parseLong(op[1]);
			final long z = Long.parseLong(op[2]);
			final String via = op[3];
			final int leg = Integer.parseInt(op[4]);
			enqueueIdempotent(key(x, y, z, via), owner, leg, untilMillis, priorityMillis);
			final MmtrTurnout turnout = turnoutAt(x, y, z);
			if (turnout != null) {
				final int demand = turnout.positionForLeg(via, leg);
				if (demand != Integer.MIN_VALUE) {
					enqueuePhysical(nodeKey(x, y, z), owner, via, leg, demand, untilMillis, priorityMillis);
				}
			}
		}
	}

	private void enqueueIdempotent(String k, String owner, int leg, long untilMillis, long priorityMillis) {
		final Req existing = findQueued(k, owner);
		if (existing != null) {
			existing.leg = leg;
			existing.untilMillis = untilMillis;
			existing.priorityMillis = Math.min(existing.priorityMillis, priorityMillis);
			// enqueuedAtMillis 保持不变：等待时长要累计，否则每 tick 的重复申请会让防饿死永不触发。
			return;
		}
		enqueue(k, new Req(owner, leg, untilMillis, priorityMillis));
	}

	/**
	 * The original per-approach machine (P3): one grant per (node, approach), competing requests queue
	 * FIFO, an operator lock parks the approach, grants die with their window.
	 */
	public Result requestPerApproach(long x, long y, long z, String viaRailHex, String owner, int leg, long untilMillis) {
		return requestPerApproach(x, y, z, viaRailHex, owner, leg, untilMillis, Long.MAX_VALUE);
	}

	/**
	 * 同上，但把**这一步的计划时刻**（{@code priorityMillis}）也带进逐进向这一层（notes/155 §13）。
	 *
	 * <p>为什么：{@link #request} 在没有真实道岔对象时会走这一层（测试网、纯逐进向的点），
	 * 而原来这一层把优先权丢掉了（{@code new Req(owner, leg, untilMillis)} ⇒ priority = MAX）——
	 * 于是"谁该先走"的判据在这些场合全变成"先到先得"，让位策略也就认不出优先权。
	 * 旧签名保持"先到先得"（既有调用方/用例行为不变）。</p>
	 */
	public Result requestPerApproach(long x, long y, long z, String viaRailHex, String owner, int leg, long untilMillis, long priorityMillis) {
		final String k = key(x, y, z, viaRailHex);
		final long now = clock.getAsLong();
		expireLocked(k, now);
		if (locks.contains(k)) {
			final Req existing = findQueued(k, owner);
			if (existing != null) {
				existing.leg = leg;
				existing.untilMillis = untilMillis;
				existing.priorityMillis = Math.min(existing.priorityMillis, priorityMillis);
				return Result.QUEUED;
			}
			enqueue(k, new Req(owner, leg, untilMillis, priorityMillis));
			return Result.QUEUED;
		}
		final Holder h = holders.get(k);
		if (h != null) {
			if (h.owner.equals(owner)) {
				h.leg = leg;
				h.untilMillis = untilMillis;
				h.priorityMillis = Math.min(h.priorityMillis, priorityMillis);
				return Result.GRANTED;
			}
			final Req existing = findQueued(k, owner);
			if (existing != null) {
				existing.leg = leg;
				existing.untilMillis = untilMillis;
				existing.priorityMillis = Math.min(existing.priorityMillis, priorityMillis);
				return Result.QUEUED;
			}
			enqueue(k, new Req(owner, leg, untilMillis, priorityMillis));
			return Result.QUEUED;
		}
		holders.put(k, new Holder(new Req(owner, leg, untilMillis, priorityMillis)));
		byOwner.computeIfAbsent(owner, o -> new ObjectArrayList<>()).add(holders.get(k));
		return Result.GRANTED;
	}

	private @Nullable Req findQueued(String k, String owner) {
		final ArrayDeque<Req> q = queued.get(k);
		if (q != null) {
			for (final Req r : q) {
				if (r.owner.equals(owner)) {
					return r;
				}
			}
		}
		return null;
	}

	/** Active holder of the point right now (validated against the clock), or null. */
	public @Nullable String holder(long x, long y, long z, String viaRailHex) {
		final String k = key(x, y, z, viaRailHex);
		expireLocked(k, clock.getAsLong());
		final Holder h = holders.get(k);
		return h == null ? null : h.owner;
	}

	/** Leg index the current holder was granted at this point, or -1. */
	public int grantedLeg(long x, long y, long z, String viaRailHex) {
		final String k = key(x, y, z, viaRailHex);
		expireLocked(k, clock.getAsLong());
		final Holder h = holders.get(k);
		return h == null ? -1 : h.leg;
	}

	/** The train crossed the point: its grant (or queued entry) is consumed and the queue advances. */
	public void passed(long x, long y, long z, String viaRailHex, String owner) {
		final String k = key(x, y, z, viaRailHex);
		final long now = clock.getAsLong();
		final Holder h = holders.get(k);
		if (h != null && h.owner.equals(owner)) {
			holders.remove(k);
			dropOwnerRequests(k, owner);
			promote(k, now);
		}
		// T1: crossing also gives up the physical position if this owner was the one defining it, so the
		// next mutually exclusive demand in the turnout queue gets its position (and its grant) at once.
		// A compatible co-holder is not the position owner and is deliberately left alone.
		releasePhysicalIfHolder(x, y, z, owner, now);
	}

	/** Operator parks the point: auto requests queue until unlocked; nothing else changes. */
	public void lock(long x, long y, long z, String viaRailHex) {
		locks.add(key(x, y, z, viaRailHex));
	}

	/**
	 * 清掉**全部**人工锁，返回清掉几把（用户 2026-09-14 现场需要：Web 上「锁闭」数字归零）。
	 *
	 * <p>为什么要"全部"而不是"逐个解"：{@code mmtr-points} 是按**进向行**给的，而一行道岔
	 * 派生出来的三行里，只有能被网页画出来的那些进向才看得见 —— 人工搬岔一次锁的是**三条进向**，
	 * 另外两条在界面上根本没有对应的按钮可点。逐行解 = 只解了一半，剩下的一半重启后原样回来
	 * （实测：网页显示 0 处锁闭，存档里还躺着 20 条）。所以这里按"引擎自己持有的键"清，
	 * 不经过界面能表达的范围。</p>
	 *
	 * <p>每清一把都走 {@link #promote}：排队的自动申请该立刻接手，不能等到下一个 tick
	 * （与单把 {@link #unlock} 行为一致）。</p>
	 */
	public int clearLocks() {
		final java.util.List<String> all = new java.util.ArrayList<>(locks);
		for (final String k : all) {
			locks.remove(k);
			promote(k, clock.getAsLong());
		}
		return all.size();
	}

	/**
	 * **一处道岔是不是被人工锁着**：任一进向有锁即算。
	 *
	 * <p>用户 2026-09-14 的选择："人工搬岔同时把道岔锁住（永久生效直到解锁）"。一处道岔只有一个位置，
	 * 所以只要有一个进向被人工锁住，这个位置就不许自动扳 —— 否则列车会从另一个进向的授权把人工位顶掉
	 * （实测：`manualOperatorBranchOutranksTheVehiclesOwnGrant` 正是这样红掉的）。</p>
	 */
	public boolean isTurnoutLocked(long x, long y, long z, MmtrTurnout turnout) {
		return isLocked(x, y, z, turnout.stemRailHex) || isLocked(x, y, z, turnout.farRailHex) || isLocked(x, y, z, turnout.branchRailHex);
	}

	/** 人工锁的全部键（{@code "x,y,z|via"}）：落盘用（重启后仍然生效，直到 {@code point unlock}）。 */
	public java.util.List<String> locksSnapshot() {
		return new java.util.ArrayList<>(locks);
	}

	/** 从存档恢复一把人工锁（键形如 {@code "x,y,z|via"}）。 */
	public void restoreLock(String lockKey) {
		locks.add(lockKey);
	}

	/**
	 * **把一把人工锁换到另一个 via 键上**（hex 写法归一化用，notes/130 §6b）：世界改画之后旧键会
	 * 静默失效（锁还在文件里，却锁不住任何东西），归一化让旧锁继续生效。
	 *
	 * @return 是否真的改了（没这把锁、或新旧写法相同 = false）
	 */
	public boolean rekeyLock(long x, long y, long z, String fromVia, String toVia) {
		final String from = key(x, y, z, fromVia);
		if (!locks.contains(from) || fromVia.equals(toVia)) {
			return false;
		}
		locks.remove(from);
		locks.add(key(x, y, z, toVia));
		return true;
	}

	public boolean isLocked(long x, long y, long z, String viaRailHex) {
		return locks.contains(key(x, y, z, viaRailHex));
	}

	/**
	 * **等道岔等多久就主动让位**（notes/149 现场）。
	 *
	 * <p>为什么需要一条时间策略：道岔的持有关系**跨 tick 存活**，而释放只发生在越岔/换路的时候 ——
	 * 一台自己也不动的车会一直按着某个位置，另一台要互斥位置的车永远等不到，几台车一起僵在咽喉里
	 * （现场：一台按着 (-170,-60,-186) 的位置 1，另外两台排在这处要位置 0，车场里六台车一台都出不去）。
	 * 道岔只有一个位置，解环必须有一方先退。</p>
	 *
	 * <p>退多久：{@value #MMTR_TURNOUT_YIELD_MILLIS} 毫秒。短了会把"前车正在过岔"这种正常等待
	 * 误判成僵局（让位反而添乱），长了操作者会觉得"它就是不动"。让位之后还要**静默同样长的一段时间**，
	 * 否则两台车会同时让位、同时再申请，谁也拿不到。</p>
	 */
	public static final long MMTR_TURNOUT_YIELD_MILLIS = 20_000L;

	/**
	 * 该不该让位：等够了、且不在上一次让位的静默窗口里。
	 *
	 * @param now              现在（毫秒）
	 * @param waitSinceMillis  从什么时候开始等（0 = 没在等）
	 * @param yieldUntilMillis 让位静默窗口到什么时候（0 = 不在窗口里）
	 */
	public static boolean shouldYieldForOthers(long now, long waitSinceMillis, long yieldUntilMillis) {
		if (waitSinceMillis == 0 || now < yieldUntilMillis) {
			return false;
		}
		return now - waitSinceMillis >= MMTR_TURNOUT_YIELD_MILLIS;
	}

	/**
	 * **该不该让位（完整判据）**：等够了 + 不在静默窗口里 + **挡着的人比我更该走**。
	 *
	 * <p>为什么第三条是必须的（notes/155 §13 现场）：只按时间判会让**领先车也把自己刚拿到的位置放掉** ——
	 * 后车于是拿到位置、前车再申请、再让 —— 现场每 20 秒一轮的"让位"日志就是这么来的，
	 * 几台车谁也走不了。道岔只有一个位置，解环要让**该让的那一方**退：**服务等级高、车号小的先走**
	 * （用户 2026-09-27），同级再看计划时刻更早（与 {@code priorityMillis} 的通行优先权同一口径），
	 * 而不是"谁等得久谁退"。</p>
	 *
	 * <p>同优先权（现场常见：两台车都还没算出计划时刻）时按持有者 id 定序 —— 关键是**判断必须不对称**，
	 * 保证同一时刻只有一方认为自己该让（两边同时让位等于回到振荡）。</p>
	 */
	public boolean shouldYieldForOthers(String owner, long now, long waitSinceMillis, long yieldUntilMillis) {
		return shouldYieldForOthers(now, waitSinceMillis, yieldUntilMillis) && someoneHasPriorityOver(owner);
	}

	/**
	 * 我按着（或排在前面的）那些道岔上，有没有**比我更该先走**的等待者要一个跟我互斥的位置。
	 *
	 * <p>两层都看：物理位置（{@code physicalHolders}）与逐进向持有（{@code holders}）。</p>
	 */
	public boolean someoneHasPriorityOver(String owner) {
		for (final Map.Entry<String, Physical> entry : physicalHolders.entrySet()) {
			final Physical mine = entry.getValue();
			if (!mine.owner.equals(owner)) {
				continue;
			}
			final ArrayDeque<PhysicalReq> queue = physicalQueued.get(entry.getKey());
			if (queue == null) {
				continue;
			}
			for (final PhysicalReq other : queue) {
				// 要的是同一个位子 ⇒ 不冲突（它排在我后面等同一个位置而已）
				if (other.owner.equals(owner) || other.position == mine.position) {
					continue;
				}
				if (outranks(other.priorityMillis, other.owner, mine.priorityMillis, owner)) {
					return true;
				}
			}
		}
		for (final Map.Entry<String, Holder> entry : holders.entrySet()) {
			final Holder mine = entry.getValue();
			if (!mine.owner.equals(owner)) {
				continue;
			}
			final ArrayDeque<Req> queue = queued.get(entry.getKey());
			if (queue == null) {
				continue;
			}
			for (final Req other : queue) {
				if (other.owner.equals(owner) || other.leg == mine.leg) {
					continue;
				}
				if (outranks(other.priorityMillis, other.owner, mine.priorityMillis, owner)) {
					return true;
				}
			}
		}
		return false;
	}

	/** {@code other} 是不是比 {@code mine} 更该先走：**服务等级/车号 → 计划时刻 → owner id**。 */
	private boolean outranks(long otherPriority, String otherOwner, long minePriority, String mineOwner) {
		/*
		 * 第一档：服务等级 + 车号（{@link MmtrTrainPriority}，用户 2026-09-27）。
		 *
		 * <p>这是**运营规则**："高铁踩通勤的头、同级车号小者先"。有作业单的车（问得出优先级）一律排在
		 * 问不出优先级的车（玩家车、无单调车）前面 —— 系统里的车有它的位置，散车没有。
		 * 两边等级与车号都相同、或都没有作业单 ⇒ 这一档说不出先后，落到下面的计划时刻。</p>
		 */
		final MmtrTrainPriority other = priorityOf(otherOwner);
		final MmtrTrainPriority mine = priorityOf(mineOwner);
		if (other != null || mine != null) {
			if (other == null) {
				return false;
			}
			if (mine == null) {
				return true;
			}
			if (other.outranks(mine)) {
				return true;
			}
			if (mine.outranks(other)) {
				return false;
			}
		}
		if (otherPriority != minePriority) {
			return otherPriority < minePriority;
		}
		// 同优先权时按持有者 id 定序，保证**判断不对称**（两边同时让位等于回到振荡）
		return otherOwner.compareTo(mineOwner) < 0;
	}

	/**
	 * 与 {@link #outranks} 同前两档（服务等级/车号 → 计划时刻），但**去掉最后的 owner id 兜底**。
	 *
	 * <h3>为什么必须分出来（2026-10-09）</h3>
	 * <p>{@code outranks} 的最后一档 {@code owner.compareTo()} 是为了让"让位"判断**不对称**（两边同时让 =
	 * 回到振荡），它的两个输入是随机的 {@code "v"+车辆id}。这个"谁排前面"的口径**可以**决定"谁让位"，
	 * 但**不可以**决定"谁能把别人already hold 的物理位置收走" —— 那会变成：同一段线路一半的运行里
	 * 道岔从已经按住它的车手里被抢走（见 {@code request} 里抢位分支的注释）。</p>
	 *
	 * <p>所以抢位只认真差别：等级/车号严格更优，或计划时刻严格更早。两边"无计划"、等级车号也一样时，
	 * 抢位这一档**不成立**，后车老老实实排队（先到先得）。</p>
	 */
	private boolean outranksStrictly(long otherPriority, String otherOwner, long minePriority, String mineOwner) {
		final MmtrTrainPriority other = priorityOf(otherOwner);
		final MmtrTrainPriority mine = priorityOf(mineOwner);
		if (other != null || mine != null) {
			if (other == null) {
				return false;
			}
			if (mine == null) {
				return true;
			}
			if (other.outranks(mine)) {
				return true;
			}
			if (mine.outranks(other)) {
				return false;
			}
		}
		if (otherPriority != minePriority) {
			return otherPriority < minePriority;
		}
		return false;
	}

	/** Operator releases the park: the longest-waiting auto request takes the point. */
	public void unlock(long x, long y, long z, String viaRailHex) {
		final String k = key(x, y, z, viaRailHex);
		locks.remove(k);
		promote(k, clock.getAsLong());
	}

	/** Explicit release of one point by its holder (mission gave up / re-plan). */
	public void release(long x, long y, long z, String viaRailHex, String owner) {
		passed(x, y, z, viaRailHex, owner);
	}

	/** Terminal mission / vehicle teardown: drop every point this owner holds or queued for. */
	public void releaseAll(String owner) {
		final long now = clock.getAsLong();
		byOwner.remove(owner);
		final ObjectArrayList<String> ownedKeys = new ObjectArrayList<>();
		for (final Map.Entry<String, Holder> e : holders.entrySet()) {
			if (e.getValue().owner.equals(owner)) {
				ownedKeys.add(e.getKey());
			}
		}
		for (final String k : ownedKeys) {
			holders.remove(k);
			promote(k, now);
		}
		queued.entrySet().removeIf(e -> e.getValue().removeIf(q -> q.owner.equals(owner)));
		// T1: same treatment for the physical layer - give up any pinned position, leave the turnout
		// queue, and let the next mutually exclusive demand take over.
		final ObjectArrayList<String> physicalKeys = new ObjectArrayList<>();
		for (final Map.Entry<String, Physical> e : physicalHolders.entrySet()) {
			if (e.getValue().owner.equals(owner)) {
				physicalKeys.add(e.getKey());
			}
		}
		for (final String nk : physicalKeys) {
			physicalHolders.remove(nk);
			promotePhysical(nk, now);
		}
		for (final Map.Entry<String, ArrayDeque<PhysicalReq>> e : physicalQueued.entrySet()) {
			e.getValue().removeIf(q -> q.owner.equals(owner));
		}
		physicalQueued.entrySet().removeIf(e -> e.getValue().isEmpty());
	}

	public boolean isGrantedTo(long x, long y, long z, String viaRailHex, String owner) {
		final String k = key(x, y, z, viaRailHex);
		expireLocked(k, clock.getAsLong());
		final Holder h = holders.get(k);
		return h != null && h.owner.equals(owner);
	}

	/** Queue snapshot for the UI: the owners queued on this point (oldest first), as owner@leg. */
	public ObjectArrayList<String> queuedSnapshot(long x, long y, long z, String viaRailHex) {
		final ObjectArrayList<String> out = new ObjectArrayList<>();
		final String k = key(x, y, z, viaRailHex);
		expireLocked(k, clock.getAsLong());
		final ArrayDeque<Req> q = queued.get(k);
		if (q != null) {
			for (final Req r : q) {
				out.add(r.owner + "@" + r.leg);
			}
		}
		return out;
	}

	public String state(long x, long y, long z, String viaRailHex) {
		final String k = key(x, y, z, viaRailHex);
		final long now = clock.getAsLong();
		expireLocked(k, now);
		final Holder h = holders.get(k);
		final StringBuilder sb = new StringBuilder();
		sb.append("lock=").append(locks.contains(k));
		sb.append(" holder=").append(h == null ? "-" : h.owner + "@" + h.leg + " until=" + h.untilMillis);
		final ArrayDeque<Req> q = queued.get(k);
		if (q != null && !q.isEmpty()) {
			sb.append(" queue=");
			for (final Req r : q) {
				sb.append(r.owner).append("@").append(r.leg).append(" ");
			}
		}
		return sb.toString();
	}

	/* ------------------------------------------------------------------ *
	 * T1: the physical turnout layer (one turnout, one position)
	 * ------------------------------------------------------------------ */

	/**
	 * Which position the turnout at this node is currently pinned to, or {@link #NO_PHYSICAL_HOLDER}
	 * when nobody holds it (then the persisted/derived position stands and the interlocking is free to
	 * move it for the next route).
	 */
	public int physicalPosition(long x, long y, long z) {
		final String nk = nodeKey(x, y, z);
		expirePhysical(nk, clock.getAsLong());
		final Physical holder = physicalHolders.get(nk);
		return holder == null ? NO_PHYSICAL_HOLDER : holder.position;
	}

	/** The owner currently defining this turnout's position, or null. */
	public @Nullable String physicalHolder(long x, long y, long z) {
		final String nk = nodeKey(x, y, z);
		expirePhysical(nk, clock.getAsLong());
		final Physical holder = physicalHolders.get(nk);
		return holder == null ? null : holder.owner;
	}

	/** 只读：某列车是不是还压在这处节点的轨上（{@code null} = 查不出来，例如测试里的标签 owner）。 */
	public @Nullable Boolean ownerOnNodeRails(long x, long y, long z, String owner) {
		return ownerIsOnNodeRails(nodeKey(x, y, z), owner);
	}

	/**
	 * 只读：{@code owner} 是不是这处道岔**"正在用"的那一方** —— 它按着位置、按的又正是**自己要的**那一位、
	 * 而且人就压在这处道岔的轨上。
	 *
	 * <h3>为什么让位规则必须问这一句（2026-09-17 现场：北端折返咽喉两班车互让到死）</h3>
	 * <p>现场读数：车 B 在 36 m 正线轨上、按着位置 0（**正是它自己要的位**，它要直着开进 31 m 折返段）；
	 * 车 A 在斜线上排队要位置 1。而"停着不动的车"那条让位规则只要看见**有人排在我按着的位置后面**
	 * 就放掉自己 —— 于是 B 每 20 秒让一次、A 拿到 1；A 又因为同样的判据在 20 秒后让出去、B 再拿回 0……
	 * **位置每 20 秒换一次手，两班车谁也没动**（日志里 {@code 让位（停着不动）} 每 20 秒一行刷了十几分钟）。</p>
	 *
	 * <p>而这时候的正确行为是确定的：**B 是在用的那一方**（它要的位就是当前的位、它的车还压在岔轨上），
	 * 它只要往前走一步就出清了；A 要的位与它互斥，只能等。规则不能把"正在用"的车劝退 —— 那不是破环，
	 * 那是把唯一能解开这个环的动作取消掉。</p>
	 *
	 * <p>"压在这处道岔的轨上"这一条不能少：车已经出清到岔外时，它按着的位才是真的挡着别人（它自己
	 * 一时半会儿不会再用），那种情况照旧让位。查不出占用（{@code null}）按**在**处理（宁可不劝退）。</p>
	 *
	 * @param demand 这列车**自己计划**在这处道岔上要的位置（由调用方按它的进路算出来）
	 */
	public boolean holdsThePositionItNeeds(long x, long y, long z, String owner, int demand) {
		if (demand == Integer.MIN_VALUE) {
			return false;
		}
		final String nk = nodeKey(x, y, z);
		expirePhysical(nk, clock.getAsLong());
		final Physical holder = physicalHolders.get(nk);
		if (holder == null || !holder.owner.equals(owner) || holder.position != demand) {
			return false;
		}
		return !Boolean.FALSE.equals(ownerIsOnNodeRails(nk, owner));
	}

	/**
	 * **诊断出口**：现在能不能把这处道岔扳到 {@code newPosition}；返回原因 = 不能。
	 *
	 * <p>为什么要公开（notes/149）：请求被拒时排队信息里只有 {@code lock=false holder=-}——
	 * 也就是"没人锁、没人持有"，看起来像引擎在无缘无故地卡自己。真正的原因往往在**净空闸**上
	 * （另一列车压在岔区里），而那句话以前只活在内部判定里，日志/接口一个字都不说 ——
	 * 现场于是没法回答"到底是谁挡着"。这条只读出口就是让那句话能被看见。</p>
	 */
	public @Nullable String positionChangeBlocked(long x, long y, long z, int newPosition, String owner) {
		return positionChangeBlockedReason(x, y, z, newPosition, owner);
	}

	/**
	 * **本 owner 现在按着位置的道岔**，逐处给出 {@code [x, y, z]}（按节点键定序，确定）。
	 *
	 * <p>给"重新规划时放掉旧计划留下的位置"用（notes/136 §3）：持有表在权限层内部，调用方只能
	 * 通过这个只读出口看见自己按了哪些道岔，再逐个 {@link #releasePhysicalHold}。</p>
	 */
	public ObjectArrayList<long[]> physicalHoldNodesOf(String owner) {
		final long now = clock.getAsLong();
		final ObjectArrayList<String> keys = new ObjectArrayList<>(physicalHolders.keySet());
		keys.sort(null);
		final ObjectArrayList<long[]> out = new ObjectArrayList<>();
		for (final String nk : keys) {
			expirePhysical(nk, now);
			final Physical holder = physicalHolders.get(nk);
			if (holder == null || !holder.owner.equals(owner)) {
				continue;
			}
			final String[] parts = nk.split(",");
			out.add(new long[]{Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2])});
		}
		return out;
	}

	/**
	 * **放掉本 owner 在某处道岔上的位置持有**（重新规划 / 计划作废时用）：该处立刻让位，队列头接手
	 * （{@link #promotePhysical}），其余持有不动。
	 *
	 * <p>与 {@link #releaseAll} 的区别只有粒度。为什么需要它：旧计划按下的位置**不会随计划消失**
	 * ——它只在"列车跨过岔口"或"任务终态"时释放。于是"先按了位置 1、没跨过去、又重规划要位置 0"
	 * 会让这处道岔谁也扳不动（持有者就是它自己），车永远停在出发信号前（notes/136 §3 的现场）。</p>
	 *
	 * @return 真的放掉了（没持有过 = false，调用方不必区分）
	 */
	public boolean releasePhysicalHold(long x, long y, long z, String owner) {
		final String nk = nodeKey(x, y, z);
		final Physical holder = physicalHolders.get(nk);
		if (holder == null || !holder.owner.equals(owner)) {
			return false;
		}
		physicalHolders.remove(nk);
		promotePhysical(nk, clock.getAsLong());
		return true;
	}

	/**
	 * **持有者改自己按的位**（notes/136 §3）：位置跟着它的新需要走，持有关系与窗口都保持不变。
	 *
	 * <p>为什么必须有这一条：位置由**持有者**决定（T1），所以"只把行视图/道岔行写过去"是没用的 ——
	 * {@code mmtrTurnoutPosition} 读的还是持有者那一位。持有者要换位（它自己的新计划要另一条腿），
	 * 就得改它自己的需求，而不是绕开它。</p>
	 *
	 * @return 真的改了（没持有、或本来就是这一位 = false）
	 */
	public boolean repointPhysicalHold(long x, long y, long z, String owner, int position) {
		final String nk = nodeKey(x, y, z);
		final Physical holder = physicalHolders.get(nk);
		if (holder == null || !holder.owner.equals(owner) || holder.position == position) {
			return false;
		}
		physicalHolders.put(nk, new Physical(owner, position, holder.untilMillis, holder.priorityMillis));
		return true;
	}


	/** Whether this node carries a physical turnout (false when the layer is unwired). */
	public boolean hasTurnout(long x, long y, long z) {
		return turnoutAt(x, y, z) != null;
	}

	/**
	 * The turnout position this (approach, leg) demands, or {@link Integer#MIN_VALUE} when the node is
	 * not a turnout / the combination is physically impossible. This is what the route layer asks to
	 * check "is the switch actually set the way my leg needs?".
	 */
	public int turnoutDemand(long x, long y, long z, String viaRailHex, int leg) {
		final MmtrTurnout turnout = turnoutAt(x, y, z);
		return turnout == null ? Integer.MIN_VALUE : turnout.positionForLeg(viaRailHex, leg);
	}

	/** Turnout wait queue snapshot (diagnostics / UI): {@code owner@position}, oldest first. */
	public ObjectArrayList<String> physicalQueueSnapshot(long x, long y, long z) {
		final ObjectArrayList<String> out = new ObjectArrayList<>();
		final ArrayDeque<PhysicalReq> q = physicalQueued.get(nodeKey(x, y, z));
		if (q != null) {
			for (final PhysicalReq r : q) {
				out.add(r.owner + "@" + r.position);
			}
		}
		return out;
	}

	private void expirePhysical(String nk, long now) {
		final Physical holder = physicalHolders.get(nk);
		if (holder != null && holder.untilMillis <= now) {
			/*
			 * **车还压在这处道岔的轨上，窗口到期也不放**（2026-09-16 现场修，就是"北部掉头处两个车顶头"那条）。
			 *
			 * <p>物理持有是有**窗口**的（{@code untilMillis}，申请时给的）。列车跨过岔口后会自己释放，
			 * 但"尾部还没出清"的那段时间里窗口可能先到期 ⇒ 位置一空出来，另一条进路的申请立刻把它拿走
			 * ⇒ **道岔在还压着它的那辆车脚下被扳走了**。现场后果有两条：那辆车停在了"禁行侧"的轨上，
			 * 它头上那盏灯从此按红显示（{@code signal why} 原话："撞在道岔 -176,-60,-253 的禁行侧"），
			 * 而另一辆车按着位置也走不了 ⇒ 两班车互相封死。
			 *
			 * <p>判据用"这辆车在这处节点的轨上还有没有足迹"（{@code HolderOccupancy}，由 Simulator 接
			 * 占用树回答）。续期而不是永久持有：车一开出去，下一次到期就正常释放并推进队列。</p>
			 */
			final long[] node = parseNodeKey(nk);
			/*
			 * 三态：{@code TRUE}=确知在岔轨上、{@code FALSE}=确知不在、{@code null}=查不出来。
			 * 这一处是"窗口到期要不要放位"，按接口注释的口径**查不出来按"在"处理**（宁可不放位），
			 * 所以用 {@code !Boolean.FALSE.equals(...)}，而不是直接拆箱。
			 *
			 * <p>2026-10-09 由用例 {@code MmtrPointAuthorityTests#anEqualPriorityNewcomerCannotStealAHeldPhysicalPosition}
			 * 逮到：这里原来是裸的 {@code holderOccupancy.ownerIsOnNodeRails(...)}，返回值是 {@code null} 时
			 * **自动拆箱直接 NPE**。而"查不出来"在生产里是常态之一 —— owner 是 {@code "v"+车辆id}，
			 * 车辆被移除/存档恢复出一个不在车辆表里的 owner，都会让它返回 null。权限层一旦在里面抛异常，
			 * 整条 tick 上的联锁判定就断了。</p>
			 */
			if (node != null && holderOccupancy != null && !Boolean.FALSE.equals(holderOccupancy.ownerIsOnNodeRails(node[0], node[1], node[2], holder.owner))) {
				physicalHolders.put(nk, new Physical(holder.owner, holder.position, now + PHYSICAL_HOLD_EXTENSION_MILLIS, holder.priorityMillis));
				final long last = physicalRetryLogMillis.getOrDefault(nk + "|hold", Long.MIN_VALUE);
				if (now - last >= PHYSICAL_RETRY_LOG_INTERVAL_MILLIS) {
					physicalRetryLogMillis.put(nk + "|hold", now);
					System.out.println("[MMTR-PT] 位置持有续期：节点 " + nk + " 仍是 " + holder.owner + " 位置 " + holder.position
						+ "（车还压在这处道岔的轨上，窗口到期也不放位）");
				}
				return;
			}
			physicalHolders.remove(nk);
			promotePhysical(nk, now);
		}
	}

	private void releasePhysicalIfHolder(long x, long y, long z, String owner, long now) {
		final String nk = nodeKey(x, y, z);
		final Physical holder = physicalHolders.get(nk);
		if (holder != null && holder.owner.equals(owner)) {
			physicalHolders.remove(nk);
			promotePhysical(nk, now);
		}
	}

	/**
	 * The turnout is free: the longest-waiting mutually exclusive demand takes the position, and gets
	 * its per-approach grant in the same step so the walker can elect immediately (it reads grants per
	 * approach, not this table). Expired queue entries are skipped; the head is never skipped over, so
	 * the queue stays FIFO.
	 */
	private void promotePhysical(String nk, long now) {
		final ArrayDeque<PhysicalReq> q = physicalQueued.get(nk);
		if (q == null) {
			return;
		}
		while (!q.isEmpty()) {
			q.removeIf(r -> r.untilMillis <= now);   // 窗口过期的排队项一律丢掉
			final PhysicalReq head = pickNextPhysical(q, now);
			if (head == null) {
				break;
			}
			// 净空闸对"事件驱动的推进"同样算数：持有者刚走，但岔区可能还被别人压着 —— 那时不许把位置
			// 判出去（否则就是把道岔从别人车下抽走）。挡着就留在队列里，等 retryPhysicalQueues 的下一轮。
			final long[] node = parseNodeKey(nk);
			if (node != null && positionChangeBlockedReason(node[0], node[1], node[2], head.position, head.owner) != null) {
				break;
			}
			q.remove(head);
			grantPhysical(nk, head);
			break;
		}
		if (q.isEmpty()) {
			physicalQueued.remove(nk);
		}
	}

	private void enqueuePhysical(String nk, String owner, String viaRailHex, int leg, int position, long untilMillis, long priorityMillis) {
		/*
		 * **已经按着这个位置的人不要排队**（2026-09-16 现场修：北端两班车相持的真正机制）。
		 *
		 * <p>现场读数：{@code holder=vX@0 … queue=vX@0} —— **持有者自己也在队列里**。来源是原子申请那条路
		 * （{@code queueSet}）在一组道岔里"整组等"时，把**已经在手里的那一处**也一起排进队。后果是
		 * 位置一空出来，{@link #promotePhysical} 的队首又是它自己 ⇒ 它原地再拿一次 ⇒ 另一班车永远轮不到
		 * （实测：两班车在同一处道岔上按着相反的两个位置来回相持，位置永远不换手）。</p>
		 */
		final Physical current = physicalHolders.get(nk);
		if (current != null && current.owner.equals(owner) && current.position == position) {
			return;
		}
		final ArrayDeque<PhysicalReq> q = physicalQueued.computeIfAbsent(nk, key -> new ArrayDeque<>());
		for (final PhysicalReq r : q) {
			if (r.owner.equals(owner)) {
				r.viaRailHex = viaRailHex;
				r.leg = leg;
				r.position = position;
				r.untilMillis = untilMillis;
				if (priorityMillis < r.priorityMillis) {
					// 计划时刻只会越刷新越准（任务换了就重来）：取更早的那个，别把优先权刷丢
					final PhysicalReq updated = new PhysicalReq(owner, viaRailHex, leg, position, untilMillis, priorityMillis);
					q.remove(r);
					q.addLast(updated);
				}
				return;
			}
		}
		final PhysicalReq created = new PhysicalReq(owner, viaRailHex, leg, position, untilMillis, priorityMillis);
		created.enqueuedAtMillis = clock.getAsLong();   // 与逐进向一样：等待时长按引擎时钟算，重复申请不刷新
		q.addLast(created);
	}

	private void dropPhysicalQueued(String nk, String owner) {
		final ArrayDeque<PhysicalReq> q = physicalQueued.get(nk);
		if (q != null) {
			q.removeIf(r -> r.owner.equals(owner));
			if (q.isEmpty()) {
				physicalQueued.remove(nk);
			}
		}
	}

	/** "位置队列卡住"诊断日志的节流（每个节点每 {@link #PHYSICAL_RETRY_LOG_INTERVAL_MILLIS} 最多一条）。 */
	private final Map<String, Long> physicalRetryLogMillis = new HashMap<>();
	private static final long PHYSICAL_RETRY_LOG_INTERVAL_MILLIS = 5_000;

	/** 申请/释放诊断日志的节流状态：键 = {@code 车@节点(+方向)}，值 = {上次打印的时刻, 上次打印的结果}。 */
	private static final Map<String, Object[]> DIAGNOSTIC_LOG_STATE = new HashMap<>();
	private static final long DIAGNOSTIC_LOG_INTERVAL_MILLIS = 10_000;
	/** 键空间很小（车 × 岔口），超过这个数说明键里有会变的东西（比如把结果串进键），清一次兜底。 */
	private static final int DIAGNOSTIC_LOG_MAX_KEYS = 2_048;

	/**
	 * 道岔申请/释放诊断日志的**节流闸**（2026-09-16 现场事故）。
	 *
	 * <h3>为什么必须节流</h3>
	 * <p>一处卡死的道岔会让车辆**每 tick** 重发同一个申请（"按计划补申请"那条自救路就是这么写的），
	 * 于是 {@code [MMTR-PT] req … -> QUEUED} 每秒刷 20 行、几十个字符一行的长串。这不只是难看：
	 * 这些行进的是服务端控制台，而控制台 I/O 与 tick 同一个线程 —— 实测刷屏时 {@code mmtr-command}
	 * 请求直接超时（8 s 不应答）、{@code server stop} 也发不进去，现场看起来像"引擎卡死"，
	 * 实际是它在写日志。诊断要留着（卡死时正是靠这几行定位的），但**同一个结论不需要每秒说 20 遍**。</p>
	 *
	 * <h3>规则</h3>
	 * <p>第一次一定打（新出现的申请要看得见）；结果变了马上打（QUEUED → GRANTED 这种状态跃迁不能等）；
	 * 其余每 {@link #DIAGNOSTIC_LOG_INTERVAL_MILLIS} 最多一条。节流是**按"车@节点"分别算**的，
	 * 所以一列车刷屏不会把别的车的话吞掉。</p>
	 *
	 * @param key     稳定的键（车 + 节点 + 方向），不要把结果串进键里
	 * @param outcome 本次结果的可比较文本（用来判"结论变了没有"）
	 */
	public static boolean shouldLogDiagnostic(String key, String outcome) {
		final long now = System.currentTimeMillis();
		synchronized (DIAGNOSTIC_LOG_STATE) {
			if (DIAGNOSTIC_LOG_STATE.size() > DIAGNOSTIC_LOG_MAX_KEYS) {
				DIAGNOSTIC_LOG_STATE.clear();
			}
			final Object[] previous = DIAGNOSTIC_LOG_STATE.get(key);
			if (previous != null && outcome.equals(previous[1]) && now - (Long) previous[0] < DIAGNOSTIC_LOG_INTERVAL_MILLIS) {
				return false;
			}
			DIAGNOSTIC_LOG_STATE.put(key, new Object[]{now, outcome});
			return true;
		}
	}

	/**
	 * **推进"位置队列"**（每 tick 由 {@link org.mtr.core.simulation.Simulator} 调用）。
	 *
	 * <p>为什么必须有这一句（2026-09-16 现场）：请求改位置被净空闸挡下时会进
	 * {@code physicalQueued}，而推进队列的 {@link #promotePhysical} 只在**持有者释放/窗口过期**这两个
	 * 事件里被调用 —— 于是出现"位置空着、车排在队首、却永远轮不到"：车停在站台上等了三分钟，
	 * {@code point why} 的读数是 {@code 物理位置=0 / 物理持有者=（没有）/ 等待队列=[v…@1]}，
	 * 只有人工扳一次道岔（{@code point set}）才把它救出来。净空是会自己清掉的（车走了、区间空了），
	 * 所以队列也必须**自己**再试一次，不能只等那两个事件。</p>
	 *
	 * <p>每次调用对"没有持有者"的队列重排一次队首，并按**同一条净空闸**再判一次：闸还挡着就继续排队
	 * （并节流打一行原因，方便操作者知道是谁/哪根轨压着岔区 —— 修前这条路径是**完全静默**的）。</p>
	 */
	public void retryPhysicalQueues(long now) {
		if (physicalQueued.isEmpty()) {
			return;
		}
		/*
		 * 注：曾经在这里加过一条"释放陈旧持有"（持有者不在岔轨上、又有人排队要别的位 ⇒ 放位），
		 * **实测副作用更大**：两班车各按着一个自己用不上的位置、互相等着对方时，这条规则会每 tick
		 * 放一次、双方立刻再申请一次，日志刷满（现场 23:06 实测每 tick 三行），位置在两者之间空转 ✗。
		 * 所以撤掉，只保留下面"队列推进"这一条（它才是必要的：位置空着而队列有人 ⇒ 判给队首）。
		 */
		for (final String nk : new ObjectArrayList<>(physicalQueued.keySet())) {
			final ArrayDeque<PhysicalReq> q = physicalQueued.get(nk);
			if (q == null) {
				continue;
			}
			q.removeIf(r -> r.untilMillis <= now);
			if (q.isEmpty()) {
				physicalQueued.remove(nk);
				continue;
			}
			if (physicalHolders.containsKey(nk)) {
				continue;   // 有持有者：等它释放或过期（那两条路会自己 promotePhysical）
			}
			final PhysicalReq head = pickNextPhysical(q, now);
			final long[] node = parseNodeKey(nk);
			if (head == null || node == null) {
				continue;
			}
			final String blocked = positionChangeBlockedReason(node[0], node[1], node[2], head.position, head.owner);
			if (blocked != null) {
				final long last = physicalRetryLogMillis.getOrDefault(nk, Long.MIN_VALUE);
				if (now - last >= PHYSICAL_RETRY_LOG_INTERVAL_MILLIS) {
					physicalRetryLogMillis.put(nk, now);
					System.out.println("[MMTR-PT] 位置队列等净空：节点 " + nk + " 车 " + head.owner + " 要位置 " + head.position + " —— " + blocked);
				}
				continue;
			}
			q.remove(head);
			grantPhysical(nk, head);
			final long lastGrantLog = physicalRetryLogMillis.getOrDefault(nk + "|grant", Long.MIN_VALUE);
			if (now - lastGrantLog >= PHYSICAL_RETRY_LOG_INTERVAL_MILLIS) {
				// 节流：原子组还在等别的点时，本处每 tick 都会被重新排队并判出——不节流会把日志刷满
				// （实测 20 行/秒，把真正该看的作业/信号行全埋掉）。
				physicalRetryLogMillis.put(nk + "|grant", now);
				System.out.println("[MMTR-PT] 位置队列推进：节点 " + nk + " → 位置 " + head.position + " 给了 " + head.owner);
			}
			if (q.isEmpty()) {
				physicalQueued.remove(nk);
			}
		}
	}

	/** 把一处位置判给队首，并**同一步**发出它那条逐进向授权（走行只读逐进向，不读位置表）。 */
	private void grantPhysical(String nk, PhysicalReq head) {
		physicalHolders.put(nk, new Physical(head.owner, head.position, head.untilMillis, head.priorityMillis));
		final String k = keyOfNode(nk, head.viaRailHex);
		final Holder existing = holders.get(k);
		if (existing == null || existing.owner.equals(head.owner)) {
			holders.put(k, new Holder(new Req(head.owner, head.leg, head.untilMillis)));
			byOwner.computeIfAbsent(head.owner, o -> new ObjectArrayList<>()).add(holders.get(k));
		}
	}

	/** {@code "x,y,z"} → {@code [x, y, z]}；解析不出来返回 {@code null}。 */
	private static long @Nullable [] parseNodeKey(String nk) {
		final String[] parts = nk.split(",");
		if (parts.length != 3) {
			return null;
		}
		try {
			return new long[]{Long.parseLong(parts[0].trim()), Long.parseLong(parts[1].trim()), Long.parseLong(parts[2].trim())};
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * 物理队列挑头：**先看计划时刻**（越小越早），一样早再看谁先排的；等太久的（{@link #MMTR_STARVATION_MILLIS}）
	 * 提到上一档按等待时长排 —— 与逐进向的 {@link #pickNext} **同一条口径**。
	 *
	 * <p>为什么物理队列也必须按计划时刻（notes/151）：六台车去同一个车站、计划到达 00:05 / 00:07 / …，
	 * 位置该给 00:05 那台。物理队列原来按到达先后（{@code pollFirst}），于是"谁先抢到谁先走"，
	 * 与时刻表无关 —— 现场就是几台车在咽喉里轮流按位置、轮流让位。</p>
	 */
	private @Nullable PhysicalReq pickNextPhysical(@Nullable ArrayDeque<PhysicalReq> q, long now) {
		if (q == null || q.isEmpty()) {
			return null;
		}
		PhysicalReq best = null;
		boolean bestStarved = false;
		for (final PhysicalReq r : q) {
			final boolean starved = now - r.enqueuedAtMillis >= MMTR_STARVATION_MILLIS;
			if (best == null || starved && !bestStarved || starved == bestStarved
				&& better(r.owner, r.priorityMillis, r.enqueuedAtMillis, best.owner, best.priorityMillis, best.enqueuedAtMillis, starved)) {
				best = r;
				bestStarved = starved;
			}
		}
		return best;
	}

	private void enqueue(String k, Req in) {
		in.enqueuedAtMillis = clock.getAsLong();   // T1b: 防饿死按这个算等待时长；重复申请**不**刷新它
		queued.computeIfAbsent(k, x -> new ArrayDeque<>()).addLast(in);
		queuedHead.put(k, queued.get(k).peekFirst());
	}

	private void dropOwnerRequests(String k, String owner) {
		final ArrayDeque<Req> q = queued.get(k);
		if (q != null) {
			q.removeIf(r -> r.owner.equals(owner));
		}
		queuedHead.put(k, q == null || q.isEmpty() ? null : q.peekFirst());
	}

	/** Expire the holder's window; the queue then advances. Returns whether the point is free. */
	private void expireLocked(String k, long now) {
		final Holder h = holders.get(k);
		if (h != null && h.untilMillis <= now) {
			holders.remove(k);
			promote(k, now);
		}
	}

	private void promote(String k, long now) {
		if (locks.contains(k)) {
			return; // operator park holds the point; queue waits for unlock
		}
		final ArrayDeque<Req> q = queued.get(k);
		if (q != null) {
			q.removeIf(r -> r.untilMillis <= now);   // 窗口过期的排队项一律丢掉
			final Req best = pickNext(q, now);
			if (best != null) {
				q.remove(best);
				holders.put(k, new Holder(best));
			}
		}
		queuedHead.put(k, q == null || q.isEmpty() ? null : q.peekFirst());
		if (q != null && q.isEmpty()) {
			queued.remove(k);
		}
	}

	/**
	 * T1b 裁决链：**计划时刻优先 > 到达序 > 防饿死**。
	 *
	 * <p>没有计划时刻（{@link Long#MAX_VALUE}）且没人等待超过 {@link #MMTR_STARVATION_MILLIS} 时，
	 * 比较键退化成"入队时刻" —— 也就是**与修前完全一样的 FIFO**。这是基线没有被这一片震动的原因。</p>
	 *
	 * <p>等待超时的排队项被提到上一档：它不再排在任何新来者后面，只在同为"饿着"的项之间按等待时长
	 * 排序（优先级在这一档里不再参与，否则一个高优先级的老等者会把它后面的饿者一直压住）。
	 * 没有这一档，咽喉繁忙时先到的那一列车可能永远轮不到 —— 而用户裁定"玩家只有司机、没有调度员"，
	 * 所以**不能**靠人来解这个套。</p>
	 */
	private @Nullable Req pickNext(@Nullable ArrayDeque<Req> q, long now) {
		if (q == null || q.isEmpty()) {
			return null;
		}
		Req best = null;
		boolean bestStarved = false;
		for (final Req r : q) {
			final boolean starved = now - r.enqueuedAtMillis >= MMTR_STARVATION_MILLIS;
			if (best == null || starved && !bestStarved || starved == bestStarved
				&& better(r.owner, r.priorityMillis, r.enqueuedAtMillis, best.owner, best.priorityMillis, best.enqueuedAtMillis, starved)) {
				best = r;
				bestStarved = starved;
			}
		}
		return best;
	}

	/**
	 * 队列挑头的比较键（**与 {@link #outranks} 同一条链**，只有末档不同：挑头是"在一堆等待者里选一个"，
	 * 入队序才是它该有的末档；让位是"两边互判"，末档必须是 owner id 才保证不对称）。
	 *
	 * <p>服务等级/车号在这一层同样说了算（用户 2026-09-27）：咽喉里排着 00101 与 00103 时，
	 * **00101 先拿**，不因为 00103 早到半秒就翻过来。等太久的（防饿死档，{@link #MMTR_STARVATION_MILLIS}）
	 * 仍按等待时长排 —— 优先级在这一档里不参与，否则一个高等级的老等者会把它后面的饿者一直压住。</p>
	 */
	private boolean better(String ownerA, long priorityA, long enqueuedA, String ownerB, long priorityB, long enqueuedB, boolean starved) {
		if (starved) {
			return enqueuedA < enqueuedB;
		}
		final MmtrTrainPriority a = priorityOf(ownerA);
		final MmtrTrainPriority b = priorityOf(ownerB);
		if (a != null || b != null) {
			if (a == null) {
				return false;
			}
			if (b == null) {
				return true;
			}
			if (a.outranks(b)) {
				return true;
			}
			if (b.outranks(a)) {
				return false;
			}
		}
		if (priorityA != priorityB) {
			return priorityA < priorityB;
		}
		return enqueuedA < enqueuedB;
	}
}
