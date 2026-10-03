package org.mtr.mod.render.light;

import org.mtr.mod.Init;

/**
 * 优化渲染器的 draw / 批次计数 —— 只为验证"光场不增加 draw call"。
 *
 * <p>计数点（都在 mapping 库里，用 mixin 挂上，见 {@code BatchManagerRenderCallMixin} 与 {@code ShaderManagerMixin}）：</p>
 * <ul>
 *   <li>{@code BatchManager$RenderCall#draw()} —— 每个 RenderCall 就是一次 {@code glDrawElements}，
 *       所以这个数就是**这一帧优化器真正发出的 draw 数**。</li>
 *   <li>{@code ShaderManager#setupShaderBatchState()} —— 每个批次调用一次，即**批次（材质组）数**。</li>
 * </ul>
 *
 * <p>光场的全部改动都在数据与着色器侧：同一个 RenderCall 仍然只调一次 draw，
 * 光值改由片元着色器按世界坐标查表 ⇒ 这两个数应当与开光场之前完全一致（notes/340 的基线：
 * 10 节编组、车辆 draws 152、总 draws ~1340–1680、帧 ≈ 8.5 ms）。</p>
 */
public final class MmtrOptimizerStats {

	private static int drawCalls;
	private static int batches;

	private MmtrOptimizerStats() {
	}

	public static void onDrawCall() {
		drawCalls++;
	}

	public static void onBatch() {
		batches++;
	}

	/** 每 5 秒把累计值摊成"每帧"打一行，然后清零。 */
	public static void logAndReset(int frames) {
		final float divisor = Math.max(1, frames);
		Init.LOGGER.info("[MMTR-LIGHT] optimizer draws/frame={} batches/frame={} (窗口 {} 帧)",
				String.format("%.1f", drawCalls / divisor), String.format("%.2f", batches / divisor), frames);
		drawCalls = 0;
		batches = 0;
	}
}
