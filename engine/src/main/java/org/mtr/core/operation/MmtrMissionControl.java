package org.mtr.core.operation;

import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Rail;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.mmtr.MmtrRunPlanner;
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
			final boolean motionVehicle = vehicle.isMmtrMotion();
			// Motion-mode vehicles run their mission through the live Motion Core: resolve the target
			// rail (platform/siding id) and plan the run BEFORE attaching; without a feasible plan the
			// dispatch is refused.
			MmtrRunPlanner.Plan motionPlan = null;
			if (motionVehicle && parsedExecutor == MmtrMission.Executor.AUTOPILOT) {
				final Rail targetRail = findTargetRail(simulator, targetSidingId);
				if (targetRail == null || vehicle.getMmtrMotionWalker() != null && targetRail.getHexId().equals(vehicle.getMmtrMotionWalker().railHex())) {
					return; // unresolvable target / already on it -> refuse
				}
				motionPlan = MmtrRunPlanner.planToRail(simulator, vehicle, targetRail.getHexId(), 1.0);
				if (!motionPlan.feasible) {
					return; // route planning failed -> refuse
				}
			}
			final MmtrMission mission = new MmtrMission(vehicle.getId(), parsedKind, siding.getId(), targetSidingId, System.currentTimeMillis());
			mission.setExecutor(parsedExecutor, playerUuid);
			if (!vehicle.setMmtrMission(mission)) {
				return;
			}
			if (parsedExecutor == MmtrMission.Executor.AUTOPILOT && startNow) {
				if (motionVehicle && motionPlan != null) {
					// Headless motion mission: request every en-route turnout through the P3 point
					// authority under this vehicle (approach locking - never a store preset), then arm the
					// auto step-run to the planned stop (doors for passenger service). When a fork is
					// operator-locked or held by another train the vehicle is not armed yet: its own
					// mission self-arm retries the grants each tick and starts the run the moment all
					// forks are granted (nothing auto-elects around a busy point).
					if (vehicle.armMmtrPointRun(simulator, motionPlan)) {
						vehicle.setMmtrMotionAuto(true);
						vehicle.setMmtrMotionStopTarget(motionPlan.stopCumulativeM, parsedKind == MmtrMission.Kind.PASSENGER);
						System.out.println("[MMTR-MSG] motion mission " + parsedKind + " armed to rail " + motionPlan.targetRailHex + " stop @" + Math.round(motionPlan.stopCumulativeM) + "m");
					} else {
						// Stay assigned and unarmed (stop target untouched so the self-arm condition stays
						// true): the vehicle's mission machine retries the grants every tick and arms the
						// run the moment every fork is granted.
						System.out.println("[MMTR-MSG] motion mission " + parsedKind + " queued behind turnout authority to rail " + motionPlan.targetRailHex);
					}
				} else if (!motionVehicle) {
					// Headless mission drive: engage the manual seam so the train actually moves.
					vehicle.engageMissionAutopilot();
				}
			}
			dispatched[0] = true;
		}));
		return dispatched[0];
	}

	/** The real rail of the platform/siding with the given id (its drawn graph rail), if any. */
	private static Rail findTargetRail(Simulator simulator, long targetSidingId) {
		return MmtrRunPlanner.findSavedRailRail(simulator, targetSidingId);
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