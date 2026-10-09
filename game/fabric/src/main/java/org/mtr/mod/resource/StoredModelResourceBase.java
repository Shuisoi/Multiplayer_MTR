package org.mtr.mod.resource;

import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.*;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mapping.mapper.OptimizedRenderer;
import org.mtr.mod.Init;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.render.DynamicVehicleModel;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.QueuedRenderLayer;
import org.mtr.mod.render.StoredMatrixTransformations;

import javax.annotation.Nullable;

public interface StoredModelResourceBase {

	default ObjectObjectImmutablePair<OptimizedModelWrapper, DynamicVehicleModel> load(String modelResource, String textureResource, boolean flipTextureV, double modelYOffset, ResourceProvider resourceProvider) {
		CustomResourceLoader.OPTIMIZED_RENDERER_WRAPPER.beginReload();

		final boolean isBlockbench = modelResource.endsWith(".bbmodel");
		final boolean isSupportedModelResource = ModelResourceLoader.isSupportedModelResource(modelResource);
		final Identifier textureId = CustomResourceTools.formatIdentifierWithDefault(textureResource, "png");
		ObjectObjectImmutablePair<OptimizedModelWrapper, DynamicVehicleModel> models;

		if (isBlockbench) {
			final Object2ObjectOpenHashMap<PartCondition, ObjectArrayList<OptimizedModelWrapper.MaterialGroupWrapper>> materialGroups = new Object2ObjectOpenHashMap<>();
			final DynamicVehicleModel tempDynamicVehicleModel = new DynamicVehicleModel(
					new BlockbenchModel(new JsonReader(Utilities.parseJson(resourceProvider.get(CustomResourceTools.formatIdentifierWithDefault(modelResource, "bbmodel"))))),
					textureId,
					new ModelProperties(modelYOffset),
					new PositionDefinitions(),
					""
			);
			tempDynamicVehicleModel.writeFloorsAndDoorways(new ObjectArrayList<>(), new ObjectArrayList<>(), new Object2ObjectOpenHashMap<>(), materialGroups, new Object2ObjectOpenHashMap<>(), new Object2ObjectOpenHashMap<>());
			models = new ObjectObjectImmutablePair<>(OptimizedModelWrapper.fromMaterialGroups(materialGroups.get(PartCondition.NORMAL)), tempDynamicVehicleModel);
		} else if (isSupportedModelResource) {
			try {
				final Object2ObjectOpenHashMap<PartCondition, ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper>> objModels = new Object2ObjectOpenHashMap<>();
				final Object2ObjectAVLTreeMap<String, OptimizedModel.ObjModel> rawModels = ModelResourceLoader.loadModel(modelResource, textureId, flipTextureV, resourceProvider);
				transform(rawModels.values());
				final DynamicVehicleModel dynamicVehicleModel = new DynamicVehicleModel(
						rawModels,
						textureId,
						new ModelProperties(modelYOffset),
						new PositionDefinitions(),
						""
				);
				dynamicVehicleModel.writeFloorsAndDoorways(new ObjectArrayList<>(), new ObjectArrayList<>(), new Object2ObjectOpenHashMap<>(), new Object2ObjectOpenHashMap<>(), new Object2ObjectOpenHashMap<>(), objModels);
				/*
				 * 源几何交接点（notes/398）。
				 *
				 * <p><b>为什么是"这里"而不是别处：</b>下面那一行会把这批 {@code ObjModel} 上传成 VBO 顶点数据，
				 * 之后再也不可能从 {@code OptimizedModel} 里读回顶点。而"把整根轨烘成一个模型"必须在
				 * **上传之前**拿到 CPU 侧的 {@code RawMesh}。放在这一行之前 = 拿到的正是**马上要被上传的那一份**，
				 * 几何完全一致。</p>
				 *
				 * <p><b>为什么是引用而不是快照：</b>下面那一行里 {@code fromObjModels} 会对每个
				 * {@code RawModel} 跑一次 {@code generateNormals()} + {@code distinct()}
				 * （映射库的 {@code lambda$fromObjModels$2}）。拿引用就能在后面读到**归一化之后**的
				 * 那份网格 —— 反过来在这里立刻快照，拿到的法线可能是全零，着色会坏。</p>
				 *
				 * <p>默认实现什么都不做 ⇒ 车辆/标志/电梯/物件一律不受影响，只有 {@code RailResource} 会接。</p>
				 */
				onObjModelsReady(objModels.get(PartCondition.NORMAL));
				models = new ObjectObjectImmutablePair<>(OptimizedModelWrapper.fromObjModels(objModels.get(PartCondition.NORMAL)), dynamicVehicleModel);
			} catch (Exception e) {
				Init.LOGGER.error("[{}] Invalid model!", modelResource, e);
				models = new ObjectObjectImmutablePair<>(null, null);
			}
		} else {
			models = new ObjectObjectImmutablePair<>(null, null);
		}

		CustomResourceLoader.OPTIMIZED_RENDERER_WRAPPER.finishReload();
		return models;
	}

	default void render(StoredMatrixTransformations storedMatrixTransformations, int light) {
		final OptimizedModelWrapper optimizedModel = getOptimizedModel();
		final DynamicVehicleModel dynamicVehicleModel = getDynamicVehicleModel();

		if (OptimizedRenderer.hasOptimizedRendering()) {
			if (optimizedModel != null) {
				MainRenderer.scheduleRender(QueuedRenderLayer.TEXT, (graphicsHolder, offset) -> {
					storedMatrixTransformations.transform(graphicsHolder, offset);
					CustomResourceLoader.OPTIMIZED_RENDERER_WRAPPER.queue(optimizedModel, graphicsHolder, light);
					graphicsHolder.pop();
				});
			}
		} else {
			if (dynamicVehicleModel != null) {
				dynamicVehicleModel.render(storedMatrixTransformations, null, 0, new int[]{0}, light, new ObjectArrayList<>(), false);
			}
		}
	}

	@Nullable
	OptimizedModelWrapper getOptimizedModel();

	@Nullable
	DynamicVehicleModel getDynamicVehicleModel();

	void preload();

	/**
	 * 模型的 CPU 侧几何即将被上传成 VBO —— 给需要"拿到源顶点"的实现一个交接点（notes/398）。
	 *
	 * <p>调用时机在 {@code OptimizedModelWrapper.fromObjModels(...)} <b>之前</b>：</p>
	 * <ul>
	 *   <li>此时顶点还没上 GPU，{@code ObjModel} 里还留着 {@code RawModel}；</li>
	 *   <li>此时 {@code generateNormals()} / {@code distinct()} <b>还没跑</b> —— 所以实现应该**存引用**、
	 *       到真正要用的时候再去读，才能读到归一化后的网格（见调用点的长注释）。</li>
	 * </ul>
	 *
	 * <p>默认空实现：只有钢轨合并烘焙（{@code MmtrRailMeshCache}）需要它。</p>
	 */
	default void onObjModelsReady(@Nullable ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper> objModelsForPartCondition) {
	}

	default void transform(ObjectCollection<OptimizedModel.ObjModel> values) {
	}
}
