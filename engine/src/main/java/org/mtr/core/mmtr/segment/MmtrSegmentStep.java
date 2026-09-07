package org.mtr.core.mmtr.segment;

/**
 * Minimal (segment + offset) running position for the decoupled motion layer (M2-Core slice A).
 *
 * <p>A train is positioned on the rail network not by an index into a pre-baked whole-journey
 * path but by a single segment step — {@code railHex} plus how far it has travelled from that
 * segment's start ({@code offsetM}) in its direction of travel ({@code reversed}). Reaching the
 * end of a segment is a <em>node event</em>: the motion layer then asks {@link MmtrNodeRouter}
 * (turnout state / task) which rail to continue onto, instead of a route having been baked
 * ahead of time.</p>
 *
 * <p>Geometry (segment length, world positions) is supplied by the caller from real rails; this
 * class only carries the scalar position + carry-over arithmetic so it stays pure and
 * deterministic and is unit-testable without a world.</p>
 */
public final class MmtrSegmentStep {

	/** Numeric safety epsilon (metres). */
	public static final double EPSILON_M = 1e-6;

	public final String railHex;
	public final double offsetM;
	public final double lengthM;
	public final boolean reversed;

	private MmtrSegmentStep(String railHex, double offsetM, double lengthM, boolean reversed) {
		this.railHex = railHex;
		this.offsetM = offsetM;
		this.lengthM = lengthM;
		this.reversed = reversed;
	}

	public static MmtrSegmentStep atStart(String railHex, double lengthM, boolean reversed) {
		return new MmtrSegmentStep(railHex, 0, lengthM, reversed);
	}

	public static MmtrSegmentStep of(String railHex, double offsetM, double lengthM, boolean reversed) {
		return new MmtrSegmentStep(railHex, Math.max(0, offsetM), lengthM, reversed);
	}

	public double remainingM() {
		return Math.max(0, lengthM - offsetM);
	}

	public boolean atEnd() {
		return lengthM - offsetM <= EPSILON_M;
	}

	/**
	 * Advance by {@code distanceM}. Returns a step that may still be past the segment end (offset
	 * beyond {@code lengthM}) — the caller reads {@link #overshootM()} and, at the node, elects
	 * the next rail, carrying the remainder onto it.
	 */
	public MmtrSegmentStep advance(double distanceM) {
		return new MmtrSegmentStep(railHex, offsetM + distanceM, lengthM, reversed);
	}

	/** Positive distance travelled past the end of this segment, else 0. */
	public double overshootM() {
		return Math.max(0, offsetM - lengthM);
	}

	@Override
	public String toString() {
		return railHex + (reversed ? "R" : "") + "@" + offsetM + "/" + lengthM;
	}
}
