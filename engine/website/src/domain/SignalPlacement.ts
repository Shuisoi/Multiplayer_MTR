/*
 * 信号灯相对节点的**方位槽**：灯不画在它的世界坐标上，而是"挂"在它所属节点的正上/正下/正左/正右。
 *
 * <h2>为什么按方位槽，而不按真实坐标</h2>
 * <p>灯立在**轨旁**而不是轨上（实测：85/96 盏的最近节点偏移正好是 dx=±2、dz=0，其余在 ±3），
 * 所以它的世界坐标本来就跟节点差一两格。真按坐标画，缩放一小就糊在一起；而且这一层要跟着节点走 ——
 * 用户的原话是"信号灯总体根据其与节点的相对置于节点正上、正下、正左、正右"。</p>
 *
 * <p>以后如果改成**人工用工具绑定远端灯**（不再靠引擎按最近节点自动识别），这套规则照样成立：
 * 那时候"相对位置"就是"节点指向那盏灯的方向"，取主轴同样得到四个槽之一。所以这一层只依赖
 * {@code (节点, 灯)} 这个配对，不依赖配对是自动还是人工来的。</p>
 */

/** 四个方位槽。 */
export type SignalSlot = "up" | "right" | "down" | "left";

/**
 * 槽 → 画布坐标里的单位方向。
 *
 * <p>注意口径：平面图里画布 `y` 就是世界的 `z`（不翻转，见 `Node.planeZ` 的说明），
 * 而画布 y 向下 —— 所以世界 z 变小（北）是"上" = `(0, -1)`。</p>
 */
export const SIGNAL_SLOT_DIRECTION: Readonly<Record<SignalSlot, readonly [number, number]>> = {
	up: [0, -1],
	right: [1, 0],
	down: [0, 1],
	left: [-1, 0],
};

/**
 * 灯相对节点的方位槽：**按相对位置取主轴**（|dx| ≥ |dz| → 左右，否则上下）。
 *
 * <p>参数一律是世界方块坐标（`x` 与 `z`）—— 故意不接实体对象：`Node` 的平面纵坐标叫
 * `planeZ`、`Signal` 的叫 `planeY`（同一个东西两个名字），传数字就不会有人写错那一个。</p>
 *
 * <p>灯正好落在节点上（dx = dz = 0）时给"上"：当前世界不会出现，但行为必须是确定的，
 * 不能靠比较顺序去碰运气。</p>
 */
export function signalSlot(nodeX: number, nodeZ: number, signalX: number, signalZ: number): SignalSlot {
	const dx = signalX - nodeX;
	const dz = signalZ - nodeZ;
	if (dx === 0 && dz === 0) {
		return "up";
	}
	if (Math.abs(dx) >= Math.abs(dz)) {
		return dx > 0 ? "right" : "left";
	}
	return dz > 0 ? "down" : "up";
}
