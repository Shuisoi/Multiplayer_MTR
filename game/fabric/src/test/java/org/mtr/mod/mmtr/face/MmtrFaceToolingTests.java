package org.mtr.mod.mmtr.face;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * **工具清单的三方一致**（notes/359 · F1）：Java 的清单 ↔ Java 的 {@code switch} ↔
 * {@code tools/anchor-check/verify_face.js} 里那份 JS 副本。
 *
 * <p>为什么值得写：{@code verify_face.js} 是作者唯一能在打包前自查"这块面会不会静默不画"的东西，
 * 而它必须**知道**有哪些元素类型、算子、过滤器。这三张清单抄在 JS 里（JS 端跑不了 Java），
 * 于是"Java 加了算子、JS 忘了加"就会造成**自检说没问题、游戏里那行被跳过**——
 * 比没有自检更坏。这条用例把三份钉在一起。</p>
 *
 * <p>另一处同样重要：{@code operators()} / {@code filters()} 这两个方法是"给人看的清单"，
 * 它们与真正执行的那个 {@code switch} 之间也可能漂 —— 所以这里顺带**从源码文本里抽出
 * {@code case} 标签**来对齐（比反射更直白：它比对的就是编译器看到的那份）。</p>
 */
public final class MmtrFaceToolingTests {

	private static final Path VERIFY_FACE = Path.of("..", "..", "tools", "anchor-check", "verify_face.js");
	private static final Path LOGIC_SOURCE = Path.of("src", "main", "java", "org", "mtr", "mod", "mmtr", "face", "MmtrFaceLogic.java");
	private static final Path TEXT_SOURCE = Path.of("src", "main", "java", "org", "mtr", "mod", "mmtr", "face", "MmtrFaceText.java");

	@Test
	public void theVerifierKnowsExactlyTheSameOperators() {
		assertEquals(MmtrFaceLogic.operators(), jsArray("OPERATORS"),
			"verify_face.js 的 OPERATORS 与 MmtrFaceLogic.operators() 不一致");
	}

	@Test
	public void theVerifierKnowsExactlyTheSameFilters() {
		assertEquals(new LinkedHashSet<>(MmtrFaceText.filters()), jsArray("FILTERS"),
			"verify_face.js 的 FILTERS 与 MmtrFaceText.filters() 不一致");
	}

	/**
	 * 元素类型与动画名**不再往 JS 里抄一份**（F3 改的）：{@code verify_face.js} 直接读
	 * {@code schema.json} 的 {@code elementTypes}/{@code animKinds}/{@code sections}，
	 * 而 {@code schema.json} 由 {@link MmtrFaceSchema} 导出、并逐字比对
	 * （{@link MmtrFaceSchemaTests}）。于是"Java 加了类型、校验脚本不认得"这条链上没有第二处副本。
	 *
	 * <p>这条用例守的就是"别再抄回来"：一旦有人在 JS 里又写了一份字面量清单，
	 * 它就会与 schema.json 分家，而那正是本文件存在的理由。</p>
	 */
	@Test
	public void theVerifierTakesItsListsFromTheKeyTableInsteadOfACopy() {
		final String source = read(VERIFY_FACE);
		assertTrue(source.contains("elementTypes"), "verify_face.js 的元素清单应当来自 schema.json 的 elementTypes");
		assertTrue(source.contains("animKinds"), "verify_face.js 的动画清单应当来自 schema.json 的 animKinds");
		assertTrue(source.contains("sections"), "verify_face.js 的未知键检查应当来自 schema.json 的 sections");
		for (final String name : new String[]{"ELEMENT_TYPES", "ANIM_KINDS"}) {
			assertFalse(Pattern.compile("const\\s+" + name + "\\s*=\\s*\\[").matcher(source).find(),
				"verify_face.js 里又抄了一份 " + name + " 字面量清单 —— 请改成读 schema.json（否则它迟早与引擎分家）");
		}
	}

	/** ★ 清单 ↔ 真正执行的那个 switch：两个方法各抽一遍 {@code case} 标签。 */
	@Test
	public void theOperatorListMatchesTheSwitchThatExecutesIt() {
		assertEquals(labelsOf(read(LOGIC_SOURCE), "case ((?:\"[^\"]+\")(?:\\s*,\\s*\"[^\"]+\")*)\\s*->"),
			MmtrFaceLogic.operators(), "MmtrFaceLogic 的 case 标签与 operators() 不一致（加了算子必须两边都写）");
	}

	@Test
	public void theFilterListMatchesTheSwitchThatExecutesIt() {
		assertEquals(labelsOf(read(TEXT_SOURCE), "case \"([^\"]+)\"\\s*->"), MmtrFaceText.filters(),
			"MmtrFaceText 的 case 标签与 filters() 不一致（加了过滤器必须两边都写）");
	}

	/** ★ 反例：抽取规则本身要能"抽不到就红"，不能悄悄返回空集合让用例白白通过。 */
	@Test
	public void theExtractionItselfIsChecked() {
		assertEquals(Set.of(), labelsOf("没有任何标签的一段文本", "case \"([^\"]+)\"\\s*->"), "抽不到就该是空集（调用方有下限断言兜着）");
		assertTrue(labelsOf(read(LOGIC_SOURCE), "case ((?:\"[^\"]+\")(?:\\s*,\\s*\"[^\"]+\")*)\\s*->").size() >= 30,
			"算子只有不到 30 个 —— 抽取规则或源码结构变了？");
	}

	/** 从 JS 里抽一个 `const NAME = ['a', 'b'];` 的数组。 */
	private static Set<String> jsArray(String name) {
		final Matcher matcher = Pattern.compile("const\\s+" + name + "\\s*=\\s*\\[([^\\]]*)\\]").matcher(read(VERIFY_FACE));
		if (!matcher.find()) {
			fail("在 " + VERIFY_FACE + " 里找不到 " + name + " 数组");
			return Set.of();
		}
		final Set<String> values = new LinkedHashSet<>();
		final Matcher items = Pattern.compile("'([^']*)'").matcher(matcher.group(1));
		while (items.find()) {
			values.add(items.group(1).toLowerCase(Locale.ROOT));
		}
		return values;
	}

	/**
	 * 抽源码里的标签。捕获组里可能是**一串带引号的字面量**（{@code case "min", "max" ->}）、
	 * 也可能是**裸名字**（{@code case "upper" ->} 里的 {@code upper}、{@code PAINTERS.put("text"} 里的
	 * {@code text}）—— 两种都要认（第一版只认前者，于是"抽到 0 个"把两条用例判红）。
	 */
	private static Set<String> labelsOf(String source, String pattern) {
		final Set<String> labels = new LinkedHashSet<>();
		final Matcher matcher = Pattern.compile(pattern).matcher(source);
		while (matcher.find()) {
			final String captured = matcher.group(1);
			boolean quoted = false;
			final Matcher item = Pattern.compile("\"([^\"]+)\"").matcher(captured);
			while (item.find()) {
				labels.add(item.group(1));
				quoted = true;
			}
			if (!quoted && !captured.isBlank()) {
				labels.add(captured.trim());
			}
		}
		return labels;
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			fail("读不了 " + file.toAbsolutePath() + "：" + e.getMessage() + "（用例的工作目录应当是 mmtr/game/fabric）");
			return "";
		}
	}
}
