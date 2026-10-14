package org.mtr.core.mmtr.stopmark;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.mmtr.plan.MmtrRailDistance;
import org.mtr.core.simulation.Simulator;

/**
 * 停车对标牌子的几何：牌子方块的世界坐标 → （归属股道，方向，**车头停点**）（设计 §4.2 / §4.3 / §10.2）。
 *
 * <h3>为什么必须用弧长参数化，不能用站台两端的弦</h3>
 * <p>站台是一段**可能带弯**的轨（曲线站台上真实弧长 > 弦长，{@code MmtrPlatformCommands} 里已经记过这一条）。
 * 拿两端点连线当尺子，弯得厉害的地方会把牌子的位置量偏几十厘米到几米 —— 而这条数最终会变成
 * 自动车的停车点（§5.2）。所以这里把轨按 {@code railMath.render} 打成折线，沿折线累计弧长，
 * 牌子投到折线上取最近点的弧长。</p>
 *
 * <h3>fraction 是在哪一个"弧长空间"里量的</h3>
 * <p>{@code fraction} 是**车头端面**在这根轨上的比例（0 = {@code mmtrOrderedPositions()[0]} 那一端，
 * 1 = 另一端），乘轨长就是自动车的停车锚点偏移（{@code Vehicle.mmtrReachedStopAnchor} 用的正是
 * "比例 × 本轨长"这一口径）。</p>
 *
 * <p><b>注意牌子坐标不能直接当弧长用</b>：MTR 的轨中心线相对节点方块坐标有 +0.5 的平移
 * （{@code RailMath.getPosition} 里的 {@code +0.5}），所以"牌子 z − 站台端 z"并不等于弧长。
 * 必须**投影到折线**上取弧长，量出来的才是车头真正会停的那一点。
 * 例：牌子落在节点 z=800..1020 的站台轨上、世界 z=927.775 时，弧长是 127.275；
 * 由此得到的 fraction 乘轨长正好把车头放在世界 z=929.025（= 设计 §11 表里南行 2 辆的车头停点）。</p>
 *
 * <h3>牌子放在两条股道之间</h3>
 * <p>归属先看显式绑定（block entity 的 platformId），没有才按位置就近解析
 * （{@link #nearbyPlatform}，判据与游戏侧 {@code MmtrCrowdPlatformLevels.platformAt} 同源）。
 * 两条股道贴得很近时，就近解析可能认错股道 —— 那种位置的正解是拿刷子显式绑定（§10.3）。</p>
 */
public final class MmtrStopMarkerGeometry {

	/** 投影结果：归属股道（规范 hex）、车头停点比例、车头端面世界坐标。 */
	public record Projection(String railHex, double fraction, double headX, double headY, double headZ) {
	}

	/**
	 * 水平判据（格）：与游戏侧 {@code MmtrCrowdPlatformLevels.platformAt} **同源、同一个系统属性**。
	 *
	 * <p>为什么共用铺人那个旋钮：现场把站台半径调宽（岛式站台宽、或牌子立在站台方块后几排）时，
	 * 牌子的归属判据必须跟着宽，否则会出现"人铺到了、牌子却红叉"这种两边不一致的现象 ——
	 * 而这两件事问的本来就是同一个问题："这一格算不算落在这个站台上"。</p>
	 */
	private static final double MAX_HORIZONTAL_BLOCKS = readDoubleProperty("mmtr.crowd.platformRadius", 6.0, 1.0, 512.0);
	/** 垂直判据（格）：站台带坡度时用两端点之间的插值高度比（同游戏侧）。 */
	private static final double MAX_VERTICAL_BLOCKS = 4.0;

	private MmtrStopMarkerGeometry() {
	}

	/**
	 * 牌子的世界坐标 → 车头停点。{@code towardEnd0} = 行车方向朝 rail 的 {@code mmtrOrderedPositions()[0]} 那一端。
	 *
	 * <p>投影用轨的折线弧长（不是站台两端点的弦），曲线站台也成立。</p>
	 *
	 * <p>{@code markerY} 不参与投影：牌子可能挂在杆顶或贴在墙上，垂直差几格不该改变它对应的车头停点
	 * （判据只管水平 + {@link #nearbyPlatform} 的垂直窗）。</p>
	 *
	 * <p><b>牌子坐标是谁给的</b>：{@code markerX/markerZ} 完全由调用方给（游戏端扫描给的是方块坐标）。
	 * 方块是 1 m 见方的格子，而"驾驶室侧窗对到格子的哪一点"是**半个方块**的粒度 —— 引擎不在这里替
	 * 调用方猜（猜错就等于两边各补一次偏移），整体平移留给 {@code datumM} 这个旋钮（设计 §10.1：
	 * 端面归属那 0.275 m 是实机量一次再钉死的事）。</p>
	 *
	 * @return null = 站台不存在 / 没有图轨 / 投影不出（车头停点落在股道之外，调用方据此拒收）
	 */
	public static @org.jspecify.annotations.Nullable Projection project(
		Simulator simulator, long platformId, double markerX, double markerY, double markerZ,
		boolean towardEnd0, double datumM
	) {
		final Rail rail = MmtrRailDistance.railOfPlatform(simulator, platformId);
		if (rail == null) {
			return null;
		}
		final double[] vertices = verticesOf(rail);
		if (vertices == null) {
			return null;
		}
		final double totalLength = polylineLength(vertices);
		final double arcAtMarker = closestArc(vertices, markerX, markerZ);
		final double fraction = headFractionAlong(arcAtMarker, totalLength, towardEnd0, datumM);
		if (Double.isNaN(fraction)) {
			return null;
		}
		// 头点从**同一条折线**上取，于是 headX/Y/Z 与 fraction 永远自洽（诊断时能直接对坐标）
		final double[] head = pointAtArc(vertices, arcAtMarker + (towardEnd0 ? -datumM : datumM));
		return new Projection(canonicalHex(rail.getHexId()), fraction, head[0], head[1], head[2]);
	}

	/**
	 * 从牌子位置推方向：牌子落在轨上弧长**后半段**（> 全长一半）⇒ 行车方向朝 {@code ends[1]}，即
	 * {@code towardEnd0 = false}；前半段 ⇒ {@code towardEnd0 = true}。
	 *
	 * <p>理由：车头在列车前端、牌子立在车头**后方** {@code datumM} 处，所以"牌子偏哪一头"
	 * 就是"车从哪一头来"——牌子在南半段 ⇒ 车是从北边开过来的 ⇒ 朝南（{@code ends[1]}）。
	 * 两个方向的牌子因此对称落在站台两端（设计 §11 表就是这么铺的）。</p>
	 *
	 * <p>没有图轨/轨长为 0 时返回 {@code false}（此时投影也必然失败，调用方会拒收 ——
	 * 不在这里抛异常，因为"牌子所在站台还没铺轨"是正常的中间状态）。</p>
	 */
	public static boolean deriveTowardEnd0(Simulator simulator, long platformId, double markerX, double markerZ) {
		final Rail rail = MmtrRailDistance.railOfPlatform(simulator, platformId);
		if (rail == null) {
			return false;
		}
		final double[] vertices = verticesOf(rail);
		if (vertices == null) {
			return false;
		}
		final double totalLength = polylineLength(vertices);
		if (!(totalLength > 0)) {
			return false;
		}
		// 正中点算前半段（<=）：恰好落在正中的牌子，南北两侧的车头停点都能落在股道内，取哪边都一样
		return closestArc(vertices, markerX, markerZ) <= totalLength / 2;
	}

	/**
	 * 按位置就近找站台（判据与游戏侧 {@code MmtrCrowdPlatformLevels.platformAt} 同源：
	 * 水平 ≤ 6 格、垂直 ≤ 4 格，多个命中取最近的）。
	 *
	 * <p>水平距离按站台**两端点连线的水平投影**量，与游戏侧逐位一致 —— 这里刻意不用
	 * {@link #project} 的折线：这一问是"牌子算不算落在这个站台上"（归属），
	 * 与"车头要停在哪"（投影）是两件事，判据跟着游戏侧走才不会出现两端归属不一致。</p>
	 *
	 * @return 命中的站台；找不到返回 null（不是 -1：站台 id 与"没找到"必须能区分）
	 */
	public static @org.jspecify.annotations.Nullable Platform nearbyPlatform(Simulator simulator, double x, double y, double z) {
		Platform best = null;
		double bestHorizontalSquared = MAX_HORIZONTAL_BLOCKS * MAX_HORIZONTAL_BLOCKS;
		for (final Platform platform : simulator.platforms) {
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

	/**
	 * 牌子弧长 → 车头弧长 → 比例：{@code fraction = (arcAtMarker + (towardEnd0 ? -datumM : +datumM)) / totalLength}。
	 *
	 * <p>{@code towardEnd0 = true} 表示行车方向是"沿弧长减小"的方向（朝 {@code ends[0]}），
	 * 所以车头弧长 = 牌子弧长 − {@code datumM}；反之 +{@code datumM}。</p>
	 *
	 * <p>抽成纯函数是为了**能单测**：这条式子（以及"越界就拒收"）是停车对标的全部数值内容，
	 * 不该被"必须造一个真 Simulator"挡住。抽出来也让 {@link #project} 只剩几何。
	 * 设计 §11 的自查：全长 220、datumM 1.25、南行（{@code towardEnd0 = false}）牌子弧长 127.775
	 * ⇒ {@code (127.775 + 1.25) / 220 = 0.5865}；北行（{@code true}）牌子弧长 90.225
	 * ⇒ {@code (90.225 − 1.25) / 220 = 0.4044}。</p>
	 *
	 * @return 车头停点比例；全长非正、或比例落在 [0,1] 之外时返回 {@code NaN}（调用方据此拒收：
	 *         车头停到股道外面去了，那块牌子没有可用的停车基准）
	 */
	public static double headFractionAlong(double arcAtMarker, double totalLength, boolean towardEnd0, double datumM) {
		if (!(totalLength > 0)) {
			return Double.NaN;
		}
		final double headArc = arcAtMarker + (towardEnd0 ? -datumM : datumM);
		final double fraction = headArc / totalLength;
		return fraction >= 0 && fraction <= 1 ? fraction : Double.NaN;
	}

	/** 折线全长（三维欧氏，逐段相加）。与 {@code railMath.getLength()} 的差只来自折线弦长 ≤ 弧长。 */
	static double polylineLength(double[] vertices) {
		double total = 0;
		for (int i = 3; i < vertices.length; i += 3) {
			total += segmentLength(vertices, i);
		}
		return total;
	}

	/**
	 * 折线上离 {@code (px, pz)} 最近点的弧长（沿顶点表顺序累计；水平投影，与牌子 y 无关）。
	 *
	 * <p>段内比例 t 由水平投影算出，再乘**该段的三维长度**得到弧长 —— 坡道上是近似
	 * （t 忽略了 dy，站台坡度一般千分之几，量级远小于一个方块），平面站台与曲线站台则精确。</p>
	 */
	static double closestArc(double[] vertices, double px, double pz) {
		double bestDistanceSquared = Double.MAX_VALUE;
		double bestArc = 0;
		double arc = 0;
		for (int i = 3; i < vertices.length; i += 3) {
			final double ax = vertices[i - 3];
			final double az = vertices[i - 1];
			final double dx = vertices[i] - ax;
			final double dz = vertices[i + 2] - az;
			final double segmentLength = segmentLength(vertices, i);
			final double lengthSquared = dx * dx + dz * dz;
			final double t = lengthSquared <= 0 ? 0 : clamp(((px - ax) * dx + (pz - az) * dz) / lengthSquared);
			final double qx = ax + t * dx - px;
			final double qz = az + t * dz - pz;
			final double distanceSquared = qx * qx + qz * qz;
			if (distanceSquared < bestDistanceSquared) {
				bestDistanceSquared = distanceSquared;
				bestArc = arc + t * segmentLength;
			}
			arc += segmentLength;
		}
		return bestArc;
	}

	/** 折线上弧长 {@code arc} 处的点（沿顶点表方向；越界夹到两端）。 */
	static double[] pointAtArc(double[] vertices, double arc) {
		double remaining = Math.max(0, arc);
		for (int i = 3; i < vertices.length; i += 3) {
			final double segmentLength = segmentLength(vertices, i);
			if (segmentLength > 0 && remaining <= segmentLength) {
				final double t = remaining / segmentLength;
				return new double[]{
					vertices[i - 3] + t * (vertices[i] - vertices[i - 3]),
					vertices[i - 2] + t * (vertices[i + 1] - vertices[i - 2]),
					vertices[i - 1] + t * (vertices[i + 2] - vertices[i - 1])
				};
			}
			remaining -= segmentLength;
		}
		return new double[]{vertices[vertices.length - 3], vertices[vertices.length - 2], vertices[vertices.length - 1]};
	}

	/**
	 * 轨的**折线顶点表**（按 {@code mmtrOrderedPositions()[0] → [1]} 顺序，去掉连续重复点），
	 * 扁平存放为 {@code x,y,z,x,y,z,…}。
	 *
	 * <p>顶点照 {@code SavedRailBase.getApproximateClosestDistance} 的既有写法收集：offsetRadius 传 0 时
	 * {@code (x1,y1,z1)} 与 {@code (x3,y3,z3)} 就是相邻两个**中心线**顶点（横截面四角 x1..x4 会把轨宽算进弧长，
	 * 不能用）。{@code RailMath} 内部一律以有序端点（字典序小的在前）为起点，所以顶点表的方向与
	 * {@code mmtrOrderedPositions()} 一致 —— 这是 fraction 的口径基础。</p>
	 *
	 * <p>步长 1 m：直线上逐步点就是端点，曲线上的弦长误差是二阶小量
	 * （半径 50 m 的弯每米差约 1.7e-5，整根轨累计远小于厘米级），于是"折线全长"与
	 * {@code railMath.getLength()} 的差可以忽略；真要更细，改这一个常量即可。</p>
	 *
	 * @return 顶点表；顶点不足两个（轨还没成形）返回 null
	 */
	private static double @org.jspecify.annotations.Nullable [] verticesOf(Rail rail) {
		final DoubleArrayList coordinates = new DoubleArrayList();
		rail.railMath.render((x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4, tiltAngle) -> {
			appendIfNew(coordinates, x1, y1, z1);
			appendIfNew(coordinates, x3, y3, z3);
		}, 1, 0, 0);
		return coordinates.size() < 6 ? null : coordinates.toDoubleArray();
	}

	private static void appendIfNew(DoubleArrayList coordinates, double x, double y, double z) {
		final int size = coordinates.size();
		if (size >= 3 && coordinates.getDouble(size - 3) == x && coordinates.getDouble(size - 2) == y && coordinates.getDouble(size - 1) == z) {
			return;
		}
		coordinates.add(x);
		coordinates.add(y);
		coordinates.add(z);
	}

	/** 第 {@code i} 个顶点到前一个顶点的三维长度（i 必须是 3 的倍数）。 */
	private static double segmentLength(double[] vertices, int i) {
		final double dx = vertices[i] - vertices[i - 3];
		final double dy = vertices[i + 1] - vertices[i - 2];
		final double dz = vertices[i + 2] - vertices[i - 1];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** 轨 hex 一律发**规范写法**（与站台/拓扑只读出口同一写法，前端按它取那根轨的 path 才不会找不到）。 */
	private static String canonicalHex(String hex) {
		return org.mtr.core.mmtr.signal.MmtrSectionService.canonicalHex(hex);
	}

	/** 点 (x,z) 到站台两端点连线（水平投影）的距离平方 —— 与游戏侧 {@code MmtrCrowdPlatformLevels} 同一式子。 */
	private static double horizontalDistanceSquared(double x, double z, Position a, Position b) {
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
	private static double interpolatedY(double x, double z, Position a, Position b) {
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

	private static double readDoubleProperty(String key, double fallback, double min, double max) {
		try {
			return Math.max(min, Math.min(max, Double.parseDouble(System.getProperty(key, Double.toString(fallback)).trim())));
		} catch (Exception e) {
			return fallback;
		}
	}
}
