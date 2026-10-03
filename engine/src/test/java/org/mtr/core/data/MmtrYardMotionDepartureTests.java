package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.MmtrMotionSnapshot;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.operation.MmtrDriveControl;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3 slice 2: yard departure through the siding seam. A vehicle is spawned by
 * {@link Siding#spawnMmtrMotionVehicle} ALREADY in live Motion-Core mode, parked inside the yard rail
 * with its head at the parked offset — no route baked anywhere. It stays put while no one drives it
 * (and the engine seeds no second parked train); once a driver throttles with the existing cab control
 * it departs: out of the yard rail, across the yard node onto the network approach, and then each fork
 * is elected LIVE (unset = halt and wait, flip = same vehicle continues). Verified through
 * {@link Siding#simulateVehicles} ticks (the real engine call path), the op-layer drive command and
 * the mmtr-motion snapshot.
 */
public final class MmtrYardMotionDepartureTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000}]"
		+ "}";

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static Rail diverge(Position node, Position far) {
		return Rail.newRail(node, Angle.fromAngle(45), far, Angle.fromAngle(225), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/**
	 * Real depot yard: siding rail YR (-32..-20, buffer at -32) sits inside a depot whose corners
	 * contain it; at the mouth node (-20) it joins the network approach rIn (-20..0) -> fork node0
	 * {rStraight | rDiverge}. Siding/sync attachment mirrors a real world (simulateVehicles requires
	 * an attached depot area).
	 */
	private static final class Net {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-yard-dep"), false);
		final Position yardBack = new Position(-32, 0, 0);
		final Position yardMouth = new Position(-20, 0, 0);
		final Position node0 = new Position(0, 0, 0);
		final Rail yardRail = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Rail rIn = through(yardMouth, node0);
		final Rail rStraight = through(node0, new Position(20, 0, 0));
		final Rail rDiverge = diverge(node0, new Position(20, 0, 12));
		final Depot depot = new Depot(TransportMode.TRAIN, sim);
		final Siding siding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);

		Net() {
			depot.setName("Yard");
			depot.setCorners(new Position(-40, -5, -5), new Position(-10, 5, 5));
			sim.rails.add(yardRail);
			sim.rails.add(rIn);
			sim.rails.add(rStraight);
			sim.rails.add(rDiverge);
			sim.depots.add(depot);
			sim.sidings.add(siding);
			siding.setVehicleCars(probeCars());
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			org.junit.jupiter.api.Assertions.assertTrue(depot.savedRails.contains(siding), "siding must attach to the depot yard");
			// Resolve the yard defaultPathData (the engine does this per tick through Siding#tick).
			siding.tick();
		}
	}

	private static ObjectArrayList<VehicleCar> probeCars() {
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1)); // total length ~2.2 m
		return cars;
	}

	@Test
	public void parkedMotionVehicleIdlesThenDepartsAcrossLiveFork() {
		final Net n = new Net();
		final BranchStore store = new BranchStore();
		final MmtrMotionWalker walker = n.siding.mmtrMotionWalkerFromYard(null, store, null);
		assertNotNull(walker, "yard walker must resolve on the connected yard rail");
		assertEquals(n.yardRail.getHexId(), walker.railHex(), "walker starts on the yard rail");
		final double parkedHead = walker.distanceM();
		assertTrue(parkedHead >= 2.2, "parked head offset fits the train body, got " + parkedHead);

		final Vehicle spawned = n.siding.spawnMmtrMotionVehicle(walker);
		assertNotNull(spawned, "motion dispatch seam must spawn the yard vehicle");
		assertTrue(spawned.isMmtrMotion(), "spawned vehicle is in live Motion-Core mode");
		assertEquals(parkedHead, spawned.getRailProgress(), 1e-6, "vehicle starts parked at the yard head offset");

		// Parked head geometry sits inside the yard rail.
		assertNotNull(spawned.getHeadPositionAndTiltAngle(), "head geometry available while parked");
		assertEquals(-32.0 + parkedHead, spawned.getHeadPositionAndTiltAngle().position().x(), 0.6, "head stands at the parked offset inside the yard");

		// Idle: no driver -> the vehicle never moves and the engine seeds no second parked train.
		double max = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < 30; i++) {
			n.siding.simulateVehicles(1000, null);
			max = Math.max(max, spawned.getRailProgress());
		}
		assertEquals(parkedHead, max, 1e-6, "vehicle must not move while no one drives it");
		assertTrue(!spawned.getIsOnRoute(), "parked vehicle is not on route");
		final int[] vehicleCount = {0};
		final long[] foundId = {0};
		n.siding.iterateVehicles(v -> {
			vehicleCount[0]++;
			foundId[0] = v.getId();
		});
		assertEquals(1, vehicleCount[0], "engine must hold exactly the one motion vehicle, no second parked train");
		assertEquals(spawned.getId(), foundId[0], "the yard still hosts the same spawned vehicle");

		// Snapshot while parked: live segment = yard rail, offset = parked head.
		final MmtrMotionSnapshot parkedSnapshot = MmtrMotionSnapshot.from(n.siding, spawned);
		assertEquals(-32.0, parkedSnapshot.segStartX, 1e-6, "parked segment starts at the yard back");
		assertEquals(-20.0, parkedSnapshot.segEndX, 1e-6, "parked segment ends at the yard mouth");
		assertEquals(parkedHead, parkedSnapshot.segmentOffsetM, 1e-6, "parked snapshot offset is the head offset");
		assertTrue(!parkedSnapshot.moving && !parkedSnapshot.onRoute, "parked snapshot reports a resting yard vehicle");

		// A driver boards and throttles through the operation layer; the vehicle departs out of the
		// yard, across the yard mouth node (single continuation, no authority needed) and halts at the
		// unset fork (~32 m from the yard back).
		// notes/233 司机优先：岔口路由合同改用**无人自动车**钉住（手动车跟随道岔物理位置，见下条用例）。
		spawned.setMmtrMotionAuto(true);
		spawned.setMmtrMotionStopTarget(1_000_000, false);

		double maxAfterDrive = spawned.getRailProgress();
		for (int i = 0; i < 300; i++) {
			n.siding.simulateVehicles(1000, null);
			maxAfterDrive = Math.max(maxAfterDrive, spawned.getRailProgress());
		}
		assertTrue(maxAfterDrive > 12.5, "vehicle must leave the yard rail onto the network approach, got " + maxAfterDrive);
		assertTrue(maxAfterDrive < 33.5, "vehicle must halt at the unset fork, got " + maxAfterDrive);
		assertTrue(spawned.getMmtrMotionWalker().haltedAtAuthority(), "walker awaits authority at the fork");
		assertEquals(n.rIn.getHexId(), spawned.getMmtrMotionWalker().railHex(), "approach rail reached the fork node");

		// Live flip to straight: the SAME vehicle continues across the fork onto the straight real rail.
		store.set(n.node0.getX(), n.node0.getY(), n.node0.getZ(), n.rIn.getHexId(), 0);
		boolean crossed = false;
		for (int i = 0; i < 200 && !crossed; i++) {
			n.siding.simulateVehicles(1000, null);
			crossed = n.rStraight.getHexId().equals(spawned.getMmtrMotionWalker().railHex());
		}
		assertTrue(crossed, "yard-departed vehicle must cross the fork onto the straight rail after the flip");
		assertEquals(n.rStraight.getHexId(), spawned.getMmtrMotionWalker().railHex(), "vehicle now runs on the straight real rail");
		assertTrue(spawned.getMmtrMotionWalker().offsetM() > 0, "vehicle has advanced onto the straight rail past the fork");
		assertTrue(spawned.getIsOnRoute(), "departed vehicle is on route");
	}

	@Test
	public void yardDepartureFlipsToDiverge() {
		final Net n = new Net();
		final BranchStore store = new BranchStore();
		final Vehicle spawned = n.siding.spawnMmtrMotionVehicle(n.siding.mmtrMotionWalkerFromYard(null, store, null));
		assertNotNull(spawned, "motion dispatch seam must spawn the yard vehicle");

		// notes/233 司机优先：岔口路由合同改用**无人自动车**钉住（手动车跟随道岔物理位置，见下条用例）。
		spawned.setMmtrMotionAuto(true);
		spawned.setMmtrMotionStopTarget(1_000_000, false);

		double max = spawned.getRailProgress();
		for (int i = 0; i < 300; i++) {
			n.siding.simulateVehicles(1000, null);
			max = Math.max(max, spawned.getRailProgress());
		}
		assertTrue(max < 33.5, "must halt at the unset fork, got " + max);
		assertTrue(spawned.getMmtrMotionWalker().haltedAtAuthority(), "awaits authority at the fork");

		store.set(n.node0.getX(), n.node0.getY(), n.node0.getZ(), n.rIn.getHexId(), 1);
		boolean crossed = false;
		for (int i = 0; i < 200 && !crossed; i++) {
			n.siding.simulateVehicles(1000, null);
			crossed = n.rDiverge.getHexId().equals(spawned.getMmtrMotionWalker().railHex());
		}
		assertTrue(crossed, "yard-departed vehicle must cross the fork onto the diverging rail after the flip");
		assertEquals(n.rDiverge.getHexId(), spawned.getMmtrMotionWalker().railHex(), "vehicle runs on the diverging rail");
	}
}
