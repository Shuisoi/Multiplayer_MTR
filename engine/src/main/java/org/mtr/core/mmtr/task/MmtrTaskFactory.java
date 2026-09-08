package org.mtr.core.mmtr.task;

import org.jspecify.annotations.Nullable;
import org.mtr.core.mmtr.job.MmtrJobStep;

/**
 * Maps a consist-job step onto a task instance (作业单步骤 → 任务实例). The scheduler executes
 * a step by instantiating the matching task (its where/when/what) and attaches it to the runtime
 * mission container; the timetable/schedule layer and - later - the interlocking read the task.
 *
 * <p>Mapping:
 * <ul>
 *   <li>MOVE_TO platform → {@link DriveToPlatformTask} (arrival, no door work);</li>
 *   <li>MOVE_TO siding   → {@link DriveToSidingTask} (shunting / 退库 return);</li>
 *   <li>SERVE            → {@link StationServiceTask} (door cycle at the platform);</li>
 *   <li>COUPLE/UNCOUPLE  → {@code null} until the derived-vehicle (powered / unpowered) slice
 *       lands; the yard surgery keeps its own semantics meanwhile.</li>
 * </ul>
 */
public final class MmtrTaskFactory {

	private MmtrTaskFactory() {
	}

	/** @param targetIsPlatform whether step.targetId references a platform (else a siding/track) */
	@Nullable
	public static MmtrTask fromStep(MmtrJobStep step, boolean targetIsPlatform) {
		if (step == null) {
			return null;
		}
		final MmtrTask task;
		switch (step.type) {
			case MOVE_TO:
				task = targetIsPlatform
					? new DriveToPlatformTask(step.stepId, step.targetId, step.dueTimeOfDayMs)
					: new DriveToSidingTask(step.stepId, step.targetId, step.dueTimeOfDayMs);
				break;
			case SERVE:
				task = new StationServiceTask(step.stepId, step.targetId, step.dueTimeOfDayMs, 0);
				break;
			case CHANGE_ENDS:
				task = new ChangeEndsTask(step.stepId, step.dueTimeOfDayMs);
				break;
			default:
				return null; // COUPLE / UNCOUPLE: derived-vehicle slice later
		}
		task.note = step.note == null ? "" : step.note;
		return task;
	}
}
