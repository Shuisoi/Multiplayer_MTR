<script setup lang="ts">
import {computed} from "vue";
import {worldToScreen, type Camera} from "@/domain/camera";
import {hasDirection, type Section, type SectionSpan} from "@/domain/Section";
import {offsetPath, sideOfDirection} from "@/domain/sectionBands";
import {SECTION_OCCUPIED_COLOR} from "@/domain/railColors";

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
	/**
	 * 轨 hex → 轨道线颜色（`domain/railColors.ts`）。**区间带用轨道的颜色**。
	 *
	 * <p>用户 2026-09-15 的要求："区间颜色从目前 web 生成的线派生，别独立生成了"。
	 * 区间本来就是"某段轨上的一个弧窗"，它画出来的颜色必须与那段轨一样，否则同一段路在屏幕上是两种颜色，
	 * 看图的人会以为它们是两样东西。</p>
	 *
	 * <p>缺省回退到灰色（找不到那根轨时），而不是另起一套配色。</p>
	 */
	railColorByHex?: ReadonlyMap<string, string>;
}>();

/** 找不到轨色时的回退（与轨道层最慢那一档同色，而不是新造一个颜色）。 */
const FALLBACK_RAIL_COLOR = "#4e585f";

/**
 * 法向偏移基准量（屏幕像素）。
 *
 * <h3>为什么必须是"小到不超过相邻走廊间距的一半"</h3>
 * <p>这台相机的世界→屏幕比例是**变的**（整图 fits 到视口）：实测本站场走廊在屏幕上只隔 **约 8 px**，
 * 而平行股道在世界上隔 8 m —— 也就是这个缩放下 **1 m ≈ 1 px**。所以任何按"固定像素"给的带宽
 * 都可能大过走廊间距：第一版把带画成 **5 px 粗**，于是相邻股道的带互相叠上，
 * 整片站场糊成一条（用户 2026-09-15："完全混乱显示了"）。</p>
 *
 * <p>现在的口径：带是**细线**（1.2–2.2 px），偏移只有 2 px —— 在实测缩放下约等于 2 m，
 * 远小于 8 m 的走廊间距，所以两条方向带只在**自己那根轨**两侧，不会盖到邻轨上去。</p>
 */
const BAND_OFFSET_PX = 2.0;

/** 两个方向都没有区间时这一层什么都不画，省掉整轮投影。 */
const hasSections = computed(() => props.sections.length > 0);

/**
 * 一段区间的折线 → 偏移后的 SVG 路径。
 *
 * <p>只按**方向**偏移（两个方向各占轨的一侧）。同一侧要是压着多个区间，就让它们**重合**：
 * 颜色本来就取轨道的颜色，再按序错开只会把带推到邻轨上去（第一版就是这么糊掉的）。</p>
 */
function bandPath(span: SectionSpan, side: number): string {
	const projected = [];
	for (let i = 0; i + 1 < span.points.length; i += 2) {
		projected.push(worldToScreen(props.camera, span.points[i]!, span.points[i + 1]!));
	}
	return offsetPath(projected, side * BAND_OFFSET_PX);
}

/** 每个区间每一段的画线数据。颜色取自**它所在那根轨**的颜色；占用转红（状态，不是配色）。 */
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
	// 同侧的第几个区间（颜色跟轨道一样，重复的区间就重合画，不再往邻轨上推）
	const stackBySide = new Map<number, number>();
	for (const section of props.sections) {
		/*
		 * 旧引擎（还没部署 notes/156）发的区间没有 `direction`：那时这一层画不了，
		 * 但**不能因此让整页挂掉** —— 跳过它，别的图层照常。
		 */
		if (!hasDirection(section)) {
			continue;
		}
		const side = sideOfDirection(section.direction.angle);
		stackBySide.set(side, (stackBySide.get(side) ?? 0) + 1);
		section.spans.forEach((span, index) => {
			const d = bandPath(span, side);
			if (d === "") {
				return;
			}
			result.push({
				key: `${section.id}#${index}`,
				d,
				// **与轨道层同一个颜色**（用户要求：从 web 生成的线派生）
				color: props.railColorByHex?.get(span.hex) ?? FALLBACK_RAIL_COLOR,
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
			**只画一条细线**（每段一条），不再画 5 px 的半透明"宽带"。
			理由见 BAND_OFFSET_PX 的说明：这台相机的比例是变的，固定像素的宽带宽过走廊间距时，
			相邻股道的带会互相叠上，整片站场糊成一条（第一版就是这样"完全混乱"的）。
			`<path>` 在 SVG 里不能真正偏移（`transform` 会跟着缩放），所以偏移量已在 bandPath 里算进坐标。
		-->
		<path
			v-for="band in bands"
			:key="band.key"
			class="band-line"
			:d="band.d"
			:stroke="band.occupied ? SECTION_OCCUPIED_COLOR : band.color"
			:stroke-width="band.selected ? 2.4 : 1.6"
			:data-section="band.section"
			:data-direction="band.label"
			:data-occupied="band.occupied ? '1' : '0'"
		/>
	</g>
</template>

<style scoped>
.section-layer .band-line {
	fill: none;
	stroke-linecap: round;
	stroke-linejoin: round;
	pointer-events: none;
}
</style>
