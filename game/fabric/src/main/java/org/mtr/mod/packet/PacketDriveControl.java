package org.mtr.mod.packet;

import org.mtr.core.mmtr.ControlState;
import org.mtr.core.operation.MmtrDriveControl;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.Init;

/**
 * MMTR explicit separated drive command (client -&gt; server -&gt; engine).
 * Throttle and brake are independent notches; reverser is carried for future shunting use.
 *
 * <p>{@code acknowledge} (A3) is the AWS cancel button: a one-shot press that must reach the engine
 * even when no notch changed, so it travels on its own drive command.</p>
 */
public final class PacketDriveControl extends PacketHandler {

	private final long vehicleId;
	private final int throttleNotch;
	private final int brakeNotch;
	private final int reverser;
	private final boolean emergency;
	private final boolean acknowledge;

	public PacketDriveControl(PacketBufferReceiver packetBufferReceiver) {
		vehicleId = packetBufferReceiver.readLong();
		throttleNotch = packetBufferReceiver.readInt();
		brakeNotch = packetBufferReceiver.readInt();
		reverser = packetBufferReceiver.readInt();
		emergency = packetBufferReceiver.readBoolean();
		acknowledge = packetBufferReceiver.readBoolean();
	}

	public PacketDriveControl(long vehicleId, int throttleNotch, int brakeNotch, int reverser, boolean emergency) {
		this(vehicleId, throttleNotch, brakeNotch, reverser, emergency, false);
	}

	public PacketDriveControl(long vehicleId, int throttleNotch, int brakeNotch, int reverser, boolean emergency, boolean acknowledge) {
		this.vehicleId = vehicleId;
		this.throttleNotch = throttleNotch;
		this.brakeNotch = brakeNotch;
		this.reverser = reverser;
		this.emergency = emergency;
		this.acknowledge = acknowledge;
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeLong(vehicleId);
		packetBufferSender.writeInt(throttleNotch);
		packetBufferSender.writeInt(brakeNotch);
		packetBufferSender.writeInt(reverser);
		packetBufferSender.writeBoolean(emergency);
		packetBufferSender.writeBoolean(acknowledge);
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		final ControlState state = new ControlState()
			.setThrottleNotch(throttleNotch).setBrakeNotch(brakeNotch).setReverser(reverser)
			.setEmergency(emergency).setAcknowledge(acknowledge);
		// The engine only honours control from the player currently occupying a cab driver seat
		// of this consist (occupation lock), so attach the sender's identity.
		Init.sendMessageC2S(OperationProcessor.MMTR_DRIVE, minecraftServer, null, new MmtrDriveControl(vehicleId, state, serverPlayerEntity == null ? null : serverPlayerEntity.getUuid()), null, null);
	}
}
