package org.mtr.core.mmtr.route;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;

/**
 * MMTR 进路 (route) — the missing first-class object of the low-speed interlocking
 * (design {@code 行车控制-低速区信号道岔任务集成-设计.md} §1: "无'进路'状态对象，信号读不到").
 *
 * <p>A route is what a task actually means in signalling terms: "this train is going from here to
 * that rail, along these rails, through these turnouts, on these legs". The planner
 * ({@link org.mtr.core.mmtr.MmtrRunPlanner}) has always produced that information as loose plan
 * fields; this object gives it a stable identity so the other layers can read it:</p>
 *
 * <ul>
 *   <li><strong>signal display (A2)</strong> — a signal protecting rail R shows a proceed aspect
 *       only when a route over R is <em>set</em> for the train approaching it;</li>
 *   <li><strong>interlocking (S5/P4)</strong> — "set" means every turnout the route needs is held
 *       by this train's owner; an operator park or another holder drops it back to PENDING, so the
 *       protecting signal returns to danger and the train waits outside it;</li>
 *   <li><strong>ops view (A4)</strong> — the web console shows the live route per train instead of
 *       only its target rail.</li>
 * </ul>
 *
 * <p>The object is a pure descriptor plus a derived state. It never requests, grants or moves
 * anything: {@link MmtrRouteRegistry#refresh(long, org.mtr.core.mmtr.point.MmtrPointAuthority)}
 * recomputes {@link #isEstablished()} from the authoritative turnout state every tick, exactly the
 * way {@code MmtrPointAuthority} windows are validated lazily against the clock.</p>
 */
public final class MmtrRoute {

	/** What kind of movement this route authorises. */
	public enum Kind {
		/** 列车进路: a main-signal movement (passenger/freight run to a platform or siding). */
		MAIN,
		/**
		 * 调车进路: a shunt movement under a subsidiary aspect (C3a/C10). It may legitimately
		 * cross occupied rails, so the signal layer must NOT treat it as a main route.
		 */
		SHUNT
	}

	private final long vehicleId;
	/** Turnout-authority owner key of the requesting train ("v&lt;id&gt;"). */
	private final String owner;
	private final Kind kind;
	/** Every rail the movement runs over, in travel order (the rail the train stands on first). */
	private final ObjectArrayList<String> railHexes = new ObjectArrayList<>();
	/** Turnout demands: {nodeX, nodeY, nodeZ, viaHex, leg}, in the order the train meets them. */
	private final ObjectArrayList<String[]> forks = new ObjectArrayList<>();
	private final String targetRailHex;
	private final long requestedMillis;
	private boolean established;
	private String stateReason = "not refreshed";

	public MmtrRoute(long vehicleId, String owner, Kind kind, @Nullable Iterable<String> railHexes, @Nullable ObjectArrayList<String[]> forks, @Nullable String targetRailHex, long requestedMillis) {
		this.vehicleId = vehicleId;
		this.owner = owner == null ? "" : owner;
		this.kind = kind == null ? Kind.MAIN : kind;
		if (railHexes != null) {
			for (final String railHex : railHexes) {
				if (railHex != null && !railHex.isEmpty()) {
					this.railHexes.add(railHex);
				}
			}
		}
		if (forks != null) {
			for (final String[] fork : forks) {
				if (fork != null && fork.length >= 5) {
					this.forks.add(fork.clone());
				}
			}
		}
		this.targetRailHex = targetRailHex == null ? "" : targetRailHex;
		this.requestedMillis = requestedMillis;
	}

	public long getVehicleId() {
		return vehicleId;
	}

	public String getOwner() {
		return owner;
	}

	public Kind getKind() {
		return kind;
	}

	/** Every rail of the movement, travel order, the train's current rail first. */
	public ObjectArrayList<String> getRailHexes() {
		return railHexes;
	}

	/** Turnout demands {x, y, z, viaHex, leg}, in the order the train meets them. */
	public ObjectArrayList<String[]> getForks() {
		return forks;
	}

	public String getTargetRailHex() {
		return targetRailHex;
	}

	public long getRequestedMillis() {
		return requestedMillis;
	}

	/** The rail the movement starts on (the one the train stands on when the route is set). */
	public @Nullable String getEntryRailHex() {
		return railHexes.isEmpty() ? null : railHexes.get(0);
	}

	/** Whether the route runs over {@code railHex} (either direction). */
	public boolean coversRail(@Nullable String railHex) {
		return railHex != null && !railHex.isEmpty() && railHexes.contains(railHex);
	}

	/**
	 * Whether the interlocking considers the route SET: every turnout it needs is held by this
	 * train's owner. A route with no turnouts (straight track) is set as soon as it is requested.
	 */
	public boolean isEstablished() {
		return established;
	}

	/** Human-readable reason for the current state (names the blocking point while PENDING). */
	public String getStateReason() {
		return stateReason;
	}

	/**
	 * Whether {@code other} describes the same movement (kind, rails in order, turnout demands in
	 * order, target). A mission whose self-arm retries every tick while it waits for the
	 * interlocking must keep ONE route identity - re-publishing a fresh object each tick would
	 * churn the object the signal layer and the ops console hold.
	 */
	public boolean sameMovement(MmtrRoute other) {
		if (kind != other.kind || !targetRailHex.equals(other.targetRailHex) || !railHexes.equals(other.railHexes) || forks.size() != other.forks.size()) {
			return false;
		}
		for (int i = 0; i < forks.size(); i++) {
			final String[] a = forks.get(i);
			final String[] b = other.forks.get(i);
			if (a.length != b.length) {
				return false;
			}
			for (int j = 0; j < a.length; j++) {
				if (!a[j].equals(b[j])) {
					return false;
				}
			}
		}
		return true;
	}

	/** @return whether the state changed (lets callers log transitions, not every tick). */
	boolean applyState(boolean establishedNow, String reasonNow) {
		final boolean changed = established != establishedNow;
		established = establishedNow;
		stateReason = reasonNow;
		return changed;
	}

	@Override
	public String toString() {
		return kind + " route v" + vehicleId + " " + (railHexes.isEmpty() ? "?" : railHexes.get(0)) + " -> " + targetRailHex
			+ " (" + railHexes.size() + " rail(s), " + forks.size() + " fork(s), " + (established ? "SET" : "PENDING: " + stateReason) + ")";
	}
}
