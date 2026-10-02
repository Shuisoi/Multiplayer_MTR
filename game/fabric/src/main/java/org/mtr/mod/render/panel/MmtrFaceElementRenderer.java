package org.mtr.mod.render.panel;

import org.mtr.mod.mmtr.face.MmtrFaceDocument;

import javax.annotation.Nullable;

/**
 * 一种元素怎么画（notes/361 · F4 的 SPI 之一）。
 *
 * <p>名字从 F0/F3 的 {@code MmtrFaceElements.Painter} 改成了这个正式名字：它现在是**对外**的类型
 * （附属模组实现它），所以不能叫"内部那个 Painter"。签名一个字没变。</p>
 */
@FunctionalInterface
public interface MmtrFaceElementRenderer {

	/**
	 * 画这一个元素。
	 *
	 * @param canvas   画布（米制：{@code x}/{@code w} 乘牌宽，{@code y}/{@code h}/{@code size} 乘牌高；
	 *                 {@code (0,0)} 是**左下角**，y 朝上）
	 * @param document 这块面的文档（要文档级的 {@code textColor} 之类就问它）
	 * @param element  这个元素（几何与文本在通用字段上；类型专属的键用
	 *                 {@code element.number/string/flag} 读 —— 它们会先查键表 {@code MmtrFaceSchema}）
	 * @param paint    这一次画的上下文：数据（{@code forEach} 已经绑好的那一份）、透明度、时钟、动画
	 */
	void paint(MmtrPanelCanvas canvas, MmtrFaceDocument document, MmtrFaceDocument.Element element, MmtrFaceElements.Paint paint);
}
