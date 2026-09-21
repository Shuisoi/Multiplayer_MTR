package org.mtr.mod.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 屏幕右下角速度读数的排版不变量（notes/223、notes/227）。
 *
 * <p>用户口径两条：①「三个数字单独排版，**位置中点固定**，防止字宽不同导致整体伸缩」；
 * ②「改成右下角，速度右加个 km/h，数字间距缩小，km/h 上面画限速图标，再加 AWS 图标指示」。</p>
 *
 * <p>第一版整幅是拿 MC 矩形原语拼的（圆 = 逐行条、楔形 = 逐列条），于是这个用例里曾经钉着
 * {@code circleHalfWidth} / {@code wedgeColumnHeight} / {@code digitsLeft} 这些**像素级拼装**的细节。
 * 用户一句「绘图也太粗糙了吧」之后整幅改走 Java2D 栅格化（{@link MmtrSpeedHud#paint}），
 * 圆和楔形由 Java2D 画、不必再自证；**留下来值得钉的是与字体相关的排版算术**——
 * 格心、按宽度反推字号，以及楔形几何。像素级结果由离线探针出 PNG 看（sandbox/SpeedHudPaintProbe）。</p>
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

	/** 格心之间的步长恒等于格宽 ⇒ 间距不随数字变化（用户要求"间距缩小点"后仍要均匀）。 */
	@Test
	public void theCellsAreEvenlySpaced() {
		final double cellWidth = 11.5;
		final double digitsRight = 200;
		final double step = MmtrSpeedHud.digitCentreX(1, cellWidth, digitsRight) - MmtrSpeedHud.digitCentreX(0, cellWidth, digitsRight);
		assertEquals(cellWidth, step, 1e-9, "相邻格心步长 = 格宽");
		assertEquals(step,
			MmtrSpeedHud.digitCentreX(2, cellWidth, digitsRight) - MmtrSpeedHud.digitCentreX(1, cellWidth, digitsRight), 1e-9, "三位等距");
		// 个位格右边缘 = 整块右边界（块宽 = 3 × 格宽，与数字无关）
		assertEquals(digitsRight, MmtrSpeedHud.digitCentreX(2, cellWidth, digitsRight) + cellWidth / 2, 1e-9);
		assertEquals(digitsRight - 3 * cellWidth, MmtrSpeedHud.digitCentreX(0, cellWidth, digitsRight) - cellWidth / 2, 1e-9, "整块左边界");
	}

	/** ② 限速牌里的数字：宽度放得下就用上限字号；放不下反复收缩；不管多挤都不小于保底值。 */
	@Test
	public void theLimitNumberShrinksToFitInsideTheSign() {
		final double maxInk = 14;
		final double boxWidth = 20;
		// 线性模型：宽度 ∝ 字号 —— 一轮就该收敛
		final java.util.function.DoubleUnaryOperator linear = widthPerInk(maxInk, 18);
		assertEquals(maxInk, MmtrSpeedHud.fittedInkHeight(maxInk, boxWidth, linear), 1e-9, "刚好放得下不该缩");
		assertEquals(maxInk, MmtrSpeedHud.fittedInkHeight(maxInk, boxWidth, widthPerInk(maxInk, boxWidth)), 1e-9, "正好等于可用宽度");
		for (final double widthAtMaxInk : new double[]{21, 28, 33, 40}) {
			final double fitted = MmtrSpeedHud.fittedInkHeight(maxInk, boxWidth, widthPerInk(maxInk, widthAtMaxInk));
			assertTrue(fitted < maxInk, "宽 " + widthAtMaxInk + " 该缩");
			assertEquals(boxWidth, widthAtMaxInk * fitted / maxInk, 1e-9, "缩完的宽度应贴住可用宽度");
		}
		// ★ 这条是这一版的由来：三位数 "160" 用 14 号字量出来 33 宽，白面直径只有 2×(13−3)=20
		//   ⇒ 不缩就会像离线 PNG 里那样，"1" 直接压在红圈上。
		assertTrue(MmtrSpeedHud.fittedInkHeight(maxInk, 2 * (13 - 3) * 0.92, widthPerInk(maxInk, 33)) < 14, "三位限速必须缩");
		// 保底：极端情况下也不返回 0/负数（否则 Java2D 会抛异常）
		assertEquals(6, MmtrSpeedHud.fittedInkHeight(maxInk, boxWidth, widthPerInk(maxInk, 10_000)), 1e-9, "保底 6");
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
		final double maxInk = 14;
		final double boxWidth = 18.4;
		// 台阶模型：字体尺寸取整到 1 px 且宽度上取整到 1 px（就是探针里量出来的那种不连续）
		final java.util.function.DoubleUnaryOperator stepped = ink -> {
			final double size = Math.max(1, Math.ceil(ink));
			return Math.ceil(size * 33 / 14 + 1); // 14 号字量出 33，往上取整再加 1 px 的"台阶"
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

	/** 背景楔形：直角在屏幕右下角、底边 = 屏宽 20%、左下顶点处"斜边与底边"夹角 = 30°。 */
	@Test
	public void theWedgeIsTwentyPercentWideAtThirtyDegrees() {
		final int[][] windows = {{320, 180}, {427, 240}, {640, 360}, {854, 480}, {1920, 1080}};
		for (final int[] windowSize : windows) {
			final int width = windowSize[0];
			final int height = windowSize[1];
			final double leg = MmtrSpeedHud.wedgeBottomLeg(width);
			final double wedgeHeight = MmtrSpeedHud.wedgeHeight(width);

			assertEquals(width * 0.20, leg, 1e-9, "屏宽 " + width + "：底边应是 20%");
			final double angle = Math.toDegrees(Math.atan2(wedgeHeight, leg));
			assertEquals(30.0, angle, 0.5, "屏宽 " + width + "：夹角应是 30°（实测 " + angle + "°）");
			assertTrue(wedgeHeight > 0 && wedgeHeight < height, "竖边要装得下：" + wedgeHeight + " / 屏高 " + height);
		}
	}

	/** 楔形随屏宽单调变长变高（画布宽固定，所以宽屏时楔形会铺满整个画布）。 */
	@Test
	public void theWedgeGrowsWithTheWindow() {
		double previousLeg = -1;
		double previousHeight = -1;
		for (final int width : new int[]{320, 427, 640, 854, 1280, 1920}) {
			final double leg = MmtrSpeedHud.wedgeBottomLeg(width);
			final double wedgeHeight = MmtrSpeedHud.wedgeHeight(width);
			assertTrue(leg > previousLeg && wedgeHeight > previousHeight, "屏宽 " + width + " 下楔形没有变大");
			previousLeg = leg;
			previousHeight = wedgeHeight;
		}
		// 854×480 这一档（用户实际窗口）竖边约 99 单位 —— 比画布矮，所以楔形只吃掉下半部分
		assertEquals(854 * 0.20 * Math.tan(Math.toRadians(30)), MmtrSpeedHud.wedgeHeight(854), 1e-9);
	}

	/**
	 * 背景 = 楔形 ∪ 读数底板（notes/227）。
	 *
	 * <p>★ 这条是"数字被斜边切掉"那个缺陷的回归判据：底板四角必须**全在背景里**，
	 * 否则白字会压在透明白底上；同时背景不能退化成整块矩形 —— 楔形的斜边得留着。</p>
	 */
	@Test
	public void theBackgroundCoversTheReadoutPlateWithoutLosingTheSlope() {
		for (final int screenWidth : new int[]{320, 427, 640, 854, 1280, 1920}) {
			// 底板：左边缘 = 三位块左边 82.92 − 6，顶边 = 图标行顶 74 + 6（与 paint() 同源）
			final double plateLeft = 82.92 - 6;
			final double plateTop = 74 + 6;
			final double[][] outline = MmtrSpeedHud.backgroundOutline(screenWidth, plateLeft, plateTop);

			assertEquals(outline[0].length, outline[1].length, "x/y 顶点数必须一致");
			assertTrue(outline[0].length >= 3, "至少是个三角形");

			// ① 底板四角（内缩 0.5）都要在背景里 —— 这就是"读数一定有暗底"
			final double[][] mustBeInside = {
				{plateLeft + 0.5, 0.5}, {plateLeft + 0.5, plateTop - 0.5},
				{MmtrSpeedHud.HUD_WIDTH - 0.5, plateTop - 0.5}, {MmtrSpeedHud.HUD_WIDTH - 0.5, 0.5},
				{plateLeft + 0.5, plateTop / 2}, // 底板左边缘那一列（数字块的最左边）
			};
			for (final double[] point : mustBeInside) {
				assertTrue(contains(outline, point[0], point[1]),
					"屏宽 " + screenWidth + "：底板上的点 (" + point[0] + "," + point[1] + ") 竟然没有背景");
			}

			// ② 屏幕右下角（楔形的直角顶点）必须在背景里
			assertTrue(contains(outline, MmtrSpeedHud.HUD_WIDTH - 0.5, 0.5), "屏宽 " + screenWidth + "：右下角没有背景");

			// ③ 但背景不能是整块矩形：屏幕左上角（斜边之上、底板之左）必须**空着**
			assertTrue(!contains(outline, 1, MmtrSpeedHud.HUD_HEIGHT - 1),
				"屏宽 " + screenWidth + "：背景退化成整块矩形了（左上角也被涂上了）");
		}
	}

	/** 射线法：点是否在（简单）多边形里。测试专用。 */
	private static boolean contains(double[][] outline, double x, double y) {
		final double[] xs = outline[0];
		final double[] ys = outline[1];
		boolean inside = false;
		for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
			if (ys[i] > y != ys[j] > y && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]) {
				inside = !inside;
			}
		}
		return inside;
	}
}
