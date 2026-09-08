package org.mtr.core.operation;

import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Vehicle;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.core.simulation.Simulator;

import java.util.UUID;

/**
 * MMTR explicit drive command: a driver sends separated throttle/brake notches (plus optional
 * HID axes and reverser) straight to one vehicle. This is the replacement for the legacy
 * single-handle mapping and is only applied when the vehicle has explicit control enabled.
 *
 * <p>Carries the sender's identity ({@code driverUuid}); the engine only honours commands from
 * the player currently occupying a cab driver seat of that vehicle (occupation lock).</p>
 */
public final class MmtrDriveControl implements SerializedDataBase {

	private long vehicleId;
	private int throttleNotch;
	private int brakeNotch;
	private int reverser;
	private double throttleAxis;
	private double brakeAxis;
	private boolean emergency;
	private boolean acknowledge;
	private @Nullable UUID driverUuid;

	public MmtrDriveControl(long vehicleId, ControlState state) {
		this(vehicleId, state, null);
	}

	public MmtrDriveControl(long vehicleId, ControlState state, @Nullable UUID driverUuid) {
		this.vehicleId = vehicleId;
		this.throttleNotch = state.getThrottleNotch();
		this.brakeNotch = state.getBrakeNotch();
		this.reverser = state.getReverser();
		this.throttleAxis = state.getThrottleAxis();
		this.brakeAxis = state.getBrakeAxis();
		this.emergency = state.isEmergency();
		this.acknowledge = state.isAcknowledge();
		this.driverUuid = driverUuid;
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
		acknowledge = readerBase.getBoolean("acknowledge", false);
		final String driverUuidString = readerBase.getString("driverUuid", "");
		driverUuid = driverUuidString.isEmpty() ? null : UUID.fromString(driverUuidString);
	}

	public void apply(Simulator simulator) {
		final ControlState state = new ControlState()
			.setThrottleNotch(throttleNotch).setBrakeNotch(brakeNotch).setReverser(reverser)
			.setThrottleAxis(throttleAxis).setBrakeAxis(brakeAxis).setEmergency(emergency).setAcknowledge(acknowledge);
		simulator.sidings.forEach(siding -> siding.iterateVehicles(vehicle -> {
			if (vehicle.getId() == vehicleId && vehicle.canTakeMmtrControl(driverUuid)) {
				vehicle.applyMmtrControl(state, driverUuid);
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
		writerBase.writeBoolean("acknowledge", acknowledge);
		writerBase.writeString("driverUuid", driverUuid == null ? "" : driverUuid.toString());
	}
}