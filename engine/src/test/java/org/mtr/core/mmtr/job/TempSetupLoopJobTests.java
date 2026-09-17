package org.mtr.core.mmtr.job;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 临时工具（用完即删）：给真实世界写一条**1↔3 站循环作业单**（出库 → 1/1 → 2/1 → 3/1 → 南端折返换端 →
 * 3/2 → 2/2 → 1/2 → 北端折返换端 → 循环）。
 *
 * <p><b>两端折返都是"到一根正规轨道上换端"</b>（用户口径：不可能为了折返把某条线定义成折返专用股道）：
 * 步骤用 {@link MmtrJobStep#targetRailHex} 指到图轨 hex，fraction 1.0 = 开到这根轨按行车方向的远端。
 * 所以本工具**不改世界几何**，只写作业单。</p>
 */
public final class TempSetupLoopJobTests {

	private static final Path ROOT = Paths.get("C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/world/mtr");
	private static final long DEPOT_987654 = 849401984139021720L;
	private static final long SIDING_C1 = -4629294257679021237L;

	// 站台（去程 1 台 / 回程 2 台）
	private static final long P1_OUT = -8021057666741005855L;    // 站1/1  (-170,-333)→(-170,-306)
	private static final long P2_OUT = 8840155259502644057L;     // 站2/1  (-170,-412)→(-170,-379)
	private static final long P3_OUT = 954364252968674217L;      // 站3/1  (-170,-478)→(-170,-458)
	private static final long P3_BACK = 538294557107521739L;     // 站3/2  (-176,-478)→(-176,-458)
	private static final long P2_BACK = -5224482131162675969L;   // 站2/2  (-176,-413)→(-176,-379)
	private static final long P1_BACK = 1067577243292760039L;    // 站1/2  (-176,-333)→(-176,-306)

	/** 两端折返点（正规轨道，按端点找）：北端是两个斜渡线之间的正线；南端是真尽头支线。 */
	private static final Position NORTH_A = new Position(-176, -60, -253);
	private static final Position NORTH_B = new Position(-176, -60, -222);
	private static final Position SOUTH_A = new Position(-176, -60, -564);
	private static final Position SOUTH_B = new Position(-176, -60, -541);

	/** due 是"当日毫秒"；循环里被换算成相对秒数（due - startTimeOfDayMs），所以要给足一整圈的时间。 */
	private static final long DUE_END_OF_DAY = 86_399_000L;
	private static final long START_TIME_OF_DAY = 1_000L;

	private static MmtrJobStep step(String id, MmtrJobStep.StepType type, long targetId, String note) {
		final MmtrJobStep step = new MmtrJobStep();
		step.stepId = id;
		step.type = type;
		step.targetId = targetId;
		step.dueTimeOfDayMs = DUE_END_OF_DAY;
		step.note = note;
		return step;
	}

	private static MmtrJobStep railStep(String id, Rail rail, String note) {
		return railStep(id, rail, 1.0, note);
	}

	/** 带**明确的停车比例**的轨目标步（用户 2026-09-17：停在 (-170,-478) 就是"目标轨的进站端" = 0.0）。 */
	private static MmtrJobStep railStep(String id, Rail rail, double fraction, String note) {
		final MmtrJobStep step = step(id, MmtrJobStep.StepType.MOVE_TO, 0, note);
		step.targetRailHex = rail.getHexId();
		step.targetRailFraction = fraction;
		return step;
	}

	private static Rail findRail(Simulator sim, Position a, Position b) {
		for (final Rail rail : sim.rails) {
			final Position[] ordered = rail.mmtrOrderedPositions();
			if (ordered[0].equals(a) && ordered[1].equals(b)) {
				return rail;
			}
		}
		return null;
	}

	/**
	 * 造一条循环作业单（写盘与离线验证共用）。
	 *
	 * @param jobId        作业单 id
	 * @param sidingId     本务股道（车停在哪条股道就从哪条认领；三条作业单各用一条，互不抢）
	 * @param startTimeOfDayMs 首次发车时刻（当日毫秒）—— **错开开车时间**靠它：三班车错开约 1/3 圈
	 * @param endAtNorthTurnback true = 一圈在北端 (-176,-222) 换端掉头后收尾（不回库，下一圈从那里自己开出去）；
	 *                           false = 走完北端后**回库**（自己开回本务股道，下一圈再从库里发车）
	 */
	public static MmtrConsistJob buildJob(Simulator sim, String jobId, long sidingId, long startTimeOfDayMs, boolean endAtNorthTurnback) {
		final Rail northTurnback = findRail(sim, NORTH_A, NORTH_B);
		final Rail southTurnback = findRail(sim, SOUTH_A, SOUTH_B);
		if (northTurnback == null || southTurnback == null) {
			throw new IllegalStateException("找不到折返轨：北=" + (northTurnback != null) + " 南=" + (southTurnback != null));
		}
		System.out.println("[SETUP] " + jobId + " 折返轨 北=" + northTurnback.getHexId() + "（长 " + Math.round(northTurnback.railMath.getLength())
			+ "m）/ 南=" + southTurnback.getHexId() + "（长 " + Math.round(southTurnback.railMath.getLength()) + "m）");

		final MmtrConsistJob job = new MmtrConsistJob();
		job.jobId = jobId;
		job.depotId = DEPOT_987654;
		job.sidingId = sidingId;
		job.startTimeOfDayMs = startTimeOfDayMs;
		job.repeatDaily = true;
		job.loop = true;
		job.loopEveryMs = LOOP_EVERY_MS;           // 圈间等待（用户 2026-09-17：取消原来的 2 分钟）
		final MmtrCarSpec car = new MmtrCarSpec();
		car.vehicleId = "saf101";
		car.length = 16;
		car.width = 5;
		car.bogie1Position = -5;
		car.bogie2Position = 5;
		car.powered = true;
		job.cars.add(car);

		int n = 0;
		job.steps.add(step("m1o", MmtrJobStep.StepType.MOVE_TO, P1_OUT, "去程到 1 站 1 台"));
		job.steps.add(step("s1o", MmtrJobStep.StepType.SERVE, P1_OUT, "1 站 1 台开关门"));
		job.steps.add(step("m2o", MmtrJobStep.StepType.MOVE_TO, P2_OUT, "去程到 2 站 1 台"));
		job.steps.add(step("s2o", MmtrJobStep.StepType.SERVE, P2_OUT, "2 站 1 台开关门"));
		job.steps.add(step("m3o", MmtrJobStep.StepType.MOVE_TO, P3_OUT, "去程到 3 站 1 台"));
		job.steps.add(step("s3o", MmtrJobStep.StepType.SERVE, P3_OUT, "3 站 1 台开关门"));
		job.steps.add(railStep("tbS", southTurnback, "开到南端折返轨尽头（(-176,-541)→(-176,-564) 支线）换端"));
		job.steps.add(step("ceS", MmtrJobStep.StepType.CHANGE_ENDS, 0, "南端换端掉头"));
		job.steps.add(step("m3b", MmtrJobStep.StepType.MOVE_TO, P3_BACK, "回程到 3 站 2 台"));
		job.steps.add(step("s3b", MmtrJobStep.StepType.SERVE, P3_BACK, "3 站 2 台开关门"));
		job.steps.add(step("m2b", MmtrJobStep.StepType.MOVE_TO, P2_BACK, "回程到 2 站 2 台"));
		job.steps.add(step("s2b", MmtrJobStep.StepType.SERVE, P2_BACK, "2 站 2 台开关门"));
		job.steps.add(step("m1b", MmtrJobStep.StepType.MOVE_TO, P1_BACK, "回程到 1 站 2 台"));
		job.steps.add(step("s1b", MmtrJobStep.StepType.SERVE, P1_BACK, "1 站 2 台开关门"));
		job.steps.add(railStep("tbN", northTurnback, "开到北端折返轨（(-176,-253)→(-176,-222) 正线段）远端 (-176,-222)，即北端掉头点"));
		if (endAtNorthTurnback) {
			job.steps.add(step("ceN", MmtrJobStep.StepType.CHANGE_ENDS, 0, "北端 (-176,-222) 换端掉头（不回库；下一圈从这里自己开出去）"));
		} else {
			job.steps.add(step("mh", MmtrJobStep.StepType.MOVE_TO, sidingId, "回库：经 (-176,-222)→(-170,-199) 斜渡线换到本务股道（自己开回去，不靠重生）"));
		}
		for (final MmtrJobStep s : job.steps) {
			n++;
			System.out.println("[SETUP] " + jobId + " 步骤 " + String.format("%02d", n) + " " + s.stepId + " " + s.type
				+ (s.targetRailHex == null || s.targetRailHex.isEmpty() ? " 目标=" + s.targetId : " 轨目标=" + s.targetRailHex.substring(0, 8) + "…")
				+ " " + s.note);
		}
		return job;
	}

	/**
	 * 全部 6 班车的参数：股道 / 首次发车时刻（**错开**）/ 一圈怎么收尾。
	 *
	 * <h3>错开量为什么从 150 s 改成 40 s（2026-09-17 用户口径）</h3>
	 * <p>用户要求：**剩下 4 辆也套同一张作业单、注意错开、并取消每圈 2 分钟的等待**。
	 * 车辆段 987654 正好 6 条 43 m 股道、各一辆 saf101 ⇒ 6 班车。</p>
	 * <p>一圈的实际运行时长约 236 s（现场日志：ceS→ceN 这半圈 118 s，两半对称）；
	 * 圈间等待压到 5 s 之后，**周期 ≈ 241 s**，6 班平分就是 **40 s 一班**
	 * （6 × 40 = 240 ≈ 一个周期）—— 错开量按"周期 ÷ 班数"取，才不会在周期回卷时两班叠在一起。</p>
	 * <p>收尾方式（第 3 列：0 = 回库，非 0 = 北端 (-176,-222) 换端，不回库）：六班**全部**取北端换端，
	 * 与用户"在 -176,-60,-222 处折返"的口径一致。</p>
	 *
	 * <p><b>产能提醒</b>：北端折返段是一根 31 m 的单线死头，一班车占用它 + 咽喉约 40–50 s；
	 * 6 班按 240 s 周期跑，需要的咽喉时间（约 300 s）**超过**周期本身 ⇒ 现场会出现排队/顺延
	 * （联锁会自己排，不会死锁）。真挤到一起时，把 {@link #LOOP_EVERY_MS} 调大即可自然拉开。</p>
	 */
	public static final long[][] LOOP_FLEET = {
		{SIDING_C1, 1_000L, 1},                    // 987654/1 —— 第一班：北端 (-176,-222) 换端
		{1607594720369027173L, 41_000L, 1},        // 987654/4
		{4321759533923363700L, 81_000L, 1},        // 987654/6
		{-7701010504948601156L, 121_000L, 1},      // 987654/1（库里第一条）
		{139388029583209177L, 161_000L, 1},        // 987654/3
		{3518737612429408379L, 201_000L, 1},       // 987654/5
	};

	/** 圈间等待（原为 2 分钟；用户 2026-09-17："取消每圈的 2min 等待时间"）。引擎的下限是 1 s。 */
	public static final long LOOP_EVERY_MS = 5_000L;

	public static final String[] LOOP_JOB_IDS = {
		"TT-LOOP-1-3", "TT-LOOP-1-3-B", "TT-LOOP-1-3-C",
		"TT-LOOP-1-3-D", "TT-LOOP-1-3-E", "TT-LOOP-1-3-F",
	};

	/* ------------------------------------------------------------------ *
	 * 信号/双向占用测试单（用户 2026-09-17 现场布置的新站）
	 * ------------------------------------------------------------------ */

	/** 用户新加的 S2 站台（轨 (-162,-798)→(-162,-778)，挂在 (-170,-764)↔(-170,-812) 的会让环上）。 */
	private static final long SIG_S2_PLATFORM = 757923074738358348L;
	/** 用户新加的 S1 站台（轨 (-160,-721)→(-160,-697)，挂在 (-170,-667)↔(-170,-736) 的会让环上）。 */
	private static final long SIG_S1_PLATFORM = -4832233127307968578L;
	/** `-170,-60,-478`：用户指定"掉头回去后在这里停车换端"的节点（= 站3/1 台轨的北端）。 */
	private static final Position P3_NORTH_END = new Position(-170, -60, -478);
	private static final Position P3_PLATFORM_SOUTH = new Position(-170, -60, -458);

	/**
	 * **信号 / 双向占用测试单**（用户口径）：随便挑两辆车，一辆开到 S2、一辆开到 S1，各停一次并掉头；
	 * 掉头回来后**在 {@code -170,-60,-478} 停车换端**，再去 {@code -176,-60,-564} 换端。
	 *
	 * <p>要测的两件事：</p>
	 * <ol>
	 *   <li><b>行车区间的双向占用</b>：这两班车的去程往北、回程往南，走的是同一条 A 线（单线），
	 *       中间还各挂一个会让环（S1/S2 就挂在环上）。对向行车时区间占用、灯色、以及"谁先过"必须自洽；</li>
	 *   <li><b>信号按"预计到达时刻"分配</b>：两班车都要经过 {@code -170,-60,-478}，到达时刻不同 ——
	 *       那处的灯（与它背后的道岔/进路）应当按计划时刻给更早的那班，而不是先到先得或来回抢。</li>
	 * </ol>
	 *
	 * <p>收尾一步是**回库**：这条 A 线是单线、北端 (-176,-564) 又是死头，测试车跑完必须让出主线，
	 * 否则会把还在环线上跑的另外 4 班堵死。</p>
	 *
	 * @param viaS2 true = 这班跑 S2，false = 跑 S1
	 */
	public static MmtrConsistJob buildSignalTestJob(Simulator sim, String jobId, long sidingId, long startTimeOfDayMs, boolean viaS2) {
		final long platformId = viaS2 ? SIG_S2_PLATFORM : SIG_S1_PLATFORM;
		final String place = viaS2 ? "S2" : "S1";
		final Rail p3Platform = findRail(sim, P3_NORTH_END, P3_PLATFORM_SOUTH);
		final Rail southDeadEnd = findRail(sim, SOUTH_A, SOUTH_B);
		if (p3Platform == null || southDeadEnd == null) {
			throw new IllegalStateException("找不到目标轨：站3/1 台轨=" + (p3Platform != null) + " (-176,-564) 支线=" + (southDeadEnd != null));
		}
		final MmtrConsistJob job = new MmtrConsistJob();
		job.jobId = jobId;
		job.depotId = DEPOT_987654;
		job.sidingId = sidingId;
		job.startTimeOfDayMs = startTimeOfDayMs;
		job.repeatDaily = true;
		job.loop = false;                    // 单程测试：跑完回库停着，不循环
		job.loopEveryMs = LOOP_EVERY_MS;
		final MmtrCarSpec car = new MmtrCarSpec();
		car.vehicleId = "saf101";
		car.length = 16;
		car.width = 5;
		car.bogie1Position = -5;
		car.bogie2Position = 5;
		car.powered = true;
		job.cars.add(car);

		job.steps.add(step("m1", MmtrJobStep.StepType.MOVE_TO, platformId, "出库北上，开到 " + place + " 站台"));
		job.steps.add(step("s1", MmtrJobStep.StepType.SERVE, platformId, place + " 停一次（开门停站）"));
		job.steps.add(step("ce1", MmtrJobStep.StepType.CHANGE_ENDS, 0, place + " 掉头（换端）"));
		job.steps.add(railStep("m2", p3Platform, 0.0, "回程南下，在 -170,-60,-478（站3/1 台轨的北端）停车"));
		job.steps.add(step("ce2", MmtrJobStep.StepType.CHANGE_ENDS, 0, "在 -170,-60,-478 换端"));
		job.steps.add(railStep("m3", southDeadEnd, 1.0, "再北上，开到 (-176,-564) 尽头支线远端"));
		job.steps.add(step("ce3", MmtrJobStep.StepType.CHANGE_ENDS, 0, "在 -176,-60,-564 换端"));
		/*
		 * **出支线走 B 线直股**（2026-09-17 现场修：车在 (-176,-564) 支线里出不去）。
		 *
		 * <p>支线 (-176,-564) 那处道岔：位置 0 = 支线↔B 线往南 (-176,-511)，位置 1 = 支线↔斜渡线去 A 线。
		 * 原来 ce3 之后直接 `mh`（回库），规划器自己挑了**走斜渡线**那条 —— 而 A 线上正排着一串等
		 * 对向进路的车，于是这班车"进路 SET、却停在红灯前"出不去，道岔也一直被那串车里的另一班按在 1。
		 * 现在先明确派一步"到站3/2"（B 线台轨，在 (-176,-478)→(-176,-458)），路线就必然走**直股 0**，
		 * 从源头避开 A 线那串对向车；再回库。</p>
		 */
		job.steps.add(step("m3b", MmtrJobStep.StepType.MOVE_TO, 538294557107521739L, "出支线走 B 线直股，先到站3/2（避开 A 线对向车流）"));
		job.steps.add(step("mh", MmtrJobStep.StepType.MOVE_TO, sidingId, "回库：让出单线主线，别把环线上的车堵死"));
		int n = 0;
		for (final MmtrJobStep s : job.steps) {
			n++;
			System.out.println("[SETUP] " + jobId + " 步骤 " + String.format("%02d", n) + " " + s.stepId + " " + s.type
				+ (s.targetRailHex == null || s.targetRailHex.isEmpty() ? " 目标=" + s.targetId : " 轨目标=" + s.targetRailHex.substring(0, 8) + "… @" + s.targetRailFraction)
				+ " " + s.note);
		}
		return job;
	}

	/** 两班测试车用的股道与发车时刻（第 5、6 条股道让出来做这个测试）。 */
	public static final long[][] SIG_TEST_FLEET = {
		{3518737612429408379L, 5_000L},      // 987654/5 —— 跑 S2
		{139388029583209177L, 15_000L},      // 987654/3 —— 跑 S1（与上一班只差 10 s：故意让两班在 A 线上对向相遇）
	};

	/**
	 * 这一版的现场车队：**4 班环线 + 2 班信号/双向占用测试**。
	 *
	 * <p>用户 2026-09-17："将目前 6 辆车里面随便选 2 辆，一辆添加 S2 停一次掉头、一辆添加 S1 停一次掉头，
	 * 掉头回去后在 -170,-60,-478 处停车换端，然后再去 -176,-60,-564 换端" ⇒ 前 4 条股道继续跑环线，
	 * 第 5/3 条股道那两辆改跑 {@link #buildSignalTestJob}。</p>
	 */
	public static java.util.List<MmtrConsistJob> buildFleet(Simulator sim) {
		final java.util.List<MmtrConsistJob> jobs = new java.util.ArrayList<>();
		for (int i = 0; i < 4; i++) {
			jobs.add(buildJob(sim, LOOP_JOB_IDS[i], LOOP_FLEET[i][0], LOOP_FLEET[i][1], LOOP_FLEET[i][2] != 0));
		}
		jobs.add(buildSignalTestJob(sim, "TT-SIG-S2", SIG_TEST_FLEET[0][0], SIG_TEST_FLEET[0][1], true));
		jobs.add(buildSignalTestJob(sim, "TT-SIG-S1", SIG_TEST_FLEET[1][0], SIG_TEST_FLEET[1][1], false));
		return jobs;
	}

	@Test
	public void setup() {
		/*
		 * **默认不写现场世界**（2026-09-16 现场事故修）：
		 *
		 * <p>这个工具会 `sim.stop()` —— 那是"立刻做一次非增量完整保存"，也就是把**线上世界连同作业单**
		 * 一起重写。而它是个测试类：`gradlew test` 跑全量时会把它一起跑掉，于是"跑一遍测试"就悄悄把
		 * 现场作业单换成了当时工具里写着的那一版（现场表现：服务端重启后跑的是实验版作业单，
		 * 车在 (-176,-222) 换端后卡住、提示无进路）。</p>
		 *
		 * <p>现在要写现场必须显式给环境变量 {@code MMTR_WRITE_LIVE_WORLD=true}；不给自己跳过，
		 * 测试套件因此永远不会改现场。</p>
		 */
		Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("MMTR_WRITE_LIVE_WORLD")),
			"写现场需要 MMTR_WRITE_LIVE_WORLD=true（这个工具会整份重写线上世界）");
		Assumptions.assumeTrue(Files.isDirectory(ROOT), "dev world save not present");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, ROOT, false);
		// 老的作业单全部撤掉（含上一轮那三条），再写这一版错开时间的循环作业单。
		for (final MmtrConsistJob existing : new java.util.ArrayList<>(sim.mmtrJobRegistry.jobs)) {
			sim.mmtrJobRegistry.jobs.remove(existing);
		}
		/*
		 * **先把场上所有 MMTR 车辆清掉，让 6 班车都从自己的股道干净出发**（2026-09-17）。
		 *
		 * <p>不清的话：服务端停机时那几班车正跑在半路上，它们的本务股道是空的 —— 新作业单去认领时
		 * 找不到车，就会另生一辆，于是场上多出一批"没人管的孤儿车"（地图上看着像车变多了）。
		 * 清掉之后，每班车都在自己那条股道上从库里认领，发车时刻也就是作业单写的那个。</p>
		 */
		int removed = 0;
		final java.util.List<Long> liveIds = new java.util.ArrayList<>();
		for (final org.mtr.core.data.Siding siding : sim.sidings) {
			siding.iterateVehicles(vehicle -> liveIds.add(vehicle.getId()));
		}
		for (final long vehicleId : liveIds) {
			if (sim.deleteMmtrVehicle(vehicleId)) {
				removed++;
			}
		}
		System.out.println("[SETUP] 已清空场上 MMTR 车辆 " + removed + " 辆（6 班车将各自从本务股道出发）");
		for (final MmtrConsistJob job : buildFleet(sim)) {
			sim.upsertMmtrJob(job);
		}
		System.out.println("[SETUP] 作业单已落盘 mmtr-jobs.json，条数=" + sim.mmtrJobRegistry.jobs.size()
			+ "（错开 " + (LOOP_FLEET[1][1] - LOOP_FLEET[0][1]) / 1000 + " s，圈间等待 " + (LOOP_EVERY_MS / 1000) + " s）");
		// `save()` 只是置 autoSave 标志（真正的写盘发生在 tick 里）；`stop()` 才是"立刻做一次非增量完整保存"
		sim.stop();
		System.out.println("[SETUP] 世界已保存（只改了作业单，没有动轨道/股道）");
	}
}
