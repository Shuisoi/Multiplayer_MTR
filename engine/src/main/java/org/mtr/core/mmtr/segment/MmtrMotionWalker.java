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
public final class MmtrMotionWalker implements MmtrMotionPosition {

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

	/**
	 * ③ 车尾清岔: points whose head has crossed but whose TAIL has not yet cleared. The hold is released
	 * only once the tail is past the node (plus the junction clearance margin), so the points cannot be
	 * moved under the trailing cars of a consist this walker drives.
	 */
	private final ObjectArrayList<PendingRelease> pendingReleases = new ObjectArrayList<>();
	private double tailLengthM;

	private record PendingRelease(long x, long y, long z, String viaRailHex, double rearClearsAtDistanceM) {
	}

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

	/** The rail the walker currently stands on. */
	public Rail currentRail() {
		return rail;
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
	 * Signal S1: pure look-ahead — predicts which rail {@link #advance(double)} would elect if the
	 * walker consumed the whole remaining current rail right now and crossed its ahead node
	 * (operator > auto grant > task target > single continuation; never auto). Returns {@code null}
	 * when the walker would NOT board a next rail: end of line (no neighbours / no continuation) or
	 * a halt at an unset fork. No state is changed — no crossing record, no authority release, no
	 * legs. Must stay in sync with the node logic inside {@link #advance} (the S1 blocking test
	 * {@code peekNextRailPredictsFollowingAdvanceElect} locks that contract).
	 */
	public @Nullable Rail peekNextRail() {
		final Object2ObjectOpenHashMap<Position, Rail> neighbors = data.positionsToRail.get(ahead);
		if (neighbors == null) {
			return null;
		}
		final ObjectArrayList<Rail> forwardRails = new ObjectArrayList<>();
		final ObjectArrayList<Position> forwardEnds = new ObjectArrayList<>();
		for (final Object2ObjectOpenHashMap.Entry<Position, Rail> e : neighbors.object2ObjectEntrySet()) {
			if (e.getValue() != rail) {
				forwardRails.add(e.getValue());
				forwardEnds.add(e.getKey());
			}
		}
		if (forwardRails.isEmpty()) {
			return null;
		} else if (forwardRails.size() == 1) {
			return forwardRails.get(0);
		} else {
			return electAtFork(forwardRails, forwardEnds);
		}
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

	/**
	 * ③: the length of the vehicle/consist this walker drives, used to delay a crossed point's release
	 * until the tail has cleared it (0 = head-only walker, the pre-③ behaviour).
	 */
	public void setTailLengthM(double tailLengthM) {
		this.tailLengthM = Math.max(0, tailLengthM);
	}

	/** Points whose head crossed but whose tail has not cleared yet (diagnostics/tests). */
	public int pendingReleaseCount() {
		return pendingReleases.size();
	}

	/** ③/②: release every crossed point whose tail (plus junction clearance) has now cleared it. */
	private void releaseClearedPoints() {
		if (pointAuthority == null || pointAuthorityOwner == null || pendingReleases.isEmpty()) {
			return;
		}
		pendingReleases.removeIf(pending -> {
			if (distanceM + 1e-9 < pending.rearClearsAtDistanceM()) {
				return false;
			}
			pointAuthority.passed(pending.x(), pending.y(), pending.z(), pending.viaRailHex(), pointAuthorityOwner);
			return true;
		});
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
	public boolean advance(double deltaM) {
		// A halt at an unset fork is a "waiting for the operator/任务 to decide", not terminal: a fresh
		// advance() re-attempts the node (自由开). End-of-line / target stay terminal.
		haltedAtAuthority = false;
		releaseClearedPoints();
		double remaining = Math.max(0, deltaM);
		while (remaining > 0 && !haltedAtAuthority && !endOfLine && !atTarget) {
			final double len = rail.railMath.getLength();
			final double toNode = len - offsetM;
			if (remaining < toNode) {
				offsetM += remaining;
				distanceM += remaining;
				releaseClearedPoints();
				return true;
			}
			remaining -= toNode;
			offsetM = len; // reached the ahead node
			distanceM += toNode;

			final Object2ObjectOpenHashMap<Position, Rail> neighbors = data.positionsToRail.get(ahead);
			if (neighbors == null) {
				endOfLine = true;
				break;
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
				break;
			} else if (forwardRails.size() == 1) {
				next = forwardRails.get(0);
			} else {
				next = electAtFork(forwardRails, forwardEnds);
				if (next == null) {
					haltedAtAuthority = true;
					break;
				}
				// The train has crossed the fork node onto the elected continuation: its authority hold
				// (if any) is consumed and the queue advances (over-release is a no-op) - but only once the
				// TAIL has cleared (③ 车尾清岔 + ② 岔区清限), not at this instant.
				crossedPointKeys.add(ahead.getX() + "," + ahead.getY() + "," + ahead.getZ() + "|" + rail.getHexId());
				if (pointAuthority != null && pointAuthorityOwner != null) {
					final Position crossedNode = ahead;
					final double clearanceM = data.positionsToRail.get(crossedNode) == null || data.positionsToRail.get(crossedNode).size() < 3
						? 0
						: org.mtr.core.data.Vehicle.MMTR_JUNCTION_CLEARANCE_M;
					pendingReleases.add(new PendingRelease(crossedNode.getX(), crossedNode.getY(), crossedNode.getZ(), rail.getHexId(), distanceM + tailLengthM + clearanceM));
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
				break;
			}
		}
		releaseClearedPoints();
		return remaining < deltaM;
	}

	/**
	 * ①: see {@link MmtrMotionPosition#wouldHaltAtForkOn(Rail)}. The election inputs are the walker's
	 * own (branch store, authority grant, target), so the answer cannot drift from {@link #advance}.
	 */
	@Override
	public boolean wouldHaltAtForkOn(@Nullable Rail target) {
		if (target == null || rail == null || ahead == null || enteredFrom == null) {
			return false;
		}
		final boolean onCurrentRail = target == rail || target.getHexId().equals(rail.getHexId());
		final Position entry = onCurrentRail ? enteredFrom : ahead;
		final Position far = otherEnd(entry, target);
		if (far == null || !org.mtr.core.mmtr.point.MmtrForkElection.hasContinuation(data, far, target)) {
			return false;
		}
		return org.mtr.core.mmtr.point.MmtrForkElection.elect(data, branches, pointAuthority, pointAuthorityOwner, targetRailHex, far, entry, target) == null;
	}

	private @Nullable Rail electAtFork(ObjectArrayList<Rail> forwardRails, ObjectArrayList<Position> forwardEnds) {
		// P1/P3: the ordered-leg election (operator > auto grant > task target > single continuation)
		// now lives in MmtrForkElection so the consist-body walker (B3) shares one implementation.
		// Continuations are ordered deterministically per approach direction (straight > left > right
		// > other, MmtrPoint); an authoritative junction table (进向表) for (node, via) overrides the
		// geometry entirely.
		return org.mtr.core.mmtr.point.MmtrForkElection.elect(data, branches, pointAuthority, pointAuthorityOwner, targetRailHex, ahead, enteredFrom, rail);
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

	/**
	 * MMTR 尽头换向 (terminal flip / 换端): the consist has come to rest at the dead end of its
	 * current rail (its {@link #aheadNode()} has no continuation - {@link #endOfLine()}), and now
	 * changes ends: the walker turns around on the SAME rail and will run back toward the entry
	 * node. A return leg (dead end -&gt; entry) is appended to the motion path so the vehicle keeps
	 * driving "forward" into the reversed run; {@link #distanceM()} keeps accumulating monotonically
	 * (the flip itself adds no distance - the return leg re-covers the same rail).
	 * @return whether the flip was legal (only at a true dead end with the head exactly at the far
	 * node); {@code false} leaves the walker untouched.
	 */
	public boolean flipDirection() {
		if (ahead == null || enteredFrom == null) {
			return false;
		}
		final double len = rail.railMath.getLength();
		if (len <= 0 || offsetM < len - 1e-3) {
			// The head must already rest at (within 1 mm of) the far node of the rail.
			return false;
		}
		final Position deadEnd = ahead;
		final Position entry = enteredFrom;
		enteredFrom = deadEnd;
		ahead = entry;
		offsetM = 0;
		endOfLine = false;
		atTarget = false;
		legs.add(new PathData(rail, 0L, 0L, 0, deadEnd, entry));
		return true;
	}

	/**
	 * {@link MmtrMotionPosition#changeEnds(boolean)}: the legacy walker has no cab model, so this is
	 * exactly the old terminal {@link #flipDirection()}. The caller passes {@code trainStopped} because
	 * it owns the speed gate; a false value is ignored here for source compatibility, never to allow a
	 * moving flip.
	 */
	@Override
	public boolean changeEnds(boolean trainStopped) {
		return flipDirection();
	}
}