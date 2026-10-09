package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * **牵引力增速控制**（notes/265，用户口径 2026-09-25）：
 *
 * <blockquote>牵引力增速控制用于减少牵引期间快速加力引发的冲力。牵引力上或下的速率限制为
 * 全部 30 kN/s，意味着每秒只能变化 30 kN 的牵引力。</blockquote>
 *
 * <p>这里钉五件事：① 上升 30 kN/s；② 下降也是 30 kN/s（方向不豁免）；③ 限的是**力**不是比例
 * （恒功率段同一个比例的力小得多，比例限速会跑出完全不同的斜率）；④ 回落期间的**残余牵引真的在推车**
 * （合力里照实扣，旧的"按分支算"会把它丢掉）；⑤ 配 0 = 不限速（旧口径，零回归）。</p>
 *
 * <p>全部纯 JVM：手柄位置直接喂给控制器，速度由用例给定（不积分），于是"每秒多少 kN"可以逐拍对账。</p>
 */
public final class MmtrTractionRampTests {

	/** BR101 量级的车底；阻力与电阻制动置 0，只留下要验的那几股力。 */
	private static final String JSON = "{"
		+ "  \"consistTypes\": ["
		+ "    {\"id\":\"ramp\",\"name\":\"ramp\",\"controlMode\":\"THREE_HANDLE\",\"powerNotches\":97,\"brakeNotches\":11,"
		+ "     \"maxSpeedKmh\":220,\"massKg\":84000,\"rotatingMassFactor\":1.0,"
		+ "     \"maxTractiveEffortN\":300000,\"maxPowerW\":6400000,"
		+ "     \"serviceBrakeForceN\":150000,\"emergencyBrakeForceN\":210000,"
		+ "     \"rheostaticBrakeForceN\":150000,\"rheostaticFadeKmh\":15,"
		+ "     \"resistanceAN\":0,\"resistanceBN\":0,\"resistanceCN\":0,"
		+ "     \"tractionLagMillis\":0,\"tractionRampNPerSecond\":30000,"
		+ "     \"airPipeChargeRatePerSecond\":0.15,\"airPipeDischargeRatePerSecond\":0.5,"
		+ "     \"airBrakeApplyRatePerSecond\":0.35,\"airBrakeReleaseRatePerSecond\":0.25,"
		+ "     \"brakeRatios\":\"0,0.05,0.12,0.20,0.3333,0.4667,0.60,0.7333,0.8667,1.00,1.00\"}"
		+ "  ]"
		+ "}";

	/** 同一份车底，但增速控制**关掉**（0 = 一拍到位，旧口径）。 */
	private static final String INSTANT_JSON = JSON.replace("\"tractionRampNPerSecond\":30000", "\"tractionRampNPerSecond\":0");

	/** 编组里没写这个键时的缺省（规格值 30 kN/s）。 */
	private static final String DEFAULT_JSON = JSON.replace(",\"tractionRampNPerSecond\":30000", "");

	private static final long DT_MS = 100;
	private static final double RAMP_N_PER_SECOND = 30_000;
	/** 满牵引（0 速、干轨 0.37 ⇒ 黏着上限 305 kN > 电机 300 kN）。 */
	private static final double FULL_EFFORT_N = 300_000;

	private static ConsistType type(String json) {
		return ConsistTypeRegistry.parse(json).get("ramp");
	}

	private static ThreeHandleDriveController controller() {
		return new ThreeHandleDriveController();
	}

	/** 手柄推满后跑 {@code seconds} 秒（速度钉在 {@code speedMps}，只让力自己爬）。 */
	private static void runAt(ThreeHandleDriveController controller, ConsistType type, ControlState control, double speedMps, double seconds) {
		for (int i = 0; i < Math.round(seconds * 1000 / DT_MS); i++) {
			controller.compute(control, type, speedMps, DT_MS);
		}
	}

	// ---- ① 上升 30 kN/s ---------------------------------------------------------------------------

	@Test
	public void tractionBuildsUpAtThirtyKilonewtonsPerSecond() {
		final ConsistType type = type(JSON);
		final ThreeHandleDriveController controller = controller();
		final ControlState full = ControlState.zero().setDriveHandle(97);

		final DriveOutput first = controller.compute(full, type, 0, DT_MS);
		assertEquals(3_000, controller.getAppliedTractiveEffortN(), 1e-6, "第一拍 100 ms ⇒ 3 kN（= 30 kN/s）");
		assertEquals(3_000 / 84_000.0, first.getAccelerationMetersPerSecondSquared(), 1e-9,
			"加速度必须由**实际施加的那份力**算出来");

		runAt(controller, type, full, 0, 1 - DT_MS / 1000.0);
		assertEquals(30_000, controller.getAppliedTractiveEffortN(), 1.0, "1 s ⇒ 30 kN");

		runAt(controller, type, full, 0, 4);
		assertEquals(150_000, controller.getAppliedTractiveEffortN(), 2.0, "5 s ⇒ 150 kN（线性爬升）");

		runAt(controller, type, full, 0, 5);
		assertEquals(FULL_EFFORT_N, controller.getAppliedTractiveEffortN(), 1e-6, "10 s 满力：300 kN / 30 kN/s");
		assertEquals(1.0, controller.getLastTractionRatio(), 1e-9, "满力 ⇒ 等效比例 1.0");
		runAt(controller, type, full, 0, 5);
		assertEquals(FULL_EFFORT_N, controller.getAppliedTractiveEffortN(), 1e-6, "到顶后不许越界");
	}

	/**
	 * 限的是**力**，不是比例：200 km/h 上满手柄只有 115 kN（恒功率段），若按比例限速，1 s 只该爬
	 * 0.1 × 115 kN = 11.5 kN —— 而力的口径要求**照样 30 kN/s**。
	 */
	@Test
	public void theRampIsInNewtonsNotInHandleRatio() {
		final ConsistType type = type(JSON);
		final ThreeHandleDriveController controller = controller();
		final double speedMps = 200 / 3.6;
		final double fullEffortAtSpeed = type.getPhysics().tractiveEffortN(1, speedMps);
		assertEquals(115_200, fullEffortAtSpeed, 200, "200 km/h 满手柄 ≈ 115 kN（6.4 MW / v）");

		runAt(controller, type, ControlState.zero().setDriveHandle(97), speedMps, 1);
		assertEquals(RAMP_N_PER_SECOND, controller.getAppliedTractiveEffortN(), 2.0,
			"高速段同样是 30 kN/s（不是 11.5 kN/s 的比例口径）");
		assertEquals(RAMP_N_PER_SECOND / fullEffortAtSpeed, controller.getLastTractionRatio(), 1e-3,
			"等效比例 = 30 kN / 115.2 kN ≈ 0.26");
	}

	// ---- ② 下降也是 30 kN/s -----------------------------------------------------------------------

	@Test
	public void tractionAlsoRampsDownAtThirtyKilonewtonsPerSecond() {
		final ConsistType type = type(JSON);
		final ThreeHandleDriveController controller = controller();
		final ControlState full = ControlState.zero().setDriveHandle(97);
		runAt(controller, type, full, 0, 10);
		assertEquals(FULL_EFFORT_N, controller.getAppliedTractiveEffortN(), 1e-6);

		final ControlState closed = ControlState.zero();
		controller.compute(closed, type, 0, DT_MS);
		assertEquals(297_000, controller.getAppliedTractiveEffortN(), 1e-6, "回杆第一拍只落 3 kN（不是立刻归零）");

		runAt(controller, type, closed, 0, 1 - DT_MS / 1000.0);
		assertEquals(270_000, controller.getAppliedTractiveEffortN(), 1.0, "1 s ⇒ 270 kN");
		runAt(controller, type, closed, 0, 9);
		assertEquals(0, controller.getAppliedTractiveEffortN(), 1e-6, "10 s 才落到底：300 kN / 30 kN/s");
		assertEquals(0, controller.getLastTractionRatio(), 1e-9);
	}

	/**
	 * 回落期间的残余牵引**真的在推车**：满牵引 300 kN 时拉 8 档（常用制动 150 kN），
	 * 头几秒合力仍是**正的**（车还在加速）—— 这是"升降都限 30 kN/s"的直接后果，写在用例里免得日后
	 * 被当成 bug。旧写法只算制动那一支，会得到"闸一拉就减速"的假象。
	 */
	@Test
	public void residualTractionIsCountedAgainstTheBrake() {
		final ConsistType type = type(JSON);
		final ThreeHandleDriveController controller = controller();
		runAt(controller, type, ControlState.zero().setDriveHandle(97), 0, 10);

		final ControlState braking = ControlState.zero().setBrakeNotch(9);
		final DriveOutput firstTick = controller.compute(braking, type, 0, DT_MS);
		assertTrue(firstTick.getAccelerationMetersPerSecondSquared() > 0,
			"牵引还没退完 + 缸压才刚建 ⇒ 合力仍为正，实际 " + firstTick.getAccelerationMetersPerSecondSquared());
		assertTrue(controller.getAppliedTractiveEffortN() > type.getBrake().serviceForceNFromCylinderBar(controller.getCylinderBar(), 0),
			"这一拍牵引力仍大于气制动力（缸压刚开始建，力 = 锚 × f(缸压)，notes/376）");

		runAt(controller, type, braking, 0, 15);
		assertEquals(0, controller.getAppliedTractiveEffortN(), 1e-6, "牵引最终必须退到 0");
		assertEquals(type.getBrakes().getCylinderMaxBar(), controller.getCylinderBar(), 1e-9, "15 s 后缸压已经建到上限");
		assertEquals(-type.getBrake().serviceForceNFromCylinderBar(type.getBrakes().getCylinderMaxBar(), 0) / 84_000.0,
			controller.compute(braking, type, 0, DT_MS).getAccelerationMetersPerSecondSquared(),
			1e-6, "退干净之后就是纯常用制动力（缸压上限折成的力 / 惯性质量；本车底 A=B=C=0、λ=1）");
	}

	/** 紧急也一样（方向与保护动作都不豁免）：残余牵引从紧急制动力里扣，直到 300 kN 退完才纯紧急。 */
	@Test
	public void theEmergencyBrakeAlsoRampsTractionDown() {
		final ConsistType type = type(JSON);
		final ThreeHandleDriveController controller = controller();
		runAt(controller, type, ControlState.zero().setDriveHandle(97), 0, 10);

		final ControlState emergency = ControlState.zero().setEmergency(true);
		final DriveOutput firstTick = controller.compute(emergency, type, 0, DT_MS);
		/*
		 * notes/376：紧急制动力现在也走"缸压 → 力"这一条路（缸簧 0.3 bar 以下不出力）。
		 * 紧急建压 2.0 bar/s，第一拍只到 0.2 bar ⇒ **这一拍还没有紧急制动力**，合力 = 残余牵引（300 − 3 kN）。
		 * 旧口径的"第一拍就扣满 210 kN"来自比例制动力（缸压比例 × 全制动力、没有缸簧、没有建压时间），已删除。
		 */
		assertTrue(firstTick.isEmergencyBrake());
		assertEquals(0.2, controller.getCylinderBar(), 1e-9, "紧急建压 2.0 bar/s × 100 ms");
		assertEquals(FULL_EFFORT_N - 3_000, firstTick.getAccelerationMetersPerSecondSquared() * 84_000.0, 1e-6,
			"第一拍：缸压还在缸簧以下 ⇒ 只剩残余牵引");

		// 建压到紧急限压 4.2 bar（高于常用上限 3.8）、牵引也退干净 ⇒ 纯紧急减速度
		runAt(controller, type, emergency, 0, 10);
		assertEquals(0, controller.getAppliedTractiveEffortN(), 1e-6, "牵引最终必须退到 0");
		assertEquals(type.getBrakes().getCylinderEmergencyBar(), controller.getCylinderBar(), 1e-9, "紧急限压 4.2 bar");
		assertEquals(-type.getBrake().emergencyForceN(0) / 84_000.0, controller.compute(emergency, type, 0, DT_MS).getAccelerationMetersPerSecondSquared(), 1e-6,
			"10 s 后牵引退干净 ⇒ 纯紧急减速度（0 速 ⇒ 闸片不衰减）");
	}

	// ---- ⑤ 配 0 = 不限速（旧口径，零回归）----------------------------------------------------------

	@Test
	public void aZeroRampKeepsTheInstantBehaviour() {
		final ConsistType type = type(INSTANT_JSON);
		final ThreeHandleDriveController controller = controller();
		final ControlState full = ControlState.zero().setDriveHandle(97);

		controller.compute(full, type, 0, DT_MS);
		assertEquals(FULL_EFFORT_N, controller.getAppliedTractiveEffortN(), 1e-9, "0 = 一拍到位");
		assertEquals(1.0, controller.getLastTractionRatio(), 1e-9);
		controller.compute(ControlState.zero(), type, 0, DT_MS);
		assertEquals(0, controller.getAppliedTractiveEffortN(), 1e-9, "0 = 立刻切除（老口径）");
	}

	// ---- 配置与镜像 -------------------------------------------------------------------------------

	@Test
	public void theRampIsConfigurableAndSurvivesTheMirrorString() {
		assertEquals(RAMP_N_PER_SECOND, type(JSON).getHandles().getTractionRampNPerSecond(), 1e-9);
		assertEquals(ThreeHandleSpec.DEFAULT_TRACTION_RAMP_N_PER_SECOND, type(DEFAULT_JSON).getHandles().getTractionRampNPerSecond(), 1e-9,
			"配置里不写 ⇒ 缺省 30 kN/s（规格值）");
		assertEquals(30_000, ThreeHandleSpec.DEFAULT_TRACTION_RAMP_N_PER_SECOND, 1e-9, "规格值就是 30 kN/s");
		assertEquals(0, type(INSTANT_JSON).getHandles().getTractionRampNPerSecond(), 1e-9, "0 = 不限速");

		final ThreeHandleSpec spec = type(JSON).getHandles();
		final ThreeHandleSpec decoded = ThreeHandleSpec.decode(spec.encode());
		assertNotNull(decoded);
		assertEquals(spec.getTractionRampNPerSecond(), decoded.getTractionRampNPerSecond(), 1e-9,
			"镜像串必须带上增速上限（否则客户端跑的是另一套物理）");

		// 旧镜像串（没有第 14 个字段）：解回缺省值，不能变成 0（0 = 不限速 = 手感变回老的）
		final String encoded = spec.encode();
		final ThreeHandleSpec legacy = ThreeHandleSpec.decode(encoded.substring(0, encoded.lastIndexOf(';')));
		assertNotNull(legacy, "旧串必须仍然能解");
		assertEquals(ThreeHandleSpec.DEFAULT_TRACTION_RAMP_N_PER_SECOND, legacy.getTractionRampNPerSecond(), 1e-9);
	}

	/** 重置控制器（换端/重连）后从 0 重新爬 —— 不许把上一段的力带过来。 */
	@Test
	public void resetDropsTheAppliedTraction() {
		final ConsistType type = type(JSON);
		final ThreeHandleDriveController controller = controller();
		runAt(controller, type, ControlState.zero().setDriveHandle(97), 0, 10);
		assertEquals(FULL_EFFORT_N, controller.getAppliedTractiveEffortN(), 1e-6);
		controller.reset();
		assertEquals(0, controller.getAppliedTractiveEffortN(), 1e-9);
	}
}
