<script setup lang="ts">
/*
 * 区间地图图层：**总区间的"带"** —— 地图上**每个位置只画一条**。
 *
 * <h2>为什么要有这一层</h2>
 * <p>L2 行车区间是有方向的，灯又是错开的 —— 一辆车夹在错开的一段里时**既在上行区间中、也在下行
 * 区间中**，按 L2 画就是两条带压在同一根轨上（现场实测 140 根轨里 62 根属于 2 个以上区间）。
 * 总区间把"位置"和"归属"分开：**位置只有一条**（几何 = L1 轨道区间），归属（哪几个方向、什么显示）
 * 在 {@code covers[]} 里，图上用悬浮提示读出来。见 `domain/TotalSection.ts`。</p>
 *
 * <h2>画在哪、多宽</h2>
 * <p>画在**轨道中心线上**（不让开），宽度 **1 格** —— 与区间切点、轨道线同一个宽度
 * （用户 2026-09-16 定："按当前的风格重新绘图，区间线宽和点相同"）。于是它**正好盖住整根轨**：
 * 这一页看得见的"轨"就是这条带本身，轨的颜色与形状都从带上看。</p>
 *
 * <p>正因为这样，**L2 方向带已经从这一页撤下**（`BlockSectionsLayer.vue` 还在、用例也在）：
 * 它原本是 0.4 格、分居中心线两侧 ± 0.25 格（两条合起来铺满一根轨），现在 1 格宽的带会把它们
 * 整条盖住，留着只是白画。要"总区间当主角 + 两侧细条"（0.6 + 0.2×2 之类）就把它调窄加回来。</p>
 *
 * <h2>道岔没指向的那根出口：淡一半**画在带上**</h2>
 * <p>「地图」页是让**轨**淡一半；这一页带把轨盖住了，轨的透明度就看不见了 —— 所以同一个规则
 * 改挂在**带上**：span 自带的 {@code railHex} 若在引擎的 {@code prohibited} 里，这条带
 * `stroke-opacity = 0.5`。用的还是**同一个** {@link prohibitedRailHexes}（哪根被切掉是引擎在
 * `mmtr-points` 每行给的说法，网页不自己算几何），两页的淡出规则永远同源。</p>
 * <p>代价说清楚：50% 透明度画在纯黑底上 = **颜色变暗**（黄 {@code #d8b81c} → 暗橄榄
 * {@code #6c5c0e}），所以"被切掉的黄"与"淡黄 {@code #e3dc9c}"在观感上会接近。要么接受，
 * 要么把这一档改成"不淡、换画法"（用户一句话）。</p>
 *
 * <h2>颜色</h2>
 * <p>与 L2 同一套色（占用 淡红 / 单黄 黄 / 双黄 淡黄 / 红灯空区间 淡红 / 空闲 淡绿），
 * 外加一档 **无信号（灰 + 虚线）**：两侧都没有灯（补出来的无灯大区间）时用它 ——
 * 这种地段**绝不能当绿灯画**（"没有灯"不是"可以走"）。</p>
 */
import {computed} from "vue";
import {prohibitedRailHexes, railHexKey} from "@/domain/MapNode";
import {totalSectionState, type TotalSection, type TotalSectionState} from "@/domain/TotalSection";
import {UNITS_PER_BLOCK, useMapContext} from "../mapContext";
import {useLiveFeeds} from "../liveFeeds";

/**
 * 带的宽度，画布单位：**1 格** —— 与区间切点（直径 1 格）和轨道线（宽 1 格）同一个数，
 * 所以它正好盖住整根轨，一眼看得出"这一处是什么状态"。
 */
const BAND_WIDTH = UNITS_PER_BLOCK;
/** 道岔没指向的那根出口：带淡一半（与「地图」页轨的淡出、与上一版 L2 带同一个数）。 */
const PROHIBITED_BAND_OPACITY = 0.5;

const STATE_COLORS: Readonly<Record<TotalSectionState, string>> = {
	occupied: "#d98080",      // 淡红
	singleYellow: "#d8b81c",  // 黄
	doubleYellow: "#e3dc9c",  // 淡黄
	red: "#d98080",           // 淡红（红灯但空着：不通就是不通）
	clear: "#5aa96e",         // 淡绿
	unsignalled: "#8a8a8a",   // 灰（无灯区段）
};

const STATE_LABELS: Readonly<Record<TotalSectionState, string>> = {
	occupied: "占用",
	singleYellow: "单黄",
	doubleYellow: "双黄",
	red: "红灯（空）",
	clear: "空闲",
	unsignalled: "无信号",
};

/** 画布里的一条带。 */
interface PlacedBand {
	readonly key: string;
	readonly state: TotalSectionState;
	readonly points: string;
	readonly title: string;
	/** 这根轨是道岔没指向的那根出口（整条淡化）。 */
	readonly prohibited: boolean;
}

const ctx = useMapContext();
/*
 * 数据来自页面的活数据 store（`../liveFeeds.ts`）：**总区间每拍换一次**（颜色随占用/灯位变），
 * 道岔（`prohibited` → 哪条带淡一半）也是活的 —— 这一层不再自己取数。
 */
const feeds = useLiveFeeds();
const sections = feeds.totalSections;
const prohibitedHexes = computed<ReadonlySet<string>>(() => new Set([...prohibitedRailHexes(feeds.mapNodes.value)].map(railHexKey)));

/** 悬浮提示：这一处是什么、多少个方向、各方向是哪一段（错开就看这里）。 */
function describe(section: TotalSection, state: TotalSectionState): string {
	const parts = [`总区间 ${section.id}`, `${Math.round(section.lengthM * 10) / 10} m`, STATE_LABELS[state]];
	parts.push(`${section.directionCount} 个方向`);
	if (section.staggered) {
		parts.push("错开（上下行的区间不是同一段路）");
	}
	for (const cover of section.covers) {
		const name = cover.uncovered ? "无灯区段" : `灯 ${cover.entrySignal}`;
		const aspect = cover.uncovered ? "无信号" : cover.aspect;
		parts.push(`${name} · ${aspect}${cover.occupied ? " · 占用" : ""}${cover.directionLabel === "" ? "" : ` · ${cover.directionLabel}行`}`);
	}
	return parts.join(" · ");
}

const bands = computed<readonly PlacedBand[]>(() => {
	ctx.anchor.value;
	const prohibited = prohibitedHexes.value;
	const placed: PlacedBand[] = [];
	for (const section of sections.value) {
		const state = totalSectionState(section);
		const title = describe(section, state);
		section.spans.forEach((span, index) => {
			if (span.points.length < 2) {
				return;
			}
			// 不让开：总区间就是"这一处"，画在轨道中心线上
			const points = span.points.map(([x, z]) => ctx.project(x, z).join(",")).join(" ");
			// span 的 hex 按**行车方向**写，与道岔那边（mmtr-points）的写法可能相反 ⇒ 规范化后比
			// （实测 159 根里 10 根是倒过来的，不做这一步就会漏掉 1 根禁止轨的淡出）
			placed.push({
				key: `${section.id}#${index}`,
				state,
				points,
				title,
				prohibited: prohibited.has(railHexKey(span.railHex)),
			});
		});
	}
	return placed;
});

</script>

<template>
	<polyline
		v-for="band in bands"
		:key="band.key"
		class="total-band"
		:class="{unsignalled: band.state === 'unsignalled'}"
		:points="band.points"
		:stroke="STATE_COLORS[band.state]"
		:stroke-width="BAND_WIDTH"
		:stroke-opacity="band.prohibited ? PROHIBITED_BAND_OPACITY : 1"
	>
		<title>{{ band.title }}</title>
	</polyline>
</template>

<style scoped>
/* 带：只有描边（折线）；端头用平口 —— 相邻两条总区间是两段，不该糊在一起 */
.total-band {
	fill: none;
	stroke-linejoin: round;
	stroke-linecap: butt;
}

/* 无信号区段：虚线 —— 一眼看出"这一处没有灯"，与"绿灯空闲"分得清 */
.total-band.unsignalled {
	stroke-dasharray: 4 3;
}
</style>
