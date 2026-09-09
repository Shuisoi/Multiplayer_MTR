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

import java.util.UUID;

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
public final class MmtrConsistWalker implements org.mtr.core.mmtr.segment.MmtrMotionPosition {

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
	/** The leading end has boarded {@link #targetRailHex} and the run rests there (task arrival). */
	private boolean atTarget;
	/**
	 * The manned cab {@link #endOfLine}/{@link #atTarget} were computed for. Both flags describe the
	 * DIRECTION of travel ("the front reached a dead end" / "the front boarded the target"), so they
	 * are meaningless once the crew mans the other cab — walking into the other cab (or an operator
	 * command) changes the direction without going through {@link #changeEnds(boolean)}.
	 */
	private MmtrCabState.Cab flagsCab = MmtrCabState.Cab.NONE;
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
		return place(data, branches, startRail, startEntryNode, aEndOffsetM, carLengthsM, targetRailHex, null, null);
	}

	/**
	 * C5: the same placement, carrying the formation's coupler seams into the body so the consist can
	 * be cut later (U6) and the body stays the single source of truth for the arc geometry.
	 */
	public static @Nullable MmtrConsistWalker place(Data data, @Nullable BranchStore branches, Rail startRail, Position startEntryNode, double aEndOffsetM, double[] carLengthsM, @Nullable String targetRailHex, @Nullable double[] seamArcMs, @Nullable int[] seamCarIndexes) {
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
			body = new MmtrConsistBody(spine, aEndOffsetM, carLengthsM, seamArcMs, seamCarIndexes);
		} catch (final IllegalArgumentException | IllegalStateException e) {
			return null;
		}
		final MmtrConsistWalker walker = new MmtrConsistWalker(data, store, body, targetRailHex);
		walker.atTarget = targetRailHex != null && targetRailHex.equals(startRail.getHexId());
		return walker;
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
		syncDirectionFlags();
		return endOfLine;
	}

	public void setTargetRailHex(@Nullable String targetRailHex) {
		this.targetRailHex = targetRailHex;
		if (targetRailHex == null || !targetRailHex.equals(leadingRailHex())) {
			atTarget = false; // retargeting away from the boarded rail resumes the run
		}
	}

	/** Whether the leading end has boarded the task target rail (the run rests there). */
	public boolean atTarget() {
		syncDirectionFlags();
		return atTarget;
	}

	/** Rail the leading end stands on (the rail a driver/signal layer reads), or {@code null} when unmanned. */
	public @Nullable Rail currentRail() {
		return cabs.isManned() ? data.railIdMap.get(leadingLeg().railHex()) : null;
	}

	/** Hex of {@link #currentRail()}. */
	public @Nullable String currentRailHex() {
		return leadingRailHex();
	}

	/** Length of the rail the leading end stands on, m. */
	public double currentRailLengthM() {
		final SpineLeg leg = leadingLeg();
		return leg.lengthM();
	}

	/** Offset of the leading end within its rail, m. */
	public double offsetM() {
		return cabs.isManned() ? frontOffsetM() : 0;
	}

	/** Node the leading end is moving away from (the entry node of its current rail). */
	public @Nullable Position enteredFromPosition() {
		return leadingLeg().entryNode();
	}

	/** Node the leading end is moving toward (the exit node of its current rail). */
	public @Nullable Position aheadNode() {
		return leadingLeg().exitNode();
	}

	/**
	 * Signal S1/S2 look-ahead: the rail the consist would continue onto after the one its leading
	 * end is on, without changing any state (no election is consumed, no authority touched). Returns
	 * {@code null} when the consist would halt (unset fork) or the line ends there.
	 *
	 * <p>If the spine already reaches past the leading rail the answer is simply the next spine leg;
	 * otherwise the same {@link MmtrForkElection} the advance uses is consulted, so the prediction and
	 * the real election cannot disagree.</p>
	 */
	public @Nullable Rail peekNextRail() {
		if (!cabs.isManned()) {
			return null;
		}
		final boolean towardB = cabs.travelsToward(MmtrCabState.End.B);
		final SpineLeg lead = leadingLeg();
		final int index = legIndex(lead);
		final int nextIndex = towardB ? index + 1 : index - 1;
		if (index >= 0 && nextIndex >= 0 && nextIndex < body.legCount()) {
			return data.railIdMap.get(body.leg(nextIndex).railHex());
		}
		final SpineLeg next = electNextSpineLeg(data, branches, targetRailHex, lead, towardB, pointAuthority, pointOwner);
		return next == null ? null : data.railIdMap.get(next.railHex());
	}

	private int legIndex(SpineLeg leg) {
		for (int i = 0; i < body.legCount(); i++) {
			if (body.leg(i) == leg) {
				return i;
			}
		}
		return -1;
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

	/** Crew key insert, identifying the crew member (a system key in the cab is displaced). */
	public boolean insertKey(MmtrCabState.Cab cab, boolean trainStopped, boolean driverAtCab, @Nullable UUID crewUuid) {
		return cabs.insertKey(cab, trainStopped, driverAtCab, crewUuid);
	}

	/** Engine placeholder key (yard spawn / auto run); refused while a key is already in the cab. */
	public boolean insertSystemKey(MmtrCabState.Cab cab, boolean trainStopped) {
		return cabs.insertSystemKey(cab, trainStopped);
	}

	public boolean removeKey() {
		return cabs.removeKey();
	}

	/** Crew key pull, restricted to the key {@code crewUuid} actually holds. */
	public boolean removeKey(@Nullable UUID crewUuid) {
		return cabs.removeKey(crewUuid);
	}

	/**
	 * 换端: the crew action of moving to the other cab. The body is not touched at all — this is the
	 * whole point of the model (I1/I2).
	 * @param trainStopped whether the train is at rest (required: the driver walks through the train)
	 */
	public boolean changeEnds(boolean trainStopped) {
		if (!cabs.changeEnds(trainStopped)) {
			return false;
		}
		// The direction of travel reverses, so "ahead" is the other way now: the flags that described
		// the old direction (dead end reached, task target boarded) no longer apply.
		endOfLine = false;
		atTarget = false;
		return true;
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
	 * Drops the direction-dependent halt flags when the manned cab changed. Without this, a crew that
	 * takes the OTHER cab of a train standing at a dead end (or on its task target) could not drive
	 * away: {@code endOfLine}/{@code atTarget} describe the old direction and would block every
	 * advance. 换端 clears them explicitly; this covers every other way the cab can change (the crew
	 * walking into the other cab via {@code enterMmtrCabAtCar}, an operator {@code cab} command, or a
	 * key inserted directly through {@link MmtrCabState}).
	 */
	private void syncDirectionFlags() {
		final MmtrCabState.Cab active = cabs.activeCab();
		if (active != flagsCab) {
			flagsCab = active;
			endOfLine = false;
			atTarget = false;
		}
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
		syncDirectionFlags();
		if (!cabs.isManned() || deltaM <= EPSILON_M || atTarget) {
			return false;
		}
		final boolean towardB = cabs.travelsToward(MmtrCabState.End.B);
		double remaining = deltaM;
		while (remaining > EPSILON_M && !haltedAtAuthority && !endOfLine && !atTarget) {
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
			if (targetRailHex != null && targetRailHex.equals(leadingLeg().railHex())) {
				atTarget = true; // the leading end has boarded the task target: rest here
			}
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

	/**
	 * C5b: the rail a signal/occupancy layer must reference — the manned cab's leading end, or the A
	 * end while the consist is unmanned. {@link #currentRail()} deliberately returns {@code null} when
	 * nobody holds the key (there is no "front" without a driver), but a stabled consist still occupies
	 * its rail and must be seen by the yard and by other trains.
	 */
	public @Nullable Rail referenceRail() {
		return railAtArcM(referenceArcM());
	}

	/** C5b: the arc of the face {@link #referenceRail()} refers to. */
	public double referenceArcM() {
		return frontArcM();
	}

	/** C5b: offset of the reference face within its rail. */
	public double referenceOffsetM() {
		return offsetAtArcM(referenceArcM());
	}

	/** The rail behind a spine leg. */
	public @Nullable Rail railForLeg(SpineLeg leg) {
		return data.railIdMap.get(leg.railHex());
	}

	/** The spine leg containing {@code arcM}. */
	public @Nullable SpineLeg spineLegAtArcM(double arcM) {
		return body.legAtArcM(arcM);
	}

	/** Offset of {@code arcM} within its spine leg, measured from the leg's entry node. */
	public double offsetAtArcM(double arcM) {
		return body.legOffsetM(arcM);
	}

	/** The body spine as engine path data, always in A-end -&gt; B-end order. This is the payload a
	 * client mirror should replay: 换端 does not change it at all, which is what stops the rendered
	 * consist from flipping 180° when the crew changes ends. */
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

	/** {@link org.mtr.core.mmtr.segment.MmtrMotionPosition#buildLegs()} — the A→B spine. */
	@Override
	public ObjectArrayList<org.mtr.core.data.PathData> buildLegs() {
		return buildPathData();
	}

	/**
	 * B7.2b: the mirror path a client must replay — the body spine oriented <strong>tail → head</strong>
	 * (the direction of travel) with its cumulative distances anchored so the leading face lands exactly
	 * at {@link #distanceM()}. That anchoring is what keeps the whole vehicle pipeline (railProgress,
	 * car placement, occupancy footprint, stop targets) in one coordinate space, while the rail order
	 * follows the manned cab: after 换端 the path is re-ordered, but every car stays on the same rail at
	 * the same offset, so the rendered consist does not turn around.
	 */
	public ObjectArrayList<org.mtr.core.data.PathData> buildMirrorLegs() {
		final ObjectArrayList<org.mtr.core.data.PathData> out = new ObjectArrayList<>();
		final boolean towardB = cabs.travelsToward(MmtrCabState.End.B);
		if (towardB) {
			for (int i = 0; i < body.legCount(); i++) {
				addLeg(out, body.leg(i), true);
			}
		} else {
			for (int i = body.legCount() - 1; i >= 0; i--) {
				addLeg(out, body.leg(i), false);
			}
		}
		org.mtr.core.path.SidingPathFinder.generatePathDataDistances(out, distanceM - mirrorHeadArcM());
		return out;
	}

	/** Distance of the leading face along {@link #buildMirrorLegs()}, m. */
	public double mirrorHeadArcM() {
		return cabs.travelsToward(MmtrCabState.End.B) ? body.bEndArcM() : body.spineLengthM() - body.aEndArcM();
	}

	/** Offset of the leading face within the last mirror leg (measured from that leg's start). */
	public double mirrorHeadOffsetM() {
		final SpineLeg leg = leadingLeg();
		return cabs.travelsToward(MmtrCabState.End.B) ? frontOffsetM() : leg.lengthM() - frontOffsetM();
	}

	/**
	 * Whether the client must render the car list backwards: true when the A-end car is at the rear,
	 * i.e. when the B end leads (CAB_A manned). Mirrored as {@code Vehicle.reversed}.
	 */
	public boolean mirrorReversed() {
		return cabs.travelsToward(MmtrCabState.End.B);
	}

	private void addLeg(ObjectArrayList<org.mtr.core.data.PathData> out, SpineLeg leg, boolean tailToHead) {
		final Rail rail = data.railIdMap.get(leg.railHex());
		if (rail == null) {
			return;
		}
		out.add(tailToHead
			? new org.mtr.core.data.PathData(rail, 0L, 0L, 0, leg.entryNode(), leg.exitNode())
			: new org.mtr.core.data.PathData(rail, 0L, 0L, 0, leg.exitNode(), leg.entryNode()));
	}

	/** {@link org.mtr.core.mmtr.segment.MmtrMotionPosition#railHex()} — the leading rail's hex. */
	@Override
	public @Nullable String railHex() {
		return leadingRailHex();
	}

	/** {@link org.mtr.core.mmtr.segment.MmtrMotionPosition#legCount()}. */
	@Override
	public int legCount() {
		return body.legCount();
	}

	/**
	 * {@link org.mtr.core.mmtr.segment.MmtrMotionPosition#drainCrossedPointKeys()}: the consist walker
	 * releases its own holds on rear-clear (B5), so there is nothing for the vehicle to drain.
	 */
	@Override
	public ObjectArrayList<String> drainCrossedPointKeys() {
		return new ObjectArrayList<>();
	}

	private int leadingLegIndex() {
		final boolean towardB = cabs.travelsToward(MmtrCabState.End.B);
		final int index = body.legIndexAtArcM(leadingArcM(), towardB);
		return index >= 0 ? index : (towardB ? body.legCount() - 1 : 0);
	}

	private int trailingLegIndex() {
		final boolean towardB = cabs.travelsToward(MmtrCabState.End.B);
		final int index = body.legIndexAtArcM(trailingArcM(), !towardB);
		return index >= 0 ? index : (towardB ? 0 : body.legCount() - 1);
	}

	/** The spine leg the leading face is on (at a boundary: the leg it is entering). */
	public SpineLeg leadingLeg() {
		return body.leg(leadingLegIndex());
	}

	/** The spine leg the trailing face is on (at a boundary: the leg it is leaving). */
	public SpineLeg trailingLeg() {
		return body.leg(trailingLegIndex());
	}

	/** Offset of the leading face within {@link #leadingLeg()}, m. */
	public double frontOffsetM() {
		return leadingArcM() - body.legStartArcM(leadingLegIndex());
	}

	/** Offset of the trailing face within {@link #trailingLeg()}, m. */
	public double rearOffsetM() {
		return trailingArcM() - body.legStartArcM(trailingLegIndex());
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
		if (targetRailHex != null && targetRailHex.equals(next.railHex())) {
			// The leading end has just reached the task target rail: rest exactly at its entry
			// (offset 0), like the single-point walker did.
			atTarget = true;
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
