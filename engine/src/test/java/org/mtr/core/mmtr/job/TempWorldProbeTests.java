package org.mtr.core.mmtr.job;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Station;
import org.mtr.core.mmtr.plan.MmtrRailDistance;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 临时探针（用完即删）：把真实世界里与"1→3 站循环 + 两端折返"有关的现场数据全打出来。 */
public final class TempWorldProbeTests {

	private static final Path ROOT = Paths.get("C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/world/mtr");

	private static String endText(Rail rail) {
		if (rail == null) {
			return "（无图轨）";
		}
		final Position[] ordered = rail.mmtrOrderedPositions();
		return "(" + ordered[0].getX() + "," + ordered[0].getZ() + ")→(" + ordered[1].getX() + "," + ordered[1].getZ() + ") 长" + Math.round(rail.railMath.getLength());
	}

	@Test
	public void probe() {
		Assumptions.assumeTrue(Files.isDirectory(ROOT), "dev world save not present");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, ROOT, false);
		System.out.println("[W] rails=" + sim.rails.size() + " stations=" + sim.stations.size() + " platforms=" + sim.platforms.size()
			+ " sidings=" + sim.sidings.size() + " depots=" + sim.depots.size());

		for (final Station station : sim.stations) {
			System.out.println("[W] 站 " + station.getName() + " id=" + station.getId());
		}
		System.out.println("[W] ---- 站台 ----");
		for (final Platform platform : sim.platforms) {
			final Rail rail = MmtrRailDistance.railOfPlatform(sim, platform.getId());
			System.out.println("[W] 站台 " + (platform.area == null ? "?" : platform.area.getName()) + "/" + platform.getName()
				+ " id=" + platform.getId() + " 停留=" + platform.getDwellTime() + " 图轨=" + endText(rail));
		}
		System.out.println("[W] ---- 车辆段与股道 ----");
		sim.depots.forEach(depot -> System.out.println("[W] 段 " + depot.getName() + " id=" + depot.getId()));
		for (final Siding siding : sim.sidings) {
			final Rail rail = MmtrRailDistance.railOfSiding(sim, siding.getId());
			System.out.println("[W] 股道 " + (siding.area == null ? "?" : siding.area.getName()) + "/" + siding.getName()
				+ " id=" + siding.getId() + " 长=" + Math.round(siding.getRailLength()) + " 车=" + sim.countVehiclesOnSiding(siding.getId())
				+ " 图轨=" + endText(rail));
		}
		System.out.println("[W] ---- 折返点周围 ----");
		final long[][] nodes = {{-176, -60, -253}, {-176, -60, -222}, {-176, -60, -564}, {-176, -60, -541}, {-176, -60, -289}, {-170, -60, -289}, {-170, -60, -511}, {-170, -60, -541}};
		for (final long[] node : nodes) {
			final Position position = new Position(node[0], node[1], node[2]);
			final var neighbours = sim.positionsToRail.get(position);
			final StringBuilder text = new StringBuilder();
			if (neighbours != null) {
				for (final Rail rail : neighbours.values()) {
					text.append(" | ").append(endText(rail));
				}
			}
			System.out.println("[W] 节点 (" + node[0] + "," + node[2] + ") 邻轨=" + (neighbours == null ? 0 : neighbours.size()) + text);
		}
		System.out.println("[W] ---- 折返点附近轨道（x-186..-166, z-600..-150）----");
		for (final Rail rail : sim.rails) {
			final Position[] ordered = rail.mmtrOrderedPositions();
			final boolean near = ordered[0].getX() >= -186 && ordered[0].getX() <= -166 && ordered[0].getZ() >= -600 && ordered[0].getZ() <= -150
				|| ordered[1].getX() >= -186 && ordered[1].getX() <= -166 && ordered[1].getZ() >= -600 && ordered[1].getZ() <= -150;
			if (near) {
				System.out.println("[W] 轨 " + ordered[0].getX() + "," + ordered[0].getZ() + " → " + ordered[1].getX() + "," + ordered[1].getZ()
					+ " 长" + Math.round(rail.railMath.getLength()) + " 股道=" + rail.isSiding() + " 站台=" + rail.isPlatform()
					+ " 可折返=" + rail.canTurnBack() + " hex=" + rail.getHexId());
			}
		}
		System.out.println("[W] ---- 库里的车 ----");
		for (final Siding siding : sim.sidings) {
			final ObjectArrayList<String> cars = new ObjectArrayList<>();
			siding.getVehicleCars().forEach(car -> cars.add(car.getVehicleId()));
			if (!cars.isEmpty()) {
				System.out.println("[W] 股道 " + (siding.area == null ? "?" : siding.area.getName()) + "/" + siding.getName() + " 车=" + cars);
			}
		}
		System.out.println("[W] ---- 作业单 ----");
		for (final MmtrConsistJob job : sim.mmtrJobRegistry.jobs) {
			System.out.println("[W] 作业 " + job.jobId + " 股道=" + job.sidingId + " 起始=" + job.startTimeOfDayMs
				+ " repeatDaily=" + job.repeatDaily + " loop=" + job.loop + " loopEveryMs=" + job.loopEveryMs + " 车=" + job.cars.size() + " 步=" + job.steps.size());
			for (final MmtrJobStep step : job.steps) {
				System.out.println("[W]    " + step.stepId + " " + step.type + " 目标=" + step.targetId + " due=" + step.dueTimeOfDayMs + " " + step.note);
			}
		}
	}
}
