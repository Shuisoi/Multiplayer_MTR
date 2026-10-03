package org.mtr.core.mmtr.signal;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.mmtr.probe.MmtrProbe;
import org.mtr.core.tool.Vector;

/**
 * 轨道几何的中立工具：**弧长空间**与"投影"这两件事，两层区间模型都依赖。
 *
 * <p>由来（notes/166 R1）：这些函数原来住在 v1 的 {@code MmtrBlockService} 里，而 v2 的
 * {@code MmtrSectionService} 一直在**借用**它们（13 处）。把 v1 整层删掉时，若不先把它们
 * 搬出来，v2 会跟着编译不过 —— 这正是"两套模型缠在一起"的直接证据，所以独立成一个类。</p>
 *
 * <p>坐标口径（与占用树同一份）：<strong>ordered-position-1 弧长空间</strong> ——
 * arc 0 = 按 {@code Position.compareTo} 排在前的那个端点，arc = 轨长在另一端。
 * {@code RailMath} 本来就是按这个端点建的，所以投影结果不需要任何换算。</p>
 */
public final class MmtrSectionGeometry {

	/** 一盏灯离轨多远之内仍算"它就在这条轨旁边"（米）。超出则这盏灯不绑任何轨。 */
	public static final double SIGNAL_BIND_TOLERANCE_M = 8.0;

	/** 投影与量方向用的采样步长（米）。MTR 的轨是双圆弧，采样式投影足够（灯只需要落进正确的那一段）。 */
	public static final double SAMPLE_STEP_M = 0.25;

	private MmtrSectionGeometry() {
	}

	/** A sampled closest point on a rail curve (arc in ordered-position-1 space). */
	private static final class Projection {
		final double arcFromOrdered1M;
		final double distanceSq;

		private Projection(double arcFromOrdered1M, double distanceSq) {
			this.arcFromOrdered1M = arcFromOrdered1M;
			this.distanceSq = distanceSq;
		}
	}

	/** 一个查询点的**原始位模式**（三个 double 逐位存下来）：精确 cache 键 —— 不拿浮点 hash 去"近似命中"。 */
	private record ProjectionKey(long worldX, long worldY, long worldZ) {
		static ProjectionKey of(double worldX, double worldY, double worldZ) {
			return new ProjectionKey(Double.doubleToLongBits(worldX), Double.doubleToLongBits(worldY), Double.doubleToLongBits(worldZ));
		}
	}

	/**
	 * **投影记忆化**（2026-09-27，用户口径："不应该加速吗？要不仿真做什么？"）。
	 *
	 * <h3>为什么这是"逐位相同"的加速</h3>
	 * <p>{@link #project} 是 <b>(轨, 世界点) 的纯函数</b>：它只读 {@code rail.railMath} 与三个入参，
	 * 不碰任何可变状态。而 {@code Rail.railMath} 是 <b>final、只在构造里赋值</b> —— 也就是说
	 * **同一个 Rail 对象的几何永远不会变**，键里放轨对象本身就够了。命中时返回的是**同一个**已算好的
	 * 结果（同一个 {@code double} 的位），所以调用方看到的值与重算逐位相同。</p>
	 *
	 * <h3>为什么它值这么多</h3>
	 * <p>现场读数（notes/328 §12 的 JFR）：<b>88% 的采样在几何</b>，其中 67% 的调用栈来自
	 * {@code MmtrSectionService.rebuild}；而重建里最贵的一段是 {@code nearestLampOnSpan}：
	 * 它对**每一根轨 × 每一盏灯**调一次投影，每次投影都拿 0.25 m 步长把整根轨扫一遍
	 * （一盏灯投影到一根 150 m 的轨 = 600 次 {@code RailMath.getPosition} + 600 个 {@code Vector} 分配）。
	 * 而 (轨, 灯) 这对输入在**每次重建里原样重复** —— 于是第一次算完，后面全是查表。</p>
	 *
	 * <p>缓存按**区间服务实例**持有（{@code MmtrSectionService} 是每仿真器一个），所以它跟着世界一起生灭，
	 * 不会把旧世界的轨对象留在静态表里。</p>
	 */
	public static final class ProjectionCache {

		/** 轨 → （查询点 → 投影结果）。 */
		private final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<Rail, it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<ProjectionKey, Projection>> byRail
			= new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>();
		/** 只是防病态世界把内存吃光；清空**只影响速度**，不影响结果（所以与"逐位相同"不冲突）。 */
		private static final int MAX_ENTRIES = 200_000;
		private int entries;

		private @Nullable Projection get(Rail rail, double worldX, double worldY, double worldZ) {
			final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<ProjectionKey, Projection> perRail = byRail.get(rail);
			final Projection cached = perRail == null ? null : perRail.get(ProjectionKey.of(worldX, worldY, worldZ));
			/*
			 * 探针（notes/337）：命中率是这一层**唯一**的结论性指标。
			 *
			 * <p>它必须分开数：{@code projection.hit} 与 {@code projection.solve} 的比值就是命中率，
			 * 而 {@code projection.evict} 一旦非零，就说明 {@link #MAX_ENTRIES} 被撑爆过 ——
			 * 那正是 notes/335 §4 现场的形态：缓存是全局共享的、清空是整体 clear()，
			 * 于是"清一次"之后每一盏灯 × 每一根轨都回到冷算。看命中率崩塌比看总耗时更早发现它。</p>
			 */
			if (cached != null) {
				MmtrProbe.hit("projection.hit");
			}
			return cached;
		}

		private void put(Rail rail, double worldX, double worldY, double worldZ, Projection projection) {
			if (entries >= MAX_ENTRIES) {
				byRail.clear();
				entries = 0;
				MmtrProbe.hit("projection.evict");
			}
			byRail.computeIfAbsent(rail, ignored -> new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>())
				.put(ProjectionKey.of(worldX, worldY, worldZ), projection);
			entries++;
			MmtrProbe.peak("projection.entries", entries);
		}
	}

	/**
	 * Closest point on the rail curve to a world position (always returns the best sample, however far
	 * - callers decide whether the distance is acceptable).
	 */
	private static @Nullable Projection project(@Nullable ProjectionCache cache, Rail rail, double worldX, double worldY, double worldZ) {
		final double length = rail.railMath.getLength();
		if (length <= 0) {
			return null;
		}
		if (cache != null) {
			final Projection cached = cache.get(rail, worldX, worldY, worldZ);
			if (cached != null) {
				return cached;
			}
		}
		/*
		 * 冷算一次的**代价**要能被点名（notes/337）—— 看门狗的栈就停在这里（notes/335 §4）：
		 *
		 * <p>{@code [MMTR-PROBE] projection.solve=<累计毫秒>/<冷算次数> max=<单次最坏毫秒>} 一行就能读出
		 * "一次冷算有多贵"（一盏灯投到一根 150 m 的轨 = 600 次 {@code RailMath.getPosition} + 600 个
		 * {@code Vector}）。{@code max} 与平均的比值接着回答"是不是个别长轨把整轮拖死"。</p>
		 */
		final long probeT = MmtrProbe.begin();
		try {
			double bestT = 0;
			double bestDistanceSq = Double.MAX_VALUE;
			for (double t = 0; t <= length + 1e-6; t += SAMPLE_STEP_M) {
				final Vector position = rail.railMath.getPosition(Math.min(t, length), false);
				final double dx = position.x() - worldX;
				final double dy = position.y() - worldY;
				final double dz = position.z() - worldZ;
				final double distanceSq = dx * dx + dy * dy + dz * dz;
				if (distanceSq < bestDistanceSq) {
					bestDistanceSq = distanceSq;
					bestT = Math.min(t, length);
				}
			}
			final Projection result = new Projection(bestT, bestDistanceSq);
			if (cache != null) {
				cache.put(rail, worldX, worldY, worldZ, result);
			}
			return result;
		} finally {
			MmtrProbe.end("projection.solve", probeT);
		}
	}

	/**
	 * Project a world position onto the rail curve and return the arc in ordered-position-1 space, or
	 * null when the closest point is farther than {@link #SIGNAL_BIND_TOLERANCE_M}.
	 */
	public static @Nullable Double projectArc(Rail rail, double worldX, double worldY, double worldZ) {
		return projectArc(null, rail, worldX, worldY, worldZ);
	}

	/** {@link #projectArc(Rail, double, double, double)} 的记忆化版：{@code cache} 为 {@code null} 时等于不缓存。 */
	public static @Nullable Double projectArc(@Nullable ProjectionCache cache, Rail rail, double worldX, double worldY, double worldZ) {
		final Projection projection = project(cache, rail, worldX, worldY, worldZ);
		if (projection == null || projection.distanceSq > SIGNAL_BIND_TOLERANCE_M * SIGNAL_BIND_TOLERANCE_M) {
			return null;
		}
		return projection.arcFromOrdered1M;
	}

	/**
	 * The arc of an endpoint node on {@code rail} in ordered-position-1 space (0 or the rail length),
	 * or {@code NaN} when {@code node} is not one of the rail's endpoints. Used to find where a movement
	 * enters a rail when it crosses onto it from the neighbouring one.
	 */
	public static double arcOfNode(Rail rail, @Nullable Position node) {
		return node == null ? Double.NaN : rail.mmtrArcOfEndNode(node);
	}

	/** The rails' two end nodes, or an empty list when the rail has no usable endpoints. */
	public static ObjectArrayList<Position> endNodes(Rail rail) {
		final ObjectArrayList<Position> out = new ObjectArrayList<>();
		final Position[] ordered = rail.mmtrOrderedPositions();
		if (ordered != null) {
			for (final Position position : ordered) {
				if (position != null) {
					out.add(position);
				}
			}
		}
		return out;
	}
}
