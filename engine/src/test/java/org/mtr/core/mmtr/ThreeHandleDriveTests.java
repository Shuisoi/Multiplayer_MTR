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
		+ "     \"maxSpeedKmh\":160,\"tractionAccelerationMps2\":0.55,\"tractionBreakpointKmh\":80,"
		+ "     \"serviceBrakeDecelerationMps2\":1.0,\"emergencyDecelerationMps2\":1.8,"
		+ "     \"rheostaticBrakeDecelerationMps2\":0.9,\"rheostaticFadeKmh\":10,"
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
		assertEquals(original.getRheostaticBrakeDecelerationMps2(), decoded.getRheostaticBrakeDecelerationMps2(), 1e-9);
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

	@Test
	public void afbApproachesTheSetSpeedWithinTheThrottleCap() {
		final ConsistType type = br101();
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		final ControlState state = ControlState.zero().setDriveHandle(97).setCruiseSpeedKmh(100);

		final DriveOutput out = controller.compute(state, type, 0, DT_MS);
		assertTrue(controller.isAfbActive(), "定速 > 0、气制动在运行位 ⇒ AFB 生效");
		assertTrue(out.getAccelerationMetersPerSecondSquared() > 0, "低于设定速度要牵引");
		assertTrue(controller.getLastTractionRatio() <= spec().tractionRatio(97) + 1e-9, "牵引不得超过油门手柄上限");

		// 手柄只有 5% 时，AFB 只能用 5% 的牵引
		final ThreeHandleDriveController capped = new ThreeHandleDriveController();
		capped.compute(ControlState.zero().setDriveHandle(2).setCruiseSpeedKmh(100), type, 0, DT_MS);
		assertEquals(0.05, capped.getLastTractionRatio(), 1e-9, "上限跟着手柄走");
	}

	@Test
	public void afbCannotAccelerateWithTheThrottleClosed() {
		final ConsistType type = br101();
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		// "油门手柄此时只当牵引上限"：关闭位 ⇒ cap = 0，AFB 这一侧推不动车
		final DriveOutput out = controller.compute(ControlState.zero().setCruiseSpeedKmh(100), type, 0, DT_MS);
		assertTrue(controller.isAfbActive());
		assertEquals(0, controller.getLastTractionRatio(), 1e-9, "手柄关闭 ⇒ 无牵引");
		assertEquals(0, out.getAccelerationMetersPerSecondSquared(), 1e-9);
	}

	@Test
	public void afbBrakesElectricallyWhenAboveTheSetSpeed() {
		final ConsistType type = br101();
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		final double target = 100 / 3.6;
		final DriveOutput out = controller.compute(ControlState.zero().setDriveHandle(97).setCruiseSpeedKmh(100), type, target + 3, DT_MS);
		assertTrue(controller.getLastRheostaticRatio() > 0, "超过设定速度要用电阻制动");
		assertTrue(out.getAccelerationMetersPerSecondSquared() < 0, "必须减速");
		// 带宽内不动手（免得在设定速度附近反复点刹）
		final ThreeHandleDriveController inBand = new ThreeHandleDriveController();
		inBand.compute(ControlState.zero().setDriveHandle(97).setCruiseSpeedKmh(100), type, target + 0.05, DT_MS);
		assertEquals(0, inBand.getLastRheostaticRatio(), 1e-9, "带宽内不施加电阻制动");
		assertEquals(0, inBand.getLastTractionRatio(), 1e-9, "带宽内惰行");
	}

	@Test
	public void pneumaticBrakeSuspendsAfb() {
		final ConsistType type = br101();
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		final ControlState state = ControlState.zero().setDriveHandle(97).setCruiseSpeedKmh(100).setBrakeNotch(1);
		final DriveOutput out = controller.compute(state, type, 0, DT_MS);
		assertFalse(controller.isAfbActive(), "司机一碰气制动，AFB 立即让位");
		assertEquals(0, controller.getLastTractionRatio(), 1e-9, "让位期间不得牵引");
		assertTrue(out.getAccelerationMetersPerSecondSquared() <= 0);
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
