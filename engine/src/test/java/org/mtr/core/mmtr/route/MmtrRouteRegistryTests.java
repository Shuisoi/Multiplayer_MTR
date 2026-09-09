package org.mtr.core.mmtr.route;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.point.MmtrPointAuthority;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S5 (联锁收编 / route locking) primitives: the route registry derives a movement's SET/PENDING
 * state from the authoritative turnout authority, never from its own copy of the grant state.
 *
 * <p>Acceptance pinned here:</p>
 * <ul>
 *   <li>a route with no turnouts is SET as soon as it is requested (straight track needs no
 *       interlocking);</li>
 *   <li>a route is SET only while EVERY turnout it needs is held by its owner;</li>
 *   <li>an operator park or another train's hold drops a SET route back to PENDING and names the
 *       blocking point - this is what turns the protecting signal back to danger (A2);</li>
 *   <li>only SET routes are visible to the signal layer ({@code routesOverRail}), in vehicle-id
 *       order so the feed is deterministic;</li>
 *   <li>a re-plan replaces the route, and a release removes it entirely.</li>
 * </ul>
 */
public final class MmtrRouteRegistryTests {

	private static final String VIA_A = "FFFFFFFFFFFFFFEC-0000000000000000";
	private static final String VIA_B = "FFFFFFFFFFFFFFEC-0000000000000001";
	private static final String RAIL_ENTRY = "0000000000000001-0000000000000002";
	private static final String RAIL_MID = "0000000000000002-0000000000000003";
	private static final String RAIL_TARGET = "0000000000000003-0000000000000004";

	private static ObjectArrayList<String[]> forks() {
		final ObjectArrayList<String[]> forks = new ObjectArrayList<>();
		forks.add(new String[]{"0", "0", "0", VIA_A, "0"});
		forks.add(new String[]{"60", "0", "0", VIA_B, "1"});
		return forks;
	}

	private static ObjectArrayList<String> rails() {
		final ObjectArrayList<String> rails = new ObjectArrayList<>();
		rails.add(RAIL_ENTRY);
		rails.add(RAIL_MID);
		rails.add(RAIL_TARGET);
		return rails;
	}

	private static MmtrRoute route(long vehicleId) {
		return new MmtrRoute(vehicleId, "v" + vehicleId, MmtrRoute.Kind.MAIN, rails(), forks(), RAIL_TARGET, 1000);
	}

	private static void grantAll(MmtrPointAuthority authority, long vehicleId, long until) {
		assertEquals(MmtrPointAuthority.Result.GRANTED, authority.request(0, 0, 0, VIA_A, "v" + vehicleId, 0, until));
		assertEquals(MmtrPointAuthority.Result.GRANTED, authority.request(60, 0, 0, VIA_B, "v" + vehicleId, 1, until));
	}

	@Test
	public void routeWithoutTurnoutsIsSetAsSoonAsItIsRequested() {
		final AtomicLong clock = new AtomicLong(1000);
		final MmtrRouteRegistry registry = new MmtrRouteRegistry();
		final MmtrPointAuthority authority = new MmtrPointAuthority(clock::get);
		final ObjectArrayList<String> straightRails = new ObjectArrayList<>();
		straightRails.add(RAIL_ENTRY);
		straightRails.add(RAIL_MID);
		final MmtrRoute straight = new MmtrRoute(7, "v7", MmtrRoute.Kind.MAIN, straightRails, new ObjectArrayList<>(), RAIL_MID, 1000);
		registry.request(straight);
		assertFalse(straight.isEstablished(), "a fresh route starts PENDING until it is refreshed");
		registry.refresh(7, authority);
		assertTrue(straight.isEstablished(), "straight track needs no interlocking");
		assertEquals("no turnout in this route", straight.getStateReason());
		assertEquals(straight, registry.routeOverRail(RAIL_MID), "the signal layer can read it");
	}

	@Test
	public void routeStaysPendingUntilEveryTurnoutIsHeldByItsOwner() {
		final AtomicLong clock = new AtomicLong(1000);
		final MmtrRouteRegistry registry = new MmtrRouteRegistry();
		final MmtrPointAuthority authority = new MmtrPointAuthority(clock::get);
		final MmtrRoute route = registry.request(route(1));

		assertEquals(MmtrPointAuthority.Result.GRANTED, authority.request(0, 0, 0, VIA_A, "v1", 0, 5000), "only the first turnout is taken");
		registry.refresh(1, authority);
		assertFalse(route.isEstablished(), "one turnout outstanding keeps the route PENDING");
		assertTrue(route.getStateReason().contains("wantLeg=1"), "the reason names the outstanding turnout: " + route.getStateReason());
		assertNull(registry.routeOverRail(RAIL_ENTRY), "a PENDING route authorises nothing");

		assertEquals(MmtrPointAuthority.Result.GRANTED, authority.request(60, 0, 0, VIA_B, "v1", 1, 5000));
		registry.refresh(1, authority);
		assertTrue(route.isEstablished(), "every turnout held -> the route is SET");
		assertEquals(route, registry.routeOverRail(RAIL_ENTRY));
		assertEquals(route, registry.routeOverRail(RAIL_TARGET));
	}

	@Test
	public void operatorLockDropsASetRouteBackToPendingAndNamesThePoint() {
		final AtomicLong clock = new AtomicLong(1000);
		final MmtrRouteRegistry registry = new MmtrRouteRegistry();
		final MmtrPointAuthority authority = new MmtrPointAuthority(clock::get);
		final MmtrRoute route = registry.request(route(1));

		// The operator parks the second turnout before the train can take it (the real ordering:
		// 人工锁定 -> 自动申请排队). The first turnout is taken normally.
		authority.lock(60, 0, 0, VIA_B);
		assertEquals(MmtrPointAuthority.Result.GRANTED, authority.request(0, 0, 0, VIA_A, "v1", 0, 5000));
		assertEquals(MmtrPointAuthority.Result.QUEUED, authority.request(60, 0, 0, VIA_B, "v1", 1, 5000));
		registry.refresh(1, authority);
		assertFalse(route.isEstablished(), "a parked turnout keeps the route at danger");
		assertTrue(route.getStateReason().contains("lock=true"), "the reason names the park: " + route.getStateReason());
		assertTrue(route.getStateReason().contains("60,0,0"), "the reason names the point: " + route.getStateReason());

		authority.unlock(60, 0, 0, VIA_B);
		registry.refresh(1, authority);
		assertTrue(route.isEstablished(), "unlock promotes the queued request and the route sets");
	}

	@Test
	public void anotherTrainTakingAPointDropsTheRouteAndNamesTheHolder() {
		final AtomicLong clock = new AtomicLong(1000);
		final MmtrRouteRegistry registry = new MmtrRouteRegistry();
		final MmtrPointAuthority authority = new MmtrPointAuthority(clock::get);
		final MmtrRoute route = registry.request(route(1));
		grantAll(authority, 1, 5000);
		registry.refresh(1, authority);
		assertTrue(route.isEstablished());

		// v2 steals the second point (e.g. v1's grant expired and the queued train was promoted).
		authority.passed(60, 0, 0, VIA_B, "v1");
		assertEquals(MmtrPointAuthority.Result.GRANTED, authority.request(60, 0, 0, VIA_B, "v2", 1, 9000));
		registry.refresh(1, authority);
		assertFalse(route.isEstablished(), "losing a turnout to another train drops the route");
		assertTrue(route.getStateReason().contains("holder=v2@1"), "the reason names the holder: " + route.getStateReason());
		assertTrue(registry.routesOverRail(RAIL_MID).isEmpty(), "no SET route over the rail any more");
	}

	@Test
	public void routesOverRailListsOnlySetRoutesInVehicleIdOrder() {
		final AtomicLong clock = new AtomicLong(1000);
		final MmtrRouteRegistry registry = new MmtrRouteRegistry();
		final MmtrPointAuthority authority = new MmtrPointAuthority(clock::get);
		final MmtrRoute v9 = registry.request(route(9));
		// A second movement over the same rails with no turnout of its own (straight track): it does
		// not compete for the points, so both routes can be SET at once.
		final MmtrRoute v2 = registry.request(new MmtrRoute(2, "v2", MmtrRoute.Kind.MAIN, rails(), new ObjectArrayList<>(), RAIL_TARGET, 1000));
		grantAll(authority, 9, 5000);
		registry.refresh(9, authority);
		registry.refresh(2, authority);

		final ObjectArrayList<MmtrRoute> overRail = registry.routesOverRail(RAIL_MID);
		assertEquals(2, overRail.size(), "both SET routes run over the rail");
		assertEquals(2, overRail.get(0).getVehicleId(), "deterministic order: lowest vehicle id first");
		assertEquals(9, overRail.get(1).getVehicleId());
		assertEquals(v2, registry.routeOverRail(RAIL_MID), "routeOverRail returns the first in that order");
		assertTrue(registry.routesOverRail("0000000000000009-000000000000000A").isEmpty(), "a rail no route covers");
		assertTrue(registry.routesOverRail(null).isEmpty(), "null / empty lookups are safe");
		assertEquals(v9, registry.route(9));
		assertNull(registry.route(404));
	}

	@Test
	public void rePlanReplacesTheRouteAndReleaseRemovesIt() {
		final AtomicLong clock = new AtomicLong(1000);
		final MmtrRouteRegistry registry = new MmtrRouteRegistry();
		final MmtrPointAuthority authority = new MmtrPointAuthority(clock::get);
		registry.request(route(1));
		final ObjectArrayList<String> replannedRails = new ObjectArrayList<>();
		replannedRails.add(RAIL_ENTRY);
		replannedRails.add(RAIL_TARGET);
		final MmtrRoute replanned = registry.request(new MmtrRoute(1, "v1", MmtrRoute.Kind.SHUNT,
			replannedRails, new ObjectArrayList<>(), RAIL_TARGET, 2000));

		assertEquals(1, registry.size(), "one route per train - a re-plan replaces it");
		assertEquals(MmtrRoute.Kind.SHUNT, registry.route(1).getKind(), "the replacement carries the new kind");
		assertEquals(2000, registry.route(1).getRequestedMillis());
		registry.refresh(1, authority);
		assertTrue(replanned.isEstablished());
		assertEquals(replanned, registry.routeOverRail(RAIL_TARGET));

		assertTrue(registry.release(1), "release drops the route");
		assertEquals(0, registry.size());
		assertNull(registry.route(1));
		assertFalse(registry.release(1), "a second release is a no-op");
		assertTrue(registry.routesOverRail(RAIL_TARGET).isEmpty(), "released routes cover nothing");
	}

	@Test
	public void repeatingTheSameMovementKeepsTheLiveRouteObject() {
		final MmtrRouteRegistry registry = new MmtrRouteRegistry();
		final MmtrRoute first = registry.request(route(1));
		assertSame(first, registry.request(route(1)), "a self-arm retry of the same movement keeps one route identity");
		assertNotSame(first, registry.request(new MmtrRoute(1, "v1", MmtrRoute.Kind.SHUNT, rails(), forks(), RAIL_TARGET, 1000)),
			"a different kind is a different movement and replaces the route");
	}

	@Test
	public void routeExposesItsRailsForksAndTarget() {
		final MmtrRoute route = route(3);
		assertEquals(RAIL_ENTRY, route.getEntryRailHex());
		assertEquals(RAIL_TARGET, route.getTargetRailHex());
		assertEquals(3, route.getRailHexes().size());
		assertEquals(2, route.getForks().size());
		assertTrue(route.coversRail(RAIL_MID));
		assertFalse(route.coversRail("nope"));
		assertFalse(route.coversRail(null));
		assertTrue(route.toString().contains("PENDING"), "the description shows the state: " + route);
	}
}
