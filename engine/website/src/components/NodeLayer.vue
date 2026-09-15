<script setup lang="ts">
import type {Camera} from "@/domain/camera";
import type {Node} from "@/domain/Node";
import NodeMarker from "./NodeMarker.vue";

/*
 * 节点层：把节点摆到屏幕坐标上。
 *
 * <p>每个节点就是一个**绝对定位的 HTML 元素**，用 `transform: translate(...)` 移动。
 * 位置由 `worldToScreen()` 从世界坐标直接算出，没有中间坐标系、没有反向缩放。
 * 因为节点尺寸是屏幕像素（见 NodeMarker），缩放只影响它落在哪里，不影响它多大——
 * 这正是"节点像地图上的标注"该有的行为。</p>
 */

defineProps<{
	nodes: readonly Node[];
	camera: Camera;
	hoveredKey: string;
	menuKey: string;
	selectedKey: string;
}>();

const emit = defineEmits<{
	(e: "hover", key: string): void;
	(e: "toggleMenu", key: string): void;
	(e: "closeMenu"): void;
	(e: "action", payload: {node: Node; action: string}): void;
}>();
</script>

<template>
	<NodeMarker
		v-for="node in nodes"
		:key="node.key"
		:node="node"
		:camera="camera"
		:hovered="hoveredKey === node.key"
		:menu-open="menuKey === node.key"
		:selected="selectedKey === node.key"
		@hover="emit('hover', $event)"
		@toggle-menu="emit('toggleMenu', $event)"
		@close-menu="emit('closeMenu')"
		@action="emit('action', $event)"
	/>
</template>

