import {requestJson} from "./client";
import {Node, type RawTopologyNode} from "@/domain/Node";

/**
 * 轨道层拓扑：`/mtr/api/map/mmtr-topology`。
 *
 * <p>返回引擎算出来的全部节点与轨。**这里只用节点**——按用户要求"不需要连线"，
 * 所以 `rails` 原样留着（接口会给），但不参与显示。</p>
 */
export interface TopologyResponse {
	readonly nodes: readonly RawTopologyNode[];
	readonly rails: readonly unknown[];
}

export async function fetchTopology(): Promise<TopologyResponse> {
	const data = await requestJson<TopologyResponse>("map/mmtr-topology");
	return {
		nodes: data.nodes ?? [],
		rails: data.rails ?? [],
	};
}

/** 取全部节点并转成前端实体。 */
export async function fetchNodes(): Promise<Node[]> {
	const topology = await fetchTopology();
	return topology.nodes.map(raw => new Node(raw));
}
