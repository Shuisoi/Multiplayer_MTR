package org.mtr.mod.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 区间带的**剪裁**（notes/286）：同侧、互相重叠的两条带必须剪开，否则两个四边形**完全共面** ——
 * 那一层是 {@code RenderLayer.getEntityCutout}（alpha-test、写深度），谁赢按像素随机，
 * 现场表现就是用户报的"有些颜色是透明的、不显示"。
 *
 * <p>口径与网页一致：**后写的盖住先写的**（载荷里靠后的那条赢）。这里只用纯函数钉住这条规则；
 * 几何（法向偏移、弧长取点）不在这层测。</p>
 */
public final class MmtrSectionBandsTests {

	@Test
	public void aLaterBandCutsTheEarlierOneOnTheSameSide() {
		// 同侧两条：先 [0,10]、后 [4,6] ⇒ 后写的赢，先写的被切成 [0,4] 与 [6,10]
		final List<List<double[]>> windows = MmtrSectionBands.visibleWindows(from(0, 4), to(10, 6), sides(1, 1));

		assertEquals(2, windows.get(0).size(), "先写的那条被中间那条切成两截");
		assertWindow(windows.get(0).get(0), 0, 4);
		assertWindow(windows.get(0).get(1), 6, 10);
		assertEquals(1, windows.get(1).size());
		assertWindow(windows.get(1).get(0), 4, 6);
	}

	@Test
	public void oppositeSidesNeverCutEachOther() {
		// 异侧：横向本来就分开了（0.1..0.9 与 −0.9..−0.1），谁都不该被剪
		final List<List<double[]>> windows = MmtrSectionBands.visibleWindows(from(0, 4), to(10, 6), sides(1, -1));

		assertEquals(1, windows.get(0).size());
		assertWindow(windows.get(0).get(0), 0, 10);
		assertEquals(1, windows.get(1).size());
		assertWindow(windows.get(1).get(0), 4, 6);
	}

	@Test
	public void theLastBandWinsNoMatterHowManyOverlap() {
		/*
		 * 三条同侧、层层包含：只有最后一条完整。
		 *
		 * 注意先写的那条被剪到哪里：中间那条**整段** [2,18] 都会挡住它 —— 即使中间那条自己也有
		 * 一段被最后一条盖住（那一段露出来的颜色属于最后一条，但**不属于**先写的那条）。
		 * 所以 c0 = [0,2] ∪ [18,20]，而不是 [0,5] ∪ [12,20]（我第一版手算就错在这里，被用例抓住）。
		 */
		final List<List<double[]>> windows = MmtrSectionBands.visibleWindows(from(0, 2, 5), to(20, 18, 12), sides(1, 1, 1));

		assertEquals(2, windows.get(0).size());
		assertWindow(windows.get(0).get(0), 0, 2);
		assertWindow(windows.get(0).get(1), 18, 20);
		assertEquals(2, windows.get(1).size());
		assertWindow(windows.get(1).get(0), 2, 5);
		assertWindow(windows.get(1).get(1), 12, 18);
		assertEquals(1, windows.get(2).size());
		assertWindow(windows.get(2).get(0), 5, 12);
	}

	@Test
	public void aFullyCoveredBandGetsNoWindowAtAll() {
		// 后写的把先写的整条盖住 ⇒ 先写的那条一个四边形都不画（这正是"共面打架"会随机吃掉的那一条）
		final List<List<double[]>> windows = MmtrSectionBands.visibleWindows(from(3, 0), to(7, 10), sides(1, 1));

		assertTrue(windows.get(0).isEmpty(), "整条被盖住 ⇒ 一个四边形都不画");
		assertEquals(1, windows.get(1).size());
		assertWindow(windows.get(1).get(0), 0, 10);
	}

	@Test
	public void touchingWindowsDoNotCutEachOther() {
		// 半开区间：[0,4) 与 [4,6) 恰好相接但不相交 ⇒ 都完整（相接处就是"区间边界"，不该被吃掉一截）
		final List<List<double[]>> windows = MmtrSectionBands.visibleWindows(from(0, 4), to(4, 6), sides(1, 1));

		assertEquals(1, windows.get(0).size());
		assertWindow(windows.get(0).get(0), 0, 4);
		assertEquals(1, windows.get(1).size());
		assertWindow(windows.get(1).get(0), 4, 6);
	}

	@Test
	public void theTrimmedWindowsNeverOverlapAndNeverLeaveHoles() {
		// 判据（"不再打架"的直接表述）：剪完以后同侧任意两段都不许相交；同时并集不许出现洞
		final List<List<double[]>> windows = MmtrSectionBands.visibleWindows(
			from(0, 5, 10, 0, 22), to(30, 25, 20, 8, 30), sides(1, 1, 1, 1, 1));

		final List<double[]> all = new ArrayList<>();
		windows.forEach(all::addAll);
		for (int i = 0; i < all.size(); i++) {
			for (int j = i + 1; j < all.size(); j++) {
				final boolean overlap = all.get(i)[0] < all.get(j)[1] - 1e-9 && all.get(j)[0] < all.get(i)[1] - 1e-9;
				assertTrue(!overlap, "剪完还相交：" + all.get(i)[0] + ".." + all.get(i)[1] + " 与 " + all.get(j)[0] + ".." + all.get(j)[1]);
			}
		}
		all.sort((a, b) -> Double.compare(a[0], b[0]));
		double coveredUntil = 0;
		for (final double[] window : all) {
			assertEquals(coveredUntil, window[0], 1e-9, "剪裁只能切、不能挖洞");
			coveredUntil = Math.max(coveredUntil, window[1]);
		}
		assertEquals(30, coveredUntil, 1e-9, "并集仍是整条 [0,30]");
	}

	private static double[] from(double... values) {
		return values;
	}

	private static double[] to(double... values) {
		return values;
	}

	private static int[] sides(int... values) {
		return values;
	}

	private static void assertWindow(double[] window, double from, double to) {
		assertEquals(from, window[0], 1e-9);
		assertEquals(to, window[1], 1e-9);
	}
}
