package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.VehicleCar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **载重百分比 → 逐车质量**（notes/274 片 4，用户口径 2026-09-26：
 * 「载重需要有一个百分比来表示，并用于计算新的质量」，并「分客车和货车」）。
 *
 * <p>口径：车底给**载重能力** {@code payloadKg}，车卡给**载重比例** {@code loadRatio ∈ [0,1]}，
 * 逐车质量 {@code m = 整备 + 比例 × 载重能力}；载重**只影响质量**（惯性、黏着法向力），
 * 制动锚不动（空重车调整阀两段动作明确不做，设计文档 §6）。</p>
 *
 * <p>本类钉四件事：① 比例进质量与惯性、进而进加速度与制动距离；② 制动锚不随载重变（有意的取舍，
 * 免得以后有人以为它是 bug）；③ 单节编组那条路也折载重；④ 没写 {@code payloadKg} 的老配置逐位不变。</p>
 */
public final class MmtrTrailerLoadTests {

	/** 一台 BR101 + 若干节货车：只有货车的载重比例在变。 */
	private static final String LOAD_JSON = "{"
		+ "\"carTypeIds\":{\"br101\":\"loco\",\"wagon\":\"wagon\"},"
		+ "\"consistTypes\":["
		+ "{\"id\":\"loco\",\"name\":\"loco\",\"controlMode\":\"THREE_HANDLE\",\"powerNotches\":97,\"brakeNotches\":11,"
		+ "\"maxSpeedKmh\":220,\"massKg\":84000,\"rotatingMassFactor\":1.16,"
		+ "\"maxTractiveEffortN\":300000,\"maxPowerW\":6400000,"
		+ "\"brakeWeightTonnes\":120,\"emergencyBrakeWeightTonnes\":168,"
		+ "\"resistanceAN\":1350,\"resistanceBN\":28,\"resistanceCN\":2.76},"
		+ "{\"id\":\"wagon\",\"name\":\"wagon\",\"controlMode\":\"NOTCHED\",\"massKg\":24000,\"payloadKg\":56000,"
		+ "\"rotatingMassFactor\":1.04,\"maxTractiveEffortN\":0,\"maxPowerW\":0,"
		+ "\"brakeWeightTonnes\":56,\"emergencyBrakeWeightTonnes\":78,"
		+ "\"resistanceAN\":700,\"resistanceBN\":12,\"resistanceCN\":1.1}]}";

	private static VehicleCar car(String vehicleId, boolean powered, double loadRatio) {
		final VehicleCar car = new VehicleCar(vehicleId, 16, 5, 0, -16 / 3.0, 16 / 3.0, 0, 0, powered, "");
		car.setMmtrLoadRatio(loadRatio);
		return car;
	}

	private static ConsistType consist(ConsistTypeRegistry registry, double loadRatio) {
		final List<VehicleCar> cars = new ArrayList<>();
		cars.add(car("br101", true, 0));
		cars.add(car("wagon", false, loadRatio));
		return MmtrComposition.fromVehicleCars(cars, registry, registry.get("loco")).toConsistType("consist:test");
	}

	/** ① 载重进质量、进惯性、进加速度与制动距离 —— 空车与重车是两条曲线。 */
	@Test
	public void theLoadRatioScalesMassInertiaAccelerationAndBrakingDistance() {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(LOAD_JSON);
		final ConsistType empty = consist(registry, 0);
		final ConsistType half = consist(registry, 0.5);
		final ConsistType full = consist(registry, 1);

		assertEquals(108_000, empty.getMassKg(), 1e-9, "空车：84 t 机车 + 24 t 货车");
		assertEquals(136_000, half.getMassKg(), 1e-9, "半载：24 t + 0.5×56 t = 52 t");
		assertEquals(164_000, full.getMassKg(), 1e-9, "满载：24 t + 56 t = 80 t");

		final double emptyAccel = empty.getPhysics().tractionAccelerationMps2(1, 0);
		final double halfAccel = half.getPhysics().tractionAccelerationMps2(1, 0);
		final double fullAccel = full.getPhysics().tractionAccelerationMps2(1, 0);
		System.out.println(String.format("[TEST] 起步加速度：空车 %.3f / 半载 %.3f / 满载 %.3f m/s²（质量 %.0f/%.0f/%.0f t）",
			emptyAccel, halfAccel, fullAccel, empty.getMassKg() / 1000, half.getMassKg() / 1000, full.getMassKg() / 1000));
		assertTrue(fullAccel < halfAccel && halfAccel < emptyAccel, "越重越慢，必须单调");
		assertTrue(fullAccel < emptyAccel * 0.75, "满载比空车明显慢（质量比 164/108），实际 " + (fullAccel / emptyAccel));

		final double emptyDistance = brakingDistanceM(empty);
		final double fullDistance = brakingDistanceM(full);
		System.out.println(String.format("[TEST] 100 km/h 全常用制动距离：空车 %.0f m / 满载 %.0f m", emptyDistance, fullDistance));
		assertTrue(fullDistance > emptyDistance * 1.3,
			"同一份闸、质量多一半 ⇒ 距离明显变长，实际 " + (fullDistance / emptyDistance) + " 倍");
	}

	/**
	 * ② **载重不动制动锚**（有意的取舍）：这是"载重只影响质量"的直接推论 ——
	 * 真车用空重车调整阀（P/G 位）补偿，那条明确不做（设计文档 §6）。把取舍钉在用例里，
	 * 免得以后有人看到"重车没更强"就去"修"出一个半套的空重车调整。
	 */
	@Test
	public void theLoadDoesNotChangeTheBrakeAnchor() {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(LOAD_JSON);
		final ConsistType empty = consist(registry, 0);
		final ConsistType full = consist(registry, 1);
		assertEquals(empty.getBrake().getServiceForceN(), full.getBrake().getServiceForceN(), 1e-9,
			"常用制动锚与载重无关（同一套制动装置）");
		assertEquals(empty.getBrake().getEmergencyForceN(), full.getBrake().getEmergencyForceN(), 1e-9);
		assertEquals(empty.getTraction().getMaxTractiveEffortN(), full.getTraction().getMaxTractiveEffortN(), 1e-9,
			"牵引力也不随载重变（力上限，不是加速度上限）");
	}

	/** ③ `withLoad` 把载重折进质量：比例 0 原样返回、比例 1 后整备质量就是总质量、载重能力归零。 */
	@Test
	public void withLoadFoldsTheLoadIntoTheMass() {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(LOAD_JSON);
		final ConsistType wagon = registry.get("wagon");
		assertEquals(56_000, wagon.getPayloadKg(), 1e-9, "载重能力写在车底上");
		assertEquals(24_000, wagon.loadedMassKg(0), 1e-9);
		assertEquals(80_000, wagon.loadedMassKg(1), 1e-9);
		assertEquals(80_000, wagon.loadedMassKg(1.5), 1e-9, "比例超范围按 1 夹住");
		assertEquals(24_000, wagon.loadedMassKg(-1), 1e-9, "负比例按 0 夹住");

		assertSame(wagon, wagon.withLoad(0), "比例 0 ⇒ 原对象（零开销、零回归）");
		final ConsistType loaded = wagon.withLoad(1);
		assertEquals(80_000, loaded.getMassKg(), 1e-9, "折进去之后整备质量 = 总质量");
		assertEquals(0, loaded.getPayloadKg(), 1e-9, "载重能力归零（已经折进质量了）");
		assertEquals(1.04 * 80_000, loaded.getPhysics().effectiveMassKg(), 1e-9, "惯性质量跟着走");
		assertEquals(80_000 * org.mtr.core.mmtr.physics.TrainPhysics.GRAVITY, loaded.getPhysics().normalForceN(), 1e-6,
			"黏着法向力也跟着走（片 5 的截断用的就是它）");
		assertFalse(loaded.canPull(), "折载重不会凭空给出牵引");
	}

	/** ④ 随包配置：客车与货车两族都有载重能力，且货车真的从 cargotest 映射过来。 */
	@Test
	public void theShippedConfigHasBothFamiliesWithTheirOwnPayload() throws IOException {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(Files.readString(findConfig(), StandardCharsets.UTF_8));
		final ConsistType coach = registry.get("p1_trailer");
		assertNotNull(coach);
		assertEquals(10_000, coach.getPayloadKg(), 1e-9, "客车载重能力（估算：满员 + 行李 ≈ 10 t）");

		assertEquals("freight_wagon", registry.typeIdForCar("cargotest"), "货车模型 cargotest 必须映射到货车车底");
		final ConsistType wagon = registry.get("freight_wagon");
		assertNotNull(wagon, "carTypeIds 指向的车底必须真的存在");
		assertFalse(wagon.canPull(), "货车是拖车");
		assertEquals(56_000, wagon.getPayloadKg(), 1e-9, "货车载重能力 56 t");
		assertEquals(24_000, wagon.getMassKg(), 1e-9, "货车整备 24 t");
		assertTrue(wagon.loadedMassKg(1) > wagon.getMassKg() * 3, "满载比空车重三倍以上 ⇒ 空/重车是两种车");

		// 一列 BR101 + 2×cargotest：空车 vs 满载
		final List<VehicleCar> carsEmpty = new ArrayList<>();
		final List<VehicleCar> carsFull = new ArrayList<>();
		carsEmpty.add(car("br101", true, 0));
		carsFull.add(car("br101", true, 0));
		for (int i = 0; i < 2; i++) {
			carsEmpty.add(car("cargotest", false, 0));
			carsFull.add(car("cargotest", false, 1));
		}
		final ConsistType empty = MmtrComposition.fromVehicleCars(carsEmpty, registry, registry.get("br101_three_handle")).toConsistType("empty");
		final ConsistType full = MmtrComposition.fromVehicleCars(carsFull, registry, registry.get("br101_three_handle")).toConsistType("full");
		System.out.println(String.format("[TEST] BR101+2×货车：空车 %.0f t（起步 %.3f m/s²）/ 满载 %.0f t（起步 %.3f m/s²）",
			empty.getMassKg() / 1000, empty.getPhysics().tractionAccelerationMps2(1, 0),
			full.getMassKg() / 1000, full.getPhysics().tractionAccelerationMps2(1, 0)));
		assertEquals(132_000, empty.getMassKg(), 1e-9);
		assertEquals(244_000, full.getMassKg(), 1e-9);
		assertTrue(full.getPhysics().tractionAccelerationMps2(1, 0) < empty.getPhysics().tractionAccelerationMps2(1, 0) * 0.65,
			"满载起步明显更肉");
	}

	/** ⑤ 没写 `payloadKg` 的老配置：任何比例都不改变质量（零回归）。 */
	@Test
	public void configsWithoutPayloadIgnoreTheLoadRatio() {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(LOAD_JSON.replace(",\"payloadKg\":56000", ""));
		final ConsistType wagon = registry.get("wagon");
		assertEquals(0, wagon.getPayloadKg(), 1e-9);
		assertEquals(wagon.getMassKg(), wagon.loadedMassKg(1), 1e-9, "没有载重能力 ⇒ 比例再大也不改质量");
		assertSame(wagon, wagon.withLoad(1));
		assertEquals(consist(registry, 0).getMassKg(), consist(registry, 1).getMassKg(), 1e-9,
			"整列等效车底也不动（老配置逐位不变）");
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

	/** 100 km/h **全常用**纯制动距离（m）：{@code v²/2a}，{@code a = 常用制动锚 / 整列惯性质量}。 */
	private static double brakingDistanceM(ConsistType type) {
		final double speedMps = 100 / 3.6;
		final double deceleration = type.getBrake().getServiceForceN() / type.getPhysics().effectiveMassKg();
		return speedMps * speedMps / (2 * deceleration);
	}
}
