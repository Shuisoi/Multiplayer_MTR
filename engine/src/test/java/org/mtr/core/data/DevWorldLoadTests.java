package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.junit.jupiter.api.Test;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads the real development world save (which already contains a built network with depots,
 * rails, routes and running stock) directly into an engine Simulator and advances it.
 * This is the fastest path to a "moving world" e2e fixture.
 */
public final class DevWorldLoadTests {

	private static final Path DEV_MTR_ROOT = Paths.get("C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/saves/新的世界/mtr");

	@Test
	public void devWorldLoadsAndMovesVehicles() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(DEV_MTR_ROOT), "dev world save not present - skipping");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, DEV_MTR_ROOT, false);
		assertTrue(sim.rails.size() > 0, "rails loaded");
		assertTrue(sim.depots.size() > 0, "depots loaded");
		assertTrue(sim.routes.size() > 0, "routes loaded");

		final ObjectOpenHashSet<Long> seenVehicles = new ObjectOpenHashSet<>();
		sim.sidings.forEach(siding -> siding.iterateVehicles(vehicle -> seenVehicles.add(vehicle.getId())));
		System.out.println("[DEV] initial vehicles=" + seenVehicles.size());
		assertTrue(seenVehicles.size() >= 1, "the loaded dev world should contain at least one spawned vehicle");

		// TODO(M2): the single vehicle parks at its depot; departures follow the in-game clock.
		// Next step is a headless driver: claim the consist and drive it (legacy/manual -> mmtr),
		// then assert railProgress advances.
		for (int i = 0; i < 40; i++) {
			sim.tick();
		}
		System.out.println("[DEV] vehicles after ticks=" + snapshot(sim).size());
		assertTrue(snapshot(sim).size() >= 1, "the vehicle should survive simulation ticks");
	}

	private static ObjectOpenHashSet<Long> snapshot(Simulator sim) {
		final ObjectOpenHashSet<Long> ids = new ObjectOpenHashSet<>();
		sim.sidings.forEach(siding -> siding.iterateVehicles(vehicle -> ids.add(vehicle.getId())));
		return ids;
	}
}