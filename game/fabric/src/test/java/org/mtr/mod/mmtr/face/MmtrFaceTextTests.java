package org.mtr.mod.mmtr.face;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 模板与**过滤器**的判据（notes/359）。
 *
 * <p>为什么现在才补：共享向量 {@code conformance/logic.json} 覆盖的是**算子**，过滤器一直只被
 * "清单一致性"那条用例间接看着 —— 于是 {@code pad:N} 与 {@code num:N} 共用同一个钳位解析、
 * {@code pad:8} 只补到 4 位这种错，**自检和用例都抓不住**，是前端移植时逐条对照才发现的。
 * 这个文件就是把过滤器也钉住（发现一条错就补一组用例，这是本仓库的老规矩）。</p>
 */
public final class MmtrFaceTextTests {

	private static final Map<String, Object> DATA = Map.of(
		"pid", Map.of("service", "101"),
		"speedKmh", 12.345,
		"hold", ""
	);

	@Test
	public void templatesResolveDottedPaths() {
		assertEquals("班次 101", MmtrFaceText.resolve("班次 {pid.service}", DATA));
	}

	/** 取不到 = 空串（不是 null、不是报错）—— 与快照"取不到就不放进去"是同一条口径。 */
	@Test
	public void missingFieldsRenderAsEmpty() {
		assertEquals("侧牌「」", MmtrFaceText.resolve("侧牌「{pid.nope}」", DATA));
	}

	@Test
	public void bracesCanBeEscaped() {
		assertEquals("{pid.service}", MmtrFaceText.resolve("{{pid.service}}", DATA));
	}

	@Test
	public void anUnclosedBraceStaysLiteralText() {
		assertEquals("开往 {pid", MmtrFaceText.resolve("开往 {pid", DATA));
	}

	@Test
	public void numbersDoNotGetADotZeroSuffix() {
		assertEquals("12", MmtrFaceText.resolve("{speedKmh|int}", DATA), "12.345 取整是 12");
		assertEquals("13", MmtrFaceText.resolve("{speedKmh|int}", Map.of("speedKmh", 12.6)), "是四舍五入，不是截断");
	}

	@Test
	public void numKeepsTheAskedDecimals() {
		assertEquals("12.35", MmtrFaceText.resolve("{speedKmh|num:2}", DATA));
		assertEquals("12", MmtrFaceText.resolve("{speedKmh|num:0}", DATA));
	}

	/**
	 * ★ {@code pad:N} 要补到 **N** 位（曾经与 {@code num:N} 共用"钳到 0..4"的解析，{@code pad:8} 只补 4 位）。
	 */
	@Test
	public void padFillsToTheRequestedWidth() {
		assertEquals("0000101", MmtrFaceText.resolve("{pid.service|pad:7}", DATA), "补到 7 位 —— 这条就是那个 bug 的判据");
		assertEquals("101", MmtrFaceText.resolve("{pid.service|pad:2}", DATA), "已经够长就不动它");
		assertEquals("101", MmtrFaceText.resolve("{pid.service|pad:0}", DATA), "0 位 = 不补");
	}

	@Test
	public void defaultReplacesAnEmptyValue() {
		assertEquals("正常", MmtrFaceText.resolve("{hold|default:正常}", DATA));
		assertEquals("有值", MmtrFaceText.resolve("{hold|default:正常}", Map.of("hold", "有值")));
	}

	@Test
	public void filtersChainLeftToRight() {
		assertEquals("x5", MmtrFaceText.resolve("x{pid.service|pad:5|len}", DATA), "先 pad:5 得到 00101，再 len = 5");
	}

	@Test
	public void textFiltersDoWhatTheySay() {
		assertEquals("ABC", MmtrFaceText.resolve("{v|upper}", Map.of("v", "abc")));
		assertEquals("abc", MmtrFaceText.resolve("{v|trim}", Map.of("v", "  abc  ")));
		assertEquals("abc", MmtrFaceText.resolve("{v|lower}", Map.of("v", "ABC")));
	}

	/** 不认识的过滤器**原样返回**（并提示一次）——比静默换空串好：作者至少还能看到字。 */
	@Test
	public void anUnknownFilterLeavesTheValueAlone() {
		assertEquals("101", MmtrFaceText.resolve("{pid.service|nope}", DATA));
	}
}
