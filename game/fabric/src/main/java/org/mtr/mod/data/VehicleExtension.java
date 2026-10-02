package org.mtr.mod.data;

import org.mtr.core.data.Data;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.operation.VehicleUpdate;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Utilities;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleDoubleImmutablePair;
import org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleObjectImmutablePair;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.Init;
import org.mtr.mod.InitClient;
import org.mtr.mod.block.BlockTrainAnnouncer;
import org.mtr.mod.block.BlockTrainRedstoneSensor;
import org.mtr.mod.block.BlockTrainSensorBase;
import org.mtr.mod.block.IBlock;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.client.MmtrVehicleMotionClient;
import org.mtr.mod.client.VehicleRidingMovement;
import org.mtr.mod.generated.lang.TranslationProvider;
import org.mtr.mod.packet.PacketCheckRouteIdHasDisabledAnnouncements;
import org.mtr.mod.packet.PacketTurnOnBlockEntity;

import org.mtr.mod.resource.VehicleResource;

import javax.annotation.Nullable;

public class VehicleExtension extends Vehicle implements Utilities {

	private double oldSpeed;
	private int speedLimitKilometersPerHour;
	/** Last server-authoritative speed (km/h) from the most recent vehicle snapshot. */
	private double serverSpeedKilometersPerHour;
	@Nullable
	private DoubleObjectImmutablePair<DoubleDoubleImmutablePair> platformStoppingDetails;

	public final PersistentVehicleData persistentVehicleData;

	public VehicleExtension(VehicleUpdate vehicleUpdate, Data data) {
		super(vehicleUpdate.getVehicleExtraData(), null, new JsonReader(Utilities.getJsonObjectFromData(vehicleUpdate.getVehicle())), data);
		serverSpeedKilometersPerHour = getSpeed() * 3600;
		org.mtr.core.mmtr.MmtrTrace.log("[MMTR-CL] mirror created id=" + getId() + " cars=" + vehicleExtraData.immutableVehicleCars.size() + " path=" + vehicleExtraData.immutablePath.size() + " progress=" + railProgress + " doors=" + vehicleExtraData.getDoorMultiplier());
		final PersistentVehicleData tempPersistentVehicleData = MinecraftClientData.getInstance().vehicleIdToPersistentVehicleData.get(getId());
		if (tempPersistentVehicleData == null || !tempPersistentVehicleData.matchesCarCount(vehicleExtraData.immutableVehicleCars.size())) {
			// MMTR: coupling/uncoupling changes a vehicle id's car count, so the per-car cache must be
			// rebuilt instead of reused - otherwise the renderer indexes past the end of rayTracing.
			if (tempPersistentVehicleData != null) {
				tempPersistentVehicleData.dispose();
			}
			persistentVehicleData = new PersistentVehicleData(vehicleExtraData.immutableVehicleCars, getTransportMode());
			MinecraftClientData.getInstance().vehicleIdToPersistentVehicleData.put(getId(), persistentVehicleData);
		} else {
			persistentVehicleData = tempPersistentVehicleData;
			persistentVehicleData.update(railProgress, vehicleExtraData.getTotalVehicleLength());
		}
	}

	/**
	 * **① 接管之后，② 让出的字段**（notes/369 §6 的所有权）。
	 *
	 * <p>运动流（①）每 100 ms 就把位置/速度/手柄/运动旗标与三个夹紧量写到客户端镜像上，
	 * 而 ② 是"脏拍才发"（车跑起来时约每秒一次）。两边都写同一格会出现**旧值覆盖新值**：
	 * ② 的补丁是按"上一拍的服务端状态"算出来的，落到镜像上就把 ① 刚对齐好的位置拉回去 ——
	 * 现场就是"每秒钟仍然跳一下"，正是这个重构要消掉的那个东西。</p>
	 *
	 * <p>所以规则写成一句：**这辆车一旦有了 ① 槽位，② 的补丁就不再碰这些字段**。
	 * 整份快照（换编组/换交路/第一次看见）**不受这条限制** —— 那条路的语义是"重建镜像"，
	 * 硬对齐正是它该做的事；而 ① 的下一帧（≤100 ms）会把运动量重新对齐。</p>
	 */
	private static final java.util.Set<String> MOTION_OWNED_KEYS = java.util.Set.of(
		"speed", "railProgress", "reversed",
		"mmtrThrottleNotch", "mmtrBrakeNotch", "mmtrDriveHandle", "mmtrCruiseKmh", "mmtrReverser", "mmtrEmergency",
		"mmtrActive", "mmtrMotionMirror", "mmtrPinned", "mmtrProtection", "mmtrBlockHeld", "mmtrAuthorityTripped",
		"mmtrRunStopTarget", "mmtrRunTotalDistance");

	/**
	 * 把一段里 ① 拥有的字段摘掉（见 {@link #MOTION_OWNED_KEYS}）。全摘空时返回空对象，
	 * 合并它是空操作 —— 这正是"这一拍对客户端没有任何新信息"的正确表达。
	 */
	private static JsonObject withoutMotionOwnedKeys(@Nullable JsonObject section) {
		if (section == null) {
			return null;
		}
		final JsonObject filtered = new JsonObject();
		section.entrySet().forEach(entry -> {
			if (!MOTION_OWNED_KEYS.contains(entry.getKey())) {
				filtered.add(entry.getKey(), entry.getValue());
			}
		});
		return filtered;
	}

	/**
	 * 把一段**稀疏补丁**合并进这份镜像（notes/174）。
	 *
	 * <p><b>两段都允许缺席</b>：`getAsJsonObject(...)` 在键不存在时返回 {@code null}，
	 * 而 {@code new JsonReader(null)} 会**抛异常**（实机吃过：服务端只发了 `data` 一段的补丁，
	 * 这里当场炸，整条补丁作废 —— 表现为"车与人抽搐、无法移动"）。现在的约定是
	 * 服务端一定把两段都发出来（空段给 `{}`），这里也再兜一层，谁先升级都不会炸。</p>
	 */
	public void updateData(@Nullable JsonObject jsonObject) {
		if (jsonObject != null) {
			// ①（运动流）管到这辆车了 ⇒ 位置/手柄/运动旗标由它说话（见 MOTION_OWNED_KEYS）。
			final boolean motionManaged = MmtrVehicleMotionClient.isMotionManaged(getId());
			final JsonObject vehicleJson = motionManaged ? withoutMotionOwnedKeys(jsonObject.getAsJsonObject("vehicle")) : jsonObject.getAsJsonObject("vehicle");
			if (vehicleJson != null) {
				updateData(new JsonReader(vehicleJson));
				serverSpeedKilometersPerHour = getSpeed() * 3600;
			}
			final JsonObject dataJson = motionManaged ? withoutMotionOwnedKeys(jsonObject.getAsJsonObject("data")) : jsonObject.getAsJsonObject("data");
			if (dataJson != null) {
				vehicleExtraData.updateData(new JsonReader(dataJson));
			}
		}
	}

	public double getServerSpeedKilometersPerHour() {
		return serverSpeedKilometersPerHour;
	}

	public void simulate(long millisElapsed) {
		final double oldRailProgress = railProgress;
		oldSpeed = speed;
		simulate(millisElapsed, null, null);
		persistentVehicleData.tick(railProgress, millisElapsed, vehicleExtraData);
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		final ClientWorld clientWorld = minecraftClient.getWorldMapped();
		final ClientPlayerEntity clientPlayerEntity = minecraftClient.getPlayerMapped();

		if (clientWorld == null || clientPlayerEntity == null) {
			return;
		}

		final int thisRouteColor = vehicleExtraData.getThisRouteColor();
		final String thisRouteName = formatRouteName(vehicleExtraData.getThisRouteName());
		final int nextRouteColor = vehicleExtraData.getNextRouteColor();
		final String nextRouteName = formatRouteName(vehicleExtraData.getNextRouteName());
		final String thisStationName = vehicleExtraData.getThisStationName();
		final String nextStationName = vehicleExtraData.getNextStationName();
		final String thisRouteDestination = vehicleExtraData.getThisRouteDestination();
		final String nextRouteDestination = vehicleExtraData.getNextRouteDestination();
		final long thisRouteId = vehicleExtraData.getThisRouteId();

		if (VehicleRidingMovement.isRiding(id)) {
			// Render client action bar floating text
			if (VehicleRidingMovement.showShiftProgressBar()) {
				if (speed * MILLIS_PER_SECOND > 5 || thisRouteName.isEmpty() || thisStationName.isEmpty() || thisRouteDestination.isEmpty()) {
					clientPlayerEntity.sendMessage(TranslationProvider.GUI_MTR_VEHICLE_SPEED.getText(Utilities.round(speed * MILLIS_PER_SECOND, 1), Utilities.round(speed * 3.6F * MILLIS_PER_SECOND, 1)), true);
				} else {
					final MutableText text;
					switch ((int) ((System.currentTimeMillis() / 1000) % 3)) {
						default:
							text = getStationText(thisStationName, TranslationProvider.GUI_MTR_THIS_STATION_CJK, TranslationProvider.GUI_MTR_THIS_STATION);
							break;
						case 1:
							if (nextStationName.isEmpty()) {
								text = getStationText(thisStationName, TranslationProvider.GUI_MTR_THIS_STATION_CJK, TranslationProvider.GUI_MTR_THIS_STATION);
							} else {
								text = getStationText(nextStationName, TranslationProvider.GUI_MTR_NEXT_STATION_CJK, TranslationProvider.GUI_MTR_NEXT_STATION);
							}
							break;
						case 2:
							switch (transportMode) {
								case TRAIN:
									text = getStationText(thisRouteDestination, TranslationProvider.GUI_MTR_LAST_TRAIN_STATION_CJK, TranslationProvider.GUI_MTR_LAST_TRAIN_STATION);
									break;
								case BOAT:
									text = getStationText(thisRouteDestination, TranslationProvider.GUI_MTR_LAST_BOAT_STATION_CJK, TranslationProvider.GUI_MTR_LAST_BOAT_STATION);
									break;
								case CABLE_CAR:
									text = getStationText(thisRouteDestination, TranslationProvider.GUI_MTR_LAST_CABLE_CAR_STATION_CJK, TranslationProvider.GUI_MTR_LAST_CABLE_CAR_STATION);
									break;
								case AIRPLANE:
									text = getStationText(thisRouteDestination, TranslationProvider.GUI_MTR_LAST_AIRPLANE_STATION_CJK, TranslationProvider.GUI_MTR_LAST_AIRPLANE_STATION);
									break;
								default:
									text = TextHelper.literal("");
							}
							break;
					}
					clientPlayerEntity.sendMessage(new Text(text.data), true);
				}
			}

			// TODO chat announcements (next station, route number, etc.)
			if (persistentVehicleData.canAnnounce(oldRailProgress, railProgress)) {
				InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketCheckRouteIdHasDisabledAnnouncements(thisRouteId, routeIdHasDisabledAnnouncements -> {
					if (!routeIdHasDisabledAnnouncements) {
						final ObjectArrayList<String> narrateText = new ObjectArrayList<>();
						final ObjectArrayList<MutableText> chatText = new ObjectArrayList<>();

						if (!nextStationName.isEmpty()) {
							final String nextStationFormatted = IGui.insertTranslation(TranslationProvider.GUI_MTR_NEXT_STATION_ANNOUNCEMENT_CJK, TranslationProvider.GUI_MTR_NEXT_STATION_ANNOUNCEMENT, 1, nextStationName);
							narrateText.add(nextStationFormatted);
							chatText.add(TextHelper.literal(IGui.formatStationName(nextStationFormatted)));
						}

						final ObjectArrayList<String> narrateTextThisStation = new ObjectArrayList<>();
						final ObjectArrayList<String> narrateTextOtherStations = new ObjectArrayList<>();
						final ObjectArrayList<MutableText> chatTextThisStation = new ObjectArrayList<>();
						final ObjectArrayList<MutableText> chatTextOtherStations = new ObjectArrayList<>();

						vehicleExtraData.iterateInterchanges((stationName, interchangeColors) -> {
							final ObjectArrayList<String> combinedRouteNames = new ObjectArrayList<>();
							final ObjectArrayList<String> globalVisitedRouteNames = new ObjectArrayList<>();
							final boolean isThisStation = stationName.equals(nextStationName);
							final boolean[] addedStationName = {false};

							interchangeColors.forEach((color, routeNames) -> {
								final ObjectArrayList<String> visitedRouteNames = new ObjectArrayList<>();

								routeNames.forEach(routeName -> {
									final String routeNameFormatted = formatRouteName(routeName);
									if (!routeName.isEmpty() && !visitedRouteNames.contains(routeNameFormatted) && (color != thisRouteColor || !routeNameFormatted.equals(thisRouteName)) && (color != nextRouteColor || !routeNameFormatted.equals(nextRouteName))) {
										if (!isThisStation && !addedStationName[0]) {
											chatTextOtherStations.add(TextHelper.literal(IGui.formatStationName(IGui.insertTranslation(TranslationProvider.GUI_MTR_CONNECTING_STATION_ANNOUNCEMENT_CJK, TranslationProvider.GUI_MTR_CONNECTING_STATION_ANNOUNCEMENT, 1, stationName))));
										}

										if (!globalVisitedRouteNames.contains(routeNameFormatted)) {
											combinedRouteNames.add(routeNameFormatted);
										}

										(isThisStation ? chatTextThisStation : chatTextOtherStations).add(TextHelper.append(
												TextHelper.setStyle(TextHelper.literal("-"), Style.getEmptyMapped().withColor(TextColor.fromRgb(color))),
												TextHelper.setStyle(TextHelper.literal(" " + IGui.formatStationName(routeNameFormatted)), Style.getEmptyMapped().withColor(TextFormatting.getWhiteMapped()))
										));

										addedStationName[0] = true;
										globalVisitedRouteNames.add(routeNameFormatted);
										visitedRouteNames.add(routeNameFormatted);
									}
								});
							});

							if (addedStationName[0]) {
								if (isThisStation) {
									narrateTextThisStation.add(IGui.insertTranslation(TranslationProvider.GUI_MTR_INTERCHANGE_ANNOUNCEMENT_CJK, TranslationProvider.GUI_MTR_INTERCHANGE_ANNOUNCEMENT, 1, getInterchangeText(combinedRouteNames)));
								} else {
									narrateTextOtherStations.add(IGui.insertTranslation(TranslationProvider.GUI_MTR_CONNECTING_STATION_PART_CJK, TranslationProvider.GUI_MTR_CONNECTING_STATION_PART, 1, IGui.insertTranslation(TranslationProvider.GUI_MTR_CONNECTING_STATION_INTERCHANGE_ANNOUNCEMENT_PART_CJK, TranslationProvider.GUI_MTR_CONNECTING_STATION_INTERCHANGE_ANNOUNCEMENT_PART, 2, getInterchangeText(combinedRouteNames), stationName)));
								}
							}
						});

						narrateText.addAll(narrateTextThisStation);
						narrateText.addAll(narrateTextOtherStations);
						chatText.addAll(chatTextThisStation);
						chatText.addAll(chatTextOtherStations);

						if (!nextRouteName.isEmpty() && (nextRouteColor != thisRouteColor || !nextRouteName.equals(thisRouteName))) {
							final String changeRouteText = IGui.insertTranslation(TranslationProvider.GUI_MTR_NEXT_ROUTE_TRAIN_ANNOUNCEMENT_CJK, TranslationProvider.GUI_MTR_NEXT_ROUTE_TRAIN_ANNOUNCEMENT, 2, nextRouteName, nextRouteDestination);
							chatText.add(TextHelper.append(
									TextHelper.setStyle(TextHelper.literal("*"), Style.getEmptyMapped().withColor(TextColor.fromRgb(nextRouteColor))),
									TextHelper.setStyle(TextHelper.literal(" " + IGui.formatStationName(changeRouteText)), Style.getEmptyMapped().withColor(TextFormatting.getWhiteMapped()))
							));
							narrateText.add(changeRouteText);
						}

						IDrawing.narrateOrAnnounce(IGui.formatStationName(IGui.mergeStations(narrateText, " ", " ")), chatText);
					}
				}));
			}

			// MMTR: used to hand this vehicle to the screen-space cab HUD (MmtrCabHudRenderer.setVehicle).
			// The overlay is disabled; the hook returns when a replacement console exists.
		}

		// Check for sensors
		final Vector headPosition = getHeadPositionAndTiltAngle().position();
		for (int xOffset = -1; xOffset <= 1; xOffset++) {
			for (int yOffset = -1; yOffset <= 1; yOffset++) {
				for (int zOffset = -1; zOffset <= 1; zOffset++) {
					final BlockPos offsetBlockPos = Init.newBlockPos(headPosition.x() + xOffset, headPosition.y() + yOffset, headPosition.z() + zOffset);
					final BlockState blockState = clientWorld.getBlockState(offsetBlockPos);
					final Block block = blockState.getBlock();
					if (BlockTrainSensorBase.matchesFilter(new World(clientWorld.data), offsetBlockPos, thisRouteId, speed)) {
						if (block.data instanceof BlockTrainRedstoneSensor && IBlock.getStatePropertySafe(blockState, BlockTrainRedstoneSensor.POWERED) < 2) {
							InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketTurnOnBlockEntity(offsetBlockPos));
						} else if (block.data instanceof BlockTrainAnnouncer && VehicleRidingMovement.isRiding(id)) {
							final BlockEntity blockEntity = clientWorld.getBlockEntity(offsetBlockPos);
							if (blockEntity != null && blockEntity.data instanceof BlockTrainAnnouncer.BlockEntity) {
								((BlockTrainAnnouncer.BlockEntity) blockEntity.data).announce();
							}
						}
					}
				}
			}
		}

		// Oscillation
		double totalLength = 0;
		for (int i = 0; i < vehicleExtraData.immutableVehicleCars.size(); i++) {
			final int currentIndex = Utilities.getIndexFromConditionalList(vehicleExtraData.immutablePath, oldRailProgress - totalLength);
			if (currentIndex >= 0 && currentIndex < vehicleExtraData.immutablePath.size()) {
				final int carNumber = reversed ? vehicleExtraData.immutableVehicleCars.size() - i - 1 : i;

				// When moving
				if (speed * MILLIS_PER_SECOND > 5 && Math.random() < 0.01) {
					persistentVehicleData.getOscillation(carNumber).startOscillation(Math.sqrt(speed) * Math.random());
				}

				// When passing node
				if (railProgress - totalLength >= vehicleExtraData.immutablePath.get(currentIndex).getEndDistance()) {
					persistentVehicleData.getOscillation(carNumber).startOscillation(Math.sqrt(speed) * 2 * (Math.random() + 0.5));
				}

				totalLength += vehicleExtraData.immutableVehicleCars.get(carNumber).getLength();
			}
		}

		// Write signals
		final double padding = 0.5 * speed * speed / vehicleExtraData.getDeceleration() + transportMode.stoppingSpace;
		final int headIndexPadded = Utilities.getIndexFromConditionalList(vehicleExtraData.immutablePath, railProgress + padding);
		final int headIndex = Utilities.getIndexFromConditionalList(vehicleExtraData.immutablePath, railProgress - 1);
		final int endIndex = Utilities.getIndexFromConditionalList(vehicleExtraData.immutablePath, railProgress - vehicleExtraData.getTotalVehicleLength());
		final int endIndexPadded = Utilities.getIndexFromConditionalList(vehicleExtraData.immutablePath, railProgress - vehicleExtraData.getTotalVehicleLength() - padding);
		for (int i = Math.max(0, endIndexPadded); i <= Math.min(vehicleExtraData.immutablePath.size() - 1, headIndexPadded); i++) {
			final PathData pathData = vehicleExtraData.immutablePath.get(i);
			if (i > endIndexPadded && i <= headIndex) {
				MinecraftClientData.getInstance().blockedRailIds.add(pathData.getHexId(false));
			}
			if (i < headIndexPadded && i >= endIndex) {
				MinecraftClientData.getInstance().blockedRailIds.add(pathData.getHexId(true));
			}
		}

		// Write speed limit
		final PathData pathDataHead = Utilities.getElement(vehicleExtraData.immutablePath, headIndex);
		if (pathDataHead != null) {
			speedLimitKilometersPerHour = (int) pathDataHead.getSpeedLimitKilometersPerHour();
		}

		// Write platform stopping position
		platformStoppingDetails = null;
		PathData previousPathData = null;
		for (int i = Math.min(vehicleExtraData.immutablePath.size() - 1, headIndex + 1); i >= 0; i--) {
			final PathData pathData = vehicleExtraData.immutablePath.get(i);

			if (i <= headIndex) {
				if (pathData.getEndDistance() <= railProgress - vehicleExtraData.getTotalVehicleLength() || pathData.getSavedRailBaseId() == vehicleExtraData.getSidingId()) {
					break;
				}

				if (pathData.getDwellTime() > 0) {
					platformStoppingDetails = new DoubleObjectImmutablePair<>(
							pathData.getEndDistance() - railProgress,
							new DoubleDoubleImmutablePair(pathData.getRailLength(), previousPathData != null && previousPathData.isOppositeRail(pathData) ? 0 : vehicleExtraData.getTotalVehicleLength())
					);
					break;
				}
			}

			previousPathData = pathData;
		}
	}

	public ObjectArrayList<ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<Vehicle.BogiePosition>>> getSmoothedVehicleCarsAndPositions(long millisElapsed) {
		/*
		 * ★ **①（运动流）管到的车不再吃这套旧平滑**（notes/369 §6）。
		 *
		 * <p>旧平滑是为"② 每秒才发一次位置"设计的：它把 ② 那一下的差值记成 adjustment，再按
		 * `millisElapsed * speed/10` 慢慢吃掉 —— 于是**渲染位置总是落后于真位置**，低速时落后得尤其久。
		 * ① 一来（10 Hz + 误差阈值），位置本身已经连续，再叠一层"落后"的平滑就是纯滞后：
		 * 2026-10-03 实机里"很卡"就有这一份 —— 车头的位置每 100 ms 被 ① 更新一次，
		 * 而画面读的是那条慢半拍的 smoothed 值。</p>
		 *
		 * <p>误差缓冲（真的要"抹平"时用它）是 S5 的活：那时它替换的就是这一段，而不是叠在上面。</p>
		 */
		if (MmtrVehicleMotionClient.isMotionManaged(getId())) {
			return getVehicleCarsAndPositions();
		}
		final double oldRailProgress = railProgress;
		railProgress = persistentVehicleData.getSmoothedRailProgress(railProgress, persistentVehicleData.getDoorValue() > 0 ? 0 : millisElapsed * (speed == 0 ? Integer.MAX_VALUE : speed / 10));
		final ObjectArrayList<ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<Vehicle.BogiePosition>>> vehicleCarsAndPositions = getVehicleCarsAndPositions();
		railProgress = oldRailProgress;
		return vehicleCarsAndPositions;
	}

	public void playMotorSound(VehicleResource vehicleResource, int carNumber, Vector bogiePosition) {
		persistentVehicleData.playMotorSound(vehicleResource, carNumber, Init.newBlockPos(bogiePosition.x(), bogiePosition.y(), bogiePosition.z()), (float) speed, (float) (speed - oldSpeed), (float) vehicleExtraData.getAcceleration(), getIsOnRoute());
	}

	public void playDoorSound(VehicleResource vehicleResource, int carNumber, Vector vehiclePosition) {
		persistentVehicleData.playDoorSound(vehicleResource, carNumber, Init.newBlockPos(vehiclePosition.x(), vehiclePosition.y(), vehiclePosition.z()));
	}

	public int getSpeedLimitKilometersPerHour() {
		return speedLimitKilometersPerHour;
	}

	public double getSpeed() {
		return speed;
	}

	public boolean isVehiclePastSafeStoppingDistance() {
		return speed > 0 && railProgress + 0.5 * speed * speed / vehicleExtraData.getDeceleration() * POWER_LEVEL_RATIO >= vehicleExtraData.getStoppingPoint();
	}

	@Nullable
	public DoubleObjectImmutablePair<DoubleDoubleImmutablePair> getPlatformStoppingDetails() {
		return platformStoppingDetails;
	}

	private static MutableText getStationText(String text, TranslationProvider.TranslationHolder keyCjk, TranslationProvider.TranslationHolder key) {
		return TextHelper.literal(text.isEmpty() ? "" : IGui.formatStationName(IGui.insertTranslation(keyCjk, key, 1, IGui.textOrUntitled(text))));
	}

	private static String formatRouteName(String routeName) {
		return routeName.split("\\|\\|")[0];
	}

	private static String getInterchangeText(ObjectArrayList<String> names) {
		final ObjectArrayList<String> newNamesCjk = new ObjectArrayList<>();
		final ObjectArrayList<String> newNames = new ObjectArrayList<>();
		names.forEach(name -> {
			for (final String nameSplit : name.split("\\|")) {
				(IGui.isCjk(nameSplit) ? newNamesCjk : newNames).add(nameSplit);
			}
		});
		final String combinedCjk = mergeNames(newNamesCjk, TranslationProvider.GUI_MTR_COMMA_CJK, TranslationProvider.GUI_MTR_COMMA_LAST_CJK);
		final String combined = mergeNames(newNames, TranslationProvider.GUI_MTR_COMMA, TranslationProvider.GUI_MTR_COMMA_LAST);
		return String.format("%s%s%s", combinedCjk, !combinedCjk.isEmpty() && !combined.isEmpty() ? "|" : "", combined);
	}

	private static String mergeNames(ObjectArrayList<String> names, TranslationProvider.TranslationHolder keyComma, TranslationProvider.TranslationHolder keyCommaLast) {
		if (names.isEmpty()) {
			return "";
		} else {
			final StringBuilder stringBuilder = new StringBuilder();
			for (int i = 0; i < names.size(); i++) {
				stringBuilder.append(names.get(i));
				if (i <= names.size() - 3) {
					stringBuilder.append(keyComma.getString());
				} else if (i == names.size() - 2) {
					stringBuilder.append(keyCommaLast.getString());
				}
			}
			return stringBuilder.toString();
		}
	}

	/**
	 * Dispose the vehicle and execute the associated clean-up work, such as stopping vehicle sounds.
	 */
	public void dispose() {
		persistentVehicleData.dispose();
	}

	/**
	 * 这列车底的**牵引档数**（{@code mmtrPowerNotches}）—— 客户端镜像。
	 *
	 * <p>单手柄车底（{@code NOTCHED} / {@code STEPLESS} / {@code AIR_BRAKE}）要在客户端就知道自己这根杆
	 * 有几档，否则杆位要么够不到最后一位、要么越过后被引擎悄悄钳掉。三手柄车底不吃这两个数（它有
	 * {@code mmtrHandleSpec} 那张位置表），读它们也不会有副作用。</p>
	 *
	 * <p>它们是**静态**镜像字段（{@code VehicleSchema} 里 protected，随整份快照下发，故意不在
	 * {@code VehicleSyncPatch} 的增量白名单里 —— 见 {@code VehicleSyncPatchTests.staticFieldsAreNotInTheWhitelist}），
	 * 所以客户端任何一拍读到的都是当前值，不需要额外的同步通道。</p>
	 */
	public int getMmtrPowerNotchesFromSync() { return (int) mmtrPowerNotches; }

	/** 这列车底的**制动档数**（{@code mmtrBrakeNotches}，含"运行/缓解"位）。 */
	public int getMmtrBrakeNotchesFromSync() { return (int) mmtrBrakeNotches; }

	/**
	 * **到目标点（停点）的剩余距离**（米）；{@code < 0} = 现在没有武装的目标点（停放 / 没有任务 / 已到点）。
	 *
	 * <h3>数据从哪来：两个引擎数，一次减法</h3>
	 *
	 * <p>{@code mmtrRunStopTarget}（引擎武装的停点，沿"这次运行的距离坐标"量）与 {@code railProgress}
	 * （本车在该坐标上的位置）**都是引擎发下来的**（{@code VehicleSchema} 里 protected 的镜像字段；
	 * 前者在 {@code VehicleSyncPatch} 的增量白名单里，所以停点一变就推过来）。这里只做一次减法，
	 * 没有任何判定 —— 而"两个数在客户端同一套坐标里"这件事**不是我假定的**：引擎自己就是用这一对数
	 * 判"到点就停"的（{@code Vehicle#simulate} 里 {@code railProgress >= mmtrRunStopTarget} 那一句），
	 * 而那一句正是在**客户端镜像**这条路上跑的（{@code mmtrMotionMirror} 分支）。</p>
	 *
	 * <h3>它有多准（为什么这是"读数"不是"保证量"）</h3>
	 *
	 * <p>客户端镜像的 {@code railProgress} 是**本机积分 + 服务器包纠正 + 平滑**（{@code PersistentVehicleData}），
	 * 与服务器上的真值有差；站在站台/岔口等待时最多约 1 s 的视觉误差（notes/26）。所以它够用来"看还有多远"，
	 * 但**不能当成制动到点位的保证**。若要一个精确到米的权威值，得由引擎算好单独发下来（改 schema +
	 * 同步引擎）—— 那是另一件事，别在这里偷偷加权平均去"凑准"。</p>
	 *
	 * <p>减出负数（镜像跑过停点、或平滑把位置推过了）时**夹到 0**：卡片上一句"到目标点 -3 m"是没有意义的，
	 * 而引擎自己也是用 {@code >=} 夹住的。</p>
	 */
	public double getMmtrDistanceToStopTargetM() {
		return mmtrRunStopTarget < 0 ? -1 : Math.max(0, mmtrRunStopTarget - railProgress);
	}
}