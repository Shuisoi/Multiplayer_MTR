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
 * <p>三手柄机车（BR101）多带两个字段：{@code driveHandle}（油门手柄 ±97：正牵引 / 负电阻制动 /
 * 0 关闭）与 {@code cruiseSpeedKmh}（定速巡航设定值，0 = 关闭，步长 5）。{@code brakeNotch} 对三手柄车底
 * 是**制动手柄的位置下标**（运行/1A/1B/2…8/EB），对老车底仍是档位数 —— 同一个字段按车型解释，
 * 引擎侧 {@code ConsistType.controlMode} 决定用哪套口径（见 docs/01-设计/驾驶输入与控制模型.md §6.1）。</p>
 *
 * <p>{@code acknowledge} (A3) is the AWS cancel button: a one-shot press that must reach the engine
 * even when no notch changed, so it travels on its own drive command.</p>
 */
public final class PacketDriveControl extends PacketHandler {

	private final long vehicleId;
	private final int throttleNotch;
	private final int brakeNotch;
	private final int driveHandle;
	private final int cruiseSpeedKmh;
	private final int reverser;
	private final boolean emergency;
	private final boolean acknowledge;

	public PacketDriveControl(PacketBufferReceiver packetBufferReceiver) {
		vehicleId = packetBufferReceiver.readLong();
		throttleNotch = packetBufferReceiver.readInt();
		brakeNotch = packetBufferReceiver.readInt();
		driveHandle = packetBufferReceiver.readInt();
		cruiseSpeedKmh = packetBufferReceiver.readInt();
		reverser = packetBufferReceiver.readInt();
		emergency = packetBufferReceiver.readBoolean();
		acknowledge = packetBufferReceiver.readBoolean();
	}

	public PacketDriveControl(long vehicleId, int throttleNotch, int brakeNotch, int reverser, boolean emergency) {
		this(vehicleId, throttleNotch, brakeNotch, reverser, emergency, false);
	}

	public PacketDriveControl(long vehicleId, int throttleNotch, int brakeNotch, int reverser, boolean emergency, boolean acknowledge) {
		this(vehicleId, throttleNotch, brakeNotch, reverser, emergency, acknowledge, 0, 0);
	}

	public PacketDriveControl(long vehicleId, int throttleNotch, int brakeNotch, int reverser, boolean emergency, boolean acknowledge,
		int driveHandle, int cruiseSpeedKmh) {
		this.vehicleId = vehicleId;
		this.throttleNotch = throttleNotch;
		this.brakeNotch = brakeNotch;
		this.driveHandle = driveHandle;
		this.cruiseSpeedKmh = cruiseSpeedKmh;
		this.reverser = reverser;
		this.emergency = emergency;
		this.acknowledge = acknowledge;
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeLong(vehicleId);
		packetBufferSender.writeInt(throttleNotch);
		packetBufferSender.writeInt(brakeNotch);
		packetBufferSender.writeInt(driveHandle);
		packetBufferSender.writeInt(cruiseSpeedKmh);
		packetBufferSender.writeInt(reverser);
		packetBufferSender.writeBoolean(emergency);
		packetBufferSender.writeBoolean(acknowledge);
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		final ControlState state = new ControlState()
			.setThrottleNotch(throttleNotch).setBrakeNotch(brakeNotch).setDriveHandle(driveHandle)
			.setCruiseSpeedKmh(cruiseSpeedKmh).setReverser(reverser)
			.setEmergency(emergency).setAcknowledge(acknowledge);
		// The engine only honours control from the player currently occupying a cab driver seat
		// of this consist (occupation lock), so attach the sender's identity.
		Init.sendMessageC2S(OperationProcessor.MMTR_DRIVE, minecraftServer, null, new MmtrDriveControl(vehicleId, state, serverPlayerEntity == null ? null : serverPlayerEntity.getUuid()), null, null);
	}
}
