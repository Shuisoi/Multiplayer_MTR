package org.mtr.core.mmtr.sim;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.mmtr.job.MmtrCarSpec;
import org.mtr.core.mmtr.job.MmtrConsistJob;
import org.mtr.core.mmtr.job.MmtrJobScheduler;
import org.mtr.core.mmtr.job.MmtrJobStep;
import org.mtr.core.simulation.Simulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * P0 闸门（作业单仿真器可行性）：**这个副本世界 + 真引擎，一天跑不跑得动？**
 *
 * <p>两问分开答：</p>
 * <ol>
 *   <li>{@link #singleConsistRoute()}：一列车的全天路线跑不跑得通（出库 → 上水村 → 莫氏岛 →
 *       叶楼村换端 → 回程 → 下水 → 循环）。跑不通的话吞吐数字毫无意义 —— 车停着不动，
 *       引擎再快也不是"仿真"。所以先要这一条。</li>
 *   <li>{@link #dayThroughput()}：N 列车同时跑，量"每仿真秒的墙钟耗时"，换算出 24 h 的墙钟成本。</li>
 * </ol>
 *
 * <p>现场 id 全部来自 {@link TempSimProbeTests} 对当前存档的实测（2026-09-26 的环线测试图：
 * 下水 / 上水村 / 莫氏岛 / 叶楼村 四站 + 车辆段 zero 的 16 条 220 m 股道），**不抄 notes/171
 * 那份旧世界的坐标**（那些站台 id 早已不存在）。</p>
 *
 * <p>世界只读：跑在 {@code build/mmtr-sim-throughput/} 的副本上，线上存档不动。</p>
 */
public final class TempSimThroughputTests {

	private static final Path COPY = Path.of("build", "mmtr-sim-throughput", "world");

	// ---- 现场 id（TempSimProbeTests 实测）------------------------------------------------
	private static final long DEPOT_ZERO = -1731906840262692523L;
	/** 车辆段 zero 的 220 m 股道 zero/1..zero/16（发车股道按顺序取）。 */
	private static final long[] YARD_SIDINGS = {
		537638679563195181L, 316705188254233101L, 4084565393348666985L, 450776890460905114L,
		4246000951245645406L, -367662580282038469L, 5206411680157499265L, -5102686760880233364L,
		3150600533333717867L, -8853771979281491002L, 1818311891080095462L, 6419485943163399993L,
		-6090440211575118679L, 5996102637193839795L, 8081687240512649798L, 4002496488136410496L,
	};

	private static final long P_XIASHUI_1 = -5838243872289882445L;   // 下水 1 台 (-500,1800)→(-280,1800)
	private static final long P_XIASHUI_2 = -7360320992890955311L;   // 下水 2 台 (-500,1806)→(-280,1806)
	private static final long P_SHANGSHUI_1 = -7902208407899476055L; // 上水村 1 台 (800,1800)→(1020,1800)
	private static final long P_SHANGSHUI_2 = -5805698305068978768L; // 上水村 2 台 (800,1806)→(1020,1806)
	private static final long P_MOSHIDAO_1 = 3475021565469313725L;   // 莫氏岛 1 台 (2300,1800)→(2520,1800)
	private static final long P_MOSHIDAO_2 = -9204035618279042033L;  // 莫氏岛 2 台 (2300,1806)→(2520,1806)
	private static final long P_YELOUCUN_1 = 3933900461054836227L;   // 叶楼村 1 台 (3921,2309)→(4141,2309)
	private static final long P_YELOUCUN_2 = 6074986673008163648L;   // 叶楼村 2 台 (3921,2315)→(4141,2315)

	/** due 给足一整天：`loop=true` 时 due 会被换算成"相对圈首"的秒数（notes/171）。 */
	private static final long DUE_END_OF_DAY = 86_399_000L;

	/**
	 * 参数走**环境变量**（不是 -D）：PowerShell 调 {@code gradlew.bat} 时 {@code -Dp0.consists=4}
	 * 会被拆成两个参数、Gradle 把它当成任务名（实测报 "Task '.consists=4' not found"）。
	 */
	private static int intProperty(String key, int fallback) {
		final String raw = System.getenv(key.toUpperCase(java.util.Locale.ROOT).replace('.', '_').replace("P0_", "P0_"));
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	// ---- 作业单构造 ---------------------------------------------------------------------

	private static MmtrJobStep step(String id, MmtrJobStep.StepType type, long targetId, String note) {
		final MmtrJobStep step = new MmtrJobStep();
		step.stepId = id;
		step.type = type;
		step.targetId = targetId;
		step.dueTimeOfDayMs = DUE_END_OF_DAY;
		step.note = note;
		return step;
	}

	private static MmtrCarSpec car() {
		final MmtrCarSpec car = new MmtrCarSpec();
		car.vehicleId = "br101";
		car.length = 19.9652;
		car.width = 3.0836;
		car.bogie1Position = -5.723;
		car.bogie2Position = 5.723;
		car.powered = true;
		car.mmtrAutoCoupler = true;
		return car;
	}

	/**
	 * 这条线路的一天（现测试图）：出库 → 东行三站 → 叶楼村换端 → 西行三站 → 下水换端 → 回东。
	 *
	 * <p>两端都是"站台原地换端"（test map 是双线 z=1800/z=1806，每个站两个台），不需要折返轨 ——
	 * 这正是 2026-09-26 之后测试图的形态（与 notes/171 那个"单线 + 尽头支线折返"的旧图不同）。</p>
	 */
	private static MmtrConsistJob loopJob(String jobId, long sidingId, long startTimeOfDayMs) {
		final MmtrConsistJob job = new MmtrConsistJob();
		job.jobId = jobId;
		job.depotId = DEPOT_ZERO;
		job.sidingId = sidingId;
		job.startTimeOfDayMs = startTimeOfDayMs;
		job.repeatDaily = true;
		job.loop = true;
		job.loopEveryMs = 5_000L;
		job.cars.add(car());
		// 东行（z=1800 侧）
		job.steps.add(step("m1", MmtrJobStep.StepType.MOVE_TO, P_SHANGSHUI_1, "出库东行到 上水村 1 台"));
		job.steps.add(step("s1", MmtrJobStep.StepType.SERVE, P_SHANGSHUI_1, "上水村 1 台开关门"));
		job.steps.add(step("m2", MmtrJobStep.StepType.MOVE_TO, P_MOSHIDAO_1, "东行到 莫氏岛 1 台"));
		job.steps.add(step("s2", MmtrJobStep.StepType.SERVE, P_MOSHIDAO_1, "莫氏岛 1 台开关门"));
		job.steps.add(step("m3", MmtrJobStep.StepType.MOVE_TO, P_YELOUCUN_1, "东行到 叶楼村 1 台（终点）"));
		job.steps.add(step("s3", MmtrJobStep.StepType.SERVE, P_YELOUCUN_1, "叶楼村 1 台开关门"));
		job.steps.add(step("ceE", MmtrJobStep.StepType.CHANGE_ENDS, 0, "叶楼村换端（东端折返）"));
		// 西行（z=1806 侧）
		job.steps.add(step("m4", MmtrJobStep.StepType.MOVE_TO, P_MOSHIDAO_2, "西行到 莫氏岛 2 台"));
		job.steps.add(step("s4", MmtrJobStep.StepType.SERVE, P_MOSHIDAO_2, "莫氏岛 2 台开关门"));
		job.steps.add(step("m5", MmtrJobStep.StepType.MOVE_TO, P_SHANGSHUI_2, "西行到 上水村 2 台"));
		job.steps.add(step("s5", MmtrJobStep.StepType.SERVE, P_SHANGSHUI_2, "上水村 2 台开关门"));
		job.steps.add(step("m6", MmtrJobStep.StepType.MOVE_TO, P_XIASHUI_2, "西行到 下水 2 台（终点）"));
		job.steps.add(step("s6", MmtrJobStep.StepType.SERVE, P_XIASHUI_2, "下水 2 台开关门"));
		job.steps.add(step("ceW", MmtrJobStep.StepType.CHANGE_ENDS, 0, "下水换端（西端折返）"));
		job.steps.add(step("m7", MmtrJobStep.StepType.MOVE_TO, P_XIASHUI_1, "移线到 下水 1 台，准备下一圈东行"));
		return job;
	}

	// ---- 读数 ---------------------------------------------------------------------------

	private static final class VehicleSnapshot {
		final long id;
		final double progress;
		final double speed;
		final String mission;
		final String target;

		VehicleSnapshot(Vehicle vehicle) {
			id = vehicle.getId();
			progress = vehicle.getRailProgress();
			speed = vehicle.getSpeed();
			final var mission = vehicle.getMmtrMission();
			this.mission = mission == null ? "-" : mission.getState() + "/" + mission.getKind();
			target = mission == null ? "-" : String.valueOf(mission.getTargetSidingId()) + (mission.getFailureReason() == null || mission.getFailureReason().isEmpty() ? "" : " 失败=" + mission.getFailureReason());
		}

		@Override
		public String toString() {
			return "车" + Long.toHexString(id) + " 里程=" + Math.round(progress * 10) / 10.0 + "m 速度=" + Math.round(speed * 10) / 10.0
				+ " 任务=" + mission + " 目标=" + target;
		}
	}

	private static ObjectArrayList<VehicleSnapshot> snapshot(Simulator sim) {
		final ObjectArrayList<VehicleSnapshot> out = new ObjectArrayList<>();
		for (final Siding siding : sim.sidings) {
			siding.iterateVehicles(vehicle -> out.add(new VehicleSnapshot(vehicle)));
		}
		return out;
	}

	private static double totalDistanceM(Simulator sim) {
		final double[] total = {0};
		for (final Siding siding : sim.sidings) {
			siding.iterateVehicles(vehicle -> total[0] += Math.max(0, vehicle.getRailProgress()));
		}
		return total[0];
	}

	private static String hhmm(long dayTimeMillis) {
		final long seconds = Math.floorDiv(dayTimeMillis, 1000);
		return String.format("%02d:%02d:%02d", Math.floorDiv(seconds, 3600), Math.floorMod(Math.floorDiv(seconds, 60), 60), Math.floorMod(seconds, 60));
	}

	private static void deleteRecursively(Path path) throws IOException {
		if (!Files.exists(path)) {
			return;
		}
		try (final var stream = Files.walk(path)) {
			for (final Path p : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	/** 开一份干净副本 + 装上 N 份作业单（发车时刻按 stagger 错开）。 */
	private static Simulator openScenario(int consists, long staggerMillis) throws IOException {
		final Path live = TempSimProbeTests.liveWorld();
		deleteRecursively(COPY);
		TempSimProbeTests.copyRecursively(live, COPY);
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, COPY, false);
		// 必须走 upsert/delete（它们会重建调度器）：直接改 registry.jobs 不会让 mmtrJobScheduler 出现。
		for (final MmtrConsistJob existing : new ObjectArrayList<>(sim.mmtrJobRegistry.jobs)) {
			sim.deleteMmtrJob(existing.jobId);
		}
		for (int i = 0; i < consists; i++) {
			sim.upsertMmtrJob(loopJob("P0-" + (i + 1), YARD_SIDINGS[i % YARD_SIDINGS.length], 1_000L + i * staggerMillis));
		}
		sim.mmtrAiJobStepsEnabled = true;
		return sim;
	}

	// ---- ① 一列车跑得通吗 ---------------------------------------------------------------

	@Test
	public void singleConsistRoute() throws IOException {
		Assumptions.assumeTrue(Files.isDirectory(TempSimProbeTests.liveWorld()), "dev world save not present");
		final Simulator sim = openScenario(1, 0);
		System.out.println("[P0-ROUTE] 作业单=" + sim.mmtrJobRegistry.jobs.size() + " 段=" + sim.mmtrJobRegistry.jobs.get(0).steps.size()
			+ " 副本=" + COPY.toAbsolutePath());

		final long[] startedAt = {System.nanoTime()};
		final double[] lastProgress = {0};
		for (int elapsed = 30; elapsed <= 1_200; elapsed += 30) {
			sim.step(30_000);
			final long dayTime = sim.mmtrPlanDayTime();
			final MmtrJobScheduler scheduler = sim.mmtrJobScheduler;
			final String jobText = scheduler == null ? "-"
				: scheduler.stateOf("P0-1") + " 步=" + scheduler.stepIndexOf("P0-1") + " 圈=" + scheduler.cyclesOf("P0-1")
					+ (scheduler.failureOf("P0-1") == null ? "" : " 失败=" + scheduler.failureOf("P0-1"));
			final ObjectArrayList<VehicleSnapshot> vehicles = snapshot(sim);
			final double progress = vehicles.isEmpty() ? 0 : vehicles.get(0).progress;
			System.out.println("[P0-ROUTE] t=" + elapsed + "s 当日=" + hhmm(dayTime) + " 作业=" + jobText
				+ " 车数=" + vehicles.size() + " Δ里程=" + Math.round(progress - lastProgress[0]) + "m 合计=" + Math.round(progress) + "m");
			if (!vehicles.isEmpty()) {
				System.out.println("[P0-ROUTE]        " + vehicles.get(0));
			}
			lastProgress[0] = progress;
		}
		System.out.println("[P0-ROUTE] 墙钟 " + Math.round((System.nanoTime() - startedAt[0]) / 1e9 * 10) / 10.0 + "s（1200 仿真秒）"
			+ " ⇒ " + Math.round((System.nanoTime() - startedAt[0]) / 1e6 / 1200.0 * 100) / 100.0 + " ms/仿真秒");
	}

	// ---- ② N 列车同时跑，一天要多久 ---------------------------------------------------

	/**
	 * 量吞吐必须**关掉引擎的控制台日志**：引擎大量用 {@code System.out.println}（每步作业、每个子任务、
	 * 每次道岔申请都打），管道 stdout 的代价会整个盖过仿真本身 —— 第一版没关，量出 8.87 ms/仿真秒，
	 * 那个数里绝大部分是 I/O，不是引擎。
	 *
	 * <p>分两窗，因为这两个数才是"一天要多久"的上下界：</p>
	 * <ul>
	 *   <li><b>活跃窗</b>：所有车都在走（出库 + 东行）。一天的绝大多数时间本该是这个状态 ⇒ 用它的
	 *       ms/仿真秒 × 86400 报"一天墙钟"。</li>
	 *   <li><b>静置窗</b>：车都停着（当前地图西行会卡死，见 {@link #singleConsistRoute()}）⇒ 这是**下界**，
	 *       用来判断"卡住的车"会不会把仿真拖慢。</li>
	 * </ul>
	 */
	@Test
	public void dayThroughput() throws IOException {
		final int consists = intProperty("p0.consists", 4);
		final int warmupSeconds = intProperty("p0.warmup", 60);
		final int activeSeconds = intProperty("p0.activeSeconds", 600);
		final int idleSeconds = intProperty("p0.idleSeconds", 600);
		final long staggerMillis = intProperty("p0.staggerMs", 180_000);
		Assumptions.assumeTrue(Files.isDirectory(TempSimProbeTests.liveWorld()), "dev world save not present");

		final Simulator sim = openScenario(consists, staggerMillis);
		System.out.println("[P0-TP] 编组=" + consists + " 错开=" + staggerMillis / 1000 + "s 预热=" + warmupSeconds
			+ "s 活跃窗=" + activeSeconds + "s 静置窗=" + idleSeconds + "s");

		if (warmupSeconds > 0) {
			sim.step(warmupSeconds * 1_000L);
		}

		final double distanceBefore = totalDistanceM(sim);
		final long[] active = measure(sim, activeSeconds, 1_000L);
		final double distanceAfter = totalDistanceM(sim);
		final ObjectArrayList<VehicleSnapshot> vehicles = snapshot(sim);
		System.out.println("[P0-TP] 活跃窗：车数=" + vehicles.size() + " 总里程=" + Math.round(distanceAfter - distanceBefore)
			+ " m（" + Math.round((distanceAfter - distanceBefore) / Math.max(1, consists)) + " m/编组）");

		// 再往前推到静置（西行卡死之后），量静置窗
		sim.step(Math.max(0, 1_800 - warmupSeconds - activeSeconds) * 1_000L);
		final ObjectArrayList<VehicleSnapshot> stuck = snapshot(sim);
		final long[] idle = measure(sim, idleSeconds, 1_000L);
		final double idleDistance = totalDistanceM(sim);

		// 生产节拍：Main 每 10 ms tick 一次（每秒 100 tick）；step() 是每秒 1 tick —— 两者每 tick 的固定开销才是关键。
		final int productionSeconds = intProperty("p0.productionSeconds", 60);
		final long[] production = measure(sim, productionSeconds, 10L);

		// --- 读数（先关掉引擎日志，最后统一打） ---
		final MmtrJobScheduler scheduler = sim.mmtrJobScheduler;
		final StringBuilder report = new StringBuilder();
		report.append("[P0-TP] 卡住后的车（静置窗内总里程 ").append(Math.round(idleDistance - distanceAfter)).append(" m）：\n");
		for (final VehicleSnapshot vehicle : stuck) {
			report.append("[P0-TP]   ").append(vehicle).append('\n');
		}
		if (scheduler != null) {
			for (int i = 0; i < consists; i++) {
				final String jobId = "P0-" + (i + 1);
				report.append("[P0-TP]   ").append(jobId).append(" 态=").append(scheduler.stateOf(jobId))
					.append(" 步=").append(scheduler.stepIndexOf(jobId)).append(" 圈=").append(scheduler.cyclesOf(jobId))
					.append(scheduler.failureOf(jobId) == null ? "" : " 失败=" + scheduler.failureOf(jobId)).append('\n');
			}
		}
		System.out.print(report);

		final double activeMsPerSimSecond = active[0] / (double) activeSeconds;
		final double idleMsPerSimSecond = idle[0] / (double) idleSeconds;
		final double productionMsPerTick = production[0] / (double) Math.max(1, production[1]);
		final double productionMsPerSimSecond = productionMsPerTick * 100.0;   // 10 ms 一步 ⇒ 每秒 100 tick
		System.out.println("[P0-TP] ==== 活跃窗（1 s 步长）" + active[0] + " ms / " + activeSeconds + " 仿真秒 = "
			+ Math.round(activeMsPerSimSecond * 100) / 100.0 + " ms/仿真秒，共 " + active[1] + " tick（"
			+ Math.round(active[0] / (double) active[1] * 1000) / 1000.0 + " ms/tick）");
		System.out.println("[P0-TP] ==== 静置窗（1 s 步长）" + idle[0] + " ms / " + idleSeconds + " 仿真秒 = "
			+ Math.round(idleMsPerSimSecond * 100) / 100.0 + " ms/仿真秒，共 " + idle[1] + " tick（"
			+ Math.round(idle[0] / (double) idle[1] * 1000) / 1000.0 + " ms/tick）");
		System.out.println("[P0-TP] ==== 生产节拍（10 ms 步长）" + production[0] + " ms / " + productionSeconds + " 仿真秒 = "
			+ Math.round(productionMsPerSimSecond * 100) / 100.0 + " ms/仿真秒，共 " + production[1] + " tick（"
			+ Math.round(productionMsPerTick * 1000) / 1000.0 + " ms/tick）");
		System.out.println("[P0-TP] ==== 按活跃窗算：24 h 一天墙钟 ≈ " + Math.round(86_400 * activeMsPerSimSecond / 1000.0) + " s = "
			+ Math.round(86_400 * activeMsPerSimSecond / 60_000.0) + " min；倍速上限 ≈ "
			+ Math.round(1000.0 / Math.max(0.001, activeMsPerSimSecond)) + "×");
		System.out.println("[P0-TP] ==== 按静置窗算：24 h 一天墙钟 ≈ " + Math.round(86_400 * idleMsPerSimSecond / 60_000.0) + " min");
		System.out.println("[P0-TP] ==== 按生产节拍算：24 h 一天墙钟 ≈ " + Math.round(86_400 * productionMsPerSimSecond / 60_000.0) + " min"
			+ "（若按它跑，一天成本 = 生产节拍 ms/仿真秒 × 86400）");
	}

	/** 静音推进 {@code seconds} 仿真秒（步长 {@code sliceMillis}）并返回墙钟毫秒与 tick 数。 */
	private static long[] measure(Simulator sim, int seconds, long sliceMillis) {
		final java.io.PrintStream realOut = System.out;
		final long startedAt;
		long ticks = 0;
		try {
			System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
			startedAt = System.nanoTime();
			for (long elapsed = 0; elapsed < seconds * 1_000L; elapsed += sliceMillis) {
				sim.step(sliceMillis);
				ticks++;
			}
		} finally {
			System.setOut(realOut);
		}
		return new long[]{(System.nanoTime() - startedAt) / 1_000_000L, ticks};
	}
}
