package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
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
 * L3 slice 8: task-target fork decisions on a RUNNING vehicle (the objective's "到节点按当前道岔状态/
 * 任务目标经 MmtrNodeRouter 选下一段" at engine level). A live retarget of the walker
 * ({@link MmtrMotionWalker#setTargetRailHex}) redirects the very next fork decision: the task target
 * overrides a stale operator setting, and on an unset fork the task target itself IS the authority
 * (never auto, but never stuck when the task says where to go). Once the target rail is boarded the
 * walker rests there (offset 0); clearing the target resumes the run.
 */
public final class MmtrMotionTaskTargetTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
		+ "}";

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/** Yard YR (-32..-20) -> mouth fork at (-20): {rA straight to (60,0,0) | rY diverge to (60,0,14)}. */
	private static final class Net {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-task-target"), false);
		final Position yardBack = new Position(-32, 0, 0);
		final Position yardMouth = new Position(-20, 0, 0);
		final Rail yardRail = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Rail rA = through(yardMouth, new Position(60, 0, 0));
		final Rail rY = through(yardMouth, new Position(60, 0, 14));
		final Depot depot = new Depot(TransportMode.TRAIN, sim);
		final Siding siding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);
		final BranchStore store = new BranchStore();

		Net() {
			depot.setName("Yard");
			depot.setCorners(new Position(-40, -5, -5), new Position(-10, 5, 5));
			sim.rails.add(yardRail);
			sim.rails.add(rA);
			sim.rails.add(rY);
			sim.depots.add(depot);
			sim.sidings.add(siding);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			siding.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			assertTrue(depot.savedRails.contains(siding), "siding must attach to the depot yard");
			siding.tick();
		}

		Vehicle spawnManual() {
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, store, null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			final UUID driver = UUID.randomUUID();
			final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
			entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
			vehicle.updateRidingEntities(entities);
			new MmtrDriveControl(vehicle.getId(), new ControlState().setThrottleNotch(3).setReverser(1), driver).apply(sim);
			return vehicle;
		}
	}

	@Test
	public void liveTaskRetargetOverridesStaleOperatorAtTheFork() {
		final Net n = new Net();
		// The operator has preset 0 = straight; the task redirects to the diverging rail mid-run.
		n.store.set(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId(), 0);
		final Vehicle v = n.spawnManual();
		final MmtrMotionWalker walker = v.getMmtrMotionWalker();
		final double midYard = walker.distanceM() + (n.yardRail.railMath.getLength() - walker.offsetM()) * 0.5;

		// Run until mid-yard, then retarget live: the diverging rail becomes the task target.
		boolean retargeted = false;
		for (int i = 0; i < 200 && !retargeted; i++) {
			n.siding.simulateVehicles(1000, null);
			if (v.getRailProgress() >= midYard) {
				walker.setTargetRailHex(n.rY.getHexId());
				retargeted = true;
			}
		}
		assertTrue(retargeted, "vehicle reached mid-yard for the live retarget, progress=" + v.getRailProgress());

		// The fork decision follows the TASK target (over the stale operator 0): boards rY and rests.
		boolean boarded = false;
		for (int i = 0; i < 300 && !boarded; i++) {
			n.siding.simulateVehicles(1000, null);
			boarded = n.rY.getHexId().equals(walker.railHex());
		}
		assertTrue(boarded, "task target must win over the stale operator 0 at the fork, rail=" + walker.railHex());
		assertTrue(walker.atTarget(), "walker rests on the task target rail");
		assertEquals(0, v.getSpeed(), 1e-9, "vehicle at rest on the task target rail");
		assertTrue(walker.offsetM() < 2.0, "rested just after boarding the target rail, offset=" + walker.offsetM());
		final double stalledProgress = v.getRailProgress();

		// Clearing the task target resumes the same run.
		walker.setTargetRailHex(null);
		double max = stalledProgress;
		for (int i = 0; i < 300; i++) {
			n.siding.simulateVehicles(1000, null);
			max = Math.max(max, v.getRailProgress());
		}
		assertTrue(max > stalledProgress + 5, "vehicle resumes running after the task target is cleared, got " + max);
		assertTrue(n.rY.getHexId().equals(walker.railHex()) || max > stalledProgress + 20, "resumed on the diverging rail");
	}

	@Test
	public void taskTargetIsTheAuthorityOnAnUnsetFork() {
		final Net n = new Net();
		final Vehicle v = n.spawnManual();
		final MmtrMotionWalker walker = v.getMmtrMotionWalker();
		assertFalse(walker.atTarget(), "no task target yet");
		// Arm the task target right away (no operator anywhere): the unset fork must NOT block the
		// vehicle once the task says where to go.
		walker.setTargetRailHex(n.rY.getHexId());

		boolean boarded = false;
		for (int i = 0; i < 400 && !boarded; i++) {
			n.siding.simulateVehicles(1000, null);
			boarded = n.rY.getHexId().equals(walker.railHex());
		}
		assertTrue(boarded, "task target acts as the authority at the unset fork, rail=" + walker.railHex());
		assertTrue(walker.atTarget(), "resting on the task target rail");
		assertEquals(0, v.getSpeed(), 1e-9, "at rest on the diverging rail");
		assertFalse(walker.haltedAtAuthority(), "never waited for an operator: the task decided");
	}
}
