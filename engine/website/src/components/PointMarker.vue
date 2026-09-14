<script setup lang="ts">
import {computed} from "vue";
import type {Point} from "@/domain/Point";

/*
 * 一个道岔（普通 HTML 元素，绝对定位在屏幕坐标上）。
 *
 * <p>显示三件事：**在哪**（位置由调用方给）、**现在开通哪条腿**（菱形里那个数字）、
 * **能怎么改**（点开后的腿按钮）。腿的序号与分类都来自引擎（`MmtrPoint.computeOrderedLegs`
 * 的排序：直通→左→右→其它），界面不自己按几何重排——两边各排一遍必然出现"界面说左、引擎走右"。</p>
 *
 * <p>为什么默认就画得这么显眼：道岔是**唯一需要人管**的东西（灯是自动出颜色的，道岔要有人定开通位）。
 * 世界里有四十来个道岔，藏在小圆点里就等于没有。</p>
 */

const props = defineProps<{
	point: Point;
	/** 视口内屏幕坐标（CSS 像素，道岔中心）。 */
	screen: {x: number; y: number};
	/** 轨 hex → 两端坐标：把"接哪条轨"说成坐标（用户按坐标认轨）。 */
	railEnds?: ReadonlyMap<string, {x1: number; z1: number; x2: number; z2: number}>;
	hovered: boolean;
	/** 打开着腿按钮面板（点一下道岔开、再点一下关）。 */
	expanded: boolean;
	/**
	 * 正在"改信号灯绑定"（画布进入 picking）。
	 *
	 * <p>这期间道岔**不响应点击**：用户此刻要点的是灯与轨，而道岔菱形挂在节点上、难免压到灯点上。
	 * 靠 z 序让灯压住菱形是巧合（菱形与灯点的先后渲染顺序一变就失效），所以这里做成显式条件。</p>
	 */
	picking?: boolean;
	/**
	 * 这个道岔是**当前选中**的那个。
	 *
	 * <p>选中的含义不止"我开着卡片"：画布会把它**当前开通那条腿的轨**在地图上点亮
	 * （见 `RailLayer` 的 `connectedRail`）。所以这里要有独立的一圈环，让"地图上那条亮线是哪个
	 * 道岔的"一眼可辨 —— 世界图里道岔挨得很近，没有这个环就认不出归属。</p>
	 */
	selected?: boolean;
}>();

const emit = defineEmits<{
	(e: "hover", key: string): void;
	(e: "toggle", point: Point): void;
	(e: "pickLeg", payload: {point: Point; leg: number}): void;
}>();

/** 点道岔：改绑定期间不响应（那时用户要选的是灯/轨）。 */
function onPointerDown() {
	if (props.picking) {
		return;
	}
	emit("toggle", props.point);
}

/** 当前开通的腿序号：操作员设过的优先，没设过就是 0（引擎默认直通）。 */
const activeLeg = computed(() => props.point.activeLeg);

/** 菱形里的数字：物理道岔显示**节点位置 0/1**，老式岔口显示这一行的腿号。 */
const markerNumber = computed(() => props.point.markerNumber);

/** 是否用的是默认值（没设过）。显示成"未设"而不是"0"，因为那是两种不同的实情。 */
const isDefault = computed(() => !props.point.isManuallySet);

/**
 * 物理道岔（单开道岔）的提示行。
 *
 * <p>用户 2026-09-13 的规格：位置 0 = 正线贯通（岔股禁止通行）、位置 1 = 岔股开放（正线被断开的那一侧
 * 禁止通行），两者互斥。所以卡片必须**先说道岔在哪一位、现在禁行哪一侧**，再说逐行腿号 ——
 * 反过来（先看腿号）就会把"这一侧禁止通行"读成"默认直通"。</p>
 */
const isTurnoutRow = computed(() => props.point.isTurnout);

/**
 * 一根轨在这处道岔上的"另一头"坐标（`(-67,-103)` 这种），认不出就给 hex 前 8 位。
 *
 * <p>为什么必须给坐标：用户说的正线是"(-67,-103) 到 (-67,-167)"、岔股是"到 (-35,-157)"，
 * 他读的是坐标；只写腿号/hex 他没法把这几位跟世界里的轨道对上（实测就是这么误读的）。</p>
 */
function farEndText(railHex: string): string {
	if (railHex === "") {
		return "（未知）";
	}
	const ends = props.railEnds?.get(railHex);
	if (ends === undefined) {
		return `${railHex.slice(0, 8)}…`;
	}
	// 取**远离本节点**的那一端：那才是"这条轨通向哪里"
	const nodeX = props.point.x;
	const nodeZ = props.point.z;
	const tail = Math.abs(ends.x1 - nodeX) + Math.abs(ends.z1 - nodeZ) > Math.abs(ends.x2 - nodeX) + Math.abs(ends.z2 - nodeZ)
		? {x: ends.x1, z: ends.z1}
		: {x: ends.x2, z: ends.z2};
	return `(${tail.x}, ${tail.z})`;
}

/** 位置 0/1 各接哪两条轨（用坐标说，例：`(-67,-103) ↔ (-67,-167)`）。 */
function positionPairText(position: number): string {
	const other = position === 1 ? props.point.branchHex : props.point.farHex;
	return `${farEndText(props.point.stemHex)} ↔ ${farEndText(other)}`;
}

const facts = computed(() => [
	{label: "形态", value: props.point.formText},
	...(props.point.isTurnout ? [] : [{label: "腿数", value: `${props.point.legs.length} 条`}]),
	{label: "开通", value: props.point.stateText},
	...(props.point.isTurnout
		? [{label: "禁行", value: `${props.point.prohibitedText} ${farEndText(props.point.prohibitedHex)}`}]
		: []),
	{label: "盆轨", value: `${props.point.via.slice(0, 12)}…`},
	{
		label: "地图上",
		value: props.selected && props.point.activeLegObject !== null
			? `琥珀色加粗的那条轨 = 当前开通的腿 ${activeLeg.value}（${props.point.activeLegObject.kindText}）`
			: "选中这个道岔后，它当前联通的腿会在地图上点亮",
	},
	{label: "操作", value: props.point.isTurnout
			? "点「位置 0 / 位置 1」= 扳动这处道岔（两个位置互斥）"
			: (props.expanded ? "点某条腿 = 把道岔扳到那条" : "点这个道岔 = 展开腿按钮")},
]);
</script>

<template>
	<div
		class="point"
		:class="{hovered, expanded, selected, 'is-default': isDefault, locked: point.locked}"
		:data-key="point.key"
		:style="{transform: `translate(${screen.x}px, ${screen.y}px)`}"
		@pointerenter="emit('hover', point.key)"
		@pointerleave="emit('hover', '')"
		@pointerdown.stop="onPointerDown"
	>
		<!--
			一条细连线把菱形系回它所属的节点：偏移 26px 之后，世界图里节点很密，
			没有这条线用户认不出这个菱形挂在哪个节点上。
		-->
		<svg class="leader" width="34" height="34" viewBox="0 0 34 34" aria-hidden="true">
			<line x1="2" y1="2" x2="32" y2="32" stroke="#f59e0b" stroke-width="1.2" stroke-opacity="0.5"/>
		</svg>

		<!--
			菱形：四边等长的方块旋转 45°。用菱形而不是圆点，是为了和节点圆点、灯点在同一张图上
			一眼分得开（道岔是"要人管的东西"，形状本身就应当不同）。
			里面的数字 = 当前开通的腿序号。
			外面那层 `.hit` 是点击靶（比菱形大一圈，但整体偏在节点右下方，见样式里的说明）。
		-->
		<div class="hit">
			<div class="diamond">
				<span class="leg-number">{{ markerNumber }}</span>
			</div>

			<div v-if="hovered || expanded" class="card">
				<div class="card-head">
					<span class="coords value">{{ point.coords }}</span>
					<span class="form">{{ point.formText }}</span>
				</div>

				<!--
					物理道岔：**先说道岔在哪一位、这一位联通哪两条轨**。
					位置是两个互斥进路的开关（0 正线贯通 / 1 岔股开放），逐行腿号只是它的派生视图 ——
					顺序反了就会把"禁止通行"读成"默认直通"，也会让人以为"从岔股能开到正线远端"。
					两个位置按钮只画在**根部那一行**上（合并后就是唯一那个标记）：只有那一行的腿能表达
					两个位置，别的进向只能说"我这一侧现在通不通"。
				-->
				<div v-if="isTurnoutRow" class="turnout">
					<div class="turnout-head">
						<span class="badge">位置 {{ point.turnoutPosition }}</span>
						<span class="turnout-state">{{ point.positionText }}</span>
					</div>
					<template v-if="point.isStemRow">
						<button
							v-for="candidate in [0, 1]"
							:key="candidate"
							class="position"
							:class="{active: candidate === point.turnoutPosition}"
							type="button"
							:title="point.turnoutPositionText(candidate)"
							:disabled="point.turnoutLegFor(candidate) < 0"
							@pointerdown.stop="emit('pickLeg', {point, leg: point.turnoutLegFor(candidate)})"
						>
							<b>{{ candidate }}</b>
							<span class="position-name">{{ candidate === 1 ? "岔股开放" : "正线贯通" }}</span>
							<span class="position-pair">{{ positionPairText(candidate) }}</span>
						</button>
					</template>
					<div v-else class="turnout-note">
						这一行是从别的进向看的：位置由道岔决定，{{ point.prohibitedText }}现在禁止通行。
					</div>
				</div>

				<div v-else-if="point.whyNotTurnoutText !== ''" class="turnout">
					<!--
						不是单开道岔的节点：**同一张卡片**上说明为什么（引擎的一行结论，与 point why 同源）。

						用户 2026-09-14："道岔的呈现要统一" —— 这类节点（三岔口 / 四条线交汇）不该换一套
						卡片形状让人猜"为什么这里和别处不一样"，而应就地给出原因。
					-->
					<div class="turnout-head">
						<span class="badge badge-quiet">不是单开道岔</span>
					</div>
					<div class="turnout-note">{{ point.whyNotTurnoutText }}</div>
				</div>

				<dl class="facts">
					<template v-for="fact in facts" :key="fact.label">
						<dt>{{ fact.label }}</dt>
						<dd>{{ fact.value }}</dd>
					</template>
				</dl>

				<!--
					腿按钮：**只有老式岔口（没有物理道岔模型的按进向 0/1）才逐个列出**。
					单开道岔不列：它的三行里必然有一行（"从岔股进来"）同时列着正线远端那条腿，
					看起来就像"能从 -35,-157 直接开到 -67,-167" —— 现实里那是背向穿过尖轨，物理上不存在。
					物理道岔只有两个位置，上面的两个按钮就是它的全部操作。
				-->
				<div v-if="!isTurnoutRow" class="legs">
					<button
						v-for="leg in point.legs"
						:key="leg.index"
						class="leg"
						:class="{active: leg.index === activeLeg, blocked: leg.prohibited}"
						type="button"
						:title="`轨 ${leg.railHex}${leg.prohibited ? '（当前位置下禁止通行）' : ''}`"
						@pointerdown.stop="emit('pickLeg', {point, leg: leg.index})"
					>
						<b>{{ leg.index }}</b>
						<span>{{ leg.kindText }}{{ leg.prohibited ? "（禁行）" : "" }}</span>
					</button>
				</div>
				<div class="hint">
					<template v-if="isTurnoutRow">
						道岔位置由人设定（0 正线贯通＝接通正线两端；1 岔股开放＝正线远端那一侧禁止通行）
						· 位置 0 时岔股禁止通行，两条进路互斥
					</template>
					<template v-else>{{ isDefault ? "当前是默认位（未设定，按 0 直通）" : `已设定为腿 ${activeLeg}` }}</template>
					<template v-if="point.locked">　·　已锁闭</template>
					<template v-if="point.holder">　·　被 {{ point.holder }} 持有（腿 {{ point.holderLeg }}）</template>
				</div>
			</div>
		</div>
	</div>
</template>

<style scoped>
/*
 * 零尺寸锚点放在道岔中心：`translate()` 的数值就是中心。
 *
 * <p><b>整层不吃事件</b>（`pointer-events: none`），只有右上角那个点击靶吃 —— 这一条是必需的：
 * 道岔节点上常常**同时立着一盏信号灯**（信号机就放在道岔旁），而道岔层在最上面。
 * 如果这一层以中心为靶（哪怕只有 16px），灯点的悬停就会被整片吃掉（实测：悬停灯位不出信息卡）。
 * 所以靶偏到右上方、当成"挂在节点上的徽标"：那里没有灯点，点击仍然好点。</p>
 */
.point {
	position: absolute;
	left: 0;
	top: 0;
	width: 0;
	height: 0;
	pointer-events: none;
	cursor: pointer;
}

/*
 * 菱形与它的命中区。
 *
 * <h3>偏移 34px 是**算出来的下限**，不是手感</h3>
 * <p>道岔节点上常常**同时立着一盏信号灯**（信号机就放在道岔旁），道岔层又在灯层之上，
 * 所以两个矩形必须**在几何上不可能相接**。两侧的数字：</p>
 * <ul>
 *   <li>灯点：圆点 7px + 悬停环，实测命中半径 ≈ 8px（直径 16px）；</li>
 *   <li>道岔菱形：16px 方块转 45°，**外接框是 23px**（关键尺寸不是 16），半宽 11.5px。</li>
 * </ul>
 * <p>最坏情况是"道岔节点与立着灯的节点相距 1px"（实测就有这种，两个点中心几乎重合）：
 * 菱形至少要从自己中心退 11.5px，加上灯那 16px 的直径，偏移必须 ≥ 11.5 + 16 + 余量。
 * 取 34px，最坏情况下两者之间仍留 6.5px。</p>
 *
 * <p>实测过的四个偏移都会漏：5px（切到灯点 2px）、12px（2px 缝）、18px（仍有 2 个叠上）、
 * 28px（1px 缝）。现象一律是 `elementsFromPoint` 在灯点上返回 `diamond` 而不是 `lamp` ——
 * "悬停灯位不出信息卡"。这种差几像素的遮挡看截图看不出来，只能按矩形相不相交来定。</p>
 */
.hit {
	position: absolute;
	left: 34px;
	top: 34px;
	width: 23px;
	height: 23px;
	display: flex;
	align-items: center;
	justify-content: center;
	pointer-events: none;
}

/*
 * 一条细连线：把菱形系回它所属的节点。
 *
 * <p>偏移到 34px 之后，菱形纯靠位置已经认不出它属于哪个节点（世界图里节点很密），
 * 这条线是"它挂在这个节点上"的唯一凭据 —— 没有它，用户点到的可能是旁边那个道岔。</p>
 */
.leader {
	position: absolute;
	left: 3px;
	top: 3px;
	width: 34px;
	height: 34px;
	overflow: visible;
	pointer-events: none;
}

/*
 * 菱形：14×14 的方块旋转 45°，加一圈暗描边（暗底上任何颜色都看得清）。
 * 默认开通位用琥珀色（"需要人管"），已锁闭用红色。
 */
.diamond {
	width: 16px;
	height: 16px;
	transform: rotate(45deg);
	display: flex;
	align-items: center;
	justify-content: center;
	background: #f59e0b;
	box-shadow: 0 0 0 1.5px #000000, 0 0 8px rgba(245, 158, 11, 0.55);
	border-radius: 2px;
	pointer-events: auto;
	cursor: pointer;
}

/* 卡片自己吃事件（要能点腿按钮）：绝对定位，脱离 flex 布局，不影响靶的尺寸 */
.card {
	pointer-events: auto;
}

/* 里面的数字要转回来，否则跟着菱形一起歪 */
.leg-number {
	transform: rotate(-45deg);
	font-family: var(--font-value);
	font-size: 10px;
	font-weight: 700;
	line-height: 1;
	color: #1a1204;
}

/* 未设定（走默认 0）：空心，提示"这一位不是人定的" */
.point.is-default .diamond {
	background: rgba(245, 158, 11, 0.28);
	box-shadow: 0 0 0 1.5px #000000, inset 0 0 0 1.5px #f59e0b, 0 0 8px rgba(245, 158, 11, 0.35);
}

.point.is-default .leg-number {
	color: #fbbf24;
}

.point.hovered .diamond,
.point.expanded .diamond {
	box-shadow: 0 0 0 2px #000000, 0 0 0 4px var(--accent), 0 0 14px var(--accent);
}

.point.expanded {
	z-index: 30;
}

/* 悬停/展开时的强调环也挂在靶上（菱形本身太小，环画大会被裁） */
.point.hovered .hit,
.point.expanded .hit {
	background: rgba(245, 158, 11, 0.12);
}

/*
 * 选中的道岔：菱形加一圈亮环 + 更强的光晕。
 * 与 hover 的区别要看得出来 —— 悬停是一时的，选中是"地图上那条琥珀色轨线属于我"，
 * 所以选中用**实心亮环**（不透明），也不随指针离开而消失。
 */
.point.selected .diamond {
	box-shadow: 0 0 0 2px #000000, 0 0 0 4px #f59e0b, 0 0 16px rgba(245, 158, 11, 0.9);
}

/* 选中时那条系回节点的连线也加亮：它是"这个亮线归哪个节点"的凭据 */
.point.selected .leader line {
	stroke-opacity: 1;
	stroke-width: 2;
}

.point.locked .diamond {
	background: #ef4444;
	box-shadow: 0 0 0 1.5px #000000, 0 0 8px rgba(239, 68, 68, 0.6);
}

.card {
	position: absolute;
	left: 30px;
	top: 30px;
	width: 268px;
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

.form {
	flex: none;
	font-size: 11px;
	color: var(--fg-dim);
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

/* 腿按钮排成一行：腿数 2~4，不需要换行 */
.legs {
	display: flex;
	flex-wrap: wrap;
	gap: 6px;
	margin-top: 9px;
	padding-top: 9px;
	border-top: 1px solid var(--hairline);
}

.leg {
	display: flex;
	align-items: center;
	gap: 5px;
	padding: 3px 9px;
	font-family: var(--font-ui);
	font-size: 12px;
	color: var(--fg-secondary);
	background: transparent;
	border: 1px solid var(--line);
	border-radius: var(--radius);
	cursor: pointer;
}

.leg b {
	font-family: var(--font-value);
	color: var(--fg);
}

.leg:hover {
	color: var(--fg);
	border-color: #4a4a4a;
}

.leg.active {
	color: #1a1204;
	background: #f59e0b;
	border-color: #f59e0b;
}

.leg.active b {
	color: #1a1204;
}

/* 当前位置下禁止通行的腿：划掉并标红，它点不动也走不了 */
.leg.blocked {
	border-color: #7f1d1d;
	color: #fca5a5;
	text-decoration: line-through;
}

.leg.blocked b {
	color: #fca5a5;
}

/*
 * 物理道岔那一块：位置 + 两个位置按钮（只画在根部那一行）。
 * 与腿按钮的视觉分工：位置按钮是"开关"（0/1 两个互斥进路），腿按钮是"从这一行进向看哪条轨"。
 */
.turnout {
	margin-top: 8px;
	padding-top: 8px;
	border-top: 1px solid var(--hairline);
}

.turnout-head {
	display: flex;
	align-items: baseline;
	gap: 8px;
	margin-bottom: 6px;
}

.badge {
	padding: 1px 7px;
	font-family: var(--font-value);
	font-size: 11px;
	color: #1a1204;
	background: #f59e0b;
	border-radius: 999px;
}

/* 「不是单开道岔」用同一个徽标形状、不同的语气：说的是事实，不是待操作的状态 */
.badge-quiet {
	color: var(--fg-secondary);
	background: transparent;
	border: 1px solid var(--hairline);
}

.turnout-state {
	color: var(--fg);
}

.turnout-note {
	font-size: 11px;
	color: var(--fg-faint);
}

.position {
	display: flex;
	align-items: center;
	flex-wrap: wrap;
	gap: 5px;
	margin-right: 6px;
	margin-bottom: 6px;
	padding: 3px 9px;
	font-family: var(--font-ui);
	font-size: 12px;
	color: var(--fg-secondary);
	background: transparent;
	border: 1px solid var(--line);
	border-radius: var(--radius);
	cursor: pointer;
}

/* "接哪两条轨"另起一行、用坐标写（用户按坐标认轨，不读 hex） */
.position-pair {
	flex-basis: 100%;
	font-family: var(--font-value);
	font-size: 10.5px;
	color: var(--fg-faint);
}

.position.active .position-pair {
	color: #4a3405;
}

.position b {
	font-family: var(--font-value);
	color: var(--fg);
}

.position:hover:not(:disabled) {
	color: var(--fg);
	border-color: #4a4a4a;
}

.position.active {
	color: #1a1204;
	background: #f59e0b;
	border-color: #f59e0b;
}

.position.active b {
	color: #1a1204;
}

.position:disabled {
	opacity: 0.45;
	cursor: not-allowed;
}

.hint {
	margin-top: 8px;
	font-size: 11px;
	color: var(--fg-faint);
}
</style>
