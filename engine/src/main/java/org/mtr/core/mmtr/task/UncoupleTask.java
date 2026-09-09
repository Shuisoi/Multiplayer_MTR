package org.mtr.core.mmtr.task;

/**
 * C9 解挂任务: cut this formation after {@link #cutAfterCarIndex} wherever it now stands.
 *
 * <p>Same "cut after car k" semantics as the {@code uncouple <id> <car>} command, the coupler key in
 * game and the yard UNCOUPLE step - the car index is what an operator aims at, and the engine maps it
 * onto a seam and refuses a boundary that has no coupler.
 */
public final class UncoupleTask extends MmtrTask {

	/** Car index the cut is made after (0-based; the tail starts at the next car). */
	public int cutAfterCarIndex;

	public UncoupleTask(String taskId, long dueMs, int cutAfterCarIndex) {
		super(taskId, TARGET_CONSIST, 0, dueMs);
		this.cutAfterCarIndex = cutAfterCarIndex;
	}

	@Override
	public MmtrTaskKind kind() {
		return MmtrTaskKind.UNCOUPLE;
	}

	@Override
	public String describe() {
		return "在第 " + (cutAfterCarIndex + 1) + " 节后解挂";
	}

	@Override
	public String validate() {
		return cutAfterCarIndex < 0 ? "解挂需要车厢序号（cutAfterCarIndex）" : "";
	}
}
