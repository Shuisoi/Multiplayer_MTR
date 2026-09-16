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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Level 1「轨道区间」的可执行规范（notes/166，用户 2026-09-15 拍板）。
 *
 * <p>用户模型原话：「单根轨道是双向的……无论是上行信号灯还是下行信号灯，都可以将轨道切成"轨道区间"，
 * 每个轨道区间端点都会在上行或下行有一个信号灯。」现实对照：检测区段（无方向，边界 = 绝缘节）
 * ＋ 闭塞分区（有方向，边界 = 停车信号机）。本文件钉的是**下面那一层**。</p>
 *
 * <p>本层与 Level 2（{@code Section}，见 {@link MmtrSectionServiceTests}）的分工：</p>
 * <ul>
 *   <li>L1 **没有方向**：同一段弧只属于一个轨道区间，双向共用；</li>
 *   <li>L1 的切点**只由灯产生**（上行灯、下行灯都算）；</li>
 *   <li>段沿"唯一续轨"的链推进，遇切点 / 分叉（度 ≠ 2）/ 线路尽头收口；</li>
 *   <li>**占用只在这一层判定**（{@code isOccupied(TrackSection, …)}），与行车方向无关。</li>
 * </ul>
 *
 * <p>角约定与 Level 2 的用例同一套（0 = 南 +Z / 90 = 西 −X / 180 = 北 −Z / 270 = 东 +X）。</p>
 */
public final class MmtrTrackSectionTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final float EAST = 270;
	private static final float WEST = 90;

	/** A straight rail from {@code p1} to {@code p2}, with the ends' ACTUAL bearings (see the L2 suite). */
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

	/** Register an AUTO lamp (no target) at {@code arcM} of {@code rail}, facing {@code angle}. */
	private static String addLamp(Simulator simulator, Rail rail, double arcM, float angle) {
		final Vector position = rail.railMath.getPosition(arcM, false);
		final int x = (int) Math.floor(position.x());
		final int y = (int) Math.floor(position.y());
		final int z = (int) Math.floor(position.z());
		simulator.mmtrSignals.put(x, y, z, angle, 4, "AUTO", "");
		return MmtrSignalRegistry.key(x, y, z);
	}

	private static ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> occupancy(Rail rail, double fromM, double toM) {
		return occupancy(rail, fromM, toM, 1);
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

	/** 某一点上有几个轨道区间（唯一划分判据：必须恰好 1）。 */
	private static int ownersOf(MmtrSectionService service, Rail rail, double arcM) {
		int owners = 0;
		for (final MmtrSectionService.TrackSection section : service.trackSectionsOf(rail.getHexId())) {
			for (final MmtrSectionService.TrackSpan span : section.spans) {
				if (span.railHex.equals(rail.getHexId()) && span.containsArc(arcM)) {
					owners++;
				}
			}
		}
		return owners;
	}

	/**
	 * **没有灯时，一根轨就是一个轨道区间**（沿唯一续轨一路接下去）。
	 *
	 * <p>现实依据：检测区段的边界是绝缘节，不是轨的"画法分段"；没有信号机/绝缘节的地方就是一段。
	 * 这条也正是"节点不再无条件切分"（v1 的 {@code NODE} 边界已删）。</p>
	 */
	@Test
	public void withoutALampAChainOfRailsIsOneTrackSection() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Rail r3 = rail(new Position(200, 0, 0), new Position(300, 0, 0));
		final Simulator simulator = sim("build/mmtr-track-unsignalled", r1, r2, r3);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		assertEquals(1, service.trackSectionCount(), "三根轨接成一条无灯走廊 = 一个轨道区间");
		final MmtrSectionService.TrackSection section = service.trackSectionAt(r2.getHexId(), 50);
		assertNotNull(section);
		assertEquals(3, section.spans.size(), "它跨过两个接头（度 = 2 的节点）");
		assertEquals(300, section.lengthM(), 1.0, "整条走廊 300 m 都在这一段里");
		// 中介接头不是切点
		assertFalse(service.startsSectionAt(r1.getHexId(), r1.railMath.getLength()), "接头不是切点：两侧接成了一段");
	}

	/**
	 * **灯站在节点上时，那个节点就是切点**（用户模型：每个轨道区间端点都有灯）。
	 *
	 * <p>这是本轮修掉的一个真漏洞：投影落在弧 0 / 轨长时，若只当成"轨端"，度 = 2 的接头仍会把两侧
	 * 接起来 —— 那盏灯就白站了（它划出的分界在数据里不存在）。</p>
	 */
	@Test
	public void aLampAtTheJointCutsTheChainInTwo() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = sim("build/mmtr-track-joint-lamp", r1, r2);
		addLamp(simulator, r2, 0, EAST);   // 朝东的灯站在接头上：它开的是 r2 那一段
		final MmtrSectionService service = new MmtrSectionService(simulator);

		assertEquals(2, service.trackSectionCount(), "接头上有灯 ⇒ 走廊被切成两段");
		final MmtrSectionService.TrackSection west = service.trackSectionAt(r1.getHexId(), 50);
		final MmtrSectionService.TrackSection east = service.trackSectionAt(r2.getHexId(), 50);
		assertNotNull(west);
		assertNotNull(east);
		assertEquals(100, west.lengthM(), 1.0, "西侧那段只有 r1");
		assertEquals(100, east.lengthM(), 1.0, "东侧那段只有 r2");
		assertTrue(service.startsSectionAt(r2.getHexId(), 0), "接头现在**是**切点：东侧那一段从接头起步");
	}

	/**
	 * **每个方向各自的灯都切，切点取并集**（用户模型里的"上行或下行"）。
	 *
	 * <p>一根 200 m 轨上有两盏反向的灯（弧 50 朝东、弧 150 朝西）⇒ 三个轨道区间；
	 * 而 L1 不知道方向，所以它只看见"两个切点"。</p>
	 */
	@Test
	public void cutPointsAreTheUnionOfBothDirections() {
		final Rail r = rail(new Position(0, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = sim("build/mmtr-track-both-directions", r);
		addLamp(simulator, r, 50, EAST);
		addLamp(simulator, r, 150, WEST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		assertEquals(3, service.trackSectionCount(), "两个切点 ⇒ 三段");
		assertEquals(50, service.trackSectionAt(r.getHexId(), 25).lengthM(), 1.0);
		assertEquals(100, service.trackSectionAt(r.getHexId(), 100).lengthM(), 1.0);
		assertEquals(50, service.trackSectionAt(r.getHexId(), 175).lengthM(), 1.0);
	}

	/**
	 * **唯一划分**：轨上每一米**恰好**属于一个轨道区间（不是"至多一个"）。
	 *
	 * <p>旧套件里那条用例的名字写着 {@code WithoutGapsOrOverlaps}，断言却只有 {@code owners <= 1}
	 * —— 天窗抓不到（现实里正是 44 根轨不属于任何区间）。本层是占用的唯一单位，所以这里必须
	 * **恰好一个**：既不许重叠，也不许有洞。</p>
	 */
	@Test
	public void everyPointBelongsToExactlyOneTrackSection() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(120, 0, 0));
		final Rail r2 = rail(new Position(120, 0, 0), new Position(240, 0, 0));
		final Simulator simulator = sim("build/mmtr-track-partition", r1, r2);
		addLamp(simulator, r1, 0, EAST);
		addLamp(simulator, r1, 60, EAST);
		addLamp(simulator, r2, 120, WEST);   // 站在 r2 的远端：投影在轨长上
		addLamp(simulator, r2, 40, WEST);
		final MmtrSectionService service = new MmtrSectionService(simulator);
		assertEquals(3, service.trackSectionCount(), "两个内部切点 + 一个节点切点 ⇒ 3 段（r1 前段 / r1 后段 + r2 前段 / r2 后段）");

		for (final Rail rail : new Rail[]{r1, r2}) {
			final double length = rail.railMath.getLength();
			for (double arc = 0; arc < length; arc += 0.25) {
				final int owners = ownersOf(service, rail, arc);
				assertEquals(1, owners, "轨 " + rail.getHexId() + " 的 "
					+ Math.round(arc * 100) / 100.0 + " m 处有 " + owners + " 个轨道区间（必须恰好 1）");
			}
		}
	}

	/**
	 * **没有任何信号灯的连通块整块作为一个大区间**（notes/166 用户裁定：「没信号灯就按一整个
	 * 就是大区间来看就行了」）。
	 *
	 * <p>这一条把 notes/157 的「没有灯照到的轨**不属于任何区间**」反转过来：那段路上"一区段一车"
	 * 必须有单位，否则占用与停车都失去依据（旧模型下 140 轨里有 44 根就处于这种状态）。</p>
	 *
	 * <p>夹具：一条 100 m 的轨 A 接在 100 m 的轨 B 后面，只有 B 上有一盏朝东的灯 ——
	 * 灯守着 B，**A 在灯的身后**，任何灯的走行都到不了。A 必须自己成为一段（两个方向各一段）。</p>
	 */
	@Test
	public void aStretchNoLampReachesBecomesOneBigSectionInBothDirections() {
		final Rail a = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail b = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = sim("build/mmtr-track-uncovered", a, b);
		addLamp(simulator, b, 0, EAST);   // 朝东的灯站在接头上：它守 B，走行到 B 的尽头
		final MmtrSectionService service = new MmtrSectionService(simulator);

		assertEquals(1, service.uncoveredSections().size() / 2, "A 这一段没有任何灯 → 一块无灯大区间（两段 = 两个方向）");
		final MmtrSectionService.Section east = service.sectionAt(a.getHexId(), 50, 1, 0);
		final MmtrSectionService.Section west = service.sectionAt(a.getHexId(), 50, -1, 0);
		assertNotNull(east, "向东走：这一段有行车区间");
		assertNotNull(west, "向西走：这一段也有（两个方向各一段）");
		assertEquals("", east.entrySignalKey, "它不属于任何一盏灯（没有入口灯）");
		assertTrue(east.endsAtDeadEnd, "它是补出来的整块，不是灯到灯的走行结果");
		assertEquals(100, east.lengthM(), 1.0, "整块一个区间：100 m，而不是一根轨一段");
		assertEquals(-1, west.spans.get(0).headingX, 1e-6, "反向那一段的走向是西");
		assertEquals(1, east.spans.get(0).headingX, 1e-6, "正向那一段的走向是东");

		// 占用照常能问（这一段是占用的单位，不是"没人管的空地"）
		assertTrue(service.isOccupied(east, occupancy(a, 20, 40, 9)), "车停在无灯区里 → 这一段被占");
	}

	/**
	 * **有灯照到的地段不补无灯区间**（补出来的段不许与灯到灯的段重叠）。
	 */
	@Test
	public void aLampLitStretchIsNotDuplicatedByAnUncoveredSection() {
		final Rail r = rail(new Position(0, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = sim("build/mmtr-track-covered", r);
		addLamp(simulator, r, 0, EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		assertTrue(service.uncoveredSections().isEmpty(), "整根轨都被那盏灯的走行盖住 → 不补无灯区间");
		final MmtrSectionService.Section section = service.sectionAt(r.getHexId(), 100, 1, 0);
		assertNotNull(section);
		assertEquals(200, section.lengthM(), 1.0, "灯到灯的那一段仍然是唯一答案");
	}

	/**
	 * **占用判定粒度是轨道区间，然后扩大至行车区间**（用户 2026-09-15 的口径）。
	 *
	 * <p>物理事实：一根轨就是一根轨，两个方向共用它 —— 所以"被占"这件事只在 **Level 1（无方向）**
	 * 上成立；**Level 2 的被占 = 它的成员里任意一段被占**。</p>
	 *
	 * <p>夹具特意造成"一个 L2 段由**多个** L1 段拼成"：r1(0..100) 接在一个**岔口**上，
	 * 岔口后面是 r2(100..200) 与一条岔线 —— L1 在岔口处必然收口（**段不能分叉**），
	 * 而一盏灯开出的东行区间会跟着**每一条前向腿**走（"一盏灯守整个咽喉"）。
	 * 于是"占用在 L1 判定、再扩大到 L2"这件事才有可测的形状。</p>
	 */
	@Test
	public void occupancyIsDecidedAtLevelOneAndWidenedToLevelTwo() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Rail branch = rail(new Position(100, 0, 0), new Position(180, 0, 60));
		final Simulator simulator = sim("build/mmtr-track-occupancy-widening", r1, r2, branch);
		addLamp(simulator, r1, 0, EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		// ① L1：岔口把三条腿切成三段
		assertEquals(3, service.trackSectionCount(), "岔口处 L1 必然收口 ⇒ 三段轨道区间");

		// ② 东行区间（弧 0 那盏灯开的）跨过岔口、把 r2 与岔线都收进来 ⇒ 成员 ≥ 2
		final MmtrSectionService.Section eastbound = service.sectionAt(r2.getHexId(), 50, 1, 0);
		assertNotNull(eastbound);
		final var members = service.trackSectionsOf(eastbound);
		assertTrue(members.size() >= 2, "这个行车区间由多个轨道区间拼成：" + members);

		// ③ 占用：占住**其中一个成员** ⇒ 整个行车区间被占（这就是"扩大"）；树是空的则不算被占
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> empty =
			ObjectArrayList.of(new Object2ObjectAVLTreeMap<>());
		assertFalse(service.isOccupied(eastbound, empty, 0), "树里没有车 ⇒ 这一段是空的");
		assertTrue(service.isOccupied(eastbound, occupancy(r2, 60, 90, 7), 0), "占住成员段 r2[60,90] ⇒ 这一段被占");

		// ④ 灯所在那一段也是成员（区间从灯那里起步）：占住它同样 ⇒ 被占
		final MmtrSectionService.TrackSection r1Track = service.trackSectionAt(r1.getHexId(), 50);
		assertNotNull(r1Track);
		assertTrue(members.contains(r1Track), "灯所在那一段也是成员：" + members);
		assertTrue(service.isOccupied(eastbound, occupancy(r1, 10, 40, 7)), "占住灯那一段 ⇒ 也被占");
	}

	/**
	 * 同一段物理轨在两个方向的区间里**共用同一份占用状态**（无方向的那一层说了算）。
	 */
	@Test
	public void occupancyIsDirectionNeutralAcrossBothDirections() {
		final Rail r = rail(new Position(0, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = sim("build/mmtr-track-both-dir-occupancy", r);
		addLamp(simulator, r, 50, EAST);
		addLamp(simulator, r, 150, WEST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final MmtrSectionService.TrackSection middle = service.trackSectionAt(r.getHexId(), 100);
		assertNotNull(middle);
		// 同一个 L1 段被两个方向的区间各自覆盖
		final MmtrSectionService.Section eastbound = service.sectionAt(r.getHexId(), 100, 1, 0);
		final MmtrSectionService.Section westbound = service.sectionAt(r.getHexId(), 100, -1, 0);
		assertNotNull(eastbound);
		assertNotNull(westbound);

		final var trees = occupancy(r, 90, 150, 7);   // 整车压在中间那一段上
		assertTrue(service.isOccupied(middle, trees, 0), "L1：这一段被占");
		assertTrue(service.isOccupied(eastbound, trees, 0), "东行区间：被占（从 L1 扩大上来）");
		assertTrue(service.isOccupied(westbound, trees, 0), "西行区间：同一段轨、同一份占用 ⇒ 也被占");
	}

	/**
	 * **灯的状态绑定在"它开的那一段行车区间"上**（用户 2026-09-15：「需要将行车区间状态绑定至信号灯上」）。
	 *
	 * <p>关键是否证：**灯站的那根轨被占，不等于它的段被占**。夹具把灯放在 r1 的远端、朝东 ——
	 * 它守的是 r2（它面朝那一侧），于是占住 r1（灯自己脚下的轨）不该让它变红。</p>
	 */
	@Test
	public void lampStateFollowsTheSectionItOpensNotTheRailItStandsOn() {
		final Rail r1 = rail(new Position(-100, 0, 0), new Position(0, 0, 0));
		final Rail r2 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Simulator simulator = sim("build/mmtr-lamp-binding", r1, r2);
		final String lamp = addLamp(simulator, r1, r1.railMath.getLength(), EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final var empty = service.lampBindings(
			ObjectArrayList.of(new Object2ObjectAVLTreeMap<>()), ignored -> false).get(lamp);
		assertNotNull(empty, "每盏登记灯都有一条绑定");
		assertFalse(empty.unbound, "这盏灯接入了闭塞");
		assertEquals(1, empty.sections.size(), "它开出一段行车区间");
		assertEquals(r2.getHexId(), empty.sections.get(0).entryRailHex(), "它开的是它面朝那一侧的轨（r2）");
		assertEquals("GREEN", empty.aspect, "线路空闲 ⇒ 绿");
		assertFalse(empty.occupied);

		// ① 占住**灯脚下的那根轨**（r1，不属于它开的段）⇒ 绑定不该报被占、灯仍绿
		final var behind = service.lampBindings(occupancy(r1, 20, 60, 7), ignored -> false).get(lamp);
		assertFalse(behind.occupied, "灯站的那根轨被占 ≠ 它开的段被占");
		assertEquals("GREEN", behind.aspect, "所以灯还是绿 —— 这就是「绑定在段上、不是绑在轨上」");

		// ② 占住**它开的那一段**（r2）⇒ 绑定报被占、灯红
		final var onSection = service.lampBindings(occupancy(r2, 40, 60, 7), ignored -> false).get(lamp);
		assertTrue(onSection.occupied, "它开的段被占 ⇒ 绑定上报被占");
		assertEquals("RED", onSection.aspect, "灯的显示跟着它开的段走");
	}

	/** 一灯多腿（咽喉）时绑定给多条段，显示取**最不利**的那一条。 */
	@Test
	public void aMultiLegLampReportsEverySectionItOpensAndTakesTheWorstAspect() {
		final Rail approach = rail(new Position(-100, 0, 0), new Position(0, 0, 0));
		final Rail straight = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail branch = rail(new Position(0, 0, 0), new Position(80, 0, 60));
		final Simulator simulator = sim("build/mmtr-lamp-binding-legs", approach, straight, branch);
		final String lamp = addLamp(simulator, approach, approach.railMath.getLength(), EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final var binding = service.lampBindings(
			ObjectArrayList.of(new Object2ObjectAVLTreeMap<>()), ignored -> false).get(lamp);
		assertNotNull(binding);
		assertEquals(2, binding.sections.size(), "岔口旁朝东的灯守两条腿 ⇒ 开出两段：" + binding.sections);
		assertEquals("GREEN", binding.aspect, "两条腿都空 ⇒ 绿");

		// ① 占住**走得到**的那条腿（正线）⇒ 最不利 ⇒ 红（信号不能因为另一条腿通畅就放行）
		final var onStraight = service.lampBindings(occupancy(straight, 20, 60, 7), ignored -> false).get(lamp);
		assertTrue(onStraight.occupied, "正线被占 ⇒ 绑定报被占");
		assertEquals("RED", onStraight.aspect, "一灯多腿取最不利：它守的腿被占 ⇒ 红");

		/*
		 * ② 占住岔股那条腿：**物理占用是真的**（绑定照实报），但**不参与颜色** ——
		 * 道岔默认位把它切断了（"被切开的腿不算进路"这条既有铁律，见笔记的 blockedAtDeparture）。
		 * 两条事实一起钉住，免得以后有人把"占用"与"显示"混成一个字段。
		 */
		final var onBranch = service.lampBindings(occupancy(branch, 20, 60, 7), ignored -> false).get(lamp);
		assertTrue(onBranch.occupied, "岔股上确实有车 ⇒ 绑定报被占（占用是物理事实）");
		assertEquals("GREEN", onBranch.aspect, "但被道岔切掉的那条腿不参与颜色 ⇒ 显示仍按走得到的那条腿给绿");
	}

	/** 守不到任何轨的灯：绑定**如实报"未接入闭塞"**，不给一个看起来正常的绿。 */
	@Test
	public void aLampThatReachesNoRailIsReportedUnbound() {
		final Rail r = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Simulator simulator = sim("build/mmtr-lamp-binding-unbound", r);
		simulator.mmtrSignals.put(500, 60, 500, EAST, 4, "AUTO", "");   // 离任何轨都远超 8 m
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final var binding = service.lampBindings(
			ObjectArrayList.of(new Object2ObjectAVLTreeMap<>()), ignored -> false).get("500,60,500");
		assertNotNull(binding, "登记过就有绑定");
		assertTrue(binding.unbound, "守不到任何轨 ⇒ 未接入闭塞");
		assertTrue(binding.sections.isEmpty(), "没有区间");
		assertEquals("", binding.aspect, "不给显示结论（网页/游戏应显示未知，而不是绿）");
	}

	/** **段不能分叉**：道岔（度 = 3）处即使没有灯也必然收口（现实：道岔区段自成一段）。 */
	@Test
	public void aJunctionAlwaysEndsATrackSection() {
		final Rail west = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail east = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Rail branch = rail(new Position(100, 0, 0), new Position(100, 0, 100));
		final Simulator simulator = sim("build/mmtr-track-junction", west, east, branch);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		assertEquals(3, service.trackSectionCount(), "三条腿各自成段：段是线性的，不能分叉");
		for (final Rail rail : new Rail[]{west, east, branch}) {
			assertEquals(1, ownersOf(service, rail, rail.railMath.getLength() / 2), "每根轨的中点恰好一个归属");
		}
	}

	/**
	 * **占用无方向**：判据里没有任何"朝哪走"的入参 —— 谁压在这段轨上就算谁占（现实的检测层口径）。
	 *
	 * <p>量纲与 Level 2 同一份（重叠要占到车长的一半，见 {@code overlapsEnough}）：整车停在里面算占，
	 * 只骑在边界上压进来几米不算。</p>
	 */
	@Test
	public void occupancyIsDirectionNeutralAndUsesOneThreshold() {
		final Rail r = rail(new Position(0, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = sim("build/mmtr-track-occupancy", r);
		addLamp(simulator, r, 0, EAST);
		addLamp(simulator, r, 100, EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);
		final MmtrSectionService.TrackSection middle = service.trackSectionAt(r.getHexId(), 50);
		assertNotNull(middle);
		assertTrue(middle.lengthM() > 90, "中间那段的长度：" + middle.lengthM());

		// ① 整车停在中间那段里 → 占
		assertTrue(service.isOccupied(middle, occupancy(r, 40, 60, 7)));
		// ② 只骑在分界上压进来 2 m（车长 16 m ⇒ 下限 5 m）→ **不**占
		assertFalse(service.isOccupied(middle, occupancy(r, 98, 114, 7)), "骑边界压 2 m 不算占用");
		// ③ 自己的足迹可以排除（避免车被自己的影子扣住）
		assertTrue(service.isOccupied(middle, occupancy(r, 40, 60, 7), 0));
		assertFalse(service.isOccupied(middle, occupancy(r, 40, 60, 7), 7), "排除自己之后这段是空的");

		// ④ 同一段弧、两个方向共用它：L1 的答案只有一份（没有"哪个方向"这个入参）
		assertSame(middle, service.trackSectionAt(r.getHexId(), 50));
	}
}
