package org.mtr.core.mmtr.sim;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Station;
import org.mtr.core.mmtr.job.MmtrConsistJob;
import org.mtr.core.mmtr.job.MmtrJobStep;
import org.mtr.core.mmtr.plan.MmtrRailDistance;
import org.mtr.core.simulation.Simulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

/**
 * P0 探针（作业单仿真器可行性闸门的第一步）：把**当前 dev 世界**的现场清单打出来 ——
 * 几条轨、几个站/台、几个车辆段/股道、库里有没有车、有没有作业单。
 *
 * <p>为什么必须先探针：P0 要量"一天跑不跑得动"，而负载（几列车同时动）必须用**这个世界的真实 id**
 * 造出来；notes/171 那份 {@code TempSetupLoopJobTests} 把站台 id、折返轨坐标全写死在**旧世界**上，
 * 环线北段那一轮施工（notes/301–326）之后大概率已经对不上。</p>
 *
 * <p>规矩：**只用副本**。世界先整份复制到 {@code build/mmtr-sim-probe/}，探针在副本上开
 * {@link Simulator}，线上存档一个字节都不动（notes/171 的同类工具当年就直接开了线上目录，
 * 这里不再重复那个风险）。</p>
 */
public final class TempSimProbeTests {

	/** 副本根：与线上 world/mtr 同层级（里面直接是 minecraft/overworld）。 */
	private static final Path COPY = Paths.get("build", "mmtr-sim-probe", "world");

	/** 线上世界目录：从测试工作目录（mmtr/engine）往上找 mmtr/game/fabric/run/world/mtr。 */
	public static Path liveWorld() {
		final Path workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
		final Path[] candidates = {
			workingDirectory.resolve(Paths.get("..", "game", "fabric", "run", "world", "mtr")),
			workingDirectory.resolve(Paths.get("game", "fabric", "run", "world", "mtr")),
			workingDirectory.resolve(Paths.get("..", "..", "game", "fabric", "run", "world", "mtr")),
		};
		for (final Path candidate : candidates) {
			if (Files.isDirectory(candidate.resolve("minecraft"))) {
				return candidate.normalize();
			}
		}
		return candidates[0].normalize();
	}

	public static void deleteRecursively(Path path) throws IOException {
		if (!Files.exists(path)) {
			return;
		}
		try (final var stream = Files.walk(path)) {
			for (final Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	/** 整份复制世界（只读使用：仿真永远跑在副本上，线上存档不动）。 */
	public static void copyRecursively(Path from, Path to) throws IOException {
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

	static Simulator openCopy() throws IOException {
		final Path live = liveWorld();
		deleteRecursively(COPY);
		copyRecursively(live, COPY);
		return new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, COPY, false);
	}

	private static String endText(Rail rail) {
		if (rail == null) {
			return "（无图轨）";
		}
		final Position[] ordered = rail.mmtrOrderedPositions();
		return "(" + ordered[0].getX() + "," + ordered[0].getZ() + ")→(" + ordered[1].getX() + "," + ordered[1].getZ()
			+ ") 长" + Math.round(rail.railMath.getLength());
	}

	@Test
	public void probe() throws IOException {
		final Path live = liveWorld();
		Assumptions.assumeTrue(Files.isDirectory(live), "dev world save not present: " + live);
		System.out.println("[P0] 线上世界 = " + live);

		final Simulator sim = openCopy();
		System.out.println("[P0] 副本 = " + COPY.toAbsolutePath());
		System.out.println("[P0] rails=" + sim.rails.size() + " stations=" + sim.stations.size() + " platforms=" + sim.platforms.size()
			+ " sidings=" + sim.sidings.size() + " depots=" + sim.depots.size());

		System.out.println("[P0] ---- 车站 ----");
		for (final Station station : sim.stations) {
			int platformCount = 0;
			for (final Platform platform : sim.platforms) {
				if (platform.area != null && platform.area.getId() == station.getId()) {
					platformCount++;
				}
			}
			System.out.println("[P0] 站 " + station.getName() + " id=" + station.getId() + " 台数=" + platformCount);
		}

		System.out.println("[P0] ---- 站台 ----");
		for (final Platform platform : sim.platforms) {
			final Rail rail = MmtrRailDistance.railOfPlatform(sim, platform.getId());
			System.out.println("[P0] 台 " + (platform.area == null ? "?" : platform.area.getName()) + "/" + platform.getName()
				+ " id=" + platform.getId() + " 停留=" + platform.getDwellTime() + " 图轨=" + endText(rail));
		}

		System.out.println("[P0] ---- 车辆段与股道 ----");
		for (final var depot : sim.depots) {
			System.out.println("[P0] 段 " + depot.getName() + " id=" + depot.getId());
		}
		for (final Siding siding : sim.sidings) {
			final Rail rail = MmtrRailDistance.railOfSiding(sim, siding.getId());
			final ObjectArrayList<String> cars = new ObjectArrayList<>();
			siding.getVehicleCars().forEach(car -> cars.add(car.getVehicleId()));
			System.out.println("[P0] 股道 " + (siding.area == null ? "?" : siding.area.getName()) + "/" + siding.getName()
				+ " id=" + siding.getId() + " 长=" + Math.round(siding.getRailLength()) + " 车=" + cars + " 图轨=" + endText(rail));
		}

		System.out.println("[P0] ---- 可折返/尽头的图轨（候选折返点）----");
		for (final Rail rail : sim.rails) {
			if (rail.canTurnBack() || rail.isSiding()) {
				final Position[] ordered = rail.mmtrOrderedPositions();
				System.out.println("[P0] 轨 " + ordered[0].getX() + "," + ordered[0].getZ() + " → " + ordered[1].getX() + "," + ordered[1].getZ()
					+ " 长" + Math.round(rail.railMath.getLength()) + " 股道=" + rail.isSiding() + " 站台=" + rail.isPlatform()
					+ " 可折返=" + rail.canTurnBack() + " hex=" + rail.getHexId());
			}
		}

		System.out.println("[P0] ---- 作业单 ----");
		if (sim.mmtrJobRegistry.jobs.isEmpty()) {
			System.out.println("[P0] （空）—— 服务器当前没有作业单");
		}
		for (final MmtrConsistJob job : sim.mmtrJobRegistry.jobs) {
			System.out.println("[P0] 作业 " + job.jobId + " 股道=" + job.sidingId + " 起始=" + job.startTimeOfDayMs
				+ " repeatDaily=" + job.repeatDaily + " loop=" + job.loop + " loopEveryMs=" + job.loopEveryMs
				+ " 车=" + job.cars.size() + " 步=" + job.steps.size());
			for (final MmtrJobStep step : job.steps) {
				System.out.println("[P0]    " + step.stepId + " " + step.type + " 目标=" + step.targetId
					+ " 轨=" + step.targetRailHex + "/" + step.targetRailFraction + " due=" + step.dueTimeOfDayMs + " " + step.note);
			}
		}
	}
}
