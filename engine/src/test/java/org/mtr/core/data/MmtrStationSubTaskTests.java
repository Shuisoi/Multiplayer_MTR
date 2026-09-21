package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.mmtr.MmtrSubTask;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.mmtr.task.DriveToPlatformTask;
import org.mtr.core.mmtr.task.StationServiceTask;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **站台作业的子任务**（用户口径 2026-09-21）：
 *
 * <blockquote>「这个东西需要做子任务，比如说到某站台。首先不能干预玩家停车的行为，所以判定可以宽松点，
 * 在站台上停车就算完成停车目标，然后按键开门，等段时间，关门。这几个子任务完成后才算任务完成。」</blockquote>
 *
 * <p>三条裁定各有一处判据，缺一条这一轮就没做完：</p>
 * <ol>
 *   <li>**到站宽松**：站台轨上有车 + 停稳即算到站 —— 不看停车点精确度；</li>
 *   <li>**停留时长**：默认 20 秒，可改（{@code Simulator.mmtrSetSubTaskDwellSeconds}，指令 {@code job dwell}）；</li>
 *   <li>**手动与自动都保留**：司机在开 ⇒ 门是他的活（引擎只等）；自动/司机不在操纵台 ⇒ 引擎动手。</li>
 * </ol>
 *
 * <p>红证（每一条都能单独把用例打红）：① 把 {@code mmtrArrivalIsLoose} 改成恒 false ⇒
 * {@link #aPlayerMissionArrivesOnTheLooseCriterionAndTheDriverWorksTheDoors} 第一段红；
 * ② 把 {@code ensureStationSubTasks} 的 {@code effectiveDwellMillis} 改成直接用引擎默认 ⇒
 * {@link #stationServiceRunsTheWholeSubTaskChainAndRecordsArrivalAndDeparture} 的 30 秒那一段红；
 * ③ 把 {@code mmtrDriverIsOperating} 改成恒 false ⇒ 第二条用例"过了兜底窗口门还关着"那一句红
 * （引擎会替在操纵台上的司机开门）。</p>
 */
public final class MmtrStationSubTaskTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
		+ "}";

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/** 车场 → 咽喉 → 岔口 → 站台轨（站台/车站挂在它上面），与 {@code MmtrMotionMissionTests} 同一张网。 */
	private static final class Net {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-sub-task"), false);
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
			assertTrue(station.savedRails.contains(platform), "站台要挂在车站上（宽松判据要按车站取全部站台轨）");
			siding.tick();
		}

		Vehicle spawn() {
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, null, null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			return vehicle;
		}

		void tick() {
			// `sim.step` 才是**推进时钟**的那一个（`siding.simulateVehicles` 只跑一拍、不动 currentMillis）——
			// 子任务链里有"停够 N 秒"，时钟不动就永远是 0 秒（第一版就是这么红的）。
			sim.step(1000);
		}
	}

	/** 先让自动车把车开到站台上（站台作业的"原地"前提），返回同一台车。 */
	private static Vehicle parkAtThePlatform(Net n) {
		final Vehicle v = n.spawn();
		final MmtrMission drive = new MmtrMission(v.getId(), MmtrMission.Kind.PASSENGER, n.siding.getId(), n.platform.getId(), 0L);
		drive.setExecutor(MmtrMission.Executor.AUTOPILOT, null);
		drive.attachTask(new DriveToPlatformTask("drive", n.platform.getId(), 0L));
		assertTrue(v.setMmtrMission(drive), "开往站台的任务挂上");
		int guard = 0;
		while (guard++ < 8000 && drive.getState() != MmtrMission.State.AT_TARGET) {
			n.tick();
		}
		assertEquals(MmtrMission.State.AT_TARGET, drive.getState(), "开到站台");
		n.tick();
		assertTrue(drive.isTerminal(), "到站即完成（停留是下一步的事）");
		assertEquals(n.rP.getHexId(), v.getMmtrMotionWalker().railHex(), "车确实停在站台轨上");
		assertEquals(0, v.getSpeed(), 1e-9, "停稳");
		return v;
	}

	private static MmtrSubTask subTask(MmtrMission mission, MmtrSubTask.Kind kind) {
		for (final MmtrSubTask subTask : mission.subTasks()) {
			if (subTask.kind() == kind) {
				return subTask;
			}
		}
		throw new AssertionError("链上没有 " + kind + " 子任务：" + mission.encodeSubTasks());
	}

	/**
	 * ① **自动执行：四个子任务依次达成，进站/出站时刻都记下来，最后才完成**。
	 *
	 * <p>{@code 到站停稳 → 开门 → 停够 → 关门}，计划给的 30 秒停留要**压过**引擎默认（20 秒），
	 * 而且完成的那一刻必须**门已经关好** —— 这就是"这几个子任务完成后才算任务完成"。</p>
	 */
	@Test
	public void stationServiceRunsTheWholeSubTaskChainAndRecordsArrivalAndDeparture() {
		final Net n = new Net();
		final Vehicle v = parkAtThePlatform(n);

		final MmtrMission service = new MmtrMission(v.getId(), MmtrMission.Kind.PASSENGER, n.siding.getId(), n.platform.getId(), 0L);
		service.setExecutor(MmtrMission.Executor.AUTOPILOT, null);
		service.attachTask(new StationServiceTask("service", n.platform.getId(), 0L, 30_000L));
		assertTrue(v.setMmtrMission(service), "站台作业挂上");

		n.tick();
		assertTrue(service.hasSubTasks(), "站台作业要建出子任务链（修前只有一条 5 秒计时）");
		assertEquals(4, service.subTasks().size(), "到站停稳 / 开门 / 停够 / 关门");
		assertEquals(30_000L, service.subTaskDwellMillis(), "计划说停 30 秒 ⇒ 压过引擎默认的 20 秒");
		assertEquals(MmtrMission.State.AT_TARGET, service.getState(), "已经在站台上 ⇒ 直接进入站台作业");
		assertTrue(v.vehicleExtraData.mmtrDoorsOpen(), "自动执行：引擎立刻开门（自动那一半保留）");
		assertTrue(subTask(service, MmtrSubTask.Kind.STOP_AT_TARGET).isDone(), "① 到站停稳（宽松判据）");
		assertTrue(subTask(service, MmtrSubTask.Kind.OPEN_DOORS).isDone(), "② 开门");
		assertEquals(MmtrSubTask.State.ACTIVE, subTask(service, MmtrSubTask.Kind.WAIT_PASSENGERS).state(), "③ 停留开始计时");
		assertFalse(subTask(service, MmtrSubTask.Kind.CLOSE_DOORS).isDone(), "④ 还没关门");
		assertTrue(service.getStationArrivalMillis() > 0, "**实际进站时刻**被记下");
		assertEquals(-1, service.getStationDepartureMillis(), "还没出站");

		// 停够之前不许完成，也不许关门 —— 门开着就是"还在停站"的可见证据。
		n.tick();
		v.getMmtrMotionWalker();
		for (int i = 0; i < 20; i++) {
			n.tick();
		}
		assertEquals(MmtrMission.State.AT_TARGET, service.getState(), "20 秒到了但计划是 30 秒 ⇒ 还不算停够");
		assertTrue(v.vehicleExtraData.mmtrDoorsOpen(), "停站期间门保持开着");

		for (int i = 0; i < 12; i++) {
			n.tick();
		}
		assertEquals(MmtrMission.State.COMPLETE, service.getState(), "停够 + 关门之后这一步才算完成");
		assertTrue(service.allSubTasksDone(), "四个子任务全部达成：" + service.encodeSubTasks());
		assertFalse(v.vehicleExtraData.mmtrDoorsOpen(), "离开时门是关的（关门是链上最后一条）");
		final long arrival = service.getStationArrivalMillis();
		final long departure = service.getStationDepartureMillis();
		assertTrue(departure >= arrival && arrival > 0, "**实际进站→出站**成对记下：" + arrival + " → " + departure);
	}

	/**
	 * ② **玩家执行：到站宽松 + 引擎不碰门**。
	 *
	 * <p>司机坐在操纵台上时，门是**他的活**（用户口径"按键开门"）：引擎开门就是抢活，
	 * 所以"等了几拍门还关着"本身就是判据。司机开门之后链才往下走；停留按引擎默认 20 秒；
	 * 全程**没有停车点**（引擎不替司机刹车，也不给"已到停车点"这个凭空的状态）。</p>
	 */
	@Test
	public void aPlayerMissionArrivesOnTheLooseCriterionAndTheDriverWorksTheDoors() {
		final Net n = new Net();
		final Vehicle v = parkAtThePlatform(n);
		final UUID driver = UUID.randomUUID();
		/*
		 * "司机坐在司机位上"这件事在引擎里是**两条**：操纵台的 override + 司机位上真的有这个骑乘者。
		 * 少了后者，`simulate` 会在下一拍把 override 自动交还（占用锁的既有规矩），
		 * 于是这条用例就成了"司机早走了"——测不到"门归司机"那一半。
		 */
		final ObjectArrayList<VehicleRidingEntity> riders = new ObjectArrayList<>();
		riders.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(riders);
		// 司机把手放在操纵台上（mmtrManualOverride）—— 这正是"门归司机"的那条判据。
		v.applyMmtrControl(new ControlState(), driver);
		assertTrue(v.isMmtrManualOverride(), "司机在操纵台上");

		final MmtrMission service = new MmtrMission(v.getId(), MmtrMission.Kind.PASSENGER, n.siding.getId(), n.platform.getId(), 0L);
		assertTrue(service.setExecutor(MmtrMission.Executor.PLAYER, driver), "玩家执行的站台作业");
		service.attachTask(new StationServiceTask("service", n.platform.getId(), 0L, 0L));
		assertTrue(v.setMmtrMission(service), "站台作业挂上");

		n.tick();
		assertEquals(MmtrMission.State.AT_TARGET, service.getState(), "车在站台上 ⇒ 到站（宽松判据）");
		assertFalse(v.hasMmtrMotionStopAnchor(), "**引擎不替司机设停车点**（不干预停车）");
		assertFalse(v.isMmtrMotionStoppedAtTarget(), "也不会凭空报'已到停车点'");
		assertEquals(20_000L, service.subTaskDwellMillis(), "引擎默认停留 = 用户定的 20 秒");

		for (int i = 0; i < 5; i++) {
			n.tick();
		}
		assertFalse(v.vehicleExtraData.mmtrDoorsOpen(), "**等司机开门**：引擎不抢这活（修前这里门已经开了）");
		assertEquals(MmtrSubTask.State.ACTIVE, subTask(service, MmtrSubTask.Kind.OPEN_DOORS).state(), "开门这条在等司机");

		// 连"司机不在操纵台"的 15 秒兜底窗口都过掉：**司机在操纵台上，引擎就永远不碰门**。
		// （这一条是"手动与自动都保留"里"手动"那一半的判据：红证 = 把 mmtrDriverIsOperating 改成恒 false。）
		for (int i = 0; i < 16; i++) {
			n.tick();
		}
		assertFalse(v.vehicleExtraData.mmtrDoorsOpen(), "司机在操纵台上 ⇒ 引擎不替他开门（兜底只对「司机不在」生效）");
		assertEquals(MmtrSubTask.State.ACTIVE, subTask(service, MmtrSubTask.Kind.OPEN_DOORS).state(), "还在等司机");

		// 司机按键开门（引擎侧看到的就是它）：链立刻往下走到停留。
		v.vehicleExtraData.openDoors();
		n.tick();
		assertTrue(subTask(service, MmtrSubTask.Kind.OPEN_DOORS).isDone(), "司机开门 ⇒ 开门子任务达成");
		assertEquals(MmtrSubTask.State.ACTIVE, subTask(service, MmtrSubTask.Kind.WAIT_PASSENGERS).state(), "停留开始计时");

		// 停留期间司机提前关门：不拦、不改判据（宽松），但停留仍然要按时间走完。
		for (int i = 0; i < 10; i++) {
			n.tick();
		}
		v.vehicleExtraData.closeDoors();
		for (int i = 0; i < 5; i++) {
			n.tick();
		}
		assertEquals(MmtrMission.State.AT_TARGET, service.getState(), "提前关门不缩短停留（宽松口径下的偏差，不是失败）");

		for (int i = 0; i < 10; i++) {
			n.tick();
		}
		assertEquals(MmtrMission.State.COMPLETE, service.getState(), "停留走完 + 门已关 ⇒ 这一步完成");
		assertTrue(service.allSubTasksDone(), "链全达成：" + service.encodeSubTasks());
		assertTrue(service.subTaskAcks() == 0, "没人在客户端确认过（引擎判定不为它编造确认）");
	}

	/**
	 * ④ **玩家执行的任何一步都用宽松到站判据**（实机回归，2026-09-21）。
	 *
	 * <p>现场症状：司机把车停在 2 站 1 台上，作业却卡在「去程到 2 站 1 台」的 DISPATCHED 上不动，
	 * **下一步"开关门"的提示因此永远不来**。根因是判据第一版多要了一个"有子任务链"：
	 * 只有站台作业（SERVE）那一步是宽松的，而"开往某站台"那一步（MOVE_TO / {@code DriveToPlatformTask}）
	 * 没有子任务链 ⇒ 掉回精确判据 {@code isMmtrMotionStoppedAtTarget()} ⇒
	 * 而玩家任务**没有停车点**，那个标志永远不会置位。</p>
	 *
	 * <p>红证：把 {@code mmtrArrivalIsLoose} 改回
	 * {@code executor == PLAYER && !mission.subTasks().isEmpty()} ⇒ 第一条断言红。</p>
	 */
	@Test
	public void aPlayerMissionArrivesOnObservationEvenWithoutASubTaskChain() {
		final MmtrMission driveStep = new MmtrMission(1L, MmtrMission.Kind.PASSENGER, 1, 2, 0L);
		driveStep.setExecutor(MmtrMission.Executor.PLAYER, UUID.randomUUID());
		assertTrue(Vehicle.mmtrArrivalIsLoose(driveStep),
			"④ 玩家执行的**开往站台**那一步也必须宽松（修前它掉回精确判据、而玩家没有停车点 ⇒ 永远到不了站）");

		final MmtrMission serviceStep = new MmtrMission(1L, MmtrMission.Kind.PASSENGER, 1, 2, 0L);
		serviceStep.setExecutor(MmtrMission.Executor.PLAYER, UUID.randomUUID());
		serviceStep.attachTask(new StationServiceTask("s", 2L, 0L, 0L));
		assertTrue(serviceStep.ensureSubTasks(20_000L), "站台作业那一步有链");
		assertTrue(Vehicle.mmtrArrivalIsLoose(serviceStep), "有链的玩家步骤当然也宽松");

		final MmtrMission autopilot = new MmtrMission(1L, MmtrMission.Kind.PASSENGER, 1, 2, 0L);
		autopilot.setExecutor(MmtrMission.Executor.AUTOPILOT, null);
		assertFalse(Vehicle.mmtrArrivalIsLoose(autopilot),
			"自动车保持精确判据（它的停车点是引擎自己按包线刹的，宽松判据会在被信号按住时误判到站）");
		assertFalse(Vehicle.mmtrArrivalIsLoose(null), "没有任务 ⇒ 不宽松");
	}

	/**
	 * ⑤ **主任务 = 模板 + 目标（变量）**（用户口径：「主任务和子任务分离，并将任务目标分离…
	 * 主任务：停站乘降 —3站1台；子任务：1.停在3站1台 2.开门 3.等待上下客 4.关门…其中 3站1台则为变量」）。
	 *
	 * <p>这一条钉住三件事：①目标名**从引擎对象现算**（车站名+站+站台名+台、段名+股道+股道名）；
	 * ②两个模板各自展开成固定的基础操作序列；③**文案里带目标名** ——
	 * 司机看到的必须是"停在 North站1台"，而不是笼统的"到站停稳"。</p>
	 */
	@Test
	public void mainTasksExpandFromATemplatePlusATargetVariable() {
		final Net n = new Net();
		final org.mtr.core.mmtr.MmtrTaskTarget platformTarget =
			org.mtr.core.mmtr.MmtrTaskTarget.resolve(n.sim, n.platform.getId(), "", -1);
		assertEquals(org.mtr.core.mmtr.MmtrTaskTarget.Kind.PLATFORM, platformTarget.kind(), "站台 id 解析成站台目标");
		assertEquals("North站1台", platformTarget.label(), "站台名从**对象**现算（车站名+站+站台名+台）");

		final org.mtr.core.mmtr.MmtrTaskTarget sidingTarget =
			org.mtr.core.mmtr.MmtrTaskTarget.resolve(n.sim, n.siding.getId(), "", -1);
		assertEquals(org.mtr.core.mmtr.MmtrTaskTarget.Kind.SIDING, sidingTarget.kind(), "股道 id 解析成股道目标");
		assertEquals("Yard股道1", sidingTarget.label(), "股道名 = 车辆段名 + 股道 + 股道名");

		// ① 开往：只有一条基础操作 —— 停在目标
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<org.mtr.core.mmtr.MmtrSubTask> driveTo =
			org.mtr.core.mmtr.MmtrTaskTemplate.DRIVE_TO.expand(platformTarget, 20_000L);
		assertEquals(1, driveTo.size(), "开往只有一条基础操作");
		assertEquals(MmtrSubTask.Kind.STOP_AT_TARGET, driveTo.get(0).kind(), "开往的第一步就是停在目标");
		assertEquals("停在 North站1台", driveTo.get(0).text(), "文案带目标名（变量代入）");

		// ② 停站乘降：四条基础操作，顺序固定
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<org.mtr.core.mmtr.MmtrSubTask> serve =
			org.mtr.core.mmtr.MmtrTaskTemplate.STOP_AND_SERVE.expand(platformTarget, 20_000L);
		assertEquals(4, serve.size(), "停站乘降 = 停在目标/开门/等待上下客/关门");
		assertEquals(MmtrSubTask.Kind.STOP_AT_TARGET, serve.get(0).kind(), "1.停在目标");
		assertEquals(MmtrSubTask.Kind.OPEN_DOORS, serve.get(1).kind(), "2.开门");
		assertEquals(MmtrSubTask.Kind.WAIT_PASSENGERS, serve.get(2).kind(), "3.等待上下客");
		assertEquals(MmtrSubTask.Kind.CLOSE_DOORS, serve.get(3).kind(), "4.关门");
		assertEquals("停在 North站1台", serve.get(0).text(), "同样是带目标名的那一条");
		assertEquals("等待上下客 20s", serve.get(2).text(), "等待时长是模板参数（用户暂定 20 秒）");

		// ③ 作业单步骤类型 → 模板：这是"作者写的东西"与"引擎要做的基础操作"之间唯一的翻译点
		assertEquals(org.mtr.core.mmtr.MmtrTaskTemplate.DRIVE_TO,
			org.mtr.core.mmtr.MmtrTaskTemplate.ofStepType("MOVE_TO"), "MOVE_TO → 开往");
		assertEquals(org.mtr.core.mmtr.MmtrTaskTemplate.STOP_AND_SERVE,
			org.mtr.core.mmtr.MmtrTaskTemplate.ofStepType("SERVE"), "SERVE → 停站乘降");
		assertEquals(org.mtr.core.mmtr.MmtrTaskTemplate.NONE,
			org.mtr.core.mmtr.MmtrTaskTemplate.ofStepType("CHANGE_ENDS"), "还没拆的步骤类型回 NONE（不假装有链）");
	}

	/**
	 * ③ **停留时长可改**：默认 20 秒，接口能改，越界被夹住。
	 *
	 * <p>改的是**之后**建的链 —— 正在停的那一站照原时间停完，免得同一个司机眼皮底下时间变来变去。</p>
	 */
	@Test
	public void theDwellTimeIsConfigurableAndClamped() {
		final Net n = new Net();
		assertEquals(20_000L, n.sim.mmtrSubTaskDwellMillis(), "默认 20 秒（用户暂定值）");
		n.sim.mmtrSetSubTaskDwellSeconds(7);
		assertEquals(7_000L, n.sim.mmtrSubTaskDwellMillis(), "改成 7 秒生效");
		n.sim.mmtrSetSubTaskDwellSeconds(0);
		assertEquals(1_000L, n.sim.mmtrSubTaskDwellMillis(), "0 被夹到 1 秒（不设 0 停站）");
		n.sim.mmtrSetSubTaskDwellSeconds(99_999);
		assertEquals(3_600_000L, n.sim.mmtrSubTaskDwellMillis(), "上界 1 小时");
		n.sim.mmtrSetSubTaskDwellSeconds(25);

		final Vehicle v = parkAtThePlatform(n);
		final MmtrMission service = new MmtrMission(v.getId(), MmtrMission.Kind.PASSENGER, n.siding.getId(), n.platform.getId(), 0L);
		service.setExecutor(MmtrMission.Executor.AUTOPILOT, null);
		service.attachTask(new StationServiceTask("service", n.platform.getId(), 0L, 0L));
		assertTrue(v.setMmtrMission(service), "站台作业挂上");
		n.tick();
		assertEquals(25_000L, service.subTaskDwellMillis(), "新链用的是改过之后的时长");
		assertTrue(service.hasStationArrival(), "进站时刻已记");
	}
}
