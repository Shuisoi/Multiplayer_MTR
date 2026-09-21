package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.manifest.MmtrRollingStockManifest;
import org.mtr.core.operation.MmtrDriveControl;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-server driving fix: rolling-stock manifest stock on a manual siding must spawn in LIVE
 * Motion-Core mode (yard walker, no baked single-yard-rail path) so a driver can actually leave the
 * yard - the legacy manifest car was confined to its 35 m yard rail (parked offset == journey end)
 * and could never drive out. This test applies the manifest exactly like the engine startup/reset
 * and verifies the first template tick stages a motion vehicle that the existing cab control then
 * drives out of the yard onto the network rail.
 */
public final class MmtrManifestMotionSpawnTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
		+ "}";

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/** Depot yard YR (-32..-20) -> mouth -> network rail MA (-20..100). */
	private static final class Net {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-manifest-motion"), false);
		final Position yardBack = new Position(-32, 0, 0);
		final Position yardMouth = new Position(-20, 0, 0);
		final Rail yardRail = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Rail mouthRail = through(yardMouth, new Position(100, 0, 0));
		final Depot depot = new Depot(TransportMode.TRAIN, sim);
		final Siding siding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);

		Net() {
			depot.setName("Yard");
			depot.setCorners(new Position(-40, -5, -5), new Position(-10, 5, 5));
			sim.rails.add(yardRail);
			sim.rails.add(mouthRail);
			sim.depots.add(depot);
			sim.sidings.add(siding);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			assertTrue(depot.savedRails.contains(siding), "siding must attach to the depot yard");
			siding.tick(); // resolve the yard defaultPathData (the engine does this per tick)
			final String manifest = "{"
				+ "\"depots\":[{\"depotId\":\"" + depot.getId() + "\",\"name\":\"Yard\",\"sidings\":[{\"sidingId\":\"" + siding.getId()
				+ "\",\"name\":\"1\",\"cars\":[{\"vehicleId\":\"probe\",\"length\":2,\"width\":1,\"capacity\":10,\"bogie1Position\":0,\"bogie2Position\":1,\"couplingPadding1\":0.1,\"couplingPadding2\":0.1}]}]}]}";
			sim.mmtrRollingStock = MmtrRollingStockManifest.parse(manifest);
		}
	}

	@Test
	public void manifestStockSpawnsAsMotionVehicleAndDrivesOutOfTheYard() {
		final Net n = new Net();
		final int placed = n.sim.mmtrResetAndApplyRollingStock();
		assertEquals(1, placed, "manifest installs stock on the declared siding");

		// First engine tick: the template spawner stages the car in MOTION mode (yard walker).
		n.siding.simulateVehicles(1000, null);
		final Vehicle[] found = {null};
		n.siding.iterateVehicles(vehicle -> found[0] = vehicle);
		assertNotNull(found[0], "manifest stock spawned on the siding");
		final Vehicle vehicle = found[0];
		assertTrue(vehicle.isMmtrMotion(), "manifest manual stock must spawn in live Motion-Core mode, not as a legacy yard-rail car");
		assertTrue(!vehicle.getIsOnRoute(), "spawned car is parked in the yard");
		final double parkedProgress = vehicle.getRailProgress();

		// Client mirror wire prep: the vehicle is marked as a motion mirror, carries the synced leg
		// shadow as its updatable path and reports the run total - so VehicleUpdates can render and
		// replay the live run client-side without the baked-journey end semantics.
		final com.google.gson.JsonObject vehicleJson = org.mtr.core.tool.Utilities.getJsonObjectFromData(vehicle);
		assertTrue(vehicleJson.get("mmtrMotionMirror") != null && vehicleJson.get("mmtrMotionMirror").getAsBoolean(), "motion mirror flag serialized");
		assertTrue(vehicleJson.get("mmtrRunTotalDistance") != null && vehicleJson.get("mmtrRunTotalDistance").getAsDouble() > 0, "run total serialized");
		final org.mtr.core.data.VehicleExtraData syncedCopy = vehicle.vehicleExtraData.copy(0);
		assertTrue(syncedCopy.immutablePath.size() > 0, "synced VED path carries the motion leg shadow (" + syncedCopy.immutablePath.size() + " legs)");

		// Drive with the existing cab control: throttle + forward reverser, like the game client keys.
		// 钥匙归属 (2026-09-10) + 放宽 (2026-09-19): the staged consist holds the engine's system key,
		// which drives nobody and does not replace a driver - but driving no longer REQUIRES the key:
		// riding as the driver is the whole gate (用户口径「操作手柄不需要手里握着钥匙」).
		final UUID driver = UUID.randomUUID();
		assertEquals(org.mtr.core.mmtr.consist.MmtrCabState.KeyHolder.SYSTEM, vehicle.getMmtrCabKeyHolder(), "the yard staged the stock under the system key");
		assertFalse(vehicle.canTakeMmtrControl(driver), "还没坐在司机位上 ⇒ 不能操纵（与钥匙无关）");
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		vehicle.updateRidingEntities(entities);
		assertTrue(vehicle.canTakeMmtrControl(driver), "骑在司机位上就能开，不必先接管 system 钥匙");
		assertTrue(vehicle.enterMmtrCab(org.mtr.core.mmtr.consist.MmtrCabState.Cab.CAB_A, driver), "the crew takes the staged cab from the system key");
		assertEquals(org.mtr.core.mmtr.consist.MmtrCabState.KeyHolder.CREW, vehicle.getMmtrCabKeyHolder());
		assertTrue(vehicle.canTakeMmtrControl(driver));
		new MmtrDriveControl(vehicle.getId(), new ControlState().setThrottleNotch(3).setReverser(1), driver).apply(n.sim);
		assertTrue(vehicle.isMmtrManualOverride(), "cab control holds the override");

		double max = parkedProgress;
		for (int i = 0; i < 600; i++) {
			n.siding.simulateVehicles(1000, null);
			max = Math.max(max, vehicle.getRailProgress());
		}
		final double yardLen = n.yardRail.railMath.getLength();
		assertTrue(max > yardLen + 5, "motion stock must drive out of the yard onto the network rail, progress=" + max + " (yard " + yardLen + " m)");
		assertTrue(vehicle.getIsOnRoute(), "departed vehicle is on route");
		assertEquals(n.mouthRail.getHexId(), vehicle.getMmtrMotionWalker().railHex(), "vehicle runs on the network rail after leaving the yard");
	}

	/**
	 * C6: the manifest (like a job or a template) can now declare the coupling seams of the stock it
	 * stages. Without {@code mmtrCouplerAfter} every multi-car consist spawned from the yard was one
	 * rigid unit (an EMU rake), so the 机辆 pairs in the dev world could not be uncoupled at all.
	 */
	@Test
	public void manifestStockDeclaresItsCouplingSeams() {
		final Net n = new Net();
		final String manifest = "{"
			+ "\"depots\":[{\"depotId\":\"" + n.depot.getId() + "\",\"name\":\"Yard\",\"sidings\":[{\"sidingId\":\"" + n.siding.getId()
			+ "\",\"name\":\"1\",\"cars\":["
			+ "{\"vehicleId\":\"loco\",\"length\":2,\"width\":1,\"capacity\":10,\"bogie1Position\":0,\"bogie2Position\":1,\"couplingPadding1\":0.1,\"couplingPadding2\":0.1,\"powered\":true,\"mmtrCouplerAfter\":true},"
			+ "{\"vehicleId\":\"wagon\",\"length\":2,\"width\":1,\"capacity\":10,\"bogie1Position\":0,\"bogie2Position\":1,\"couplingPadding1\":0.1,\"couplingPadding2\":0.1,\"powered\":false}"
			+ "]}]}]}";
		n.sim.mmtrRollingStock = MmtrRollingStockManifest.parse(manifest);
		assertEquals(1, n.sim.mmtrResetAndApplyRollingStock());
		n.siding.simulateVehicles(1000, null);

		final Vehicle[] found = {null};
		n.siding.iterateVehicles(vehicle -> found[0] = vehicle);
		assertNotNull(found[0], "the two-car consist spawned");
		final Vehicle vehicle = found[0];
		assertEquals(2, vehicle.getVehicleCarsAndPositions().size(), "both manifest cars are in the consist");
		assertTrue(vehicle.getVehicleCarsAndPositions().get(0).left().getMmtrCouplerAfter(), "the declared seam reaches the spawned car");
		assertFalse(vehicle.getVehicleCarsAndPositions().get(1).left().getMmtrCouplerAfter(), "the last car has no coupler after it");
		assertEquals(1, vehicle.getMmtrConsistWalker().body().seamCount(), "exactly one coupling seam");

		final MmtrCoupleSurgery.Result cut = MmtrCoupleSurgery.uncouple(n.sim, vehicle.getId(), 0);
		assertTrue(cut.ok(), "the declared seam is a legal cut: " + cut.reason());
		assertEquals(1, cut.mergedCarCount(), "the head half keeps the locomotive");
	}
}
