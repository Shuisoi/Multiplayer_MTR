package org.mtr.core.data;

import it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.jspecify.annotations.Nullable;
import org.mtr.core.generated.data.PathDataSchema;
import org.mtr.core.path.SidingPathFinder;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.ConditionalList;
import org.mtr.core.tool.Utilities;
import org.mtr.core.tool.Vector;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

public class PathData extends PathDataSchema implements ConditionalList {

	@Nullable
	private Rail rail;
	private long getRailCacheCooldown = 0;
	public final boolean reversePositions;

	private static final Random RANDOM = new Random();

	/*
	 * ==== MMTR: 兜底近似轨缓存 ====================================================
	 *
	 * {@link #writePathCache} 查不到 rail 时走 {@link #defaultRail()}。**这一路原来既不缓存也不
	 * 延冷却**（`getRailCacheCooldown` 只在命中分支里加），于是每一次位置查询都重新 new 一条 Rail
	 * —— 而 RailMath 的构造要沿曲线采样建表、分配 double[]。
	 *
	 * 2026-10-08 实机 JFR（客户端 Render thread，整份录制 7509 个 jdk.ExecutionSample）：
	 *   - 1076 个（14.3%）的栈里有 `org.mtr.core.data.PathData.defaultRail`，**全部在 Render thread**；
	 *   - 其中 1075 个的**叶子帧**就在 RailMath 构造里（lambda$new$0 619 / renderSegment 235 /
	 *     StrictMath.atan2 130 / getTiltPointsAndAngles 66 / ...）；
	 *   - 1044 个同时有 `Vehicle.getPositionAndTiltAngle` ⇒ 就是"每帧每车每个转向架都重建一次曲线"。
	 *
	 * 兜底轨只由 (startPosition, startAngle, endPosition, endAngle, speedLimit) 决定，前四个在本类里
	 * 是 final（PathDataSchema），只有 speedLimit 会变（writePathCache(ObjectList, ...) 按区间重算限速）
	 * —— 所以缓存键就是 speedLimit。兜底轨从不登记进 `data.positionsToRail`、也没有任何外部持有它，
	 * 因此复用同一个实例不改变任何调用方能看到的东西（几何逐位相同，见 mmtr.pathdata.verify）。
	 *
	 * -Dmmtr.pathdata.fallbackcache=false ⇒ 回到"每次现建"（改动前行为，用于 A/B）
	 * -Dmmtr.pathdata.probe=true         ⇒ 计数，由客户端 [MMTR-FRAME] 汇总行取走并清零
	 * -Dmmtr.pathdata.verify=true        ⇒ 头几次"复用"当场再建一条新的、逐位比几何（默认关）
	 */
	@Nullable
	private Rail mmtrFallbackRail;
	private long mmtrFallbackRailSpeedLimit = Long.MIN_VALUE;

	private static final boolean MMTR_FALLBACK_CACHE = !"false".equalsIgnoreCase(System.getProperty("mmtr.pathdata.fallbackcache", "true"));
	private static final boolean MMTR_PATH_PROBE = Boolean.parseBoolean(System.getProperty("mmtr.pathdata.probe", "false"));
	private static final boolean MMTR_FALLBACK_VERIFY = Boolean.parseBoolean(System.getProperty("mmtr.pathdata.verify", "false"));
	private static final int MMTR_VERIFY_LIMIT = Integer.getInteger("mmtr.pathdata.verifylimit", 5);
	private static final AtomicLong MMTR_BUILDS = new AtomicLong();
	private static final AtomicLong MMTR_BUILD_NANOS = new AtomicLong();
	private static final AtomicLong MMTR_REUSES = new AtomicLong();
	private static final AtomicLong MMTR_LOOKUP_HITS = new AtomicLong();
	private static final AtomicLong MMTR_LOOKUP_MISSES = new AtomicLong();
	private static final AtomicLong MMTR_VERIFY_DONE = new AtomicLong();

	public PathData(Rail rail, long savedRailBaseId, long dwellTime, int stopIndex, Position startPosition, Position endPosition) {
		this(rail, savedRailBaseId, dwellTime, stopIndex, 0, 0, startPosition, rail.getStartAngle(startPosition), endPosition, rail.getStartAngle(endPosition));
	}

	public PathData(PathData oldPathData, double startDistance, double endDistance) {
		this(oldPathData.rail, oldPathData.savedRailBaseId, oldPathData.dwellTime, oldPathData.stopIndex, startDistance, endDistance, oldPathData.startPosition, oldPathData.startAngle, oldPathData.endPosition, oldPathData.endAngle);
		speedLimit = oldPathData.speedLimit;
	}

	public PathData(@Nullable Rail rail, long savedRailBaseId, long dwellTime, long stopIndex, double startDistance, double endDistance, Position startPosition, Angle startAngle, Position endPosition, Angle endAngle) {
		super(savedRailBaseId, dwellTime, stopIndex, startDistance, endDistance, startPosition, startAngle, endPosition, endAngle);
		this.rail = rail;
		reversePositions = startPosition.compareTo(endPosition) > 0;
	}

	public PathData(ReaderBase readerBase) {
		super(readerBase);
		updateData(readerBase);
		reversePositions = startPosition.compareTo(endPosition) > 0;
	}

	@Override
	public boolean matchesCondition(double value) {
		return value >= startDistance;
	}

	public final Rail getRail() {
		return rail == null ? defaultRail() : rail;
	}

	public final long getSavedRailBaseId() {
		return savedRailBaseId;
	}

	public final double getStartDistance() {
		return startDistance;
	}

	public final double getEndDistance() {
		return endDistance;
	}

	public final long getDwellTime() {
		return dwellTime;
	}

	public final int getStopIndex() {
		return (int) stopIndex;
	}

	public boolean isSameRail(PathData pathData) {
		return startPosition.equals(pathData.startPosition) && endPosition.equals(pathData.endPosition);
	}

	public boolean isOppositeRail(PathData pathData) {
		return startPosition.equals(pathData.endPosition) && endPosition.equals(pathData.startPosition);
	}

	public Position getOrderedPosition1() {
		return reversePositions ? endPosition : startPosition;
	}

	public Position getOrderedPosition2() {
		return reversePositions ? startPosition : endPosition;
	}

	public Angle getFacingStart() {
		return getRail().getStartAngle(reversePositions);
	}

	public double getSpeedLimitMetersPerMillisecond() {
		return Utilities.kilometersPerHourToMetersPerMillisecond(getSpeedLimitKilometersPerHour());
	}

	public long getSpeedLimitKilometersPerHour() {
		return Math.max(1, speedLimit);
	}

	public double getRailLength() {
		return rail == null ? endDistance - startDistance : rail.railMath.getLength();
	}

	public boolean isDescending() {
		return endPosition.getY() < startPosition.getY();
	}

	public Vehicle.PositionAndTiltAngle getPositionAndTiltAngle(Data data, double rawValue) {
		writePathCache(data);

		if (rail != null && rail.railMath.isValid()) {
			return new Vehicle.PositionAndTiltAngle(rail.railMath.getPosition(rawValue, reversePositions), rail.railMath.getTiltAngle(rawValue, reversePositions));
		} else {
			// TODO better positioning when vehicle is moving too quickly
			final double ratio = Utilities.clampSafe(rawValue / getRailLength(), 0, 1);
			return new Vehicle.PositionAndTiltAngle(new Vector(
				startPosition.getX() + ratio * (endPosition.getX() - startPosition.getX()) + 0.5,
				startPosition.getY() + ratio * (endPosition.getY() - startPosition.getY()),
				startPosition.getZ() + ratio * (endPosition.getZ() - startPosition.getZ()) + 0.5
			), 0);
		}
	}

	public String getHexId(boolean reverse) {
		return reverse ? TwoPositionsBase.getHexIdRaw(endPosition, startPosition) : TwoPositionsBase.getHexIdRaw(startPosition, endPosition);
	}

	public boolean isSignalBlocked(long vehicleId, Rail.BlockReservation blockReservation) {
		return getRail().isBlocked(vehicleId, blockReservation);
	}

	public IntAVLTreeSet getSignalColors() {
		return getRail().getSignalColors();
	}

	private void writePathCache(Data data) {
		final long currentMillis = System.currentTimeMillis();
		if (currentMillis > getRailCacheCooldown) {
			rail = Data.tryGet(data.positionsToRail, startPosition, endPosition);
			if (rail == null) {
				if (MMTR_PATH_PROBE) {
					MMTR_LOOKUP_MISSES.incrementAndGet();
				}
				rail = defaultRail();
			} else {
				if (MMTR_PATH_PROBE) {
					MMTR_LOOKUP_HITS.incrementAndGet();
				}
				getRailCacheCooldown = currentMillis + 1000 + RANDOM.nextInt(1000);
			}
		}
	}

	/**
	 * 取本对象自己的兜底近似轨（见类里 MMTR 那一段注释）：几何只由 final 的四端信息与 speedLimit
	 * 决定，所以能安全复用；speedLimit 变了就重算一条。
	 */
	private Rail defaultRail() {
		if (MMTR_FALLBACK_CACHE && mmtrFallbackRail != null && mmtrFallbackRailSpeedLimit == speedLimit) {
			if (MMTR_PATH_PROBE) {
				MMTR_REUSES.incrementAndGet();
			}
			if (MMTR_FALLBACK_VERIFY && MMTR_VERIFY_DONE.get() < MMTR_VERIFY_LIMIT) {
				MMTR_VERIFY_DONE.incrementAndGet();
				mmtrVerifyFallbackRail(mmtrFallbackRail);
			}
			return mmtrFallbackRail;
		}

		final long startNanos = MMTR_PATH_PROBE ? System.nanoTime() : 0L;
		final Rail built = buildDefaultRail();
		if (MMTR_PATH_PROBE) {
			MMTR_BUILDS.incrementAndGet();
			MMTR_BUILD_NANOS.addAndGet(System.nanoTime() - startNanos);
		}
		if (MMTR_FALLBACK_CACHE) {
			mmtrFallbackRail = built;
			mmtrFallbackRailSpeedLimit = speedLimit;
		}
		return built;
	}

	/**
	 * 自检：把"正在被复用的那条兜底轨"与"此刻现建的一条"逐位比几何。比的是**渲染真正用到的东西**
	 * —— 曲线长度、形状、沿曲线 9 个等分点上的位置与倾角。用 {@link Double#doubleToLongBits} 全等
	 * 比较而非容差：输入相同就该逐位相同，给容差只会让"几乎一样"掩盖真问题。
	 */
	private void mmtrVerifyFallbackRail(Rail cached) {
		final Rail fresh = buildDefaultRail();
		final String cachedDigest = mmtrFallbackDigest(cached);
		final String freshDigest = mmtrFallbackDigest(fresh);
		final boolean same = cachedDigest.equals(freshDigest);
		System.out.println("[MMTR-PATHCACHE] 兜底轨等价自检第 " + MMTR_VERIFY_DONE.get() + " 次（"
			+ (same ? "逐位相同" : "**不一致**") + "）：复用 " + cachedDigest + " ｜ 现建 " + freshDigest
			+ " ｜ 限速=" + speedLimit + " ｜ " + startPosition + " -> " + endPosition);
		if (!same) {
			System.out.println("[MMTR-PATHCACHE] **几何不一致 ⇒ 缓存不成立**，请用 -Dmmtr.pathdata.fallbackcache=false 回退并报告上面这两条");
		}
	}

	private static String mmtrFallbackDigest(Rail fallbackRail) {
		final RailMath railMath = fallbackRail.railMath;
		final double length = railMath.getLength();
		long hash = 0xcbf29ce484222325L;
		hash = mmtrDigestMix(hash, Double.doubleToLongBits(length));
		hash = mmtrDigestMix(hash, railMath.getShape().ordinal());
		final StringBuilder ends = new StringBuilder();
		for (int i = 0; i <= 8; i++) {
			final double value = length * i / 8.0;
			final Vector position = railMath.getPosition(value, false);
			final double tiltAngle = railMath.getTiltAngle(value, false);
			hash = mmtrDigestMix(hash, Double.doubleToLongBits(position.x()));
			hash = mmtrDigestMix(hash, Double.doubleToLongBits(position.y()));
			hash = mmtrDigestMix(hash, Double.doubleToLongBits(position.z()));
			hash = mmtrDigestMix(hash, Double.doubleToLongBits(tiltAngle));
			if (i == 0 || i == 8) {
				ends.append(String.format("(%.4f,%.4f,%.4f,tilt=%.4f)", position.x(), position.y(), position.z(), tiltAngle));
			}
		}
		return Long.toHexString(hash) + " 长=" + String.format("%.4f", length) + " 端点=" + ends;
	}

	private static long mmtrDigestMix(long hash, long value) {
		return (hash ^ value) * 0x100000001b3L;
	}

	/**
	 * MMTR: 取走并清零引擎侧的"兜底轨"计数，交给客户端 [MMTR-FRAME] 汇总行打印。
	 * 没开 {@code -Dmmtr.pathdata.probe=true} 时返回 null（探针默认关，免得 AtomicLong 影响被测对象）。
	 */
	@Nullable
	public static String mmtrPathProbeReportAndReset() {
		if (!MMTR_PATH_PROBE) {
			return null;
		}
		final long builds = MMTR_BUILDS.getAndSet(0L);
		final long buildNanos = MMTR_BUILD_NANOS.getAndSet(0L);
		final long reuses = MMTR_REUSES.getAndSet(0L);
		final long lookupHits = MMTR_LOOKUP_HITS.getAndSet(0L);
		final long lookupMisses = MMTR_LOOKUP_MISSES.getAndSet(0L);
		final long total = builds + reuses;
		return String.format(
			"兜底轨：现建 %d 次（%.1fms，均 %s）｜ 复用 %d 次（%.2f%%）｜ 查表 命中 %d / 未命中 %d ｜ 缓存=%s",
			builds, buildNanos / 1_000_000.0, builds == 0 ? "-" : String.format("%.1fus", buildNanos / 1000.0 / builds),
			reuses, total == 0 ? 0.0 : reuses * 100.0 / total, lookupHits, lookupMisses,
			MMTR_FALLBACK_CACHE ? "开" : "关"
		);
	}

	private Rail buildDefaultRail() {
		final ObjectObjectImmutablePair<Angle, Angle> angles = Rail.getAngles(startPosition, startAngle.angleDegrees, endPosition, endAngle.angleDegrees);
		return Rail.newRail(
			startPosition, angles.left(),
			endPosition, angles.right(),
			Rail.Shape.QUADRATIC, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			new ObjectArrayList<>(), speedLimit == 0 ? SidingPathFinder.AIRPLANE_SPEED : speedLimit, 0,
			false, false, true, false, false, TransportMode.TRAIN
		);
	}

	public static void writePathCache(ObjectList<PathData> path, Data data, TransportMode transportMode) {
		for (int i = 0; i < path.size(); i++) {
			final PathData pathData = path.get(i);
			pathData.writePathCache(data);
			pathData.speedLimit = getRailSpeed(path, i, transportMode.defaultSpeedKilometersPerHour);
		}
	}

	/**
	 * Gets the rail speed on a path section. If {@link Rail#canAccelerate()} for the rail is {@code false}, (such as platform or turnback), search before and after for a rail with a speed.
	 *
	 * @param path                          the current path
	 * @param currentIndex                  the index of the current rail
	 * @param defaultSpeedKilometersPerHour the default value if searching fails
	 * @return the speed in km/h
	 */
	private static long getRailSpeed(ObjectList<PathData> path, int currentIndex, long defaultSpeedKilometersPerHour) {
		for (int offset = 0; offset <= Math.max(currentIndex, path.size() - currentIndex - 1); offset++) {
			for (int sign = -1; sign <= 1; sign += 2) {
				final PathData pathData = Utilities.getElement(path, currentIndex + sign * offset);

				if (pathData == null) {
					break;
				}

				if (pathData.getRail().canAccelerate()) {
					return pathData.getRail().getSpeedLimitKilometersPerHour(pathData.reversePositions);
				}

				if (offset == 0) {
					break;
				}
			}
		}

		return defaultSpeedKilometersPerHour;
	}
}
