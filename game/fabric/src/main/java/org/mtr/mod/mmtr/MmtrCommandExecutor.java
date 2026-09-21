package org.mtr.mod.mmtr;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import org.mtr.core.Main;
import org.mtr.core.mmtr.signal.MmtrSignalRegistry.SignalEntry;
import org.mtr.core.simulation.Simulator;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.World;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockSignalBase;

/**
 * MMTR game-side command executor (指令执行器): polls the engine command queue pushed from the
 * web console (指令栏) once per server tick and runs them against the real world, e.g.
 * {@code signals scan} - register every placed MTR signal light in currently loaded chunks as an
 * AUTO wayside entry. Covered binds (BOUND) are never touched by the scan.
 */
public final class MmtrCommandExecutor {

	private MmtrCommandExecutor() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(MmtrCommandExecutor::tick);
	}

	private static void tick(MinecraftServer minecraftServer) {
		final Main main = Init.getMain();
		if (main == null) {
			return;
		}
		for (final ServerWorld serverWorld : minecraftServer.getWorlds()) {
			final Simulator simulator = main.getSimulator(Init.getWorldId(new World(serverWorld)));
			if (simulator == null) {
				continue;
			}
			/*
			 * 服务端运维（一键重启 / 停机）：引擎只会挂一个"该停机了"的信号，真正的停机必须在这里做 ——
			 * 只有游戏端能走**优雅停机**（存档、断开连接、通知客户端）。用 stop(false) 而不是 System.exit，
			 * 差别就是"存档完整落盘"还是"进程被杀"。
			 *
			 * 重启由启动器接力：引擎在请求重启时写了 mmtr-restart.request，dev-server.ps1 看到它就会再拉一次。
			 */
			if (simulator.mmtrShutdownDue()) {
				System.out.println("[MMTR-SRV] 收到停机请求，优雅关闭服务端（重启标记如存在则由启动器接力拉起）");
				simulator.mmtrCommandResult("[server] 正在优雅停机…");
				minecraftServer.stop(false);
				return;
			}
			final String command = simulator.mmtrPollCommand();
			if (command != null && !command.isEmpty()) {
				execute(simulator, serverWorld, command);
			}
		}
	}

	private static void execute(Simulator simulator, ServerWorld serverWorld, String command) {
		if (command.equals("signals scan")) {
			scanSignals(simulator, serverWorld);
			return;
		}
		// B7.6 crew commands: changeends <vehicleId> | cab <vehicleId> <A|B|out> | doors <vehicleId> [open|close|toggle]
		String[] parts = command.trim().split("\\s+");
		/*
		 * 名词打头的写法（中控指令系统的 `train doors …`）：先把名词摘掉，下面就一路按动词打头处理。
		 *
		 * 为什么在游戏端翻译而不是让引擎别加名词：游戏内控制台/旧脚本敲的一直是 `doors <id> open`，
		 * 改掉它就等于把两个入口的语法同时换一遍；翻译只有三行，却让两种写法都能用。
		 * engine 侧的 `train …` 也是这么转的，两处保持一致。
		 */
		if (parts.length >= 2 && parts[0].equals("train")) {
			final String[] shifted = new String[parts.length - 1];
			System.arraycopy(parts, 1, shifted, 0, shifted.length);
			parts = shifted;
		}
		// A2/A3/S5 interlocking report: what the engine thinks this train's route and signals are.
		if (parts.length >= 1 && parts[0].equals("interlock")) {
			executeInterlock(simulator, parts);
			return;
		}
		/*
		 * 区间层（按方向划分）。`blocks <节点键>` = 这个节点被哪些区间覆盖；`blocks` = 逐区间转储。
		 *
		 * <p>原来这两条走 `MmtrDirectionalBlockReport`（水闸区间的"唯一归属"，notes/157 已删）。
		 * 那个类的"诊断报告"职责现在由 `MmtrSectionService` 自己承担：
		 * 节点那一问用 `describeNodeSections`（**多值**，双向线路上一个节点被两个方向的区间同时覆盖），
		 * 区间那一份直接遍历 `allSections()` 打印 —— 不再需要一层只做转储的中间类。</p>
		 */
		if (parts.length >= 1 && parts[0].equals("blocks")) {
			final org.mtr.core.mmtr.signal.MmtrSectionService blockService =
				new org.mtr.core.mmtr.signal.MmtrSectionService(simulator);
			if (parts.length >= 2) {
				final String[] nodeParts = parts[1].split(",");
				if (nodeParts.length == 3) {
					try {
						simulator.mmtrCommandResult("[blocks] 节点 " + parts[1] + " 覆盖它的区间："
							+ blockService.describeNodeSections(new org.mtr.core.data.Position(
								Long.parseLong(nodeParts[0].trim()), Long.parseLong(nodeParts[1].trim()), Long.parseLong(nodeParts[2].trim()))));
					} catch (NumberFormatException ignored) {
						simulator.mmtrCommandResult("[blocks] 节点坐标无法解析，用法: blocks <x>,<y>,<z>");
					}
				} else {
					simulator.mmtrCommandResult("[blocks] 节点坐标无法解析，用法: blocks <x>,<y>,<z>");
				}
				return;
			}
			final var allSections = blockService.allSections();
			int sectionCount = 0;
			final StringBuilder report = new StringBuilder("[blocks] 有向区间（按方向划分）");
			for (final var entry : allSections.entrySet()) {
				for (final var section : entry.getValue()) {
					sectionCount++;
					report.append("\n  ").append(section.id)
						.append(" → ").append(section.exitSignalKey == null || section.exitSignalKey.isEmpty() ? "尽头" : section.exitSignalKey)
						.append(" 跨 ").append(section.spans.size()).append(" 段 长=").append(Math.round(section.lengthM() * 10) / 10.0);
				}
			}
			report.append("\n[blocks] 合计 ").append(sectionCount).append(" 个区间");
			simulator.mmtrCommandResult(report.toString());
			return;
		}
		/*
		 * Level 1 轨道区间（notes/166）：**占用判定的单位**，无方向、双向共用。
		 *
		 * 与 `blocks-v2`（Level 2 行车区间，有方向）分开两条指令，是因为两层要能分别看：
		 *   `tracks`      → 切点只由灯产生，轨上每一点恰好属于一段（占用在这里算）
		 *   `tracks <轨>` → 这根轨上被切成了几段、各段的弧窗
		 */
		if (parts.length >= 1 && parts[0].equals("tracks")) {
			final org.mtr.core.mmtr.signal.MmtrSectionService sectionService =
				new org.mtr.core.mmtr.signal.MmtrSectionService(simulator);
			if (parts.length >= 2) {
				final var onRail = sectionService.trackSectionsOf(parts[1]);
				if (onRail.isEmpty()) {
					simulator.mmtrCommandResult("[tracks] 轨 " + parts[1] + " 上没有轨道区间（这根轨不存在？）");
					return;
				}
				final StringBuilder one = new StringBuilder("[tracks] 轨 " + parts[1] + " 上有 " + onRail.size() + " 个轨道区间");
				for (final var track : onRail) {
					one.append("\n  ").append(track.id).append(" 跨 ").append(track.spans.size()).append(" 段 长=")
						.append(Math.round(track.lengthM() * 10) / 10.0).append(" ").append(track.spans);
				}
				simulator.mmtrCommandResult(one.toString());
				return;
			}
			final StringBuilder report = new StringBuilder("[tracks] 轨道区间（Level 1，无方向，占用在这一层算）");
			for (final var track : sectionService.allTrackSections()) {
				report.append("\n  ").append(track.id).append(" 跨 ").append(track.spans.size()).append(" 段 长=")
					.append(Math.round(track.lengthM() * 10) / 10.0).append(" ").append(track.spans);
			}
			report.append("\n[tracks] 合计 ").append(sectionService.trackSectionCount()).append(" 个轨道区间");
			simulator.mmtrCommandResult(report.toString());
			return;
		}
		// 某一根轨属于哪几个区间（一个点属于哪几段，**多值**）。
		if (parts.length >= 1 && parts[0].equals("blocks-v2")) {
			final org.mtr.core.mmtr.signal.MmtrSectionService blockService =
				new org.mtr.core.mmtr.signal.MmtrSectionService(simulator);
			if (parts.length >= 2 && !parts[1].equals("all")) {
				final var onRail = blockService.sectionsOfRail(parts[1]);
				if (onRail.isEmpty()) {
					simulator.mmtrCommandResult("[blocks-v2] 轨 " + parts[1] + " 不属于任何区间（这一段没有灯照到）");
					return;
				}
				final StringBuilder report = new StringBuilder("[blocks-v2] 轨 " + parts[1] + " 属于 " + onRail.size() + " 个有向区间");
				for (final var section : onRail) {
					// 本轨在这个区间里的弧窗（一个区间可能在这一根轨上出现多段，这里列出全部）
					final StringBuilder windows = new StringBuilder();
					for (final var span : section.spans) {
						if (span.railHex.equals(parts[1])) {
							if (windows.length() > 0) {
								windows.append(" / ");
							}
							windows.append("[").append(Math.round(span.arcFromM * 10) / 10.0)
								.append(", ").append(Math.round(span.arcToM * 10) / 10.0).append(")");
						}
					}
					report.append("\n  ").append(section.id)
						.append(" → ").append(section.exitSignalKey == null || section.exitSignalKey.isEmpty() ? "尽头" : section.exitSignalKey)
						.append(" 本轨弧").append(windows);
				}
				simulator.mmtrCommandResult(report.toString());
				return;
			}
			simulator.mmtrCommandResult("[blocks-v2] 用法: blocks-v2 all | blocks-v2 <轨hex>（逐区间转储请用 `blocks`）");
			return;
		}
		/*
		 * 逐灯转储：**灯的状态绑定在它开的行车区间上**（notes/167）。
		 *
		 * `lamps` 是正式名（`lamps-v2` 保留为别名）：一行一盏灯 —— 它守的轨、开的区间（一灯多腿多条）、
		 * 该区间的占用、由段状态推出的显示、以及未接入闭塞时的"未接入"。
		 * 这样"灯为什么是这个颜色"可以直接从"它开的那一段怎么样"读出来，不需要去看轨。
		 */
		if (parts.length >= 1 && (parts[0].equals("lamps") || parts[0].equals("lamps-v2"))) {
			// `var`, not an explicit ObjectArrayList: the engine jar ships its own relocated fastutil
			// (org.mtr.libraries.*), so naming the type here would clash with the game's copy.
			final var restricted = org.mtr.core.mmtr.signal.MmtrJunctionState.unclearedNodeKeys(simulator, null);
			final var bindings = simulator.mmtrSections.lampBindings(null, restricted::contains);
			final java.util.TreeMap<String, String> sorted = new java.util.TreeMap<>();
			bindings.forEach((key, binding) -> {
				final StringBuilder line = new StringBuilder();
				line.append("[lamps] ").append(key).append(" → ").append(binding.unbound ? "**未接入闭塞**" : binding.aspect);
				line.append("  占用=").append(binding.occupied);
				line.append("  守轨=").append(binding.protectedRails.size());
				if (binding.sections.isEmpty()) {
					line.append("  区间=（无）");
				} else {
					for (final var section : binding.sections) {
						line.append("  区间=").append(section.id).append("[").append(section.spans.size()).append("段/")
							.append(Math.round(section.lengthM() * 10) / 10.0).append("m]");
					}
					line.append("  后继=").append(binding.nextSectionIds(simulator.mmtrSections));
				}
				sorted.put(key, line.toString());
			});
			simulator.mmtrCommandResult(String.join("\n", sorted.values()));
			return;
		}
		/*
		 * 总区间（notes/168）：**地图上一条带** —— 一个位置 ＋ 覆盖它的各方向行车区间。
		 *
		 * 存在的理由就是"错开处"：一辆车夹在错开的一段里时，它**既在上行区间里、也在下行区间里**，
		 * 两条带并排画会重叠 —— 总区间把"位置"和"归属"分开：位置只有一条（几何 = 轨道区间），
		 * 归属逐方向列出。这里的 `**错开**` 就是那两个方向不是同一段路的情形。
		 */
		if (parts.length >= 1 && parts[0].equals("totals")) {
			final var restricted = org.mtr.core.mmtr.signal.MmtrJunctionState.unclearedNodeKeys(simulator, null);
			final var totals = simulator.mmtrSections.totalSectionViews(null, restricted::contains);
			final StringBuilder report = new StringBuilder("[totals] 总区间（几何 = 轨道区间；每一条带出覆盖它的各方向行车区间）");
			int staggered = 0;
			for (final var total : totals) {
				if (total.staggered()) {
					staggered++;
				}
				report.append("\n  ").append(total.track.id)
					.append(" 长=").append(Math.round(total.lengthM() * 10) / 10.0)
					.append(total.occupied ? " **占用**" : " 空")
					.append(total.staggered() ? " **错开**（上下行的区间不是同一段路）" : "");
				for (final var cover : total.covers) {
					final boolean uncovered = cover.entrySignalKey == null || cover.entrySignalKey.isEmpty();
					report.append("\n      ").append(uncovered ? "（无灯）" : cover.entrySignalKey)
						.append(" → ").append(uncovered ? "（无信号）" : cover.aspect)
						.append(" 占用=").append(cover.occupied)
						.append(" 方向=").append(cover.direction.label())
						.append(" 长=").append(Math.round(cover.lengthM() * 10) / 10.0);
				}
			}
			report.append("\n[totals] 合计 ").append(totals.size()).append(" 个总区间，其中错开 ").append(staggered).append(" 个");
			simulator.mmtrCommandResult(report.toString());
			return;
		}
		// 占用转储: whose footprint is on a rail right now (why a train is "blocked ahead").
		if (parts.length >= 2 && parts[0].equals("occ")) {
			simulator.mmtrCommandResult(String.join("\n", simulator.mmtrSections.describeOccupancy(parts[1])));
			return;
		}
		if (parts.length >= 2 && (parts[0].equals("changeends") || parts[0].equals("cab") || parts[0].equals("doors"))) {
			executeCabCommand(simulator, parts);
			return;
		}
		// C3a 调车授权: shunt <vehicleId> <targetRailHex|off> [minutes] [kmh] [SUBSIDIARY_SHUNT|CALLING_ON]
		if (parts.length >= 2 && parts[0].equals("shunt")) {
			executeShuntCommand(simulator, parts);
			return;
		}
		// C4 连挂/解挂: couple <initiatorId> <targetId> | uncouple <vehicleId> <cutAfterCarIndex>
		if (parts.length >= 3 && (parts[0].equals("couple") || parts[0].equals("uncouple"))) {
			executeCoupleCommand(simulator, parts);
			return;
		}
		// 玩家上车: board <vehicleId> [<车厢序号><A|B>] [玩家名]（"传送上车 + 进入驾驶状态"）
		if (parts.length >= 2 && parts[0].equals("board")) {
			executeBoardCommand(simulator, serverWorld, parts);
			return;
		}
		// 诊断开关: trace [on|off|status] - 每 tick 的走行/同步日志（默认关）
		if (parts[0].equals("trace")) {
			executeTraceCommand(simulator, parts);
			return;
		}
		simulator.mmtrCommandResult("未知指令: " + command + " (支持: signals scan | interlock <id>|all | tracks [<railHex>] | lamps | totals | blocks [all|<railHex>] | blocks-v2 [all|<railHex>] | changeends <id> | cab <id> <A|B|out> | doors <id> [open|close|toggle] [left|right|both] | shunt <id> <targetRailHex|off> [minutes] [kmh] [SUBTYPE] | couple <initiatorId> <targetId> | uncouple <id> <cutAfterCarIndex> | board <id> [<车节><A|B>] [玩家名] | trace [on|off])"
			+ "（这些也都能用名词打头的写法从网页指令栏发：train doors <id> open / train couple <a> <b> / train board <id> / …）");
	}

	/**
	 * 玩家上车: {@code board <vehicleId> [<车厢序号><A|B>] [玩家名]} —— 把某位玩家送到那辆车的驾驶室里。
	 *
	 * <p>为什么要在游戏端做：这件事动的是**玩家实体与客户端**（服务端权威挪人 + 客户端建立骑乘状态），
	 * 引擎侧只负责转交（{@code train board …}）。</p>
	 *
	 * <p>不点名时：场上**恰好一名**玩家就用他，多于一名就要求点名 —— 猜错人等于把人瞬移走，
	 * 那是比"多打一个参数"贵得多的错误。</p>
	 */
	private static void executeBoardCommand(Simulator simulator, ServerWorld serverWorld, String[] parts) {
		final long vehicleId;
		try {
			vehicleId = Long.parseLong(parts[1].trim());
		} catch (NumberFormatException e) {
			simulator.mmtrCommandResult("[board] vehicleId 必须是数字: " + parts[1] + "（用法: board <vehicleId> [<车厢序号><A|B>] [玩家名]）");
			return;
		}
		String cabSpec = "";
		String playerName = "";
		for (int i = 2; i < parts.length; i++) {
			if (cabSpec.isEmpty() && parts[i].matches("(?i)\\d+[AB]")) {
				cabSpec = parts[i];
			} else if (playerName.isEmpty()) {
				playerName = parts[i];
			}
		}
		final org.mtr.mapping.holder.MinecraftServer mappedServer = new org.mtr.mapping.holder.MinecraftServer(serverWorld.getServer());
		final org.mtr.mapping.holder.ServerPlayerEntity player;
		if (!playerName.isEmpty()) {
			player = MmtrBoardPlayer.findPlayer(mappedServer, playerName);
			if (player == null) {
				simulator.mmtrCommandResult("[board] 找不到在线玩家 " + playerName);
				return;
			}
		} else {
			final java.util.ArrayList<org.mtr.mapping.holder.ServerPlayerEntity> online = new java.util.ArrayList<>();
			org.mtr.mapping.mapper.MinecraftServerHelper.iteratePlayers(mappedServer, online::add);
			if (online.size() != 1) {
				simulator.mmtrCommandResult("[board] 场上有 " + online.size() + " 名玩家，请点名一位: board " + vehicleId + (cabSpec.isEmpty() ? "" : " " + cabSpec) + " <玩家名>");
				return;
			}
			player = online.get(0);
		}
		if (!MmtrBoardPlayer.board(mappedServer, player, vehicleId, cabSpec)) {
			simulator.mmtrCommandResult("[board] 找不到车辆 " + vehicleId + "（用 vehicle list 看看场上有哪些车）");
			return;
		}
		simulator.mmtrCommandResult("[board] 已把 " + player.getName().getString() + " 送到车 " + vehicleId
			+ " 的驾驶室" + (cabSpec.isEmpty() ? "（自动挑第一个）" : " " + cabSpec)
			+ " —— 客户端 5 秒内没进驾驶室的话，看游戏日志的 [MMTR-BOARD] / [MMTR-CAB] 两行");
	}

	/**
	 * A2/A3/S5 联锁诊断: {@code interlock <vehicleId>} prints the engine's view of one train's movement -
	 * route kind/state (SET/PENDING) and its reason, every turnout it still needs with the authority
	 * state (holder/lock/queue), the aspect the signal layer would show for every rail of the route,
	 * and the narrowing that was mirrored to clients. {@code interlock all} summarises every live
	 * route. Output goes to the OP command log (网页指令栏可见), which is what makes the in-game
	 * verification pass a comparison instead of a guess.
	 */
	private static void executeInterlock(Simulator simulator, String[] parts) {
		if (parts.length < 2 || parts[1].equals("all")) {
			simulator.mmtrCommandResult(org.mtr.core.mmtr.MmtrInterlockReport.describeAll(simulator));
			return;
		}
		try {
			simulator.mmtrCommandResult(org.mtr.core.mmtr.MmtrInterlockReport.describe(simulator, Long.parseLong(parts[1])));
		} catch (NumberFormatException e) {
			simulator.mmtrCommandResult("[interlock] 用法: interlock <vehicleId> | interlock all");
		}
	}

	/**
	 * 实机诊断开关: {@code trace on|off|status}. The per-tick motion / client-sync traces are off by
	 * default - a single coupled consist fills thousands of log lines a minute and buries the messages
	 * that matter - so they are switched on only while someone is watching, without a server restart.
	 */
	private static void executeTraceCommand(Simulator simulator, String[] parts) {
		final String action = parts.length >= 2 ? parts[1] : "status";
		switch (action) {
			case "on" -> org.mtr.core.mmtr.MmtrTrace.setEnabled(true);
			case "off" -> org.mtr.core.mmtr.MmtrTrace.setEnabled(false);
			case "status" -> {
			}
			default -> {
				simulator.mmtrCommandResult("[trace] 用法: trace on | trace off | trace status");
				return;
			}
		}
		simulator.mmtrCommandResult("[trace] 每 tick 走行/同步日志 = " + (org.mtr.core.mmtr.MmtrTrace.isEnabled() ? "开" : "关"));
	}

	/**
	 * C4: {@code couple <initiatorId> <targetId>} performs the real coupling surgery (the initiator is
	 * the train that drove up under a 调车授权), {@code uncouple <vehicleId> <cutAfterCarIndex>} cuts a
	 * formation after a car. The engine enforces every gate; this layer only parses and reports.
	 */
	private static void executeCoupleCommand(Simulator simulator, String[] parts) {
		final long firstId;
		final long secondId;
		try {
			firstId = Long.parseLong(parts[1]);
			secondId = Long.parseLong(parts[2]);
		} catch (NumberFormatException e) {
			simulator.mmtrCommandResult("[" + parts[0] + "] 参数必须是数字: " + parts[1] + " " + parts[2]);
			return;
		}
		if (parts[0].equals("couple")) {
			new org.mtr.core.operation.MmtrCoupleControl(firstId, secondId, -1).couple(simulator);
		} else {
			new org.mtr.core.operation.MmtrCoupleControl(firstId, 0, (int) secondId).uncouple(simulator);
		}
	}

	/**
	 * C3a 调车授权 (subsidiary-aspect authority): {@code shunt <id> <targetRailHex> [minutes] [kmh]}
	 * grants one train the authority to pass a signal at danger into the occupied section (the rail
	 * it is about to couple to), {@code shunt <id> off} withdraws it. The grant rail is the rail the
	 * train stands on right now, so the authority covers exactly this movement.
	 */
	private static void executeShuntCommand(Simulator simulator, String[] parts) {
		final long vehicleId;
		try {
			vehicleId = Long.parseLong(parts[1]);
		} catch (NumberFormatException e) {
			simulator.mmtrCommandResult("[shunt] vehicleId 必须是数字: " + parts[1]);
			return;
		}
		final org.mtr.core.data.Vehicle vehicle = simulator.mmtrFindVehicle(vehicleId);
		if (vehicle == null) {
			simulator.mmtrCommandResult("[shunt] 找不到车辆 " + vehicleId);
			return;
		}
		if (parts.length < 3) {
			simulator.mmtrCommandResult("[shunt] 用法: shunt <id> <targetRailHex|off> [minutes] [kmh] [SUBSIDIARY_SHUNT|CALLING_ON]");
			return;
		}
		if (parts[2].equalsIgnoreCase("off") || parts[2].equalsIgnoreCase("revoke")) {
			final boolean ok = simulator.mmtrShuntAuthorities.revoke(vehicleId);
			simulator.mmtrCommandResult("[shunt] " + vehicleId + (ok ? " 已撤销调车授权" : " 无授权可撤销"));
			return;
		}
		final org.mtr.core.mmtr.segment.MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
		final String grantRailHex = walker == null ? "" : walker.railHex();
		final long minutes = parts.length >= 4 ? parseLong(parts[3], 5) : 5;
		final double speedLimitKmh = parts.length >= 5 ? parseDouble(parts[4], 0) : 0;
		final org.mtr.core.mmtr.signal.MmtrShuntAuthority.Kind kind = parts.length >= 6 && parts[5].equalsIgnoreCase("CALLING_ON")
				? org.mtr.core.mmtr.signal.MmtrShuntAuthority.Kind.CALLING_ON
				: org.mtr.core.mmtr.signal.MmtrShuntAuthority.Kind.SUBSIDIARY_SHUNT;
		final org.mtr.core.mmtr.signal.MmtrShuntAuthority authority = simulator.mmtrShuntAuthorities.grant(
				vehicleId, grantRailHex, parts[2], kind, speedLimitKmh, Math.max(1, minutes) * 60_000L);
		simulator.mmtrCommandResult("[shunt] " + vehicleId + " 已授 " + authority.getKind() + ": " + grantRailHex + " -> " + authority.getTargetRailHex()
				+ " 限速 " + Math.round(authority.getSpeedLimitKmh()) + " km/h，有效期 " + minutes + " 分钟（主显示仍红，副显示授权）");
	}

	private static long parseLong(String value, long fallback) {
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static double parseDouble(String value, double fallback) {
		try {
			return Double.parseDouble(value);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/**
	 * B7.6: OP-side cab ops. {@code changeends <id>} performs the whole 换端 (legal only at a stand,
	 * only on a consist-body train); {@code cab <id> A|B|out} takes/leaves a cab (key in / key out).
	 * The physical gates live in the engine ({@code Vehicle.enterMmtrCab/leaveMmtrCab/
	 * changeEndsMmtrMotion}); this layer only resolves the id and reports back to the command log.
	 */
	private static void executeCabCommand(Simulator simulator, String[] parts) {
		final long vehicleId;
		try {
			vehicleId = Long.parseLong(parts[1]);
		} catch (NumberFormatException e) {
			simulator.mmtrCommandResult("[" + parts[0] + "] vehicleId 必须是数字: " + parts[1]);
			return;
		}
		final org.mtr.core.data.Vehicle vehicle = simulator.mmtrFindVehicle(vehicleId);
		if (vehicle == null) {
			simulator.mmtrCommandResult("[" + parts[0] + "] 找不到车辆 " + vehicleId);
			return;
		}
		if (parts[0].equals("doors")) {
			// Crew door control: no cab/consist requirement, so anyone at the platform can open a
			// standing train's doors through the interact key. The optional side argument ("left" /
			// "right") is the per-side control the cab crew uses (Y / U).
			final String action = parts.length >= 3 ? parts[2].toLowerCase(java.util.Locale.ROOT) : "toggle";
			final String side = parts.length >= 4 ? parts[3] : "both";
			final boolean open = vehicle.vehicleExtraData.mmtrSetDoors(action, side);
			simulator.mmtrCommandResult("[doors] " + vehicleId + (open ? " 开门" : " 关门") + " " + side + " (L=" + vehicle.vehicleExtraData.getMmtrDoorLeft() + " R=" + vehicle.vehicleExtraData.getMmtrDoorRight() + " 手动=" + vehicle.vehicleExtraData.isMmtrDoorManual() + ")");
			return;
		}
		if (vehicle.getMmtrConsistWalker() == null) {
			simulator.mmtrCommandResult("[" + parts[0] + "] 车辆 " + vehicleId + " 不是编组体车（无驾驶室模型）");
			return;
		}
		if (parts[0].equals("changeends")) {
			final boolean ok = vehicle.changeEndsMmtrMotion();
			simulator.mmtrCommandResult("[" + parts[0] + "] " + vehicleId + (ok ? " 换端完成 → " + vehicle.getMmtrActiveCab() : " 换端失败（需停稳且已有驾驶室）"));
			return;
		}
		final String what = parts.length >= 3 ? parts[2].toLowerCase(java.util.Locale.ROOT) : "a";
		// 钥匙归属: the game-side packet appends the crew member's uuid; a web OP command has none and
		// acts as an operator (may take/release any key).
		final java.util.UUID crew = parseCrewUuid(parts.length >= 4 ? parts[3] : null);
		if (what.equals("out") || what.equals("leave")) {
			final boolean ok = vehicle.leaveMmtrCab(crew);
			simulator.mmtrCommandResult("[" + parts[0] + "] " + vehicleId + (ok ? " 已拔钥匙" : " 无钥匙可拔（或钥匙在他人手中）"));
		} else {
			// C6 cab naming: "<car><A|B>" (e.g. 3A = the A-end cab of car 3) or the plain ends A/B.
			final java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(\\d*)([ab])$", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(what);
			if (!matcher.matches()) {
				simulator.mmtrCommandResult("[" + parts[0] + "] 驾驶室写法: A | B | <车厢序号><A|B>（例如 3A / 3B）");
				return;
			}
			final String carText = matcher.group(1);
			final boolean towardA = matcher.group(2).equalsIgnoreCase("a");
			final boolean ok;
			final String name;
			if (carText.isEmpty()) {
				ok = vehicle.enterMmtrCab(towardA ? org.mtr.core.mmtr.consist.MmtrCabState.Cab.CAB_A : org.mtr.core.mmtr.consist.MmtrCabState.Cab.CAB_B, crew);
				name = towardA ? "A" : "B";
			} else {
				final int carIndex = Integer.parseInt(carText) - 1;
				ok = vehicle.enterMmtrCabAtCar(carIndex, towardA, crew);
				name = (carIndex + 1) + (towardA ? "A" : "B");
			}
			simulator.mmtrCommandResult("[" + parts[0] + "] " + vehicleId + (ok ? " 已进入驾驶室 " + name + "（钥匙归属 " + vehicle.getMmtrCabKeyHolder() + (crew == null ? "" : " " + crew) + "）" : " 无法进入（需停稳且该驾驶室空闲）"));
		}
	}

	/** Parses the optional crew uuid argument; {@code null} when absent or malformed (operator). */
	private static java.util.UUID parseCrewUuid(@javax.annotation.Nullable String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		try {
			return java.util.UUID.fromString(value);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * 手动刷新：把**所有已加载区块**里的信号灯与登记表核对一遍（全量重建、结果可解释）。
	 *
	 * <h3>为什么还要**删**（用户实测："有几个信号灯我已经敲掉了，地图不正确"）</h3>
	 * <p>第一版这里是**只加不删**的：灯被敲掉之后登记表里那一条还在，于是地图上永远画着一盏
	 * 已经不存在的灯。扫描是唯一能看到"世界里到底还有没有这盏灯"的地方（只有游戏端能枚举已加载
	 * 区块），所以删除也只能在这里做。</p>
	 *
	 * <h3>删除的安全边界（不然会误删）</h3>
	 * <p>只能删**所在区块已加载、但区块里没有它**的条目。玩家走远之后区块会卸载，那时"找不到"
	 * 只说明没加载，不代表灯没了 —— 按"没找到就删"会把远处的灯全部清掉，那是灾难性的。</p>
	 *
	 * <h3>为什么这里只剩一个循环</h3>
	 * <p>逐区块的核对逻辑整体搬到了 {@link MmtrSignalSync#syncChunk}：自动刷新（区块加载 / 敲灯 /
	 * 每秒轮转）和这条手动指令必须是**同一份实现**。两条路各写一份的结果是"手动扫一遍修好了、
	 * 自动跑一轮又改回去"，而且用户没法用手动扫描的结果去解释自动刷新做了什么。</p>
	 */
	private static void scanSignals(Simulator simulator, ServerWorld serverWorld) {
		final MmtrSignalSync.Result total = new MmtrSignalSync.Result();
		for (final WorldChunk chunk : MmtrChunkTracker.loadedChunks(serverWorld)) {
			total.merge(MmtrSignalSync.syncChunk(simulator, serverWorld, chunk));
		}
		simulator.mmtrCommandResult("[signals] 扫描完成: 找到 " + total.found + " 个信号灯, 新增 " + total.added
			+ " 个 AUTO 条目, 保留人工绑定 " + total.skippedBound + " 个（只刷新朝向，不动绑定）, 清理已拆掉的 " + total.removed + " 个"
			+ (total.removedBound > 0 ? "（其中 " + total.removedBound + " 个是人工绑定：世界里的灯没了，绑定一并移除）" : "")
			+ "; 节点朝向 " + scanNodeAngles(simulator, serverWorld));
	}

	/**
	 * 把每个 MTR 节点的**游戏内朝向角**读出来上报给引擎。
	 *
	 * <h3>为什么要走经纬度遍历，而不是像灯那样走方块实体</h3>
	 * <p>信号灯是方块实体，上一段的循环能枚举到；**节点不是方块实体** —— 它是个可穿过的模型方块，
	 * 世界里根本没有对应的 {@code BlockEntity}。所以第一版把节点判定写在方块实体循环里，结果是
	 * 一个都没采到（实测：拓扑里 137 个节点，带 angle 的 0 个）。</p>
	 *
	 * <h3>为什么只在"节点所在的那一格"读</h3>
	 * <p>引擎的 {@code positionsToRail} 里已经有全部节点坐标（轨连到哪，节点就在哪），所以不需要
	 * 满世界扫：只去这些坐标查一次方块状态即可。每个区块只扫一遍，且只扫区块里**真的登记过节点**
	 * 的那些格，避免 16×16×384 的全量遍历拖住服务端 tick。</p>
	 *
	 * <h3>这个值拿来干什么</h3>
	 * <p>引擎的拓扑原本只有节点坐标，"一盏灯守哪条腿"只能靠灯自己的朝向去猜；实测世界里同一个节点上
	 * 两盏朝向相对的灯守的是**相反方向**，用灯的朝向推不出来。原版渲染
	 * {@code RenderSignalBase.getAspectState} 用的正是 {@code BlockNode.getAngle(state)}（偏移 90°），
	 * 现在把它原样带给引擎。</p>
	 */
	private static String scanNodeAngles(Simulator simulator, ServerWorld serverWorld) {
		// 区块 → 该区块里登记过的节点坐标（局部坐标），只查这些格
		final java.util.HashMap<Long, java.util.List<int[]>> nodesByChunk = new java.util.HashMap<>();
		simulator.positionsToRail.keySet().forEach(node -> {
			final int x = (int) node.getX();
			final int y = (int) node.getY();
			final int z = (int) node.getZ();
			nodesByChunk.computeIfAbsent(chunkKey(x >> 4, z >> 4), k -> new java.util.ArrayList<>()).add(new int[]{x, y, z});
		});
		final World world = new World(serverWorld);
		int seen = 0;
		int updated = 0;
		int missing = 0;
		for (final java.util.Map.Entry<Long, java.util.List<int[]>> group : nodesByChunk.entrySet()) {
			final int chunkX = (int) (group.getKey() >> 32);
			final int chunkZ = (int) (long) group.getKey();
			if (!world.isChunkLoaded(chunkX, chunkZ)) {
				continue; // 没加载就读不到，留着上一次的值
			}
			for (final int[] pos : group.getValue()) {
				final BlockState blockState = world.getBlockState(new org.mtr.mapping.holder.BlockPos(pos[0], pos[1], pos[2]));
				// 节点方块上下各一格都可能，两格都试；两格都不是节点就说明这个坐标上没节点
				float angle = Float.NaN;
				if (blockState.getBlock().data instanceof org.mtr.mod.block.BlockNode) {
					angle = org.mtr.mod.block.BlockNode.getAngle(blockState);
				} else {
					for (final int dy : new int[]{-1, 1}) {
						final BlockState other = world.getBlockState(new org.mtr.mapping.holder.BlockPos(pos[0], pos[1] + dy, pos[2]));
						if (other.getBlock().data instanceof org.mtr.mod.block.BlockNode) {
							angle = org.mtr.mod.block.BlockNode.getAngle(other);
							break;
						}
					}
				}
				if (Float.isNaN(angle)) {
					missing++;
					continue;
				}
				seen++;
				if (simulator.mmtrNodeAngleUpsert(pos[0], pos[1], pos[2], angle)) {
					updated++;
				}
			}
		}
		return "已读 " + seen + " 个（新/变更 " + updated + " 个，坐标上没找到节点 " + missing
			+ " 个，引擎共 " + simulator.mmtrNodeAngles.size() + " 个有朝向）";
	}

	/** 区块坐标 → 可比较的键（世界坐标每次 {@code >>4} 得到区块坐标）。 */
	private static long chunkKey(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
	}
}
