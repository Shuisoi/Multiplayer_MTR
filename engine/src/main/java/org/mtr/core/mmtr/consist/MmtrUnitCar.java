package org.mtr.core.mmtr.consist;

import org.jspecify.annotations.Nullable;

/**
 * C1: one car inside a {@link MmtrUnit}.
 *
 * <p>Kept in the consist package (not reusing the job layer's {@code MmtrCarSpec}) so the consist
 * core stays independent of the web-authoring DTO: the dependency points job → consist, never the
 * other way. A later slice can add a {@code MmtrCarSpec} → {@code MmtrUnitCar} adapter.</p>
 *
 * <p>Length convention is MTR's own ({@code VehicleCar.getTotalLength}): a car's total length is
 * {@code couplingPadding1 + length + couplingPadding2}, but the outward-facing padding of the first
 * and last car of a <em>formation</em> is not counted (see {@link #totalLengthM(boolean, boolean)}).
 * That is what makes a coupling seam gain {@code head.padding2 + tail.padding1} of length.</p>
 */
public record MmtrUnitCar(String vehicleId, double lengthM, double couplingPadding1M, double couplingPadding2M, boolean powered, @Nullable String consistTypeId) {

	public MmtrUnitCar {
		if (vehicleId == null || vehicleId.isEmpty()) {
			throw new IllegalArgumentException("car needs a vehicle id");
		}
		if (!(lengthM > 0)) {
			throw new IllegalArgumentException("car length must be positive: " + lengthM);
		}
		if (couplingPadding1M < 0 || couplingPadding2M < 0) {
			throw new IllegalArgumentException("coupling padding must not be negative: " + couplingPadding1M + "/" + couplingPadding2M);
		}
	}

	/**
	 * Total length of this car as part of a car list, MTR convention: the coupling paddings that
	 * face out of the list are not counted.
	 *
	 * @param firstCar whether this is the first car of the list (its outward padding 1 is dropped)
	 * @param lastCar  whether this is the last car of the list (its outward padding 2 is dropped)
	 */
	public double totalLengthM(boolean firstCar, boolean lastCar) {
		return (firstCar ? 0 : couplingPadding1M) + lengthM + (lastCar ? 0 : couplingPadding2M);
	}

	/** Convenience factory for a car with no coupling padding, unpowered and without a consist type. */
	public static MmtrUnitCar of(String vehicleId, double lengthM) {
		return new MmtrUnitCar(vehicleId, lengthM, 0, 0, false, null);
	}
}
