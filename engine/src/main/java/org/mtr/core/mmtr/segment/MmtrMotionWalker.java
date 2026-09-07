package org.mtr.core.mmtr.segment;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Data;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
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
	private final @Nullable String targetRailHex;

	private Rail rail;
	private Position enteredFrom;
	private Position ahead;
	private double offsetM;

	private boolean haltedAtAuthority;
	private boolean endOfLine;
	private boolean atTarget;

	private MmtrMotionWalker(Data data, Rail startRail, Position startAt, BranchStore branches, @Nullable String targetRailHex) {
		this.data = data;
		this.branches = branches;
		this.targetRailHex = targetRailHex;
		this.rail = startRail;
		this.enteredFrom = startAt;
		this.ahead = otherEnd(startAt, startRail);
		this.offsetM = 0;
		this.atTarget = startRail.getHexId().equals(targetRailHex);
	}

	public static MmtrMotionWalker start(Data data, Rail startRail, Position startAt, BranchStore branches, @Nullable String targetRailHex) {
		return new MmtrMotionWalker(data, startRail, startAt, branches, targetRailHex);
	}

	public String railHex() {
		return rail.getHexId();
	}

	public double offsetM() {
		return offsetM;
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

	public boolean haltedAtAuthority() {
		return haltedAtAuthority;
	}

	public boolean endOfLine() {
		return endOfLine;
	}

	public boolean atTarget() {
		return atTarget;
	}

	/**
	 * Advance up to {@code deltaM} metres from the current position, crossing nodes by authority as
	 * they are reached. Any part of the distance that cannot be covered (unset fork, end of line)
	 * is left unconsumed and the walker stops there.
	 */
	public void advance(double deltaM) {
		double remaining = Math.max(0, deltaM);
		while (remaining > 0 && !haltedAtAuthority && !endOfLine && !atTarget) {
			final double len = rail.railMath.getLength();
			final double toNode = len - offsetM;
			if (remaining < toNode) {
				offsetM += remaining;
				return;
			}
			remaining -= toNode;
			offsetM = len; // reached the ahead node

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
			}

			rail = next;
			enteredFrom = ahead;
			ahead = otherEnd(ahead, next);
			offsetM = 0;
			if (rail.getHexId().equals(targetRailHex)) {
				atTarget = true;
				return;
			}
		}
	}

	private @Nullable Rail electAtFork(ObjectArrayList<Rail> forwardRails, ObjectArrayList<Position> forwardEnds) {
		final double ax = ahead.getX() - enteredFrom.getX();
		final double az = ahead.getZ() - enteredFrom.getZ();
		final double[] cos = new double[forwardRails.size()];
		for (int i = 0; i < forwardRails.size(); i++) {
			final double bx = forwardEnds.get(i).getX() - ahead.getX();
			final double bz = forwardEnds.get(i).getZ() - ahead.getZ();
			final double la = Math.sqrt(ax * ax + az * az);
			final double lb = Math.sqrt(bx * bx + bz * bz);
			cos[i] = la == 0 || lb == 0 ? -2 : (ax * bx + az * bz) / (la * lb);
		}
		int b0 = 0;
		for (int i = 1; i < cos.length; i++) {
			if (cos[i] > cos[b0]) {
				b0 = i;
			}
		}
		int b1 = b0 == 0 ? 1 : 0;
		for (int i = 0; i < cos.length; i++) {
			if (i != b0 && cos[i] > cos[b1]) {
				b1 = i;
			}
		}
		final MmtrNodeRouter.Continuation continuation = new MmtrNodeRouter.Continuation(forwardRails.get(b0).getHexId(), forwardRails.get(b1).getHexId());
		final @Nullable String chosen = MmtrNodeRouter.electFromStore(continuation, branches, ahead.getX(), ahead.getY(), ahead.getZ(), rail.getHexId(), targetRailHex);
		if (chosen == null) {
			return null;
		}
		for (int i = 0; i < forwardRails.size(); i++) {
			if (forwardRails.get(i).getHexId().equals(chosen)) {
				return forwardRails.get(i);
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