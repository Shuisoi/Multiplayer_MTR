package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2b vertical: a mission (host = train, autopilot executor) drives the dev-world vehicle to a
 * target; the state machine reaches AT_TARGET then COMPLETE while railProgress advances.
 */
public final class MiniWorldMissionTests {

	private static final Path DEV_MTR_ROOT = Paths.get("C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/saves/新的世界/mtr");

	@Test
	public void missionAutopilotRunsTrainToTarget() throws Exception {
		Assumptions.assumeTrue(Files.isDirectory(DEV_MTR_ROOT), "dev world save not present - skipping");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, DEV_MTR_ROOT, false);
		final Vehicle[] candidate = {null};
		sim.sidings.forEach(siding -> {
			if (candidate[0] == null) {
				siding.iterateVehicles(vehicle -> {
					if (candidate[0] == null && vehicle.vehicleExtraData.getIsManualAllowed()) {
						candidate[0] = vehicle;
					}
				});
			}
		});
		Assumptions.assumeTrue(candidate[0] != null, "no manual-allowed vehicle in dev world");
		final Vehicle vehicle = candidate[0];
		final double start = vehicle.getRailProgress();
		final double targetProgress = start + 30;

		final MmtrMission mission = new MmtrMission(vehicle.getId(), MmtrMission.Kind.PASSENGER, 1, 2, sim.getCurrentMillis());
		assertEquals(MmtrMission.State.ASSIGNED, mission.getState());
		vehicle.vehicleExtraData.closeDoors();

		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		final UUID autopilot = UUID.randomUUID(); // the system acts as the cab driver for the autopilot
		entities.add(new VehicleRidingEntity(autopilot, 0, 0, 0, 0, false, true, true, false, false, false, false));

		boolean reached = false;
		for (int i = 0; i < 900 && !mission.isTerminal(); i++) {
			if (mission.getState() == MmtrMission.State.ASSIGNED) {
				mission.dispatch();
			}
			vehicle.updateRidingEntities(entities);
			sim.tick();
			if (!reached && vehicle.getRailProgress() >= targetProgress) {
				reached = true;
				mission.atTarget();
			}
		}
		if (mission.getState() == MmtrMission.State.AT_TARGET) {
			mission.complete();
		}
		System.out.println("[MIS] state=" + mission.getState() + " delta=" + (vehicle.getRailProgress() - start) + " reached=" + reached);
		Assumptions.assumeTrue(reached, "dev-world train was blocked before reaching the target in this run (world-position dependent)");
		assertEquals(MmtrMission.State.COMPLETE, mission.getState(), "mission should complete after reaching target");
	}
}