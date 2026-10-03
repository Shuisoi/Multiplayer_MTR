package org.mtr.core.data;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.mmtr.signal.MmtrShuntAuthority;
import org.mtr.core.mmtr.signal.MmtrShuntAuthority.Kind;
import org.mtr.core.operation.MmtrDriveControl;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.JsonWriter;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C3b: two trains on one rail (同轨多车).
 *
 * <p>The coupling approach ends with a locomotive standing behind a stabled rake on the same rail.
 * Three things must hold for that state to be real rather than cosmetic: the stabled rake must be
 * visible in the occupancy tree (otherwise the movement - or any other train - would drive straight
 * through it), both trains must survive the yard's one-parked-train rule while the authority is live,
 * and the saved world must carry both of them back.</p>
 */
public final class MmtrMultiVehiclePerRailTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000}]"
		+ "}";

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> newTrees() {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = new ObjectArrayList<>();
		trees.add(new Object2ObjectAVLTreeMap<>());
		trees.add(new Object2ObjectAVLTreeMap<>());
		return trees;
	}

	/** Y1 (-12..0, siding 1: the rake) -> MA (0..16) -> PL (16..60); Y2 (-30..-18, siding 2: the loco) -> X2 (-18..-12) -> Y1. */
	private static final class Net {
		final Simulator sim;
		final Position y2Back = new Position(-30, 0, 0);
		final Position y2Mouth = new Position(-18, 0, 0);
		final Position y1Back = new Position(-12, 0, 0);
		final Position y1Mouth = new Position(0, 0, 0);
		final Rail y2;
		final Rail x2;
		final Rail y1;
		final Rail ma;
		final Rail pl;
		final Depot depot;
		final Siding siding1;
		final Siding siding2;
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = newTrees();

		Net(String savePath) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			y2 = Rail.newSidingRail(y2Back, Angle.fromAngle(0), y2Mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			x2 = through(y2Mouth, y1Back);
			y1 = Rail.newSidingRail(y1Back, Angle.fromAngle(0), y1Mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			ma = through(y1Mouth, new Position(16, 0, 0));
			pl = through(new Position(16, 0, 0), new Position(60, 0, 0));
			depot = new Depot(TransportMode.TRAIN, sim);
			siding1 = new Siding(y1Back, y1Mouth, 12, TransportMode.TRAIN, sim);
			siding2 = new Siding(y2Back, y2Mouth, 12, TransportMode.TRAIN, sim);
			depot.setName("Multi Vehicle Yard");
			depot.setCorners(new Position(-35, -3, -3), new Position(65, 3, 3));
			sim.rails.add(y2);
			sim.rails.add(x2);
			sim.rails.add(y1);
			sim.rails.add(ma);
			sim.rails.add(pl);
			sim.depots.add(depot);
			sim.sidings.add(siding1);
			sim.sidings.add(siding2);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			siding1.setVehicleCars(cars);
			siding2.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			siding1.tick();
			siding2.tick();
		}

		Vehicle spawn(Siding siding) {
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, new BranchStore(), null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			return vehicle;
		}

		void tick() {
			trees.removeFirst();
			trees.add(new Object2ObjectAVLTreeMap<>());
			siding1.simulateVehicles(1000, trees);
			siding2.simulateVehicles(1000, trees);
		}

		void tickUntil(BooleanSupplier condition, int maxTicks) {
			for (int i = 0; i < maxTicks && !condition.getAsBoolean(); i++) {
				tick();
			}
			assertTrue(condition.getAsBoolean(), "condition not met within " + maxTicks + " ticks");
		}

		/** Occupancy segments recorded for {@code rail} by any vehicle other than {@code id}. */
		double externalOverlapOn(Rail rail, long id) {
			final VehiclePosition vehiclePosition = Data.tryGet(trees.get(1), rail.getPosition1(), rail.getPosition2());
			return vehiclePosition == null ? -1 : vehiclePosition.getClosestOverlap(0, rail.railMath.getLength(), false, id);
		}

		/** Whether ANY vehicle registered a segment on {@code rail} (id -1 matches every segment). */
		double anySegmentOn(Rail rail) {
			final VehiclePosition vehiclePosition = Data.tryGet(trees.get(1), rail.getPosition1(), rail.getPosition2());
			return vehiclePosition == null ? -1 : vehiclePosition.getClosestOverlap(0, rail.railMath.getLength(), false, -1);
		}
	}

	private static void boardDriver(Simulator sim, Vehicle v, int throttle) {
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(entities);
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(throttle).setReverser(1), driver).apply(sim);
	}

	@Test
	public void stabledStockIsVisibleInTheOccupancyTree() {
		final Net n = new Net("build/mmtr-multi-vehicle-visible");
		final Vehicle rake = n.spawn(n.siding1);
		assertTrue(!rake.getIsOnRoute(), "the rake stays stabled in its siding");
		n.tick();
		n.tick();
		assertTrue(n.externalOverlapOn(n.y1, rake.getId()) < 0, "only the rake itself is on its own rail");
		assertTrue(n.anySegmentOn(n.y1) >= 0, "the stabled rake writes its footprint");

		// The loco leaves its own siding and drives toward the rake's siding: the stabled rake must
		// block it (before C3b the parked stock wrote no occupancy at all).
		final Vehicle loco = n.spawn(n.siding2);
		boardDriver(n.sim, loco, 3);
		n.tickUntil(() -> loco.getSpeed() == 0 && n.x2.getHexId().equals(loco.getMmtrMotionWalker().railHex()), 4000);
		assertEquals(n.x2.getHexId(), loco.getMmtrMotionWalker().railHex(), "the loco never boards the rail the stabled rake stands on");
		assertTrue(n.externalOverlapOn(n.y1, loco.getId()) >= 0, "the rake's footprint is visible to the loco");
	}

	@Test
	public void anAuthorizedLocoJoinsTheStabledRakeAndBothSurvive() {
		final Net n = new Net("build/mmtr-multi-vehicle-join");
		final Vehicle rake = n.spawn(n.siding1);
		final Vehicle loco = n.spawn(n.siding2);
		n.tick();
		n.tick();

		// 调车授权: the loco may pass the signal at danger and draw up to the rake.
		final MmtrShuntAuthority authority = n.sim.mmtrShuntAuthorities.grant(loco.getId(), n.x2.getHexId(), n.y1.getHexId(), Kind.SUBSIDIARY_SHUNT, 0, 10 * 60 * 1000L);
		assertTrue(authority.covers(n.y1.getHexId()));
		boardDriver(n.sim, loco, 3);
		n.tickUntil(() -> n.y1.getHexId().equals(loco.getMmtrMotionWalker().railHex()), 4000);
		assertEquals(n.y1.getHexId(), loco.getMmtrMotionWalker().railHex(), "the loco reached the rake's rail");
		assertEquals(n.y1.getHexId(), rake.getMmtrMotionWalker().railHex(), "the rake is still there");

		// Both trains keep standing on one rail: neither is culled by the yard rule while the authority
		// is live, and both footprints coexist in the shared occupancy tree.
		final double rakeProgress = rake.getRailProgress();
		for (int i = 0; i < 60; i++) {
			n.tick();
		}
		assertEquals(rakeProgress, rake.getRailProgress(), 1e-6, "the stabled rake never gets culled or pushed");
		assertEquals(n.y1.getHexId(), rake.getMmtrMotionWalker().railHex());
		assertEquals(n.y1.getHexId(), loco.getMmtrMotionWalker().railHex(), "the loco stays coupled-up on the same rail");
		assertTrue(n.externalOverlapOn(n.y1, rake.getId()) >= 0, "the rake sees the loco's footprint");
		assertTrue(n.externalOverlapOn(n.y1, loco.getId()) >= 0, "the loco sees the rake's footprint");
		// Both trains entered y1 from the same end (x2), so their rail-local head offsets are directly
		// comparable: the loco must stand behind the rake's tail, at coupler distance.
		final double rakeHead = rake.getMmtrMotionWalker().offsetM();
		final double locoHead = loco.getMmtrMotionWalker().offsetM();
		assertTrue(locoHead <= rakeHead - rake.vehicleExtraData.getTotalVehicleLength() + 0.05,
				"the loco draws up to the rake's tail, not through it (rake head " + rakeHead + " m, loco head " + locoHead + " m)");
		assertTrue(locoHead >= rakeHead - rake.vehicleExtraData.getTotalVehicleLength() - 0.5,
				"the loco actually closed up to the rake (coupler distance), loco head " + locoHead + " m");
	}

	@Test
	public void theSavedWorldCarriesBothTrainsOfTheCouplingPair() {		final Net n = new Net("build/mmtr-multi-vehicle-save");
		final Vehicle rake = n.spawn(n.siding1);
		final Vehicle loco = n.spawn(n.siding2);
		n.tick();
		n.tick();
		n.sim.mmtrShuntAuthorities.grant(loco.getId(), n.x2.getHexId(), n.y1.getHexId(), Kind.SUBSIDIARY_SHUNT, 0, 10 * 60 * 1000L);
		boardDriver(n.sim, loco, 3);
		n.tickUntil(() -> n.y1.getHexId().equals(loco.getMmtrMotionWalker().railHex()), 4000);

		// Serialize both sidings exactly as the save does (serializeFullData -> "vehicles" dataset).
		final JsonObject siding1Json = new JsonObject();
		n.siding1.serializeFullData(new JsonWriter(siding1Json));
		final JsonObject siding2Json = new JsonObject();
		n.siding2.serializeFullData(new JsonWriter(siding2Json));
		assertEquals(1, siding1Json.getAsJsonArray("vehicles").size(), "the rake is in siding 1's saved vehicle list");
		assertEquals(1, siding2Json.getAsJsonArray("vehicles").size(), "the loco is in siding 2's saved vehicle list");

		// Rebuild the same network in a fresh simulator and re-load the sidings from that JSON.
		final Simulator reloaded = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-multi-vehicle-save-reload"), false);
		final Rail y2r = Rail.newSidingRail(n.y2Back, Angle.fromAngle(0), n.y2Mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Rail x2r = through(n.y2Mouth, n.y1Back);
		final Rail y1r = Rail.newSidingRail(n.y1Back, Angle.fromAngle(0), n.y1Mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Rail mar = through(n.y1Mouth, new Position(16, 0, 0));
		final Rail plr = through(new Position(16, 0, 0), new Position(60, 0, 0));
		final Depot depotR = new Depot(TransportMode.TRAIN, reloaded);
		depotR.setName("Multi Vehicle Yard");
		depotR.setCorners(new Position(-35, -3, -3), new Position(65, 3, 3));
		reloaded.rails.add(y2r);
		reloaded.rails.add(x2r);
		reloaded.rails.add(y1r);
		reloaded.rails.add(mar);
		reloaded.rails.add(plr);
		reloaded.depots.add(depotR);
		reloaded.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
		reloaded.mmtrDefaultConsistTypeId = "emu";
		final Siding siding1Reloaded = new Siding(new JsonReader(siding1Json), reloaded);
		final Siding siding2Reloaded = new Siding(new JsonReader(siding2Json), reloaded);
		reloaded.sidings.add(siding1Reloaded);
		reloaded.sidings.add(siding2Reloaded);
		reloaded.sync();
		siding1Reloaded.init();
		siding2Reloaded.init();

		final ObjectArrayList<Vehicle> reloadedVehicles = new ObjectArrayList<>();
		siding1Reloaded.iterateVehicles(reloadedVehicles::add);
		siding2Reloaded.iterateVehicles(reloadedVehicles::add);
		assertEquals(2, reloadedVehicles.size(), "both trains of the coupling pair come back from the save");
		boolean rakeFound = false;
		boolean locoFound = false;
		for (final Vehicle vehicle : reloadedVehicles) {
			if (vehicle.getId() == rake.getId()) {
				rakeFound = true;
			}
			if (vehicle.getId() == loco.getId()) {
				locoFound = true;
			}
		}
		assertTrue(rakeFound, "the stabled rake survived the round trip");
		assertTrue(locoFound, "the loco standing on the rake's rail survived the round trip");
	}

	@Test
	public void twoClientMirrorsOnOneRailKeepTheirOwnPositions() {
		final Net n = new Net("build/mmtr-multi-vehicle-mirror");
		final Vehicle rake = n.spawn(n.siding1);
		final Vehicle loco = n.spawn(n.siding2);
		n.tick();
		n.tick();
		n.sim.mmtrShuntAuthorities.grant(loco.getId(), n.x2.getHexId(), n.y1.getHexId(), Kind.SUBSIDIARY_SHUNT, 0, 10 * 60 * 1000L);
		boardDriver(n.sim, loco, 3);
		n.tickUntil(() -> n.y1.getHexId().equals(loco.getMmtrMotionWalker().railHex()), 4000);

		// Reproduce the client view of BOTH trains exactly as the game builds it (server packs each
		// vehicle into a DynamicDataResponse, the client parses and rebuilds a VehicleExtension).
		final ObjectArrayList<Vehicle> clientVehicles = new ObjectArrayList<>();
		for (final Vehicle serverVehicle : new Vehicle[]{rake, loco}) {
			final org.mtr.core.operation.DynamicDataResponse response = new org.mtr.core.operation.DynamicDataResponse(UUID.randomUUID(), new ClientData());
			response.addVehicleToUpdate(new org.mtr.core.operation.VehicleUpdate(serverVehicle, serverVehicle.vehicleExtraData.copy(0)));
			final JsonObject packetJson = org.mtr.core.tool.Utilities.getJsonObjectFromData(response);
			final org.mtr.core.operation.DynamicDataResponse parsed = new org.mtr.core.operation.DynamicDataResponse(new JsonReader(packetJson), new ClientData());
			parsed.iterateVehiclesToUpdate(vehicleUpdate -> {
				final JsonObject vehicleJson = org.mtr.core.tool.Utilities.getJsonObjectFromData(vehicleUpdate.getVehicle());
				clientVehicles.add(new Vehicle(vehicleUpdate.getVehicleExtraData(), null, new JsonReader(vehicleJson), new ClientData()));
			});
		}
		assertEquals(2, clientVehicles.size(), "both trains reach the client");
		assertEquals(rake.getRailProgress(), clientVehicles.get(0).getRailProgress(), 1e-6, "the rake mirror carries the rake's own progress");
		assertEquals(loco.getRailProgress(), clientVehicles.get(1).getRailProgress(), 1e-6, "the loco mirror carries the loco's own progress");
		assertTrue(clientVehicles.get(0).getRailProgress() != clientVehicles.get(1).getRailProgress(), "the two mirrors are not collapsed onto one position");

		// The client ticks every mirror once per frame. A PARKED mirror must not drift (the collapse
		// bug overwrote it with the siding default position); a moving mirror legitimately keeps
		// rolling between updates (client-side interpolation), but never onto the other train.
		final Vehicle rakeMirror = clientVehicles.get(0);
		final double rakeMirrorBefore = rakeMirror.getRailProgress();
		for (int i = 0; i < 20; i++) {
			rakeMirror.simulate(50, null, null);
		}
		assertEquals(rakeMirrorBefore, rakeMirror.getRailProgress(), 1e-9, "a parked mirror keeps its own position on the shared rail");
		assertTrue(Math.abs(clientVehicles.get(1).getRailProgress() - rakeMirror.getRailProgress()) > 1e-6, "the two mirrors are never collapsed onto one position");
	}
}
