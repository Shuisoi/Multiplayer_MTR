<script setup lang="ts">
import {computed, onBeforeUnmount, ref} from "vue";
import {NPopover} from "naive-ui";
import {Node} from "@/domain/Node";

/*
 * 一个轨道层节点（SVG 世界坐标）。
 *
 * 三件事，按用户要求：
 *   1. 悬停 → 信息卡（坐标、类型、度数、所属区间、相邻轨）；
 *   2. 左键 → 操作菜单；
 *   3. 圆点半径随度数变化（端点细、道岔粗且亮）。
 *
 * 两个踩过的坑，别再犯：
 *   · 事件必须挂在 foreignObject 里的 HTML 靶上（SVG 圆点在上层被盖住，永远收不到 click）；
 *   · 操作菜单**自绘**，不用 Naive UI 的 NDropdown——它的浮层走自己的事件代理，
 *     点不到（实测合成点击与真实点击都不可靠），而且样式要跟 C# 那套对齐反而自绘更省事。
 */

const props = defineProps<{
	node: Node;
	/** 世界坐标。 */
	x: number;
	y: number;
	selected?: boolean;
}>();

const emit = defineEmits<{
	(e: "action", payload: {node: Node; action: string}): void;
}>();

/** 圆点半径（世界单位）。 */
const radius = computed(() => (props.node.degree <= 1 ? 0.9 : (props.node.degree === 2 ? 1.3 : 1.9)));
/** 交互靶尺寸（世界单位）。 */
const hitSize = computed(() => radius.value * 3);

const kindText = computed(() => props.node.kindText);
const blockText = computed(() => {
	if (props.node.isUnguardedBlock) {
		return "无灯区间（无人看守）";
	}
	return props.node.block === "" ? "未知" : `灯 ${props.node.block}`;
});
const neighbourRows = computed(() => props.node.neighbours.map(neighbour => ({
	rail: Node.shortHex(neighbour.rail),
	to: `${neighbour.x}, ${neighbour.y}, ${neighbour.z}`,
	length: Math.round(props.node.distanceTo(neighbour)),
})));

interface MenuItem {
	key: string;
	label: string;
	divider?: boolean;
}

const menuItems = computed<MenuItem[]>(() => [
	{key: "center", label: "居中到这里"},
	{key: "block", label: "查看所属区间"},
	{key: "divider1", label: "", divider: true},
	{key: "copy", label: "复制坐标"},
	{key: "neighbors", label: "复制相邻轨"},
	...(props.node.isFork
		? [{key: "divider2", label: "", divider: true}, {key: "fork", label: "查看道岔（进向与腿）"}]
		: []),
]);

const menuOpen = ref(false);

/**
 * 左键：开/关操作菜单。
 *
 * <p>用 `pointerdown` 而不是 `click`：视口本身在 `pointerdown` 里开始拖动，
 * 先按下再抬起时若手指有位移会被判成拖拽，`click` 就不发了——这是"左键点不开菜单"的第二个原因。</p>
 */
function toggleMenu(event: PointerEvent | MouseEvent) {
	event.stopPropagation();
	event.preventDefault();
	menuOpen.value = !menuOpen.value;
}

function pick(key: string) {
	menuOpen.value = false;
	emit("action", {node: props.node, action: key});
}

function closeMenu() {
	menuOpen.value = false;
}

// 点别处 / Esc 关闭菜单（挂在 window 上，因为菜单在 SVG 里、点外面可能落在任何地方）
const onWindowDown = (event: PointerEvent) => {
	if (!menuOpen.value) {
		return;
	}
	const target = event.target as HTMLElement | null;
	if (target?.closest?.(".node-menu, .hit")) {
		return;
	}
	closeMenu();
};
const onKey = (event: KeyboardEvent) => {
	if (event.key === "Escape") {
		closeMenu();
	}
};
if (typeof window !== "undefined") {
	window.addEventListener("pointerdown", onWindowDown, true);
	window.addEventListener("keydown", onKey);
}
onBeforeUnmount(() => {
	window.removeEventListener("pointerdown", onWindowDown, true);
	window.removeEventListener("keydown", onKey);
});
</script>

<template>
	<g :transform="`translate(${x}, ${y})`">
		<!-- 可见圆点：只显示，事件交给 HTML 靶 -->
		<circle
			:r="radius"
			:fill="node.isFork ? '#2a2a2a' : '#111111'"
			:stroke="selected || menuOpen ? '#0078d7' : (node.isFork ? '#d0d0d0' : (node.degree === 2 ? '#5f5f5f' : '#3f3f3f'))"
			:stroke-width="selected || menuOpen ? 0.55 : 0.3"
			style="pointer-events: none"
		/>

		<!-- 交互靶 + 悬停信息卡 + 自绘菜单，都在 foreignObject 里（HTML 世界） -->
		<foreignObject
			:x="-hitSize"
			:y="-hitSize"
			:width="hitSize * 2"
			:height="hitSize * 2"
			style="overflow: visible"
		>
			<div class="anchor">
				<NPopover trigger="hover" placement="top" :show-arrow="true" :delay="120" :disabled="menuOpen" raw>
					<template #trigger>
						<div class="hit" @pointerdown="toggleMenu"/>
					</template>
					<div class="card">
						<div class="head">
							<span class="title value">{{ node.coords }}</span>
							<span class="kind">{{ kindText }}</span>
						</div>
						<div class="grid">
							<span class="label">度数</span><span class="value">{{ node.degree }}</span>
							<span class="label">所属区间</span><span class="value block">{{ blockText }}</span>
						</div>
						<div class="sub">相邻轨 {{ neighbourRows.length }} 条</div>
						<div v-for="row in neighbourRows" :key="row.rail" class="neighbour">
							<span class="value rail">{{ row.rail }}…</span>
							<span class="value dim">{{ row.length }} m</span>
							<span class="value dim">→ {{ row.to }}</span>
						</div>
						<div class="tip">左键：操作菜单</div>
					</div>
				</NPopover>

				<!-- 操作菜单：自绘，定位在圆点右侧；项数少，不需要滚动 -->
				<div v-if="menuOpen" class="node-menu">
					<template v-for="item in menuItems" :key="item.key">
						<div v-if="item.divider" class="menu-divider"/>
						<button v-else class="menu-item" type="button" @click="pick(item.key)">{{ item.label }}</button>
					</template>
				</div>
			</div>
		</foreignObject>
	</g>
</template>

<style scoped>
.anchor {
	position: absolute;
	inset: 0;
}

.hit {
	width: 100%;
	height: 100%;
	cursor: pointer;
	border-radius: 50%;
}

/*
 * 操作菜单：C# 端那套"假玻璃"深色卡片（实色底 + 细边 + 小圆角），
 * 但用 px 而不是世界单位——它是 UI，不该随地图缩放变糊。
 */
.node-menu {
	position: absolute;
	left: 100%;
	top: 50%;
	transform: translate(6px, -50%);
	min-width: 132px;
	padding: 4px;
	background: #0a0a0a;
	border: 1px solid #2a2a2a;
	border-radius: 4px;
	box-shadow: 0 10px 28px rgba(0, 0, 0, 0.6);
	z-index: 30;
}

.menu-item {
	display: block;
	width: 100%;
	padding: 5px 10px;
	background: transparent;
	border: 0;
	border-radius: 3px;
	color: #c8c8c8;
	font-family: "Alte DIN 1451", "HarmonyOS Sans SC", system-ui, sans-serif;
	font-size: 12px;
	text-align: left;
	white-space: nowrap;
	cursor: pointer;
}

.menu-item:hover {
	background: rgba(255, 255, 255, 0.08);
	color: #ffffff;
}

.menu-divider {
	height: 1px;
	margin: 4px 2px;
	background: #1f1f1f;
}
</style>
