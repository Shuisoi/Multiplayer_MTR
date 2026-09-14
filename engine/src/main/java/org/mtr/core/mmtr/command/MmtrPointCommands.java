package org.mtr.core.mmtr.command;

import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Position;
import org.mtr.core.mmtr.point.MmtrTurnout;
import org.mtr.core.simulation.Simulator;

import java.util.Map;

/**
 * {@code point …}：道岔（转辙器）的设定、锁闭、释放。
 *
 * <p>引擎里的道岔能力（{@code mmtrSetPoint} / {@code mmtrPointLock} / {@code mmtrPointUnlock} /
 * {@code mmtrPointRelease}）此前只从 {@code mmtr-point-op} 那组专用接口暴露，
 * 这里收进统一指令，并把"这条道岔现在在哪一位、谁持有"一并回给调用方。</p>
 */
final class MmtrPointCommands {

	private MmtrPointCommands() {
	}

	static MmtrCommandDispatcher.Result execute(Simulator simulator, String verb, java.util.List<String> positional, Map<String, String> options) {
		switch (verb) {
			case "set":
				return set(simulator, positional, options);
			case "lock":
			case "unlock":
				return lock(simulator, verb, positional, options);
			case "release":
				return release(simulator, positional, options);
			case "list":
				return list(simulator);
			case "why":
				return why(simulator, positional);
			default:
				return MmtrCommandDispatcher.usage("point 支持 set / lock / unlock / release / list / why");
		}
	}

	/**
	 * {@code point why <x> <y> <z>}：这个节点的**道岔模型是怎么定出来的**（或者为什么定不出来），
	 * 顺带列出"每个进向那一行现在写给第几条腿"和"闭塞把这一处算在哪个区间"。
	 *
	 * <p>用户 2026-09-14 的现场问题（"-170,-60,-199 怎么和 -67,-60,-139 显示得不一样"）在这之前
	 * 只能从 {@code mmtr-points} 那一串 JSON 反推 —— 缺 {@code position}/{@code stem} 字段就说明没有模型。
	 * 现在把判定过程本身做成一条可读指令：它与 {@code MmtrTurnout.resolve} 走**同一段代码**，
	 * 所以这份解释不会和真正生效的模型走偏。</p>
	 */
	private static MmtrCommandDispatcher.Result why(Simulator simulator, java.util.List<String> positional) {
		final Position node = coordinates(positional);
		if (node == null) {
			return MmtrCommandDispatcher.usage("point why 需要 <x> <y> <z>");
		}
		final long x = node.getX();
		final long y = node.getY();
		final long z = node.getZ();
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "point", "why");
		result.add(x + "," + y + "," + z);

		result.line("—— 道岔模型（节点 " + x + "," + y + "," + z + "）——");
		appendLines(result, MmtrTurnout.describeResolution(node, simulator.positionsToRail.get(node)));

		final MmtrTurnout turnout = simulator.mmtrTurnout(x, y, z);
		final int position = simulator.mmtrTurnoutPosition(x, y, z);
		final String holder = simulator.mmtrPointAuthority.physicalHolder(x, y, z);
		result.line("—— 当前状态 ——");
		result.line("物理位置 = " + position + (position == MmtrTurnout.REVERSE ? "（岔股开放）" : "（正线贯通）")
			+ (turnout == null ? "（注意：这个节点没有道岔模型，位置读的是旧的行视图/节点值）" : "")
			+ "，物理持有者 = " + (holder == null ? "（没有）" : holder)
			+ "，等待队列 = " + simulator.mmtrPointAuthority.physicalQueueSnapshot(x, y, z));
		appendRows(result, simulator, turnout, x, y, z);

		result.line("—— 闭塞归属（这个节点被算在哪条区间里）——");
		appendLines(result, new org.mtr.core.mmtr.signal.MmtrDirectionalBlockService(simulator).describeNodeResolution(node));
		return result;
	}

	/** 把节点上的每一行（进向 → 腿号）列出来，并在有模型时翻译成"这表示道岔在哪一位"。 */
	private static void appendRows(MmtrCommandDispatcher.Result result, Simulator simulator,
		@Nullable MmtrTurnout turnout, long x, long y, long z) {
		final String prefix = x + "," + y + "," + z + "|";
		int shown = 0;
		for (final java.util.Map.Entry<String, Integer> entry : simulator.mmtrPointBranches.branches.entrySet()) {
			if (!entry.getKey().startsWith(prefix)) {
				continue;
			}
			shown++;
			final String via = entry.getKey().substring(prefix.length());
			result.line("  行 " + shortHex(via) + " = 腿 " + entry.getValue() + (turnout == null ? "" : " " + legMeaning(turnout, via, entry.getValue())));
		}
		if (shown == 0) {
			result.line("  （这个节点一行都没有 —— 没有道岔模型时网页只能画 legacy 卡片，就是这个原因）");
		}
	}

	/** 某个进向上的第 {@code leg} 条腿，在这个道岔模型里表示什么（含"任何位置都不相连"这一种）。 */
	private static String legMeaning(MmtrTurnout turnout, String via, int leg) {
		final int position = turnout.positionForLeg(via, leg);
		if (position == Integer.MIN_VALUE) {
			return "（这根轨在任何位置都不与它相连 = 禁止通行）";
		}
		return position == MmtrTurnout.REVERSE ? "（位置 1 = 岔股开放）" : "（位置 0 = 正线贯通）";
	}

	private static void appendLines(MmtrCommandDispatcher.Result result, String text) {
		for (final String line : text.split("\n")) {
			if (!line.isEmpty()) {
				result.line(line);
			}
		}
	}

	/** {@code point set <x> <y> <z> --via=<轨hex> --branch=<n>} */
	private static MmtrCommandDispatcher.Result set(Simulator simulator, java.util.List<String> positional, Map<String, String> options) {
		final Position node = coordinates(positional);
		final String via = options.get("via");
		if (node == null || via == null || via.isEmpty()) {
			return MmtrCommandDispatcher.usage("point set 需要 <x> <y> <z> --via=<轨hex> --branch=<n>");
		}
		final int branch = MmtrCommandDispatcher.intOption(options, "branch", 0);
		final boolean ok = simulator.mmtrSetPoint(node.getX(), node.getY(), node.getZ(), via, branch);
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(ok, "point", "set");
		result.add(key(node, via));
		result.line(ok
			? "道岔已设定：节点 " + node.getX() + "," + node.getY() + "," + node.getZ() + " 经轨 " + shortHex(via) + " → 第 " + branch + " 条腿"
			: failureText(simulator, node, via, branch));
		return result;
	}

	/**
	 * 失败时说清**是哪种失败**（用户 2026-09-13 现场问的）。
	 *
	 * <p>最容易踩的是"两根轨在任何位置都不相连"：单开道岔只有两个位置，从岔股那一头**开不到**正线远端
	 * （背向穿过尖轨）。接口按"第几条腿"给的是**几何腿号**，"从岔股看正线远端"也是一条腿，
	 * 所以很容易被当成一条真实进路。这里把它点名出来，而不是笼统说"节点/轨不匹配"。</p>
	 */
	private static String failureText(Simulator simulator, Position node, String via, int branch) {
		final org.mtr.core.mmtr.point.MmtrTurnout turnout = simulator.mmtrTurnout(node.getX(), node.getY(), node.getZ());
		final String where = node.getX() + "," + node.getY() + "," + node.getZ() + " / " + shortHex(via);
		if (turnout == null) {
			return "设定失败（这个节点不是单开道岔，或者盆轨对不上）：" + where;
		}
		final boolean viaBranch = via.equals(turnout.branchRailHex);
		final boolean viaFar = via.equals(turnout.farRailHex);
		final String viaName = viaBranch ? "岔股" : viaFar ? "正线远端" : "根部";
		final String wantName = branch == turnout.stemLeg.getOrDefault(via, Integer.MIN_VALUE) ? "根部"
			: branch == turnout.farLeg.getOrDefault(via, Integer.MIN_VALUE) ? "正线远端"
			: branch == turnout.branchLeg.getOrDefault(via, Integer.MIN_VALUE) ? "岔股" : "第 " + branch + " 条腿";
		return "设定失败：从" + viaName + "去" + wantName + "这两条进路**互斥**（道岔只有位置 0/1），"
			+ "物理上不存在这个组合 —— 该节点 " + where;
	}

	/** {@code point lock|unlock <x> <y> <z> --via=<轨hex>} */
	private static MmtrCommandDispatcher.Result lock(Simulator simulator, String verb, java.util.List<String> positional, Map<String, String> options) {
		final Position node = coordinates(positional);
		final String via = options.get("via");
		if (node == null || via == null || via.isEmpty()) {
			return MmtrCommandDispatcher.usage("point " + verb + " 需要 <x> <y> <z> --via=<轨hex>");
		}
		if (verb.equals("lock")) {
			simulator.mmtrPointLock(node.getX(), node.getY(), node.getZ(), via);
		} else {
			simulator.mmtrPointUnlock(node.getX(), node.getY(), node.getZ(), via);
		}
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "point", verb);
		result.add(key(node, via));
		result.line((verb.equals("lock") ? "已锁闭" : "已解锁") + "：节点 " + node.getX() + "," + node.getY() + "," + node.getZ() + " / 轨 " + shortHex(via));
		return result;
	}

	/** {@code point release <x> <y> <z> --via=<轨hex> [--owner=<名字>] | point release --all --owner=<名字>} */
	private static MmtrCommandDispatcher.Result release(Simulator simulator, java.util.List<String> positional, Map<String, String> options) {
		final String owner = options.getOrDefault("owner", "");
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "point", "release");
		if (options.containsKey("all")) {
			simulator.mmtrPointReleaseAll(owner);
			result.line("已释放「" + owner + "」持有的全部道岔申请");
			return result;
		}
		final Position node = coordinates(positional);
		final String via = options.get("via");
		if (node == null || via == null || via.isEmpty()) {
			return MmtrCommandDispatcher.usage("point release 需要 <x> <y> <z> --via=<轨hex>，或 --all --owner=<名字>");
		}
		simulator.mmtrPointRelease(node.getX(), node.getY(), node.getZ(), via, owner);
		result.add(key(node, via));
		result.line("已释放：节点 " + node.getX() + "," + node.getY() + "," + node.getZ() + " / 轨 " + shortHex(via));
		return result;
	}

	/** {@code point list}：当前登记的道岔操作（节点 + 经轨 → 腿）。 */
	private static MmtrCommandDispatcher.Result list(Simulator simulator) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "point", "list");
		int shown = 0;
		for (final var entry : simulator.mmtrPointBranches.branches.entrySet()) {
			shown++;
			result.add(entry.getKey());
			result.line(entry.getKey() + " → 腿 " + entry.getValue());
		}
		if (shown == 0) {
			result.line("（没有人工设定的道岔；自动检测仍在生效）");
		}
		return result;
	}

	private static Position coordinates(java.util.List<String> positional) {
		if (positional.size() < 3) {
			return null;
		}
		try {
			return new Position(Long.parseLong(positional.get(0)), Long.parseLong(positional.get(1)), Long.parseLong(positional.get(2)));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static String key(Position node, String via) {
		return node.getX() + "," + node.getY() + "," + node.getZ() + "|" + shortHex(via);
	}

	private static String shortHex(String hex) {
		return hex == null || hex.length() <= 12 ? String.valueOf(hex) : hex.substring(0, 12) + "…";
	}
}
