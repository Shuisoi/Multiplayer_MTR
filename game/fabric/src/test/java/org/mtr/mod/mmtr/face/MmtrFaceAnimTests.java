package org.mtr.mod.mmtr.face;

import org.junit.jupiter.api.Test;
import org.mtr.libraries.com.google.gson.JsonParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐帧动画的判据（notes/359 · F3）。
 *
 * <p>动画是"随时间变的东西"，最容易出的错不是画不出来，而是**在错的时间看起来是对的**：
 * 相位差半个周期、{@code fade} 写成锯齿波、时间取模对负数给出负余数（于是时间一到就跳回去）。
 * 这些用眼睛看不出，所以在这里按毫秒钉住。</p>
 */
public final class MmtrFaceAnimTests {

	private static MmtrFaceAnim parse(String json) {
		return MmtrFaceAnim.from(JsonParser.parseString(json));
	}

	@Test
	public void onlyTheFourKnownKindsAreAccepted() {
		assertEquals(MmtrFaceAnim.Kind.BLINK, parse("{\"kind\":\"blink\"}").kind());
		assertEquals(MmtrFaceAnim.Kind.MARQUEE, parse("{\"kind\":\"marquee\"}").kind());
		assertEquals(MmtrFaceAnim.Kind.FADE, parse("{\"kind\":\"fade\"}").kind());
		assertEquals(MmtrFaceAnim.Kind.SPIN, parse("{\"kind\":\"spin\"}").kind());
		assertEquals(4, MmtrFaceAnim.kinds().size());
	}

	/** 拼错的 kind 当"没有动画"（静态显示），**不是**抛异常 —— 资源包是不可信内容。 */
	@Test
	public void aTypoInKindMeansNoAnimation() {
		assertNull(parse("{\"kind\":\"blnik\"}"));
		assertNull(parse("{}"));
		assertNull(parse("null"));
		assertNull(MmtrFaceAnim.from(null));
	}

	@Test
	public void defaultsMatchTheDocumentedOnes() {
		final MmtrFaceAnim blink = parse("{\"kind\":\"blink\"}");
		assertEquals(600, blink.onMs());
		assertEquals(600, blink.offMs());
		assertEquals(4000, parse("{\"kind\":\"marquee\"}").spanMs());
		assertEquals(1500, parse("{\"kind\":\"fade\"}").spanMs());
		assertEquals(2000, parse("{\"kind\":\"spin\"}").spanMs());
		assertEquals(0.3, parse("{\"kind\":\"marquee\"}").gapRatio());
		assertEquals(0.25, parse("{\"kind\":\"fade\"}").minAlpha());
		assertFalse(parse("{\"kind\":\"marquee\"}").rightwards(), "缺省向左走");
		assertTrue(parse("{\"kind\":\"marquee\",\"direction\":\"right\"}").rightwards());
		assertTrue(parse("{\"kind\":\"marquee\",\"direction\":\" RIGHT \"}").rightwards(), "大小写与空白不敏感");
		// ★ 只有明确写 right 才反过来：拼错的 "rihgt"/"left"/"" 都按向左走 ——
		// 与工作室的 anim.mjs 同一条口径（这条差异是逐条对照时发现的：Java 原来写的是"不是 left 就算 right"）
		assertFalse(parse("{\"kind\":\"marquee\",\"direction\":\"rihgt\"}").rightwards(), "拼错的方向 = 向左（不是随机换向）");
		assertFalse(parse("{\"kind\":\"marquee\",\"direction\":\"left\"}").rightwards());
	}

	@Test
	public void blinkIsOnForOnMsThenOffForOffMs() {
		final MmtrFaceAnim blink = parse("{\"kind\":\"blink\",\"onMs\":600,\"offMs\":400}");
		assertTrue(blink.visible(0), "刚开始是亮的");
		assertTrue(blink.visible(599));
		assertFalse(blink.visible(600), "满 600ms 转灭");
		assertFalse(blink.visible(999));
		assertTrue(blink.visible(1000), "一个周期 1000ms");
	}

	/** 亮灭都写 0 ⇒ 当静态显示（作者的本意不是"永远不画"）。 */
	@Test
	public void aBlinkWithNoTimeStaysVisible() {
		final MmtrFaceAnim blink = parse("{\"kind\":\"blink\",\"onMs\":0,\"offMs\":0}");
		assertTrue(blink.visible(0));
		assertTrue(blink.visible(123456));
		assertFalse(blink.animates(), "没有时间在走 ⇒ 文档不该为它带时间桶");
	}

	@Test
	public void phaseWalksZeroToOneWithinThePeriod() {
		final MmtrFaceAnim fade = parse("{\"kind\":\"fade\",\"spanMs\":1000}");
		assertEquals(0, fade.phase(0));
		assertEquals(0.25, fade.phase(250));
		assertEquals(0.75, fade.phase(1750));
	}

	@Test
	public void fadeIsATriangleWaveNotASawtooth() {
		final MmtrFaceAnim fade = parse("{\"kind\":\"fade\",\"spanMs\":1000,\"min\":0.25}");
		assertEquals(0.25, fade.alpha(0), 1.0E-9);
		assertEquals(1.0, fade.alpha(500), 1.0E-9, "半周期最亮");
		assertEquals(0.25, fade.alpha(1000), 1.0E-9, "整周期回到最暗（★ 锯齿波会在这里突然跳到 1）");
		assertEquals(0.625, fade.alpha(250), 1.0E-9);
	}

	@Test
	public void marqueeTravelsAndTheDirectionFlipsIt() {
		final MmtrFaceAnim left = parse("{\"kind\":\"marquee\",\"spanMs\":1000}");
		assertEquals(0, left.offsetFraction(0));
		assertEquals(0.5, left.offsetFraction(500));
		final MmtrFaceAnim right = parse("{\"kind\":\"marquee\",\"spanMs\":1000,\"direction\":\"right\"}");
		assertEquals(1, right.offsetFraction(0));
		assertEquals(0.5, right.offsetFraction(500));
	}

	@Test
	public void spinTurnsOncePerSpan() {
		final MmtrFaceAnim spin = parse("{\"kind\":\"spin\",\"spanMs\":1000}");
		assertEquals(0, spin.degrees(0));
		assertEquals(90, spin.degrees(250));
		assertEquals(270, spin.degrees(750));
		assertEquals(0, parse("{\"kind\":\"blink\"}").degrees(250), "别的动画不转");
	}

	/** 时间桶：{@code fps} 越界用缺省 8；同一个桶里必须给出同一个桶号（否则每帧都重画）。 */
	@Test
	public void theBucketQuantisesTimeToTheDocumentedFps() {
		assertEquals(0, MmtrFaceAnim.bucket(0, 8));
		assertEquals(0, MmtrFaceAnim.bucket(124, 8));
		assertEquals(1, MmtrFaceAnim.bucket(125, 8));
		assertEquals(8, MmtrFaceAnim.bucket(1000, 8));
		assertEquals(30, MmtrFaceAnim.bucket(1000, 30));
		assertEquals(MmtrFaceAnim.bucket(1000, MmtrFaceAnim.DEFAULT_FPS), MmtrFaceAnim.bucket(1000, 0), "fps=0 用缺省 8");
		assertEquals(MmtrFaceAnim.bucket(1000, MmtrFaceAnim.DEFAULT_FPS), MmtrFaceAnim.bucket(1000, 99), "fps=99 用缺省 8");
	}

	/** 时钟走到负数（用例、时钟回拨）不该让相位变成负的 —— Java 的 {@code %} 会给负余数。 */
	@Test
	public void aNegativeClockStillWalksForward() {
		final MmtrFaceAnim fade = parse("{\"kind\":\"fade\",\"spanMs\":1000}");
		assertEquals(0.75, fade.phase(-250), 1.0E-9);
		assertTrue(fade.alpha(-250) >= 0.25);
	}
}
