package org.mtr.core.mmtr.point;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.mmtr.command.MmtrCommandDispatcher;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **浅岔口也必须是一处道岔**（用户 2026-09-14 现场提出："-170,-60,-199 怎么和 -67,-60,-139 显示得不一样？"）。
 *
 * <h3>实测世界的形状</h3>
 * <p>那一片是两条平行正线（沿 z 走，在 x=-170 与 x=-176，相距 6 格），中间由两条很浅的斜线相连：
 * 一条是两线之间的**渡线**（{@code -170,-60,-289} ↔ {@code -176,-60,-253}，两端各是一处道岔），
 * 一条是 x=-176 那条线**并进** x=-170 那条线的汇入（{@code -170,-60,-199} ↔ {@code -176,-60,-222}）。
 * 6 格横移要在 23~36 格内走完 ⇒ 岔股夹角只有 <b>9.5° ~ 14.6°</b>，而 &gt;25.8° 才算"明显偏开"。</p>
 *
 * <h3>为什么老实现会认不出来</h3>
 * <p>老实现取的是"遍历腿表时最后遇到的一对互为直股的轨"，而 {@code COS_STRAIGHT = 0.9}（≈25.8°）
 * 让**浅岔股自己也够"直"**（cos 9.5° = 0.986）：三根轨里有三对够格 —— 真共线那一对互直度 1.00、
 * 含岔股的两对 0.986 —— 谁最后被遍历到谁赢。于是同一个几何的镜像节点可能一个被认成道岔（甚至
 * 把岔股当正线 = 模型倒置）、一个返回 null（网页退化成 legacy 卡片，T1 的物理互斥完全不生效）。
 * 实测 {@code -147,-60,-169} 与 {@code -155,-60,-189} 的方位角多重集完全相同却一认一不认，就是这么来的。</p>
 *
 * <p>本类只钉住"判据 = 几何本身"：取互直度最大的那一对当直股对、根部按"谁迎着看得到岔股"定，
 * 并且结果与邻表的遍历顺序无关。</p>
 */
public final class MmtrTurnoutResolveTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final Position NODE = new Position(0, 0, 0);
	private static final long Y = 0;

	private static Rail rail(Position from, Position to) {
		return Rail.newRail(from, Angle.fromAngle((float) bearing(from, to)), to, Angle.fromAngle((float) bearing(to, from)),
			Rail.Shape.QUADRATIC, 0, NO_STYLES, 80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static double bearing(Position from, Position to) {
		final double degrees = Math.toDegrees(Math.atan2(to.getZ() - from.getZ(), to.getX() - from.getX()));
		return (degrees % 360 + 360) % 360;
	}

	/** 节点 + 各股远端 → 邻表（键 = 该股远端坐标，与 {@code positionsToRail} 同形）。 */
	private static Object2ObjectOpenHashMap<Position, Rail> neighbours(Position... farEnds) {
		return neighbours(3, farEnds);
	}

	private static Object2ObjectOpenHashMap<Position, Rail> neighbours(int capacity, Position... farEnds) {
		final Object2ObjectOpenHashMap<Position, Rail> map = new Object2ObjectOpenHashMap<>(capacity);
		for (final Position farEnd : farEnds) {
			map.put(farEnd, rail(NODE, farEnd));
		}
		return map;
	}

	/** 某根轨在这个节点上的远端（用于把判定结果翻回人读的坐标）。 */
	private static String farEndOf(Object2ObjectOpenHashMap<Position, Rail> neighbours, String railHex) {
		for (final java.util.Map.Entry<Position, Rail> entry : neighbours.entrySet()) {
			if (entry.getValue().getHexId().equals(railHex)) {
				return entry.getKey().getX() + "," + entry.getKey().getZ();
			}
		}
		return "（不在邻表里）";
	}

	private static String describe(MmtrTurnout turnout) {
		return "根部→" + turnout.stemRailHex + " 正线远端→" + turnout.farRailHex + " 岔股→" + turnout.branchRailHex;
	}

	// ------------------------------------------------------------------ 实测几何（真值）

	/** 实测 {@code -170,-60,-289}（渡线的南端）：正线南 17 格 / 北 36 格，岔股在 36.5 格处横移 6 格 = 偏 9.5°。 */
	private static final Position SHALLOW_NORTH = new Position(0, Y, 36);
	private static final Position SHALLOW_BRANCH = new Position(-6, Y, 36);
	private static final Position SHALLOW_SOUTH = new Position(0, Y, -17);

	/** 实测 {@code -176,-60,-253}（渡线的北端，镜像）：正线北 31 格 / 南 36 格，岔股偏 9.5°。 */
	private static final Position MIRROR_NORTH = new Position(0, Y, 31);
	private static final Position MIRROR_SOUTH = new Position(0, Y, -36);
	private static final Position MIRROR_BRANCH = new Position(6, Y, -36);

	/** 实测 {@code -147,-60,-169} 那一类：岔股偏 21.8°（cos 0.93 ≥ 0.9 —— 正是老实现"两对都够格"的区间）。 */
	private static final Position STEEP_NORTH = new Position(0, Y, 36);
	private static final Position STEEP_SOUTH = new Position(0, Y, -36);
	private static final Position STEEP_BRANCH = new Position(-13, Y, -33);

	// ------------------------------------------------------------------ 用例

	/**
	 * ① 偏 9.5° 的渡线是一处真道岔：正线 = 共线的那两根，岔股 = 偏 9.5° 的那根，
	 * 根部 = **迎着开能看到岔股分出去**的那一侧（南侧）。
	 */
	@Test
	public void aNineDegreeCrossoverIsModelled() {
		final Object2ObjectOpenHashMap<Position, Rail> map = neighbours(SHALLOW_NORTH, SHALLOW_BRANCH, SHALLOW_SOUTH);
		final MmtrTurnout turnout = MmtrTurnout.resolve(NODE, map);
		assertNotNull(turnout, "偏 9.5° 也是道岔 —— 横移 6 格就是 6 格，画得长不等于不是道岔");
		assertEquals("0,36", farEndOf(map, turnout.farRailHex), "正线远端 = 北股（与南股共线的那一端）：" + describe(turnout));
		assertEquals("0,-17", farEndOf(map, turnout.stemRailHex), "根部 = 南股（从它向北开，岔股在前方分出去）：" + describe(turnout));
		assertEquals("-6,36", farEndOf(map, turnout.branchRailHex), "岔股 = 偏 9.5° 的那一根：" + describe(turnout));
	}

	/**
	 * ② 几何完全镜像的那一端（{@code -176,-60,-253}）给出**对应的镜像结果**，而不是相反答案。
	 * 老实现正是在这一对上分裂的：一个认出来（还是倒置的），一个直接返回 null。
	 */
	@Test
	public void theMirrorCrossoverGivesTheMirroredAnswer() {
		final Object2ObjectOpenHashMap<Position, Rail> map = neighbours(MIRROR_NORTH, MIRROR_BRANCH, MIRROR_SOUTH);
		final MmtrTurnout turnout = MmtrTurnout.resolve(NODE, map);
		assertNotNull(turnout, "镜像节点（-176,-60,-253）同样必须是道岔");
		assertEquals("0,31", farEndOf(map, turnout.stemRailHex), "根部 = 北股（镜像后换成北侧）：" + describe(turnout));
		assertEquals("0,-36", farEndOf(map, turnout.farRailHex), "正线远端 = 南股：" + describe(turnout));
		assertEquals("6,-36", farEndOf(map, turnout.branchRailHex), "岔股 = 偏 9.5° 的那一根：" + describe(turnout));
	}

	/**
	 * ③ 偏 21.8° 的岔股：cos 0.93 也算"直股续行"，所以这一格上老实现有**两对**够格
	 * （真共线 1.00 与"正线远端↔岔股" 0.93）。必须取更直的那一对当正线。
	 */
	@Test
	public void aTwentyTwoDegreeBranchStillLeavesTheStraightestPairAsTheMainLine() {
		final Object2ObjectOpenHashMap<Position, Rail> map = neighbours(STEEP_NORTH, STEEP_SOUTH, STEEP_BRANCH);
		final MmtrTurnout turnout = MmtrTurnout.resolve(NODE, map);
		assertNotNull(turnout, "偏 21.8° 的岔股同样是单开道岔");
		assertEquals("0,-36", farEndOf(map, turnout.farRailHex), "正线远端 = 南股（共线的那一端，不是岔股）：" + describe(turnout));
		assertEquals("0,36", farEndOf(map, turnout.stemRailHex), "根部 = 北股：" + describe(turnout));
		assertEquals("-13,-33", farEndOf(map, turnout.branchRailHex), "岔股 = 偏 21.8° 的那一根：" + describe(turnout));
	}

	/**
	 * ④ **判定与邻表遍历顺序无关。** 老实现外层是 {@code Object2ObjectOpenHashMap}（键 = 轨 hex），
	 * 遍历顺序由哈希槽位决定，而"最后遇到的一对获胜"—— 于是同一处几何换个表的容量/插入次序就可能换答案。
	 *
	 * <p>这里对每个容量 × 每种插入次序都断言**显式期望几何**（而不是互相之间比对）：只要有任何一种
	 * 顺序把岔股当成了正线，就会当场失败。</p>
	 */
	@Test
	public void theDecisionDoesNotDependOnNeighbourIterationOrder() {
		final List<Position[]> orders = new ArrayList<>();
		orders.add(new Position[]{SHALLOW_NORTH, SHALLOW_BRANCH, SHALLOW_SOUTH});
		orders.add(new Position[]{SHALLOW_BRANCH, SHALLOW_SOUTH, SHALLOW_NORTH});
		orders.add(new Position[]{SHALLOW_SOUTH, SHALLOW_NORTH, SHALLOW_BRANCH});

		for (int capacity = 1; capacity <= 24; capacity++) {
			for (int i = 0; i < orders.size(); i++) {
				final Object2ObjectOpenHashMap<Position, Rail> map = neighbours(capacity, orders.get(i));
				final MmtrTurnout turnout = MmtrTurnout.resolve(NODE, map);
				final String where = "容量 " + capacity + " / 次序 " + i;
				assertNotNull(turnout, where + " 下也必须认出道岔");
				assertEquals("0,-17", farEndOf(map, turnout.stemRailHex), "根部与遍历顺序无关（" + where + "）：" + describe(turnout));
				assertEquals("0,36", farEndOf(map, turnout.farRailHex), "正线远端与遍历顺序无关（" + where + "）：" + describe(turnout));
				assertEquals("-6,36", farEndOf(map, turnout.branchRailHex), "岔股与遍历顺序无关（" + where + "）：" + describe(turnout));
			}
		}
	}

	/** ⑤ 直角三岔口与 120° 三角线**仍旧不建模**（现实里是两组道岔背靠背，没有"根部/被切断的一侧"可言）。 */
	@Test
	public void rightAngleTeeAndWyeAreStillNotTurnouts() {
		final Object2ObjectOpenHashMap<Position, Rail> tee = neighbours(new Position(20, Y, 0), new Position(-20, Y, 0), new Position(0, Y, 20));
		assertNull(MmtrTurnout.resolve(NODE, tee), "正线共线 + 一根垂直臂 = 直角三岔口，没有根部可言");

		final Object2ObjectOpenHashMap<Position, Rail> wye =
			neighbours(new Position(20, Y, 0), new Position(-10, Y, 17), new Position(-10, Y, -17));
		assertNull(MmtrTurnout.resolve(NODE, wye), "120° 三角线：没有任何一对互为直股");
	}

	/**
	 * ⑥ {@code point why <x> <y> <z>} 把判定过程写成人读的（用户点了名要这条指令）。
	 *
	 * <p>它与 {@code resolve} 走同一段代码，所以这里同时钉住了两件事：真道岔给出"认成 1 处单开道岔 +
	 * 互直度比较"，认不出的节点给出**为什么**（而不是只丢一张 legacy 卡片）。</p>
	 */
	@Test
	public void pointWhyExplainsBothOutcomes() {
		final Simulator shallow = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-turnout-why-shallow"), false);
		shallow.rails.add(rail(NODE, SHALLOW_NORTH));
		shallow.rails.add(rail(NODE, SHALLOW_BRANCH));
		shallow.rails.add(rail(NODE, SHALLOW_SOUTH));
		shallow.sync();
		final MmtrCommandDispatcher.Result modelled = MmtrCommandDispatcher.execute(shallow, "point why 0 0 0");
		final String modelledText = String.join("\n", modelled.lines);
		assertTrue(modelled.ok, "指令要成功执行：" + modelledText);
		assertTrue(modelledText.contains("认成 1 处单开道岔"), "要给出结论：" + modelledText);
		assertTrue(modelledText.contains("候选直股对"), "要列出每一对的互直度：" + modelledText);
		assertTrue(modelledText.contains("岔股 = "), "要点名岔股是哪一根：" + modelledText);
		assertTrue(modelledText.contains("闭塞归属"), "顺带给出闭塞归属（describeNodeResolution）：" + modelledText);

		final Simulator tee = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-turnout-why-tee"), false);
		tee.rails.add(rail(NODE, new Position(20, Y, 0)));
		tee.rails.add(rail(NODE, new Position(-20, Y, 0)));
		tee.rails.add(rail(NODE, new Position(0, Y, 20)));
		tee.sync();
		final MmtrCommandDispatcher.Result unmodelled = MmtrCommandDispatcher.execute(tee, "point why 0 0 0");
		final String unmodelledText = String.join("\n", unmodelled.lines);
		assertTrue(unmodelled.ok, "认不出来也要成功返回解释：" + unmodelledText);
		assertTrue(unmodelledText.contains("**不是**一处可建模的单开道岔"), "要给出否定结论：" + unmodelledText);
		assertTrue(unmodelledText.contains("直角三岔口"), "要说清是哪种几何：" + unmodelledText);
	}
}
