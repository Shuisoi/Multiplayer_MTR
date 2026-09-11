import {Node, type RawTopologyNode} from "./Node";

/**
 * 示例节点（**临时数据，不连服务端**）。
 *
 * <p>用户要求这一步"就单独放个节点在上面，先不读接口"，所以这里手写三个不同形态的节点，
 * 用来验证节点本身的三种交互：悬停信息、左键操作菜单、以及不同度数下的外观。
 * 等接入引擎的 `/mtr/api/map/mmtr-topology` 时，这个文件整体删掉即可——
 * 视图只依赖 `Node[]`，不关心它从哪来。</p>
 *
 * <p>坐标是真实世界里的位置（dev 世界的车场梯线一带），方便以后真接上时对得上。</p>
 */
export const sampleNodes: Node[] = [
	new Node({
		x: -162,
		y: -60,
		z: -122,
		degree: 2,
		block: "无灯#FFFFFFFFFFFFFF5E-FFFFFFFFFFFFFFC4-FFFFFFFFFFFFFF86-FFFFFFFFFFFFFF5E-FFFFFFFFFFFFFFC4-FFFFFFFFFFFFFFB1@0.0",
		neighbors: [
			{x: -162, y: -60, z: -79, rail: "FFFFFFFFFFFFFF5E-FFFFFFFFFFFFFFC4-FFFFFFFFFFFFFF86-FFFFFFFFFFFFFF5E-FFFFFFFFFFFFFFC4-FFFFFFFFFFFFFFB1"},
			{x: -154, y: -60, z: -138, rail: "FFFFFFFFFFFFFF5E-FFFFFFFFFFFFFFC4-FFFFFFFFFFFFFF86-FFFFFFFFFFFFFF66-FFFFFFFFFFFFFFC4-FFFFFFFFFFFFFF75"},
		],
	} satisfies RawTopologyNode),
	new Node({
		x: -168,
		y: -60,
		z: -100,
		degree: 3,
		block: "-168,-60,-100",
		neighbors: [
			{x: -170, y: -60, z: -122, rail: "FFFFFFFFFFFFFF56-FFFFFFFFFFFFFFC4-FFFFFFFFFFFFFF86-FFFFFFFFFFFFFF56-FFFFFFFFFFFFFFC4-FFFFFFFFFFFFFFB1"},
			{x: -170, y: -60, z: -79, rail: "FFFFFFFFFFFFFF56-FFFFFFFFFFFFFFC4-FFFFFFFFFFFFFF86-FFFFFFFFFFFFFF56-FFFFFFFFFFFFFFC4-FFFFFFFFFFFFFFB0"},
			{x: -176, y: -60, z: -121, rail: "FFFFFFFFFFFFFF50-FFFFFFFFFFFFFFC4-FFFFFFFFFFFFFF87-FFFFFFFFFFFFFF56-FFFFFFFFFFFFFFC4-FFFFFFFFFFFFFF79"},
		],
	} satisfies RawTopologyNode),
	new Node({
		x: -145,
		y: -60,
		z: -290,
		degree: 1,
		block: "",
		neighbors: [
			{x: -145, y: -60, z: -274, rail: "FFFFFFFFFFFFFF6D-FFFFFFFFFFFFFFC4-FFFFFFFFFFFFFF00-FFFFFFFFFFFFFF6D-FFFFFFFFFFFFFFC4-FFFFFFFFFFFFFF11"},
		],
	} satisfies RawTopologyNode),
];
