package org.mtr.core.mmtr;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Vehicle;
import org.mtr.core.mmtr.consist.MmtrConsistWalker;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionPosition;
import org.mtr.core.simulation.Simulator;

/**
 * MMTR (L3, slice 6): run planner for live Motion-Core vehicles — the engine-side counterpart of the
 * E2E yard test discovery, promoted to a service the task/ops layer calls for MOVE_TO-style steps.
 * Given a motion vehicle and a target real rail, it plans a graph route from the vehicle's current
 * position to a stop ON the target rail, decides the operator setting for every en-route turnout that
 * keeps the vehicle on the route (the same straightest/diverging cos ranking the walker and
 * MmtrNodeRouter use — the -96-style forks stay unset and live), and computes the cumulative stop
 * distance in the vehicle's own walker-distance space. Ops/tasks then simply arm
 * {@code setMmtrMotionAuto(true)} + {@code setMmtrMotionStopTarget(plan.stopCumulativeM, openDoors)}
 * and the vehicle runs itself there, forks elected exactly as planned.
 */
public final class MmtrRunPlanner {

	private static final class NodeRec {
		final Position from;
		final Rail rail;

		NodeRec(Position from, Rail rail) {
			this.from = from;
			this.rail = rail;
		}
	}

	public static final class Plan {
		public boolean feasible;
		public String reason = "unplanned";
		/** Node chain from the vehicle's current ahead node to the far end of the target rail. */
		public final ObjectArrayList<Position> nodes = new ObjectArrayList<>();
		/** Turnout operator settings: {nodeX, nodeY, nodeZ, viaHex, op} for every en-route fork. */
		public final ObjectArrayList<String[]> forkOps = new ObjectArrayList<>();
		/** Absolute walker-space distance of every {@link #forkOps} entry (parallel, same index) -
		 * the approach-locking layer requests a fork only once the train is near it. */
		public final ObjectArrayList<Double> forkMeters = new ObjectArrayList<>();
		/** Cumulative stop distance in the vehicle's walker space (head rests there). */
		public double stopCumulativeM = -1;
		public String targetRailHex = "";
		/**
		 * **站台锚点**：停车点所在的那根轨 + 它在轨内的比例（0 = 本方向的轨起点，1 = 远端）。
		 *
		 * <p>为什么光有 {@link #stopCumulativeM} 不够（notes/155 现场实测）：累计里程 = "自臂那一刻的位置 +
		 * **算出来的**进路长度"。算出来的东西会变 —— 让位后重规划、岔位换了、进路绕了一条 ——
		 * 同一个站台的停车点就跟着漂：实测同一根站台轨两次自臂给出 <b>1298 m 与 1375 m</b> 两个停车点，
		 * 车于是穿过站台又往前开了 77 m 才开门（用户看到的就是"3 站以后不停站"）。</p>
		 *
		 * <p>锚点是**世界里的一个位置**（哪根轨、轨上多深处），重算多少遍都不动；累计里程退化成
		 * "刹车的粗略目标"，到位判据改用锚点。</p>
		 */
		public String stopRailHex = "";
		public double stopFraction = -1;
		/**
		 * 尽头换向 (terminal flip): absolute walker distance at which the run reaches the dead end
		 * of {@link #flipRailHex} - the vehicle stops there and changes ends (换端) before the plan
		 * continues to {@link #stopCumulativeM}. {@code -1} = the plan needs no flip.
		 */
		public double flipCumulativeM = -1;
		/** Hex of the dead-end rail the run must flip (换端) at; empty when no flip is planned. */
		public String flipRailHex = "";
		/**
		 * C10: every rail the run crosses, in order (the current rail first). A task-driven shunt uses
		 * this to authorise its WHOLE route, so S1 does not stop it on a rail another train occupies.
		 */
		public final ObjectArrayList<String> routeRailHexes = new ObjectArrayList<>();
		/**
		 * 这份计划**真的穿过了**的经由点（作业单给的 {@code viaNodes} 解析后、去掉"已越过"的那些）。
		 * 给日志与验收用：进路是否按作业单要求走了那条引入线，看这里，而不是靠推断。
		 */
		public final ObjectArrayList<Position> viaNodes = new ObjectArrayList<>();
	}

	private MmtrRunPlanner() {
	}

	/**
	 * Whether the walker can leave {@code node} on {@code desired} after arriving on {@code incoming}
	 * from {@code approachNode}: a node with a single forward rail needs no turnout decision, a real fork
	 * must offer {@code desired} among its drivable legs (a doubling-back leg is not one).
	 */
	private static boolean turnoutAllows(Simulator sim, Position node, @Nullable Position approachNode, @Nullable Rail incoming, Rail desired) {
		if (incoming == null || desired == incoming || approachNode == null) {
			return true;
		}
		final ObjectArrayList<Rail> forwards = forwardRails(sim, node, incoming);
		return forwards.size() < 2 || branchOperator(sim, approachNode, node, incoming, forwards, desired) >= 0;
	}

	/**
	 * The node the consist is really heading toward. A consist body's {@code aheadNode()} is the B side
	 * of its leading spine leg, which is only the travel direction when the train runs toward B; a train
	 * running A-end-first (the common yard parking: system key at the A cab) travels the other way, so
	 * its raw ahead/entry nodes are swapped. Planning from the wrong end made a parked locomotive's
	 * route start at the dead end of its own siding (实机 2026-09-09, aassdd).
	 *
	 * <p>公开（2026-09-16）：车辆侧的"停在岔口前按计划补申请"那条自救路也要用同一口径 ——
	 * 它原来读裸的 {@code aheadNode()}/{@code enteredFromPosition()}，车反向行驶时**问错了节点**，
	 * 于是 {@code legIndexForRail} 必然解不出腿、打出"计划要的腿不在岔口腿表里"的假警报
	 * （实测：日志指着 {@code -170,-60,-458}，而那处按 {@code query node} 只有度 2、根本不是岔口）。</p>
	 */
	public static @Nullable Position travelAheadNode(MmtrMotionPosition walker) {
		if (walker instanceof final MmtrConsistWalker consistWalker && !consistWalker.travelsTowardB()) {
			return walker.enteredFromPosition();
		}
		return walker.aheadNode();
	}

	/** The node the consist is really coming from (see {@link #travelAheadNode}). */
	public static @Nullable Position travelEntryNode(MmtrMotionPosition walker) {
		if (walker instanceof final MmtrConsistWalker consistWalker && !consistWalker.travelsTowardB()) {
			return walker.aheadNode();
		}
		return walker.enteredFromPosition();
	}

	/**
	 * Metres from the leading face to the node it is heading toward. A consist body's {@code offsetM()}
	 * is measured along the spine (A → B), so a train running A-end-first ({@code travelsTowardB() ==
	 * false}) has {@code offsetM()} metres LEFT, not travelled - using {@code length - offset} put the
	 * planned stop (and the 牵出—推进 reversal point) 2×offset too far down the line, which is why the
	 * real locomotive drove straight past its reversal point (实机 2026-09-09, aassdd).
	 *
	 * <p>Public since A3: the AWS trigger measures the distance to the signal it is about to pass in
	 * the same direction-aware space.</p>
	 */
	public static double remainingToAheadNodeM(MmtrMotionPosition walker) {
		if (walker instanceof final MmtrConsistWalker consistWalker && !consistWalker.travelsTowardB()) {
			return Math.max(0, consistWalker.offsetM());
		}
		return Math.max(0, walker.currentRailLengthM() - walker.offsetM());
	}

	/**
	 * Plans the run of {@code vehicle} to a stop on {@code targetRailHex}: {@code stopFraction} of the
	 * target rail's length from its entry end (1.0 = its far end). Infeasible when the vehicle is not
	 * in motion mode, the target rail is missing/unreachable, a needed turnout branch is not the
	 * walker's branch0/1 choice, or the stop lies at/before the vehicle's current position.
	 *
	 * <p>When the straight-ahead plan is infeasible (target requires reversing 掉头), a second
	 * attempt plans the run through a terminal flip (尽头换向): forward to the dead end of the
	 * single-continuation corridor ahead, change ends there, run back and continue to the target.</p>
	 */
	public static Plan planToRail(Simulator sim, Vehicle vehicle, String targetRailHex, double stopFraction) {
		final Plan forward = planToRailForward(sim, vehicle, targetRailHex, stopFraction);
		if (forward.feasible) {
			return forward;
		}
		final Plan viaFlip = planToRailViaDeadEndFlip(sim, vehicle, targetRailHex, stopFraction);
		if (viaFlip.feasible) {
			// A mission that cannot arm retries every tick (and several trains can be waiting at
			// once), so report at most one flip plan every few seconds: the unconditional print
			// flooded the log at thousands of lines a second.
			final long now = System.currentTimeMillis();
			if (now - mmtrLastFlipPlanLogMillis >= FLIP_PLAN_LOG_INTERVAL_MILLIS) {
				mmtrLastFlipPlanLogMillis = now;
				System.out.println("[MMTR-RUN] planned via 尽头换向 flip @" + Math.round(viaFlip.flipCumulativeM) + "m (rail " + viaFlip.flipRailHex + ") - " + forward.reason);
			}
			return viaFlip;
		}
		final Plan viaSetback = planToRailViaSetback(sim, vehicle, targetRailHex, stopFraction);
		if (viaSetback.feasible) {
			final long now = System.currentTimeMillis();
			if (now - mmtrLastFlipPlanLogMillis >= FLIP_PLAN_LOG_INTERVAL_MILLIS) {
				mmtrLastFlipPlanLogMillis = now;
				System.out.println("[MMTR-RUN] planned via 牵出—推进 setback @" + Math.round(viaSetback.flipCumulativeM) + "m (rail " + viaSetback.flipRailHex + ") - " + forward.reason);
			}
			return viaSetback;
		}
		// An unplannable movement is an operator-visible event (a task will fail), so report all three
		// attempts' reasons once: the forward search, the terminal flip and the setback search.
		System.out.println("[MMTR-RUN] no plan for " + targetRailHex + ": forward=" + forward.reason + " | flip=" + viaFlip.reason + " | setback=" + viaSetback.reason);
		return forward;
	}

	/**
	 * **带经由点（路径点）的规划**：进路必须**依次穿过** {@code viaNodeKeys} 里的图节点，再到目标轨。
	 *
	 * <h3>为什么需要它（2026-09-27 用户现场口径）</h3>
	 * <p>"在库与正线连接的那个立交上，列车不能在线上逆行，这可能也是导致列车卡死的原因，所以需要引入
	 * 路径点逻辑：所有回库列车都需要经过 {@code 106,65,1600} 点。"</p>
	 *
	 * <p>{@link #planToRailForward} 是**按跳数最短**的前向 BFS，它对"这条引入线是上行还是下行"一无所知：
	 * 咽喉处两条平行引入线都能到库房，BFS 就挑先够着的那条 —— 回库车于是顺着**出库方向**那条线逆向开进去
	 * （逆行）。实测（2026-09-27，10 ms 节拍探针）不带经由点的回库进路是"沿 1 道向东 480 m 再拐进出库引入线"，
	 * 而 1 道是**西行**方向、跑完最后一圈的车正沿它进站 ⇒ 同一根正线上的对头就是 22:37 起那 27 min
	 * 全网站住的直接成因（notes/329 §3）。</p>
	 *
	 * <p>经由点把这条**线路知识**交回作业单：先到经由点、再从经由点去目标，一段一段地前向 BFS，
	 * 拼成一条完整进路（岔位、停车点、里程都按同一条链算，见 {@link #planToRailForwardVia}）。</p>
	 *
	 * <p><b>已经越过的经由点不再要求</b>：车辆每 tick 都可能按当前位置重新自臂（{@code Vehicle#mmtrMotionSelfArmMission}），
	 * 若经由点被当成"永远要在前方"，车一过它下一次规划就失败。所以判据是"它还是不是**前方**的事"：
	 * 经由点若正好是车所在轨的一个端点（车正骑在这根轨上 —— 正接近它或刚越过它），即视为已达成。</p>
	 *
	 * <p>没有任何（剩余的）经由点时，**走的还是 {@link #planToRail(Simulator, Vehicle, String, double)}
	 * 那条原路** —— 不带经由点的步骤行为与改动前逐位相同。</p>
	 *
	 * @param viaNodeKeys 经由点，写法 {@code "x,y,z"}（按书写顺序依次经过）；{@code null}/空 = 不带经由点
	 */
	public static Plan planToRail(Simulator sim, Vehicle vehicle, String targetRailHex, double stopFraction, @Nullable ObjectArrayList<String> viaNodeKeys) {
		if (viaNodeKeys == null || viaNodeKeys.isEmpty()) {
			return planToRail(sim, vehicle, targetRailHex, stopFraction);
		}
		final MmtrMotionPosition viaWalker = vehicle.getMmtrMotionWalker();
		final Position viaAhead = viaWalker == null ? null : travelAheadNode(viaWalker);
		final Position viaEntry = viaWalker == null ? null : travelEntryNode(viaWalker);
		final ObjectArrayList<Position> vias = new ObjectArrayList<>();
		for (final String raw : viaNodeKeys) {
			final Position node = parseViaNode(sim, raw);
			if (node == null) {
				final Plan bad = new Plan();
				bad.targetRailHex = targetRailHex;
				bad.reason = "via: 经由点 " + raw + " 不是图上能唯一定位的节点（写法 {\"x,y,z\"}）";
				return bad;
			}
			if (node.equals(viaAhead) || node.equals(viaEntry)) {
				continue; // 车正骑在这根轨上：这个经由点已经（或正在）达成
			}
			vias.add(node);
		}
		if (vias.isEmpty()) {
			return planToRail(sim, vehicle, targetRailHex, stopFraction);
		}
		return planToRailForwardVia(sim, vehicle, targetRailHex, stopFraction, vias);
	}

	/**
	 * 经由点写法 {@code "x,y,z"} → 图上的节点。容错一条：**y 写错一档也认** ——
	 * 同一列上有 {@code y=-60} 与 {@code y=-59} 两条登记是本项目踩过的坑（{@code query node} 的注释里
	 * 就是为它写的）。于是先精确匹配 {@code (x,y,z)}；匹配不上再看同一 {@code (x,z)} 上是不是**只有一个**
	 * 节点：是就用它（并说明"按 x,z 解的"），有多个则**报错而不猜**（宁可这一步失败，也不要悄悄走错线）。
	 *
	 * @return 图上的节点；{@code null} = 写法不对或定位不到/有歧义
	 */
	@Nullable
	private static Position parseViaNode(Simulator sim, @Nullable String raw) {
		if (raw == null) {
			return null;
		}
		final String[] parts = raw.trim().split(",");
		if (parts.length != 3) {
			return null;
		}
		final long[] xyz = new long[3];
		for (int i = 0; i < 3; i++) {
			try {
				xyz[i] = Long.parseLong(parts[i].trim());
			} catch (NumberFormatException e) {
				return null;
			}
		}
		final Position exact = new Position(xyz[0], xyz[1], xyz[2]);
		if (sim.positionsToRail.containsKey(exact)) {
			return exact;
		}
		Position found = null;
		for (final Position candidate : sim.positionsToRail.keySet()) {
			if (candidate.getX() == xyz[0] && candidate.getZ() == xyz[2]) {
				if (found != null && !found.equals(candidate)) {
					return null; // 同一 (x,z) 上不止一个节点：说不出是哪一个，就不猜
				}
				found = candidate;
			}
		}
		return found;
	}

	/**
	 * 经由点版的**前向**进路：起点 → 经由1 → 经由2 → … → 目标轨的一个端点，逐段 BFS 后拼成一条链。
	 *
	 * <p>与 {@link #planToRailForward} 同一口径的三件事：</p>
	 * <ul>
	 *   <li><b>不许折返</b>：每一段的根节点上，不能向来时那根轨回头（车不会倒着开出咽喉）；</li>
	 *   <li><b>岔位</b>：链上每个度 ≥ 2 的节点都按有序腿表算一个操作号（与运行时
	 *       {@code MmtrForkElection} 同一份排序），算不出来就整条计划不可行 —— 绝不"到时候再说"；</li>
	 *   <li><b>里程</b>：从车当前位置的**剩余里程**起算，沿链把每根轨的长度累加，最后按停车比例扣掉目标轨的尾段。</li>
	 * </ul>
	 *
	 * <p>经由点本身**不停车**：它是"穿过"，不是"到站"—— 停车点永远在目标轨上，这一点与 {@link #planToRailForward}
	 * 完全一致（若在这里停下来，"回库"就变成两段调车，现场会多出一次停车）</p>
	 */
	private static Plan planToRailForwardVia(Simulator sim, Vehicle vehicle, String targetRailHex, double stopFraction, ObjectArrayList<Position> vias) {
		final Plan plan = new Plan();
		plan.targetRailHex = targetRailHex;
		final MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
		if (walker == null) {
			plan.reason = "via: vehicle is not in live Motion-Core mode";
			return plan;
		}
		final Rail target = findRail(sim, targetRailHex);
		if (target == null) {
			plan.reason = "via: target rail " + targetRailHex + " not found";
			return plan;
		}
		final Position startNode = travelAheadNode(walker);
		final Rail currentRail = findRail(sim, walker.railHex());
		if (startNode == null || currentRail == null) {
			plan.reason = "via: walker has no current rail / ahead node";
			return plan;
		}
		if (currentRail == target) {
			plan.reason = "via: target rail is the rail the vehicle is already on";
			return plan;
		}
		final Position[] targetEnds = railEndpoints(sim, target);
		if (targetEnds[0] == null || targetEnds[1] == null) {
			plan.reason = "via: target rail endpoints not in graph";
			return plan;
		}

		// ---- 逐段 BFS，拼一条从起点到目标轨远端的完整节点链（chain）+ 每跳用的轨（chainRails）----
		final ObjectArrayList<Position> chain = new ObjectArrayList<>();
		final ObjectArrayList<Rail> chainRails = new ObjectArrayList<>();
		chain.add(startNode);
		Position root = startNode;
		Rail rootArrival = currentRail;
		for (int stage = 0; stage <= vias.size(); stage++) {
			final boolean lastStage = stage == vias.size();
			// 这一段的根与"进来时那根轨"：段内的 lambda 要捕获它们，所以取成本段的 final 副本
			// （root/rootArrival 会随段推进而改，直接捕获是编译错误）。
			final Position stageRoot = root;
			final Rail stageArrival = rootArrival;
			final Object2ObjectOpenHashMap<Position, NodeRec> prev = new Object2ObjectOpenHashMap<>();
			final ObjectArrayList<Position> queue = new ObjectArrayList<>();
			queue.add(stageRoot);
			prev.put(stageRoot, new NodeRec(null, null));
			Position goal = null;
			while (!queue.isEmpty() && goal == null) {
				final Position node = queue.remove(0);
				if (lastStage ? node.equals(targetEnds[0]) || node.equals(targetEnds[1]) : node.equals(vias.get(stage))) {
					goal = node;
					break;
				}
				final Object2ObjectOpenHashMap<Position, Rail> neighbors = sim.positionsToRail.get(node);
				if (neighbors == null) {
					continue;
				}
				neighbors.forEach((other, rail) -> {
					// 不许折返：段根上不能向来时那根轨回头（与整段一次性 BFS 的"起点不许向来向折返"同一口径）。
					// 每一段单独清空的 prev 只保证"不重复访问节点"，回头这件事必须显式挡掉。
					if (node.equals(stageRoot) && rail == stageArrival) {
						return;
					}
					if (!prev.containsKey(other)) {
						prev.put(other, new NodeRec(node, rail));
						queue.add(other);
					}
				});
			}
			if (goal == null) {
				plan.reason = lastStage
					? "via: target rail " + targetRailHex + " is not reachable from " + nodeText(stageRoot)
					: "via: 经由点 " + nodeText(vias.get(stage)) + " 从 " + nodeText(stageRoot) + " 前向不可达（不许折返）";
				return plan;
			}
			// 重建这一段：goal → … → root（沿 prev 倒走），把除 root（已在链上）之外的节点按行车顺序接进链。
			final ObjectArrayList<Position> reversed = new ObjectArrayList<>();
			Position cursor = goal;
			while (cursor != null) {
				reversed.add(cursor);
				final NodeRec rec = prev.get(cursor);
				cursor = rec == null || rec.from == null ? null : rec.from;
			}
			for (int i = reversed.size() - 2; i >= 0; i--) {
				final Position node = reversed.get(i);
				final NodeRec rec = prev.get(node);
				if (rec == null || rec.rail == null) {
					plan.reason = "via: 进路重建失败（第 " + (stage + 1) + " 段）";
					return plan;
				}
				chain.add(node);
				chainRails.add(rec.rail);
			}
			if (lastStage) {
				chain.add(goal.equals(targetEnds[0]) ? targetEnds[1] : targetEnds[0]);
				chainRails.add(target);
			} else {
				plan.viaNodes.add(goal);
				root = goal;
				rootArrival = chainRails.get(chainRails.size() - 1);
			}
		}

		/*
		 * **同一根轨走了两次 ⇒ 这条"进路"是绕圈**（经由点已经在目标之后时，BFS 会这么干：从经由点找目标，
		 * 找到的是被经由点甩在身后的那一端，于是绕一圈再回来）。前向进路不许折返，一趟车正常也不会在同一根
		 * 轨上过两遍，所以直接判不可行 —— 宁可这一步失败并说清原因，也不要放一趟"绕圈"的车出去
		 * （现场那 25 分钟的互堵，正是"看着能走、其实走不通"的进路放出去的结果）。
		 */
		for (int i = 0; i < chainRails.size(); i++) {
			for (int j = i + 1; j < chainRails.size(); j++) {
				if (chainRails.get(i) == chainRails.get(j)) {
					plan.reason = "via: 进路绕圈（轨 " + chainRails.get(i).getHexId() + " 走了两次）—— 经由点很可能已在目标之后";
					return plan;
				}
			}
		}
		for (final Position viaNode : plan.viaNodes) {
			int occurrences = 0;
			for (final Position chainNode : chain) {
				if (chainNode.equals(viaNode)) {
					occurrences++;
				}
			}
			if (occurrences > 1) {
				plan.reason = "via: 经由点 " + nodeText(viaNode) + " 在进路上出现了 " + occurrences + " 次（它已在目标之后 ⇒ 只能绕圈）";
				return plan;
			}
		}

		// ---- 停车点：剩余里程 + 链上每根轨的长度 -（1 - 停车比例）× 目标轨长（与 planToRailForward 同一算式）----
		double fromCurrentToStartNode = remainingToAheadNodeM(walker);
		if (fromCurrentToStartNode < 0) {
			fromCurrentToStartNode = 0;
		}
		double plannedM = fromCurrentToStartNode;
		for (int i = 0; i < chainRails.size(); i++) {
			plannedM += chainRails.get(i).railMath.getLength();
		}
		final double viaClamp = Math.max(0.0, Math.min(1.0, stopFraction));
		plan.stopCumulativeM = walker.distanceM() + plannedM - (1.0 - viaClamp) * target.railMath.getLength();
		plan.stopRailHex = targetRailHex;
		plan.stopFraction = viaClamp;
		if (plan.stopCumulativeM <= walker.distanceM() + 1e-6) {
			plan.reason = "via: stop would lie at or behind the vehicle position";
			return plan;
		}

		// ---- 岔位：链上每个度 ≥ 2 的节点一个操作号（与 planToRailForward 同一口径）----
		double cumulM = fromCurrentToStartNode;
		for (int i = 0; i + 1 < chain.size(); i++) {
			final Position node = chain.get(i);
			final Position approach = i == 0 ? travelEntryNode(walker) : chain.get(i - 1);
			if (approach == null && i == 0) {
				plan.reason = "via: walker has no entry node for the first turnout";
				return plan;
			}
			final Rail incoming = i == 0 ? currentRail : chainRails.get(i - 1);
			final Rail desired = chainRails.get(i);
			final ObjectArrayList<Rail> forwards = forwardRails(sim, node, incoming);
			if (forwards.size() >= 2) {
				final int op = branchOperator(sim, approach, node, incoming, forwards, desired);
				if (op < 0) {
					plan.reason = "via: turnout at node requires a branch outside the walker's branch0/1 choice";
					return plan;
				}
				plan.forkOps.add(new String[]{String.valueOf(node.getX()), String.valueOf(node.getY()), String.valueOf(node.getZ()), incoming.getHexId(), String.valueOf(op)});
				plan.forkMeters.add(walker.distanceM() + cumulM);
			}
			cumulM += desired.railMath.getLength();
			plan.routeRailHexes.add(desired.getHexId());
		}
		plan.routeRailHexes.add(0, currentRail.getHexId());
		plan.nodes.addAll(chain);
		plan.feasible = true;
		plan.reason = "ok";
		return plan;
	}

	/** 节点的人话写法（日志用）。 */
	private static String nodeText(@Nullable Position node) {
		return node == null ? "（无）" : node.getX() + "," + node.getY() + "," + node.getZ();
	}

	/** How far short of the reversal node the train stops, so the walker does not cross onto the next rail. */
	private static final double SETBACK_EPS_M = 0.2;

	/** One search state: where the train is, which rail it arrived on, and whether it has reversed. */
	private static final class SetbackState {
		final Position node;
		final @Nullable Rail arrival;
		final int reversals;

		SetbackState(Position node, @Nullable Rail arrival, int reversals) {
			this.node = node;
			this.arrival = arrival;
			this.reversals = reversals;
		}

		String key() {
			return node.getX() + "," + node.getY() + "," + node.getZ() + "|" + (arrival == null ? "" : arrival.getHexId()) + "|" + reversals;
		}
	}

	/** How a {@link SetbackState} was reached: from where, along which rail, and by reversing there. */
	private static final class SetbackRec {
		final @Nullable SetbackState from;
		final @Nullable Rail rail;
		final boolean reversedHere;

		SetbackRec(@Nullable SetbackState from, @Nullable Rail rail, boolean reversedHere) {
			this.from = from;
			this.rail = rail;
			this.reversedHere = reversedHere;
		}
	}

	/**
	 * C10 牵出—推进 (pull out, then set back): when neither a straight run nor a terminal flip can reach
	 * the target, a real shunt reverses ONCE mid-route - the train runs past the junction into the lead,
	 * stops, changes ends and comes back into the branch that leads to the target. This is the normal way
	 * into a stub siding whose only entry faces the wrong way (实机 2026-09-09: the aassdd long track).
	 *
	 * <p>Search: BFS over (node, arrival rail, reversals used) with at most one reversal, where a
	 * reversal is a zero-length transition at a node onto a different rail (including back along the
	 * arrival rail). The plan is a normal plan plus {@code flipRailHex}/{@code flipCumulativeM} at the
	 * reversal point, so the existing vehicle-side flip handling stops the train there, changes ends and
	 * continues with the forks already preset for the second leg.
	 */
	private static Plan planToRailViaSetback(Simulator sim, Vehicle vehicle, String targetRailHex, double stopFraction) {
		final Plan plan = new Plan();
		plan.targetRailHex = targetRailHex;
		final org.mtr.core.mmtr.segment.MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
		if (walker == null) {
			plan.reason = "setback: vehicle is not in live Motion-Core mode";
			return plan;
		}
		final Rail target = findRail(sim, targetRailHex);
		final Rail currentRail = findRail(sim, walker.railHex());
		final Position startNode = travelAheadNode(walker);
		if (target == null || currentRail == null || startNode == null) {
			plan.reason = "setback: walker or target rail unavailable";
			return plan;
		}
		if (currentRail == target) {
			plan.reason = "setback: target is the rail the vehicle is already on";
			return plan;
		}

		final Object2ObjectOpenHashMap<String, SetbackRec> prev = new Object2ObjectOpenHashMap<>();
		final ObjectArrayList<SetbackState> queue = new ObjectArrayList<>();
		final SetbackState start = new SetbackState(startNode, currentRail, 0);
		prev.put(start.key(), new SetbackRec(null, null, false));
		queue.add(start);
		SetbackState goalFrom = null;
		Position goalFar = null;
		boolean goalReversed = false;
		while (!queue.isEmpty() && goalFrom == null) {
			final SetbackState state = queue.remove(0);
			final Object2ObjectOpenHashMap<Position, Rail> neighbors = sim.positionsToRail.get(state.node);
			if (neighbors == null) {
				continue;
			}
			final ObjectArrayList<SetbackState> nextStates = new ObjectArrayList<>();
			final ObjectArrayList<Rail> nextRails = new ObjectArrayList<>();
			final ObjectArrayList<Boolean> nextReversed = new ObjectArrayList<>();
			final Position approach = state.arrival == null ? null : otherEndOf(sim, state.node, state.arrival);
			neighbors.forEach((other, rail) -> {
				// A turnout only offers branches the walker can actually drive (a leg that doubles back
				// is a 人字 move and is not in the ordered legs). Validate every transition here, during
				// the search: aborting only after a route is reconstructed would reject the whole plan
				// even though a longer route - e.g. pull out past the fork and set back into it - is
				// perfectly drivable (实机 2026-09-09, the aassdd junction).
				if (rail != state.arrival && !turnoutAllows(sim, state.node, approach, state.arrival, rail)) {
					return;
				}
				if (rail != state.arrival) {
					nextStates.add(new SetbackState(other, rail, state.reversals));
					nextRails.add(rail);
					nextReversed.add(false);
				}
				if (state.reversals == 0 && !state.node.equals(startNode)) {
					// Reverse at this node (change ends) and depart along `rail` - which may be the rail
					// the train arrived on (backing out of the lead) or another branch at the junction.
					// Reversing AT THE START node is deliberately excluded: that is the "parked facing the
					// wrong way" case, which the mission handles by reversing the travel direction before
					// planning again - the forward search must never quietly route a parked train backwards
					// out of its own siding (see MmtrRunPlannerTests.plannerReachesTheYardRearOnlyThroughAnExplicitReversal).
					nextStates.add(new SetbackState(other, rail, 1));
					nextRails.add(rail);
					nextReversed.add(true);
				}
			});
			for (int i = 0; i < nextStates.size(); i++) {
				final SetbackState next = nextStates.get(i);
				if (nextRails.get(i) == target) {
					goalFrom = state;
					goalFar = next.node;
					goalReversed = nextReversed.get(i);
					break;
				}
				if (!prev.containsKey(next.key())) {
					prev.put(next.key(), new SetbackRec(state, nextRails.get(i), nextReversed.get(i)));
					queue.add(next);
				}
			}
		}
		if (goalFrom == null) {
			plan.reason = "setback: target rail " + targetRailHex + " is not reachable with one reversal";
			return plan;
		}

		// Reconstruct the rail traversal (goal edge last) and reverse it into run order.
		final ObjectArrayList<Rail> rails = new ObjectArrayList<>();
		final ObjectArrayList<Boolean> reversedAt = new ObjectArrayList<>();
		rails.add(target);
		reversedAt.add(goalReversed);
		SetbackState cursor = goalFrom;
		while (cursor != null) {
			final SetbackRec rec = prev.get(cursor.key());
			if (rec == null || rec.from == null) {
				break;
			}
			rails.add(rec.rail);
			reversedAt.add(rec.reversedHere);
			cursor = rec.from;
		}
		final ObjectArrayList<Rail> orderedRails = new ObjectArrayList<>();
		final ObjectArrayList<Boolean> orderedReversed = new ObjectArrayList<>();
		for (int i = rails.size() - 1; i >= 0; i--) {
			orderedRails.add(rails.get(i));
			orderedReversed.add(reversedAt.get(i));
		}

		// Node chain: startNode, then the far endpoint of every traversed rail.
		final ObjectArrayList<Position> nodes = new ObjectArrayList<>();
		nodes.add(startNode);
		for (int i = 0; i < orderedRails.size(); i++) {
			final Position from = nodes.get(i);
			final Position to = otherEndOf(sim, from, orderedRails.get(i));
			if (to == null) {
				plan.reason = "setback: rail endpoint missing in the route";
				return plan;
			}
			nodes.add(to);
		}
		if (!nodes.get(nodes.size() - 1).equals(goalFar)) {
			plan.reason = "setback: route reconstruction ended on the wrong node";
			return plan;
		}

		// Distances: the remainder of the current rail, then every traversed rail (the last one only
		// up to the stop fraction). The reversal stops SETBACK_EPS_M short of its node.
		final double toStartNodeM = remainingToAheadNodeM(walker);
		final double clamp = Math.max(0.0, Math.min(1.0, stopFraction));
		double travelledM = toStartNodeM;
		double flipAtM = -1;
		for (int i = 0; i < orderedRails.size(); i++) {
			if (orderedReversed.get(i)) {
				flipAtM = walker.distanceM() + travelledM - SETBACK_EPS_M;
			}
			travelledM += i == orderedRails.size() - 1 ? orderedRails.get(i).railMath.getLength() * clamp : orderedRails.get(i).railMath.getLength();
		}
		plan.stopCumulativeM = walker.distanceM() + travelledM;
		plan.stopRailHex = targetRailHex;
		plan.stopFraction = clamp;
		if (flipAtM > 0) {
			plan.flipCumulativeM = flipAtM;
			final int flipRailIndex = orderedReversed.indexOf(true);
			plan.flipRailHex = (flipRailIndex <= 0 ? currentRail : orderedRails.get(flipRailIndex - 1)).getHexId();
		}
		if (plan.stopCumulativeM <= walker.distanceM() + 1e-6) {
			plan.reason = "setback: stop would lie at or behind the vehicle position";
			return plan;
		}

		// Turnout decisions: the fork at each rail's entry node, in the order the walker will meet them.
		double cumulM = toStartNodeM;
		for (int i = 0; i < orderedRails.size(); i++) {
			final Position node = nodes.get(i);
			final Rail incoming = i == 0 ? currentRail : orderedRails.get(i - 1);
			final Rail desired = orderedRails.get(i);
			final Position approach = i == 0 ? travelEntryNode(walker) : nodes.get(i - 1);
			if (desired != incoming && approach != null) {
				final ObjectArrayList<Rail> forwards = forwardRails(sim, node, incoming);
				if (forwards.size() >= 2) {
					final int op = branchOperator(sim, approach, node, incoming, forwards, desired);
					if (op < 0) {
						plan.reason = "setback: turnout at " + node + " requires a branch outside the walker's choice";
						return plan;
					}
					plan.forkOps.add(new String[]{String.valueOf(node.getX()), String.valueOf(node.getY()), String.valueOf(node.getZ()), incoming.getHexId(), String.valueOf(op)});
					plan.forkMeters.add(walker.distanceM() + cumulM);
				}
			}
			cumulM += i == orderedRails.size() - 1 ? desired.railMath.getLength() * clamp : desired.railMath.getLength();
			plan.routeRailHexes.add(desired.getHexId());
		}
		plan.routeRailHexes.add(0, currentRail.getHexId());
		plan.feasible = true;
		plan.reason = "ok";
		return plan;
	}

	/** Minimum gap between two flip-plan reports, ms. */
	private static final long FLIP_PLAN_LOG_INTERVAL_MILLIS = 5000;
	private static long mmtrLastFlipPlanLogMillis;

	private static Plan planToRailForward(Simulator sim, Vehicle vehicle, String targetRailHex, double stopFraction) {
		final Plan plan = new Plan();
		plan.targetRailHex = targetRailHex;
		final org.mtr.core.mmtr.segment.MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
		if (walker == null) {
			plan.reason = "vehicle is not in live Motion-Core mode";
			return plan;
		}
		final Rail target = findRail(sim, targetRailHex);
		if (target == null) {
			plan.reason = "target rail " + targetRailHex + " not found";
			return plan;
		}
		final Position startNode = travelAheadNode(walker);
		final Rail currentRail = findRail(sim, walker.railHex());
		if (startNode == null || currentRail == null) {
			plan.reason = "walker has no current rail / ahead node";
			return plan;
		}
		if (currentRail == target) {
			plan.reason = "target rail is the rail the vehicle is already on; arm the stop distance directly";
			return plan;
		}

		// BFS over the real rail graph until either endpoint of the target rail is reached; the
		// reached endpoint is the ENTRY side, the far end is where the rail is fully traversed.
		final Position[] targetEnds = railEndpoints(sim, target);
		if (targetEnds[0] == null || targetEnds[1] == null) {
			plan.reason = "target rail endpoints not in graph";
			return plan;
		}
		final Object2ObjectOpenHashMap<Position, NodeRec> prev = new Object2ObjectOpenHashMap<>();
		final ObjectArrayList<Position> queue = new ObjectArrayList<>();
		queue.add(startNode);
		prev.put(startNode, new NodeRec(null, null));
		final Position behind = travelEntryNode(walker);
		Position entry = null;
		while (!queue.isEmpty()) {
			final Position node = queue.remove(0);
			if (node.equals(targetEnds[0]) || node.equals(targetEnds[1])) {
				entry = node;
				break;
			}
			final Object2ObjectOpenHashMap<Position, Rail> neighbors = sim.positionsToRail.get(node);
			if (neighbors == null) {
				continue;
			}
			neighbors.forEach((other, rail) -> {
				// Real-yard fix (P3 real-machine): BFS is undirected, so from the CURRENT ahead node
				// it must never hop back onto the rail the walker came from - the train cannot reverse.
				// Real yards often continue behind the parked rail (extra leads), which used to let the
				// planner route "forward" trains backwards through the yard rear (halt at the real fork).
				if (node.equals(startNode) && behind != null && other.equals(behind)) {
					return;
				}
				if (!prev.containsKey(other)) {
					prev.put(other, new NodeRec(node, rail));
					queue.add(other);
				}
			});
		}
		if (entry == null) {
			plan.reason = "target rail " + targetRailHex + " is not reachable from the vehicle";
			return plan;
		}

		// Reconstruct the node chain startNode -> ... -> entry -> farEndOfTarget.
		final Position farEnd = entry.equals(targetEnds[0]) ? targetEnds[1] : targetEnds[0];
		Position cursor = entry;
		final ObjectArrayList<Position> reversed = new ObjectArrayList<>();
		while (cursor != null) {
			reversed.add(cursor);
			final NodeRec rec = prev.get(cursor);
			cursor = rec == null || rec.from == null ? null : rec.from;
		}
		for (int i = reversed.size() - 1; i >= 0; i--) {
			plan.nodes.add(reversed.get(i));
		}
		plan.nodes.add(farEnd);

		// Cumulative metres from the vehicle's current position to the stop: remainder of the current
		// rail + every planned rail up to the target entry + fraction of the target rail.
		double fromCurrentToStartNode = remainingToAheadNodeM(walker);
		if (fromCurrentToStartNode < 0) {
			fromCurrentToStartNode = 0;
		}
		double plannedM = fromCurrentToStartNode;
		// Rails between consecutive nodes (nodes[0] == startNode): sum until reaching entry.
		for (int i = 1; i < plan.nodes.size(); i++) {
			final NodeRec rec = prev.get(plan.nodes.get(i));
			final Rail rail = plan.nodes.get(i).equals(farEnd) ? target : rec.rail;
			if (rail == null) {
				plan.reason = "route reconstruction failed";
				return plan;
			}
			plannedM += rail.railMath.getLength();
		}
		final double forwardClamp = Math.max(0.0, Math.min(1.0, stopFraction));
		plan.stopCumulativeM = walker.distanceM() + plannedM - (1.0 - forwardClamp) * target.railMath.getLength();
		plan.stopRailHex = targetRailHex;
		plan.stopFraction = forwardClamp;
		if (plan.stopCumulativeM <= walker.distanceM() + 1e-6) {
			plan.reason = "stop would lie at or behind the vehicle position";
			return plan;
		}

		// Turnout decisions at every node that has >= 2 forward rails (excluding the incoming rail).
		// Each fork records its absolute walker-space distance (parallel with forkOps) so the
		// approach-locking layer can request it only when the train is actually near it.
		double cumulM = fromCurrentToStartNode;
		for (int i = 0; i + 1 < plan.nodes.size(); i++) {
			final Position node = plan.nodes.get(i);
			final Position approach = i == 0 ? travelEntryNode(walker) : plan.nodes.get(i - 1);
			if (approach == null && i == 0) {
				plan.reason = "walker has no entry node for the first turnout";
				return plan;
			}
			final Rail incoming = i == 0 ? currentRail : (prev.get(plan.nodes.get(i)) == null ? currentRail : prev.get(plan.nodes.get(i)).rail);
			final Rail desired = plan.nodes.get(i + 1).equals(farEnd) ? target : prev.get(plan.nodes.get(i + 1)).rail;
			final ObjectArrayList<Rail> forwards = forwardRails(sim, node, incoming);
			if (forwards.size() >= 2) {
				final int op = branchOperator(sim, approach, node, incoming, forwards, desired);
				if (op < 0) {
					plan.reason = "turnout at node requires a branch outside the walker's branch0/1 choice";
					return plan;
				}
				plan.forkOps.add(new String[]{String.valueOf(node.getX()), String.valueOf(node.getY()), String.valueOf(node.getZ()), incoming.getHexId(), String.valueOf(op)});
				plan.forkMeters.add(walker.distanceM() + cumulM);
			}
			// Advance the cumulative distance over the segment nodes[i] -> nodes[i+1].
			final Position nextNode = plan.nodes.get(i + 1);
			final Rail segmentRail = nextNode.equals(farEnd) ? target : prev.get(nextNode) == null ? null : prev.get(nextNode).rail;
			if (segmentRail != null) {
				cumulM += segmentRail.railMath.getLength();
				plan.routeRailHexes.add(segmentRail.getHexId());
			}
		}
		plan.routeRailHexes.add(0, currentRail.getHexId());
		plan.feasible = true;
		plan.reason = "ok";
		return plan;
	}

	/**
	 * 尽头换向 fallback (real-junction 人字 rule): when the direct forward plan is infeasible the
	 * train can still reach a target behind it by driving to the dead end of the single-continuation
	 * corridor ahead (a real turnout cannot fold 180° at the crossing - only the near-straight arm
	 * is drivable), stopping there, changing ends (换端), and running back out. Produces a plan with
	 * {@code flipCumulativeM}/{@code flipRailHex} set; the vehicle flips once its head rests exactly
	 * at that dead end. Multi-rail corridors / corridors whose far end is not a dead end are refused
	 * (v1) with a reason.
	 */
	private static Plan planToRailViaDeadEndFlip(Simulator sim, Vehicle vehicle, String targetRailHex, double stopFraction) {
		final Plan plan = new Plan();
		plan.targetRailHex = targetRailHex;
		final org.mtr.core.mmtr.segment.MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
		if (walker == null) {
			plan.reason = "flip: vehicle is not in live Motion-Core mode";
			return plan;
		}
		final Rail target = findRail(sim, targetRailHex);
		final Rail currentRail = findRail(sim, walker.railHex());
		if (target == null || currentRail == null) {
			plan.reason = target == null ? "flip: target rail " + targetRailHex + " not found" : "flip: current rail not found";
			return plan;
		}
		final Position[] targetEnds = railEndpoints(sim, target);
		if (targetEnds[0] == null || targetEnds[1] == null) {
			plan.reason = "flip: target rail endpoints not in graph";
			return plan;
		}
		if (currentRail == target) {
			plan.reason = "flip: target is the rail the vehicle is already on";
			return plan;
		}
		final Position startNode = travelAheadNode(walker);
		final Position entryEnd = travelEntryNode(walker);
		if (startNode == null || entryEnd == null) {
			plan.reason = "flip: walker has no ahead/entry node";
			return plan;
		}
		final double nowM = walker.distanceM();
		final double remM = remainingToAheadNodeM(walker);

		// Resolve the flip corridor: where the train is heading, only the near-straight continuation
		// (legs) is drivable. A single continuation that ends in a true dead end is the flip rail.
		final Object2ObjectOpenHashMap<Position, Rail> startNeighbors = sim.positionsToRail.get(startNode);
		final ObjectArrayList<org.mtr.core.mmtr.point.MmtrPoint.MmtrPointLeg> legsHere = startNeighbors == null || startNeighbors.isEmpty()
			? new ObjectArrayList<>()
			: org.mtr.core.mmtr.point.MmtrPoint.computeOrderedLegs(startNode, entryEnd, currentRail, startNeighbors,
				sim.mmtrJunctionLegs.get(startNode.getX(), startNode.getY(), startNode.getZ(), currentRail.getHexId()));
		final Rail flipRail;
		final Position deadEnd;
		final Position flipEntry;
		final double toFlipEndM;
		if (startNeighbors == null || startNeighbors.isEmpty() || legsHere.isEmpty()) {
			// The current rail itself dead-ends at its far node.
			flipRail = currentRail;
			deadEnd = startNode;
			flipEntry = entryEnd;
			toFlipEndM = remM;
		} else if (legsHere.size() == 1) {
			flipRail = findRail(sim, legsHere.get(0).railHex);
			if (flipRail == null) {
				plan.reason = "flip: corridor rail not in graph";
				return plan;
			}
			if (flipRail == target) {
				plan.reason = "flip: target rail is the flip-corridor rail itself";
				return plan;
			}
			final Position far = otherEndOf(sim, startNode, flipRail);
			if (far == null) {
				plan.reason = "flip: corridor rail endpoint missing";
				return plan;
			}
			deadEnd = far;
			flipEntry = startNode;
			toFlipEndM = remM + flipRail.railMath.getLength();
		} else {
			plan.reason = "flip: the corridor ahead forks (>=2 continuations) before any dead end - set a 进向表 entry or split the run";
			return plan;
		}

		// The far end of the flip rail must be a true dead end (nothing else joins it).
		final Object2ObjectOpenHashMap<Position, Rail> deadNeighbors = sim.positionsToRail.get(deadEnd);
		final boolean[] onlyFlipRail = {false};
		if (deadNeighbors != null && !deadNeighbors.isEmpty()) {
			deadNeighbors.forEach((other, rail) -> onlyFlipRail[0] = rail == flipRail);
		}
		if (deadNeighbors == null || deadNeighbors.size() != 1 || !onlyFlipRail[0]) {
			plan.reason = "flip: the far end of rail " + flipRail.getHexId() + " is not a dead end";
			return plan;
		}

		final double flipAtM = nowM + toFlipEndM;
		final double backM = flipRail.railMath.getLength();

		// After the flip the train runs the flip rail back to its entry and continues from there.
		// BFS from the entry node to the target, never re-entering the flip rail (it is the dead
		// end behind the train).
		final Object2ObjectOpenHashMap<Position, NodeRec> prev = new Object2ObjectOpenHashMap<>();
		final ObjectArrayList<Position> queue = new ObjectArrayList<>();
		queue.add(flipEntry);
		prev.put(flipEntry, new NodeRec(null, null));
		Position entry = null;
		while (!queue.isEmpty()) {
			final Position node = queue.remove(0);
			if (node.equals(targetEnds[0]) || node.equals(targetEnds[1])) {
				entry = node;
				break;
			}
			final Object2ObjectOpenHashMap<Position, Rail> neighbors = sim.positionsToRail.get(node);
			if (neighbors == null) {
				continue;
			}
			neighbors.forEach((other, rail) -> {
				if (rail == flipRail) {
					return; // the flip rail only leads back into the dead end
				}
				if (!prev.containsKey(other)) {
					prev.put(other, new NodeRec(node, rail));
					queue.add(other);
				}
			});
		}
		if (entry == null) {
			plan.reason = "flip: target rail " + targetRailHex + " is not reachable after the terminal flip";
			return plan;
		}
		final Position farEnd = entry.equals(targetEnds[0]) ? targetEnds[1] : targetEnds[0];
		Position cursor = entry;
		final ObjectArrayList<Position> reversed = new ObjectArrayList<>();
		while (cursor != null) {
			reversed.add(cursor);
			final NodeRec rec = prev.get(cursor);
			cursor = rec == null || rec.from == null ? null : rec.from;
		}
		final ObjectArrayList<Position> chain = new ObjectArrayList<>();
		for (int i = reversed.size() - 1; i >= 0; i--) {
			chain.add(reversed.get(i));
		}
		chain.add(farEnd);

		// Cumulative metres after the flip: back along the flip rail to its entry, then the BFS chain.
		double plannedM = 0;
		for (int i = 1; i < chain.size(); i++) {
			final NodeRec rec = prev.get(chain.get(i));
			final Rail rail = chain.get(i).equals(farEnd) ? target : rec == null ? null : rec.rail;
			if (rail == null) {
				plan.reason = "flip: route reconstruction failed";
				return plan;
			}
			plannedM += rail.railMath.getLength();
		}
		final double clamp = Math.max(0.0, Math.min(1.0, stopFraction));
		final double stopAbs = flipAtM + backM + plannedM - (1.0 - clamp) * target.railMath.getLength();
		if (stopAbs <= flipAtM + 1e-6) {
			plan.reason = "flip: stop would lie at or behind the flip point";
			return plan;
		}
		plan.stopCumulativeM = stopAbs;
		plan.stopRailHex = targetRailHex;
		plan.stopFraction = clamp;
		plan.flipCumulativeM = flipAtM;
		plan.flipRailHex = flipRail.getHexId();

		// Turnout decisions after the flip, in the same ordering the walker will elect at runtime
		// (approach node of the flip rail = the dead end).
		double cumulM = backM;
		for (int i = 0; i + 1 < chain.size(); i++) {
			final Position node = chain.get(i);
			final Position approach = i == 0 ? deadEnd : chain.get(i - 1);
			final Rail incoming = i == 0 ? flipRail : (prev.get(chain.get(i)) == null ? flipRail : prev.get(chain.get(i)).rail);
			final Rail desired = chain.get(i + 1).equals(farEnd) ? target : prev.get(chain.get(i + 1)) == null ? null : prev.get(chain.get(i + 1)).rail;
			if (desired == null) {
				plan.reason = "flip: fork reconstruction failed";
				return plan;
			}
			final ObjectArrayList<Rail> forwards = forwardRails(sim, node, incoming);
			if (forwards.size() >= 2) {
				final int op = branchOperator(sim, approach, node, incoming, forwards, desired);
				if (op < 0) {
					plan.reason = "flip: after the flip the turnout at the corridor entry requires a branch outside the walker's choice";
					return plan;
				}
				plan.forkOps.add(new String[]{String.valueOf(node.getX()), String.valueOf(node.getY()), String.valueOf(node.getZ()), incoming.getHexId(), String.valueOf(op)});
				plan.forkMeters.add(flipAtM + cumulM);
			}
			final Position nextNode = chain.get(i + 1);
			final Rail segmentRail = nextNode.equals(farEnd) ? target : prev.get(nextNode) == null ? null : prev.get(nextNode).rail;
			if (segmentRail != null) {
				cumulM += segmentRail.railMath.getLength();
				plan.routeRailHexes.add(segmentRail.getHexId());
			}
		}
		plan.routeRailHexes.add(0, flipRail.getHexId());
		plan.feasible = true;
		plan.reason = "ok";
		return plan;
	}

	/**
	 * The real graph rail of the platform/siding with the given id, when drawn (null otherwise).
	 * Shared by the mission control op and the vehicle's mission self-arm.
	 */
	@Nullable
	public static Rail findSavedRailRail(Simulator simulator, long savedRailId) {
		final Rail[] found = {null};
		simulator.sidings.forEach(siding -> {
			if (found[0] == null && siding.getId() == savedRailId) {
				found[0] = siding.mmtrGraphRail();
			}
		});
		if (found[0] == null) {
			simulator.platforms.forEach(platform -> {
				if (found[0] == null && platform.getId() == savedRailId) {
					found[0] = platform.mmtrGraphRail();
				}
			});
		}
		return found[0];
	}

	/**
	 * **按 hex 找图轨**，两种端点写法都认：{@code getHexId()} 用的是轨的**声明顺序**，而作业单/网页里
	 * 手写的 hex 可能把两端写反（{@code x1-y1-z1-x2-y2-z2} 的前后半互换）。折返点这类"轨目标"
	 * （{@link org.mtr.core.mmtr.job.MmtrJobStep#targetRailHex}）全靠它解析。
	 *
	 * @return the graph rail, or {@code null} when the hex matches nothing
	 */
	@Nullable
	public static Rail findRailByHex(Simulator simulator, @Nullable String railHex) {
		if (railHex == null) {
			return null;
		}
		final String trimmed = railHex.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		final Rail direct = simulator.railIdMap.get(trimmed);
		if (direct != null) {
			return direct;
		}
		final String[] parts = trimmed.split("-");
		if (parts.length == 6) {
			return simulator.railIdMap.get(parts[3] + "-" + parts[4] + "-" + parts[5] + "-" + parts[0] + "-" + parts[1] + "-" + parts[2]);
		}
		return null;
	}

	/** Applies a feasible plan's turnout presets into {@code store} (skips identical settings). */
	public static void applyForkOps(Plan plan, BranchStore store) {
		for (final String[] op : plan.forkOps) {
			final long x = Long.parseLong(op[0]);
			final long y = Long.parseLong(op[1]);
			final long z = Long.parseLong(op[2]);
			final String viaHex = op[3];
			final int branch = Integer.parseInt(op[4]);
			if (!store.contains(x, y, z, viaHex) || store.get(x, y, z, viaHex) != branch) {
				store.set(x, y, z, viaHex, branch);
			}
		}
	}

	/**
	 * P3: request every en-route turnout of a feasible plan through the {@link MmtrPointAuthority}
	 * under {@code owner} (approach locking - requests land before the vehicle arrives). Re-requesting
	 * refreshes the grant/queue window and is idempotent. Returns whether the owner currently holds
	 * every fork (all granted now; queued forks stay queued and the caller waits/retries).
	 */
	public static boolean requestForkOps(Plan plan, org.mtr.core.mmtr.point.MmtrPointAuthority authority, String owner, long untilMillis) {
		return requestForkOps(plan.forkOps, authority, owner, untilMillis);
	}

	/** Request a specific set of fork ops (plan.forkOps or a still-pending subset of them). */
	public static boolean requestForkOps(ObjectArrayList<String[]> forkOps, org.mtr.core.mmtr.point.MmtrPointAuthority authority, String owner, long untilMillis) {
		boolean all = true; // an empty op set has nothing to wait on
		for (final String[] op : forkOps) {
			final long x = Long.parseLong(op[0]);
			final long y = Long.parseLong(op[1]);
			final long z = Long.parseLong(op[2]);
			final String viaHex = op[3];
			final int leg = Integer.parseInt(op[4]);
			if (authority.request(x, y, z, viaHex, owner, leg, untilMillis) != org.mtr.core.mmtr.point.MmtrPointAuthority.Result.GRANTED) {
				all = false;
			}
		}
		return all;
	}

	/**
	 * P3: describe the first fork of {@code forkOps} that {@code owner} does NOT hold right now, for the
	 * throttled "waiting for turnout authority" message. A mission whose arm keeps failing retries every
	 * tick and never gives up (an operator may unlock the point at any time), so the only thing that
	 * makes such a wait actionable is naming the blocking point and why: operator park, another holder,
	 * or the queue. Returns a short reason when nothing is blocked.
	 */
	public static String describeForkWait(ObjectArrayList<String[]> forkOps, org.mtr.core.mmtr.point.MmtrPointAuthority authority, String owner) {
		/*
		 * notes/149：**先说权限层记下的那一句**（"到底是哪一处、哪一道门挡住了"）。
		 *
		 * 请求集合是原子的，下面的循环只能报"第一处我没有持有的道岔" —— 它往往只是还没轮到，
		 * 真正把整组按下去的是集合里后面某一处。那句话只有权限层知道，不问它就只能猜。
		 */
		final String refused = authority.lastWaitReason(owner);
		if (refused != null) {
			return refused;
		}
		if (forkOps.isEmpty()) {
			return "no fork inside the approach window";
		}
		for (final String[] op : forkOps) {
			final long x = Long.parseLong(op[0]);
			final long y = Long.parseLong(op[1]);
			final long z = Long.parseLong(op[2]);
			final String viaHex = op[3];
			if (!authority.isGrantedTo(x, y, z, viaHex, owner)) {
				/*
				 * notes/149：描述里要带**物理层与净空闸**。只有 lock/holder/queue 时，最常见的现场
				 * （"另一列车压在岔区里，位置改不动"）看起来是"没人锁、没人持有、就是不给" ——
				 * 排查只能靠猜。净空闸那句话本来就在内部算出来了，这里把它说出来。
				 */
				final int demand = authority.turnoutDemand(x, y, z, viaHex, Integer.parseInt(op[4]));
				final String physical = authority.physicalHolder(x, y, z);
				final String blocked = demand == Integer.MIN_VALUE ? null : authority.positionChangeBlocked(x, y, z, demand, owner);
				return "point " + x + "," + y + "," + z + " via=" + viaHex + " wantLeg=" + op[4]
					+ " needPos=" + (demand == Integer.MIN_VALUE ? "（不存在）" : demand)
					+ " " + authority.state(x, y, z, viaHex)
					+ " phys=" + (physical == null ? "-" : physical)
					+ (blocked == null ? "" : " 净空闸=" + blocked);
			}
		}
		return "all requested forks granted";
	}

	private static ObjectArrayList<Rail> forwardRails(Simulator sim, Position node, Rail current) {
		final ObjectArrayList<Rail> out = new ObjectArrayList<>();
		final Object2ObjectOpenHashMap<Position, Rail> neighbors = sim.positionsToRail.get(node);
		if (neighbors != null) {
			neighbors.forEach((other, rail) -> {
				if (rail != current) {
					out.add(rail);
				}
			});
		}
		return out;
	}

	/** Index of the desired next rail in the direction-ordered fork legs (same ordering the
	 * walker's electAtFork uses at runtime), or -1 when desired is not a candidate leg. */
	private static int branchOperator(Simulator sim, Position approachNode, Position forkNode, Rail incoming, ObjectArrayList<Rail> forwards, Rail desired) {
		return legIndexForRail(sim, approachNode, forkNode, incoming, desired);
	}

	/**
	 * **这条腿在岔口有序腿表里的序号**（与运行时 {@code MmtrForkElection.electAtFork} 同一份排序），
	 * 找不到返回 -1。公开出来是给"车被岔挡住时自己补一条申请"用的（notes/155 §17 现场）：
	 * 申请接口收的是腿序号，而不是轨 hex。
	 */
	public static int legIndexForRail(Simulator sim, Position approachNode, Position forkNode, Rail incoming, @Nullable Rail desired) {
		final Object2ObjectOpenHashMap<Position, Rail> neighbors = sim.positionsToRail.get(forkNode);
		if (neighbors == null || incoming == null || desired == null) {
			return -1;
		}
		final ObjectArrayList<org.mtr.core.mmtr.point.MmtrPoint.MmtrPointLeg> legs = org.mtr.core.mmtr.point.MmtrPoint.computeOrderedLegs(forkNode, approachNode, incoming, neighbors,
			sim.mmtrJunctionLegs.get(forkNode.getX(), forkNode.getY(), forkNode.getZ(), incoming.getHexId()));
		for (int i = 0; i < legs.size(); i++) {
			if (legs.get(i).railHex.equals(desired.getHexId())) {
				return i;
			}
		}
		return -1;
	}

	/** 轨 hex → 图上的轨（诊断/自救用）。 */
	public static @Nullable Rail railByHex(Simulator sim, @Nullable String hex) {
		return hex == null ? null : findRail(sim, hex);
	}

	/**
	 * **计划里"这根轨之后要走的那根轨"**（自救用，notes/155 §17）：在 {@link Plan#routeRailHexes}
	 * 里找 {@code railHex} 的下一项；计划不走这根轨、或它是最后一根，返回 null。
	 */
	public static @Nullable String plannedRailAfter(@Nullable Plan plan, @Nullable String railHex) {
		if (plan == null || railHex == null) {
			return null;
		}
		for (int i = 0; i + 1 < plan.routeRailHexes.size(); i++) {
			if (plan.routeRailHexes.get(i).equals(railHex)) {
				return plan.routeRailHexes.get(i + 1);
			}
		}
		return null;
	}

	@Nullable
	private static Rail findRail(Simulator sim, String hex) {
		if (hex == null || hex.isEmpty()) {
			return null;
		}
		final Rail[] found = {null};
		sim.positionsToRail.forEach((pos, neigh) -> neigh.forEach((q, rail) -> {
			if (found[0] == null && rail.getHexId().equals(hex)) {
				found[0] = rail;
			}
		}));
		return found[0];
	}

	private static Position[] railEndpoints(Simulator sim, Rail rail) {
		final Position[] found = new Position[2];
		sim.positionsToRail.forEach((pos, neigh) -> neigh.forEach((other, r) -> {
			if (r == rail) {
				if (found[0] == null) {
					found[0] = pos;
				} else if (!pos.equals(found[0]) && found[1] == null) {
					found[1] = pos;
				}
			}
		}));
		return found;
	}

	@Nullable
	private static Position otherEndOf(Simulator sim, Position node, Rail rail) {
		final Position[] found = {null};
		final Object2ObjectOpenHashMap<Position, Rail> neighbors = sim.positionsToRail.get(node);
		if (neighbors != null) {
			neighbors.forEach((other, r) -> {
				if (found[0] == null && r == rail && !other.equals(node)) {
					found[0] = other;
				}
			});
		}
		return found[0];
	}
}