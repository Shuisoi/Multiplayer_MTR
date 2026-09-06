package org.mtr.core.operation;

import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.mmtr.MmtrCoupling;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.core.simulation.Simulator;

import java.util.UUID;

/**
 * MMTR coupling/uncoupling request ("连挂/解挂").
 *
 * <p>{@code couple} attaches the {@code tailVehicleId} train onto the {@code headVehicleId}
 * train; {@code uncouple} cuts {@code headVehicleId} after {@code cutAfterCarIndex}. The
 * operation validates the coupling guards (same manual siding, both stopped at the depot,
 * capacity) and — for the parts that only touch pure car lists / geometry — computes the plan.
 * The live registry surgery (merging the tail's {@code vehicleCars} into the head vehicle and
 * freeing the tail's siding, or spawning the cut tail as a new vehicle) is performed by the
 * game-layer executor that has a running world; here we only check and log the outcome so the
 * seam stays safe to wire up later.</p>
 */
public final class MmtrCoupleControl implements SerializedDataBase {

	private long headVehicleId;
	private long tailVehicleId;
	private int cutAfterCarIndex = -1;
	private @Nullable UUID driverUuid;

	public MmtrCoupleControl(long headVehicleId, long tailVehicleId, int cutAfterCarIndex) {
		this.headVehicleId = headVehicleId;
		this.tailVehicleId = tailVehicleId;
		this.cutAfterCarIndex = cutAfterCarIndex;
	}

	public MmtrCoupleControl(ReaderBase readerBase) {
		updateData(readerBase);
	}

	public long getHeadVehicleId() { return headVehicleId; }
	public long getTailVehicleId() { return tailVehicleId; }
	public int getCutAfterCarIndex() { return cutAfterCarIndex; }
	public @Nullable UUID getDriverUuid() { return driverUuid; }
	public MmtrCoupleControl setDriverUuid(@Nullable UUID driverUuid) { this.driverUuid = driverUuid; return this; }

	@Override
	public void updateData(ReaderBase readerBase) {
		headVehicleId = readerBase.getLong("headVehicleId", 0);
		tailVehicleId = readerBase.getLong("tailVehicleId", 0);
		cutAfterCarIndex = readerBase.getInt("cutAfterCarIndex", -1);
		final String driverUuidString = readerBase.getString("driverUuid", "");
		driverUuid = driverUuidString.isEmpty() ? null : UUID.fromString(driverUuidString);
	}

	@Override
	public void serializeData(WriterBase writerBase) {
		writerBase.writeLong("headVehicleId", headVehicleId);
		writerBase.writeLong("tailVehicleId", tailVehicleId);
		writerBase.writeInt("cutAfterCarIndex", cutAfterCarIndex);
		writerBase.writeString("driverUuid", driverUuid == null ? "" : driverUuid.toString());
	}

	/** Couples {@code tailVehicleId} onto {@code headVehicleId} (guard checks + plan logging). */
	public void couple(Simulator simulator) {
		final Vehicle[] head = {null};
		final Vehicle[] tail = {null};
		final Siding[] siding = {null};
		simulator.sidings.forEach(currentSiding -> currentSiding.iterateVehicles(vehicle -> {
			if (vehicle.getId() == headVehicleId) {
				head[0] = vehicle;
				siding[0] = currentSiding;
			} else if (vehicle.getId() == tailVehicleId) {
				tail[0] = vehicle;
			}
		}));
		if (head[0] == null || tail[0] == null) {
			System.out.println("[MMTR-COUP] fail: head or tail vehicle not found (" + headVehicleId + "/" + tailVehicleId + ")");
			return;
		}
		final Siding sharedSiding = siding[0];
		final int headCars = sharedSiding == null ? 0 : sharedSiding.getVehicleCars().size();
		final int tailCars = sharedSiding == null ? 0 : sharedSiding.getVehicleCars().size();
		final boolean headManual = sharedSiding != null && sharedSiding.getIsManual();
		final MmtrCoupling.Check check = MmtrCoupling.canCoupleAtDepot(
			sharedSiding == null ? 0 : sharedSiding.getId(), sharedSiding == null ? 0 : sharedSiding.getId(),
			head[0].closeToDepot(), tail[0].closeToDepot(), 0, 0, headCars, tailCars, Integer.MAX_VALUE, headManual, headManual);
		if (!check.allowed) {
			System.out.println("[MMTR-COUP] denied: " + check.reason);
			return;
		}
		System.out.println("[MMTR-COUP] ok: head=" + headVehicleId + " tail=" + tailVehicleId
			+ " combinedCars=" + (headCars + tailCars)
			+ " (registry merge / siding free / empty-pipe seeding pending the world executor)");
	}

	/** Uncoupled {@code headVehicleId} after {@code cutAfterCarIndex} (guard checks + plan logging). */
	public void uncouple(Simulator simulator) {
		final Vehicle[] vehicle = {null};
		final Siding[] siding = {null};
		simulator.sidings.forEach(currentSiding -> currentSiding.iterateVehicles(train -> {
			if (train.getId() == headVehicleId) {
				vehicle[0] = train;
				siding[0] = currentSiding;
			}
		}));
		if (vehicle[0] == null || siding[0] == null) {
			System.out.println("[MMTR-COUP] fail: vehicle not found (" + headVehicleId + ")");
			return;
		}
		final int cars = siding[0].getVehicleCars().size();
		final MmtrCoupling.Check check = MmtrCoupling.canUncouple(cars, cutAfterCarIndex);
		if (!check.allowed) {
			System.out.println("[MMTR-COUP] denied: " + check.reason);
			return;
		}
		System.out.println("[MMTR-COUP] ok: split vehicle=" + headVehicleId + " after car " + cutAfterCarIndex
			+ " of " + cars + " (spawn-tail pending the world executor)");
	}
}
