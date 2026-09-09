package org.mtr.core.data;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class ClientTests {

	private Simulator simulator;

	@BeforeEach
	public void setUp() {
		simulator = new Simulator("test", new String[]{"test"}, Paths.get("build/test-data-client"), false);
	}

	@Test
	public void testClientConstruction() {
		final UUID uuid = UUID.randomUUID();
		final Client client = new Client(uuid);
		assertNotNull(client);
		assertEquals(uuid, client.uuid);
	}

	@Test
	public void testDefaultPosition() {
		final Client client = new Client(UUID.randomUUID());
		final Position position = client.getPosition();
		assertNotNull(position);
		assertEquals(0, position.getX());
		assertEquals(0, position.getY());
		assertEquals(0, position.getZ());
	}

	@Test
	public void testSetPositionAndUpdateRadius() {
		final Client client = new Client(UUID.randomUUID());
		final Position position = new Position(100, 64, -200);
		client.setPositionAndUpdateRadius(position, 500);
		assertEquals(position, client.getPosition());
		assertEquals(500, client.getUpdateRadius());
	}

	@Test
	public void testPassengerUpdateDoesNotThrow() {
		final Client client = new Client(UUID.randomUUID());
		final Passenger passenger = new Passenger(simulator);
		client.update(passenger, true);
	}

	@Test
	public void testPassengerUpdateKeepAliveDoesNotThrow() {
		final Client client = new Client(UUID.randomUUID());
		final Passenger passenger = new Passenger(simulator);
		// Test the "keep alive" path: second update with needsUpdate=false
		client.update(passenger, true);
		client.update(passenger, false);
	}

	/**
	 * A player who leaves and rejoins gets a fresh (empty) client dataset, but the engine's client
	 * record still believes it has already been told about everything. Keeping that record left
	 * stationary trains and rails invisible after a reconnect until something happened to move.
	 */
	@Test
	public void testRemovedClientResendsEverythingOnRejoin() {
		final UUID uuid = UUID.randomUUID();
		final Client client = new Client(uuid);
		simulator.clients.add(client);
		final Passenger passenger = new Passenger(simulator);

		// First sync: the passenger is new, so it goes out as a full update.
		client.update(passenger, true);
		assertEquals(1, vehiclesLiftsPackets(client));

		// Next cycle: keep-alive only, so there is nothing to send.
		client.update(passenger, false);
		assertEquals(0, vehiclesLiftsPackets(client));

		// The player disconnects (the game layer evicts the record) and rejoins as a new session.
		assertTrue(simulator.removeClient(uuid));
		final Client rejoinedClient = new Client(uuid);
		simulator.clients.add(rejoinedClient);
		rejoinedClient.update(passenger, false);

		// The rejoined client knows nothing, so the same keep-alive update must be a full send again.
		assertEquals(1, vehiclesLiftsPackets(rejoinedClient));
	}

	/** Count the vehicle/lift sync packets {@code client} produces, draining the outgoing queue. */
	private int vehiclesLiftsPackets(Client client) {
		final int[] count = {0};
		client.sendUpdates(simulator);
		simulator.processMessagesS2C(queueObject -> {
			if (queueObject.key.equals(OperationProcessor.VEHICLES_LIFTS)) {
				count[0]++;
			}
		});
		return count[0];
	}
}
