package org.mtr.core.data;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.mmtr.MmtrPeriodicTaskSource;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1 vertical slice: a periodic (timetable-style) task source keeps assigning PASSENGER missions
 * to the consist itself on a fixed cadence — no player or AI anywhere. Deterministic via
 * Simulator.step: missions must complete and never overlap on the single consist.
 */
public final class MmtrPeriodicTaskTests {

	private static Simulator buildWorld() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-mini-periodic"), false);
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
		siding.setMaxManualSpeed(Utilities.kilometersPerHourToMetersPerMillisecond(30));
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
		return sim;
	}

	@Test
	public void periodicSourceKeepsAssigningMissionsToTheConsist() {
		final Simulator sim = buildWorld();
		final Vehicle[] parked = {null};
		sim.sidings.forEach(s -> s.iterateVehicles(vehicle -> {
			if (parked[0] == null) {
				parked[0] = vehicle;
			}
		}));
		assertNotNull(parked[0], "a parked train must exist");

		// Every 60 simulated seconds the consist gets a fresh passenger run; no player/AI involved.
		final MmtrPeriodicTaskSource source = new MmtrPeriodicTaskSource(MmtrMission.Kind.PASSENGER, 60_000L, 0);
		sim.mmtrPeriodicTaskSources.add(source);

		int completedRuns = 0;
		boolean wasComplete = false;
		for (int second = 0; second < 280; second++) {
			sim.step(1000);
			final MmtrMission mission = parked[0].getMmtrMission();
			final boolean complete = mission != null && mission.getState() == MmtrMission.State.COMPLETE;
			if (complete && !wasComplete) {
				completedRuns++;
				System.out.println("[PERIODIC] completed run " + completedRuns + " at t=" + second + " kind=" + (mission == null ? "?" : mission.getKind()));
			}
			wasComplete = complete;
		}
		final MmtrMission last = parked[0].getMmtrMission();
		System.out.println("[PERIODIC] completedRuns=" + completedRuns + " last=" + (last == null ? "none" : last.getState()));
		assertTrue(completedRuns >= 3, "periodic source must complete multiple runs, got " + completedRuns);
		assertNotNull(last);
		assertEquals(MmtrMission.State.COMPLETE, last.getState());
		assertEquals(MmtrMission.Kind.PASSENGER, last.getKind());
		assertEquals(MmtrMission.Executor.AUTOPILOT, last.getExecutor());
	}
}
