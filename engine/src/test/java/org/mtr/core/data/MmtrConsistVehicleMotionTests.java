package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.MmtrMotionSnapshot;
import org.mtr.core.mmtr.consist.MmtrCabState;
import org.mtr.core.mmtr.consist.MmtrConsistWalker;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B7.2a: a REAL Vehicle running on the consist-body walker, engaged through the new additive entry
 * {@code engageMmtrConsistMotion}. The point of this slice is that the motion tick itself did not
 * change — the vehicle drives, halts and reports exactly as before, because the walker is behind the
 * {@link org.mtr.core.mmtr.segment.MmtrMotionPosition} seam — while 换端 now moves nothing at all.
 */
public final class MmtrConsistVehicleMotionTests {

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

	/** A straight 3-rail line: nA -r0- nB -r1- nC -r2- nD. */
	private static final class Line {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-consist-vehicle"), false);
		final Position nA = new Position(-40, 0, 0);
		final Position nB = new Position(-20, 0, 0);
		final Position nC = new Position(0, 0, 0);
		final Position nD = new Position(20, 0, 0);
		final Rail r0 = through(nA, nB);
		final Rail r1 = through(nB, nC);
		final Rail r2 = through(nC, nD);

		Line() {
			sim.rails.add(r0);
			sim.rails.add(r1);
			sim.rails.add(r2);
			sim.sync();
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
		}
	}

	/** rIn -rIn- node0 -{rStraight | rDiverge}. */
	private static final class Fork {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-consist-vehicle-fork"), false);
		final Position nIn = new Position(-40, 0, 0);
		final Position node0 = new Position(-20, 0, 0);
		final Rail rIn = through(nIn, node0);
		final Rail rStraight = through(node0, new Position(0, 0, 0));
		final Rail rDiverge = diverge(node0, new Position(0, 0, 12));

		Fork() {
			sim.rails.add(rIn);
			sim.rails.add(rStraight);
			sim.rails.add(rDiverge);
			sim.sync();
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
		}
	}

	private static ObjectArrayList<VehicleCar> probeCars() {
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
		return cars;
	}

	/** A consist-body vehicle standing with its A end {@code aEndOffsetM} into {@code startRail}. */
	private static Vehicle consistVehicle(Simulator sim, Rail startRail, Position startEntry, double aEndOffsetM, BranchStore store) {
		final VehicleExtraData ved = VehicleExtraData.createWithLegs(1L, 0L, 6, probeCars(), new ObjectArrayList<>(), 0.0004, 0.0004, true, 120, 30000L);
		final Vehicle v = new Vehicle(ved, null, TransportMode.TRAIN, sim);
		final MmtrConsistWalker walker = MmtrConsistWalker.place(sim, store, startRail, startEntry, aEndOffsetM, new double[]{ved.getTotalVehicleLength()}, null);
		assertNotNull(walker, "the consist must fit on the placement rail");
		v.engageMmtrConsistMotion(walker, MmtrCabState.Cab.CAB_B);
		return v;
	}

	private static double driveTicks(Vehicle v, int ticks, ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions) {
		double max = v.getRailProgress();
		for (int i = 0; i < ticks; i++) {
			if (vehiclePositions != null) {
				vehiclePositions.set(0, vehiclePositions.get(1));
				vehiclePositions.set(1, new Object2ObjectAVLTreeMap<>());
			}
			v.simulate(1000, vehiclePositions, null);
			max = Math.max(max, v.getRailProgress());
		}
		return max;
	}

	private static ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> positions() {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vp = new ObjectArrayList<>();
		vp.add(new Object2ObjectAVLTreeMap<>());
		vp.add(new Object2ObjectAVLTreeMap<>());
		return vp;
	}

	@Test
	public void consistVehicleRunsUnderCabControl() {
		final Line line = new Line();
		final Vehicle v = consistVehicle(line.sim, line.r0, line.nA, 2, new BranchStore());
		assertTrue(v.isMmtrMotion());
		assertNotNull(v.getMmtrConsistWalker(), "the vehicle reports the consist walker");
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		final double max = driveTicks(v, 5, positions());
		assertTrue(max > 2, "the consist moved under cab control, got " + max);
		assertTrue(v.getSpeed() > 0);
		final MmtrConsistWalker walker = v.getMmtrConsistWalker();
		assertTrue(walker.distanceM() > 0);
		assertEquals(line.r0.getHexId(), walker.currentRailHex(), "a short run stays on the placement rail");
		assertEquals(1, walker.occupancy().size(), "a short consist on one rail occupies one segment");
	}

	@Test
	public void consistVehicleHaltsAtAnUnsetForkAndContinuesAfterTheBranchIsSet() {
		final Fork fork = new Fork();
		final BranchStore store = new BranchStore();
		final Vehicle v = consistVehicle(fork.sim, fork.rIn, fork.nIn, fork.rIn.railMath.getLength() - 8, store);
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vp = positions();
		final double firstMax = driveTicks(v, 300, vp);
		assertTrue(firstMax < fork.rIn.railMath.getLength(), "must halt before an unset fork, got " + firstMax);
		assertTrue(v.getMmtrConsistWalker().haltedAtAuthority());
		assertEquals(0, v.getSpeed(), 1e-9);
		store.set(fork.node0.getX(), fork.node0.getY(), fork.node0.getZ(), fork.rIn.getHexId(), 0);
		boolean boarded = false;
		for (int i = 0; i < 300 && !boarded; i++) {
			vp.set(0, vp.get(1));
			vp.set(1, new Object2ObjectAVLTreeMap<>());
			v.simulate(1000, vp, null);
			boarded = fork.rStraight.getHexId().equals(v.getMmtrConsistWalker().currentRailHex());
		}
		assertTrue(boarded, "the same vehicle continues onto the elected real rail");
	}

	@Test
	public void changeEndsOnAStandingConsistVehicleMovesNothing() {
		final Line line = new Line();
		final Vehicle v = consistVehicle(line.sim, line.r0, line.nA, 2, new BranchStore());
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		driveTicks(v, 120, positions());
		// Bring it to a stand (the crew cannot change ends on a moving train).
		v.applyMmtrControl(new ControlState().setBrakeNotch(8).setReverser(1));
		driveTicks(v, 200, positions());
		assertEquals(0, v.getSpeed(), 1e-9, "the consist is at rest before 换端");
		final MmtrMotionSnapshot before = MmtrMotionSnapshot.from(null, v);
		final double progressBefore = v.getRailProgress();
		final double beforeAEnd = v.getMmtrConsistWalker().body().aEndArcM();
		assertEquals("CAB_B", before.activeCab);

		assertTrue(v.changeEndsMmtrMotion(), "the crew changes ends on the standing consist");

		final MmtrMotionSnapshot after = MmtrMotionSnapshot.from(null, v);
		assertEquals("CAB_A", after.activeCab, "the other cab now drives");
		assertEquals(before.rearX, after.frontX, 1e-9, "I1 at the vehicle level: the rear point is now the front");
		assertEquals(before.rearZ, after.frontZ, 1e-9);
		assertEquals(before.frontX, after.rearX, 1e-9, "I1: the front point is now the rear");
		assertEquals(before.frontZ, after.rearZ, 1e-9);
		assertEquals(progressBefore, v.getRailProgress(), 1e-9, "换端 does not move the vehicle's reported progress");
		assertEquals(beforeAEnd, v.getMmtrConsistWalker().body().aEndArcM(), 1e-9, "the body interval is untouched");
	}

	@Test
	public void changeEndsRendersEveryCarInTheSameWorldPlace() {
		final Line line = new Line();
		final Vehicle v = consistVehicle(line.sim, line.r0, line.nA, 2, new BranchStore());
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		driveTicks(v, 5, positions());
		v.applyMmtrControl(new ControlState().setBrakeNotch(8).setReverser(1));
		driveTicks(v, 200, positions());
		assertEquals(0, v.getSpeed(), 1e-9, "at rest before the crew changes ends");
		final ObjectArrayList<it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<Vehicle.BogiePosition>>> before = v.getVehicleCarsAndPositions();
		assertTrue(v.changeEndsMmtrMotion());
		final ObjectArrayList<it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<Vehicle.BogiePosition>>> after = v.getVehicleCarsAndPositions();
		assertEquals(before.size(), after.size());
		for (int i = 0; i < before.size(); i++) {
			final ObjectArrayList<Vehicle.BogiePosition> beforeBogies = before.get(i).right();
			final ObjectArrayList<Vehicle.BogiePosition> afterBogies = after.get(i).right();
			assertEquals(beforeBogies.size(), afterBogies.size());
			for (int j = 0; j < beforeBogies.size(); j++) {
				final var beforePosition = beforeBogies.get(j).positionAndTiltAngle1().position();
				final var afterPosition = afterBogies.get(j).positionAndTiltAngle1().position();
				// 1 cm tolerance: the rail curve is parameterised per direction, so the same physical
				// point resolved along a reversed path differs by a sub-millimetre rounding, not by a move.
				assertEquals(beforePosition.x(), afterPosition.x(), 0.01, "car " + i + " bogie " + j + " must not move in x");
				assertEquals(beforePosition.z(), afterPosition.z(), 0.01, "car " + i + " bogie " + j + " must not move in z");
			}
		}
	}

	@Test
	public void consistVehicleWritesItsWholeBodyIntoTheOccupancyTree() {
		final Line line = new Line();
		// Placed so that after departure the 2.2 m body still straddles the r1/r2 boundary.
		final Vehicle v = consistVehicle(line.sim, line.r0, line.nA, line.r0.railMath.getLength() + line.r1.railMath.getLength() - 5.1, new BranchStore());
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vp = positions();
		for (int i = 0; i < 60 && !v.getIsOnRoute(); i++) {
			vp.set(0, vp.get(1));
			vp.set(1, new Object2ObjectAVLTreeMap<>());
			v.simulate(1000, vp, null);
		}
		assertTrue(v.getIsOnRoute(), "the consist has departed, so it writes its footprint");
		assertEquals(2, v.getMmtrConsistWalker().occupancy().size(), "the body still spans two rails");
		assertNotNull(Data.tryGet(vp.get(1), line.nB, line.nC), "the tail half occupies r1");
		assertNotNull(Data.tryGet(vp.get(1), line.nC, line.nD), "the head half occupies r2");
	}

	@Test
	public void cabOpsTakeAndLeaveTheMannedCab() {
		final Line line = new Line();
		// Placed well inside r0 so the A-end cab has room to roll after the crew takes it.
		final Vehicle v = consistVehicle(line.sim, line.r0, line.nA, line.r0.railMath.getLength() - 12, new BranchStore());
		assertEquals(MmtrCabState.Cab.CAB_B, v.getMmtrActiveCab(), "the spawn seam inserted the system key in the B-end cab");

		// Key out, then take the other cab: both are legal at a stand.
		assertTrue(v.leaveMmtrCab());
		assertEquals(MmtrCabState.Cab.NONE, v.getMmtrActiveCab());
		assertFalse(v.leaveMmtrCab(), "no key left to pull");
		assertTrue(v.enterMmtrCab(MmtrCabState.Cab.CAB_A));
		assertEquals(MmtrCabState.Cab.CAB_A, v.getMmtrActiveCab());
		assertFalse(v.enterMmtrCab(MmtrCabState.Cab.CAB_B), "a consist holds exactly one key");
		assertEquals(MmtrCabState.Cab.CAB_A, v.getMmtrActiveCab());

		// No cab can be taken on a moving consist; pulling the key is always legal.
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		driveTicks(v, 3, positions());
		assertTrue(v.getSpeed() > 0, "the consist is rolling");
		assertFalse(v.enterMmtrCab(MmtrCabState.Cab.CAB_B), "cannot walk into a cab on a moving train");
		assertTrue(v.leaveMmtrCab());
		assertEquals(MmtrCabState.Cab.NONE, v.getMmtrActiveCab());
	}

	@Test
	public void aMovingConsistVehicleRefusesToChangeEnds() {
		final Line line = new Line();
		final Vehicle v = consistVehicle(line.sim, line.r0, line.nA, 2, new BranchStore());
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		driveTicks(v, 5, positions());
		assertTrue(v.getSpeed() > 0, "the consist is rolling");
		assertFalse(v.changeEndsMmtrMotion(), "no change-ends while moving");
		assertEquals(MmtrCabState.Cab.CAB_B, v.getMmtrConsistWalker().cabs().activeCab());
	}
}
