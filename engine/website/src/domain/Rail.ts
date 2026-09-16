
/**
 * 轨实体。
 *
 * <p>数据来自引擎的 `/mtr/api/map/mmtr-topology` 的 `rails` 数组：每条轨给出两端的世界坐标
 * （端点一定落在节点上，实测 134/134）与两端限速。</p>
 *
 * <p>线型（直线/曲线）由世界坐标决定，这里只做"给人看"的封装，不含几何计算——
 * 几何在 `domain/railGeometry.ts`。</p>
 */

/** 引擎原始轨数据（接口形状，改动时要与 `SystemMapServlet.getMmtrTopology` 同步）。 */
export interface RawRail {
	readonly hex: string;
	readonly x1: number;
	readonly y1: number;
	readonly z1: number;
	readonly x2: number;
	readonly y2: number;
	readonly z2: number;
	/**
	 * 沿轨里程采样的真实形状（世界坐标三元组，从轨的一个端点到另一个端点）。
	 *
	 * <p>引擎的轨由**两段圆弧**拼成，所以可以弯成 U 型甚至 S 型；只有两个端点的话前端只能自己
	 * 编一个中间形状，编出来的（单弧、贝塞尔）跟真实轨道明显不一样。老接口刻意不带这个字段
	 * （原注释写的是 "no in-game curve sampling"），那是把地图当成抽象拓扑图了。</p>
	 *
	 * <p>可选：老版本引擎不带这个字段时，前端退回"端点 + 线型规则"的画法。</p>
	 */
	readonly path?: readonly (readonly number[])[];
	readonly speedLimitKmh1?: number;
	readonly speedLimitKmh2?: number;
}

export class Rail {
	readonly hex: string;
	readonly x1: number;
	readonly y1: number;
	readonly z1: number;
	readonly x2: number;
	readonly y2: number;
	readonly z2: number;
	/** 沿轨采样点（世界坐标）。为空表示引擎没给形状，调用方退回端点画法。 */
	readonly path: readonly {x: number; y: number; z: number}[];
	readonly speedLimitKmh1: number;
	readonly speedLimitKmh2: number;

	constructor(raw: RawRail) {
		this.hex = raw.hex;
		this.x1 = raw.x1;
		this.y1 = raw.y1;
		this.z1 = raw.z1;
		this.x2 = raw.x2;
		this.y2 = raw.y2;
		this.z2 = raw.z2;
		this.path = (raw.path ?? [])
			.filter(sample => sample.length >= 3)
			.map(sample => ({x: sample[0]!, y: sample[1]!, z: sample[2]!}));
		this.speedLimitKmh1 = raw.speedLimitKmh1 ?? 0;
		this.speedLimitKmh2 = raw.speedLimitKmh2 ?? 0;
	}

	/**
	 * 平面图坐标 = 世界 {@code (x, z)} 直映（见 `Node.planeZ` 的说明）。
	 *
	 * <p>同一份映射必须各处一致：节点、轨的两端、轨的采样点、信号灯位 —— 少改一处，
	 * 那一样东西就会**上下颠倒**地画到图上（比全反更难发现）。所以这里连注释都指向同一处说明。</p>
	 */
	get planeX1(): number {
		return this.x1;
	}

	get planeY1(): number {
		return this.z1;
	}

	get planeX2(): number {
		return this.x2;
	}

	get planeY2(): number {
		return this.z2;
	}

	/** 是否"同一轴"：x 或 z 任一相同 → 画直线，否则画曲线。 */
	get isAxisAligned(): boolean {
		/*
	 * "同一轴"的判据（原来是 `railGeometry.isAxisAligned`）：x 或 z 任一相同就是轴对齐。
	 * 容差取 0.05 格（方块坐标下的浮点噪声远小于它）。绘图那一摊删掉之后它留在这里 ——
	 * 这条规则属于**数据**（这条轨是直是弯），不属于画法。
	 */
	return Math.abs(this.x1 - this.x2) < 0.05 || Math.abs(this.z1 - this.z2) < 0.05;
	}

	/** 两个方向的限速是否一致（不一致的轨在界面上单独标出来）。 */
	get speedLimitsEqual(): boolean {
		return this.speedLimitKmh1 === this.speedLimitKmh2;
	}

	/** 取较高的限速用来配色（双方向限速不同时，画出来的是"较好的那条"）。 */
	get speedLimitKmh(): number {
		return Math.max(this.speedLimitKmh1, this.speedLimitKmh2);
	}

	/** 平面长度（米），用于信息卡。 */
	get length(): number {
		return Math.hypot(this.x2 - this.x1, this.z2 - this.z1);
	}
}

/**
 * **采样坐标系相对节点坐标系的系统偏移**（世界格）—— 也就是"方块中心 − 方块角"。
 *
 * <h2>为什么需要它（这一页踩到的坑）</h2>
 * <p>引擎里有两套写法，量的是同一个物理位置：</p>
 * <ul>
 *   <li>**节点坐标是方块角**（整数）：`mmtr-topology.nodes`、`mmtr-points` 的道岔行、`mmtr-lamps` 的
 *       灯坐标都在这套里；</li>
 *   <li>**轨的采样点（`path`）与区间的 span 是方块中心**（`n + 0.5`）：一根轨的首个采样点比它的
 *       端点**一致地多 (0.5, 0.5) 格** —— 实测这张 dev 世界 159 根轨的 318 个端点里 **317 个**
 *       都是这个偏移（另一个是轨中段被灯切出来的那段）。</li>
 * </ul>
 * <p>所以"把 A 画到 B 旁边"时，两边只要不是同一套写法，就会出现半格的错位。区间地图上亲眼看到的是：
 * **信号灯左右分布不均匀** —— 灯锚在**节点**上（角），而这一页的轨/带/端点在**方块中心**，
 * 于是右边的灯离点 1.58 格、左边的灯 2.55 格（实测 48 盏 2.55 + 43 盏 1.58）。</p>
 *
 * <h2>为什么"量"而不是写死 0.5</h2>
 * <p>与「地图」页那条"不要把校正量写成减 0.5 格"同源：引擎的约定一变，写死的数就会**反向偏**。
 * 这里取所有轨的 {@code path[0] − 端点1} 的**众数**（按 0.01 格取整后统计），没有 `path` 的轨不参与；
 * 一条都算不出来时返回 {@code [0, 0]}（= 两套写法一致，不做任何搬移）。</p>
 */
export function railSampleShift(rails: readonly Rail[]): readonly [number, number] {
	const counts = new Map<string, {offset: readonly [number, number]; count: number}>();
	for (const rail of rails) {
		const first = rail.path[0];
		if (!first) {
			continue;
		}
		const offset = [
			Math.round((first.x - rail.planeX1) * 100) / 100,
			Math.round((first.z - rail.planeY1) * 100) / 100,
		] as const;
		const key = offset.join(",");
		const seen = counts.get(key);
		counts.set(key, {offset, count: (seen?.count ?? 0) + 1});
	}
	let best: {offset: readonly [number, number]; count: number} | null = null;
	for (const entry of counts.values()) {
		if (best === null || entry.count > best.count) {
			best = entry;
		}
	}
	return best?.offset ?? [0, 0];
}

