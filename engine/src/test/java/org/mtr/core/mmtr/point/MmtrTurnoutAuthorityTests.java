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

	/**
	 * **通行优先权：计划更早的那台车可以收回晚班车按着的位置**（notes/151，用户裁定）。
	 *
	 * <p>现场：六台车去同一个车站，计划到达 00:05 / 00:07 / …。晚班车先把道岔按在自己要的那一位上，
	 * 早班车就只能干等 —— 而"道岔位置归持有者"这条不许别人改 ⇒ 几台车在咽喉里轮流按位置、
	 * 轮流让位（现场实测的 ping-pong），谁也没走出去。</p>
	 *
	 * <p>口径：位置只给**计划更早**的（priority 更小）；晚班车在物理队列里等。
	 * 红证：去掉 {@code priorityMillis < holder.priorityMillis} 这个条件，第一段就红。</p>
	 */
	@Test
	public void theEarlierPlanTakesTheTurnoutFromTheLaterOne() {
		final Simulator simulator = forkNet("build/mmtr-t1-priority");
		final MmtrTurnout turnout = turnoutOf(simulator);
		final int branchToStem = turnout.stemLeg.getOrDefault(turnout.branchRailHex, -1);
		final int stemToFar = turnout.farLeg.getOrDefault(turnout.stemRailHex, -1);
		final long late = 7 * 60_000L;    // 计划 00:07
		final long early = 5 * 60_000L;   // 计划 00:05

		// 晚班车先到，把道岔按在位置 1（岔股开放）
		assertEquals(MmtrPointAuthority.Result.GRANTED, simulator.mmtrPointAuthority.request(
			0, 0, 0, turnout.branchRailHex, "vLate", branchToStem, until(simulator), late));
		assertEquals(MmtrTurnout.REVERSE, simulator.mmtrTurnoutPosition(0, 0, 0));
		assertEquals("vLate", simulator.mmtrPointAuthority.physicalHolder(0, 0, 0));

		// 早班车要位置 0：位置从晚班车手里收回来
		assertEquals(MmtrPointAuthority.Result.GRANTED, simulator.mmtrPointAuthority.request(
			0, 0, 0, turnout.stemRailHex, "vEarly", stemToFar, until(simulator), early), "早班车优先");
		assertEquals("vEarly", simulator.mmtrPointAuthority.physicalHolder(0, 0, 0), "位置交给早班车");
		assertEquals(MmtrTurnout.NORMAL, simulator.mmtrTurnoutPosition(0, 0, 0), "道岔扳到早班车要的位置");
		assertTrue(simulator.mmtrPointAuthority.isGrantedTo(0, 0, 0, turnout.stemRailHex, "vEarly"));

		// 反过来：晚班车再来要位置 1 ⇒ 只排队，不许把位置抢回去
		assertEquals(MmtrPointAuthority.Result.QUEUED, simulator.mmtrPointAuthority.request(
			0, 0, 0, turnout.branchRailHex, "vLate", branchToStem, until(simulator), late), "晚班车排队");
		assertEquals("vEarly", simulator.mmtrPointAuthority.physicalHolder(0, 0, 0), "位置仍在早班车手里");
		assertEquals(MmtrTurnout.NORMAL, simulator.mmtrTurnoutPosition(0, 0, 0), "位置没被翻回去");
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
	 * **持有者本人按着的位，只有它自己能改**（notes/136 §3 的死锁第一半）。
	 *
	 * <p>现场：车把某处道岔按在位置 1（旧计划要岔股），新计划要位置 0，而意图扳岔的闸门是
	 * "有人物理持有就不扳" —— **没有"持有者就是我"这条豁免**，于是谁也扳不动它，车永远停在
	 * 出发信号前。修法：豁免持有者本人，并且**改它自己的需求**（位置由持有者决定，只写行视图没用）。</p>
	 */
	@Test
	public void onlyTheHolderItselfMayRepointATurnoutItPins() {
		final Simulator simulator = forkNet("build/mmtr-t1-repoint-self");
		final MmtrTurnout turnout = turnoutOf(simulator);
		final int stemToFar = turnout.farLeg.getOrDefault(turnout.stemRailHex, -1);

		assertEquals(MmtrPointAuthority.Result.GRANTED, simulator.mmtrPointAuthority.request(
			0, 0, 0, turnout.stemRailHex, "v1", stemToFar, until(simulator)), "v1 拿到道岔并按下位置 0");
		assertEquals("v1", simulator.mmtrPointAuthority.physicalHolder(0, 0, 0));
		assertEquals(MmtrTurnout.NORMAL, simulator.mmtrTurnoutPosition(0, 0, 0));

		assertFalse(simulator.mmtrThrowTurnoutForIntent(0, 0, 0, MmtrTurnout.REVERSE, "v2"),
			"别人的道岔还是不许扳（T1 的原意：不许把道岔从别人列车脚下抽走）");
		assertEquals(MmtrTurnout.NORMAL, simulator.mmtrTurnoutPosition(0, 0, 0), "被拒时位置没动");

		assertTrue(simulator.mmtrThrowTurnoutForIntent(0, 0, 0, MmtrTurnout.REVERSE, "v1"),
			"持有者本人可以改自己按的位 —— 没有这条豁免就是死锁");
		assertEquals(MmtrTurnout.REVERSE, simulator.mmtrTurnoutPosition(0, 0, 0), "位置真的扳过去了（不是只写了行视图）");
		assertEquals(MmtrTurnout.REVERSE, simulator.mmtrPointAuthority.physicalPosition(0, 0, 0), "持有者按的位跟着改");
		assertEquals("v1", simulator.mmtrPointAuthority.physicalHolder(0, 0, 0), "持有关系不变，只是换了它要的位");
	}

	/**
	 * **重新规划会放掉旧计划不要的位**（notes/136 §3 的死锁第二半），且**要的是同一位时一个都不放**。
	 *
	 * <p>这是那条死锁真正的现场形状：进路一直判 PENDING，理由是"物理道岔被 v1 按在位置 1，
	 * 本车需要位置 0 —— 两条进路互斥"，而持有者正是它自己。放掉之后进路立刻能 SET。</p>
	 */
	@Test
	public void aReplanGivesUpThePositionTheOldPlanPinnedButKeepsTheOneItStillWants() {
		final Simulator simulator = forkNet("build/mmtr-t1-replan-release");
		final MmtrTurnout turnout = turnoutOf(simulator);
		final int branchToStem = turnout.stemLeg.getOrDefault(turnout.branchRailHex, -1);
		final int stemToFar = turnout.farLeg.getOrDefault(turnout.stemRailHex, -1);

		// 旧计划：把道岔按在位置 1（岔股开放）
		assertEquals(MmtrPointAuthority.Result.GRANTED, simulator.mmtrPointAuthority.request(
			0, 0, 0, turnout.branchRailHex, "v1", branchToStem, until(simulator)));
		assertEquals(MmtrTurnout.REVERSE, simulator.mmtrTurnoutPosition(0, 0, 0));

		// 新计划要的是**同一位** → 保留（等联锁时每 tick 重来一次，不能churn）
		assertEquals(0, simulator.mmtrReleaseStalePhysicalHolds("v1", java.util.Map.of("0,0,0", MmtrTurnout.REVERSE)),
			"新计划要的就是现在按着的这一位：不动");
		assertEquals("v1", simulator.mmtrPointAuthority.physicalHolder(0, 0, 0));

		// 新计划要位置 0 → 放掉，队列/默认位接手
		assertEquals(1, simulator.mmtrReleaseStalePhysicalHolds("v1", java.util.Map.of("0,0,0", MmtrTurnout.NORMAL)),
			"新计划要的是另一位：放掉旧计划的位");
		assertNull(simulator.mmtrPointAuthority.physicalHolder(0, 0, 0), "持有没了");
		assertEquals(MmtrTurnout.NORMAL, simulator.mmtrTurnoutPosition(0, 0, 0), "位置回到行视图（新计划要的 0）");
		assertEquals(0, simulator.mmtrReleaseStalePhysicalHolds("v1", java.util.Map.of("0,0,0", MmtrTurnout.NORMAL)),
			"再放一次是幂等的（没有可放的）");

		// 新计划要位置 0：这一次由它自己按上去，然后整条进路能 SET
		assertEquals(MmtrPointAuthority.Result.GRANTED, simulator.mmtrPointAuthority.request(
			0, 0, 0, turnout.stemRailHex, "v1", stemToFar, until(simulator)), "新计划按新需要重新拿");
		assertEquals(MmtrTurnout.NORMAL, simulator.mmtrPointAuthority.physicalPosition(0, 0, 0));
	}

	/**
	 * **自持有不再让进路卡死**：同一条进路先因为"我自己按着另一位"PENDING，放掉之后立刻 SET。
	 *
	 * <p>没有这两条修法时，这个用例的中间那一步是**永久**的：进路一直 PENDING（理由点名持有者
	 * 就是本车），而扳岔被"有人物理持有"挡住（持有者也是本车）—— 谁也解不开。</p>
	 */
	@Test
	public void aRouteBlockedOnlyByTheVehiclesOwnStaleHoldBecomesSetOnceItIsDropped() {
		final Simulator simulator = forkNet("build/mmtr-t1-self-hold-route");
		final MmtrTurnout turnout = turnoutOf(simulator);
		final int stemToFar = turnout.farLeg.getOrDefault(turnout.stemRailHex, -1);
		final int branchToStem = turnout.stemLeg.getOrDefault(turnout.branchRailHex, -1);
		final long until = until(simulator);

		// 旧计划留下的持有：v1 按着位置 1
		assertEquals(MmtrPointAuthority.Result.GRANTED, simulator.mmtrPointAuthority.request(
			0, 0, 0, turnout.branchRailHex, "v1", branchToStem, until));

		// 新进路：从根部开往正线远端（位置 0）
		final ObjectArrayList<String> rails = new ObjectArrayList<>();
		rails.add(turnout.stemRailHex);
		rails.add(turnout.farRailHex);
		final ObjectArrayList<String[]> forks = new ObjectArrayList<>();
		forks.add(new String[]{"0", "0", "0", turnout.stemRailHex, String.valueOf(stemToFar)});
		final MmtrRoute route =
			simulator.mmtrRoutes.request(new MmtrRoute(1, "v1", MmtrRoute.Kind.MAIN, rails, forks, turnout.farRailHex, simulator.getCurrentMillis()));
		simulator.mmtrRoutes.refresh(1, simulator.mmtrPointAuthority);

		assertFalse(route.isEstablished(), "物理位置（1，我自己按的）与这条腿要的（0）不一致 → PENDING");
		assertTrue(route.getStateReason().contains("v1"), "理由点名持有者就是本车：" + route.getStateReason());

		/*
		 * 死亡的那个状态就是这里：进路 PENDING 点名"被自己按住了另一位"，而闸门又拦着"不许扳有人持有的
		 * 道岔" —— 修前**谁也解不开**。修好的 {@code armMmtrPointRun} 按这个顺序自救：先放旧计划的位，
		 * 再按新计划原子申请（那一步会把新的一位按上）。
		 */
		assertEquals(1, simulator.mmtrReleaseStalePhysicalHolds("v1", java.util.Map.of("0,0,0", MmtrTurnout.NORMAL)),
			"重新规划先放掉旧计划不要的位");
		final ObjectArrayList<String[]> pending = new ObjectArrayList<>();
		pending.add(new String[]{"0", "0", "0", turnout.stemRailHex, String.valueOf(stemToFar)});
		assertEquals(MmtrPointAuthority.Result.GRANTED,
			simulator.mmtrPointAuthority.requestAtomically(pending, "v1", until, Long.MAX_VALUE), "再按新计划原子申请");
		simulator.mmtrRoutes.refresh(1, simulator.mmtrPointAuthority);

		assertTrue(route.isEstablished(), "自救之后进路立刻 SET（死锁解除）");
		assertEquals(MmtrTurnout.NORMAL, simulator.mmtrTurnoutPosition(0, 0, 0), "道岔真的在它要的 0 位上");
	}

	/**
	 * **一组申请里同一处道岔出现两次（折返的两程）时，只认最先要过的那一程**（notes/137）。
	 *
	 * <p>两程要的是互斥的两个位置，而一处道岔只有一个位置：整组照办的话，后一程的位会覆盖前一程的，
	 * 于是"手里按着 1、进路需要 0"，车永远停在自己的出发信号前（现场实测，见 notes/137 §1b）。
	 * 申请集按行进次序给（最近的在前），所以第一个说了算。</p>
	 */
	@Test
	public void aContradictoryAtomicSetKeepsTheNearestPassPosition() {
		final Simulator simulator = forkNet("build/mmtr-t1-atomic-two-passes");
		final MmtrTurnout turnout = turnoutOf(simulator);
		final int stemToFar = turnout.farLeg.getOrDefault(turnout.stemRailHex, -1);
		final int branchToStem = turnout.stemLeg.getOrDefault(turnout.branchRailHex, -1);

		final ObjectArrayList<String[]> ops = new ObjectArrayList<>();
		ops.add(new String[]{"0", "0", "0", turnout.stemRailHex, String.valueOf(stemToFar)});      // 第一程 → 位置 0
		ops.add(new String[]{"0", "0", "0", turnout.branchRailHex, String.valueOf(branchToStem)}); // 第二程 → 位置 1

		assertEquals(MmtrPointAuthority.Result.GRANTED,
			simulator.mmtrPointAuthority.requestAtomically(ops, "v1", until(simulator), Long.MAX_VALUE), "整组给我");
		assertEquals(MmtrTurnout.NORMAL, simulator.mmtrPointAuthority.physicalPosition(0, 0, 0),
			"同一处道岔只认最先要过的那一程（修前这里会被后一程按成 1）");
		assertEquals("v1", simulator.mmtrPointAuthority.physicalHolder(0, 0, 0));
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
