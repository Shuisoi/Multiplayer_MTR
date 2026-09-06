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

/**
 * M2: headless driver on the loaded dev world (manual vehicle). Closes the doors once, then
 * presses accelerate and asserts the vehicle moves.
 */
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
		final double startProgress = vehicle.getRailProgress();
		final UUID driver = UUID.randomUUID();

		// Close the doors once (toggle while stopped), then press accelerate every tick.
		final ObjectArrayList<VehicleRidingEntity> closeDoors = new ObjectArrayList<>();
		closeDoors.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, false, false, true, false, false));
		vehicle.updateRidingEntities(closeDoors);
		sim.tick();

		final ObjectArrayList<VehicleRidingEntity> accelerate = new ObjectArrayList<>();
		accelerate.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		for (int i = 0; i < 1500; i++) {
			vehicle.updateRidingEntities(accelerate);
			sim.tick();
			if (i < 4 || i % 250 == 0) {
				System.out.println("[DRV] tick " + i + " power=" + vehicle.vehicleExtraData.getPowerLevel() + " speed=" + vehicle.getSpeed() + " progress=" + vehicle.getRailProgress() + " doorMultiplier=" + vehicle.vehicleExtraData.getDoorMultiplier());
			}
			if (vehicle.getRailProgress() - startProgress > 1.0) {
				System.out.println("[DRV] moved at tick " + i + " progress=" + vehicle.getRailProgress());
				break;
			}
		}
		final double delta = vehicle.getRailProgress() - startProgress;
		System.out.println("[DRV] final delta=" + delta + " speed=" + vehicle.getSpeed() + " moving=" + vehicle.isMoving());
		assertTrue(delta > 1.0, "manual vehicle should move after closing doors and applying power, delta=" + delta);
	}
}
