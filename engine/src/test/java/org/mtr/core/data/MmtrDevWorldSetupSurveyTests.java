package org.mtr.core.data;

import org.junit.jupiter.api.Test;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One-shot survey of the CURRENT dev-server world (run/world): depot yards (siding rail lengths)
 * and platform ids - used to author a rolling-stock manifest that fits the siding lengths.
 * Gated on the live world directory being present.
 */
public final class MmtrDevWorldSetupSurveyTests {

	private static final Path DEV_WORLD_MTR_ROOT = Paths.get("C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/world/mtr");

	@Test
	public void surveyLiveWorldDepotsAndPlatforms() {
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(DEV_WORLD_MTR_ROOT), "live dev world not present - skipping");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, DEV_WORLD_MTR_ROOT, false);
		assertTrue(sim.rails.size() > 0, "rails loaded");

		System.out.println("[SETUP] rails=" + sim.rails.size() + " depots=" + sim.depots.size() + " sidings=" + sim.sidings.size() + " platforms=" + sim.platforms.size());
		for (final Depot depot : sim.depots) {
			System.out.println("[SETUP] depot id=" + depot.getId() + " name=" + depot.getName() + " savedRails=" + depot.savedRails.size());
			for (final Siding siding : depot.savedRails) {
				System.out.println("[SETUP]   siding id=" + siding.getId() + " name='" + siding.getName() + "' railLength=" + Math.round(siding.getRailLength() * 10.0) / 10.0);
			}
		}
		for (final Platform platform : sim.platforms) {
			System.out.println("[SETUP] platform id=" + platform.getId() + " name=" + platform.getName());
		}
	}
}
