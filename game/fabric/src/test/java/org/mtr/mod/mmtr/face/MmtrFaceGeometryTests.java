package org.mtr.mod.mmtr.face;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 装图 / 走马灯 / 翻牌机这三处**纯几何**的判据（notes/359 · F3）。
 *
 * <p>这三条都是"作者盯着看、引擎不吭声"的口径：图是拉满还是留白、走马灯在周期两端各在哪、
 * 翻牌机在第 25% 处转到哪 —— 画布那边（AWT / Canvas2D）钉不住，纯函数钉得住，所以它们住在这里。</p>
 */
public final class MmtrFaceGeometryTests {

	@Test
	public void stretchFillsTheWholeBox() {
		final double[] rect = MmtrFaceGeometry.fitRect("stretch", 0.1, 0.2, 0.4, 0.1, 4);
		assertArrayEquals(new double[]{0.1, 0.2, 0.4, 0.1}, rect);
	}

	/** contain：装得下、居中。宽图受宽度限制，高图受高度限制。 */
	@Test
	public void containFitsInsideAndCentres() {
		// 宽高比 4：框 1.0 x 0.4 ⇒ 按宽装 = 1.0 x 0.25（居中：y 多出 0.15，各一半）
		final double[] wide = MmtrFaceGeometry.fitRect("contain", 0, 0, 1.0, 0.4, 4);
		assertArrayEquals(new double[]{0, 0.075, 1.0, 0.25}, wide);
		// 高图（宽高比 0.5）：框 1.0 x 0.4 ⇒ 按高装 = 0.2 x 0.4
		final double[] tall = MmtrFaceGeometry.fitRect("contain", 0, 0, 1.0, 0.4, 0.5);
		assertArrayEquals(new double[]{0.4, 0, 0.2, 0.4}, tall);
	}

	/** cover：盖满、居中、超出的部分由调用方裁（坐标会是负的，这是对的 —— 裁在框里才看得对）。 */
	@Test
	public void coverOverflowsTheBoxOnPurpose() {
		final double[] wide = MmtrFaceGeometry.fitRect("cover", 0, 0, 1.0, 0.4, 4);
		assertArrayEquals(new double[]{-0.3, 0, 1.6, 0.4}, wide);
		assertTrue(wide[2] > 1.0, "盖满 ⇒ 宽比框大（多出来的左右各 0.3 由调用方裁掉）");
	}

	/** 不认识的 fit ⇒ 当 contain（"宁可留白也别溢出"是这里的选择，写在注释里）。 */
	@Test
	public void unknownFitFallsBackToContain() {
		assertArrayEquals(new double[]{0, 0.25, 1, 0.5}, MmtrFaceGeometry.fitRect("nope", 0, 0, 1, 1, 2));
		assertArrayEquals(new double[]{0, 0, 1, 1}, MmtrFaceGeometry.fitRect("contain", 0, 0, 1, 1, 0), "没有宽高比（图没加载出来）⇒ 拉满");
		assertArrayEquals(new double[]{0, 0, 0, 0}, MmtrFaceGeometry.fitRect("contain", 0, 0, 0, 0, 2), "零尺寸的框不该算出 NaN");
	}

	@Test
	public void marqueeStartsAtTheRightEdgeAndEndsPastTheLeftOne() {
		// 视口 [0.2, 0.6]（宽 0.4），文本 0.3 宽，接缝 0.1 ⇒ 全程 0.8
		assertEquals(0.6, MmtrFaceGeometry.marqueeLeft(0.2, 0.4, 0.3, 0.1, 0), 1.0E-9, "phase=0：文本左边缘在视口右缘（刚从右边进来）");
		assertEquals(0.2, MmtrFaceGeometry.marqueeLeft(0.2, 0.4, 0.3, 0.1, 0.5), 1.0E-9, "走了一半");
		assertEquals(-0.2, MmtrFaceGeometry.marqueeLeft(0.2, 0.4, 0.3, 0.1, 1), 1.0E-9, "phase=1：整段已经走出左边（左边缘在 -0.2，右边缘还在 0.1 处）");
	}

	/** 负相位/超范围相位都要钳住（否则文本会跳到视口外面去）。 */
	@Test
	public void marqueePhaseIsClamped() {
		assertEquals(MmtrFaceGeometry.marqueeLeft(0.2, 0.4, 0.3, 0.1, 0), MmtrFaceGeometry.marqueeLeft(0.2, 0.4, 0.3, 0.1, -5), 1.0E-9);
		assertEquals(MmtrFaceGeometry.marqueeLeft(0.2, 0.4, 0.3, 0.1, 1), MmtrFaceGeometry.marqueeLeft(0.2, 0.4, 0.3, 0.1, 9), 1.0E-9);
	}

	/**
	 * 翻牌机：一个周期里**前 `1 - turnFraction` 停在第 k 页**（正面就是 {@code pageIndex} 那一面），
	 * **最后 `turnFraction`** 翻到第 k+1 页，落地那一刻正好是 {@code pageIndex} 前进的时刻。
	 *
	 * <p>这条判据抓过两次真错：第一版"周期开头就翻"会让 {@code pageExpr} 指定页时整整差一页
	 * （正对面变成 k+1），而"一直匀速转"会像风车。</p>
	 */
	@Test
	public void theDrumRestsFlatThenTurnsInsideItsWindow() {
		// 3 面 ⇒ 每面 120 度；turnFraction 0.25 ⇒ 前 75% 停着、最后 25% 翻
		assertEquals(0, MmtrFaceGeometry.drumAngle(1, 1, 0, 0.25, 3), 1.0E-9, "停稳：第 1 面正对（= pageIndex 那一面）");
		assertEquals(-120, MmtrFaceGeometry.drumAngle(0, 1, 0, 0.25, 3), 1.0E-9, "第 0 面在第 1 页时偏 -120 度");
		assertEquals(-120, MmtrFaceGeometry.drumAngle(0, 1, 0.5, 0.25, 3), 1.0E-9, "★ 停住的这段时间里，正面还是 pageIndex 那一面（没提前翻）");
		assertEquals(-120, MmtrFaceGeometry.drumAngle(0, 1, 0.75, 0.25, 3), 1.0E-9, "窗口的起点：还没动");
		assertEquals(-180, MmtrFaceGeometry.drumAngle(0, 1, 0.875, 0.25, 3), 1.0E-9, "翻到一半（缓入缓出在 0.5 处正好是 0.5）");
		assertEquals(-240, MmtrFaceGeometry.drumAngle(0, 1, 1, 0.25, 3), 1.0E-9, "周期末尾：正好翻到第 2 页的位置（下一个周期从这里接上）");
		assertEquals(0, MmtrFaceGeometry.drumAngle(2, 2, 0, 0.25, 3), 1.0E-9, "第 2 页停稳时第 2 面正对");
		assertEquals(0, MmtrFaceGeometry.drumAngle(0, 0, 0, 0.25, 3), 1.0E-9, "★ pageExpr 指定页（clockFraction = 0）不翻滚：指定的那一面就是正面");
	}

	@Test
	public void smoothStepIsSShapedAndClamped() {
		assertEquals(0, MmtrFaceGeometry.smoothStep(0), 1.0E-9);
		assertEquals(1, MmtrFaceGeometry.smoothStep(1), 1.0E-9);
		assertEquals(0.5, MmtrFaceGeometry.smoothStep(0.5), 1.0E-9);
		assertEquals(1, MmtrFaceGeometry.smoothStep(5), 1.0E-9);
		assertEquals(0, MmtrFaceGeometry.smoothStep(-5), 1.0E-9);
	}

	/** 正 N 面棱柱：正对的那一面正好有面宽那么宽 ⇒ 半径 = w / (2·tan(π/N))。 */
	@Test
	public void prismRadiusMakesTheFrontFaceTheRightWidth() {
		assertEquals(0.6 / (2 * Math.tan(Math.PI / 3)), MmtrFaceGeometry.prismRadius(0.6, 3), 1.0E-9);
		assertEquals(0.5, MmtrFaceGeometry.prismRadius(1.0, 4), 1.0E-9, "4 面时 tan(45°)=1 ⇒ 半径 = 半个面宽");
	}

	/**
	 * **共享几何向量**（notes/360）：Java 与工作室跑同一份 {@code conformance/geometry.json}。
	 *
	 * <p>为什么这几条也要共享：装图方式与走马灯位置是"看得见的版式"，而它们在工作室里是
	 * Canvas2D 重写的一份实现 —— 只靠各自的感觉写，两边会差半个文本宽（走马灯首帧就是这条），
	 * 而作者会以为"预览与游戏不一样是正常的"。这里只放**两边都有的**两个函数；
	 * 翻牌机角度只有引擎有真几何，由本类的 {@code theDrumRestsFlatThenTurnsInsideItsWindow} 钉着。</p>
	 */
	@Test
	public void theSharedGeometryVectorsHold() {
		final java.nio.file.Path vectors = java.nio.file.Path.of("..", "..", "tools", "face-studio", "conformance", "geometry.json");
		final String text;
		try {
			text = java.nio.file.Files.readString(vectors, java.nio.charset.StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			fail("读不了共享几何向量 " + vectors.toAbsolutePath() + "：" + e.getMessage()
				+ "（这份文件由工作室侧维护：mmtr/tools/face-studio/conformance/geometry.json）");
			return;
		}
		final org.mtr.libraries.com.google.gson.JsonArray cases = org.mtr.libraries.com.google.gson.JsonParser.parseString(text)
			.getAsJsonObject().getAsJsonArray("cases");
		assertTrue(cases.size() >= 10, "共享几何向量只有 " + cases.size() + " 条 —— 装图三态 / 走马灯两端都该有几条");
		for (final var element : cases) {
			final var testCase = element.getAsJsonObject();
			final String name = testCase.get("name").getAsString();
			final var args = testCase.getAsJsonArray("args");
			switch (testCase.get("fn").getAsString()) {
				case "fitRect" -> {
					final double[] actual = MmtrFaceGeometry.fitRect(args.get(0).getAsString(),
						args.get(1).getAsDouble(), args.get(2).getAsDouble(), args.get(3).getAsDouble(),
						args.get(4).getAsDouble(), args.get(5).getAsDouble());
					final var expected = testCase.getAsJsonArray("expect");
					for (int i = 0; i < 4; i++) {
						assertEquals(expected.get(i).getAsDouble(), actual[i], 1.0E-4, name + "：第 " + i + " 项不一致");
					}
				}
				case "marqueeLeft" -> assertEquals(testCase.get("expect").getAsDouble(),
					MmtrFaceGeometry.marqueeLeft(args.get(0).getAsDouble(), args.get(1).getAsDouble(),
						args.get(2).getAsDouble(), args.get(3).getAsDouble(), args.get(4).getAsDouble()),
					1.0E-4, name + "：走马灯左边缘不一致");
				default -> fail(name + "：不认识的 fn「" + testCase.get("fn").getAsString() + "」（只支持 fitRect / marqueeLeft）");
			}
		}
	}

	private static void assertArrayEquals(double[] expected, double[] actual, String... because) {
		assertEquals(expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], actual[i], 1.0E-9, (because.length > 0 ? because[0] + "：" : "") + "第 " + i + " 项不一致");
		}
	}
}
