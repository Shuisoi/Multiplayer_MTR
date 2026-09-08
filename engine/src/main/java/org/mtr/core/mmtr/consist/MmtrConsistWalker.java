package org.mtr.core.mmtr.consist;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Data;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.mmtr.consist.MmtrConsistBody.OccupiedSegment;
import org.mtr.core.mmtr.consist.MmtrConsistBody.SpineLeg;
import org.mtr.core.mmtr.point.MmtrForkElection;
import org.mtr.core.mmtr.point.MmtrPointAuthority;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;

/**
 * B3: the double-ended Motion Core walker. It replaces the single-point {@code MmtrMotionWalker}
 * for Motion-Core-driven consists: the train is a {@link MmtrConsistBody} (an oriented interval on
 * the rail graph) plus a {@link MmtrCabState} (which cab the driver is in).
 *
 * <p>The two concepts the old walker conflated are now separate, which is what makes 换端 stop
 * looking like a 180° turn:</p>
 * <ul>
 *   <li><strong>posture</strong> — the body interval. Moving the train slides the interval along its
 *       spine; extending the spine at the leading side elects the next rail at that node;</li>
 *   <li><strong>direction</strong> — the manned cab. {@link #changeEnds(boolean)} flips the cab and
 *       touches nothing else, so no car moves (invariant I1) and the geometry is untouched (I2).</li>
 * </ul>
 *
 * <p>Invariants this class is responsible for: I1 (change-ends zero displacement), I2 (posture and
 * direction decoupled), I3 (cumulative distance never decreases; travel is always toward the manned
 * cab's facing, so the consist can never run backwards).</p>
 */
public final class MmtrConsistWalker {

	public static final double EPSILON_M = 1e-9;

	private final Data data;
	private final BranchStore branches;
	private final MmtrConsistBody body;
	private final MmtrCabState cabs = new MmtrCabState();
	private @Nullable String targetRailHex;
	private @Nullable MmtrPointAuthority pointAuthority;
	private String pointOwner = "";
	/** Cumulative distance actually consumed, m — monotone for the life of the walker (I3). */
	private double distanceM;
	private boolean haltedAtAuthority;
	private boolean endOfLine;
	/**
	 * B5 rear-clear: a point the consist's front has crossed but whose hold must survive until the
	 * <em>rear</em> has cleared it. Keyed by the cumulative distance at which that happens, so it
	 * survives spine trimming (arc coordinates shift, cumulative distance does not).
	 */
	private final ObjectArrayList<PendingRelease> pendingReleases = new ObjectArrayList<>();

	private record PendingRelease(long x, long y, long z, String viaRailHex, double rearClearsAtDistanceM) {
	}

	private MmtrConsistWalker(Data data, BranchStore branches, MmtrConsistBody body, @Nullable String targetRailHex) {
		this.data = data;
		this.branches = branches;
		this.body = body;
		this.targetRailHex = targetRailHex;
	}

	/**
	 * Place a consist with its A end {@code aEndOffsetM} along {@code startRail} (measured from
	 * {@code startEntryNode} toward the rail's other end), extending the spine over as many
	 * continuations as the body needs.
	 *
	 * @return the walker, or {@code null} when the body does not fit (an unset fork or a dead end
	 * before the far end of the consist)
	 */
	public static @Nullable MmtrConsistWalker place(Data data, @Nullable BranchStore branches, Rail startRail, Position startEntryNode, double aEndOffsetM, double[] carLengthsM, @Nullable String targetRailHex) {
		if (data == null || startRail == null || startEntryNode == null || carLengthsM == null || carLengthsM.length == 0 || aEndOffsetM < 0) {
			return null;
		}
		final Position startFarNode = otherEnd(data, startEntryNode, startRail);
		if (startFarNode == null) {
			return null;
		}
		final BranchStore store = branches == null ? new BranchStore() : branches;
		final ObjectArrayList<SpineLeg> spine = new ObjectArrayList<>();
		spine.add(new SpineLeg(startRail.getHexId(), startEntryNode, startFarNode, startRail.railMath.getLength()));
		double bodyLengthM = 0;
		for (final double carLengthM : carLengthsM) {
			if (carLengthM <= 0) {
				return null;
			}
			bodyLengthM += carLengthM;
		}
		while (spineLengthM(spine) < aEndOffsetM + bodyLengthM - EPSILON_M) {
			final SpineLeg next = electNextSpineLeg(data, store, targetRailHex, spine.get(spine.size() - 1), true, null, "");
			if (next == null) {
				return null;
			}
			spine.add(next);
		}
		final MmtrConsistBody body;
		try {
			body = new MmtrConsistBody(spine, aEndOffsetM, carLengthsM);
		} catch (final IllegalArgumentException | IllegalStateException e) {
			return null;
		}
		return new MmtrConsistWalker(data, store, body, targetRailHex);
	}

	public MmtrConsistBody body() {
		return body;
	}

	public MmtrCabState cabs() {
		return cabs;
	}

	/** Cumulative distance consumed since placement (never decreases). */
	public double distanceM() {
		return distanceM;
	}

	public boolean haltedAtAuthority() {
		return haltedAtAuthority;
	}

	public boolean endOfLine() {
		return endOfLine;
	}

	public void setTargetRailHex(@Nullable String targetRailHex) {
		this.targetRailHex = targetRailHex;
	}

	/**
	 * B5: wire this walker into the turnout authority under {@code owner} (vehicle/mission id). While
	 * wired, an unset fork with a grant for this owner is crossed at the granted ordered-leg index,
	 * and a crossed point is released only once the consist's <strong>rear</strong> has cleared it
	 * (rear-clear) instead of the moment the front crosses — a long consist must not free the point
	 * while its own tail is still standing on it.
	 */
	public void setPointAuthority(@Nullable MmtrPointAuthority authority, @Nullable String owner) {
		pointAuthority = authority;
		pointOwner = owner == null ? "" : owner;
		if (authority == null) {
			pendingReleases.clear();
		}
	}

	/** Points whose front has crossed but whose rear has not yet cleared (diagnostics/tests). */
	public int pendingReleaseCount() {
		return pendingReleases.size();
	}

	/** Terminal teardown: drop every point this owner holds or queued for. */
	public void releaseAllPoints() {
		pendingReleases.clear();
		if (pointAuthority != null && !pointOwner.isEmpty()) {
			pointAuthority.releaseAll(pointOwner);
		}
	}

	public boolean insertKey(MmtrCabState.Cab cab, boolean trainStopped, boolean driverAtCab) {
		return cabs.insertKey(cab, trainStopped, driverAtCab);
	}

	public boolean removeKey() {
		return cabs.removeKey();
	}

	/**
	 * 换端: the crew action of moving to the other cab. The body is not touched at all — this is the
	 * whole point of the model (I1/I2).
	 * @param trainStopped whether the train is at rest (required: the driver walks through the train)
	 */
	public boolean changeEnds(boolean trainStopped) {
		return cabs.changeEnds(trainStopped);
	}

	/** Rail the leading face currently stands on, or {@code null} when no cab is manned. */
	public @Nullable String leadingRailHex() {
		return cabs.isManned() ? leadingLeg().railHex() : null;
	}

	/** Offset of the leading face within {@link #leadingRailHex()}, m; {@code 0} when unmanned. */
	public double leadingOffsetM() {
		return cabs.isManned() ? body.legOffsetM(leadingArcM()) : 0;
	}

	/** Rail the trailing face currently stands on, or {@code null} when no cab is manned. */
	public @Nullable String trailingRailHex() {
		return cabs.isManned() ? trailingLeg().railHex() : null;
	}

	public double trailingOffsetM() {
		return cabs.isManned() ? body.legOffsetM(trailingArcM()) : 0;
	}

	/** The rails the consist stands on, each with the slice it occupies. */
	public ObjectArrayList<OccupiedSegment> occupancy() {
		return body.occupancy();
	}

	/**
	 * Advance the consist by up to {@code deltaM} metres in the manned cab's direction. The leading
	 * face moves first, the trailing face follows at the fixed consist length; at a node the spine is
	 * extended by electing the next rail (operator &gt; auto grant &gt; task target &gt; single
	 * continuation). Anything that cannot be covered is left unconsumed and the walker halts there.
	 *
	 * @return whether any distance was consumed
	 */
	public boolean advance(double deltaM) {
		haltedAtAuthority = false;
		if (!cabs.isManned() || deltaM <= EPSILON_M) {
			return false;
		}
		final boolean towardB = cabs.travelsToward(MmtrCabState.End.B);
		double remaining = deltaM;
		while (remaining > EPSILON_M && !haltedAtAuthority && !endOfLine) {
			final double space = towardB ? body.spineLengthM() - body.bEndArcM() : body.aEndArcM();
			if (space <= EPSILON_M) {
				if (!extendSpine(towardB)) {
					break;
				}
				continue;
			}
			final double step = Math.min(remaining, space);
			if (!body.slideBy(towardB ? step : -step)) {
				endOfLine = true;
				break;
			}
			distanceM += step;
			remaining -= step;
			body.trimOutsideLegs();
			releaseClearedPoints();
		}
		return remaining < deltaM - EPSILON_M;
	}

	/**
	 * B5 rear-clear: release every crossed point whose rear has now cleared it. The threshold is the
	 * cumulative distance at the crossing plus the consist length, i.e. exactly when the trailing face
	 * passes the node.
	 */
	private void releaseClearedPoints() {
		if (pointAuthority == null || pointOwner.isEmpty() || pendingReleases.isEmpty()) {
			return;
		}
		pendingReleases.removeIf(pending -> {
			if (distanceM + EPSILON_M < pending.rearClearsAtDistanceM()) {
				return false;
			}
			pointAuthority.passed(pending.x(), pending.y(), pending.z(), pending.viaRailHex(), pointOwner);
			return true;
		});
	}

	private double leadingArcM() {
		return cabs.travelsToward(MmtrCabState.End.B) ? body.bEndArcM() : body.aEndArcM();
	}

	private double trailingArcM() {
		return cabs.travelsToward(MmtrCabState.End.B) ? body.aEndArcM() : body.bEndArcM();
	}

	/** Arc position of the leading (front) face; equals the A end when no cab is manned. */
	public double frontArcM() {
		return leadingArcM();
	}

	/** Arc position of the trailing (rear) face; equals the B end when no cab is manned. */
	public double rearArcM() {
		return trailingArcM();
	}

	/** The rail containing {@code arcM} of the body spine, or {@code null} when outside it. */
	public @Nullable Rail railAtArcM(double arcM) {
		final SpineLeg leg = body.legAtArcM(arcM);
		return leg == null ? null : data.railIdMap.get(leg.railHex());
	}

	/** The spine leg containing {@code arcM}. */
	public @Nullable SpineLeg spineLegAtArcM(double arcM) {
		return body.legAtArcM(arcM);
	}

	/** Offset of {@code arcM} within its spine leg, measured from the leg's entry node. */
	public double offsetAtArcM(double arcM) {
		return body.legOffsetM(arcM);
	}

	/**
	 * The body spine as engine path data, always in A-end -&gt; B-end order. This is the payload a
	 * client mirror should replay: 换端 does not change it at all, which is what stops the rendered
	 * consist from flipping 180° when the crew changes ends.
	 */
	public ObjectArrayList<org.mtr.core.data.PathData> buildPathData() {
		final ObjectArrayList<org.mtr.core.data.PathData> out = new ObjectArrayList<>();
		for (int i = 0; i < body.legCount(); i++) {
			final SpineLeg leg = body.leg(i);
			final Rail rail = data.railIdMap.get(leg.railHex());
			if (rail != null) {
				out.add(new org.mtr.core.data.PathData(rail, 0L, 0L, 0, leg.entryNode(), leg.exitNode()));
			}
		}
		org.mtr.core.path.SidingPathFinder.generatePathDataDistances(out, 0);
		return out;
	}

	private SpineLeg leadingLeg() {
		final SpineLeg leg = body.legAtArcM(leadingArcM());
		return leg == null ? body.leg(cabs.travelsToward(MmtrCabState.End.B) ? body.legCount() - 1 : 0) : leg;
	}

	private SpineLeg trailingLeg() {
		final SpineLeg leg = body.legAtArcM(trailingArcM());
		return leg == null ? body.leg(cabs.travelsToward(MmtrCabState.End.B) ? 0 : body.legCount() - 1) : leg;
	}

	/**
	 * Grow the spine by one rail at the leading side. Election goes through
	 * {@link MmtrForkElection}, so an unset fork halts (never auto) and a real dead end sets
	 * {@link #endOfLine()}.
	 */
	private boolean extendSpine(boolean towardB) {
		final SpineLeg lead = towardB ? body.leg(body.legCount() - 1) : body.leg(0);
		final SpineLeg next = electNextSpineLeg(data, branches, targetRailHex, lead, towardB, pointAuthority, pointOwner);
		final Position node = towardB ? lead.exitNode() : lead.entryNode();
		if (next == null) {
			final Rail viaRail = data.railIdMap.get(lead.railHex());
			if (viaRail != null && MmtrForkElection.hasContinuation(data, node, viaRail)) {
				haltedAtAuthority = true;
			} else {
				endOfLine = true;
			}
			return false;
		}
		// B5: the front has just crossed this node; the point's hold (if we hold it) survives until the
		// rear clears the node, i.e. after another consist-length of travel.
		pendingReleases.add(new PendingRelease(node.getX(), node.getY(), node.getZ(), lead.railHex(), distanceM + body.lengthM()));
		if (towardB) {
			body.appendLeg(next);
		} else {
			body.prependLeg(next);
		}
		return true;
	}

	/** Elect the next spine leg beyond {@code lead} in the given direction; {@code null} = halt/end. */
	private static @Nullable SpineLeg electNextSpineLeg(Data data, BranchStore branches, @Nullable String targetRailHex, SpineLeg lead, boolean towardB, @Nullable MmtrPointAuthority authority, String owner) {
		final Position node = towardB ? lead.exitNode() : lead.entryNode();
		final Position cameFrom = towardB ? lead.entryNode() : lead.exitNode();
		final Rail viaRail = data.railIdMap.get(lead.railHex());
		if (viaRail == null) {
			return null;
		}
		final Rail next = MmtrForkElection.elect(data, branches, authority, owner.isEmpty() ? null : owner, targetRailHex, node, cameFrom, viaRail);
		if (next == null) {
			return null;
		}
		final Position far = otherEnd(data, node, next);
		if (far == null) {
			return null;
		}
		return new SpineLeg(next.getHexId(), towardB ? node : far, towardB ? far : node, next.railMath.getLength());
	}

	private static double spineLengthM(ObjectArrayList<SpineLeg> spine) {
		double total = 0;
		for (final SpineLeg leg : spine) {
			total += leg.lengthM();
		}
		return total;
	}

	private static @Nullable Position otherEnd(Data data, Position node, Rail rail) {
		final Object2ObjectOpenHashMap<Position, Rail> neighbors = data.positionsToRail.get(node);
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
