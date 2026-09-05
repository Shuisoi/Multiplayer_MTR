package org.mtr.core.operation;

import org.mtr.core.data.Vehicle;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.core.simulation.Simulator;

/**
 * MMTR explicit drive command: a driver sends separated throttle/brake notches (plus optional
 * HID axes and reverser) straight to one vehicle. This is the replacement for the legacy
 * single-handle mapping and is only applied when the vehicle has explicit control enabled.
 */
public final class MmtrDriveControl implements SerializedDataBase {

	private long vehicleId;
	private int throttleNotch;
	private int brakeNotch;
	private int reverser;
	private double throttleAxis;
	private double brakeAxis;
	private boolean emergency;

	public MmtrDriveControl(long vehicleId, ControlState state) {
		this.vehicleId = vehicleId;
		this.throttleNotch = state.getThrottleNotch();
		this.brakeNotch = state.getBrakeNotch();
		this.reverser = state.getReverser();
		this.throttleAxis = state.getThrottleAxis();
		this.brakeAxis = state.getBrakeAxis();
		this.emergency = state.isEmergency();
	}

	public MmtrDriveControl(ReaderBase readerBase) {
		updateData(readerBase);
	}

	@Override
	public void updateData(ReaderBase readerBase) {
		vehicleId = readerBase.getLong("vehicleId", 0);
		throttleNotch = readerBase.getInt("throttleNotch", 0);
		brakeNotch = readerBase.getInt("brakeNotch", 0);
		reverser = readerBase.getInt("reverser", 0);
		throttleAxis = readerBase.getDouble("throttleAxis", 0);
		brakeAxis = readerBase.getDouble("brakeAxis", 0);
		emergency = readerBase.getBoolean("emergency", false);
	}

	public void apply(Simulator simulator) {
		final ControlState state = new ControlState()
			.setThrottleNotch(throttleNotch).setBrakeNotch(brakeNotch).setReverser(reverser)
			.setThrottleAxis(throttleAxis).setBrakeAxis(brakeAxis).setEmergency(emergency);
		simulator.sidings.forEach(siding -> siding.iterateVehicles(vehicle -> {
			if (vehicle.getId() == vehicleId) {
				vehicle.applyMmtrControl(state);
			}
		}));
	}

	@Override
	public void serializeData(WriterBase writerBase) {
		writerBase.writeLong("vehicleId", vehicleId);
		writerBase.writeInt("throttleNotch", throttleNotch);
		writerBase.writeInt("brakeNotch", brakeNotch);
		writerBase.writeInt("reverser", reverser);
		writerBase.writeDouble("throttleAxis", throttleAxis);
		writerBase.writeDouble("brakeAxis", brakeAxis);
		writerBase.writeBoolean("emergency", emergency);
	}
}