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
