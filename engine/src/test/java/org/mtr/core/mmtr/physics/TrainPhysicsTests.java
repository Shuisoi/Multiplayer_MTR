package org.mtr.core.mmtr.physics;

import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 力模型（正向半）的真值表。
 *
 * <p>两条主线：①**曲线形状**（恒力矩 → 恒功率、手柄线性、折点由 P/F 自动给出）；
 * ②**与旧口径的无损换算**（{@link TractionSpec#fromLegacy}）—— 这一条是"换模型不改手感"的根据，
 * 也是 notes/235 里配置迁移配方的可执行版本。</p>
 */
public final class TrainPhysicsTests {

	/** BR101 的粗量级：82 t、λ=1.06、起动牵引力 200 kN、轮周功率 1.9 MW。 */
	private static TrainPhysics br101() {
		return new TrainPhysics(82_000, 1.06,
			new TractionSpec(200_000, 1_900_000),
			new BrakeSpec(120_000, 200_000),
			new RunningResistanceSpec(1_500, 20, 3.0));
	}

	/**
	 * **DB BR 101 的真实牵引曲线**（用户 2026-09-23 给的数据表）：两段式
	 * `F = min(300 kN, 6.4 MW / v)` —— 76.8 km/h 折点、92 km/h 给 250 kN、220 km/h 给约 104.7 kN。
	 *
	 * <p>真车表里没有独立的弱磁段（恒功率一路到 220 km/h），所以 `fieldWeakeningSpeedMps` 保持 0；
	 * 第三段是给"弱磁点较早"的其它车底留的接口，这里单独钉一条。</p>
	 */
	@Test
	public void theRealBr101CurveMatchesTheDatasheet() {
		final TractionSpec br101 = new TractionSpec(300_000, 6_400_000);
		assertEquals(21.333333333, br101.breakpointMetersPerSecond(), 1e-6, "折点 = 6.4 MW / 300 kN = 76.8 km/h");
		assertEquals(300_000, br101.effortN(1.0, 0), 1e-6);
		assertEquals(300_000, br101.effortN(1.0, 76.8 / 3.6), 1e-3, "76.8 km/h 仍然给 300 kN");
		assertEquals(250_000, br101.effortN(1.0, 92 / 3.6), 1_000, "92 km/h 给约 250 kN（真车表取整）");
		// 220 km/h = 61.11 m/s ⇒ 6.4 MW / 61.11 = 104.7 kN（真车表给 95～104.7 kN）
		assertEquals(104_727, br101.effortN(1.0, 220 / 3.6), 200, "顶速 220 km/h 给约 104.7 kN");
		// 黏着需求：F_max / (m g) = 300 kN / (84 t · 9.81) ≈ 0.364（干燥轨面峰值 0.35–0.38 的边缘）
		assertEquals(0.364, 300_000 / (84_000 * 9.81), 0.002, "300 kN 起步几乎占满干燥轨面的黏着峰值");

		// 第三段（可选）：超过弱磁点后 F ∝ 1/v²（以弱磁点为锚，曲线连续）
		final TractionSpec weakField = new TractionSpec(300_000, 6_400_000, 40.0);
		final double atAnchor = weakField.effortN(1.0, 40.0);
		final double doubled = weakField.effortN(1.0, 80.0);
		assertEquals(atAnchor / 4, doubled, 1e-6, "弱磁段 F ∝ 1/v²：速度翻倍 ⇒ 力降到 1/4");
	}

	/**
	 * **轮轨黏着**（规格 §4）：牵引力的物理上限是 {@code μ_eff · m · g}，不是电机能给多少。
	 *
	 * <p>BR 101 的 300 kN 需要 {@code μ = 0.364} —— 干轨峰值 0.35–0.38 刚好够；湿轨 0.20 只能传 165 kN；
	 * 落叶 0.07 只剩 58 kN；撒砂把湿轨的 0.20 提到 0.26 ⇒ 214 kN。</p>
	 */
	@Test
	public void adhesionLimitsTheTractiveEffort() {
		final double normal = 84_000 * TrainPhysics.GRAVITY;
		final ConsistType br101 = new ConsistType("br101", "BR 101", ConsistType.ControlMode.THREE_HANDLE, 97, 11,
			220, 84_000, 1.16, 300_000, 6_400_000, 150_000, 220_000,
			1350, 28, 2.76, 0.37, false, 0.1, 0.4, 0.15, 0.1, 0, null);
		// 干轨：μ=0.37 ⇒ 上限 305 kN > 电机给的 300 kN ⇒ 满牵引可用（与真车"300 kN 几乎占满黏着"一致）
		assertTrue(br101.getPhysics().adhesionLimitedEffortN() > 300_000, "干轨要能把 300 kN 传下去");
		assertEquals(300_000, br101.getPhysics().tractiveEffortN(1.0, 0), 1e-6, "干轨满手柄 = 电机上限");
		// 湿轨：μ=0.20 ⇒ 只能传 165 kN
		final ConsistType wet = new ConsistType("wet", "wet", ConsistType.ControlMode.THREE_HANDLE, 97, 11,
			220, 84_000, 1.16, 300_000, 6_400_000, 150_000, 220_000,
			1350, 28, 2.76, 0.20, false, 0.1, 0.4, 0.15, 0.1, 0, null);
		assertEquals(0.20 * normal, wet.getPhysics().tractiveEffortN(1.0, 0), 1e-6, "湿轨：力被黏着截住");
		// 撒砂 ×1.30 ⇒ 湿轨 0.26
		final ConsistType wetSanding = new ConsistType("wet_sand", "wet+sand", ConsistType.ControlMode.THREE_HANDLE, 97, 11,
			220, 84_000, 1.16, 300_000, 6_400_000, 150_000, 220_000,
			1350, 28, 2.76, 0.20, true, 0.1, 0.4, 0.15, 0.1, 0, null);
		assertEquals(0.26 * normal, wetSanding.getPhysics().tractiveEffortN(1.0, 0), 1e-6, "撒砂增粘 ×1.30");

		// μ–s 曲线：微滑线性升到峰值（s_crit = 2%），之后负斜率衰减
		final AdhesionSpec dry = AdhesionSpec.DRY;
		assertEquals(0.37, dry.muAtSlip(0.02), 1e-9, "峰值滑差处的 μ 就是 μ_max");
		assertEquals(0.185, dry.muAtSlip(0.01), 1e-9, "微滑段线性：半滑差 ⇒ 半黏着");
		assertTrue(dry.muAtSlip(0.06) < dry.muAtSlip(0.02), "打滑区负斜率：滑差越大黏着越小");
		assertEquals(0.02, dry.slipForEffort(0.37 * normal, normal), 1e-9, "要满黏着就得在峰值滑差上");
		assertEquals(Double.MAX_VALUE, dry.slipForEffort(0.5 * normal, normal), 1e-9, "超过 μ_max 的力：传不下去");
	}

	@Test
	public void constantEffortThenConstantPower() {
		final TrainPhysics p = br101();
		final TractionSpec t = p.getTraction();
		final double breakpoint = t.breakpointMetersPerSecond();
		assertEquals(9.5, breakpoint, 1e-9, "折点 = P / F_max = 1.9 MW / 200 kN = 9.5 m/s (34 km/h)");

		// 折点以下：恒牵引力（力不随速度变）
		assertEquals(200_000, p.tractiveEffortN(1.0, 0), 1e-6);
		assertEquals(200_000, p.tractiveEffortN(1.0, breakpoint), 1e-6);
		// 折点以上：恒功率 ⇒ 力 × 速度 = 常数
		final double above = breakpoint * 3;
		assertEquals(1_900_000, p.tractiveEffortN(1.0, above) * above, 1e-3, "恒功率段 F·v 必须恒定");
		assertTrue(p.tractiveEffortN(1.0, above) < p.tractiveEffortN(1.0, breakpoint), "过折点后牵引力要下降");

		// 手柄比例同时缩放力与功率 ⇒ 折点不变、整条曲线等比缩放
		assertEquals(0.5 * p.tractiveEffortN(1.0, 2), p.tractiveEffortN(0.5, 2), 1e-9);
		assertEquals(0.5 * p.tractiveEffortN(1.0, 25), p.tractiveEffortN(0.5, 25), 1e-9);
		assertEquals(0, p.tractiveEffortN(0, 20), 1e-12, "手柄关闭 = 没有牵引力");
	}

	@Test
	public void massAndRotatingFactorScaleTheAcceleration() {
		final TrainPhysics heavy = new TrainPhysics(164_000, 1.06, new TractionSpec(200_000, 1_900_000), BrakeSpec.NONE, RunningResistanceSpec.NONE);
		final TrainPhysics light = new TrainPhysics(82_000, 1.06, new TractionSpec(200_000, 1_900_000), BrakeSpec.NONE, RunningResistanceSpec.NONE);
		// 同样的牵引力，质量翻倍 ⇒ 加速度减半
		assertEquals(light.tractionAccelerationMps2(1, 2) / 2, heavy.tractionAccelerationMps2(1, 2), 1e-12);
		// λ = 1.06 ⇒ 加速度小 6%（相对 λ=1）
		final TrainPhysics noRotating = new TrainPhysics(82_000, 1.0, new TractionSpec(200_000, 1_900_000), BrakeSpec.NONE, RunningResistanceSpec.NONE);
		assertEquals(noRotating.tractionAccelerationMps2(1, 2) / 1.06, light.tractionAccelerationMps2(1, 2), 1e-12);
		// 无动力车节：质量参与、牵引为 0
		assertEquals(0, new TrainPhysics(40_000, 1.06, TractionSpec.POWERLESS, BrakeSpec.NONE, RunningResistanceSpec.NONE).tractionAccelerationMps2(1, 10), 1e-12);
	}

	@Test
	public void brakingIsForceOverInertiaAndEmergencyIsStronger() {
		// 先看纯力-质量关系（不带阻力），再看阻力对制动的"帮忙"
		final TrainPhysics pure = new TrainPhysics(82_000, 1.06, TractionSpec.POWERLESS, new BrakeSpec(120_000, 200_000), RunningResistanceSpec.NONE);
		final double inertia = pure.effectiveMassKg();
		assertEquals(120_000 / inertia, pure.serviceBrakeDecelerationMps2(1, 0), 1e-12, "常用制动 = 力 / (λm)");
		assertEquals(60_000 / inertia, pure.serviceBrakeDecelerationMps2(0.5, 0), 1e-12, "缸压比例线性");
		assertEquals(200_000 / inertia, pure.emergencyDecelerationMps2(0), 1e-12);
		assertTrue(pure.emergencyDecelerationMps2(0) > pure.serviceBrakeDecelerationMps2(1, 0), "EB 必须强于全常用");
		// 配置写反（EB < 常用）时取常用为下限，绝不出现"EB 更软"
		assertEquals(120_000, new BrakeSpec(120_000, 50_000).emergencyForceN(), 0, "紧急制动力不会低于常用");
		// 阻力在制动时**帮忙**（方向相同）
		final TrainPhysics p = br101();
		assertTrue(p.serviceBrakeDecelerationMps2(1, 30) > p.serviceBrakeDecelerationMps2(1, 0), "运行阻力与制动力同向");
	}

	@Test
	public void gradientForceHelpsDownhillAndHurtsUphill() {
		final TrainPhysics p = br101();
		final double inertia = p.effectiveMassKg();
		// 10‰ 上坡：附加力为负，量级 = λm·g·0.01
		assertEquals(-inertia * 9.80665 * 0.01, p.gradeForceN(10), 1e-6);
		assertTrue(p.gradeForceN(-10) > 0, "下坡帮助前进");
		// 10‰ 坡道对 82 t 的车 ≈ 0.098 m/s²
		assertEquals(0.0980665, -p.gradeForceN(10) / inertia, 1e-6);
	}

	@Test
	public void balancingSpeedIsWhereTractionMeetsResistance() {
		final TrainPhysics p = br101();
		final double balancing = p.balancingSpeedMps2(1.0);
		assertTrue(balancing > 9.5, "平衡速度应在恒功率段内（否则这列车跑不到额定功率）：" + balancing);
		assertEquals(0, p.tractiveEffortN(1, balancing) - p.resistanceForceN(balancing), 5.0, "平衡点上牵引≈阻力");
		assertTrue(p.balancingSpeedMps2(0.5) < balancing, "手柄越小平衡速度越低");
		assertEquals(0, p.balancingSpeedMps2(0), 0, "手柄关闭没有平衡点");
	}

	/**
	 * **无损迁移**：旧口径 {@code A₀ / v_bp / 阻力} 换算成力以后，曲线逐点相同。
	 *
	 * <p>这就是 notes/235 的配置迁移配方：{@code F_max = m₀·A₀}、{@code P = F_max·v_bp}、
	 * {@code F_制动 = m₀·a_制动}、{@code A_N/B_N/C_N = m₀·A/B/C}，参考质量取 {@code m₀}。</p>
	 */
	@Test
	public void legacyAccelerationParametersConvertWithoutChangingTheCurve() {
		final double legacyA0 = 0.55;
		final double legacyBreakpointKmh = 80;
		final double legacyService = 1.0;
		final double legacyEmergency = 1.8;
		final double legacyResA = 0.02;
		final double legacyResB = 0.0005;
		final double legacyResC = 0.00002;
		final double m0 = 60_000;
		final double lambda = 1.0;

		final TrainPhysics migrated = new TrainPhysics(m0, lambda,
			TractionSpec.fromLegacy(legacyA0, m0, legacyBreakpointKmh),
			new BrakeSpec(legacyService * m0, legacyEmergency * m0),
			new RunningResistanceSpec(legacyResA * m0, legacyResB * m0, legacyResC * m0));

		// 旧模型（已删除的 MmtrPhysics 口径）：a = A0·ratio（折点前）/ A0·ratio·v_bp/v（折点后）− 阻力
		final double vBp = legacyBreakpointKmh / 3.6;
		for (final double v : new double[]{0, 1, 5, vBp, vBp * 2, 30, 44}) {
			for (final double ratio : new double[]{0.02, 0.5, 1.0}) {
				final double legacyTraction = legacyA0 * ratio * (v > vBp ? vBp / v : 1);
				final double legacyResistance = legacyResA + legacyResB * v + legacyResC * v * v;
				assertEquals(legacyTraction - legacyResistance, migrated.tractionAccelerationMps2(ratio, v), 1e-12,
					"v=" + v + " ratio=" + ratio + " 的牵引净加速度必须逐点相同");
			}
			assertEquals(legacyService + legacyResA + legacyResB * v + legacyResC * v * v,
				migrated.serviceBrakeDecelerationMps2(1, v), 1e-12, "常用制动（含阻力）逐点相同：v=" + v);
		}
	}
}
