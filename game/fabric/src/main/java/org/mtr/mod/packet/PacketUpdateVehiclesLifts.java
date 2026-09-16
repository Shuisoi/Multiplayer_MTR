package org.mtr.mod.packet;

import org.mtr.core.data.NameColorDataBase;
import org.mtr.core.data.PathData;
import org.mtr.core.operation.DynamicDataResponse;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.mtr.mapping.mapper.EntityHelper;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mod.Init;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.RenderVehicles;

import javax.annotation.Nonnull;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.ToLongFunction;

public final class PacketUpdateVehiclesLifts extends PacketRequestResponseBase {

	/** notes/177：包频率上限（毫秒），防止这行诊断自己变成刷屏源。 */
	private static final long PACKET_LOG_INTERVAL_MILLIS = 1000L;
	private static long mmtrPacketLogMillis;

	public PacketUpdateVehiclesLifts(PacketBufferReceiver packetBufferReceiver) {
		super(packetBufferReceiver);
	}

	public PacketUpdateVehiclesLifts(DynamicDataResponse vehicleLiftResponse) {
		super(Utilities.getJsonObjectFromData(vehicleLiftResponse).toString());
	}

	private PacketUpdateVehiclesLifts(String content) {
		super(content);
	}

	@Override
	protected void runClientInbound(JsonReader jsonReader) {
		final MinecraftClientData minecraftClientData = MinecraftClientData.getInstance();
		final DynamicDataResponse vehicleLiftResponse = new DynamicDataResponse(jsonReader, minecraftClientData);
		/*
		 * notes/177 取证：整份 / 补丁 / 保活各自的条数要分开数。
		 *
		 * 为什么：`hasUpdate1` 是个布尔，它把"收到 7 份整份快照（客户端会把 7 个镜像连同渲染缓存全部重建）"
		 * 和"收到 1 份"混在一起，而这两种情况的现场表现差别巨大（前者一卡一卡，后者正常）。
		 * 计数只在这里加，不动同步逻辑。
		 */
		final int[] fullCount = {0};
		final int[] keepCount = {0};
		/*
		 * 注意这里的写法**必须**保留 `vehicleLiftResponse::iterateVehiclesToUpdate` 这个方法引用：
		 * {@code updateVehiclesOrLifts} 的 {@code U} 只从它身上推得出来（{@code Consumer<Consumer<U>>} →
		 * U = VehicleUpdate）。把它换成 lambda 会让 U 退化成 Object，编译直接不通过
		 * （现场吃过：`错误: 不兼容的类型: Object无法转换为VehicleUpdate`）。
		 * 计数因此放在 `createInstance` 里 —— 它每建一个镜像正好被调一次。
		 */
		final boolean hasUpdate1 = updateVehiclesOrLifts(minecraftClientData.vehicles, keep -> vehicleLiftResponse.iterateVehiclesToKeep(vehicleId -> {
			keepCount[0]++;
			keep.accept(vehicleId);
		}), vehicleLiftResponse::iterateVehiclesToUpdate, VehicleExtension::dispose, vehicleUpdate -> vehicleUpdate.getVehicle().getId(), vehicleUpdate -> {
			fullCount[0]++;
			return new VehicleExtension(vehicleUpdate, minecraftClientData);
		});
		final boolean hasUpdate2 = updateVehiclesOrLifts(minecraftClientData.lifts, vehicleLiftResponse::iterateLiftsToKeep, vehicleLiftResponse::iterateLiftsToUpdate, (removedLift) -> {
		}, NameColorDataBase::getId, lift -> lift);

		/*
		 * **稀疏补丁**（notes/174）：静态字段没变的那些拍，服务端只发"这一拍变了的字段"，
		 * 这里**原地合并**进客户端镜像（`VehicleExtension.updateData` 就是为部分更新留的那个口）。
		 *
		 * 为什么这样能省：整份快照实测 3,249 字符（其中九成是路线名/站名/终点/编组/path 这类静态字段），
		 * 而"停车点变了"这一拍只有 33 字符。
		 *
		 * 合并的**时机**：必须在整份更新之后 —— 补丁指的是"客户端已经持有的那辆车"。
		 * 认不出车（理论上不该发生：服务端对"客户端没持有的车"一律发整份）时**不静默吞掉**，
		 * 记一行 debug 便于现场排查，兜底是服务端每 30 秒的那次强制整份。
		 */
		final int[] patchCount = {0};
		final int[] patchMissCount = {0};
		vehicleLiftResponse.iterateVehiclesToPatch(vehiclePatch -> {
			// 注意用的是**重定位后**的 gson（mod 侧看引擎 jar 里的类型一律是 org.mtr.libraries.*）。
			final org.mtr.libraries.com.google.gson.JsonObject patchJson = Utilities.parseJson(vehiclePatch.getPatch());
			final VehicleExtension mirror = minecraftClientData.vehicles.stream().filter(vehicle -> vehicle.getId() == vehiclePatch.getVehicleId()).findFirst().orElse(null);
			if (mirror == null) {
				// 没有这辆车：不猜、不错合并，只记一笔（服务端的 30 秒整份兜底会把它补回来）。
				patchMissCount[0]++;
				org.mtr.core.mmtr.MmtrTrace.log("[MMTR-CL] 收到补丁但本地没有这辆车 id=" + vehiclePatch.getVehicleId() + " patch=" + vehiclePatch.getPatch());
			} else {
				mirror.updateData(patchJson);
				patchCount[0]++;
			}
		});

		vehicleLiftResponse.iterateSignalBlockUpdates(signalBlockUpdate -> {
			minecraftClientData.railIdToPreBlockedSignalColors.put(signalBlockUpdate.getRailId(), signalBlockUpdate.getPreBlockedSignalColors());
			minecraftClientData.railIdToCurrentlyBlockedSignalColors.put(signalBlockUpdate.getRailId(), signalBlockUpdate.getCurrentlyBlockedSignalColors());
		});

		/*
		 * notes/177：这一行从"只在 trace 开关打开时可见"改成**默认可见、每秒最多一行**。
		 *
		 * 为什么要默认可见：实机排查"人抽搐 / 车一亮一灭"时，最先要回答的问题就是"服务端这一拍到底发了
		 * 整份还是补丁、客户端镜像有几辆车"，而刻意的 trace 开关意味着**每次都要重开一次现场**——
		 * 而现场往往只出现一次。每秒一行的量级（约 2 条/秒的实际包频）不会淹掉真正的状态日志。
		 */
		final long nowMillis = System.currentTimeMillis();
		if (nowMillis - mmtrPacketLogMillis >= PACKET_LOG_INTERVAL_MILLIS) {
			mmtrPacketLogMillis = nowMillis;
			Init.LOGGER.info("[MMTR-CL] 车辆包：整份={} 补丁={} 补丁失配={} 保活={} 客户端镜像={} 字节={}", fullCount[0], patchCount[0], patchMissCount[0], keepCount[0], minecraftClientData.vehicles.size(), mmtrContentLength());
		}
		if (hasUpdate1 || hasUpdate2 || patchCount[0] > 0) {
			if (hasUpdate1) {
				EntityHelper.HIDDEN_PLAYERS.clear();
				minecraftClientData.vehicles.forEach(vehicle -> {
					PathData.writePathCache(vehicle.vehicleExtraData.immutablePath, new MinecraftClientData(), vehicle.getTransportMode());
					vehicle.vehicleExtraData.iterateRidingEntities(vehicleRidingEntity -> EntityHelper.HIDDEN_PLAYERS.add(vehicleRidingEntity.uuid));
				});
				RenderVehicles.RIDING_PLAYER_INTERPOLATIONS.removeIf(ridingPlayerInterpolation -> EntityHelper.HIDDEN_PLAYERS.stream().noneMatch(uuid -> uuid.equals(ridingPlayerInterpolation.uuid)));
			}
			minecraftClientData.sync();
		}
	}

	@Override
	protected PacketRequestResponseBase getInstance(String content) {
		return new PacketUpdateVehiclesLifts(content);
	}

	@Override
	protected SerializedDataBase getDataInstance(JsonReader jsonReader) {
		return new SerializedDataBase() {
			@Override
			public void updateData(ReaderBase readerBase) {
			}

			@Override
			public void serializeData(WriterBase writerBase) {
			}
		};
	}

	@Nonnull
	@Override
	protected String getKey() {
		return OperationProcessor.UPDATE_DATA;
	}

	@Override
	protected PacketRequestResponseBase.ResponseType responseType() {
		return PacketRequestResponseBase.ResponseType.NONE;
	}

	private static <T extends NameColorDataBase, U> boolean updateVehiclesOrLifts(ObjectArraySet<T> dataSet, Consumer<LongConsumer> iterateKeep, Consumer<Consumer<U>> iterateUpdate, Consumer<T> onRemove, ToLongFunction<U> getId, Function<U, T> createInstance) {
		final LongAVLTreeSet keepIds = new LongAVLTreeSet();
		iterateKeep.accept(keepIds::add);

		final LongAVLTreeSet updateIds = new LongAVLTreeSet();
		final ObjectArrayList<U> dataSetToUpdate = new ObjectArrayList<>();
		iterateUpdate.accept(dataToUpdate -> {
			dataSetToUpdate.add(dataToUpdate);
			updateIds.add(getId.applyAsLong(dataToUpdate));
		});

		final Long2ObjectOpenHashMap<T> removedItems = new Long2ObjectOpenHashMap<>();
		final boolean itemRemoved = dataSet.removeIf(data -> {
			boolean shouldBeRemoved = !keepIds.contains(data.getId());
			if (shouldBeRemoved) {
				removedItems.put(data.getId(), data);
			}
			return shouldBeRemoved || updateIds.contains(data.getId());
		});
		dataSetToUpdate.forEach(dataToUpdate -> dataSet.add(createInstance.apply(dataToUpdate)));
		dataSet.forEach(e -> removedItems.remove(e.getId()));
		removedItems.values().forEach(onRemove);

		return !dataSetToUpdate.isEmpty() || itemRemoved;
	}
}