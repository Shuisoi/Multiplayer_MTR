package org.mtr.core.data;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.point.MmtrPointAuthority;
import org.mtr.core.mmtr.route.MmtrRoute;
import org.mtr.core.mmtr.signal.MmtrSignalAspect;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2 acceptance (design §2「信号 = 进路 × 闭塞」): the display aspect of a rail is the occupancy
 * chain, narrowed by a SET main route and held at danger while a route over it is still PENDING.
 * The rule lives in {@link MmtrSignalAspect} and is shared by the ops feed and (once mirrored) the
 * in-game renderer, replacing the two duplicated "at a fork every branch counts" walks.
 *
 * <p>Network: entry E (-20..0) -> fork N (0) -> {straight S (0..60) -> S2 (60..120) | diverge D
 * (0..60,+20)}. A signal protecting E sees depth 1 = E, 2 = S/D, 3 = S2.</p>
 */
public final class MmtrSignalAspectTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static final class Net {
		final Simulator sim;
		final Rail entry;
		final Rail straight;
		final Rail diverge;
		final Rail beyond;
		final Position a = new Position(-20, 0, 0);
		final Position fork = new Position(0, 0, 0);
		final Position b = new Position(60, 0, 0);
		final Position c = new Position(60, 0, 20);

		Net(String savePath) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			entry = through(a, fork);
			straight = through(fork, b);
			diverge = through(fork, c);
			beyond = through(b, new Position(120, 0, 0));
			sim.rails.add(entry);
			sim.rails.add(straight);
			sim.rails.add(diverge);
			sim.rails.add(beyond);
			sim.sync();
			sim.mmtrEnsureSignalColors();
		}

		/** Mark a rail occupied the way a standing train does (manual block -> CURRENTLY_RESERVE). */
		void occupy(Rail rail) {
			rail.blockRail(new LongArrayList());
			rail.tick1(sim); // roll the tick snapshots
			rail.tick2(0);   // reserve under the manual block -> currently-blocked set
		}

		MmtrSignalAspect aspect() {
			return new MmtrSignalAspect(sim, sim.mmtrRoutes);
		}

		/** Publish and set a route over the given rails; {@code withFork} grants the fork leg 0 (straight). */
		MmtrRoute setRoute(long vehicleId, MmtrRoute.Kind kind, boolean withFork, Rail... rails) {
			final ObjectArrayList<String> hexes = new ObjectArrayList<>();
			for (final Rail rail : rails) {
				hexes.add(rail.getHexId());
			}
			final ObjectArrayList<String[]> forks = new ObjectArrayList<>();
			if (withFork) {
				forks.add(new String[]{String.valueOf(fork.getX()), String.valueOf(fork.getY()), String.valueOf(fork.getZ()), entry.getHexId(), "0"});
			}
			final MmtrRoute route = sim.mmtrRoutes.request(new MmtrRoute(vehicleId, "v" + vehicleId, kind, hexes, forks, rails[rails.length - 1].getHexId(), 1000));
			if (withFork) {
				sim.mmtrPointAuthority.request(fork.getX(), fork.getY(), fork.getZ(), entry.getHexId(), "v" + vehicleId, 0, sim.getCurrentMillis() + 60_000);
			}
			sim.mmtrRoutes.refresh(vehicleId, sim.mmtrPointAuthority);
			return route;
		}
	}

	@Test
	public void occupancyChainGivesRedYellowAndDoubleYellow() {
		final Net n = new Net("build/mmtr-aspect-chain");
		assertEquals(MmtrSignalAspect.Aspect.GREEN, n.aspect().aspectOf(n.entry.getHexId()), "clear line shows green");

		n.occupy(n.entry);
		assertEquals(MmtrSignalAspect.Aspect.RED, n.aspect().aspectOf(n.entry.getHexId()), "the protected rail itself occupied is red");

		final Net n2 = new Net("build/mmtr-aspect-chain2");
		n2.occupy(n2.straight);
		assertEquals(MmtrSignalAspect.Aspect.SINGLE_YELLOW, n2.aspect().aspectOf(n2.entry.getHexId()), "one rail beyond occupied is single yellow");

		final Net n3 = new Net("build/mmtr-aspect-chain3");
		n3.occupy(n3.beyond);
		assertEquals(MmtrSignalAspect.Aspect.DOUBLE_YELLOW, n3.aspect().aspectOf(n3.entry.getHexId()), "two rails beyond occupied is double yellow");
	}

	@Test
	public void withoutARouteEveryForkBranchCounts() {
		final Net n = new Net("build/mmtr-aspect-fork");
		n.occupy(n.diverge);
		assertEquals(MmtrSignalAspect.Aspect.SINGLE_YELLOW, n.aspect().aspectOf(n.entry.getHexId()),
			"free driving: the occupied diverging branch is seen from the entry signal (conservative rule)");
	}

	@Test
	public void aSetMainRouteNarrowsTheForkToItsOwnPath() {
		final Net n = new Net("build/mmtr-aspect-route");
		n.occupy(n.diverge);
		final MmtrRoute route = n.setRoute(1, MmtrRoute.Kind.MAIN, true, n.entry, n.straight, n.beyond);
		assertTrue(route.isEstablished(), "the route is SET (its fork is granted)");
		assertEquals(MmtrSignalAspect.Aspect.GREEN, n.aspect().aspectOf(n.entry.getHexId()),
			"the interlocking locked the straight path: the occupied diverging branch no longer affects this signal");

		// The route's own path stays protected: an occupied rail ON the route is still seen.
		n.occupy(n.straight);
		assertEquals(MmtrSignalAspect.Aspect.SINGLE_YELLOW, n.aspect().aspectOf(n.entry.getHexId()),
			"an occupied rail on the set route still gives a caution");
	}

	@Test
	public void aPendingRouteHoldsItsEntrySignalAtDanger() {
		final Net n = new Net("build/mmtr-aspect-pending");
		final ObjectArrayList<String> hexes = new ObjectArrayList<>();
		hexes.add(n.entry.getHexId());
		hexes.add(n.straight.getHexId());
		final ObjectArrayList<String[]> forks = new ObjectArrayList<>();
		forks.add(new String[]{String.valueOf(n.fork.getX()), String.valueOf(n.fork.getY()), String.valueOf(n.fork.getZ()), n.entry.getHexId(), "0"});
		final MmtrRoute route = n.sim.mmtrRoutes.request(new MmtrRoute(1, "v1", MmtrRoute.Kind.MAIN, hexes, forks, n.straight.getHexId(), 1000));
		n.sim.mmtrRoutes.refresh(1, n.sim.mmtrPointAuthority);
		assertFalse(route.isEstablished(), "no grant yet");
		assertEquals(MmtrSignalAspect.Aspect.RED, n.aspect().aspectOf(n.entry.getHexId()),
			"a movement waiting outside its signal sees red even though the track is clear");
	}

	@Test
	public void aShuntRouteDoesNotClearTheMainHead() {
		final Net n = new Net("build/mmtr-aspect-shunt");
		n.occupy(n.diverge);
		final MmtrRoute route = n.setRoute(1, MmtrRoute.Kind.SHUNT, true, n.entry, n.straight, n.beyond);
		assertTrue(route.isEstablished(), "the shunt route is set");
		assertEquals(MmtrSignalAspect.Aspect.SINGLE_YELLOW, n.aspect().aspectOf(n.entry.getHexId()),
			"a subsidiary aspect authorises the shunt with the main head still at danger - it narrows nothing");
	}

	/**
	 * A2 on a 折返 (setback / flip) route: the movement runs over the SAME rail twice (out and back),
	 * so the route's rail list contains it twice. The narrowing must pick the occurrence that matches
	 * the direction being walked - picking the wrong one would make the signal follow the path the
	 * train has already travelled.
	 *
	 * <p>Layout: E(-20..0) -&gt; N(0) -&gt; S(0..60) -&gt; M(60) -&gt; {S2(60..120) | D(60 -&gt; 120,+20)}.
	 * Route E, S, S2, S, E (out over S to S2, back over S to E). With BOTH the diverging D and the
	 * return target E occupied, the signal protecting S walked OUTBOUND from N must still be green:
	 * the locked path there is S2.</p>
	 */
	@Test
	public void aRouteThatRunsOverARailTwiceNarrowsPerDirection() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-aspect-doubled"), false);
		final Position a = new Position(-20, 0, 0);
		final Position n = new Position(0, 0, 0);
		final Position m = new Position(60, 0, 0);
		final Rail entry = through(a, n);
		final Rail s = through(n, m);
		final Rail s2 = through(m, new Position(120, 0, 0));
		final Rail d = through(m, new Position(120, 0, 20));
		sim.rails.add(entry);
		sim.rails.add(s);
		sim.rails.add(s2);
		sim.rails.add(d);
		sim.sync();
		sim.mmtrEnsureSignalColors();

		occupy(sim, d);
		occupy(sim, entry);
		assertEquals(MmtrSignalAspect.Aspect.SINGLE_YELLOW, new MmtrSignalAspect(sim, sim.mmtrRoutes).aspectFrom(s.getHexId(), n),
			"no route: the occupied diverging branch is seen from the signal protecting S");

		final ObjectArrayList<String> rails = new ObjectArrayList<>();
		rails.add(entry.getHexId());
		rails.add(s.getHexId());
		rails.add(s2.getHexId());
		rails.add(s.getHexId());
		rails.add(entry.getHexId());
		final MmtrRoute route = sim.mmtrRoutes.request(new MmtrRoute(1, "v1", MmtrRoute.Kind.MAIN, rails, new ObjectArrayList<>(), entry.getHexId(), 1000));
		sim.mmtrRoutes.refresh(1, sim.mmtrPointAuthority);
		assertTrue(route.isEstablished(), "a route without turnouts is set");
		assertEquals(MmtrSignalAspect.Aspect.GREEN, new MmtrSignalAspect(sim, sim.mmtrRoutes).aspectFrom(s.getHexId(), n),
			"the locked path outbound is S2: neither the diverging branch nor the return target affects this signal");
	}

	private static void occupy(Simulator sim, Rail rail) {
		rail.blockRail(new LongArrayList());
		rail.tick1(sim);
		rail.tick2(0);
	}

	@Test
	public void unknownAndEmptyRailsAreSafe() {
		final Net n = new Net("build/mmtr-aspect-unknown");
		final MmtrSignalAspect aspect = n.aspect();
		assertEquals(MmtrSignalAspect.Aspect.GREEN, aspect.aspectOf(null));
		assertEquals(MmtrSignalAspect.Aspect.GREEN, aspect.aspectOf(""));
		assertEquals(MmtrSignalAspect.Aspect.GREEN, aspect.aspectOf("0000000000000000-0000000000000000"));
		assertEquals(4, aspect.aspectsForAllRails().size(), "one aspect per drawn rail");
	}
}
