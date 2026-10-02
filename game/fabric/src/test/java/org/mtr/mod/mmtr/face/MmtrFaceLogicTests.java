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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 动态面**逻辑层**的判据（notes/359）：跑的是**共享测试向量**
 * {@code tools/face-studio/conformance/logic.json} —— 同一份文件将来由 web 工作室的 JS 端再跑一遍，
 * 两个实现谁漂了就红。
 *
 * <p>为什么把语义用例放在 JSON 里而不是全写成 Java 断言：<b>面文档的逻辑必须在游戏里和在工作室里
 * 算出同一个答案</b>。作者在浏览器里试出来的条件，进游戏必须还是那个结果；否则"所见即所得"是假的。
 * 用一份两边都读的向量，这条性质就从"靠自觉"变成"可证伪"。</p>
 *
 * <p>这里只放纯函数面的判据（缺文件就大声失败）；预算/注册这类进程内状态各有用例。</p>
 */
public final class MmtrFaceLogicTests {

	/** 用例的工作目录是 {@code mmtr/game/fabric}（见 run-fabric-tests-offline.ps1 的 Push-Location）。 */
	private static final Path CONFORMANCE = Path.of("..", "..", "tools", "face-studio", "conformance", "logic.json");

	/** 向量条数的下限：文件被删空、路径读错、JSON 被截断，都要在这里拦住（不是"跑 0 条也算绿"）。 */
	private static final int MINIMUM_VECTORS = 60;

	@Test
	public void everyConformanceVectorHolds() {
		final JsonObject root = readConformance();
		final JsonArray vectors = root.getAsJsonArray("cases");
		assertNotNull(vectors, "共享向量文件里没有 cases");
		assertTrue(vectors.size() >= MINIMUM_VECTORS,
			"共享向量只剩 " + vectors.size() + " 条（少于下限 " + MINIMUM_VECTORS + "）—— 文件被删空或读错了？");

		int index = 0;
		for (final JsonElement element : vectors) {
			final JsonObject vector = element.getAsJsonObject();
			final String name = vector.get("name").getAsString();
			final String label = "向量[" + index + "]「" + name + "」";
			final Object actual = MmtrFaceLogic.eval(vector.get("expr"), root.get("data"));
			assertSameValue(vector.get("expect"), actual, label);
			index++;
		}
	}

	/** ★ 两种数据形态必须给出同一个答案：普通 Java 集合（游戏里 {@code MmtrFaceData} 建的）与 Gson 树。 */
	@Test
	public void everyVectorAlsoHoldsWithPlainJavaData() {
		final JsonObject root = readConformance();
		final Map<String, Object> plain = toPlain(root.get("data"));
		int index = 0;
		for (final JsonElement element : root.getAsJsonArray("cases")) {
			final JsonObject vector = element.getAsJsonObject();
			final String name = vector.get("name").getAsString();
			final Object fromPlain = MmtrFaceLogic.eval(vector.get("expr"), plain);
			assertSameValue(vector.get("expect"), fromPlain, "向量[" + index + "]「" + name + "」(普通 Java 数据)");
			index++;
		}
	}

	/** ★ 反例：层数超预算 ⇒ 抛（不是把客户端拖死，也不是静默算错）。 */
	@Test
	public void aDeeplyNestedExpressionIsRejected() {
		final StringBuilder json = new StringBuilder();
		for (int i = 0; i < MmtrFaceLogic.MAX_DEPTH + 8; i++) {
			json.append("{\"!\": [");
		}
		json.append("true");
		for (int i = 0; i < MmtrFaceLogic.MAX_DEPTH + 8; i++) {
			json.append("]}");
		}
		assertThrows(MmtrFaceLogic.BudgetExceededException.class, () -> MmtrFaceLogic.evalJson(json.toString(), Map.of()));
	}

	/** ★ 反例：节点数超预算 ⇒ 抛。 */
	@Test
	public void tooManyNodesAreRejected() {
		final StringBuilder json = new StringBuilder("{\"and\": [");
		for (int i = 0; i < MmtrFaceLogic.MAX_NODES + 40; i++) {
			json.append(i == 0 ? "1" : ",1");
		}
		json.append("]}");
		assertThrows(MmtrFaceLogic.BudgetExceededException.class, () -> MmtrFaceLogic.evalJson(json.toString(), Map.of()));
	}

	/** ★ 反例：不认识的算子 ⇒ 抛（调用方把这一条元素跳过并记一次日志，而不是画错东西）。 */
	@Test
	public void anUnknownOperatorIsRejected() {
		assertThrows(MmtrFaceLogic.UnknownOperatorException.class, () -> MmtrFaceLogic.evalJson("{\"nope\": [1]}", Map.of()));
	}

	/** ★ 多键对象 = and，且**遇假即停**：第二个算子根本不认识，也不该被求值。 */
	@Test
	public void aMultiKeyObjectShortCircuitsOnTheFirstFalsyKey() {
		assertEquals(false, MmtrFaceLogic.evalJson("{\"!!\": [false], \"nope\": [1]}", Map.of()));
	}

	private static JsonObject readConformance() {
		if (!Files.isRegularFile(CONFORMANCE)) {
			fail("找不到共享测试向量：" + CONFORMANCE.toAbsolutePath() + "（用例的工作目录应当是 mmtr/game/fabric）");
		}
		try {
			return JsonParser.parseString(Files.readString(CONFORMANCE, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IOException e) {
			fail("读不了共享测试向量：" + e.getMessage());
			return new JsonObject();
		}
	}

	/** Gson 树 → 普通 Java 集合（{@code Map}/{@code List}/String/Double/Boolean/null）。 */
	private static Map<String, Object> toPlain(JsonElement element) {
		final Map<String, Object> map = new LinkedHashMap<>();
		for (final Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
			map.put(entry.getKey(), toPlainValue(entry.getValue()));
		}
		return map;
	}

	private static Object toPlainValue(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return null;
		}
		if (element.isJsonObject()) {
			return toPlain(element);
		}
		if (element.isJsonArray()) {
			final List<Object> list = new ArrayList<>();
			for (final JsonElement item : element.getAsJsonArray()) {
				list.add(toPlainValue(item));
			}
			return list;
		}
		final var primitive = element.getAsJsonPrimitive();
		if (primitive.isBoolean()) {
			return primitive.getAsBoolean();
		}
		if (primitive.isNumber()) {
			return primitive.getAsDouble();
		}
		return primitive.getAsString();
	}

	/**
	 * 期望值比对：数字按数值比（{@code 1} 与 {@code 1.0} 不算两回事）、字符串逐字比、
	 * 数组按下标递归、对象按键递归、{@code null} 必须真的是 null。
	 */
	private static void assertSameValue(JsonElement expected, Object actualValue, String label) {
		final Object actual = unwrap(actualValue);
		if (expected == null || expected.isJsonNull()) {
			assertTrue(actual == null, label + "：期望 null，实际 " + describe(actual));
			return;
		}
		if (expected.isJsonArray()) {
			assertTrue(actual instanceof List, label + "：期望数组，实际 " + describe(actual));
			final List<?> list = (List<?>) actual;
			final JsonArray expectedArray = expected.getAsJsonArray();
			assertEquals(expectedArray.size(), list.size(), label + "：数组长度");
			for (int i = 0; i < expectedArray.size(); i++) {
				assertSameValue(expectedArray.get(i), list.get(i), label + "[" + i + "]");
			}
			return;
		}
		if (expected.isJsonObject()) {
			assertTrue(actual instanceof Map, label + "：期望对象，实际 " + describe(actual));
			final JsonObject expectedObject = expected.getAsJsonObject();
			final Map<?, ?> map = (Map<?, ?>) actual;
			for (final Map.Entry<String, JsonElement> entry : expectedObject.entrySet()) {
				assertTrue(map.containsKey(entry.getKey()), label + "：结果里没有键 " + entry.getKey());
				assertSameValue(entry.getValue(), map.get(entry.getKey()), label + "." + entry.getKey());
			}
			return;
		}
		final var primitive = expected.getAsJsonPrimitive();
		if (primitive.isBoolean()) {
			assertEquals(primitive.getAsBoolean(), actual, label + "：期望布尔 " + primitive.getAsBoolean() + "，实际 " + describe(actual));
			return;
		}
		if (primitive.isNumber()) {
			final Double number = MmtrFaceLogic.asNumber(actual);
			assertNotNull(number, label + "：期望数字 " + primitive.getAsDouble() + "，实际 " + describe(actual));
			assertEquals(primitive.getAsDouble(), number, 1.0E-9, label + "：数字不等");
			return;
		}
		assertEquals(primitive.getAsString(), MmtrFaceLogic.asString(actual), label + "：文本不等");
	}

	/** 结果里可能是 Gson 节点（向量数据就是 Gson 树）也可能是普通 Java 值 —— 比对前先拉平到普通值。 */
	private static Object unwrap(Object value) {
		if (value instanceof JsonElement element) {
			if (element.isJsonNull()) {
				return null;
			}
			if (element.isJsonPrimitive()) {
				final var primitive = element.getAsJsonPrimitive();
				if (primitive.isBoolean()) {
					return primitive.getAsBoolean();
				}
				if (primitive.isNumber()) {
					return primitive.getAsDouble();
				}
				return primitive.getAsString();
			}
			if (element.isJsonArray()) {
				final List<Object> list = new ArrayList<>();
				for (final JsonElement item : element.getAsJsonArray()) {
					list.add(unwrap(item));
				}
				return list;
			}
			return toPlain(element);
		}
		return value;
	}

	private static String describe(Object value) {
		return value == null ? "null" : value.getClass().getSimpleName() + "(" + MmtrFaceLogic.asString(value) + ")";
	}
}
