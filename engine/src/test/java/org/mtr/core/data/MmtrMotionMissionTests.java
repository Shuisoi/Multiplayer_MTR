package org.mtr.core.data;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.MmtrMission;
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
 * L3 slice 7: mission-driven motion run end to end. MmtrMissionControl dispatches a PASSENGER
 * AUTOPILOT mission against a motion vehicle; the control op plans the run to the target platform's
 * real rail (MmtrRunPlanner), presets the en-route turnouts, arms the auto step-run with doors; the
 * vehicle drives itself there, the mission state machine observes the exact stop (AT_TARGET), opens
 * doors for the dwell and completes - then the vehicle is handed back to idle (auto off, target
 * cleared). This closes the loop for MOVE_TO-style task steps on live Motion-Core vehicles.
 */
public final class MmtrMotionMissionTests {

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
	 * Yard YR (-32..-20) -> mouth fork {rX (to 60,0,0) | rY} -> fork at (60,0,0): {rP (platform rail
	 * to 140,0,0) | rQ}. A real Platform + Station sit over rP exactly as in a station.
	 */
	private static final class Net {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-mission"), false);
		final Position yardBack = new Position(-32, 0, 0);
		final Position yardMouth = new Position(-20, 0, 0);
		final Position node60 = new Position(60, 0, 0);
		final Rail yardRail = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Rail rX = through(yardMouth, node60);
		final Rail rY = through(yardMouth, new Position(60, 0, 14));
		final Rail rP = Rail.newPlatformRail(node60, Angle.fromAngle(0), new Position(140, 0, 0), Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Rail rQ = through(node60, new Position(140, 0, 14));
		final Depot depot = new Depot(TransportMode.TRAIN, sim);
		final Siding siding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);
		final Station station = new Station(sim);
		final Platform platform = new Platform(node60, new Position(140, 0, 0), TransportMode.TRAIN, sim);

		Net() {
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
			// Spawn against the simulator's authoritative BranchStore (what the mission control op
			// presets through MmtrRunPlanner.applyForkOps), so the live walker sees the presets.
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
	public void passengerMissionDrivesMotionVehicleToPlatformAndHoldsUntilTerminal() {
		final Net n = new Net();
		final Vehicle v = n.spawn();
		final MmtrMotionWalker walker = v.getMmtrMotionWalker();
		assertNotNull(walker, "motion walker");
		final double expectedStop = walker.distanceM()
			+ (n.yardRail.railMath.getLength() - walker.offsetM())
			+ n.rX.railMath.getLength()
			+ n.rP.railMath.getLength();

		assertTrue(missionOp(v, "PASSENGER", n.platform.getId()).dispatch(n.sim), "mission dispatch must succeed for a motion vehicle");
		assertNotNull(v.getMmtrMission(), "mission attached");
		assertEquals(MmtrMission.State.ASSIGNED, v.getMmtrMission().getState(), "fresh mission assigned");
		assertEquals(MmtrMission.Executor.AUTOPILOT, v.getMmtrMission().getExecutor(), "autopilot executor");
		assertTrue(v.isMmtrMotionAuto(), "auto step-run armed by the mission control");

		boolean doorsSeenWhileAtTarget = false;
		int guard = 0;
		while (guard++ < 8000 && (v.getMmtrMission() == null || v.getMmtrMission().getState() != MmtrMission.State.AT_TARGET)) {
			n.siding.simulateVehicles(1000, null);
			if (v.getMmtrMission() != null && v.getMmtrMission().getState() == MmtrMission.State.AT_TARGET && v.vehicleExtraData.getDoorMultiplier() > 0) {
				doorsSeenWhileAtTarget = true;
			}
		}
		assertNotNull(v.getMmtrMission(), "mission present after the run");
		assertEquals(MmtrMission.State.AT_TARGET, v.getMmtrMission().getState(), "mission must arrive AT_TARGET at the platform stop, mission=" + v.getMmtrMission().getState());
		// Keep sampling a few ticks after the arrival transition (the doors open once the vehicle
		// settles at the stop and stays open through the hold).
		for (int i = 0; i < 10 && !doorsSeenWhileAtTarget; i++) {
			n.siding.simulateVehicles(1000, null);
			if (v.vehicleExtraData.getDoorMultiplier() > 0) {
				doorsSeenWhileAtTarget = true;
			}
		}
		assertTrue(doorsSeenWhileAtTarget, "doors open while AT_TARGET (passenger service)");
		assertEquals(expectedStop, v.getRailProgress(), 0.05, "vehicle stopped exactly at the planned platform stop");
		assertEquals(n.rP.getHexId(), walker.railHex(), "vehicle arrived on the platform rail");
		assertEquals(0, v.getSpeed(), 1e-9, "resting at the platform");
		assertFalse(v.isMmtrManualOverride(), "no cab override involved");

		// The dwell -> COMPLETE transition is the shared legacy state machine (verified there); here
		// we end the mission through a terminal transition (cancel) and check the motion hand-back.
		v.getMmtrMission().cancel();
		n.siding.simulateVehicles(1000, null);
		assertFalse(v.isMmtrMotionAuto(), "auto run disabled after terminal mission");
		assertFalse(v.isMmtrMotionStoppedAtTarget(), "stop target consumed by the terminal mission");
		assertFalse(v.vehicleExtraData.getDoorMultiplier() > 0, "doors closed after the terminal mission");
	}

	@Test
	public void missionDispatchRefusedWithoutFeasibleMotionTarget() {
		final Net n = new Net();
		final Vehicle v = n.spawn();

		// Target = the vehicle's own yard siding rail: the planner refuses (already on it).
		assertFalse(missionOp(v, "MANEUVER", n.siding.getId()).dispatch(n.sim), "dispatch to the current rail must be refused");
		assertNull(v.getMmtrMission(), "no mission attached on refusal");

		// Unknown target id: refused too.
		assertFalse(missionOp(v, "MANEUVER", 999_999L).dispatch(n.sim), "dispatch to an unknown id must be refused");
		assertNull(v.getMmtrMission(), "still no mission attached");
		assertFalse(v.isMmtrMotionAuto(), "auto never armed on refusal");
	}

	@Test
	public void plainMissionAssignmentSelfArmsAndRunsWithoutControlOp() {
		final Net n = new Net();
		final Vehicle v = n.spawn();
		final MmtrMotionWalker walker = v.getMmtrMotionWalker();
		final double expectedStop = walker.distanceM()
			+ (n.yardRail.railMath.getLength() - walker.offsetM())
			+ n.rX.railMath.getLength()
			+ n.rP.railMath.getLength();

		// Whoever attaches the mission (scheduler / periodic source / ops) needs no arming calls:
		// the motion vehicle self-arms on its next tick.
		final MmtrMission mission = new MmtrMission(v.getId(), MmtrMission.Kind.PASSENGER, n.siding.getId(), n.platform.getId(), 0L);
		mission.setExecutor(MmtrMission.Executor.AUTOPILOT, null);
		assertTrue(v.setMmtrMission(mission), "mission attached directly");

		boolean doorsSeenWhileAtTarget = false;
		int guard = 0;
		while (guard++ < 8000 && (v.getMmtrMission() == null || v.getMmtrMission().getState() != MmtrMission.State.AT_TARGET)) {
			n.siding.simulateVehicles(1000, null);
		}
		assertNotNull(v.getMmtrMission(), "mission present");
		assertTrue(v.isMmtrMotionAuto(), "vehicle self-armed the auto step-run without any ops call");
		assertEquals(MmtrMission.State.AT_TARGET, v.getMmtrMission().getState(), "mission arrived AT_TARGET after the self-armed run");
		assertEquals(expectedStop, v.getRailProgress(), 0.05, "self-armed run stopped exactly at the planned platform stop");
		assertEquals(n.rP.getHexId(), walker.railHex(), "vehicle arrived on the platform rail");
		for (int i = 0; i < 10 && !doorsSeenWhileAtTarget; i++) {
			n.siding.simulateVehicles(1000, null);
			if (v.vehicleExtraData.getDoorMultiplier() > 0) {
				doorsSeenWhileAtTarget = true;
			}
		}
		assertTrue(doorsSeenWhileAtTarget, "doors open while AT_TARGET (passenger service)");

		v.getMmtrMission().cancel();
		n.siding.simulateVehicles(1000, null);
		assertFalse(v.isMmtrMotionAuto(), "auto off after the terminal mission");
	}

	@Test
	public void selfArmFailsMissionWhenTargetIsTheCurrentRail() {
		final Net n = new Net();
		final Vehicle v = n.spawn();
		// Target = this vehicle's own yard siding rail: the self-arm must fail the mission with a
		// reason instead of arming a nonsense run.
		final MmtrMission mission = new MmtrMission(v.getId(), MmtrMission.Kind.MANEUVER, n.siding.getId(), n.siding.getId(), 0L);
		mission.setExecutor(MmtrMission.Executor.AUTOPILOT, null);
		assertTrue(v.setMmtrMission(mission), "mission attached directly");
		n.siding.simulateVehicles(1000, null);
		n.siding.simulateVehicles(1000, null);
		assertNotNull(v.getMmtrMission(), "mission present");
		assertEquals(MmtrMission.State.FAILED, v.getMmtrMission().getState(), "self-arm must fail the mission for the current-rail target");
		assertFalse(v.isMmtrMotionAuto(), "auto never armed");
	}
}
