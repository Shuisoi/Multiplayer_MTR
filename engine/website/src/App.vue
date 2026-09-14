<script setup lang="ts">
import {ref} from "vue";
import {NConfigProvider, NDialogProvider, NMessageProvider, NRadioButton, NRadioGroup, darkTheme, zhCN, dateZhCN} from "naive-ui";
import AppBar from "./components/AppBar.vue";
import TopologyView from "./views/TopologyView.vue";
import PlanView from "./views/PlanView.vue";
import {themeOverrides} from "./theme";

/*
 * 外壳：Naive UI 的深色主题 + 我们自己的 token。
 *
 * 这里做三件事：把 Naive UI 的主题对齐 C# 端（纯黑底、强调色 #0078D7、小圆角、DIN 数字字体），
 * 摆上上边栏，然后在内容区里切换视图。
 *
 * 视图切换放在外壳而不是塞进某个视图里：拓扑与时刻表是**两件事**（一个看世界、一个编排），
 * 各自都不该知道对方存在；切换只换"内容区里放谁"。时刻表要能滚动，拓扑则必须是
 * "量得到尺寸、不许滚动"的画布 —— 所以内容区按视图切一个 class，见下面的注释。
 */
const overrides = themeOverrides();
const view = ref<"topology" | "plan">("topology");
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
								<NRadioButton value="topology">拓扑</NRadioButton>
								<NRadioButton value="plan">时刻表</NRadioButton>
							</NRadioGroup>
						</div>
						<div class="view-body" :class="{'view-body--scroll': view === 'plan'}">
							<TopologyView v-if="view === 'topology'"/>
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
 * 实测踩过：`.console`(flex column) + `.content{flex:1}` 这条链上，内容区高度会塌成 0——
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

/*
 * 视图切换条：与上边栏同一种玻璃，高度写死 40px —— 内容区的 top 就靠这个数（同一类接缝，
 * 不依赖 CSS 变量）。
 */
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

/*
 * 视图本体：绝对定位填满剩余空间（拓扑画布要的是"量得到尺寸"，所以这里不能用 flex 传导高度）。
 * 时刻表那一边内容会很长：给它滚动（`--scroll`），拓扑保持 hidden ——
 * 拓扑视口一旦能滚，自动缩放会量到内容高度而不是窗口高度。
 */
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
