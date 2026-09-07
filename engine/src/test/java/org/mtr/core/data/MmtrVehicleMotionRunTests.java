package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.MmtrMotionSnapshot;
import org.mtr.core.mmtr.point.MmtrPointRegistry;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.point.MmtrSwitch;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.operation.MmtrDriveControl;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3 first slice: a REAL Vehicle whose RUNNING motion is driven live by Motion Core. Unlike T3
 * (legs baked once at dispatch), the vehicle owns an {@link MmtrMotionWalker}: every tick the
 * existing cab control (ControlState) drives the MMTR physics model and the integrated distance
 * advances the walker, which elects the next rail AT EACH NODE from the CURRENT turnout state —
 * an unset fork halts the same vehicle (waiting, never auto), and flipping the branch mid-run
 * makes it continue onto the elected real rail without any re-spawn or path rebuild. railProgress,
 * render geometry and the mmtr-motion snapshot all follow the walker / its growing leg shadow.
 */
public final class MmtrVehicleMotionRunTests {

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

	/**
	 * Two real forks in a chain: rIn -> node0 {rStraight | rDiverge} -> (20,0,0) -> rBeyondA ->
	 * node1(40,0,0) {rC | rD}. Straight continuation stays live so one vehicle can be elected across
	 * TWO forks in a single run (multi-node live routing).
	 */
	private static final class Net {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-motion-run"), false);
		final Position node0 = new Position(0, 0, 0);
		final Position node1 = new Position(40, 0, 0);
		final Rail rIn = through(new Position(-20, 0, 0), node0);
		final Rail rStraight = through(node0, new Position(20, 0, 0));
		final Rail rDiverge = diverge(node0, new Position(20, 0, 12));
		final Rail rBeyondA = through(new Position(20, 0, 0), node1);
		final Rail rC = through(node1, new Position(60, 0, 0));
		final Rail rD = diverge(node1, new Position(60, 0, 12));

		Net() {
			sim.rails.add(rIn);
			sim.rails.add(rStraight);
			sim.rails.add(rDiverge);
			sim.rails.add(rBeyondA);
			sim.rails.add(rC);
			sim.rails.add(rD);
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

	/** A live-Motion-Core Vehicle standing at the entry of {@code startRail} (offset 0, no target). */
	private static Vehicle motionVehicle(Net n, BranchStore store, Rail startRail, Position startAt) {
		final VehicleExtraData ved = VehicleExtraData.createWithLegs(1L, 0L, 6, probeCars(), new ObjectArrayList<>(), 0.0004, 0.0004, true, 120, 30000L);
		final Vehicle v = new Vehicle(ved, null, TransportMode.TRAIN, n.sim);
		v.engageMmtrMotion(MmtrMotionWalker.start(n.sim, startRail, startAt, store, null));
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

	@Test
	public void motionVehicleHaltsAtUnsetForksAndFlipsLiveAcrossBoth() {
		final Net n = new Net();
		final BranchStore store = new BranchStore();
		final Vehicle v = motionVehicle(n, store, n.rIn, new Position(-20, 0, 0));
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vp = new ObjectArrayList<>();
		vp.add(new Object2ObjectAVLTreeMap<>());
		vp.add(new Object2ObjectAVLTreeMap<>());

		// Drive toward fork 0 (its end distance is the rIn rail length, ~20 m). No authority: the
		// SAME vehicle must stop at the node and wait - it never auto-selects.
		final double firstMax = driveTicks(v, 500, vp);
		assertTrue(firstMax < 21, "must halt before crossing an unset fork, got " + firstMax);
		assertEquals(0, v.getSpeed(), 1e-9, "vehicle is at rest while awaiting authority");
		assertTrue(v.getMmtrMotionWalker().haltedAtAuthority(), "walker reports awaiting authority at the fork");
		assertEquals(n.rIn.getHexId(), v.getMmtrMotionWalker().railHex(), "still on the approach rail");

		// Flip the fork to straight (live turnout change, same vehicle, same control) and detect the
		// crossing onto the straight real rail.
		store.set(n.node0.getX(), n.node0.getY(), n.node0.getZ(), n.rIn.getHexId(), 0);
		double maxAfterStraight = v.getRailProgress();
		boolean onStraight = false;
		for (int i = 0; i < 200 && !onStraight; i++) {
			vp.set(0, vp.get(1));
			vp.set(1, new Object2ObjectAVLTreeMap<>());
			v.simulate(1000, vp, null);
			maxAfterStraight = Math.max(maxAfterStraight, v.getRailProgress());
			onStraight = n.rStraight.getHexId().equals(v.getMmtrMotionWalker().railHex());
		}
		assertTrue(onStraight, "same vehicle must continue onto the straight real rail after the flip, got " + maxAfterStraight);
		assertTrue(v.getHeadPositionAndTiltAngle() != null && v.getHeadPositionAndTiltAngle().position().x() > 0, "head geometry follows the real rail after the flip");

		// It keeps going through the pass node and halts at fork 1 (unset, rIn+rStraight+rBeyondA
		// ~60 m) - a SECOND live halt, then the same vehicle is elected onto the diverging rail.
		final double secondMax = driveTicks(v, 600, vp);
		assertTrue(secondMax < 61.5, "must halt at the second unset fork, got " + secondMax);
		assertTrue(v.getMmtrMotionWalker().haltedAtAuthority(), "walker awaits authority at the second fork");
		assertEquals(n.rBeyondA.getHexId(), v.getMmtrMotionWalker().railHex(), "approaching the second fork on the continuation rail");

		store.set(n.node1.getX(), n.node1.getY(), n.node1.getZ(), n.rBeyondA.getHexId(), 1);
		double maxAfterSecondFlip = secondMax;
		boolean onDiverge2 = false;
		for (int i = 0; i < 400 && !onDiverge2; i++) {
			v.simulate(1000, null, null);
			maxAfterSecondFlip = Math.max(maxAfterSecondFlip, v.getRailProgress());
			onDiverge2 = n.rD.getHexId().equals(v.getMmtrMotionWalker().railHex());
		}
		assertTrue(onDiverge2, "same vehicle must cross the second fork onto the diverging rail, got " + maxAfterSecondFlip);
		assertEquals(n.rD.getHexId(), v.getMmtrMotionWalker().railHex(), "vehicle now runs on the second fork's diverging real rail");
	}

	@Test
	public void motionVehicleFlipsToDivergeAtFirstFork() {
		final Net n = new Net();
		final BranchStore store = new BranchStore();
		final Vehicle v = motionVehicle(n, store, n.rIn, new Position(-20, 0, 0));
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));

		driveTicks(v, 300, null);
		assertTrue(v.getMmtrMotionWalker().haltedAtAuthority(), "awaits authority at the unset fork");

		store.set(n.node0.getX(), n.node0.getY(), n.node0.getZ(), n.rIn.getHexId(), 1);
		final double max = driveTicks(v, 300, null);
		assertTrue(max > 21, "same vehicle must continue onto the diverging real rail after the flip, got " + max);
		assertEquals(n.rDiverge.getHexId(), v.getMmtrMotionWalker().railHex(), "vehicle runs on the diverging rail");
	}

	@Test
	public void sidingHostedMotionVehicleDrivenByExistingCommandFlipsLive() {
		final Net n = new Net();
		final BranchStore store = new BranchStore();
		// Host the vehicle in a real yard siding so the operation layer can find and drive it.
		final Siding siding = new Siding(new Position(-25, 0, -2), new Position(-22, 0, 2), 8, TransportMode.TRAIN, n.sim);
		siding.setVehicleCars(probeCars());
		n.sim.sidings.add(siding);
		// A walker on the approach rail; engage live motion on the spawned vehicle.
		final ObjectArrayList<PathData> starterLegs = MmtrMotionWalker.start(n.sim, n.rIn, new Position(-20, 0, 0), store, null).buildLegs();
		final Vehicle spawned = siding.spawnMmtrManualWithLegs(starterLegs);
		assertNotNull(spawned, "seam must stage the yard vehicle");
		spawned.engageMmtrMotion(MmtrMotionWalker.start(n.sim, n.rIn, new Position(-20, 0, 0), store, null));
		assertTrue(spawned.isMmtrMotion(), "vehicle runs in live Motion-Core mode");

		// A driver rides the cab; the existing operation-layer drive command drives it.
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		spawned.updateRidingEntities(entities);
		new MmtrDriveControl(spawned.getId(), new ControlState().setThrottleNotch(3).setReverser(1), driver).apply(n.sim);
		assertTrue(spawned.isMmtrManualOverride(), "drive command must hold the MMTR override");

		driveTicks(spawned, 300, null);
		assertTrue(spawned.getMmtrMotionWalker().haltedAtAuthority(), "motion vehicle halts at the unset fork");

		// Live flip through the authoritative turnout state.
		store.set(n.node0.getX(), n.node0.getY(), n.node0.getZ(), n.rIn.getHexId(), 0);
		double max = spawned.getRailProgress();
		boolean crossed = false;
		for (int i = 0; i < 120 && !crossed; i++) {
			spawned.simulate(1000, null, null);
			max = Math.max(max, spawned.getRailProgress());
			crossed = max > 21.1;
		}
		assertTrue(crossed, "siding-hosted motion vehicle must cross onto the straight rail after the flip, got " + max);
		assertEquals(n.rStraight.getHexId(), spawned.getMmtrMotionWalker().railHex(), "vehicle runs on the straight rail");

		// The mmtr-motion snapshot reports the live (segment, offset) state right after the crossing.
		final MmtrMotionSnapshot snapshot = MmtrMotionSnapshot.from(siding, spawned);
		assertEquals(20.0, snapshot.segEndX, 1e-6, "snapshot segment ends at the straight rail far node");
		assertEquals(0.0, snapshot.segStartX, 1e-6, "snapshot segment starts at the fork node");
		assertTrue(snapshot.segmentOffsetM > 0 && snapshot.segmentOffsetM < 20, "snapshot carries the live segment offset, got " + snapshot.segmentOffsetM);
		assertTrue(snapshot.moving, "snapshot reports the vehicle moving right after the live flip");

		// It continues through the pass node and halts at the second unset fork (~60 m) - live multi-node.
		final double secondMax = driveTicks(spawned, 400, null);
		assertTrue(secondMax < 61.5, "must halt at the second unset fork, got " + secondMax);
		assertTrue(spawned.getMmtrMotionWalker().haltedAtAuthority(), "awaits authority at the second fork");
		assertEquals(n.rBeyondA.getHexId(), spawned.getMmtrMotionWalker().railHex(), "approaching the second fork on the continuation rail");
	}

	// ---- Real dev world: the -96 yard fork; live flip on a real Vehicle through the authoritative store ----

	private static final Path DEV_MTR_ROOT = Paths.get("C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/saves/新的世界/mtr");
	private static final long NX = -96, NY = -60, NZ = 76;

	private static MmtrSwitch devSwitch(Simulator sim) {
		final ObjectArrayList<MmtrSwitch> all = MmtrPointRegistry.discover(sim);
		for (final MmtrSwitch s : all) {
			if (s.nodeX == NX && s.nodeY == NY && s.nodeZ == NZ) {
				return s;
			}
		}
		return null;
	}

	private static Rail findRailByHex(Simulator sim, String hex) {
		final Rail[] found = {null};
		sim.positionsToRail.forEach((pos, neigh) -> neigh.forEach((q, rail) -> {
			if (found[0] == null && rail.getHexId().equals(hex)) {
				found[0] = rail;
			}
		}));
		return found[0];
	}

	private static Position otherEnd(Simulator sim, Position at, Rail rail) {
		final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<Position, Rail> neighbors = sim.positionsToRail.get(at);
		if (neighbors == null) {
			return null;
		}
		for (final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap.Entry<Position, Rail> e : neighbors.object2ObjectEntrySet()) {
			if (e.getValue() == rail) {
				return e.getKey();
			}
		}
		return null;
	}

	private static double driveUntilRail(Simulator sim, BranchStore store, Rail via, Position start, String targetHex, int maxTicks) {
		final VehicleExtraData ved = VehicleExtraData.createWithLegs(1L, 0L, 8, probeCars(), new ObjectArrayList<>(), 0.0004, 0.0004, true, 120, 30000L);
		final Vehicle v = new Vehicle(ved, null, TransportMode.TRAIN, sim);
		v.engageMmtrMotion(MmtrMotionWalker.start(sim, via, start, store, null));
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		double max = v.getRailProgress();
		for (int i = 0; i < maxTicks; i++) {
			v.simulate(1000, null, null);
			max = Math.max(max, v.getRailProgress());
			if (targetHex.equals(v.getMmtrMotionWalker().railHex())) {
				return max;
			}
		}
		return max;
	}

	@Test
	public void realDevYardMotionVehicleCrossesMinus96ByLiveFlip() {
		Assumptions.assumeTrue(Files.isDirectory(DEV_MTR_ROOT), "dev world save not present - skipping");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, DEV_MTR_ROOT, false);
		final MmtrSwitch sw = devSwitch(sim);
		Assumptions.assumeTrue(sw != null, "no turnout at -96");
		final Position node = new Position(NX, NY, NZ);
		final Rail via = findRailByHex(sim, sw.viaRailHex);
		Assumptions.assumeTrue(via != null, "via rail missing");
		final Position start = otherEnd(sim, node, via);
		Assumptions.assumeTrue(start != null, "via rail has a far endpoint");

		// Live authority through the simulator's authoritative branch store (what mmtr-point-op mutates).
		sim.mmtrPointBranches.set(NX, NY, NZ, sw.viaRailHex, 1);
		final double max1 = driveUntilRail(sim, sim.mmtrPointBranches, via, start, sw.branch1Hex, 3000);
		assertTrue(max1 > 0, "motion vehicle never advanced");
		assertEquals(sw.branch1Hex, findRailByHex(sim, sw.branch1Hex).getHexId(), "vehicle boarded the branch1 real rail");

		// Second vehicle: flip to 0 and the same live machinery takes the straight real rail.
		sim.mmtrPointBranches.set(NX, NY, NZ, sw.viaRailHex, 0);
		final double max0 = driveUntilRail(sim, sim.mmtrPointBranches, via, start, sw.branch0Hex, 3000);
		assertTrue(max0 > 0, "motion vehicle never advanced on branch0");
		assertEquals(sw.branch0Hex, findRailByHex(sim, sw.branch0Hex).getHexId(), "vehicle boarded the branch0 real rail");
	}
}
