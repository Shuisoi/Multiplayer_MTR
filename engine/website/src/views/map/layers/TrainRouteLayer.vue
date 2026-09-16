<script setup lang="ts">
/*
 * 「地图」页图层：**选中那列车的进路（"路线图叠加层"）**。
 *
 * <h2>画什么</h2>
 * <p>用户 2026-09-16：「游戏内的列车都是通过任务驱动的，让列车图标能够点击，点击后高亮并提示任务目标
 * **还有路线图叠加层**。」—— 点中一列车之后，把它**当前进路**要走的那些轨用主题强调色压在原来的
 * 灰轨上（`/mmtr-trains` 的 `route.rails[]`，联锁实际排的那条路，不是"应该走哪条"）。</p>
 * <p>没选中、或者选中的车现在没有进路（`route` 这个键在引擎里就不存在）⇒ 什么也不画。</p>
 *
 * <h2>几何必须与基础图层逐点相同</h2>
 * <p>叠加层绝**不能**自己重算轨的形状：轨在图上是 `alignRail()` 对齐过端点的折线（引擎的采样走
 * 方块中心，见 `railAlignment.ts`），少这一步整条线就会偏半格 —— 那正是用户抓到的
 * "信号灯左右分布不均匀"同一个坑。所以这里按**规范 hex 键**去基础图层同一份对齐结果里查，
 * 查不到就不画（宁可少画一根，也不画一根位置不对的线）。</p>
 *
 * <h2>叠放与颜色</h2>
 * <p>它紧跟在基础图层之后（在信号灯与车辆**下面**）：进路是"这条轨属于哪条路"的信息，
 * 不该盖住灯与车。颜色用主题强调色 —— 与"选中的那列车"同一个颜色，一眼能对上。
 * 线宽比轨道线细一点（0.7 格 vs 1 格），于是底下的灰轨还在，看得清它压在哪根轨上。</p>
 */
import {computed} from "vue";
import {railHexKey} from "@/domain/MapNode";
import {hasRoute} from "@/domain/Train";
import {UNITS_PER_BLOCK, useMapContext} from "../mapContext";
import {useLiveFeeds} from "../liveFeeds";
import {alignRail} from "../railAlignment";
import {selectedTrainId} from "../trainSelection";

/** 进路线宽（画布单位）：0.7 格 —— 比基础图层的轨道线（1 格）细，底下那根灰轨还看得见。 */
const ROUTE_WIDTH = UNITS_PER_BLOCK * 0.7;

const ctx = useMapContext();
const feeds = useLiveFeeds();

/** 选中那列车的进路：`rails[]` 的规范键 → 基础图层同一份对齐折线的点串。 */
const routeLines = computed<readonly {readonly key: string; readonly points: string}[]>(() => {
	ctx.anchor.value;
	const id = selectedTrainId.value;
	if (id === null) {
		return [];
	}
	const train = feeds.trains.value.find(candidate => candidate.vehicleId === id);
	if (!train || !hasRoute(train)) {
		return [];
	}
	const alignedByKey = new Map<string, string>();
	for (const rail of feeds.rails.value) {
		alignedByKey.set(railHexKey(rail.hex), alignRail(rail).points.map(([x, z]) => ctx.project(x, z).join(",")).join(" "));
	}
	return train.routeRailKeys
		.map(key => ({key, points: alignedByKey.get(key) ?? ""}))
		.filter(line => line.points !== "");
});
</script>

<template>
	<polyline
		v-for="line in routeLines"
		:key="line.key"
		class="train-route"
		:points="line.points"
		:stroke-width="ROUTE_WIDTH"
	/>
</template>

<style scoped>
/* 进路：主题强调色，压在灰轨上（与"选中的那列车"同一个颜色） */
.train-route {
	fill: none;
	stroke: var(--accent);
	stroke-linejoin: round;
	stroke-linecap: round;
	pointer-events: none;
}
</style>
