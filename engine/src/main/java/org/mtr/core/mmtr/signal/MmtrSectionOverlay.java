package org.mtr.core.mmtr.signal;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

/**
 * 区间叠加层的**配色**：把"相连的两段不许同色"做成一次贪心上色（用户 2026-09-25 的判据：
 * 「用不同颜色来代表不同区间，**仅需相连颜色不同即可**，连续轨道可以有相同颜色」）。
 *
 * <h3>为什么是贪心，而不是"每段一个固定颜色"</h3>
 * <p>调色板只有十来个色相，而世界里有上百段区间 —— 想"每段唯一"是做不到的。用户要的其实是
 * <b>边界看得见</b>：只要相邻两段不同色，人眼就能在颜色跳变处读出"这里换区间了"。贪心正好是这个
 * 需求的最小实现：访问每一段时取"邻居没用过的最小色号"。</p>
 *
 * <h3>为什么必须由引擎算、而且必须稳定</h3>
 * <p>颜色是**拓扑的函数**：调用方按 id 排序后传进来，邻居表也只由"链上的下一段 + 同轨重叠的段"
 * 决定。于是世界不变 ⇒ 颜色逐段不变（否则每帧重算会让整条线的颜色闪）。这与 S4 的老规矩一致：
 * 引擎给结论，客户端只显示（客户端自己算一遍必然与引擎分叉，而"哪两段算相连"正是结论的一部分）。</p>
 */
public final class MmtrSectionOverlay {

	/**
	 * 调色板大小（客户端画带用的色相数，两边必须一致）。
	 *
	 * <p>取 12 是为了**咽喉**：现场实测一根轨上最多叠过 5 个区间，连同链上的后继，一个区间的邻居
	 * 可能接近 10 个。贪心在小度数图上用不了几个色，12 是"够用且不撞"的折中；真的用尽时
	 * {@link #colorize} 会回绕（相邻可能同色）—— 那时宁可撞色，也不能不画。</p>
	 */
	public static final int COLOR_COUNT = 12;

	private MmtrSectionOverlay() {
	}

	/**
	 * 贪心上色：{@code idsInOrder} 必须**按稳定顺序**排好（调用方按 id 排序），
	 * {@code neighbors} 是"相连"关系。
	 *
	 * <p>入参只填一个方向也够：这里先做**对称闭包**（"相连"本来就是双向的）。这一步不是洁癖 ——
	 * 少了它就会出现"访问 A 时 B 还没有颜色、访问 B 时它的邻居表里又没有 A"，于是相接的两段拿到同一个
	 * 色号（实测：用例 {@code twoSectionsThatMeetAtALampGetDifferentColours} 就是这样红的）。</p>
	 *
	 * @return 区间 id → 色号（0..colorCount-1）
	 */
	public static Object2ObjectOpenHashMap<String, Integer> colorize(ObjectArrayList<String> idsInOrder, Object2ObjectOpenHashMap<String, ObjectOpenHashSet<String>> neighbors, int colorCount) {
		final Object2ObjectOpenHashMap<String, ObjectOpenHashSet<String>> symmetric = new Object2ObjectOpenHashMap<>();
		neighbors.forEach((id, adjacent) -> {
			final ObjectOpenHashSet<String> own = symmetric.computeIfAbsent(id, ignored -> new ObjectOpenHashSet<>());
			for (final String other : adjacent) {
				own.add(other);
				symmetric.computeIfAbsent(other, ignored -> new ObjectOpenHashSet<>()).add(id);
			}
		});

		final Object2ObjectOpenHashMap<String, Integer> colors = new Object2ObjectOpenHashMap<>();
		for (final String id : idsInOrder) {
			final ObjectOpenHashSet<Integer> used = new ObjectOpenHashSet<>();
			final ObjectOpenHashSet<String> adjacent = symmetric.get(id);
			if (adjacent != null) {
				for (final String other : adjacent) {
					final Integer color = colors.get(other);
					if (color != null) {
						used.add(color);
					}
				}
			}
			int chosen = 0;
			while (chosen < colorCount && used.contains(chosen)) {
				chosen++;
			}
			// 调色板用尽（chosen == colorCount）时回绕到 0：撞色好过不画，调用方/用例盯着这一条。
			colors.put(id, chosen % colorCount);
		}
		return colors;
	}
}
