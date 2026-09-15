/**
 * 方向带的几何（纯函数，便于单测）。
 *
 * <p>方案 B 的画法：把同一个行车方向的区间沿轨的**法向**偏移一段固定距离，于是同一根轨上两个方向
 * 各占一条并排的带，每条带再被它自己方向的灯切成段（见 `components/SectionLayer.vue`）。</p>
 *
 * <p><b>为什么偏移在屏幕坐标里做</b>：偏移量的意义是"视觉上把两条带分开"，那是屏幕尺度的事；
 * 在世界尺度上偏移会让缩放时两条带的间距忽宽忽窄（整图比例可能只有 ~0.1 px/世界单位）。</p>
 */

/** 平面上的一个点（屏幕坐标）。 */
export interface ScreenPoint {
	readonly x: number;
	readonly y: number;
}

/**
 * 方向角 → 带号（−1 或 +1）。
 *
 * <p>按四个正方向定，非正方向取最近的 90°。要求只有一条：**相对的两个方向落在相反的侧**
 * （南北相反、东西相反）—— 那正是双向运行的情形。为什么按方向而不是按"第几条"编号：
 * 同一根轨上同方向可能有多个区间（现场实测最多 5 个），按出现顺序编号会把它们分到两条带上，
 * 看图的人会误以为那是两个方向。</p>
 */
export function sideOfDirection(angle: number): number {
	const normalized = ((angle % 360) + 360) % 360;
	const quadrant = (Math.round(normalized / 90) * 90) % 360;
	return quadrant === 0 || quadrant === 270 ? 1 : -1;
}

/**
 * 折线 → 逐点法向偏移后的 SVG 路径。
 *
 * <p>切向取**前后两点的差分**（而不是相邻两点），折线在拐角处两侧才不外翻；端点只有单侧邻居，
 * 所以用自身代替另一侧。</p>
 *
 * @param points 屏幕坐标折线（至少两点；少于两点返回空串 = 不画）
 * @param offset 法向偏移量（像素，带符号）
 */
export function offsetPath(points: readonly ScreenPoint[], offset: number): string {
	if (points.length < 2) {
		return "";
	}
	const parts: string[] = [];
	for (let i = 0; i < points.length; i++) {
		const previous = points[Math.max(0, i - 1)]!;
		const next = points[Math.min(points.length - 1, i + 1)]!;
		const dx = next.x - previous.x;
		const dy = next.y - previous.y;
		const length = Math.hypot(dx, dy);
		if (length < 1e-6) {
			parts.push(`${i === 0 ? "M" : "L"} ${points[i]!.x} ${points[i]!.y}`);
			continue;
		}
		// 平面法向 = 切向转 90°
		const nx = -dy / length;
		const ny = dx / length;
		parts.push(`${i === 0 ? "M" : "L"} ${points[i]!.x + nx * offset} ${points[i]!.y + ny * offset}`);
	}
	return parts.join(" ");
}
