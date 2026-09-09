package org.mtr.core.mmtr.signal;

import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.signal.MmtrShuntAuthority.Kind;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C3a: the 调车授权 data plane. A subsidiary aspect authorises exactly one train to pass a signal at
 * danger into an occupied section; the registry is what the vehicle, the yard and the ops UI read.
 * These tests pin the ownership rule (U8), the lazily-expiring window, the rail coverage and the
 * aspect table (AWS suppressed, protection suspended, shunt speed limit).
 */
public final class MmtrShuntAuthorityTests {

	private static final String RAIL_A = "rail-a";
	private static final String RAIL_B = "rail-b";
	private static final String RAIL_C = "rail-c";

	private static MmtrShuntAuthorityRegistry registry(AtomicLong clock) {
		return new MmtrShuntAuthorityRegistry(clock::get);
	}

	@Test
	public void anAuthorityCoversItsOwnMovementRailsOnly() {
		final AtomicLong clock = new AtomicLong(1_000);
		final MmtrShuntAuthorityRegistry registry = registry(clock);
		final MmtrShuntAuthority authority = registry.grant(7L, RAIL_A, RAIL_B, Kind.SUBSIDIARY_SHUNT, 0, 60_000);

		assertEquals(7L, authority.getVehicleId());
		assertEquals(Kind.SUBSIDIARY_SHUNT, authority.getKind());
		assertTrue(authority.covers(RAIL_A), "the rail the movement starts on is covered");
		assertTrue(authority.covers(RAIL_B), "the occupied target rail is covered");
		assertFalse(authority.covers(RAIL_C), "a rail outside the movement is not");
		assertFalse(authority.covers(null));
		assertEquals(Kind.SUBSIDIARY_SHUNT.getDefaultSpeedLimitKmh(), authority.getSpeedLimitKmh(), 1e-9, "the aspect's default limit is used when none is given");
		assertTrue(registry.allowsCoexistence(RAIL_B), "the target section may hold two trains while authorised");
		assertFalse(registry.allowsCoexistence(RAIL_C), "any other section stays one-train-per-section");
	}

	/**
	 * C10: a task-driven shunt is authorised along its WHOLE planned route, because the movement may cross
	 * rails other stock occupies on the way to the target (实机 2026-09-09: a relocation past a parked rake
	 * stopped at the intermediate occupancy face even though its target rail was authorised).
	 */
	@Test
	public void aRouteWideAuthorityCoversEveryRailOfTheMovement() {
		final AtomicLong clock = new AtomicLong(5_000);
		final MmtrShuntAuthorityRegistry registry = registry(clock);
		final MmtrShuntAuthority authority = registry.grantRoute(9L, RAIL_A, RAIL_C,
			java.util.List.of(RAIL_A, RAIL_B, RAIL_C), Kind.SUBSIDIARY_SHUNT, 25, 60_000);

		assertTrue(authority.covers(RAIL_A), "the rail the movement starts on");
		assertTrue(authority.covers(RAIL_B), "an intermediate rail of the route (may be occupied)");
		assertTrue(authority.covers(RAIL_C), "the target rail");
		assertFalse(authority.covers("rail-d"), "a rail outside the route stays blocked");
		assertEquals(3, authority.getRouteRailHexes().size(), "the whole route is recorded for the map/console");
		assertTrue(registry.allowsCoexistence(RAIL_B), "permissive working applies on the route's middle rails too");
	}

	@Test
	public void oneTrainOneAuthority() {		final AtomicLong clock = new AtomicLong(0);
		final MmtrShuntAuthorityRegistry registry = registry(clock);
		registry.grant(1L, RAIL_A, RAIL_B, Kind.SUBSIDIARY_SHUNT, 0, 60_000);
		registry.grant(2L, RAIL_A, RAIL_C, Kind.CALLING_ON, 0, 60_000);

		assertNotNull(registry.active(1L), "each train keeps its own authority");
		assertNotNull(registry.active(2L));
		assertEquals(RAIL_C, registry.active(2L).getTargetRailHex());

		// Re-granting for the same train replaces (never stacks) its authority.
		registry.grant(1L, RAIL_A, RAIL_C, Kind.CALLING_ON, 10, 60_000);
		assertEquals(1, registry.snapshot().stream().filter(authority -> authority.getVehicleId() == 1L).count());
		assertEquals(Kind.CALLING_ON, registry.active(1L).getKind());
		assertEquals(10, registry.active(1L).getSpeedLimitKmh(), 1e-9, "an explicit limit overrides the aspect default");

		assertTrue(registry.revoke(1L));
		assertNull(registry.active(1L));
		assertFalse(registry.revoke(1L), "nothing left to revoke");
		assertNotNull(registry.active(2L), "revoking one train does not touch another");
	}

	@Test
	public void anExpiredAuthorityStopsAuthorising() {
		final AtomicLong clock = new AtomicLong(0);
		final MmtrShuntAuthorityRegistry registry = registry(clock);
		registry.grant(5L, RAIL_A, RAIL_B, Kind.SUBSIDIARY_SHUNT, 0, 1_000);
		assertTrue(registry.allowsCoexistence(RAIL_B));
		assertEquals(1_000, registry.active(5L).remainingMillis(clock.get()), 1e-9);

		clock.set(1_000);
		assertNull(registry.active(5L), "the window closed - the section is one-train-per-section again");
		assertFalse(registry.allowsCoexistence(RAIL_B));
		assertEquals(0, registry.size());
	}

	@Test
	public void theAspectTableSuspendsAwsAndProtection() {
		// The whole point of the slice: under a subsidiary aspect AWS must stay silent and the
		// TPWS-equivalent protection must not judge a SPAD - the signal just authorised the move.
		for (final Kind kind : Kind.values()) {
			assertTrue(kind.suppressesAws(), kind + " suppresses AWS");
			assertFalse(kind.enforcesProtection(), kind + " does not judge a SPAD");
			assertTrue(kind.getDefaultSpeedLimitKmh() > 0, kind + " carries a shunt speed limit");
			assertTrue(kind.getDefaultSpeedLimitKmh() <= 25 + 1e-9, kind + " is a low-speed movement (UNVERIFIED, common practice 15 mph)");
		}
	}

	@Test
	public void grantingWithoutAValidityUsesTheDefaultWindow() {
		final AtomicLong clock = new AtomicLong(0);
		final MmtrShuntAuthorityRegistry registry = registry(clock);
		final MmtrShuntAuthority authority = registry.grant(3L, RAIL_A, RAIL_B, null, 0, 0);
		assertEquals(Kind.SUBSIDIARY_SHUNT, authority.getKind(), "null kind falls back to the shunt aspect");
		assertEquals(MmtrShuntAuthorityRegistry.DEFAULT_VALIDITY_MILLIS, authority.remainingMillis(0), 1e-9);
		assertTrue(authority.isActiveAt(MmtrShuntAuthorityRegistry.DEFAULT_VALIDITY_MILLIS - 1));
		assertFalse(authority.isActiveAt(MmtrShuntAuthorityRegistry.DEFAULT_VALIDITY_MILLIS));
	}
}
