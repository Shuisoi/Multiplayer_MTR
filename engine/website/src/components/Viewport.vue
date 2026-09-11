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
	/** 取景时内容占视口较短边的比例（0.9 = 四周留 10%）。 */
	fillRatio?: number;
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

/**
 * 取景：让内容"填满"视口（内容占较短边的 `fill`），并保持 viewBox 与容器同比例。
 *
 * <p>两个要点，都是踩出来的：</p>
 * <ol>
 *   <li><b>viewBox 宽高比 = 容器宽高比</b>。`preserveAspectRatio="meet"` 是等比缩放，
 *       若 viewBox 比例与容器不同，两轴的实际比例就不一样；拖动/缩放若按"每轴各自的比例"换算，
 *       纵向会用到一个被等比缩放抛弃的假比例（实测 495 vs 13.9），表现就是"左右能拖、上下拖不动"。</li>
 *   <li><b>内容要填满，不能只保证"装得下"</b>。只按内容尺寸取景时，一个 80×1 的单行节点在
 *       1244×554 的容器里会被缩得很小、拖动一像素就跨过好几个内容宽度（实测 200px 拖动 = 2777 世界单位，
 *       节点直接飞出屏幕）。按"占满较短边"取景后，拖动距离与内容尺寸同量级，手感正常。</li>
 * </ol>
 */
function fit() {
	const fill = props.fillRatio ?? 0.9;
	const contentWidth = Math.max(1e-3, props.worldWidth);
	const contentHeight = Math.max(1e-3, props.worldHeight);

	const rect = host.value?.getBoundingClientRect();
	const containerAspect = rect && rect.width > 0 && rect.height > 0 ? rect.width / rect.height : contentWidth / contentHeight;

	/*
	 * 一个比例同时满足三件事：
	 *   1. viewBox 与容器同比例（等比缩放才两轴一致）；
	 *   2. 内容在较短边方向占 `fill`（内容太扁时由高度决定）；
	 *   3. 内容完整可见（另一个方向按比例补足）。
	 */
	const scale = fill / Math.max(contentWidth / contentHeight, containerAspect) / contentHeight;
	const viewW = contentWidth * scale;
	const viewH = contentHeight * scale;

	viewMinX.value = props.worldMinX + props.worldWidth / 2 - viewW / 2;
	viewMinY.value = props.worldMinY + props.worldHeight / 2 - viewH / 2;
	viewWidth.value = viewW;
	viewHeight.value = viewH;
	baseWidth.value = viewW;
	baseHeight.value = viewH;
}

/** 屏幕像素 → 世界单位的统一比例（等比缩放下两轴相同）。 */
function worldPerPixel(rect: DOMRect) {
	if (rect.width <= 0 || rect.height <= 0) {
		return 1;
	}
	return Math.min(rect.width / viewWidth.value, rect.height / viewHeight.value);
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
	// 锚点在世界坐标里的位置保持不变（viewBox 与容器同比例，所以两轴用同一个比例）
	const perPixel = worldPerPixel(rect);
	const anchorX = viewMinX.value + (clientX - rect.left) * perPixel;
	const anchorY = viewMinY.value + (clientY - rect.top) * perPixel;
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
	// 屏幕像素位移 → 世界坐标位移。viewBox 与容器同比例，所以两轴用同一个比例：
	// 早期按"每轴各自的比例"算，纵向用了被等比缩放抛弃的假比例，导致上下拖不动。
	const perPixel = worldPerPixel(rect);
	viewMinX.value = dragStart.minX - (event.clientX - dragStart.clientX) * perPixel;
	viewMinY.value = dragStart.minY - (event.clientY - dragStart.clientY) * perPixel;
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

/*
 * 诊断：`?viewportDebug=1` 时把取景的输入与输出挂出来。
 * 取景这类"算出来不对但看不出哪一步错"的问题，只看 viewBox 猜不出来，得能看到输入。
 */
if (typeof window !== "undefined" && window.location.search.includes("viewportDebug")) {
	(window as unknown as {__viewportFit: unknown}).__viewportFit = () => ({
		props: {worldMinX: props.worldMinX, worldMinY: props.worldMinY, worldWidth: props.worldWidth, worldHeight: props.worldHeight, fillRatio: props.fillRatio},
		rect: host.value ? {width: host.value.clientWidth, height: host.value.clientHeight} : null,
		viewBox: `${viewMinX.value} ${viewMinY.value} ${viewWidth.value} ${viewHeight.value}`,
	});
}
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
