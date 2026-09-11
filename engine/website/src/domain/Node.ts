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
	readonly block?: string;
	readonly neighbors?: readonly {x: number; y: number; z: number; rail: string}[];
}

/** 节点在极简平面图里的形状分类（按度数）。 */
export type NodeKind = "end" | "through" | "fork" | "crossing";

export class Node {
	readonly x: number;
	readonly y: number;
	readonly z: number;
	readonly degree: number;
	/** 所属区间 id：有灯区间是灯键，无灯区间是 `无灯#<轨>@<弧>`；空串表示引擎没给出。 */
	readonly block: string;
	readonly neighbours: readonly NodeNeighbour[];

	constructor(raw: RawTopologyNode) {
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
		this.block = raw.block ?? "";
	}

	/** 稳定唯一键（与引擎 `positionsToRail` 的键一致，三道岔接口也用它）。 */
	get key(): string {
		return `${this.x},${this.y},${this.z}`;
	}

	/** 显示坐标（世界方块坐标）。 */
	get coords(): string {
		return `${this.x}, ${this.y}, ${this.z}`;
	}

	/** 极简平面图坐标：`(x, -z)`，与 C# 端画布同一约定（世界 z 越大越靠上）。 */
	get planeX(): number {
		return this.x;
	}

	get planeZ(): number {
		return -this.z;
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

	/** 有灯区间返回灯键，无灯区间返回"无灯"，空串返回"未知"。 */
	get blockText(): string {
		if (!this.block) {
			return "未知";
		}
		return this.isUnguardedBlock ? "无灯区间" : this.block;
	}

	get isUnguardedBlock(): boolean {
		return this.block.startsWith("无灯#");
	}

	/** 无灯区间的短写法：`无灯#轨hex@弧` → `无灯#轨片段@弧`。 */
	get blockShort(): string {
		if (!this.block) {
			return "—";
		}
		if (!this.isUnguardedBlock) {
			return this.block;
		}
		const rail = this.block.slice(this.block.indexOf("#") + 1);
		const at = rail.lastIndexOf("@");
		const hex = at > 0 ? rail.slice(0, at) : rail;
		const arc = at > 0 ? rail.slice(at) : "";
		return `无灯#${Node.shortHex(hex)}${arc}`;
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
