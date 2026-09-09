package org.mtr.core.mmtr.task;

/**
 * C9 连挂任务: attach the consist standing on the siding this train has drawn up to.
 *
 * <p>The task is an ACTION, not a movement: the approach is its own MOVE_TO step (which may be a
 * cross-track run under a 调车授权), and this step performs the surgery once the train stands in
 * coupler reach. {@link #targetJobId} names the job whose stock is to be coupled on - the normal case,
 * because a consist job's stock is the thing the timetable knows about - and an empty id means
 * "whatever else stands on this rail".
 *
 * <p>With automatic couplers (C8) the two trains may already be latched together by the time the step
 * runs; the scheduler treats an absorbed target as a completed step, so the same job works for both
 * coupler types.
 */
public final class CoupleTask extends MmtrTask {

	/** Stable id of the job whose stock is coupled onto this consist; empty = the consist standing here. */
	public String targetJobId = "";

	public CoupleTask(String taskId, long dueMs, String targetJobId) {
		super(taskId, TARGET_CONSIST, 0, dueMs);
		this.targetJobId = targetJobId == null ? "" : targetJobId.trim();
	}

	@Override
	public MmtrTaskKind kind() {
		return MmtrTaskKind.COUPLE;
	}

	@Override
	public String describe() {
		return targetJobId.isEmpty() ? "与停在本道的车列连挂" : "与作业单 " + targetJobId + " 的车列连挂";
	}

	@Override
	public String validate() {
		return "";
	}
}
