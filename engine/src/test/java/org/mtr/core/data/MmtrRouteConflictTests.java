package org.mtr.core.data;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.route.MmtrRoute;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
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
 * S5 acceptance (联锁收编 / route locking): conflicting routes are mutually exclusive and queue in
 * order. Two trains start in two sidings that converge on one throat rail; both need the SAME fork
 * at the far end of the throat (train 1 straight to its platform, train 2 the diverging leg). The
 * first mission's route is SET and it holds the point; the second train's route stays PENDING, its
 * mission never arms and it does not leave its siding. Only when the first train crosses the point
 * (which releases it) does the second route set and its train run.
 *
 * <p>Layout: Y1 (-40,0,0) and Y2 (-40,0,10) both join the throat entry N (-20,0,0); the throat runs
 * N -&gt; M (0,0,0); at M the line forks to platform 1 (M -&gt; 100,0,0) and to P2 (M -&gt; 100,0,20).</p>
 */
public final class MmtrRouteConflictTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000}]"
		+ "}";

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> newTrees() {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = new ObjectArrayList<>();
		trees.add(new Object2ObjectAVLTreeMap<>());
		trees.add(new Object2ObjectAVLTreeMap<>());
		return trees;
	}

	private static final class Net {
		final Simulator sim;
		final Position y1Back = new Position(-40, 0, 0);
		final Position y2Back = new Position(-40, 0, 10);
		final Position throatEntry = new Position(-20, 0, 0);
		final Position fork = new Position(0, 0, 0);
		final Rail y1;
		final Rail y2;
		final Rail throat;
		final Rail p1;
		final Rail p2;
		final Siding siding1;
		final Siding siding2;
		final Platform platform1;
		final Platform platform2;
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = newTrees();

		Net(String savePath) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			y1 = Rail.newSidingRail(y1Back, Angle.fromAngle(0), throatEntry, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			y2 = Rail.newSidingRail(y2Back, Angle.fromAngle(0), throatEntry, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			throat = through(throatEntry, fork);
			p1 = Rail.newPlatformRail(fork, Angle.fromAngle(0), new Position(100, 0, 0), Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			p2 = Rail.newPlatformRail(fork, Angle.fromAngle(0), new Position(100, 0, 20), Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			final Depot depot = new Depot(TransportMode.TRAIN, sim);
			siding1 = new Siding(y1Back, throatEntry, 12, TransportMode.TRAIN, sim);
			siding2 = new Siding(y2Back, throatEntry, 12, TransportMode.TRAIN, sim);
			final Station station = new Station(sim);
			platform1 = new Platform(fork, new Position(100, 0, 0), TransportMode.TRAIN, sim);
			platform2 = new Platform(fork, new Position(100, 0, 20), TransportMode.TRAIN, sim);
			depot.setName("Throat Yard");
			depot.setCorners(new Position(-45, -3, -5), new Position(5, 3, 15));
			station.setName("Terminus");
			station.setCorners(new Position(-5, -3, -5), new Position(105, 3, 25));
			sim.rails.add(y1);
			sim.rails.add(y2);
			sim.rails.add(throat);
			sim.rails.add(p1);
			sim.rails.add(p2);
			sim.depots.add(depot);
			sim.sidings.add(siding1);
			sim.sidings.add(siding2);
			sim.stations.add(station);
			sim.platforms.add(platform1);
			sim.platforms.add(platform2);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			siding1.setVehicleCars(cars);
			siding2.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			assertTrue(depot.savedRails.contains(siding1), "siding 1 must attach to the yard");
			assertTrue(depot.savedRails.contains(siding2), "siding 2 must attach to the yard");
			assertTrue(station.savedRails.contains(platform1), "platform 1 must attach to the station");
			assertTrue(station.savedRails.contains(platform2), "platform 2 must attach to the station");
			siding1.tick();
			siding2.tick();
		}

		Vehicle spawn(Siding siding) {
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, new BranchStore(), null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			return vehicle;
		}

		/** One deterministic second: both sidings simulate against the SHARED occupancy trees. */
		void tick() {
			trees.removeFirst();
			trees.add(new Object2ObjectAVLTreeMap<>());
			siding1.simulateVehicles(1000, trees);
			siding2.simulateVehicles(1000, trees);
		}
	}

	private static MmtrMissionControl missionOp(Vehicle v, long targetSidingId) {
		final JsonObject json = new JsonObject();
		json.addProperty("vehicleId", String.valueOf(v.getId()));
		json.addProperty("kind", "PASSENGER");
		json.addProperty("targetSidingId", String.valueOf(targetSidingId));
		json.addProperty("executor", "AUTOPILOT");
		json.addProperty("startNow", true);
		return new MmtrMissionControl(new JsonReader(json));
	}

	@Test
	public void conflictingRoutesQueueAtTheThroatAndTheSecondSetsAfterTheFirstClears() {
		final Net n = new Net("build/mmtr-route-conflict");
		final Vehicle v1 = n.spawn(n.siding1);
		final Vehicle v2 = n.spawn(n.siding2);
		final String owner1 = "v" + v1.getId();

		assertTrue(missionOp(v1, n.platform1.getId()).dispatch(n.sim), "train 1 mission dispatches");
		n.tick();
		assertTrue(v1.isMmtrMotionAuto(), "train 1 armed: its route is set");
		final MmtrRoute route1 = v1.getMmtrRoute();
		assertNotNull(route1, "train 1 published its route");
		assertEquals(MmtrRoute.Kind.MAIN, route1.getKind());
		assertTrue(route1.isEstablished(), "train 1 route is SET");
		assertTrue(route1.coversRail(n.throat.getHexId()), "the route runs over the throat");
		assertTrue(n.sim.mmtrPointAuthority.isGrantedTo(n.fork.getX(), n.fork.getY(), n.fork.getZ(), n.throat.getHexId(), owner1),
			"train 1 holds the throat fork");

		assertTrue(missionOp(v2, n.platform2.getId()).dispatch(n.sim), "train 2 mission dispatches");
		n.tick();
		final MmtrRoute route2 = v2.getMmtrRoute();
		assertNotNull(route2, "train 2 published its route too");
		assertFalse(route2.isEstablished(), "the conflicting route stays PENDING while train 1 holds the point");
		/*
		 * T1（2026-09-14）：等待理由改口径，但**不是措辞变了而已 —— 阻挡点换了**。
		 *
		 * 闸口 N={@code -20,0,0} 是一处单开道岔（{@code y1}/{@code throat} 是直股对、{@code y2} 是岔股）：
		 * train 1 走 y1→throat 要**位置 0**，train 2 走 y2→throat 要**位置 1**，两者物理互斥。
		 * 修前两列车各自拿"自己那一行"的授权，直到 M={@code 0,0,0} 的岔口才排队 —— 也就是
		 * **两列车同时"持有"一处物理上不可能同时成立的道岔**；现在物理层在 N 就把它们分开，
		 * 后车停在**咽喉口**（自己的股道上）而不是先开进咽喉再等。所以断言从"点名 holder=vN@leg"
		 * 改成"点名**前车**" + "点名道岔坐标" + "点名互斥的位置"，判据本身没有放松。
		 */
		assertTrue(route2.getStateReason().contains(owner1), "the wait names the holding train: " + route2.getStateReason());
		assertTrue(route2.getStateReason().contains(
				n.throatEntry.getX() + "," + n.throatEntry.getY() + "," + n.throatEntry.getZ()),
			"the wait names the point that blocks it: " + route2.getStateReason());
		assertTrue(route2.getStateReason().contains("本车需要位置 1"),
			"the wait names the mutually exclusive position it needs: " + route2.getStateReason());
		assertFalse(v2.isMmtrMotionAuto(), "a train whose route is not set never arms");
		assertEquals(n.y2.getHexId(), v2.getMmtrMotionWalker().railHex(), "train 2 waits in its siding");
		assertEquals(route1, n.sim.mmtrRoutes.routeOverRail(n.p1.getHexId()), "train 1's SET route covers its target rail");

		// Train 1 runs to its platform. Until it crosses the fork, train 2 must not move at all.
		boolean v1ClearedThroat = false;
		boolean v2ArmedEarly = false;
		int guard = 0;
		while (guard++ < 6000 && (v1.getMmtrMission() == null || v1.getMmtrMission().getState() != MmtrMission.State.AT_TARGET)) {
			n.tick();
			if (!n.throat.getHexId().equals(v1.getMmtrMotionWalker().railHex())) {
				v1ClearedThroat = true;
			}
			if (v2.isMmtrMotionAuto() && !v1ClearedThroat) {
				v2ArmedEarly = true;
			}
			if (!v1ClearedThroat) {
				assertEquals(n.y2.getHexId(), v2.getMmtrMotionWalker().railHex(), "train 2 stays in its siding while train 1 holds the throat");
			}
		}
		assertFalse(v2ArmedEarly, "train 2 never arms before train 1 clears the point");
		assertNotNull(v1.getMmtrMission(), "train 1 mission present");
		assertEquals(MmtrMission.State.AT_TARGET, v1.getMmtrMission().getState(), "train 1 arrived at platform 1");
		assertEquals(n.p1.getHexId(), v1.getMmtrMotionWalker().railHex(), "train 1 is on platform 1");
		assertTrue(v1ClearedThroat, "train 1 crossed the throat fork");

		// The fork is free again: train 2's route sets and its mission runs to platform 2.
		guard = 0;
		while (guard++ < 6000 && (v2.getMmtrMission() == null || v2.getMmtrMission().getState() != MmtrMission.State.AT_TARGET)) {
			n.tick();
		}
		assertNotNull(v2.getMmtrMission(), "train 2 mission present");
		assertEquals(MmtrMission.State.AT_TARGET, v2.getMmtrMission().getState(), "train 2 arrived at platform 2 after the queue");
		assertEquals(n.p2.getHexId(), v2.getMmtrMotionWalker().railHex(), "train 2 is on platform 2");
		assertTrue(route2.isEstablished() || n.sim.mmtrRoutes.route(v2.getId()) == null, "train 2's route was set (or already released on arrival)");
		assertNull(n.sim.mmtrPointAuthority.holder(n.fork.getX(), n.fork.getY(), n.fork.getZ(), n.throat.getHexId()),
			"no train holds the throat fork once both have crossed");
	}

	@Test
	public void aFollowingMovementOnTheSameLegIsNotAHostileRoute() {
		// Two routes over the same fork with the SAME leg (a following movement) are not hostile: the
		// point authority still serialises the crossing, but nothing about train 1's route blocks train
		// 2's - and once the point is free the second train runs and is then kept apart by block
		// signalling (S1), stopping at the section boundary behind the first train.
		final Net n = new Net("build/mmtr-route-follow");
		final Vehicle v1 = n.spawn(n.siding1);
		final Vehicle v2 = n.spawn(n.siding2);

		assertTrue(missionOp(v1, n.platform1.getId()).dispatch(n.sim), "train 1 mission dispatches");
		n.tick();
		assertTrue(v1.getMmtrRoute().isEstablished(), "train 1 route SET");
		assertTrue(missionOp(v2, n.platform1.getId()).dispatch(n.sim), "train 2 mission to the SAME platform dispatches");
		n.tick();
		assertNotNull(v2.getMmtrRoute(), "train 2 published its route");
		assertFalse(v2.isMmtrMotionAuto(), "train 2 waits for the exclusive point, not for a hostile route");
		assertEquals(n.y2.getHexId(), v2.getMmtrMotionWalker().railHex(), "train 2 waits in its siding");

		// Train 1 crosses the fork: the point frees, train 2 arms and follows.
		boolean v2Armed = false;
		int guard = 0;
		while (guard++ < 8000 && !v2Armed) {
			n.tick();
			v2Armed = v2.isMmtrMotionAuto();
		}
		assertTrue(v2Armed, "the following train arms as soon as the point is free");
		assertEquals(n.p1.getHexId(), v1.getMmtrMotionWalker().railHex(), "train 1 had already crossed the fork onto its platform");
		int arrival = 0;
		while (arrival++ < 6000 && v1.getMmtrMission().getState() != MmtrMission.State.AT_TARGET) {
			n.tick();
		}
		assertEquals(MmtrMission.State.AT_TARGET, v1.getMmtrMission().getState(), "train 1 reached its platform first");

		// It then draws up to the block boundary behind train 1 and stops - it never boards platform 1.
		boolean heldBehind = false;
		for (int i = 0; i < 3000 && !heldBehind; i++) {
			n.tick();
			heldBehind = v2.isMmtrBlockHeldFromSync() && v2.getSpeed() == 0;
		}
		assertTrue(heldBehind, "S1 holds the follower at the section boundary, rail=" + v2.getMmtrMotionWalker().railHex());
		assertEquals(n.throat.getHexId(), v2.getMmtrMotionWalker().railHex(), "the follower stays on the throat, one section behind");
		assertEquals(n.p1.getHexId(), v1.getMmtrMotionWalker().railHex(), "train 1 still stands on platform 1");
	}
}
