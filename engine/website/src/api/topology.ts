import {postJson, requestJson} from "./client";
import {Node, type RawTopologyNode} from "@/domain/Node";
import {Rail, type RawRail} from "@/domain/Rail";
import {Signal, type RawSignal} from "@/domain/Signal";
import {Point, type RawPoint} from "@/domain/Point";
import type {RailMembership, RawSectionsResponse, Section} from "@/domain/Section";

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
