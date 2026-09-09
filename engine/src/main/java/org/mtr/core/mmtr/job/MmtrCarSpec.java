package org.mtr.core.mmtr.job;

import org.mtr.core.data.VehicleCar;
import org.mtr.core.mmtr.consist.MmtrUnitCar;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;

/**
 * Rolling-stock spec for one car of a job consist. Kept stable and independent of MTR's
 * VehicleCar so web-authored jobs do not leak engine schema details; the executor maps this
 * onto the engine's VehicleCar when the consist is spawned.
 *
 * <p>C2 adds the two fields that make a formation physically real: {@link #powered} (a hauled
 * wagon contributes mass and brake-pipe volume but no traction) and {@link #consistTypeId}
 * (a per-car override of the consist's default ConsistType). Both default to the previous
 * behaviour — powered, inheriting the consist default — so existing jobs are unchanged.</p>
 *
 * <p>C6 answers the open question "who declares the coupling seams" (design §8 O3): all three
 * authoring paths — the rolling-stock manifest, a consist job and a consist template — go through
 * this spec, so {@link #mmtrCouplerAfter} is the single place a seam is declared. It maps straight
 * onto {@link VehicleCar#getMmtrCouplerAfter()} (default {@code false} = a fixed unit such as an
 * 8-car EMU, no uncoupling inside it).</p>
 */
public final class MmtrCarSpec implements SerializedDataBase {

	/** Built-in MTR train used when an authoring path leaves the model id blank. */
	public static final String DEFAULT_VEHICLE_ID = "m_train";

	public String vehicleId = DEFAULT_VEHICLE_ID;
	public double length;
	public double width;
	public long capacity;
	public double bogie1Position;
	public double bogie2Position;
	public double couplingPadding1;
	public double couplingPadding2;
	public boolean powered = true;
	public String consistTypeId = "";
	/**
	 * Whether a COUPLER sits between this car and the next one — i.e. whether this boundary is a legal
	 * uncoupling seam. {@code false} (default) keeps the previous behaviour: the cars form one fixed
	 * unit (an EMU rake), which cannot be cut. Set it on the last car of each haulable group so
	 * "locomotive + wagons" and 重联 8+8 can be uncoupled at exactly those boundaries.
	 */
	public boolean mmtrCouplerAfter;

	public MmtrCarSpec() {
	}

	public MmtrCarSpec(ReaderBase readerBase) {
		updateData(readerBase);
	}

	@Override
	public void updateData(ReaderBase readerBase) {
		vehicleId = readerBase.getString("vehicleId", "");
		length = readerBase.getDouble("length", 0);
		width = readerBase.getDouble("width", 0);
		capacity = readerBase.getLong("capacity", 0);
		bogie1Position = readerBase.getDouble("bogie1Position", 0);
		bogie2Position = readerBase.getDouble("bogie2Position", 0);
		couplingPadding1 = readerBase.getDouble("couplingPadding1", 0);
		couplingPadding2 = readerBase.getDouble("couplingPadding2", 0);
		powered = readerBase.getBoolean("powered", true);
		consistTypeId = readerBase.getString("consistTypeId", "");
		mmtrCouplerAfter = readerBase.getBoolean("mmtrCouplerAfter", false);
	}

	@Override
	public void serializeData(WriterBase writerBase) {
		writerBase.writeString("vehicleId", vehicleId);
		writerBase.writeDouble("length", length);
		writerBase.writeDouble("width", width);
		writerBase.writeLong("capacity", capacity);
		writerBase.writeDouble("bogie1Position", bogie1Position);
		writerBase.writeDouble("bogie2Position", bogie2Position);
		writerBase.writeDouble("couplingPadding1", couplingPadding1);
		writerBase.writeDouble("couplingPadding2", couplingPadding2);
		writerBase.writeBoolean("powered", powered);
		writerBase.writeString("consistTypeId", consistTypeId);
		writerBase.writeBoolean("mmtrCouplerAfter", mmtrCouplerAfter);
	}

	/** Converts this authoring spec into MTR's runtime car, carrying the MMTR metadata with it. */
	public VehicleCar toVehicleCar() {
		final VehicleCar car = new VehicleCar(vehicleId, length, width, capacity, bogie1Position, bogie2Position, couplingPadding1, couplingPadding2, powered, consistTypeId);
		car.setMmtrCouplerAfter(mmtrCouplerAfter);
		return car;
	}

	/** Converts this authoring spec into the consist core's immutable car (job → consist). */
	public MmtrUnitCar toUnitCar() {
		return new MmtrUnitCar(vehicleId, length, couplingPadding1, couplingPadding2, powered, consistTypeId == null || consistTypeId.isEmpty() ? null : consistTypeId);
	}
}
