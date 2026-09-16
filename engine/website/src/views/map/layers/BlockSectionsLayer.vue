<script setup lang="ts">
/*
 * 区间地图图层：**L2 行车区间的"带"**（按灯位/占用上色）。
 *
 * <h2>画在哪</h2>
 * <p>每条区间沿着它的 spans（沿轨采样点）画一条带，**垂直于它的行车方向让开一点** ——
 * 同一条轨上南行与北行是两条区间，不让开就完全重叠、只剩后画的那个颜色。
 * 让开的宽度与偏移取"半个轨宽"：轨宽 1 格，两条带各自占轨道的**一半**，合起来正好铺满一根轨。</p>
 *
 * <h2>颜色（用户 2026-09-16 的判据）</h2>
 * <table>
 *   <tr><th>状态</th><th>颜色</th><th>判据</th></tr>
 *   <tr><td>占用</td><td>淡红</td><td>{@code occupied}（有车，优先级最高）</td></tr>
 *   <tr><td>单黄</td><td>黄</td><td>{@code aspect = SINGLE_YELLOW}</td></tr>
 *   <tr><td>双黄</td><td>淡黄</td><td>{@code aspect = DOUBLE_YELLOW}</td></tr>
 *   <tr><td>红灯空区间</td><td>淡红</td><td>{@code aspect = RED} 但没车（实测 13 条：前方占用导致的）</td></tr>
 *   <tr><td>空闲</td><td>淡绿</td><td>其余（绿灯且没车，实测 67 条）</td></tr>
 * </table>
 *
 * <p><b>"淡"是颜色本身淡，不是加透明度</b>：底色是纯黑，半透明只会变成更暗的同色
 * （与 §五 那条黑色阴影的教训同源）。</p>
 *
 * <p>状态判据收在 `domain/BlockSection.ts` 的 `sectionState()`（纯函数 + 用例），
 * 这里只做"状态 → 颜色"的翻译。</p>
 */
import {computed, onMounted, ref} from "vue";
import {fetchBlockSections} from "@/api/topology";
import {bandOffsetDirection, sectionState, type BlockSection, type BlockSectionState} from "@/domain/BlockSection";
import {UNITS_PER_BLOCK, useMapContext} from "../mapContext";

/** 带宽度，画布单位：1 格的 40%（比半个轨宽略窄，两条带之间留一条缝，看得出是两条）。 */
const BAND_WIDTH = UNITS_PER_BLOCK * 0.4;
/** 带中心离轨道中心线的距离，画布单位：1 格的 1/4（两条带分居两侧，合起来铺满一根轨）。 */
const BAND_OFFSET = UNITS_PER_BLOCK * 0.25;

/** 状态 → 颜色（"淡"= 颜色本身淡）。 */
const STATE_COLORS: Readonly<Record<BlockSectionState, string>> = {
	occupied: "#d98080",      // 淡红
	singleYellow: "#d8b81c",  // 黄
	doubleYellow: "#e3dc9c",  // 淡黄
	red: "#d98080",           // 淡红（红灯但空着：不通就是不通）
	clear: "#5aa96e",         // 淡绿
};

/** 画布里的一条带。 */
interface PlacedBand {
	readonly key: string;
	readonly state: BlockSectionState;
	readonly points: string;
}

const ctx = useMapContext();
const sections = ref<readonly BlockSection[]>([]);

const bands = computed<readonly PlacedBand[]>(() => {
	ctx.anchor.value;
	const placed: PlacedBand[] = [];
	for (const section of sections.value) {
		const [ox, oy] = bandOffsetDirection(section);
		const state = sectionState(section);
		section.spans.forEach((span, index) => {
			if (span.points.length < 2) {
				return;
			}
			const points = span.points
				.map(([x, z]) => ctx.project(x + ox * BAND_OFFSET, z + oy * BAND_OFFSET).join(","))
				.join(" ");
			placed.push({key: `${section.id}#${index}`, state, points});
		});
	}
	return placed;
});

async function load(): Promise<void> {
	try {
		const feed = await fetchBlockSections();
		sections.value = feed.sections;
	} catch (caught) {
		// 页面上不摆任何提示（只留图形与控件）：失败落控制台。
		console.error("取行车区间失败", caught);
	}
}

onMounted(() => {
	void load();
});
</script>

<template>
	<polyline
		v-for="band in bands"
		:key="band.key"
		class="band"
		:points="band.points"
		:stroke="STATE_COLORS[band.state]"
		:stroke-width="BAND_WIDTH"
	/>
</template>

<style scoped>
/* 带：只有描边（折线）；端头用平口而不是圆头 —— 相邻两条区间是两段，不该糊在一起 */
.band {
	fill: none;
	stroke-linejoin: round;
	stroke-linecap: butt;
}
</style>
