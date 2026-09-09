package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.consist.MmtrCabState;
import org.mtr.core.mmtr.consist.MmtrConsistWalker;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client-side view of a multi-car consist, reproduced headlessly on the live dev world.
 *
 * <p>Both failures pinned here were only ever visible in game: the per-frame client tick used to
 * overwrite the synced {@code railProgress} with the siding default position (every car then fell
 * outside the synced path and the whole train collapsed onto one point), and the motion simulation
 * used to close the doors on every tick (so a crew door command never survived). Both are engine
 * code, so both are testable here without a client.</p>
 */
public final class MmtrConsistMultiCarPlacementTests {

	private static final Path DEV_WORLD_MTR_ROOT = Paths.get("C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/world/mtr");
	private static final long LONG_YARD_SIDING_ID = -5385228036074278397L;

	@Test
	public void eightCarConsistSpreadsItsCarsOverTheWholeTrain() {
		final Vehicle vehicle = spawnEightCarConsist();

		final java.util.List<PathData> path = vehicle.vehicleExtraData.immutablePath;
		System.out.println("[PROBE] railProgress=" + vehicle.getRailProgress() + " legs=" + path.size()
			+ " pathStart=" + (path.isEmpty() ? "-" : path.get(0).getStartDistance())
			+ " pathEnd=" + (path.isEmpty() ? "-" : path.get(path.size() - 1).getEndDistance())
			+ " totalVehicleLength=" + vehicle.vehicleExtraData.getTotalVehicleLength());
		printCars("SERVER", vehicle);
		assertTrue(spreadOf(vehicle) > 100, "the cars must spread over the train length, not pile onto one spot");

		// Reproduce the client mirror exactly as the game does it: the server packs the update into a
		// DynamicDataResponse, the client parses it and builds a VehicleExtension from the update's
		// "data" VED plus the "vehicle" child.
		final org.mtr.core.operation.DynamicDataResponse response = new org.mtr.core.operation.DynamicDataResponse(java.util.UUID.randomUUID(), new ClientData());
		response.addVehicleToUpdate(new org.mtr.core.operation.VehicleUpdate(vehicle, vehicle.vehicleExtraData.copy(0)));
		final com.google.gson.JsonObject packetJson = org.mtr.core.tool.Utilities.getJsonObjectFromData(response);
		final org.mtr.core.operation.DynamicDataResponse parsedResponse = new org.mtr.core.operation.DynamicDataResponse(new org.mtr.core.serializer.JsonReader(packetJson), new ClientData());
		parsedResponse.iterateVehiclesToUpdate(vehicleUpdate -> {
			final com.google.gson.JsonObject vehicleJson = org.mtr.core.tool.Utilities.getJsonObjectFromData(vehicleUpdate.getVehicle());
			final Vehicle clientVehicle = new Vehicle(vehicleUpdate.getVehicleExtraData(), null, new org.mtr.core.serializer.JsonReader(vehicleJson), new ClientData());
			final java.util.List<PathData> clientPath = clientVehicle.vehicleExtraData.immutablePath;
			System.out.println("[PROBE] CLIENT railProgress=" + clientVehicle.getRailProgress() + " legs=" + clientPath.size()
				+ " pathStart=" + (clientPath.isEmpty() ? "-" : clientPath.get(0).getStartDistance())
				+ " pathEnd=" + (clientPath.isEmpty() ? "-" : clientPath.get(clientPath.size() - 1).getEndDistance())
				+ " totalVehicleLength=" + clientVehicle.vehicleExtraData.getTotalVehicleLength()
				+ " reversed=" + clientVehicle.getReversed());
			printCars("CLIENT", clientVehicle);

			// The client ticks every vehicle once per frame (MainRenderer -> Vehicle.simulate). For a
			// parked motion mirror that used to run simulateInDepot(), which overwrites the synced
			// railProgress with the siding default position and clears reversed - every car then lands
			// outside the synced path and the whole consist collapses onto the path end.
			final double progressBefore = clientVehicle.getRailProgress();
			for (int i = 0; i < 10; i++) {
				clientVehicle.simulate(50, null, null);
			}
			printCars("CLIENT-TICKED", clientVehicle);
			assertEquals(progressBefore, clientVehicle.getRailProgress(), 1e-9, "the client tick must not move a parked mirror");
			assertTrue(spreadOf(clientVehicle) > 100, "the cars must still spread over the train after a client tick");
		});
	}

	@Test
	public void aStandingMotionTrainKeepsTheCrewsDoorsOpen() {
		final Vehicle vehicle = spawnEightCarConsist();
		assertTrue(vehicle.vehicleExtraData.mmtrSetDoors("open"), "the crew door command opens the doors");
		for (int i = 0; i < 20; i++) {
			vehicle.simulate(50, null, null);
		}
		assertTrue(vehicle.vehicleExtraData.mmtrDoorsOpen(), "a standing train must not auto-close the crew's doors on the next tick");
	}

	private static Vehicle spawnEightCarConsist() {
		Assumptions.assumeTrue(Files.isDirectory(DEV_WORLD_MTR_ROOT), "live dev world not present - skipping");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, DEV_WORLD_MTR_ROOT, false);

		Siding siding = null;
		for (final Siding candidate : sim.sidings) {
			if (candidate.getId() == LONG_YARD_SIDING_ID) {
				siding = candidate;
				break;
			}
		}
		assertNotNull(siding, "the long aassdd siding must exist");

		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("hst_h", 15, 5, 400, -5, 5, 0, 0));
		for (int i = 0; i < 6; i++) {
			cars.add(new VehicleCar("hst_b", 17, 5, 400, -5, 5, 0, 0));
		}
		cars.add(new VehicleCar("hst_h_rev", 15, 5, 400, -5, 5, 0, 0));
		siding.setVehicleCars(cars);
		siding.clearParkedVehicles();

		final MmtrConsistWalker walker = siding.mmtrConsistWalkerFromYard(null, null, null);
		assertNotNull(walker, "the 132 m consist body must fit the long siding");
		final Vehicle vehicle = siding.spawnMmtrConsistVehicle(walker, MmtrCabState.Cab.CAB_A);
		assertNotNull(vehicle, "consist vehicle spawned");
		return vehicle;
	}

	private static void printCars(String label, Vehicle vehicle) {
		final StringBuilder zs = new StringBuilder();
		for (final var carAndPosition : vehicle.getVehicleCarsAndPositions()) {
			if (!carAndPosition.right().isEmpty()) {
				zs.append(String.format("%.0f ", carAndPosition.right().get(0).positionAndTiltAngle1().position().z()));
			}
		}
		System.out.println("[PROBE] " + label + " spread=" + String.format("%.1f", spreadOf(vehicle)) + " m  zs=[" + zs.toString().trim() + "]");
	}

	private static double spreadOf(Vehicle vehicle) {
		double minZ = Double.MAX_VALUE;
		double maxZ = -Double.MAX_VALUE;
		for (final var carAndPosition : vehicle.getVehicleCarsAndPositions()) {
			if (carAndPosition.right().isEmpty()) {
				continue;
			}
			final double z = carAndPosition.right().get(0).positionAndTiltAngle1().position().z();
			minZ = Math.min(minZ, z);
			maxZ = Math.max(maxZ, z);
		}
		return maxZ - minZ;
	}
}
