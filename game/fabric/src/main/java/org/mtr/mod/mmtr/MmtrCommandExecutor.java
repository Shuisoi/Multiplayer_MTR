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
		final long probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
		try {
			tickMeasured(minecraftServer);
		} finally {
			// 指令执行器本身应当接近 0；这里出现毫秒级读数就说明**指令本身**很贵（扫描/铺轨都在这一格里）。
			org.mtr.core.mmtr.probe.MmtrProbe.end("server.commandExecutor", probeT);
		}
	}

	private static void tickMeasured(MinecraftServer minecraftServer) {
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
		/*
		 * 性能探针（notes/337）：`probe on|off|status|dump|reset` 加上三个可调旋钮。
		 *
		 * <p>为什么值得有一条自己的指令：现场"服务端落后 40 s"这件事拿到手时，需要的不是重启加参数
		 * （重启就要重新等区块生成），而是**当场开着、当场读一次**。三个旋钮也都能在不动服务端的情况下改 ——
		 * interval 调小是为了抓尖峰，warnMs 调小是为了把"多慢算慢"的门槛压到当前规模以下。</p>
		 */
		if (parts[0].equals("probe")) {
			executeProbeCommand(simulator, serverWorld, parts);
			return;
		}
		// 铺轨: rail add <x1> <y1> <z1> <x2> <y2> <z2> [--speed=300] [--platform] [--siding]
		// 放在游戏端做，是因为造轨的两半里"节点方块"只有游戏端能放；一次调用同时产生两半，
		// 从此不会出现"文件写了、方块没放"那种两侧对不上的状态。
		if (parts[0].equals("rail")) {
			executeRailCommand(simulator, serverWorld, parts);
			return;
		}
		simulator.mmtrCommandResult("未知指令: " + command + " (支持: signals scan | interlock <id>|all | tracks [<railHex>] | lamps | totals | blocks [all|<railHex>] | blocks-v2 [all|<railHex>] | changeends <id> | cab <id> <A|B|out> | doors <id> [open|close|toggle] [left|right|both] | shunt <id> <targetRailHex|off> [minutes] [kmh] [SUBTYPE] | couple <initiatorId> <targetId> | uncouple <id> <cutAfterCarIndex> | board <id> [<车节><A|B>] [玩家名] | trace [on|off])"
			+ "（这些也都能用名词打头的写法从网页指令栏发：train doors <id> open / train couple <a> <b> / train board <id> / rail add … / rail remove <x> <y> <z> / …）");
	}

	/**
	 * 运行时铺轨: {@code rail add <x1> <y1> <z1> <x2> <y2> <z2> [--speed=300] [--platform] [--siding]}。
	 *
	 * <h3>为什么必须由游戏端执行</h3>
	 * <p>MTR 的一条轨道在磁盘上是<b>两半</b>：世界里的 {@code mtr:rail} 节点方块，
	 * 以及引擎的轨道数据（{@code rails/<hex>}，只在 {@code Simulator} 构造时读一次）。
	 * 从外部改文件只能产生后者，于是必然要重启，而且极容易做出<b>两侧对不上</b>的状态 ——
	 * 实测就踩过：文件写了、起点方块却是 air。</p>
	 *
	 * <p>这里一次调用同时完成三件事，顺序与 {@code ItemRailModifier.placeNodeAndConnect}
	 * （玩家亲手放轨走的那段）一致：</p>
	 * <ol>
	 *   <li>两端放 {@code mtr:rail} 节点方块，朝向用<b>同一套游戏函数</b>算
	 *       （{@link org.mtr.core.data.Rail#getAngles} + {@code BlockNode.getStateWithAngle}）；</li>
	 *   <li>构造 {@link org.mtr.core.data.Rail}；</li>
	 *   <li>{@code PacketUpdateData.sendDirectlyToServerRail(...)} 推给引擎 ——
	 *       引擎的 {@code UpdateDataRequest.update()} 会运行时建轨、
	 *       顺手调 {@code checkOrCreateSavedRailAndUpdateTiltAngles} 创建站台/股道记录、
	 *       再 {@code data.sync()} 重建 {@code positionsToRail} 图。<b>全程不重启。</b></li>
	 * </ol>
	 *
	 * <h3>节点朝向是怎么定的（这里曾经错过一次）</h3>
	 * <p>第一次手工铺轨时我按源码推 {@code facing=true}，实机看是反的。原因是
	 * {@code BlockNode.getAngle} 与 {@code getStateWithAngle} 的组合语义没法靠读代码可靠地反推
	 * （22.5/45 的位组合与 facing 的镜像关系绕）。所以这里不硬编码那套位组合，而是
	 * <b>先算方向、再用候选 yaw 试算并回读校验</b>：把候选 yaw 代进
	 * {@code getStateWithAngle}，再用 {@code BlockNode.getAngle} 读回来，与"起点指向终点的
	 * 几何方位"比对 —— 夹角在 90° 以内的那个候选才被采用。朝向因此是<b>验出来的</b>，不是猜的。</p>
	 *
	 * <h3>{@code --angle1=} / {@code --angle2=}：显式朝向，用来画曲线（2026-09-26 加）</h3>
	 * <p>不给这两个参数时，两端节点的朝向都取自"另一端点方向"（弦向），于是
	 * {@code RailMath} 永远走 case 1.a「平行且共线」⇒ <b>这条指令过去只会画直线</b>
	 * （实测：16 条侧线精确 220 m，见 notes/288）。给了之后，两端的行进方向可以不同：</p>
	 * <ul>
	 *   <li>两端方向<b>不同</b> ⇒ 转角。差 90° 且 {@code along == lateral} 时是干净的纯圆弧
	 *       （例如 {@code (0,0,h0) → (R,R,h90)} ⇒ 长度 πR/2）；</li>
	 *   <li>两端方向<b>相同</b>但横向错开 ⇒ S 弯（两段圆弧，{@code R = (L²+o²)/(4o)}）。</li>
	 * </ul>
	 * <p>角度口径 = <b>行进方向</b>，0=东、90=南、180=西、270=北，与节点方块同一套
	 * （节点朝向空间是 mod 180，见 {@link #resolveNodeState} 的说明）。</p>
	 */
	private static void executeRailCommand(Simulator simulator, ServerWorld serverWorld, String[] parts) {
		if (parts.length >= 2 && parts[1].equals("remove")) {
			executeRailRemoveCommand(simulator, serverWorld, parts);
			return;
		}
		if (parts.length < 2 || !parts[1].equals("add")) {
			simulator.mmtrCommandResult("[rail] 用法: rail add <x1> <y1> <z1> <x2> <y2> <z2> [--speed=300] [--platform] [--siding]"
				+ " [--angle1=deg --angle2=deg]  |  rail remove <x> <y> <z>");
			return;
		}
		if (parts.length < 8) {
			simulator.mmtrCommandResult("[rail] 需要六个坐标: rail add <x1> <y1> <z1> <x2> <y2> <z2>");
			return;
		}

		final long x1;
		final long y1;
		final long z1;
		final long x2;
		final long y2;
		final long z2;
		try {
			x1 = Long.parseLong(parts[2]);
			y1 = Long.parseLong(parts[3]);
			z1 = Long.parseLong(parts[4]);
			x2 = Long.parseLong(parts[5]);
			y2 = Long.parseLong(parts[6]);
			z2 = Long.parseLong(parts[7]);
		} catch (NumberFormatException e) {
			simulator.mmtrCommandResult("[rail] 坐标必须是整数: rail add <x1> <y1> <z1> <x2> <y2> <z2>");
			return;
		}

		if (x1 == x2 && y1 == y2 && z1 == z2) {
			simulator.mmtrCommandResult("[rail] 两端点相同，无法构成轨道");
			return;
		}

		boolean isPlatform = false;
		boolean isSiding = false;
		long speed = 300;
		Double angle1Override = null;
		Double angle2Override = null;
		for (int i = 8; i < parts.length; i++) {
			final String token = parts[i];
			if (token.equals("--platform")) {
				isPlatform = true;
			} else if (token.equals("--siding")) {
				isSiding = true;
			} else if (token.startsWith("--speed=")) {
				try {
					speed = Long.parseLong(token.substring("--speed=".length()).trim());
				} catch (NumberFormatException e) {
					simulator.mmtrCommandResult("[rail] --speed 必须是整数（公里/小时），收到: " + token);
					return;
				}
			} else if (token.startsWith("--angle1=")) {
				angle1Override = parseDegrees(token.substring("--angle1=".length()));
				if (angle1Override == null) {
					simulator.mmtrCommandResult("[rail] --angle1 必须是度数，收到: " + token);
					return;
				}
			} else if (token.startsWith("--angle2=")) {
				angle2Override = parseDegrees(token.substring("--angle2=".length()));
				if (angle2Override == null) {
					simulator.mmtrCommandResult("[rail] --angle2 必须是度数，收到: " + token);
					return;
				}
			}
		}
		if (isPlatform && isSiding) {
			simulator.mmtrCommandResult("[rail] --platform 与 --siding 互斥");
			return;
		}
		if ((angle1Override == null) != (angle2Override == null)) {
			// 只给一个时另一端会落回弦向 ⇒ 得到的形状既不是转角也不是 S 弯，且不会报错。宁可拒绝。
			simulator.mmtrCommandResult("[rail] --angle1 与 --angle2 必须成对给出（只给一个时另一端的朝向无从确定）");
			return;
		}

		// 坐标：直接用引擎侧的 Position 构造，不走 Init.blockPosToPosition ——
		// 后者吃的是**映射层**的 org.mtr.mapping.holder.BlockPos，而这里的 posStart 是原生
		// net.minecraft.util.math.BlockPos，两者不可隐式转换。
		final org.mtr.core.data.Position positionStart = new org.mtr.core.data.Position(x1, y1, z1);
		final org.mtr.core.data.Position positionEnd = new org.mtr.core.data.Position(x2, y2, z2);

		// 映射层的世界与坐标：节点方块的状态读写要走 org.mtr.mapping.holder 那一套
		// （与 BlockNode.resetRailNode 同款），所以这里转一次。
		final org.mtr.mapping.holder.ServerWorld mappedWorld = new org.mtr.mapping.holder.ServerWorld(serverWorld);
		final org.mtr.mapping.holder.BlockPos mappedPosStart = new org.mtr.mapping.holder.BlockPos((int) x1, (int) y1, (int) z1);
		final org.mtr.mapping.holder.BlockPos mappedPosEnd = new org.mtr.mapping.holder.BlockPos((int) x2, (int) y2, (int) z2);

		// ---- 1) 节点方块：朝向用"穷举 + 比对"定，不推公式、不硬编码位组合 ----
		//     --angle1/--angle2 给了就用它当目标方位；没给才退回"另一端点方向"（= 弦向 ⇒ 直线）
		final BlockState stateStart = resolveNodeState(org.mtr.mod.Blocks.RAIL_NODE.get().getDefaultState(), positionStart, positionEnd, angle1Override);
		final BlockState stateEnd = resolveNodeState(org.mtr.mod.Blocks.RAIL_NODE.get().getDefaultState(), positionEnd, positionStart, angle2Override);
		if (stateStart == null || stateEnd == null) {
			simulator.mmtrCommandResult("[rail] 无法为这两个端点定出节点朝向（两端点重合？）");
			return;
		}

		// ---- 2) 构造轨道（角度口径与 ItemRailModifier 完全一致）----
		final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair<org.mtr.core.tool.Angle, org.mtr.core.tool.Angle> angles =
			org.mtr.core.data.Rail.getAngles(positionStart, org.mtr.mod.block.BlockNode.getAngle(stateStart), positionEnd, org.mtr.mod.block.BlockNode.getAngle(stateEnd));

		final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<String> styles = org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList.of("default");
		final org.mtr.core.data.Rail rail;
		if (isSiding) {
			rail = org.mtr.core.data.Rail.newSidingRail(positionStart, angles.left(), positionEnd, angles.right(),
				org.mtr.core.data.Rail.Shape.QUADRATIC, 0, styles, org.mtr.core.data.TransportMode.TRAIN);
		} else {
			/*
			 * 普通轨与站台轨走同一条构造路径，只有 isPlatform 不同。
			 *
			 * 为什么不给站台轨用 Rail.newPlatformRail：那个便捷方法把限速写死成 80
			 * （见 RailType.PLATFORM == 80），而"是不是站台轨"与"限速多少"在引擎里是两个
			 * 独立参数（见 Rail.newRail 的 isPlatform 与 speedLimit1/2）。用 newRail 显式传
			 * isPlatform，就能做出**160 限速的站台轨**——这正是用户要的：
			 * MTR 的站台轨类型存在，但不必被它的默认限速绑住。
			 *
			 * canAccelerate：站台轨按 MTR 的 RailType 惯例应当是 false（列车在站台不做加速区段），
			 * 但 canHaveSignal 保持 true —— 站台轨同样要能装信号灯，否则闭塞区间会在站台断开。
			 */
			rail = org.mtr.core.data.Rail.newRail(positionStart, angles.left(), positionEnd, angles.right(),
				org.mtr.core.data.Rail.Shape.QUADRATIC, 0, styles, speed, speed,
				isPlatform, false, !isPlatform, false, true, org.mtr.core.data.TransportMode.TRAIN);
		}

		// ---- 3) 放方块并推给引擎 ----
		// 方块读写走映射层的 ServerWorld（与 BlockNode.resetNode 同款），
		// 推送走 PacketUpdateData（与玩家亲手放轨同一条路）。
		mappedWorld.setBlockState(mappedPosStart, stateStart.with(new org.mtr.mapping.holder.Property<>(org.mtr.mod.block.BlockNode.IS_CONNECTED.data), true));
		mappedWorld.setBlockState(mappedPosEnd, stateEnd.with(new org.mtr.mapping.holder.Property<>(org.mtr.mod.block.BlockNode.IS_CONNECTED.data), true));
		org.mtr.mod.packet.PacketUpdateData.sendDirectlyToServerRail(mappedWorld, rail);

		final String kind = isPlatform ? "站台" : isSiding ? "股道" : "普通";
		simulator.mmtrCommandResult("[rail] 已铺 " + kind + "轨 (" + x1 + "," + y1 + "," + z1 + ") → (" + x2 + "," + y2 + "," + z2 + ")"
			+ (isSiding ? "  （股道限速固定 40）" : "  限速 " + speed + " km/h")
			+ (isPlatform ? "  isPlatform=true" : "")
			+ "  轨 hex=" + rail.getHexId()
			+ "  已推给引擎热建（无需重启）。用 query node " + x1 + "," + y1 + "," + z1 + " 核对。");
	}

	/**
	 * 运行时**删轨**: {@code rail remove <x> <y> <z>}。
	 *
	 * <h3>为什么加这一条</h3>
	 * <p>在此之前删轨<b>只能靠人在游戏里手动敲节点方块</b>：{@code rail} 命名空间只有
	 * {@code add}/{@code list}，而 {@code /setblock … air} 实测<b>不触发</b>
	 * {@code BlockNode.onBreak2}（notes/306 实测：setblock 前后 {@code rail list} 都是 74 条）。
	 * 于是改一段标高、切一段节点分段都要人肉敲几十下 —— notes/307、notes/315 两次卡在这里。</p>
	 *
	 * <h3>做法：与玩家敲掉节点方块**完全同一条通路**</h3>
	 * <ol>
	 *   <li>把该节点方块换成空气（等价于"方块没了"）；</li>
	 *   <li>{@code PacketDeleteData.sendDirectlyToServerRailNodePosition(…)} ——
	 *       与 {@link org.mtr.mod.block.BlockNode#onBreak2} 里那一行<b>逐字相同</b>，
	 *       引擎据此删掉<b>挂在这个节点上的所有轨</b>。</li>
	 * </ol>
	 *
	 * <p>⚠ 删一个节点会连带删掉以它为端点的**全部**轨（中间节点 = 左右两段一起没）。
	 * 这与玩家敲方块的行为一致，是刻意的。</p>
	 */
	private static void executeRailRemoveCommand(Simulator simulator, ServerWorld serverWorld, String[] parts) {
		if (parts.length < 5) {
			simulator.mmtrCommandResult("[rail] 用法: rail remove <x> <y> <z>");
			return;
		}
		final long x;
		final long y;
		final long z;
		try {
			x = Long.parseLong(parts[2]);
			y = Long.parseLong(parts[3]);
			z = Long.parseLong(parts[4]);
		} catch (NumberFormatException e) {
			simulator.mmtrCommandResult("[rail] 坐标必须是整数: rail remove <x> <y> <z>");
			return;
		}

		final BlockPos pos = new BlockPos((int) x, (int) y, (int) z);
		final net.minecraft.block.BlockState vanillaState = serverWorld.getBlockState(pos);
		if (!(vanillaState.getBlock() instanceof org.mtr.mod.block.BlockNode)) {
			// 幂等：方块已经不在了（或不是节点）就当"没什么可删"，别报成失败
			simulator.mmtrCommandResult("[rail] (" + x + "," + y + "," + z + ") 不是 mtr:rail 节点方块（当前 "
				+ vanillaState.getBlock().getTranslationKey() + "），未做改动");
			return;
		}

		final org.mtr.mapping.holder.ServerWorld mappedWorld = new org.mtr.mapping.holder.ServerWorld(serverWorld);
		serverWorld.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState());
		org.mtr.mod.packet.PacketDeleteData.sendDirectlyToServerRailNodePosition(mappedWorld, new org.mtr.core.data.Position(x, y, z));

		simulator.mmtrCommandResult("[rail] 已删节点 (" + x + "," + y + "," + z + ") 及其上所有轨"
			+ "  （方块→空气 + 已发 PacketDeleteData）。用 rail list 核对条数。");
	}

	/**
	 * 求节点方块的朝向状态：让"这个节点本身朝向哪"与"它指向另一个端点的几何方位"最接近。
	 *
	 * <h3>为什么不用 {@code getStateWithAngle(state, yaw)}</h3>
	 * <p>玩家放轨走的是那条路，但它要的是<b>玩家 yaw</b>，而这里没有玩家。
	 * 第一版我按几何反推 yaw，再用 {@code getStateWithAngle} 造状态 —— 实机验证时
	 * 守卫直接拒绝了：造出来的状态再过 {@code BlockNode.getAngle} 读回来，
	 * 与目标方位对不上（差 90°），说明"yaw ↔ 属性位组合"的换算不是我推的那个。</p>
	 *
	 * <p>所以这里换一种<b>可验证</b>的定法：属性只有三个布尔（facing / is_45 / is_22_5），
	 * 一共 8 种组合，全部枚举出来，每种都用 {@code BlockNode.getAngle} 读回它的实际朝向角，
	 * 取与目标方位夹角最小的那个。于是"节点朝向"这件事完全不依赖我对位组合语义的理解：
	 * 组合是穷举的，角度是游戏函数自己报的。</p>
	 *
	 * <p>注意 {@code getAngle} 返回的角度口径（+X 为 0、逆时针？顺时针）也不必假定：
	 * {@code Rail.getAngles} 与它是<b>同一口径</b>（玩家放轨时正是把 {@code getAngle(stateEnd)}
	 * 直接喂给 {@code getAngles}），所以只要"节点朝向"与"轨的几何方位"在<b>同一个口径里</b>
	 * 一一对应即可，两个口径一起错反而仍然自洽。</p>
	 *
	 * <h3>⚠️ 角度必须按 mod 180 比（2026-09-25 实机修正）</h3>
	 * <p>上面那句"两个口径一起错反而仍然自洽"是**错的**，它漏了一个前提：两个口径的<b>取值范围</b>
	 * 也得一样。{@code BlockNode.getAngle} 的取值只有 {@code 0 / 22.5 / … / 157.5} —— 看
	 * {@code assets/mtr/blockstates/rail.json} 就明白：节点总共 8 个分支，是 4 个模型
	 * （{@code rail_node} / {@code _22_5} / {@code _45} / {@code _67_5}）各配 {@code facing}
	 * 的 0°/90° 两种 y 旋转，<b>根本没有 180° 以上的值</b>。也就是说节点的朝向空间本身是
	 * <b>mod 180</b> 的：一根直轨的两端，本来就应该摆成同一个朝向。</p>
	 *
	 * <p>原实现拿 {@code 0..360} 的几何方位去比 {@code 0..157.5} 的可达角，于是"远端"出错：
	 * 沿 Z 的一根轨，起点方位 90° 命中 90°（对），终点方位 270° 在可达集合里的最近值却是
	 * <b>0°</b>（差 90°）而不是 90°。<b>实机现象</b>：16 条沿 Z 的侧线，z=-153 那端横平竖直，
	 * z=67 那端整个横了 90°（用户当场指出）。</p>
	 *
	 * <p>所以这里把目标方位与候选角度<b>都折到 mod 180</b> 再比最小夹角 —— 直轨两端因此得到
	 * 同一个状态，与 blockstate 的 8 分支一一对应。</p>
	 *
	 * @param bearingOverride 显式给的行进方向（{@code --angle1=} / {@code --angle2=}，度）；
	 *                        为 {@code null} 时退回"从 {@code from} 指向 {@code to} 的几何方位"
	 *                        （弦向 ⇒ 两端朝向相同 ⇒ 引擎只会给一条直线，见铁律 5）
	 * @return 朝向最贴合的节点状态；两端点重合时返回 {@code null}
	 */
	private static BlockState resolveNodeState(BlockState defaultState, org.mtr.core.data.Position from, org.mtr.core.data.Position to, Double bearingOverride) {
		final double dx = to.getX() - from.getX();
		final double dz = to.getZ() - from.getZ();
		if (dx == 0 && dz == 0) {
			return null;
		}
		// 目标方位：与 Rail.getAngles 内部同一个式子（Math.atan2(dz, dx) 的度数形式），折到 [0, 180)
		// 显式朝向走同一条折叠加（口径一致：0=东、90=南，与节点朝向空间一样是 mod 180）
		final double rawBearing = bearingOverride != null ? bearingOverride : Math.toDegrees(Math.atan2(dz, dx));
		final double targetBearing = ((rawBearing % 180) + 180) % 180;

		BlockState best = null;
		double bestDifference = Double.MAX_VALUE;
		for (final boolean facing : new boolean[]{false, true}) {
			for (final boolean is45 : new boolean[]{false, true}) {
				for (final boolean is225 : new boolean[]{false, true}) {
					final BlockState candidate = defaultState
						.with(new org.mtr.mapping.holder.Property<>(org.mtr.mod.block.BlockNode.FACING.data), facing)
						.with(new org.mtr.mapping.holder.Property<>(org.mtr.mod.block.BlockNode.IS_45.data), is45)
						.with(new org.mtr.mapping.holder.Property<>(org.mtr.mod.block.BlockNode.IS_22_5.data), is225);
					final double actual = org.mtr.mod.block.BlockNode.getAngle(candidate) % 180;
					// 两个角度之间的最小夹角（mod 180：raw 与 180-raw 取小）
					final double raw = ((actual - targetBearing) % 180 + 180) % 180;
					final double difference = Math.min(raw, 180 - raw);
					if (difference < bestDifference) {
						bestDifference = difference;
						best = candidate;
					}
				}
			}
		}
		return best;
	}

	/** 解析 {@code --angle1=} / {@code --angle2=} 的度数；不是数就返回 {@code null}（调用方报错，不静默退回）。 */
	private static Double parseDegrees(String raw) {
		try {
			return Double.parseDouble(raw.trim());
		} catch (NumberFormatException e) {
			return null;
		}
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
	 * 性能探针开关与读数：{@code probe on|off|status|dump|reset [--interval=n] [--warnMs=n] [--file=path]}。
	 *
	 * <h3>为什么读数是"引擎侧 + 服务端侧"一起报</h3>
	 * <p>分段表是**共用**的（引擎的 {@code MmtrProbe} 与游戏端的 {@code MmtrTickProbe} 写同一张表），
	 * 所以一次 {@code probe dump} 就能同时看到 {@code server.*}（服务端 tick 视角）与
	 * {@code sections.*} / {@code projection.*}（引擎视角）。两边分开读会得出矛盾的结论 ——
	 * 现场那 40 s 正是"服务端帧很长、引擎分段却不大"的组合，必须并排看。</p>
	 *
	 * <p>汇总里的帧统计按**帧槽**分开：{@code frame=sim} 是引擎一次模拟 tick，
	 * {@code frame=server} 是 MC 一次服务端 tick，两者不是一回事。</p>
	 */
	private static void executeProbeCommand(Simulator simulator, ServerWorld serverWorld, String[] parts) {
		final String action = parts.length >= 2 ? parts[1] : "status";
		final String label = simulator.dimension;
		switch (action) {
			case "on", "start" -> {
				org.mtr.core.mmtr.probe.MmtrProbe.setEnabled(true);
				simulator.mmtrCommandResult("[probe] 已开（每 " + org.mtr.core.mmtr.probe.MmtrProbe.getReportIntervalTicks()
					+ " tick 一行汇总；超过 " + org.mtr.core.mmtr.probe.MmtrProbe.getWarnMillis() + " ms 的帧进最坏帧榜）。"
					+ " 现在起着：服务端 tick（server.*）与引擎 tick（rails/vehicles/sections/projection…）两个视角。");
			}
			case "off", "stop" -> {
				org.mtr.core.mmtr.probe.MmtrProbe.setEnabled(false);
				simulator.mmtrCommandResult("[probe] 已关（分段不再计时；累计量保留，用 probe reset 清）");
			}
			case "dump", "report", "show" -> {
				// fullReport 里既有引擎分段表，也有游戏端注册的补充行（世界规模）。
				simulator.mmtrCommandResult(org.mtr.core.mmtr.probe.MmtrProbe.fullReport(label));
			}
			case "reset" -> {
				org.mtr.core.mmtr.probe.MmtrProbe.resetAll();
				simulator.mmtrCommandResult("[probe] 窗口与累计都已清零（做前后对照时先 reset，再跑一段，再 dump）");
			}
			case "interval", "warn", "file", "status" -> {
				boolean changed = false;
				for (int i = 2; i < parts.length; i++) {
					final String token = parts[i];
					if (token.startsWith("--interval=")) {
						final int ticks = (int) parseLong(token.substring("--interval=".length()), org.mtr.core.mmtr.probe.MmtrProbe.getReportIntervalTicks());
						org.mtr.core.mmtr.probe.MmtrProbe.setReportIntervalTicks(ticks);
						System.setProperty("mmtr.probe.interval", Integer.toString(org.mtr.core.mmtr.probe.MmtrProbe.getReportIntervalTicks()));
						changed = true;
					} else if (token.startsWith("--warnMs=")) {
						org.mtr.core.mmtr.probe.MmtrProbe.setWarnMillis(parseLong(token.substring("--warnMs=".length()), org.mtr.core.mmtr.probe.MmtrProbe.getWarnMillis()));
						changed = true;
					} else if (token.startsWith("--file=")) {
						org.mtr.core.mmtr.probe.MmtrProbe.setFile(token.substring("--file=".length()));
						changed = true;
					}
				}
				if (action.equals("interval") && !changed) {
					org.mtr.core.mmtr.probe.MmtrProbe.setReportIntervalTicks((int) parseLong(parts.length >= 3 ? parts[2] : "", 100));
					changed = true;
				}
				simulator.mmtrCommandResult("[probe] 开=" + org.mtr.core.mmtr.probe.MmtrProbe.isEnabled()
					+ " 每=" + org.mtr.core.mmtr.probe.MmtrProbe.getReportIntervalTicks() + " tick"
					+ " 慢帧门槛=" + org.mtr.core.mmtr.probe.MmtrProbe.getWarnMillis() + " ms"
					+ " 明细文件=" + (org.mtr.core.mmtr.probe.MmtrProbe.getFile().isEmpty() ? "（关）" : org.mtr.core.mmtr.probe.MmtrProbe.getFile())
					+ (changed ? "  —— 已按本条指令调整" : ""));
			}
			default -> simulator.mmtrCommandResult("[probe] 用法: probe on | probe off | probe status | probe dump | probe reset"
				+ " | probe interval <tick> | probe --warnMs=<ms> | probe --file=<路径|空>"
				+ "（启动参数同名：-Dmmtr.probe=true -Dmmtr.probe.interval=100 -Dmmtr.probe.warnMs=40 -Dmmtr.probe.file=…）");
		}
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
