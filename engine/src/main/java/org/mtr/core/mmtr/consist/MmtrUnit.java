package org.mtr.core.mmtr.consist;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

/**
 * C1: a coupling unit ("连挂单元") — the indivisible building block of a formation.
 *
 * <p>A unit owns an ordered car list that is never changed by coupling or uncoupling, plus one
 * {@link MmtrCoupler} at each end. Its {@link UnitClass} only records how the unit came to be
 * (and is what the rolling-stock manifest/editor will pick); the coupling rules themselves need no
 * class check, because "an 8-car EMU cannot be cut in the middle" is already true structurally —
 * its eight cars live in one unit, so there is no seam to cut at (invariants U1/U6).</p>
 *
 * <p>Car order is the unit's own order: index 0 is the car next to coupler {@code A}, the last car
 * is next to coupler {@code B}. {@link #lengthM()} measures the unit standing alone (its outward
 * paddings not counted); a formation recomputes lengths over its whole car list, which is why a
 * coupling seam gains {@code head.padding2 + tail.padding1} of length.</p>
 */
public final class MmtrUnit {

	public enum UnitClass {
		/** A fixed multiple unit: several cars that are never split (an 8-car high-speed set). */
		EMU,
		/** A single locomotive. */
		LOCO,
		/** A single unpowered wagon (a rake is several of these coupled together). */
		WAGON
	}

	private final String unitId;
	private final UnitClass unitClass;
	private final ObjectArrayList<MmtrUnitCar> cars;
	private final MmtrCoupler couplerA;
	private final MmtrCoupler couplerB;

	/** A unit with default couplers on both ends (haulage, charged pipe). */
	public MmtrUnit(String unitId, UnitClass unitClass, ObjectArrayList<MmtrUnitCar> cars) {
		this(unitId, unitClass, cars, MmtrCoupler.Type.HAULED, true, MmtrCoupler.Type.HAULED, true);
	}

	public MmtrUnit(
		String unitId,
		UnitClass unitClass,
		ObjectArrayList<MmtrUnitCar> cars,
		MmtrCoupler.Type couplerAType,
		boolean couplerAAirCharged,
		MmtrCoupler.Type couplerBType,
		boolean couplerBAirCharged
	) {
		if (unitId == null || unitId.isEmpty()) {
			throw new IllegalArgumentException("unit needs an id");
		}
		if (unitClass == null) {
			throw new IllegalArgumentException("unit needs a class");
		}
		if (cars == null || cars.isEmpty()) {
			throw new IllegalArgumentException("unit " + unitId + " needs at least one car");
		}
		this.unitId = unitId;
		this.unitClass = unitClass;
		this.cars = new ObjectArrayList<>(cars);
		couplerA = new MmtrCoupler(unitId, MmtrCoupler.End.A, couplerAType, couplerAAirCharged);
		couplerB = new MmtrCoupler(unitId, MmtrCoupler.End.B, couplerBType, couplerBAirCharged);
	}

	public String unitId() {
		return unitId;
	}

	public UnitClass unitClass() {
		return unitClass;
	}

	public int carCount() {
		return cars.size();
	}

	public MmtrUnitCar car(int index) {
		return cars.get(index);
	}

	/** A copy of the car list, in unit order (index 0 next to coupler A). */
	public ObjectArrayList<MmtrUnitCar> cars() {
		return new ObjectArrayList<>(cars);
	}

	public MmtrUnitCar firstCar() {
		return cars.get(0);
	}

	public MmtrUnitCar lastCar() {
		return cars.get(cars.size() - 1);
	}

	public MmtrCoupler couplerA() {
		return couplerA;
	}

	public MmtrCoupler couplerB() {
		return couplerB;
	}

	/** Whether any car of this unit can produce traction (drives invariant U4's physics later). */
	public boolean hasPoweredCar() {
		for (final MmtrUnitCar car : cars) {
			if (car.powered()) {
				return true;
			}
		}
		return false;
	}

	/** Length of this unit standing alone (its own outward coupling paddings are not counted). */
	public double lengthM() {
		double total = 0;
		for (int i = 0; i < cars.size(); i++) {
			total += cars.get(i).totalLengthM(i == 0, i == cars.size() - 1);
		}
		return total;
	}

	/** Per-car lengths of this unit standing alone, in unit order. */
	public double[] carLengthsM() {
		final double[] out = new double[cars.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = cars.get(i).totalLengthM(i == 0, i == out.length - 1);
		}
		return out;
	}
}
