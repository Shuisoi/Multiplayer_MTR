package org.mtr.mod.packet;

import org.mtr.core.mmtr.duty.MmtrDutyRegistry;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mod.client.MmtrPdaList;

import java.util.List;

/**
 * MMTR **服务端 → 客户端**的"给你一份**全部车次**"（notes/408 §3 的列表页）。
 *
 * <h2>它解决什么</h2>
 * <p>综合运转面板原来自己遍历 {@code MinecraftClientData.vehicles} 造行。那份镜像**按玩家位置同步**
 * （见 {@link PacketMmtrBoardPlayer} 的类注释）：玩家站在几百格外时那辆车根本不在镜像里，
 * 于是面板**天生**只能显示附近几趟车（用户原话："这边 PDA 没有显示全部车次，而是只有附近的车次"）。
 * 名单因此改由**引擎**给（{@code MmtrDutyRegistry.allVehicleRows()}），这条包就是那份名单的载体，
 * 客户端只把它存进 {@link MmtrPdaList} 并显示。</p>
 *
 * <h2>形状</h2>
 * <p>照 {@link PacketMmtrPdaScreen}：{@code extends PacketRequestResponseBase}、JSON {@code content}
 * 承载负载、只覆写 {@code runClientInbound}、{@code getKey()} 是形状上的占位、{@code responseType()}
 * 返回 {@code NONE}（服务端不等回话 —— 面板每秒问一次，回话就是下一份列表）。</p>
 *
 * <h2>两处刻意的实现细节</h2>
 * <ol>
 *   <li><b>负载是一个对象 {@code {"rows":[…]}}，不是裸数组。</b>{@code PacketRequestResponseBase}
 *       给 {@code runClientInbound} 的是一个 {@code JsonReader}，而它由
 *       {@code Utilities.parseJson(content)} 构造 —— 那个方法在顶层不是 JSON 对象时**静默**
 *       返回空对象（它自己 catch 掉了 {@code getAsJsonObject()} 的异常）。裸数组负载会在那里被吃掉，
 *       面板永远停在"正在向引擎取车次列表…"，而且没有任何报错。所以外面裹一层对象。</li>
 *   <li><b>本包自己记下那份负载字符串</b>（{@code payload}）：{@code PacketRequestResponseBase}
 *       只把 {@code content} 留在自己的 private 字段里，子类在 {@code runClientInbound} 里拿不到原文，
 *       而 {@link MmtrPdaList} 的入口是 {@code set(String json)}。收包那一支用
 *       {@code this(packetBufferReceiver.readString())} 把**同一个字符串**取出来交给
 *       {@code (String content)} 那一支 —— 线上格式与基类完全一样（读一个字符串），
 *       没有多发一个字节，也没有第二条解析路径。</li>
 * </ol>
 */
public final class PacketMmtrDutyList extends PacketRequestResponseBase {

	/** 本包携带的那份负载（与基类的 {@code content} 是同一个字符串）。 */
	private final String payload;

	/**
	 * 收包那一支：从缓冲里读出负载，交给 {@code (String content)} 那一支（于是 {@link #payload} 一定是它）。
	 *
	 * <p>读的**就是基类构造函数读的那一个字符串**，位置与数量都不变 —— 只是顺路留个副本，
	 * 否则子类在自己的 {@code runClientInbound} 里够不到它。</p>
	 */
	public PacketMmtrDutyList(PacketBufferReceiver packetBufferReceiver) {
		this(packetBufferReceiver.readString());
	}

	public PacketMmtrDutyList(String content) {
		super(content);
		payload = content;
	}

	/**
	 * 把引擎那份列表编成线上负载。
	 *
	 * <p>字段名与 {@code MmtrDutyRegistry.VehicleRow} 一一对应（见那张表）；64 位车 id 走**字符串**
	 * （与 {@link PacketMmtrBoardPlayer#contentOf(long, String)} 同一个口径：整数在网页/JS 那条线上会丢精度）。</p>
	 */
	public static String contentOf(List<MmtrDutyRegistry.VehicleRow> rows) {
		final JsonArray array = new JsonArray();
		for (final MmtrDutyRegistry.VehicleRow row : rows) {
			final JsonObject json = new JsonObject();
			json.addProperty("vehicleId", String.valueOf(row.vehicleId()));
			json.addProperty("jobId", row.jobId());
			json.addProperty("dutyState", row.dutyState());
			json.addProperty("dutyCrew", row.dutyCrew());
			json.addProperty("taskNote", row.taskNote());
			json.addProperty("nextStation", row.nextStation());
			json.addProperty("speedKmh", row.speedKmh());
			json.addProperty("distanceToStopM", row.distanceToStopM());
			json.addProperty("dutyWaiting", row.dutyWaiting());
			array.add(json);
		}
		final JsonObject content = new JsonObject();
		content.add("rows", array);
		return content.toString();
	}

	@Override
	protected void runClientInbound(JsonReader jsonReader) {
		if (payload == null) {
			// 到不了（两个构造入口都会把 payload 填上）；真出现说明这条包换了收发方式，别静默吞掉。
			org.mtr.mod.Init.LOGGER.warn("[MMTR-DUTY] 车次列表包没有负载 —— 面板保持上一份");
			return;
		}
		MmtrPdaList.set(payload);
	}

	@Override
	protected PacketRequestResponseBase getInstance(String content) {
		return new PacketMmtrDutyList(content);
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
		return "mmtr-duty-list";
	}

	@Override
	protected ResponseType responseType() {
		return ResponseType.NONE;
	}
}
