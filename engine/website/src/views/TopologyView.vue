<script setup lang="ts">
import {computed, ref, useTemplateRef} from "vue";
import {NModal, useMessage} from "naive-ui";
import Viewport from "@/components/Viewport.vue";
import NodeMarker from "@/components/NodeMarker.vue";
import {Node} from "@/domain/Node";
import {sampleNodes} from "@/domain/sampleNodes";

/*
 * 轨道层视图：现在只做一件事——把节点摆出来，验证节点自身的交互。
 *
 * 坐标全部是**世界坐标**，交给 SVG 的 viewBox 映射成屏幕（见 Viewport.vue）。
 * 这一层不做任何"算像素"的事：节点用 <g transform="translate(x y)"> 摆在世界坐标上；
 * 滚轮缩放、拖动平移只改 viewBox。所以没有"父组件与视口各算一半"的偏移问题。
 *
 * 数据是 domain/sampleNodes.ts 的三个示例节点（通过点 / 道岔 / 端点），**不连服务端**。
 */

const viewport = useTemplateRef<InstanceType<typeof Viewport>>("viewport");
const message = useMessage();

const nodes = ref<Node[]>(sampleNodes);

/** 示例布局：三个节点沿世界 X 轴排开（间距就是世界单位，缩放时一起放大缩小）。 */
const spacing = 40;
const placed = computed(() => nodes.value.map((node, index) => ({node, x: index * spacing, y: 0})));
const world = computed(() => ({
	minX: 0,
	minY: 0,
	width: Math.max(1, (placed.value.length - 1) * spacing),
	height: 1,
}));

const detail = ref({open: false, title: "", text: ""});
/** 选中的节点（操作菜单打开中）。 */
const selectedKey = ref("");

function onAction({node, action}: {node: Node; action: string}) {
	switch (action) {
		case "center":
			selectedKey.value = node.key;
			viewport.value?.centerOn(worldPointOf(node)?.x ?? 0, 0);
			break;
		case "copy":
			void navigator.clipboard.writeText(node.coords);
			message.success(`已复制坐标 ${node.coords}`);
			break;
		case "neighbors":
			void navigator.clipboard.writeText(node.neighbours.map(neighbour => neighbour.rail).join("\n"));
			message.success(`已复制 ${node.neighbours.length} 条相邻轨`);
			break;
		case "block":
			selectedKey.value = node.key;
			detail.value = {
				open: true,
				title: `节点 ${node.coords} · 所属区间`,
				text: `区间 id：${node.block || "（无）"}\n类型：${node.isUnguardedBlock ? "无灯区间（无人看守）" : "有灯区间"}\n\n（尚未接入引擎：接入后这里显示 blocks ${node.key} 的诊断输出。）`,
			};
			break;
		case "fork":
			selectedKey.value = node.key;
			detail.value = {
				open: true,
				title: `道岔 ${node.coords}`,
				text: `度数 ${node.degree} · 相邻轨 ${node.neighbours.length} 条\n\n（尚未接入引擎：接入后这里显示该节点的进向与各条腿。）`,
			};
			break;
		default:
			break;
	}
}

function worldPointOf(node: Node) {
	return placed.value.find(item => item.node.key === node.key);
}
</script>

<template>
	<div class="wrap">
		<Viewport
			ref="viewport"
			:world-min-x="world.minX"
			:world-min-y="world.minY"
			:world-width="world.width"
			:world-height="world.height"
		>
			<!-- 节点：世界坐标里的一个 <g>；圆点与浮层都由 NodeMarker 负责。 -->
			<NodeMarker
				v-for="item in placed"
				:key="item.node.key"
				:node="item.node"
				:x="item.x"
				:y="item.y"
				:selected="selectedKey === item.node.key"
				@action="onAction"
			/>
		</Viewport>

		<div class="hud">
			<span>示例节点 <b class="value">{{ nodes.length }}</b></span>
			<span class="tip">悬停看信息 · 左键开菜单 · 滚轮缩放 · 拖动平移</span>
			<button class="reset" @click="viewport?.fit()">重置视图</button>
		</div>

		<NModal v-model:show="detail.open" preset="card" :title="detail.title" style="width: 680px; max-width: 92vw">
			<pre class="panel">{{ detail.text }}</pre>
		</NModal>
	</div>
</template>

<style scoped>
.wrap {
	position: relative;
	width: 100%;
	height: 100%;
}

.hud {
	position: absolute;
	left: 12px;
	bottom: 10px;
	display: flex;
	align-items: center;
	gap: 14px;
	font-size: 12px;
	color: var(--fg-dim);
}

.hud b {
	color: var(--fg);
	font-weight: 600;
}

.tip {
	color: var(--fg-faint);
}

.reset {
	margin-left: 4px;
	padding: 2px 10px;
	font-family: var(--font-ui);
	font-size: 12px;
	color: var(--fg-secondary);
	background: #0f0f0f;
	border: 1px solid #262626;
	border-radius: var(--radius);
	cursor: pointer;
}

.reset:hover {
	color: #fff;
	border-color: #4a4a4a;
}

.panel {
	margin: 0;
	max-height: 50vh;
	overflow: auto;
	font-family: var(--font-value);
	font-size: 12px;
	line-height: 1.6;
	color: var(--fg-secondary);
	white-space: pre-wrap;
}
</style>
