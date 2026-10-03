package org.mtr.core.data;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.mmtr.point.MmtrPointAuthority;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.point.MmtrTurnout;
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
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000}]"
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

		/*
		 * 收尾：人工搬岔 = 覆盖 + **锁定**（notes/130），锁随存档落盘 —— 不清的话下一跑一开始
		 * 这道岔就是锁着的，第 195 行那条"任务先拿到授权"会当场红（实测过）。
		 */
		final org.mtr.core.mmtr.point.MmtrTurnout turnout = n.sim.mmtrTurnout(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ());
		assertNotNull(turnout, "这个岔口现在是真道岔（有物理模型）");
		for (final String via : new String[]{turnout.stemRailHex, turnout.farRailHex, turnout.branchRailHex}) {
			authority.unlock(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), via);
		}
		n.sim.mmtrSetTurnoutPosition(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), org.mtr.core.mmtr.point.MmtrTurnout.NORMAL);
		n.sim.persistMmtrPointBranches();
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

	/**
	 * 锁是"永久生效直到解锁"（用户 2026-09-14）—— 那就意味着**锁定与解锁都必须落盘**。
	 *
	 * <p>实测踩到的坑：{@code mmtrPointUnlock} 早先只改内存、不落盘，于是"解锁"只在当前进程里成立，
	 * 重启后锁原样回来 —— 现场表现是"网页上锁闭显示 0，重启又全回来了"。这条测试把两个方向都钉死：
	 * 锁要能重启后还在，解锁也要能重启后不回来。</p>
	 *
	 * <p>顺带钉住"一处道岔三条进向"这件事：解一条进向不算解开这处道岔（
	 * {@link MmtrPointAuthority#isTurnoutLocked} 是"任一进向锁着即算锁着"）。</p>
	 */
	@Test
	public void aLockSurvivesARestartAndAnUnlockSurvivesItToo() {
		final String savePath = "build/mmtr-point-lock-persist";
		final Net n = new Net(savePath);
		final long x = n.yardMouth.getX();
		final long y = n.yardMouth.getY();
		final long z = n.yardMouth.getZ();
		final MmtrPointAuthority authority = n.sim.mmtrPointAuthority;

		// 人工搬岔 = 设位置 + 锁三条进向 + 落盘
		assertTrue(n.sim.mmtrSetPoint(x, y, z, n.yardRail.getHexId(), 1), "operator throws the mouth fork to rY");
		final MmtrTurnout turnout = n.sim.mmtrTurnout(x, y, z);
		assertNotNull(turnout, "这个岔口现在是真道岔（有物理模型）");
		assertTrue(authority.isTurnoutLocked(x, y, z, turnout), "人工搬岔把一处道岔锁上（三条进向一起锁）");

		// "重启" = 同一存档目录再起一个 Simulator（构造时就 loadLocks）
		final Net restarted = new Net(savePath);
		assertTrue(restarted.sim.mmtrPointAuthority.isTurnoutLocked(x, y, z, restarted.sim.mmtrTurnout(x, y, z)),
			"锁是持久的：重启后仍然锁着（不许静默解锁）");

		// 解一条进向**不算**解开这处道岔：一处道岔只有一个位置，三条进向任一锁着就不许自动扳
		final int lockedBefore = authority.locksSnapshot().size();
		n.sim.mmtrPointUnlock(x, y, z, n.yardRail.getHexId());
		assertEquals(lockedBefore - 1, authority.locksSnapshot().size(), "只解掉了这一条进向的锁");
		assertTrue(authority.isTurnoutLocked(x, y, z, turnout), "只解一条进向还不算解开这处道岔");

		// 全解锁（含界面上没有对应进向行的那些键），并且**要落盘**
		assertEquals(lockedBefore - 1, n.sim.mmtrUnlockAllPoints(), "剩下两条进向的锁被一次清掉");
		assertTrue(authority.locksSnapshot().isEmpty(), "清完之后引擎手里一把锁都不剩");

		final Net restartedAgain = new Net(savePath);
		assertFalse(restartedAgain.sim.mmtrPointAuthority.isTurnoutLocked(x, y, z, restartedAgain.sim.mmtrTurnout(x, y, z)),
			"解锁也持久：重启后不再锁着（否则用户看到的\"解锁\"是假的）");

		// 收尾：位置回正线，别把人工位留给下一跑
		n.sim.mmtrSetTurnoutPosition(x, y, z, MmtrTurnout.NORMAL);
	}

	/**
	 * {@code point unlock --all} 要能清掉**界面表达不出来**的锁键，而且清完要落盘。
	 *
	 * <p>人工搬岔一次锁三条进向，而网页/指令是按"进向行"表达的 —— 那些没有对应行的键
	 * （旧世界遗留、或道岔改画之后行没了）在界面上永远点不到。只按界面逐行解就会留下死角，
	 * 重启后它们又回来了（现场存档里确实躺着这种键：网页显示 0 处锁闭，文件里 20 条）。</p>
	 */
	@Test
	public void unlockAllAlsoClearsLocksThatNoApproachRowCouldShow() {
		final String savePath = "build/mmtr-point-unlock-all";
		final Net n = new Net(savePath);

		// 一个界面上不可能点到的键：坐标上根本没有这个进向（没有行指向它）
		n.sim.mmtrPointLock(0, 0, 0, "no-such-approach-rail");
		final Net restarted = new Net(savePath);
		assertEquals(1, restarted.sim.mmtrPointAuthority.locksSnapshot().size(),
			"这条锁落盘了：重启后还在，而它在界面上没有任何一行可点（逐行解永远解不到它）");
		assertEquals(1, restarted.sim.mmtrUnlockAllPoints(), "全解锁把这种看不见的键也算进来");
		assertTrue(restarted.sim.mmtrPointAuthority.locksSnapshot().isEmpty(), "清完之后引擎手里一把锁都不剩");

		final Net restartedAgain = new Net(savePath);
		assertTrue(restartedAgain.sim.mmtrPointAuthority.locksSnapshot().isEmpty(), "全解锁也落盘了：重启后依然是空的");
	}
}
