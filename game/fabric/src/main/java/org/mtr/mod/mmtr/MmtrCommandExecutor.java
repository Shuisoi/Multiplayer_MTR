package org.mtr.mod.mmtr;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import org.mtr.core.Main;
import org.mtr.core.mmtr.signal.MmtrSignalRegistry.SignalEntry;
import org.mtr.core.simulation.Simulator;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.World;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockSignalBase;

/**
 * MMTR game-side command executor (指令执行器): polls the engine command queue pushed from the
 * web console (指令栏) once per server tick and runs them against the real world, e.g.
 * {@code signals scan} - register every placed MTR signal light in currently loaded chunks as an
 * AUTO wayside entry. Covered binds (BOUND) are never touched by the scan.
 */
public final class MmtrCommandExecutor {

	private MmtrCommandExecutor() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(MmtrCommandExecutor::tick);
	}

	private static void tick(MinecraftServer minecraftServer) {
		final Main main = Init.getMain();
		if (main == null) {
			return;
		}
		for (final ServerWorld serverWorld : minecraftServer.getWorlds()) {
			final Simulator simulator = main.getSimulator(Init.getWorldId(new World(serverWorld)));
			if (simulator == null) {
				continue;
			}
			final String command = simulator.mmtrPollCommand();
			if (command != null && !command.isEmpty()) {
				execute(simulator, serverWorld, command);
			}
		}
	}

	private static void execute(Simulator simulator, ServerWorld serverWorld, String command) {
		if (command.equals("signals scan")) {
			scanSignals(simulator, serverWorld);
		} else {
			simulator.mmtrCommandResult("未知指令: " + command + " (支持: signals scan)");
		}
	}

	/**
	 * Scan currently loaded chunks for placed MTR signal light block entities and register them
	 * as AUTO entries (upsert). Entries already BOUND to a rail stay untouched.
	 */
	private static void scanSignals(Simulator simulator, ServerWorld serverWorld) {
		int found = 0;
		int added = 0;
		int skippedBound = 0;
		final World world = new World(serverWorld);
		for (final WorldChunk chunk : MmtrChunkTracker.loadedChunks(serverWorld)) {
			for (final BlockEntity blockEntity : chunk.getBlockEntities().values()) {
				final BlockPos pos = blockEntity.getPos();
				final BlockState blockState = world.getBlockState(new org.mtr.mapping.holder.BlockPos(pos.getX(), pos.getY(), pos.getZ()));
				final Object block = blockState.getBlock().data;
				if (!MmtrSignalBlocks.isSignalLight(block)) {
					continue;
				}
				found++;
				final SignalEntry existing = simulator.mmtrSignals.get(pos.getX(), pos.getY(), pos.getZ());
				if (existing != null && "BOUND".equals(existing.mode)) {
					skippedBound++;
					continue;
				}
				if (simulator.mmtrSignalOp(pos.getX(), pos.getY(), pos.getZ(), BlockSignalBase.getAngle(blockState), MmtrSignalBlocks.aspectsOf(block), "set", "")) {
					added++;
				}
			}
		}
		simulator.mmtrCommandResult("[signals] 扫描完成: 找到 " + found + " 个信号灯, 新增 " + added + " 个 AUTO 条目, 跳过 BOUND " + skippedBound + " 个");
	}
}
