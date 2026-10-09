package org.mtr.mod.mmtr;

import org.mtr.core.mmtr.duty.MmtrDutyRegistry;
import org.mtr.core.simulation.Simulator;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.Text;
import org.mtr.mapping.mapper.MinecraftServerHelper;
import org.mtr.mapping.mapper.TextHelper;

import java.util.List;
import java.util.UUID;

/**
 * **值守待办的执行器**：引擎 ↔ 玩家之间那条"引擎说得出、做不到"的缝（notes/408 §2、§7）。
 *
 * <h2>为什么需要它</h2>
 * <p>引擎是 headless 的：它能决定"这位玩家现在该上那趟车"，但**动不了玩家**（传送、骑乘、
 * 说话都只有游戏端能做）。所以引擎把这类事排成一条**待办**（{@code mmtrDuties.pendingBoards()}），
 * 由这里在**服务端 tick 上**取走并执行。这与既有的"网页指令队列"（{@code mmtrPollCommand}）是
 * 同一个形状，只是那一条队列是给"要动世界方块的指令"用的。</p>
 *
 * <h2>两种待办的含义完全不同</h2>
 * <ul>
 *   <li>{@code direct = true}（面板上点了"直接上车"，或 {@code duty assign … 1A} 派的车）：把玩家
 *       **传送上车** —— 复用 {@link MmtrBoardPlayer#board}，与 {@code /mtr mmtrboard} / {@code board}
 *       指令**同一条路**（同一段落点算法、同一条客户端进入驾驶室的流程），不另写一套。
 *       **进哪一间驾驶室读的是这条值守上记的 {@code cabSpec}**（派车指定的那一间），只有在记录上
 *       为空串时才回落到"引擎按行进方向挑"。</li>
 *   <li>{@code direct = false}（"站台接站"）：**不传送任何人**。玩家本来就是自己走到下一站
 *       站台上的，这里唯一要做的是**告诉他车到了**（"可以上车接管了"）。剩下的一步由他自己按 G
 *       完成 —— 上了车、`enterMmtrCab` 把钥匙写成他的之后，引擎下一个 tick 自己就会把
 *       值守推到 `DRIVING`（见 {@code MmtrDutyRegistry.tick}）。</li>
 * </ul>
 *
 * <h2>为什么两种都必须在处理完之后调 {@code markBoardDone}</h2>
 * <p>待办是一张**只增不减**的表：不做完标记，{@code direct = false} 那条就会每拍重复播报同一句话，
 * 而 {@code direct = true} 那条会每拍重复传送 —— 后者是"人根本下不来"的那种事故。
 * 人不在线时同样要标记（找谁去执行是没有意义的），只是要留一条日志说明为什么跳过了。</p>
 */
public final class MmtrDutyBoardWatch {

	private MmtrDutyBoardWatch() {
	}

	/** 每服务端 tick 一次（由 {@code MmtrCommandExecutor} 在既有的引擎 tick 钩子里调用）。 */
	public static void tick(MinecraftServer minecraftServer, Simulator simulator) {
		// pendingBoards() 给的是**拷贝**（引擎侧 new 了一份），所以下面边遍历边 markBoardDone 是安全的。
		final List<MmtrDutyRegistry.PendingBoard> pending = simulator.mmtrDuties.pendingBoards();
		for (final MmtrDutyRegistry.PendingBoard board : pending) {
			final ServerPlayerEntity player = findPlayer(minecraftServer, board.playerUuid());
			if (player == null) {
				org.mtr.mod.Init.LOGGER.info("[MMTR-DUTY] 值守待办跳过：玩家 {} 不在线（车 {}）", board.playerUuid(), board.vehicleId());
				simulator.mmtrDuties.markBoardDone(board.playerUuid());
				continue;
			}
			if (board.isEject()) {
				/*
				 * **把上一个玩家弹出车厢**（用户口径 2026-10-09："在车站接车时先将上个玩家弹出车厢后，
				 * 另一边玩家进入驾驶室"）。引擎那一半已经做完（钥匙收回、那个人的值守落到"已退出"），
				 * **物理上车只有游戏端能做**：从骑乘表里摘掉他，MTR 那条乘车路下一拍就会把他放下来。
				 */
				org.mtr.mod.Init.updateRidingEntity(player, true);
				send(player, "驾驶室已交给新司机 —— 你被弹出车厢");
				org.mtr.mod.Init.LOGGER.info("[MMTR-DUTY] 弹出车厢：玩家 {} 被请出车 {}（驾驶室已交给新司机）",
					board.playerUuid(), board.vehicleId());
			} else if (board.direct()) {
				/*
				 * **传送用的驾驶室 = 这条值守上记的那一间**（notes/409 §3）。
				 *
				 * <p>{@code board(..., "")} 会让 {@code MmtrBoardPlayer} 反过来问引擎"该进哪一间"
				 * （{@code mmtrPreferredCabSpec}）—— 那对"没指定驾驶室"的认领是对的，但对**派车**
				 * （{@code duty assign … <驾驶室>}）就是"记住了却没用"：引擎记录了 1A，人却被送进
				 * 引擎偏好值那一端。所以这里读记录上的 {@link MmtrDutyRegistry.Duty#cabSpec()}，
				 * 空串才回落到"让引擎挑"。</p>
				 *
				 * <p>这样"记住的驾驶室"与"实际用的驾驶室"是**同一个值**，不可能分叉。</p>
				 */
				final MmtrDutyRegistry.Duty duty = simulator.mmtrDuties.of(board.playerUuid());
				final String cabSpec = duty == null ? "" : duty.cabSpec();
				if (!MmtrBoardPlayer.board(minecraftServer, player, board.vehicleId(), cabSpec)) {
					// 车没了 / 算不出落点：如实说一句，并且**不要**把待办留着 —— 留着只会每拍再试一次同样会失败的事。
					org.mtr.mod.Init.LOGGER.warn("[MMTR-DUTY] 送上车失败：车 {} 找不到落点（玩家 {}）", board.vehicleId(), board.playerUuid());
					send(player, "上车请求没能落实（车 " + board.vehicleId() + " 现在算不出落点）—— 看服务端日志 [MMTR-DUTY] / [MMTR-BOARD]");
				} else if (!cabSpec.isEmpty()) {
					org.mtr.mod.Init.LOGGER.info("[MMTR-DUTY] 送上车用的驾驶室来自值守记录（玩家 {}，车 {}，驾驶室 {}）",
						board.playerUuid(), board.vehicleId(), cabSpec);
				}
			} else {
				send(player, arrivedText(simulator, board));
			}
			simulator.mmtrDuties.markBoardDone(board.playerUuid());
		}
	}

	/** "车到了"那一句：带上车次与站台名（站台名可能查不到 —— 折返点/股道那种目标本来就没有站台）。 */
	private static String arrivedText(Simulator simulator, MmtrDutyRegistry.PendingBoard board) {
		final MmtrDutyRegistry.Duty duty = simulator.mmtrDuties.of(board.playerUuid());
		final String jobId = duty == null || duty.jobId().isEmpty() ? ("车 " + board.vehicleId()) : duty.jobId();
		String platform = "";
		if (board.platformId() != 0) {
			final org.mtr.core.data.Platform target = simulator.platformIdMap.get(board.platformId());
			platform = target == null ? "（站台 " + board.platformId() + "）" : target.getName();
		}
		return jobId + " 已到站" + (platform.isEmpty() ? "" : " " + platform) + " —— 对着司机门按 G 上车即可接管";
	}

	private static void send(ServerPlayerEntity player, String text) {
		player.sendMessage(new Text(TextHelper.literal(text).data), true);
	}

	/**
	 * 按 uuid 找在线玩家。
	 *
	 * <p>仓库里现成的只有 {@code MmtrBoardPlayer.findPlayer(server, 名字)}（**按名字**遍历）——
	 * 而引擎只认 uuid（它不知道玩家的显示名）。所以这里补一个按 uuid 的，不新造一套遍历方式，
	 * 仍走 {@code MinecraftServerHelper.iteratePlayers}。</p>
	 */
	private static ServerPlayerEntity findPlayer(MinecraftServer minecraftServer, UUID playerUuid) {
		if (minecraftServer == null || playerUuid == null) {
			return null;
		}
		final ServerPlayerEntity[] found = {null};
		MinecraftServerHelper.iteratePlayers(minecraftServer, player -> {
			if (found[0] == null && playerUuid.equals(player.getUuid())) {
				found[0] = player;
			}
		});
		return found[0];
	}
}
