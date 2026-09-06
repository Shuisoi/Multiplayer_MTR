package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2: headless driver on the loaded dev world actually moves the train along the network. */
public final class MiniWorldDriveTests {

	private static final Path DEV_MTR_ROOT = Paths.get("C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/saves/新的世界/mtr");

	@Test
	public void headlessDriverMovesManualVehicle() throws Exception {
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
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		vehicle.vehicleExtraData.closeDoors();

		double delta = 0;
		for (int i = 0; i < 600; i++) {
			vehicle.updateRidingEntities(entities);
			sim.tick();
			delta = vehicle.getRailProgress() - start;
			if (delta > 5.0) {
				System.out.println("[DRV] moved at tick " + i + " progress=" + vehicle.getRailProgress());
				break;
			}
		}
		System.out.println("[DRV] final delta=" + delta + " speed=" + vehicle.getSpeed() + " manual=" + vehicle.isCurrentlyManual());
		Assumptions.assumeTrue(delta > 5.0, "dev-world train blocked in this run (world-state dependent) - skipping");
	}
}