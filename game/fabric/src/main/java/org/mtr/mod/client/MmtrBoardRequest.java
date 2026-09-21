package org.mtr.mod.client;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mod.Init;
import org.mtr.mod.render.MmtrInteractPrompt;

import javax.annotation.Nullable;

/**
 * 「把这个玩家放到某辆车的驾驶室」的**客户端落地**（服务端请求 → 客户端执行）。
 *
 * <h2>为什么不直接进</h2>
 *
 * <p>服务端把玩家挪到车旁之后，车辆的**客户端镜像**（{@code MinecraftClientData.vehicles}）不一定
 * 已经到位 —— 镜像按玩家位置同步，而"落点"与"镜像出现"之间隔着一次服务端 tick 与一次包往返。
 * 收到 {@code PacketMmtrBoardPlayer} 就立刻算座位点，最常见的失败是"车还不在镜像里"，
 * 而那是一次**时机**问题，不是错误。所以这里每拍重试一小段时间，其间给玩家一句"正在上车"。</p>
 *
 * <h2>与按 G 的关系</h2>
 *
 * <p>坐进驾驶室那一步走的是**同一段代码**（{@link MmtrCabInteraction#enterCab}）：它负责写骑乘坐标、
 * 发 {@code PacketMmtrCabOp} 申领驾驶室、并把座位朝向摆正。这里只是免掉"站在门口 + 用准星瞄准"
 * 这两个前提，让 {@code /mtr mmtrboard} 与引擎指令栏的 {@code train board} 能把人直接放进去。</p>
 */
public final class MmtrBoardRequest {

	private MmtrBoardRequest() {
	}

	/** 等车镜像出现的最长时间：镜像没来就说明服务端的落点没生效，早点如实报错比一直静默重试好。 */
	private static final long TIMEOUT_MILLIS = 5000;

	private static long pendingVehicleId;
	private static String pendingCabSpec = "";
	private static long pendingUntilMillis;
	/** 只提示一次"正在上车"，重试期间不刷屏。 */
	private static boolean pendingAnnounced;

	/**
	 * @param vehicleId 目标车
	 * @param cabSpec   {@code <车厢序号><A|B>}（1 起）；空串 = 挑第一个可用的驾驶室
	 */
	public static void request(long vehicleId, String cabSpec) {
		/*
		 * 只在**主线程**动手：本方法由包处理线程调用（netty），而"算座位点"要读客户端车辆镜像、
		 * 取玩家实体、发聊天消息 —— 那些都归主线程。所以这里只登记意图，真正的执行在 tick()。
		 * 代价是至多晚一拍（50 ms），换来的是不会在包线程上碰渲染/世界状态。
		 */
		pendingVehicleId = vehicleId;
		pendingCabSpec = cabSpec == null ? "" : cabSpec.trim();
		pendingUntilMillis = System.currentTimeMillis() + TIMEOUT_MILLIS;
		pendingAnnounced = false;
	}

	/** 每客户端 tick 一次（{@code MainRenderer} 的 GUI 钩子里，与别的交互同一处）。 */
	public static void tick() {
		if (pendingVehicleId == 0) {
			return;
		}
		if (System.currentTimeMillis() > pendingUntilMillis) {
			Init.LOGGER.warn("[MMTR-BOARD] 放弃上车：车 {} 在 {} ms 内没有出现在客户端镜像里（服务端落点没生效，或车已不存在）",
					pendingVehicleId, TIMEOUT_MILLIS);
			message("没等到列车出现在视野里 / vehicle never appeared");
			clear();
			return;
		}
		tryEnter();
	}

	private static void tryEnter() {
		final long vehicleId = pendingVehicleId;
		if (vehicleId == 0) {
			return;
		}
		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		if (player == null) {
			return;
		}
		// 两种"还进不去"必须分开：车还没同步过来（等一等就好）vs 这辆车的模型没有驾驶室锚点（等多久都不会好）。
		if (!MmtrInteractPrompt.isVehicleMirrored(vehicleId)) {
			if (!pendingAnnounced) {
				pendingAnnounced = true;
				Init.LOGGER.info("[MMTR-BOARD] 车 {} 还没进客户端镜像，重试中…", vehicleId);
				message("正在上车… / boarding");
			}
			return;
		}
		final MmtrInteractPrompt.CabTarget target = pick(vehicleId, pendingCabSpec);
		if (target == null) {
			Init.LOGGER.warn("[MMTR-BOARD] 车 {} 在镜像里但一个驾驶室锚点都没有（车型缺 mmtr_cabdoor？）", vehicleId);
			message("这辆车没有驾驶室锚点 / no cab anchors on this model");
			clear();
			return;
		}
		clear();
		MmtrCabInteraction.enterCab(player, target);
	}

	/**
	 * 从这辆车**全部**的驾驶室里挑一个：给了 {@code cabSpec} 就按它挑（它落不到实处就退回第一个，
	 * 而不是静默失败 —— 例如车型少了一个 {@code mmtr_cabdoor} 锚点时，"3A" 是敲不出来的）。
	 *
	 * <p>比的是**引擎端**（{@code target.engineEnd()}），不是锚点编号：编号是模型约定、
	 * 端是脊柱约定，BR101 上两者是反的（见 {@link MmtrVehicleAnchors#engineEndOfSeat}）。</p>
	 */
	@Nullable
	private static MmtrInteractPrompt.CabTarget pick(long vehicleId, String cabSpec) {
		final ObjectArrayList<MmtrInteractPrompt.CabTarget> targets = MmtrInteractPrompt.cabTargetsOf(vehicleId);
		if (targets.isEmpty()) {
			return null;
		}
		if (!cabSpec.isEmpty()) {
			final int carNumber = carNumberOf(cabSpec);
			final int engineEnd = engineEndOf(cabSpec);
			for (final MmtrInteractPrompt.CabTarget target : targets) {
				if (target.carNumber() == carNumber && target.engineEnd() == engineEnd) {
					return target;
				}
			}
			Init.LOGGER.warn("[MMTR-BOARD] 车 {} 没有驾驶室 {}（车节 {} + {} 端），改用第一个可用的",
					vehicleId, cabSpec, carNumber + 1, engineEnd == 2 ? "B" : "A");
			message("没有驾驶室 " + cabSpec + "，改用第一个 / cab not found, using the first");
		}
		return targets.get(0);
	}

	/** {@code "3B"} → 车节下标 {@code 2}（0 起）；解析不了返回 -1。 */
	private static int carNumberOf(String cabSpec) {
		final String digits = cabSpec.replaceAll("[^0-9]", "");
		try {
			return Integer.parseInt(digits) - 1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** {@code "3B"} → {@code 2}（引擎的 B 端）；缺省 A 端 = 1。 */
	private static int engineEndOf(String cabSpec) {
		return cabSpec.toUpperCase(java.util.Locale.ROOT).endsWith("B") ? 2 : 1;
	}

	private static void clear() {
		pendingVehicleId = 0;
		pendingCabSpec = "";
		pendingAnnounced = false;
	}

	private static void message(String text) {
		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		if (player != null) {
			player.sendMessage(new org.mtr.mapping.holder.Text(org.mtr.mapping.mapper.TextHelper.literal(text).data), true);
		}
	}
}
