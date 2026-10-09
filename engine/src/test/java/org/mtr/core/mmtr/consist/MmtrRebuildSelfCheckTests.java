package org.mtr.core.mmtr.consist;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.mmtr.consist.MmtrCabState.Cab;
import org.mtr.core.mmtr.consist.MmtrConsistBody.OccupiedSegment;
import org.mtr.core.mmtr.consist.MmtrConsistBody.SpineLeg;
import org.mtr.core.mmtr.point.MmtrPointAuthority;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.point.MmtrTurnout;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **可重建性自检**：把"车在哪、有多长"压成"一端位置 + 单元几何"，还能不能逐位还原整列车？
 *
 * <h2>它回答什么</h2>
 * <p>网络协议要往"单元 + 位置 + 方向"收敛（而不是现在 ① 那种"腿表 + 累计里程"）。前提是：
 * 那条更小的信息量**足以**重建几何。这个用例不复述设计，只给读数：</p>
 *
 * <ol>
 *   <li>{@link #railListReconstructionIsBitIdentical()} —— 给出**脊线轨序**（每根轨的 hex + 入端）时，
 *       只凭"一端位置 + 逐车长 + 接缝"重建出来的车体，与真身**逐位相同**。
 *       ⇒ 这条成立，"单元 + 位置"就是无损的重参数化。</li>
 *   <li>{@link #placeCanNotRebuildWithoutTheForkDecision()} —— 若只给"一端位置 + 几何"、
 *       让 {@code MmtrConsistWalker.place} 自己选岔，而岔口那侧**没有任何提示**：
 *       它**直接失败**（返回 {@code null}）。⇒ 客户端拿不到"当时走的是哪条腿"就建不出脊线。</li>
 *   <li>{@link #theForkDecisionIsWhatPlaceNeeds()} —— 把**当时那一次岔决定**（节点 + 进向轨 → 第几条腿）
 *       交给 {@code place}，它就能重建，而且与真身**逐位相同**。
 *       ⇒ 线上要带的是"**岔决定**"，不是"道岔现在的位置"。</li>
 *   <li>{@link #theDecisionStillWorksAfterTheInterlockingThrowsThePointBack()} —— 把模拟器的道岔
 *       **扳回正线**（车清岔之后联锁本来就会这么干）之后，同一份岔决定仍然逐位还原。
 *       ⇒ "知道道岔状态"是**必要但不充分**：历史决定与当前状态是两件事。</li>
 *   <li>{@link #placingFromTheHeadBuildsAReversedBody()} —— {@code place} 只有"A 端朝 B 端铺"
 *       这一个方向。把**头部**（B 端）当起点去铺，得到的是另一条脊线（占用面与真身不同），
 *       而且**它是成功返回的**（安静地错）。⇒ 线上必须发**命名端（A 端）**；要支持"从头部铺"
 *       得新增一个反向入口。</li>
 *   <li>{@link #seamArcsDoNotFollowTheBody()} —— 实测发现：接缝弧长（以及内部驾驶室弧长）
 *       **不随车体移动**，车走了多少米就漂多少米。⇒ 线上表达单元边界只能按"**第 k 节之后**"，
 *       绝不能发弧长。这一条是**现状读数**（疑为缺陷），本用例只钉住它、不修。</li>
 * </ol>
 *
 * <h2>为什么这一轮只做引擎侧</h2>
 * <p>它不碰网络层、不碰渲染、不改任何生产代码 —— 只把"信息量够不够"从判断变成读数。</p>
 */
public final class MmtrRebuildSelfCheckTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String OWNER = "self-check";
	private static final long WINDOW = 100_000L;
	/** 自检用的车列：三节 5 m 车，第 1 节之后有车钩 ⇒ 一处接缝（= 两个单元）。 */
	private static final double[] CAR_LENGTHS_M = {5, 5, 5};
	private static final boolean[] COUPLER_AFTER = {false, true, false};
	private static final double A_END_OFFSET_M = 2;
	private static final double ADVANCE_M = 8;
	/** 接缝在车体内的位置：第 0、1 节车长之和。 */
	private static final double SEAM_IN_BODY_M = 10;

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static Rail diverge(Position node, Position far) {
		return Rail.newRail(node, Angle.fromAngle(45), far, Angle.fromAngle(225), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/**
	 * 咽喉一处岔口：根部 {@code rIn} 上来，正线 {@code rStraight} 与岔股 {@code rDiverge} 二选一。
	 * 与 {@code MmtrConsistWalkerApiTests.Fork} 同一形状（那里已证明"授权第 1 条腿 ⇒ 真的走岔股"）。
	 */
	private static final class Fork {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-rebuild-selfcheck"), false);
		final Position nIn = new Position(-40, 0, 0);
		final Position node0 = new Position(-20, 0, 0);
		final Rail rIn = through(nIn, node0);
		final Rail rStraight = through(node0, new Position(0, 0, 0));
		final Rail rDiverge = diverge(node0, new Position(0, 0, 12));

		Fork() {
			sim.rails.add(rIn);
			sim.rails.add(rStraight);
			sim.rails.add(rDiverge);
			sim.sync();
		}

		/** 当时那一次岔决定：从根部轨进这个节点，走第 1 条腿（岔股）。 */
		BranchStore divergenceDecision() {
			final BranchStore decision = new BranchStore();
			decision.set(node0.getX(), node0.getY(), node0.getZ(), rIn.getHexId(), 1);
			return decision;
		}
	}

	/**
	 * 真身：一列三节车**跨在岔口上** —— 车尾（A 端）还在根部轨上，车头已经上了岔股。
	 * 这是"编组体跨轨"的现场，也是"只发头部位置"最容易出事的那种位置。
	 */
	private static final class Spanning {
		final Fork fork = new Fork();
		final MmtrConsistWalker walker;
		/** 放下那一刻、接缝在车体内的位置（从 A 端量），m。 */
		final double seamInBodyAtPlacementM;
		/** 这一轮实际消耗掉的距离，m。 */
		final double travelledM;

		Spanning() {
			// 先在根部轨上放下来（车体 15 m < 根部轨 20 m ⇒ 这一次不需要选岔）
			walker = MmtrConsistWalker.place(fork.sim, new BranchStore(), fork.rIn, fork.nIn, A_END_OFFSET_M,
				CAR_LENGTHS_M, null,
				MmtrConsistBody.seamArcMsFrom(A_END_OFFSET_M, CAR_LENGTHS_M, COUPLER_AFTER),
				MmtrConsistBody.seamCarIndexesFrom(CAR_LENGTHS_M, COUPLER_AFTER));
			assertNotNull(walker, "先在根部轨上放下：这一步不该需要选岔");
			assertEquals(1, walker.body().legCount(), "放下时车体整在根部轨上");
			assertEquals(1, walker.body().seamCount(), "夹具：两节车之后一处接缝");
			seamInBodyAtPlacementM = walker.body().seamArcM(0) - walker.body().aEndArcM();

			// 车头朝 B 端开：给岔股发一次授权（第 1 条腿），开 8 m ⇒ 车头压上岔股、车尾仍在根部
			walker.insertKey(Cab.CAB_B, true, true);
			final MmtrPointAuthority authority = new MmtrPointAuthority(() -> 0L);
			walker.setPointAuthority(authority, OWNER);
			assertEquals(MmtrPointAuthority.Result.GRANTED, authority.request(fork.node0.getX(), fork.node0.getY(), fork.node0.getZ(),
				fork.rIn.getHexId(), OWNER, 1, WINDOW), "授权岔股那一条腿");
			assertTrue(walker.advance(ADVANCE_M), "车头应当压上岔股");
			travelledM = walker.distanceM();
		}

		MmtrConsistBody body() {
			return walker.body();
		}
	}

	// ------------------------------------------------------------------ 读数工具

	private static String fmt(double value) {
		return String.format(Locale.ROOT, "%.6f", value);
	}

	/**
	 * 短 hex：这份 world 的坐标全为负，hex 前 8 位**每根轨都一样**（全是 F），
	 * 所以显示 id 取"头 4 + 尾 10" —— 只看前缀会把两根不同的轨看成同一根。
	 */
	private static String shortHex(String hex) {
		return hex.length() <= 14 ? hex : hex.substring(0, 4) + "…" + hex.substring(hex.length() - 10);
	}

	/** 一份可逐位比较的几何摘要：车长 + 一端弧 + 逐节车中心 + 占用面。 */
	private static String digest(MmtrConsistBody body) {
		final StringBuilder builder = new StringBuilder("len=").append(fmt(body.lengthM())).append(" aEnd=").append(fmt(body.aEndArcM()));
		final double[] centers = body.carCenterArcMs();
		for (int i = 0; i < centers.length; i++) {
			builder.append(" c").append(i).append('=').append(fmt(centers[i]));
		}
		for (final OccupiedSegment segment : body.occupancy()) {
			builder.append(" [").append(shortHex(segment.railHex()))
				.append(' ').append(fmt(segment.fromM())).append("..").append(fmt(segment.toM())).append(']');
		}
		return builder.toString();
	}

	/** 客户端手上有的东西：轨序（hex + 入端）+ 一端位置 + 逐车长 + 接缝 ⇒ 直接拼出车体，不选岔。 */
	private static MmtrConsistBody rebuildFromRailList(Simulator sim, MmtrConsistBody truth, double aEndArcM) {
		final ObjectArrayList<SpineLeg> spine = new ObjectArrayList<>();
		for (int i = 0; i < truth.legCount(); i++) {
			final SpineLeg leg = truth.leg(i);
			final Rail rail = sim.railIdMap.get(leg.railHex());
			assertNotNull(rail, "客户端必须持有这根轨才能重建（轨表按渲染半径裁剪，缺轨是真实场景）");
			spine.add(new SpineLeg(leg.railHex(), leg.entryNode(), leg.exitNode(), rail.railMath.getLength()));
		}
		return new MmtrConsistBody(spine, aEndArcM, CAR_LENGTHS_M,
			MmtrConsistBody.seamArcMsFrom(aEndArcM, CAR_LENGTHS_M, COUPLER_AFTER),
			MmtrConsistBody.seamCarIndexesFrom(CAR_LENGTHS_M, COUPLER_AFTER));
	}

	/** 沿"真身的 A 端（rail / 入端 / 轨内偏移）"重建，选岔靠给定的道岔表。 */
	private static MmtrConsistWalker placeFromAEnd(Spanning s, BranchStore branches) {
		final MmtrConsistBody truth = s.body();
		final SpineLeg aEnd = truth.leg(0);
		return MmtrConsistWalker.place(s.fork.sim, branches,
			s.fork.sim.railIdMap.get(aEnd.railHex()), aEnd.entryNode(), truth.aEndArcM(),
			CAR_LENGTHS_M, null,
			MmtrConsistBody.seamArcMsFrom(truth.aEndArcM(), CAR_LENGTHS_M, COUPLER_AFTER),
			MmtrConsistBody.seamCarIndexesFrom(CAR_LENGTHS_M, COUPLER_AFTER));
	}

	// ------------------------------------------------------------------ ① 轨序重建逐位相同

	@Test
	public void railListReconstructionIsBitIdentical() {
		final Spanning s = new Spanning();
		final MmtrConsistBody truth = s.body();
		assertEquals(2, truth.legCount(), "车体应当跨在两根轨上（根部 + 岔股）");
		assertEquals(2, truth.occupancy().size(), "占用面应当两段：一截在根部、一截在岔股");

		final SpineLeg aEnd = truth.leg(0);
		final MmtrConsistBody rebuilt = rebuildFromRailList(s.fork.sim, truth, truth.aEndArcM());
		final String truthDigest = digest(truth);
		final String rebuiltDigest = digest(rebuilt);

		System.out.println("[REBUILD] ① 轨序重建：A端=" + shortHex(aEnd.railHex()) + "@" + fmt(truth.aEndArcM()) + " legs=" + truth.legCount()
			+ "\n[REBUILD]   真身 " + truthDigest
			+ "\n[REBUILD]   重建 " + rebuiltDigest);
		assertEquals(truthDigest, rebuiltDigest, "给足轨序时，「一端位置 + 逐车长 + 接缝」必须逐位还原几何");
	}

	// ------------------------------------------------------------------ ② 没有岔决定就建不出来

	@Test
	public void placeCanNotRebuildWithoutTheForkDecision() {
		final Spanning s = new Spanning();
		final MmtrConsistWalker attempt = placeFromAEnd(s, new BranchStore());
		System.out.println("[REBUILD] ② 空的道岔表 → place 返回 " + (attempt == null ? "null（建不出来）" : "一个走行器"));
		assertNull(attempt, "岔口上没有任何提示时 place 选不出腿 —— 客户端拿不到岔决定就建不出脊线");
	}

	// ------------------------------------------------------------------ ③ 需要的是"岔决定"

	@Test
	public void theForkDecisionIsWhatPlaceNeeds() {
		final Spanning s = new Spanning();
		final MmtrConsistWalker rebuilt = placeFromAEnd(s, s.fork.divergenceDecision());
		assertNotNull(rebuilt, "给出岔决定之后，place 应当能重建");
		final String truthDigest = digest(s.body());
		final String rebuiltDigest = digest(rebuilt.body());
		System.out.println("[REBUILD] ③ 给出岔决定（节点+进向轨 → 第 1 条腿）"
			+ "\n[REBUILD]   真身 " + truthDigest
			+ "\n[REBUILD]   重建 " + rebuiltDigest);
		assertEquals(truthDigest, rebuiltDigest, "岔决定就是缺的那一项：补上它重建逐位相同");
	}

	// ------------------------------------------------------------------ ④ 联锁把道岔扳回去之后仍然成立

	@Test
	public void theDecisionStillWorksAfterTheInterlockingThrowsThePointBack() {
		final Spanning s = new Spanning();
		final BranchStore decision = s.fork.divergenceDecision();
		// 车已经出了岔区 ⇒ 联锁把道岔扳回正线（真实世界里 rear-clear 之后就是这样）
		s.fork.sim.mmtrSetTurnoutPosition(s.fork.node0.getX(), s.fork.node0.getY(), s.fork.node0.getZ(), MmtrTurnout.NORMAL);
		final int nowPosition = s.fork.sim.mmtrTurnoutPosition(s.fork.node0.getX(), s.fork.node0.getY(), s.fork.node0.getZ());
		assertEquals(MmtrTurnout.NORMAL, nowPosition, "夹具：道岔现在确实在正线位，与那一次决定相反");

		final MmtrConsistWalker rebuilt = placeFromAEnd(s, decision);
		final String truthDigest = digest(s.body());
		final String rebuiltDigest = rebuilt == null ? "null" : digest(rebuilt.body());
		System.out.println("[REBUILD] ④ 道岔现在=" + nowPosition + "（正线），而当时的决定=第 1 条腿（岔股）"
			+ " → 用决定重建：" + (rebuilt == null ? "null" : "成功")
			+ "\n[REBUILD]   真身 " + truthDigest
			+ "\n[REBUILD]   重建 " + rebuiltDigest);

		assertNotNull(rebuilt, "重建靠的是那份岔决定，与道岔当前在哪一位无关");
		assertEquals(truthDigest, rebuiltDigest, "当前道岔位置被扳回也不影响重建 —— 决定 ≠ 现状");
	}

	// ------------------------------------------------------------------ ⑤ 从头部铺会得到另一条脊线

	@Test
	public void placingFromTheHeadBuildsAReversedBody() {
		final Spanning s = new Spanning();
		final MmtrConsistBody truth = s.body();
		// 车头 = B 端（CAB_B 在岗 ⇒ 行进朝 B）。B 端落在岔股上，轨内偏移 = bEnd − 该腿起点。
		final int headLegIndex = truth.legIndexAtArcM(truth.bEndArcM(), false);
		assertTrue(headLegIndex >= 0, "车头必须落在脊线上");
		final SpineLeg headLeg = truth.leg(headLegIndex);
		final double headOffsetM = truth.bEndArcM() - truth.legStartArcM(headLegIndex);

		final MmtrConsistWalker fromHead = MmtrConsistWalker.place(s.fork.sim, new BranchStore(),
			s.fork.sim.railIdMap.get(headLeg.railHex()), headLeg.entryNode(), headOffsetM, CAR_LENGTHS_M, null);

		System.out.println("[REBUILD] ⑤ 头部=" + shortHex(headLeg.railHex()) + "@" + fmt(headOffsetM)
			+ " → place 返回 " + (fromHead == null ? "null" : digest(fromHead.body()))
			+ "\n[REBUILD]   真身 " + digest(truth));
		assertNotNull(fromHead, "从头部铺也能「成功」—— 这正是危险之处：它成功得很安静");
		assertNotEquals(digest(truth), digest(fromHead.body()),
			"place 只有「A 端朝 B 端铺」一个方向：拿头部当起点得到的是另一条脊线");
	}

	// ------------------------------------------------------------------ ⑥ 接缝弧不随车体移动（现状读数）

	@Test
	public void seamArcsDoNotFollowTheBody() {
		final Spanning s = new Spanning();
		assertEquals(ADVANCE_M, s.travelledM, 1e-6, "夹具：这一轮真的走满了 " + fmt(ADVANCE_M) + " m");
		final double inBodyAfter = s.body().seamArcM(0) - s.body().aEndArcM();

		System.out.println("[REBUILD] ⑥ 接缝在车体内的位置：走行前 " + fmt(s.seamInBodyAtPlacementM)
			+ " m → 走行 " + fmt(s.travelledM) + " m 后 " + fmt(inBodyAfter) + " m（应当恒为 " + fmt(SEAM_IN_BODY_M) + "）"
			+ "；carIndexAfterSeam=" + s.body().carIndexAfterSeam(0) + "（这个不漂）");

		assertEquals(SEAM_IN_BODY_M, s.seamInBodyAtPlacementM, 1e-6, "放下时接缝正好在两节车之后");
		assertEquals(1, s.body().carIndexAfterSeam(0), "接缝的「第 k 节之后」表达是稳定的");
		/*
		 * ★ 现状读数（疑为缺陷，未修）：接缝弧与内部驾驶室弧都是**脊线空间的绝对值**，而
		 * `MmtrConsistBody.slideBy` 只移动 aEndArcM、不移动它们 ⇒ 车走了多少米，接缝在车体内的
		 * 位置就漂多少米。生产路径每 tick 都走 `Vehicle:2941 → mmtrMotionWalker.advance`，
		 * 而 `MmtrCoupleSurgery.uncouple` 直接拿 `body.seamArcM(k)` 当"后段的 A 端弧"用（:549），
		 * 所以"挂上之后开一段再解挂"这一支会切在错的地方。既有用例都在
		 * 「刚刚合并完、一步没开」的状态下切分（`MmtrCoupleSurgeryTests:333`），因此没有覆盖。
		 *
		 * 这里钉的是**漂移量 = 走行距离**这条规律（而不是一个魔法数）：真要修的时候，
		 * 这条断言会红，提醒连同 notes 一起更新。
		 */
		assertEquals(s.travelledM, s.seamInBodyAtPlacementM - inBodyAfter, 1e-6,
			"接缝弧不随车体移动：漂移量恒等于走行距离（现状读数，见 notes/406）");
	}

	// ------------------------------------------------------------------ ⑦ 内部驾驶室弧同样不随车体移动

	/**
	 * 与 ⑥ 同一个机制，但换一个消费者：{@code MmtrCabState.cabArcM} 也是**脊线空间的绝对值**
	 * （`MmtrConsistBodyCouplingTests:243` 拿它与 `carEndArcM(1)` 直接比），而没有任何地方在
	 * 走行时平移它。生产里读它的是连挂/解挂的"合并在哪一节"（{@code MmtrCoupleSurgery.cabArcOf}）
	 * 与换端后的内部驾驶室定位。
	 */
	@Test
	public void interiorCabArcDoesNotFollowTheBodyEither() {
		final Spanning s = new Spanning();
		final MmtrCabState cabs = s.walker.cabs();
		assertTrue(cabs.removeKey(), "先拔掉具名端的钥匙，才能按弧长插进编组内部");
		final double arcInBodyM = 2.5;
		final double cabArcAtInsert = s.body().carStartArcM(0) + arcInBodyM;
		assertTrue(cabs.insertKeyAtArc(cabArcAtInsert, false, true, true, null), "按弧长把钥匙插进编组内部");
		assertEquals(cabArcAtInsert, cabs.cabArcM(), 1e-9, "插入值原样存下（绝对值）");
		final double cabInBodyBefore = cabs.cabArcM() - s.body().aEndArcM();

		final double aEndBeforeM = s.body().aEndArcM();
		assertTrue(s.walker.advance(4), "脊线已经够长，这一段不该需要再选岔");
		final double travelledM = s.body().aEndArcM() - aEndBeforeM;
		final double cabInBodyAfter = cabs.cabArcM() - s.body().aEndArcM();

		System.out.println("[REBUILD] ⑦ 内部驾驶室在车体内的位置：走行前 " + fmt(cabInBodyBefore)
			+ " m → 走行 " + fmt(travelledM) + " m 后 " + fmt(cabInBodyAfter) + " m（应当恒为 " + fmt(arcInBodyM) + "）");

		assertEquals(4.0, travelledM, 1e-6, "夹具：这一段真的走了 4 m");
		assertEquals(travelledM, cabInBodyBefore - cabInBodyAfter, 1e-6,
			"内部驾驶室弧同样不随车体移动：漂移量恒等于走行距离（现状读数，见 notes/406）");
	}
}
