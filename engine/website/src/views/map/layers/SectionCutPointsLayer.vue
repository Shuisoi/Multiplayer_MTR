<script setup lang="ts">
/*
 * 区间地图图层：**区间端点**（L1 轨道区间的节点 / L2 行车区间的端点所在处）—— 这一页的"节点"，
 * **不画轨道节点**。
 *
 * <p>端点 = 每条 L1 轨道区间、每个 span 的两端，去重后就是它们（{@link sectionCutPoints}）。
 * 之所以它同时也是**行车区间的端点**：L2 行车区间是"灯到灯"，而**切点只由灯产生**（notes/166
 * 裁定 2），所以两个方向的区间都在这批点上断开。实测这张世界 156 个端点里 155 个正好落在轨道节点上、
 * 只有 1 个是轨中段的灯切点 —— 所以它看着像"同一批点"，区别只在位置（**方块中心**）
 * 与多出来的那一个。</p>
 *
 * <h2>样式与「地图」页的节点圆点**逐条相同**（用户 2026-09-16："点的样式也和地图相同"）</h2>
 * <ol>
 *   <li>白心 + 灰边（`var(--fg)` / `var(--fg-dim)`），**边框宽 = 直径的 10%**；</li>
 *   <li>**渐变黑色外阴影**：半径 = 点外半径 × 2.5（与「地图」页同一个倍数），径向渐变在那之前全黑、
 *       之后渐隐到透明 —— 所以紧贴边框的一圈是黑的；</li>
 *   <li>**阴影夹在线和点之间**（轨/带 → 阴影 → 点）：它落在穿过端点的轨与带上，那圈黑才看得见
 *       （画在线的下面等于白画）。</li>
 * </ol>
 * <p>唯一的差别是**尺寸**：这一页端点是 2 格（用户："将行车区间的端点变为原来的2倍"），
 * 「地图」页的节点是 1 格 —— 样式规则一样，跟着尺寸等比放大。</p>
 *
 * <p>画在**最上层**（轨 → 区间带 → 端点）：点要压在带和线上，才看得出"区间在哪儿断开"。</p>
 */
import {computed, onMounted, ref} from "vue";
import {fetchTrackSections} from "@/api/topology";
import {sectionCutPoints, type TrackSection} from "@/domain/TrackSection";
import {UNITS_PER_BLOCK, useMapContext} from "../mapContext";

/**
 * 端点直径，画布单位：**2 格**（2026-09-16 用户："将行车区间的端点变为原来的2倍" —— 原来是 1 格，
 * 与「地图」页的节点圆点同口径）。边框宽度按同一个比例（直径的 10%）一起放大，
 * 所以观感还是"白点 + 灰边"，只是整体大了一倍。
 */
const CUT_POINT_DIAMETER = UNITS_PER_BLOCK * 2;
/** 边框宽度，画布单位：占直径的 10%（原来的 1 格点就是这个口径：0.1 格边框）。 */
const CUT_POINT_STROKE = CUT_POINT_DIAMETER * 0.1;
/** 半径：**连边框一起**正好等于直径（`stroke` 两侧各画一半，所以要扣掉半个边框）。 */
const CUT_POINT_RADIUS = (CUT_POINT_DIAMETER - CUT_POINT_STROKE) / 2;
/** 黑色外阴影的半径倍数（相对点的外半径）—— 与「地图」页同一个数。 */
const CUT_POINT_SHADOW_SCALE = 2.5;
/** 外阴影半径，画布单位。 */
const CUT_POINT_SHADOW_RADIUS = (CUT_POINT_DIAMETER / 2) * CUT_POINT_SHADOW_SCALE;

/** 画布里的一个点。 */
interface Placed {
	readonly key: string;
	readonly x: number;
	readonly y: number;
}

const ctx = useMapContext();
const sections = ref<readonly TrackSection[]>([]);

const cutPoints = computed<readonly Placed[]>(() => {
	ctx.anchor.value;
	return sectionCutPoints(sections.value).map(point => {
		const [x, y] = ctx.project(point.x, point.z);
		return {key: point.key, x, y};
	});
});

async function load(): Promise<void> {
	try {
		const feed = await fetchTrackSections();
		sections.value = feed.sections;
	} catch (caught) {
		// 页面上不摆任何提示（只留图形与控件）：失败落控制台。
		console.error("取 L1 轨道区间失败", caught);
	}
}

onMounted(() => {
	void load();
});
</script>

<template>
	<!--
		两层，按这个顺序叠：点的外阴影 → 点。
		阴影夹在中间是**故意的**（与「地图」页同一套）：它落在穿过端点的轨与区间带上，
		于是紧贴边框的那圈黑是看得见的 —— 画在带/线的下面就等于白画。
	-->
	<defs>
		<!--
			端点的黑色外阴影（与「地图」页的 `#mmtr-node-shadow` 同一套写法，id 另取一个：
			整个 SVG 共享一个 id 空间）。objectBoundingBox 单位：0% = 阴影圆心、100% = 阴影边缘，
			"点边缘"落在 `1 / CUT_POINT_SHADOW_SCALE`；在那之前保持全黑（反正被点盖住），之后线性渐隐。
		-->
		<radialGradient id="mmtr-cut-point-shadow">
			<stop offset="0%" stop-color="#000000" stop-opacity="1"/>
			<stop :offset="`${100 / CUT_POINT_SHADOW_SCALE}%`" stop-color="#000000" stop-opacity="1"/>
			<stop offset="100%" stop-color="#000000" stop-opacity="0"/>
		</radialGradient>
	</defs>
	<circle
		v-for="point in cutPoints"
		:key="`shadow-${point.key}`"
		class="cut-point-shadow"
		:cx="point.x"
		:cy="point.y"
		:r="CUT_POINT_SHADOW_RADIUS"
	/>
	<circle
		v-for="point in cutPoints"
		:key="point.key"
		class="cut-point"
		:cx="point.x"
		:cy="point.y"
		:r="CUT_POINT_RADIUS"
		:stroke-width="CUT_POINT_STROKE"
	/>
</template>

<style scoped>
/*
 * 端点：与「地图」页的节点圆点同一套样式（白心 + 灰边 + 渐变黑色外阴影）。
 * 尺寸一律在画布坐标系里定（见文件头常量），CSS 只管颜色。
 */
.cut-point {
	fill: var(--fg);
	stroke: var(--fg-dim);
}

.cut-point-shadow {
	fill: url("#mmtr-cut-point-shadow");
}
</style>
