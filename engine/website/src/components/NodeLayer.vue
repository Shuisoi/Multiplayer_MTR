<script setup lang="ts">
import type {Node} from "@/domain/Node";
import NodeMarker from "./NodeMarker.vue";

/*
 * 节点层：把节点摆到**世界坐标**上。
 *
 * <p>相机由 `MapCanvas` 的 `.layer`（`translate + scale`）承担，所以这一层只转发数据：
 * 每个节点就是一个**绝对定位的 HTML 元素**，位置直接是它的世界坐标。尺寸（屏幕像素）
 * 由 `NodeMarker` 内部反向缩放得到 —— 位置、尺寸各归一处，这一层不再做任何坐标运算。</p>
 */

defineProps<{
	nodes: readonly Node[];
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
		:hovered="hoveredKey === node.key"
		:menu-open="menuKey === node.key"
		:selected="selectedKey === node.key"
		@hover="emit('hover', $event)"
		@toggle-menu="emit('toggleMenu', $event)"
		@close-menu="emit('closeMenu')"
		@action="emit('action', $event)"
	/>
</template>

