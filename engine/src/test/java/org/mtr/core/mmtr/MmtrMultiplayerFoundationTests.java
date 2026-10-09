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
 *   <li>Air-brake state seeding (mirror state handoff) on the controller's BrakeModel.</li>
 *   <li>Occupation rules are enforced by {@link MmtrDriveAccess} (see MmtrDriveAccessTests).</li>
 *   <li>SimRail-style server health watchdog reports live counts and jammed routes
 *       on demand and automatically while the simulator ticks.</li>
 * </ol>
 */
public final class MmtrMultiplayerFoundationTests {

	private static final String CONFIG = "{"
		+ "  \"consistTypes\": ["
		+ "    {\"id\":\"emu\",\"name\":\"EMU\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "     \"maxSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000,"
		+ "     \"airPipeChargeRatePerSecond\":0.1,\"airPipeDischargeRatePerSecond\":0.4,"
		+ "     \"airBrakeApplyRatePerSecond\":0.15,\"airBrakeReleaseRatePerSecond\":0.1},"
		+ "    {\"id\":\"freight\",\"name\":\"Freight\",\"controlMode\":\"AIR_BRAKE\",\"powerNotches\":8,\"brakeNotches\":3,"
		+ "     \"maxSpeedKmh\":100,\"massKg\":60000,\"maxTractiveEffortN\":18000,\"serviceBrakeForceN\":42000,\"emergencyBrakeForceN\":72000,"
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

	/**
	 * 日志降噪 (notes/77): the 5 s recount stays, but an idle simulator prints its summary at most once
	 * a minute - otherwise three simulators put 36 heartbeat lines a minute into the real-machine log.
	 * A non-zero counter is the state an operator must see, so that prints immediately.
	 */
	@Test
	public void testWatchdogHeartbeatIsThrottledButDefectsPrintImmediately() throws Exception {
		final Simulator simulator = createSimulator();
		final java.io.PrintStream originalOut = System.out;
		final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
		try {
			System.setOut(new java.io.PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8));
			simulator.watchdogHealthCheck();
			final int afterFirstCheck = buffer.size();
			assertTrue(afterFirstCheck > 0, "the first health summary is printed");
			simulator.watchdogHealthCheck();
			assertEquals(afterFirstCheck, buffer.size(), "a second idle summary inside the same minute is silent");
			simulator.markRouteJammed(7L);
			simulator.watchdogHealthCheck();
			assertTrue(buffer.size() > afterFirstCheck, "a jammed route prints immediately instead of waiting for the heartbeat");
		} finally {
			System.setOut(originalOut);
		}
	}

	@Test
	public void testVehicleSnapshotMmtrFieldsRoundTrip() throws Exception {
		final Simulator simulator = createSimulator();
		final ObjectArrayList<VehicleCar> vehicleCars = new ObjectArrayList<>();
		vehicleCars.add(new VehicleCar("car_0", 10, 2, 100, 0, 5, 0.5, 0.5));
		final PathData dummyPath = new PathData(new JsonReader(new JsonObject()));
		final VehicleExtraData vehicleExtraData = VehicleExtraData.createWithLegs(
			0, 0, 10, vehicleCars, ObjectArrayList.wrap(new PathData[]{dummyPath}), false, 0, 10000
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
		// 力模型口径（notes/235）：镜像带的是质量/牵引力/功率/制动力，不再是"加速度常数"。
		assertTrue(snapshot.has("mmtrMassKg"), "consist parameters should also be mirrored on the wire");
		assertTrue(snapshot.has("mmtrMaxTractiveEffortN"));
		assertTrue(snapshot.has("mmtrMaxPowerW"));
		assertTrue(snapshot.has("mmtrServiceBrakeForceN"));
		assertEquals(60_000, snapshot.get("mmtrMassKg").getAsDouble(), 1e-9, "the generic-vehicle mass mirrors when no consist type resolves");
	}

	@Test
	public void testAirBrakeStateSeeding() {
		/*
		 * notes/376：旧 AirBrakeController 已删除，气压种子现在由控制器自己持有的
		 * {@link org.mtr.core.mmtr.brake.BrakeModel} 负责 —— 归一化 0…1（镜像/HUD 口径）与逐车 bar
		 * 状态串是同一件事的两种写法，比例按**车底口径**折成 bar。
		 */
		final NotchedDriveController controller = new NotchedDriveController();
		controller.setState(0.4, 0.25);
		assertEquals(0.4, controller.getPipePressure(), 1e-9);
		assertEquals(0.25, controller.getBrakeCylinderPressure(), 1e-9);
		// 逐车状态串种子：0.4 × 充风 5.2 bar = 2.08 bar、0.25 × 缸压上限 3.8 bar = 0.95 bar
		final org.mtr.core.mmtr.brake.BrakeModel model = controller.getBrakeModel();
		model.applyState("0.4,0.25");
		// dt = 0：只把系统建起来（把种子灌进去），不推进这一拍
		model.step(ConsistType.DEFAULT_BRAKES, null, org.mtr.core.mmtr.brake.BrakeCommand.coast(), 0, 0, 0);
		assertEquals(0.4 * ConsistType.DEFAULT_BRAKES.getChargedBar(), model.getPipeBar(), 1e-9, "管压比例 × 充风值");
		assertEquals(0.25 * ConsistType.DEFAULT_BRAKES.getCylinderMaxBar(), model.getCylinderBar(), 1e-9, "缸压比例 × 缸压上限");
		assertEquals(0.4, controller.getPipePressure(), 1e-9, "两种写法读回来必须是同一个数");
		assertEquals(0.25, controller.getBrakeCylinderPressure(), 1e-9);
		controller.reset();
		assertEquals(1.0, controller.getPipePressure(), 1e-9);
		assertEquals(0.0, controller.getBrakeCylinderPressure(), 1e-9);
	}
}