<script setup lang="ts">
import {computed} from "vue";
import type {Signal} from "@/domain/Signal";
import {DECAL_KINDS, decalPlacement, decalTransform, pixelOffset, specToWorld, signalUnitAnchor, signalUnitChevronPath} from "@/domain/mapElements";

/*
 * 一个信号灯（**地图上的元素**：位置与尺寸都跟着摄像机走）。
 *
 * ============================ 这一版是整个重画的 ============================
 *
 * 用户 2026-09-15 连着提了三次，前两版的错法各不相同，所以这次不是再挪一下位置，
 * 而是**把整盏灯画成一个整体、再整体放置**：
 *
 *   ① "信号灯图标的点和方向指示位置需要**错开一些**" —— 原来两个图形是两个绝对定位的元素，
 *      各自算各自的位置，中心重合（折角尖的可见外缘离中心只 1.68 px，而灯点半径 2 px）；
 *   ② "**现在距离又太大了**" —— 改成"箭头前移 12 + 灯点后退 8"（轴距 20 px）后离得太远；
 *   ③ "**现在箭头又一左一右了**" —— 再改成"侧向 14 px"后，位移跟着朝向转，于是南行灯在左、
 *      北行灯在右。**根因是位移挂在"管辖方向"那个会转的坐标系上**。
 *
 * 所以现在：
 *   · **一个 `<svg>`**（viewBox 固定 `0 0 20 26`）里同时画灯点与折角 —— 内部几何是常量，
 *     只有一处可以出错，而那一处有单测钉住（`signal-overlap.test.ts`）；
 *   · **只做一次放置**：位移 = 世界坐标 → 屏幕 + 灯位偏移（`signalSideOffset`），
 *     旋转 = 管辖方向。**位移不随朝向变**（整盏灯一起转），所以不会一左一右；
 *   · 折角画在灯点**正上方**（同一组里一起转），箭头指向 = 管辖方向。
 */

const props = defineProps<{
	signal: Signal;
	/** 当前相机：位置由它算（组件不接收屏幕坐标，"位置"只有一个来源）。 */
	hovered: boolean;
	/** 正在改这盏灯的绑定（点选绑定）：加一圈强调环。 */
	selected?: boolean;
}>();

/**
 * 贴片规格（**唯一真源** `domain/mapElements.ts`）。
 *
 * <p>用户口径："所谓的 8 px 是缩放为 6× 的时候大小是 8 px，这个应该跟随缩放变换大小 ——
 * 可以理解为摄像机在移动，地图大小和位置关系不动"。所以 6× 时图标 8 px；推近到 12× 是 16 px；
 * 拉远到 3× 是 4 px。组件里不再出现任何写死的像素值。</p>
 */


/**
 * 图标宽度（**世界单位**）：规格 × `unitsPerPx`（全览时屏幕上就是规格 8 px）。
 *
 * <p>整套系统里只有这一处尺寸换算，之后由相机统一缩放 —— 见 `specToWorld`。</p>
 */
const iconPx = computed(() => specToWorld(DECAL_KINDS.icon));
/** 整个 SVG 的高度：viewBox 是 20 × 20.5，高度按同一个比例走。 */
const boxHeightPx = computed(() => iconPx.value * (DECAL_KINDS.signalUnit.boxHeight / DECAL_KINDS.signalUnit.boxWidth));
/** 灯点在 viewBox 里的位置（世界坐标就落在它上面，也是旋转中心）。 */
const anchor = signalUnitAnchor();
/** 折角的线心折线：**与单测同一份几何**（`signalUnitChevronPath`），不许在模板里另写一遍。 */
const chevronPath = signalUnitChevronPath();
/**
 * 整盏灯相对锚点的偏移（**屏幕像素**）：让**灯点**落在锚点上。
 *
 * <p>灯点不在 SVG 盒子的正中心（折角要画在它上方），所以整盒要往左上让这么多 ——
 * 与 `rootTransform` 里那个 `translate(-anchorX -anchorY)` 是同一个量，
 * 只是单位从 viewBox 单位换成了像素。</p>
 *
 * <p><b>为什么不用百分比</b>：`left: -50%` 里的百分比是按**包含块**的宽度解析的，
 * 而 `.signal` 是零尺寸锚点 ⇒ 包含块宽 0 ⇒ `-50%` 解析成 `0px`，
 * 整盒左上角直接压在锚点上、还右移了半个盒子。**这个坑实测把 96 盏灯全画到了地图左上角**
 * （`getBoundingClientRect` 全是同一个点），所以这里必须是实打实的像素。</p>
 */
/**
 * 整盏灯相对锚点的偏移（**屏幕像素**）：`left/top` 取"灯点在盒子里的位置"的相反数。
 *
 * <h3>这个值是在页面上试出来的，不是推导出来的</h3>
 * <p>判据只有一个：**灯点必须落在锚点上**（锚点 = 世界坐标 + 灯位偏移，由 `.signal` 的位移给）。
 * `sandbox/signal-offset-trial.js` 把 `.unit` 的 `left/top` 换成几组候选、各量 24 盏灯：</p>
 *
 * <table>
 *   <tr><th>取值</th><th>偏离锚点（均值 / 最坏，px）</th></tr>
 *   <tr><td><b>灯点在盒子里的位置（取反）</b></td><td><b>(0, −0.004) / 0.013</b></td></tr>
 *   <tr><td>半个盒子</td><td>(0, 0.86) / 2.06</td></tr>
 *   <tr><td>中心 − 灯点</td><td>(1.48, 3.23) / 8.53</td></tr>
 *   <tr><td>0,0</td><td>(1.48, 2.37) / 6.72</td></tr>
 * </table>
 *
 * <p>推导过两轮都差一个"盒子尺寸"的常数项：原因是旋转中心不是盒子的几何中心，而是
 * `left/top` 之后**那个盒子的中心**（`transform-origin: 50% 50%`），而绝对定位的 SVG 还在
 * `.rot`（零尺寸包含块）里 —— 常数项在中间被抵消掉了。**结论：别再用推导定这个数，
 * 它由上面那个页面试验定，并且由 `check-web-signal-unit.ps1` 的"灯点在锚点上"看着。**</p>
 */
const boxOffsetPx = computed(() => {
	const anchor = signalUnitAnchor();
	const unit = DECAL_KINDS.signalUnit;
	return {
		x: (anchor.x / unit.boxWidth) * iconPx.value,
		y: (anchor.y / unit.boxHeight) * boxHeightPx.value,
	};
});

/**
 * 灯位偏移（**世界单位**）：规格 × `unitsPerPx`（与尺寸用同一个常量）。
 *
 * <p>父容器（标记层的 `.layer`）承担相机，所以标记的 `left/top` 是**世界坐标**；
 * 偏移与尺寸一样只需要乘那一个常量，之后就由相机统一缩放。</p>
 */
const offsetWorld = computed(() => pixelOffset(
	props.signal.sideOffsetDirection,
	specToWorld(DECAL_KINDS.signalSideOffset),
));

/** 灯点的**世界坐标**（世界坐标 + 灯位偏移）：**锚点**，旋转绕它发生。 */
const anchorPx = computed(() => decalPlacement(props.signal.planeX, props.signal.planeY, offsetWorld.value));

/** 内层的反向缩放：父容器已被相机缩放，这里乘回去 ⇒ 整盏灯屏幕尺寸恒定。 */
const counterScale = computed(() => 1);

/**
 * 外层的位移：**只有平移，没有旋转**。
 *
 * <p>旋转在里层的 `span.rot` 上（见下），所以这一层里的东西（信息卡）永远是正的 ——
 * 用户 2026-09-15："向下的信号灯鼠标移上去**弹窗也是反的**"。原因是当时卡片是
 * **被旋转元素的后代**：整盏灯转了 180°（朝下的灯），`<div>` 里的文字跟着倒过来。
 * 现在"会转的只有图形"，卡片挂在不会转的这一层上。</p>
 */
const rootTransform = computed(() => decalTransform(anchorPx.value));

/**
 * 整盏灯的旋转：**绕盒子中心**转到管辖方向。
 *
 * <p>盒子中心已经用 `left/top: -b`（`boxOffsetPx`）摆到了**灯点**上，所以 `rotate()` 的
 * `transform-origin`（默认 = 盒子中心）正好就是灯点 —— 不需要再写 `translate(中心)…translate(−中心)`。
 * 反过来说：**定位偏移只能写一处**，两处叠加会把旋转中心顶到别处（详见 `boxOffsetPx` 的说明）。</p>
 */
const unitTransform = computed(() => `rotate(${props.signal.arrowRotation}deg)`);

const emit = defineEmits<{
	(e: "hover", key: string): void;
	(e: "pick", key: string): void;
	/** 复制这盏灯的坐标（指令格式 `x y z`）。 */
	(e: "copy", signal: Signal): void;
	/** 把 `signal why x y z` 送进网页指令栏（不依赖剪贴板）。 */
	(e: "why", signal: Signal): void;
}>();

/** 状态 → 颜色。与引擎那套一致：红/黄/绿用高饱和，未接入用灰。 */
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
		:data-zoom-ratio="signal.angle"
		:data-angle="signal.angle"
		:data-world="`${signal.planeX},${signal.planeY}`"
		:style="{transform: rootTransform}"
		@pointerenter="emit('hover', signal.key)"
		@pointerleave="emit('hover', '')"
		@pointerdown.stop="emit('pick', signal.key)"
	>
		<!--
			**会转的只有图形**（`span.rot`），信息卡挂在外层（不转）——
			否则朝下的灯（180°）会把卡片里的字一起倒过来（用户 2026-09-15："弹窗也是反的"）。
		-->
		<span class="rot" :style="{transform: unitTransform}">
			<!--
				整盏灯就这一个 SVG：灯点（状态色圆点）+ 折角（指向管辖方向）。
				内部几何是常量（viewBox 0 0 20 20.5），所以不存在"两个图形各自算位置、
				算着算着粘上/跑远/换边"这件事。
			-->
			<svg
				class="unit"
				:width="iconPx"
				:height="boxHeightPx"
				:viewBox="`0 0 ${DECAL_KINDS.signalUnit.boxWidth} ${DECAL_KINDS.signalUnit.boxHeight}`"
				:style="{'--box-x': `${boxOffsetPx.x}px`, '--box-y': `${boxOffsetPx.y}px`, transform: `scale(${counterScale})`}"
			>
			<!--
				折角：一个 `^`，尖朝组的上方。整组会被旋转到管辖方向，所以它指向管辖方向。
				路径与笔画宽都来自 `DECAL_KINDS.signalUnit`（与单测同一份几何）。
			-->
			<path
				class="chevron-outline"
				:d="chevronPath"
				:stroke-width="DECAL_KINDS.signalUnit.chevronOutline"
				fill="none"
				stroke-linecap="round"
				stroke-linejoin="round"
			/>
			<path
				class="chevron"
				:d="chevronPath"
				:stroke="stateColor"
				:stroke-width="DECAL_KINDS.signalUnit.chevronStroke"
				fill="none"
				stroke-linecap="round"
				stroke-linejoin="round"
			/>
			<!-- 灯点：圆心就是"信号机立在哪"（锚点），所以它在世界坐标上、不随旋转移动 -->
			<circle
				class="lamp"
				:cx="anchor.x"
				:cy="anchor.y"
				:r="DECAL_KINDS.signalUnit.lampRadius"
				:fill="stateColor"
			/>
			</svg>
		</span>

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
 * 零尺寸锚点放在信号灯**灯点**的屏幕位置上：`transform` 里的位移就是灯点的位置
 * （世界坐标 + 灯位偏移）。外层**不带旋转**，所以挂在这一层的信息卡永远是正的。
 */
.signal {
	position: absolute;
	left: 0;
	top: 0;
	width: 0;
	height: 0;
	pointer-events: auto;
	cursor: pointer;
}

/*
 * **会转的那一层**：只装图形（SVG），绕灯点转到管辖方向。
 *
 * <p>零尺寸、无 `position`（保持 static，让里面的绝对定位参照 `.signal` 而不是它 ——
 * 它带 transform 会自己成为包含块，所以里面的 `left/top` 百分比仍然按它解析；
 * 好在整盒用的是像素，不受影响）。</p>
 */
.rot {
	position: absolute;
	left: 0;
	top: 0;
	width: 0;
	height: 0;
}

/*
 * 整盏灯。它相对锚点是**负偏移**（`left/top` 由模板按灯点在 viewBox 里的位置给），
 * 于是"灯点"正好落在锚点上 —— 这是"灯点 = 世界坐标"这条规格的实现方式。
 *
 * 命中区就是整个盒子（含折角），因为折角也是这盏灯的一部分；它比原来的 4 px 圆点大一圈，
 * 反而更好点。
 */
.unit {
	position: absolute;
	/*
	 * 整盒往左上让，使**灯点**（而不是盒子中心）落在锚点上。
	 * 这里必须是像素：百分比会按**包含块**（零尺寸的 `.signal`）解析而变成 0 ——
	 * 实测那一版把 96 盏灯全画到了地图左上角。
	 */
	left: calc(var(--box-x) * -1);
	top: calc(var(--box-y) * -1);
	overflow: visible;
	filter: drop-shadow(0 0 1.5px #000000);
}

/* 折角的黑色描边：比本色笔画宽一圈，暗底上做"描边"用（宽度由模板按规格给） */
.chevron-outline {
	stroke: #000000;
}

/* 灯点：没有描边、没有发光 —— 位置就是它的语义（"信号机立在哪"），不要用装饰把它画大 */
.lamp {
	stroke: none;
}

/* 悬停 / 选中：给灯点加一圈环（比 hover 更醒目，且不随指针离开消失） */
.signal.hovered .lamp {
	stroke: rgba(255, 255, 255, 0.45);
	stroke-width: 1.6;
}

.signal.selected .lamp {
	stroke: var(--accent);
	stroke-width: 1.6;
}

/*
 * 信息卡：C# 端的"假玻璃"深色卡片。**挂在不会转的那一层**（`.signal` 只有位移、没有 rotate），
 * 所以不管灯朝哪边，卡片永远是正的、永远在锚点的右下方。
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

/* 守轨列表：等宽小字，一行一条 */
.guarded {
	margin: 6px 0 0;
	padding: 6px 0 0;
	border-top: 1px solid var(--hairline);
	list-style: none;
	font-family: var(--font-value);
	font-size: 11px;
	color: var(--accent);
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
