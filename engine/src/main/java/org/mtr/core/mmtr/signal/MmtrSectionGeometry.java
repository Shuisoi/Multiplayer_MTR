package org.mtr.core.mmtr.signal;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
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

	/**
	 * Closest point on the rail curve to a world position (always returns the best sample, however far
	 * - callers decide whether the distance is acceptable).
	 */
	private static @Nullable Projection project(Rail rail, double worldX, double worldY, double worldZ) {
		final double length = rail.railMath.getLength();
		if (length <= 0) {
			return null;
		}
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
		return new Projection(bestT, bestDistanceSq);
	}

	/**
	 * Project a world position onto the rail curve and return the arc in ordered-position-1 space, or
	 * null when the closest point is farther than {@link #SIGNAL_BIND_TOLERANCE_M}.
	 */
	public static @Nullable Double projectArc(Rail rail, double worldX, double worldY, double worldZ) {
		final Projection projection = project(rail, worldX, worldY, worldZ);
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
