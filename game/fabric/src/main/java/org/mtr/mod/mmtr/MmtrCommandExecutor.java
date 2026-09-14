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
		// 闭塞区间 v2 (S6): the 水闸区间 layer itself - one cell per lamp, plus the node assignment check.
		if (parts.length >= 1 && parts[0].equals("blocks")) {
			simulator.mmtrCommandResult(parts.length >= 2
				? org.mtr.core.mmtr.signal.MmtrDirectionalBlockReport.describeNode(simulator, parts[1])
				: org.mtr.core.mmtr.signal.MmtrDirectionalBlockReport.describeBlocks(simulator));
			return;
		}
		// 闭塞区间 v2 (S2): the directional lamp-to-lamp sections, next to the v1 report.
		if (parts.length >= 1 && parts[0].equals("blocks-v2")) {
			simulator.mmtrCommandResult(parts.length >= 2 && !parts[1].equals("all")
				? org.mtr.core.mmtr.signal.MmtrDirectionalBlockReport.describe(simulator, parts[1])
				: org.mtr.core.mmtr.signal.MmtrDirectionalBlockReport.describeAll(simulator));
			return;
		}
		// 闭塞区间 v2 (S4, observable before it is wired): what every lamp WOULD show under the v2 rule.
		if (parts.length >= 1 && parts[0].equals("lamps-v2")) {
			// `var`, not an explicit ObjectArrayList: the engine jar ships its own relocated fastutil
			// (org.mtr.libraries.*), so naming the type here would clash with the game's copy.
			final var restricted = org.mtr.core.mmtr.signal.MmtrJunctionState.unclearedNodeKeys(simulator, null);
			final var lines = simulator.mmtrDirectionalBlocks.describeLampAspects(null, restricted::contains);
			simulator.mmtrCommandResult(String.join("\n", lines));
			return;
		}
		// 占用转储: whose footprint is on a rail right now (why a train is "blocked ahead").
		if (parts.length >= 2 && parts[0].equals("occ")) {
			simulator.mmtrCommandResult(String.join("\n", simulator.mmtrDirectionalBlocks.describeOccupancy(parts[1])));
			return;
		}
		// B3 闭塞区间诊断: blocks [all|<railHex>] - 哪些轨被灯切成多段、每架灯绑到哪根轨。
		if (parts[0].equals("blocks")) {
			simulator.mmtrCommandResult(parts.length >= 2 && !parts[1].equals("all")
				? org.mtr.core.mmtr.signal.MmtrBlockReport.describe(simulator, parts[1])
				: org.mtr.core.mmtr.signal.MmtrBlockReport.describeAll(simulator));
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
		// 诊断开关: trace [on|off|status] - 每 tick 的走行/同步日志（默认关）
		if (parts[0].equals("trace")) {
			executeTraceCommand(simulator, parts);
			return;
		}
		simulator.mmtrCommandResult("未知指令: " + command + " (支持: signals scan | interlock <id>|all | blocks [all|<railHex>] | blocks-v2 [all|<railHex>] | lamps-v2 | changeends <id> | cab <id> <A|B|out> | doors <id> [open|close|toggle] [left|right|both] | shunt <id> <targetRailHex|off> [minutes] [kmh] [SUBTYPE] | couple <initiatorId> <targetId> | uncouple <id> <cutAfterCarIndex> | trace [on|off])"
			+ "（这些也都能用名词打头的写法从网页指令栏发：train doors <id> open / train couple <a> <b> / …）");
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
	 * Scan currently loaded chunks for placed MTR signal light block entities and register them
	 * as AUTO entries (upsert). Entries already BOUND to a rail stay untouched.
	 *
	 * <h3>为什么还要**删**（用户实测："有几个信号灯我已经敲掉了，地图不正确"）</h3>
	 * <p>原来这里是**只加不删**的：灯被敲掉之后登记表里那一条还在，于是地图上永远画着一盏
	 * 已经不存在的灯。扫描是唯一能看到"世界里到底还有没有这盏灯"的地方（只有游戏端能枚举已加载
	 * 区块），所以删除也只能在这里做。</p>
	 *
	 * <h3>删除的安全边界（不然会误删）</h3>
	 * <p>只能删**所在区块已加载、但区块里没有它**的条目。玩家走远之后区块会卸载，那时"找不到"
	 * 只说明没加载，不代表灯没了 —— 按"没找到就删"会把远处的灯全部清掉，那是灾难性的。</p>
	 */
	private static void scanSignals(Simulator simulator, ServerWorld serverWorld) {
		int found = 0;
		int added = 0;
		int skippedBound = 0;
		int removed = 0;
		int removedBound = 0;
		final World world = new World(serverWorld);
		// 本次扫描覆盖到的区块（按区块坐标），以及在这些区块里真正看到的灯格
		final java.util.Set<Long> scannedChunks = new java.util.HashSet<>();
		final java.util.Set<String> seen = new java.util.HashSet<>();
		for (final WorldChunk chunk : MmtrChunkTracker.loadedChunks(serverWorld)) {
			scannedChunks.add(chunkKey(chunk.getPos().x, chunk.getPos().z));
			// 先把这一块的方块实体抄一份再遍历：下面会在遍历中改登记表，而直接迭代原集合时序上更脆
			final java.util.List<BlockEntity> blockEntities = new java.util.ArrayList<>(chunk.getBlockEntities().values());
			for (final BlockEntity blockEntity : blockEntities) {
				final BlockPos pos = blockEntity.getPos();
				final BlockState blockState = world.getBlockState(new org.mtr.mapping.holder.BlockPos(pos.getX(), pos.getY(), pos.getZ()));
				final Object block = blockState.getBlock().data;
				if (!MmtrSignalBlocks.isSignalLight(block)) {
					continue;
				}
				found++;
				// 一盏灯方块两格高，扫描会把两格都记下 —— 两格都算"看到过"，
				// 否则删除那一步会把另一格误判成"灯没了"。
				seen.add(org.mtr.core.mmtr.signal.MmtrSignalRegistry.key(pos.getX(), pos.getY(), pos.getZ()));
				seen.add(org.mtr.core.mmtr.signal.MmtrSignalRegistry.key(pos.getX(), pos.getY() - 1, pos.getZ()));
				final SignalEntry existing = simulator.mmtrSignals.get(pos.getX(), pos.getY(), pos.getZ());
				if (existing != null && "BOUND".equals(existing.mode)) {
					skippedBound++;
					continue;
				}
				if (simulator.mmtrSignalOp(pos.getX(), pos.getY(), pos.getZ(), BlockSignalBase.getAngle(blockState), MmtrSignalBlocks.aspectsOf(block), "set", "")) {
					added++;
				}
			}
		}
		// 清掉"区块已加载、却不在世界里"的登记：这正是被玩家敲掉的灯
		final java.util.List<SignalEntry> stale = new java.util.ArrayList<>();
		for (final SignalEntry entry : simulator.mmtrSignals.signals.values()) {
			if (seen.contains(org.mtr.core.mmtr.signal.MmtrSignalRegistry.key(entry.x, entry.y, entry.z))) {
				continue;
			}
			if (!scannedChunks.contains(chunkKey(entry.x >> 4, entry.z >> 4))) {
				continue; // 区块没加载：判断不了，留着
			}
			stale.add(entry);
		}
		for (final SignalEntry entry : stale) {
			final boolean bound = "BOUND".equals(entry.mode);
			if (simulator.mmtrSignalRemove(entry.x, entry.y, entry.z)) {
				removed++;
				if (bound) {
					removedBound++;
				}
			}
		}
		simulator.mmtrCommandResult("[signals] 扫描完成: 找到 " + found + " 个信号灯, 新增 " + added
			+ " 个 AUTO 条目, 跳过 BOUND " + skippedBound + " 个, 清理已拆掉的 " + removed + " 个"
			+ (removedBound > 0 ? "（其中 " + removedBound + " 个是人工绑定：世界里的灯没了，绑定一并移除）" : "")
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
