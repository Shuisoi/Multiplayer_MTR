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

	/**
	 * **这次站台作业实际要停多久**（notes/155）。
	 *
	 * <p>为什么要有这一句：计划里的"停留 30 秒"原来**没有任何地方读**（{@code dwellMs} 只被写、被打进日志），
	 * 于是站台作业形同虚设 —— 车到站只是"开往站台"那一步到点停了一下，下一步立刻派出去；
	 * 现场看起来就是"车在站台不停/一闪而过"（用户实测：只有前两站像停了）。
	 * 现在由车辆侧的**任务执行器**按这个时长开门 → 停留 → 关门。</p>
	 *
	 * @param engineDefaultMillis 计划没给停留时用的引擎默认值
	 */
	public long effectiveDwellMillis(long engineDefaultMillis) {
		return Math.max(Math.max(0, engineDefaultMillis), dwellMs);
	}

	/**
	 * **站台作业是原地动作**（notes/155）：车已经在站台上了，这活是"开门、停够、关门"，
	 * 没有任何要去的地方。
	 *
	 * <p>修前它走的是"有目的地的任务"那条路（{@code inPlace()} 默认 false，目标 = 站台），
	 * 而那条路上有一条**捷径**：目标轨就是脚下这条轨 ⇒ 立刻算"已经在那儿了" → 任务**当 tick 完成**。
	 * 于是"停留 30s"永远轮不到执行：站台作业被当成"车已经在站台 ⇒ 没事可做"。
	 * 现场的观感正是用户报的"车到站不停/一闪而过"。</p>
	 *
	 * <p>车**不在**那个站台上时（比如上一步进站被撤了活、车还停在半路），
	 * {@code Vehicle#mmtrMotionSelfArmMission} 会照常规划一趟开过去 —— 见那里的站台在场判据。</p>
	 */
	@Override
	public boolean inPlace() {
		return true;
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
