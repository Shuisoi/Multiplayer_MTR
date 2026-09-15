<script setup lang="ts">
import {computed, ref, watch} from "vue";
import type {Camera} from "@/domain/camera";
import type {Rail} from "@/domain/Rail";
import {buildStraightDirections, railScreenPath, straightLookup} from "@/domain/railPath";

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
	/** 正在改绑定的那盏灯守的轨（hex）：画成"已绑定"色。 */
	boundRails?: readonly string[];
	/** 那盏灯**可以点**的候选轨（hex）：画成"可绑定"色，并且可点。 */
	candidateRails?: readonly string[];
	/**
	 * 选中的道岔**当前开通**那条腿的轨（hex）：画成"联通"色。
	 *
	 * <p>存在的理由：道岔的卡片上写着"开通 leg 0（直通）"，但"直通"在世界图里到底是哪条轨，
	 * 光看数字看不出来 —— 尤其复式交叉、多条腿走向相近时。把那条轨直接点亮，才谈得上"看懂"。</p>
	 */
	connectedRail?: string;
}>();

/** 轨道层对外的事件：点中某条轨（只有在有候选时才会发生），以及画出来的线型统计。 */
const emit = defineEmits<{
	(e: "pick-rail", payload: {hex: string; bound: boolean}): void;
	/** 实际画出来的直线 / 曲线条数，上报给 HUD。 */
	(e: "shapes", summary: {straight: number; curve: number}): void;
}>();

/** 悬停中的轨（hex）：候选轨上加一点反馈，让人知道"这条能点"。 */
const hoveredRail = ref("");

/** 这一层现在能不能点轨（有候选才有意义）。 */
const pickable = computed(() => props.candidateRails !== undefined && props.candidateRails.length > 0);

/**
 * 点选绑定：把"点中的轨"与"它当时画成什么状态"一起上报。
 *
 * <p><b>为什么把状态一起带上</b>：上层要判断这次点击是"绑"还是"解绑"，而它手里那份
 * `boundRails` 可能比画面旧一点（刚点完、数据刚重取的那一瞬间）—— 实测就因此把"解绑"
 * 下成了"再绑一次"（点了同一条轨，越点越多）。而**画面上这条线是实线（已守）还是虚线（候选）**
 * 是用户看到的事实，也是这一刻最可靠的依据：看到实线的人期待解绑，看到虚线的人期待绑上。</p>
 *
 * <p>顺手把最近几次点击（含路径 `d`）记到 {@code window.__mmtrPickedRails}：页面上的线看不出 hex，
 * 检查脚本靠它在绑定前后认出同一条线。</p>
 */
function onRailPick(hex: string, bound: boolean) {
	if (pickable.value && (props.candidateRails?.includes(hex) || props.boundRails?.includes(hex))) {
		const w = window as unknown as {__mmtrPickedRails?: {hex: string; d: string; bound: boolean}[]};
		const path = (document.querySelector(`.rails .rail[data-hex="${hex}"]`) as SVGPathElement | null)?.getAttribute("d") ?? "";
		w.__mmtrPickedRails = [...(w.__mmtrPickedRails ?? []), {hex, d: path, bound}].slice(-20);
		emit("pick-rail", {hex, bound});
	}
}

/*
 * 限速不再参与画法（用户 2026-09-15 定的规格：路线图 = 4 px 纯白线）。
 *
 * 旧版按限速分 5 个灰阶 + 5 档线宽。取消的理由：路线图要回答的是**结构**（有哪些轨、怎么连），
 * 而暗底上的 5 个相近灰阶既没把结构说清楚，又把"颜色"这个通道占住了 —— 颜色现在留给区间层与状态
 * （绑定绿、候选虚线、道岔琥珀、悬停/选中）。样式全部在 <style> 里按类给，不再走内联。
 */

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
const straightDirections = computed(() => buildStraightDirections(props.rails));

/** 一条轨画出来需要的全部信息（屏幕坐标 + 线型 + 状态）。样式（颜色/线宽）一律由 <style> 按类给。 */
const drawn = computed(() => {
	const result: {
		hex: string;
		d: string;
		/** 这条轨**实际**画成了曲线（path 里带 C）。斜向轨也可能因为真实轨道共线而画成直线。 */
		isCurve: boolean;
		highlight: "none" | "hover" | "select" | "bound" | "candidate" | "connected";
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
		// 整根轨的画法在 domain/railPath.ts：区间层截同一根轨的一段时走**同一个**函数，
		// 所以轨道线与它上面的区间带必然重合（用户 2026-09-15 的要求）。
		const path = railScreenPath(rail, props.camera, straightLookup(straightDirections.value));
		if (path === "") {
			continue;
		}

		/*
		 * 高亮：与悬停/选中的节点相连的轨。轨的端点就是节点坐标，所以直接比 endpoint key——
		 * 不需要额外的邻接索引，134 条轨这个规模比字符串很快。
		 */
		let highlight: "none" | "hover" | "select" | "bound" | "candidate" | "connected" = "none";
		const startKey = endpointKey(rail, 1);
		const endKey = endpointKey(rail, 2);
		if (props.connectedRail !== undefined && props.connectedRail !== "" && rail.hex === props.connectedRail) {
			// 选中道岔当前开通的那条轨：最高优先级。它是此刻用户唯一在问的问题
			// （"这个道岔现在把哪条轨接通了"），别的强调都可以让位。
			highlight = "connected";
		} else if (props.boundRails?.includes(rail.hex)) {
			// 这盏灯现在守的轨：最高优先级（它就是我们要看清楚的结论）
			highlight = "bound";
		} else if (props.candidateRails?.includes(rail.hex)) {
			// 可以点的候选轨
			highlight = "candidate";
		} else if (props.selectKey !== "" && (startKey === props.selectKey || endKey === props.selectKey)) {
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
			highlight,
		});
	}

	/*
	 * 画序：**有状态的轨后画**（压在纯白本体之上），因为它们的颜色/线宽是覆盖上去的，
	 * 被后面画的普通轨盖住就看不见了。同类之间保持稳定顺序（hex），避免每次重算都抖。
	 */
	return result.sort((left, right) => {
		const rank = (item: {highlight: string}) => (item.highlight === "none" ? 0 : 1);
		return rank(left) - rank(right) || left.hex.localeCompare(right.hex);
	});
});

/** 实际画出来的直线 / 曲线条数，上报给 HUD。 */
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
			:class="[item.highlight, {pickable: pickable && (item.highlight === 'candidate' || item.highlight === 'bound')}]"
			:data-hex="item.hex"
			:d="item.d"
			@pointerenter="hoveredRail = item.hex"
			@pointerleave="hoveredRail = ''"
			@pointerdown.stop="onRailPick(item.hex, item.highlight === 'bound')"
		/>
		<!--
			可点区域：候选轨**与已绑定的轨**上都叠一条透明但**很宽**的线，把细轨变成好点的目标
			（实测 1.6px 的线很难点中）。已绑定的轨也要能点 —— "再点一次解绑"这条路必须有命中区，
			否则解绑只能靠清空全部绑定。画在所有轨之后，所以它压在上面负责命中，可见样式不受影响。
		-->
		<path
			v-for="item in drawn.filter(entry => pickable && (entry.highlight === 'candidate' || entry.highlight === 'bound'))"
			:key="`${item.hex}-hit`"
			class="hit"
			:d="item.d"
			@pointerenter="hoveredRail = item.hex"
			@pointerleave="hoveredRail = ''"
			@pointerdown.stop="onRailPick(item.hex, item.highlight === 'bound')"
		/>
	</g>
</template>

<style scoped>
/*
 * ============================ 路线图（本层）的样式 ============================
 *
 * 用户 2026-09-15 定的规格：**路线图 = 4 px 纯白线**。
 *
 * <p>为什么用纯白而不是按限速分色：路线图回答的是"世界上有哪些轨、怎么连"，
 * 那是**结构**问题；限速分色（旧版的 5 个灰阶）在暗底上彼此只差一点，
 * 既没表达清楚结构，又把"颜色"这个通道占掉了 —— 而颜色现在要留给区间层与各种状态
 * （绑定绿、候选虚线、道岔琥珀、悬停/选中）。白线把结构画到最清楚，状态再在它上面叠。</p>
 *
 * <p>护套（shadow）宽度跟着本体一起定：本体 4 px，护套要比它宽出**足够的一圈**，
 * 否则相邻股道会连成一片。这里取 4 + 2×1.6 = 7.2 px（每侧留 1.6 px 的暗缝）。</p>
 */

/* 护套：比本体宽一圈的纯黑描边。底也是黑的，等于在两条轨之间"抠"出一条缝。 */
.shadow {
	fill: none;
	stroke: #000000;
	stroke-width: 7.2;
	stroke-linecap: round;
}

/* 本体：**4 px 纯白**（用户规格）。所有状态在下面按类覆盖。 */
.rail {
	fill: none;
	stroke: #ffffff;
	stroke-width: 4;
	stroke-linecap: round;
}

/* 与悬停节点相连的轨：略暗一点（白线已经最亮，只能往"不那么亮"调） */
.rail.hover {
	stroke: #c8c8c8;
}

/* 与选中节点相连的轨：强调色 */
.rail.select {
	stroke: var(--accent);
}

/*
 * 正在改绑定的那盏灯**已经守**的轨：绿色实线（与灯状态用的绿同一个色，读起来就是"这条归它管"）。
 */
.rail.bound {
	stroke: #22c55e;
	stroke-width: 4;
}

/*
 * 可以点的候选轨：虚线 + 强调色。虚线是刻意的——它表达"还没定，等你点"，
 * 而定下来之后变成 bound 的实线，一眼能区分"能点"和"已经在守"。
 */
.rail.candidate {
	stroke: var(--accent);
	stroke-width: 4;
	stroke-dasharray: 6 4;
}

/*
 * 选中道岔**当前开通**的那条腿：琥珀实线 + 发光。
 *
 * <p>用道岔自己的琥珀色（与菱形同色）而不是别的强调色：读起来就是"这条轨归那个道岔管"。
 * 发光（drop-shadow）是为了在密集站场里也能一眼找到。</p>
 */
.rail.connected {
	stroke: #f59e0b;
	stroke-width: 4;
	filter: drop-shadow(0 0 5px rgba(245, 158, 11, 0.85));
}

.rail.pickable {
	cursor: pointer;
}

/* 命中区：完全透明，只负责把细线变成好点的目标 */
.hit {
	fill: none;
	stroke: transparent;
	stroke-width: 12;
	/*
	 * 用 `all` 而不是 `stroke`：`stroke` 要求指针**正好落在描边覆盖的像素上**，
	 * 而命中区是透明的宽描边 —— 一像素的取整误差就会让点击落到空白（实测点不下去，
	 * elementFromPoint 命中了、事件却没到）。透明线没有可见的填充部分，所以 `all` 是等价的。
	 */
	pointer-events: all;
	cursor: pointer;
}
</style>
