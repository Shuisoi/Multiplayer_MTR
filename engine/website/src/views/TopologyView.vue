<script setup lang="ts">
import {computed, ref, useTemplateRef} from "vue";
import {NModal, useMessage} from "naive-ui";
import MapCanvas from "@/components/MapCanvas.vue";
import {Node} from "@/domain/Node";
import {sampleNodes} from "@/domain/sampleNodes";
import type {Camera} from "@/domain/camera";

/*
 * 轨道层视图：把节点交给 `MapCanvas` 显示，并处理节点操作菜单的动作。
 *
 * <p>分层很清楚：`MapCanvas` 负责"世界坐标怎么变成屏幕坐标"，这里负责"点了某个动作要做什么"。
 * 视图组件不再持有任何坐标换算——这是这一版重做的核心目的。</p>
 *
 * <p>数据是 `domain/sampleNodes.ts` 的示例节点（一段梯线），**不连服务端**。</p>
 */

const canvas = useTemplateRef<InstanceType<typeof MapCanvas>>("canvas");
const message = useMessage();

const nodes = ref<Node[]>(sampleNodes);

/** 当前摄像机（由画布上报），只用来显示读数。 */
const camera = ref<Camera>({originX: 0, originY: 0, scale: 1});
/** 取景基准比例（第一次取景时的比例），用来把"缩放"显示成倍率。 */
const baseScale = ref(0);

function onCamera(value: Camera) {
	camera.value = value;
	if (baseScale.value === 0 && value.scale > 0) {
		baseScale.value = value.scale;
	}
}

const zoomText = computed(() => {
	if (baseScale.value <= 0) {
		return "—";
	}
	return `${(camera.value.scale / baseScale.value).toFixed(2)}×`;
});

const detail = ref({open: false, title: "", text: ""});

function onAction({node, action}: {node: Node; action: string}) {
	switch (action) {
		case "center":
			canvas.value?.centerOnWorld(node.planeX, node.planeZ);
			break;
		case "copy":
			void navigator.clipboard?.writeText(node.coords);
			message.success(`已复制坐标 ${node.coords}`);
			break;
		case "neighbors":
			void navigator.clipboard?.writeText(node.neighbours.map(neighbour => neighbour.rail).join("\n"));
			message.success(`已复制 ${node.neighbours.length} 条相邻轨`);
			break;
		case "block":
			detail.value = {
				open: true,
				title: `节点 ${node.coords} · 所属区间`,
				text: [
					`区间 id：${node.block || "（无）"}`,
					`类型：${node.isUnguardedBlock ? "无灯区间（无人看守）" : "有灯区间"}`,
					"",
					"（尚未接入引擎：接入后这里显示 blocks " + node.key + " 的诊断输出。）",
				].join("\n"),
			};
			break;
		case "fork":
			detail.value = {
				open: true,
				title: `道岔 ${node.coords}`,
				text: [
					`度数 ${node.degree} · 相邻轨 ${node.neighbours.length} 条`,
					"",
					...node.neighbours.map((neighbour, index) =>
						`腿 ${index + 1}：${neighbour.x}, ${neighbour.y}, ${neighbour.z}　轨 ${Node.shortHex(neighbour.rail)}…　${Math.round(node.distanceTo(neighbour))} m`),
					"",
					"（尚未接入引擎：接入后这里显示该道岔的进向与各条腿的拓扑。）",
				].join("\n"),
			};
			break;
		default:
			break;
	}
}
</script>

<template>
	<div class="wrap">
		<MapCanvas
			ref="canvas"
			:nodes="nodes"
			@action="onAction"
			@camera="onCamera"
		/>

		<!-- HUD：节点数量 / 缩放读数 / 操作提示 / 重置视图 -->
		<div class="hud">
			<span class="group">节点 <b class="value">{{ nodes.length }}</b></span>
			<span class="group">缩放 <b class="value">{{ zoomText }}</b></span>
			<span class="tip">悬停看信息 · 左键开菜单 · 滚轮缩放 · 拖动平移</span>
			<button class="reset" type="button" @click="canvas?.fit()">重置视图</button>
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
	pointer-events: none;
}

.hud .group {
	display: flex;
	align-items: center;
	gap: 4px;
}

.hud b {
	color: var(--fg);
	font-family: var(--font-value);
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
	background: var(--panel);
	border: 1px solid var(--line);
	border-radius: var(--radius);
	cursor: pointer;
	pointer-events: auto;
}

.reset:hover {
	color: var(--fg);
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
