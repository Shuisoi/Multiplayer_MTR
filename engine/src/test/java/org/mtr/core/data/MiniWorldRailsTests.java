package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1: platforms/sidings become valid (and attach to their station/depot) only when a real rail
 * with the matching flag spans their exact endpoints. This builds the first real rails.
 */
public final class MiniWorldRailsTests {

	private static Simulator simulator(String suffix) {
		return new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-mini-rails-" + suffix), false);
	}

	private static ObjectArrayList<String> noStyles() {
		return new ObjectArrayList<>();
	}

	@Test
	public void platformRailMakesPlatformAttach() {
		final Simulator sim = simulator("platform");
		final Position p1 = new Position(0, 0, 0);
		final Position p2 = new Position(10, 0, 0);
		final Rail rail = Rail.newPlatformRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, noStyles(), TransportMode.TRAIN);
		final Platform platform = new Platform(p1, p2, TransportMode.TRAIN, sim);
		final Station station = new Station(sim);
		station.setName("Alpha");
		station.setCorners(new Position(-100, -100, -100), new Position(100, 100, 100));

		sim.rails.add(rail);
		sim.platforms.add(platform);
		sim.stations.add(station);
		sim.sync();

		assertTrue(sim.platforms.contains(platform), "platform on a real platform rail must not be pruned");
		assertTrue(station.savedRails.contains(platform), "platform must attach to the station whose corners contain it");
	}

	@Test
	public void sidingRailMakesSidingAttach() {
		final Simulator sim = simulator("siding");
		final Position p1 = new Position(-200, 0, 0);
		final Position p2 = new Position(-190, 0, 0);
		final Rail rail = Rail.newSidingRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, noStyles(), TransportMode.TRAIN);
		final Siding siding = new Siding(p1, p2, 10, TransportMode.TRAIN, sim);
		final Depot depot = new Depot(TransportMode.TRAIN, sim);
		depot.setName("Yard");
		depot.setCorners(new Position(-300, -100, -100), new Position(-100, 100, 100));

		sim.rails.add(rail);
		sim.sidings.add(siding);
		sim.depots.add(depot);
		sim.sync();

		assertTrue(sim.sidings.contains(siding), "siding on a real siding rail must not be pruned");
		assertTrue(depot.savedRails.contains(siding), "siding must attach to the depot whose corners contain it");
	}
}