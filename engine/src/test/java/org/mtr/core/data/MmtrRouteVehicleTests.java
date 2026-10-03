package org.mtr.core.data;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.mmtr.point.MmtrPointAuthority;
import org.mtr.core.mmtr.route.MmtrRoute;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.mmtr.signal.MmtrShuntAuthority;
import org.mtr.core.operation.MmtrMissionControl;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S5 acceptance at the real engine seams: a motion mission now publishes its movement as a live
 * 进路 (route) in {@code Simulator.mmtrRoutes}, the route's SET/PENDING state tracks the turnout
 * authority every tick, and a terminal mission / vehicle deletion drops it again. This is the
 * object the signal layer (A2) reads: a signal may only show a proceed aspect for a rail when a
 * SET route runs over it.
 */
public final class MmtrRouteVehicleTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000}]"
		+ "}";

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/**
	 * Yard YR (-32..-20) -> mouth fork at (-20): {rX straight to (60,0,0) | rY diverge} -> fork at
	 * (60,0,0): {rP platform rail to 140 | rQ}. Each test gets its own save directory: the turnout
	 * store persists into the save path, which the Simulator constructor loads back.
	 */
	private static final class Net {
		final Simulator sim;
		final Position yardBack;
		final Position yardMouth;
		final Position node60;
		final Rail yardRail;
		final Rail rX;
		final Rail rY;
		final Rail rP;
		final Rail rQ;
		final Depot depot;
		final Siding siding;
		final Station station;
		final Platform platform;

		Net(String savePath) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			yardBack = new Position(-32, 0, 0);
			yardMouth = new Position(-20, 0, 0);
			node60 = new Position(60, 0, 0);
			yardRail = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			rX = through(yardMouth, node60);
			rY = through(yardMouth, new Position(60, 0, 14));
			rP = Rail.newPlatformRail(node60, Angle.fromAngle(0), new Position(140, 0, 0), Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			rQ = through(node60, new Position(140, 0, 14));
			depot = new Depot(TransportMode.TRAIN, sim);
			siding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);
			station = new Station(sim);
			platform = new Platform(node60, new Position(140, 0, 0), TransportMode.TRAIN, sim);

			depot.setName("Yard");
			depot.setCorners(new Position(-40, -5, -5), new Position(-10, 5, 5));
			station.setName("North");
			station.setCorners(new Position(50, -5, -5), new Position(150, 5, 5));
			sim.rails.add(yardRail);
			sim.rails.add(rX);
			sim.rails.add(rY);
			sim.rails.add(rP);
			sim.rails.add(rQ);
			sim.depots.add(depot);
			sim.sidings.add(siding);
			sim.stations.add(station);
			sim.platforms.add(platform);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			siding.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			assertTrue(depot.savedRails.contains(siding), "siding must attach to the depot yard");
			assertTrue(station.savedRails.contains(platform), "platform must attach to the station");
			siding.tick();
		}

		Vehicle spawn() {
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, null, null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			return vehicle;
		}
	}

	private static MmtrMissionControl missionOp(Vehicle v, String kind, long targetSidingId) {
		final JsonObject json = new JsonObject();
		json.addProperty("vehicleId", String.valueOf(v.getId()));
		json.addProperty("kind", kind);
		json.addProperty("targetSidingId", String.valueOf(targetSidingId));
		json.addProperty("executor", "AUTOPILOT");
		json.addProperty("startNow", true);
		return new MmtrMissionControl(new JsonReader(json));
	}

	@Test
	public void missionPublishesItsRouteAndReleasesItWhenTerminal() {
		final Net n = new Net("build/mmtr-route-publish");
		final Vehicle v = n.spawn();

		assertTrue(missionOp(v, "PASSENGER", n.platform.getId()).dispatch(n.sim), "mission dispatch must succeed");
		assertTrue(v.isMmtrMotionAuto(), "auto run armed once every en-route fork was granted");

		final MmtrRoute route = v.getMmtrRoute();
		assertNotNull(route, "the mission published its 进路");
		assertEquals(v.getId(), route.getVehicleId());
		assertEquals("v" + v.getId(), route.getOwner());
		assertEquals(MmtrRoute.Kind.MAIN, route.getKind(), "a passenger run is a main route");
		assertTrue(route.isEstablished(), "every turnout held -> the route is SET");
		assertEquals(n.yardRail.getHexId(), route.getEntryRailHex(), "the route starts on the rail the train stands on");
		assertEquals(n.rP.getHexId(), route.getTargetRailHex(), "the route ends on the platform rail");
		assertTrue(route.coversRail(n.yardRail.getHexId()), "the route covers the yard rail");
		assertTrue(route.coversRail(n.rX.getHexId()), "the route covers the intermediate rail");
		assertTrue(route.coversRail(n.rP.getHexId()), "the route covers the platform rail");
		assertEquals(2, route.getForks().size(), "both en-route turnouts are part of the route");
		assertEquals(route, n.sim.mmtrRoutes.routeOverRail(n.rP.getHexId()), "the signal layer reads the SET route over the platform rail");

		int guard = 0;
		while (guard++ < 8000 && (v.getMmtrMission() == null || v.getMmtrMission().getState() != MmtrMission.State.AT_TARGET)) {
			n.siding.simulateVehicles(1000, null);
		}
		assertEquals(MmtrMission.State.AT_TARGET, v.getMmtrMission().getState(), "arrived at the platform");

		v.getMmtrMission().cancel();
		n.siding.simulateVehicles(1000, null);
		assertNull(v.getMmtrRoute(), "a terminal mission drops the vehicle's route");
		assertNull(n.sim.mmtrRoutes.route(v.getId()), "and the registry forgets it");
		assertTrue(n.sim.mmtrRoutes.routesOverRail(n.rP.getHexId()).isEmpty(), "no route is left set over the platform rail");
	}

	@Test
	public void lockedTurnoutKeepsTheRoutePendingUntilUnlock() {
		final Net n = new Net("build/mmtr-route-lock");
		final Vehicle v = n.spawn();
		final MmtrPointAuthority authority = n.sim.mmtrPointAuthority;
		authority.lock(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId());

		assertTrue(missionOp(v, "PASSENGER", n.platform.getId()).dispatch(n.sim), "dispatch succeeds even while the fork is locked");
		assertFalse(v.isMmtrMotionAuto(), "auto NOT armed while an en-route fork is operator-locked");
		final MmtrRoute route = v.getMmtrRoute();
		assertNotNull(route, "the route is published even while it cannot be set");
		assertFalse(route.isEstablished(), "an operator-parked turnout keeps the route PENDING (signal at danger)");
		assertTrue(route.getStateReason().contains("lock=true"), "the reason names the park: " + route.getStateReason());
		assertTrue(n.sim.mmtrRoutes.routeOverRail(n.yardRail.getHexId()) == null, "a PENDING route authorises no proceed aspect");

		authority.unlock(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId());
		for (int i = 0; i < 20 && !v.isMmtrMotionAuto(); i++) {
			n.siding.simulateVehicles(1000, null);
		}
		assertTrue(v.isMmtrMotionAuto(), "unlock lets the mission take its route and arm");
		assertTrue(route.isEstablished(), "the route is SET once the turnout is held");
		assertEquals(route, n.sim.mmtrRoutes.routeOverRail(n.yardRail.getHexId()), "the signal layer now reads the SET route");
	}

	@Test
	public void shuntMovementIsPublishedAsAShuntRoute() {
		final Net n = new Net("build/mmtr-route-shunt");
		final Vehicle v = n.spawn();
		// C3a: a subsidiary-aspect authority is what makes the movement a 调车 (shunt); the route
		// must carry that kind so the signal layer does not treat it as a main route.
		n.sim.mmtrShuntAuthorities.grant(v.getId(), n.yardRail.getHexId(), n.rP.getHexId(),
			MmtrShuntAuthority.Kind.SUBSIDIARY_SHUNT, 0, 5 * 60 * 1000L);

		assertTrue(missionOp(v, "MANEUVER", n.platform.getId()).dispatch(n.sim), "shunt dispatch must succeed");
		final MmtrRoute route = v.getMmtrRoute();
		assertNotNull(route, "the shunt published its 进路");
		assertEquals(MmtrRoute.Kind.SHUNT, route.getKind(), "an authorised shunt is a shunt route, not a main one");
		assertTrue(route.isEstablished(), "the shunt route is SET under its authority");
	}

	@Test
	public void deletingTheVehicleDropsItsRouteAndTurnoutHolds() {
		final Net n = new Net("build/mmtr-route-delete");
		final Vehicle v = n.spawn();
		assertTrue(missionOp(v, "PASSENGER", n.platform.getId()).dispatch(n.sim), "mission dispatch must succeed");
		assertTrue(n.sim.mmtrRoutes.route(v.getId()) != null, "route present before the deletion");
		assertTrue(n.sim.mmtrPointAuthority.holder(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId()) != null, "the mission holds the mouth fork");

		assertTrue(n.sim.deleteMmtrVehicle(v.getId()), "vehicle deleted");
		assertNull(n.sim.mmtrRoutes.route(v.getId()), "a deleted train leaves no route behind");
		assertNull(n.sim.mmtrPointAuthority.holder(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId()), "and no turnout hold either");
	}
}
