import {postJson, requestJson} from "./client";
import {Node, type RawTopologyNode} from "@/domain/Node";
import {Rail, type RawRail} from "@/domain/Rail";
import {Signal, type RawSignal} from "@/domain/Signal";
import {Point, type RawPoint} from "@/domain/Point";
import type {RailMembership, RawSectionsResponse, Section} from "@/domain/Section";
import {deriveMapNodes, type DerivedNodes} from "@/domain/MapNode";
import {parseTrackSections, type RawTrackSection, type TrackSection} from "@/domain/TrackSection";
import {parseBlockSections, type BlockSection, type RawBlockSection} from "@/domain/BlockSection";
import {parseTotalSections, type RawTotalSection, type TotalSection} from "@/domain/TotalSection";
import {parsePlatforms, type Platform, type RawPlatform} from "@/domain/Platform";
import {parseTrains, type RawTrain, type Train} from "@/domain/Train";
import {parseSidings, type RawSiding, type Siding} from "@/domain/Siding";

/**
 * **L1 轨道区间**：`/mtr/api/map/mmtr-track-sections`（引擎 2026-09-16 新增）。
 *
 * <p>切点只由灯产生、**无方向**、双向共用；占用判定在这一层。上面的 `count / busyCount / railCount`
 * 一眼看出规模与忙闲，一并带回来（页面/调试都用得上）。</p>
 */
export interface TrackSectionsFeed {
	readonly sections: readonly TrackSection[];
	readonly count: number;
	readonly busyCount: number;
	readonly railCount: number;
}

/** 取 L1 轨道区间（区间地图的基础数据）。 */
export async function fetchTrackSections(): Promise<TrackSectionsFeed> {
	const data = await requestJson<{
		trackSections?: readonly RawTrackSection[];
		count?: number;
		busyCount?: number;
		railCount?: number;
	}>("map/mmtr-track-sections");
	return {
		sections: parseTrackSections(data.trackSections ?? []),
		count: data.count ?? 0,
		busyCount: data.busyCount ?? 0,
		railCount: data.railCount ?? 0,
	};
}

/**
 * **L2 行车区间**：`/mtr/api/map/mmtr-block-sections`（引擎 2026-09-16 新增）。
 *
 * <p>**有方向**、灯到灯、跨轨；无灯连通块整块一段 —— 授权（显示与停车）的单位。
 * 区间地图上的"带"（按灯位/占用上色）就画它，见 `domain/BlockSection.ts`。</p>
 */
export interface BlockSectionsFeed {
	readonly sections: readonly BlockSection[];
	readonly count: number;
	readonly busyCount: number;
	readonly lampCount: number;
}

/** 取 L2 行车区间。 */
export async function fetchBlockSections(): Promise<BlockSectionsFeed> {
	const data = await requestJson<{
		blockSections?: readonly RawBlockSection[];
		count?: number;
		busyCount?: number;
		lampCount?: number;
	}>("map/mmtr-block-sections");
	return {
		sections: parseBlockSections(data.blockSections ?? []),
		count: data.count ?? 0,
		busyCount: data.busyCount ?? 0,
		lampCount: data.lampCount ?? 0,
	};
}

/**
 * **总区间**：`/mtr/api/map/mmtr-total-sections`（引擎 2026-09-16 新增）。
 *
 * <p>**地图上"一条带"的唯一来源**：几何就是 L1 轨道区间（切点只由灯产生 ⇒ 再想变粗就得放弃某个方向的
 * 灯当界），多出来的是**归属** —— {@code covers[]} 说清"这一处由哪几个方向的哪几段覆盖、各自什么显示"。
 * 错开处正是它存在的理由：一辆车在那里**既在上行区间中、也在下行区间中**（两条 cover 都报占用），
 * 而地图只画一条带。见 `domain/TotalSection.ts`。</p>
 */
export interface TotalSectionsFeed {
	readonly sections: readonly TotalSection[];
	readonly count: number;
	readonly busyCount: number;
	/** 错开的有多少处（上下行都照到、却不是同一段路）。 */
	readonly staggeredCount: number;
}

/** 取总区间（区间地图的"带"）。 */
export async function fetchTotalSections(): Promise<TotalSectionsFeed> {
	const data = await requestJson<{
		totalSections?: readonly RawTotalSection[];
		count?: number;
		busyCount?: number;
		staggeredCount?: number;
	}>("map/mmtr-total-sections");
	return {
		sections: parseTotalSections(data.totalSections ?? []),
		count: data.count ?? 0,
		busyCount: data.busyCount ?? 0,
		staggeredCount: data.staggeredCount ?? 0,
	};
}

/**
 * **站台**：`/mtr/api/map/mmtr-platforms`（引擎 2026-09-16 新增）。
 *
 * <p>一条站台 = 一根被标记为站台的轨（引擎自己给的映射），带**站名 / 站台号 / 停站时间 / 所在轨 hex /
 * 两端 / 轴方向**。网页画"站台侧的 I 字 + 站名与台号（文字与轨平行）"就靠这一份。
 * 解析与几何见 `domain/Platform.ts`。</p>
 */
export interface PlatformsFeed {
	readonly platforms: readonly Platform[];
	readonly count: number;
}

/** 取站台（地图上的站台标记与站名）。 */
export async function fetchPlatforms(): Promise<PlatformsFeed> {
	const data = await requestJson<{platforms?: readonly RawPlatform[]; count?: number}>("map/mmtr-platforms");
	return {platforms: parsePlatforms(data.platforms ?? []), count: data.count ?? 0};
}

/**
 * **车辆**：`/mtr/api/map/mmtr-trains`（引擎 2026-09-16 补了"在轨上的位置"）。
 *
 * <p>每条给出 `railHex + railArcM + railArcLengthM + arcIncreasing` —— 网页在**自己已经画出来的那条轨**
 * 上按弧长比例取点，于是曲线轨也对得上、也不用去猜坐标系（`headX/headZ` 是采样那一套，直接用会偏半格）。
 * 同时还带速度、是否在运行、线路号、下一区间、车门、有人驾驶等，供悬浮提示用。
 * 解析见 `domain/Train.ts`。</p>
 */
export interface TrainsFeed {
	readonly trains: readonly Train[];
	/**
	 * **股道清单**（与车辆同一份响应里的 `sidings[]`）。
	 *
	 * <p>任务里写的是股道 **id**（`mission.targetSidingId`），要提示"任务目标"就得把 id 换成
	 * 名字（车场 + 股道号）—— 而这份清单本来就在同一个响应里，所以**不多发一个请求**。</p>
	 */
	readonly sidings: readonly Siding[];
	readonly count: number;
}

/** 取车辆（地图上的车位置）+ 股道清单（任务目标的 id 要靠它换名字）。 */
export async function fetchTrains(): Promise<TrainsFeed> {
	const data = await requestJson<{trains?: readonly RawTrain[]; sidings?: readonly RawSiding[]}>("map/mmtr-trains");
	const trains = parseTrains(data.trains ?? []);
	return {trains, sidings: parseSidings(data.sidings ?? []), count: trains.length};
}

/**
 * `fetchMapNodes()` 的结果：派生节点 + **轨道线本身**。
 *
 * <p>轨道线不用派生（它是几何，不带状态），但同一个入口已经把拓扑取到手了，就一起给出来 ——
 * 否则调用方（画线网的图层）为了拿轨还得再取一遍拓扑，同一份数据发两次请求。</p>
 */
export interface MapNodesFeed extends DerivedNodes {
	readonly rails: readonly Rail[];
}

/**
 * **派生节点**：一次把三份数据取齐（节点 / 道岔 / 信号灯）并拼成
 * {@link DerivedNodes}（见 `domain/MapNode.ts` 的匹配规则），顺带给出轨道线。
 *
 * <p>要画"节点上的道岔与信号灯状态"的图层用这一个入口就够，不用自己拼三个接口 ——
 * 拼法是领域规则（信号灯按水平最近节点 + 容差），散在组件里迟早会两边不一致。</p>
 *
 * <p>三个请求**并行**发出：它们是同一份世界数据的不同侧面，串行只会白白拉长首帧。</p>
 */
export async function fetchMapNodes(): Promise<MapNodesFeed> {
	const [topology, points, signals] = await Promise.all([fetchTopology(), fetchPoints(), fetchSignals()]);
	return {...deriveMapNodes(topology.nodes, points, signals), rails: topology.rails};
}

/**
 * 轨道层拓扑：`/mtr/api/map/mmtr-topology`。
 *
 * <p>返回引擎算出来的全部节点与轨。轨的两个端点一定落在节点上（实测这次 dev 世界的
 * 134 条轨全是如此），所以连线可以直接以节点为端点画，不需要额外的顶点。</p>
 */
export interface TopologyResponse {
	readonly nodes: readonly RawTopologyNode[];
	readonly rails: readonly RawRail[];
}

/** 取数并转成前端实体。 */
export async function fetchTopology(): Promise<{nodes: Node[]; rails: Rail[]}> {
	const data = await requestJson<TopologyResponse>("map/mmtr-topology");
	return {
		nodes: (data.nodes ?? []).map(raw => new Node(raw)),
		rails: (data.rails ?? []).map(raw => new Rail(raw)),
	};
}

/**
 * 信号灯：`/mtr/api/map/mmtr-signals`。
 *
 * <p>状态（红/单黄/双黄/绿）由引擎的闭塞层给出，前端只显示结论——见 `domain/Signal.ts`。
 * 放在这个文件里而不是单开一个：它和拓扑同一个 feed 组（同一个 servlet、同一份世界数据），
 * 拆开只会让"取数时机"变成两处。</p>
 */
export async function fetchSignals(): Promise<Signal[]> {
	const data = await requestJson<{signals: readonly RawSignal[]}>("map/mmtr-signals");
	return (data.signals ?? []).map(raw => new Signal(raw));
}

/**
 * 区间层：`/mtr/api/map/mmtr-sections`（**按方向划分的区间**，notes/156）。
 *
 * <p>这是网页"区间图层"的唯一数据来源。它一次给两样东西：</p>
 * <ul>
 *   <li>`sections` —— 每个区间（= 一盏灯开的那段路，带**方向**、入口/出口灯、每段的采样点）；</li>
 *   <li>`byRail` —— **一个点属于哪几个区间**的多值索引（双向线路上同一段弧会同时属于南行与北行，
 *       现场实测 96 根被覆盖的轨里 62 根是多归属）。</li>
 * </ul>
 *
 * <p>旧的 `blocks`（水闸区间）与节点 `block`（唯一归属）字段已经不再发送：区间是某方向的一段路，
 * "一个节点归一个区间"在双向线路上必然错，那一层已按用户裁定删除。</p>
 */
export async function fetchSections(): Promise<{sections: Section[]; byRail: RailMembership[]; railCount: number}> {
	const data = await requestJson<RawSectionsResponse>("map/mmtr-sections");
	return {
		sections: [...(data.sections ?? [])],
		byRail: [...(data.byRail ?? [])],
		railCount: data.railCount ?? 0,
	};
}

/**
 * 道岔：`/mtr/api/map/mmtr-points`。
 *
 * <p>引擎只把**有分支的**节点算作道岔（腿数 ≥ 2），直通与尽头不列出来 —— 所以这个列表的长度
 * 就是"世界上有多少个需要人管的道岔"，不是节点数。腿的顺序由引擎定（直通→左→右→其它），
 * 界面直接按序号显示与下发，不自己按几何重排。</p>
 */
export async function fetchPoints(): Promise<Point[]> {
	const data = await requestJson<{points: readonly RawPoint[]}>("map/mmtr-points");
	return (data.points ?? []).map(raw => new Point(raw));
}

/**
 * 扳一个道岔：把开通位设成第 {@code branch} 条腿。
 *
 * <p>走 `mmtr-point-op`（引擎的点操作接口）而不是指令通道：这是**一个原子动作**
 * （设一位），不是一段要解析的文本；而指令通道是给指令栏用的。两者最终落到同一个
 * `mmtrSetPoint`，所以语义一致，不存在"网页扳一处、指令栏扳成另一个样"。</p>
 *
 * @returns 引擎是否受理（`ok=false` = 节点/盆轨对不上，说明这个道岔已经不是接口给的那一个了）
 */
export async function setPointBranch(point: Point, branch: number): Promise<boolean> {
	const data = await postJson<{ok: boolean} | null>("map/mmtr-point-op", {
		x: point.x, y: point.y, z: point.z, via: point.via, branch,
	});
	return data?.ok ?? true;
}

/**
 * 触发一次"扫描世界里的信号灯"（游戏端命令 `signals scan`）。
 *
 * <h3>为什么需要它</h3>
 * <p>信号灯要**先被登记**才会出现在 `mmtr-signals` 里（登记表是 `mmtr-signals.json`）。
 * 世界里放着的 MTR 信号灯块如果没登记，接口里根本没有它们 —— 实测世界里扫出 37 个，
 * 而登记表当时只有 31 个，也就是**控制台少了 6 个灯**（用户反馈的"显示不全"就是这个）。</p>
 *
 * <h3>为什么在这里轮询</h3>
 * <p>命令是推给游戏端执行的（`mmtrPushCommand`），结果会回写到命令日志里。所以这里推完命令后
 * 轮询日志，直到看见 `[signals]` 那行扫描统计。**只有碰过命令日志的调用方才知道该等什么**，
 * 所以这段逻辑放在 API 层，而不是让视图组件去猜"要等多久"。</p>
 *
 * @returns 扫到的灯数与新增条目数；日志里没出现统计行（超时）时返回 null
 */
export async function scanSignals(timeoutMs = 8000): Promise<{found: number; added: number} | null> {
	await postJson<{ok: boolean}>("map/mmtr-command", {command: "signals scan"});
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		await new Promise(resolve => setTimeout(resolve, 400));
		try {
			// 空命令 = 只读日志（引擎对空命令不会入队，只回日志）
			const result = await postJson<{log?: readonly string[]}>("map/mmtr-command", {command: ""});
			// 从后往前找最新一行统计，避免读到上一轮的旧结果
			for (let i = (result.log?.length ?? 0) - 1; i >= 0; i--) {
				const match = /\[signals\]\s*扫描完成:\s*找到\s*(\d+)\s*个信号灯,\s*新增\s*(\d+)\s*个/.exec(result.log![i]!);
				if (match) {
					return {found: Number(match[1]), added: Number(match[2])};
				}
			}
		} catch {
			// 轮询失败不致命：继续等到超时，调用方按 null 处理（当作"没扫出新的"）
		}
	}
	return null;
}
