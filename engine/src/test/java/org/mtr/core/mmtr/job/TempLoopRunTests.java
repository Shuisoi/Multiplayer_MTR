package org.mtr.core.mmtr.job;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.simulation.Simulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

/**
 * 临时验证（用完即删）：把真实世界**复制到临时目录**后离线跑循环作业单，逐秒推进仿真，
 * 观察那辆车是否真的出库 → 1/1 → 2/1 → 3/1 → 南折返 → 换端 → 3/2 → 2/2 → 1/2 → 北折返 → 换端 → 循环。
 *
 * <p>不碰线上存档（复制一份跑），所以可以放心连跑 20 分钟仿真时间。</p>
 */
public final class TempLoopRunTests {

	/**
	 * 验证用的世界源：**改动前的备份**（`mtr.bak-20260916-loop`）—— 里面没有我已经写进去的作业单、
	 * 也没有现场那些停在半路的车，所以三班车的仿真从干净状态开始（作业单由工具在副本里现装）。
	 */
	private static final Path SRC = Paths.get("C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/world/mtr.bak-20260916-loop");
	/** 复制出来的世界根目录：里面直接是 minecraft/overworld（和线上 world/mtr 一个层级）。 */
	private static final Path DST = Paths.get(System.getProperty("java.io.tmpdir"), "mmtr-looprun");
	private static final String JOB = "TT-LOOP-1-3";
	private static final int SECONDS = 1_600;

	private static void deleteRecursively(Path path) throws IOException {
		if (!Files.exists(path)) {
			return;
		}
		try (final var stream = Files.walk(path)) {
			for (final Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	private static void copyRecursively(Path from, Path to) throws IOException {
		try (final var stream = Files.walk(from)) {
			for (final Path p : stream.toList()) {
				final Path target = to.resolve(from.relativize(p).toString());
				if (Files.isDirectory(p)) {
					Files.createDirectories(target);
				} else {
					Files.createDirectories(target.getParent());
					Files.copy(p, target);
				}
			}
		}
	}

	private static String fleetText(Simulator sim) {
		final StringBuilder text = new StringBuilder();
		for (final Siding siding : sim.sidings) {
			siding.iterateVehicles(vehicle -> appendVehicle(sim, siding, vehicle, text));
		}
		return text.isEmpty() ? "（无车）" : text.toString();
	}

	private static String railText(Simulator sim, String hex) {
		final org.mtr.core.data.Rail rail = sim.railIdMap.get(hex);
		if (rail == null) {
			return hex;
		}
		final org.mtr.core.data.Position[] ordered = rail.mmtrOrderedPositions();
		return "(" + ordered[0].getX() + "," + ordered[0].getZ() + ")→(" + ordered[1].getX() + "," + ordered[1].getZ() + ")";
	}

	private static void appendVehicle(Simulator sim, Siding siding, Vehicle vehicle, StringBuilder text) {
		final var walker = vehicle.getMmtrMotionWalker();
		final var mission = vehicle.getMmtrMission();
		final double[] frame = vehicle.mmtrTravelFrame();
		text.append("\n      · ").append(siding.getName()).append("(").append(siding.getId()).append(")")
			.append(" 车速=").append(Math.round(vehicle.getSpeed() * 100) / 100.0)
			.append(" 在途=").append(vehicle.getIsOnRoute())
			.append(" 移动=").append(vehicle.isMoving())
			.append(" 车列长=").append(Math.round(vehicle.vehicleExtraData.getTotalVehicleLength()))
			.append(" 帧=").append(frame == null ? "-" : Math.round(frame[0] * 10) / 10.0 + "/" + Math.round(frame[1] * 10) / 10.0)
			.append(" 走行轨=").append(walker == null ? "-" : railText(sim, walker.railHex()))
			.append(" 距离=").append(walker == null ? "-" : Math.round(walker.distanceM() * 10) / 10.0)
			.append(" 偏移=").append(walker == null ? "-" : Math.round(walker.offsetM() * 10) / 10.0)
			.append(" 里程=").append(Math.round(vehicle.getRailProgress() * 10) / 10.0)
			.append(" 任务=").append(mission == null ? "-" : mission.getState());
		if (mission != null) {
			// 任务卡的哪一步、目标是什么、任务类型是不是"原地动作"、目标轨与车下的轨是否同一根 —— 卡住时全靠这几项。
			final long targetId = mission.getTargetSidingId();
			final org.mtr.core.data.Rail targetRail = org.mtr.core.mmtr.MmtrRunPlanner.findSavedRailRail(sim, targetId);
			text.append(" 类型=").append(mission.getKind())
				.append(" 目标轨=").append(targetRail == null ? "-" : railText(sim, targetRail.getHexId()))
				.append(" 原地=").append(mission.isInPlace())
				.append(" 任务类=").append(mission.getTask() == null ? "-" : mission.getTask().kind())
				.append(" 在同轨=").append(targetRail != null && walker != null && targetRail.getHexId().equals(walker.railHex()))
				.append(" 失败=").append(mission.getFailureReason());
		}
	}

	private static void report(Simulator sim, int second) {
		final MmtrJobScheduler scheduler = sim.mmtrJobScheduler;
		final StringBuilder text = new StringBuilder("[RUN] t=" + second + "s");
		for (final MmtrConsistJob job : sim.mmtrJobRegistry.jobs) {
			text.append(" | ").append(job.jobId).append(" 状态=").append(scheduler.stateOf(job.jobId))
				.append(" 步=").append(scheduler.stepIndexOf(job.jobId))
				.append(" 圈=").append(scheduler.cyclesOf(job.jobId))
				.append(" 失败=").append(scheduler.failureOf(job.jobId));
		}
		System.out.println(text.append(fleetText(sim)));
	}

	/** 卡住时把引擎自己的诊断（point why / point locks）和车辆手上的道岔申请打出来。 */
	private static void diagnose(Simulator sim, int second) {
		System.out.println("[DIAG] ==== t=" + second + "s ====");
		for (final Siding siding : sim.sidings) {
			siding.iterateVehicles(vehicle -> {
				if (vehicle.getMmtrMission() != null) {
					System.out.println("[DIAG] 车 " + vehicle.getId() + " 手上道岔申请=" + vehicle.getMmtrPendingPointOps()
						+ " 停在信号前=" + vehicle.getMmtrMotionWalker().haltedAtAuthority()
						+ " 锚点=" + vehicle.hasMmtrMotionStopAnchor());
				}
			});
		}
		for (final String command : new String[]{"point locks", "point why -176 -60 -253", "point why -176 -60 -306", "point why -176 -60 -289"}) {
			final org.mtr.core.mmtr.command.MmtrCommandDispatcher.Result result = org.mtr.core.mmtr.command.MmtrCommandDispatcher.execute(sim, command);
			System.out.println("[DIAG] $ " + command + " -> ok=" + result.ok);
			result.lines.forEach(line -> System.out.println("[DIAG]   " + line));
		}
	}

	@Test
	public void run() throws IOException {
		Assumptions.assumeTrue(Files.isDirectory(SRC), "dev world save not present");
		deleteRecursively(DST);
		copyRecursively(SRC, DST);
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, DST, false);
		System.out.println("[RUN] 复制世界到 " + DST + " rails=" + sim.rails.size() + " sidings=" + sim.sidings.size()
			+ " jobs=" + sim.mmtrJobRegistry.jobs.size() + " 调度器=" + (sim.mmtrJobScheduler != null));
		// 副本里装上本轮这三班错开时间的循环作业单（备份世界本身没有作业单，所以调度器要等装完才有）。
		for (final MmtrConsistJob job : new java.util.ArrayList<>(sim.mmtrJobRegistry.jobs)) {
			sim.mmtrJobRegistry.jobs.remove(job);
		}
		for (final MmtrConsistJob job : TempSetupLoopJobTests.buildFleet(sim)) {
			sim.upsertMmtrJob(job);
		}
		System.out.println("[RUN] 装好作业单，调度器=" + (sim.mmtrJobScheduler != null));
		Assumptions.assumeTrue(sim.mmtrJobScheduler != null, "no job scheduler");
		// 现场有 15 把人工锁：车会停在出发信号前等 point unlock。验证时先全解（只动副本）。
		System.out.println("[RUN] 清掉人工锁 " + sim.mmtrUnlockAllPoints() + " 把");

		for (int second = 1; second <= SECONDS; second++) {
			sim.step(1_000);
			if (second % 15 == 0) {
				report(sim, second);
			}
			// 有车长时间停着不动（可能被人工锁/道岔扣住）就问引擎自己：卡在哪把道岔上。
			if (second > 300 && second % 120 == 0) {
				diagnose(sim, second);
			}
		}
	}
}
