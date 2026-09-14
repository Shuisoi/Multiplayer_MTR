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

	/**
	 * 换端是**原地动作**（notes/150）：没有目标对象，但**派得出去**（判据 {@link MmtrTask#dispatchable()}）。
	 *
	 * <p>执行在车辆侧：到点停稳 → 翻司机台方向（{@code Vehicle.changeEndsMmtrMotion}）。
	 * 修前没有这条：计划里的换端步骤既派不出去、也没人执行，交路一到终点就停住。</p>
	 */
	@Override
	public boolean inPlace() {
		return true;
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
