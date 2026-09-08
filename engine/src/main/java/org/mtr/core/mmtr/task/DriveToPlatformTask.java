package org.mtr.core.mmtr.task;

/**
 * 客运到站: drive to a platform and stop aligned. No door cycle (that is the separate
 * {@link StationServiceTask}); completion = arrived and stopped at the platform.
 */
public final class DriveToPlatformTask extends MmtrTask {

	public DriveToPlatformTask(String taskId, long platformId, long dueMs) {
		super(taskId, TARGET_PLATFORM, platformId, dueMs);
	}

	@Override
	protected String requiredTargetKind() {
		return TARGET_PLATFORM;
	}

	@Override
	public MmtrTaskKind kind() {
		return MmtrTaskKind.DRIVE_TO_PLATFORM;
	}

	@Override
	public String describe() {
		return "开往站台 #" + targetRef + " 到站停稳";
	}

	@Override
	public String validate() {
		return invalidWhenTargetMismatch();
	}
}
