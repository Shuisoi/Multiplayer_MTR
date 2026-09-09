package org.mtr.mod.packet;

import org.mtr.core.Main;
import org.mtr.core.simulation.Simulator;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.Init;

/**
 * C7: the crew's coupling interaction (client → server → engine). Pressing the coupler key while
 * aiming at a train sends either "couple this train onto the one I am in" or "uncouple after car
 * {@code cutAfterCarIndex}" — the server turns it into the engine command the OP console already
 * uses ({@code couple}/{@code uncouple}), so every physical gate (调车授权, both trains at a stand,
 * orientation, a real coupler at the cut) is enforced by the engine, never by the client.
 */
public final class PacketMmtrCoupleOp extends PacketHandler {

	public enum Op {COUPLE, UNCOUPLE}

	private final long vehicleId;
	private final long targetVehicleId;
	private final int cutAfterCarIndex;

	public PacketMmtrCoupleOp(PacketBufferReceiver packetBufferReceiver) {
		vehicleId = packetBufferReceiver.readLong();
		targetVehicleId = packetBufferReceiver.readLong();
		cutAfterCarIndex = packetBufferReceiver.readInt();
	}

	public PacketMmtrCoupleOp(long vehicleId, long targetVehicleId, int cutAfterCarIndex) {
		this.vehicleId = vehicleId;
		this.targetVehicleId = targetVehicleId;
		this.cutAfterCarIndex = cutAfterCarIndex;
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeLong(vehicleId);
		packetBufferSender.writeLong(targetVehicleId);
		packetBufferSender.writeInt(cutAfterCarIndex);
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		final Main main = Init.getMain();
		if (main == null) {
			return;
		}
		// The vehicles may live in any dimension; push the command into the engine that owns the one
		// the player aimed at. The command is the same string the web console uses, so the coupling
		// gates (and the command log) stay in one place.
		for (final net.minecraft.server.world.ServerWorld serverWorld : minecraftServer.data.getWorlds()) {
			final Simulator simulator = main.getSimulator(Init.getWorldId(new World(serverWorld)));
			if (simulator == null || simulator.mmtrFindVehicle(vehicleId) == null) {
				continue;
			}
			if (targetVehicleId != 0) {
				simulator.mmtrPushCommand("couple " + vehicleId + " " + targetVehicleId);
			} else {
				simulator.mmtrPushCommand("uncouple " + vehicleId + " " + cutAfterCarIndex);
			}
			return;
		}
	}
}
