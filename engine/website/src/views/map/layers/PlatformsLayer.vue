<script setup lang="ts">
/*
 * 区间地图图层：**站台**（用户 2026-09-16："先把站台读取出来吧，包括其方向和站名，站台号，
 * 然后绘制进地图中，用白色的大写I字型线段在站台侧表示站，然后字体排列方向与站的轨道平行"）。
 *
 * <h2>画什么</h2>
 * <ol>
 *   <li>**白色的方括号**（先是"大写 I"，后按用户要求削成括号）：一条沿站台轴的长杠 + 两端各一道
 *       **只朝外**伸的臂（靠轨道那一半的衬线不画 —— 用户："把靠轨道一边的I字衬线删除，类似[的造型"）。
 *       整体让到**站台外侧**（{@link platformSideDirection}：背离本站其他站台的那一侧），
 *       所以开口朝着轨、看着就是贴在轨旁边的一个站标。</li>
 *   <li>**站名 + 站台号**：两段文字，**旋转到与站台轴平行**（{@link uprightRotation} 保证能正着读）。
 *       站名在括号的外侧、沿站台居中；站台号在括号的一端外侧。</li>
 * </ol>
 * 除这两样之外不加东西（用户："不要加我要求以外任何元素"）。
 *
 * <h2>几何从哪来（这里有个必须避开的坑）</h2>
 * <p>站台的**范围**取那根轨在**这一页已经画出来的** `path`（按 `railHex` 从 `/mmtr-topology` 取），
 * 而不是引擎给的两端坐标：引擎的两端是**方块角**那一套，而这一页的轨/带/端点都在**方块中心**那一套，
 * 直接拿会整层差半格（就是用户刚发现的"信号灯左右分布不均匀"同一个坑）。取不到轨时才退回两端
 * （那一路要自己补 {@code railSampleShift} 的量，见 `TotalSectionsLayer` 的同类处理）。</p>
 *
 * <h2>尺寸（画布单位，1 格 = UNITS_PER_BLOCK）</h2>
 * <p>括号线宽 0.4 格、两端臂各 1.4 格长；站名 2.2 格高、站台号 1.8 格高；括号离轨心 **3 格**
 * （用户 2026-09-16 三次调整：1 格 → 2 格 → 3 格，现在与 1 格宽的带之间留 2 格空档），
 * 文字再往外让 0.7 格。屏幕大小 = 画布尺寸 × 相机比例。</p>
 */
import {computed, onMounted, ref} from "vue";
import {fetchPlatforms, fetchTopology} from "@/api/topology";
import {
	extentAxis,
	extentMidpoint,
	platformExtent,
	platformSideDirection,
	uprightRotation,
	type Platform,
} from "@/domain/Platform";
import {UNITS_PER_BLOCK, useMapContext} from "../mapContext";

/** 括号的线宽（画布单位）：0.4 格 —— 比轨（1 格）细，但缩到默认取景也看得见。 */
const I_STROKE = UNITS_PER_BLOCK * 0.4;
/**
 * 括号（原来是"大写 I"）离轨心的距离（画布单位）：**3 格**。
 *
 * <p>用户 2026-09-16 连着三次调它："在站台侧"（1 格）→"线段需要离远点"（2 格）→"再远一点"（3 格）。
 * 现在杠与 1 格宽的带之间留出 **2 格**空档。</p>
 */
const I_SIDE_OFFSET = UNITS_PER_BLOCK * 3;
/** 括号两端朝外的臂长（画布单位）：1.4 格。**只往外伸**（靠轨道那一半不画），见模板注释。 */
const I_CAP_LENGTH = UNITS_PER_BLOCK * 1.4;
/** 文字离主杠的距离（画布单位）：0.7 格（再往外一点，别压在杠上）。 */
const LABEL_GAP = UNITS_PER_BLOCK * 0.7;
/** 站名与站台号的字号（画布单位）。 */
const STATION_FONT_SIZE = UNITS_PER_BLOCK * 2.2;
const PLATFORM_FONT_SIZE = UNITS_PER_BLOCK * 1.8;
/** 站台号离 I 那一端的距离（画布单位）：1 格。 */
const NUMBER_GAP = UNITS_PER_BLOCK;

/** 画布里的一套站台图形。 */
interface Placed {
	readonly key: string;
	/** 方括号的一条折线（`d`）：外侧臂 → 主杠 → 外侧臂。 */
	readonly pathData: string;
	readonly stationName: string;
	readonly platformName: string;
	/** 站名锚点与旋转。 */
	readonly x: number;
	readonly y: number;
	readonly rotation: number;
	/** 站台号的锚点（括号的一端外侧，同一个旋转）。 */
	readonly numberX: number;
	readonly numberY: number;
}

const ctx = useMapContext();
const platforms = ref<readonly Platform[]>([]);
/** 轨 hex（规范形式）→ 采样点（世界坐标）。用来把站台铺在那根**已经画出来**的轨上。 */
const railPaths = ref<ReadonlyMap<string, readonly (readonly [number, number])[]>>(new Map());

const placed = computed<readonly Placed[]>(() => {
	ctx.anchor.value;
	const out: Placed[] = [];
	// 同一站的站台先分组：外侧方向要知道"本站其他站台在哪边"。
	// 只分**有站**的（引擎里存在没有归属站的站台 —— 它们彼此毫无关系，凑成一组会让"外侧"变成瞎猜）。
	const byStation = new Map<string, Platform[]>();
	for (const platform of platforms.value) {
		if (platform.stationId === "") {
			continue;
		}
		const list = byStation.get(platform.stationId) ?? [];
		list.push(platform);
		byStation.set(platform.stationId, list);
	}
	for (const platform of platforms.value) {
		const extent = platformExtent(platform, railPaths.value.get(platform.railHex) ?? null);
		if (extent.length < 2) {
			continue;
		}
		const axis = extentAxis(extent);
		const rotation = uprightRotation(axis[0], axis[1]);
		const mid = extentMidpoint(extent);
		// 外侧：从本站台中点指向本站**其他**站台中点的向量（外侧取它的反方向；没有其他站台时为 null → 固定一侧）
		const others = (byStation.get(platform.stationId) ?? []).filter(other => other !== platform);
		let towardOthers: readonly [number, number] | null = null;
		if (others.length > 0) {
			let sumX = 0;
			let sumZ = 0;
			for (const other of others) {
				const otherMid = extentMidpoint(platformExtent(other, railPaths.value.get(other.railHex) ?? null));
				sumX += otherMid[0] - mid[0];
				sumZ += otherMid[1] - mid[1];
			}
			towardOthers = [sumX / others.length, sumZ / others.length];
		}
		const side = platformSideDirection(axis, towardOthers);
		const [midX, midY] = ctx.project(mid[0], mid[1]);
		// 站台两端（沿轴方向），主杠整体让到外侧
		const first = extent[0]!;
		const last = extent[extent.length - 1]!;
		const offsetX = side[0] * I_SIDE_OFFSET;
		const offsetY = side[1] * I_SIDE_OFFSET;
		const [ax, ay] = ctx.project(first[0], first[1]);
		const [bx, by] = ctx.project(last[0], last[1]);
		const barA = [ax + offsetX, ay + offsetY] as const;
		const barB = [bx + offsetX, by + offsetY] as const;
		/*
		 * 衬线只留在**外侧**：主杠两端各往外伸一道 I_CAP_LENGTH 长的短杠，靠轨道那一半**不画**
		 * （用户 2026-09-16："把靠轨道一边的I字衬线删除，类似[的造型"）——
		 * 于是整体是一个「方括号」：杠 + 两条朝外的臂，开口朝着轨。
		 * 一条折线画完（臂 → 杠 → 臂），拐角用 miter 才是方角。
		 */
		const capX = side[0] * I_CAP_LENGTH;
		const capY = side[1] * I_CAP_LENGTH;
		const armA = [barA[0] + capX, barA[1] + capY] as const;
		const armB = [barB[0] + capX, barB[1] + capY] as const;
		const pathData = `M ${armA[0]} ${armA[1]} L ${barA[0]} ${barA[1]} L ${barB[0]} ${barB[1]} L ${armB[0]} ${armB[1]}`;
		// 站名：主杠中点再往外 LABEL_GAP
		const labelX = midX + side[0] * (I_SIDE_OFFSET + LABEL_GAP);
		const labelY = midY + side[1] * (I_SIDE_OFFSET + LABEL_GAP);
		// 站台号：主杠的**一端**外侧（沿轴往外 NUMBER_GAP），与站名同一条外侧线
		const numberX = barA[0] - axis[0] * NUMBER_GAP + side[0] * LABEL_GAP;
		const numberY = barA[1] - axis[1] * NUMBER_GAP + side[1] * LABEL_GAP;
		out.push({
			key: platform.platformId,
			pathData,
			stationName: platform.stationName,
			platformName: platform.platformName,
			x: labelX,
			y: labelY,
			rotation,
			numberX,
			numberY,
		});
	}
	return out;
});

async function load(): Promise<void> {
	try {
		const [feed, topology] = await Promise.all([fetchPlatforms(), fetchTopology()]);
		platforms.value = feed.platforms;
		const paths = new Map<string, readonly (readonly [number, number])[]>();
		for (const rail of topology.rails) {
			if (rail.path.length >= 2) {
				paths.set(rail.hex, rail.path.map(sample => [sample.x, sample.z] as const));
			}
		}
		railPaths.value = paths;
	} catch (caught) {
		// 页面上不摆任何提示（只留图形与控件）：失败落控制台。
		console.error("取站台失败", caught);
	}
}

onMounted(() => {
	void load();
});
</script>

<template>
	<g v-for="item in placed" :key="item.key" class="platform">
		<!--「方括号」= 一条主杠 + 两端各一道**只朝外**的臂（靠轨道那一半的衬线不画），整体让在站台外侧 -->
		<path class="platform-mark" :d="item.pathData" :stroke-width="I_STROKE"/>
		<!-- 站名与站台号：旋转到与站台轴平行；轴是两向的，所以角度已归一到"能正着读"的一半 -->
		<text
			class="platform-station"
			:x="item.x"
			:y="item.y"
			:transform="`rotate(${item.rotation} ${item.x} ${item.y})`"
			:font-size="STATION_FONT_SIZE"
		>{{ item.stationName }}</text>
		<text
			class="platform-number"
			:x="item.numberX"
			:y="item.numberY"
			:transform="`rotate(${item.rotation} ${item.numberX} ${item.numberY})`"
			:font-size="PLATFORM_FONT_SIZE"
		>{{ item.platformName }}</text>
	</g>
</template>

<style scoped>
/* 括号：只有描边的一条白线（不留填充）；拐角用 miter 才是方角 */
.platform-mark {
	fill: none;
	stroke: #ffffff;
	stroke-linecap: butt;
	stroke-linejoin: miter;
}

/* 站名与站台号：白色、居中在锚点上、沿轴排列；描边是同底色的黑，压在带色上才读得清
   （这不是多出来的元素，只是文字自己的衬底，见文件头"画什么"） */
.platform-station,
.platform-number {
	fill: #ffffff;
	font-family: var(--font-value);
	text-anchor: middle;
	dominant-baseline: middle;
	paint-order: stroke;
	stroke: var(--bg);
	stroke-width: 0.6;
	stroke-linejoin: round;
}
</style>
