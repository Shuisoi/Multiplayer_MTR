package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 三手柄机车（定速巡航 + 双向油门 + 气制动）的规格与控制器用例。
 *
 * <p>规格真源：docs/01-设计/驾驶输入与控制模型.md §2–§5；用户口径逐条钉在这里，避免日后"手感变了"
 * 却分不清是配错了还是代码错了。全部纯 JVM，不启动 Minecraft。</p>
 */
public final class ThreeHandleDriveTests {

	private static final String JSON = "{"
		+ "  \"consistTypes\": ["
		+ "    {\"id\":\"br101\",\"name\":\"BR 101\",\"controlMode\":\"THREE_HANDLE\",\"powerNotches\":97,\"brakeNotches\":11,"
		+ "     \"maxSpeedKmh\":160,\"massKg\":82000,\"maxTractiveEffortN\":45100,\"maxPowerW\":1002222,"
		+ "     \"massKg\":82000,\"serviceBrakeForceN\":82000,\"emergencyBrakeForceN\":147600,"
		+ "     \"rheostaticBrakeForceN\":73800,\"rheostaticFadeKmh\":10,"
		// 牵引力增速控制**关掉**（0 = 一拍到位）：这一类用例钉的是"位置 → 力"的静态映射与制动/EB 的
		// 拍内行为，30 kN/s 的爬升会把第一拍的静态值盖掉。增速控制本身在 MmtrTractionRampTests 里单独钉。
		+ "     \"tractionRampNPerSecond\":0,"
		+ "     \"airPipeChargeRatePerSecond\":0.15,\"airPipeDischargeRatePerSecond\":0.5,"
		+ "     \"airBrakeApplyRatePerSecond\":0.35,\"airBrakeReleaseRatePerSecond\":0.25,"
		+ "     \"brakeRatios\":\"0,0.05,0.12,0.20,0.32,0.44,0.56,0.68,0.80,1.00,1.00\"}"
		+ "  ]"
		+ "}";

	private static final long DT_MS = 100;
	/** 10 km/h —— 电阻制动衰减的满力门槛。 */
	private static final double FADE_SPEED_MPS = 10 / 3.6;

	private static ConsistType br101() {
		return ConsistTypeRegistry.parse(JSON).get("br101");
	}

	private static ThreeHandleSpec spec() {
		final ThreeHandleSpec handles = br101().getHandles();
		assertNotNull(handles, "THREE_HANDLE 车底必须带操纵规格");
		return handles;
	}

	// ---- 规格：位置 → 力 ---------------------------------------------------------------------------

	@Test
	public void driveHandlePositionsFollowTheUserSpec() {
		final ThreeHandleSpec spec = spec();
		// 中央关闭 / 最小 / 5%…100%（正牵引）
		assertEquals(0, spec.tractionRatio(0), 1e-9, "0 = 关闭");
		assertEquals(0, spec.rheostaticRatio(0), 1e-9, "0 = 关闭（两侧都没有力）");
		assertEquals(0.02, spec.tractionRatio(1), 1e-9, "+1 = 最小（独立档，默认 2%）");
		assertEquals(0.05, spec.tractionRatio(2), 1e-9, "+2 = 5%");
		assertEquals(0.53, spec.tractionRatio(50), 1e-9, "+50 = 53%");
		assertEquals(1.00, spec.tractionRatio(97), 1e-9, "+97 = 100%");
		assertEquals(0, spec.tractionRatio(-97), 1e-9, "制动侧没有牵引");
		// 负侧完全镜像（电阻制动）
		assertEquals(0.02, spec.rheostaticRatio(-1), 1e-9, "-1 = 最小（电阻制动）");
		assertEquals(0.05, spec.rheostaticRatio(-2), 1e-9, "-2 = 5%");
		assertEquals(1.00, spec.rheostaticRatio(-97), 1e-9, "-97 = 100% 电阻制动");
		assertEquals(0, spec.rheostaticRatio(97), 1e-9, "牵引侧没有电阻制动");
		// 越界钳位与说人话的诊断
		assertEquals(ThreeHandleSpec.DRIVE_HANDLE_MAX, spec.clampDriveHandle(999));
		assertEquals(-ThreeHandleSpec.DRIVE_HANDLE_MAX, spec.clampDriveHandle(-999));
		assertEquals("牵引 100%", spec.describeDriveHandle(97));
		assertEquals("电阻制动 最小", spec.describeDriveHandle(-1));
		assertEquals("关闭", spec.describeDriveHandle(0));
	}

	@Test
	public void brakePositionsFollowTheTable() {
		final ThreeHandleSpec spec = spec();
		assertEquals(11, spec.getBrakePositionCount(), "运行/1A/1B/2…8/EB = 11 个位置");
		assertEquals(0.00, spec.brakeRatio(0), 1e-9, "运行 = 缓解");
		assertEquals(0.05, spec.brakeRatio(1), 1e-9, "1A");
		assertEquals(0.12, spec.brakeRatio(2), 1e-9, "1B");
		assertEquals(0.20, spec.brakeRatio(3), 1e-9, "2");
		assertEquals(1.00, spec.brakeRatio(9), 1e-9, "8 = 全常用制动");
		assertTrue(spec.isEmergencyPosition(10), "最后一位 = EB");
		assertFalse(spec.isEmergencyPosition(9), "8 不是紧急");
		// 1A/1B 是弱档：力必须严格递增，且 8 档 = 全常用
		for (int position = 0; position < spec.getBrakePositionCount() - 1; position++) {
			assertTrue(spec.brakeRatio(position) <= spec.brakeRatio(position + 1),
				"制动力必须随位置不减：position " + position);
		}
		assertEquals("1A", spec.brakePositionLabel(1));
		assertEquals("EB", spec.brakePositionLabel(10));
	}

	@Test
	public void cruiseClampsToTheFiveKmhGrid() {
		final ThreeHandleSpec spec = spec();
		assertEquals(0, spec.clampCruiseKmh(0));
		assertEquals(0, spec.clampCruiseKmh(2), "2 对齐到 0（并因此 = 关闭）");
		assertEquals(105, spec.clampCruiseKmh(103), "步长 5 km/h");
		assertEquals(160, spec.clampCruiseKmh(999), "上限 160");
		assertEquals(0, spec.clampCruiseKmh(-40), "负值钳到 0");
	}

	@Test
	public void specRoundTripsThroughTheMirrorString() {
		final ThreeHandleSpec original = spec();
		final ThreeHandleSpec decoded = ThreeHandleSpec.decode(original.encode());
		assertNotNull(decoded, "镜像字符串必须能解回来");
		assertEquals(original.getDriveMinRatio(), decoded.getDriveMinRatio(), 1e-9);
		assertEquals(original.getRheostaticBrakeForceN(), decoded.getRheostaticBrakeForceN(), 1e-9);
		assertEquals(original.getRheostaticFadeKmh(), decoded.getRheostaticFadeKmh(), 1e-9);
		assertEquals(original.getCruiseMaxKmh(), decoded.getCruiseMaxKmh());
		assertEquals(original.getCruiseStepKmh(), decoded.getCruiseStepKmh());
		assertEquals(original.getBrakePositionCount(), decoded.getBrakePositionCount());
		for (int position = 0; position < original.getBrakePositionCount(); position++) {
			assertEquals(original.brakeRatio(position), decoded.brakeRatio(position), 1e-9, "位置 " + position);
		}
		assertEquals(0.53, decoded.tractionRatio(50), 1e-9, "解出来的位置表必须还能算力");
		// 残缺/空字符串不能让客户端起不来
		assertNull(ThreeHandleSpec.decode(""));
		assertNull(ThreeHandleSpec.decode("NOT-A-SPEC"));
	}

	// ---- 控制器：AFB 与动力手柄的"相互制约"（notes/253）-----------------------------------------

	/** 用户口径（2026-09-23）：**动力手柄决定当前输出的力，AFB 只按速度削减它**。 */
	private static final String CAP_JSON = "{"
		+ "  \"consistTypes\": ["
		+ "    {\"id\":\"br101c\",\"name\":\"BR 101 (cap)\",\"controlMode\":\"THREE_HANDLE\",\"powerNotches\":97,\"brakeNotches\":11,"
		+ "     \"maxSpeedKmh\":160,\"massKg\":82000,\"rotatingMassFactor\":1.16,\"maxTractiveEffortN\":300000,\"maxPowerW\":6400000,"
		+ "     \"serviceBrakeForceN\":150000,\"emergencyBrakeForceN\":210000,\"rheostaticBrakeForceN\":150000,\"rheostaticFadeKmh\":15,"
		+ "     \"cruiseMaxKmh\":160,\"afbGainPerMps\":1.0,\"afbBrakeDecelPerMps\":0.5,\"afbBrakeThresholdMps\":0.05,"
		+ "     \"afbUsesHandleAsCap\":true,\"tractionLagMillis\":0,\"tractionRampNPerSecond\":0,"
		+ "     \"airPipeChargeRatePerSecond\":0.15,\"airPipeDischargeRatePerSecond\":0.5,"
		+ "     \"airBrakeApplyRatePerSecond\":0.35,\"airBrakeReleaseRatePerSecond\":0.25,"
		+ "     \"brakeRatios\":\"0,0.05,0.12,0.20,0.3333,0.4667,0.60,0.7333,0.8667,1.00,1.00\"}"
		+ "  ]"
		+ "}";

	private static ConsistType capType() {
		return ConsistTypeRegistry.parse(CAP_JSON).get("br101c");
	}

	/**
	 * **手柄是上限**：手柄 50% 时，即使 AFB 想要 100%，实际也只有手柄那一份（用户 2026-09-23 现场：
	 * "油门设置在 50% 时也是 300 kN" —— 那时配置是 `afbUsesHandleAsCap=false`，AFB 直接盖掉了手柄）。
	 */
	@Test
	public void theHandleCapsTheAfbDemandWhenTheSpecSaysSo() {
		final ConsistType type = capType();
		final ThreeHandleSpec spec = type.getHandles();
		assertTrue(spec.isAfbUsesHandleAsCap(), "这份规格要求手柄当上限");
		final int halfHandle = (int) Math.round(spec.getDrivePercentSteps() / 2.0);
		final double halfHandleRatio = spec.tractionRatio(spec.clampDriveHandle(halfHandle));
		assertTrue(halfHandleRatio > 0.4 && halfHandleRatio < 0.6, "半手柄约 50%，实际 " + halfHandleRatio);

		// AFB 要 100%（定速 100、车还停着），手柄一半 ⇒ 只有一半的力
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		controller.compute(ControlState.zero().setDriveHandle(halfHandle).setCruiseSpeedKmh(100), type, 0, 100);
		assertEquals(halfHandleRatio, controller.getLastTractionRatio(), 0.02, "半手柄 + AFB 要满 ⇒ 实际只能是半手柄那份");

		// 手柄关闭 + AFB 挂着 ⇒ 牵引 0：**AFB 只能削不能加**，上车手柄 0 时车不许自己往前开
		// （用户 2026-09-23：「AFB 只能限制功率，不能提升功率，为什么现在一上车手柄为 0 就会直接往前开」）
		final ThreeHandleDriveController closed = new ThreeHandleDriveController();
		closed.compute(ControlState.zero().setDriveHandle(0).setCruiseSpeedKmh(100), type, 0, 100);
		assertEquals(0, closed.getLastTractionRatio(), 1e-9, "手柄在关闭位 ⇒ 牵引 0（AFB 不提升功率）");

		// 手柄推满、接近设定速度（误差 0.3 m/s）⇒ AFB 把手柄那份**削**到 0.3
		final ThreeHandleDriveController capped = new ThreeHandleDriveController();
		capped.compute(ControlState.zero().setDriveHandle(spec.clampDriveHandle(spec.getDrivePercentSteps())).setCruiseSpeedKmh(100), type, 100 / 3.6 - 0.3, 100);
		assertEquals(0.3, capped.getLastTractionRatio(), 0.05, "AFB 必须能把满手柄削下来");
	}

	// ---- 控制器：油门手柄 ---------------------------------------------------------------------------
	@Test
	public void tractionSideAcceleratesAndBrakingSideDecelerates() {
		final ConsistType type = br101();
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();

		final DriveOutput traction = controller.compute(ControlState.zero().setDriveHandle(97), type, 0, DT_MS);
		assertEquals(0.55, traction.getAccelerationMetersPerSecondSquared(), 1e-9, "满油门 = 车底牵引加速度");
		assertFalse(traction.isBrakeLamp());

		final DriveOutput minimum = controller.compute(ControlState.zero().setDriveHandle(1), type, 0, DT_MS);
		assertEquals(0.55 * 0.02, minimum.getAccelerationMetersPerSecondSquared(), 1e-9, "最小档 = 最小比例");

		// 电阻制动在衰减门槛以上给满力
		final DriveOutput electric = controller.compute(ControlState.zero().setDriveHandle(-97), type, FADE_SPEED_MPS, DT_MS);
		assertEquals(-0.9, electric.getAccelerationMetersPerSecondSquared(), 1e-9, "满电阻制动 = 0.9 m/s²");
		assertTrue(electric.isBrakeLamp(), "电阻制动要亮制动灯");
	}

	@Test
	public void rheostaticBrakeFadesOutAtLowSpeed() {
		final ConsistType type = br101();
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		final ControlState fullElectricBrake = ControlState.zero().setDriveHandle(-97);

		assertEquals(0, controller.compute(fullElectricBrake, type, 0, DT_MS).getAccelerationMetersPerSecondSquared(), 1e-9,
			"静止时电阻制动为 0（真车行为：想停住必须用气制动）");
		// 衰减带 = [fade/2, fade] = [5, 10] km/h
		assertEquals(0, controller.compute(fullElectricBrake, type, 5 / 3.6, DT_MS).getAccelerationMetersPerSecondSquared(), 1e-9,
			"衰减带下沿以下完全失效");
		assertEquals(-0.9 * 0.5, controller.compute(fullElectricBrake, type, 7.5 / 3.6, DT_MS).getAccelerationMetersPerSecondSquared(), 1e-9,
			"衰减带中点给一半力");
		assertEquals(-0.9, controller.compute(fullElectricBrake, type, FADE_SPEED_MPS, DT_MS).getAccelerationMetersPerSecondSquared(), 1e-9,
			"到门槛及以上满力");
	}

	// ---- 控制器：制动手柄 ---------------------------------------------------------------------------

	@Test
	public void pneumaticBrakeBuildsAndReleasesAtTheConfiguredRates() {
		final ConsistType type = br101();
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		final ControlState fullService = ControlState.zero().setBrakeNotch(9);

		// 0.35/s 的建压速率：1 s 后约 0.35，3 s 后满
		controller.compute(fullService, type, 20, 1000);
		assertEquals(0.35, controller.getBrakeCylinderPressure(), 1e-9, "建压按 airBrakeApplyRatePerSecond");
		for (int i = 0; i < 20; i++) {
			controller.compute(fullService, type, 20, DT_MS);
		}
		assertEquals(1.0, controller.getBrakeCylinderPressure(), 1e-9, "3 s 内建满");
		assertEquals(-1.0, controller.compute(fullService, type, 20, DT_MS).getAccelerationMetersPerSecondSquared(), 1e-9,
			"满缸压 = serviceBrakeDecelerationMps2");

		// 缓解：0.25/s 排空缸压；管压 0.15/s 回充（从满制动时的 0 起，约 6.7 s 充满）
		final ControlState running = ControlState.zero().setBrakeNotch(0);
		for (int i = 0; i < 45; i++) {
			controller.compute(running, type, 20, DT_MS);
		}
		assertEquals(0.0, controller.getBrakeCylinderPressure(), 1e-9, "缓解到零");
		assertTrue(controller.getPipePressure() > 0.6, "管压正在回充，实际 " + controller.getPipePressure());
		for (int i = 0; i < 100; i++) {
			controller.compute(running, type, 20, DT_MS);
		}
		assertEquals(1.0, controller.getPipePressure(), 1e-9, "最终充满");
	}

	@Test
	public void weakPositionsBrakeFarLessThanEight() {
		final ConsistType type = br101();
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		final StateAfter weak = settle(controller, type, ControlState.zero().setBrakeNotch(1));
		final StateAfter strong = settle(new ThreeHandleDriveController(), type, ControlState.zero().setBrakeNotch(9));
		assertEquals(0.05, weak.cylinder, 1e-9, "1A = 缸压 0.05");
		assertEquals(1.0, strong.cylinder, 1e-9, "8 = 缸压 1.0");
		assertTrue(weak.acceleration > strong.acceleration, "弱档减速必须明显小于全常用");
	}

	@Test
	public void emergencyPositionOverridesTractionAndUsesTheEmergencyRate() {
		final ConsistType type = br101();
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		// 油门推满 + 制动到 EB：紧急必须压住牵引
		final DriveOutput out = controller.compute(ControlState.zero().setDriveHandle(97).setBrakeNotch(10), type, 20, DT_MS);
		assertTrue(out.isEmergencyBrake(), "EB 位置要置紧急标志");
		assertEquals(-1.8, out.getAccelerationMetersPerSecondSquared(), 1e-9, "紧急减速度");
	}

	@Test
	public void brakeDemandBlocksTractionFromTheVeryFirstTick() {
		final ConsistType type = br101();
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		// 刚拉到 8 档的那一拍缸压还是 0：若按缸压判优先权，牵引会偷偷生效一小会儿（真车不会）
		final DriveOutput out = controller.compute(ControlState.zero().setDriveHandle(97).setBrakeNotch(9), type, 20, DT_MS);
		assertTrue(out.getAccelerationMetersPerSecondSquared() <= 0, "有制动需求时不得出现牵引，实际 " + out.getAccelerationMetersPerSecondSquared());
	}

	// ---- 控制器：定速巡航（AFB） -------------------------------------------------------------------

	/**
	 * **AFB 只能削、不能加**（用户 2026-09-23 最终口径）：手柄 5% ⇒ 出力最多 5%，哪怕 AFB 想要 100%。
	 *
	 * <p>`afbUsesHandleAsCap=false`（历史的"AFB 自己加牵引"模式）现在需要**显式配**才生效；
	 * 缺省是 true（见 {@link org.mtr.core.mmtr.ThreeHandleSpec#DEFAULT}）。</p>
	 */
	@Test
	public void afbOnlyCutsNeverAdds() {
		// 静态断言（一拍就要看到稳态比例）⇒ 用增速关闭版的世界配置；开着 30 kN/s 时第一拍只爬
		// 3 kN / 200 kN = 1.5%，量的是"爬升"而不是"AFB 有没有加力"（notes/265）。
		final ConsistType type = ConsistTypeRegistry.parse(WORLD_INSTANT_JSON).get("br101");
		final ThreeHandleSpec spec = type.getHandles();
		assertTrue(spec.isAfbUsesHandleAsCap(), "缺省必须是「手柄是上限」");
		final ThreeHandleDriveController small = new ThreeHandleDriveController();
		small.compute(ControlState.zero().setDriveHandle(2).setCruiseSpeedKmh(100), type, 0, DT_MS);
		assertEquals(spec.tractionRatio(2), small.getLastTractionRatio(), 1e-9,
			"手柄 5% ⇒ AFB 不许加力，实际 " + small.getLastTractionRatio());
		// 手柄全推 + 速度贴着设定值 ⇒ 这时才削（"削减"该出现的地方）
		final ThreeHandleDriveController nearTarget = new ThreeHandleDriveController();
		nearTarget.compute(ControlState.zero().setDriveHandle(97).setCruiseSpeedKmh(100), type, 100 / 3.6 - 0.2, DT_MS);
		assertTrue(nearTarget.getLastTractionRatio() < 0.3,
			"接近设定速度要削，实际 " + nearTarget.getLastTractionRatio());
	}

	@Test
	public void afbApproachesTheSetSpeedWithinTheThrottleCap() {
		final ConsistType type = br101();
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		final ControlState state = ControlState.zero().setDriveHandle(97).setCruiseSpeedKmh(100);

		final DriveOutput out = controller.compute(state, type, 0, DT_MS);
		assertTrue(controller.isAfbActive(), "定速 > 0 ⇒ AFB 在岗");
		assertTrue(out.getAccelerationMetersPerSecondSquared() > 0, "低于设定速度要牵引");
		assertTrue(controller.getLastTractionRatio() <= 1.0 + 1e-9, "牵引比不得越界");

		// 手柄 5% + 速度贴着设定值 ⇒ 削到 5% 以下（这时手柄"要得少"仍然是有效表达）
		final ThreeHandleDriveController capped = new ThreeHandleDriveController();
		capped.compute(ControlState.zero().setDriveHandle(2).setCruiseSpeedKmh(100), type, 100 / 3.6 - 0.02, DT_MS);
		assertTrue(capped.getLastTractionRatio() < 0.05, "接近设定值时连 5% 也要削，实际 " + capped.getLastTractionRatio());
	}

	/**
	 * **AFB 只能削减，不能提升功率**（用户 2026-09-23 最终口径）：
	 * 「AFB 只能限制功率，不能提升功率，为什么现在一上车手柄为 0 就会直接往前开」。
	 *
	 * <p>⇒ 手柄在关闭位 ⇒ 牵引比 0，车不许自己走。要起步必须司机推手柄。</p>
	 */
	@Test
	public void afbNeverPullsTheTrainWithTheThrottleClosed() {
		final ConsistType type = ConsistTypeRegistry.parse(WORLD_JSON).get("br101");
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		final ControlState state = ControlState.zero().setCruiseSpeedKmh(100); // 油门关闭、气制动运行位、定速 100
		double speed = 0;
		final int dt = 50;
		for (int i = 0; i < 200 * 1000 / dt; i++) {
			speed = ConsistDynamics.step(speed, controller.compute(state, type, speed, dt), type, dt);
		}
		System.out.println(String.format("[AFB5] 手柄关闭、定速 100：200 s 后 v=%.2f km/h（必须 ≈0）", speed * 3.6));
		assertEquals(0, speed * 3.6, 1e-6, "手柄关闭时 AFB 不许把车拉起来，实际 " + speed * 3.6);
		assertEquals(0, controller.getLastTractionRatio(), 1e-9, "手柄关闭 ⇒ 牵引比 0");
	}

	@Test
	public void afbBrakesElectricallyWhenAboveTheSetSpeed() {
		final ConsistType type = br101();
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		final double target = 100 / 3.6;
		final DriveOutput out = controller.compute(ControlState.zero().setDriveHandle(97).setCruiseSpeedKmh(100), type, target + 3, DT_MS);
		assertTrue(controller.getLastRheostaticRatio() > 0, "超过设定速度要用电阻制动");
		assertTrue(out.getAccelerationMetersPerSecondSquared() < 0, "必须减速");
		// 带宽内不动手（免得在设定速度附近反复点刹）；门限默认 0.05 m/s，这里取带宽正中
		final ThreeHandleDriveController inBand = new ThreeHandleDriveController();
		inBand.compute(ControlState.zero().setDriveHandle(97).setCruiseSpeedKmh(100), type, target + 0.02, DT_MS);
		assertEquals(0, inBand.getLastRheostaticRatio(), 1e-9, "带宽内不施加电阻制动");
		assertEquals(0, inBand.getLastTractionRatio(), 1e-9, "带宽内惰行");
	}

	/**
	 * 司机拉气制动：牵引**立刻**被压住（牵引联锁，按手柄诉求判，不等缸压），但 AFB 不再"退出" ——
	 * 用户 2026-09-23 的口径是**与气制动手柄耦合**：AFB 继续按速度决定要不要补气，只是补不上去而已。
	 */
	@Test
	public void pneumaticBrakeBlocksTractionWhileAfbStaysCoupled() {
		final ConsistType type = br101();
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		final ControlState state = ControlState.zero().setDriveHandle(97).setCruiseSpeedKmh(100).setBrakeNotch(1);
		final DriveOutput out = controller.compute(state, type, 0, DT_MS);
		assertTrue(controller.isAfbActive(), "定速还挂着 ⇒ AFB 在岗（耦合），只是不出牵引");
		assertEquals(0, controller.getLastTractionRatio(), 1e-9, "有制动需求时不得牵引");
		assertTrue(out.getAccelerationMetersPerSecondSquared() <= 0);
		// 建压之后：缸压 = 司机 1A 的目标（0.05）；此时速度为 0、低于设定值，AFB 没有补气诉求 ⇒ 不叠加
		for (int i = 0; i < 100; i++) {
			controller.compute(state, type, 0, DT_MS);
		}
		assertEquals(0.05, controller.getBrakeCylinderPressure(), 1e-9, "司机的手柄照旧管着自己那一份");
	}

	@Test
	public void afbIsInactiveWhenCruiseIsClosed() {
		final ConsistType type = br101();
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		controller.compute(ControlState.zero().setDriveHandle(97), type, 0, DT_MS);
		assertFalse(controller.isAfbActive(), "定速 0 = 关闭");
		assertEquals(1.0, controller.getLastTractionRatio(), 1e-9, "此时手柄直接给牵引");
	}

	// ---- 整段运行（与 Vehicle 同一条积分路径） ------------------------------------------------------

	@Test
	public void aFullRunAcceleratesHoldsTheCruiseAndStopsOnThePneumaticBrake() {
		final ConsistType type = br101();
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		final ControlState cruising = ControlState.zero().setDriveHandle(97).setCruiseSpeedKmh(100);

		double speed = 0;
		for (int i = 0; i < 1200; i++) { // 120 s
			speed = ConsistDynamics.step(speed, controller.compute(cruising, type, speed, DT_MS), type, DT_MS);
		}
		assertEquals(100 / 3.6, speed, 0.5, "AFB 应当保速在 100 km/h 附近，实际 " + speed * 3.6 + " km/h");

		// 拉到 8 档停车：必须在有限时间内停住，且速度永不为负
		double stoppingTimeSeconds = 0;
		for (int i = 0; i < 6000 && speed > 0.01; i++) {
			speed = ConsistDynamics.step(speed, controller.compute(ControlState.zero().setBrakeNotch(9), type, speed, DT_MS), type, DT_MS);
			stoppingTimeSeconds += DT_MS / 1000.0;
			assertTrue(speed >= 0, "速度不得为负");
		}
		assertTrue(speed <= 0.01, "气制动必须能把车停住，实际 " + speed);
		assertTrue(stoppingTimeSeconds < 60, "停车时间应当有限，实际 " + stoppingTimeSeconds + " s");
	}

	@Test
	public void emergencyStopsFasterThanFullServiceFromTheSameSpeed() {
		final ConsistType type = br101();
		final double start = 100 / 3.6;
		assertTrue(timeToStop(type, ControlState.zero().setBrakeNotch(10), start) < timeToStop(type, ControlState.zero().setBrakeNotch(9), start),
			"EB 必须比 8 档停得快");
	}

	@Test
	public void rheostaticBrakeAloneCannotFinishTheStop() {
		final ConsistType type = br101();
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		double speed = 100 / 3.6;
		for (int i = 0; i < 3000 && speed > 1.2; i++) {
			speed = ConsistDynamics.step(speed, controller.compute(ControlState.zero().setDriveHandle(-97), type, speed, DT_MS), type, DT_MS);
		}
		// 衰减带下沿（5 km/h）以下电阻制动整段失效 ⇒ 只靠电阻制动停不下来。
		// 这正是"两根手柄要配合"的设计意图：最后几 km/h 必须用气制动。
		assertTrue(speed > 1.0, "只靠电阻制动不该停稳，实际 " + speed + " m/s");
		assertTrue(speed < 5 / 3.6 + 0.02, "但应当减速到衰减下沿附近，实际 " + speed + " m/s");
	}

	// ---- 助手 -------------------------------------------------------------------------------------

	/**
	 * 世界里的 BR101 配置（力模型 + 阻力 + AFB 三个旋钮）下的**定速保速**：
	 * 从 0 起步拉到设定速度，必须**不超调**并稳住（现场反馈："接近设定速度后停不住、一直往上冲"）。
	 */
	@Test
	public void afbHoldsTheSetSpeedUnderTheWorldTuning() {
		final ConsistType type = ConsistTypeRegistry.parse(WORLD_JSON).get("br101");
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		final ControlState state = ControlState.zero().setDriveHandle(97).setCruiseSpeedKmh(100);
		double speed = 0;
		double peak = 0;
		final int dt = 50;
		for (int i = 0; i < 120 * 1000 / dt; i++) {
			speed = ConsistDynamics.step(speed, controller.compute(state, type, speed, dt), type, dt);
			peak = Math.max(peak, speed);
			if (i % 2000 == 0) {
				System.out.println(String.format("[AFB] t=%5.1fs v=%6.2f km/h ratio=%.3f rheo=%.3f",
					i * dt / 1000.0, speed * 3.6, controller.getLastTractionRatio(), controller.getLastRheostaticRatio()));
			}
		}
		System.out.println(String.format("[AFB] 120 s 后 v=%.2f km/h 峰值=%.2f km/h", speed * 3.6, peak * 3.6));
		assertTrue(peak * 3.6 <= 100.5, "定速不得冲过设定值 0.5 km/h 以上，峰值 " + peak * 3.6 + " km/h");
		assertEquals(100, speed * 3.6, 0.8, "定速应当保在 100 km/h 附近，实际 " + speed * 3.6 + " km/h");
	}

	/** 高出设定速度时必须真的收得住（原来电阻制动只在超出 0.72 km/h 才动、增益还和牵引一样软）。 */
	@Test
	public void afbBringsAnOverspeedBackDownToTheSetSpeed() {
		final ConsistType type = ConsistTypeRegistry.parse(WORLD_JSON).get("br101");
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		final ControlState state = ControlState.zero().setDriveHandle(97).setCruiseSpeedKmh(100);
		double speed = 120 / 3.6;
		final int dt = 50;
		double lowest = speed;
		for (int i = 0; i < 60 * 1000 / dt; i++) {
			speed = ConsistDynamics.step(speed, controller.compute(state, type, speed, dt), type, dt);
			lowest = Math.min(lowest, speed);
		}
		System.out.println(String.format("[AFB3] 从 120 km/h 起，60 s 后 v=%.2f km/h 最低=%.2f km/h", speed * 3.6, lowest * 3.6));
		assertEquals(100, speed * 3.6, 1.0, "60 s 内必须回到设定速度附近，实际 " + speed * 3.6);
		assertTrue(lowest * 3.6 > 96, "回落不得冲过头（低于 96 km/h），最低 " + lowest * 3.6);
	}

	/**
	 * 现场反馈诊断（根因）：**拉过气制动再回"运行"**的那几秒。
	 *
	 * <p>缸压要按 {@code airBrakeReleaseRatePerSecond} 慢慢排空（满缸压 4 s）。修前那 4 秒里
	 * "闸还满着、牵引已经满上"，而且**残余制动力被整段丢掉** —— 实测 5 s 内 50 → 72.7 km/h，
	 * AFB 还因为缸压没排空而让位，于是冲到设定速度以上才收得住。修后：松闸那几秒**在减速**。</p>
	 */
	@Test
	public void releasingTheBrakeDoesNotHandBackFullTraction() {
		final ConsistType type = ConsistTypeRegistry.parse(WORLD_JSON).get("br101");
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		final int dt = 50;
		double speed = 60 / 3.6;
		for (int i = 0; i < 4 * 1000 / dt; i++) {
			speed = ConsistDynamics.step(speed, controller.compute(ControlState.zero().setBrakeNotch(9), type, speed, dt), type, dt);
		}
		assertEquals(1.0, controller.getBrakeCylinderPressure(), 1e-9, "8 档 4 s 后缸压应当建满");

		final ControlState released = ControlState.zero().setDriveHandle(97).setCruiseSpeedKmh(100);
		final double speedAtRelease = speed;
		double speedAfterFiveSeconds = speed;
		for (int i = 0; i < 30 * 1000 / dt; i++) {
			final DriveOutput out = controller.compute(released, type, speed, dt);
			speed = ConsistDynamics.step(speed, out, type, dt);
			if (controller.getBrakeCylinderPressure() > 0.01) {
				assertEquals(0, controller.getLastTractionRatio(), 1e-9, "闸没松完不许出牵引（tick " + i + "）");
				assertTrue(out.getAccelerationMetersPerSecondSquared() <= 0, "闸没松完必须还在减速，tick " + i);
			}
			if (i == 5 * 1000 / dt - 1) {
				speedAfterFiveSeconds = speed;
			}
		}
		System.out.println(String.format("[AFB2] 松闸时 %.2f km/h → 5 s 后 %.2f km/h → 30 s 后 %.2f km/h",
			speedAtRelease * 3.6, speedAfterFiveSeconds * 3.6, speed * 3.6));
		assertTrue(speedAfterFiveSeconds <= speedAtRelease + 0.05,
			"松闸后的 5 s 里不许加速（修前 +22.5 km/h），实际 " + (speedAfterFiveSeconds - speedAtRelease) * 3.6 + " km/h");
	}

	/**
	 * 现实 AFB 的"Fahr- **und Brems**steuerung"：定速低于当前速度、而且**电阻制动一个人压不住**时，
	 * AFB 必须自己**补气制动**（用户 2026-09-23 追加的口径）。
	 */
	@Test
	public void afbAddsPneumaticBrakeWhenTheElectricBrakeCannotHold() {
		final ConsistType type = ConsistTypeRegistry.parse(WORLD_JSON).get("br101");
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		final ControlState state = ControlState.zero().setDriveHandle(97).setCruiseSpeedKmh(20);
		double speed = 60 / 3.6;
		double maxCylinder = 0;
		final int dt = 50;
		for (int i = 0; i < 90 * 1000 / dt; i++) {
			speed = ConsistDynamics.step(speed, controller.compute(state, type, speed, dt), type, dt);
			maxCylinder = Math.max(maxCylinder, controller.getBrakeCylinderPressure());
		}
		System.out.println(String.format("[AFB4] 60 → 定速 20 km/h：90 s 后 v=%.2f km/h 最大缸压=%.3f", speed * 3.6, maxCylinder));
		assertTrue(maxCylinder > 0.5, "电阻制动压不住时必须补气，最大缸压 " + maxCylinder);
		assertEquals(20, speed * 3.6, 2.0, "补气之后应当稳在设定速度附近，实际 " + speed * 3.6 + " km/h");
	}

	/** 补气与气制动手柄**耦合**：司机要得多时听司机的（AFB 不许把缸压压下来），司机要得少时 AFB 往上抬。 */
	@Test
	public void afbPneumaticAssistIsCoupledWithTheBrakeHandle() {
		final ConsistType type = ConsistTypeRegistry.parse(WORLD_JSON).get("br101");
		// ① 司机 6 档（0.56）而 AFB 的缺口为 0（电阻制动一个人就够）⇒ 缸压就是司机的 0.56：一分别加、也一分不减
		final ThreeHandleDriveController driverHeavier = new ThreeHandleDriveController();
		for (int i = 0; i < 200; i++) {
			driverHeavier.compute(ControlState.zero().setBrakeNotch(6).setCruiseSpeedKmh(55), type, 60 / 3.6, 50);
		}
		assertEquals(0.56, driverHeavier.getBrakeCylinderPressure(), 1e-9, "司机要得多 ⇒ 听司机的");
		// ② 司机只拉 1A（0.05），AFB 的缺口比它大 ⇒ 缸压被抬到 AFB 要的那一档
		final ThreeHandleDriveController afbHeavier = new ThreeHandleDriveController();
		for (int i = 0; i < 200; i++) {
			afbHeavier.compute(ControlState.zero().setBrakeNotch(1).setCruiseSpeedKmh(20), type, 60 / 3.6, 50);
		}
		assertTrue(afbHeavier.getBrakeCylinderPressure() > 0.05,
			"司机要得少 ⇒ AFB 把它抬上去，实际 " + afbHeavier.getBrakeCylinderPressure());
	}

	/**
	 * **驱动延迟 τ**（规格 §2）：杆位 → 轮周力是一阶惯性（电气 50–150 ms），但**切除是快的**。
	 *
	 * <p>不对称是关键：两边都加 τ 时 AFB 的"牵引↔电阻制动"切换多一段相位滞后，实测把保速回路
	 * 推成了极限环（定速 20 掉到 14、定速 100 掉到 91）。</p>
	 *
	 * <p><b>这条用例显式关掉牵引力增速控制</b>（notes/265）：世界配置里增速是 30 kN/s，而
	 * 300 kN ÷ 30 kN/s = 10 s ≫ τ = 100 ms —— 增速会把 τ 整个盖住，于是 τ 本身就没法在这里验了。
	 * 开着增速的整套行为（含"回杆也是 30 kN/s"）在 {@code MmtrTractionRampTests}。</p>
	 */
	@Test
	public void theDriveLagRampsTractionUpButCutsItImmediately() {
		final ConsistType type = ConsistTypeRegistry.parse(WORLD_INSTANT_JSON).get("br101");
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		final ControlState full = ControlState.zero().setDriveHandle(97);
		controller.compute(full, type, 0, 50);
		assertEquals(0.5, controller.getLastTractionRatio(), 0.02, "τ=100 ms、dt=50 ms ⇒ 第一拍约到一半");
		for (int i = 0; i < 20; i++) {
			controller.compute(full, type, 0, 50);
		}
		assertEquals(1.0, controller.getLastTractionRatio(), 1e-3, "1 s 后基本到位");
		controller.compute(ControlState.zero(), type, 0, 50);
		assertEquals(0, controller.getLastTractionRatio(), 1e-9,
			"增速关掉时切牵引第一拍即 0（保护动作；增速开着时按 30 kN/s 回落，见 MmtrTractionRampTests）");
	}

	/** 往车底 JSON 的第一条里补一个键（用例只想钉某一条律时，把另一条显式关掉）。 */
	private static String withKey(String json, String key, String rawValue) {
		final int at = json.indexOf("\"id\"");
		return at < 0 ? json : json.substring(0, at) + "\"" + key + "\":" + rawValue + "," + json.substring(at);
	}

	private static final String WORLD_JSON = "{"
		+ "  \"consistTypes\": ["
		+ "    {\"id\":\"br101\",\"controlMode\":\"THREE_HANDLE\",\"powerNotches\":97,\"brakeNotches\":11,"
		+ "     \"maxSpeedKmh\":160,\"massKg\":82000,\"rotatingMassFactor\":1.06,"
		+ "     \"maxTractiveEffortN\":200000,\"maxPowerW\":1900000,"
		+ "     \"serviceBrakeForceN\":90000,\"emergencyBrakeForceN\":150000,"
		+ "     \"resistanceAN\":1500,\"resistanceBN\":20,\"resistanceCN\":3.0,"
		+ "     \"rheostaticBrakeForceN\":70000,\"rheostaticFadeKmh\":10,"
		+ "     \"airPipeChargeRatePerSecond\":0.15,\"airPipeDischargeRatePerSecond\":0.5,"
		+ "     \"airBrakeApplyRatePerSecond\":0.35,\"airBrakeReleaseRatePerSecond\":0.25,"
		+ "     \"afbGainPerMps\":1.0,\"afbBrakeDecelPerMps\":0.5,\"afbBrakeThresholdMps\":0.05,\"afbUsesHandleAsCap\":true,"
		+ "     \"brakeRatios\":\"0,0.05,0.12,0.20,0.32,0.44,0.56,0.68,0.80,1.00,1.00\"}"
		+ "  ]"
		+ "}";

	/** 世界配置的**增速关闭**版：静态断言（一拍就要稳态比例）与 τ 用例用它，免得 30 kN/s 盖住被测的那条律。 */
	private static final String WORLD_INSTANT_JSON = withKey(WORLD_JSON, "tractionRampNPerSecond", "0");

	private record StateAfter(double cylinder, double acceleration) {
	}

	private static StateAfter settle(ThreeHandleDriveController controller, ConsistType type, ControlState control) {
		DriveOutput out = DriveOutput.coast();
		for (int i = 0; i < 100; i++) {
			out = controller.compute(control, type, 20, DT_MS);
		}
		return new StateAfter(controller.getBrakeCylinderPressure(), out.getAccelerationMetersPerSecondSquared());
	}

	private static double timeToStop(ConsistType type, ControlState control, double startSpeed) {
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		double speed = startSpeed;
		double seconds = 0;
		for (int i = 0; i < 60000 && speed > 0.01; i++) {
			speed = ConsistDynamics.step(speed, controller.compute(control, type, speed, DT_MS), type, DT_MS);
			seconds += DT_MS / 1000.0;
		}
		return seconds;
	}
}
