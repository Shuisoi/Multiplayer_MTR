package org.mtr.core.mmtr.job;

import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;

/**
 * Rolling-stock spec for one car of a job consist. Kept stable and independent of MTR's
 * VehicleCar so web-authored jobs do not leak engine schema details; the executor maps this
 * onto the engine's VehicleCar when the consist is spawned.
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
	}
}
