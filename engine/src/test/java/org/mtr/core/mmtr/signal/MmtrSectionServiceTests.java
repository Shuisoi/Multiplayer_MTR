package org.mtr.core.mmtr.signal;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闭塞区间 v2 S1: <strong>灯到灯的跨轨有向区间</strong> (pure data, not yet wired to S1 stops or display).
 *
 * <p>The behaviour under test is the whole point of v2: a lamp that stands on a rail end node still
 * creates a boundary, and the section it starts runs <em>across</em> rail boundaries until the next
 * lamp facing the same way - so a long corridor with one lamp is not "one section per 15 m rail".</p>
 *
 * <p>Facing convention (locked here): the engine stores Minecraft's block facing rotation, where
 * 0 = south (+Z), 90 = west (-X), 180 = north (-Z), 270 = east (+X). {@link #EAST} etc. are the lamp
 * angles used by the tests, so a change to the convention fails these tests.</p>
 */
public final class MmtrSectionServiceTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final float SOUTH = 0;
	private static final float WEST = 90;
	private static final float NORTH = 180;
	private static final float EAST = 270;

	/**
	 * A straight rail from {@code p1} to {@code p2}. The two end bearings are the ACTUAL bearings of the
	 * p1 -> p2 axis, not 0/180: MTR's {@link Angle} is a bearing measured clockwise from EAST (see
	 * {@code Angle.fromAngle}), and handing it 0/180 for a rail running north-south collapses the rail
	 * (RailMath treats the ends as perpendicular to the axis and yields a zero-length rail), which makes a
	 * test silently exercise nothing.
	 */
	private static Rail rail(Position p1, Position p2) {
		final double bearing = Math.toDegrees(Math.atan2(p2.getZ() - p1.getZ(), p2.getX() - p1.getX()));
		return rail(p1, Angle.fromAngle((float) bearing), p2, Angle.fromAngle((float) (bearing + 180)));
	}

	private static Rail rail(Position p1, Angle a1, Position p2, Angle a2) {
		return Rail.newRail(p1, a1, p2, a2, Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static Simulator sim(String savePath, Rail... rails) {
		final Simulator simulator = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
		for (final Rail r : rails) {
			simulator.rails.add(r);
		}
		simulator.sync();
		return simulator;
	}

	/** The block coordinates a signal block would occupy for the point at {@code arcM} on {@code rail}. */
	private static int[] blockCoordsAt(Rail rail, double arcM) {
		final Vector position = rail.railMath.getPosition(arcM, false);
		return new int[]{(int) Math.floor(position.x()), (int) Math.floor(position.y()), (int) Math.floor(position.z())};
	}

	/** Register an AUTO lamp (no target) at {@code arcM} of {@code rail}, facing {@code angle}. */
	private static String addLamp(Simulator simulator, Rail rail, double arcM, float angle) {
		final int[] coords = blockCoordsAt(rail, arcM);
		// Register directly (not via mmtrSignalOp): the op persists into the save dir and would leak
		// into a later test that reuses the same save path.
		simulator.mmtrSignals.put(coords[0], coords[1], coords[2], angle, 4, "AUTO", "");
		return MmtrSignalRegistry.key(coords[0], coords[1], coords[2]);
	}

	@Test
	public void oneLampOnALongRailMakesTheWholeRailOneSection() {
		final Rail r = rail(new Position(0, 0, 0), new Position(300, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-long", r);
		final String lamp = addLamp(simulator, r, 0, EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final MmtrSectionService.Section section = service.sectionOfSignal(lamp);
		assertNotNull(section, "a lamp standing on the rail's end node still starts a section (v1 discarded it)");
		assertEquals(1, section.spans.size(), "one rail, no further lamp -> one span");
		assertEquals(300, section.lengthM(), 1.0, "the section covers the whole 300 m rail");
		assertTrue(section.endsAtDeadEnd, "with no second lamp the walk runs to the end of the line");
	}

	@Test
	public void aSectionRunsAcrossRailBoundariesUntilTheNextLamp() {
		// Three rails in a row, 100 m each, and ONE lamp at the head: the section must span all three.
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Rail r3 = rail(new Position(200, 0, 0), new Position(300, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-cross", r1, r2, r3);
		final String lamp = addLamp(simulator, r1, 0, EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final MmtrSectionService.Section section = service.sectionOfSignal(lamp);
		assertNotNull(section);
		assertEquals(3, section.spans.size(),
			"the section crosses both rail boundaries - this is what v1 could not express");
		assertEquals(300, section.lengthM(), 1.5, "the section is the whole 300 m corridor, not one 100 m rail");
		assertEquals(r1.getHexId(), section.spans.get(0).railHex);
		assertEquals(r2.getHexId(), section.spans.get(1).railHex);
		assertEquals(r3.getHexId(), section.spans.get(2).railHex);
	}

	@Test
	public void aLampStartsAtTheNodeItStandsOnEvenWhenAHeadRailEndsThere() {
		/*
		 * 一根轨在灯站的节点处走到头。灯**不能**被绑到"下一根"上——那根是它守的那段之外。
		 *
		 * <p>规则（用户 2026-09-13）：灯管的是**它朝向的对面**那一侧。灯站在节点上朝东，
		 * 对面是西边的 head 轨 —— 所以它守 head，不守 next。这正是"车从对面开过来，
		 * 过灯之后进入 head"的口径。</p>
		 */
		final Rail head = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail next = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-node-choice", head, next);
		// 朝西（角 90 = FACING 西）的灯管西边那段：头轨 0→100 从节点 100 往西延伸。
		final String lamp = addLamp(simulator, head, 100, WEST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final MmtrSectionService.Section section = service.sectionOfSignal(lamp);
		assertNotNull(section, "the lamp protects the rail on its own side of the node");
		assertEquals(head.getHexId(), section.entryRailHex(), "它守的是朝它面那侧的对面 —— 西边那段");
		assertEquals(100, section.lengthM(), 1.5, "the full head rail, not a zero-length stub");
	}

	/**
	 * 灯到灯的边界：**下一架灯是"守同一个走行方向"的那一盏**。
	 *
	 * <h3>为什么这条判据不是"看它面朝哪边"</h3>
	 * <p>相邻两段区间由两盏灯各自守着，列车走完我们这段就进入下一段 —— 所以下一架灯判据只有一句话：
	 * <b>它自己守的那段与我们的走行方向一致</b>。至于"它面朝哪边"，两种灯的定法本来就不一样
	 * （节点旁自动推断 = 守灯面**对面**，见 notes/114；人工绑定/轨中段 = 走行**就是**灯面方向，
	 * 见 {@code ItemMmtrRailBindingTool}），所以走行不能拿"朝向"当判据，只能问几何
	 * （{@code MmtrSectionService.facesInto} → {@code guardedHeadings}）。</p>
	 *
	 * <p>这条用例的名字原来是 {@code theNextLampFacingTheSameWayEndsTheSection}
	 * （"面朝同一方向的下一架灯结束本段"）—— 那是把节点灯的朝向当成了走行方向，于是它接受的是
	 * **管反方向**的那盏灯，而真正该当界的（面朝我们、守同一方向的）那盏被跳过。实测世界里的后果：
	 * {@code -70,-59,-167} 该单黄却读成红。</p>
	 */
	@Test
	public void theNextLampGuardingTheSameDirectionEndsTheSection() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Rail r3 = rail(new Position(200, 0, 0), new Position(300, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-two-lamps", r1, r2, r3);
		// 东行链：两盏灯都"朝东"（= 方块 FACING 朝东，角 270），于是各管自己东边那段：L1 管 r1+r2，L2 管 r3。
		final String first = addLamp(simulator, r1, 0, EAST);
		final String second = addLamp(simulator, r3, 0, EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final MmtrSectionService.Section section = service.sectionOfSignal(first);
		assertNotNull(section);
		assertEquals(second, section.exitSignalKey, "the next lamp guarding the same direction ends the section");
		assertEquals(2, section.spans.size(), "from lamp 1 (r1 start) to lamp 2 (r3 start): r1 and r2");
		assertEquals(r1.getHexId(), section.spans.get(0).railHex);
		assertEquals(r2.getHexId(), section.spans.get(1).railHex);
		assertEquals(200, section.lengthM(), 1.5);
		assertNotNull(service.sectionOfSignal(second), "the second lamp starts its own section");
	}

	/**
	 * 反面：**管反方向**的那盏灯不得当界（它守的区间在走行方向的身后）。
	 *
	 * <p>同一处两格高的灯可以是"一对反向的头"，这正是 notes/112 §3.1 里"没人守的碎片"的成因：
	 * 把反方向和的头也算成界，本区间就会被切碎。</p>
	 */
	@Test
	public void aLampGuardingTheOppositeDirectionDoesNotEndTheSection() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Rail r3 = rail(new Position(200, 0, 0), new Position(300, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-opposite-head", r1, r2, r3);
		// L1 朝东（角 270）→ 管东边的 r1+r2；L2 朝西（角 90）→ 管西边的 r3，走行是**西**。
		final String first = addLamp(simulator, r1, 0, EAST);
		addLamp(simulator, r3, 0, WEST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final MmtrSectionService.Section section = service.sectionOfSignal(first);
		assertNotNull(section);
		assertNull(section.exitSignalKey,
			"a lamp guarding the opposite direction belongs to the other movement: it must not cut this block");
		assertEquals(3, section.spans.size(), "the walk runs on to the end of the line");
	}

	@Test
	public void sectionsAreDirectional() {
		// The same rail carries TWO sections, one per direction: an east-facing head at the west end
		// protects the eastbound movement, a west-facing head at the east end protects the westbound one.
		// This is what "directed" buys: the same arc of the same rail is in different sections depending
		// on which way the movement travels.
		final Rail line = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-directional", line);
		simulator.mmtrSignals.put(0, 0, 0, EAST, 4, "BOUND", line.getHexId());
		simulator.mmtrSignals.put(100, 0, 0, WEST, 4, "BOUND", line.getHexId());
		final String eastLamp = MmtrSignalRegistry.key(0, 0, 0);
		final String westLamp = MmtrSignalRegistry.key(100, 0, 0);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final MmtrSectionService.Section eastSection = service.sectionOfSignal(eastLamp);
		final MmtrSectionService.Section westSection = service.sectionOfSignal(westLamp);
		assertNotNull(eastSection, "the east-facing head at the west end starts a section");
		assertNotNull(westSection, "the west-facing head at the east end starts a section");
		assertEquals(line.getHexId(), eastSection.entryRailHex());
		assertEquals(line.getHexId(), westSection.entryRailHex());

		// Both cover the rail, but each is only seen by a movement going its way.
		assertEquals(100, eastSection.lengthM(), 1.5);
		assertEquals(100, westSection.lengthM(), 1.5);
		assertEquals(eastSection, service.sectionAt(line.getHexId(), 50, 1, 0), "eastbound -> the east-facing section");
		assertEquals(westSection, service.sectionAt(line.getHexId(), 50, -1, 0), "westbound -> the west-facing section");
		assertNull(service.sectionAt("missing", 50, 1, 0), "unknown rails are safe");
	}

	@Test
	public void aSectionReportsTheDirectionItServes() {
		/*
		 * 区间**属于哪个行车方向**必须能从接口读出来（本轮新增）。
		 *
		 * <p>为什么这是必须的而不是"可以从几何推"：双向线路上同一根轨同时属于两个方向各一个区间，
		 * 显示层要按方向画成两条带（方案 B）。如果方向只能靠 id 猜，网页就无法把"南行区间"和
		 * "北行区间"分开，双向线路永远画不对。</p>
		 *
		 * <p>判据：本用例这条轨上两个方向的区间各一个，<b>各自报出自己的方向</b>，且方向与
		 * "开这个区间的那盏灯的面朝方向"一致（区间 = 那盏灯开的那段路）。角约定与灯一致：
		 * 0 = 南行、90 = 西行、180 = 北行、270 = 东行。</p>
		 */
		final Rail line = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-section-direction", line);
		simulator.mmtrSignals.put(0, 0, 0, EAST, 4, "BOUND", line.getHexId());
		simulator.mmtrSignals.put(100, 0, 0, WEST, 4, "BOUND", line.getHexId());
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final ObjectArrayList<MmtrSectionService.SectionView> views = service.sectionViews(null, key -> false);
		assertEquals(2, views.size(), "one rail carrying both directions = two sections");

		MmtrSectionService.SectionView eastbound = null;
		MmtrSectionService.SectionView westbound = null;
		for (final MmtrSectionService.SectionView view : views) {
			if (view.direction.dx > 0.5) {
				eastbound = view;
			} else if (view.direction.dx < -0.5) {
				westbound = view;
			}
		}
		assertNotNull(eastbound, "the east-facing lamp opens an EASTBOUND section");
		assertNotNull(westbound, "the west-facing lamp opens a WESTBOUND section");
		assertEquals(270, eastbound.direction.angle, 0.2, "east = 270 in the engine's facing convention");
		assertEquals(90, westbound.direction.angle, 0.2, "west = 90");
		assertEquals("东行", eastbound.direction.label());
		assertEquals("西行", westbound.direction.label());
		// 入口灯也单列一份：前端画分界点不该去拆 id 的字符串
		assertEquals(MmtrSignalRegistry.key(0, 0, 0), eastbound.entrySignalKey);
		assertEquals(MmtrSignalRegistry.key(100, 0, 0), westbound.entrySignalKey);
	}

	/**
	 * 方向角与灯角是**同一套数**：{@code angleOfHeading} 必须是 {@code headingOf} 的逆。
	 *
	 * <p>这一条挡的是"两个方向各用一套约定"这种最难查的错——灯的角按 MTR 的 Facing（0=南），
	 * 而区间方向如果按数学角（0=东）报出去，网页画出来的箭头会**整体转 90°**，而数字看着都"挺合理"。
	 * 现场实测：{@code mmtr-sections} 报的方向角与 {@code mmtr-signals} 报的灯角逐盏对得上。</p>
	 */
	@Test
	public void theDirectionAngleUsesTheSameConventionAsTheLamps() {
		final double[][] cases = {{0, 0, 1}, {90, -1, 0}, {180, 0, -1}, {270, 1, 0}};
		for (final double[] expected : cases) {
			assertEquals(expected[0], MmtrSectionService.angleOfHeading(expected[1], expected[2]), 0.2,
				"heading (" + expected[1] + "," + expected[2] + ") must read back as the lamp angle " + expected[0]);
		}
		// 非正交方向也要能来回（区间方向不保证正好是四正方向）
		for (double angle = 0; angle < 360; angle += 7) {
			final double radians = Math.toRadians(angle);
			final double headingX = -Math.sin(radians);
			final double headingZ = Math.cos(radians);
			assertEquals(angle, MmtrSectionService.angleOfHeading(headingX, headingZ), 0.2,
				"round trip through headingOf must return the original angle " + angle);
		}
	}

	@Test
	public void aLampFacingAwayFromARailDoesNotBindToIt() {
		// The lamp stands on r1's rail but faces NORTH while the rail runs east-west: it must not be
		// bound to r1 (v1 used nearest-rail-only, so a lamp could bind to a rail behind it).
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-facing-away", r1);
		addLamp(simulator, r1, 50, NORTH);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		assertEquals(0, service.sectionCount(), "a lamp facing across the rail protects no section of it");
	}

	@Test
	public void anExplicitTargetWinsOverGeometricInference() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(0, 0, 0), new Position(0, 0, 100));
		final Simulator simulator = sim("build/mmtr-dirblock-target", r1, r2);
		// A BOUND lamp on r2's line, bound to r1: the target must win, and its section covers r1.
		final int[] coords = blockCoordsAt(r2, 0);
		simulator.mmtrSignals.put(coords[0], coords[1], coords[2], EAST, 4, "BOUND", r1.getHexId());
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final MmtrSectionService.Section section = service.sectionOfSignal(
			MmtrSignalRegistry.key(coords[0], coords[1], coords[2]));
		assertNotNull(section, "the explicit target wins");
		assertEquals(r1.getHexId(), section.entryRailHex(), "the bound rail is the one protected");
	}

	// ---------------------------------------------------------------- S2: occupancy and queries

	/** An occupancy tree holding one vehicle footprint on {@code rail} between the two arcs. */
	private static ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> occupancy(Rail rail, double fromM, double toM) {
		return occupancy(rail, fromM, toM, 1);
	}

	/** As above, with an explicit owner id (so self-exclusion can be exercised). */
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

	/**
	 * A train must never be held by ITS OWN body shadow (live defect, notes/112 §4).
	 *
	 * <p>Measured on the dev world: a train parked at offset 5.46 on a 43 m rail had written its own
	 * footprint as [5.5, 37.5) - the shadow's anchor sat ahead of its head - so S1 read a 0.04 m block stop
	 * (the stop point at the train's own feet, where the throttle does nothing) and the AWS rule read its
	 * own shadow as a RED signal ahead and stopped the train dead the moment the driver touched the
	 * throttle. The rule now ignores the asking vehicle's own footprints while still seeing everyone
	 * else's.</p>
	 */
	@Test
	public void aTrainIsNeverHeldByItsOwnFootprint() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(60, 0, 0));
		final Rail r2 = rail(new Position(60, 0, 0), new Position(120, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-self", r1, r2);
		final String lamp = addLamp(simulator, r1, 0, EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);
		assertNotNull(service.sectionOfSignal(lamp));

		final long me = 4242L;
		// My own footprint covering the very stretch the rule inspects: must NOT block me.
		assertEquals(Double.MAX_VALUE, service.sectionBoundaryAheadM(r1.getHexId(), 10, 1, 0, occupancy(r1, 12, 40, me), me), 1e-9,
			"a train's own footprint is not an obstruction ahead of it");
		assertFalse(service.isOccupied(service.sectionOfSignal(lamp), occupancy(r1, 12, 40, me), me),
			"nor does it make its own block read occupied (the AWS trigger asks the same question)");
		assertEquals(0, service.chainDepth(r1.getHexId(), new Position(0, 0, 0), occupancy(r1, 12, 40, me), key -> false, 3, me), 1e-9,
			"and the aspect chain stays clear for it");

		// Someone ELSE's footprint in the same place still blocks, as it must.
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> other = occupancy(r1, 12, 40, me + 1);
		assertTrue(service.isOccupied(service.sectionOfSignal(lamp), other, me), "another train in my block is still an obstruction");
		assertEquals(1, service.chainDepth(r1.getHexId(), new Position(0, 0, 0), other, key -> false, 3, me),
			"another train in my block still reads red for me");
		// The stop rule also still holds me at that face (12 - the head at 10 is 2 m short of it).
		assertEquals(2.0, service.sectionBoundaryAheadM(r1.getHexId(), 10, 1, 0, other, me), 0.01,
			"a foreign footprint is held at its face, exactly as before");
	}

	@Test
	public void occupancyProjectsOntoASectionThatSpansSeveralRails() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-occupancy", r1, r2);
		final String lamp = addLamp(simulator, r1, 0, EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);
		final MmtrSectionService.Section section = service.sectionOfSignal(lamp);
		assertNotNull(section);
		assertEquals(2, section.spans.size(), "the section spans two rails");

		assertFalse(service.isOccupied(section, null), "no train -> not occupied (live trees are empty)");

		// A footprint in the SECOND rail of the section still occupies the whole section.
		assertTrue(service.isOccupied(section, occupancy(r2, 20, 40)),
			"a train standing in the far span occupies the section - this is the cross-rail win");
		// A footprint in the first rail does too.
		assertTrue(service.isOccupied(section, occupancy(r1, 10, 30)), "a train in the near span occupies it");
	}

	@Test
	public void aFootprintOutsideTheSectionDoesNotOccupyIt() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-occupancy-outside", r1);
		final String lamp = addLamp(simulator, r1, 40, EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);
		final MmtrSectionService.Section section = service.sectionOfSignal(lamp);
		assertNotNull(section);
		assertEquals(40, section.entryArcM(), 1.0, "the section starts at the lamp (arc 40)");

		assertFalse(service.isOccupied(section, occupancy(r1, 0, 20)),
			"a train BEHIND the lamp is in the previous section, not this one");
		assertTrue(service.isOccupied(section, occupancy(r1, 50, 70)), "a train ahead of the lamp is in it");
	}

	@Test
	public void theSectionEndIsTheDistanceToItsOwnBoundary() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Rail r3 = rail(new Position(200, 0, 0), new Position(300, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-end-ahead", r1, r2, r3);
		addLamp(simulator, r1, 0, EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		// The one lamp's section covers all three rails (300 m); from arc 50 that is 250 m to its end.
		assertEquals(250, service.sectionEndAheadM(r1.getHexId(), 50, 1, 0), 2.0);
		assertEquals(Double.MAX_VALUE, service.sectionEndAheadM(r1.getHexId(), 50, -1, 0), 1e-9,
			"no section is directed westbound on this rail");
		assertEquals(Double.MAX_VALUE, service.sectionEndAheadM("missing", 50, 1, 0), 1e-9, "unknown rails are safe");
	}

	@Test
	public void sectionsChainThroughConsecutiveLamps() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Rail r3 = rail(new Position(200, 0, 0), new Position(300, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-chain", r1, r2, r3);
		/*
		 * 一条**东行**的链这样立：灯站在区间入口，方块 FACING 朝**东**（= 它管东边那段；灯面在它反面，
		 * 所以肉眼看是"灯面朝西、迎着东行开来的车"）。
		 *
		 * <p>角的语义见 {@code headingOf}：FACING 方向就是灯**管**的那一侧。于是 L1 站在 x=0 管 r1+r2，
		 * L2 站在 x=200 管 r3。L1 的区间在 r2 的末端被 L2 切断，出口灯正是 L2 —— 这就是"灯到灯"。</p>
		 */
		final String first = addLamp(simulator, r1, 0, EAST);
		final String second = addLamp(simulator, r3, 0, EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final MmtrSectionService.Section a = service.sectionOfSignal(first);
		final MmtrSectionService.Section b = service.sectionOfSignal(second);
		assertNotNull(a);
		assertNotNull(b);
		assertEquals(second, a.exitSignalKey, "L1 的区间在 L2 那里结束（灯到灯）");
		assertEquals(b, service.following(a), "the section beyond a lamp is the one that lamp starts");
		assertNull(service.following(b), "nothing follows the last section of the line");

		// While the near section is clear the movement is admitted to it; once occupied it waits for the
		// section beyond - which is exactly the rule S3 will wire into the S1 stop.
		assertEquals(a, service.sectionAhead(r1.getHexId(), 10, 1, 0, ObjectArrayList.of()),
			"本段空 → 给自己（可以进）");
		assertEquals(b, service.sectionAhead(r1.getHexId(), 10, 1, 0, occupancy(r1, 10, 30)),
			"本段被占 → 等的就是下一段（sectionAhead 的语义：本段空给自己，否则给下一段）");
		assertEquals(b, service.sectionAhead(r3.getHexId(), 50, 1, 0, ObjectArrayList.of()),
			"r3 上往东走的那一段就是 L2 开的 b（同一个走行方向）");
	}

	// ---------------------------------------------------------------- S4: display queries

	@Test
	public void aTrainInsideTheSectionReadsAsRedNotAsBlocksAway() {
		// The point of the display half of v2: the unit of the chain is the LAMP-TO-LAMP section, so a
		// train standing three rails ahead inside the same section means "the block I am about to enter is
		// occupied" = RED. The v1 per-rail chain would call that three blocks away (double yellow).
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Rail r3 = rail(new Position(200, 0, 0), new Position(300, 0, 0));
		final Rail r4 = rail(new Position(300, 0, 0), new Position(400, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-display", r1, r2, r3, r4);
		final String lamp = addLamp(simulator, r1, 0, EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final MmtrSectionService.Section section = service.sectionOfSignal(lamp);
		assertNotNull(section);
		assertEquals(4, section.spans.size(), "the lamp's section runs the whole corridor");

		// The lamp protects r1 entered from its west end.
		assertEquals(section, service.sectionProtecting(r1.getHexId(), new Position(0, 0, 0)),
			"entering r1 from the lamp's node is inside the lamp's section");
		assertNull(service.sectionProtecting(r1.getHexId(), new Position(100, 0, 0)),
			"entering r1 from the other end belongs to the opposite direction, not this section");
		assertNull(service.sectionProtecting("missing", new Position(0, 0, 0)), "unknown rails are safe");

		assertEquals(0, service.chainDepth(r1.getHexId(), new Position(0, 0, 0), ObjectArrayList.of(), key -> false, 3),
			"nothing occupied: the chain is clear");

		// A train on r3 - two rails beyond the next one - is still inside the lamp's own section: depth 1.
		assertEquals(1, service.chainDepth(r1.getHexId(), new Position(0, 0, 0), occupancy(r3, 20, 60), key -> false, 3),
			"a train inside the protected section is the next block, however many rails away it stands");

		assertEquals(1, service.chainDepth(r1.getHexId(), new Position(0, 0, 0), ObjectArrayList.of(),
			key -> key.equals(MmtrJunctionState.nodeKey(new Position(0, 0, 0))), 3),
			"an uncleared junction at the step's boundary restricts the same step (④)");
	}

	@Test
	public void theChainStepsToTheNextLampWhenThereIsOne() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Rail r3 = rail(new Position(200, 0, 0), new Position(300, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-display-chain", r1, r2, r3);
		// 东行链：两盏灯都朝东（角 270）→ L1 站在节点 100 管 r2，L2 站在节点 200 管 r3。
		final String first = addLamp(simulator, r2, 0, EAST);
		final String second = addLamp(simulator, r3, 0, EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);
		assertEquals(second, service.sectionOfSignal(first).exitSignalKey, "section 1 ends at lamp 2");

		// A train in the SECOND section is one step beyond the first: depth 2 = single yellow.
		assertEquals(2, service.chainDepth(r2.getHexId(), new Position(100, 0, 0), occupancy(r3, 10, 40), key -> false, 3),
			"the next lamp's own block occupied reads as a caution, not as red");
		// 而车停在自己守的那段里就是红（本段占用）。
		assertEquals(1, service.chainDepth(r2.getHexId(), new Position(100, 0, 0), occupancy(r2, 10, 40), key -> false, 3),
			"a train inside the lamp's own block is red");
	}

	/**
	 * 实机锚点回归（用户 2026-09-13 报"web 显示不对"）：一盏灯守的那段是空的、**下一架灯**守的那段上
	 * 停着车 —— 这盏灯必须读**单黄**（不是红、也不是绿）。
	 *
	 * <p>按用户世界里的真实几何缩成一条南北线：节点 N1 在 {@code (0,0,0)}，往南（+z）28 m 到 N2 是
	 * {@code mid}；N2 再往南 36 m 是 {@code depot}（尽头，上面停着车）。两盏灯都**面朝北**
	 * （角 0，{@code headingOf(0) = (0,-1)}），于是各守自己**南边**那段：A 守 {@code mid}、
	 * B 守 {@code depot} —— 与用户原话一致（"发光面朝北，接管的是南边那段"、"-70 管南段、-64 管北段"）。</p>
	 *
	 * <p>修前有两个错叠在一起：① 走行在 N2 用"节点那一格"精确查表找灯，而实机的灯摆在轨旁 3 格、
	 * 高一格，**一盏都查不到**（本世界 73 盏灯没有一盏落在节点格上）；② 就算查到了，判据要求灯面
	 * "顺着走行方向"，而真正的下一架灯是**迎着**走行方向的。两个错都让走行穿过 B，把有车的
	 * {@code depot} 吞进 A 的区间 —— A 读成**红**，而正确答案是单黄。</p>
	 */
	@Test
	public void theAnchorLampReadsSingleYellowWhenTheNextLampBlockIsOccupied() {
		final Rail mid = rail(new Position(0, 0, 0), new Position(0, 0, 28));
		final Rail depot = rail(new Position(0, 0, 28), new Position(0, 0, 64));
		final Simulator simulator = sim("build/mmtr-dirblock-anchor-single-yellow", mid, depot);
		// 灯摆在节点旁 3 格、高一格（离节点 3.16 m）—— 与实机一样，**不在**节点那一格上。
		final String lampA = MmtrSignalRegistry.key(-3, 1, 0);
		final String lampB = MmtrSignalRegistry.key(-3, 1, 28);
		simulator.mmtrSignals.put(-3, 1, 0, 0, 4, "AUTO", "");
		simulator.mmtrSignals.put(-3, 1, 28, 0, 4, "AUTO", "");
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final MmtrSectionService.Section a = service.sectionOfSignal(lampA);
		assertNotNull(a, "A 必须守到它南边那段（mid）");
		assertEquals(mid.getHexId(), a.entryRailHex(), "A 守的是 mid，不吞并下一架灯守的那段");
		assertEquals(1, a.spans.size(), "区间到下一架灯为止（灯到灯），不再一路走到尽头");
		assertEquals(lampB, a.exitSignalKey, "当界的就是南边那架灯 B");
		assertEquals(28, a.lengthM(), 1.0);
		assertNotNull(service.sectionOfSignal(lampB), "B 自己也开一段（depot）");

		// 车停在 B 守的 depot 上：A 读单黄（本段空、下一段占用），B 自己读红（本段占用）。
		final var aspects = service.lampAspectNames(occupancy(depot, 10, 30), key -> false);
		assertEquals("SINGLE_YELLOW", aspects.get(lampA), "本段空、下一段占用 = 单黄");
		assertEquals("RED", aspects.get(lampB), "本段占用 = 红");
		assertEquals("GREEN", service.lampAspectNames(ObjectArrayList.of(), key -> false).get(lampA),
			"两段都空时是绿");
	}

	/**
	 * 咽喉的多支**都要读**（用户 2026-09-10 的裁定："整个咽喉都是闭塞"；显示取最不利）。
	 *
	 * <p>缩成一条线：{@code A --E--> B --C--> D --S(有车)/N(空)--> 两侧尽头}，B、D 是节点。
	 * 四盏灯各守一段：{@code L_E} 在 A 守 E、{@code L_C} 在 B 守 C、{@code L_S} 在 D 守 S（**有车**）、
	 * {@code L_N} 在 D 守 N（空）。灯一律摆在线路旁 3 格、高一格 —— 与实机一样不在节点那一格上，
	 * 且摆在各守那条轨的**垂直方向**（于是它们的投影落在轨端，不会把轨从中间切开）。</p>
	 *
	 * <p>修前有两处错叠在一起：① 节点上找灯用"节点那一格"精确查表，实机的灯一盏都查不到；
	 * ② "只要找到任何一架面向本区间的灯，整段走行就停" —— 到达 D 时只认了与到达切线更贴合的那一支，
	 * **另一支（有车）根本没被走**。结果 {@code L_E}/{@code L_C} 都读绿，而正确答案是
	 * 单黄（{@code L_C}：本段空、下一段占用）与双黄（{@code L_E}：再往前一段才占用）。</p>
	 */
	@Test
	public void theThroatReadsThroughEveryBranchNotOnlyTheStraightestOne() {
		final Rail e = rail(new Position(0, 0, 0), new Position(40, 0, 0));
		final Rail c = rail(new Position(40, 0, 0), new Position(40, 0, 40));
		final Rail s = rail(new Position(40, 0, 40), new Position(80, 0, 40));
		final Rail n = rail(new Position(40, 0, 40), new Position(0, 0, 40));
		final Simulator simulator = sim("build/mmtr-dirblock-throat-branches", e, c, s, n);
		// 角的语义（见 headingOf）：FACING 方向 = 灯**管**的那一侧（灯面在它反面）。
		// 角 270 = FACING 东 → 管 E（+x）；角 0 = FACING 南 → 管 C（+z）；角 90 = FACING 西 → 管 N（-x）。
		final String lE = MmtrSignalRegistry.key(-3, 1, 0);
		final String lC = MmtrSignalRegistry.key(37, 1, 0);
		final String lS = MmtrSignalRegistry.key(40, 1, 43);
		final String lN = MmtrSignalRegistry.key(40, 1, 37);
		simulator.mmtrSignals.put(-3, 1, 0, 270, 4, "AUTO", "");  // FACING 东 → 管 E（+x 方向）
		simulator.mmtrSignals.put(37, 1, 0, 0, 4, "AUTO", "");    // FACING 南 → 管 C（+z 方向）
		simulator.mmtrSignals.put(40, 1, 43, 270, 4, "AUTO", ""); // FACING 东 → 管 S（+x 方向，有车）
		simulator.mmtrSignals.put(40, 1, 37, 90, 4, "AUTO", "");  // FACING 西 → 管 N（-x 方向，空）
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final MmtrSectionService.Section eSection = service.sectionOfSignal(lE);
		assertNotNull(eSection, "L_E 必须守到 E");
		assertEquals(e.getHexId(), eSection.spans.get(0).railHex);
		assertEquals(1, eSection.spans.size(), "到 B 那架灯为止（灯到灯）");
		assertEquals(lC, eSection.exitSignalKey, "在 B 被 L_C 截断");

		final MmtrSectionService.Section cSection = service.sectionOfSignal(lC);
		assertNotNull(cSection);
		assertEquals(c.getHexId(), cSection.spans.get(0).railHex, "L_C 守 C");
		assertEquals(2, cSection.exitSignalKeys.size(), "咽喉的多支都要收：朝东与朝西各一架界灯");
		assertTrue(cSection.exitSignalKeys.contains(lS), "朝东那支的入口灯是 L_S");
		assertTrue(cSection.exitSignalKeys.contains(lN), "朝西那支的入口灯是 L_N");

		// 车停在咽喉朝东那支（S）上：
		final var aspects = service.lampAspectNames(occupancy(s, 10, 30), key -> false);
		assertEquals("RED", aspects.get(lS), "L_S 自己那段有车 = 红");
		assertEquals("SINGLE_YELLOW", aspects.get(lC), "本段空 / 下一段（咽喉朝东那支）占用 = 单黄");
		assertEquals("DOUBLE_YELLOW", aspects.get(lE), "本段空 / 下一段空 / 第三段占用 = 双黄");
		assertEquals("GREEN", aspects.get(lN), "朝西那支是空的");
	}

	@Test
	public void atAForkTheSectionCoversEveryLegAndNarrowsToTheRouteWhenOneIsSet() {
		// 岔口多腿 (user ruling 2026-09-10): a lamp at a yard throat protects the WHOLE throat, not just the
		// leg it happens to face. Without a route every forward leg is the same block; with a MAIN route
		// set through the throat the block narrows to that route's own next rail.
		final Rail throat = rail(new Position(0, 0, 0), new Position(50, 0, 0));
		final Rail straight = rail(new Position(50, 0, 0), new Position(100, 0, 0));
		final Rail diverge = rail(new Position(50, 0, 0), new Position(100, 0, 12));
		final Simulator simulator = sim("build/mmtr-dirblock-fork-legs", throat, straight, diverge);
		final String lamp = addLamp(simulator, throat, 0, EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final MmtrSectionService.Section unrouted = service.sectionOfSignal(lamp);
		assertNotNull(unrouted);
		assertEquals(3, unrouted.spans.size(),
			"no route set: the throat block covers the approach AND both legs (they are one block)");

		// A MAIN route through the throat onto the straight leg narrows the walk to that leg.
		final ObjectArrayList<String> rails = new ObjectArrayList<>();
		rails.add(throat.getHexId());
		rails.add(straight.getHexId());
		simulator.mmtrRoutes.request(new org.mtr.core.mmtr.route.MmtrRoute(1L, "test", org.mtr.core.mmtr.route.MmtrRoute.Kind.MAIN,
			rails, null, straight.getHexId(), 0L));
		final MmtrSectionService.Section routed = new MmtrSectionService(simulator).sectionOfSignal(lamp);
		assertNotNull(routed);
		assertEquals(2, routed.spans.size(), "a set MAIN route narrows the throat block to its own leg");
		assertEquals(straight.getHexId(), routed.spans.get(1).railHex, "and it is the route's leg that is walked");
	}

	@Test
	public void aTurnoutNodeIsNotAnUndecidedJunction() {
		// 用户 2026-09-13 决策 (b)：一处道岔只有一个位置、只有 0 或 1、默认 0 —— **不存在"未知态"**，
		// 所以"这个岔口没人决定"这条老规则对单开道岔是假警报（位置 0 时岔股侧禁止通行，那一侧的行
		// 写 -1 甚至不写）。这个用例把两件事钉住：岔股侧确实没有行；这处岔口不因此被判"清不掉"。
		final Rail throat = rail(new Position(0, 0, 0), new Position(50, 0, 0));
		final Rail straight = rail(new Position(50, 0, 0), new Position(100, 0, 0));
		final Rail diverge = rail(new Position(50, 0, 0), new Position(100, 0, 12));
		final Simulator simulator = sim("build/mmtr-dirblock-turnout-junction", throat, straight, diverge);
		final org.mtr.core.mmtr.point.MmtrTurnout turnout = simulator.mmtrTurnout(50, 0, 0);
		assertNotNull(turnout, "the degree-3 node must be recognised as one physical turnout");
		assertEquals(org.mtr.core.mmtr.point.MmtrTurnout.NORMAL, simulator.mmtrTurnoutPosition(50, 0, 0), "默认 0（正线贯通）");
		assertFalse(simulator.mmtrPointBranches.contains(50, 0, 0, turnout.branchRailHex),
			"位置 0 时岔股进向没有行（它禁止通行）—— 正是老规则会想成「没人决定」的那一条");
		assertFalse(MmtrJunctionState.isUncleared(simulator, new Position(50, 0, 0), simulator.mmtrOccupancyTrees()),
			"道岔位置本身就是决定：这处岔口不该被判成「清不掉」");
	}

	@Test
	public void theLampKeepsItsOwnLegsWhenThePointsMove() {
		/*
		 * 用户 2026-09-13 的最终裁定（原话）："这个灯的灯光向南，保护向北的**所有**股道，
		 * 那就是到 -67,-60,-167 和 -35,-60,-157 的。"
		 *
		 * <p>也就是说：灯守的是**它自己那一侧的整组腿**，道岔扳到哪一位都不改变这一点。
		 * 这里曾经按"位置 0 守北 / 位置 1 退守根部"把禁行那条腿从灯的视野里删掉，后果有两个：
		 * ① 灯守的轨随扳道岔跳来跳去；② 人工点选绑定到"被禁行那一侧"的灯被静默改掉
		 * —— 用户报的"现在不能手动设置某盏灯守某个道了"就是它。道岔的禁行由走行的闸门负责
		 * （车停在岔前），不需要灯替它表达。</p>
		 */
		final Rail throat = rail(new Position(0, 0, 0), new Position(50, 0, 0));
		final Rail straight = rail(new Position(50, 0, 0), new Position(100, 0, 0));
		final Rail diverge = rail(new Position(50, 0, 0), new Position(100, 0, 12));
		final Simulator simulator = sim("build/mmtr-dirblock-turnout-move", throat, straight, diverge);
		final Position node = new Position(50, 0, 0);
		final org.mtr.core.mmtr.point.MmtrTurnout turnout = simulator.mmtrTurnout(50, 0, 0);
		assertNotNull(turnout, "the degree-3 node must be recognised as one physical turnout");
		final Rail far = railByHex(simulator, turnout.farRailHex);
		assertNotNull(far, "正线远端那条轨要能在轨图里找到");

		// 灯立在正线远端这条轨上、靠近节点那一头，管的方向 = 从节点沿这条轨出去（灯具的"面向"）。
		final String lamp = addLamp(simulator, far, MmtrSectionGeometry.arcOfNode(far, node), (float) headingAngle(far, node));
		final MmtrSectionService service = new MmtrSectionService(simulator);
		assertEquals(0, simulator.mmtrTurnoutPosition(50, 0, 0), "默认 0：正线贯通");

		final ObjectArrayList<String> atNormal = protectedRailHexes(service, lamp);
		assertFalse(atNormal.isEmpty(), "灯至少要守一条腿（不能变成不参与闭塞的死灯）");
		/*
		 * 灯立在**节点方块上**、朝东：引擎把这一盏归给东向的那条腿 —— 这个夹具里就是**岔股**
		 * （`branchRailHex`）。这是引擎对"同一方块上有三根轨时这盏灯属于哪一根"的归属判定，与道岔
		 * 把哪一侧叫"正线"无关。
		 *
		 * <p>注意这条断言从前写的是 {@code farRailHex}：那时老判据把岔股当成了 far（模型倒置，
		 * 见 {@code MmtrTurnoutResolveTests}），所以"守岔股"看起来像"守 far"。判据修正后 far = 真·正线，
		 * 归属没变，变的是名字。</p>
		 */
		assertTrue(atNormal.contains(turnout.branchRailHex), "灯守它 FACING 那一侧的腿（" + shortHexes(atNormal) + "）");

		assertTrue(simulator.mmtrSetTurnoutPosition(50, 0, 0, org.mtr.core.mmtr.point.MmtrTurnout.REVERSE), "能扳到位置 1");
		assertEquals(1, simulator.mmtrTurnoutPosition(50, 0, 0), "位置 1：岔股开放");
		final ObjectArrayList<String> atReverse = protectedRailHexes(service, lamp);
		// 这一条就是用户报的那个 bug：扳道岔把灯守的腿换掉了（连带把人工绑定也改掉）
		assertEquals(atNormal, atReverse, "扳道岔**不改变**这盏灯守的腿（" + shortHexes(atNormal) + " → " + shortHexes(atReverse) + "）");
		assertFalse(atReverse.contains(turnout.stemRailHex), "根部在灯的另一侧，扳道岔也不会把它塞给这盏灯（" + shortHexes(atReverse) + "）");

		// 收尾：道岔位置是落盘的（savePath 下的存档），扳回 0 才不会污染下一次运行
		// （实测过：不清就是第二跑从位置 1 开始）。
		assertTrue(simulator.mmtrSetTurnoutPosition(50, 0, 0, org.mtr.core.mmtr.point.MmtrTurnout.NORMAL), "扳回位置 0");
	}

	/**
	 * 用户 2026-09-13 现场问的那一盏（dev 世界 {@code -70,-59,-167}）：**进路被道岔切断的灯必须是红**。
	 *
	 * <p>那盏灯立在正线北端、朝南（管 28 m 那段正线，列车朝岔口开）。道岔设 1 时北侧正线被切断 ——
	 * 车即使被放行也只能开到岔前停住，所以这条进路根本走不出去。原实现看不到这一层，只在闭塞链上
	 * 找到"岔口另一侧那段有车"，于是给**单黄**（看着像"前方有车"），用户的判词是"正常不应该是红的吗"。</p>
	 */
	@Test
	public void aRouteCutByThePointsReadsRedNotCaution() {
		final Rail throat = rail(new Position(0, 0, 0), new Position(50, 0, 0));
		final Rail straight = rail(new Position(50, 0, 0), new Position(100, 0, 0));
		final Rail diverge = rail(new Position(50, 0, 0), new Position(100, 0, 12));
		final Simulator simulator = sim("build/mmtr-dirblock-turnout-cut", throat, straight, diverge);
		final Position node = new Position(50, 0, 0);
		final org.mtr.core.mmtr.point.MmtrTurnout turnout = simulator.mmtrTurnout(50, 0, 0);
		assertNotNull(turnout, "the degree-3 node must be recognised as one physical turnout");
		final Rail far = railByHex(simulator, turnout.farRailHex);
		assertNotNull(far, "正线远端那条轨要能在轨图里找到");

		// 灯立在正线**远端那一头**、朝回岔口（管的是"列车朝岔口开"这条进路）
		final double towardsNode = headingAngle(far, node) + 180;
		final String lamp = addLamp(simulator, far, far.railMath.getLength(), (float) towardsNode);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		// 位置是**落盘**的（savePath 下的存档）：先显式归零，第二次跑才不会带着上一跑的位置
		assertTrue(simulator.mmtrSetTurnoutPosition(50, 0, 0, org.mtr.core.mmtr.point.MmtrTurnout.NORMAL), "先归零");
		assertEquals(0, simulator.mmtrTurnoutPosition(50, 0, 0), "位置 0：正线贯通");
		final var straightThrough = service.lampAspectNames(null, key -> false);
		assertEquals("GREEN", straightThrough.get(lamp), "位置 0：这条进路是贯通正线，线路空闲即绿");

		assertTrue(simulator.mmtrSetTurnoutPosition(50, 0, 0, org.mtr.core.mmtr.point.MmtrTurnout.REVERSE), "扳到位置 1");
		final var cut = service.lampAspectNames(null, key -> false);
		assertEquals("RED", cut.get(lamp), "位置 1：正线被切断 ⇒ 这条进路走不出去 ⇒ 红（不是「前方有车」的黄）");
		assertTrue(service.sectionOfSignal(lamp).blockedAtArrival, "这一段的结束原因应当是「撞在道岔禁行侧」");

		assertTrue(simulator.mmtrSetTurnoutPosition(50, 0, 0, org.mtr.core.mmtr.point.MmtrTurnout.NORMAL), "扳回位置 0");
	}

	/**
	 * **浅渡线：灯守着岔股那一条腿，但不因为它撞墙而变红**（用户 2026-09-14 现场报的）。
	 *
	 * <p>实测现场：{@code -170,-60,-253} 旁朝北的灯 {@code -168,-60,-253} 本该是绿（位置 0 =
	 * 正线贯通、直通那条空闲），却是红的。原因：区间在 {@code -170,-60,-289} 拐上了那条 9.5° 斜线
	 * （位置 0 时它**禁止通行**），走到斜线另一端又撞上 {@code -176,-60,-253} 的禁行侧，
	 * 整段被判成"没有进路" ⇒ 红。这个副作用是"浅岔口被认出来"之后才出现的：那两个节点以前没有
	 * 物理模型，也就没有禁行侧这道墙。</p>
	 *
	 * <p>修法保留用户 2026-09-10 的"一盏灯守整个咽喉"（岔股上可能停着扳岔之前就进来的车，
	 * 那条腿照样要守住），但区分"这次运行**走得到**的腿"与"走不到的腿"：后者的墙**不许**把整段
	 * 判成没有进路。本用例把这三点钉住：岔股仍在区间里、区间没被判断路、灯是绿。</p>
	 */
	@Test
	public void theBranchOfAShallowCrossoverIsGuardedWithoutTurningTheLampRed() {
		final Position a = new Position(0, 0, 0);
		final Position b = new Position(-6, 0, 36);
		final Rail farA = rail(a, new Position(0, 0, 36));
		final Rail stemA = rail(a, new Position(0, 0, -17));
		final Rail diagonal = rail(a, b);
		final Rail farB = rail(b, new Position(-6, 0, 67));
		final Rail stemB = rail(b, new Position(-6, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-shallow-crossover", farA, stemA, diagonal, farB, stemB);
		assertNotNull(simulator.mmtrTurnout(a.getX(), a.getY(), a.getZ()), "渡线的一端必须是一处道岔");
		assertNotNull(simulator.mmtrTurnout(b.getX(), b.getY(), b.getZ()), "另一端也是");

		// 灯立在直通轨上、朝着岔口，管的是"顺着这条正线开进岔口"这段
		final String lamp = addLamp(simulator, farA, 5.0, (float) ((headingAngle(farA, a) + 180) % 360));
		final MmtrSectionService service = new MmtrSectionService(simulator);
		final MmtrSectionService.Section section = service.sectionOfSignal(lamp);
		assertNotNull(section);

		assertTrue(protectedRailHexes(service, lamp).contains(diagonal.getHexId()),
			"岔股那条腿仍在区间里（一盏灯守整个咽喉：那条轨上可能停着扳岔之前就进来的车）");
		assertFalse(section.blockedAtArrival,
			"但它是**这次运行走不到**的那条腿：它撞墙不许把整段判成没有进路");
		assertEquals("GREEN", service.lampAspectNames(null, key -> false).get(lamp),
			"位置 0 = 正线贯通、直通那条空闲 ⇒ 绿（这正是现场那盏灯该有的显示）");

		// 反过来：位置 1 时灯自己那条轨被切断 ⇒ 这条进路真的走不出去 ⇒ 红（用户 2026-09-13 的规格）
		assertTrue(simulator.mmtrSetTurnoutPosition(a.getX(), a.getY(), a.getZ(), org.mtr.core.mmtr.point.MmtrTurnout.REVERSE), "扳到位置 1");
		final MmtrSectionService flipped = new MmtrSectionService(simulator);
		final MmtrSectionService.Section flippedSection = flipped.sectionOfSignal(lamp);
		assertNotNull(flippedSection);
		assertTrue(flippedSection.blockedAtArrival, "位置 1 时灯自己那条轨禁止通行 ⇒ 区间撞在禁行侧");
		assertEquals("RED", flipped.lampAspectNames(null, key -> false).get(lamp), "这条进路走不出去 ⇒ 红");

		assertTrue(simulator.mmtrSetTurnoutPosition(a.getX(), a.getY(), a.getZ(), org.mtr.core.mmtr.point.MmtrTurnout.NORMAL), "扳回位置 0");
	}

		/**
	 * **走不到的那条腿的入口灯不许当出口灯**（用户 2026-09-14 追出来的第二层问题）。
	 *
	 * <p>现场：`-152,-60,-122` 那盏灯的单黄来自 100 m 外**另一条线**上股道里的停车。原因是区间在
	 * 咽喉里拐上了按当前道岔位置**走不到**的腿（`-170,-60,-161` 在位置 1，正线远端那一侧是切断的），
	 * 并把那条腿的入口灯当成了自己的出口 ⇒ 链顺着它接到了别的线上。</p>
	 *
	 * <p>规则：走不到的腿照旧**守住**（span 覆盖、占用仍算保守），但它的入口灯不进 `exitSignalKeys`
	 * —— 链只沿这次运行走得出去的腿接。老式无模型咽喉不受影响（那里每条腿都算走得到，
	 * 用户 2026-09-10 的裁定照旧）。</p>
	 */
	@Test
	public void anUnreachableLegDoesNotContributeAnExitLamp() {
		final Position a = new Position(0, 0, 0);
		final Position b = new Position(-6, 0, 36);
		final Rail farA = rail(a, new Position(0, 0, 36));
		final Rail stemA = rail(a, new Position(0, 0, -17));
		final Rail diagonal = rail(a, b);
		final Rail farB = rail(b, new Position(-6, 0, 67));
		final Rail stemB = rail(b, new Position(-6, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-unreachable-leg", farA, stemA, diagonal, farB, stemB);
		assertEquals(0, simulator.mmtrTurnoutPosition(a.getX(), a.getY(), a.getZ()), "位置 0：正线贯通，岔股禁止通行");

		/*
		 * 两条腿各自的"入口灯"：走行从 A 拐进哪一条腿，就会停在那条腿的入口灯上。
		 * 灯面方向 = 该腿的走行方向（`guardedHeadings` 的语义），所以这里**朝腿的远端**（不加 180）。
		 */
		final String lampThrough = addLamp(simulator, stemA, 5.0, (float) headingAngle(stemA, a));
		final String lampBranch = addLamp(simulator, diagonal, 5.0, (float) headingAngle(diagonal, a));

		// 灯立在正线远端上朝岔口：它的区间要走 farA → A → 两条腿
		final String lamp = addLamp(simulator, farA, 5.0, (float) ((headingAngle(farA, a) + 180) % 360));
		final MmtrSectionService service = new MmtrSectionService(simulator);
		final MmtrSectionService.Section section = service.sectionOfSignal(lamp);
		assertNotNull(section);

		assertTrue(section.exitSignalKeys.contains(lampThrough),
			"走得到的那条腿（正线贯通侧）的入口灯要收：" + section.exitSignalKeys);
		assertFalse(section.exitSignalKeys.contains(lampBranch),
			"走不到的那条腿（岔股，位置 0 禁止通行）的入口灯**不收** —— 否则链会接到别的线上：" + section.exitSignalKeys);
	}

	/** 这盏灯当前保护的轨（去重后的 hex 列表）。 */
	private static ObjectArrayList<String> protectedRailHexes(MmtrSectionService service, String lamp) {
		final ObjectArrayList<String> out = new ObjectArrayList<>();
		final MmtrSectionService.Section section = service.sectionOfSignal(lamp);
		if (section != null) {
			for (final MmtrSectionService.RailSpan span : section.spans) {
				if (!out.contains(span.railHex)) {
					out.add(span.railHex);
				}
			}
		}
		return out;
	}

	/** 一列 hex 的前 8 位（断言消息里用，全 hex 太长看不清）。 */
	private static String shortHexes(ObjectArrayList<String> hexes) {
		final StringBuilder out = new StringBuilder();
		for (final String hex : hexes) {
			if (out.length() > 0) {
				out.append(", ");
			}
			out.append(hex.length() <= 8 ? hex : hex.substring(0, 8) + "…");
		}
		return out.toString();
	}

	/** 轨图里按 hex 找轨（测试夹具用）。 */
	private static Rail railByHex(Simulator simulator, String hex) {
		for (final Rail rail : simulator.rails) {
			if (rail.getHexId().equals(hex)) {
				return rail;
			}
		}
		return null;
	}

	/** 从 {@code node} 沿 {@code rail} 出去的方向对应的**信号角**（角语义见 headingOf：(x,z) = (-sin a, cos a)）。 */
	private static double headingAngle(Rail rail, Position node) {
		final Position[] ends = rail.mmtrOrderedPositions();
		final Position other = ends[0].equals(node) ? ends[1] : ends[0];
		final double hx = other.getX() - node.getX();
		final double hz = other.getZ() - node.getZ();
		return Math.toDegrees(Math.atan2(-hx, hz));
	}

	// ---------------------------------------------------------------- S6: 水闸区间 (the layer the map draws)

	/** Register a BOUND lamp on {@code rail} at {@code arcM} facing {@code angle}, bound to {@code target}. */
	private static String addBoundLamp(Simulator simulator, Rail rail, double arcM, float angle, Rail target) {
		final int[] coords = blockCoordsAt(rail, arcM);
		simulator.mmtrSignals.put(coords[0], coords[1], coords[2], angle, 4, "BOUND", target.getHexId());
		return MmtrSignalRegistry.key(coords[0], coords[1], coords[2]);
	}

	/** 某盏灯开的那个区间（一灯多腿时取它守的第一条腿）。 */
	private static MmtrSectionService.Section sectionOfLamp(MmtrSectionService service, String lamp) {
		return service.sectionOfSignal(lamp);
	}

	/**
	 * 在 {@code (rail, arc)} 处**面朝 {@code (headingX, headingZ)} 行进**时所在的那个区间，或 null。
	 *
	 * <p>取代旧的 `blockAt`：那时问的是"这一点归哪个水闸区间"（**单值**）。现在归属是**按方向**的 ——
	 * 同一个点南行和北行各属一个区间，所以问的时候必须带上方向。</p>
	 */
	private static MmtrSectionService.Section sectionAt(
		MmtrSectionService service, String railHex, double arcM, double headingX, double headingZ) {
		return service.sectionAt(railHex, arcM, headingX, headingZ);
	}

	/**
	 * 区间只由灯划定（用户 2026-09-15 裁定 ①「只有灯产生边界」）：区间从面朝它的那盏灯开始，
	 * **跨过轨的接头**走到下一盏面朝同方向的灯。
	 *
	 * <p>三根轨、两盏同向的灯 ⇒ 恰好两个区间，而不是"一根轨一个区间"。</p>
	 */
	@Test
	public void blocksRunLampToLampAndIgnoreRailBoundaries() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Rail r3 = rail(new Position(200, 0, 0), new Position(300, 0, 0));
		final Simulator simulator = sim("build/mmtr-gate-lamp-to-lamp", r1, r2, r3);
		// 东行链：两盏灯都朝东（角 270）→ L1 站在节点 0 管 r1+r2；L2 站在节点 200 管 r3。
		final String first = addLamp(simulator, r1, 0, EAST);
		final String second = addLamp(simulator, r3, 0, EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		assertEquals(2, service.sectionCount(), "one section per lamp - the rail boundary between r1/r2 is NOT a boundary here");

		final MmtrSectionService.Section a = sectionOfLamp(service, first);
		final MmtrSectionService.Section b = sectionOfLamp(service, second);
		assertNotNull(a, "the first lamp opens a section");
		assertNotNull(b, "the second lamp opens the next one");
		assertEquals(2, a.spans.size(), "the first section covers r1 AND r2: it crosses the node at z=100");
		assertEquals(200, a.lengthM(), 1.5);
		assertEquals(second, a.exitSignalKey, "it closes on the next lamp, it does not run out");
		assertEquals(r1.getHexId(), a.spans.get(0).railHex);
		assertEquals(r2.getHexId(), a.spans.get(1).railHex);

		assertEquals(1, b.spans.size(), "the second section is the last rail");
		assertEquals(r3.getHexId(), b.spans.get(0).railHex);
		assertNull(b.exitSignalKey, "nothing closes it: the walk ran to the end of the line");

		// 本层的法则：**节点不切分**。车走在第一段区间中途，无论在 r1 还是跨过接头到了 r2，都还在同一个区间里。
		assertEquals(a, sectionAt(service, r1.getHexId(), 50, 1, 0), "on r1 -> section a");
		assertEquals(a, sectionAt(service, r2.getHexId(), 50, 1, 0), "across the node on r2 -> still section a");
		assertEquals(b, sectionAt(service, r3.getHexId(), 50, 1, 0), "past the second lamp -> section b");
	}

	/**
	 * 没有灯照到的轨**不属于任何区间**（用户 2026-09-15 裁定 ①「只有灯产生边界」）。
	 *
	 * <p>旧的水闸区间层给这种轨**自己编一个区间**（{@code 无灯#轨@弧}）。那一层已删除：区间是"一盏灯
	 * 开的那段路"，没有灯就没有区间。这里钉住这条新法则，免得"无灯轨自动成段"又悄悄长回来。</p>
	 */
	@Test
	public void aRailNoLampReachesBelongsToNoSection() {
		final Rail guarded = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		// The siding stands well clear of the guarded rail so the lamp cannot bind to it: this test is
		// about the section layer, not about the 3 m bind tolerance (notes/105 §3.1).
		final Rail siding = rail(new Position(500, 0, 500), new Position(500, 0, 600));		final Simulator simulator = sim("build/mmtr-gate-no-lamp", guarded, siding);
		final String guardedLamp = addLamp(simulator, guarded, 0, EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		assertEquals(1, service.sectionCount(), "世界上两个区间：有灯的那根轨一个，没有灯的那根零个");
		assertNotNull(sectionOfLamp(service, guardedLamp), "有灯的那根轨的区间照旧");
		// **没有灯的轨不属于任何区间** —— 它既不是别人的一段，也不会"自己成一段"。
		assertNull(sectionAt(service, siding.getHexId(), 25, 1, 0), "无灯轨向东走：没有区间");
		assertNull(sectionAt(service, siding.getHexId(), 25, -1, 0), "无灯轨向西走：也没有区间");

		// 给那条孤立轨西端**朝东**加一盏灯：它现在自己开一个区间，从灯所在处沿轨走到东端。
		final String sidingLamp = addLamp(simulator, siding, 0, EAST);
		final MmtrSectionService withLamp = new MmtrSectionService(simulator);
		final MmtrSectionService.Section sidingSection = sectionOfLamp(withLamp, sidingLamp);
		assertNotNull(sidingSection, "灯开着它自己那段区间");
		assertEquals(siding.getHexId(), sidingSection.spans.get(0).railHex);
		assertEquals(100, sidingSection.lengthM(), 1.5, "整根轨，不是残段");
		assertFalse(guarded.getHexId().equals(sidingSection.spans.get(0).railHex), "它守的是自己那条轨");
	}

	/**
	 * **走行不许掉头**（notes/159）：区间是"某方向的一段路"，走到线路尽头（方向反过来）就结束。
	 *
	 * <p>这条钉的是那个 628 m 巨块的真因：区间从一条走廊一路走到底，在尽头的 U 弯处**跟着钢轨的物理
	 * 连接转了过去**，于是沿对面走廊往回走，把对手方向的整条走廊收进了自己的区间。</p>
	 *
	 * <p>夹具是一个"发夹"：南行段 (0,0,0)→(0,0,100)，10 m 的连接段横过去，再一条回程段
	 * (10,0,100)→(10,0,0) **朝北**。灯站在南行段北端朝南 ⇒ 走行向南 100 m 到 (0,0,100)，
	 * 连接段（朝东，与本方向垂直）**必须放行**，然后面对朝北的回程段 —— 那与基准方向（南）相反
	 * ⇒ **到此为止**。</p>
	 *
	 * <p>红证：把 {@code MMTR_REVERSAL_DOT} 那条判据去掉（或改成永不触发），这个区间就会继续吃下回程段，
	 * 长度变成 210 m、段数变成 3 —— 正是巨块的成因。</p>
	 */
	@Test
	public void theWalkNeverTurnsBackDownTheLine() {
		final Rail southbound = rail(new Position(0, 0, 0), new Position(0, 0, 100));
		final Rail connector = rail(new Position(0, 0, 100), new Position(10, 0, 100));
		final Rail northbound = rail(new Position(10, 0, 100), new Position(10, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-reversal", southbound, connector, northbound);
		// 灯站在南行段北端、朝南（角 0 = +z = 南）：它开的区间朝南走
		final String lamp = addLamp(simulator, southbound, 0, SOUTH);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final MmtrSectionService.Section section = service.sectionOfSignal(lamp);
		assertNotNull(section, "朝南的灯开出朝南的区间");
		assertEquals(2, section.spans.size(),
			"南行段 + 那 10 m 连接段（垂直，属正常行车）；**回程段不许收进来**");
		assertEquals(110, section.lengthM(), 2.0, "长度到此为止，不是 210 m");
		assertTrue(section.endsAtDeadEnd, "结束原因是走到尽头（方向反了），不是因为撞上另一盏灯");

		final MmtrSectionService.RailSpan last = section.spans.get(section.spans.size() - 1);
		assertFalse(last.railHex.equals(northbound.getHexId()), "回程段不在本区间里");
		assertEquals(section, service.sectionAt(southbound.getHexId(), 50, 0, 1), "南行段上朝南走落在本区间");
		/*
		 * notes/166 R4 按新语义改写：**没有任何信号灯的连通块整块是一个大区间**（用户 2026-09-15 裁定），
		 * 于是"那一侧还没立灯"的回程段不再是"没有区间"，而是被补出来的 `无灯#…·逆` ——
		 * 它上面朝北走**有**一段行车区间（这正是那张裁定要的效果：那段路也得有占用与停车的单位）。
		 * 原来这里断言 null（notes/157 的"无灯轨不属于任何区间"），那条已被本轮反转。
		 */
		final MmtrSectionService.Section northboundSection = service.sectionAt(northbound.getHexId(), 50, 0, -1);
		assertNotNull(northboundSection, "回程段属于补出来的无灯大区间（朝北那一段）");
		assertEquals("", northboundSection.entrySignalKey, "它不属于任何一盏灯（没有入口灯）");
		assertTrue(northboundSection.id.startsWith("无灯#"), "它是补出来的无灯大区间：" + northboundSection.id);
	}

	/**
	 * 灯只守它**面朝**的那一侧（用户："反向没放灯啊"）。同一根 200 m 轨上两盏朝向相反的灯 ⇒
	 * 两个区间，车**已经走过**的那一段属于它身后那盏灯。
	 */
	@Test
	public void aLampGuardsTheSideItFaces() {
		// 200 m, not 100: two lamps 50 m apart would land on the same block coordinate and one registry
		// entry would overwrite the other, which is a test-geometry mistake, not a model one.
		final Rail line = rail(new Position(0, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = sim("build/mmtr-gate-facing", line);
		// Mid-rail facing east: it protects the stretch ahead of it.
		final String eastLamp = addBoundLamp(simulator, line, 50, EAST, line);
		// At the far end facing west: it protects the stretch it faces, up to the east-facing head.
		final String westLamp = addBoundLamp(simulator, line, 150, WEST, line);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final MmtrSectionService.Section eastSection = sectionOfLamp(service, eastLamp);
		final MmtrSectionService.Section westSection = sectionOfLamp(service, westLamp);
		assertNotNull(eastSection, "the east-facing lamp opens a section");
		assertNotNull(westSection, "the west-facing lamp opens the one it faces");

		assertEquals(50, eastSection.spans.get(0).arcFromM, 0.5, "the east-facing lamp protects only what is ahead of it");
		assertEquals(150, eastSection.lengthM(), 1.0, "which is everything east of it: no other head faces east");
		assertEquals(0, westSection.spans.get(0).arcFromM, 0.5, "the west-facing lamp protects the stretch behind it");
		/*
		 * 西行区间的长度**只到 150**，也就是它止步于**自己所在的位置**，而不是"走到 50 m 那盏东行灯为止"。
		 *
		 * <p>这与 notes/104 §3.3 那条规矩一致：**背向本方向的灯不切断走行**。从 150 m 往西走，遇到
		 * 50 m 处那盏朝东的灯时，那盏灯守的是**东行**区间、对着反方向，所以西行走行不理它 —— 它一路
		 * 走到线路西端（弧 0）才收口；而它自己的入口本来就落在 150 m，于是这一段只有 0..150。
		 * 反过来说：这条轨 West 侧的区间长度不代表"东行区间从哪儿开始"，两件事各自由各方向的灯决定。</p>
		 */
		assertEquals(150, westSection.lengthM(), 1.0, "back-facing lamps do not cut the walk: it runs to the line's end");
		assertFalse(eastSection.id.equals(westSection.id), "两个方向的区间是两个对象，不是一个");
		// 同一根轨、两个区间：这就是"有向"在显示上的含义，也是**一个点归属不是单值**的由来。
		assertEquals(2, service.sectionCount());
		// 带方向地问，答案就唯一了：向东走落在东行区间，向西走落在西行区间。
		assertEquals(eastSection, sectionAt(service, line.getHexId(), 100, 1, 0), "eastbound -> the east-facing section");
		assertEquals(westSection, sectionAt(service, line.getHexId(), 100, -1, 0), "westbound -> the west-facing section");
	}

	/**
	 * 区间层必须是**每个方向各自**的一条干净划分：**同一个方向上**，轨的每一米恰好属于一个区间。
	 *
	 * <p>旧判据是"每一点恰好属于一个区间"（单值）。双向线路上那一条不可能成立、也不该成立：
	 * 同一段轨上南行一个区间、北行一个区间，重叠是**正确**的（现场实测 96 根被覆盖的轨里 62 根如此）。
	 * 真正的不变量是按方向的那一条 —— 所以这里把每个区间的方向读出来，再逐方向数归属。</p>
	 */
	@Test
	public void theSectionsDivideEveryRailWithoutGapsOrOverlapsPerDirection() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Rail branch = rail(new Position(100, 0, 0), new Position(200, 0, 100));
		final Rail siding = rail(new Position(0, 0, 400), new Position(0, 0, 500));
		final Simulator simulator = sim("build/mmtr-gate-partition", r1, r2, branch, siding);
		addLamp(simulator, r1, 0, EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final ObjectArrayList<MmtrSectionService.Section> sections = new ObjectArrayList<>();
		service.allSections().values().forEach(sections::addAll);
		final ObjectArrayList<Rail> rails = ObjectArrayList.of(r1, r2, branch, siding);

		for (final Rail rail : rails) {
			final double length = rail.railMath.getLength();
			for (final MmtrSectionService.Section section : sections) {
				for (final MmtrSectionService.RailSpan span : section.spans) {
					if (span.railHex.equals(rail.getHexId())) {
						assertTrue(span.arcFromM >= -1e-6 && span.arcToM <= length + 1e-6,
							"a span never runs off its rail: " + span);
					}
				}
			}
			// 每一米：本方向的区间必须恰好一个（0 = 出现没人管的天窗，2+ = 同方向重叠的缺陷）
			for (double arc = 0.05; arc < length; arc += 5) {
				for (final double[] heading : new double[][]{{1, 0}, {-1, 0}}) {
					int owners = 0;
					for (final MmtrSectionService.Section section : sections) {
						for (final MmtrSectionService.RailSpan span : section.spans) {
							if (span.railHex.equals(rail.getHexId())
								&& arc >= span.arcFromM - 1e-6 && arc <= span.arcToM + 1e-6
								&& span.matchesHeading(heading[0], heading[1])) {
								owners++;
							}
						}
					}
					assertTrue(owners <= 1, "轨 " + rail.getHexId() + " 的 " + Math.round(arc)
						+ " m 处在方向 (" + heading[0] + "," + heading[1] + ") 上有 " + owners
						+ " 个区间：同一方向不许重叠");
				}
			}
		}
	}

	/**
	 * 游戏端的绑定工具（铲子）把灯登记到节点上时，必须解出**区间模型会守的那条轨**（notes/111）：
	 * 它原来自己重写了一套"朝向 + 90°"的数学，于是绑到了**横着**的那条轨上 —— 那正是
	 * {@code -163,-60,-189} 那盏哑绑定灯（notes/105 §3.1）的成因。
	 *
	 * <p>判据是"灯管它 **FACING** 那一侧"（2026-09-13 用户按实机灯位纠正：{@code -10,-59,-160} 角 90
	 * = FACING 西 → 管西边那条；锚点角 0 = FACING 南 → 管南边那段），所以朝东的灯绑的是它**东边**那条轨。
	 * 这里特意多放一条**南北向**的轨：90° 的错绑会落到它上面，只有两条轨的旧夹具抓不到这种错。</p>
	 */
	@Test
	public void theBindToolChoosesTheRailTheSectionModelWouldProtect() {
		final Rail westbound = rail(new Position(-50, 0, 0), new Position(0, 0, 0));
		final Rail eastbound = rail(new Position(0, 0, 0), new Position(50, 0, 0));
		final Rail across = rail(new Position(0, 0, 0), new Position(0, 0, 50));
		final Simulator simulator = sim("build/mmtr-dirblock-bind", westbound, eastbound, across);

		// 站在共享节点上朝东的灯（角 270）：管它**东边**那条，不是横着那条。
		// (The op's boolean is "the registry CHANGED", so the assertion is on the resulting BOUND target.)
		simulator.mmtrSignalBindAtNode(0, 0, 0, EAST, 4, 0, 0, 0);
		final MmtrSignalRegistry.SignalEntry registered = simulator.mmtrSignals.get(0, 0, 0);
		assertNotNull(registered, "the bind tool must register the lamp");
		assertEquals(eastbound.getHexId(), registered.target,
			"朝东（FACING 东）的灯管东边那条轨 —— 不是横着那条");
		assertEquals("BOUND", registered.mode, "a bind with a target is a covered bind");

		// The other direction of the same node binds the other rail - the two heads are independent.
		simulator.mmtrSignalBindAtNode(0, 0, 0, WEST, 4, 0, 0, 0);
		assertEquals(westbound.getHexId(), simulator.mmtrSignals.get(0, 0, 0).target,
			"朝西（FACING 西）的灯管西边那条轨");
	}

	// -------------------------------------------- S7: 木斧人工分配轨道 (live defect, 2026-09-12)

	/**
	 * 用木斧把一条轨分配给一盏灯之后，区间必须**仍然朝灯面朝的方向**走 (user: 是用木斧工具分配轨道后，
	 * 灯的颜色就有问题了).
	 *
	 * <p>人工绑定这条路径上曾经写着 {@code forward = 灯在轨的哪半段}——"列车从远端那一头进来"。
	 * 那是一句纯几何的话，把灯的朝向整个丢掉了。于是一盏朝东的灯，只要它站在轨的后半段（这里弧 150 / 200），
	 * 区间就会从轨的**西头**开始走：灯守着它背后的 150 m，而它面对的 50 m 反而无人看守。
	 * 现象就是"颜色反了"——对着库内的绿灯、对着库外的红灯。自动推断那条路径反而是对的，
	 * 所以这个 bug 只在人工分配之后才现形。</p>
	 */
	@Test
	public void aBoundRailWalksTheWayTheLampFaces() {
		final Rail line = rail(new Position(0, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-bound-rail-facing", line);
		final String lamp = addLamp(simulator, line, 150, EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		// What the wooden axe writes: an explicit binding to one rail, no node target.
		assertTrue(simulator.mmtrSignals.setBoundRails(150, 0, 0, java.util.List.of(line.getHexId())),
			"the axe's write must land in the registry");

		final MmtrSectionService.Section section = service.sectionOfSignal(lamp);
		assertNotNull(section, "a lamp with an explicit rail still has a section");

		final MmtrSectionService.RailSpan span = section.spans.get(0);
		assertEquals(line.getHexId(), span.railHex, "the bound rail is the one guarded");
		assertEquals(1.0, span.headingX, 1e-9, "the span is walked EAST - the way the lamp faces");
		assertEquals(0.0, span.headingZ, 1e-9, "and not across the track");
		assertEquals(50, section.lengthM(), 1.0, "the section is the 50 m ahead of the lamp, not the 150 m behind it");

		// The colour consequence, which is what the user actually sees.
		assertFalse(service.isOccupied(section, occupancy(line, 0, 100)),
			"a train BEHIND the lamp is not in the block it guards");
		assertTrue(service.isOccupied(section, occupancy(line, 160, 190)),
			"a train in front of the lamp is in the block it guards - this is what makes the lamp go red");
	}

	/** The same law for a westward lamp: 区间朝西，不是朝"离灯远的那一头"。 */
	@Test
	public void aBoundRailWalksTheWayTheLampFacesWest() {
		final Rail line = rail(new Position(0, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-bound-rail-facing-west", line);
		final String lamp = addLamp(simulator, line, 50, WEST);
		final MmtrSectionService service = new MmtrSectionService(simulator);
		simulator.mmtrSignals.setBoundRails(50, 0, 0, java.util.List.of(line.getHexId()));

		final MmtrSectionService.Section section = service.sectionOfSignal(lamp);
		assertNotNull(section);
		final MmtrSectionService.RailSpan westSpan = section.spans.get(0);
		assertEquals(-1.0, westSpan.headingX, 1e-9, "the span is walked WEST - the way the lamp faces");
		assertEquals(50, section.lengthM(), 1.0, "the section is the 50 m west of the lamp");
		assertTrue(service.isOccupied(section, occupancy(line, 10, 40)), "a train west of the lamp is in front of it");
		assertFalse(service.isOccupied(section, occupancy(line, 60, 190)), "a train east of the lamp is behind it");
	}

	/**
	 * 人工分配**不会**变成"换一条轨"：列出的轨就是全部，别的轨一概不算 (live defect).
	 *
	 * <p>实测复现：给 {@code -69,-60,-139}（本来守着出库那条 29 m 的轨）用 {@code --add} 重新指定同一条轨，
	 * 它的区间反而跑到旁边那条 36 m 的库内 stub 上去了——灯的颜色于是跟着换了个方向。
	 * 人工绑定的语义是"就这条"，任何把区间引到别的轨上的行为都是错的。</p>
	 */
	@Test
	public void anExplicitBindingNeverWandersOntoAnotherRail() {
		final Rail corridor = rail(new Position(0, 0, 0), new Position(200, 0, 0));
		final String lamp = MmtrSignalRegistry.key(150, 0, 0);

		// Path A (what the lamp does on its own) versus path B (what a re-assignment of the SAME rail gives).
		final Simulator auto = sim("build/mmtr-dirblock-bound-stable-auto", corridor);
		addLamp(auto, corridor, 150, EAST);
		final MmtrSectionService autoService = new MmtrSectionService(auto);
		final MmtrSectionService.Section inferred = autoService.sectionOfSignal(lamp);
		assertNotNull(inferred, "the inferred path also has to yield a section");

		final Simulator bound = sim("build/mmtr-dirblock-bound-stable-explicit", corridor);
		addLamp(bound, corridor, 150, EAST);
		bound.mmtrSignals.setBoundRails(150, 0, 0, java.util.List.of(corridor.getHexId()));
		final MmtrSectionService boundService = new MmtrSectionService(bound);
		final MmtrSectionService.Section explicit = boundService.sectionOfSignal(lamp);

		assertNotNull(explicit);
		assertEquals(inferred.entryRailHex(), explicit.entryRailHex(),
			"re-assigning the rail that was already guarded must not move the guard to a different rail");
		assertEquals(inferred.lengthM(), explicit.lengthM(), 1.0,
			"nor may it change how far the lamp looks");
	}

	/**
	 * 节点上的灯必须守住**从节点朝它面朝方向延伸出去的那条轨**，哪怕那条轨以节点为弧端
	 * (live defect, 2026-09-12: "用木斧工具分配轨道后，灯的颜色就有问题了" 的同一个根因).
	 *
	 * <p>原来的选腿把"前方余长"算成 {@code forward ? length - arc : arc} —— 那个式子的含义是"沿**弧增**
	 * 方向还剩多少"，而 {@code forward} 是**行向**，两者在节点处经常相反：一条从节点向南伸出 50 m 的轨，
	 * 节点正好是它的弧端（弧 0），于是"前方余长"被算成 0，这条**明明有 50 m 可走**的腿被整条丢掉；
	 * 灯只好去守旁边那条轨，或者干脆不参与闭塞。实测 depot {@code test1} 出库口那盏灯就是这样：
	 * 库里停着车、它守的却是别处的轨，颜色于是"看着不对"——从外面只看得见颜色，看不见"它守错了轨"。</p>
	 */
	@Test
	public void aNodeLampGuardsTheLegThatLeavesTheNodeItsOwnWay() {
		// 两条轨在 (0,0,0) 相交：主线向东 100 m，岔线从节点向南伸出 50 m。
		final Rail main = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail branch = rail(new Position(0, 0, 0), new Position(0, 0, 50));
		final Simulator simulator = sim("build/mmtr-dirblock-node-leg-outward", main, branch);
		// 灯站在节点旁、面朝南（= +z）：它守的是从节点向南延伸出去的那条岔线
		final String lamp = addLamp(simulator, branch, 50, SOUTH);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final MmtrSectionService.Section section = service.sectionOfSignal(lamp);
		assertNotNull(section, "节点旁的灯必须守到一条轨（不能因为判据算错而掉进'不参与闭塞'）");
		assertEquals(branch.getHexId(), section.entryRailHex(), "它守的是朝它面朝方向延伸出去的那条轨");
		/*
		 * 2026-09-13：区间长度从 50 变成 150 —— 岔线走到节点 (0,0,0) 后**继续走主线**。
		 *
		 * <p>这是本次"岔口多支"改动的必然结果，而且是对的：节点上没有别的界灯，列车从岔线出来确实
		 * 能开上主线，按"灯到灯"这条区间就该一直开到下一架灯（或线路尽头）。原来的 50 是**误打误撞**：
		 * 那时 `forward` 用"这条腿的切线 · 进来的方向"判号，岔线与主线垂直 → 点积 0 → 被判成
		 * "从远端进来"，于是走行原地递归后停住，区间正好只剩岔线。要点（它守的是岔线、岔线上有车就变红）
		 * 两条断言都还在。</p>
		 */
		assertEquals(150, section.lengthM(), 1.0,
			"岔线 50 m + 过节点后继续走的主线 100 m；'前方余长'仍必须是这条腿真实剩下的长度（见上）");
		assertTrue(service.isOccupied(section, occupancy(branch, 10, 40)),
			"岔线上停着车，这盏灯必须是红的");
	}

	// -------------------------------------------- S8: 用户世界的基准锚点 (2026-09-13)

	/**
	 * **基准锚点**：出库口那盏灯（用户 2026-09-13 指定的判据来源）。
	 *
	 * <p>用户原话："{@code -70,-59,-139} 这盏灯**对着 north 亮**，显示的是 depot 的区间状态（红色有车）"。
	 * 把它翻成几何：节点 {@code (-67,-139)} 上有三条轨 —— 向北的进库 stub、向南的主线、向东北的环线；
	 * 灯在节点西侧 3 格、**角 0**（{@code headingOf(0) = (0,-1)} = −z = 北），所以它守的是**向北那条
	 * 进库 stub**（车就停在那上面，因此它是红的）。</p>
	 *
	 * <p>这条测试钉住三件事，缺一条这套模型就会再歪回"对着库内的显示绿、对着库外的显示红"：</p>
	 * <ol>
	 *   <li>角 0 的朝向是 −z（北）—— 与"发光面朝北"一致；</li>
	 *   <li>灯守的是**它朝向的对面那一侧**（用户基准点：朝北的灯接管 {@code (-67,-139)→(-67,-103)}，
	 *       那段是**往南**出去的）；</li>
	 *   <li>分叉节点上**不能**用"前方余长"去把某条腿整体丢掉。</li>
	 * </ol>
	 *
	 * <p>三条腿都造出来（而不是只造两条）：只造两条时"挑错一条"恰好等于"挑中另一条"，
	 * 测试会通过而 bug 仍在 —— 实测就是这么漏过去的。</p>
	 */
	@Test
	public void theDepotThroatLampGuardsTheRailItFaces() {
		// 世界约定：**北 = z 更小**。所以朝南的进库线远端是 +z。
		final Rail depotStub = rail(new Position(0, 0, 0), new Position(0, 0, 40));
		final Rail mainLine = rail(new Position(0, 0, 0), new Position(0, 0, -30));
		final Rail loop = rail(new Position(0, 0, 0), new Position(34, 0, -20));
		final Simulator simulator = sim("build/mmtr-dirblock-depot-anchor", depotStub, mainLine, loop);
		// 灯在节点西侧 3 格、**高一格**（-3,-1,0）、角 0。
		// 高一格是照抄实测世界：MTR 信号灯方块两格高，登记的那一格常比轨道节点高一格，
		// 于是"贴着节点"的灯算出来是 3.16 格 —— 原来 3.0 格的门槛正好把它挡在节点分支之外，
		// 掉进兜底分支，"按朝向选腿"那套规则根本没被应用（实测锚点就是这么漏的）。
		simulator.mmtrSignals.put(-3, -1, 0, 0, 4, "AUTO", "");
		final MmtrSectionService service = new MmtrSectionService(simulator);
		MmtrSectionService.recordLegTraceFor(MmtrSignalRegistry.key(-3, -1, 0));
		service.sectionCount();

		final MmtrSectionService.Section section = service.sectionOfSignal(MmtrSignalRegistry.key(-3, -1, 0));
		assertNotNull(section, "节点的灯必须守到一条轨（不能因为判据算错而掉进'不参与闭塞'）"
			+ MmtrSectionService.lastLegTrace());
		/*
		 * 断言的是**这盏灯最终显示什么**，判据取自用户给的基准点：
		 * 「发光面朝北，接管的是 (-67,-139)→(-67,-103) 这段」—— 也就是**朝南**那条腿。
		 *
		 * 节点上的三条腿按世界约定摆好（北 = z 更小）：
		 *   depotStub  (0,0,0)→(0,0,+36)   朝南 ← 它管的这条
		 *   mainLine   (0,0,0)→(0,0,-30)   朝北（它发光面朝着的那侧，不归它管）
		 *   loop       (0,0,0)→(34,0,-20)  朝东北（同侧，也不归它管）
		 */
		final String key = MmtrSignalRegistry.key(-3, -1, 0);
		assertEquals("RED", service.lampAspectNames(occupancy(depotStub, 10, 30), node -> false).get(key),
			"车停在**朝南**那条腿上（用户基准点：这段才是它接管的），这盏灯必须是红的"
				+ MmtrSectionService.lastLegTrace());
		assertEquals("GREEN", service.lampAspectNames(occupancy(mainLine, 5, 20), node -> false).get(key),
			"车停在**朝北**那条腿上（那是它发光面朝着的一侧，不归它管），它应当还是绿"
				+ MmtrSectionService.lastLegTrace());
		assertEquals("GREEN", service.lampAspectNames(occupancy(loop, 5, 20), node -> false).get(key),
			"车停在东北环线上（同在它面朝的那一侧，不归它管）"
				+ MmtrSectionService.lastLegTrace());
	}

	/**
	 * 占用树的**键顺序**与 {@code mmtrOrderedPositions()} 相反时，也必须读到占用。
	 *
	 * <p>占用树是两级映射（轨的一端 → 另一端 → 足迹）。轨是无向的，所以两个端点写在前都应当等价 ——
	 * 但车辆写入时用的顺序来自它自己，与 {@code mmtrOrderedPositions()} 可以相反。</p>
	 *
	 * <p>实测那一次（2026-09-13，锚点 {@code -70,-59,-139} 那盏"游戏里红、web 上绿"的灯）：
	 * {@code query occupancy} 明明白白写着 {@code (-67,-139)→(-67,-103) 车=[…] 足迹=[10..26]}，
	 * 车就停在进库 stub 上，可那条腿的区间一直算"占用=否"。原因不是走行、不是选腿，而是**取错了键**。
	 * 既有 526 条用例全都没抓到它 —— 因为夹具 {@code occupancy(...)} 用的是同一个顺序，
	 * 于是"反向的那一半"从来没被测过。这条用例专门把它钉住。</p>
	 */
	@Test
	public void theOccupancyTreeIsReadWhenItsKeyOrderIsReversed() {
		final Rail line = rail(new Position(0, 0, 0), new Position(0, 0, -40));
		final Simulator simulator = sim("build/mmtr-dirblock-reversed-key", line);
		final String lamp = addLamp(simulator, line, 0, NORTH);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		// 与 occupancy(...) 同样的足迹，但两级键**反过来**放
		final Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> tree = new Object2ObjectAVLTreeMap<>();
		final VehiclePosition vehiclePosition = new VehiclePosition();
		vehiclePosition.addSegment(10, 30, 7);
		final Position[] ordered = line.mmtrOrderedPositions();
		final Object2ObjectAVLTreeMap<Position, VehiclePosition> inner = new Object2ObjectAVLTreeMap<>();
		inner.put(ordered[0], vehiclePosition);
		tree.put(ordered[1], inner);
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> reversed = ObjectArrayList.of(tree);

		final MmtrSectionService.Section section = service.sectionOfSignal(lamp);
		assertNotNull(section, "这盏灯必须守到一条轨");
		assertTrue(service.isOccupied(section, reversed),
			"键顺序反了也必须在树里找到这辆车的足迹 —— 找不到就会报「占用=否」，灯永远绿");
		assertEquals("RED", service.lampAspectNames(reversed, node -> false).get(lamp),
			"车就停在这盏灯守的轨上，它必须是红的");
	}

	/**
	 * **用户 2026-09-13 给的判据基准**（原话）：「{@code -70,-59,-139} 这盏灯它发光面是**朝北**的，
	 * 它接管的是 {@code -67,-139} 到 {@code -67,-103} 这段」。
	 *
	 * <p>而那段是从道岔**往南**出去的（约定：北 = z 更小；往北那条经 z=-167 到 z=-191 才是尽头）。
	 * 所以规则是 **灯管它朝向的对面那一侧** —— 与铁路惯例一致：司机迎着灯面开过来，
	 * 灯守的是他开来的那个方向。</p>
	 *
	 * <p>这条测试是**照着那个基准点**写的：朝北的灯必须守朝南的那条腿。</p>
	 */
	@Test
	public void aNorthFacingLampGuardsTheLegOppositeToIt() {
		final Rail southLeg = rail(new Position(0, 0, 0), new Position(0, 0, 40));
		final Rail northLeg = rail(new Position(0, 0, 0), new Position(0, 0, -30));
		final Rail northEastLoop = rail(new Position(0, 0, 0), new Position(34, 0, -20));
		final Simulator simulator = sim("build/mmtr-dirblock-north-facing", southLeg, northLeg, northEastLoop);
		// 灯在道岔西侧 3 格，角 0 = 发光面朝北
		final String lamp = MmtrSignalRegistry.key(-3, 0, 0);
		simulator.mmtrSignals.put(-3, 0, 0, 0, 4, "AUTO", "");
		final MmtrSectionService service = new MmtrSectionService(simulator);
		MmtrSectionService.recordLegTraceFor(lamp);

		assertEquals("RED", service.lampAspectNames(occupancy(southLeg, 5, 25), node -> false).get(lamp),
			"车停在**朝南**那条腿上（用户基准点：这段才是它接管的），朝北的灯必须是红的"
				+ MmtrSectionService.lastLegTrace());
		assertEquals("GREEN", service.lampAspectNames(occupancy(northLeg, 5, 25), node -> false).get(lamp),
			"车停在**朝北**那条腿上（那是它发光面朝着的那侧，不归它管），它应当还是绿"
				+ MmtrSectionService.lastLegTrace());
		assertEquals("GREEN", service.lampAspectNames(occupancy(northEastLoop, 5, 25), node -> false).get(lamp),
			"东北环线也在它面朝的那一侧（点积 +0.49），同样不归它管"
				+ MmtrSectionService.lastLegTrace());
	}

	/**
	 * 把"灯守对了腿、车也在那条腿上，灯却还是绿的"逼到最小：**四种情形一次试完**
	 * （灯的朝向两种 × 占用树键顺序两种），全都要红。
	 *
	 * <p>写它的原因：锚点的故障在"选腿已对、车也在守的腿上"之后**仍然**读不到占用，
	 * 而单看一条日志无法判断是"守错了腿"还是"占用取不到"。四种组合全红，就说明这两层各自独立正确。</p>
	 */
	@Test
	public void aLampReadsOccupancyOnTheLegItGuardsInEveryCombination() {
		/*
		 * 打开占用逐步账：它现在是**默认关**的（notes/172 —— 那串字符串原来是每个 span 无条件拼一次，
		 * 而 isOccupied 是引擎里最热的查询之一）。本用例把它打开，是为了断言失败时那条消息里
		 * 仍然带着"这一步为什么算占用"的逐步账。
		 */
		MmtrSectionService.setOccupancyTraceEnabled(true);
		try {
			assertEveryLegCombinationReadsItsOwnOccupancy();
		} finally {
			MmtrSectionService.setOccupancyTraceEnabled(false);
		}
	}

	private void assertEveryLegCombinationReadsItsOwnOccupancy() {
		for (final float lampAngle : new float[]{SOUTH, NORTH}) {
			for (final boolean reverseKey : new boolean[]{false, true}) {
				final String tag = "angle=" + lampAngle + " reverseKey=" + reverseKey;
				// 节点 (0,0,0)：南边一条腿（+z）、北边一条腿（−z）
				final Rail southLeg = rail(new Position(0, 0, 0), new Position(0, 0, 40));
				final Rail northLeg = rail(new Position(0, 0, 0), new Position(0, 0, -30));
				final Simulator simulator = sim("build/mmtr-dirblock-combo-" + lampAngle + "-" + reverseKey, southLeg, northLeg);
				final String lamp = MmtrSignalRegistry.key(-3, 0, 0);
				simulator.mmtrSignals.put(-3, 0, 0, lampAngle, 4, "AUTO", "");
				final MmtrSectionService service = new MmtrSectionService(simulator);

				// 灯角 0（SOUTH 常量）= 朝向 −z = 北，守南边那条；角 180 = 朝南，守北边那条
				final Rail guarded = lampAngle == SOUTH ? southLeg : northLeg;
				final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees =
					occupancyAnyKeyOrder(guarded, 5, 35, reverseKey);
				// 先钉"区间这一层"：它必须在自己守的那条腿上读到车
				final var sections = service.sectionsOfSignal(lamp);
				assertFalse(sections.isEmpty(), "[" + tag + "] 这盏灯必须至少有一段区间" + MmtrSectionService.lastLegTrace());
				boolean anyOccupied = false;
				for (final var candidate : sections) {
					anyOccupied |= service.isOccupied(candidate, trees);
				}
				assertTrue(anyOccupied, "[" + tag + "] 它守的腿上停着车，至少有一段区间必须报占用"
					+ MmtrSectionService.occupancyTrace() + MmtrSectionService.lastLegTrace());
				assertEquals("RED", service.lampAspectNames(trees, node -> false).get(lamp),
					"[" + tag + "] 车停在它守的那条腿上，必须是红的" + MmtrSectionService.lastLegTrace());
			}
		}
	}

	/** 与 {@link #occupancy} 同样的足迹，但两级键的顺序可以**反过来**放。 */
	private static ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> occupancyAnyKeyOrder(Rail rail, double fromM, double toM, boolean reverse) {
		final Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> tree = new Object2ObjectAVLTreeMap<>();
		final VehiclePosition vehiclePosition = new VehiclePosition();
		vehiclePosition.addSegment(fromM, toM, 11);
		final Position[] ordered = rail.mmtrOrderedPositions();
		final Object2ObjectAVLTreeMap<Position, VehiclePosition> inner = new Object2ObjectAVLTreeMap<>();
		if (reverse) {
			inner.put(ordered[0], vehiclePosition);
			tree.put(ordered[1], inner);
		} else {
			inner.put(ordered[1], vehiclePosition);
			tree.put(ordered[0], inner);
		}
		return ObjectArrayList.of(tree);
	}

	/**
	 * 锁住朝向的**符号**，以及"人工分配之后守到轨的哪一头"。
	 *
	 * <p>这是全模型唯一的铁律：**灯的朝向与区间走行方向同向**（司机只有正对灯面才看得见灯，
	 * 所以迎着灯开来的列车与灯朝同一个方向）。它极难自查，因为符号反了以后两支灯的颜色会
	 * **成对**互换（库内的绿、库外的红），看上去像"某一盏灯配错了"，而不像"朝向整个反了 180°"。
	 * 所以这里用两个位置把铁律钉死：同样一盏朝西的灯，站在弧的前半段与后半段，守的必须是同一侧。</p>
	 *
	 * <p>这两条断言**故意不看灯站在哪半段**：原来的实现正是拿"前半段还是后半段"当判据的，
	 * 于是同一盏朝西的灯，站在 50 处守东边、站在 150 处守西边——朝向被几何顶替，这正是
	 * "用木斧分配轨道后灯的颜色就不对了"。实测世界里的样子：depot {@code test1} 的
	 * {@code -69,-60,-139} 朝外那盏灯，人工分配之后区间跑到库里那条 36 m 的 stub 上去了，
	 * 于是对着库内显示绿、对着库外显示红。</p>
	 */
	@Test
	public void aBoundRailIsGuardedOnTheSideTheLampFaces() {
		final Rail line = rail(new Position(0, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-bound-facing-side", line);
		final String frontHalf = addLamp(simulator, line, 50, WEST);
		final String backHalf = addLamp(simulator, line, 150, WEST);
		final MmtrSectionService service = new MmtrSectionService(simulator);
		simulator.mmtrSignals.setBoundRails(50, 0, 0, java.util.List.of(line.getHexId()));
		simulator.mmtrSignals.setBoundRails(150, 0, 0, java.util.List.of(line.getHexId()));

		for (final String lamp : new String[]{frontHalf, backHalf}) {
			final MmtrSectionService.Section section = service.sectionOfSignal(lamp);
			assertNotNull(section, "灯 " + lamp + " 必须守到它面前的那段轨");
			assertEquals(-1.0, section.spans.get(0).headingX, 1e-9,
				"灯 " + lamp + " 朝西，区间必须朝西走——不管它站在轨的哪半段");
		}
		assertEquals(50, service.sectionOfSignal(frontHalf).lengthM(), 1.0, "站在 50 处，守它西边的 50 m");
		// 站在 150 处：西边 150 m 里横着弧 50 那盏**同向**的灯，区间本就该在它那里截断（灯到灯）
		assertEquals(100, service.sectionOfSignal(backHalf).lengthM(), 1.0,
			"站在 150 处朝西，区间到弧 50 的那盏灯为止（150-50=100 m），不是一路走到轨端");
		assertEquals(frontHalf, service.sectionOfSignal(backHalf).exitSignalKey,
			"截断它的就是弧 50 那盏灯——灯到灯跨轨，节点不参与");
	}

	/**
	 * 轨中段的灯：**两侧都要能绑**（2026-09-13 实测 depot 的 {@code -168,-60,-100} 掉成"不参与闭塞"）。
	 *
	 * <p>一盏站在南北向存车线旁、FACING 朝北（角 180）的中段灯，管的是它**北边**（弧减那一侧）那段轨。
	 * 原来的中段分支只认 {@code dot > 0.1}（弧增侧），这种灯一条轨都绑不上 → 悄悄退出闭塞：
	 * 网页上表现为灰色未知色，而它旁边就停着车（本该是红）。</p>
	 */
	@Test
	public void aMidRailLampCanGovernTheDecreasingArcSide() {
		final Rail line = rail(new Position(0, 0, 0), new Position(0, 0, 100));
		final Simulator simulator = sim("build/mmtr-dirblock-midrail-both-sides", line);
		// 离两端各 60/40 m：够不到节点 → 走"中段灯"那条路径；离轨 2 格（投影存在）
		final String lamp = MmtrSignalRegistry.key(-2, 1, 60);
		simulator.mmtrSignals.put(-2, 1, 60, 180, 4, "AUTO", "");
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final MmtrSectionService.Section section = service.sectionOfSignal(lamp);
		assertNotNull(section, "中段灯必须绑到轨（不能悄悄退出闭塞）");
		assertEquals(line.getHexId(), section.entryRailHex());
		assertEquals(-1.0, section.spans.get(0).headingZ, 1e-9, "管的是北边那侧 → 走行 -z");
		assertEquals(60, section.lengthM(), 1.5, "从弧 60 往北到轨端（弧 0）");
		assertTrue(service.isOccupied(section, occupancy(line, 30, 50)), "北边那段上有车 → 这盏灯是红的");
	}

	/**
	 * **斜向（45°）节点与轨**：引擎里所有方向判据都是点积/投影，没有"只认 90°"的假设。
	 *
	 * <p>用户 2026-09-13 提醒："其实还可以 45 度放轨道节点的"。这里用一条西北—东南的 45° 走廊钉住三件事：
	 * ① 斜轨上的灯按 FACING 管斜的那一侧（角 315 → FACING 东南 → 管东南那条斜轨，走行方向 (0.707, 0.707)）；
	 * ② 斜轨上有车时这盏灯读红；③ 游戏端上报的**节点朝向角**（斜向节点 = 45）能存能读
	 * （引擎目前只把它透出到 {@code mmtr-topology}，留给道岔/进路那层用）。</p>
	 */
	@Test
	public void aDiagonalNodeAndRailAreHandledLikeAnyOther() {
		final Rail nw = rail(new Position(0, 0, 0), new Position(40, 0, 40));
		final Rail se = rail(new Position(40, 0, 40), new Position(80, 0, 80));
		final Simulator simulator = sim("build/mmtr-dirblock-diagonal", nw, se);
		// 斜向节点的"贯通轴"：BlockNode.getAngle = FACING?0:90 + 22.5/45 偏移 → 45 = 东北—西南那条轴
		simulator.mmtrNodeAngleUpsert(40, 0, 40, 45);
		assertEquals(45F, simulator.mmtrNodeAngle(40, 0, 40), 1e-3, "斜向节点的朝向角要能上报/读回");

		// 灯摆在节点旁（沿走廊偏 2 格，离节点 3 格）：
		final String lamp = MmtrSignalRegistry.key(42, 1, 42);
		simulator.mmtrSignals.put(42, 1, 42, 315, 4, "AUTO", "");
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final MmtrSectionService.Section section = service.sectionOfSignal(lamp);
		assertNotNull(section, "斜向节点旁的灯必须守到一条斜轨（不能因为角度不是 90 的倍数就掉出去）");
		assertEquals(se.getHexId(), section.entryRailHex(), "角 315 = FACING 东南 → 管东南那条");
		assertEquals(1, section.spans.size(), "整条斜轨一段");
		assertEquals(0.7071, section.spans.get(0).headingX, 1e-3, "走行 = 东南（+x/+z 各 0.7071）");
		assertEquals(0.7071, section.spans.get(0).headingZ, 1e-3);
		assertTrue(section.lengthM() > 40, "斜轨 56.6 m，区间不能是零头：" + section.lengthM());
		assertTrue(service.isOccupied(section, occupancy(se, 20, 40)), "斜轨上有车 → 这盏灯读红");
	}

	/** 细分角：灯的角可以是 22.5° 的整数倍（方块状态 {@code IS_22_5}/{@code IS_45}），同样要绑得上。 */
	@Test
	public void aFineAngleLampStillBindsTheDiagonalRail() {
		final Rail nw = rail(new Position(0, 0, 0), new Position(40, 0, 40));
		final Rail se = rail(new Position(40, 0, 40), new Position(80, 0, 80));
		final Simulator simulator = sim("build/mmtr-dirblock-fine-angle", nw, se);
		final String lamp = MmtrSignalRegistry.key(42, 1, 42);
		// 角 337.5 = 315 + 22.5 → FACING 东南偏南，与斜轨夹角 22.5°，点积 0.92 仍算"管这条"
		simulator.mmtrSignals.put(42, 1, 42, 337.5F, 4, "AUTO", "");
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final MmtrSectionService.Section section = service.sectionOfSignal(lamp);
		assertNotNull(section, "22.5° 细分角的灯也要能绑上斜轨");
		assertEquals(se.getHexId(), section.entryRailHex());
		assertEquals(0.7071, section.spans.get(0).headingX, 1e-3, "走行仍是这条斜轨的方向");
	}

	/** 解除人工绑定（木斧 shift+右键）之后，灯必须回到自动推断——解绑不是"没有区间"。 */
	@Test
	public void clearingTheBoundRailsFallsBackToInference() {
		final Rail line = rail(new Position(0, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = sim("build/mmtr-dirblock-bound-clear", line);
		final String lamp = addLamp(simulator, line, 150, EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		simulator.mmtrSignals.setBoundRails(150, 0, 0, java.util.List.of(line.getHexId()));
		assertEquals("BOUND", simulator.mmtrSignals.get(150, 0, 0).mode, "an explicit binding is a covered bind");

		simulator.mmtrSignals.setBoundRails(150, 0, 0, java.util.List.of());
		final MmtrSignalRegistry.SignalEntry entry = simulator.mmtrSignals.get(150, 0, 0);
		assertTrue(entry.rails.isEmpty(), "the rail list is empty again");
		assertEquals("AUTO", entry.mode, "and the lamp is back to inference");
		assertNotNull(service.sectionOfSignal(lamp), "an unbound lamp still guards what it faces");
	}

	// ---------------------------------------------------------------- 区间叠加层（notes/291）

	/**
	 * 相隔两段必须异色：这是整个叠加层唯一要保证的事（用户：「仅需相连颜色不同即可」）。
	 *
	 * <p>做法：两根轨、两盏都朝东的灯 —— 东边那盏开出的区间与西边那盏的**首尾相接**（灯到灯），
	 * 于是它们在 {@code followingBySection} 上互为邻居。颜色若在边界处不跳变，人眼就看不见"这里换区间了"。</p>
	 */
	@Test
	public void twoSectionsThatMeetAtALampGetDifferentColours() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = sim("build/mmtr-overlay-chain", r1, r2);
		final String westLamp = addLamp(simulator, r1, 0, EAST);
		final String eastLamp = addLamp(simulator, r1, 100, EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final MmtrSectionService.Section west = service.sectionOfSignal(westLamp);
		final MmtrSectionService.Section east = service.sectionOfSignal(eastLamp);
		assertNotNull(west, "西边那盏灯开出它守的那段区间");
		assertNotNull(east, "东边那盏灯同样开出一段");
		assertTrue(service.followings(west).contains(east), "两段在灯处首尾相接（否则这条用例什么都没验到）");

		final java.util.Map<String, Integer> colors = new java.util.HashMap<>();
		for (final MmtrSectionService.OverlaySection row : service.sectionOverlay()) {
			colors.put(row.id, row.colorIndex);
		}
		assertNotEquals(colors.get(west.id), colors.get(east.id), "相接的两段必须异色 —— 边界就靠它显出来");
	}

	/**
	 * 同一根轨上叠着两段时也必须异色：它们会画在**同一条带**上（同向），撞色的话切点就看不见了。
	 *
	 * <p>做法：一根 100 m 轨，西端一盏朝东的灯（守整根、走行向东），轨中段一盏朝西的灯（守它西边那一段、
	 * 走行向西）。后者不构成前者的边界（背向），所以前者仍走满整根轨 —— 于是西半根上叠了两段。</p>
	 */
	@Test
	public void twoSectionsOverlappingOnOneRailGetDifferentColours() {
		final Rail line = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Simulator simulator = sim("build/mmtr-overlay-overlap", line);
		final String eastwardLamp = addLamp(simulator, line, 0, EAST);
		final String westwardLamp = addLamp(simulator, line, 50, WEST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final MmtrSectionService.Section eastward = service.sectionOfSignal(eastwardLamp);
		final MmtrSectionService.Section westward = service.sectionOfSignal(westwardLamp);
		assertNotNull(eastward);
		assertNotNull(westward);
		assertTrue(overlaps(eastward, westward), "两段必须真的压在同一根轨上（否则这条用例什么都没验到）");

		final java.util.Map<String, Integer> colors = new java.util.HashMap<>();
		for (final MmtrSectionService.OverlaySection row : service.sectionOverlay()) {
			colors.put(row.id, row.colorIndex);
		}
		assertNotEquals(colors.get(eastward.id), colors.get(westward.id), "同轨叠着（同一条带）的两段也必须异色");
	}

	/**
	 * 颜色必须**稳定**：世界不变时连算两次要逐段同色。
	 *
	 * <p>不稳定的后果不是"难看"，是"看着像区间变了"—— 每帧换色的图层没法用来认边界。</p>
	 */
	@Test
	public void theOverlayColoursAreStableAcrossCalls() {
		final Rail r1 = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail r2 = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = sim("build/mmtr-overlay-stable", r1, r2);
		addLamp(simulator, r1, 0, EAST);
		addLamp(simulator, r1, 100, EAST);
		final MmtrSectionService service = new MmtrSectionService(simulator);

		final java.util.Map<String, Integer> first = new java.util.HashMap<>();
		for (final MmtrSectionService.OverlaySection row : service.sectionOverlay()) {
			first.put(row.id, row.colorIndex);
			assertTrue(row.colorIndex >= 0 && row.colorIndex < MmtrSectionOverlay.COLOR_COUNT, "色号必须落在调色板内：" + row.colorIndex);
			assertTrue(row.spans.size() > 0, "一条区间至少要有一段轨（" + row.id + "）");
			for (final MmtrSectionService.RailSpan span : row.spans) {
				assertTrue(span.arcToM >= span.arcFromM, "弧窗必须是 [起, 止]");
			}
		}
		assertFalse(first.isEmpty(), "这个世界里必须有区间，否则用例是空转");

		final java.util.Map<String, Integer> second = new java.util.HashMap<>();
		for (final MmtrSectionService.OverlaySection row : service.sectionOverlay()) {
			second.put(row.id, row.colorIndex);
		}
		assertEquals(first, second, "同样的世界连算两次必须给同样的颜色");
	}

	/** 两段区间是不是压在至少同一根轨上（弧窗有重叠）。 */
	private static boolean overlaps(MmtrSectionService.Section a, MmtrSectionService.Section b) {
		for (final MmtrSectionService.RailSpan spanA : a.spans) {
			for (final MmtrSectionService.RailSpan spanB : b.spans) {
				if (spanA.railHex.equals(spanB.railHex) && spanA.arcFromM < spanB.arcToM - 1e-6 && spanB.arcFromM < spanA.arcToM - 1e-6) {
					return true;
				}
			}
		}
		return false;
	}
}
