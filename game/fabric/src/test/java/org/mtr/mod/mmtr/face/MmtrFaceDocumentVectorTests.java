package org.mtr.mod.mmtr.face;

import org.junit.jupiter.api.Test;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * **面文档 v2 的共享向量**（notes/359 · F3）：Java 与工作室（JS）跑**同一份** {@code conformance/document.json}。
 *
 * <p>这是这套系统防"两份实现分家"的老办法（{@code conformance/logic.json} 管算子，这份管文档层：
 * 选页、{@code forEach} 展开、动画相位、缺省值）。向量是**手写的期望值**，两边各自去够 ——
 * 所以它同时钉住了 Java 和 JS：任何一边改了语义，这份文件就先红，而不是等作者在游戏里发现
 * "预览与游戏画得不一样"。</p>
 *
 * <p>判据面刻意选在**语义层**（哪一页、会画哪些字、展开成哪几条元素），不比对像素：
 * 像素两边永远不可能一样（AWT 与 Canvas2D 的字形、抗锯齿都不同），那样的向量只会天天红。</p>
 */
public final class MmtrFaceDocumentVectorTests {

	private static final Path VECTORS = Path.of("..", "..", "tools", "face-studio", "conformance", "document.json");

	@Test
	public void everySharedVectorHolds() {
		final JsonObject root = JsonParser.parseString(read()).getAsJsonObject();
		final JsonArray cases = root.getAsJsonArray("cases");
		assertNotNull(cases, "向量文件里没有 cases");
		assertTrue(cases.size() >= 20, "共享向量只有 " + cases.size() + " 条 —— 页/动画/重复/缺省各面都该有几条");
		for (final JsonElement element : cases) {
			check(element.getAsJsonObject());
		}
	}

	private static void check(JsonObject testCase) {
		final String name = testCase.get("name").getAsString();
		// 向量里的 face 是"faces[面名] 的内容"；这里包一层锚点文件，走作者真正走的那条解析路
		final String anchors = "{\"faces\":{\"f\":" + testCase.get("face") + "}}";
		final MmtrFaceDocument document = MmtrFaceDocument.fromAnchors("vector", "f", anchors);
		assertNotNull(document, name + "：解析不出来");

		final long timeMs = testCase.has("timeMs") ? testCase.get("timeMs").getAsLong() : 0;
		final Object data = toPlain(testCase.has("data") ? testCase.get("data") : new JsonObject());
		final JsonObject expect = testCase.getAsJsonObject("expect");

		assertEquals(expect.get("page").getAsInt(), document.pageIndex(data, timeMs), name + "：选错页了");
		assertEquals(expect.get("visible").getAsBoolean(), document.visible(data), name + "：文档级 require 的门不一致");
		// fps 与翻牌机角度是**可选**的观测面：写了就断言（新加的向量会写，老的没写就跳过）
		if (expect.has("fps")) {
			assertEquals(expect.get("fps").getAsInt(), document.fps(), name + "：fps 的钳位不一致");
		}
		if (expect.has("background")) {
			assertEquals(expect.get("background").getAsInt(), document.background(), name + "：底色不一致（0 是哨兵，不是不透明黑）");
		}
		assertDrumAngles(name, document, expect);

		final List<String> texts = document.texts(data, timeMs);
		final List<String> expectedTexts = new ArrayList<>();
		expect.getAsJsonArray("texts").forEach(text -> expectedTexts.add(text.getAsString()));
		assertEquals(expectedTexts, texts, name + "：会画出来的字不一致");

		final List<MmtrFaceDocument.Drawable> plan = document.plan(data, timeMs);
		final JsonArray expectedDrawables = expect.getAsJsonArray("drawables");
		assertEquals(expectedDrawables.size(), plan.size(), name + "：展开出来的元素条数不一致");
		for (int i = 0; i < plan.size(); i++) {
			final JsonObject expected = expectedDrawables.get(i).getAsJsonObject();
			final MmtrFaceDocument.Element element = plan.get(i).element();
			final String where = name + "：第 " + i + " 条";
			assertEquals(expected.get("type").getAsString(), element.type(), where + " 类型不一致");
			assertEquals(expected.get("x").getAsDouble(), element.x(), 1.0E-4, where + " x 不一致");
			assertEquals(expected.get("y").getAsDouble(), element.y(), 1.0E-4, where + " y 不一致");
			assertEquals(expected.get("w").getAsDouble(), element.w(), 1.0E-4, where + " w 不一致");
			assertEquals(expected.get("h").getAsDouble(), element.h(), 1.0E-4, where + " h 不一致");
			assertEquals(expected.get("size").getAsDouble(), element.size(), 1.0E-4, where + " size 不一致");
			assertEquals(expected.get("text").getAsString(), element.text(), where + " 文本模板不一致");
			final JsonElement anim = expected.get("anim");
			assertEquals(anim == null || anim.isJsonNull() ? null : anim.getAsString().toLowerCase(java.util.Locale.ROOT),
				element.anim() == null ? null : element.anim().kind().name().toLowerCase(java.util.Locale.ROOT), where + " 动画不一致");
		}
	}

	/**
	 * 翻牌机角度（可选观测面）：每条 {@code {face, page, clockFraction, angle}} 都按
	 * {@link MmtrFaceGeometry#drumAngle} 与文档自己的 {@code drum} 参数算一遍。
	 *
	 * <p>为什么这条值得进共享向量：翻牌机的角度是"看得见的版式"，而它只有一条正确的语义
	 * （周期末尾翻一格，停住时正面就是 {@code pageIndex} 那一面）。只放在 Java 侧，
	 * 工作室的预览就会永远差一格，而作者会以为"预览与游戏不一样是正常的"。</p>
	 *
	 * <p>容差 0.05°：向量里的 {@code clockFraction} 是**四舍五入到 4 位**的输入，而
	 * {@code smoothStep} 在窗口两端最陡（每 0.01 的输入误差最多放大成 ~7°），所以拿被舍入过的
	 * 输入去算，输出本来就会差个百分之几度。0.05° 足够吸收这个误差，又远小于"翻错一页"
	 * （120°/180°）与"该停的时候在转"（几十度）——判据该抓的都抓得住。</p>
	 */
	private static void assertDrumAngles(String name, MmtrFaceDocument document, JsonObject expect) {
		final JsonElement entries = expect.get("drumAngle");
		if (entries == null || !entries.isJsonArray()) {
			return;
		}
		final MmtrFaceDocument.Drum drum = document.drum();
		assertNotNull(drum, name + "：向量给了 drumAngle，但这份文档没有 drum 段");
		for (final JsonElement entry : entries.getAsJsonArray()) {
			final JsonObject sample = entry.getAsJsonObject();
			final double actual = MmtrFaceGeometry.drumAngle(sample.get("face").getAsInt(), sample.get("page").getAsInt(),
				sample.get("clockFraction").getAsDouble(), drum.turnFraction(), drum.count());
			assertEquals(sample.get("angle").getAsDouble(), actual, 0.05,
				name + "：翻牌机角度不一致（face=" + sample.get("face") + " page=" + sample.get("page")
					+ " clockFraction=" + sample.get("clockFraction") + "）");
		}
	}

	/** Gson 容器 → 普通 Map/List（{@code vars} 要能并进数据，所以数据必须是 Map 而不是 JsonObject）。 */	private static Object toPlain(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return null;
		}
		if (element.isJsonObject()) {
			final Map<String, Object> map = new LinkedHashMap<>();
			for (final Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
				map.put(entry.getKey(), toPlain(entry.getValue()));
			}
			return map;
		}
		if (element.isJsonArray()) {
			final List<Object> list = new ArrayList<>();
			element.getAsJsonArray().forEach(item -> list.add(toPlain(item)));
			return list;
		}
		final var primitive = element.getAsJsonPrimitive();
		if (primitive.isBoolean()) {
			return primitive.getAsBoolean();
		}
		if (primitive.isNumber()) {
			final double value = primitive.getAsDouble();
			return value == Math.rint(value) ? (Object) (long) value : (Object) value;
		}
		return primitive.getAsString();
	}

	private static String read() {
		try {
			return Files.readString(VECTORS, StandardCharsets.UTF_8);
		} catch (IOException e) {
			fail("读不了共享向量 " + VECTORS.toAbsolutePath() + "：" + e.getMessage()
				+ "（这份文件由工作室侧维护：mmtr/tools/face-studio/conformance/document.json）");
			return "";
		}
	}
}
