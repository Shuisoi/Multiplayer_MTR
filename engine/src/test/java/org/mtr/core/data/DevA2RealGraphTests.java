package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.mmtr.signal.MmtrSignalAspect;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2 acceptance on the REAL dev world's rail graph: the aspect view must read occupancy off the real
 * rails a real train stands on - the same projection the ops feed and the in-game renderer consume.
 * Complements the synthetic {@code MmtrSignalAspectTests} (which pin the rules) by proving the wiring
 * against 130+ real rails, real junction leg tables and real siding placement.
 *
 * <p>Skipped when the dev save is absent (the test never invents a world).</p>
 */
public final class DevA2RealGraphTests {

	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
		+ "}";
	private static final Path DEV_MTR_ROOT = Paths.get("C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/saves/新的世界/mtr");

	@Test
	public void theAspectViewReadsOccupancyOnTheRealRailGraph() {
		Assumptions.assumeTrue(Files.isDirectory(DEV_MTR_ROOT), "dev world save not present - skipping");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, DEV_MTR_ROOT, false);
		sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
		sim.mmtrDefaultConsistTypeId = "emu";
		sim.mmtrEnsureSignalColors();

		// A probe train on the first real siding that can size a consist body.
		final ObjectArrayList<VehicleCar> probeCars = new ObjectArrayList<>();
		probeCars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
		final Vehicle[] spawned = {null};
		sim.sidings.forEach(siding -> {
			if (spawned[0] != null) {
				return;
			}
			siding.setVehicleCars(probeCars);
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, sim.mmtrPointBranches, null);
			if (walker == null) {
				return;
			}
			spawned[0] = siding.spawnMmtrMotionVehicle(walker);
		});
		Assumptions.assumeTrue(spawned[0] != null, "no real siding could take a probe train - skipping");
		final Vehicle vehicle = spawned[0];

		// One simulated tick: the train registers its footprint under the rail's signal colour, which is
		// exactly the channel the aspect view reads.
		sim.sidings.forEach(siding -> siding.simulateVehicles(1000, null));
		final String railHex = vehicle.getMmtrMotionWalker().railHex();
		assertNotNull(railHex, "the real train reports the rail it stands on");

		final Map<String, MmtrSignalAspect.Aspect> aspects = sim.mmtrSignalAspectView().aspectsForAllRails();
		assertEquals(sim.rails.size(), aspects.size(), "the aspect view covers every real rail");
		assertTrue(aspects.size() >= 20, "the dev world is a real network, got " + aspects.size() + " rails");
		assertEquals(MmtrSignalAspect.Aspect.RED, aspects.get(railHex), "the real rail the train stands on shows red");

		// A fresh load has no movements: free driving, so no route and no forced-red entry rail.
		assertTrue(sim.mmtrRoutes.snapshot().isEmpty(), "no route on a fresh load");
		assertTrue(sim.mmtrRoutes.pendingEntryRails().isEmpty(), "no pending entry rail either");
		assertTrue(sim.mmtrRoutes.setMainRouteNextRails().isEmpty(), "nothing mirrored to clients");
	}
}
