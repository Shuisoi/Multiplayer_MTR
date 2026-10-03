package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.physics.BrakeSpec;
import org.mtr.core.mmtr.physics.PneumaticBrakeSpec;

import static org.junit.jupiter.api.Assertions.*;

/**
 * **列车管气压与制动力模型**（notes/266，规格见 {@code docs/01-设计/制动系统-气压与制动力模型-设计.md}）。
 *
 * <p>用户口径（2026-09-25）：标准气压 5.0 bar、运行停留 5.2 bar、初制动 4.6 bar、逐级递减到 3.5 bar；
 * 制动力按 UIC 制动重率反推；闸片摩擦随速衰减；电制动高速按恒功率掉。</p>
 *
 * <p>这里钉六组数：① UIC 锚（102.2 / 138.4 kN）；② 级位表与分配阀（含灵敏限/限压）；
 * ③ 缸簧死区与闸片 κ(v)；④ 建压/缓解时间；⑤ 电制动三段（含 153.6 km/h 折点）；
 * ⑥ 镜像串往返 + **旧串/旧配置零回归**（没有 bar 键 ⇒ 逐位回到归一化老模型）。</p>
 */
public final class MmtrPneumaticBrakeTests {

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

	/** 气压口径的 BR101（力由 UIC 制动重率反推：120 t / 168 t）。 */
	private static final String BAR_JSON = "{"
		+ "  \"consistTypes\": ["
		+ "    {\"id\":\"bar\",\"name\":\"bar\",\"controlMode\":\"THREE_HANDLE\",\"powerNotches\":97,\"brakeNotches\":11,"
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

	/** 同一台车，但**一个 bar 键都没有**：必须逐位回到旧归一化模型（零回归）。 */
	private static final String LEGACY_JSON = "{"
		+ "  \"consistTypes\": ["
		+ "    {\"id\":\"legacy\",\"name\":\"legacy\",\"controlMode\":\"THREE_HANDLE\",\"powerNotches\":97,\"brakeNotches\":11,"
		+ "     \"maxSpeedKmh\":220,\"massKg\":84000,\"rotatingMassFactor\":1.16,"
		+ "     \"maxTractiveEffortN\":300000,\"maxPowerW\":6400000,"
		+ "     \"serviceBrakeForceN\":150000,\"emergencyBrakeForceN\":210000,"
		+ "     \"resistanceAN\":0,\"resistanceBN\":0,\"resistanceCN\":0,"
		+ "     \"rheostaticBrakeForceN\":150000,\"rheostaticFadeKmh\":15,"
		+ "     \"tractionLagMillis\":0,\"tractionRampNPerSecond\":0,"
		+ "     \"brakeRatios\":\"0,0.05,0.12,0.20,0.3333,0.4667,0.60,0.7333,0.8667,1.00,1.00\"}"
		+ "  ]"
		+ "}";

	private static final long DT_MS = 50;
	private static final double MEQ_KG = 84000 * 1.16;
	/** UIC：λ = 120/84 = 142.86% ⇒ a = 1.0486 ⇒ F = 102.2 kN。 */
	private static final double UIC_SERVICE_N = 102_200;
	/** UIC：λ = 200% ⇒ a = 1.42 ⇒ F = 138.4 kN。 */
	private static final double UIC_EMERGENCY_N = 138_400;

	private static ConsistType type(String json, String id) {
		return ConsistTypeRegistry.parse(json).get(id);
	}

	private static PneumaticBrakeSpec air() {
		return airOf(BAR_JSON);
	}

	private static PneumaticBrakeSpec airOf(String json) {
		final PneumaticBrakeSpec brakes = type(json, "bar").getHandles().getBrakes();
		assertNotNull(brakes, "气压口径的车底必须带气压规格");
		return brakes;
	}

	/** 同一台车，但列车管沿车列传播的松弛率改成 1.0/s（"挂车自己的气路更慢"，片 3）。 */
	private static final String SLOW_AIR_JSON = BAR_JSON.replace("\"airPipeNominalBar\":5.0",
		"\"airPipePropagationPerSecond\":1.0,\"airPipeNominalBar\":5.0");

	private static ThreeHandleDriveController controller() {
		return new ThreeHandleDriveController();
	}

	// ---- ① UIC 标定 -------------------------------------------------------------------------------

	@Test
	public void theForceAnchorsComeFromTheUicBrakeWeight() {
		final ConsistType type = type(BAR_JSON, "bar");
		assertEquals(UIC_SERVICE_N, type.getBrake().getServiceForceN(), 300,
			"常用制动力 = m_equiv × (0.0065λ + 0.12)，λ = 120/84");
		assertEquals(UIC_EMERGENCY_N, type.getBrake().getEmergencyForceN(), 300,
			"紧急制动力 = λ=200% 那一档（R+E 168 t）");
		// 与铭牌/旧口径的关系：UIC 明显更保守（这正是用户 2026-09-25 选它的原因）
		assertTrue(UIC_SERVICE_N < 150_000 * 0.75, "UIC 反推 ≈ 铭牌 150 kN 的 68%");
		assertEquals(1.353, type.getBrake().getEmergencyForceN() / type.getBrake().getServiceForceN(), 0.01,
			"紧急/常用 = a(200%)/a(142.9%) = 1.354（旧口径的制动重量比是 1.4）");
		// 纯函数本身
		assertEquals(1.0486, BrakeSpec.uicDecelerationMps2(120, 84000), 1e-3);
		assertEquals(1.42, BrakeSpec.uicDecelerationMps2(168, 84000), 1e-3);
	}

	// ---- ② 级位表与分配阀 -------------------------------------------------------------------------

	@Test
	public void theNotchTableIsTheUsersPipeSchedule() {
		final PneumaticBrakeSpec air = air();
		final double[] expected = {5.2, 4.6, 4.4, 4.2, 4.05, 3.9, 3.8, 3.7, 3.6, 3.5, 3.0};
		for (int notch = 0; notch < expected.length; notch++) {
			assertEquals(expected[notch], air.targetPipeBar(notch), 1e-9, "档位 " + notch + " 的列车管目标");
		}
		assertEquals(5.0, air.getNominalBar(), 1e-9, "标准气压 5.0 bar（表盘标称）");
		assertEquals(5.2, air.getChargedBar(), 1e-9, "运行位实际停在 5.2 bar");
		assertEquals(4.6, air.targetPipeBar(1), 1e-9, "初制动（1A）= 4.6 bar");
		assertEquals(3.5, air.getFullServiceBar(), 1e-9, "8 档 = 全常用 3.5 bar");
		assertTrue(air.isReleasePosition(0) && !air.isReleasePosition(1), "只有运行位是缓解位");
	}

	@Test
	public void theDistributorAppliesRatioSensitivityAndLimit() {
		final PneumaticBrakeSpec air = air();
		assertEquals(0, air.distributorCylinderBar(5.2), 1e-9, "管压没掉 ⇒ 缸压 0");
		assertEquals(0, air.distributorCylinderBar(5.05), 1e-9, "管压降 0.15 bar < 灵敏限 0.2 ⇒ 缸压不动");
		assertEquals(2.5333 * 0.4, air.distributorCylinderBar(4.6), 1e-6, "1A：降 0.6 ⇒ 扣灵敏限后 ×2.5333");
		assertEquals(3.8, air.distributorCylinderBar(3.5), 1e-9, "8 档：降 1.7 ⇒ 顶到缸压上限");
		assertEquals(3.8, air.distributorCylinderBar(2.0), 1e-9, "管压更低也**不许**越过限压阀");
		// 每一档的稳态缸压（与设计文 §2.2 的表逐行对账）
		final double[] expectedCylinder = {0, 1.013, 1.520, 2.027, 2.407, 2.787, 3.040, 3.293, 3.547, 3.800, 3.800};
		for (int notch = 0; notch < expectedCylinder.length; notch++) {
			assertEquals(expectedCylinder[notch], air.distributorCylinderBar(air.targetPipeBar(notch)), 2e-3,
				"档位 " + notch + " 的稳态缸压");
		}
	}

	// ---- ③ 缸簧死区与闸片衰减 ---------------------------------------------------------------------

	@Test
	public void cylinderPressureTurnsIntoForceWithSpringDeadbandAndSaturation() {
		final ConsistType type = type(BAR_JSON, "bar");
		final BrakeSpec brake = type.getBrake();
		final double anchorN = brake.getServiceForceN();
		assertEquals(UIC_SERVICE_N, anchorN, 300, "锚 = UIC 反推");
		assertEquals(0, brake.serviceForceNFromCylinderBar(0.30, 0), 1e-9, "缸簧 0.3 bar 以下不出力");
		assertEquals(0, brake.serviceForceNFromCylinderBar(0.10, 0), 1e-9);
		assertEquals(anchorN * (1.013 - 0.30) / 3.5, brake.serviceForceNFromCylinderBar(1.013, 0), 100,
			"1A 初制动 ≈ 20.8 kN");
		assertEquals(anchorN, brake.serviceForceNFromCylinderBar(3.8, 0), 0.5, "8 档 = 全常用锚");
		assertEquals(anchorN, brake.serviceForceNFromCylinderBar(4.2, 0), 0.5, "常用侧饱和（4.2 是紧急限压）");
	}

	@Test
	public void padFrictionFallsWithSpeed() {
		final BrakeSpec brake = type(BAR_JSON, "bar").getBrake();
		assertTrue(brake.isPadFadeEnabled(), "随包配置开着闸片衰减");
		assertEquals(0.39, brake.padFrictionCoefficient(0), 1e-9);
		assertEquals(0.345, brake.padFrictionCoefficient(100 / 3.6), 1e-3, "100 km/h ⇒ μ=0.345");
		assertEquals(0.30, brake.padFrictionCoefficient(200 / 3.6), 1e-3, "200 km/h ⇒ μ=0.30（规格模块一）");
		assertEquals(0.885, brake.frictionFactor(100 / 3.6), 1e-3);
		assertEquals(0.769, brake.frictionFactor(200 / 3.6), 1e-3);
		assertEquals(78_600, brake.serviceForceNFromCylinderBar(3.8, 200 / 3.6), 300,
			"8 档 @200 km/h = 102.2 kN × 0.769 ≈ 78.6 kN");
		// 关闭衰减 ⇒ 与旧口径一致（零回归的判据）
		final BrakeSpec noFade = new BrakeSpec(150_000, 210_000);
		assertFalse(noFade.isPadFadeEnabled());
		assertEquals(1, noFade.frictionFactor(200 / 3.6), 1e-12);
	}

	// ---- ④ 时间：建压 / 缓解 -----------------------------------------------------------------------

	@Test
	public void applicationAndReleaseFollowTheConfiguredRates() {
		final ConsistType type = type(BAR_JSON, "bar");
		final ThreeHandleDriveController controller = controller();
		final ControlState fullService = ControlState.zero().setBrakeNotch(9);

		double secondsToFull = 0;
		for (int i = 0; i < 400 && controller.getCylinderBar() < 3.8 - 1e-9; i++) {
			controller.compute(fullService, type, 0, DT_MS);
			secondsToFull += DT_MS / 1000.0;
		}
		System.out.println("[TEST] 全常用建压 " + Math.round(secondsToFull * 100) / 100.0
			+ " s（管压 " + Math.round(controller.getPipeBar() * 100) / 100.0 + " bar）");
		assertTrue(secondsToFull > 2.0 && secondsToFull < 4.5,
			"管压排空 2 s + 缸压按 1.3 bar/s 建 ⇒ 3 s 量级，实际 " + secondsToFull);
		assertEquals(3.5, controller.getPipeBar(), 1e-6, "8 档管压停在 3.5 bar");
		assertEquals(3.8, controller.getCylinderBar(), 1e-6);

		double secondsToRelease = 0;
		final ControlState released = ControlState.zero();
		for (int i = 0; i < 400 && controller.getCylinderBar() > 0.35; i++) {
			controller.compute(released, type, 0, DT_MS);
			secondsToRelease += DT_MS / 1000.0;
		}
		System.out.println("[TEST] 松闸到联锁放开（缸压 ≤0.35 bar）" + Math.round(secondsToRelease * 100) / 100.0 + " s");
		assertTrue(secondsToRelease < 6, "松闸后牵引联锁必须很快放开，实际 " + secondsToRelease + " s");
		assertTrue(controller.getPipeBar() > 4.8, "联锁放开时管子已经充回大半，实际 " + controller.getPipeBar());
		// 再等 3 s：管子回到运行位的 5.2 bar、缸压归零（完全缓解）
		for (int i = 0; i < 60; i++) {
			controller.compute(released, type, 0, DT_MS);
		}
		assertEquals(5.2, controller.getPipeBar(), 1e-6, "运行位管子充回 5.2 bar");
		assertEquals(0, controller.getCylinderBar(), 1e-9, "完全缓解");
	}

	@Test
	public void theEmergencyVentIsFastAndUsesTheEmergencyAnchor() {
		final ConsistType type = type(BAR_JSON, "bar");
		final ThreeHandleDriveController controller = controller();
		controller.compute(ControlState.zero().setBrakeNotch(9), type, 0, DT_MS);

		double secondsToEmergencyCylinder = 0;
		final ControlState emergency = ControlState.zero().setEmergency(true);
		for (int i = 0; i < 200 && controller.getCylinderBar() < 4.19; i++) {
			controller.compute(emergency, type, 0, DT_MS);
			secondsToEmergencyCylinder += DT_MS / 1000.0;
		}
		assertTrue(secondsToEmergencyCylinder < 2.5, "紧急缸压 4.2 bar 要在 2.5 s 内建起来，实际 " + secondsToEmergencyCylinder);
		assertEquals(3.0, controller.getPipeBar(), 1e-6, "紧急管压排到 3.0 bar");
		final DriveOutput out = controller.compute(emergency, type, 0, DT_MS);
		assertTrue(out.isEmergencyBrake());
		assertEquals(-UIC_EMERGENCY_N / MEQ_KG, out.getAccelerationMetersPerSecondSquared(), 0.02,
			"紧急力取**紧急锚**（138.4 kN），不是缸压 4.2 bar 折出来的常用力");
	}

	@Test
	public void theFirstNotchIsARealInitialServiceApplication() {
		final ConsistType type = type(BAR_JSON, "bar");
		final ThreeHandleDriveController controller = controller();
		final ControlState first = ControlState.zero().setBrakeNotch(1);
		for (int i = 0; i < 200; i++) {
			controller.compute(first, type, 0, DT_MS);
		}
		assertEquals(4.6, controller.getPipeBar(), 1e-6, "1A 管压 4.6 bar");
		assertEquals(1.013, controller.getCylinderBar(), 2e-3);
		final double forceN = type.getBrake().serviceForceNFromCylinderBar(controller.getCylinderBar(), 0);
		System.out.println("[TEST] 初制动（1A）：缸压 " + Math.round(controller.getCylinderBar() * 1000) / 1000.0
			+ " bar，轮周力 " + Math.round(forceN / 100) / 10.0 + " kN");
		assertEquals(20_700, forceN, 500, "初制动 ≈ 20.8 kN（旧表的 1A 只有 7.5 kN：bar 语义下弱档不存在）");
		assertTrue(forceN > 15_000, "初制动必须明显强于旧口径的弱档");
	}

	// ---- ⑦ 电空混合（P3）--------------------------------------------------------------------------

	/**
	 * **电空混合**（notes/267，规格模块三）：电制动先吃饱、机械补缺口，EP 阀**只削不加**。
	 * 于是**总力不变**（还是司机手柄那份），变的只是"谁来出"与缸压。
	 */
	@Test
	public void blendingLetsTheElectricBrakeCoverTheMechanicalAsk() {
		final ConsistType type = type(BAR_JSON, "bar");
		final ControlState full = ControlState.zero().setBrakeNotch(9);

		// ① 200 km/h：电制动可用 115.2 kN > 诉求 78.6 kN（8 档在 200 km/h 的闸片衰减值）⇒ 机械被削到 0
		final ThreeHandleDriveController fast = controller();
		for (int i = 0; i < 400; i++) {
			fast.compute(full, type, 200 / 3.6, DT_MS);
		}
		final double askFastN = type.getBrake().serviceForceNFromCylinderBar(3.8, 200 / 3.6);
		assertEquals(78_600, askFastN, 300, "200 km/h 的诉求 = 102.2 kN × κ(200)");
		assertEquals(0, fast.getCylinderBar(), 1e-6, "电制动吃得下 ⇒ 缸压被 EP 阀削到 0");
		assertTrue(fast.getLastRheostaticRatio() > 0.6, "电制动接过了这份力，实际比例 " + fast.getLastRheostaticRatio());

		// ② 静止（电制动已淡出）：机械必须把全部诉求担起来 —— "只削不加"的反面
		final ThreeHandleDriveController slow = controller();
		for (int i = 0; i < 400; i++) {
			slow.compute(full, type, 0, DT_MS);
		}
		assertEquals(3.8, slow.getCylinderBar(), 1e-6, "电制动没了 ⇒ 缸压回到分配阀给的那一档");
		assertEquals(0, slow.getLastRheostaticRatio(), 1e-9);

		// ③ 关掉混合：缸压只由管压决定、电制动不自动参与（= P1+P2 的行为）
		final ConsistType noBlend = type(BAR_JSON.replace("\"blendingEnabled\":true", "\"blendingEnabled\":false"), "bar");
		final ThreeHandleDriveController off = controller();
		for (int i = 0; i < 400; i++) {
			off.compute(full, noBlend, 200 / 3.6, DT_MS);
		}
		assertEquals(3.8, off.getCylinderBar(), 1e-6, "混合关掉 ⇒ 缸压就是分配阀那档，不被削");
		assertEquals(0, off.getLastRheostaticRatio(), 1e-9, "自动电制动不参与");
	}

	/** 混合用的自动电制动与**司机电阻制动手柄**取大不相加（电机只有一台）。 */
	@Test
	public void theElectricBrakeIsNotCountedTwiceWhenBothHandlesAreUsed() {
		final ConsistType type = type(BAR_JSON, "bar");
		final ThreeHandleDriveController controller = controller();
		// 电制动手柄推满（-97）**同时**拉 8 档气制动；100 km/h 电制动仍是满力 150 kN（折点 153.6 km/h）
		final ControlState both = ControlState.zero().setDriveHandle(-97).setBrakeNotch(9);
		for (int i = 0; i < 400; i++) {
			controller.compute(both, type, 100 / 3.6, DT_MS);
		}
		final double electricKn = type.getHandles().rheostaticEffortN(100 / 3.6) / 1000;
		final double totalKn = type.getHandles().rheostaticEffortN(100 / 3.6) * controller.getLastRheostaticRatio() / 1000;
		System.out.println(String.format("[TEST] 双手柄 @100 km/h：电制动可用 %.1f kN，实际 %.1f kN（不是相加）", electricKn, totalKn));
		assertEquals(electricKn, totalKn, 1.0, "总电制动 = max(手柄, 混合) 而不是两者相加");
		assertTrue(totalKn < electricKn * 1.2, "相加会得到 ~220 kN，实际 " + totalKn);
		assertEquals(0, controller.getCylinderBar(), 1e-6, "电制动已经吃下诉求 ⇒ 缸压被削到 0");
	}

	// ---- ⑧ 黏着截断与 WSP（P4）-------------------------------------------------------------------

	/** Curtius-Kniffler 制动黏着曲线：干轨 0.331 → 0.213 → 0.192；天气/撒砂仍然取小生效。 */
	@Test
	public void theBrakingAdhesionFollowsCurtiusKniffler() {
		final ConsistType type = type(BAR_JSON, "bar");
		assertEquals(0.3315, type.getAdhesion().brakingMu(0), 1e-3, "0 km/h：7.5/44 + 0.161");
		assertEquals(0.2131, type.getAdhesion().brakingMu(100 / 3.6), 1e-3, "100 km/h");
		assertEquals(0.1917, type.getAdhesion().brakingMu(200 / 3.6), 1e-3, "200 km/h");
		assertEquals(273_100, type.getPhysics().brakingAdhesionLimitN(0), 500, "84 t 干轨 @0 km/h");
		assertEquals(158_000, type.getPhysics().brakingAdhesionLimitN(200 / 3.6), 500, "84 t 干轨 @200 km/h");

		// 湿轨（μ=0.20）比 C-K 更差 ⇒ 取湿轨那个数；落叶（0.07）同理
		final ConsistType wet = type(BAR_JSON.replace("\"adhesionMuMax\":0.37", "\"adhesionMuMax\":0.20"), "bar");
		assertEquals(0.20, wet.getAdhesion().brakingMu(0), 1e-9, "湿轨取小");
		final ConsistType leaves = type(BAR_JSON.replace("\"adhesionMuMax\":0.37", "\"adhesionMuMax\":0.07"), "bar");
		assertEquals(0.07, leaves.getAdhesion().brakingMu(100 / 3.6), 1e-9, "落叶/油污取小");
		assertEquals(57_700, leaves.getPhysics().brakingAdhesionLimitN(100 / 3.6), 500, "落叶：84 t 只剩 57.7 kN");
	}

	/**
	 * **截断与 WSP**：干轨上这台车根本吃不满黏着（1.04 m/s² ≪ 0.19g），所以 P4 实际是一条**天气特性**；
	 * 只有落叶/油污（或夸张的制动重量）才真的截，WSP 关掉时才会出现"抱死断崖"。
	 */
	@Test
	public void theAdhesionLimitOnlyBitesInBadWeatherButTheClampIsReal() {
		final ConsistType dry = type(BAR_JSON, "bar");
		// 需求 300 kN（远超这台车实际会有的 138 kN）⇒ 干轨 @0 km/h 上限 273 kN
		assertEquals(273_100, dry.getPhysics().adhesionLimitedBrakingForceN(300_000, 0, 0, true, 0.12), 500, "WSP 恒开：钳到上限");
		assertEquals(98_900, dry.getPhysics().adhesionLimitedBrakingForceN(300_000, 0, 0, false, 0.12), 1_000,
			"无 WSP：断崖到动摩擦 0.12·m·g（力反而更小 —— 这就是抱死）");
		assertEquals(200_000, dry.getPhysics().adhesionLimitedBrakingForceN(200_000, 0, 0, false, 0.12), 1e-9, "没超限就不截");

		// 控制器端到端：落叶 + 8 档 @200 km/h ⇒ 诉求 78.6 kN 被截到 57.7 kN
		final ConsistType leaves = type(BAR_JSON
			.replace("\"adhesionMuMax\":0.37", "\"adhesionMuMax\":0.07")
			.replace("\"blendingEnabled\":true", "\"blendingEnabled\":false"), "bar");
		final ThreeHandleDriveController controller = controller();
		DriveOutput out = DriveOutput.coast();
		for (int i = 0; i < 400; i++) {
			out = controller.compute(ControlState.zero().setBrakeNotch(9), leaves, 200 / 3.6, DT_MS);
		}
		assertEquals(3.8, controller.getCylinderBar(), 1e-6, "机械照常建压（分配阀不知道黏着）");
		final double expectedDecel = -(57_700 + leaves.getPhysics().resistanceForceN(200 / 3.6)) / leaves.getPhysics().effectiveMassKg();
		assertEquals(expectedDecel, out.getAccelerationMetersPerSecondSquared(), 0.02,
			"实际减速度按**截断后**的 57.7 kN 算（叶子轨上 8 档也只有这么多）");
	}

	/**
	 * **HUD 的"制动力（气）"必须是整列的数**（notes/269，用户 2026-09-25 现场：「制动力 xx kN 只显示电制动的」）。
	 *
	 * <p>现场那条日志：`制动=4 缸压=0.0 管压=0.779 电机=-54kN` —— 电空混合把**机车自己**那份闸（≈53 kN）
	 * 交给电制动削掉了，机车缸压因此是 0；但**拖车仍在气制动**（≈45 kN）。用"车头缸压 × 制动锚"反算
	 * 就把气的那一份整段丢掉 —— 所以它必须由服务端**逐车求和**后镜像下去（`mmtrPneumaticBrakeForceN`）。</p>
	 */
	@Test
	public void thePneumaticBrakeForceIsTheWholeTrainNotTheHeadCar() {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(CONSIST_JSON);
		final ConsistType aggregate = consistOf(registry, 2);
		final MmtrComposition composition = compositionOf(registry, 2);
		final ThreeHandleDriveController controller = controller();
		controller.setBrakeCars(composition.brakeCars());

		final double speedMps = 132 / 3.6;
		for (int i = 0; i < 400; i++) {
			controller.compute(ControlState.zero().setBrakeNotch(4), aggregate, speedMps, DT_MS);
		}
		final double pneumaticKn = controller.getLastPneumaticBrakeForceN() / 1000;
		System.out.println(String.format("[TEST] 现场场景（3 档 @132 km/h）：机车缸压 %.3f bar、拖车 %.3f bar ⇒ 整列气制动力 %.1f kN",
			controller.getCylinderBar(0), controller.getCylinderBar(1), pneumaticKn));
		assertEquals(0, controller.getCylinderBar(0), 1e-6, "机车自己的缸压被 EP 阀削到 0（电制动替掉了它）");
		assertEquals(2.41, controller.getCylinderBar(1), 0.15, "拖车仍在气制动（分配阀给的那一档）");
		assertTrue(pneumaticKn > 35 && pneumaticKn < 55, "整列气制动力 ≈ 2×43.7×f(2.41)×κ(132)，实际 " + pneumaticKn);
		assertTrue(controller.getLastRheostaticRatio() > 0.3, "电制动承担了机车那一份");
	}

	// ---- ⑨ 逐车管压（P5）--------------------------------------------------------------------------

	/**
	 * **逐车列车管**（notes/268，P5）：司机阀只作用在车头，压力沿车列往后传 ⇒ **尾车的闸比车头晚**。
	 *
	 * <p>钉三件事：① 车头先建压、尾车滞后（≥0.3 s）；② **过渡过程**里逐车管压给出的力比"整列一个管压"小
	 * （这就是"长编组制动距离更长"的来源）；③ **稳态一致** —— 所有车缸压相同后，逐车力之和与整列一个锚
	 * 逐位相同，所以 P5 只改过渡、不改稳态手感。</p>
	 */
	@Test
	public void perCarPipesMakeTheTailBrakeLaterThanTheHead() {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(CONSIST_JSON);
		final ConsistType aggregate = consistOf(registry, 4);
		final MmtrComposition composition = compositionOf(registry, 4);
		final double[] anchors = composition.brakeCars().stream()
			.mapToDouble(org.mtr.core.mmtr.brake.BrakeCar::serviceForceN).toArray();

		final ThreeHandleDriveController perCar = controller();
		perCar.setBrakeCars(composition.brakeCars());
		assertTrue(perCar.isPerCarBrakePipe(), "5 节编组必须走逐车管压");
		final ThreeHandleDriveController single = controller();

		final ControlState full = ControlState.zero().setBrakeNotch(9);
		double headSeconds = 0;
		double tailSeconds = 0;
		double perCarEarly = 0;
		double singleEarly = 0;
		double perCarSteady = 0;
		double singleSteady = 0;
		for (int i = 1; i <= 240; i++) {
			perCar.compute(full, aggregate, 0, DT_MS);
			single.compute(full, aggregate, 0, DT_MS);
			final double seconds = i * DT_MS / 1000.0;
			if (headSeconds == 0 && perCar.getCylinderBar(0) >= 3.79) {
				headSeconds = seconds;
			}
			if (tailSeconds == 0 && perCar.getCylinderBar(4) >= 3.79) {
				tailSeconds = seconds;
			}
			if (Math.abs(seconds - 1.0) < 1e-9) {
				perCarEarly = perCarForceN(perCar, aggregate, anchors);
				singleEarly = typeForceN(single, aggregate);
			}
			if (Math.abs(seconds - 12.0) < 1e-9) {
				perCarSteady = perCarForceN(perCar, aggregate, anchors);
				singleSteady = typeForceN(single, aggregate);
			}
		}
		System.out.println(String.format("[TEST] 逐车管压：车头缸压到位 %.2f s、尾车 %.2f s（滞后 %.2f s）", headSeconds, tailSeconds, tailSeconds - headSeconds));
		System.out.println(String.format("[TEST] 1 s 时制动力：逐车 %.1f kN / 整列 %.1f kN；12 s 时：逐车 %.1f kN / 整列 %.1f kN",
			perCarEarly / 1000, singleEarly / 1000, perCarSteady / 1000, singleSteady / 1000));
		assertTrue(tailSeconds > headSeconds + 0.3, "尾车必须明显滞后，实际头 " + headSeconds + " s / 尾 " + tailSeconds + " s");
		assertEquals(3.8, perCar.getCylinderBar(0), 1e-3, "车头建满");
		assertEquals(3.8, perCar.getCylinderBar(4), 1e-3, "12 s 后尾车也要建满");
		assertTrue(perCarEarly < singleEarly * 0.95, "过渡过程里逐车管压的力必须更小（尾车还没建压）");
		assertEquals(singleSteady, perCarSteady, 500, "稳态必须一致（P5 只改过渡）");
	}

	/** **逐车管压让长编组的制动距离更长**（用户 2026-09-25 问过的"长编组会不会更长"）。 */
	@Test
	public void thePerCarPipeLengthensTheStoppingDistance() {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(CONSIST_JSON);
		final ConsistType aggregate = consistOf(registry, 4);
		final MmtrComposition composition = compositionOf(registry, 4);
		final ThreeHandleDriveController perCar = controller();
		perCar.setBrakeCars(composition.brakeCars());

		final double perCarM = stoppingDistanceM(perCar, aggregate, ControlState.zero().setBrakeNotch(9), 100 / 3.6);
		final double singleM = stoppingDistanceM(controller(), aggregate, ControlState.zero().setBrakeNotch(9), 100 / 3.6);
		System.out.println(String.format("[TEST] 100 km/h 全常用（BR101+4×p1）：逐车管压 %.0f m / 整列一个管压 %.0f m", perCarM, singleM));
		assertTrue(perCarM > singleM + 3, "逐车管压必须更远（尾车滞后），实际 " + perCarM + " vs " + singleM);
		assertTrue(perCarM < singleM * 1.15, "但不能夸张到离谱，实际比值 " + (perCarM / singleM));
	}

	/** 单节车 / 没设逐车锚 ⇒ 逐位走单车等效模型（零回归）。 */
	@Test
	public void aSingleCarConsistKeepsTheSingleStateModel() {
		final ConsistType type = type(BAR_JSON, "bar");
		final ThreeHandleDriveController controller = controller();
		assertFalse(controller.isPerCarBrakePipe(), "默认不是逐车");
		controller.setBrakeCars(java.util.List.of(new org.mtr.core.mmtr.brake.BrakeCar(102_200, 138_400, true, null)));
		assertFalse(controller.isPerCarBrakePipe(), "只有一节车 ⇒ 不该开逐车管压");
		for (int i = 0; i < 400; i++) {
			controller.compute(ControlState.zero().setBrakeNotch(9), type, 0, DT_MS);
		}
		assertEquals(3.8, controller.getCylinderBar(), 1e-6, "单车缸压与 P1 时代一致");
		assertEquals(0, controller.getCylinderBar(3), 1e-9, "越界的逐车读数给 0");
	}

	// ---- ⑩ 逐车气路口径与管容积当量（片 3，notes/273）---------------------------------------------

	/**
	 * **每节车用自己的气路口径**（notes/273 片 3）：后三节车挂着"自己的慢气路"（1.0/s）时，
	 * 尾车比整列共用说话那节车口径（5.0/s）时更晚建满。
	 *
	 * <p>片 3 之前 `BrakeSystem.step` 的①（车头追目标）与②（往后传播）都读**编组级** spec ——
	 * 也就是"说话那节车"的口径。挂车自己的充排气速率与传播率从来不参与，所以"这列车的管子多快"
	 * 完全由机车决定（notes/270 §6 自认）。</p>
	 */
	@Test
	public void trailingCarsUseTheirOwnAirPipeRates() {
		final PneumaticBrakeSpec fast = air();
		final PneumaticBrakeSpec slow = airOf(SLOW_AIR_JSON);
		assertEquals(5.0, fast.getPipePropagationPerSecond(), 1e-9);
		assertEquals(1.0, slow.getPipePropagationPerSecond(), 1e-9);

		final double fastSeconds = tailFullSeconds(brakeCarsOf(fast, 20), fast);
		final double slowSeconds = tailFullSeconds(brakeCarsOf(slow, 20), fast);
		System.out.println(String.format("[TEST] 尾车建满：编组口径 %.2f s / 挂车自己的慢气路 %.2f s", fastSeconds, slowSeconds));
		assertTrue(fastSeconds > 0, "基准情形必须建得起来");
		assertTrue(slowSeconds > fastSeconds + 0.5,
			"挂车自己的气路慢 ⇒ 尾车必须明显更晚，实际 " + fastSeconds + " s vs " + slowSeconds + " s");
	}

	/** **管容积当量按车长**（用户口径 2026-09-26）：同口径下 40 m 的车比 20 m 的更晚建满。 */
	@Test
	public void pipeVolumeEquivalentFollowsCarLength() {
		final PneumaticBrakeSpec fast = air();
		assertEquals(1.0, org.mtr.core.mmtr.brake.BrakeSystem.pipeVolumeScale(20), 1e-9, "基准车长 20 m ⇒ 不缩放（与片 3 之前逐位相同）");
		assertEquals(1.0, org.mtr.core.mmtr.brake.BrakeSystem.pipeVolumeScale(0), 1e-9, "车长未知 ⇒ 不缩放（老夹具/单节等效车底）");
		assertEquals(0.5, org.mtr.core.mmtr.brake.BrakeSystem.pipeVolumeScale(40), 1e-9, "40 m 车容积一倍 ⇒ 松弛率减半");

		final double shortSeconds = tailFullSeconds(brakeCarsOf(fast, 20), fast);
		final double longSeconds = tailFullSeconds(brakeCarsOf(fast, 40), fast);
		System.out.println(String.format("[TEST] 尾车建满：20 m 车 %.2f s / 40 m 车 %.2f s", shortSeconds, longSeconds));
		assertTrue(longSeconds > shortSeconds + 0.3,
			"长车要充的容积大 ⇒ 更晚，实际 " + shortSeconds + " s vs " + longSeconds + " s");
	}

	/** 随包配置：P1 客车**有自己的** bar 口径（用户 2026-09-26「P1 可以给」）。 */
	@Test
	public void theShippedCoachHasItsOwnBarSpec() throws java.io.IOException {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(java.nio.file.Files.readString(
			findConfig(), java.nio.charset.StandardCharsets.UTF_8));
		final ConsistType p1 = registry.get("p1_trailer");
		assertNotNull(p1, "随包配置里必须有 p1_trailer");
		assertNotNull(p1.getBrakes(), "客车必须有自己的 bar 口径，否则它会借说话那节车的");
		assertEquals(2.0, p1.getBrakes().getCylinderMaxBar(), 1e-9, "客车缸压上限 2.0 bar（用户口径一族）");
		assertEquals(5.2, p1.getBrakes().getChargedBar(), 1e-9, "列车管是全列同一根管子：充风稳定值 5.2");
		assertEquals(4.0, p1.getBrakes().getPipePropagationPerSecond(), 1e-9, "客车自己的传播松弛率（估算 4.0/s）");
		assertFalse(p1.getBrakes().isBlendingEnabled(), "拖车没有电制动（声明性的）");
		assertFalse(p1.canPull(), "仍然零牵引");
	}

	/** 从测试工作目录往上找 config-example（Gradle 的 cwd 是 engine 工程目录，别写死层数）。 */
	private static java.nio.file.Path findConfig() {
		final java.nio.file.Path start = java.nio.file.Paths.get("").toAbsolutePath();
		for (java.nio.file.Path directory = start; directory != null; directory = directory.getParent()) {
			final java.nio.file.Path direct = directory.resolve("config-example").resolve("consist-types.json");
			if (java.nio.file.Files.isRegularFile(direct)) {
				return direct;
			}
			final java.nio.file.Path nested = directory.resolve("mmtr").resolve("config-example").resolve("consist-types.json");
			if (java.nio.file.Files.isRegularFile(nested)) {
				return nested;
			}
		}
		throw new AssertionError("找不到 config-example/consist-types.json（起点 " + start + "）");
	}

	/** 一节机车（气压口径、20 m）+ 三节同口径/同车长的挂车：只变"挂车的口径与车长"这一个自变量。 */
	private static java.util.List<org.mtr.core.mmtr.brake.BrakeCar> brakeCarsOf(PneumaticBrakeSpec trailerSpec, double carLengthM) {
		final java.util.ArrayList<org.mtr.core.mmtr.brake.BrakeCar> cars = new java.util.ArrayList<>();
		cars.add(new org.mtr.core.mmtr.brake.BrakeCar(102_200, 138_400, true, air(), 20));
		for (int i = 0; i < 3; i++) {
			cars.add(new org.mtr.core.mmtr.brake.BrakeCar(43_700, 58_800, false, trailerSpec, carLengthM));
		}
		return cars;
	}

	/** 满常用（诉求 0.9 = 全常用档）把**尾车**缸压建到它自己上限所需秒数（50 ms 步长，最多 60 s；-1 = 没建到）。 */
	private static double tailFullSeconds(java.util.List<org.mtr.core.mmtr.brake.BrakeCar> cars, PneumaticBrakeSpec consistSpec) {
		final org.mtr.core.mmtr.brake.BrakeSystem system = new org.mtr.core.mmtr.brake.BrakeSystem(cars, consistSpec);
		final int last = cars.size() - 1;
		final PneumaticBrakeSpec lastSpec = cars.get(last).spec() == null ? consistSpec : cars.get(last).spec();
		final double target = lastSpec.getCylinderMaxBar();
		final org.mtr.core.mmtr.brake.BrakeCommand command = org.mtr.core.mmtr.brake.BrakeCommand.ofRatio(0.9, 0, false);
		for (int i = 1; i <= 1200; i++) {
			system.step(command, 0, 0, 0.05);
			if (system.getCylinderBar(last) >= target - 0.02) {
				return i * 0.05;
			}
		}
		return -1;
	}

	/** BR101 + {@code coaches} 节 p1 的等效车底（本用例自带的编组 JSON）。 */
	private static ConsistType consistOf(ConsistTypeRegistry registry, int coaches) {
		return compositionOf(registry, coaches).toConsistType("consist:test");
	}

	private static MmtrComposition compositionOf(ConsistTypeRegistry registry, int coaches) {
		final java.util.ArrayList<org.mtr.core.data.VehicleCar> cars = new java.util.ArrayList<>();
		cars.add(car("br101", true));
		for (int i = 0; i < coaches; i++) {
			cars.add(car("p1", false));
		}
		return MmtrComposition.fromVehicleCars(cars, registry, registry.get("loco"));
	}

	private static org.mtr.core.data.VehicleCar car(String vehicleId, boolean powered) {
		return new org.mtr.core.data.VehicleCar(vehicleId, 20, 5, 0, -20 / 3.0, 20 / 3.0, 0, 0, powered, "");
	}

	/** 满 8 档跑 {@code seconds} 秒后的**气制动力**（N）——整列一个管压那条路。 */
	private static double typeForceN(ThreeHandleDriveController controller, ConsistType type) {
		return type.getBrake().serviceForceNFromCylinderBar(controller.getCylinderBar(), 0);
	}

	/** 逐车管压下的实际气制动力（N）：逐车"锚 × f(缸压)"求和（与 {@link org.mtr.core.mmtr.brake.BrakeSystem} 同一口径）。 */
	private static double perCarForceN(ThreeHandleDriveController controller, ConsistType type, double[] anchors) {
		double forceN = 0;
		for (int i = 0; i < anchors.length; i++) {
			final double bar = controller.getCylinderBar(i);
			forceN += anchors[i] * Math.max(0, Math.min(1, (bar - type.getBrake().getCylinderSpringBar())
				/ (type.getBrake().getCylinderMaxBar() - type.getBrake().getCylinderSpringBar())));
		}
		return forceN;
	}

	/** 从 {@code startMps} 刹到停的距离（m）：50 ms 步长，力由控制器给。 */
	private static double stoppingDistanceM(ThreeHandleDriveController controller, ConsistType type, ControlState control, double startMps) {
		double speed = startMps;
		double distance = 0;
		final double dt = DT_MS / 1000.0;
		for (int i = 0; i < 40000 && speed > 0.05; i++) {
			final DriveOutput out = controller.compute(control, type, speed, DT_MS);
			final double next = Math.max(0, speed + out.getAccelerationMetersPerSecondSquared() * dt);
			distance += (speed + next) / 2 * dt;
			speed = next;
		}
		return distance;
	}

	/** 逐车管压测试自带的编组：一台 BR101（气压口径）+ 40 t 级客车（各自的制动重量）。 */
	private static final String CONSIST_JSON = "{"
		+ "\"carTypeIds\":{\"br101\":\"loco\",\"p1\":\"coach\"},"
		+ "\"consistTypes\":["
		+ "{\"id\":\"loco\",\"name\":\"loco\",\"controlMode\":\"THREE_HANDLE\",\"powerNotches\":97,\"brakeNotches\":11,"
		+ "\"maxSpeedKmh\":220,\"massKg\":84000,\"rotatingMassFactor\":1.16,"
		+ "\"maxTractiveEffortN\":300000,\"maxPowerW\":6400000,"
		+ "\"brakeWeightTonnes\":120,\"emergencyBrakeWeightTonnes\":168,"
		+ "\"resistanceAN\":1350,\"resistanceBN\":28,\"resistanceCN\":2.76,\"adhesionMuMax\":0.37,"
		+ "\"rheostaticBrakeForceN\":150000,\"rheostaticFadeKmh\":15,\"rheostaticMaxPowerW\":6400000,"
		+ "\"tractionLagMillis\":0,\"tractionRampNPerSecond\":0,"
		+ AIR_KEYS
		+ "\"brakeRatios\":\"0,0.05,0.12,0.20,0.3333,0.4667,0.60,0.7333,0.8667,1.00,1.00\"},"
		+ "{\"id\":\"coach\",\"name\":\"coach\",\"controlMode\":\"NOTCHED\",\"massKg\":40000,\"rotatingMassFactor\":1.06,"
		+ "\"maxTractiveEffortN\":0,\"maxPowerW\":0,\"brakeWeightTonnes\":56,\"emergencyBrakeWeightTonnes\":78,"
		+ "\"resistanceAN\":400,\"resistanceBN\":11,\"resistanceCN\":1.3}]}";

	// ---- ⑤ 电制动三段 ----------------------------------------------------------------------------

	@Test
	public void theElectricBrakeHasAConstantPowerCeiling() {
		final ThreeHandleSpec spec = type(BAR_JSON, "bar").getHandles();
		assertEquals(6_400_000, spec.getRheostaticMaxPowerW(), 1e-6);
		assertEquals(150_000 / 6_400_000.0, 1.0 / spec.rheostaticBreakpointMetersPerSecond(), 1e-9, "折点 = P/B_max");
		assertEquals(42.667, spec.rheostaticBreakpointMetersPerSecond(), 1e-2, "153.6 km/h");
		assertEquals(150_000, spec.rheostaticEffortN(153.6 / 3.6), 1, "折点及以下满力");
		assertEquals(115_200, spec.rheostaticEffortN(200 / 3.6), 200, "200 km/h ⇒ 6.4 MW / v ≈ 115 kN");
		assertEquals(50_000, spec.rheostaticEffortN(10 / 3.6), 500, "10 km/h 落在淡出带中点 ⇒ 一半力");
		assertEquals(0, spec.rheostaticEffortN(5 / 3.6), 1e-9, "淡出带下沿以下完全没有电制动");
		// 不限功率的旧口径：一路 150 kN
		assertEquals(0, type(LEGACY_JSON, "legacy").getHandles().getRheostaticMaxPowerW(), 1e-9);
		assertEquals(150_000, type(LEGACY_JSON, "legacy").getHandles().rheostaticEffortN(200 / 3.6), 1, "旧口径不做恒功率");
	}

	// ---- ⑥ 镜像与零回归 ---------------------------------------------------------------------------

	@Test
	public void theMirrorStringCarriesTheBarModel() {
		final ThreeHandleSpec original = type(BAR_JSON, "bar").getHandles();
		final ThreeHandleSpec decoded = ThreeHandleSpec.decode(original.encode());
		assertNotNull(decoded);
		final PneumaticBrakeSpec air = decoded.getBrakes();
		assertNotNull(air, "镜像串必须带上气压口径（否则客户端跑的是另一套气路）");
		assertEquals(5.2, air.getChargedBar(), 1e-9);
		assertEquals(3.8, air.getCylinderMaxBar(), 1e-9);
		assertEquals(0.20, air.getDistributorSensitivityBar(), 1e-6);
		assertEquals(2.5333, air.getDistributorRatio(), 1e-4);
		assertTrue(air.isPadFadeEnabled());
		assertEquals(0.39, air.getPadMu0(), 1e-9);
		for (int notch = 0; notch < air.getPositionCount(); notch++) {
			assertEquals(air().targetPipeBar(notch), air.targetPipeBar(notch), 1e-9, "级位表逐档往返");
		}
		assertEquals(6_400_000, decoded.getRheostaticMaxPowerW(), 1e-6);
	}

	/**
	 * **HUD 那三行的读数**（notes/266，用户 2026-09-25「在屏幕 UI 右上角添加制动压力以及制动力显示」）：
	 * 管压/缸压 = 归一化镜像读数 × 气压规格满量程，制动力 = 缸压折成的力（+ 电机出力的负侧）。
	 * 这条钉的是 HUD 与控制器**同一个数**（"表说在出力、车却不动"是本仓最恨的一类现场）。
	 */
	@Test
	public void theHudPressureAndForceReadingsFollowTheBarModel() {
		final ConsistType type = type(BAR_JSON, "bar");
		final ThreeHandleDriveController controller = controller();
		for (int i = 0; i < 200; i++) {
			controller.compute(ControlState.zero().setBrakeNotch(9), type, 0, DT_MS);
		}
		final PneumaticBrakeSpec air = type.getHandles().getBrakes();
		assertEquals(3.8, controller.getBrakeCylinderPressure() * air.getCylinderMaxBar(), 1e-9,
			"HUD 缸压(bar) = 归一化读数 × 缸压上限");
		assertEquals(3.5, controller.getPipePressure() * air.getChargedBar(), 1e-6,
			"HUD 管压(bar) = 归一化读数 × 充风稳定值");
		assertEquals(type.getBrake().getServiceForceN(),
			type.getBrake().serviceForceNFromCylinderBar(controller.getCylinderBar(), 0), 0.5,
			"HUD 制动力（气）= 满缸压折成的力 = UIC 锚");
	}

	@Test
	public void legacyStringsAndConfigsFallBackToTheOldModel() {
		// 旧镜像串（没有 PB 段）：解出来必须是 null，而不是"半套气压参数"
		final ThreeHandleSpec legacySpec = type(LEGACY_JSON, "legacy").getHandles();
		assertNull(legacySpec.getBrakes(), "没有 bar 键的车底 ⇒ 气压规格为 null（零回归）");
		assertNull(ThreeHandleSpec.decode(legacySpec.encode()).getBrakes(), "旧串解回来也不能凭空多出气压口径");

		// 旧模型的力：150 kN × 比例，与速度无关（闸片不衰减）
		final ConsistType legacyType = type(LEGACY_JSON, "legacy");
		assertEquals(150_000, legacyType.getBrake().getServiceForceN(), 1e-9);
		assertEquals(75_000, legacyType.getBrake().serviceForceN(0.5), 1e-9);
		assertEquals(75_000, legacyType.getBrake().serviceForceN(0.5, 200 / 3.6), 1e-9, "旧口径不随速衰减");

		// 旧模型的缸压：查 brakeRatios 表（8 档 = 1.00），管压/缸压仍是归一化的
		final ThreeHandleDriveController controller = controller();
		for (int i = 0; i < 200; i++) {
			controller.compute(ControlState.zero().setBrakeNotch(9), legacyType, 0, DT_MS);
		}
		assertEquals(1.0, controller.getBrakeCylinderPressure(), 1e-9, "旧口径 8 档缸压 1.00");
		assertEquals(0, controller.getCylinderBar(), 1e-9, "旧口径没有 bar 读数");
	}
}
