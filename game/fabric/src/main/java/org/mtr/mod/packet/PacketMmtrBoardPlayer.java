package org.mtr.mod.packet;

import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mod.client.MmtrBoardRequest;

/**
 * MMTR **服务端 → 客户端**的"把这位玩家放进某辆车的驾驶室"请求。
 *
 * <h2>为什么这件事必须由客户端做</h2>
 *
 * <p>MTR 的乘车不是原版的 {@code startRiding}：骑乘状态（{@code VehicleRidingMovement} 里的
 * {@code ridingVehicleId} 与车体局部坐标）**只存在于客户端**，而且只有客户端的
 * {@code RenderVehicles → movePlayer} 每帧把玩家钉在车上。服务端把玩家实体挪到座位坐标上，
 * 客户端并不会因此认为自己在车上 —— 所以"传送"与"进入驾驶状态"必须分成两半：
 * <b>服务端负责把玩家挪到车旁/车上（权威位置）</b>，<b>客户端负责建立骑乘并坐进驾驶室</b>
 * （与按 G 进驾驶室走的是同一段 {@code MmtrCabInteraction.enterCab}）。</p>
 *
 * <h2>为什么车上还要等</h2>
 *
 * <p>车辆的客户端镜像（{@code MinecraftClientData.vehicles}）是按玩家位置同步的：玩家在几百格外时
 * 那辆车根本不在镜像里，客户端算不出座位点。所以收到本包后不立刻放弃 —— 交给
 * {@link MmtrBoardRequest} 每拍重试，直到车镜像出现（服务端已经先把人挪过去了，所以很快）。</p>
 */
public final class PacketMmtrBoardPlayer extends PacketRequestResponseBase {

	public PacketMmtrBoardPlayer(PacketBufferReceiver packetBufferReceiver) {
		super(packetBufferReceiver);
	}

	public PacketMmtrBoardPlayer(String content) {
		super(content);
	}

	/**
	 * @param vehicleId 目标车（引擎/游戏端的车辆 id）
	 * @param cabSpec   驾驶室写法 {@code <车厢序号><A|B>}（1 起，例如 {@code 1A} / {@code 3B}）；
	 *                  空串 = 由客户端挑第一个可用的驾驶室
	 */
	public static String contentOf(long vehicleId, String cabSpec) {
		final JsonObject json = new JsonObject();
		// 64 位 id 超过 JS 安全整数，线上统一走字符串（与 web 那条线同一个口径）
		json.addProperty("vehicleId", String.valueOf(vehicleId));
		json.addProperty("cab", cabSpec == null ? "" : cabSpec);
		return json.toString();
	}

	@Override
	protected void runClientInbound(JsonReader jsonReader) {
		final String rawVehicleId = jsonReader.getString("vehicleId", "").trim();
		final long vehicleId;
		try {
			vehicleId = Long.parseLong(rawVehicleId);
		} catch (NumberFormatException e) {
			org.mtr.mod.Init.LOGGER.warn("[MMTR-BOARD] 车 id 无法解析：{}", rawVehicleId);
			return;
		}
		final String cabSpec = jsonReader.getString("cab", "");
		org.mtr.mod.Init.LOGGER.info("[MMTR-BOARD] 收到上车请求：车={} 驾驶室={}", vehicleId, cabSpec.isEmpty() ? "(自动)" : cabSpec);
		MmtrBoardRequest.request(vehicleId, cabSpec);
	}

	@Override
	protected PacketRequestResponseBase getInstance(String content) {
		return new PacketMmtrBoardPlayer(content);
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

	/**
	 * 本包**只**由服务端发往客户端（引擎侧没有 {@code mmtr-board-player} 这个动作）。
	 * 客户端永远不会发它，所以这个 key 只是形状上的占位。
	 */
	@Override
	protected String getKey() {
		return "mmtr-board-player";
	}

	@Override
	protected ResponseType responseType() {
		return ResponseType.NONE;
	}
}
