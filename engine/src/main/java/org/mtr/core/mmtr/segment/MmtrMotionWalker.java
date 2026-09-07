package org.mtr.core.mmtr.segment;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Data;
import org.mtr.core.data.Position;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Rail;
import org.mtr.core.path.SidingPathFinder;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;

/**
 * Resumable (segment + offset) walker — the per-tick engine of the Motion Core driver. Unlike the
 * one-shot {@link MmtrLiveRouter#integrate}, this holds a persistent position on the rail graph
 * (current rail + offset from its entry node) and can be advanced by a small per-tick distance,
 * crossing each node as it arrives by electing the next rail from operator/task (never auto). A
 * free-driven train / headless driver calls {@link #advance(double)} every tick with its travelled
 * distance; the walker updates the (segment, offset) state in place. This is what lets a running
 * consist be driven by Motion Core instead of a pre-baked MTR path.
 */
public final class MmtrMotionWalker {

	public final Data data;
	private final BranchStore branches;
	private org.mtr.core.mmtr.point.MmtrPointAuthority pointAuthority;
	private String pointAuthorityOwner;
	private @Nullable String targetRailHex;

	private Rail rail;
	private Position enteredFrom;
	private Position ahead;
	private double offsetM;
	/** Total distance actually consumed since this walker started, m (the head's cumulative progress). */
	private double distanceM;

	private boolean haltedAtAuthority;
	private boolean endOfLine;
	private boolean atTarget;
	/** Authority-question forks (>=2 continuations) this walker has actually crossed since the last
	 * drain, as point keys x,y,z|viaHex - lets the owner stop refreshing crossed holds. */
	private final ObjectArrayList<String> crossedPointKeys = new ObjectArrayList<>();

	/** Ordered engine-runnable legs (PathData) over the rails the walker has traversed/boarded. */
	private final ObjectArrayList<PathData> legs = new ObjectArrayList<>();

	private MmtrMotionWalker(Data data, Rail startRail, Position startAt, double initialOffsetM, BranchStore branches, @Nullable String targetRailHex) {
		this.data = data;
		this.branches = branches;
		this.targetRailHex = targetRailHex;
		this.rail = startRail;
		this.enteredFrom = startAt;
		this.ahead = otherEnd(startAt, startRail);
		this.offsetM = 0;
		this.atTarget = startRail.getHexId().equals(targetRailHex);
		if (!this.atTarget && this.ahead != null) {
			this.legs.add(new PathData(startRail, 0L, 0L, 0, startAt, this.ahead));
		}
		// Parked starts: the consist's head may stand mid-rail inside a yard, not at the entry node.
		// Offset is measured from the entry node toward {@code ahead}; it must not pass the far node
		// (a parked body ahead of the node would have no elected continuation).
		final double railLength = startRail.railMath.getLength();
		this.offsetM = Math.max(0, Math.min(initialOffsetM, railLength));
		this.distanceM = this.offsetM;
	}

	public static MmtrMotionWalker start(Data data, Rail startRail, Position startAt, BranchStore branches, @Nullable String targetRailHex) {
		return new MmtrMotionWalker(data, startRail, startAt, 0, branches, targetRailHex);
	}

	/**
	 * Starts a walker on {@code startRail} with its head already {@code initialOffsetM} metres inside
	 * the rail (measured from {@code startAt} toward the far node) — the yard-parked position. The
	 * initial offset must not reach the far node; a fresh {@link #advance} then runs the rest of the
	 * rail and crosses its far node by authority like any other node.
	 */
	public static MmtrMotionWalker startAtOffset(Data data, Rail startRail, Position startAt, double initialOffsetM, BranchStore branches, @Nullable String targetRailHex) {
		return new MmtrMotionWalker(data, startRail, startAt, initialOffsetM, branches, targetRailHex);
	}

	public String railHex() {
		return rail.getHexId();
	}

	public double offsetM() {
		return offsetM;
	}

	/** Total distance consumed since this walker started (m) — the head's cumulative run progress. */
	public double distanceM() {
		return distanceM;
	}

	/** Number of legs (boarded rails) recorded so far; grows as nodes are crossed. */
	public int legCount() {
		return legs.size();
	}

	/** Node the train is currently moving toward. */
	public Position aheadNode() {
		return ahead;
	}

	/** The node the train is moving away from (its entry point on the current rail). */
	public Position enteredFromPosition() {
		return enteredFrom;
	}

	/** Length of the current rail, m. */
	public double currentRailLengthM() {
		return rail.railMath.getLength();
	}

	/**
	 * The ordered, engine-runnable legs (PathData with cumulative distances) covering the route the
	 * Motion Core walker has traversed so far. This is what a real Vehicle can be handed instead of a
	 * pre-baked whole-journey path — the route was decided segment by segment by Motion Core.
	 */
	public ObjectArrayList<PathData> buildLegs() {
		final ObjectArrayList<PathData> out = new ObjectArrayList<>(legs);
		SidingPathFinder.generatePathDataDistances(out, 0);
		return out;
	}

	public boolean haltedAtAuthority() {
		return haltedAtAuthority;
	}

	/** Forks crossed since the last drain, as x,y,z|viaHex point keys (consumed). */
	public ObjectArrayList<String> drainCrossedPointKeys() {
		final ObjectArrayList<String> out = new ObjectArrayList<>(crossedPointKeys);
		crossedPointKeys.clear();
		return out;
	}

	public boolean endOfLine() {
		return endOfLine;
	}

	public boolean atTarget() {
		return atTarget;
	}

	/**
	 * P3: wire this walker into the turnout authority under {@code owner} (mission/vehicle id).
	 * While wired, an unset fork with an authority grant for this owner is crossed at the granted
	 * ordered-leg index, and each point actually crossed releases the owner's hold so the next
	 * queued auto request can take it. Pass null/null to unwire (plain operator free-driving).
	 */
	public void setPointAuthority(org.mtr.core.mmtr.point.MmtrPointAuthority authority, String owner) {
		pointAuthority = authority;
		pointAuthorityOwner = owner;
	}

	/**
	 * MMTR (L3, slice 8): live retargeting of a RUNNING walker — the task/ops layer can redirect the
	 * vehicle at its next fork: {@link MmtrNodeRouter} gives the task target priority over a stale
	 * operator setting and overrides \"unset = wait\", and the walker comes to rest once it boards the
	 * target rail (offset 0 of that rail). Pass {@code null} (or a different rail) to clear/resume
	 * free running; retargeting away from a boarded target resumes the run.
	 */
	public void setTargetRailHex(@Nullable String targetRailHex) {
		this.targetRailHex = targetRailHex;
		if (targetRailHex == null || !rail.getHexId().equals(targetRailHex)) {
			atTarget = false; // a retarget away from the current rail resumes the run
		}
	}

	/**
	 * Advance up to {@code deltaM} metres from the current position, crossing nodes by authority as
	 * they are reached. Any part of the distance that cannot be covered (unset fork, end of line)
	 * is left unconsumed and the walker stops there.
	 */
	public void advance(double deltaM) {
		// A halt at an unset fork is a "waiting for the operator/任务 to decide", not terminal: a fresh
		// advance() re-attempts the node (自由开). End-of-line / target stay terminal.
		haltedAtAuthority = false;
		double remaining = Math.max(0, deltaM);
		while (remaining > 0 && !haltedAtAuthority && !endOfLine && !atTarget) {
			final double len = rail.railMath.getLength();
			final double toNode = len - offsetM;
			if (remaining < toNode) {
				offsetM += remaining;
				distanceM += remaining;
				return;
			}
			remaining -= toNode;
			offsetM = len; // reached the ahead node
			distanceM += toNode;

			final Object2ObjectOpenHashMap<Position, Rail> neighbors = data.positionsToRail.get(ahead);
			if (neighbors == null) {
				endOfLine = true;
				return;
			}
			final ObjectArrayList<Rail> forwardRails = new ObjectArrayList<>();
			final ObjectArrayList<Position> forwardEnds = new ObjectArrayList<>();
			for (final Object2ObjectOpenHashMap.Entry<Position, Rail> e : neighbors.object2ObjectEntrySet()) {
				if (e.getValue() != rail) {
					forwardRails.add(e.getValue());
					forwardEnds.add(e.getKey());
				}
			}

			final Rail next;
			if (forwardRails.isEmpty()) {
				endOfLine = true;
				return;
			} else if (forwardRails.size() == 1) {
				next = forwardRails.get(0);
			} else {
				next = electAtFork(forwardRails, forwardEnds);
				if (next == null) {
					haltedAtAuthority = true;
					return;
				}
				// The train has crossed the fork node onto the elected continuation: its authority
				// hold (if any) is consumed and the queue advances (over-release is a no-op).
				crossedPointKeys.add(ahead.getX() + "," + ahead.getY() + "," + ahead.getZ() + "|" + rail.getHexId());
				if (pointAuthority != null && pointAuthorityOwner != null) {
					pointAuthority.passed(ahead.getX(), ahead.getY(), ahead.getZ(), rail.getHexId(), pointAuthorityOwner);
				}
			}

			rail = next;
			enteredFrom = ahead;
			ahead = otherEnd(ahead, next);
			offsetM = 0;
			if (ahead != null) {
				legs.add(new PathData(rail, 0L, 0L, 0, enteredFrom, ahead));
			}
			if (rail.getHexId().equals(targetRailHex)) {
				atTarget = true;
				return;
			}
		}
	}

	private @Nullable Rail electAtFork(ObjectArrayList<Rail> forwardRails, ObjectArrayList<Position> forwardEnds) {
		// P1/P3: continuations are ordered deterministically per approach direction
		// (straight > left > right > other, cosine inside a kind - MmtrPoint), not by raw cosine
		// over map iteration. The operator branch (persisted 0/1 - or any leg index for multi-leg
		// junctions) and the task target are resolved against that ordering, so T junctions pick
		// left=0/right=1, crossings keep the straight as leg 0 and ordering never flips.
		final Object2ObjectOpenHashMap<Position, Rail> neighbors = data.positionsToRail.get(ahead);
		final ObjectArrayList<org.mtr.core.mmtr.point.MmtrPoint.MmtrPointLeg> legs = neighbors == null ? new ObjectArrayList<>() : org.mtr.core.mmtr.point.MmtrPoint.computeOrderedLegs(ahead, enteredFrom, rail, neighbors);
		if (legs.isEmpty()) {
			return null;
		}
		Rail chosen = null;
		final long px = ahead.getX();
		final long py = ahead.getY();
		final long pz = ahead.getZ();
		final String viaHex = rail.getHexId();
		// Decision order (design R2): manual operator > explicit auto grant > legacy task target >
		// single continuation; nothing auto-elects a two+-leg fork without one of the first three.
		if (branches.contains(px, py, pz, viaHex)) {
			// Manual operator (point-op / legacy preset): highest priority, never auto.
			final int operator = branches.get(px, py, pz, viaHex);
			if (operator >= 0 && operator < legs.size()) {
				chosen = findRailByHex(forwardRails, legs.get(operator).railHex);
			}
		}
		if (chosen == null && pointAuthority != null && pointAuthorityOwner != null) {
			// Explicit auto grant for THIS owner at its ordered-leg index (authority expiry-checked).
			if (pointAuthority.isGrantedTo(px, py, pz, viaHex, pointAuthorityOwner)) {
				final int granted = pointAuthority.grantedLeg(px, py, pz, viaHex);
				if (granted >= 0 && granted < legs.size()) {
					chosen = findRailByHex(forwardRails, legs.get(granted).railHex);
				}
			}
		}
		if (chosen == null && targetRailHex != null) {
			// Legacy live task target: legacy ops steering hint, used only when no manual/grant holds.
			for (final org.mtr.core.mmtr.point.MmtrPoint.MmtrPointLeg leg : legs) {
				if (leg.railHex.equals(targetRailHex)) {
					chosen = findRailByHex(forwardRails, leg.railHex);
					break;
				}
			}
		}
		if (chosen == null && legs.size() == 1) {
			chosen = findRailByHex(forwardRails, legs.get(0).railHex); // single continuation never needs authority
		}
		return chosen;
	}

	private static Rail findRailByHex(ObjectArrayList<Rail> forwardRails, String hex) {
		for (final Rail forwardRail : forwardRails) {
			if (forwardRail.getHexId().equals(hex)) {
				return forwardRail;
			}
		}
		return null;
	}

	private @Nullable Position otherEnd(Position at, Rail rail) {
		final Object2ObjectOpenHashMap<Position, Rail> neighbors = data.positionsToRail.get(at);
		if (neighbors == null) {
			return null;
		}
		for (final Object2ObjectOpenHashMap.Entry<Position, Rail> e : neighbors.object2ObjectEntrySet()) {
			if (e.getValue() == rail) {
				return e.getKey();
			}
		}
		return null;
	}
}