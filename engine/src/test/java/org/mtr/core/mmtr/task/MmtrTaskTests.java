package org.mtr.core.mmtr.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task taxonomy v1 (任务体系): concrete kinds derive from {@link MmtrTask} and carry their
 * where/when/what semantics - kind identity, target-kind rules, time window and describe().
 */
public final class MmtrTaskTests {

	private static final long PLATFORM_5 = -5224482131162675969L;
	private static final long SIDING_3 = 4321759533923363700L;
	private static final long DUE = 7 * 3_600_000L + 5 * 60_000L; // 07:05

	@Test
	public void driveToSidingKindTargetAndDescribe() {
		final DriveToSidingTask task = new DriveToSidingTask("s1", SIDING_3, DUE);
		task.earliestMs = DUE - 60_000L;
		task.note = "退库";
		assertEquals(MmtrTaskKind.DRIVE_TO_SIDING, task.kind());
		assertEquals(MmtrTask.TARGET_SIDING, task.targetKind);
		assertEquals(SIDING_3, task.targetRef);
		assertEquals("", task.validate());
		assertFalse(task.describe().isEmpty());
		assertEquals("", task.invalidWhenTargetMismatch());
	}

	@Test
	public void driveToPlatformRejectsWrongTargetKind() {
		final DriveToPlatformTask ok = new DriveToPlatformTask("a1", PLATFORM_5, DUE);
		assertEquals("", ok.validate());
		// Constructors pin the target kind; validate() guards hand-edited / deserialized tasks.
		final DriveToPlatformTask bad = new DriveToPlatformTask("a2", SIDING_3, DUE);
		bad.targetKind = MmtrTask.TARGET_SIDING;
		assertNotEquals("", bad.validate(), "platform task must reject a siding target");
	}

	@Test
	public void stationServiceCarriesDwellAndPlatformTarget() {
		final StationServiceTask task = new StationServiceTask("a1s", PLATFORM_5, DUE + 60_000L, 45_000L);
		assertEquals(MmtrTaskKind.STATION_SERVICE, task.kind());
		assertEquals(45_000L, task.dwellMs);
		assertEquals("", task.validate());
		assertTrue(task.describe().contains("45s"), "describe shows the dwell: " + task.describe());
		final StationServiceTask noDwell = new StationServiceTask("a1s2", PLATFORM_5, DUE, -1);
		assertEquals(0, noDwell.dwellMs, "negative dwell clamps to engine default");
	}

	@Test
	public void turnbackTargetsTheReturnSiding() {
		final DriveTurnbackTask task = new DriveTurnbackTask("tb", SIDING_3, DUE);
		assertEquals(MmtrTaskKind.DRIVE_TURNBACK, task.kind());
		assertEquals("", task.validate());
	}

	@Test
	public void driveToConsistIsTheCouplingApproachPlaceholder() {
		final DriveToConsistTask task = new DriveToConsistTask("c1", 42L, DUE, 8.0);
		assertEquals(MmtrTaskKind.DRIVE_TO_CONSIST, task.kind());
		assertEquals(MmtrTask.TARGET_CONSIST, task.targetKind);
		assertEquals(8.0, task.approachSpeedKmh);
		assertEquals("", task.validate());
		assertTrue(task.describe().contains("42"), "describe mentions the target consist: " + task.describe());
	}

	@Test
	public void freightWorkAcceptsFreightOrSidingTarget() {
		final FreightWorkTask freight = new FreightWorkTask("f1", 7L, MmtrTask.TARGET_FREIGHT, DUE, 120_000L);
		assertEquals("", freight.validate());
		assertEquals(120_000L, freight.workMs);
		final FreightWorkTask stabling = new FreightWorkTask("f2", SIDING_3, MmtrTask.TARGET_SIDING, DUE, 60_000L);
		assertEquals("", stabling.validate());
		final FreightWorkTask bad = new FreightWorkTask("f3", 7L, MmtrTask.TARGET_PLATFORM, DUE, 0);
		assertNotEquals("", bad.validate(), "freight work must not target a platform");
	}

	@Test
	public void changeEndsNeedsNoTargetAndSaysSo() {
		final ChangeEndsTask task = new ChangeEndsTask("c1", DUE);
		assertEquals(MmtrTaskKind.CHANGE_ENDS, task.kind());
		assertEquals("", task.validate(), "换端 has no target to validate");
		assertEquals(0, task.targetRef);
		assertEquals("", task.targetKind);
		assertTrue(task.describe().contains("换端"), "describe names the operation: " + task.describe());
		assertEquals(DUE, task.dueMs, "the timetable still carries the planned moment");
	}

	/**
	 * **没有目标 ≠ 派不出去**（notes/150）。
	 *
	 * <p>现场：计划里的换端步骤在终点**永远派不出去** —— 派发路径要求 {@code targetRef != 0}，
	 * 而换端按设计没有目标。派发器只会一直重试（重试数在涨），整条交路停在终点，界面上看不出是哪一步。
	 * 判据因此改成"有目标 **或** 是原地动作"。</p>
	 *
	 * <p>红证：把 {@link MmtrTask#dispatchable()} 改回 {@code targetRef != 0}，本用例第一段就红。</p>
	 */
	@Test
	public void anInPlaceTaskIsDispatchableWithoutATarget() {
		final ChangeEndsTask changeEnds = new ChangeEndsTask("c1", DUE);
		assertTrue(changeEnds.inPlace(), "换端是原地动作");
		assertTrue(changeEnds.dispatchable(), "原地动作没有目标也派得出去");

		// 反过来：真有目标要走、却偏偏没给目标的任务，不许放出去（否则车会"开到 0 号目标"）
		final DriveToPlatformTask noTarget = new DriveToPlatformTask("d1", 0, DUE);
		assertFalse(noTarget.inPlace());
		assertFalse(noTarget.dispatchable(), "要开走却没有目标 ⇒ 不能派");
		assertTrue(new DriveToPlatformTask("d2", 1234, DUE).dispatchable(), "有目标的照常派");
	}

	/**
	 * **站台作业该停多久**（notes/155）：计划给的停留与引擎默认取大者。
	 *
	 * <p>现场问题：计划里的"停留 30 秒"**全引擎没有任何地方读**（{@code dwellMs} 只被写、被打日志），
	 * 于是站台作业形同虚设 —— 车到站只是"开往站台"那一步到点停了一下、下一步立刻派出去，
	 * 看起来就是"站台不停"。这条判据把"计划说了算"钉住。</p>
	 *
	 * <p>红证：把 {@code effectiveDwellMillis} 改成恒返回引擎默认 ⇒ 第一段红。</p>
	 */
	@Test
	public void thePlannedDwellWinsOverTheEngineDefault() {
		final StationServiceTask planned = new StationServiceTask("s1", 2001, DUE, 30_000);
		assertEquals(30_000, planned.effectiveDwellMillis(5_000), "计划说停 30 秒就停 30 秒");

		final StationServiceTask withoutPlannedDwell = new StationServiceTask("s2", 2002, DUE, 0);
		assertEquals(5_000, withoutPlannedDwell.effectiveDwellMillis(5_000), "计划没给停留 ⇒ 用引擎默认");

		final StationServiceTask shorterThanDefault = new StationServiceTask("s3", 2003, DUE, 1_000);
		assertEquals(5_000, shorterThanDefault.effectiveDwellMillis(5_000), "计划比默认还短 ⇒ 取默认（别把门开一下就走）");
	}

	@Test
	public void everyConcreteTaskDescribesItself() {
		for (final MmtrTask task : new MmtrTask[]{
			new DriveToSidingTask("t", 1L, DUE),
			new DriveToPlatformTask("t", 2L, DUE),
			new StationServiceTask("t", 3L, DUE, 0),
			new DriveTurnbackTask("t", 4L, DUE),
			new DriveToConsistTask("t", 5L, DUE, 0),
			new FreightWorkTask("t", 6L, MmtrTask.TARGET_FREIGHT, DUE, 0),
			new ChangeEndsTask("t", DUE),
		}) {
			assertFalse(task.toString().isEmpty());
			assertFalse(task.describe().isEmpty(), task.kind() + " must describe its operation");
		}
	}
}
