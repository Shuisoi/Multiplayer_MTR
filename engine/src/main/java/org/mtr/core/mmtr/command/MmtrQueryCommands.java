package org.mtr.core.mmtr.command;

import org.mtr.core.data.Depot;
import org.mtr.core.data.Siding;
import org.mtr.core.simulation.Simulator;

import java.util.List;
import java.util.Map;

/**
 * {@code query …}：只读的状态查询，给人和脚本核对用。
 *
 * <p>每个查询都尽量给出"可以据此做下一步"的信息：车辆段查询会列出股道与上面停的车，
 * 拓扑查询会给节点/轨的规模，等等。指令系统的价值一半在"能改"，另一半在"改完能核对"。</p>
 */
final class MmtrQueryCommands {

	private MmtrQueryCommands() {
	}

	static MmtrCommandDispatcher.Result execute(Simulator simulator, String namespace, String verb, List<String> positional, Map<String, String> options) {
		if (namespace.equals("world")) {
			// world scan-signals 需要枚举已加载区块，只有游戏端能做 → 转交游戏端命令通道
			if (verb.equals("scan-signals") || (verb.equals("scan") && positional.isEmpty())) {
				simulator.mmtrPushCommand("signals scan");
				final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "world", "scan-signals");
				result.line("已把 signals scan 交给游戏端执行（只有它能枚举已加载区块里的信号灯）。");
				result.line("结果会写进指令日志，可用 query signals 查看登记后的灯。");
				return result;
			}
			// 同理：牌子是自由摆放的方块，引擎只有登记表，世界的真相只有游戏端看得见
			if (verb.equals("scan-stop-markers")) {
				simulator.mmtrPushCommand("stop-markers scan");
				final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "world", "scan-stop-markers");
				result.line("已把 stop-markers scan 交给游戏端执行（只有它能枚举已加载区块里的停车对标牌子）。");
				result.line("结果会写进指令日志，可用 stop-marker list 查看登记后的牌子。");
				return result;
			}
			return MmtrCommandDispatcher.usage("world 支持 scan-signals / scan-stop-markers");
		}

		switch (verb) {
			case "depots":
				return depots(simulator);
			case "trains":
			case "vehicles":
				return MmtrVehicleCommands.execute(simulator, "list", positional, options);
			case "signals":
				return MmtrSignalCommands.execute(simulator, "list", positional, options);
			case "points":
				return MmtrPointCommands.execute(simulator, "list", positional, options);
			case "topology":
				return topology(simulator);
			case "sections":
				return sections(simulator);
			case "totals":
				return totals(simulator);
			case "occupancy":
				return occupancy(simulator);
			case "node":
				return node(simulator, positional);
			default:
				return MmtrCommandDispatcher.usage("query 支持 depots / trains / signals / points / topology / sections / totals / occupancy / node <x,y,z>");
		}
	}

	/**
	 * {@code stop-marker …}：停车对标牌子登记表的手工出入口（设计 §5.5）。
	 *
	 * <h3>为什么引擎侧也要能增删</h3>
	 * <p>牌子的常规入口是游戏端的世界扫描（{@code world scan-stop-markers}），但它要求人站在牌子旁边、
	 * 区块已加载。而排障真正要问的只有两件事："引擎现在拿哪一块当停车基准"（{@code list}）与
	 * "这一块是错的，先摘掉"（{@code remove}）—— 这两件事不该逼人进游戏。登记表本来就在引擎手里，
	 * 所以这一组动词住在引擎侧；{@code add} 则给"补一条丢了归属的牌子、或演练一组数据"用
	 * （牌子的**位置**没法凭空产生，这里只是把坐标喂给与扫描同一条登记路径）。</p>
	 */
	static MmtrCommandDispatcher.Result executeStopMarker(Simulator simulator, String verb, List<String> positional, Map<String, String> options) {
		switch (verb) {
			case "list":
				return stopMarkerList(simulator);
			case "add":
				return stopMarkerAdd(simulator, positional, options);
			case "remove":
				return stopMarkerRemove(simulator, positional);
			default:
				return MmtrCommandDispatcher.usage("stop-marker 支持 list / add <x> <y> <z> [--cars=n] [--platform=<站台id>] [--label=牌面] [--datum=n] / remove <x> <y> <z>");
		}
	}

	/**
	 * {@code stop-marker list}：逐条报坐标 / 站台 / 辆数 / 方向 / fraction / 冲突。
	 *
	 * <p>方向只报"朝 ends[0] / 朝 ends[1]"，不报南北：这是引擎自己的口径（站台轨的两个有序端点，
	 * 字典序小的在前），而"南北"取决于站台怎么摆。报南北会让人以为方向是从世界里读出来的 ——
	 * 它其实是从牌子位置推的（{@code deriveTowardEnd0}）。</p>
	 */
	private static MmtrCommandDispatcher.Result stopMarkerList(Simulator simulator) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "stop-marker", "list");
		final java.util.TreeMap<String, Boolean> conflicts = org.mtr.core.mmtr.stopmark.MmtrStopMarkerRegistry.conflictFlags(simulator);
		int conflicting = 0;
		for (final org.mtr.core.mmtr.stopmark.MmtrStopMarkerRegistry.StopMarkerEntry entry : simulator.mmtrStopMarkers.markers.values()) {
			final String key = org.mtr.core.mmtr.stopmark.MmtrStopMarkerRegistry.key(entry.x, entry.y, entry.z);
			final boolean conflict = Boolean.TRUE.equals(conflicts.get(key));
			if (conflict) {
				conflicting++;
			}
			result.add(key);
			result.line("  牌子 (" + entry.x + "," + entry.y + "," + entry.z + ")  站台=" + platformLabel(entry)
				+ "  辆数=" + entry.cars + "  方向=朝 ends[" + (entry.towardEnd0 ? "0" : "1") + "]"
				+ "  fraction=" + roundTo4(entry.fraction) + "  datum=" + roundTo4(entry.datumM) + "m"
				+ "  车头=(" + roundTo4(entry.headX) + "," + roundTo4(entry.headY) + "," + roundTo4(entry.headZ) + ")"
				+ "  轨=" + entry.railHex
				+ (entry.label.isEmpty() ? "" : "  牌面=" + entry.label)
				+ (conflict ? "  **冲突**（同一站台同一辆数同一方向有多块牌，基准取离站台中点最近的那块）" : ""));
		}
		result.line("共 " + simulator.mmtrStopMarkers.markers.size() + " 块牌子，其中冲突 " + conflicting + " 块。");
		if (simulator.mmtrStopMarkers.markers.isEmpty()) {
			result.line("世界里还没扫到牌子：先在站台上立牌、拨好辆数，再 `world scan-stop-markers`。");
		}
		return result;
	}

	/**
	 * {@code stop-marker add <x> <y> <z> [--cars=n] [--platform=<id>] [--label=…] [--datum=n]}。
	 *
	 * <p>走与扫描**同一条**登记路径（{@code Simulator.mmtrStopMarkerRegister}）：归属解析、方向推导、
	 * 投影、冲突判定全都只有一份实现，手工加一条与世界里扫一条不会有任何差别。</p>
	 */
	private static MmtrCommandDispatcher.Result stopMarkerAdd(Simulator simulator, List<String> positional, Map<String, String> options) {
		final int[] xyz = parseXyz(positional);
		if (xyz == null) {
			return MmtrCommandDispatcher.usage("stop-marker add <x> <y> <z> [--cars=n] [--platform=<站台id>] [--label=牌面] [--datum=n]");
		}
		final String key = org.mtr.core.mmtr.stopmark.MmtrStopMarkerRegistry.key(xyz[0], xyz[1], xyz[2]);
		final boolean changed = simulator.mmtrStopMarkerRegister(
			xyz[0], xyz[1], xyz[2],
			MmtrCommandDispatcher.intOption(options, "cars", 0),
			MmtrCommandDispatcher.longOption(options, "platform", 0),
			options.getOrDefault("label", ""),
			doubleOption(options, "datum", 1.25)
		);
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(changed, "stop-marker", "add");
		result.add(key);
		final org.mtr.core.mmtr.stopmark.MmtrStopMarkerRegistry.StopMarkerEntry entry = simulator.mmtrStopMarkers.get(xyz[0], xyz[1], xyz[2]);
		if (changed && entry != null) {
			result.line("已登记 " + key + "  站台=" + platformLabel(entry) + "  辆数=" + entry.cars
				+ "  方向=朝 ends[" + (entry.towardEnd0 ? "0" : "1") + "]  fraction=" + roundTo4(entry.fraction));
		} else if (entry != null) {
			result.line(key + " 已经在登记表里，内容与这次给的完全一样（没有改动）。");
		} else {
			result.line("没有登记 " + key + "：可能是辆数不在 2..10、找不到站台（--platform 写错，或牌子离站台超过容差）、");
			result.line("站台还没铺图轨、或车头停点落在股道之外（牌子离股道端头太近）。");
			result.line("用 `stop-marker list` 与 `query platforms` 对一遍归属，或把牌子往股道中间挪一格。");
		}
		return result;
	}

	/** {@code stop-marker remove <x> <y> <z>}：只删登记，不动世界里的方块（方块由游戏端扫描负责）。 */
	private static MmtrCommandDispatcher.Result stopMarkerRemove(Simulator simulator, List<String> positional) {
		final int[] xyz = parseXyz(positional);
		if (xyz == null) {
			return MmtrCommandDispatcher.usage("stop-marker remove <x> <y> <z>");
		}
		final String key = org.mtr.core.mmtr.stopmark.MmtrStopMarkerRegistry.key(xyz[0], xyz[1], xyz[2]);
		final boolean removed = simulator.mmtrStopMarkerRemove(xyz[0], xyz[1], xyz[2]);
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(removed, "stop-marker", "remove");
		result.add(key);
		if (removed) {
			result.line("已删掉 " + key + " 的牌子登记（世界里的方块没动；它若还在，下一次扫描会把它重新登记回来）。");
		} else {
			result.line(key + " 本来就不在登记表里。");
		}
		return result;
	}

	/** 站台怎么写给人看：有 hex 用 hex（与只读出口同一写法），否则退回 id。 */
	private static String platformLabel(org.mtr.core.mmtr.stopmark.MmtrStopMarkerRegistry.StopMarkerEntry entry) {
		return entry.platformHex.isEmpty() ? String.valueOf(entry.platformId) : entry.platformHex;
	}

	private static double roundTo4(double value) {
		return Math.round(value * 10000) / 10000.0;
	}

	/** {@code --key=} 的浮点值；没给或解析不出来时用 fallback（照 {@code intOption} 的口径）。 */
	private static double doubleOption(Map<String, String> options, String key, double fallback) {
		final String raw = options.get(key);
		if (raw == null || raw.isEmpty()) {
			return fallback;
		}
		try {
			return Double.parseDouble(raw.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/**
	 * {@code query node <x>,<y>,<z>}：一个节点在**引擎眼里**的样子 —— 它的邻接轨、每条轨的有序端点、长度。
	 *
	 * <h3>为什么要有它</h3>
	 * <p>"某条腿为什么不在灯的选择范围里"这个问题，答案永远在 {@code positionsToRail} 里，
	 * 而它以前没有任何出口：只能从 {@code signal why} 的选腿日志反推（那里只列灯附近 6 格的轨），
	 * 或者拿 hex 去手工反解坐标（实测反解错两次，把两根轨认成一根，之后每一句日志都是假的）。
	 * 直接把节点的邻接表打出来，"这条轨挂在哪个节点上"就是一个字符串比较。</p>
	 *
	 * <p>实测锚点 {@code -70,-59,-139}（进库口那盏"游戏里红、web 上绿"的灯）：车停在
	 * {@code (-67,-139)→(-67,-103)} 上，占用树里也有它的足迹，可节点 {@code (-67,-139)} 的度为 3 ——
	 * 那条轨挂在了**另一个**节点上，于是灯的腿表里根本没有它。</p>
	 */
	private static MmtrCommandDispatcher.Result node(Simulator simulator, List<String> positional) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "query", "node");
		if (positional.isEmpty()) {
			return MmtrCommandDispatcher.usage("query node <x>,<y>,<z>（也接受三个分开的数）");
		}
		final int[] xyz = parseXyz(positional);
		if (xyz == null) {
			return MmtrCommandDispatcher.usage("query node <x>,<y>,<z>：坐标解析失败");
		}
		/*
		 * 先把**附近所有节点**列出来（含 y），再对最近的那个展开。
		 *
		 * <p>只认 x/z、把 y 当无关紧要的那一维，是本项目踩过的坑：{@code query node -67,-60,-139}
		 * 与实际让灯选腿的节点可能不是同一个对象（同一列上有 y=-60 与 y=-59 两个登记），
		 * 而 {@code positionsToRail} 是按 Position（含 y）索引的 —— 于是"直读说有 4 条邻轨、
		 * 灯那里只有 3 条"这种现象就会出现。把 y 一起列出来，这个分叉立刻可见。</p>
		 */
		result.line("附近节点（|dx|,|dz| ≤ 2，含 y）：");
		for (final org.mtr.core.data.Position candidate : simulator.positionsToRail.keySet()) {
			if (Math.abs(candidate.getX() - xyz[0]) <= 2 && Math.abs(candidate.getZ() - xyz[2]) <= 2) {
				final var neighbourMap = simulator.positionsToRail.get(candidate);
				result.line("  (" + candidate.getX() + "," + candidate.getY() + "," + candidate.getZ() + ")  度="
					+ (neighbourMap == null ? 0 : neighbourMap.size()));
			}
		}
		org.mtr.core.data.Position found = null;
		for (final org.mtr.core.data.Position candidate : simulator.positionsToRail.keySet()) {
			if (candidate.getX() == xyz[0] && candidate.getZ() == xyz[2]) {
				found = candidate;
				if (candidate.getY() == xyz[1]) {
					break;
				}
			}
		}
		if (found == null) {
			result.line("positionsToRail 里没有 x=" + xyz[0] + " z=" + xyz[2] + " 的节点。");
			result.line("（邻域内的节点：）");
			for (final org.mtr.core.data.Position candidate : simulator.positionsToRail.keySet()) {
				if (Math.abs(candidate.getX() - xyz[0]) <= 2 && Math.abs(candidate.getZ() - xyz[2]) <= 2) {
					result.line("  (" + candidate.getX() + "," + candidate.getY() + "," + candidate.getZ() + ")");
				}
			}
			return result;
		}
		final var neighbours = simulator.positionsToRail.get(found);
		result.line("节点 (" + found.getX() + "," + found.getY() + "," + found.getZ() + ")  度=" + neighbours.size());
		neighbours.forEach((other, rail) -> {
			final org.mtr.core.data.Position[] ordered = rail.mmtrOrderedPositions();
			result.line("  另一端点 (" + other.getX() + "," + other.getY() + "," + other.getZ() + ")"
				+ "  轨 (" + ordered[0].getX() + "," + ordered[0].getY() + "," + ordered[0].getZ() + ")→("
				+ ordered[1].getX() + "," + ordered[1].getY() + "," + ordered[1].getZ() + ")"
				+ "  长=" + Math.round(rail.railMath.getLength()) + " m");
		});
		return result;
	}

	/** {@code <x>,<y>,<z>} 或三个分开的数 → 三个整数。 */
	private static int @org.jspecify.annotations.Nullable [] parseXyz(List<String> positional) {
		try {
			if (positional.size() == 1) {
				final String[] parts = positional.get(0).split(",");
				if (parts.length < 3) {
					return null;
				}
				return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())};
			}
			if (positional.size() >= 3) {
				return new int[]{Integer.parseInt(positional.get(0)), Integer.parseInt(positional.get(1)), Integer.parseInt(positional.get(2))};
			}
		} catch (NumberFormatException ignored) {
			// 落到 null
		}
		return null;
	}

	/**
	 * {@code query occupancy}：把**占用树里真正记着的轨**列出来（哪根轨、哪辆车、占了哪段弧）。
	 *
	 * <h3>为什么需要它</h3>
	 * <p>"灯为什么是红的/绿的"最后一步永远是"这根轨上有没有车"。而占用是按<b>端点对</b>两级查的
	 * （{@code Data.tryGet(tree, ordered[0], ordered[1])}），所以"车在轨 A 上"与"区间读到车在轨 B 上"
	 * 完全可能同时成立 —— 两根轨在世界里重叠、或被声明成不同的 Rail 对象时就是这样。</p>
	 *
	 * <p>实测那次（锚点 {@code -70,-59,-139}）：车在 {@code (-66.5,-128.5)}，灯守的 stub
	 * 也覆盖那个坐标，可区间就是读不到占用。没有这张表，只能靠"车到底挂在哪根轨"反推。
	 * 把树按轨列出来，"车挂在哪根轨"就是一个字符串比较。</p>
	 */
	private static MmtrCommandDispatcher.Result occupancy(Simulator simulator) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "query", "occupancy");
		final var trees = simulator.mmtrOccupancyTrees();
		if (trees == null || trees.isEmpty()) {
			result.line("占用树还没有（模拟器尚未 sync，或没有任何车辆位置）。");
			return result;
		}
		final java.util.TreeSet<String> lines = new java.util.TreeSet<>();
		int entries = 0;
		for (int i = 0; i < trees.size(); i++) {
			for (final var outer : trees.get(i).entrySet()) {
				for (final var inner : outer.getValue().entrySet()) {
					final var position = inner.getValue();
					if (position == null) {
						continue;
					}
					entries++;
					final String owner = position.footprintIds().isEmpty() ? "?" : position.footprintIds().toString();
					lines.add(String.format("  (%d,%d)→(%d,%d)  车=%s  足迹=%s",
						outer.getKey().getX(), outer.getKey().getZ(),
						inner.getKey().getX(), inner.getKey().getZ(),
						owner, footprintArcs(position)));
				}
			}
		}
		result.line("占用树 " + trees.size() + " 层，共 " + entries + " 条轨上有足迹：");
		for (final String line : lines) {
			result.line(line);
		}

		/*
		 * 关键的一步：**反着对一遍** —— 拿引擎手里的每条轨去树里取，看取不取得到。
		 *
		 * <p>只列树、不列引擎的视角，就会漏掉真正的那类故障：树里有足迹、引擎也有这根轨，
		 * 但两边的**端点坐标不是同一对**（轨的声明端点与车辆写入时用的端点可以来自不同的数据源），
		 * 于是取值永远为空，现象与"这根轨上没车"完全一样。</p>
		 */
		result.line("");
		result.line("反向核对（引擎的轨 → 树里取到的车）：");
		int matched = 0;
		for (final org.mtr.core.data.Rail rail : simulator.rails) {
			final org.mtr.core.data.Position[] ordered = rail.mmtrOrderedPositions();
			if (ordered == null || ordered.length < 2) {
				continue;
			}
			for (int i = 0; i < trees.size(); i++) {
				final org.mtr.core.data.VehiclePosition position =
					org.mtr.core.mmtr.signal.MmtrSectionService.footprintOn(trees.get(i), ordered);
				if (position != null) {
					matched++;
					result.line("  (" + ordered[0].getX() + "," + ordered[0].getY() + "," + ordered[0].getZ() + ")→("
						+ ordered[1].getX() + "," + ordered[1].getY() + "," + ordered[1].getZ() + ")  车=" + position.footprintIds()
						+ "  足迹=" + footprintArcs(position));
					break;
				}
			}
		}
		result.line("  引擎有 " + simulator.rails.size() + " 条轨，其中在树里取到车的 " + matched + " 条。");
		if (matched < entries) {
			result.line("  **注意**：树里有足迹的轨比这里取到的多 —— 说明某些轨的端点坐标与树键对不上。");
		}

		/*
		 * 那些"引擎有、但**没被任何节点登记**"的轨：区间走行只能沿 {@code positionsToRail} 走，
		 * 所以这种轨上的车**任何一盏灯都读不到** —— 灯于是永远绿。
		 *
		 * <p>实测锚点 {@code -70,-59,-139}（进库口那盏"游戏里红、web 上绿"的灯）就是这一类：
		 * 车停在 {@code (-67,-139)→(-67,-103)} 上，占用树里明明有它的足迹，可这条腿根本不在
		 * 节点 {@code (-67,-139)} 的邻接表里，于是灯的腿表里只有环线与主线，永远看不到那辆车。</p>
		 */
		result.line("");
		result.line("**未被任何节点登记的轨**（不在 positionsToRail 里 → 区间走行永远走不到它）：");
		int orphan = 0;
		for (final org.mtr.core.data.Rail rail : simulator.rails) {
			boolean registered = false;
			for (final var neighbours : simulator.positionsToRail.values()) {
				if (neighbours.containsValue(rail)) {
					registered = true;
					break;
				}
			}
			if (registered) {
				continue;
			}
			orphan++;
			final org.mtr.core.data.Position[] ordered = rail.mmtrOrderedPositions();
			result.line("  (" + ordered[0].getX() + "," + ordered[0].getY() + "," + ordered[0].getZ() + ")→("
				+ ordered[1].getX() + "," + ordered[1].getY() + "," + ordered[1].getZ() + ")  长="
				+ Math.round(rail.railMath.getLength()) + " m");
		}
		result.line("  共 " + orphan + " 条。");
		return result;
	}

	/** 一条足迹占的弧段，形如 {@code [1]8.0..24.0}（多段就并列）。 */
	private static String footprintArcs(org.mtr.core.data.VehiclePosition position) {
		final StringBuilder out = new StringBuilder();
		for (final double[] segment : position.segmentsExcluding(0)) {
			if (out.length() > 0) {
				out.append(' ');
			}
			out.append('[').append(Math.round(segment[0])).append("..").append(Math.round(segment[1])).append(']');
		}
		return out.length() == 0 ? "（无段）" : out.toString();
	}

	/** {@code query depots}：车辆段 → 股道 → 车。 */
	private static MmtrCommandDispatcher.Result depots(Simulator simulator) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "query", "depots");
		for (final String line : describeDepots(simulator, true)) {
			result.line(line);
		}
		return result;
	}

	/**
	 * 车辆段清单（供 query 与"找不到股道"时的提示复用）。
	 *
	 * @param withCars 是否列出每条股道上停的车
	 */
	static List<String> describeDepots(Simulator simulator, boolean withCars) {
		final java.util.ArrayList<String> lines = new java.util.ArrayList<>();
		if (simulator.depots.isEmpty()) {
			lines.add("（没有车辆段）");
			return lines;
		}
		for (final Depot depot : simulator.depots) {
			final List<Siding> sidings = MmtrVehicleCommands.sortedSidings(depot);
			lines.add("车辆段 " + depot.getName() + "  id=" + depot.getId() + "  股道 " + sidings.size() + " 条");
			int index = 0;
			for (final Siding siding : sidings) {
				index++;
				String carText = "";
				if (withCars) {
					// "模板"= 这条股道配了什么车（列车表里的条目）；"在场"= 现在真的站在上面的车。
					// 两者不一致时一眼就能看出"配了但没生成成功"，这正是排查生成问题时最需要的信息。
					final java.util.ArrayList<String> template = new java.util.ArrayList<>();
					for (final var car : siding.getVehicleCars()) {
						template.add(car.getVehicleId());
					}
					final List<String> present = simulator.mmtrCarsOnSiding(siding.getId());
					carText = "  模板=[" + String.join("+", template) + "]  在场=[" + String.join("+", present) + "]";
				}
				lines.add("    第 " + index + " 条  股道 id=" + siding.getId()
					+ "  长 " + Math.round(siding.getRailLength()) + " m" + carText);
			}
		}
		return lines;
	}

	/** {@code query topology}：节点/轨规模与坐标范围。 */
	private static MmtrCommandDispatcher.Result topology(Simulator simulator) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "query", "topology");
		long minX = Long.MAX_VALUE;
		long maxX = Long.MIN_VALUE;
		long minZ = Long.MAX_VALUE;
		long maxZ = Long.MIN_VALUE;
		int nodes = 0;
		for (final var node : simulator.positionsToRail.keySet()) {
			nodes++;
			minX = Math.min(minX, node.getX());
			maxX = Math.max(maxX, node.getX());
			minZ = Math.min(minZ, node.getZ());
			maxZ = Math.max(maxZ, node.getZ());
		}
		result.line("节点 " + nodes + " 个，轨 " + simulator.rails.size() + " 条");
		if (nodes > 0) {
			result.line("范围：x " + minX + ".." + maxX + "，z " + minZ + ".." + maxZ);
		}
		result.line("车辆段 " + simulator.depots.size() + " 个，股道 " + simulator.sidings.size() + " 条，站台 " + simulator.platforms.size() + " 个");
		return result;
	}

	/** {@code query sections}：闭塞区间规模与占用概况（按方向划分，见 notes/156）。 */
	private static MmtrCommandDispatcher.Result sections(Simulator simulator) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "query", "sections");
		final var trees = simulator.mmtrOccupancyTrees();
		final var views = simulator.mmtrSections.sectionViews(trees, ignored -> false);
		int occupied = 0;
		final it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<String> byDirection = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<>();
		for (final var view : views) {
			if (view.occupied) {
				occupied++;
			}
			byDirection.addTo(view.direction.label(), 1);
		}
		result.line("闭塞区间 " + views.size() + " 个（按方向：可能有同一根轨两个方向各一条），其中被占用 " + occupied + " 个");
		for (final var entry : byDirection.object2IntEntrySet()) {
			result.line("  " + entry.getKey() + "：" + entry.getIntValue() + " 个");
		}
		result.line("信号灯 " + simulator.mmtrSignals.signals.size() + " 个");
		return result;
	}

	/**
	 * {@code query totals}：**总区间**的规模与概况（notes/168）。
	 *
	 * <p>总区间 = 地图上"一条带"：几何就是 L1 轨道区间（切点只由灯产生 ⇒ 再想变粗就得放弃某个方向的
	 * 灯当界），多出来的是**归属** —— 这一处由哪几个方向的哪几段覆盖。所以这里报三件事：</p>
	 * <ul>
	 *   <li>**条数必须与 {@code query tracks} 的条数相等**（一一对应；不等就说明有人又按"上下行都照到"
	 *       重划了一遍）；</li>
	 *   <li>方向数分布（1 = 只有单侧有灯或另一侧是无灯大区间）；</li>
	 *   <li>**错开**多少处（上下行都照到、但不是同一段路）—— 那正是"车既在上行也在下行"的地方。</li>
	 * </ul>
	 */
	private static MmtrCommandDispatcher.Result totals(Simulator simulator) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "query", "totals");
		final var trees = simulator.mmtrOccupancyTrees();
		final var restricted = org.mtr.core.mmtr.signal.MmtrJunctionState.unclearedNodeKeys(simulator, trees);
		final var totals = simulator.mmtrSections.totalSectionViews(trees, restricted::contains);
		int occupied = 0;
		int staggered = 0;
		int unsignalled = 0;
		// 按方向数排序输出（不是 hash 顺序）：这条是给人读的，两次跑出不同顺序会让人以为模型在变
		final java.util.TreeMap<String, Integer> byDirections = new java.util.TreeMap<>();
		for (final var total : totals) {
			if (total.occupied) {
				occupied++;
			}
			if (total.staggered()) {
				staggered++;
			}
			if (total.covers.isEmpty() || total.directionCount() == 0) {
				unsignalled++;
			}
			byDirections.merge(String.valueOf(total.directionCount()), 1, Integer::sum);
		}
		result.line("总区间 " + totals.size() + " 条（几何 = L1 轨道区间，条数应与 `query tracks` 相等），其中被占用 " + occupied + " 条");
		result.line("  错开 " + staggered + " 条（上下行都照到、但不是同一段路 —— 车既在上行也在下行的那一段）");
		byDirections.forEach((directions, count) -> result.line("  覆盖它的方向数 = " + directions + "：" + count + " 条"));
		if (unsignalled > 0) {
			result.line("  其中 " + unsignalled + " 条一条 cover 都没有（连无灯大区间也没覆盖到 —— 该查走行）");
		}
		return result;
	}
}

