package org.mtr.core.mmtr.task;

import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.mmtr.job.MmtrJobStep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Job step → task instance mapping (作业单步骤 → 任务实例): the scheduler instantiates the
 * matching task per step and the mission container carries it for timetable/interlocking.
 */
public final class MmtrTaskFactoryTests {

	private static final long PLATFORM = 8840155259502644057L;
	private static final long SIDING = 4321759533923363700L;
	private static final long DUE = 7 * 3_600_000L + 10 * 60_000L;

	private static MmtrJobStep step(MmtrJobStep.StepType type, long targetId) {
		final MmtrJobStep step = new MmtrJobStep();
		step.stepId = "s1";
		step.type = type;
		step.targetId = targetId;
		step.dueTimeOfDayMs = DUE;
		step.note = "上车乘客";
		return step;
	}

	@Test
	public void moveToPlatformMapsToDriveToPlatform() {
		final MmtrTask task = MmtrTaskFactory.fromStep(step(MmtrJobStep.StepType.MOVE_TO, PLATFORM), true);
		assertNotNull(task);
		final DriveToPlatformTask mapped = assertInstanceOf(DriveToPlatformTask.class, task);
		assertEquals(PLATFORM, mapped.targetRef);
		assertEquals(DUE, mapped.dueMs);
		assertEquals("", mapped.validate());
	}

	@Test
	public void moveToSidingMapsToDriveToSiding() {
		final MmtrTask task = MmtrTaskFactory.fromStep(step(MmtrJobStep.StepType.MOVE_TO, SIDING), false);
		assertNotNull(task);
		final DriveToSidingTask mapped = assertInstanceOf(DriveToSidingTask.class, task);
		assertEquals(SIDING, mapped.targetRef);
		assertEquals("", mapped.validate());
	}

	@Test
	public void serveMapsToStationService() {
		final MmtrTask task = MmtrTaskFactory.fromStep(step(MmtrJobStep.StepType.SERVE, PLATFORM), true);
		assertNotNull(task);
		final StationServiceTask mapped = assertInstanceOf(StationServiceTask.class, task);
		assertEquals(PLATFORM, mapped.targetRef);
		assertEquals(DUE, mapped.dueMs);
	}

	@Test
	public void coupleAndUncoupleStayNullUntilDerivedVehicleSlice() {
		assertNull(MmtrTaskFactory.fromStep(step(MmtrJobStep.StepType.COUPLE, 0), false));
		assertNull(MmtrTaskFactory.fromStep(step(MmtrJobStep.StepType.UNCOUPLE, 0), false));
	}

	@Test
	public void missionCarriesTheMappedTask() {
		final MmtrTask task = MmtrTaskFactory.fromStep(step(MmtrJobStep.StepType.MOVE_TO, PLATFORM), true);
		final MmtrMission mission = new MmtrMission(1L, MmtrMission.Kind.PASSENGER, SIDING, PLATFORM, 1000L);
		assertNull(mission.getTask());
		mission.attachTask(task);
		assertNotNull(mission.getTask());
		assertEquals(MmtrTaskKind.DRIVE_TO_PLATFORM, mission.getTask().kind());
		assertEquals(MmtrMission.Kind.PASSENGER, mission.getKind(), "platform move stays PASSENGER for doors/dwell");
	}
}
