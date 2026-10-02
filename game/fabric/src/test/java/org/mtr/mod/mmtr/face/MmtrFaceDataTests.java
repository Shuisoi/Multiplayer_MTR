package org.mtr.mod.mmtr.face;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据快照的判据（notes/359 §数据接口）：分层、派生、"取不到就不放进去"这三条口径。
 *
 * <p>这套口径是整个面系统的地基：条件 {@code when}、模板 {@code {field}}、{@code missing} 三种用法
 * 对"这个量没有"必须给出**同一个**答案，否则同一块牌上"没画出来"的原因会有三种解释，
 * 作者查不出来。</p>
 */
public final class MmtrFaceDataTests {

	@Test
	public void dottedFieldsLandInNestedMaps() {
		final MmtrFaceData data = MmtrFaceData.of(source(Map.of(
			"pid.service", "00101",
			"pid.terminus", "海山",
			"speed", 0.05
		)));
		assertEquals("海山", data.get("pid.terminus"), "点号字段要能按路径取");
		assertEquals("00101", data.get("pid.service"));
		assertTrue(data.asMap().get("pid") instanceof Map, "pid.* 应当收在一层子 map 里（面文档的 var 就是按它走）");
		assertTrue(data.names().contains("pid.*"), "names() 把分组报成一个 pid.*：" + data.names());
	}

	/** ★ 空串 = 没有：引擎里"未知"就写空串，把它当有值会让 when 条件写不下去。 */
	@Test
	public void anEmptyStringCountsAsAbsent() {
		final MmtrFaceData data = MmtrFaceData.of(source(Map.of(
			"pid.service", "00101",
			"pid.terminus", ""
		)));
		assertNull(data.get("pid.terminus"), "空串不该进快照（否则「回库趟」与「有终点」分不开）");
		assertFalse(data.describe().contains("terminus"), "describe() 里也不该出现：" + data.describe());
	}

	/** 派生量：speedKmh = speed × 3600（引擎的 speed 单位是米/毫秒）。 */
	@Test
	public void theDerivedSpeedIsKmh() {
		final MmtrFaceData data = MmtrFaceData.of(source(Map.of("speed", 0.05)));
		assertEquals(180D, MmtrFaceLogic.asNumber(data.get("speedKmh")), 1.0E-9, "0.05 m/ms = 180 km/h");
	}

	/** ★ 反例：没有 speed 就没有 speedKmh（不拿 0 冒充"我知道速度是 0"）。 */
	@Test
	public void noSpeedMeansNoDerivedSpeed() {
		final MmtrFaceData data = MmtrFaceData.of(source(Map.of("pid.service", "00101")));
		assertNull(data.get("speedKmh"), "缺料就不放派生量 —— 0 与「不知道」不同");
	}

	/** 登记表之外的字段名不进快照（问也不问 —— 免得"某处偷偷塞进来的量"变成事实接口）。 */
	@Test
	public void fieldsOutsideTheRegistryAreIgnored() {
		final MmtrFaceData data = MmtrFaceData.of(source(Map.of("secret.custom", 42, "pid.service", "00101")));
		assertNull(data.get("secret.custom"));
		assertEquals("00101", data.get("pid.service"));
	}

	/** 空源 ⇒ 空快照（不崩、不乱给默认值）。 */
	@Test
	public void anEmptySourceGivesAnEmptySnapshot() {
		final MmtrFaceData data = MmtrFaceData.of(MmtrFaceSource.empty());
		assertTrue(data.names().isEmpty(), "空源不该有字段：" + data.names());
		assertNull(data.get("pid.service"));
		assertEquals("", data.describe());
	}

	/** describe() 是重画签名的原料：同样的数据必须给同一段文本，且真的把值写进去。 */
	@Test
	public void describeIsDeterministicAndCarriesTheValues() {
		final Map<String, Object> values = Map.of("pid.service", "00101", "speed", 0.05);
		assertEquals(MmtrFaceData.of(source(values)).describe(), MmtrFaceData.of(source(values)).describe());
		final String described = MmtrFaceData.of(source(values)).describe();
		assertTrue(described.contains("speed=0.05"), described);
		assertTrue(described.contains("speedKmh=180"), described);
		assertTrue(described.contains("pid.service=00101"), described);
	}

	private static MmtrFaceSource source(Map<String, Object> values) {
		final Map<String, Object> copy = new HashMap<>(values);
		return copy::get;
	}
}
