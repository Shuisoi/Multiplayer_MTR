/**
 * 节点实体（轨道层）。
 *
 * 数据来自引擎的 `/mtr/api/map/mmtr-topology`：每个节点带世界坐标、度数、它所属的区间 id、以及邻居
 * （每个邻居 = 一条相邻轨 + 那条轨的另一端坐标）。
 *
 * <p>拓扑概念在引擎里，这里只做"给人看"的封装：把三个数字变成有含义的名字（端点/通过/道岔）、
 * 算出它在极简平面图上的位置（沿用 C# 端那套 `(x, -z)` 习惯）、以及邻居轨的短标识。</p>
 */

/** 一个邻居：通过 `rail` 连到的那个节点。 */
export interface NodeNeighbour {
	/** 邻居节点的世界坐标。 */
	readonly x: number;
	readonly y: number;
	readonly z: number;
	/** 连接两者的轨 id（引擎的 hex）。 */
	readonly rail: string;
}

/** 引擎原始节点数据（接口形状，改动时要与 `SystemMapServlet.getMmtrTopology` 同步）。 */
export interface RawTopologyNode {
	readonly x: number;
	readonly y: number;
	readonly z: number;
	readonly degree?: number;
	readonly neighbors?: readonly {x: number; y: number; z: number; rail: string}[];
}

/**
 * 节点 → 覆盖它的区间 id（**可能多个**）。
 *
 * <p>由视图层从 `mmtr-sections` 的 spans 推出来（节点是轨的端点，所以按"轨 + 端点弧"就能对上），
 * 因为引擎已经不再给"节点归属"了 —— 区间是某方向的一段路，一个节点被两个方向的区间同时覆盖是常态。</p>
 */
export type SectionIndex = ReadonlyMap<string, readonly string[]>;

/** 节点在极简平面图里的形状分类（按度数）。 */
export type NodeKind = "end" | "through" | "fork" | "crossing";

export class Node {
	readonly x: number;
	readonly y: number;
	readonly z: number;
	readonly degree: number;
	/**
	 * 覆盖这个节点的区间 id（**可能多个**：双向线路上南行、北行各一个）。
	 *
	 * <p>空数组 = 没有任何区间覆盖它（这一段没有灯管到）。</p>
	 */
	readonly sections: readonly string[];
	readonly neighbours: readonly NodeNeighbour[];

	constructor(raw: RawTopologyNode, sectionIndex?: SectionIndex) {
		this.x = raw.x;
		this.y = raw.y;
		this.z = raw.z;
		this.neighbours = (raw.neighbors ?? []).map(neighbour => ({
			x: neighbour.x,
			y: neighbour.y,
			z: neighbour.z,
			rail: neighbour.rail,
		}));
		this.degree = raw.degree ?? this.neighbours.length;
		this.sections = sectionIndex?.get(`${raw.x},${raw.y},${raw.z}`) ?? [];
	}

	/** 稳定唯一键（与引擎 `positionsToRail` 的键一致，三道岔接口也用它）。 */
	get key(): string {
		return `${this.x},${this.y},${this.z}`;
	}

	/** 显示坐标（世界方块坐标）。 */
	get coords(): string {
		return `${this.x}, ${this.y}, ${this.z}`;
	}

	/**
	 * 平面图坐标 = 世界 {@code (x, z)} 直映（不翻转）。
	 *
	 * <p>原先是 {@code (x, -z)}（"北在上"的制图习惯）。但看这张图的人是拿它对着游戏里的俯视图看的，
	 * 而游戏俯视里 z 增大就是往下 —— {@code -z} 会让整张图**上下反着**（用户原话："好像地图是反的"）。
	 * 直映之后与游戏一致：x 向右、z 向下。</p>
	 */
	get planeX(): number {
		return this.x;
	}

	get planeZ(): number {
		return this.z;
	}

	get kind(): NodeKind {
		if (this.degree <= 1) {
			return "end";
		}
		if (this.degree === 2) {
			return "through";
		}
		return this.degree === 3 ? "fork" : "crossing";
	}

	get kindText(): string {
		switch (this.kind) {
			case "end":
				return "端点（尽头）";
			case "through":
				return "通过点";
			case "fork":
				return "道岔（三岔）";
			default:
				return "交叉（四岔）";
		}
	}

	/** 是否是一个可以搬岔的节点。 */
	get isFork(): boolean {
		return this.degree >= 3;
	}

	/**
	 * 覆盖这个节点的区间：0 个 → "无区间"，1 个 → 那个 id，多个 → 用 ` + ` 连起来。
	 *
	 * <p>取代旧的单值 `blockText`。多值不是异常：双向线路上同一根轨的两个方向的区间都覆盖它，
	 * 所以界面上必须能一眼看出"这一格同时属于两个方向"。</p>
	 */
	get blockText(): string {
		if (this.sections.length === 0) {
			return "无区间";
		}
		return this.sections.join(" + ");
	}

	/** 是否有多个区间覆盖（双向运行的位置）。 */
	get isMultiSection(): boolean {
		return this.sections.length > 1;
	}

	/** 短写法：轨 hex 只留后 8 位（节点 id 本身就是短坐标，不必再截）。 */
	get blockShort(): string {
		if (this.sections.length === 0) {
			return "—";
		}
		return this.sections
			.map(section => {
				const at = section.lastIndexOf("#");
				return at > 0 ? section.slice(0, at) : section;
			})
			.join(" + ");
	}

	/** 与某个邻居之间的世界距离（米）。 */
	distanceTo(neighbour: NodeNeighbour): number {
		return Math.hypot(neighbour.x - this.x, neighbour.y - this.y, neighbour.z - this.z);
	}

	/** 节点自身的半径（画图用，仅依赖度数）。 */
	get radius(): number {
		return this.degree <= 1 ? 3 : (this.degree === 2 ? 3.6 : 5);
	}

	/** 轨 hex 的短标识（前 8 位足以在界面上区分）。 */
	static shortHex(hex: string): string {
		return hex.length > 8 ? hex.slice(0, 8) : hex;
	}

	/** 按 key 建索引。 */
	static index(nodes: readonly Node[]): Map<string, Node> {
		const map = new Map<string, Node>();
		for (const node of nodes) {
			map.set(node.key, node);
		}
		return map;
	}
}
