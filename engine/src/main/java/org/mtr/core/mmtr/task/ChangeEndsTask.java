package org.mtr.core.mmtr.task;

/**
 * 原地换端 (change ends in place): the consist is standing and the crew moves to the other cab.
 *
 * <p>This is the terminal-reversal task of the real world — a train arrives at the end of the line,
 * the driver changes ends and departs the other way. It is deliberately <strong>not</strong>
 * {@link DriveTurnbackTask}: no turnback lead is needed, the train does not move at all (the B-series
 * consist body makes 换端 a cab change, see the design doc §3.2), and there is no duration to model
 * (§3.5.1: a special-case operation on a dedicated timetable, the schedule records only the actual
 * moment). The task therefore has no target object.</p>
 */
public final class ChangeEndsTask extends MmtrTask {

	public ChangeEndsTask(String taskId, long dueMs) {
		super(taskId, "", 0, dueMs);
	}

	@Override
	protected String requiredTargetKind() {
		return "";
	}

	@Override
	public MmtrTaskKind kind() {
		return MmtrTaskKind.CHANGE_ENDS;
	}

	@Override
	public String describe() {
		return "换端（原地，司机转至另一端驾驶室）";
	}

	@Override
	public String validate() {
		return ""; // no target, no parameters
	}
}
