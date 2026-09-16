package org.mtr.core.mmtr.command;

import org.mtr.core.mmtr.signal.MmtrSectionService;
import org.mtr.core.mmtr.signal.MmtrJunctionState;
import org.mtr.core.mmtr.signal.MmtrSignalRegistry;
import org.mtr.core.simulation.Simulator;

import java.util.List;
import java.util.Map;

/**
 * {@code signal why <x> <y> <z>}：把"这盏灯为什么显示这个状态"完整摊开。
 *
 * <h3>为什么需要它</h3>
 * <p>灯的状态是**推**出来的：灯 → 它保护的轨 → 它开始的区间 → 区间链上第一个被占用的区间。
 * 任何一环出问题，现象都一样——"显示得不对"——但从外面看不出是哪一环。实测就是这么卡住的：
 * 用户说 {@code -38,-60,-235} 该双黄却是绿，而接口、游戏端、地图三处给的答案完全一致，
 * 因为三处本来就用同一段推算；问题在推算的输入（区间走歪了 / 查灯差了一格），不在显示。</p>
 *
 * <p>所以这条指令把中间量全部打出来：查到哪盏灯、保护的哪根轨、区间有多少段多长、
 * 每一段的占用与否、后继区间是谁、为什么在这里停住。这样"为什么绿"是一句能核对的话，
 * 而不是又一次猜测。</p>
 */
final class MmtrSignalWhy {

	private MmtrSignalWhy() {
	}

	/** 当前查询用的模拟器（{@link #ownersOn} 要用它的占用树与轨索引）。 */
	private static Simulator active;

	/** 链上最多看几段（与显示层的 3 段一致，多给两段用来看"再往前是什么"）。 */
	private static final int CHAIN_LIMIT = 5;

	/**
	 * 落在 {@code span} 上的车辆 id 列表（诊断用）。
	 *
	 * <p>找不到占用者、却仍然判"占用"时，问题一定在占用数据本身（不是区间走歪），
	 * 这个列表就是用来区分这两种情况的：<b>有 id</b> = 真的停着车；<b>空</b> = 占用树里有幽灵足迹。</p>
	 */
	private static String ownersOn(MmtrSectionService.RailSpan span) {
		if (active == null) {
			return "";
		}
		final var occupancyTrees = active.mmtrOccupancyTrees();
		final org.mtr.core.data.Rail rail = active.rails.stream().filter(r -> r.getHexId().equals(span.railHex)).findFirst().orElse(null);
		if (occupancyTrees == null || rail == null) {
			return "";
		}
		final org.mtr.core.data.Position[] ordered = rail.mmtrOrderedPositions();
		if (ordered == null || ordered.length < 2) {
			return "";
		}
		final StringBuilder out = new StringBuilder();
		/*
		 * 先无条件打一句"这个 span 解析到了哪条轨、它的有序端点是哪两个"。
		 *
		 * <p>这件事**必须**打出来：区间的 span 只带一个 hex，而"这根轨到底是哪一根"要看
		 * {@code mmtrOrderedPositions()}。手工拿 hex 去反解坐标在本项目里错过两次（把两根轨的 hex
		 * 认成一根），而错了之后看到的每一句日志都是假的 —— 直接让代码报出它自己解析的结果。</p>
		 */
		out.append("[轨] hex=").append(span.railHex).append(" 有序端点=(")
			.append(ordered[0].getX()).append(',').append(ordered[0].getY()).append(',').append(ordered[0].getZ())
			.append(")→(").append(ordered[1].getX()).append(',').append(ordered[1].getY()).append(',').append(ordered[1].getZ())
			.append(')');
		for (int i = 0; i < occupancyTrees.size(); i++) {
			final org.mtr.core.data.VehiclePosition position = org.mtr.core.mmtr.signal.MmtrSectionService.footprintOn(occupancyTrees.get(i), ordered);
			if (position == null) {
				continue;
			}
			final double overlap = position.getClosestOverlap(span.arcFromM, span.arcToM, false, 0);
			if (overlap >= 0) {
				if (out.length() > 0) {
					out.append(", ");
				}
				// 足迹的归属车 id 与它在这根轨上占的弧段：
				// "占用"是按区间与足迹**有任何重叠**判的，所以车骑在边界上会同时压住前后两段；
				// 把足迹的弧段打出来，才能分清"车真的停在这段里"和"车只是压到了边界"。
				out.append(position.footprintIds()).append(" 占弧 ").append(footprintArcs(position));
				out.append(" 与本段重叠=").append(overlaps(position, span));
			}
		}
		return out.toString();
	}

	static MmtrCommandDispatcher.Result explain(Simulator simulator, List<String> positional, Map<String, String> options) {
		if (positional.size() < 3) {
			return MmtrCommandDispatcher.usage("signal why 需要三格坐标：signal why <x> <y> <z>（y 可以是轨道那一格或它的上一格）");
		}
		final int x;
		final int y;
		final int z;
		try {
			x = Integer.parseInt(positional.get(0).trim());
			y = Integer.parseInt(positional.get(1).trim());
			z = Integer.parseInt(positional.get(2).trim());
		} catch (NumberFormatException e) {
			return MmtrCommandDispatcher.usage("坐标必须是整数：signal why <x> <y> <z>");
		}

		final MmtrSectionService blocks = simulator.mmtrSections;
		final var trees = simulator.mmtrOccupancyTrees();
		active = simulator;
		/*
		 * 结果对象在最后一次性构造：Result 的 ok 是 final，而这里"这盏灯能不能参与闭塞"要走到
		 * 三个环节（登记 / 保护的轨 / 区间）才知道。先攒行、后定型，比中途到处 return 更不容易漏掉信息——
		 * 这条指令的价值恰恰在于**把失败的那一环也打出来**。
		 */
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "signal", "why");

		result.line("查询坐标 (" + x + ", " + y + ", " + z + ")");
		// 登记表按"两格高的灯"两个格子都可能存在条目，先把这件事说清楚
		final MmtrSignalRegistry.SignalEntry exact = simulator.mmtrSignals.get(x, y, z);
		final MmtrSignalRegistry.SignalEntry below = simulator.mmtrSignals.get(x, y - 1, z);
		final MmtrSignalRegistry.SignalEntry near = simulator.mmtrSignals.getNear(x, y, z);
		/*
		 * 让接下来的 refresh 顺手记下**这盏灯**的选腿明细（见 MmtrSectionService#recordLegTraceFor）。
		 * 按灯键记录是必须的：refresh 会重建全部灯，不指定目标的话记下来的是"最后一盏"的明细，
		 * 拿去解释这盏灯就会把人引到错的灯上。
		 */
		MmtrSectionService.recordLegTraceFor(near == null ? "" : MmtrSignalRegistry.key(near.x, near.y, near.z));
		if (exact == null && below == null) {
			result.line("这一格（以及下面一格）都没有登记过信号灯。");
			result.line("用 signal list 看看登记了哪些；世界里新放的灯要先扫描（world scan-signals）才会登记。");
			result.line("（登记表里共 " + simulator.mmtrSignals.signals.size() + " 盏灯；注意坐标用方块坐标，不带小数。）");
			return failed(result);
		}
		result.line("登记情况：本格=" + (exact == null ? "无" : describe(exact)) + "；下面一格=" + (below == null ? "无" : describe(below)));
		if (exact != null && below != null) {
			result.line("注意：这盏灯在两个格子上都有条目（MTR 的灯方块两格高，扫描会把两格都记下）——");
			result.line("      两格的朝向若不同，就是**两盏不同的灯**（一个方向一架），不要当成重复登记删掉。");
		}

		final String key = MmtrSignalRegistry.key(near.x, near.y, near.z);
		result.add(key);

		// ① 保护的轨与方向
		// 先报人工绑定本身：这条最容易被静默忽略（hex 认不出 / 离灯太远），而现象只是"颜色不对"。
		final MmtrSignalRegistry.SignalEntry boundEntry = near;
		if (boundEntry != null && !boundEntry.rails.isEmpty()) {
			result.line("①[人工绑定] 登记 " + boundEntry.rails.size() + " 条轨（模式=" + boundEntry.mode + "）：");
			for (final String hex : boundEntry.rails) {
				result.line("     " + hex);
			}
		}
		final MmtrSectionService.ProtectedRail protectedRail = blocks.resolveProtectedRail(near);
		if (boundEntry != null && !boundEntry.rails.isEmpty()) {
			/*
			 * 注意顺序：`protectedRailsOf` 自己会 refresh，而 refresh 会把"目标灯的记录"重置掉
			 * （见 MmtrSectionService#recordLegTraceFor），所以**先**取它的返回值，
			 * **再**读 lastBindReject —— 反过来读到的就会是别的灯留下的（或者空的）。
			 */
			final var effective = blocks.protectedRailsOf(boundEntry);
			final String rejected = MmtrSectionService.lastBindReject();
			result.line("     → 实际生效 " + effective.size() + " 条" + (effective.isEmpty()
				? "（**绑定被忽略了**，下面按自动推断继续看）"
				: "：" + String.join(", ", effective)));
			if (!rejected.isEmpty()) {
				result.line(rejected);
			}
		}
		if (protectedRail == null) {
			result.line("① 保护的轨：**没有**（这盏灯方向上找不到它该保护的轨：朝向与附近轨的走向不一致，或离轨太远）。");
			result.line("   这种灯不会参与闭塞，显示层只能回退到逐轨链（或显示未知）。");
			result.line("   下面把候选轨的打分列出来——多半是「灯站在这条轨的末端、且前方已无轨」这一类位置问题。");
			for (final String line : blocks.describeProtectedRailCandidates(near)) {
				result.line("   " + line);
			}
			return failed(result);
		}
		result.line("① 保护的轨 " + shortHex(protectedRail.rail.getHexId())
			+ "（长 " + Math.round(protectedRail.rail.railMath.getLength()) + " m），"
			+ "起点弧 " + Math.round(protectedRail.arcM) + " m，朝向 (" + Math.round(protectedRail.headingX * 100) / 100.0
			+ ", " + Math.round(protectedRail.headingZ * 100) / 100.0 + ")");
		/*
		 * 把"灯离这条轨多远、投影落在哪、区间往哪边走"也打出来。
		 *
		 * <p>为什么必须看这个：灯常常**不**在它所守的轨旁边（站在节点另一侧、隔着两格），
		 * 于是"投影落在弧 0 还是弧末端"就是决定性的一步 —— 它决定区间朝哪个方向走。
		 * 只报一个"起点弧 0"看不出这件事是**量出来的**还是**被夹到端点的**，而两者结果完全相反。</p>
		 */
		final org.mtr.core.data.Position[] railEnds = protectedRail.rail.mmtrOrderedPositions();
		if (railEnds != null && railEnds.length >= 2) {
			final double lampCentreX = near.x + 0.5;
			final double lampCentreZ = near.z + 0.5;
			final double dx = protectedRail.rail.railMath.getPosition(protectedRail.arcM, false).x() - lampCentreX;
			final double dz = protectedRail.rail.railMath.getPosition(protectedRail.arcM, false).z() - lampCentreZ;
			result.line("   投影点离灯 " + Math.round(Math.sqrt(dx * dx + dz * dz) * 100) / 100.0 + " 格；轨的两端 ("
				+ railEnds[0].getX() + "," + railEnds[0].getZ() + ") 与 (" + railEnds[1].getX() + "," + railEnds[1].getZ()
				+ ")；灯在 (" + lampCentreX + "," + lampCentreZ + ")");
			result.line("   区间从弧 " + Math.round(protectedRail.arcM) + " 开始，" + travelDirectionText(protectedRail) + "（走到哪一端由几何定，不看灯的朝向）");
		}
		// 选轨的打分明细：绑错了轨是"灯显示不对"的头号原因，明细放在这里才有得核对
		for (final String line : blocks.describeProtectedRailCandidates(near)) {
			result.line("   " + line);
		}

		// ② 它开始的区间，以及区间链上的占用情况
		final MmtrSectionService.Section section = blocks.sectionOfSignal(key);
		if (section == null) {
			result.line("② 它开始的区间：**没有**（这条走行没能走出一段区间：可能空走、也可能是被前一架灯的区间吸收了）。");
			result.line("   这盏灯因此没有可推算的状态，显示层会回退到逐轨链或显示未知。");
			return failed(result);
		}
		/*
		 * **一盏灯可能守多条腿**（道岔旁边朝某侧的灯：那个半平面里的腿全归它管），每条腿各成一段区间，
		 * 显示层取其中最不利的一条。
		 *
		 * <p>这里以前只打印 {@code sectionOfSignal} 的**第一条**，于是发生了一件很难查的事：
		 * 明细里明明写着"第 1 段没占用"，灯却是红的 —— 因为红是因为**另一条腿**占了。
		 * 用户报的"web 灯色和游戏对不上"有好几次就是在这种"只报一条腿"的日志里绕不出来。
		 * 现在每条腿各打印一节，并在 ③ 之前把结论按全部腿复算一遍。</p>
		 */
		final var allSections = blocks.sectionsOfSignal(key);
		if (allSections.size() > 1) {
			result.line("②[多腿] 这盏灯守 " + allSections.size() + " 条腿，各成一段区间；显示层取**最不利**的一条：");
			for (final MmtrSectionService.Section other : allSections) {
				result.line("     · id=" + other.id + "  长=" + Math.round(other.lengthM()) + " m"
					+ "  占用=" + (blocks.isOccupied(other, trees) ? "**是**" : "否")
					+ "  段数=" + other.spans.size()
					+ "  链深=" + depthAt(blocks, other, trees)
					// 被道岔切断的腿**不是进路**：它不算进颜色（见 MmtrSectionService.Section#blockedAtDeparture）
					+ (other.blockedAtDeparture ? "   ← 这一条是**道岔禁行侧**：不是进路，不参与灯色" : ""));
				// 每段展开：占用的最后一步是"这根轨上有谁的车"，看不到它就只能猜
				for (final MmtrSectionService.RailSpan span : other.spans) {
					final String owners = ownersOn(span);
					result.line("         span 轨" + span.railHex + " 弧 " + Math.round(span.arcFromM) + ".."
						+ Math.round(span.arcToM) + " m  占用者=" + (owners.isEmpty() ? "无" : owners));
				}
			}
		}

		MmtrSectionService.Section walk = section;
		for (int depth = 1; depth <= CHAIN_LIMIT && walk != null; depth++) {
			final boolean occupied = blocks.isOccupied(walk, trees);
			final String restrictedNodes = restrictedNodesOf(walk);
			final boolean restricted = !restrictedNodes.isEmpty();
			final java.util.List<String> restrictedNodeList = restrictedNodeKeys(walk);
			final boolean selfLamp = walk.id.equals(key);
			result.line("②[" + depth + "] " + (selfLamp ? "本灯区间" : "第 " + depth + " 段")
				+ " id=" + walk.id
				+ "  段数=" + walk.spans.size()
				+ "  长=" + Math.round(walk.lengthM()) + " m"
				+ "  占用=" + (occupied ? "**是**" : "否")
				+ "  出口灯=" + (walk.exitSignalKey == null || walk.exitSignalKey.isEmpty() ? "（无 → 走到头）" : walk.exitSignalKey));
			/*
			 * 受限节点（④ 的"清不掉的岔口"：净空被占 / 道岔未定）与占用**同等**地把深度钉在这一层。
			 * 这一条很容易被漏掉：只按"占用"复算时，明明看到"本段没占用、下一段占了"，
			 * 引擎却报红 —— 差别就在这里（实测就被这一点挡了一轮）。
			 */
			result.line("      受限节点=" + (restricted ? "**" + restrictedNodes + "**（与占用同等，深度钉在本层）" : "无"));
			if (restricted) {
				// "为什么红"必须一步到位：把每个受限节点**为什么**清不掉打出来（净空被占 / 岔口没人决定）
				for (final String nodeKey : restrictedNodeList) {
					result.line("        原因 " + nodeKey + "：" + restrictionReason(nodeKey));
				}
			}
			result.line("      结束原因：" + walk.endReason);
			// 占用来自哪一段、哪辆车：这是"为什么红/黄"的最后一步，不看到它就只能猜
			for (final MmtrSectionService.RailSpan span : walk.spans) {
				final String owners = ownersOn(span);
				result.line("      span 轨" + shortHex(span.railHex) + " 弧 " + Math.round(span.arcFromM) + ".." + Math.round(span.arcToM)
					+ " m（长 " + Math.round(span.lengthM()) + " m，方向 " + Math.round(span.headingX) + "," + Math.round(span.headingZ) + "）"
					+ "  占用者=" + (owners.isEmpty() ? "无" : owners));
			}
			if (depth <= 3) {
				result.line("      → 显示层读到 depth=" + depth + " 就会给出 "
					+ (depth == 1 ? "红" : depth == 2 ? "单黄" : "双黄") + (occupied ? "（本段占用）" : ""));
			}
			// 链接判据：链是按"结束节点 / 轨中间首尾相接"连的，把这两个事实打出来，
			// 断了的时候才知道该往哪儿看（名字对不上是断链最常见的原因）。
			final MmtrSectionService.Section next = blocks.following(walk);
			result.line("      本段起点节点=" + (walk.entryNodeKeyOrNull() == null ? "（未知）" : walk.entryNodeKeyOrNull())
				+ "  结束节点=" + (walk.endNodeKey == null ? "（未知）" : walk.endNodeKey)
				+ "  下一段=" + (next == null ? "**无（链到此为止）**" : next.id));
			walk = next;
		}
		if (walk == null) {
			result.line("②[链到此为止] 第 " + (CHAIN_LIMIT + 1) + " 段及以后：无（走行结束或回到起点）。");
		}

		/*
		 * ③ 三段的结论：**与显示层同一条规则** —— 一灯多腿取最不利，但**被道岔切断的腿不算进路**
		 * （它压根走不进去，不参与灯色）；一条能走的腿都没有时给红。
		 */
		int worstSeverity = 0;
		boolean anyRoute = false;
		int shownDepth = 0;
		for (final MmtrSectionService.Section candidate : allSections.isEmpty() ? java.util.List.of(section) : allSections) {
			if (candidate == null || candidate.blockedAtDeparture) {
				continue;
			}
			anyRoute = true;
			final int candidateDepth = depthAt(blocks, candidate, trees);
			final int severity = candidateDepth == 1 ? 4 : candidateDepth == 2 ? 2 : candidateDepth == 3 ? 3 : 1;
			if (severity > worstSeverity) {
				worstSeverity = severity;
				shownDepth = candidateDepth;
			}
		}
		final int depth = anyRoute ? shownDepth : 1;
		/*
		 * 选腿明细（MMTR_DIAG_LEGS=<灯键> 时才有内容）。
		 *
		 * 为什么需要它：一盏灯"守错了轨"从外面只能看到颜色不对，而选腿是一串判据的合取
		 * （腿数 → 前方余长 → 方向点积 → 端距），任何一项把对的腿挡掉、或者留下一条退化腿，
		 * 现象都只是"这盏灯颜色不对"。实测 depot test1 就是：那盏灯有区间、但守的是一条
		 * 前方余长 0 m 的腿，光看结论无法判断是哪一步出的错。
		 */
		final String legTrace = MmtrSectionService.lastLegTrace();
		if (!legTrace.isEmpty()) {
			result.line(legTrace);
		}
		final String bindReject = MmtrSectionService.lastBindReject();
		if (!bindReject.isEmpty()) {
			result.line(bindReject);
		}
		result.line("③ 结论：深度 " + depth + " → " + aspectName(depth)
			+ "（0=绿 1=红 2=单黄 3=双黄；0 表示链上前三段都没有占用）");
		result.line("   " + blocks.describeChainStats());
		if (depth == 0) {
			result.line("   若游戏里显示的是黄/红，那问题就在**上文哪一段走歪了**：核对 ② 里各段的出口灯与段数，"
				+ "看链是不是提前断掉（出口灯为空、或走到了别的方向）。");
		}
		return result;
	}

	/**
	 * 把已经攒好的行搬进一个 {@code ok=false} 的结果里。
	 *
	 * <p>为什么需要这个搬运：{@code Result.ok} 是 final，而"失败"是在攒了几行之后才知道的。
	 * 第一版直接 {@code return new Result(false, …)}，把那几行全丢了 —— 于是最需要诊断的灯
	 * （绑不上轨的那些）回一句空话，反而更难查（实测被这一点挡了一轮）。</p>
	 */
	private static MmtrCommandDispatcher.Result failed(MmtrCommandDispatcher.Result collected) {
		final MmtrCommandDispatcher.Result out = new MmtrCommandDispatcher.Result(false, "signal", "why");
		out.lines.addAll(collected.lines);
		return out;
	}

	/**
	 * 该轨上足迹占用的弧段（诊断用），形如 {@code [3..15]}；读不到时返回 {@code ?}。
	 *
	 * <p>用的是 {@code segmentsExcluding(0)}（0 = 不排除任何车），它给出这份足迹的原始区间。</p>
	 */
	private static String footprintArcs(org.mtr.core.data.VehiclePosition position) {
		try {
			final StringBuilder out = new StringBuilder();
			for (final double[] segment : position.segmentsExcluding(0)) {
				if (out.length() > 0) {
					out.append(' ');
				}
				out.append('[').append(Math.round(segment[0])).append("..").append(Math.round(segment[1])).append(']');
			}
			return out.length() == 0 ? "?" : out.toString();
		} catch (Exception e) {
			return "?";
		}
	}

	/**
	 * 足迹与本段的重叠长度，以及"最少要重叠多少才算占用"的判据（诊断用）。
	 *
	 * <p>两个数放在一起，才能看出阈值是不是按预期在起作用：重叠 2 m 而阈值 1.5 m 时，2 m ≥ 1.5 m，
	 * **仍然算占用** —— 这正是"改了阈值却什么都没变"的原因，光看结论是看不出来的。</p>
	 */
	private static String overlaps(org.mtr.core.data.VehiclePosition position, MmtrSectionService.RailSpan span) {
		double best = 0;
		for (final double[] segment : position.segmentsExcluding(0)) {
			final double low = Math.max(span.arcFromM, Math.min(segment[0], segment[1]));
			final double high = Math.min(span.arcToM, Math.max(segment[0], segment[1]));
			best = Math.max(best, high - low);
		}
		return Math.round(best * 10) / 10.0 + "m";
	}

	/**
	 * 本段边界上"清不掉"的节点（诊断用），逗号分隔；空串 = 没有。
	 *
	 * <p>判据与显示层一致：{@code MmtrJunctionState.unclearedNodeKeys}（净空被占 / 道岔未定）。</p>
	 */
	private static String restrictedNodesOf(MmtrSectionService.Section section) {
		if (active == null) {
			return "";
		}
		final StringBuilder out = new StringBuilder();
		for (final String nodeKey : active.mmtrSections.boundaryNodeKeys(section)) {
			if (nodes().contains(nodeKey)) {
				if (out.length() > 0) {
					out.append(", ");
				}
				out.append(nodeKey);
			}
		}
		return out.toString();
	}

	/** 受限节点集合（每次查询重算一次：占用变了，受限也就变了）。 */
	private static it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<String> nodes() {
		return MmtrJunctionState.unclearedNodeKeys(active, active == null ? null : active.mmtrOccupancyTrees());
	}

	/** 这一段的边界节点里，哪些现在"清不掉"（有序，便于逐条打印原因）。 */
	private static java.util.List<String> restrictedNodeKeys(MmtrSectionService.Section section) {
		final java.util.List<String> out = new java.util.ArrayList<>();
		if (active == null) {
			return out;
		}
		final it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<String> uncleared = nodes();
		for (final String nodeKey : active.mmtrSections.boundaryNodeKeys(section)) {
			if (uncleared.contains(nodeKey)) {
				out.add(nodeKey);
			}
		}
		return out;
	}

	/** 某个受限节点**为什么**清不掉（净空被占 / 岔口没人决定）——"灯为什么红"的最后一步。 */
	private static String restrictionReason(String nodeKey) {
		if (active == null) {
			return "";
		}
		try {
			final String[] parts = nodeKey.split(",");
			final org.mtr.core.data.Position node = new org.mtr.core.data.Position(Long.parseLong(parts[0].trim()), Long.parseLong(parts[1].trim()), Long.parseLong(parts[2].trim()));
			final String reason = MmtrJunctionState.reason(active, node, active.mmtrOccupancyTrees());
			return reason.isEmpty() ? "（当下已清掉）" : reason;
		} catch (final RuntimeException e) {
			return "（节点键无法解析：" + nodeKey + "）";
		}
	}

	/** 区间沿哪边走的文字说明（诊断用）。 */
	private static String travelDirectionText(MmtrSectionService.ProtectedRail protectedRail) {
		final double headingX = protectedRail.headingX;
		final double headingZ = protectedRail.headingZ;
		if (Math.abs(headingX) >= Math.abs(headingZ)) {
			return headingX > 0 ? "东(+x) 走" : "西(-x) 走";
		}
		return headingZ > 0 ? "南(+z) 走" : "北(-z) 走";
	}

	/** 与 {@code MmtrSectionService.depthAt} 同规则的复算（故意重写一遍，用来互相印证）。 */
	/**
	 * 深度**直接用显示层那一份**（{@code MmtrSectionService.aspectDepthFor}）。
	 *
	 * <p>原来这里"故意重写一遍来互相印证"，结果它只沿**单条**链走（{@code following} 取第一支），
	 * 岔口另一支上的占用看不到 —— 实测 73 盏里 6 盏自相矛盾：接口/网页/游戏读的是单黄/双黄，
	 * 这条诊断却说绿。诊断输出骗人比没有诊断更坏（用户正是拿它来问"为什么是黄的"），
	 * 所以现在与权威口径**同源**，不再各算一遍。</p>
	 */
	private static int depthAt(MmtrSectionService blocks, MmtrSectionService.Section section, Object trees) {
		final it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<String> uncleared = nodes();
		return blocks.aspectDepthFor(section, castTrees(trees), uncleared::contains);
	}

	@SuppressWarnings("unchecked")
	private static it.unimi.dsi.fastutil.objects.ObjectArrayList<it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap<org.mtr.core.data.Position, it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap<org.mtr.core.data.Position, org.mtr.core.data.VehiclePosition>>> castTrees(Object trees) {
		return (it.unimi.dsi.fastutil.objects.ObjectArrayList<it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap<org.mtr.core.data.Position, it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap<org.mtr.core.data.Position, org.mtr.core.data.VehiclePosition>>>) trees;
	}

	/** 轨 hex 太长，日志里只放前 12 个字符（够区分，也能在完整输出里对上）。 */
	private static String shortHex(String hex) {
		return hex == null || hex.length() <= 12 ? String.valueOf(hex) : hex.substring(0, 12) + "…";
	}

	private static String aspectName(int depth) {
		return depth == 1 ? "RED（红）" : depth == 2 ? "SINGLE_YELLOW（单黄）" : depth == 3 ? "DOUBLE_YELLOW（双黄）" : "GREEN（绿）";
	}

	private static String describe(MmtrSignalRegistry.SignalEntry entry) {
		return "朝向=" + entry.angle + "° 面数=" + entry.aspects + " 模式=" + entry.mode
			+ (entry.target == null || entry.target.isEmpty() ? "" : " 绑定轨=" + shortHex(entry.target));
	}
}
