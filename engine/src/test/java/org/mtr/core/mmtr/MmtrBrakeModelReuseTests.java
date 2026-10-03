package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.brake.BrakeCar;
import org.mtr.core.mmtr.brake.BrakeCommand;
import org.mtr.core.mmtr.brake.BrakeModel;
import org.mtr.core.mmtr.brake.BrakeSystem;
import org.mtr.core.mmtr.physics.PneumaticBrakeSpec;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * **制动模型的可复用性**（notes/270）——用户 2026-09-26：「尽量使其更加可复用，因为不同列车逻辑不同，
 * 操作也不同，手柄档位也不同，车体连挂形式也不同（需要留连挂接口）」。
 *
 * <p>四个轴各钉一组：</p>
 * <ol>
 *   <li><b>不同操纵方式</b>：三手柄 / 有级 / 无级在**同一份制动诉求**下必须给出同一个力
 *       （它们只是把手柄折成 {@link BrakeCommand} 的三种写法，共用 {@link BrakeSystem}）；</li>
 *   <li><b>不同手柄档位</b>：档位表长度随车底走（9 档货车与 11 位机车都落在同一个全常用位上）；</li>
 *   <li><b>不同列车逻辑</b>：每节车可以有自己的气压/闸片口径（{@link BrakeCar#spec()}）；</li>
 *   <li><b>不同连挂形式</b>：逐车列表就是连挂形式，状态串（{@link BrakeModel#encodeState()} /
 *       {@link BrakeModel#applyState} / {@link BrakeModel#seedAfterCoupling}）跨过合并与切分。</li>
 * </ol>
 *
 * <p>外加两组**零回归**：没配 bar 键的车底三种操纵方式都不接管（逐位回到旧模型）。</p>
 */
public final class MmtrBrakeModelReuseTests {

	private static final String AIR_KEYS =
		"\"airPipeNominalBar\":5.0,\"airPipeChargedBar\":5.2,\"airPipeFullServiceBar\":3.5,\"airPipeEmergencyBar\":3.0,"
			+ "\"airPipeTargetsBar\":\"5.2,4.6,4.4,4.2,4.05,3.9,3.8,3.7,3.6,3.5,3.0\","
			+ "\"airPipeChargeBarPerSecond\":0.35,\"airPipeDischargeBarPerSecond\":0.85,"
			+ "\"brakeCylinderMaxBar\":3.8,\"brakeCylinderSpringBar\":0.30,\"brakeCylinderEmergencyBar\":4.2,"
			+ "\"brakeCylinderApplyBarPerSecond\":1.30,\"brakeCylinderReleaseBarPerSecond\":0.95,"
			+ "\"brakeCylinderEmergencyBarPerSecond\":2.0,"
			+ "\"distributorRatio\":2.5333,\"distributorSensitivityBar\":0.20,"
			+ "\"padFadeEnabled\":true,\"padMu0\":0.39,\"padMuSlopePerKmh\":0.00045,\"padMuFloor\":0.28,"
			+ "\"blendingEnabled\":true,\"wspEnabled\":true,\"wheelSlipMu\":0.12,";

	/** 同一台 BR101 车底，只换 {@code controlMode} 与档数 —— "换一种操纵方式"在数据上就只是这两个键。 */
	private static String loco(String controlMode, int brakeNotches) {
		return "{"
			+ "  \"consistTypes\": ["
			+ "    {\"id\":\"bar\",\"name\":\"bar\",\"controlMode\":\"" + controlMode + "\",\"powerNotches\":97,"
			+ "     \"brakeNotches\":" + brakeNotches + ","
			+ "     \"maxSpeedKmh\":220,\"massKg\":84000,\"rotatingMassFactor\":1.16,"
			+ "     \"maxTractiveEffortN\":300000,\"maxPowerW\":6400000,"
			+ "     \"brakeWeightTonnes\":120,\"emergencyBrakeWeightTonnes\":168,"
			+ "     \"resistanceAN\":0,\"resistanceBN\":0,\"resistanceCN\":0,"
			+ "     \"adhesionMuMax\":0.37,\"sanding\":false,"
			+ "     \"rheostaticBrakeForceN\":150000,\"rheostaticFadeKmh\":15,\"rheostaticMaxPowerW\":6400000,"
			+ "     \"tractionLagMillis\":0,\"tractionRampNPerSecond\":0,"
			+ "     " + AIR_KEYS
			+ "     \"brakeRatios\":\"0,0.05,0.12,0.20,0.3333,0.4667,0.60,0.7333,0.8667,1.00,1.00\"}"
			+ "  ]"
			+ "}";
	}

	/** 一台**没配 bar 键**的货车（零回归那一组用）。 */
	private static final String LEGACY_JSON = "{"
		+ "  \"consistTypes\": ["
		+ "    {\"id\":\"legacy\",\"name\":\"legacy\",\"controlMode\":\"NOTCHED\",\"powerNotches\":8,\"brakeNotches\":9,"
		+ "     \"maxSpeedKmh\":100,\"massKg\":84000,\"rotatingMassFactor\":1.16,"
		+ "     \"maxTractiveEffortN\":300000,\"maxPowerW\":2000000,"
		+ "     \"serviceBrakeForceN\":150000,\"emergencyBrakeForceN\":210000,"
		+ "     \"resistanceAN\":0,\"resistanceBN\":0,\"resistanceCN\":0,"
		+ "     \"tractionLagMillis\":0,\"tractionRampNPerSecond\":0,"
		+ "     \"brakeRatios\":\"0,0.125,0.25,0.375,0.5,0.625,0.75,0.875,1.0\"}"
		+ "  ]"
		+ "}";

	/** 一辆**自己的缸压口径更小**的客车（逐车口径那一组用）：缸压上限 2.0 bar、无闸片衰减。 */
	private static final String WAGON_JSON = "{"
		+ "  \"consistTypes\": ["
		+ "    {\"id\":\"wagon\",\"name\":\"wagon\",\"controlMode\":\"NOTCHED\",\"powerNotches\":0,\"brakeNotches\":9,"
		+ "     \"maxSpeedKmh\":120,\"massKg\":40000,\"rotatingMassFactor\":1.06,"
		+ "     \"maxTractiveEffortN\":0,\"maxPowerW\":0,"
		+ "     \"brakeWeightTonnes\":40,\"emergencyBrakeWeightTonnes\":56,"
		+ "     \"resistanceAN\":0,\"resistanceBN\":0,\"resistanceCN\":0,"
		+ "     \"tractionLagMillis\":0,\"tractionRampNPerSecond\":0,"
		+ "     \"airPipeChargedBar\":5.2,\"airPipeFullServiceBar\":3.5,\"airPipeEmergencyBar\":3.0,"
		+ "     \"airPipeTargetsBar\":\"5.2,4.6,4.4,4.2,4.05,3.9,3.8,3.7,3.6,3.5,3.0\","
		+ "     \"brakeCylinderMaxBar\":2.0,\"brakeCylinderSpringBar\":0.20,"
		+ "     \"padFadeEnabled\":false,"
		+ "     \"brakeRatios\":\"0,0.125,0.25,0.375,0.5,0.625,0.75,0.875,1.0\"}"
		+ "  ]"
		+ "}";

	private static final long DT_MS = 50;
	private static final int TICKS = 240;

	private static ConsistType type(String json, String id) {
		return ConsistTypeRegistry.parse(json).get(id);
	}

	// ---- ① 不同操纵方式：同一份诉求 ⇒ 同一个力 ------------------------------------------------------

	/**
	 * **三种操纵方式驱动同一个制动模型**：11 位三手柄（第 p 位）、9 档有级（第 p 档）、无级（轴 = p/10）
	 * 给出的是**同一个制动诉求**（p/10），于是稳态气制动力必须逐位相同。
	 *
	 * <p>9 档货车不是凑数：{@code 档/档数 × getServiceDemandLimit()} = {@code p/9 × 0.9 = p/10} ——
	 * 最后一位正好落在"全常用"（3.5 bar），与机车第 9 位同位。这条钉住"手柄档位不同只是数据不同"。</p>
	 */
	@Test
	public void everyControlModeDrivesTheSameBrakeModel() {
		final ConsistType threeHandleType = type(loco("THREE_HANDLE", 11), "bar");
		final ConsistType notchedType = type(loco("NOTCHED", 9), "bar");
		final ConsistType steplessType = type(loco("STEPLESS", 9), "bar");
		System.out.println(String.format("[TEST] 全常用位：机车第 9 位 = %.2f bar，常用诉求上限 %.2f",
			threeHandleType.getBrakes().targetPipeBar(9), threeHandleType.getBrakes().getServiceDemandLimit()));

		for (int p = 1; p <= 9; p++) {
			final ThreeHandleDriveController threeHandle = new ThreeHandleDriveController();
			final NotchedDriveController notched = new NotchedDriveController();
			final SteplessDriveController stepless = new SteplessDriveController();
			final ControlState threeHandleControl = ControlState.zero().setBrakeNotch(p);
			final ControlState notchedControl = ControlState.zero().setBrakeNotch(p);
			final ControlState steplessControl = ControlState.zero().setBrakeAxis(p / 9.0);
			for (int i = 0; i < TICKS; i++) {
				threeHandle.compute(threeHandleControl, threeHandleType, 0, DT_MS);
				notched.compute(notchedControl, notchedType, 0, DT_MS);
				stepless.compute(steplessControl, steplessType, 0, DT_MS);
			}
			final double threeHandleKn = threeHandle.getLastPneumaticBrakeForceN() / 1000;
			final double notchedKn = notched.getBrakeModel().getPneumaticForceN() / 1000;
			final double steplessKn = stepless.getBrakeModel().getPneumaticForceN() / 1000;
			final double threeHandleBar = threeHandle.getCylinderBar();
			System.out.println(String.format("[TEST] 诉求 %.1f：三手柄 %.1f kN / 有级 %.1f kN / 无级 %.1f kN（缸压 %.2f bar）",
				p / 10.0, threeHandleKn, notchedKn, steplessKn, threeHandleBar));
			assertEquals(threeHandleKn, notchedKn, 0.5, "第 " + p + " 位：有级必须给同一个力");
			assertEquals(threeHandleKn, steplessKn, 0.5, "第 " + p + " 位：无级必须给同一个力");
			assertEquals(threeHandleBar, notched.getBrakeModel().getCylinderBar(), 0.01, "缸压也要同一位");
			assertEquals(threeHandleBar, stepless.getBrakeModel().getCylinderBar(), 0.01, "缸压也要同一位");
		}
	}

	/** **档位只是数据**：{@code notched(p, 11)} 与 {@code ofRatio(p/10)} 是同一个诉求（模型层）。 */
	@Test
	public void theNotchCountIsJustData() {
		final PneumaticBrakeSpec air = type(loco("THREE_HANDLE", 11), "bar").getBrakes();
		final BrakeCar car = new BrakeCar(102_200, 138_400, true, null);
		final BrakeModel byNotch = new BrakeModel("test-notch");
		final BrakeModel byRatio = new BrakeModel("test-ratio");
		for (int i = 0; i < TICKS; i++) {
			byNotch.step(air, car, BrakeCommand.notched(8, 11, 0, false), 0, 0, DT_MS / 1000.0);
			byRatio.step(air, car, BrakeCommand.ofRatio(0.8, 0, false), 0, 0, DT_MS / 1000.0);
		}
		assertEquals(byRatio.getCylinderBar(), byNotch.getCylinderBar(), 1e-9, "第 8 位 = 比例 0.8，逐位相同");
		assertEquals(3.55, byNotch.getCylinderBar(), 0.02, "第 8 位：管压 3.6 bar ⇒ 分配阀给 3.55 bar");
		assertEquals(0.9, air.getServiceDemandLimit(), 1e-9, "常用诉求上限 = 第 9 位（3.5 bar）");
	}

	// ---- ② 不同列车逻辑：逐车各自的闸片/缸压口径 ----------------------------------------------------

	/**
	 * **每节车可以有自己的口径**（{@link BrakeCar#spec()}）：同一列车里机车的缸压建到 3.8 bar，
	 * 而自带小缸径口径的客车停在 2.0 bar —— "不同列车逻辑"不需要不同的模型，只需要不同的数据。
	 */
	@Test
	public void eachCarCanCarryItsOwnBrakeSpec() {
		final PneumaticBrakeSpec locoAir = type(loco("THREE_HANDLE", 11), "bar").getBrakes();
		final PneumaticBrakeSpec wagonAir = type(WAGON_JSON, "wagon").getBrakes();
		assertNotNull(wagonAir, "客车自带口径");
		final List<BrakeCar> cars = new ArrayList<>();
		cars.add(new BrakeCar(102_200, 138_400, true, null));
		// 客车：既没有编组口径覆盖，也自带一套更小的缸压口径（闸片不衰减）
		cars.add(new BrakeCar(43_700, 58_800, false, wagonAir));
		final BrakeSystem system = new BrakeSystem(cars, locoAir);
		for (int i = 0; i < TICKS; i++) {
			system.step(BrakeCommand.ofRatio(0.9, 0, false), 0, 0, DT_MS / 1000.0);
		}
		System.out.println(String.format("[TEST] 逐车口径：机车缸压 %.2f bar、客车 %.2f bar（客车自己的上限 %.1f）",
			system.getCylinderBar(0), system.getCylinderBar(1), wagonAir.getCylinderMaxBar()));
		assertEquals(3.8, system.getCylinderBar(0), 0.02, "机车按编组口径建满");
		assertEquals(2.0, system.getCylinderBar(1), 0.02, "客车按**自己**的缸压上限封顶");
		assertTrue(system.getPneumaticForceN() > 0 && system.isPneumaticHolding(), "两节车都在出闸");
	}

	// ---- ③ 不同连挂形式：状态串跨过合并/切分 ------------------------------------------------------

	/**
	 * **连挂接口**：状态串往返（解挂切分 → 各自跑 → 重新合并）后逐车管压/缸压必须还在；
	 * 连挂后新挂上来的**无动力车**从 0 起（自己充风），动力车保留自己的风源。
	 */
	@Test
	public void theCouplingInterfaceMovesTheWholeBrakeState() {
		final PneumaticBrakeSpec air = type(loco("THREE_HANDLE", 11), "bar").getBrakes();
		final List<BrakeCar> cars = List.of(
			new BrakeCar(102_200, 138_400, true, null),
			new BrakeCar(43_700, 58_800, false, null),
			new BrakeCar(43_700, 58_800, false, null));

		final BrakeModel original = new BrakeModel("原编组");
		original.setCars(cars);
		for (int i = 0; i < TICKS; i++) {
			original.step(air, null, BrakeCommand.ofRatio(0.7, 0, false), 0, 0, DT_MS / 1000.0);
		}
		final String state = original.encodeState();
		assertFalse(state.isEmpty(), "配了 bar 键的车底必须能编出逐车状态串");
		assertEquals(3, state.split(";").length, "一节车一段：" + state);

		// 切分：后半列（2 节客车）带着自己的那一段继续跑，同时把管压排空（缓解）
		final String[] units = state.split(";");
		final BrakeModel tail = new BrakeModel("切分出来的尾列");
		tail.setCars(cars.subList(1, 3));
		tail.applyState(units[1] + ";" + units[2]);
		for (int i = 0; i < TICKS; i++) {
			tail.step(air, null, BrakeCommand.coast(), 0, 0, DT_MS / 1000.0);
		}
		assertEquals(0, tail.getCylinderBar(0), 0.02, "缓解位：切出去的尾列自己把闸排空");
		assertTrue(tail.getPipeBar(0) > 5.0, "管压回到充风压力：" + tail.getPipeBar(0));

		// 重新合并：切分时的状态灌回去 ⇒ 逐车管压/缸压与"原编组"分开的那一刻一致（现场：并钩不再凭空满管）
		final BrakeModel merged = new BrakeModel("重新合并");
		merged.setCars(cars);
		merged.applyState(state);
		for (int i = 0; i < 10; i++) {
			merged.step(air, null, BrakeCommand.ofRatio(0.7, 0, false), 0, 0, DT_MS / 1000.0);
			original.step(air, null, BrakeCommand.ofRatio(0.7, 0, false), 0, 0, DT_MS / 1000.0);
		}
		for (int i = 0; i < 3; i++) {
			assertEquals(original.getCylinderBar(i), merged.getCylinderBar(i), 0.01, "第 " + i + " 节车的缸压要跟着走");
		}

		// 连挂：新挂上来的两节无动力车从 0 起，动力车保住自己的管压
		final double headPipeBefore = original.getPipeBar(0);
		final List<BrakeCar> extended = new ArrayList<>(cars);
		extended.add(new BrakeCar(43_700, 58_800, false, null));
		extended.add(new BrakeCar(43_700, 58_800, false, null));
		final BrakeModel coupled = new BrakeModel("连挂后");
		coupled.setCars(extended);
		// 合并瞬间先把原状态交给模型（此刻系统还没建 ⇒ 先存着），下一拍按"新车列 + 原状态"建起来
		coupled.applyState(state);
		coupled.step(air, null, BrakeCommand.coast(), 0, 0, DT_MS / 1000.0);
		assertEquals(headPipeBefore, coupled.getPipeBar(0), 0.05, "原有的车不受影响（管压仍是切分时那一份）");
		coupled.seedAfterCoupling(3);
		assertEquals(0, coupled.getCylinderBar(3), 1e-9, "新挂上来的车缸压从 0 起");
		assertEquals(0, coupled.getPipeBar(3), 1e-9, "管压也从 0 起（随后自己充风）");
	}

	/** 系统**还没建**（新车还没人开完一拍）时收到的状态串不能丢：先存着，建起来的那一刻灌进去。 */
	@Test
	public void theStateSurvivesUntilTheModelIsFirstBuilt() {
		final PneumaticBrakeSpec air = type(loco("THREE_HANDLE", 11), "bar").getBrakes();
		final BrakeModel model = new BrakeModel("新合并的车");
		model.applyState("0.4,0.5");
		assertEquals("0.4,0.5", model.encodeState(), "还没建系统 ⇒ 原样保留，不能编成空串");
		final BrakeCar car = new BrakeCar(102_200, 138_400, true, null);
		model.step(air, car, BrakeCommand.coast(), 0, 0, DT_MS / 1000.0);
		System.out.println(String.format("[TEST] 迟到的状态串：建系统后管压 %.3f bar、缸压 %.3f bar",
			model.getPipeBar(), model.getCylinderBar()));
		assertEquals(5.2 * 0.4, model.getPipeBar(), 0.05, "管压按切分时那一份恢复（而不是满管）");
		assertEquals(3.8 * 0.5, model.getCylinderBar(), 0.1, "缸压同理（第一拍按建压速率往上走了一点）");
	}

	// ---- ④ 零回归：没配 bar 键 ⇒ 三种操纵方式都不接管 ----------------------------------------------

	/** 没配 bar 键的车底：模型不接管（{@code isPneumatic()} 假、bar 读数为 0），逐位回到旧归一化模型。 */
	@Test
	public void aConsistWithoutAirKeysKeepsTheLegacyModel() {
		final ConsistType legacy = type(LEGACY_JSON, "legacy");
		final NotchedDriveController notched = new NotchedDriveController();
		final SteplessDriveController stepless = new SteplessDriveController();
		final ConsistType notchedType = legacy;
		final ConsistType steplessType = type(LEGACY_JSON.replace("\"controlMode\":\"NOTCHED\"", "\"controlMode\":\"STEPLESS\""), "legacy");
		final ControlState brake = ControlState.zero().setBrakeNotch(9);
		for (int i = 0; i < TICKS; i++) {
			notched.compute(brake, notchedType, 50 / 3.6, DT_MS);
			stepless.compute(ControlState.zero().setBrakeAxis(0.5), steplessType, 50 / 3.6, DT_MS);
		}
		assertFalse(notched.getBrakeModel().isPneumatic(), "没有 bar 键 ⇒ 有级控制器不接管");
		assertFalse(stepless.getBrakeModel().isPneumatic(), "没有 bar 键 ⇒ 无级控制器不接管");
		assertEquals(0, notched.getBrakeModel().getCylinderBar(), 1e-9, "bar 读数为 0（那一路读数走归一化字段）");
		assertEquals(0, stepless.getBrakeModel().getCylinderBar(), 1e-9);
		// 旧口径的解析值一个字都不许变：减速度 = f(档位比例) + 运行阻力
		final double expected = -(legacy.getPhysics().serviceBrakeDecelerationMps2(9.0 / legacy.getBrakeNotches(), 50 / 3.6));
		assertEquals(expected, notched.compute(brake, notchedType, 50 / 3.6, DT_MS).getAccelerationMetersPerSecondSquared(), 1e-9,
			"没配 bar 键 ⇒ 有级制动逐位还是旧公式");
	}

	/** 有级/无级接管后**惰行不许点亮制动灯**，而闸没排空时**不许给牵引**（真车牵引联锁）。 */
	@Test
	public void thePneumaticPathKeepsTheInterlockAndDoesNotLightTheLampWhenCoasting() {
		final ConsistType type = type(loco("NOTCHED", 9), "bar");
		final NotchedDriveController controller = new NotchedDriveController();
		final double speedMps = 40 / 3.6;
		for (int i = 0; i < TICKS; i++) {
			controller.compute(ControlState.zero().setBrakeNotch(9), type, speedMps, DT_MS);
		}
		assertEquals(3.8, controller.getBrakeModel().getCylinderBar(), 0.02, "9 档建到缸压上限");

		// 拉满牵引但闸还压着 ⇒ 牵引一份力都不许出（牵引联锁）：与"不推牵引"逐位相同
		final ControlState traction = ControlState.zero().setBrakeNotch(9).setThrottleNotch(8);
		final double withThrottle = controller.compute(traction, type, speedMps, DT_MS).getAccelerationMetersPerSecondSquared();
		final double withoutThrottle = controller.compute(ControlState.zero().setBrakeNotch(9), type, speedMps, DT_MS)
			.getAccelerationMetersPerSecondSquared();
		assertTrue(controller.getBrakeModel().isPneumaticHolding(), "缸压压着 ⇒ 联锁生效");
		assertEquals(withoutThrottle, withThrottle, 1e-9, "缸压没排空 ⇒ 推牵引什么也不加（牵引联锁）");
		assertTrue(withThrottle < 0, "那一刻车是在减速（闸真的在出制动力）");
		assertTrue(controller.compute(traction, type, speedMps, DT_MS).isBrakeLamp(), "有闸时制动灯亮");

		// 回缓解位、等闸排空后再惰行：不许点灯
		final ControlState coast = ControlState.zero();
		for (int i = 0; i < TICKS * 2; i++) {
			controller.compute(coast, type, speedMps, DT_MS);
		}
		final DriveOutput idle = controller.compute(coast, type, speedMps, DT_MS);
		assertEquals(0, controller.getBrakeModel().getCylinderBar(), 0.02, "缓解位把闸排空");
		assertFalse(idle.isBrakeLamp(), "惰行不许点亮制动灯（旧支只在制动档点灯）");
		assertEquals(0, idle.getAccelerationMetersPerSecondSquared(), 1e-9,
			"惰行只剩运行阻力（本车底 A=B=C=0 ⇒ 0），既没有闸也没有牵引");
		// 排空后牵引恢复
		assertTrue(controller.compute(ControlState.zero().setThrottleNotch(8), type, speedMps, DT_MS)
			.getAccelerationMetersPerSecondSquared() > 0, "闸排空后牵引恢复");
	}
}
