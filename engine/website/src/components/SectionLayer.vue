<script setup lang="ts">
import {computed} from "vue";
import type {Camera} from "@/domain/camera";
import {hasDirection, type Section, type SectionSpan} from "@/domain/Section";
import {offsetSvgPath, sideOfDirection} from "@/domain/sectionBands";
import {railSpanPath, type StraightLookup} from "@/domain/railPath";
import type {Rail} from "@/domain/Rail";

/*
 * 区间图（用户 2026-09-15 定的规格）。
 *
 * <h3>画法（规格原文）</h3>
 * <p>"当切换至区间图时，路线图直接隐身。在原来的路线图位置画 6px 线，线的第 1-2px、4-5px 用来表示区间，
 * 轨道节点也隐身，圆点用来表示区间端点。"</p>
 *
 * <pre>
 *   ────────────────────────────  ← 6 px 白线（占轨道原来的位置）
 *   ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓  ← 第 1–2 px：一个方向的区间
 *   ────────────────────────────      （中间 2 px 留白 = 线心）
 *   ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓  ← 第 4–5 px：另一个方向的区间
 * </pre>
 *
 * <p>于是"两根并排的股道"看起来就是两条（都是 6 px，一点不糊），而每一条自己身上有两道 2 px 的状态色 ——
 * 这个空间尺度远小于 8 m 的股道间距，所以永远不会串到邻轨上去（第一版 5 px 宽带糊掉的就是这个原因）。</p>
 *
 * <h3>颜色（规格原文）</h3>
 * <p>"因为有区间端点，所以区间颜色只需要有红，黄，虚线黄颜色即可。"</p>
 * <ul>
 *   <li><b>红</b> = 占用（有人）</li>
 *   <li><b>黄</b> = 空闲（没人）</li>
 *   <li><b>虚线黄</b> = 其他（被道岔切断 / 走不到 / 没入口灯等异常情形）</li>
 * </ul>
 *
 * <h3>几何</h3>
 * <p>线本身用**网页画轨道线的同一套几何**（`domain/railPath.ts`），所以区间与轨道位置必然一致；
 * 状态条则是把那条线按法向偏移 1.5 px / 4.5 px 得到的。实现上走 `offsetSvgPath`：它把路径按法向逐点偏移，
 * 于是**一条 2 px 宽、偏移 1.5 px 的线**恰好覆盖第 1–2 px，偏移 4.5 px 的覆盖第 4–5 px。</p>
 */

/*
 * 线心位置 = **6 px**（用户规格），样式表里写死；状态条的偏移见 STRIPE_* 常量。
 */
/** 两条状态条的中心偏移（用户规格：第 1–2 px 与第 4–5 px ⇒ 中心在 ±1.5 / ±4.5）。 */
const STRIPE_OFFSET_PX = 4.5;
const STRIPE_NEAR_OFFSET_PX = 1.5;
/** 状态条宽度（用户规格：各 2 px）。 */
const STRIPE_WIDTH_PX = 2;

/**
 * 状态条的颜色 = **这条区间的入口信号灯显示的灯色**（用户 2026-09-15 定的规格）。
 *
 * <p>用户原话："虚线黄就是后面信号灯是双黄灯的意思呗，是信号灯颜色"。所以这一层**不是**自己编状态，
 * 而是把引擎算出来的 signal aspect 直接画出来 —— 区间层与信号灯层说的是同一件事。</p>
 *
 * <table>
 *   <tr><th>引擎 aspect</th><th>画法</th><th>含义</th></tr>
 *   <tr><td>{@code RED}</td><td>红实线</td><td>危险：前方区间被占（或没有进路）</td></tr>
 *   <tr><td>{@code SINGLE_YELLOW}</td><td>黄实线</td><td>单黄：下一段要停</td></tr>
 *   <tr><td>{@code DOUBLE_YELLOW}</td><td><b>黄虚线</b></td><td>双黄：前方两段之内有情况</td></tr>
 *   <tr><td>{@code GREEN}</td><td>绿实线</td><td>绿灯：畅通</td></tr>
 * </table>
 *
 * <p>色值直接沿用信号灯层那一套（`SignalMarker.stateColor`），所以同一盏灯在两张图上颜色一致；
 * 四种 aspect 四种画法，一一对应，没有合并。</p>
 */
const COLOR_RED = "#ef4444";          // RED
const COLOR_SINGLE_YELLOW = "#f59e0b"; // SINGLE_YELLOW
const COLOR_DOUBLE_YELLOW = "#eab308"; // DOUBLE_YELLOW（虚线）
const COLOR_GREEN = "#22c55e";         // GREEN

/** 区间 → 该画成什么（颜色 + 是否虚线），逐 aspect 一一对应。 */
function stripeOf(section: Section): {color: string; dashed: boolean; state: string} {
	switch (section.aspect) {
		case "RED":
			return {color: COLOR_RED, dashed: false, state: "red"};
		case "SINGLE_YELLOW":
			return {color: COLOR_SINGLE_YELLOW, dashed: false, state: "single-yellow"};
		case "DOUBLE_YELLOW":
			// 用户规格里的"虚线黄"就是它
			return {color: COLOR_DOUBLE_YELLOW, dashed: true, state: "double-yellow"};
		case "GREEN":
			return {color: COLOR_GREEN, dashed: false, state: "green"};
		default:
			// 引擎没给 aspect（例如灯没接进闭塞层）：灰，不猜
			return {color: "#6b7280", dashed: false, state: "unknown"};
	}
}

const props = defineProps<{
	sections: readonly Section[];
	/** 要画出 6 px 线心的那些轨（区间按弧窗切它们的形状）。 */
	rails: readonly Rail[];
	camera: Camera;
	/** 选中的区间 id：加宽并置顶。 */
	selectedSection?: string;
	/** 轨 hex → 轨实体（按弧窗切片时要用）。 */
	railByHex?: ReadonlyMap<string, Rail>;
	/** 节点 → 该节点上直线轨的方向（与路线图同一份，曲线端点切线要用）。 */
	straightLookup?: StraightLookup;
	/** 轨 hex → 轨道线颜色（保留：区间图默认不用它，但选中态之类仍可能需要）。 */
	railColorByHex?: ReadonlyMap<string, string>;
}>();

const NO_STRAIGHT: StraightLookup = () => null;
const straight = () => props.straightLookup ?? NO_STRAIGHT;

/** 轨 hex 的另一种端点写法（无向 id；区间与拓扑的写法不保证一致，见 notes）。 */
function altHex(hex: string): string {
	const parts = hex.split("-");
	return parts.length === 6 ? [...parts.slice(3), ...parts.slice(0, 3)].join("-") : hex;
}

/** 取轨：先按原写法、再按反向写法。 */
function railOf(hex: string): Rail | undefined {
	return props.railByHex?.get(hex) ?? props.railByHex?.get(altHex(hex));
}

/** 区间状态：红 / 黄 / 虚线黄（用户规格的三种）。 */

/**
 * 一段区间的线（6 px 线心 + 两条 2 px 状态条）。
 *
 * <p>线心与状态条都来自**同一条路径**（`railSpanPath` 按弧窗切出来的那段轨），
 * 状态条只是把它按法向偏移 —— 所以三者永远平行、永远贴在轨道位置上。</p>
 */
const bands = computed(() => {
	const result: {
		key: string;
		base: string;
		side: string;
		color: string;
		dashed: boolean;
		section: string;
		state: string;
		selected: boolean;
	}[] = [];
	for (const section of props.sections) {
		if (!hasDirection(section)) {
			continue;
		}
		// 颜色与实线/虚线**直接来自信号灯的 aspect**（用户："是信号灯颜色"）
		const {color, dashed, state} = stripeOf(section);
		// 方向决定状态条画在哪一侧（"第 1–2 px"还是"第 4–5 px"）—— 与路线图两侧的语义一致
		const side = sideOfDirection(section.direction.angle);
		const offset = (side > 0 ? 1 : -1) * STRIPE_OFFSET_PX;
		const nearOffset = (side > 0 ? 1 : -1) * STRIPE_NEAR_OFFSET_PX;
		const selected = props.selectedSection === section.id;
		section.spans.forEach((span: SectionSpan, index: number) => {
			const rail = railOf(span.hex);
			if (rail === undefined) {
				return;
			}
			const path = railSpanPath(rail, span.from, span.to, props.camera, straight());
			if (path === "") {
				return;
			}
			result.push({
				key: `${section.id}#${index}`,
				base: path,
				side: offsetSvgPath(path, offset),
				color,
				dashed,
				section: section.id,
				state,
				selected,
			});
			// 另一侧画一条**同样的**状态条（近心那条）：两根轨并排时两侧都看得出状态
			result.push({
				key: `${section.id}#${index}-near`,
				base: "",
				side: offsetSvgPath(path, nearOffset),
				color,
				dashed,
				section: section.id,
				state,
				selected,
			});
		});
	}
	return result;
});

/** 线心：每根轨一条 6 px 白线（区间图里它就是"原来的路线图位置"）。 */
const baseLines = computed(() => {
	const out: {key: string; d: string}[] = [];
	// 只用区间覆盖到的轨来画线心：区间图讲的是"区间覆盖到的地方"，没有区间的轨不画（用户规格：区间图）
	const covered = new Set<string>();
	for (const section of props.sections) {
		for (const span of section.spans) {
			covered.add(span.hex);
			covered.add(altHex(span.hex));
		}
	}
	for (const rail of props.rails) {
		if (!covered.has(rail.hex)) {
			continue;
		}
		const rail2 = railOf(rail.hex);
		if (rail2 === undefined) {
			continue;
		}
		// 整根轨：两端点弧长就是 0 与轨长（`railSpanPath` 内部按弧长比例切片）
		const path = railSpanPath(rail2, 0, railLengthOf(rail2), props.camera, straight());
		if (path !== "") {
			out.push({key: rail.hex, d: path});
		}
	}
	return out;
});

/** 轨的世界长度（端点距离，足够用来表示"整根轨"）。 */
function railLengthOf(rail: Rail): number {
	return Math.hypot(rail.planeX2 - rail.planeX1, rail.planeY2 - rail.planeY1);
}

/** 区间端点（圆点）：每个区间两端的平面坐标。 */
const endpointDots = computed(() => {
	const dots: {key: string; x: number; y: number; color: string; selected: boolean}[] = [];
	for (const section of props.sections) {
		if (!hasDirection(section) || section.spans.length === 0) {
			continue;
		}
		// 端点圆点也用**该区间入口灯的颜色**，于是"灯什么色、这一段就是什么色"在图上处处一致
		const {color} = stripeOf(section);
		const first = section.spans[0]!;
		const last = section.spans[section.spans.length - 1]!;
		for (const [suffix, span, atEnd] of [["a", first, false], ["b", last, true]] as const) {
			const rail = railOf(span.hex);
			if (rail === undefined) {
				continue;
			}
			const point = atEnd ? screenAt(rail, span.to) : screenAt(rail, span.from);
			if (point === null) {
				continue;
			}
			dots.push({key: `${section.id}#${suffix}`, x: point.x, y: point.y, color, selected: props.selectedSection === section.id});
		}
	}
	return dots;
});

/** 轨上某个弧长处的屏幕坐标（端点圆点用）。 */
function screenAt(rail: Rail, arcM: number): {x: number; y: number} | null {
	const path = railSpanPath(rail, arcM, arcM, props.camera, straight());
	const match = /(-?[\d.]+) (-?[\d.]+)/.exec(path);
	return match === null ? null : {x: Number(match[1]), y: Number(match[2])};
}
</script>

<template>
	<g class="section-layer">
		<!-- 线心：6 px 白线，占轨道原来的位置（"在原来的路线图位置画 6px 线"） -->
		<path v-for="line in baseLines" :key="`base-${line.key}`" class="base" :d="line.d"/>
		<!--
			状态条：每条 2 px，法向偏移 ±1.5 / ±4.5 ⇒ 落在线的第 1–2 px 与第 4–5 px。
			`<path>` 在 SVG 里不能真正偏移（transform 会跟着缩放），所以偏移已在 offsetSvgPath 里算进坐标。
		-->
		<path
			v-for="band in bands"
			:key="band.side"
			class="stripe"
			:d="band.side"
			:stroke="band.color"
			:stroke-width="band.selected ? STRIPE_WIDTH_PX + 1 : STRIPE_WIDTH_PX"
			:stroke-dasharray="band.dashed ? '4 3' : undefined"
			:data-section="band.section"
			:data-state="band.state"
		/>
		<!-- 区间端点（圆点）—— 轨道节点在区间图里隐身，用这些点代替 -->
		<circle
			v-for="dot in endpointDots"
			:key="dot.key"
			class="endpoint"
			:cx="dot.x"
			:cy="dot.y"
			:r="dot.selected ? 4 : 3"
			:fill="dot.color"
		/>
	</g>
</template>

<style scoped>
.section-layer .base {
	fill: none;
	stroke: #ffffff;
	stroke-width: 6px;
	stroke-linecap: round;
	stroke-linejoin: round;
	pointer-events: none;
}

.section-layer .stripe {
	fill: none;
	stroke-linecap: butt;
	stroke-linejoin: round;
	pointer-events: none;
}

/* 端点圆点：加一圈暗边，压在密集处也数得清 */
.section-layer .endpoint {
	stroke: #10161c;
	stroke-width: 1;
	pointer-events: none;
}
</style>
