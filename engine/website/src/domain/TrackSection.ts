/*
 * L1 **轨道区间**（`/mtr/api/map/mmtr-track-sections`）的数据形状与"区间切点"。
 *
 * <h2>引擎给的是什么</h2>
 * <p>一条轨道区间 = 一段**连续可占用**的轨，由若干 span 拼成；每个 span 是"某根轨上从
 * `from` 到 `to`（沿轨里程，米）的一截"，带一串**沿轨采样点**。切点只由灯产生、**无方向**、
 * 双向共用 —— 占用判定就在这一层（一根轨就是一根轨）。</p>
 *
 * <h2>两个会踩的点</h2>
 * <ol>
 *   <li>**`points` 是扁平数组**：`[x0,z0,x1,z1,…]`，不是 `[[x,z],…]`（实测 18 个数 = 9 个点）。
 *       直接当点数组用会把数量看错一倍。</li>
 *   <li>**坐标是方块中心**（`1.5, 6.5…`），与拓扑 `path` 的原始采样同一套 —— 实测 span 的点
 *       落在同 hex 轨的原始 path 上，最大偏离 **0 格**。所以这一页画轨**不要**做"对齐到节点"
 *       那一步（那是"地图"页为了让线穿过节点圆点才做的）：两边都用引擎原始形状，区间的点
 *       自然落在线上。</li>
 * </ol>
 */

/** 引擎原始的一段（接口形状，改动时要与 `SystemMapServlet.getMmtrTrackSections` 同步）。 */
export interface RawTrackSpan {
	readonly hex: string;
	readonly from: number;
	readonly to: number;
	/** **扁平**的采样点：`[x0,z0,x1,z1,…]`。 */
	readonly points?: readonly number[];
}

/** 引擎原始的一条轨道区间。 */
export interface RawTrackSection {
	readonly id: string;
	readonly length: number;
	readonly occupied?: boolean;
	readonly spans?: readonly RawTrackSpan[];
}

/** 一段（界面用的形状）：采样点已经拆成 `[x, z]` 对。 */
export interface TrackSpan {
	readonly railHex: string;
	readonly fromM: number;
	readonly toM: number;
	readonly points: readonly (readonly [number, number])[];
}

/** 一条轨道区间（界面用的形状）。 */
export interface TrackSection {
	readonly id: string;
	readonly lengthM: number;
	readonly occupied: boolean;
	readonly spans: readonly TrackSpan[];
}

/** 一个区间切点（世界方块坐标；**方块中心**那一套，与轨的采样点同源）。 */
export interface SectionCutPoint {
	/** 稳定键 `x,z` —— 用它去重、也用它当列表 key。 */
	readonly key: string;
	readonly x: number;
	readonly z: number;
}

/** 把扁平的 `[x0,z0,x1,z1,…]` 拆成 `[x,z]` 对；奇数个（数据坏了）时丢掉最后一个。 */
export function parseFlatPoints(flat: readonly number[] | undefined): (readonly [number, number])[] {
	const points: (readonly [number, number])[] = [];
	if (!flat) {
		return points;
	}
	for (let i = 0; i + 1 < flat.length; i += 2) {
		points.push([flat[i]!, flat[i + 1]!]);
	}
	return points;
}

/** 解析接口给的一批轨道区间。 */
export function parseTrackSections(raw: readonly RawTrackSection[]): TrackSection[] {
	return raw.map(section => ({
		id: section.id,
		lengthM: section.length,
		occupied: section.occupied === true,
		spans: (section.spans ?? []).map(span => ({
			railHex: span.hex,
			fromM: span.from,
			toM: span.to,
			points: parseFlatPoints(span.points),
		})),
	}));
}

/**
 * **区间切点**：每条区间、每个 span 的两端，去重后就是这张图的"区间节点"。
 *
 * <p>引擎说切点只由灯产生；实测这张世界 156 个切点里 **155 个正好落在轨道节点上**
 * （方块中心），只有 1 个是轨中段的灯切点 —— 所以它和"地图"页的节点圆点看着几乎一样，
 * 区别只在**位置（方块中心）**和**多出来的那一个**。</p>
 *
 * <p>顺序稳定（按首次出现的次序），这样图层可以按下标或 key 稳定渲染。</p>
 */
export function sectionCutPoints(sections: readonly TrackSection[]): SectionCutPoint[] {
	const seen = new Set<string>();
	const cutPoints: SectionCutPoint[] = [];
	for (const section of sections) {
		for (const span of section.spans) {
			if (span.points.length === 0) {
				continue;
			}
			for (const point of [span.points[0]!, span.points[span.points.length - 1]!]) {
				const key = `${point[0]},${point[1]}`;
				if (!seen.has(key)) {
					seen.add(key);
					cutPoints.push({key, x: point[0], z: point[1]});
				}
			}
		}
	}
	return cutPoints;
}
