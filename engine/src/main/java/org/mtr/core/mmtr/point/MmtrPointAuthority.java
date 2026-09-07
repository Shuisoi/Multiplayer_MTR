
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

	public enum Result { GRANTED, QUEUED }

	private final LongSupplier clock;
	private final Map<String, Holder> holders = new HashMap<>();
	private final Map<String, Req> queuedHead = new HashMap<>();
	private final Map<String, ArrayDeque<Req>> queued = new HashMap<>();
	private final java.util.Set<String> locks = new java.util.HashSet<>();
	/** Turnout requests, keyed for releaseAll / diagnostics. */
	public final Map<String, ObjectArrayList<Req>> byOwner = new HashMap<>();

	public MmtrPointAuthority(LongSupplier clock) {
		this.clock = clock;
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
	public Result request(long x, long y, long z, String viaRailHex, String owner, int leg, long untilMillis) {
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
	}

	public boolean isGrantedTo(long x, long y, long z, String viaRailHex, String owner) {
		final String k = key(x, y, z, viaRailHex);
		expireLocked(k, clock.getAsLong());
		final Holder h = holders.get(k);
		return h != null && h.owner.equals(owner);
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
