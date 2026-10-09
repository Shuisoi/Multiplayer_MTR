package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.MmtrLightSwitch;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.mmtr.MmtrMotionSnapshot;
import org.mtr.core.mmtr.consist.MmtrCabState;
import org.mtr.core.mmtr.consist.MmtrConsistBody;
import org.mtr.core.mmtr.consist.MmtrConsistWalker;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000}]"
		+ "}";

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES, 80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static Rail diverge(Position node, Position far) {
		return Rail.newRail(node, Angle.fromAngle(45), far, Angle.fromAngle(225), Rail.Shape.QUADRATIC, 0, NO_STYLES, 80, 80, false, false, true, false, true, TransportMode.TRAIN);
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
		final VehicleExtraData ved = VehicleExtraData.createWithLegs(1L, 0L, 6, probeCars(), new ObjectArrayList<>(), true, 120, 30000L);
		final Vehicle v = new Vehicle(ved, null, TransportMode.TRAIN, sim);
		final MmtrConsistWalker walker = MmtrConsistWalker.place(sim, store, startRail, startEntry, aEndOffsetM, new double[]{ved.getTotalVehicleLength()}, null);
		assertNotNull(walker, "the consist must fit on the placement rail");
		v.engageMmtrConsistMotion(walker, MmtrCabState.Cab.CAB_B);
		return v;
	}

	/** 机辆 two-car probe: a powered locomotive (coupler after it) plus a hauled wagon. */
	private static ObjectArrayList<VehicleCar> probeCars2() {
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		final VehicleCar loco = new VehicleCar("loco", 2, 1, 10, 0, 1, 0.1, 0.1, true, "");
		loco.setMmtrCouplerAfter(true);
		cars.add(loco);
		cars.add(new VehicleCar("wagon", 2, 1, 10, 0, 1, 0.1, 0.1, false, ""));
		return cars;
	}

	/** The same placement, with a two-car 机辆 consist and the requested cab manned. */
	private static Vehicle consistVehicle2(Simulator sim, Rail startRail, Position startEntry, double aEndOffsetM, MmtrCabState.Cab cab, BranchStore store) {
		final ObjectArrayList<VehicleCar> cars = probeCars2();
		final double[] carLengthsM = {cars.get(0).getTotalLength(true, false), cars.get(1).getTotalLength(false, true)};
		final boolean[] couplerAfter = {cars.get(0).getMmtrCouplerAfter(), cars.get(1).getMmtrCouplerAfter()};
		final VehicleExtraData ved = VehicleExtraData.createWithLegs(1L, 0L, 8, cars, new ObjectArrayList<>(), true, 120, 30000L);
		final Vehicle v = new Vehicle(ved, null, TransportMode.TRAIN, sim);
		final MmtrConsistWalker walker = MmtrConsistWalker.place(sim, store, startRail, startEntry, aEndOffsetM, carLengthsM, null,
				MmtrConsistBody.seamArcMsFrom(aEndOffsetM, carLengthsM, couplerAfter),
				MmtrConsistBody.seamCarIndexesFrom(carLengthsM, couplerAfter));
		assertNotNull(walker, "the two-car consist must fit on the placement rail");
		v.engageMmtrConsistMotion(walker, cab);
		return v;
	}

	/**
	 * REV 实机反馈（2026-09-09）：在 A 端开出去、停车、进另一端，再往前开时"整个编组像掉头一样反过来、
	 * 后面挂的货车跑到前面"。物理上换驾驶室/换向只改变**哪一端在前**，车体一格都不能动 —— 这条用例
	 * 把 2 节机辆编组（机车 + 货车）的两个边界都钉死。
	 */
	@Test
	public void changingToTheOtherCabDoesNotMoveOrReorderTheCars() {
		final Line line = new Line();
		final Vehicle v = consistVehicle2(line.sim, line.r0, line.nA, 2, MmtrCabState.Cab.CAB_A, new BranchStore());
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		driveTicks(v, 5, positions());
		v.applyMmtrControl(new ControlState().setBrakeNotch(8).setReverser(1));
		driveTicks(v, 200, positions());
		assertEquals(0, v.getSpeed(), 1e-9, "at rest before the crew walks to the other cab");

		final ObjectArrayList<it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<Vehicle.BogiePosition>>> before = v.getVehicleCarsAndPositions();
		assertEquals(2, before.size(), "the probe is a two-car 机辆 consist");
		assertTrue(v.enterMmtrCabAtCar(0, false, java.util.UUID.randomUUID()), "the crew takes the B-end cab of the same car");
		assertEquals(MmtrCabState.Cab.CAB_B, v.getMmtrActiveCab());
		assertCarsDoNotMove("换到另一端", before, v.getVehicleCarsAndPositions());
	}

	/**
	 * The reported symptom, verbatim: after the cab change the crew DRIVES, and the consist looked like
	 * it had turned around (the wagon ran to the front). The cause was not the geometry but the mirror:
	 * the synced legs are rebuilt on every moving tick in the new direction while {@code reversed} (the
	 * car-list direction) was only refreshed on 换端/换向, so the client laid the car list on the wrong
	 * end. Cars may only creep by the distance actually driven, never jump by a car length.
	 */
	@Test
	public void drivingFromTheOtherCabKeepsTheCarOrder() {
		final Line line = new Line();
		final Vehicle v = consistVehicle2(line.sim, line.r0, line.nA, 2, MmtrCabState.Cab.CAB_A, new BranchStore());
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		driveTicks(v, 5, positions());
		v.applyMmtrControl(new ControlState().setBrakeNotch(8).setReverser(1));
		driveTicks(v, 200, positions());
		assertEquals(0, v.getSpeed(), 1e-9, "at rest before the crew walks to the other cab");

		final java.util.UUID crew = java.util.UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> riders = new ObjectArrayList<>();
		riders.add(new VehicleRidingEntity(crew, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(riders);
		final ObjectArrayList<it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<Vehicle.BogiePosition>>> before = v.getVehicleCarsAndPositions();
		assertTrue(v.enterMmtrCabAtCar(0, false, crew), "the crew takes the B-end cab of the same car");

		// Drive a couple of ticks from the other cab: the legs get rebuilt in the new direction here.
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1), crew);
		driveTicks(v, 2, positions());

		final ObjectArrayList<it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<Vehicle.BogiePosition>>> after = v.getVehicleCarsAndPositions();
		assertEquals(2, after.size());
		for (int i = 0; i < before.size(); i++) {
			assertEquals(before.get(i).left().getVehicleId(), after.get(i).left().getVehicleId(), "the car order must not change");
		}
		// The train itself creeps forward, so compare the RELATIVE arrangement of the two cars: a car
		// list laid out on the wrong end flips this vector by a whole car length (~2.2 m).
		final var beforeLoco = before.get(0).right().get(0).positionAndTiltAngle1().position();
		final var beforeWagon = before.get(1).right().get(0).positionAndTiltAngle1().position();
		final var afterLoco = after.get(0).right().get(0).positionAndTiltAngle1().position();
		final var afterWagon = after.get(1).right().get(0).positionAndTiltAngle1().position();
		assertEquals(beforeLoco.x() - beforeWagon.x(), afterLoco.x() - afterWagon.x(), 0.05, "the loco must stay on its own side of the wagon in x");
		assertEquals(beforeLoco.z() - beforeWagon.z(), afterLoco.z() - afterWagon.z(), 0.05, "the loco must stay on its own side of the wagon in z");
	}

	/** The same invariant for the reverser: pulling it changes the leading end, not the car placement. */
	@Test
	public void pullingTheReverserDoesNotMoveOrReorderTheCars() {		final Line line = new Line();
		final Vehicle v = consistVehicle2(line.sim, line.r0, line.nA, 2, MmtrCabState.Cab.CAB_A, new BranchStore());
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		driveTicks(v, 5, positions());
		v.applyMmtrControl(new ControlState().setBrakeNotch(8).setReverser(1));
		driveTicks(v, 200, positions());
		assertEquals(0, v.getSpeed(), 1e-9);

		final ObjectArrayList<it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<Vehicle.BogiePosition>>> before = v.getVehicleCarsAndPositions();
		v.applyMmtrControl(new ControlState().setReverser(-1));
		assertTrue(v.getMmtrConsistWalker().travelReversed(), "the reverser is pulled");
		assertCarsDoNotMove("换向器", before, v.getVehicleCarsAndPositions());
	}

	/** Every car's bogies must stay at the same world position (1 cm tolerance for curve rounding). */
	private static void assertCarsDoNotMove(String what, ObjectArrayList<it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<Vehicle.BogiePosition>>> before, ObjectArrayList<it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<Vehicle.BogiePosition>>> after) {
		assertEquals(before.size(), after.size());
		for (int i = 0; i < before.size(); i++) {
			assertEquals(before.get(i).left().getVehicleId(), after.get(i).left().getVehicleId(), what + ": the car order must not change");
			final ObjectArrayList<Vehicle.BogiePosition> beforeBogies = before.get(i).right();
			final ObjectArrayList<Vehicle.BogiePosition> afterBogies = after.get(i).right();
			assertEquals(beforeBogies.size(), afterBogies.size());
			for (int j = 0; j < beforeBogies.size(); j++) {
				final var beforePosition = beforeBogies.get(j).positionAndTiltAngle1().position();
				final var afterPosition = afterBogies.get(j).positionAndTiltAngle1().position();
				assertEquals(beforePosition.x(), afterPosition.x(), 0.01, what + ": car " + i + " bogie " + j + " must not move in x");
				assertEquals(beforePosition.z(), afterPosition.z(), 0.01, what + ": car " + i + " bogie " + j + " must not move in z");
			}
		}
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

	/**
	 * notes/233 司机优先（用户口径 2026-09-21：「只要司机能上车，什么都阻挡不了他开车」）。
	 *
	 * <p>编组体列车在**没有人工位/进路授权/任务目标**的岔口上，手动司机不再被停在岔前：
	 * 走行器**跟随道岔当前物理位置**（位置 0 = 正线/直行那条腿）继续走；司机在物理上会脱轨的组合
	 * （道岔没开通他要走的那条）仍然停在岔前 —— 那是唯一还允许拦车的理由（见 {@code MmtrForkElection}）。</p>
	 */
	@Test
	public void consistVehicleFollowsTheTurnoutsPhysicalPositionUnderCabControl() {
		final Fork fork = new Fork();
		final BranchStore store = new BranchStore();
		final Vehicle v = consistVehicle(fork.sim, fork.rIn, fork.nIn, fork.rIn.railMath.getLength() - 8, store);
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vp = positions();
		final double firstMax = driveTicks(v, 300, vp);
		assertTrue(firstMax > fork.rIn.railMath.getLength(), "must run through the unset fork, got " + firstMax);
		assertFalse(v.getMmtrConsistWalker().haltedAtAuthority(), "a cab driver is not held at an unset fork");
		assertEquals(fork.rStraight.getHexId(), v.getMmtrConsistWalker().currentRailHex(), "follows the physically open (straight) leg");
	}

	/** 自动车**仍然**停在未设好的岔前（司机优先只改手动那一支）。 */
	@Test
	public void autoRunStillHaltsAtAnUnsetForkAndContinuesAfterTheBranchIsSet() {
		final Fork fork = new Fork();
		final BranchStore store = new BranchStore();
		final Vehicle v = consistVehicle(fork.sim, fork.rIn, fork.nIn, fork.rIn.railMath.getLength() - 8, store);
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(1_000_000, false);
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
	public void aMovingConsistVehicleRefusesToChangeEnds() {		final Line line = new Line();
		final Vehicle v = consistVehicle(line.sim, line.r0, line.nA, 2, new BranchStore());
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		driveTicks(v, 5, positions());
		assertTrue(v.getSpeed() > 0, "the consist is rolling");
		assertFalse(v.changeEndsMmtrMotion(), "no change-ends while moving");
		assertEquals(MmtrCabState.Cab.CAB_B, v.getMmtrConsistWalker().cabs().activeCab());
	}

	/**
	 * 钥匙归属 (2026-09-10) + **操纵判据放宽 (2026-09-19)**：车底在编组时被引擎的 <em>system 钥匙</em>占着，
	 * 那既是"哪一端在前"的判据、也是自动运行的占位。system 钥匙**不代替司机**，但操纵也不再要求手里有钥匙
	 * —— 判据只有"骑在司机位上"（用户口径：「操作手柄不需要手里握着钥匙」）。
	 *
	 * <p>仍然保留的规矩：编组体上同时只有一个人在开（占用锁：先动手的人拿到，持有者还在司机位上时别人抢不走）、
	 * system 钥匙可以被乘务员顶掉、引擎永远不顶掉乘务员、**司机位空了才交还操纵权**。</p>
	 */
	@Test
	public void theCrewTakesTheStagedCabFromTheSystemKeyAndThenOwnsTheThrottle() {
		final Line line = new Line();
		final Vehicle v = consistVehicle(line.sim, line.r0, line.nA, 2, new BranchStore());
		final java.util.UUID crew = java.util.UUID.randomUUID();
		final java.util.UUID other = java.util.UUID.randomUUID();

		assertEquals(MmtrCabState.KeyHolder.SYSTEM, v.getMmtrCabKeyHolder(), "the staging seam holds the system key");
		assertEquals(MmtrCabState.Cab.CAB_B, v.getMmtrActiveCab());
		assertFalse(v.canTakeMmtrControl(crew), "system 钥匙不代替司机：没人骑在司机位上就不能操纵");

		assertTrue(v.enterMmtrCab(MmtrCabState.Cab.CAB_A, crew), "the crew takes the cab the system key held");
		assertEquals(MmtrCabState.KeyHolder.CREW, v.getMmtrCabKeyHolder());
		assertEquals("CAB_A", v.getMmtrActiveCabFromSync(), "the cab state is mirrored for clients");
		assertEquals(crew.toString(), v.getMmtrCabCrewFromSync());
		// 两位玩家都骑在司机位上：钥匙不再是前提 ⇒ 两人都拿得到；先动手的那位拿到之后，
		// 占用锁把另一位挡住 —— 这就是"一列车上只有一个司机"。
		final ObjectArrayList<VehicleRidingEntity> riders = new ObjectArrayList<>();
		riders.add(new VehicleRidingEntity(crew, 0, 0, 0, 0, false, true, true, false, false, false, false));
		riders.add(new VehicleRidingEntity(other, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(riders);
		assertTrue(v.canTakeMmtrControl(crew));
		assertTrue(v.canTakeMmtrControl(other), "钥匙不再是前提：骑在司机位上就能开（2026-09-19）");
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1), crew);
		assertTrue(v.isMmtrManualOverride());
		assertFalse(v.canTakeMmtrControl(other), "占用锁：持有者还在司机位上时别人抢不走");

		// The engine's own insert is refused while the crew holds the key, and the crew key cannot be
		// pulled by anyone else.
		assertFalse(v.getMmtrConsistWalker().insertSystemKey(MmtrCabState.Cab.CAB_B, true));
		assertFalse(v.leaveMmtrCab(other));
		assertEquals(MmtrCabState.Cab.CAB_A, v.getMmtrActiveCab());

		driveTicks(v, 2, positions());
		assertTrue(v.getSpeed() > 0);
		// 拔钥匙**不再**断牵引：override 跟着司机位走、不跟着钥匙走（否则站在司机位上的人会突然失去制动）。
		assertTrue(v.leaveMmtrCab(crew));
		driveTicks(v, 1, positions());
		assertTrue(v.isMmtrManualOverride(), "人还在司机位上 ⇒ 操纵权还在（钥匙不再是前提）");
		assertEquals(MmtrCabState.Cab.NONE, v.getMmtrActiveCab());
		assertEquals(MmtrCabState.KeyHolder.NONE, v.getMmtrCabKeyHolder());
		// 走开（不再"骑着的司机"）才交还操纵权 —— 放宽之后这是唯一的交还判据。
		// 注意 updateRidingEntities 收的是**逐人的更新**：要让某人下车得发一条 ridingCar = -1 的记录，
		// 传空表什么也不会移除。
		final ObjectArrayList<VehicleRidingEntity> dismount = new ObjectArrayList<>();
		dismount.add(new VehicleRidingEntity(crew, -1, 0, 0, 0, false, false, false, false, false, false, false));
		v.updateRidingEntities(dismount);
		driveTicks(v, 1, positions());
		assertFalse(v.isMmtrManualOverride(), "司机位空了 ⇒ 交还操纵权");
	}

	/** Taking a cab also disarms an auto run that was armed under the system key. */
	@Test
	public void takingTheCabDropsTheArmedAutoRun() {
		final Line line = new Line();
		final Vehicle v = consistVehicle(line.sim, line.r0, line.nA, 2, new BranchStore());
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(line.r0.railMath.getLength() - 2, false);
		assertTrue(v.enterMmtrCab(MmtrCabState.Cab.CAB_A, java.util.UUID.randomUUID()));
		assertFalse(v.isMmtrMotionAuto(), "the crew is driving by hand now");
	}

	/**
	 * 实机缺陷 (2026-09-09)「端2 没法开车」: the crew drove the train into a dead end, walked to the
	 * other cab and took it — and the throttle then did nothing. {@code endOfLine}/{@code atTarget}
	 * describe the OLD direction of travel and used to survive a cab change made by walking (only the
	 * 换端 command cleared them), so every advance was refused.
	 */
	@Test
	public void takingTheOtherCabAtTheDeadEndLetsTheConsistDriveBack() {
		final Line line = new Line();
		// Near the far end of r2 (the last rail) so the B-end cab runs into the dead end at once.
		final Vehicle v = consistVehicle(line.sim, line.r2, line.nC, line.r2.railMath.getLength() - 4, new BranchStore());
		final java.util.UUID crew = java.util.UUID.randomUUID();
		assertTrue(v.enterMmtrCab(MmtrCabState.Cab.CAB_B, crew), "the crew takes the B-end cab");
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		driveTicks(v, 300, positions());

		final MmtrConsistWalker walker = v.getMmtrConsistWalker();
		assertTrue(walker.endOfLine(), "the B end reached the dead end of r2");
		assertEquals(0, v.getSpeed(), 1e-9, "the consist is at rest against the dead end");

		// The crew walks to the other cab of the same car and takes it: the dead end is now BEHIND the
		// leading end, so the train must be able to drive away.
		assertTrue(v.enterMmtrCabAtCar(0, true, crew), "the same crew moves to the A-end cab");
		assertEquals(MmtrCabState.Cab.CAB_A, v.getMmtrActiveCab());
		assertFalse(walker.endOfLine(), "the old direction's dead end no longer applies");

		final double before = walker.distanceM();
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		driveTicks(v, 60, positions());
		assertTrue(walker.distanceM() > before + 0.5, "the consist drove back out of the dead end");
	}

	/**
	 * REV 换向器: the reverser points the consist the other way while the SAME cab stays manned, which is
	 * what a shunting locomotive does — no 换端 needed. The body must not move when the lever is pulled
	 * (I1) and the geometry stays untouched (I2).
	 */
	@Test
	public void reverserDrivesTheConsistTailFirstFromTheSameCab() {
		final Line line = new Line();
		final Vehicle v = consistVehicle(line.sim, line.r0, line.nA, 2, new BranchStore());
		final MmtrConsistWalker walker = v.getMmtrConsistWalker();
		assertEquals(MmtrCabState.Cab.CAB_B, v.getMmtrActiveCab());
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		driveTicks(v, 10, positions());
		assertTrue(walker.distanceM() > 0.5, "the consist ran with its B end leading");
		v.applyMmtrControl(new ControlState().setBrakeNotch(8).setReverser(1));
		driveTicks(v, 200, positions());
		assertEquals(0, v.getSpeed(), 1e-9, "at rest before the reverser is pulled");

		final double frontBefore = walker.frontArcM();
		final double rearBefore = walker.rearArcM();
		assertFalse(walker.travelReversed());

		v.applyMmtrControl(new ControlState().setReverser(-1));
		assertTrue(walker.travelReversed(), "the reverser flips the direction of travel");
		assertEquals(MmtrCabState.Cab.CAB_B, v.getMmtrActiveCab(), "the crew stays in the same cab");
		assertEquals(frontBefore, walker.rearArcM(), 1e-9, "I1: the old front face is the new rear face");
		assertEquals(rearBefore, walker.frontArcM(), 1e-9);
		assertEquals(0, v.getSpeed(), 1e-9, "pulling the reverser moves nothing");

		final double before = walker.distanceM();
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(-1));
		driveTicks(v, 10, positions());
		assertTrue(walker.distanceM() > before + 0.5, "the consist drove tail-first");
		assertTrue(v.getSpeed() > 0);
	}

	/**
	 * REV: a reverser change requested while rolling must not teleport the motion — traction is cut and
	 * the new direction applies once the consist is at a stand (a real reverser's interlock).
	 */
	@Test
	public void reverserChangeWhileRollingWaitsForTheStand() {
		final Line line = new Line();
		final Vehicle v = consistVehicle(line.sim, line.r0, line.nA, 2, new BranchStore());
		final MmtrConsistWalker walker = v.getMmtrConsistWalker();
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		driveTicks(v, 5, positions());
		assertTrue(v.getSpeed() > 0, "the consist is rolling");
		final double progressWhileRolling = walker.distanceM();

		// Pull the reverser AND brake: the direction must not change while the train is still moving.
		v.applyMmtrControl(new ControlState().setBrakeNotch(8).setReverser(-1));
		assertFalse(walker.travelReversed(), "the direction does not flip while rolling");
		driveTicks(v, 200, positions());
		assertEquals(0, v.getSpeed(), 1e-9);
		assertTrue(walker.travelReversed(), "the requested direction applies once at a stand");
		assertTrue(walker.distanceM() >= progressWhileRolling, "I3: distance never decreases");
	}

	/**
	 * 自动运行（"AI 驾驶员"）的灯光：**车头白、车尾红**（用户口径 2026-10-03「AI驾驶员也需要控制车灯开关，
	 * 并且尾部车灯需要变红」）。
	 *
	 * <p>钉四件事：
	 * <ol>
	 *   <li>没有任务的车（车场停放）**不动**灯 —— 两端仍是出厂值尾灯（"无人照看的车两端都是红标志灯"）；</li>
	 *   <li>任务挂上（钥匙是引擎的、没人接管）⇒ 车头 = 世界时决定的近光/远光前照灯、车尾 = 尾灯，
	 *       <b>上一次人工驾驶留在另一端的那盏白灯就是这样被收掉的</b>；</li>
	 *   <li>镜像换向器被写成"在档"：不写它，客户端判据的"换向 N ⇒ 固定红"会把刚点亮的车头也判成红的；</li>
	 *   <li>有乘务员钥匙时这条规则**一条都不动**（司机优先），换向 / 收工后按方向重排、白灯落回尾灯。</li>
	 * </ol>
	 */
	@Test
	public void autoRunLightsTheLeadingEndAndKeepsTheTailRed() {
		final Line line = new Line();
		line.sim.setGameTime(12 * 50_000L, 1_200_000L, false); // 世界时 = 正午
		final Vehicle v = consistVehicle(line.sim, line.r0, line.nA, 2, new BranchStore());

		// ① 停放（还没有任务）：两端都是红标志灯。
		driveTicks(v, 1, positions());
		assertEquals(MmtrLightSwitch.TAIL, v.getMmtrLightAFromSync(), "没有任务 ⇒ A 端不动（红标志灯）");
		assertEquals(MmtrLightSwitch.TAIL, v.getMmtrLightBFromSync(), "没有任务 ⇒ B 端不动（红标志灯）");

		// ② 上一次人工驾驶留在 A 端的一盏白灯（等价于"司机按过 L、下车时那一次回落漏了"）。
		v.setMmtrLightSwitch(MmtrLightSwitch.END_A, MmtrLightSwitch.HIGH);

		// ③ 自动任务挂上（原地换端：不需要规划进路，且顺带把车头/车尾翻一次）。
		final MmtrMission mission = new MmtrMission(v.getId(), MmtrMission.Kind.MANEUVER, 7L, 7L, 0L);
		mission.attachTask(new org.mtr.core.mmtr.task.ChangeEndsTask("t-auto-lights", 0L));
		assertTrue(v.setMmtrMission(mission), "自动任务挂上");
		driveTicks(v, 1, positions());
		assertFalse(mission.isTerminal(), "原地任务不会自己结束（状态 " + mission.getState() + "）");

		final MmtrConsistWalker walker = v.getMmtrConsistWalker();
		final int front = MmtrLightSwitch.autoLeadingEnd(walker.travelsTowardB());
		final int rear = MmtrLightSwitch.otherEnd(front);
		assertEquals(MmtrLightSwitch.LOW, MmtrLightSwitch.switchOfEnd(v.getMmtrLightAFromSync(), v.getMmtrLightBFromSync(), front),
			"车头 = 近光档（世界时正午）");
		assertEquals(MmtrLightSwitch.TAIL, MmtrLightSwitch.switchOfEnd(v.getMmtrLightAFromSync(), v.getMmtrLightBFromSync(), rear),
			"车尾 = 红尾灯（陈旧的白灯被收掉）");
		assertNotEquals(0, v.getMmtrReverserFromSync(), "自动运行的镜像换向器在档：0（N）会让客户端把两端都画成红");

		// ④ 天黑 ⇒ 车头自动翻到更亮的远光档（同一趟车、同一个方向，只有世界时变了）。
		line.sim.setGameTime(22 * 50_000L, 1_200_000L, false);
		driveTicks(v, 1, positions());
		assertEquals(MmtrLightSwitch.HIGH, MmtrLightSwitch.switchOfEnd(v.getMmtrLightAFromSync(), v.getMmtrLightBFromSync(), front),
			"22 时 ⇒ 车头远光档");
		assertEquals(MmtrLightSwitch.TAIL, MmtrLightSwitch.switchOfEnd(v.getMmtrLightAFromSync(), v.getMmtrLightBFromSync(), rear), "车尾始终红");

		// ⑤ 乘务员接管驾驶室：灯归他 —— 引擎这条规则一条都不动。
		assertTrue(v.enterMmtrCab(MmtrCabState.Cab.CAB_A), "乘务员接管 A 端驾驶室");
		v.setMmtrLightSwitch(MmtrLightSwitch.END_A, MmtrLightSwitch.TAIL);
		v.setMmtrLightSwitch(MmtrLightSwitch.END_B, MmtrLightSwitch.HIGH);
		driveTicks(v, 3, positions());
		assertEquals(MmtrLightSwitch.TAIL, v.getMmtrLightAFromSync(), "有乘务员钥匙 ⇒ 引擎不碰灯（司机优先）");
		assertEquals(MmtrLightSwitch.HIGH, v.getMmtrLightBFromSync(), "有乘务员钥匙 ⇒ 引擎不碰灯（司机优先）");

		// ⑥ 司机走了（钥匙拔掉、没人接手）⇒ 交还：白灯落回尾灯。
		assertTrue(v.leaveMmtrCab(), "乘务员拔钥匙");
		driveTicks(v, 1, positions());
		assertEquals(MmtrLightSwitch.TAIL, v.getMmtrLightAFromSync(), "AI 不再持钥匙 ⇒ 白灯落回尾灯");
		assertEquals(MmtrLightSwitch.TAIL, v.getMmtrLightBFromSync());
	}

	/**
	 * "无人照看 ⇒ 收掉陈旧白灯"这条兜底**不许碰有司机在场的车** —— 哪怕钥匙还是引擎那把 SYSTEM
	 * 占位钥匙（现场：司机在无人编组上推手柄，引擎补一把钥匙让编组有头，见
	 * {@code Vehicle#mmtrAdoptUnmannedConsistForDriver}），他刚按 L 拨的档位就是他要的答案。
	 */
	@Test
	public void unattendedLightFallbackNeverTouchesALightTheDriverJustSet() {
		final Line line = new Line();
		final Vehicle v = consistVehicle(line.sim, line.r0, line.nA, 2, new BranchStore());
		final MmtrConsistWalker walker = v.getMmtrConsistWalker();
		final int mannedEnd = walker.cabs().activeCab() == MmtrCabState.Cab.CAB_B ? MmtrLightSwitch.END_B : MmtrLightSwitch.END_A;

		// 司机推手柄 ⇒ 人工接管（钥匙仍是引擎的占位钥匙）。
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		driveTicks(v, 1, positions());
		assertTrue(v.isMmtrManualOverride(), "司机推了手柄 ⇒ 人工接管");

		// 他按 L 拨到远光 ⇒ 这条灯是他的；兜底与自动灯光规则都不许把它收掉。
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1).setLightSwitch(MmtrLightSwitch.HIGH));
		driveTicks(v, 3, positions());
		assertEquals(MmtrLightSwitch.HIGH, MmtrLightSwitch.switchOfEnd(v.getMmtrLightAFromSync(), v.getMmtrLightBFromSync(), mannedEnd),
			"有司机在场 ⇒ 引擎不碰他自己拨的那盏灯");
	}

	/**
	 * notes/411 的**兜底网**（{@code MmtrAutoKeyWatch}）：钥匙从车上出去的那一刻只**登记**，
	 * 判断与补钥匙按有界重试做（不每 tick 扫全车队）；只有"在跑的自动任务"才在范围内。
	 *
	 * <p>实机验证据此而来（2026-10-09 22:51，{@code train cab <id> out} 拔钥匙后 42 秒没自愈）：
	 * 自臂那条路有 {@code mmtrMotionAuto} 的门，"已经武装 + 随后被拔钥匙"走不到它，所以必须有这一层。</p>
	 */
	@Test
	public void theKeyWatchMansAStoppedAutoMissionConsistButLeavesAPlayerMissionAlone() {
		final Line line = new Line();
		final Vehicle v = consistVehicle(line.sim, line.r0, line.nA, 2, new BranchStore());
		// 兜底网按 id 查车（耦合手术会换掉 Vehicle 对象，所以只存 id）⇒ 用例里得把它挂到股道上
		final Siding siding = new Siding(new Position(-60, 0, 0), new Position(-40, 0, 0), 12, TransportMode.TRAIN, line.sim);
		line.sim.sidings.add(siding);
		siding.adoptVehicle(v);
		final MmtrConsistWalker walker = v.getMmtrConsistWalker();
		final MmtrMission mission = new MmtrMission(v.getId(), MmtrMission.Kind.PASSENGER, 0L, 9L, 0L);
		mission.setTargetRail(line.r2.getHexId(), 1.0);
		assertTrue(v.setMmtrMission(mission), "自动任务挂上");

		// 拔钥匙 = "到站停稳，自动交还"之后那一拍：只登记，不立刻补
		assertTrue(v.leaveMmtrCab(), "司机拔钥匙");
		assertFalse(walker.cabs().isManned(), "钥匙出去了 ⇒ 无人");
		assertEquals(1, line.sim.mmtrAutoKeyWatch.size(), "丢钥匙时只登记一列车（不是全车队扫描）");

		// 兜底网按 250 ms 节拍（5 tick）看一眼：车停稳 ⇒ 补回引擎占位钥匙并出表
		for (int i = 0; i < 5; i++) {
			line.sim.mmtrAutoKeyWatch.tick(line.sim);
		}
		assertTrue(walker.cabs().isManned(), "兜底网把占位钥匙补回来了");
		assertEquals(MmtrCabState.KeyHolder.SYSTEM, walker.cabs().keyHolder(), "补的是引擎占位钥匙");
		assertEquals(0, line.sim.mmtrAutoKeyWatch.size(), "补上即出表（表不该长期非空）");

		// 玩家执行的任务不在范围内：不许抢玩家的驾驶室
		assertTrue(v.leaveMmtrCab(), "再拔一次");
		assertTrue(mission.setExecutor(MmtrMission.Executor.PLAYER, java.util.UUID.randomUUID()), "任务改成玩家执行（PLAYER 必须带 uuid）");
		for (int i = 0; i < 5; i++) {
			line.sim.mmtrAutoKeyWatch.tick(line.sim);
		}
		assertFalse(walker.cabs().isManned(), "玩家任务不补钥匙（司机可能正走向驾驶室）");
		assertEquals(0, line.sim.mmtrAutoKeyWatch.size(), "不在范围 ⇒ 下一个重试节拍出表（最多 250 ms），不留悬挂条目");
	}

	/**
	 * notes/411 现场（2026-10-09，车 00101 / 00106，整个车队停摆）：到站"自动交还"把司机的钥匙拔走之后，
	 * 编组变**无人** ⇒ {@code MmtrConsistWalker.railHex()} 按设计返回 null（无人 = 没有车头），
	 * 规划器于是给不出"当前轨"，而**旧代码把这一条当不可恢复**，直接 {@code mission.fail(...)} ⇒
	 * 任务进终态、再没有任何状态机会来救它 ⇒ 两列 0 km/h 的车把整条单线堵死。
	 *
	 * <p>这一条把三件事钉死：</p>
	 * <ol>
	 *   <li>无人 ⇒ 没有当前轨（既有定义，一个字都不许改）；</li>
	 *   <li>自动任务在"无人导致规划不出来"时**不许**进终态；</li>
	 *   <li>车要能自己把**引擎占位钥匙**补回来（补完立刻又有当前轨）。</li>
	 * </ol>
	 */
	@Test
	public void anUnmannedConsistOnAnAutoMissionMansItselfInsteadOfFailingTheMission() {
		final Line line = new Line();
		final Vehicle v = consistVehicle(line.sim, line.r0, line.nA, 2, new BranchStore());
		final MmtrConsistWalker walker = v.getMmtrConsistWalker();
		assertTrue(walker.cabs().isManned(), "engage 时插的就是引擎占位钥匙");
		assertNotNull(walker.leadingRailHex(), "有人 ⇒ 有当前轨");

		final MmtrMission mission = new MmtrMission(v.getId(), MmtrMission.Kind.PASSENGER, 0L, 9L, 0L);
		mission.setTargetRail(line.r2.getHexId(), 1.0);
		assertTrue(v.setMmtrMission(mission), "自动任务挂上");

		// 司机把钥匙拔走 = "到站停稳，自动交还"之后的下一拍
		assertTrue(v.leaveMmtrCab(), "司机拔钥匙");
		assertFalse(walker.cabs().isManned(), "钥匙拔走 ⇒ 无人");
		assertNull(walker.leadingRailHex(), "无人 ⇒ 走行器按设计不给当前轨（这条定义不许变）");

		// 自臂：旧代码在这一步 mission.fail()；现在必须自己补钥匙、任务不进终态
		assertTrue(v.mmtrArmActiveMissionNow(line.sim), "自臂入口确实跑了一次");
		assertFalse(mission.isTerminal(), "无人不是失败：任务不许进终态（实际 " + mission.getState() + "）");
		assertTrue(walker.cabs().isManned(), "引擎把占位钥匙补回来了");
		assertEquals(MmtrCabState.KeyHolder.SYSTEM, walker.cabs().keyHolder(), "补的是引擎占位钥匙，不是乘务员钥匙");
		assertNotNull(walker.leadingRailHex(), "补完钥匙 ⇒ 又有当前轨（下一拍就能规划）");
	}
}
