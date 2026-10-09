package org.mtr.mod.item;

import org.mtr.mapping.holder.Hand;
import org.mtr.mapping.holder.ItemSettings;
import org.mtr.mapping.holder.PlayerEntity;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.ItemExtension;
import org.mtr.mod.Init;
import org.mtr.mod.packet.PacketMmtrPdaScreen;

/**
 * **综合运转面板（PDA）** —— 玩家手里的那台东西（notes/408 §3）。
 *
 * <h2>它只做一件事：把界面打开</h2>
 * <p>界面上"哪趟车能上、哪个按钮能点"全部读引擎推过来的镜像（见 {@code MmtrDutyView}），
 * 物品本身不查任何业务数据 —— 所以这里**不需要**像 {@code ItemDashboard} 那样多走一次
 * 引擎往返（那次往返只是因为它要在服务端查"附近有没有车站/机务段"）。</p>
 *
 * <h2>为什么由服务端决定开屏</h2>
 * <p>{@code useWithoutResult} 在**两端都会跑**。客户端自己 {@code openScreen} 在多人游戏里
 * 会与服务端状态不一致（服务端并不知道你在看什么），所以照本仓库既有的约定：
 * <b>服务端判定 → {@code sendPacketToClient} → 客户端 {@code runClientInbound} 开屏</b>
 * （与 {@code ItemDashboard} 同一形状）。</p>
 */
public class ItemMmtrPda extends ItemExtension {

	public ItemMmtrPda(ItemSettings itemSettings) {
		super(itemSettings);
	}

	@Override
	public void useWithoutResult(World world, PlayerEntity user, Hand hand) {
		if (!world.isClient()) {
			/*
			 * 手里拿着 PDA 右键 = 打开"车次列表"那一页（不是驾驶页）。
			 *
			 * 驾驶页（时刻表 + 马上退出/下一站退出）走**驾驶中按 TAB**那条路，由客户端自己开
			 * （见 MmtrPdaInteraction）—— 因为那条路没有右键这个动作，而它要显示的东西
			 * （我在开哪趟车）本来就是客户端镜像里现成的。
			 */
			Init.REGISTRY.sendPacketToClient(ServerPlayerEntity.cast(user), new PacketMmtrPdaScreen(PacketMmtrPdaScreen.contentOf(false)));
		}
	}
}
