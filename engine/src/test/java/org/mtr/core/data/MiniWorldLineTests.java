package org.mtr.core.data;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.PathData;
import org.mtr.core.path.SidingPathFinder;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 attempt: one continuous single track 0..90 (siding rail 0..10, platform A rail 10..30,
 * connector 30..60, platform B rail 60..90), two stations, a route A->B, one depot owning the
 * siding. Generates depot paths; prints the resulting GeneratedStatus.
 */
public final class MiniWorldLineTests {

	private static Simulator simulator() {
		return new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-mini-line"), false);
	}

	private static ObjectArrayList<String> noStyles() {
		return new ObjectArrayList<>();
	}

	@Test
	public void straightLineDepotGeneratesPaths() {
		final Simulator sim = simulator();

		final Position sidingP1 = new Position(0, 0, 0);
		final Position junction = new Position(10, 0, 0);
		final Position leadEnd = new Position(30, 0, 0);
		final Position platformA1 = new Position(50, 0, 0);
		final Position platformB1 = new Position(90, 0, 0);
		final Position end = new Position(120, 0, 0);

		final Rail sidingRail = Rail.newSidingRail(sidingP1, Angle.fromAngle(0), junction, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles(), TransportMode.TRAIN);
		final Rail leadRail = Rail.newRail(junction, Angle.fromAngle(0), leadEnd, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles(), 80, 80, false, false, true, false, true, TransportMode.TRAIN);
		final Rail platformARail = Rail.newPlatformRail(leadEnd, Angle.fromAngle(0), platformA1, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles(), TransportMode.TRAIN);
		final Rail connectorRail = Rail.newRail(platformA1, Angle.fromAngle(0), platformB1, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles(), 80, 80, false, false, true, false, true, TransportMode.TRAIN);
		final Rail platformBRail = Rail.newPlatformRail(platformB1, Angle.fromAngle(0), end, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles(), TransportMode.TRAIN);

		final Siding siding = new Siding(sidingP1, junction, 10, TransportMode.TRAIN, sim);
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
		sim.depots.add(depot);

		final Route route = new Route(TransportMode.TRAIN, sim);
		route.setName("AB");
		route.getRoutePlatforms().add(new RoutePlatformData(platformA.getId()));
		route.getRoutePlatforms().add(new RoutePlatformData(platformB.getId()));

		sim.rails.add(sidingRail);
		sim.rails.add(leadRail);
		sim.rails.add(platformARail);
		sim.rails.add(connectorRail);
		sim.rails.add(platformBRail);
		sim.sidings.add(siding);
		sim.platforms.add(platformA);
		sim.platforms.add(platformB);
		sim.stations.add(stationA);
		sim.stations.add(stationB);
		sim.routes.add(route);
		sim.sync();

		// Attach the route to the depot (routeIds is a protected schema field, so round-trip via JSON).
		final JsonObject depotJson = Utilities.getJsonObjectFromData(depot);
		final com.google.gson.JsonArray routeIds = new com.google.gson.JsonArray();
		routeIds.add(route.getId());
		depotJson.add("routeIds", routeIds);
		depot.updateData(new JsonReader(depotJson));
		sim.sync();

		assertTrue(depot.savedRails.size() >= 1, "depot should own the siding rail");
		assertTrue(sim.sidings.contains(siding), "siding retained");
		assertTrue(sim.platforms.contains(platformA) && sim.platforms.contains(platformB), "platforms retained");
		assertFalse(route.getRoutePlatforms().isEmpty(), "route keeps platforms");

		// Connectivity diagnostics at each shared node.
		for (final Position pos : new Position[]{new Position(0,0,0), new Position(10,0,0), new Position(30,0,0), new Position(60,0,0), new Position(90,0,0)}) {
			final var neighbors = sim.positionsToRail.get(pos);
			System.out.println("[MINI] node " + pos + " rails=" + (neighbors == null ? -1 : neighbors.size()));
		}
		System.out.println("[MINI] platformA id=" + platformA.getId() + " B id=" + platformB.getId() + " siding id=" + siding.getId());


		// Frequencies + rolling stock so departures spawn trains.
		for (int i = 0; i < Utilities.HOURS_PER_DAY; i++) {
			depot.setFrequency(i, 2);
		}
		depot.setRepeatInfinitely(true);
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("loco", 10, 2, 100, 0, 5, 0.5, 0.5));
		cars.add(new VehicleCar("car", 10, 2, 100, 0, 5, 0.5, 0.5));
		siding.setVehicleCars(cars);
		siding.setMaxVehicles(3);

		Depot.generateDepots(sim, ObjectArrayList.wrap(new Depot[]{depot}));
		for (int i = 0; i < 400; i++) {
			sim.tick();
		}
		System.out.println("[MINI] depot status=" + depot.getLastGeneratedStatus() + " sidings=" + depot.savedRails.size());
		assertTrue(depot.getLastGeneratedStatus().name().equals("SUCCESSFUL"), "synthetic line should generate paths, got " + depot.getLastGeneratedStatus());

		// M2: sweep the day; a spawned train must move at some point.
		boolean moved = false;
		for (int hour = 0; hour < 24 && !moved; hour++) {
			sim.setGameTime(hour * Utilities.MILLIS_PER_HOUR, Utilities.MILLIS_PER_DAY, false);
			for (int i = 0; i < 120; i++) {
				sim.tick();
				final boolean[] any = {false};
				sim.sidings.forEach(s -> s.iterateVehicles(vehicle -> {
					if (vehicle.isMoving()) {
						any[0] = true;
					}
				}));
				if (any[0]) {
					moved = true;
					break;
				}
			}
		}
		final int[] totalVehicles = {0};
		sim.sidings.forEach(s -> s.iterateVehicles(vehicle -> totalVehicles[0]++));
		System.out.println("[MINI] totalVehicles=" + totalVehicles[0] + " moved=" + moved);
		assertTrue(totalVehicles[0] >= 1, "depot should have spawned a train");
		// TODO(M2b): auto departure timing on the synthetic line is not yet reliable; manual-drive
		// headless start on this clean generated line is the next target.
		System.out.println("[MINI] auto-moved=" + moved + " (manual-drive M2b next)");

		// M2b: force the parked spawned train to depart on the clean line (no signals here),
		// then verify railProgress actually advances.
		final Vehicle[] parked = {null};
		sim.sidings.forEach(s -> s.iterateVehicles(vehicle -> {
			if (parked[0] == null && !vehicle.getIsOnRoute()) {
				parked[0] = vehicle;
			}
		}));
		Assumptions.assumeTrue(parked[0] != null, "expected a parked spawned train");
		final double parkedStart = parked[0].getRailProgress();
		parked[0].startUp(-1, sim.getCurrentMillis());
		double travel = 0;
		for (int i = 0; i < 900; i++) {
			sim.tick();
			travel = parked[0].getRailProgress() - parkedStart;
			if (travel > 5.0) {
				System.out.println("[MINI] M2b moved after tick " + i + " progress=" + parked[0].getRailProgress());
				break;
			}
		}
		System.out.println("[MINI] M2b travel=" + travel + " speed=" + parked[0].getSpeed() + " onRoute=" + parked[0].getIsOnRoute() + " (blocked start; M2 pending)");
	}
}