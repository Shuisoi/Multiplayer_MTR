package org.mtr.mod.client;

import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * **综合运转面板（PDA）车次列表的客户端缓存**（{@code PacketMmtrDutyList} 的落点）。
 *
 * <h2>它解决什么</h2>
 * <p>面板原来自己遍历 {@code MinecraftClientData.vehicles} 造行 —— 那份镜像是**按玩家位置同步**的
 * （见 {@code PacketMmtrBoardPlayer} 的类注释），玩家站在几百格外时车根本不在里面，
 * 于是列表**天生**只能显示附近几趟车。现在名单只有一个来源：**引擎**
 * （{@code MmtrDutyRegistry.allVehicleRows()}），经 {@code PacketMmtrDutyList} 送到这里，
 * 界面只读 {@link #rows()} —— 客户端不推断"场上有哪些车次"。</p>
 *
 * <h2>为什么是静态缓存 + 整份替换</h2>
 * <p>界面每拍都读它（{@code PdaScreen.tick2}），而包是每秒来一份：<b>一次换掉整份</b>意味着
 * 界面读到的永远是完整的一帧，不会读到"一半是新车、一半是旧车"的行序。列表是只读显示，
 * 没有任何一方在这里写引擎状态。</p>
 */
public final class MmtrPdaList {

	/** 引擎给的**一行 = 一趟车**；字段名与 {@code PacketMmtrDutyList} 的负载一一对应。 */
	public static final class Row {
		private final long vehicleId;
		private final String jobId;
		private final String dutyState;
		private final String dutyCrew;
		private final String taskNote;
		private final String nextStation;
		private final double speedKmh;
		private final double distanceToStopM;
		private final int dutyWaiting;

		private Row(long vehicleId, String jobId, String dutyState, String dutyCrew, String taskNote, String nextStation,
				double speedKmh, double distanceToStopM, int dutyWaiting) {
			this.vehicleId = vehicleId;
			this.jobId = jobId;
			this.dutyState = dutyState;
			this.dutyCrew = dutyCrew;
			this.taskNote = taskNote;
			this.nextStation = nextStation;
			this.speedKmh = speedKmh;
			this.distanceToStopM = distanceToStopM;
			this.dutyWaiting = dutyWaiting;
		}

		public long vehicleId() {
			return vehicleId;
		}

		/** 车次号（作业单名）；引擎那份列表里不会有空串。 */
		public String jobId() {
			return jobId;
		}

		/** 引擎状态名（{@code MmtrDutyRegistry.State.name()}）；空串 = 无人值守。 */
		public String dutyState() {
			return dutyState;
		}

		/** 值守人的 uuid 字符串；空串 = 无人。判"是不是我"用 {@link MmtrDutyView#isMine(String, String)}。 */
		public String dutyCrew() {
			return dutyCrew;
		}

		/** 当前这一步的人话（引擎算好的原话）。 */
		public String taskNote() {
			return taskNote;
		}

		/** 下一站站名；空串 = 后面不再有站台作业。 */
		public String nextStation() {
			return nextStation;
		}

		public double speedKmh() {
			return speedKmh;
		}

		/** 到停车点的距离（m）；{@code < 0} = 本趟没有停车目标。 */
		public double distanceToStopM() {
			return distanceToStopM;
		}

		public int dutyWaiting() {
			return dutyWaiting;
		}
	}

	private static List<Row> rows = Collections.emptyList();
	/** 最后一次**成功**替换这份列表的时刻（{@code 0} = 还没收到过任何一份）。 */
	private static long receivedAtMillis;

	private MmtrPdaList() {
	}

	/**
	 * 收下引擎那一份列表并整份替换缓存。
	 *
	 * <p>解析失败时**保持上一份、时间戳也不动**：宁可显示一份旧名单（读者能从
	 * {@link #receivedAtMillis()} 看出它旧了），也不要把它换成空表 —— 空表在界面上的意思是
	 * "场上没有车次"，那是一句假话。</p>
	 */
	public static void set(String json) {
		final List<Row> parsed = parse(json);
		if (parsed == null) {
			org.mtr.mod.Init.LOGGER.warn("[MMTR-DUTY] 车次列表解析失败 —— 保留上一份（{} 行）", rows.size());
			return;
		}
		rows = parsed;
		receivedAtMillis = System.currentTimeMillis();
	}

	/** 引擎此刻说场上挂着车次的车（**可能是空表**：那也是一句真话 —— 场上真的没有车次）。 */
	public static List<Row> rows() {
		return rows;
	}

	/** 最后一次成功收到列表的墙钟时刻（毫秒）；{@code 0} = 还没收到过。 */
	public static long receivedAtMillis() {
		return receivedAtMillis;
	}

	/**
	 * 解析负载 {@code {"rows":[{"vehicleId":"…",…},…]}}；解析不出来返回 {@code null}。
	 *
	 * <p><b>为什么负载是对象不是裸数组</b>：{@code PacketRequestResponseBase.runClient()} 走的是
	 * {@code Utilities.parseJson(content)} → {@code JsonParser.parseString(...).getAsJsonObject()}，
	 * 顶层不是对象时它**静默**退化成空对象（catch 里返回 {@code new JsonObject()}）——
	 * 一份裸数组负载会在那里被吃掉，面板永远显示"正在向引擎取列表"。这里两种情况都容错。</p>
	 *
	 * <p>数值一律按字符串读再自己转（{@code getAsString()} 对数字也返回它的字面量）：
	 * {@code speedKmh} / {@code distanceToStopM} 走双精度，区域设置若用逗号作小数点，
	 * {@code Double.parseDouble} 不受影响，而"读不出来"一律退化成 0 / 空串，不抛到界面。</p>
	 */
	private static List<Row> parse(String json) {
		if (json == null || json.isEmpty()) {
			return null;
		}
		try {
			final JsonElement root = JsonParser.parseString(json);
			if (!root.isJsonObject()) {
				return null;
			}
			final JsonElement rowsElement = root.getAsJsonObject().get("rows");
			if (rowsElement == null || !rowsElement.isJsonArray()) {
				return null;
			}
			final List<Row> parsed = new ArrayList<>();
			for (final JsonElement element : rowsElement.getAsJsonArray()) {
				if (!element.isJsonObject()) {
					continue; // 一条坏行不该让整份名单作废
				}
				final JsonObject row = element.getAsJsonObject();
				parsed.add(new Row(
					longOf(row, "vehicleId"),
					stringOf(row, "jobId"),
					stringOf(row, "dutyState"),
					stringOf(row, "dutyCrew"),
					stringOf(row, "taskNote"),
					stringOf(row, "nextStation"),
					doubleOf(row, "speedKmh"),
					doubleOf(row, "distanceToStopM"),
					(int) doubleOf(row, "dutyWaiting")));
			}
			return parsed;
		} catch (Exception e) {
			org.mtr.mod.Init.LOGGER.warn("[MMTR-DUTY] 车次列表负载解析异常", e);
			return null;
		}
	}

	/** 64 位车 id 走字符串（与 {@code PacketMmtrBoardPlayer} 同一个口径），两种写法都读。 */
	private static long longOf(JsonObject json, String key) {
		final JsonElement element = json.get(key);
		if (element == null || element.isJsonNull()) {
			return 0;
		}
		try {
			return Long.parseLong(element.getAsString().trim());
		} catch (Exception e) {
			return 0; // 读不出来就当"没有这辆车"（车 0 不存在），不猜一个假车号
		}
	}

	private static double doubleOf(JsonObject json, String key) {
		final JsonElement element = json.get(key);
		if (element == null || element.isJsonNull()) {
			return 0;
		}
		try {
			return Double.parseDouble(element.getAsString().trim());
		} catch (Exception e) {
			return 0;
		}
	}

	private static String stringOf(JsonObject json, String key) {
		final JsonElement element = json.get(key);
		return element == null || element.isJsonNull() ? "" : element.getAsString();
	}
}
