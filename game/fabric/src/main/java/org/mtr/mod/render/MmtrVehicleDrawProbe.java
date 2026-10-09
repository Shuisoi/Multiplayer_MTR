package org.mtr.mod.render;

import org.mtr.mod.Init;
import org.mtr.mod.resource.PartCondition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 判定性埋点：**车辆每一帧到底排队了几次 draw，以及当时车是什么状态**（notes/400）。
 *
 * <h2>要判定什么</h2>
 *
 * <p>同一个客户端、同一列车，{@code [MMTR-LIGHT]} 报的「非钢轨 draws」在 40 与 284–320 之间来回跳，
 * 而 17:34 之后再没有一条 {@code [MMTR-VEHMERGE]}（模型构建）日志。三种可能互斥：
 * 合并被静默回退 / 存在未合并的第二条几何路径 / **匹配到的 {@link PartCondition} 变多了**。
 * 第三种与服务器→客户端的数据层直接相关：{@code ON_ROUTE_*}、{@code AT_DEPOT}、{@code DOORS_*}
 * 全部由车辆状态（{@code getIsOnRoute} / {@code getReversed} / {@code getDoorValue}）决定，
 * 而这些状态是**服务端镜像过来的**；{@code totalCars} 更是直接来自服务端的车辆编组，
 * 并且它还是 {@code getCachedVehicleResource} 的缓存键之一。</p>
 *
 * <h2>怎么判定</h2>
 *
 * <p>按「门开/关 + 编组节数 + 匹配到的条件集合」分桶，每个桶累计两件事：
 * <b>出现次数</b>与<b>每次排队的 draw 数</b>（= 各 {@code OptimizedModelWrapper.partCount()} 之和）。</p>
 *
 * <p>读法：</p>
 * <ul>
 *   <li>两个桶的**签名相同**、draw 数不同 ⇒ 几何被换了（合并态翻转，回去查 {@code MmtrVehicleMeshMerger} 的回退路径）；</li>
 *   <li>两个桶的**签名不同** ⇒ 状态驱动的正常差异（例如门开时门走逐位置那条路），
 *       此时「非钢轨 draws」的波动**不是 bug**，要做的是让那部分也能合并。</li>
 * </ul>
 *
 * <h2>纪律</h2>
 *
 * <p>只累加、不分配在热路径上（条件集合用 {@code int} 位掩码，只有出报告时才拼名字）；
 * 输出有界（每窗口一行、签名最多 {@link #MAX_SIGNATURES} 个、窗口数有上限）。可用
 * {@code -Dmmtr.vdrawprobe=false} 关掉。</p>
 */
public final class MmtrVehicleDrawProbe {

	private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("mmtr.vdrawprobe", "true"));
	private static final long WINDOW_NANOS = 5_000_000_000L;
	private static final int MAX_SIGNATURES = 6;
	private static final int MAX_WINDOWS = 240;
	private static final int MAX_CARS_PER_KEY = 15;

	private static long windowStartNanos;
	private static int windowCount;
	private static int cars;
	private static int carsDoorsClosed;
	private static int carsDoorsOpen;
	private static long partsTotal;
	private static int partsMax;
	private static int conditionsTotal;
	private static int conditionsMax;

	/** 门/雨刷那条路（`ModelPropertiesPart.renderNormal`）**不经过** `VehicleResource.queue`，单独计。 */
	private static int doorQueues;
	private static long doorPartsTotal;
	private static int doorPartsMax;

	/**
	 * A 路线（notes/400 §6）里**真正被合并画出去**的那些类。它与 {@link #doorQueues} 是互补的：
	 * 合并成立时 {@code renderNormal} 会被跳过 ⇒ 只有这里涨；一眼就能看出"到底合成了没有"，
	 * 不用去猜"是不是 {@code renderNormal} 没跳过"。
	 */
	private static int doorBatchedClasses;
	private static long doorBatchedParts;
	private static final TreeMap<Integer, Bucket> SIGNATURES = new TreeMap<>();
	private static final Map<Integer, String> NAME_CACHE = new HashMap<>();

	private MmtrVehicleDrawProbe() {
	}

	public static boolean isEnabled() {
		return ENABLED;
	}

	/**
	 * 一辆车、一帧、一条 bundle 记录一次。
	 *
	 * @param conditionMask     匹配到的 {@link PartCondition} 位掩码（{@code 1 << ordinal()}）
	 * @param matchingConditions 这些条件里**真的有几何**（在 bundle 里出现过）的条数
	 * @param parts             这一辆车这一帧排队的 draw 数之和；{@code -1} 表示取不到
	 * @param totalCars         车辆编组节数（服务端数据）
	 * @param noOpenDoorways    {@code true} = 这一帧没有任何车门处在可开状态（走"关门"bundle）
	 * @param vehicleId         车辆 id（只用来在报告里点名"是哪些车"，见 {@link Bucket#describe()}）
	 */
	public static void onCarQueued(int conditionMask, int matchingConditions, int parts, int totalCars, boolean noOpenDoorways, long vehicleId) {
		if (!ENABLED) {
			return;
		}
		final long now = System.nanoTime();
		if (windowStartNanos == 0L) {
			windowStartNanos = now;
		}

		cars++;
		if (noOpenDoorways) {
			carsDoorsClosed++;
		} else {
			carsDoorsOpen++;
		}
		if (parts >= 0) {
			partsTotal += parts;
			if (parts > partsMax) {
				partsMax = parts;
			}
		}
		conditionsTotal += matchingConditions;
		if (matchingConditions > conditionsMax) {
			conditionsMax = matchingConditions;
		}

		final int carsKey = Math.min(Math.max(totalCars, 0), MAX_CARS_PER_KEY);
		final int key = (noOpenDoorways ? 1 : 0) << 20 | carsKey << 16 | conditionMask & 0xFFFF;
		final Bucket bucket = SIGNATURES.get(key);
		if (bucket != null) {
			bucket.add(parts);
			bucket.noteVehicle(vehicleId);
		} else if (SIGNATURES.size() < MAX_SIGNATURES) {
			SIGNATURES.put(key, new Bucket(key).add(parts).noteVehicle(vehicleId));
		} else {
			// 桶满了就并进 -1（"其它"），否则输出无界
			SIGNATURES.computeIfAbsent(-1, Bucket::new).add(parts).noteVehicle(vehicleId);
		}

		if (now - windowStartNanos >= WINDOW_NANOS) {
			report();
			windowStartNanos = now;
		}
	}

	/**
	 * 门/雨刷的**逐位置**排队记一次（{@code ModelPropertiesPart.renderNormal}）。
	 *
	 * <p>2026-10-08 的教训：这一条路**绕过** {@link org.mtr.mod.resource.VehicleResource#queue}，
	 * 所以只埋 `VehicleResource.queue` 会得到"门开与门关每次排队的 draw 数一模一样"的假结论 ——
	 * 而同期 {@code [MMTR-LIGHT]} 的「非钢轨 draws」会涨 6 倍。两个数对不上就是这条漏了。</p>
	 *
	 * @param parts 这一次排队要画的 draw 数（{@code OptimizedModelWrapper.partCount()}）
	 */
	public static void onDoorQueued(int parts) {
		if (!ENABLED) {
			return;
		}
		doorQueues++;
		if (parts >= 0) {
			doorPartsTotal += parts;
			if (parts > doorPartsMax) {
				doorPartsMax = parts;
			}
		}
	}

	/**
	 * A 路线（notes/400 §6）：一个**动画类**被合并画了一次（{@code MmtrDoorBatch}）。
	 *
	 * @param parts 这一次排队要画的 draw 数（合并后一个材质一次）
	 */
	public static void onDoorBatchQueued(int parts) {
		if (!ENABLED) {
			return;
		}
		doorBatchedClasses++;
		if (parts >= 0) {
			doorBatchedParts += parts;
		}
	}

	private static void report() {
		if (cars == 0) {
			return;
		}
		windowCount++;
		if (windowCount > MAX_WINDOWS) {
			// 报告够多了就停（长会话不该被探针刷屏）
			if (windowCount == MAX_WINDOWS + 1) {
				Init.LOGGER.info("[MMTR-VDRAW] 已输出 {} 个窗口，探针停止输出（-Dmmtr.vdrawprobe=false 可彻底关掉）", MAX_WINDOWS);
			}
			reset();
			return;
		}

		final StringBuilder signatureText = new StringBuilder();
		SIGNATURES.forEach((key, bucket) -> {
			if (signatureText.length() > 0) {
				signatureText.append(" ｜ ");
			}
			signatureText.append(bucket.describe());
		});

		Init.LOGGER.info(
				"[MMTR-VDRAW] {}.0s 窗口：车·帧={}（门关={} 门开={}）｜ 每次排队 draw 数 avg={} max={} ｜ 条件匹配数 avg={} max={} ｜ 门/雨刷逐位置排队={} 次 draw avg={} max={} ｜ 门·类合并={} 次 draw avg={} ｜ 签名：{}",
				WINDOW_NANOS / 1_000_000_000L,
				cars, carsDoorsClosed, carsDoorsOpen,
				cars == 0 ? "-" : String.format("%.1f", partsTotal / (double) cars),
				partsMax,
				String.format("%.2f", conditionsTotal / (double) cars),
				conditionsMax,
				doorQueues,
				doorQueues == 0 ? "-" : String.format("%.1f", doorPartsTotal / (double) doorQueues),
				doorPartsMax,
				doorBatchedClasses,
				doorBatchedClasses == 0 ? "-" : String.format("%.1f", doorBatchedParts / (double) doorBatchedClasses),
				signatureText
		);
		reset();
	}

	private static void reset() {
		cars = 0;
		carsDoorsClosed = 0;
		carsDoorsOpen = 0;
		partsTotal = 0;
		partsMax = 0;
		conditionsTotal = 0;
		conditionsMax = 0;
		doorQueues = 0;
		doorPartsTotal = 0;
		doorPartsMax = 0;
		doorBatchedClasses = 0;
		doorBatchedParts = 0;
		SIGNATURES.clear();
	}

	/** 把位掩码翻成人看的条件名（只在出报告时调用）。 */
	private static String describeMask(int conditionMask) {
		final String cached = NAME_CACHE.get(conditionMask);
		if (cached != null) {
			return cached;
		}
		final List<String> names = new ArrayList<>();
		for (final PartCondition partCondition : PartCondition.values()) {
			if ((conditionMask & 1 << partCondition.ordinal()) != 0) {
				names.add(partCondition.name());
			}
		}
		final String text = names.isEmpty() ? "（无）" : String.join("+", names);
		NAME_CACHE.put(conditionMask, text);
		return text;
	}

	private static final class Bucket {

		private final int key;
		private int count;
		private long parts;
		private int partsMax;
		/**
		 * 这个桶里**第一辆**被记进来的车（2026-10-10 加）。
		 *
		 * <p>为什么要有它：本局实测出现了一个 `车=1 … draw avg=0.0 max=0` 的桶 —— 一台**单节车**
		 * 被排队画了 5980 个"车·帧"、一次都没画出去。而引擎那边十列车全是 10 节（00101–00110），
		 * 所以这台单节车**不在引擎的车辆表里**（幽灵车 / 只同步到一节）。光看"车=1、draw=0"点不出是谁，
		 * 把车辆 id 带出来就能直接和 `mmtr-trains` 对表。</p>
		 */
		private long sampleVehicleId;
		private boolean hasSampleVehicle;

		private Bucket(int key) {
			this.key = key;
		}

		private Bucket add(int value) {
			count++;
			if (value >= 0) {
				parts += value;
				if (value > partsMax) {
					partsMax = value;
				}
			}
			return this;
		}

		private Bucket noteVehicle(long vehicleId) {
			if (!hasSampleVehicle) {
				hasSampleVehicle = true;
				sampleVehicleId = vehicleId;
			}
			return this;
		}

		private String describe() {
			if (key < 0) {
				return String.format("其它 n=%d draw avg=%.1f", count, parts / (double) count);
			}
			final boolean doorsClosed = (key >> 20 & 1) != 0;
			final int totalCars = key >> 16 & 0xF;
			final int conditionMask = key & 0xFFFF;
			return String.format("%s 车=%d [%s] n=%d draw avg=%.1f max=%d%s",
					doorsClosed ? "门关" : "门开", totalCars, describeMask(conditionMask), count, parts / (double) count, partsMax,
					hasSampleVehicle ? " 样本车=" + sampleVehicleId : "");
		}
	}
}
