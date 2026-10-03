package org.mtr.core.data;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.MmtrPid;
import org.mtr.core.mmtr.MmtrTaskTarget;
import org.mtr.core.mmtr.job.MmtrConsistJob;
import org.mtr.core.mmtr.job.MmtrJobStep;
import org.mtr.core.operation.VehicleSyncPatch;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **水牌 / PID**（notes/354）：从作业单读 班次号 / 本趟终点 / 下一站，并确认这三项真的到了客户端那条线上。
 *
 * <h2>用例钉住的口径（用户 2026-10-01 选定）</h2>
 * <ol>
 *   <li><b>班次号 = 作业单号</b>（不是另编一个号，也不是车号）。</li>
 *   <li><b>本趟终点 = 本趟最后一个停在站台的目标</b>；"本趟"到**下一次换端**为止。</li>
 *   <li><b>下一站 = 当前位置之后第一个停在站台的目标</b>，**允许跨趟**（在终点站开着门时，
 *       下一站就是换端之后那一趟的第一站 —— 这正是旅客要看的下一站）。</li>
 *   <li>名字是**车站名**，不是站台标签：{@code "Alpha"}，不是 {@code "Alpha站1台"}。</li>
 *   <li>名字**现算**，不抄作业单那句 {@code note}（作者写的可能过时）。</li>
 * </ol>
 *
 * <h2>为什么这个用例要建一张小世界</h2>
 * <p>要钉的是"从引擎对象现算车站名"这件事，就得真的有车站/站台对象 —— 拿假 id 试，
 * 三项会全空，于是"终点为空"这类反例会**假通过**。所以每个用例开头先断言
 * {@code platformIdMap} 里真的认得那几个站台（夹具守卫），再断言水牌。</p>
 */
public final class MmtrPidTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	/* ============================ 夹具：三个车站 + 四个站台 + 一条库内股道 ============================ */

	/** 站台名与车站名的两种写法（水牌要的是后者）—— 用例里反复用到这一对。 */
	private static final String STATION_ALPHA = "Alpha";
	private static final String STATION_BRAVO = "Bravo";
	private static final String STATION_CHARLIE = "Charlie";

	private static Rail platformRail(int z) {
		return Rail.newPlatformRail(new Position(0, 0, z), Angle.fromAngle(0), new Position(40, 0, z), Angle.fromAngle(0),
			Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
	}

	private static Station addStation(Simulator sim, String name, int zFrom, int zTo) {
		final Station station = new Station(sim);
		station.setName(name);
		station.setCorners(new Position(-5, -10, zFrom - 5), new Position(45, 10, zTo + 5));
		sim.stations.add(station);
		return station;
	}

	private static Platform addPlatform(Simulator sim, int z, String platformName) {
		sim.rails.add(platformRail(z));
		final Platform platform = new Platform(new Position(0, 0, z), new Position(40, 0, z), TransportMode.TRAIN, sim);
		platform.setName(platformName);
		sim.platforms.add(platform);
		return platform;
	}

	/** 一张最小世界：Alpha 两个站台（1、2 台）、Bravo 1 台、Charlie 2 台，外加一条库内股道。 */
	private static final class Net {

		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-pid"), false);
		final Platform alpha1;
		final Platform alpha2;
		final Platform bravo1;
		final Platform charlie2;
		final Siding siding;

		Net() {
			addStation(sim, STATION_ALPHA, 0, 20);
			addStation(sim, STATION_BRAVO, 200, 200);
			addStation(sim, STATION_CHARLIE, 400, 400);
			alpha1 = addPlatform(sim, 0, "1");
			alpha2 = addPlatform(sim, 20, "2");
			bravo1 = addPlatform(sim, 200, "1");
			charlie2 = addPlatform(sim, 400, "2");

			final Position yardA = new Position(0, 0, 800);
			final Position yardB = new Position(40, 0, 800);
			sim.rails.add(Rail.newSidingRail(yardA, Angle.fromAngle(0), yardB, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN));
			siding = new Siding(yardA, yardB, 40, TransportMode.TRAIN, sim);
			sim.sidings.add(siding);
			final Depot depot = new Depot(TransportMode.TRAIN, sim);
			depot.setName("Yard");
			depot.setCorners(new Position(-5, -10, 795), new Position(45, 10, 805));
			sim.depots.add(depot);

			sim.sync();
		}

		/**
		 * 夹具守卫：站台/股道必须在 {@code platformIdMap} 里认得出。
		 *
		 * <p>为什么这条不能省：{@code sync()} 会把"底下没有对应图轨"的站台删掉，删掉之后
		 * {@link MmtrPid} 三项全空 —— 而"终点为空"正是本次要验的一条反例，于是夹具坏掉时
		 * 用例会**假通过**。先守住夹具，后面的断言才有意义。</p>
		 */
		void assertFixtureAlive() {
			assertNotNull(sim.platformIdMap.get(alpha1.getId()), "站台 Alpha1 必须在 platformIdMap 里");
			assertNotNull(sim.platformIdMap.get(alpha2.getId()), "站台 Alpha2 必须在 platformIdMap 里");
			assertNotNull(sim.platformIdMap.get(bravo1.getId()), "站台 Bravo1 必须在 platformIdMap 里");
			assertNotNull(sim.platformIdMap.get(charlie2.getId()), "站台 Charlie2 必须在 platformIdMap 里");
			assertNotNull(sim.sidingIdMap.get(siding.getId()), "库内股道必须在 sidingIdMap 里");
		}
	}

	/**
	 * 现场 {@code 00101} 的形状：{@code Alpha1 → Bravo1 → 换端 → Charlie2 → 换端 → Alpha2 → 回库}。
	 * 站点名用拉丁字母是刻意的：这一层要验的是"读的是哪一个名字"，与中文无关。
	 */
	private static MmtrConsistJob job(Net net) {
		final MmtrConsistJob job = new MmtrConsistJob();
		job.jobId = "00101";
		job.steps.add(step(MmtrJobStep.StepType.MOVE_TO, net.alpha1.getId(), "开到 A 站 1 台"));     // 0
		job.steps.add(step(MmtrJobStep.StepType.SERVE, net.alpha1.getId(), "A 站 1 台 开关门停站")); // 1
		job.steps.add(step(MmtrJobStep.StepType.MOVE_TO, net.bravo1.getId(), "开到 B 站 1 台"));     // 2
		job.steps.add(step(MmtrJobStep.StepType.SERVE, net.bravo1.getId(), "B 站 1 台 开关门停站")); // 3
		job.steps.add(step(MmtrJobStep.StepType.CHANGE_ENDS, 0, "B 站 1 台 换端"));                  // 4
		job.steps.add(step(MmtrJobStep.StepType.MOVE_TO, net.charlie2.getId(), "开到 C 站 2 台"));   // 5
		job.steps.add(step(MmtrJobStep.StepType.SERVE, net.charlie2.getId(), "C 站 2 台 开关门停站")); // 6
		job.steps.add(step(MmtrJobStep.StepType.CHANGE_ENDS, 0, "C 站 2 台 换端"));                  // 7
		job.steps.add(step(MmtrJobStep.StepType.MOVE_TO, net.alpha2.getId(), "开到 A 站 2 台"));     // 8
		job.steps.add(step(MmtrJobStep.StepType.SERVE, net.alpha2.getId(), "A 站 2 台 开关门停站")); // 9
		job.steps.add(step(MmtrJobStep.StepType.MOVE_TO, net.siding.getId(), "回库"));               // 10
		return job;
	}

	private static MmtrJobStep step(MmtrJobStep.StepType type, long targetId, String note) {
		final MmtrJobStep step = new MmtrJobStep();
		step.type = type;
		step.targetId = targetId;
		step.note = note;
		return step;
	}

	private static void assertPid(MmtrPid pid, String service, String terminus, String next, int leg, String because) {
		assertEquals(service, pid.serviceNumber(), because + "：班次号");
		assertEquals(terminus, pid.terminus(), because + "：本趟终点");
		assertEquals(next, pid.nextStation(), because + "：下一站");
		assertEquals(leg, pid.legIndex(), because + "：第几趟");
	}

	/* ============================ ① 本趟终点 = 本趟最后一个停站 ============================ */

	@Test
	public void theDestinationIsThisLegsLastStop() {
		final Net net = new Net();
		net.assertFixtureAlive();
		final MmtrConsistJob job = job(net);

		// 出发前（第 0 步开往 A 站）：本趟是 A → B，终点是 B —— 不是下一趟的 C
		assertPid(MmtrPid.of(net.sim, job, 0), "00101", STATION_BRAVO, STATION_ALPHA, 1, "第 0 步（始发）");
		// 开往 B 的途中：终点仍是 B，下一站也是 B（马上就到）
		assertPid(MmtrPid.of(net.sim, job, 2), "00101", STATION_BRAVO, STATION_BRAVO, 1, "第 2 步（开往 B）");
	}

	/** **反例**：终点不许取成"整条作业单的最后一个站"（那样始发时报的就是 C）。 */
	@Test
	public void theDestinationIsNotTheWholeJobsLastStation() {
		final Net net = new Net();
		final MmtrConsistJob job = job(net);
		final MmtrPid first = MmtrPid.of(net.sim, job, 0);
		assertNotEquals(STATION_CHARLIE, first.terminus(), "始发时终点是 B，不许报成整条作业单最后的 C");
		assertNotEquals(STATION_ALPHA, first.terminus(), "始发时终点是 B，不许报成本趟起点 A");
	}

	/* ============================ ② 下一站（允许跨趟） ============================ */

	/**
	 * **反例（本用例最重要的一条）**：在终点站开着门时，"下一站"必须是**换端之后那一趟的第一站**。
	 *
	 * <p>把扫描范围截在"本趟"（到换端为止）就会得到空 —— 而那一刻正是旅客抬头看下一站的时候。</p>
	 */
	@Test
	public void theNextStationCrossesTheChangeEnds() {
		final Net net = new Net();
		final MmtrConsistJob job = job(net);

		// 第 3 步：B 站停站开门（本趟终点站）—— 下一站是换端之后那一趟的第一站 C
		assertPid(MmtrPid.of(net.sim, job, 3), "00101", STATION_BRAVO, STATION_CHARLIE, 1, "在终点站 B 开门中");
		// 第 6 步：C 站停站开门 —— 下一站是再过一次换端之后的第一站 A（2 台）
		assertPid(MmtrPid.of(net.sim, job, 6), "00101", STATION_CHARLIE, STATION_ALPHA, 2, "在终点站 C 开门中");
		// 第 9 步：A 站 2 台开门 —— 本趟是最后一趟，后面只有回库 ⇒ 下一站为空
		assertPid(MmtrPid.of(net.sim, job, 9), "00101", STATION_ALPHA, "", 3, "末趟终点站 A 开门中");
	}

	/* ============================ ③ 换端 = 翻趟（水牌在换端那一刻就翻面） ============================ */

	@Test
	public void changeEndsFlipsTheLegAndTheDestination() {
		final Net net = new Net();
		final MmtrConsistJob job = job(net);

		// 到达 B（第 3 步）与换端那一步（第 4 步）：终点已经翻成 C
		assertEquals(STATION_BRAVO, MmtrPid.of(net.sim, job, 3).terminus(), "换端之前：本趟终点是 B");
		assertPid(MmtrPid.of(net.sim, job, 4), "00101", STATION_CHARLIE, STATION_CHARLIE, 2, "换端那一步（已是下一趟）");
		// 第二次换端（第 7 步）：终点翻回 A
		assertPid(MmtrPid.of(net.sim, job, 7), "00101", STATION_ALPHA, STATION_ALPHA, 3, "第二次换端");
	}

	/* ============================ ④ 回库趟：没有站台目标 ⇒ 三项里只有班次号 ============================ */

	@Test
	public void theReturnLegHasNoStationDestination() {
		final Net net = new Net();
		final MmtrConsistJob job = job(net);
		assertPid(MmtrPid.of(net.sim, job, 10), "00101", "", "", 3, "回库那一步");
	}

	/* ============================ ⑤ 名字：车站名，不是站台标签；不抄 note ============================ */

	@Test
	public void theNameIsTheStationNotThePlatformLabel() {
		final Net net = new Net();
		final MmtrConsistJob job = job(net);
		final MmtrPid pid = MmtrPid.of(net.sim, job, 0);

		assertEquals(STATION_BRAVO, pid.terminus());
		assertNotEquals("Bravo站1台", pid.terminus(), "终点是车站名，不许带站台");
		// 同一个目标，另一套读法确实是"车站 + 站台"—— 证明这两个名字在这一层是可分的（不是碰巧相等）
		assertEquals("Bravo站1台", MmtrTaskTarget.resolve(net.sim, net.bravo1.getId(), null, -1).label(),
			"站台标签仍然是『车站名 + 站号 + 台』；水牌取的是同一个目标的车站名那一份");
	}

	@Test
	public void theNamesAreComputedAndNotCopiedFromTheStepsNote() {
		final Net net = new Net();
		final MmtrConsistJob job = job(net);
		// 把作者写的说明改成谎话：水牌三项必须一字不变（它从引擎对象现算）
		job.steps.forEach(step -> step.note = "胡说八道");
		assertPid(MmtrPid.of(net.sim, job, 0), "00101", STATION_BRAVO, STATION_ALPHA, 1, "note 被改写之后");
	}

	/** 班次号就是作业单号（用户口径）：换一个作业单号，水牌第一项跟着换。 */
	@Test
	public void theServiceNumberIsTheJobId() {
		final Net net = new Net();
		final MmtrConsistJob job = job(net);
		job.jobId = "04202";
		assertEquals("04202", MmtrPid.of(net.sim, job, 0).serviceNumber());
	}

	/* ============================ ⑥ 边界：不挂作业单 / 步号越界 ============================ */

	@Test
	public void noJobOrEmptyJobIsUnknown() {
		final Net net = new Net();
		assertEquals(MmtrPid.UNKNOWN, MmtrPid.of(net.sim, null, 3));
		assertFalse(MmtrPid.of(net.sim, null, 3).isKnown());
		assertEquals("（不在作业单上）", MmtrPid.UNKNOWN.describe());
		assertFalse(MmtrPid.of(net.sim, new MmtrConsistJob(), 0).isKnown(), "没有步骤的作业单 = 没有水牌");
	}

	@Test
	public void stepIndexOutOfRangeIsClampedNotThrown() {
		final Net net = new Net();
		final MmtrConsistJob job = job(net);
		// tick 抛异常会中断整拍剩下的工作（现场有先例），所以调度器换单/循环重置那一拍必须安全
		assertEquals(MmtrPid.of(net.sim, job, 10).describe(), MmtrPid.of(net.sim, job, 999).describe());
		assertEquals(MmtrPid.of(net.sim, job, 0).describe(), MmtrPid.of(net.sim, job, -5).describe());
	}

	/* ============================ ⑦ 镜像：三项真的会到客户端（补丁，不是整份） ============================ */

	@Test
	public void theThreeFieldsAreDynamicKeysOnTheWire() {
		assertTrue(VehicleSyncPatch.isDynamicKey("mmtrPidService"), "班次号要能走稀疏补丁（每站都在变）");
		assertTrue(VehicleSyncPatch.isDynamicKey("mmtrPidTerminus"), "终点要能走稀疏补丁");
		assertTrue(VehicleSyncPatch.isDynamicKey("mmtrPidNext"), "下一站要能走稀疏补丁");
	}

	/** 客户端读法：收到 `vehicle` 段就落到这三个字段上（与客户端 `updateData` 同一条路）。 */
	@Test
	public void theClientReadsTheThreeFieldsFromTheVehicleSection() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-pid-wire"), false);
		final Vehicle vehicle = new Vehicle(new VehicleExtraData(new JsonReader(new JsonObject())), null, new JsonReader(new JsonObject()), sim);
		assertEquals("", vehicle.getMmtrPidServiceFromSync(), "基线：还没收到过水牌");

		final JsonObject json = new JsonObject();
		json.addProperty("mmtrPidService", "00101");
		json.addProperty("mmtrPidTerminus", STATION_BRAVO);
		json.addProperty("mmtrPidNext", STATION_CHARLIE);
		vehicle.updateData(new JsonReader(json));

		assertEquals("00101", vehicle.getMmtrPidServiceFromSync());
		assertEquals(STATION_BRAVO, vehicle.getMmtrPidTerminusFromSync());
		assertEquals(STATION_CHARLIE, vehicle.getMmtrPidNextFromSync());
	}

	/**
	 * **"调度器每 tick 都写"不许变成"每 tick 都推"**：三项没变时 {@code setMmtrPid} 什么都不做。
	 *
	 * <p>这条不变量一旦破了，十列车每分钟会多推十几次补丁（过一站一次是应该的，每 tick 一次不是）。
	 * 观测量是 {@code checkForUpdate()}（脏标记），它就是同步路径上"发不发"的那道闸。</p>
	 */
	@Test
	public void writingTheSamePidDoesNotMarkTheVehicleDirty() {
		final Net net = new Net();
		final MmtrConsistJob job = job(net);
		final Vehicle vehicle = new Vehicle(new VehicleExtraData(new JsonReader(new JsonObject())), null, new JsonReader(new JsonObject()), net.sim);

		vehicle.vehicleExtraData.checkForUpdate(); // 清掉构造时的初值
		assertFalse(vehicle.vehicleExtraData.checkForUpdate(), "基线：没有变化");

		vehicle.setMmtrPid(MmtrPid.of(net.sim, job, 0));
		assertTrue(vehicle.vehicleExtraData.checkForUpdate(), "第一次写水牌要标脏（客户端得拿到）");
		assertFalse(vehicle.vehicleExtraData.checkForUpdate(), "同一次写只脏一次");

		vehicle.setMmtrPid(MmtrPid.of(net.sim, job, 0));
		assertFalse(vehicle.vehicleExtraData.checkForUpdate(), "★ 同名再写一次不许标脏（每 tick 都写 ≠ 每 tick 都推）");

		vehicle.setMmtrPid(MmtrPid.of(net.sim, job, 2));
		assertTrue(vehicle.vehicleExtraData.checkForUpdate(), "下一站变了 ⇒ 要推");

		vehicle.setMmtrPid(MmtrPid.of(net.sim, job, 2));
		assertFalse(vehicle.vehicleExtraData.checkForUpdate(), "★ 再写同一个值仍然不许标脏");
	}

	/** 车不再挂在在跑的作业单上 ⇒ 清空水牌（停在库里的车不该挂水牌），并且也只在**变化时**推一次。 */
	@Test
	public void clearingThePidEmptiesTheMirrorOnce() {
		final Net net = new Net();
		final MmtrConsistJob job = job(net);
		final Vehicle vehicle = new Vehicle(new VehicleExtraData(new JsonReader(new JsonObject())), null, new JsonReader(new JsonObject()), net.sim);

		vehicle.setMmtrPid(MmtrPid.of(net.sim, job, 0));
		vehicle.vehicleExtraData.checkForUpdate();
		vehicle.clearMmtrPid();
		assertTrue(vehicle.vehicleExtraData.checkForUpdate(), "清空要标脏一次");
		assertEquals("", vehicle.getMmtrPidServiceFromSync());
		assertEquals("", vehicle.getMmtrPidTerminusFromSync());
		assertEquals("", vehicle.getMmtrPidNextFromSync());
		assertFalse(vehicle.vehicleExtraData.checkForUpdate(), "再清一次不脏");
	}
}
