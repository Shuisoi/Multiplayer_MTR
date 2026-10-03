package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.point.MmtrTurnout;
import org.mtr.core.mmtr.route.MmtrRoute;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.mmtr.signal.MmtrMovementAuthority;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3：**进路没设好 ⇒ 车停在出发信号前**（设计 §6 T3 的 ②③⑤）。
 *
 * <p>这一条是"信号第一次真正控车"里最干净的一种情形：**没有占用**参与，红灯完全由**联锁**造成。
 * 修前这种车会一路开到岔前再等（那是规则 ③ 的行为）—— 堵住咽喉、而且看不出为什么；
 * 现在它在**出发信号**前就被扣住。</p>
 *
 * <p>为什么不在这里也测"红灯前精确停住"（①）与"黄灯确认后通过"（④）：那两条要用**占用**把灯压红，
 * 而占用造成的停车**规则 (2) 本来就会做**（修前就有），因此测不出规则 (5) 的增量。
 * 真正能隔离规则 (5) 的就是本类用的"进路 PENDING"通道 —— 它以前完全不产生任何停车。
 * 那两条留待需要"真灯 + 真占用 + 真车"的夹具，已记在 notes/120 §4。</p>
 */
public final class MmtrSignalAuthorityStopTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000}]"
		+ "}";

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static Rail diverge(Position node, Position far) {
		return Rail.newRail(node, Angle.fromAngle(0), far, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> newTrees() {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = new ObjectArrayList<>();
		trees.add(new Object2ObjectAVLTreeMap<>());
		trees.add(new Object2ObjectAVLTreeMap<>());
		return trees;
	}

	/** 与 {@code MmtrSectionAuthorityStopTests.ForkNet} 同形的单开道岔夹具（车场 → 咽喉 → 岔口）。 */
	private static final class ForkNet {
		final Simulator sim;
		final Position fork = new Position(52, 0, 0);
		final Rail x;
		final Rail s;
		final Rail d;
		final Siding siding;
		final BranchStore store = new BranchStore();
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = newTrees();
		final double stopTargetM = 12.0 + 20.0 + 40.0 + 30.0;

		ForkNet(String savePath) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			final Position yardBack = new Position(-20, 0, 0);
			final Position yardMouth = new Position(-8, 0, 0);
			final Rail y = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			final Rail w = through(yardMouth, new Position(12, 0, 0));
			x = through(new Position(12, 0, 0), fork);
			s = through(fork, new Position(92, 0, 0));
			d = diverge(fork, new Position(92, 0, 20));
			final Depot depot = new Depot(TransportMode.TRAIN, sim);
			siding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);
			depot.setName("Authority Stop Yard");
			depot.setCorners(new Position(-25, -3, -3), new Position(100, 3, 15));
			sim.rails.add(y);
			sim.rails.add(w);
			sim.rails.add(x);
			sim.rails.add(s);
			sim.rails.add(d);
			sim.depots.add(depot);
			sim.sidings.add(siding);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			siding.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			assertTrue(depot.savedRails.contains(siding), "车场股道要挂在车辆段上");
			siding.tick();
		}

		Vehicle spawn() {
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, store, null);
			assertNotNull(walker, "车场走行源要能建立");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion 车要能生成");
			return vehicle;
		}

		void tick() {
			trees.removeFirst();
			trees.add(new Object2ObjectAVLTreeMap<>());
			siding.simulateVehicles(1000, trees);
		}

		/** 发布一条**故意不授权**的进路（去岔股），使它停在 PENDING。 */
		MmtrRoute publishPendingRoute(Vehicle v) {
			final MmtrTurnout turnout = sim.mmtrTurnout(fork.getX(), fork.getY(), fork.getZ());
			assertNotNull(turnout, "夹具的岔口是一处单开道岔");
			final int legToBranch = turnout.branchLeg.getOrDefault(x.getHexId(), -1);
			assertTrue(legToBranch >= 0, "从 x 看岔股那条腿的序号要能取到");
			final ObjectArrayList<String> rails = new ObjectArrayList<>();
			rails.add(x.getHexId());
			rails.add(turnout.branchRailHex);
			final ObjectArrayList<String[]> forks = new ObjectArrayList<>();
			forks.add(new String[]{String.valueOf(fork.getX()), String.valueOf(fork.getY()), String.valueOf(fork.getZ()),
				x.getHexId(), String.valueOf(legToBranch)});
			final MmtrRoute route = sim.mmtrRoutes.request(new MmtrRoute(v.getId(), "v" + v.getId(), MmtrRoute.Kind.MAIN,
				rails, forks, turnout.branchRailHex, sim.getCurrentMillis()));
			sim.mmtrRoutes.refresh(v.getId(), sim.mmtrPointAuthority);
			return route;
		}
	}

	/** ② + ⑤：进路 PENDING ⇒ 停在**出发信号**前，而且理由说得出是进路没设好。 */
	@Test
	public void aTrainWhoseRouteIsNotSetIsHeldAtItsDepartureSignal() {
		final ForkNet n = new ForkNet("build/mmtr-t3-route-pending");
		final Vehicle v = n.spawn();
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(n.stopTargetM, true);

		final MmtrRoute route = n.publishPendingRoute(v);
		assertFalse(route.isEstablished(), "没人授权那条腿 ⇒ 进路必须停在 PENDING");

		final MmtrMovementAuthority authority = v.mmtrMovementAuthority();
		assertNotNull(authority, "车要有行车许可");
		assertTrue(authority.mustStop(), "进路没设好 ⇒ 出发信号红 ⇒ 有停车义务");
		assertTrue(authority.reason.contains("进路未设好"), "⑤ 理由要点明是进路没设好：" + authority.reason);
		assertTrue(authority.reason.contains(route.getStateReason()) || route.getStateReason().isEmpty(),
			"⑤ 理由要把进路自己的等待原因带上：" + authority.reason);

		// 前进一小段就会被扣住（目标 = 出发信号，约等于到前方节点的距离）
		for (int i = 0; i < 60; i++) {
			n.tick();
		}
		final double heldM = v.getMmtrMotionWalker().distanceM();
		assertTrue(heldM < 20.0, "被扣在出发信号附近，而不是开到岔前（travelled=" + heldM + " m）");

		for (int i = 0; i < 300; i++) {
			n.tick();
		}
		assertEquals(heldM, v.getMmtrMotionWalker().distanceM(), 1e-6, "被许可扣住之后不再前进");
		// notes/217：被扣住必须有**司机可读**的理由（镜像给客户端 HUD）——
		// 这些闸门跑在司机控制之前，没有这一条，"手柄有反应但车不动"在游戏里就没有解释。
		assertEquals("前方进路未设好（红灯）", v.getMmtrHoldReasonFromSync(), "扣住时要把理由镜像给司机");
	}

	/** notes/217：理由只在被扣住时出现；放行之后必须清空（否则 HUD 会一直挂着一条过期理由）。 */
	@Test
	public void theHoldReasonClearsOnceTheTrainIsLetGo() {
		final ForkNet n = new ForkNet("build/mmtr-t3-hold-reason-clears");
		final Vehicle v = n.spawn();
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(n.stopTargetM, true);
		final MmtrRoute route = n.publishPendingRoute(v);
		for (int i = 0; i < 60; i++) {
			n.tick();
		}
		assertFalse(v.getMmtrHoldReasonFromSync().isEmpty(), "先是红灯：应当有理由");

		// 授权那条腿（与 settingTheRouteLetsTheHeldTrainGo 同一手法）
		final MmtrTurnout turnout = n.sim.mmtrTurnout(n.fork.getX(), n.fork.getY(), n.fork.getZ());
		final int legToBranch = turnout.branchLeg.getOrDefault(n.x.getHexId(), -1);
		n.sim.mmtrPointAuthority.request(n.fork.getX(), n.fork.getY(), n.fork.getZ(), n.x.getHexId(),
			"v" + v.getId(), legToBranch, n.sim.getCurrentMillis() + 600_000L);
		n.sim.mmtrRoutes.refresh(v.getId(), n.sim.mmtrPointAuthority);
		assertTrue(route.isEstablished(), "授权到手 ⇒ 进路 SET");
		// 走起来必须"没有扣住理由"：逐拍看有没有那种时刻（放行之后前方还可能遇到**下一个**闸门，
		// 所以不能在 200 拍之后直接断言空 —— 那是在断言"后面一路绿灯"，不是断言这条理由会清）。
		boolean sawMovementWithoutHold = false;
		for (int i = 0; i < 200; i++) {
			n.tick();
			if (v.getSpeed() > 1e-4 && v.getMmtrHoldReasonFromSync().isEmpty()) {
				sawMovementWithoutHold = true;
			}
		}
		assertTrue(v.getMmtrMotionWalker().distanceM() > 1.0, "放行后要能走");
		assertTrue(sawMovementWithoutHold, "必须出现过'在走且没有扣住理由'的时刻（否则 HUD 会一直挂着过期理由）");
	}

	/** ③：进路一旦设好（授权到手）⇒ 许可放行，车自己续行。 */
	@Test
	public void settingTheRouteLetsTheHeldTrainGo() {
		final ForkNet n = new ForkNet("build/mmtr-t3-route-set");
		final Vehicle v = n.spawn();
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(n.stopTargetM, true);

		final MmtrRoute route = n.publishPendingRoute(v);
		for (int i = 0; i < 60; i++) {
			n.tick();
		}
		final double heldM = v.getMmtrMotionWalker().distanceM();
		assertTrue(v.mmtrMovementAuthority().mustStop(), "先是红灯：扣住");

		// 授权那条腿 —— 这正是一列车"拿到进路"的那一刻
		final MmtrTurnout turnout = n.sim.mmtrTurnout(n.fork.getX(), n.fork.getY(), n.fork.getZ());
		final int legToBranch = turnout.branchLeg.getOrDefault(n.x.getHexId(), -1);
		n.sim.mmtrPointAuthority.request(n.fork.getX(), n.fork.getY(), n.fork.getZ(), n.x.getHexId(),
			"v" + v.getId(), legToBranch, n.sim.getCurrentMillis() + 600_000L);
		n.sim.mmtrRoutes.refresh(v.getId(), n.sim.mmtrPointAuthority);
		assertTrue(route.isEstablished(), "授权到手 ⇒ 进路 SET");

		assertFalse(v.mmtrMovementAuthority().mustStop(), "红转绿：许可不再要求停车");
		for (int i = 0; i < 200; i++) {
			n.tick();
		}
		assertTrue(v.getMmtrMotionWalker().distanceM() > heldM + 1.0,
			"放行之后自己续行（held=" + heldM + " now=" + v.getMmtrMotionWalker().distanceM() + "）");
	}
}
