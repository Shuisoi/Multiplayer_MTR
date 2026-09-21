package org.mtr.mod.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 左下角速度读数的排版不变量（notes/223）。用户口径：「三个数字单独排版，**位置中点固定**，
 * 防止字宽不同导致整体伸缩」——这条不变量就是本用例钉的东西。
 *
 * <p>纯整数算术，不碰 MC：{@code digitOffset(index, digitWidth, cellWidth)} 必须让**格子中心与
 * 字宽无关**（比例字体里 1 比 8 窄，整串画会让读数整体左右抖）。</p>
 */
public final class MmtrSpeedHudTests {

	/** 一个"比例字体"的样本字宽：1 窄、8 宽（真实 DIN 也是这个形状）。 */
	private static final int[] WIDTHS = {6, 3, 6, 6, 6, 6, 6, 6, 7, 6};

	@Test
	public void everyDigitIsCentredInItsOwnCellRegardlessOfWidth() {
		final int cellWidth = 9; // 最宽 7 + 呼吸 2
		for (int index = 0; index < 3; index++) {
			final int targetCenter = index * cellWidth + cellWidth / 2;
			for (final int width : WIDTHS) {
				final int offset = MmtrSpeedHud.digitOffset(index, width, cellWidth);
				final int centre = offset + width / 2;
				// 整数除法有 1 px 取整误差，所以判据是"偏差 ≤ 1 px"，不是相等
				assertTrue(Math.abs(centre - targetCenter) <= 1,
					"格 " + index + " 字宽 " + width + "：中心 " + centre + " 应贴近 " + targetCenter);
			}
		}
	}

	@Test
	public void aNarrowDigitKeepsTheSameCentreAsAWideOne() {
		// ★ 这里刻意**不**断言"左边缘一致"：居中之后 1 的左边缘必然比 8 靠右（本样本差 2 px），
		//   那不是抖动，而正是"中点固定"的结果。第一版判据写成左边缘差 ≤1 px，被离线探针当场判 FAIL，
		//   才看清自己把要求读成了"等宽网格"。用户要的是**中点**固定。
		final int cellWidth = 9;
		for (int index = 0; index < 3; index++) {
			final int narrowCentre = MmtrSpeedHud.digitOffset(index, WIDTHS[1], cellWidth) + WIDTHS[1] / 2;
			final int wideCentre = MmtrSpeedHud.digitOffset(index, WIDTHS[8], cellWidth) + WIDTHS[8] / 2;
			assertTrue(Math.abs(narrowCentre - wideCentre) <= 1,
				"第 " + index + " 位：1 与 8 的中心应一致（" + narrowCentre + " vs " + wideCentre + "）");
		}
	}

	@Test
	public void everyDigitStaysInsideItsOwnCell() {
		final int cellWidth = 9;
		for (int index = 0; index < 3; index++) {
			for (final int width : WIDTHS) {
				final int offset = MmtrSpeedHud.digitOffset(index, width, cellWidth);
				assertTrue(offset >= index * cellWidth, "第 " + index + " 位不得压到左邻格");
				assertTrue(offset + width <= (index + 1) * cellWidth, "第 " + index + " 位不得压到右邻格");
			}
		}
	}

	@Test
	public void theBlockIsThreeFixedCellsWide() {
		// 整块宽度只由格宽决定 ⇒ 读数从 199 掉到 100 时不会缩一截
		final int cellWidth = 9;
		assertEquals(27, cellWidth * 3, "三位固定格宽");
		assertEquals(0, MmtrSpeedHud.digitOffset(0, cellWidth, cellWidth), "与格子一样宽的数字（取最大格宽）从 0 开始");
		assertTrue(MmtrSpeedHud.digitOffset(2, WIDTHS[8], cellWidth) < 27, "第三位仍在整块内");
	}
}
