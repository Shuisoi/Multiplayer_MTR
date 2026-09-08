package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.consist.MmtrCabState;
import org.mtr.core.mmtr.consist.MmtrConsistWalker;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B7.2d: the yard spawn seam on the consist-body model. The parked stock is placed with its A end
 * (car 0, the head) toward the yard mouth and its body toward the buffer, so the driver in CAB_A —
 * whose seat faces outward past the A end — departs head-first with no reverse running. The legacy
 * single-point walker stays available as the fallback.
 */
public final class MmtrConsistYardDepartureTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
		+ "}";

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static Rail diverge(Position node, Position far) {
		return Rail.newRail(node, Angle.fromAngle(45), far, Angle.fromAngle(225), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/** Yard rail (-32 buffer .. -20 mouth) joining the network approach rIn -> fork node0. */
	private static final class Net {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-consist-yard"), false);
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
			siding.tick();
		}

		double yardLength() {
			return yardRail.railMath.getLength();
		}
	}

	private static ObjectArrayList<VehicleCar> probeCars() {
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1)); // total length ~2.2 m
		return cars;
	}

	private static ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> positions() {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vp = new ObjectArrayList<>();
		vp.add(new Object2ObjectAVLTreeMap<>());
		vp.add(new Object2ObjectAVLTreeMap<>());
		return vp;
	}

	@Test
	public void parkedConsistFacesTheYardMouthWithItsBodyTowardTheBuffer() {
		final Net n = new Net();
		final MmtrConsistWalker walker = n.siding.mmtrConsistWalkerFromYard(null, new BranchStore(), null);
		assertNotNull(walker, "the consist must fit the yard rail");
		final double trainLength = Siding.getTotalVehicleLength(probeCars());
		final double headOffset = Math.max(trainLength, Math.min((n.yardLength() + trainLength) / 2, n.yardLength()));
		assertEquals(n.yardLength() - headOffset, walker.body().aEndArcM(), 1e-6, "the A end (head) stands headOffset from the buffer, measured from the mouth");
		assertEquals(n.yardMouth, walker.body().leg(0).entryNode(), "the spine starts at the yard mouth");
		assertEquals(n.yardBack, walker.body().leg(0).exitNode(), "and runs toward the buffer");
		assertEquals(n.yardRail.getHexId(), walker.occupancy().get(0).railHex());
	}

	@Test
	public void spawnedConsistTakesTheSystemKeyInTheAEndCabAndDepartsHeadFirst() {
		final Net n = new Net();
		final Vehicle vehicle = n.siding.spawnMmtrConsistVehicle(n.siding.mmtrConsistWalkerFromYard(null, new BranchStore(), null), MmtrCabState.Cab.CAB_A);
		assertNotNull(vehicle, "the consist stock must spawn");
		assertTrue(vehicle.isMmtrMotion());
		assertNotNull(vehicle.getMmtrConsistWalker(), "the vehicle runs on the consist body");
		assertEquals(MmtrCabState.Cab.CAB_A, vehicle.getMmtrConsistWalker().cabs().activeCab(), "the system key sits in the A-end cab");
		assertEquals(MmtrCabState.End.A, vehicle.getMmtrConsistWalker().cabs().leadingEnd(), "so the head (A end) leads out of the yard");

		// Throttle with the existing cab control: the consist runs toward the mouth and onto the network.
		vehicle.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vp = positions();
		boolean leftYard = false;
		double max = vehicle.getRailProgress();
		for (int i = 0; i < 300 && !leftYard; i++) {
			vp.set(0, vp.get(1));
			vp.set(1, new Object2ObjectAVLTreeMap<>());
			vehicle.simulate(1000, vp, null);
			max = Math.max(max, vehicle.getRailProgress());
			leftYard = n.rIn.getHexId().equals(vehicle.getMmtrConsistWalker().currentRailHex());
		}
		assertTrue(leftYard, "the consist departs head-first onto the approach rail, got progress " + max);
		assertTrue(vehicle.getRailProgress() > n.yardLength() - 8, "it actually travelled the yard, got " + vehicle.getRailProgress());
	}
}
