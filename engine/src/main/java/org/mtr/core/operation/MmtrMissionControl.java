package org.mtr.core.operation;

import org.jspecify.annotations.Nullable;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.core.simulation.Simulator;

import java.util.UUID;

/**
 * MMTR dispatch entry point: assign a mission to one train (consist) and start it, driven
 * headlessly when the executor is AUTOPILOT. The mission is owned by the train; players/AI are
 * only optional executors that read it later. Target siding id 0 means "run to the end of the
 * current path" (terminal / freight destination).
 */
public final class MmtrMissionControl implements SerializedDataBase {

	private long vehicleId;
	private String kind = "PASSENGER";
	private long targetSidingId;
	private String executor = "AUTOPILOT";
	private @Nullable String executorPlayerUuid;
	private boolean startNow = true;

	public MmtrMissionControl(ReaderBase readerBase) {
		updateData(readerBase);
	}

	@Override
	public void updateData(ReaderBase readerBase) {
		// Ids travel as strings on the web wire (64-bit longs exceed JS safe integers); the
		// in-process op still sends numbers. Accept both.
		vehicleId = parseId(readerBase, "vehicleId");
		kind = readerBase.getString("kind", "PASSENGER");
		targetSidingId = parseId(readerBase, "targetSidingId");
		executor = readerBase.getString("executor", "AUTOPILOT");
		executorPlayerUuid = readerBase.getString("executorPlayerUuid", "");
		startNow = readerBase.getBoolean("startNow", true);
		if (executorPlayerUuid != null && executorPlayerUuid.isEmpty()) {
			executorPlayerUuid = null;
		}
	}

	/**
	 * @return whether a mission was attached (false when the vehicle is missing or already has
	 * an active mission).
	 */
	public boolean dispatch(Simulator simulator) {
		final MmtrMission.Kind parsedKind;
		try {
			parsedKind = MmtrMission.Kind.valueOf(kind);
		} catch (IllegalArgumentException e) {
			return false;
		}
		final MmtrMission.Executor parsedExecutor;
		try {
			parsedExecutor = MmtrMission.Executor.valueOf(executor);
		} catch (IllegalArgumentException e) {
			return false;
		}
		final UUID playerUuid = executorPlayerUuid == null ? null : UUID.fromString(executorPlayerUuid);

		final boolean[] dispatched = {false};
		simulator.sidings.forEach(siding -> siding.iterateVehicles(vehicle -> {
			if (vehicle.getId() != vehicleId || dispatched[0]) {
				return;
			}
			final MmtrMission mission = new MmtrMission(vehicle.getId(), parsedKind, siding.getId(), targetSidingId, System.currentTimeMillis());
			mission.setExecutor(parsedExecutor, playerUuid);
			if (!vehicle.setMmtrMission(mission)) {
				return;
			}
			if (parsedExecutor == MmtrMission.Executor.AUTOPILOT && startNow) {
				// Headless mission drive: engage the manual seam so the train actually moves.
				vehicle.engageMissionAutopilot();
			}
			dispatched[0] = true;
		}));
		return dispatched[0];
	}

	private static long parseId(ReaderBase readerBase, String key) {
		final String raw = readerBase.getString(key, "");
		if (!raw.isEmpty()) {
			try {
				return Long.parseLong(raw.trim());
			} catch (NumberFormatException ignored) {
				// Fall through to the numeric read below.
			}
		}
		return readerBase.getLong(key, 0);
	}

	@Override
	public void serializeData(WriterBase writerBase) {
		writerBase.writeString("vehicleId", String.valueOf(vehicleId));
		writerBase.writeString("kind", kind);
		writerBase.writeString("targetSidingId", String.valueOf(targetSidingId));
		writerBase.writeString("executor", executor);
		writerBase.writeString("executorPlayerUuid", executorPlayerUuid == null ? "" : executorPlayerUuid);
		writerBase.writeBoolean("startNow", startNow);
	}
}