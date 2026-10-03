package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.brake.BrakeCar;
import org.mtr.core.mmtr.brake.BrakeCommand;
import org.mtr.core.mmtr.brake.BrakeSystem;
import org.mtr.core.mmtr.physics.AdhesionSpec;
import org.mtr.core.mmtr.physics.PneumaticBrakeSpec;
import org.mtr.core.mmtr.physics.TrainPhysics;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **逐车黏着截断 + 简化 WSP/撒砂**（notes/275 片 5，用户口径 2026-09-26：WSP 与撒砂"过于复杂简化即可"）。
 *
 * <p>片 5 之前只有**编组级**截断（{@code TrainPhysics.adhesionLimitedBrakingForceN}：整列 {@code m·g}
 * × "说话那节车"的 μ）。后果是空车那点轴重被重车的轴重"担保"了 —— 分客车/货车两族之后更明显，
 * 因为**黏着档是车底属性**（盘型闸与闸瓦的 μ 不同、撒砂只有机车有）。</p>
 *
 * <p>本类钉五件事：① 轻车被**它自己**的轴重钳住（编组级上限这时根本不咬）；② 载重移动截断点
 * （空车截得狠、重车不截）；③ WSP 开着钳到上限、关掉断崖到动摩擦（决定 2 的简化口径）；
 * ④ 撒砂 = 车底一个布尔（×1.3，只有它低于 C-K 曲线时才真的有用 ⇒ 湿轨）；⑤ 没有逐车数据的车
 * 逐位不变（零回归）。</p>
 */
public final class MmtrTrailerPhysicsTests {

	private static final double DT_S = 0.05;
	private static final double G = TrainPhysics.GRAVITY;

	/** 气压口径（BR101 一族，逐车截断不需要它特殊，只要缸压能建满）。 */
	private static final String AIR_JSON = "{\"consistTypes\":[{\"id\":\"bar\",\"controlMode\":\"THREE_HANDLE\","
		+ "\"massKg\":84000,\"maxTractiveEffortN\":300000,\"maxPowerW\":6400000,"
		+ "\"brakeWeightTonnes\":120,\"emergencyBrakeWeightTonnes\":168,"
		+ "\"airPipeNominalBar\":5.0,\"airPipeChargedBar\":5.2,\"airPipeFullServiceBar\":3.5,\"airPipeEmergencyBar\":3.0,"
		+ "\"airPipeTargetsBar\":\"5.2,4.6,4.4,4.2,4.05,3.9,3.8,3.7,3.6,3.5,3.0\","
		+ "\"airPipeChargeBarPerSecond\":0.35,\"airPipeDischargeBarPerSecond\":0.85,"
		+ "\"brakeCylinderMaxBar\":3.8,\"brakeCylinderSpringBar\":0.30,\"brakeCylinderEmergencyBar\":4.2,"
		+ "\"brakeCylinderApplyBarPerSecond\":1.30,\"brakeCylinderReleaseBarPerSecond\":0.95,"
		+ "\"distributorRatio\":2.5333,\"distributorSensitivityBar\":0.20,"
		+ "\"wspEnabled\":true,\"wheelSlipMu\":0.12}]}";

	private static PneumaticBrakeSpec spec(String json) {
		final PneumaticBrakeSpec brakes = ConsistTypeRegistry.parse(json).get("bar").getHandles().getBrakes();
		assertTrue(brakes != null, "夹具必须带气压口径");
		return brakes;
	}

	private static PneumaticBrakeSpec wsp(boolean enabled) {
		return spec(AIR_JSON.replace("\"wspEnabled\":true", "\"wspEnabled\":" + enabled));
	}

	/** 满常用（诉求 0.9 = 全常用档）跑 30 s 到稳态，返回制动系统。 */
	private static BrakeSystem steady(List<BrakeCar> cars, PneumaticBrakeSpec consistSpec) {
		final BrakeSystem system = new BrakeSystem(cars, consistSpec);
		final BrakeCommand command = BrakeCommand.ofRatio(0.9, 0, false);
		for (int i = 0; i < 600; i++) {
			system.step(command, 0, 0, DT_S);
		}
		return system;
	}

	/**
	 * ① **轻车被它自己的轴重钳住** —— 而编组级那一层在这条场景里根本不咬。
	 *
	 * <p>机车 84 t（干轨 0.37，锚 102.2 kN）+ 货车 24 t（弱黏着档 0.07，锚 40.9 kN）：
	 * 货车自己的上限只有 {@code 0.07×24 t×g ≈ 16.5 kN}；整列上限却是
	 * {@code 0.3315×108 t×g ≈ 351 kN} ≫ 诉求 143 kN —— 只有逐车那一层拦得住。</p>
	 */
	@Test
	public void aLightWagonIsCappedByItsOwnAxleLoad() {
		final PneumaticBrakeSpec brakes = spec(AIR_JSON);
		final double wagonAnchorN = 40_900;
		final BrakeSystem system = steady(List.of(
			new BrakeCar(102_200, 138_400, true, brakes, 20, 84_000, new AdhesionSpec(0.37, 0.02, false)),
			new BrakeCar(wagonAnchorN, 56_000, false, brakes, 16, 24_000, new AdhesionSpec(0.07, 0.02, false))), brakes);

		final double wagonLimitN = 0.07 * 24_000 * G;
		System.out.println(String.format("[TEST] 机车 %.1f kN（不截）+ 货车 %.1f kN（被自己的轴重钳到 %.1f kN）",
			102_200 / 1000.0, wagonAnchorN / 1000, wagonLimitN / 1000));
		assertEquals(102_200 + wagonLimitN, system.getPneumaticForceN(), 300,
			"整列气制动力 = 机车那一份 + 货车被自己轴重钳住的那一份");
		assertTrue(system.isPerCarAdhesionLimited(), "必须报告被截");
		assertEquals(wagonAnchorN - wagonLimitN, system.getAdhesionCappedN(), 300, "被截掉的量 = 货车锚 − 它的上限");

		final double consistLimitN = new AdhesionSpec(0.37, 0.02, false).brakingLimitN((84_000 + 24_000) * G, 0);
		assertTrue(consistLimitN > 102_200 + wagonAnchorN,
			"编组级上限（" + Math.round(consistLimitN / 1000) + " kN）比诉求还高 ⇒ 这一层没拦，拦的是逐车那一层");
	}

	/** ② **载重移动截断点**（片 4 的法向力 + 片 5 的截断合起来）：空车截得狠、重车不截。 */
	@Test
	public void theLoadRatioMovesThePerCarAdhesionLimit() {
		final PneumaticBrakeSpec brakes = spec(AIR_JSON);
		final AdhesionSpec weak = new AdhesionSpec(0.07, 0.02, false);
		final double anchorN = 40_900;

		final BrakeSystem empty = steady(List.of(new BrakeCar(anchorN, 56_000, false, brakes, 16, 24_000, weak)), brakes);
		final BrakeSystem loaded = steady(List.of(new BrakeCar(anchorN, 56_000, false, brakes, 16, 80_000, weak)), brakes);
		System.out.println(String.format("[TEST] 同一份闸、弱黏着档：空车 %.1f kN（截掉 %.1f）/ 满载 %.1f kN（截掉 %.1f）",
			empty.getPneumaticForceN() / 1000, empty.getAdhesionCappedN() / 1000,
			loaded.getPneumaticForceN() / 1000, loaded.getAdhesionCappedN() / 1000));

		assertEquals(0.07 * 24_000 * G, empty.getPneumaticForceN(), 300, "空车（24 t）：被自己的轴重钳住");
		assertEquals(anchorN, loaded.getPneumaticForceN(), 300, "满载（80 t）：轴重大到闸能传下去 ⇒ 不截");
		assertEquals(0, loaded.getAdhesionCappedN(), 1e-6);
		assertTrue(loaded.getPneumaticForceN() > empty.getPneumaticForceN() * 2, "同一份闸，重车出力大得多");
	}

	/**
	 * ③ **WSP 的简化口径**（决定 2）：开着 ⇒ 钳到 {@code μ_brake·m·g}（真车点刹保持峰值微滑）；
	 * 关掉 ⇒ 断崖到动摩擦 {@code wheelSlipMu·m·g}（抱死，力反而更小）。
	 *
	 * <p>夹具故意把闸配强（200 kN 锚 / 24 t 车）：不然诉求根本到不了上限，截断不会发生。</p>
	 */
	@Test
	public void wspOnClampsToTheLimitWhileWspOffDropsToSlidingFriction() {
		final AdhesionSpec dry = new AdhesionSpec(0.37, 0.02, false);
		final double massKg = 24_000;
		final BrakeSystem on = steady(List.of(new BrakeCar(200_000, 260_000, false, wsp(true), 16, massKg, dry)), wsp(true));
		final BrakeSystem off = steady(List.of(new BrakeCar(200_000, 260_000, false, wsp(false), 16, massKg, dry)), wsp(false));

		final double limitN = dry.brakingLimitN(massKg * G, 0);
		System.out.println(String.format("[TEST] 210 kN 诉求 @24 t：WSP 开 %.1f kN / WSP 关 %.1f kN（动摩擦 0.12）",
			on.getPneumaticForceN() / 1000, off.getPneumaticForceN() / 1000));
		assertEquals(limitN, on.getPneumaticForceN(), 300, "WSP 开：钳到 μ_brake·m·g");
		assertEquals(0.12 * massKg * G, off.getPneumaticForceN(), 300, "WSP 关：断崖到动摩擦 0.12·m·g");
		assertTrue(off.getPneumaticForceN() < on.getPneumaticForceN() * 0.5, "抱死之后力反而更小");
	}

	/**
	 * ④ **撒砂 = 车底一个布尔**（×1.3 折进 {@code usableMuMax}）：只有在它低于 C-K 曲线时才真的有用
	 * —— 干轨 0 km/h 的 C-K 是 0.3315，撒砂后的 0.481 取小还是 0.3315；**湿轨** 0.20 才会被抬到 0.26。
	 */
	@Test
	public void sandingRaisesThePerCarLimitOnlyWhereTheSurfaceIsTheBindingTerm() {
		final AdhesionSpec dry = new AdhesionSpec(0.37, 0.02, false);
		final AdhesionSpec drySanded = new AdhesionSpec(0.37, 0.02, true);
		assertEquals(dry.brakingLimitN(100_000, 0), drySanded.brakingLimitN(100_000, 0), 1e-9,
			"干轨上取小的是 C-K 曲线（0.3315）⇒ 撒砂不改上限");

		final AdhesionSpec wet = new AdhesionSpec(0.20, 0.02, false);
		final AdhesionSpec wetSanded = new AdhesionSpec(0.20, 0.02, true);
		assertEquals(0.20, wet.usableMuMax(), 1e-9);
		assertEquals(0.26, wetSanded.usableMuMax(), 1e-9, "×1.30 的增粘折在 usableMuMax 里");
		assertEquals(1.3, wetSanded.brakingLimitN(100_000, 0) / wet.brakingLimitN(100_000, 0), 1e-6,
			"湿轨上撒砂真的抬上限");
	}

	/** ⑤ 没有逐车黏着数据的车（单节等效车底、老夹具）**逐位不变**：由控制器的编组级截断兜底。 */
	@Test
	public void carsWithoutPerCarAdhesionDataKeepTheOldBehaviour() {
		final PneumaticBrakeSpec brakes = spec(AIR_JSON);
		final BrakeCar oldStyle = new BrakeCar(200_000, 260_000, false, brakes);
		assertFalse(oldStyle.hasPerCarAdhesion(), "载重与黏着档都没给 ⇒ 逐车截断不生效");
		assertEquals(Double.MAX_VALUE, oldStyle.brakingAdhesionLimitN(0), 1e-9, "没有逐车数据就是「不截」");

		final BrakeSystem system = steady(List.of(oldStyle), brakes);
		assertEquals(200_000, system.getPneumaticForceN(), 1e-6, "力原样给出去（编组级截断在控制器那一层）");
		assertFalse(system.isPerCarAdhesionLimited());
		assertEquals(0, system.getAdhesionCappedN(), 1e-9);
	}
}
