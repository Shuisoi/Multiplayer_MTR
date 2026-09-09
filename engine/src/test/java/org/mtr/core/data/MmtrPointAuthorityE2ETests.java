package org.mtr.core.data;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.mmtr.point.MmtrPointAuthority;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
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
 * P3 acceptance 7-9 at the real engine seams: an AUTOPILOT motion mission now requests its
 * en-route turnouts through the turnout authority (MmtrPointAuthority - approach locking) instead
 * of writing operator presets into the store. The vehicle crosses each fork under its own grant,
 * the crossing auto-releases the point, an operator LOCK parks the fork (the mission stays
 * unarmed and waits - nothing auto-elects), a manual operator branch outranks the vehicle's own
 * grant at runtime, and two trains converging on one fork queue FIFO and cross in order.
 */
public final class MmtrPointAuthorityE2ETests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
		+ "}";

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/**
	 * Yard YR (-32..-20) -> mouth fork at (-20): {rX straight to (60,0,0) | rY diverge} -> fork at
	 * (60,0,0): {rP platform rail to 140 | rQ}. Platform sits on rP exactly as in a station.
	 * Each test gets its own save directory: mmtr-point-op writes persist mmtr-points.json into the
	 * save path, which the Simulator constructor loads back - a shared path would leak an operator
	 * setting from one scenario into the next.
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

		String owner(Vehicle v) {
			return "v" + v.getId();
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
	public void missionGrantsEveryForkCrossesAndAutoReleasesAfterwards() {
		final Net n = new Net("build/mmtr-point-auth-grant");
		final Vehicle v = n.spawn();
		final MmtrPointAuthority authority = n.sim.mmtrPointAuthority;

		assertTrue(missionOp(v, "PASSENGER", n.platform.getId()).dispatch(n.sim), "mission dispatch must succeed");
		assertTrue(v.isMmtrMotionAuto(), "auto run armed once every en-route fork was granted");
		// Approach locking: the mission holds both forks BEFORE the train leaves the yard.
		assertTrue(authority.isGrantedTo(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId(), n.owner(v)), "mouth fork granted to the mission vehicle");
		assertTrue(authority.isGrantedTo(n.node60.getX(), n.node60.getY(), n.node60.getZ(), n.rX.getHexId(), n.owner(v)), "second fork granted too");

		int guard = 0;
		while (guard++ < 8000 && (v.getMmtrMission() == null || v.getMmtrMission().getState() != MmtrMission.State.AT_TARGET)) {
			n.siding.simulateVehicles(1000, null);
		}
		assertNotNull(v.getMmtrMission(), "mission present after the run");
		assertEquals(MmtrMission.State.AT_TARGET, v.getMmtrMission().getState(), "arrived at the platform");
		assertEquals(n.rP.getHexId(), v.getMmtrMotionWalker().railHex(), "vehicle arrived on the platform rail");
		assertEquals(0, v.getSpeed(), 1e-9, "resting at the platform");

		// Both crossings auto-released their holds: the network is free for the next train.
		assertFalse(authority.isGrantedTo(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId(), n.owner(v)), "mouth hold released when the train crossed");
		assertFalse(authority.isGrantedTo(n.node60.getX(), n.node60.getY(), n.node60.getZ(), n.rX.getHexId(), n.owner(v)), "node60 hold released when the train crossed");
		assertNull(authority.holder(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId()), "no stale holder left on the mouth fork");
		assertEquals(MmtrPointAuthority.Result.GRANTED, authority.request(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId(), "next", 0, n.sim.getCurrentMillis() + 60_000), "a next train is granted immediately after the release");

		v.getMmtrMission().cancel();
		n.siding.simulateVehicles(1000, null);
		assertFalse(v.isMmtrMotionAuto(), "terminal mission handed the vehicle back to idle");
		assertFalse(authority.isGrantedTo(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId(), n.owner(v)), "terminal cleanup released every remaining hold of the mission owner");
	}

	@Test
	public void operatorLockedForkKeepsMissionUnarmedUntilUnlock() {
		final Net n = new Net("build/mmtr-point-auth-lock");
		final Vehicle v = n.spawn();
		final MmtrPointAuthority authority = n.sim.mmtrPointAuthority;
		// Operator parks the mouth fork BEFORE the mission arrives: 人工锁定, 自动申请排队.
		authority.lock(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId());

		assertTrue(missionOp(v, "PASSENGER", n.platform.getId()).dispatch(n.sim), "dispatch succeeds even while the fork is locked");
		assertFalse(v.isMmtrMotionAuto(), "auto NOT armed while an en-route fork is operator-locked");
		for (int i = 0; i < 30; i++) {
			n.siding.simulateVehicles(1000, null);
		}
		assertFalse(v.isMmtrMotionAuto(), "still waiting - nothing auto-elects around the locked fork");
		assertEquals(n.yardRail.getHexId(), v.getMmtrMotionWalker().railHex(), "the vehicle never left the yard rail");
		assertNull(authority.holder(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId()), "no grant while locked");

		authority.unlock(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId());
		int guard = 0;
		while (guard++ < 8000 && (v.getMmtrMission() == null || v.getMmtrMission().getState() != MmtrMission.State.AT_TARGET)) {
			n.siding.simulateVehicles(1000, null);
		}
		assertEquals(MmtrMission.State.AT_TARGET, v.getMmtrMission().getState(), "unlock lets the mission auto-takeover and complete");
		assertEquals(n.rP.getHexId(), v.getMmtrMotionWalker().railHex(), "arrived at the platform after the unlock");
	}

	@Test
	public void manualOperatorBranchOutranksTheVehiclesOwnGrant() {
		final Net n = new Net("build/mmtr-point-auth-manual");
		final Vehicle v = n.spawn();
		final MmtrPointAuthority authority = n.sim.mmtrPointAuthority;

		assertTrue(missionOp(v, "PASSENGER", n.platform.getId()).dispatch(n.sim), "mission dispatch must succeed");
		assertTrue(authority.isGrantedTo(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId(), n.owner(v)), "mission holds leg 0 (straight) at the mouth");
		// The operator then throws the fork the other way (人工搬岔优先级最高): the vehicle must
		// follow the operator rail even though its own auto grant still points straight.
		assertTrue(n.sim.mmtrSetPoint(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId(), 1), "operator throws the mouth fork to rY");

		int guard = 0;
		while (guard++ < 4000 && !n.rY.getHexId().equals(v.getMmtrMotionWalker().railHex())) {
			n.siding.simulateVehicles(1000, null);
		}
		assertEquals(n.rY.getHexId(), v.getMmtrMotionWalker().railHex(), "manual operator branch outranks the mission's own grant at runtime");
		// ③ 车尾清岔: the (unused) grant is consumed at the crossing, but the hold lasts until the tail
		// has cleared the junction's clearance zone - the points must not move under the trailing cars.
		guard = 0;
		while (guard++ < 4000 && authority.holder(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId()) != null) {
			n.siding.simulateVehicles(1000, null);
		}
		assertNull(authority.holder(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId()), "the hold was released once the consist cleared the junction");

		// rY ends at a dead end: the mission cannot reach its platform - cancel to clean up.
		v.getMmtrMission().cancel();
		n.siding.simulateVehicles(1000, null);
		assertFalse(v.isMmtrMotionAuto(), "manual override path handed the vehicle back to idle");
	}

	@Test
	public void twoTrainsQueueOnTheSameForkAndCrossInOrder() {
		final Net n = new Net("build/mmtr-point-auth-queue");
		final MmtrPointAuthority authority = n.sim.mmtrPointAuthority;
		final long until = n.sim.getCurrentMillis() + 300_000;

		final MmtrMotionWalker w1 = MmtrMotionWalker.start(n.sim, n.yardRail, n.yardBack, new BranchStore(), null);
		w1.setPointAuthority(authority, "t1");
		final MmtrMotionWalker w2 = MmtrMotionWalker.start(n.sim, n.yardRail, n.yardBack, new BranchStore(), null);
		w2.setPointAuthority(authority, "t2");

		// Both trains approach the same (mouth, yard rail) point for leg 0; t1 holds, t2 queues.
		assertEquals(MmtrPointAuthority.Result.GRANTED, authority.request(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId(), "t1", 0, until));
		assertEquals(MmtrPointAuthority.Result.QUEUED, authority.request(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId(), "t2", 0, until), "second train queues behind the first");

		// t2 arrives first: it must wait at the fork (approach lock), never auto-elect.
		w2.advance(13);
		assertTrue(w2.haltedAtAuthority(), "queued train waits at the fork");
		assertEquals(12, w2.offsetM(), 1e-6, "stopped exactly at the mouth node");

		// t1 arrives and crosses under its grant; ③ 车尾清岔 keeps the point held until its tail (plus the
		// junction clearance margin) has cleared the node, so t2 stays queued for now.
		w1.advance(13);
		assertEquals(n.rX.getHexId(), w1.railHex(), "holder train crossed onto the granted leg");
		assertFalse(w1.haltedAtAuthority());
		assertEquals("t1", authority.holder(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId()), "still held: t1's tail is inside the clearance zone");

		// Once t1's tail clears the clearance zone the point is released and t2 is promoted.
		w1.advance(org.mtr.core.data.Vehicle.MMTR_JUNCTION_CLEARANCE_M);
		assertEquals("t2", authority.holder(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId()), "queued train promoted to holder by the clearance release");

		// t2 retries the fork on its next advance and crosses in order.
		w2.advance(13);
		assertEquals(n.rX.getHexId(), w2.railHex(), "second train crossed onto the same leg after the first released");
		assertFalse(w2.haltedAtAuthority());
	}
}
