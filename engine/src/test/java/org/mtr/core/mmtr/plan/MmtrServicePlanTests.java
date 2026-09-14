package org.mtr.core.mmtr.plan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 验收（{@code 任务系统-线路派生与车底交路-设计.md} §10 的 P2 四条）：
 *
 * <ol>
 *   <li>07:00–08:30/5min + 08:30–10:00/3min 的趟次表**逐条**断言；</li>
 *   <li>时间带边界（08:30）**不重不漏**；</li>
 *   <li>沿途站到发时刻按走行 + 停站累计正确；</li>
 *   <li>环线闭合（每一趟首尾同站，末趟终点 = 首趟起点）。</li>
 * </ol>
 *
 * <p>假世界：每段走行 4 分钟、终点处理 3 分钟（{@link MmtrTravelTimes#uniform}）——
 * 与"不知道轨图"的 P2 处境一致，数字好手算。</p>
 */
public final class MmtrServicePlanTests {

	private static final long MIN = 60_000L;
	private static final long H07 = 7 * 60 * MIN;
	private static final long H08_30 = 8 * 60 * MIN + 30 * MIN;
	private static final long H10 = 10 * 60 * MIN;
	private static final long LEG = 4 * MIN;
	private static final long TERMINAL = 3 * MIN;
	private static final MmtrTravelTimes TIMES = MmtrTravelTimes.uniform(LEG, TERMINAL);

	private static MmtrLine threeStopLine() {
		final MmtrLine line = new MmtrLine("L1", "1 号线");
		line.yardSidingId = 42;
		line.addStop(1001, 2001, 30_000);   // A 停 30 s
		line.addStop(1002, 2002, 45_000);   // B 停 45 s
		line.addStop(1003, 2003, 30_000);   // C 停 30 s（终点）
		return line;
	}

	private static MmtrPattern peakPattern() {
		return new MmtrPattern("L1")
			.addSegment(H07, H08_30, 5 * MIN)
			.addSegment(H08_30, H10, 3 * MIN);
	}

	// ---------------------------------------------------------------- ① 逐条 + ② 边界

	/** ① 两段密度展开的趟次表：段内每 5 min / 3 min 一趟，**逐条**对。 */
	@Test
	public void theTimetableExpandsBothSegmentsExactly() {
		final MmtrServicePlan plan = MmtrServicePlan.generate(threeStopLine(), peakPattern(), TIMES);
		final List<Long> departures = new ArrayList<>();
		for (final MmtrServicePlan.Trip trip : plan.trips) {
			departures.add(trip.departureMillis);
		}

		// 07:00–08:30 每 5 min：07:00 … 08:25 共 18 趟
		for (int i = 0; i < 18; i++) {
			assertEquals(H07 + i * 5 * MIN, departures.get(i), "第 " + (i + 1) + " 趟（平峰段）");
		}
		// 08:30–10:00 每 3 min：08:30 … 09:57 共 30 趟
		for (int i = 0; i < 30; i++) {
			assertEquals(H08_30 + i * 3 * MIN, departures.get(18 + i), "第 " + (19 + i) + " 趟（高峰段）");
		}
		assertEquals(48, departures.size(), "18 + 30 = 48 趟");
		assertEquals(H07, plan.firstDepartureMillis());
		assertEquals(H08_30 + 29 * 3 * MIN, plan.lastDepartureMillis(), "末班 09:57");
		assertEquals("L1-001", plan.trips.get(0).tripId);
		assertEquals("L1-048", plan.trips.get(47).tripId);
	}

	/** ② 08:30 这个边界时刻**只出现一次**，且属于后一段（3 min 那一档）。 */
	@Test
	public void the0810BoundaryBelongsToExactlyOneSegment() {
		final MmtrServicePlan plan = MmtrServicePlan.generate(threeStopLine(), peakPattern(), TIMES);
		final List<Long> departures = new ArrayList<>();
		for (final MmtrServicePlan.Trip trip : plan.trips) {
			departures.add(trip.departureMillis);
		}
		assertEquals(1, departures.stream().filter(time -> time == H08_30).count(), "08:30 只能发一趟");
		assertTrue(departures.contains(H08_30 - 5 * MIN), "平峰段最后一趟是 08:25");
		assertEquals(H08_30 + 3 * MIN, departures.get(departures.indexOf(H08_30) + 1), "08:30 之后按 3 min 走");
		// 严格递增、无重复
		for (int i = 1; i < departures.size(); i++) {
			assertTrue(departures.get(i) > departures.get(i - 1), "第 " + (i + 1) + " 趟必须晚于上一趟");
		}
		// 运营时段首尾：起点 07:00，末班不越过 10:00
		assertEquals(H07, departures.get(0));
		assertTrue(departures.get(departures.size() - 1) < H10, "末班必须在 10:00 之前（左闭右开）");
	}

	// ---------------------------------------------------------------- ③ 到发时刻累计

	/** ③ 各站到发时刻 = 前站开车 + 走行；开车 = 到点 + 停站；终点处理另计。 */
	@Test
	public void stopArrivalAndDepartureTimesAccumulateCorrectly() {
		final MmtrServicePlan plan = MmtrServicePlan.generate(threeStopLine(), peakPattern(), TIMES);
		final MmtrServicePlan.Trip trip = plan.trips.get(0);   // 07:00 发

		assertEquals(3, trip.stopTimes.size(), "三站");
		// A：07:00 到、07:00:30 开
		assertEquals(H07, trip.stopTimes.get(0).arrivalMillis);
		assertEquals(H07 + 30_000, trip.stopTimes.get(0).departureMillis);
		// B：A 开车 + 4 min 走行 = 07:04:30 到，停 45 s → 07:05:15 开
		assertEquals(H07 + 30_000 + LEG, trip.stopTimes.get(1).arrivalMillis);
		assertEquals(H07 + 30_000 + LEG + 45_000, trip.stopTimes.get(1).departureMillis);
		// C（终点）：B 开车 + 4 min = 07:09:15 到，停 30 s → 07:09:45 开（终点处理 3 min → 07:12:45）
		assertEquals(H07 + 30_000 + LEG + 45_000 + LEG, trip.stopTimes.get(2).arrivalMillis);
		assertEquals(H07 + 30_000 + LEG + 45_000 + LEG + 30_000, trip.stopTimes.get(2).departureMillis);
		assertEquals(trip.stopTimes.get(2).departureMillis + TERMINAL, trip.terminalDoneMillis, "终点处理 3 min");
		assertEquals(1003, trip.terminalStop().stationId);
		assertEquals(MmtrLine.TerminalTreatment.CHANGE_ENDS, trip.terminalTreatment);

		// 每趟的站序都对得上：到点递增、开车 ≥ 到点
		for (final MmtrServicePlan.Trip each : plan.trips) {
			for (int i = 0; i < each.stopTimes.size(); i++) {
				assertTrue(each.stopTimes.get(i).dwellMillis() >= 0, "停站时长不为负");
				assertTrue(each.stopTimes.get(i).departureMillis >= each.stopTimes.get(i).arrivalMillis, "开车不早于到达");
				if (i > 0) {
					assertTrue(each.stopTimes.get(i).arrivalMillis > each.stopTimes.get(i - 1).departureMillis, "到点晚于前站开点");
				}
			}
		}
	}

	/** ring（周转）= 往程 + 停站 + 终点处理 + 返程 + 停站 —— 车底数的分母，必须手算得住。 */
	@Test
	public void theRingAddsBothDirectionsDwellsAndTerminalHandling() {
		final MmtrLine line = threeStopLine();
		// 2 段 × 2 方向 × 4 min = 16 min 走行；停站 (30+45+30)s × 2 = 3.5 min；终点处理 3 min → 22.5 min
		final long expected = 2 * 2 * LEG + 2 * (30_000 + 45_000 + 30_000) + TERMINAL;
		assertEquals(expected, MmtrServicePlan.ringMillis(line, TIMES));
		assertEquals(22 * MIN + 30_000, expected, "22.5 min（手算核对）");
		assertEquals(5, MmtrFleet.requiredConsists(MmtrServicePlan.ringMillis(line, TIMES), 5 * MIN),
			"22.5 min 周转 / 5 min 间隔 = 4.5 → 向上取整 5（这就是「车辆数固定要利用好」要盯的那个数）");
	}

	/** ③ 密度表为空 / 坏段时**不生成无穷多趟**（生成器不替输入层报错，但也不失控）。 */
	@Test
	public void brokenSegmentsProduceNoTrips() {
		assertTrue(MmtrServicePlan.generate(threeStopLine(), new MmtrPattern("L1"), TIMES).isEmpty(), "没有段 → 没有趟次");
		final MmtrPattern zeroHeadway = new MmtrPattern("L1").addSegment(H07, H10, 0);
		assertTrue(MmtrServicePlan.generate(threeStopLine(), zeroHeadway, TIMES).isEmpty(), "headway=0 → 不生成（由输入层报错）");
	}

	// ---------------------------------------------------------------- ④ 环线闭合

	/** ④ 环线：每一趟首尾同站（终点即起点），且**末趟终点 = 首趟起点**。 */
	@Test
	public void aLoopLineClosesOnItself() {
		final MmtrLine loop = new MmtrLine("L2", "环线");
		loop.yardSidingId = 7;
		loop.loop = true;
		loop.addStop(2001, 3001, 30_000);
		loop.addStop(2002, 3002, 30_000);
		loop.addStop(2003, 3003, 30_000);
		loop.addStop(2001, 3001, 30_000);   // 回到起点（站序闭合，P1 已校验）

		final MmtrServicePlan plan = MmtrServicePlan.generate(loop, peakPattern(), TIMES);
		assertTrue(plan.size() > 0);
		for (final MmtrServicePlan.Trip trip : plan.trips) {
			assertEquals(trip.stopTimes.get(0).stationId, trip.terminalStop().stationId, "环线一趟的终点就是它的起点");
			assertEquals(trip.stopTimes.get(0).platformId, trip.terminalStop().platformId);
			assertEquals(trip.terminalStop().departureMillis, trip.terminalDoneMillis, "环线不处理终点（继续跑）");
		}
		assertEquals(plan.trips.get(0).stopTimes.get(0).stationId, plan.trips.get(plan.size() - 1).terminalStop().stationId,
			"末趟终点 = 首趟起点");
		assertEquals(3, MmtrServicePlan.ringMillis(loop, TIMES) / LEG / 2,
			"环线四站 = 3 段，ring 是往返走行（3 段 × 2 方向 × 4 min；终点处理不计）");
	}
}
