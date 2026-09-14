package org.mtr.core.mmtr.plan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P4：**走行时间从轨图算**（{@link MmtrRailDistance} / {@link MmtrRailTravelTimes}）。
 *
 * <p>夹具是一条直线站场：A(-0) —40 m— B(-40) —60 m— C(-100)，三个站台各贴在对应的一段轨上
 * （站台就是"被标记的一段轨"，取轨走 {@code SavedRailBase.mmtrGraphRail()}，与
 * {@code MmtrRunPlanner} 解析站台目标是同一条路）。</p>
 *
 * <p>这里测的是**里程**，不是"几秒"：速度换了、限速改了，里程不变 —— 所以断言钉在里程上，
 * 时间只钉"里程 ÷ 速度"这一条换算。</p>
 */
public final class MmtrRailTravelTimesTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/**
	 * 站台 A(-200..-180) —20 m 连接轨— 站台 B(-160..-100) 的直线站场。
	 *
	 * <p>**站台之间要有一根连接轨**才测得出东西：站台本身就是一段轨，两个相邻站台若直接共用端点，
	 * 它们之间的里程就是 0（真实站场里两站之间当然有一串轨）。这条夹具照现实来搭。</p>
	 */
	private static final class Net {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-plan-travel-times"), false);
		final Position a = new Position(-200, 0, 0);
		final Position b = new Position(-180, 0, 0);
		final Position c = new Position(-160, 0, 0);
		final Position d = new Position(-100, 0, 0);

		Net() {
			sim.rails.add(through(a, b));     // 站台 1（20 m）
			sim.rails.add(through(b, c));     // 站间连接轨（20 m）
			sim.rails.add(through(c, d));     // 站台 2（60 m）
			sim.sync();
			sim.platforms.add(new Platform(a, b, TransportMode.TRAIN, sim));
			sim.platforms.add(new Platform(c, d, TransportMode.TRAIN, sim));
		}

		long platformId(int index) {
			final long[] ids = new long[sim.platforms.size()];
			final int[] cursor = {0};
			sim.platforms.forEach(platform -> ids[cursor[0]++] = platform.getId());
			return ids[index];
		}
	}

	/** 相邻两站的里程 = 两站台之间那段轨的里程（站台自己那两根轨不算进去）。 */
	@Test
	public void legDistanceComesFromTheTrackBetweenThePlatforms() {
		final Net n = new Net();
		final Rail first = MmtrRailDistance.railOfPlatform(n.sim, n.platformId(0));
		final Rail second = MmtrRailDistance.railOfPlatform(n.sim, n.platformId(1));
		assertNotNull(first);
		assertNotNull(second);
		assertEquals(20.0, MmtrRailDistance.between(n.sim, first, second), 0.5, "A→B 是那根 20 m 的连接轨");
		assertEquals(20.0, MmtrRailDistance.between(n.sim, second, first), 0.5, "反过来也一样（轨图无向）");
		assertEquals(0.0, MmtrRailDistance.between(n.sim, first, first), "自己到自己 = 0");
	}

	/** 站台 → 轨的解析走的是 {@code mmtrGraphRail}（站台两端点之间那根轨）；不存在 = null。 */
	@Test
	public void platformRailsResolveThroughTheGraphRail() {
		final Net n = new Net();
		assertNotNull(MmtrRailDistance.railOfPlatform(n.sim, n.platformId(0)));
		assertNull(MmtrRailDistance.railOfPlatform(n.sim, 999_999), "不存在的站台不该编出一根轨来");
	}

	/** 走行时间 = 里程 ÷ 速度；站序反着取也一致（返程用同一张腿表反向取）。 */
	@Test
	public void travelTimeIsDistanceOverSpeed() {
		final Net n = new Net();
		final MmtrLine line = new MmtrLine("L1", "");
		line.addStop(1, n.platformId(0), 30_000);
		line.addStop(2, n.platformId(1), 30_000);
		final MmtrRailTravelTimes times = MmtrRailTravelTimes.of(n.sim, line, 72);
		// 20 m @ 72 km/h = 20 m/s → 1 s
		assertEquals(1_000, times.legMillis(0, 1), 50, "20 m ÷ 20 m/s = 1 s");
		assertEquals(1_000, times.legMillis(1, 0), 50, "反向同一条腿");
		assertEquals(20.0, times.legMeters(1), 0.5);
		assertEquals(0.0, times.legMeters(0), 0.5, "起点站那一段是 0");
		assertEquals(MmtrRailTravelTimes.DEFAULT_TERMINAL_MILLIS, times.terminalMillis(MmtrLine.TerminalTreatment.CHANGE_ENDS));
		assertNull(times.describeMissingLegs(), "站序与轨图对得上");

		// 整条线路的 ring 也应该算得出来（走行 + 停站 + 两端处理）
		final long ring = MmtrServicePlan.ringMillis(line, times);
		assertTrue(ring > 4_000, "往返 ≥ 两段 2 s × 2 + 停站：实际 " + ring);
	}

	/** 站序与轨图对不上时**明说**，而不是编一个数字（诊断要能一眼看出配置错了）。 */
	@Test
	public void aLegThatCannotBeWalkedIsReportedRatherThanGuessed() {
		final Net n = new Net();
		final MmtrLine line = new MmtrLine("L1", "");
		line.addStop(1, n.platformId(0), 30_000);
		line.addStop(2, 999_999, 30_000);   // 不存在的站台
		final MmtrRailTravelTimes times = MmtrRailTravelTimes.of(n.sim, line, 72);
		assertEquals(0, times.legMillis(0, 1), "走不到就不给数字");
		assertEquals(-1, times.legMeters(1), 0.001, "里程标记为 -1（走不到）");
		assertNotNull(times.describeMissingLegs(), "要说清是哪一段对不上");
		assertTrue(times.describeMissingLegs().contains("999999"), times.describeMissingLegs());
	}
}
