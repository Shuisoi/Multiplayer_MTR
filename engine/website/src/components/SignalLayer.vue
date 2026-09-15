<script setup lang="ts">
import type {Camera} from "@/domain/camera";
import type {Signal} from "@/domain/Signal";
import SignalMarker from "./SignalMarker.vue";

/*
 * 信号灯层：把每盏灯摆出来。
 *
 * <p>这一层**不再自己算屏幕坐标**：位置由标记组件按统一模型（`domain/mapElements.ts`）算 ——
 * 世界坐标 + 固定像素偏移。以前在这一层 `worldToScreen`、在标记里再加偏移，等于"位置"有两个来源，
 * 而两者用的尺子不同就会写出"缩放时相对节点滑走"那类缺陷。</p>
 */

defineProps<{
	signals: readonly Signal[];
	/** 当前相机：只用来做尺寸换算（见 SignalMarker）。 */
	camera: Camera;
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
		:hovered="hoveredKey === signal.key"
		:selected="selectedKey === signal.key"
		@hover="emit('hover', $event)"
		@pick="emit('pick', signal)"
		@copy="emit('copy', signal)"
		@why="emit('why', signal)"
	/>
</template>
