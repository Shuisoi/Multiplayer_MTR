package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.MmtrRegime;
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
 * Signal S4 (LZB): continuous speed supervision for MANUAL driving on the high-speed band
 * (>= 101 km/h rails). Unlike AWS (advisory, S2/S3), the LZB ceiling is ENFORCED: traction can
 * never push the train past min(rail limit, consist ceiling) - overspeed decays at service
 * deceleration - and a slower rail ahead is braced for with the service envelope so the train
 * crosses the node at (about) the slower rail's limit. Cab data (ceiling / target speed / target
 * distance) is exposed for the future driver display. Air-brake consists keep the controller path.
 */
public final class MmtrLzbSupervisionTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":300,\"maxManualSpeedKmh\":300,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000},"
		// 三手柄机车（BR101 口径）：notes/264 的现场车 —— LZB 段的手柄比例必须决定出力。
		// 牵引力增速控制**关掉**（notes/265）：这条用例量的是"同一时刻两档手柄的出力比"，
		// 而增速让两档的**爬升时间**不同（20% 用 2 s、100% 用 10 s），同一拍比出来的比值就不再是
		// 手柄比例了。增速本身在 MmtrTractionRampTests 里单独钉。
		+ "{\"id\":\"loco\",\"controlMode\":\"THREE_HANDLE\",\"powerNotches\":97,\"brakeNotches\":11,"
		+ "\"massKg\":84000,\"rotatingMassFactor\":1.16,\"maxTractiveEffortN\":300000,\"maxPowerW\":6400000,"
		+ "\"serviceBrakeForceN\":150000,\"emergencyBrakeForceN\":210000,\"maxSpeedKmh\":220,\"manualMaxSpeedKmh\":220,"
		+ "\"resistanceAN\":1350,\"resistanceBN\":28,\"resistanceCN\":2.76,\"cruiseMaxKmh\":220,\"drivePercentSteps\":96,"
		+ "\"rheostaticBrakeForceN\":150000,\"afbUsesHandleAsCap\":true,\"tractionLagMillis\":100,\"tractionRampNPerSecond\":0}]"
		+ "}";
	private static final long OBSTACLE_VEHICLE_ID = 999_999_003L;

	private static double kmh(double speedKilometersPerHour) {
		return speedKilometersPerHour / 3600.0;
	}

	private static Rail through(Position p1, Position p2, long speedLimitKmh) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			speedLimitKmh, speedLimitKmh, false, false, true, false, true, TransportMode.TRAIN);
	}

	/**
	 * Y (yard rail, 12 m, 40 AWS) -> A (1400 m, limit 120 -> LZB band) -> B (200 m, limit 40 ->
	 * AWS band). The consist ceiling is 300, so on A the LZB ceiling is the rail limit (120) and
	 * manual traction must be capped there; approaching B the supervision must brace the train down
	 * to ~40 at the node; after crossing into B (AWS) the manual driver is free again.
	 */
	private static final class LzbNet {
		final Simulator sim;
		final Position rear = new Position(-20, 0, 0);
		final Position mouth = new Position(-8, 0, 0);
		final Rail yRail;
		final Rail aRail;
		final Rail bRail;
		final Depot depot;
		final Siding siding;
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = new ObjectArrayList<>();

		LzbNet(String savePath) {
			this(savePath, "emu");
		}

		LzbNet(String savePath, String consistTypeId) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			yRail = Rail.newSidingRail(rear, Angle.fromAngle(0), mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			final Position aEnd = new Position(1392, 0, 0);
			aRail = through(mouth, aEnd, 120);
			bRail = through(aEnd, new Position(1592, 0, 0), 40);
			depot = new Depot(TransportMode.TRAIN, sim);
			siding = new Siding(rear, mouth, 12, TransportMode.TRAIN, sim);
			depot.setName("Yard");
			depot.setCorners(new Position(-25, -3, -3), new Position(1600, 3, 3));
			sim.rails.add(yRail);
			sim.rails.add(aRail);
			sim.rails.add(bRail);
			sim.depots.add(depot);
			sim.sidings.add(siding);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			siding.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = consistTypeId;
			sim.sync();
			assertTrue(depot.savedRails.contains(siding), "siding must attach to the depot yard");
			siding.tick();
			trees.add(new Object2ObjectAVLTreeMap<>());
			trees.add(new Object2ObjectAVLTreeMap<>());
		}

		Vehicle spawn() {
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, new BranchStore(), null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			return vehicle;
		}

		void tick() {
			trees.removeFirst();
			trees.add(new Object2ObjectAVLTreeMap<>());
			siding.simulateVehicles(1000, trees);
		}
	}

	@Test
	public void manualLzbDriverIsEnforcedToTheRailCeilingAndBracesIntoTheSlowerRail() {
		final LzbNet n = new LzbNet("build/mmtr-lzb-manual");
		final Vehicle v = n.spawn();
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(entities);
		// 满油门档（notes/264 起 LZB 段的出力也走控制器 ⇒ **档位会缩放牵引力**，"推得动"就得给满档）。
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(7).setReverser(1), driver).apply(n.sim);

		double maxOnA = 0;
		double crossingSpeed = Double.MAX_VALUE;
		double maxOnB = 0;
		boolean crossed = false;
		boolean targetBecame40 = false;
		boolean cabDataOnA = false;
		boolean mirrorMatchesOnA = false;
		for (int i = 0; i < 2000 && !(crossed && v.getSpeed() == 0 && v.getMmtrMotionWalker().endOfLine()); i++) {
			n.tick();
			final double speed = v.getSpeed();
			if (n.aRail.getHexId().equals(v.getMmtrMotionWalker().railHex())) {
				maxOnA = Math.max(maxOnA, speed);
				if (v.getMmtrRegime() == MmtrRegime.LZB && v.getMmtrLzbCeilingKmh() == 120) {
					cabDataOnA = true;
				}
				// The cab target drops to the slower rail's 40 well before the node, with distance.
				if (v.getMmtrLzbTargetKmh() == 40 && v.getMmtrLzbTargetDistanceM() > 0) {
					targetBecame40 = true;
				}
				// HUD-2 data plane: the mirrored fields (what the client cab reads) must equal the
				// live supervision values the train is enforced against.
				if (v.isMmtrLzbSupervisingFromSync() && v.getMmtrLzbCeilingKmhFromSync() == v.getMmtrLzbCeilingKmh()
					&& v.getMmtrLzbTargetKmhFromSync() == v.getMmtrLzbTargetKmh()
					&& Math.abs(v.getMmtrLzbTargetDistanceMFromSync() - v.getMmtrLzbTargetDistanceM()) < 1e-6
					&& v.getMmtrSpeedLimitKmhFromSync() == v.getMmtrCurrentSpeedLimitKmh()) {
					mirrorMatchesOnA = true;
				}
			} else if (n.bRail.getHexId().equals(v.getMmtrMotionWalker().railHex())) {
				if (!crossed) {
					crossed = true;
					crossingSpeed = speed;
				}
				maxOnB = Math.max(maxOnB, speed);
			}
		}
		assertTrue(cabDataOnA, "cab reports the 120 km/h LZB ceiling on the 120 rail");
		assertTrue(targetBecame40, "cab target drops to 40 with a distance before the slower rail");
		assertTrue(mirrorMatchesOnA, "HUD mirror fields track the live LZB supervision values");
		assertTrue(crossed, "manual train crossed onto the slower AWS rail");
		/*
		 * 力模型（notes/235）之后这一条要**分开看两件事**：
		 * ① 天花板是**强制**的（绝不越过 120，见下一条）；② "能不能推到贴着天花板"取决于**功率**：
		 * 折点以上牵引力按 1/v 掉，于是接近天花板时加速越来越慢、限时内到不了 120 —— 这不是监督失效，
		 * 而是真车就是这样。所以这里只钉"明显推上去了"，把"贴着天花板"留给配置里真写了足够功率的车底。
		 */
		assertTrue(maxOnA > kmh(100), "driver pushed hard on the 120 rail, max=" + maxOnA * 3600.0 + " km/h");
		assertTrue(maxOnA <= kmh(120) + 1e-6, "LZB ceiling enforced: never exceeded 120 on the 120 rail, max=" + maxOnA * 3600.0 + " km/h");
		// The brake envelope is sampled at tick rate (service brake 0.9 m/s^2 per 1 s tick), so the
		// node crossing carries up to ~1 tick of residual deceleration (~3 km/h) above the target.
		assertTrue(crossingSpeed <= kmh(40) + kmh(3), "braced into the 40 rail, crossed at " + crossingSpeed * 3600.0 + " km/h");
		assertTrue(crossingSpeed >= kmh(35), "did not crawl into the slow rail, crossed at " + crossingSpeed * 3600.0 + " km/h");
		// On the AWS rail the manual driver is free again (S2 semantics): the same throttle pushes past 40.
		assertTrue(maxOnB > kmh(45), "AWS band frees the driver again after the LZB section, max=" + maxOnB * 3600.0 + " km/h");
		assertEquals(0, v.getMmtrLzbCeilingKmh(), "no LZB supervision once off the LZB band");
	}

	@Test
	public void manualLzbDriverStopsAtAnOccupiedRailWithZeroCabTarget() {
		final LzbNet n = new LzbNet("build/mmtr-lzb-block");
		final Vehicle v = n.spawn();
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(entities);
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(3).setReverser(1), driver).apply(n.sim);

		// A foreign train occupies B: while the manual LZB run approaches, the cab target is 0 and
		// the S1 occupancy stop holds the train on A, never boarding B.
		boolean sawZeroTarget = false;
		boolean stoppedOnA = false;
		for (int i = 0; i < 2000 && !stoppedOnA; i++) {
			treesRotateAndOccupyB(n);
			if (v.getMmtrLzbTargetKmh() == 0 && v.getMmtrLzbTargetDistanceM() > 0) {
				sawZeroTarget = true;
			}
			stoppedOnA = v.getSpeed() == 0 && v.isMmtrBlockHeldFromSync() && n.aRail.getHexId().equals(v.getMmtrMotionWalker().railHex());
		}
		assertTrue(sawZeroTarget, "cab target 0 with distance while the rail ahead is occupied");
		assertTrue(stoppedOnA, "LZB manual run stops on A at the occupied-rail boundary, rail=" + v.getMmtrMotionWalker().railHex());
		assertEquals(n.aRail.getHexId(), v.getMmtrMotionWalker().railHex(), "never boarded the occupied B rail");
		assertEquals(0, v.getMmtrLzbTargetKmh(), "cab target stays 0 while held");
		// notes/233 司机优先：手动车在未授权界限上是**紧急制动**（司机越界那一路，可按响应键解除），
		// 它不是 AWS 报警超时的 SPAD —— 所以这里只断言"没有 SPAD"。
		assertFalse(v.isMmtrProtectionFromSync() && !v.isMmtrAuthorityTripped(), "soft occupancy stop, no SPAD");
	}

	/**
	 * **定速与 LZB 耦合**（用户 2026-09-23：「AFB 速度正常设置，需要 LZB 来进行向下限定」）。
	 *
	 * <p>现场就是这一条漏了：LZB 区段的手动驾驶走的是"包线 + 天花板"的简化模型、**根本不走控制器**，
	 * 于是定速 20 + 手柄 97 的车在 120 的 LZB 轨上一路涨到 91.5 km/h 还在涨（司机原话："定速没用"）。</p>
	 *
	 * <ul>
	 *   <li>① 定速**低于**线路限速（60 &lt; 120）⇒ LZB 不干预，车稳在定速；</li>
	 *   <li>② 定速**高于**线路限速（150 &gt; 120）⇒ 由 LZB **向下限定**到 120。</li>
	 * </ul>
	 */
	@Test
	public void theSetSpeedAndTheLzbCeilingAreCoupled() {
		// ① 定速 60（线路 120）：车必须稳在 60，不许被线路限速带着往 120 跑
		final LzbNet hold = new LzbNet("build/mmtr-lzb-afb-hold");
		final Vehicle holdVehicle = spawnAndDrive(hold, 7, 60);
		final double maxHolding = maxSpeedOnARail(hold, holdVehicle, 400);
		System.out.println("[AFB+LZB] 定速 60：A 轨最高 " + Math.round(maxHolding * 3600) + " km/h");
		assertTrue(maxHolding > kmh(50), "定速 60 要真的推上去，实际 " + maxHolding * 3600 + " km/h");
		assertTrue(maxHolding <= kmh(60) + 1e-6, "定速 60 就是天花板，实际 " + maxHolding * 3600 + " km/h");

		// ② 定速 150（线路 120）：LZB 向下限定 ⇒ 不许越过 120
		final LzbNet capped = new LzbNet("build/mmtr-lzb-afb-cap");
		final Vehicle cappedVehicle = spawnAndDrive(capped, 7, 150);
		final double maxCapped = maxSpeedOnARail(capped, cappedVehicle, 900);
		System.out.println("[AFB+LZB] 定速 150：A 轨最高 " + Math.round(maxCapped * 3600) + " km/h");
		assertTrue(maxCapped > kmh(100), "定速 150 应当把车推到接近线路限速，实际 " + maxCapped * 3600 + " km/h");
		assertTrue(maxCapped <= kmh(120) + 1e-6, "LZB 向下限定到 120，实际 " + maxCapped * 3600 + " km/h");
	}

	/**
	 * **LZB 段上"手柄决定出力"**（用户 2026-09-23 现场：「牵引 20% 和 100% 加速度都是 +1.6」）。
	 *
	 * <p>现场：这条线的轨限速 160 km/h ≥ 101 ⇒ **整条线都在 LZB 区段**，而手动驾驶在 LZB 段走的是
	 * "包线 + 天花板"的简化模型：加速度原来写死成 `tractionAccelerationMps2(1, v)` = **满牵引**，
	 * 手柄只被当成"要不要走"的开关。于是同一根杆在 20% 和 100% 给出完全一样的加速度，
	 * 而定速（天花板）只把速度**卡在某个值**、根本不削减牵引力。</p>
	 *
	 * <p>修法（notes/264）：LZB 只管天花板，出力走控制器（手柄比例 + AFB 削力 + 牵引联锁 + 驱动延迟）。
	 * 这一条钉住"两档手柄的加速度必须明显不同"，且绝对值符合单机车的设计值。</p>
	 */
	@Test
	public void theHandleSetsTheForceOnTheLzbBandToo() {
		final double slow = lzbAccelerationWithHandle(20);
		final double fast = lzbAccelerationWithHandle(97);
		System.out.println("[TEST] LZB 段手柄 20% → " + Math.round(slow * 100) / 100.0
			+ " m/s²，100% → " + Math.round(fast * 100) / 100.0 + " m/s²");
		// 单车 84 t / λ1.16：满牵引 ≈ 3.0 m/s²；手柄 20%（比例 23%）≈ 0.7 m/s²。
		assertTrue(slow > 0.4 && slow < 1.2, "20% 手柄在 LZB 段只该给约 0.7 m/s²（不是满牵引），实际 " + slow);
		assertTrue(fast > 2.2, "100% 手柄应当给约 3 m/s²，实际 " + fast);
		assertTrue(fast > slow * 3, "手柄必须决定出力：100% 至少是 20% 的 3 倍，实际 " + (fast / slow) + "×");
	}

	/** 三手柄机车在 A 轨（120 km/h ⇒ LZB 段）上**单拍**的加速度（m/s²）。 */
	private static double lzbAccelerationWithHandle(int driveHandle) {
		final LzbNet n = new LzbNet("build/mmtr-lzb-handle-" + driveHandle, "loco");
		final Vehicle v = n.spawn();
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(entities);
		// 定速 160（高于线路 120）：AFB 在岗，但**不许**因为它就把手柄那一份力抹平。
		new MmtrDriveControl(v.getId(), new ControlState().setDriveHandle(driveHandle).setReverser(1).setCruiseSpeedKmh(160), driver).apply(n.sim);

		for (int i = 0; i < 400 && !n.aRail.getHexId().equals(v.getMmtrMotionWalker().railHex()); i++) {
			n.tick();
		}
		assertEquals(n.aRail.getHexId(), v.getMmtrMotionWalker().railHex(), "车应当已进入 A 轨（120 ⇒ LZB 段）");
		assertEquals(MmtrRegime.LZB, v.getMmtrRegime(), "A 轨（120 km/h）必须是 LZB 段，否则这条用例没测到要测的东西");
		final double before = v.getSpeed();
		n.tick();
		// 车辆速度是引擎内部的 m/ms；×1000 才是 m/s（用例里比 km/h 的那个 kmh() 是同一个换算）。
		return (v.getSpeed() - before) * 1000.0;
	}

	/** 车场发车 + 坐进司机位 + 给一个带定速的操纵。 */
	private static Vehicle spawnAndDrive(LzbNet n, int throttleNotch, int cruiseKmh) {
		final Vehicle v = n.spawn();
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(entities);
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(throttleNotch).setReverser(1).setCruiseSpeedKmh(cruiseKmh), driver).apply(n.sim);
		return v;
	}

	/** 先在车场段把车放到 A 轨，再在 A 轨上量最高速度（离开 A 轨即停）。 */
	private static double maxSpeedOnARail(LzbNet n, Vehicle v, int maxTicks) {
		for (int i = 0; i < 400 && !n.aRail.getHexId().equals(v.getMmtrMotionWalker().railHex()); i++) {
			n.tick();
		}
		assertEquals(n.aRail.getHexId(), v.getMmtrMotionWalker().railHex(), "车应当已进入 A 轨（120 LZB 段）");
		double max = 0;
		for (int i = 0; i < maxTicks && n.aRail.getHexId().equals(v.getMmtrMotionWalker().railHex()); i++) {
			n.tick();
			max = Math.max(max, v.getSpeed());
		}
		return max;
	}

	private void treesRotateAndOccupyB(LzbNet n) {
		n.trees.removeFirst();
		n.trees.add(new Object2ObjectAVLTreeMap<>());
		final Position orderedP1 = n.bRail.getPosition1().compareTo(n.bRail.getPosition2()) <= 0 ? n.bRail.getPosition1() : n.bRail.getPosition2();
		final Position orderedP2 = orderedP1 == n.bRail.getPosition1() ? n.bRail.getPosition2() : n.bRail.getPosition1();
		Data.put(n.trees.get(1), orderedP1, orderedP2,
			vehiclePosition -> {
				final VehiclePosition newVehiclePosition = vehiclePosition == null ? new VehiclePosition() : vehiclePosition;
				newVehiclePosition.addSegment(20, 60, OBSTACLE_VEHICLE_ID);
				return newVehiclePosition;
			}, Object2ObjectAVLTreeMap::new);
		n.siding.simulateVehicles(1000, n.trees);
	}
}
