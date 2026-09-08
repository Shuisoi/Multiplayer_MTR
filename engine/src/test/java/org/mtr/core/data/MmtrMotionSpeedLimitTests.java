package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.MmtrRegime;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.operation.MmtrDriveControl;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signal S2: per-segment rail speed limits and the AWS/LZB regime split on live Motion-Core runs.
 * Every rail carries two directional speed limits in the MTR data; the regime derives from the
 * travel-direction limit (<= 100 km/h = AWS, >= 101 km/h = LZB - user-confirmed threshold). Auto
 * runs cruise under min(rail limit, consist ceiling); a SLOWER rail ahead is braced for with the
 * service-brake envelope so the train crosses the node at (about) the slower rail's limit (never
 * over-running it), then holds the new limit. Manual drivers are NOT forced to the rail limit on
 * AWS rails (UK semantics: the warning layer, S3, comes later) - the regime is still reported.
 */
public final class MmtrMotionSpeedLimitTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON_AUTO = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":80,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
		+ "}";
	private static final String CONSIST_JSON_MANUAL = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
		+ "}";

	private static double kmh(double speedKilometersPerHour) {
		return speedKilometersPerHour / 3600.0; // engine internal m/ms
	}

	private static Rail through(Position p1, Position p2, long speedLimitKmh1, long speedLimitKmh2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			speedLimitKmh1, speedLimitKmh2, false, false, true, false, true, TransportMode.TRAIN);
	}

	/**
	 * Straight line with one yard and two mainline rails:
	 * Y (yard rail, rear -20 .. mouth -8, 12 m) -> A (-8 .. aEnd) -> B (aEnd .. aEnd + bLen).
	 * Every node is a two-rail single continuation (no forks).
	 */
	private static final class LineNet {
		final Simulator sim;
		final Position rear = new Position(-20, 0, 0);
		final Position mouth = new Position(-8, 0, 0);
		final Rail yardRail;
		final Rail aRail;
		final Rail bRail;
		final Depot depot;
		final Siding siding;
		final long aLengthM;
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = new ObjectArrayList<>();

		LineNet(String savePath, String consistJson, long aLimitKmh1, long aLimitKmh2, long aLengthM, long bLimitKmh1, long bLimitKmh2, long bLengthM) {
			this.aLengthM = aLengthM;
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			yardRail = Rail.newSidingRail(rear, Angle.fromAngle(0), mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			final Position aEnd = new Position(-8 + aLengthM, 0, 0);
			aRail = through(mouth, aEnd, aLimitKmh1, aLimitKmh2);
			bRail = through(aEnd, new Position(aEnd.getX() + bLengthM, 0, 0), bLimitKmh1, bLimitKmh2);
			depot = new Depot(TransportMode.TRAIN, sim);
			siding = new Siding(rear, mouth, 12, TransportMode.TRAIN, sim);
			depot.setName("Yard");
			depot.setCorners(new Position(-25, -3, -3), new Position(aEnd.getX() + bLengthM + 5, 3, 3));
			sim.rails.add(yardRail);
			sim.rails.add(aRail);
			sim.rails.add(bRail);
			sim.depots.add(depot);
			sim.sidings.add(siding);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			siding.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(consistJson);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			assertTrue(depot.savedRails.contains(siding), "siding must attach to the depot yard");
			siding.tick();
			trees.add(new Object2ObjectAVLTreeMap<>());
			trees.add(new Object2ObjectAVLTreeMap<>());
		}

		Vehicle spawn() {
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, new BranchStore(), null);
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

		void tickUntil(BooleanSupplier condition, int maxTicks) {
			for (int i = 0; i < maxTicks && !condition.getAsBoolean(); i++) {
				tick();
			}
			assertTrue(condition.getAsBoolean(), "condition not met within " + maxTicks + " ticks");
		}
	}

	@Test
	public void regimeThresholdBoundaryIsAwsBelow101AndLzbFrom101() {
		assertEquals(MmtrRegime.AWS, MmtrRegime.fromSpeedLimitKmh(0), "unreachable/0 is AWS");
		assertEquals(MmtrRegime.AWS, MmtrRegime.fromSpeedLimitKmh(40));
		assertEquals(MmtrRegime.AWS, MmtrRegime.fromSpeedLimitKmh(100), "100 km/h stays AWS");
		assertEquals(MmtrRegime.LZB, MmtrRegime.fromSpeedLimitKmh(101), "101 km/h and above is LZB");
		assertEquals(MmtrRegime.LZB, MmtrRegime.fromSpeedLimitKmh(160));
		assertEquals(MmtrRegime.LZB, MmtrRegime.fromSpeedLimitKmh(300));
	}

	@Test
	public void autoCruiseHoldsTheSlowRailLimitAndStillStopsExactly() {
		// Y (12 m) -> A (60 m, 200) -> B (900 m, limit 40/40). Auto stop 350 m into B, then a
		// farther stop 800 m into B. The consist ceiling is 80 km/h, so the cruise cap on B is the
		// rail limit (40): the auto run must never exceed ~40 km/h on B and still stop exactly at
		// each armed target with doors.
		final LineNet n = new LineNet("build/mmtr-limit-cruise", CONSIST_JSON_AUTO, 200, 200, 60, 40, 40, 900);
		final Vehicle v = n.spawn();
		v.setMmtrMotionAuto(true);
		final double firstStopM = 12 + 60 + 350.0;
		v.setMmtrMotionStopTarget(firstStopM, true);
		n.tickUntil(v::isMmtrMotionStoppedAtTarget, 4000);
		assertEquals(firstStopM, v.getRailProgress(), 0.05, "auto stop exact on the slow rail");
		assertEquals(n.bRail.getHexId(), v.getMmtrMotionWalker().railHex(), "stopped on the slow rail");
		assertTrue(v.vehicleExtraData.getDoorMultiplier() > 0, "doors open at the stop");

		// Depart to a second, farther stop and sample the whole run: the cruise must hold the rail
		// cap (40 km/h) on B and never exceed it (the consist could run 80 elsewhere).
		double maxSpeed = 0;
		boolean sampled = false;
		final double secondStopM = 12 + 60 + 800.0;
		v.setMmtrMotionStopTarget(secondStopM, false);
		for (int i = 0; i < 4000 && !v.isMmtrMotionStoppedAtTarget(); i++) {
			n.tick();
			if (n.bRail.getHexId().equals(v.getMmtrMotionWalker().railHex())) {
				sampled = true;
				maxSpeed = Math.max(maxSpeed, v.getSpeed());
			}
		}
		assertTrue(v.isMmtrMotionStoppedAtTarget(), "second auto stop reached");
		assertEquals(secondStopM, v.getRailProgress(), 0.05, "second auto stop exact");
		assertTrue(sampled, "train ran on the 40 rail");
		assertTrue(maxSpeed > kmh(35), "auto run actually cruised, max=" + maxSpeed * 3600.0 + " km/h");
		assertTrue(maxSpeed <= kmh(40) + 1e-6, "auto cruise never exceeds the 40 km/h rail limit, max=" + maxSpeed * 3600.0 + " km/h");
	}

	@Test
	public void autoBracesBeforeBoardingASlowerRailAndRegimeFlipsAtTheNode() {
		// Y (12 m) -> A (700 m, limit 200/200 -> LZB band) -> B (400 m, limit 40/40 -> AWS band).
		// Consist ceiling 80: on A the train cruises 80 km/h, then must brace (service envelope) to
		// cross into B at ~40 km/h - never over-run the slower rail - and hold 40 there. The regime
		// flips LZB -> AWS exactly at the node.
		final LineNet n = new LineNet("build/mmtr-limit-transition", CONSIST_JSON_AUTO, 200, 200, 700, 40, 40, 400);
		final Vehicle v = n.spawn();
		v.setMmtrMotionAuto(true);
		final double targetM = 12 + 700 + 150.0;
		v.setMmtrMotionStopTarget(targetM, true);

		double maxOnA = 0;
		double speedAtCrossing = Double.MAX_VALUE;
		double maxOnB = 0;
		boolean observedLzb = false;
		boolean observedAws = false;
		boolean crossed = false;
		for (int i = 0; i < 4000 && !v.isMmtrMotionStoppedAtTarget(); i++) {
			n.tick();
			final double speed = v.getSpeed();
			if (n.aRail.getHexId().equals(v.getMmtrMotionWalker().railHex())) {
				maxOnA = Math.max(maxOnA, speed);
				if (v.getMmtrRegime() == MmtrRegime.LZB) {
					observedLzb = true;
				}
				assertEquals(200, v.getMmtrCurrentSpeedLimitKmh(), "current rail limit reported on A");
			} else if (n.bRail.getHexId().equals(v.getMmtrMotionWalker().railHex())) {
				if (!crossed) {
					crossed = true;
					speedAtCrossing = speed;
				}
				maxOnB = Math.max(maxOnB, speed);
				if (v.getMmtrRegime() == MmtrRegime.AWS) {
					observedAws = true;
				}
				assertEquals(40, v.getMmtrCurrentSpeedLimitKmh(), "current rail limit reported on B");
			}
		}
		assertTrue(v.isMmtrMotionStoppedAtTarget(), "auto run finished at the target");
		assertTrue(observedLzb && observedAws, "regime flips LZB (A) -> AWS (B) along the run");
		assertTrue(maxOnA >= kmh(75), "train really cruised fast on the 200 km/h rail, max=" + maxOnA * 3600.0 + " km/h");
		assertTrue(speedAtCrossing <= kmh(40) + kmh(1.5), "crossed into the 40 rail near its limit, got " + speedAtCrossing * 3600.0 + " km/h");
		assertTrue(speedAtCrossing >= kmh(35), "did not crawl into the slow rail, got " + speedAtCrossing * 3600.0 + " km/h");
		assertTrue(maxOnB <= kmh(40) + kmh(1.5), "never over-ran the slow rail after crossing, max=" + maxOnB * 3600.0 + " km/h");
		assertEquals(targetM, v.getRailProgress(), 0.05, "exact stop on B after the transition");
	}

	@Test
	public void manualDriverIsNotForcedToTheRailLimitOnAwsRails() {
		// UK AWS semantics (user-confirmed): on <= 100 km/h rails the rail limit is advisory for a
		// human driver - the engine does NOT force it (the warning layer is S3). A driver may run
		// past the 40 km/h limit on the AWS rail; the regime/limit are still reported.
		final LineNet n = new LineNet("build/mmtr-limit-manual", CONSIST_JSON_MANUAL, 200, 200, 40, 40, 40, 1000);
		final Vehicle v = n.spawn();
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(entities);
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(3).setReverser(1), driver).apply(n.sim);
		assertTrue(v.isMmtrManualOverride(), "drive command must hold the override");

		double maxOnB = 0;
		boolean reportedAws = false;
		boolean forcedToStop = false;
		for (int i = 0; i < 3000; i++) {
			n.tick();
			if (v.getMmtrMotionWalker() != null && n.bRail.getHexId().equals(v.getMmtrMotionWalker().railHex())) {
				maxOnB = Math.max(maxOnB, v.getSpeed());
				if (v.getMmtrRegime() == MmtrRegime.AWS && v.getMmtrCurrentSpeedLimitKmh() == 40) {
					reportedAws = true;
				}
			}
			if (v.getSpeed() == 0 && v.getMmtrMotionWalker() != null && v.getMmtrMotionWalker().endOfLine()) {
				forcedToStop = true; // reached the end of the 1000 m test rail
				break;
			}
		}
		assertTrue(reportedAws, "manual run on the 40 rail reports AWS regime + limit 40");
		assertTrue(maxOnB > kmh(45), "manual driver was NOT forced to the 40 km/h rail limit, max=" + maxOnB * 3600.0 + " km/h");
		assertTrue(forcedToStop, "manual train ran the whole test rail to its end");
	}
}
