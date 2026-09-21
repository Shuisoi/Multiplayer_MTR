package org.mtr.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import org.mtr.mod.client.MmtrVanillaHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 上车后收起**物品栏那一簇**（用户口径：「只把物品栏，物品栏上的信息，隐藏，聊天一定要保留」，notes/227）。
 *
 * <p>收起来的是四样，正好是原版里挨在一起的那一簇：</p>
 *
 * <table border="1">
 *   <caption>注入点与它负责画的东西</caption>
 *   <tr><th>方法</th><th>画的是什么</th></tr>
 *   <tr><td>{@code renderHotbar}</td><td>物品栏九格 + 副手格 + 选中框</td></tr>
 *   <tr><td>{@code renderStatusBars}</td><td>血量 / 饥饿 / 护甲 / 氧气泡</td></tr>
 *   <tr><td>{@code renderExperienceBar}</td><td>经验条 + 等级数字</td></tr>
 *   <tr><td>{@code renderHeldItemTooltip}</td><td>切换物品时在物品栏上方弹出的物品名</td></tr>
 * </table>
 *
 * <p><b>没碰的东西</b>（用户点名要保留聊天）：{@code ChatHud}（聊天）、准星、状态效果图标、
 * 计分板、F3、字幕。这里刻意**不用** {@code GameOptions.hudHidden}（F1）—— 那是整条 HUD，
 * 而用户说的是"只"。坐骑相关的 {@code renderMountHealth} / {@code renderMountJumpBar} 也没碰：
 * 那两样是骑马时才有，跟"物品栏那一簇"不是一回事，而且在列车上是不可达状态。</p>
 *
 * <p>这些方法名与描述符写错了**编译不会报**，只在启动应用 mixin 时炸，所以
 * {@code sandbox/run-mixin-probe.ps1} 会离线逐个核对它们真的存在（含描述符精确匹配），
 * 并且会额外断言"没有任何注入点是聊天"。</p>
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

	@Inject(method = "renderHotbar(FLnet/minecraft/client/gui/DrawContext;)V", at = @At("HEAD"), cancellable = true)
	private void mmtrHideHotbarOnBoard(float tickDelta, DrawContext context, CallbackInfo callbackInfo) {
		cancelIfRiding(callbackInfo);
	}

	@Inject(method = "renderStatusBars(Lnet/minecraft/client/gui/DrawContext;)V", at = @At("HEAD"), cancellable = true)
	private void mmtrHideStatusBarsOnBoard(DrawContext context, CallbackInfo callbackInfo) {
		cancelIfRiding(callbackInfo);
	}

	@Inject(method = "renderExperienceBar(Lnet/minecraft/client/gui/DrawContext;I)V", at = @At("HEAD"), cancellable = true)
	private void mmtrHideExperienceBarOnBoard(DrawContext context, int x, CallbackInfo callbackInfo) {
		cancelIfRiding(callbackInfo);
	}

	@Inject(method = "renderHeldItemTooltip(Lnet/minecraft/client/gui/DrawContext;)V", at = @At("HEAD"), cancellable = true)
	private void mmtrHideHeldItemTooltipOnBoard(DrawContext context, CallbackInfo callbackInfo) {
		cancelIfRiding(callbackInfo);
	}

	private static void cancelIfRiding(CallbackInfo callbackInfo) {
		if (MmtrVanillaHud.hideWhileRiding()) {
			callbackInfo.cancel();
		}
	}
}
