package org.mtr.mod.client;

import org.mtr.core.operation.DataRequest;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Text;
import org.mtr.mapping.mapper.MinecraftClientHelper;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.Init;
import org.mtr.mod.InitClient;
import org.mtr.mod.KeyBindings;
import org.mtr.mod.packet.PacketRequestData;

/**
 * 手动重拉引擎数据（按键 H）——"铺完轨不用重连就能看见"。
 *
 * <h3>为什么需要它（实测的根因）</h3>
 * <p>客户端的轨道渲染走 {@code MinecraftClientData.railWrapperList}，而它<b>只在收到
 * {@code DataResponse} 时才更新</b>。问题是客户端拉数据的时机很窄：</p>
 *
 * <pre>
 *   InitClient（客户端 tick）：
 *     仅当 lastUpdatePacketMillis &gt; 0 且已到期时，才发 PacketRequestData 拉附近新数据；
 *     而 lastUpdatePacketMillis 只由 registerChunkLoad（区块加载）置位。
 * </pre>
 *
 * <p>于是：<b>站着不动 → 没有新区块加载 → 永远不发拉取请求</b>。此时从外部新建的轨道
 * （引擎侧确实已经有了，服务端也把方块放好了）客户端就是看不见，直到重连 ——
 * 重连走的是全量 {@code DataResponse}，所以"一重连就有了"。</p>
 *
 * <p>实测确认过：服务端的广播链路本身没问题（{@code PacketUpdateData} 的
 * {@code ResponseType.ALL} 会遍历玩家发包），是客户端<b>没主动拉</b>。所以这里不给
 * 服务端加任何东西，只补一个"我现在就要拉一次"的入口。</p>
 *
 * <h3>实现</h3>
 * <p>复用客户端既有的那条路（与 {@code InitClient} 里每 0.5 秒那次完全同一个包），
 * 所以拿到的数据形状、落地方式（{@code DataResponse.write()} → {@code data.sync()}）
 * 都与常规同步一致，不引入第二条数据通道。</p>
 *
 * <p>{@code writeExistingIds} 会把客户端已有的 id 带上，所以这次拉取是<b>增量的</b>：
 * 新增的轨道会被带回来，已有的不会被重复覆盖。</p>
 */
public final class MmtrDataResync {

	private static boolean lastPressed = false;

	private MmtrDataResync() {
	}

	public static void tick() {
		// isPressed() + 自记边沿：与 MmtrTaskInteraction / MmtrDoorInteraction 同一写法
		// （映射层没有 wasPressed 的等价物）。
		final boolean pressed = KeyBindings.MMTR_RESYNC.isPressed();
		final boolean justPressed = pressed && !lastPressed;
		lastPressed = pressed;
		if (!justPressed) {
			return;
		}

		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		final ClientPlayerEntity player = minecraftClient.getPlayerMapped();
		if (player == null) {
			return;
		}

		final int before = MinecraftClientData.getInstance().rails.size();

		// 与 InitClient 那次拉取逐字段一致：玩家 uuid + 相机所在方块 + 渲染距离换算出的半径。
		final DataRequest dataRequest = new DataRequest(
			player.getUuid(),
			Init.blockPosToPosition(minecraftClient.getGameRendererMapped().getCamera().getBlockPos()),
			MinecraftClientHelper.getRenderDistance() * 16L
		);
		dataRequest.writeExistingIds(MinecraftClientData.getInstance());
		InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketRequestData(dataRequest));

		player.sendMessage(new Text(TextHelper.literal(
			"已向引擎重拉数据（当前本地轨 " + before + " 条；新铺的轨应立刻出现）/ resync requested"
		).data), true);
	}
}
