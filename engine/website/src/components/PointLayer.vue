<script setup lang="ts">
import type {Camera} from "@/domain/camera";
import type {Point} from "@/domain/Point";
import PointMarker from "./PointMarker.vue";

/*
 * 道岔层：把引擎给出的道岔摆出来，并允许点开换开通位。
 *
 * <p>这一层**不做任何坐标运算**：相机由 `MapCanvas` 的 `.layer`（translate + scale）承担，
 * 标记的 `left/top` 直接写世界坐标。</p>
 *
 * <p>层序上这一层在信号灯层**之下**（见 MapCanvas 的模板顺序）：道岔菱形按固定像素偏移挂在节点
 * 右下方，那个距离本身就够到邻节点，所以难免会压到别人家的灯点 —— 让灯在上面，保证"灯永远点得到"。
 * 改绑定期间更进一步：`picking` 为真时道岔干脆不响应点击（那一刻用户要点的是灯与轨）。</p>
 */

defineProps<{
	points: readonly Point[];
	/** 当前相机：透传给标记。 */
	camera: Camera;
	/** 轨 hex → 两端坐标（道岔卡片写"接的是哪条轨"用，见 MapCanvas 的说明）。 */
	railEnds?: ReadonlyMap<string, {x1: number; z1: number; x2: number; z2: number}>;
	hoveredKey: string;
	/** 展开着腿按钮面板的那个道岔（点一下开、再点一下关）。 */
	expandedKey: string;
	/** 正在改信号灯绑定：这期间道岔不响应点击（见 PointMarker 的说明）。 */
	picking?: boolean;
	/** 当前选中的道岔（它的联通轨会在地图上被点亮，所以要有独立的高亮）。 */
	selectedKey?: string;
}>();

const emit = defineEmits<{
	(e: "hover", key: string): void;
	(e: "toggle", point: Point): void;
	(e: "pickLeg", payload: {point: Point; leg: number}): void;
}>();
</script>

<template>
	<PointMarker
		v-for="point in points"
		:key="point.key"
		:point="point"
		:camera="camera"
		:rail-ends="railEnds"
		:hovered="hoveredKey === point.key"
		:expanded="expandedKey === point.key"
		:selected="selectedKey === point.key"
		:picking="picking"
		@hover="emit('hover', $event)"
		@toggle="emit('toggle', $event)"
		@pick-leg="emit('pickLeg', $event)"
	/>
</template>

