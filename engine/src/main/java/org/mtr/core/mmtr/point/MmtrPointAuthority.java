
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

	/** {@link #physicalPosition} answer when nobody currently defines this turnout's position. */
	public static final int NO_PHYSICAL_HOLDER = Integer.MIN_VALUE;

	/** T1b 防饿死：排队等待超过这个时长就提到上一档（不再排在任何新来者后面）。 */
	public static final long MMTR_STARVATION_MILLIS = 5L * 60 * 1000;

	/** T1: one turnout, one position - who has currently pinned it where. */
	private static final class Physical {
		final String owner;
		final int position;
		long untilMillis;

		Physical(String owner, int position, long untilMillis) {
			this.owner = owner;
			this.position = position;
			this.untilMillis = untilMillis;
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

		PhysicalReq(String owner, String viaRailHex, int leg, int position, long untilMillis) {
			this.owner = owner;
			this.viaRailHex = viaRailHex;
			this.leg = leg;
			this.position = position;
			this.untilMillis = untilMillis;
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
		final MmtrTurnout turnout = turnoutAt(x, y, z);
		if (turnout == null) {
			return requestPerApproach(x, y, z, viaRailHex, owner, leg, untilMillis);
		}
		// 一处道岔只有两个位置：先把"某进向的第几条腿"翻译成**位置需求**。
		final int demand = turnout.positionForLeg(viaRailHex, leg);
		if (demand == Integer.MIN_VALUE) {
			return Result.REJECTED;
		}
		final Result perApproach = requestPerApproach(x, y, z, viaRailHex, owner, leg, untilMillis);
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
				enqueuePhysical(nk, owner, viaRailHex, leg, demand, untilMillis);
				return Result.QUEUED;
			}
			physicalHolders.put(nk, new Physical(owner, demand, untilMillis));
			dropPhysicalQueued(nk, owner);
			return Result.GRANTED;
		}
		if (holder.position == demand) {
			// 位置相容：两列车要的是**同一位**，道岔不是它们之间的争用点（它们之间的冲突是
			// 共用轨段/对向，那由闭塞与 T1b 的敌对进路表负责，不归道岔这一层）。
			return Result.GRANTED;
		}
		// 互斥：收回刚发出的逐进向授权，改为在**道岔上**排队。
		holders.remove(k);
		dropOwnerRequests(k, owner);
		promote(k, now);
		enqueuePhysical(nk, owner, viaRailHex, leg, demand, untilMillis);
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
		}
		/*
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
					return Result.REJECTED;   // 整组里有物理上不存在的组合 → 整组都不申请
				}
				if (!physicallyGrantableTo(nodeKey(x, y, z), demand, owner, now)) {
					releaseSet(effective, owner);
					queueSet(effective, owner, untilMillis, priorityMillis);
					return Result.QUEUED;
				}
			}
			if (!perApproachGrantableTo(key(x, y, z, via), owner, now)) {
				releaseSet(effective, owner);
				queueSet(effective, owner, untilMillis, priorityMillis);
				return Result.QUEUED;
			}
		}
		for (final String[] op : effective) {
			if (request(Long.parseLong(op[0]), Long.parseLong(op[1]), Long.parseLong(op[2]), op[3], owner,
					Integer.parseInt(op[4]), untilMillis) != Result.GRANTED) {
				releaseSet(effective, owner);
				queueSet(effective, owner, untilMillis, priorityMillis);
				return Result.QUEUED;
			}
		}
		return Result.GRANTED;
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
					enqueuePhysical(nodeKey(x, y, z), owner, via, leg, demand, untilMillis);
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
		final String k = key(x, y, z, viaRailHex);
		final long now = clock.getAsLong();
		expireLocked(k, now);
		if (locks.contains(k)) {
			final Req existing = findQueued(k, owner);
			if (existing != null) {
				existing.leg = leg;
				existing.untilMillis = untilMillis;
				return Result.QUEUED;
			}
			enqueue(k, new Req(owner, leg, untilMillis));
			return Result.QUEUED;
		}
		final Holder h = holders.get(k);
		if (h != null) {
			if (h.owner.equals(owner)) {
				h.leg = leg;
				h.untilMillis = untilMillis;
				return Result.GRANTED;
			}
			final Req existing = findQueued(k, owner);
			if (existing != null) {
				existing.leg = leg;
				existing.untilMillis = untilMillis;
				return Result.QUEUED;
			}
			enqueue(k, new Req(owner, leg, untilMillis));
			return Result.QUEUED;
		}
		holders.put(k, new Holder(new Req(owner, leg, untilMillis)));
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
		physicalHolders.put(nk, new Physical(owner, position, holder.untilMillis));
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
			final PhysicalReq head = q.peekFirst();
			if (head.untilMillis <= now) {
				q.pollFirst();
				continue;
			}
			q.pollFirst();
			physicalHolders.put(nk, new Physical(head.owner, head.position, head.untilMillis));
			final String k = keyOfNode(nk, head.viaRailHex);
			final Holder existing = holders.get(k);
			if (existing == null || existing.owner.equals(head.owner)) {
				holders.put(k, new Holder(new Req(head.owner, head.leg, head.untilMillis)));
				byOwner.computeIfAbsent(head.owner, o -> new ObjectArrayList<>()).add(holders.get(k));
			}
			break;
		}
		if (q.isEmpty()) {
			physicalQueued.remove(nk);
		}
	}

	private void enqueuePhysical(String nk, String owner, String viaRailHex, int leg, int position, long untilMillis) {
		final ArrayDeque<PhysicalReq> q = physicalQueued.computeIfAbsent(nk, key -> new ArrayDeque<>());
		for (final PhysicalReq r : q) {
			if (r.owner.equals(owner)) {
				r.viaRailHex = viaRailHex;
				r.leg = leg;
				r.position = position;
				r.untilMillis = untilMillis;
				return;
			}
		}
		q.addLast(new PhysicalReq(owner, viaRailHex, leg, position, untilMillis));
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
