<script setup lang="ts">
import {computed} from "vue";
import type {Signal} from "@/domain/Signal";
import type {Camera} from "@/domain/camera";
import {DECAL_KINDS, decalPlacement, decalTransform, pixelOffset, scaled} from "@/domain/mapElements";
import {useZoomRatio} from "@/views/mapContext";

/*
 * 一个信号灯（**地图上的元素**：位置与尺寸都跟着摄像机走）。
 *
 * <p>三件事：**状态**（颜色）、**方向**（箭头 `^`）、**在哪**（由本组件按统一模型算）。</p>
 *
 * <p><b>尺寸与偏移都是"规格值 × 当前倍率"</b>（用户 2026-09-15 定的口径："所谓的 8 px 是缩放为 6×
 * 的时候大小是 8 px，这个应该跟随缩放变换大小 —— 可以理解成是摄像机在移动，地图大小和位置关系不动"）。
 * 所以 6× 时图标 8 px、偏移 10 px；推近到 12× 就是 16 px / 20 px；拉远到 3× 就是 4 px / 5 px。
 * 规格与换算都在 `domain/mapElements.ts`，组件不再自己定任何像素值。</p>
 */

const props = defineProps<{
	signal: Signal;
	/** 当前相机：位置由它算（组件不接收屏幕坐标，"位置"只有一个来源）。 */
	camera: Camera;
	hovered: boolean;
	/** 正在改这盏灯的绑定（点选绑定）：加一圈强调环。 */
	selected?: boolean;
}>();

/**
 * 这个贴片的一切尺寸/位置都由 `domain/mapElements.ts` 决定（**唯一真源**）。
 *
 * <p>组件里不再出现"这几个像素是我算的"这类判断 —— 那正是以前每加一种元素就要重写一遍、
 * 并且写出"缩放时相对节点滑走"那类缺陷的原因。</p>
 */
/** 缩放倍率（画布注入；拿不到按 1 算）。 */
const zoomRatio = useZoomRatio();

/** 图标与灯点的**当前**屏幕尺寸 = 规格值 × 倍率换算（6× 时正好是规格值）。 */
const iconPx = computed(() => scaled(DECAL_KINDS.icon, zoomRatio.value));
const lampDotPx = computed(() => scaled(DECAL_KINDS.lampDot, zoomRatio.value));
const lampDotHalf = computed(() => lampDotPx.value / 2);

/**
 * 相对锚点的偏移：方向来自管辖方向（实测世界数据对得上），
 * **距离 = 规格值 × 倍率** —— 所以它跟地图一起变，而不是像屏幕 HUD 那样固定。
 */
const offsetPx = computed(() => pixelOffset(
	props.signal.sideOffsetDirection,
	scaled(DECAL_KINDS.signalSideOffset, zoomRatio.value),
));

/** 元素锚点：灯的世界坐标 → 屏幕 + 偏移。 */
const placement = computed(() => decalPlacement(props.signal.planeX, props.signal.planeY, props.camera, offsetPx.value));

/** 外层的位移。 */
const rootTransform = computed(() => decalTransform(placement.value));


const emit = defineEmits<{
	(e: "hover", key: string): void;
	(e: "pick", key: string): void;
	/** 复制这盏灯的坐标（指令格式 `x y z`）。 */
	(e: "copy", signal: Signal): void;
	/** 把 `signal why x y z` 送进网页指令栏（不依赖剪贴板）。 */
	(e: "why", signal: Signal): void;
}>();

/** 状态 → 颜色。与 C# 端那套一致：红/黄/绿用高饱和，未接入用灰。 */
const stateColor = computed(() => {
	switch (props.signal.state) {
		case "red":
			return "#ef4444";
		case "singleYellow":
			return "#f59e0b";
		case "doubleYellow":
			return "#eab308";
		case "green":
			return "#22c55e";
		default:
			return "#6b7280";
	}
});

/** 信息卡里的说明行。 */
const facts = computed(() => [
	{label: "状态", value: props.signal.stateText},
	{label: "管辖方向", value: `${props.signal.directionText}　（角 ${props.signal.angle}°；灯面在它的反面）`},
	{label: "灯位", value: props.signal.aspectsText},
	{label: "守轨", value: props.signal.bindingText},
	{label: "开区间", value: props.signal.hasSection ? "是" : "未接入闭塞层"},
	{label: "操作", value: props.selected ? "点轨道上的高亮线 = 绑定/解绑，Esc 取消" : "点这盏灯 = 改绑定"},
]);

/** 信息卡里列出的守轨（只显示 hex 前 10 位，完整值在 title 里）。 */
const guarded = computed(() => props.signal.boundRails.map(hex => ({hex, short: hex.slice(0, 10)})));
</script>

<template>
	<div
		class="signal"
		:class="{hovered, selected}"
		:data-key="signal.key"
		:data-side-offset="`${Math.round(offsetPx.x)},${Math.round(offsetPx.y)}`"
		:style="{transform: rootTransform}"
		@pointerenter="emit('hover', signal.key)"
		@pointerleave="emit('hover', '')"
		@pointerdown.stop="emit('pick', signal.key)"
	>
		<!--
			方向：一个 `^` 形状的折角符号，按朝向角旋转（基准朝上 = 北）。

			用 SVG 画折角（而不是文字 `^`）：形状一样，但线条长度/粗细/描边都可控 ——
			实测字号下 DIN 的 `^` 字形只占元素框顶部一点点，旋转后被灯点盖住，等于看不见。

			**尺寸 = 图标直径（8 px）**：与地图上其他标注（道岔菱形）同一个大小，
			见 `domain/mapElements.ts#DECAL_KINDS`。viewBox 保持不变，所以笔画的相对比例也不变。
		-->
		<svg
			class="arrow"
			:style="{transform: `translate(-50%, -50%) rotate(${signal.arrowRotation}deg)`, color: stateColor}"
			:width="iconPx"
			:height="iconPx"
			viewBox="0 0 20 20"
		>
			<!-- 先描一条比底色暗的粗线做"描边"，再画本色：暗底上任何颜色都能看清 -->
			<path d="M 3 15 L 10 4 L 17 15" fill="none" stroke="#000000" stroke-width="7" stroke-linecap="round" stroke-linejoin="round"/>
			<path d="M 3 15 L 10 4 L 17 15" fill="none" stroke="currentColor" stroke-width="3.6" stroke-linecap="round" stroke-linejoin="round"/>
		</svg>
		<!-- 灯位：一个小圆点，颜色 = 状态。 -->
		<div class="lamp" :style="{background: stateColor}"/>

		<div v-if="hovered" class="card">
			<div class="card-head">
				<!--
					坐标本身就是"复制"按钮：把它送进剪贴板是这个界面里最常做的一件事
					（粘进指令栏、粘进 issue、粘进游戏），所以别让人再去瞄一个 11px 的小按钮。
					点了复制的是**指令格式** `x y z`（见 domain/coords.ts），显示仍给人读的那版。
					pointerdown/click 都要 stop：否则会被灯本身的"点这盏灯 = 改绑定"吃掉。
				-->
				<span
					class="coords value copyable"
					title="点一下复制指令坐标（x y z）"
					@pointerdown.stop
					@click.stop="emit('copy', signal)"
				>{{ signal.coords }}</span>
				<span class="state" :style="{color: stateColor}">{{ signal.stateText }}</span>
			</div>
			<dl class="facts">
				<template v-for="fact in facts" :key="fact.label">
					<dt>{{ fact.label }}</dt>
					<dd>{{ fact.value }}</dd>
				</template>
			</dl>
			<ul v-if="guarded.length > 0" class="guarded">
				<li v-for="item in guarded" :key="item.hex" :title="item.hex">{{ item.short }}…</li>
			</ul>
			<!--
				坐标的两个出口：复制（指令格式 x y z）与**送进指令栏**。
				第二个不碰剪贴板 —— 剪贴板会被浏览器拒绝（NotAllowedError），那时"复制"只能弹个窗让你手抄。
				按钮上的 pointerdown 要 stop，否则会触发灯本身的"点这盏灯 = 改绑定"。
			-->
			<div class="card-actions" @pointerdown.stop @click.stop>
				<button type="button" class="mini" @click="emit('copy', signal)">复制坐标</button>
				<button type="button" class="mini" @click="emit('why', signal)">查为什么是这个色</button>
			</div>
		</div>
	</div>
</template>

<style scoped>
/*
 * 零尺寸锚点放在信号灯中心：`translate()` 的数值就是中心，不用为元素自身尺寸做补偿
 * （那类补偿是上一版反复算错的地方之一）。
 */
.signal {
	position: absolute;
	left: 0;
	top: 0;
	width: 0;
	height: 0;
	pointer-events: auto;
	cursor: default;
}

/*
 * 方向符号 `^`（SVG 画的折角）：绕**灯点**旋转。
 *
 * <p>transform 有两件独立的事：把符号整体上移 `ARROW_OFFSET`（让折角画在灯点上方，
 * 否则向下的朝向会正好压在灯点上），以及按朝向角旋转。旋转中心必须回到灯点，
 * 所以 `transform-origin` 写成 `50% calc(50% + ARROW_OFFSET)` —— 不这样做的话符号会绕
 * 自己的中心转，看起来是"歪着指"。</p>
 */
.arrow {
	position: absolute;
	left: 0;
	top: 0;
	/*
	 * 箭头就画在灯点**正中**（不再往上浮固定像素）。
	 *
	 * <p>原来是"往上浮 5 px"：那个偏移相对**节点**是随缩放变化的（灯点像素不动、但用户眼的参照物
	 * ——节点与轨道——在动），看起来就是"灯相对节点滑走"（用户 2026-09-15 报的现象）。
	 * 现在整个图标（灯点 + 旋转到管辖方向的箭头）作为一个整体，只按**固定 10 px 的横向偏移**
	 * 挂在节点旁边（见 `markerOffset`），所以缩放时它与节点的相对位置**不变**。</p>
	 */
	transform: translate(-50%, -50%);
	transform-origin: 50% 50%;
	pointer-events: none;
	filter: drop-shadow(0 0 1.5px #000000);
}

/*
 * 灯位圆点：尺寸与位置都来自规格表 **再乘当前倍率**（`DECAL_KINDS.lampDot` = 4 px @6×），
 * 所以 CSS 里不写死数字。
 * 它必须比方向箭头小一圈，否则箭头被自己压住、"方向"这件事又白做了。
 */
.lamp {
	position: absolute;
	/* 半径也做成变量：下面描边/发光的宽度由它派生，于是它们随倍率一起缩放。 */
	--lamp-r: v-bind('`${lampDotHalf}px`');
	left: v-bind('`${-lampDotHalf}px`');
	top: v-bind('`${-lampDotHalf}px`');
	width: v-bind('`${lampDotPx}px`');
	height: v-bind('`${lampDotPx}px`');
	border-radius: 50%;
	box-shadow: 0 0 0 calc(var(--lamp-r) * 0.25) #000000, 0 0 calc(var(--lamp-r) * 1.25) currentColor;
}

.signal.hovered .lamp {
	box-shadow: 0 0 0 calc(var(--lamp-r) * 0.25) #000000, 0 0 0 calc(var(--lamp-r) * 0.63) rgba(255, 255, 255, 0.45);
}

/* 正在改绑定的灯：加一圈强调色环（比悬停更醒目，且不会因为指针离开而消失） */
.signal.selected .lamp {
	box-shadow: 0 0 0 calc(var(--lamp-r) * 0.25) #000000, 0 0 0 calc(var(--lamp-r) * 0.63) var(--accent), 0 0 calc(var(--lamp-r) * 2.5) var(--accent);
}

.signal {
	cursor: pointer;
}

/* 守轨列表：等宽小字，一行一条 */
.guarded {	margin: 6px 0 0;
	padding: 6px 0 0;
	border-top: 1px solid var(--hairline);
	list-style: none;
	font-family: var(--font-value);
	font-size: 11px;
	color: var(--accent);
}

/*
 * 信息卡：C# 端的"假玻璃"深色卡片。定位在灯的右下方，避免盖住箭头。
 */
.card {
	position: absolute;
	left: 12px;
	top: 10px;
	width: 246px;
	padding: 9px 11px;
	font-size: 12px;
	line-height: 1.5;
	color: var(--fg-secondary);
	background: var(--panel);
	border: 1px solid var(--line);
	border-radius: var(--radius);
	box-shadow: 0 12px 32px rgba(0, 0, 0, 0.72);
	cursor: default;
	z-index: 40;
}

.card-head {
	display: flex;
	align-items: baseline;
	justify-content: space-between;
	gap: 8px;
	margin-bottom: 7px;
	padding-bottom: 7px;
	border-bottom: 1px solid var(--hairline);
}

.coords {
	font-family: var(--font-value);
	font-size: 13px;
	color: var(--fg);
}

/* 坐标本身可点 = 复制（见模板里的说明）；悬停给一条下划线，让人知道这里能点 */
.copyable {
	cursor: copy;
}

.copyable:hover {
	color: var(--accent);
	text-decoration: underline dotted;
}

.state {
	flex: none;
	font-size: 11px;
}

.facts {
	display: grid;
	grid-template-columns: auto 1fr;
	gap: 2px 10px;
	margin: 0;
}

.facts dt {
	color: var(--fg-dim);
}

.facts dd {
	margin: 0;
	color: var(--fg-secondary);
	word-break: break-all;
}

.value {
	font-family: var(--font-value);
}

/* 卡片底部的两个动作（复制坐标 / 送进指令栏）：小按钮，鼠标移上去才显眼 */
.card-actions {
	display: flex;
	gap: 6px;
	margin-top: 7px;
	padding-top: 7px;
	border-top: 1px solid var(--hairline);
}

.mini {
	flex: 1;
	padding: 3px 6px;
	font-size: 11px;
	color: var(--fg-secondary);
	background: var(--panel-raised, rgba(255, 255, 255, 0.04));
	border: 1px solid var(--line);
	border-radius: 4px;
	cursor: pointer;
}

.mini:hover {
	color: var(--fg);
	border-color: var(--accent);
}
</style>



