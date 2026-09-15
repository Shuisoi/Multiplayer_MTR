<script setup lang="ts">
import {computed, onBeforeUnmount, watch} from "vue";
import {Node} from "@/domain/Node";
import type {Camera} from "@/domain/camera";
import {DECAL_KINDS, decalPlacement, decalTransform} from "@/domain/mapElements";

/*
 * 一个节点（普通 HTML 元素，绝对定位在屏幕坐标上）。
 *
 * <h3>这一版的三个决定</h3>
 * <ol>
 *   <li><b>不用 SVG。</b>节点是"地图标注"：大小固定、永远清晰、可悬停可点。放进 SVG 就要面对
 *       `viewBox` 缩放，而 HTML 浮层又得用 `foreignObject` 塞进去再反向缩放——上一版就是这么把
 *       三层坐标系绕在一起的（详见 `domain/camera.ts`）。现在节点就是一个 div，
 *       圆点由 CSS 画，尺寸就是 CSS 像素，缩放视图只改它的 `transform: translate()`。</li>
 *   <li><b>事件用 `pointerdown` 取左键，配合拖动阈值区分点击与拖拽。</b>视口在 `pointerdown` 上开始平移，
 *       所以"按下即开菜单"会在拖动时误触发；"用 click"又会在拖动后收不到 click。
 *       这里的做法：按下时记位置，抬起时若位移 &lt; 4px 才算点击、开菜单。
 *       两种操作都不打架。（上一版在 SVG 里踩过两个更隐蔽的坑：靶子被浮层盖住、菜单浮层点不到，
 *       自绘 HTML 之后这两类问题不存在了。）</li>
 *   <li><b>悬停信息卡与操作菜单都是这个元素的子节点。</b>所以它们天然跟着节点走——
 *       平移、缩放时不需要任何"重新定位浮层"的代码。</li>
 * </ol>
 */

const props = defineProps<{
	node: Node;
	/** 当前相机：贴片位置由它算（组件自己不接收屏幕坐标，"位置"只有一个来源）。 */
	camera: Camera;
	hovered: boolean;
	menuOpen: boolean;
	selected: boolean;
}>();

const emit = defineEmits<{
	(e: "hover", key: string): void;
	(e: "toggleMenu", key: string): void;
	(e: "closeMenu"): void;
	(e: "action", payload: {node: Node; action: string}): void;
}>();

/**
 * 节点圆点半径（CSS 像素）：来自**规格表**（`DECAL_KINDS.nodeDot`），不再在这里写死数字。
 *
 * <p>注意旧实现按度数分三档（端点 3.5 / 通过 4.5 / 道岔 6）。这一版先统一到规格表那一档 ——
 * "形状表达类型"已经由别的元素承担（灯是箭头、道岔是菱形），节点本身统一大小反而更整齐；
 * 要恢复分档就在规格表里加"按度数取尺寸"的规则，而不是回到组件里写死。</p>
 */
const radius = DECAL_KINDS.nodeDot;

/** 贴片锚点：节点的世界坐标 → 屏幕（节点不需要偏移，所以第二个参数省略）。 */
const placement = computed(() => decalPlacement(props.node.planeX, props.node.planeZ, props.camera));

/** 外层的位移（贴片锚点）。 */
const rootTransform = computed(() => decalTransform(placement.value));

/** 高亮状态：悬停 / 菜单打开 / 被选中，都画强调色边。 */
const active = computed(() => props.hovered || props.menuOpen || props.selected);

/**
 * 覆盖这个节点的区间文案（**可能多个**：双向线路上南行、北行各一个）。
 *
 * <p>原来是"所属区间"（单值）。引擎已不再给唯一归属——区间是某方向的一段路，一个节点被两个方向的
 * 区间同时覆盖是常态（现场实测被覆盖的 96 根轨里 62 根多归属），所以这里如实列出全部。</p>
 */
const blockText = computed(() => {
	if (props.node.sections.length === 0) {
		return "无区间覆盖（这一段没有灯）";
	}
	return props.node.isMultiSection
		? `${props.node.sections.length} 个区间：${props.node.blockText}`
		: props.node.blockText;
});

/** 相邻轨列表（悬停卡里一行一条）。 */
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
	{key: "console", label: "坐标送进指令栏"},
	{key: "divider1", label: "", divider: true},
	{key: "copy", label: "复制坐标（指令用 x y z）"},
	{key: "copyReadable", label: "复制坐标（x, y, z）"},
	{key: "neighbors", label: "复制相邻轨"},
	...(props.node.isFork
		? [{key: "divider2", label: "", divider: true}, {key: "fork", label: "查看道岔（进向与腿）"}]
		: []),
]);

/*
 * 点击判定：按下记位置，抬起时位移小于阈值才算点击。
 * 用 `pointerdown` 关闭菜单会导致拖动时误关，用 `click` 又收不到拖拽后的抬起，
 * 所以自己在抬起时判定——这样"拖动平移"和"点开菜单"互不干扰。
 */
const CLICK_SLOP_PX = 4;
let pressedAt: {x: number; y: number} | null = null;

function onPointerDown(event: PointerEvent) {
	if (event.button !== 0) {
		return;
	}
	// 阻止视口把这次按下当成拖动的开始（否则同一次手势既平移又开菜单）。
	event.stopPropagation();
	pressedAt = {x: event.clientX, y: event.clientY};
}

function onPointerUp(event: PointerEvent) {
	if (event.button !== 0 || !pressedAt) {
		return;
	}
	const moved = Math.hypot(event.clientX - pressedAt.x, event.clientY - pressedAt.y);
	pressedAt = null;
	if (moved <= CLICK_SLOP_PX) {
		event.stopPropagation();
		emit("toggleMenu", props.node.key);
	}
}

/** 指针离开时清掉"按下中"状态，避免抬起判定串到别的元素上。 */
function onPointerCancel() {
	pressedAt = null;
}

function pick(key: string) {
	emit("action", {node: props.node, action: key});
}

/** Esc 关菜单：键盘操作里最容易被期待的一条，成本也最低。 */
function onKey(event: KeyboardEvent) {
	if (event.key === "Escape" && props.menuOpen) {
		emit("closeMenu");
	}
}
if (typeof window !== "undefined") {
	window.addEventListener("keydown", onKey);
}
onBeforeUnmount(() => {
	window.removeEventListener("keydown", onKey);
});

// 菜单打开时若节点已经不在视野里，位置会跑到屏幕外；这不是错误，拖动回来即可。
// 但**选中态**要在菜单关闭后清掉高亮以外的残留语义，所以这里不做额外处理，保持"显示即状态"。
watch(() => props.menuOpen, open => {
	if (open) {
		pressedAt = null;
	}
});
</script>

<template>
	<div
		class="node"
		:class="{active, fork: node.isFork}"
		:data-key="node.key"
		:style="{transform: rootTransform}"
		@pointerenter="emit('hover', node.key)"
		@pointerleave="emit('hover', '')"
		@pointerdown="onPointerDown"
		@pointerup="onPointerUp"
		@pointercancel="onPointerCancel"
	>
		<!-- 圆点：纯 CSS 画。半径用 CSS 变量传下去，保持"只有一处定义尺寸"。 -->
		<div class="dot" :style="{'--r': `${radius}px`}"/>

		<!-- 悬停信息卡：菜单打开时让位，避免两层卡片叠在一起 -->
		<div v-if="hovered && !menuOpen" class="card">
			<div class="card-head">
				<span class="coords value">{{ node.coords }}</span>
				<span class="kind">{{ node.kindText }}</span>
			</div>
			<dl class="facts">
				<dt>度数</dt>
				<dd class="value">{{ node.degree }}</dd>
				<dt>所属区间</dt>
				<dd>{{ blockText }}</dd>
			</dl>
			<div class="sub">相邻轨 {{ neighbourRows.length }} 条</div>
			<ul class="neighbours">
				<li v-for="row in neighbourRows" :key="row.rail">
					<span class="rail value">{{ row.rail }}…</span>
					<span class="len value">{{ row.length }} m</span>
					<span class="to value">→ {{ row.to }}</span>
				</li>
			</ul>
			<div class="tip">左键：操作菜单</div>
		</div>

		<!--
			操作菜单：自绘。定位在圆点右下方，不与信息卡重叠。

			**必须吃掉 pointerdown/pointerup**：菜单是 `.node` 的子元素，而 `.node` 上有
			"按下-抬起位移 ≤4px 就算点了一下 → 切换菜单"的判定。不拦的话，按在菜单项上会先冒泡到 `.node`
			触发 toggleMenu，菜单**当场关掉**、按钮从 DOM 里消失，真正的 `click` 于是落空 ——
			现象就是"点『复制坐标』什么都没发生"（实测 2026-09-13 用户报的正是这个）。
		-->
		<div v-if="menuOpen" class="menu" @pointerdown.stop @pointerup.stop @pointercancel.stop>
			<template v-for="item in menuItems" :key="item.key">
				<div v-if="item.divider" class="menu-divider"/>
				<button v-else class="menu-item" type="button" @click.stop="pick(item.key)">{{ item.label }}</button>
			</template>
		</div>
	</div>
</template>

<style scoped>
/*
 * 节点：一个零尺寸的定位锚点，放在节点中心的屏幕坐标上。
 * 宽高都是 0，所以 `translate()` 的数值就是节点中心——不用再为"元素自身尺寸"做补偿，
 * 这类补偿是上一版反复算错的地方之一。
 */
.node {
	position: absolute;
	left: 0;
	top: 0;
	width: 0;
	height: 0;
	pointer-events: auto;
	cursor: pointer;
}

/*
 * 圆点：用 box-shadow 做外圈高光而不是 border，这样尺寸不会因为边框变胖（半径就是半径）。
 * 鼠标靶用一个更大的 ::before 透明圆，保证小圆点也好点。
 */
.dot {
	position: absolute;
	left: calc(var(--r) * -1);
	top: calc(var(--r) * -1);
	width: calc(var(--r) * 2);
	height: calc(var(--r) * 2);
	border-radius: 50%;
	background: #111111;
	box-shadow: inset 0 0 0 1.4px #5f5f5f;
	transition: box-shadow var(--fast) var(--ease), background var(--fast) var(--ease);
}

.node.fork .dot {
	background: #1c1c1c;
	box-shadow: inset 0 0 0 1.6px #d0d0d0;
}

/* 悬停 / 打开菜单 / 选中：换成强调色描边（C# 端那套只把强调色用在关键处） */
.node.active .dot {
	background: #0d1b26;
	box-shadow: inset 0 0 0 2px var(--accent), 0 0 0 4px var(--accent-soft);
}

/* 鼠标靶：24px 见方的透明圆，覆盖在圆点上 */
.node::before {
	content: "";
	position: absolute;
	left: -12px;
	top: -12px;
	width: 24px;
	height: 24px;
	border-radius: 50%;
}

/*
 * 信息卡与菜单：C# 端的"假玻璃"深色卡片（实色底 + 细边 + 小圆角 + 大阴影）。
 * 都是 px 尺寸的普通 HTML，不再经过任何缩放，所以字永远是清晰的。
 */
.card,
.menu {
	position: absolute;
	background: var(--panel);
	border: 1px solid var(--line);
	border-radius: var(--radius);
	box-shadow: 0 12px 32px rgba(0, 0, 0, 0.72);
}

.card {
	left: 14px;
	bottom: 14px;
	width: 262px;
	padding: 10px 12px;
	font-size: 12px;
	line-height: 1.5;
	color: var(--fg-secondary);
	cursor: default;
}

.card-head {
	display: flex;
	align-items: baseline;
	justify-content: space-between;
	gap: 8px;
	margin-bottom: 8px;
	padding-bottom: 8px;
	border-bottom: 1px solid var(--hairline);
}

.coords {
	font-family: var(--font-value);
	font-size: 13px;
	color: var(--fg);
}

.kind {
	flex: none;
	font-size: 11px;
	color: var(--fg-dim);
}

.facts {
	display: grid;
	grid-template-columns: auto 1fr;
	gap: 2px 10px;
	margin: 0 0 8px;
}

.facts dt {
	color: var(--fg-dim);
}

.facts dd {
	margin: 0;
	color: var(--fg-secondary);
	word-break: break-all;
}

.sub {
	margin-bottom: 4px;
	color: var(--fg-dim);
}

.neighbours {
	margin: 0;
	padding: 0;
	list-style: none;
}

.neighbours li {
	display: flex;
	gap: 6px;
	font-family: var(--font-value);
	font-size: 11px;
	color: var(--fg-faint);
}

.neighbours .rail {
	color: var(--fg-dim);
}

.neighbours .len {
	flex: none;
	color: var(--fg-secondary);
}

.tip {
	margin-top: 8px;
	padding-top: 6px;
	border-top: 1px solid var(--hairline);
	font-size: 11px;
	color: var(--fg-faint);
}

.menu {
	left: 14px;
	top: 14px;
	min-width: 136px;
	padding: 4px;
	z-index: 20;
}

.menu-item {
	display: block;
	width: 100%;
	padding: 5px 10px;
	background: transparent;
	border: 0;
	border-radius: 3px;
	color: var(--fg-secondary);
	font-family: var(--font-ui);
	font-size: 12px;
	text-align: left;
	white-space: nowrap;
	cursor: pointer;
}

.menu-item:hover {
	background: rgba(255, 255, 255, 0.08);
	color: var(--fg);
}

.menu-divider {
	height: 1px;
	margin: 4px 2px;
	background: var(--line);
}

.value {
	font-family: var(--font-value);
}
</style>

