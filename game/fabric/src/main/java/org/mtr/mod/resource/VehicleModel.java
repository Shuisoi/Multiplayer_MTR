package org.mtr.mod.resource;

import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mod.Init;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.generated.resource.VehicleModelSchema;
import org.mtr.mod.render.DynamicVehicleModel;

import javax.annotation.Nullable;

public final class VehicleModel extends VehicleModelSchema {

	boolean shouldPreload = false;
	final CachedResource<DynamicVehicleModel> cachedModel;
	private final JsonReader modelPropertiesJsonReader;
	private final JsonReader positionDefinitionsJsonReader;

	public static final int MODEL_LIFESPAN = 60000;

	/** 只有真的卡了一帧才打一条读数（一帧约 11 ms，20 ms 已经肉眼可见）。 */
	private static final long MODEL_BUILD_LOG_MIN_MILLIS = 20;
	private static final int MAX_MODEL_BUILD_LOGS = 60;
	private static int modelBuildLogCount;

	/**
	 * 正在别的线程上解析的那一份（notes/400 §7）。
	 *
	 * <p>{@code pendingSource} 与 {@code pendingParse} 都是"一次重建"的生命周期：解析好（或失败）之后
	 * 立刻 {@link #clearPending()}。**每次重建都要重新解析**：解析产物（{@code RawModel}）会被
	 * {@code addObjModelPosition} 就地追加变换，复用会让几何一次比一次多。</p>
	 */
	@Nullable
	private ModelResourceLoader.ModelSource pendingSource;
	@Nullable
	private MmtrAsyncModelParse<Object2ObjectAVLTreeMap<String, OptimizedModel.ObjModel>> pendingParse;
	private long pendingReadMillis = -1;

	private void clearPending() {
		// 顺手把那份 4.3 MB 的文本放掉
		pendingSource = null;
		pendingParse = null;
		pendingReadMillis = -1;
	}

	public VehicleModel(ReaderBase readerBase, ResourceProvider resourceProvider) {
		super(readerBase, resourceProvider);
		updateData(readerBase);
		modelPropertiesJsonReader = new JsonReader(Utilities.parseJson(resourceProvider.get(CustomResourceTools.formatIdentifierWithDefault(modelPropertiesResource, "json"))));
		positionDefinitionsJsonReader = new JsonReader(Utilities.parseJson(resourceProvider.get(CustomResourceTools.formatIdentifierWithDefault(positionDefinitionsResource, "json"))));
		cachedModel = new CachedResource<>(() -> createModel(new ModelProperties(modelPropertiesJsonReader), new PositionDefinitions(positionDefinitionsJsonReader), modelPropertiesResource), shouldPreload ? Integer.MAX_VALUE : MODEL_LIFESPAN);
	}

	public VehicleModel(ReaderBase readerBase, JsonReader modelPropertiesJsonReader, JsonReader positionDefinitionsJsonReader, String id, ResourceProvider resourceProvider) {
		super(readerBase, resourceProvider);
		updateData(readerBase);
		this.modelPropertiesJsonReader = modelPropertiesJsonReader;
		this.positionDefinitionsJsonReader = positionDefinitionsJsonReader;
		cachedModel = new CachedResource<>(() -> createModel(new ModelProperties(modelPropertiesJsonReader), new PositionDefinitions(positionDefinitionsJsonReader), id), shouldPreload ? Integer.MAX_VALUE : MODEL_LIFESPAN);
	}

	VehicleModel(
			String modelResource,
			String textureResource,
			String modelPropertiesResource,
			String positionDefinitionsResource,
			boolean flipTextureV,
			ResourceProvider resourceProvider
	) {
		super(
				modelResource,
				textureResource,
				modelPropertiesResource,
				positionDefinitionsResource,
				flipTextureV,
				resourceProvider
		);
		modelPropertiesJsonReader = new JsonReader(Utilities.parseJson(resourceProvider.get(CustomResourceTools.formatIdentifierWithDefault(modelPropertiesResource, "json"))));
		positionDefinitionsJsonReader = new JsonReader(Utilities.parseJson(resourceProvider.get(CustomResourceTools.formatIdentifierWithDefault(positionDefinitionsResource, "json"))));
		cachedModel = new CachedResource<>(() -> createModel(new ModelProperties(modelPropertiesJsonReader), new PositionDefinitions(positionDefinitionsJsonReader), modelPropertiesResource), shouldPreload ? Integer.MAX_VALUE : MODEL_LIFESPAN);
	}

	public MinecraftModelResource getAsMinecraftResource() {
		return new MinecraftModelResource(modelResource, modelPropertiesResource, positionDefinitionsResource);
	}

	public void addToTextureResource(ObjectArraySet<String> textureResources) {
		final ModelProperties modelProperties = new ModelProperties(modelPropertiesJsonReader);
		if (modelProperties.gangwayInnerSideTexture != null) {
			textureResources.add(modelProperties.gangwayInnerSideTexture.data.toString());
		}
		if (modelProperties.gangwayInnerTopTexture != null) {
			textureResources.add(modelProperties.gangwayInnerTopTexture.data.toString());
		}
		if (modelProperties.gangwayInnerBottomTexture != null) {
			textureResources.add(modelProperties.gangwayInnerBottomTexture.data.toString());
		}
		if (modelProperties.gangwayOuterSideTexture != null) {
			textureResources.add(modelProperties.gangwayOuterSideTexture.data.toString());
		}
		if (modelProperties.gangwayOuterTopTexture != null) {
			textureResources.add(modelProperties.gangwayOuterTopTexture.data.toString());
		}
		if (modelProperties.gangwayOuterBottomTexture != null) {
			textureResources.add(modelProperties.gangwayOuterBottomTexture.data.toString());
		}
		if (modelProperties.barrierInnerSideTexture != null) {
			textureResources.add(modelProperties.barrierInnerSideTexture.data.toString());
		}
		if (modelProperties.barrierInnerTopTexture != null) {
			textureResources.add(modelProperties.barrierInnerTopTexture.data.toString());
		}
		if (modelProperties.barrierInnerBottomTexture != null) {
			textureResources.add(modelProperties.barrierInnerBottomTexture.data.toString());
		}
		if (modelProperties.barrierOuterSideTexture != null) {
			textureResources.add(modelProperties.barrierOuterSideTexture.data.toString());
		}
		if (modelProperties.barrierOuterTopTexture != null) {
			textureResources.add(modelProperties.barrierOuterTopTexture.data.toString());
		}
		if (modelProperties.barrierOuterBottomTexture != null) {
			textureResources.add(modelProperties.barrierOuterBottomTexture.data.toString());
		}
		textureResources.add(textureResource);
	}

	VehicleModelWrapper toVehicleModelWrapper() {
		final ModelProperties modelProperties = new ModelProperties(modelPropertiesJsonReader);
		final PositionDefinitions positionDefinitions = new PositionDefinitions(positionDefinitionsJsonReader);
		final ObjectArrayList<ModelPropertiesPartWrapper> parts = new ObjectArrayList<>();
		modelProperties.iterateParts(modelPropertiesPart -> modelPropertiesPart.addToModelPropertiesPartWrapperMap(positionDefinitions, parts));
		return modelProperties.toVehicleModelWrapper(modelResource, textureResource, modelPropertiesResource, positionDefinitionsResource, flipTextureV, parts);
	}

	private DynamicVehicleModel createModel(ModelProperties modelProperties, PositionDefinitions positionDefinitions, String id) {
		final Identifier textureId = CustomResourceTools.formatIdentifierWithDefault(textureResource, "png");
		final long startNanos = System.nanoTime();
		long readMillis = -1;
		long parseMillis = -1;

		/*
		 * notes/400 §7：把"OBJ 文本 → RawMesh"这一段挪到别的线程上。
		 *
		 * 切分点：读（渲染线程，3 ms，资源缓存不是线程安全的）→ 解析（可搬，120–155 ms）→ 建 VBO
		 * （必须在渲染线程）。解析还没好时这一轮返回 null —— CachedResource 与
		 * getCachedVehicleResource 那条链**本来就用 null 表达"未就绪"**，所以车厢只是晚一两个 tick 出现，
		 * 而不是把渲染线程卡住 300 ms。
		 */
		ModelResourceLoader.ModelSource source = null;
		if (ModelResourceLoader.isSupportedModelResource(modelResource) && MmtrAsyncModelParse.isEnabled()) {
			/*
			 * 读只做**一次**：`createSource` 除了取资源，还要扫一遍 4.3 MB 的文本找 mtllib；
			 * 解析没好的那几个 tick 里每 tick 重扫一遍是白烧 CPU。
			 */
			if (pendingSource == null) {
				final long readStartNanos = System.nanoTime();
				pendingSource = ModelResourceLoader.createSource(modelResource, textureId, flipTextureV, resourceProvider);
				pendingReadMillis = (System.nanoTime() - readStartNanos) / 1_000_000L;
			}
			source = pendingSource;
			final ModelResourceLoader.ModelSource readySource = source;
			if (readySource == null) {
				// MQO/MQOZ：先转换再解析，这条路暂不拆（notes/400 §7 记了为什么）。
				clearPending();
			} else {
				readMillis = pendingReadMillis;
				if (pendingParse == null) {
					pendingParse = new MmtrAsyncModelParse<>("后台解析 " + modelResource, () -> ModelResourceLoader.parseSource(readySource));
				}
				final Object2ObjectAVLTreeMap<String, OptimizedModel.ObjModel> parsed;
				try {
					parsed = pendingParse.poll();
				} catch (Throwable throwable) {
					// 后台解析失败 ⇒ **同步兜底**：模型照常出现，只是这一轮没省下时间（绝不静默）。
					Init.LOGGER.warn("[MMTR-MODEL] {} 后台解析失败，回退到渲染线程同步解析", modelResource, throwable);
					clearPending();
					final long parseStartNanos = System.nanoTime();
					final Object2ObjectAVLTreeMap<String, OptimizedModel.ObjModel> models = ModelResourceLoader.parseSource(readySource);
					parseMillis = (System.nanoTime() - parseStartNanos) / 1_000_000L;
					return buildModel(models, textureId, modelProperties, positionDefinitions, id, startNanos, readMillis, parseMillis, true);
				}
				if (parsed == null) {
					// 还没好：这一轮按"模型未就绪"处理（那条链本来就用 null 表达未就绪），下一轮再问。
					return null;
				}
				clearPending();
				return buildModel(parsed, textureId, modelProperties, positionDefinitions, id, startNanos, readMillis, -1, false);
			}
		}

		CustomResourceLoader.OPTIMIZED_RENDERER_WRAPPER.beginReload();
		try {
			if (modelResource.endsWith(".bbmodel")) {
				return new DynamicVehicleModel(
						new BlockbenchModel(new JsonReader(Utilities.parseJson(resourceProvider.get(CustomResourceTools.formatIdentifierWithDefault(modelResource, "bbmodel"))))),
						textureId,
						modelProperties,
						positionDefinitions,
						id
				);
			} else if (ModelResourceLoader.isSupportedModelResource(modelResource)) {
				final long parseStartNanos = System.nanoTime();
				final Object2ObjectAVLTreeMap<String, OptimizedModel.ObjModel> models = ModelResourceLoader.loadModel(modelResource, textureId, flipTextureV, resourceProvider);
				parseMillis = (System.nanoTime() - parseStartNanos) / 1_000_000L;
				return buildModel(models, textureId, modelProperties, positionDefinitions, id, startNanos, readMillis, parseMillis, false);
			} else {
				Init.LOGGER.error("[{}] Invalid model!", modelResource);
				return new DynamicVehicleModel(
						new BlockbenchModel(new JsonReader(new JsonObject())),
						textureId,
						modelProperties,
						positionDefinitions,
						id
				);
			}
		} catch (Exception e) {
			Init.LOGGER.error("[{}] Invalid model!", modelResource, e);
			return new DynamicVehicleModel(
					new BlockbenchModel(new JsonReader(new JsonObject())),
					textureId,
					modelProperties,
					positionDefinitions,
					id
			);
		} finally {
			CustomResourceLoader.OPTIMIZED_RENDERER_WRAPPER.finishReload();
		}
	}

	/** "建 VBO"那一半（必须在渲染线程），外加一条有界的分段读数。 */
	private DynamicVehicleModel buildModel(
			Object2ObjectAVLTreeMap<String, OptimizedModel.ObjModel> models,
			Identifier textureId,
			ModelProperties modelProperties,
			PositionDefinitions positionDefinitions,
			String id,
			long startNanos,
			long readMillis,
			long parseMillis,
			boolean parseOnRenderThread
	) {
		CustomResourceLoader.OPTIMIZED_RENDERER_WRAPPER.beginReload();
		final long buildStartNanos = System.nanoTime();
		/*
		 * CPU 时间 vs 墙钟时间（notes/401 §8）：这是"这段到底是在算、还是在等"的**判定性**读数。
		 * CPU ≈ 墙钟 ⇒ 真的是计算（搬走就有效）；CPU ≪ 墙钟 ⇒ 时间花在 GC 停顿/被别的线程抢核/驱动上
		 * （搬走没用，因为 GC 是全局停顿、抢核换谁都一样）。
		 */
		final long cpuStartNanos = currentThreadCpuNanos();
		try {
			final DynamicVehicleModel model = new DynamicVehicleModel(models, textureId, modelProperties, positionDefinitions, id);
			final long cpuNanos = currentThreadCpuNanos() - cpuStartNanos;
			reportModelBuild(startNanos, readMillis, parseMillis, (System.nanoTime() - buildStartNanos) / 1_000_000L, cpuNanos / 1_000_000L, parseOnRenderThread);
			return model;
		} finally {
			CustomResourceLoader.OPTIMIZED_RENDERER_WRAPPER.finishReload();
		}
	}

	/** 本线程的 CPU 时间（纳秒）；平台不支持时返回 {@code -1}。 */
	private static long currentThreadCpuNanos() {
		try {
			final java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
			return bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled() ? bean.getCurrentThreadCpuTime() : -1;
		} catch (Throwable throwable) {
			return -1;
		}
	}

	/**
	 * 一次模型构建花在哪几段上（notes/400 §7）：读 / 解析 / 建 VBO。
	 *
	 * <p>三段都是**直接量的**（不靠相减反推）。只在真的卡了一帧
	 * （≥ {@link #MODEL_BUILD_LOG_MIN_MILLIS}）时打，且总数封顶 —— 一个会话里模型构建本来就是
	 * 个位数量级，但这条读数必须一直在，否则"这次重建卡了多久、卡在哪一段"只能靠猜。</p>
	 */
	private void reportModelBuild(long startNanos, long readMillis, long parseMillis, long buildMillis, long cpuMillis, boolean parseOnRenderThread) {
		final long totalMillis = (System.nanoTime() - startNanos) / 1_000_000L;
		if (totalMillis < MODEL_BUILD_LOG_MIN_MILLIS || modelBuildLogCount >= MAX_MODEL_BUILD_LOGS) {
			return;
		}
		modelBuildLogCount++;
		Init.LOGGER.info("[MMTR-MODEL] {} 这一轮重建 {} ms ｜ 读 {} ｜ 解析 {} ｜ 建 VBO {} ms（本线程 CPU {} ms{}）{}",
				modelResource,
				totalMillis,
				readMillis < 0 ? "（上一轮已读）" : readMillis + " ms",
				parseMillis < 0 ? "已在后台完成" : parseMillis + " ms（本线程）",
				buildMillis,
				cpuMillis < 0 ? "?" : String.valueOf(cpuMillis),
				cpuMillis >= 0 && cpuMillis * 2 < buildMillis ? "，**远小于墙钟 ⇒ 这段在等（GC/抢核/驱动），不是在算**" : "",
				parseOnRenderThread ? " ｜ 注意：这一轮是后台失败后的同步兜底" : "");
		if (modelBuildLogCount == MAX_MODEL_BUILD_LOGS) {
			Init.LOGGER.info("[MMTR-MODEL] 模型构建读数已达 {} 条上限，后续不再输出", MAX_MODEL_BUILD_LOGS);
		}
	}
}
