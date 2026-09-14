package org.mtr.core.mmtr.plan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.job.MmtrCarSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6 验收（{@code 任务系统-线路派生与车底交路-设计.md} §10 的 P6 ①②）：
 *
 * <ol>
 *   <li>替补接续点正确（**从停放位置到接续站的前置任务**）；</li>
 *   <li>来不及 → **取消该趟** + 发出**带理由**的事件。</li>
 * </ol>
 */
public final class MmtrSubstitutionTests {

	private static final long MIN = 60_000L;
	private static final long H07 = 7 * 60 * MIN;
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
		// 三个发车槽位：07:00 / 07:20 / 07:40（够摆出"在途一趟 + 接续一趟"）
		return new MmtrPattern("L1").addSegment(H07, H07 + 45 * MIN, 20 * MIN);
	}

	/** ① 替补接手：前置任务从车场开到**接续站的站台**，计划开始 = 接续趟发车 − 出库前移量。 */
	@Test
	public void theHandoverComesWithAPrePositionTaskToTheHandoverStation() {
		final MmtrLine line = line();
		final MmtrDiagram diagram = MmtrDiagram.generate(line, pattern(), fleet(1, 1), TIMES);
		final MmtrDiagram.Working source = diagram.workings.get(0);
		// 现在 07:25：07:20 那趟在途（冻结到 07:32），接续点落在 07:40 那趟
		final long now = H07 + 25 * MIN;
		final MmtrSubstitution.Result result = MmtrSubstitution.handOver(line, source, now, H07 + 32 * MIN, "S1", "P6");

		assertTrue(result.handoverHappened(), "应当接上了：" + result.notes);
		assertNotNull(result.replacement);
		assertEquals("S1", result.replacement.consistId);
		// 前置任务在最前，且开到接续站的站台
		final MmtrDiagram.Entry depart = result.replacement.entries.get(0);
		assertEquals(MmtrDiagram.Entry.Kind.DEPART_YARD, depart.kind);
		final MmtrServicePlan.Trip handoverTrip = result.replacement.trips().get(0);
		assertEquals(result.handoverTripId, handoverTrip.tripId, "接续点就是接手的头一趟");
		assertEquals(handoverTrip.stopTimes.get(0).platformId, depart.platformId, "前置任务开到接续站的站台");
		assertEquals(42, depart.sidingId, "从车场（本线路的出库股道）出发");
		assertEquals(handoverTrip.departureMillis - LEAD, depart.startMillis, "计划开始 = 接续趟发车 − 出库前移量");
		assertEquals(handoverTrip.departureMillis, depart.endMillis, "到站台上等出发");
		// 时刻表一行不改：接手过来的趟次**原样**（同一个对象）
		assertEquals(handoverTrip.departureMillis, source.trips().stream()
			.filter(trip -> trip.tripId.equals(handoverTrip.tripId)).findFirst().orElseThrow().departureMillis);
		// 冻结期内的那趟留给故障车
		assertTrue(result.remaining.trips().stream().allMatch(trip -> trip.departureMillis < H07 + 32 * MIN),
			"故障车只剩冻结期内的趟次");
		assertFalse(result.notes.isEmpty(), "要有可读说明：" + result.notes);
	}

	/** ② 来不及：该趟**取消**，并发出带理由的事件（前台看得到为什么）。 */
	@Test
	public void aSubstituteThatCannotMakeItCancelsTheTripAndSaysWhy() {
		final MmtrLine line = line();
		final MmtrDiagram diagram = MmtrDiagram.generate(line, pattern(), fleet(1, 1), TIMES);
		final MmtrDiagram.Working source = diagram.workings.get(0);
		// 现在 07:50：剩下的最后一趟（07:52 那趟返程）也只剩 2 分钟，而前置任务要 5 分钟 → 全部来不及
		final long now = H07 + 50 * MIN;
		final MmtrSubstitution.Result result = MmtrSubstitution.handOver(line, source, now, now, "S1", "P6");

		assertFalse(result.handoverHappened(), "全都来不及 ⇒ 不接手：" + result.notes);
		assertFalse(result.cancelledTripIds.isEmpty(), "要报出取消了哪几趟");
		assertNotNull(result.event, "取消必须对外发事件");
		assertTrue(result.event.reason.contains("来不及"), "事件理由要说得清：" + result.event.reason);
		assertEquals(1, result.event.severity);
		assertTrue(result.notes.stream().anyMatch(note -> note.contains("取消")), result.notes.toString());
	}

	/** 没有替补可用：全部取消 + 事件（与"有替补但来不及"区分开）。 */
	@Test
	public void withoutAnySpareEverythingLeftIsCancelled() {
		final MmtrLine line = line();
		final MmtrDiagram diagram = MmtrDiagram.generate(line, pattern(), fleet(1, 0), TIMES);
		final MmtrSubstitution.Result result = MmtrSubstitution.handOver(
			line, diagram.workings.get(0), H07 + 5 * MIN, H07 + 5 * MIN, null, "P6");

		assertNull(result.replacement);
		assertFalse(result.cancelledTripIds.isEmpty());
		assertNotNull(result.event);
		assertTrue(result.event.reason.contains("没有替补"), result.event.reason);
		assertTrue(result.notes.get(0).contains("没有替补"), result.notes.get(0));
	}

	/** 替补挑选：按代码定序取第一个**没上场**的（可复现），且不会挑已经在跑的那个。 */
	@Test
	public void theFirstUnusedSpareIsChosen() {
		final MmtrFleet fleet = fleet(2, 2);
		final java.util.HashSet<String> used = new java.util.HashSet<>();
		assertEquals("S1", MmtrSubstitution.firstSpare(fleet, used));
		used.add("S1");
		assertEquals("S2", MmtrSubstitution.firstSpare(fleet, used));
		used.add("S2");
		assertNull(MmtrSubstitution.firstSpare(fleet, used), "替补用完了就没人可顶");
		assertNull(MmtrSubstitution.firstSpare(null, used));
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
}
