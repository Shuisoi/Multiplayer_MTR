package org.mtr.core.mmtr.duty;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Station;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.mmtr.consist.MmtrCabState;
import org.mtr.core.mmtr.consist.MmtrConsistWalker;
import org.mtr.core.mmtr.job.MmtrCarSpec;
import org.mtr.core.mmtr.job.MmtrConsistJob;
import org.mtr.core.mmtr.job.MmtrJobStep;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * notes/408 S1：**玩家值守状态机**（引擎侧）。
 *
 * <p>夹具搭法照 {@code MmtrTaskDrivenCouplingTests} / {@code MmtrStationSubTaskTests}：
 * 车场股道 → 咽喉 → 站台轨，作业单用 {@code sim.upsertMmtrJob} 装（它会重建
 * {@code sim.mmtrJobScheduler}），推进用 {@code sim.step(1000)} 走完整 tick ——
 * 于是值守的插入点（车辆走行之后、作业调度器之前）在这一条路上是**真的被走过**的。</p>
 *
 * <p>每条断言的意图都写成注释；{@code [MMTR-DUTY]} 日志在这些用例里会真的打到 stdout，
 * 于是"日志里每一次迁移都有谁/哪趟车/从哪到哪/为什么"这条验收判据（notes/408 §5 S1）
 * 跑一次就能用眼睛核。</p>
 */
public final class MmtrDutyRegistryTests {

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
	 * 车场 → 咽喉 → 站台轨：够让"从车场开到站台"成为一条要过道岔的真进路
	 * （与 {@code MmtrPlayerMissionInterlockTests} 同一张网，只是把折返那根去掉了）。
	 */
	private static final class Net {
		final Simulator sim;
		final Position yardBack = new Position(-32, 0, 0);
		final Position yardMouth = new Position(-20, 0, 0);
		final Position node60 = new Position(60, 0, 0);
		final Rail yardRail;
		final Rail rX;
		final Rail rP;
		final Rail rFar;
		final Siding siding;
		final Siding farSiding;
		final Station station;
		final Platform platform;
		final BranchStore store = new BranchStore();
		final String platformRailHex;

		/**
		 * 夹具里那一列车的 id。
		 *
		 * <p>为什么不能靠"扫股道上的车"反查：车一旦走起来就**不在股道的车表里**了
		 * （{@code Siding.iterateVehicles} 只枚举停在股道上的车），于是"车在动"那些用例
		 * 会查不到自己的车。夹具自己记住 id 最稳。</p>
		 */
		long spawnedVehicleId;

		/** 每一拍走一个完整仿真步（1000ms）：时钟真的往前走（站台停留/到站判据都要它）。 */
		public long millis;

		Net(String savePath) {
			/*
			 * **清掉上一次跑留下的作业单文件**（{@code mmtr-jobs.json}）。
			 *
			 * <p>为什么要这一步（2026-10-09 实测）：{@code Simulator} 的构造器会把 savePath 下这份文件
			 * **加载回来**（{@code mmtrJobRegistry.fromFile}），而 {@code upsertMmtrJob} 又把它存回去 ——
			 * 于是同一个目录跑第二遍时，夹具里面装着**上一次那条作业单**。对既有用例没有影响
			 * （它们的断言不看"场上有几辆车次"），但对 ⑨ 那三条是致命的：同一个车次号（例如
			 * {@code D-021}）会**同时挂在两辆车上**（上一遍留下的那辆 + 这一遍的），
			 * 于是派车正确地报了"同名多辆" —— 那不是被测代码的 bug，是夹具不干净。</p>
			 */
			try {
				/*
				 * 落点是 {@code <rootPath>/<dimension>/mmtr-jobs.json}（Simulator 里 {@code savePath} 又拼了一层
				 * dimension，而夹具传进来的 rootPath 是"这一条用例的目录"）—— 少写这一层就会**删不掉**，
				 * 而测试仍然是绿的（只是又变回"跑第二遍才红"）。
				 */
				java.nio.file.Files.deleteIfExists(Paths.get(savePath).resolve("test").resolve("mmtr-jobs.json"));
			} catch (java.io.IOException e) {
				throw new AssertionError("清不掉上一次的作业单文件：" + savePath, e);
			}
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			yardRail = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			rX = through(yardMouth, node60);
			// 站台那根轨必须是 **platform rail**，否则 Platform 挂不上车站（照抄既有夹具的注释）。
			rP = Rail.newPlatformRail(node60, Angle.fromAngle(0), new Position(140, 0, 0), Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			platformRailHex = rP.getHexId();
			/*
			 * 站台轨尽头接一根**空股道**：它是给"作业单挂在别处、车却停在站台上"这种现场用的
			 * （见 {@link #dummySidingJob}）—— 那股道上永远没有车，所以作业单不会去动站台上那列。
			 */
			rFar = Rail.newSidingRail(new Position(140, 0, 0), Angle.fromAngle(0), new Position(200, 0, 0), Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			final Depot depot = new Depot(TransportMode.TRAIN, sim);
			station = new Station(sim);
			siding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);
			farSiding = new Siding(new Position(140, 0, 0), new Position(200, 0, 0), 12, TransportMode.TRAIN, sim);
			platform = new Platform(node60, new Position(140, 0, 0), TransportMode.TRAIN, sim);
			depot.setName("Duty Yard");
			depot.setCorners(new Position(-40, -5, -5), new Position(-10, 5, 5));
			station.setName("Duty Terminus");
			station.setCorners(new Position(50, -5, -5), new Position(150, 5, 5));
			sim.rails.add(yardRail);
			sim.rails.add(rX);
			sim.rails.add(rP);
			sim.rails.add(rFar);
			sim.depots.add(depot);
			sim.sidings.add(siding);
			sim.sidings.add(farSiding);
			sim.stations.add(station);
			sim.platforms.add(platform);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			assertTrue(station.savedRails.contains(platform), "站台要挂在车站上");
			siding.tick();
			farSiding.tick();
		}

		/** 一列单节车停在**车场股道**上（编组体车 —— 驾驶室钥匙、宽松到站判据都只对它成立）。 */
		Vehicle parkedConsist() {
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("emu", 2, 1, 10, 0, 1, 0.1, 0.1, true, "emu"));
			siding.setVehicleCars(cars);
			siding.clearParkedVehicles();
			final MmtrConsistWalker walker = siding.mmtrConsistWalkerFromYard(null, store, null);
			assertNotNull(walker, "编组体车要能放进车场股道");
			final Vehicle vehicle = siding.spawnMmtrConsistVehicle(walker, MmtrCabState.Cab.CAB_A);
			assertNotNull(vehicle, "编组体车的走行缝要能生成");
			spawnedVehicleId = vehicle.getId();
			return vehicle;
		}

		/** 一拍：整条 {@code Simulator.step} 路（车辆走行 → 自动车钩 → **值守** → … → 作业调度器）。 */
		void tick() {
			tick(1000);
		}

		/** 小步走（例如把残余速度走完、让车真的停下来）：{@code millisElapsed} 直接给采样步长。 */
		void tick(long millisElapsed) {
			millis += millisElapsed;
			sim.step(millisElapsed);
		}
	}

	/**
	 * 装一条"开到站台"的作业单：它的存在是值守能走到 {@code DRIVING} 的**前提**
	 * （{@code mmtrJobTakeover} 的第三道闸：车必须挂在某条作业单上）。
	 */
	private static MmtrConsistJob platformJob(String jobId, long sidingId, long platformId) {
		final MmtrConsistJob job = new MmtrConsistJob();
		job.jobId = jobId;
		job.sidingId = sidingId;
		job.startTimeOfDayMs = 0;
		job.repeatDaily = false;
		final MmtrCarSpec car = new MmtrCarSpec();
		car.vehicleId = "emu";
		car.length = 2;
		car.width = 1;
		car.capacity = 10;
		car.bogie1Position = 0;
		car.bogie2Position = 1;
		car.couplingPadding1 = 0.1;
		car.couplingPadding2 = 0.1;
		car.powered = true;
		car.consistTypeId = "emu";
		job.cars.add(car);
		final MmtrJobStep step = new MmtrJobStep();
		step.stepId = "s1";
		step.type = MmtrJobStep.StepType.MOVE_TO;
		step.targetId = platformId;
		step.dueTimeOfDayMs = 3_600_000;
		job.steps.add(step);
		return job;
	}

	/**
	 * 推到一个明确的时刻：作业**认领了车**（{@code jobIdOfVehicle} 不再是 null）、
	 * 且**车已经真的走起来**。
	 *
	 * <p>这两件事都不是"一拍就有"：调度器要先把股道上那列静止的车认成自己的实车、
	 * 再挂任务、再自臂，然后车辆那一拍才开始加速。所以用轮询而不是"tick N 次"。</p>
	 *
	 * <p><b>速度阈值用 {@code isMoving()}，不是自己写一个数</b>：{@code Vehicle.getSpeed()} 的单位是
	 * **m/ms**（120 km/h ≈ 0.033），所以"速度 &gt; 0.1"其实是"超过 360 km/h"——
	 * 第一版就是这么写的，于是轮询一次都没命中（探针实测：车从第 1 拍起 speed=6e-4 m/ms、moving=true）。
	 * "在动"这件事引擎已经有判据（{@code mmtrJobTakeover} 的静止闸门用的就是它），照用。</p>
	 */
	private static void tickUntilMoving(Net n) {
		for (int i = 0; i < 400; i++) {
			n.tick();
			final Vehicle vehicle = n.sim.mmtrFindVehicle(n.spawnedVehicleId);
			if (vehicle != null && n.sim.mmtrJobScheduler != null && n.sim.mmtrJobScheduler.jobIdOfVehicle(n.spawnedVehicleId) != null
					&& vehicle.isMoving()) {
				return;
			}
		}
		throw new AssertionError("车始终没有走起来（值守用例需要「车在动」这个现场）");
	}

	/** 推到"车已经停在它的目标上"（{@code mmtrDutyStoppedAtTarget}）那一刻，**在当拍**返回。 */
	private static void tickUntilStoppedAtTarget(Net n, Vehicle vehicle) {
		for (int i = 0; i < 4000; i++) {
			n.tick();
			if (vehicle.mmtrDutyStoppedAtTarget()) {
				return;
			}
		}
		throw new AssertionError("车始终没有停到目标上（值守的到站判据没有成立）");
	}

	// ------------------------------------------------------------------ ① 认领 → WAITING

	/**
	 * ① {@code claimPlatform} 落到 {@link MmtrDutyRegistry.State#WAITING}，并且：
	 * 记下"下一步的站台"、排一条 {@code direct=false} 的待办（游戏端据此**只播报不传送**）、
	 * 车上镜像字段说"这趟车还无人值守、但有 1 人认领"。
	 */
	@Test
	public void claimPlatformPutsThePlayerInWaitingAndOnTheNextStopPlatform() {
		final Net n = new Net("build/mmtr-duty-claim-platform");
		final Vehicle vehicle = n.parkedConsist();
		n.sim.upsertMmtrJob(platformJob("D-001", n.siding.getId(), n.platform.getId()));
		final MmtrDutyRegistry registry = n.sim.mmtrDuties;
		final UUID player = UUID.randomUUID();

		// 先让调度器认领这列车并挂上"开往站台"那一步 —— 否则"下一站是不是站台"根本无从判起。
		n.tick();
		assertNotNull(n.sim.mmtrJobScheduler.jobIdOfVehicle(vehicle.getId()), "作业单要能认领这列停在车场上的车");

		assertNull(registry.claimPlatform(player, "阿甲", vehicle.getId()), "站台接站应当被接受");
		final MmtrDutyRegistry.Duty duty = registry.of(player);
		assertNotNull(duty, "认领之后必须有值守记录（修前这个问题的答案散在三处、没有一处能回答）");
		assertEquals(MmtrDutyRegistry.State.WAITING, duty.state(), "① 站台接站 ⇒ 等待接站");
		assertEquals(vehicle.getId(), duty.vehicleId(), "认领绑的是这列车");
		assertEquals(n.platform.getId(), duty.waitPlatformId(), "记下的是**下一停站的站台**");
		assertEquals("等待接站", duty.stateWord(), "界面词与日志同一份编码");
		assertEquals("D-001", duty.jobId(), "车次号 = 作业单名");

		// 待办：站台接站**不传送任何人**（用户口径 2026-09-28），所以 direct 必须是 false。
		assertEquals(1, registry.pendingBoards().size(), "认领时排一条待办");
		final MmtrDutyRegistry.PendingBoard pending = registry.pendingBoards().get(0);
		assertEquals(player, pending.playerUuid());
		assertEquals(vehicle.getId(), pending.vehicleId());
		assertEquals(n.platform.getId(), pending.platformId(), "站台接站的待办带上「在哪个站台等」");
		assertFalse(pending.direct(), "站台接站不传送玩家 —— 这条待办只用来播一句话");

		// 镜像：人还不在车上 ⇒ 状态是空串；但"有 1 人认领"要让 PDA 看得见。
		assertEquals("", vehicle.getMmtrDutyStateFromSync(), "只有人在站台上等 ⇒ 这趟车**不算**有人值守（它还在自动跑）");
		assertEquals("", vehicle.getMmtrDutyCrewFromSync(), "没人拿着驾驶权就没有值守人");
		assertEquals(1, vehicle.getMmtrDutyWaitingFromSync(), "有 1 个人认领了这趟车");
		assertEquals(registry.of(player), registry.operatorOf(vehicle.getId()), "operatorOf 要能按车反查到这条值守");
	}

	// ------------------------------------------------------------------ ② 静止时 arriveAndBoard 直接进 DRIVING

	/**
	 * ② 车**静止**时 {@code arriveAndBoard} 直接进 {@link MmtrDutyRegistry.State#DRIVING}，
	 * 并且作业单的司机绑定与任务执行者**同时**落到这个 uuid 上（铁律 ①：DRIVING 只由接管成功进入）。
	 */
	@Test
	public void arriveAndBoardOnAStoppedTrainTakesOverImmediately() {
		final Net n = new Net("build/mmtr-duty-board-stopped");
		final Vehicle vehicle = n.parkedConsist();
		n.sim.upsertMmtrJob(platformJob("D-002", n.siding.getId(), n.platform.getId()));
		final MmtrDutyRegistry registry = n.sim.mmtrDuties;
		final UUID player = UUID.randomUUID();

		n.tick();
		final String jobId = n.sim.mmtrJobScheduler.jobIdOfVehicle(vehicle.getId());
		assertNotNull(jobId, "作业单要认领这列车");
		// "直接上车"这条路：游戏端会真的把人传送上车，所以引擎这边只等他回调。
		assertNull(registry.claimDirect(player, "阿乙", vehicle.getId()), "直接上车应当被接受");

		assertNull(registry.arriveAndBoard(player, vehicle.getId()), "回调应当成功");
		final MmtrDutyRegistry.Duty duty = registry.of(player);
		assertEquals(MmtrDutyRegistry.State.DRIVING, duty.state(), "② 车静止 + 交接条件成立 ⇒ 直接运转中");
		assertEquals(player, n.sim.mmtrJobScheduler.driverOf(jobId), "② 作业单的司机绑定就是这个 uuid");
		final MmtrMission mission = vehicle.getMmtrMission();
		assertNotNull(mission, "车上应当有这一步的任务");
		assertEquals(MmtrMission.Executor.PLAYER, mission.getExecutor(), "② 任务执行者换成 PLAYER");
		assertEquals(player, mission.getExecutorPlayer(), "② 且执行者就是这个 uuid（不是我、也不是别人）");
		assertEquals("DRIVING", vehicle.getMmtrDutyStateFromSync(), "车上镜像说「运转中」");
		assertEquals(player.toString(), vehicle.getMmtrDutyCrewFromSync(), "镜像里的值守人是他");
	}

	// ------------------------------------------------------------------ ③ 车在动 ⇒ 只到 ABOARD，且**不**调用接管

	/**
	 * ③ 车**在动**时只到 {@link MmtrDutyRegistry.State#ABOARD}，并且**接管没有被调用**——
	 * 判据是"作业单的司机仍是 null"（红证：如果值守在车动时也去交接，
	 * {@code mmtrJobTakeover} 会拒，但若有人把静止闸门挪掉，这一条就会变成"司机已经是他"）。
	 */
	@Test
	public void aMovingTrainOnlyReachesAboardAndNeverCallsTakeover() {
		final Net n = new Net("build/mmtr-duty-board-moving");
		final Vehicle vehicle = n.parkedConsist();
		n.sim.upsertMmtrJob(platformJob("D-003", n.siding.getId(), n.platform.getId()));
		final MmtrDutyRegistry registry = n.sim.mmtrDuties;
		final UUID player = UUID.randomUUID();

		tickUntilMoving(n);
		final String jobId = n.sim.mmtrJobScheduler.jobIdOfVehicle(vehicle.getId());
		assertNotNull(jobId, "作业单要认领这列车");
		assertTrue(vehicle.isMoving(), "现场条件：车真的在动（speed=" + vehicle.getSpeed() + " m/ms）");

		assertNull(registry.claimDirect(player, "阿丙", vehicle.getId()), "车在动也可以先认领（ABOARD 存在的理由）");
		assertNull(registry.arriveAndBoard(player, vehicle.getId()), "回调本身不是错误（人确实到了）");

		final MmtrDutyRegistry.Duty duty = registry.of(player);
		assertEquals(MmtrDutyRegistry.State.ABOARD, duty.state(), "③ 车还在动 ⇒ 只到「已上车·未获驾驶权」");
		assertNull(n.sim.mmtrJobScheduler.driverOf(jobId), "③ 车在动时**不许**交接：作业单的司机仍然是 null");
		assertEquals("ABOARD", vehicle.getMmtrDutyStateFromSync(), "镜像如实说「已上车·未获驾驶权」");
	}

	// ------------------------------------------------------------------ ④ 停稳后从 ABOARD 自动进 DRIVING

	/**
	 * ④ 车停稳后，值守**自己**把 {@code ABOARD} 推到 {@code DRIVING}（用户口径：
	 * "在停站符合交接条件后，明确赋予玩家驾驶的权利"读起来是自动给的）——
	 * 而且这一条要求**驾驶室钥匙在他手里**（否则就是"替一个可能已经走开的人按接管"）。
	 *
	 * <p>所以这里的两半都断：①车还在动时**不许**推进（铁律 ②，钥匙已经在手里也不行）；
	 * ②车停稳之后下一拍自动进 DRIVING。</p>
	 *
	 * <p><b>钥匙为什么必须在车停着的时候先拿到</b>：{@code enterMmtrCab} 自己就要求车静止
	 * （"司机不可能走进一列开着车的驾驶室"），而且它会把引擎自臂出来的自动运行放掉
	 * （{@code mmtrMotionStopTargetM} 一并清掉）。所以现场只能是"人先在静止的车场里坐进驾驶室、
	 * 车再自己开出去"——这也正是站台接站那位玩家的真实动作顺序（他先上车，车才动）。</p>
	 */
	@Test
	public void aboardPromotesToDrivingOnceStoppedAndTheKeyIsHis() {
		final Net n = new Net("build/mmtr-duty-aboard-to-driving");
		final Vehicle vehicle = n.parkedConsist();
		n.sim.upsertMmtrJob(platformJob("D-004", n.siding.getId(), n.platform.getId()));
		final MmtrDutyRegistry registry = n.sim.mmtrDuties;
		final UUID player = UUID.randomUUID();

		// 游戏端"进驾驶室"那一步就是这一句：此时车还停在车场，钥匙能真的交出去。
		assertTrue(vehicle.enterMmtrCab(MmtrCabState.Cab.CAB_A, player), "车停着时进驾驶室要成功");
		assertEquals(player, vehicle.getMmtrCrewUuid(), "钥匙要真的在他手里");

		tickUntilMoving(n);
		final String jobId = n.sim.mmtrJobScheduler.jobIdOfVehicle(vehicle.getId());
		assertNotNull(jobId, "作业单要认领这列车");
		assertNull(registry.claimDirect(player, "阿丁", vehicle.getId()), "先认领");
		assertNull(registry.arriveAndBoard(player, vehicle.getId()), "车在动 ⇒ 只落到 ABOARD");
		assertEquals(MmtrDutyRegistry.State.ABOARD, registry.of(player).state(), "起点是 ABOARD");

		// ① 车还在动：即便钥匙在他手里也**不许**交接（铁律 ②）。
		n.tick();
		assertEquals(MmtrDutyRegistry.State.ABOARD, registry.of(player).state(), "④ 车还在动（钥匙在他手里）⇒ 仍然停在 ABOARD");
		assertNull(n.sim.mmtrJobScheduler.driverOf(jobId), "④ 车在动时作业单的司机还是 null");

		/*
		 * ② 车停到站台上（作业单本来就是要开过去）+ 钥匙在他手里 ⇒ 下一拍自动进 DRIVING。
		 * 用轮询而不是"tick N 次"：到站要多久取决于物理，不是本用例要测的东西。
		 */
		for (int i = 0; i < 4000 && registry.of(player).state() != MmtrDutyRegistry.State.DRIVING; i++) {
			n.tick();
		}
		assertEquals(MmtrDutyRegistry.State.DRIVING, registry.of(player).state(),
			"④ 停稳 + 钥匙在他手里 ⇒ 值守自动把驾驶权交给他（" + registry.of(player).reason() + "）");
		assertEquals(player, n.sim.mmtrJobScheduler.driverOf(jobId), "④ 作业单的司机绑定变成他");
		assertEquals(MmtrMission.Executor.PLAYER, vehicle.getMmtrMission().getExecutor(), "④ 任务执行者也变成 PLAYER");
	}

	// -------------------------------------------------- ④b 站台接车：先把上一个玩家弹出车厢

	/**
	 * 用户口径（2026-10-09 逐字）："在车站接车时**先将上个玩家弹出车厢**后，另一边玩家进入驾驶室"。
	 *
	 * <p>为什么必须有这一步：{@code MmtrCabState.insertKey} **拒绝第二名乘务员**
	 * （只有引擎的占位钥匙能被乘务员顶掉），所以上一个司机的钥匙不收回来，新玩家**永远上不了车**；
	 * 而 {@link MmtrDutyRegistry#tickWaiting} 的出口要求"钥匙在他手里" ⇒ 站台接车会死在
	 * "等一个永远不会空出来的驾驶室"上，而且**一句原因都没有**。</p>
	 *
	 * <p>这条用例把三件事一起断：①钥匙被收回；②那个人的值守落到"已退出"（不许占着车）；
	 * ③排了一条 {@code EJECT} 待办 —— 物理"弹出车厢"只有游戏端能做，引擎只能排待办。</p>
	 */
	@Test
	public void aStationMeetEjectsThePreviousCrewAndTakesHisKey() {
		final Net n = new Net("build/mmtr-duty-eject-previous");
		final Vehicle vehicle = n.parkedConsist();
		n.sim.upsertMmtrJob(platformJob("D-006", n.siding.getId(), n.platform.getId()));
		final MmtrDutyRegistry registry = n.sim.mmtrDuties;
		final UUID previous = UUID.randomUUID();
		final UUID next = UUID.randomUUID();

		// 现场照 ⑥：上一个司机在车**停着**的时候进驾驶室（钥匙这时才交得出去），车再自己开出去。
		assertTrue(vehicle.enterMmtrCab(MmtrCabState.Cab.CAB_A, previous), "车停着时上一个司机进驾驶室");
		tickUntilMoving(n);
		assertNotNull(n.sim.mmtrJobScheduler.jobIdOfVehicle(vehicle.getId()), "作业单要认领这列车");
		assertNull(registry.claimDirect(previous, "阿己", vehicle.getId()), "上一个司机认领");
		assertNull(registry.arriveAndBoard(previous, vehicle.getId()), "车在动 ⇒ 先到已上车·未获驾驶权");
		for (int i = 0; i < 4000 && registry.of(previous).state() != MmtrDutyRegistry.State.DRIVING; i++) {
			n.tick();
		}
		assertEquals(MmtrDutyRegistry.State.DRIVING, registry.of(previous).state(), "到站后他拿到驾驶权");
		assertTrue(vehicle.holdsMmtrCabKey(previous), "现场条件：钥匙在他手里（编组被占用）");
		assertNull(registry.armExitAtNextStop(previous), "他挂「下一站退出」：到站把车交给接站的人");

		// 新玩家：站台接站（不传送他 —— 他自己走到站台上按 G 上车）。
		assertNull(registry.claimPlatform(next, "阿庚", vehicle.getId()), "新玩家站台接站认领");
		assertEquals(MmtrDutyRegistry.State.WAITING, registry.of(next).state(), "他在站台上等");

		/*
		 * 到站停稳那一拍：车交给新玩家，**同时**把钥匙从上一个司机手里转下来、排一条弹出待办。
		 * 不转下来，新玩家按 G 时会撞上 {@code insertKey} 的"拒绝第二名乘务员"——永远上不了车，
		 * 而且一句原因都没有（这正是本用例存在的理由）。
		 */
		boolean handedOver = false;
		for (int i = 0; i < 4000 && !handedOver; i++) {
			n.tick();
			handedOver = registry.of(next) != null && registry.of(next).state() == MmtrDutyRegistry.State.DRIVING;
		}
		assertTrue(handedOver, "① 到站把车交给等着接的新玩家（新玩家现在="
			+ (registry.of(next) == null ? "无" : registry.of(next).stateWord()) + "）");
		assertTrue(vehicle.holdsMmtrCabKey(next), "② 钥匙要写进新司机手里（收旧钥匙与写新钥匙必须连在一起，中间不许空占用端）");
		assertFalse(vehicle.holdsMmtrCabKey(previous), "③ 上一个司机的钥匙必须被收回（不收回新玩家进不了驾驶室）");
		assertEquals(MmtrDutyRegistry.State.RELEASED, registry.of(previous).state(), "④ 他的值守落到已退出（别占着车）");
		assertTrue(registry.pendingBoards().stream().anyMatch(board -> board.isEject() && previous.equals(board.playerUuid())),
			"⑤ 要排一条「把上一个玩家弹出车厢」的待办（物理那一半只有游戏端能做）");
	}

	// ------------------------------------------------------------------ ⑤ 下一站退出 → 到站自动交还

	/**
	 * ⑤ {@code armExitAtNextStop} → {@link MmtrDutyRegistry.State#DRIVING_EXIT_ARMED}；
	 * 到站停稳后自动交还 ⇒ {@link MmtrDutyRegistry.State#RELEASED}、作业单回到 {@code AUTOPILOT}。
	 *
	 * <p>用"已经停在站台上"的现场（{@link #parkConsistAtPlatform}）：交接与到站判据都立刻成立，
	 * 于是这条用例测的是**交还**那一半，不必把"开过去"再跑一遍。</p>
	 */
	@Test
	public void armExitAtNextStopReleasesBackToAutopilotOnArrival() {
		final Net n = new Net("build/mmtr-duty-exit-armed");
		final Vehicle vehicle = parkConsistAtPlatform(n);
		n.sim.upsertMmtrJob(platformJob("D-005", n.siding.getId(), n.platform.getId()));
		final MmtrDutyRegistry registry = n.sim.mmtrDuties;
		final UUID player = UUID.randomUUID();

		n.tick();
		final String jobId = n.sim.mmtrJobScheduler.jobIdOfVehicle(vehicle.getId());
		assertNotNull(jobId, "作业单要认领这列车");
		assertNull(registry.claimDirect(player, "阿戊", vehicle.getId()), "先认领");
		assertNull(registry.arriveAndBoard(player, vehicle.getId()), "车停在站台上 ⇒ 直接接管");
		assertEquals(MmtrDutyRegistry.State.DRIVING, registry.of(player).state(), "起点是运转中");
		assertEquals(player, n.sim.mmtrJobScheduler.driverOf(jobId), "司机绑定已是他");

		assertNull(registry.armExitAtNextStop(player), "挂「下一站退出」应当成功");
		assertEquals(MmtrDutyRegistry.State.DRIVING_EXIT_ARMED, registry.of(player).state(), "⑤ 挂上之后是独立的那个态");
		assertEquals("DRIVING_EXIT_ARMED", vehicle.getMmtrDutyStateFromSync(), "⑤ 车上镜像说「运转中·下一站退出」");

		n.tick();
		assertEquals(MmtrDutyRegistry.State.RELEASED, registry.of(player).state(), "⑤ 到站停稳 ⇒ 自动交还（已退出）");
		assertNull(n.sim.mmtrJobScheduler.driverOf(jobId), "⑤ 作业单回到自动：司机绑定被清掉");
		assertEquals(MmtrMission.Executor.AUTOPILOT, vehicle.getMmtrMission().getExecutor(), "⑤ 任务执行者也回到 AUTOPILOT");
		assertEquals("", vehicle.getMmtrDutyStateFromSync(), "⑤ 没人值守了 ⇒ 车上镜像回到空串");
		assertEquals(0, vehicle.getMmtrDutyWaitingFromSync(), "⑤ 已退出的人不再算「认领」");

		/*
		 * ⑤补：**归还真的把车重新点起来**（2026-10-09 实机回归，用户原话"退出后也需要让AI重新接管啊"）。
		 *
		 * <p>症状：交出驾驶权之后 {@code job status} 已经是"执行者=AUTOPILOT 速度 0.0"，可车 20 秒没动
		 * —— 因为旧代码归还时手动 {@code setMmtrMotionAuto(true)}，而这个标志在本代码库里不是
		 * "允许自动开"，是"自动步进已经武装好了"（自臂成功时自己置真）。它被置真之后
		 * {@code Vehicle} 每 tick 的自臂门（{@code !mmtrMotionAuto && …}）反而把它自己挡住 ⇒
		 * 执行者是 AUTOPILOT、车却没有任何目的地。修法是把归还接到与"调度器挂完一步"同一个入口
		 * （{@code vehicle.mmtrArmActiveMissionNow(this)}，见 {@code Simulator.mmtrJobRelease}）。</p>
		 *
		 * <p>所以这一条钉的是：归还之后 {@code isMmtrMotionAuto()} 必须**还是 false**
		 * （= 自臂的门开着），于是下一拍自臂跑得起来。红证：把 {@code Simulator.mmtrJobRelease} 里那句
		 * 改回 {@code vehicle.setMmtrMotionAuto(true)} ⇒ 本断言红。</p>
		 */
		assertFalse(vehicle.isMmtrMotionAuto(),
			"⑤ 归还之后不许把 mmtrMotionAuto 置真 —— 它是「自动步进已武装」，置真会把下一拍的自臂门挡住");

		// 再走几拍：**把任务换成一步"有地方可去"的**（原来那一步已经做完了，没有目的地可自臂），
		// 于是"归还之后自臂跑得起来"这件事才有可观测的形状 —— 车真的重新动起来。
		//
		// 为什么不在归还那一刻就换：mmtrJobRelease 自己会调 mmtrArmActiveMissionNow，
		// 它认的是"当下车上挂着的那一步"。这里要证的恰恰是**之后**每一拍的自臂门是开的。
		assertTrue(vehicle.setMmtrMission(null), "先把那一步做完的任务撤掉");
		final MmtrMission onwards = new MmtrMission(vehicle.getId(), MmtrMission.Kind.MANEUVER, n.siding.getId(), n.siding.getId(), n.millis);
		assertTrue(vehicle.setMmtrMission(onwards), "换上一趟回库（车场那股道在 100m 之外，真的要走）");
		for (int i = 0; i < 200 && !vehicle.isMoving(); i++) {
			n.tick();
		}
		assertTrue(vehicle.isMoving(),
			"⑤ 归还之后自臂能跑起来、车重新有目的地（修前 mmtrMotionAuto 被置真，自臂门永远关着 ⇒ 一直不动）");
	}

	// ------------------------------------------------------------------ ⑥ 有 WAITING 的人 ⇒ 到站把车交给他

	/**
	 * ⑥ 有人在站台上等着接时，到站把车交给**他**，而不是先还给自动（notes/408 §2.1 的第 ③ 条：
	 * 接站的人在到站时**有权直接接手**）。
	 *
	 * <p>现场是**真的开过去**（车从车场出发、开向站台）：乙方认领的"下一站"就是作业单那一步的目标，
	 * 所以必须在那一步还活着的时候认领 —— 一步做完（{@code COMPLETE}）之后引擎就不知道下一站去哪了
	 * （这正是 {@code refusalForPlatformTarget} 里那句"等它挂上下一步再认领"）。</p>
	 */
	@Test
	public void aWaitingPlayerTakesOverAtTheStopInsteadOfTheAutopilot() {
		final Net n = new Net("build/mmtr-duty-handover");
		final Vehicle vehicle = n.parkedConsist();
		n.sim.upsertMmtrJob(platformJob("D-006", n.siding.getId(), n.platform.getId()));
		final MmtrDutyRegistry registry = n.sim.mmtrDuties;
		final UUID driver = UUID.randomUUID();
		final UUID waiter = UUID.randomUUID();

		// 甲先坐进驾驶室（车还停在车场，钥匙才交得出去），车再自己开出去。
		assertTrue(vehicle.enterMmtrCab(MmtrCabState.Cab.CAB_A, driver), "车停着时甲进驾驶室要成功");
		tickUntilMoving(n);
		final String jobId = n.sim.mmtrJobScheduler.jobIdOfVehicle(vehicle.getId());
		assertNotNull(jobId, "作业单要认领这列车");

		// 甲在开，并且挂了「下一站退出」。
		assertNull(registry.claimDirect(driver, "甲", vehicle.getId()), "甲认领");
		assertNull(registry.arriveAndBoard(driver, vehicle.getId()), "甲上车（车在动 ⇒ 先到已上车未获权）");
		assertEquals(MmtrDutyRegistry.State.ABOARD, registry.of(driver).state(), "甲这时还没拿到权（车在动）");
		for (int i = 0; i < 4000 && registry.of(driver).state() != MmtrDutyRegistry.State.DRIVING; i++) {
			n.tick();
		}
		assertEquals(MmtrDutyRegistry.State.DRIVING, registry.of(driver).state(), "甲拿到驾驶权了");
		assertNull(registry.armExitAtNextStop(driver), "甲挂「下一站退出」");

		/*
		 * 乙在站台上等着接站：挂在"下一站退出"的车**允许**别人接站（§2.2 那张表），
		 * 而同一趟车对别人**不允许**直接上车（座位上还坐着甲）。
		 *
		 * <p>乙**不**在这里拿钥匙：站台接站不传送他，他自己按 G 上车的那一刻游戏端才会
		 * {@code enterMmtrCab}（而那一句本来也要求车静止）。所以本用例走的是
		 * "甲到站交还 → 直接把车交给等着接的乙"这一条路，乙在那一刻接手的证据就是作业单的司机绑定。</p>
		 */
		assertNull(registry.refusalForPlatformMeet(waiter, vehicle.getId()), "有人·下一站退出的车允许接站");
		assertNotNull(registry.refusalForDirectBoard(waiter, vehicle.getId()), "同一趟车不允许直接上车（位置上还坐着甲）");
		assertNull(registry.claimPlatform(waiter, "乙", vehicle.getId()), "乙站台接站");
		assertEquals(MmtrDutyRegistry.State.WAITING, registry.of(waiter).state(), "乙在等");

		// 车开到站台停稳那一拍：值守把车**交给乙**（而不是回自动）。
		boolean handedOver = false;
		for (int i = 0; i < 4000 && !handedOver; i++) {
			n.tick();
			handedOver = registry.of(waiter) != null && registry.of(waiter).state() == MmtrDutyRegistry.State.DRIVING;
		}
		assertTrue(handedOver, "⑥ 到站时车交给等着接的乙，而不是回自动（乙现在="
			+ (registry.of(waiter) == null ? "无" : registry.of(waiter).stateWord()) + "）");
		assertEquals(waiter, n.sim.mmtrJobScheduler.driverOf(jobId), "⑥ 作业单的司机绑定是乙");
		assertEquals(waiter.toString(), vehicle.getMmtrDutyCrewFromSync(), "⑥ 车上镜像里的值守人也是乙");
	}

	// ------------------------------------------------------------------ ⑥补 到站通知只许出现一次

	/**
	 * ⑥补 **到站通知在"同一站停稳期间"只许出现一次**（2026-10-09 实机回归：14 秒 262 行同一句话）。
	 *
	 * <p>症状来自两个各自都对的机制撞车：引擎靠"待办还在不在"判断要不要通知，而游戏端
	 * **必须每拍消费掉待办**（播一句话，然后无论成败都调 {@link MmtrDutyRegistry#markBoardDone}；
	 * 不这样清，待办就永远躺着、每拍重复播报）。于是：没待办 → 排队+记日志 → 被消费 → 又没待办 → …，
	 * 玩家动作栏每秒被刷 ~19 条。</p>
	 *
	 * <p>所以这里**如实模拟**游戏端那一拍：每次 {@link MmtrDutyRegistry#pendingBoards()} 非空就取走
	 * 并 {@code markBoardDone}，然后数"通知"的次数。守卫必须是引擎自己按站记的闩
	 * （{@code notifiedAtStop}），不是"待办还在不在"。红证：把守卫改回
	 * {@code if (!hasPendingBoard(uuid))} ⇒ 本断言立刻红（会数出与拍数同量级的次数）。</p>
	 */
	@Test
	public void theArrivalNoticeIsSentOnlyOncePerStop() {
		final Net n = new Net("build/mmtr-duty-notice-once");
		final Vehicle vehicle = parkConsistAtPlatform(n);
		n.sim.upsertMmtrJob(platformJob("D-009", n.siding.getId(), n.platform.getId()));
		final MmtrDutyRegistry registry = n.sim.mmtrDuties;
		final UUID player = UUID.randomUUID();

		// ① 作业单认领这列车（交接的第三道闸要它）。
		n.tick();
		assertNotNull(n.sim.mmtrJobScheduler.jobIdOfVehicle(vehicle.getId()), "作业单要认领这列车");

		/*
		 * ② 把这列车**停稳在站台上**（用 100ms 的小步走完残余速度），然后给它换一步
		 * **目标就是站台、又不会自动完成**的任务。
		 *
		 * <p>为什么要这么搭：要测的是"到站停稳期间只通知一次"，那就必须让
		 * {@code mmtrDutyStoppedAtTarget()} 在**多拍里持续为真**，同时"下一步是站台"还得成立
		 * （{@code claimPlatform} 要求它）。作业单那条路做不到 —— 它一停到站台那一步就
		 * {@code COMPLETE}（实机日志顺序："到站 → 停稳 → 本步作业完成"），之后引擎只会答
		 * "还不知道下一步去哪"。所以这里直接挂一个 MANEUVER：目标 = 站台轨，
		 * 车就在那根轨上 ⇒ 观测判据真、而任务不终态。这是"停稳 + 下一步是站台"最干净的现场。</p>
		 */
		for (int i = 0; i < 200 && !vehicle.mmtrDutyStoppedAtTarget(); i++) {
			n.tick(100);
		}
		assertTrue(vehicle.setMmtrMission(null), "先把引擎挂的那一步撤掉（setMmtrMission 一条活着的不许覆盖）");
		final MmtrMission liveAtPlatform = new MmtrMission(vehicle.getId(), MmtrMission.Kind.MANEUVER,
			n.siding.getId(), n.platform.getId(), n.millis);
		assertTrue(vehicle.setMmtrMission(liveAtPlatform), "换上一刻活的、目标就是站台的任务");
		assertTrue(vehicle.mmtrDutyStoppedAtTarget(), "现场条件：车停在站台上（到站这条事实为真）");
		assertFalse(liveAtPlatform.isTerminal(), "现场条件：这一步还没终态（否则引擎答不出下一站）");

		// ③ 认领 + 等：直接调 registry.tick，不推进车辆 —— 这样"通知了几次"是干净可数的。
		assertNull(registry.claimPlatform(player, "阿庚", vehicle.getId()), "站台接站");
		assertEquals(MmtrDutyRegistry.State.WAITING, registry.of(player).state(), "他在等");

		final long before = MmtrDutyRegistry.noticeLogCount;
		for (int i = 0; i < 10; i++) {
			registry.tick(n.millis + i * 100L);
		}
		final long logged = MmtrDutyRegistry.noticeLogCount - before;
		assertEquals(1, logged,
			"同一站停稳期间「车到站停稳，通知他可以上车接管了」只许记一次（实机修前 14 秒 262 条）");
		assertEquals(1, registry.pendingBoards().size(),
			"而且待办只排了一条（不是每拍一条）");
		assertEquals(MmtrDutyRegistry.State.WAITING, registry.of(player).state(),
			"他还没上车 ⇒ 记录如实停在等待接站（通知过不等于已经在车上）");
		assertFalse(vehicle.holdsMmtrCabKey(player), "他没上车拿钥匙 ⇒ 不该被自动接管");
	}

	// ------------------------------------------------------------------ ⑦ 拒绝路径
	/**
	 * ⑦ 拒绝路径（PDA 的按钮出不出现读的就是这一组）：
	 * 无人认领的车被别人认领、同一人重复认领、认领不存在的车、以及"有人·运转中"那趟车的两条路。
	 */
	@Test
	public void refusalsCoverTheAvailabilityTable() {
		final Net n = new Net("build/mmtr-duty-refusals");
		final Vehicle vehicle = n.parkedConsist();
		n.sim.upsertMmtrJob(platformJob("D-007", n.siding.getId(), n.platform.getId()));
		final MmtrDutyRegistry registry = n.sim.mmtrDuties;
		final UUID first = UUID.randomUUID();
		final UUID second = UUID.randomUUID();

		// 认领不存在的车：两条路都要拒，并且都要说出"找不到"。
		assertNotNull(registry.refusalForDirectBoard(first, 987654321L), "不存在的车不能直接上车");
		assertNotNull(registry.claimDirect(first, "甲", 987654321L), "claimDirect 也要拒");
		assertNotNull(registry.claimPlatform(first, "甲", 987654321L), "站台接站同样拒");
		assertNull(registry.of(first), "被拒的认领不许留下值守记录");

		/*
		 * 车还没被任何作业单认领 ⇒ **站台接站**要拒（引擎不知道它下一站去哪）。
		 *
		 * <p>刻意**不**断言"停着又没挂作业单 ⇒ 直接上车也拒"（{@code refusalForDirectBoard} 里最上面
		 * 那一条"交不出驾驶权"）：那条判据的前提是 {@code mmtrDutyStoppedAtTarget()} 为真，
		 * 而"没挂作业"的裸车实测**答不出**这个事实（=false，见下面那行的现场读数）——
		 * 于是那条分支在单测里搭不出可判的现场（它要的是"车停在某站台上"且"恰好没有作业单"）。
		 * 留着它是因为真实现场会走到（车停在站台上、调度器还没认领它），
		 * 但它的证据只能等一次实机/集成观察，这里如实说明，不写一条不具区分度的断言充数。
		 */
		assertNotNull(registry.refusalForPlatformMeet(first, vehicle.getId()), "没有在跑的一步 ⇒ 站台接站拒（引擎不知道下一站）");

		n.tick();
		assertNull(registry.claimDirect(first, "甲", vehicle.getId()), "第一个认领成功");
		assertNull(registry.arriveAndBoard(first, vehicle.getId()), "并且拿到驾驶权");
		assertEquals(MmtrDutyRegistry.State.DRIVING, registry.of(first).state(), "甲在运转中");

		// 同一人重复认领：不许再排一条待办、也不许换态。
		assertNotNull(registry.claimDirect(first, "甲", vehicle.getId()), "同一人重复认领要被拒（他在运转中）");

		// 别人：两条路都拒（§2.2 那张表 —— 有玩家·运转中的车谁都不能上）。
		assertNotNull(registry.refusalForDirectBoard(second, vehicle.getId()), "有人运转中 ⇒ 不许直接上车");
		assertNotNull(registry.refusalForPlatformMeet(second, vehicle.getId()), "有人运转中 ⇒ 也不许站台接站（到站位置不会空出来）");
		assertNotNull(registry.claimDirect(second, "乙", vehicle.getId()), "claimDirect 照同一份判据拒");
		assertNotNull(registry.claimPlatform(second, "乙", vehicle.getId()), "claimPlatform 照同一份判据拒");

		// 车在动 / 停在站台上的两种现场各有一半，见 ③ 与 ⑥。
		assertEquals(first, registry.operatorOf(vehicle.getId()).playerUuid(), "operatorOf 认的是真正占着车的那位");
	}

	/**
	 * ⑦补：**"下一站不是站台"必须拒绝站台接站**（notes/408 §6.1）——
	 * 把人送到一个没有站台能上车的股道上比拒绝更糟。
	 *
	 * <p>现场是**手搭**的，理由要说清：作业单那条路走不到这个分支 ——
	 * MOVE_TO 到股道之后那一步立刻就 {@code COMPLETE}（实机日志里的顺序就是
	 * "到站 → 停稳 → 本步作业完成"），而 {@code COMPLETE} 的那一步答的是另一条拒绝理由
	 * （"引擎还不知道下一步去哪"）。要测的却是**目标活着的**时候那句"不是站台"
	 * （回库/调车的真实现场就是这样：目的地是一处股道，而车正在开过去）。
	 * 所以这里直接把任务定到一股道上、并且让它**不**达到终态。</p>
	 */
	@Test
	public void platformMeetIsRefusedWhenTheNextStopIsNotAPlatform() {
		final Net n = new Net("build/mmtr-duty-refuse-non-platform");
		final Vehicle vehicle = n.parkedConsist();
		// 作业单照装（交接的第三道闸要它），但这一步的**目标由下面的任务直接指定**成股道。
		n.sim.upsertMmtrJob(platformJob("D-008", n.siding.getId(), n.platform.getId()));
		final MmtrDutyRegistry registry = n.sim.mmtrDuties;
		final UUID player = UUID.randomUUID();

		n.tick();
		assertNotNull(n.sim.mmtrJobScheduler.jobIdOfVehicle(vehicle.getId()), "作业单要认领这列车");

		// 目的地 = 车场那股道（不是站台），而且任务类型是 MANEUVER：到站不会被当成完成。
		// 引擎此刻已经给这列车挂了"开往站台"那一步（非终态），先让它下车 ——
		// setMmtrMission 明写"一条任务活着时不许覆盖"（调用方先取消），这里就照它说的做。
		assertTrue(vehicle.setMmtrMission(null), "先把引擎挂的那一步撤掉");
		final MmtrMission mission = new MmtrMission(vehicle.getId(), MmtrMission.Kind.MANEUVER, n.siding.getId(), n.siding.getId(), n.millis);
		assertTrue(vehicle.setMmtrMission(mission), "把手搭的任务挂上车");
		for (int i = 0; i < 4000 && !vehicle.mmtrDutyStoppedAtTarget(); i++) {
			n.tick();
		}
		assertTrue(vehicle.mmtrDutyStoppedAtTarget(), "车真的停在那股道上了（到站这条事实成立）");
		assertFalse(mission.isTerminal(), "这一步还没到终态 —— 正是要测的那个窗口（目标活着、车已停稳）");

		final String refusal = registry.refusalForPlatformMeet(player, vehicle.getId());
		assertNotNull(refusal, "下一站不是站台 ⇒ 站台接站必须被拒");
		assertTrue(refusal.contains("不是站台"), "拒绝理由要点名原因：" + refusal);
		assertNotNull(registry.claimPlatform(player, "阿己", vehicle.getId()), "claimPlatform 用同一条判据拒");
		assertNull(registry.of(player), "被拒之后不留值守记录");
	}

	// ------------------------------------------------------------------ ⑧ PDA 车次列表的取数口

	/**
	 * ⑧a {@link MmtrDutyRegistry#allVehicleRows()} 的**枚举范围 = 全部股道**（PDA 列表页的名单）。
	 *
	 * <h3>它钉的是哪条 bug</h3>
	 * <p>用户原话："这边 PDA 没有显示全部车次，而是只有附近的车次"。根因是面板原来遍历
	 * {@code MinecraftClientData.vehicles} —— 那份镜像是**按玩家位置同步**的，站在几百格外时车根本不在里面。
	 * 修法是把名单搬到引擎，而这个用例钉的就是这一份的枚举范围：</p>
	 * <ul>
	 *   <li>**第一条股道**（车场）上停着一列**没有车次**的车 ⇒ 它不该进名单；</li>
	 *   <li>**第二条股道**上的那列车挂着车次 {@code D-010} ⇒ 名单里有且只有它。</li>
	 * </ul>
	 * <p>枚举只看第一条股道的话，"恰好一行"那条断言会红（名单会是空的）。</p>
	 *
	 * <h3>车次为什么用手搭的任务而不是作业单</h3>
	 * <p>车次号（{@code mmtrJobId}）是**车上的镜像字段**，由车辆自己的 tick 从"这趟车挂着的那一步"写成
	 * （{@code Vehicle.updateMmtrSyncFields} → {@code mission.getJobId()}）。作业单那条路要求车场/时刻表那一整套，
	 * 而本用例要测的是**枚举范围**，不是调度 —— 所以照 ⑥补/⑦补 的做法直接挂一步、把车次号写上去
	 * （{@code attachJobStep} 与 {@code MmtrJobScheduler} 里那一对调用同序）。</p>
	 */
	@Test
	public void thePdaVehicleRowListEnumeratesEverySiding() {
		final Net n = new Net("build/mmtr-duty-rows-range");
		/*
		 * 夹具里 farSiding 是**没有归属**的（它的注释写着"那股道上永远没有车"）——
		 * 而引擎对"没有 area 的股道"每拍**清空车表**（{@code Siding.simulateVehicles} 开头：{@code area == null} ⇒
		 * {@code vehicleIdMap.clear()}）。所以要先给它一个车场（照夹具给 yard 那条股道配车场、再 {@code sync()} 的写法），
		 * 那条股道才留得住车 —— 否则下面第二条断言会以"车不见了"的形式红，而那不是本用例要测的东西。
		 */
		final Depot farDepot = new Depot(TransportMode.TRAIN, n.sim);
		farDepot.setName("Far Yard");
		farDepot.setCorners(new Position(139, -5, -5), new Position(205, 5, 5));
		n.sim.depots.add(farDepot);
		n.sim.sync();
		n.farSiding.tick();
		assertNotNull(n.farSiding.area, "（现场）第二条股道要归到一个车场里，否则它每拍清空车表（车根本留不住）");

		// 第一条股道（车场）上停一列没有车次的车：车场里的裸车没有车次可显示。
		final Vehicle bare = parkConsistOn(n, n.siding);
		// 第二条股道上的车才是"只有看全景才答得出来"的那一趟。
		final Vehicle far = parkConsistOn(n, n.farSiding);
		assertEquals(n.siding.getId(), bare.vehicleExtraData.getSidingId(), "（现场）那列裸车在第一条股道上");
		assertEquals(n.farSiding.getId(), far.vehicleExtraData.getSidingId(), "（现场）挂着车次的那列车在**第二条**股道上");

		final MmtrMission farMission = new MmtrMission(far.getId(), MmtrMission.Kind.MANEUVER,
			n.farSiding.getId(), n.platform.getId(), n.millis);
		farMission.attachJobStep("D-010", 0, 1, "去程到 1 站 1 台");
		assertTrue(far.setMmtrMission(farMission), "给第二条股道那列车挂一步（车次号从它来）");
		final MmtrDutyRegistry registry = n.sim.mmtrDuties;

		// 车次号是镜像字段：它要在下一拍车辆自己 update 时才写上去。
		for (int i = 0; i < 60 && far.getMmtrJobIdFromSync().isEmpty(); i++) {
			n.tick();
		}
		assertEquals("D-010", far.getMmtrJobIdFromSync(), "车上的车次号镜像（名单读的就是它）");
		assertEquals("", bare.getMmtrJobIdFromSync(), "第一条股道那列车没有车次号");

		final java.util.List<MmtrDutyRegistry.VehicleRow> rows = registry.allVehicleRows();
		assertEquals(1, rows.size(), "场上只有一趟车次 ⇒ 名单一行（第一条股道那列裸车不算）");
		final MmtrDutyRegistry.VehicleRow row = rows.get(0);
		assertEquals("D-010", row.jobId(), "车次号来自引擎写进车上的镜像字段");
		assertEquals(far.getId(), row.vehicleId(), "⑧a 名单收的是**第二条股道**上那趟车（枚举范围 = 全部股道）");
		assertEquals("", row.dutyState(), "无人值守 = 空串（不伪造一个叫 IDLE 的人）");
		assertEquals("", row.dutyCrew(), "无人值守 ⇒ 没有值守人");
		assertEquals(0, row.dutyWaiting(), "没人认领");
		assertTrue(row.distanceToStopM() >= -1,
			"到停车点的距离要么是 >= 0 的真数值、要么是 -1（本趟没有停车目标），不许是别的：" + row.distanceToStopM());
	}

	/**
	 * ⑧b 名单里的**值守占用**读的是引擎的 {@code operatorOf}，不是车上那个镜像字段。
	 *
	 * <p>车上那个 {@code mmtrDutyState} 在"有人正在站台上等它"（{@code WAITING}）时**按设计是空串**
	 * （见 {@code pushDutySync}：只有人在车上的那几个态才算"有人值守"，否则别人会以为有人在开而不敢上车）。
	 * 而面板的按钮可用性要按**真实占用**判（§2.2 那张表把"等待接站"算成谁都不能上）。
	 * 所以名单必须读 {@code operatorOf} —— 这个用例把两个值**同时**断出来，让这个差别是看得见的、不是猜的。</p>
	 *
	 * <p>现场照 ⑥补：车场那列编队车挂一步"开往站台"的任务（{@code isTerminal()} 为假 ⇒ 站台接站可认领），
	 * 车次号由 {@code attachJobStep} 写上去。</p>
	 */
	@Test
	public void thePdaVehicleRowListReadsTheEngineDutyStateNotTheVehicleMirror() {
		final Net n = new Net("build/mmtr-duty-rows-duty");
		final Vehicle vehicle = n.parkedConsist();
		final MmtrMission mission = new MmtrMission(vehicle.getId(), MmtrMission.Kind.MANEUVER,
			n.siding.getId(), n.platform.getId(), n.millis);
		mission.attachJobStep("D-011", 0, 1, "去程到 1 站 1 台");
		assertTrue(vehicle.setMmtrMission(mission), "挂上一条开往站台的任务（它就是车次号的来源）");
		final MmtrDutyRegistry registry = n.sim.mmtrDuties;
		final UUID player = UUID.randomUUID();

		for (int i = 0; i < 20 && vehicle.getMmtrJobIdFromSync().isEmpty(); i++) {
			n.tick();
		}
		assertEquals("D-011", vehicle.getMmtrJobIdFromSync(), "车上的车次号镜像写上了");

		final java.util.List<MmtrDutyRegistry.VehicleRow> before = registry.allVehicleRows();
		assertEquals(1, before.size(), "① 场上正好一趟车次 ⇒ 名单一行");
		assertEquals(vehicle.getId(), before.get(0).vehicleId(), "收的就是这列车");
		assertEquals("D-011", before.get(0).jobId(), "车次号来自车上的镜像字段");
		assertEquals("", before.get(0).dutyState(), "还没人认领 ⇒ 空串（不是 IDLE）");

		assertNull(registry.claimPlatform(player, "阿辛", vehicle.getId()), "站台接站应当被接受");
		final MmtrDutyRegistry.VehicleRow claimed = registry.allVehicleRows().get(0);
		assertEquals("WAITING", claimed.dutyState(), "⑧b 名单里的值守状态来自引擎 operatorOf（等待接站）");
		assertEquals(player.toString(), claimed.dutyCrew(), "⑧b 值守人就是他（面板靠这一格认「我值守的那趟车」）");
		assertEquals(1, claimed.dutyWaiting(), "1 个人认领了这趟车");
		assertEquals("", vehicle.getMmtrDutyStateFromSync(),
			"（对照）车上那个镜像在 WAITING 时按设计是空串 —— 所以名单不能读它，否则面板会把「有人在等它」显示成「无人」");
	}

	/**
	 * 把一列编组体车放进**指定的**股道（{@code parkedConsist} 只认 {@code Net.siding}）。
	 *
	 * <p>⑧ 那个用例需要"车次挂在第二条股道上"这个现场，所以把夹具那几行参数化。</p>
	 */
	private static Vehicle parkConsistOn(Net n, Siding siding) {
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("emu", 2, 1, 10, 0, 1, 0.1, 0.1, true, "emu"));
		siding.setVehicleCars(cars);
		siding.clearParkedVehicles();
		final MmtrConsistWalker walker = siding.mmtrConsistWalkerFromYard(null, n.store, null);
		assertNotNull(walker, "编组体车要能放进股道 " + siding.getId());
		final Vehicle vehicle = siding.spawnMmtrConsistVehicle(walker, MmtrCabState.Cab.CAB_A);
		assertNotNull(vehicle, "编组体车的走行缝要能生成");
		return vehicle;
	}

	// ------------------------------------------------------------------ ⑨ 派车：按车次名 + 驾驶室编号

	/**
	 * 推到"这趟车的车次号镜像已经写上"（{@code getMmtrJobIdFromSync()}）那一刻。
	 *
	 * <p>为什么要轮询而不是"tick N 次"：车次号不是作业单认领那一拍就有的 —— 它是车辆自己从
	 * **挂在车上的那一步任务**里算出来、写进镜像字段的（{@code Vehicle.updateMmtrSyncFields}）。
	 * 派车读的就是这个镜像（{@code allVehicleRows}），所以用例必须等到它真的写上；
	 * 拍数取决于自臂与走向，不是本用例要测的东西（照 ⑧a/⑧b 的写法）。</p>
	 */
	private static void tickUntilJobIdMirrored(Net n, Vehicle vehicle, String expectedJobId) {
		for (int i = 0; i < 120 && !expectedJobId.equals(vehicle.getMmtrJobIdFromSync()); i++) {
			n.tick();
		}
		assertEquals(expectedJobId, vehicle.getMmtrJobIdFromSync(),
			"现场条件：车上的车次号镜像（派车与面板读的就是它）");
	}

	/**
	 * ⑨a {@code assign} 按**车次名（作业单名）**把车定下来，并且把**给定的驾驶室**记进
	 * {@link MmtrDutyRegistry.Duty#cabSpec()}（notes/409 §0 的 ①，用户口径：
	 * "输入玩家，车次（作业单名），驾驶室编号为玩家分配接下来驾驶的车辆"）。
	 *
	 * <p>钉子有三处，都是"派车只认车辆 id"这个缺口的具体形状：</p>
	 * <ul>
	 *   <li>调用方给的是 {@code "D-020"}（车次名），**没有任何车辆 id** —— 解析走
	 *       {@link MmtrDutyRegistry#findVehicleIdByJobId(String)}（= {@code allVehicleRows()} 那份名单）；</li>
	 *   <li>{@code Duty.cabSpec()} 必须是**给的那个**，而且 {@code cabSpecExplicit()} 为真 ——
	 *       否则 {@code refreshPassiveFields} 会拿引擎的偏好值把它刷掉（"记住了没用"）；</li>
	 *   <li>{@code --wait} 与默认两条路各落到 WAITING / ABOARD。</li>
	 * </ul>
	 */
	@Test
	public void assignResolvesTheJobIdAndKeepsTheGivenCabSpec() {
		final Net n = new Net("build/mmtr-duty-assign-by-job");
		final Vehicle vehicle = n.parkedConsist();
		n.sim.upsertMmtrJob(platformJob("D-020", n.siding.getId(), n.platform.getId()));
		final MmtrDutyRegistry registry = n.sim.mmtrDuties;
		final UUID player = UUID.randomUUID();

		tickUntilJobIdMirrored(n, vehicle, "D-020");
		assertEquals(vehicle.getId(), registry.findVehicleIdByJobId("D-020"),
			"⑨a 车次名 → 车辆：解析走的是 allVehicleRows() 那份「场上有哪些车次」");
		assertEquals(0, registry.findVehicleIdByJobId("D-999"), "场上没有的车次答 0（不是乱指一辆）");

		// 默认那条路 = 直接传送：ABOARD + 一条 direct=true 的待办（游戏端据此把他送上车）。
		assertNull(registry.assign(player, "阿壬", "D-020", "1A", false), "⑨a 按车次名派车应当被接受");
		final MmtrDutyRegistry.Duty duty = registry.of(player);
		assertNotNull(duty, "派车之后必须有值守记录");
		assertEquals(MmtrDutyRegistry.State.ABOARD, duty.state(), "⑨a 默认（不 --wait）= 直接上车");
		assertEquals(vehicle.getId(), duty.vehicleId(), "⑨a 绑的车是**车次名解析出来的那辆**（不是调用方给的 id）");
		assertEquals("D-020", duty.jobId(), "⑨a 车次号就是作业单名");
		assertEquals("1A", duty.cabSpec(), "⑨a 驾驶室就是给的那个（修前这一格只会是引擎自己挑的值）");
		assertTrue(duty.cabSpecExplicit(), "⑨a 而且它是「显式给的」—— 它不会被引擎的偏好值刷掉");
		assertEquals(1, registry.pendingBoards().size(), "派一条待办");
		assertTrue(registry.pendingBoards().get(0).direct(), "直接上车那条待办是 direct=true（游戏端据此传送）");
		assertEquals(1, registry.allVehicleRows().size(), "⑨a 场上正好一趟车次 ⇒ 名单一行（派车与面板读的是同一份）");

		/*
		 * 站台接站那条路：WAITING，且驾驶室照记（他上车之前就已经写清"该进哪一间"）。
		 *
		 * <p>为什么必须先把上一条放掉：**有人在车上时这趟车对别人只允许站台接站、连站台接站都拒**
		 * （notes/408 §2.2 那张表：有玩家·运转中的车谁都不能上，因为到站位置不会空出来）。
		 * 所以"两条路"是同一趟车的**两次派车**（换人），而不是两个人同时占着它 ——
		 * 这里走的是真实的那条路：取消 → 换个人再派，驾驶室换成新给的那个。</p>
		 */
		registry.cancel(player, "用例：换个人派同一趟车");
		final UUID waiter = UUID.randomUUID();
		assertNull(registry.assign(waiter, "阿癸", "D-020", "1B", true), "⑨a --wait 那条路应当被接受");
		assertEquals(MmtrDutyRegistry.State.WAITING, registry.of(waiter).state(), "⑨a --wait = 站台接站（等待接站）");
		assertEquals("1B", registry.of(waiter).cabSpec(), "⑨a 站台接站也把指定的驾驶室记下来（换人即换驾驶室）");
		assertTrue(registry.of(waiter).cabSpecExplicit(), "⑨a 站台接站这一间同样是显式给的");
		assertEquals(vehicle.getId(), registry.of(waiter).vehicleId(), "⑨a 两条路解析到的是同一辆车（同一个车次名）");
		assertFalse(registry.pendingBoards().get(registry.pendingBoards().size() - 1).direct(), "站台接站不传送任何人");
	}

	/**
	 * ⑨b **车次名不存在 ⇒ 拒绝，且记录不变**（notes/409 的硬要求之一：找不到就说清"场上没有正在跑的车次"）。
	 *
	 * <p>第二条断言（记录不变）比第一条重要：如果拒绝之前已经写过记录，现场就是"报错了，人却被派到
	 * 一辆随手挑的车上"——那比不派更坏。</p>
	 */
	@Test
	public void assignRefusesAnUnknownJobIdAndLeavesTheRecordAlone() {
		final Net n = new Net("build/mmtr-duty-assign-unknown-job");
		final Vehicle vehicle = n.parkedConsist();
		n.sim.upsertMmtrJob(platformJob("D-021", n.siding.getId(), n.platform.getId()));
		final MmtrDutyRegistry registry = n.sim.mmtrDuties;
		final UUID player = UUID.randomUUID();

		tickUntilJobIdMirrored(n, vehicle, "D-021");
		assertNotNull(n.sim.mmtrJobScheduler.jobIdOfVehicle(vehicle.getId()), "现场条件：作业单认领了这列车");

		// ① 从来没有过的车次：拒绝，并且点名说"场上没有"。
		final String refusal = registry.assign(player, "阿子", "D-999", "1A", false);
		assertNotNull(refusal, "⑨b 不存在的车次必须被拒");
		assertTrue(refusal.contains("场上没有正在跑的车次") && refusal.contains("D-999"),
			"⑨b 拒绝理由要点名车次并说清是「场上没有」：" + refusal);
		assertNull(registry.of(player), "⑨b 被拒的派车不许留下任何值守记录");

		// ② 已经有一条值守时再派一个不存在的车次：记录必须**原封不动**（不许被半途改坏）。
		assertNull(registry.assign(player, "阿子", "D-021", "1A", false), "先派一条真的");
		final MmtrDutyRegistry.Duty before = registry.of(player);
		assertNotNull(registry.assign(player, "阿子", "D-999", "1A", false), "换一个不存在的车次 ⇒ 拒");
		final MmtrDutyRegistry.Duty after = registry.of(player);
		assertEquals(before.vehicleId(), after.vehicleId(), "⑨b 被拒之后绑的车没变");
		assertEquals(before.cabSpec(), after.cabSpec(), "⑨b 被拒之后驾驶室没变");
		assertEquals(1, registry.pendingBoards().size(), "⑨b 被拒的派车不排待办（还是原来那一条）");
	}

	/**
	 * ⑨c **驾驶室编号越界 / 写法不对 ⇒ 拒绝**（校验方法照 {@code cab <id> <车节><A|B>} 那一支的写法，
	 * 范围查 {@code MmtrConsistBody.carCount()}）。
	 *
	 * <p>夹具那列车只有**一节**，所以 {@code 2A} 合法不了 —— 这正是"越界"最干净的现场；
	 * 另一个方向（不是编组体车）在单测里搭不出可判的现场（车场里的车都是
	 * {@code spawnMmtrConsistVehicle} 出来的编组体车），如实记在这里，不写一条不具区分度的断言充数。</p>
	 */
	@Test
	public void assignRefusesAnOutOfRangeOrMalformedCab() {
		final Net n = new Net("build/mmtr-duty-assign-bad-cab");
		final Vehicle vehicle = n.parkedConsist();
		n.sim.upsertMmtrJob(platformJob("D-022", n.siding.getId(), n.platform.getId()));
		final MmtrDutyRegistry registry = n.sim.mmtrDuties;
		final UUID player = UUID.randomUUID();

		tickUntilJobIdMirrored(n, vehicle, "D-022");
		assertEquals("1A", vehicle.mmtrPreferredCabSpec(), "现场条件：这列单节编组车只有 1A / 1B 两间驾驶室");

		// 车节序号越界（2 起就是越界）。
		final String outOfRange = registry.assign(player, "阿丑", "D-022", "2A", false);
		assertNotNull(outOfRange, "⑨c 越界的车节序号必须被拒");
		assertTrue(outOfRange.contains("越界") && outOfRange.contains("2A"),
			"⑨c 拒绝理由要说清是车节序号越界、并且点名写的那个：" + outOfRange);
		assertNull(registry.of(player), "⑨c 被拒的派车不留记录");

		// 写法不对（缺 A|B / 序号不是数字）。空串是**合法**的"不指定"，不在这一类里。
		assertNotNull(registry.assign(player, "阿丑", "D-022", "A", false), "⑨c 缺车节序号的写法要拒（引擎无法判断是哪一节的 A 端）");
		assertNotNull(registry.assign(player, "阿丑", "D-022", "1C", false), "⑨c 端别不是 A/B 要拒");
		assertNotNull(registry.assign(player, "阿丑", "D-022", "0A", false), "⑨c 车节序号 0 要拒（写法是 1 起）");
		assertNull(registry.of(player), "⑨c 三次被拒都不许留下记录");

		// 空串 = "仍由引擎挑"：这是**旧签名的行为**，必须还能走（老调用点一个字都不用改）。
		assertNull(registry.assign(player, "阿丑", "D-022", "", false), "⑨c 空串 = 不指定驾驶室 ⇒ 与 claim 的旧行为一致");
		assertEquals("1A", registry.of(player).cabSpec(), "⑨c 不指定时记录上仍是引擎挑的那一间");
		assertFalse(registry.of(player).cabSpecExplicit(), "⑨c 但它**不是**显式的 —— 于是会被引擎随方向刷新（旧行为）");
	}

	// ------------------------------------------------------------------ 工具
	/**
	 * 把这列编组体车直接**停在站台轨**上（A 端在站台轨起点）。
	 *
	 * <p>为什么需要这样一个现场：交接与"到站停稳"两条判据都要求车**已经在目标上**，
	 * 而"开过去"那一段是物理与规划的事（{@code MmtrStationSubTaskTests} 已经单独测过它）。
	 * 这些用例要测的是**值守**，所以把现场直接摆成"车已在站台"。</p>
	 */
	private static Vehicle parkConsistAtPlatform(Net n) {
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("emu", 2, 1, 10, 0, 1, 0.1, 0.1, true, "emu"));
		n.siding.setVehicleCars(cars);
		n.siding.clearParkedVehicles();
		final double[] carLengthsM = {cars.get(0).getTotalLength(true, true)};
		final boolean[] couplerAfter = {cars.get(0).getMmtrCouplerAfter()};
		final MmtrConsistWalker walker = MmtrConsistWalker.place(n.sim, n.store, n.rP, n.node60, 0.5, carLengthsM, null,
			org.mtr.core.mmtr.consist.MmtrConsistBody.seamArcMsFrom(0.5, carLengthsM, couplerAfter),
			org.mtr.core.mmtr.consist.MmtrConsistBody.seamCarIndexesFrom(carLengthsM, couplerAfter));
		assertNotNull(walker, "编组体车要能放到站台轨上");
		final Vehicle vehicle = n.siding.spawnMmtrConsistVehicle(walker, MmtrCabState.Cab.CAB_A);
		assertNotNull(vehicle, "编组体车的走行缝要能生成");
		return vehicle;
	}
}
