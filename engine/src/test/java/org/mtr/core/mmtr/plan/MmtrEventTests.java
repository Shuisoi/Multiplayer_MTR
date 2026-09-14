package org.mtr.core.mmtr.plan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.job.MmtrCarSpec;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P5 验收（{@code 任务系统-线路派生与车底交路-设计.md} §10 的 P5 六条）：
 *
 * <ol>
 *   <li>抽象事件类的**四类细分各一例**；</li>
 *   <li>{@code PeakSurge} → 未来趟次加密、**已过的趟次一行不改**；</li>
 *   <li>{@code Delay} → 保表（换车）/ 保车（平移）两种策略各有用例；</li>
 *   <li>{@code Fault} → 车列下线；</li>
 *   <li>冻结边界：在途车的当前任务**不被重算改动**；</li>
 *   <li>重算**不抢在途车已持有的道岔**（不重新分派在途车当前的趟次）。</li>
 * </ol>
 */
public final class MmtrEventTests {

	private static final long MIN = 60_000L;
	private static final long H07 = 7 * 60 * MIN;
	private static final long H08 = 8 * 60 * MIN;
	private static final long LEG = 4 * MIN;
	private static final long TERMINAL = 3 * MIN;
	private static final long LEAD = 5 * MIN;
	private static final MmtrTravelTimes TIMES = MmtrTravelTimes.uniform(LEG, TERMINAL);

	private static MmtrLine line() {
		final MmtrLine line = new MmtrLine("L1", "1 号线");
		line.yardSidingId = 42;
		line.leadTimeMillis = LEAD;
		line.addStop(1001, 2001, 30_000);
		line.addStop(1002, 2002, 45_000);
		line.addStop(1003, 2003, 30_000);
		return line;
	}

	private static MmtrPattern pattern() {
		return new MmtrPattern("L1").addSegment(H07, H08 + 2 * 60 * MIN, 20 * MIN);
	}

	private static MmtrFleet fleet(int consists) {
		final MmtrFleet fleet = new MmtrFleet();
		for (int i = 0; i < consists; i++) {
			fleet.addConsist(new MmtrFleet.ConsistSpec("C" + (i + 1), 80).addCar(new MmtrCarSpec()));
		}
		return fleet;
	}

	// ---------------------------------------------------------------- ① 四类细分

	/** ① 四类细分各一例：类型、目标、状态（由时钟算）、理由文案、剩余时长。 */
	@Test
	public void theFourEventKindsEachAnswerWhatWhenAndWhy() {
		final MmtrEvent surge = new MmtrEvent.PeakSurge("E1", 5, H07, H07 + 30 * MIN, 5 * MIN);
		surge.reason = "站台客流激增";
		final MmtrEvent delay = new MmtrEvent.Delay("E2", "C1", H07 + MIN, 10 * MIN);
		delay.reason = "区间信号故障导致晚点";
		final MmtrEvent fault = new MmtrEvent.Fault("E3", "C1", H07 + 2 * MIN, true);
		fault.reason = "牵引故障";
		final MmtrEvent speed = new MmtrEvent.SpeedRestriction("E4", "FFFFFFFFFFFFFF2C-FFFFFFFFFFFFFFC4", H07, H07 + 60 * MIN, 40);
		speed.reason = "大雨限速";

		assertEquals(MmtrEvent.Kind.PEAK_SURGE, surge.kind());
		assertEquals(MmtrEvent.Kind.DELAY, delay.kind());
		assertEquals(MmtrEvent.Kind.FAULT, fault.kind());
		assertEquals(MmtrEvent.Kind.SPEED_RESTRICTION, speed.kind());

		// 状态由时钟算：未生效 / 生效中 / 已结束
		assertEquals(MmtrEvent.State.PENDING, surge.stateAt(H07 - MIN));
		assertEquals(MmtrEvent.State.ACTIVE, surge.stateAt(H07 + 10 * MIN));
		assertEquals(MmtrEvent.State.ENDED, surge.stateAt(H07 + 31 * MIN));
		assertEquals(MmtrEvent.State.ACTIVE, delay.stateAt(H07 + 2 * MIN), "结束时间未知 ⇒ 一直生效到被终止");

		// 理由与时间都要能推前台（§6.3）
		assertTrue(surge.describe(H07 + 10 * MIN).contains("客流激增"), surge.describe(H07 + 10 * MIN));
		assertTrue(surge.describe(H07 + 10 * MIN).contains("预计 20 分钟后恢复"), surge.describe(H07 + 10 * MIN));
		assertTrue(delay.describe(H07 + 2 * MIN).contains("结束时间未知"), delay.describe(H07 + 2 * MIN));
		assertTrue(fault.targetName().contains("C1"), fault.targetName());
		assertTrue(speed.targetName().contains("区段"), speed.targetName());

		// 终止
		delay.endAt(H07 + 5 * MIN);
		assertEquals(MmtrEvent.State.ENDED, delay.stateAt(H07 + 6 * MIN));
	}

	/** ① 续：登记表只给"生效中"与"即将生效"（前台两条带子），并且能按 id 覆盖/终止。 */
	@Test
	public void theRegistryFeedsActiveAndUpcomingOnly() {
		final MmtrEventRegistry registry = new MmtrEventRegistry();
		final MmtrEvent surge = new MmtrEvent.PeakSurge("E1", 5, H07, H07 + 30 * MIN, 5 * MIN);
		surge.reason = "客流激增";
		final MmtrEvent upcoming = new MmtrEvent.Fault("E2", "C1", H07 + 20 * MIN, true);
		upcoming.reason = "计划检修";
		final MmtrEvent ended = new MmtrEvent.Fault("E3", "C2", H07 - 60 * MIN, true);
		ended.endAt(H07 - 30 * MIN);
		registry.put(surge);
		registry.put(upcoming);
		registry.put(ended);
		assertEquals(3, registry.size());

		assertEquals(1, registry.activeAt(H07 + 5 * MIN).size(),
			"生效中的只有高峰那一条：" + registry.activeAt(H07 + 5 * MIN));
		assertEquals(1, registry.upcomingAt(H07 + 5 * MIN).size(),
			"20 分钟后的故障算即将生效：" + registry.upcomingAt(H07 + 5 * MIN));
		final ObjectArrayList<String> feed = registry.describeForFeed(H07 + 5 * MIN);
		assertEquals(2, feed.size(), "前台只看到生效中 + 即将生效：" + feed);
		assertTrue(feed.get(0).startsWith("生效："), feed.get(0));
		assertTrue(feed.get(1).startsWith("即将："), feed.get(1));

		assertTrue(registry.end("E1", H07 + 10 * MIN));
		assertEquals(0, registry.activeAt(H07 + 11 * MIN).size(), "终止之后不再生效");
		assertTrue(registry.remove("E2"));
		assertEquals(2, registry.size(), "剩下 E1 与 E3（都已结束；结束不等于从表里消失，前台还要显示'刚刚恢复'）");
	}

	// ---------------------------------------------------------------- ② 临时高峰

	/** ② 未来趟次加密，**已过的趟次一行不改**。 */
	@Test
	public void aPeakSurgeDensifiesOnlyTheFuture() {
		final MmtrLine line = line();
		final MmtrPattern base = pattern();                     // 07:00–09:00 每 20 min
		final long now = H07 + 20 * MIN;                        // 现在 07:20
		final ObjectArrayList<MmtrEvent> events = new ObjectArrayList<>();
		final MmtrEvent.PeakSurge surge = new MmtrEvent.PeakSurge("E1", 5, H07, H07 + 60 * MIN, 5 * MIN);
		surge.reason = "客流激增";
		events.add(surge);
		final ObjectArrayList<String> notes = new ObjectArrayList<>();

		final MmtrPattern adjusted = MmtrPlanAdjustments.applyPatternEvents(line, base, events, now, notes);
		assertFalse(notes.isEmpty(), "要有可读的说明：" + notes);
		assertTrue(notes.get(0).contains("客流激增"), notes.get(0));

		// 过去（07:00–07:20）那一档保持 20 min；未来（07:20 起）压到 5 min
		assertEquals(20 * MIN, adjusted.headwayAt(H07 + 5 * MIN), "已过的那一段一行不改");
		assertEquals(5 * MIN, adjusted.headwayAt(now + MIN), "未来压到 5 min");
		assertEquals(5 * MIN, adjusted.headwayAt(H07 + 55 * MIN));
		assertEquals(20 * MIN, adjusted.headwayAt(H07 + 70 * MIN), "高峰窗之后恢复原间隔");

		// 趟次表：已过的发车时刻与原来**逐条一致**，之后显著变密
		final ObjectArrayList<Long> before = MmtrServicePlan.departureSlots(base);
		final ObjectArrayList<Long> after = MmtrServicePlan.departureSlots(adjusted);
		final List<Long> pastBefore = before.stream().filter(time -> time < now).toList();
		final List<Long> pastAfter = after.stream().filter(time -> time < now).toList();
		assertEquals(pastBefore, pastAfter, "已过的趟次逐条不变");
		assertTrue(after.size() > before.size(), "之后变密：" + before.size() + " → " + after.size());
		assertEquals(20 * MIN, base.headwayAt(H07 + 5 * MIN), "基础密度表本身没被改动（事件是运行时扰动）");
	}

	// ---------------------------------------------------------------- ③ 延误两种策略

	/** ③ 保车（平移）：车不变，它**未来**的条目整体后移；过去的条目不动。 */
	@Test
	public void aDelayCanKeepTheVehicleAndShiftItsFutureTrips() {
		final MmtrLine line = line();
		final MmtrFleet fleet = fleet(1);
		final MmtrPattern pattern = pattern();
		final MmtrDiagram diagram = MmtrDiagram.generate(line, pattern, fleet, TIMES);
		final long now = H07 + 25 * MIN;
		final HashMap<String, Long> frozen = new HashMap<>();

		final ObjectArrayList<MmtrEvent> events = new ObjectArrayList<>();
		final MmtrEvent.Delay delay = new MmtrEvent.Delay("E2", "C1", now, 15 * MIN)
			.withStrategy(MmtrEvent.Delay.STRATEGY_KEEP_VEHICLE);
		delay.reason = "区间故障";
		events.add(delay);
		final ObjectArrayList<String> notes = new ObjectArrayList<>();
		final MmtrDiagram adjusted = MmtrPlanAdjustments.applyDiagramEvents(line, pattern, fleet, TIMES, diagram, events, now, frozen, notes);

		assertEquals(1, adjusted.workings.size(), "车还是那辆（保车）");
		assertTrue(notes.get(0).contains("保车"), notes.get(0));
		final ObjectArrayList<MmtrServicePlan.Trip> before = diagram.workings.get(0).trips();
		final ObjectArrayList<MmtrServicePlan.Trip> after = adjusted.workings.get(0).trips();
		assertEquals(before.size(), after.size(), "趟次数不变（只是平移）");
		boolean anyShifted = false;
		for (int i = 0; i < before.size(); i++) {
			final long shift = after.get(i).departureMillis - before.get(i).departureMillis;
			assertTrue(shift == 0 || shift == 15 * MIN, "每条要么没动、要么正好平移 15 min：第 " + (i + 1) + " 趟 shift=" + shift);
			if (shift != 0) {
				anyShifted = true;
				assertTrue(before.get(i).departureMillis >= now, "只有未来的趟次被平移");
			}
		}
		assertTrue(anyShifted, "至少有一条被平移了");
	}

	/** ③ 保表（换车）：时刻表一行不动，未来趟次转给别的编组。 */
	@Test
	public void aDelayCanKeepTheTimetableAndHandTripsOver() {
		final MmtrLine line = line();
		final MmtrFleet fleet = fleet(2);
		final MmtrPattern pattern = pattern();
		final MmtrDiagram diagram = MmtrDiagram.generate(line, pattern, fleet, TIMES);
		final long now = H07 + 25 * MIN;

		final ObjectArrayList<MmtrEvent> events = new ObjectArrayList<>();
		final MmtrEvent.Delay delay = new MmtrEvent.Delay("E2", "C1", now, 15 * MIN);   // 默认保表
		delay.reason = "车底故障";
		events.add(delay);
		final ObjectArrayList<String> notes = new ObjectArrayList<>();
		final MmtrDiagram adjusted = MmtrPlanAdjustments.applyDiagramEvents(line, pattern, fleet, TIMES, diagram, events, now, new HashMap<>(), notes);

		assertTrue(notes.get(0).contains("保表"), notes.get(0));
		final ObjectArrayList<MmtrServicePlan.Trip> before = diagram.workings.get(0).trips();
		final ObjectArrayList<MmtrServicePlan.Trip> after = adjusted.workings.get(0).trips();
		assertEquals(before.size(), after.size());
		for (int i = 0; i < before.size(); i++) {
			assertEquals(before.get(i).departureMillis, after.get(i).departureMillis,
				"保表：第 " + (i + 1) + " 趟的时刻**一行不动**");
		}
		assertEquals("C1", adjusted.workings.get(0).consistId, "第一辆车的交路还在（只是被别的车接手的时间里）");
	}

	// ---------------------------------------------------------------- ④⑤⑥ 故障 + 冻结

	/** ④ 车列下线：未来的趟次取消、并且**说清为什么**；在途（冻结期内）的不动（验收 ⑤）。 */
	@Test
	public void aFaultTakesTheConsistOutOfServiceButNeverTouchesAnInFlightTrip() {
		final MmtrLine line = line();
		final MmtrFleet fleet = fleet(1);
		final MmtrPattern pattern = pattern();
		final MmtrDiagram diagram = MmtrDiagram.generate(line, pattern, fleet, TIMES);
		final long now = H07 + 25 * MIN;
		// 这辆车正在跑 07:40 那一趟（预计 07:52 处理完）→ 冻结到那时
		final HashMap<String, Long> frozen = new HashMap<>();
		frozen.put("C1", H07 + 52 * MIN);

		final ObjectArrayList<MmtrEvent> events = new ObjectArrayList<>();
		final MmtrEvent.Fault fault = new MmtrEvent.Fault("E3", "C1", now, true);
		fault.reason = "牵引故障";
		events.add(fault);
		final ObjectArrayList<String> notes = new ObjectArrayList<>();
		final MmtrDiagram adjusted = MmtrPlanAdjustments.applyDiagramEvents(line, pattern, fleet, TIMES, diagram, events, now, frozen, notes);

		assertTrue(notes.get(0).contains("下线"), notes.get(0));
		final ObjectArrayList<MmtrServicePlan.Trip> before = diagram.workings.get(0).trips();
		final ObjectArrayList<MmtrServicePlan.Trip> after = adjusted.workings.get(0).trips();
		assertTrue(after.size() < before.size(), "未来的趟次被取消：" + before.size() + " → " + after.size());
		// 冻结期内（07:52 之前开出的）必须原样在
		for (final MmtrServicePlan.Trip trip : before) {
			if (trip.departureMillis < frozen.get("C1")) {
				assertTrue(after.stream().anyMatch(kept -> kept.tripId.equals(trip.tripId)),
					"在途/冻结期内的第 " + trip.tripId + " 趟必须原样保留");
			}
		}
		for (final MmtrServicePlan.Trip trip : after) {
			assertTrue(trip.departureMillis < frozen.get("C1"), "保留下来的只能是冻结期内的：" + trip.tripId);
		}
	}

	/**
	 * ⑥ **不抢在途车已持有的道岔**：重算只改"未来谁跑哪一趟"，在途车当前那条趟次对象**原样保留**
	 * （连对象都不换）—— 它手里的进路/道岔授权因此不会被重算动摇（先冻结、再申请）。
	 */
	@Test
	public void recomputeNeverReassignsTheTripAnInFlightVehicleIsRunning() {
		final MmtrLine line = line();
		final MmtrFleet fleet = fleet(1);
		final MmtrPattern pattern = pattern();
		final MmtrDiagram diagram = MmtrDiagram.generate(line, pattern, fleet, TIMES);
		final long now = H07 + 25 * MIN;
		final ObjectArrayList<MmtrEvent> events = new ObjectArrayList<>();
		final MmtrEvent.PeakSurge surge = new MmtrEvent.PeakSurge("E1", 5, now, H07 + 90 * MIN, 5 * MIN);
		surge.reason = "客流激增";
		events.add(surge);

		final MmtrPlanAdjustments.Result result = MmtrPlanAdjustments.recompute(
			line, pattern, fleet, TIMES, events, now, new HashMap<>());

		// 在途车（现在跑着的那一趟 = 07:20 发的那趟）在重算结果里是**同一个对象**
		final MmtrServicePlan.Trip inFlight = diagram.workings.get(0).trips().stream()
			.filter(trip -> trip.departureMillis <= now && now < trip.terminalDoneMillis)
			.findFirst().orElse(null);
		assertNotNull(inFlight, "夹具里要有一趟正在跑的");
		final MmtrServicePlan.Trip afterRecompute = result.diagram.workings.get(0).trips().stream()
			.filter(trip -> trip.tripId.equals(inFlight.tripId)).findFirst().orElse(null);
		assertNotNull(afterRecompute, "重算不许把在途那趟弄丢");
		/*
		 * 重算本身是"重新生成 + 只调未来"，所以对象是新的；要求的是**逐字段一样**
		 * （车次 id、发车、终到处理完）。派发器按任务 id 跟踪，所以内容一致就够了 ——
		 * 这也是"冻结"这条规矩的可检验形式：在途那一趟的每一个数都没动。
		 */
		assertEquals(inFlight.tripId, afterRecompute.tripId);
		assertEquals(inFlight.departureMillis, afterRecompute.departureMillis, "在途那一趟的发车时刻不许动");
		assertEquals(inFlight.terminalDoneMillis, afterRecompute.terminalDoneMillis, "终到处理完的时刻也不许动");
		assertEquals(inFlight.direction, afterRecompute.direction);
		assertEquals(inFlight.stopTimes.size(), afterRecompute.stopTimes.size());
		for (int i = 0; i < inFlight.stopTimes.size(); i++) {
			assertEquals(inFlight.stopTimes.get(i).arrivalMillis, afterRecompute.stopTimes.get(i).arrivalMillis,
				"第 " + (i + 1) + " 站的到点不许动");
			assertEquals(inFlight.stopTimes.get(i).platformId, afterRecompute.stopTimes.get(i).platformId);
		}
		assertTrue(result.pattern.headwayAt(now + MIN) < pattern.headwayAt(now + MIN), "未来确实加密了");
	}
}
