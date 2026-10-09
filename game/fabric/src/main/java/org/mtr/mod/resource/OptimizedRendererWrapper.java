package org.mtr.mod.resource;

import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.OptimizedRenderer;
import org.mtr.mapping.render.tool.GlStateTracker;
import org.mtr.mod.data.IGui;
import org.mtr.mod.render.MmtrGlStateGuard;
import org.mtr.mod.render.light.MmtrHeadlights;

import javax.annotation.Nullable;

public final class OptimizedRendererWrapper implements IGui {

	@Nullable
	private final OptimizedRenderer optimizedRenderer;

	/**
	 * 着色器"该重载了吗"。初值 {@code true} ⇒ 开机第一次建模型时编译一次；
	 * 之后由 {@link #markShadersDirty()}（资源重载）重新置位。
	 *
	 * <p>为什么需要这个标记：MTR 把"重编译着色器"绑在了每一次模型构建上，
	 * 而一次车辆重建会走 5 次 {@code beginReload()} ⇒ 同一批着色器被重编译 15 次，
	 * 实测让渲染线程卡 562 ms（notes/399）。建 VBO 其实只要 {@code GlStateTracker.capture()} 那一半。</p>
	 */
	private boolean shadersDirty = true;

	/** {@link #beginReload()} 是否由**我们**开了保护区 —— 决定 {@link #finishReload()} 要不要收尾。 */
	private boolean ownsProtection;

	/** {@link #beginReload()}/{@link #finishReload()} 的嵌套层数；只有最外层碰 GL 状态。 */
	private int protectionDepth;

	public OptimizedRendererWrapper() {
		this.optimizedRenderer = OptimizedRenderer.hasOptimizedRendering() ? new OptimizedRenderer() : null;
	}

	/**
	 * 着色器需要重编译 —— 由资源重载调用。
	 *
	 * <p>刻意做成**显式标记**而不是"距上次若干毫秒内去重"：时间窗猜错会漏掉一次真正的重载
	 * （资源包换了却还在用旧 program），而那是"画面不对"级别的问题。</p>
	 */
	public void markShadersDirty() {
		shadersDirty = true;
	}

	/**
	 * 把保护区的账本清零 —— 资源重载时调用（notes/399）。
	 *
	 * <p>防的是"某次模型构建半路抛异常、漏掉 {@code finishReload()}"把层级永久留高。
	 * 后果不是崩溃而是**静默**：之后每次 {@code beginReload()} 都以为自己在外层保护区里，
	 * 既不再重编译着色器、也不再 capture。清零之后，下一次真正该重载时走
	 * {@code optimizedRenderer.beginReload()}（它内部会 {@code capture()} 并最终 {@code restore()}），
	 * 顺手把漏掉的那层一起收掉。</p>
	 */
	public void resetProtectionState() {
		protectionDepth = 0;
		ownsProtection = false;
	}

	public void beginReload() {
		if (optimizedRenderer == null) {
			return;
		}
		/*
		 * 自己数嵌套层数。理由：GlStateTracker 是**一个布尔量不是计数**，
		 * 而建模型这条路会嵌套（VehicleResource 的链里 createModel / writeToOptimizedModels
		 * 各自成对调用）。只有**最外层**才该去开保护区、也只有它才该收尾；
		 * 内层什么都不做，否则会提前把保护区拆掉。
		 */
		if (protectionDepth == 0) {
			if (shadersDirty) {
				// 真的该重载：走 MTR 原来的整套（reloadShaders + capture）
				shadersDirty = false;
				optimizedRenderer.beginReload();
				ownsProtection = true;
			} else {
				// 同一波模型重建里的第 2..N 次：着色器刚编译过，只需要补上保护区
				ownsProtection = MmtrGlStateGuard.begin();
			}
		}
		protectionDepth++;
	}

	public void finishReload() {
		if (optimizedRenderer == null || protectionDepth <= 0) {
			// 不配对的调用：忽略，绝不去动 GL 状态
			return;
		}
		protectionDepth--;
		if (protectionDepth == 0 && ownsProtection) {
			// 两种来源都归一到 GlStateTracker.restore()（MTR 的 finishReload 也就是这一件事）
			GlStateTracker.restore();
			ownsProtection = false;
		}
	}

	public void queue(OptimizedModelWrapper optimizedModel, GraphicsHolder graphicsHolder, int light) {
		queue(optimizedModel, graphicsHolder, ARGB_WHITE, light);
	}

	/**
	 * 带**逐 draw 颜色**的版本（notes/374）：MMTR 用它给车灯灯罩染色。
	 *
	 * <p>那个 color 在 GL 里就是 {@code COLOR} 顶点属性，而 MTR 的顶点映射把 COLOR 定为
	 * {@code VertexAttributeSource.GLOBAL}（= 每个 draw 一个常量，见 {@code OptimizedModel.DEFAULT_MAPPING}）
	 * ⇒ 一次 draw 一个颜色。**打包口径是 RGBA（0xRRGGBBAA）**，见 {@code MmtrHeadlights.lensRgba}。</p>
	 */
	public void queue(OptimizedModelWrapper optimizedModel, GraphicsHolder graphicsHolder, int color, int light) {
		if (optimizedRenderer != null && optimizedModel.optimizedModel != null) {
			if (color != ARGB_WHITE) {
				// 诊断（notes/374）：带颜色的 draw 真的排进去了吗 —— 见 MmtrHeadlights.statColoredDraws。
				MmtrHeadlights.noteColoredDraw();
			}
			optimizedRenderer.queue(optimizedModel.optimizedModel, graphicsHolder, color, light);
		}
	}

	public void render(boolean renderTranslucent) {
		if (optimizedRenderer != null) {
			optimizedRenderer.render(renderTranslucent);
		}
	}
}
