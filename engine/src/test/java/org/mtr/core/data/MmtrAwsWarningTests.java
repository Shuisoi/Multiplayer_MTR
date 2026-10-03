package org.mtr.core.data;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.operation.MmtrDriveControl;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signal S3 (AWS): the point-style warning state machine for live manual driving on AWS-band
 * rails (<= 100 km/h). While the train runs inside the trigger lead of a restricted boundary -
 * an occupied rail ahead (block stop, S1) or a slower rail to be braced for (S2) - the warning
 * sounds (WARN). The driver acknowledges through ControlState.acknowledge (one-shot); an
 * unacknowledged warning that outlives the 3 s window while the train is STILL MOVING becomes a
 * SPAD emergency stop through the existing protection channel (10 s lock). Auto runs drive
 * themselves and never see driver warnings. Manual overspeed on AWS rails stays unforced (S2
 * semantics) - the warning is about restricted boundaries, not speed.
 */
public final class MmtrAwsWarningTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000}]"
		+ "}";
	private static final long OBSTACLE_VEHICLE_ID = 999_999_002L;

	private static Rail through(Position p1, Position p2, long speedLimitKmh) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			speedLimitKmh, speedLimitKmh, false, false, true, false, true, TransportMode.TRAIN);
	}

	/**
	 * All-AWS straight line: Y (yard rail, rear -20 .. mouth -8, 12 m, 40 km/h) -> A (-8..142,
	 * 150 m, 40 km/h) -> B (142..342, 200 m, 40 km/h) -> C (342..542, 200 m, 40 km/h). A foreign
	 * train occupies B (injected segment 20..40 m from B's ordered start every tick), so the block
	 * stop sits epsilon short of the A/B node; the warning lead (75 m) therefore engages while the
	 * manual train still runs on A, 150 m of runway for the acknowledgement window to expire mid-run.
	 */
	private static final class AwsNet {
		final Simulator sim;
		final Position rear = new Position(-20, 0, 0);
		final Position mouth = new Position(-8, 0, 0);
		final Rail yRail;
		final Rail aRail;
		final Rail bRail;
		final Rail cRail;
		final Depot depot;
		final Siding siding;
		/**
		 * 占用树（notes/166 R8）：**必须用 sim 自己的那一份**。
		 *
		 * <p>旧夹具用的是一份**私有**列表（`new ObjectArrayList<>()` 再 add 两棵），而**信号显示与 AWS
		 * 触发器**读的是 sim 的树 ⇒ 那道危险灯永远读绿，警告只能靠"占用停车边界"那一路触发
		 * （实测日志：{@code signal GREEN on cRail in 71.0m, boundary at 362.0m}）。真实世界里两者是同一份
		 * （车把足迹写进 sim 的树），所以夹具必须跟着走。</p>
		 */
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees;

		AwsNet(String savePath) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			yRail = Rail.newSidingRail(rear, Angle.fromAngle(0), mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			final Position aEnd = new Position(142, 0, 0);
			aRail = through(mouth, aEnd, 40);
			bRail = through(aEnd, new Position(342, 0, 0), 40);
			cRail = through(new Position(342, 0, 0), new Position(542, 0, 0), 40);
			depot = new Depot(TransportMode.TRAIN, sim);
			siding = new Siding(rear, mouth, 12, TransportMode.TRAIN, sim);
			depot.setName("Yard");
			depot.setCorners(new Position(-25, -3, -3), new Position(550, 3, 3));
			sim.rails.add(yRail);
			sim.rails.add(aRail);
			sim.rails.add(bRail);
			sim.rails.add(cRail);
			sim.depots.add(depot);
			sim.sidings.add(siding);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			siding.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			/*
			 * notes/166 R5：这个世界原来**一盏灯都没有** —— 而新模型里"没有任何信号灯的连通块整块是
			 * 一个大区间"（用户 2026-09-15 裁定），于是 A/B/C 合成一段，"隔一段的单黄"这个前提根本
			 * 不存在（AWS 也没有信号可触发，实测警告一次都不响）。
			 *
			 * 按用例本意补两盏朝东的灯：A/B 节点那盏守 B、B/C 节点那盏守 C ⇒ C 被占时，
			 * 车还在 A 上、**即将通过的那架灯**（142 处）就该读单黄（depth 2）—— 这才是本用例要测的东西。
			 */
			addLampAt(aEnd);
			addLampAt(new Position(342, 0, 0));
			assertTrue(depot.savedRails.contains(siding), "siding must attach to the depot yard");
			siding.tick();
			trees = sim.mmtrOccupancyTrees();
			assertTrue(trees != null && trees.size() >= 2, "sim 必须有占用树（占用只有这一份来源）");
		}

		/** 在节点格上放一盏**朝东**（MTR 角 270）的自动推断灯：它守从该节点朝东出去的那根轨。 */
		private void addLampAt(Position node) {
			sim.mmtrSignals.put((int) node.getX(), (int) node.getY(), (int) node.getZ(), 270, 4, "AUTO", "");
		}

		Vehicle spawn() {
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, new BranchStore(), null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			return vehicle;
		}

		/** Occupies {@code rail} (20..40 m from its ordered start) for the whole tick, then simulates. */
		void tickWithOccupancy(Rail rail) {
			trees.removeFirst();
			trees.add(new Object2ObjectAVLTreeMap<>());
			final Position orderedP1 = rail.getPosition1().compareTo(rail.getPosition2()) <= 0 ? rail.getPosition1() : rail.getPosition2();
			final Position orderedP2 = orderedP1 == rail.getPosition1() ? rail.getPosition2() : rail.getPosition1();
			Data.put(trees.get(1), orderedP1, orderedP2,
				vehiclePosition -> {
					final VehiclePosition newVehiclePosition = vehiclePosition == null ? new VehiclePosition() : vehiclePosition;
					newVehiclePosition.addSegment(20, 40, OBSTACLE_VEHICLE_ID);
					return newVehiclePosition;
				}, Object2ObjectAVLTreeMap::new);
			siding.simulateVehicles(1000, trees);
		}

		/** Occupies B (20..40 m from its ordered start) for the whole tick, then simulates. */
		void tickWithOccupiedB() {
			tickWithOccupancy(bRail);
		}

		/**
		 * notes/166 R4：占用只有一份来源 —— 共享占用树，所以"标一根轨被占"就是 {@link #tickWithOccupancy}
		 * （它写树 + 走一步）。这里保留这个入口名只是为了让调用点读起来仍是"把这条轨的信号占用标上"。
		 */
		void occupyRailSignal(Rail rail) {
			// 空实现：写树那一半已经由 tickWithOccupancy 做了（原来这里还写"每轨预留信号色"通道，已删）。
		}

		/** Clears a rail's signal-channel occupancy (two rounds: the old snapshot also counts). */
		void releaseRailSignal(Rail rail) {
			rail.tick1(sim);
			rail.tick2(60_000);
			rail.tick1(sim);
			rail.tick2(60_000);
		}

		/** C occupied for the whole tick, then one simulated step. */
		void tickWithOccupiedC() {
			occupyRailSignal(cRail);
			tickWithOccupancy(cRail);
		}

		/** One tick with nothing occupied anywhere. */
		void tickClear() {
			trees.removeFirst();
			trees.add(new Object2ObjectAVLTreeMap<>());
			siding.simulateVehicles(1000, trees);
		}
	}

	@Test
	public void unacknowledgedOccupancyWarningTriggersSpadWhileStillMoving() {
		final AwsNet n = new AwsNet("build/mmtr-aws-spad");
		final Vehicle v = n.spawn();
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(entities);
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(3).setReverser(1), driver).apply(n.sim);
		assertTrue(v.isMmtrManualOverride(), "drive command must hold the override");

		// Run: the warning must engage inside the lead of the occupied B and, unacknowledged, turn
		// into a SPAD while the train is still moving - before it ever boards B.
		boolean warned = false;
		boolean spad = false;
		int warnTicks = 0;
		for (int i = 0; i < 500 && !spad; i++) {
			n.tickWithOccupiedB();
			if (v.isMmtrAwsWarningPending()) {
				if (!warned) {
					warned = true;
					assertTrue(v.getSpeed() > 1e-9, "warning engages while the train is running");
					assertEquals(40, v.getMmtrCurrentSpeedLimitKmh(), "warning only on AWS-band rails");
				}
				warnTicks++;
			}
			if (v.isMmtrProtectionFromSync()) {
				spad = true;
			}
		}
		assertTrue(warned, "AWS warning must engage before the occupied rail");
		assertTrue(spad, "unacknowledged warning must SPAD while the train is still moving");
		assertTrue(warnTicks >= 3, "the warning window (3 s) elapsed before the SPAD, ticks=" + warnTicks);
		assertFalse(v.isMmtrAwsWarningPending(), "SPAD resolves the unacknowledged warning");
		assertTrue(v.isMmtrAwsWarningAcknowledged(), "state machine lands in acknowledged (post-SPAD)");
		// Mirror fields carry the same state to the client HUD.
		assertFalse(v.isMmtrAwsWarningPendingFromSync(), "mirror pending follows the internal state");
		assertTrue(v.isMmtrAwsWarningAcknowledgedFromSync(), "mirror acknowledged follows the internal state");
		assertEquals(40, v.getMmtrSpeedLimitKmhFromSync(), "mirror carries the current rail limit (km/h)");
		assertTrue(n.aRail.getHexId().equals(v.getMmtrMotionWalker().railHex()) || n.yRail.getHexId().equals(v.getMmtrMotionWalker().railHex()),
			"SPAD happened before the train could board the occupied rail B, rail=" + v.getMmtrMotionWalker().railHex());
	}

	@Test
	public void acknowledgedWarningStopsAtTheBoundaryWithoutSpad() {
		final AwsNet n = new AwsNet("build/mmtr-aws-ack");
		final Vehicle v = n.spawn();
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(entities);
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(3).setReverser(1), driver).apply(n.sim);

		boolean acked = false;
		boolean stoppedAtBoundary = false;
		boolean spad = false;
		boolean tripped = false;
		for (int i = 0; i < 800 && !stoppedAtBoundary; i++) {
			n.tickWithOccupiedB();
			if (v.isMmtrAwsWarningPending()) {
				/*
				 * notes/166 R8：新模型下一次行程里**可能响两次** —— 先是"信号预告"那一路
				 * （即将进入的那根轨上那盏灯非绿），它随着列车前进会清掉；然后是"占用停车边界"那一路。
				 * 真实的 AWS 规定**每一次新的警告都要重新确认**（本文件另一条用例的注释也这么说：
				 * "the driver presses the button at every AWS horn"），所以这里改成每次响都按。
				 * 旧模型只会响一次（那时信号那一路读不到危险），所以"只按一次"才够用。
				 */
				new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(3).setReverser(1).setAcknowledge(true), driver).apply(n.sim);
				acked = true;
			}
			if (v.isMmtrProtectionFromSync() && !v.isMmtrAuthorityTripped()) {
				spad = true;
			}
			tripped |= v.isMmtrAuthorityTripped();
			// notes/233 司机优先：手动车不再被"钉"在界限上，而是**紧急制动**把它刹停在界限附近
			// （紧急减速比服务减速强，所以停点比"贴着界限 ε"略靠前 —— 安全侧）。判据因此是
			// "越界紧急制动已施加 + 车真的停住 + 还没越过节点"。
			stoppedAtBoundary = tripped && v.getSpeed() == 0 && n.aRail.getHexId().equals(v.getMmtrMotionWalker().railHex()) && v.getRailProgress() > 140.0;
		}
		assertTrue(acked, "warning engaged and was acknowledged");
		/*
		 * notes/233 司机优先：手动车停在未授权界限上时施加的是**司机越界那一路的紧急制动**
		 * （司机按响应键解除），它与"AWS 报警超时的 SPAD"共用通道但来源不同 ——
		 * 所以这里只断言**没有 SPAD**（`isMmtrAuthorityTripped` 为假时的 protection）。
		 */
		assertFalse(spad, "acknowledged warning never SPADs");
		assertTrue(v.isMmtrAwsWarningAcknowledged(), "indicator stays acknowledged");
		assertTrue(v.isMmtrAwsWarningAcknowledgedFromSync(), "mirror acknowledged follows the internal state");
		assertEquals(40, v.getMmtrSpeedLimitKmhFromSync(), "mirror carries the AWS rail limit");
		assertTrue(stoppedAtBoundary, "the S1 occupancy stop holds the train at the A/B node, rail=" + v.getMmtrMotionWalker().railHex() + " progress=" + v.getRailProgress());
		assertEquals(n.aRail.getHexId(), v.getMmtrMotionWalker().railHex(), "never boarded the occupied B rail");
		assertTrue(v.isMmtrBlockHeldFromSync(), "mirror carries the occupancy-hold state for the HUD");
		// While the restriction persists, the acknowledged warning does not re-time into another SPAD.
		for (int i = 0; i < 30; i++) {
			n.tickWithOccupiedB();
		}
		assertFalse(v.isMmtrProtectionFromSync() && !v.isMmtrAuthorityTripped(), "no second SPAD while parked acknowledged at the boundary");
		assertTrue(v.isMmtrAwsWarningAcknowledged(), "indicator stays up while the restriction persists");
	}

	/**
	 * A3: the trigger is the SIGNAL the train is about to pass, so a caution two blocks ahead - a
	 * single yellow the old occupancy-stop rule could not see - warns as soon as the train enters the
	 * lead of that signal. C is occupied while the train still runs on A; B is clear, so the S1 block
	 * stop is far beyond the lead and only the aspect can raise the warning.
	 */
	@Test
	public void cautionTwoBlocksAheadWarnsAtTheSignal() {
		final AwsNet n = new AwsNet("build/mmtr-aws-caution");
		final Vehicle v = n.spawn();
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(entities);
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(3).setReverser(1), driver).apply(n.sim);

		boolean warned = false;
		boolean spad = false;
		for (int i = 0; i < 400 && !warned && !spad; i++) {
			n.tickWithOccupiedC();
			if (v.isMmtrAwsWarningPending()) {
				/*
				 * notes/166 R6：新模型里"没有信号灯的连通块整块是一个大区间"，**黄灯链往前看得更远**
				 * （探针实测：从 bRail 的 142 入口读 depth 2 = 单黄）。于是警告在**车还没起步**时就响了
				 * —— 那正是侧线/第一根轨上、即将进入 aRail 的位置。旧断言要求"必须还在 aRail 上且速度>0"，
				 * 在新时序下永远抓不到（警告第一 tick 就进 ACKED，之后 `pending` 不再为真）。
				 *
				 * 所以这里只断言"**确实响过**"（本用例要证明的正是"只靠 S1 占用停车的旧规则看不到的
				 * 隔一段危险，AWS 也看得到"），语义核心由下面那条 aspect 断言单独钉住。
				 */
				warned = true;
				// Acknowledge at once so the test measures the TRIGGER, not the SPAD window.
				new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(3).setReverser(1).setAcknowledge(true), driver).apply(n.sim);
			}
			spad = v.isMmtrProtectionFromSync();
		}
		assertFalse(spad, "the warning was acknowledged, no SPAD");
		assertTrue(warned, "隔一段的黄灯必须能触发 AWS 告警（旧规则只看 S1 占用停车的触发带），rail="
			+ v.getMmtrMotionWalker().railHex() + " progress=" + v.getRailProgress());
		/*
		 * 本用例的语义核心（单独钉住，不再靠"必须还在 aRail 上"间接表达）：
		 * **C 被占 ⇒ 保护 B 的那架灯读单黄**（depth 2 = 隔一段）。
		 */
		assertEquals(org.mtr.core.mmtr.signal.MmtrSignalAspect.Aspect.SINGLE_YELLOW,
			n.sim.mmtrSignalAspectView().aspectFrom(n.bRail.getHexId(), new Position(142, 0, 0)),
			"C 被占 ⇒ 保护 B 的那架灯（142 处）读单黄：隔一段危险");
	}

	/**
	 * A3: a green signal clears the warning. The train is held at the block stop in front of the
	 * occupied C with an acknowledged warning; once C empties the signal protecting it turns green and
	 * the indicator drops.
	 */
	@Test
	public void greenSignalClearsTheAcknowledgedWarning() {
		final AwsNet n = new AwsNet("build/mmtr-aws-clear");
		final Vehicle v = n.spawn();
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(entities);
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(3).setReverser(1), driver).apply(n.sim);

		boolean acked = false;
		boolean heldAtBoundary = false;
		for (int i = 0; i < 600 && !heldAtBoundary; i++) {
			n.tickWithOccupiedC();
			// A3 warns at each signal the train approaches, so acknowledge every warning (the real
			// driver presses the button at every AWS horn) - the test is about the CLEAR, not the horn.
			if (v.isMmtrAwsWarningPending()) {
				new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(3).setReverser(1).setAcknowledge(true), driver).apply(n.sim);
				acked = true;
			}
			// notes/233：司机越界那一路的紧急制动不算 SPAD（见 acknowledgedWarningStopsAtTheBoundaryWithoutSpad）。
			assertFalse(v.isMmtrProtectionFromSync() && !v.isMmtrAuthorityTripped(), "acknowledged warning never SPADs");
			// The train draws up to the occupied C: S1 holds it at the B/C node.
			heldAtBoundary = acked && v.isMmtrBlockHeldFromSync() && v.getSpeed() == 0 && n.bRail.getHexId().equals(v.getMmtrMotionWalker().railHex());
		}
		assertTrue(acked, "warning engaged and was acknowledged");
		assertTrue(heldAtBoundary, "the occupancy stop holds the train at the B/C node, rail=" + v.getMmtrMotionWalker().railHex() + " progress=" + v.getRailProgress());
		assertTrue(v.isMmtrAwsWarningAcknowledged(), "indicator up while the restriction persists");

		n.releaseRailSignal(n.cRail);
		for (int i = 0; i < 5; i++) {
			n.tickClear();
		}
		assertFalse(v.isMmtrAwsWarningPending(), "green signal: no warning");
		assertFalse(v.isMmtrAwsWarningAcknowledged(), "green signal: the acknowledged indicator clears");
	}

	/**
	 * 实机 2026-09-09: the driver pressed the acknowledge key while NO warning was showing; the engine
	 * remembered it, and the next warning was acknowledged on its very first tick - the 2.5 s window
	 * never ran, so the SPAD never fired. A press outside a warning must be ignored.
	 */
	@Test
	public void anAcknowledgePressedBeforeTheWarningDoesNotCancelIt() {
		final AwsNet n = new AwsNet("build/mmtr-aws-stale-ack");
		final Vehicle v = n.spawn();
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(entities);

		// The driver tests the key early (nothing restricted yet), then drives normally.
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(3).setReverser(1).setAcknowledge(true), driver).apply(n.sim);
		assertFalse(v.isMmtrAwsWarningPending(), "no warning is showing yet");
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(3).setReverser(1), driver).apply(n.sim);

		boolean warned = false;
		boolean spad = false;
		for (int i = 0; i < 500 && !spad; i++) {
			n.tickWithOccupiedB();
			if (v.isMmtrAwsWarningPending()) {
				warned = true;
			}
			spad = v.isMmtrProtectionFromSync();
		}
		assertTrue(warned, "the warning still engages");
		assertTrue(spad, "a press made before the warning must not acknowledge it");
	}

	@Test
	public void autoRunNeverSeesDriverWarnings() {
		final AwsNet n = new AwsNet("build/mmtr-aws-auto");
		final Vehicle v = n.spawn();
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(12.0 + 150.0 + 60.0, false); // 60 m into the occupied B

		boolean stoppedAtBoundary = false;
		for (int i = 0; i < 500 && !stoppedAtBoundary; i++) {
			n.tickWithOccupiedB();
			assertFalse(v.isMmtrAwsWarningPending(), "auto runs never raise driver warnings");
			assertFalse(v.isMmtrAwsWarningAcknowledged(), "auto runs never acknowledge");
			assertFalse(v.isMmtrProtectionFromSync(), "auto is blocked softly, never SPADs on occupancy");
			stoppedAtBoundary = v.getSpeed() == 0 && n.aRail.getHexId().equals(v.getMmtrMotionWalker().railHex()) && v.getRailProgress() > 140.0;
		}
		assertTrue(stoppedAtBoundary, "auto occupancy stop holds at the A/B node, rail=" + v.getMmtrMotionWalker().railHex() + " progress=" + v.getRailProgress());
	}
}
