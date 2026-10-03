package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.point.MmtrTurnout;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ① 区间式信号与道岔配合: the section stop must respect the TURNOUT at the far end of the block ahead.
 *
 * <p>Before this rule a train ran the whole block and parked at the points, occupying the block (and
 * its junction) while it waited for the operator/authority - and the red signal that should have held
 * it stood behind. Now the train is held at the signal BEFORE that block, so the block containing the
 * turnout stays clear and "signal red = train stopped at the signal" holds for manual and auto alike.
 *
 * <p>Network: yard Y(-20..-8, siding) -&gt; W(-8..12) -&gt; X(12..52) -&gt; fork(52) {S(52..92) |
 * D(52 -&gt; 92,+12)}. The fork sits at X's far node, so a train running W -&gt; X must be held at the
 * end of W until that fork has a decision.</p>
 */
public final class MmtrSectionAuthorityStopTests {

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
		return Rail.newRail(node, Angle.fromAngle(45), far, Angle.fromAngle(225), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static final class ForkNet {
		final Simulator sim;
		final Position fork = new Position(52, 0, 0);
		final Rail w;
		final Rail x;
		final Rail s;
		final Rail d;
		final Siding siding;
		final BranchStore store = new BranchStore();
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = newTrees();
		/** Auto stop target: 30 m into S, i.e. far beyond the fork. */
		final double stopTargetM = 12.0 + 20.0 + 40.0 + 30.0;

		ForkNet(String savePath) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			final Position yardBack = new Position(-20, 0, 0);
			final Position yardMouth = new Position(-8, 0, 0);
			final Rail y = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			w = through(yardMouth, new Position(12, 0, 0));
			x = through(new Position(12, 0, 0), fork);
			s = through(fork, new Position(92, 0, 0));
			d = diverge(fork, new Position(92, 0, 12));
			final Depot depot = new Depot(TransportMode.TRAIN, sim);
			siding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);
			depot.setName("Fork Yard");
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
			assertTrue(depot.savedRails.contains(siding), "the yard siding must attach to the depot");
			siding.tick();
		}

		Vehicle spawn() {
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, store, null);
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

		void tickUntil(java.util.function.BooleanSupplier condition, int maxTicks) {
			for (int i = 0; i < maxTicks && !condition.getAsBoolean(); i++) {
				tick();
			}
			assertTrue(condition.getAsBoolean(), "condition not met within " + maxTicks + " ticks");
		}

		void setStraight() {
			store.set(fork.getX(), fork.getY(), fork.getZ(), x.getHexId(), 0);
		}
	}

	private static ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> newTrees() {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = new ObjectArrayList<>();
		trees.add(new Object2ObjectAVLTreeMap<>());
		trees.add(new Object2ObjectAVLTreeMap<>());
		return trees;
	}

	@Test
	public void aTrainIsHeldAtTheSignalBeforeTheBlockThatEndsAtAnUnsetTurnout() {
		final ForkNet n = new ForkNet("build/mmtr-authority-hold");
		final Vehicle v = n.spawn();
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(n.stopTargetM, true);

		// The fork is unset: the train must rest at the END OF W (the boundary before X, whose far node
		// is the turnout), not inside X at the points.
		n.tickUntil(() -> v.getSpeed() == 0 && v.isMmtrBlockHeldFromSync(), 4000);
		assertEquals(n.w.getHexId(), v.getMmtrMotionWalker().railHex(), "held on the approach rail, not inside the block with the turnout");
		assertEquals(20.0 - 0.001, v.getMmtrMotionWalker().offsetM(), 0.05, "rests epsilon short of the boundary before that block");
		assertFalse(n.x.getHexId().equals(v.getMmtrMotionWalker().railHex()), "the block containing the turnout stays clear");
		assertFalse(v.getMmtrMotionWalker().haltedAtAuthority(), "the walker is not at the points - the section stop holds it");
		assertFalse(v.isMmtrMotionStoppedAtTarget(), "a block hold is not a task arrival");

		// The hold is stable while the fork stays unset, and the train never creeps into X.
		final double held = v.getMmtrMotionWalker().offsetM();
		for (int i = 0; i < 60; i++) {
			n.tick();
		}
		assertEquals(held, v.getMmtrMotionWalker().offsetM(), 1e-6, "stays at the signal while the turnout is unset");
		assertEquals(n.w.getHexId(), v.getMmtrMotionWalker().railHex(), "still has not entered the block with the turnout");

		// The operator sets the fork straight: the same held train runs through X, across the points and
		// onto S - no fresh command needed (the block hold is not terminal).
		n.setStraight();
		n.tickUntil(() -> n.s.getHexId().equals(v.getMmtrMotionWalker().railHex()), 4000);
		assertEquals(n.s.getHexId(), v.getMmtrMotionWalker().railHex(), "proceeds onto the elected leg once the fork is set");
	}

	@Test
	public void aSetTurnoutLetsTheTrainRunThroughWithoutStoppingAtTheBoundary() {
		final ForkNet n = new ForkNet("build/mmtr-authority-set");
		n.setStraight();
		final Vehicle v = n.spawn();
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(n.stopTargetM, true);

		// With the fork already set the block is cleared for the movement: the train crosses the W/X
		// boundary without stopping and never sets the block-hold flag on the way.
		n.tickUntil(() -> n.x.getHexId().equals(v.getMmtrMotionWalker().railHex()) || n.s.getHexId().equals(v.getMmtrMotionWalker().railHex()), 4000);
		assertFalse(v.isMmtrBlockHeldFromSync(), "no hold when the turnout is already set for this movement");
		n.tickUntil(() -> n.s.getHexId().equals(v.getMmtrMotionWalker().railHex()), 4000);
		assertEquals(n.s.getHexId(), v.getMmtrMotionWalker().railHex(), "runs straight through onto S");
	}

	/**
	 * ① 的**长区块**形态（2026-09-26 全天卡死，notes/328 §9）：车被扣在信号前，而扣住它的那处道岔
	 * **在 120 m 接近锁闭窗口之外** —— 因为 ① 的判据是"要进的那个区块的**出口**道岔"，区块能跨好几根轨。
	 *
	 * <p>网络（咽喉的形状，与 dev 世界 {@code -280,1800 → -200,1806} 那两处道岔同构）：</p>
	 * <pre>
	 *   Y(-20..-8 股道) -&gt; W(-8..192) -&gt; F1(192) { T(192..342,0) | C(192,0 -&gt; 342,6) }
	 *   F2(342,6) { 根部 S(342..422,6，站台) | 正线远端 A(282,6 -&gt; 342,6) }，C 是 F2 的**岔股**
	 * </pre>
	 * <p>F2 在位置 0（正线贯通）时"从 C 开不出去"（岔股那一侧禁止通行）—— 于是：</p>
	 * <ol>
	 *   <li>① 把车扣在 W 的末端（区块 C 的出口是选不出腿的道岔，正是规则要拦的形态）；</li>
	 *   <li>而 C 长 150 m ⇒ F2 落在窗口之外，"等车开近再申请"永远不会发生（车被扣着，开不近）。</li>
	 * </ol>
	 * <p>修前这里就是死锁：真正的申请集只有 F1 一处（{@code break} 把后面的全丢了），F2 一次申请都
	 * 发不出去，车在这条信号前停到天长地久；实时现场还连带把后面那列车扣在咽喉里（前车车头正压在
	 * F1 节点 10 m 净空区内，后车连"扳 F1"都被净空闸拒绝）。</p>
	 *
	 * <p>所以本用例断言的是**活性**：车必须自己走过 C 并到站台轨 S —— 修前 {@code tickUntil} 直接失败。</p>
	 */
	@Test
	public void aTrainHeldBeforeALongBlockRequestsTheForkBeyondItsApproachWindow() {
		final TwoForkNet n = new TwoForkNet("build/mmtr-authority-hold-long-block");
		final Vehicle v = n.spawn();
		final MmtrMission mission = new MmtrMission(v.getId(), MmtrMission.Kind.PASSENGER, n.siding.getId(), n.platform.getId(), 0L);
		mission.setExecutor(MmtrMission.Executor.AUTOPILOT, null);
		assertTrue(v.setMmtrMission(mission), "任务挂到车上（自臂会规划进路并申请道岔）");
		assertEquals(MmtrTurnout.NORMAL, n.effectivePositionOfF2(), "前提：F2 在位置 0（正线贯通）⇒ 从 C 开不出去");

		// 车必须自己走进长区块 C。修前它被 ① 扣在 W 的末端，而那处真正扣住它的道岔（150 m 外、
		// 窗口之外）一次申请也发不出去 ⇒ 这里直接超时失败。
		n.tickUntil(() -> n.c.getHexId().equals(v.getMmtrMotionWalker().railHex()), 6000);
		assertEquals(n.c.getHexId(), v.getMmtrMotionWalker().railHex(), "① 之后必须能走进区块 C（扣住它的那处道岔被申请并被扳动）");
		// 走进 C 的那一刻，F2 必须已经是"岔股开放"—— 那不是车自己走过去的结果，而是联锁按申请扳的。
		assertEquals(MmtrTurnout.REVERSE, n.effectivePositionOfF2(), "F2 必须被扳到岔股位（C 那一侧开通）");
		n.tickUntil(() -> n.s.getHexId().equals(v.getMmtrMotionWalker().railHex()), 6000);
		assertEquals(n.s.getHexId(), v.getMmtrMotionWalker().railHex(), "穿过 C 之后上站台轨 S");
	}

	/**
	 * 两处道岔的咽喉：F1 在接近轨末端，F2 在一条**长区块**（C）的出口 —— 见
	 * {@link #aTrainHeldBeforeALongBlockRequestsTheForkBeyondItsApproachWindow()} 的网络图。
	 */
	private static final class TwoForkNet {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-authority-long-block"), false);
		final Position yardBack = new Position(-20, 0, 0);
		final Position yardMouth = new Position(-8, 0, 0);
		final Position nodeF1 = new Position(192, 0, 0);
		final Position nodeF2 = new Position(342, 0, 6);
		final Rail y = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Rail w = through(yardMouth, nodeF1);
		final Rail t = through(nodeF1, new Position(342, 0, 0));
		final Rail c = through(nodeF1, nodeF2);
		final Rail a = through(new Position(282, 0, 6), nodeF2);
		final Rail s = Rail.newPlatformRail(nodeF2, Angle.fromAngle(0), new Position(422, 0, 6), Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Depot depot = new Depot(TransportMode.TRAIN, sim);
		final Siding siding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);
		final Station station = new Station(sim);
		final Platform platform = new Platform(nodeF2, new Position(422, 0, 6), TransportMode.TRAIN, sim);
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = newTrees();

		TwoForkNet(String savePath) {
			depot.setName("Yard");
			depot.setCorners(new Position(-25, -3, -3), new Position(100, 3, 15));
			station.setName("Far");
			station.setCorners(new Position(330, -5, -5), new Position(430, 5, 15));
			sim.rails.add(y);
			sim.rails.add(w);
			sim.rails.add(t);
			sim.rails.add(c);
			sim.rails.add(a);
			sim.rails.add(s);
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
			assertTrue(depot.savedRails.contains(siding), "the yard siding must attach to the depot");
			assertTrue(station.savedRails.contains(platform), "the platform must attach to the station");
			/*
			 * **把 F2 扳到位置 0**（正线贯通）—— 这就是 2026-09-26 现场存档里的状态，
			 * 也是本条用例的前提：作业单要走岔股那条进路（C），而道岔开着的是另一条。
			 *
			 * <p>必须走引擎自己的扳岔接口（它会**同时写行视图**）。直接改 {@code nodePositions} 不行：
			 * 新表型出来的道岔会被默认行（每个进向 leg 0）折回位置 1 —— 而"从岔股那一行看 leg 0"
			 * 恰恰就是"岔股开放"。这一层折算是既有行为，不在本片讨论范围内（现象与 notes/117 同族）。</p>
			 */
			assertTrue(sim.mmtrThrowTurnoutForIntent(nodeF2.getX(), nodeF2.getY(), nodeF2.getZ(), MmtrTurnout.NORMAL, null),
				"开跑前必须能把 F2 扳到位置 0（无人工锁、岔区净空干净）");
			// 网络前提（几何一变这条用例就该被重新审视）：C 是 F2 的岔股，位置 0 时"从 C 开不出去"。
			final MmtrTurnout f2 = sim.mmtrTurnout(nodeF2.getX(), nodeF2.getY(), nodeF2.getZ());
			assertNotNull(f2, "F2 must be modelled as a single turnout");
			assertEquals(c.getHexId(), f2.branchRailHex, "C must be F2's branch (the diverting leg)");
			assertNull(f2.continuationFrom(c.getHexId(), MmtrTurnout.NORMAL), "position 0 (through) must forbid leaving F2 via C");
			assertEquals(s.getHexId(), f2.continuationFrom(c.getHexId(), MmtrTurnout.REVERSE), "position 1 must open C <-> S");
			siding.tick();
		}

		Vehicle spawn() {
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, null, null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			return vehicle;
		}

		/** F2 的**生效位置**（联锁/持有者驱动的那一个，不是存档里的那一列数字）。 */
		int effectivePositionOfF2() {
			return sim.mmtrTurnoutPosition(nodeF2.getX(), nodeF2.getY(), nodeF2.getZ());
		}

		void tick() {
			trees.removeFirst();
			trees.add(new Object2ObjectAVLTreeMap<>());
			siding.simulateVehicles(1000, trees);
		}

		void tickUntil(java.util.function.BooleanSupplier condition, int maxTicks) {
			for (int i = 0; i < maxTicks && !condition.getAsBoolean(); i++) {
				tick();
			}
			assertTrue(condition.getAsBoolean(), "condition not met within " + maxTicks + " ticks");
		}
	}
}
