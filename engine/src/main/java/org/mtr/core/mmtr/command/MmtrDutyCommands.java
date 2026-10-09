package org.mtr.core.mmtr.command;

import org.mtr.core.mmtr.duty.MmtrDutyRegistry;
import org.mtr.core.simulation.Simulator;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code duty …}：**玩家值守**的操作面（notes/408 S1 的指令入口，S2 的游戏内指令照这个形状写）。
 *
 * <h3>为什么单开一个名词，而不是并进 {@code job}</h3>
 * <p>{@code job} 说的是"这条**作业单**的执行者是谁"；而值守说的是"**这个玩家**现在是什么态" ——
 * 它先于作业单存在（认领时车可能还没挂上一条在跑的步骤）、也晚于它结束
 * （交还之后人还在车上，算"已退出"而不是空闲）。{@code job take}/{@code job release} 从此是
 * **更低层的原语**：值守进入 {@code DRIVING} 时调的就是 {@link Simulator#mmtrJobTakeover}
 * （notes/408 §2.4）。塞进 {@code job} 会让那张用法表说不清"我到底在操作车还是操作作业单"。</p>
 *
 * <h3>只收 uuid</h3>
 * <p>引擎不知道玩家名字（那是游戏端的事），所以这里的玩家一律是 uuid；{@code --name=} 是可选的 ——
 * 游戏端拿到自己的 uuid 与名字之后顺手填上，界面与日志里就有人话可读（空着也能跑）。</p>
 */
final class MmtrDutyCommands {

	private MmtrDutyCommands() {
	}

	static MmtrCommandDispatcher.Result execute(Simulator simulator, String verb, List<String> positional, Map<String, String> options) {
		switch (verb) {
			case "status":
				return status(simulator, positional);
			case "claim":
				return claim(simulator, positional, options);
			case "assign":
				return assign(simulator, positional, options);
			case "exit":
				return exit(simulator, positional, options);
			case "cancel":
				return cancel(simulator, positional);
			default:
				return MmtrCommandDispatcher.usage("duty 支持 status / claim / assign / exit / cancel");
		}
	}

	/**
	 * {@code duty status [<玩家uuid>]}：列（或单个）值守状态。
	 *
	 * <p>输出里带 {@code stateWord()} 与 {@code reason()} —— 与 HUD、动作栏播报**同一份编码**
	 * （notes/408 §3.4），所以这一行就是"界面上的字"的引擎侧底稿，核对时能逐字对上。</p>
	 */
	private static MmtrCommandDispatcher.Result status(Simulator simulator, List<String> positional) {
		final MmtrDutyRegistry registry = simulator.mmtrDuties;
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "duty", "status");
		if (!positional.isEmpty()) {
			final UUID playerUuid = parseUuid(positional.get(0));
			if (playerUuid == null) {
				return MmtrCommandDispatcher.usage("玩家 uuid 格式不对：" + positional.get(0));
			}
			final MmtrDutyRegistry.Duty duty = registry.of(playerUuid);
			result.add(playerUuid.toString());
			if (duty == null) {
				result.line("玩家 " + playerUuid + "：空闲（没有认领任何车次）");
				return result;
			}
			result.line("玩家 " + playerUuid + "：" + duty.stateWord() + "（" + duty.state() + "）");
			result.line("  车 " + (duty.vehicleId() == 0 ? "（还没绑车）" : duty.vehicleId())
				+ "  车次 " + (duty.jobId().isEmpty() ? "（无）" : duty.jobId())
				+ "  驾驶室 " + (duty.cabSpec().isEmpty() ? "（无）" : duty.cabSpec())
				+ "  等待站台 " + (duty.waitPlatformId() == 0 ? "（不适用）" : duty.waitPlatformId()));
			result.line("  为什么停在这个态：" + duty.reason());
			result.line("  自 " + duty.sinceMillis() + "ms；一句话=" + registry.describe(duty));
			return result;
		}
		final List<MmtrDutyRegistry.Duty> all = registry.all();
		if (all.isEmpty()) {
			result.line("现在没有任何玩家值守（全部空闲）");
			return result;
		}
		result.line("值守 " + all.size() + " 条：");
		for (final MmtrDutyRegistry.Duty duty : all) {
			result.add(duty.playerUuid().toString());
			result.line("  " + registry.describe(duty)
				+ "  车=" + duty.vehicleId()
				+ "  为什么=" + duty.reason());
		}
		return result;
	}

	/**
	 * {@code duty claim <玩家uuid> <车辆id> [--wait] [--name=<玩家名>]}。
	 *
	 * <p>{@code --wait} = 站台接站（{@code WAITING}），否则直接上车（{@code ABOARD}）。
	 * 两条路的可用性判据都在引擎里（{@code refusalForDirectBoard} / {@code refusalForPlatformMeet}），
	 * 所以 PDA 出不出现的按钮与这里拒绝的理由是**同一个判断**。</p>
	 */
	private static MmtrCommandDispatcher.Result claim(Simulator simulator, List<String> positional, Map<String, String> options) {
		if (positional.size() < 2) {
			return MmtrCommandDispatcher.usage("duty claim 需要 <玩家uuid> <车辆id> [--wait] [--name=<玩家名>]");
		}
		final UUID playerUuid = parseUuid(positional.get(0));
		if (playerUuid == null) {
			return MmtrCommandDispatcher.usage("玩家 uuid 格式不对：" + positional.get(0));
		}
		final long vehicleId;
		try {
			vehicleId = Long.parseLong(positional.get(1).trim());
		} catch (NumberFormatException e) {
			return MmtrCommandDispatcher.usage("车辆 id 必须是数字：" + positional.get(1));
		}
		final String name = options.getOrDefault("name", "");
		final boolean wait = options.containsKey("wait");
		final MmtrDutyRegistry registry = simulator.mmtrDuties;
		final MmtrDutyRegistry.@org.jspecify.annotations.Nullable Duty before = registry.of(playerUuid);
		final String refusal = wait
			? registry.claimPlatform(playerUuid, name, vehicleId)
			: registry.claimDirect(playerUuid, name, vehicleId);
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(refusal == null, "duty", "claim");
		result.add(playerUuid.toString());
		result.add(String.valueOf(vehicleId));
		if (refusal != null) {
			result.line((wait ? "站台接站" : "直接上车") + "被拒：" + refusal);
			return result;
		}
		final MmtrDutyRegistry.Duty after = registry.of(playerUuid);
		result.line((wait ? "站台接站" : "直接上车") + "成功：" + registry.describe(after)
			+ (before == null || before.state() == MmtrDutyRegistry.State.IDLE ? "" : "（原来 " + before.stateWord() + "）"));
		if (after != null && after.state() == MmtrDutyRegistry.State.WAITING) {
			result.line("  下一个待办：车到站停稳后引擎会排一条 PendingBoard，游戏端据此通知他上车接管"
				+ "（站台接站**不传送**任何人 —— 他本来就在站台上）");
		} else {
			result.line("  下一个待办：游戏端把他传送上车并进驾驶室，然后回调 arriveAndBoard");
		}
		return result;
	}

	/**
	 * {@code duty assign <玩家uuid> <车次名> <驾驶室> [--wait] [--name=<玩家名>]}：**给某个玩家派车**
	 * （notes/409 §0 的 ①，用户口径）。
	 *
	 * <pre>
	 *   输入玩家 / 车次（作业单名） / 驾驶室编号 → 为他认领接下来要开的那趟车；
	 *   --wait = 在下一停站车站站台等候（WAITING，引擎不传送任何人）；
	 *   默认    = 直接传送到车上并进驾驶室（ABOARD；车停稳就当场交驾驶权，见 arriveAndBoard）。
	 * </pre>
	 *
	 * <p>三个参数各自解析之后**全部交给 {@link MmtrDutyRegistry#assign}** 判：车次名 → 车辆 id 走
	 * 官方那一份"场上有哪些车次"（{@code allVehicleRows}），驾驶室编号走
	 * {@code cabSpecRefusal}。这里**不复刻**任何一条业务判据（铁律：动词只有一处实现）——
	 * 本方法只负责把参数读成人话、把回话拼成人能核对的几行。</p>
	 */
	private static MmtrCommandDispatcher.Result assign(Simulator simulator, List<String> positional, Map<String, String> options) {
		if (positional.size() < 3) {
			return MmtrCommandDispatcher.usage("duty assign 需要 <玩家uuid> <车次名> <驾驶室> [--wait] [--name=<玩家名>]"
				+ "（例如 duty assign 0f1e…-… 00103 1A --wait --name=Shuisoi）");
		}
		final UUID playerUuid = parseUuid(positional.get(0));
		if (playerUuid == null) {
			return MmtrCommandDispatcher.usage("玩家 uuid 格式不对：" + positional.get(0));
		}
		final String jobId = positional.get(1).trim();
		final String cabSpec = positional.get(2).trim();
		final String name = options.getOrDefault("name", "");
		/*
		 * 三种写法都收：`--wait` = 站台接站；`--now` = 直接传送（**显式写出默认值** —— 用户 brief 里
		 * 就是"下一停站站台等候 / 直接传送"这两种并列的写法）；都没有 = 直接传送。
		 * 两个都给时 --now 胜（"现在就去"是更具体的意图）。
		 */
		final boolean wait = options.containsKey("wait") && !options.containsKey("now");
		final MmtrDutyRegistry registry = simulator.mmtrDuties;
		final MmtrDutyRegistry.@org.jspecify.annotations.Nullable Duty before = registry.of(playerUuid);
		final String refusal = registry.assign(playerUuid, name, jobId, cabSpec, wait);
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(refusal == null, "duty", "assign");
		result.add(playerUuid.toString());
		result.add(jobId);
		if (refusal != null) {
			result.line("派车被拒：" + refusal);
			return result;
		}
		final MmtrDutyRegistry.Duty after = registry.of(playerUuid);
		// 车辆 id 从记录上回读：调用方给的是**车次名**，"最后派到哪辆车"必须由引擎说（= 面板上那一行）。
		result.add(after == null ? "" : String.valueOf(after.vehicleId()));
		result.line("派车成功：" + registry.describe(after)
			+ (before == null || before.state() == MmtrDutyRegistry.State.IDLE ? "" : "（原来 " + before.stateWord() + "）"));
		result.line("  车次 " + jobId + " → 车辆 " + (after == null ? "?" : after.vehicleId())
			+ "  驾驶室 " + (after == null || after.cabSpec().isEmpty() ? "（由引擎按行进方向挑）" : after.cabSpec())
			+ "  " + (wait ? "站台接站（不传送，他自己走到站台等）" : "直接传送（游戏端把他送上车并进驾驶室）"));
		result.line(wait
			? "  下一个待办：车到站停稳后引擎排一条 PendingBoard，游戏端据此通知他上车接管"
			: "  下一个待办：游戏端把他传送上车并进驾驶室，然后回调 arriveAndBoard");
		return result;
	}

	/** {@code duty exit <玩家uuid> [--next] [--name=<玩家名>]}：{@code --next} = 下一站退出，否则马上退出。 */
	private static MmtrCommandDispatcher.Result exit(Simulator simulator, List<String> positional, Map<String, String> options) {
		if (positional.isEmpty()) {
			return MmtrCommandDispatcher.usage("duty exit 需要 <玩家uuid> [--next]");
		}
		final UUID playerUuid = parseUuid(positional.get(0));
		if (playerUuid == null) {
			return MmtrCommandDispatcher.usage("玩家 uuid 格式不对：" + positional.get(0));
		}
		final MmtrDutyRegistry registry = simulator.mmtrDuties;
		final boolean next = options.containsKey("next");
		final String refusal = next ? registry.armExitAtNextStop(playerUuid) : registry.exitNow(playerUuid);
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(refusal == null, "duty", "exit");
		result.add(playerUuid.toString());
		if (refusal != null) {
			result.line((next ? "下一站退出" : "马上退出") + "被拒：" + refusal);
			return result;
		}
		final MmtrDutyRegistry.Duty after = registry.of(playerUuid);
		if (after == null) {
			result.line((next ? "下一站退出" : "马上退出") + "成功：现在空闲（值守记录已清）");
		} else {
			result.line((next ? "下一站退出" : "马上退出") + "成功：" + registry.describe(after));
			if (after.state() == MmtrDutyRegistry.State.DRIVING_EXIT_ARMED) {
				result.line("  到站停稳后引擎会自动交还；若有人在站台上等着接，会**直接交给他**（不用再等一次静止闸门）");
			}
		}
		return result;
	}

	/** {@code duty cancel <玩家uuid>}：清掉这条值守（下车 / 改主意 / 出了异常要手工收拾）。 */
	private static MmtrCommandDispatcher.Result cancel(Simulator simulator, List<String> positional) {
		if (positional.isEmpty()) {
			return MmtrCommandDispatcher.usage("duty cancel 需要 <玩家uuid>");
		}
		final UUID playerUuid = parseUuid(positional.get(0));
		if (playerUuid == null) {
			return MmtrCommandDispatcher.usage("玩家 uuid 格式不对：" + positional.get(0));
		}
		final MmtrDutyRegistry registry = simulator.mmtrDuties;
		final MmtrDutyRegistry.Duty before = registry.of(playerUuid);
		registry.cancel(playerUuid, "指令 duty cancel");
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "duty", "cancel");
		result.add(playerUuid.toString());
		result.line(before == null ? "玩家 " + playerUuid + " 本来就没有值守（空闲）" : "已清：原 " + registry.describe(before));
		return result;
	}

	/** uuid 解析：格式不对返回 {@code null}（调用方给用法）。 */
	private static UUID parseUuid(String raw) {
		try {
			return UUID.fromString(raw == null ? "" : raw.trim());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
