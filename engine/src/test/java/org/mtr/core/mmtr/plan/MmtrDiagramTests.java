package org.mtr.core.mmtr.plan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.job.MmtrCarSpec;
import org.mtr.core.mmtr.task.MmtrTask;
import org.mtr.core.mmtr.task.StationServiceTask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 验收（{@code 任务系统-线路派生与车底交路-设计.md} §10 的 P3 五条）：
 *
 * <ol>
 *   <li>每辆车交路**连续**（上一趟终点 = 下一趟起点）；</li>
 *   <li>出库提前量正确（{@code leadTimeMillis} 前移）；</li>
 *   <li>替补**不占趟次**；</li>
 *   <li>{@code N} 与 {@code ceil(ring / 高峰间隔)} 一致；</li>
 *   <li>环状套班在运营时段首尾闭合。</li>
 * </ol>
 *
 * <p>假世界同 P2：每段走行 4 min、终点处理 3 min。三条站、停站 (30+45+30)s（**停站算在"到达"上**）：
 * 单程 = 2×4min + 45s + 30s + 3min = 12.25 min；ring = 往 + 返 = 24.5 min；高峰间隔 3 min ⇒ N = ceil(8.17) = 9。</p>
 */
public final class MmtrDiagramTests {

	private static final long MIN = 60_000L;
	private static final long H07 = 7 * 60 * MIN;
	private static final long H08_30 = 8 * 60 * MIN + 30 * MIN;
	private static final long H10 = 10 * 60 * MIN;
	private static final long LEG = 4 * MIN;
	private static final long TERMINAL = 3 * MIN;
	private static final long LEAD = 5 * MIN;
	private static final MmtrTravelTimes TIMES = MmtrTravelTimes.uniform(LEG, TERMINAL);

	private static MmtrLine line() {
		final MmtrLine line = new MmtrLine("L1", "1 号线");
		line.yardSidingId = 42;
		line.leadTimeMillis = LEAD;
		line.terminalTreatment = MmtrLine.TerminalTreatment.CHANGE_ENDS;
		line.addStop(1001, 2001, 30_000);
		line.addStop(1002, 2002, 45_000);
		line.addStop(1003, 2003, 30_000);
		return line;
	}

	private static MmtrPattern pattern() {
		return new MmtrPattern("L1")
			.addSegment(H07, H08_30, 5 * MIN)
			.addSegment(H08_30, H10, 3 * MIN);
	}

	private static MmtrFleet fleet(int consists, int spares) {
		final MmtrFleet fleet = new MmtrFleet();
		for (int i = 0; i < consists; i++) {
			fleet.addConsist(new MmtrFleet.ConsistSpec("C" + (i + 1), 80).addCar(new MmtrCarSpec()));
		}
		for (int i = 0; i < spares; i++) {
			fleet.addSpare(new MmtrFleet.ConsistSpec("S" + (i + 1), 80).addCar(new MmtrCarSpec()));
		}
		return fleet;
	}

	private static final long RING = 2 * (2 * LEG + 45_000 + 30_000 + TERMINAL);   // 24.5 min（往 + 返两趟）
	private static final long PEAK_HEADWAY = 3 * MIN;
	private static final int N = 9;   // ceil(24.5 / 3) = 9

	// ---------------------------------------------------------------- ④ N 与公式一致

	/** ④ N = ceil(ring / 高峰间隔)，且交路里**真的**用了这个数（不是另算一个）。 */
	@Test
	public void theDiagramUsesExactlyTheFormulaConsists() {
		final MmtrDiagram diagram = MmtrDiagram.generate(line(), pattern(), fleet(N, 1), TIMES);
		assertEquals(RING, diagram.ringMillis, "周转时间与手算一致");
		assertEquals(PEAK_HEADWAY, diagram.peakHeadwayMillis);
		assertEquals(MmtrFleet.requiredConsists(RING, PEAK_HEADWAY), diagram.requiredConsists, "交路用的 N 就是公式给的");
		assertEquals(N, diagram.requiredConsists);
		assertEquals(N, diagram.scheduledWorkings().size(), "9 辆车都排上班了");
		assertNull(diagram.capacityProblem, "配了 9 个套班编组，够跑");
	}

	/** ④ 续：车底比 N 多时，多出来的**今天不上场**（但要与替补区分开）。 */
	@Test
	public void extraConsistsAreIdleRatherThanSpare() {
		final MmtrDiagram diagram = MmtrDiagram.generate(line(), pattern(), fleet(N + 2, 1), TIMES);
		assertEquals(N, diagram.scheduledWorkings().size(), "只排 N 辆");
		assertEquals(2, diagram.idleConsistIds.size(), "多出来的 2 个编组闲置");
		assertEquals(1, diagram.spareConsistIds.size(), "替补单独记");
		assertFalse(diagram.workings.stream().anyMatch(working -> working.departYard() != null
			&& working.consistId.startsWith("S")), "闲置名单里不该混进替补");
	}

	// ---------------------------------------------------------------- ① 连续

	/**
	 * ① **每辆车交路连续**：上一趟的终点就是下一趟的起点；序列严格不重叠；
	 * 出库在最前、回库在最后；往程与返程**成对**出现。
	 */
	@Test
	public void everyWorkingIsContinuous() {
		final MmtrDiagram diagram = MmtrDiagram.generate(line(), pattern(), fleet(N, 1), TIMES);
		for (final MmtrDiagram.Working working : diagram.scheduledWorkings()) {
			final ObjectArrayList<MmtrServicePlan.Trip> trips = working.trips();
			assertTrue(trips.size() >= 2, working.consistId + " 一天至少两趟（往 + 返）");
			assertEquals(0, trips.get(0).direction.ordinal(), working.consistId + " 第一趟必须是往程（先出库）");
			for (int i = 0; i + 1 < trips.size(); i++) {
				final MmtrServicePlan.Trip previous = trips.get(i);
				final MmtrServicePlan.Trip next = trips.get(i + 1);
				final MmtrServicePlan.StopTime previousEnd = previous.stopTimes.get(previous.stopTimes.size() - 1);
				final MmtrServicePlan.StopTime nextStart = next.stopTimes.get(0);
				assertEquals(previousEnd.stationId, nextStart.stationId,
					working.consistId + " 第 " + (i + 1) + " 趟终点 = 第 " + (i + 2) + " 趟起点（站）");
				assertEquals(previousEnd.platformId, nextStart.platformId,
					working.consistId + " 第 " + (i + 1) + " 趟终点 = 第 " + (i + 2) + " 趟起点（台）");
				assertTrue(nextStart.arrivalMillis >= previous.terminalDoneMillis,
					working.consistId + " 下一趟不能在上一趟处理完之前开：第 " + (i + 1) + " 趟 " + previous.tripId
						+ " 处理完 " + MmtrPattern.hhmm(previous.terminalDoneMillis) + " → 第 " + (i + 2) + " 趟 "
						+ next.tripId + " 开 " + MmtrPattern.hhmm(nextStart.arrivalMillis));
				// 方向交替：往 → 返 → 往 …
				assertTrue(previous.direction != next.direction, working.consistId + " 相邻两趟必须方向相反");
			}
			// 出库 → 首趟 → … → 末趟 → 回库
			final ObjectArrayList<MmtrDiagram.Entry> entries = working.entries;
			assertEquals(MmtrDiagram.Entry.Kind.DEPART_YARD, entries.get(0).kind);
			assertEquals(MmtrDiagram.Entry.Kind.STABLE_YARD, entries.get(entries.size() - 1).kind);
			for (int i = 1; i < entries.size(); i++) {
				assertTrue(entries.get(i).startMillis >= entries.get(i - 1).endMillis,
					working.consistId + " 第 " + (i + 1) + " 条不能压在前一条上");
				assertEquals(Math.max(0, entries.get(i).startMillis - entries.get(i - 1).endMillis), entries.get(i).waitBeforeMillis,
					"等待时长 = 两条之间的空档");
			}
		}
	}

	// ---------------------------------------------------------------- ② 出库提前量

	/** ② 出库那条比首发**早 leadTimeMillis**，终点是首发站的站台；回库在末趟处理完之后。 */
	@Test
	public void theYardDepartureIsPulledForwardByTheLeadTime() {
		final MmtrDiagram diagram = MmtrDiagram.generate(line(), pattern(), fleet(N, 1), TIMES);
		for (final MmtrDiagram.Working working : diagram.scheduledWorkings()) {
			final MmtrDiagram.Entry depart = working.departYard();
			assertNotNull(depart);
			final MmtrServicePlan.Trip first = working.trips().get(0);
			assertEquals(first.departureMillis - LEAD, depart.startMillis, working.consistId + " 出库前移 " + LEAD + " ms");
			assertEquals(first.departureMillis, depart.endMillis, "出库那条到点就是首发时刻（在站台上等出发）");
			assertEquals(first.stopTimes.get(0).stationId, depart.stationId, "出库开到首发站台");
			assertEquals(first.stopTimes.get(0).platformId, depart.platformId);
			assertEquals(42, depart.sidingId, "从车场股道出发");

			final MmtrDiagram.Entry stable = working.stableYard();
			assertNotNull(stable);
			assertEquals(working.lastDoneMillis(), stable.startMillis, "末趟处理完就回库");
			assertEquals(42, stable.sidingId, "回到同一个车场股道");
		}
	}

	// ---------------------------------------------------------------- ③ 替补不占趟次

	/** ③ 替补**一个趟次都不占**，也不出现在交路里。 */
	@Test
	public void sparesTakeNoTrips() {
		final MmtrDiagram diagram = MmtrDiagram.generate(line(), pattern(), fleet(N, 2), TIMES);
		assertEquals(2, diagram.spareConsistIds.size());
		assertTrue(diagram.spareConsistIds.contains("S1") && diagram.spareConsistIds.contains("S2"));
		for (final MmtrDiagram.Working working : diagram.workings) {
			assertFalse(working.consistId.startsWith("S"), "替补 " + working.consistId + " 不该出现在交路里");
		}
		for (final String spare : diagram.spareConsistIds) {
			assertFalse(diagram.scheduledWorkings().stream().anyMatch(working -> working.consistId.equals(spare)),
				"替补 " + spare + " 不占趟次");
		}
		assertEquals(2 * MmtrServicePlan.departureSlots(pattern()).size(), diagram.totalTripCount(),
			"全部趟次（往 + 返）都落在套班车底上");
	}

	// ---------------------------------------------------------------- ⑤ 环状套班闭合

	/** ⑤ 套班是**环形**的：第 i 槽位归第 (i mod N) 辆，每辆车趟数相差 ≤ 1，首尾闭合。 */
	@Test
	public void theRotaIsCyclicAndClosesAtBothEnds() {
		final MmtrDiagram diagram = MmtrDiagram.generate(line(), pattern(), fleet(N, 1), TIMES);
		final int slots = MmtrServicePlan.departureSlots(pattern()).size();
		assertEquals(48, slots, "48 个发车槽位（18 平峰 + 30 高峰）");

		final ObjectArrayList<MmtrDiagram.Working> workings = diagram.scheduledWorkings();
		assertEquals(N, workings.size());
		int min = Integer.MAX_VALUE;
		int max = 0;
		int assigned = 0;
		for (int v = 0; v < N; v++) {
			final MmtrDiagram.Working working = workings.get(v);
			// 每辆车拿到的是槽位 v, v+N, v+2N …，每个槽位含"往 + 返"两趟
			final int expectedSlots = (slots - 1 - v) / N + 1;
			assertEquals(expectedSlots * 2, working.tripCount(),
				working.consistId + " 的槽位应当是 " + v + " + k×" + N);
			assigned += expectedSlots;
			min = Math.min(min, expectedSlots);
			max = Math.max(max, expectedSlots);
		}
		assertEquals(slots, assigned, "48 个槽位一个不少地分给了 8 辆车（环形套班不丢趟）");
		assertTrue(max - min <= 1, "每辆车承担的发车数相差不超过 1：" + min + "–" + max);

		// 首尾闭合：第 1 辆车的首趟就是首班；把每辆车的往程趟合起来 = 整张发车时刻表（一趟不丢）
		assertEquals(H07, workings.get(0).firstDepartureMillis(), "第 1 辆车的首趟就是首班");
		final ObjectArrayList<Long> assignedDepartures = new ObjectArrayList<>();
		for (final MmtrDiagram.Working working : workings) {
			for (final MmtrServicePlan.Trip trip : working.trips()) {
				if (trip.direction == MmtrServicePlan.Trip.Direction.OUT) {
					assignedDepartures.add(trip.departureMillis);
				}
			}
		}
		assignedDepartures.sort(null);
		assertEquals(MmtrServicePlan.departureSlots(pattern()), assignedDepartures,
			"各车的往程趟合起来正好是整张发车时刻表（首尾都在里面，一趟不丢）");
		for (final MmtrDiagram.Working working : workings) {
			assertTrue(working.firstDepartureMillis() >= H07, "没人早于运营开始发车");
			assertTrue(working.lastDoneMillis() >= working.firstDepartureMillis(), "收车不早于首发");
		}
	}

	/** ⑤ 续：环线的套班同样闭合（每趟首尾同站 → 末趟终点 = 首趟起点）。 */
	@Test
	public void aLoopLineClosesItsRotaToo() {
		final MmtrLine loop = new MmtrLine("L2", "环线");
		loop.yardSidingId = 7;
		loop.loop = true;
		loop.leadTimeMillis = LEAD;
		loop.addStop(2001, 3001, 30_000);
		loop.addStop(2002, 3002, 30_000);
		loop.addStop(2001, 3001, 30_000);

		final MmtrDiagram diagram = MmtrDiagram.generate(loop, pattern(), fleet(N, 0), TIMES);
		for (final MmtrDiagram.Working working : diagram.scheduledWorkings()) {
			final ObjectArrayList<MmtrServicePlan.Trip> trips = working.trips();
			for (final MmtrServicePlan.Trip trip : trips) {
				assertEquals(trip.stopTimes.get(0).stationId, trip.stopTimes.get(trip.stopTimes.size() - 1).stationId,
					"环线每趟首尾同站");
			}
			assertEquals(trips.get(0).stopTimes.get(0).stationId,
				trips.get(trips.size() - 1).stopTimes.get(trips.get(trips.size() - 1).stopTimes.size() - 1).stationId,
				"首趟起点 = 末趟终点（环状套班闭合）");
		}
	}

	/** 车底不够时：交路照建（能排多少排多少），但把"不够"带在身上 —— 与 P1 的加载期校验同一句话。 */
	@Test
	public void notEnoughConsistsStillBuildsButCarriesTheProblem() {
		final MmtrDiagram diagram = MmtrDiagram.generate(line(), pattern(), fleet(3, 0), TIMES);
		assertEquals(N, diagram.requiredConsists, "需要 9 个");
		assertEquals(3, diagram.scheduledWorkings().size(), "只有 3 个就排 3 个");
		assertNotNull(diagram.capacityProblem, "要带上那句原因");
		assertTrue(diagram.capacityProblem.contains("N=9"), diagram.capacityProblem);
		assertFalse(diagram.capacityProblem.isBlank());
	}

	/** 没有车底时：不崩、不排班，原因可读。 */
	@Test
	public void noFleetMeansNoDiagramRatherThanACrash() {
		final MmtrDiagram diagram = MmtrDiagram.generate(line(), pattern(), new MmtrFleet(), TIMES);
		assertTrue(diagram.scheduledWorkings().isEmpty());
		assertNotNull(diagram.capacityProblem);
		assertEquals(0, diagram.totalTripCount());
	}

	// ---------------------------------------------------------------- ⑤ 逐步展开：每一步从"它最早能开始"算起

	/**
	 * ⑤ **站台作业的计划时刻是"到站"，不是"发车"**（notes/155 现场）。
	 *
	 * <p>修前 `MmtrPlanTasks` 把站台作业的计划时刻写成了 {@code stop.departureMillis}。后果现场量得出来：
	 * 车到站的那一刻这一趟还不许派（{@code earliestMs} 是发车时刻），于是先干等一个停留；
	 * 等到点挂上去，执行器再按计划停一个停留 —— **每站多花整整一个停留**。
	 * 十站一趟多 5 分钟，第二趟就冲破 {@code LATE_GRACE_MILLIS}（10 分钟）开始"跳过不停"，
	 * 现场的观感就是用户报的"只停前两站"。</p>
	 *
	 * <p>红证：把那句改回 {@code stop.departureMillis} ⇒ 本用例第一段的 {@code assertEquals} 红。</p>
	 */
	@Test
	public void everyPlanStepIsStampedWithWhenItCanStart() {
		final MmtrLine line = line();
		final MmtrDiagram diagram = MmtrDiagram.generate(line, pattern(), fleet(N, 1), TIMES);
		final MmtrDiagram.Working first = diagram.scheduledWorkings().get(0);
		final MmtrServicePlan.StopTime secondStop = first.trips().get(0).stopTimes.get(1);
		boolean checked = false;
		for (final MmtrTask task : MmtrPlanTasks.expand(line, first)) {
			if (task instanceof final StationServiceTask service && service.targetRef == secondStop.platformId) {
				assertEquals(secondStop.arrivalMillis, service.earliestMs, "站台作业从**到站**那一刻开始算，不是发车时刻");
				assertEquals(secondStop.dwellMillis(), service.dwellMs, "停留照计划给的值");
				checked = true;
				break;
			}
		}
		assertTrue(checked, "第一趟第二站必须有站台作业");
	}

	/**
	 * ⑤ 续：**每一步的计划时刻不早于上一步最早能结束的时刻**（"区间很短、停留很长"的线最容易踩）。
	 *
	 * <p>这条判据是通用的：站台作业的"最早能结束"就是 `计划时刻 + 停留`，其余步骤本身不占时间。
	 * 拿现场那种形状再走一遍（区间 10 秒 < 停留 30 秒）—— 修前站台作业按发车时刻起算，
	 * 下一步的计划时刻会落在"本站停留还没结束"的时候，这条立刻红。</p>
	 *
	 * <p>红证：把 {@code MmtrPlanTasks} 里站台作业的时刻改回 {@code stop.departureMillis} ⇒ 本用例红。</p>
	 */
	@Test
	public void noStepIsPlannedBeforeThePreviousOneCanFinish() {
		final MmtrLine line = line();
		// 现场形状：区间比停留还短
		final MmtrDiagram dense = MmtrDiagram.generate(line, pattern(), fleet(N, 1), MmtrTravelTimes.uniform(10_000, 30_000));
		int services = 0;
		for (final MmtrDiagram.Working working : dense.scheduledWorkings()) {
			long previousEnd = Long.MIN_VALUE;
			for (final MmtrTask task : MmtrPlanTasks.expand(line, working)) {
				assertTrue(task.earliestMs >= previousEnd, working.consistId + " " + task.taskId + "（" + task.kind()
					+ "）计划 " + MmtrPattern.hhmm(task.earliestMs) + "，而上一步最早也要到 " + MmtrPattern.hhmm(previousEnd) + " 才结束");
				if (task instanceof final StationServiceTask service) {
					services++;
					previousEnd = task.earliestMs + service.dwellMs;
				} else {
					previousEnd = task.earliestMs;
				}
			}
		}
		assertTrue(services > 0, "这条线的交路里必须有站台作业");
	}
}
