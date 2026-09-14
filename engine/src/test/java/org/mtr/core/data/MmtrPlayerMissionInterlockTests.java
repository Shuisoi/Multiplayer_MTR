package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.MmtrDriveAccess;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.mmtr.point.MmtrTurnout;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.route.MmtrRoute;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4：**任务 = 进路意图的显式来源**，玩家执行的任务与自动车走**同一条联锁路径**；
 * 以及**无任务不得操纵**的准入闸门。
 *
 * <p>修前那条路的门是 {@code executor == AUTOPILOT && mmtrMotionAuto}（`Vehicle` 里自臂与每 tick
 * 续期两处都是），于是**玩家执行的任务根本不发布进路、不申请道岔** —— 玩家开车执行任务到第一处道岔
 * 就撞上"位置停在默认 0、没有任何东西给它授权"的物理闸门，任务做不下去。</p>
 */
public final class MmtrPlayerMissionInterlockTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
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

	/** 车场 → 咽喉 → 岔口 → 站台：够让"从车场开到站台"成为一条要过道岔的真进路。 */
	private static final class Net {
		final Simulator sim;
		final Position fork = new Position(52, 0, 0);
		final Rail x;
		final Rail s;
		final Siding siding;
		final Platform platform;
		final BranchStore store = new BranchStore();
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = newTrees();

		Net(String savePath) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			final Position yardBack = new Position(-20, 0, 0);
			final Position yardMouth = new Position(-8, 0, 0);
			final Rail y = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			final Rail w = through(yardMouth, new Position(12, 0, 0));
			x = through(new Position(12, 0, 0), fork);
			// 站台那根轨必须是 **platform rail**，否则 Platform 挂不上车站（照抄 MmtrRouteConflictTests）。
			s = Rail.newPlatformRail(fork, Angle.fromAngle(0), new Position(92, 0, 0), Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			final Rail d = through(fork, new Position(92, 0, 20));
			final Depot depot = new Depot(TransportMode.TRAIN, sim);
			// 车站要先于站台建立（与 MmtrRouteConflictTests 同序）—— 反过来站台挂不上车站。
			final Station station = new Station(sim);
			siding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);
			platform = new Platform(fork, new Position(92, 0, 0), TransportMode.TRAIN, sim);
			depot.setName("T4 Yard");
			depot.setCorners(new Position(-25, -3, -3), new Position(100, 3, 25));
			station.setName("T4 Terminus");
			station.setCorners(new Position(45, -3, -3), new Position(100, 3, 25));
			sim.rails.add(y);
			sim.rails.add(w);
			sim.rails.add(x);
			sim.rails.add(s);
			sim.rails.add(d);
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
			assertTrue(depot.savedRails.contains(siding), "车场股道要挂在车辆段上");
			assertTrue(station.savedRails.contains(platform), "站台要挂在车站上");
			siding.tick();
			sim.mmtrEnsureSignalColors();
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
	}

	/**
	 * ① **玩家执行的任务同样发布进路、申请道岔、参与 SET 判定**，但**不接管油门**。
	 *
	 * <p>这两件事必须同时成立：只发进路不接管油门 = 玩家开车时联锁照样为他工作；
	 * 一旦连油门也接管了，"玩家执行的任务"就退化成自动车了。</p>
	 */
	@Test
	public void aPlayerMissionStillGetsItsRouteAndTurnoutsButNotTheThrottle() {
		final Net n = new Net("build/mmtr-t4-player");
		final Vehicle v = n.spawn();
		final MmtrMission mission = new MmtrMission(v.getId(), MmtrMission.Kind.PASSENGER, n.siding.getId(), n.platform.getId(), n.sim.getCurrentMillis());
		assertTrue(mission.setExecutor(MmtrMission.Executor.PLAYER, UUID.randomUUID()), "玩家执行的任务要能挂上");
		assertTrue(v.setMmtrMission(mission), "任务挂到车上");

		n.tick();

		final MmtrRoute route = n.sim.mmtrRoutes.route(v.getId());
		assertNotNull(route, "① 玩家执行的任务也要**发布进路**（修前这里永远是 null）");
		assertTrue(route.getForks().size() >= 1, "进路要带着这条咽喉的道岔需求");
		assertTrue(route.isEstablished(), "道岔申请到手 ⇒ 进路 SET（玩家与自动车同一条判定）");

		final MmtrTurnout turnout = n.sim.mmtrTurnout(n.fork.getX(), n.fork.getY(), n.fork.getZ());
		assertNotNull(turnout, "夹具的岔口是一处单开道岔");
		assertTrue(n.sim.mmtrPointAuthority.isGrantedTo(n.fork.getX(), n.fork.getY(), n.fork.getZ(), n.x.getHexId(), "v" + v.getId()),
			"① 道岔真的授权给了这列车（修前玩家车什么都拿不到）");

		assertFalse(v.isMmtrMotionAuto(), "**油门仍然留给司机**：引擎不接管");
	}

	/**
	 * ② **准入门槛：无任务不得操纵**。
	 *
	 * <p>用户裁定"玩家的一切行为都在任务内"，所以"无任务也能把车开走"就不是一条规则。
	 * 策略开关默认关（引擎单测与工具链大量在无任务前提下开车），真服务器上打开 ——
	 * 与 {@code mmtrDefaultPointsZero} 同一个模式。这里直接测那条判据的真值表。</p>
	 */
	@Test
	public void drivingWithoutATaskIsRefusedOnlyWhenThePolicyIsOn() {
		assertTrue(MmtrDriveAccess.taskAdmitsDriving(false, false), "策略关：不设闸（引擎单测/工具链照旧）");
		assertTrue(MmtrDriveAccess.taskAdmitsDriving(false, true), "策略关：有任务当然也放行");
		assertFalse(MmtrDriveAccess.taskAdmitsDriving(true, false), "② 策略开 + 无任务 ⇒ **不得操纵**");
		assertTrue(MmtrDriveAccess.taskAdmitsDriving(true, true), "策略开 + 有任务 ⇒ 放行");
	}

	/**
	 * ② 接线与默认值：闸门**默认关**，所以既有行为逐位不变。
	 *
	 * <p>为什么不在 Vehicle 层面断言"策略开 + 有任务 ⇒ 能操纵"：那道闸过了之后还有原有的
	 * "坐在司机座 + 持钥匙 + override 归属"三关，而本夹具没有骑乘的玩家，于是两种情况都返回 false，
	 * **断言不具区分度**（写了等于自我安慰）。真正有区分度的是这条判据本身（上一条用例）
	 * 与"默认关"这个事实；两者一起才说明"闸门存在且不改变现状"。</p>
	 */
	@Test
	public void theGateIsOffByDefaultSoExistingBehaviourIsUntouched() {
		final Net n = new Net("build/mmtr-t4-admission");
		assertFalse(n.sim.mmtrRequireTaskToDrive, "策略默认关（真服务器上才打开）");
		assertTrue(MmtrDriveAccess.taskAdmitsDriving(n.sim.mmtrRequireTaskToDrive, false),
			"默认关 ⇒ 无任务也不拦（这就是全量 561 例不受影响的原因）");
		final Vehicle v = n.spawn();
		assertFalse(v.canTakeMmtrControl(null), "没有骑乘者时本来就进不了操纵 —— 与任务无关");
	}
}
