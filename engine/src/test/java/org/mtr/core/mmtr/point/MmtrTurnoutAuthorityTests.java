package org.mtr.core.mmtr.point;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.mmtr.route.MmtrRoute;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1: **一处道岔一个位置** —— 权限层的物理闸门（design
 * {@code 行车控制-任务联锁与行车许可-设计.md} §A-1/§A-2）。
 *
 * <p>修前的模型是"每个进向各一份 0/1"（方向视图）：两列车从**不同进向**申请同一处道岔时，键不同
 * （{@code node|via}），于是**两份物理上互斥的许可可以同时成立**，两条进路都报 SET；而真正决定道岔
 * 位置的是 {@code {stem, far, branch}} 的**数组顺序**。走行侧的禁行闸门兜住了安全（车停在岔前），
 * 但状态在说谎，且咽喉里两列车会一起僵住。</p>
 *
 * <p>本类只测新加的那一层：物理持有者、互斥需求在**道岔上**排队、位置由持有者决定、
 * 不可能的组合被拒绝。逐进向的老语义由 {@link MmtrPointAuthorityTests} 守着（那里不接物理层，
 * 所以行为逐位不变）。</p>
 */
public final class MmtrTurnoutAuthorityTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final long WINDOW_MILLIS = 10L * 60 * 1000;

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/** 单开道岔夹具（与 {@code MmtrPointDefaultZeroTests} 同形）：正线 stem ↔ far，岔股 = branch。 */
	private static Simulator forkNet(String path) {
		final Simulator simulator = new Simulator("test", new String[]{"test"}, Paths.get(path), false);
		final Position node = new Position(0, 0, 0);
		simulator.rails.add(through(new Position(-20, 0, 0), node));
		simulator.rails.add(through(node, new Position(20, 0, 0)));
		simulator.rails.add(through(node, new Position(20, 0, 14)));
		simulator.sync();
		return simulator;
	}

	private static MmtrTurnout turnoutOf(Simulator simulator) {
		final MmtrTurnout turnout = simulator.mmtrTurnout(0, 0, 0);
		assertTrue(turnout != null, "夹具必须是一处单开道岔");
		return turnout;
	}

	private static long until(Simulator simulator) {
		return simulator.getCurrentMillis() + WINDOW_MILLIS;
	}

	/**
	 * ③ **位置由持有者决定，与 {@code {stem, far, branch}} 的数组顺序无关。**
	 *
	 * <p>两种次序都测：先由"根部→正线远端"（位置 0）持有，再由"岔股→根部"（位置 1）持有。
	 * 修前的实现按数组顺序取第一个有授权的进向，{@code stem} 永远排在第一个 —— 于是**无论谁先来，
	 * stem 那一侧都赢**。现在两次的结果都跟着持有者走。</p>
	 */
	@Test
	public void thePositionFollowsTheHolderNotTheViaArrayOrder() {
		final Simulator simulator = forkNet("build/mmtr-t1-order");
		final MmtrTurnout turnout = turnoutOf(simulator);
		final int stemToFar = turnout.farLeg.getOrDefault(turnout.stemRailHex, -1);
		final int branchToStem = turnout.stemLeg.getOrDefault(turnout.branchRailHex, -1);
		assertTrue(stemToFar >= 0 && branchToStem >= 0, "夹具的几何腿号要能取到");

		// 第一程：正线那一侧先来（位置 0）
		assertEquals(MmtrPointAuthority.Result.GRANTED, simulator.mmtrPointAuthority.request(
				0, 0, 0, turnout.stemRailHex, "vA", stemToFar, until(simulator)), "正线侧先到，拿到道岔");
		assertEquals(MmtrTurnout.NORMAL, simulator.mmtrPointAuthority.physicalPosition(0, 0, 0), "位置 0：持有者定");
		assertEquals("vA", simulator.mmtrPointAuthority.physicalHolder(0, 0, 0));
		assertEquals(MmtrTurnout.NORMAL, simulator.mmtrTurnoutPosition(0, 0, 0), "道岔真的扳到了 0");

		// 岔股那一侧随后到，要的是互斥的位置 1 → 排队，且**不能**把位置翻过去
		assertEquals(MmtrPointAuthority.Result.QUEUED, simulator.mmtrPointAuthority.request(
				0, 0, 0, turnout.branchRailHex, "vB", branchToStem, until(simulator)), "互斥需求在道岔上排队");
		assertEquals(MmtrTurnout.NORMAL, simulator.mmtrPointAuthority.physicalPosition(0, 0, 0), "排队者不动位置");
		assertEquals(MmtrTurnout.NORMAL, simulator.mmtrTurnoutPosition(0, 0, 0), "排队者不动位置（引擎读到的也一样）");
		assertEquals("vA", simulator.mmtrPointAuthority.physicalHolder(0, 0, 0));

		// 前车越岔 → 让位；队列头的需求成为新位置（1）
		simulator.mmtrPointAuthority.passed(0, 0, 0, turnout.stemRailHex, "vA");
		assertEquals("vB", simulator.mmtrPointAuthority.physicalHolder(0, 0, 0), "越岔即让位，队列头接手");
		assertEquals(MmtrTurnout.REVERSE, simulator.mmtrPointAuthority.physicalPosition(0, 0, 0), "新持有者的位置生效");
		assertEquals(MmtrTurnout.REVERSE, simulator.mmtrTurnoutPosition(0, 0, 0), "道岔跟着扳到 1");
		assertTrue(simulator.mmtrPointAuthority.isGrantedTo(0, 0, 0, turnout.branchRailHex, "vB"),
			"递补者同时拿到**逐进向**的授权（走行读的是那一份）");
	}

	/**
	 * ③ 的镜像：**岔股先到**时位置必须是 1，哪怕 {@code stem} 在数组里排第一。
	 * 这一条单独存在，是因为它正是修前"数组顺序仲裁"会给出错误答案的那一格。
	 */
	@Test
	public void theBranchHolderWinsEvenThoughStemComesFirstInTheArray() {
		final Simulator simulator = forkNet("build/mmtr-t1-branch-first");
		final MmtrTurnout turnout = turnoutOf(simulator);
		final int stemToFar = turnout.farLeg.getOrDefault(turnout.stemRailHex, -1);
		final int branchToStem = turnout.stemLeg.getOrDefault(turnout.branchRailHex, -1);

		assertEquals(MmtrPointAuthority.Result.GRANTED, simulator.mmtrPointAuthority.request(
				0, 0, 0, turnout.branchRailHex, "vBranch", branchToStem, until(simulator)), "岔股侧先到");
		assertEquals(MmtrTurnout.REVERSE, simulator.mmtrTurnoutPosition(0, 0, 0), "位置 1（岔股开放）");

		assertEquals(MmtrPointAuthority.Result.QUEUED, simulator.mmtrPointAuthority.request(
				0, 0, 0, turnout.stemRailHex, "vStem", stemToFar, until(simulator)), "正线侧排队，而不是把位置抢回去");
		assertEquals(MmtrTurnout.REVERSE, simulator.mmtrTurnoutPosition(0, 0, 0),
			"位置仍然按持有者（岔股），不是按 {stem,far,branch} 的数组顺序");
	}

	/** 位置相容 = 道岔不是这两列车之间的争用点，两份授权都可以发（冲突在别处，归闭塞/敌对进路表）。 */
	@Test
	public void compatibleDemandsShareTheSamePosition() {
		final Simulator simulator = forkNet("build/mmtr-t1-compatible");
		final MmtrTurnout turnout = turnoutOf(simulator);
		final int stemToFar = turnout.farLeg.getOrDefault(turnout.stemRailHex, -1);
		final int farToStem = turnout.stemLeg.getOrDefault(turnout.farRailHex, -1);

		assertEquals(MmtrPointAuthority.Result.GRANTED, simulator.mmtrPointAuthority.request(
				0, 0, 0, turnout.stemRailHex, "v1", stemToFar, until(simulator)), "正线侧去远端 = 位置 0");
		assertEquals(MmtrTurnout.NORMAL, simulator.mmtrPointAuthority.turnoutDemand(0, 0, 0, turnout.farRailHex, farToStem),
			"从远端回根部也是位置 0 —— 与上一条**同一位**");
		assertEquals(MmtrPointAuthority.Result.GRANTED, simulator.mmtrPointAuthority.request(
				0, 0, 0, turnout.farRailHex, "v2", farToStem, until(simulator)), "同一位相容，两份授权都要给");
		assertEquals(MmtrTurnout.NORMAL, simulator.mmtrTurnoutPosition(0, 0, 0), "位置没被推动");
	}

	/** 两根轨在任何位置都不相连的组合：拒绝，而且**不留任何持有**（排队等于承认它将来可能成立）。 */
	@Test
	public void impossibleCombinationsAreRejectedWithoutTakingThePoint() {
		final Simulator simulator = forkNet("build/mmtr-t1-impossible");
		final MmtrTurnout turnout = turnoutOf(simulator);
		final int branchToFar = turnout.farLeg.getOrDefault(turnout.branchRailHex, -1);
		assertTrue(branchToFar >= 0, "几何腿号里有这条（这正是会误读的原因）");

		assertEquals(MmtrPointAuthority.Result.REJECTED, simulator.mmtrPointAuthority.request(
				0, 0, 0, turnout.branchRailHex, "vBad", branchToFar, until(simulator)), "背向穿过尖轨 = 不存在");
		assertNull(simulator.mmtrPointAuthority.physicalHolder(0, 0, 0), "没有产生持有者");
		assertFalse(simulator.mmtrPointAuthority.isGrantedTo(0, 0, 0, turnout.branchRailHex, "vBad"), "没有发出授权");
		assertTrue(simulator.mmtrPointAuthority.physicalQueueSnapshot(0, 0, 0).isEmpty(), "也没有进队列");
	}

	/** 终态释放：让出位置并推进队列（车被删掉/任务终止时不能把道岔永久按在自己的位置上）。 */
	@Test
	public void releaseAllGivesUpThePositionAndAdvancesTheQueue() {
		final Simulator simulator = forkNet("build/mmtr-t1-release");
		final MmtrTurnout turnout = turnoutOf(simulator);
		final int stemToFar = turnout.farLeg.getOrDefault(turnout.stemRailHex, -1);
		final int branchToStem = turnout.stemLeg.getOrDefault(turnout.branchRailHex, -1);

		simulator.mmtrPointAuthority.request(0, 0, 0, turnout.stemRailHex, "v1", stemToFar, until(simulator));
		simulator.mmtrPointAuthority.request(0, 0, 0, turnout.branchRailHex, "v2", branchToStem, until(simulator));
		assertEquals("v1", simulator.mmtrPointAuthority.physicalHolder(0, 0, 0));

		simulator.mmtrPointAuthority.releaseAll("v1");
		assertEquals("v2", simulator.mmtrPointAuthority.physicalHolder(0, 0, 0), "释放后队列头接手");
		assertEquals(MmtrTurnout.REVERSE, simulator.mmtrTurnoutPosition(0, 0, 0), "位置跟着新持有者");
	}

	/**
	 * ① + ② **两条互斥进路不可能同时 SET**，且等待方点名道岔与它需要的位置。
	 *
	 * <p>进路层与权限层是分开的：授权只说明"这个进向归我"，SET 还要求**道岔物理上就在我这条腿要的
	 * 那一位**。修前只判授权，于是两条互斥进路都报 SET。</p>
	 */
	@Test
	public void mutuallyExclusiveRoutesCannotBothBeSet() {
		final Simulator simulator = forkNet("build/mmtr-t1-routes");
		final MmtrTurnout turnout = turnoutOf(simulator);
		final int stemToFar = turnout.farLeg.getOrDefault(turnout.stemRailHex, -1);
		final int branchToStem = turnout.stemLeg.getOrDefault(turnout.branchRailHex, -1);
		final long until = until(simulator);

		// 进路 A：从根部一侧开往正线远端（位置 0）
		final ObjectArrayList<String> railsA = new ObjectArrayList<>();
		railsA.add(turnout.stemRailHex);
		railsA.add(turnout.farRailHex);
		final ObjectArrayList<String[]> forksA = new ObjectArrayList<>();
		forksA.add(new String[]{"0", "0", "0", turnout.stemRailHex, String.valueOf(stemToFar)});
		final MmtrRoute routeA =
			simulator.mmtrRoutes.request(new MmtrRoute(1, "v1", MmtrRoute.Kind.MAIN, railsA, forksA, turnout.farRailHex, simulator.getCurrentMillis()));

		// 进路 B：从岔股一侧开回根部（位置 1）—— 与 A 物理互斥
		final ObjectArrayList<String> railsB = new ObjectArrayList<>();
		railsB.add(turnout.branchRailHex);
		railsB.add(turnout.stemRailHex);
		final ObjectArrayList<String[]> forksB = new ObjectArrayList<>();
		forksB.add(new String[]{"0", "0", "0", turnout.branchRailHex, String.valueOf(branchToStem)});
		final MmtrRoute routeB =
			simulator.mmtrRoutes.request(new MmtrRoute(2, "v2", MmtrRoute.Kind.MAIN, railsB, forksB, turnout.stemRailHex, simulator.getCurrentMillis()));

		simulator.mmtrPointAuthority.request(0, 0, 0, turnout.stemRailHex, "v1", stemToFar, until);
		simulator.mmtrPointAuthority.request(0, 0, 0, turnout.branchRailHex, "v2", branchToStem, until);
		simulator.mmtrRoutes.refresh(1, simulator.mmtrPointAuthority);
		simulator.mmtrRoutes.refresh(2, simulator.mmtrPointAuthority);

		assertTrue(routeA.isEstablished(), "先到的进路 SET");
		assertFalse(routeB.isEstablished(), "互斥的进路**不可能**同时 SET");
		assertFalse(routeA.isEstablished() && routeB.isEstablished(), "两条互斥进路不能都报 SET");

		final String reason = routeB.getStateReason();
		assertTrue(reason.contains("0,0,0"), "等待理由点名道岔坐标：" + reason);
		assertTrue(reason.contains("v1"), "等待理由点名持有者：" + reason);
		assertTrue(reason.contains("本车需要位置 1"), "等待理由点名它需要的位置：" + reason);
		assertTrue(reason.contains("岔股开放"), "并用中文说清那一位是什么：" + reason);
	}
}
