package org.mtr.mod.mmtr.face;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * **面文档键表的三方一致**（notes/359 · F3）：Java 键表（{@link MmtrFaceSchema}）↔ 导出的
 * {@code schema.json} ↔ 真正执行的那份画法（{@code MmtrFaceElements}）。
 *
 * <p>为什么这张表值得钉：工作室的**属性面板是按 {@code schema.json} 生成的**，引擎的**缺省值也是从
 * 这张表读的**。于是"表里少一条键"的后果不是报错，而是两件都不明显的事：作者在面板上找不到那个键，
 * 而 if 他手写进去，引擎会**当没看见**（画出来是缺省的样子）—— 也就是"预览好好的、进游戏不对"。
 * 这类问题只能靠"表 ↔ 画法"两边对照来防，所以要读源码文本逐键比对（{@code MmtrFaceToolingTests}
 * 用的同一招：比对的就是编译器看到的那份）。</p>
 */
public final class MmtrFaceSchemaTests {

	/** {@code EXPORT_PATH} 是**工作区相对**路径，而用例的工作目录是 {@code mmtr/game/fabric}。 */
	private static final Path EXPORT = Path.of("..", "..", "..").resolve(MmtrFaceSchema.EXPORT_PATH);
	private static final Path ELEMENTS_SOURCE = Path.of("src", "main", "java", "org", "mtr", "mod", "render", "panel", "MmtrFaceElements.java");

	/** 画法方法名 → 元素类型（一对一；{@code needle} 是 gauge 的辅助方法）。 */
	private static final Map<String, String> PAINTER_METHODS = Map.of(
		"paintText", "text",
		"paintRect", "rect",
		"paintRoundRect", "roundrect",
		"paintLine", "line",
		"paintCircle", "circle",
		"paintArc", "arc",
		"paintGauge", "gauge",
		"needle", "gauge",
		"paintImage", "image",
		"paintForEach", "foreach"
	);

	/** 导出的 {@code schema.json} 与 {@code exportJson()} 必须逐字相同（不一致时写出 {@code .actual}）。 */
	@Test
	public void theSchemaFileMatchesTheTable() {
		final String actual = MmtrFaceSchema.exportJson();
		if (!Files.isRegularFile(EXPORT)) {
			writeActual(actual);
			fail("键表还不存在：" + EXPORT.toAbsolutePath() + " —— 已写出 schema.json.actual，拷过去即可");
		}
		final String expected = readText(EXPORT);
		if (!expected.equals(actual)) {
			writeActual(actual);
			fail("键表与 MmtrFaceSchema 不一致（" + EXPORT + "）—— 已写出 schema.json.actual，核对后拷过去覆盖");
		}
	}

	/** 注册进 {@code PAINTERS} 的类型 == 键表里声明的类型（多了没登记的画法、少了没实现的类型都红）。 */
	@Test
	public void thePaintersImplementExactlyTheDeclaredElementTypes() {
		final Set<String> registered = labelsOf(readText(ELEMENTS_SOURCE), "PAINTERS\\.put\\(\"([^\"]+)\"");
		assertTrue(registered.size() >= 9, "从 MmtrFaceElements 里只抽出 " + registered.size() + " 种元素 —— 抽取规则坏了？");
		assertEquals(new LinkedHashSet<>(MmtrFaceSchema.ELEMENT_TYPES), registered,
			"MmtrFaceElements 的注册表与 MmtrFaceSchema.ELEMENT_TYPES 不一致（加一种元素必须两边都写）");
	}

	/**
	 * ★ 画法里读的每一个键都必须在键表里登记。
	 *
	 * <p>反过来说：表里登记了、画法却不读的键**不算错**（{@code foreach} 的 {@code elements} 由解析器读），
	 * 但画法读了、表里没有的键一定是漏登记 —— 那条路作者在面板上找不到它。</p>
	 */
	@Test
	public void everyKeyAPainterReadsIsDeclared() {
		final String source = readText(ELEMENTS_SOURCE);
		final Set<String> allRead = new LinkedHashSet<>();
		for (final Map.Entry<String, String> entry : PAINTER_METHODS.entrySet()) {
			final String body = methodBody(source, entry.getKey());
			assertFalse(body.isBlank(), "抽不出 " + entry.getKey() + " 的方法体 —— 抽取规则坏了？");
			for (final String key : keysRead(body)) {
				allRead.add(key);
				assertTrue(MmtrFaceSchema.knownKeys(entry.getValue()).contains(key),
					entry.getKey() + " 读了键「" + key + "」，但 " + entry.getValue() + " 的键表里没有它（schema.json 也不会有 ⇒ 工作室面板里看不见）");
			}
		}
		assertTrue(allRead.size() >= 20, "一共只抽出 " + allRead.size() + " 个键 —— 抽取规则坏了？（画法读的键远不止这些）");
	}

	/** 公共键必须被每一种元素都认得（{@code x}/{@code y}/{@code color}/{@code rotate}/{@code anim}…）。 */
	@Test
	public void theCommonKeysAreKnownToEveryType() {
		for (final String type : MmtrFaceSchema.ELEMENT_TYPES) {
			final Set<String> known = MmtrFaceSchema.knownKeys(type);
			for (final String key : List.of("type", "when", "x", "y", "w", "h", "size", "color", "align", "text", "rotate", "opacity", "anim")) {
				assertTrue(known.contains(key), type + " 不认得公共键「" + key + "」");
			}
		}
		assertTrue(MmtrFaceSchema.knownKeys("text").contains("shrinkToFit"));
		assertFalse(MmtrFaceSchema.knownKeys("rect").contains("shrinkToFit"), "rect 不该认得文本专属键（那是拼错时才该出现的提示）");
	}

	/** 每种动画都有自己的键表，且都带 {@code kind}；动画名的清单与实现里认得的四种种一致。 */
	@Test
	public void everyAnimationKindIsDeclaredWithItsKeys() {
		assertEquals(MmtrFaceAnim.kinds(), MmtrFaceSchema.animKinds());
		for (final String kind : MmtrFaceAnim.kinds()) {
			final List<MmtrFaceSchema.Key> keys = MmtrFaceSchema.keysOf(kind);
			assertFalse(keys.isEmpty(), kind + " 没有键表");
			assertTrue(keys.stream().anyMatch(key -> key.name().equals("kind")), kind + " 的键表里没有 kind");
		}
	}

	/** 缺省值确实是从表里读的（元素没写就用表里的数）—— 这是"面板上显示的数就是引擎用的数"的判据。 */
	@Test
	public void theDefaultsComeFromTheTable() {
		assertEquals(0.44, MmtrFaceSchema.defaultNumberOf("gauge", "radius", -1), 1.0E-9);
		assertEquals(4000, MmtrFaceSchema.defaultNumberOf("marquee", "spanMs", -1), 1.0E-9, "动画缺省也在这张表里");
		assertEquals(0.4, MmtrFaceSchema.defaultNumberOf("text", "size", -1), 1.0E-9, "公共键的缺省对所有类型都成立");
		assertEquals(8, MmtrFaceSchema.defaultNumberOf("document", "fps", -1), 1.0E-9);
		assertEquals(6, MmtrFaceSchema.defaultNumberOf("document", "pageSeconds", -1), 1.0E-9);
		assertEquals(2, MmtrFaceSchema.defaultNumberOf("drum", "count", -1), 1.0E-9);
		assertEquals(0.03, MmtrFaceSchema.defaultNumberOf("line", "width", -1), 1.0E-9);
		// x2/y2 的缺省刻意是 null（"跟 x/y 一样"由画法兜底），所以这里应当回落到调用方给的 fallback
		assertEquals(0.77, MmtrFaceSchema.defaultNumberOf("line", "x2", 0.77), 1.0E-9);
		assertEquals("stretch", MmtrFaceSchema.defaultStringOf("image", "fit", "?"));
		assertEquals("item", MmtrFaceSchema.defaultStringOf("foreach", "as", "?"));
	}

	// ---- 抽取 ------------------------------------------------------------------------------------

	/** 抽一个方法体：从它的签名到下一个方法的签名（缩进一个 tab 的 {@code private static}）。 */
	private static String methodBody(String source, String name) {
		final int start = source.indexOf("private static " + (name.startsWith("paint") ? "void " : "Object ") + name + "(");
		if (start < 0) {
			return "";
		}
		final int next = source.indexOf("\n\tprivate static ", start + 1);
		return next < 0 ? source.substring(start) : source.substring(start, next);
	}

	/** 方法体里读的键：{@code element.number/string/flag("k"…)} 与 {@code element.raw().has/get("k")}。 */
	private static Set<String> keysRead(String body) {
		final Set<String> keys = new LinkedHashSet<>();
		final Matcher matcher = Pattern.compile("element\\.(?:number|string|flag)\\(\"([^\"]+)\"|element\\.raw\\(\\)\\.(?:has|get)\\(\"([^\"]+)\"\\)").matcher(body);
		while (matcher.find()) {
			keys.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
		}
		return keys;
	}

	private static Set<String> labelsOf(String source, String pattern) {
		final Set<String> labels = new LinkedHashSet<>();
		final Matcher matcher = Pattern.compile(pattern).matcher(source);
		while (matcher.find()) {
			labels.add(matcher.group(1));
		}
		return labels;
	}

	private static void writeActual(String content) {
		try {
			Files.writeString(Path.of(EXPORT + ".actual"), content, StandardCharsets.UTF_8);
		} catch (IOException e) {
			fail("连 schema.json.actual 都写不出来：" + e.getMessage());
		}
	}

	private static String readText(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			fail("读不了 " + file.toAbsolutePath() + "：" + e.getMessage() + "（用例的工作目录应当是 mmtr/game/fabric）");
			return "";
		}
	}
}
