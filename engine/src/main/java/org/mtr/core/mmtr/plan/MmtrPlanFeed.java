package org.mtr.core.mmtr.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.tool.Utilities;

import java.util.List;

/**
 * P6 ⑤（WEB 六页）的**只读数据契约**：网页要看什么，这里就只算那几样。
 *
 * <p>为什么单独一个类、而且**全是纯函数**：六个页面要的东西分三类 —— 趟次表（与车无关）、
 * 交路时间轴（计划 vs 实际）、现场可选项（站/台/车场股道）。它们都能由"输入层 + 走行时间 + 交路"
 * 算出来，**不需要认识世界**：于是可以拿假数据逐字段断言（{@code MmtrPlanFeedTests}），
 * 而不是靠"打开网页看一眼"。认识的世界的只有 servlet 里那几行适配（把 {@code Station}/{@code Siding}
 * 摘成 {@link StationInfo}/{@link DepotInfo}）。</p>
 *
 * <p>① 里两个方向都列：P2 的 v1 只生成起点方向的发车序列，返程是镜像时刻表 —— 乘客视角的
 * "趟次表"必须两个方向都有，所以这里把 {@code Direction.BACK} 那份也算出来（P6 呈现的正是它）。</p>
 */
public final class MmtrPlanFeed {

	private MmtrPlanFeed() {
	}

	// ---------------------------------------------------------------- ① 趟次表（乘客视角 · 与车无关）

	/**
	 * 一条线路的趟次表：两个方向、每趟的到发时刻与沿途各站到发。
	 *
	 * <p>字段名就是契约：{@code tripId / direction / departureMillis / stops[]{stationId, platformId,
	 * arrivalMillis, departureMillis, dwellMillis}}。</p>
	 */
	public static JsonObject servicePlan(MmtrLine line, MmtrPattern pattern, MmtrTravelTimes times) {
		final MmtrServicePlan outbound = MmtrServicePlan.generate(line, pattern, times, MmtrServicePlan.Trip.Direction.OUT);
		final MmtrServicePlan inbound = MmtrServicePlan.generate(line, pattern, times, MmtrServicePlan.Trip.Direction.BACK);
		final JsonObject root = new JsonObject();
		root.addProperty("lineId", line.lineId);
		root.addProperty("name", line.name);
		root.addProperty("terminalTreatment", line.terminalTreatment.name());
		root.addProperty("loop", line.loop);
		root.addProperty("ringMillis", MmtrServicePlan.ringMillis(line, times));
		root.addProperty("peakHeadwayMillis", pattern.peakHeadwayMillis());
		root.addProperty("tripCount", outbound.size() + inbound.size());
		root.add("outbound", trips(outbound));
		root.add("inbound", trips(inbound));
		return root;
	}

	private static JsonArray trips(MmtrServicePlan plan) {
		final JsonArray array = new JsonArray();
		for (final MmtrServicePlan.Trip trip : plan.trips) {
			final JsonObject out = new JsonObject();
			out.addProperty("tripId", trip.tripId);
			out.addProperty("sequence", trip.sequence);
			out.addProperty("direction", trip.direction.name());
			out.addProperty("departureMillis", trip.departureMillis);
			out.addProperty("terminalDoneMillis", trip.terminalDoneMillis);
			out.addProperty("durationMillis", trip.durationMillis());
			out.addProperty("terminalTreatment", trip.terminalTreatment.name());
			final JsonArray stops = new JsonArray();
			for (final MmtrServicePlan.StopTime stopTime : trip.stopTimes) {
				final JsonObject stop = new JsonObject();
				stop.addProperty("stopIndex", stopTime.stopIndex);
				stop.addProperty("stationId", String.valueOf(stopTime.stationId));
				stop.addProperty("platformId", String.valueOf(stopTime.platformId));
				stop.addProperty("arrivalMillis", stopTime.arrivalMillis);
				stop.addProperty("departureMillis", stopTime.departureMillis);
				stop.addProperty("dwellMillis", stopTime.dwellMillis());
				stops.add(stop);
			}
			out.add("stops", stops);
			array.add(out);
		}
		return array;
	}

	// ---------------------------------------------------------------- ② 交路时间轴（计划 vs 实际）

	/** 时间轴上的三个相对位置（网页照这个上色：灰 = 已过、亮 = 正在这一段、浅 = 还没到）。 */
	public enum Phase {
		PAST,
		NOW,
		FUTURE
	}

	/**
	 * 一个编组的一行：**计划**（交路条目的时间窗与等待）与**实际**（派发器那串步，谁派出去了、正在等谁）。
	 *
	 * <p>两条并排放在一个对象里，就是为了让网页"计划 vs 实际"能对齐同一行，不用在前端拼两份列表。</p>
	 *
	 * @param state 这个编组的派发状态；{@code null} = 它今天没班（交路里只有收尾条目）
	 */
	public static JsonObject timeline(MmtrDiagram.Working working, MmtrPlanDispatcher.@Nullable WorkingState state, long dayTimeMillis) {
		final JsonObject root = new JsonObject();
		root.addProperty("consistId", working.consistId);
		root.addProperty("vehicleId", String.valueOf(state == null ? 0 : state.vehicleId));
		root.addProperty("dispatchedSteps", state == null ? 0 : state.dispatchedSteps);
		root.addProperty("awaitingTaskId", state == null ? "" : state.awaitingTaskId);
		root.addProperty("stepCount", state == null ? 0 : state.tasks.size());

		final JsonArray entries = new JsonArray();
		for (final MmtrDiagram.Entry entry : working.entries) {
			final JsonObject out = new JsonObject();
			out.addProperty("kind", entry.kind.name());
			out.addProperty("startMillis", entry.startMillis);
			out.addProperty("endMillis", entry.endMillis);
			out.addProperty("waitBeforeMillis", entry.waitBeforeMillis);
			out.addProperty("phase", phaseOf(entry, dayTimeMillis).name());
			if (entry.kind == MmtrDiagram.Entry.Kind.TRIP && entry.trip != null) {
				out.addProperty("tripId", entry.trip.tripId);
				out.addProperty("direction", entry.trip.direction.name());
				out.addProperty("stopCount", entry.trip.stopTimes.size());
			} else {
				// 出库/回库那条：网页要知道"往哪个站台/股道"，这两行也给出去（编组代码→车列的对应就靠它）
				out.addProperty("stationId", String.valueOf(entry.stationId));
				out.addProperty("platformId", String.valueOf(entry.platformId));
				out.addProperty("sidingId", String.valueOf(entry.sidingId));
			}
			entries.add(out);
		}
		root.add("entries", entries);

		final JsonArray steps = new JsonArray();
		if (state != null) {
			for (int i = 0; i < state.tasks.size(); i++) {
				final org.mtr.core.mmtr.task.MmtrTask task = state.tasks.get(i);
				final JsonObject out = new JsonObject();
				out.addProperty("taskId", task.taskId);
				out.addProperty("kind", task.kind().name());
				out.addProperty("dueMs", task.dueMs);
				out.addProperty("earliestMs", task.earliestMs);
				out.addProperty("targetRef", String.valueOf(task.targetRef));
				out.addProperty("describe", task.describe());
				out.addProperty("dispatched", i < state.dispatchedSteps);
				out.addProperty("awaiting", task.taskId.equals(state.awaitingTaskId));
				steps.add(out);
			}
		}
		root.add("steps", steps);
		return root;
	}

	private static Phase phaseOf(MmtrDiagram.Entry entry, long dayTimeMillis) {
		if (dayTimeMillis > entry.endMillis) {
			return Phase.PAST;
		}
		return dayTimeMillis < entry.startMillis ? Phase.FUTURE : Phase.NOW;
	}

	// ---------------------------------------------------------------- ③ 现场可选项（站/台、车场股道）

	/**
	 * 网页下拉框要的现场清单：**站与站台**（线路页选站序）、**车辆段与股道**（车底页选出库股道）。
	 *
	 * <p>id 一律给**十进制字符串**：浏览器装不下 64 位整数，而 {@link MmtrPlanIds} 正是按十进制字符串解析的
	 * —— 网页存进去、引擎读回来必须对得上（hex 只是给人看的标签，{@code hex} 字段）。</p>
	 */
	public static JsonObject world(List<StationInfo> stations, List<DepotInfo> depots) {
		final JsonObject root = new JsonObject();
		final JsonArray stationArray = new JsonArray();
		for (final StationInfo station : stations) {
			final JsonObject out = new JsonObject();
			out.addProperty("id", String.valueOf(station.id));
			out.addProperty("hex", Utilities.numberToPaddedHexString(station.id));
			out.addProperty("name", station.name);
			final JsonArray platforms = new JsonArray();
			for (final PlatformInfo platform : station.platforms) {
				final JsonObject p = new JsonObject();
				p.addProperty("id", String.valueOf(platform.id));
				p.addProperty("hex", Utilities.numberToPaddedHexString(platform.id));
				p.addProperty("name", platform.name);
				p.addProperty("dwellMillis", platform.dwellMillis);
				platforms.add(p);
			}
			out.add("platforms", platforms);
			stationArray.add(out);
		}
		root.add("stations", stationArray);

		final JsonArray depotArray = new JsonArray();
		for (final DepotInfo depot : depots) {
			final JsonObject out = new JsonObject();
			out.addProperty("id", String.valueOf(depot.id));
			out.addProperty("hex", Utilities.numberToPaddedHexString(depot.id));
			out.addProperty("name", depot.name);
			final JsonArray sidings = new JsonArray();
			for (final SidingInfo siding : depot.sidings) {
				final JsonObject s = new JsonObject();
				s.addProperty("id", String.valueOf(siding.id));
				s.addProperty("hex", Utilities.numberToPaddedHexString(siding.id));
				s.addProperty("name", siding.name);
				s.addProperty("vehicles", siding.vehicles);
				s.addProperty("railLength", siding.railLength);
				sidings.add(s);
			}
			out.add("sidings", sidings);
			depotArray.add(out);
		}
		root.add("depots", depotArray);
		return root;
	}

	public static final class StationInfo {

		public final long id;
		public final String name;
		public final ObjectArrayList<PlatformInfo> platforms = new ObjectArrayList<>();

		public StationInfo(long id, String name) {
			this.id = id;
			this.name = name;
		}

		public StationInfo addPlatform(long platformId, String platformName, long dwellMillis) {
			platforms.add(new PlatformInfo(platformId, platformName, dwellMillis));
			return this;
		}
	}

	public static final class PlatformInfo {

		public final long id;
		public final String name;
		public final long dwellMillis;

		PlatformInfo(long id, String name, long dwellMillis) {
			this.id = id;
			this.name = name;
			this.dwellMillis = dwellMillis;
		}
	}

	public static final class DepotInfo {

		public final long id;
		public final String name;
		public final ObjectArrayList<SidingInfo> sidings = new ObjectArrayList<>();

		public DepotInfo(long id, String name) {
			this.id = id;
			this.name = name;
		}

		public DepotInfo addSiding(long sidingId, String sidingName, int vehicles, double railLength) {
			sidings.add(new SidingInfo(sidingId, sidingName, vehicles, railLength));
			return this;
		}
	}

	public static final class SidingInfo {

		public final long id;
		public final String name;
		public final int vehicles;
		public final double railLength;

		SidingInfo(long id, String name, int vehicles, double railLength) {
			this.id = id;
			this.name = name;
			this.vehicles = vehicles;
			this.railLength = railLength;
		}
	}
}
