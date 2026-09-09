package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.mmtr.signal.MmtrShuntAuthority;
import org.mtr.core.mmtr.signal.MmtrShuntAuthority.Kind;
import org.mtr.core.operation.MmtrDriveControl;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C3a: 调车授权 (subsidiary-aspect authority) against a real Vehicle in a synthetic world.
 *
 * <p>The scenario is the coupling approach: train 1 stands in the platform section, train 2 has to
 * pass the signal at danger and draw up to it. Without an authority the existing S1 occupancy rule
 * holds train 2 at the section entrance (one train per section). With a granted authority the same
 * occupancy must no longer block it, AWS must stay silent, no SPAD may be judged, and the movement
 * must run at the shunt speed limit.</p>
 */
public final class MmtrShuntAuthorityVehicleTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
		+ "}";
	/** 25 km/h expressed in the engine's internal speed unit (m/ms). */
	private static final double SHUNT_CAP_INTERNAL = 25.0 / 3600.0;

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> newTrees() {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = new ObjectArrayList<>();
		trees.add(new Object2ObjectAVLTreeMap<>());
		trees.add(new Object2ObjectAVLTreeMap<>());
		return trees;
	}

	/**
	 * Y2 (-30..-18, siding 2) -> X2 (-18..-12) -> Y1 (-12..0, siding 1) -> MA (0..16) -> PL (16..60).
	 * The platform section PL is the "occupied section" train 2 has to enter.
	 */
	private static final class Net {
		final Simulator sim;
		final Position y2Back = new Position(-30, 0, 0);
		final Position y2Mouth = new Position(-18, 0, 0);
		final Position y1Back = new Position(-12, 0, 0);
		final Position y1Mouth = new Position(0, 0, 0);
		final Rail y2;
		final Rail x2;
		final Rail y1;
		final Rail ma;
		final Rail pl;
		final Depot depot;
		final Siding siding1;
		final Siding siding2;
		/** Stop target 24 m into PL, measured from the siding-1 rear (-12) and siding-2 rear (-30). */
		final double platformStopM1 = 52.0;
		final double platformStopM2 = 70.0;
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = newTrees();
		/** The train parked in the platform section by {@link #occupiedPlatform()}. */
		Vehicle v1;

		Net(String savePath) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			y2 = Rail.newSidingRail(y2Back, Angle.fromAngle(0), y2Mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			x2 = through(y2Mouth, y1Back);
			y1 = Rail.newSidingRail(y1Back, Angle.fromAngle(0), y1Mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			ma = through(y1Mouth, new Position(16, 0, 0));
			pl = through(new Position(16, 0, 0), new Position(60, 0, 0));
			depot = new Depot(TransportMode.TRAIN, sim);
			siding1 = new Siding(y1Back, y1Mouth, 12, TransportMode.TRAIN, sim);
			siding2 = new Siding(y2Back, y2Mouth, 12, TransportMode.TRAIN, sim);
			depot.setName("Shunt Yard");
			depot.setCorners(new Position(-35, -3, -3), new Position(65, 3, 3));
			sim.rails.add(y2);
			sim.rails.add(x2);
			sim.rails.add(y1);
			sim.rails.add(ma);
			sim.rails.add(pl);
			sim.depots.add(depot);
			sim.sidings.add(siding1);
			sim.sidings.add(siding2);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			siding1.setVehicleCars(cars);
			siding2.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			assertTrue(depot.savedRails.contains(siding1), "siding 1 must attach to the depot yard");
			assertTrue(depot.savedRails.contains(siding2), "siding 2 must attach to the depot yard");
			siding1.tick();
			siding2.tick();
		}

		Vehicle spawn(Siding siding) {
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, new BranchStore(), null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			return vehicle;
		}

		void tick() {
			trees.removeFirst();
			trees.add(new Object2ObjectAVLTreeMap<>());
			siding1.simulateVehicles(1000, trees);
			siding2.simulateVehicles(1000, trees);
		}

		void tickUntil(BooleanSupplier condition, int maxTicks) {
			for (int i = 0; i < maxTicks && !condition.getAsBoolean(); i++) {
				tick();
			}
			assertTrue(condition.getAsBoolean(), "condition not met within " + maxTicks + " ticks");
		}

		MmtrShuntAuthority grant(Vehicle vehicle, String grantRailHex, String targetRailHex) {
			return sim.mmtrShuntAuthorities.grant(vehicle.getId(), grantRailHex, targetRailHex, Kind.SUBSIDIARY_SHUNT, 0, 10 * 60 * 1000L);
		}
	}

	private static void boardDriver(Simulator sim, Vehicle v, int throttle) {
		boardDriver(sim, v, throttle, false);
	}

	/**
	 * Puts a live driver on {@code v}. {@code acknowledge} presses the AWS acknowledgement once: the
	 * warning then latches as ACKED instead of escalating to a SPAD three seconds later, which is what
	 * lets these tests observe the warning state itself.
	 */
	private static void boardDriver(Simulator sim, Vehicle v, int throttle, boolean acknowledge) {
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(entities);
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(throttle).setReverser(1).setAcknowledge(acknowledge), driver).apply(sim);
		assertTrue(v.isMmtrManualOverride(), "drive command must hold the override");
	}

	/** Train 1 parks in the platform section; returns the net with that train in {@link Net#v1}. */
	private static Net occupiedPlatform() {
		final Net n = new Net("build/mmtr-shunt-authority");
		n.v1 = n.spawn(n.siding1);
		n.v1.setMmtrMotionAuto(true);
		n.v1.setMmtrMotionStopTarget(n.platformStopM1, true);
		n.tickUntil(n.v1::isMmtrMotionStoppedAtTarget, 4000);
		assertEquals(n.pl.getHexId(), n.v1.getMmtrMotionWalker().railHex(), "v1 stands in the platform section");
		return n;
	}

	@Test
	public void withoutAnAuthorityTheOccupiedSectionStillBlocks() {
		final Net n = occupiedPlatform();
		final Vehicle v2 = n.spawn(n.siding2);
		boardDriver(n.sim, v2, 3, true);
		n.tickUntil(() -> v2.getSpeed() == 0 && n.ma.getHexId().equals(v2.getMmtrMotionWalker().railHex()) && v2.getRailProgress() > 45.5, 4000);
		assertEquals(46.0 - 0.001, v2.getRailProgress(), 0.02, "v2 rests epsilon short of the occupied section");
		assertNull(v2.getMmtrShuntAuthority(), "no authority is live");
		assertFalse(n.sim.mmtrShuntAuthorities.allowsCoexistence(n.pl.getHexId()), "the section is one-train-per-section");
		assertTrue(v2.isMmtrBlockHeldFromSync(), "the occupancy block holds the train at the section entrance");
		assertTrue(v2.isMmtrAwsWarningAcknowledged(), "AWS warned about the restriction ahead and the driver acknowledged it");
		assertFalse(v2.isMmtrProtectionFromSync(), "an acknowledged warning is not a SPAD");
	}

	@Test
	public void anAuthorizedMovementEntersTheOccupiedSectionAtTheShuntLimit() {
		final Net n = occupiedPlatform();
		final Vehicle v2 = n.spawn(n.siding2);
		boardDriver(n.sim, v2, 3, true);
		n.tickUntil(() -> v2.getSpeed() == 0 && n.ma.getHexId().equals(v2.getMmtrMotionWalker().railHex()) && v2.getRailProgress() > 45.5, 4000);
		assertEquals(46.0 - 0.001, v2.getRailProgress(), 0.02, "v2 waits at the section entrance");
		assertTrue(v2.isMmtrBlockHeldFromSync(), "the occupancy block holds v2");
		assertTrue(v2.isMmtrAwsWarningAcknowledged(), "AWS warned before the authority was granted");

		// The signal authorises the movement: main head stays red, the subsidiary display is up.
		final MmtrShuntAuthority authority = n.grant(v2, n.ma.getHexId(), n.pl.getHexId());
		assertEquals(v2.getId(), authority.getVehicleId(), "the authority is bound to v2 alone (U8)");
		assertTrue(authority.covers(n.ma.getHexId()), "the grant rail is covered");
		assertTrue(authority.covers(n.pl.getHexId()), "the occupied target rail is covered");
		assertTrue(n.sim.mmtrShuntAuthorities.allowsCoexistence(n.pl.getHexId()));

		// AWS is suppressed and no SPAD is judged: the section occupancy no longer blocks the movement.
		n.tick();
		assertFalse(v2.isMmtrAwsWarningPending(), "AWS is suppressed under the subsidiary aspect");
		assertFalse(v2.isMmtrAwsWarningAcknowledged(), "the latched warning is cleared too");
		assertFalse(v2.isMmtrProtectionFromSync(), "the protection layer does not judge a SPAD under the authority");
		n.tickUntil(() -> n.pl.getHexId().equals(v2.getMmtrMotionWalker().railHex()), 4000);
		assertEquals(n.pl.getHexId(), v2.getMmtrMotionWalker().railHex(), "v2 entered the occupied section");
		assertTrue(v2.getRailProgress() > 46.0, "v2 is past the old block stop");

		// The movement runs at the shunt limit (25 km/h), never above it.
		double maxSpeed = 0;
		for (int i = 0; i < 120; i++) {
			n.tick();
			maxSpeed = Math.max(maxSpeed, v2.getSpeed());
		}
		assertTrue(maxSpeed > 0, "the authorised movement actually moves");
		assertTrue(maxSpeed <= SHUNT_CAP_INTERNAL + 1e-9, "the shunt speed limit is enforced, maxSpeed=" + maxSpeed);
		assertFalse(v2.isMmtrProtectionFromSync(), "still no SPAD judgement under the authority");
		assertFalse(v2.isMmtrAwsWarningPending(), "still no AWS warning under the authority");
		assertEquals(0, n.v1.getSpeed(), 1e-9, "v1 keeps standing in the section");
	}

	@Test
	public void withdrawingTheAuthorityRestoresTheBlock() {
		final Net n = occupiedPlatform();
		final Vehicle v2 = n.spawn(n.siding2);
		boardDriver(n.sim, v2, 3, true);
		n.tickUntil(() -> v2.getSpeed() == 0 && n.ma.getHexId().equals(v2.getMmtrMotionWalker().railHex()) && v2.getRailProgress() > 45.5, 4000);
		n.grant(v2, n.ma.getHexId(), n.pl.getHexId());
		n.tickUntil(() -> n.pl.getHexId().equals(v2.getMmtrMotionWalker().railHex()), 4000);
		assertEquals(n.pl.getHexId(), v2.getMmtrMotionWalker().railHex(), "v2 is inside the occupied section");

		assertTrue(n.sim.mmtrShuntAuthorities.revoke(v2.getId()));
		assertNull(v2.getMmtrShuntAuthority());
		assertFalse(n.sim.mmtrShuntAuthorities.allowsCoexistence(n.pl.getHexId()), "the section is one-train-per-section again");
		// The movement was already inside the section: with the authority gone the section is a normal
		// occupied block again, so the train must come to rest behind the train it was drawing up to -
		// by the occupancy rule or, if the driver ignores the (now live) AWS warning, by the protection.
		n.tickUntil(() -> v2.getSpeed() == 0, 400);
		assertEquals(0, v2.getSpeed(), 1e-9, "v2 stops once the authority is withdrawn");
		assertTrue(v2.getRailProgress() < n.v1.getRailProgress(), "v2 never runs through the train it was drawing up to");
		assertTrue(v2.isMmtrBlockHeldFromSync() || v2.isMmtrProtectionFromSync(), "the occupancy rule or the protection holds it again");
	}
}
