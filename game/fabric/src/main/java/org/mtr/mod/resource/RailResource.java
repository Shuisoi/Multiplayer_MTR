package org.mtr.mod.resource;

import org.mtr.core.serializer.ReaderBase;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.config.Config;
import org.mtr.mod.generated.resource.RailResourceSchema;
import org.mtr.mod.render.DynamicVehicleModel;

import javax.annotation.Nullable;

public final class RailResource extends RailResourceSchema implements StoredModelResourceBase {

	public final boolean shouldPreload;
	private final CachedResource<ObjectObjectImmutablePair<OptimizedModelWrapper, DynamicVehicleModel>> cachedRailResource;

	/**
	 * 烘焙用的**源几何**（notes/398）。
	 *
	 * <p>存的是 {@code ObjModel} 的**引用**，不是快照 —— 理由见
	 * {@link StoredModelResourceBase#onObjModelsReady}。它让 CPU 侧的顶点数据在这根资源活着期间
	 * 一直可达，代价是每个钢轨模型多留一份未上传的网格（几个模型、几 KB 级，可接受）。</p>
	 *
	 * <p>{@code null} = 这条路径拿不到源几何（比如是 {@code .bbmodel}，或映射库改了字段名）
	 * ⇒ 调用方必须回退到逐实例渲染，而不是什么都不画。</p>
	 */
	@Nullable
	private ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper> bakeSourceObjModels;

	public RailResource(ReaderBase readerBase, ResourceProvider resourceProvider) {
		super(readerBase, resourceProvider);
		updateData(readerBase);
		shouldPreload = Config.getClient().matchesPreloadResourcePattern(id);
		cachedRailResource = new CachedResource<>(() -> load(modelResource, textureResource, flipTextureV, 0, resourceProvider), shouldPreload ? Integer.MAX_VALUE : VehicleModel.MODEL_LIFESPAN);
	}

	/**
	 * Used to create the default rail
	 */
	public RailResource(String id, String name, ResourceProvider resourceProvider) {
		super(id, name, "777777", "", "", false, 0, 0, resourceProvider);
		shouldPreload = false;
		cachedRailResource = new CachedResource<>(() -> null, Integer.MAX_VALUE);
	}

	@Override
	public void onObjModelsReady(@Nullable ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper> objModelsForPartCondition) {
		bakeSourceObjModels = objModelsForPartCondition;
	}

	/** 烘焙用的源几何；{@code null} = 不可烘焙（调用方回退逐实例路径）。见 {@link #bakeSourceObjModels}。 */
	@Nullable
	public ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper> getBakeSourceObjModels() {
		return bakeSourceObjModels;
	}

	@Override
	@Nullable
	public OptimizedModelWrapper getOptimizedModel() {
		final ObjectObjectImmutablePair<OptimizedModelWrapper, DynamicVehicleModel> railResource = cachedRailResource.getData(false);
		return railResource == null ? null : railResource.left();
	}

	@Override
	@Nullable
	public DynamicVehicleModel getDynamicVehicleModel() {
		final ObjectObjectImmutablePair<OptimizedModelWrapper, DynamicVehicleModel> railResource = cachedRailResource.getData(false);
		return railResource == null ? null : railResource.right();
	}

	@Override
	public void preload() {
		cachedRailResource.getData(true);
	}

	public String getId() {
		return id;
	}

	public String getName() {
		return TextHelper.translatable(name).getString();
	}

	public int getColor() {
		return CustomResourceTools.colorStringToInt(color);
	}

	public double getRepeatInterval() {
		return repeatInterval;
	}

	public double getModelYOffset() {
		return modelYOffset;
	}

	public static String getIdWithoutDirection(String id) {
		return id.endsWith("_1") || id.endsWith("_2") ? id.substring(0, id.length() - 2) : id;
	}
}
