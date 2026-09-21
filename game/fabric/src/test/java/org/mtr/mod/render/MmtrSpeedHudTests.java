package org.mtr.mod.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 屏幕右下角速度读数的排版不变量（notes/224、notes/225）。
 *
 * <p>用户口径：①「三个数字单独排版，**位置中点固定**，防止字宽不同导致整体伸缩」；
 * ②「背景楔形：直角在屏幕右下角、底边 20% 屏宽、斜边与底边 30°」；
 * ③「三角型斜边怎么有个方形的凸起」—— ③ 之后背景回到**纯三角形**，读数改成按斜边约束自动定字号。</p>
 *
 * <p>这里只放**纯算术**判据（不碰字体、不碰画布），所以离线、无头、游戏运行时都能跑。
 * 依赖真实字体的部分（"字号反推得对不对""内容是不是真的落在斜边里"）在离线探针
 * {@code sandbox/SpeedHudProbe} 与 {@code sandbox/SpeedHudPaintProbe} 里，那里有真画布和真字体。</p>
 */
public final class MmtrSpeedHudTests {

	/** 一个"比例字体"的样本字宽：1 窄、8 宽（真实 DIN 也是这个形状）。 */
	private static final double[] WIDTHS = {6, 3, 6, 6, 6, 6, 6, 6, 7, 6};

	/** ① 每位在自己的格子里居中；格心与"是哪一位数字"无关 ⇒ 0↔8 变化时中点不动。 */
	@Test
	public void everyDigitIsCentredInItsOwnCellRegardlessOfWidth() {
		final double cellWidth = 9; // 最宽 7 + 呼吸 2
		final double digitsRight = 100;
		for (int index = 0; index < 3; index++) {
			final double centre = MmtrSpeedHud.digitCentreX(index, cellWidth, digitsRight);
			final double expected = digitsRight - (3 - index) * cellWidth + cellWidth / 2;
			assertEquals(expected, centre, 1e-9, "第 " + index + " 位的格心");
			// ★ 关键：digitCentreX **不接受字宽参数**，所以 1（3 宽）与 8（7 宽）的中心按构造就相同。
			//   第一版是 offset(index, width, cellWidth) + width/2，靠整数除法的 1 px 容差才勉强相等。
			for (final double width : WIDTHS) {
				final double left = centre - width / 2;
				final double right = centre + width / 2;
				assertTrue(left >= centre - cellWidth / 2 - 1e-9 && right <= centre + cellWidth / 2 + 1e-9,
					"字宽 " + width + " 居中后不该越出本格（格宽 " + cellWidth + "）");
			}
		}
	}

	/** 格心之间的步长恒等于格宽 ⇒ 间距不随数字变化；整块宽度 = 3 × 格宽。 */
	@Test
	public void theCellsAreEvenlySpaced() {
		final double cellWidth = 11.5;
		final double digitsRight = 200;
		final double step = MmtrSpeedHud.digitCentreX(1, cellWidth, digitsRight) - MmtrSpeedHud.digitCentreX(0, cellWidth, digitsRight);
		assertEquals(cellWidth, step, 1e-9, "相邻格心步长 = 格宽");
		assertEquals(step,
			MmtrSpeedHud.digitCentreX(2, cellWidth, digitsRight) - MmtrSpeedHud.digitCentreX(1, cellWidth, digitsRight), 1e-9, "三位等距");
		assertEquals(digitsRight, MmtrSpeedHud.digitCentreX(2, cellWidth, digitsRight) + cellWidth / 2, 1e-9, "个位格右边缘 = 整块右边界");
		assertEquals(digitsRight - 3 * cellWidth, MmtrSpeedHud.digitCentreX(0, cellWidth, digitsRight) - cellWidth / 2, 1e-9, "整块左边界");
	}

	/** ② 限速牌里的数字：宽度放得下就用上限字号；放不下反复收缩；不管多挤都不小于保底值。 */
	@Test
	public void theLimitNumberShrinksToFitInsideTheSign() {
		final double maxInk = MmtrSpeedHud.LIMIT_TEXT_MAX_INK_HEIGHT;
		final double boxWidth = MmtrSpeedHud.limitBoxWidth(); // = 2 × (半径 − 环宽) × 0.92
		for (final double widthAtMaxInk : new double[]{6, 12, 16, 20, 26, 33}) {
			final double fitted = MmtrSpeedHud.fittedInkHeight(maxInk, boxWidth, widthPerInk(maxInk, widthAtMaxInk));
			if (widthAtMaxInk <= boxWidth) {
				assertEquals(maxInk, fitted, 1e-9, "宽 " + widthAtMaxInk + " 放得下，不该缩");
			} else if (maxInk * boxWidth / widthAtMaxInk >= MmtrSpeedHud.MIN_TEXT_INK_HEIGHT) {
				assertTrue(fitted < maxInk, "宽 " + widthAtMaxInk + " 该缩");
				assertEquals(boxWidth, widthAtMaxInk * fitted / maxInk, 1e-9, "缩完的宽度应贴住可用宽度");
			} else {
				assertEquals(MmtrSpeedHud.MIN_TEXT_INK_HEIGHT, fitted, 1e-9, "该落到保底字号");
			}
			assertTrue(fitted >= MmtrSpeedHud.MIN_TEXT_INK_HEIGHT && fitted <= maxInk, "字号在合法区间：" + fitted);
		}
	}

	/**
	 * ★ 宽度对字号**不严格线性**（AWT 会把字体尺寸取整）：一轮等比估算会缩不够。
	 *
	 * <p>离线探针实测的正是这一条：限速 "100" 一次估算得 8.69 号字，真实宽度 18.73 &gt; 可用 18.40
	 * —— 用户看到的"数字压住红圈"因此并没有被修掉。这里用一个"台阶 + 上取整"的模型复现它，
	 * 断言迭代之后的**真实宽度**确实放得下。</p>
	 */
	@Test
	public void theLimitNumberConvergesEvenWhenWidthIsNotLinearInInkHeight() {
		final double maxInk = MmtrSpeedHud.LIMIT_TEXT_MAX_INK_HEIGHT;
		final double boxWidth = MmtrSpeedHud.limitBoxWidth();
		final java.util.function.DoubleUnaryOperator stepped = ink -> {
			final double size = Math.max(1, Math.ceil(ink));
			return Math.ceil(size * 33 / 14 + 1);
		};
		final double fitted = MmtrSpeedHud.fittedInkHeight(maxInk, boxWidth, stepped);
		assertTrue(stepped.applyAsDouble(fitted) <= boxWidth,
			"迭代完的真实宽度 " + stepped.applyAsDouble(fitted) + " 必须放得下（可用 " + boxWidth + "）");
		assertTrue(fitted <= maxInk && fitted >= MmtrSpeedHud.MIN_TEXT_INK_HEIGHT, "字号在合法区间：" + fitted);
	}

	/** 线性宽度模型：字号 → 宽度。 */
	private static java.util.function.DoubleUnaryOperator widthPerInk(double maxInk, double widthAtMaxInk) {
		return ink -> widthAtMaxInk * ink / maxInk;
	}

	/** 楔形：底边 = 屏宽 20%（窄窗口走下限）、竖边 = 底边 × tan30°、画布就是它的外接矩形。 */
	@Test
	public void theWedgeIsTwentyPercentWideAtThirtyDegrees() {
		final int[][] windows = {{320, 180}, {427, 240}, {640, 360}, {854, 480}, {1920, 1080}};
		for (final int[] windowSize : windows) {
			final int width = windowSize[0];
			final int height = windowSize[1];
			final double leg = MmtrSpeedHud.wedgeBottomLeg(width);
			final double wedgeHeight = MmtrSpeedHud.wedgeHeight(width);

			assertEquals(Math.max(width * 0.20, MmtrSpeedHud.MIN_WEDGE_LEG), leg, 1e-9, "屏宽 " + width + "：底边");
			assertEquals(30.0, Math.toDegrees(Math.atan2(wedgeHeight, leg)), 0.5, "屏宽 " + width + "：斜边与底边夹角");
			assertTrue(wedgeHeight > 0 && wedgeHeight < height, "竖边要装得下：" + wedgeHeight + " / 屏高 " + height);
			// 画布 = 外接矩形 ⇒ 背景三角形正好铺满它的一半（±1 取整）
			assertEquals(leg, MmtrSpeedHud.canvasWidth(width), 1.0, "画布宽 = 底边");
			assertEquals(wedgeHeight, MmtrSpeedHud.canvasHeight(width), 1.0, "画布高 = 竖边");
		}
	}

	/**
	 * ★ 背景是**纯三角形**（用户：「三角型斜边怎么有个方形的凸起」）。
	 *
	 * <p>{@code insideWedge} 是"内容有没有落在斜边里"的唯一判据：斜边从左下 (0,0) 到右上 (leg, H)，
	 * 内侧就是 {@code y ≤ x·tan30°}。斜边上、斜边内为真；斜边外为假。</p>
	 */
	@Test
	public void theWedgeIsAPlainTriangleWithNothingStuckOnItsSlope() {
		final int screenWidth = 854;
		final double tan = Math.tan(Math.toRadians(30));
		// ① 右下角（直角顶点那一带）一定在里面；左下角之外（x 很小、y 很高）一定在外面
		assertTrue(MmtrSpeedHud.insideWedge(screenWidth, MmtrSpeedHud.canvasWidth(screenWidth) - 1, 1, 0), "右下角在内");
		assertTrue(!MmtrSpeedHud.insideWedge(screenWidth, 1, MmtrSpeedHud.canvasHeight(screenWidth) - 1, 0), "左上角在外");
		// ② 斜边上的点算"在内"（留白为 0 时），再往上一点就算"在外"
		for (final double x : new double[]{20, 40, 80, 120, 160}) {
			final double onSlope = x * tan;
			assertTrue(MmtrSpeedHud.insideWedge(screenWidth, x, onSlope, 0), "斜边上的点 (" + x + "," + onSlope + ") 应在内");
			assertTrue(!MmtrSpeedHud.insideWedge(screenWidth, x, onSlope + 0.5, 0), "斜边上方 0.5 应在外的 (" + x + ")");
			// ③ 留白是"往外推"：斜边下方只剩 1 个单位时，留白 2 就判为不在内
			assertTrue(!MmtrSpeedHud.insideWedge(screenWidth, x, onSlope - 1, 2), "留白把 1 个单位的余量吃掉了 (" + x + ")");
		}
	}

	/** 窄窗口下限：屏宽 20% 装不下读数时用 {@link MmtrSpeedHud#MIN_WEDGE_LEG}，常见窗口仍走 20%。 */
	@Test
	public void theWedgeKeepsAUsableMinimumOnNarrowWindows() {
		assertEquals(150.0, MmtrSpeedHud.wedgeBottomLeg(320), 1e-9, "320 宽：20% = 64 → 取下限");
		assertEquals(150.0, MmtrSpeedHud.wedgeBottomLeg(640), 1e-9, "640 宽：20% = 128 → 取下限");
		assertEquals(854 * 0.20, MmtrSpeedHud.wedgeBottomLeg(854), 1e-9, "854 宽：20% = 171 > 下限 → 按 20%");
		assertEquals(1920 * 0.20, MmtrSpeedHud.wedgeBottomLeg(1920), 1e-9, "1920 宽：384");
	}

	/**
	 * ★ 贴图四边形必须**钉在屏幕右下角**（用户：「UI 不在右下角了」）。
	 *
	 * <p>{@code GuiDrawing.drawTexture} 的 8 参数重载收的是**两个角** {@code (x1,y1,x2,y2)}，
	 * 不是 {@code (x,y,w,h)} —— 参数全是 {@code double}，把宽高填进后两个槽位照样编译通过，
	 * 结果四边形从 (屏宽−208, 屏高−104) 一路拉到 (208,104)，整块被摊到屏幕中上部。</p>
	 */
	@Test
	public void theHudQuadIsPinnedToTheBottomRightCorner() {
		final int[][] windows = {{320, 180}, {427, 240}, {640, 360}, {854, 480}, {1920, 1080}};
		for (final int[] windowSize : windows) {
			final int width = windowSize[0];
			final int height = windowSize[1];
			final double[] quad = MmtrSpeedHud.screenQuad(width, height);
			assertEquals(4, quad.length, "四边形是 x1,y1,x2,y2");
			// ① 尺寸必须正好是画布尺寸（w/h 填错槽位时这里立刻炸）
			assertEquals(MmtrSpeedHud.canvasWidth(width), quad[2] - quad[0], 1e-9, "屏 " + width + "：宽度 = 画布宽");
			assertEquals(MmtrSpeedHud.canvasHeight(width), quad[3] - quad[1], 1e-9, "屏 " + width + "：高度 = 画布高");
			// ② 右边缘与下边缘齐屏 ⇒ 贴着右下角
			assertEquals(width, quad[2], 1e-9, "屏 " + width + "：右边缘应齐屏");
			assertEquals(height, quad[3], 1e-9, "屏 " + width + "：下边缘应齐屏");
			// ③ x1<x2、y1<y2（反向矩形就是当初那个 bug）
			assertTrue(quad[0] < quad[2] && quad[1] < quad[3], "屏 " + width + "：四边形反向了");
			assertTrue(quad[0] >= 0 && quad[1] >= 0, "屏 " + width + "：整块应还在屏幕里");
		}
	}

	/** 单位字号跟着数字缩，但有下限；图标行顶边跟着数字走。 */
	@Test
	public void theUnitAndIconRowFollowTheDigitSize() {
		assertEquals(MmtrSpeedHud.MIN_UNIT_INK_HEIGHT, MmtrSpeedHud.unitInkHeight(0), 1e-9, "数字再小，单位不低于下限");
		assertEquals(MmtrSpeedHud.MIN_UNIT_INK_HEIGHT, MmtrSpeedHud.unitInkHeight(MmtrSpeedHud.MIN_DIGIT_INK_HEIGHT), 1e-9, "最小数字时取下限");
		assertEquals(36 * MmtrSpeedHud.UNIT_INK_RATIO, MmtrSpeedHud.unitInkHeight(36), 1e-9, "常规时按比例");
		assertEquals(
			MmtrSpeedHud.EDGE_PADDING + 30 + MmtrSpeedHud.ICON_GAP + 2 * MmtrSpeedHud.LIMIT_SIGN_RADIUS,
			MmtrSpeedHud.iconRowTop(30), 1e-9, "图标行顶边 = 留白 + 数字高 + 间隙 + 牌直径");
	}
}
