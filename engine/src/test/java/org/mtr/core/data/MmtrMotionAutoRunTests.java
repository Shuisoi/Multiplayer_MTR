package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3 slice 5: unmanned auto step-run in live Motion-Core mode. With {@link Vehicle#setMmtrMotionAuto}
 * enabled and a stop target armed, the vehicle drives itself (no driver/override at all) to the exact
 * stop, holds there, and departs automatically when the next stop target is armed (the task owns the
 * dwell). An unset fork halts the auto run exactly like a manual one - and flipping the branch lets
 * the same auto run continue to its armed target. This is the engine-level foundation the task /
 * SERVE executor will drive: one stop target per step, no cab input required.
 */
public final class MmtrMotionAutoRunTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
		+ "}";

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/** Straight yard chain: YR (-32..-20, buffer at -32) -> MA (-20..0) -> PL (0..60). */
	private static final class StraightNet {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-auto-run"), false);
		final Position yardBack = new Position(-32, 0, 0);
		final Position yardMouth = new Position(-20, 0, 0);
		final Rail yardRail = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Rail mouthRail = through(yardMouth, new Position(0, 0, 0));
		final Rail platformRail = through(new Position(0, 0, 0), new Position(60, 0, 0));
		final Depot depot = new Depot(TransportMode.TRAIN, sim);
		final Siding siding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);
		final double firstTargetM;

		StraightNet() {
			depot.setName("Yard");
			depot.setCorners(new Position(-40, -5, -5), new Position(-10, 5, 5));
			sim.rails.add(yardRail);
			sim.rails.add(mouthRail);
			sim.rails.add(platformRail);
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
			firstTargetM = yardRail.railMath.getLength() + mouthRail.railMath.getLength() + 18.0;
		}

		Vehicle spawn() {
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, new BranchStore(), null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			return vehicle;
		}
	}

	/** Fork right at the yard mouth: YR (-32..-20) then {rA straight | rB diverge}. */
	private static final class ForkNet {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-auto-run-fork"), false);
		final Position yardBack = new Position(-32, 0, 0);
		final Position yardMouth = new Position(-20, 0, 0);
		final Rail yardRail = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Rail rA = through(yardMouth, new Position(100, 0, 0));
		final Rail rB = through(yardMouth, new Position(100, 0, 14));
		final Depot depot = new Depot(TransportMode.TRAIN, sim);
		final Siding siding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);
		final BranchStore store = new BranchStore();
		final double targetM;

		ForkNet() {
			depot.setName("Yard");
			depot.setCorners(new Position(-40, -5, -5), new Position(-10, 5, 5));
			sim.rails.add(yardRail);
			sim.rails.add(rA);
			sim.rails.add(rB);
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
			targetM = yardRail.railMath.getLength() + 40.0;
		}

		Vehicle spawn() {
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, store, null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			return vehicle;
		}
	}

	private static void tickUntil(Siding siding, java.util.function.BooleanSupplier condition, int maxTicks) {
		for (int i = 0; i < maxTicks && !condition.getAsBoolean(); i++) {
			siding.simulateVehicles(1000, null);
		}
		assertTrue(condition.getAsBoolean(), "condition not met within " + maxTicks + " ticks");
	}

	@Test
	public void autoRunDrivesTwoStepStopsWithoutAnyDriver() {
		final StraightNet n = new StraightNet();
		final Vehicle v = n.spawn();
		v.setMmtrMotionAuto(true);
		assertTrue(v.isMmtrMotionAuto(), "auto run enabled");
		v.setMmtrMotionStopTarget(n.firstTargetM, true);

		tickUntil(n.siding, v::isMmtrMotionStoppedAtTarget, 3000);
		assertTrue(v.getRailProgress() > 10, "vehicle actually ran, progress=" + v.getRailProgress());
		assertEquals(n.firstTargetM, v.getRailProgress(), 0.05, "first auto stop exact");
		assertEquals(0, v.getSpeed(), 1e-9, "auto run rests at the stop");
		assertTrue(v.vehicleExtraData.getDoorMultiplier() > 0, "first auto stop opens doors");
		assertFalse(v.isMmtrManualOverride(), "no driver/override was ever involved");

		// The task arms the next stop while stopped: the auto run departs by itself.
		final double secondTarget = n.firstTargetM + 25.0;
		v.setMmtrMotionStopTarget(secondTarget, false);
		tickUntil(n.siding, v::isMmtrMotionStoppedAtTarget, 3000);
		assertEquals(secondTarget, v.getRailProgress(), 0.05, "second auto stop exact after automatic departure");
		assertTrue(v.getRailProgress() > n.firstTargetM + 5, "auto run left the first stop on its own");
		assertFalse(v.vehicleExtraData.getDoorMultiplier() > 0, "second auto stop was requested without doors");
		assertEquals(0, v.getSpeed(), 1e-9, "resting at the second stop");
	}

	@Test
	public void autoRunHaltsAtUnsetForkAndContinuesToTargetAfterFlip() {
		final ForkNet n = new ForkNet();
		final Vehicle v = n.spawn();
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(n.targetM, true);

		// Runs out of the yard and halts at the unset yard-mouth fork (no authority, never auto).
		boolean stalled = false;
		for (int i = 0; i < 3000 && !stalled; i++) {
			n.siding.simulateVehicles(1000, null);
			stalled = v.getMmtrMotionWalker().haltedAtAuthority() && n.yardRail.getHexId().equals(v.getMmtrMotionWalker().railHex());
		}
		assertTrue(stalled, "auto run must halt at the unset yard-mouth fork, rail=" + v.getMmtrMotionWalker().railHex() + " progress=" + v.getRailProgress());
		final double stalledProgress = v.getRailProgress();
		final double yardLen = n.yardRail.railMath.getLength();
		assertTrue(Math.abs(stalledProgress - yardLen) < 1.0, "stalled at the yard end, got " + stalledProgress + " vs " + yardLen);
		for (int i = 0; i < 20; i++) {
			n.siding.simulateVehicles(1000, null);
		}
		assertEquals(stalledProgress, v.getRailProgress(), 1e-6, "no movement while the fork is unset");

		// Flip the fork to the straight rail: the SAME auto run continues and stops exactly on target.
		n.store.set(n.yardMouth.getX(), n.yardMouth.getY(), n.yardMouth.getZ(), n.yardRail.getHexId(), 0);
		tickUntil(n.siding, v::isMmtrMotionStoppedAtTarget, 3000);
		assertEquals(n.targetM, v.getRailProgress(), 0.05, "auto stop exact after the live flip");
		assertEquals(n.rA.getHexId(), v.getMmtrMotionWalker().railHex(), "auto run crossed onto the straight rail");
		assertTrue(v.vehicleExtraData.getDoorMultiplier() > 0, "stop opens doors");
		assertFalse(v.isMmtrManualOverride(), "flip + auto continue needed no driver");
	}
}
