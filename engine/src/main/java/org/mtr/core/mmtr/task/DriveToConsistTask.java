package org.mtr.core.mmtr.task;

/**
 * 连挂走行 (interface placeholder): drive to and stop at the coupling point of the target
 * consist (a derived non-powered vehicle once the Vehicle derivation slice lands). Completion =
 * reached the coupling point at coupling speed; the actual "hooked" transition belongs to the
 * future COUPLE action on the derived-vehicle model.
 */
public final class DriveToConsistTask extends MmtrTask {

	/** Coupling approach speed cap, km/h (0 = engine default shunting speed). */
	public double approachSpeedKmh;

	public DriveToConsistTask(String taskId, long consistRef, long dueMs, double approachSpeedKmh) {
		super(taskId, TARGET_CONSIST, consistRef, dueMs);
		this.approachSpeedKmh = Math.max(0, approachSpeedKmh);
	}

	@Override
	protected String requiredTargetKind() {
		return TARGET_CONSIST;
	}

	@Override
	public MmtrTaskKind kind() {
		return MmtrTaskKind.DRIVE_TO_CONSIST;
	}

	@Override
	public String describe() {
		return "驶向目标车列 #" + targetRef + " 连挂区（低速对位）";
	}

	@Override
	public String validate() {
		return invalidWhenTargetMismatch();
	}
}
