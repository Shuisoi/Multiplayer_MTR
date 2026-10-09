package org.mtr.mod.resource;

import org.mtr.core.data.TransportMode;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.*;
import org.mtr.mapping.holder.Box;
import org.mtr.mapping.holder.MutableText;
import org.mtr.mapping.mapper.OptimizedRenderer;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.Init;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.config.Config;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.generated.resource.VehicleResourceSchema;
import org.mtr.mod.render.DynamicVehicleModel;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.MmtrVehicleDrawProbe;
import org.mtr.mod.render.QueuedRenderLayer;
import org.mtr.mod.render.StoredMatrixTransformations;
import org.mtr.mod.render.light.MmtrHeadlights;
import org.mtr.mod.sound.BveVehicleSound;
import org.mtr.mod.sound.BveVehicleSoundConfig;
import org.mtr.mod.sound.LegacyVehicleSound;
import org.mtr.mod.sound.MmtrTractionSoundSet;
import org.mtr.mod.sound.MmtrVehicleSound;
import org.mtr.mod.sound.VehicleSoundBase;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.mtr.mod.data.IGui.ARGB_WHITE;

public final class VehicleResource extends VehicleResourceSchema {

	public final Supplier<VehicleSoundBase> createVehicleSoundBase;
	public final boolean shouldPreload;
	@Nullable
	private final LegacyVehicleSupplier<ObjectArrayList<VehicleModel>> extraModelsSupplier;
	private final Int2ObjectAVLTreeMap<Int2ObjectAVLTreeMap<ObjectArrayList<VehicleModel>>> allModels = new Int2ObjectAVLTreeMap<>();
	private final Int2ObjectAVLTreeMap<Int2ObjectAVLTreeMap<CachedResource<CachedResource<CachedResource<VehicleResourceCacheHolder>>>>> cachedVehicleResource = new Int2ObjectAVLTreeMap<>();

	private static final boolean[][] CHRISTMAS_LIGHT_STAGES = {
			{true, false, false, false},
			{false, true, false, false},
			{false, false, true, false},
			{false, false, false, true},
			{true, false, false, false},
			{false, true, false, false},
			{false, false, true, false},
			{false, false, false, true},

			{true, true, false, false},
			{false, true, true, false},
			{false, false, true, true},
			{true, false, false, true},
			{true, true, false, false},
			{false, true, true, false},
			{false, false, true, true},
			{true, false, false, true},

			{true, false, true, false},
			{false, true, false, true},
			{true, false, true, false},
			{false, true, false, true},
			{true, false, true, false},
			{false, true, false, true},
			{true, false, true, false},
			{false, true, false, true},

			{true, false, false, false},
			{true, true, false, false},
			{true, true, true, false},
			{true, true, true, true},
			{false, true, false, false},
			{false, true, true, false},
			{false, true, true, true},
			{true, true, true, true},
			{false, false, true, false},
			{false, false, true, true},
			{true, false, true, true},
			{true, true, true, true},
			{false, false, false, true},
			{true, false, false, true},
			{true, true, false, true},
			{true, true, true, true},

			{false, false, false, false},
			{true, true, true, true},
			{true, true, true, true},
			{true, true, true, true},
			{false, false, false, false},
			{true, true, true, true},
			{true, true, true, true},
			{true, true, true, true},
	};

	public VehicleResource(ReaderBase readerBase, @Nullable LegacyVehicleSupplier<ObjectArrayList<VehicleModel>> extraModelsSupplier, ResourceProvider resourceProvider) {
		super(readerBase, resourceProvider);
		updateData(readerBase);
		this.extraModelsSupplier = extraModelsSupplier;
		createVehicleSoundBase = createVehicleSoundBaseInitializer();
		shouldPreload = Config.getClient().matchesPreloadResourcePattern(id);
		if (shouldPreload) {
			models.forEach(model -> model.shouldPreload = true);
		}
	}

	public VehicleResource(ReaderBase readerBase, ResourceProvider resourceProvider) {
		this(readerBase, null, resourceProvider);
	}

	VehicleResource(
			String id,
			String name,
			String color,
			TransportMode transportMode,
			double length,
			double width,
			double bogie1Position,
			double bogie2Position,
			double couplingPadding1,
			double couplingPadding2,
			String description,
			String wikipediaArticle,
			ObjectArrayList<String> tags,
			ObjectArrayList<VehicleModel> models,
			ObjectArrayList<VehicleModel> bogie1Models,
			ObjectArrayList<VehicleModel> bogie2Models,
			boolean hasGangway1,
			boolean hasGangway2,
			boolean hasBarrier1,
			boolean hasBarrier2,
			double legacyRiderOffset,
			String bveSoundBaseResource,
			String legacySpeedSoundBaseResource,
			long legacySpeedSoundCount,
			boolean legacyUseAccelerationSoundsWhenCoasting,
			boolean legacyConstantPlaybackSpeed,
			String legacyDoorSoundBaseResource,
			double legacyDoorCloseSoundTime,
			ResourceProvider resourceProvider
	) {
		super(
				id,
				name,
				color,
				transportMode,
				length,
				width,
				bogie1Position,
				bogie2Position,
				couplingPadding1,
				couplingPadding2,
				description,
				wikipediaArticle,
				hasGangway1,
				hasGangway2,
				hasBarrier1,
				hasBarrier2,
				legacyRiderOffset,
				bveSoundBaseResource,
				legacySpeedSoundBaseResource,
				legacySpeedSoundCount,
				legacyUseAccelerationSoundsWhenCoasting,
				legacyConstantPlaybackSpeed,
				legacyDoorSoundBaseResource,
				legacyDoorCloseSoundTime,
				resourceProvider
		);
		this.tags.addAll(tags);
		this.models.addAll(models);
		this.bogie1Models.addAll(bogie1Models);
		this.bogie2Models.addAll(bogie2Models);
		this.extraModelsSupplier = null;
		createVehicleSoundBase = createVehicleSoundBaseInitializer();
		shouldPreload = Config.getClient().matchesPreloadResourcePattern(id);
		if (shouldPreload) {
			models.forEach(model -> model.shouldPreload = true);
		}
	}

	@Nonnull
	@Override
	protected ResourceProvider modelsResourceProviderParameter() {
		return resourceProvider;
	}

	@Nonnull
	@Override
	protected ResourceProvider bogie1ModelsResourceProviderParameter() {
		return resourceProvider;
	}

	@Nonnull
	@Override
	protected ResourceProvider bogie2ModelsResourceProviderParameter() {
		return resourceProvider;
	}

	@Nullable
	public VehicleResourceCache getCachedVehicleResource(int carNumber, int totalCars, boolean force) {
		final int newCarNumber = extraModelsSupplier == null ? 0 : carNumber;
		final int newTotalCars = extraModelsSupplier == null ? 0 : totalCars;
		final CachedResource<CachedResource<VehicleResourceCacheHolder>> data1 = cachedVehicleResource.computeIfAbsent(newCarNumber, key -> new Int2ObjectAVLTreeMap<>()).computeIfAbsent(newTotalCars, key -> cachedVehicleResourceInitializer(newCarNumber, newTotalCars, force)).getData(force);
		if (data1 != null) {
			final CachedResource<VehicleResourceCacheHolder> data2 = data1.getData(force);
			if (data2 != null) {
				final VehicleResourceCacheHolder data3 = data2.getData(force);
				if (data3 != null) {
					final Object2ObjectOpenHashMap<PartCondition, OptimizedModelWrapper> optimizedModels = data3.optimizedModels.getData(force);
					final Object2ObjectOpenHashMap<PartCondition, OptimizedModelWrapper> optimizedModelsDoorsClosed = data3.optimizedModelsDoorsClosed.getData(force);
					final Object2ObjectOpenHashMap<PartCondition, OptimizedModelWrapper> optimizedModelsBogie1 = data3.optimizedModelsBogie1.getData(force);
					final Object2ObjectOpenHashMap<PartCondition, OptimizedModelWrapper> optimizedModelsBogie2 = data3.optimizedModelsBogie2.getData(force);
					if (optimizedModels != null && optimizedModelsDoorsClosed != null && optimizedModelsBogie1 != null && optimizedModelsBogie2 != null) {
						return new VehicleResourceCache(data3.floors, data3.doorways, optimizedModels, optimizedModelsDoorsClosed, optimizedModelsBogie1, optimizedModelsBogie2);
					}
				}
			}
		}
		return null;
	}

	public void queue(StoredMatrixTransformations storedMatrixTransformations, VehicleExtension vehicle, int carNumber, int totalCars, int light, boolean noOpenDoorways) {
		final VehicleResourceCache vehicleResourceCache = getCachedVehicleResource(carNumber, totalCars, false);
		if (vehicleResourceCache != null) {
			/*
			 * MMTR 车灯灯罩的颜色（notes/374）：**这一节车**的灯罩该是什么颜色（近光/远光 = 白、
			 * 尾灯 = 红、关闭/回库 = 暗），逐 draw 交给 MTR 的优化渲染器 ——
			 * 它那个 color 参数在 GL 里就是 COLOR 顶点属性，而 COLOR 在 MTR 的顶点映射里是
			 * VertexAttributeSource.GLOBAL（每个 draw 一个常量）⇒ 染色粒度正好是"一组几何"。
			 *
			 * <p>判据与光场同源（MmtrHeadlights.lampColor：端 + 档位 + 换向器），所以灯罩颜色
			 * 与灯投出的光束不可能对不上。没装灯的部件（车体、内装、门）照旧拿 ARGB_WHITE = 不染。</p>
			 */
			final int lampColor = MmtrHeadlights.lampColor(vehicle, carNumber);
			if (noOpenDoorways) {
				queue(vehicleResourceCache.optimizedModelsDoorsClosed, storedMatrixTransformations, vehicle, light, true, lampColor, totalCars);
			} else {
				queue(vehicleResourceCache.optimizedModels, storedMatrixTransformations, vehicle, light, false, lampColor, totalCars);
			}
		}
	}

	public void queueBogie(int bogieIndex, StoredMatrixTransformations storedMatrixTransformations, VehicleExtension vehicle, int light) {
		final VehicleResourceCache vehicleResourceCache = getCachedVehicleResource(0, 1, false);
		if (vehicleResourceCache != null && Utilities.isBetween(bogieIndex, 0, 1)) {
			queue(bogieIndex == 0 ? vehicleResourceCache.optimizedModelsBogie1 : vehicleResourceCache.optimizedModelsBogie2, storedMatrixTransformations, vehicle, light, true, ARGB_WHITE, 1);
		}
	}

	public String getId() {
		return id;
	}

	public MutableText getName() {
		return TextHelper.translatable(name);
	}

	public int getColor() {
		return CustomResourceTools.colorStringToInt(color);
	}

	public TransportMode getTransportMode() {
		return transportMode;
	}

	public double getLength() {
		return length;
	}

	public double getWidth() {
		return width;
	}

	public double getBogie1Position() {
		return bogie1Position;
	}

	public double getBogie2Position() {
		return bogie2Position;
	}

	public double getCouplingPadding1() {
		return couplingPadding1;
	}

	public double getCouplingPadding2() {
		return couplingPadding2;
	}

	public MutableText getDescription() {
		return TextHelper.translatable(description);
	}

	public String getWikipediaArticle() {
		return wikipediaArticle;
	}

	public void iterateModels(int carNumber, int totalCars, ModelConsumer modelConsumer) {
		iterateModels(getAllModels(carNumber, totalCars), modelConsumer);
	}

	public void iterateBogieModels(int bogieIndex, ModelConsumer modelConsumer) {
		if (Utilities.isBetween(bogieIndex, 0, 1)) {
			iterateModels(bogieIndex == 0 ? bogie1Models : bogie2Models, modelConsumer);
		}
	}

	public VehicleResourceWrapper toVehicleResourceWrapper() {
		final int carNumber = id.endsWith("trailer") ? 1 : id.endsWith("cab_2") ? 2 : 0;
		final int totalCars = id.endsWith("cab_3") ? 1 : 3;
		getCachedVehicleResource(carNumber, totalCars, true);
		return new VehicleResourceWrapper(
				id,
				name,
				color,
				transportMode,
				length,
				width,
				bogie1Position,
				bogie2Position,
				couplingPadding1,
				couplingPadding2,
				description,
				wikipediaArticle,
				tags,
				getAllModels(carNumber, totalCars).stream().map(VehicleModel::toVehicleModelWrapper).collect(Collectors.toCollection(ObjectArrayList::new)),
				bogie1Models.stream().map(VehicleModel::toVehicleModelWrapper).collect(Collectors.toCollection(ObjectArrayList::new)),
				bogie2Models.stream().map(VehicleModel::toVehicleModelWrapper).collect(Collectors.toCollection(ObjectArrayList::new)),
				hasGangway1,
				hasGangway2,
				hasBarrier1,
				hasBarrier2,
				legacyRiderOffset,
				bveSoundBaseResource,
				legacySpeedSoundBaseResource,
				legacySpeedSoundCount,
				legacyUseAccelerationSoundsWhenCoasting,
				legacyConstantPlaybackSpeed,
				legacyDoorSoundBaseResource,
				legacyDoorCloseSoundTime
		);
	}

	public void writeMinecraftResource(ObjectArraySet<MinecraftModelResource> minecraftModelResources, ObjectArraySet<String> minecraftTextureResources) {
		models.forEach(vehicleModel -> {
			minecraftModelResources.add(vehicleModel.getAsMinecraftResource());
			vehicleModel.addToTextureResource(minecraftTextureResources);
		});
		bogie1Models.forEach(vehicleModel -> {
			minecraftModelResources.add(vehicleModel.getAsMinecraftResource());
			vehicleModel.addToTextureResource(minecraftTextureResources);
		});
		bogie2Models.forEach(vehicleModel -> {
			minecraftModelResources.add(vehicleModel.getAsMinecraftResource());
			vehicleModel.addToTextureResource(minecraftTextureResources);
		});
	}

	public boolean hasGangway1() {
		return hasGangway1;
	}

	public boolean hasGangway2() {
		return hasGangway2;
	}

	public boolean hasBarrier1() {
		return hasBarrier1;
	}

	public boolean hasBarrier2() {
		return hasBarrier2;
	}

	private ObjectArrayList<VehicleModel> getAllModels(int carNumber, int totalCars) {
		if (extraModelsSupplier == null) {
			return models;
		} else {
			return allModels.getOrDefault(carNumber, new Int2ObjectAVLTreeMap<>()).getOrDefault(totalCars, new ObjectArrayList<>());
		}
	}

	public static boolean matchesCondition(VehicleExtension vehicle, PartCondition partCondition, boolean noOpenDoorways) {
		switch (partCondition) {
			case AT_DEPOT:
				return !vehicle.getIsOnRoute();
			case ON_ROUTE_FORWARDS:
				return vehicle.getIsOnRoute() && !vehicle.getReversed();
			case ON_ROUTE_BACKWARDS:
				return vehicle.getIsOnRoute() && vehicle.getReversed();
			case DOORS_CLOSED:
				return vehicle.persistentVehicleData.getDoorValue() == 0 && noOpenDoorways;
			case DOORS_OPENED:
				return vehicle.persistentVehicleData.getDoorValue() > 0 || !noOpenDoorways;
			case MMTR_LAMP:
				// 灯罩**一直在**（notes/374）：灭灯 = 一块暗玻璃，而不是把车头挖一个洞
				// （2026-10-03 之前用 ON_ROUTE_FORWARDS 那类方向条件，车往另一端跑时两端的灯罩
				//  一起消失，现场就是"灯没了"）。颜色由 queue 里那个逐 draw 的顶点色决定。
				return true;
			default:
				return getChristmasLightState(partCondition);
		}
	}

	public void collectTags(Object2ObjectAVLTreeMap<String, Object2ObjectAVLTreeMap<String, ObjectArrayList<String>>> tagMap) {
		tags.forEach(tag -> {
			final String[] tagSplit = tag.split(":");
			if (tagSplit.length == 2) {
				tagMap.computeIfAbsent(tagSplit[0], key -> new Object2ObjectAVLTreeMap<>()).computeIfAbsent(tagSplit[1], key -> new ObjectArrayList<>()).add(id);
			}
		});
	}

	private CachedResource<CachedResource<CachedResource<VehicleResourceCacheHolder>>> cachedVehicleResourceInitializer(int carNumber, int totalCars, boolean force) {
		final int modelLifespan = shouldPreload ? Integer.MAX_VALUE : VehicleModel.MODEL_LIFESPAN;
		return new CachedResource<>(() -> {
			final ObjectArrayList<VehicleModel> allModelsList = allModels.computeIfAbsent(carNumber, key -> new Int2ObjectAVLTreeMap<>()).computeIfAbsent(totalCars, key -> new ObjectArrayList<>());
			allModelsList.clear();
			allModelsList.addAll(models);

			if (extraModelsSupplier != null) {
				final long startMillis = System.currentTimeMillis();
				allModelsList.addAll(extraModelsSupplier.apply(carNumber, totalCars));
				final long endMillis = System.currentTimeMillis();
				if (endMillis - startMillis >= 100) {
					Init.LOGGER.warn("[{}] Model loading took {} ms, which is longer than usual!", id, endMillis - startMillis);
				}
			}

			final ObjectArrayList<VehicleModel> modelsToInitialize = new ObjectArrayList<>();
			modelsToInitialize.addAll(allModelsList);
			modelsToInitialize.addAll(bogie1Models);
			modelsToInitialize.addAll(bogie2Models);

			return new CachedResource<>(() -> {
				for (final VehicleModel vehicleModel : modelsToInitialize) {
					final DynamicVehicleModel dynamicVehicleModel = vehicleModel.cachedModel.getData(force);
					if (dynamicVehicleModel == null) {
						return null;
					}
				}

				final ObjectArrayList<Box> floors = new ObjectArrayList<>();
				final Object2ObjectOpenHashMap<PartCondition, ObjectArrayList<OptimizedModelWrapper.MaterialGroupWrapper>> materialGroupsModel = new Object2ObjectOpenHashMap<>();
				final Object2ObjectOpenHashMap<PartCondition, ObjectArrayList<OptimizedModelWrapper.MaterialGroupWrapper>> materialGroupsModelDoorsClosed = new Object2ObjectOpenHashMap<>();
				final Object2ObjectOpenHashMap<PartCondition, ObjectArrayList<OptimizedModelWrapper.MaterialGroupWrapper>> materialGroupsBogie1Model = new Object2ObjectOpenHashMap<>();
				final Object2ObjectOpenHashMap<PartCondition, ObjectArrayList<OptimizedModelWrapper.MaterialGroupWrapper>> materialGroupsBogie2Model = new Object2ObjectOpenHashMap<>();
				final Object2ObjectOpenHashMap<PartCondition, ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper>> objModelsModel = new Object2ObjectOpenHashMap<>();
				final Object2ObjectOpenHashMap<PartCondition, ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper>> objModelsModelDoorsClosed = new Object2ObjectOpenHashMap<>();
				final Object2ObjectOpenHashMap<PartCondition, ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper>> objModelsBogie1Model = new Object2ObjectOpenHashMap<>();
				final Object2ObjectOpenHashMap<PartCondition, ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper>> objModelsBogie2Model = new Object2ObjectOpenHashMap<>();

				final ObjectArrayList<Box> doorways = new ObjectArrayList<>();
				final long floorsStartNanos = System.nanoTime();
				forEachNonNull(allModelsList, dynamicVehicleModel -> dynamicVehicleModel.writeFloorsAndDoorways(floors, doorways, materialGroupsModel, materialGroupsModelDoorsClosed, objModelsModel, objModelsModelDoorsClosed), force);

				if (floors.isEmpty() && doorways.isEmpty()) {
					Init.LOGGER.info("[{}] No floors or doorways found in vehicle models", id);
					final double x1 = width / 2 + 0.25;
					final double x2 = width / 2 + 0.5;
					final double y = 1 + legacyRiderOffset;
					final double z = length / 2 - 0.5;
					floors.add(new Box(-x1, y, -z, x1, y, z));
					for (double j = -z; j <= z + 0.001; j++) {
						doorways.add(new Box(-x1, y, j, -x2, y, j + 1));
						doorways.add(new Box(x1, y, j, x2, y, j + 1));
					}
				}

				forEachNonNull(allModelsList, dynamicVehicleModel -> dynamicVehicleModel.modelProperties.iterateParts(modelPropertiesPart -> modelPropertiesPart.mapDoors(doorways)), force);
				forEachNonNull(bogie1Models, dynamicVehicleModel -> dynamicVehicleModel.writeFloorsAndDoorways(new ObjectArrayList<>(), new ObjectArrayList<>(), new Object2ObjectOpenHashMap<>(), materialGroupsBogie1Model, new Object2ObjectOpenHashMap<>(), objModelsBogie1Model), force);
				forEachNonNull(bogie2Models, dynamicVehicleModel -> dynamicVehicleModel.writeFloorsAndDoorways(new ObjectArrayList<>(), new ObjectArrayList<>(), new Object2ObjectOpenHashMap<>(), materialGroupsBogie2Model, new Object2ObjectOpenHashMap<>(), objModelsBogie2Model), force);
				MmtrVehicleRebuildProbe.report("地面/门洞/mapDoors", id, floorsStartNanos);

				return new CachedResource<>(() -> {
					final CachedResource<Object2ObjectOpenHashMap<PartCondition, OptimizedModelWrapper>> optimizedModels = writeToOptimizedModels(materialGroupsModel, objModelsModel, modelLifespan, id + "/开门");
					final CachedResource<Object2ObjectOpenHashMap<PartCondition, OptimizedModelWrapper>> optimizedModelsDoorsClosed = writeToOptimizedModels(materialGroupsModelDoorsClosed, objModelsModelDoorsClosed, modelLifespan, id + "/关门");
					final CachedResource<Object2ObjectOpenHashMap<PartCondition, OptimizedModelWrapper>> optimizedModelsBogie1 = writeToOptimizedModels(materialGroupsBogie1Model, objModelsBogie1Model, modelLifespan, id + "/转向架1");
					final CachedResource<Object2ObjectOpenHashMap<PartCondition, OptimizedModelWrapper>> optimizedModelsBogie2 = writeToOptimizedModels(materialGroupsBogie2Model, objModelsBogie2Model, modelLifespan, id + "/转向架2");
					return new VehicleResourceCacheHolder(new ObjectImmutableList<>(floors), new ObjectImmutableList<>(doorways), optimizedModels, optimizedModelsDoorsClosed, optimizedModelsBogie1, optimizedModelsBogie2);
				}, modelLifespan);
			}, modelLifespan);
		}, modelLifespan);
	}

	private Supplier<VehicleSoundBase> createVehicleSoundBaseInitializer() {
		/*
		 * 三条路，按"音效集自己怎么声明"来选，而不是靠车辆配置里再加一个开关位：
		 *   · 目录里有 mmtr_traction.json ⇒ MMTR 牵引音（离线烘焙 27 档 + 在线挑档变调）；
		 *   · 有 bveSoundBaseResource     ⇒ MTR 原来的 BVE 引擎（现存的 27 套素材走这条）；
		 *   · 都没有                      ⇒ legacy。
		 * 清单在、但解析坏了时 load() 会写一条 error 日志并返回 null —— 于是**退回 BVE/legacy**，
		 * 不会静默无声（现场至少还有别的音）。
		 */
		final MmtrTractionSoundSet mmtrSoundSet = MmtrTractionSoundSet.load(bveSoundBaseResource);
		if (mmtrSoundSet != null) {
			return () -> new MmtrVehicleSound(mmtrSoundSet);
		}
		if (bveSoundBaseResource.isEmpty()) {
			final LegacyVehicleSound legacyVehicleSound = new LegacyVehicleSound(
					legacySpeedSoundBaseResource,
					(int) legacySpeedSoundCount,
					legacyUseAccelerationSoundsWhenCoasting,
					legacyConstantPlaybackSpeed,
					legacyDoorSoundBaseResource,
					legacyDoorCloseSoundTime
			);
			return () -> legacyVehicleSound;
		} else {
			final BveVehicleSoundConfig bveVehicleSoundConfig = new BveVehicleSoundConfig(bveSoundBaseResource);
			return () -> new BveVehicleSound(bveVehicleSoundConfig);
		}
	}

	private static void iterateModels(ObjectArrayList<VehicleModel> models, ModelConsumer modelConsumer) {
		for (int i = 0; i < models.size(); i++) {
			final VehicleModel vehicleModel = models.get(i);
			if (vehicleModel != null) {
				final DynamicVehicleModel dynamicVehicleModel = vehicleModel.cachedModel.getData(false);
				if (dynamicVehicleModel != null) {
					modelConsumer.accept(i, dynamicVehicleModel);
				}
			}
		}
	}

	private static boolean getChristmasLightState(PartCondition partCondition) {
		final int index;
		switch (partCondition) {
			case CHRISTMAS_LIGHT_RED:
				index = 0;
				break;
			case CHRISTMAS_LIGHT_YELLOW:
				index = 1;
				break;
			case CHRISTMAS_LIGHT_GREEN:
				index = 2;
				break;
			case CHRISTMAS_LIGHT_BLUE:
				index = 3;
				break;
			default:
				return true;
		}
		return CHRISTMAS_LIGHT_STAGES[(int) ((System.currentTimeMillis() / 500) % CHRISTMAS_LIGHT_STAGES.length)][index];
	}

	private static void queue(Object2ObjectOpenHashMap<PartCondition, OptimizedModelWrapper> optimizedModels, StoredMatrixTransformations storedMatrixTransformations, VehicleExtension vehicle, int light, boolean noOpenDoorways, int lampColor, int totalCars) {
		/*
		 * notes/400 判定性埋点：把「这一帧这一辆车排队了几次 draw」直接量出来。
		 *
		 * 光看 [MMTR-VEHMERGE] 只能知道**构建时**合并成没成；渲染时用的是哪一个模型、匹配到了几个
		 * PartCondition，只有在这里才看得见。条件集合用位掩码累加（热路径上不拼字符串），
		 * 由 MmtrVehicleDrawProbe 每 5 秒出一行有界的报告。
		 */
		final boolean probe = MmtrVehicleDrawProbe.isEnabled();
		int conditionMask = 0;
		int matchingConditions = 0;
		int parts = 0;
		if (probe) {
			for (final PartCondition partCondition : PartCondition.values()) {
				if (matchesCondition(vehicle, partCondition, noOpenDoorways)) {
					/*
					 * CHRISTMAS_LIGHT_* 的判据是 System.currentTimeMillis()/500 的相位，**每 500 ms 翻一次**
					 * ⇒ 它进掩码的话，桶键与签名都会跟着抖，读出来全是"只差一位"的碎片，还会把真正
					 * 有信息的那几个桶挤出 MAX_SIGNATURES（notes/400 §4.2）。这几档在本 mod 的资源包里
					 * 没有几何，所以**只计数、不进掩码**。代价：真用圣诞灯的资源包在这条埋点里看不出来。
					 */
					if (!partCondition.name().startsWith("CHRISTMAS_LIGHT")) {
						conditionMask |= 1 << partCondition.ordinal();
					}
					final OptimizedModelWrapper wrapper = optimizedModels.get(partCondition);
					if (wrapper != null) {
						matchingConditions++;
						final int wrapperParts = wrapper.partCount();
						parts = parts < 0 || wrapperParts < 0 ? -1 : parts + wrapperParts;
					}
				}
			}
		}

		optimizedModels.forEach((partCondition, optimizedModel) -> {
			if (matchesCondition(vehicle, partCondition, noOpenDoorways)) {
				// 只有灯罩那一组几何吃 lampColor，其余部件一律不染（notes/374）。
				final int color = partCondition == PartCondition.MMTR_LAMP ? lampColor : ARGB_WHITE;
				MainRenderer.scheduleRender(QueuedRenderLayer.TEXT, (graphicsHolder, offset) -> {
					storedMatrixTransformations.transform(graphicsHolder, offset);
					CustomResourceLoader.OPTIMIZED_RENDERER_WRAPPER.queue(optimizedModel, graphicsHolder, color, light);
					graphicsHolder.pop();
				});
			}
		});

		if (probe) {
			MmtrVehicleDrawProbe.onCarQueued(conditionMask, matchingConditions, parts, totalCars, noOpenDoorways);
		}
	}

	/**
	 * 把一个 bundle 变成"按材质合桶的 {@code OptimizedModel}"。
	 *
	 * <h2>两段式（notes/400 §8）</h2>
	 *
	 * <p>映射库的 {@code fromObjModels}/{@code merge} 是**就地**对每组几何跑
	 * {@code generateNormals() + distinct()} 的，而 {@code distinct()} 会
	 * {@code vertices.clear(); addAll(去重结果)} —— 读的人会看到半个列表。所以这一段只能留在渲染线程，
	 * 也就成了 notes/400 §7 之后剩下那几十毫秒的主体。现在拆成两段：</p>
	 *
	 * <ul>
	 *   <li><b>后台</b>（{@link MmtrVehicleMeshMerger#bake}，纯 CPU、只改副本）：复制 → 法线 → 去重 → 合桶；</li>
	 *   <li><b>渲染线程</b>（{@link MmtrVehicleMeshMerger#upload}）：只做 {@code upload()} 建 GL buffer。</li>
	 * </ul>
	 *
	 * <p>后台没算完就让这一轮返回 {@code null} —— {@code CachedResource} 与
	 * {@code getCachedVehicleResource} 那条链本来就用 {@code null} 表达"未就绪"，
	 * 所以车厢只是晚一两个 tick 出现，而不是把渲染线程按住几十毫秒。</p>
	 *
	 * <h2>为什么先轮询完再碰 GL</h2>
	 *
	 * <p>第一阶段一个 GL 调用都没有。只要有一份后台还没好就整体 {@code return null}，
	 * 绝不出现"这份 upload 了、那份没有"的半成品 —— 半成品意味着已经建好的 GL buffer 被丢掉（漏显存）。</p>
	 *
	 * <h2>回退纪律</h2>
	 *
	 * <p>后台烘焙抛异常（映射库变了、内存不够）⇒ <b>回退到渲染线程上同步算</b>，并且这一份以后都走同步，
	 * 画面完全不受影响；开关 {@code -Dmmtr.meshbakeasync=false} 也是一样的路。</p>
	 */
	private static CachedResource<Object2ObjectOpenHashMap<PartCondition, OptimizedModelWrapper>> writeToOptimizedModels(
			Object2ObjectOpenHashMap<PartCondition, ObjectArrayList<OptimizedModelWrapper.MaterialGroupWrapper>> materialGroupsModel,
			Object2ObjectOpenHashMap<PartCondition, ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper>> objModelsModel,
			int modelLifespan,
			String label
	) {
		/*
		 * 只有"这条路真能走"时才造后台作业：没开优化渲染时一条 GL 都不该碰，
		 * 映射库字段取不到时 bake 会返回 null（那就没省下任何东西，白开线程）。
		 */
		final boolean async = MmtrVehicleMeshMerger.isBakeAsyncEnabled() && MmtrVehicleMeshMerger.canBake() && OptimizedRenderer.hasOptimizedRendering();
		final Object2ObjectOpenHashMap<PartCondition, MmtrAsyncModelParse<MmtrVehicleMeshMerger.Baked>> bakes = new Object2ObjectOpenHashMap<>();
		if (async) {
			for (final PartCondition partCondition : PartCondition.values()) {
				final ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper> objModels = objModelsModel.get(partCondition);
				if (objModels != null && !objModels.isEmpty()) {
					final MmtrAsyncModelParse<MmtrVehicleMeshMerger.Baked> bake = new MmtrAsyncModelParse<>("后台烘焙 " + label + ":" + partCondition, () -> MmtrVehicleMeshMerger.bake(objModels));
					bakes.put(partCondition, bake);
					// 早点开工：反正下一轮才会来问结果，这一轮先把活交出去。
					bake.start();
				}
			}
		}

		return new CachedResource<>(() -> {
			final long waitStartNanos = System.nanoTime();
			final Object2ObjectOpenHashMap<PartCondition, MmtrVehicleMeshMerger.Baked> baked = new Object2ObjectOpenHashMap<>();
			if (!bakes.isEmpty()) {
				boolean failed = false;
				for (final Map.Entry<PartCondition, MmtrAsyncModelParse<MmtrVehicleMeshMerger.Baked>> entry : bakes.entrySet()) {
					final MmtrVehicleMeshMerger.Baked result;
					try {
						result = entry.getValue().poll();
					} catch (Throwable throwable) {
						// 后台失败 ⇒ **同步兜底**：这一轮就在渲染线程上把几何算完，画面照常（绝不静默）。
						Init.LOGGER.warn("[MMTR-MESH] {} 后台烘焙失败 —— 回退到渲染线程上同步烘焙", label, throwable);
						failed = true;
						break;
					}
					if (result == null) {
						// 还没好：这一轮按"模型未就绪"处理（那条链本来就用 null 表达未就绪），下一轮再问。
						return null;
					}
					baked.put(entry.getKey(), result);
				}
				if (failed) {
					bakes.clear();
				}
			}
			final long waitMillis = (System.nanoTime() - waitStartNanos) / 1_000_000L;

			final long uploadStartNanos = System.nanoTime();
			CustomResourceLoader.OPTIMIZED_RENDERER_WRAPPER.beginReload();
			/*
			 * try/finally 是必须的（notes/399）。
			 *
			 * 原来这里是裸的一对 beginReload/finishReload：一旦循环里抛异常（坏资源、缺 group、
			 * 映射库的变化），finishReload 就永远不执行 —— GlStateTracker 的保护位会**永久留着**，
			 * 而保护区又是个布尔量，后果是 GL 状态再也回不到调用前的绑定。
			 * VehicleModel.createModel 那边本来就有 try/finally，这里补齐。
			 */
			try {
				final Object2ObjectOpenHashMap<PartCondition, OptimizedModelWrapper> optimizedModels = new Object2ObjectOpenHashMap<>();

				for (final PartCondition partCondition : PartCondition.values()) {
					final OptimizedModelWrapper optimizedModel1;
					final ObjectArrayList<OptimizedModelWrapper.MaterialGroupWrapper> materialGroups = materialGroupsModel.get(partCondition);
					optimizedModel1 = materialGroups == null ? null : OptimizedModelWrapper.fromMaterialGroups(materialGroups);

					final ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper> objModels = objModelsModel.get(partCondition);
					final MmtrVehicleMeshMerger.Baked bakedForCondition = baked.get(partCondition);
					/*
					 * 有后台结果就直接 upload 它（几何已经在别的线程上归一化 + 合桶好了）；
					 * 否则走老路：
					 *   - 默认（notes/399）按材质合并后 upload（一个材质一次 draw）；
					 *   - -Dmmtr.vehiclemerge=false 时逐部件 upload（一个部件一次 draw）。
					 *
					 * 只碰这两个 bundle（开门用的非门部件 / 关门用的车体+关门后的门）——
					 * 门与雨刷走的是 ModelPropertiesPart 里另一条 optimizedModelDoor 的路，不在这里。
					 */
					final OptimizedModelWrapper optimizedModel2;
					if (bakedForCondition != null) {
						final OptimizedModelWrapper uploaded = MmtrVehicleMeshMerger.upload(bakedForCondition);
						optimizedModel2 = uploaded != null ? uploaded : OptimizedModelWrapper.fromObjModels(objModels);
					} else {
						optimizedModel2 = objModels == null ? null
								: MmtrVehicleMeshMerger.isEnabled()
										? MmtrVehicleMeshMerger.mergeOrFallback(objModels)
										: OptimizedModelWrapper.fromObjModels(objModels);
					}
					optimizedModels.put(partCondition, new OptimizedModelWrapper(optimizedModel1, optimizedModel2));
				}

				return optimizedModels;
			} finally {
				CustomResourceLoader.OPTIMIZED_RENDERER_WRAPPER.finishReload();
				MmtrVehicleRebuildProbe.reportUpload(label, waitMillis, (System.nanoTime() - uploadStartNanos) / 1_000_000L);
			}
		}, modelLifespan);
	}

	private static void forEachNonNull(ObjectArrayList<VehicleModel> models, Consumer<DynamicVehicleModel> consumer, boolean force) {
		models.forEach(vehicleModel -> {
			final DynamicVehicleModel dynamicVehicleModel = vehicleModel.cachedModel.getData(force);
			if (dynamicVehicleModel != null) {
				consumer.accept(dynamicVehicleModel);
			}
		});
	}

	private static class VehicleResourceCacheHolder {

		private final ObjectImmutableList<Box> floors;
		private final ObjectImmutableList<Box> doorways;
		private final CachedResource<Object2ObjectOpenHashMap<PartCondition, OptimizedModelWrapper>> optimizedModels;
		private final CachedResource<Object2ObjectOpenHashMap<PartCondition, OptimizedModelWrapper>> optimizedModelsDoorsClosed;
		private final CachedResource<Object2ObjectOpenHashMap<PartCondition, OptimizedModelWrapper>> optimizedModelsBogie1;
		private final CachedResource<Object2ObjectOpenHashMap<PartCondition, OptimizedModelWrapper>> optimizedModelsBogie2;

		private VehicleResourceCacheHolder(
				ObjectImmutableList<Box> floors, ObjectImmutableList<Box> doorways,
				CachedResource<Object2ObjectOpenHashMap<PartCondition, OptimizedModelWrapper>> optimizedModels,
				CachedResource<Object2ObjectOpenHashMap<PartCondition, OptimizedModelWrapper>> optimizedModelsDoorsClosed,
				CachedResource<Object2ObjectOpenHashMap<PartCondition, OptimizedModelWrapper>> optimizedModelsBogie1,
				CachedResource<Object2ObjectOpenHashMap<PartCondition, OptimizedModelWrapper>> optimizedModelsBogie2
		) {
			this.floors = floors;
			this.doorways = doorways;
			this.optimizedModels = optimizedModels;
			this.optimizedModelsDoorsClosed = optimizedModelsDoorsClosed;
			this.optimizedModelsBogie1 = optimizedModelsBogie1;
			this.optimizedModelsBogie2 = optimizedModelsBogie2;
		}
	}

	@FunctionalInterface
	public interface ModelConsumer {
		void accept(int index, DynamicVehicleModel dynamicVehicleModel);
	}

	@FunctionalInterface
	public interface LegacyVehicleSupplier<T> {
		T apply(int carNumber, int totalCars);
	}
}
