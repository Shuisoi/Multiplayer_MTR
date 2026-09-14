package org.mtr.core.mmtr.plan;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.simulation.Simulator;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * P4：**轨图上的里程**（Dijkstra）—— 时刻表要的"这一段要走多远"从真实轨图算，不是猜的。
 *
 * <p>为什么必须是里程而不是直线距离：站间距离是**沿轨**算的（要绕咽喉、绕灯泡线），
 * 直线距离在咽喉密集的站场会差出一大截，时刻表就会整体偏快。</p>
 *
 * <p>为什么不复用 {@code MmtrRunPlanner}：那个要一辆车（它按车当前所在的轨往下走），
 * 而交路（P3）是**与车无关**的 —— 计划必须先算出来、再去分车。所以这里只做"两点之间的最短里程"，
 * 与"这趟车实际怎么走"（道岔在位、进路能不能设）解耦：计划时段是**参考时刻**，实际由联锁决定。</p>
 */
public final class MmtrRailDistance {

	private MmtrRailDistance() {
	}

	/**
	 * 从 {@code from} 到 {@code to} 的最短里程（米）。
	 *
	 * <p>算法：轨是边、节点是位置；从 {@code from} 的**两端**同时起算（车可能停在轨的任何一头），
	 * 走到 {@code to} 的任意一端为止。同一条轨不许走第二遍（轨图上没有重边）。</p>
	 *
	 * @return 里程（米）；走不到返回 {@code -1}
	 */
	public static double between(Simulator simulator, Rail from, Rail to) {
		if (from == to) {
			return 0;
		}
		final ObjectArrayList<Position> fromEnds = new ObjectArrayList<>();
		for (final Position end : from.mmtrOrderedPositions()) {
			fromEnds.add(end);
		}
		final ObjectArrayList<Position> toEnds = new ObjectArrayList<>();
		for (final Position end : to.mmtrOrderedPositions()) {
			toEnds.add(end);
		}
		final Map<Position, Double> best = new HashMap<>();
		final PriorityQueue<Position> queue = new PriorityQueue<>(Comparator.comparingDouble(node -> best.getOrDefault(node, Double.MAX_VALUE)));
		final Object2ObjectOpenHashMap<Position, Rail> visitedVia = new Object2ObjectOpenHashMap<>();
		for (final Position end : fromEnds) {
			best.put(end, 0.0);
			visitedVia.put(end, from);
			queue.add(end);
		}
		double answer = -1;
		while (!queue.isEmpty()) {
			final Position node = queue.poll();
			final double cost = best.getOrDefault(node, Double.MAX_VALUE);
			if (toEnds.contains(node)) {
				answer = cost;
				break;
			}
			final Object2ObjectOpenHashMap<Position, Rail> neighbours = simulator.positionsToRail.get(node);
			if (neighbours == null) {
				continue;
			}
			for (final Object2ObjectOpenHashMap.Entry<Position, Rail> entry : neighbours.object2ObjectEntrySet()) {
				final Rail rail = entry.getValue();
				if (rail == visitedVia.get(node)) {
					continue;   // 不原路折返：最短里程不会这么走
				}
				final double candidate = cost + rail.railMath.getLength();
				final Position next = entry.getKey();
				if (candidate < best.getOrDefault(next, Double.MAX_VALUE) - 1e-6) {
					best.put(next, candidate);
					visitedVia.put(next, rail);
					queue.add(next);
				}
			}
		}
		return answer;
	}

	/**
	 * 一串轨之间的连续里程（按给定顺序，逐段相加）。
	 *
	 * @return 总里程（米）；任何一段走不到就返回 {@code -1}
	 */
	public static double pathLength(Simulator simulator, java.util.List<Rail> rails) {
		double total = 0;
		for (int i = 1; i < rails.size(); i++) {
			final double leg = between(simulator, rails.get(i - 1), rails.get(i));
			if (leg < 0) {
				return -1;
			}
			total += leg;
		}
		return total;
	}

	/**
	 * 站台 id → 它的物理轨（{@code SavedRailBase.mmtrGraphRail()}：站台两个端点之间的那根轨）。
	 *
	 * <p>站台在轨图上就是"一段被标记的轨"，所以取轨不需要另做匹配 —— 这也是
	 * {@code MmtrRunPlanner} 解析站台目标的同一条路。</p>
	 *
	 * @return 那根轨；站台不存在或没画轨时返回 null
	 */
	public static @Nullable Rail railOfPlatform(Simulator simulator, long platformId) {
		final Rail[] found = {null};
		simulator.platforms.forEach(platform -> {
			if (found[0] == null && platform.getId() == platformId) {
				found[0] = platform.mmtrGraphRail();
			}
		});
		return found[0];
	}

	/** 股道 id → 它的物理轨（车场出库/回库用）。 */
	public static @Nullable Rail railOfSiding(Simulator simulator, long sidingId) {
		final Rail[] found = {null};
		simulator.sidings.forEach(siding -> {
			if (found[0] == null && siding.getId() == sidingId) {
				found[0] = siding.mmtrGraphRail();
			}
		});
		return found[0];
	}
}
