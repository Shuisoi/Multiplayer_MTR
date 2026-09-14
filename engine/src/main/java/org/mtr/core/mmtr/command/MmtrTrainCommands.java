package org.mtr.core.mmtr.command;

import org.mtr.core.data.Vehicle;
import org.mtr.core.operation.MmtrCoupleControl;
import org.mtr.core.simulation.Simulator;

import java.util.List;
import java.util.Map;

/**
 * {@code train …}：对**已经存在的车**做操作（连挂、解挂、开关门、换端、调车授权、诊断）。
 *
 * <h3>为什么和 {@code vehicle …} 分开</h3>
 * <p>两个名词管的不是一类东西：{@code vehicle} 管"车的存在"（生成、删除、列出 —— 数据层的增删），
 * {@code train} 管"车在跑什么"（连挂解挂、车门、司机室、调车授权 —— 运行层的动作）。
 * 混成一个名词的话，{@code vehicle doors …} 和 {@code vehicle spawn …} 会并列在同一张用法表里，
 * 而它们需要的参数、失败原因、检查方式完全不同。</p>
 *
 * <h3>哪些在引擎侧做，哪些转游戏端</h3>
 * <p>分界线是"这件事需要不需要 tick 里的世界"：</p>
 * <ul>
 *   <li><b>连挂/解挂</b>：引擎自己就能做（编组是引擎的数据），走 {@link MmtrCoupleControl}——
 *       这样回复里能立刻带上"连成了什么"，而不是"已入队等游戏端"。游戏端原本的 {@code couple}/{@code uncouple}
 *       文本指令仍然可用，两者走的是同一段实现。</li>
 *   <li><b>车门 / 换端 / 司机室 / 调车授权 / 诊断</b>：这些动的是世界里的实体（车门开合、司机位置、
 *       路由追踪），只有游戏端知道，所以推给游戏端命令通道执行，结果写进命令日志。</li>
 * </ul>
 */
final class MmtrTrainCommands {

	private MmtrTrainCommands() {
	}

	static MmtrCommandDispatcher.Result execute(Simulator simulator, String verb, List<String> positional, Map<String, String> options) {
		switch (verb) {
			case "couple":
				return couple(simulator, positional);
			case "uncouple":
				return uncouple(simulator, positional);
			// 以下动词的实现在游戏端（世界里的实体），这里只负责改名与转发
			case "doors":
			case "changeends":
			case "cab":
			case "shunt":
			case "interlock":
			case "trace":
				return relayToGame(simulator, verb, positional, options);
			default:
				return MmtrCommandDispatcher.usage("train 支持 couple / uncouple / doors / changeends / cab / shunt / interlock / trace");
		}
	}

	/**
	 * {@code train couple <主动车id> <目标车id>}：把两列车连挂成一列。
	 *
	 * <p>与游戏端 {@code couple} 指令效果一致（同一段 {@link MmtrCoupleControl}），
	 * 但回复里直接给出结果，不需要再去日志里找游戏端说了什么。</p>
	 */
	private static MmtrCommandDispatcher.Result couple(Simulator simulator, List<String> positional) {
		if (positional.size() < 2) {
			return MmtrCommandDispatcher.usage("train couple 需要两个车辆 id：<主动车id> <目标车id>");
		}
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "train", "couple");
		final long initiatorId;
		final long targetId;
		try {
			initiatorId = Long.parseLong(positional.get(0).trim());
			targetId = Long.parseLong(positional.get(1).trim());
		} catch (NumberFormatException e) {
			result.line("车辆 id 必须是数字：" + positional.get(0) + " " + positional.get(1));
			return new MmtrCommandDispatcher.Result(false, "train", "couple");
		}
		final Vehicle initiator = simulator.mmtrFindVehicle(initiatorId);
		final Vehicle target = simulator.mmtrFindVehicle(targetId);
		if (initiator == null || target == null) {
			final MmtrCommandDispatcher.Result failure = new MmtrCommandDispatcher.Result(false, "train", "couple");
			failure.line(initiator == null ? "找不到主动车 " + initiatorId : "找不到目标车 " + targetId);
			failure.line("用 vehicle list 看看场上有哪些车。");
			return failure;
		}
		/*
		 * 连挂会**重建车辆对象**（两辆并成一辆），所以结果必须按引擎的实情回话，不能沿用原来的对象。
		 * 被拒时（不在同一股道、车钩朝向不对、编组超长…）要说被拒以及原因 —— 这里若一律回"已连挂"，
		 * 调用方就会照着假状态继续干活。
		 */
		final org.mtr.core.data.MmtrCoupleSurgery.Result outcome = new MmtrCoupleControl(initiatorId, targetId, -1).coupleResult(simulator);
		if (!outcome.ok() || outcome.vehicle() == null) {
			final MmtrCommandDispatcher.Result failure = new MmtrCommandDispatcher.Result(false, "train", "couple");
			failure.line("连挂被拒：" + (outcome.ok() ? "手术没有产出车辆" : outcome.reason()));
			failure.line("（拒绝原因来自引擎的连挂手术，例如两车不在同一股道、车钩朝向不对、合并后超过股道长度。）");
			return failure;
		}
		result.add(String.valueOf(outcome.vehicle().getId()));
		result.line("连挂完成：" + initiatorId + " + " + targetId + " → 车辆 " + outcome.vehicle().getId()
			+ "（共 " + outcome.mergedCarCount() + " 节）");
		result.line("注意：连挂会重建车辆对象，原来的两个 id 通常都不再存在——请用上面这个新 id 继续操作。");
		return result;
	}

	/** {@code train uncouple <车辆id> <在第几节之后切开>}：按车钩接缝把编组切成两列。 */
	private static MmtrCommandDispatcher.Result uncouple(Simulator simulator, List<String> positional) {
		if (positional.size() < 2) {
			return MmtrCommandDispatcher.usage("train uncouple 需要 <车辆id> <在第几节之后切开>（切点必须在车钩接缝上）");
		}
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "train", "uncouple");
		final long vehicleId;
		final int cutAfter;
		try {
			vehicleId = Long.parseLong(positional.get(0).trim());
			cutAfter = Integer.parseInt(positional.get(1).trim());
		} catch (NumberFormatException e) {
			result.line("参数必须是数字：<车辆id> <在第几节之后切开>");
			return new MmtrCommandDispatcher.Result(false, "train", "uncouple");
		}
		final Vehicle vehicle = simulator.mmtrFindVehicle(vehicleId);
		if (vehicle == null) {
			final MmtrCommandDispatcher.Result failure = new MmtrCommandDispatcher.Result(false, "train", "uncouple");
			failure.line("找不到车辆 " + vehicleId + "；用 vehicle list 看看场上有哪些车。");
			return failure;
		}
		final org.mtr.core.data.MmtrCoupleSurgery.Result outcome = new MmtrCoupleControl(vehicleId, 0, cutAfter).uncoupleResult(simulator);
		if (!outcome.ok() || outcome.vehicle() == null) {
			final MmtrCommandDispatcher.Result failure = new MmtrCommandDispatcher.Result(false, "train", "uncouple");
			failure.line("解挂被拒：" + (outcome.ok() ? "手术没有产出车辆" : outcome.reason()));
			failure.line("切点必须落在**车钩接缝**上：动车组内部没有车钩，切在那里会被拒绝（用 vehicle list 看这列车有几节）。");
			return failure;
		}
		result.add(String.valueOf(outcome.vehicle().getId()));
		if (outcome.other() != null) {
			result.add(String.valueOf(outcome.other().getId()));
		}
		result.line("解挂完成：车辆 " + vehicleId + " 在第 " + cutAfter + " 节之后切开 → 前段 " + outcome.vehicle().getId()
			+ "（" + outcome.mergedCarCount() + " 节）"
			+ (outcome.other() == null ? "" : " + 后段 " + outcome.other().getId()));
		result.line("注意：解挂会重建车辆对象，请用上面这两个新 id 继续操作。");
		return result;
	}

	/**
	 * 转交游戏端执行：把名词打头的写法**翻译回**游戏端认的动词打头文本。
	 *
	 * <p>翻译而不是改游戏端：游戏端那套 {@code doors <id> open} 已经在用（游戏内控制台、调试脚本都在敲），
	 * 改掉它就等于把两个入口的语法都换一遍。翻译只有一行，却让两个入口能并存。</p>
	 */
	private static MmtrCommandDispatcher.Result relayToGame(Simulator simulator, String verb, List<String> positional, Map<String, String> options) {
		final StringBuilder line = new StringBuilder(verb);
		for (final String word : positional) {
			line.append(' ').append(word);
		}
		// 选项按游戏端习惯的裸值顺序补上（游戏端是位置参数，不解析 --key=value）
		final String[] optionOrder = switch (verb) {
			case "doors" -> new String[]{"side"};
			case "cab" -> new String[]{};
			default -> new String[]{"minutes", "kmh", "kind"};
		};
		for (final String key : optionOrder) {
			final String value = options.get(key);
			if (value != null) {
				line.append(' ').append(value);
			}
		}
		final String gameCommand = line.toString();
		simulator.mmtrPushCommand(gameCommand);

		/*
		 * 回复里说明"这是转交"，而不是假装已经做完。
		 *
		 * 转交类指令的结果只有游戏端知道，我们这里能做的最诚实的事是：告诉调用方去日志里看哪一行，
		 * 并附上它需要的入口。若这里回一句"成功"，调用方就会以为车门已经开了——而它可能还在队列里。
		 */
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "train", verb);
		result.line("已转交游戏端执行：" + gameCommand);
		result.line("原因：这条动的是世界里的实体，只有游戏端能做（需要 tick 里的世界）。");
		result.line("结果会由游戏端写进命令日志（指令栏里直接能看到）。");
		switch (verb) {
			case "doors" -> result.line("用法：train doors <车辆id> [open|close|toggle] [--side=left|right|both]");
			case "changeends" -> result.line("用法：train changeends <车辆id>");
			case "cab" -> result.line("用法：train cab <车辆id> <A|B|out>");
			case "shunt" -> result.line("用法：train shunt <车辆id> <目标轨hex|off> [--minutes=n] [--kmh=n] [--kind=SUBSIDIARY_SHUNT|CALLING_ON]");
			case "interlock" -> result.line("用法：train interlock <车辆id|all>");
			case "trace" -> result.line("用法：train trace [on|off|status]");
			default -> {
			}
		}
		return result;
	}
}
