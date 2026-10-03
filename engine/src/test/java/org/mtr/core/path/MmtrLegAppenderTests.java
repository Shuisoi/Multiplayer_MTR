package org.mtr.core.path;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.mmtr.net.MmtrMotionFrame;
import org.mtr.core.tool.Angle;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **① 的腿表落到客户端路径上**（notes/369 S3b / notes/375）的规矩。
 *
 * <p>这一层要防的现场（错一条的表现都是"某一节车摆到别的轨上"或"车不动再瞬移"，很贵）：</p>
 * <ol>
 *   <li>没有基准、又不是整表时**不许猜**（等 ② 的整份或下一拍那张整表）；</li>
 *   <li>**整表替换**（换端/换向：同一批轨、相反顺序）按服务端给的**锚点 + 逐腿方向**重建，
 *       客户端一个数都不推；</li>
 *   <li>hex 查不到轨、或新腿的接入端不等于当前游标时**原子地**放弃（连"该丢的尾巴"也不丢）——
 *       原来的"先丢后接"会留下残表，而残表 = 渲染每帧把车夹回阴影末端 = **车不动**；</li>
 *   <li>接上的腿**必须续算累计里程**（从旧表最后一根的末端接着算）——
 *       从 0 重算就是把整列车瞬移到线路起点。</li>
 * </ol>
 */
public final class MmtrLegAppenderTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	/** 东西向直线轨，声明顺序 (a → b)：a 的 x 小。 */
	private static Rail rail(Position a, Position b) {
		return Rail.newRail(a, Angle.fromAngle(0), b, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES, 80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static Position p(int x) {
		return new Position(x, 0, 0);
	}

	/** 一根腿的上线形态：规范化 hex + 接入端方向位。 */
	private static MmtrMotionFrame.Leg leg(Rail rail, boolean entryIsOrdered1) {
		return new MmtrMotionFrame.Leg(rail.getHexId(), entryIsOrdered1);
	}

	/** A(0) → B(100) → C(200) → D(300)。 */
	private static final class Line {

		final Rail ab = rail(p(0), p(100));
		final Rail bc = rail(p(100), p(200));
		final Rail cd = rail(p(200), p(300));
		final Map<String, Rail> railIdMap = new Object2ObjectOpenHashMap<>();

		Line() {
			railIdMap.put(ab.getHexId(), ab);
			railIdMap.put(bc.getHexId(), bc);
			railIdMap.put(cd.getHexId(), cd);
		}

		/** 从 {@code first} 起按顺序拼一条腿表（车尾 → 车头，方向都是"往前"），累计里程从 0 起。 */
		ObjectArrayList<PathData> path(Rail... rails) {
			final ObjectArrayList<PathData> path = new ObjectArrayList<>();
			for (final Rail r : rails) {
				final Position[] ends = r.mmtrOrderedPositions();
				path.add(new PathData(r, 0L, 0L, 0, ends[0], ends[1]));
			}
			SidingPathFinder.generatePathDataDistances(path, 0);
			return path;
		}
	}

	@Test
	public void appendsAndContinuesCumulativeDistance() {
		final Line line = new Line();
		final ObjectArrayList<PathData> path = line.path(line.ab, line.bc);

		final MmtrLegAppender.Applied applied = MmtrLegAppender.apply(path, 1, 1000, List.of(leg(line.cd, true)), line.railIdMap, 150);

		assertEquals(MmtrLegAppender.Reason.OK, applied.reason());
		assertEquals(1, applied.dropped(), "车尾端那一根整根都在车尾之后，应该丢掉");
		assertEquals(1, applied.appended());
		assertEquals(2, path.size());
		assertSame(line.bc, path.get(0).getRail());
		assertSame(line.cd, path.get(1).getRail());
		// 续算：C→D 的起点就是 B→C 的终点，终点再往后一根轨长。
		assertEquals(path.get(0).getEndDistance(), path.get(1).getStartDistance(), 1e-9);
		assertEquals(200, path.get(1).getStartDistance(), 0.01);
		assertEquals(300, path.get(1).getEndDistance(), 0.01);
		// 方向：新腿的末端落在 D（继续往前），而不是回到 C。
		assertEquals(p(300), MmtrLegAppender.endPosition(path.get(1)));
	}

	@Test
	public void appendsRailThatIsDeclaredBackwards() {
		final Line line = new Line();
		// 反向声明的一根轨：hex 是规范化的（= railIdMap 的键），但轨本身是 (300 → 200)。
		// 行驶方向仍然是 C → D，于是**接入端 = 规范化顺序的第一端 (200)**，方向位就是 true。
		final Rail reverseDeclared = rail(p(300), p(200));
		line.railIdMap.put(reverseDeclared.getHexId(), reverseDeclared);
		final ObjectArrayList<PathData> path = line.path(line.ab, line.bc);

		final MmtrLegAppender.Applied applied = MmtrLegAppender.apply(path, 0, 1000, List.of(leg(reverseDeclared, true)), line.railIdMap, -1000);

		assertEquals(MmtrLegAppender.Reason.OK, applied.reason());
		assertEquals(1, applied.appended());
		assertEquals(3, path.size());
		// 关键：接入端由**服务端给的方向位**定，而不是由 hex 的书写方向推出。
		assertEquals(p(200), MmtrLegAppender.entryPosition(path.get(2)));
		assertEquals(p(300), MmtrLegAppender.endPosition(path.get(2)));
		assertEquals(200, path.get(2).getStartDistance(), 0.01);
		assertEquals(300, path.get(2).getEndDistance(), 0.01);
	}

	/** 方向位说错 = 与现有腿接不上 ⇒ **一根都不动**（原子），不许"丢完尾巴才发现接不上"。 */
	@Test
	public void aWrongDirectionBitIsRefusedAtomically() {
		final Line line = new Line();
		final ObjectArrayList<PathData> path = line.path(line.ab, line.bc);

		// 车尾远远落在后面（tail=1000 ⇒ 那一根"可以丢"），方向位却说接入端是 D(300) —— 接不上。
		final MmtrLegAppender.Applied applied = MmtrLegAppender.apply(path, 1, 1000, List.of(leg(line.cd, false)), line.railIdMap, 1000);

		assertEquals(MmtrLegAppender.Reason.DISCONTINUOUS, applied.reason());
		assertEquals(0, applied.dropped(), "接不上时连尾巴都不许丢（否则留下一张残表 ⇒ 车不动）");
		assertEquals(0, applied.appended());
		assertEquals(2, path.size());
		assertSame(line.ab, path.get(0).getRail());
		assertSame(line.bc, path.get(1).getRail());
	}

	@Test
	public void refusesIncrementWithoutBasePath() {
		final Line line = new Line();
		final ObjectArrayList<PathData> empty = new ObjectArrayList<>();

		final MmtrLegAppender.Applied applied = MmtrLegAppender.apply(empty, 0, 0, List.of(leg(line.ab, true)), line.railIdMap, 0);

		assertEquals(MmtrLegAppender.Reason.NO_BASE, applied.reason());
		assertTrue(empty.isEmpty(), "没有基准时一个元素都不许动");
	}

	/**
	 * ★ **换端**（notes/375）：整表 + 锚点 + 逐腿方向 ⇒ 客户端重建出一张与服务端同坐标系的表。
	 *
	 * <p>换端前：车尾 → 车头 = [A→B, B→C]，里程 0..200，车头压在 180。换端后方向反过来，
	 * 表变成 [C→B, B→A]，锚点由服务端给（这里取 −20，表示表起点在镜像里程 −20 处）。</p>
	 */
	@Test
	public void fullReplaceRebuildsFromTheAnchorAndTheDirectionBits() {
		final Line line = new Line();
		final ObjectArrayList<PathData> path = line.path(line.ab, line.bc);
		assertEquals(200, path.get(1).getEndDistance(), 0.01);

		final MmtrLegAppender.Applied applied = MmtrLegAppender.apply(path, MmtrMotionFrame.LEGS_FULL_REPLACE, -20,
			List.of(leg(line.bc, false), leg(line.ab, false)), line.railIdMap, 150);

		assertEquals(MmtrLegAppender.Reason.REPLACED, applied.reason());
		assertEquals(2, applied.appended());
		assertEquals(2, path.size());
		// 顺序与方向都换了：表头是 C→B，表尾是 B→A。
		assertSame(line.bc, path.get(0).getRail());
		assertSame(line.ab, path.get(1).getRail());
		assertEquals(p(200), MmtrLegAppender.entryPosition(path.get(0)));
		assertEquals(p(100), MmtrLegAppender.endPosition(path.get(0)));
		assertEquals(p(100), MmtrLegAppender.entryPosition(path.get(1)));
		assertEquals(p(0), MmtrLegAppender.endPosition(path.get(1)));
		// 锚点：表起点就是服务端说的那个数，整表往后 200 m。
		assertEquals(-20, path.get(0).getStartDistance(), 0.01);
		assertEquals(80, path.get(0).getEndDistance(), 0.01);
		assertEquals(80, path.get(1).getStartDistance(), 0.01);
		assertEquals(180, path.get(1).getEndDistance(), 0.01);
	}

	/** 整表替换也**可以在没有基准时**落地（客户端手上那张表刚被清掉/还没建起来）。 */
	@Test
	public void fullReplaceWorksWithoutABase() {
		final Line line = new Line();
		final ObjectArrayList<PathData> empty = new ObjectArrayList<>();

		final MmtrLegAppender.Applied applied = MmtrLegAppender.apply(empty, MmtrMotionFrame.LEGS_FULL_REPLACE, 500,
			List.of(leg(line.ab, true), leg(line.bc, true)), line.railIdMap, 0);

		assertEquals(MmtrLegAppender.Reason.REPLACED, applied.reason());
		assertEquals(2, empty.size());
		assertEquals(500, empty.get(0).getStartDistance(), 0.01);
		assertEquals(700, empty.get(1).getEndDistance(), 0.01);
	}

	/** 整表里有一根轨不认识 / 链断了 ⇒ 旧表原样保留（宁可画旧表，也不摆一列错位的车）。 */
	@Test
	public void aBrokenFullTableLeavesTheOldOneAlone() {
		final Line line = new Line();
		final ObjectArrayList<PathData> path = line.path(line.ab, line.bc);

		final MmtrLegAppender.Applied unknownRail = MmtrLegAppender.apply(path, MmtrMotionFrame.LEGS_FULL_REPLACE, 0,
			List.of(leg(line.bc, false), new MmtrMotionFrame.Leg("0000-0000-0000-0001-0000-0000", true)), line.railIdMap, 150);
		assertEquals(MmtrLegAppender.Reason.UNKNOWN_RAIL, unknownRail.reason());
		assertEquals(2, path.size());
		assertSame(line.ab, path.get(0).getRail());

		// 链断：第二根说自己从 C 进，但第一根（C→B）的末端是 B。
		final MmtrLegAppender.Applied brokenChain = MmtrLegAppender.apply(path, MmtrMotionFrame.LEGS_FULL_REPLACE, 0,
			List.of(leg(line.bc, false), leg(line.ab, true)), line.railIdMap, 150);
		assertEquals(MmtrLegAppender.Reason.DISCONTINUOUS, brokenChain.reason());
		assertEquals(2, path.size());
		assertSame(line.ab, path.get(0).getRail());

		// 空表：说不出车在哪根轨上 —— 留着旧表。
		final MmtrLegAppender.Applied emptyTable = MmtrLegAppender.apply(path, MmtrMotionFrame.LEGS_FULL_REPLACE, 0, List.of(), line.railIdMap, 150);
		assertEquals(MmtrLegAppender.Reason.EMPTY_TABLE, emptyTable.reason());
		assertEquals(2, path.size());
	}

	@Test
	public void stopsOnUnknownRail() {
		final Line line = new Line();
		final ObjectArrayList<PathData> path = line.path(line.ab, line.bc);

		final MmtrLegAppender.Applied applied = MmtrLegAppender.apply(path, 0, 0, List.of(new MmtrMotionFrame.Leg("0000-0000-0000-0001-0000-0000", true)), line.railIdMap, -1000);

		assertEquals(MmtrLegAppender.Reason.UNKNOWN_RAIL, applied.reason());
		assertEquals(0, applied.appended());
		assertEquals(2, path.size());
	}

	@Test
	public void stopsWhenDiscontinuous() {
		final Line line = new Line();
		final ObjectArrayList<PathData> path = line.path(line.ab, line.bc);

		// 方向位说从"远轨"的某一端进，而游标在 B→C 的末端 (200)：接不上。
		final Rail far = rail(p(1000), p(1100));
		line.railIdMap.put(far.getHexId(), far);

		final MmtrLegAppender.Applied applied = MmtrLegAppender.apply(path, 0, 0, List.of(leg(far, true)), line.railIdMap, -1000);

		assertEquals(MmtrLegAppender.Reason.DISCONTINUOUS, applied.reason());
		assertEquals(0, applied.appended());
		assertEquals(2, path.size(), "接不上时列表保持原样（等一张整表）");
	}

	@Test
	public void doesNotDropLegsStillUnderTheBody() {
		final Line line = new Line();
		final ObjectArrayList<PathData> path = line.path(line.ab, line.bc, line.cd);

		// 车尾还在 A→B 中间（tail=50）⇒ 第一根腿不许丢。
		final MmtrLegAppender.Applied applied = MmtrLegAppender.apply(path, 1, 0, List.of(), line.railIdMap, 50);

		assertEquals(0, applied.dropped());
		assertEquals(3, path.size());
		assertSame(line.ab, path.get(0).getRail());
	}

	@Test
	public void dropsLegsEntirelyBehindTheBody() {
		final Line line = new Line();
		final ObjectArrayList<PathData> path = line.path(line.ab, line.bc, line.cd);

		final MmtrLegAppender.Applied applied = MmtrLegAppender.apply(path, 2, 0, List.of(), line.railIdMap, 200);

		assertEquals(2, applied.dropped());
		assertEquals(1, path.size());
		assertSame(line.cd, path.get(0).getRail(), "留下的必须是车头那一根");
	}

	/** 车头那一根永远留着：它是"车头在哪根轨上"的唯一判据（丢光了摆车就找不到落点）。 */
	@Test
	public void neverDropsTheHeadLeg() {
		final Line line = new Line();
		final ObjectArrayList<PathData> path = line.path(line.cd);

		final MmtrLegAppender.Applied applied = MmtrLegAppender.apply(path, 5, 0, List.of(), line.railIdMap, 10_000);

		assertEquals(0, applied.dropped());
		assertEquals(1, path.size());
		assertSame(line.cd, path.get(0).getRail());
	}
}
