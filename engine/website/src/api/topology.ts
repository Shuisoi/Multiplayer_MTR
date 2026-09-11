import {requestJson} from "./client";
import {Node, type RawTopologyNode} from "@/domain/Node";
import {Rail, type RawRail} from "@/domain/Rail";

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
