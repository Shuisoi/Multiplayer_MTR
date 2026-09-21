package org.mtr.core.mmtr;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

/**
 * **主任务模板**（用户口径 2026-09-21）：
 *
 * <blockquote>「主任务应该是以下几个因素组装：主任务：停站乘降 —3站1台；子任务：1.停在3站1台 2.开门
 * 3.等待上下客 4.关门。其中 3站1台则为变量，任务为一对象。同理主任务 开往——车厂987654股道1；
 * 子任务：1.停在车厂987654股道1。这样拆一个任务到基础的操作以简化逻辑判定。」</blockquote>
 *
 * <p>于是引擎里"主任务"只剩两样东西：**模板**（要做哪几件基础操作）+ **目标**（{@link MmtrTaskTarget}）。
 * 具体的一次执行 = 用目标把模板展开成一张子任务清单（{@link #expand}），判定就逐条做基础操作 ——
 * 没有"每一种主任务各写一套判定"这回事。</p>
 *
 * <h3>为什么这件事必须做（不只是好看）</h3>
 *
 * <p>2026-09-21 的实机回归就是"模板没统一"的直接后果：{@code SERVE}（停站乘降）那一步有子任务链，
 * 于是它有宽松到站判据；而 {@code MOVE_TO}（开往）那一步**没有链**，掉回"精确停车点"判据 ——
 * 而玩家任务没有停车点，于是那一步永远到不了站、下一步的开门提示永远不来。
 * 统一成"每个主任务都展开成基础操作"之后，**到站判定属于 {@code STOP_AT_TARGET} 这一条子任务**，
 * 谁展开都走同一句代码，这一类"漏了一条模板就漏了一套判定"的 bug 在结构上不再成立。</p>
 */
public enum MmtrTaskTemplate {

	/** **开往**：只有一条基础操作 —— 停在目标（开着去、停住、到位）。 */
	DRIVE_TO,

	/** **停站乘降**：停在目标 → 开门 → 等待上下客 → 关门。 */
	STOP_AND_SERVE,

	/** 不是已知模板（引擎里没有对应动作的临时任务）：不展开链，走老口径。 */
	NONE;

	/**
	 * **把模板 + 目标展开成子任务清单**（含每条的人话）。
	 *
	 * @param target     目标（变量；{@link MmtrTaskTarget#none()} 表示原地动作）
	 * @param dwellMillis 等待上下客的时长（模板参数；{@code <= 0} = 由调用方决定）
	 */
	public ObjectArrayList<MmtrSubTask> expand(MmtrTaskTarget target, long dwellMillis) {
		final ObjectArrayList<MmtrSubTask> subTasks = new ObjectArrayList<>();
		switch (this) {
			case DRIVE_TO -> subTasks.add(MmtrSubTask.stopAtTarget(target));
			case STOP_AND_SERVE -> {
				subTasks.add(MmtrSubTask.stopAtTarget(target));
				subTasks.add(MmtrSubTask.openDoors());
				subTasks.add(MmtrSubTask.waitPassengers(dwellMillis));
				subTasks.add(MmtrSubTask.closeDoors());
			}
			case NONE -> {
			}
		}
		return subTasks;
	}

	/**
	 * **作业单步骤类型 → 模板**（作业单是"作者写的东西"，模板是"引擎要做的基础操作"，
	 * 这一句是两者之间唯一的翻译点）。
	 *
	 * <p>不在表里的步骤类型（换端、连挂、解挂…）暂时回 {@link #NONE}：它们的动作是原地/机械的，
	 * 还没有拆成基础操作；这一轮先把"开往"与"停站乘降"两条主干道统一，其余保持原样。</p>
	 */
	public static MmtrTaskTemplate ofStepType(String stepType) {
		if (stepType == null) {
			return NONE;
		}
		return switch (stepType.trim().toUpperCase(java.util.Locale.ROOT)) {
			case "MOVE_TO", "DRIVE_TO" -> DRIVE_TO;
			case "SERVE", "STATION_SERVICE", "STOP_AND_SERVE" -> STOP_AND_SERVE;
			default -> NONE;
		};
	}
}
