package org.mtr.core.data;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 deterministic: the synthetic single-track line (depot siding -> platform A -> platform B)
 * must dispatch a train that actually travels when stepped with fixed simulation milliseconds.
 * Simulator.step removes the wall-clock dependency that made the earlier headless drive tests
 * flaky (most Simulator.tick calls advanced 0 ms in tight loops).
 */
public final class MiniWorldDeterministicDriveTests {

	private static Simulator simulator() {
		return new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-mini-detdrive"), false);
	}

	private static ObjectArrayList<String> noStyles() {
		return new ObjectArrayList<>();
	}

	@Test
	public void manualAutopilotTravelsAlongGeneratedLine() {
		final Simulator sim = simulator();
		final Position p0 = new Position(0, 0, 0);
		final Position junction = new Position(10, 0, 0);
		final Position leadEnd = new Position(30, 0, 0);
		final Position platformA1 = new Position(50, 0, 0);
		final Position platformB1 = new Position(90, 0, 0);
		final Position end = new Position(120, 0, 0);

		sim.rails.add(Rail.newSidingRail(p0, Angle.fromAngle(0), junction, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles(), TransportMode.TRAIN));
		sim.rails.add(Rail.newRail(junction, Angle.fromAngle(0), leadEnd, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles(), 80, 80, false, false, true, false, true, TransportMode.TRAIN));
		sim.rails.add(Rail.newPlatformRail(leadEnd, Angle.fromAngle(0), platformA1, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles(), TransportMode.TRAIN));
		sim.rails.add(Rail.newRail(platformA1, Angle.fromAngle(0), platformB1, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles(), 80, 80, false, false, true, false, true, TransportMode.TRAIN));
		sim.rails.add(Rail.newPlatformRail(platformB1, Angle.fromAngle(0), end, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles(), TransportMode.TRAIN));

		final Siding siding = new Siding(p0, junction, 10, TransportMode.TRAIN, sim);
		siding.setIsManual(true);
		final Platform platformA = new Platform(leadEnd, platformA1, TransportMode.TRAIN, sim);
		final Platform platformB = new Platform(platformB1, end, TransportMode.TRAIN, sim);
		final Station stationA = new Station(sim);
		stationA.setName("A");
		stationA.setCorners(new Position(20, -50, -50), new Position(60, 50, 50));
		final Station stationB = new Station(sim);
		stationB.setName("B");
		stationB.setCorners(new Position(80, -50, -50), new Position(130, 50, 50));
		final Depot depot = new Depot(TransportMode.TRAIN, sim);
		depot.setName("Yard");
		depot.setCorners(new Position(-5, -50, -50), new Position(15, 50, 50));
		final Route route = new Route(TransportMode.TRAIN, sim);
		route.setName("AB");
		route.getRoutePlatforms().add(new RoutePlatformData(platformA.getId()));
		route.getRoutePlatforms().add(new RoutePlatformData(platformB.getId()));

		sim.sidings.add(siding);
		sim.platforms.add(platformA);
		sim.platforms.add(platformB);
		sim.stations.add(stationA);
		sim.stations.add(stationB);
		sim.depots.add(depot);
		sim.routes.add(route);
		sim.sync();

		final JsonObject depotJson = Utilities.getJsonObjectFromData(depot);
		final com.google.gson.JsonArray routeIds = new com.google.gson.JsonArray();
		routeIds.add(route.getId());
		depotJson.add("routeIds", routeIds);
		depot.updateData(new JsonReader(depotJson));
		sim.sync();

		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("loco", 10, 2, 100, 0, 5, 0.5, 0.5));
		siding.setVehicleCars(cars);
		Depot.generateDepots(sim, ObjectArrayList.wrap(new Depot[]{depot}));
		for (int i = 0; i < 300; i++) {
			sim.tick();
		}
		assertEquals("SUCCESSFUL", depot.getLastGeneratedStatus().name(), "synthetic line must generate paths");

		final Vehicle[] parked = {null};
		sim.sidings.forEach(s -> s.iterateVehicles(vehicle -> {
			if (parked[0] == null) {
				parked[0] = vehicle;
			}
		}));
		assertNotNull(parked[0], "depot must have a spawned train");
		assertTrue(parked[0].vehicleExtraData.getIsManualAllowed(), "manual siding vehicle must allow manual drive");

		parked[0].vehicleExtraData.closeDoors();
		parked[0].engageManualAutopilot(300_000L);
		parked[0].vehicleExtraData.setPowerLevel(Vehicle.MAX_POWER_LEVEL);

		// Deterministic: fixed 1s slices. Speed is internal m/ms so 1 s slice = 1000 ms.
		final double startProgress = parked[0].getRailProgress();
		double maxTravel = 0;
		for (int second = 0; second < 60; second++) {
			sim.step(1000);
			maxTravel = Math.max(maxTravel, parked[0].getRailProgress() - startProgress);
			if (maxTravel > 5.0) {
				break;
			}
		}
		System.out.println("[DET] start=" + startProgress + " progress=" + parked[0].getRailProgress() + " maxTravel=" + maxTravel + " speed=" + parked[0].getSpeed() + " onRoute=" + parked[0].getIsOnRoute());
		assertTrue(maxTravel > 5.0, "train must travel at least 5 m with deterministic stepping, got " + maxTravel);
	}
}
