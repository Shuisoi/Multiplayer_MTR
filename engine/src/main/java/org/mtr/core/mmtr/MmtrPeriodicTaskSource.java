package org.mtr.core.mmtr;

import org.mtr.core.data.Vehicle;
import org.mtr.core.simulation.Simulator;

/**
 * MMTR timetable-style task source: periodically (every periodMillis of simulation time)
 * attaches a mission to an idle parked train. The task is owned by the consist — players and AI
 * are never required; the AUTOPILOT executor drives it headlessly. This is the adapter that
 * turns "a passenger line has a schedule" into "that power unit gets a task on schedule".
 *
 * <p>One source covers the whole siding set; a per-siding filter is available for fleet
 * management. Missions complete at the end of the consist's generated path (target 0).</p>
 */
public final class MmtrPeriodicTaskSource {

	private final MmtrMission.Kind kind;
	private final long periodMillis;
	/**
	 * Optional siding filter; 0 means any manual siding's parked train is eligible.
	 */
	private final long sidingId;

	private long nextDueMillis = -1;

	public MmtrPeriodicTaskSource(MmtrMission.Kind kind, long periodMillis, long sidingId) {
		this.kind = kind;
		this.periodMillis = periodMillis;
		this.sidingId = sidingId;
	}

	/**
	 * Called by the simulator each tick. When a due mission is assigned the next due time is set;
	 * otherwise it retries on the next tick so a full schedule is not skipped because a consist
	 * was briefly occupied.
	 */
	public void tick(long currentMillis, Simulator simulator) {
		if (nextDueMillis < 0) {
			nextDueMillis = currentMillis + periodMillis;
			return;
		}
		if (currentMillis < nextDueMillis) {
			return;
		}
		if (tryAssign(simulator, currentMillis)) {
			nextDueMillis = currentMillis + periodMillis;
		}
	}

	private boolean tryAssign(Simulator simulator, long currentMillis) {
		final boolean[] assigned = {false};
		simulator.sidings.forEach(siding -> {
			if (assigned[0] || sidingId != 0 && siding.getId() != sidingId) {
				return;
			}
			siding.iterateVehicles(vehicle -> {
				if (assigned[0]) {
					return;
				}
				final MmtrMission existing = vehicle.getMmtrMission();
				if (existing != null && !existing.isTerminal()) {
					return;
				}
				if (vehicle.getIsOnRoute() || !vehicle.vehicleExtraData.getIsManualAllowed()) {
					// Only idle parked manual trains are eligible for a scheduled headless task.
					return;
				}
				final MmtrMission mission = new MmtrMission(vehicle.getId(), kind, siding.getId(), 0, currentMillis);
				if (vehicle.setMmtrMission(mission)) {
					// AUTOPILOT executor: engage the headless seam so the consist starts immediately.
					vehicle.engageMissionAutopilot();
					assigned[0] = true;
				}
			});
		});
		return assigned[0];
	}
}
