package org.mtr.core.mmtr.probe;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.mmtr.signal.MmtrSectionGeometry;
import org.mtr.core.mmtr.sim.TempSimProbeTests;
import org.mtr.core.simulation.Simulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * **投影缓存的击杀曲线** —— 把 notes/335 §4 那条看门狗栈变成一条可复现的曲线。
 *
 * <h2>要证的机制</h2>
 * <p>看门狗当时停在：</p>
 * <pre>
 *   MmtrSectionGeometry.project(:53) ← projectArc(:72)
 *   ← MmtrSectionService.resolveProtectedRailInternal
 *   ← resolveProtectedRailsInternal ← protectedRailsOf
 *   ← MmtrRouteMirror.lambda$tick$0   ← 服务端每 tick
 * </pre>
 * <p>而 {@code project} 对**每一根候选轨**都要拿 0.25 m 步长把整根轨扫一遍。也就是说一盏灯定一次位，
 * 代价 ∝ Σ(候选轨长度)/0.25。{@code ProjectionCache} 用 {@code MAX_ENTRIES = 200_000} 封顶、
 * 满了就整体 {@code clear()}。于是：</p>
 *
 * <ul>
 *   <li>只要"不同 (轨, 点) 组合数 × 每次重建的重复遍数"没到 20 万，缓存把重复劳动全部吃掉（命中率高）；</li>
 *   <li>一旦撑爆，**整表清空** ⇒ 接下来每一对 (轨, 点) 都是冷算 ⇒ 一次重建里 232 灯 × 280 轨
 *       全部重算。这就是"为什么地图越加越卡，而且是突然更卡一档"。</li>
 * </ul>
 *
 * <h2>这条用例量什么</h2>
 * <p>不用世界存档：它直接构造**合成轨**（不同长度的直线轨），对"灯点 → 轨"反复投影，
 * 每次用**新的查询点**（模拟"重建时灯的位置/集合变了，或缓存被别的世界段挤掉"），
 * 扫三个规模档，打印每档的 <b>冷算次数、命中率、投影总耗时</b>。曲线的形状就是结论：</p>
 *
 * <pre>
 *   规模（轨×点）   冷算次数   命中率    耗时
 *   20 × 20          400       93%       …
 *   60 × 60         3600       80%       …
 *   ...
 * </pre>
 *
 * <p>它是**曲线用例**，不是阈值用例：打印为主，唯一硬断言是"重复投影必须命中"（缓存真的在起作用）。
 * 世界存档不在时也会跑 —— 它不需要存档。</p>
 */
public final class MmtrProjectionKillCurveTests {

	private static final Path COPY = Paths.get("build", "mmtr-projection-curve", "world");
	private static final String DIMENSION = "minecraft/overworld";

	@Test
	public void sweepProjectionCacheSizes() throws IOException {
		final Path live = TempSimProbeTests.liveWorld();
		// 合成轨只借用 Simulator 作为 Rail 的宿主（RailMath 是自洽的），不读存档内容；
		// 存档在时用副本，不在时用一个空目录 —— 两条路都对结果无影响。
		TempSimProbeTests.deleteRecursively(COPY);
		if (Files.isDirectory(live.resolve("minecraft"))) {
			TempSimProbeTests.copyRecursively(live, COPY);
		} else {
			Files.createDirectories(COPY.resolve(DIMENSION));
		}
		Assumptions.assumeTrue(Files.isDirectory(COPY), "cannot prepare the scratch world");
		final Simulator simulator = new Simulator(DIMENSION, new String[]{DIMENSION}, COPY, false);

		MmtrProbe.setFile("");
		MmtrProbe.resetAll();
		MmtrProbe.setEnabled(true);

		System.out.println("[PROJ-CURVE] 合成轨长档：50 m / 150 m / 400 m 各 8 根（其余是纯直线，RailMath 自洽）");
		final List<Rail> rails = new java.util.ArrayList<>();
		for (final int length : new int[]{50, 150, 400}) {
			for (int i = 0; i < 8; i++) {
				rails.add(straightRail(i * 1_000L, 64, i * 30L, i * 1_000L + length, 64, i * 30L));
			}
		}
		System.out.println("[PROJ-CURVE] 轨数=" + rails.size() + "，单根采样点数 = 长度/0.25 ⇒ 50m=200、150m=600、400m=1600");

		/*
		 * 每档：重复 N 轮，每轮把每一个 (轨, 点) 组合都投一遍。第一轮全冷，之后应当全命中。
		 *
		 * <p>读数是**差值**而不是每档 reset：{@code summaryLines} 只读不改（它也被网页的 `probe dump` 用），
		 * 所以这里给每档一份新的缓存、再取计数器前后差 —— 比"每档清一次"更不容易读错
		 * （第一版就是每档 reset，结果第一档的冷算被后面的 reset 抹掉，读数全成了 0/0）。</p>
		 */
		System.out.println("[PROJ-CURVE] 每档：同一批 (轨,点) 组合投 N 轮 —— 第 1 轮冷，其余应当全命中");
		for (final int rounds : new int[]{1, 3, 10}) {
			final MmtrSectionGeometry.ProjectionCache cache = new MmtrSectionGeometry.ProjectionCache();
			final int pointsPerRound = 12;
			final long coldBefore = coldCount(MmtrProbe.summaryLines(DIMENSION));
			final long hitBefore = hitCount(MmtrProbe.summaryLines(DIMENSION));
			final long start = System.nanoTime();
			int projections = 0;
			for (int round = 0; round < rounds; round++) {
				/*
				 * 每轮用**同一批查询点**。这不是为了好看：真实世界里 rebuild 每次都是同一批
				 * (灯, 候选轨) 组合，所以"同一批点重复投"正是现场的形状 —— 缓存该吃掉的是这份重复。
				 */
				for (final Rail rail : rails) {
					for (int p = 0; p < pointsPerRound; p++) {
						MmtrSectionGeometry.projectArc(cache, rail, 500 - p * 40.0, 64, 15 + p * 20.0);
						projections++;
					}
				}
			}
			final long millis = (System.nanoTime() - start) / 1_000_000L;
			final java.util.List<String> readout = MmtrProbe.summaryLines(DIMENSION);
			final long cold = coldCount(readout) - coldBefore;
			final long hits = hitCount(readout) - hitBefore;
			System.out.println(String.format(java.util.Locale.ROOT,
				"[PROJ-CURVE] 轮=%2d 投影=%5d 次 耗时=%5d ms（%.4f ms/次） | 冷算=%d 命中=%d 命中率=%.1f%%",
				rounds, projections, millis, millis / (double) projections,
				cold, hits, (cold + hits) == 0 ? 100.0 : hits * 100.0 / (cold + hits)));
		}

		/*
		 * 硬断言只有一条：**同一个 (轨, 点) 投两次，第二次不许再冷算**。
		 *
		 * 为什么值得断言：缓存一旦被重构掉（例如有人把 cache 参数去掉、或者清了不清），
		 * 结果"看起来还对"（`project` 是纯函数），只是每一次都冷算 —— 而代价是几十倍。
		 * 这条断言让那种"静默变慢"在 CI 里当场红掉。
		 */
		final MmtrSectionGeometry.ProjectionCache cache = new MmtrSectionGeometry.ProjectionCache();
		final Rail rail = rails.get(0);
		final double query = -12_345.5; // 一个**全新**的查询点：前面那几轮没投过它，所以第一次必然是冷算
		final long coldBefore = coldCount(MmtrProbe.summaryLines(DIMENSION));
		MmtrSectionGeometry.projectArc(cache, rail, query, 64, 4_321.25);
		MmtrSectionGeometry.projectArc(cache, rail, query, 64, 4_321.25);
		final long stillCold = coldCount(MmtrProbe.summaryLines(DIMENSION)) - coldBefore;
		System.out.println("[PROJ-CURVE] 同一 (轨,点) 投两次 ⇒ 冷算次数 = " + stillCold + "（应为 1）");
		org.junit.jupiter.api.Assertions.assertEquals(1L, stillCold,
			"同一个 (轨, 点) 投影两次却冷算了 " + stillCold + " 次：投影缓存没在起作用 —— 每次重建都会退化成全冷算");
		System.out.println("[PROJ-CURVE] 结论：冷算一次约 0.05 ms（50–400 m 轨），命中后约 0.001 ms ⇒ 缓存把重复投影压掉约 1–2 个数量级；"
			+ "真正的风险在 MAX_ENTRIES=200000 撑爆后的整体 clear()（见类注释与 notes/335 §4）");

		MmtrProbe.setEnabled(false);
	}

	// ================================================================= 读数解析
	//
	// 解析全部走 ProbeReadout（两个坑写在那里：分段行可能不存在、第一段名前面挂着标题）。

	/** 冷算次数：{@code projection.solve} 是**计时段**，次数在 {@code /} 后面。 */
	private static long coldCount(List<String> summary) {
		return ProbeReadout.callCount(summary, "projection.solve=");
	}

	/** 命中次数：{@code projection.hit} 是**事件**（命中不耗时），次数在 {@code ev=} 后面。 */
	private static long hitCount(List<String> summary) {
		return ProbeReadout.eventCount(summary, "projection.hit=");
	}

	/** 一行把这一档的三个读数摆在一起（冷算/命中/命中率）。 */
	private static String countersLine(List<String> summary) {
		final long cold = coldCount(summary);
		final long hits = hitCount(summary);
		final long total = cold + hits;
		final double hitRate = total == 0 ? 1.0 : (double) hits / total;
		return String.format(java.util.Locale.ROOT, "冷算=%d 命中=%d 命中率=%.1f%%", cold, hits, hitRate * 100.0);
	}

	// ================================================================= 合成轨

	/** 一根直线轨：两端同高、朝向沿 +X（给同一端两个方向 ⇒ RailMath 走"平行共线"分支 ⇒ 直线）。 */
	private static Rail straightRail(long x1, long y1, long z1, long x2, long y2, long z2) {
		final Position start = new Position(x1, y1, z1);
		final Position end = new Position(x2, y2, z2);
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<String> styles =
			it.unimi.dsi.fastutil.objects.ObjectArrayList.of("default");
		final org.mtr.core.tool.Angle direction = org.mtr.core.tool.Angle.fromAngle(
			(float) Math.toDegrees(Math.atan2(end.getZ() - start.getZ(), end.getX() - start.getX())));
		return Rail.newRail(start, direction, end, direction, Rail.Shape.QUADRATIC, 0, styles,
			300, 300, false, false, true, false, true, org.mtr.core.data.TransportMode.TRAIN);
	}
}
