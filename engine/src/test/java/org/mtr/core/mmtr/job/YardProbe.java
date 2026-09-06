package org.mtr.core.mmtr.job;

import org.junit.jupiter.api.Test;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class YardProbe {
	private static final Path ROOT = Paths.get("C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/saves/新的世界/mtr");

	@Test
	public void probe() {
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(ROOT), "no save");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, ROOT, false);
		sim.depots.forEach(depot -> {
			System.out.println("[YARD] depot=" + depot.getId() + " name=" + depot.getName() + " sidings=" + depot.savedRails.size());
			depot.savedRails.forEach(siding -> {
				final int[] parked = {0};
				siding.iterateVehicles(v -> { if (!v.getIsOnRoute()) parked[0]++; });
				System.out.println("[YARD]   siding=" + siding.getId() + " name=" + siding.getName()
					+ " manual=" + siding.getIsManual() + " maxVeh=" + siding.getMaxVehicles()
					+ " rail=" + Math.round(siding.getRailLength()) + " parked=" + parked[0]);
			});
			depot.routes.forEach(route -> {
				System.out.println("[YARD] route=" + route.getId() + " platforms=" + route.getRoutePlatforms().size());
				route.getRoutePlatforms().forEach(rp -> System.out.println("[YARD]   platform=" + (rp.getPlatform() == null ? "null" : rp.getPlatform().getId())));
			});
		});
		org.junit.jupiter.api.Assertions.assertTrue(true);
	}
}
