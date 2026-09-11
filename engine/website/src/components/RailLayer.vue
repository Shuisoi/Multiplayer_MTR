<script setup lang="ts">
import {computed, watch} from "vue";
import {worldToScreen, type Camera} from "@/domain/camera";
import type {Rail} from "@/domain/Rail";
import {linePath, railCurvePath, type PlanePoint} from "@/domain/railGeometry";

/*
 * 轨道层：把引擎给的每条轨画出来（SVG，**屏幕坐标**）。
 *
 * <p><b>线型规则（用户要求）：x 或 z 任一相同 → 直线，其余 → 曲线。</b>
 * 判断用世界坐标（`Rail.isAxisAligned`），不用屏幕坐标——屏幕坐标会随缩放取整，
 * 缩小时两条斜向的轨可能因为取整而"看起来"轴对齐，线型就会随缩放跳变。</p>
 *
 * <p><b>曲线是简化版，但端点切线与真实轨道共线（用户要求）</b>：
 * 端点切线取自这条轨自己的采样点（`railCurvePath`），所以每一段在节点处的方向与真实走向一致；
 * 相接的两条轨在节点上的切线本来就几乎共线，于是节点处自然切线连续，不会折角。
 * 弯曲幅度由控制点长度封顶控制，不照搬真实几何的形态。</p>
 *
 * <p>SVG 故意不设 `viewBox`：它的用户单位默认就是 CSS 像素，所以这里可以直接写屏幕坐标，
 * 线宽也就是 1.6px，不会随缩放变粗变细（旧版在 viewBox + preserveAspectRatio + foreignObject
 * 三者之间对不齐而反复翻车，见 camera.ts）。</p>
 */

const props = defineProps<{
	rails: readonly Rail[];
	camera: Camera;
	/** 悬停节点的 key；与它相连的轨会加亮。 */
	hoverKey: string;
	/** 选中节点的 key；与它相连的轨画强调色。 */
	selectKey: string;
}>();

/** 限速 → 颜色（速度越高越亮）。高限速干线在暗底上自然浮起来。 */
function speedColor(speed: number): string {
	if (speed >= 300) {
		return "#8b9aab";
	}
	if (speed >= 200) {
		return "#75839a";
	}
	if (speed >= 160) {
		return "#616e80";
	}
	if (speed >= 80) {
		return "#4e585f";
	}
	return "#414a50";
}

/** 限速 → 线宽（屏幕像素）。 */
function speedWidth(speed: number): number {
	if (speed >= 300) {
		return 2.4;
	}
	if (speed >= 200) {
		return 2.1;
	}
	if (speed >= 160) {
		return 1.8;
	}
	if (speed >= 80) {
		return 1.5;
	}
	return 1.3;
}

/** 轨的第 n 个端点对应的节点 key（引擎的节点键就是 `x,y,z`）。 */
function endpointKey(rail: Rail, index: 1 | 2): string {
	return index === 1 ? `${rail.x1},${rail.y1},${rail.z1}` : `${rail.x2},${rail.y2},${rail.z2}`;
}

/**
 * 节点 → 该节点上"直线轨"的方向（世界平面坐标，单位向量）。
 *
 * <p>用户要求"曲线末端的切线要和上一段直线共线"，所以曲线接直线的那一端直接取这条直线的方向。
 * 只有直线轨参与：曲线轨的端点切向由它自己的采样点估（见 `railCurvePath` 的说明——
 * 用节点上所有轨的弦向做统一切向那条路实测是退步，会把曲线拉直）。</p>
 */
const straightDirections = computed(() => {
	const map = new Map<string, PlanePoint>();
	for (const rail of props.rails) {
		if (!rail.isAxisAligned) {
			continue;
		}
		const dx = rail.planeX2 - rail.planeX1;
		const dy = rail.planeY2 - rail.planeY1;
		const length = Math.hypot(dx, dy);
		if (!(length > 1e-9)) {
			continue;
		}
		const direction = {x: dx / length, y: dy / length};
		map.set(`${rail.x1},${rail.y1},${rail.z1}`, direction);
		// 同一节点上可能有多条直线轨（岔口），记第一条即可，`align` 会按本轨流向来对齐
		if (!map.has(`${rail.x2},${rail.y2},${rail.z2}`)) {
			map.set(`${rail.x2},${rail.y2},${rail.z2}`, direction);
		}
	}
	return map;
});

/** 一条轨画出来需要的全部信息（屏幕坐标 + 线型 + 样式）。 */
const drawn = computed(() => {
	const result: {
		hex: string;
		d: string;
		/** 这条轨**实际**画成了曲线（path 里带 C）。斜向轨也可能因为真实轨道共线而画成直线。 */
		isCurve: boolean;
		color: string;
		width: number;
		highlight: "none" | "hover" | "select";
	}[] = [];

	for (const rail of props.rails) {
		/*
		 * 线型（用户规则）：
		 *   · 同一轴（x 或 z 相同）→ 两端点直线；
		 *   · 斜向 → 简化的曲线，端点切线取自引擎采样的真实轨道（`railCurvePath`）。
		 *
		 * 曲线在**世界平面坐标**里算，最后才投影到屏幕：所有阈值（最小弯曲量、切线长度上限）
		 * 必须在缩放无关的尺度上判断，否则线型会随缩放变化（实测整图比例只有 ~0.1px/世界单位时，
		 * 93 条曲线有 74 条被"屏幕上看不出来"这个理由压成了直线）。
		 */
		const from = {x: rail.planeX1, y: rail.planeY1};
		const to = {x: rail.planeX2, y: rail.planeY2};
		const project = (point: PlanePoint) => worldToScreen(props.camera, point.x, point.y);
		const path = rail.isAxisAligned
			? linePath(project(from), project(to))
			: railCurvePath(
				from,
				to,
				rail.path.map(point => ({x: point.x, y: -point.z})),
				project,
				// 节点键直接用引擎的 `x,y,z`（不能从平面坐标反推，那样会丢掉 y 与符号）
				node => straightDirections.value.get(node) ?? null,
				[`${rail.x1},${rail.y1},${rail.z1}`, `${rail.x2},${rail.y2},${rail.z2}`],
			);
		if (path === "") {
			continue;
		}

		/*
		 * 高亮：与悬停/选中的节点相连的轨。轨的端点就是节点坐标，所以直接比 endpoint key——
		 * 不需要额外的邻接索引，134 条轨这个规模比字符串很快。
		 */
		let highlight: "none" | "hover" | "select" = "none";
		const startKey = endpointKey(rail, 1);
		const endKey = endpointKey(rail, 2);
		if (props.selectKey !== "" && (startKey === props.selectKey || endKey === props.selectKey)) {
			highlight = "select";
		} else if (props.hoverKey !== "" && (startKey === props.hoverKey || endKey === props.hoverKey)) {
			highlight = "hover";
		}

		result.push({
			hex: rail.hex,
			d: path,
			/*
			 * 线型的**实际**结果，不是"规则该怎么画"：斜向轨也可能因为真实轨道几乎共线而画成直线，
			 * 那时它就不是曲线。HUD 报的数字必须与页面上真正画出来的东西一致，
			 * 否则"读数 41、实际 40"这种差异会一直误导排查（实测被它带偏过一轮）。
			 */
			isCurve: path.includes("C"),
			color: speedColor(rail.speedLimitKmh),
			width: speedWidth(rail.speedLimitKmh),
			highlight,
		});
	}

	// 慢的轨先画、快的后画：交叉处"更重要的轨"压在上面，视觉层级自然。
	return result.sort((left, right) => left.width - right.width);
});

/** 实际画出来的直线 / 曲线条数，上报给 HUD。 */
const emit = defineEmits<{
	(e: "shapes", summary: {straight: number; curve: number}): void;
}>();

watch(drawn, items => {
	let straight = 0;
	let curve = 0;
	for (const item of items) {
		if (item.isCurve) {
			curve++;
		} else {
			straight++;
		}
	}
	emit("shapes", {straight, curve});
}, {immediate: true});
</script>

<template>
	<g class="rails">
		<!--
			每条轨画两遍：下面一条更宽的暗色做"护套"，让交叉与贴得很近的地方能看出是两条轨，
			上面那条才是带颜色的本体。只画一条的话，密集处会糊成一片。
		-->
		<path v-for="item in drawn" :key="`${item.hex}-shadow`" class="shadow" :d="item.d"/>
		<path
			v-for="item in drawn"
			:key="item.hex"
			class="rail"
			:class="item.highlight"
			:d="item.d"
			:stroke="item.highlight === 'none' ? item.color : undefined"
			:stroke-width="item.highlight === 'none' ? item.width : item.width + 0.6"
		/>
	</g>
</template>

<style scoped>
/*
 * 护套：比本体宽约 3px 的暗色描边。颜色用纯黑而不是半透明——底也是黑的，
 * 等于在两条轨之间"抠"出一条缝隙，比半透明更容易看清。
 */
.shadow {
	fill: none;
	stroke: #000000;
	stroke-width: 4.4;
	stroke-linecap: round;
}

.rail {
	fill: none;
	stroke-width: 1.6;
	stroke-linecap: round;
}

/* 与悬停节点相连的轨：加亮 */
.rail.hover {
	stroke: #c8c8c8;
}

/* 与选中节点相连的轨：强调色 */
.rail.select {
	stroke: var(--accent);
}
</style>
