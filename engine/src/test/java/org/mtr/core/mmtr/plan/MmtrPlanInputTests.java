package org.mtr.core.mmtr.plan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.job.MmtrCarSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 验收（{@code 任务系统-线路派生与车底交路-设计.md} §10 的 P1 三条）：
 *
 * <ol>
 *   <li><b>往返序列化逐字段一致</b> —— 输入落盘再读回来必须一模一样（"每天重启直接加载"的前提）；</li>
 *   <li><b>四条校验各自报错</b> —— 密度区间重叠 / 未覆盖运营时段 / headway&lt;=0 / 站序不闭合；</li>
 *   <li><b>车底数不足在加载时报错</b> —— {@code available < N} 不留到运行时。</li>
 * </ol>
 *
 * <p>这里只测**纯输入层**：不碰轨图、不碰世界。周转时间（{@code ring}）是 P2/P3 从轨图算的，
 * 所以第 3 条由用例显式传一个 ring（与 P3 接线后的调用形状一致）。</p>
 */
public final class MmtrPlanInputTests {

	private static final long MIN = 60_000L;
	private static final long H07 = 7 * 60 * MIN;
	private static final long H08_30 = 8 * 60 * MIN + 30 * MIN;
	private static final long H10 = 10 * 60 * MIN;

	private static MmtrLine line() {
		final MmtrLine line = new MmtrLine("L1", "1 号线");
		line.yardSidingId = 42;
		line.leadTimeMillis = 5 * MIN;
		line.terminalTreatment = MmtrLine.TerminalTreatment.CHANGE_ENDS;
		line.addStop(1001, 2001, 30_000);
		line.addStop(1002, 2002, 45_000);
		line.addStop(1003, 2003, 30_000);
		return line;
	}

	private static MmtrPattern pattern() {
		return new MmtrPattern("L1")
			.addSegment(H07, H08_30, 5 * MIN)
			.addSegment(H08_30, H10, 3 * MIN);
	}

	private static MmtrCarSpec car(String vehicleId) {
		final MmtrCarSpec car = new MmtrCarSpec();
		car.vehicleId = vehicleId;
		car.length = 20;
		car.width = 3;
		car.capacity = 200;
		return car;
	}

	private static MmtrFleet fleet(int consists, int spares) {
		final MmtrFleet fleet = new MmtrFleet();
		for (int i = 0; i < consists; i++) {
			fleet.addConsist(new MmtrFleet.ConsistSpec("C" + (i + 1), 80).addCar(car("p1")).addCar(car("p1")));
		}
		for (int i = 0; i < spares; i++) {
			fleet.addSpare(new MmtrFleet.ConsistSpec("S" + (i + 1), 80).addCar(car("p1")));
		}
		return fleet;
	}

	private static MmtrPlanInputs inputs() {
		final MmtrPlanInputs inputs = new MmtrPlanInputs();
		inputs.putLine(line());
		inputs.putPattern(pattern());
		inputs.fleet = fleet(3, 1);
		return inputs;
	}

	// ---------------------------------------------------------------- ① 往返

	/** ① 落盘 → 读回 → **逐字段**一致（含嵌套的车卡与段）。 */
	@Test
	public void inputsRoundTripThroughTheFileFieldByField() throws Exception {
		final MmtrPlanInputs original = inputs();
		final Path path = Paths.get("build/mmtr-plan-roundtrip/test/mmtr-plan.json");
		Files.deleteIfExists(path);
		original.save(path);
		assertTrue(Files.exists(path), "落盘失败");

		final MmtrPlanInputs loaded = MmtrPlanInputs.fromFile(path);
		assertEquals(1, loaded.lines.size());
		final MmtrLine line = loaded.lines.get(0);
		assertEquals("L1", line.lineId);
		assertEquals("1 号线", line.name);
		assertEquals(42, line.yardSidingId);
		assertEquals(5 * MIN, line.leadTimeMillis);
		assertEquals(MmtrLine.TerminalTreatment.CHANGE_ENDS, line.terminalTreatment);
		assertEquals(3, line.stops.size());
		assertEquals(30_000, line.stops.get(0).dwellMillis);
		assertEquals(1002, line.stops.get(1).stationId);
		assertEquals(2002, line.stops.get(1).platformId);
		assertEquals(45_000, line.stops.get(1).dwellMillis);
		assertEquals(1003, line.stops.get(2).stationId);

		assertEquals(1, loaded.patterns.size());
		final MmtrPattern pattern = loaded.patterns.get(0);
		assertEquals("L1", pattern.lineId);
		assertEquals(2, pattern.segments.size());
		assertEquals(H07, pattern.segments.get(0).fromMillis);
		assertEquals(H08_30, pattern.segments.get(0).toMillis);
		assertEquals(5 * MIN, pattern.segments.get(0).headwayMillis);
		assertEquals(3 * MIN, pattern.segments.get(1).headwayMillis);

		assertEquals(3, loaded.fleet.consists.size());
		assertEquals(1, loaded.fleet.spares.size());
		assertEquals("C1", loaded.fleet.consists.get(0).consistId);
		assertEquals(80.0, loaded.fleet.consists.get(0).maxSpeedKmh, 1e-9);
		assertEquals(2, loaded.fleet.consists.get(0).cars.size(), "两节车都要在");
		assertEquals("p1", loaded.fleet.consists.get(0).cars.get(0).vehicleId);
		assertEquals(20.0, loaded.fleet.consists.get(0).cars.get(0).length, 1e-9);
		assertTrue(loaded.fleet.isSpare("S1"), "替补身份也要读回来");

		assertTrue(loaded.validate().isEmpty(), "往返之后仍然合法：" + loaded.validate());
	}

	/** ① 续：字符串 JSON 解析（网页 PUT 走的就是这条路）与文件路径得到同一个结果。 */
	@Test
	public void parseFromJsonStringMatchesTheFileRoundTrip() {
		final MmtrPlanInputs original = inputs();
		final String json = org.mtr.core.tool.Utilities.getJsonObjectFromData(original).toString();
		final MmtrPlanInputs parsed = MmtrPlanInputs.parse(json);
		assertEquals(1, parsed.lines.size());
		assertEquals("L1", parsed.lines.get(0).lineId);
		assertEquals(3, parsed.fleet.consists.size());
		assertEquals(parsed.patterns.get(0).peakHeadwayMillis(), original.patterns.get(0).peakHeadwayMillis());
	}

	// ---------------------------------------------------------------- ② 校验

	/** ② 一份合法输入必须**一条问题都没有**（免得把"报错"做成"什么都报"）。 */
	@Test
	public void aWellFormedInputHasNoProblems() {
		final ObjectArrayList<String> errors = inputs().validate();
		assertTrue(errors.isEmpty(), "合法输入不该有问题：" + errors);
		assertNull(inputs().validateCapacity("L1", 9 * MIN), "9 min 周转 / 3 min 高峰 = 需要 3 个，正好配了 3 个");
	}

	/** ② 密度段**重叠**：报出来，并点名是哪两段、压在哪一段时间上。 */
	@Test
	public void overlappingPatternSegmentsAreReported() {
		final MmtrPattern pattern = new MmtrPattern("L1")
			.addSegment(H07, H08_30, 5 * MIN)
			.addSegment(H08_30 - 10 * MIN, H10, 3 * MIN);   // 与上一段压了 10 分钟
		final ObjectArrayList<String> errors = pattern.validate();
		assertTrue(errors.stream().anyMatch(e -> e.contains("重叠")), "要点名重叠：" + errors);
		assertTrue(errors.stream().anyMatch(e -> e.contains("08:20") && e.contains("08:30")), "要点名重叠区间：" + errors);
	}

	/** ② 运营时段**未覆盖**：两段之间空一截也是错（"某段时间没有车"必须报）。 */
	@Test
	public void aGapInTheOperatingWindowIsReported() {
		final MmtrPattern pattern = new MmtrPattern("L1")
			.addSegment(H07, H08_30, 5 * MIN)
			.addSegment(H08_30 + 30 * MIN, H10, 3 * MIN);   // 08:30–09:00 没有车
		final ObjectArrayList<String> errors = pattern.validate();
		assertTrue(errors.stream().anyMatch(e -> e.contains("未覆盖")), "要点名未覆盖：" + errors);
		assertTrue(errors.stream().anyMatch(e -> e.contains("08:30") && e.contains("09:00")), "要点名空档：" + errors);
	}

	/** ② {@code headway <= 0}：0 与负数都报（0 会让"每 0 分钟发一趟"变成死循环）。 */
	@Test
	public void nonPositiveHeadwayIsReported() {
		final MmtrPattern zero = new MmtrPattern("L1").addSegment(H07, H10, 0);
		assertTrue(zero.validate().stream().anyMatch(e -> e.contains("发车间隔必须为正")), zero.validate().toString());
		final MmtrPattern negative = new MmtrPattern("L1").addSegment(H07, H10, -5 * MIN);
		assertTrue(negative.validate().stream().anyMatch(e -> e.contains("发车间隔必须为正")), negative.validate().toString());
	}

	/** ② 站序**不闭合**两种写法都要报：环线首尾不同、非环线首尾相同。 */
	@Test
	public void stationOrderClosureIsCheckedBothWays() {
		final MmtrLine loopNotClosed = new MmtrLine("L1", "");
		loopNotClosed.yardSidingId = 1;
		loopNotClosed.loop = true;
		loopNotClosed.addStop(1, 1, 0).addStop(2, 2, 0).addStop(3, 3, 0);
		assertTrue(loopNotClosed.validate().stream().anyMatch(e -> e.contains("站序不闭合")), loopNotClosed.validate().toString());

		final MmtrLine notALoopButClosed = new MmtrLine("L2", "");
		notALoopButClosed.yardSidingId = 1;
		notALoopButClosed.addStop(1, 1, 0).addStop(2, 2, 0).addStop(1, 1, 0);
		assertTrue(notALoopButClosed.validate().stream().anyMatch(e -> e.contains("没有标成环线")), notALoopButClosed.validate().toString());

		final MmtrLine loopClosed = new MmtrLine("L3", "");
		loopClosed.yardSidingId = 1;
		loopClosed.loop = true;
		loopClosed.addStop(1, 1, 0).addStop(2, 2, 0).addStop(1, 1, 0);
		assertTrue(loopClosed.validate().isEmpty(), "环线首尾同站同台 = 合法：" + loopClosed.validate());
	}

	/** ② 其余结构错误（站数、停站时长、出库股道、密度表缺失、指向不存在的线路）都要在加载期说出来。 */
	@Test
	public void structuralAndCrossReferenceProblemsAreAllReported() {
		final MmtrPlanInputs inputs = new MmtrPlanInputs();
		final MmtrLine shortLine = new MmtrLine("L1", "");
		shortLine.addStop(1, 1, -1);                      // 负停站 + 只有一站
		inputs.putLine(shortLine);                        // 没给出库股道、没有密度表
		inputs.putPattern(new MmtrPattern("NOPE").addSegment(H07, H10, 5 * MIN));  // 指向不存在的线路
		inputs.fleet = new MmtrFleet();                   // 没有编组

		final ObjectArrayList<String> errors = inputs.validate();
		assertTrue(errors.stream().anyMatch(e -> e.contains("至少要有 2 站")), errors.toString());
		assertTrue(errors.stream().anyMatch(e -> e.contains("停站时长为负")), errors.toString());
		assertTrue(errors.stream().anyMatch(e -> e.contains("没有指定出库股道")), errors.toString());
		assertTrue(errors.stream().anyMatch(e -> e.contains("没有密度表")), errors.toString());
		assertTrue(errors.stream().anyMatch(e -> e.contains("不存在的线路")), errors.toString());
		assertTrue(errors.stream().anyMatch(e -> e.contains("车底为空")), errors.toString());
	}

	// ---------------------------------------------------------------- ③ 车底够不够

	/** ③ {@code N = ceil(ring / 高峰间隔)}：算得对，且**不够就报**（点名缺几个）。 */
	@Test
	public void notEnoughConsistsIsReportedAtLoadTime() {
		assertEquals(1, MmtrFleet.requiredConsists(30 * MIN, 60 * MIN), "周转比间隔还短 → 1 个就够");
		assertEquals(6, MmtrFleet.requiredConsists(30 * MIN, 5 * MIN), "30 min 周转 / 5 min 间隔 = 6");
		assertEquals(10, MmtrFleet.requiredConsists(29 * MIN, 3 * MIN), "有余数就向上取整（29/3 = 9.67 → 10）");
		assertEquals(2, MmtrFleet.requiredConsists(6 * MIN, 4 * MIN), "ceil(6/4) = 2");

		final MmtrPlanInputs inputs = inputs();          // 3 个套班编组，高峰间隔 3 min
		final String problem = inputs.validateCapacity("L1", 30 * MIN);
		assertNotNull(problem, "30 min 周转 / 3 min 高峰 = 需要 10 个，现在 3 个 —— 必须报");
		assertTrue(problem.contains("N=10"), "要点名需要几个：" + problem);
		assertTrue(problem.contains("现在只配了 3"), "要点名现在有几个：" + problem);
		assertTrue(problem.contains("缺 7"), "要点名缺几个：" + problem);
		assertTrue(problem.contains("替补 1 个不算在套班里"), "替补不参与套班要说清：" + problem);

		final MmtrPlanInputs enough = inputs();
		enough.fleet = fleet(10, 0);
		assertNull(enough.validateCapacity("L1", 30 * MIN), "配够 10 个就通过");
	}

	/** ③ 周转时间未知（0）时不误报 —— P2/P3 还没算出来之前不能把配置说成错的。 */
	@Test
	public void anUnknownRingDoesNotProduceAFalseAlarm() {
		assertNull(inputs().validateCapacity("L1", 0), "ring 未知 = 1 个就够（不误报）");
	}

	/**
	 * "一条都没配"与"配了但有错"必须分得开：前者是**没启用**（不该在启动日志里刷错误），
	 * 后者才是问题。这条是现场验证接口时发现的（空配置回了"车底为空"）。
	 */
	@Test
	public void anUnconfiguredPlanIsEmptyRatherThanBroken() {
		assertTrue(new MmtrPlanInputs().isEmpty(), "全新实例 = 未配置");
		final MmtrPlanInputs onlyLine = new MmtrPlanInputs();
		onlyLine.putLine(line());
		assertTrue(!onlyLine.isEmpty(), "只要配了一样东西，就不再是「未配置」");
		assertTrue(!onlyLine.validate().isEmpty(), "配了一半要照报（这里缺密度表与车底）");
	}
}
