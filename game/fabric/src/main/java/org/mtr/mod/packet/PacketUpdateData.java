package org.mtr.mod.packet;

import org.mtr.core.data.PathData;
import org.mtr.core.data.Rail;
import org.mtr.core.data.SignalModification;
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.core.operation.UpdateDataResponse;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.core.tool.Utilities;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mapping.mapper.MinecraftServerHelper;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mod.Init;
import org.mtr.mod.client.DynamicTextureCache;
import org.mtr.mod.client.MinecraftClientData;

import javax.annotation.Nonnull;

public final class PacketUpdateData extends PacketRequestResponseBase {

	public PacketUpdateData(PacketBufferReceiver packetBufferReceiver) {
		super(packetBufferReceiver);
	}

	public PacketUpdateData(UpdateDataRequest updateDataRequest) {
		super(Utilities.getJsonObjectFromData(updateDataRequest).toString());
	}

	private PacketUpdateData(String content) {
		super(content);
	}

	@Override
	protected void runClientInbound(JsonReader jsonReader) {
		update(jsonReader);
	}

	@Override
	protected PacketRequestResponseBase getInstance(String content) {
		return new PacketUpdateData(content);
	}

	@Override
	protected SerializedDataBase getDataInstance(JsonReader jsonReader) {
		return new UpdateDataRequest(jsonReader, new MinecraftClientData());
	}

	@Nonnull
	@Override
	protected String getKey() {
		return OperationProcessor.UPDATE_DATA;
	}

	@Override
	protected PacketRequestResponseBase.ResponseType responseType() {
		return PacketRequestResponseBase.ResponseType.ALL;
	}

	public static void sendDirectlyToServerRail(ServerWorld serverWorld, Rail rail) {
		new PacketUpdateData(new UpdateDataRequest(new MinecraftClientData()).addRail(rail)).runServerOutbound(serverWorld, null);
	}

	public static void sendDirectlyToServerSignalModification(ServerWorld serverWorld, SignalModification signalModification) {
		new PacketUpdateData(new UpdateDataRequest(new MinecraftClientData()).addSignalModification(signalModification)).runServerOutbound(serverWorld, null);
	}

	/**
	 * MMTR：把一份**静态数据增量**（{@code UpdateDataResponse}）直接推给这个世界的所有玩家。
	 *
	 * <p>这条路与"玩家改了东西 → 引擎回一份 {@code UpdateDataResponse} → 广播给所有玩家"是**同一条**
	 * （{@link PacketRequestResponseBase#runServerOutbound} 里 {@code ResponseType.ALL} 那一支发的就是这个类、
	 * 这个载荷），无非是发起方不是玩家而是服务端自己。客户端落地走 {@code UpdateDataResponse.write()}：
	 * 载荷里出现的每一条**原地替换**镜像对象，所以只放"变了的那几条"就够。</p>
	 *
	 * <p>两个调用方：①引擎那边的 depot 生成状态更新（{@code GENERATION_STATUS_UPDATE}）；
	 * ②站台客量变了（notes/416 客流方案 A，见 {@code MmtrCrowdModule#syncCrowdLevelsToClients}）——
	 * 客量是"已经下发过的对象的字段"，客户端不会再来要第二次（{@code DataRequest} 对已知 id 只回 id），
	 * 不推就永远停在旧值上。</p>
	 *
	 * <p>历史名字是 {@code sendDirectlyToClientDepotUpdate}（那时只有 depot 生成在用），
	 * 2026-10-12 改成现在这个名字：它推的是"静态数据"，与 depot 无关。</p>
	 */
	public static void sendDirectlyToClientStaticData(ServerWorld serverWorld, UpdateDataResponse updateDataResponse) {
		MinecraftServerHelper.iteratePlayers(serverWorld, serverPlayerEntityNew -> Init.REGISTRY.sendPacketToClient(serverPlayerEntityNew, new PacketUpdateData(Utilities.getJsonObjectFromData(updateDataResponse).toString())));
	}

	private static void update(JsonReader jsonReader) {
		final MinecraftClientData minecraftClientData = MinecraftClientData.getInstance();
		new UpdateDataResponse(jsonReader, minecraftClientData).write();
		new UpdateDataResponse(jsonReader, MinecraftClientData.getDashboardInstance()).write();
		minecraftClientData.vehicles.forEach(vehicle -> PathData.writePathCache(vehicle.vehicleExtraData.immutablePath, new MinecraftClientData(), vehicle.getTransportMode()));
		DynamicTextureCache.instance.refresh();
	}
}
