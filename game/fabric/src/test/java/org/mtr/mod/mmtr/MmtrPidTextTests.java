package org.mtr.mod.mmtr;

import org.junit.jupiter.api.Test;
import org.mtr.mod.mmtr.MmtrPidText.Board;
import org.mtr.mod.mmtr.MmtrPidText.Line;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 水牌（PID）**内容与主次**的不变量（notes/357）。
 *
 * <p>与 {@code MmtrTaskHudTests} 同一个路子：这里只放不碰字体、不碰画布的判据 —— 离线、无头、
 * 游戏开着的时候都能跑。真画布/真字体的效果仍然只能起客户端肉眼看（{@code MmtrPidBoard} 那一半）。</p>
 *
 * <p>钉住的四条口径，每一条都是"顺手改一下就会错、而且错了不一定看得出来"的：</p>
 * <ol>
 *   <li><b>不在作业单上 ⇒ 整块牌不画</b>（不是画一块只有牌底的黑板）；</li>
 *   <li><b>回库趟照挂水牌，但不谎报终点</b>（只有班次号那一行）；</li>
 *   <li><b>下一站牌没有站名 ⇒ 整块不画</b>（写个"下一站"却空着比不写更容易误读）；</li>
 *   <li><b>主行是站名</b>（字大），班次号 / "下一站"是副行 —— 主次反了水牌就不好读。</li>
 * </ol>
 */
public final class MmtrPidTextTests {

	private static final String SERVICE = "00101";
	private static final String TERMINUS = "海山";
	private static final String NEXT = "鸥湾";

	@Test
	public void theDestinationBoardPutsTheServiceNumberAboveTheTerminus() {
		final Line[] lines = MmtrPidText.lines(Board.DESTINATION, SERVICE, TERMINUS, NEXT);
		assertEquals(2, lines.length, "水牌两行：班次号 + 终点");
		assertEquals(SERVICE, lines[0].text(), "上一行是班次号");
		assertFalse(lines[0].primary(), "班次号是副行（字小）");
		assertEquals(TERMINUS, lines[1].text(), "下一行是本趟终点");
		assertTrue(lines[1].primary(), "★ 站名是主行（字大）—— 主次反了水牌就不好读");
	}

	/** ★ 反例：车不挂在在跑的作业单上 ⇒ 一块牌都不画（终点/下一站有值也不行）。 */
	@Test
	public void noRunningJobMeansNoBoardAtAll() {
		assertEquals(0, MmtrPidText.lines(Board.DESTINATION, "", TERMINUS, NEXT).length, "没有班次号 ⇒ 不挂牌");
		assertEquals(0, MmtrPidText.lines(Board.NEXT_STATION, "", TERMINUS, NEXT).length, "没有班次号 ⇒ 连下一站牌也不画");
		assertEquals("水牌（不画）", MmtrPidText.describe(Board.DESTINATION, MmtrPidText.lines(Board.DESTINATION, "", TERMINUS, NEXT)));
	}

	/** ★ 回库趟：本趟没有站台目标 ⇒ 只写班次号，**不**编一个终点出来。 */
	@Test
	public void theReturnLegKeepsTheBoardButNeverInventsATerminus() {
		final Line[] lines = MmtrPidText.lines(Board.DESTINATION, SERVICE, "", NEXT);
		assertEquals(1, lines.length, "终点未知时只剩一行（班次号）");
		assertEquals(SERVICE, lines[0].text());
		assertFalse(lines[0].primary(), "只剩一行时它仍是副行 —— 免得被读成终点");
		assertEquals("水牌 00101", MmtrPidText.describe(Board.DESTINATION, lines));
	}

	@Test
	public void theNextStationBoardNeedsAStationToDraw() {
		final Line[] lines = MmtrPidText.lines(Board.NEXT_STATION, SERVICE, TERMINUS, NEXT);
		assertEquals(2, lines.length);
		assertEquals(MmtrPidText.NEXT_STATION_LABEL, lines[0].text(), "上一行是固定的「下一站」");
		assertFalse(lines[0].primary());
		assertEquals(NEXT, lines[1].text(), "下一行是站名");
		assertTrue(lines[1].primary(), "★ 站名是主行");

		// 反例：后面不再有站台作业 ⇒ 整块不画（写"下一站"却空着更糟）
		assertEquals(0, MmtrPidText.lines(Board.NEXT_STATION, SERVICE, TERMINUS, "").length);
		assertEquals("水牌 00101 / 海山", MmtrPidText.describe(Board.DESTINATION, MmtrPidText.lines(Board.DESTINATION, SERVICE, TERMINUS, "")));
	}

	/** 引擎给的是 std::string 语义的字段，可能带空白（作业单号是从 JSON 读的）—— 空白不算内容。 */
	@Test
	public void blankFieldsAreTrimmedAndCountAsEmpty() {
		final Line[] lines = MmtrPidText.lines(Board.DESTINATION, "  00101 ", " 海山 ", NEXT);
		assertEquals("00101", lines[0].text());
		assertEquals("海山", lines[1].text());
		assertEquals(0, MmtrPidText.lines(Board.DESTINATION, "   ", TERMINUS, NEXT).length, "全空白 = 没有班次号 ⇒ 不挂牌");
		assertEquals(0, MmtrPidText.lines(Board.NEXT_STATION, SERVICE, TERMINUS, "   ").length, "空白站名 = 没有下一站 ⇒ 不画");
	}

	/**
	 * ★ 内容签名：三项里任何一个变、或牌面尺寸变，都必须换签名（否则贴图不重画，牌上还是旧字）。
	 *
	 * <p>尺寸进签名这条特别容易漏：换了模型（牌变大）不重画就会把旧尺寸的字拉花。</p>
	 */
	@Test
	public void theSignatureCoversEveryFieldAndTheBoardSize() {
		final String base = MmtrPidText.signature(Board.DESTINATION, SERVICE, TERMINUS, NEXT, 1.2, 0.35);
		assertEquals(base, MmtrPidText.signature(Board.DESTINATION, SERVICE, TERMINUS, NEXT, 1.2, 0.35), "同样的输入 ⇒ 同样的签名（不重画）");
		assertNotEquals(base, MmtrPidText.signature(Board.NEXT_STATION, SERVICE, TERMINUS, NEXT, 1.2, 0.35), "换牌种要重画");
		assertNotEquals(base, MmtrPidText.signature(Board.DESTINATION, "00102", TERMINUS, NEXT, 1.2, 0.35), "换班次要重画");
		assertNotEquals(base, MmtrPidText.signature(Board.DESTINATION, SERVICE, "叶楼村", NEXT, 1.2, 0.35), "换终点要重画");
		assertNotEquals(base, MmtrPidText.signature(Board.DESTINATION, SERVICE, TERMINUS, "岩壁", 1.2, 0.35), "换下一站要重画");
		assertNotEquals(base, MmtrPidText.signature(Board.DESTINATION, SERVICE, TERMINUS, NEXT, 1.25, 0.35), "牌面尺寸变了要重画");
		assertNotEquals(base, MmtrPidText.signature(Board.DESTINATION, SERVICE, TERMINUS, NEXT, 1.2, 0.4), "牌面高度变了要重画");
	}
}
