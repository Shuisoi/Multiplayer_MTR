package org.mtr.core.mmtr.job;

import org.junit.jupiter.api.Test;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2-Core L0 real-save probe: the two auto sidings (1 and 2) of the dev depot must be mutually reachable. */
public final class DevWorldRouterProbeTests {
	private static final Path DEV_MTR_ROOT = Paths.get("C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/saves/新的世界/mtr");
	private static final long SIDING_1 = 7707385017525480299L;
	private static final long SIDING_2 = -8058689381957463612L;

	@Test
	public void realYardSidingsAreMutuallyReachable() {
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(DEV_MTR_ROOT), "dev world save not present - skipping");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, DEV_MTR_ROOT, false);
		org.mtr.core.data.Depot.generateDepots(sim, new it.unimi.dsi.fastutil.objects.ObjectArrayList<>(sim.depots));
		for (int i = 0; i < 300; i++) { sim.step(1000); }
		sim.sidings.forEach(s -> { if (s.getId() == SIDING_1 || s.getId() == SIDING_2) {
			System.out.println("[ROUTE] siding=" + s.getId() + " hasOut=" + s.hasPathToMainRoute() + " hasReturn=" + s.hasReturnFromMainRoute() + " rail=" + Math.round(s.getRailLength()));
		} });
		assertTrue(MmtrMotionRouter.canReachSiding(sim, SIDING_1, SIDING_2), "siding 1 must be able to plan a leg to siding 2");
		assertTrue(MmtrMotionRouter.canReachSiding(sim, SIDING_2, SIDING_1), "siding 2 must be able to plan a leg to siding 1");
	}
}