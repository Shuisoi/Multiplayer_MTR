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

import java.util.UUID;

/**
 * **综合运转面板的按钮**（客户端 → 服务端 → 引擎）：认领一趟车、马上退出、下一站退出，
 * 以及**"要一份全部车次的列表"**（{@link Op#LIST} —— 面板的列表页靠它，见 {@link PacketMmtrDutyList}）。
 *
 * <h2>为什么与 {@code PacketMmtrCabOp} 分成两个包</h2>
 * <p>那个包管"人的身体在驾驶室里"（进/出驾驶室、门、子任务确认），它推的是**驾驶室**的状态；
 * 这个包管"这件事归谁"（值守），它推的是**作业单**的状态。两件事的生命周期不同
 * （钥匙可以在我手里而车不归我开 —— 那正是 notes/407 那个洞），合成一个包会让
 * "谁在什么条件下改哪个状态"再次说不清。</p>
 *
 * <h2>两处实现细节</h2>
 * <ol>
 *   <li><b>直接调引擎，不走文本指令队列</b>：与 {@code TASK_TAKE/TASK_RELEASE} 同一条路
 *       （{@code PacketMmtrCabOp.runServer}）—— 引擎状态必须在**模拟线程**上改，
 *       而且这样能当场把拒绝原因拿回来写进指令日志。</li>
 *   <li><b>传送那一半不在这里做</b>：引擎动不了玩家，它会给出一条**待办**（{@code pendingBoards()}），
 *       由游戏端的 {@code MmtrDutyBoardWatch} 在服务端 tick 上执行。刻意只留**一个**执行者 ——
 *       如果这里也顺手发一条 {@code board …}，同一个玩家会被两处各传送一次。</li>
 * </ol>
 */
public final class PacketMmtrDutyOp extends PacketHandler {

	public enum Op {
		CLAIM_DIRECT("直接上车"),
		CLAIM_PLATFORM("站台接站"),
		EXIT_NOW("马上退出"),
		EXIT_NEXT("下一站退出"),
		/**
		 * **要一份"全部车次"的列表**（{@code vehicleId} 传 0）。
		 *
		 * <p>它与上面四个是同一族动作（"面板按了一下"），但**不针对任何一辆车**：车上那四个 op 的
		 * 判据是"这辆车归谁"，而这条问的是"场上有哪些车次"。所以服务端拿到它之后不找车、
		 * 只按玩家所在维度取 simulator，然后把 {@code MmtrDutyRegistry.allVehicleRows()} 发给**他一个人**。</p>
		 */
		LIST("要车次列表");

		public final String label;

		Op(String label) {
			this.label = label;
		}
	}

	private final long vehicleId;
	private final Op op;

	public PacketMmtrDutyOp(PacketBufferReceiver packetBufferReceiver) {
		vehicleId = packetBufferReceiver.readLong();
		final int ordinal = packetBufferReceiver.readInt();
		op = ordinal >= 0 && ordinal < Op.values().length ? Op.values()[ordinal] : Op.CLAIM_DIRECT;
	}

	public PacketMmtrDutyOp(long vehicleId, Op op) {
		this.vehicleId = vehicleId;
		this.op = op;
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeLong(vehicleId);
		packetBufferSender.writeInt(op.ordinal());
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		final Main main = Init.getMain();
		if (main == null || serverPlayerEntity == null) {
			return;
		}
		final UUID crewUuid = serverPlayerEntity.getUuid();
		if (crewUuid == null) {
			return;
		}
		final String playerName = serverPlayerEntity.getName() == null ? "" : serverPlayerEntity.getName().getString();

		/*
		 * LIST 先走，因为**它找 simulator 的方式和其余四个 op 不一样**：
		 *   · 那四个都指着一辆车（vehicleId），靠"哪条股道上有这辆车"定位是哪个维度（mmtrFindVehicle）；
		 *   · "场上有哪些车次"不针对任何一辆车（vehicleId 传 0），拿 0 去 mmtrFindVehicle 永远找不到 ——
		 *     只能按**玩家所在的维度**取 simulator。
		 */
		if (op == Op.LIST) {
			final Simulator simulator = main.getSimulator(Init.getWorldId(new World(serverPlayerEntity.getServerWorld().data)));
			if (simulator == null) {
				Init.LOGGER.warn("[MMTR-DUTY] 车次列表请求找不到 {} 所在维度的 simulator", playerName);
				return;
			}
			/*
			 * 取数与发包都在**服务端主线程**上：本局 `useThreadedSimulation=false`（引擎 tick 就在这条
			 * 线程上，见 Init 的 registerStartServerTick），而"在服务端 tick 上把引擎状态读出来发走"
			 * 与 MmtrVehicleMotionSync 每 2 tick 做的事是同一形状。这里只**读**，不改任何引擎状态。
			 */
			final java.util.List<org.mtr.core.mmtr.duty.MmtrDutyRegistry.VehicleRow> rows = simulator.mmtrDuties.allVehicleRows();
			Init.REGISTRY.sendPacketToClient(serverPlayerEntity, new PacketMmtrDutyList(PacketMmtrDutyList.contentOf(rows)));
			Init.LOGGER.info("[MMTR-DUTY] 车次列表 → {}：{} 趟（枚举全部股道上的车，与玩家站在哪里无关）", playerName, rows.size());
			return;
		}

		for (final net.minecraft.server.world.ServerWorld serverWorld : minecraftServer.data.getWorlds()) {
			final Simulator simulator = main.getSimulator(Init.getWorldId(new World(serverWorld)));
			if (simulator == null || simulator.mmtrFindVehicle(vehicleId) == null) {
				continue;
			}
			simulator.run(() -> {
				final String refusal = switch (op) {
					case CLAIM_DIRECT -> simulator.mmtrDuties.claimDirect(crewUuid, playerName, vehicleId);
					case CLAIM_PLATFORM -> simulator.mmtrDuties.claimPlatform(crewUuid, playerName, vehicleId);
					case EXIT_NOW -> simulator.mmtrDuties.exitNow(crewUuid);
					case EXIT_NEXT -> simulator.mmtrDuties.armExitAtNextStop(crewUuid);
					// 到不了（LIST 在上面已经提前返回了）；switch 表达式必须穷尽枚举，这里只是形状。
					case LIST -> null;
				};
				simulator.mmtrCommandResult(refusal == null
					? "[duty] " + playerName + " " + op.label + "：已受理（车 " + vehicleId + "）"
					: "[duty] " + playerName + " " + op.label + " 被拒：" + refusal);
			});
			return;
		}
		// 一条车都没找到：把话说清楚，别静默吞掉（玩家按了按钮却什么都没发生是最难查的那种反馈）
		org.mtr.mod.Init.LOGGER.warn("[MMTR-DUTY] 面板请求找不到车辆 {}（{} / {}）", vehicleId, playerName, op);
	}
}
