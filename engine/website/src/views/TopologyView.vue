<script setup lang="ts">
import {computed, onMounted, ref, useTemplateRef} from "vue";
import {NModal, useMessage} from "naive-ui";
import MapCanvas from "@/components/MapCanvas.vue";
import {Node} from "@/domain/Node";
import {Rail} from "@/domain/Rail";
import {Signal} from "@/domain/Signal";
import {fetchSignals, fetchTopology} from "@/api/topology";
import type {Camera} from "@/domain/camera";

/*
 * 轨道层视图：把引擎里的**全部节点、轨与信号灯**显示出来，并处理节点操作菜单的动作。
 *
 * <p>分层：`MapCanvas` 负责"世界坐标怎么变成屏幕坐标"，这里负责取数与"点了某个动作要做什么"。
 * 视图组件不持有任何坐标换算——这是重做节点系统的核心目的。</p>
 *
 * <p>数据来自引擎的 `/mtr/api/map/mmtr-topology`（节点与轨）与 `/mtr/api/map/mmtr-signals`（信号灯）。
 * 连线按用户规则画：x 或 z 任一相同 → 直线，其余 → 曲线（见 `domain/railGeometry.ts`）。
 * 信号灯的状态（红/单黄/双黄/绿）由引擎的闭塞层给出，前端只显示，不重算。</p>
 *
 * <p>不做轮询：拓扑是"世界改了才会变"的东西，默认取一次 + 手动刷新。
 * 需要自动跟随的时候应当由服务端给出一个版本号，前端比版本号再决定要不要重取。</p>
 */

const canvas = useTemplateRef<InstanceType<typeof MapCanvas>>("canvas");
const message = useMessage();

const nodes = ref<Node[]>([]);
const rails = ref<Rail[]>([]);
const signals = ref<Signal[]>([]);
/** 取数状态：loading / ready / error，界面按它显示不同提示。 */
const status = ref<"loading" | "ready" | "error">("loading");
const errorText = ref("");

async function load() {
	status.value = "loading";
	errorText.value = "";
	try {
		// 两个 feed 一起取：它们描述同一个世界的两层，分两次 await 只会让画面先出现半份数据
		const [topology, lamps] = await Promise.all([fetchTopology(), fetchSignals()]);
		nodes.value = topology.nodes;
		rails.value = topology.rails;
		signals.value = lamps;
		status.value = "ready";
	} catch (error) {
		status.value = "error";
		errorText.value = error instanceof Error ? error.message : String(error);
	}
}

onMounted(load);

/** 信号灯状态统计（HUD 摘要）。 */
const signalCount = computed(() => {
	const counts = {red: 0, singleYellow: 0, doubleYellow: 0, green: 0, unknown: 0};
	for (const signal of signals.value) {
		counts[signal.state]++;
	}
	return counts;
});

/** 按度数统计：端点数 / 通过点数 / 道岔数，HUD 上给一句概览。 */
const degreeCount = computed(() => {
	let end = 0;
	let through = 0;
	let fork = 0;
	for (const node of nodes.value) {
		if (node.degree <= 1) {
			end++;
		} else if (node.degree === 2) {
			through++;
		} else {
			fork++;
		}
	}
	return {end, through, fork};
});

/**
 * 线型统计：**实际画出来的**直线 / 曲线条数，由轨道层上报。
 *
 * <p>不在这里按"x 或 z 相同"的规则重算：斜向轨也可能因为真实轨道几乎共线而画成直线，
 * 那时它不是曲线。两边各算一遍必然出现"HUD 41、页面 40"这种差异（实测被它带偏过一轮排查）。</p>
 */
const shapeCount = ref({straight: 0, curve: 0});

/**
 * 视图读数：缩放倍率由画布上报。
 *
 * <p>这里**只存不算**。倍率依赖"最后一次取景得到的比例"，那份状态归摄像机所有；
 * 上层自己再记一份基准的话，一旦在节点数据到达之前先取过一次景，就会 latch 到那次退化取景的比例，
 * 从此读数永远错（实测：画面正常，读数一直显示 0.02×）。</p>
 */
const zoomText = ref("—");

function onCamera(payload: {camera: Camera; zoom: number}) {
	zoomText.value = `${payload.zoom.toFixed(2)}×`;
}

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
					`区间 id：${node.block || "（引擎未给出）"}`,
					`类型：${node.isUnguardedBlock ? "无灯区间（无人看守）" : "有灯区间"}`,
					"",
					"（下一步接 blocks 诊断输出：届时这里显示该节点所属区间的出口信号灯与占用状态。）",
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
					"（下一步接道岔接口：届时这里显示进向与各条腿的拓扑。）",
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
			:rails="rails"
			:signals="signals"
			@action="onAction"
			@camera="onCamera"
			@shapes="shapeCount = $event"
		/>

		<!-- 取数状态：加载中 / 失败时给明确提示，不要让人对着空画布猜 -->
		<div v-if="status === 'loading'" class="banner">
			<span class="spinner"/><span>正在读取轨道层拓扑…</span>
		</div>
		<div v-else-if="status === 'error'" class="banner error">
			<span>读取失败：{{ errorText }}</span>
			<button class="action" type="button" @click="load">重试</button>
		</div>

		<!-- HUD：节点统计 / 缩放读数 / 操作提示 / 刷新与重置 -->
		<div class="hud">
			<span class="group">节点 <b class="value">{{ nodes.length }}</b></span>
			<span class="group">
				端点 <b class="value">{{ degreeCount.end }}</b>
				通过 <b class="value">{{ degreeCount.through }}</b>
				道岔 <b class="value">{{ degreeCount.fork }}</b>
			</span>
			<span class="group">轨 <b class="value">{{ rails.length }}</b><span class="note">画成 直线 {{ shapeCount.straight }} · 曲线 {{ shapeCount.curve }}</span></span>
			<!-- 信号灯：只统计"带灯的节点"，颜色按状态（与图中的箭头/灯点同一套颜色） -->
			<span class="group">
				信号灯 <b class="value">{{ signals.length }}</b>
				<span class="lamp-dot red"/><b class="value">{{ signalCount.red }}</b>
				<span class="lamp-dot single"/><b class="value">{{ signalCount.singleYellow }}</b>
				<span class="lamp-dot double"/><b class="value">{{ signalCount.doubleYellow }}</b>
				<span class="lamp-dot green"/><b class="value">{{ signalCount.green }}</b>
				<template v-if="signalCount.unknown > 0">
					<span class="lamp-dot unknown"/><b class="value">{{ signalCount.unknown }}</b>
				</template>
			</span>
			<span class="group">缩放 <b class="value">{{ zoomText }}</b></span>
			<span class="tip">悬停看信息 · 左键开菜单 · 滚轮缩放 · 拖动平移</span>
			<button class="action" type="button" @click="canvas?.focusSignals()">看信号灯</button>
			<button class="action" type="button" @click="load">重新读取</button>
			<button class="action" type="button" @click="canvas?.fit()">重置视图</button>
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

.banner {
	position: absolute;
	left: 50%;
	top: 50%;
	transform: translate(-50%, -50%);
	display: flex;
	align-items: center;
	gap: 10px;
	padding: 10px 16px;
	font-size: 13px;
	color: var(--fg-secondary);
	background: var(--panel);
	border: 1px solid var(--line);
	border-radius: var(--radius);
	box-shadow: 0 12px 32px rgba(0, 0, 0, 0.6);
}

.banner.error {
	color: var(--fg);
	border-color: var(--danger);
}

/* 加载指示：一个转圈的小弧，不加动画依赖 */
.spinner {
	width: 13px;
	height: 13px;
	border: 2px solid var(--line);
	border-top-color: var(--accent);
	border-radius: 50%;
	animation: spin 0.8s linear infinite;
}

@keyframes spin {
	to {
		transform: rotate(360deg);
	}
}

.hud {
	position: absolute;
	left: 12px;
	bottom: 10px;
	display: flex;
	flex-wrap: wrap;
	align-items: center;
	gap: 6px 14px;
	max-width: calc(100% - 24px);
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

.hud .note {
	color: var(--fg-faint);
}

.tip {
	color: var(--fg-faint);
}

/* HUD 里的状态小圆点：与图上的灯点同色，一眼能把数字对到颜色 */
.lamp-dot {
	width: 7px;
	height: 7px;
	border-radius: 50%;
	margin-left: 2px;
}

.lamp-dot.red {
	background: #ef4444;
}

.lamp-dot.single {
	background: #f59e0b;
}

.lamp-dot.double {
	background: #eab308;
}

.lamp-dot.green {
	background: #22c55e;
}

.lamp-dot.unknown {
	background: #6b7280;
}

.action {
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

.action:hover {
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
