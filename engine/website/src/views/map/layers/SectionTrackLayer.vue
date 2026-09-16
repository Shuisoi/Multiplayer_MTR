<script setup lang="ts">
/*
 * 区间地图的**轨道层**：轨道线（引擎原始形状 + 道岔状态淡出）。这一页**不画轨道节点**。
 *
 * <h2>与"地图"页的一点不同</h2>
 * <p>**轨按引擎的原始形状画，不做"对齐到节点"**：区间的采样点与拓扑 `path` 是同一套坐标
 * （实测偏离 0 格），这一页没有节点圆点要去穿，两边都用原始形状，区间带与切点自然落在线上。
 * "地图"页那一步对齐是为了让线穿过节点圆点，是那一页自己的需要。</p>
 *
 * <h2>道岔没指向的那根出口：和「地图」页一样淡一半</h2>
 * <p>用的是**同一个** {@link prohibitedRailHexes}（哪根被切掉是引擎在 `mmtr-points` 每行给的
 * `prohibited`，网页不自己算几何）。所以两页的淡出规则永远一致，不会一处一个说法。</p>
 *
 * <h2>锚点</h2>
 * <p>这一页的基础图层是它，所以要由它把世界范围报给框架（`ctx.setAnchor`，只有第一次生效）——
 * 用**节点云中心**，与「地图」页同一个算法，这样两张图的取景一致。</p>
 */
import {computed, watch} from "vue";
import {prohibitedRailHexes, type MapNode} from "@/domain/MapNode";
import {UNITS_PER_BLOCK, useMapContext} from "../mapContext";
import {useLiveFeeds} from "../liveFeeds";

/** 轨道线宽，画布单位：1 格（一根轨就是一个方块宽）。 */
const RAIL_WIDTH = UNITS_PER_BLOCK;
/** 道岔没指向的那根出口线的透明度（与「地图」页同一个数）。 */
const PROHIBITED_RAIL_OPACITY = 0.5;

const ctx = useMapContext();
/*
 * 数据来自页面的活数据 store（`../liveFeeds.ts`）：轨是静态的那份，道岔（`prohibited` → 淡出）
 * 每拍换一次 —— 这一层不再自己取数。
 */
const feeds = useLiveFeeds();
const rails = feeds.rails;
const mapNodes = feeds.mapNodes;

/** 轨（引擎原始形状，按道岔状态淡出），落进框架的画布坐标系。 */
const scene = computed<readonly {key: string; points: string; opacity: number}[]>(() => {
	ctx.anchor.value;
	const prohibited = prohibitedRailHexes(mapNodes.value);
	return rails.value.map(rail => {
		// 有采样形状就用它（33 点，弧线才画得准）；老引擎没给时退回两端点。
		const world = rail.path.length >= 2
			? rail.path.map(sample => [sample.x, sample.z] as const)
			: [[rail.planeX1, rail.planeY1] as const, [rail.planeX2, rail.planeY2] as const];
		return {
			key: rail.hex,
			points: world.map(([x, z]) => ctx.project(x, z).join(",")).join(" "),
			// 道岔没指向的那根出口：整根淡一半（引擎说它现在禁止通行）
			opacity: prohibited.has(rail.hex) ? PROHIBITED_RAIL_OPACITY : 1,
		};
	});
});

/*
 * 锚点：只有第一次生效（框架里冻结），但派生节点每拍都会重算 —— 所以用 watch 报，报的是最新那朵节点云。
 */
watch(mapNodes, value => {
	reportAnchor(value);
}, {immediate: true});

/**
 * 把**节点云中心**报给框架当锚点（只有第一次生效，之后冻结）——与「地图」页同一个算法，
 * 于是两张图的取景一致（同一块世界落在同一个位置）。
 */
function reportAnchor(nodes: readonly MapNode[]): void {
	if (nodes.length === 0) {
		return;
	}
	const xs = nodes.map(({node}) => node.planeX);
	const zs = nodes.map(({node}) => node.planeZ);
	ctx.setAnchor((Math.min(...xs) + Math.max(...xs)) / 2, (Math.min(...zs) + Math.max(...zs)) / 2);
}
</script>

<template>
	<polyline
		v-for="rail in scene"
		:key="rail.key"
		class="rail"
		:points="rail.points"
		:stroke-width="RAIL_WIDTH"
		:stroke-opacity="rail.opacity"
	/>
</template>

<style scoped>
/*
 * 尺寸一律在画布坐标系里定（见文件头常量），CSS 只管颜色。
 * 线宽是尺寸，所以走模板上的 `:stroke-width`，不写在这里（免得两处各写一个数）。
 */
.rail {
	fill: none;
	stroke: var(--fg-dim);
	stroke-linejoin: round;
	stroke-linecap: round;
}
</style>
