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
		final MmtrJobStep step = step(id, MmtrJobStep.StepType.MOVE_TO, 0, note);
		step.targetRailHex = rail.getHexId();
		step.targetRailFraction = 1.0;   // 开到头（按行车方向的远端），正好留给下一步换端
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

	/** 三班车一起造（收尾方式见 {@link #LOOP_FLEET}）。 */
	public static java.util.List<MmtrConsistJob> buildFleet(Simulator sim) {
		final java.util.List<MmtrConsistJob> jobs = new java.util.ArrayList<>();
		for (int i = 0; i < LOOP_JOB_IDS.length; i++) {
			jobs.add(buildJob(sim, LOOP_JOB_IDS[i], LOOP_FLEET[i][0], LOOP_FLEET[i][1], LOOP_FLEET[i][2] != 0));
		}
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
