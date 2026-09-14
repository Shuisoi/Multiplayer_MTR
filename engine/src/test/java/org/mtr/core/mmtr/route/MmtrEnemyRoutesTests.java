package org.mtr.core.mmtr.route;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.mmtr.point.MmtrTurnout;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T5 第一块：**敌对进路表** —— "什么算冲突"。
 *
 * <p>它只回答"这两条进路敌对吗、因为什么"，**不改变任何状态**。所以这里的用例只需要轨图 + 进路登记，
 * 不需要车、不需要授权、不需要 tick —— 这正是把"先做成一张可单测的表"与"直接接进 SET 判定"分开的好处。</p>
 *
 * <p>最重要的一条断言是**跟随不算敌对**（notes/82）：共用一根轨但同向，是闭塞（S1）按间隔解决的事，
 * 把跟随也报成敌对会让这张表变成噪声（而噪声表等于没有表）。</p>
 */
public final class MmtrEnemyRoutesTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/** 单开道岔夹具（与 MmtrPointDefaultZeroTests 同形）：正线 stem ↔ far，岔股 = branch。 */
	private static Simulator forkNet(String path) {
		final Simulator simulator = new Simulator("test", new String[]{"test"}, Paths.get(path), false);
		final Position node = new Position(0, 0, 0);
		simulator.rails.add(through(new Position(-20, 0, 0), node));
		simulator.rails.add(through(node, new Position(20, 0, 0)));
		simulator.rails.add(through(node, new Position(20, 0, 14)));
		simulator.sync();
		return simulator;
	}

	private static MmtrRoute route(Simulator simulator, long vehicleId, ObjectArrayList<String> rails, ObjectArrayList<String[]> forks) {
		return simulator.mmtrRoutes.request(new MmtrRoute(vehicleId, "v" + vehicleId, MmtrRoute.Kind.MAIN, rails,
			forks, rails.isEmpty() ? "" : rails.get(rails.size() - 1), simulator.getCurrentMillis()));
	}

	/** 判据①：同一处道岔、两条进路要求**不同位置** ⇒ 报 TURNOUT。 */
	@Test
	public void twoRoutesWantingDifferentTurnoutPositionsAreReported() {
		final Simulator simulator = forkNet("build/mmtr-enemy-turnout");
		final MmtrTurnout turnout = simulator.mmtrTurnout(0, 0, 0);
		assertNotNull(turnout, "夹具是一处单开道岔");
		final int stemToFar = turnout.farLeg.getOrDefault(turnout.stemRailHex, -1);
		final int branchToStem = turnout.stemLeg.getOrDefault(turnout.branchRailHex, -1);
		assertTrue(stemToFar >= 0 && branchToStem >= 0, "几何腿号要能取到");

		final ObjectArrayList<String> railsA = new ObjectArrayList<>();
		railsA.add(turnout.stemRailHex);
		railsA.add(turnout.farRailHex);
		final ObjectArrayList<String[]> forksA = new ObjectArrayList<>();
		forksA.add(new String[]{"0", "0", "0", turnout.stemRailHex, String.valueOf(stemToFar)});
		route(simulator, 1L, railsA, forksA);

		final ObjectArrayList<String> railsB = new ObjectArrayList<>();
		railsB.add(turnout.branchRailHex);
		railsB.add(turnout.stemRailHex);
		final ObjectArrayList<String[]> forksB = new ObjectArrayList<>();
		forksB.add(new String[]{"0", "0", "0", turnout.branchRailHex, String.valueOf(branchToStem)});
		route(simulator, 2L, railsB, forksB);

		final ObjectArrayList<MmtrEnemyRoutes.Conflict> conflicts = MmtrEnemyRoutes.conflicts(simulator);
		assertEquals(1, conflicts.size(), "应当只有一条敌对：" + conflicts);
		final MmtrEnemyRoutes.Conflict conflict = conflicts.get(0);
		assertEquals("TURNOUT", conflict.kind);
		assertEquals(1L, conflict.vehicleA, "小 id 在前（输出确定性）");
		assertEquals(2L, conflict.vehicleB);
		assertTrue(conflict.detail.contains("0,0,0"), "理由点名道岔坐标：" + conflict.detail);
		assertTrue(conflict.detail.contains("要位置 0") && conflict.detail.contains("要位置 1"),
			"理由把两位都写出来：" + conflict.detail);
	}

	/** 判据①的反例：两条进路要**同一位** ⇒ 不相斥，不许报（道岔不是它们的争用点）。 */
	@Test
	public void twoRoutesWantingTheSameTurnoutPositionAreNotEnemies() {
		final Simulator simulator = forkNet("build/mmtr-enemy-same-position");
		final MmtrTurnout turnout = simulator.mmtrTurnout(0, 0, 0);
		final int stemToFar = turnout.farLeg.getOrDefault(turnout.stemRailHex, -1);
		final int farToStem = turnout.stemLeg.getOrDefault(turnout.farRailHex, -1);
		assertTrue(stemToFar >= 0 && farToStem >= 0, "几何腿号要能取到");

		final ObjectArrayList<String> railsA = new ObjectArrayList<>();
		railsA.add(turnout.stemRailHex);
		railsA.add(turnout.farRailHex);
		final ObjectArrayList<String[]> forksA = new ObjectArrayList<>();
		forksA.add(new String[]{"0", "0", "0", turnout.stemRailHex, String.valueOf(stemToFar)});
		route(simulator, 1L, railsA, forksA);

		final ObjectArrayList<String> railsB = new ObjectArrayList<>();
		railsB.add(turnout.farRailHex);
		railsB.add(turnout.stemRailHex);
		final ObjectArrayList<String[]> forksB = new ObjectArrayList<>();
		forksB.add(new String[]{"0", "0", "0", turnout.farRailHex, String.valueOf(farToStem)});
		route(simulator, 2L, railsB, forksB);

		assertEquals(0, MmtrEnemyRoutes.conflicts(simulator).size(),
			"两位相同 ⇒ 道岔不是争用点（它们之间的冲突是共用轨，由方向判据处理）");
	}

	/** 判据②：共用一根轨且**方向相反** ⇒ 报 OPPOSING。 */
	@Test
	public void opposingMovementsOverTheSameRailAreReported() {
		final Simulator simulator = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-enemy-opposing"), false);
		final Rail r1 = through(new Position(-40, 0, 0), new Position(-20, 0, 0));
		final Rail r2 = through(new Position(-20, 0, 0), new Position(0, 0, 0));
		final Rail r3 = through(new Position(0, 0, 0), new Position(20, 0, 0));
		simulator.rails.add(r1);
		simulator.rails.add(r2);
		simulator.rails.add(r3);
		simulator.sync();

		route(simulator, 1L, new ObjectArrayList<>(java.util.List.of(r1.getHexId(), r2.getHexId(), r3.getHexId())), new ObjectArrayList<>());
		route(simulator, 2L, new ObjectArrayList<>(java.util.List.of(r3.getHexId(), r2.getHexId(), r1.getHexId())), new ObjectArrayList<>());

		final ObjectArrayList<MmtrEnemyRoutes.Conflict> conflicts = MmtrEnemyRoutes.conflicts(simulator);
		assertEquals(1, conflicts.size(), "三段轨里只有中间那段是真正对向的：" + conflicts);
		assertEquals("OPPOSING", conflicts.get(0).kind);
		assertTrue(conflicts.get(0).detail.contains(r2.getHexId()), "理由点名哪根轨：" + conflicts.get(0).detail);
	}

	/**
	 * 判据②的反例（**本类最重要的一条**）：共用一根轨但**同向跟随** ⇒ 不算敌对。
	 *
	 * <p>notes/82 已定：跟随是闭塞（S1）按间隔解决的事。把跟随也报成敌对，这张表就会把每一次
	 * "两列车在同一条线上"都变成冲突 —— 噪声表等于没有表。</p>
	 */
	@Test
	public void followingMovementsOverTheSameRailAreNotEnemies() {
		final Simulator simulator = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-enemy-following"), false);
		final Rail r1 = through(new Position(-40, 0, 0), new Position(-20, 0, 0));
		final Rail r2 = through(new Position(-20, 0, 0), new Position(0, 0, 0));
		final Rail r3 = through(new Position(0, 0, 0), new Position(20, 0, 0));
		simulator.rails.add(r1);
		simulator.rails.add(r2);
		simulator.rails.add(r3);
		simulator.sync();

		// 前车走全程；后车从 r2 起步、同样朝 r3 去 —— 同向，且共用 r2/r3
		route(simulator, 1L, new ObjectArrayList<>(java.util.List.of(r1.getHexId(), r2.getHexId(), r3.getHexId())), new ObjectArrayList<>());
		route(simulator, 2L, new ObjectArrayList<>(java.util.List.of(r2.getHexId(), r3.getHexId())), new ObjectArrayList<>());

		assertEquals(0, MmtrEnemyRoutes.conflicts(simulator).size(),
			"同向跟随不算敌对（交给闭塞）：" + MmtrEnemyRoutes.conflicts(simulator));
	}

	/** 没有进路 / 只有一条进路：没有任何敌对（表必须是空的，而不是抛异常）。 */
	@Test
	public void anIdleNetworkHasNoConflicts() {
		final Simulator simulator = forkNet("build/mmtr-enemy-idle");
		assertEquals(0, MmtrEnemyRoutes.conflicts(simulator).size(), "没有进路 ⇒ 空表");
		final MmtrTurnout turnout = simulator.mmtrTurnout(0, 0, 0);
		final ObjectArrayList<String> rails = new ObjectArrayList<>();
		rails.add(turnout.stemRailHex);
		rails.add(turnout.farRailHex);
		route(simulator, 1L, rails, new ObjectArrayList<>());
		assertEquals(0, MmtrEnemyRoutes.conflicts(simulator).size(), "只有一条进路 ⇒ 无从敌对");
	}
}
