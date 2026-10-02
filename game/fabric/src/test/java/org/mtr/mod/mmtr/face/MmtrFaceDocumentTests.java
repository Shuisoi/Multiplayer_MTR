package org.mtr.mod.mmtr.face;

import org.junit.jupiter.api.Test;
import org.mtr.mod.mmtr.MmtrPidText;
import org.mtr.mod.render.panel.MmtrPidLayout;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 面文档的判据（notes/359），核心是**内置水牌文档与老版式逐行等价**。
 *
 * <p>为什么这么在意"逐行等价"：F0 加的这一层要在 F1 接管真实的水牌渲染（{@code faces.pid_1} 一写，
 * 老渲染器就让位）。如果这一层画出来跟老版式有一点点不一样，那就是"资源包没改、牌变了样"——
 * 属于最难查的一类回归。所以先把两边的**画什么字、第几行、什么位置、多大字号、像素密度**
 * 一格一格对上（{@link MmtrPidLayout} 因此留着当对照物），再去谈新功能。</p>
 *
 * <p>另一个判据面是**门**：老版式的"不在作业单上不挂牌 / 下一站牌没站名不挂"由
 * {@code MmtrPidText.lines} 说了算，面文档把它翻成 {@code require} 表达式。两者必须在真值表的
 * 每一格上一致 —— 否则会出现"空牌子"（老版式不画、新文档画）或者"该挂的没挂"。</p>
 */
public final class MmtrFaceDocumentTests {

	/** SAF420 实车用的那份版式（左班次号、右"开往 终点"），照锚点 JSON 的形状写。 */
	private static final String AUTHORED_PID = """
		{
		  "pid": {
		    "background": "#FF101418", "textColor": "#FFF2F4F6",
		    "rows": [
		      {"field": "service",  "x": 0.03, "y": 0.5, "size": 0.46, "align": "left", "color": "#FF9FB3C8"},
		      {"field": "terminus", "x": 0.97, "y": 0.5, "size": 0.62, "align": "right", "prefix": "开往 "}
		    ]
		  }
		}
		""";

	private static final String AUTHORED_NEXT = """
		{
		  "next": {
		    "rows": [
		      {"text": "下一站", "x": 0.5, "y": 0.78, "size": 0.2},
		      {"field": "next", "x": 0.5, "y": 0.35, "size": 0.5}
		    ]
		  }
		}
		""";

	private static final String TWO_LINE_PID = """
		{"pid": {"rows": [{"field": "service", "y": 0.21, "size": 0.24}, {"field": "terminus", "y": 0.65, "size": 0.52}]}}
		""";

	/** 一行既没 field 也没 text（老版式会警告并丢掉）、一行字段名不认识（老版式保留但画不出字）。 */
	private static final String MESSY_PID = """
		{"pid": {"rows": [{"x": 0.5}, {"field": "nope"}, {"field": "service", "size": 0.4}]}}
		""";

	@Test
	public void theDefaultLayoutMatchesTheLegacyOne() {
		assertEquivalent(MmtrPidText.Board.DESTINATION, "{}", "00101", "海山", "鸥湾");
		assertEquivalent(MmtrPidText.Board.NEXT_STATION, "{}", "00101", "海山", "鸥湾");
	}

	@Test
	public void anAuthoredLayoutMatchesTheLegacyOne() {
		assertEquivalent(MmtrPidText.Board.DESTINATION, AUTHORED_PID, "00109", "海山", "鸥湾");
		assertEquivalent(MmtrPidText.Board.NEXT_STATION, AUTHORED_NEXT, "00109", "海山", "鸥湾");
		assertEquivalent(MmtrPidText.Board.DESTINATION, TWO_LINE_PID, "00109", "海山", "鸥湾");
		assertEquivalent(MmtrPidText.Board.DESTINATION, MESSY_PID, "00109", "海山", "鸥湾");
	}

	/** ★ 回库趟：两边都只剩班次号那一行（前缀"开往 "不许孤零零留在牌上）。 */
	@Test
	public void theReturnLegIsEquivalentToo() {
		assertEquivalent(MmtrPidText.Board.DESTINATION, AUTHORED_PID, "00109", "", "");
		assertEquivalent(MmtrPidText.Board.DESTINATION, TWO_LINE_PID, "00109", "", "");
		assertEquivalent(MmtrPidText.Board.DESTINATION, "{}", "00109", "", "");
	}

	/** ★ 超长终点站名（要不要等比缩小两边得一致）。 */
	@Test
	public void aVeryLongTerminusIsHandledTheSameWay() {
		assertEquivalent(MmtrPidText.Board.DESTINATION, AUTHORED_PID, "00109", "一个特别特别长的终点站名字", "");
	}

	/**
	 * ★ 门（{@code require}）与老口径 {@code MmtrPidText.lines(...).length > 0} 在真值表上逐格一致。
	 */
	@Test
	public void theGateMatchesTheLegacyRuleOnEveryCase() {
		final String[][] cases = {
			{"00101", "海山", "鸥湾"},
			{"", "海山", "鸥湾"},
			{"00101", "", "鸥湾"},
			{"00101", "海山", ""},
			{"", "", ""},
			{"00101", "", ""}
		};
		for (final MmtrPidText.Board board : MmtrPidText.Board.values()) {
			for (final String[] row : cases) {
				final String service = row[0];
				final String terminus = row[1];
				final String next = row[2];
				final boolean legacy = MmtrPidText.lines(board, service, terminus, next).length > 0;
				for (final String anchors : new String[]{"{}", AUTHORED_PID, AUTHORED_NEXT, MESSY_PID}) {
					final MmtrFaceDocument document = MmtrFaceDocument.builtinPid(board, "saf420", anchors);
					assertEquals(legacy, document.visible(data(service, terminus, next).asMap()),
						"门不一致：" + board + " 班次「" + service + "」终点「" + terminus + "」下一站「" + next + "」（版式 " + anchors.length() + " 字节）");
				}
			}
		}
	}

	/** ★ 反例：新面的 {@code faces} 段不存在时 {@code fromAnchors} 给 null（调用方才知道该退回内置文档）。 */
	@Test
	public void noFacesSectionMeansNoDocument() {
		assertNull(MmtrFaceDocument.fromAnchors("saf420", "pid_1", AUTHORED_PID), "只有老 pid 段 ⇒ 没有面文档");
		assertNull(MmtrFaceDocument.fromAnchors("saf420", "pid_1", "{}"));
	}

	/** 新面：{@code faces} 段按锚点名索引，条件/模板/多元素都生效。 */
	@Test
	public void aFacesDocumentBindsConditionsAndTemplates() {
		final String anchors = """
			{
			  "faces": {
			    "pis_1": {
			      "background": "#FF000000",
			      "vars": {"arriving": {"and": [{">": [{"var": "lzb.targetM"}, 0]}, {"<": [{"var": "lzb.targetM"}, 200]}]}},
			      "elements": [
			        {"type": "text", "text": "{pid.service}", "x": 0.1, "y": 0.5, "size": 0.4, "align": "left"},
			        {"type": "text", "text": "开往 {pid.terminus}", "y": 0.5,
			         "when": {"!=": [{"var": "pid.terminus"}, ""]}},
			        {"type": "text", "text": "即将到站", "y": 0.5, "when": {"var": "arriving"}},
			        {"type": "rect", "x": 0, "y": 0, "w": 1, "h": 0.05}
			      ],
			      "require": {"!=": [{"var": "pid.service"}, ""]}
			    }
			  }
			}
			""";
		final MmtrFaceDocument document = MmtrFaceDocument.fromAnchors("saf420", "pis_1", anchors);
		assertNotNull(document, "faces 段在，就该有文档");
		assertEquals(4, document.elements().size());
		assertEquals(0xFF000000, document.background());

		final Map<String, Object> data = new HashMap<>(data("00101", "海山", "鸥湾").asMap());
		data.put("lzb", Map.of("targetM", 120.0));
		assertEquals(List.of("00101", "开往 海山", "即将到站"), document.texts(data), "三段文字都该出来");
		assertTrue(document.visible(data));

		final Map<String, Object> far = new HashMap<>(data("00101", "海山", "鸥湾").asMap());
		far.put("lzb", Map.of("targetM", 900.0));
		assertEquals(List.of("00101", "开往 海山"), document.texts(far), "远着呢 ⇒ 不写「即将到站」");
	}

	/** 新面没有 {@code require} ⇒ 永远可见；写了假的 {@code require} ⇒ 不可见。 */
	@Test
	public void withoutARequireTheFaceIsAlwaysVisible() {
		final String anchors = "{\"faces\": {\"x_1\": {\"elements\": [{\"type\": \"text\", \"text\": \"hi\"}]}}}";
		final MmtrFaceDocument document = MmtrFaceDocument.fromAnchors("saf420", "x_1", anchors);
		assertNotNull(document);
		assertTrue(document.visible(Map.of()), "没写 require ⇒ 一直画");
		assertEquals(List.of("hi"), document.texts(Map.of()));
	}

	/** 文本元素缺省"超宽等比缩小"就是老版式那个 0.90（不一致会让长站名被牌边切掉）。 */
	@Test
	public void theDefaultShrinkToFitIsTheLegacyRatio() {
		assertEquals(0.90, MmtrFaceDocument.DEFAULT_SHRINK_TO_FIT, 1.0E-9);
	}

	/** 逐行对照：字、行数、位置、字号、像素密度、底色与文本色。 */
	private static void assertEquivalent(MmtrPidText.Board board, String anchors, String service, String terminus, String next) {
		final MmtrPidLayout legacy = MmtrPidLayout.parse("saf420", board, anchors);
		final MmtrFaceDocument document = MmtrFaceDocument.builtinPid(board, "saf420", anchors);
		final String label = board + " / 版式 " + anchors.replaceAll("\\s+", "") + " / 「" + service + "」「" + terminus + "」「" + next + "」";

		assertEquals(List.of(legacy.texts(service, terminus, next)), document.texts(data(service, terminus, next).asMap()),
			label + "：画出来的字不一致");
		assertEquals(legacy.rowCount(), document.elements().size(), label + "：行数不一致");
		assertEquals(legacy.pxPerMetre(), document.pxPerMetre(), label + "：像素密度不一致");
		for (int i = 0; i < legacy.rowCount() && i < document.elements().size(); i++) {
			final MmtrFaceDocument.Element element = document.elements().get(i);
			assertEquals(legacy.rowX(i), element.x(), 1.0E-9, label + "：第 " + i + " 行 x 不一致");
			assertEquals(legacy.rowY(i), element.y(), 1.0E-9, label + "：第 " + i + " 行 y 不一致");
			assertEquals(legacy.rowSize(i), element.size(), 1.0E-9, label + "：第 " + i + " 行字号不一致");
			assertEquals("text", element.type(), label + "：第 " + i + " 行应当是文本元素");
		}
	}

	/** 数据源：只有水牌那三项（其余字段取不到，与"车不在作业单上"的实况无关 —— 门由 require 判）。 */
	private static MmtrFaceData data(String service, String terminus, String next) {
		final Map<String, Object> values = new HashMap<>();
		values.put("pid.service", service);
		values.put("pid.terminus", terminus);
		values.put("pid.next", next);
		return MmtrFaceData.of(values::get);
	}
}
