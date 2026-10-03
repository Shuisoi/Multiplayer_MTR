package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.physics.BrakeSpec;
import org.mtr.core.mmtr.physics.PneumaticBrakeSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 制动手柄 11 个位置 ↔ **制动重量表 + 列车管级位表**（notes/246、notes/266）。
 *
 * <p>真值来源是随包发布的 {@code config-example/consist-types.json}：BR 101 车底的制动重量表为
 * P 84 t / G 70 t / R 120 t / R+E 168 t（相对 84 t 车底约 100% / 83% / 143% / 200%）。</p>
 *
 * <p><b>2026-09-25 起标定源换成 UIC 制动重率</b>（用户口径，见
 * {@code docs/01-设计/制动系统-气压与制动力模型-设计.md}）：制动重量**不再折成手写的牛顿**，而是
 * 由 {@code a = 0.0065λ + 0.12}、{@code F = m_equiv·a} 反推 ⇒ 常用 **102.2 kN**、紧急 **138.4 kN**
 * （旧的铭牌口径是 150/210 kN，明显更强）。气压级位表则按用户给的 5.2 / 4.6 / … / 3.5 bar。</p>
 */
public final class MmtrBrakeWeightTableTests {

	/** 制动重量（t）：R 位 = 全常用制动的锚。 */
	private static final double BRAKING_WEIGHT_R = 120;

	/** 制动重量（t）：R+E = 紧急制动的锚。 */
	private static final double BRAKING_WEIGHT_RE = 168;

	/** 制动重量（t）：P 位（本用例暂时只用于说明比例，未建模为独立档）。 */
	private static final double BRAKING_WEIGHT_P = 84;

	private static ConsistType br101() throws IOException {
		final String json = Files.readString(findConfig(), StandardCharsets.UTF_8);
		final ConsistType type = ConsistTypeRegistry.parse(json).get("br101_three_handle");
		assertNotNull(type, "config-example 里必须有 br101_three_handle");
		return type;
	}

	private static ThreeHandleSpec spec() throws IOException {
		final ThreeHandleSpec handles = br101().getHandles();
		assertNotNull(handles, "THREE_HANDLE 车底必须带操纵规格");
		return handles;
	}

	/** 从测试工作目录往上找 config-example（Gradle 的 cwd 是 engine 工程目录，别写死层数）。 */
	private static Path findConfig() {
		final Path start = Paths.get("").toAbsolutePath();
		for (Path directory = start; directory != null; directory = directory.getParent()) {
			final Path direct = directory.resolve("config-example").resolve("consist-types.json");
			if (Files.isRegularFile(direct)) {
				return direct;
			}
			final Path nested = directory.resolve("mmtr").resolve("config-example").resolve("consist-types.json");
			if (Files.isRegularFile(nested)) {
				return nested;
			}
		}
		throw new AssertionError("找不到 config-example/consist-types.json（起点 " + start + "）");
	}

	/** 制动重量表本身：R 相对车底 143%、R+E 200%（这是 λ 的来源，不再直接等于牛顿）。 */
	@Test
	public void theBrakingWeightsAreTheSourceOfTheForce() throws IOException {
		final ConsistType type = br101();
		final double massTonnes = type.getMassKg() / 1000.0;
		assertEquals(84, massTonnes, 1e-9, "BR101 整备 84 t");

		final double lambdaService = BRAKING_WEIGHT_R / massTonnes * 100;
		final double lambdaEmergency = BRAKING_WEIGHT_RE / massTonnes * 100;
		assertEquals(142.86, lambdaService, 0.01, "R 位 = 143%");
		assertEquals(200, lambdaEmergency, 1e-9, "R+E 位 = 200%");
		assertEquals(1.4, lambdaEmergency / lambdaService, 0.02, "R+E/R 的比值 ≈ 1.4");
		assertTrue(BRAKING_WEIGHT_R / BRAKING_WEIGHT_P > 1.4, "R 位相对车底 84 t 约 143%");

		// λ → a → F（UIC 经验式）
		assertEquals(1.0486, BrakeSpec.uicDecelerationMps2(BRAKING_WEIGHT_R, type.getMassKg()), 1e-3);
		assertEquals(1.42, BrakeSpec.uicDecelerationMps2(BRAKING_WEIGHT_RE, type.getMassKg()), 1e-3);
		assertEquals(102_200, type.getBrake().getServiceForceN(), 300, "常用 = m_equiv·1.0486 ≈ 102.2 kN");
		assertEquals(138_400, type.getBrake().getEmergencyForceN(), 300, "紧急 = m_equiv·1.42 ≈ 138.4 kN");
		assertEquals(1.354, type.getBrake().getEmergencyForceN() / type.getBrake().getServiceForceN(), 0.01,
			"紧急/常用 = 1.354（旧口径按制动重量比给的是 1.399）");
		// 旧铭牌口径的对照：UIC 明显软一档，这是用户 2026-09-25 的选择
		assertTrue(type.getBrake().getServiceForceN() < 150_000, "UIC 反推比铭牌 150 kN 软");
	}

	/** 随包配置里的**列车管级位表**：5.2 / 4.6（初制动）/ 逐级 / 3.5（全常用）/ 3.0（EB）。 */
	@Test
	public void theShippedPipeScheduleMatchesTheUserSpec() throws IOException {
		final PneumaticBrakeSpec air = spec().getBrakes();
		assertNotNull(air, "BR101 必须配气压口径（notes/266）");
		assertEquals(5.0, air.getNominalBar(), 1e-9, "定压 5.0 bar");
		assertEquals(5.2, air.getChargedBar(), 1e-9, "运行位停在 5.2 bar");
		assertEquals(4.6, air.targetPipeBar(1), 1e-9, "初制动 4.6 bar");
		assertEquals(3.5, air.getFullServiceBar(), 1e-9, "全常用 3.5 bar");
		assertEquals(3.0, air.getEmergencyBar(), 1e-9, "EB 快排 3.0 bar");
		assertEquals(3.8, air.getCylinderMaxBar(), 1e-9, "常用全制动缸压 3.8 bar");
		assertEquals(0.20, air.getDistributorSensitivityBar(), 1e-9, "分配阀灵敏限 0.2 bar");

		// 级位表必须与代码里的出厂表一致（配置与出厂值不能各说各话）
		assertEquals(PneumaticBrakeSpec.DEFAULT_TARGETS_BAR.length, air.getPositionCount(), "11 位手柄");
		for (int notch = 0; notch < PneumaticBrakeSpec.DEFAULT_TARGETS_BAR.length; notch++) {
			assertEquals(PneumaticBrakeSpec.DEFAULT_TARGETS_BAR[notch], air.targetPipeBar(notch), 1e-9,
				"位置 " + notch + " 的管压目标");
		}
		// 单调不减：档位越高管压越低（减压越多）
		for (int notch = 0; notch < air.getPositionCount() - 1; notch++) {
			assertTrue(air.targetPipeBar(notch) >= air.targetPipeBar(notch + 1),
				"管压必须随档位递减：位置 " + notch);
		}
		assertTrue(spec().isEmergencyPosition(10), "最后一位 = EB");
		assertFalse(spec().isEmergencyPosition(9), "8 档不是紧急");
	}

	/** 闸片与电制动的随包口径（notes/266 P2）。 */
	@Test
	public void theShippedFrictionAndElectricBrakeCurvesAreConfigured() throws IOException {
		final ConsistType type = br101();
		assertTrue(type.getBrake().isPadFadeEnabled(), "随包配置开着闸片衰减");
		assertEquals(0.39, type.getBrake().getPadMu0(), 1e-9);
		assertEquals(0.30, type.getBrake().padFrictionCoefficient(200 / 3.6), 1e-3, "200 km/h ⇒ μ=0.30");
		assertEquals(6_400_000, spec().getRheostaticMaxPowerW(), 1e-9, "电制动恒功率 6.4 MW");
		assertEquals(153.6, spec().rheostaticBreakpointMetersPerSecond() * 3.6, 0.2, "折点 153.6 km/h");
	}

	/** 拖车按自己的制动重量出力（P1 客车 56 t ⇒ λ=140% ⇒ 43.7 kN）。 */
	@Test
	public void theTrailerHasItsOwnBrakeWeight() throws IOException {
		final String json = Files.readString(findConfig(), StandardCharsets.UTF_8);
		final ConsistType trailer = ConsistTypeRegistry.parse(json).get("p1_trailer");
		assertNotNull(trailer);
		assertFalse(trailer.canPull(), "拖车不给牵引");
		assertEquals(43_700, trailer.getBrake().getServiceForceN(), 500, "P1 常用制动 ≈ 43.7 kN（旧配置 70 kN）");
		assertTrue(trailer.getBrake().getEmergencyForceN() > trailer.getBrake().getServiceForceN());
		assertNull(trailer.getHandles(), "拖车不是三手柄模式");
	}

	/** 旧的等比比例表仍在配置里（legacy 模式与镜像还读它），但**不再是三手柄的权威**。 */
	@Test
	public void theLegacyRatioTableIsStillShippedAndMonotone() throws IOException {
		final ThreeHandleSpec spec = spec();
		assertEquals(11, spec.getBrakePositionCount(), "运行/1A/1B/2…8/EB = 11 个位置");
		assertEquals(0.00, spec.brakeRatio(0), 1e-9, "运行 = 缓解");
		for (int position = 0; position < 11 - 1; position++) {
			assertTrue(spec.brakeRatio(position) <= spec.brakeRatio(position + 1), "制动力不得随位置下降：位置 " + position);
		}
		assertEquals(1.00, spec.brakeRatio(9), 2e-3, "8 档 = R = 全常用制动");
		for (int position = 0; position < ThreeHandleSpec.DEFAULT_BRAKE_RATIOS.length; position++) {
			assertEquals(ThreeHandleSpec.DEFAULT_BRAKE_RATIOS[position], spec.brakeRatio(position), 2e-3,
				"config-example 与 ThreeHandleSpec.DEFAULT_BRAKE_RATIOS 必须一致：位置 " + position);
		}
	}
}
