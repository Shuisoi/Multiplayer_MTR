package org.mtr.core.operation;

import org.mtr.core.data.Data;
import org.mtr.core.data.Lift;
import org.mtr.core.data.Passenger;
import org.mtr.core.data.Rail;
import org.mtr.core.generated.operation.DynamicDataResponseSchema;
import org.mtr.core.serializer.ReaderBase;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

public final class DynamicDataResponse extends DynamicDataResponseSchema {

	public final UUID uuid;
	private final Data data;

	public DynamicDataResponse(UUID uuid, Data data) {
		super(uuid.toString());
		this.uuid = uuid;
		this.data = data;
	}

	public DynamicDataResponse(ReaderBase readerBase, Data data) {
		super(readerBase);
		this.data = data;
		updateData(readerBase);
		uuid = UUID.fromString(clientId);
	}

	@Override
	protected Data liftsToUpdateDataParameter() {
		return data;
	}

	public void iterateVehiclesToUpdate(Consumer<VehicleUpdate> consumer) {
		vehiclesToUpdate.forEach(consumer);
	}

	public void iterateVehiclesToKeep(LongConsumer consumer) {
		vehiclesToKeep.forEach(consumer);
	}

	/** 这一拍要**原地合并**的稀疏补丁（notes/173）：只有变化的字段，客户端用 updateData 合并。 */
	public void iterateVehiclesToPatch(Consumer<VehiclePatch> consumer) {
		vehiclesToPatch.forEach(consumer);
	}

	public void iterateLiftsToUpdate(Consumer<Lift> consumer) {
		liftsToUpdate.forEach(consumer);
	}

	public void iterateLiftsToKeep(LongConsumer consumer) {
		liftsToKeep.forEach(consumer);
	}

	public void iteratePassengersToUpdate(Consumer<Passenger> consumer) {
		passengersToUpdate.forEach(consumer);
	}

	public void iteratePassengersToKeep(LongConsumer consumer) {
		passengersToKeep.forEach(consumer);
	}

	public void iterateSignalBlockUpdates(Consumer<SignalBlockUpdate> consumer) {
		signalBlockUpdates.forEach(consumer);
	}

	public void addVehicleToUpdate(VehicleUpdate vehicleUpdate) {
		vehiclesToUpdate.add(vehicleUpdate);
	}

	public void addVehicleToKeep(long vehicleId) {
		vehiclesToKeep.add(vehicleId);
	}

	/** 补丁走它自己的通道（{@code vehiclesToPatch}）：客户端那边是原地合并，不是重建镜像。 */
	public void addVehicleToPatch(long vehicleId, String patchJson) {
		vehiclesToPatch.add(new VehiclePatch(vehicleId, patchJson));
	}

	public void addLiftToUpdate(Lift lift) {
		liftsToUpdate.add(lift);
	}

	public void addLiftToKeep(long liftId) {
		liftsToKeep.add(liftId);
	}

	public void addPassengerToUpdate(Passenger passenger) {
		passengersToUpdate.add(passenger);
	}

	public void addPassengerToKeep(long passengerId) {
		passengersToKeep.add(passengerId);
	}

	public void addSignalBlockUpdate(Rail rail) {
		signalBlockUpdates.add(new SignalBlockUpdate(rail));
	}
}
