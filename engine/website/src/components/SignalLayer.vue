<script setup lang="ts">
import {worldToScreen, type Camera} from "@/domain/camera";
import type {Signal} from "@/domain/Signal";
import SignalMarker from "./SignalMarker.vue";

/*
 * 信号灯层：把带灯的节点摆到屏幕坐标上。
 *
 * <p>每个信号灯是一个绝对定位的 HTML 元素（与节点层同一套做法）：位置由 `worldToScreen()` 从世界坐标
 * 直接算出，尺寸是屏幕像素，缩放只影响它落在哪里。悬停出信息卡（状态 / 朝向 / 灯位 / 模式 / 是否接入闭塞层）。</p>
 */

defineProps<{
	signals: readonly Signal[];
	camera: Camera;
	hoveredKey: string;
}>();

const emit = defineEmits<{
	(e: "hover", key: string): void;
}>();
</script>

<template>
	<SignalMarker
		v-for="signal in signals"
		:key="signal.key"
		:signal="signal"
		:screen="worldToScreen(camera, signal.planeX, signal.planeY)"
		:hovered="hoveredKey === signal.key"
		@hover="emit('hover', $event)"
	/>
</template>
