package org.mtr.mod.render.panel;

import org.junit.jupiter.api.Test;
import org.mtr.mod.mmtr.MmtrPidText;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 水牌**版式**的不变量（notes/358）：不同车型不同尺寸与排版，靠"配置 → 锚点 JSON → 客户端按车型解析"实现。
 *
 * <p>这里只钉**解析与取字**（纯数据，不碰字体/画布），所以离线无头可跑；画出来好不好看仍然只能看图
 * （客户端会把首帧画布落成 PNG：{@code run/mmtr-panel-debug/*_pid_*.png}）。</p>
 *
 * <p>四条口径：</p>
 * <ol>
 *   <li><b>没写版式 = 默认版式</b>，而且默认版式画出来的字必须与 {@link MmtrPidText#lines} **完全一致**
 *       （否则"加版式"这件事会顺手改掉老包的样子）；</li>
 *   <li><b>水牌与下一站牌各读各的段</b>（{@code pid} / {@code next}）——同一车型两块牌可以完全不同；</li>
 *   <li><b>取不到内容的行不画</b>（终点未知的回库趟、下一站未知、字段拼错），而不是画个空位或占位符；</li>
 *   <li>版式里的数字都是**比例**且被钳在合法范围（字高 0.02..1.5 牌高、x/y 0..1）。</li>
 * </ol>
 */
public final class MmtrPidLayoutTests {

	private static final String VEHICLE = "saf420cab_a";
	private static final String SERVICE = "00101";
	private static final String TERMINUS = "海山";
	private static final String NEXT = "鸥湾";

	private static String withSections(String pid, String next) {
		return "{\"anchors\":[]," + (pid == null ? "" : "\"pid\":" + pid + ",") + (next == null ? "" : "\"next\":" + next + ",") + "\"rider\":{\"feetY\":1.0}}";
	}

	/* ============================ ① 没写版式 = 默认版式（与 lines() 逐字一致） ============================ */

	@Test
	public void withoutASectionTheDefaultLayoutIsUsed() {
		final MmtrPidLayout layout = MmtrPidLayout.parse(VEHICLE, MmtrPidText.Board.DESTINATION, withSections(null, null));
		assertTrue(layout.id().startsWith("default-"), "没有 pid 段 ⇒ 默认版式，实际 " + layout.id());
		assertArrayEquals(new String[]{SERVICE, TERMINUS}, layout.texts(SERVICE, TERMINUS, NEXT),
			"默认水牌 = 班次号在上、终点在下（与 MmtrPidText.lines 同一口径）");
	}

	/** ★ 这条是"加版式不许改老样子"的守卫：默认版式的取字结果必须与 lines() 一致（含空字段的各种组合）。 */
	@Test
	public void theDefaultLayoutAgreesWithTheUnversionedRules() {
		final String[][] cases = {
			{SERVICE, TERMINUS, NEXT},
			{SERVICE, "", NEXT},
			{SERVICE, TERMINUS, ""},
			{"", TERMINUS, NEXT}
		};
		for (final String[] c : cases) {
			for (final MmtrPidText.Board board : MmtrPidText.Board.values()) {
				final MmtrPidLayout layout = MmtrPidLayout.parse(VEHICLE, board, withSections(null, null));
				final MmtrPidText.Line[] fromRules = MmtrPidText.lines(board, c[0], c[1], c[2]);
				final String[] expected = new String[fromRules.length];
				for (int i = 0; i < fromRules.length; i++) {
					expected[i] = fromRules[i].text();
				}
				/*
				 * 只在**门开着**的时候比（lines() 非空）。
				 *
				 * 门关着的时候（作业单空 / 下一站牌没站名）默认版式里的固定字（"下一站"）仍然能解析出字来，
				 * 而那是**故意**的：门在 MmtrPidBoard 那边，整块牌根本不会被画（见下一条用例）。
				 * 版式这一层是纯展示的，让字面量行不受字段空值摆布，作者才能写"回库趟显示『回库』"这种版式。
				 */
				if (expected.length > 0) {
					assertArrayEquals(expected, layout.texts(c[0], c[1], c[2]), board + " / 班次号「" + c[0] + "」终点「" + c[1] + "」下一站「" + c[2] + "」");
				}
			}
		}
	}

	/* ============================ ② 作者写的版式（每个车型独立，两块牌各有各的段） ============================ */

	@Test
	public void anAuthoredLayoutReplacesTheDefault() {
		final String pid = "{\"background\":\"#FF101418\",\"pxPerMetre\":768,\"rows\":["
			+ "{\"field\":\"service\",\"x\":0.16,\"y\":0.5,\"size\":0.5,\"align\":\"left\"},"
			+ "{\"field\":\"terminus\",\"x\":0.62,\"y\":0.5,\"size\":0.62,\"prefix\":\"开往 \"}]}";
		final MmtrPidLayout layout = MmtrPidLayout.parse(VEHICLE, MmtrPidText.Board.DESTINATION, withSections(pid, null));

		assertArrayEquals(new String[]{SERVICE, "开往 " + TERMINUS}, layout.texts(SERVICE, TERMINUS, NEXT),
			"★ 一行并排的扁牌：班次号靠左、终点带「开往」前缀 —— 这是默认版式给不出的排版");
		assertEquals(2, layout.rowCount());
		assertEquals(0.16, layout.rowX(0), 1e-9);
		assertEquals(768, layout.pxPerMetre(), "作者可以给这块牌定像素密度");
		assertTrue(layout.id().startsWith(VEHICLE + "@"), "版式身份带车型，换车型会重画：" + layout.id());
	}

	@Test
	public void eachBoardReadsItsOwnSection() {
		final String pid = "{\"rows\":[{\"field\":\"terminus\",\"size\":0.8}]}";
		final String next = "{\"rows\":[{\"text\":\"下一站\",\"size\":0.2,\"y\":0.8},{\"field\":\"next\",\"size\":0.7,\"y\":0.35}]}";
		final MmtrPidLayout destination = MmtrPidLayout.parse(VEHICLE, MmtrPidText.Board.DESTINATION, withSections(pid, next));
		final MmtrPidLayout nextBoard = MmtrPidLayout.parse(VEHICLE, MmtrPidText.Board.NEXT_STATION, withSections(pid, next));

		assertArrayEquals(new String[]{TERMINUS}, destination.texts(SERVICE, TERMINUS, NEXT), "水牌只写了终点那一行");
		assertArrayEquals(new String[]{"下一站", NEXT}, nextBoard.texts(SERVICE, TERMINUS, NEXT), "下一站牌用自己的段（字面量 + 站名）");
	}

	/* ============================ ③ 取不到内容的行不画 ============================ */

	@Test
	public void rowsWithoutContentAreSkippedNotBlanked() {
		final String pid = "{\"rows\":[{\"field\":\"service\",\"size\":0.3},{\"field\":\"terminus\",\"size\":0.5},{\"field\":\"bogus\",\"size\":0.3}]}";
		final MmtrPidLayout layout = MmtrPidLayout.parse(VEHICLE, MmtrPidText.Board.DESTINATION, withSections(pid, null));

		assertArrayEquals(new String[]{SERVICE}, layout.texts(SERVICE, "", NEXT),
			"★ 回库趟（终点未知）：只画班次号那行；拼错的字段名不会画出任何东西，也不会画成空白行");
		assertArrayEquals(new String[]{SERVICE, TERMINUS}, layout.texts(SERVICE, TERMINUS, NEXT), "终点有值时两行都在");
		assertEquals(3, layout.rowCount(), "三行都还在版式里（只是其中一行取不到字）");
	}

	/**
	 * ★ **门与版式分开**这条口径的两半一起钉住。
	 *
	 * <p>版式是纯展示的：{@code text} 那种**字面量**行不看作业单、也不看字段空不空（作者要写
	 * "回库趟显示『回库』"就得靠这个）。而"没有作业单的车不挂牌"由 {@link MmtrPidText#lines} 那道门保证
	 * （{@code MmtrPidBoard} 拿它当开关）——两半都在，才既不会漏挂牌、也不会把字面量行写死。</p>
	 */
	@Test
	public void literalRowsAreBoardGatedNotLayoutGated() {
		final String pid = "{\"rows\":[{\"text\":\"回库\"},{\"field\":\"terminus\"}]}";
		final MmtrPidLayout layout = MmtrPidLayout.parse(VEHICLE, MmtrPidText.Board.DESTINATION, withSections(pid, null));

		assertArrayEquals(new String[]{"回库"}, layout.texts("", TERMINUS, NEXT),
			"版式这一层：字面量行照旧解析（它不依赖引擎给的字段）");
		assertEquals(0, MmtrPidText.lines(MmtrPidText.Board.DESTINATION, "", TERMINUS, NEXT).length,
			"★ 门那一层：不在作业单上 ⇒ lines() 为空 ⇒ MmtrPidBoard 整块不画（字面量也漏不出来）");
		assertEquals(0, MmtrPidText.lines(MmtrPidText.Board.NEXT_STATION, SERVICE, TERMINUS, "").length,
			"★ 下一站未知 ⇒ 门也关着（默认版式里那行固定的「下一站」不会被单独画出来）");
	}

	/* ============================ ④ 数字被钳在合法范围 / 空版式退回默认 ============================ */

	@Test
	public void sizesAndPositionsAreClamped() {
		final String pid = "{\"rows\":[{\"field\":\"terminus\",\"x\":9,\"y\":-3,\"size\":99}]}";
		final MmtrPidLayout layout = MmtrPidLayout.parse(VEHICLE, MmtrPidText.Board.DESTINATION, withSections(pid, null));
		assertEquals(1, layout.rowCount());
		assertEquals(1, layout.rowX(0), 1e-9, "x > 1 被钳到 1（牌面右边界）");
		assertEquals(0, layout.rowY(0), 1e-9, "y < 0 被钳到 0（牌面下边界）");
		assertEquals(1.5, layout.rowSize(0), 1e-9, "字高上限 1.5 牌高（再大就超出牌面了）");
		assertArrayEquals(new String[]{TERMINUS}, layout.texts(SERVICE, TERMINUS, NEXT), "坐标越界不影响取字");
	}

	@Test
	public void anEmptyOrBrokenLayoutFallsBackToTheDefault() {
		assertTrue(MmtrPidLayout.parse(VEHICLE, MmtrPidText.Board.DESTINATION, withSections("{\"rows\":[]}", null)).id().startsWith("default-"),
			"rows 为空 ⇒ 默认版式");
		assertTrue(MmtrPidLayout.parse(VEHICLE, MmtrPidText.Board.DESTINATION, withSections("{\"rows\":[{\"size\":0.5}]}", null)).id().startsWith("default-"),
			"有一行但既没 field 也没 text ⇒ 等于没写，退回默认");
		assertTrue(MmtrPidLayout.parse(VEHICLE, MmtrPidText.Board.DESTINATION, "这不是 JSON").id().startsWith("default-"),
			"坏 JSON 不许抛（渲染路径上抛异常会中断整帧剩下的车）：退回默认");
		assertTrue(MmtrPidLayout.parse(VEHICLE, MmtrPidText.Board.DESTINATION, "").id().startsWith("default-"), "空文件 ⇒ 默认");
	}
}
