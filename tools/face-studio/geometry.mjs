/*
 * geometry.mjs —— 面文档里几处**纯几何**：装图方式、走马灯位置、翻牌机角度。
 *
 * 这是 MmtrFaceGeometry.java 的逐条移植。为什么单独一份：这几条是"作者会盯着看"的口径
 * （装图到底按宽还是按高、走马灯在周期两端各在哪、翻牌机在第 25% 处转到哪），
 * 都可以写成用例钉住，而画布那边（AWT / Canvas2D）钉不住。
 *
 * 共享向量在 conformance/geometry.json：Java 的 MmtrFaceGeometryTests 与工作室的 selftest.mjs
 * 跑**同一份**，谁漂了立刻红。
 *
 * 三条口径（都照抄 Java，别自己发明）：
 *   · fitRect 的 aspect 是**宽/高**，非正数（画不出来/还没加载）⇒ 当 stretch；
 *   · marqueeLeft 给的是文本**左边缘**的横坐标（不是中心）—— 画的时候用左对齐从那个 x 起画，
 *     元素的 align 在走马灯里**不参与**；
 *   · drumAngle 是"第 faceIndex 面相对正对读它的人转了多少度"（绕水平轴），
 *     一个换页周期里先用 turnFraction 从第 k 页翻到第 k+1 页，**其余时间停在 k+1**。
 */

/** 装图方式的名字（打包校验与工作室的下拉都读它）。 */
export const FITS = ['stretch', 'contain', 'cover'];

function clamp01(value) {
	return value < 0 ? 0 : (value > 1 ? 1 : value);
}

/**
 * 一张图装进 boxW × boxH 的框里，返回它**实际占的矩形** [x, y, w, h]
 * （x/y 是左下角，与 rect 同一条口径）。
 *
 *   · stretch —— 拉满整个框（会变形）；
 *   · contain —— 等比缩到**装得下**，居中，多出来的留白；
 *   · cover   —— 等比缩到**盖满**，居中，超出的部分由调用方裁掉（clip）。
 *
 * @param aspect 图的高宽比倒数（宽 / 高）；非正数 ⇒ 当 stretch
 */
export function fitRect(fit, boxX, boxY, boxW, boxH, aspect) {
	if (!(boxW > 0) || !(boxH > 0)) {
		return [boxX, boxY, Math.max(0, boxW), Math.max(0, boxH)];
	}
	const mode = String(fit === null || fit === undefined ? '' : fit).trim().toLowerCase();
	if (!(aspect > 0) || mode === 'stretch' || mode === '') {
		return [boxX, boxY, boxW, boxH];
	}
	let width = boxW;
	let height = width / aspect;
	if (mode === 'cover') {
		if (height < boxH) {
			height = boxH;
			width = height * aspect;
		}
	} else {
		// contain（以及任何没见过的名字：缩到装得下比溢出安全）
		if (height > boxH) {
			height = boxH;
			width = height * aspect;
		}
	}
	return [boxX + (boxW - width) / 2, boxY + (boxH - height) / 2, width, height];
}

/**
 * 走马灯里文本**左边缘**的横坐标（米）。
 *
 * 视口是 [left, left + viewportWidth]：phase = 0 时文本刚从视口右边进来
 * （左边缘在视口右缘），phase → 1 时整段已经走出左边（左边缘在 left - 文本宽 - gap）。
 * 于是"要滚多远" = 视口宽 + 文本宽 + 接缝 —— 接缝就是下一遍的起点与这一遍的尾巴之间的空当。
 *
 * @param phase 0..1（来自 anim.mjs 的 offsetFraction，方向已经算进去了）；越界会被钳住
 */
export function marqueeLeft(left, viewportWidth, textWidth, gapM, phase) {
	const travel = Math.max(0, viewportWidth) + Math.max(0, textWidth) + Math.max(0, gapM);
	return left + Math.max(0, viewportWidth) - clamp01(phase) * travel;
}

/**
 * 翻牌机：第 faceIndex 面相对"正对读它的人"转了多少度（绕水平轴）。
 *
 * ★ 翻转窗口在**周期末尾**（2026-10-02 与 Java 的 MmtrFaceGeometry 同步改）：
 *   一个换页周期里**前 (1 - turnFraction) 停住**（正面 = pageIndex 那一面，0°），
 *   **最后 turnFraction 翻过去**（缓入缓出 —— 现实里的翻牌牌就是"啪"地翻过去然后歇着，
 *   一直匀速转反而像风车）。clockFraction = 1 时正好翻到下一页的位置，下一个周期从这里接上。
 *
 * 为什么不能把窗口放在周期开头（旧式，已废）：那样 `progress = 1` 会让正面变成 pageIndex+1，
 * 而 `pageExpr` 指定页时调用方只能给 clockFraction = 1（"停稳"）⇒ 指定页的水牌永远显示下一页；
 * 而且它比"不用翻牌机的多页"（周期末尾换页）早四分之一周期换页 —— 同一份文档加不加 drum，
 * 换页时刻不该变。
 *
 * @param count 棱柱面数（≥ 2）
 */
export function drumAngle(faceIndex, pageIndex, clockFraction, turnFraction, count) {
	const step = 360 / Math.max(2, count);
	const window = turnFraction <= 0 ? 1 : Math.min(1, turnFraction);
	const remaining = Math.max(0, 1 - window);
	const fraction = clamp01(clockFraction);
	const progress = fraction <= remaining ? 0 : smoothStep((fraction - remaining) / window);
	return (faceIndex - pageIndex - progress) * step;
}

/** 缓入缓出（0→0、1→1、S 形）；翻牌机用它，免得"啪"的那一下太生硬。 */
export function smoothStep(value) {
	const x = clamp01(value);
	return x * x * (3 - 2 * x);
}

/** 正 N 面棱柱的外接半径（面宽 widthM 时要多大才能让正对的那一面正好是 widthM 宽）。 */
export function prismRadius(widthM, count) {
	const faces = Math.max(2, count);
	return widthM / (2 * Math.tan(Math.PI / faces));
}
