package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
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
 * ① 区间式信号与道岔配合: the section stop must respect the TURNOUT at the far end of the block ahead.
 *
 * <p>Before this rule a train ran the whole block and parked at the points, occupying the block (and
 * its junction) while it waited for the operator/authority - and the red signal that should have held
 * it stood behind. Now the train is held at the signal BEFORE that block, so the block containing the
 * turnout stays clear and "signal red = train stopped at the signal" holds for manual and auto alike.
 *
 * <p>Network: yard Y(-20..-8, siding) -&gt; W(-8..12) -&gt; X(12..52) -&gt; fork(52) {S(52..92) |
 * D(52 -&gt; 92,+12)}. The fork sits at X's far node, so a train running W -&gt; X must be held at the
 * end of W until that fork has a decision.</p>
 */
public final class MmtrSectionAuthorityStopTests {

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

	private static final class ForkNet {
		final Simulator sim;
		final Position fork = new Position(52, 0, 0);
		final Rail w;
		final Rail x;
		final Rail s;
		final Rail d;
		final Siding siding;
		final BranchStore store = new BranchStore();
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = newTrees();
		/** Auto stop target: 30 m into S, i.e. far beyond the fork. */
		final double stopTargetM = 12.0 + 20.0 + 40.0 + 30.0;

		ForkNet(String savePath) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			final Position yardBack = new Position(-20, 0, 0);
			final Position yardMouth = new Position(-8, 0, 0);
			final Rail y = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			w = through(yardMouth, new Position(12, 0, 0));
			x = through(new Position(12, 0, 0), fork);
			s = through(fork, new Position(92, 0, 0));
			d = diverge(fork, new Position(92, 0, 12));
			final Depot depot = new Depot(TransportMode.TRAIN, sim);
			siding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);
			depot.setName("Fork Yard");
			depot.setCorners(new Position(-25, -3, -3), new Position(100, 3, 15));
			sim.rails.add(y);
			sim.rails.add(w);
			sim.rails.add(x);
			sim.rails.add(s);
			sim.rails.add(d);
			sim.depots.add(depot);
			sim.sidings.add(siding);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			siding.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			assertTrue(depot.savedRails.contains(siding), "the yard siding must attach to the depot");
			siding.tick();
			sim.mmtrEnsureSignalColors();
		}

		Vehicle spawn() {
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, store, null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			return vehicle;
		}

		void tick() {
			trees.removeFirst();
			trees.add(new Object2ObjectAVLTreeMap<>());
			siding.simulateVehicles(1000, trees);
		}

		void tickUntil(java.util.function.BooleanSupplier condition, int maxTicks) {
			for (int i = 0; i < maxTicks && !condition.getAsBoolean(); i++) {
				tick();
			}
			assertTrue(condition.getAsBoolean(), "condition not met within " + maxTicks + " ticks");
		}

		void setStraight() {
			store.set(fork.getX(), fork.getY(), fork.getZ(), x.getHexId(), 0);
		}
	}

	private static ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> newTrees() {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = new ObjectArrayList<>();
		trees.add(new Object2ObjectAVLTreeMap<>());
		trees.add(new Object2ObjectAVLTreeMap<>());
		return trees;
	}

	@Test
	public void aTrainIsHeldAtTheSignalBeforeTheBlockThatEndsAtAnUnsetTurnout() {
		final ForkNet n = new ForkNet("build/mmtr-authority-hold");
		final Vehicle v = n.spawn();
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(n.stopTargetM, true);

		// The fork is unset: the train must rest at the END OF W (the boundary before X, whose far node
		// is the turnout), not inside X at the points.
		n.tickUntil(() -> v.getSpeed() == 0 && v.isMmtrBlockHeldFromSync(), 4000);
		assertEquals(n.w.getHexId(), v.getMmtrMotionWalker().railHex(), "held on the approach rail, not inside the block with the turnout");
		assertEquals(20.0 - 0.001, v.getMmtrMotionWalker().offsetM(), 0.05, "rests epsilon short of the boundary before that block");
		assertFalse(n.x.getHexId().equals(v.getMmtrMotionWalker().railHex()), "the block containing the turnout stays clear");
		assertFalse(v.getMmtrMotionWalker().haltedAtAuthority(), "the walker is not at the points - the section stop holds it");
		assertFalse(v.isMmtrMotionStoppedAtTarget(), "a block hold is not a task arrival");

		// The hold is stable while the fork stays unset, and the train never creeps into X.
		final double held = v.getMmtrMotionWalker().offsetM();
		for (int i = 0; i < 60; i++) {
			n.tick();
		}
		assertEquals(held, v.getMmtrMotionWalker().offsetM(), 1e-6, "stays at the signal while the turnout is unset");
		assertEquals(n.w.getHexId(), v.getMmtrMotionWalker().railHex(), "still has not entered the block with the turnout");

		// The operator sets the fork straight: the same held train runs through X, across the points and
		// onto S - no fresh command needed (the block hold is not terminal).
		n.setStraight();
		n.tickUntil(() -> n.s.getHexId().equals(v.getMmtrMotionWalker().railHex()), 4000);
		assertEquals(n.s.getHexId(), v.getMmtrMotionWalker().railHex(), "proceeds onto the elected leg once the fork is set");
	}

	@Test
	public void aSetTurnoutLetsTheTrainRunThroughWithoutStoppingAtTheBoundary() {
		final ForkNet n = new ForkNet("build/mmtr-authority-set");
		n.setStraight();
		final Vehicle v = n.spawn();
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(n.stopTargetM, true);

		// With the fork already set the block is cleared for the movement: the train crosses the W/X
		// boundary without stopping and never sets the block-hold flag on the way.
		n.tickUntil(() -> n.x.getHexId().equals(v.getMmtrMotionWalker().railHex()) || n.s.getHexId().equals(v.getMmtrMotionWalker().railHex()), 4000);
		assertFalse(v.isMmtrBlockHeldFromSync(), "no hold when the turnout is already set for this movement");
		n.tickUntil(() -> n.s.getHexId().equals(v.getMmtrMotionWalker().railHex()), 4000);
		assertEquals(n.s.getHexId(), v.getMmtrMotionWalker().railHex(), "runs straight through onto S");
	}
}
