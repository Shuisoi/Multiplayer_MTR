<script setup lang="ts">
/*
 * 信号灯图层：在**节点旁**显示信号灯图标（颜色 = 状态），并用 `^` 表示它**管辖的方向**。
 *
 * <h2>位置</h2>
 * <p>灯不画在它的世界坐标上，而是按"它相对节点的方位"挂到节点的**正上 / 正下 / 正左 / 正右**
 * （规则见 `domain/SignalPlacement.ts`）。同一个节点上多盏灯落在同一个槽时，沿同一方向依次外排
 * （实测当前世界不会撞：组合只有 右+左、上+下、单个，但人工绑定远端灯以后就可能撞）。</p>
 *
 * <h2>"挂在节点上"有两个坐标系，别混（2026-09-16 用户发现"灯左右分布不均匀"）</h2>
 * <p>节点坐标是**方块角**（整数），而轨的采样点与区间的 span 是**方块中心**（`n + 0.5`）——
 * 量的是同一个物理位置、写法差半格。所以要看**这一页的点画在哪一套**里：</p>
 * <ul>
 *   <li>「地图」页：节点圆点就画在**节点坐标**上、轨也对齐到了节点 ⇒ 用 {@code "node"}（默认）；</li>
 *   <li>区间地图：轨/带/端点都在**方块中心**（引擎的采样写法）⇒ 用 {@code "rail-sample"}，
 *       锚点整体搬 {@link railSampleShift}（**量出来的**偏移，实测 (0.5, 0.5) 格）。</li>
 * </ul>
 * <p>不搬会怎样（实测）：右边的灯离点 1.58 格、左边的灯 2.55 格 —— 看着就是"左右分布不均匀"；
 * 搬完 86/96 盏正好 2.000 格（剩下的是槽位本来就在 3 格外的灯与 1 盏孤儿灯）。</p>
 *
 * <h2>尺寸</h2>
 * <p>与其它图层一样写在**画布单位**里（屏幕大小 = 画布尺寸 × 相机比例），常量在本文件顶部。
 * 灯图标与轨道节点**等大**（连描边一起 1 格）；默认取景（1 格 = 0.625 px）下灯直径 1.25 px、
 * 离节点 2 格 = 2.5 px、`^` 高 0.6 格 = 0.38 px（张角 ≈ 90°）。</p>
 *
 * <h2>配对从哪来</h2>
 * <p>用 {@link fetchMapNodes} 的派生结果（引擎按"水平最近节点 + 5 格容差"配的，见 `domain/MapNode.ts`）。
 * 以后改成人工用工具绑定远端灯时，只要还是"一盏灯属于一个节点"，这一层不用改 ——
 * 它只用配对，不用配对是怎么来的。</p>
 */
import {computed} from "vue";
import type {SignalState} from "@/domain/Signal";
import {railSampleShift} from "@/domain/Rail";
import {SIGNAL_SLOT_DIRECTION, signalSlot, type SignalSlot} from "@/domain/SignalPlacement";
import {UNITS_PER_BLOCK, useMapContext} from "../mapContext";
import {useLiveFeeds} from "../liveFeeds";

/**
 * 锚点用哪个坐标系挂灯（见文件头）：
 * `"node"` = 「地图」页（点画在节点坐标上）；`"rail-sample"` = 区间地图（点画在方块中心）。
 */
const props = withDefaults(defineProps<{anchorFrame?: "node" | "rail-sample"}>(), {anchorFrame: "node"});

/**
 * 灯图标描边宽度（画布单位）：与轨道节点同一口径（1 格的 10%），把灯从底色和轨道线里分出来。
 */
const SIGNAL_LAMP_STROKE = UNITS_PER_BLOCK * 0.1;
/**
 * 灯图标半径（画布单位）：**与轨道节点等大**。
 *
 * <p>和 `RailNodesLayer` 的 `NODE_RADIUS` 同一个算法：`stroke` 以路径为中心两侧各画一半，
 * 所以要扣掉半个描边宽度，**连描边一起**正好 1 格 —— 两个圆的外径相同才是真的"等大"，
 * 只把半径写成一样会差一圈描边。</p>
 */
const SIGNAL_LAMP_RADIUS = (UNITS_PER_BLOCK - SIGNAL_LAMP_STROKE) / 2;
/** 图标离节点中心的距离（画布单位）：2 格（用户要求：与节点的距离缩到原来的一半）。 */
const SIGNAL_OFFSET = UNITS_PER_BLOCK * 2;
/**
 * `^` 的半宽与高度（画布单位）：高 **0.6 格**、半宽 **0.6 格**。
 *
 * <p>两个数一起决定**张角** = `2 × atan(半宽 / 高)`：半宽 = 高 ⇒ **90°**（前两版是 70°、48°）。
 * 用户 2026-09-16 的四次要求依次是"大小缩小"、"角度大一点"、"角度再大点"、"^缩小点" ——
 * 最后一次是**等比缩小**：两个数一起乘 0.6，张角保持 90° 不变。</p>
 */
const SIGNAL_ARROW_HALF_WIDTH = UNITS_PER_BLOCK * 0.6;
const SIGNAL_ARROW_HEIGHT = UNITS_PER_BLOCK * 0.6;
/** `^` 离图标外缘的间隙（画布单位）：0.25 格（用户要求"离点的距离近一点"）。 */
const SIGNAL_ARROW_GAP = UNITS_PER_BLOCK * 0.25;
/**
 * `^` 的描边宽度（画布单位）：0.15 格（= 高度的 25%，与箭头同比例）。
 *
 * <p>这里踩过一个坑：原来图省事让它跟灯图标共用 0.1 格的描边 —— 折线配 0.125 px 的线，
 * 默认取景下**细到等于没画**，看起来像"一个没渲染出来的符号"。指向符号本来就要比轮廓线粗；
 * 但它必须**跟着箭头一起缩**，不然小箭头上糊一根粗线就成了一坨。</p>
 */
const SIGNAL_ARROW_STROKE = UNITS_PER_BLOCK * 0.15;
/** `^` 中心离灯心的距离（画布单位）：贴着灯的外缘再留一道间隙。 */
const SIGNAL_ARROW_DISTANCE = SIGNAL_LAMP_RADIUS + SIGNAL_ARROW_GAP + SIGNAL_ARROW_HEIGHT / 2;
/**
 * 同一个槽里多盏灯时，每盏再往外让多少（画布单位）。
 *
 * <p>要按**整个图标朝外的跨度**算：灯心 → `^` 尖 = {@link SIGNAL_ARROW_DISTANCE} + 半个箭头高，
 * 再留出下一盏灯的半径与一道间隙。当前世界不会撞（实测组合只有 右+左、上+下、单个），
 * 但人工绑定远端灯以后可能撞，撞了就是两盏重叠成一盏、静默少画一盏。</p>
 */
const SIGNAL_SLOT_SPREAD = SIGNAL_ARROW_DISTANCE + SIGNAL_ARROW_HEIGHT / 2 + SIGNAL_LAMP_RADIUS + SIGNAL_ARROW_GAP;

/**
 * 状态 → 颜色。
 *
 * <p>颜色只做"一眼看出通不通"；精确说法在节点菜单的状态行里（`MapNode.stateText`，
 * 例如"绿灯（通行） / 红灯（停车）"）。单黄与双黄用同一个琥珀色的深浅区分 —— 两三个像素的图标上
 * 再细分颜色没有意义，要点进来才知道。</p>
 */
const SIGNAL_COLORS: Readonly<Record<SignalState, string>> = {
	red: "#ef4444",
	singleYellow: "#c8920c",
	doubleYellow: "#ffd400",
	green: "#22c55e",
	unknown: "#5f5f5f",
};

/** 画布里的一个灯：图标锚点（`x/y` = 它占据的那个方位槽）+ `^` 的朝向。 */
interface PlacedSignal {
	readonly key: string;
	readonly state: SignalState;
	/** **整个图标**的旋转角（度，顺时针，0 = 朝上）= 灯的管辖方向（`Signal.arrowRotation`）。 */
	readonly rotation: number;
	readonly x: number;
	readonly y: number;
}

const ctx = useMapContext();
/*
 * 数据来自页面的活数据 store（`../liveFeeds.ts`）：节点里的灯**每拍**都会换成最新的（显示随车变），
 * 所以这一层不需要自己的定时器 —— 它只是"把最新的灯摆到方位槽里"。
 */
const feeds = useLiveFeeds();
const mapNodes = feeds.mapNodes;
/** 这一页的轨（用来**量**采样坐标系相对节点坐标系的偏移，见 {@link railSampleShift}）。 */
const rails = feeds.rails;

/**
 * 锚点偏移（世界格）：{@code "rail-sample"} 时把节点坐标搬到**方块中心**那一套写法上。
 *
 * <p>「地图」页是 {@code "node"} ⇒ 恒为 (0, 0)，与改动前逐像素一致。</p>
 */
const anchorShift = computed<readonly [number, number]>(() =>
	props.anchorFrame === "rail-sample" ? railSampleShift(rails.value) : [0, 0]);

/** 所有带灯的节点上的灯，按方位槽摆好（读一次 `ctx.anchor` 以跟随锚点冻结后的重算）。 */
const placedSignals = computed<readonly PlacedSignal[]>(() => {
	ctx.anchor.value;
	const [shiftX, shiftZ] = anchorShift.value;
	const placed: PlacedSignal[] = [];
	for (const mapNode of mapNodes.value) {
		if (mapNode.signals.length === 0) {
			continue;
		}
		const [nodeX, nodeY] = ctx.project(mapNode.node.planeX + shiftX, mapNode.node.planeZ + shiftZ);
		/** 每个槽里已经放了几盏（同槽要依次外排，不然会重叠成一盏）。 */
		const usedPerSlot = new Map<SignalSlot, number>();
		for (const signal of mapNode.signals) {
			const slot = signalSlot(mapNode.node.planeX, mapNode.node.planeZ, signal.planeX, signal.planeY);
			const indexInSlot = usedPerSlot.get(slot) ?? 0;
			usedPerSlot.set(slot, indexInSlot + 1);
			const [dx, dy] = SIGNAL_SLOT_DIRECTION[slot];
			const distance = SIGNAL_OFFSET + indexInSlot * SIGNAL_SLOT_SPREAD;
			placed.push({
				key: signal.key,
				state: signal.state,
				rotation: signal.arrowRotation,
				x: nodeX + dx * distance,
				y: nodeY + dy * distance,
			});
		}
	}
	return placed;
});

/**
 * `^` 的形状：**局部坐标**里以灯心为原点、箭头在**正上方**（用户给的口径："以向上为例，`^` 在圆点的上方"）。
 *
 * <p>整个图标先在局部坐标里画好，再靠一个 `translate + rotate` 整体旋转 —— 不是逐元素按方向算位置。
 * 好处是"图标长什么样"只写一次，四个方向、以及以后任意角度都只是同一个图形转过去。</p>
 */
const ARROW_PATH = `M ${-SIGNAL_ARROW_HALF_WIDTH} ${-SIGNAL_ARROW_DISTANCE + SIGNAL_ARROW_HEIGHT / 2}`
	+ ` L 0 ${-SIGNAL_ARROW_DISTANCE - SIGNAL_ARROW_HEIGHT / 2}`
	+ ` L ${SIGNAL_ARROW_HALF_WIDTH} ${-SIGNAL_ARROW_DISTANCE + SIGNAL_ARROW_HEIGHT / 2}`;

function colorOf(state: SignalState): string {
	return SIGNAL_COLORS[state];
}

</script>
<template>
	<!--
		一盏灯 = **一个整体图形**：灯点 + 它正上方的 `^`（局部坐标里画好，见 ARROW_PATH）。
		整组只用一个 `translate(槽位) rotate(管辖方向)` —— 图形长什么样只定义一次，
		四个方向都只是同一个图形转过去，不是逐元素按方向算位置。
	-->
	<g
		v-for="signal in placedSignals"
		:key="signal.key"
		class="signal"
		:transform="`translate(${signal.x} ${signal.y}) rotate(${signal.rotation})`"
	>
		<circle
			class="signal-lamp"
			:r="SIGNAL_LAMP_RADIUS"
			:fill="colorOf(signal.state)"
			:stroke-width="SIGNAL_LAMP_STROKE"
		/>
		<path
			class="signal-arrow"
			:d="ARROW_PATH"
			:stroke="colorOf(signal.state)"
			:stroke-width="SIGNAL_ARROW_STROKE"
		/>
	</g>
</template>

<style scoped>
/* 图标：状态色填充 + 底色描边（把灯从轨道线和黑底里分出来）。 */
.signal-lamp {
	stroke: var(--bg);
}

/* `^`：只有描边，不要填充（它是指向符号，不是实心三角）。 */
.signal-arrow {
	fill: none;
	stroke-linejoin: round;
	stroke-linecap: round;
}
</style>
