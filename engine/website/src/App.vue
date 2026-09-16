<script setup lang="ts">
import {ref} from "vue";
import {NConfigProvider, NDialogProvider, NMessageProvider, NRadioButton, NRadioGroup, darkTheme, zhCN, dateZhCN} from "naive-ui";
import AppBar from "./components/AppBar.vue";
import MapView from "./views/MapView.vue";
import PlanView from "./views/PlanView.vue";
import SectionMapView from "./views/SectionMapView.vue";
import {themeOverrides} from "./theme";

/*
 * 外壳：Naive UI 的深色主题 + 我们自己的 token。
 *
 * 这里做三件事：把 Naive UI 的主题对齐 C# 端（纯黑底、强调色 #0078D7、小圆角、DIN 数字字体），
 * 摆上上边栏，然后决定内容区放谁。
 *
 * <h2>地图视图为什么是空的（2026-09-15）</h2>
 * 旧的绘图那一摊（画布 + 相机 + 六个图层/标记 + 尺度表，共 4796 行）已经**整块删除** ——
 * 用户原话："给绘图逻辑全删了吧，太混乱了"、"都搞干净然后重新构建"。
 * 删掉的东西里积累了三套互相打架的坐标/尺寸口径（屏幕像素 / 取景基准 / 世界格），
 * 每一层各自乘倍率，所以任何一处理解错都会以"位置或尺寸不对"的形式出现在整张图上。
 *
 * <p>现在的做法是**相机外包给现成控件**（`svg-pan-zoom`，见 `views/MapView.vue`）：
 * 它把相机写成 viewport `<g>` 上的**一个矩阵**，所有元素按世界坐标画、尺寸也是世界单位，
 * 于是"元素相对缩放、位置不变"成了同一个矩阵的必然结果 —— 不再有任何一层自己乘倍率。</p>
 */
const overrides = themeOverrides();
// 默认落在**区间地图**：这一轮新建的是它。地图/时刻表切过去就能用。
const view = ref<"map" | "sections" | "plan">("sections");
</script>

<template>
	<NConfigProvider
		:theme="darkTheme"
		:theme-overrides="overrides"
		:locale="zhCN"
		:date-locale="dateZhCN"
	>
		<NMessageProvider>
			<NDialogProvider>
				<div class="console">
					<AppBar/>
					<main class="content">
						<div class="switcher">
							<NRadioGroup v-model:value="view" size="small">
								<NRadioButton value="sections">区间地图</NRadioButton>
								<NRadioButton value="map">地图</NRadioButton>
								<NRadioButton value="plan">时刻表</NRadioButton>
							</NRadioGroup>
						</div>
						<!--
							视图本体：滚动只给时刻表。两张地图要的都是"量得到尺寸"的舞台，
							外层一旦是可滚容器，画布高度就会跟着内容走（塌成 0 那一类事故的来源）。
						-->
						<div :class="['view-body', view === 'plan' ? 'view-body--scroll' : '']">
							<SectionMapView v-if="view === 'sections'"/>
							<MapView v-else-if="view === 'map'"/>
							<PlanView v-else/>
						</div>
					</main>
				</div>
			</NDialogProvider>
		</NMessageProvider>
	</NConfigProvider>
</template>

<style scoped>
/*
 * 外壳布局：不依赖 flex 的高度传导。
 *
 * 实测踩过：`.console`(flex column) + `.content{flex:1}` 这条链上，内容区高度会塌成 0 ——
 * 里面的视口量到 1244x0，自动缩放算错，节点全挤在一点。所以这里用"上边栏固定高度 +
 * 内容区绝对定位填满其余空间"，是确定的、量得到尺寸的布局。
 */
.console {
	position: relative;
	width: 100%;
	height: 100%;
	min-height: 100vh;
	background: var(--bg);
}

.content {
	position: absolute;
	/*
	 * 上边栏高度写死 48px（不再用 var(--bar-height)）：这条定位是这个布局的关键接缝，
	 * 万一 CSS 变量没解析出来（自定义属性在 :root 上、组件样式在 body 之后加载等），
	 * top 会变成 auto，内容区就落回文档流、高度塌成 0。写死值不可能失效。
	 */
	top: 48px;
	right: 0;
	bottom: 0;
	left: 0;
	overflow: hidden;
	background: var(--bg);
}

/* 视图切换条：与上边栏同一种玻璃，高度写死 40px —— 内容区的 top 就靠这个数。 */
.switcher {
	position: absolute;
	top: 0;
	left: 0;
	right: 0;
	height: 40px;
	display: flex;
	align-items: center;
	padding: 0 12px;
	background: var(--glass);
	border-bottom: 1px solid var(--hairline);
}

.switcher + .view-body {
	top: 40px;
}

/* 视图本体：绝对定位填满剩余空间（新画布要的是"量得到尺寸"，所以这里不能用 flex 传导高度）。 */
.view-body {
	position: absolute;
	top: 0;
	right: 0;
	bottom: 0;
	left: 0;
	overflow: hidden;
}

.view-body--scroll {
	overflow: auto;
}
</style>
