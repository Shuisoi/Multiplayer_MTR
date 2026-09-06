package org.mtr.core.data;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.servlet.SystemMapServlet;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3-A engine feed: the SystemMapServlet "mmtr-trains" endpoint must expose live trains and
 * their assigned missions (task belongs to the train), plus siding occupancy, so the web
 * dashboard can render the task-driven Transport System Map without extra round trips.
 */
public final class MmtrTrainsServletTests {

	@Test
	public void trainsEndpointListsVehiclesAndMissions() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-mini-trains-servlet"), false);
		final ObjectArrayList<String> noStyles = new ObjectArrayList<>();
		final Position p0 = new Position(0, 0, 0);
		final Position junction = new Position(10, 0, 0);
		final Position leadEnd = new Position(30, 0, 0);
		final Position platformA1 = new Position(50, 0, 0);
		final Position platformB1 = new Position(90, 0, 0);
		final Position end = new Position(120, 0, 0);

		sim.rails.add(Rail.newSidingRail(p0, Angle.fromAngle(0), junction, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles, TransportMode.TRAIN));
		sim.rails.add(Rail.newRail(junction, Angle.fromAngle(0), leadEnd, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles, 80, 80, false, false, true, false, true, TransportMode.TRAIN));
		sim.rails.add(Rail.newPlatformRail(leadEnd, Angle.fromAngle(0), platformA1, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles, TransportMode.TRAIN));
		sim.rails.add(Rail.newRail(platformA1, Angle.fromAngle(0), platformB1, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles, 80, 80, false, false, true, false, true, TransportMode.TRAIN));
		sim.rails.add(Rail.newPlatformRail(platformB1, Angle.fromAngle(0), end, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, noStyles, TransportMode.TRAIN));

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
		assertEquals("SUCCESSFUL", depot.getLastGeneratedStatus().name());

		final Vehicle[] parked = {null};
		sim.sidings.forEach(s -> s.iterateVehicles(vehicle -> {
			if (parked[0] == null) {
				parked[0] = vehicle;
			}
		}));
		assertNotNull(parked[0], "a train must have spawned");
		final MmtrMission mission = new MmtrMission(parked[0].getId(), MmtrMission.Kind.FREIGHT, siding.getId(), platformA.getId(), System.currentTimeMillis());
		assertTrue(parked[0].setMmtrMission(mission), "mission assignment must succeed on an idle train");
		assertEquals(MmtrMission.Kind.FREIGHT, parked[0].getMmtrMission().getKind());

		// Fetch the live dashboard feed.
		final ObjectArrayList<Simulator> simulators = new ObjectArrayList<>();
		simulators.add(sim);
		final SystemMapServlet servlet = new SystemMapServlet(new ObjectImmutableList<>(simulators));
		final JsonObject[] result = {null};
		servlet.getContent("mmtr-trains", "", new Object2ObjectAVLTreeMap<>(), new JsonReader(new JsonObject()), sim, json -> result[0] = json);
		assertNotNull(result[0], "endpoint must answer");

		final JsonObject root = result[0];
		assertTrue(root.has("trains") && root.has("sidings"), "feed must carry trains and sidings");
		final JsonObject train = root.getAsJsonArray("trains").get(0).getAsJsonObject();
		assertEquals(parked[0].getId(), train.get("vehicleId").getAsLong(), "feed train must match the spawned vehicle");
		assertTrue(train.has("sidingName") && train.has("depotName"), "train must expose siding/depot names");
		assertTrue(train.has("speedKmh") && train.has("railProgressM") && train.has("onRoute"), "train must expose motion fields");
		assertTrue(train.has("mission"), "assigned mission must be visible in the feed");
		final JsonObject missionJson = train.getAsJsonObject("mission");
		assertEquals("FREIGHT", missionJson.get("kind").getAsString());
		assertEquals("ASSIGNED", missionJson.get("state").getAsString());
		assertEquals("AUTOPILOT", missionJson.get("executor").getAsString());
		assertEquals(platformA.getId(), missionJson.get("targetSidingId").getAsLong());

		final JsonObject sidingOut = root.getAsJsonArray("sidings").get(0).getAsJsonObject();
		assertEquals(siding.getName(), sidingOut.get("sidingName").getAsString());
		assertTrue(sidingOut.has("vehiclesTotal"), "siding must expose occupancy");
	}
}
