package org.mtr.core.mmtr.physics;

import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistType;
import org.mtr.core.mmtr.ConsistTypeRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **车钩力（折中版）**（notes/277 片 7，规格 §6：自由间隙 15–30 mm、多段刚度、迟滞吸能 60–70%）。
 *
 * <p>折中版的范围：引擎里一列车只有**一个速度**，所以只建**机车 ↔ 车列**这一个钩 —— 车头那节与它后面
 * 整列车列各当一个等效质点，挂车之间仍视为刚性（全多体留给后面）。本类钉的是这个钩的**物理内容**：</p>
 *
 * <ol>
 *   <li><b>自由间隙是死区</b>：间隙没吃完钩力恒 0 ⇒ 车头按**自己的质量**冲出去（加速度 &gt; 刚体值）；</li>
 *   <li><b>间隙吃完那一下会顿</b>：钩力顶上来，加速度掉到刚体值之下（这就是"冲动"）；</li>
 *   <li><b>多段刚度</b>：软段之后是极硬的尾段（撞底前那一截）；</li>
 *   <li><b>迟滞吸能</b>：复原行程刚度按 {@code 1−比例} 折 ⇒ 一圈耗掉 60–70%；</li>
 *   <li><b>稳态回到刚体</b>：张力稳定后车头感受到的就是刚体加速度，+ 拖车列所需的钩力；</li>
 *   <li><b>制动时压钩</b>：钩力变负（车列推车头）⇒ 车头减速度**小于**刚体值；</li>
 *   <li><b>不写车钩键就是刚性</b>（零回归）。</li>
 * </ol>
 */
public final class MmtrCouplerTests {

	/** BR101 的惯性质量 λ·m = 84 t × 1.16。 */
	private static final double LEAD_M = 84_000 * 1.16;
	/** 2×p1 客车（各 40 t × 1.06）的惯性质量。 */
	private static final double RAKE_M = 2 * 40_000 * 1.06;
	private static final double DT = 0.05;

	/** 起步：间隙白走一段（钩力 0、车头单飞），吃完那一下顿一顿，稳态回到刚体。 */
	@Test
	public void theSlackIsFreePlayAndTakingItUpIsTheJerk() {
		final CouplerDynamics coupler = new CouplerDynamics(CouplerSpec.DEFAULT);
		final double rigid = 1.0;
		final double firstStepAccel = coupler.step(0, DT, LEAD_M, RAKE_M, rigid);
		assertEquals(rigid * (LEAD_M + RAKE_M) / LEAD_M, firstStepAccel, 0.02,
			"第一拍钩力为 0 ⇒ 车头按自己的质量冲出去（(mL+mR)/mL 倍刚体）");
		assertFalse(coupler.isSlackTakenUp(), "第一拍还在间隙里（钩舌没受力）");

		double speed = Math.max(0, firstStepAccel * DT);
		int firstTakeUpStep = -1;
		double minAccelAfterTakeUp = Double.MAX_VALUE;
		for (int i = 2; i <= 400; i++) {
			final double acceleration = coupler.step(speed, DT, LEAD_M, RAKE_M, rigid);
			speed = Math.max(0, speed + acceleration * DT);
			if (firstTakeUpStep < 0 && coupler.isSlackTakenUp()) {
				firstTakeUpStep = i;
			}
			if (firstTakeUpStep > 0 && i >= firstTakeUpStep) {
				minAccelAfterTakeUp = Math.min(minAccelAfterTakeUp, acceleration);
			}
		}
		System.out.println(String.format("[TEST] 起步：第一拍 %.2f m/s²（车头单飞）/ 间隙吃完第 %d 拍（%.2f s）/ 吃完后最低 %.2f m/s² / 稳态钩力 %.1f kN / 峰值 %.1f kN",
			firstStepAccel, firstTakeUpStep, firstTakeUpStep * DT, minAccelAfterTakeUp,
			coupler.getForceN() / 1000, coupler.getPeakForceN() / 1000));

		assertTrue(firstTakeUpStep >= 2 && firstTakeUpStep <= 8,
			"20 mm 间隙在 1.87 m/s² 下要吃 0.15 s 量级（2–8 拍），实际第 " + firstTakeUpStep + " 拍");
		assertTrue(minAccelAfterTakeUp < rigid * 0.98, "间隙吃完那一下必须**顿**（掉到刚体值之下），实际 " + minAccelAfterTakeUp);
		assertTrue(minAccelAfterTakeUp > rigid * 0.3,
			"顿挫要有量级、不能是数值振荡（规格 §7 的刚性 ODE），实际 " + minAccelAfterTakeUp);

		// 稳态：**取最后 40 拍的平均**。单拍读数是钩上那点振荡的瞬时值，拿它当判据会把"稳态对不对"
		// 变成"抽到哪个相位"（本仓的老教训：判据要选能代表物理量的量）。
		double settledSum = 0;
		for (int i = 0; i < 40; i++) {
			final double acceleration = coupler.step(speed, DT, LEAD_M, RAKE_M, rigid);
			speed = Math.max(0, speed + acceleration * DT);
			settledSum += acceleration;
		}
		final double settledAccel = settledSum / 40;
		System.out.println(String.format("[TEST] 稳态（最后 40 拍平均）%.3f m/s²（刚体 1.000）/ 稳态钩力 %.1f kN（理论 a·mR = %.1f kN）",
			settledAccel, coupler.getForceN() / 1000, rigid * RAKE_M / 1000));
		assertEquals(rigid, settledAccel, 0.05, "稳态回到刚体加速度");
		assertEquals(rigid * RAKE_M, coupler.getForceN(), rigid * RAKE_M * 0.15, "稳态钩力 ≈ 拖着车列所需的力 = a·mR");
		assertTrue(coupler.getPeakForceN() < 1.0e6, "峰值钩力不该冲到断钩量级，实际 " + coupler.getPeakForceN() / 1000 + " kN");
	}

	/** 多段刚度 + 迟滞吸能（吸能比是**振幅的函数**，典型行程上落在规格的 60%–70%）。 */
	@Test
	public void theStiffnessIsMultiStageAndTheHysteresisAbsorbsEnergy() {
		final CouplerSpec spec = CouplerSpec.DEFAULT;
		assertEquals(0, spec.stiffnessAt(spec.slackM() * 0.99), 1e-9, "间隙内刚度 0（钩舌没受力）");
		assertEquals(spec.stiffnessSoftNPerM(), spec.stiffnessAt(spec.slackM() + 0.01), 1e-9, "软段");
		assertEquals(spec.stiffnessHardNPerM(), spec.stiffnessAt(spec.slackM() + spec.travelSoftM() + 0.01), 1e-9,
			"超过软段行程 ⇒ 尾段极硬（防撞底）");
		assertTrue(spec.stiffnessHardNPerM() > spec.stiffnessSoftNPerM() * 5, "硬段必须明显更硬");
		// 稳态变形必须落在软段里，否则会在两段之间极限环（见 CouplerSpec 的 javadoc）
		final double steadyDeflectionM = spec.slackM() + 85_000 / spec.stiffnessSoftNPerM();
		assertTrue(steadyDeflectionM < spec.slackM() + spec.travelSoftM(),
			"1 m/s² 拖 100 t 级车列的稳态变形（" + Math.round(steadyDeflectionM * 1000) + " mm）必须在软段内");

		// 迟滞吸能：一圈 4·F_y·A / 弹性储能 ½kA²
		final double ratioAtTypicalStroke = spec.energyAbsorptionRatio(0.040);
		System.out.println(String.format("[TEST] 迟滞吸能比：40 mm 行程 %.0f%%（规格 60–70%%）/ 10 mm 行程 %.0f%%（小振幅摩擦占比更高 ⇒ 吸能比更大，真缓冲器就是这样）",
			ratioAtTypicalStroke * 100, spec.energyAbsorptionRatio(0.010) * 100));
		assertTrue(ratioAtTypicalStroke >= 0.60 && ratioAtTypicalStroke <= 0.70,
			"规格 §6：典型行程上吸能 60%–70%，实际 " + ratioAtTypicalStroke);
		assertTrue(spec.energyAbsorptionRatio(0.010) > ratioAtTypicalStroke,
			"吸能比 ∝ 1/振幅（库仑圈固定 4·F_y·A 对弹性储能 ½kA²）—— 不是常数");
	}

	/** 制动时**压钩**：钩力变负（车列推车头）⇒ 过渡段里车头减速度小于刚体值。 */
	@Test
	public void theCouplerGoesIntoCompressionUnderBraking() {
		final CouplerDynamics coupler = new CouplerDynamics(CouplerSpec.DEFAULT);
		double speed = 0;
		for (int i = 0; i < 400; i++) {
			speed = Math.max(0, speed + coupler.step(speed, DT, LEAD_M, RAKE_M, 1.0) * DT);
		}
		assertTrue(coupler.getForceN() > 0, "先跑成拉伸状态，实际 " + coupler.getForceN());

		double lastDeceleration = 0;
		double minAbsDeceleration = Double.MAX_VALUE;
		for (int i = 0; i < 40; i++) {
			lastDeceleration = coupler.step(speed, DT, LEAD_M, RAKE_M, -1.0);
			speed = Math.max(0, speed + lastDeceleration * DT);
			minAbsDeceleration = Math.min(minAbsDeceleration, Math.abs(lastDeceleration));
		}
		System.out.println(String.format("[TEST] 制动：钩力 %.1f kN（负 = 车列在推车头）/ 过渡段车头减速度最低 %.2f m/s²（刚体 1.00）",
			coupler.getForceN() / 1000, minAbsDeceleration));
		assertTrue(coupler.getForceN() < 0, "压钩：钩力变负，实际 " + coupler.getForceN());
		assertTrue(minAbsDeceleration < 0.98,
			"车列从后面推 ⇒ 过渡段里车头减速度**小于**刚体值，实际 " + minAbsDeceleration);
	}

	/** 随包/老配置：不写车钩键 ⇒ {@code null}（刚性车列，逐位等于片 7 之前）。 */
	@Test
	public void consistTypesWithoutCouplerKeysStayRigid() {
		final ConsistTypeRegistry plain = ConsistTypeRegistry.parse("{\"consistTypes\":["
			+ "{\"id\":\"loco\",\"massKg\":84000,\"maxTractiveEffortN\":300000,\"maxPowerW\":6400000,\"payloadKg\":1000}]}");
		assertNull(plain.get("loco").getCoupler(), "没有车钩键 ⇒ 刚性");
		assertNull(plain.get("loco").withLoad(1).getCoupler(), "折载重不该凭空造出一个钩");
		assertTrue(plain.get("loco").withLoad(1).getMassKg() > plain.get("loco").getMassKg());

		final ConsistTypeRegistry coupled = ConsistTypeRegistry.parse("{\"consistTypes\":["
			+ "{\"id\":\"loco\",\"massKg\":84000,\"maxTractiveEffortN\":300000,\"maxPowerW\":6400000,"
			+ "\"couplerSlackM\":0.02,\"payloadKg\":1000}]}");
		final CouplerSpec spec = coupled.get("loco").getCoupler();
		assertNotNull(spec, "写了任意一个车钩键 ⇒ 建钩（其余按典型值补）");
		assertEquals(0.02, spec.slackM(), 1e-9);
		assertEquals(CouplerSpec.DEFAULT.stiffnessSoftNPerM(), spec.stiffnessSoftNPerM(), 1e-9);
		assertSame(spec, coupled.get("loco").getCoupler(), "同一份车底取到的钩口径是同一个对象（重建判据用它）");
		assertNotNull(coupled.get("loco").withLoad(1).getCoupler(), "折载重必须保住钩口径");
		assertFalse(coupled.get("loco").getCoupler() == null);
	}
}
