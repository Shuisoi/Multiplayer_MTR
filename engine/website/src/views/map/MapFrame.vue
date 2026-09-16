<script setup lang="ts">
/*
 * 地图页的**框架**：舞台 + SVG + 相机 + 内建坐标系。图层由默认插槽放进来。
 *
 * <h2>框架负责什么</h2>
 * <ul>
 *   <li>舞台（`100% × 100%` 的绝对定位容器）与那个**唯一的** `<g class="svg-pan-zoom_viewport">`；</li>
 *   <li>相机：现成控件 svg-pan-zoom 只往那个 `<g>` 上写 `transform="matrix(…)"`；滚轮缩放、
 *       拖拽平移、双击放大都是它自带的；</li>
 *   <li>内建坐标系（`mapContext.ts`）：常量、锚点、`project()`，通过 provide 交给图层。</li>
 * </ul>
 *
 * <h2>三个必须守住的接缝（读库源码得出的，别改）</h2>
 * <ol>
 *   <li>**只有一个顶层 `<g>`**（类名 `svg-pan-zoom_viewport`），里面不许写 `transform`
 *       （库会自己写，写重了行为就不可预期）。图层都渲染在这个 `<g>` 里。</li>
 *   <li>**`viewBox` 是静态常量**，不跟数据/窗口/缩放联动：库在挂载那一刻读走它、缓存下来、
 *       再从 SVG 上删掉（所以 DOM 里查不到这个属性是正常的），那份缓存就是
 *       `fit()` / `center()` / `resize()` 唯一的取景依据。挂载后 Vue 再碰它，就等于给相机接第二个头。</li>
 *   <li>**不调 `updateBBox()`**：全库唯一用 `getBBox()` 的地方，而这里取景完全由静态 viewBox 决定。</li>
 * </ol>
 */
import {onBeforeUnmount, onMounted, shallowRef} from "vue";
import svgPanZoom from "svg-pan-zoom";
import {MAP_VIEW, provideMapContext} from "./mapContext";

/*
 * `shallowRef` 而不是 `ref`：这里存的是**真的 DOM 元素**，让 Vue 去深度代理一个 SVGSVGElement
 * 只会白白包一层（而且这层代理会被传进上下文给图层用）。
 */
const svgEl = shallowRef<SVGSVGElement | null>(null);
const stageEl = shallowRef<HTMLElement | null>(null);
provideMapContext(svgEl, stageEl);

let viewer: ReturnType<typeof svgPanZoom> | null = null;
let observer: ResizeObserver | null = null;

onMounted(() => {
	if (!svgEl.value) {
		return;
	}
	viewer = svgPanZoom(svgEl.value, {
		// 库自带的按钮是白底内联样式，和这套纯黑 token 不搭：关掉。
		controlIconsEnabled: false,
		// 按缓存的 viewBox（= 取景窗）铺满并居中。
		fit: true,
		center: true,
	});
	// 画布是 100%x100% 的：窗口大小变了要让库按新尺寸重算（`resize()` 走同一份缓存 viewBox）。
	observer = new ResizeObserver(() => viewer?.resize());
	observer.observe(svgEl.value);
});

onBeforeUnmount(() => {
	observer?.disconnect();
	observer = null;
	viewer?.destroy();
	viewer = null;
});
</script>

<template>
	<div ref="stageEl" class="frame">
		<svg
			ref="svgEl"
			class="canvas"
			:viewBox="`0 0 ${MAP_VIEW} ${MAP_VIEW}`"
			xmlns="http://www.w3.org/2000/svg"
		>
			<!--
				唯一的顶层 <g>：库认这个类名，会把相机矩阵写在它上面。
				图层按插槽顺序渲染在这里 —— **顺序就是叠放顺序**（先写的在下面）。
			-->
			<g class="svg-pan-zoom_viewport">
				<slot/>
			</g>
		</svg>

		<!--
			**HTML 浮层插槽**：图层渲染在 SVG 的 `<g>` 里，写不出能显示的 HTML
			（未知 SVG 元素不渲染；`<Teleport>` 也会把 SVG 命名空间带过去 —— 实测落地仍是 SVG 元素、
			尺寸 0×0）。所以提示/菜单这类 HTML 界面由这里渲染：它在 SVG **外面**，是真 HTML。
			容器不吃事件（`pointer-events: none`），浮层自己按需打开。
		-->
		<div class="overlay">
			<slot name="overlay"/>
		</div>
	</div>
</template>

<style scoped>
.frame {
	position: absolute;
	inset: 0;
	background: var(--bg);
	overflow: hidden;
}

.overlay {
	position: absolute;
	inset: 0;
	overflow: hidden;
	pointer-events: none;
}

.canvas {
	display: block;
	width: 100%;
	height: 100%;
	cursor: grab;
	touch-action: none;
}

.canvas:active {
	cursor: grabbing;
}
</style>
