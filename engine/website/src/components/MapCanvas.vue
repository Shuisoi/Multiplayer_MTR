<script setup lang="ts">
import {computed, nextTick, onBeforeUnmount, onMounted, provide, ref, useTemplateRef, watch} from "vue";import {useCameraView} from "@/composables/useCameraView";
import {boundsOf} from "@/domain/camera";
import type {Camera} from "@/domain/camera";
import type {Node} from "@/domain/Node";
import type {Rail} from "@/domain/Rail";
import type {Signal} from "@/domain/Signal";
import type {Point} from "@/domain/Point";
import type {Section} from "@/domain/Section";
import type {Rail as RailEntity} from "@/domain/Rail";
import type {StraightLookup} from "@/domain/railPath";
import {CAMERA, ZOOM_RATIO} from "@/views/mapContext";
import RailLayer from "./RailLayer.vue";
import SectionLayer from "./SectionLayer.vue";
import NodeLayer from "./NodeLayer.vue";
import SignalLayer from "./SignalLayer.vue";
import PointLayer from "./PointLayer.vue";

/*
 * 地图画布：视口 + 摄像机 + 内容层。
 *
 * 从下到上：
 *   1. 点阵背景（纯装饰，不随摄像机变，给人"有地方可以拖"的感觉）；
 *   2. 轨道层 `RailLayer`（SVG，屏幕坐标；同一轴画直线、斜向画曲线）；
 *   3. 节点层 `NodeLayer`（HTML；节点圆点、悬停信息卡、左键操作菜单）；
 *   4. 信号灯层 `SignalLayer`（HTML；状态颜色 + `^` 方向箭头）。
 *
 * <p><b>信号灯必须在节点层之上</b>：灯位常常正好落在节点上，而节点的交互靶有 24px（为了好点），
 * 压在灯上就会把指针整个吃掉 —— 实测"悬停灯位不出信息卡"就是这个原因。
 * 反向的代价（灯点压住节点圆点）可以接受：灯点只有 7px，节点圆点 7~12px 且悬停时会有强调色光圈提示。</p>
 *
 * 为什么节点与灯不画在 SVG 里：见 `domain/camera.ts` 顶部。世界坐标只由 `worldToScreen()` 换算一次，
 * 各层共用同一份摄像机，不存在 `viewBox` + `preserveAspectRatio` + `foreignObject` 三方对账。
 */

const props = defineProps<{
	/** 要显示的节点（世界坐标在 `Node.planeX / planeZ`）。 */
	nodes: readonly Node[];
	/** 要显示的轨。 */
	rails: readonly Rail[];
	/** 要显示的信号灯。 */
	signals: readonly Signal[];
	/**
	 * 要显示的道岔。
	 *
	 * <p>道岔是**唯一需要人管**的东西（灯的颜色由闭塞层自动给出，道岔的开通位要有人定），
	 * 所以这一层默认就画得显眼、并且可点开换向，而不是等用户去找。</p>
	 */
	points: readonly Point[];
	/**
	 * 轨 hex → 两端坐标：道岔卡片要写"这一位接的是哪条轨"。
	 *
	 * <p>用户是按**坐标**认轨的（"(-67,-103) 到 (-67,-167) 是正线"），不是按 hex。
	 * 只给 hex 前 12 位等于没说清它是哪一根。</p>
	 */
	railEnds?: ReadonlyMap<string, {x1: number; z1: number; x2: number; z2: number}>;
	/**
	 * 区间层（方案 B：沿轨法向偏移的**方向带**）。
	 *
	 * <p>缺省是空数组 = 不画这一层（旧行为不变）。区间是"某方向的一段路"，一个区间跨多根轨，
	 * 所以由 {@link SectionLayer} 自己按 span 的采样点投影，**不**复用轨道层的路径：
	 * 轨道层画的是整根轨，区间画的是轨上的一段弧窗，两者取的点本来就不同。</p>
	 */
	sections?: readonly Section[];
	/**
	 * 轨 hex → 轨道线颜色（`domain/railColors.ts`）：区间带的颜色**从轨道线派生**。
	 *
	 * <p>用户 2026-09-15："区间颜色从目前 web 生成的线派生，别独立生成了"。</p>
	 */
	railColorByHex?: ReadonlyMap<string, string>;
	/** 轨 hex → 轨实体：区间带用**网页画轨道线的同一套几何**切片（用户要求）。 */
	railByHex?: ReadonlyMap<string, RailEntity>;
	/** 节点 → 该节点上直线轨的方向（与轨道层同一份，供曲线端点切线使用）。 */
	straightLookup?: StraightLookup;
	/** 选中的区间 id（信息卡联动）。 */
	selectedSection?: string;
	/**
	 * 画不画**路线图**（4 px 纯白轨线 + 轨道节点）。
	 *
	 * <p>2026-09-15 用户定的规格：路线图与区间图是**两张图**，切到区间图时路线图**直接隐身** ——
	 * 不叠、不半透明，就是不在场。区间图在**同一位置**画 6 px 线，占轨道原来的位置。</p>
	 */
	showRoute?: boolean;
}>();

const host = useTemplateRef<HTMLElement>("host");
const camera = ref<Camera>({originX: 0, originY: 0, scale: 1});

/**
 * 内容包围盒：**节点与轨的全部采样点一起**算。
 *
 * <p>轨的采样点必须算进去：U 型轨的弯折部分会伸出两端点构成的包围盒，只用端点取景的话
 * 弯出去的那一段会被切在视口外。</p>
 *
 * <p>这里**不**做留白：留白是屏幕观感（"内容不要贴边"），所以由 `fitView` 按屏幕像素加，
 * 而不是在这里按世界单位或内容比例加——那两种口径换算成像素都要再乘当前比例，
 * 而比例取决于世界有多大，实测两次都导致边缘内容越界（见 `camera.fitView`）。</p>
 */
const content = computed(() => boundsOf([
	...props.nodes.map(node => ({x: node.planeX, y: node.planeZ})),
	...props.rails.flatMap(rail => [
		{x: rail.planeX1, y: rail.planeY1},
		{x: rail.planeX2, y: rail.planeY2},
		...rail.path.map(point => ({x: point.x, y: point.z})),
	]),
]));

const view = useCameraView({host, camera, content});
provide(CAMERA, camera);
/** 缩放倍率注入给各元素：它们按它把"规格尺寸"（例如 6× 下 8 px）换算成当前像素。 */
provide(ZOOM_RATIO, view.zoomRatio);

/** 悬停中的节点 key（信息卡）。 */
const hoveredKey = ref("");
/** 悬停中的信号灯 key（信息卡）。与节点的分开：两者的 key 空间不同（灯是方块键，节点是节点键）。 */
const hoveredSignalKey = ref("");
/** 悬停中的道岔 key（信息卡）。 */
const hoveredPointKey = ref("");
/** 打开了操作菜单的节点 key。 */
const menuKey = ref("");
/** 选中的节点 key（菜单动作后保持高亮）。 */
const selectedKey = ref("");
/**
 * 正在改绑定的那盏灯的 **key**（点选绑定）。
 *
 * <p>存 key 而不是存 Signal 对象：重取数据之后 `signals` 里是**新对象**，还攥着旧对象的话，
 * 高亮用的 `boundRails` 永远是绑定前那一份 —— 于是"绑定成功了，页面却还画着旧状态"
 * （实测：引擎已经守 2 条，页面上仍只画 1 条实线，HUD 也还写着 1）。存 key 再实时查，就不会有这份陈旧。</p>
 */
const selectedSignalKey = ref("");
const selectedSignal = computed(() => props.signals.find(item => item.key === selectedSignalKey.value) ?? null);

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
	/** 实际画出来的直线/曲线条数（由轨道层统计，HUD 直接显示，不再自己按规则重算）。 */
	(e: "shapes", summary: {straight: number; curve: number}): void;
	/**
	 * 点中了某条轨（只有在选中某盏灯、那条轨又是它的候选时才会发生）。
	 *
	 * <p>这里只上报"点了哪条"，不决定"点了算绑还是算解绑"——那是绑定的语义，属于视图
	 * （它知道这盏灯现在守哪几根，也知道要怎么改）。</p>
	 */
	(e: "pickRail", payload: {signal: Signal; railHex: string; bound: boolean}): void;
	/** 选中/取消选中一盏灯（视图据此在 HUD 上给提示）。 */
	(e: "selectSignal", signal: Signal | null): void;
	/**
	 * 灯卡片上的两个动作：复制坐标 / 把 `signal why x y z` 送进指令栏。
	 *
	 * <p>地图这一层不做这两件事：复制要说实话（可能被浏览器拒绝）、送指令要动指令栏，
	 * 两者都属于**视图**（{@code TopologyView}）—— 这里只把"用户点了哪盏灯的哪个动作"报上去。</p>
	 */
	(e: "signalCopy", signal: Signal): void;
	(e: "signalWhy", signal: Signal): void;
	/**
	 * 要把某个道岔扳到第 {@code leg} 条腿。
	 *
	 * <p>这里只上报"扳哪个道岔、扳到哪一位"，不自己改状态：道岔的开通位由引擎持有并持久化
	 * （`mmtrSetPoint` 会写 `mmtr-points.json`），界面改完必须重取一次数据才显示得对。</p>
	 */
	(e: "setPointLeg", payload: {point: Point; leg: number}): void;
	/** 选中/取消选中一个道岔（视图据此在 HUD 上给提示）。 */
	(e: "selectPoint", point: Point | null): void;
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

/** 点空白处：关菜单、取消选中（也退出改绑定）。 */
function onBackgroundDown(event: PointerEvent) {
	if (event.target === host.value) {
		menuKey.value = "";
		selectedKey.value = "";
		expandedPointKey.value = "";
		clearSelectedSignal();
	}
}

/** 打开着腿按钮面板的道岔 key。 */
const expandedPointKey = ref("");
/**
 * 选中的道岔 key。
 *
 * <p>与 `expandedPointKey` 分开：展开是"面板开着"，选中是"我正看着这个道岔" ——
 * 后者会让它**当前开通那条腿的轨**在地图上点亮（见下面的 `connectedRailHex`）。
 * 两者通常同时发生（点开就选中），但取消选中时不该把面板一起关掉。</p>
 */
const selectedPointKey = ref("");

/**
 * 选中道岔**当前开通**那条腿的轨（hex），交给轨道层点亮。
 *
 * <p>派生而不是存一份：重取数据后会拿到新的 `Point` 对象，存对象就会像早先那盏灯一样
 * "绑定成功了页面还显示旧的"。存 key、每次从当前数据里查，就不会有这份陈旧。</p>
 */
const connectedRailHex = computed(() => {
	const point = props.points.find(item => item.key === selectedPointKey.value) ?? null;
	return point?.activeLegObject?.railHex ?? "";
});

/** 点道岔：展开/收起腿按钮，并把它设为选中（取消时不清选中，避免地图上亮线一闪一闪）。 */
function onTogglePoint(point: Point) {
	const wasExpanded = expandedPointKey.value === point.key;
	expandedPointKey.value = wasExpanded ? "" : point.key;
	selectedPointKey.value = wasExpanded ? "" : point.key;
	selectedKey.value = "";
	menuKey.value = "";
	clearSelectedSignal();
	emit("selectPoint", wasExpanded ? null : point);
}

/** 点某条腿：上报给视图去下指令（这里不改任何状态，改状态要等引擎确认后重取数据）。 */
function onPickPointLeg(payload: {point: Point; leg: number}) {
	emit("setPointLeg", payload);
}

/**
 * 点了一盏灯：进入/退出"改绑定"。
 *
 * <p>再点同一盏 = 退出（和节点菜单同一个手感：同一个东西点两次就是关掉）。
 * 进入时清掉节点选中，免得两套高亮同时亮着、看不出现在在改什么。</p>
 */
function onPickSignal(signal: Signal) {
	if (selectedSignalKey.value === signal.key) {
		clearSelectedSignal();
		return;
	}
	selectedSignalKey.value = signal.key;
	selectedKey.value = "";
	menuKey.value = "";
	emit("selectSignal", signal);
}

function clearSelectedSignal() {
	selectedSignalKey.value = "";
	emit("selectSignal", null);
}

function onPickRail(payload: {hex: string; bound: boolean}) {
	const signal = selectedSignal.value;
	if (signal !== null) {
		emit("pickRail", {signal, railHex: payload.hex, bound: payload.bound});
	}
}

/** Esc = 退出改绑定（与"点空白处"同效）。 */
function onKeydown(event: KeyboardEvent) {
	if (event.key === "Escape") {
		clearSelectedSignal();
	}
}

onMounted(() => window.addEventListener("keydown", onKeydown));
onBeforeUnmount(() => window.removeEventListener("keydown", onKeydown));

defineExpose({
	/** 视图命令，供 HUD 与节点菜单用。 */
	fit: view.fit,
	centerOnWorld: view.centerOnWorld,
	zoom: view.zoom,
	/**
	 * 聚焦到信号灯那一块区域。
	 *
	 * <p>为什么需要：灯只占世界的一小块（实测 46×190 格 vs 世界 407×1623 格），
	 * 整图取景时它们挤成十几像素、箭头互相盖住，看不清状态与方向。区域在这里算（信号灯层的数据在这里），
	 * 取景交给摄像机。</p>
	 */
	focusSignals(paddingPx = 40) {
		const points = props.signals.map(signal => ({x: signal.planeX, y: signal.planeY}));
		if (points.length === 0) {
			return;
		}
		view.fitRegion(boundsOf(points), paddingPx);
	},
	/** 聚焦到道岔那一块区域（理由同 `focusSignals`：道岔是操作入口，得先看得见才点得到）。 */
	focusPoints(paddingPx = 60) {
		const points = props.points.map(point => ({x: point.planeX, y: point.planeY}));
		if (points.length === 0) {
			return;
		}
		view.fitRegion(boundsOf(points), paddingPx);
	},
});

/*
 * **只有几何真的变了才重新取景**（节点集合 / 轨集合）。
 *
 * <p>用 watch 而不是指望 `setContent()`：数据是从接口拿的，会晚于挂载到达。但**不能**"数据一变就取景" ——
 * 面板上的操作（扳道岔、看灯、扫描登记）都会走 `load()` 重取一遍数据，几何其实一模一样，
 * 于是每扳一次道岔地图就回中一次（用户报的现象）。</p>
 *
 * <p>所以这里比一份**几何签名**（条数 + 排好序的首/末项）：签名没变 ⇒ 这是"状态刷新"，
 * 视图一动不动（用户的平移缩放必须留着）；签名变了 ⇒ 世界改画了，重新取景并清掉"用户动过视图"的标记
 * （新几何可能落在视野外，看起来像"没数据"）。</p>
 *
 * <p>空列表要跳过：那时候包围盒是退化的 1×1，取景会把比例算到极大（实测 ~15 px/单位，
 * 之后真实数据的倍率读数就成了 0.02×）。</p>
 */
let framedGeometry = "";
watch([() => props.nodes, () => props.rails], async () => {
	if (props.nodes.length === 0 && props.rails.length === 0) {
		return;
	}
	const nodeKeys = props.nodes.map(node => node.key).sort();
	const railHexes = props.rails.map(rail => rail.hex).sort();
	const geometry = `${nodeKeys.length}|${nodeKeys[0] ?? ""}|${nodeKeys[nodeKeys.length - 1] ?? ""}|${railHexes.length}|${railHexes[0] ?? ""}|${railHexes[railHexes.length - 1] ?? ""}`;
	if (geometry === framedGeometry) {
		return;
	}
	framedGeometry = geometry;
	await nextTick();
	view.resetTouched();
	view.fit();
}, {immediate: true});

/*
 * 诊断：`?cameraDebug=1` 时把"拟合用的内容框"和"算出来的摄像机"一起挂出来。
 * 曾经出现"画面看着正常但边缘越界 20px"，只查摄像机看不出问题——内容框也要能看到
 * （结果是拟合发生在轨数据到达之前，用的内容框偏小，而 watch 当时只盯着节点）。
 */
if (typeof window !== "undefined" && window.location.search.includes("cameraDebug")) {
	(window as unknown as {__mmtrContent: unknown}).__mmtrContent = () => ({
		content: content.value,
		camera: camera.value,
		nodes: props.nodes.length,
		rails: props.rails.length,
		pathPoints: props.rails.reduce((sum, rail) => sum + rail.path.length, 0),
		viewport: {width: view.width.value, height: view.height.value},
	});
}
</script>

<template>
	<div
		ref="host"
		class="map"
		:class="{dragging: view.dragging.value, picking: selectedSignal !== null}"
		:data-zoom-ratio="view.zoomRatio.value.toFixed(4)"
		@pointerdown="view.onPointerDown"
		@pointerdown.capture="onBackgroundDown"
		@pointermove="view.onPointerMove"
		@pointerup="view.onPointerUp"
		@pointercancel="view.onPointerUp"
		@wheel="view.onWheel"
		@contextmenu.prevent
	>
		<div class="grid" aria-hidden="true"/>

		<!--
			轨道层：SVG，**故意不设 viewBox**。
			SVG 的用户单位默认就是 CSS 像素，所以这里可以直接写屏幕坐标，1 单位 = 1px。
			一旦给了 viewBox 就引入又一次缩放映射（以及 preserveAspectRatio 的第二套对账），
			这正是旧版三次翻车的来源，所以这里连机会都不留。
			`pickable` 只在"选中了某盏灯、正在改绑定"时为真：那时候选轨要能点，
			所以这一层临时接管指针事件（`.hit` 只让描边本身可命中，空白处仍然穿透给画布拖动）。
		-->
		<svg class="rails" :class="{pickable: selectedSignal !== null}">
			<!-- 路线图：切到区间图时整层不渲染（用户规格："路线图直接隐身"） -->
			<RailLayer
				v-if="showRoute !== false"
				:rails="rails"
				:camera="camera"
				:hover-key="hoveredKey"
				:select-key="selectedKey"
				:bound-rails="selectedSignal?.boundRails"
				:candidate-rails="selectedSignal?.candidateRails"
				:connected-rail="connectedRailHex"
				@shapes="emit('shapes', $event)"
				@pick-rail="onPickRail"
			/>
			<!--
				区间图：在**轨道原来的位置**画 6 px 线，两侧 2 px 表示区间。
				它自带那条 6 px 白线（不用轨道层），所以两张图真的互相独立。
			-->
			<SectionLayer
				:sections="sections ?? []"
				:rails="rails"
				:camera="camera"
				:selected-section="selectedSection ?? ''"
				:rail-color-by-hex="railColorByHex"
				:rail-by-hex="railByHex"
				:straight-lookup="straightLookup"
			/>
		</svg>

		<!-- 节点层：普通 HTML。这一层整体不吃事件，只有节点自己吃。 -->
		<div v-if="showRoute !== false" class="nodes">
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

		<!--
			道岔层：**在信号灯层之下**。
			道岔菱形按 34px 偏移挂在节点右下角（见 PointMarker 的说明），这个距离本身就够到邻节点，
			所以相邻节点的菱形难免会压到别人家的灯点上。让灯层在上面 = "灯永远点得到"，
			而道岔在自己没被压住的地方照旧可点（实测点灯被压住的那一处，正是这个原因）。
			顺序上先渲染道岔、再渲染灯，就得到这个优先级。
		-->
		<div class="points">
			<PointLayer
				:points="points"
				:camera="camera"
				:rail-ends="railEnds"
				:hovered-key="hoveredPointKey"
				:expanded-key="expandedPointKey"
				:selected-key="selectedPointKey"
				:picking="selectedSignal !== null"
				@hover="hoveredPointKey = $event"
				@toggle="onTogglePoint"
				@pick-leg="onPickPointLeg"
			/>
		</div>

		<!-- 信号灯层：在道岔层**之后**渲染，所以压在道岔上面（见上）。 -->
		<div class="signals">
			<SignalLayer
				:signals="signals"
				:camera="camera"
				:hovered-key="hoveredSignalKey"
				:selected-key="selectedSignal?.key ?? ''"
				@hover="hoveredSignalKey = $event"
				@pick="onPickSignal"
				@copy="emit('signalCopy', $event)"
				@why="emit('signalWhy', $event)"
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

.rails {
	position: absolute;
	inset: 0;
	width: 100%;
	height: 100%;
	/* 轨道层默认不参与命中测试：节点的交互不该被线抢走，空白处的拖动也要能穿透到画布。 */
	pointer-events: none;
}

/*
 * 改绑定期间，轨道层要让**候选轨**能点。这里只打开 SVG 根节点的事件，
 * 真正可命中的是 `.hit`（透明宽描边，`pointer-events: stroke`）—— 空白处的 pointer-events
 * 仍是 none，所以拖动与"点空白取消"照旧穿透到画布。
 */
.rails.pickable {
	pointer-events: auto;
}

/*
 * 节点层：整体 `pointer-events: none`，只让节点自己接收事件。
 * 这样"点空白处拖动/关菜单"不会被这一层挡住——上一版把交互靶塞进 foreignObject 时，
 * 整层都是命中区，空白处点不下去。
 *
 * 改绑定期间（`.pickable`）例外：那时候要点的是**轨**，而节点圆点/交互靶正好压在轨上
 * （信号机就立在轨道旁、道岔节点又恰是轨的交点），于是"点轨"会点到节点上去（实测：
 * 点已绑定的实线中点，命中的是节点，事件根本没到轨道层）。所以这时候整层让开。
 */
.nodes {
	position: absolute;
	inset: 0;
	pointer-events: none;
}

/* 信号灯层：同上，只有灯自己接收事件（悬停出信息卡）。 */
.signals {
	position: absolute;
	inset: 0;
	pointer-events: none;
}

/* 道岔层：同上。它在最上面，所以道岔的点击优先级最高。 */
.points {
	position: absolute;
	inset: 0;
	pointer-events: none;
}

/*
 * 这些层里的**子元素**默认是 `pointer-events: auto`（灯、节点、道岔各自开），
 * 所以让整层退出命中必须点名子元素，否则它们照旧吃事件。
 *
 * <p>道岔层也在这里让开：改绑定期间用户要点的是**灯和轨**，而道岔菱形又挂在节点上、
 * 世界图里 44 个道岔总有几个正好压在灯点上（实测压住了改绑定的第一次点灯）。
 * 让道岔在这一步暂时退场，比让用户"多绕 34px 去点灯"合理。</p>
 */
.map.picking .nodes > *,
.map.picking .signals > *,
.map.picking .points > * {
	pointer-events: none;
}
</style>
