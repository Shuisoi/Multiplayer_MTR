package org.mtr.core.path;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.tool.Angle;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **① 的腿阴影增量落到客户端路径上**（notes/369 S3b）的规矩。
 *
 * <p>这一层要防的三种现场（错一条的表现都是"某一节车摆到别的轨上"，很贵）：</p>
 * <ol>
 *   <li>没有基准 / 整表替换时**不许猜**（等 ② 的整份快照）；</li>
 *   <li>hex 查不到轨、或新轨与现有腿**接不上**时停在原地（不许按声明顺序猜方向）；</li>
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

		/** 从 {@code first} 起按顺序拼一条腿表（车尾 → 车头），累计里程从 0 起。 */
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

		final MmtrLegAppender.Applied applied = MmtrLegAppender.apply(path, 1, List.of(line.cd.getHexId()), line.railIdMap, 150);

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
		// 反向声明的一根轨：hex 是规范化的（= railIdMap 的键），但轨本身是 (D(300) → E(400)) 之外的
		// C→D 声明成 D→C —— 行驶方向仍然是 C → D。
		final Rail reverseDeclared = rail(p(300), p(200));
		line.railIdMap.put(reverseDeclared.getHexId(), reverseDeclared);
		final ObjectArrayList<PathData> path = line.path(line.ab, line.bc);

		final MmtrLegAppender.Applied applied = MmtrLegAppender.apply(path, 0, List.of(reverseDeclared.getHexId()), line.railIdMap, -1000);

		assertEquals(MmtrLegAppender.Reason.OK, applied.reason());
		assertEquals(1, applied.appended());
		assertEquals(3, path.size());
		// 关键：接入端由"上一根腿的末端"推出，而不是由 hex 的书写方向推出。
		assertEquals(p(200), path.get(2).getOrderedPosition1());
		assertEquals(p(300), MmtrLegAppender.endPosition(path.get(2)));
		assertEquals(200, path.get(2).getStartDistance(), 0.01);
		assertEquals(300, path.get(2).getEndDistance(), 0.01);
	}

	@Test
	public void refusesWithoutBasePath() {
		final Line line = new Line();
		final ObjectArrayList<PathData> empty = new ObjectArrayList<>();

		final MmtrLegAppender.Applied applied = MmtrLegAppender.apply(empty, 0, List.of(line.ab.getHexId()), line.railIdMap, 0);

		assertEquals(MmtrLegAppender.Reason.NO_BASE, applied.reason());
		assertTrue(empty.isEmpty(), "没有基准时一个元素都不许动");
	}

	@Test
	public void refusesFullTableReplace() {
		final Line line = new Line();
		final ObjectArrayList<PathData> path = line.path(line.ab, line.bc);

		// 换端/换向：服务端把整表换了顺序 ⇒ 客户端保持现状，等 ② 的整份硬对齐。
		final MmtrLegAppender.Applied applied = MmtrLegAppender.apply(path, 2, List.of(line.cd.getHexId()), line.railIdMap, 0);

		assertEquals(MmtrLegAppender.Reason.FULL_REPLACE, applied.reason());
		assertEquals(2, path.size());
		assertSame(line.ab, path.get(0).getRail());
	}

	@Test
	public void stopsOnUnknownRail() {
		final Line line = new Line();
		final ObjectArrayList<PathData> path = line.path(line.ab, line.bc);

		final MmtrLegAppender.Applied applied = MmtrLegAppender.apply(path, 0, List.of("0000-0000-0000-0001-0000-0000"), line.railIdMap, -1000);

		assertEquals(MmtrLegAppender.Reason.UNKNOWN_RAIL, applied.reason());
		assertEquals(0, applied.appended());
		assertEquals(2, path.size());
	}

	@Test
	public void stopsWhenDiscontinuous() {
		final Line line = new Line();
		final ObjectArrayList<PathData> path = line.path(line.ab, line.bc);

		// 声明顺序反了、方向也接不上：游标在 B→C 的末端 (200)，而两根轨都不接在 200 上。
		final Rail far = rail(p(1000), p(1100));
		line.railIdMap.put(far.getHexId(), far);

		final MmtrLegAppender.Applied applied = MmtrLegAppender.apply(path, 0, List.of(far.getHexId()), line.railIdMap, -1000);

		assertEquals(MmtrLegAppender.Reason.DISCONTINUOUS, applied.reason());
		assertEquals(0, applied.appended());
		assertEquals(2, path.size(), "接不上时列表保持原样（留给 ② 的整份去修）");
	}

	@Test
	public void doesNotDropLegsStillUnderTheBody() {
		final Line line = new Line();
		final ObjectArrayList<PathData> path = line.path(line.ab, line.bc, line.cd);

		// 车尾还在 A→B 中间（tail=50）⇒ 第一根腿不许丢。
		final MmtrLegAppender.Applied applied = MmtrLegAppender.apply(path, 1, List.of(), line.railIdMap, 50);

		assertEquals(0, applied.dropped());
		assertEquals(3, path.size());
		assertSame(line.ab, path.get(0).getRail());
	}

	@Test
	public void dropsLegsEntirelyBehindTheBody() {
		final Line line = new Line();
		final ObjectArrayList<PathData> path = line.path(line.ab, line.bc, line.cd);

		final MmtrLegAppender.Applied applied = MmtrLegAppender.apply(path, 2, List.of(), line.railIdMap, 200);

		assertEquals(2, applied.dropped());
		assertEquals(1, path.size());
		assertSame(line.cd, path.get(0).getRail(), "留下的必须是车头那一根");
	}
}
