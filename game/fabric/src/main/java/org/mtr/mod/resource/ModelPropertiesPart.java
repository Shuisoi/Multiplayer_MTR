package org.mtr.mod.resource;

import org.mtr.core.data.Data;
import org.mtr.core.data.Vehicle;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.tool.EnumHelper;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.*;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.*;
import org.mtr.mod.Init;
import org.mtr.mod.MutableBox;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.client.DynamicTextureCache;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MmtrVehicleAnchors;
import org.mtr.mod.client.ScrollingText;
import org.mtr.mod.data.IGui;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.generated.resource.ModelPropertiesPartSchema;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.MmtrDoorSides;
import org.mtr.mod.render.MmtrVehicleDrawProbe;
import org.mtr.mod.render.light.MmtrHeadlights;
import org.mtr.mod.render.panel.MmtrWindshield;
import org.mtr.mod.render.QueuedRenderLayer;
import org.mtr.mod.render.StoredMatrixTransformations;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Supplier;

public final class ModelPropertiesPart extends ModelPropertiesPartSchema implements IGui {

	/** 门模型惰性化（notes/401 §11.3）：默认开；{@code -Dmmtr.lazydoor=false} 回到改动前的"构造时就建好"。 */
	private static final boolean LAZY_DOOR_MODEL = Boolean.parseBoolean(System.getProperty("mmtr.lazydoor", "true"));

	private final ObjectArrayList<PartDetails> partDetailsList = new ObjectArrayList<>();
	private final ObjectArrayList<DisplayPartDetails> displayPartDetailsList = new ObjectArrayList<>();
	private final int displayColorCjkInt;
	private final int displayColorInt;

	/**
	 * A 路线（notes/400 §6）：这一组门的几何，交给 {@link MmtrDoorBatch} 按动画类合并。
	 * {@code null} = 不走合并（不是门、是雨刷、不是 OBJ 路、或者位置数 &gt; 1）。
	 */
	@Nullable
	private ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper> doorBatchObjModels;

	private static final int LINE_PADDING = 2;

	public ModelPropertiesPart(ReaderBase readerBase) {
		super(readerBase);
		updateData(readerBase);
		displayColorInt = parseColor(displayColor, 0xFF9900);
		displayColorCjkInt = parseColor(displayColorCjk, displayColorInt);
	}

	ModelPropertiesPart(ObjectSet<String> names) {
		super(PartCondition.NORMAL, RenderStage.EXTERIOR, PartType.NORMAL, 0, 0, "", "", 0, 0, 0, DisplayType.DESTINATION, "", 0, 0, DoorAnimationType.STANDARD, 0, 0, 0, 0, 0, 0);
		this.names.addAll(names);
		positionDefinitions.add("");
		displayColorInt = 0;
		displayColorCjkInt = 0;
	}

	ModelPropertiesPart(
			ObjectArrayList<String> names,
			ObjectArrayList<String> positionDefinitions,
			PartCondition condition,
			RenderStage renderStage,
			PartType type,
			double displayXPadding,
			double displayYPadding,
			String displayColorCjk,
			String displayColor,
			double displayMaxLineHeight,
			double displayCjkSizeRatio,
			ObjectArrayList<String> displayOptions,
			double displayPadZeros,
			DisplayType displayType,
			String displayDefaultText,
			double doorXMultiplier,
			double doorZMultiplier,
			DoorAnimationType doorAnimationType,
			long renderFromOpeningDoorTime,
			long renderUntilOpeningDoorTime,
			long renderFromClosingDoorTime,
			long renderUntilClosingDoorTime,
			long flashOnTime,
			long flashOffTime
	) {
		super(
				condition,
				renderStage,
				type,
				displayXPadding,
				displayYPadding,
				displayColorCjk,
				displayColor,
				displayMaxLineHeight,
				displayCjkSizeRatio,
				displayPadZeros,
				displayType,
				displayDefaultText,
				doorXMultiplier,
				doorZMultiplier,
				doorAnimationType,
				renderFromOpeningDoorTime,
				renderUntilOpeningDoorTime,
				renderFromClosingDoorTime,
				renderUntilClosingDoorTime,
				flashOnTime,
				flashOffTime
		);
		this.names.addAll(names);
		this.positionDefinitions.addAll(positionDefinitions);
		this.displayOptions.addAll(displayOptions);
		displayColorInt = parseColor(displayColor, 0xFF9900);
		displayColorCjkInt = parseColor(displayColorCjk, displayColorInt);
	}

	/**
	 * Maps each part name to the corresponding part and collects all floors, doors, and doorways for processing later.
	 * Writes to the collective vehicle model parts (one with doors, one without doors).
	 * If this part is a door, create an optimized model to be rendered later.
	 */
	public void writeCache(
			Identifier texture,
			Object2ObjectOpenHashMap<String, ObjectObjectImmutablePair<ModelPartExtension, MutableBox>> nameToPart,
			Object2ObjectOpenHashMap<String, ObjectArrayList<ModelDisplayPart>> nameToDisplayParts,
			PositionDefinitions positionDefinitionsObject,
			ObjectArraySet<Box> floors,
			ObjectArraySet<Box> doorways,
			Object2ObjectOpenHashMap<PartCondition, Object2ObjectOpenHashMap<RenderStage, OptimizedModelWrapper.MaterialGroupWrapper>> materialGroupsForPartConditionAndRenderStage,
			Object2ObjectOpenHashMap<PartCondition, Object2ObjectOpenHashMap<RenderStage, OptimizedModelWrapper.MaterialGroupWrapper>> materialGroupsForPartConditionAndRenderStageDoorsClosed
	) {
		final ObjectArrayList<ModelPartExtension> modelParts = new ObjectArrayList<>();
		final MutableBox mutableBox = new MutableBox();
		final ObjectArrayList<ObjectArrayList<ModelDisplayPart>> modelDisplayParts = new ObjectArrayList<>();
		final OptimizedModelWrapper optimizedModelDoor;

		names.forEach(name -> {
			final ObjectObjectImmutablePair<ModelPartExtension, MutableBox> part = nameToPart.get(name);
			if (part != null) {
				modelParts.add(part.left());
				mutableBox.add(part.right());
			}

			final ObjectArrayList<ModelDisplayPart> displayParts = nameToDisplayParts.get(name);
			if (displayParts != null) {
				modelDisplayParts.add(displayParts);
			}
		});

		if (isDoor()) {
			final OptimizedModelWrapper.MaterialGroupWrapper materialGroup = new OptimizedModelWrapper.MaterialGroupWrapper(renderStage.shaderType, texture);
			modelParts.forEach(modelPart -> materialGroup.addCube(modelPart, 0, 0, 0, false, MAX_LIGHT_INTERIOR));
			optimizedModelDoor = OptimizedModelWrapper.fromMaterialGroups(ObjectArrayList.of(materialGroup));
		} else {
			optimizedModelDoor = null;
		}

		positionDefinitions.forEach(positionDefinitionName -> positionDefinitionsObject.getPositionDefinition(positionDefinitionName, (positions, positionsFlipped) -> {
			switch (type) {
				case NORMAL:
					iteratePositions(positions, positionsFlipped, (x, y, z, flipped) -> {
						if (!isDoor()) {
							addCube(texture, modelParts, materialGroupsForPartConditionAndRenderStage, x, y, z, flipped);
						}
						addCube(texture, modelParts, materialGroupsForPartConditionAndRenderStageDoorsClosed, x, y, z, flipped);
						partDetailsList.add(new PartDetails(modelParts, optimizedModelDoor, null, addBox(mutableBox.get(), x, y, z, flipped), x, y, z, flipped));
					});
					break;
				case DISPLAY:
					iteratePositions(positions, positionsFlipped, (x, y, z, flipped) -> displayPartDetailsList.add(new DisplayPartDetails(modelDisplayParts, x, y, z, flipped)));
					break;
				case FLOOR:
					iteratePositions(positions, positionsFlipped, (x, y, z, flipped) -> mutableBox.getAll().forEach(box -> floors.add(addBox(box, x, y, z, flipped))));
					break;
				case DOORWAY:
					iteratePositions(positions, positionsFlipped, (x, y, z, flipped) -> mutableBox.getAll().forEach(box -> doorways.add(addBox(box, x, y, z, flipped))));
					break;
			}
		}));
	}

	public void writeCache(
			Map<String, OptimizedModel.ObjModel> nameToObjModels,
			PositionDefinitions positionDefinitionsObject,
			Object2ObjectOpenHashMap<PartCondition, Object2ObjectOpenHashMap<RenderStage, ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper>>> objModelsForPartConditionAndRenderStage,
			Object2ObjectOpenHashMap<PartCondition, Object2ObjectOpenHashMap<RenderStage, ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper>>> objModelsForPartConditionAndRenderStageDoorsClosed,
			double modelYOffset
	) {
		final ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper> objModels = new ObjectArrayList<>();
		final MutableBox mutableBox = new MutableBox();
		final Supplier<OptimizedModelWrapper> optimizedModelDoor;

		names.forEach(name -> {
			final OptimizedModel.ObjModel objModel = nameToObjModels.get(name);
			if (objModel != null) {
				objModels.add(new OptimizedModelWrapper.ObjModelWrapper(objModel));
				mutableBox.add(new Box(-objModel.getMinX(), -objModel.getMinY(), -objModel.getMinZ(), -objModel.getMaxX(), -objModel.getMaxY(), -objModel.getMaxZ()));
			}
		});

		// A mechanism part (wiper_/wiperarm_/wiperrod_) is moved per part by its own kinematics, so it needs
		// its own drawable wrapper exactly like a door does: on the OBJ path `modelParts` is ALWAYS empty and
		// the geometry otherwise goes into the main optimized batch, which is drawn elsewhere and therefore
		// cannot be rotated per part.
		//
		// It has to stay out of BOTH batches, not just the main one. The doors-closed list is not "doors
		// only": a vehicle draws its body from the doors-closed optimised model whenever the doors are shut
		// - i.e. normally - so a mechanism left in there is a SECOND, static copy that never moves. Parked,
		// the two copies sit on top of each other and look like one; the moment the wipers run, the moving
		// copy separates from the frozen one and the car appears to grow a second wiper. A door can live
		// with that because its frozen copy IS the closed door; a wiper has no such state.
		//
		// It still has to REGISTER its position, though - just not in a batch. An OBJ wrapper materialises
		// geometry only from the transformations recorded on the ObjModel (`addObjModelPosition` does both),
		// so skipping the call entirely builds an EMPTY wrapper and the wiper disappears altogether. A
		// scratch map is thrown away instead of being turned into a vehicle-level model.
		final boolean mechanism = isMechanism();
		/*
		 * 这一份**不要**在这里按材质合并（notes/400 §5 实测：零收益）。
		 *
		 * 它的粒度是"一个部件组的一个位置"，而实测每个门部件组的位置数就是 1 ⇒ 这里 upload 出来
		 * 永远只有 1 个材质 = 1 次 draw，`[MMTR-VDRAW]` 的 `每次排队 draw 数 avg=1.0 max=1` 就是证据。
		 * 真正的大头是"开门时按部件组逐条排队"：24 组门 × 每车 ⇒ 一辆车开门态多出 20+ 次 draw。
		 * 那个要**跨部件组**按动画类合并（MmtrDoorBatch），不是在这里。
		 */
		optimizedModelDoor = () -> isDoor() || mechanism ? OptimizedModelWrapper.fromObjModels(objModels) : null;
		/*
		 * 门模型惰性化（notes/401 §11.3）的**回退开关**：`-Dmmtr.lazydoor=false` 回到"构造时就建好"
		 * （改动前的行为：每次重建都把那 24 组门的模型建出来并上传）。
		 * 雨刷（mechanism）两条路都现建 —— 它每帧都画，推迟没有收益。
		 */
		final boolean lazyDoor = LAZY_DOOR_MODEL && !mechanism;

		positionDefinitions.forEach(positionDefinitionName -> positionDefinitionsObject.getPositionDefinition(positionDefinitionName, (positions, positionsFlipped) -> {
			if (type == PartType.NORMAL) {
				iteratePositions(positions, positionsFlipped, (x, y, z, flipped) -> {
					if (mechanism) {
						addObjModelPosition(objModels, new Object2ObjectOpenHashMap<>(), x, y, z, flipped, modelYOffset);
					} else {
						if (!isDoor()) {
							addObjModelPosition(objModels, objModelsForPartConditionAndRenderStage, x, y, z, flipped, modelYOffset);
						}
						addObjModelPosition(objModels, objModelsForPartConditionAndRenderStageDoorsClosed, x, y, z, flipped, modelYOffset);
					}
					/*
					 * 门那一次"自己的模型"**改成第一次真要用时才建**（notes/401 §11.2）。
					 *
					 * <p>读数：`[MMTR-VEHCTOR]` 实测一次车辆构造 97 ms 里有 **36 ms / 28 次** 花在这里
					 * （`fromObjModels` → generateNormals + distinct + **upload**，也就是 writeCache 里唯一的 GL）。
					 * 而门那 24 个包装**只在开门态才用得到**（`renderNormal` 的分支条件里带
					 * `!openDoorways.isEmpty()`；按动画类合并的 `MmtrDoorBatch` 用的是源 `ObjModel`，不用它）
					 * ⇒ 关门态（常见情形）那 24 次建 VBO 是白做的。</p>
					 *
					 * <p>雨刷（mechanism）**照旧现建**：它走 else 分支、**每帧都画**，推迟只是把同一笔钱挪到
					 * 第一帧，没有收益。</p>
					 */
					final long doorStartNanos = System.nanoTime();
					final OptimizedModelWrapper doorModel;
					final Supplier<OptimizedModelWrapper> lazyDoorSupplier;
					if (lazyDoor) {
						doorModel = null;
						lazyDoorSupplier = optimizedModelDoor;
					} else {
						doorModel = optimizedModelDoor.get();
						lazyDoorSupplier = null;
					}
					doorModelNanos += System.nanoTime() - doorStartNanos;
					if (doorModel != null && doorModel.partCount() != 0) {
						doorModelCalls++;
					}
					partDetailsList.add(new PartDetails(new ObjectArrayList<>(), doorModel, lazyDoorSupplier, addBox(mutableBox.get(), x, y, z, flipped), x, y, z, flipped));
				});
			}
		}));

		/*
		 * A 路线（notes/400 §6）：把这一组门的几何交给"按动画类合并"那条路。
		 *
		 * 只在 OBJ 路、只在门、只在一个位置时登记。位置数 > 1 时不登记（合并的 draw 只能有一个变换，
		 * 多位置的门得各画各的，理由见 notes/400 §6）。登记的是**已经烘进位置的**那批 ObjModel，
		 * 所以合并后的模型里每个门都已经站在自己的位置上，draw 时只需要一个共同的动画位移。
		 */
		doorBatchObjModels = isDoor() && !mechanism && type == PartType.NORMAL && partDetailsList.size() == 1 ? objModels : null;
	}

	public void render(Identifier texture, StoredMatrixTransformations storedMatrixTransformations, @Nullable VehicleExtension vehicle, int carNumber, int[] scrollingDisplayIndexTracker, int light, ObjectArrayList<ObjectDoubleImmutablePair<Box>> openDoorways, boolean fromResourcePackCreator, boolean doorBatched) {
		if (vehicle == null || VehicleResource.matchesCondition(vehicle, condition, openDoorways.isEmpty())) {
			switch (type) {
				case NORMAL:
					final ObjectIntImmutablePair<QueuedRenderLayer> renderProperties = getRenderProperties(renderStage, light, vehicle);
					if (OptimizedRenderer.hasOptimizedRendering()) {
						MainRenderer.scheduleRender(QueuedRenderLayer.TEXT, (graphicsHolder, offset) -> renderNormal(storedMatrixTransformations, vehicle, carNumber, renderProperties, openDoorways, light, graphicsHolder, offset, doorBatched));
					} else {
						MainRenderer.scheduleRender(texture, false, renderProperties.left(), (graphicsHolder, offset) -> renderNormal(storedMatrixTransformations, vehicle, carNumber, renderProperties, openDoorways, light, graphicsHolder, offset, doorBatched));
					}
					break;
				case DISPLAY:
					if (vehicle != null) {
						if (displayType == DisplayType.ROUTE_COLOR || displayType == DisplayType.ROUTE_COLOR_ROUNDED) {
							renderLineColor(storedMatrixTransformations, vehicle, fromResourcePackCreator);
						} else {
							if (displayOptions.contains(DisplayOption.SEVEN_SEGMENT.toString())) {
								renderSevenSegmentDisplay(storedMatrixTransformations, vehicle);
							} else if (displayOptions.contains(DisplayOption.SCROLL_NORMAL.toString()) || displayOptions.contains(DisplayOption.SCROLL_LIGHT_RAIL.toString())) {
								renderScrollingDisplay(storedMatrixTransformations, vehicle, carNumber, scrollingDisplayIndexTracker);
							} else {
								renderDisplay(storedMatrixTransformations, vehicle);
							}
						}
					}
					break;
			}
		}
	}

	public void getOpenDoorBounds(ObjectArrayList<Box> boxes, double time) {
		if (isDoor()) {
			partDetailsList.forEach(partDetails -> {
				final double x = doorAnimationType.getDoorAnimationX(doorXMultiplier, partDetails.flipped, time) / 16;
				final double z = doorAnimationType.getDoorAnimationZ(doorZMultiplier, partDetails.flipped, time, true) / 16;
				final Box box = partDetails.box;
				final float xOffset = box.getMinXMapped() == box.getMaxXMapped() ? 0.1F : 0;
				final float yOffset = box.getMinYMapped() == box.getMaxYMapped() ? 0.1F : 0;
				final float zOffset = box.getMinZMapped() == box.getMaxZMapped() ? 0.1F : 0;
				boxes.add(new Box(
						box.getMinXMapped() - xOffset + x,
						box.getMinYMapped() - yOffset,
						box.getMinZMapped() - zOffset + z,
						box.getMaxXMapped() + xOffset + x,
						box.getMaxYMapped() + yOffset,
						box.getMaxZMapped() + zOffset + z
				));
			});
		}
	}

	void addToModelPropertiesPartWrapperMap(PositionDefinitions actualPositionDefinitions, ObjectArrayList<ModelPropertiesPartWrapper> parts) {
		names.forEach(modelPartName -> positionDefinitions.forEach(positionDefinitionName -> actualPositionDefinitions.getPositionDefinition(positionDefinitionName, (positions, positionsFlipped) -> parts.add(new ModelPropertiesPartWrapper(
				new PositionDefinition(modelPartName, positions, positionsFlipped),
				condition,
				renderStage,
				type,
				displayXPadding,
				displayYPadding,
				displayColorCjk,
				displayColor,
				displayMaxLineHeight,
				displayCjkSizeRatio,
				displayOptions,
				displayPadZeros,
				displayType,
				displayDefaultText,
				doorXMultiplier,
				doorZMultiplier,
				doorAnimationType,
				renderFromOpeningDoorTime,
				renderUntilOpeningDoorTime,
				renderFromClosingDoorTime,
				renderUntilClosingDoorTime,
				flashOnTime,
				flashOffTime
		)))));
	}

	/**
	 * If this part is a door, find the closest doorway.
	 */
	void mapDoors(ObjectArrayList<Box> doorways) {
		if (isDoor()) {
			partDetailsList.forEach(partDetails -> doorways.stream().min(Comparator.comparingDouble(checkDoorway -> getClosestDistance(
					partDetails.box.getMinXMapped(),
					partDetails.box.getMaxXMapped(),
					checkDoorway.getMinXMapped(),
					checkDoorway.getMaxXMapped()
			) + getClosestDistance(
					partDetails.box.getMinYMapped(),
					partDetails.box.getMaxYMapped(),
					checkDoorway.getMinYMapped(),
					checkDoorway.getMaxYMapped()
			) + getClosestDistance(
					partDetails.box.getMinZMapped(),
					partDetails.box.getMaxZMapped(),
					checkDoorway.getMinZMapped(),
					checkDoorway.getMaxZMapped()
			))).ifPresent(closestDoorway -> partDetails.doorway = closestDoorway));
		}
	}

	/**
	 * 开门态下这个门部件该被平移到哪里（单位 1/16 格），以及它这一帧到底画不画。
	 *
	 * <p>从 {@code renderNormal} 里原样抽出来的（notes/400 §6）—— <b>只有这一份</b>：
	 * 逐部件的路（{@code renderNormal}）和按动画类合并的路（{@link MmtrDoorBatch}）都必须用同一个
	 * 判据，否则"合并后门的位置和现在不一样"。抽的时候没有改任何一行算式。</p>
	 *
	 * @return {@code {x, y, z}}；{@code null} = 这一帧不该画（原来是用 {@code Integer.MAX_VALUE}
	 *         把它挪到天边去，这里让调用方自己决定怎么表达"不画"）
	 */
	@Nullable
	private float[] doorDrawTranslation(VehicleExtension vehicle, ObjectArrayList<ObjectDoubleImmutablePair<Box>> openDoorways, PartDetails partDetails) {
		final boolean flashOn = flashOnTime + flashOffTime == 0 || (System.currentTimeMillis() % (flashOnTime + flashOffTime)) > flashOffTime;
		double doorOverrideValue = 0;
		boolean canOpen = false;
		for (final ObjectDoubleImmutablePair<Box> openDoorway : openDoorways) {
			if (openDoorway.left().equals(partDetails.doorway)) {
				doorOverrideValue = openDoorway.rightDouble();
				canOpen = true;
				break;
			}
		}

		// MMTR B7.6h: when the cab crew works the doors by hand, the side they commanded decides
		// - MTR's own rule (a doorway only opens next to a platform block) would leave the doors
		// shut on a train standing in a siding, which is exactly what "the HUD says open but
		// nothing moves" was.
		if (isDoor() && vehicle.vehicleExtraData.isMmtrDoorManual()) {
			canOpen = MmtrDoorSides.isOpenSide(vehicle, partDetails.box);
		}

		final double doorValue = canOpen ? vehicle.persistentVehicleData.getDoorValue() : 0;
		final boolean opening = vehicle.persistentVehicleData.getAdjustedDoorMultiplier(vehicle.vehicleExtraData) > 0;
		final boolean shouldRender;

		if (opening) {
			shouldRender = renderFromOpeningDoorTime == 0 && renderUntilOpeningDoorTime == 0 || Utilities.isBetween(Math.abs(doorValue) * Vehicle.DOOR_MOVE_TIME, renderFromOpeningDoorTime, renderUntilOpeningDoorTime);
		} else {
			shouldRender = renderFromClosingDoorTime == 0 && renderUntilClosingDoorTime == 0 || Utilities.isBetween(Math.abs(doorValue) * Vehicle.DOOR_MOVE_TIME, renderFromClosingDoorTime, renderUntilClosingDoorTime);
		}

		if (!shouldRender) {
			return null;
		}

		final float x = (float) (partDetails.x + doorAnimationType.getDoorAnimationX(doorXMultiplier, partDetails.flipped, Math.max(doorValue, doorOverrideValue)));
		final float y = flashOn ? (float) partDetails.y : Integer.MAX_VALUE;
		final float z = (float) (partDetails.z + (canOpen ? vehicle.persistentVehicleData.getInterpolatedDoorValue(doorAnimationType, doorZMultiplier, partDetails.flipped, doorOverrideValue, opening) : 0));
		return new float[]{x, y, z};
	}

	private boolean isDoor() {
		return doorXMultiplier != 0 || doorZMultiplier != 0;
	}

	/**
	 * A 路线（notes/400 §6）：这一组门的**动画类**。同一个类的门在同一帧里的 draw 位移必然相同，
	 * 所以可以合成一次 draw。
	 *
	 * <p>类里必须包含所有"会影响这一组门怎么被画出来"的部件级参数，一个都不能漏：</p>
	 * <ul>
	 *   <li>两个 multiplier —— 它们直接进动画函数，决定位移的方向与大小；</li>
	 *   <li>{@link DoorAnimationType} —— 同一个 multiplier 下不同动画曲线的位移也不同；</li>
	 *   <li>{@code flipped} —— 它决定 draw 时那一次 180° Y 旋转，不能和没转的合并；</li>
	 *   <li>{@link PartCondition} —— 不同条件的部件在 {@code render()} 里被**分别**判定要不要画，
	 *       合并就把条件判定的粒度也合并了；</li>
	 *   <li><b>哪一侧</b> —— 这一条是实机逼出来的：资源包里 `door_r_1` 与 `door_l_1` 的
	 *       {@code doorZMultiplier} **完全相同**（都是 −14，见 `properties_saf420car.json`，
	 *       左右门是同一套动画），所以只按 multiplier 分组会把左右门塞进一个类 ——
	 *       而 `canOpen` 是**逐 doorway** 判的（{@code RenderVehicleHelper.canOpenDoors}
	 *       看那一扇门口有没有站台），一站台只在一侧时左右就不一致，整个类永远合不上。</li>
	 * </ul>
	 *
	 * <p>侧别取"这一组门映射到的那个 doorway 的中线 x 符号"，取不到就用部件自己的盒子。
	 * 这也是 {@code canOpen} 真正的判据来源。**粒度只能到"侧"**：再细到 doorway 就变成
	 * 一组门一个类，一组的 draw 数本来就只有 1，合并反而更贵。</p>
	 *
	 * @return {@code null} = 这一组不参与合并
	 */
	@Nullable
	String doorBatchKey() {
		if (doorBatchObjModels == null || partDetailsList.size() != 1) {
			return null;
		}
		final PartDetails partDetails = partDetailsList.get(0);
		return doorXMultiplier + "|" + doorZMultiplier + "|" + doorAnimationType.name() + "|" + partDetails.flipped + "|" + condition.name() + "|" + doorBatchSide();
	}

	/**
	 * "这一组门在哪一侧"。
	 *
	 * <p>取的是**车厢局部坐标**里盒子的中线符号（{@code writeFloorsAndDoorways} 交出去的 doorway
	 * 与部件盒子同在这一套坐标里，`canOpenDoors` 也是把它们变换到世界坐标的）。优先用 doorway：
	 * 那一扇门口就是 {@code canOpen} 的来源；没有 doorway（模型没声明 {@code DOORWAY} 部件、
	 * 或者这一组还没被 {@code mapDoors} 认领）就退回部件自己的盒子。</p>
	 */
	private int doorBatchSide() {
		final PartDetails partDetails = partDetailsList.get(0);
		return sideOf(partDetails.doorway) * 2 + sideOf(partDetails.box);
	}

	private static int sideOf(@Nullable Box box) {
		return box == null || box.getMinXMapped() + box.getMaxXMapped() >= 0 ? 1 : 0;
	}

	/** @return 这一组门的几何（已按位置烘好）；{@code null} = 不参与合并 */
	@Nullable
	ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper> doorBatchGeometry() {
		return doorBatchObjModels;
	}

	/** @return draw 时是否要施加那一次 180° Y 旋转（与 {@link #doorBatchKey()} 里的 flipped 同源） */
	boolean doorBatchFlipped() {
		return !partDetailsList.isEmpty() && partDetailsList.get(0).flipped;
	}

	/** @return 这一组门的 group 名（"为什么没合并"的日志里要用它指认是哪一组） */
	String doorBatchNames() {
		return names.toString();
	}

	/**
	 * @return 这一组门在这一帧的 draw 位移（单位 1/16 格）；{@code null} = 这一帧不画，或者
	 *         不参与合并（位置数 &gt; 1）。**与 {@code renderNormal} 用的是同一个算式。**
	 */
	@Nullable
	float[] doorBatchTranslation(VehicleExtension vehicle, ObjectArrayList<ObjectDoubleImmutablePair<Box>> openDoorways) {
		if (doorBatchObjModels == null || partDetailsList.size() != 1) {
			return null;
		}
		/*
		 * 条件必须先判（notes/400 §6）：`render()` 是先过 `matchesCondition` 才排队的，条件不成立的部件
		 * 这一帧根本不画。合并模型里却带着它的几何 ⇒ 不判条件就会**多画**（例如 AT_DEPOT 的门在途时
		 * 被合并模型画出来）。类的键里含 condition，所以同一个类要么全中、要么全不中，判一次就够。
		 */
		if (!VehicleResource.matchesCondition(vehicle, condition, openDoorways.isEmpty())) {
			return null;
		}
		return doorDrawTranslation(vehicle, openDoorways, partDetailsList.get(0));
	}
	/**
	 * @return whether this part is a wiper mechanism piece (wiper_/wiperarm_/wiperrod_). Such a part is
	 *         moved by rotating the PART, not by the door animation, so on the OBJ path it needs its own
	 *         drawable wrapper the same way a door does - see the OBJ writeCache.
	 */
	private boolean isMechanism() {
		for (final String name : names) {
			if (name != null && name.startsWith("wiper")) {
				return true;
			}
		}
		return false;
	}

	/** The model's RESOURCE id for one car of a consist - what the anchors and wiper states are keyed by. */
	private static String vehicleIdFor(VehicleExtension vehicle, int carNumber) {
		return vehicle.mmtrCarResourceId(carNumber);
	}

	private void renderNormal(StoredMatrixTransformations storedMatrixTransformations, @Nullable VehicleExtension vehicle, int carNumber, ObjectIntImmutablePair<QueuedRenderLayer> renderProperties, ObjectArrayList<ObjectDoubleImmutablePair<Box>> openDoorways, int light, GraphicsHolder graphicsHolder, Vector3d offset, boolean doorBatched) {
		storedMatrixTransformations.transform(graphicsHolder, offset);
		final boolean flashOn = flashOnTime + flashOffTime == 0 || (System.currentTimeMillis() % (flashOnTime + flashOffTime)) > flashOffTime;
		/*
		 * A 路线（notes/400 §6）：这一组门的几何已经由 MmtrDoorBatch 用**一个动画类一份合并模型**
		 * 画过了，这里就不能再画第二遍 —— 那会是一模一样的重影（同一个位移、同一批顶点）。
		 * 只有"开门态"那条分支会被跳过；雨刷走的是下面 else 分支，不受影响。
		 */
		final boolean skipOptimizedDoor = doorBatched && isDoor();
		partDetailsList.forEach(partDetails -> {
			final float x;
			final float y = flashOn ? (float) partDetails.y : Integer.MAX_VALUE;
			final float z;

			if (vehicle == null) {
				x = (float) partDetails.x;
				z = (float) partDetails.z;
			} else {
				final float[] doorTranslation = doorDrawTranslation(vehicle, openDoorways, partDetails);
				x = doorTranslation == null ? Integer.MAX_VALUE : doorTranslation[0];
				z = doorTranslation == null ? Integer.MAX_VALUE : doorTranslation[2];
			}

			// The mechanism parts (wiper_ / wiperarm_ / wiperrod_) are moved per part by their own kinematics,
			// so they must NOT be taken by the optimized branch: that branch only re-draws an
			// `optimizedModelDoor` and therefore draws NOTHING for a non-door part. The whole W4 rotation
			// lived in the else below, so with the optimized renderer on (the default) it never ran - which
			// is exactly "the wiper does not move no matter what the stalk says".
			boolean isWiperPart = false;
			for (final String candidate : names) {
				if (candidate != null && candidate.startsWith("wiper")) {
					isWiperPart = true;
					break;
				}
			}

			if (OptimizedRenderer.hasOptimizedRendering() && !isWiperPart) {
				// If doors are open, only render the optimized door parts
				// Otherwise, the main model already includes closed doors
				if (!skipOptimizedDoor && !openDoorways.isEmpty() && partDetails.hasDoorModel()) {
					// 惰性：开门态第一次走到这里才真的建（关门态永远不建 —— notes/401 §11.2）
					final OptimizedModelWrapper doorModel = partDetails.doorModel();
					if (doorModel != null) {
						graphicsHolder.push();
						graphicsHolder.translate(x / 16, y / 16, z / 16);
						if (partDetails.flipped) {
							graphicsHolder.rotateYDegrees(180);
						}
						CustomResourceLoader.OPTIMIZED_RENDERER_WRAPPER.queue(doorModel, graphicsHolder, light);
						MmtrVehicleDrawProbe.onDoorQueued(doorModel.partCount());
						graphicsHolder.pop();
					}
				}
			} else {
				// W4: a modelled wiper part is moved by the mechanism's own kinematics, applied as a matrix
				// around the part's draw - the same shape as MTR's own 180 degree flip - so the optimized
				// model's baked geometry is untouched. False for every other part, which is the common case.
				// getId() is the NUMERIC vehicle id; the anchors and the wiper states are keyed by the
				// model's RESOURCE id, which is what the consist reports for this car.
				final String vehicleResourceId = vehicle == null ? null : vehicleIdFor(vehicle, carNumber);
				final int modelCar = vehicle == null ? carNumber : MmtrVehicleAnchors.modelCarIndex(vehicle, carNumber);
				final boolean wiperMoved = vehicleResourceId != null && MmtrWindshield.pushPartTransform(graphicsHolder, names, vehicleResourceId, carNumber, modelCar);
				if (condition == PartCondition.MMTR_LAMP) {
					// 诊断（notes/374）：传统路画了灯罩 —— 这条路没有颜色参数，画出来一定是白的，
					// 所以"尾灯不红"的答案就在这个计数器上（见 MmtrHeadlights.statLegacyLampDraws）。
					MmtrHeadlights.noteLegacyLampDraw();
				}
				partDetails.modelParts.forEach(modelPart -> modelPart.render(graphicsHolder, x, y, z, partDetails.flipped ? (float) Math.PI : 0, renderProperties.rightInt(), OverlayTexture.getDefaultUvMapped()));
				// On the OBJ path `modelParts` is always empty and the geometry lives in the part's own
				// wrapper (the same one doors use). Without this the draw above is a no-op, which is why the
				// wiper rotated into nothing. The wrapper is queued INSIDE the rotation push/pop from
				// pushPartTransform above, so the blade actually turns.
				if (partDetails.modelParts.isEmpty() && partDetails.hasDoorModel()) {
					// 雨刷（mechanism）那条路：模型在构造时就现建好了，这里只是取；门在非优化渲染下也走这里（那时建它是零成本，见 doorModel 的说明）
					final OptimizedModelWrapper doorModel = partDetails.doorModel();
					if (doorModel != null) {
						graphicsHolder.push();
						graphicsHolder.translate(x / 16, y / 16, z / 16);
						if (partDetails.flipped) {
							graphicsHolder.rotateYDegrees(180);
						}
						CustomResourceLoader.OPTIMIZED_RENDERER_WRAPPER.queue(doorModel, graphicsHolder, light);
						MmtrVehicleDrawProbe.onDoorQueued(doorModel.partCount());
						graphicsHolder.pop();
					}
				}
				if (wiperMoved) {
					graphicsHolder.pop();
				}
			}
		});
		graphicsHolder.pop();
	}

	private void renderLineColor(StoredMatrixTransformations storedMatrixTransformations, VehicleExtension vehicle, boolean fromResourcePackCreator) {
		final int color;
		if (fromResourcePackCreator) {
			color = ARGB_BLACK | rainbowColor();
		} else {
			color = getOrDefault(ARGB_BLACK | vehicle.vehicleExtraData.getThisRouteColor(), ARGB_BLACK | vehicle.vehicleExtraData.getNextRouteColor(), ARGB_BLACK | vehicle.vehicleExtraData.getPreviousRouteColor(), 0, vehicle);
		}

		MainRenderer.scheduleRender(new Identifier(Init.MOD_ID, String.format("textures/block/%s.png", displayType == DisplayType.ROUTE_COLOR ? "white" : "sign/circle")), true, QueuedRenderLayer.LIGHT_2, (graphicsHolder, offset) -> {
			storedMatrixTransformations.transform(graphicsHolder, offset);

			displayPartDetailsList.forEach(displayPartDetails -> {
				graphicsHolder.push();
				graphicsHolder.translate(displayPartDetails.x, displayPartDetails.y, displayPartDetails.z);
				graphicsHolder.rotateYDegrees(displayPartDetails.flipped ? 180 : 0);

				displayPartDetails.modelDisplayParts.forEach(displayParts -> displayParts.forEach(displayPart -> {
					displayPart.storedMatrixTransformations.transform(graphicsHolder, Vector3d.getZeroMapped());
					graphicsHolder.translate(displayXPadding / 16, displayYPadding / 16, -SMALL_OFFSET);
					IDrawing.drawTexture(
							graphicsHolder,
							0,
							0,
							(displayPart.width - (float) displayXPadding * 2) / 16,
							(displayPart.height - (float) displayYPadding * 2) / 16,
							0, 0, 1, 1, Direction.UP,
							color, GraphicsHolder.getDefaultLight()
					);
					graphicsHolder.pop();
				}));

				graphicsHolder.pop();
			});

			graphicsHolder.pop();
		});
	}

	private void renderSevenSegmentDisplay(StoredMatrixTransformations storedMatrixTransformations, VehicleExtension vehicle) {
		final String text = formatText(vehicle);
		final HorizontalAlignment horizontalAlignment = getHorizontalAlignment(false);

		MainRenderer.scheduleRender(new Identifier(Init.MOD_ID, "textures/overlay/seven_segment.png"), true, QueuedRenderLayer.LIGHT_2, (graphicsHolder, offset) -> {
			storedMatrixTransformations.transform(graphicsHolder, offset);

			displayPartDetailsList.forEach(displayPartDetails -> {
				graphicsHolder.push();
				graphicsHolder.translate(displayPartDetails.x, displayPartDetails.y, displayPartDetails.z);
				graphicsHolder.rotateYDegrees(displayPartDetails.flipped ? 180 : 0);

				displayPartDetails.modelDisplayParts.forEach(displayParts -> displayParts.forEach(displayPart -> {
					displayPart.storedMatrixTransformations.transform(graphicsHolder, Vector3d.getZeroMapped());
					graphicsHolder.translate(0, displayYPadding / 16, -SMALL_OFFSET);
					IDrawing.drawSevenSegment(
							graphicsHolder,
							text,
							(displayPart.width - (float) displayXPadding * 2) / 16,
							0, 0,
							(displayPart.height - (float) displayYPadding * 2) / 16,
							horizontalAlignment,
							ARGB_BLACK | displayColorInt, GraphicsHolder.getDefaultLight()
					);
					graphicsHolder.pop();
				}));

				graphicsHolder.pop();
			});

			graphicsHolder.pop();
		});
	}

	private void renderScrollingDisplay(StoredMatrixTransformations storedMatrixTransformations, VehicleExtension vehicle, int carNumber, int[] scrollingDisplayIndexTracker) {
		final String text = formatText(vehicle);
		final ObjectArrayList<ScrollingText> scrollingTexts = vehicle.persistentVehicleData.getScrollingText(carNumber);

		displayPartDetailsList.forEach(displayPartDetails -> {
			final StoredMatrixTransformations storedMatrixTransformations1 = storedMatrixTransformations.copy();
			storedMatrixTransformations1.add(graphicsHolder -> {
				graphicsHolder.translate(displayPartDetails.x, displayPartDetails.y, displayPartDetails.z);
				graphicsHolder.rotateYDegrees(displayPartDetails.flipped ? 180 : 0);
			});

			displayPartDetails.modelDisplayParts.forEach(displayParts -> displayParts.forEach(displayPart -> {
				final StoredMatrixTransformations storedMatrixTransformations2 = storedMatrixTransformations1.copy();
				storedMatrixTransformations2.add(displayPart.storedMatrixTransformations);
				storedMatrixTransformations2.add(graphicsHolder -> graphicsHolder.translate(displayXPadding / 16, displayYPadding / 16, -SMALL_OFFSET));
				final double width = (displayPart.width - displayXPadding * 2) / 16F;
				final double height = (displayPart.height - displayYPadding * 2) / 16F;

				while (scrollingTexts.size() <= scrollingDisplayIndexTracker[0]) {
					scrollingTexts.add(new ScrollingText(width, height, 4, height < 0.1));
				}

				scrollingTexts.get(scrollingDisplayIndexTracker[0]).changeImage(text.isEmpty() ? null : DynamicTextureCache.instance.getPixelatedText(text, ARGB_BLACK | displayColorInt, Integer.MAX_VALUE, displayCjkSizeRatio, height < 0.1));
				scrollingTexts.get(scrollingDisplayIndexTracker[0]).scrollText(storedMatrixTransformations2);
				scrollingDisplayIndexTracker[0]++;
			}));
		});
	}

	private void renderDisplay(StoredMatrixTransformations storedMatrixTransformations, VehicleExtension vehicle) {
		final String[] textSplit = formatText(vehicle).split("\\|");
		final boolean[] isCjk = new boolean[textSplit.length];
		final double[] textHeightScale = new double[textSplit.length];
		double tempTotalHeight = 0;
		for (int i = 0; i < textSplit.length; i++) {
			isCjk[i] = IGui.isCjk(textSplit[i]);
			textHeightScale[i] = isCjk[i] ? displayCjkSizeRatio <= 0 ? 1 : displayCjkSizeRatio : 1;
			tempTotalHeight += textHeightScale[i];
		}
		final double rawTextHeight = tempTotalHeight;

		MainRenderer.scheduleRender(QueuedRenderLayer.TEXT, (graphicsHolder, offset) -> {
			storedMatrixTransformations.transform(graphicsHolder, offset);

			displayPartDetailsList.forEach(displayPartDetails -> {
				graphicsHolder.push();
				graphicsHolder.translate(displayPartDetails.x, displayPartDetails.y, displayPartDetails.z);
				graphicsHolder.rotateYDegrees(displayPartDetails.flipped ? 180 : 0);

				displayPartDetails.modelDisplayParts.forEach(displayParts -> displayParts.forEach(displayPart -> {
					displayPart.storedMatrixTransformations.transform(graphicsHolder, Vector3d.getZeroMapped());
					final double totalTextHeight = Math.min(displayPart.height - displayYPadding * 2, displayMaxLineHeight <= 0 ? Double.MAX_VALUE : displayMaxLineHeight * rawTextHeight) / 16;
					final double textScale = totalTextHeight / rawTextHeight / (TEXT_HEIGHT + LINE_PADDING);
					graphicsHolder.translate(displayXPadding / 16, displayYPadding / 16 + Math.max(0, getVerticalAlignment().getOffset(0, (float) (totalTextHeight - (displayPart.height - displayYPadding * 2) / 16))), -SMALL_OFFSET);

					for (int i = 0; i < textSplit.length; i++) {
						final double availableTextWidth = (displayPart.width - displayXPadding * 2) / 16;
						final double newTextScale = textHeightScale[i] * textScale;
						final MutableText mutableText = IDrawing.withMTRFont(TextHelper.literal(textSplit[i]));
						final double textWidth = GraphicsHolder.getTextWidth(mutableText) * newTextScale;
						final HorizontalAlignment horizontalAlignment = getHorizontalAlignment(isCjk[i]);
						graphicsHolder.push();
						graphicsHolder.translate(Math.max(0, horizontalAlignment.getOffset(0, (float) (textWidth - availableTextWidth))), 0, 0);
						graphicsHolder.scale((float) (Math.min(1, availableTextWidth / textWidth) * newTextScale), (float) newTextScale, 1);
						graphicsHolder.drawText(mutableText, 0, 0, isCjk[i] ? displayColorCjkInt : displayColorInt, false, GraphicsHolder.getDefaultLight());
						graphicsHolder.pop();
						graphicsHolder.translate(0, newTextScale * (TEXT_HEIGHT + LINE_PADDING), 0);
					}

					graphicsHolder.pop();
				}));

				graphicsHolder.pop();
			});

			graphicsHolder.pop();
		});
	}

	private void addCube(Identifier texture, ObjectArrayList<ModelPartExtension> modelParts, Object2ObjectOpenHashMap<PartCondition, Object2ObjectOpenHashMap<RenderStage, OptimizedModelWrapper.MaterialGroupWrapper>> materialGroupsForPartConditionAndRenderStage, double x, double y, double z, boolean flipped) {
		modelParts.forEach(modelPart -> Data.put(materialGroupsForPartConditionAndRenderStage, condition, renderStage, oldValue -> {
			final OptimizedModelWrapper.MaterialGroupWrapper materialGroup = oldValue == null ? new OptimizedModelWrapper.MaterialGroupWrapper(renderStage.shaderType, texture) : oldValue;
			materialGroup.addCube(modelPart, (x + doorAnimationType.getDoorAnimationX(doorXMultiplier, flipped, 0)) / 16, y / 16, (z + doorAnimationType.getDoorAnimationZ(doorZMultiplier, flipped, 0, false)) / 16, flipped, MAX_LIGHT_INTERIOR);
			return materialGroup;
		}, Object2ObjectOpenHashMap::new));
	}

	private void addObjModelPosition(
			ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper> objModels,
			Object2ObjectOpenHashMap<PartCondition, Object2ObjectOpenHashMap<RenderStage, ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper>>> objModelsForPartConditionAndRenderStage,
			double x, double y, double z, boolean flipped, double modelYOffset
	) {
		objModels.forEach(objModel -> Data.put(objModelsForPartConditionAndRenderStage, condition, renderStage, oldValue -> {
			final ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper> newObjModels = oldValue == null ? new ObjectArrayList<>() : oldValue;
			/*
			 * 判定性读数（notes/401 §8）：把"烘位置"这一段的耗时单独累出来。
			 * 车辆构造那 72–111 ms 到底花在哪，光看总数分不出来 —— 而 addTransformation 是里面
			 * 唯一一段"逐实例深拷贝网格"的重活，它是不是大头决定了下一步该不该把它搬走。
			 */
			final long transformationStartNanos = System.nanoTime();
			objModel.addTransformation(renderStage.shaderType, (x + doorAnimationType.getDoorAnimationX(doorXMultiplier, flipped, 0)) / 16, y / 16 - modelYOffset, (z + doorAnimationType.getDoorAnimationZ(doorZMultiplier, flipped, 0, false)) / 16, flipped);
			addTransformationNanos += System.nanoTime() - transformationStartNanos;
			addTransformationCalls++;
			newObjModels.add(objModel);
			return newObjModels;
		}, Object2ObjectOpenHashMap::new));
	}

	/** {@code addTransformation} 的累计耗时/次数（notes/401 §8 的分段读数，由模型构造那边汇总后清零）。 */
	private static long addTransformationNanos;
	private static int addTransformationCalls;

	/**
	 * 取走累计耗时并清零（跨包：{@code DynamicVehicleModel} 在 {@code org.mtr.mod.render}）。
	 *
	 * <p>为什么不做成"每辆车一个计数器"：模型构造是**单线程**的（渲染线程），
	 * 而这两个数只在"这一次构造"的边界上被取走 —— 加锁反而会把热路径拖慢。</p>
	 */
	public static long takeAddTransformationNanos() {
		final long value = addTransformationNanos;
		addTransformationNanos = 0;
		return value;
	}

	public static int takeAddTransformationCalls() {
		final int value = addTransformationCalls;
		addTransformationCalls = 0;
		return value;
	}

	/** {@code optimizedModelDoor.get()} 的累计耗时/有效次数（notes/401 §8 续）—— writeCache 里唯一的 GL 那一段。 */
	private static long doorModelNanos;
	private static int doorModelCalls;

	public static long takeDoorModelNanos() {
		final long value = doorModelNanos;
		doorModelNanos = 0;
		return value;
	}

	public static int takeDoorModelCalls() {
		final int value = doorModelCalls;
		doorModelCalls = 0;
		return value;
	}

	private String formatText(Vehicle vehicle) {
		final String destination = getOrDefault(vehicle.vehicleExtraData.getThisRouteDestination(), vehicle.vehicleExtraData.getNextRouteDestination(), vehicle.vehicleExtraData.getPreviousRouteDestination(), displayDefaultText, vehicle);
		final String routeNumber = getOrDefault(vehicle.vehicleExtraData.getThisRouteNumber(), vehicle.vehicleExtraData.getNextRouteNumber(), vehicle.vehicleExtraData.getPreviousRouteNumber(), displayDefaultText, vehicle);
		final String routeName = getOrDefault(routeNumber + " ", routeNumber, "") + getOrDefault(vehicle.vehicleExtraData.getThisRouteName(), vehicle.vehicleExtraData.getNextRouteName(), vehicle.vehicleExtraData.getPreviousRouteName(), displayDefaultText, vehicle);
		final String thisStation = getOrDefault(vehicle.vehicleExtraData.getThisStationName(), vehicle.vehicleExtraData.getPreviousStationName());
		final String nextStation = getOrDefault(vehicle.vehicleExtraData.getNextStationName(), vehicle.vehicleExtraData.getThisStationName(), vehicle.vehicleExtraData.getThisStationName());
		final boolean doorsOpen = vehicle.vehicleExtraData.getDoorMultiplier() > 0;

		final String text;
		switch (displayType) {
			case DESTINATION:
				text = vehicle.getIsOnRoute() ? destination : displayDefaultText;
				break;
			case ROUTE_NUMBER:
				text = vehicle.getIsOnRoute() ? routeNumber : displayDefaultText;
				break;
			case DEPARTURE_INDEX:
				if (vehicle.getIsOnRoute()) {
					final StringBuilder stringBuilder = new StringBuilder(String.valueOf(vehicle.getDepartureIndex() + 1));
					final int startLength = stringBuilder.length();
					for (int i = startLength; i < displayPadZeros; i++) {
						stringBuilder.insert(0, "0");
					}
					text = stringBuilder.toString();
				} else {
					text = displayDefaultText;
				}
				break;
			case NEXT_STATION:
				text = vehicle.getIsOnRoute() ? doorsOpen ? thisStation : nextStation : displayDefaultText;
				break;
			case NEXT_STATION_KCR:
				text = vehicle.getIsOnRoute() ? DisplayType.getHongKongNextStationString(thisStation, nextStation, doorsOpen, true) : displayDefaultText;
				break;
			case NEXT_STATION_MTR:
				text = vehicle.getIsOnRoute() ? DisplayType.getHongKongNextStationString(thisStation, nextStation, doorsOpen, false) : displayDefaultText;
				break;
			case NEXT_STATION_UK:
				text = vehicle.getIsOnRoute() ? DisplayType.getLondonNextStationString(routeName, thisStation, nextStation, vehicle.vehicleExtraData::iterateInterchanges, destination, doorsOpen, vehicle.vehicleExtraData.getIsTerminating()) : displayDefaultText;
				break;
			default:
				text = "";
				break;
		}

		String newText = text;
		for (final String displayOption : displayOptions) {
			newText = EnumHelper.valueOf(DisplayOption.NONE, displayOption).format(newText);
		}
		return newText;
	}

	private HorizontalAlignment getHorizontalAlignment(boolean isCjk) {
		if (isCjk) {
			if (displayOptions.contains(DisplayOption.ALIGN_LEFT_CJK.toString())) {
				return HorizontalAlignment.LEFT;
			} else if (displayOptions.contains(DisplayOption.ALIGN_RIGHT_CJK.toString())) {
				return HorizontalAlignment.RIGHT;
			} else {
				return HorizontalAlignment.CENTER;
			}
		} else {
			if (displayOptions.contains(DisplayOption.ALIGN_LEFT.toString())) {
				return HorizontalAlignment.LEFT;
			} else if (displayOptions.contains(DisplayOption.ALIGN_RIGHT.toString())) {
				return HorizontalAlignment.RIGHT;
			} else {
				return HorizontalAlignment.CENTER;
			}
		}
	}

	private VerticalAlignment getVerticalAlignment() {
		if (displayOptions.contains(DisplayOption.ALIGN_TOP.toString())) {
			return VerticalAlignment.TOP;
		} else if (displayOptions.contains(DisplayOption.ALIGN_BOTTOM.toString())) {
			return VerticalAlignment.BOTTOM;
		} else {
			return VerticalAlignment.CENTER;
		}
	}

	private static ObjectIntImmutablePair<QueuedRenderLayer> getRenderProperties(RenderStage renderStage, int light, @Nullable VehicleExtension vehicle) {
		if (renderStage == RenderStage.ALWAYS_ON_LIGHT) {
			return new ObjectIntImmutablePair<>(QueuedRenderLayer.LIGHT_2, GraphicsHolder.getDefaultLight());
		} else if (vehicle != null) {
			if (vehicle.getIsOnRoute()) {
				switch (renderStage) {
					case LIGHT:
						return new ObjectIntImmutablePair<>(QueuedRenderLayer.LIGHT, GraphicsHolder.getDefaultLight());
					case INTERIOR:
						return new ObjectIntImmutablePair<>(QueuedRenderLayer.INTERIOR, MAX_LIGHT_INTERIOR);
					case INTERIOR_TRANSLUCENT:
						return new ObjectIntImmutablePair<>(QueuedRenderLayer.INTERIOR_TRANSLUCENT, MAX_LIGHT_INTERIOR);
				}
			} else {
				if (renderStage == RenderStage.INTERIOR_TRANSLUCENT) {
					return new ObjectIntImmutablePair<>(QueuedRenderLayer.EXTERIOR_TRANSLUCENT, light);
				}
			}
		}

		return new ObjectIntImmutablePair<>(QueuedRenderLayer.EXTERIOR, light);
	}

	private static Box addBox(Box box, double x, double y, double z, boolean flipped) {
		return new Box(
				(flipped ? -1 : 1) * box.getMinXMapped() + x / 16, box.getMinYMapped() + y / 16, (flipped ? 1 : -1) * box.getMinZMapped() + z / 16,
				(flipped ? -1 : 1) * box.getMaxXMapped() + x / 16, box.getMaxYMapped() + y / 16, (flipped ? 1 : -1) * box.getMaxZMapped() + z / 16
		);
	}

	private static void iteratePositions(ObjectArrayList<PartPosition> positions, ObjectArrayList<PartPosition> positionsFlipped, PositionCallback positionCallback) {
		positions.forEach(position -> positionCallback.accept(position.getX(), position.getY(), position.getZ(), false));
		positionsFlipped.forEach(position -> positionCallback.accept(-position.getX(), position.getY(), position.getZ(), true));
	}

	private static double getClosestDistance(double a1, double a2, double b1, double b2) {
		return Math.min(Math.min(Math.abs(b1 - a1), Math.abs(b1 - a2)), Math.min(Math.abs(b2 - a1), Math.abs(b2 - a2)));
	}

	private static int parseColor(String colorString, int defaultColor) {
		try {
			return Integer.parseInt(colorString, 16);
		} catch (Exception ignored) {
			return defaultColor;
		}
	}

	private static int rainbowColor() {
		final long timeR = System.currentTimeMillis() % 3000;
		final long timeG = (timeR + 1000) % 3000;
		final long timeB = (timeR + 2000) % 3000;
		int r = timeR < 2000 ? (int) Math.round(Math.sin(timeR * Math.PI / 2000) * 0xFF) : 0;
		int g = timeG < 2000 ? (int) Math.round(Math.sin(timeG * Math.PI / 2000) * 0xFF) : 0;
		int b = timeB < 2000 ? (int) Math.round(Math.sin(timeB * Math.PI / 2000) * 0xFF) : 0;
		return (r << 16) + (g << 8) + b;
	}

	private static String getOrDefault(String checkText, String defaultText) {
		return getOrDefault(checkText, checkText, defaultText);
	}

	private static <T> T getOrDefault(T outputValue, String checkText, T defaultValue) {
		return checkText.isEmpty() ? defaultValue : outputValue;
	}

	private static <T> T getOrDefault(T thisRouteData, T nextRouteData, T previousRouteData, T defaultValue, Vehicle vehicle) {
		if (vehicle.vehicleExtraData.getThisRouteId() != 0) {
			return thisRouteData;
		} else if (vehicle.vehicleExtraData.getNextRouteId() != 0) {
			return nextRouteData;
		} else if (vehicle.vehicleExtraData.getPreviousRouteId() != 0) {
			return previousRouteData;
		} else {
			return defaultValue;
		}
	}

	private static class PartDetails {

		@Nullable
		private Box doorway;
		private final ObjectArrayList<ModelPartExtension> modelParts;
		/** 已建好的门/雨刷模型；门那条路是**惰性**的 ⇒ 第一次要用之前这里是 {@code null}。 */
		@Nullable
		private OptimizedModelWrapper optimizedModelDoor;
		/** 惰性建门模型的那个 supplier（与雨刷共用同一份实现：`isDoor() || mechanism ? fromObjModels : null`）。 */
		@Nullable
		private final Supplier<OptimizedModelWrapper> lazyDoorModel;
		private boolean lazyDoorResolved;
		private final Box box;
		private final double x;
		private final double y;
		private final double z;
		private final boolean flipped;

		private PartDetails(ObjectArrayList<ModelPartExtension> modelParts, @Nullable OptimizedModelWrapper optimizedModelDoor, @Nullable Supplier<OptimizedModelWrapper> lazyDoorModel, Box box, double x, double y, double z, boolean flipped) {
			this.modelParts = OptimizedRenderer.hasOptimizedRendering() ? new ObjectArrayList<>() : modelParts;
			this.optimizedModelDoor = optimizedModelDoor;
			this.lazyDoorModel = lazyDoorModel;
			this.box = box;
			this.x = x;
			this.y = y;
			this.z = z;
			this.flipped = flipped;
		}

		/** 有没有可能拿到门/雨刷模型（**不触发**惰性创建）—— 给渲染分支做条件用。 */
		private boolean hasDoorModel() {
			return optimizedModelDoor != null || (!lazyDoorResolved && lazyDoorModel != null);
		}

		/**
		 * 门/雨刷模型（**渲染线程**；门那条路是第一次调用时才建）。
		 *
		 * <p>{@code beginReload/finishReload} 是必须的：`fromObjModels` 内部会 {@code upload()} 建 VBO，
		 * 而 `VertexArray` 的构造要求 {@code GlStateTracker} 在保护区内（否则抛
		 * {@code GlStateTracker: Not protected}，notes/398 §8.9.2 踩过）。这个包装器是**按嵌套层数**
		 * 记账的（只有最外层碰 GL 状态），所以渲染中途再开一层是安全的 —— `MmtrDoorBatch.build`
		 * 在渲染里做的就是同一件事。</p>
		 */
		@Nullable
		private OptimizedModelWrapper doorModel() {
			if (optimizedModelDoor == null && !lazyDoorResolved && lazyDoorModel != null) {
				lazyDoorResolved = true;
				final long lazyStartNanos = System.nanoTime();
				final OptimizedModelWrapper built;
				CustomResourceLoader.OPTIMIZED_RENDERER_WRAPPER.beginReload();
				try {
					built = lazyDoorModel.get();
				} finally {
					CustomResourceLoader.OPTIMIZED_RENDERER_WRAPPER.finishReload();
				}
				optimizedModelDoor = built;
				if (built != null) {
					// 只记**真的建出了模型**的那些：非门/非雨刷部件第一次解析也会走到这里（supplier 返回 null），
					// 把那些也打出来只会把"惰性建了几个门"淹没掉。
					reportLazyDoor((System.nanoTime() - lazyStartNanos) / 1_000_000L, built.partCount());
				}
			}
			return optimizedModelDoor;
		}
	}

	/** 惰性建门模型的累计次数（notes/401 §11.2）—— 用来证明"推迟"真的发生了，以及它推迟到了哪一帧。 */
	private static int lazyDoorBuilds;
	private static final int MAX_LAZY_DOOR_LOGS = 40;
	private static int lazyDoorLogs;

	private static void reportLazyDoor(long millis, int draws) {
		lazyDoorBuilds++;
		if (lazyDoorLogs < MAX_LAZY_DOOR_LOGS) {
			lazyDoorLogs++;
			Init.LOGGER.info("[MMTR-LAZYDOOR] 惰性建门模型 {} ms ｜ {} 次 draw ｜ 累计第 {} 次", millis, draws, lazyDoorBuilds);
			if (lazyDoorLogs == MAX_LAZY_DOOR_LOGS) {
				Init.LOGGER.info("[MMTR-LAZYDOOR] 读数已达 {} 条上限（惰性建仍在正常进行）", MAX_LAZY_DOOR_LOGS);
			}
		}
	}

	private static class DisplayPartDetails {

		private final ObjectArrayList<ObjectArrayList<ModelDisplayPart>> modelDisplayParts;
		private final double x;
		private final double y;
		private final double z;
		private final boolean flipped;

		private DisplayPartDetails(ObjectArrayList<ObjectArrayList<ModelDisplayPart>> modelDisplayParts, double x, double y, double z, boolean flipped) {
			this.modelDisplayParts = modelDisplayParts;
			this.x = x / 16;
			this.y = y / 16;
			this.z = z / 16;
			this.flipped = flipped;
		}
	}

	@FunctionalInterface
	private interface PositionCallback {
		void accept(double x, double y, double z, boolean flipped);
	}
}



