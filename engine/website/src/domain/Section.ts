/**
 * 区间实体（区间层）：**按方向划分的一段路**。
 *
 * <p>数据来自引擎的 `/mtr/api/map/mmtr-sections`（notes/156）。三件事必须先说清楚，否则这个文件
 * 很容易被写回旧样子：</p>
 *
 * <ol>
 *   <li><b>区间不是"一段轨"</b>，而是**一盏灯开的那段路**：它从入口灯开始，跨过轨的接头，
 *       走到下一盏**面朝同一方向**的灯为止。所以一个区间会跨好几根轨，而一根轨会被好几个区间用到。</li>
 *   <li><b>区间属于一个行车方向</b>（`direction.angle`：0=南 90=西 180=北 270=东）。
 *       双向线路上同一根轨上南行、北行**各有一个区间**，两者重叠是**正确**的 ——
 *       现场实测被区间覆盖的 96 根轨里 62 根属于 2 个以上区间（最多 5 个）。</li>
 *   <li><b>同一个点属于几个区间是"集合"，不是"唯一归属"</b>（`byRail`）。旧的单值"节点归属"
 *       已按用户裁定删除，任何"这点归哪一段"的问法都要带上方向或接受多个答案。</li>
 * </ol>
 */

/** 区间的行车方向。角度沿用引擎与信号灯同一套 Facing 约定。 */
export interface SectionDirection {
	/** MTR Facing 角：0 = 南（+z）、90 = 西（−x）、180 = 北（−z）、270 = 东（+x）。 */
	readonly angle: number;
	/** 引擎给的中文方向名（南行/北行/东行/西行）。 */
	readonly label: string;
	readonly dx: number;
	readonly dz: number;
}

/** 区间里的一段：某根轨上的一段弧窗 + 这段的采样点（世界平面坐标）。 */
export interface SectionSpan {
	readonly hex: string;
	/** 沿轨弧长的起点/终点（米）。 */
	readonly from: number;
	readonly to: number;
	/** 本段是沿弧增（true）还是沿弧减（false）走过的。 */
	readonly dirOfTravel: boolean;
	/** 引擎采样好的折线点：`[x, z, x, z, …]`（世界坐标，**不是**屏幕坐标）。 */
	readonly points: readonly number[];
}

/** 一个区间。 */
export interface Section {
	/** 区间 id（`<入口灯>`，一灯多腿时带 `#n` 后缀）。 */
	readonly id: string;
	/** 开这个区间的那盏灯（区间 id 可能带后缀，所以入口灯单列一份）。 */
	readonly entrySignal: string;
	/** 出口灯；空串 = 走到线路尽头（没人收口）。 */
	readonly exitSignal: string;
	/** 下游区间 id。 */
	readonly next: string;
	readonly aspect: string;
	readonly occupied: boolean;
	readonly length: number;
	readonly direction: SectionDirection;
	readonly spans: readonly SectionSpan[];
}

/** `byRail` 的一条：某根轨的一段弧窗，以及**覆盖它的那些区间**（可能多个方向各一个）。 */
export interface RailMembership {
	readonly hex: string;
	readonly from: number;
	readonly to: number;
	readonly members: readonly {
		readonly section: string;
		readonly angle: number;
		readonly label: string;
		readonly aspect: string;
		readonly occupied: boolean;
	}[];
	/** 覆盖这条弧窗的区间个数。 */
	readonly memberCount: number;
	/** 覆盖它的**方向数**（≥2 = 双向运行，方案 B 要画两条带的就是它）。 */
	readonly directionCount: number;
	readonly bidirectional: boolean;
}

/** 引擎原始响应（接口形状，改动时要与 `SystemMapServlet#getMmtrSections` 同步）。 */
export interface RawSectionsResponse {
	readonly sections?: readonly Section[];
	readonly byRail?: readonly RailMembership[];
	readonly railCount?: number;
}

/**
 * 区间是否**带方向**（= 引擎已经部署了 notes/156 那一版）。
 *
 * <p>存在的理由：网页的 `dist` 由引擎直接从磁盘发（`MMTR_WEB_ROOT`），所以**换前端不需要重启引擎**，
 * 而"先上网页、后停机上引擎"是完全可能的顺序。旧引擎发的区间没有 `direction` 字段，直接去读它
 * 会在渲染里抛异常、整张图白掉。所以画带之前先认一下这一版数据有没有方向，没有就不画这一层 ——
 * 宁可少画一层，也不要整个页面挂掉。</p>
 */
export function hasDirection(section: Section): boolean {
	return typeof (section as {direction?: unknown}).direction === "object" && section.direction !== null;
}

/** 方向上色（方案 B：两个方向各一条带，颜色必须一眼分得开）。 */
export function directionColor(angle: number): string {
	// 归一到 [0,360) 后取最近的 90°，避免浮点误差把 179.9 判成东行
	const normalized = ((angle % 360) + 360) % 360;
	const quadrant = Math.round(normalized / 90) * 90 % 360;
	switch (quadrant) {
		case 0:
			return "#4a9ee8";      // 南行（+z）
		case 90:
			return "#d98b3a";      // 西行（−x）
		case 180:
			return "#5cc08a";      // 北行（−z）
		default:
			return "#b47ad9";      // 东行（+x）
	}
}

/** 方向角 → 单位向量（与引擎 `headingOf` 同一套数：`(-sin, cos)`）。 */
export function directionHeading(angle: number): {x: number; z: number} {
	const radians = (angle * Math.PI) / 180;
	return {x: -Math.sin(radians), z: Math.cos(radians)};
}

/** 按轨 hex 建索引：一根轨 → 覆盖它的那些区间（多值）。 */
export function indexMembershipsByRail(memberships: readonly RailMembership[]): Map<string, RailMembership[]> {
	const map = new Map<string, RailMembership[]>();
	for (const membership of memberships) {
		const list = map.get(membership.hex);
		if (list === undefined) {
			map.set(membership.hex, [membership]);
		} else {
			list.push(membership);
		}
	}
	return map;
}
