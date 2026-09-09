package org.mtr.core.data;

import org.jspecify.annotations.Nullable;
import org.mtr.core.generated.data.VehicleCarSchema;
import org.mtr.core.serializer.ReaderBase;

public final class VehicleCar extends VehicleCarSchema {

	public final boolean hasOneBogie;

	private static final int PASSENGERS_PER_SQUARE_METER = 2;

	/** MTR's car geometry; the MMTR metadata defaults to powered with the consist's default type. */
	public VehicleCar(String vehicleId, double length, double width, long capacity, double bogie1Position, double bogie2Position, double couplingPadding1, double couplingPadding2) {
		this(vehicleId, length, width, capacity, bogie1Position, bogie2Position, couplingPadding1, couplingPadding2, true, "");
	}

	/**
	 * Full constructor: adds the MMTR per-car metadata. {@code mmtrPowered} is what makes a hauled
	 * wagon contribute mass but no traction, and {@code mmtrConsistTypeId} overrides the consist's
	 * default ConsistType for this car only.
	 */
	public VehicleCar(String vehicleId, double length, double width, long capacity, double bogie1Position, double bogie2Position, double couplingPadding1, double couplingPadding2, boolean mmtrPowered, @Nullable String mmtrConsistTypeId) {
		super(vehicleId, length, width, capacity, bogie1Position, bogie2Position, couplingPadding1, couplingPadding2);
		this.mmtrPowered = mmtrPowered;
		this.mmtrConsistTypeId = mmtrConsistTypeId == null ? "" : mmtrConsistTypeId;
		hasOneBogie = this.bogie1Position == this.bogie2Position;
	}

	public VehicleCar(ReaderBase readerBase) {
		super(readerBase);
		hasOneBogie = bogie1Position == bogie2Position;
		updateData(readerBase);
	}

	/** Whether this car can produce traction (hauled wagons cannot). */
	public boolean getMmtrPowered() {
		return mmtrPowered;
	}

	/** Per-car ConsistType override; empty means "use the consist's default type". */
	public String getMmtrConsistTypeId() {
		return mmtrConsistTypeId;
	}

	/**
	 * C4b: whether a COUPLER sits between this car and the next one — i.e. whether this boundary is a
	 * legal uncoupling seam. A fixed unit (a 8-car EMU) has no internal couplers, so it cannot be cut;
	 * a coupled-on rake leaves exactly one seam behind (the joint the surgery created).
	 */
	public boolean getMmtrCouplerAfter() {
		return mmtrCouplerAfter;
	}

	/** Marks (or clears) the coupler seam after this car. */
	public void setMmtrCouplerAfter(boolean value) {
		mmtrCouplerAfter = value;
	}

	/**
	 * C8: whether this car's couplers are AUTOMATIC (动车组/调机的自动车钩). A train that has drawn up
	 * to a standing rake under a 调车授权 and stopped inside coupler reach latches on by itself when
	 * both facing cars are automatic; a manual coupler (货车螺旋车钩) still needs the crew to confirm.
	 */
	public boolean getMmtrAutoCoupler() {
		return mmtrAutoCoupler;
	}

	/** Declares (or clears) this car's automatic couplers. */
	public void setMmtrAutoCoupler(boolean value) {
		mmtrAutoCoupler = value;
	}

	public String getVehicleId() {
		return vehicleId;
	}

	public double getLength() {
		return length;
	}

	public double getWidth() {
		return width;
	}

	public long getCapacity() {
		return capacity > 0 ? capacity : Math.round(length * width * PASSENGERS_PER_SQUARE_METER);
	}

	public double getBogie1Position() {
		return bogie1Position;
	}

	public double getBogie2Position() {
		return bogie2Position;
	}

	public double getTotalLength(boolean firstCar, boolean lastCar) {
		return getCouplingPadding1(firstCar) + length + getCouplingPadding2(lastCar);
	}

	/** Raw coupling padding at the A end (no first/last-car adjustment). */
	public double getCouplingPadding1() {
		return couplingPadding1;
	}

	/** Raw coupling padding at the B end (no first/last-car adjustment). */
	public double getCouplingPadding2() {
		return couplingPadding2;
	}

	double getCouplingPadding1(boolean firstCar) {
		return firstCar ? 0 : couplingPadding1;
	}

	double getCouplingPadding2(boolean lastCar) {
		return lastCar ? 0 : couplingPadding2;
	}
}
