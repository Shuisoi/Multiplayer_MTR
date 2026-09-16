
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
	 * <p>用途只有一个：物理持有窗口到期时决定"能不能放位"。放位等于允许别人把道岔扳到别的位，
	 * 而车还压在这处道岔的轨上时那样做就是把道岔从它脚下抽走（见 {@code expirePhysical}）。</p>
	 */
	public interface HolderOccupancy {
		boolean ownerIsOnNodeRails(long x, long y, long z, String owner);
	}

	/** 挂上"持有者还在不在岔轨上"的查询；不挂 = 到期就放位（老语义，测试夹具就是这样）。 */
	public MmtrPointAuthority withHolderOccupancy(@Nullable HolderOccupancy occupancy) {
		this.holderOccupancy = occupancy;
		return this;
	}

	private @Nullable HolderOccupancy holderOccupancy;

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
		if (holder == null || holder.owner.equals(owner)) {
			/*
			 * 没人定这个位置，或者我本来就定着它 → 位置跟着我走。
			 *
			 * <p>但**改位置**要先过净空闸（{@link PositionChangeGuard}）：另一列车压在岔区上时不许改
			 * —— 那正是"把道岔从车下抽走"。请求方自己压在岔上不算（它按着自己的位，本来就该能改自己
			 * 的需要，否则换端/折返会把自己锁死）。被挡下来时与"互斥"同一处置：**收回刚发出的逐进向
			 * 授权**、改为在道岔上排队，绝不留下"半个持有"（T1b 的不变量）。</p>
			 */
			if ((holder == null || holder.position != demand)
				&& positionChangeBlockedReason(x, y, z, demand, owner) != null) {
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
		 * **早班车可以收回晚班车按着的位置**（notes/151，用户裁定的通行优先权）。
		 *
		 * 六台车去同一个车站、计划到达 00:05 / 00:07 / … 时，位置该给 00:05 那台：
		 *   - 只有**计划更早**（priority 更小）才谈得上收回 —— 否则就是位置来回翻（ping-pong 的来源）；
		 *   - 而且要过**净空闸**：晚班车压在岔区里就不许从它脚下改位（那是把道岔抽走）；
		 *   - 收回之后晚班车排队等（它的进向行还在，位置不在它手里），等它自己再申请时会按优先权排队。
		 */
		if (priorityMillis < holder.priorityMillis && positionChangeBlockedReason(x, y, z, demand, owner) == null) {
			System.out.println("[MMTR-PT] 优先权：把道岔 " + nk + " 的位置从 " + holder.owner + "（计划 " + holder.priorityMillis
				+ "）交给更早的 " + owner + "（计划 " + priorityMillis + "）");
			physicalHolders.put(nk, new Physical(owner, demand, untilMillis, priorityMillis));
			dropPhysicalQueued(nk, owner);
			return Result.GRANTED;
		}
		// 互斥：收回刚发出的逐进向授权，改为在**道岔上**排队。
		holders.remove(k);
		dropOwnerRequests(k, owner);
		promote(k, now);
		enqueuePhysical(nk, owner, viaRailHex, leg, demand, untilMillis, priorityMillis);
		return Result.QUEUED;
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
	 * 几台车谁也走不了。道岔只有一个位置，解环要让**该让的那一方**退：谁的计划时刻更早谁先走
	 * （与 {@code priorityMillis} 的通行优先权同一口径），而不是"谁等得久谁退"。</p>
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

	/** {@code other} 是不是比 {@code mine} 更该先走：计划时刻更早；同优先权按持有者 id 定序（判断不对称）。 */
	private static boolean outranks(long otherPriority, String otherOwner, long minePriority, String mineOwner) {
		if (otherPriority != minePriority) {
			return otherPriority < minePriority;
		}
		return otherOwner.compareTo(mineOwner) < 0;
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
			if (node != null && holderOccupancy != null && holderOccupancy.ownerIsOnNodeRails(node[0], node[1], node[2], holder.owner)) {
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
			if (best == null
				|| starved && !bestStarved
				|| starved == bestStarved && (starved
					? r.enqueuedAtMillis < best.enqueuedAtMillis
					: r.priorityMillis != best.priorityMillis
						? r.priorityMillis < best.priorityMillis
						: r.enqueuedAtMillis < best.enqueuedAtMillis)) {
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
			if (best == null || starved && !bestStarved || starved == bestStarved && better(r, best, starved)) {
				best = r;
				bestStarved = starved;
			}
		}
		return best;
	}

	private static boolean better(Req a, Req b, boolean starved) {
		if (starved) {
			return a.enqueuedAtMillis < b.enqueuedAtMillis;
		}
		return a.priorityMillis != b.priorityMillis
			? a.priorityMillis < b.priorityMillis
			: a.enqueuedAtMillis < b.enqueuedAtMillis;
	}
}
