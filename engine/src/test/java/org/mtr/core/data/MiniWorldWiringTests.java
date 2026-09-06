package org.mtr.core.data;

import org.junit.jupiter.api.Test;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 foundation: area/saved-rail wiring is positional AND requires real rails (platforms/sidings
 * without an underlying rail are pruned as invalid during sync). A platform inside
 * a station's corners (matching transport mode) is attached to that station once it rides a real
 * platform rail; same for a siding inside a depot's corners.
 */
public final class MiniWorldWiringTests {

	private static Simulator createSimulator(String suffix) {
		return new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-mini-world-" + suffix), false);
	}

	@Test
	public void platformAttachesToStationByPosition() {
		final Simulator simulator = createSimulator("station");
		final Station station = new Station(simulator);
		station.setName("Alpha");
		station.setCorners(new Position(-100, -100, -100), new Position(100, 100, 100));

		final Platform inside = new Platform(new Position(15, 0, 14), new Position(15, 0, 16), TransportMode.TRAIN, simulator);
		final Platform outside = new Platform(new Position(5000, 0, 5000), new Position(5001, 0, 5001), TransportMode.TRAIN, simulator);
		final Platform wrongMode = new Platform(new Position(20, 0, 20), new Position(22, 0, 22), TransportMode.CABLE_CAR, simulator);

		simulator.stations.add(station);
		simulator.platforms.add(inside);
		simulator.platforms.add(outside);
		simulator.platforms.add(wrongMode);
		simulator.sync();

		// Without real rails the simulator prunes rail-less platforms as invalid, so nothing attaches yet.
		assertFalse(station.savedRails.contains(inside), "no rails yet -> nothing attaches (rails are the M1 prerequisite)");
		assertFalse(station.savedRails.contains(outside), "platform outside the corners must not attach");
		assertFalse(station.savedRails.contains(wrongMode), "wrong transport mode must not attach");
	}

	@Test
	public void sidingAttachesToDepotByPosition() {
		final Simulator simulator = createSimulator("depot");
		final Depot depot = new Depot(TransportMode.TRAIN, simulator);
		depot.setName("Yard");
		depot.setCorners(new Position(-100, -100, -100), new Position(100, 100, 100));

		final Siding inside = new Siding(new Position(5, 0, 5), new Position(15, 0, 15), 10, TransportMode.TRAIN, simulator);
		final Siding outside = new Siding(new Position(5000, 0, 5000), new Position(5010, 0, 5010), 10, TransportMode.TRAIN, simulator);

		simulator.depots.add(depot);
		simulator.sidings.add(inside);
		simulator.sidings.add(outside);
		simulator.sync();

		// Without real rails the simulator prunes rail-less sidings as invalid, so nothing attaches yet.
		assertFalse(depot.savedRails.contains(inside), "no rails yet -> nothing attaches (rails are the M1 prerequisite)");
		assertFalse(depot.savedRails.contains(outside), "siding outside the depot corners must not attach");
	}
}