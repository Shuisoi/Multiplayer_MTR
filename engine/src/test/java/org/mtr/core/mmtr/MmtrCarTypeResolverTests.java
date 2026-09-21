package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.VehicleCar;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 按车选车底（多车底）：{@link MmtrCarTypeResolver} 的真值表 + {@code carTypeIds} 的解析。
 *
 * <p>这一条的现场意义：一个维度只挂一个缺省车底 ⇒ BR101 的三根手柄要生效就得把**整个维度**改成三手柄物理。
 * 现在车型自己说话（{@code carTypeIds: {"br101": "br101_three_handle"}}），别的车一个字节不变。</p>
 */
public final class MmtrCarTypeResolverTests {

	private static final String JSON = "{"
		+ "  \"defaultConsistTypeId\": \"emu_8_notched\","
		+ "  \"carTypeIds\": { \"br101\": \"br101_three_handle\", \"ghost\": \"does_not_exist\" },"
		+ "  \"consistTypes\": ["
		+ "    {\"id\":\"emu_8_notched\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,\"maxSpeedKmh\":120},"
		+ "    {\"id\":\"br101_three_handle\",\"controlMode\":\"THREE_HANDLE\",\"maxSpeedKmh\":160},"
		+ "    {\"id\":\"wagon_air\",\"controlMode\":\"AIR_BRAKE\",\"powerNotches\":1,\"brakeNotches\":3,\"maxSpeedKmh\":100}"
		+ "  ]"
		+ "}";

	private static ConsistTypeRegistry registry() {
		return ConsistTypeRegistry.parse(JSON);
	}

	private static VehicleCar car(String modelId, boolean powered, String declaredTypeId) {
		return new VehicleCar(modelId, 20, 3, 100, 5, 15, 0.5, 0.5, powered, declaredTypeId);
	}

	@Test
	public void registryParsesTheCarTypeMapping() {
		final ConsistTypeRegistry registry = registry();
		assertEquals("br101_three_handle", registry.typeIdForCar("br101"));
		assertNull(registry.typeIdForCar("emu_8"), "没配过的车型 = null（调用方退回缺省）");
		assertNull(registry.typeIdForCar(null), "车型 id 为 null 不能炸");
		assertNull(registry.typeIdForCar("ghost"), "指向不存在的车底 = 配置写错，忽略而不是整份失败");
		assertEquals(3, registry.all().size(), "三份车底照常全部解析");
	}

	@Test
	public void theLeadingPoweredCarDecidesTheConsistType() {
		final ConsistTypeRegistry registry = registry();
		// 一节 BR101 动力车 + 一节没配过的挂车 ⇒ 本车是三手柄
		final MmtrCarTypeResolver.Resolution resolution = MmtrCarTypeResolver.resolve(
			List.of(car("br101", true, ""), car("flatcar", false, "")), registry, registry.getDefaultId());
		assertEquals("br101_three_handle", resolution.consistTypeId());
		assertFalse(resolution.key().isEmpty(), "解析出来了就必须有重解判据");
	}

	@Test
	public void aResolvedWagonLosesToALaterPoweredCar() {
		final ConsistTypeRegistry registry = registry();
		// 挂车（非动力）声明了 wagon_air，后面才是 BR101 动力车 ⇒ 机车说话
		final MmtrCarTypeResolver.Resolution resolution = MmtrCarTypeResolver.resolve(
			List.of(car("flatcar", false, "wagon_air"), car("br101", true, "")), registry, registry.getDefaultId());
		assertEquals("br101_three_handle", resolution.consistTypeId(), "一列车只有一套纵向动力学，必须由动力车定");
	}

	@Test
	public void aDeclaredCarTypeBeatsTheModelMapping() {
		final ConsistTypeRegistry registry = registry();
		// 车厢自己声明了 wagon_air（动力车）⇒ 声明的优先于按车型的映射
		final MmtrCarTypeResolver.Resolution resolution = MmtrCarTypeResolver.resolve(
			List.of(car("br101", true, "wagon_air")), registry, registry.getDefaultId());
		assertEquals("wagon_air", resolution.consistTypeId());
	}

	@Test
	public void anUnconfiguredConsistFallsBackToTheDimensionDefault() {
		final ConsistTypeRegistry registry = registry();
		final MmtrCarTypeResolver.Resolution resolution = MmtrCarTypeResolver.resolve(
			List.of(car("saf101", true, ""), car("saf101", false, "")), registry, registry.getDefaultId());
		assertEquals("emu_8_notched", resolution.consistTypeId(), "没配过的车零回归");
		assertEquals("", resolution.key(), "用缺省时没有'说话的车'");
	}

	@Test
	public void couplingThatChangesTheSpeakingCarChangesTheKey() {
		final ConsistTypeRegistry registry = registry();
		final String before = MmtrCarTypeResolver.resolve(List.of(car("br101", true, "")), registry, registry.getDefaultId()).key();
		final String after = MmtrCarTypeResolver.resolve(List.of(car("br101", true, ""), car("br101", false, "")), registry, registry.getDefaultId()).key();
		final String other = MmtrCarTypeResolver.resolve(List.of(car("flatcar", true, "")), registry, registry.getDefaultId()).key();
		assertEquals(before, after, "同一节说话车 ⇒ 不重解（免得每次连挂都把气制动状态清掉）");
		assertNotEquals(before, other, "换了说话车 / 换成没配过的车 ⇒ 判据必须变，否则会一直跑挂上之前那套参数");
	}

	@Test
	public void aCarWithNoTypeAndNoRegistryIsSafe() {
		final MmtrCarTypeResolver.Resolution resolution = MmtrCarTypeResolver.resolve(List.of(car("br101", true, "")), null, "emu_8_notched");
		assertEquals("emu_8_notched", resolution.consistTypeId(), "注册表缺失也要退回缺省");
		assertEquals("", resolution.key());
		assertEquals("emu_8_notched", MmtrCarTypeResolver.resolve(null, registry(), "emu_8_notched").consistTypeId(), "空车列不能炸");
	}

	@Test
	public void theCompositionAlsoHonoursTheModelMapping() {
		final ConsistTypeRegistry registry = registry();
		final ConsistType fallback = registry.get(registry.getDefaultId());
		final MmtrComposition composition = MmtrComposition.fromVehicleCars(
			List.of(car("br101", true, ""), car("flatcar", false, "")), registry, fallback);
		assertNotNull(composition);
		final Map<String, String> expected = Map.of("car0", "br101_three_handle", "car1", "emu_8_notched");
		for (int i = 0; i < composition.size(); i++) {
			assertEquals(expected.get("car" + i), composition.unit(i).getType().getId(), "车节 " + i + " 的车底");
		}
	}
}
