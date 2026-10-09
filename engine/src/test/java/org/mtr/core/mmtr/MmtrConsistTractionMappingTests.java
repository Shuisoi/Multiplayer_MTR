package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.VehicleCar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * **牵引到底是谁的**：编组里每节车各自算多少牵引力（notes/247）。
 *
 * <p>现场事故：BR 101 + 2×p1 被当成**三台机车** —— 因为 p1 既没声明车底、{@code carTypeIds} 里也没有它，
 * 于是三节车都借了"说话那节车"（BR 101）的车底，牵引 900 kN / 19.2 MW、常用制动 450 kN 全是三倍，
 * 起步约 3 m/s²、145 km/h 时仍有约 1.5 m/s²（用户口径"加速度很不正常"；日志速度序列反推的 1.49 m/s²
 * 与"三台机车"的模型吻合到 5% 以内）。</p>
 *
 * <p>本类钉三件事：① 借来的车底不给牵引；② 真·拖车车底（{@code p1_trailer}）不给牵引；
 * ③ 随包配置下这一列车的合力就是"一台机车 + 两节挂车的质量"。用例读的是**随包发布的真值配置**。</p>
 */
public final class MmtrConsistTractionMappingTests {

	private static final String LOCAL_LOCO_JSON = "{\"carTypeIds\":{\"br101\":\"loco\"},\"consistTypes\":["
		+ "{\"id\":\"loco\",\"massKg\":84000,\"rotatingMassFactor\":1.16,\"maxTractiveEffortN\":300000,\"maxPowerW\":6400000,"
		+ "\"serviceBrakeForceN\":150000,\"emergencyBrakeForceN\":210000,"
		+ "\"resistanceAN\":1350,\"resistanceBN\":28,\"resistanceCN\":2.76}]}";

	private static VehicleCar car(String vehicleId, boolean powered) {
		return new VehicleCar(vehicleId, 16, 5, 0, -16 / 3.0, 16 / 3.0, 0, 0, powered, "");
	}

	/** 一节**显式声明**了动力位的车（世界文件 / {@code --unpowered} / {@code vehicle spawn} 那条路）。 */
	private static VehicleCar declaredCar(String vehicleId, boolean powered) {
		final VehicleCar car = car(vehicleId, powered);
		car.setMmtrPoweredDeclared(true);
		return car;
	}

	/** 借来的车底只借质量/制动/阻力，**不借牵引**（否则 N 节同型车 = N 倍功率）。 */
	@Test
	public void borrowedConsistTypesDoNotBringTheirTraction() {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(LOCAL_LOCO_JSON);
		final MmtrComposition train = MmtrComposition.fromVehicleCars(
			List.of(car("br101", true), car("p1", true), car("p1", true)), registry, registry.get("loco"));
		assertNotNull(train);
		final ConsistType aggregate = train.toConsistType("consist:br101+p1+p1");
		assertEquals(300_000, aggregate.getTraction().getMaxTractiveEffortN(), 1e-9, "牵引只有机车那一份，不是三份");
		assertEquals(6_400_000, aggregate.getTraction().getMaxPowerW(), 1e-9, "功率同上");
		assertEquals(252_000, aggregate.getMassKg(), 1e-9, "借来的质量照旧进物理（宁可重，不可快）");
		assertEquals(450_000, aggregate.getBrake().getServiceForceN(), 1e-9, "制动按车相加（挂车也有闸）");
	}

	/** 整列**一节都没配**车底时（维度缺省兜底）：第一节借牵引，其余只借质量 —— 加节数不再翻功率。 */
	@Test
	public void anUnmappedConsistKeepsOneUnitsTractionNoMatterTheCarCount() {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse("{\"consistTypes\":["
			+ "{\"id\":\"loco\",\"massKg\":84000,\"rotatingMassFactor\":1.16,\"maxTractiveEffortN\":300000,\"maxPowerW\":6400000,"
			+ "\"serviceBrakeForceN\":150000,\"emergencyBrakeForceN\":210000}]}");
		final MmtrComposition train = MmtrComposition.fromVehicleCars(
			List.of(car("unknown", true), car("unknown", true), car("unknown", true)), registry, registry.get("loco"));
		assertNotNull(train);
		final ConsistType aggregate = train.toConsistType("consist:unknown×3");
		assertEquals(300_000, aggregate.getTraction().getMaxTractiveEffortN(), 1e-9, "三节未知车也只能有一份牵引");
		assertEquals(252_000, aggregate.getMassKg(), 1e-9, "三节的质量都算进来");
	}

	/** "说话的车"必须真能出力：被误标成 powered 的挂车排在机车前面时不许它说话。 */
	@Test
	public void theSpeakingCarMustBeAbleToPull() {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(LOCO_AND_TRAILER_JSON);
		assertEquals("loco", MmtrCarTypeResolver.resolve(List.of(car("p1", true), car("br101", true)), registry, null).consistTypeId(),
			"零牵引的车底不算操纵车底");
		assertEquals("trailer", MmtrCarTypeResolver.resolve(List.of(car("p1", false)), registry, null).consistTypeId(),
			"整车只有一节零牵引挂车时仍要解析出它（不能变成 null）");
	}

	/** 两型车底：能出力的 loco 与零牵引的 trailer（"说话的车"那族用例共用）。 */
	private static final String LOCO_AND_TRAILER_JSON = "{\"carTypeIds\":{\"p1\":\"trailer\",\"br101\":\"loco\"},\"consistTypes\":["
		+ "{\"id\":\"loco\",\"massKg\":84000,\"maxTractiveEffortN\":300000,\"maxPowerW\":6400000},"
		+ "{\"id\":\"trailer\",\"massKg\":40000,\"maxTractiveEffortN\":0,\"maxPowerW\":0}]}";

	// ---- notes/271 片 2：显式无动力 = 永不给牵引（挂车不能开）-------------------------------------

	/**
	 * **显式无动力的车列一份牵引都不给** —— 这就是"挂车不能开"。
	 *
	 * <p>与"没表态"的区别是片 1 立的三态：世界文件里的 {@code powered:false} 与 {@code --unpowered}
	 * 都是**显式声明**，于是它们不再享受"整列一节都没声明动力时第一节借牵引"的老兜底。加这个兜底原本是为了
	 * 让"用例里随手拼的车列"还能开（那些车不写 powered），而实机车列一旦写了 false 就该当真。</p>
	 */
	@Test
	public void anExplicitlyUnpoweredConsistGetsNoTractionAtAll() {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(LOCAL_LOCO_JSON);
		final MmtrComposition train = MmtrComposition.fromVehicleCars(
			List.of(declaredCar("br101", false), declaredCar("br101", false)), registry, registry.get("loco"));
		assertNotNull(train);
		final ConsistType aggregate = train.toConsistType("consist:unpowered×2");
		assertEquals(0, aggregate.getTraction().getMaxTractiveEffortN(), 1e-9, "显式无动力 ⇒ 牵引恒 0");
		assertEquals(0, aggregate.getTraction().getMaxPowerW(), 1e-9);
		assertFalse(aggregate.canPull(), "整列不能出力");
		assertEquals(168_000, aggregate.getMassKg(), 1e-9, "质量照旧按车求和（挂车不是没有重量）");

		// 准入层与物理层同源：**单节**显式无动力也不许掌权（单节不走等效车底那条路）
		assertFalse(MmtrCarTypeResolver.anyCarCanPull(List.of(declaredCar("br101", false)), registry, registry.get("loco")),
			"单节显式无动力 ⇒ 准入拒绝掌权");
		assertTrue(MmtrCarTypeResolver.anyCarCanPull(List.of(car("br101", false)), registry, registry.get("loco")),
			"没表态的老车列保持老行为（借一节牵引）");
	}

	/** 老夹具（没表态、powered=false）**保持旧行为**：整列最多一节借牵引。 */
	@Test
	public void anUndeclaredConsistKeepsTheLegacySingleBorrow() {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(LOCAL_LOCO_JSON);
		final MmtrComposition train = MmtrComposition.fromVehicleCars(
			List.of(car("br101", false), car("br101", false)), registry, registry.get("loco"));
		assertNotNull(train);
		assertEquals(300_000, train.toConsistType("consist:auto×2").getTraction().getMaxTractiveEffortN(), 1e-9,
			"没表态 ⇒ 老兜底照旧，且仍只有一节");
	}

	/** 显式无动力的机车**不许当"说话的车"**（否则它靠 ② 那一档把整列按满牵引跑）。 */
	@Test
	public void anExplicitlyUnpoweredLocoIsNotTheSpeakingCar() {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(LOCO_AND_TRAILER_JSON);
		final MmtrCarTypeResolver.Resolution resolution = MmtrCarTypeResolver.resolve(
			List.of(declaredCar("br101", false), car("p1", true)), registry, null);
		assertEquals("trailer", resolution.consistTypeId(),
			"前面那节机车被显式声明成无动力 ⇒ 不能让它的车底说话（挂车不能开）");
		assertEquals("loco", MmtrCarTypeResolver.resolve(List.of(car("br101", false), car("p1", true)), registry, null).consistTypeId(),
			"没表态（老数据）时保持旧行为：能出力的车底仍可当说话的车");
	}

	// ---- 随包配置：这一列车的合力到底是多少 ----------------------------------------------------------

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

	private static ConsistTypeRegistry shippedRegistry() throws IOException {
		return ConsistTypeRegistry.parse(Files.readString(findConfig(), StandardCharsets.UTF_8));
	}

	// ---- 编组长度 vs 制动距离（用户 2026-09-25 提问：挂车变多，制动距离会相应变长么？）----------------

	/**
	 * **同制动重量率（λ）的车挂多少节，制动距离几乎不变**。
	 *
	 * <p>为什么：UIC 制动重率是**按每节车自己的制动重量**折算力的（{@code F_i = m_equiv_i·(0.0065λ_i+0.12)}），
	 * 力和惯性**同比例**随车数涨，而 λ 是每节车自己的比值 ⇒ 减速度是"λ 的加权平均"，与编组长度无关。
	 * 三条用例：BR101 + 1 / 2 / 4 节 P1（λ≈140%）的 100 km/h 全常用制动距离几乎一样。</p>
	 */
	@Test
	public void addingEquallyBrakedCoachesDoesNotLengthenTheBrakingDistance() throws IOException {
		final ConsistTypeRegistry registry = shippedRegistry();
		final ConsistType one = consistWithCoaches(registry, 1);
		final ConsistType two = consistWithCoaches(registry, 2);
		final ConsistType four = consistWithCoaches(registry, 4);
		final double oneM = brakingDistanceM(one);
		final double twoM = brakingDistanceM(two);
		final double fourM = brakingDistanceM(four);
		System.out.println(String.format("[TEST] 100 km/h 全常用制动距离：1 节挂车 %.0f m / 2 节 %.0f m / 4 节 %.0f m", oneM, twoM, fourM));
		System.out.println(String.format("[TEST] 制动力 %.0f / %.0f / %.0f kN；惯性质量 %.0f / %.0f / %.0f t",
			one.getBrake().getServiceForceN() / 1000, two.getBrake().getServiceForceN() / 1000, four.getBrake().getServiceForceN() / 1000,
			one.getPhysics().effectiveMassKg() / 1000, two.getPhysics().effectiveMassKg() / 1000, four.getPhysics().effectiveMassKg() / 1000));
		assertEquals(370, oneM, 8, "BR101+1×p1 约 370 m");
		assertEquals(371, twoM, 8, "BR101+2×p1 约 371 m（现场那一列）");
		assertTrue(Math.abs(fourM - oneM) < 10, "同 λ 的车从 1 节挂到 4 节，距离只差几米，实际 " + (fourM - oneM) + " m");
		assertTrue(four.getMassKg() > two.getMassKg() && two.getMassKg() > one.getMassKg(), "但质量确实在涨");
		assertTrue(four.getBrake().getServiceForceN() > two.getBrake().getServiceForceN(), "制动力也同步在涨 ⇒ 比值不变");
	}

	/**
	 * **真正拉长制动距离的是"λ 更低的车"**（同一台机车拉 4 节 50% 货车 / 4 节无闸货车）。
	 *
	 * <p>注意最后那条断言：**无闸车必须显式写 {@code "serviceBrakeForceN": 0}** —— 只写
	 * {@code brakeWeightTonnes: 0} 会落到"没配就给 60 kN"的兜底，等于白借一份闸（本仓最恨的静默借车底）。</p>
	 *
	 * <p>另有**一半尚未建模**：真车长编组的**列车管传播 / 尾车滞后**（P5）—— "尾车晚几秒才有闸"那段
	 * 额外距离现在没有（全列同一时刻建压）。</p>
	 *
	 * <p><b>+0.12 常数落在"每节车"而不是"整列一次"</b>（规格模块五的 λ 是整列口径）：对**有闸**的车
	 * 两者差不到 2%（BR101+4×50%：196.5 kN 对 192.9 kN）；对**无闸**车我们的实现更物理 ——
	 * 无闸车一分力都不出（实测 1168 m），而"整列一次"的读法会白送它 0.12 m/s²（约 977 m）。</p>
	 */
	@Test
	public void lowerBrakedWeightCarsLengthenTheBrakingDistance() {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(WAGON_JSON);
		final ConsistType loco = registry.get("br101_three_handle");
		final double halfBraked = brakingDistanceM(MmtrComposition.fromVehicleCars(
			List.of(car("br101", true), car("w50", false), car("w50", false), car("w50", false), car("w50", false)), registry, loco)
			.toConsistType("consist:br101+4×w50"));
		final double unbraked = brakingDistanceM(MmtrComposition.fromVehicleCars(
			List.of(car("br101", true), car("w0", false), car("w0", false), car("w0", false), car("w0", false)), registry, loco)
			.toConsistType("consist:br101+4×w0"));
		System.out.println(String.format("[TEST] BR101+4×W50（λ=77.5%%）%.0f m；BR101+4×无闸货车（只有机车在刹）%.0f m", halfBraked, unbraked));
		assertTrue(halfBraked > 550 && halfBraked < 700, "50% 制动重量的货车把距离拉到 600 m 量级，实际 " + halfBraked);
		assertTrue(unbraked > 1000 && unbraked < 1300, "无闸货车 1100–1200 m（284 t 全靠机车那 102 kN），实际 " + unbraked);
		assertTrue(unbraked > halfBraked * 1.5, "闸越少越刹不住：无闸车必须明显比 50% 车更远");
		assertEquals(0, registry.get("w0").getBrake().getServiceForceN(), 1e-9,
			"无闸货车必须显式写 serviceBrakeForceN:0，否则会落到 60 kN 兜底");
	}

	/** BR101 + {@code coachCount} 节 p1 客车的等效车底（走随包配置）。 */
	private static ConsistType consistWithCoaches(ConsistTypeRegistry registry, int coachCount) {
		final java.util.ArrayList<VehicleCar> cars = new java.util.ArrayList<>();
		cars.add(car("br101", true));
		for (int i = 0; i < coachCount; i++) {
			cars.add(car("p1", false));
		}
		return MmtrComposition.fromVehicleCars(cars, registry, registry.get("br101_three_handle")).toConsistType("consist:test");
	}

	/** 100 km/h **全常用**（8 档）纯制动距离（m）：{@code v²/2a}，{@code a = 制动力 / 整列惯性质量}。 */
	private static double brakingDistanceM(ConsistType type) {
		final double speedMps = 100 / 3.6;
		final double deceleration = type.getBrake().getServiceForceN() / type.getPhysics().effectiveMassKg();
		return speedMps * speedMps / (2 * deceleration);
	}

	/** 货车测试用的车底：BR101（UIC 120/168）+ 50% 制动重量的货车 + 无闸货车。 */
	private static final String WAGON_JSON = "{"
		+ "\"carTypeIds\":{\"br101\":\"br101_three_handle\",\"w50\":\"w50\",\"w0\":\"w0\"},"
		+ "\"consistTypes\":["
		+ "{\"id\":\"br101_three_handle\",\"controlMode\":\"THREE_HANDLE\",\"massKg\":84000,\"rotatingMassFactor\":1.16,"
		+ "\"maxTractiveEffortN\":300000,\"maxPowerW\":6400000,\"brakeWeightTonnes\":120,\"emergencyBrakeWeightTonnes\":168},"
		+ "{\"id\":\"w50\",\"controlMode\":\"NOTCHED\",\"massKg\":50000,\"rotatingMassFactor\":1.06,"
		+ "\"maxTractiveEffortN\":0,\"maxPowerW\":0,\"brakeWeightTonnes\":25,\"emergencyBrakeWeightTonnes\":35},"
		+ "{\"id\":\"w0\",\"controlMode\":\"NOTCHED\",\"massKg\":50000,\"rotatingMassFactor\":1.06,"
		+ "\"maxTractiveEffortN\":0,\"maxPowerW\":0,\"serviceBrakeForceN\":0,\"emergencyBrakeForceN\":0}]}";

	@Test
	public void theShippedConfigGivesTheCoachItsOwnTrailerType() throws IOException {
		final ConsistTypeRegistry registry = shippedRegistry();
		assertEquals("br101_three_handle", registry.typeIdForCar("br101"));
		assertEquals("p1_trailer", registry.typeIdForCar("p1"), "p1 必须有车型 → 车底映射，否则它会借机车的车底");
		final ConsistType trailer = registry.get("p1_trailer");
		assertNotNull(trailer, "carTypeIds 指向的车底必须真的存在");
		assertFalse(trailer.canPull(), "拖车不能有牵引力/功率");
		assertTrue(trailer.getMassKg() > 0, "拖车质量必须写出来（否则会借机车的 84 t）");
		assertTrue(trailer.getMassKg() < registry.get("br101_three_handle").getMassKg(), "客车比机车轻");
		assertTrue(trailer.getBrake().getServiceForceN() > 0, "客车有自己的闸");
	}

	/**
	 * 端到端：BR 101 + 2×p1 这一列的合力与惯性 —— 这一条正是"进游戏加速度很不正常"的判据。
	 *
	 * <p>三台机车的错误模型：900 kN / 292 t → 3.1 m/s²；正确模型：300 kN / 182 t → 约 1.6 m/s²。</p>
	 */
	@Test
	public void theShippedBr101WithTwoCoachesAcceleratesLikeOneLocomotive() throws IOException {
		final ConsistTypeRegistry registry = shippedRegistry();
		final MmtrComposition train = MmtrComposition.fromVehicleCars(
			List.of(car("br101", true), car("p1", false), car("p1", false)), registry, registry.get("br101_three_handle"));
		assertNotNull(train);
		final ConsistType aggregate = train.toConsistType("consist:br101+p1+p1");
		assertEquals(164_000, aggregate.getMassKg(), 1e-9, "84 t 机车 + 2×40 t 客车");
		assertEquals(300_000, aggregate.getTraction().getMaxTractiveEffortN(), 1e-9, "只有机车出力");
		assertEquals(6_400_000, aggregate.getTraction().getMaxPowerW(), 1e-9);
		/*
		 * 制动力的口径在 notes/266 换成 UIC 制动重率（用户 2026-09-25）：逐车算再相加 ——
		 * BR101（120 t / 84 t ⇒ 102.2 kN）+ 2×p1（56 t / 40 t ⇒ 43.7 kN）= 189.5 kN。
		 * 旧口径是铭牌 150 + 2×70 = 290 kN，明显更强。
		 */
		assertEquals(189_500, aggregate.getBrake().getServiceForceN(), 1_000,
			"UIC 逐车：102.2 kN + 2×43.7 kN（旧口径 150 + 2×70 = 290 kN）");
		final double startAcceleration = aggregate.getPhysics().fullTractionAccelerationMps2(0);
		assertTrue(startAcceleration > 1.4 && startAcceleration < 1.8,
			"起步加速度必须落在 1.4–1.8 m/s²（一台机车拉两节客车），实际 " + startAcceleration);
		// 145 km/h（40.3 m/s）处的加速度：功率限制段，约 0.85 m/s²（三台机车模型会给约 1.6）
		final double atSpeed = aggregate.getPhysics().fullTractionAccelerationMps2(40.3);
		assertTrue(atSpeed > 0.7 && atSpeed < 1.0,
			"145 km/h 处约 0.85 m/s²，实际 " + atSpeed);
	}

	/**
	 * **设计值：0 → 100 km/h 要多久**（用户 2026-09-23「按计划 0-100 加速多长时间？」）。
	 *
	 * <p>满油门、干轨、平直道，用整列等效车底的力模型逐步积分（10 ms 步长，与实机同一套公式：
	 * {@code a = (min(F_max, P/v) − R(v)) / (λ·m)}，含黏着上限）。三个车底一起钉住，
	 * 以后谁改了牵引/质量/阻力，这张表立刻会说话。</p>
	 */
	@Test
	public void theDesignZeroToHundredTime() throws IOException {
		final ConsistTypeRegistry registry = shippedRegistry();
		final MmtrComposition train = MmtrComposition.fromVehicleCars(
			List.of(car("br101", true), car("p1", false), car("p1", false)), registry, registry.get("br101_three_handle"));
		assertNotNull(train);
		final ConsistType consist = train.toConsistType("consist:br101+p1+p1");
		final double seconds = integrateZeroTo100(consist.getPhysics());
		System.out.println("[TEST] 0→100 km/h：BR101+2×p1（164 t）= " + Math.round(seconds * 10) / 10.0 + " s");
		assertTrue(seconds > 16.5 && seconds < 19,
			"BR101+2×p1（164 t）按设计应在 17–19 s 之间（恒力矩段 1.63 m/s² 到 76.8 km/h，之后功率限制），实际 " + seconds);

		// 同一台机车不带客车：84 t / λ1.16 —— 说明"挂两节车"这件事在 0-100 上值多少秒
		final double locoOnly = integrateZeroTo100(registry.get("br101_three_handle").getPhysics());
		System.out.println("[TEST] 0→100 km/h：BR101 单车（84 t）= " + Math.round(locoOnly * 10) / 10.0 + " s");
		assertTrue(locoOnly > 8 && locoOnly < 11, "单车约 9.5 s，实际 " + locoOnly);
		assertTrue(seconds > locoOnly + 5, "两节客车必须明显拖慢起步（差 " + (seconds - locoOnly) + " s）");
	}

	/** 满油门 0→100 km/h 的积分耗时（s）：10 ms 定步长，含运行阻力与黏着上限。 */
	private static double integrateZeroTo100(org.mtr.core.mmtr.physics.TrainPhysics physics) {
		final double targetMps = 100 / 3.6;
		double speed = 0;
		double seconds = 0;
		while (speed < targetMps && seconds < 600) {
			speed += physics.fullTractionAccelerationMps2(speed) * 0.01;
			seconds += 0.01;
		}
		return seconds;
	}

	/**
	 * **带牵引力增速控制的 0 → 100 km/h**（notes/265）：上面那条是**设计曲线**（满牵引随时可用），
	 * 而真实口径里力要按 **30 kN/s** 爬（满档 10 s 才到 300 kN）—— 起步因此肉一点。
	 * 两个数并排钉住：以后谁动了增速或功率，这张表立刻会说话。
	 */
	@Test
	public void theTractionRampSlowsTheRealZeroToHundred() throws IOException {
		final ConsistTypeRegistry registry = shippedRegistry();
		final MmtrComposition train = MmtrComposition.fromVehicleCars(
			List.of(car("br101", true), car("p1", false), car("p1", false)), registry, registry.get("br101_three_handle"));
		assertNotNull(train);
		final ConsistType consist = train.toConsistType("consist:br101+p1+p1");
		final ConsistType loco = registry.get("br101_three_handle");
		assertEquals(30_000, loco.getHandles().getTractionRampNPerSecond(), 1e-9, "出厂配置就是 30 kN/s");

		final double consistSeconds = integrateZeroTo100ThroughController(consist);
		final double locoSeconds = integrateZeroTo100ThroughController(loco);
		System.out.println("[TEST] 0→100 km/h（含 30 kN/s 增速）：BR101+2×p1 = "
			+ Math.round(consistSeconds * 10) / 10.0 + " s（设计 " + Math.round(integrateZeroTo100(consist.getPhysics()) * 10) / 10.0
			+ " s）；单车 = " + Math.round(locoSeconds * 10) / 10.0 + " s（设计 "
			+ Math.round(integrateZeroTo100(loco.getPhysics()) * 10) / 10.0 + " s）");

		assertTrue(consistSeconds > integrateZeroTo100(consist.getPhysics()) + 0.5,
			"编组必须比设计曲线慢（力要爬 10 s），实际 " + consistSeconds + " s");
		assertTrue(locoSeconds > integrateZeroTo100(loco.getPhysics()) + 0.5,
			"单车必须比设计曲线慢，实际 " + locoSeconds + " s");
		// 实测：单车 14.4 s（设计 9.4 s）、BR101+2×p1 22.8 s（设计 17.7 s）—— 慢出来的那几秒就是
		// "10 s 才爬到满力"的代价；再慢就不对了（说明增速或功率被改坏）。
		assertTrue(locoSeconds < 15, "单车仍应在 15 s 内，实际 " + locoSeconds + " s");
		assertTrue(consistSeconds < 24, "编组仍应在 24 s 内，实际 " + consistSeconds + " s");
	}

	/** 满油门 0→100 km/h（走控制器 + {@link ConsistDynamics}，10 ms 步长）：含增速控制的**实际**耗时。 */
	private static double integrateZeroTo100ThroughController(ConsistType type) {
		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		final ControlState full = ControlState.zero().setDriveHandle(97);
		final double targetMps = 100 / 3.6;
		double speed = 0;
		double seconds = 0;
		while (speed < targetMps && seconds < 600) {
			speed = ConsistDynamics.step(speed, controller.compute(full, type, speed, 10), type, 10);
			seconds += 0.01;
		}
		return seconds;
	}

	/** 随着配置走的"手柄是上限"：现场那份配置必须是 true（用户 2026-09-23 的现场：false 时 50% 手柄也出满力）。 */
	@Test
	public void theShippedConfigLetsTheHandleCapTheAfbDemand() throws IOException {
		final ConsistType type = shippedRegistry().get("br101_three_handle");
		assertNotNull(type.getHandles());
		assertTrue(type.getHandles().isAfbUsesHandleAsCap(),
			"br101_three_handle 的 afbUsesHandleAsCap 必须是 true：手柄决定当前输出的力，AFB 只削减它");
	}

	/** 随着配置走的"手柄是上限"：现场那份配置必须是 true（用户 2026-09-23 的现场：false 时 50% 手柄也出满力）。 */
	@Test
	public void theTrailerPullsNothing() throws IOException {
		assertFalse(shippedRegistry().get("p1_trailer").canPull(), "拖车不给牵引");
	}

	/**
	 * **松闸之后牵引必须很快回来**（现场 2026-09-23：制动到停、手柄回运行位、油门推到 96，
	 * 日志里 20+ 秒 `牵引比=0%`、车一动不动）。
	 *
	 * <p>牵引联锁（缸压没排空 ⇒ 牵引为 0）本身是对的 —— 真车也不许带闸牵引。问题在**排空要多久**：
	 * 编组的气路速率取"最慢的一节"，而 `p1_trailer` 当初没写气路字段 ⇒ 落回缺省 0.1/s，
	 * 整列于是从机车的 0.25/s 被拖到 0.1/s，松闸后要十来秒才让出牵引。</p>
	 *
	 * <p><b>notes/265 之后要分两段量</b>：① 缸压排空（联锁放开）≤ 5 s —— 这一条是本用例原来的命题；
	 * ② 放开之后牵引力按**增速上限**（30 kN/s）从 0 爬，爬到 50% 力（150 kN）还要 5 s。
	 * 两者是两件事，所以分开钉：混在一起量会把"增速慢"误读成"松闸慢"。</p>
	 */
	@Test
	public void tractionComesBackQuicklyAfterReleasingTheBrake() throws IOException {
		final ConsistTypeRegistry registry = shippedRegistry();
		final MmtrComposition train = MmtrComposition.fromVehicleCars(
			List.of(car("br101", true), car("p1", false), car("p1", false)), registry, registry.get("br101_three_handle"));
		assertNotNull(train);
		final ConsistType aggregate = train.toConsistType("consist:br101+p1+p1");
		/*
		 * notes/376：整列"缓解速率"**不再是编组级的一个数** —— 逐车各自拿自己的 bar 口径
		 * （每节车一个 {@code PneumaticBrakeSpec}，缺省才沿用编组级）。这里钉的还是原来那条：
		 * 拖车必须**显式**写气路字段，别让缺省值把整列的缓解拖慢（旧口径的 0.25/s 归一化速率已删除）。
		 */
		assertEquals(0.95, aggregate.getBrakes().getCylinderReleaseBarPerSecond(), 1e-9,
			"编组口径 = 说话那节车（br101_three_handle）的缓解速率");
		for (final org.mtr.core.mmtr.brake.BrakeCar brakeCar : train.brakeCars()) {
			assertNotNull(brakeCar.spec(), "每节车都必须自带 bar 口径（拖车也不许借缺省值）");
			assertTrue(brakeCar.spec().getCylinderReleaseBarPerSecond() >= 0.9,
				"缓解速率不得被拖车的缺省值拖慢，实际 " + brakeCar.spec().getCylinderReleaseBarPerSecond());
		}

		final ThreeHandleDriveController controller = new ThreeHandleDriveController();
		for (int i = 0; i < 200; i++) {
			controller.compute(ControlState.zero().setBrakeNotch(9), aggregate, 0, 50);
		}
		assertEquals(1.0, controller.getBrakeCylinderPressure(), 1e-9, "先制动到满缸压");

		final ControlState release = ControlState.zero().setDriveHandle(96).setCruiseSpeedKmh(90);
		double seconds = 0;
		for (int i = 0; i < 600 && controller.getAppliedTractiveEffortN() <= 0; i++) {
			controller.compute(release, aggregate, 0, 50);
			seconds += 0.05;
		}
		System.out.println("[TEST] 松闸后牵引恢复耗时 " + Math.round(seconds * 100) / 100.0 + " s（缸压 "
			+ Math.round(controller.getBrakeCylinderPressure() * 1000) / 1000.0 + "）");
		assertTrue(seconds <= 5, "松闸后牵引必须在 5 s 内回来（缸压排空），实际 " + seconds + " s");

		// ② 放开之后**力**从 0 按增速上限爬：10 s 到满 300 kN（notes/265 的口径，与"松闸慢"无关）。
		final double firstTickForceN = 1_500;
		assertEquals(firstTickForceN, controller.getAppliedTractiveEffortN(), 1e-6,
			"联锁放开的第一拍 = 30 kN/s × 50 ms，实际 " + controller.getAppliedTractiveEffortN());
		for (int i = 0; i < 400; i++) {
			controller.compute(release, aggregate, 0, 50);
		}
		// 手柄 96 = 99%（不是满档 97）⇒ 目标力是 0.99 × 300 kN = 297 kN，爬到它就算到位。
		assertEquals(aggregate.getTraction().getMaxTractiveEffortN() * 0.99, controller.getAppliedTractiveEffortN(), 1.0,
			"再过 20 s 应当爬到该档位的满力（手柄 96 ⇒ 297 kN）");
	}
}
