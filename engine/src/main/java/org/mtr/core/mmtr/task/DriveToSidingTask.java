package org.mtr.core.mmtr.task;

/**
 * 调车/回库/转线: drive to a siding and stop. No door work; completion = stopped on the siding.
 */
public final class DriveToSidingTask extends MmtrTask {

	public DriveToSidingTask(String taskId, long sidingId, long dueMs) {
		super(taskId, TARGET_SIDING, sidingId, dueMs);
	}

	@Override
	protected String requiredTargetKind() {
		return TARGET_SIDING;
	}

	@Override
	public MmtrTaskKind kind() {
		return MmtrTaskKind.DRIVE_TO_SIDING;
	}

	@Override
	public String describe() {
		return "开往股道 #" + targetRef + " 停稳";
	}

	@Override
	public String validate() {
		return invalidWhenTargetMismatch();
	}
}
