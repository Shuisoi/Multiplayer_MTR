package org.mtr.mod.packet;

import org.mtr.core.Main;
import org.mtr.core.data.Vehicle;
import org.mtr.core.mmtr.duty.MmtrDutyRegistry;
import org.mtr.core.simulation.Simulator;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.Init;

import java.util.UUID;

/**
 * **上行：客户端把自己那一份位置传给引擎**（notes/409 §4）—— 上下行架构里"上"的那一半。
 *
 * <h2>它要解决什么</h2>
 * <p>用户口径（notes/409 §0）："在停站符合交接条件后，明确赋予玩家驾驶的权利并使玩家与列车之间绑定，
 * 此时玩家客户端下载当前列车位置路径需要旁路至对账纠正路径，**玩家正式开始上传该列车位置**，
 * 这样做的目的是防止客户端延迟导致列车操作、动画因网络原因卡顿。"</p>
 *
 * <p>也就是说：交接之后**位置权威在客户端**。引擎（服务端）不再采纳自己算出来的位移，改用这一条
 * 传上来的位置与速度；而观察者不需要第二条通路 —— ① {@code MmtrVehicleMotionSync} 本来就是每 tick
 * 读**权威车辆**的 {@code railProgress} 打包发出去的，于是他们拿到的就是上传后的位置
 * （notes/409 §4.2 第 5 条：**上行只改"权威从哪来"，不改"怎么发出去"**）。</p>
 *
 * <h2>这一条包里有什么 / 没有什么</h2>
 * <p>四个字段：{@code vehicleId} / {@code railProgressM}（累计里程，与引擎同一坐标系）/
 * {@code speedMilli}（m/ms，与 ① 同口径）/ {@code sequence}（单调递增序号，只用于丢包与乱序的判据）。
 * <b>手柄六件套不在这里</b> —— 它继续走 {@code PacketDriveControl}（notes/409 §4.3 的所有权表：
 * 上行只补位置，不搬手柄）。</p>
 *
 * <h2>三道校验都在引擎侧</h2>
 * <p>认人 / 认序号 / 认合理性写在 {@code Vehicle#mmtrAcceptUploadedMotion} 里（那里才拿得到"引擎当前
 * 在哪"这个参照），本类只负责把帧取出来、问清楚"发件人是不是这辆车的驾驶权持有人"、然后交给引擎。
 * 判据本身**不在这一层写死**：权威是"这辆车归谁"的函数，而真源是值守状态机（notes/408 §2.4）。</p>
 *
 * <h2>开关</h2>
 * <p>{@code -Dmmtr.upload}（**默认 false**）：两端都关着时这一条路一个字节都不发/不收，行为与今天
 * 逐位一致。要先实测出"最大可能位移"那三个界，再把默认值改成 true（notes/409 §4.5）。</p>
 */
public final class PacketMmtrUploadMotion extends PacketHandler {

	private final long vehicleId;
	private final double railProgressM;
	private final double speedMilli;
	private final int sequence;

	public PacketMmtrUploadMotion(PacketBufferReceiver packetBufferReceiver) {
		vehicleId = packetBufferReceiver.readLong();
		railProgressM = packetBufferReceiver.readDouble();
		speedMilli = packetBufferReceiver.readDouble();
		sequence = packetBufferReceiver.readInt();
	}

	public PacketMmtrUploadMotion(long vehicleId, double railProgressM, double speedMilli, int sequence) {
		this.vehicleId = vehicleId;
		this.railProgressM = railProgressM;
		this.speedMilli = speedMilli;
		this.sequence = sequence;
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeLong(vehicleId);
		packetBufferSender.writeDouble(railProgressM);
		packetBufferSender.writeDouble(speedMilli);
		packetBufferSender.writeInt(sequence);
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		/*
		 * 服务端把总开关挡在**最外面**：关着的时候连"找车"都不做（这条路上不该有任何副作用）。
		 * 引擎侧那一份判据也读同一个属性，所以两端一起关就是"这条路不存在"。
		 */
		if (!Vehicle.mmtrUploadEnabled()) {
			return;
		}
		final Main main = Init.getMain();
		if (main == null || serverPlayerEntity == null) {
			return;
		}
		final UUID crewUuid = serverPlayerEntity.getUuid();
		if (crewUuid == null) {
			return;
		}
		final String playerName = serverPlayerEntity.getName() == null ? "" : serverPlayerEntity.getName().getString();
		for (final net.minecraft.server.world.ServerWorld serverWorld : minecraftServer.data.getWorlds()) {
			final Simulator simulator = main.getSimulator(Init.getWorldId(new World(serverWorld)));
			if (simulator == null || simulator.mmtrFindVehicle(vehicleId) == null) {
				continue;
			}
			/*
			 * 认人（notes/409 §4.2 第 1 条）：发件人必须是这辆车**当前的驾驶权持有人**。
			 * 判据取自值守状态机，而不是包里的任何字段 —— 客户端能编造位置，但编不出"引擎认我是这辆车的司机"。
			 *
			 * <p>★ **{@code DRIVING_EXIT_ARMED}（"运转中·下一站退出"）也算持有人** —— 2026-10-10
			 * 实机抓到的洞：那个状态的语义是**司机还在开、只是约好了下一站交还**（notes/408 §2.2）。
			 * 把它排除掉，司机的上行会在按下"下一站退出"的那一刻全被拒，服务端转头用自己的位置，
			 * 而人还在开车 ⇒ 现场就是"一按下一站退出，车被拽一下"。实机读数（18:44:07 之后每秒一条）：</p>
			 *
			 * <pre>[MMTR-UP] 丢弃（Shuisoi 不是这辆车的驾驶权持有人 —— 值守状态机没把他记成这辆车的司机）</pre>
			 */
			final MmtrDutyRegistry.Duty duty = simulator.mmtrDuties.of(crewUuid);
			final boolean holder = duty != null
				&& (duty.state() == MmtrDutyRegistry.State.DRIVING || duty.state() == MmtrDutyRegistry.State.DRIVING_EXIT_ARMED)
				&& duty.vehicleId() == vehicleId;
			/*
			 * 与 {@code PacketMmtrDutyOp} 同一条路：引擎状态必须在**模拟线程**上改（{@code simulator.run}），
			 * 所以这里只取一次要用的东西，改状态那一段整个放进去。
			 */
			simulator.run(() -> {
				final Vehicle vehicle = simulator.mmtrFindVehicle(vehicleId);
				if (vehicle != null) {
					vehicle.mmtrAcceptUploadedMotion(crewUuid, playerName, railProgressM, speedMilli, sequence, simulator.getCurrentMillis(), holder);
				}
			});
			return;
		}
	}
}
