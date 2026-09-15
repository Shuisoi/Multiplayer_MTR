<script setup lang="ts">
import {computed} from "vue";
import {blockGridLines, BLOCK} from "@/domain/mapElements";

/*
 * **方块网格**：一层半透明的 1×1 格底衬。
 *
 * <p>用户 2026-09-15："我的世界的坐标永远都是 1x1x1，方块也是，就不能按 1x1x1 进行放置吗？
 * 给屏幕加一个 1x1x1 的半透明叠加底层。"</p>
 *
 * <p>它同时是**标尺**：网格线落在世界坐标的整格上（`BLOCK.origin` 是格子线的相位），
 * 所以"元素是不是按格放的"可以在屏幕上直接看出来 —— 轨宽正好一条格带、圆点正好一格。</p>
 *
 * <p>网格是**世界属性**：格子大小恒为 1 格，随相机一起缩放（推近时格子变大、格数减少）。
 * 这是对的 —— 它就是世界里的方块。</p>
 *
 * <p>实现用 SVG `pattern`（一格一个 tile），所以格子数再多也只有三个 DOM 节点。</p>
 */

defineProps<{
	/** 视口的 viewBox 尺寸（世界单位）：网格只铺可见范围，不铺整张图。 */
	viewWidth: number;
	viewHeight: number;
}>();

/** 网格线的相位：格子线落在整数世界坐标上（默认原点；相机原点若不在整数上也能对齐）。 */
const origin = computed(() => BLOCK.origin);
</script>

<template>
	<defs>
		<!--
			一格一个 tile：`patternUnits="userSpaceOnUse"` 让 tile 以**世界坐标**为单位，
			于是 pattern 随相机缩放（缩放时格子跟着变大），这正是方块该有的行为。
		-->
		<pattern
			id="mmtr-block-grid"
			patternUnits="userSpaceOnUse"
			:x="origin.x"
			:y="origin.y"
			:width="BLOCK.size"
			:height="BLOCK.size"
		>
			<!-- 每格的右下两条边就是格线；描边宽度按规格（世界单位） -->
			<path
				:d="blockGridLines(BLOCK.size)"
				:stroke-width="BLOCK.lineWidth"
				stroke-linecap="square"
			/>
		</pattern>
	</defs>
	<!--
		铺满可见范围：尺寸取 viewBox 大小（比视口略大，免得边缘出现半格空白）。
		它不吃事件（pointer-events: none），也不参与任何命中判定。
	-->
	<rect
		class="block-grid"
		:x="origin.x - BLOCK.size"
		:y="origin.y - BLOCK.size"
		:width="viewWidth + BLOCK.size * 2"
		:height="viewHeight + BLOCK.size * 2"
		fill="url(#mmtr-block-grid)"
	/>
</template>

<style scoped>
/*
 * 格子线：**半透明**（用户要求"半透明叠加底层"），压在轨道与元素之下。
 * 颜色与透明度都在 `DECAL_KINDS.blockGrid` 里，这里只落样式，不写数值。
 */
.block-grid {
	pointer-events: none;
}
</style>
