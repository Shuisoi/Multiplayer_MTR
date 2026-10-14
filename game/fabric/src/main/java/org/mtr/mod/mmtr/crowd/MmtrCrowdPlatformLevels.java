package org.mtr.mod.mmtr.crowd;

import net.minecraft.util.math.BlockPos;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.mod.client.MinecraftClientData;

/**
 * 方案 A（客量驱动）的**唯一**跨端数值入口，见 {@code notes/416-客流方案A-客量驱动-合同.md}。
 *
 * <p>服务端只把站台客量（{@code Platform.crowdLevel}，0–100%）随静态数据流下发；客户端渲染端
 * 只从这里问"这个位置所在站台有多少人"，不再读任何逐格 {@code occupied} 状态。</p>
 *
 * <p><b>为什么不是 {@code Platform.containsPos}</b>：{@code SavedRailBase.containsPos} 只比较站台的
 * **两个端点**（{@code position1.equals || position2.equals}），站台内部任何一格都返回 {@code false}。
 * 而 MTR 的站台就是"轨旁从端点到端点的一条带"，所以这里用**到两端点连线的最小距离**判定归属：
 * 水平 ≤ {@link #MAX_HORIZONTAL_BLOCKS}、垂直 ≤ {@link #MAX_VERTICAL_BLOCKS} 才算落在站台上，
 * 同一个位置命中多个站台时取最近的那个。</p>
 *
 * <p>位置不在任何站台内（或客户端镜像还没同步到数据）⇒ {@link #NO_PLATFORM}。宁可"这一格没人"，
 * 也不猜一个默认客量。</p>
 */
public final class MmtrCrowdPlatformLevels {

	/** 不在任何站台内（或镜像还没到）。 */
	public static final long NO_PLATFORM = -1L;

	/**
	 * 离站台中轴的横向容差（格）：站台带本身的宽度 + 标记格一般落在轨旁几格内。
	 *
	 * <p>为什么做成可调：这个数决定"一格算不算落在站台上"，而它同时受两件事影响 ——
	 * 站台有多宽（岛式站台比侧式宽）、以及标记铺到离站台方块多远
	 * （服务端扫标记的窗口是 {@code MmtrCrowdModule.MARKER_WINDOW = 12} 格）。
	 * 现场要是看到"靠里的标记格一个人都不画"，先用 {@code -Dmmtr.crowd.platformRadius=12}
	 * 放宽一格一格试，不必改代码重编：这条读数只在**建块/换块**时用，放宽的代价是
	 * "离站台几格远的地方也会借到这条站台的客量"（同一个位置命中多条站台时仍然取最近的）。</p>
	 */
	private static final double MAX_HORIZONTAL_BLOCKS = readDoubleProperty("mmtr.crowd.platformRadius", 6.0, 1.0, 512.0);
	/** 纵向容差（格）：站台有坡度时用同一条线段的插值高度比较。 */
	private static final double MAX_VERTICAL_BLOCKS = 4.0;

	private static double readDoubleProperty(String key, double fallback, double min, double max) {
		try {
			return Math.max(min, Math.min(max, Double.parseDouble(System.getProperty(key, Double.toString(fallback)).trim())));
		} catch (Exception e) {
			return fallback;
		}
	}

	private MmtrCrowdPlatformLevels() {
	}

	/** 该方块位置所在站台的客量（0–100%），或 {@link #NO_PLATFORM}。 */
	public static long levelAt(int x, int y, int z) {
		final Platform platform = platformAt(x, y, z);
		return platform == null ? NO_PLATFORM : platform.getCrowdLevel();
	}

	/** 便捷重载：直接给 {@link BlockPos}。 */
	public static long levelAt(BlockPos pos) {
		return levelAt(pos.getX(), pos.getY(), pos.getZ());
	}

	/** 该方块位置所在站台的镜像对象（最近的那个），或 {@code null}。 */
	public static Platform platformAt(int x, int y, int z) {
		final MinecraftClientData data = MinecraftClientData.getInstance();
		if (data == null) {
			return null;
		}

		Platform best = null;
		double bestHorizontalSquared = MAX_HORIZONTAL_BLOCKS * MAX_HORIZONTAL_BLOCKS;

		for (final Platform platform : data.platforms) {
			final Position[] ends = platform.mmtrOrderedPositions();
			final double horizontalSquared = horizontalDistanceSquared(x, z, ends[0], ends[1]);
			if (horizontalSquared > bestHorizontalSquared) {
				continue;
			}
			if (Math.abs(y - interpolatedY(x, z, ends[0], ends[1])) > MAX_VERTICAL_BLOCKS) {
				continue;
			}
			best = platform;
			bestHorizontalSquared = horizontalSquared;
		}

		return best;
	}

	/** 点 (x,z) 到站台两端点连线（水平投影）的距离平方。 */
	private static double horizontalDistanceSquared(int x, int z, Position a, Position b) {
		final double ax = a.getX();
		final double az = a.getZ();
		final double dx = b.getX() - ax;
		final double dz = b.getZ() - az;
		final double lengthSquared = dx * dx + dz * dz;
		final double t = lengthSquared <= 0 ? 0 : clamp(((x - ax) * dx + (z - az) * dz) / lengthSquared);
		final double qx = ax + t * dx - x;
		final double qz = az + t * dz - z;
		return qx * qx + qz * qz;
	}

	/** 上面那个最近点在站台线段上的高度（站台有坡度时跟着插值）。 */
	private static double interpolatedY(int x, int z, Position a, Position b) {
		final double ax = a.getX();
		final double az = a.getZ();
		final double dx = b.getX() - ax;
		final double dz = b.getZ() - az;
		final double lengthSquared = dx * dx + dz * dz;
		final double t = lengthSquared <= 0 ? 0 : clamp(((x - ax) * dx + (z - az) * dz) / lengthSquared);
		return a.getY() + t * (b.getY() - a.getY());
	}

	private static double clamp(double value) {
		return value < 0 ? 0 : Math.min(value, 1);
	}

	/** 调试用：把"镜像里有多少个站台、这一格读到多少"写进一行，便于现场对账。 */
	public static String describe(int x, int y, int z) {
		final MinecraftClientData data = MinecraftClientData.getInstance();
		final Platform platform = platformAt(x, y, z);
		return "站台镜像=" + (data == null ? "null" : Integer.toString(data.platforms.size()))
				+ " 归属=" + (platform == null ? "无" : platform.getHexId())
				+ " 客量=" + (platform == null ? "无" : platform.getCrowdLevel() + "%");
	}
}
