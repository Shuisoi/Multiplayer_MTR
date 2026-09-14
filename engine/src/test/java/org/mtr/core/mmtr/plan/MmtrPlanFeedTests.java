package org.mtr.core.mmtr.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.job.MmtrCarSpec;
import org.mtr.core.serializer.JsonReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6 ⑤（WEB 六页）的数据契约用例：网页要的三样东西**逐字段**钉住。
 *
 * <p>为什么这些能测、也值得测：网页是"看得见的东西"，一旦字段名/单位/方向反了，人是**看出来**的
 * —— 而看出来的代价是"打开网页翻半天"。所以把契约放在纯函数里（{@link MmtrPlanFeed}），
 * 用假数据断言：① 趟次表两个方向都在、发车间隔与停站时长对得上；② 交路时间轴的"计划三态"与
 * "实际那串步"能对齐；③ 现场清单给的 id，**引擎自己读得回来**（十进制字符串，见
 * {@link MmtrPlanIds}）—— 最后这条正是现场踩过的坑：网页填 hex、引擎读成 0。</p>
 */
public final class MmtrPlanFeedTests {

	private static final long MIN = 60_000L;
	private static final long H07 = 7 * 60 * MIN;
	private static final long LEG = 4 * MIN;
	private static final long TERMINAL = 3 * MIN;
	private static final MmtrTravelTimes TIMES = MmtrTravelTimes.uniform(LEG, TERMINAL);

	private static MmtrLine line() {
		final MmtrLine line = new MmtrLine("L1", "1 号线");
		line.yardSidingId = 42;
		line.leadTimeMillis = 5 * MIN;
		line.terminalTreatment = MmtrLine.TerminalTreatment.CHANGE_ENDS;
		line.addStop(1001, 2001, 30_000);
		line.addStop(1002, 2002, 45_000);
		line.addStop(1003, 2003, 30_000);
		return line;
	}

	/** 07:00–07:30、间隔 10 分钟 ⇒ 3 个槽位。 */
	private static MmtrPattern pattern() {
		return new MmtrPattern("L1").addSegment(H07, H07 + 30 * MIN, 10 * MIN);
	}

	private static MmtrFleet fleet(int consists) {
		final MmtrFleet fleet = new MmtrFleet();
		for (int i = 0; i < consists; i++) {
			fleet.addConsist(new MmtrFleet.ConsistSpec("C" + (i + 1), 80).addCar(new MmtrCarSpec()));
		}
		return fleet;
	}

	// ---------------------------------------------------------------- ① 趟次表

	/** ① 两个方向都要有；发车间隔、停站时长、站序 id 逐条对得上（乘客视角的契约）。 */
	@Test
	public void theServicePlanListsBothDirectionsWithStopTimes() {
		final MmtrLine line = line();
		final MmtrPattern pattern = pattern();
		final JsonObject plan = MmtrPlanFeed.servicePlan(line, pattern, TIMES);

		assertEquals("L1", plan.get("lineId").getAsString());
		assertEquals("1 号线", plan.get("name").getAsString());
		assertEquals(ringOfTheFixtureLine(), plan.get("ringMillis").getAsLong(), "周转时间要与 P2/P3 同一个算法");
		assertEquals(10 * MIN, plan.get("peakHeadwayMillis").getAsLong());

		final JsonArray outbound = plan.getAsJsonArray("outbound");
		final JsonArray inbound = plan.getAsJsonArray("inbound");
		assertEquals(3, outbound.size(), "07:00/07:10/07:20 三趟");
		assertEquals(3, inbound.size(), "返程是镜像，也要列出来（P2 的 v1 只生成起点方向）");
		assertEquals(6, plan.get("tripCount").getAsInt());

		final JsonObject first = outbound.get(0).getAsJsonObject();
		assertEquals(H07, first.get("departureMillis").getAsLong(), "首趟就是密度表第一个槽位");
		assertEquals("OUT", first.get("direction").getAsString());
		assertEquals(H07 + 10 * MIN, outbound.get(1).getAsJsonObject().get("departureMillis").getAsLong(), "间隔按密度表");
		assertEquals("BACK", inbound.get(0).getAsJsonObject().get("direction").getAsString());

		final JsonArray stops = first.getAsJsonArray("stops");
		assertEquals(3, stops.size());
		assertEquals("1001", stops.get(0).getAsJsonObject().get("stationId").getAsString());
		assertEquals("2001", stops.get(0).getAsJsonObject().get("platformId").getAsString());
		// 起点没有"到达"，所以它的停站是 0（停站属于到达，P3 的 ring 公式同一条口径）；
		// 中间站的停站就是线路定义里那个值 —— 网页上"停 45 秒"要显示成 45。
		assertEquals(0, stops.get(0).getAsJsonObject().get("dwellMillis").getAsLong(), "起点站不停站");
		assertEquals(45_000, stops.get(1).getAsJsonObject().get("dwellMillis").getAsLong(), "中途站停站来自线路定义");
		assertEquals(30_000, stops.get(2).getAsJsonObject().get("dwellMillis").getAsLong(), "终点站也停（终点处理另算）");
		long previous = Long.MIN_VALUE;
		for (int i = 0; i < stops.size(); i++) {
			final long arrival = stops.get(i).getAsJsonObject().get("arrivalMillis").getAsLong();
			assertTrue(arrival > previous, "沿途到点必须递增（第 " + i + " 站 " + arrival + "）");
			previous = arrival;
		}
	}

	/** 周转时间用 P2/P3 那一个算法，不在网页那边另算一遍（免得两处口径漂移）。 */
	private static long ringOfTheFixtureLine() {
		return MmtrServicePlan.ringMillis(line(), TIMES);
	}

	// ---------------------------------------------------------------- ② 交路时间轴

	/**
	 * ② 一行 = 一个编组：**计划**（条目窗口 + 三态）与**实际**（那串步、派出去了几步、正在等哪一步）。
	 *
	 * <p>三态的定义就是派发器判据的同一个数（{@code dayTime} 与条目窗口的关系）—— 网页上"灰/亮/浅"
	 * 与引擎里"该跳/该派"是同一件事的两种呈现。</p>
	 */
	@Test
	public void theTimelineAlignsPlannedEntriesWithActualSteps() {
		final MmtrLine line = line();
		final MmtrDiagram diagram = MmtrDiagram.generate(line, pattern(), fleet(1), TIMES);
		final MmtrPlanDispatcher dispatcher = new MmtrPlanDispatcher(line, diagram);
		final MmtrDiagram.Working working = diagram.scheduledWorkings().get(0);
		final MmtrPlanDispatcher.WorkingState state = dispatcher.states.get(0);

		// 开机前看：一整天的条目都还没到
		final JsonObject before = MmtrPlanFeed.timeline(working, state, H07 - 60 * MIN);
		assertEquals("C1", before.get("consistId").getAsString());
		final JsonArray entries = before.getAsJsonArray("entries");
		assertEquals(working.entries.size(), entries.size(), "条目一条不少");
		assertEquals("DEPART_YARD", entries.get(0).getAsJsonObject().get("kind").getAsString());
		assertEquals("FUTURE", entries.get(0).getAsJsonObject().get("phase").getAsString());
		assertEquals("42", entries.get(0).getAsJsonObject().get("sidingId").getAsString(), "出库条目要给出库股道");
		boolean sawTrip = false;
		for (int i = 0; i < entries.size(); i++) {
			final JsonObject entry = entries.get(i).getAsJsonObject();
			if (entry.get("kind").getAsString().equals("TRIP")) {
				sawTrip = true;
				assertNotEquals("", entry.get("tripId").getAsString());
				assertTrue(entry.get("stopCount").getAsInt() >= 2);
			}
		}
		assertTrue(sawTrip, "交路里必须有趟次条目");

		// 实际：一步都没派
		assertEquals(0, before.get("dispatchedSteps").getAsInt());
		final JsonArray steps = before.getAsJsonArray("steps");
		assertEquals(state.tasks.size(), steps.size(), "步数 = 展开出来的任务数");
		assertFalse(steps.get(0).getAsJsonObject().get("dispatched").getAsBoolean());

		// 走到白天里：第一条目已经过去、趟次条目正在这一段
		state.dispatchedSteps = 2;
		state.awaitingTaskId = state.tasks.get(2).taskId;
		final JsonObject during = MmtrPlanFeed.timeline(working, state, working.entries.get(0).endMillis + MIN);
		assertEquals(2, during.get("dispatchedSteps").getAsInt());
		assertEquals(state.tasks.get(2).taskId, during.get("awaitingTaskId").getAsString());
		final JsonArray stepsDuring = during.getAsJsonArray("steps");
		assertTrue(stepsDuring.get(0).getAsJsonObject().get("dispatched").getAsBoolean(), "前两步已派");
		assertFalse(stepsDuring.get(2).getAsJsonObject().get("dispatched").getAsBoolean());
		assertTrue(stepsDuring.get(2).getAsJsonObject().get("awaiting").getAsBoolean(), "第 3 步正在等");
		assertEquals("PAST", during.getAsJsonArray("entries").get(0).getAsJsonObject().get("phase").getAsString());
		assertEquals("NOW", during.getAsJsonArray("entries").get(1).getAsJsonObject().get("phase").getAsString());
		assertEquals(state.tasks.get(2).describe(), stepsDuring.get(2).getAsJsonObject().get("describe").getAsString());
	}

	// ---------------------------------------------------------------- ③ 现场可选项

	/**
	 * ③ 现场清单：站/台、车场股道，以及**引擎读得回来的 id**。
	 *
	 * <p>红证：把 id 换成 hex（网页更好看的那一种），最后那段往返就会读出 0 —— 现场真的这么错过一次。
	 * 站台可以为空的站也要在清单里（现场有几个没设站台的站）。</p>
	 */
	@Test
	public void theWorldFeedGivesIdsTheEngineCanReadBack() {
		final long stationId = 4244617445981648900L;
		final long platformId = 4244617445981648901L;
		final JsonObject world = MmtrPlanFeed.world(
			java.util.List.of(
				new MmtrPlanFeed.StationInfo(stationId, "1 号站").addPlatform(platformId, "1 站台", 30_000),
				new MmtrPlanFeed.StationInfo(1870719232554423099L, "没有站台的站（现场就有）")
			),
			java.util.List.of(
				new MmtrPlanFeed.DepotInfo(849401984139021720L, "客车场").addSiding(-4629294257679021237L, "1 道", 1, 120.5)
			)
		);

		final JsonObject station = world.getAsJsonArray("stations").get(0).getAsJsonObject();
		assertEquals(String.valueOf(stationId), station.get("id").getAsString(), "id 必须是十进制字符串（64 位装不进浏览器）");
		assertEquals("3AE7E91403297804", station.get("hex").getAsString(), "hex 只当给人看的标签");
		assertEquals(1, station.getAsJsonArray("platforms").size());
		assertEquals(30_000, station.getAsJsonArray("platforms").get(0).getAsJsonObject().get("dwellMillis").getAsLong());

		final JsonObject empty = world.getAsJsonArray("stations").get(1).getAsJsonObject();
		assertTrue(empty.has("platforms"), "没有站台的站也要给一个空数组，不能整个字段消失");
		assertEquals(0, empty.getAsJsonArray("platforms").size());

		final JsonObject depot = world.getAsJsonArray("depots").get(0).getAsJsonObject();
		assertEquals("849401984139021720", depot.get("id").getAsString());
		final JsonObject siding = depot.getAsJsonArray("sidings").get(0).getAsJsonObject();
		assertEquals("-4629294257679021237", siding.get("id").getAsString());
		assertEquals(1, siding.get("vehicles").getAsInt());

		// 关键一步：把网页拿到的 id 原样填进线路 JSON，引擎必须解析回同一个数（否则"网页存进去、引擎读成 0"）
		final String posted = "{\"lineId\":\"W1\",\"name\":\"网页填的\",\"terminalTreatment\":\"CHANGE_ENDS\","
			+ "\"yardSidingId\":\"" + siding.get("id").getAsString() + "\",\"leadTimeMillis\":0,\"stops\":["
			+ "{\"stationId\":\"" + station.get("id").getAsString() + "\",\"platformId\":\""
			+ station.getAsJsonArray("platforms").get(0).getAsJsonObject().get("id").getAsString() + "\",\"dwellMillis\":30000}]}";
		final MmtrLine parsed = new MmtrLine(new JsonReader(com.google.gson.JsonParser.parseString(posted).getAsJsonObject()));
		assertEquals(stationId, parsed.stops.get(0).stationId, "站 id 往返必须一致");
		assertEquals(platformId, parsed.stops.get(0).platformId, "台 id 往返必须一致");
		assertEquals(-4629294257679021237L, parsed.yardSidingId, "出库股道往返必须一致");
	}
}
