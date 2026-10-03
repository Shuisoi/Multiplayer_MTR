package org.mtr.core.mmtr.signal;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 区间叠加层的配色（notes/291）：用户 2026-09-25 的判据是「仅需相连颜色不同即可，
 * 连续轨道可以有相同颜色」。这里钉的就是这一条 —— 连同"颜色必须稳定"（否则整条线每帧闪色）。
 */
public final class MmtrSectionOverlayTests {

	@Test
	public void neighboursNeverShareAColour() {
		// A—B—C—D 一条链，外加 B—E 一个分叉：四条边上的两端都不许同色
		final Object2ObjectOpenHashMap<String, ObjectOpenHashSet<String>> neighbours = new Object2ObjectOpenHashMap<>();
		link(neighbours, "A", "B");
		link(neighbours, "B", "C");
		link(neighbours, "C", "D");
		link(neighbours, "B", "E");

		final Object2ObjectOpenHashMap<String, Integer> colors = MmtrSectionOverlay.colorize(ids("A", "B", "C", "D", "E"), neighbours, MmtrSectionOverlay.COLOR_COUNT);

		assertNotEquals(colors.get("A"), colors.get("B"), "相连的两段必须异色（这条是全部意义所在）");
		assertNotEquals(colors.get("B"), colors.get("C"));
		assertNotEquals(colors.get("C"), colors.get("D"));
		assertNotEquals(colors.get("B"), colors.get("E"));
		// "连续轨道可以有相同颜色"：隔着一段的两段允许同色（贪心会给 A/C 同一个色号）
		assertEquals(colors.get("A"), colors.get("C"), "隔一段不必异色 —— 调色板要留给真正相邻的段");
	}

	@Test
	public void theSameTopologyAlwaysGivesTheSameColours() {
		final Object2ObjectOpenHashMap<String, ObjectOpenHashSet<String>> neighbours = new Object2ObjectOpenHashMap<>();
		link(neighbours, "A", "B");
		link(neighbours, "B", "C");

		final Object2ObjectOpenHashMap<String, Integer> first = MmtrSectionOverlay.colorize(ids("A", "B", "C"), neighbours, MmtrSectionOverlay.COLOR_COUNT);
		// 邻居表的**插入顺序**反过来再算一遍：颜色只该由"访问顺序 + 拓扑"决定，与哈希序无关
		final Object2ObjectOpenHashMap<String, ObjectOpenHashSet<String>> reversed = new Object2ObjectOpenHashMap<>();
		link(reversed, "C", "B");
		link(reversed, "B", "A");
		final Object2ObjectOpenHashMap<String, Integer> second = MmtrSectionOverlay.colorize(ids("A", "B", "C"), reversed, MmtrSectionOverlay.COLOR_COUNT);

		assertEquals(first, second, "同样的拓扑必须给同样的颜色 —— 否则世界不变而整条线闪色");
	}

	@Test
	public void aPaletteSmallerThanTheDegreeWrapsInsteadOfFailing() {
		// 三角形：三段互为邻居，而调色板只有 2 个色 —— 必然撞色，但不许越界、不许抛异常
		final Object2ObjectOpenHashMap<String, ObjectOpenHashSet<String>> neighbours = new Object2ObjectOpenHashMap<>();
		link(neighbours, "A", "B");
		link(neighbours, "B", "C");
		link(neighbours, "C", "A");

		final Object2ObjectOpenHashMap<String, Integer> colors = MmtrSectionOverlay.colorize(ids("A", "B", "C"), neighbours, 2);

		assertEquals(3, colors.size());
		for (final Integer color : colors.values()) {
			assertTrue(color >= 0 && color < 2, "色号必须落在调色板内（撞色可以，越界不行）");
		}
	}

	@Test
	public void anEdgeStoredInOneDirectionStillForbidsTheSameColour() {
		/*
		 * 只从 A 记到 B（B 那侧没有反向条目）。"相连"是双向关系，配色必须照样不同 ——
		 * 少了对称闭包时的现象很隐蔽：A 先被访问、B 那时还没有颜色，轮到 B 时它的邻居表里又没有 A
		 * ⇒ 相接的两段拿到同一个色号，而边界正是靠它显出来的（实测在 MmtrSectionServiceTests 上红过一次）。
		 */
		final Object2ObjectOpenHashMap<String, ObjectOpenHashSet<String>> oneWay = new Object2ObjectOpenHashMap<>();
		oneWay.computeIfAbsent("A", ignored -> new ObjectOpenHashSet<>()).add("B");

		final Object2ObjectOpenHashMap<String, Integer> colors = MmtrSectionOverlay.colorize(ids("A", "B"), oneWay, MmtrSectionOverlay.COLOR_COUNT);

		assertNotEquals(colors.get("A"), colors.get("B"), "单方向记录的相连也必须异色");
	}

	private static void link(Object2ObjectOpenHashMap<String, ObjectOpenHashSet<String>> neighbours, String a, String b) {
		neighbours.computeIfAbsent(a, ignored -> new ObjectOpenHashSet<>()).add(b);
		neighbours.computeIfAbsent(b, ignored -> new ObjectOpenHashSet<>()).add(a);
	}

	private static ObjectArrayList<String> ids(String... values) {
		final ObjectArrayList<String> out = new ObjectArrayList<>();
		for (final String value : values) {
			out.add(value);
		}
		return out;
	}
}
