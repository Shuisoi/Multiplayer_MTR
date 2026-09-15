/**
 * 轨道线**画出来**的几何（web 自己那一套），以及"一根轨上某一段弧窗"的同一套几何。
 *
 * <h3>为什么必须抽到这里</h3>
 * <p>用户 2026-09-15 的现场问题："显示的区间和绘制的路线图不一样" —— 网页画的轨是一条
 * **简化曲线**（`railCurvePath`，端点切线取自引擎采样的真实轨道），而区间带原先是拿**引擎在弧窗里
 * 另采的点**直线连起来的。两套算法不同（曲线 vs 折线、取点位置也不同），所以同一条轨在屏幕上出现两条
 * 不重合的线。用户的要求是："既然都是节点划分区间，那么区间图也使用 web 绘制的图像"。</p>
 *
 * <p>所以：**轨道怎么画，区间就怎么截**。`railScreenPath` 画整根轨，`railSpanPath` 画这根轨上的一段弧窗，
 * 两者走**同一个** `railCurvePath`、用同一份 `straightAtNode` —— 一条轨与它上面的区间带必然重合，
 * 因为它们本来就是同一条线的两段。</p>
 */

import {worldToScreen, type Camera} from "@/domain/camera";
import {linePath, railCurvePath, type PlanePoint} from "@/domain/railGeometry";
import type {Rail} from "@/domain/Rail";

/** 一根轨的两个端点键（引擎的节点键就是 `x,y,z`）。 */
export function railNodeKeys(rail: Rail): [string, string] {
	return [`${rail.x1},${rail.y1},${rail.z1}`, `${rail.x2},${rail.y2},${rail.z2}`];
}

/**
 * 节点 → 该节点上"直线轨"的世界平面方向。
 *
 * <p>实现用户的要求"曲线末端的切线要和上一段直线共线"：曲线接直线的那一端直接取这条直线的方向，
 * 于是共线是**构造出来的**而不是估出来的。只有轴对齐的轨参与；曲线轨的端点切向由它自己的采样点估。</p>
 *
 * <p>同一节点上可能有多条直线轨（岔口），记第一条即可 —— `railCurvePath` 内部会按本轨流向来对齐方向。</p>
 */
export function buildStraightDirections(rails: readonly Rail[]): Map<string, PlanePoint> {
	const map = new Map<string, PlanePoint>();
	for (const rail of rails) {
		if (!rail.isAxisAligned) {
			continue;
		}
		const dx = rail.planeX2 - rail.planeX1;
		const dy = rail.planeY2 - rail.planeY1;
		const length = Math.hypot(dx, dy);
		if (!(length > 1e-9)) {
			continue;
		}
		const direction = {x: dx / length, y: dy / length};
		const [startKey, endKey] = railNodeKeys(rail);
		map.set(startKey, direction);
		if (!map.has(endKey)) {
			map.set(endKey, direction);
		}
	}
	return map;
}

/** 该节点上的直线轨方向，没有则 null（`railCurvePath` 的查表回调）。 */
export type StraightLookup = (nodeKey: string) => PlanePoint | null;

/** 把"节点 → 直线方向"的表包成 `railCurvePath` 要的回调。 */
export function straightLookup(directions: ReadonlyMap<string, PlanePoint>): StraightLookup {
	return nodeKey => directions.get(nodeKey) ?? null;
}

/**
 * 一根轨**整根**画出来的 SVG 路径（屏幕坐标）。
 *
 * <p>线型规则（用户要求）：同一轴（x 或 z 相同）→ 两端点直线；斜向 → 简化曲线，端点切线取自引擎采样的
 * 真实轨道。所有阈值都在**世界平面**里判，不随缩放变（见 `railGeometry.railCurvePath` 的说明）。</p>
 */
export function railScreenPath(rail: Rail, camera: Camera, straight: StraightLookup): string {
	const from = {x: rail.planeX1, y: rail.planeY1};
	const to = {x: rail.planeX2, y: rail.planeY2};
	if (rail.isAxisAligned) {
		return linePath(worldToScreen(camera, from.x, from.y), worldToScreen(camera, to.x, to.y));
	}
	return curvePath(rail, from, to, rail.path.map(point => ({x: point.x, y: point.z})), camera, straight);
}

/**
 * 一根轨上**一段弧窗**画出来的 SVG 路径（屏幕坐标）。
 *
 * <p>做法：把引擎给的轨采样点按弧长**切**到 `[arcFrom, arcTo]`（两端补精确端点），再把这段喂给
 * **同一个** `railCurvePath` —— 所以它与整根轨那条线在重叠处必然重合。</p>
 *
 * <p>弧长 ↔ 采样点的换算：引擎的采样点沿轨**等弧长**布（实测 32 步/根），所以第 i 个点对应的弧长是
 * `i / (n-1) × 轨长`。区间给的 `from`/`to` 就是沿轨弧长，按比例取下标即可。
 * 端点落在两个采样点之间时**线性插值**补出来，免得带子比轨短一截。</p>
 */
export function railSpanPath(rail: Rail, arcFrom: number, arcTo: number, camera: Camera, straight: StraightLookup): string {
	const samples = rail.path.map(point => ({x: point.x, y: point.z}));
	const project = (point: PlanePoint) => worldToScreen(camera, point.x, point.y);
	if (samples.length < 2) {
		// 引擎没给形状：退回两端点直线（与轨道层的兜底一致）
		return linePath(project(interpolateEndpoints(rail, arcFrom)), project(interpolateEndpoints(rail, arcTo)));
	}
	const total = railLength(rail, samples);
	const low = Math.min(arcFrom, arcTo);
	const high = Math.max(arcFrom, arcTo);
	const inner: PlanePoint[] = [];
	const lastIndex = samples.length - 1;
	for (let i = 1; i < lastIndex; i++) {
		// 采样点等弧长 ⇒ 第 i 个点的弧长 = i / lastIndex × 总长
		const arc = (i / lastIndex) * total;
		if (arc > low + 1e-6 && arc < high - 1e-6) {
			inner.push(samples[i]!);
		}
	}
	// 沿弧增走时按原序，沿弧减走时反过来 —— 切线方向要跟走行方向一致（与轨道层"按本轨流向来对齐"同一口径）
	const ordered = arcTo >= arcFrom ? inner : [...inner].reverse();
	const from = sampleAtArc(samples, total, arcFrom);
	const to = sampleAtArc(samples, total, arcTo);
	return curvePath(rail, from, to, [from, ...ordered, to], camera, straight);
}

/**
 * 弧长 → 平面坐标：在采样点之间线性插值。
 *
 * <p>采样点等弧长，所以 `arc / total × lastIndex` 就是要找的下标位置；取整得下标、取小数得插值系数。</p>
 */
function sampleAtArc(samples: readonly PlanePoint[], total: number, arcM: number): PlanePoint {
	const lastIndex = samples.length - 1;
	const position = total <= 1e-9 ? 0 : (arcM / total) * lastIndex;
	const index = Math.min(lastIndex, Math.max(0, Math.floor(position)));
	const a = samples[index]!;
	const b = samples[Math.min(lastIndex, index + 1)]!;
	const t = Math.min(1, Math.max(0, position - index));
	return {x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t};
}

/** 没有采样点时：按弧长在两端点之间线性插值（轨是弧，这里只是兜底）。 */
function interpolateEndpoints(rail: Rail, arcM: number): PlanePoint {
	const a = {x: rail.planeX1, y: rail.planeY1};
	const b = {x: rail.planeX2, y: rail.planeY2};
	const total = Math.hypot(b.x - a.x, b.y - a.y);
	const t = total <= 1e-9 ? 0 : Math.min(1, Math.max(0, arcM / total));
	return {x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t};
}

/** 采样折线的总长（世界平面米）：用于把下标换算成弧长。 */
function railLength(rail: Rail, samples: readonly PlanePoint[]): number {
	let total = 0;
	for (let i = 0; i + 1 < samples.length; i++) {
		const a = samples[i]!;
		const b = samples[i + 1]!;
		total += Math.hypot(b.x - a.x, b.y - a.y);
	}
	return total > 1e-9 ? total : Math.hypot(rail.planeX2 - rail.planeX1, rail.planeY2 - rail.planeY1);
}

/** 共用的曲线构造：与轨道层完全同一条实现。 */
function curvePath(rail: Rail, from: PlanePoint, to: PlanePoint, points: readonly PlanePoint[], camera: Camera, straight: StraightLookup): string {
	return railCurvePath(
		from,
		to,
		points,
		point => worldToScreen(camera, point.x, point.y),
		straight,
		railNodeKeys(rail),
	);
}
