/*
 * 相对导入带 `.ts` 扩展名是**故意的**：这个模块要被 `scripts/map-nodes.test.ts` 用 node 直接跑，
 * 而 Node 的 ESM 解析不认省略扩展名（见 `tsconfig.json` 的 `allowImportingTsExtensions`）。
 */
import {Node} from "./Node.ts";
import {Point, railEndpointsText} from "./Point.ts";
import {Signal} from "./Signal.ts";

/**
 * **派生出来的轨道节点**：原始拓扑节点 + 挂在它身上的道岔与信号灯。
 *
 * <h2>为什么要有这一层</h2>
 * <p>引擎的三个接口是分开的：节点在 `mmtr-topology`、道岔在 `mmtr-points`、信号灯在
 * `mmtr-signals`。要画"这个节点上是红灯还是绿灯、道岔开在哪一位"，就得先在网页这边把它们
 * 拼成一个对象 —— 拼法（怎么算"这盏灯属于哪个节点"）是**要写下来、要能被验证**的东西，
 * 所以放在 domain 里当纯函数，而不是散在画图的组件里。</p>
 *
 * <h2>不是每个节点都有道岔，也不是每个节点都有灯</h2>
 * <p>实测这张 dev 世界：155 个节点里 **30 个有道岔**（18 个一行、10 个三行、2 个四行，共 56 行）、 * 96 盏灯里 **94 盏落在了某个节点上**（另外 2 盏离最近的节点也有 11.4 / 21.1 格，见下）。
 * 所以两样都是**可空**的，界面必须能表示"什么都没有"这一种。</p>
 *
 * <h2>道岔为什么可能有多行</h2>
 * <p>单开道岔在接口里是**每个进向一行**（三行说的是同一处道岔的同一个位置），腿号逐行不同。
 * {@link MapNode.turnouts} 保留全部行，{@link MapNode.turnoutState} 给出"根部那一行"——
 * 只有它的 `via` 等于 `stem`，也只有它能表达位置 0/1。</p>
 *
 * <h2>信号灯怎么落到节点上</h2>
 * <p>规则是**水平面内的最近节点 + 容差**（{@link SIGNAL_NODE_MAX_DISTANCE}）：灯立在轨旁而不是
 * 轨上，所以它跟节点的坐标本来就不重合。实测最近节点偏移的构成：dx=±2、dz=0 共 85 盏，
 * dx/dz=±3 共 9 盏，再往外就是 11.4 / 21.1 格那两盏。容差取 5 格正好把"轨旁的灯"与
 * "离节点很远的灯"分开，落不进容差的进 {@link DerivedNodes.orphanSignals} —— **不硬塞给某个
 * 节点**（硬塞会让一盏远处的灯在图上长在错误的节点上，比不显示更难查）。</p>
 *
 * <p>只比水平距离（x/z）不比 y：灯比节点高 0～1 格是常态，而这张图是平面视图。</p>
 */

/**
 * 信号灯能被认到某个节点上的最大水平距离（格）。
 *
 * <p>取 5：实测 94/96 盏在 3 格以内（85 盏正好 2 格），另 2 盏在 11.4 / 21.1 格。中间是空的，
 * 所以这个阈值不是"卡在数据上"，而是两边都有余量。</p>
 */
export const SIGNAL_NODE_MAX_DISTANCE = 5;

/** 派生结果：全部节点 + 没能认到节点的灯。 */
export interface DerivedNodes {
	readonly nodes: readonly MapNode[];
	/**
	 * 离任何节点都超过 {@link SIGNAL_NODE_MAX_DISTANCE} 格的灯。
	 *
	 * <p>返回它们是为了**看得见**：静默丢掉会变成"图上少了一盏灯，没人知道为什么"。</p>
	 */
	readonly orphanSignals: readonly Signal[];
}

export class MapNode {
	/** 原始拓扑节点（坐标、度数、邻居）。 */
	readonly node: Node;
	/** 这个节点上的道岔行（**可能为空**；单开道岔是多行）。 */
	readonly turnouts: readonly Point[];
	/** 守在这个节点上的信号灯（**可能为空**，也可能多盏：两个方向各一盏是常态）。 */
	readonly signals: readonly Signal[];

	constructor(node: Node, turnouts: readonly Point[], signals: readonly Signal[]) {
		this.node = node;
		this.turnouts = turnouts;
		this.signals = signals;
	}

	/** 节点键（引擎的 `x,y,z`）—— 道岔行的键与它相同。 */
	get key(): string {
		return this.node.key;
	}

	get hasTurnout(): boolean {
		return this.turnouts.length > 0;
	}

	get hasSignal(): boolean {
		return this.signals.length > 0;
	}

	/** 这个节点是不是"什么都没有"（既不是道岔、也没有灯）。 */
	get isPlain(): boolean {
		return !this.hasTurnout && !this.hasSignal;
	}

	/**
	 * 道岔状态（**没有道岔时为 null**）。
	 *
	 * <p>优先给**根部那一行**（`isStemRow`）：单开道岔的三行里只有它能表达"位置 0/1"，
	 * 另两行是逐进向的腿号视图。不是物理道岔（老式岔口、没有 `position`）时退回第一行，
	 * 由调用方按 `Point.isTurnout` 分两种读法。</p>
	 */
	get turnoutState(): Point | null {
		if (this.turnouts.length === 0) {
			return null;
		}
		return this.turnouts.find(turnout => turnout.isStemRow) ?? this.turnouts[0]!;
	}

	/** 这一个节点上所有灯的状态（可能为空）—— 空数组 = 这个节点没有灯，不是"状态未知"。 */
	get signalStates(): readonly string[] {
		return this.signals.map(signal => signal.state);
	}

	/** 一行字的状态摘要（信息卡与调试用）：没有的就不写。 */
	get stateText(): string {
		const parts: string[] = [];
		const turnout = this.turnoutState;
		if (turnout) {
			parts.push(turnout.stateText);
		}
		if (this.signals.length > 0) {
			parts.push(this.signals.map(signal => signal.stateText).join(" / "));
		}
		return parts.length === 0 ? "无道岔、无信号灯" : parts.join("；");
	}
}

/**
 * 菜单里可以点的一个"扳岔动作"。
 *
 * <p>它已经是**能直接下发的形状**：`{x, y, z, via, leg}`（节点坐标取自 {@link MapNode}），
 * 与 `mmtr-point-op` 的入参一一对应 —— 位置↔腿的换算在这里做完，界面只负责发出去。</p>
 */
export interface TurnoutAction {
	/** 这一行（进向轨）的 hex。 */
	readonly via: string;
	/** 要下发的腿号（`mmtr-point-op` 的 `branch`）。 */
	readonly leg: number;
	/**
	 * 单开道岔：这个动作把道岔扳到哪个**物理位置**（0 = 正线贯通 / 1 = 岔股开放）。
	 *
	 * <p>菜单要用它去问"这个位置接的是哪两根轨"（{@link Point.turnoutPositionText}）——
	 * 用户 2026-09-16 当场问过"这处道岔到底在 (-176,-289) 与 (-170,-289) 之间切，还是在别处切"：
	 * 卡片只说"位置 0/1（正线贯通/岔股开放）"时，人没法把它与图上看到的轨对上。</p>
	 *
	 * <p>没有物理模型的岔口（逐行逐腿那种）给 {@code null}：那里"位置"这个概念不成立。</p>
	 */
	readonly position: number | null;
	/** 菜单文字。 */
	readonly label: string;
	/** 是不是**当前所在位**（菜单上标一下，免得点了个寂寞）。 */
	readonly current: boolean;
}

/**
 * 一个道岔节点在菜单里应该给哪些动作。
 *
 * <p>两种形态分开处理，因为接口里它们的语义不同：</p>
 * <ol>
 *   <li>**单开道岔**（`turnoutState.isTurnout`，实测 28/30 个节点）：物理上只有一个位置，菜单给
 *       「位置 0（正线贯通）/ 位置 1（岔股开放）」两项；下发时用**根部那一行**的 `via`，腿号由
 *       {@link Point.turnoutLegFor} 按轨换算 —— 腿序是"直通→左→右→其它"，位置与腿号**没有**固定
 *       对应，直接发 0/1 会有时候对、有时候把位置说反。</li>
 *   <li>**没有物理模型的岔口**（实测 2/30，都是地图画法遗留的度4节点）：逐行逐腿列出来
 *       （文档的原话是"点 0/1/leg 即搬"），因为每个进向各有一组腿。</li>
 * </ol>
 *
 * <p>换算不出来（世界改画、这条腿的轨不见了）的动作**不列出来**，而不是列一个点了会静默失败的按钮。</p>
 */
export function turnoutActions(mapNode: MapNode): readonly TurnoutAction[] {
	const state = mapNode.turnoutState;
	if (state === null) {
		return [];
	}
	if (state.isTurnout) {
		const current = state.turnoutPosition ?? 0;
		return [0, 1].flatMap(position => {
			const leg = state.turnoutLegFor(position);
			return leg < 0 ? [] : [{
				via: state.via,
				leg,
				position,
				label: `位置 ${position}（${position === 1 ? "岔股开放" : "正线贯通"}）`,
				current: position === current,
			}];
		});
	}
	// 没有物理模型的岔口：逐行逐腿列出。轨名用**坐标**（hex 前缀对负坐标全是 FFFFFFFF，等于没说）
	return mapNode.turnouts.flatMap(row => row.legs.map(leg => ({
		via: row.via,
		leg: leg.index,
		position: null,
		label: `经 ${railEndpointsText(row.via)} 扳到腿 ${leg.index}（${leg.kindText}）`,
		current: row.activeLeg === leg.index,
	})));
}

/**
 * 当前道岔位置下**禁止通行**的那些轨（hex 集合）。
 *
 * <p>就是"道岔没指向的那根出口"：引擎在 `mmtr-points` 的每一行里直接给了 `prohibited`
 * （位置 0 = 岔股禁止通行，位置 1 = 正线远端被断开），网页**照用不重算** —— 几何自己推一遍
 * 就会出现"图上淡出的是 A、引擎其实切的是 B"这种最难查的分叉。</p>
 *
 * <p>实测这张 dev 世界：56 行道岔里 48 行带 `prohibited`（另 8 行是单开道岔的非根部行，位置由
 * 根部那一行表达），去重后 **27 根轨**被禁止；同一个节点的多行结论完全一致（实测 0 个节点不一致），
 * 而且这 27 根都真实存在于拓扑里、都挨着各自的道岔节点（实测 27/27、48/48）。</p>
 *
 * <p>返回集合而不是数组：调用方（图层）要按 hex 逐个问"这根要不要淡出"，集合是 O(1)。</p>
 */
export function prohibitedRailHexes(nodes: readonly MapNode[]): ReadonlySet<string> {
	const hexes = new Set<string>();
	for (const mapNode of nodes) {
		for (const turnout of mapNode.turnouts) {
			if (turnout.prohibitedHex !== "") {
				hexes.add(turnout.prohibitedHex);
			}
		}
	}
	return hexes;
}

/**
 * 轨 hex 的**规范化键**：把两端的坐标三元组按固定顺序排好，于是"同一根轨换了个写法"也能认出来。
 *
 * <p>为什么需要：hex 的六段是 `x1-y1-z1-x2-y2-z2`，**写法跟着方向走** —— 同一根轨，引擎在
 * `mmtr-topology` / `mmtr-points` 里按一种顺序写，而在**区间的 span 里按行车方向**写。
 * 实测这张 dev 世界 159 根轨里有 **10 根**在 span 里是倒过来的（例：`(1,64)→(-19,51)` 写成
 * `(-19,51)→(1,64)`）。于是"按 hex 字符串比对"会漏 —— 总区间的带按它比对时，**27 根禁止轨
 * 里漏掉了 1 根**（现场实测 26/27，探针逐条比对才发现，图上只是"少淡了一条"，肉眼看不出来）。</p>
 *
 * <p>轨道层（`SectionTrackLayer` / `RailNodesLayer`）用的是 `mmtr-points` 与 `mmtr-topology`
 * 两份数据，实测两边写法一致（27/27 原样命中），所以那边直接用原字符串也对；这个函数是给
 * "跨来源比对"用的（区间 span ↔ 道岔禁止轨）。</p>
 */
export function railHexKey(hex: string): string {
	const parts = hex.split("-");
	if (parts.length !== 6) {
		return hex;
	}
	const head = parts.slice(0, 3).join("-");
	const tail = parts.slice(3).join("-");
	return head <= tail ? hex : [parts[3], parts[4], parts[5], parts[0], parts[1], parts[2]].join("-");
}

/**
 * 把三份数据拼成派生节点。
 *
 * <p>纯函数：不取数、不碰 DOM，输入什么就是什么（好测）。</p>
 *
 * <p>道岔按**节点键精确匹配**（实测 56/56 命中，key 就是节点键）；信号灯按**水平最近 + 容差**
 * 匹配。两个方向的复杂度都是 O(节点 × 待匹配项)，实测这个世界的规模（155 × 96）根本不需要
 * 空间索引 —— 真到需要的时候再说。</p>
 *
 * @param nodes `mmtr-topology` 的节点
 * @param turnouts `mmtr-points` 的道岔行
 * @param signals `mmtr-signals` 的信号灯
 * @param maxSignalDistance 灯到节点的最大水平距离（默认 {@link SIGNAL_NODE_MAX_DISTANCE}）
 */
export function deriveMapNodes(
	nodes: readonly Node[],
	turnouts: readonly Point[],
	signals: readonly Signal[],
	maxSignalDistance = SIGNAL_NODE_MAX_DISTANCE,
): DerivedNodes {
	const turnoutsByNode = new Map<string, Point[]>();
	for (const turnout of turnouts) {
		const bucket = turnoutsByNode.get(turnout.key);
		if (bucket) {
			bucket.push(turnout);
		} else {
			turnoutsByNode.set(turnout.key, [turnout]);
		}
	}

	const signalsByNode = new Map<string, Signal[]>();
	const orphanSignals: Signal[] = [];
	for (const signal of signals) {
		let bestNode: Node | null = null;
		let bestDistance = Number.POSITIVE_INFINITY;
		let bestVerticalDistance = Number.POSITIVE_INFINITY;
		for (const node of nodes) {
			/*
			 * 注意两个实体对"平面纵坐标"的命名不同：`Node` 叫 `planeZ`、`Signal` 叫 `planeY`
			 * （都是"世界的 z"）。写错的那个不会报"拼错"，只会让距离变成 NaN，于是所有灯都落不进
			 * 任何节点 —— 静默地少掉整层数据。这里显式按各自的访问器取。
			 */
			const horizontal = Math.hypot(signal.planeX - node.planeX, signal.planeY - node.planeZ);
			if (horizontal > maxSignalDistance) {
				continue;
			}
			// 同一 (x, z) 上有多个节点（多层线路）时，取垂直方向最近的那个
			const vertical = Math.abs(signal.y - node.y);
			if (horizontal < bestDistance || (horizontal === bestDistance && vertical < bestVerticalDistance)) {
				bestNode = node;
				bestDistance = horizontal;
				bestVerticalDistance = vertical;
			}
		}
		if (!bestNode) {
			orphanSignals.push(signal);
			continue;
		}
		const bucket = signalsByNode.get(bestNode.key);
		if (bucket) {
			bucket.push(signal);
		} else {
			signalsByNode.set(bestNode.key, [signal]);
		}
	}

	return {
		nodes: nodes.map(node => new MapNode(node, turnoutsByNode.get(node.key) ?? [], signalsByNode.get(node.key) ?? [])),
		orphanSignals,
	};
}
