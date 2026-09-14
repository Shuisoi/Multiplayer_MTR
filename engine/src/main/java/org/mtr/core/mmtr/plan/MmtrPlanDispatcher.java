package org.mtr.core.mmtr.plan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.mmtr.task.MmtrTask;

/**
 * P4：**派发器**（{@code 任务系统-线路派生与车底交路-设计.md} §2.1 ③ / §10 的 P4）。
 *
 * <p>它要替换掉 {@code MmtrPeriodicTaskSource} —— 那个"每 tick 被调用却从未被填充"的空壳：
 * 单一固定周期、挑任一空闲停放车、目标写 0、不派生多步服务、不管车底数与替补。</p>
 *
 * <h3>它做什么</h3>
 * <ol>
 *   <li><b>到点了把任务挂到具体车列</b>：交路（P3）→ 任务序列（{@link MmtrPlanTasks}）→ 按计划时刻派发；</li>
 *   <li><b>跨 tick 不重复派发</b>：每辆车记着"派到第几步"，派出去才前进（验收 ②）；</li>
 *   <li><b>车列被占用时重试而不丢趟</b>：拿不到车 / 挂不上任务 → 这一步原地等着，
 *       下一 tick 拿**同一步**再试，不跳步也不丢趟（验收 ④，继承旧源的语义）；</li>
 *   <li><b>与世界的接口只有三件事</b>（见 {@link World}）：哪些车空着、这辆车还空着吗、挂这一步任务。
 *       于是核心可以在没有世界的用例里跑完一整天（纯函数式的好处：可离线、可重放）。</li>
 * </ol>
 *
 * <p><b>不做的事</b>（保持边界）：不算时刻表（那是 P2）、不排班（P3）、不处理事件与重算（P5）、
 * 不做替补顶替（P6）。本类只回答"现在该不该把下一步挂出去"。</p>
 */
public final class MmtrPlanDispatcher {

	/** 派发器看到的世界（适配层实现它：Simulator 侧看股道上的车、挂任务）。 */
	public interface World {
		/** 该车场股道（或其所在车辆段）里**空闲可用**的车列 id，按 id 定序（稳定，便于复现）。 */
		long[] idleVehiclesForYard(long yardSidingId);

		/** 这辆车现在还能接活吗（没在途、没有未完成任务）。 */
		boolean isVehicleIdle(long vehicleId);

		/**
		 * 这辆车**正在跑我派的这一步吗**（任务 id 对得上）。
		 *
		 * <p>为什么需要它：一趟车要跑好几步（到站、停站、换端…），中间车是"忙"的 —— 但那是**我自己的活**。
		 * 少了这条判据，派发器会把"我派出去的那一步"误当成"车被别人占了"，于是解绑、下一 tick 重新找车，
		 * **一趟车的每一步都换一辆车**（装机实测：五条派车日志、五个不同的车辆 id）。</p>
		 */
		boolean isVehicleRunningTask(long vehicleId, String taskId);

		/**
		 * 真正把这一步挂到车上。
		 *
		 * @return false = 现在挂不上（车被占 / 目标不可达 / 任务不可行），派发器下一 tick 用**同一步**重试
		 */
		boolean dispatchTask(long vehicleId, MmtrTask task);
	}

	/** 一辆车当前的状态。 */
	public static final class WorkingState {
		public final String consistId;
		public final ObjectArrayList<MmtrTask> tasks;
		/** 已经派到第几步（0 = 一步都还没派）。 */
		public int dispatchedSteps;
		/** 绑定的车列（0 = 还没分到车）。 */
		public long vehicleId;
		/** 已经派出去、**还在等它跑完**的那一步的任务 id（空 = 手空着，可以派下一步）。 */
		public String awaitingTaskId = "";
		/** 上一次尝试派发的那一步的计划时刻（诊断用）。 */
		public long lastAttemptMillis;

		WorkingState(String consistId, ObjectArrayList<MmtrTask> tasks) {
			this.consistId = consistId;
			this.tasks = tasks;
		}

		public boolean isComplete() {
			return dispatchedSteps >= tasks.size();
		}

		public @Nullable MmtrTask nextTask() {
			return isComplete() ? null : tasks.get(dispatchedSteps);
		}

		@Override
		public String toString() {
			return consistId + (vehicleId == 0 ? "（未分车）" : "（车 " + vehicleId + "）")
				+ " 已派 " + dispatchedSteps + "/" + tasks.size() + " 步"
				+ (awaitingTaskId.isEmpty() ? "" : "，等 " + awaitingTaskId + " 跑完");
		}
	}

	public final String lineId;
	/** 这条线路的出库/回库股道（分车就从它上面找空闲车列）。 */
	public final long yardSidingId;
	public final MmtrDiagram diagram;
	public final ObjectArrayList<WorkingState> states = new ObjectArrayList<>();
	/** 已经派出去多少步（跨 tick 累计；诊断与"不重复派发"的证据）。 */
	public int dispatchedTotal;
	/** 因为拿不到车/挂不上而**原地等待**过多少次（验收 ④ 的可观察量）。 */
	public int retryCount;
	/**
	 * 因为**已经过时**而跳过的步数（迟到的步不补跑）。
	 *
	 * <p>服务器中午启动时，"早上 07:00 的出库"不该在 19:00 被执行 —— 设计 §7 的口径是
	 * **过去不可改**（已完成的趟次是事实）。所以超过宽限期（{@link #LATE_GRACE_MILLIS}）的步
	 * 直接跳过并计数；这也保证"半路开机"不会把上午的班次整套补跑一遍。</p>
	 */
	public int skippedSteps;

	/** 迟到多久算"过时"（不补跑）。10 分钟：够吸收一次卡顿/重连，又不至于补跑半天。 */
	public static final long LATE_GRACE_MILLIS = 10L * 60 * 1000;

	/**
	 * P6 ③ **接管**（设计 §8.2）：玩家正在开的那些车，派发器**一步都不派**。
	 *
	 * <p>原则是"**任务是作业，执行者可换**"：接管只换执行者 —— 交路与任务原样不动
	 * （{@link WorkingState#tasks} 一个字节都不改），改变的只是"谁在跑"。
	 * 玩家把车还回来（{@code setPlayerDriven(..., false)}）时，派发器**从那一步续行**：
	 * 手上下一步还是原来那一步（`dispatchedSteps` 没动过），所以不会跳步、也不会从头再来。</p>
	 */
	private final java.util.HashSet<String> playerDriven = new java.util.HashSet<>();

	/** 玩家接管 / 归还（返回是否真的变了）。 */
	public boolean setPlayerDriven(String consistId, boolean player) {
		return player ? playerDriven.add(consistId) : playerDriven.remove(consistId);
	}

	public boolean isPlayerDriven(String consistId) {
		return playerDriven.contains(consistId);
	}

	public MmtrPlanDispatcher(MmtrLine line, MmtrDiagram diagram) {
		this.lineId = line.lineId;
		this.yardSidingId = line.yardSidingId;
		this.diagram = diagram;
		for (final MmtrDiagram.Working working : diagram.scheduledWorkings()) {
			states.add(new WorkingState(working.consistId, MmtrPlanTasks.expand(line, working)));
		}
	}

	/**
	 * **走一个 tick**：能给谁派就把下一步派出去。
	 *
	 * <p>顺序固定（按交路里的车序）—— 让"哪辆车先拿到车"这件事可复现，而不是看哈希顺序。</p>
	 *
	 * @param dayTimeMillis 当前**当日毫秒**（与线路密度表同一口径：{@code 07:00 = 25_200_000}）。
	 *                      不是纪元毫秒 —— 计划里的一切时刻都是"当天几点"。
	 * @param world         世界适配层
	 * @return 这一次真正派出去的步数
	 */
	public int tick(long dayTimeMillis, World world) {
		int dispatched = 0;
		for (final WorkingState state : states) {
			if (state.isComplete()) {
				continue;
			}
			if (playerDriven.contains(state.consistId)) {
				continue;   // 玩家在开：派发器不插手（接管只换执行者，交路与任务不变）
			}
			if (state.vehicleId == 0) {
				state.vehicleId = acquire(world, state);
				if (state.vehicleId == 0) {
					// 还没车：这一步原地等（下一 tick 再看），**不跳步**
					continue;
				}
			}
			/*
			 * 手上有活（上一步派出去了还没跑完）→ 先把"这一步跑完了没有"这件事看完。
			 *
			 * 三种情形分得很清，也正是**一辆车跑一整趟**的关键：
			 *   ① 还在跑我那一步 → 等（不算重试）；
			 *   ② 车空了 → 我那一步跑完了，可以派下一步；
			 *   ③ 车忙着但不是我的活（玩家开走/别的编排抢走）→ 解绑，重新找车。
			 */
			if (!state.awaitingTaskId.isEmpty()) {
				if (world.isVehicleRunningTask(state.vehicleId, state.awaitingTaskId)) {
					continue;   // 还在跑：等它
				}
				if (!world.isVehicleIdle(state.vehicleId)) {
					// 车忙着，但忙的不是我的活（玩家开走 / 别的编排抢走）→ 解绑重新找车
					state.vehicleId = 0;
					state.awaitingTaskId = "";
					retryCount++;
					continue;
				}
				// 我那一步跑完了：同一次 tick 接着看下一步（不白等一个 tick）
				state.awaitingTaskId = "";
			}
			if (!world.isVehicleIdle(state.vehicleId)) {
				state.vehicleId = 0;   // 车被别的活占了：下一 tick 重新找车
				retryCount++;
				continue;
			}
			final MmtrTask task = state.nextTask();
			if (task == null) {
				continue;
			}
			if (dayTimeMillis < task.earliestMs) {
				continue;   // 还没到点
			}
			if (dayTimeMillis - task.earliestMs > LATE_GRACE_MILLIS) {
				// 过时了：不补跑（设计 §7「过去不可改」），跳过这一步继续看下一步
				state.dispatchedSteps++;
				skippedSteps++;
				continue;
			}
			state.lastAttemptMillis = dayTimeMillis;
			if (world.dispatchTask(state.vehicleId, task)) {
				state.dispatchedSteps++;
				state.awaitingTaskId = task.taskId;
				dispatchedTotal++;
				dispatched++;
			} else {
				retryCount++;
			}
		}
		return dispatched;
	}

	/** 分车：先看这条线路自己车场里空着的车，按 id 定序取第一辆**没被别的交路占用**的。 */
	private long acquire(World world, WorkingState state) {
		final long[] idle = world.idleVehiclesForYard(yardSidingId);
		for (final long vehicleId : idle) {
			if (isVehicleTakenByAnotherWorking(vehicleId, state)) {
				continue;
			}
			return vehicleId;
		}
		return 0;
	}

	private boolean isVehicleTakenByAnotherWorking(long vehicleId, WorkingState self) {
		for (final WorkingState other : states) {
			if (other != self && other.vehicleId == vehicleId) {
				return true;
			}
		}
		return false;
	}

	public boolean isComplete() {
		for (final WorkingState state : states) {
			if (!state.isComplete()) {
				return false;
			}
		}
		return true;
	}

	/** 全部交路的状态（运营台/日志用）。 */
	public ObjectArrayList<WorkingState> snapshot() {
		return new ObjectArrayList<>(states);
	}

	/**
	 * P5：**冻结边界**（设计 §7）—— 每辆车"不许动到什么时候"。
	 *
	 * <p>规则：正在跑某一步的车，冻结到**它当前所在的那条交路条目结束**为止。
	 * 条目就是"出库/一趟车/回库"，所以边界正好是"这趟车跑完"= 设计里
	 * "frozenUntil = 它当前任务的预计完成时刻"的可计算形式（不需要另建一套在途状态：
	 * 谁在跑、跑到哪一步，派发器自己就知道）。</p>
	 *
	 * @return 编组代码 → 冻结到的当日毫秒
	 */
	public java.util.Map<String, Long> frozenUntilByConsist() {
		final java.util.HashMap<String, Long> out = new java.util.HashMap<>();
		for (final WorkingState state : states) {
			if (state.awaitingTaskId.isEmpty() || state.dispatchedSteps == 0) {
				continue;
			}
			final MmtrTask running = state.tasks.get(Math.min(state.dispatchedSteps - 1, state.tasks.size() - 1));
			long boundary = running.dueMs;
			for (final MmtrDiagram.Working working : diagram.workings) {
				if (!working.consistId.equals(state.consistId)) {
					continue;
				}
				for (final MmtrDiagram.Entry entry : working.entries) {
					if (running.dueMs >= entry.startMillis && running.dueMs <= entry.endMillis) {
						boundary = entry.endMillis;
					}
				}
			}
			out.put(state.consistId, boundary);
		}
		return out;
	}

	@Override
	public String toString() {
		return "派发器 " + lineId + "：" + states.size() + " 辆车，已派 " + dispatchedTotal + " 步，跳过 " + skippedSteps + " 步，重试 " + retryCount
			+ " 次" + (isComplete() ? "（今天跑完）" : "");
	}
}
