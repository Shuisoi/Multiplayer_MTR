package org.mtr.core.mmtr.sim;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Assumptions;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.mmtr.job.MmtrConsistJob;
import org.mtr.core.mmtr.job.MmtrJobScheduler;
import org.mtr.core.simulation.Simulator;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **回库现场取证（简化场景：跑 N 圈就回去）**：作业单只排 {@code N=2..6} 圈 + 一步回库，
 * 用**与服务端同节拍**的 10 ms 跑，把每份作业单的回库进路**一根轨一根轨**记下来。
 *
 * <h3>为什么简化成"N 圈就回去"（用户 2026-09-27 口径）</h3>
 * <p>"回库直接简化吧，分别测试跑 2、3、4、5、6 圈就回去。" —— 全天 88 圈一场要跑 90 min 墙钟，
 * 迭代太慢；而回库这件事只取决于"车怎么进库"，与前面 88 圈无关。于是每档 N 单独一张作业单：
 * N 圈（同一路线、4 列、180 s 错开）+ 回库（带/不带经由点各一份，做对照）。</p>
 *
 * <h3>看什么</h3>
 * <ul>
 *   <li><b>走哪条引入线</b>：回库段逐轨记下来 —— "沿 1 道向东逆行"还是"2 道向东 → 立交 → 106,65,1600"；</li>
 *   <li><b>用时</b>：回库开始 → 到库（本务股道），以及回库段最长静止；</li>
 *   <li><b>到不到得了</b>：最终态与失败原因。</li>
 * </ul>
 *
 * <p>纪律：世界只读（副本 {@code build/mmtr-return-via/&lt;档&gt;}），线上存档不动；副本取自
 * <b>{@code .tmp-studio/world} 那份快照</b>（全天录制用的就是它）—— 两个对照臂必须同源。
 * 录制期间引擎日志被静音（量出来的才是仿真本身，不是管道 I/O）。</p>
 */
final class TempRetArms {

	/** 每圈给多少仿真秒的上限（实测圈时 635~760 s）。 */
	private static final int SECONDS_PER_CYCLE = 800;
	/** 录制尾巴：出库 + 排队 + 回库那一趟。 */
	private static final int TAIL_SECONDS = 1500;
	/** 首发时刻（日钟）：00:20 —— 只要避开"日钟 0"这一件事，越早越省空推。 */
	private static final long START_DAY_MS = 20 * 60 * 1000L;
	/** 服务端节拍。 */
	private static final long TICK_MILLIS = 10;

	/** 工作区根（测试工作目录是 {@code mmtr/engine}）。 */
	private static Path workspaceRoot() {
		return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize().resolve(Paths.get("..", "..")).normalize();
	}

	/** 全天录制用的那份世界快照（存在就用它；没有才退回线上世界）。 */
	private static Path studioWorld() {
		final Path snapshot = workspaceRoot().resolve(Paths.get(".tmp-studio", "world"));
		return Files.isDirectory(snapshot.resolve("minecraft")) ? snapshot : TempSimProbeTests.liveWorld();
	}

	private static String railText(Simulator sim, Rail rail) {
		final Position[] ends = new Position[]{null, null};
		sim.positionsToRail.forEach((pos, neighbours) -> neighbours.forEach((other, r) -> {
			if (r == rail) {
				if (ends[0] == null) {
					ends[0] = pos;
				} else if (!pos.equals(ends[0]) && ends[1] == null) {
					ends[1] = pos;
				}
			}
		}));
		return ends[0] == null ? "?" : "(" + ends[0].getX() + "," + ends[0].getY() + "," + ends[0].getZ() + ")→("
			+ ends[1].getX() + "," + ends[1].getY() + "," + ends[1].getZ() + ")";
	}

	private static String hhmmss(long dayMillis) {
		final long seconds = Math.floorDiv(dayMillis, 1000);
		return String.format("%02d:%02d:%02d", Math.floorDiv(seconds, 3600), Math.floorMod(Math.floorDiv(seconds, 60), 60), Math.floorMod(seconds, 60));
	}

	/**
	 * 跑一档 N 圈 + 回库。
	 *
	 * @param tag     报告标题
	 * @param copyDir 世界副本目录名（**必须每臂唯一**：两臂同名会同时往一个目录里拷世界，实测直接 FileAlreadyExistsException）
	 * @param jobFile 作业单文件名（{@code mmtr-studio/jobs/} 下）
	 * @param cycles  这一档的圈数（只用于算录制窗口）
	 */
	static void run(String tag, String copyDir, String jobFile, int cycles) throws IOException {
		final Path world = studioWorld();
		Assumptions.assumeTrue(Files.isDirectory(world.resolve("minecraft")), "world not present: " + world);
		final Path jobs = workspaceRoot().resolve(Paths.get("mmtr-studio", "jobs", jobFile));
		Assumptions.assumeTrue(Files.isRegularFile(jobs), "jobs file not present: " + jobs);

		final PrintStream realOut = System.out;
		final Path copy = Paths.get("build", "mmtr-return-via", copyDir);
		TempSimProbeTests.deleteRecursively(copy);
		TempSimProbeTests.copyRecursively(world, copy);
		final Path jobsTarget = copy.resolve("minecraft/overworld/mmtr-jobs.json");
		Files.createDirectories(jobsTarget.getParent());
		Files.copy(jobs, jobsTarget, StandardCopyOption.REPLACE_EXISTING);

		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, copy, false);
		final MmtrJobScheduler scheduler = sim.mmtrJobScheduler;
		Assumptions.assumeTrue(scheduler != null && !sim.mmtrJobRegistry.jobs.isEmpty(), "no jobs installed");

		final List<MmtrConsistJob> jobList = new ArrayList<>(sim.mmtrJobRegistry.jobs);
		jobList.sort(Comparator.comparing(job -> job.jobId));
		final long startDayMs = jobList.stream().mapToLong(job -> job.startTimeOfDayMs).min().orElse(START_DAY_MS);
		final int seconds = cycles * SECONDS_PER_CYCLE + TAIL_SECONDS;

		realOut.println();
		realOut.println("[RET] ================= " + tag + " =================");
		realOut.println("[RET] 世界快照 = " + world);
		realOut.println("[RET] 作业单 = " + jobs + "（" + jobList.size() + " 份，" + cycles + " 圈 + 回库，首发 " + hhmmss(startDayMs) + "）");
		realOut.println("[RET] 节拍 = " + TICK_MILLIS + " ms（服务端 MILLISECONDS_PER_TICK）；录制窗口上限 " + seconds + " s（跑完即停）");

		// 经由点那两个节点 → 与它相连的轨（进路里出现这些轨，就是真的从那条引入线上过了）
		final Position viaNode = new Position(106, 65, 1600);
		final ObjectArrayList<String> viaRailHexes = new ObjectArrayList<>();
		final var viaNeighbours = sim.positionsToRail.get(viaNode);
		if (viaNeighbours != null) {
			viaNeighbours.forEach((other, rail) -> viaRailHexes.add(rail.getHexId()));
		}
		realOut.println("[RET] 经由点 106,65,1600 相连的轨：" + viaRailHexes.size() + " 根"
			+ (viaNeighbours == null ? "（节点不存在！）" : ""));

		final Map<String, JobTrack> tracks = new LinkedHashMap<>();
		for (final MmtrConsistJob job : jobList) {
			tracks.put(job.jobId, new JobTrack(job.jobId, job.steps.size(), job.startTimeOfDayMs));
		}

		int recordedSeconds = 0;
		final long startedAt = System.nanoTime();
		final PrintStream realOutStream = realOut;
		System.setOut(new PrintStream(OutputStream.nullOutputStream()));
		try {
			// ---- 空推到首发时刻（车都停在库里，节拍同录制）----
			final long preRollStart = System.nanoTime();
			for (long elapsed = 0; elapsed < startDayMs; elapsed += Math.min(TICK_MILLIS, startDayMs - elapsed)) {
				sim.step(Math.min(TICK_MILLIS, startDayMs - elapsed));
			}
			realOutStream.println("[RET] 空推到 " + hhmmss(startDayMs) + " 用了 " + Math.round((System.nanoTime() - preRollStart) / 1e9) + " s 墙钟");

			for (int second = 1; second <= seconds; second++) {
				for (int tick = 0; tick < 1000 / TICK_MILLIS; tick++) {
					sim.step(TICK_MILLIS);
				}
				recordedSeconds = second;
				final long dayMs = startDayMs + second * 1000L;
				final Map<String, Vehicle> vehicles = new LinkedHashMap<>();
				for (final Siding siding : sim.sidings) {
					siding.iterateVehicles(vehicle -> {
						final String jobId = scheduler.jobIdOfVehicle(vehicle.getId());
						if (jobId != null) {
							vehicles.putIfAbsent(jobId, vehicle);
						}
					});
				}
				boolean allTerminal = true;
				for (final Map.Entry<String, JobTrack> entry : tracks.entrySet()) {
					final JobTrack track = entry.getValue();
					final String state = String.valueOf(scheduler.stateOf(entry.getKey()));
					final int index = scheduler.stepIndexOf(entry.getKey());
					final Vehicle vehicle = vehicles.get(entry.getKey());
					final boolean terminal = "DONE".equals(state) || "FAILED".equals(state);
					if (!terminal) {
						allTerminal = false;
					}
					final boolean retStep = index == track.stepCount - 1;
					if (retStep && track.retStartDayMs < 0 && "RUNNING".equals(state)) {
						track.retStartDayMs = dayMs;
					}
					if ("DONE".equals(state) && track.doneDayMs < 0) {
						track.doneDayMs = dayMs;
						track.retEndDayMs = dayMs;
					}
					if ("FAILED".equals(state) && track.failedDayMs < 0) {
						track.failedDayMs = dayMs;
						track.failure = String.valueOf(scheduler.failureOf(entry.getKey()));
					}
					if (vehicle == null || terminal) {
						continue;
					}
					final double distance = vehicle.getMmtrMotionWalker() == null ? -1 : vehicle.getMmtrMotionWalker().distanceM();
					final String railHex = vehicle.getMmtrMotionWalker() == null ? null : vehicle.getMmtrMotionWalker().railHex();
					// 静止时长：里程 10 s 没动 > 0.5 m 才算"停住了"（到站停留 ~19 s 是正常的）
					if (track.lastDistanceM >= 0 && Math.abs(distance - track.lastDistanceM) > 0.5) {
						track.stationarySinceDayMs = -1;
					} else if (track.lastDistanceM >= 0 && track.stationarySinceDayMs < 0) {
						track.stationarySinceDayMs = dayMs;
					}
					if (track.stationarySinceDayMs > 0) {
						track.maxStationaryMs = Math.max(track.maxStationaryMs, dayMs - track.stationarySinceDayMs);
						if (retStep) {
							track.retMaxStationaryMs = Math.max(track.retMaxStationaryMs, dayMs - track.stationarySinceDayMs);
						}
					}
					track.lastDistanceM = (long) distance;
					track.lastSpeedKmh = vehicle.getSpeed() * 3.6;
					// 回库段的轨序（去重相邻重复）：这才是"走了哪条引入线"的直接证据
					if (retStep && railHex != null) {
						track.lastRailHex = railHex;
						if (!railHex.equals(track.lastRecordedRailHex)) {
							track.railSequence.add(railHex);
							track.lastRecordedRailHex = railHex;
						}
						if (viaRailHexes.contains(railHex)) {
							track.viaRailsHit++;
						}
					}
				}
				if (second % 120 == 0) {
					final StringBuilder line = new StringBuilder("[RET] " + hhmmss(dayMs) + "（" + second + " s）");
					for (final JobTrack track : tracks.values()) {
						line.append(" | ").append(track.jobId).append(' ').append(track.stateText(scheduler))
							.append(" 停=").append(track.maxStationaryMs / 1000).append("s");
					}
					realOutStream.println(line);
				}
				if (allTerminal) {
					break;
				}
			}
		} finally {
			System.setOut(realOut);
		}
		final long wallMillis = (System.nanoTime() - startedAt) / 1_000_000L;
		realOut.println("[RET] 记录到第 " + recordedSeconds + " s（全部终态则提前停）；墙钟 " + wallMillis + " ms（"
			+ Math.round(wallMillis * 100.0 / Math.max(1, recordedSeconds)) / 100.0 + " ms/仿真秒）");

		for (final JobTrack track : tracks.values()) {
			realOut.println("[RET] ---- " + track.jobId + " 最终态=" + track.stateText(scheduler)
				+ " 首发=" + hhmmss(track.startDayMs)
				+ (track.failure == null ? "" : " 失败=" + track.failure));
			realOut.println("[RET]   回库：" + (track.retStartDayMs < 0 ? "没走到回库步" : "开始 " + hhmmss(track.retStartDayMs))
				+ (track.retEndDayMs < 0 ? " → 没到库" : " → 到库 " + hhmmss(track.retEndDayMs) + "（用时 "
					+ (track.retEndDayMs - track.retStartDayMs) / 1000 + " s）")
				+ " 回库段最长静止 " + track.retMaxStationaryMs / 1000 + " s"
				+ "（整段最长静止 " + track.maxStationaryMs / 1000 + " s）"
				+ " 经由点相连轨命中 " + track.viaRailsHit + " 次");
			realOut.println("[RET]   回库轨序（" + track.railSequence.size() + " 根）：");
			for (final String hex : track.railSequence) {
				final Rail rail = org.mtr.core.mmtr.MmtrRunPlanner.railByHex(sim, hex);
				realOut.println("[RET]     " + railText(sim, rail) + (viaRailHexes.contains(hex) ? "   ← 经由点引入线" : ""));
			}
		}
	}

	/** 每份作业单的记录。 */
	private static final class JobTrack {
		final String jobId;
		final int stepCount;
		final long startDayMs;
		final ObjectArrayList<String> railSequence = new ObjectArrayList<>();
		String lastRecordedRailHex;
		String lastRailHex;
		String failure;
		long retStartDayMs = -1;
		long retEndDayMs = -1;
		long doneDayMs = -1;
		long failedDayMs = -1;
		long lastDistanceM = -1;
		long stationarySinceDayMs = -1;
		long maxStationaryMs;
		long retMaxStationaryMs;
		double lastSpeedKmh;
		int viaRailsHit;

		JobTrack(String jobId, int stepCount, long startDayMs) {
			this.jobId = jobId;
			this.stepCount = stepCount;
			this.startDayMs = startDayMs;
		}

		String stateText(MmtrJobScheduler scheduler) {
			return scheduler.stateOf(jobId) + "/步" + scheduler.stepIndexOf(jobId) + "/圈" + scheduler.cyclesOf(jobId);
		}
	}

}
