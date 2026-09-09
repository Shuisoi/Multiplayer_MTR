package org.mtr.mod.mmtr;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import org.mtr.core.Main;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.MinecraftServerHelper;
import org.mtr.mod.Init;
import org.mtr.mod.packet.PacketMmtrRoutes;

import java.util.HashMap;
import java.util.Map;

/**
 * MMTR route-mirror pusher (A2): once per server tick, compare the engine's derived route views
 * (locked path + PENDING entry rails) against the last push and broadcast only on change. An idle
 * world with no route set sends nothing, and a route going SET/PENDING reaches every client in the
 * same tick the interlocking changes - so the signal heads never disagree with the engine.
 */
public final class MmtrRouteMirror {

	/** Last pushed signature per world id (a server can host several worlds/simulators). */
	private static final Map<String, String> LAST_SIGNATURE = new HashMap<>();

	private MmtrRouteMirror() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(MmtrRouteMirror::tick);
	}

	private static void tick(MinecraftServer minecraftServer) {
		final Main main = Init.getMain();
		if (main == null) {
			return;
		}
		for (final ServerWorld serverWorld : minecraftServer.getWorlds()) {
			final String worldId = Init.getWorldId(new World(serverWorld));
			final Simulator simulator = main.getSimulator(worldId);
			if (simulator == null) {
				continue;
			}
			final Object2ObjectOpenHashMap<String, ObjectArrayList<String>> nextRails = simulator.mmtrRoutes.setMainRouteNextRails();
			final ObjectOpenHashSet<String> pendingEntries = simulator.mmtrRoutes.pendingEntryRails();
			// B3b: the block sections of every SPLIT rail (a wayside signal in mid-rail). Empty in a
			// world whose lights all stand beside nodes - which is the normal case - so nothing is sent.
			final Object2ObjectOpenHashMap<String, ObjectArrayList<org.mtr.core.mmtr.signal.MmtrBlockService.Block>> splitRails = simulator.mmtrBlocks.splitRails();
			final String signature = nextRails.toString() + "|" + pendingEntries.toString() + "|" + splitRails.toString();
			if (signature.equals(LAST_SIGNATURE.get(worldId))) {
				continue;
			}
			LAST_SIGNATURE.put(worldId, signature);
			final String content = PacketMmtrRoutes.contentOf(nextRails, pendingEntries, splitRails);
			final org.mtr.mapping.holder.ServerWorld mappedWorld = new org.mtr.mapping.holder.ServerWorld(serverWorld);
			MinecraftServerHelper.iteratePlayers(mappedWorld, serverPlayerEntity -> Init.REGISTRY.sendPacketToClient(serverPlayerEntity, new PacketMmtrRoutes(content)));
		}
	}

	/** Forget the cached signature (world unload / tests) so the next tick re-pushes. */
	public static void reset() {
		LAST_SIGNATURE.clear();
	}
}
