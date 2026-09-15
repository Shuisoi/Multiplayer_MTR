<script setup lang="ts">
import {computed} from "vue";
import type {Camera} from "@/domain/camera";
import type {Point} from "@/domain/Point";
import {DECAL_KINDS, decalPlacement, decalTransform, pixelOffset, pxToWorld} from "@/domain/mapElements";

/*
 * 一个道岔（**贴片元素**：位置由世界坐标定，尺寸恒为固定屏幕像素）。
 *
 * <p>三件事：**在哪**、**现在开通哪条腿**（菱形里那个数字）、**能怎么改**（点开后的腿按钮）。
 * 腿的序号与分类都来自引擎（`Point.computeOrderedLegs` 的排序：直通→左→右→其它），
 * 界面不自己按几何重排——两边各排一遍必然出现"界面说左、引擎走右"。</p>
 *
 * <p>为什么形状是菱形：它和节点圆点、灯点在同一张图上一眼分得开（道岔是"要人管的东西"，
 * 形状本身就应当不同）。</p>
 *
 * <p>尺寸与偏移**全部来自 `domain/mapElements.ts`**（贴片 8 px、偏移 16 px、引线 16 px），
 * 本组件不再自己定像素值。</p>
 */



/** 缩放倍率（画布注入；拿不到按 1 算）。 */
/** 菱形边长：**规格值 × 倍率**（6× 时为 8 px）。 */
const viewScale = computed(() => (props.camera.scale > 0 ? props.camera.scale : 1));
const diamondPx = computed(() => pxToWorld(DECAL_KINDS.turnoutDiamond, viewScale.value));
/** 引线长度同理跟着倍率走。 */
const leaderPx = computed(() => pxToWorld(DECAL_KINDS.turnoutLeader, viewScale.value));

/**
 * 菱形挂靠方向：**右下方**（屏幕对角）。
 *
 * <p>为什么挂出去而不是画在节点上：道岔节点上常常同时立着一盏信号灯（信号机就放在道岔旁），
 * 而道岔层在灯层之上 —— 菱形压在灯点上就点不到灯了。偏移距离由规格表给（{@code turnoutOffset}），
 * 那个值是**算出来的下限**（菱形外接框半宽 + 灯的命中半径 + 余量），见样式里的说明。</p>
 */
const DIAGONAL = {x: Math.SQRT1_2, y: Math.SQRT1_2};

/**
 * **不反向缩放**：道岔菱形与轨道共用一个相机比例（`notes/165`）—— 全览时 16 px 偏移、8 px 菱形，
 * 之后跟着地图一起放大。
 */
const counterScale = computed(() => 1);
/** 规格像素 → 世界单位的那个**唯一常量**（取景校准一次）。 */
/** 相对锚点的偏移（**世界单位**）：方向来自屏幕对角、距离是规格（全览 16 px）。 */
const offsetWorld = computed(() => pixelOffset(DIAGONAL, pxToWorld(DECAL_KINDS.turnoutOffset, viewScale.value)));
/** 菱形与引线的定位用同一个偏移（都在世界坐标里）。 */
const offsetScreenPx = offsetWorld;

/** 贴片锚点：道岔的世界坐标 → 屏幕 + 固定像素偏移。 */
/* 道岔的锚点 = 它绑定的**节点**（`point.key` 就是节点键）—— 不掺实际坐标。 */
const placement = computed(() => decalPlacement(props.point.planeX, props.point.planeY, offsetWorld.value));

const rootTransform = computed(() => decalTransform(placement.value));

const props = defineProps<{
	point: Point;
	/** 当前相机：**只用来把屏幕像素规格折成世界单位**。 */
	camera: Camera;
	/** 当前相机：贴片位置由它算（组件自己不接收屏幕坐标，避免"位置"有两个来源）。 */
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
		:data-world="`${point.planeX},${point.planeY}`"
		:style="{transform: rootTransform}"
		@pointerenter="emit('hover', point.key)"
		@pointerleave="emit('hover', '')"
		@pointerdown.stop="onPointerDown"
	>
		<!--
			一条细连线把菱形系回它所属的节点：菱形偏移出去之后，世界图里节点很密，
			没有这条线用户认不出这个菱形挂在哪个节点上。
		-->
		<svg class="leader" :width="leaderPx" :height="leaderPx" :viewBox="`0 0 ${leaderPx} ${leaderPx}`" aria-hidden="true">
			<line x1="3" y1="3" :x2="leaderPx - 3" :y2="leaderPx - 3" stroke="#f59e0b" stroke-width="1" stroke-opacity="0.5"/>
		</svg>

		<!--
			菱形：四边等长的方块旋转 45°，边长 = 图标直径（8 px，与信号灯同一个值，见
			`domain/mapElements.ts#DECAL_KINDS`）。用菱形而不是圆点，是为了和节点圆点、灯点
			在同一张图上一眼分得开（道岔是"要人管的东西"，形状本身就应当不同）。
			里面的数字 = 当前开通的腿序号。
			外面那层 `.hit` 是点击靶，整体偏在节点右下方（偏移量按"不许与灯点相接"算出来，见样式说明）。
		-->
		<div class="hit" :style="{'--d': `${diamondPx}px`, transform: `scale(${counterScale})`}">
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
 * <h3>偏移 16px 是**算出来的下限**，不是手感（尺寸改 8px 后重算过）</h3>
 * <p>道岔节点上常常**同时立着一盏信号灯**（信号机就放在道岔旁），道岔层又在灯层之上，
 * 所以两个矩形必须**在几何上不可能相接**。两侧的数字（按 8 px 图标重算）：</p>
 * <ul>
 *   <li>灯：圆点 4px + 悬停环，实测命中半径 ≈ 8px（直径 16px 是**灯**那一侧的尺寸，没变）；</li>
 *   <li>道岔菱形：8px 方块转 45°，**外接框是 11.3px**（关键尺寸不是 8），半宽 5.7px。</li>
 * </ul>
 * <p>最坏情况是"道岔节点与立着灯的节点相距 1px"（实测就有这种，两个点中心几乎重合）：
 * 菱形至少要从自己中心退 5.7px，加上灯那一侧的 16px，再留一点余量 ⇒ 取 <b>16px</b>。
 * 于是最坏情况下两者之间仍有 {@code 16 − 5.7 − 8 ≈ 2.3px} 的空隙，矩形不相接
 * （34px 那版的余量是 6.5px，现在更紧，但**仍然为正** —— 要更大余量就把这个值一起调大）。</p>
 */
.hit {
	position: absolute;
	/*
	 * 偏移与尺寸**都从模型来**，不再写死。
	 *
	 * <p>写死这一处正是"缩放时脱层"的典型：菱形跟着倍率长，靶不动 —— 放大后菱形跑出靶外（点不到），
	 * 缩小后靶比菱形大一圈（挡住隔壁灯点）。现在偏移是 {@code 对角方向 × 规格(16px) × 倍率}，
	 * 尺寸是 {@code var(--d)}（与菱形同一边长），两者与菱形**同一个倍率**，
	 * 所以上面那段"最坏情况仍有 ≈2.3px 空隙"的推导在任意倍率下都成立（它本来就是比例的）。</p>
	 */
	left: v-bind('`${offsetScreenPx.x}px`');
	top: v-bind('`${offsetScreenPx.y}px`');
	width: var(--d);
	height: var(--d);
	display: flex;
	align-items: center;
	justify-content: center;
	pointer-events: none;
}

/*
 * 一条细连线：把菱形系回它所属的节点。
 *
 * <p>菱形偏移出去之后，纯靠位置已经认不出它属于哪个节点（世界图里节点很密），
 * 这条线是"它挂在这个节点上"的唯一凭据。</p>
 */
.leader {
	position: absolute;
	left: 3px;
	top: 3px;
	/* 引线长度跟着倍率走（规格 16 px @6×）；viewBox 不变，所以线本身的相对比例不变。 */
	width: v-bind('`${leaderPx}px`');
	height: v-bind('`${leaderPx}px`');
	overflow: visible;
	pointer-events: none;
}

/*
 * 菱形：8×8 的方块旋转 45°（= 图标直径，与信号灯同一个值），加一圈暗描边。
 * 默认开通位用琥珀色（"需要人管"），已锁闭用红色。
 */
.diamond {
	/* 边长由外层 `.hit` 按倍率写成 `--d`，这里只是继承（继承才能让靶与菱形同一个值）。 */
	width: var(--d);
	height: var(--d);
	transform: rotate(45deg);
	display: flex;
	align-items: center;
	justify-content: center;
	background: #f59e0b;
	box-shadow: 0 0 0 calc(var(--d) * 0.125) #000000, 0 0 calc(var(--d) * 0.75) rgba(245, 158, 11, 0.55);
	border-radius: calc(var(--d) * 0.125);
	pointer-events: auto;
	cursor: pointer;
}

/* 卡片自己吃事件（要能点腿按钮）：绝对定位，脱离 flex 布局，不影响靶的尺寸 */
.card {
	pointer-events: auto;
}

/*
 * 里面的数字要转回来，否则跟着菱形一起歪。
 *
 * <p>字号由边长派生（`--d` 的 75%）：菱形对角线只有边长的 1.41 倍，一行数字放得下但不宽裕 ——
 * 详情在悬停卡片里，这里只要"能看出开通位是几"。</p>
 */
.leg-number {
	transform: rotate(-45deg);
	font-family: var(--font-value);
	font-size: calc(var(--d) * 0.75);
	font-weight: 700;
	line-height: 1;
	color: #1a1204;
}

/* 未设定（走默认 0）：空心，提示"这一位不是人定的" */
.point.is-default .diamond {
	background: rgba(245, 158, 11, 0.28);
	box-shadow: 0 0 0 calc(var(--d) * 0.19) #000000, inset 0 0 0 calc(var(--d) * 0.19) #f59e0b, 0 0 calc(var(--d) * 1) rgba(245, 158, 11, 0.35);
}

.point.is-default .leg-number {
	color: #fbbf24;
}

.point.hovered .diamond,
.point.expanded .diamond {
	box-shadow: 0 0 0 calc(var(--d) * 0.25) #000000, 0 0 0 calc(var(--d) * 0.5) var(--accent), 0 0 calc(var(--d) * 1.75) var(--accent);
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
	box-shadow: 0 0 0 calc(var(--d) * 0.25) #000000, 0 0 0 calc(var(--d) * 0.5) #f59e0b, 0 0 calc(var(--d) * 2) rgba(245, 158, 11, 0.9);
}

/* 选中时那条系回节点的连线也加亮：它是"这个亮线归哪个节点"的凭据 */
.point.selected .leader line {
	stroke-opacity: 1;
	stroke-width: 2;
}

.point.locked .diamond {
	background: #ef4444;
	box-shadow: 0 0 0 calc(var(--d) * 0.19) #000000, 0 0 calc(var(--d) * 1) rgba(239, 68, 68, 0.6);
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





