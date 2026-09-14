package org.mtr.core.mmtr.command;

import org.mtr.core.data.Position;
import org.mtr.core.simulation.Simulator;

import java.util.Map;

/**
 * {@code signal …}：信号灯的增删改与绑定。
 *
 * <p>这些能力引擎里本来就有（{@code Simulator.mmtrSignalOp} / {@code mmtrSignalBindAtNode}），
 * 之前只从 {@code mmtr-signal-op} 那个专用接口暴露。这里把它们收进统一指令，好处是
 * **回复能带上核对信息**（改完之后这盏灯守哪个区间、显示什么状态），而不是一个 ok/not-ok。</p>
 */
final class MmtrSignalCommands {

	private MmtrSignalCommands() {
	}

	static MmtrCommandDispatcher.Result execute(Simulator simulator, String verb, java.util.List<String> positional, Map<String, String> options) {
		switch (verb) {
			case "set":
			case "add":
				return set(simulator, positional, options);
			case "remove":
			case "delete":
				return remove(simulator, positional);
			case "bind":
				return bind(simulator, positional, options);
			case "list":
				return list(simulator, options);
			case "why":
				return MmtrSignalWhy.explain(simulator, positional, options);
			default:
				return MmtrCommandDispatcher.usage("signal 支持 set / remove / bind / list / why");
		}
	}

	/** {@code signal set <x> <y> <z> [--angle=n] [--aspects=2|3|4]} */
	private static MmtrCommandDispatcher.Result set(Simulator simulator, java.util.List<String> positional, Map<String, String> options) {
		final Position position = coordinates(positional);
		if (position == null) {
			return MmtrCommandDispatcher.usage("signal set 需要 <x> <y> <z>");
		}
		final float angle = (float) MmtrCommandDispatcher.longOption(options, "angle", 180);
		final int aspects = MmtrCommandDispatcher.intOption(options, "aspects", 2);
		final boolean ok = simulator.mmtrSignalOp((int) position.getX(), (int) position.getY(), (int) position.getZ(), angle, aspects, "set", options.getOrDefault("target", ""));
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(ok, "signal", "set");
		result.add(key(position));
		result.line(ok
			? "已登记信号灯 " + key(position) + "（朝向 " + angle + "°，" + aspects + " 灯位）"
			: "登记失败：信号灯 " + key(position));
		return result;
	}

	/** {@code signal remove <x> <y> <z>} */
	private static MmtrCommandDispatcher.Result remove(Simulator simulator, java.util.List<String> positional) {
		final Position position = coordinates(positional);
		if (position == null) {
			return MmtrCommandDispatcher.usage("signal remove 需要 <x> <y> <z>");
		}
		final boolean ok = simulator.mmtrSignalOp((int) position.getX(), (int) position.getY(), (int) position.getZ(), 0, 2, "remove", "");
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(ok, "signal", "remove");
		result.add(key(position));
		result.line(ok ? "已移除信号灯 " + key(position) : "移除失败（该位置没有登记的信号灯）：" + key(position));
		return result;
	}

	/**
	 * {@code signal bind <x> <y> <z> --rail=<hex>[,…] | --node=<x,y,z> [--add|--remove|--clear] [--angle=n] [--aspects=n]}
	 *
	 * <h3>两种绑定：点选绑轨（新）与按节点绑（旧）</h3>
	 * <p>{@code --rail} 是"这盏灯守哪几根轨"的直接表达 —— 网页上点灯、再点轨，落到引擎就是这条指令。
	 * 可以给多个 hex（一灯多腿），也可以用 {@code --add} / {@code --remove} 在现有列表上增删一条，
	 * 或 {@code --clear} 清掉人工绑定、回到按几何推断。</p>
	 *
	 * <p>{@code --node} 保留原样（覆盖式绑定到某节点，再由朝向推断读哪条轨）：游戏里的绑定刷子走的是它。</p>
	 */
	private static MmtrCommandDispatcher.Result bind(Simulator simulator, java.util.List<String> positional, Map<String, String> options) {
		final Position position = coordinates(positional);
		if (position == null) {
			return MmtrCommandDispatcher.usage("signal bind 需要 <x> <y> <z>，并给 --rail=<轨hex>[,…] 或 --node=<x,y,z>");
		}
		final int x = (int) position.getX();
		final int y = (int) position.getY();
		final int z = (int) position.getZ();
		final String lampKey = key(position);
		final org.mtr.core.mmtr.signal.MmtrSignalRegistry.SignalEntry entry = simulator.mmtrSignals.get(x, y, z);

		// ① 点选绑定：--rail / --add / --remove / --toggle / --clear 都作用在"守轨列表"上
		//
		// 注意这个守卫必须把**每个**开关都列全：漏一个（实测漏了 --toggle），那条指令就会跳过整段，
		// 掉到最后的"用法"分支去 —— 现象是"指令明明传了参数却说需要参数"，很难从错误信息反推。
		if (options.containsKey("rail") || options.containsKey("add") || options.containsKey("remove")
			|| options.containsKey("toggle") || options.containsKey("clear")) {
			if (entry == null) {
				final MmtrCommandDispatcher.Result failure = new MmtrCommandDispatcher.Result(false, "signal", "bind");
				failure.line("这盏灯还没登记：" + lampKey + "（先 signal set 登记，或 world scan-signals 扫描世界）");
				return failure;
			}
			if (options.containsKey("clear")) {
				simulator.mmtrSignalBindRails(x, y, z, java.util.List.of());
				final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "signal", "bind");
				result.add(lampKey);
				result.line("已清掉 " + lampKey + " 的人工绑定，回到按站位与朝向推断。");
				return result;
			}
			// 从现有列表出发增删，避免"点一根就覆盖掉另一根"（一灯多腿要能一根一根点亮）
			final java.util.List<String> rails = new java.util.ArrayList<>(entry.rails);
			/*
			 * {@code --toggle}：把"加还是减"交给引擎决定。
			 *
			 * <p>为什么需要它：网页点一条轨时，界面手里的 `boundRails` 可能比画面旧一瞬
			 * （刚点完、数据刚重取），于是"点已守的轨想解绑"会被误判成"再绑一次"——
			 * 实测同一条轨越点越多。**引擎自己知道现在守什么**，让它按事实取反，
			 * 调用方就不必持有最新状态，这条交互也就不可能自相矛盾。</p>
			 */
			if (options.containsKey("toggle")) {
				final String toggleHex = options.get("toggle");
				if (!toggleHex.isEmpty() && !toggleHex.equals("true")) {
					// 规范 hex 比较：同一条轨的两种端点写法必须算同一条（见 canonicalHex）
					final String want = canonicalRailKey(toggleHex);
					final java.util.List<String> kept = new java.util.ArrayList<>();
					boolean found = false;
					for (final String hex : rails) {
						if (canonicalRailKey(hex).equals(want)) {
							found = true;
						} else {
							kept.add(hex);
						}
					}
					rails.clear();
					rails.addAll(kept);
					final boolean nowBound;
					if (found) {
						nowBound = false;
					} else {
						if (!railExists(simulator, toggleHex)) {
							final MmtrCommandDispatcher.Result failure = new MmtrCommandDispatcher.Result(false, "signal", "bind");
							failure.line("找不到这条轨：" + toggleHex + "（hex 要完整；用 query topology 或网页上点轨取）");
							return failure;
						}
						rails.add(toggleHex);
						nowBound = true;
					}
					simulator.mmtrSignalBindRails(x, y, z, rails);
					final MmtrCommandDispatcher.Result toggled = new MmtrCommandDispatcher.Result(true, "signal", "bind");
					toggled.add(lampKey);
					rails.forEach(toggled::add);
					toggled.line("信号灯 " + lampKey + (nowBound ? " 已**绑上** " : " 已**解绑** ")
						+ canonicalRailKey(toggleHex) + "，现在守 " + rails.size() + " 条轨。");
					return toggled;
				}
			}
			final String addHex = options.getOrDefault("add", options.get("rail"));
			if (addHex != null && !addHex.isEmpty()) {
				for (final String hex : addHex.split(",")) {
					final String trimmed = hex.trim();
					if (!trimmed.isEmpty() && !rails.contains(trimmed)) {
						if (!railExists(simulator, trimmed)) {
							final MmtrCommandDispatcher.Result failure = new MmtrCommandDispatcher.Result(false, "signal", "bind");
							failure.line("找不到这条轨：" + trimmed + "（hex 要完整；用 query topology 或网页上点轨取）");
							return failure;
						}
						rails.add(trimmed);
					}
				}
			}
			final String removeHex = options.get("remove");
			if (removeHex != null && !removeHex.isEmpty() && !removeHex.equals("true")) {
				for (final String hex : removeHex.split(",")) {
					rails.remove(hex.trim());
				}
			}
			simulator.mmtrSignalBindRails(x, y, z, rails);
			final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "signal", "bind");
			result.add(lampKey);
			for (final String hex : rails) {
				result.add(hex);
			}
			result.line("信号灯 " + lampKey + " 现在守 " + rails.size() + " 条轨："
				+ (rails.isEmpty() ? "（无，回到推断）" : String.join("，", rails)));
			result.line("方向不用指定：列车从灯所在的那一端进入 —— 绑哪根轨，方向就是那根轨上从你这边过去的方向。");
			return result;
		}

		// ② 旧的节点绑定
		final Position node = MmtrCommandDispatcher.positionOption(options, "node");
		if (node == null) {
			return MmtrCommandDispatcher.usage("signal bind 需要 --rail=<轨hex>[,…]（点选绑定）或 --node=<x,y,z>（按节点绑定）");
		}
		final float angle = (float) MmtrCommandDispatcher.longOption(options, "angle", 180);
		final int aspects = MmtrCommandDispatcher.intOption(options, "aspects", 2);
		final boolean ok = simulator.mmtrSignalBindAtNode(x, y, z, angle, aspects, node.getX(), node.getY(), node.getZ());
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(ok, "signal", "bind");
		result.add(lampKey);
		result.line(ok
			? "已把信号灯 " + lampKey + " 绑定到节点 " + node.getX() + "," + node.getY() + "," + node.getZ() + "（覆盖式绑定，按朝向推断它读哪条轨）"
			: "绑定失败：灯 " + lampKey + " 或节点 " + node.getX() + "," + node.getY() + "," + node.getZ());
		return result;
	}

	/** 这条轨 hex 在世界上存不存在（绑定前校验，免得把打错的 hex 存进档）。 */
	private static boolean railExists(Simulator simulator, String hex) {
		for (final org.mtr.core.data.Rail rail : simulator.rails) {
			if (rail.getHexId().equals(hex) || canonicalRailKey(rail.getHexId()).equals(canonicalRailKey(hex))) {
				return true;
			}
		}
		return false;
	}

	/** 轨 hex 的规范写法（与 {@code MmtrDirectionalBlockService.canonicalHex} 同一规则）。 */
	private static String canonicalRailKey(String hex) {
		return org.mtr.core.mmtr.signal.MmtrDirectionalBlockService.canonicalHex(hex);
	}

	/** {@code signal list [--state=红|黄|绿] [--limit=n]} */
	private static MmtrCommandDispatcher.Result list(Simulator simulator, Map<String, String> options) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "signal", "list");
		final var trees = simulator.mmtrOccupancyTrees();
		final var restricted = org.mtr.core.mmtr.signal.MmtrJunctionState.unclearedNodeKeys(simulator, trees);
		final var aspects = simulator.mmtrDirectionalBlocks.lampAspectNames(trees, restricted::contains);
		final String wanted = options.get("state");
		final int limit = MmtrCommandDispatcher.intOption(options, "limit", 200);
		int shown = 0;
		for (final var entry : simulator.mmtrSignals.signals.entrySet()) {
			final String state = aspects.getOrDefault(entry.getKey(), "");
			if (wanted != null && !state.equalsIgnoreCase(wanted)) {
				continue;
			}
			if (shown++ >= limit) {
				break;
			}
			result.add(entry.getKey());
			result.line("信号灯 " + entry.getKey()
				+ "  朝向=" + entry.getValue().angle + "°"
				+ "  状态=" + (state.isEmpty() ? "（未接入闭塞层）" : state)
				+ "  模式=" + entry.getValue().mode);
		}
		if (shown == 0) {
			result.line("（没有匹配的信号灯）");
		}
		return result;
	}

	/** 从位置参数解析坐标。 */
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

	private static String key(Position position) {
		return position.getX() + "," + position.getY() + "," + position.getZ();
	}
}
