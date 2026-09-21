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

	/** 版位：**右对齐**（用户 2026-09-21 由左下角改为右下角）。 */
	@Test
	public void theBlockIsFlushWithTheRightEdge() {
		final int[] windowWidths = {320, 427, 640, 854, 1920};
		final float[] scales = {2F, 4F, 4.5F};
		for (final int windowWidth : windowWidths) {
			for (final float scale : scales) {
				for (final int cellWidth : new int[]{8, 9, 11, 13}) {
					final int edgePadding = 8;
					final int left = MmtrSpeedHud.blockLeft(windowWidth, cellWidth, scale, edgePadding);
					final int right = left + Math.round(3 * cellWidth * scale);
					// ① 右边缘钉在"屏宽 - 留白" ⇒ 读数不会随数字宽度左右移动
					assertEquals(windowWidth - edgePadding, right,
						"屏宽 " + windowWidth + " 格宽 " + cellWidth + " 缩放 " + scale);
					// ② 整块必须还在屏幕里（窗口别太窄）
					assertTrue(left >= 0, "屏宽 " + windowWidth + " 下整块跑到屏幕外了：" + left);
				}
			}
		}
	}

	/** 背景楔形：直角在屏幕右下角、底边 = 屏宽 20%、左下顶点处"斜边与底边"夹角 = 30°。 */
	@Test
	public void theWedgeIsTwentyPercentWideAtThirtyDegrees() {
		final int[][] windows = {{320, 180}, {427, 240}, {640, 360}, {854, 480}, {1920, 1080}};
		for (final int[] windowSize : windows) {
			final int width = windowSize[0];
			final int height = windowSize[1];
			final int leg = MmtrSpeedHud.wedgeBottomLeg(width);
			final int wedgeHeight = MmtrSpeedHud.wedgeHeight(width);

			assertTrue(Math.abs(leg - width * 0.20) <= 1, "屏宽 " + width + "：底边应是 20%（" + leg + "）");
			final double angle = Math.toDegrees(Math.atan2(wedgeHeight, leg));
			assertTrue(Math.abs(angle - 30.0) <= 0.5, "屏宽 " + width + "：夹角应是 30°（实测 " + angle + "°）");
			assertTrue(wedgeHeight > 0 && wedgeHeight < height, "竖边要装得下：" + wedgeHeight + " / 屏高 " + height);
		}
	}

	@Test
	public void theWedgeRisesMonotonicallyToItsFullHeight() {
		final int width = 854;
		final int leg = MmtrSpeedHud.wedgeBottomLeg(width);
		final int wedgeHeight = MmtrSpeedHud.wedgeHeight(width);
		int previous = -1;
		for (int column = 1; column <= leg; column++) {
			final int columnHeight = MmtrSpeedHud.wedgeColumnHeight(width, column);
			assertTrue(columnHeight >= previous, "第 " + column + " 列高回落了（" + previous + " → " + columnHeight + "）");
			assertTrue(columnHeight <= wedgeHeight, "第 " + column + " 列高超过竖边高");
			previous = columnHeight;
		}
		assertEquals(wedgeHeight, previous, "最后一列恰好等于竖边高（楔形的右上顶点）");
		// 越界的列被钳住：不该把三角形画到楔形之外
		assertEquals(0, MmtrSpeedHud.wedgeColumnHeight(width, 0));
		assertEquals(0, MmtrSpeedHud.wedgeColumnHeight(width, -3));
		assertEquals(wedgeHeight, MmtrSpeedHud.wedgeColumnHeight(width, leg + 5));
	}
}
