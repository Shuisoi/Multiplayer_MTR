package org.mtr.core.mmtr.sim;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.mmtr.MmtrRunPlanner;
import org.mtr.core.mmtr.job.MmtrConsistJob;
import org.mtr.core.mmtr.job.MmtrJobScheduler;
import org.mtr.core.mmtr.plan.MmtrRailDistance;
import org.mtr.core.mmtr.point.MmtrForkElection;
import org.mtr.core.mmtr.point.MmtrPoint;
import org.mtr.core.mmtr.point.MmtrTurnout;
import org.mtr.core.mmtr.segment.MmtrMotionPosition;
import org.mtr.core.mmtr.signal.MmtrMovementAuthority;
import org.mtr.core.simulation.Simulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 卡死现场取证（P0.5 第 1 步）：把 {@code mmtr-studio/jobs/day-loop.json} 原样装进副本世界，跑到全网
 * 静止，然后把"计划要什么 / 权限层给了谁 / 走行器选不选得出来"三份读数并排打出来。
 *
 * <p>为什么要原样装那份作业单：notes/328 §8 的现场就是它跑出来的，单列车复现不出来（一条作业单
 * 改正路线后能连跑两圈）—— 卡死是**多列车在咽喉互斥**，所以取证必须带上全部 16 份。</p>
 *
 * <p>纪律：世界只读（副本 {@code build/mmtr-sim-stuck/world}），线上存档不动。</p>
 */
public final class TempSimStuckProbeTests {

	private static final Path COPY = Path.of("build", "mmtr-sim-stuck", "world");
	private static final Path JOBS_FILE = Path.of("..", "..", "mmtr-studio", "jobs", "day-loop.json");

	private static String railText(Simulator sim, Rail rail) {
		if (rail == null) {
			return "（无）";
		}
		final Position[] ends = railEndpoints(sim, rail);
		return "(" + ends[0].getX() + "," + ends[0].getZ() + ")→(" + ends[1].getX() + "," + ends[1].getZ() + ")"
			+ " 长" + Math.round(rail.railMath.getLength()) + " " + short8(rail.getHexId());
	}

	private static Position[] railEndpoints(Simulator sim, Rail rail) {
		final Position[] found = {null, null};
		sim.positionsToRail.forEach((pos, neighbours) -> neighbours.forEach((other, r) -> {
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

	private static String nodeText(Position node) {
		return node == null ? "（无）" : node.getX() + "," + node.getY() + "," + node.getZ();
	}

	private static String short8(String hex) {
		return hex == null ? "-" : hex.length() <= 8 ? hex : hex.substring(0, 8) + "…";
	}

	private static ObjectArrayList<Vehicle> vehicles(Simulator sim) {
		final ObjectArrayList<Vehicle> out = new ObjectArrayList<>();
		for (final Siding siding : sim.sidings) {
			siding.iterateVehicles(out::add);
		}
		return out;
	}

	private static String jobText(Simulator sim, long vehicleId) {
		final MmtrJobScheduler scheduler = sim.mmtrJobScheduler;
		if (scheduler == null) {
			return "（无调度器）";
		}
		final String jobId = scheduler.jobIdOfVehicle(vehicleId);
		if (jobId == null) {
			return "（无作业）";
		}
		return jobId + " 态=" + scheduler.stateOf(jobId) + " 步=" + scheduler.stepIndexOf(jobId) + " 圈=" + scheduler.cyclesOf(jobId)
			+ (scheduler.failureOf(jobId) == null ? "" : " 失败=" + scheduler.failureOf(jobId));
	}

	private static long targetPlatformId(Simulator sim, long vehicleId) {
		final MmtrJobScheduler scheduler = sim.mmtrJobScheduler;
		if (scheduler == null) {
			return 0;
		}
		final String jobId = scheduler.jobIdOfVehicle(vehicleId);
		if (jobId == null) {
			return 0;
		}
		for (final MmtrConsistJob job : sim.mmtrJobRegistry.jobs) {
			if (!job.jobId.equals(jobId)) {
				continue;
			}
			final int index = scheduler.stepIndexOf(jobId);
			return index >= 0 && index < job.steps.size() ? job.steps.get(index).targetId : 0;
		}
		return 0;
	}

	/**
	 * 一处岔口的**全部读数**：几何（有序腿表 + 位置翻译）、权限层（当前岔位 / 持有者 / 各进向授予腿）、
	 * 以及"走行器现在选不选得出来"。
	 *
	 * <p>{@code elect} 那一行是**跨口径对照**：按走行器自己的（进向、来向、目标）重算一次选举，
	 * 返回 null 就是"车会停在岔前"。它带副作用（会同步岔位、必要时按意图扳岔），所以只在停住之后调用。</p>
	 */
	private static void dumpNode(Simulator sim, Position node, String viaHex, Position enteredFrom, String targetRailHex, String owner) {
		System.out.println("[STUCK] ---- 节点 " + nodeText(node) + " 进向 " + short8(viaHex) + " 来向 " + nodeText(enteredFrom) + " ----");
		final Object2ObjectOpenHashMap<Position, Rail> neighbours = sim.positionsToRail.get(node);
		if (neighbours == null) {
			System.out.println("[STUCK]   图上没有这个节点");
			return;
		}
		neighbours.forEach((farEnd, rail) -> System.out.println("[STUCK]   相邻轨 " + railText(sim, rail)));
		final Rail viaRail = MmtrRunPlanner.railByHex(sim, viaHex);
		if (viaRail == null) {
			System.out.println("[STUCK]   进向轨不在图上");
			return;
		}
		final ObjectArrayList<MmtrPoint.MmtrPointLeg> legs = MmtrPoint.computeOrderedLegs(node, enteredFrom, viaRail, neighbours,
			sim.mmtrJunctionLegs.get(node.getX(), node.getY(), node.getZ(), viaHex));
		for (int i = 0; i < legs.size(); i++) {
			final MmtrPoint.MmtrPointLeg leg = legs.get(i);
			System.out.println("[STUCK]   腿 " + i + " = " + leg.kind + " cos " + Math.round(leg.cos * 100) / 100.0
				+ " 到(" + leg.endX + "," + leg.endZ + ") " + short8(leg.railHex)
				+ " ⇒ 位置 " + sim.mmtrTurnoutPositionForLeg(node.getX(), node.getY(), node.getZ(), viaHex, i));
		}
		final MmtrTurnout turnout = sim.mmtrTurnout(node.getX(), node.getY(), node.getZ());
		if (turnout == null) {
			System.out.println("[STUCK]   **这处没有被认成单开道岔**：" + MmtrTurnout.rejectionReason(node, neighbours));
		} else {
			System.out.println("[STUCK]   道岔：根部 " + short8(turnout.stemRailHex) + " / 正线远端 " + short8(turnout.farRailHex)
				+ " / 岔股 " + short8(turnout.branchRailHex));
		}
		final int actual = sim.mmtrTurnoutPosition(node.getX(), node.getY(), node.getZ());
		final String holder = sim.mmtrPointAuthority.physicalHolder(node.getX(), node.getY(), node.getZ());
		System.out.println("[STUCK]   权限层：现位=" + actual + " 物理持有者=" + (holder == null ? "-" : holder)
			+ " 授予腿(" + short8(viaHex) + ")=" + sim.mmtrPointAuthority.grantedLeg(node.getX(), node.getY(), node.getZ(), viaHex)
			+ " 进向状态=" + sim.mmtrPointAuthority.state(node.getX(), node.getY(), node.getZ(), viaHex)
			+ " 等待理由=" + sim.mmtrPointAuthority.lastWaitReason(owner));
		if (turnout != null) {
			System.out.println("[STUCK]   现位 " + actual + " 开通的续行 = " + short8(turnout.continuationFrom(viaHex, actual))
				+ "，禁行 = " + short8(turnout.prohibitedRailHex(actual)));
		}
		final Rail elected = MmtrForkElection.elect(sim, sim.mmtrPointBranches, sim.mmtrPointAuthority, owner, targetRailHex,
			node, enteredFrom, viaRail, false);
		System.out.println("[STUCK]   走行器选举（进向 " + short8(viaHex) + " 去目标 " + short8(targetRailHex) + "）⇒ "
			+ (elected == null ? "**null（停在岔前）**" : railText(sim, elected)));
	}

	/** 一列车停住那一刻的全量取证。 */
	private static void dumpStuck(Simulator sim, Vehicle vehicle, boolean withNodeDetail) {
		final String owner = "v" + vehicle.getId();
		final long targetPlatformId = targetPlatformId(sim, vehicle.getId());
		final Rail targetRail = MmtrRailDistance.railOfPlatform(sim, targetPlatformId);
		final String targetRailHex = targetRail == null ? "" : targetRail.getHexId();
		System.out.println("[STUCK] ================ 车 " + owner + " 停住现场（作业 " + jobText(sim, vehicle.getId()) + "）================ ");
		System.out.println("[STUCK] 目标站台 " + targetPlatformId + " 目标轨 " + railText(sim, targetRail));
		final MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
		if (walker == null) {
			System.out.println("[STUCK] 没有走行器");
			return;
		}
		System.out.println("[STUCK] 走行器：当前轨 " + railText(sim, walker.currentRail()));
		System.out.println("[STUCK] 走行器：轨内偏移 " + Math.round(walker.offsetM() * 100) / 100.0 + "m / 轨长 "
			+ Math.round(walker.currentRailLengthM() * 100) / 100.0 + "m，累计里程 " + Math.round(walker.distanceM() * 100) / 100.0 + "m");
		System.out.println("[STUCK] 走行器：ahead=" + nodeText(walker.aheadNode()) + " enteredFrom=" + nodeText(walker.enteredFromPosition())
			+ "（travelAhead=" + nodeText(MmtrRunPlanner.travelAheadNode(walker)) + " travelEntry=" + nodeText(MmtrRunPlanner.travelEntryNode(walker)) + "）");
		final Rail next = walker.peekNextRail();
		System.out.println("[STUCK] 走行器：peekNextRail=" + railText(sim, next) + " wouldHaltAtForkOn(next)=" + walker.wouldHaltAtForkOn(next));
		final MmtrMovementAuthority authority = vehicle.mmtrMovementAuthority();
		System.out.println("[STUCK] 行车许可：" + (authority == null ? "-" : authority.aspect + " / " + authority.reason));
		System.out.println("[STUCK] 司机视角理由：" + vehicle.getMmtrHoldReasonFromSync());
		final var mission = vehicle.getMmtrMission();
		System.out.println("[STUCK] 任务：" + (mission == null ? "（无）" : "态=" + mission.getState() + " 类=" + mission.getKind()
			+ " 执行者=" + mission.getExecutor() + " 原地=" + mission.isInPlace() + " 目标股道=" + mission.getTargetSidingId()
			+ " 目标轨=" + short8(mission.getTargetRailHex()) + " 任务对象=" + (mission.getTask() == null ? "-" : mission.getTask().getClass().getSimpleName())
			+ (mission.getFailureReason() == null || mission.getFailureReason().isEmpty() ? "" : " 失败=" + mission.getFailureReason())));
		System.out.println("[STUCK] 子任务镜像：" + vehicle.getMmtrSubTasksFromSync());
		System.out.println("[STUCK] 钉住=" + vehicle.isMmtrPinned() + " 自动=" + vehicle.isMmtrMotionAuto() + " 到点=" + vehicle.isMmtrMotionStoppedAtTarget());
		final var route = vehicle.getMmtrRoute();
		if (route == null) {
			System.out.println("[STUCK] 没有进路");
		} else {
			System.out.println("[STUCK] 进路：established=" + route.isEstablished() + " 理由=" + route.getStateReason()
				+ " entry=" + short8(route.getEntryRailHex()) + " 轨数=" + route.getRailHexes().size());
			for (final String[] fork : route.getForks()) {
				System.out.println("[STUCK]   进路岔 " + fork[0] + "," + fork[1] + "," + fork[2] + " via " + short8(fork[3])
					+ " leg " + fork[4] + " 已越过=" + route.isForkCrossed(fork) + " ⇒ 位置 "
					+ sim.mmtrTurnoutPositionForLeg(Long.parseLong(fork[0]), Long.parseLong(fork[1]), Long.parseLong(fork[2]), fork[3], Integer.parseInt(fork[4])));
			}
		}
		final ObjectArrayList<String[]> pending = vehicle.getMmtrPendingPointOps();
		System.out.println("[STUCK] 正在申请的岔（原子组）：" + pending.size() + " 处");
		for (final String[] op : pending) {
			System.out.println("[STUCK]   申请 " + op[0] + "," + op[1] + "," + op[2] + " via " + short8(op[3]) + " leg " + op[4]
				+ " ⇒ 位置 " + sim.mmtrTurnoutPositionForLeg(Long.parseLong(op[0]), Long.parseLong(op[1]), Long.parseLong(op[2]), op[3], Integer.parseInt(op[4])));
		}

		System.out.println("[STUCK] ---- 现在重新规划一次（目标 " + short8(targetRailHex) + "）----");
		final MmtrRunPlanner.Plan plan = MmtrRunPlanner.planToRail(sim, vehicle, targetRailHex, 1.0);
		System.out.println("[STUCK] 计划：feasible=" + plan.feasible + " 理由=" + plan.reason + " 停车里程=" + plan.stopCumulativeM
			+ " 换端里程=" + plan.flipCumulativeM);
		System.out.println("[STUCK] 计划节点链：" + plan.nodes);
		for (int i = 0; i < plan.forkOps.size(); i++) {
			final String[] op = plan.forkOps.get(i);
			System.out.println("[STUCK]   计划岔 " + op[0] + "," + op[1] + "," + op[2] + " via " + short8(op[3]) + " leg " + op[4]
				+ " @" + Math.round(plan.forkMeters.get(i)) + "m ⇒ 位置 "
				+ sim.mmtrTurnoutPositionForLeg(Long.parseLong(op[0]), Long.parseLong(op[1]), Long.parseLong(op[2]), op[3], Integer.parseInt(op[4])));
		}
		System.out.println("[STUCK] 计划轨序：" + plan.routeRailHexes);

		if (!withNodeDetail) {
			return;
		}
		final Position[] walkerEnds = walker.currentRail() == null ? null : railEndpoints(sim, walker.currentRail());
		for (int i = 0; i < plan.forkOps.size(); i++) {
			final String[] op = plan.forkOps.get(i);
			final Position node = new Position(Long.parseLong(op[0]), Long.parseLong(op[1]), Long.parseLong(op[2]));
			final Position approach = i == 0
				? MmtrRunPlanner.travelEntryNode(walker)
				: new Position(Long.parseLong(plan.forkOps.get(i - 1)[0]), Long.parseLong(plan.forkOps.get(i - 1)[1]), Long.parseLong(plan.forkOps.get(i - 1)[2]));
			dumpNode(sim, node, op[3], approach == null ? (walkerEnds == null ? null : walkerEnds[0]) : approach, targetRailHex, owner);
		}
	}

	/**
	 * 跑真作业单到全网静止。{@code jobCount} 控制装几份（前 N 份，发车时刻仍是文件里的），
	 * {@code detailVehicles} 控制在取证时对前几列车做逐岔读数（每列车会重规划一次，带副作用）。
	 *
	 * <p>{@code poisonNode} 非空时，开跑前把该节点的人工位扳到 0（用引擎自己的意图扳岔接口，
	 * 行/位置一起写、与现场手动扳岔同一条路）。用途：**复现 2026-09-26 22:59 那次全天卡死**——
	 * 那次世界的 {@code mmtr-points.json} 与 23:05 之后的不一样，同一份作业单现在跑得通。</p>
	 */
	private void run(String tag, int jobCount, int detailVehicles, int seconds, int stuckSeconds, @org.jspecify.annotations.Nullable Position poisonNode) throws IOException {
		run(tag, jobCount, detailVehicles, seconds, stuckSeconds, poisonNode, 0);
	}

	/**
	 * {@code staggerMillisOverride > 0} 时把留下的前 {@code jobCount} 份作业单的发车时刻**改成**
	 * {@code 1000 + i × stagger}（走 {@code upsertMmtrJob}，它会重建调度器）——用来量"这张图一小时
	 * 能排几列车"：错开太密 ⇒ 车在库里越积越多 ⇒ 线路饱和。
	 */
	private void run(String tag, int jobCount, int detailVehicles, int seconds, int stuckSeconds, @org.jspecify.annotations.Nullable Position poisonNode, long staggerMillisOverride) throws IOException {
		Assumptions.assumeTrue(Files.isDirectory(TempSimProbeTests.liveWorld()), "dev world save not present");
		final Path live = TempSimProbeTests.liveWorld();
		TempSimProbeTests.deleteRecursively(COPY);
		TempSimProbeTests.copyRecursively(live, COPY);
		final Path worldDir = COPY.resolve("minecraft").resolve("overworld");
		Files.createDirectories(worldDir);
		Files.copy(JOBS_FILE, worldDir.resolve("mmtr-jobs.json"), StandardCopyOption.REPLACE_EXISTING);

		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, COPY, false);
		if (poisonNode != null) {
			final boolean thrown = sim.mmtrThrowTurnoutForIntent(poisonNode.getX(), poisonNode.getY(), poisonNode.getZ(), MmtrTurnout.NORMAL, null);
			System.out.println("[STUCK] 开局把 " + nodeText(poisonNode) + " 扳到位置 0 ⇒ " + thrown
				+ "（现在生效位置 = " + sim.mmtrTurnoutPosition(poisonNode.getX(), poisonNode.getY(), poisonNode.getZ()) + "）");
		}
		// 只留前 jobCount 份：走 deleteMmtrJob（它会重建调度器），直接改 registry 不会。
		final ObjectArrayList<String> jobIds = new ObjectArrayList<>();
		for (final MmtrConsistJob job : sim.mmtrJobRegistry.jobs) {
			jobIds.add(job.jobId);
		}
		for (int i = jobCount; i < jobIds.size(); i++) {
			sim.deleteMmtrJob(jobIds.get(i));
		}
		if (staggerMillisOverride > 0) {
			int index = 0;
			for (final MmtrConsistJob job : new ObjectArrayList<>(sim.mmtrJobRegistry.jobs)) {
				job.startTimeOfDayMs = 1_000L + index * staggerMillisOverride;
				sim.upsertMmtrJob(job);
				index++;
			}
		}
		System.out.println("[STUCK] == " + tag + " == 作业单 " + sim.mmtrJobRegistry.jobs.size() + " 份（文件 " + JOBS_FILE + "）");

		final Map<Long, Double> lastDistance = new LinkedHashMap<>();
		int still = 0;
		String lastProgressSignature = "";
		for (int t = 1; t <= seconds; t++) {
			sim.step(1_000L);
			final ObjectArrayList<Vehicle> running = vehicles(sim);
			double moved = 0;
			final StringBuilder signature = new StringBuilder();
			for (final Vehicle vehicle : running) {
				final MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
				final double distance = walker == null ? 0 : Math.round(walker.distanceM() * 10) / 10.0;
				final Double previous = lastDistance.get(vehicle.getId());
				if (previous != null) {
					moved += Math.abs(distance - previous);
				}
				lastDistance.put(vehicle.getId(), distance);
				final MmtrJobScheduler signatureScheduler = sim.mmtrJobScheduler;
				final String jobId = signatureScheduler == null ? null : signatureScheduler.jobIdOfVehicle(vehicle.getId());
				signature.append(vehicle.getId()).append(':').append(distance).append(':')
					.append(jobId == null ? "-" : signatureScheduler.stepIndexOf(jobId) + "/" + signatureScheduler.cyclesOf(jobId))
					.append(jobId == null ? "" : "/" + signatureScheduler.stateOf(jobId))
					// 子任务 revision 也要进签名：站停那 20 s 里"步号/里程/调度状态"都不变，
					// 变的只有子任务推进（开门→等待→关门）——少这一项就会把正常站停误报成卡死。
					.append('/').append(vehicle.getMmtrSubTaskRevisionFromSync())
					.append('/').append(vehicle.getMmtrSubTaskHintFromSync()).append('|');
			}
			if (t % 20 == 0) {
				final StringBuilder line = new StringBuilder("[STUCK] t=" + t + "s 车=" + running.size() + " 动量=" + Math.round(moved * 10) / 10.0);
				for (int i = 0; i < Math.min(4, running.size()); i++) {
					final Vehicle vehicle = running.get(i);
					final MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
					line.append(" | ").append(Long.toHexString(vehicle.getId()).substring(0, 6))
						.append(" 里程=").append(walker == null ? "-" : Math.round(walker.distanceM()))
						.append(" 轨=").append(walker == null ? "-" : short8(walker.railHex()))
						.append(" ").append(jobText(sim, vehicle.getId()).replace("DAY-LOOP-", "J"));
				}
				System.out.println(line);
			}
			/*
			 * **判据是"作业单不推进"而不是"车不动"**：站停 20 s + 圈间静置 5 s + 下一圈站停 20 s
			 * 会让一列车合法地静止 45 s 以上（第一版判据就是这么误报的）。所以要求
			 * "所有车的（里程, 步号/圈号, 调度状态）签名"连续 {@code stuckSeconds} 秒一模一样。
			 */
			final boolean noProgress = moved < 0.05 && signature.toString().equals(lastProgressSignature);
			lastProgressSignature = signature.toString();
			if (t > 30 && noProgress) {
				still++;
			} else {
				still = 0;
			}
			if (still >= stuckSeconds) {
				System.out.println("[STUCK] t=" + t + "s：全网 " + stuckSeconds + " s 零位移 ⇒ 认定卡死，开始取证");
				int dumped = 0;
				for (final Vehicle vehicle : running) {
					dumpStuck(sim, vehicle, dumped < detailVehicles);
					dumped++;
					if (dumped >= 16) {
						System.out.println("[STUCK] （其余车略）");
						break;
					}
				}
				return;
			}
		}
		System.out.println("[STUCK] " + seconds + " s 内没有出现 " + stuckSeconds + " s 的全网静止 —— 没复现");
	}

	/**
	 * 咽喉岔口的**静态地图**：每个岔口的几何（含腿表与位置翻译）+ 存档里的行/位置。
	 *
	 * <p>用途：卡死读数里的 {@code -280,65,1800} / {@code -200,65,1800} / {@code -200,65,1806}
	 * 三个节点必须能一眼看清"哪根是根部、哪根是岔股、第几条腿是什么"，否则日志里的腿号无从校对。</p>
	 */
	@Test
	public void throatMap() throws IOException {
		Assumptions.assumeTrue(Files.isDirectory(TempSimProbeTests.liveWorld()), "dev world save not present");
		TempSimProbeTests.deleteRecursively(COPY);
		TempSimProbeTests.copyRecursively(TempSimProbeTests.liveWorld(), COPY);
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, COPY, false);
		System.out.println("[MAP] 存档点位：" + sim.mmtrPointBranches.nodePositions.size() + " 条；行：" + sim.mmtrPointBranches.branches.size() + " 条");
		final java.util.TreeSet<String> keys = new java.util.TreeSet<>();
		sim.positionsToRail.forEach((node, neighbours) -> {
			if (neighbours.size() >= 3 && node.getZ() >= 1700 && node.getZ() <= 1870 && node.getX() >= -560 && node.getX() <= 300) {
				keys.add(node.getX() + "," + node.getY() + "," + node.getZ());
			}
		});
		for (final String key : keys) {
			final String[] parts = key.split(",");
			final Position node = new Position(Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2]));
			final Object2ObjectOpenHashMap<Position, Rail> neighbours = sim.positionsToRail.get(node);
			System.out.println("[MAP] ======== 节点 " + key + " ========");
			neighbours.forEach((farEnd, rail) -> System.out.println("[MAP]   相邻 " + railText(sim, rail)));
			System.out.println("[MAP]   " + MmtrTurnout.describeResolution(node, neighbours).replace("\n", "\n[MAP]   "));
			for (final var entry : neighbours.object2ObjectEntrySet()) {
				final Rail via = entry.getValue();
				final ObjectArrayList<MmtrPoint.MmtrPointLeg> legs = MmtrPoint.computeOrderedLegs(node, entry.getKey(), via, neighbours,
					sim.mmtrJunctionLegs.get(node.getX(), node.getY(), node.getZ(), via.getHexId()));
				final StringBuilder row = new StringBuilder("[MAP]   进向 " + railText(sim, via) + " 行="
					+ (sim.mmtrPointBranches.contains(node.getX(), node.getY(), node.getZ(), via.getHexId())
						? String.valueOf(sim.mmtrPointBranches.get(node.getX(), node.getY(), node.getZ(), via.getHexId())) : "（无）") + " 腿表：");
				for (int i = 0; i < legs.size(); i++) {
					row.append("[").append(i).append("]").append(legs.get(i).kind).append("到(")
						.append(legs.get(i).endX).append(",").append(legs.get(i).endZ).append(")⇒位置")
						.append(sim.mmtrTurnoutPositionForLeg(node.getX(), node.getY(), node.getZ(), via.getHexId(), i)).append(' ');
				}
				System.out.println(row);
			}
			System.out.println("[MAP]   存档位置=" + sim.mmtrPointBranches.nodePosition(node.getX(), node.getY(), node.getZ())
				+ " 生效位置=" + sim.mmtrTurnoutPosition(node.getX(), node.getY(), node.getZ()));
		}
	}

	@Test
	public void dayLoopFullHour() throws IOException {
		run("全天作业单（文件里的全部份数，一整个小时）", 99, 3, 3600, 45, null);
	}

	/**
	 * **线路容量**：同一张图、同一份作业单，只把发车错开改疏 —— 量"一小时能排几列"。
	 *
	 * <p>为什么要量它：16 份 / 180 s 错开跑到 t≈1.9 ks 会全网静止（notes/328 §9.6）。那到底是
	 * "引擎扣住了车"还是"这张图本来就装不下这么多车"？把错开从 180 s 放到 900 s（= 单列一圈的时间），
	 * 线路上的并发数从 ~5 降到 ~1，若此时跑得通，就说明瓶颈是**编排密度**而不是某一处互锁。</p>
	 *
	 * <p>作业单文件现在是 4 份（用户 2026-09-26 口径）⇒ 这四个用例量的是"4 列车要错开多少"。想复现
	 * 16 份那组数字：{@code studio.ps1 -Action jobs -Consists 16} 重新生成即可。</p>
	 */
	@Test
	public void capacityAllSpacedBy900s() throws IOException {
		run("全部份数 / 900 s 错开（并发 ≈ 1）", 99, 0, 3600, 45, null, 900_000L);
	}

	@Test
	public void capacityAllSpacedBy450s() throws IOException {
		run("全部份数 / 450 s 错开（并发 ≈ 2）", 99, 0, 3600, 45, null, 450_000L);
	}

	@Test
	public void capacityAllSpacedBy300s() throws IOException {
		run("全部份数 / 300 s 错开（并发 ≈ 3）", 99, 0, 3600, 45, null, 300_000L);
	}

	@Test
	public void dayLoopTwo() throws IOException {
		run("全天作业单 2 份", 2, 2, 900, 45, null);
	}

	/**
	 * **复现 22:59 那次全天卡死**：把 -200,65,1806 扳到位置 0（"正线贯通"），它恰好是
	 * 下水 1 台东端那个道岔的岔股那条进路要去的下一处道岔 —— 而作业单走的正是"过岔股"。
	 */
	@Test
	public void dayLoopTwoThroatSwitchThrownAgainstTheRoute() throws IOException {
		run("2 份作业单 + -200,65,1806 被扳到 0", 2, 2, 900, 45, new Position(-200, 65, 1806));
	}

	@Test
	public void dayLoopFullThroatSwitchThrownAgainstTheRoute() throws IOException {
		run("全部份数 + -200,65,1806 被扳到 0", 99, 2, 900, 45, new Position(-200, 65, 1806));
	}
}
