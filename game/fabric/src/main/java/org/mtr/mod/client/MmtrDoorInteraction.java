package org.mtr.mod.client;

import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.KeyBinding;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Text;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.InitClient;
import org.mtr.mod.KeyBindings;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.packet.PacketMmtrCabOp;
import org.mtr.mod.render.MmtrInteractPrompt;

/**
 * **司机的车门键**（用户口径 2026-09-21：「然后按键开门，等段时间，关门」）。
 *
 * <h2>为什么这一轮必须把它做出来</h2>
 *
 * <p>骑行层重建时（notes/185）MTR 自带的那套门键被删掉了，而 {@code PacketMmtrCabOp.Op.DOORS} 与引擎的
 * {@code doors <车辆id> toggle [<side>]} 一直都在 —— 也就是说"站台作业的子任务链"里的**开门/关门
 * 那两条子任务根本没有任何按键能达成**。子任务化把这件事暴露了出来：链要求"门真的开了"，
 * 而司机手里没有能开门的键。</p>
 *
 * <h2>两个键、两种口径</h2>
 *
 * <ul>
 *   <li><b>Y</b>：本列车**两侧门一起**开/关 —— 站台作业最常用的一下；</li>
 *   <li><b>U</b>：只动**右侧**门 —— 靠站台一侧（铁路上的日常做法，notes/50 的左/右分控）。</li>
 * </ul>
 *
 * <p>目标是"我在开的那列车"；没坐在车上时退回**瞄准的车门**（交互提示的那条 {@code [Y] 开门}），
 * 于是站在站台上也能开一列停着的车 —— 引擎侧那条指令本来就不要求驾驶权。</p>
 *
 * <h2>为什么不在这里判"该不该开"</h2>
 *
 * <p>引擎侧 {@code mmtrSetDoors} 会把门标成<b>手动</b>（覆盖 MTR 自己的站台邻近规则），
 * 并把结果写进指令日志。客户端只发意图 —— 与接管/确认同一个规矩：判据只有一份，在引擎里。</p>
 */
public final class MmtrDoorInteraction {

	private MmtrDoorInteraction() {
	}

	private static boolean lastPressedBoth;
	private static boolean lastPressedRight;

	/** 每客户端 tick 一次（{@code MainRenderer} 的 GUI 钩子里）。 */
	public static void tick() {
		final boolean pressedBoth = KeyBindings.MMTR_DOORS.isPressed();
		final boolean pressedRight = KeyBindings.MMTR_DOORS_SIDE.isPressed();
		final boolean justPressedBoth = pressedBoth && !lastPressedBoth;
		final boolean justPressedRight = pressedRight && !lastPressedRight;
		lastPressedBoth = pressedBoth;
		lastPressedRight = pressedRight;
		if (justPressedBoth) {
			toggleDoors("", "两侧");
		}
		if (justPressedRight) {
			toggleDoors("right", "右侧");
		}
	}

	private static void toggleDoors(String side, String label) {
		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		if (player == null) {
			return;
		}
		final VehicleExtension riding = MmtrDriverSeat.ridingVehicle();
		final long vehicleId = riding != null ? riding.getId() : MmtrInteractPrompt.aimedDoorVehicleId();
		if (vehicleId == 0) {
			player.sendMessage(new Text(TextHelper.literal("先上车或瞄准车门 / board a train or aim at a door first").data), true);
			return;
		}
		InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketMmtrCabOp(vehicleId, PacketMmtrCabOp.Op.DOORS, side));
		player.sendMessage(new Text(TextHelper.literal("车门（" + label + "）已切换… / doors toggled").data), true);
	}
}
