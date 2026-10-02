package org.mtr.core.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link MmtrDisplayMirror} 的用例（notes/369 §8）。
 *
 * <h2>它钉的是什么</h2>
 * <p>这个 record 的意义是"**这些**字段参与标脏判据"。所以用例逐个字段改一遍、要求它被判成"变了"——
 * 谁把某个字段从 record 里删掉（或改错类型），这条用例立刻红或者**编不过**，
 * 于是"某个 HUD 读数停在旧值"这种静默错在编译期就被拦下来。</p>
 *
 * <p>它**不**测 {@code record} 的 equals 本身（那是语言给的），测的是"字段清单没有被悄悄缩短"。</p>
 */
public final class MmtrDisplayMirrorTests {

	private static final MmtrDisplayMirror BASE = new MmtrDisplayMirror("", 0, false, 0, 0, -1, false, false, false, 0);

	@Test
	public void identicalValuesAreNotADirtyReason() {
		assertEquals(BASE, new MmtrDisplayMirror("", 0, false, 0, 0, -1, false, false, false, 0),
			"一模一样就不该标脏（否则每次比都脏 = 每 tick 一份快照）");
	}

	/** 司机最需要"为什么不动"的那一个字段。 */
	@Test
	public void aChangedHoldReasonIsADirtyReason() {
		assertNotEquals(BASE, new MmtrDisplayMirror("闭塞区间占用（S1）", 0, false, 0, 0, -1, false, false, false, 0));
	}

	@Test
	public void everyOtherParticipatingFieldIsADirtyReason() {
		assertNotEquals(BASE, new MmtrDisplayMirror("", 80, false, 0, 0, -1, false, false, false, 0), "限速");
		assertNotEquals(BASE, new MmtrDisplayMirror("", 0, true, 0, 0, -1, false, false, false, 0), "LZB 监督中");
		assertNotEquals(BASE, new MmtrDisplayMirror("", 0, false, 100, 0, -1, false, false, false, 0), "LZB 限速顶棚");
		assertNotEquals(BASE, new MmtrDisplayMirror("", 0, false, 0, 60, -1, false, false, false, 0), "LZB 目标速度");
		assertNotEquals(BASE, new MmtrDisplayMirror("", 0, false, 0, 0, 350.5, false, false, false, 0), "LZB 目标距离");
		assertNotEquals(BASE, new MmtrDisplayMirror("", 0, false, 0, 0, -1, true, false, false, 0), "AWS 报警");
		assertNotEquals(BASE, new MmtrDisplayMirror("", 0, false, 0, 0, -1, false, true, false, 0), "AWS 已确认");
		assertNotEquals(BASE, new MmtrDisplayMirror("", 0, false, 0, 0, -1, false, false, true, 0), "闭塞扣车");
		assertNotEquals(BASE, new MmtrDisplayMirror("", 0, false, 0, 0, -1, false, false, false, 12), "调车授权剩余秒数");
	}

	/** 授权倒计时是**每秒**都在变的：它必须进判据，否则司机看着"还剩 12 秒"停在那里。 */
	@Test
	public void theCountdownChangesOnceASecond() {
		final MmtrDisplayMirror atTwelve = new MmtrDisplayMirror("", 0, false, 0, 0, -1, false, false, false, 12);
		final MmtrDisplayMirror atEleven = new MmtrDisplayMirror("", 0, false, 0, 0, -1, false, false, false, 11);
		assertNotEquals(atTwelve, atEleven, "倒计时少一秒 = 变了（每秒一帧补丁，可接受）");
	}
}
