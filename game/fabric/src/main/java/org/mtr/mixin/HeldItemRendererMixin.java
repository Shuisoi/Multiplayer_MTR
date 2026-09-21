package org.mtr.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.mtr.mod.client.MmtrVanillaHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 上车后收起**第一人称的手/物品**（用户口径，notes/226/227）。
 *
 * <p><b>★ 描述符必须精确到这一个重载</b>：{@code HeldItemRenderer} 有两个 {@code renderItem}，
 * 另一个 {@code renderItem(LivingEntity, ItemStack, ModelTransformationMode, boolean, MatrixStack,
 * VertexConsumerProvider, int)} 是**任何实体**手里拿的东西（含别的玩家）。掐错了就是把所有玩家
 * 手里的物品都抹掉。这里只掐 {@code (float, MatrixStack, VertexConsumerProvider$Immediate,
 * ClientPlayerEntity, int)} —— 那是 {@code GameRenderer} 画自己第一人称手时调的那一个。</p>
 *
 * <p>第三者视角下本地玩家的手持物是走实体渲染的，不受这里影响；
 * {@code client.options.hudHidden}（F1）走的是另一条路，也不受影响。</p>
 */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {

	@Inject(
		method = "renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;Lnet/minecraft/client/network/ClientPlayerEntity;I)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void mmtrHideFirstPersonItemOnBoard(float tickDelta, MatrixStack matrices, VertexConsumerProvider.Immediate vertexConsumers, ClientPlayerEntity player, int light, CallbackInfo callbackInfo) {
		if (MmtrVanillaHud.hideWhileRiding()) {
			callbackInfo.cancel();
		}
	}
}
