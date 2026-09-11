import {Node, type RawTopologyNode} from "./Node";

/**
 * 示例节点（**临时数据，不连服务端**）。
 *
 * <p>用户要求这一步"就单独放个节点在上面，先不读接口"，所以手写一小段真实形状的梯线：
 * 一个三岔（F）+ 三个端点分支 + 一个通过点（B），覆盖端点 / 通过 / 道岔三种外观与三种交互。</p>
 *
 * <p>形状按平面坐标 `(x, -z)` 看（世界 z 越大越靠上，与 C# 端画布同一约定）：</p>
 * <pre>
 *   A (-170,  79)        D (-176, 121)     C (-154, 138)
 *        \                  /                   |
 *         \                /                    |
 *          F (-168, 100) ─┘                     |
 *           |                                    |
 *           └──────────  B (-170, 122) ─────────┘
 * </pre>
 * <p>连接：A–F、D–F、B–F、C–B。所以 F 是度数 3 的道岔（喇叭口），B 是度数 2 的通过点，
 * A / C / D 是度数 1 的端点。</p>
 *
 * <p>坐标取自 dev 世界真实位置，方便以后接 `/mtr/api/map/mmtr-topology` 时对得上。
 * 接入后这个文件整体删掉——视图只依赖 `Node[]`，不关心它从哪来。</p>
 */

/** 每条相邻轨的 hex 用统一的前缀，避免示例里出现一行几百字符的假 id。 */
function railHex(tag: string): string {
	return `sample-${tag}`.padEnd(24, "0");
}

export const sampleNodes: Node[] = [
	// 三岔：三条腿分别通 A、B、D
	new Node({
		x: -168,
		y: -60,
		z: -100,
		degree: 3,
		block: "灯 F-100",
		neighbors: [
			{x: -170, y: -60, z: -79, rail: railHex("fa")},
			{x: -170, y: -60, z: -122, rail: railHex("fb")},
			{x: -176, y: -60, z: -121, rail: railHex("fd")},
		],
	} satisfies RawTopologyNode),
	// 通过点：一条腿通 F，另一条通 C
	new Node({
		x: -170,
		y: -60,
		z: -122,
		degree: 2,
		block: "无灯#sample-b@0.0",
		neighbors: [
			{x: -168, y: -60, z: -100, rail: railHex("bf")},
			{x: -154, y: -60, z: -138, rail: railHex("bc")},
		],
	} satisfies RawTopologyNode),
	// 端点：只有一条腿
	new Node({
		x: -170,
		y: -60,
		z: -79,
		degree: 1,
		block: "灯 A-79",
		neighbors: [
			{x: -168, y: -60, z: -100, rail: railHex("af")},
		],
	} satisfies RawTopologyNode),
	new Node({
		x: -154,
		y: -60,
		z: -138,
		degree: 1,
		block: "",
		neighbors: [
			{x: -170, y: -60, z: -122, rail: railHex("cb")},
		],
	} satisfies RawTopologyNode),
	new Node({
		x: -176,
		y: -60,
		z: -121,
		degree: 1,
		block: "无灯#sample-d@0.0",
		neighbors: [
			{x: -168, y: -60, z: -100, rail: railHex("df")},
		],
	} satisfies RawTopologyNode),
];
