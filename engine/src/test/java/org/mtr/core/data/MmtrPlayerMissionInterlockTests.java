package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.MmtrDriveAccess;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.mmtr.point.MmtrPointAuthority;
import org.mtr.core.mmtr.point.MmtrTurnout;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.route.MmtrRoute;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.mmtr.signal.MmtrShuntAuthority;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000}]"
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
		final Rail d;
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
			d = through(fork, new Position(92, 0, 20));
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

	/**
	 * ③ **进路类型由任务类型决定**，而不是从"此刻有没有调车授权"反推。
	 *
	 * <p>反推的病根不是答错，而是**不稳定**：授权一来/一走，同一条 movement 的类型就翻，
	 * 而 {@code MmtrRouteRegistry.request} 靠 {@code sameMovement}（含 kind）认"还是不是同一条进路"，
	 * 于是每翻一次就换一个新对象，把信号层与运营台手里的那个对象 churn 掉。
	 * 决策记录见 notes/122（含与设计文档字面的那一处偏离及理由）。</p>
	 */
	@Test
	public void theRouteKindComesFromTheTaskTypeNotFromTransientAuthority() {
		final MmtrMission passenger = new MmtrMission(1L, MmtrMission.Kind.PASSENGER, 1, 2, 0);
		final MmtrMission maneuver = new MmtrMission(1L, MmtrMission.Kind.MANEUVER, 1, 2, 0);
		assertEquals(MmtrRoute.Kind.MAIN, Vehicle.mmtrRouteKindOf(passenger, false), "客运作业、无副显示 ⇒ 列车进路");
		assertEquals(MmtrRoute.Kind.MAIN, Vehicle.mmtrRouteKindOf(null, false), "没有任务 ⇒ 默认列车进路");
		assertEquals(MmtrRoute.Kind.SHUNT, Vehicle.mmtrRouteKindOf(maneuver, false),
			"③ **调车作业就是调车进路**，哪怕此刻还没有副显示授权（修前这里答 MAIN，还会随授权翻）");
		assertEquals(MmtrRoute.Kind.SHUNT, Vehicle.mmtrRouteKindOf(passenger, true),
			"客运作业但拿了副显示授权 ⇒ 也按调车（安全侧：主灯不许清）");
	}

	/**
	 * ⑤ **调车进路同样受物理道岔预约约束** —— "副显示授权"不绕过联锁。
	 *
	 * <p>设计把这条列为待办，说法是"MmtrShuntAuthority 是绕过 S1 占用的**第二套授权**，与主进路并行"。
	 * 先核实它到底是不是缺口，结论是：**结构上已经满足** ——</p>
	 * <ul>
	 *   <li>道岔申请与 kind 无关（{@code armMmtrPointRun} 对两类进路一视同仁）；</li>
	 *   <li>{@code MmtrRouteRegistry.refresh} 的 SET 判据（授权 + T1 的物理位置）**没有 kind 过滤**。</li>
	 * </ul>
	 * <p>也就是"第二套授权"管的只是**副显示的灯显**与**S1 占用豁免**这两件事 —— 那正是设计要的
	 * （调车本来就可以进占用区段），不是绕过联锁。本用例把它钉住：道岔被别人按在互斥位置时，
	 * **即便手里握着副显示授权，调车进路也必须停在 PENDING**。</p>
	 */
	@Test
	public void aShuntRouteCannotBypassTheTurnoutReservation() {
		final Net n = new Net("build/mmtr-t4-shunt-interlock");
		final MmtrTurnout turnout = n.sim.mmtrTurnout(n.fork.getX(), n.fork.getY(), n.fork.getZ());
		assertNotNull(turnout, "夹具的岔口是一处单开道岔");
		final int legToPlatform = turnout.farLeg.getOrDefault(n.x.getHexId(), -1);
		final int legToBranch = turnout.branchLeg.getOrDefault(n.x.getHexId(), -1);
		assertTrue(legToPlatform >= 0 && legToBranch >= 0, "几何腿号要能取到");
		final long until = n.sim.getCurrentMillis() + 600_000L;
		final long shuntId = 42L;
		final String shuntOwner = "v" + shuntId;

		// 另一列车先把这处道岔按在**正线贯通**（它要去站台）—— 物理位置已经被别人占了
		assertEquals(MmtrPointAuthority.Result.GRANTED,
			n.sim.mmtrPointAuthority.request(n.fork.getX(), n.fork.getY(), n.fork.getZ(), n.x.getHexId(), "vOther", legToPlatform, until),
			"另一列车先拿到正线那一位");

		// 调车进路：手里确实有 C3a 的副显示授权
		n.sim.mmtrShuntAuthorities.grant(shuntId, n.x.getHexId(), n.d.getHexId(),
			MmtrShuntAuthority.Kind.SUBSIDIARY_SHUNT, 25, 5 * 60 * 1000L);
		assertNotNull(n.sim.mmtrShuntAuthorities.active(shuntId), "副显示授权已在手上");

		final ObjectArrayList<String> rails = new ObjectArrayList<>();
		rails.add(n.x.getHexId());
		rails.add(turnout.branchRailHex);
		final ObjectArrayList<String[]> forks = new ObjectArrayList<>();
		forks.add(new String[]{String.valueOf(n.fork.getX()), String.valueOf(n.fork.getY()), String.valueOf(n.fork.getZ()),
			n.x.getHexId(), String.valueOf(legToBranch)});
		final MmtrRoute route = n.sim.mmtrRoutes.request(new MmtrRoute(shuntId, shuntOwner, MmtrRoute.Kind.SHUNT,
			rails, forks, turnout.branchRailHex, n.sim.getCurrentMillis()));

		assertEquals(MmtrPointAuthority.Result.QUEUED,
			n.sim.mmtrPointAuthority.request(n.fork.getX(), n.fork.getY(), n.fork.getZ(), n.x.getHexId(), shuntOwner, legToBranch, until),
			"⑤ 申请道岔走的是**同一条路**：互斥位置被占 ⇒ 只能排队");

		n.sim.mmtrRoutes.refresh(shuntId, n.sim.mmtrPointAuthority);
		assertFalse(route.isEstablished(), "⑤ 拿不到道岔 ⇒ **调车进路也必须 PENDING**（副显示不等于绕过联锁）");
		assertTrue(route.getStateReason().contains("物理道岔"), "理由点名物理冲突：" + route.getStateReason());

		// 前车越岔让位 → 调车进路这才 SET（与列车进路**同一套判定**）
		n.sim.mmtrPointAuthority.passed(n.fork.getX(), n.fork.getY(), n.fork.getZ(), n.x.getHexId(), "vOther");
		n.sim.mmtrRoutes.refresh(shuntId, n.sim.mmtrPointAuthority);
		assertTrue(route.isEstablished(), "道岔到手 ⇒ 调车进路 SET（同一套判据，没有第二条路）");
	}
}
