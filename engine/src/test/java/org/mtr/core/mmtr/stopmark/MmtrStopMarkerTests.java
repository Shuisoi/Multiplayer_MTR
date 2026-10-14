package org.mtr.core.mmtr.stopmark;

import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.stopmark.MmtrStopMarkerRegistry.StopMarkerEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 停车对标登记表 + 投影的数值内容（设计 §7 判据 5）。
 *
 * <h3>为什么这些用例全都不要一个真 Simulator</h3>
 * <p>判据 5 要钉的是四件事：查表命中、缺表回退、按辆数与方向过滤、冲突取最近 —— 它们全是
 * "给定一张表、问一个问题"的纯逻辑；而判据 7 的停车数值只有一条式子
 * （{@code (牌子弧长 ± datum) / 全长}）。把它们做成需要 Simulator 才能跑的测试，
 * 结果是没人愿意跑、而且一有几何改动就先怀疑夹具。所以这里：
 * 登记表的查询/冲突/落盘用真对象直接喂数据，投影的数值用
 * {@link MmtrStopMarkerGeometry#headFractionAlong} 与折线纯函数
 * （{@link MmtrStopMarkerGeometry#closestArc}/{@link MmtrStopMarkerGeometry#pointAtArc}）验证，
 * {@code project} 本身只剩"取轨 → 取折线 → 调这几条"（各段都有单测覆盖）。</p>
 */
public final class MmtrStopMarkerTests {

	/** 设计 §11 的 TEST 站台：节点 z=800..1020，全长 220，参照点 1.25 m（驾驶室侧窗）。 */
	private static final double TEST_TOTAL_LENGTH = 220;
	private static final double TEST_DATUM_M = 1.25;

	@Test
	public void findHitsTheEntryWithTheSamePlatformCarsAndDirection() {
		final MmtrStopMarkerRegistry registry = new MmtrStopMarkerRegistry();
		final StopMarkerEntry southTwo = entry(10, 60, 928, 2, false);
		registry.markers.put(MmtrStopMarkerRegistry.key(10, 60, 928), southTwo);

		assertSame(southTwo, registry.find(7L, 2, false), "同站台 / 同辆数 / 同方向应当命中同一条");
		assertNull(registry.find(8L, 2, false), "别的站台不命中");
		assertNull(registry.find(7L, 4, false), "别的辆数不命中");
		assertNull(registry.find(7L, 2, true), "反方向不命中");
	}

	/**
	 * 缺表回退（§7 判据 2 的引擎侧一半）：查不到就该是 null，让调用方走
	 * "stopFraction = 1.0"（= 今天的行为），而不是拿到一个 0 或者随便一条。
	 */
	@Test
	public void findReturnsNullWhenNothingMatches() {
		final MmtrStopMarkerRegistry registry = new MmtrStopMarkerRegistry();
		assertNull(registry.find(7L, 2, false), "空表不命中");
		assertNull(registry.find(7L, 10, true), "空表不命中");
		assertNull(registry.get(1, 2, 3), "空表按坐标也取不到");
		assertTrue(registry.byPlatform(7L).isEmpty(), "空表按站台列出应为空");
	}

	/** 辆数过滤：同一站台同一方向、只有辆数不同时，两块牌子各归各的（不能串）。 */
	@Test
	public void carsFilterKeepsDifferentConsistLengthsApart() {
		final MmtrStopMarkerRegistry registry = new MmtrStopMarkerRegistry();
		final StopMarkerEntry two = entry(10, 60, 928, 2, false);
		final StopMarkerEntry ten = entry(10, 60, 1000, 10, false);
		registry.markers.put(MmtrStopMarkerRegistry.key(two.x, two.y, two.z), two);
		registry.markers.put(MmtrStopMarkerRegistry.key(ten.x, ten.y, ten.z), ten);

		assertSame(two, registry.find(7L, 2, false), "2 辆应当拿到 2 辆那块牌");
		assertSame(ten, registry.find(7L, 10, false), "10 辆应当拿到 10 辆那块牌");
		assertNull(registry.find(7L, 6, false), "没有 6 辆的牌子就是缺表");
		assertEquals(2, registry.byPlatform(7L).size(), "两条都在同一站台上");
	}

	/** 方向过滤：同一辆数两个方向各一块，各命中各自的（§3：方向不能省）。 */
	@Test
	public void directionFilterKeepsBothDirectionsApart() {
		final MmtrStopMarkerRegistry registry = new MmtrStopMarkerRegistry();
		final StopMarkerEntry south = entry(10, 60, 928, 2, false);
		final StopMarkerEntry north = entry(10, 60, 890, 2, true);
		registry.markers.put(MmtrStopMarkerRegistry.key(south.x, south.y, south.z), south);
		registry.markers.put(MmtrStopMarkerRegistry.key(north.x, north.y, north.z), north);

		assertSame(south, registry.find(7L, 2, false), "南行那块牌应当只被 towardEnd0=false 命中");
		assertSame(north, registry.find(7L, 2, true), "北行那块牌应当只被 towardEnd0=true 命中");
	}

	/**
	 * 多牌冲突取最近（§4.3 判据 5）：同一 (站台, 辆数, 方向) 有三块牌时，只有**车头停点离站台中点最近**
	 * 的那块是基准，其余算冲突。
	 *
	 * <p>站台中点在这里直接喂坐标（纯函数），因为"哪块最近"本来就只是一次水平距离比较 ——
	 * 真 Simulator 也改变不了这条规则，只会把用例变得不可跑。</p>
	 */
	@Test
	public void conflictingMarkersResolveToTheOneNearestThePlatformMidpoint() {
		final StopMarkerEntry farNorth = entry(0, 60, 820, 2, false, 820, 0);
		final StopMarkerEntry middle = entry(0, 60, 910, 2, false, 910, 0);
		final StopMarkerEntry farSouth = entry(0, 60, 1010, 2, false, 1010, 0);
		final List<StopMarkerEntry> group = new ArrayList<>();
		group.add(farNorth);
		group.add(middle);
		group.add(farSouth);

		// 站台中点 z=910 ⇒ middle 是基准
		assertSame(middle, MmtrStopMarkerRegistry.nearestToPlatformMid(group, 0, 910), "离站台中点最近的那块是基准");
		// 中点挪到南端 ⇒ 结论跟着变（正因为如此，冲突标记必须现算而不是落盘）
		assertSame(farSouth, MmtrStopMarkerRegistry.nearestToPlatformMid(group, 0, 1100), "中点变化会改变基准");
		// 中点给不出（站台已不存在）⇒ 退回第一条，而不是把整组丢掉
		assertSame(farNorth, MmtrStopMarkerRegistry.nearestToPlatformMid(group, Double.NaN, Double.NaN), "不知道中点时退回第一条");
		assertNull(MmtrStopMarkerRegistry.nearestToPlatformMid(new ArrayList<>(), 0, 0), "空组没有基准");
	}

	/** 登记表自己的查询也要按同一口径取最近：带站台中点的 find 与不带的重载必须一致地命中基准。 */
	@Test
	public void findWithMidpointPicksTheNearestOfConflictingMarkers() {
		final MmtrStopMarkerRegistry registry = new MmtrStopMarkerRegistry();
		final StopMarkerEntry north = entry(0, 60, 820, 2, false, 820, 0);
		final StopMarkerEntry south = entry(0, 60, 1010, 2, false, 1010, 0);
		registry.markers.put(MmtrStopMarkerRegistry.key(north.x, north.y, north.z), north);
		registry.markers.put(MmtrStopMarkerRegistry.key(south.x, south.y, south.z), south);

		assertSame(north, registry.find(7L, 2, false, 0, 810), "中点在北 ⇒ 北边那块");
		assertSame(south, registry.find(7L, 2, false, 0, 1000), "中点在南 ⇒ 南边那块");
		// 不带中点的重载（冻结签名）也要能命中一条，只是取哪条由 TreeMap 顺序决定
		assertNotNull(registry.find(7L, 2, false), "不带中点的重载至少要约到一条");
	}

	/**
	 * 登记/刷新语义：内容一样时 {@code upsert} 返回 false（不落盘），内容变了才 true。
	 *
	 * <p>这条直接保护"牌子自动核对每秒都在跑"这件事：如果每次核对都算改动，{@code mmtr-stop-markers.json}
	 * 会变成每秒一写的热文件。</p>
	 */
	@Test
	public void upsertOnlyReportsRealChanges() {
		final MmtrStopMarkerRegistry registry = new MmtrStopMarkerRegistry();
		final StopMarkerEntry first = entry(0, 60, 910, 2, false, 910, 0.5);
		assertTrue(registry.upsert(first), "第一次写入算改动");

		final StopMarkerEntry same = entry(0, 60, 910, 2, false, 910, 0.5);
		assertFalse(registry.upsert(same), "内容一致的再次核对不算改动");
		assertSame(first, registry.markers.get("0,60,910"), "不变动时保留原对象（引用也不该被换掉）");

		final StopMarkerEntry moved = entry(0, 60, 910, 2, false, 911, 0.51);
		assertTrue(registry.upsert(moved), "头点/比例变了才算改动");
		assertSame(moved, registry.markers.get("0,60,910"), "改动后换成新对象");
	}

	/** 删除入口：删到了 true，再删 false（游戏端扫描靠它清掉被拆掉的牌子）。 */
	@Test
	public void removeReportsWhetherAnEntryWasDeleted() {
		final MmtrStopMarkerRegistry registry = new MmtrStopMarkerRegistry();
		registry.markers.put("0,60,910", entry(0, 60, 910, 2, false));

		assertTrue(registry.remove(0, 60, 910), "删到了应当报 true");
		assertFalse(registry.remove(0, 60, 910), "再删一次应当是 false");
		assertTrue(registry.markers.isEmpty(), "删完表要空");
	}

	/** 落盘往返（判据 5 的第 6 条）：写到临时文件再读回来，字段逐条相等。 */
	@Test
	public void saveAndLoadRoundTripEveryField() throws Exception {
		final TreeMap<String, StopMarkerEntry> markers = new TreeMap<>();
		final StopMarkerEntry south = entry(10, 60, 928, 2, false, 929.025, 0.5865);
		south.cars = 2;
		south.platformId = 1234567890123L;
		south.platformHex = "PLATFORM-HEX";
		south.railHex = "RAIL-HEX";
		south.datumM = 1.25;
		south.label = "2辆";
		south.headY = 60;
		markers.put(MmtrStopMarkerRegistry.key(south.x, south.y, south.z), south);
		final StopMarkerEntry north = entry(10, 60, 890, 2, true, 888.975, 0.4044);
		north.platformId = 1234567890123L;
		north.platformHex = "PLATFORM-HEX";
		markers.put(MmtrStopMarkerRegistry.key(north.x, north.y, north.z), north);

		final Path path = Files.createTempDirectory("mmtr-stop-markers").resolve("mmtr-stop-markers.json");
		MmtrStopMarkerRegistry.save(path, markers);
		assertTrue(Files.exists(path), "save 应当真的写出文件");

		final MmtrStopMarkerRegistry loaded = MmtrStopMarkerRegistry.load(path);
		assertEquals(2, loaded.markers.size(), "读回来还是两条");
		assertEntryEquals(south, loaded.get(10, 60, 928));
		assertEntryEquals(north, loaded.get(10, 60, 890));
		// 键必须仍是坐标键：游戏端扫描用 key() 反查，键换了就"扫到也认不出"
		assertEquals("10,60,890", loaded.markers.firstKey(), "TreeMap 键 = 坐标键");
	}

	/** 读一个不存在的文件 = 空表（不是异常）：第一次开世界时没有这个文件。 */
	@Test
	public void loadOfAMissingFileIsAnEmptyRegistry() throws Exception {
		final Path path = Files.createTempDirectory("mmtr-stop-markers-empty").resolve("nope.json");
		assertTrue(MmtrStopMarkerRegistry.load(path).markers.isEmpty(), "文件不存在应当得到空表");
	}

	/**
	 * 判据 7 的第一条数值（设计 §11 的 TEST 站台自查）。
	 *
	 * <p>南行（{@code towardEnd0 = false}）牌子在 z=927.775：牌子弧长 127.775、
	 * 全长 220、datumM 1.25 ⇒ {@code (127.775 + 1.25) / 220 = 0.5865}
	 * —— 车头停点在牌子**前方**（弧长增大一侧），因为牌子记的是车头后方 1.25 m 的驾驶室侧窗。</p>
	 */
	@Test
	public void southboundTestMarkerProjectsToTheDesignedHeadFraction() {
		assertEquals(0.5865, MmtrStopMarkerGeometry.headFractionAlong(127.775, TEST_TOTAL_LENGTH, false, TEST_DATUM_M), 1e-4,
			"南行牌子弧长 127.775 ⇒ fraction 0.5865（129.025 / 220，设计表取四位小数）");
	}

	/**
	 * 判据 7 的第二条数值：北行（{@code towardEnd0 = true}）牌子在 z=890.225：牌子弧长 90.225
	 * ⇒ {@code (90.225 − 1.25) / 220 = 0.4044}。
	 *
	 * <p>两个方向一个加、一个减，正是"驾驶室在列车前端"这条几何唯一的表现（§3 的注意框）：
	 * 南行车头在北端、北行车头在南端，所以同一辆数的两块牌子对称落在站台两端。</p>
	 */
	@Test
	public void northboundTestMarkerProjectsToTheDesignedHeadFraction() {
		assertEquals(0.4044, MmtrStopMarkerGeometry.headFractionAlong(90.225, TEST_TOTAL_LENGTH, true, TEST_DATUM_M), 1e-4,
			"北行牌子弧长 90.225 ⇒ fraction 0.4044（88.975 / 220，设计表取四位小数）");
	}

	/** 车头停点落在股道之外（fraction ∉ [0,1]）⇒ NaN，调用方据此拒收（§4.3）。 */
	@Test
	public void headFractionOutsideTheRailIsRejected() {
		assertTrue(Double.isNaN(MmtrStopMarkerGeometry.headFractionAlong(0.2, TEST_TOTAL_LENGTH, true, TEST_DATUM_M)),
			"牌子贴在股道起点、又是朝 ends[0] 的方向 ⇒ 车头停点在股道之前");
		assertTrue(Double.isNaN(MmtrStopMarkerGeometry.headFractionAlong(219.5, TEST_TOTAL_LENGTH, false, TEST_DATUM_M)),
			"牌子贴在股道终点、朝 ends[1] ⇒ 车头停点越过股道终点");
		assertTrue(Double.isNaN(MmtrStopMarkerGeometry.headFractionAlong(100, 0, false, TEST_DATUM_M)),
			"轨长为 0（站台还没铺成形）⇒ 没有基准");
		assertEquals(0, MmtrStopMarkerGeometry.headFractionAlong(0, TEST_TOTAL_LENGTH, false, 0), 1e-9,
			"datumM = 0 时牌子位置就是车头停点（端点上的 0 是合法的）");
	}

	/**
	 * 折线弧长参数化：投影取的是**沿折线累计**的弧长，不是弦长、也不是"最近顶点"。
	 *
	 * <p>夹具是一条 L 形折线（先 +z 10 m、再 +x 10 m）。牌子的世界坐标在第二段的中间，
	 * 正确弧长 = 10 + 5 = 15；用两端的弦去量会得到 ~14.6，只认最近顶点会得到 10 或 20 ——
	 * 这就是"曲线站台要用弧长"这条（§10.2）在数值上的样子。</p>
	 */
	@Test
	public void projectionMeasuresArcLengthAlongThePolyline() {
		final double[] polyline = {0, 0, 0, 0, 0, 10, 10, 0, 10};
		assertEquals(20, MmtrStopMarkerGeometry.polylineLength(polyline), 1e-9, "折线全长 = 两段之和");
		assertEquals(15, MmtrStopMarkerGeometry.closestArc(polyline, 5, 10.4), 1e-9, "第二段中点 ⇒ 弧长 10 + 5");

		// 弧长 → 点：头点从同一条折线上取，于是头点与 fraction 永远自洽
		final double[] head = MmtrStopMarkerGeometry.pointAtArc(polyline, 15);
		assertEquals(5, head[0], 1e-9, "弧长 15 处的 x");
		assertEquals(10, head[2], 1e-9, "弧长 15 处的 z");
		assertEquals(10, MmtrStopMarkerGeometry.pointAtArc(polyline, 999)[0], 1e-9, "越界夹到折线末端");
	}

	/**
	 * 直线折线上的完整链条：牌子世界坐标 → 弧长 → 车头停点（设计 §11 的 z 值）。
	 *
	 * <p>折线就是那根 TEST 站台轨（z 800.5..1020.5 —— 轨中心线相对节点方块坐标有 +0.5 的平移，
	 * 见 {@code MmtrStopMarkerGeometry} 的类注释）。牌子在 z=927.775 时弧长 127.275，
	 * 南行 ⇒ fraction {@code (127.275 + 1.25) / 220}，头点回到世界 z 929.025
	 * ——正是设计表里南行 2 辆的车头停点。这一条同时钉住"投影量的是弧长"与"头点由弧长反算"。</p>
	 */
	@Test
	public void projectionOfTheTestMarkerLandsOnTheDesignedHeadPosition() {
		final double[] polyline = {-8, 60, 800.5, -8, 60, 1020.5};
		final double arc = MmtrStopMarkerGeometry.closestArc(polyline, -8, 927.775);
		assertEquals(127.275, arc, 1e-9, "牌子世界 z=927.775 落在轨中心线上弧长 127.275 处");
		final double fraction = MmtrStopMarkerGeometry.headFractionAlong(arc, MmtrStopMarkerGeometry.polylineLength(polyline), false, TEST_DATUM_M);
		assertEquals(0.5842, fraction, 1e-4, "南行 ⇒ 车头在牌子前方 1.25 m");
		final double[] head = MmtrStopMarkerGeometry.pointAtArc(polyline, arc + TEST_DATUM_M);
		assertEquals(929.025, head[2], 1e-9, "头点回到世界 z=929.025（设计 §11 的南行 2 辆）");
	}

	/** 造一条牌子：不碰任何世界数据，全部字段直接给。 */
	private static StopMarkerEntry entry(int x, int y, int z, int cars, boolean towardEnd0) {
		return entry(x, y, z, cars, towardEnd0, z + (towardEnd0 ? -1.25 : 1.25), 0.5);
	}

	private static StopMarkerEntry entry(int x, int y, int z, int cars, boolean towardEnd0, double headZ, double fraction) {
		final StopMarkerEntry entry = new StopMarkerEntry();
		entry.x = x;
		entry.y = y;
		entry.z = z;
		entry.cars = cars;
		entry.towardEnd0 = towardEnd0;
		entry.platformId = 7L;
		entry.platformHex = "PLATFORM-HEX";
		entry.railHex = "RAIL-HEX";
		entry.fraction = fraction;
		entry.datumM = TEST_DATUM_M;
		entry.label = "";
		entry.headX = x;
		entry.headY = y;
		entry.headZ = headZ;
		return entry;
	}

	private static void assertEntryEquals(StopMarkerEntry expected, StopMarkerEntry actual) {
		assertNotNull(actual, "读回来应当有这一条");
		assertEquals(expected.x, actual.x, "x");
		assertEquals(expected.y, actual.y, "y");
		assertEquals(expected.z, actual.z, "z");
		assertEquals(expected.cars, actual.cars, "cars");
		assertEquals(expected.towardEnd0, actual.towardEnd0, "towardEnd0");
		assertEquals(expected.platformId, actual.platformId, "platformId");
		assertEquals(expected.platformHex, actual.platformHex, "platformHex");
		assertEquals(expected.railHex, actual.railHex, "railHex");
		assertEquals(expected.fraction, actual.fraction, 1e-9, "fraction");
		assertEquals(expected.datumM, actual.datumM, 1e-9, "datumM");
		assertEquals(expected.label, actual.label, "label");
		assertEquals(expected.headX, actual.headX, 1e-9, "headX");
		assertEquals(expected.headY, actual.headY, 1e-9, "headY");
		assertEquals(expected.headZ, actual.headZ, 1e-9, "headZ");
	}
}
