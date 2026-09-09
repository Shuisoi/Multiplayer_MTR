package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Vector;

import java.nio.file.Paths;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2: S1 stops at a SECTION boundary, not at a rail end. When a wayside signal splits the rail the
 * train is on, the section beyond that signal is the next block - if it is occupied the train draws up
 * to the SIGNAL and waits there (proper 闭塞区间 working: one train per section), instead of following
 * the tail of the train ahead 2 m into the occupied section as the pre-B2 whole-rail rule did.
 *
 * <p>Rails with no signals keep the old behaviour exactly: their only boundary is the rail end, so the
 * follower closes up to the tail of the train ahead inside that one big section. Both halves are
 * asserted below on the same track layout, once with the signal registered and once without.</p>
 */
public final class MmtrBlockSectionStopTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
		+ "}";
	private static final long FOREIGN_VEHICLE_ID = 999_999_003L;

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/**
	 * Yard Y (-20..-8, siding, 12 m) -> A (-8..192, 200 m) -> B (192..392, 200 m). A foreign train
	 * occupies arc 120..140 of A for the whole run; the wayside signal (when present) stands at arc 100
	 * of A and is bound to A, so A is cut into [0,100] and [100,200].
	 */
	private static final class SectionNet {
		final Simulator sim;
		final Rail y;
		final Rail a;
		final Rail b;
		final Siding siding;
		final double signalArcM = 100.0;
		final double foreignFromM = 120.0;
		final double foreignToM = 140.0;
		/** Auto stop target: 60 m into B, far beyond everything that can hold the train. */
		final double stopTargetM = 12.0 + 200.0 + 60.0;
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = newTrees();

		SectionNet(String savePath, boolean withSignal) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			final Position yardBack = new Position(-20, 0, 0);
			final Position yardMouth = new Position(-8, 0, 0);
			final Rail y = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			this.y = y;
			a = through(yardMouth, new Position(192, 0, 0));
			b = through(new Position(192, 0, 0), new Position(392, 0, 0));
			final Depot depot = new Depot(TransportMode.TRAIN, sim);
			siding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);
			depot.setName("Section Yard");
			depot.setCorners(new Position(-25, -3, -3), new Position(400, 3, 3));
			sim.rails.add(y);
			sim.rails.add(a);
			sim.rails.add(b);
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
			if (withSignal) {
				final Vector position = a.railMath.getPosition(signalArcM, false);
				sim.mmtrSignals.put((int) Math.floor(position.x()), (int) Math.floor(position.y()), (int) Math.floor(position.z()), 0, 2, "set", a.getHexId());
			}
		}

		Vehicle spawn() {
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, new BranchStore(), null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			return vehicle;
		}

		/** One 1000 ms tick: rotate the trees, inject the foreign train into A, then simulate. */
		void tick() {
			trees.removeFirst();
			trees.add(new Object2ObjectAVLTreeMap<>());
			Data.put(trees.get(1), a.getPosition1(), a.getPosition2(),
				vehiclePosition -> {
					final VehiclePosition newVehiclePosition = vehiclePosition == null ? new VehiclePosition() : vehiclePosition;
					newVehiclePosition.addSegment(foreignFromM, foreignToM, FOREIGN_VEHICLE_ID);
					return newVehiclePosition;
				}, Object2ObjectAVLTreeMap::new);
			siding.simulateVehicles(1000, trees);
		}

		void tickUntil(BooleanSupplier condition, int maxTicks) {
			for (int i = 0; i < maxTicks && !condition.getAsBoolean(); i++) {
				tick();
			}
			assertTrue(condition.getAsBoolean(), "condition not met within " + maxTicks + " ticks");
		}
	}

	private static ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> newTrees() {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = new ObjectArrayList<>();
		trees.add(new Object2ObjectAVLTreeMap<>());
		trees.add(new Object2ObjectAVLTreeMap<>());
		return trees;
	}

	@Test
	public void aSignalSplitsTheRailSoTheTrainStopsAtTheSignalNotAtTheTail() {
		final SectionNet n = new SectionNet("build/mmtr-section-signal", true);
		assertEquals(2, n.sim.mmtrBlocks.blocksOf(n.a.getHexId()).size(), "the bound signal cuts rail A in two");

		final Vehicle v = n.spawn();
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(n.stopTargetM, true);

		// The train runs into A's first section and must rest AT the signal (arc 100) because the
		// section beyond it is occupied by the foreign train.
		n.tickUntil(() -> v.getSpeed() == 0 && n.a.getHexId().equals(v.getMmtrMotionWalker().railHex()) && v.getMmtrMotionWalker().offsetM() > 95.0, 4000);
		assertEquals(n.signalArcM - 0.001, v.getMmtrMotionWalker().offsetM(), 0.05, "the block stop sits epsilon short of the signal");
		assertEquals(n.a.getHexId(), v.getMmtrMotionWalker().railHex(), "the train is on rail A, at the signal");
		assertTrue(v.getMmtrMotionWalker().offsetM() < n.foreignFromM - 1.0,
			"B2: the train stops at the signal, NOT 2 m behind the foreign tail (pre-B2 behaviour)");
		assertFalse(v.isMmtrMotionStoppedAtTarget(), "a block stop is not a task arrival");

		// The hold is stable while the section stays occupied, and the train never creeps into it.
		final double held = v.getMmtrMotionWalker().offsetM();
		for (int i = 0; i < 60; i++) {
			n.tick();
		}
		assertEquals(held, v.getMmtrMotionWalker().offsetM(), 1e-6, "the train stays at the signal while the section ahead is occupied");
		assertTrue(v.getMmtrMotionWalker().offsetM() < n.foreignFromM, "the train never entered the occupied section");
		assertEquals(n.a.getHexId(), v.getMmtrMotionWalker().railHex(), "and never boarded rail B");
	}

	@Test
	public void aRailWithoutSignalsIsStillClosedToAFollowingTrain() {
		final SectionNet n = new SectionNet("build/mmtr-section-no-signal", false);
		assertEquals(1, n.sim.mmtrBlocks.blocksOf(n.a.getHexId()).size(), "no signal -> rail A is one section");

		final Vehicle v = n.spawn();
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(n.stopTargetM, true);

		// One big section: ANY occupancy on A closes that section, so the follower waits at the Y/A
		// node and never enters the rail at all - exactly the pre-B2 whole-rail rule. This is the
		// contrast to the signal case above, where the train may enter the rail and run to the signal.
		n.tickUntil(() -> v.getSpeed() == 0 && n.y.getHexId().equals(v.getMmtrMotionWalker().railHex()) && v.getMmtrMotionWalker().offsetM() > 11.0, 4000);
		assertEquals(12.0 - 0.001, v.getMmtrMotionWalker().offsetM(), 0.05, "held epsilon short of the A entrance node");
		assertEquals(n.y.getHexId(), v.getMmtrMotionWalker().railHex(), "never boarded the occupied rail A");
		assertFalse(v.isMmtrMotionStoppedAtTarget(), "the block stop is not a task arrival");
	}
}
