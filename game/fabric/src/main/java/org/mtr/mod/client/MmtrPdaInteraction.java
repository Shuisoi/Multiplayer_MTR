package org.mtr.mod.client;

import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mod.KeyBindings;
import org.mtr.mod.Items;
import org.mtr.mod.packet.ClientPacketHelper;

/**
 * **驾驶中按 TAB 调出综合运转面板**（notes/408 §3.3）。
 *
 * <h2>为什么不无条件抢 TAB</h2>
 * <p>TAB 在本 mod 里原本是空的（全仓库零命中），但它同时是**原版的"按住看玩家列表"**。
 * 所以这里的判据是"手里拿着 PDA，或者我在驾驶室里"才开面板；不满足时**什么都不做**，
 * 把 TAB 让给原版 —— 站在站台上按 TAB 就该看到玩家列表，而不是弹出驾驶面板。</p>
 *
 * <p>另外：坐在司机位上时，本 mod 的 {@code MmtrInputDecouple} 会把非 {@code key.mmtr.*}
 * 的绑定从原版动作上摘掉（notes/407 的现场），而新键名恰好是 {@code key.mmtr.pda} ——
 * 于是"驾驶中 TAB"不会同时翻出玩家列表，这正是我们要的效果。</p>
 */
public final class MmtrPdaInteraction {

	private MmtrPdaInteraction() {
	}

	private static boolean lastPressed;

	/** 每客户端 tick 一次（{@code MainRenderer} 的 GUI 钩子里、且只在没有别的界面开着时）。 */
	public static void tick() {
		final boolean pressed = KeyBindings.MMTR_PDA.isPressed();
		final boolean justPressed = pressed && !lastPressed;
		lastPressed = pressed;
		if (!justPressed) {
			return;
		}
		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		if (player == null) {
			return;
		}
		final boolean holdingPda = player.isHolding(Items.PDA.get());
		final boolean inCab = MmtrDriverSeat.current() != null || VehicleRidingMovement.mmtrCabVehicleId() != 0;
		if (!holdingPda && !inCab) {
			return;
		}
		// 手里拿着 PDA 站在站台上 → 列表页；坐在驾驶室里 → 直接翻到驾驶页（用户口径"驾驶中按 TAB"）。
		ClientPacketHelper.openPdaScreen(inCab);
	}
}
