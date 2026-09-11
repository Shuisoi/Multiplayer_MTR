<script setup lang="ts">
import {computed, provide, ref, useTemplateRef, watch} from "vue";
import {contentBounds, useCameraView} from "@/composables/useCameraView";
import type {Camera} from "@/domain/camera";
import type {Node} from "@/domain/Node";
import {CAMERA} from "@/views/mapContext";
import RailLayer from "./RailLayer.vue";
import NodeLayer from "./NodeLayer.vue";

/*
 * 地图画布：视口 + 摄像机 + 三层内容。
 *
 * 三层从下到上：
 *   1. 点阵背景（纯装饰，不随摄像机变，给人"有地方可以拖"的感觉）；
 *   2. 轨道层 `RailLayer`（SVG，画节点之间的轨 + 从节点伸出的股道刻度）；
 *   3. 节点层 `NodeLayer`（**普通 HTML**，节点圆点、悬停信息卡、左键操作菜单）。
 *
 * 为什么节点不画在 SVG 里：见 `domain/camera.ts` 顶部。现在世界坐标只由 `toScreen()` 换算一次，
 * SVG 与 HTML 都用同一个投影，不再有 `viewBox` + `preserveAspectRatio` + `foreignObject` 三方对账。
 *
 * 交互状态（悬停 / 选中 / 菜单）由这里持有，节点组件只负责显示与上报，
 * 所以"菜单跟着节点走"是自动的：菜单是节点元素的子元素，节点动它就动。
 */

const props = defineProps<{
	/** 要显示的节点（世界坐标在 `Node.planeX / planeZ`）。 */
	nodes: readonly Node[];
}>();

const host = useTemplateRef<HTMLElement>("host");
const camera = ref<Camera>({originX: 0, originY: 0, scale: 1});

/** 内容包围盒：所有节点位置，外扩一点留白。 */
const content = computed(() => contentBounds(
	props.nodes.map(node => ({x: node.planeX, y: node.planeZ})),
	12,
));

const view = useCameraView({host, camera, content});
provide(CAMERA, camera);

/** 悬停中的节点 key（信息卡）。 */
const hoveredKey = ref("");
/** 打开了操作菜单的节点 key。 */
const menuKey = ref("");
/** 选中的节点 key（菜单动作后保持高亮）。 */
const selectedKey = ref("");

const emit = defineEmits<{
	/** 节点操作菜单被点了某一项。 */
	(e: "action", payload: {node: Node; action: string}): void;
	/** 视图变化（取景 / 平移 / 缩放），供外部显示比例等读数。 */
	(e: "camera", camera: Camera): void;
}>();

/*
 * 摄像机一变就上报。用 watch 而不是在每次改摄像机的地方手动 emit：
 * 平移、缩放、取景、居中四条路径都会写 camera，逐个 emit 迟早漏一条。
 */
watch(camera, value => emit("camera", value), {deep: true});

function onHover(key: string) {
	hoveredKey.value = key;
}

function onToggleMenu(key: string) {
	menuKey.value = menuKey.value === key ? "" : key;
	selectedKey.value = menuKey.value;
}

function onCloseMenu() {
	menuKey.value = "";
}

function onAction(payload: {node: Node; action: string}) {
	menuKey.value = "";
	selectedKey.value = payload.node.key;
	emit("action", payload);
}

/** 点空白处：关菜单、取消选中。 */
function onBackgroundDown(event: PointerEvent) {
	if (event.target === host.value) {
		menuKey.value = "";
		selectedKey.value = "";
	}
}

defineExpose({
	/** 视图命令，供 HUD 与节点菜单用。 */
	fit: view.fit,
	centerOnWorld: view.centerOnWorld,
	zoom: view.zoom,
});
</script>

<template>
	<div
		ref="host"
		class="map"
		:class="{dragging: view.dragging.value}"
		@pointerdown="view.onPointerDown"
		@pointerdown.capture="onBackgroundDown"
		@pointermove="view.onPointerMove"
		@pointerup="view.onPointerUp"
		@pointercancel="view.onPointerUp"
		@wheel="view.onWheel"
		@contextmenu.prevent
	>
		<div class="grid" aria-hidden="true"/>

		<!--
			轨道层：SVG，**故意不设 viewBox**。
			SVG 的用户单位默认就是 CSS 像素，所以这里的坐标可以直接写屏幕坐标，1 单位 = 1px。
			一旦给了 viewBox 就会引入又一次缩放映射（以及 preserveAspectRatio 的第二套对账），
			这正是上一版三次翻车的来源，所以这里连机会都不留。
		-->
		<svg class="rails">
			<RailLayer :nodes="nodes" :camera="camera"/>
		</svg>

		<!-- 节点层：普通 HTML。这一层整体不吃事件，只有节点自己吃。 -->
		<div class="nodes">
			<NodeLayer
				:nodes="nodes"
				:camera="camera"
				:hovered-key="hoveredKey"
				:menu-key="menuKey"
				:selected-key="selectedKey"
				@hover="onHover"
				@toggle-menu="onToggleMenu"
				@close-menu="onCloseMenu"
				@action="onAction"
			/>
		</div>

		<slot/>
	</div>
</template>

<style scoped>
.map {
	position: relative;
	width: 100%;
	height: 100%;
	overflow: hidden;
	touch-action: none;
	cursor: grab;
	user-select: none;
	background: var(--bg);
}

.map.dragging {
	cursor: grabbing;
}

/*
 * 点阵背景：纯 CSS，不动、不随摄像机。间距固定 24px，作用是让"可以拖动"这件事看得出来。
 * 用 radial-gradient 画点比生成一堆 DOM 便宜得多，也不会影响命中测试。
 */
.grid {
	position: absolute;
	inset: 0;
	background-image: radial-gradient(circle, rgba(255, 255, 255, 0.05) 1px, transparent 1px);
	background-size: 24px 24px;
	pointer-events: none;
}

.rails {
	position: absolute;
	inset: 0;
	width: 100%;
	height: 100%;
	pointer-events: none;
}

/*
 * 节点层：整体 `pointer-events: none`，只让节点自己接收事件。
 * 这样"点空白处拖动/关菜单"不会被这一层挡住——上一版把交互靶塞进 foreignObject 时，
 * 整层都是命中区，空白处点不下去。
 */
.nodes {
	position: absolute;
	inset: 0;
	pointer-events: none;
}
</style>
