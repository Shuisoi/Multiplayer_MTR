package org.mtr.mod.mmtr;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MMTR loaded-chunk bookkeeping (指令驱动的扫描用): the {@code signals scan} command must
 * enumerate every light currently in the world, which vanilla does not expose directly - so the
 * chunk load/unload events maintain the live set per world here. No periodic scanning: the set is
 * only READ when an operator command arrives from the web console.
 */
public final class MmtrChunkTracker {

	private static final Map<ServerWorld, Map<ChunkPos, WorldChunk>> LOADED_CHUNKS = new HashMap<>();

	private MmtrChunkTracker() {
	}

	public static void register() {
		ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
			final Map<ChunkPos, WorldChunk> chunks = LOADED_CHUNKS.computeIfAbsent(world, ignored -> new HashMap<>());
			chunks.put(chunk.getPos(), chunk);
		});
		ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
			final Map<ChunkPos, WorldChunk> chunks = LOADED_CHUNKS.get(world);
			if (chunks != null) {
				chunks.remove(chunk.getPos());
			}
		});
	}

	public static List<WorldChunk> loadedChunks(ServerWorld world) {
		final Map<ChunkPos, WorldChunk> chunks = LOADED_CHUNKS.get(world);
		return chunks == null || chunks.isEmpty() ? List.of() : new ArrayList<>(chunks.values());
	}
}
