package org.mtr.core.mmtr.plan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.job.MmtrCarSpec;
import org.mtr.core.mmtr.task.MmtrTask;
import org.mtr.core.mmtr.task.MmtrTaskKind;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P4 验收（{@code 任务系统-线路派生与车底交路-设计.md} §10 的 P4 四条）：
 *
 * <ol>
 *   <li>一趟完整服务端到端：出库 → 各站（到站+停站）→ 终点处理 → 回程 → 回库；</li>
 *   <li>跨 tick **不重复派发**；</li>
 *   <li>任务类型与 {@link MmtrTaskKind} 对应正确（§5.2 的三态映射）；</li>
 *   <li>车列被占用时**重试而不丢趟**。</li>
 * </ol>
 *
 * <p>用一个**假的 World** 跑完一整天：派发器核心不认识世界，所以这些用例可以在毫秒内走完
 * 一整天的几百步 —— 这正是"纯函数核心 + 适配层"的好处。</p>
 */
public final class MmtrPlanDispatcherTests {

	private static final long MIN = 60_000L;
	private static final long H07 = 7 * 60 * MIN;
	private static final long LEG = 4 * MIN;
	private static final long TERMINAL = 3 * MIN;
	private static final MmtrTravelTimes TIMES = MmtrTravelTimes.uniform(LEG, TERMINAL);

	private static MmtrLine line(MmtrLine.TerminalTreatment treatment, boolean loop) {
		final MmtrLine line = new MmtrLine("L1", "1 号线");
		line.yardSidingId = 42;
		line.leadTimeMillis = 5 * MIN;
		line.loop = loop;
		line.terminalTreatment = treatment;
		line.addStop(1001, 2001, 30_000);
		line.addStop(1002, 2002, 45_000);
		line.addStop(1003, 2003, 30_000);
		return line;
	}

	/** 短运营时段（3 个槽位）—— 够走完一整趟往返，又不至于让用例跑几百步。 */
	private static MmtrPattern shortPattern() {
		return new MmtrPattern("L1").addSegment(H07, H07 + 30 * MIN, 10 * MIN);
	}

	/**
	 * **长途线路**（6 站、单程约半小时）：只有这种线路才存在"窗口还没过完、但某一步已经迟到很久"的
	 * 情形 —— 短线（{@link #line}）的一趟只有 11 分钟，任何超过宽限期的步都必然已经出了窗口，
	 * 于是"窗口"这条判据在短线上不可观察（见 {@link #aLateStepInsideAnOpenTripWindowIsStillDispatched}）。
	 */
	private static MmtrLine longLine() {
		final MmtrLine line = new MmtrLine("L2", "2 号线（长途）");
		line.yardSidingId = 43;
		line.leadTimeMillis = 5 * MIN;
		line.terminalTreatment = MmtrLine.TerminalTreatment.CHANGE_ENDS;
		for (int i = 1; i <= 6; i++) {
			line.addStop(2000 + i, 3000 + i, 60_000);
		}
		return line;
	}

	/** 发车间隔 60 分钟 ≥ 一个往返：一个编组就够（N=1）。 */
	private static MmtrPattern longPattern() {
		return new MmtrPattern("L2").addSegment(H07, H07 + 120 * MIN, 60 * MIN);
	}

	private static MmtrFleet fleet(int consists) {
		final MmtrFleet fleet = new MmtrFleet();
		for (int i = 0; i < consists; i++) {
			fleet.addConsist(new MmtrFleet.ConsistSpec("C" + (i + 1), 80).addCar(new MmtrCarSpec()));
		}
		return fleet;
	}

	/**
	 * 假世界：给定一批"可用车列"，记录每一次派发。
	 *
	 * <p>{@code autoComplete} 打开时，派出去的活立刻算跑完（用例关心的是"派发顺序"）；
	 * 关掉时车会一直忙，用来钉住"一辆车跑一整趟"和"不换车"这两条。</p>
	 */
	private static final class FakeWorld implements MmtrPlanDispatcher.World {
		final List<Long> idle = new ArrayList<>();
		final List<String> dispatched = new ArrayList<>();
		final List<Long> dispatchedTo = new ArrayList<>();
		final java.util.Map<Long, String> runningTasks = new java.util.HashMap<>();
		boolean acceptDispatch = true;
		boolean autoComplete = true;

		FakeWorld(Long... vehicles) {
			for (final Long vehicle : vehicles) {
				idle.add(vehicle);
			}
		}

		/** 手动收工（autoComplete=false 的用例用它推进）。 */
		void complete(long vehicleId) {
			runningTasks.remove(vehicleId);
			if (!idle.contains(vehicleId)) {
				idle.add(vehicleId);
			}
		}

		@Override
		public long[] idleVehiclesForYard(long yardSidingId) {
			final long[] out = new long[idle.size()];
			for (int i = 0; i < idle.size(); i++) {
				out[i] = idle.get(i);
			}
			return out;
		}

		@Override
		public boolean isVehicleIdle(long vehicleId) {
			return idle.contains(vehicleId) && !runningTasks.containsKey(vehicleId);
		}

		@Override
		public boolean isVehicleRunningTask(long vehicleId, String taskId) {
			return taskId != null && taskId.equals(runningTasks.get(vehicleId));
		}

		@Override
		public boolean dispatchTask(long vehicleId, MmtrTask task) {
			if (!acceptDispatch) {
				return false;
			}
			dispatched.add(task.kind().name() + "→" + task.targetRef + "@" + MmtrPattern.hhmm(task.dueMs));
			dispatchedTo.add(vehicleId);
			if (autoComplete) {
				complete(vehicleId);
			} else {
				idle.remove(vehicleId);
				runningTasks.put(vehicleId, task.taskId);
			}
			return true;
		}

		/** 把任务收回来（只有"跑的正是在这一步"才收，与真的适配层同一条判据）。 */
		@Override
		public boolean releaseTask(long vehicleId, String taskId) {
			if (taskId != null && taskId.equals(runningTasks.get(vehicleId))) {
				runningTasks.remove(vehicleId);
				if (!idle.contains(vehicleId)) {
					idle.add(vehicleId);
				}
				released.add(taskId);
				return true;
			}
			return false;
		}

		/** 被收回过的任务 id（换代交接的对账用）。 */
		final List<String> released = new ArrayList<>();

		/** 这台车世界上还有没有（换代交接要问世界的第二句）。 */
		boolean knows(long vehicleId) {
			return idle.contains(vehicleId) || runningTasks.containsKey(vehicleId);
		}

		/** 换代交接时"问世界"的那两问，直接用这个假世界回答。 */
		MmtrPlanDispatcher.InFlightCheck check() {
			return new MmtrPlanDispatcher.InFlightCheck() {
				@Override
				public boolean vehicleExists(long vehicleId) {
					return knows(vehicleId);
				}

				@Override
				public boolean isStillRunning(long vehicleId, String taskId) {
					return isVehicleRunningTask(vehicleId, taskId);
				}
			};
		}
	}

	private static MmtrPlanDispatcher dispatcher(MmtrLine.TerminalTreatment treatment, boolean loop) {
		final MmtrLine line = line(treatment, loop);
		final MmtrDiagram diagram = MmtrDiagram.generate(line, shortPattern(), fleet(1), TIMES);
		assertEquals(1, diagram.scheduledWorkings().size());
		return new MmtrPlanDispatcher(line, diagram);
	}

	// ---------------------------------------------------------------- ③ 任务类型映射

	/** ③ 展开出来的任务类型与 §5.2 的映射表逐条对得上（三种终点处理 + 环线）。 */
	@Test
	public void theExpansionUsesTheDesignTaskKinds() {
		assertEquals(MmtrTaskKind.CHANGE_ENDS, MmtrPlanTasks.terminalTaskKind(line(MmtrLine.TerminalTreatment.CHANGE_ENDS, false)));
		assertEquals(MmtrTaskKind.DRIVE_TURNBACK, MmtrPlanTasks.terminalTaskKind(line(MmtrLine.TerminalTreatment.TURNBACK, false)));
		assertEquals(MmtrTaskKind.DRIVE_TO_SIDING, MmtrPlanTasks.terminalTaskKind(line(MmtrLine.TerminalTreatment.STABLE, false)));
		assertEquals(null, MmtrPlanTasks.terminalTaskKind(line(MmtrLine.TerminalTreatment.CHANGE_ENDS, true)), "环线不处理终点");

		final MmtrPlanDispatcher d = dispatcher(MmtrLine.TerminalTreatment.CHANGE_ENDS, false);
		final ObjectArrayList<MmtrTask> tasks = d.states.get(0).tasks;
		assertFalse(tasks.isEmpty());
		// 全序列只应出现这几种（没有新词汇）
		for (final MmtrTask task : tasks) {
			assertTrue(task.kind() == MmtrTaskKind.DRIVE_TO_PLATFORM || task.kind() == MmtrTaskKind.STATION_SERVICE
					|| task.kind() == MmtrTaskKind.CHANGE_ENDS || task.kind() == MmtrTaskKind.DRIVE_TO_SIDING,
				"不该出现 " + task.kind());
		}
		// 第一条是出库（开到首发站台），最后一条是回库
		assertEquals(MmtrTaskKind.DRIVE_TO_PLATFORM, tasks.get(0).kind(), "出库 = DRIVE_TO_PLATFORM 到首发站台");
		assertEquals(2001, tasks.get(0).targetRef);
		assertEquals(MmtrTaskKind.DRIVE_TO_SIDING, tasks.get(tasks.size() - 1).kind(), "最后回库");
		assertEquals(42, tasks.get(tasks.size() - 1).targetRef);
	}

	/** ③ 续：灯泡线掉头线路上，末端处理是 DRIVE_TURNBACK；回库那条**不会连着来两次**。 */
	@Test
	public void turnbackAndStableLinesExpandDifferently() {
		final MmtrPlanDispatcher turnback = dispatcher(MmtrLine.TerminalTreatment.TURNBACK, false);
		assertTrue(turnback.states.get(0).tasks.stream().anyMatch(task -> task.kind() == MmtrTaskKind.DRIVE_TURNBACK),
			"灯泡线要有掉头任务");

		final MmtrPlanDispatcher stable = dispatcher(MmtrLine.TerminalTreatment.STABLE, false);
		final ObjectArrayList<MmtrTask> tasks = stable.states.get(0).tasks;
		assertTrue(tasks.stream().anyMatch(task -> task.kind() == MmtrTaskKind.DRIVE_TO_SIDING));
		int consecutive = 0;
		for (int i = 1; i < tasks.size(); i++) {
			if (tasks.get(i).kind() == MmtrTaskKind.DRIVE_TO_SIDING && tasks.get(i - 1).kind() == MmtrTaskKind.DRIVE_TO_SIDING) {
				consecutive++;
			}
		}
		assertEquals(0, consecutive, "回库连着来两次 = 让车白跑一趟");
	}

	// ---------------------------------------------------------------- ① 端到端

	/**
	 * ① 一趟完整的服务：出库 → 各站到发 → 终点处理 → 回程 → 回库。
	 *
	 * <p>断言的是**派发出去的顺序**（这才是派发层的产物），并逐条对照计划时刻。</p>
	 */
	@Test
	public void aFullServiceRunDispatchesInOrder() {
		final MmtrPlanDispatcher d = dispatcher(MmtrLine.TerminalTreatment.CHANGE_ENDS, false);
		final FakeWorld world = new FakeWorld(9001L);
		// 一直推进到这一天跑完（时钟按分钟走）
		for (long clock = H07 - 10 * MIN; clock <= H07 + 120 * MIN; clock += MIN) {
			d.tick(clock, world);
		}
		assertTrue(d.isComplete(), "整天的交路都派完了：" + d);

		final List<String> seats = world.dispatched;
		assertTrue(seats.size() >= 12, "至少十几步：" + seats);
		// 第一段：出库 → 首发站 → 中途停站 → 终点处理
		assertTrue(seats.get(0).startsWith("DRIVE_TO_PLATFORM→2001"), "出库到首发站台：" + seats.get(0));
		assertTrue(seats.get(1).startsWith("DRIVE_TO_PLATFORM→2002"), "第一站开车后到第二站：" + seats.get(1));
		assertTrue(seats.get(2).startsWith("STATION_SERVICE→2002"), "第二站停站作业：" + seats.get(2));
		assertTrue(seats.get(3).startsWith("DRIVE_TO_PLATFORM→2003"), "到终点站：" + seats.get(3));
		assertTrue(seats.get(4).startsWith("STATION_SERVICE→2003"), "终点停站：" + seats.get(4));
		assertTrue(seats.get(5).startsWith("CHANGE_ENDS"), "终点换端：" + seats.get(5));
		// 回程：从终点往回开
		assertTrue(seats.get(6).startsWith("DRIVE_TO_PLATFORM→2002"), "回程第一段：" + seats.get(6));
		// 最后一步回库
		assertTrue(seats.get(seats.size() - 1).startsWith("DRIVE_TO_SIDING→42"), "收车回库：" + seats.get(seats.size() - 1));
		// 全部落在同一辆车上（只有一辆可用）
		assertTrue(world.dispatchedTo.stream().allMatch(id -> id == 9001L));
	}

	// ---------------------------------------------------------------- ② 不重复派发

	/** ② 同一个时钟连问两次只派一次；同一 tick 反复调用也不会重派。 */
	@Test
	public void theSameStepIsNeverDispatchedTwice() {
		final MmtrPlanDispatcher d = dispatcher(MmtrLine.TerminalTreatment.CHANGE_ENDS, false);
		final FakeWorld world = new FakeWorld(9001L);
		final long at = H07;   // 出库那条的计划时刻 = 首发 − leadTime = 06:55，先走到 07:00 保证到点
		d.tick(at, world);
		final int afterFirst = d.dispatchedTotal;
		assertTrue(afterFirst >= 1, "到点就该派出去");
		for (int i = 0; i < 5; i++) {
			d.tick(at, world);
		}
		assertEquals(afterFirst, d.dispatchedTotal, "同一时刻重复 tick 不能再派（不重复派发）");
		assertEquals(afterFirst, world.dispatched.size(), "世界那边也只收到这么多次");

		// 时钟往前走一点，下一步该派了：步数**只加一**
		d.tick(at + 5 * MIN, world);
		assertEquals(afterFirst + 1, d.dispatchedTotal, "到点才派下一步，且一次只派一步");
	}

	/** ② 续：计划还没到点的步骤不许提前派。 */
	@Test
	public void nothingIsDispatchedBeforeItsTime() {
		final MmtrPlanDispatcher d = dispatcher(MmtrLine.TerminalTreatment.CHANGE_ENDS, false);
		final FakeWorld world = new FakeWorld(9001L);
		d.tick(H07 - 60 * MIN, world);   // 出库那条在 06:55，还早
		assertEquals(0, d.dispatchedTotal, "没到点不派");
		d.tick(H07 - 6 * MIN, world);
		assertEquals(0, d.dispatchedTotal);
		d.tick(H07 - 5 * MIN, world);    // 06:55 = 出库时刻
		assertEquals(1, d.dispatchedTotal, "整点即派");
	}

	// ---------------------------------------------------------------- ④ 重试不丢趟

	/** ④ 没有可用车列时**原地等**：宽限期内一步不丢，车来了就从同一步继续。 */
	@Test
	public void noVehicleMeansWaitNotSkip() {
		final MmtrPlanDispatcher d = dispatcher(MmtrLine.TerminalTreatment.CHANGE_ENDS, false);
		final FakeWorld empty = new FakeWorld();
		for (long clock = H07 - 3 * MIN; clock <= H07 - MIN; clock += MIN) {
			assertEquals(0, d.tick(clock, empty), "没车就一步都派不出去");
		}
		assertEquals(0, d.dispatchedTotal);
		assertEquals(0, d.skippedSteps, "没车不是迟到（车来了还得跑这一趟）");
		assertEquals(0, d.states.get(0).dispatchedSteps, "一步都没跳过");

		// 车来了（还在宽限期内）：从**第一步**继续
		final FakeWorld world = new FakeWorld(9001L);
		d.tick(H07, world);
		assertEquals(1, d.dispatchedTotal);
		assertTrue(world.dispatched.get(0).startsWith("DRIVE_TO_PLATFORM→2001"), "补派的是第一步（出库）：" + world.dispatched.get(0));
	}

	/** ④ 续：车被占用（或挂任务失败）→ 计数不动，下一 tick 用同一步重试。 */
	@Test
	public void anOccupiedVehicleIsRetriedWithTheSameStep() {
		final MmtrPlanDispatcher d = dispatcher(MmtrLine.TerminalTreatment.CHANGE_ENDS, false);
		final FakeWorld world = new FakeWorld(9001L);
		world.acceptDispatch = false;   // 挂不上（车被占/目标不可达）
		d.tick(H07, world);
		assertEquals(0, d.dispatchedTotal, "挂不上就不算派出去");
		assertEquals(1, d.retryCount, "记一次重试");

		world.acceptDispatch = true;
		d.tick(H07 + MIN, world);
		assertEquals(1, d.dispatchedTotal, "同一步重试成功");
		assertTrue(world.dispatched.get(0).startsWith("DRIVE_TO_PLATFORM→2001"), "重试的仍是第一步");

		// 车中途被开走（不再空闲）：交路解绑，等有车了重新分
		world.idle.clear();
		d.tick(H07 + 20 * MIN, world);
		assertEquals(0, d.states.get(0).vehicleId, "车没了就解绑，等下一辆");
		assertEquals(1, d.dispatchedTotal, "已派的步数不变（不丢趟）");
	}

	// ---------------------------------------------------------------- 迟到不补跑

	/**
	 * 服务器**半路开机**：早上的步已经过时了，不补跑（设计 §7「过去不可改」），只记跳过。
	 *
	 * <p>这条是装机前推演不出来的：派发器的 {@code tick} 收的是**当日毫秒**（07:00 = 25_200_000），
	 * 拿纪元毫秒去比会让"到点没有"永远成立 —— 于是所有步一起涌出去。</p>
	 */
	@Test
	public void staleStepsAreSkippedInsteadOfReplayed() {
		final MmtrPlanDispatcher d = dispatcher(MmtrLine.TerminalTreatment.CHANGE_ENDS, false);
		final FakeWorld world = new FakeWorld(9001L);
		final int total = d.states.get(0).tasks.size();
		// 09:00 才开机：这一整天的短交路（07:00–07:30）全都过时了
		for (int i = 0; i < total + 2; i++) {
			d.tick(H07 + 2 * 60 * MIN, world);
		}
		assertTrue(d.isComplete(), "过时的步会被逐步跳过，不会卡住：" + d);
		assertEquals(0, d.dispatchedTotal, "一步都不补跑（设计 §7「过去不可改」）");
		assertEquals(total, d.skippedSteps, "全部计成跳过：" + d.skippedSteps + "/" + total);
		assertTrue(world.dispatched.isEmpty(), "世界那边一次都没收到");
	}

	/**
	 * **窗口还没过完的步，哪怕迟到超过宽限，也照派**（"迟到不补跑"的边界）。
	 *
	 * <p>这条是 P6 接管实测抓出来的（notes/145 §3）：第一版按"这一步的时刻晚了多久"一刀切，
	 * 玩家把车还回来时，**同一趟的下一步**被判成"错过的班次"吃掉了 —— 车就停在那儿不动。
	 * 判据改成"**这一趟的窗口过没过去**"：过完了才跳（上面那条），窗口还在就是活的。</p>
	 *
	 * <p>反证：把判据换回老语义（迟到就跳），本用例会红 —— 它跳的是 {@code skippedSteps} 不变。</p>
	 */
	@Test
	public void aLateStepInsideAnOpenTripWindowIsStillDispatched() {
		final MmtrLine line = longLine();
		final MmtrDiagram diagram = MmtrDiagram.generate(line, longPattern(), fleet(1), TIMES);
		assertEquals(1, diagram.scheduledWorkings().size(), "一个编组就够（N=1）");
		final MmtrPlanDispatcher d = new MmtrPlanDispatcher(line, diagram);
		final FakeWorld world = new FakeWorld(9101L);
		world.autoComplete = false;   // 派出去的活要手动收工：这样"下一步"总是可以派

		// 06:55 出库、07:00 上客，各收一次工；接下来那一步在 07:04（出站后第一段 4 分钟）
		d.tick(H07 - 5 * MIN, world);
		world.complete(9101L);
		d.tick(H07, world);
		world.complete(9101L);
		final MmtrTask next = d.states.get(0).nextTask();
		assertNotNull(next);
		final int dispatchedBefore = d.dispatchedTotal;
		final int skippedBefore = d.skippedSteps;

		// 一直卡到"计划时刻 + 12 分钟"（超过 10 分钟宽限），但那一趟的窗口远没结束
		d.tick(next.dueMs + 12 * MIN, world);
		assertFalse(d.isComplete(), "这一天还长着（窗口没结束）");
		assertEquals(skippedBefore, d.skippedSteps, "窗口还在的步不许当成'错过的班次'吃掉：" + d);
		assertEquals(dispatchedBefore + 1, d.dispatchedTotal, "照派（只是晚了）：" + d);
		assertEquals(9101L, world.dispatchedTo.get(world.dispatchedTo.size() - 1), "还是这辆车");
	}

	// ---------------------------------------------------------------- 一辆车跑一整趟

	/**
	 * **一趟车由同一辆车跑完**（装机实测抓出来的缺陷）。
	 *
	 * <p>第一版把"车正忙着我派的那一步"当成"车被别人占了"，于是解绑、重新找车 ——
	 * 五个派车日志、五个不同的车辆 id。修法是 World 多一条
	 * {@link MmtrPlanDispatcher.World#isVehicleRunningTask}，把"忙"与"忙的是我的活"分开。</p>
	 */
	@Test
	public void oneWorkingKeepsTheSameVehicleForTheWholeTrip() {
		final MmtrPlanDispatcher d = dispatcher(MmtrLine.TerminalTreatment.CHANGE_ENDS, false);
		// 两个编组、两辆车：如果会换车，那么第二辆车也会被用上
		final FakeWorld world = new FakeWorld(9001L, 9002L);
		world.autoComplete = false;   // 派出去的活一直"在跑"，直到用例手动收工

		long clock = H07 - 5 * MIN;
		for (int step = 0; step < 6; step++) {
			d.tick(clock, world);
			clock += MIN;
		}
		// 每一步都是同一辆车，而且它一直"在跑"（没有被解绑去换车）
		assertEquals(1, world.dispatched.size(), "车忙着我派的活时不会再派下一步");
		assertEquals(1, d.dispatchedTotal);
		assertEquals(0, d.retryCount, "这不该记成重试：忙的是我自己的活");
		assertEquals(9001L, world.dispatchedTo.get(0));

		// 收工 → 下一步（到第二站，07:04 的计划时刻）立刻接着派给同一辆车
		world.complete(9001L);
		d.tick(clock + 10 * MIN, world);
		assertEquals(2, world.dispatched.size());
		assertEquals(9001L, world.dispatchedTo.get(1), "下一趟还是这辆车（不换车）");
		assertFalse(world.dispatchedTo.contains(9002L), "第二辆车不该被牵进来（它还没沾过这条交路）");
	}

	// ---------------------------------------------------------------- 多条交路

	/** 多辆车时各占各的：同一辆车不会被两条交路同时分到。 */
	@Test
	public void twoWorkingsNeverShareOneVehicle() {
		final MmtrLine line = line(MmtrLine.TerminalTreatment.CHANGE_ENDS, false);
		final MmtrDiagram diagram = MmtrDiagram.generate(line, shortPattern(), fleet(2), TIMES);
		assertEquals(2, diagram.scheduledWorkings().size(), "两个编组都上场");
		final MmtrPlanDispatcher d = new MmtrPlanDispatcher(line, diagram);
		final FakeWorld world = new FakeWorld(9001L, 9002L);
		d.tick(H07, world);
		assertEquals(9001L, d.states.get(0).vehicleId);
		assertEquals(9002L, d.states.get(1).vehicleId);
		assertNotNull(d.states.get(0).nextTask());
		for (long clock = H07; clock <= H07 + 120 * MIN; clock += MIN) {
			d.tick(clock, world);
		}
		assertTrue(world.dispatchedTo.contains(9001L) && world.dispatchedTo.contains(9002L), "两辆车都在跑");
	}

	// ---------------------------------------------------------------- P6 ③ 接管

	// ---------------------------------------------------------------- 重建时的冻结快照

	/**
	 * **重建交路之前**把"每辆车不许动到什么时候"取成快照（notes/147）。
	 *
	 * <p>第一版在重建里先 {@code mmtrPlanDispatchers.clear()} 再遍历取，于是那张表**永远是空的**：
	 * "在途车的当前任务不被重算改动"（P5 验收 ⑤）看起来接好了、实际一次都没生效。
	 * 现在取快照是独立一步（{@link MmtrPlanAdjustments#frozenSnapshot}），重建改成"建到局部表最后整体换掉"，
	 * 顺序在结构上就不可能再写反。</p>
	 */
	@Test
	public void theFreezeSnapshotCarriesTheInFlightBoundary() {
		final MmtrPlanDispatcher d = dispatcher(MmtrLine.TerminalTreatment.CHANGE_ENDS, false);
		final FakeWorld world = new FakeWorld(9001L);
		world.autoComplete = false;

		assertTrue(MmtrPlanAdjustments.frozenSnapshot(java.util.List.of(d)).isEmpty(), "一步都没派时没有边界（要重排就重排）");

		d.tick(H07, world);
		final java.util.Map<String, Long> boundaries = d.frozenUntilByConsist();
		assertFalse(boundaries.isEmpty(), "派出去一步之后，这辆车就有冻结边界了");
		assertEquals(boundaries, MmtrPlanAdjustments.frozenSnapshot(java.util.List.of(d)), "快照必须原样带出边界（先取后用）");
		assertTrue(boundaries.get("C1") >= H07, "边界是这辆车正在跑的那一段的结束时刻，不是 0");
	}

	// ---------------------------------------------------------------- 换代交接（重算不丢在途车）

	/** 同一份定义、只换线路代码的派发器（用来演"新计划里没有这个编组/这一步了"）。 */
	private static MmtrPlanDispatcher dispatcherWithLineId(String lineId) {
		final MmtrLine line = new MmtrLine(lineId, "验证线 " + lineId);
		line.yardSidingId = 42;
		line.leadTimeMillis = 5 * MIN;
		line.terminalTreatment = MmtrLine.TerminalTreatment.CHANGE_ENDS;
		line.addStop(1001, 2001, 30_000);
		line.addStop(1002, 2002, 45_000);
		line.addStop(1003, 2003, 30_000);
		final MmtrDiagram diagram = MmtrDiagram.generate(line, shortPattern(), fleet(1), TIMES);
		return new MmtrPlanDispatcher(line, diagram);
	}

	/**
	 * **重算之后新一代要接住上一代的在途车**（notes/149 的现场缺陷）。
	 *
	 * <p>现场：改一次密度 ⇒ 重建派发器 ⇒ 新派发器不认识世界里那台"上一代派出去的车"，
	 * 被忘掉的车永远停在原地并按 FIFO 占着道岔，两台就把车场咽喉堵死。判据只看任务 id
	 * （{@code 线路/编组/序号}，跨次重算稳定），接上以后"在途不打断"对重算也成立。</p>
	 *
	 * <p>红证：去掉 {@code adoptFrom} 那一步，本用例的第二段会看到同一步被派第二遍。</p>
	 */
	@Test
	public void aRebuildHandsTheInFlightStepToTheNextGeneration() {
		final MmtrPlanDispatcher first = dispatcher(MmtrLine.TerminalTreatment.CHANGE_ENDS, false);
		final FakeWorld world = new FakeWorld(9001L, 9002L);
		world.autoComplete = false;   // 车真的在跑那一步（交接的前提）
		first.tick(H07 - 5 * MIN, world);
		assertEquals(1, world.dispatched.size(), "第一代先把出库那一步派出去了");
		final String inFlight = first.states.get(0).awaitingTaskId;
		assertFalse(inFlight.isEmpty(), "派出去了就应该在等它跑完");
		assertEquals(9001L, first.states.get(0).vehicleId);

		final MmtrPlanDispatcher second = dispatcher(MmtrLine.TerminalTreatment.CHANGE_ENDS, false);
		final var orphaned = second.adoptFrom(first, world.check());
		assertTrue(orphaned.isEmpty(), "这一步在新计划里还在、车也还在跑，不该被收回：" + orphaned);
		assertEquals(9001L, second.states.get(0).vehicleId, "车还是那台（不许换车）");
		assertEquals(inFlight, second.states.get(0).awaitingTaskId, "正在等的那一步照样是它");
		assertEquals(first.states.get(0).dispatchedSteps, second.states.get(0).dispatchedSteps);

		second.tick(H07 - 5 * MIN, world);
		assertEquals(1, world.dispatched.size(), "同一步不许派第二遍：" + world.dispatched);
		assertEquals(0, second.retryCount, "也不该记成重试（车跑的是我自己的活）");
	}

	/** 同一份定义、但**第一站的站台不同**（任务 id 一样 `L1/C1/001`，去的地方不同）。 */
	private static MmtrPlanDispatcher dispatcherWithOtherFirstPlatform(long platformId) {
		final MmtrLine line = new MmtrLine("L1", "1 号线（改了站台）");
		line.yardSidingId = 42;
		line.leadTimeMillis = 5 * MIN;
		line.terminalTreatment = MmtrLine.TerminalTreatment.CHANGE_ENDS;
		line.addStop(1001, platformId, 30_000);
		line.addStop(1002, 2002, 45_000);
		line.addStop(1003, 2003, 30_000);
		final MmtrDiagram diagram = MmtrDiagram.generate(line, shortPattern(), fleet(1), TIMES);
		return new MmtrPlanDispatcher(line, diagram);
	}

	/**
	 * **同一个任务 id，去的地方变了 ⇒ 不许交接**（notes/150："没在跑当前版本的任务"）。
	 *
	 * <p>交接只对任务 id 的话，车会继续开向新计划已经不想要的目标 —— 改站台、改折返点、
	 * 事件把某一段挪到别的股道，都会踩这个坑。目标（类型 + id + 任务种类）变了就收回重派；
	 * **时刻变了不算**（车已经在路上，"过去不可改"）。</p>
	 *
	 * <p>红证：去掉目标比对（只按 id 交接），本用例会看到"接上了、车没交回"。</p>
	 */
	@Test
	public void aStepWhoseDestinationChangedIsNotAdopted() {
		final MmtrPlanDispatcher first = dispatcher(MmtrLine.TerminalTreatment.CHANGE_ENDS, false);
		final FakeWorld world = new FakeWorld(9001L);
		world.autoComplete = false;
		first.tick(H07 - 5 * MIN, world);
		assertEquals(9001L, first.states.get(0).vehicleId);
		final String inFlight = first.states.get(0).awaitingTaskId;
		assertFalse(inFlight.isEmpty());

		// 新一代：同样的线路与编组（任务 id 相同），但第一站的**站台换了** ⇒ 目标不同
		final MmtrPlanDispatcher second = dispatcherWithOtherFirstPlatform(9999L);
		assertTrue(second.states.get(0).tasks.get(0).taskId.equals(inFlight), "任务 id 必须是一样的（这正是危险之处）");
		final var orphaned = second.adoptFrom(first, world.check());
		assertEquals(1, orphaned.size(), "目标变了 ⇒ 不能交接，要把车交回来：" + orphaned);
		assertEquals(0, second.states.get(0).vehicleId, "不许接着开旧目标");
		assertEquals("", second.states.get(0).awaitingTaskId);
	}

	/**
	 * **接一台已经不存在的车 = 一台车都不动**（notes/149 第二轮现场）。
	 *
	 * <p>车场按生成表重建之后，上一代记的车列 id 全没了。照着记忆交接的话，新派发器会一直
	 * "等一台不存在的车跑完" —— 比不交接更糟（不交接至少会重新找车）。所以交接必须**问世界**：
	 * 车还在吗？还在跑这一步吗？</p>
	 */
	@Test
	public void aBindingToAVehicleThatVanishedIsNotAdopted() {
		final MmtrPlanDispatcher first = dispatcher(MmtrLine.TerminalTreatment.CHANGE_ENDS, false);
		final FakeWorld before = new FakeWorld(9001L);
		before.autoComplete = false;
		first.tick(H07 - 5 * MIN, before);
		assertEquals(9001L, first.states.get(0).vehicleId);

		// 车场重建：那台车没了（新的世界里只有 9002）
		final FakeWorld after = new FakeWorld(9002L);
		final MmtrPlanDispatcher second = dispatcher(MmtrLine.TerminalTreatment.CHANGE_ENDS, false);
		final var orphaned = second.adoptFrom(first, after.check());
		assertEquals(1, orphaned.size(), "接不上的车要交回给调用方：" + orphaned);
		assertEquals(0, second.states.get(0).vehicleId, "不许把不存在的车接过来");
		assertEquals(0, second.states.get(0).dispatchedSteps);

		// 新一代 tick：自己去找一台真车，把同一步重新派出去
		second.tick(H07 - 5 * MIN, after);
		assertEquals(1, after.dispatched.size(), "重新派给活着的车");
		assertEquals(9002L, after.dispatchedTo.get(0));
	}

	/** 新计划里**没有这个编组**了 ⇒ 把那台车交回给调用方去收回任务（不能让它开着一个不存在的班）。 */
	@Test
	public void aWorkingThatVanishesFromTheNewPlanIsHandedBackForRelease() {
		final MmtrPlanDispatcher first = dispatcherWithLineId("L1");
		final FakeWorld world = new FakeWorld(9001L);
		world.autoComplete = false;
		first.tick(H07 - 5 * MIN, world);
		final long vehicle = first.states.get(0).vehicleId;
		assertEquals(9001L, vehicle);
		final String taskId = first.states.get(0).awaitingTaskId;
		assertFalse(taskId.isEmpty());

		// 新一代是另一条线路（编组代码一样，但计划里已经没有 L1/C1 那一步）
		final MmtrPlanDispatcher second = dispatcherWithLineId("L9");
		final var orphaned = second.adoptFrom(first, world.check());
		assertEquals(1, orphaned.size(), "应该交回一台车：" + orphaned);
		assertEquals(vehicle, orphaned.get(0));
		assertEquals(0, second.states.get(0).vehicleId, "新一代不许把别的线路的车拿来用");

		// 收回：只有"跑的正是在这一步"才收得动
		assertFalse(world.releaseTask(vehicle, "L1/C1/999"), "不是这一步 ⇒ 不动");
		assertTrue(world.releaseTask(vehicle, taskId), "就是这一步 ⇒ 收回来");
		assertTrue(world.released.contains(taskId));
	}

	// ---------------------------------------------------------------- 任务能不能被派出去

	/**
	 * **计划里的终点步骤必须真的派得出去**（notes/150）。
	 *
	 * <p>现场：交路的最后一步是换端，而它按设计没有目标 —— 派发路径却要求"有目标才派"，
	 * 于是这一步永远派不出去、派发器在终点一直重试（重试数在涨），整条交路停住。
	 * 这个用例把"计划展开出来的终点任务是可派的"钉在计划层：换端是**原地动作**，允许没有目标。</p>
	 */
	@Test
	public void theTerminalChangeEndsStepCanBeHandedToATrain() {
		final MmtrPlanDispatcher d = dispatcher(MmtrLine.TerminalTreatment.CHANGE_ENDS, false);
		final var tasks = d.states.get(0).tasks;

		MmtrTask changeEnds = null;
		for (int i = 0; i < tasks.size(); i++) {
			if (tasks.get(i).kind() == MmtrTaskKind.CHANGE_ENDS) {
				changeEnds = tasks.get(i);
			}
		}
		assertNotNull(changeEnds, "一趟往返里应当有换端的步骤（共 " + tasks.size() + " 步）");
		assertEquals(0, changeEnds.targetRef, "换端是原地动作，没有目标");
		assertTrue(changeEnds.inPlace(), "换端必须被认成原地动作");
		assertTrue(changeEnds.dispatchable(), "没有目标也要派得出去（修前这一步永远派不出去）");

		// 而真正要开走的那一步仍然必须有目标
		assertTrue(tasks.get(0).dispatchable(), "出库那一步有目标");
		assertFalse(tasks.get(0).inPlace(), "出库不是原地动作");
	}

	// ---------------------------------------------------------------- 出库排队（咽喉一次只放一台）

	/**
	 * **两台车不许同时出库**（notes/152 现场）。
	 *
	 * <p>车场咽喉只有一组道岔，而每台车的停车位都落在**别人要过的那处道岔的净空区**里（净空闸
	 * "不许把道岔从车下抽走"是对的）。现场就是两台车各自排在对方后面、`phys=-`、谁也不动。
	 * 计划层的规矩：两次"出库那一步"之间隔开 {@link MmtrPlanDispatcher#YARD_DEPARTURE_GAP_MILLIS}。</p>
	 *
	 * <p>红证：去掉那条间隔判断，第二台车会在同一 tick 里跟着出库（本用例立刻红）。</p>
	 */
	@Test
	public void twoWorkingsDoNotLeaveTheYardAtOnce() {
		final MmtrLine line = line(MmtrLine.TerminalTreatment.CHANGE_ENDS, false);
		final MmtrDiagram diagram = MmtrDiagram.generate(line, shortPattern(), fleet(2), TIMES);
		final MmtrPlanDispatcher d = new MmtrPlanDispatcher(line, diagram);
		final FakeWorld world = new FakeWorld(9001L, 9002L);

		// 到点（06:55 出库）：同一 tick 只放一台，第二台排队
		d.tick(H07 - 5 * MIN, world);
		assertEquals(1, world.dispatched.size(), "同一时刻只放一台出库：" + world.dispatched);
		assertEquals(1, d.dispatchedTotal);

		// 判据本身（红证：去掉间隔判断这条就红）
		final long gap = MmtrPlanDispatcher.YARD_DEPARTURE_GAP_MILLIS;
		final long first = H07 - 5 * MIN;
		assertTrue(MmtrPlanDispatcher.yardDepartureAllowed(first, 0), "还没派过 ⇒ 允许出库");
		assertFalse(MmtrPlanDispatcher.yardDepartureAllowed(first + gap - 1, first), "间隔没到 ⇒ 不许第二台跟着出");
		assertTrue(MmtrPlanDispatcher.yardDepartureAllowed(first + gap, first), "间隔到了 ⇒ 放行");
		assertTrue(MmtrPlanDispatcher.yardDepartureAllowed(first + 30 * MIN, first), "过了很久 ⇒ 当然放行");
	}

	/**
	 * ③ **接管只换执行者**（设计 §8.2）：玩家接管后派发器一步不派、交路与任务**一个字节都不改**；
	 * 归还后**从那一步续行**（不跳步、不从头上再来）。
	 */
	@Test
	public void aPlayerTakeoverOnlyChangesWhoRunsIt() {
		final MmtrPlanDispatcher d = dispatcher(MmtrLine.TerminalTreatment.CHANGE_ENDS, false);
		final FakeWorld world = new FakeWorld(9001L);
		d.tick(H07, world);
		final int dispatchedBefore = d.dispatchedTotal;
		final int stepsBefore = d.states.get(0).tasks.size();
		final var tasksBefore = new java.util.ArrayList<String>();
		d.states.get(0).tasks.forEach(task -> tasksBefore.add(task.taskId + "@" + task.dueMs));
		assertTrue(dispatchedBefore >= 1);

		// AI → 玩家
		assertTrue(d.setPlayerDriven("C1", true), "接管成功");
		for (int i = 0; i < 2; i++) {
			d.tick(H07 + (i + 1) * MIN, world);
		}
		assertEquals(dispatchedBefore, d.dispatchedTotal, "玩家开着的时候派发器一步都不派");
		assertEquals(0, d.retryCount, "也不算重试（不是'车被占'，是人在开）");
		assertEquals(stepsBefore, d.states.get(0).tasks.size(), "任务数不变");
		final var tasksAfter = new java.util.ArrayList<String>();
		d.states.get(0).tasks.forEach(task -> tasksAfter.add(task.taskId + "@" + task.dueMs));
		assertEquals(tasksBefore, tasksAfter, "交路与任务逐条不变（接管只换执行者）");

		// 玩家 → AI：从那一步续行
		assertTrue(d.setPlayerDriven("C1", false), "归还成功");
		d.tick(H07 + 5 * MIN, world);   // 07:05：下一步（07:04 到第二站）已在迟到宽限内
		assertEquals(dispatchedBefore + 1, d.dispatchedTotal, "归还后接着派下一步（不跳步）");
		assertFalse(d.isPlayerDriven("C1"));
	}
}