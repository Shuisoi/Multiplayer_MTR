package org.mtr.core.mmtr.probe;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.sim.TempSimProbeTests;
import org.mtr.core.simulation.Simulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **真实世界存档上的服务端预算实测**（notes/337 的验收用例）。
 *
 * <h2>它回答的那个问题</h2>
 * <p>现场读数（notes/336 §3）：</p>
 * <pre>
 *   232 灯 / 280 轨 → Can't keep up! Running 19828ms / 396 ticks behind
 *   250 灯 / 294 轨 → Can't keep up! Running 40685ms / 813 ticks behind
 *   vehicles=0、无网页构建
 * </pre>
 * <p>也就是说：**空世界、没有车**，光靠地图规模就把服务端压到落后 40 s。<b>而"是谁吃掉的"
 * 一直分不清 —— 引擎的模拟 tick，还是别的东西？</b>这条用例把那 40 s 拆成两半，各量一遍：</p>
 *
 * <ol>
 *   <li><b>① 引擎模拟 tick</b>：{@code simulator.step(50)} 跑 N 步，量每步耗时。这一半是
 *       "仿真本身贵不贵"；</li>
 *   <li><b>② 游戏端每-tick 的那条链</b>：{@code lampAspectNames} + {@code lampBindings} ——
 *       也就是 {@code MmtrRouteMirror.tick} 每个服务端 tick 都要走的引擎调用。看门狗栈
 *       （notes/335 §4）指向的正是这一条里的 {@code protectedRailsOf → resolveProtectedRail → project}。</li>
 * </ol>
 *
 * <p>两半的对比就是结论：如果 ① 是零点几毫秒/步而 ② 是几百毫秒，那么 40 s 的落后**不在模拟里**，
 * 而在"每 tick 把每个结论重算一遍"的那条镜像链上 —— 于是"要不要做脏标记/节流"就不再是猜测。</p>
 *
 * <h2>为什么必须是"逐位相同"的量法</h2>
 * <p>探针只记录不改行为（notes/328 §R 把允许的优化钉死为逐位相同），所以这条用例量到的就是**现在这份
 * 实现**的代价。它不断言"优化后必须更快" —— 那是把测试写成许愿池。它断言的是三件**结构**上的事：</p>
 *
 * <ol>
 *   <li>引擎 tick 的每个阶段都被真的走到了（各 {@code 阶段名} 有数）—— 防止"探针静默失联"
 *       （埋点被重构掉之后读数恒 0，比没有读数更危险）；</li>
 *   <li>引擎 tick 本身有一个**宽松**的天花板（默认 5 ms/步 = tick 预算的 10%）：这条不是性能指标，
 *       而是"模拟别悄悄变成整帧瓶颈"的结构守卫（要改阈值传 {@code -Dmmtr.budget.tickMs=…}）；</li>
 *   <li>投影缓存在**重复查询**下必须命中（见 {@link MmtrProjectionKillCurveTests} 的同类断言）。</li>
 * </ol>
 *
 * <h2>端口</h2>
 * <pre>
 *   -Dmmtr.budget.steps=400        引擎跑多少步（每步 50 ms 仿真时间，400 步 ≈ 20 仿真秒）
 *   -Dmmtr.budget.bindRepeat=3     ② 那条链重复量几次（第 1 次冷、其后热 —— 差值就是缓存值多少）
 *   -Dmmtr.budget.tickMs=5         ① 单步耗时上限（毫秒）
 * </pre>
 *
 * <h2>量不到的东西（写在明处，免得把"没量到"当成"不贵"）</h2>
 * <ul>
 *   <li><b>32 个客户端</b>：{@code clients.sendUpdates} 的代价 ∝ 在线玩家 × 脏对象，无头环境是 0；
 *       要量它得开客户端，探针已经把这一段单列成 {@code clients.sendUpdates}；</li>
 *   <li><b>原版世界活</b>：区块生成/光照/方块实体（施工期上千条 {@code fill}）在 MC 服务端里，
 *       由游戏端的 {@code server.vanillaAndEngine} 与 {@code world.*} 两个读数回答；</li>
 *   <li><b>存档</b>：{@code save.auto} 段只在自动驾驶落盘那一拍才有数。</li>
 * </ul>
 */
public final class MmtrTickBudgetTests {

	private static final String DIMENSION = "minecraft/overworld";
	/** 副本根：与线上 world/mtr 同层级（里面直接是 minecraft/overworld）。 */
	private static final Path COPY = Paths.get("build", "mmtr-tick-budget", "world");

	/** 一步 = 20 TPS 的一帧（50 ms 仿真时间）。选它是因为服务端的 tick 就是这个节拍。 */
	private static final long STEP_MILLIS = 50L;

	@Test
	public void measureRealWorldTickBudget() throws IOException {
		final Path live = TempSimProbeTests.liveWorld();
		Assumptions.assumeTrue(Files.isDirectory(live.resolve("minecraft")), "dev world save not present - skipping");

		final int steps = Integer.getInteger("mmtr.budget.steps", 400);
		final int warmupSteps = Math.max(1, steps / 10);
		final int ceilingMillis = Integer.getInteger("mmtr.budget.tickMs", 5);
		final int bindRepeat = Integer.getInteger("mmtr.budget.bindRepeat", 3);

		TempSimProbeTests.deleteRecursively(COPY);
		TempSimProbeTests.copyRecursively(live, COPY);
		final Simulator simulator = new Simulator(DIMENSION, new String[]{DIMENSION}, COPY, false);

		// 引擎自己的量表（rails/lamps/points）= 后面所有读数的主语：脱离规模谈毫秒没有意义。
		final int rails = simulator.rails.size();
		final int lamps = simulator.mmtrSignals.signals.size();
		final int sections = simulator.mmtrSections.sectionCount();
		final int points = simulator.mmtrAllTurnouts().size();
		System.out.println("[BUDGET] world=" + live.toAbsolutePath());
		System.out.println("[BUDGET] 规模 rails=" + rails + " lamps=" + lamps + " sections=" + sections
			+ " turnouts=" + points + " vehicles=" + vehicleCount(simulator)
			+ "（现场那条 40 s 的读数正是 vehicles=0 时的）");

		// 探针：不写文件（读数进 stdout），只在这个进程里开。
		MmtrProbe.setFile("");
		MmtrProbe.resetAll();
		MmtrProbe.setEnabled(true);

		// 预热：JIT 与一级缓存都要先热起来，否则量到的是"第一次跑"而不是"稳态"。
		simulator.step(warmupSteps * STEP_MILLIS);
		MmtrProbe.resetAll();

		// ---- ① 引擎模拟 tick ----
		final long[] perStepNanos = new long[steps];
		final long wallStart = System.nanoTime();
		for (int i = 0; i < steps; i++) {
			final long start = System.nanoTime();
			simulator.step(STEP_MILLIS);
			perStepNanos[i] = System.nanoTime() - start;
		}
		final long wallNanos = System.nanoTime() - wallStart;
		java.util.Arrays.sort(perStepNanos);
		final long sumNanos = java.util.Arrays.stream(perStepNanos).sum();
		final double msPerTick = sumNanos / 1_000_000.0 / steps;
		System.out.println(String.format(java.util.Locale.ROOT,
			"[BUDGET] ① 引擎 %d 步（每步 %d ms 仿真）墙钟 %d ms ⇒ 平均 %.2f ms/步 | p50=%d p95=%d p99=%d max=%d ms",
			steps, STEP_MILLIS, wallNanos / 1_000_000L, msPerTick,
			perStepNanos[steps / 2] / 1_000_000L, perStepNanos[(int) (steps * 0.95)] / 1_000_000L,
			perStepNanos[(int) (steps * 0.99)] / 1_000_000L, perStepNanos[steps - 1] / 1_000_000L));
		System.out.println(String.format(java.util.Locale.ROOT,
			"[BUDGET] ① 折算：20 TPS 下引擎占用 %.1f%% 的 tick 预算（%.2f ms / 50 ms；100%% = 20 TPS 跑不动）",
			msPerTick / 50.0 * 100.0, msPerTick));

		final List<String> engineSummary = MmtrProbe.summaryLines(DIMENSION);
		for (final String line : engineSummary) {
			System.out.println("[BUDGET] " + line.replace("[MMTR-PROBE] ", ""));
		}

		// ---- ② 游戏端每-tick 的那条链（MmtrRouteMirror 走的就是它） ----
		MmtrProbe.resetAll();
		System.out.println("[BUDGET] ② 游戏端每-tick 链：lampAspectNames + lampBindings（每盏灯 × 每根候选轨的弧投影都在这里）");
		long firstCold = 0;
		long firstWarm = 0;
		for (int repeat = 1; repeat <= Math.max(1, bindRepeat); repeat++) {
			final long start = System.nanoTime();
			simulator.mmtrSections.lampAspectNames(simulator.mmtrOccupancyTrees(), node -> false);
			final long aspectsMillis = (System.nanoTime() - start) / 1_000_000L;
			final long start2 = System.nanoTime();
			simulator.mmtrSignals.signals.forEach((key, entry) -> simulator.mmtrSections.protectedRailsOf(entry));
			final long bindingsMillis = (System.nanoTime() - start2) / 1_000_000L;
			if (repeat == 1) {
				firstCold = aspectsMillis + bindingsMillis;
			}
			firstWarm = aspectsMillis + bindingsMillis;
			System.out.println(String.format(java.util.Locale.ROOT,
				"[BUDGET] ② 第 %d 次：lampAspects=%d ms  lampRails=%d ms  合计=%d ms（一个服务端 tick 里这条链要走一次）",
				repeat, aspectsMillis, bindingsMillis, aspectsMillis + bindingsMillis));
		}
		final List<String> mirrorSummary = MmtrProbe.summaryLines(DIMENSION);
		for (final String line : mirrorSummary) {
			System.out.println("[BUDGET] " + line.replace("[MMTR-PROBE] ", ""));
		}
		final double mirrorHitRate = hitRate(mirrorSummary);
		System.out.println(String.format(java.util.Locale.ROOT,
			"[BUDGET] ② 投影命中率 %.1f%%（第 1 次 %d ms → 其后 %d ms，冷热比 ≈ %.0f×）",
			mirrorHitRate * 100.0, firstCold, firstWarm, firstWarm == 0 ? 0.0 : (double) firstCold / firstWarm));

		// ---- 断言（三条结构性的，见类注释） ----
		final String joined = String.join("\n", engineSummary);
		for (final String stage : new String[]{"rails.tick1", "rails.tick2", "vehicles.simulate", "signal.aspectView", "clients.sendUpdates"}) {
			assertTrue(joined.contains(stage), "探针 " + stage + " 没有读数 —— 埋点失联（被重构掉了？）：\n" + joined);
		}
		assertTrue(msPerTick <= ceilingMillis,
			"引擎单步平均 " + msPerTick + " ms 超过天花板 " + ceilingMillis + " ms（tick 预算 50 ms）");

		// ② 的读数必须存在：投影的次数与命中都在这一档里，它们是"要不要动缓存"的唯一依据。
		final long solves = ProbeReadout.callCount(mirrorSummary, "projection.solve=");
		final long hits = ProbeReadout.eventCount(mirrorSummary, "projection.hit=");
		assertTrue(solves + hits > 0,
			"② 那条链没有产生任何投影读数 —— 埋点失联或链本身没跑起来：\n" + String.join("\n", mirrorSummary));
		assertTrue(mirrorHitRate >= 0.5, "② 重复调用后的投影命中率只有 " + mirrorHitRate
			+ " —— 缓存没在起作用（notes/335 §4 的形态：MAX_ENTRIES 撑爆后整体 clear()）");

		MmtrProbe.setEnabled(false);
	}

	/**
	 * ② 的投影命中率。
	 *
	 * <p>两条读数的**字段位置不同**，这是刻意的，也是这里最容易读错的地方（{@link ProbeReadout} 里记了
	 * 三个踩过的坑）：{@code projection.solve=12ms/48} 是**计时段**（冷算次数在 {@code /} 后面），
	 * 而 {@code projection.hit=0ms/0 ev=1234} 是**事件**（命中不耗时，次数在 {@code ev=} 后面）。
	 * 所以命中率 = hit 的 ev ÷ (hit 的 ev + solve 的次数)。</p>
	 */
	private static double hitRate(List<String> summary) {
		final long hits = ProbeReadout.eventCount(summary, "projection.hit=");
		final long solves = ProbeReadout.callCount(summary, "projection.solve=");
		final long total = hits + solves;
		return total == 0 ? 0.0 : (double) hits / total;
	}

	private static int vehicleCount(Simulator simulator) {
		final int[] count = {0};
		simulator.sidings.forEach(siding -> siding.iterateVehicles(vehicle -> count[0]++));
		return count[0];
	}
}
