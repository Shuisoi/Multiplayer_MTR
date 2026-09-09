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
			return;
		}
		// B7.6 crew commands: changeends <vehicleId> | cab <vehicleId> <A|B|out> | doors <vehicleId> [open|close|toggle]
		final String[] parts = command.trim().split("\\s+");
		if (parts.length >= 2 && (parts[0].equals("changeends") || parts[0].equals("cab") || parts[0].equals("doors"))) {
			executeCabCommand(simulator, parts);
			return;
		}
		simulator.mmtrCommandResult("未知指令: " + command + " (支持: signals scan | changeends <id> | cab <id> <A|B|out> | doors <id> [open|close|toggle] [left|right|both])");
	}

	/**
	 * B7.6: OP-side cab ops. {@code changeends <id>} performs the whole 换端 (legal only at a stand,
	 * only on a consist-body train); {@code cab <id> A|B|out} takes/leaves a cab (key in / key out).
	 * The physical gates live in the engine ({@code Vehicle.enterMmtrCab/leaveMmtrCab/
	 * changeEndsMmtrMotion}); this layer only resolves the id and reports back to the command log.
	 */
	private static void executeCabCommand(Simulator simulator, String[] parts) {
		final long vehicleId;
		try {
			vehicleId = Long.parseLong(parts[1]);
		} catch (NumberFormatException e) {
			simulator.mmtrCommandResult("[" + parts[0] + "] vehicleId 必须是数字: " + parts[1]);
			return;
		}
		final org.mtr.core.data.Vehicle vehicle = simulator.mmtrFindVehicle(vehicleId);
		if (vehicle == null) {
			simulator.mmtrCommandResult("[" + parts[0] + "] 找不到车辆 " + vehicleId);
			return;
		}
		if (parts[0].equals("doors")) {
			// Crew door control: no cab/consist requirement, so anyone at the platform can open a
			// standing train's doors through the interact key. The optional side argument ("left" /
			// "right") is the per-side control the cab crew uses (Y / U).
			final String action = parts.length >= 3 ? parts[2].toLowerCase(java.util.Locale.ROOT) : "toggle";
			final String side = parts.length >= 4 ? parts[3] : "both";
			final boolean open = vehicle.vehicleExtraData.mmtrSetDoors(action, side);
			simulator.mmtrCommandResult("[doors] " + vehicleId + (open ? " 开门" : " 关门") + " " + side + " (L=" + vehicle.vehicleExtraData.getMmtrDoorLeft() + " R=" + vehicle.vehicleExtraData.getMmtrDoorRight() + " 手动=" + vehicle.vehicleExtraData.isMmtrDoorManual() + ")");
			return;
		}
		if (vehicle.getMmtrConsistWalker() == null) {
			simulator.mmtrCommandResult("[" + parts[0] + "] 车辆 " + vehicleId + " 不是编组体车（无驾驶室模型）");
			return;
		}
		if (parts[0].equals("changeends")) {
			final boolean ok = vehicle.changeEndsMmtrMotion();
			simulator.mmtrCommandResult("[" + parts[0] + "] " + vehicleId + (ok ? " 换端完成 → " + vehicle.getMmtrActiveCab() : " 换端失败（需停稳且已有驾驶室）"));
			return;
		}
		final String what = parts.length >= 3 ? parts[2].toLowerCase(java.util.Locale.ROOT) : "a";
		// 钥匙归属: the game-side packet appends the crew member's uuid; a web OP command has none and
		// acts as an operator (may take/release any key).
		final java.util.UUID crew = parseCrewUuid(parts.length >= 4 ? parts[3] : null);
		if (what.equals("out") || what.equals("leave")) {
			final boolean ok = vehicle.leaveMmtrCab(crew);
			simulator.mmtrCommandResult("[" + parts[0] + "] " + vehicleId + (ok ? " 已拔钥匙" : " 无钥匙可拔（或钥匙在他人手中）"));
		} else {
			final org.mtr.core.mmtr.consist.MmtrCabState.Cab cab = what.equals("b") ? org.mtr.core.mmtr.consist.MmtrCabState.Cab.CAB_B : org.mtr.core.mmtr.consist.MmtrCabState.Cab.CAB_A;
			final boolean ok = vehicle.enterMmtrCab(cab, crew);
			simulator.mmtrCommandResult("[" + parts[0] + "] " + vehicleId + (ok ? " 已进入 " + cab + "（钥匙归属 " + vehicle.getMmtrCabKeyHolder() + (crew == null ? "" : " " + crew) + "）" : " 无法进入（需停稳且该驾驶室空闲）"));
		}
	}

	/** Parses the optional crew uuid argument; {@code null} when absent or malformed (operator). */
	private static java.util.UUID parseCrewUuid(@javax.annotation.Nullable String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		try {
			return java.util.UUID.fromString(value);
		} catch (IllegalArgumentException e) {
			return null;
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
