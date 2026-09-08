package org.mtr.core.mmtr.task;

/**
 * 货运装卸停留 (placeholder until freight-point objects exist): stop at a freight point or
 * stabling siding and work for a duration. Completion = work time served.
 */
public final class FreightWorkTask extends MmtrTask {

	/** Planned loading/unloading time, milliseconds. */
	public long workMs;

	public FreightWorkTask(String taskId, long targetRef, String targetKind, long dueMs, long workMs) {
		super(taskId, targetKind, targetRef, dueMs);
		this.workMs = Math.max(0, workMs);
	}

	@Override
	public MmtrTaskKind kind() {
		return MmtrTaskKind.FREIGHT_WORK;
	}

	@Override
	public String describe() {
		return "货运点 #" + targetRef + " 装卸停留" + (workMs > 0 ? "（" + workMs / 1000 + "s）" : "");
	}

	@Override
	public String validate() {
		if (!TARGET_FREIGHT.equals(targetKind) && !TARGET_SIDING.equals(targetKind)) {
			return "货运作业目标应为 FREIGHT/SIDING，实际 " + targetKind + "（task " + taskId + "）";
		}
		return "";
	}
}
