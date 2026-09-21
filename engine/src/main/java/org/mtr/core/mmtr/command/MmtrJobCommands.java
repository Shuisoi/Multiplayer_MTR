package org.mtr.core.mmtr.command;

import org.mtr.core.data.Vehicle;
import org.mtr.core.simulation.Simulator;

import java.util.List;
import java.util.Map;

/**
 * {@code job …}：**作业单的接管与归还**（"玩家与作业表相连"的操作面）。
 *
 * <h3>为什么单开一个名词</h3>
 * <p>它管的既不是"车的存在"（{@code vehicle}）也不是"车在跑什么"的即时动作（{@code train}），
 * 而是**谁在执行作业表里那一步**：接管之后油门归司机、进路与停车点仍由引擎给，
 * 走到位就算这一步完成。这三件事跨了车、作业单、联锁三层，塞进任何一边都会让那张用法表说不清。</p>
 *
 * <h3>只允许在静止状态接管</h3>
 * <p>用户口径（2026-09-21）："比如在车辆还在等待发车，到站停站等静止状态时接管"。
 * 车在动时换执行者，进路/道岔持有/protection 都还是"为自动车算出来的"那一份，
 * 交接窗口里每一拍都可能在两个执行者之间空转。判据在
 * {@link Simulator#mmtrJobTakeover(long, java.util.UUID)} 里（速度必须为 0）。</p>
 */
final class MmtrJobCommands {

	private MmtrJobCommands() {
	}

	static MmtrCommandDispatcher.Result execute(Simulator simulator, String verb, List<String> positional, Map<String, String> options) {
		switch (verb) {
			case "take":
				return take(simulator, positional);
			case "release":
				return release(simulator, positional);
			case "status":
				return status(simulator, positional);
			case "dwell":
				return dwell(simulator, positional);
			default:
				return MmtrCommandDispatcher.usage("job 支持 take / release / status / dwell");
		}
	}

	/**
	 * {@code job dwell [秒数]}：**站台作业的停留时长**（用户口径「时间暂定 20 秒，但保留修改接口」）。
	 *
	 * <p>不带参数就是查当前值。改它只影响**之后**建的子任务链（正在停的那一站照原时间停完），
	 * 理由见 {@link Simulator#mmtrSetSubTaskDwellSeconds(long)}。</p>
	 */
	private static MmtrCommandDispatcher.Result dwell(Simulator simulator, List<String> positional) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "job", "dwell");
		if (!positional.isEmpty()) {
			final long seconds;
			try {
				seconds = Math.round(Double.parseDouble(positional.get(0).trim()));
			} catch (NumberFormatException e) {
				return MmtrCommandDispatcher.usage("停留秒数必须是数字：" + positional.get(0));
			}
			simulator.mmtrSetSubTaskDwellSeconds(seconds);
			result.line("站台停留已改为 " + Math.round(simulator.mmtrSubTaskDwellMillis() / 1000.0) + "s（输入 "
				+ seconds + "s，超范围会被夹到 1–3600s）—— 只影响之后建的子任务链");
		}
		result.line("当前站台停留 " + Math.round(simulator.mmtrSubTaskDwellMillis() / 1000.0) + "s"
			+ "（作业单若给了更长的计划停留，取计划那条）");
		return result;
	}

	/**
	 * {@code job take <车辆id> [<司机uuid>]}：把该车当前挂着的作业单交给司机。
	 *
	 * <p>不给 uuid 时从**驾驶室钥匙**读（谁坐进了驾驶室），所以控制台里敲
	 * {@code job take <车id>} 就够了 —— 不必让人把自己的 uuid 抄一遍。</p>
	 */
	private static MmtrCommandDispatcher.Result take(Simulator simulator, List<String> positional) {
		if (positional.isEmpty()) {
			return MmtrCommandDispatcher.usage("job take 需要 <车辆id>（可选 <司机uuid>）");
		}
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "job", "take");
		final long vehicleId;
		try {
			vehicleId = Long.parseLong(positional.get(0).trim());
		} catch (NumberFormatException e) {
			result.line("车辆 id 必须是数字：" + positional.get(0));
			return new MmtrCommandDispatcher.Result(false, "job", "take");
		}
		java.util.UUID driver = null;
		if (positional.size() >= 2) {
			try {
				driver = java.util.UUID.fromString(positional.get(1).trim());
			} catch (IllegalArgumentException e) {
				result.line("司机 uuid 格式不对：" + positional.get(1));
				return new MmtrCommandDispatcher.Result(false, "job", "take");
			}
		}
		final String reason = simulator.mmtrJobTakeover(vehicleId, driver);
		if (reason != null) {
			final MmtrCommandDispatcher.Result failure = new MmtrCommandDispatcher.Result(false, "job", "take");
			failure.line("接管被拒：" + reason);
			return failure;
		}
		final Vehicle vehicle = simulator.mmtrFindVehicle(vehicleId);
		final org.mtr.core.mmtr.MmtrMission mission = vehicle == null ? null : vehicle.getMmtrMission();
		result.add(String.valueOf(vehicleId));
		result.line("已接管：车 " + vehicleId + " 的作业 "
			+ (mission == null ? "（当前没有在跑的一步）" : mission.getJobId())
			+ " 交给司机 " + (driver == null ? "（驾驶室钥匙持有人）" : driver));
		result.line("引擎仍然替你做这些：发布进路、申请/扳道岔、给信号；"
			+ "油门与制动完全在你手里 —— 车停到站台上（任意一节车在站台轨上、停稳）就算到站，然后按顺序做开门 → 停够 → 关门。");
		if (mission != null) {
			result.line("当前这一步：" + describeStep(mission));
		}
		return result;
	}

	/** {@code job release <车辆id>}：把作业单还给自动执行（车上这一步也换回 AUTOPILOT）。 */
	private static MmtrCommandDispatcher.Result release(Simulator simulator, List<String> positional) {
		if (positional.isEmpty()) {
			return MmtrCommandDispatcher.usage("job release 需要 <车辆id>");
		}
		final long vehicleId;
		try {
			vehicleId = Long.parseLong(positional.get(0).trim());
		} catch (NumberFormatException e) {
			return MmtrCommandDispatcher.usage("车辆 id 必须是数字：" + positional.get(0));
		}
		final String reason = simulator.mmtrJobRelease(vehicleId);
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(reason == null, "job", "release");
		result.add(String.valueOf(vehicleId));
		result.line(reason == null ? "已归还给自动：车 " + vehicleId : "归还被拒：" + reason);
		return result;
	}

	/** {@code job status [车辆id]}：现在谁在执行、执行到哪一步。 */
	private static MmtrCommandDispatcher.Result status(Simulator simulator, List<String> positional) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "job", "status");
		final org.mtr.core.mmtr.job.MmtrJobScheduler scheduler = simulator.mmtrJobScheduler;
		if (scheduler == null) {
			result.line("这局里没有作业单调度器（没有作业单）");
			return result;
		}
		if (!positional.isEmpty()) {
			final long vehicleId;
			try {
				vehicleId = Long.parseLong(positional.get(0).trim());
			} catch (NumberFormatException e) {
				return MmtrCommandDispatcher.usage("车辆 id 必须是数字：" + positional.get(0));
			}
			final Vehicle vehicle = simulator.mmtrFindVehicle(vehicleId);
			final org.mtr.core.mmtr.MmtrMission mission = vehicle == null ? null : vehicle.getMmtrMission();
			final String jobId = scheduler.jobIdOfVehicle(vehicleId);
			result.line("车 " + vehicleId + "：" + (jobId == null ? "没有挂在任何作业单上" : "作业 " + jobId)
				+ (vehicle == null ? "" : "  速度 " + Math.round(vehicle.getSpeed() * 3600 * 100) / 100.0 + " km/h"));
			result.line("  当前这一步：" + describeStep(mission));
			if (mission != null && mission.hasSubTasks()) {
				/*
				 * 子任务清单与**实际进出站时刻**：司机在站台上等的这几十秒里，网页指令栏（运营台）
				 * 与 HUD 看到的是同一份状态（同一份编码），所以这一行就是"双向确认"的引擎侧底稿。
				 */
				final long now = simulator.getCurrentMillis();
				result.line("  子任务 " + mission.subTasksDoneCount() + "/" + mission.subTasks().size()
					+ "：" + mission.describeSubTasks(now)
					+ "  确认 " + mission.subTaskAcks() + " 次（rev " + mission.subTaskRevision() + "）");
				result.line("  现在该做：" + mission.subTaskHint(now));
				result.line("  进站=" + describeMillis(mission.getStationArrivalMillis())
					+ "  出站=" + describeMillis(mission.getStationDepartureMillis())
					+ "  停留要求=" + Math.round(mission.subTaskDwellMillis() / 1000.0) + "s");
			}
			if (vehicle != null) {
				result.line("  驾驶室：现在占用=" + vehicle.getMmtrActiveCab()
					+ "  引擎认为该进=" + (vehicle.mmtrPreferredCabSpec().isEmpty() ? "（不是编组体车）" : vehicle.mmtrPreferredCabSpec())
					+ "  行进方向=" + describeDirection(vehicle));
			}
			return result;
		}
		for (final org.mtr.core.mmtr.job.MmtrConsistJob job : simulator.getMmtrJobRegistry().jobs) {
			result.line("作业 " + job.jobId
				+ "  状态=" + scheduler.stateOf(job.jobId)
				+ "  步=" + scheduler.stepIndexOf(job.jobId) + "/" + job.steps.size()
				+ "  人工=" + scheduler.isHumanHeld(job.jobId)
				+ "  司机=" + (scheduler.driverOf(job.jobId) == null ? "（无）" : scheduler.driverOf(job.jobId)));
		}
		return result;
	}

	/**
	 * 行进方向的一句话：**领先端在哪一边 + 换向器是否拉着**。
	 *
	 * <p>这两件事必须一起说：{@code travelsTowardB()} 是"实际往哪边走"，而"实际"= 领先端 ⊕ 换向器 ——
	 * 只报其中之一，"司机坐在车尾"这种现场是看不出来的（实机 2026-09-21）。</p>
	 */
	private static String describeDirection(Vehicle vehicle) {
		final org.mtr.core.mmtr.consist.MmtrConsistWalker walker = vehicle.getMmtrConsistWalker();
		if (walker == null) {
			return "（不是编组体车）";
		}
		return (walker.travelsTowardB() ? "朝 B 端" : "朝 A 端")
			+ "（领先端=" + (walker.cabs().leadingEnd() == null ? "无人" : walker.cabs().leadingEnd())
			+ "·换向器=" + (walker.travelReversed() ? "拉（尾在前）" : "推（头在前）") + "）";
	}

	/** 毫秒时刻的一句话（{@code -1} = 还没发生）。 */
	private static String describeMillis(long millis) {
		return millis < 0 ? "（未发生）" : millis + "ms";
	}

	/** 一步的人话：作业号 / 第几步 / 谁在执行 / 提示（与发给客户端的提示同源）。 */	private static String describeStep(org.mtr.core.mmtr.MmtrMission mission) {
		if (mission == null) {
			return "（没有任务）";
		}
		final StringBuilder text = new StringBuilder();
		if (!mission.getJobId().isEmpty()) {
			text.append(mission.getJobId()).append(' ')
				.append(mission.getJobStepIndex() + 1).append('/').append(mission.getJobStepCount()).append(' ');
		}
		text.append(mission.getKind()).append(' ').append(mission.getState())
			.append(" 执行者=").append(mission.getExecutor());
		if (!mission.getJobStepNote().isEmpty()) {
			text.append(" 「").append(mission.getJobStepNote()).append('」');
		}
		return text.toString();
	}
}
