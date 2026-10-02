package org.mtr.mod.mmtr.face;

import org.junit.jupiter.api.Test;
import org.mtr.mod.mmtr.face.MmtrFaceFields.Kind;
import org.mtr.mod.mmtr.face.MmtrFaceFields.Spec;
import org.mtr.mod.mmtr.face.MmtrFaceFields.Sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 字段表的三方**漂移守卫**（notes/359 §数据接口）：登记表（{@code MmtrFaceFields}）
 * ↔ 取值口（{@code MmtrVehicleFaceSource}）↔ 引擎同步白名单（{@code VehicleSyncPatch.DYNAMIC_KEYS}）
 * ↔ 导出的 {@code fields.json}。四处只要有一处掉队，就红。
 *
 * <p>为什么值得为"加一个字段"写这么多核对：字段表是**给外人看的接口**。作者照着
 * {@code fields.json} 写面文档，如果表里有、游戏里没有（或反过来），表现是"牌上永远空着"或
 * "这个量我怎么写都不出来" —— 两种都很难自查。把漏步骤变成红灯，是这件事最便宜的做法。</p>
 *
 * <p>用例的工作目录是 {@code mmtr/game/fabric}（见 run-fabric-tests-offline.ps1 的 Push-Location），
 * 所以下面几条路径都从那里往上走。</p>
 */
public final class MmtrFaceFieldsTests {

	/** {@code EXPORT_PATH} 是**工作区相对**路径，而用例的工作目录是 {@code mmtr/game/fabric}。 */
	private static final Path EXPORT = Path.of("..", "..", "..").resolve(MmtrFaceFields.EXPORT_PATH);
	private static final Path VALUE_SOURCE = Path.of("src", "main", "java", "org", "mtr", "mod", "render", "panel", "MmtrVehicleFaceSource.java");
	private static final Path ENGINE_WHITELIST = Path.of("..", "..", "engine", "src", "main", "java", "org", "mtr", "core", "operation", "VehicleSyncPatch.java");

	/** 抽取结果的下限：正则改坏/文件读错时，别让"抽出 0 个"悄悄通过。 */
	private static final int MINIMUM_KEYS = 40;

	@Test
	public void fieldNamesAreUniqueAndDocumented() {
		final Set<String> seen = new LinkedHashSet<>();
		for (final Spec spec : MmtrFaceFields.specs()) {
			assertTrue(seen.add(spec.name()), "字段名重复：" + spec.name());
			assertFalse(spec.doc().isBlank(), "字段 " + spec.name() + " 没写说明 —— 作者只能靠它");
			if (spec.kind() == Kind.RAW && !spec.mirror().isEmpty()) {
				assertTrue(spec.sync() == Sync.TICK || spec.sync() == Sync.SNAPSHOT,
					"字段 " + spec.name() + " 来自镜像字段却没写节拍（见 MmtrFaceFields.Sync）");
			} else {
				assertEquals(Sync.NA, spec.sync(), "字段 " + spec.name() + " 不是镜像字段，节拍应为 na");
			}
		}
		assertTrue(MmtrFaceFields.specs().size() >= 40, "字段表只剩 " + MmtrFaceFields.specs().size() + " 行 —— 被删了？");
	}

	/** ★ 取值口与登记表必须**互相**覆盖：少一个 = 作者写不出来；多一个 = 死代码。 */
	@Test
	public void valueSourceCoversExactlyTheRawFields() {
		final Set<String> cases = casesOf(VALUE_SOURCE);
		assertTrue(cases.size() >= MINIMUM_KEYS, "从 " + VALUE_SOURCE + " 只抽出 " + cases.size() + " 个 case —— 抽取规则坏了？");
		final Set<String> expected = new LinkedHashSet<>();
		for (final Spec spec : MmtrFaceFields.rawSpecs()) {
			expected.add(spec.name());
		}
		assertEquals(expected, cases, "取值口的 case 与字段表的 raw 行不一致（缺的写不出来；多的没人用）");
	}

	/** ★ 节拍那一位必须等于引擎白名单的真实情况（在 = 每 tick 走稀疏补丁；不在 = 只在整份快照）。 */
	@Test
	public void syncFlagMatchesTheEngineWhitelist() {
		final Set<String> whitelist = dynamicKeysOf(ENGINE_WHITELIST);
		assertTrue(whitelist.size() >= MINIMUM_KEYS,
			"从 " + ENGINE_WHITELIST + " 的 DYNAMIC_KEYS 只抽出 " + whitelist.size() + " 个键 —— 抽取规则或引擎文件结构变了？");
		for (final Spec spec : MmtrFaceFields.specs()) {
			if (spec.mirror().isEmpty()) {
				continue;
			}
			final boolean listed = whitelist.contains(spec.mirror());
			assertEquals(spec.sync() == Sync.TICK, listed,
				"字段 " + spec.name() + " 的节拍与引擎白名单不符：登记表写 " + spec.sync().json()
					+ "，而 " + spec.mirror() + (listed ? " 在白名单里" : " 不在白名单里")
					+ "（不在白名单 ⇒ 只在整份快照时更新，应写 snapshot）");
		}
	}

	/**
	 * ★ 导出的字段表必须与登记表逐字一致（web 工作室的自动补全就吃这份文件）。
	 *
	 * <p>不一致时会把实际内容写到 {@code fields.json.actual} —— 拷过去覆盖即可（这是"怎么重生成"
	 * 的说明书，省得为此再写一个生成器程序）。</p>
	 */
	@Test
	public void exportedFieldsFileMatchesTheRegistry() {
		final String actual = MmtrFaceFields.exportJson();
		if (!Files.isRegularFile(EXPORT)) {
			writeActual(actual);
			fail("字段表还不存在：" + EXPORT.toAbsolutePath() + " —— 已写出 fields.json.actual，拷过去即可");
		}
		final String expected;
		try {
			expected = Files.readString(EXPORT, StandardCharsets.UTF_8);
		} catch (IOException e) {
			fail("读不了 " + EXPORT + "：" + e.getMessage());
			return;
		}
		if (!expected.equals(actual)) {
			writeActual(actual);
			fail("字段表与登记表不一致（" + EXPORT + "）—— 已写出 fields.json.actual，核对后拷过去覆盖");
		}
	}

	/** ★ 反例：不存在的字段名取不到东西（`spec` 返回 null，不是抛异常、也不是随便给一行）。 */
	@Test
	public void anUnknownFieldNameIsSimplyAbsent() {
		assertNull(MmtrFaceFields.spec("pid.nope"));
		assertNull(MmtrFaceFields.spec(""));
	}

	private static void writeActual(String content) {
		try {
			Files.writeString(Path.of(EXPORT + ".actual"), content, StandardCharsets.UTF_8);
		} catch (IOException e) {
			fail("连 fields.json.actual 都写不出来：" + e.getMessage());
		}
	}

	private static Set<String> casesOf(Path file) {
		final Matcher matcher = Pattern.compile("case \"([^\"]+)\"").matcher(read(file));
		final Set<String> values = new LinkedHashSet<>();
		while (matcher.find()) {
			values.add(matcher.group(1));
		}
		return values;
	}

	/** 取引擎白名单数组里的字符串字面量（先剥掉注释，免得注释里的字段名混进来）。 */
	private static Set<String> dynamicKeysOf(Path file) {
		final String text = read(file).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
		final int start = text.indexOf("DYNAMIC_KEYS");
		assertTrue(start >= 0, "在 " + file + " 里找不到 DYNAMIC_KEYS");
		final int end = text.indexOf(");", start);
		assertTrue(end > start, "在 " + file + " 里找不到 DYNAMIC_KEYS 的结尾");
		final Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(text.substring(start, end));
		final Set<String> values = new LinkedHashSet<>();
		while (matcher.find()) {
			values.add(matcher.group(1));
		}
		return values;
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
