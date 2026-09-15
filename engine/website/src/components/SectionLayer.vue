<script setup lang="ts">
import {computed} from "vue";
import {worldToScreen, type Camera} from "@/domain/camera";
import {directionColor, hasDirection, type Section, type SectionSpan} from "@/domain/Section";
import {offsetPath, sideOfDirection} from "@/domain/sectionBands";

/*
 * 区间层（方案 B）：**沿轨法向偏移的"方向带"**。
 *
 * <h3>为什么是两条带</h3>
 * <p>区间是"某一行车方向下的一段路"，不是"一段轨"。双向线路上同一根轨的南行与北行**各有一个区间**，
 * 所以一根轨上同时存在两段互相独立的区间 —— 把它们画在同一条线上就分不开了。方案 B 的做法：
 * 把同一个方向的所有区间沿轨的**法向**偏移一段固定距离，于是两个方向自然成两条并排的带，
 * 每条带再被**它自己方向的灯**切成一段一段。这正是现实里双向区间的样子
 * （分界点两架背靠背的灯，各管一个方向）。</p>
 *
 * <h3>为什么偏移量按方向定，而不是按"第几条"定</h3>
 * <p>按出现顺序编号（第一条偏移 +5、第二条 −5）会让**同一根轨上同方向的多个区间**分到两条带上
 * （现场实测有一根轨被 5 个区间覆盖），看图的人会以为它们是两个方向。按**方向**定偏移则天然自洽：
 * 南行永远在法向一侧、北行永远在另一侧，重叠的区间落回同一条带并**叠色**，一眼看出"这里区间重叠"。</p>
 *
 * <h3>几何从哪来</h3>
 * <p>折线点由**引擎采样**（`spans[].points`，世界坐标），这里只做"投影到屏幕 + 法向偏移"，
 * 不重算轨的弧长与形态 —— 引擎是几何的唯一真源（与轨道层同一条规矩）。</p>
 */

const props = defineProps<{
	sections: readonly Section[];
	camera: Camera;
	/** 选中的区间 id：加粗并置顶。 */
	selectedSection?: string;
}>();

/** 法向偏移量（屏幕像素）。正负各一侧，两个方向各占一条带。 */
const BAND_OFFSET_PX = 2.6;

/** 两个方向都没有区间时这一层什么都不画，省掉整轮投影。 */
const hasSections = computed(() => props.sections.length > 0);

/**
 * 一段区间的折线 → 偏移后的 SVG 路径。
 *
 * <p>这里只做两件事：投影到屏幕、按方向求偏移量。偏移的几何本身在 `domain/sectionBands.ts`
 * （纯函数、有单测），因为这个组件渲染不了也测不了。</p>
 */
function bandPath(span: SectionSpan, side: number): string {
	const projected = [];
	for (let i = 0; i + 1 < span.points.length; i += 2) {
		projected.push(worldToScreen(props.camera, span.points[i]!, span.points[i + 1]!));
	}
	return offsetPath(projected, side * BAND_OFFSET_PX);
}

/** 每个区间每一段的画线数据。区间之间按方向错开，重叠的区间叠色。 */
const bands = computed(() => {
	const result: {
		key: string;
		d: string;
		color: string;
		occupied: boolean;
		section: string;
		label: string;
		selected: boolean;
	}[] = [];
	for (const section of props.sections) {
		/*
		 * 旧引擎（还没部署 notes/156）发的区间没有 `direction`：那时这一层画不了，
		 * 但**不能因此让整页挂掉** —— 跳过它，别的图层照常。
		 */
		if (!hasDirection(section)) {
			continue;
		}
		const side = sideOfDirection(section.direction.angle);
		const color = directionColor(section.direction.angle);
		section.spans.forEach((span, index) => {
			const d = bandPath(span, side);
			if (d === "") {
				return;
			}
			result.push({
				key: `${section.id}#${index}`,
				d,
				color,
				occupied: section.occupied,
				section: section.id,
				label: section.direction.label,
				selected: props.selectedSection === section.id,
			});
		});
	}
	// 选中的最后画（压在最上层）
	return result.sort((a, b) => Number(a.selected) - Number(b.selected));
});
</script>

<template>
	<g v-if="hasSections" class="section-layer">
		<!--
			每条带画两遍：先一条较粗的半透明"带"，再一条细的实线。
			<path> 在 SVG 里**不能真正偏移**（`transform: translate` 是几何变换，
			在缩放过的图里会跟着缩放），所以偏移量已经在 `bandPath` 里逐点算进坐标。
		-->
		<path
			v-for="band in bands"
			:key="`${band.key}-band`"
			class="band"
			:d="band.d"
			:stroke="band.occupied ? '#d9534f' : band.color"
			:stroke-width="band.selected ? 7 : 5"
			:stroke-opacity="band.selected ? 0.5 : 0.28"
			:data-section="band.section"
			:data-direction="band.label"
		/>
		<path
			v-for="band in bands"
			:key="`${band.key}-line`"
			class="band-line"
			:d="band.d"
			:stroke="band.occupied ? '#ff7b74' : band.color"
			:stroke-width="band.selected ? 2.6 : 1.6"
			:data-section="band.section"
			:data-direction="band.label"
			:data-occupied="band.occupied ? '1' : '0'"
		/>
	</g>
</template>

<style scoped>
.section-layer .band,
.section-layer .band-line {
	fill: none;
	stroke-linecap: round;
	stroke-linejoin: round;
	pointer-events: none;
}
</style>
