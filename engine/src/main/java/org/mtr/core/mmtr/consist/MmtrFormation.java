package org.mtr.core.mmtr.consist;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

/**
 * C1: a formation ("编组") — an ordered chain of {@link MmtrUnit}s joined at their couplers.
 *
 * <p>This is the semantic model the coupling design is built on: the chain order <em>is</em> the
 * connection state. A coupler is connected exactly when it is not one of the formation's two free
 * ends, so "one coupler has at most one partner" (U3) cannot be violated, and cuts are only ever
 * offered at {@link #seamCount()} seams between neighbouring units — never inside a unit (U1/U6).</p>
 *
 * <p>Lengths follow MTR's own convention ({@code VehicleCar.getTotalLength}): the outward-facing
 * coupling paddings of the whole formation's first and last car are not counted. Coupling two
 * formations therefore adds {@code head.padding2 + tail.padding1} at the seam, which is what
 * {@link #couplingPaddingM(MmtrFormation, MmtrFormation)} reports and invariant U2 pins:
 * {@code couple(a, b).lengthM() == a.lengthM() + b.lengthM() + couplingPaddingM(a, b)}.</p>
 *
 * <p>Instances are immutable; {@link #couple(MmtrFormation)} and {@link #splitAfterSeam(int)}
 * return new formations. Only same-direction joins are supported (head's B end onto tail's A end);
 * reversing a unit is out of scope for this slice (design O1).</p>
 */
public final class MmtrFormation {

	private final ObjectArrayList<MmtrUnit> units;

	public MmtrFormation(ObjectArrayList<MmtrUnit> units) {
		if (units == null || units.isEmpty()) {
			throw new IllegalArgumentException("a formation needs at least one unit");
		}
		final ObjectOpenHashSet<String> seenUnitIds = new ObjectOpenHashSet<>();
		for (final MmtrUnit unit : units) {
			if (unit == null) {
				throw new IllegalArgumentException("formation must not contain a null unit");
			}
			if (!seenUnitIds.add(unit.unitId())) {
				throw new IllegalArgumentException("duplicate unit id in formation: " + unit.unitId());
			}
		}
		this.units = new ObjectArrayList<>(units);
	}

	public static MmtrFormation of(MmtrUnit... units) {
		final ObjectArrayList<MmtrUnit> list = new ObjectArrayList<>();
		for (final MmtrUnit unit : units) {
			list.add(unit);
		}
		return new MmtrFormation(list);
	}

	/**
	 * The coupling padding that becomes internal (and therefore counted) when {@code head}'s B end is
	 * coupled onto {@code tail}'s A end — invariant U2's correction term.
	 */
	public static double couplingPaddingM(MmtrFormation head, MmtrFormation tail) {
		if (head == null || tail == null) {
			throw new IllegalArgumentException("both formations are needed");
		}
		return head.lastUnit().lastCar().couplingPadding2M() + tail.firstUnit().firstCar().couplingPadding1M();
	}

	public int unitCount() {
		return units.size();
	}

	public MmtrUnit unit(int index) {
		return units.get(index);
	}

	public MmtrUnit firstUnit() {
		return units.get(0);
	}

	public MmtrUnit lastUnit() {
		return units.get(units.size() - 1);
	}

	public ObjectArrayList<MmtrUnit> units() {
		return new ObjectArrayList<>(units);
	}

	public int carCount() {
		int total = 0;
		for (final MmtrUnit unit : units) {
			total += unit.carCount();
		}
		return total;
	}

	/** All cars of the formation, flattened in chain order (A end first). */
	public ObjectArrayList<MmtrUnitCar> cars() {
		final ObjectArrayList<MmtrUnitCar> flat = new ObjectArrayList<>(carCount());
		for (final MmtrUnit unit : units) {
			flat.addAll(unit.cars());
		}
		return flat;
	}

	/**
	 * Per-car lengths of the whole formation in chain order, MTR convention: the outward paddings of
	 * the formation's own first and last car are not counted (paddings at internal seams are).
	 */
	public double[] carLengthsM() {
		final ObjectArrayList<MmtrUnitCar> flat = cars();
		final double[] out = new double[flat.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = flat.get(i).totalLengthM(i == 0, i == out.length - 1);
		}
		return out;
	}

	public double lengthM() {
		double total = 0;
		for (final double carLengthM : carLengthsM()) {
			total += carLengthM;
		}
		return total;
	}

	/** Number of couplers that join two units; a single-unit formation has none. */
	public int seamCount() {
		return units.size() - 1;
	}

	/** The coupler at the head side of seam {@code seamIndex} (the B end of unit {@code seamIndex}). */
	public MmtrCoupler seamHeadCoupler(int seamIndex) {
		requireSeam(seamIndex);
		return units.get(seamIndex).couplerB();
	}

	/** The coupler at the tail side of seam {@code seamIndex} (the A end of unit {@code seamIndex + 1}). */
	public MmtrCoupler seamTailCoupler(int seamIndex) {
		requireSeam(seamIndex);
		return units.get(seamIndex + 1).couplerA();
	}

	/** How many cars sit in front of seam {@code seamIndex} (i.e. the seam's car index). */
	public int carIndexAfterSeam(int seamIndex) {
		requireSeam(seamIndex);
		int cars = 0;
		for (int i = 0; i <= seamIndex; i++) {
			cars += units.get(i).carCount();
		}
		return cars;
	}

	/** Arc position of seam {@code seamIndex} from the formation's A end, in metres. */
	public double seamArcM(int seamIndex) {
		requireSeam(seamIndex);
		final double[] carLengthsM = carLengthsM();
		final int carsBefore = carIndexAfterSeam(seamIndex);
		double arc = 0;
		for (int i = 0; i < carsBefore; i++) {
			arc += carLengthsM[i];
		}
		return arc;
	}

	/**
	 * The coupling padding that becomes internal (and therefore counted) when this formation's B end
	 * is coupled to another formation's A end: the head car's outward padding 2 plus the tail car's
	 * outward padding 1.
	 */
	public double seamPaddingM(int seamIndex) {
		requireSeam(seamIndex);
		return units.get(seamIndex).lastCar().couplingPadding2M() + units.get(seamIndex + 1).firstCar().couplingPadding1M();
	}

	/** Whether {@code coupler} is one of this formation's two free ends. */
	public boolean isCouplerFree(MmtrCoupler coupler) {
		if (coupler == null) {
			return false;
		}
		return coupler.equals(firstUnit().couplerA()) || coupler.equals(lastUnit().couplerB());
	}

	/**
	 * Couples {@code tail} onto this formation: this formation's B end onto {@code tail}'s A end.
	 * Both operands are unchanged; the merged formation is returned.
	 */
	public MmtrFormation couple(MmtrFormation tail) {
		if (tail == null) {
			throw new IllegalArgumentException("tail formation must not be null");
		}
		final ObjectArrayList<MmtrUnit> merged = new ObjectArrayList<>(units.size() + tail.units.size());
		merged.addAll(units);
		merged.addAll(tail.units);
		return new MmtrFormation(merged);
	}

	/**
	 * Cuts this formation at seam {@code seamIndex}: the head keeps units {@code 0..seamIndex} and
	 * the returned tail holds the rest. Both are new formations; the couplers at the cut become free
	 * ends again.
	 */
	public ObjectObjectImmutablePair<MmtrFormation, MmtrFormation> splitAfterSeam(int seamIndex) {
		requireSeam(seamIndex);
		final ObjectArrayList<MmtrUnit> head = new ObjectArrayList<>(seamIndex + 1);
		final ObjectArrayList<MmtrUnit> tail = new ObjectArrayList<>(units.size() - seamIndex - 1);
		for (int i = 0; i < units.size(); i++) {
			(i <= seamIndex ? head : tail).add(units.get(i));
		}
		return new ObjectObjectImmutablePair<>(new MmtrFormation(head), new MmtrFormation(tail));
	}

	private void requireSeam(int seamIndex) {
		if (seamIndex < 0 || seamIndex >= seamCount()) {
			throw new IllegalArgumentException("seam " + seamIndex + " out of range (0.." + (seamCount() - 1) + ")");
		}
	}
}
