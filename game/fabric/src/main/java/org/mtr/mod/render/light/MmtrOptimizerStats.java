package org.mtr.mod.render.light;

import org.mtr.mapping.holder.Identifier;
import org.mtr.mod.Init;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 优化渲染器的 draw / 批次计数 —— 回答"这些 draw 是谁发的"。
 *
 * <p>计数点（都在 MTR 的映射库里，用 mixin 挂上）：</p>
 * <ul>
 *   <li>{@code BatchManager$RenderCall#draw()} —— 每个 RenderCall 就是一次 {@code glDrawElements}，
 *       所以这个数就是**这一帧优化器真正发出的 draw 数**（见 {@code BatchManagerRenderCallMixin}）。</li>
 *   <li>{@code ShaderManager#setupShaderBatchState()} —— 每个批次（材质组）调用一次，见 {@code ShaderManagerMixin}。</li>
 * </ul>
 *
 * <h2>为什么要按来源分桶（2026-10-05 加）</h2>
 *
 * <p>实测帧率与 {@code draws/frame} 强负相关（308 个窗口 r = −0.781，见运行手册 §8.2 D10），
 * 于是"砍 draw"成了唯一有效的方向 —— 但当时**无法回答"这些 draw 是谁发的"**：只有一个总数，
 * 只能靠"有车的窗口 vs 只有钢轨的窗口"去凑对照，而场景一直在变，凑不出结论。
 * 这一版把总数按**批次贴图**拆开，让归属变成一行读数。</p>
 *
 * <p><b>为什么在批次处设桶、在 draw 处归集：</b>MTR 的批次循环是"每桶
 * {@code setupShaderBatchState} 一次 → 把这个桶的 RenderCall 全画完"，而
 * {@code RenderCall.draw()} 不重绑材质 ⇒ <b>一个批次内的每个 draw 必然属于这一批</b>。
 * 所以在批次 HEAD 记住"当前是哪个桶"，逐 draw 时累加到它头上，归属是精确的。</p>
 *
 * <p><b>判据唯一：</b>"算不算钢轨"复用 {@link MmtrLightField#isRailTexture}（光场分 LUT 用的是同一份判据）。
 * 贴图与模型同目录（{@code mtr:models/rail/rail.png}）⇒ 以 {@code /rail/} 判定。
 * 若某个车辆贴图被误判成钢轨，日志里"非钢轨 top"会缺一项、钢轨桶会异常膨胀，一眼能看见。</p>
 *
 * <p>非钢轨那一侧按 {@code namespace:path} 分开并只打**累计最重的前几个**（有界输出，见运行手册的探针纪律）。</p>
 */
public final class MmtrOptimizerStats {

	/** 一个桶在一个 5 秒窗口里的累计。 */
	private static final class Bucket {

		private int draws;
		private int batches;
	}

	private static final String NO_TEXTURE_KEY = "(无贴图)";
	/** 非钢轨那一侧最多打几项（其余折叠成一行合计）。 */
	private static final int MAX_NON_RAIL_REPORTED = 4;

	private static int drawCalls;
	private static int batches;

	/** 钢轨桶。与 {@link MmtrLightField#isRailTexture} 同一判据。 */
	private static final Bucket RAIL_BUCKET = new Bucket();

	/** 非钢轨桶：键是 {@code namespace:path}。窗口结束后清空（只做窗口汇总，不跨窗口累积）。 */
	private static final Map<String, Bucket> NON_RAIL_BUCKETS = new HashMap<>();

	/** 当前这一批属于哪个桶 —— 批次 HEAD 处设置，该批次的每个 draw 都记到它头上。 */
	private static Bucket currentBucket = RAIL_BUCKET;

	private MmtrOptimizerStats() {
	}

	/**
	 * 一个批次（材质组）开始。
	 *
	 * @param texture 该批次的贴图（{@code MaterialProperties.getTexture()}），用于区分非钢轨的来源
	 * @param rail    这一批算不算钢轨 —— 由调用点在**同一个地方**用 {@link MmtrLightField#isRailTexture} 判好传进来，
	 *                避免"什么算钢轨"出现第二份实现
	 */
	public static void onBatch(Identifier texture, boolean rail) {
		batches++;
		if (rail) {
			RAIL_BUCKET.batches++;
			currentBucket = RAIL_BUCKET;
		} else {
			final String key = texture == null ? NO_TEXTURE_KEY : texture.getNamespace() + ":" + texture.getPath();
			final Bucket bucket = NON_RAIL_BUCKETS.computeIfAbsent(key, ignored -> new Bucket());
			bucket.batches++;
			currentBucket = bucket;
		}
	}

	/** 一次真正的 draw（{@code BatchManager$RenderCall#draw()}）。记到当前批次所属的桶。 */
	public static void onDrawCall() {
		drawCalls++;
		currentBucket.draws++;
	}

	/** 每 5 秒把累计值摊成"每帧"打一行，然后清零。 */
	public static void logAndReset(int frames) {
		final float divisor = Math.max(1, frames);
		int nonRailDraws = 0;
		int nonRailBatches = 0;
		for (final Bucket bucket : NON_RAIL_BUCKETS.values()) {
			nonRailDraws += bucket.draws;
			nonRailBatches += bucket.batches;
		}

		Init.LOGGER.info("[MMTR-LIGHT] optimizer draws/frame={} batches/frame={} (窗口 {} 帧) ｜ 拆分：{} ｜ 非钢轨 top{}：{}",
				String.format("%.1f", drawCalls / divisor),
				String.format("%.2f", batches / divisor),
				frames,
				String.format("钢轨 draws=%.1f(%.2f批) 非钢轨 draws=%.1f(%.2f批)",
						RAIL_BUCKET.draws / divisor, RAIL_BUCKET.batches / divisor,
						nonRailDraws / divisor, nonRailBatches / divisor),
				MAX_NON_RAIL_REPORTED,
				describeTopNonRail(divisor));

		drawCalls = 0;
		batches = 0;
		RAIL_BUCKET.draws = 0;
		RAIL_BUCKET.batches = 0;
		NON_RAIL_BUCKETS.clear();
		currentBucket = RAIL_BUCKET;
	}

	/**
	 * 非钢轨那一侧按 draw 累计排序，只打前 {@link #MAX_NON_RAIL_REPORTED} 项，其余折叠成一行合计。
	 * 形状：{@code mtr:models/vehicle/x.png=180.2(3.00批) | ...}
	 */
	private static String describeTopNonRail(float divisor) {
		if (NON_RAIL_BUCKETS.isEmpty()) {
			return "（本窗口没有非钢轨批次）";
		}

		final List<Map.Entry<String, Bucket>> entries = new ArrayList<>(NON_RAIL_BUCKETS.entrySet());
		entries.sort((left, right) -> Integer.compare(right.getValue().draws, left.getValue().draws));

		final StringBuilder builder = new StringBuilder();
		int restDraws = 0;
		for (int i = 0; i < entries.size(); i++) {
			final Map.Entry<String, Bucket> entry = entries.get(i);
			if (i < MAX_NON_RAIL_REPORTED) {
				if (i > 0) {
					builder.append(" | ");
				}
				builder.append(entry.getKey())
						.append('=')
						.append(String.format("%.1f", entry.getValue().draws / divisor))
						.append('(')
						.append(String.format("%.2f", entry.getValue().batches / divisor))
						.append("批)");
			} else {
				restDraws += entry.getValue().draws;
			}
		}

		if (entries.size() > MAX_NON_RAIL_REPORTED) {
			builder.append(String.format(" …另 %d 个贴图（合计 %.1f draw/帧）", entries.size() - MAX_NON_RAIL_REPORTED, restDraws / divisor));
		}
		return builder.toString();
	}
}
