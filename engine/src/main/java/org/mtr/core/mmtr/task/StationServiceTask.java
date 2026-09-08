package org.mtr.core.mmtr.task;

/**
 * 客运停站作业: standing at the platform (target), run the door cycle - open, dwell, close.
 * Completion = doors closed (the timetable's 发车时刻). The arrival/departure split lets the
 * schedule express "stop without doors" (DriveToPlatform alone) vs full passenger work.
 */
public final class StationServiceTask extends MmtrTask {

	/** Planned dwell at the platform, milliseconds (0 = engine default). */
	public long dwellMs;

	public StationServiceTask(String taskId, long platformId, long dueMs, long dwellMs) {
		super(taskId, TARGET_PLATFORM, platformId, dueMs);
		this.dwellMs = Math.max(0, dwellMs);
	}

	@Override
	protected String requiredTargetKind() {
		return TARGET_PLATFORM;
	}

	@Override
	public MmtrTaskKind kind() {
		return MmtrTaskKind.STATION_SERVICE;
	}

	@Override
	public String describe() {
		return "站台 #" + targetRef + " 开关门停站" + (dwellMs > 0 ? "（停留 " + dwellMs / 1000 + "s）" : "");
	}

	@Override
	public String validate() {
		return invalidWhenTargetMismatch();
	}
}
