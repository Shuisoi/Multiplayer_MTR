<script setup lang="ts">
import {computed} from "vue";
import {hasDirection, type Section, type SectionSpan} from "@/domain/Section";
import {offsetSvgPath, sideOfDirection} from "@/domain/sectionBands";
import {railSpanWorldPath, type StraightLookup} from "@/domain/railPath";
import type {Camera} from "@/domain/camera";
import type {Rail} from "@/domain/Rail";
import {DECAL_KINDS, specPxToWorld} from "@/domain/mapElements";
import {useUnitsPerPx} from "@/views/mapContext";

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
 * 线心的宽度、状态条的宽度与偏移、端点圆点半径都来自**规格表**
 * （`domain/mapElements.ts#DECAL_KINDS`），并且都**乘当前倍率** —— 口径是"世界不动、动的是摄像机"，
 * 所以规格值（6× 下 6 px 线心、2 px 状态条）要跟着倍率一起变。
 */
/**
 * 本层的尺寸（**世界单位**）：这一层画在设了 `viewBox` 的 SVG 里，相机由 viewBox 承担。
 *
 * <p>规格像素 → 世界单位的换算只有一处、也是唯一真源 {@link specPxToWorld}
 * （{@code 规格 × 6 / 倍率 / 视口比例}）。于是这里**不再有"规格 × 倍率"那套补丁**。</p>
 */
const unitsPerPx = useUnitsPerPx();

const stripeWidthPx = computed(() => specPxToWorld(DECAL_KINDS.stripeWidth, unitsPerPx.value));
/**
 * 状态条相对线心**中心**的法向偏移（条中心落在第 1–2 px 的**中心** = 1.5 px）。
 *
 * <p>用 `stripeNear`（= 1.5 px）而不是 `stripeFar`（= 4.5 px）：每个方向的区间只画**一条**，
 * 它写在线心的哪一侧由方向决定（见 `bands`），所以两侧的条各自落在"第 1–2 px"与"第 4–5 px" ——
 * 这正是"三根线"的读法（`stripeNear`/`stripeFar` 与线心中心对称，所以两侧用哪个偏移是一样的）。</p>
 */
const stripeOffsetPx = computed(() => specPxToWorld(DECAL_KINDS.stripeNear, unitsPerPx.value));
const baseWidthPx = computed(() => specPxToWorld(DECAL_KINDS.sectionBaseWidth, unitsPerPx.value));
/**
 * 端点圆点的半径（**画布坐标单位**，与 `cx/cy` 同一套）：直接就是规格值。
 *
 * <p>`r` 与 `cx/cy` 必须是同一套单位 —— 这一层的坐标是"世界 → 画布坐标"，
 * 画布坐标 × 倍率 = 屏幕像素（6× 时正好相等），所以半径 0.5 就是**屏幕上直径 1 px @6×**。
 * 曾经把它再按 `× 6 / 倍率` 换一次，量出来是 6 px（差了 6.7 倍，见 `check-web-section-three-lines`）。</p>
 */
const endpointDotRadiusUnits = computed(() => specPxToWorld(DECAL_KINDS.endpointDot, unitsPerPx.value));
/** 选中时放大的量（画布单位）：给一点视觉反馈，但不改变常态尺寸。 */
const endpointDotSelectedExtra = computed(() => DECAL_KINDS.endpointDot);

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

	/** 选中的区间 id：加宽并置顶。 */
	selectedSection?: string;
	/** 轨 hex → 轨实体（按弧窗切片时要用）。 */
	railByHex?: ReadonlyMap<string, Rail>;
	/** 节点 → 该节点上直线轨的方向（与路线图同一份，曲线端点切线要用）。 */
	straightLookup?: StraightLookup;
	/** 当前相机：**只用来做尺寸换算**（位置一点不用它 —— 相机由外层 viewBox 承担）。 */
	camera: Camera;
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
 * 一段区间的**线**（状态条）：每个方向的区间只画**一条** 2 px 条。
 *
 * <h3>为什么只画一条（用户 2026-09-15 纠正）</h3>
 * <p>规格是"**绘图只有三根线**"：6 px 线心 + 第 1–2 px + 第 4–5 px。
 * 上一版给每个方向的区间画了**两条**（远心 + 近心，理由是"两根轨并排时两侧都看得出状态"），
 * 于是双向轨道上就成了 **5 根**（线心 + 2 个方向 × 2 条）—— 用户一眼就看了出来。</p>
 *
 * <p>正确的读法是：**线心的两侧各是一个方向**。方向为"侧向正"的区间写在第 1–2 px，
 * 方向为"侧向负"的写在第 4–5 px（`sideOfDirection` 按法向的符号分侧）；
 * 线心本身只画一次（整根轨，见 `baseLines`）。</p>
 *
 * <p>线心与状态条都来自**同一条路径**（`railSpanPath` 按弧窗切出来的那段轨），
 * 状态条只是把它按法向偏移 —— 所以三者永远平行、永远贴在轨道位置上。</p>
 */
const bands = computed(() => {
	const result: {
		key: string;
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
		// 方向决定状态条画在**哪一侧**（"第 1–2 px"还是"第 4–5 px"）
		const side = sideOfDirection(section.direction.angle);
		const offset = (side > 0 ? 1 : -1) * stripeOffsetPx.value;
		const selected = props.selectedSection === section.id;
		section.spans.forEach((span: SectionSpan, index: number) => {
			const rail = railOf(span.hex);
			if (rail === undefined) {
				return;
			}
			const path = railSpanWorldPath(rail, span.from, span.to, straight());
			if (path === "") {
				return;
			}
			result.push({
				key: `${section.id}#${index}`,
				side: offsetSvgPath(path, offset),
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
		const path = railSpanWorldPath(rail2, 0, railLengthOf(rail2), straight());
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

/**
 * 区间端点（圆点）。
 *
 * <h3>两条用户规格（2026-09-15）</h3>
 * <ol>
 *   <li><b>端点无需颜色</b> —— 双向的两个区间颜色本来就不同，端点再上色反而把"哪段属于谁"搅乱
 *       （一个节点上两条区间各有一个端点，两色并排会看着像第三个东西）。所以端点统一用**白**色，
 *       它只表示"区间到这里为止"。</li>
 *   <li><b>点要落在区间的状态条上，不是线心上</b> —— 端点是"这条区间的端点"，
 *       而区间画在法向偏移 1.5 px 的那条细线上（见 `bands`）。所以端点的坐标直接从
 *       **那条已经偏移好的条**上取（`d` 的首/末点），而不是从线心取再自己加偏移 ——
 *       自己加偏移会在弯道处与条的真实端点错开（条的偏移是按折线逐段算的）。</li>
 * </ol>
 */
const endpointDots = computed(() => {
	const dots: {key: string; x: number; y: number; selected: boolean}[] = [];
	for (const section of props.sections) {
		if (!hasDirection(section) || section.spans.length === 0) {
			continue;
		}
		// 这一区间的条：与 `bands` 同一套算法、同一个 key 规则（每个 span 恰好一条）
		const side = sideOfDirection(section.direction.angle);
		const offset = (side > 0 ? 1 : -1) * stripeOffsetPx.value;
		for (const [suffix, index, atEnd] of [["a", 0, false], ["b", section.spans.length - 1, true]] as const) {
			const span = section.spans[index];
			if (span === undefined) {
				continue;
			}
			const rail = railOf(span.hex);
			if (rail === undefined) {
				continue;
			}
			const path = railSpanWorldPath(rail, span.from, span.to, straight());
			if (path === "") {
				continue;
			}
			const point = endPointOf(offsetSvgPath(path, offset), atEnd);
			if (point === null) {
				continue;
			}
			dots.push({key: `${section.id}#${suffix}`, x: point.x, y: point.y, selected: props.selectedSection === section.id});
		}
	}
	return dots;
});

/** 一条路径的首点或末点（屏幕坐标）：`d` 里的第一个/最后一个 `x y`。 */
function endPointOf(path: string, atEnd: boolean): {x: number; y: number} | null {
	const numbers = path.match(/-?[\d.]+/g);
	if (numbers === null || numbers.length < 4) {
		return null;
	}
	const index = atEnd ? numbers.length - 2 : 0;
	return {x: Number(numbers[index]), y: Number(numbers[index + 1])};
}
</script>

<template>
	<!--
		规格值挂成 data 属性：检查脚本（`sandbox/check-web-section-three-lines.ps1`）直接读它们，
		而不是把"规格是多少"再抄一遍 —— 抄一遍就会出现"规格改了、检查还在按旧值判"。
		`data-endpoint-dot-radius` 是**半径**（画布坐标单位）：屏幕上直径 = 2r × 倍率。
	-->
	<g
		class="section-layer"
		:data-core-width="DECAL_KINDS.sectionBaseWidth"
		:data-stripe-width="DECAL_KINDS.stripeWidth"
		:data-stripe-near="DECAL_KINDS.stripeNear"
		:data-stripe-far="DECAL_KINDS.stripeFar"
		:data-endpoint-dot-radius="DECAL_KINDS.endpointDot"
	>
		<!-- 线心：6 px 白线，占轨道原来的位置（"在原来的路线图位置画 6px 线"） -->
		<path v-for="line in baseLines" :key="`base-${line.key}`" class="base" :d="line.d"/>
		<!--
			状态条：每条 2 px，法向偏移 ±2（近心那条）⇒ 落在 6 px 线心的第 1–2 px 或第 4–5 px。
			**每个方向的区间只画一条**：线心的两侧各是一个方向（用户规格："绘图只有三根线"）。
			`<path>` 在 SVG 里不能真正偏移（transform 会跟着缩放），所以偏移已在 offsetSvgPath 里算进坐标。
		-->
		<path
			v-for="band in bands"
			:key="band.key"
			class="stripe"
			:d="band.side"
			:stroke="band.color"
			:stroke-width="band.selected ? stripeWidthPx + 1 : stripeWidthPx"
			:stroke-dasharray="band.dashed ? '4 3' : undefined"
			:data-section="band.section"
			:data-state="band.state"
		/>
		<!--
			区间端点（圆点）—— 轨道节点在区间图里隐身，用这些点代替。
			**不带颜色**（用户 2026-09-15："端点无需颜色，因为双向的区间不同"）：
			端点只表示"区间到这里为止"，它落在哪条状态条上（哪一侧）已经说明属于哪个方向。
			**直径 1 px**（同一轮："点的大小也改为 1px"）；`r` 是画布单位，所以由 `endpointDotRadiusUnits` 换算。
		-->
		<circle
			v-for="dot in endpointDots"
			:key="dot.key"
			class="endpoint"
			:cx="dot.x"
			:cy="dot.y"
			:r="dot.selected ? endpointDotRadiusUnits + endpointDotSelectedExtra : endpointDotRadiusUnits"
			:style="{'--dot-r': `${endpointDotRadiusUnits}`}"
		/>
	</g>
</template>

<style scoped>
.section-layer .base {
	fill: none;
	stroke: #ffffff;
	stroke-width: v-bind('`${baseWidthPx}px`');
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

/*
 * 端点圆点：**固定白色**（不留颜色，见 `endpointDots` 的说明）、**直径 1 px @6×**。
 *
 * 描边按"半径的 0.4 倍"派生而不是写死像素：`r` 已经是画布单位，写死像素会让描边
 * 在缩放时相对圆点变粗（1 px 的点加 1 px 描边就只剩描边了）。
 */
.section-layer .endpoint {
	fill: var(--fg);
	stroke: #10161c;
	stroke-width: calc(var(--dot-r) * 0.4);
	pointer-events: none;
}
</style>



