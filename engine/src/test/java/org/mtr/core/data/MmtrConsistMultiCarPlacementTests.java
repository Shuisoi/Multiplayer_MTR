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
 * An 8-car consist parked on the long yard siding must spread its cars over the whole 132 m.
 *
 * <p>{@code Vehicle.getVehicleCarsAndPositions()} is the same code the client mirror runs (the client
 * holds a synced copy of the same class), so this is the headless reproduction of the "all cars piled
 * onto one spot" report: it prints the per-car bogie positions, the synced path distances and the
 * resulting spread.</p>
 */
public final class MmtrConsistMultiCarPlacementTests {

	private static final Path DEV_WORLD_MTR_ROOT = Paths.get("C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/world/mtr");
	private static final long LONG_YARD_SIDING_ID = -5385228036074278397L;

	@Test
	public void eightCarConsistSpreadsItsCarsOverTheWholeTrain() {
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

		final java.util.List<PathData> path = vehicle.vehicleExtraData.immutablePath;
		System.out.println("[PROBE] railProgress=" + vehicle.getRailProgress() + " legs=" + path.size()
			+ " pathStart=" + (path.isEmpty() ? "-" : path.get(0).getStartDistance())
			+ " pathEnd=" + (path.isEmpty() ? "-" : path.get(path.size() - 1).getEndDistance())
			+ " totalVehicleLength=" + vehicle.vehicleExtraData.getTotalVehicleLength());
		printCars("SERVER", vehicle);

		// Reproduce the client mirror exactly as the game does it: the server packs the update into a
		// DynamicDataResponse, the client parses it and builds a VehicleExtension from the update's
		// "data" VED plus the "vehicle" child.
		final org.mtr.core.operation.DynamicDataResponse response = new org.mtr.core.operation.DynamicDataResponse(java.util.UUID.randomUUID(), sim);
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
			// railProgress with the siding default position ((railLength + trainLength) / 2) and clears
			// reversed - every car then lands outside the synced path and the whole consist collapses
			// onto the path end. Pin the invariant: the client mirror must survive its own tick.
			final double progressBefore = clientVehicle.getRailProgress();
			for (int i = 0; i < 10; i++) {
				clientVehicle.simulate(50, null, null);
			}
			printCars("CLIENT-TICKED", clientVehicle);
			assertEquals(progressBefore, clientVehicle.getRailProgress(), 1e-9, "the client tick must not move a parked mirror");
			assertTrue(spreadOf(clientVehicle) > 100, "the cars must still spread over the train after a client tick");
		});
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

	private static void printCars(String label, Vehicle vehicle) {
		double minZ = Double.MAX_VALUE;
		double maxZ = -Double.MAX_VALUE;
		for (final var carAndPosition : vehicle.getVehicleCarsAndPositions()) {
			final var bogie = carAndPosition.right().isEmpty() ? null : carAndPosition.right().get(0).positionAndTiltAngle1().position();
			if (bogie != null) {
				minZ = Math.min(minZ, bogie.z());
				maxZ = Math.max(maxZ, bogie.z());
			}
			System.out.println("[PROBE] " + label + " car=" + carAndPosition.left().getVehicleId()
				+ " bogie1=" + (bogie == null ? "-" : String.format("%.1f,%.1f", bogie.x(), bogie.z())));
		}
		System.out.println("[PROBE] " + label + " spread=" + String.format("%.1f", maxZ - minZ) + " m");
	}
}
