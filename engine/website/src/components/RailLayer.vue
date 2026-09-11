<script setup lang="ts">
import {computed} from "vue";
import {worldToScreen, type Camera} from "@/domain/camera";
import type {Node} from "@/domain/Node";

/*
 * 轨道层：节点之间的轨，以及从每个节点朝各条股道伸出的短刻度。
 *
 * <p>画在 SVG 里、用**屏幕坐标**：SVG 元素不做 `viewBox` 缩放（容器的 CSS 像素就是它的用户单位），
 * 所以线宽就是 1.5px，不会随缩放变粗变细。世界→屏幕的换算全部来自 `worldToScreen()`，
 * 与节点层用的是同一份摄像机。</p>
 *
 * <p>刻度是这批示例数据的"骨架可视化"：每个节点朝每个邻居的方向伸 16px。
 * 真实数据接入后，刻度会换成真正的轨几何（引擎的 `rails` 数组），但投影方式不变。</p>
 */

const props = defineProps<{
	nodes: readonly Node[];
	camera: Camera;
}>();

/** 屏幕坐标下的节点位置：key → {x, y}。 */
const screenPositions = computed(() => {
	const map = new Map<string, {x: number; y: number}>();
	for (const node of props.nodes) {
		map.set(node.key, worldToScreen(props.camera, node.planeX, node.planeZ));
	}
	return map;
});

/**
 * 轨：每个"节点 → 邻居"对画一条线，用排序后的 key 去重（同一条轨会被两端各报一次，
 * 而两端报的 `rail` id 并不保证相同，所以不能按 rail id 去重）。
 */
const rails = computed(() => {
	const seen = new Set<string>();
	const lines: {id: string; x1: number; y1: number; x2: number; y2: number}[] = [];
	for (const node of props.nodes) {
		const from = screenPositions.value.get(node.key);
		if (!from) {
			continue;
		}
		for (const neighbour of node.neighbours) {
			const toKey = `${neighbour.x},${neighbour.y},${neighbour.z}`;
			const pair = node.key < toKey ? `${node.key}|${toKey}` : `${toKey}|${node.key}`;
			if (seen.has(pair)) {
				continue;
			}
			seen.add(pair);
			const to = screenPositions.value.get(toKey);
			if (!to) {
				// 邻居不在当前数据集里：跳过。真实数据里这很常见（相邻节点被筛掉了），
				// 所以这里不是错误，静默略过即可。
				continue;
			}
			lines.push({id: pair, x1: from.x, y1: from.y, x2: to.x, y2: to.y});
		}
	}
	return lines;
});

/**
 * 股道刻度：每个节点朝每个邻居方向伸出的短线。
 *
 * <p>方向在世界坐标里算、再投影到屏幕上求角度——如果直接拿屏幕坐标算角度，
 * 缩放方向不同时角度是对的，但我不想依赖"缩放是等比的"这个前提，所以在世界坐标里算更稳。</p>
 */
const ticks = computed(() => {
	const length = 16;
	const result: {id: string; x1: number; y1: number; x2: number; y2: number}[] = [];
	for (const node of props.nodes) {
		const from = screenPositions.value.get(node.key);
		if (!from) {
			continue;
		}
		node.neighbours.forEach((neighbour, index) => {
			const dx = neighbour.x - node.x;
			const dy = -(neighbour.z - node.z);
			const length2 = Math.hypot(dx, dy);
			// 邻居与自己在平面图上重合（垂直线路）时给一个朝上的方向，避免除零后画到 NaN 坐标上。
			const ux = length2 > 1e-6 ? dx / length2 : 0;
			const uy = length2 > 1e-6 ? dy / length2 : -1;
			result.push({
				id: `${node.key}#${index}`,
				x1: from.x,
				y1: from.y,
				x2: from.x + ux * length,
				y2: from.y + uy * length,
			});
		});
	}
	return result;
});
</script>

<template>
	<g class="rails">
		<!-- 轨：节点之间 -->
		<line
			v-for="rail in rails"
			:key="rail.id"
			:x1="rail.x1"
			:y1="rail.y1"
			:x2="rail.x2"
			:y2="rail.y2"
			class="rail"
		/>
		<!-- 股道刻度：从节点朝各条腿 -->
		<line
			v-for="tick in ticks"
			:key="tick.id"
			:x1="tick.x1"
			:y1="tick.y1"
			:x2="tick.x2"
			:y2="tick.y2"
			class="tick"
		/>
	</g>
</template>

<style scoped>
.rail {
	stroke: #3a3a3a;
	stroke-width: 1.5;
	stroke-linecap: round;
}

.tick {
	stroke: #4a4a4a;
	stroke-width: 1.5;
	stroke-linecap: round;
}
</style>
