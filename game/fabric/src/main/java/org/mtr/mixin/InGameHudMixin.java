package org.mtr.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import org.mtr.mod.client.MmtrVanillaHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 上车后收起**物品栏**（用户口径，notes/226）。
 *
 * <p>只掐 {@code renderHotbar}：血量、饥饿、经验条、准星、聊天都照旧 —— 用户说的是"物品栏"。
 * 要连整条 HUD 一起收，改的应该是 {@code GameOptions.hudHidden}（等同按 F1），不是这里。</p>
 *
 * <p>目标是 1.20.4 的私有方法 {@code renderHotbar(float, DrawContext)}（注意入参顺序是
 * 先 float 后 DrawContext）。描述符写错不会编译报错、只会让 mixin 在启动时应用失败，
 * 所以 {@code mmtr/scripts/check-mixin-targets.ps1} 会离线核对它真的存在。</p>
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

	@Inject(method = "renderHotbar(FLnet/minecraft/client/gui/DrawContext;)V", at = @At("HEAD"), cancellable = true)
	private void mmtrHideHotbarOnBoard(float tickDelta, DrawContext context, CallbackInfo callbackInfo) {
		if (MmtrVanillaHud.hideHotbarAndHand()) {
			callbackInfo.cancel();
		}
	}
}
