package org.mtr.mod.resource;

import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.OptimizedRenderer;
import org.mtr.mod.data.IGui;
import org.mtr.mod.render.light.MmtrHeadlights;

import javax.annotation.Nullable;

public final class OptimizedRendererWrapper implements IGui {

	@Nullable
	private final OptimizedRenderer optimizedRenderer;

	public OptimizedRendererWrapper() {
		this.optimizedRenderer = OptimizedRenderer.hasOptimizedRendering() ? new OptimizedRenderer() : null;
	}

	public void beginReload() {
		if (optimizedRenderer != null) {
			optimizedRenderer.beginReload();
		}
	}

	public void finishReload() {
		if (optimizedRenderer != null) {
			optimizedRenderer.finishReload();
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
