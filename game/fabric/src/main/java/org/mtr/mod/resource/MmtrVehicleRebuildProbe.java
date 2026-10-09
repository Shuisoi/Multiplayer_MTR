package org.mtr.mod.resource;

import org.mtr.mod.Init;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 车辆模型重建的**分段读数**（notes/400 §8）。
 *
 * <h2>为什么还要再加一条读数</h2>
 *
 * <p>{@code [MMTR-MODEL]} 量的是 {@code createModel}（读 / 解析 / 建模型）这一轮，
 * 而一次重建其实跨了好几个 tick：{@code VehicleResource} 那条链是四层
 * {@code CachedResource} 套起来的，每层"这一轮该我跑"才跑一次（{@code CachedResource.canFetchCache}
 * 每 tick 只放行一次）。于是"这一轮重建总共多久"没有意义，能对上账的是**每一段各自多久**：</p>
 *
 * <ul>
 *   <li>{@code 地面/门洞/mapDoors}：{@code writeFloorsAndDoorways} + {@code mapDoors}（渲染线程）；</li>
 *   <li>{@code 后台等待}：{@code writeToOptimizedModels} 里等后台烘焙（纯等待，不占 CPU）；</li>
 *   <li>{@code upload}：建 GL buffer（渲染线程，**必须**留在这里）。</li>
 * </ul>
 *
 * <p>后台那一半自己的耗时在 {@code [MMTR-MODELASYNC] 后台烘焙 …} 里，两行对起来就是完整的账。</p>
 *
 * <h2>纪律</h2>
 *
 * <p>只打"真的卡了"的读数（≥ {@link #MIN_MILLIS}），总数封顶（{@link #MAX_LOGS}），
 * 到达上限留一条说明 —— 与 {@code [MMTR-MODEL]} 同一套。开关 {@code -Dmmtr.rebuildprobe=false}。</p>
 */
public final class MmtrVehicleRebuildProbe {

	private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("mmtr.rebuildprobe", "true"));

	/** 一帧约 11 ms；20 ms 已经能感觉到，比这小的读数不值得刷屏。 */
	private static final long MIN_MILLIS = 20;

	private static final int MAX_LOGS = 120;

	private static final AtomicInteger LOG_COUNT = new AtomicInteger();

	private MmtrVehicleRebuildProbe() {
	}

	public static boolean isEnabled() {
		return ENABLED;
	}

	/** 一段"渲染线程上的活"用了多久。 */
	public static void report(String stage, String label, long startNanos) {
		if (!ENABLED) {
			return;
		}
		final long millis = (System.nanoTime() - startNanos) / 1_000_000L;
		if (millis < MIN_MILLIS) {
			return;
		}
		log(stage, label + " " + millis + " ms");
	}

	/** 一个 bundle 的两段：等后台 + upload。任一段够长就出一条（两段一起给，便于对账）。 */
	public static void reportUpload(String label, long waitMillis, long uploadMillis) {
		if (!ENABLED || (waitMillis < MIN_MILLIS && uploadMillis < MIN_MILLIS)) {
			return;
		}
		log("upload", String.format("%s ｜ 后台等待 %d ms ｜ upload %d ms", label, waitMillis, uploadMillis));
	}

	private static void log(String stage, String message) {
		final int count = LOG_COUNT.incrementAndGet();
		if (count > MAX_LOGS) {
			if (count == MAX_LOGS + 1) {
				Init.LOGGER.info("[MMTR-REBUILD] 分段读数已达 {} 条上限，后续不再输出", MAX_LOGS);
			}
			return;
		}
		Init.LOGGER.info("[MMTR-REBUILD] {} {}", stage, message);
	}
}
