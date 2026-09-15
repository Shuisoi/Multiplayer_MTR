<script setup lang="ts">
import {computed} from "vue";
import type {Signal} from "@/domain/Signal";
import type {Camera} from "@/domain/camera";
import {DECAL_KINDS, decalPlacement, decalTransform, pixelOffset, scaled, signalDecalOffset} from "@/domain/mapElements";
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
 * 相对锚点的偏移：方向 = **管辖方向**，距离 = 规格值 × 倍率（所以它跟地图一起变，不是屏幕 HUD）。
 *
 * <p>为什么沿管辖方向而不是横向（旧的 `sideOffsetDirection`）：整盏灯现在是**两个位置** ——
 * 灯点在后、方向箭头在前（用户 2026-09-15："信号灯图标的点和方向指示位置需要错开一些"），
 * 于是"锚点"应当落在**这一对的中段**。沿管辖方向偏移时，两个位置正好分列锚点前后，
 * 顺管辖方向看是"灯点 → 箭头"，与"信号机立在它所管区间的人口处、司机迎着它开"同向。</p>
 */
const offsetPx = computed(() => pixelOffset(
	props.signal.bearingDirection,
	scaled(DECAL_KINDS.signalSideOffset, zoomRatio.value),
));

/** 元素锚点：灯的世界坐标 → 屏幕 + 偏移。 */
const placement = computed(() => decalPlacement(props.signal.planeX, props.signal.planeY, props.camera, offsetPx.value));

/** 外层的位移。 */
const rootTransform = computed(() => decalTransform(placement.value));

/**
 * **灯点与方向箭头各自相对锚点的位移**（一处算、两处用；判据见 `signal-overlap.test.ts`）。
 *
 * <p>为什么两个都由 `signalDecalOffset` 给：用户 2026-09-15 要求"信号灯图标的点和方向指示位置
 * 需要错开一些"，而"错开"这件事只有在**同一个坐标系**里才量得准 —— 组件算一点、测试再算一遍，
 * 两边必然各漂各的。两个量都乘同一个倍率，所以错开比例在任何缩放下不变（不会推近了又粘上）。</p>
 */
const decalOffset = computed(() => signalDecalOffset(props.signal.angle, zoomRatio.value));


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

			**位置由 `signalDecalOffset` 给**：沿管辖方向前移，所以折角不再压在灯点上
			（用户 2026-09-15："信号灯图标的点和方向指示位置需要错开一些"）。
			尺寸 = 图标直径（8 px），与道岔菱形同一个大小；viewBox 不变，所以笔画比例也不变。
		-->
		<svg
			class="arrow"
			:style="{transform: `translate(-50%, -50%) translate(${decalOffset.arrow.x}px, ${decalOffset.arrow.y}px) rotate(${signal.arrowRotation}deg)`, color: stateColor}"
			:width="iconPx"
			:height="iconPx"
			viewBox="0 0 20 20"
		>
			<!-- 先描一条比底色暗的粗线做"描边"，再画本色：暗底上任何颜色都能看清 -->
			<path d="M 3 15 L 10 4 L 17 15" fill="none" stroke="#000000" stroke-width="7" stroke-linecap="round" stroke-linejoin="round"/>
			<path d="M 3 15 L 10 4 L 17 15" fill="none" stroke="currentColor" stroke-width="3.6" stroke-linecap="round" stroke-linejoin="round"/>
		</svg>
		<!-- 灯位：一个小圆点，颜色 = 状态。位置同样来自模型（沿管辖方向退到箭头后面）。 -->
		<div class="lamp" :style="{background: stateColor, '--dot-x': `${decalOffset.dot.x}px`, '--dot-y': `${decalOffset.dot.y}px`}"/>

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
 * 方向符号 `^`（SVG 画的折角）。
 *
 * <p>transform 有三件事，顺序不能换：`translate(-50%, -50%)` 把 SVG 的**中心**对到锚点，
 * 第二个 translate 把箭头沿管辖方向挪开（见 `signalArrowOffset`），最后才旋转。
 * 先旋转再平移的话，位移会跟着一起转 —— 那正是"越调越歪"的来源。
 * 旋转中心是元素中心（`transform-origin: 50% 50%`），所以折角绕自己的中心转、指向管辖方向。</p>
 */
.arrow {
	position: absolute;
	left: 0;
	top: 0;
	/*
	 * 箭头**不再画在灯点正中**：灯点是"状态在哪"，箭头是"管哪边"，两者各占一处。
	 *
	 * <p>历史上两者在同一个锚点上，折角的尖正好戳进灯点（用户 2026-09-15 报"需要错开一些"）。
	 * 位移量来自规格表（前移 8 px + 横向 3 px @6×），并**乘同一个倍率**，
	 * 所以拉远推近时这个错开比例不变 —— 不会出现"推近了又粘在一起"。</p>
	 */
	transform-origin: 50% 50%;
	pointer-events: none;
	filter: drop-shadow(0 0 1.5px #000000);
}

/*
 * 灯位圆点：尺寸与位置都来自规格表 **再乘当前倍率**（`DECAL_KINDS.lampDot` = 4 px @6×），
 * 所以 CSS 里不写死数字。
 *
 * <p>位置还要再加一层位移（`--dot-x/--dot-y`）：它沿管辖方向**退到方向箭头后面**
 * （用户 2026-09-15："信号灯图标的点和方向指示位置需要错开一些"）。两个位移来自
 * `domain/mapElements.ts#signalDecalOffset`，与箭头用的是同一个函数 —— 判据见 `signal-overlap.test.ts`。</p>
 *
 * <p>它必须比方向箭头小一圈 —— 两者现在已经错开（见 `signalDecalOffset`），但箭头仍可能扫过灯点附近，
 * 圆点大了就会把"方向"这件事重新压掉。</p>
 */
.lamp {
	position: absolute;
	/* 半径也做成变量：下面描边/发光的宽度由它派生，于是它们随倍率一起缩放。 */
	--lamp-r: v-bind('`${lampDotHalf}px`');
	left: calc(var(--dot-x, 0px) - var(--lamp-r));
	top: calc(var(--dot-y, 0px) - var(--lamp-r));
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



