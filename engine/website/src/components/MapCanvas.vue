<script setup lang="ts">
import {computed, nextTick, provide, ref, useTemplateRef, watch} from "vue";
import {useCameraView} from "@/composables/useCameraView";
import {boundsOf} from "@/domain/camera";
import type {Camera} from "@/domain/camera";
import type {Node} from "@/domain/Node";
import type {Rail} from "@/domain/Rail";
import {CAMERA} from "@/views/mapContext";
import RailLayer from "./RailLayer.vue";
import NodeLayer from "./NodeLayer.vue";

/*
 * 地图画布：视口 + 摄像机 + 内容层。
 *
 * 三层从下到上：
 *   1. 点阵背景（纯装饰，不随摄像机变，给人"有地方可以拖"的感觉）；
 *   2. 轨道层 `RailLayer`（SVG，屏幕坐标；同一轴画直线、斜向画圆弧）；
 *   3. 节点层 `NodeLayer`（**普通 HTML**，节点圆点、悬停信息卡、左键操作菜单）。
 *
 * 为什么节点不画在 SVG 里：见 `domain/camera.ts` 顶部。世界坐标只由 `worldToScreen()` 换算一次，
 * 两层共用同一份摄像机，不存在 `viewBox` + `preserveAspectRatio` + `foreignObject` 三方对账。
 *
 * 交互状态（悬停 / 选中 / 菜单）由这里持有，节点组件只负责显示与上报；
 * 轨道层也读同一份状态，所以"悬停节点时与它相连的轨加亮"是自动的。
 */

const props = defineProps<{
	/** 要显示的节点（世界坐标在 `Node.planeX / planeZ`）。 */
	nodes: readonly Node[];
	/** 要显示的轨。 */
	rails: readonly Rail[];
}>();

const host = useTemplateRef<HTMLElement>("host");
const camera = ref<Camera>({originX: 0, originY: 0, scale: 1});

/**
 * 内容包围盒：**节点与轨的全部采样点一起**算。
 *
 * <p>轨的采样点必须算进去：U 型轨的弯折部分会伸出两端点构成的包围盒，只用端点取景的话
 * 弯出去的那一段会被切在视口外。</p>
 *
 * <p>这里**不**做留白：留白是屏幕观感（"内容不要贴边"），所以由 `fitView` 按屏幕像素加，
 * 而不是在这里按世界单位或内容比例加——那两种口径换算成像素都要再乘当前比例，
 * 而比例取决于世界有多大，实测两次都导致边缘内容越界（见 `camera.fitView`）。</p>
 */
const content = computed(() => boundsOf([
	...props.nodes.map(node => ({x: node.planeX, y: node.planeZ})),
	...props.rails.flatMap(rail => [
		{x: rail.planeX1, y: rail.planeY1},
		{x: rail.planeX2, y: rail.planeY2},
		...rail.path.map(point => ({x: point.x, y: -point.z})),
	]),
]));

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
	/**
	 * 视图变化（取景 / 平移 / 缩放）。
	 *
	 * <p>`zoom` 是相对取景基准的倍率，**由这里给出而不是让上层自己算**：
	 * 它依赖"最后一次取景得到的比例"这份状态，而那份状态归摄像机所有。上层自己存一份基准的话，
	 * 一旦在数据到达之前先取了一次景，就会 latch 到那次退化取景的比例（内容框 1×1，比例约 1120），
	 * 从此读数永远是错的——实测显示 0.02× 而画面完全正常。</p>
	 */
	(e: "camera", payload: {camera: Camera; zoom: number}): void;
}>();

/*
 * 摄像机或倍率一变就上报。用 watch 而不是在每次改摄像机的地方手动 emit：
 * 平移、缩放、取景、居中四条路径都会写 camera，逐个 emit 迟早漏一条。
 */
watch([camera, view.zoomRatio], () => emit("camera", {camera: camera.value, zoom: view.zoomRatio.value}), {deep: true});

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

/*
 * 节点集合或轨集合变化时重新取景。
 *
 * <p>用 watch 而不是指望 `setContent()`：数据是从接口拿的，会晚于挂载到达，
 * 而且用户可能已经拖过视图（那样 `touched` 会挡住自动取景）。数据变了就是"新数据"，
 * 这时候重新取景是唯一合理的行为——否则新节点落在视野外，看起来像"没数据"。</p>
 *
 * <p>空列表要跳过：那时候包围盒是退化的 1×1，取景会把比例算到极大（实测 ~15 px/单位，
 * 之后真实数据的倍率读数就成了 0.02×）。</p>
 */
watch([() => props.nodes, () => props.rails], async () => {
	if (props.nodes.length === 0 && props.rails.length === 0) {
		return;
	}
	await nextTick();
	view.resetTouched();
	view.fit();
}, {immediate: true});

/*
 * 诊断：`?cameraDebug=1` 时把"拟合用的内容框"和"算出来的摄像机"一起挂出来。
 * 曾经出现"画面看着正常但边缘越界 20px"，只查摄像机看不出问题——内容框也要能看到
 * （结果是拟合发生在轨数据到达之前，用的内容框偏小，而 watch 当时只盯着节点）。
 */
if (typeof window !== "undefined" && window.location.search.includes("cameraDebug")) {
	(window as unknown as {__mmtrContent: unknown}).__mmtrContent = () => ({
		content: content.value,
		camera: camera.value,
		nodes: props.nodes.length,
		rails: props.rails.length,
		pathPoints: props.rails.reduce((sum, rail) => sum + rail.path.length, 0),
		viewport: {width: view.width.value, height: view.height.value},
	});
}
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
			SVG 的用户单位默认就是 CSS 像素，所以这里可以直接写屏幕坐标，1 单位 = 1px。
			一旦给了 viewBox 就引入又一次缩放映射（以及 preserveAspectRatio 的第二套对账），
			这正是旧版三次翻车的来源，所以这里连机会都不留。
		-->
		<svg class="rails">
			<RailLayer
				:rails="rails"
				:camera="camera"
				:hover-key="hoveredKey"
				:select-key="selectedKey"
			/>
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
	/* 轨道层不参与命中测试：节点的交互不该被线抢走，空白处的拖动也要能穿透到画布。 */
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
