package org.mtr.mod.packet;

import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.MinecraftServerHelper;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.Init;
import org.mtr.mod.mmtr.MmtrLoadProbe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Sends a round trip request/response. This is meant to be used on the Minecraft client only.
 * <p>
 * Minecraft client -> Minecraft server -> Transport Simulation Core -> Minecraft server -> Minecraft client
 */
public abstract class PacketRequestResponseBase extends PacketHandler {

	private final String content;

	public PacketRequestResponseBase(PacketBufferReceiver packetBufferReceiver) {
		content = packetBufferReceiver.readString();
	}

	public PacketRequestResponseBase(String content) {
		this.content = content;
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeString(content);
	}

	@Override
	public final void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		runServerOutbound(serverPlayerEntity.getServerWorld(), serverPlayerEntity);
	}

	/**
	 * MMTR 取证（notes/410）：这条路上**解析**与**落地**分开计时。
	 *
	 * <p>为什么要分：{@code DataResponse} 一份落地 = {@code parseJson(content)}（整份 JSON 建对象图）
	 * + {@code DataResponse.write()}（{@code rails.removeIf/addAll} → {@code data.sync()}：全量重建
	 * positionsToRail、各 id 映射、站台与站关系）。现场"经过节点卡一下"的 300ms 里这两段各占多少，
	 * 决定了该修哪一段 —— 只记一个总数会让我们一起改错地方。探针不改变任何顺序与副作用。</p>
	 */
	@Override
	public final void runClient() {
		final long parseStartNanos = MmtrLoadProbe.begin();
		final JsonReader jsonReader = new JsonReader(Utilities.parseJson(content));
		final long parseNanos = MmtrLoadProbe.elapsed(parseStartNanos);
		final long applyStartNanos = MmtrLoadProbe.begin();
		try {
			runClientInbound(jsonReader);
		} finally {
			MmtrLoadProbe.packetApplied(getClass().getSimpleName(), content.length(), parseNanos, MmtrLoadProbe.elapsed(applyStartNanos));
		}
	}

	protected void runServerOutbound(ServerWorld serverWorld, @Nullable ServerPlayerEntity serverPlayerEntity) {
		Init.sendMessageC2S(getKey(), serverWorld.getServer(), new World(serverWorld.data), getDataInstance(new JsonReader(Utilities.parseJson(content))), responseType() == ResponseType.NONE ? null : responseData -> {
			final JsonObject responseJson = Utilities.getJsonObjectFromData(responseData);
			if (responseType() == ResponseType.PLAYER) {
				if (serverPlayerEntity != null) {
					Init.REGISTRY.sendPacketToClient(serverPlayerEntity, getInstance(responseJson.toString()));
				}
			} else {
				MinecraftServerHelper.iteratePlayers(serverWorld, serverPlayerEntityNew -> Init.REGISTRY.sendPacketToClient(serverPlayerEntityNew, getInstance(responseJson.toString())));
			}
			runServerInbound(serverWorld, responseJson);
		}, SerializedDataBase.class);
	}

	protected void runServerInbound(ServerWorld serverWorld, JsonObject jsonObject) {
	}

	protected void runClientInbound(JsonReader jsonReader) {
	}

	/**
	 * @param content the content being sent from the Minecraft server to the Minecraft client
	 * @return an instance of the packet (should be constructed using {@link #PacketRequestResponseBase(String)})
	 */
	protected abstract PacketRequestResponseBase getInstance(String content);

	/**
	 * MMTR 取证（notes/177）：这条包文的原始 JSON 长度。
	 *
	 * <p>整份车辆快照实测 3.5 KB、稀疏补丁只有几十字节 —— 现场排查"到底在发哪一种"时，
	 * 光看条数是不够的（一条整份顶一百条补丁），所以把字节数一起记下来。</p>
	 */
	protected final int mmtrContentLength() {
		return content.length();
	}

	protected abstract SerializedDataBase getDataInstance(JsonReader jsonReader);

	@Nonnull
	protected abstract String getKey();

	/**
	 * If a response is needed, override {@link #runClient()}.
	 *
	 * @return whether this request expects a response from the POST request
	 */
	protected abstract ResponseType responseType();

	protected enum ResponseType {
		NONE, PLAYER, ALL
	}
}
