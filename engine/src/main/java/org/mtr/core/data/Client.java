package org.mtr.core.data;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.longs.Long2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectAVLTreeSet;
import org.jspecify.annotations.Nullable;
import org.mtr.core.generated.data.ClientSchema;
import org.mtr.core.operation.DynamicDataResponse;
import org.mtr.core.operation.PlayerPresentResponse;
import org.mtr.core.operation.VehicleUpdate;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.core.simulation.Simulator;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public class Client extends ClientSchema {

	public final UUID uuid;

	private final LongAVLTreeSet existingVehicleIds = new LongAVLTreeSet();
	private final LongAVLTreeSet keepVehicleIds = new LongAVLTreeSet();
	private final Long2ObjectAVLTreeMap<VehicleUpdate> vehicleUpdates = new Long2ObjectAVLTreeMap<>();
	/**
	 * 这一拍要发的**稀疏补丁**（notes/174）：车辆 id → 只含变化字段的那段 JSON。
	 *
	 * <p>与 {@link #vehicleUpdates} 是同一件事的两档：整份快照只在"客户端第一次看到这辆车"或
	 * **静态字段**变了（换交路/改编组/path 换了）时发；其余动态变化走这里。
	 * 两者都算"这个客户端现在持有这辆车"，所以都要进 {@link #existingVehicleIds} ——
	 * 否则下一拍它会被判成"要删掉"。</p>
	 */
	private final Long2ObjectAVLTreeMap<JsonObject> vehiclePatches = new Long2ObjectAVLTreeMap<>();

	private final LongAVLTreeSet existingLiftIds = new LongAVLTreeSet();
	private final LongAVLTreeSet keepLiftIds = new LongAVLTreeSet();
	private final Long2ObjectAVLTreeMap<Lift> liftUpdates = new Long2ObjectAVLTreeMap<>();

	private final ObjectAVLTreeSet<String> existingRailIds = new ObjectAVLTreeSet<>();
	private final ObjectAVLTreeSet<String> keepRailIds = new ObjectAVLTreeSet<>();
	private final Object2ObjectAVLTreeMap<String, Rail> signalBlockUpdates = new Object2ObjectAVLTreeMap<>();

	private final LongAVLTreeSet existingPassengerIds = new LongAVLTreeSet();
	private final LongAVLTreeSet keepPassengerIds = new LongAVLTreeSet();
	private final Long2ObjectAVLTreeMap<Passenger> passengerUpdates = new Long2ObjectAVLTreeMap<>();

	/**
	 * Create a new client with the given unique identifier.
	 *
	 * @param uuid client UUID used for tracking across the mod and dashboard
	 */
	public Client(UUID uuid) {
		super(uuid.toString());
		this.uuid = uuid;
	}

	/**
	 * Deserialisation constructor used by the wire / on-disk layer.
	 */
	public Client(ReaderBase readerBase) {
		super(readerBase);
		updateData(readerBase);
		uuid = UUID.fromString(clientId);
	}

	@Override
	protected Position getDefaultPosition() {
		return new Position(0, 0, 0);
	}

	public Position getPosition() {
		return position;
	}

	public double getUpdateRadius() {
		return updateRadius;
	}

	/**
	 * Update the client's tracked position and area-of-interest radius. Entities within this
	 * radius will be pushed to the client on the next {@link #sendUpdates} cycle.
	 */
	public void setPositionAndUpdateRadius(Position position, long updateRadius) {
		this.position = position;
		this.updateRadius = updateRadius;
	}

	/**
	 * Send pending vehicle / lift / passenger / signal-block updates to the client via the
	 * server-to-client message queue.
	 */
	public void sendUpdates(Simulator simulator) {
		final DynamicDataResponse dynamicDataResponse = new DynamicDataResponse(uuid, simulator);
		final boolean hasUpdate1 = processVehicles(dynamicDataResponse);
		final boolean hasUpdate2 = process(liftUpdates, existingLiftIds, keepLiftIds, dynamicDataResponse::addLiftToUpdate, dynamicDataResponse::addLiftToKeep);
		final boolean hasUpdate3 = process(signalBlockUpdates, existingRailIds, keepRailIds, dynamicDataResponse::addSignalBlockUpdate, railId -> {
		});
		final boolean hasUpdate4 = process(passengerUpdates, existingPassengerIds, keepPassengerIds, dynamicDataResponse::addPassengerToUpdate, dynamicDataResponse::addPassengerToKeep);

		if (hasUpdate1 || hasUpdate2 || hasUpdate3 || hasUpdate4) {
			simulator.sendMessageS2C(OperationProcessor.VEHICLES_LIFTS, dynamicDataResponse, playerPresentResponse -> playerPresentResponse.verify(simulator, uuid), PlayerPresentResponse.class);
		}
	}

	/**
	 * 车辆那一档的三路分派：**整份快照 / 稀疏补丁 / 保活**（notes/174）。
	 *
	 * <p>与通用 {@link #process} 的两处不同：①多了"补丁"这一路，它同样意味着"这个客户端现在持有
	 * 这辆车"，所以必须一起进 {@link #existingVehicleIds}；②"要不要删"仍由"这一拍发出去的三样
	 * 加起来"决定 —— 少算了任何一路，客户端就会把还在视野里的车删掉（一亮一灭）。</p>
	 */
	private boolean processVehicles(DynamicDataResponse dynamicDataResponse) {
		vehicleUpdates.forEach((vehicleId, vehicleUpdate) -> {
			dynamicDataResponse.addVehicleToUpdate(vehicleUpdate);
			existingVehicleIds.remove(vehicleId);
		});

		vehiclePatches.forEach((vehicleId, patch) -> {
			dynamicDataResponse.addVehicleToPatch(vehicleId, patch.toString());
			/*
			 * ★ **补丁必须同时保活**（notes/174 的实机回归，2026-09-16）：
			 * 客户端那条镜像的规则是"**这一条消息里没出现的车就删掉**"
			 * （`PacketUpdateVehiclesLifts.updateVehiclesOrLifts` 的 removeIf，删的时候还会
			 * dispose 掉按节数缓存的渲染数据）。所以只放进补丁列表的话，客户端每收到一次补丁
			 * 就把这辆车删掉、渲染缓存重建 —— 现场表现是**列车一亮一灭、非常卡**，
			 * 而且要等到 30 秒后的兜底整份才恢复。
			 */
			dynamicDataResponse.addVehicleToKeep(vehicleId);
			existingVehicleIds.remove(vehicleId);
		});

		keepVehicleIds.forEach(vehicleId -> {
			dynamicDataResponse.addVehicleToKeep(vehicleId);
			existingVehicleIds.remove(vehicleId);
		});

		final boolean hasUpdate = !existingVehicleIds.isEmpty() || !vehicleUpdates.isEmpty() || !vehiclePatches.isEmpty();

		existingVehicleIds.clear();
		existingVehicleIds.addAll(vehicleUpdates.keySet());
		existingVehicleIds.addAll(vehiclePatches.keySet());
		existingVehicleIds.addAll(keepVehicleIds);

		vehicleUpdates.clear();
		vehiclePatches.clear();
		keepVehicleIds.clear();
		return hasUpdate;
	}

	/**
	 * Track a vehicle for the next {@link #sendUpdates} cycle. If the vehicle is new or
	 * dirty it is queued for a full update; otherwise it is kept alive so the client knows not
	 * to remove it.
	 *
	 * @param vehicle         the vehicle to track
	 * @param needsUpdate     whether the vehicle's state has changed since the last sync
	 * @param pathUpdateIndex index into the vehicle's path data for partial updates
	 * @param patch           {@link org.mtr.core.operation.VehicleSyncPatch} 产出的稀疏补丁；
	 *                        {@code null} = 发整份快照（静态变了或第一次），**空对象 = 一处都没变**
	 */
	public void update(Vehicle vehicle, boolean needsUpdate, int pathUpdateIndex, @Nullable JsonObject patch) {
		final long vehicleId = vehicle.getId();
		if (needsUpdate || !existingVehicleIds.contains(vehicleId)) {
			final boolean clientAlreadyHasIt = existingVehicleIds.contains(vehicleId);
			if (patch != null && patch.size() > 0 && clientAlreadyHasIt) {
				// 客户端已经持有这辆车、而这一拍只有动态字段变了 ⇒ 发补丁（原地合并）
				vehiclePatches.put(vehicleId, patch);
				vehicleUpdates.remove(vehicleId);
				keepVehicleIds.remove(vehicleId);
			} else if (!clientAlreadyHasIt) {
				// 第一次看到（或重新进视野）：客户端要重建镜像 ⇒ 整份快照。
				// 这一支**必须**排在"空补丁"之前：新客户端 + 空补丁仍然要给整份，
				// 否则它永远建不起镜像（那里的"空"说的是"与我上次发的一样"，不是"不用发"）。
				vehicleUpdates.put(vehicleId, new VehicleUpdate(vehicle, vehicle.vehicleExtraData.copy(pathUpdateIndex)));
				vehiclePatches.remove(vehicleId);
				keepVehicleIds.remove(vehicleId);
			} else {
				/*
				 * ★ **标脏了、但一份都不差 ⇒ 与"没脏"同路（保活）**（notes/368 §2(2)）。
				 *
				 * <p>{@code checkForUpdate()} 里有一项是"标脏标志本身"（{@code hasRidingEntityUpdate}），
				 * 而 {@code VehicleSyncPatch.patchOf} 在逐字段比对下来一处都没变时返回的是**空对象**
				 * （它自己那句 early return）。原来这里只看 {@code patch.size() > 0}，空对象于是掉进
				 * "整份"那一支 —— 现场就是"车停着不动，服务器每秒往外扔一份 7.4 KB 整份快照"。</p>
				 *
				 * <p>"空补丁"的正确语义 = **这一拍没有东西要发**，不是"发整份"。</p>
				 */
				if (!vehicleUpdates.containsKey(vehicleId) && !vehiclePatches.containsKey(vehicleId)) {
					keepVehicleIds.add(vehicleId);
				}
			}
		} else if (!vehicleUpdates.containsKey(vehicleId) && !vehiclePatches.containsKey(vehicleId)) {
			keepVehicleIds.add(vehicleId);
		}
	}

	/**
	 * 兼容旧调用方（无补丁）：等价于"这一次发整份快照"。
	 *
	 * @deprecated 新增调用方请用 {@link #update(Vehicle, boolean, int, JsonObject)}；
	 * 保留它只是为了不动升降机/信号那些不在这条协议里的路径。
	 */
	@Deprecated
	public void update(Vehicle vehicle, boolean needsUpdate, int pathUpdateIndex) {
		update(vehicle, needsUpdate, pathUpdateIndex, null);
	}

	/**
	 * Track a lift for the next {@link #sendUpdates} cycle.
	 *
	 * @param lift        the lift to track
	 * @param needsUpdate whether the lift's state has changed since the last sync
	 */
	public void update(Lift lift, boolean needsUpdate) {
		final long liftId = lift.getId();
		if (needsUpdate || !existingLiftIds.contains(liftId)) {
			liftUpdates.put(liftId, lift);
			keepLiftIds.remove(liftId);
		} else if (!liftUpdates.containsKey(liftId)) {
			keepLiftIds.add(liftId);
		}
	}

	/**
	 * Track a rail for signal-block updates on the next {@link #sendUpdates} cycle.
	 *
	 * @param rail        the rail to track
	 * @param needsUpdate whether the rail's signal-block state has changed since the last sync
	 */
	public void update(Rail rail, boolean needsUpdate) {
		final String railId = rail.getHexId();
		if (needsUpdate || !existingRailIds.contains(railId)) {
			signalBlockUpdates.put(railId, rail);
			keepRailIds.remove(railId);
		} else if (!signalBlockUpdates.containsKey(railId)) {
			keepRailIds.add(railId);
		}
	}

	/**
	 * Track a passenger for the next {@link #sendUpdates} cycle. If the passenger is new or
	 * dirty it is queued for a full update; otherwise it is kept alive so the client knows not
	 * to remove it.
	 *
	 * @param passenger   the passenger to track
	 * @param needsUpdate whether the passenger's state has changed since the last sync
	 */
	public void update(Passenger passenger, boolean needsUpdate) {
		final long passengerId = passenger.getId();
		if (needsUpdate || !existingPassengerIds.contains(passengerId)) {
			passengerUpdates.put(passengerId, passenger);
			keepPassengerIds.remove(passengerId);
		} else if (!passengerUpdates.containsKey(passengerId)) {
			keepPassengerIds.add(passengerId);
		}
	}

	private static <T, U extends SerializedDataBase> boolean process(Map<T, U> dataUpdates, Set<T> existingIds, Set<T> keepIds, Consumer<U> addDataToUpdate, Consumer<T> addDataToKeep) {
		dataUpdates.forEach((id, data) -> {
			addDataToUpdate.accept(data);
			existingIds.remove(id);
		});

		keepIds.forEach(id -> {
			addDataToKeep.accept(id);
			existingIds.remove(id);
		});

		// Has data to remove or has data to update
		final boolean hasUpdate = !existingIds.isEmpty() || !dataUpdates.isEmpty();

		existingIds.clear();
		existingIds.addAll(dataUpdates.keySet());
		existingIds.addAll(keepIds);

		dataUpdates.clear();
		keepIds.clear();
		return hasUpdate;
	}
}
