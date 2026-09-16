package org.mtr.core.mmtr.signal;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.VehiclePosition;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Vector;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **总区间**的可执行规范（notes/168，用户 2026-09-15 提出并认可）。
 *
 * <p>用户原话：「以错开的一段轨道区间为例，一辆车在其中间，代表着这辆车**既在上行区间中，也在下行
 * 区间中**，那么这时候就需要引出下一层了，叫做**总区间**，用于显示在地图上。」并追问了这一层该
 * 放在网页还是服务端 —— 裁定：**服务端**（几何与归属都是引擎的结论，网页只画）。</p>
 *
 * <h3>它不是第三层划分</h3>
 * <p>两个方向的行车区间各自都是"若干 L1 段的并"，所以它们的交、并、按覆盖配对分组**都还是 L1 段的并**；
 * 而相邻两个 L1 段之间必有一盏灯（上行灯会换掉上行区间、下行灯会换掉下行区间），于是"覆盖配对相同"的
 * 最大连段**恰好就是 L1 段本身**。想要更粗就只能放弃某一个方向的灯当界 ——
 * 那与"切点只由灯产生"的裁定冲突。所以总区间的**几何 = L1**，它多出来的是"这一处由哪几个方向的
 * 哪几段覆盖"。本文件钉的就是这两半：**几何与 L1 一一对应**，**归属按方向如实列出**。</p>
 */
public final class MmtrTotalSectionTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final float EAST = 270;
	private static final float WEST = 90;

	private static Rail rail(Position p1, Position p2) {
		final double bearing = Math.toDegrees(Math.atan2(p2.getZ() - p1.getZ(), p2.getX() - p1.getX()));
		return Rail.newRail(p1, Angle.fromAngle((float) bearing), p2, Angle.fromAngle((float) (bearing + 180)),
			Rail.Shape.QUADRATIC, 0, NO_STYLES, 80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static Simulator sim(String savePath, Rail... rails) {
		final Simulator simulator = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
		for (final Rail r : rails) {
			simulator.rails.add(r);
		}
		simulator.sync();
		return simulator;
	}

	private static void addLamp(Simulator simulator, Rail rail, double arcM, float angle) {
		final Vector position = rail.railMath.getPosition(arcM, false);
		simulator.mmtrSignals.put((int) Math.floor(position.x()), (int) Math.floor(position.y()), (int) Math.floor(position.z()), angle, 4, "AUTO", "");
	}

	private static ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> occupancy(Rail rail, double fromM, double toM, long vehicleId) {
		final Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> tree = new Object2ObjectAVLTreeMap<>();
		final VehiclePosition vehiclePosition = new VehiclePosition();
		vehiclePosition.addSegment(fromM, toM, vehicleId);
		final Position[] ordered = rail.mmtrOrderedPositions();
		final Object2ObjectAVLTreeMap<Position, VehiclePosition> inner = new Object2ObjectAVLTreeMap<>();
		inner.put(ordered[1], vehiclePosition);
		tree.put(ordered[0], inner);
		return ObjectArrayList.of(tree);
	}

	/** 落在 {@code arcM} 那一处的总区间（轨迹唯一划分 ⇒ 恰好一段）。 */
	private static MmtrSectionService.TotalView at(MmtrSectionService service, Rail rail, double arcM, ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees) {
		for (final MmtrSectionService.TotalView total : service.totalSectionViews(trees, key -> false)) {
			for (final MmtrSectionService.TrackSpan span : total.track.spans) {
				if (span.railHex.equals(rail.getHexId()) && span.containsArc(arcM)) {
					return total;
				}
			}
		}
		return null;
	}

	/**
	 * **地图上每个位置恰好一条带**：总区间与轨道区间一一对应（同一批 id、同一批几何）。
	 *
	 * <p>这一条把"总区间不是第三层"钉死：如果哪天有人又按"上下行都照到"去重新划一遍，
	 * 数量就会与 L1 对不上。</p>
	 */
	@Test
	public void everyPlaceHasOneTotalSectionWhoseGeometryIsTheTrackSection() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = sim("build/mmtr-total-plain", r1, r2);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final ObjectArrayList<MmtrSectionService.TotalView> totals = service.totalSectionViews(null, key -> false);
		assertEquals(service.trackSectionCount(), totals.size(), "总区间与轨道区间一一对应（几何就是 L1）");
		assertEquals(1, totals.size(), "无灯走廊 = 一个位置");

		final MmtrSectionService.TotalView only = totals.get(0);
		assertEquals(service.trackSectionAt(r2.getHexId(), 50).id, only.track.id, "id 就是那一段 L1（同一批 id）");
		assertEquals(2, only.track.spans.size(), "几何 = L1 的那两个 span（跨过一个接头）");
		assertEquals(2, only.directionCount(), "两个方向都没有灯 ⇒ 两个方向各补一段无灯大区间");
		assertEquals(2, only.covers.size());
		for (final MmtrSectionService.SectionView cover : only.covers) {
			assertEquals("", cover.entrySignalKey, "补出来的段没有入口灯");
			assertEquals("", cover.aspect, "无灯区段没有显示 —— 前端必须画成无信号，绝不能被当成绿灯");
		}
		assertFalse(only.occupied, "空线");

		// 占用仍然只有一条判据（L1）：车压在这段轨上，这一段位置被占，两个方向的 cover 也都报占
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = occupancy(r2, 20, 40, 7);
		final MmtrSectionService.TotalView busy = at(service, r2, 30, trees);
		assertNotNull(busy);
		assertTrue(busy.occupied, "车在无灯区里 → 这一段位置被占");
		for (final MmtrSectionService.SectionView cover : busy.covers) {
			assertTrue(cover.occupied, "无灯大区间同样是占用的载体");
		}
	}

	/**
	 * **错开处的核心语义**：车在错开的一段里，**既在上行区间中、也在下行区间中**。
	 *
	 * <p>夹具（一段 200 m 轨，两盏反向的灯错开布置）：</p>
	 * <ul>
	 *   <li>东行灯在弧 50 ⇒ 它守 {@code [50, 200]}；</li>
	 *   <li>西行灯在弧 150 ⇒ 它守 {@code [0, 150]}；</li>
	 *   <li>于是弧 50..150 这一段**两个方向都照到，却不是同一段路** —— 车停在弧 100 时，
	 *       它同时落在"东行 50..200"与"西行 0..150"两段区间里。</li>
	 * </ul>
	 *
	 * <p>两端那两段（弧 &lt; 50 与弧 &gt; 150）只有**一个**方向照到：这就是"错开"的可测形状 ——
	 * 一串总区间里只有中间那段是错开的。</p>
	 */
	@Test
	public void aCarOnAStaggeredStretchSitsInBothDirectionalSectionsAtOnce() {
		final Rail r = rail(new Position(0, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = sim("build/mmtr-total-staggered", r);
		addLamp(simulator, r, 50, EAST);
		addLamp(simulator, r, 150, WEST);
		final MmtrSectionService service = new MmtrSectionService(simulator);
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = occupancy(r, 90, 110, 7);

		final ObjectArrayList<MmtrSectionService.TotalView> totals = service.totalSectionViews(trees, key -> false);
		assertEquals(3, totals.size(), "两个切点（弧 50、弧 150）⇒ 三段位置");
		int staggered = 0;
		for (final MmtrSectionService.TotalView total : totals) {
			if (total.staggered()) {
				staggered++;
			}
		}
		assertEquals(1, staggered, "只有中间那一段是错开的");

		final MmtrSectionService.TotalView middle = at(service, r, 100, trees);
		assertNotNull(middle);
		assertEquals(100, middle.lengthM(), 0.5, "错开段就是弧 50..150");
		assertEquals(2, middle.directionCount(), "上下行都照到这一处");
		assertTrue(middle.staggered(), "两个方向的区间不是同一段路 = 错开");
		assertTrue(middle.occupied, "车 90..110 停在这一处");
		assertEquals(2, middle.covers.size(), "东行 [50,200] 与西行 [0,150]");
		int occupiedCovers = 0;
		for (final MmtrSectionService.SectionView cover : middle.covers) {
			assertFalse(cover.entrySignalKey.isEmpty(), "错开处两个方向都有灯守着（不是补出来的无灯段）");
			if (cover.occupied) {
				occupiedCovers++;
			}
		}
		assertEquals(2, occupiedCovers, "车压在同一根轨上 ⇒ 两个方向的区间**都**报占用（既在上行、也在下行）");

		// 两端各只有一个方向照到 —— 一辆车在那里就只属于一个方向的区间
		final MmtrSectionService.TotalView westEnd = at(service, r, 25, trees);
		assertEquals(1, westEnd.directionCount(), "弧 25：只有西行灯的区间（0..150）照到这里");
		assertFalse(westEnd.staggered(), "只有一个方向 ⇒ 谈不上错开");
		final MmtrSectionService.TotalView eastEnd = at(service, r, 175, trees);
		assertEquals(1, eastEnd.directionCount(), "弧 175：只有东行灯的区间（50..200）照到这里");
		assertFalse(eastEnd.staggered());
	}

	/**
	 * **成对布置的灯不算错开**：两个方向的区间守的是**同一段路**（起止重合）。
	 *
	 * <p>这是"不是错开"的对照夹具 —— 一条 200 m 线路（A 接 B），西端一盏东行灯、东端一盏西行灯，
	 * 两段区间都覆盖 A+B。此时每一处位置的两个方向归属都指向同一段路，"同一段路"必须按**集合**比
	 * （上行段从西往东数、下行段从东往西数，span 表顺序正好相反），否则成对的灯会被误判成错开。</p>
	 */
	@Test
	public void lampsPairedAtTheTwoEndsAreNotStaggered() {
		final Rail a = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail b = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = sim("build/mmtr-total-aligned", a, b);
		addLamp(simulator, a, 0, EAST);      // 东行：守 A+B
		addLamp(simulator, b, 100, WEST);    // 西行：守 B+A（站在 B 的东端）
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final ObjectArrayList<MmtrSectionService.TotalView> totals = service.totalSectionViews(null, key -> false);
		assertEquals(1, totals.size(), "两盏灯都站在线路的**两端**（端点不是切点）⇒ 整条 200 m 就是一段");
		for (final MmtrSectionService.TotalView total : totals) {
			assertEquals(2, total.directionCount(), "两个方向都照到这一段");
			assertEquals(2, total.covers.size());
			assertTrue(total.aligned(), "上下行守的是同一段路 ⇒ 不是错开");
			assertFalse(total.staggered());
		}
	}

	/**
	 * **一个方向有灯、另一个方向没有** 时的如实归属。
	 *
	 * <p>补出来的无灯大区间只在"**任何**区间都没照到"的窗口上生成（两个方向是同一批窗口，各补一段），
	 * 所以：</p>
	 * <ul>
	 *   <li>弧 25 被西行那一段（0..150）照到 ⇒ **一条** cover，且它是灯到灯的段；</li>
	 *   <li>弧 175 谁都没照到 ⇒ **两条** cover，都是补出来的无灯段、都没有显示。</li>
	 * </ul>
	 *
	 * <p>"无灯"与"另一方向的区间"必须分得清：前者 {@code entrySignal} 为空、{@code aspect} 为空，
	 * 前端要画成无信号区段，绝不能当成绿灯。</p>
	 */
	@Test
	public void aStretchOnlyOneDirectionReachesReportsASingleCover() {
		final Rail r = rail(new Position(0, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = sim("build/mmtr-total-one-sided", r);
		addLamp(simulator, r, 150, WEST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final MmtrSectionService.TotalView behind = at(service, r, 25, null);
		assertNotNull(behind);
		assertEquals(1, behind.directionCount(), "西行灯的区间（0..150）是唯一照到这里的");
		assertEquals(1, behind.covers.size());
		assertEquals(150, behind.covers.get(0).lengthM(), 0.5);
		assertFalse(behind.covers.get(0).entrySignalKey.isEmpty(), "它是灯到灯的段，不是补出来的");

		final MmtrSectionService.TotalView ahead = at(service, r, 175, null);
		assertNotNull(ahead);
		assertEquals(2, ahead.covers.size(), "灯的前方（弧 150..200）谁都没照到 ⇒ 两个方向各补一段");
		for (final MmtrSectionService.SectionView cover : ahead.covers) {
			assertEquals("", cover.entrySignalKey, "无灯大区间没有入口灯");
			assertEquals("", cover.aspect, "也没有显示");
		}
	}
}
