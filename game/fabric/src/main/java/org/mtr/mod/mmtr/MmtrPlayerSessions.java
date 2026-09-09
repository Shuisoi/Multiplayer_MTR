package org.mtr.mod.mmtr;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.mtr.core.Main;
import org.mtr.core.simulation.Simulator;
import org.mtr.mapping.holder.World;
import org.mtr.mod.Init;

/**
 * MMTR player-session bookkeeping: forget a player's engine-side {@link org.mtr.core.data.Client}
 * record when they leave the server.
 *
 * <p>The engine keeps one client record per UUID that remembers what has already been sent to that
 * client, so a stationary train only has to be pushed once. A player who leaves and rejoins starts
 * from an empty client dataset, but the record survived the disconnect and kept claiming the client
 * already knew about every vehicle, rail, lift and passenger - so a rejoined client rendered an empty
 * world (no trains, no rails) until something happened to move and dirty it. Dropping the record on
 * disconnect makes the next join build a fresh one, which re-sends everything.</p>
 */
public final class MmtrPlayerSessions {

	private MmtrPlayerSessions() {
	}

	public static void register() {
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			final Main main = Init.getMain();
			if (main == null) {
				return;
			}
			final ServerPlayerEntity player = handler.getPlayer();
			final ServerWorld serverWorld = player.getServerWorld();
			final Simulator simulator = main.getSimulator(Init.getWorldId(new World(serverWorld)));
			if (simulator != null) {
				final java.util.UUID uuid = player.getUuid();
				simulator.run(() -> simulator.removeClient(uuid));
			}
		});
	}
}
