package org.mtr.core.mmtr.command;

import org.mtr.core.data.Depot;
import org.mtr.core.data.Siding;
import org.mtr.core.simulation.Simulator;

import java.util.List;
import java.util.Map;

/**
 * {@code manifest …}：列车表（初始生成内容）的查看、热改与重放。
 *
 * <h3>设计：列车表就是一张"指令表"</h3>
 * <p>原先的列车表是一个只在启动时读一次的文件（{@code mmtr-rolling-stock.json}），改它必须重启 —
 * 这是实测撞过的墙：给新车辆段配一列车，只能"改文件 → 重启 → 让引擎重读"。而且应用它的代码
 * 与运行时的操作逻辑是两套，容易分叉。</p>
 *
 * <p>现在把两者合成一条路：**列车表的每一条，都是一条可执行的指令**。
 * 启动播种与管理员手工操作走同一个执行器（{@link MmtrCommandDispatcher}），于是：</p>
 * <ul>
 *   <li>管理者可以**把整张表再跑一遍**（{@code manifest replay}），不需要重启；</li>
 *   <li>想只补一条股道，就直接写一条 {@code vehicle spawn …} 指令；</li>
 *   <li>表的格式与指令语法一一对应，不存在"文件里那样写、控制台里这样写"的两套心智。</li>
 * </ul>
 *
 * <p>{@code manifest add} 既改内存里的表（立刻生效），也写回文件（下次启动仍在），
 * 这就是"热改"：不再需要重启。</p>
 */
final class MmtrManifestCommands {

	private MmtrManifestCommands() {
	}

	static MmtrCommandDispatcher.Result execute(Simulator simulator, String verb, List<String> positional, Map<String, String> options) {
		switch (verb) {
			case "list":
				return list(simulator);
			case "reload":
				return reload(simulator);
			case "replay":
				return replay(simulator, positional, options);
			case "add":
				return add(simulator, positional, options);
			case "remove":
				return remove(simulator, positional);
			default:
				return MmtrCommandDispatcher.usage("manifest 支持 list / reload / replay / add / remove");
		}
	}

	/** {@code manifest list}：当前列车表（每条 = 一条待执行/已执行的生成指令）。 */
	private static MmtrCommandDispatcher.Result list(Simulator simulator) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "manifest", "list");
		final var manifest = simulator.getMmtrRollingStock();
		final var commandLines = manifest.asCommandLines();
		if (commandLines.isEmpty()) {
			result.line("（列车表是空的）");
			return result;
		}
		result.line("列车表共 " + commandLines.size() + " 条生成指令：");
		for (final String line : commandLines) {
			result.line("  " + line);
		}
		return result;
	}

	/** {@code manifest reload}：从磁盘重读列车表（替代"改文件必须重启"）。 */
	private static MmtrCommandDispatcher.Result reload(Simulator simulator) {
		final boolean ok = simulator.mmtrReloadRollingStockManifest();
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(ok, "manifest", "reload");
		result.line(ok ? "已从磁盘重读列车表" : "重读失败（文件不存在或格式不对）");
		return result;
	}

	/**
	 * {@code manifest replay [--dry-run]}：把整张表当指令逐条再跑一遍。
	 *
	 * <p>这是"初始生成内容 = 指令表"的直接好处：不用重启就能把整份配置重新应用一次。</p>
	 */
	private static MmtrCommandDispatcher.Result replay(Simulator simulator, List<String> positional, Map<String, String> options) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "manifest", "replay");
		final var commandLines = simulator.getMmtrRollingStock().asCommandLines();
		if (commandLines.isEmpty()) {
			result.line("（列车表是空的，没有可重放的指令）");
			return result;
		}
		final boolean dryRun = options.containsKey("dry-run");
		int okCount = 0;
		int failCount = 0;
		for (final String command : commandLines) {
			if (dryRun) {
				result.line("  试算：" + command);
				continue;
			}
			final MmtrCommandDispatcher.Result one = MmtrCommandDispatcher.execute(simulator, command);
			if (one.ok) {
				okCount++;
				one.affected.forEach(result::add);
				result.line("  成功：" + command + (one.affected.isEmpty() ? "" : "  → " + String.join(",", one.affected)));
			} else {
				failCount++;
				result.line("  失败：" + command);
				one.lines.forEach(text -> result.line("        " + text));
			}
		}
		if (!dryRun) {
			result.line("重放完成：" + okCount + " 条成功，" + failCount + " 条失败");
		}
		return result;
	}

	/**
	 * {@code manifest add <depotId|名> <sidingId> [车型...]}：给一条股道配车并立刻生成。
	 *
	 * <p>等价于"写一条 {@code vehicle spawn} 指令进表，然后执行它"。写回文件是顺带的：
	 * 让下次启动也还在（这一点与{@code manifest reload}配合就是不重启的完整闭环）。</p>
	 */
	private static MmtrCommandDispatcher.Result add(Simulator simulator, List<String> positional, Map<String, String> options) {
		if (positional.size() < 2) {
			return MmtrCommandDispatcher.usage("manifest add 需要 <depotId|名> <sidingId> [车型...]（车型省略时沿用该股道现有模板）");
		}
		final Depot depot = MmtrCommandDispatcher.findDepot(simulator, positional.get(0));
		if (depot == null) {
			final MmtrCommandDispatcher.Result failure = new MmtrCommandDispatcher.Result(false, "manifest", "add");
			failure.line("找不到车辆段「" + positional.get(0) + "」");
			return failure;
		}
		Siding siding = null;
		try {
			siding = MmtrCommandDispatcher.findSiding(simulator, Long.parseLong(positional.get(1)));
		} catch (NumberFormatException ignored) {
			// 保持 null，下面报错
		}
		if (siding == null) {
			final MmtrCommandDispatcher.Result failure = new MmtrCommandDispatcher.Result(false, "manifest", "add");
			failure.line("找不到股道「" + positional.get(1) + "」（股道 id 是 64 位十进制，可用 query depots 查看）");
			return failure;
		}
		if (siding.area == null || siding.area.getId() != depot.getId()) {
			final MmtrCommandDispatcher.Result failure = new MmtrCommandDispatcher.Result(false, "manifest", "add");
			failure.line("股道 " + siding.getId() + " 不属于车辆段「" + depot.getName() + "」"
				+ "（它的归属是 " + (siding.area == null ? "无" : siding.area.getName()) + "）");
			return failure;
		}

		final boolean ok = simulator.mmtrManifestAddSiding(depot.getId(), siding.getId(), positional.subList(2, positional.size()));
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(ok, "manifest", "add");
		result.add(String.valueOf(siding.getId()));
		if (!ok) {
			result.line("写入列车表失败：" + depot.getName() + " / " + siding.getId());
			return result;
		}
		final String cars = positional.size() > 2 ? String.join("+", positional.subList(2, positional.size())) : "（沿用现有模板）";
		result.line("已写入列车表并生效：" + depot.getName() + " / 股道 " + siding.getId() + "  编组 " + cars);
		result.line("可直接用 query depots 核对该股道的「模板 / 在场」两项。");
		return result;
	}

	/** {@code manifest remove <depotId|名> [sidingId]}：从表里删条目（不删世界上已有的车）。 */
	private static MmtrCommandDispatcher.Result remove(Simulator simulator, List<String> positional) {
		if (positional.isEmpty()) {
			return MmtrCommandDispatcher.usage("manifest remove 需要 <depotId|名> [sidingId]");
		}
		final Depot depot = MmtrCommandDispatcher.findDepot(simulator, positional.get(0));
		if (depot == null) {
			final MmtrCommandDispatcher.Result failure = new MmtrCommandDispatcher.Result(false, "manifest", "remove");
			failure.line("找不到车辆段「" + positional.get(0) + "」");
			return failure;
		}
		final long sidingId = positional.size() > 1 ? parseLongOrZero(positional.get(1)) : 0;
		final boolean ok = simulator.mmtrManifestRemove(depot.getId(), sidingId);
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(ok, "manifest", "remove");
		result.line(ok
			? "已从列车表删除：" + depot.getName() + (sidingId == 0 ? "（整个车辆段）" : " / 股道 " + sidingId) + "（世界上的车需要另外用 vehicle remove 删）"
			: "删除失败：表里没有匹配的条目");
		return result;
	}

	private static long parseLongOrZero(String raw) {
		try {
			return Long.parseLong(raw.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

}

