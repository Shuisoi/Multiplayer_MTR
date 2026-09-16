<script setup lang="ts">
/*
 * 节点浮层：**悬停显示坐标** + **左键菜单**（扳岔动作 + 复制坐标）。
 *
 * <p>它由框架的 `#overlay` 插槽渲染 —— 在 SVG **外面**的 HTML 层里。原因见
 * `nodeInteraction.ts` 头部：图层那边在 SVG 命名空间里，写不出能显示的 HTML。</p>
 *
 * <p>菜单里两件事：道岔节点列出可点的扳岔动作（普通节点没有这一段），**复制坐标则每个节点都有**。</p>
 *
 * <p>定位一律用**指针的 client 坐标**（`position: fixed`），所以不需要知道相机状态；
 * 命中判定（"鼠标下面是哪个节点"）由图层做，状态从这里取。</p>
 */
import {computed} from "vue";
import {useMessage} from "naive-ui";
import {setPointBranch} from "@/api/topology";
import {railEndpointsText} from "@/domain/Point";
import {turnoutActions, type TurnoutAction} from "@/domain/MapNode";
import {busy, hovered, menu, menuActions, pointer, reloadNode} from "./nodeInteraction";

/** 菜单宽度（px）——同时用于 CSS、内联宽度与"别顶出窗口"的夹取，所以只有这一个数。 */
const MENU_WIDTH = 240;
/** 菜单最大高度（px），同上。 */
const MENU_MAX_HEIGHT = 300;

/**
 * 物理道岔的"根部"一行（没有就隐藏这一块）。
 *
 * <p>根部 = **两个位置都连通的那一侧**（列车开来的方向）。把它单独说出来，是因为用户看着地图问过
 * "为什么像是在另外两根轨之间切"—— 那两根里有一根其实是根部，它**永远不切换**。</p>
 */
const turnoutRails = computed<{stem: string} | null>(() => {
	const state = menu.value?.node.turnoutState ?? null;
	return state !== null && state.isTurnout ? {stem: railEndpointsText(state.stemHex)} : null;
});

/** 按钮上第二行：这个位置**接**的那根轨（坐标）。 */
function actionRail(action: TurnoutAction): string {
	const state = menu.value?.node.turnoutState ?? null;
	if (state === null || action.position === null) {
		return "";
	}
	return `接 ${railEndpointsText(action.position === 1 ? state.branchHex : state.farHex)}`;
}

/** 按钮提示：把"根部 / 接谁 / 切谁"整句写全（引擎侧 `point why` 同一套说法）。 */
function actionTooltip(action: TurnoutAction): string {
	const state = menu.value?.node.turnoutState ?? null;
	return state === null || action.position === null ? action.label : state.turnoutPositionText(action.position);
}

const message = useMessage();

/** 复制坐标到剪贴板。 */
async function copyCoords(): Promise<void> {
	const node = menu.value?.node;
	if (!node) {
		return;
	}
	const text = `${node.node.x}, ${node.node.y}, ${node.node.z}`;
	menu.value = null;
	try {
		await navigator.clipboard.writeText(text);
		message.success(`已复制坐标 ${text}`);
	} catch (caught) {
		message.error(`复制失败：${(caught as Error).message}`);
	}
}

/**
 * 手动扳岔：下发 `mmtr-point-op`，然后**回读**看真实位置。
 *
 * <p>为什么要回读：引擎那个接口只要 `via` 非空就回 `ok:true`，**不带拒绝原因**
 * （例如"车压在岔上不许扳"、腿与位置互斥）。所以"受理了"不等于"扳到位了" —— 只有重新取一次
 * `mmtr-points`、看这个动作是不是变成了"当前位"，才算真的扳过去了。</p>
 */
async function throwTurnout(action: TurnoutAction): Promise<void> {
	const node = menu.value?.node;
	if (!node) {
		return;
	}
	busy.value = true;
	menu.value = null;
	try {
		const row = node.turnouts.find(turnout => turnout.via === action.via);
		if (!row) {
			message.error("这一行已经不在了（世界改画了？），刷新页面再试");
			return;
		}
		await setPointBranch(row, action.leg);
		// 回读：重取一次数据，看这个动作是不是真的变成了"当前位"
		const after = await reloadNode(node.key);
		const landed = after !== null
			&& turnoutActions(after).some(item => item.via === action.via && item.leg === action.leg && item.current);
		if (landed) {
			message.success(`已扳到「${action.label}」`);
		} else {
			message.warning(`位置没有变 —— 引擎可能拒绝了（车压在岔上 / 腿与位置互斥）。当前：${after?.stateText ?? "节点不见了"}`);
		}
	} catch (caught) {
		message.error(`扳岔失败：${(caught as Error).message}`);
	} finally {
		busy.value = false;
	}
}
</script>

<template>
	<div class="node-overlay">
		<div
			v-if="hovered && menu === null"
			class="node-tooltip"
			:style="{left: `${pointer.x + 14}px`, top: `${pointer.y + 14}px`}"
		>
			{{ hovered.node.x }}, {{ hovered.node.y }}, {{ hovered.node.z }}
		</div>

		<div
			v-if="menu"
			class="node-menu"
			:style="{left: `${menu.x}px`, top: `${menu.y}px`, width: `${MENU_WIDTH}px`, maxHeight: `${MENU_MAX_HEIGHT}px`}"
		>
			<div class="menu-head">{{ menu.node.node.x }}, {{ menu.node.node.y }}, {{ menu.node.node.z }}</div>
			<div v-if="!menu.node.isPlain" class="menu-state">{{ menu.node.stateText }}</div>
			<!--
				物理道岔：把"两个位置各接哪两根轨"直接写在卡片上（用户 2026-09-16 现场问的正是这件事）。
				根部单独一行说清"它不参与切换"—— 根部那根轨永远通，切换的是另外两根。
			-->
			<div v-if="turnoutRails !== null" class="menu-rails">
				<div>根部 {{ turnoutRails.stem }}（列车开来的一侧，两个位置都通）</div>
				<div>现在切掉 {{ menu.node.turnoutState?.prohibitedText }}</div>
			</div>
			<div class="menu-body">
				<!--
					扳岔动作（只有道岔节点才有；普通节点这块是空的）。
					当前位那一项禁用并标出"当前"，免得点了半天发现没动。
				-->
				<button
					v-for="action in menuActions"
					:key="`${action.via}-${action.leg}`"
					type="button"
					:disabled="busy || action.current"
					:title="actionTooltip(action)"
					@click="throwTurnout(action)"
				>
					{{ action.label }}<span v-if="action.position !== null" class="menu-rail">{{ actionRail(action) }}</span><span v-if="action.current" class="menu-current">当前</span>
				</button>
				<!-- 复制坐标：**每个节点都有**（道岔节点也能复制，先列扳岔再列它） -->
				<button type="button" class="menu-copy" @click="copyCoords()">复制坐标</button>
			</div>
		</div>
	</div>
</template>

<style scoped>
/*
 * 浮层容器铺满舞台但不吃事件（pointer-events: none）：地图的拖拽/缩放照常。
 * 提示本来就不该挡事件；菜单自己再把事件打开。
 */
.node-overlay {
	position: absolute;
	inset: 0;
	overflow: hidden;
	pointer-events: none;
}

.node-tooltip {
	position: fixed;
	z-index: 10;
	padding: 3px 8px;
	background: var(--glass);
	border: 1px solid var(--hairline);
	border-radius: var(--radius);
	font-family: var(--font-value);
	font-size: 12px;
	color: var(--fg);
	user-select: none;
}

.node-menu {
	position: fixed;
	z-index: 11;
	overflow-y: auto;
	display: flex;
	flex-direction: column;
	background: var(--glass);
	border: 1px solid var(--hairline);
	border-radius: var(--radius);
	background-image: var(--glass-fog);
	box-shadow: 0 8px 24px rgba(0, 0, 0, 0.6);
	pointer-events: auto;
	user-select: none;
}

.menu-head {
	padding: 6px 10px;
	font-family: var(--font-value);
	font-size: 12px;
	color: var(--fg);
}

.menu-state {
	padding: 0 10px 6px;
	font-size: 12px;
	color: var(--fg-dim);
	border-bottom: 1px solid var(--hairline);
}

/*
 * 根部 / 现在切掉的那根：**紧凑两行、字号比状态行小**，它是"与图上的轨对上号"的信息，
 * 不是主状态（主状态还是"位置 0/1"那一行）。
 */
.menu-rails {
	padding: 4px 10px 6px;
	border-bottom: 1px solid var(--hairline);
	font-family: var(--font-value);
	font-size: 11px;
	line-height: 1.5;
	color: var(--fg-faint);
}

/* 按钮里的第二行：这个位置接的那根轨（比标签暗一档，不抢"位置 0/1"这个主语） */
.menu-rail {
	display: block;
	font-family: var(--font-value);
	font-size: 11px;
	color: var(--fg-faint);
}

.menu-body {
	display: flex;
	flex-direction: column;
	padding: 4px;
}

.menu-body button {
	padding: 6px 8px;
	background: transparent;
	border: none;
	border-radius: var(--radius);
	color: var(--fg-secondary);
	font-family: var(--font-ui);
	font-size: 12px;
	text-align: left;
	cursor: pointer;
}

.menu-body button:hover:enabled {
	background: var(--panel-hover);
	color: var(--fg);
}

.menu-body button:disabled {
	color: var(--fg-faint);
	cursor: default;
}

.menu-current {
	margin-left: 6px;
	color: var(--accent);
}

/*
 * 复制坐标排在扳岔动作后面，用一条发丝线隔开：上面是"改动世界"的操作，下面是"只是复制"。
 * 纯 CSS 的相邻兄弟选择器，不需要为它多加一个元素。
 */
.menu-body button + .menu-copy {
	margin-top: 4px;
	border-top: 1px solid var(--hairline);
	border-radius: 0 0 var(--radius) var(--radius);
	padding-top: 8px;
}
</style>
