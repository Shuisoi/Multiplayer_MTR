package org.mtr.mod.packet;

import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.mapping.tool.PacketBufferReceiver;

/**
 * MMTR **服务端 → 客户端**的"打开综合运转面板"请求（notes/408 §3）。
 *
 * <p>形状照 {@link PacketMmtrBoardPlayer}：只由服务端发、{@code getKey()} 是形状上的占位、
 * {@code responseType() = NONE}。真正开屏那一段交给 {@link ClientPacketHelper}（客户端专有类，
 * 由 {@code runClientInbound} 惰性加载）—— 与 {@code PacketOpenDashboardScreen} 同一条路。</p>
 */
public final class PacketMmtrPdaScreen extends PacketRequestResponseBase {

	public PacketMmtrPdaScreen(PacketBufferReceiver packetBufferReceiver) {
		super(packetBufferReceiver);
	}

	public PacketMmtrPdaScreen(String content) {
		super(content);
	}

	/**
	 * @param drivePage {@code true} = 直接翻到"我在开的那趟车"那一页（驾驶中用）；
	 *                  {@code false} = 车次列表页
	 */
	public static String contentOf(boolean drivePage) {
		final JsonObject json = new JsonObject();
		json.addProperty("drivePage", drivePage);
		return json.toString();
	}

	@Override
	protected void runClientInbound(JsonReader jsonReader) {
		ClientPacketHelper.openPdaScreen(jsonReader.getBoolean("drivePage", false));
	}

	@Override
	protected PacketRequestResponseBase getInstance(String content) {
		return new PacketMmtrPdaScreen(content);
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

	/** 本包只由服务端发往客户端（引擎侧没有这个动作），所以 key 只是形状上的占位。 */
	@Override
	protected String getKey() {
		return "mmtr-pda-screen";
	}

	@Override
	protected ResponseType responseType() {
		return ResponseType.NONE;
	}
}
