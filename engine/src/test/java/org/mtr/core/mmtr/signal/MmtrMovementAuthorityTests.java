package org.mtr.core.mmtr.signal;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2: 行车许可 —— 信号显示 → "能走到哪、到那儿该多快"。
 *
 * <p>这一片**只算不停**：没有任何停车规则读它（那是 T3）。所以除了下面这些映射用例，
 * 还有一条同样重要的验收是**全量零期望值改动** —— 算得再准也不许改变既有行为，
 * 那一半由全量套件（553/0/4 不变）证明，不在这里写成断言。</p>
 *
 * <p>测试边界：**"下一架管我的信号是哪一架"这条规则不在这里测** —— 它是
 * {@link MmtrSignalAspect#aspectFrom} 的结论，由 {@code MmtrDirectionalBlockServiceTests}
 * 那一整套（v2 选腿 / 轨中段的灯 / 岔口多腿 / 折返双候选）负责。本类只测**映射**与**接线**：
 * 映射是纯函数，接线是一行委托（{@link MmtrMovementAuthority#forApproach} 只调一次
 * {@code aspectFrom}，灯显读的也是它）。</p>
 */
public final class MmtrMovementAuthorityTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	private static Rail rail(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/** 夹具里从节点向东的那根轨（`simulator.rails` 不可按下标取，所以留个引用）。 */
	private static Rail eastRail;

	private static Simulator sim(String path) {
		final Simulator simulator = new Simulator("test", new String[]{"test"}, Paths.get(path), false);
		simulator.rails.add(rail(new Position(-30, 0, 0), new Position(0, 0, 0)));
		eastRail = rail(new Position(0, 0, 0), new Position(30, 0, 0));
		simulator.rails.add(eastRail);
		simulator.sync();
		return simulator;
	}

	/** ① 红灯 = **唯一的停车义务**来源，且带上到它的距离。 */
	@Test
	public void redIsTheOnlyStopObligationAndItCarriesTheDistance() {
		final MmtrMovementAuthority a = MmtrMovementAuthority.of(MmtrSignalAspect.Aspect.RED, "ABCDEF", 137.5);
		assertTrue(a.mustStop(), "红灯必须停");
		assertTrue(a.hasTarget(), "红灯有目标位置");
		assertEquals(0, a.targetSpeedKmh, "目标速度 0");
		assertEquals(137.5, a.targetDistanceM, 1e-9, "目标距离 = 到那架信号的距离");
		assertEquals("ABCDEF", a.targetRailHex, "目标在那架信号守的轨上");
		assertFalse(a.cautionOnly, "红灯不是只注意，而是停车义务");
		assertTrue(a.reason.contains(String.valueOf(Math.round(137.5))), "理由串报出距离（四舍五入后）：" + a.reason);
	}

	/** ① 绿灯：无约束（轨限速与闭塞说了算）。 */
	@Test
	public void greenImposesNothing() {
		final MmtrMovementAuthority a = MmtrMovementAuthority.of(MmtrSignalAspect.Aspect.GREEN, "ABCDEF", 42);
		assertFalse(a.hasTarget(), "绿灯没有目标");
		assertFalse(a.mustStop(), "绿灯不停车");
		assertFalse(a.cautionOnly, "绿灯也不是注意");
		assertEquals(MmtrMovementAuthority.NO_TARGET_M, a.targetDistanceM, 1e-9);
	}

	/**
	 * ① 黄灯 = **只有注意义务**，不给停车目标。
	 *
	 * <p>这是本片唯一的取舍（notes/119 写明理由）：四显示是一条链，单黄的意思正是"下一架是红"，
	 * 列车往前开一段就会读到那盏红灯，**停车义务在那一刻自然产生**。所以黄灯不需要"停在下一架信号前"
	 * 这个跨区间的目标距离 —— 那个距离要按行进方向算方向性区间的长度（这一段最容易写错），
	 * 而它是多余的。黄灯真正承担的是预告（AWS 的响与确认，notes/103 已实机验收）。</p>
	 */
	@Test
	public void yellowAspectsCarryCautionButNoStopObligation() {
		for (final MmtrSignalAspect.Aspect aspect : new MmtrSignalAspect.Aspect[]{MmtrSignalAspect.Aspect.SINGLE_YELLOW, MmtrSignalAspect.Aspect.DOUBLE_YELLOW}) {
			final MmtrMovementAuthority a = MmtrMovementAuthority.of(aspect, "ABCDEF", 60);
			assertTrue(a.cautionOnly, aspect + " 只有注意义务");
			assertFalse(a.hasTarget(), aspect + " 不给停车目标（红灯会自己来）");
			assertFalse(a.mustStop(), aspect + " 不构成停车义务");
			assertEquals(aspect, a.aspect, "显示仍然如实记录");
		}
	}

	/** 红灯的距离不给负数（车已经压在信号上时不许算出一个"负的停车点"）。 */
	@Test
	public void aRedRightOnTopOfTheTrainStillYieldsZeroDistance() {
		final MmtrMovementAuthority a = MmtrMovementAuthority.of(MmtrSignalAspect.Aspect.RED, "ABCDEF", -5);
		assertEquals(0, a.targetDistanceM, 1e-9, "距离被夹到 0，不出现负目标");
		assertTrue(a.mustStop(), "仍然必须停");
	}

	/**
	 * 接线：{@code forApproach} 报的显示必须**就是**灯显自己那一份结论。
	 *
	 * <p>用的是同一个 {@code aspectFrom}，所以这一条是"不许有人在这里另写一套"的结构性守卫 ——
	 * 一旦有人在许可这条路上重新推导显示，这个断言就会红。</p>
	 */
	@Test
	public void theAuthorityReportsExactlyWhatTheLampsShow() {
		final Simulator simulator = sim("build/mmtr-authority-wiring");
		final Rail east = eastRail;
		final Position node = new Position(0, 0, 0);
		final MmtrSignalAspect.Aspect lampView = simulator.mmtrSignalAspectView().aspectFrom(east.getHexId(), node, 7L);
		final MmtrMovementAuthority authority = MmtrMovementAuthority.forApproach(simulator, east.getHexId(), node, 7L, 88);
		assertEquals(lampView, authority.aspect, "许可报的显示 = 灯显的显示（同源，不是第二套算法）");
		if (lampView == MmtrSignalAspect.Aspect.RED) {
			assertTrue(authority.mustStop(), "灯红 → 许可要求停车");
			assertEquals(88, authority.targetDistanceM, 1e-9, "距离由调用方（车的走行空间）提供");
		}
	}

	/** 没有走行位置的车（或轨解不出来）：不产生任何许可，也不许谎报红灯。 */
	@Test
	public void noPositionMeansNoAuthority() {
		final Simulator simulator = sim("build/mmtr-authority-nopos");
		assertFalse(MmtrMovementAuthority.forVehicle(simulator, null, 1L).hasTarget(), "没有走行位置就没有目标");
		assertFalse(MmtrMovementAuthority.forVehicle(simulator, null, 1L).mustStop(), "也不许谎报停车义务");
		assertFalse(MmtrMovementAuthority.forApproach(simulator, null, null, 1L, 10).mustStop(), "轨为空同样");
	}
}
