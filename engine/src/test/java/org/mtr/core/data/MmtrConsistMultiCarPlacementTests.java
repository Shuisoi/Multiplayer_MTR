package org.mtr.core.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client-side view of a multi-car consist, reproduced headlessly.
 *
 * <p>Both failures pinned here were only ever visible in game: the per-frame client tick used to
 * overwrite the synced {@code railProgress} with the siding default position (every car then fell
 * outside the synced path and the whole train collapsed onto one point), and the motion simulation
 * used to close the doors on every tick (so a crew door command never survived). Both are engine
 * code, so both are testable here without a client.</p>
 *
 * <p>2026-10-09: the 8-car consist used to be parked on the live dev world's {@code aassdd} long
 * siding, which no longer exists. The yard is now built by {@link MmtrLongYardSidingFixture} (a
 * 170 m straight siding in {@code build/}), so the case no longer depends on someone else's world
 * save - the assertions themselves are unchanged.</p>
 */
public final class MmtrConsistMultiCarPlacementTests {

	@Test
	public void eightCarConsistSpreadsItsCarsOverTheWholeTrain() {
		final Vehicle vehicle = newFixture("build/mmtr-consist-placement").spawnEightCarConsist();

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
		final Vehicle vehicle = newFixture("build/mmtr-consist-doors").spawnEightCarConsist();
		assertTrue(vehicle.vehicleExtraData.mmtrSetDoors("open"), "the crew door command opens the doors");
		for (int i = 0; i < 20; i++) {
			vehicle.simulate(50, null, null);
		}
		assertTrue(vehicle.vehicleExtraData.mmtrDoorsOpen(), "a standing train must not auto-close the crew's doors on the next tick");
	}

	private static MmtrLongYardSidingFixture newFixture(String savePath) {
		return new MmtrLongYardSidingFixture(savePath);
	}

	private static void printCars(String label, Vehicle vehicle) {
		final StringBuilder xs = new StringBuilder();
		for (final var carAndPosition : vehicle.getVehicleCarsAndPositions()) {
			if (!carAndPosition.right().isEmpty()) {
				xs.append(String.format("%.0f ", carAndPosition.right().get(0).positionAndTiltAngle1().position().x()));
			}
		}
		System.out.println("[PROBE] " + label + " spread=" + String.format("%.1f", spreadOf(vehicle)) + " m  xs=[" + xs.toString().trim() + "]");
	}

	/**
	 * 编组在水平面上的最大车心间距（米）。用**两两车心距离**而不是某一根轴上的极差：夹具自建的股道与
	 * 现场那条 {@code aassdd} 股道朝向未必相同，而"全车挤在一点"这个失效模式在任何朝向下都必须红。
	 */
	private static double spreadOf(Vehicle vehicle) {
		final java.util.List<double[]> centres = new java.util.ArrayList<>();
		for (final var carAndPosition : vehicle.getVehicleCarsAndPositions()) {
			if (carAndPosition.right().isEmpty()) {
				continue;
			}
			final var position = carAndPosition.right().get(0).positionAndTiltAngle1().position();
			centres.add(new double[]{position.x(), position.z()});
		}
		double max = 0;
		for (int i = 0; i < centres.size(); i++) {
			for (int j = i + 1; j < centres.size(); j++) {
				max = Math.max(max, Math.hypot(centres.get(i)[0] - centres.get(j)[0], centres.get(i)[1] - centres.get(j)[1]));
			}
		}
		return max;
	}
}
