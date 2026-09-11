<script setup lang="ts">
import {NConfigProvider, NDialogProvider, NMessageProvider, darkTheme, zhCN, dateZhCN} from "naive-ui";
import AppBar from "./components/AppBar.vue";
import TopologyView from "./views/TopologyView.vue";
import {themeOverrides} from "./theme";

/*
 * 外壳：Naive UI 的深色主题 + 我们自己的 token。
 *
 * 这里只做两件事：把 Naive UI 的主题对齐 C# 端（纯黑底、强调色 #0078D7、小圆角、DIN 数字字体），
 * 然后摆上上边栏与内容区。数据服务还没有——按指令一步一步来。
 */
const overrides = themeOverrides();
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
						<TopologyView/>
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
</style>
