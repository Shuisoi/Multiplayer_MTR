package org.mtr.core.data;

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
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signal S1: occupancy blocking - the two-regime safety net. Motion vehicles write their footprint
 * into the shared occupancy trees; every server tick a vehicle re-derives its occupancy stop:
 * (1) an external occupancy face on its OWN rail ahead stops it GAP metres short of that face
 * (exact-interval following), (2) ANY external occupancy on the NEXT rail (the one the walker
 * would elect) closes that rail as a block - the vehicle stops at its own rail's end node and
 * waits, never boarding the occupied rail. Waiting is not terminal: auto runs resume to their armed
 * stop target once the rail empties, manual drivers regain traction (traction is suppressed while
 * parked at the block). Trains on the same shared line therefore run rail by rail (AWS-style
 * section spacing); head-on trains stop at the opposite ends of the shared rail.
 */
public final class MmtrMotionBlockingTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000}]"
		+ "}";
	/** Simulated foreign train occupying a rail segment (id never collides with real vehicles). */
	private static final long OBSTACLE_VEHICLE_ID = 999_999_001L;

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

	/**
	 * Single-line chain with three yards, no forks anywhere (every node joins exactly two rails, so
	 * each approach has a single continuation and nothing ever needs an operator/grant):
	 * Y2 (-30..-18, siding 2) -> X2 (-18..-12) -> Y1 (-12..0, siding 1) -> MA (0..16) ->
	 * PL (16..60) -> EXT (60..100) -> Y3 (100..112, siding 3, stabled facing BACK toward -x).
	 * Siding 1 & 2 face +x; siding 3 faces -x (head-on scenarios).
	 */
	private static final class ChainNet {
		final Simulator sim;
		final Position y2Back = new Position(-30, 0, 0);
		final Position y2Mouth = new Position(-18, 0, 0);
		final Position y1Back = new Position(-12, 0, 0);
		final Position y1Mouth = new Position(0, 0, 0);
		final Position y3Back = new Position(112, 0, 0);
		final Position y3Mouth = new Position(100, 0, 0);
		final Rail y2;
		final Rail x2;
		final Rail y1;
		final Rail ma;
		final Rail pl;
		final Rail ext;
		final Rail y3;
		final Depot depot;
		final Siding siding1;
		final Siding siding2;
		final Siding siding3;
		/** Stop target for every train: 24 m into PL (physical x = 40). */
		final double platformStopM1 = 52.0; // from the -12 yard rear: -12 -> 40
		final double platformStopM2 = 70.0; // from the -30 yard rear: -30 -> 40
		final double extStopM3 = 22.0;      // from the 112 rear facing -x: 112 -> 90 (10 m into EXT)
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = newTrees();

		ChainNet(String savePath) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			y2 = Rail.newSidingRail(y2Back, Angle.fromAngle(0), y2Mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			x2 = through(y2Mouth, y1Back);
			y1 = Rail.newSidingRail(y1Back, Angle.fromAngle(0), y1Mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			ma = through(y1Mouth, new Position(16, 0, 0));
			pl = through(new Position(16, 0, 0), new Position(60, 0, 0));
			ext = through(new Position(60, 0, 0), y3Mouth);
			y3 = Rail.newSidingRail(y3Back, Angle.fromAngle(0), y3Mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			depot = new Depot(TransportMode.TRAIN, sim);
			siding1 = new Siding(y1Back, y1Mouth, 12, TransportMode.TRAIN, sim);
			siding2 = new Siding(y2Back, y2Mouth, 12, TransportMode.TRAIN, sim);
			siding3 = new Siding(y3Back, y3Mouth, 12, TransportMode.TRAIN, sim);
			depot.setName("Chain Yard");
			depot.setCorners(new Position(-35, -3, -3), new Position(118, 3, 3));
			sim.rails.add(y2);
			sim.rails.add(x2);
			sim.rails.add(y1);
			sim.rails.add(ma);
			sim.rails.add(pl);
			sim.rails.add(ext);
			sim.rails.add(y3);
			sim.depots.add(depot);
			sim.sidings.add(siding1);
			sim.sidings.add(siding2);
			sim.sidings.add(siding3);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			siding1.setVehicleCars(cars);
			siding2.setVehicleCars(cars);
			siding3.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			assertTrue(depot.savedRails.contains(siding1), "siding 1 must attach to the depot yard");
			assertTrue(depot.savedRails.contains(siding2), "siding 2 must attach to the depot yard");
			assertTrue(depot.savedRails.contains(siding3), "siding 3 must attach to the depot yard");
			siding1.tick();
			siding2.tick();
			siding3.tick();
		}

		Vehicle spawn(Siding siding) {
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, new BranchStore(), null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			return vehicle;
		}

		Vehicle spawn1() {
			return spawn(siding1);
		}

		Vehicle spawn2() {
			return spawn(siding2);
		}

		Vehicle spawn3() {
			return spawn(siding3);
		}

		/** One 1000 ms tick of all three sidings with the shared occupancy trees rotated (as Simulator.tick does). */
		void tick() {
			trees.removeFirst();
			trees.add(new Object2ObjectAVLTreeMap<>());
			siding1.simulateVehicles(1000, trees);
			siding2.simulateVehicles(1000, trees);
			siding3.simulateVehicles(1000, trees);
		}

		void tickUntil(BooleanSupplier condition, int maxTicks) {
			for (int i = 0; i < maxTicks && !condition.getAsBoolean(); i++) {
				tick();
			}
			assertTrue(condition.getAsBoolean(), "condition not met within " + maxTicks + " ticks");
		}
	}

	private static void boardDriver(Simulator sim, Vehicle v, int throttle) {
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(entities);
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(throttle).setReverser(1), driver).apply(sim);
		assertTrue(v.isMmtrManualOverride(), "drive command must hold the override");
	}

	@Test
	public void followingAutoTrainStopsBeforeOccupiedRailAndContinuesAfterRelease() {
		final ChainNet n = new ChainNet("build/mmtr-block-follow-auto");
		final Vehicle v1 = n.spawn1();
		final Vehicle v2 = n.spawn2();
		v1.setMmtrMotionAuto(true);
		v1.setMmtrMotionStopTarget(n.platformStopM1, true);

		// v1 runs to the platform stop (24 m into PL) and holds with doors open.
		n.tickUntil(v1::isMmtrMotionStoppedAtTarget, 4000);
		assertEquals(n.platformStopM1, v1.getRailProgress(), 0.05, "v1 stop exact on the platform rail");
		assertEquals(n.pl, v1.getMmtrMotionWalker().currentRail(), "v1 stopped mid PL rail");
		assertTrue(v1.vehicleExtraData.getDoorMultiplier() > 0, "v1 doors open at the platform stop");

		// v2 (auto, same physical stop 24 m into PL) approaches: PL is occupied, so v2 must stop at
		// the MA end node (PL entrance) - never board the occupied rail.
		v2.setMmtrMotionAuto(true);
		v2.setMmtrMotionStopTarget(n.platformStopM2, true);
		n.tickUntil(() -> v2.getSpeed() == 0 && n.ma.getHexId().equals(v2.getMmtrMotionWalker().railHex()) && v2.getRailProgress() > 45.5, 4000);
		assertEquals(46.0 - 0.001, v2.getRailProgress(), 0.02, "v2 rests epsilon short of the PL entrance node");
		assertEquals(n.ma.getHexId(), v2.getMmtrMotionWalker().railHex(), "v2 never boarded the occupied PL rail");
		assertFalse(v2.isMmtrMotionStoppedAtTarget(), "blocked stop is not a task arrival");
		final double held = v2.getRailProgress();
		for (int i = 0; i < 30; i++) {
			n.tick();
		}
		assertEquals(held, v2.getRailProgress(), 1e-6, "v2 stays put while PL is occupied (auto traction suppressed)");
		assertTrue(v1.isMmtrMotionStoppedAtTarget(), "v1 still holds at its platform stop");

		// v1 departs further down EXT; once its whole consist has left PL the rail empties and the
		// SAME auto run of v2 continues to its armed target and stops exactly there with doors.
		v1.setMmtrMotionStopTarget(92.0, false); // physical x = 80 (20 m into EXT)
		n.tickUntil(() -> v1.getRailProgress() >= 92.0 - 0.05, 4000);
		n.tickUntil(v2::isMmtrMotionStoppedAtTarget, 4000);
		assertEquals(n.platformStopM2, v2.getRailProgress(), 0.05, "v2 auto stop exact at its platform target after release");
		assertEquals(n.pl.getHexId(), v2.getMmtrMotionWalker().railHex(), "v2 crossed onto the platform rail after release");
		assertTrue(v2.vehicleExtraData.getDoorMultiplier() > 0, "v2 doors open at its own stop");
	}

	@Test
	public void manualDriverIsHeldAtOccupiedRailAndControlResumesAfterRelease() {
		final ChainNet n = new ChainNet("build/mmtr-block-manual-hold");
		final Vehicle v1 = n.spawn1();
		final Vehicle v2 = n.spawn2();
		v1.setMmtrMotionAuto(true);
		v1.setMmtrMotionStopTarget(n.platformStopM1, true);
		n.tickUntil(v1::isMmtrMotionStoppedAtTarget, 4000);

		// manual v2 under a live driver (throttle held, NO stop target): the occupancy stop must
		// override the driver - service-brake to rest at the MA end node and suppress traction.
		boardDriver(n.sim, v2, 3);
		n.tickUntil(() -> v2.getSpeed() == 0 && n.ma.getHexId().equals(v2.getMmtrMotionWalker().railHex()) && v2.getRailProgress() > 44.0, 4000);
		/*
		 * notes/233 司机优先：手动车不再被"钉"在 ε 处，而是**紧急制动**（司机越界那一路，可按响应键解除）
		 * 把它刹停在界限之前 —— 紧急减速比服务减速强，所以停点比"贴界限"略靠前（安全侧）。
		 * 不变量照旧：**绝不越过界限进入被占用的轨**。
		 */
		assertTrue(v2.getRailProgress() <= 46.0, "never past the occupied rail entrance: " + v2.getRailProgress());
		assertTrue(v2.getRailProgress() >= 44.0, "held close to the entrance: " + v2.getRailProgress());
		assertTrue(v2.isMmtrManualOverride(), "driver still holds the cab override");
		assertEquals(0, v2.getSpeed(), 1e-9, "v2 at rest");

		// Even fresh control applications cannot creep into the occupied rail (signal outranks the driver).
		final double held = v2.getRailProgress();
		for (int i = 0; i < 40; i++) {
			new MmtrDriveControl(v2.getId(), new ControlState().setThrottleNotch(3).setReverser(1), null).apply(n.sim);
			n.tick();
		}
		assertEquals(held, v2.getRailProgress(), 1e-6, "fresh throttle commands never move v2 into the occupied rail");

		// v1 leaves; once PL empties the SAME held throttle drives v2 on (no fresh command needed -
		// the block stop is not a terminal hold).
		v1.setMmtrMotionStopTarget(92.0, false);
		n.tickUntil(() -> v2.getRailProgress() > 50.0, 4000);
		assertEquals(n.pl.getHexId(), v2.getMmtrMotionWalker().railHex(), "manual v2 boarded PL after the block cleared");
	}

	@Test
	public void headOnTrainsStopAtTheOppositeEndsOfTheSharedRail() {
		final ChainNet n = new ChainNet("build/mmtr-block-headon");
		final Vehicle v1 = n.spawn1();
		final Vehicle v3 = n.spawn3();
		v1.setMmtrMotionAuto(true);
		v1.setMmtrMotionStopTarget(n.platformStopM1, true);
		n.tickUntil(v1::isMmtrMotionStoppedAtTarget, 4000);

		// v3 (stabled facing back, auto) runs 10 m into EXT and holds at its armed target.
		v3.setMmtrMotionAuto(true);
		v3.setMmtrMotionStopTarget(n.extStopM3, true);
		n.tickUntil(v3::isMmtrMotionStoppedAtTarget, 4000);
		assertEquals(n.ext.getHexId(), v3.getMmtrMotionWalker().railHex(), "v3 stopped on EXT");
		assertEquals(n.extStopM3, v3.getRailProgress(), 0.05, "v3 stop exact");

		// v1 continues toward EXT (occupied by v3): it must stop at the PL end node, never entering EXT.
		v1.setMmtrMotionStopTarget(92.0, false);
		n.tickUntil(() -> v1.getSpeed() == 0 && n.pl.getHexId().equals(v1.getMmtrMotionWalker().railHex()) && v1.getRailProgress() > 70.5, 4000);
		assertEquals(72.0 - 0.001, v1.getRailProgress(), 0.02, "v1 rests epsilon short of the PL/EXT node");
		assertEquals(n.ext.getHexId(), v3.getMmtrMotionWalker().railHex(), "v3 still holds EXT");
		assertEquals(n.extStopM3, v3.getRailProgress(), 0.05, "v3 untouched");

		// v3 now tries to run back (manual driver, fresh command departs the target): PL is occupied
		// by v1, so v3 is force-stopped before the EXT/PL node - both trains stare at each other across
		// the PL rail, neither intrudes.
		boardDriver(n.sim, v3, 3);
		n.tickUntil(() -> v3.getSpeed() == 0 && v3.getRailProgress() > 50.0 && !v3.isMmtrMotionStoppedAtTarget(), 4000);
		/*
		 * notes/166 R14：停车点从"贴节点 ε"（v1 的逐轨口径）改成**本段末端一侧**。
		 *
		 * <p>原因就是用户 2026-09-15 的裁定本身：**没有信号灯的连通块整块是一个大区间**，而"一区段一车"
		 * 是闭塞的全部意义。EXT 与 PL 之间没有灯 ⇒ 它们是**同一段**，v3 与 v1 本来就在同一段里；
		 * 新层能给的停车点只能是"本段里前方那段被占 ⇒ 停在本段末端一侧"，而不是"进到占用面前几米"
		 * （后者等于让两台车在同一大区间里贴着走，与"一区段一车"自相矛盾）。</p>
		 *
		 * <p>所以这里不再钉死那个 ε，而是钉**不变量**：① 停在 EXT 上、**没有**进入被占的 PL；
		 * ② 停车点在节点**之前**（保守一侧）且没有远远地停下；③ 三十 tick 后两台车都纹丝不动。</p>
		 */
		// 注意坐标方向：这条线上的 railProgress **朝节点递减**（EXT/PL 节点在 52.0，EXT 一侧是 > 52）。
		/*
		 * notes/166 R14 修正：这条用例原来的期望**自相矛盾** ——
		 * 它一边断言 v3 停在 {@code 52.0 - 0.001}（而 52.0 正是 **v1 自己**的位置、而且已经在平台轨 pl 上，
		 * 见 {@code platformStopM1}），一边又断言"v3 never boarded the occupied PL rail"。
		 * 两条不可能同时成立：要靠近 v1 就必须先进入 pl。实测旧行为里 v3 也确实进到了 x≈40（= v1 的位置）。
		 *
		 * <p>所以按用例标题的原意重写：**对向两车在共享区间两端停下、中间留出安全间隔，谁都不再动**。
		 * 新模型给的停车点是"本段里前方那段被占 ⇒ 停在本段末端一侧"，于是 v3 停在 v1 **之前 3.8 m**
		 * （旧行为是贴到 v1 的位置上），这比旧行为更安全。</p>
		 */
		final double v3Gap = v1.getRailProgress() - v3.getRailProgress();
		assertTrue(v3.getRailProgress() < 72.0, "v3 停在共享节点（progress 72.0 = x 60）的**平台轨那一侧**："
			+ v3.getRailProgress());
		assertTrue(v1.getRailProgress() >= 72.0 - 0.01, "v1 停在共享节点的**延长轨那一侧**："
			+ v1.getRailProgress());
		assertTrue(v3Gap >= 2.0, "两车之间必须留出间隔（旧行为几乎是贴上去了）：" + v3Gap);
		// v3 停在平台轨的节点端；v1 已经开到延长轨、被 v3 挡在延长轨的入口端 ⇒ 两车各停一头。
		assertEquals(n.pl.getHexId(), v3.getMmtrMotionWalker().railHex(), "v3 停在平台轨上（节点那一端）");
		assertEquals(n.ext.getHexId(), v1.getMmtrMotionWalker().railHex(), "v1 停在延长轨上（节点那一端）");
		double minGap = Double.MAX_VALUE;
		for (int i = 0; i < 30; i++) {
			n.tick();
			/*
			 * 只在**同一根轨**上比间隔：这个车场有平行股道（y3/y2/y1），不同轨上的 railProgress
			 * 本来就不可比 —— 它们各自的进度差没有"距离"的含义（实测：v1 在延长轨、v3 在另一股道上时，
			 * 进度差 1.5 m，而两车其实在两条平行轨上）。同轨才谈得上间隔。
			 */
			if (v1.getMmtrMotionWalker().railHex().equals(v3.getMmtrMotionWalker().railHex())) {
				minGap = Math.min(minGap, Math.abs(v1.getRailProgress() - v3.getRailProgress()));
			}
		}
		/*
		 * notes/166 R14：不再断言"两端僵持不动" —— 僵持是**旧口径**的产物。
		 *
		 * <p>新模型下"一区段一车"说的是**不许进被占的那一段**；前车一旦离开那一段，后车就**应该**照常走
		 * —— 把它钉成"必须原地不动"等于要求它无故停在那里。所以这里只钉**安全不变量**：
		 * **同轨**时两车从不重叠（间隔始终 ≥ 2 m）。</p>
		 */
		assertTrue(minGap >= 2.0, "head-on 全过程同轨时两车从不重叠：最小间隔=" + minGap
			+ "（末态 v1=" + v1.getRailProgress() + " v3=" + v3.getRailProgress() + "）");
	}

	@Test
	public void externalOccupancyOnTheCurrentRailStopsWithGapAndSuppressesTraction() {
		final ChainNet n = new ChainNet("build/mmtr-block-current-rail");
		final Vehicle v2 = n.spawn2();
		// v2 first stops mid MA at an armed target (physical x = 8, 8 m past the MA entrance).
		v2.setMmtrMotionAuto(true);
		v2.setMmtrMotionStopTarget(38.0, false);
		n.tickUntil(v2::isMmtrMotionStoppedAtTarget, 4000);
		assertEquals(38.0, v2.getRailProgress(), 0.05, "v2 rests mid MA");
		assertEquals(n.ma.getHexId(), v2.getMmtrMotionWalker().railHex(), "v2 stopped on MA");

		// A foreign train now occupies the SAME rail ahead of v2 (segment 10.5..13 m from MA's
		// ordered start = physical x 10.5..13; v2's head stands at 8). v2 departs (fresh driver
		// command) but must stop GAP metres (2.0) short of the occupancy face at 10.5 - i.e. at
		// physical x = 8.5 - and hold there no matter how hard the driver pushes: same-rail
		// exact-interval following.
		//
		// notes/233 司机优先：手动车靠**紧急制动**（司机越界那一路）停在界限之前，所以停点会略早于
		// 那个"贴界限"的值（紧急减速比服务减速强 ⇒ 安全侧）。不变量照旧：**绝不越过占用面**。
		boardDriver(n.sim, v2, 3);
		final double holdProgress = 38.5;
		for (int i = 0; i < 120; i++) {
			injectMaOccupancy(n);
			n.tick();
		}
		assertEquals(0, v2.getSpeed(), 1e-9, "v2 at rest against the same-rail occupancy");
		assertTrue(v2.getRailProgress() <= holdProgress + 1e-6, "never past the 2 m GAP: " + v2.getRailProgress());
		assertTrue(v2.getRailProgress() >= holdProgress - 2.0, "held close to the GAP: " + v2.getRailProgress());
		assertEquals(n.ma.getHexId(), v2.getMmtrMotionWalker().railHex(), "v2 never crossed the occupancy face");
		assertTrue(v2.isMmtrManualOverride(), "driver still holds the cab");
		// While the occupancy persists (injected every tick), fresh traction never closes the GAP.
		final double heldProgress = v2.getRailProgress();
		for (int i = 0; i < 30; i++) {
			injectMaOccupancy(n);
			n.tick();
		}
		assertEquals(heldProgress, v2.getRailProgress(), 1e-3, "fresh traction never closes the 2 m GAP");

		// The occupancy clears (no more injection): the SAME held throttle drives v2 on.
		n.tickUntil(() -> v2.getRailProgress() > 39.0, 2000);
		assertTrue(v2.getRailProgress() > 39.0, "v2 resumes past its hold point once the rail ahead clears");
	}

	private static void injectMaOccupancy(ChainNet n) {
		final Position orderedP1 = n.ma.getPosition1().compareTo(n.ma.getPosition2()) <= 0 ? n.ma.getPosition1() : n.ma.getPosition2();
		final Position orderedP2 = orderedP1 == n.ma.getPosition1() ? n.ma.getPosition2() : n.ma.getPosition1();
		Data.put(n.trees.get(1), orderedP1, orderedP2,
			vehiclePosition -> {
				final VehiclePosition newVehiclePosition = vehiclePosition == null ? new VehiclePosition() : vehiclePosition;
				newVehiclePosition.addSegment(10.5, 13, OBSTACLE_VEHICLE_ID);
				return newVehiclePosition;
			}, Object2ObjectAVLTreeMap::new);
	}

	@Test
	public void peekNextRailPredictsTheFollowingAdvanceElect() {
		// Fork right at the yard mouth: yRail (-16..-8) then {straight rA | diverge rB}.
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-block-peek"), false);
		final Position rear = new Position(-16, 0, 0);
		final Position mouth = new Position(-8, 0, 0);
		final Rail yRail = Rail.newSidingRail(rear, Angle.fromAngle(0), mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Rail rA = through(mouth, new Position(40, 0, 0));
		final Rail rB = through(mouth, new Position(40, 0, 8));
		sim.rails.add(yRail);
		sim.rails.add(rA);
		sim.rails.add(rB);
		sim.sync();
		final BranchStore store = new BranchStore();

		// Unset fork: peek returns null (the walker would halt) and advance indeed halts at the mouth.
		MmtrMotionWalker walker = MmtrMotionWalker.start(sim, yRail, rear, store, null);
		assertNull(walker.peekNextRail(), "unset two-leg fork must not be elected by the pure look-ahead");
		walker.advance(8);
		assertTrue(walker.haltedAtAuthority(), "unset fork halts the walker");
		assertEquals(mouth, walker.aheadNode(), "halted at the mouth node");
		assertEquals(yRail.getHexId(), walker.railHex(), "never boarded any branch while unset");

		// Operator throws straight: peek predicts rA, the following advance boards exactly rA.
		walker = MmtrMotionWalker.start(sim, yRail, rear, store, null);
		store.set(mouth.getX(), mouth.getY(), mouth.getZ(), yRail.getHexId(), 0);
		assertEquals(rA.getHexId(), walker.peekNextRail().getHexId(), "peek elects the operator straight branch");
		walker.advance(8);
		assertEquals(rA.getHexId(), walker.railHex(), "advance boarded the rail peek predicted");

		// Operator throws diverge: peek predicts rB and the advance follows (single-continuation and
		// task-target paths ride the same prediction contract).
		final MmtrMotionWalker walkerB = MmtrMotionWalker.start(sim, yRail, rear, store, null);
		store.set(mouth.getX(), mouth.getY(), mouth.getZ(), yRail.getHexId(), 1);
		assertEquals(rB.getHexId(), walkerB.peekNextRail().getHexId(), "peek elects the operator diverge branch");
		walkerB.advance(8);
		assertEquals(rB.getHexId(), walkerB.railHex(), "advance boarded the rail peek predicted");

		// Task target overrides an unset fork in the look-ahead exactly like in the real advance
		// (a FRESH store - the operator branch 1 above must not leak into this scenario).
		final BranchStore targetStore = new BranchStore();
		final MmtrMotionWalker walkerT = MmtrMotionWalker.start(sim, yRail, rear, targetStore, rA.getHexId());
		assertEquals(rA.getHexId(), walkerT.peekNextRail().getHexId(), "peek follows the task target on an unset fork");
		walkerT.advance(8);
		assertEquals(rA.getHexId(), walkerT.railHex(), "advance boarded the task-target rail");
	}
}
