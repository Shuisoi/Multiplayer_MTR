<script setup lang="ts">
import {computed, h, ref} from "vue";
import {NDropdown, NPopover, type DropdownOption} from "naive-ui";
import {Node} from "@/domain/Node";

/*
 * 一个轨道层节点（SVG 世界坐标）。
 *
 * 三件事，按用户的要求：
 *   1. 悬停 → 弹窗显示基础信息（坐标、类型、度数、所属区间、相邻轨）；
 *   2. 左键 → 展开操作菜单（居中、查区间、查占用、复制…）；
 *   3. 画出来是一个可点的圆点，半径随度数变化（端点细、道岔粗）。
 *
 * 为什么是 SVG 而不是 div：
 * 节点位置由父层的 viewBox 映射到屏幕，**世界单位**即可——不用自己乘缩放、也不用减平移
 * （之前用绝对定位的 div，坐标空间算错一次就整体偏移半个视口）。
 * Naive UI 的浮层是 HTML，所以用 foreignObject 承载。
 *
 * 组件不认识引擎：数据与动作都由父组件给。
 */

const props = defineProps<{
	node: Node;
	/** 世界坐标。 */
	x: number;
	y: number;
	selected?: boolean;
}>();

const emit = defineEmits<{
	(e: "action", payload: {node: Node; action: string}): void;
}>();

/** 圆点半径（世界单位）：端点细、通过点中、道岔粗且亮。 */
const radius = computed(() => (props.node.degree <= 1 ? 0.9 : (props.node.degree === 2 ? 1.3 : 1.9)));
/** 浮层锚点尺寸（世界单位），决定悬停区大小。 */
const hitSize = computed(() => radius.value * 2.6);

const kindText = computed(() => props.node.kindText);
const blockText = computed(() => {
	if (props.node.isUnguardedBlock) {
		return "无灯区间（无人看守）";
	}
	return props.node.block === "" ? "未知" : `灯 ${props.node.block}`;
});
const neighbourRows = computed(() => props.node.neighbours.map(neighbour => ({
	rail: Node.shortHex(neighbour.rail),
	to: `${neighbour.x}, ${neighbour.y}, ${neighbour.z}`,
	length: Math.round(props.node.distanceTo(neighbour)),
})));

const menuOpen = ref(false);
const trigger = ref<HTMLElement>();
const menuOptions = computed<DropdownOption[]>(() => [
	{key: "center", label: "居中到这里"},
	{key: "block", label: "查看所属区间"},
	{key: "occupancy", label: "查看占用情况"},
	{type: "divider", key: "d1"},
	{key: "copy", label: "复制坐标"},
	{key: "neighbors", label: "复制相邻轨"},
	...(props.node.isFork ? [{type: "divider" as const, key: "d2"}, {key: "fork", label: "查看道岔（进向与腿）"}] : []),
]);

/** 浮层锚点：把 HTML 元素的屏幕位置取出来给 Naive UI 的浮层用。 */
const anchor = ref({x: 0, y: 0});

function openMenu(event: MouseEvent) {
	event.stopPropagation();
	const rect = trigger.value?.getBoundingClientRect();
	anchor.value = {x: rect?.left ?? event.clientX, y: rect?.bottom ?? event.clientY};
	menuOpen.value = true;
}

function onSelect(action: string) {
	menuOpen.value = false;
	emit("action", {node: props.node, action});
}

const renderLabel = (option: DropdownOption) => h("span", {class: "menu-label"}, String(option.label ?? ""));
</script>

<template>
	<g :transform="`translate(${x}, ${y})`">
		<!-- 命中区：不可见的圆，只负责鼠标事件与悬停 -->
		<circle :r="hitSize" fill="transparent" style="cursor: pointer" @click="openMenu"/>

		<!-- 可见圆点：颜色按形态区分 -->
		<circle
			:r="radius"
			:fill="node.isFork ? '#2a2a2a' : '#111111'"
			:stroke="selected ? '#0078d7' : (node.isFork ? '#d0d0d0' : (node.degree === 2 ? '#5f5f5f' : '#3f3f3f'))"
			:stroke-width="selected ? 0.55 : 0.3"
			:class="['node', {fork: node.isFork, selected}]"
			style="pointer-events: none"
		/>

		<!-- 悬停信息卡 / 操作菜单的锚点：foreignObject 里的 HTML，尺寸是世界单位（随缩放一起变） -->
		<foreignObject :x="-hitSize" :y="-hitSize" :width="hitSize * 2" :height="hitSize * 2" style="overflow: visible">
			<div ref="trigger" class="anchor"/>

			<NPopover trigger="hover" placement="top" :show-arrow="true" :delay="120" :disabled="menuOpen" raw>
				<template #trigger>
					<div class="hover-target"/>
				</template>
				<div class="card">
					<div class="head">
						<span class="title value">{{ node.coords }}</span>
						<span class="kind">{{ kindText }}</span>
					</div>
					<div class="grid">
						<span class="label">度数</span><span class="value">{{ node.degree }}</span>
						<span class="label">所属区间</span><span class="value block">{{ blockText }}</span>
					</div>
					<div class="sub">相邻轨 {{ neighbourRows.length }} 条</div>
					<div v-for="row in neighbourRows" :key="row.rail" class="neighbour">
						<span class="value rail">{{ row.rail }}…</span>
						<span class="value dim">{{ row.length }} m</span>
						<span class="value dim">→ {{ row.to }}</span>
					</div>
					<div class="tip">左键：操作菜单</div>
				</div>
			</NPopover>
		</foreignObject>
	</g>

	<!-- 菜单是窗口级浮层（挂在 body 上），用锚点的屏幕坐标定位 -->
	<NDropdown
		trigger="manual"
		placement="bottom-start"
		:x="anchor.x"
		:y="anchor.y"
		:options="menuOptions"
		:render-label="renderLabel"
		:show="menuOpen"
		@select="onSelect"
		@clickoutside="menuOpen = false"
	/>
</template>

<style scoped>
.node {
	transition: stroke 0.15s ease, fill 0.15s ease;
}

.hover-target {
	width: 100%;
	height: 100%;
}

.anchor {
	position: absolute;
	inset: 0;
}
</style>

<style>
/* 浮层挂在 body 上，所以样式不能 scoped。 */
.card {
	min-width: 240px;
	max-width: 340px;
	padding: 10px 12px;
	background: #0a0a0a;
	border: 1px solid #262626;
	border-radius: 4px;
	box-shadow: 0 10px 28px rgba(0, 0, 0, 0.5);
	font-family: "Alte DIN 1451", "HarmonyOS Sans SC", system-ui, sans-serif;
	font-size: 12px;
	color: #ffffff;
}

.card .head {
	display: flex;
	align-items: baseline;
	justify-content: space-between;
	gap: 10px;
	padding-bottom: 6px;
	margin-bottom: 6px;
	border-bottom: 1px solid #1f1f1f;
}

.card .title {
	font-size: 13px;
}

.card .kind {
	color: #0078d7;
	white-space: nowrap;
}

.card .grid {
	display: grid;
	grid-template-columns: auto 1fr;
	gap: 2px 10px;
	margin-bottom: 6px;
}

.card .label {
	color: #8a8a8a;
}

.card .value {
	color: #c8c8c8;
	word-break: break-all;
}

.card .value.block {
	color: #ffffff;
}

.card .dim {
	color: #8a8a8a;
}

.card .sub {
	color: #8a8a8a;
	margin-bottom: 4px;
}

.card .neighbour {
	display: flex;
	gap: 8px;
	padding: 2px 0;
	border-top: 1px dashed #1a1a1a;
}

.card .tip {
	margin-top: 8px;
	color: #5f5f5f;
	font-size: 11px;
}

.menu-label {
	font-family: "Alte DIN 1451", "HarmonyOS Sans SC", system-ui, sans-serif;
	font-size: 13px;
}
</style>
