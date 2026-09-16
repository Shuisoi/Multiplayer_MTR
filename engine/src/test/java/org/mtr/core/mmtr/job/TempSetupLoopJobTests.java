package org.mtr.core.mmtr.job;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 临时工具（用完即删）：给真实世界写一条**1↔3 站循环作业单**（出库 → 1/1 → 2/1 → 3/1 → 南端折返换端 →
 * 3/2 → 2/2 → 1/2 → 北端折返换端 → 循环）。
 *
 * <p><b>两端折返都是"到一根正规轨道上换端"</b>（用户口径：不可能为了折返把某条线定义成折返专用股道）：
 * 步骤用 {@link MmtrJobStep#targetRailHex} 指到图轨 hex，fraction 1.0 = 开到这根轨按行车方向的远端。
 * 所以本工具**不改世界几何**，只写作业单。</p>
 */
public final class TempSetupLoopJobTests {

	private static final Path ROOT = Paths.get("C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/world/mtr");
	private static final long DEPOT_987654 = 849401984139021720L;
	private static final long SIDING_C1 = -4629294257679021237L;

	// 站台（去程 1 台 / 回程 2 台）
	private static final long P1_OUT = -8021057666741005855L;    // 站1/1  (-170,-333)→(-170,-306)
	private static final long P2_OUT = 8840155259502644057L;     // 站2/1  (-170,-412)→(-170,-379)
	private static final long P3_OUT = 954364252968674217L;      // 站3/1  (-170,-478)→(-170,-458)
	private static final long P3_BACK = 538294557107521739L;     // 站3/2  (-176,-478)→(-176,-458)
	private static final long P2_BACK = -5224482131162675969L;   // 站2/2  (-176,-413)→(-176,-379)
	private static final long P1_BACK = 1067577243292760039L;    // 站1/2  (-176,-333)→(-176,-306)

	/** 两端折返点（正规轨道，按端点找）：北端是两个斜渡线之间的正线；南端是真尽头支线。 */
	private static final Position NORTH_A = new Position(-176, -60, -253);
	private static final Position NORTH_B = new Position(-176, -60, -222);
	private static final Position SOUTH_A = new Position(-176, -60, -564);
	private static final Position SOUTH_B = new Position(-176, -60, -541);

	/** due 是"当日毫秒"；循环里被换算成相对秒数（due - startTimeOfDayMs），所以要给足一整圈的时间。 */
	private static final long DUE_END_OF_DAY = 86_399_000L;
	private static final long START_TIME_OF_DAY = 1_000L;

	private static MmtrJobStep step(String id, MmtrJobStep.StepType type, long targetId, String note) {
		final MmtrJobStep step = new MmtrJobStep();
		step.stepId = id;
		step.type = type;
		step.targetId = targetId;
		step.dueTimeOfDayMs = DUE_END_OF_DAY;
		step.note = note;
		return step;
	}

	private static MmtrJobStep railStep(String id, Rail rail, String note) {
		final MmtrJobStep step = step(id, MmtrJobStep.StepType.MOVE_TO, 0, note);
		step.targetRailHex = rail.getHexId();
		step.targetRailFraction = 1.0;   // 开到头（按行车方向的远端），正好留给下一步换端
		return step;
	}

	private static Rail findRail(Simulator sim, Position a, Position b) {
		for (final Rail rail : sim.rails) {
			final Position[] ordered = rail.mmtrOrderedPositions();
			if (ordered[0].equals(a) && ordered[1].equals(b)) {
				return rail;
			}
		}
		return null;
	}

	/**
	 * 造一条循环作业单（写盘与离线验证共用）。
	 *
	 * @param jobId        作业单 id
	 * @param sidingId     本务股道（车停在哪条股道就从哪条认领；三条作业单各用一条，互不抢）
	 * @param startTimeOfDayMs 首次发车时刻（当日毫秒）—— **错开开车时间**靠它：三班车错开约 1/3 圈
	 * @param endAtNorthTurnback true = 一圈在北端 (-176,-222) 换端掉头后收尾（不回库，下一圈从那里自己开出去）；
	 *                           false = 走完北端后**回库**（自己开回本务股道，下一圈再从库里发车）
	 */
	public static MmtrConsistJob buildJob(Simulator sim, String jobId, long sidingId, long startTimeOfDayMs, boolean endAtNorthTurnback) {
		final Rail northTurnback = findRail(sim, NORTH_A, NORTH_B);
		final Rail southTurnback = findRail(sim, SOUTH_A, SOUTH_B);
		if (northTurnback == null || southTurnback == null) {
			throw new IllegalStateException("找不到折返轨：北=" + (northTurnback != null) + " 南=" + (southTurnback != null));
		}
		System.out.println("[SETUP] " + jobId + " 折返轨 北=" + northTurnback.getHexId() + "（长 " + Math.round(northTurnback.railMath.getLength())
			+ "m）/ 南=" + southTurnback.getHexId() + "（长 " + Math.round(southTurnback.railMath.getLength()) + "m）");

		final MmtrConsistJob job = new MmtrConsistJob();
		job.jobId = jobId;
		job.depotId = DEPOT_987654;
		job.sidingId = sidingId;
		job.startTimeOfDayMs = startTimeOfDayMs;
		job.repeatDaily = true;
		job.loop = true;
		job.loopEveryMs = 120_000L;               // 一圈跑完歇 2 分钟再发下一圈（三班同周期 ⇒ 相位不会被拖乱）
		final MmtrCarSpec car = new MmtrCarSpec();
		car.vehicleId = "saf101";
		car.length = 16;
		car.width = 5;
		car.bogie1Position = -5;
		car.bogie2Position = 5;
		car.powered = true;
		job.cars.add(car);

		int n = 0;
		job.steps.add(step("m1o", MmtrJobStep.StepType.MOVE_TO, P1_OUT, "去程到 1 站 1 台"));
		job.steps.add(step("s1o", MmtrJobStep.StepType.SERVE, P1_OUT, "1 站 1 台开关门"));
		job.steps.add(step("m2o", MmtrJobStep.StepType.MOVE_TO, P2_OUT, "去程到 2 站 1 台"));
		job.steps.add(step("s2o", MmtrJobStep.StepType.SERVE, P2_OUT, "2 站 1 台开关门"));
		job.steps.add(step("m3o", MmtrJobStep.StepType.MOVE_TO, P3_OUT, "去程到 3 站 1 台"));
		job.steps.add(step("s3o", MmtrJobStep.StepType.SERVE, P3_OUT, "3 站 1 台开关门"));
		job.steps.add(railStep("tbS", southTurnback, "开到南端折返轨尽头（(-176,-541)→(-176,-564) 支线）换端"));
		job.steps.add(step("ceS", MmtrJobStep.StepType.CHANGE_ENDS, 0, "南端换端掉头"));
		job.steps.add(step("m3b", MmtrJobStep.StepType.MOVE_TO, P3_BACK, "回程到 3 站 2 台"));
		job.steps.add(step("s3b", MmtrJobStep.StepType.SERVE, P3_BACK, "3 站 2 台开关门"));
		job.steps.add(step("m2b", MmtrJobStep.StepType.MOVE_TO, P2_BACK, "回程到 2 站 2 台"));
		job.steps.add(step("s2b", MmtrJobStep.StepType.SERVE, P2_BACK, "2 站 2 台开关门"));
		job.steps.add(step("m1b", MmtrJobStep.StepType.MOVE_TO, P1_BACK, "回程到 1 站 2 台"));
		job.steps.add(step("s1b", MmtrJobStep.StepType.SERVE, P1_BACK, "1 站 2 台开关门"));
		job.steps.add(railStep("tbN", northTurnback, "开到北端折返轨（(-176,-253)→(-176,-222) 正线段）远端 (-176,-222)，即北端掉头点"));
		if (endAtNorthTurnback) {
			job.steps.add(step("ceN", MmtrJobStep.StepType.CHANGE_ENDS, 0, "北端 (-176,-222) 换端掉头（不回库；下一圈从这里自己开出去）"));
		} else {
			job.steps.add(step("mh", MmtrJobStep.StepType.MOVE_TO, sidingId, "回库：经 (-176,-222)→(-170,-199) 斜渡线换到本务股道（自己开回去，不靠重生）"));
		}
		for (final MmtrJobStep s : job.steps) {
			n++;
			System.out.println("[SETUP] " + jobId + " 步骤 " + String.format("%02d", n) + " " + s.stepId + " " + s.type
				+ (s.targetRailHex == null || s.targetRailHex.isEmpty() ? " 目标=" + s.targetId : " 轨目标=" + s.targetRailHex.substring(0, 8) + "…")
				+ " " + s.note);
		}
		return job;
	}

	/**
	 * 三班车的参数：股道 / 首次发车时刻（**错开**）/ 一圈怎么收尾。
	 *
	 * <p>错开量为什么是 150 s（≈ 一圈的 2/3）：这条环线的**两端共用同几处道岔**（南端 (-541)/(-511)、
	 * 北端 (-253)/(289)），两班车同时停在同一端就会互相扣住。</p>
	 *
	 * <p>收尾方式（第 3 列：0 = 回库，非 0 = 北端换端）——<b>三班都取"回库"</b>：
	 * 在 (-176,-222) 经斜渡线换到 x=-170 正线、再进库房股道，下一圈从库里发车。这样**每一段的行车方向
	 * 都是单向的**（出库走 A 往南、回程走 B 往北、回库再走 A 往北），不会出现"北部掉头处两个车顶头"
	 * 那种对向相遇（现场实测过：B/C 在图里换端后下一圈要往南走 B 轨，正好撞上回程北上的车）。</p>
	 */
	public static final long[][] LOOP_FLEET = {
		{SIDING_C1, 1_000L, 0},                    // 987654/1 —— 第一班
		{1607594720369027173L, 151_000L, 0},       // 987654/2 —— 第二班
		{4321759533923363700L, 301_000L, 0},       // 987654/3 —— 第三班
	};

	public static final String[] LOOP_JOB_IDS = {"TT-LOOP-1-3", "TT-LOOP-1-3-B", "TT-LOOP-1-3-C"};

	/** 三班车一起造（收尾方式见 {@link #LOOP_FLEET}）。 */
	public static java.util.List<MmtrConsistJob> buildFleet(Simulator sim) {
		final java.util.List<MmtrConsistJob> jobs = new java.util.ArrayList<>();
		for (int i = 0; i < LOOP_JOB_IDS.length; i++) {
			jobs.add(buildJob(sim, LOOP_JOB_IDS[i], LOOP_FLEET[i][0], LOOP_FLEET[i][1], LOOP_FLEET[i][2] != 0));
		}
		return jobs;
	}

	@Test
	public void setup() {
		Assumptions.assumeTrue(Files.isDirectory(ROOT), "dev world save not present");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, ROOT, false);
		// 老的三条都撤掉（含上一轮那条单班的），再写这三班错开时间的循环作业单。
		for (final MmtrConsistJob existing : new java.util.ArrayList<>(sim.mmtrJobRegistry.jobs)) {
			sim.mmtrJobRegistry.jobs.remove(existing);
		}
		for (final MmtrConsistJob job : buildFleet(sim)) {
			sim.upsertMmtrJob(job);
		}
		System.out.println("[SETUP] 作业单已落盘 mmtr-jobs.json，条数=" + sim.mmtrJobRegistry.jobs.size());
		// `save()` 只是置 autoSave 标志（真正的写盘发生在 tick 里）；`stop()` 才是"立刻做一次非增量完整保存"
		sim.stop();
		System.out.println("[SETUP] 世界已保存（只改了作业单，没有动轨道/股道）");
	}
}
