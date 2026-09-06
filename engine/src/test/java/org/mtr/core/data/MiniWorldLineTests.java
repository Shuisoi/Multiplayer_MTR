package org.mtr.core.data;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
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
		final Position platformA1 = new Position(30, 0, 0);
		final Position platformB1 = new Position(60, 0, 0);
		final Position end = new Position(90, 0, 0);

		final Rail sidingRail = Rail.newSidingRail(sidingP1, Angle.fromAngle(0), junction, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, noStyles(), TransportMode.TRAIN);
		final Rail platformARail = Rail.newPlatformRail(junction, Angle.fromAngle(0), platformA1, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, noStyles(), TransportMode.TRAIN);
		final Rail connectorRail = Rail.newRail(platformA1, Angle.fromAngle(0), platformB1, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, noStyles(), 80, 80, false, false, true, false, true, TransportMode.TRAIN);
		final Rail platformBRail = Rail.newPlatformRail(platformB1, Angle.fromAngle(0), end, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, noStyles(), TransportMode.TRAIN);

		final Siding siding = new Siding(sidingP1, junction, 10, TransportMode.TRAIN, sim);
		final Platform platformA = new Platform(junction, platformA1, TransportMode.TRAIN, sim);
		final Platform platformB = new Platform(platformB1, end, TransportMode.TRAIN, sim);

		final Station stationA = new Station(sim);
		stationA.setName("A");
		stationA.setCorners(new Position(5, -50, -50), new Position(45, 50, 50));
		final Station stationB = new Station(sim);
		stationB.setName("B");
		stationB.setCorners(new Position(55, -50, -50), new Position(95, 50, 50));

		final Depot depot = new Depot(TransportMode.TRAIN, sim);
		depot.setName("Yard");
		depot.setCorners(new Position(-5, -50, -50), new Position(25, 50, 50));
		sim.depots.add(depot);

		final Route route = new Route(TransportMode.TRAIN, sim);
		route.setName("AB");
		route.getRoutePlatforms().add(new RoutePlatformData(platformA.getId()));
		route.getRoutePlatforms().add(new RoutePlatformData(platformB.getId()));

		sim.rails.add(sidingRail);
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

		Depot.generateDepots(sim, ObjectArrayList.wrap(new Depot[]{depot}));
		for (int i = 0; i < 200; i++) {
			sim.tick();
		}
		System.out.println("[MINI] depot status=" + depot.getLastGeneratedStatus());
		System.out.println("[MINI] depot saved sidings=" + depot.savedRails.size());
		System.out.println("[MINI] route platforms=" + route.getRoutePlatforms().size());
	}
}