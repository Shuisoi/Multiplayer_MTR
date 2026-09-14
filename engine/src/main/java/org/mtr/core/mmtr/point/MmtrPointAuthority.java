
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

		Req(String owner, int leg, long untilMillis) {
			this.owner = owner;
			this.leg = leg;
			this.untilMillis = untilMillis;
		}
	}

	private static final class Holder extends Req {
		Holder(Req req) {
			super(req.owner, req.leg, req.untilMillis);
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

	/** {@link #physicalPosition} answer when nobody currently defines this turnout's position. */
	public static final int NO_PHYSICAL_HOLDER = Integer.MIN_VALUE;

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

	public MmtrPointAuthority(LongSupplier clock) {
		this.clock = clock;
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
			// 没人定这个位置，或者我本来就定着它 → 位置跟着我走。
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
		ArrayDeque<Req> q = queued.get(k);
		while (q != null && !q.isEmpty()) {
			final Req head = q.peekFirst();
			if (head.untilMillis <= now) {
				q.pollFirst(); // stale queued request expired
				continue;
			}
			holders.put(k, new Holder(head));
			q.pollFirst();
			break;
		}
		queuedHead.put(k, q == null || q.isEmpty() ? null : q.peekFirst());
		if (q != null && q.isEmpty()) {
			queued.remove(k);
		}
	}
}
