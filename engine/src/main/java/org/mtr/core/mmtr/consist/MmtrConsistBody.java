package org.mtr.core.mmtr.consist;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Position;

/**
 * 编组体 (consist body) — the physical train as an <em>oriented interval</em> on the rail graph.
 *
 * <p>Motion Core used to track a single point (the head) plus the legs it had already walked. That
 * representation cannot express a train whose body spans several rails, and it made 换端 (change
 * ends) look like the vehicle turning 180° in place: the walker swapped its front/back nodes and
 * appended a reversed return leg, so the synced path direction flipped while the cars never
 * moved. This class replaces it: the body is a spine of rail legs plus the arc position of its
 * <strong>A end</strong>, and every car position is measured from the A end.</p>
 *
 * <p>Consequences that matter for the rewrite:</p>
 * <ul>
 *   <li>换端 does not touch this object at all — changing the driving cab cannot move a car
 *       (design invariant I1);</li>
 *   <li>occupancy is the intersection of {@code [aEndArcM, aEndArcM + lengthM]} with the spine, so a
 *       consist crossing three rails reports three occupied segments (I4);</li>
 *   <li>everything here is pure arithmetic over arc lengths — no world, no simulator, deterministic
 *       and unit-testable.</li>
 * </ul>
 *
 * <p>See {@code docs/01-设计/运动系统-编组体与双驾驶室换端-设计.md} (§3.1) for the model and §4
 * for the invariants this class is responsible for.</p>
 */
public final class MmtrConsistBody {

	/** Arc-length epsilon (metres): overlaps shorter than this count as "not occupied". */
	public static final double EPSILON_M = 1e-9;

	/**
	 * One leg of the body spine: a real rail plus its two end nodes. {@code entryNode} is the node
	 * on the A-end side of the leg, {@code exitNode} the B-end side, so the leg's own coordinate
	 * runs {@code entryNode -> exitNode} and a world position at {@code offsetM} is unambiguous.
	 */
	public record SpineLeg(String railHex, Position entryNode, Position exitNode, double lengthM) {
		public SpineLeg {
			if (railHex == null || railHex.isEmpty()) {
				throw new IllegalArgumentException("spine leg needs a rail hex");
			}
			if (lengthM <= 0) {
				throw new IllegalArgumentException("spine leg length must be positive: " + lengthM);
			}
		}
	}

	/** A slice of one rail occupied by the body, in that rail's own {@code entryNode -> exitNode} coordinate. */
	public record OccupiedSegment(String railHex, double fromM, double toM) {
		public double lengthM() {
			return toM - fromM;
		}
	}

	private final ObjectArrayList<SpineLeg> spine = new ObjectArrayList<>();
	private final double[] carLengthsM;
	/** C5: arc positions (from the A end) of the coupler seams; empty for a fixed unit (an EMU). */
	private final double[] seamArcMs;
	/** Car index in front of each seam (parallel to {@link #seamArcMs}). */
	private final int[] seamCarIndexes;
	private double aEndArcM;

	/**
	 * @param spine      ordered legs from the A-end side toward the B-end side; at least one
	 * @param aEndArcM   arc position of the consist's A end, measured from the spine start
	 * @param carLengthsM per-car length in consist order (index 0 = the A-end car); all positive
	 */
	public MmtrConsistBody(ObjectArrayList<SpineLeg> spine, double aEndArcM, double[] carLengthsM) {
		this(spine, aEndArcM, carLengthsM, null, null);
	}

	/**
	 * C5: the body also carries the formation's coupler seams, so a consist body can be cut at a
	 * seam (U6) and its arc geometry stays the single source of truth.
	 *
	 * @param seamArcMs      arc position of each seam from the A end (ascending, inside the body)
	 * @param seamCarIndexes the car index in front of each seam (parallel to {@code seamArcMs})
	 */
	public MmtrConsistBody(ObjectArrayList<SpineLeg> spine, double aEndArcM, double[] carLengthsM, @Nullable double[] seamArcMs, @Nullable int[] seamCarIndexes) {
		if (spine == null || spine.isEmpty()) {
			throw new IllegalArgumentException("consist body needs at least one spine leg");
		}
		if (carLengthsM == null || carLengthsM.length == 0) {
			throw new IllegalArgumentException("consist body needs at least one car");
		}
		for (final double carLengthM : carLengthsM) {
			if (carLengthM <= 0) {
				throw new IllegalArgumentException("car length must be positive: " + carLengthM);
			}
		}
		this.spine.addAll(spine);
		this.carLengthsM = carLengthsM.clone();
		this.aEndArcM = aEndArcM;
		this.seamArcMs = seamArcMs == null ? new double[0] : seamArcMs.clone();
		this.seamCarIndexes = seamCarIndexes == null ? new int[0] : seamCarIndexes.clone();
		if (this.seamArcMs.length != this.seamCarIndexes.length) {
			throw new IllegalArgumentException("seam arcs and car indexes must be parallel");
		}
		validate();
	}

	/**
	 * C5: seam arc positions (in spine space, like {@link #aEndArcM()}) derived from the per-car
	 * coupler flags. A car's {@code mmtrCouplerAfter} marks a coupler between it and the next car, so
	 * the seam sits at the end of that car.
	 */
	public static double[] seamArcMsFrom(double aEndArcM, double[] carLengthsM, boolean[] couplerAfter) {
		final ObjectArrayList<Double> arcs = new ObjectArrayList<>();
		double arc = aEndArcM;
		for (int i = 0; i < carLengthsM.length; i++) {
			arc += carLengthsM[i];
			if (couplerAfter != null && i < couplerAfter.length && couplerAfter[i] && i < carLengthsM.length - 1) {
				arcs.add(arc);
			}
		}
		final double[] out = new double[arcs.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = arcs.get(i);
		}
		return out;
	}

	/** C5: the car index in front of each seam, parallel to {@link #seamArcMsFrom}. */
	public static int[] seamCarIndexesFrom(double[] carLengthsM, boolean[] couplerAfter) {
		final ObjectArrayList<Integer> indexes = new ObjectArrayList<>();
		for (int i = 0; i < carLengthsM.length - 1; i++) {
			if (couplerAfter != null && i < couplerAfter.length && couplerAfter[i]) {
				indexes.add(i);
			}
		}
		final int[] out = new int[indexes.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = indexes.get(i);
		}
		return out;
	}

	private void validate() {
		final double spineLengthM = spineLengthM();
		if (aEndArcM < -EPSILON_M) {
			throw new IllegalStateException("A end before spine start: " + aEndArcM);
		}
		if (aEndArcM + lengthM() > spineLengthM + EPSILON_M) {
			throw new IllegalStateException("body (" + lengthM() + "m at " + aEndArcM + "m) runs past the spine (" + spineLengthM + "m)");
		}
	}

	public double spineLengthM() {
		double total = 0;
		for (final SpineLeg leg : spine) {
			total += leg.lengthM();
		}
		return total;
	}

	public int legCount() {
		return spine.size();
	}

	public SpineLeg leg(int index) {
		return spine.get(index);
	}

	public int carCount() {
		return carLengthsM.length;
	}

	public double carLengthM(int index) {
		return carLengthsM[index];
	}

	/** Total consist length, m (sum of the car lengths, coupling paddings already folded in). */
	public double lengthM() {
		double total = 0;
		for (final double carLengthM : carLengthsM) {
			total += carLengthM;
		}
		return total;
	}

	/** Arc position of the consist's A end (the car 0 end). */
	public double aEndArcM() {
		return aEndArcM;
	}

	/** Arc position of the consist's B end (the last car's end). */
	public double bEndArcM() {
		return aEndArcM + lengthM();
	}

	/** Arc position of the leading face of car {@code index} (measured from the A end). */
	public double carStartArcM(int index) {
		double arc = aEndArcM;
		for (int i = 0; i < index; i++) {
			arc += carLengthsM[i];
		}
		return arc;
	}

	/** Arc position of the trailing face of car {@code index}. */
	public double carEndArcM(int index) {
		return carStartArcM(index) + carLengthsM[index];
	}

	/** Arc position of the centre of car {@code index} — the value the renderer places the car at. */
	public double carCenterArcM(int index) {
		return carStartArcM(index) + carLengthsM[index] / 2;
	}

	/** C5: number of coupler seams inside this body (0 for a fixed unit). */
	public int seamCount() {
		return seamArcMs.length;
	}

	/** C5: arc position of seam {@code index} from the A end. */
	public double seamArcM(int index) {
		return seamArcMs[index];
	}

	/** C5: car index in front of seam {@code index} (i.e. the cut keeps cars {@code 0..index}). */
	public int carIndexAfterSeam(int index) {
		return seamCarIndexes[index];
	}

	/** All car centres, in consist order (index 0 = A-end car). */
	public double[] carCenterArcMs() {
		final double[] out = new double[carLengthsM.length];
		for (int i = 0; i < out.length; i++) {
			out[i] = carCenterArcM(i);
		}
		return out;
	}

	/**
	 * The rails the body currently stands on, each with the slice it occupies in that rail's own
	 * coordinate. A consist crossing three rails yields three segments (I4).
	 */
	public ObjectArrayList<OccupiedSegment> occupancy() {
		final ObjectArrayList<OccupiedSegment> out = new ObjectArrayList<>();
		final double aEnd = aEndArcM();
		final double bEnd = bEndArcM();
		double legStart = 0;
		for (final SpineLeg leg : spine) {
			final double legEnd = legStart + leg.lengthM();
			final double from = Math.max(aEnd, legStart);
			final double to = Math.min(bEnd, legEnd);
			if (to - from > EPSILON_M) {
				out.add(new OccupiedSegment(leg.railHex(), from - legStart, to - legStart));
			}
			legStart = legEnd;
		}
		return out;
	}

	/** Arc position of the start of spine leg {@code index}. */
	public double legStartArcM(int index) {
		double arc = 0;
		for (int i = 0; i < index && i < spine.size(); i++) {
			arc += spine.get(i).lengthM();
		}
		return arc;
	}

	/**
	 * Index of the spine leg containing {@code arcM}, or {@code -1} when the arc lies outside the
	 * spine. At an exact leg boundary the answer is ambiguous (the arc is the end of one leg and the
	 * start of the next): {@code preferLater} resolves it — true picks the leg that starts there,
	 * false the one that ends there. Callers use this to name the rail a moving end is on.
	 */
	public int legIndexAtArcM(double arcM, boolean preferLater) {
		double legStart = 0;
		for (int i = 0; i < spine.size(); i++) {
			final double legEnd = legStart + spine.get(i).lengthM();
			if (arcM >= legStart - EPSILON_M && arcM <= legEnd + EPSILON_M) {
				if (preferLater && Math.abs(arcM - legEnd) <= EPSILON_M && i + 1 < spine.size()) {
					return i + 1;
				}
				if (!preferLater && Math.abs(arcM - legStart) <= EPSILON_M && i > 0) {
					return i - 1;
				}
				return i;
			}
			legStart = legEnd;
		}
		return -1;
	}

	/** The spine leg containing {@code arcM} (earlier leg wins at a boundary), or {@code null}. */
	public @Nullable SpineLeg legAtArcM(double arcM) {
		final int index = legIndexAtArcM(arcM, false);
		return index < 0 ? null : spine.get(index);
	}

	/** Offset within the leg containing {@code arcM}, in that leg's own coordinate. */
	public double legOffsetM(double arcM) {
		final int index = legIndexAtArcM(arcM, false);
		return index < 0 ? 0 : Math.max(0, Math.min(spine.get(index).lengthM(), arcM - legStartArcM(index)));
	}

	/**
	 * Slide the whole body along the spine by {@code deltaM} (positive = toward the B end). The
	 * relative geometry is untouched — this is what "the train moved" means in the body frame, and
	 * it is direction-free: it moves the body, not "the front".
	 * @return whether the slide fit inside the current spine; {@code false} leaves the body unchanged
	 * and the caller must extend the spine first
	 */
	public boolean slideBy(double deltaM) {
		if (deltaM == 0) {
			return true;
		}
		final double next = aEndArcM + deltaM;
		if (next < -EPSILON_M || next + lengthM() > spineLengthM() + EPSILON_M) {
			return false;
		}
		aEndArcM = Math.max(0, Math.min(next, spineLengthM() - lengthM()));
		return true;
	}

	/** Extend the spine at the B-end side (the train ran onto a new rail ahead of the B end). */
	public void appendLeg(SpineLeg leg) {
		spine.add(leg);
	}

	/** Extend the spine at the A-end side; arc coordinates shift by the new leg's length. */
	public void prependLeg(SpineLeg leg) {
		spine.add(0, leg);
		aEndArcM += leg.lengthM();
	}

	/**
	 * Drop spine legs that no longer touch the body (they are fully behind the A end or fully ahead
	 * of the B end) and correct the arc origin. Keeping the spine trimmed is what keeps a long-running
	 * consist's memory bounded; the rear-clear rule (I5) uses the same "the body no longer overlaps
	 * this leg" condition.
	 * @return number of legs removed
	 */
	public int trimOutsideLegs() {
		int removed = 0;
		while (!spine.isEmpty()) {
			final SpineLeg first = spine.get(0);
			if (first.lengthM() <= aEndArcM + EPSILON_M) {
				aEndArcM -= first.lengthM();
				spine.remove(0);
				removed++;
			} else {
				break;
			}
		}
		while (spine.size() > 1) {
			final SpineLeg last = spine.get(spine.size() - 1);
			final double lastStart = spineLengthM() - last.lengthM();
			if (lastStart >= bEndArcM() - EPSILON_M) {
				spine.remove(spine.size() - 1);
				removed++;
			} else {
				break;
			}
		}
		aEndArcM = Math.max(0, aEndArcM);
		return removed;
	}
}
