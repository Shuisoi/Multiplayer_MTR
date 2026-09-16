package org.mtr.core.mmtr.point;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * notes/171 现场缺陷（2026-09-16）的回归用例：**位置队列必须自己再试一次**。
 *
 * <p>现场读数（1↔3 站循环作业的南折返步）：{@code 物理位置=0 / 物理持有者=（没有）/ 等待队列=[v…@1]}，
 * 车停在站台上等了三分钟，只有人工扳一次道岔才把它救出来。原因：请求改位置被**净空闸**挡下时会进
 * {@code physicalQueued}，而推进队列的 {@code promotePhysical} 只在"持有者释放/窗口过期"两个事件里被调用
 * —— "位置本来就没人持有"这条路上，没有任何事件会再来一次。</p>
 *
 * <p>现在 {@link MmtrPointAuthority#retryPhysicalQueues(long)} 由 {@code Simulator} 每 tick 调一次：
 * 位置没人持有时重排队首、按同一条净空闸再判一次；闸还挡着就继续排队（并节流报原因）。</p>
 */
public final class MmtrPointQueueRetryTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final long WINDOW_MILLIS = 10L * 60 * 1000;

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/** 与 {@code MmtrTurnoutAuthorityTests} 同形的单开道岔夹具：正线 stem ↔ far，岔股 = branch。 */
	private static Simulator forkNet(String path) {
		final Simulator simulator = new Simulator("test", new String[]{"test"}, Paths.get(path), false);
		final Position node = new Position(0, 0, 0);
		simulator.rails.add(through(new Position(-20, 0, 0), node));
		simulator.rails.add(through(node, new Position(20, 0, 0)));
		simulator.rails.add(through(node, new Position(20, 0, 14)));
		simulator.sync();
		return simulator;
	}

	private static long until(Simulator simulator) {
		return simulator.getCurrentMillis() + WINDOW_MILLIS;
	}

	/**
	 * 净空闸挡下 → 排队；闸清了（车走了、区间空了）→ **没有释放事件**，只有每 tick 的重试把位置发出去。
	 */
	@Test
	public void thePositionQueueAdvancesItselfWhenTheClearanceClears() {
		final Simulator simulator = forkNet("build/mmtr-point-queue-retry");
		final MmtrTurnout turnout = simulator.mmtrTurnout(0, 0, 0);
		assertNotNull(turnout, "夹具必须是一处单开道岔");
		final int branchToStem = turnout.stemLeg.getOrDefault(turnout.branchRailHex, -1);
		assertTrue(branchToStem >= 0, "夹具的几何腿号要能取到");

		// 净空闸：暂时不许改位置（模拟"岔区被别的车压着"）。
		final boolean[] blocked = {true};
		simulator.mmtrPointAuthority.withPositionChangeGuard((x, y, z, newPosition, owner) -> blocked[0] ? "测试闸门：岔区暂不清空" : null);

		// 岔股侧申请位置 1：位置没人持有，但闸门挡着 ⇒ 进位置队列（不是逐进向队列）。
		assertEquals(MmtrPointAuthority.Result.QUEUED, simulator.mmtrPointAuthority.request(
			0, 0, 0, turnout.branchRailHex, "vB", branchToStem, until(simulator)), "净空被挡 ⇒ 在道岔上排队");
		assertEquals(MmtrPointAuthority.NO_PHYSICAL_HOLDER, simulator.mmtrPointAuthority.physicalPosition(0, 0, 0), "闸门挡着时位置不变");
		assertEquals(1, simulator.mmtrPointAuthority.physicalQueueSnapshot(0, 0, 0).size(), "队列里有它一条");

		// 闸门还挡着：tick 一轮也不许改位置。
		simulator.mmtrPointAuthority.retryPhysicalQueues(simulator.getCurrentMillis());
		assertEquals(MmtrPointAuthority.NO_PHYSICAL_HOLDER, simulator.mmtrPointAuthority.physicalPosition(0, 0, 0), "闸门挡着时重试也不许改位置");
		assertEquals(1, simulator.mmtrPointAuthority.physicalQueueSnapshot(0, 0, 0).size(), "还在排队");

		// 闸门清了 —— 关键在于**没有任何释放/过期事件**：修前这里永远轮不到它。
		blocked[0] = false;
		simulator.mmtrPointAuthority.retryPhysicalQueues(simulator.getCurrentMillis());
		assertEquals(MmtrTurnout.REVERSE, simulator.mmtrPointAuthority.physicalPosition(0, 0, 0), "净空清了以后队列要自己往前走");
		assertEquals("vB", simulator.mmtrPointAuthority.physicalHolder(0, 0, 0), "位置判给队首那台车");
		assertEquals(0, simulator.mmtrPointAuthority.physicalQueueSnapshot(0, 0, 0).size(), "发出去了就从队列里出来");
		assertTrue(simulator.mmtrPointAuthority.isGrantedTo(0, 0, 0, turnout.branchRailHex, "vB"), "位置判出去的同一刻要发出逐进向授权（走行只读逐进向）");
	}

	/** 位置已经被别人持有时，重试**不许**从持有者手里把位置抽走（净空闸同一条道理）。 */
	@Test
	public void theRetryNeverTakesThePositionAwayFromAHolder() {
		final Simulator simulator = forkNet("build/mmtr-point-queue-retry-holder");
		final MmtrTurnout turnout = simulator.mmtrTurnout(0, 0, 0);
		assertNotNull(turnout, "夹具必须是一处单开道岔");
		final int stemToFar = turnout.farLeg.getOrDefault(turnout.stemRailHex, -1);
		final int branchToStem = turnout.stemLeg.getOrDefault(turnout.branchRailHex, -1);
		assertTrue(stemToFar >= 0 && branchToStem >= 0, "夹具的几何腿号要能取到");

		assertEquals(MmtrPointAuthority.Result.GRANTED, simulator.mmtrPointAuthority.request(
			0, 0, 0, turnout.stemRailHex, "vA", stemToFar, until(simulator)), "正线侧先到");
		assertEquals(MmtrPointAuthority.Result.QUEUED, simulator.mmtrPointAuthority.request(
			0, 0, 0, turnout.branchRailHex, "vB", branchToStem, until(simulator)), "互斥需求排队");

		simulator.mmtrPointAuthority.retryPhysicalQueues(simulator.getCurrentMillis());
		assertEquals(MmtrTurnout.NORMAL, simulator.mmtrPointAuthority.physicalPosition(0, 0, 0), "持有者还在，位置不动");
		assertEquals("vA", simulator.mmtrPointAuthority.physicalHolder(0, 0, 0), "持有者没被抢走");
	}
}
