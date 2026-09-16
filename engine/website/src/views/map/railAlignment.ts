/*
 * 「地图」页画轨的**对齐规则**，以及"按里程落在轨上"的取点 —— 两个图层共用这一份。
 *
 * <h2>为什么要对齐（这条规则只有一份，别抄第二遍）</h2>
 * <p>引擎的轨有两套写法：**采样点走方块中心**（`n + 0.5`），而**轨的两端是方块角**（节点坐标）。
 * 「地图」页要让线穿过节点圆点，所以按**每条轨自己的首尾偏移**把采样形状对齐到它的两个端点上
 * （首点落到端点1、末点落到端点2、中间按 t 线性分摊）。校正量是**量出来的**（端点 − 采样点），
 * 不是写死的 0.5 格 —— 引擎的约定一变它也跟着变。见 §五 的说明。</p>
 *
 * <h2>为什么车辆图层也要用它</h2>
 * <p>引擎报的车辆位置是"在轨上的**弧长**"（`railArcM / railArcLengthM`），网页就在**自己已经画出来的
 * 那条折线**上按同一个比例取点 —— 于是曲线轨也对得上，而且不用去猜坐标系。取点时补上该处的对齐
 * 校正量，车才会**压在线上**而不是偏半格（用户 2026-09-16 抓到的"信号灯左右分布不均匀"就是这类偏差）。</p>
 */
import type {Rail} from "@/domain/Rail";

/** 一条轨在「地图」页上的样子：对齐后的折线 + 那套校正量。 */
export interface AlignedRail {
	/** 世界坐标（对齐后）——直接喂 `ctx.project()`。 */
	readonly points: readonly (readonly [number, number])[];
	/** 首点/末点的校正量（世界格）：`fix1` 配 t=0、`fix2` 配 t=1，中间线性分摊。 */
	readonly fixX1: number;
	readonly fixZ1: number;
	readonly fixX2: number;
	readonly fixZ2: number;
	/** 引擎给没给采样形状。没给时折线就是两端点（校正量为 0）。 */
	readonly sampled: boolean;
}

/** 把一条轨对齐到它的两个端点上（规则见文件头；与「地图」页的轨道层同一份实现）。 */
export function alignRail(rail: Rail): AlignedRail {
	if (rail.path.length < 2) {
		return {
			points: [[rail.planeX1, rail.planeY1], [rail.planeX2, rail.planeY2]],
			fixX1: 0, fixZ1: 0, fixX2: 0, fixZ2: 0,
			sampled: false,
		};
	}
	const first = rail.path[0]!;
	const last = rail.path[rail.path.length - 1]!;
	const fixX1 = rail.planeX1 - first.x;
	const fixZ1 = rail.planeY1 - first.z;
	const fixX2 = rail.planeX2 - last.x;
	const fixZ2 = rail.planeY2 - last.z;
	const lastIndex = rail.path.length - 1;
	return {
		points: rail.path.map((sample, index) => {
			const t = index / lastIndex;
			return [
				sample.x + fixX1 * (1 - t) + fixX2 * t,
				sample.z + fixZ1 * (1 - t) + fixZ2 * t,
			] as const;
		}),
		fixX1, fixZ1, fixX2, fixZ2,
		sampled: true,
	};
}

/**
 * 折线上按**弧长比例** `t` 取点（用累计长度插值，不是按"第几个点" —— 采样点在曲线段上疏密不均，
 * 按点号取会让车在长段上跑得偏快）。
 */
export function pointAtLengthFraction(points: readonly (readonly [number, number])[], t: number): readonly [number, number] {
	if (points.length === 0) {
		return [0, 0];
	}
	if (points.length === 1) {
		return points[0]!;
	}
	const cumulative: number[] = [0];
	for (let i = 1; i < points.length; i++) {
		cumulative.push(cumulative[i - 1]! + Math.hypot(points[i]![0] - points[i - 1]![0], points[i]![1] - points[i - 1]![1]));
	}
	const total = cumulative[cumulative.length - 1]!;
	if (total <= 0) {
		return points[0]!;
	}
	const target = Math.max(0, Math.min(1, t)) * total;
	for (let i = 1; i < points.length; i++) {
		if (cumulative[i]! >= target) {
			const segment = cumulative[i]! - cumulative[i - 1]!;
			const local = segment <= 0 ? 0 : (target - cumulative[i - 1]!) / segment;
			return [
				points[i - 1]![0] + (points[i]![0] - points[i - 1]![0]) * local,
				points[i - 1]![1] + (points[i]![1] - points[i - 1]![1]) * local,
			];
		}
	}
	return points[points.length - 1]!;
}
