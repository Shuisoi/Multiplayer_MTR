package org.mtr.mod.mmtr.face;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 面文档 v2 的**边角判据**（notes/359 · F3）：页、{@code forEach} 的上下限、缺省、以及"显式
 * {@code null} 等于没写"这条规矩。
 *
 * <p>为什么还要在共享向量之外再写一层：共享向量是"两边都同意"的东西，**两边同意不等于对**——
 * 它只能防分家，防不了"两份实现一起想错"。这个文件按格式文档逐条写期望，
 * 与 {@code conformance/document.json} 互为对照。</p>
 */
public final class MmtrFaceDocumentV2Tests {

	/** 造一块面：{@code face} 是 {@code faces[name]} 的内容。 */
	private static MmtrFaceDocument face(String json) {
		final MmtrFaceDocument document = MmtrFaceDocument.fromAnchors("v2", "f", "{\"faces\":{\"f\":" + json + "}}");
		assertNotNull(document, "解析不出来：" + json);
		return document;
	}

	private static Map<String, Object> data(Object... pairs) {
		final Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			map.put(String.valueOf(pairs[i]), pairs[i + 1]);
		}
		return map;
	}

	/** ★ 显式 JSON {@code null} 一律当"没写这个键"（这条是共享向量逼出来的，见 {@code Mn`trFaceDocument.logic}）。 */
	@Test
	public void anExplicitNullMeansTheKeyIsNotThere() {
		assertTrue(face("{\"require\": null, \"elements\": []}").visible(Map.of()), "require: null ⇒ 没有门（不是「永远不画」）");
		assertFalse(face("{\"require\": {\"==\": [1, 2]}, \"elements\": []}").visible(Map.of()), "写了真的门还是要判");
		assertEquals(List.of("hi"), face("{\"elements\": [{\"type\": \"text\", \"text\": \"hi\", \"when\": null}]}").texts(Map.of()),
			"when: null ⇒ 总是画（不是「条件不成立」）");
		// pageExpr: null 不该把自动轮转关掉（那是"写了 pageExpr"才会有的行为）
		final MmtrFaceDocument rotating = face("{\"pageExpr\": null, \"pageSeconds\": 1, \"pages\": [{\"elements\": [{\"type\": \"rect\"}]}, {\"elements\": []}]}");
		assertTrue(rotating.autoRotating(), "pageExpr: null ⇒ 还是按时长自动轮转");
		assertEquals(1, rotating.pageIndex(Map.of(), 1500), "1.5 秒后到第 1 页");
	}

	@Test
	public void thePageClockWrapsAndTheFractionIsLanding() {
		final MmtrFaceDocument rotating = face("{\"pageSeconds\": 2, \"pages\": [{\"elements\": []}, {\"elements\": []}]}");
		assertEquals(0, rotating.pageIndex(Map.of(), 0));
		assertEquals(0, rotating.pageIndex(Map.of(), 1999));
		assertEquals(1, rotating.pageIndex(Map.of(), 2000));
		assertEquals(0, rotating.pageIndex(Map.of(), 4000), "两页 ⇒ 4 秒回到第 0 页");
		assertEquals(0.5, rotating.pageClockFraction(3000), 1.0E-9, "第 1 页的第 0.5 个周期");
		final MmtrFaceDocument manual = face("{\"pageExpr\": 1, \"pageSeconds\": 2, \"pages\": [{\"elements\": []}, {\"elements\": []}]}");
		assertFalse(manual.autoRotating(), "pageExpr 指定页 ⇒ 不自动转");
		assertEquals(0, manual.pageClockFraction(3000), 1.0E-9, "指定页 ⇒ 周期刚开头（翻牌机不翻滚，指定的那一面就是正面）");
	}

	@Test
	public void pagesWinOverTopLevelElementsAndSaysSo() {
		final MmtrFaceDocument document = face("{\"elements\": [{\"type\": \"text\", \"text\": \"被忽略\"}], \"pages\": [{\"elements\": [{\"type\": \"text\", \"text\": \"这一页\"}]}]}");
		assertTrue(document.ignoredTopLevelElements(), "同时写了 pages 与 elements ⇒ 要能被提示出来（画法里记一次账）");
		assertEquals(List.of("这一页"), document.texts(Map.of()));
		assertFalse(face("{\"elements\": [{\"type\": \"text\", \"text\": \"v1\"}]}").ignoredTopLevelElements(), "v1 文档不算「被忽略」");
	}

	/** 页自己的 require：不成立 ⇒ 这一页画 0 条（**不跳到下一页** —— 跳到哪一页是作者的语义）。 */
	@Test
	public void aPageGateEmptiesOnlyThatPage() {
		final MmtrFaceDocument document = face("{\"pageExpr\": 1, \"pages\": [{\"elements\": [{\"type\": \"text\", \"text\": \"A\"}]}, {\"require\": {\"==\": [1, 2]}, \"elements\": [{\"type\": \"text\", \"text\": \"B\"}]}]}");
		assertEquals(List.of(), document.texts(Map.of()), "第 1 页的门不成立 ⇒ 什么都不画");
		assertEquals(1, document.pageIndex(Map.of(), 0), "但页号仍然是 1");
	}

	@Test
	public void forEachExpandsEachItemWithItsOwnBindings() {
		final MmtrFaceDocument document = face("""
			{"vars": {"calls": ["海山", "鸥湾"]},
			 "elements": [{"type": "foreach", "var": "calls", "as": "stop", "index": "i",
			   "elements": [{"type": "text", "text": "{stop}-{i}"}]}]}""");
		assertEquals(List.of("海山-0", "鸥湾-1"), document.texts(Map.of()), "项与下标都要能读");
		assertEquals(2, document.plan(Map.of(), 0).size());
	}

	@Test
	public void forEachLimitsAndNonListsAreHonest() {
		final MmtrFaceDocument limited = face("""
			{"vars": {"calls": ["a", "b", "c"]},
			 "elements": [{"type": "foreach", "var": "calls", "limit": 2, "elements": [{"type": "text", "text": "{item}"}]}]}""");
		assertEquals(List.of("a", "b"), limited.texts(Map.of()), "limit 截断");
		assertEquals(List.of(), face("{\"elements\": [{\"type\": \"foreach\", \"var\": \"nope\", \"elements\": [{\"type\": \"text\", \"text\": \"x\"}]}]}").texts(Map.of()),
			"取不到列表 ⇒ 画 0 项（不是画一项空的）");
		assertEquals(List.of(), face("{\"vars\": {\"one\": 5}, \"elements\": [{\"type\": \"foreach\", \"var\": \"one\", \"elements\": [{\"type\": \"text\", \"text\": \"x\"}]}]}").texts(Map.of()),
			"标量 ⇒ 画 0 项");
		assertEquals(List.of(), face("{\"elements\": [{\"type\": \"foreach\", \"of\": {\"var\": \"nope\"}, \"elements\": [{\"type\": \"text\", \"text\": \"x\"}]}]}").texts(Map.of()),
			"of 求值为 null ⇒ 画 0 项");
	}

	/** 嵌套上限 4 层、一页上限 512 条：不可信内容不能靠"套娃"把一帧拖死。 */
	@Test
	public void forEachIsBounded() {
		final String nested = """
			{"vars": {"list": [1, 2]},
			 "elements": [{"type": "foreach", "var": "list", "elements": [
			   {"type": "foreach", "var": "list", "elements": [
			     {"type": "foreach", "var": "list", "elements": [
			       {"type": "foreach", "var": "list", "elements": [
			         {"type": "foreach", "var": "list", "elements": [{"type": "text", "text": "太深了"}]}]}]}]}]}]}""";
		// 第 5 层不再展开 ⇒ 那一层的 foreach 自己变成一条（画法会记一次账并跳过）
		assertEquals(16, face(nested).plan(Map.of(), 0).size(), "4 层各展开 2 项 ⇒ 第 5 层共 16 个 foreach：它们各算一条（不再展开，画法会记一次账）");

		final StringBuilder wide = new StringBuilder("{\"vars\": {\"list\": [");
		for (int i = 0; i < 300; i++) {
			wide.append(i).append(',');
		}
		wide.append("0]}, \"elements\": [{\"type\": \"foreach\", \"var\": \"list\", \"limit\": 256, \"elements\": [{\"type\": \"text\", \"text\": \"{item}\"}]}]}");
		assertEquals(256, face(wide.toString()).plan(Map.of(), 0).size(), "limit 上限 256");
	}

	@Test
	public void fpsIsClampedToTheDocumentedRange() {
		assertEquals(8, face("{\"elements\": []}").fps(), "没写 ⇒ 缺省 8");
		assertEquals(12, face("{\"fps\": 12, \"elements\": []}").fps());
		assertEquals(8, face("{\"fps\": 0, \"elements\": []}").fps());
		assertEquals(8, face("{\"fps\": 60, \"elements\": []}").fps());
		assertEquals(8, face("{\"fps\": 10000000000, \"elements\": []}").fps(), "1e10 撑爆 int ⇒ 也回落缺省（不是 0）");
		assertEquals(8, face("{\"fps\": -5, \"elements\": []}").fps());
	}

	@Test
	public void drumIsClampedAndFourSidedAtMost() {
		assertEquals(3, face("{\"drum\": {\"count\": 3}, \"elements\": []}").drum().count());
		assertEquals(2, face("{\"drum\": {\"count\": 0}, \"elements\": []}").drum().count(), "低于 2 钳到 2");
		assertEquals(8, face("{\"drum\": {\"count\": 99}, \"elements\": []}").drum().count(), "高于 8 钳到 8");
		assertEquals(0.25, face("{\"drum\": {}, \"elements\": []}").drum().turnFraction(), 1.0E-9);
		assertEquals(1, face("{\"drum\": {\"turnFraction\": 5}, \"elements\": []}").drum().turnFraction(), 1.0E-9);
		assertNull(face("{\"drum\": null, \"elements\": []}").drum(), "drum: null = 不是翻牌机");
		assertNull(face("{\"elements\": []}").drum());
	}

	@Test
	public void theAnimatedFlagOnlyLightsUpWhenSomethingMoves() {
		assertFalse(face("{\"elements\": [{\"type\": \"rect\"}]}").animated(), "静态文档不该带时间桶（F0 的老规矩）");
		assertTrue(face("{\"elements\": [{\"type\": \"text\", \"text\": \"x\", \"anim\": {\"kind\": \"blink\"}}]}").animated());
		assertTrue(face("{\"elements\": [{\"type\": \"foreach\", \"var\": \"nope\", \"elements\": [{\"type\": \"text\", \"text\": \"x\", \"anim\": {\"kind\": \"fade\"}}]}]}").animated(),
			"嵌套里的动画也算（否则那块牌永远不动）");
		assertTrue(face("{\"pageSeconds\": 3, \"pages\": [{\"elements\": []}, {\"elements\": []}]}").animated());
		assertFalse(face("{\"pageSeconds\": 0, \"pages\": [{\"elements\": []}, {\"elements\": []}]}").animated(), "不自动转 ⇒ 不带时间桶");
		assertFalse(face("{\"pageExpr\": 1, \"pageSeconds\": 3, \"pages\": [{\"elements\": []}, {\"elements\": []}]}").animated(), "指定页 ⇒ 不带时间桶");
	}

	/** ★ {@code "0"} 是哨兵（不是不透明黑）—— 文档里到处写着"0 = 用 textColor / 不铺底 / 全透明"。 */
	@Test
	public void zeroIsASentinelNotOpaqueBlack() {
		assertEquals(0, MmtrFaceDocument.parseColor("0", 7), "写 0 ⇒ 0（哨兵）");
		assertEquals(0, MmtrFaceDocument.parseColor(" 0 ", 7), "带空白也一样");
		assertEquals(0, MmtrFaceDocument.parseColor("0x0", 7), "0x0 同样");
		assertEquals(0xFF000000, MmtrFaceDocument.parseColor("000000", 7), "★ 六位全零还是**不透明黑**（要黑就写这个）");
		assertEquals(0xFF000000, MmtrFaceDocument.parseColor("#FF000000", 7), "带 alpha 的黑");
		assertEquals(0, MmtrFaceDocument.parseColor("#00000000", 7), "带 alpha 的全透明");
		assertEquals(0, face("{\"background\": 0, \"elements\": []}").background(), "background: 0（JSON 数字）⇒ 不铺底");
		assertEquals(0xFF000000, face("{\"background\": \"000000\", \"elements\": []}").background(), "要黑板得写六位");
		assertEquals(0, face("{\"elements\": [{\"type\": \"rect\", \"color\": 0}]}").plan(Map.of(), 0).get(0).element().color(),
			"形状的 color: 0 ⇒ 全透明（不是黑块）");
	}

	/** 元素几何与字号的缺省来自键表（改了表就改了行为，没有第二份常量）。 */
	@Test
	public void elementDefaultsComeFromTheKeyTable() {
		final MmtrFaceDocument.Element element = face("{\"elements\": [{\"type\": \"text\", \"text\": \"x\"}]}").plan(Map.of(), 0).get(0).element();
		assertEquals(0.5, element.x(), 1.0E-9);
		assertEquals(0.5, element.y(), 1.0E-9);
		assertEquals(0.4, element.size(), 1.0E-9);
		assertEquals("center", element.align());
		assertEquals(0.9, element.number("shrinkToFit", -1), 1.0E-9, "文本专属键的缺省也在表里");
		assertEquals(0.44, face("{\"elements\": [{\"type\": \"gauge\"}]}").plan(Map.of(), 0).get(0).element().number("radius", -1), 1.0E-9);
		// x2/y2 的缺省是"跟 x/y 一样"：键表里写的是 null，所以读到的是调用方兜底的那个值
		final MmtrFaceDocument.Element line = face("{\"elements\": [{\"type\": \"line\", \"x\": 0.2, \"y\": 0.3}]}").plan(Map.of(), 0).get(0).element();
		assertEquals(0.2, line.number("x2", line.x()), 1.0E-9);
	}
}
