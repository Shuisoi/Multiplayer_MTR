package org.mtr.mod.render.light;

import org.lwjgl.opengl.GL33;
import org.mtr.mapping.render.vertex.VertexAttributeState;
import org.mtr.mapping.render.vertex.VertexAttributeType;

/**
 * 逐 draw 的颜色（{@code OptimizedRenderer.queue(..., color, light)} 的那一个）**能被看见**的那一步。
 *
 * <h3>为什么需要这个类（2026-10-03，notes/374）</h3>
 *
 * <p>MTR 的优化渲染器一次 draw 会 apply **两份**顶点属性状态（字节码：{@code BatchManager$RenderCall.draw()}）：</p>
 *
 * <ol>
 *   <li>本次 draw 自己的那份（{@code OptimizedRenderer.queue} 里 new 出来的
 *       {@code VertexAttributeState(color, light, ModelMat)}）—— **我们的灯罩颜色就在这一份里**；</li>
 *   <li><b>材质那一份</b>（{@code MaterialProperties.vertexAttributeState}）—— 它在**后面** apply。</li>
 * </ol>
 *
 * <p>而材质那份的颜色**永远不是 null**：{@code ObjModelLoader} 从 MTL 读颜色，
 * {@code Kd} 缺失时也照样走 {@code mergeColor(255,255,255,255)} 填白（本包的 MTL 没有 Kd 行，
 * 所以就是纯白）⇒ 第二份 apply 把我们辛苦算出来的灯罩颜色**原样盖成白色**。
 * 这就是 2026-10-03 现场"数据全对、日志全绿、尾灯就是不红"的全部原因：
 * 颜色到了 GL，只是随后被材质的白盖掉了。</p>
 *
 * <p>所以这一步很简单：材质那份 apply 完，**把我们那份颜色再写回去**。
 * 只在"我们真的要求过一个非白颜色"时动手（{@code color != 0xFFFFFFFF}），
 * 于是别的车底/别的资源包靠 MTL {@code Kd} 做染色的老行为一点不受影响。</p>
 *
 * <p>打包口径是 <b>RGBA（0xRRGGBBAA）</b>：{@code VertexAttributeState.apply()} 与
 * {@code ObjModelLoader.mergeColor} 都按 {@code (c>>24, c>>16, c>>8, c&255)} 取四个分量
 * （javap 读出来的，见 {@link MmtrHeadlights} 的 {@code lensRgba}）。</p>
 */
public final class MmtrDrawColor {

	/** 材质颜色（{@code Kd}）默认就是纯白：等于"没染色"，这时什么都不用做。 */
	private static final int WHITE = 0xFFFFFFFF;

	private MmtrDrawColor() {
	}

	/**
	 * 材质那份 {@code VertexAttributeState} 生效之后，把**本次 draw 自己的颜色**重新写回 GL。
	 *
	 * @param drawState 本次 draw 的状态（{@code BatchManager$RenderCall.vertexAttributeState}）；
	 *                  {@code color} 为 null（除了光/矩阵没设颜色）或纯白时什么都不做
	 */
	public static void reapplyIfTinted(VertexAttributeState drawState) {
		if (drawState == null) {
			return;
		}
		final Integer color = drawState.color;
		if (color == null || color == WHITE) {
			return;
		}
		GL33.glVertexAttrib4f(
				VertexAttributeType.COLOR.location,
				((color >> 24) & 0xFF) / 255.0F,
				((color >> 16) & 0xFF) / 255.0F,
				((color >> 8) & 0xFF) / 255.0F,
				(color & 0xFF) / 255.0F
		);
	}
}
