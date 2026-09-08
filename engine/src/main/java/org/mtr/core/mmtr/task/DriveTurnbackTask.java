package org.mtr.core.mmtr.task;

/**
 * 折返/掉头: the consist has reached the terminal and must run around the turnback lead
 * (via the junction tables) onto the return track, ending on the given siding/track.
 * The engine has no reverse gear, so 掉头 is itself a driving task, not an action.
 */
public final class DriveTurnbackTask extends MmtrTask {

	/** Optional via-rail hint (turnback lead hex); empty = route planner decides. */
	public String viaRailHex = "";

	public DriveTurnbackTask(String taskId, long returnSidingId, long dueMs) {
		super(taskId, TARGET_SIDING, returnSidingId, dueMs);
	}

	@Override
	protected String requiredTargetKind() {
		return TARGET_SIDING;
	}

	@Override
	public MmtrTaskKind kind() {
		return MmtrTaskKind.DRIVE_TURNBACK;
	}

	@Override
	public String describe() {
		return "经折返轨掉头至股道 #" + targetRef;
	}

	@Override
	public String validate() {
		return invalidWhenTargetMismatch();
	}
}
