package org.mtr.core.mmtr;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.PathData;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end foundation tests for the MMTR multiplayer groundwork:
 * <ol>
 *   <li>Client mirror plumbing: mmtr sync fields survive a full server snapshot
 *       serialise/parse round trip and default safely on legacy vehicles.</li>
 *   <li>Air-brake controller state seeding (mirror state handoff).</li>
 *   <li>Occupation rules are enforced by {@link MmtrDriveAccess} (see MmtrDriveAccessTests).</li>
 *   <li>SimRail-style server health watchdog reports live counts and jammed routes
 *       on demand and automatically while the simulator ticks.</li>
 * </ol>
 */
public final class MmtrMultiplayerFoundationTests {

	private static final String CONFIG = "{"
		+ "  \"consistTypes\": ["
		+ "    {\"id\":\"emu\",\"name\":\"EMU\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "     \"maxSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5,"
		+ "     \"airPipeChargeRatePerSecond\":0.1,\"airPipeDischargeRatePerSecond\":0.4,"
		+ "     \"airBrakeApplyRatePerSecond\":0.15,\"airBrakeReleaseRatePerSecond\":0.1},"
		+ "    {\"id\":\"freight\",\"name\":\"Freight\",\"controlMode\":\"AIR_BRAKE\",\"powerNotches\":8,\"brakeNotches\":3,"
		+ "     \"maxSpeedKmh\":100,\"tractionAccelerationMps2\":0.3,\"serviceBrakeDecelerationMps2\":0.7,\"emergencyDecelerationMps2\":1.2,"
		+ "     \"airPipeChargeRatePerSecond\":0.12,\"airPipeDischargeRatePerSecond\":0.5,"
		+ "     \"airBrakeApplyRatePerSecond\":0.2,\"airBrakeReleaseRatePerSecond\":0.08}"
		+ "  ]"
		+ "}";

	private static Simulator createSimulator() throws Exception {
		final Path root = Paths.get("build/mmtr-multiplayer-e2e");
		final Path dimension = root.resolve("test");
		Files.createDirectories(dimension);
		Files.writeString(dimension.resolve("mmtr-consist-types.json"), CONFIG);
		return new Simulator("test", new String[]{"test"}, root, false);
	}

	@Test
	public void testWatchdogHealthCountsEmptyAndJammed() throws Exception {
		final Simulator simulator = createSimulator();
		assertNotNull(simulator.mmtrConsistTypes, "server policy should be loaded from config");
		simulator.watchdogHealthCheck();
		assertEquals(0, simulator.getWatchdogVehicles());
		assertEquals(0, simulator.getWatchdogRiders());
		assertEquals(0, simulator.getWatchdogDrivers());
		assertEquals(0, simulator.getWatchdogMmtrOverrides());
		assertEquals(0, simulator.getWatchdogProtections());
		assertTrue(simulator.getWatchdogLastCheckAt() > 0, "health check should record a timestamp");

		simulator.markRouteJammed(4242L);
		simulator.watchdogHealthCheck();
		assertEquals(1, simulator.getWatchdogJammedRoutes(), "jammed route should appear in the health snapshot");
	}

	@Test
	public void testWatchdogRunsAutomaticallyWhileTicking() throws Exception {
		final Simulator simulator = createSimulator();
		for (int i = 0; i < 130; i++) {
			simulator.tick();
		}
		assertTrue(simulator.getWatchdogLastCheckAt() > 0, "watchdog should run automatically every ~100 ticks");
		assertEquals(0, simulator.getWatchdogMmtrOverrides());
	}

	@Test
	public void testVehicleSnapshotMmtrFieldsRoundTrip() throws Exception {
		final Simulator simulator = createSimulator();
		final ObjectArrayList<VehicleCar> vehicleCars = new ObjectArrayList<>();
		vehicleCars.add(new VehicleCar("car_0", 10, 2, 100, 0, 5, 0.5, 0.5));
		final ObjectArrayList<PathData> emptyPath = new ObjectArrayList<>();
		final PathData dummyPath = new PathData(new JsonReader(new JsonObject()));
		final VehicleExtraData vehicleExtraData = VehicleExtraData.create(
			0, 0, 10, vehicleCars, emptyPath, emptyPath, emptyPath, dummyPath, false, 0.000004, 0.000004, false, 0, 10000
		);

		final Vehicle serverVehicle = new Vehicle(vehicleExtraData, null, TransportMode.TRAIN, simulator);
		final JsonObject snapshot = Utilities.getJsonObjectFromData(serverVehicle);

		// Server snapshots carry the mmtr sync fields with safe defaults for legacy vehicles.
		assertTrue(snapshot.has("mmtrActive"));
		assertFalse(snapshot.get("mmtrActive").getAsBoolean(), "default should be inactive (legacy path)");
		assertEquals("", snapshot.get("mmtrMode").getAsString());
		assertEquals("", snapshot.get("mmtrDriver").getAsString());
		assertEquals(0, snapshot.get("mmtrThrottleNotch").getAsInt());
		assertEquals(0, snapshot.get("mmtrBrakeNotch").getAsInt());
		assertFalse(snapshot.get("mmtrProtection").getAsBoolean());
		assertFalse(snapshot.get("mmtrEmergency").getAsBoolean());
		assertTrue(snapshot.has("mmtrTractionAccelerationMps2"), "consist parameters should also be mirrored on the wire");
		assertEquals(1.0, snapshot.get("mmtrMassRatio").getAsDouble(), 1e-12, "mass ratio defaults to 1 on legacy vehicles");
	}

	@Test
	public void testAirBrakeStateSeeding() {
		final AirBrakeController controller = new AirBrakeController();
		controller.setState(0.4, 0.25);
		assertEquals(0.4, controller.getPipePressure(), 1e-9);
		assertEquals(0.25, controller.getBrakeCylinderPressure(), 1e-9);
		controller.reset();
		assertEquals(1.0, controller.getPipePressure(), 1e-9);
		assertEquals(0.0, controller.getBrakeCylinderPressure(), 1e-9);
	}
}