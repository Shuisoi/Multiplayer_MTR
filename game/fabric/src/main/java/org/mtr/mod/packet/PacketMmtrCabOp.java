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
 * <p>For {@link Op#DOORS} the {@code cab} field carries the side to work ({@code ""} = both sides,
 * {@code "left"} / {@code "right"} = the cab crew's per-side door keys).</p>
 *
 * <p>For {@link Op#TASK_CONFIRM} the {@code cab} field carries {@code <子任务序号>:<版本号>} ——
 * 司机对**显示给他的那一版**子任务清单的确认（双向确认的上行那一半，见
 * {@code Vehicle.mmtrConfirmSubTask}）。带版本号是刻意的：两端看到的必须是同一版，
 * 对不上时引擎会记一条日志，而不是把过期的确认当成"确认了现在这一条"。</p>
 *
 * <p>Permission and the driver's position are the game side's job: this packet is only sent from the
 * client interaction, which requires the player to stand within reach of the car.</p>
 */
public final class PacketMmtrCabOp extends PacketHandler {

	public enum Op {ENTER, LEAVE, DOORS, TASK_TAKE, TASK_RELEASE, TASK_CONFIRM}

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
		/*
		 * **计划内接管**（S3 入口）：这条动的是**引擎侧的作业绑定**，不是世界里的实体 ——
		 * 所以它不走文本指令队列（那条队列的游戏端执行器只做世界实体），而是直接调引擎，
		 * 并且必须落在**模拟线程**上（引擎状态都在那一根线程上改）。结果写进指令日志，
		 * 于是网页指令栏与游戏里都能看到"接管成功 / 被拒：车还在动"。
		 */
		if (op == Op.TASK_CONFIRM) {
			final java.util.UUID crewUuid = crew.isEmpty() ? null : java.util.UUID.fromString(crew);
			final int separator = cab.indexOf(':');
			final int index;
			final long revision;
			try {
				index = Integer.parseInt(separator < 0 ? cab : cab.substring(0, separator));
				revision = separator < 0 ? 0 : Long.parseLong(cab.substring(separator + 1));
			} catch (NumberFormatException e) {
				return;
			}
			for (final net.minecraft.server.world.ServerWorld serverWorld : minecraftServer.data.getWorlds()) {
				final Simulator simulator = main.getSimulator(Init.getWorldId(new World(serverWorld)));
				if (simulator == null || simulator.mmtrFindVehicle(vehicleId) == null) {
					continue;
				}
				simulator.run(() -> {
					final String refusal = simulator.mmtrConfirmSubTask(vehicleId, index, revision, crewUuid);
					if (refusal != null) {
						simulator.mmtrCommandResult("[task] 子任务确认被拒：" + refusal);
					}
				});
				return;
			}
			return;
		}
		if (op == Op.TASK_TAKE || op == Op.TASK_RELEASE) {
			final java.util.UUID crewUuid = crew.isEmpty() ? null : java.util.UUID.fromString(crew);
			for (final net.minecraft.server.world.ServerWorld serverWorld : minecraftServer.data.getWorlds()) {
				final Simulator simulator = main.getSimulator(Init.getWorldId(new World(serverWorld)));
				if (simulator == null || simulator.mmtrFindVehicle(vehicleId) == null) {
					continue;
				}
				simulator.run(() -> {
					if (op == Op.TASK_TAKE) {
						final String refusal = simulator.mmtrJobTakeover(vehicleId, crewUuid);
						simulator.mmtrCommandResult(refusal == null
							? "[task] 车 " + vehicleId + " 的作业已交给司机 " + crew
							: "[task] 接管被拒：" + refusal);
					} else {
						final String refusal = simulator.mmtrJobRelease(vehicleId);
						simulator.mmtrCommandResult(refusal == null
							? "[task] 车 " + vehicleId + " 已归还给自动执行"
							: "[task] 归还被拒：" + refusal);
					}
				});
				return;
			}
			return;
		}
		for (final net.minecraft.server.world.ServerWorld serverWorld : minecraftServer.data.getWorlds()) {
			final Simulator simulator = main.getSimulator(Init.getWorldId(new World(serverWorld)));
			if (simulator == null || simulator.mmtrFindVehicle(vehicleId) == null) {
				continue;
			}
			simulator.mmtrPushCommand(op == Op.ENTER ? "cab " + vehicleId + " " + cab + " " + crew : op == Op.LEAVE ? "cab " + vehicleId + " out " + crew : "doors " + vehicleId + " toggle" + (cab.isEmpty() ? "" : " " + cab));
			return;
		}
	}
}
