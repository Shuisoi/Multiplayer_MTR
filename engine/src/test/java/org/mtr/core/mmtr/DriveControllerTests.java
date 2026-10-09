package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.physics.BrakeSpec;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 有级 / 无级 / （AIR_BRAKE 走的）有级 三条操纵路径的真值表。
 *
 * <p>notes/376（2026-10-03 用户口径「legacy 的车辆牵引制动逻辑直接删除，只能用新版本的」）之后，
 * 这里的**力只有一条路**：档位/轴 → {@code BrakeCommand} → 列车管 → 分配阀 → 缸压 →
 * 锚 × f(缸压) × κ(v) → 除以惯性质量。所以"某个档位给多少减速度"不再是"档/档数 × 全制动力"，
 * 本类里的硬数字都按这条式子**重新量过**（旧值写在注释里备查）。</p>
 */
public final class DriveControllerTests {

	private static final String SAMPLE_JSON = "{"
		+ "  \"consistTypes\": ["
		+ "    {\"id\":\"emu\",\"name\":\"EMU\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "     \"maxSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000},"
		+ "    {\"id\":\"lr\",\"name\":\"LR\",\"controlMode\":\"STEPLESS\",\"maxSpeedKmh\":80,"
		+ "     \"massKg\":60000,\"maxTractiveEffortN\":42000,\"serviceBrakeForceN\":60000,\"emergencyBrakeForceN\":96000},"
		+ "    {\"id\":\"freight\",\"name\":\"Freight\",\"controlMode\":\"AIR_BRAKE\",\"powerNotches\":8,\"brakeNotches\":3,"
		+ "     \"maxSpeedKmh\":100,\"massKg\":60000,\"maxTractiveEffortN\":18000,\"serviceBrakeForceN\":42000,\"emergencyBrakeForceN\":72000,"
		+ "     \"airPipeChargeRatePerSecond\":0.12,\"airPipeDischargeRatePerSecond\":0.5,"
		+ "     \"airBrakeApplyRatePerSecond\":0.2,\"airBrakeReleaseRatePerSecond\":0.08}"
		+ "  ]"
		+ "}";

	/** 出厂闸片摩擦衰减 κ(v)（notes/376）：{@code μ_b(v) = μ0 − k·v_kmh}，κ = μ_b/μ0；0 速恒为 1。 */
	private static double padFadeKappa(double speedMetersPerSecond) {
		return (BrakeSpec.DEFAULT_PAD_MU0 - BrakeSpec.DEFAULT_PAD_MU_SLOPE_PER_KMH * speedMetersPerSecond * 3.6)
			/ BrakeSpec.DEFAULT_PAD_MU0;
	}

	@Test
	public void testRegistryParsesSample() {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(SAMPLE_JSON);
		assertTrue(registry.contains("emu"));
		assertTrue(registry.contains("lr"));
		assertTrue(registry.contains("freight"));
		assertEquals(ConsistType.ControlMode.NOTCHED, registry.get("emu").getControlMode());
		assertEquals(7, registry.get("emu").getPowerNotches());
		assertEquals(80 / 3.6, registry.get("lr").getMaxSpeedMetersPerSecond(), 1e-9);
	}

	@Test
	public void testRegistryDefaultsFallback() {
		final ConsistType type = ConsistType.fromJson(new com.google.gson.JsonObject());
		assertEquals(ConsistType.ControlMode.DEFAULT, type.getControlMode());
		assertEquals(7, type.getPowerNotches());
		assertTrue(type.getPhysics().getTraction().getMaxTractiveEffortN() > 0, "缺省也要有牵引力（通用车力学）");
		// notes/376 判据①：没写 bar 键的车底取出厂气压口径 —— getBrakes() 永不为 null（没有"旧模型"这条路）。
		assertNotNull(type.getBrakes(), "缺配置也必须有气压口径（出厂口径）");
		assertEquals(ConsistType.DEFAULT_BRAKES.getChargedBar(), type.getBrakes().getChargedBar(), 1e-12);
	}

	@Test
	public void testNotchedControllerMonotonicAndBounded() {
		final ConsistType type = ConsistTypeRegistry.parse(SAMPLE_JSON).get("emu");
		final NotchedDriveController controller = new NotchedDriveController();
		double previous = -1;
		for (int notch = 0; notch <= 7; notch++) {
			final double accel = controller.compute(ControlState.zero().setThrottleNotch(notch), type, 0, 100)
				.getAccelerationMetersPerSecondSquared();
			assertTrue(accel >= previous, "accel should not decrease with notch at standstill");
			previous = accel;
		}
		assertEquals(0.6, previous, 1e-9); // full notch at standstill == 36000 N / 60000 kg (no resistance)
		/*
		 * 制动：8 档（8/8 档 × 级位表常用上限 0.9 = 诉求 0.9）= 级位表第 9 项 **3.5 bar** ⇒
		 * 分配阀给 3.8 bar 缸压（顶到限压）⇒ 力 = 锚 × κ(36 km/h)。制动需要时间（排风 + 建压 ≈ 3 s），
		 * 所以先跑 100 拍再读，读数才是稳态而不是"第一拍缸压还是 0"。
		 *
		 * <p>旧口径这里断言 −0.9（= 54000 / 60000，档/档数 × 全制动力，没有管压/缸簧/闸片这三层）。</p>
		 */
		final ControlState both = ControlState.zero().setThrottleNotch(7).setBrakeNotch(8);
		for (int i = 0; i < 100; i++) {
			controller.compute(both, type, 10, 100);
		}
		final DriveOutput output = controller.compute(both, type, 10, 100);
		assertEquals(3.5, controller.getBrakeModel().getPipeBar(), 1e-9, "8 档管压停在全常用 3.5 bar");
		assertEquals(3.8, controller.getBrakeModel().getCylinderBar(), 1e-9, "缸压顶到车底上限");
		assertEquals(-54_000 * padFadeKappa(10) / 60_000, output.getAccelerationMetersPerSecondSquared(), 1e-9,
			"满档 = 锚 × 闸片衰减 / 惯性质量（−0.8626 m/s²，旧口径 −0.9）");
		assertTrue(output.isBrakeLamp());
		// out-of-range notches are clamped
		final NotchedDriveController clamped = new NotchedDriveController();
		for (int i = 0; i < 100; i++) {
			clamped.compute(ControlState.zero().setBrakeNotch(99), type, 10, 100);
		}
		assertEquals(-54_000 * padFadeKappa(10) / 60_000,
			clamped.compute(ControlState.zero().setBrakeNotch(99), type, 10, 100).getAccelerationMetersPerSecondSquared(), 1e-9,
			"超出档位数的 99 档被夹到 8 档");
	}

	@Test
	public void testControlStateNotchStepsClamp() {
		final ControlState state = ControlState.zero();
		state.stepThrottle(5, 7);
		state.stepThrottle(5, 7); // would be 10 -> clamp to 7
		assertEquals(7, state.getThrottleNotch());
		state.stepThrottle(-99, 7);
		assertEquals(0, state.getThrottleNotch());
		state.stepBrake(-3, 8); // stays 0
		assertEquals(0, state.getBrakeNotch());
		state.stepReverser(5, -1, 1);
		assertEquals(1, state.getReverser());
	}

	@Test
	public void testSteplessController() {
		final ConsistType type = ConsistTypeRegistry.parse(SAMPLE_JSON).get("lr");
		final SteplessDriveController controller = new SteplessDriveController();
		/*
		 * 制动轴 0.5（notes/376 重新量过，旧口径的 −0.5 = 轴 × 全制动力已删除）：
		 *
		 * <pre>
		 *   轴 0.5 → 常用诉求 0.5 × 级位表常用上限 0.9 = 0.45
		 *          → 级位表插值落在第 4/5 项之间：4.05 与 3.9 的中点 = 3.975 bar（管压）
		 *   分配阀：缸压 = 2.5333 × (5.2 − 3.975 − 0.2) = 2.5966 bar
		 *   缸簧以上可用比例 = (2.5966 − 0.3) / 3.5 = 0.6562
		 *   力 = 60 kN × 0.6562 × κ(36 km/h) = 60 kN × 0.6562 × 0.9585 = 37.7 kN
		 *   a = 37.7 kN / 60 t = **−0.629 m/s²**
		 * </pre>
		 */
		final ControlState halfBrakeControl = ControlState.zero().setBrakeAxis(0.5);
		for (int i = 0; i < 100; i++) {
			controller.compute(halfBrakeControl, type, 10, 100);
		}
		final double pipeBar = controller.getBrakeModel().getPipeBar();
		final double cylinderBar = controller.getBrakeModel().getCylinderBar();
		System.out.println(String.format("[TEST] 无级制动轴 0.5：管压 %.4f bar、缸压 %.4f bar、减速度 %.6f m/s²",
			pipeBar, cylinderBar, controller.compute(halfBrakeControl, type, 10, 100).getAccelerationMetersPerSecondSquared()));
		assertEquals(3.975, pipeBar, 1e-9, "制动轴 0.5 的管压目标 = 4.05 与 3.9 的中点（级位表插值）");
		assertEquals(2.5333 * (5.2 - 3.975 - 0.2), cylinderBar, 1e-4, "分配阀：缸压 = 倍率 × (充风 − 管压 − 灵敏限)");
		final double usable = (cylinderBar - 0.30) / (3.8 - 0.30);
		final DriveOutput halfBrake = controller.compute(halfBrakeControl, type, 10, 100);
		assertEquals(-60_000 * usable * padFadeKappa(10) / 60_000, halfBrake.getAccelerationMetersPerSecondSquared(), 1e-9,
			"轴 → 缸压 → 力：−0.629 m/s²（旧口径 −0.5）");
		// 惰行 / 牵引：**各用一台新控制器**。同一台控制器此刻还压着 2.6 bar 缸压 ⇒ 牵引联锁把牵引压住、
		// 残余闸还在减速（那是"有闸不许牵引"，不是"推油门不动"）—— 详见 notes/376 的联锁口径。
		final DriveOutput power = new SteplessDriveController().compute(ControlState.zero().setThrottleAxis(1.0), type, 0, 100);
		assertTrue(power.getAccelerationMetersPerSecondSquared() > 0 && power.getAccelerationMetersPerSecondSquared() <= 0.7 + 1e-9);
		// above target speed -> coast (no negative from throttle)
		final DriveOutput atTarget = new SteplessDriveController().compute(ControlState.zero().setThrottleAxis(0.5), type, type.getMaxSpeedMetersPerSecond(), 100);
		assertTrue(atTarget.getAccelerationMetersPerSecondSquared() >= 0);
		// idle -> coast
		assertEquals(0, new SteplessDriveController().compute(ControlState.zero(), type, 5, 100).getAccelerationMetersPerSecondSquared(), 1e-9);
	}

	/**
	 * **缓解 → 保压 → 全制动 → 紧急**的整循环（notes/376：旧 {@code AirBrakeController} 已删除，
	 * AIR_BRAKE 与有级共用 {@link NotchedDriveController}，见 {@code Vehicle} 的控制器工厂）。
	 *
	 * <p>钉的还是同一组语义：制动 ⇒ 管压朝级位表目标掉、缸压建起、制动灯亮；缓解 ⇒ 缸压排空、管压回充；
	 * 紧急 ⇒ 快排（3 倍常用排风）+ 缸压顶到紧急限压 + {@code isEmergencyBrake()}。
	 * 气压量本身是 **bar**（管压 5.2 → 3.5 / 3.0，缸压 0 → 3.8 / 4.2），不再是"0 = 排空"的归一化量。</p>
	 */
	@Test
	public void testAirBrakeReleaseLapApplyCycle() {
		final ConsistType type = ConsistTypeRegistry.parse(SAMPLE_JSON).get("freight");
		final NotchedDriveController controller = new NotchedDriveController();
		assertEquals(1.0, controller.getPipePressure(), 1e-9, "新车的归一化管压 = 满管（镜像读数）");
		// bar 读数要等制动系统建起来（第一拍）才有：那之前只有归一化镜像读数（notes/376）
		assertEquals(0, controller.getBrakeModel().getPipeBar(), 1e-9);
		controller.compute(ControlState.zero(), type, 10, 100);
		assertEquals(5.2, controller.getBrakeModel().getPipeBar(), 1e-9, "建系统那一拍从充风值 5.2 bar 起");

		// APPLY 全制动档（3/3 档 × 0.9 = 诉求 0.9 = 级位表全常用 3.5 bar）
		final ControlState apply = ControlState.zero().setBrakeNotch(3);
		for (int i = 0; i < 120; i++) {
			controller.compute(apply, type, 10, 100);
		}
		assertTrue(controller.getBrakeCylinderPressure() > 0.95, "sustained apply must build the cylinder");
		assertEquals(3.5, controller.getBrakeModel().getPipeBar(), 1e-9, "管压停在全常用 3.5 bar（旧口径的「排空到 0」是归一化量）");
		assertEquals(3.8, controller.getBrakeModel().getCylinderBar(), 1e-9, "缸压顶到车底上限 3.8 bar");
		final DriveOutput applied = controller.compute(apply, type, 10, 100);
		assertEquals(-42_000 * padFadeKappa(10) / 60_000, applied.getAccelerationMetersPerSecondSquared(), 1e-9,
			"全常用 = 锚 × κ / 惯性质量（−0.671 m/s²；旧口径不衰减 ⇒ −0.7）");
		assertTrue(applied.isBrakeLamp());

		// RELEASE (notch 0) -> cylinder releases, pipe recharges
		final ControlState release = ControlState.zero().setBrakeNotch(0);
		for (int i = 0; i < 200; i++) {
			controller.compute(release, type, 10, 100);
		}
		assertTrue(controller.getBrakeCylinderPressure() < 0.05, "cylinder should release");
		assertTrue(controller.getPipePressure() > 0.95, "pipe should recharge");
		assertEquals(5.2, controller.getBrakeModel().getPipeBar(), 1e-9);

		// 紧急排风比常用快得多：第一拍常用掉 0.85 bar/s、紧急掉 0.85 × 3 = 2.55 bar/s
		final NotchedDriveController serviceProbe = new NotchedDriveController();
		serviceProbe.compute(apply, type, 10, 100);
		final double serviceDropBar = 5.2 - serviceProbe.getBrakeModel().getPipeBar();
		final NotchedDriveController emergencyProbe = new NotchedDriveController();
		emergencyProbe.compute(ControlState.zero().setEmergency(true), type, 10, 100);
		final double emergencyDropBar = 5.2 - emergencyProbe.getBrakeModel().getPipeBar();
		System.out.println(String.format("[TEST] 第一拍排风：常用 %.3f bar / 紧急 %.3f bar", serviceDropBar, emergencyDropBar));
		assertEquals(0.085, serviceDropBar, 1e-9, "常用排风 = airPipeDischargeBarPerSecond × 0.1 s");
		assertEquals(0.255, emergencyDropBar, 1e-9, "紧急排风 = 3 × 常用");

		// 紧急：快排到紧急位管压 3.0 bar、缸压顶到紧急限压 4.2 bar（高于常用上限），并置紧急标志
		controller.reset();
		final ControlState emergency = ControlState.zero().setEmergency(true);
		for (int i = 0; i < 40; i++) {
			controller.compute(emergency, type, 10, 100);
		}
		assertTrue(controller.getBrakeCylinderPressure() > 0.99, "紧急缸压 ≥ 常用上限（4.2 bar > 3.8）");
		assertEquals(3.0, controller.getBrakeModel().getPipeBar(), 1e-9, "紧急管压目标 3.0 bar");
		assertEquals(4.2, controller.getBrakeModel().getCylinderBar(), 1e-9, "紧急限压 4.2 bar");
		final DriveOutput emOut = controller.compute(emergency, type, 10, 100);
		assertTrue(emOut.isEmergencyBrake());
		assertTrue(emOut.getAccelerationMetersPerSecondSquared() < applied.getAccelerationMetersPerSecondSquared(),
			"紧急必须比全常用强");
	}
}
