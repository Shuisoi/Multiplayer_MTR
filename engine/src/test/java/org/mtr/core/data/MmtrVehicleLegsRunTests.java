package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.point.MmtrPointRegistry;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.point.MmtrSwitch;
import org.mtr.core.mmtr.segment.MmtrMotionDriver;
import org.mtr.core.operation.MmtrDriveControl;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Files;
import java.util.UUID;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3 (Vehicle body adopts Motion Core): a REAL Vehicle whose running path is supplied as Motion Core
 * legs (createWithLegs from MmtrMotionWalker.buildLegs) actually advances its railProgress along the
 * real elected rails and, when the operator branch is flipped, is rerouted onto the OTHER real rail.
 * T3b: the same Vehicle is driven by the engine's EXISTING cab control (ControlState via
 * applyMmtrControl) - Motion Core is the motion underneath the existing control, not a new control
 * system. Synthetic (no dev save): a real fork on real rails is routed branch0 vs branch1 and each leg
 * set is handed to a live Vehicle through createWithLegs. Deterministic and always green.
 */
public final class MmtrVehicleLegsRunTests {

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

	private static final class Net {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-vehlegs"), false);
		final Position node0 = new Position(0, 0, 0);
		final Rail rIn = through(new Position(-20, 0, 0), node0);
		final Rail rStraight = through(node0, new Position(20, 0, 0));
		final Rail rDiverge = diverge(node0, new Position(20, 0, 12));
		final Rail rBeyondA = through(new Position(20, 0, 0), new Position(40, 0, 0));
		final Rail rBeyondB = diverge(new Position(20, 0, 12), new Position(40, 0, 12));

		Net() {
			sim.rails.add(rIn);
			sim.rails.add(rStraight);
			sim.rails.add(rDiverge);
			sim.rails.add(rBeyondA);
			sim.rails.add(rBeyondB);
			sim.sync();
			// MMTR physics policy so the existing cab control (applyMmtrControl) has a consist model.
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
		}

		BranchStore branch(int op) {
			final BranchStore s = new BranchStore();
			s.set(node0.getX(), node0.getY(), node0.getZ(), rIn.getHexId(), op);
			/*
			 * notes/235：车列现在是**活走行体**（每一处岔按现场位置现场选定），不再照抄一份"预制进路"。
			 * 所以"岔开哪一边"必须落在**运行中的操作位**上（{@code sim.mmtrPointBranches}），
			 * 只交给建腿时用的那个临时 store 是不够的 —— 走行体看不到它。
			 */
			sim.mmtrPointBranches.set(node0.getX(), node0.getY(), node0.getZ(), rIn.getHexId(), op);
			return s;
		}
	}

	private static ObjectArrayList<org.mtr.core.data.PathData> legsFor(Net n, int operatorBranch, String targetHex) {
		final MmtrMotionDriver d = MmtrMotionDriver.start(n.sim, n.rIn, new Position(-20, 0, 0), n.branch(operatorBranch), targetHex);
		d.driveToRest(1000, 0.004, 60);
		return d.walker.buildLegs();
	}

	private static Rail railAt(ObjectArrayList<org.mtr.core.data.PathData> legs, int index) {
		return legs.get(index).getRail();
	}

	/** Farthest railProgress a real Vehicle driven by the existing cab control reached along {@code legs}. */
	private static double driveWithExistingControl(Simulator sim, ObjectArrayList<org.mtr.core.data.PathData> legs) {
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
		final VehicleExtraData ved = VehicleExtraData.createWithLegs(1L, 0L, 6, cars, legs, true, 120, 30000L);
		final Vehicle v = new Vehicle(ved, null, TransportMode.TRAIN, sim);
		// T3b: existing engine cab control (ControlState) drives this Motion-legs Vehicle.
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		assertTrue(v.isMmtrManualOverride(), "existing cab control must hold the MMTR override");
		double max = v.getRailProgress();
		for (int i = 0; i < 80; i++) {
			v.simulate(1000, null, null);
			max = Math.max(max, v.getRailProgress());
		}
		return max;
	}

	private static int indexAt(ObjectArrayList<org.mtr.core.data.PathData> legs, double progress) {
		for (int i = 0; i < legs.size(); i++) {
			if (legs.get(i).getEndDistance() > progress) { return i; }
		}
		return legs.size() - 1;
	}

	@Test
	public void realVehicleRunsMotionLegsAcrossForkOntoStraight() {
		final Net n = new Net();
		final ObjectArrayList<org.mtr.core.data.PathData> legs = legsFor(n, 0, n.rStraight.getHexId());
		assertEquals(n.rStraight.getHexId(), railAt(legs, 1).getHexId(), "branch0 leg sequence chooses the straight real rail");

		final double max = driveWithExistingControl(n.sim, legs);
		assertTrue(max > 21, "real Vehicle must cross the fork (approach leg ends at 20 m) under existing cab control, got " + max);
		assertEquals(n.rStraight.getHexId(), railAt(legs, indexAt(legs, max)).getHexId(), "on-route Vehicle index sits on the straight rail after crossing");
	}

	@Test
	public void flippingBranchReroutesRealVehicleOntoDiverge() {
		final Net n = new Net();
		final ObjectArrayList<org.mtr.core.data.PathData> legs = legsFor(n, 1, n.rDiverge.getHexId());
		assertEquals(n.rDiverge.getHexId(), railAt(legs, 1).getHexId(), "branch1 leg sequence chooses the diverging real rail");

		final double max = driveWithExistingControl(n.sim, legs);
		assertTrue(max > 21, "real Vehicle must cross the fork onto the diverging rail, got " + max);
		assertEquals(n.rDiverge.getHexId(), railAt(legs, indexAt(legs, max)).getHexId(), "on-route Vehicle index sits on the diverging rail after flipping branch");
	}


	// ---- Real dev world: the -96 yard fork, real Vehicle with Motion legs driven by existing cab control ----

	private static final Path DEV_MTR_ROOT = Paths.get("C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/saves/新的世界/mtr");
	private static final long NX = -96, NY = -60, NZ = 76;

	/** Farthest progress a real dev-yard Vehicle (Motion legs) reaches under the existing cab control. */
	private static double driveDevVehicle(Simulator sim, ObjectArrayList<org.mtr.core.data.PathData> legs) {
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("probe", 4, 1, 10, 0, 2, 0.1, 0.1));
		final VehicleExtraData ved = VehicleExtraData.createWithLegs(1L, 0L, 8, cars, legs, true, 120, 30000L);
		final Vehicle v = new Vehicle(ved, null, TransportMode.TRAIN, sim);
		v.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		double max = v.getRailProgress();
		for (int i = 0; i < 600; i++) {
			v.simulate(1000, null, null);
			max = Math.max(max, v.getRailProgress());
		}
		return max;
	}

	private static MmtrSwitch devSwitch(Simulator sim) {
		final ObjectArrayList<MmtrSwitch> all = MmtrPointRegistry.discover(sim);
		for (final MmtrSwitch s : all) { if (s.nodeX == NX && s.nodeY == NY && s.nodeZ == NZ) { return s; } }
		return null;
	}

	private static Rail findRailByHex(Simulator sim, String hex) {
		final Rail[] found = {null};
		sim.positionsToRail.forEach((pos, neigh) -> neigh.forEach((q, rail) -> { if (found[0] == null && rail.getHexId().equals(hex)) { found[0] = rail; } }));
		return found[0];
	}

	@Test
	public void realDevYardVehicleCrossesMinus96ByAuthority() {
		Assumptions.assumeTrue(Files.isDirectory(DEV_MTR_ROOT), "dev world save not present - skipping");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, DEV_MTR_ROOT, false);
		final MmtrSwitch sw = devSwitch(sim);
		Assumptions.assumeTrue(sw != null, "no turnout at -96");
		final Position node = new Position(NX, NY, NZ);
		final Rail via = findRailByHex(sim, sw.viaRailHex);
		Assumptions.assumeTrue(via != null, "via rail missing");
		final Position start = otherEnd(sim, node, via);
		Assumptions.assumeTrue(start != null, "via rail has a far endpoint");

		// branch0 -> a real Vehicle whose path is the Motion-Core chosen via+branch0 sequence.
		final MmtrMotionDriver d0 = MmtrMotionDriver.start(sim, via, start, devBranch(sim, via, 0), sw.branch0Hex);
		d0.driveToRest(1000, 0.004, 200);
		Assumptions.assumeTrue(d0.atTarget(), "branch0 should route onto the straight real rail");
		final ObjectArrayList<org.mtr.core.data.PathData> legs0 = d0.walker.buildLegs();
		assertEquals(sw.branch0Hex, railAt(legs0, legs0.size() - 1).getHexId(), "branch0 leg sequence ends on the straight rail");
		final double fork0 = legs0.get(0).getEndDistance();
		final double max0 = driveDevVehicle(sim, legs0);
		assertTrue(max0 > fork0 + 0.5, "real Vehicle must cross the -96 fork onto the straight rail, got " + max0 + " (fork at " + fork0 + ")");
		assertEquals(sw.branch0Hex, railAt(legs0, indexAt(legs0, max0)).getHexId(), "Vehicle index sits on branch0 real rail after crossing");

		// branch1 -> rerouted onto the diverging real rail.
		final MmtrMotionDriver d1 = MmtrMotionDriver.start(sim, via, start, devBranch(sim, via, 1), sw.branch1Hex);
		d1.driveToRest(1000, 0.004, 200);
		Assumptions.assumeTrue(d1.atTarget(), "branch1 should route onto the diverging real rail");
		final ObjectArrayList<org.mtr.core.data.PathData> legs1 = d1.walker.buildLegs();
		assertEquals(sw.branch1Hex, railAt(legs1, legs1.size() - 1).getHexId(), "branch1 leg sequence ends on the diverging rail");
		final double fork1 = legs1.get(0).getEndDistance();
		final double max1 = driveDevVehicle(sim, legs1);
		assertTrue(max1 > fork1 + 0.5, "real Vehicle must cross the -96 fork onto the diverging rail, got " + max1 + " (fork at " + fork1 + ")");
		assertEquals(sw.branch1Hex, railAt(legs1, indexAt(legs1, max1)).getHexId(), "Vehicle index sits on branch1 real rail after crossing");
	}

	private static BranchStore devBranch(Simulator sim, Rail via, int op) {
		final BranchStore s = new BranchStore();
		s.set(NX, NY, NZ, via.getHexId(), op);
		// notes/235：同上 —— 活走行体读的是**运行中的操作位**，所以这里也要落到 sim 上。
		sim.mmtrPointBranches.set(NX, NY, NZ, via.getHexId(), op);
		return s;
	}

	private static Position otherEnd(Simulator sim, Position at, Rail rail) {
		final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<Position, Rail> neighbors = sim.positionsToRail.get(at);
		if (neighbors == null) { return null; }
		for (final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap.Entry<Position, Rail> e : neighbors.object2ObjectEntrySet()) { if (e.getValue() == rail) { return e.getKey(); } }
		return null;
	}


	@Test
	public void sidingSeamSpawnsMotionLegsManualVehicle() {
		final Net n = new Net();
		final ObjectArrayList<org.mtr.core.data.PathData> legs = legsFor(n, 0, n.rStraight.getHexId());
		// A bare yard siding whose cars template is set, then a real Vehicle is dispatched on Motion legs.
		final Siding siding = new Siding(new Position(-25, 0, -2), new Position(-22, 0, 2), 8, TransportMode.TRAIN, n.sim);
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
		siding.setVehicleCars(cars);
		final Vehicle spawned = siding.spawnMmtrManualWithLegs(legs);
		org.junit.jupiter.api.Assertions.assertNotNull(spawned, "seam must spawn a Motion-legs manual vehicle");
		assertTrue(spawned.isMmtrManualOverride() || spawned.vehicleExtraData.getIsManualAllowed(), "spawned vehicle is manual-allowed");
		assertEquals(legs.size(), spawned.vehicleExtraData.immutablePath.size(), "spawned vehicle path is exactly the Motion Core legs");
		assertEquals(n.rStraight.getHexId(), spawned.vehicleExtraData.immutablePath.get(1).getRail().getHexId(), "vehicle path carries the Motion-chosen straight rail");

		// The spawned yard vehicle is drivable by the existing cab control.
		spawned.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		double max = spawned.getRailProgress();
		for (int i = 0; i < 80; i++) {
			spawned.simulate(1000, null, null);
			max = Math.max(max, spawned.getRailProgress());
		}
		assertTrue(max > 21, "Motion-legs vehicle spawned by the seam must advance past the fork, got " + max);
	}


	@Test
	public void existingDriveCommandLayerDrivesMotionLegsVehicle() {
		final Net n = new Net();
		final ObjectArrayList<org.mtr.core.data.PathData> legs = legsFor(n, 0, n.rStraight.getHexId());
		// A real yard siding with the Motion-legs vehicle staged, so the drive command can find it.
		final Siding siding = new Siding(new Position(-25, 0, -2), new Position(-22, 0, 2), 8, TransportMode.TRAIN, n.sim);
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
		siding.setVehicleCars(cars);
		n.sim.sidings.add(siding);
		final Vehicle spawned = siding.spawnMmtrManualWithLegs(legs);
		org.junit.jupiter.api.Assertions.assertNotNull(spawned, "seam must stage a Motion-legs manual vehicle");

		// A driver sits in the cab; the existing operation-layer drive command drives the train.
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		spawned.updateRidingEntities(entities);
		new MmtrDriveControl(spawned.getId(), new ControlState().setThrottleNotch(3).setReverser(1), driver).apply(n.sim);
		assertTrue(spawned.isMmtrManualOverride(), "drive command must engage the MMTR override through the operation layer");

		double max = spawned.getRailProgress();
		for (int i = 0; i < 80; i++) {
			spawned.simulate(1000, null, null);
			max = Math.max(max, spawned.getRailProgress());
		}
		assertTrue(max > 21, "operation-layer-driven Motion vehicle must advance past the fork, got " + max);
		assertEquals(n.rStraight.getHexId(), railAt(legs, indexAt(legs, max)).getHexId(), "on-route index sits on the straight rail after the drive command moved it");
	}

}
