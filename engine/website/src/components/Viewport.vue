<script setup lang="ts">
import {computed, onMounted, ref} from "vue";

/*
 * 极简视口：一块可平移/缩放的画布。**坐标语义只有一条**——世界坐标，交给 SVG 的 viewBox 映射。
 *
 * 为什么用 viewBox 而不是自己算像素：
 * 之前用"平面层 + 每个元素自己乘 scale 再减 pan"的写法，父组件和视口各算一半，
 * 坐标空间（相对视口中心 / 相对平面层）搞错一次就整体偏移半个视口，实测反复踩坑。
 * viewBox 把所有映射收敛到一处：内容按世界坐标摆放，平移改 viewBox.x/y，缩放改 viewBox 宽高，
 * 屏幕位置由 SVG 负责——调用方永远不用做坐标换算。
 *
 * 交互：拖动平移、滚轮以光标为锚点缩放；`reset()` 回到取景全部内容。
 */

const props = defineProps<{
	/** 世界坐标下的内容包围盒：viewBox = (minX, minY, width, height)。 */
	worldMinX: number;
	worldMinY: number;
	worldWidth: number;
	worldHeight: number;
	/** 取景留白（世界单位；按内容尺寸的百分比理解更稳，所以这里用比例）。 */
	paddingRatio?: number;
	/** 缩小/放大倍率上下限。 */
	minScale?: number;
	maxScale?: number;
}>();

const host = ref<SVGSVGElement>();
/** 当前视图（世界坐标）：viewBox 就是它。 */
const viewMinX = ref(0);
const viewMinY = ref(0);
const viewWidth = ref(1);
const viewHeight = ref(1);
/** 取景后的基准，用于 reset 与缩放上限判断。 */
const baseWidth = ref(1);
const baseHeight = ref(1);
const dragging = ref(false);

let dragStart = {clientX: 0, clientY: 0, minX: 0, minY: 0};

/** 视图状态快照（响应式）：翻译成 viewBox 字符串与缩放倍率给调用方用。 */
const view = computed(() => {
	const aspect = props.worldWidth > 0 && props.worldHeight > 0 ? props.worldWidth / props.worldHeight : 1;
	return {
		viewBox: `${viewMinX.value} ${viewMinY.value} ${viewWidth.value} ${viewHeight.value}`,
		scale: baseWidth.value > 0 ? baseWidth.value / viewWidth.value : 1,
		aspect,
	};
});

/** 把整张图放进视口：viewBox 盖住内容包围盒并留出 padding。 */
function fit() {
	const padding = props.paddingRatio ?? 0.06;
	const padX = props.worldWidth * padding;
	const padY = props.worldHeight * padding;
	viewMinX.value = props.worldMinX - padX;
	viewMinY.value = props.worldMinY - padY;
	viewWidth.value = Math.max(1e-3, props.worldWidth + padX * 2);
	viewHeight.value = Math.max(1e-3, props.worldHeight + padY * 2);
	baseWidth.value = viewWidth.value;
	baseHeight.value = viewHeight.value;
}

/** 缩放到指定倍率（相对取景基准），锚点为屏幕像素点。 */
function zoomAt(clientX: number, clientY: number, factor: number) {
	const rect = host.value?.getBoundingClientRect();
	if (!rect) {
		return;
	}
	const nextWidth = viewWidth.value / factor;
	const scale = baseWidth.value / nextWidth;
	if (scale < (props.minScale ?? 0.05) || scale > (props.maxScale ?? 200)) {
		return;
	}
	// 锚点在世界坐标里的位置保持不变
	const anchorX = viewMinX.value + ((clientX - rect.left) / rect.width) * viewWidth.value;
	const anchorY = viewMinY.value + ((clientY - rect.top) / rect.height) * viewHeight.value;
	const ratio = nextWidth / viewWidth.value;
	viewMinX.value = anchorX - (anchorX - viewMinX.value) * ratio;
	viewMinY.value = anchorY - (anchorY - viewMinY.value) * ratio;
	viewWidth.value = nextWidth;
	viewHeight.value = viewHeight.value * ratio;
}

function onWheel(event: WheelEvent) {
	event.preventDefault();
	zoomAt(event.clientX, event.clientY, event.deltaY < 0 ? 1.12 : 1 / 1.12);
}

function onPointerDown(event: PointerEvent) {
	if (event.button !== 0 && event.button !== 1) {
		return;
	}
	dragging.value = true;
	dragStart = {clientX: event.clientX, clientY: event.clientY, minX: viewMinX.value, minY: viewMinY.value};
	host.value?.setPointerCapture(event.pointerId);
}

function onPointerMove(event: PointerEvent) {
	if (!dragging.value) {
		return;
	}
	const rect = host.value?.getBoundingClientRect();
	if (!rect || rect.width <= 0) {
		return;
	}
	// 屏幕像素位移 → 世界坐标位移
	viewMinX.value = dragStart.minX - (event.clientX - dragStart.clientX) * (viewWidth.value / rect.width);
	viewMinY.value = dragStart.minY - (event.clientY - dragStart.clientY) * (viewHeight.value / rect.height);
}

function onPointerUp(event: PointerEvent) {
	dragging.value = false;
	if (host.value?.hasPointerCapture(event.pointerId)) {
		host.value.releasePointerCapture(event.pointerId);
	}
}

/**
 * 把视口中心对准世界坐标上的某一点（"居中到这里"）：只改 viewBox 的原点。
 */
function centerOn(worldX: number, worldY: number) {
	viewMinX.value = worldX - viewWidth.value / 2;
	viewMinY.value = worldY - viewHeight.value / 2;
}

onMounted(() => {
	fit();
});

defineExpose({view, fit, zoomAt, centerOn});
</script>

<template>
	<svg
		ref="host"
		class="viewport"
		:class="{dragging}"
		:viewBox="view.viewBox"
		preserveAspectRatio="xMidYMid meet"
		@pointerdown="onPointerDown"
		@pointermove="onPointerMove"
		@pointerup="onPointerUp"
		@pointercancel="onPointerUp"
		@wheel="onWheel"
		@contextmenu.prevent
	>
		<!-- 内容全部按世界坐标摆放；映射由 viewBox 负责。 -->
		<slot/>
	</svg>
</template>

<style scoped>
.viewport {
	display: block;
	width: 100%;
	height: 100%;
	min-height: 120px;
	touch-action: none;
	cursor: grab;
	user-select: none;
	background: var(--bg);
}

.viewport.dragging {
	cursor: grabbing;
}
</style>
