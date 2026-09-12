<script setup lang="ts">
import {computed} from "vue";
import type {Signal} from "@/domain/Signal";

/*
 * 一个信号灯（普通 HTML 元素，绝对定位在屏幕坐标上）。
 *
 * <p>显示三件事：**状态**（颜色）、**方向**（箭头 `^`）、**在哪**（位置由调用方给）。
 * 方向和状态都是引擎给的（`Signal.angle` / `Signal.state`），这里只做映射。</p>
 *
 * <p>为什么用 HTML 而不是 SVG：和节点层同一理由。信号灯是"地图标注"，大小固定、永远清晰，
 * 缩放只改它落在哪里。放进 SVG 就要再面对一次 viewBox 缩放（旧版踩过，见 camera.ts）。</p>
 */

const props = defineProps<{
	signal: Signal;
	/** 视口内屏幕坐标（CSS 像素，信号灯中心）。 */
	screen: {x: number; y: number};
	hovered: boolean;
}>();

const emit = defineEmits<{
	(e: "hover", key: string): void;
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
	{label: "朝向", value: `${props.signal.directionText}　（角 ${props.signal.angle}°）`},
	{label: "灯位", value: props.signal.aspectsText},
	{label: "模式", value: props.signal.mode === "BOUND" ? `绑定（${props.signal.target.slice(0, 12)}…）` : "自动（按位置推断）"},
	{label: "开区间", value: props.signal.hasSection ? "是" : "未接入闭塞层"},
]);
</script>

<template>
	<div
		class="signal"
		:class="{hovered}"
		:style="{transform: `translate(${screen.x}px, ${screen.y}px)`}"
		@pointerenter="emit('hover', signal.key)"
		@pointerleave="emit('hover', '')"
	>
		<!--
			方向：`^` 字符按朝向角旋转（基准朝上 = 北）。用字符而不是三角形，
			是用户要求的表现形式；好处也实在——字体里的 `^` 天然居中、旋转起来"指向哪边"一眼能看懂。
		-->
		<div
			class="arrow value"
			:style="{transform: `translate(-50%, -50%) rotate(${signal.arrowRotation}deg)`, color: stateColor}"
		>^</div>
		<!-- 灯位：一个小圆点，颜色 = 状态。 -->
		<div class="lamp" :style="{background: stateColor}"/>

		<div v-if="hovered" class="card">
			<div class="card-head">
				<span class="coords value">{{ signal.coords }}</span>
				<span class="state" :style="{color: stateColor}">{{ signal.stateText }}</span>
			</div>
			<dl class="facts">
				<template v-for="fact in facts" :key="fact.label">
					<dt>{{ fact.label }}</dt>
					<dd>{{ fact.value }}</dd>
				</template>
			</dl>
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
 * 方向符号 `^`：以灯位为原点旋转。
 * 外层 translate(-50%,-50%) 让字符的**中心**落在灯位上，再按朝向角旋转 ——
 * 顺序不能反（先旋转再位移会把位移也一起转掉，方向就会偏）。
 */
.arrow {
	position: absolute;
	left: 0;
	top: 0;
	font-size: 15px;
	font-weight: 700;
	line-height: 1;
	transform-origin: 50% 50%;
	text-shadow: 0 0 3px #000000, 0 0 3px #000000;
	pointer-events: none;
}

/* 灯位：3.5px 的小圆点，加一圈暗描边在暗底上更清晰 */
.lamp {
	position: absolute;
	left: -3.5px;
	top: -3.5px;
	width: 7px;
	height: 7px;
	border-radius: 50%;
	box-shadow: 0 0 0 1.5px #000000, 0 0 6px currentColor;
}

.signal.hovered .lamp {
	box-shadow: 0 0 0 1.5px #000000, 0 0 0 3.5px rgba(255, 255, 255, 0.35);
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
</style>
