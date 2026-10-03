package org.mtr.mod.client;

/**
 * 解耦判据的**缓存窗口**：纯函数、零 Minecraft 依赖 —— 所以它可以被离线用例直接跑（见 notes/353 §2）。
 *
 * <h2>为什么这三行要单独拿出来</h2>
 *
 * <p>2026-10-01 实机：坐在驾驶室里按 A 会去推油门、按 L 依旧弹"进度"，也就是<b>解耦一次都没生效</b>。
 * 查下来判据没写错，错的是缓存这一行：{@code cachedAtMillis} 的初值是 {@link #NEVER}，而"缓存还新鲜吗"
 * 当时写成 {@code now - cachedAtMillis < WINDOW_MILLIS}。{@code now - Long.MIN_VALUE} 在 long 上
 * <b>溢出成负数</b>（数学上等于 {@code now - 2^63}），于是这个条件<b>永远成立</b>：函数第一次调用就
 * 返回 {@code cachedDecoupled} 的初值 {@code false}，而 {@code cachedAtMillis} 再也不会被写 ——
 * 判据永远不跑，日志里连一行 {@code [MMTR-KEY]} 都没有。</p>
 *
 * <p>教训是<b>哨兵值不能参与算术</b>。现在哨兵只出现在等式里（{@link #fresh} 的第一个条件），
 * 减法只在两个操作数都是真实时间戳时做。这条规则由
 * {@code sandbox/light-switch-probe/check.ps1} 的离线用例钉住：旧写法必须判失败。</p>
 */
public final class MmtrDecoupleCache {

	/** 还没建立缓存时的哨兵。**不要拿它做减法**（原因见类注释）。 */
	public static final long NEVER = Long.MIN_VALUE;

	/**
	 * 判据缓存窗口（ms）。
	 *
	 * <p>为什么要缓存：{@code isPressed()} 每一 tick 会被原版问几十次（它遍历所有绑定），而"我在不在
	 * 司机位上"要遍历车辆镜像。100 ms 对键盘手感毫无影响，却把这条路变成一次字段读。</p>
	 */
	public static final long WINDOW_MILLIS = 100L;

	private MmtrDecoupleCache() {
	}

	/**
	 * 缓存还新鲜吗（= 可以沿用上一次算出来的布尔，不必重算）。
	 *
	 * @param nowMillis      现在
	 * @param cachedAtMillis 上一次算出来的时刻；还没算过时是 {@link #NEVER}
	 */
	public static boolean fresh(long nowMillis, long cachedAtMillis) {
		return cachedAtMillis != NEVER && nowMillis - cachedAtMillis < WINDOW_MILLIS;
	}
}
