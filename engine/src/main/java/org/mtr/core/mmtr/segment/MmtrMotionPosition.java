package org.mtr.core.mmtr.segment;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.mmtr.point.MmtrPointAuthority;

/**
 * The positional motion source a {@code Vehicle} drives in live Motion-Core mode.
 *
 * <p>Introduced in B7.2a so the Vehicle depends on the <em>capability</em> rather than on one
 * implementation. Two implementations exist while the consist-body rewrite lands:</p>
 *
 * <ul>
 *   <li>{@link MmtrMotionWalker} — the legacy single-point walker (head only, no cab);</li>
 *   <li>{@code MmtrConsistWalker} — the double-ended consist body with the manned-cab direction
 *       model, which is what makes 换端 a cab change instead of a 180° turn.</li>
 * </ul>
 *
 * <p>The method set is exactly what {@code Vehicle} calls; keeping it minimal means the swap is a
 * type change plus a new engagement entry, not a rewrite of the motion tick.</p>
 */
public interface MmtrMotionPosition {

	/** Consume up to {@code deltaM} metres in the direction of travel; unconsumable distance is left.
	 * @return whether any distance was consumed */
	boolean advance(double deltaM);

	/** Cumulative distance consumed since the source started, m (never decreases). */
	double distanceM();

	/** Hex of the rail the leading end stands on. */
	@Nullable String railHex();

	/** The rail the leading end stands on, when the graph is available. */
	@Nullable Rail currentRail();

	/** Node the leading end is moving away from. */
	@Nullable Position enteredFromPosition();

	/** Node the leading end is moving toward. */
	@Nullable Position aheadNode();

	/** Offset of the leading end within its rail, m. */
	double offsetM();

	/** Length of the rail the leading end stands on, m. */
	double currentRailLengthM();

	/** Side-effect-free look-ahead at the next rail, or {@code null} when the train would halt/end. */
	@Nullable Rail peekNextRail();

	/** Whether the leading end has boarded the task target rail. */
	boolean atTarget();

	/** Task target rail (hex) or {@code null} to clear. */
	void setTargetRailHex(@Nullable String targetRailHex);

	/** The train is waiting at a fork for an operator/task/grant decision (not terminal). */
	boolean haltedAtAuthority();

	/** The line ends ahead of the train (terminal). */
	boolean endOfLine();

	/** Number of ordered legs recorded so far (used to detect newly boarded rails). */
	int legCount();

	/** Ordered legs as engine path data (the vehicle's mirror path). */
	ObjectArrayList<PathData> buildLegs();

	/** Wire this source into the turnout authority under {@code owner} (null/null unwires). */
	void setPointAuthority(@Nullable MmtrPointAuthority authority, @Nullable String owner);

	/** Forks crossed since the last drain, as {@code x,y,z|viaHex} keys. */
	ObjectArrayList<String> drainCrossedPointKeys();

	/**
	 * Change ends (换端). For a consist body this flips the manned cab and moves nothing; for the
	 * legacy single-point walker it is the old terminal flip. The stop precondition is the caller's
	 * responsibility and is passed explicitly.
	 */
	boolean changeEnds(boolean trainStopped);
}
