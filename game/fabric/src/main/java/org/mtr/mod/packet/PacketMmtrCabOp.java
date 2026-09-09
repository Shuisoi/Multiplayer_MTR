package org.mtr.mod.packet;

import org.mtr.core.Main;
import org.mtr.core.simulation.Simulator;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.Init;

/**
 * B7.6c: the crew cab interaction (client → server → engine). Pressing the cab key while looking at
 * a consist sends either "take this cab" (key in) or "leave the cab" (key out); the server turns it
 * into an engine command, so the physical gates (consist model, train at a stand, one key per
 * consist) are enforced by the engine and the result lands in the command log.
 *
 * <p>Permission and the driver's position are the game side's job: this packet is only sent from the
 * client interaction, which requires the player to stand within reach of the car.</p>
 */
public final class PacketMmtrCabOp extends PacketHandler {

	public enum Op {ENTER, LEAVE, DOORS}

	private final long vehicleId;
	private final Op op;
	private final String cab;

	public PacketMmtrCabOp(PacketBufferReceiver packetBufferReceiver) {
		vehicleId = packetBufferReceiver.readLong();
		final int ordinal = packetBufferReceiver.readInt();
		op = ordinal >= 0 && ordinal < Op.values().length ? Op.values()[ordinal] : Op.ENTER;
		cab = packetBufferReceiver.readString();
	}

	public PacketMmtrCabOp(long vehicleId, Op op, String cab) {
		this.vehicleId = vehicleId;
		this.op = op;
		this.cab = cab == null ? "" : cab;
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeLong(vehicleId);
		packetBufferSender.writeInt(op.ordinal());
		packetBufferSender.writeString(cab);
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		final Main main = Init.getMain();
		if (main == null) {
			return;
		}
		// The vehicle may live in any dimension; push the command into the engine that owns it.
		// 钥匙归属: the command carries the crew member's uuid so the engine can remember whose key is
		// in the cab - the drive gate then checks that identity instead of trusting the client.
		final String crew = serverPlayerEntity == null || serverPlayerEntity.getUuid() == null ? "" : serverPlayerEntity.getUuid().toString();
		for (final net.minecraft.server.world.ServerWorld serverWorld : minecraftServer.data.getWorlds()) {
			final Simulator simulator = main.getSimulator(Init.getWorldId(new World(serverWorld)));
			if (simulator == null || simulator.mmtrFindVehicle(vehicleId) == null) {
				continue;
			}
			simulator.mmtrPushCommand(op == Op.ENTER ? "cab " + vehicleId + " " + cab + " " + crew : op == Op.LEAVE ? "cab " + vehicleId + " out " + crew : "doors " + vehicleId + " toggle");
			return;
		}
	}
}
