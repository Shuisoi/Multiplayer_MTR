<script setup lang="ts">
import type {Camera} from "@/domain/camera";
import type {Signal} from "@/domain/Signal";
import SignalMarker from "./SignalMarker.vue";

/*
 * 信号灯层：把每盏灯摆出来。
 *
 * <p>灯**绑在节点上**（用户 2026-09-15："信号灯，道岔是绑定在节点上的，直接固定显示在节点旁
 * 不行吗？不要掺活实际坐标进来"）。所以这一层只转发两样东西：节点的坐标表、
 * 以及"这盏灯属于哪个节点"的匹配函数 —— 位置由标记组件按"节点 + 固定像素偏移"算。</p>
 */

defineProps<{
	signals: readonly Signal[];
	/** 当前相机：透传给标记（它们用它把屏幕像素规格折成世界单位）。 */
	camera: Camera;
	/** 节点键 → 平面坐标：灯的锚点从它取（灯绑在节点上）。 */
	nodePlane: ReadonlyMap<string, {x: number; y: number}>;
	/** 每盏灯绑定的节点键（`MapCanvas` 用"离它最近的节点"匹配出来的）。 */
	nodeKeyOf: (signal: Signal) => string;
	hoveredKey: string;
	/** 正在改绑定的那盏灯（点选绑定）：它会被强调出来。 */
	selectedKey: string;
}>();

const emit = defineEmits<{
	(e: "hover", key: string): void;
	(e: "pick", signal: Signal): void;
	(e: "copy", signal: Signal): void;
	(e: "why", signal: Signal): void;
}>();
</script>

<template>
	<SignalMarker
		v-for="signal in signals"
		:key="signal.key"
		:signal="signal"
		:camera="camera"
		:node-plane="nodePlane"
		:node-key="nodeKeyOf(signal)"
		:hovered="hoveredKey === signal.key"
		:selected="selectedKey === signal.key"
		@hover="emit('hover', $event)"
		@pick="emit('pick', signal)"
		@copy="emit('copy', signal)"
		@why="emit('why', signal)"
	/>
</template>
