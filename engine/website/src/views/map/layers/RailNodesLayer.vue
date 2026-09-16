<script setup lang="ts">
/*
 * 基础图层：**轨道线 + 铁路节点**（整个线网）。
 *
 * <h2>它是"基础图层"的意思</h2>
 * <p>这张图的世界范围由它定：取到节点后把**节点云的中心**报给框架当锚点
 * （`ctx.setAnchor`，只有第一次生效）。别的图层（信号灯、道岔、区间…）只管把自己的世界坐标
 * 交给 `ctx.project()`，不用管画面摆在哪。</p>
 *
 * <h2>尺寸全在画布单位里（这一层自己的常量）</h2>
 * <p>点的直径 = 1 格（连边框一起算，`stroke` 居中画的所以要扣掉半个边框宽度）；轨道线宽 = 1 格。
 * 屏幕上的大小 = 画布尺寸 × 相机比例，所以缩放和平移都由相机负责，这一层只管"画多大"。</p>
 *
 * <h2>采样形状要对齐到轨的两个端点</h2>
 * <p>引擎的采样走**方块中心**、节点坐标走**方块角**，实测整条一致偏 (0.5, 0.5) 格。校正量按
 * "端点 − 采样点"现算（首点归位到端点1、末点归位到端点2、中间按 t 线性分摊），所以引擎的约定
 * 再变也不会偏 —— **不要写成"减 0.5 格"**。</p>
 *
 * <h2>道岔没指向的那根出口：线淡一半</h2>
 * <p>哪根被切掉是**引擎的结论**（`mmtr-points` 每行的 `prohibited`），网页照用不重算
 * （见 {@link prohibitedRailHexes}）—— 自己按几何推一遍，就会出现"图上淡的是 A、引擎切的是 B"。
 * 淡出只改这一根线的 alpha，不改几何、不改叠放顺序。</p>
 */
import {computed, onBeforeUnmount, onMounted, watch} from "vue";
import {prohibitedRailHexes, type MapNode} from "@/domain/MapNode";
import {LIVE_REFRESH_MILLIS, UNITS_PER_BLOCK, useMapContext} from "../mapContext";
import {useLiveFeeds} from "../liveFeeds";
import {alignRail} from "../railAlignment";
import {hovered, menu, pointer, registerReload} from "./nodeInteraction";
import {vehicleHitAt} from "../trainSelection";

/** 边框宽度，画布单位：占 1 格的 10%。 */
const NODE_STROKE = UNITS_PER_BLOCK * 0.1;
/** 点半径：**连边框一起**正好 1 格（`stroke` 两侧各画一半，所以要扣掉半个边框）。 */
const NODE_RADIUS = (UNITS_PER_BLOCK - NODE_STROKE) / 2;
/** 黑色外阴影的半径倍数（相对点的外半径）。 */
const NODE_SHADOW_SCALE = 2.5;
/** 外阴影半径，画布单位。 */
const NODE_SHADOW_RADIUS = (UNITS_PER_BLOCK / 2) * NODE_SHADOW_SCALE;
/** 轨道线宽，画布单位：1 格（一根轨就是一个方块宽）。 */
const RAIL_WIDTH = UNITS_PER_BLOCK;
/** 道岔没指向的那根出口线的透明度（用户：减 50%）。 */
const PROHIBITED_RAIL_OPACITY = 0.5;
/**
 * 命中半径（**屏幕像素**，不是画布单位）。
 *
 * <p>点只有 1 格大 —— 默认取景下是 1.25 px，直接按元素监听就等于"要拿鼠标去戳一个亚像素的点"。
 * 所以命中判定自己做：把指针换算成画布坐标，再找这个屏幕半径内最近的节点。
 * 定在屏幕像素上，任何缩放级别的手感都一样。</p>
 */
const HIT_RADIUS_PX = 12;
/** 菜单宽度（px）——同时用于 CSS 与"别顶出窗口"的夹取，所以只有这一个数。 */
const MENU_WIDTH = 240;
/** 菜单最大高度（px），同上。 */
const MENU_MAX_HEIGHT = 300;

/** 画布里的一个点。 */
interface Placed {
	readonly key: string;
	readonly x: number;
	readonly y: number;
}

interface Scene {
	readonly nodes: readonly Placed[];
	readonly rails: readonly {readonly key: string; readonly points: string; readonly opacity: number}[];
}

const ctx = useMapContext();

/**
 * 布局：把这一层的世界坐标落进框架的画布坐标系。
 *
 * <p>算之前**先读一次 `ctx.anchor`**：锚点是响应式的，读它才能让锚点冻结时这一层跟着重算
 * （不然先画的那一层会停在旧锚点上）。</p>
 */
const scene = computed<Scene>(() => {
	ctx.anchor.value;
	if (mapNodes.value.length === 0) {
		return {nodes: [], rails: []};
	}
	const prohibited = prohibitedRailHexes(mapNodes.value);
	return {
		nodes: mapNodes.value.map(({node}) => {
			const [x, y] = ctx.project(node.planeX, node.planeZ);
			return {key: node.key, x, y};
		}),
		rails: rails.value.map(rail => {
			/*
			 * 采样形状**对齐到轨的两个端点**（引擎的采样走方块中心、节点坐标走方块角，实测整条一致偏
			 * (0.5, 0.5) 格，校正量按"端点 − 采样点"现算而不是写死 0.5）。规则只有一份，在
			 * `../railAlignment.ts`：**车辆图层也用它**把自己落在同一条线上 —— 别在这里另抄一遍。
			 */
			const aligned = alignRail(rail);
			return {
				key: rail.hex,
				points: aligned.points.map(([x, z]) => ctx.project(x, z).join(",")).join(" "),
				// 道岔没指向的那根出口：整根淡一半（引擎说它现在禁止通行）
				opacity: prohibited.has(rail.hex) ? PROHIBITED_RAIL_OPACITY : 1,
			};
		}),
	};
});

/*
 * 数据来自页面的活数据 store（`../liveFeeds.ts`）：拓扑 + 道岔 + 灯拼出来的派生节点、以及轨。
 * **这一层不再自己取数** —— 会变的那几路由页面按统一节拍（`LIVE_REFRESH_MILLIS`）取，
 * 地图页的节点层与信号灯层共用同一份派生结果（早先两边各取一次 `fetchMapNodes`，同一份数据发两遍）。
 */
const feeds = useLiveFeeds();
const mapNodes = feeds.mapNodes;
const rails = feeds.rails;

/*
 * 锚点：**第一次报到后冻结**（框架那边只接受第一次）。派生节点每拍都会重算，所以用 watch 报 ——
 * 反正只有第一次生效，世界改画了也不会把用户正在看的画面挪走。
 */
watch(mapNodes, value => {
	if (value.length === 0) {
		return;
	}
	const xs = value.map(({node}) => node.planeX);
	const zs = value.map(({node}) => node.planeZ);
	ctx.setAnchor((Math.min(...xs) + Math.max(...xs)) / 2, (Math.min(...zs) + Math.max(...zs)) / 2);
}, {immediate: true});

// ---------------------------------------------------------------- 悬停与点选

/*
 * 命中判定与状态放在这一层，显示与动作在 `NodeMenu.vue`（框架的 HTML 浮层插槽里）——
 * 为什么必须拆开：这一层的模板在 SVG 的 `<g>` 里，写不出能显示的 HTML（见 nodeInteraction.ts 头部）。
 */

/**
 * 命中测试：指针 → 画布坐标 → 这个**屏幕半径**内最近的节点。
 *
 * <p>屏幕↔画布的换算交给框架（`ctx.toCanvas`，它才知道相机在哪）：<b>不要自己用 `<svg>` 根的
 * `getScreenCTM()`</b> —— 相机矩阵在子 `<g>` 上，根元素的 CTM 里没有它，于是指针位置会被当成
 * 画布坐标，缩放越大错得越离谱（实测踩过：命中判定永远找不到节点）。</p>
 */
function hitTest(event: MouseEvent): MapNode | null {
	const point = ctx.toCanvas(event.clientX, event.clientY);
	if (!point) {
		return null;
	}
	const radius = HIT_RADIUS_PX / point.scale;
	let best: MapNode | null = null;
	let bestDistance = Number.POSITIVE_INFINITY;
	for (const mapNode of mapNodes.value) {
		const [x, y] = ctx.project(mapNode.node.planeX, mapNode.node.planeZ);
		const distance = Math.hypot(x - point.x, y - point.y);
		if (distance <= radius && distance < bestDistance) {
			best = mapNode;
			bestDistance = distance;
		}
	}
	return best;
}

function onPointerMove(event: PointerEvent): void {
	pointer.value = {x: event.clientX, y: event.clientY};
	if (menu.value === null) {
		hovered.value = hitTest(event);
	}
}

function onPointerLeave(): void {
	hovered.value = null;
}

/** 左键点节点 → 开菜单；点空白 → 关菜单。 */
function onPointerDown(event: PointerEvent): void {
	if (event.button !== 0) {
		return;
	}
	/*
	 * 只处理**来自地图画布**的点击（target 是 SVG 元素）。
	 *
	 * <p>为什么必须挡一下：浮层（菜单）也在舞台里，它的点击会冒泡到舞台 —— 如果照单全收，
	 * 点菜单按钮会先被当成"点了空白"把菜单关掉，于是 `pointerup` 落到别的元素上、
	 * `click` 永远不触发：**菜单看得见、就是点不动**（实测踩过的坑，靠抓指针事件序列才认出来）。</p>
	 */
	if (!(event.target instanceof SVGElement)) {
		return;
	}
	/*
	 * **车压在节点上时，点下去算点车**（用户 2026-09-16 让列车图标可点之后新增的仲裁）。
	 *
	 * <p>两个图层挂在同一个舞台元素上各做各的命中判定，谁先收到事件不可依赖；而车是画在节点
	 * **上面**的，所以"看得见谁就点到谁"要求这里让路。判定由车辆层注册（`trainSelection.ts`），
	 * 没注册（区间地图没有车辆层）时它一律返回 false，这一层的行为与从前一模一样。</p>
	 */
	if (vehicleHitAt(event.clientX, event.clientY)) {
		menu.value = null;
		return;
	}
	const node = hitTest(event);
	hovered.value = null;
	menu.value = node === null ? null : {
		node,
		// 菜单别顶出窗口（宽高就是上面那两个常量）
		x: Math.min(event.clientX, window.innerWidth - MENU_WIDTH - 8),
		y: Math.min(event.clientY, window.innerHeight - MENU_MAX_HEIGHT - 8),
	};
}
/** 点地图以外的地方（上边栏、时刻表切页…）也要关掉菜单。 */
function onDocumentPointerDown(event: PointerEvent): void {
	if (menu.value === null) {
		return;
	}
	const target = event.target as Node | null;
	if (target && ctx.stage.value?.contains(target)) {
		return; // 地图内部的点击交给 onPointerDown 裁决
	}
	if (target && (target as HTMLElement).closest?.(".node-menu") !== null) {
		return; // 菜单自己的点击
	}
	menu.value = null;
}

/*
 * 监听挂在**框架的舞台元素**上，而不是 155 个圆点上：命中判定本来就要按屏幕半径找最近的节点
 * （见 hitTest），一个一个元素监听既解决不了"点太小点不到"，也会在缩放后失效。
 *
 * 为什么用 watch 而不是 onMounted：图层是**子组件**，别假设自己的 onMounted 跑的时候框架的
 * 模板 ref 已经落上；`flush: "post"` 保证 DOM 补丁完再挂，`immediate` 兼顾"元素已经在了"的情况。
 */
let listeningOn: HTMLElement | null = null;

function detachListeners(): void {
	listeningOn?.removeEventListener("pointermove", onPointerMove);
	listeningOn?.removeEventListener("pointerleave", onPointerLeave);
	listeningOn?.removeEventListener("pointerdown", onPointerDown);
	listeningOn = null;
}

function attachListeners(stage: HTMLElement | null): void {
	if (listeningOn === stage) {
		return;
	}
	detachListeners();
	listeningOn = stage;
	stage?.addEventListener("pointermove", onPointerMove);
	stage?.addEventListener("pointerleave", onPointerLeave);
	stage?.addEventListener("pointerdown", onPointerDown);
}

watch(ctx.stage, attachListeners, {immediate: true, flush: "post"});

onMounted(() => {
	document.addEventListener("pointerdown", onDocumentPointerDown);
	/*
	 * 把"重新取数并找回这个节点"交给浮层用：扳岔之后浮层要回读核对（引擎不回传拒绝原因），
	 * 而数据在活数据 store 手里。**扳岔本身不需要这里做什么** —— 会变的那几路每
	 * `LIVE_REFRESH_MILLIS` 自己刷新，回读要的是"立刻再取一次"，所以这里只等一拍。
	 */
	registerReload(async key => {
		await new Promise(resolve => setTimeout(resolve, LIVE_REFRESH_MILLIS));
		return mapNodes.value.find(item => item.key === key) ?? null;
	});
});

onBeforeUnmount(() => {
	detachListeners();
	registerReload(null);
	document.removeEventListener("pointerdown", onDocumentPointerDown);
});
</script>

<template>
	<!--
		三层，按这个顺序叠：轨道线 → 点的外阴影 → 点。
		阴影夹在中间是**故意的**：它落在穿过节点的轨道线上，于是那圈黑是看得见的
		（画在线的下面就等于白画 —— 线的颜色会盖掉它）。
	-->
	<defs>
		<!--
			点的黑色外阴影。objectBoundingBox 单位：0% = 阴影圆心、100% = 阴影边缘，
			所以"点边缘"这个位置是 `1 / NODE_SHADOW_SCALE`；在那之前保持全黑（反正被点盖住），
			之后线性渐隐到透明。id 在整个 SVG 里唯一，别的图层要用渐变请另取名字。
		-->
		<radialGradient id="mmtr-node-shadow">
			<stop offset="0%" stop-color="#000000" stop-opacity="1"/>
			<stop :offset="`${100 / NODE_SHADOW_SCALE}%`" stop-color="#000000" stop-opacity="1"/>
			<stop offset="100%" stop-color="#000000" stop-opacity="0"/>
		</radialGradient>
	</defs>

	<polyline
		v-for="rail in scene.rails"
		:key="rail.key"
		class="rail"
		:points="rail.points"
		:stroke-width="RAIL_WIDTH"
		:stroke-opacity="rail.opacity"
	/>
	<circle
		v-for="point in scene.nodes"
		:key="`shadow-${point.key}`"
		class="node-shadow"
		:cx="point.x"
		:cy="point.y"
		:r="NODE_SHADOW_RADIUS"
	/>
	<circle
		v-for="point in scene.nodes"
		:key="point.key"
		class="node"
		:cx="point.x"
		:cy="point.y"
		:r="NODE_RADIUS"
		:stroke-width="NODE_STROKE"
	/>
</template>

<style scoped>
/*
 * 尺寸一律在画布坐标系里定（见文件头常量），CSS 只管颜色。
 * 线宽/边框宽是尺寸，所以走模板上的 `:stroke-width`，不写在这里（免得两处各写一个数）。
 */
.node {
	fill: var(--fg);
	stroke: var(--fg-dim);
}

.rail {
	fill: none;
	stroke: var(--fg-dim);
	stroke-linejoin: round;
	stroke-linecap: round;
}

.node-shadow {
	fill: url("#mmtr-node-shadow");
}

/*
 * 悬停提示与点选菜单：Teleport 到 body 的 HTML 浮层（见模板注释）。
 * 与上边栏同一种"假玻璃"（深色实底 + 1px 发丝线），颜色一律走 token。
 */
.node-tooltip {
	position: fixed;
	z-index: 10;
	padding: 3px 8px;
	background: var(--glass);
	border: 1px solid var(--hairline);
	border-radius: var(--radius);
	font-family: var(--font-value);
	font-size: 12px;
	color: var(--fg);
	pointer-events: none; /* 提示不能挡住它自己底下的地图 */
	user-select: none;
}

.node-menu {
	position: fixed;
	z-index: 11;
	overflow-y: auto;
	display: flex;
	flex-direction: column;
	background: var(--glass);
	border: 1px solid var(--hairline);
	border-radius: var(--radius);
	background-image: var(--glass-fog);
	box-shadow: 0 8px 24px rgba(0, 0, 0, 0.6);
	user-select: none;
}

.menu-head {
	padding: 6px 10px;
	font-family: var(--font-value);
	font-size: 12px;
	color: var(--fg);
}

.menu-state {
	padding: 0 10px 6px;
	font-size: 12px;
	color: var(--fg-dim);
	border-bottom: 1px solid var(--hairline);
}

.menu-body {
	display: flex;
	flex-direction: column;
	padding: 4px;
}

.menu-body button {
	padding: 6px 8px;
	background: transparent;
	border: none;
	border-radius: var(--radius);
	color: var(--fg-secondary);
	font-family: var(--font-ui);
	font-size: 12px;
	text-align: left;
	cursor: pointer;
}

.menu-body button:hover:enabled {
	background: var(--panel-hover);
	color: var(--fg);
}

.menu-body button:disabled {
	color: var(--fg-faint);
	cursor: default;
}

.menu-current {
	margin-left: 6px;
	color: var(--accent);
}
</style>
