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

		/**
		 * **把这一步收回来**（换代交接时，这一步在新计划里已经不存在了）。
		 *
		 * <p>不收回的后果是现场实测出来的：车会一直开着一个已经不存在的班，并且继续占着道岔 ——
		 * 计划的每一次重算都会多留一台这样的"幽灵车"，最后把车场咽喉彻底堵死。</p>
		 *
		 * @return 是否真的收回了（车不在 / 它跑的不是这一步 = false，什么都不做）
		 */
		boolean releaseTask(long vehicleId, String taskId);
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
	 * **两次出库之间至少隔多久**（notes/152，现场逼出来的）。
	 *
	 * <p>为什么必须有这条：车场咽喉只有一组道岔，而每台车的停车位都落在**别人要过的那处道岔的净空区**
	 * 里（引擎的净空闸"不许把道岔从车下抽走"是完全正确的）。于是两台车同时出库时互相把对方锁死 ——
	 * 现场读数：两台车各自排在对方的后面、`phys=-`（谁都没按位置）、净空闸报"10 m 内有车足迹"，谁也不动。</p>
	 *
	 * <p>计划层的口径：**发车间隔 3 分钟 ≠ 出库能 3 分钟一台**。出库那一步原来一律"发车前 5 分钟"
	 * 派出去，于是咽喉里必然同时有两台。这条按"前一台出清咽喉要多久"把它们隔开 ——
	 * 与联锁层的通行优先权（计划早的先走）互补：一条排次序，一条隔时间。</p>
	 */
	public static final long YARD_DEPARTURE_GAP_MILLIS = 4L * 60 * 1000;

	/** 上一次派出"某个编组的出库那一步"的当日时刻（0 = 还没派过）。 */
	private long lastYardDepartureMillis;

	/**
	 * 现在能不能放**下一个编组出库**：上一次出库之后至少隔 {@link #YARD_DEPARTURE_GAP_MILLIS}。
	 *
	 * <p>抽成纯函数是为了能被钉住（红证）：这条规矩的两个后果都很直白 —— 太短则两台车挤在咽喉里
	 * 互相锁在净空区（现场实测），太长则车底周转不过来（发车间隔已经定了，出库拖久了接不上下一趟）。</p>
	 *
	 * @param dayTimeMillis            现在（当日毫秒）
	 * @param lastYardDepartureMillis  上一次出库的当日时刻（0 = 还没派过 ⇒ 允许）
	 */
	public static boolean yardDepartureAllowed(long dayTimeMillis, long lastYardDepartureMillis) {
		if (lastYardDepartureMillis <= 0) {
			return true;
		}
		return dayTimeMillis - lastYardDepartureMillis >= YARD_DEPARTURE_GAP_MILLIS;
	}

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

	/**
	 * **这一步所属的那一趟车，窗口过没过去**（"迟到不补跑"只对窗口已过的步生效）。
	 *
	 * <p>为什么要有这条：P4 的"迟到不补跑"（§7 过去不可改）说的是**整趟都错过了**的班次不该补跑；
	 * 但一趟车中间那些步（到站、停站、换端）**不能跳** —— 跳了就等于这趟车不停那一站。
	 * 这条规则是被 P6 的接管用例逼出来的：玩家 07:00 接手、07:05 还回来时，
	 * 归还后那一步被判成"错过的班次"吃掉了（notes/145 §3），而它其实只是**同一趟的下一步**。</p>
	 *
	 * <p>判据：找到这一步所属的**交路条目**（按计划时刻落在哪个条目的时间窗里），
	 * 只看**那个条目本身过没过完**（{@code dayTimeMillis > entry.endMillis}）：
	 * 过完了 ⇒ 这一趟今天已经不成立，它的步逐步跳过（半路开机不会补跑上午的班）；
	 * 窗口还在（或还没到）⇒ 这一步是**活的**，晚几分钟也照派。</p>
	 *
	 * <p>换句话说：**错过的是"这一趟的窗口"，不是"某一步的时刻"** —— 这正是第一版
	 * （只跳"一趟的发车"、中趟的步永不跳）会把错过的整趟车照跑一遍的原因。</p>
	 */
	private boolean theTripWindowIsOver(WorkingState state, MmtrTask task, long dayTimeMillis) {
		final MmtrDiagram.Working working = workingOf(state.consistId);
		if (working == null) {
			return true;   // 查不到交路（理论上不该）→ 退回老语义：迟到就跳过
		}
		MmtrDiagram.Entry containing = null;
		for (final MmtrDiagram.Entry entry : working.entries) {
			if (task.dueMs >= entry.startMillis && task.dueMs <= entry.endMillis) {
				containing = entry;
			}
		}
		return containing == null || dayTimeMillis > containing.endMillis;
	}

	private MmtrDiagram.@Nullable Working workingOf(String consistId) {
		for (final MmtrDiagram.Working working : diagram.workings) {
			if (working.consistId.equals(consistId)) {
				return working;
			}
		}
		return null;
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
	 * 换代交接时向世界核对一句："这台车还在、还在跑这一步吗？"
	 *
	 * <p>为什么必须问世界而不是只看上一代的记忆：现场实测（notes/149）—— 车场按生成表重建之后，
	 * 上一代记的车列 id 已经不存在了；照着记忆交接的话，新派发器会一直"等一台不存在的车跑完"，
	 * 于是**一台车都不动**（比交接之前更糟）。</p>
	 */
	public interface InFlightCheck {
		/** 这台车还在世界上吗。 */
		boolean vehicleExists(long vehicleId);

		/** 这台车现在跑的是不是这一步。 */
		boolean isStillRunning(long vehicleId, String taskId);
	}

	/**
	 * **换代交接**：把上一代派发器的"谁在跑、跑到第几步、正在等哪一步"接到这一代上。
	 *
	 * <p>为什么必须有这一步（现场实测，notes/149）：计划一重算就重建派发器，而**世界里那台车还在跑
	 * 上一代派给它的那一步**。新派发器什么都不记得，于是它眼里的车是"没沾过这条交路"的，会去牵另一台；
	 * 被忘掉的那台就永远停在原地，还继续按 FIFO 占着道岔 —— 车场咽喉被两台"幽灵车"堵死，
	 * 后面六台车一步都出不去（现场：两台车分别卡在 (-153,-121) 与 (-211,-158)，路线 PENDING、
	 * 排队等道岔、谁都不动）。</p>
	 *
	 * <p>判据是**两个**：任务 id 在新计划里还在（{@code 线路/编组/序号}，跨次重算稳定）**并且**
	 * 世界说这台车还在跑它（{@link InFlightCheck}）。两个都对才接过来 —— "在途不打断"于是对重算成立，
	 * 而"接一台不存在的车"这种更坏的情形被挡在外面。</p>
	 *
	 * @return 需要收回任务的车列 id（这些车手里的那一步在新计划里已经不存在、或已经不是它在跑了）
	 */
	public ObjectArrayList<Long> adoptFrom(@Nullable MmtrPlanDispatcher previous, InFlightCheck check) {
		final ObjectArrayList<Long> orphaned = new ObjectArrayList<>();
		if (previous == null) {
			return orphaned;
		}
		for (final WorkingState old : previous.states) {
			final WorkingState next = stateOf(old.consistId);
			if (next == null) {
				// 这个编组在新计划里没有班了：它手里那一步必须收回，否则它会一直开下去
				if (old.vehicleId != 0) {
					orphaned.add(old.vehicleId);
				}
				continue;
			}
			if (old.vehicleId == 0) {
				continue;
			}
			final boolean inFlight = !old.awaitingTaskId.isEmpty();
			final boolean usable = check.vehicleExists(old.vehicleId)
				&& (!inFlight || check.isStillRunning(old.vehicleId, old.awaitingTaskId));
			if (!usable) {
				// 车没了、或者它跑的不是这一步了：不交接（按新计划重新派），并让调用方去收回
				orphaned.add(old.vehicleId);
				continue;
			}
			next.vehicleId = old.vehicleId;
			next.dispatchedSteps = Math.min(old.dispatchedSteps, next.tasks.size());
			next.lastAttemptMillis = old.lastAttemptMillis;
			if (!inFlight) {
				continue;
			}
			final int index = indexOfTask(next, old.awaitingTaskId);
			if (index < 0) {
				// 同一编组，但它正在等的那一步没了（趟次被取消/挪走）：车要收回
				orphaned.add(old.vehicleId);
				next.awaitingTaskId = "";
				next.dispatchedSteps = 0;
				next.vehicleId = 0;
			} else if (!sameTarget(old, next.tasks.get(index), old.awaitingTaskId)) {
				/*
				 * **同一个任务 id，去的地方变了**（notes/150）：交接只看 id 的话，车会继续开向
				 * 新计划已经不想要的目标 —— 这正是"没在跑当前版本的任务"。目标变了就收回重派，
				 * 时刻变了不算（车已经在路上，"过去不可改"）。
				 */
				orphaned.add(old.vehicleId);
				next.awaitingTaskId = "";
				next.dispatchedSteps = 0;
				next.vehicleId = 0;
			} else {
				// 接上：那一步已经派出去并且还没跑完 ⇒ 已派步数至少到它这里
				next.dispatchedSteps = Math.max(next.dispatchedSteps, index + 1);
				next.awaitingTaskId = old.awaitingTaskId;
			}
		}
		return orphaned;
	}

	/** 这个编组的状态（没有 = 新计划里它没班）。 */
	private @Nullable WorkingState stateOf(String consistId) {
		for (final WorkingState state : states) {
			if (state.consistId.equals(consistId)) {
				return state;
			}
		}
		return null;
	}

	private static int indexOfTask(WorkingState state, String taskId) {
		for (int i = 0; i < state.tasks.size(); i++) {
			if (state.tasks.get(i).taskId.equals(taskId)) {
				return i;
			}
		}
		return -1;
	}

	/** 上一代在等的那一步与新一代同名的那一步，**去的地方一样吗**（目标类型 + 目标 id）。 */
	private static boolean sameTarget(WorkingState old, MmtrTask nextTask, String taskId) {
		for (int i = 0; i < old.tasks.size(); i++) {
			final MmtrTask oldTask = old.tasks.get(i);
			if (oldTask.taskId.equals(taskId)) {
				return oldTask.targetRef == nextTask.targetRef && oldTask.targetKind.equals(nextTask.targetKind)
					&& oldTask.kind() == nextTask.kind();
			}
		}
		return false;
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
			/*
			 * **出库要排队**（notes/152）：车场咽喉只有一组道岔，两台车同时出库会互相锁在净空区里
			 * （各自的停车位落在对方要过的道岔的净空区内）。所以两次"出库那一步"之间隔开
			 * {@link #YARD_DEPARTURE_GAP_MILLIS}，让前一台先出清咽喉。
			 */
			if (state.dispatchedSteps == 0 && !yardDepartureAllowed(dayTimeMillis, lastYardDepartureMillis)) {
				continue;   // 上一台才刚出库：等咽喉清出来再放这一台
			}
			if (dayTimeMillis - task.earliestMs > LATE_GRACE_MILLIS && theTripWindowIsOver(state, task, dayTimeMillis)) {
				// 过时了，而且**这一趟的窗口已经过完**：不补跑（设计 §7「过去不可改」），跳过继续看下一步。
				// 窗口还在的步不走这条路 —— 那是"同一趟的下一步"，跳了就等于这趟车不停那一站
				// （见 theTripWindowIsOver；玩家接管后归还就是这种情形）。
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
				if (state.dispatchedSteps == 1) {
					lastYardDepartureMillis = dayTimeMillis;   // 记下"这一台刚出库"，下一台要隔开
				}
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
