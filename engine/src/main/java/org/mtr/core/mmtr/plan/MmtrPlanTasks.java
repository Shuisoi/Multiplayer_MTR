package org.mtr.core.mmtr.plan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.mmtr.task.ChangeEndsTask;
import org.mtr.core.mmtr.task.DriveToPlatformTask;
import org.mtr.core.mmtr.task.DriveToSidingTask;
import org.mtr.core.mmtr.task.DriveTurnbackTask;
import org.mtr.core.mmtr.task.MmtrTask;
import org.mtr.core.mmtr.task.MmtrTaskKind;
import org.mtr.core.mmtr.task.StationServiceTask;

/**
 * P4：**把交路展开成任务序列**（{@code 任务系统-线路派生与车底交路-设计.md} §3 / §5.2）。
 *
 * <p>交路（P3）里的"一条"是一条*记录*：出库、一趟车、回库。派发层要的是**一步一个任务**，
 * 于是每一趟车按站点展开成：</p>
 *
 * <pre>
 * 中间站：DRIVE_TO_PLATFORM（开到站台）→ STATION_SERVICE（开门—停站—关门）
 * 终点站：DRIVE_TO_PLATFORM → STATION_SERVICE → 终点处理（§5.2 的三态）
 * 出库  ：DRIVE_TO_PLATFORM（车场 → 首发站台）
 * 回库  ：DRIVE_TO_SIDING（末站台 → 车场股道）
 * </pre>
 *
 * <p><b>不新增任务词汇</b>（设计 §1.1）：九种已有类型就够，本类只负责拼装 ——
 * 这也是这份设计最省的地方：任务/执行层早就完备，缺的只是"谁在什么时候挂哪一步"。</p>
 */
public final class MmtrPlanTasks {

	private MmtrPlanTasks() {
	}

	/** 这条线路的终点处理需要哪一步任务（设计 §5.2；环线继续跑，不处理）。 */
	public static @Nullable MmtrTaskKind terminalTaskKind(MmtrLine line) {
		if (line.loop) {
			return null;
		}
		return switch (line.effectiveTerminalTreatment()) {
			case CHANGE_ENDS -> MmtrTaskKind.CHANGE_ENDS;
			case TURNBACK -> MmtrTaskKind.DRIVE_TURNBACK;
			case STABLE -> MmtrTaskKind.DRIVE_TO_SIDING;
		};
	}

	/**
	 * 把一辆车一天的交路展开成**有序任务序列**。
	 *
	 * <p>时间取交路里的计划时刻（{@code earliestMs = dueMs = 该步的计划时刻}）：派发器按它决定
	 * "到点没有"，而时刻本身来自趟次表 —— 所以晚点/重排（P5）只要重算交路，这一层不用改。</p>
	 */
	public static ObjectArrayList<MmtrTask> expand(MmtrLine line, MmtrDiagram.Working working) {
		final ObjectArrayList<MmtrTask> tasks = new ObjectArrayList<>();
		int sequence = 0;
		for (final MmtrDiagram.Entry entry : working.entries) {
			switch (entry.kind) {
				case DEPART_YARD -> {
					// 出库 = 开到首发站台；计划到点就是首发时刻（提前量已经在交路里体现成开始时刻）
					sequence++;
					tasks.add(stamp(new DriveToPlatformTask(taskId(line, working, sequence), entry.platformId, entry.endMillis), entry.startMillis));
				}
				case TRIP -> {
					if (entry.trip == null) {
						continue;
					}
					sequence = expandTrip(line, working, entry.trip, tasks, sequence);
				}
				case STABLE_YARD -> {
					sequence++;
					final MmtrTask task = new DriveToSidingTask(taskId(line, working, sequence), entry.sidingId, entry.startMillis);
					// 终点处理本身就是"回库"时不要连挂两条一样的（末端那一趟已经去过了）
					if (!isSameMoveAsLast(tasks, task)) {
						tasks.add(stamp(task, entry.startMillis));
					}
				}
			}
		}
		return tasks;
	}

	/** 一趟车：逐站到发 + 末端处理。 */
	private static int expandTrip(MmtrLine line, MmtrDiagram.Working working, MmtrServicePlan.Trip trip, ObjectArrayList<MmtrTask> tasks, int sequence) {
		int seq = sequence;
		final ObjectArrayList<MmtrServicePlan.StopTime> stopTimes = trip.stopTimes;
		for (int i = 0; i < stopTimes.size(); i++) {
			final MmtrServicePlan.StopTime stop = stopTimes.get(i);
			if (i > 0) {
				// 到站：开到站台（计划到点 = 到站时刻）
				seq++;
				tasks.add(stamp(new DriveToPlatformTask(taskId(line, working, seq), stop.platformId, stop.arrivalMillis), stop.arrivalMillis));
			}
			// 停站作业：开门—停站—关门（起点站不停：车本来就在那儿）
			if (i > 0 && stop.dwellMillis() > 0) {
				seq++;
				/*
				 * **计划时刻取"到站"，不是"发车"**（notes/155 §8 现场）。
				 *
				 * <p>取发车时刻的后果现场量得出来：到站那一刻这一趟还不许派（{@code earliestMs} = 发车时刻），
				 * 车先干等一个停留；等到点把站台作业挂上去，执行器再按计划停第二个停留 ——
				 * **每站多花整整一个停留**（30 秒），十站一趟就是 5 分钟，第二趟就冲破
				 * {@code LATE_GRACE_MILLIS}（10 分钟）而开始"跳过不停"（用户报的"只停前两站"正是这样来的）。</p>
				 *
				 * <p>与本类统一口径一致（"计划时刻 = 这一步该开始的时刻"）：站台作业**开始于到站**。</p>
				 */
				tasks.add(stamp(new StationServiceTask(taskId(line, working, seq), stop.platformId, stop.arrivalMillis, stop.dwellMillis()), stop.arrivalMillis));
			}
		}
		// 终点处理（§5.2 三态；环线继续跑，不加）
		final MmtrTaskKind treatment = terminalTaskKind(line);
		if (treatment != null) {
			seq++;
			final MmtrTask task = switch (treatment) {
				case CHANGE_ENDS -> new ChangeEndsTask(taskId(line, working, seq), trip.terminalDoneMillis);
				case DRIVE_TURNBACK -> new DriveTurnbackTask(taskId(line, working, seq), line.yardSidingId, trip.terminalDoneMillis);
				default -> new DriveToSidingTask(taskId(line, working, seq), line.yardSidingId, trip.terminalDoneMillis);
			};
			if (!isSameMoveAsLast(tasks, task)) {
				tasks.add(stamp(task, trip.terminalDoneMillis));
			} else {
				seq--;
			}
		}
		return seq;
	}

	/** 任务 id 带上车与序号，日志里能直接对上是哪辆车的第几步。 */
	private static String taskId(MmtrLine line, MmtrDiagram.Working working, int sequence) {
		return line.lineId + "/" + working.consistId + "/" + String.format("%03d", sequence);
	}

	/** 计划时刻就是这一步该开始的时刻（{@code earliestMs} 留 0：到点即派，不额外压时间窗）。 */
	private static MmtrTask stamp(MmtrTask task, long planMillis) {
		task.earliestMs = planMillis;
		task.dueMs = planMillis;
		task.note = "时刻表 " + MmtrPattern.hhmm(planMillis);
		return task;
	}

	/** 上一条任务是不是同一个动作、同一个目标（避免"回库"连着来两次）。 */
	private static boolean isSameMoveAsLast(ObjectArrayList<MmtrTask> tasks, MmtrTask candidate) {
		if (tasks.isEmpty()) {
			return false;
		}
		final MmtrTask last = tasks.get(tasks.size() - 1);
		return last.kind() == candidate.kind() && last.targetRef == candidate.targetRef;
	}
}
