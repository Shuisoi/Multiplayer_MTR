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

	double getCouplingPadding1(boolean firstCar) {
		return firstCar ? 0 : couplingPadding1;
	}

	double getCouplingPadding2(boolean lastCar) {
		return lastCar ? 0 : couplingPadding2;
	}
}
