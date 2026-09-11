<script setup lang="ts">
/*
 * 上边栏 (title bar)。
 *
 * C# 端窗口把内容延伸到标题栏，48px 的玻璃条上只有产品名；网页这里同构：同一种"假玻璃"
 * （深色实底 + 白色渐变雾 + 底部 1px 高光，不描边），左边标题，右边留出读数槽，
 * 等后续步骤接入真实数据（车辆 / 道岔 / 区间 …）。
 */
</script>

<template>
	<header class="bar">
		<div class="bar-glass" aria-hidden="true"/>
		<div class="bar-content">
			<!-- 标题：西文/数字走 Alte DIN 1451，中文由字体栈回落到 HarmonyOS Sans SC。 -->
			<h1 class="title">MMTR控制台</h1>
			<!-- 读数槽：后续放实时状态。 -->
			<div class="slot"/>
		</div>
	</header>
</template>

<style scoped>
.bar {
	position: relative;
	flex: none;
	height: var(--bar-height);
	overflow: hidden;
	/* 实色玻璃底：留透会把窗口底色透上来（实测染蓝）。 */
	background: var(--glass);
}

.bar-glass {
	position: absolute;
	inset: 0;
	background: var(--glass-fog);
}

.bar-glass::after {
	content: "";
	position: absolute;
	left: 0;
	right: 0;
	bottom: 0;
	height: 1px;
	background: var(--hairline);
}

.bar-content {
	position: relative;
	display: flex;
	align-items: center;
	gap: var(--gap);
	height: 100%;
	padding: 0 16px;
}

.title {
	margin: 0;
	font-family: var(--font-title);
	font-size: 16px;
	font-weight: 600;
	letter-spacing: 0.02em;
	color: var(--fg);
	white-space: nowrap;
	user-select: none;
}

.slot {
	margin-left: auto;
	display: flex;
	align-items: center;
	gap: 16px;
	font-size: 12px;
	color: var(--fg-secondary);
}
</style>
