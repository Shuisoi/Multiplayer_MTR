package org.mtr.core.data;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.mmtr.task.DriveToPlatformTask;
import org.mtr.core.mmtr.task.StationServiceTask;
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
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000}]"
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
		final org.mtr.core.mmtr.segment.MmtrMotionPosition walker = v.getMmtrMotionWalker();
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
		final org.mtr.core.mmtr.segment.MmtrMotionPosition walker = v.getMmtrMotionWalker();
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
	public void selfArmCompletesMissionWhenTargetIsTheCurrentRail() {		final Net n = new Net();
		final Vehicle v = n.spawn();
		// Target = this vehicle's own yard siding rail. C9: a task-driven cross-track run ends exactly
		// this way - the approach stopped at the coupler gap, the surgery (or the automatic couplers)
		// absorbed the rake standing there, and the target rail is the one the merged train now stands
		// on. "Already there" is arrival, not a failure; arming a run to where we stand would be the
		// nonsense the old behaviour avoided by failing.
		final MmtrMission mission = new MmtrMission(v.getId(), MmtrMission.Kind.MANEUVER, n.siding.getId(), n.siding.getId(), 0L);
		mission.setExecutor(MmtrMission.Executor.AUTOPILOT, null);
		assertTrue(v.setMmtrMission(mission), "mission attached directly");
		n.siding.simulateVehicles(1000, null);
		n.siding.simulateVehicles(1000, null);
		assertNotNull(v.getMmtrMission(), "mission present");
		assertEquals(MmtrMission.State.COMPLETE, v.getMmtrMission().getState(), "a mission whose target is the current rail is already there");
		assertFalse(v.isMmtrMotionAuto(), "no run is armed for a movement that is over");
	}

	/**
	 * **站台作业真的会停够计划的时间**（notes/155）。
	 *
	 * <p>现场问题：计划里每一站都写了"停留 30s"，但引擎里**没有任何地方读它**，而且站台作业自己
	 * 走的是"有目的地的任务"那条路 —— 车已经站在目标站台上 ⇒ 命中"已经在那儿了"的捷径 ⇒ **当 tick 完成**。
	 * 于是用户看到的正是"车到站一闪而过/根本没停"。</p>
	 *
	 * <p>两段判据：①{@code DRIVE_TO_PLATFORM} 是"到站停稳即完成"（停留归下一步，不该白停引擎默认那 5 秒）；
	 * ②站台作业在站台上要**开门、停够计划的 30 秒、关门**才完成（引擎默认 5 秒不许提前收工）。</p>
	 *
	 * <p>红证：把 {@code StationServiceTask.inPlace()} 改回默认的 false ⇒ 站台作业命中"已经在目标轨上"
	 * 的捷径、当 tick 完成，本用例第二段（门开着 / 还 AT_TARGET）立刻红。</p>
	 */
	@Test
	public void stationServiceHoldsTheDoorsForThePlannedDwellAtThePlatform() {
		final Net n = new Net();
		final Vehicle v = n.spawn();
		final MmtrMission drive = new MmtrMission(v.getId(), MmtrMission.Kind.PASSENGER, n.siding.getId(), n.platform.getId(), 0L);
		drive.setExecutor(MmtrMission.Executor.AUTOPILOT, null);
		drive.attachTask(new DriveToPlatformTask("drive", n.platform.getId(), 0L));
		assertTrue(v.setMmtrMission(drive), "drive mission attached");

		int guard = 0;
		while (guard++ < 8000 && drive.getState() != MmtrMission.State.AT_TARGET) {
			n.sim.step(1000);
		}
		assertEquals(MmtrMission.State.AT_TARGET, drive.getState(), "the drive step reached the platform");
		n.sim.step(1000);
		assertTrue(drive.isTerminal(), "开往站台到站即完成（停留是下一步的事），state=" + drive.getState());

		final long plannedDwell = 30_000L;
		final MmtrMission service = new MmtrMission(v.getId(), MmtrMission.Kind.PASSENGER, n.siding.getId(), n.platform.getId(), 0L);
		service.setExecutor(MmtrMission.Executor.AUTOPILOT, null);
		service.attachTask(new StationServiceTask("service", n.platform.getId(), 0L, plannedDwell));
		assertTrue(v.setMmtrMission(service), "service mission attached");

		n.sim.step(1000);
		assertEquals(MmtrMission.State.AT_TARGET, service.getState(), "station service is serving at the platform");
		assertTrue(v.vehicleExtraData.mmtrDoorsOpen(), "doors open for the station service");

		// 引擎默认停留只有 5 秒：计划说了 30 秒，就不许在 5 秒时收工。
		n.sim.step(6_000);
		assertEquals(MmtrMission.State.AT_TARGET, service.getState(), "the planned dwell outlives the engine default");
		assertTrue(v.vehicleExtraData.mmtrDoorsOpen(), "doors stay open through the planned dwell");

		n.sim.step(plannedDwell);
		assertEquals(MmtrMission.State.COMPLETE, service.getState(), "the stop ends once the planned dwell is served");
		assertFalse(v.vehicleExtraData.mmtrDoorsOpen(), "doors closed for departure");
	}

	/**
	 * **不在站台上就不许开门停站**（notes/155）。
	 *
	 * <p>"原地动作"这条近路对站台作业有个前提：车得**真在那个站台上**。少了这条判据，
	 * 上一步进站被撤活、车卡在半路时，下一步的站台作业会在半路开一次门、停够 30 秒、报"本站服务完成"
	 * —— 比不停更坏（时刻表看起来是对的，车却从没进过站）。</p>
	 *
	 * <p>红证：把 {@code Vehicle#mmtrStandsOnRail} 的判据去掉（原地动作不看出身），
	 * 本用例第一段就红：车在库里就把门开了。</p>
	 */
	@Test
	public void stationServiceInTheYardRunsToThePlatformInsteadOfOpeningTheDoorsWhereItStands() {
		final Net n = new Net();
		final Vehicle v = n.spawn();
		final MmtrMission service = new MmtrMission(v.getId(), MmtrMission.Kind.PASSENGER, n.siding.getId(), n.platform.getId(), 0L);
		service.setExecutor(MmtrMission.Executor.AUTOPILOT, null);
		service.attachTask(new StationServiceTask("service", n.platform.getId(), 0L, 5_000L));
		assertTrue(v.setMmtrMission(service), "service mission attached");

		n.sim.step(1000);
		assertFalse(v.vehicleExtraData.mmtrDoorsOpen(), "车还在库里，站台作业不许原地开门");
		assertTrue(v.isMmtrMotionAuto(), "不在站台上 ⇒ 照常规划一趟开过去");

		int guard = 0;
		while (guard++ < 8000 && service.getState() != MmtrMission.State.AT_TARGET) {
			n.sim.step(1000);
		}
		assertEquals(MmtrMission.State.AT_TARGET, service.getState(), "the run reached the platform");
		assertEquals(n.rP.getHexId(), v.getMmtrMotionWalker().railHex(), "vehicle arrived on the platform rail");
		n.sim.step(1000);
		assertTrue(v.vehicleExtraData.mmtrDoorsOpen(), "doors open once it really is at the platform");
	}

	/**
	 * **车离开世界时，它在道岔层的持有必须一起放掉**（notes/155 §11 现场）。
	 * <p>现场读数：清车重铺之后，六台新车的出发信号全红，理由是
	 * {@code point -170,-60,-161 wantLeg=0 needPos=0 | phys=v-1408677344944419170 现位=1} ——
	 * 而那个 id 的车早就不在世界里了。道岔的物理位置与排队是**按持有者 id 跨 tick 存活**的，
	 * 车主没了却不释放 ⇒ 幽灵持有者按着位置，谁也不动、也不让，整条咽喉死锁。</p>
	 *
	 * <p>红证：把 {@code Siding#clearVehicles} / 淘汰分支里的 {@code mmtrReleaseVehicleClaims}
	 * 去掉，本用例第二段红（位置还被那台已经不存在的车按着）。</p>
	 */
	@Test
	public void clearingTheFleetReleasesTheTurnoutHoldsOfEveryVehicle() {
		final Net n = new Net();
		final Vehicle v = n.spawn();
		final String owner = "v" + v.getId();
		final org.mtr.core.mmtr.MmtrRunPlanner.Plan plan = org.mtr.core.mmtr.MmtrRunPlanner.planToRail(n.sim, v, n.rP.getHexId(), 1.0);
		assertTrue(plan.feasible, "计划必须可行：" + plan.reason);
		assertTrue(v.armMmtrPointRun(n.sim, plan), "自臂必须拿到道岔");
		assertFalse(n.sim.mmtrPointAuthority.physicalHoldNodesOf(owner).isEmpty(), "它应当按着至少一处道岔位置");
		assertTrue(n.sim.mmtrPointAuthority.byOwner.containsKey(owner), "道岔层里应当有这个持有者");

		n.sim.mmtrClearAllVehicles();
		assertTrue(n.sim.mmtrPointAuthority.physicalHoldNodesOf(owner).isEmpty(),
			"车没了 ⇒ 位置不能还被它按着（幽灵持有者会把咽喉锁死）");
		assertFalse(n.sim.mmtrPointAuthority.byOwner.containsKey(owner), "排队/持有记录也要一起清");
	}
}
