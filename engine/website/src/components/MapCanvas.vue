<script setup lang="ts">
import {computed, nextTick, provide, ref, useTemplateRef, watch} from "vue";
import {contentBounds, useCameraView} from "@/composables/useCameraView";
import type {Camera} from "@/domain/camera";
import type {Node} from "@/domain/Node";
import {CAMERA} from "@/views/mapContext";
import NodeLayer from "./NodeLayer.vue";

/*
 * 地图画布：视口 + 摄像机 + 内容层。
 *
 * 两层从下到上：
 *   1. 点阵背景（纯装饰，不随摄像机变，给人"有地方可以拖"的感觉）；
 *   2. 节点层 `NodeLayer`（**普通 HTML**，节点圆点、悬停信息卡、左键操作菜单）。
 *
 * **不画连线**（用户明确要求）：节点之间不画轨、也不画股道刻度，节点本身就是全部内容。
 *
 * 为什么节点不画在 SVG 里：见 `domain/camera.ts` 顶部。现在世界坐标只由 `toScreen()` 换算一次，
 * 不存在 `viewBox` + `preserveAspectRatio` + `foreignObject` 三方对账的问题。
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
 * 节点集合变化时重新取景。
 *
 * <p>用 watch 而不是指望 `setContent()`：数据是从接口拿的，会晚于挂载到达，
 * 而且用户可能已经拖过视图（那样 `touched` 会挡住自动取景）。节点列表变了就是"新数据"，
 * 这时候重新取景是唯一合理的行为——否则新节点落在视野外，看起来像"没数据"。</p>
 *
 * <p>空列表要跳过：那时候包围盒是退化的 1×1，取景会把比例算到极大（实测 ~15 px/单位，
 * 之后真实数据的倍率读数就成了 0.02×）。</p>
 */
watch(() => props.nodes, async () => {
	if (props.nodes.length === 0) {
		return;
	}
	await nextTick();
	view.resetTouched();
	view.fit();
}, {immediate: true});
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
