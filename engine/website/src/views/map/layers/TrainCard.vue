<script setup lang="ts">
/*
 * 车辆浮层：**点中一列车之后的任务卡片**。
 *
 * <p>用户 2026-09-16：「游戏内的列车都是通过任务驱动的，让列车图标能够点击，点击后高亮并提示
 * **任务目标**还有路线图叠加层。」—— 高亮在 `VehiclesLayer`，路线在 `TrainRouteLayer`，
 * 这个卡片只负责**把任务目标写清楚**：这列车被派去干什么、去哪个车场哪条股道（或哪个站的哪个站台）、
 * 从哪来、联锁给它排了哪条进路（立起来没有）。</p>
 *
 * <p>它由框架的 `#overlay` 插槽渲染 —— 在 SVG **外面**的 HTML 层里。原因见
 * `nodeInteraction.ts` 头部（图层那边在 SVG 命名空间里，写不出能显示的 HTML）。</p>
 *
 * <p>定位固定在**左上角**（不是跟着指针，也不是跟着车）：它是"我在看这列车"的持续状态。
 * 选中态在 `../trainSelection.ts`，卡片只读它。</p>
 *
 * <p>**只写用户要的那几行**（"不要加我要求以外任何元素"）：任务、目标、起点、进路。
 * 速度/车门/下一区间那些已经在记号的悬浮提示里了，不在这里重复。</p>
 */
import {computed} from "vue";
import {missionTarget} from "@/domain/MissionTarget";
import {describeMission, hasRoute, routeKindLabel, routeStateLabel, type Train} from "@/domain/Train";
import {useLiveFeeds} from "../liveFeeds";
import {selectTrain, selectedTrainId} from "../trainSelection";

const feeds = useLiveFeeds();

/** 点中的那列车（活数据每拍都在换，所以这里按 id 现查 —— 卡片跟着车走，不会显示旧对象）。 */
const train = computed<Train | null>(() => {
	const id = selectedTrainId.value;
	return id === null ? null : feeds.trains.value.find(candidate => candidate.vehicleId === id) ?? null;
});

/*
 * 任务目标 / 起点：那个 id 可能是**股道**也可能是**站台**（客运任务的目标就是站台，
 * 见 `domain/MissionTarget.ts`），所以两个清单都交给它查。
 */
const targetText = computed<string>(() => {
	const current = train.value;
	if (!current) {
		return "";
	}
	const resolved = missionTarget(current.targetSidingId, feeds.sidings.value, feeds.platforms.value);
	return current.destination === "" ? resolved.text : `${resolved.text} · ${current.destination}`;
});

/** 起点：作业单里那一步是从哪条股道开的（引擎给的一定是股道，但照同一个解析走，少一条特例）。 */
const startText = computed<string>(() => {
	const current = train.value;
	return current === null ? "" : missionTarget(current.startSidingId, feeds.sidings.value, feeds.platforms.value).text;
});

/** 进路一行：种类 · 状态 · 几根轨 / 几个岔（没有进路就写"没有进路"）。 */
const routeText = computed<string>(() => {
	const current = train.value;
	if (!current) {
		return "";
	}
	if (!hasRoute(current)) {
		return routeKindLabel("");
	}
	const parts = [routeKindLabel(current.routeKind), routeStateLabel(current.routeState), `${current.routeRailKeys.length} 根轨`];
	if (current.routeForkCount > 0) {
		parts.push(`${current.routeForkCount} 个岔`);
	}
	if (current.routeStateReason !== "" && current.routeState !== "SET") {
		parts.push(current.routeStateReason);
	}
	return parts.join(" · ");
});
</script>

<template>
	<div class="train-overlay">
		<div v-if="train" class="train-card">
			<!-- 标题就是车号（与记号的悬浮提示同一个写法）；全页的车号都很长，所以用等宽字体 -->
			<div class="card-head">
				车 {{ train.vehicleId }}
				<button type="button" class="card-close" title="取消选中" @click="selectTrain(null)">×</button>
			</div>
			<div class="card-rows">
				<div class="card-row">
					<span class="card-key">任务</span>
					<span class="card-value">{{ describeMission(train) || "没有任务" }}</span>
				</div>
				<div class="card-row">
					<span class="card-key">目标</span>
					<span class="card-value card-target">{{ targetText }}</span>
				</div>
				<div class="card-row">
					<span class="card-key">起点</span>
					<span class="card-value">{{ startText }}</span>
				</div>
				<div class="card-row">
					<span class="card-key">进路</span>
					<span class="card-value">{{ routeText }}</span>
				</div>
			</div>
		</div>
	</div>
</template>

<style scoped>
/* 与节点浮层同一套：容器铺满舞台但不吃事件，卡片自己再把事件打开 */
.train-overlay {
	position: absolute;
	inset: 0;
	overflow: hidden;
	pointer-events: none;
}

/*
 * 卡片：固定在**左上角**（不是跟着指针）。它是"我在看这列车"的持续状态，不是飘一下就走的提示 ——
 * 跟着指针会挡住刚点的那辆车、也会随鼠标乱跑。样式与节点菜单同一种"假玻璃"。
 */
.train-card {
	position: absolute;
	top: 12px;
	left: 12px;
	display: flex;
	flex-direction: column;
	min-width: 260px;
	max-width: 380px;
	background: var(--glass);
	border: 1px solid var(--hairline);
	border-radius: var(--radius);
	background-image: var(--glass-fog);
	box-shadow: 0 8px 24px rgba(0, 0, 0, 0.6);
	pointer-events: auto;
	user-select: none;
}

.card-head {
	display: flex;
	align-items: center;
	justify-content: space-between;
	padding: 6px 6px 6px 10px;
	border-bottom: 1px solid var(--hairline);
	font-family: var(--font-value);
	font-size: 12px;
	color: var(--fg);
}

.card-close {
	width: 20px;
	height: 20px;
	padding: 0;
	background: transparent;
	border: none;
	border-radius: var(--radius);
	color: var(--fg-dim);
	font-size: 14px;
	line-height: 1;
	cursor: pointer;
}

.card-close:hover {
	background: var(--panel-hover);
	color: var(--fg);
}

.card-rows {
	display: flex;
	flex-direction: column;
	gap: 4px;
	padding: 8px 10px;
}

.card-row {
	display: flex;
	gap: 8px;
	font-size: 12px;
	line-height: 1.5;
}

/* 键固定宽度，四行的值对齐成一列 —— 扫一眼就能看出"目标"那一行是什么 */
.card-key {
	flex: 0 0 32px;
	color: var(--fg-faint);
}

.card-value {
	flex: 1 1 auto;
	color: var(--fg-secondary);
	word-break: break-all;
}

/* 任务目标是这一张卡片的**主语**（用户："提示任务目标"），所以它比别的行亮一档 */
.card-target {
	color: var(--fg);
}
</style>
