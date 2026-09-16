<script setup lang="ts">
/*
 * 地图页**右侧的动作按钮条**（用户 2026-09-16："UI 右侧设计按钮，先设计一个解锁所有人工锁岔吧"）。
 *
 * <h2>它是什么、不是什么</h2>
 * <p>它是"**按一下要对世界做一件事**"的按钮集合：每个按钮对应引擎指令通道里的一条指令
 * （见 `api/topology.ts` 的 `runMmtrCommand`）。所以它既不是图层（不画东西），也不是读数面板
 * （不显示状态）—— 用户只说要按钮，那这里就只有按钮。</p>
 *
 * <p>挂在框架的 `#overlay` 插槽里（HTML 浮层，在 SVG 外面、不随相机缩放），
 * 因此它可以被面板自己接管事件（浮层容器整体是 `pointer-events: none`）。</p>
 *
 * <h2>为什么按钮不按"有没有锁"灰掉</h2>
 * <p>看起来能做的优化（用 `mmtr-points` 里的 `locked` 决定是否灰掉）**是错的**：人工扳一次岔会把
 * 一处道岔的**三条进向一起锁上**，而那个接口只列得出够得上岔口的进向 —— 现场实测引擎手里握着
 * **3 把**锁、接口里**只有 1 行**报 `locked`。按它灰掉，就会在"锁还在、但界面上看不出来"的时候
 * 把唯一的解药关掉，而那恰恰是这条指令存在的理由（见 `unlockAllPoints` 的注释）。
 * 所以按钮**一直可点**，点完由引擎自己回话："已解锁全部人工锁：清掉 N 把（含界面上没有对应进向的那些）"，
 * N = 0 时也会如实说出来。</p>
 */
import {ref} from "vue";
import {useMessage} from "naive-ui";
import {unlockAllPoints} from "@/api/command";
import {scanSignals} from "@/api/topology";

/** 正在下发（按钮置灰，避免连点）。 */
const busy = ref(false);
const message = useMessage();

/** 解锁所有人工锁岔：下发 `point unlock --all`，把引擎的回话原样显示出来。 */
async function unlockAllLocks(): Promise<void> {
	if (busy.value) {
		return;
	}
	busy.value = true;
	try {
		const result = await unlockAllPoints();
		const text = result.lines[0] ?? "已下发解锁指令";
		if (result.ok) {
			message.success(text);
		} else {
			message.error(text);
		}
	} catch (caught) {
		message.error(`解锁失败：${(caught as Error).message}`);
	} finally {
		busy.value = false;
	}
}

/**
 * 刷新信号灯登记表（手动刷新）。
 *
 * <h3>为什么要有这个按钮</h3>
 * <p>登记表（`mmtr-signals.json`）是引擎侧的数据，而"世界里到底还有没有这盏灯"只有游戏端答得出来
 * （只有游戏端能枚举区块里的方块实体）。用户实测的现场就是：**灯敲掉了，登记表没跟上** ——
 * 地图上一直画着一盏已经不存在的灯。</p>
 *
 * <p>自动刷新（游戏端常驻：区块加载 / 敲灯事件 / 每秒轮转）已经把这件事变成"世界改了登记表就跟着改"，
 * 但它是**按区块**收敛的 —— 没加载的区块不敢删（"找不到"只说明没加载）。所以"我确定这一片现在
 * 没有灯了"需要一个能一次把全部已加载区块核一遍的入口，这就是这个按钮。</p>
 *
 * <p>下发的是 `signals scan`（游戏端执行），`scanSignals()` 会轮询命令日志直到看见
 * `[signals] 扫描完成: …` 那行统计再回话 —— 所以按钮上的数字是**引擎自己报的**，不是猜的。</p>
 */
async function refreshSignals(): Promise<void> {
	if (busy.value) {
		return;
	}
	busy.value = true;
	try {
		const result = await scanSignals(15000);
		if (result) {
			message.success(`信号灯登记表已刷新：世界里 ${result.found} 盏，新增登记 ${result.added} 个（清掉几条看指令日志）`);
		} else {
			message.warning("扫描已下发，但没等到游戏端的统计回话（可能那一带的区块没加载）；指令日志里有详情");
		}
	} catch (caught) {
		message.error(`刷新失败：${(caught as Error).message}`);
	} finally {
		busy.value = false;
	}
}
</script>

<template>
	<div class="map-actions">
		<!--
			按钮的提示写清两个要点：**连界面上没有按钮的进向一起解**（这是它相对"逐行解锁"的唯一价值），
			以及**人工锁是干什么的**（锁着的道岔不许自动扳、自动进路排队等解锁）。
		-->
		<button
			type="button"
			class="action-button"
			:disabled="busy"
			title="解开引擎手里全部人工锁（含界面上没有对应进向的那些）。人工锁着的道岔不许自动扳、自动进路会排队等解锁。"
			@click="unlockAllLocks()"
		>
			解锁所有人工锁岔
		</button>

		<!--
			刷新信号灯登记表：一句"世界才是真的"的按钮。自动刷新已经在游戏端常驻，这个按钮是**手动的那一份**
			—— 手动扫描能把全部已加载区块一次核完，而自动刷新为了不误删远处没加载的灯，只敢按区块逐步收敛。
		-->
		<button
			type="button"
			class="action-button"
			:disabled="busy"
			title="让游戏端把世界里真实存在的信号灯与登记表核对一遍（世界是准的：灯敲掉了就销掉登记，新放的灯就补上）。自动刷新一直在跑，这里是一次核完全部已加载区块的手动入口。"
			@click="refreshSignals()"
		>
			刷新信号灯登记表
		</button>
	</div>
</template>

<style scoped>
/*
 * 位置：**右侧竖排、垂直居中**。右上角留给消息提示，左上角留给选中列车的任务卡片，
 * 所以动作条占右侧中段（地图的拖拽/缩放在它之外照常）。
 */
.map-actions {
	position: absolute;
	top: 50%;
	right: 12px;
	transform: translateY(-50%);
	display: flex;
	flex-direction: column;
	gap: 6px;
	/* 浮层容器整体不吃事件（地图的拖拽/缩放照常），按钮自己再把事件打开 */
	pointer-events: auto;
}

/*
 * 按钮：与节点菜单/任务卡片同一种"假玻璃"（深色实底 + 1px 发丝线 + 同一个小圆角）。
 * 宽度按内容撑开但齐平（同一列的两个按钮宽度一致），免得竖排看起来像一串长短不齐的标签。
 */
.action-button {
	min-width: 132px;
	padding: 7px 12px;
	background: var(--glass);
	border: 1px solid var(--hairline);
	border-radius: var(--radius);
	background-image: var(--glass-fog);
	box-shadow: 0 4px 12px rgba(0, 0, 0, 0.5);
	color: var(--fg-secondary);
	font-family: var(--font-ui);
	font-size: 12px;
	text-align: center;
	cursor: pointer;
	user-select: none;
}

.action-button:hover:enabled {
	background-color: var(--panel-hover);
	color: var(--fg);
}

.action-button:disabled {
	color: var(--fg-faint);
	cursor: default;
}
</style>
