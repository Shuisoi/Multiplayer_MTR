package org.mtr.mod.resource;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectDoubleImmutablePair;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectSet;
import org.mtr.mapping.holder.Box;
import org.mtr.mapping.mapper.OptimizedRenderer;
import org.mtr.mod.Init;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.DynamicVehicleModel;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.MmtrVehicleDrawProbe;
import org.mtr.mod.render.QueuedRenderLayer;
import org.mtr.mod.render.StoredMatrixTransformations;

import javax.annotation.Nullable;

/**
 * A 路线（notes/400 §6）：把一辆车**同一个动画类**的门合成一次 draw。
 *
 * <h2>要解决什么</h2>
 *
 * <p>开门态下门走的不是批次，而是 {@code ModelPropertiesPart.renderNormal} 里那条"逐部件组排队"的路
 * （{@code optimizedModelDoor}）。实测一辆车 24 组门 + 4 组雨刷，每组排一次队、每组恰好一次 draw：
 * {@code [MMTR-VDRAW] 门/雨刷逐位置排队=151776 次 draw avg=1.0 max=1}，而关门态整辆车只有 40 次 draw。
 * 于是<b>开门态比关门态多出 280 次 draw/pass</b>（notes/400 §1），这就是本节要消掉的东西。</p>
 *
 * <h2>为什么能合、为什么合了不变</h2>
 *
 * <p>两件已用实验定下来的事实（notes/400 §5）：</p>
 * <ol>
 *   <li>每个门部件组的位置数是 <b>1</b>（{@code [MMTR-DOORSHAPE] … 位置数=1}）——
 *       所以"逐部件组"与"逐位置"是同一件事，合并的粒度是部件组而不是位置；</li>
 *   <li>门几何在 {@code addObjModelPosition} 里已经按自己的位置烘好，draw 时那次
 *       {@code translate(x/16, y/16, z/16)} <b>纯粹是动画位移</b>
 *       （{@code -Dmmtr.doortranslate=false} 实机实验：门位置照旧、只是不再滑动）。</li>
 * </ol>
 *
 * <p>因此：同一个动画类里的门，几何各自站在自己的位置上，只需要一个<b>共同的</b>位移
 * ⇒ 合成一份几何、排一次队、用一个位移画出去，每个门落点与合并前逐位相同。</p>
 *
 * <h2>什么时候<b>不</b>合（这里就是安全阀）</h2>
 *
 * <p>合并的前提是"这一帧这个类里的每一组门都用同一个位移画"。这一条在<b>运行时</b>才成立
 * （{@code canOpen} 取决于这组门自己那个 doorway 有没有对着站台、{@code shouldRender} 取决于
 * 它自己的 renderFrom/Until 时间窗、{@code doorOverrideValue} 取决于有没有玩家堵门）。
 * 所以每次都先<b>试算</b>：把类里每一组的位移算出来比一遍 ——</p>
 * <ul>
 *   <li>全一样 ⇒ 用合并模型画一次，并告诉 {@code renderNormal} 这一组别再画（否则是重影）；</li>
 *   <li>有一个不一样、或者有一组这一帧不该画 ⇒ <b>整个类都不合</b>，一组都不画，
 *       让 {@code renderNormal} 照旧逐组画。</li>
 * </ul>
 *
 * <p>于是"合并没有成立"就等于改动前，不可能出现半个类被合并、位置还对不上的情况。</p>
 *
 * <h2>开关</h2>
 *
 * <p><b>默认开启</b>（2026-10-08 起）。它先以默认关闭上线、经实机验证后才翻成默认开
 * （与 {@code mmtr.vehiclemerge} 同样的纪律）：实测门路径 draw / 每节开门车 <b>24.8 → 9.0（−64%）</b>、
 * 排队次数 <b>−80%</b>、开门态「非钢轨 draws」491.2 → 83.4、可比窗口 45.3 → 53.6 FPS；
 * 且用户已肉眼确认<b>车门 / 门玻璃 / 面板 / 车灯 / 雨刷</b>画面逐位不变（notes/400 §6.6）。</p>
 *
 * <p>要回到改动前的行为：{@code -Dmmtr.doorbatch=false}（逐部件组排队，一组一次 draw）。</p>
 */
public final class MmtrDoorBatch {

	private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("mmtr.doorbatch", "true"));

	private static final ObjectSet<ModelPropertiesPart> NOTHING_BATCHED = new ObjectArraySet<>();

	/** "为什么这个类没合并"的说明上限（每个类最多一条，总数也封顶）—— 探针纪律：输出有界。 */
	private static final int MAX_NOT_BATCHED_LOGS = 8;

	/**
	 * {@code z} 分量的允许差（单位 1/16 格）。
	 *
	 * <p>开门位移的 z 是 {@code PersistentVehicleData.getInterpolatedDoorValue} 给的，而它是拿
	 * {@code System.currentTimeMillis()} 插值的（{@code Interpolation.getValue} 按"距 startMillis 多少毫秒"
	 * 算）⇒ 同一帧里**先算的门和后算的门落在不同的毫秒上**，位移本来就差一点点。实测差
	 * {@code 0.028}（≈0.4 ms 的门位移，见 18:38:44 那条判定日志）。要求逐位相等的话，门一动就永远合不上。</p>
	 *
	 * <p>而这条判据真正要挡的是"这一组门和那一组状态不同"（一扇能开一扇不能、或者有玩家堵住其中一扇）——
	 * 那种差是 {@code O(1)}–{@code O(20)}（zMultiplier = 14）。{@code 0.05} 既躲得开时间抖动
	 * （世界空间 ≈ 3 mm），又拦得住状态差异。</p>
	 *
	 * <p>{@code x} 与 {@code y} 不设容差：{@code x} 走的是 {@code getDoorAnimationX}（只看门值，与时间无关），
	 * {@code y} 是闪光遮挡用的哨兵（要么相等要么是天壤之别）。</p>
	 */
	private static final float DOOR_OFFSET_TOLERANCE = 0.05F;

	private final ObjectArrayList<Batch> batches = new ObjectArrayList<>();
	/** 复用同一份集合（渲染是单线程、调用方在同一帧内立刻用掉），免得每车每帧都新分配一个 set。 */
	private final ObjectArraySet<ModelPropertiesPart> batchedParts = new ObjectArraySet<>();
	private final ObjectArraySet<String> notBatchedNoted = new ObjectArraySet<>();
	private int notBatchedLogCount;
	private boolean built;

	public static boolean isEnabled() {
		return ENABLED && OptimizedRenderer.hasOptimizedRendering();
	}

	/**
	 * 画掉这一帧所有可合并的门类。
	 *
	 * @return 已经被这次批处理画过的部件 —— 调用方必须把它们传给
	 *         {@code ModelPropertiesPart.render(…, doorBatched=true)}，否则会画第二遍
	 */
	public ObjectSet<ModelPropertiesPart> renderBatched(
			DynamicVehicleModel model,
			StoredMatrixTransformations storedMatrixTransformations,
			@Nullable VehicleExtension vehicle,
			int light,
			ObjectArrayList<ObjectDoubleImmutablePair<Box>> openDoorways
	) {
		if (!isEnabled() || vehicle == null || openDoorways.isEmpty()) {
			return NOTHING_BATCHED;
		}

		if (!built) {
			built = true;
			build(model);
		}
		if (batches.isEmpty()) {
			return NOTHING_BATCHED;
		}

		batchedParts.clear();
		for (final Batch batch : batches) {
			final float[] transform = commonTransform(batch, vehicle, openDoorways);
			if (transform == null) {
				continue;
			}
			MainRenderer.scheduleRender(QueuedRenderLayer.TEXT, (graphicsHolder, offset) -> {
				storedMatrixTransformations.transform(graphicsHolder, offset);
				graphicsHolder.push();
				graphicsHolder.translate(transform[0] / 16, transform[1] / 16, transform[2] / 16);
				if (batch.flipped) {
					graphicsHolder.rotateYDegrees(180);
				}
				CustomResourceLoader.OPTIMIZED_RENDERER_WRAPPER.queue(batch.model, graphicsHolder, light);
				graphicsHolder.pop();
				graphicsHolder.pop();
			});
			MmtrVehicleDrawProbe.onDoorBatchQueued(batch.model.partCount());
			batchedParts.addAll(batch.parts);
		}
		return batchedParts.isEmpty() ? NOTHING_BATCHED : batchedParts;
	}

	/**
	 * "这个类为什么没合并"要有话说（notes/400 §6.3）。
	 *
	 * <p>合并不成立时的现场表现是**什么都没变**（draw 数与改动前一样），而那正好也是
	 * "开关没打开"、"这个模型没门"、"类里只有一组"的表现 —— 光看数字分不出来。
	 * 所以第一次见到某个类不合就把它为什么不合打出来，**每个类最多一条、总数也封顶**，
	 * 既不刷屏也留得下证据。</p>
	 */
	private void noteNotBatched(Batch batch, String reason) {
		if (notBatchedLogCount >= MAX_NOT_BATCHED_LOGS || notBatchedNoted.contains(batch.key)) {
			return;
		}
		notBatchedNoted.add(batch.key);
		notBatchedLogCount++;
		Init.LOGGER.info("[MMTR-DOORBATCH] 类 {}（{} 组门）这一帧不合并：{}", batch.key, batch.parts.size(), reason);
		if (notBatchedLogCount == MAX_NOT_BATCHED_LOGS) {
			Init.LOGGER.info("[MMTR-DOORBATCH] 不合并的说明已达 {} 条上限，后续不再逐类输出（合并仍在正常尝试）", MAX_NOT_BATCHED_LOGS);
		}
	}

	/**
	 * 把一辆车同一个动画类的门合成一份几何。只在第一次渲染这一辆车时做一次，
	 * 每个类只 upload 一次 ⇒ 每个材质一次 draw。
	 */
	private void build(DynamicVehicleModel model) {
		final Object2ObjectOpenHashMap<String, ObjectArrayList<ModelPropertiesPart>> partsByClass = new Object2ObjectOpenHashMap<>();
		model.modelProperties.iterateParts(part -> {
			final String key = part.doorBatchKey();
			if (key != null) {
				partsByClass.computeIfAbsent(key, ignored -> new ObjectArrayList<>()).add(part);
			}
		});
		if (partsByClass.isEmpty()) {
			return;
		}

		CustomResourceLoader.OPTIMIZED_RENDERER_WRAPPER.beginReload();
		try {
			partsByClass.forEach((key, parts) -> {
				if (parts.size() < 2) {
					// 一个部件本来就只要一次 draw，合并进来只是多一份显存。
					return;
				}
				final ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper> objModels = new ObjectArrayList<>();
				parts.forEach(part -> objModels.addAll(part.doorBatchGeometry()));
				final OptimizedModelWrapper merged = MmtrVehicleMeshMerger.mergeOrNull(objModels);
				if (merged == null) {
					// 合并拿不到结果（映射库变了/GL 出问题）⇒ 这一类退回逐部件，绝不留半成品。
					Init.LOGGER.warn("[MMTR-DOORBATCH] 类 {} 合并失败（{} 个部件组）—— 这一类退回逐部件排队", key, parts.size());
					return;
				}
				batches.add(new Batch(key, merged, parts.get(0).doorBatchFlipped(), parts));
			});
		} finally {
			CustomResourceLoader.OPTIMIZED_RENDERER_WRAPPER.finishReload();
		}

		if (!batches.isEmpty()) {
			int draws = 0;
			for (final Batch batch : batches) {
				draws += batch.model.partCount();
			}
			Init.LOGGER.info("[MMTR-DOORBATCH] 门合并：{} 个动画类、{} 组门 → {} 次 draw/pass（-Dmmtr.doorbatch=false 可回退）",
					batches.size(), batches.stream().mapToInt(batch -> batch.parts.size()).sum(), draws);
		}
	}

	/**
	 * 试算一个类里每一组门这一帧的位移，只有全部一致才返回那个位移。
	 *
	 * <p>"一致"对 {@code z} 是"差在 {@link #DOOR_OFFSET_TOLERANCE} 以内"（插值是按毫秒采样的，
	 * 逐位相等不可能，见那个常量的说明）。取的是**第一组**的位移 —— 同一类里这些值本来就该是同一个，
	 * 差只来自同一帧内的采样时刻。</p>
	 *
	 * @return {@code {x, y, z}}（单位 1/16 格）；{@code null} = 这个类这一帧不能合并
	 */
	@Nullable
	private float[] commonTransform(Batch batch, VehicleExtension vehicle, ObjectArrayList<ObjectDoubleImmutablePair<Box>> openDoorways) {
		float[] common = null;
		ModelPropertiesPart commonPart = null;
		for (final ModelPropertiesPart part : batch.parts) {
			final float[] translation = part.doorBatchTranslation(vehicle, openDoorways);
			if (translation == null) {
				// 这个组这一帧不该画（条件不成立 / shouldRender 为假 / 位置数不为 1），
				// 而它的几何在合并模型里 —— 合并就画多了，整类放弃。
				noteNotBatched(batch, part.doorBatchNames() + " 这一帧不参与（条件不成立，或者它自己的 shouldRender 为假）");
				return null;
			}
			if (common == null) {
				common = translation;
				commonPart = part;
			} else if (common[0] != translation[0] || common[1] != translation[1] || Math.abs(common[2] - translation[2]) > DOOR_OFFSET_TOLERANCE) {
				// 同一个类里的门这一帧位移不同（只有站台只覆盖一部分门、或者有玩家堵住某一扇时才会发生）
				// ⇒ 一个共同位移画不出来，整类退回逐组。
				noteNotBatched(batch, String.format("%s 与 %s 的位移不同：%s vs %s（位置×16，含动画位移）",
						commonPart.doorBatchNames(), part.doorBatchNames(), format(common), format(translation)));
				return null;
			}
		}
		return common;
	}

	private static String format(float[] values) {
		return String.format("[%.4f, %.4f, %.4f]", values[0], values[1], values[2]);
	}

	private static final class Batch {

		private final String key;
		private final OptimizedModelWrapper model;
		private final boolean flipped;
		private final ObjectArrayList<ModelPropertiesPart> parts;

		private Batch(String key, OptimizedModelWrapper model, boolean flipped, ObjectArrayList<ModelPropertiesPart> parts) {
			this.key = key;
			this.model = model;
			this.flipped = flipped;
			this.parts = parts;
		}
	}
}
