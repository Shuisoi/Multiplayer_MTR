package org.mtr.mod.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 左上角作业卡里**纯算术**那部分的不变量（notes/280 §1.4 —— 「显示到目标点的距离」）。
 *
 * <p>与速度 HUD 同一个路子（{@link MmtrSpeedHudTests}，notes/224）：只放不碰字体、不碰画布的判据，
 * 于是离线、无头、游戏还开着的时候都能跑。真画布/真字体的验证仍然只能靠起客户端肉眼看。</p>
 *
 * <p>这里锁住的是**两条容易被后人"顺手改回去"的口径**：</p>
 * <ol>
 *   <li>没有武装的目标点 ⇒ **整行不画**（不是画一个 {@code 0 m}）；</li>
 *   <li>单位**固定是米**，永不换算成 km —— 司机进站是按米刹车的（见下）。</li>
 * </ol>
 */
public final class MmtrTaskHudTests {

	/** 没有武装的目标点（引擎给的哨兵 {@code -1}）⇒ 空串 ⇒ 卡片上整行不出现。 */
	@Test
	public void noArmedTargetDrawsNoRow() {
		assertEquals("", MmtrTaskHud.distanceText(-1), "没有目标点时应返回空串（整行不画）");
	}

	/** 取整到米、带上单位；{@code 0} 是有意义的（车正停在目标点上），要画。 */
	@Test
	public void theDistanceIsRoundedToWholeMetres() {
		assertEquals("0 m", MmtrTaskHud.distanceText(0), "停在目标点上 ⇒ 0 m（这一行仍然要有）");
		assertEquals("1 m", MmtrTaskHud.distanceText(0.6), "四舍五入到米");
		assertEquals("320 m", MmtrTaskHud.distanceText(320.4));
		assertEquals("321 m", MmtrTaskHud.distanceText(320.6));
	}

	/**
	 * ★ **单位固定是米**（口径，不是没实现 km）。
	 *
	 * <p>司机在进站那几秒是**按米**刹车的：{@code 1240 m} 比 {@code 1.2 km} 直接；而 1 km 以上若只留
	 * 一位小数，{@code 1240} 会被显示成 {@code 1.2}（丢掉 40 m）。这条线只有几公里，米制不会长到读不下。</p>
	 */
	@Test
	public void theUnitStaysInMetresEvenForLongRuns() {
		assertEquals("999 m", MmtrTaskHud.distanceText(999.4), "不会在 1 km 处改单位");
		assertEquals("1000 m", MmtrTaskHud.distanceText(999.5), "跨过 1 km 仍是米");
		assertEquals("1240 m", MmtrTaskHud.distanceText(1240.2), "1240 m 不能写成 1.2 km");
		assertEquals("15000 m", MmtrTaskHud.distanceText(15000), "再长也还是米");
	}

	/** 读数只由"数字 + 空格 + m"构成：没有负号、没有小数点（负值在上游已夹成 0）。 */
	@Test
	public void theReadoutIsAPlainNonNegativeIntegerInMetres() {
		for (final double metres : new double[]{0, 0.4, 1, 87.5, 320, 1240.2, 15000}) {
			final String text = MmtrTaskHud.distanceText(metres);
			assertTrue(text.endsWith(" m"), "读数应以 \" m\" 结尾：" + text);
			assertTrue(text.indexOf('-') < 0 && text.indexOf('.') < 0, "读数不该有负号或小数点：" + text);
			final int value = Integer.parseInt(text.substring(0, text.length() - 2));
			assertTrue(value >= 0, "读数不该是负数：" + text);
		}
	}
}
