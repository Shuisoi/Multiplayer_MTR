package org.mtr.mod.mmtr.face;

import java.util.List;
import java.util.Locale;

/**
 * 面文档里几处**纯几何**（notes/359 · F3）：装图方式、走马灯位置、翻牌机角度。
 *
 * <p>放在纯数据层（{@code mmtr.face}）而不是画法里，是因为这几条是"作者会盯着看"的口径：
 * 装图到底按宽还是按高、走马灯在周期两端各在哪、翻牌机在第 25% 处转到哪 —— 都是可以写用例钉住的，
 * 而画布那边（AWT / Canvas2D）钉不住。工作室有一份同口径的 JS 实现，两边跑同一份向量。</p>
 */
public final class MmtrFaceGeometry {

	/** 装图方式的名字（打包校验与工作室的下拉都读它）。 */
	public static final List<String> FITS = List.of("stretch", "contain", "cover");

	private MmtrFaceGeometry() {
	}

	/**
	 * 一张图装进 {@code boxW × boxH} 的框里，返回它**实际占的矩形** {@code [x, y, w, h]}
	 * （{@code x}/{@code y} 是左下角，与 {@code rect} 同一条口径）。
	 *
	 * <ul>
	 *   <li>{@code stretch} —— 拉满整个框（会变形）；</li>
	 *   <li>{@code contain} —— 等比缩到**装得下**，居中，多出来的留白；</li>
	 *   <li>{@code cover} —— 等比缩到**盖满**，居中，超出的部分由调用方裁掉（{@code clip}）。</li>
	 * </ul>
	 *
	 * @param aspect 图的高宽比倒数（{@code 宽 / 高}）；非正数 ⇒ 当 {@code stretch}
	 */
	public static double[] fitRect(String fit, double boxX, double boxY, double boxW, double boxH, double aspect) {
		if (!(boxW > 0) || !(boxH > 0)) {
			return new double[]{boxX, boxY, Math.max(0, boxW), Math.max(0, boxH)};
		}
		final String mode = fit == null ? "" : fit.trim().toLowerCase(Locale.ROOT);
		if (!(aspect > 0) || "stretch".equals(mode) || mode.isEmpty()) {
			return new double[]{boxX, boxY, boxW, boxH};
		}
		double width = boxW;
		double height = width / aspect;
		if ("cover".equals(mode)) {
			if (height < boxH) {
				height = boxH;
				width = height * aspect;
			}
		} else {
			// contain（以及任何没见过的名字：缩到装得下比溢出安全）
			if (height > boxH) {
				height = boxH;
				width = height * aspect;
			}
		}
		return new double[]{boxX + (boxW - width) / 2, boxY + (boxH - height) / 2, width, height};
	}

	/**
	 * 走马灯里文本**左边缘**的横坐标（米）。
	 *
	 * <p>视口是 {@code [left, left + viewportWidth]}：{@code phase = 0} 时文本刚从视口右边进来
	 * （左边缘在视口右缘），{@code phase → 1} 时整段已经走出左边（左边缘在 {@code left - 文本宽 - gap}）。
	 * 于是"要滚多远" = 视口宽 + 文本宽 + 接缝 —— 接缝就是下一遍的起点与这一遍的尾巴之间的空当。</p>
	 *
	 * @param phase 0..1（来自 {@link MmtrFaceAnim#offsetFraction}，方向已经算进去了）
	 */
	public static double marqueeLeft(double left, double viewportWidth, double textWidth, double gapM, double phase) {
		final double travel = Math.max(0, viewportWidth) + Math.max(0, textWidth) + Math.max(0, gapM);
		return left + Math.max(0, viewportWidth) - clamp01(phase) * travel;
	}

	/**
	 * 翻牌机：第 {@code faceIndex} 面相对"正对读它的人"转了多少度（绕水平轴）。
	 *
	 * <p><b>翻转发生在周期末尾</b>：一个周期里前 {@code 1 - turnFraction} 的时间**停在第 k 页**
	 * （和第 i 面正对 = 0 度），最后 {@code turnFraction} 的时间（缓入缓出）翻到第 k+1 页，
	 * 落地那一刻正好是 {@code pageIndex} 前进的时刻。这样做有两个好处，都是"不这样做就会错"的：</p>
	 * <ul>
	 *   <li>停稳的时候**正面就是 {@code pageIndex} 那一面** —— 与"不用翻牌机的多页"（页在周期末尾才换）
	 *       是同一条时间线，作者把 {@code drum} 去掉时看到的换页时刻不会变；</li>
	 *   <li>{@code pageExpr} 指定页（{@code clockFraction = 0}）时**不翻** —— 否则会整整差一页
	 *       （F3 第一版的判据就是"停稳后正面应该是 0 度"，当时写成"周期开头就翻"，被这条用例抓住）。</li>
	 * </ul>
	 *
	 * <p>现实里的翻牌牌就是这样"啪"地翻过去然后停着；一直匀速转反而像风车。</p>
	 *
	 * @param clockFraction 本页周期走到哪儿了（0..1；{@link MmtrFaceDocument#pageClockFraction}）
	 * @param count         棱柱面数（≥ 2）
	 */
	public static double drumAngle(int faceIndex, int pageIndex, double clockFraction, double turnFraction, int count) {
		final double step = 360.0 / Math.max(2, count);
		final double window = turnFraction <= 0 ? 1 : Math.min(1, turnFraction);
		final double remaining = Math.max(0, 1 - window);
		final double progress = smoothStep(clamp01(clockFraction) <= remaining ? 0 : (clamp01(clockFraction) - remaining) / window);
		return (faceIndex - pageIndex - progress) * step;
	}

	/** 缓入缓出（0→0、1→1、S 形）；翻牌机用它，免得"啪"的那一下太生硬。 */
	public static double smoothStep(double value) {
		final double x = clamp01(value);
		return x * x * (3 - 2 * x);
	}

	/** 正 N 面棱柱的外接半径（面宽 {@code widthM} 时要多大才能让正对的那一面正好是 {@code widthM} 宽）。 */
	public static double prismRadius(double widthM, int count) {
		final int faces = Math.max(2, count);
		return widthM / (2 * Math.tan(Math.PI / faces));
	}

	private static double clamp01(double value) {
		return Math.max(0, Math.min(1, value));
	}
}
