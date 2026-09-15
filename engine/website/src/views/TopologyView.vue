<script setup lang="ts">
import {computed, nextTick, onMounted, onUnmounted, ref, useTemplateRef, watch} from "vue";
import {NModal, useMessage} from "naive-ui";
import MapCanvas from "@/components/MapCanvas.vue";
import CommandConsole from "@/components/CommandConsole.vue";
import {Node} from "@/domain/Node";
import {Rail} from "@/domain/Rail";
import {Signal} from "@/domain/Signal";
import {Point} from "@/domain/Point";
import type {Section} from "@/domain/Section";
import {copyText, describeEnvironment} from "@/domain/clipboard";
import {commandCoords, readableCoords} from "@/domain/coords";
import {sendToConsole} from "@/domain/consoleBridge";
import {fetchPoints, fetchSections, fetchSignals, fetchTopology, scanSignals, setPointBranch} from "@/api/topology";
import {speedBandColor} from "@/domain/railColors";
import {buildStraightDirections, straightLookup} from "@/domain/railPath";
import {toggleSignalRail} from "@/api/commands";
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
 * <p>取数：打开页面时读一次（拓扑 + 灯 + 道岔），之后由用户手动「重新读取」。
 * 需要"坐在屏幕前看车走、灯跟着变"的时候打开 HUD 上的**自动刷新**开关（默认关，
 * 见下方 `AUTO_REFRESH_MILLIS`）：它只按拍重读灯与道岔这两份会变的小数据。
 * 拓扑本身是"世界改了才会变"的东西，不跟着轮询；真要自动跟随也应当由服务端给版本号，
 * 前端比版本号再决定要不要重取。</p>
 */

const canvas = useTemplateRef<InstanceType<typeof MapCanvas>>("canvas");
const message = useMessage();

const nodes = ref<Node[]>([]);
const rails = ref<Rail[]>([]);
const signals = ref<Signal[]>([]);
/**
 * 道岔（引擎里腿数 ≥ 2 的节点）。
 *
 * <p>道岔开通位是**要人管**的状态：闭塞层靠它决定"道岔后面那一段是哪一段"，从而决定灯的显示。
 * 所以这一层不是"可选的调试信息"，是操作入口，必须与节点/轨/灯一起取。
 */
const points = ref<Point[]>([]);
/**
 * 区间层（按方向划分的区间，notes/156）。
 *
 * <p>与节点/轨/灯/道岔一起取：区间层画的是"每盏灯开的那段路 + 它属于哪个方向"，而它是由**灯**推出来的，
 * 所以灯的登记表一变，区间就会跟着变 —— 分两次取会让画面出现"灯新、区间旧"的半份状态。</p>
 */
const sections = ref<Section[]>([]);
/**
 * 区间图层要不要画（默认**画**，用户 2026-09-15 要求加一个显示/隐藏按钮）。
 *
 * <p>隐藏时传空数组给画布，而不是在组件里判一个 `visible` 标志：不画就是不画，
 * 省掉整轮投影与 DOM 节点（实测 101 个区间 → 322 条 path）。</p>
 */
const showSections = ref(true);
/** 取数状态：loading / ready / error，界面按它显示不同提示。 */
const status = ref<"loading" | "ready" | "error">("loading");
const errorText = ref("");

/**
 * 节点 → 覆盖它的区间（**可能多个**）。
 *
 * <p>引擎不再给"节点归属"了（区间是某方向的一段路，一个节点被两个方向的区间同时覆盖是常态，
 * 现场实测被覆盖的 96 根轨里 62 根是多归属）。所以这里由 **spans 推**：节点是轨的端点，
 * 所以"某区间的某一段落在某根轨上、且弧窗贴到端点"就意味着那个区间覆盖这个节点。</p>
 */
const sectionIndex = computed(() => {
	// 区间 → 它覆盖的轨（hex → 该区间在这些轨上的那些段）
	const spansByRail = new Map<string, {section: string; points: readonly number[]}[]>();
	for (const section of sections.value) {
		for (const span of section.spans) {
			const list = spansByRail.get(span.hex);
			const entry = {section: section.id, points: span.points};
			if (list === undefined) {
				spansByRail.set(span.hex, [entry]);
			} else {
				list.push(entry);
			}
		}
	}
	/*
	 * 判据与引擎"灯贴着节点就算守这个节点"同一条口径：**空间容差**，不比弧长。
	 *
	 * <p>为什么要空间而不是弧：网页拿不到轨的弧长（`Rail` 只给两端坐标与采样点），而引擎本身就是
	 * 按"灯与节点的距离 ≤ 4 m"判的。这里用区间那一段的**采样点**里离节点最近的一个点来判，
	 * 阈值取 3 m（引擎的 4 m 减去端点取整的余量）。</p>
	 */
	const NODE_TOLERANCE_M = 3;
	const index = new Map<string, string[]>();
	for (const node of nodes.value) {
		const found: string[] = [];
		for (const neighbour of node.neighbours) {
			for (const span of spansByRail.get(neighbour.rail) ?? []) {
				for (let i = 0; i + 1 < span.points.length; i += 2) {
					if (Math.hypot(span.points[i]! - node.x, span.points[i + 1]! - node.z) <= NODE_TOLERANCE_M) {
						if (!found.includes(span.section)) {
							found.push(span.section);
						}
						break;
					}
				}
			}
		}
		found.sort();
		index.set(node.key, found);
	}
	return index;
});

async function load() {
	status.value = "loading";
	errorText.value = "";
	try {
		// 四个 feed 一起取：它们描述同一个世界的四层（轨/节点、灯、道岔、区间），分开 await 只会让画面出现半份数据
		const [topology, lamps, switches, sectionFeed] = await Promise.all([
			fetchTopology(),
			fetchSignals(),
			fetchPoints(),
			fetchSections(),
		]);
		nodes.value = topology.nodes;
		rails.value = topology.rails;
		signals.value = lamps;
		points.value = switches;
		sections.value = sectionFeed.sections;
		status.value = "ready";
	} catch (error) {
		status.value = "error";
		errorText.value = error instanceof Error ? error.message : String(error);
	}
}

/**
 * 读取 + 自动扫描信号灯。
 *
 * <p>信号灯**要先被登记**才会出现在接口里（登记表 = 世界目录下的 `mmtr-signals.json`），
 * 而世界里放着的 MTR 信号灯块未必都已登记：实测世界里扫出 37 个，登记表当时只有 31 个，
 * 也就是控制台会少显示 6 个灯——用户反馈的"显示不全"就是这个。</p>
 *
 * <p>所以每次读取都顺手让游戏端扫一次（`signals scan`，它把已加载区块里的信号灯块登记进去）。
 * 只有**扫出了新增条目**才重新取一遍数据，所以正常情况下这只是一次额外的确认往返，不会抖动。
 * 扫描是幂等的：已经登记过的不会重复加，BOUND 的条目不会被覆盖。</p>
 */
const scanState = ref<"idle" | "scanning">("idle");
/** 上一次扫描的结果（HUD 上给一句实情，而不是让用户猜"到底登记全没全"）。 */
const lastScan = ref("");

/*
 * **自动刷新（默认关）**：用户 2026-09-14 的现场问题——"车开出去以后灯为什么还是绿的"。
 *
 * <p>根因不是引擎：这个页面**从来不轮询**（全站没有 setInterval / SSE / WebSocket），
 * `load()` 只在打开页面与用户操作之后各读一次。所以页面上的 aspect 是"上次读取那一刻"的快照，
 * 引擎早就逐 tick 更新过了 —— 实测：车头 `(-169.5,-198.5)` 已经在区间里、引擎报红，
 * 而页面那一帧还是绿的。</p>
 *
 * <p>约定：**默认关**（保持既有行为不变，也不平白多打接口），开关状态记在 localStorage；
 * 打开后每 {@link AUTO_REFRESH_MILLIS} 只重读**信号灯与道岔**这两份小数据（不重取拓扑、
 * 不重建轨图），所以地图不会闪、相机也不会动。</p>
 */
const AUTO_REFRESH_MILLIS = 3000;
const AUTO_REFRESH_STORAGE_KEY = "mmtr.topology.autoRefresh";
const autoRefresh = ref(false);
let autoRefreshTimer: number | null = null;

/** 轻量重读：只更新"会变的那两份"（aspect / 道岔位置），失败就悄悄跳过（下一拍再试）。 */
async function refreshLive() {
	try {
		const [lamps, switches] = await Promise.all([fetchSignals(), fetchPoints()]);
		signals.value = lamps;
		points.value = switches;
	} catch {
		// 一次没读到不算错误：轮询本来就会再来一次，报错弹窗只会打扰人
	}
}

function stopAutoRefresh() {
	if (autoRefreshTimer !== null) {
		window.clearInterval(autoRefreshTimer);
		autoRefreshTimer = null;
	}
}

function startAutoRefresh() {
	stopAutoRefresh();
	autoRefreshTimer = window.setInterval(refreshLive, AUTO_REFRESH_MILLIS);
	void refreshLive();
}

function setAutoRefresh(on: boolean) {
	autoRefresh.value = on;
	try {
		window.localStorage.setItem(AUTO_REFRESH_STORAGE_KEY, on ? "1" : "0");
	} catch {
		// 隐私模式下 localStorage 可能被拒：开关照样生效，只是记不住
	}
	if (on) {
		startAutoRefresh();
	} else {
		stopAutoRefresh();
	}
}

function toggleAutoRefresh() {
	setAutoRefresh(!autoRefresh.value);
}

async function loadWithScan() {
	await load();
	scanState.value = "scanning";
	try {
		const scan = await scanSignals();
		if (scan) {
			lastScan.value = `扫到 ${scan.found} 个信号灯${scan.added > 0 ? `，补登记 ${scan.added} 个` : "（已全部登记）"}`;
			if (scan.added > 0) {
				await load();
			}
		} else {
			// 超时/没读到统计：不当成错误，只是没有这条信息
			lastScan.value = "";
		}
	} catch (error) {
		lastScan.value = `扫描失败：${error instanceof Error ? error.message : String(error)}`;
	} finally {
		scanState.value = "idle";
	}
}

/**
 * 记住上次的开关状态：默认关。
 *
 * <p>只读 `"1"` 才算开——localStorage 里可能是别的页面写的脏值（或用户手改过），
 * 这里不猜语义，读不出"1"就当关。</p>
 *
 * <p>顺序上放在首次读取**之后**：恢复成"开"会立刻多读一拍（灯 + 道岔），
 * 刚 `load()` 完再抓一次是白抓，等首屏数据落地再起表。</p>
 */
function restoreAutoRefreshPreference() {
	let stored: string | null = null;
	try {
		stored = window.localStorage.getItem(AUTO_REFRESH_STORAGE_KEY);
	} catch {
		// 隐私模式下读不到：按默认关处理
	}
	if (stored === "1") {
		setAutoRefresh(true);
	}
}

onMounted(async () => {
	await loadWithScan();
	restoreAutoRefreshPreference();
});

// 离开这个视图必须停表：定时器不属于组件生命周期，不显式停就会在后台一直打接口
onUnmounted(stopAutoRefresh);

/**
 * 画在地图上的道岔标记：**一处物理道岔只画一个**。
 *
 * <p>引擎的 `mmtr-points` 是**按进向**给的：一个单开道岔在那个节点上有三行（每个进向一行），
 * 因为"从这一行看能去哪些轨"是逐进向的问题。可物理上只有**一处道岔、一个位置**。直接把三行都画出来，
 * 用户看到的就是"同一个节点上三个道岔，各自去向不同" —— 其中"从岔股进来"那一行还会列出**正线远端**
 * 那条腿，看起来像"能从 -35,-157 直接开到 -67,-167"（实测用户就是这么读的）。
 * 现实里那是背向穿过尖轨，物理上不存在，引擎也会拒绝。</p>
 *
 * <p>所以单开道岔只留**根部那一行**（唯一能表达两个位置的进向）来画，卡片上用「位置 0/1 + 两条连通轨」
 * 说话；没有物理道岔模型的老式岔口照旧一行一个（它们的 0/1 本来就是逐进向的）。</p>
 */
const displayPoints = computed(() => {
	const byNode = new Map<string, Point[]>();
	for (const point of points.value) {
		const list = byNode.get(point.key);
		if (list === undefined) {
			byNode.set(point.key, [point]);
		} else {
			list.push(point);
		}
	}
	const out: Point[] = [];
	for (const list of byNode.values()) {
		if (!list.some(point => point.isTurnout)) {
			out.push(...list);
			continue;
		}
		out.push(list.find(point => point.isStemRow) ?? list[0]!);
	}
	return out;
});

/**
 * 交给画布的节点：**带上"覆盖它的区间"**。
 *
 * <p>为什么要在这里重建实体：区间索引是从 `mmtr-sections` 推出来的，而节点是在 `fetchTopology()`
 * 里造的（那时还没有区间数据）。重建一次（137 个节点）比让 `Node` 变成可变对象干净得多 ——
 * 实体一旦可变，"这份节点的区间是哪一次取的"就说不清了。</p>
 */
const displayNodes = computed(() => {
	const index = sectionIndex.value;
	return nodes.value.map(node => new Node({
		x: node.x,
		y: node.y,
		z: node.z,
		degree: node.degree,
		neighbors: node.neighbours.map(neighbour => ({x: neighbour.x, y: neighbour.y, z: neighbour.z, rail: neighbour.rail})),
	}, index));
});

/**
 * 轨 hex → **轨道线的颜色**（限速分档，与轨道层同一份约定）。
 *
 * <p>区间带用这个颜色，而不是自己一套"按方向配色"（用户 2026-09-15 的要求）：
 * 区间是轨上的一段弧窗，画出来必须与那段轨同色。</p>
 */
const railColorByHex = computed(() => {
	const map = new Map<string, string>();
	for (const rail of rails.value) {
		// 两个方向的限速取较大者：轨道层画的是这一条轨，颜色该由它自己的限速定
		map.set(rail.hex, speedBandColor(Math.max(rail.speedLimitKmh1, rail.speedLimitKmh2)));
	}
	return map;
});

/** 轨 hex → 轨实体（区间带按**网页画轨道线的同一套几何**切片时要用）。 */
const railByHex = computed(() => {
	const map = new Map<string, Rail>();
	for (const rail of rails.value) {
		map.set(rail.hex, rail);
	}
	return map;
});

/**
 * "该节点上的直线轨方向"查表 —— 与**轨道层同一份**（曲线端点切线靠它才能与相邻直线轨共线）。
 * 同时交给 MapCanvas：轨道层与区间层必须查同一张表，否则带子会在节点处与轨线错开。
 */
const straightDirections = computed(() => buildStraightDirections(rails.value));
const straightForCurves = computed(() => straightLookup(straightDirections.value));

/** 轨 hex → 两端坐标（画"这一位接的是哪条轨"用：用户读坐标，不读 hex）。 */
const railEndsByHex = computed(() => {
	const map = new Map<string, {x1: number; z1: number; x2: number; z2: number}>();
	for (const rail of rails.value) {
		map.set(rail.hex, {x1: rail.x1, z1: rail.z1, x2: rail.x2, z2: rail.z2});
	}
	return map;
});

/**
 * 道岔统计（HUD 摘要）。
 *
 * <p>"已设定 / 未设定"必须分开数：未设定表示引擎按 **0（直通）** 走，已设定表示有人定过位。
 * 合成一个数字就看不出"这些道岔到底有没有人管过"——而那正是这一层存在的理由。</p>
 */
const pointCount = computed(() => {
	let set = 0;
	let unset = 0;
	let locked = 0;
	for (const point of displayPoints.value) {
		if (point.isTurnout) {
			// 物理道岔：位置是落盘的（默认 0 = 正线贯通），所以它永远"有位"
			set++;
		} else if (point.isManuallySet) {
			set++;
		} else {
			unset++;
		}
		if (point.locked) {
			locked++;
		}
	}
	return {set, unset, locked};
});

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
/** 弹窗正文：剪贴板兜底时要把内容全选好，用户按 Ctrl+C 就行 */
const detailBox = useTemplateRef<HTMLTextAreaElement>("detailBox");

// 弹窗打开后把正文全选：这是"复制失败"时唯一的出路，不该再要求用户去拖选
watch(() => detail.value.open, async open => {
	if (!open) {
		return;
	}
	await nextTick();
	detailBox.value?.focus();
	detailBox.value?.select();
});

/**
 * 正在改绑定的那盏灯的 **key**（点选绑定）。
 *
 * <p>存 key、再看 `signals` 实时查，而不是存 Signal 对象：每次绑定后都会重取数据，
 * 重取后是**新对象**，攥着旧对象会让 HUD 与高亮都停在绑定前的状态
 * （实测：绑定成功、引擎已是 2 条，页面还显示 1 条）。</p>
 *
 * <p>绑定的**语义**在这一层：点一条轨是"绑上"还是"解绑"，由"这盏灯现在守不守它"决定 ——
 * 画布只上报"点了哪条轨"，不替这里做决定。</p>
 */
const bindingKey = ref("");
const bindingSignal = computed(() => signals.value.find(item => item.key === bindingKey.value) ?? null);

/**
 * 选中的道岔（点菱形时选中）。
 *
 * <p>存 key 而不是对象：改开通位后会重取数据，`points` 里是新对象，攥着旧对象 HUD 就停在旧位
 * （和信号灯绑定那一处同一个坑）。这里也用它显示"当前联通哪条腿"。</p>
 */
const selectedPointKey = ref("");
const selectedPoint = computed(() => points.value.find(item => item.key === selectedPointKey.value) ?? null);

function onSelectPoint(point: Point | null) {
	selectedPointKey.value = point?.key ?? "";
}

function onSelectSignal(signal: Signal | null) {
	bindingKey.value = signal?.key ?? "";
}

/**
 * 点了一条轨：已经在守 → 解绑；没在守 → 绑上。
 *
 * <p>**一次点击 = 一条指令**，且指令是"加一条 / 减一条"而不是"整表替换"（见 `toggleSignalRail`）：
 * 整表替换要依赖本地那份列表是最新的，而刚点完、数据刚重取的那一瞬间它会过期 ——
 * 实测就出现过"点同一条越绑越多、解绑不生效"。增删由引擎按自己当前的事实执行，界面不必持有真相。</p>
 */
async function onPickRail({signal, railHex}: {signal: Signal; railHex: string; bound: boolean}) {
	/*
	 * "这次是绑还是解绑"**不在这里判断**：交给引擎（`--toggle`）。
	 *
	 * <p>界面手里那份 `boundRails` 可能比画面旧一瞬（刚点完、数据刚重取），据此判断会把解绑
	 * 下成"再绑一次" —— 实测同一条轨越点越多，而画面上完全看不出来。引擎知道自己现在守什么，
	 * 让它按事实取反，这条交互就不可能自相矛盾。</p>
	 */
	(window as unknown as {__mmtrBindDecisions?: unknown[]}).__mmtrBindDecisions =
		((window as unknown as {__mmtrBindDecisions?: unknown[]}).__mmtrBindDecisions ?? []).concat([{
			hex: railHex.slice(0, 12), held: signal.boundRails.length,
		}]);
	try {
		const result = await toggleSignalRail(signal, railHex);
		if (!result.ok) {
			message.error(result.lines[0] ?? "绑定失败");
			return;
		}
		// 回复里带引擎的结论（绑上还是解绑、现在守几条），直接说给用户听，不自己猜
		message.success(result.lines[0] ?? "已更新绑定");
		await load();
	} catch (error) {
		message.error(error instanceof Error ? error.message : String(error));
	}
}

/**
 * 把某个道岔扳到第 {@code leg} 条腿。
 *
 * <p>**不本地改状态、只等引擎回话**：开通位存在引擎里（`mmtrSetPoint` 写 `mmtr-points.json`），
 * 本地先改会出现"界面显示已扳、列车其实还走旧位"这种最难查的分叉。所以这里是
 * "下指令 → 按引擎的 ok 说话 → 重取数据"三步，界面永远显示引擎的事实。</p>
 */
const switchingPointKey = ref("");

async function onSetPointLeg({point, leg}: {point: Point; leg: number}) {
	if (switchingPointKey.value !== "") {
		return; // 上一条还没回来：道岔动作不并发，免得两次点击的响应乱序
	}
	switchingPointKey.value = point.key;
	try {
		const ok = await setPointBranch(point, leg);
		if (!ok) {
			// 单开道岔只有两个位置：从岔股写"去正线远端"这种组合物理上不存在（引擎会拒绝，绝不猜）
			message.error(point.isTurnout
				? `扳道岔失败：${point.coords} 位置 ${point.turnoutPosition} / 腿 ${leg}（这两条进路互斥，物理上不存在这个组合）`
				: `扳道岔失败：${point.coords} / 腿 ${leg}（引擎说这个节点与盆轨对不上，重新读取后再试）`);
			return;
		}
		if (point.isTurnout) {
			const position = point.turnoutLegFor(0) === leg ? 0 : point.turnoutLegFor(1) === leg ? 1 : point.turnoutPosition;
			message.success(`道岔 ${point.coords} 已扳到位置 ${position}（${position === 1 ? "岔股开放" : "正线贯通"}）`);
		} else {
			const legObject = point.legs[leg] ?? null;
			message.success(`道岔 ${point.coords} 已扳到腿 ${leg}${legObject === null ? "" : `（${legObject.kindText}）`}`);
		}
		await load();
	} catch (error) {
		message.error(error instanceof Error ? error.message : String(error));
	} finally {
		switchingPointKey.value = "";
	}
}

/**
 * 复制一段文本，并把**实情**说给用户。
 *
 * <p>不说"已复制"就完事：剪贴板可能被权限拒绝（实测 `NotAllowedError`），
 * 那时候界面还报成功，用户去粘贴才发现是空的 —— 这种"说了假话"的提示比没有提示更坏，
 * 因为他会以为是自己操作错了。所以失败时**把内容也显示出来**，让他能手动复制。</p>
 */
async function copyAndTell(text: string, what: string) {
	const result = await copyText(text);
	if (result.ok) {
		message.success(`已复制${what}`);
		return;
	}
	detail.value = {
		open: true,
		title: `没能写入剪贴板 · ${what}`,
		text: [
			"两条路都失败了：",
			`　${result.error}`,
			"",
			`运行环境：${describeEnvironment()}`,
			"",
			"常见原因：① 站点剪贴板权限被拒（地址栏左侧图标里把「剪贴板」放行）；② 窗口不在最前面；",
			"③ 用局域网 IP（http://192.168…）打开时不是安全上下文，剪贴板接口整个不存在。",
			"",
			"下面是本该复制的内容，**已经替你全选**，按 Ctrl+C 即可（这条不经过剪贴板接口）：",
			"",
			text,
		].join("\n"),
	};
	message.warning(`没能复制${what}：两条路都被拒了（内容已全选在弹窗里，Ctrl+C 即可）`);
}

/**
 * 灯卡片上的「复制坐标」：复制成**指令格式**（`x y z`），因为粘的地方就是指令栏/游戏。
 */
async function onSignalCopy(signal: Signal) {
	await copyAndTell(commandCoords(signal), `坐标 ${commandCoords(signal)}（指令格式）`);
}

/**
 * 灯卡片上的「查为什么是这个色」：把 `signal why x y z` **送进指令栏**（不碰剪贴板）。
 *
 * <p>这条正是我们排查灯色时手打的那条指令；送进去后按回车就有 ①②③ 三段诊断
 * （守哪条腿 / 链上各段占用 / 最终深度）。</p>
 */
function onSignalWhy(signal: Signal) {
	const command = `signal why ${commandCoords(signal)}`;
	sendToConsole(command);
	message.success(`已送进指令栏：${command}（回车执行）`);
}

async function onAction({node, action}: {node: Node; action: string}) {	switch (action) {
		case "center":
			canvas.value?.centerOnWorld(node.planeX, node.planeZ);
			break;
		case "copy":
			// 复制成**指令格式**（x y z，空格分隔）：这个功能存在的意义就是把它粘进指令栏/游戏里，
			// 而 `-70, -59, -167` 粘进去必然解析失败（见 domain/coords.ts）。
			await copyAndTell(commandCoords(node), `坐标 ${commandCoords(node)}（指令格式）`);
			break;
		case "copyReadable":
			await copyAndTell(readableCoords(node), `坐标 ${readableCoords(node)}`);
			break;
		case "console":
			// 不碰剪贴板：直接把查询送进网页指令栏（剪贴板被拒时这条仍然可用）
			sendToConsole(`query node ${commandCoords(node)}`);
			message.success(`已送进指令栏：query node ${commandCoords(node)}（回车执行）`);
			break;
		case "neighbors": {
			const rails = node.neighbours.map(neighbour => neighbour.rail).join("\n");
			await copyAndTell(rails, `${node.neighbours.length} 条相邻轨`);
			break;
		}
		case "block":
			detail.value = {
				open: true,
				title: `节点 ${node.coords} · 覆盖它的区间`,
				text: [
					node.sections.length === 0
						? "区间：无（这一段没有灯照到）"
						: `区间（${node.sections.length} 个）：\n${node.sections.map(section => `  · ${section}`).join("\n")}`,
					node.isMultiSection
						? "说明：**多个区间覆盖同一个节点是正常的** —— 区间按行车方向划分，双向线路上同一根轨的南行、北行各有一个区间。"
						: "说明：这个节点只被一个方向的区间覆盖。",
					"",
					"（区间层按方向画成两条带：南行/北行各一条。要看某条区间的入口灯、出口灯与占用，看画面上的色带或 `query sections` 指令。）",
				].join("\n"),
			};
			break;
		case "fork":
			detail.value = {
				open: true,
				title: `节点 ${node.coords} · 拓扑`,
				text: [
					`度数 ${node.degree} · 相邻轨 ${node.neighbours.length} 条`,
					"",
					...node.neighbours.map((neighbour, index) =>
						`腿 ${index + 1}：${neighbour.x}, ${neighbour.y}, ${neighbour.z}　轨 ${Node.shortHex(neighbour.rail)}…　${Math.round(node.distanceTo(neighbour))} m`),
					"",
					"要**扳道岔**请点图上的琥珀色菱形（道岔层）；那个列表的顺序是引擎定的",
					"（直通→左→右→其它），不是这里的相邻轨顺序——两者用途不同。",
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
			:nodes="displayNodes"
			:rails="rails"
			:signals="signals"
			:points="displayPoints"
			:rail-ends="railEndsByHex"
			:sections="showSections ? sections : []"
			:rail-color-by-hex="railColorByHex"
			:rail-by-hex="railByHex"
			:straight-lookup="straightForCurves"
			@action="onAction"
			@camera="onCamera"
			@shapes="shapeCount = $event"
			@pick-rail="onPickRail"
			@signal-copy="onSignalCopy"
			@signal-why="onSignalWhy"
			@select-signal="onSelectSignal"
			@select-point="onSelectPoint"
			@set-point-leg="onSetPointLeg"
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
			<!-- 道岔：琥珀色菱形 = 要人管的东西。已设定 / 未设定（按 0 直通）分开数，别混成一个数字 -->
			<span class="group">
				<span class="point-dot"/><b class="value">{{ displayPoints.length }}</b>
				<span class="note">道岔（已设定 {{ pointCount.set }} · 未设定 {{ pointCount.unset }} · 锁闭 {{ pointCount.locked }}）</span>
			</span>
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
				<!-- 登记情况：说清"这个数字是不是全的"（世界里扫到多少 / 有没有补登记） -->
				<span v-if="scanState === 'scanning'" class="note">扫描中…</span>
				<span v-else-if="lastScan" class="note">{{ lastScan }}</span>
			</span>
			<span class="group">缩放 <b class="value">{{ zoomText }}</b></span>
			<span class="tip">悬停看信息 · 左键开菜单 · 滚轮缩放 · 拖动平移</span>
			<!-- 点选绑定：选中一盏灯后，真实线=它在守、虚线=可以点，再点一次可以解绑 -->
			<span v-if="bindingSignal" class="group binding">
				<b class="value">改绑定：{{ bindingSignal.coords }}</b>
				<span class="note">
					实线=它在守（{{ bindingSignal.boundRails.length }} 根）· 虚线=可点 · 点已守的线=解绑 · Esc 退出
				</span>
			</span>
			<!-- 选中的道岔：说清"地图上那条琥珀色加粗的轨是哪条腿"，光看数字看不出来 -->
			<span v-if="selectedPoint" class="group binding">
				<b class="value">道岔：{{ selectedPoint.coords }}</b>
				<span class="note">
					开通腿 {{ selectedPoint.activeLeg }}<template v-if="selectedPoint.activeLegObject">（{{ selectedPoint.activeLegObject.kindText }}）</template>
					· 地图上琥珀色加粗的那条轨就是它 · 点腿按钮换向
				</span>
			</span>
			<button class="action" type="button" @click="canvas?.focusPoints()">看道岔</button>
			<button class="action" type="button" @click="canvas?.focusSignals()">看信号灯</button>
			<!-- 区间图层开关：区间带压在轨道上，需要看裸轨或者觉得太花时可以关掉 -->
			<button
				class="action"
				:class="{ active: showSections }"
				type="button"
				:title="showSections
					? `隐藏区间图层（现在画了 ${sections.length} 个区间）`
					: `显示区间图层（按方向画成色带，颜色取自轨道线；共 ${sections.length} 个区间）`"
				@click="showSections = !showSections"
			>
				区间 {{ showSections ? "显示中" : "已隐藏" }}
			</button>
			<button class="action" type="button" @click="loadWithScan">重新读取</button>
			<!-- 自动刷新：默认关。开着的时候按拍重读灯与道岔，屏幕上的 aspect 才跟得上车走 -->
			<button
				class="action"
				:class="{ active: autoRefresh }"
				type="button"
				:title="autoRefresh
					? `每 ${AUTO_REFRESH_MILLIS / 1000} 秒重读信号灯与道岔（拓扑不重取）`
					: '打开后屏幕上的灯与道岔会自己跟着世界更新（默认关，只重读灯与道岔）'"
				@click="toggleAutoRefresh"
			>
				自动刷新 {{ autoRefresh ? "开" : "关" }}
			</button>
			<button class="action" type="button" @click="canvas?.fit()">重置视图</button>
		</div>

		<!-- 指令栏：直接对世界下指令（生成列车、扳道岔、改信号灯、重启服务端…）。
			 指令改动了世界（例如生成/删除车辆、改信号灯）时它会上报 changed，这里重取一次拓扑 -->
		<CommandConsole @changed="load" />

		<NModal v-model:show="detail.open" preset="card" :title="detail.title" style="width: 680px; max-width: 92vw">
			<!--
				剪贴板被浏览器拒绝时，这个弹窗就是唯一的出路，所以正文必须是**能直接复制**的：
				用 readonly 的 textarea 而不是 <pre> —— 一打开就整段选中（@focus 里 select()），
				用户按 Ctrl+C 就行；<pre> 的正文在弹窗里靠拖选，实测很难选中（尤其带滚动条时）。
			-->
			<textarea
				ref="detailBox"
				class="panel selectable"
				readonly
				rows="14"
				:value="detail.text"
				@focus="($event.target as HTMLTextAreaElement).select()"
			/>
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

/* 改绑定提示：整条用强调色，因为这是"现在该做什么"的一句话，不该和统计数字混在一起 */
.hud .binding {
	gap: 8px;
}

.hud .binding b {
	color: var(--accent);
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

/* HUD 里的道岔小菱形：与图上的道岔同形同色（琥珀），一眼能把数字对到东西 */
.point-dot {
	width: 8px;
	height: 8px;
	margin-left: 2px;
	background: #f59e0b;
	border-radius: 1px;
	transform: rotate(45deg);
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

/* 自动刷新开着：用道岔那套琥珀色，跟"这个按钮现在是生效状态"对上 */
.action.active {
	color: #f59e0b;
	border-color: #f59e0b;
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

/* 剪贴板兜底弹窗里的正文：整块可选、一打开就选中（见模板里的说明） */
.selectable {
	display: block;
	width: 100%;
	padding: 8px 10px;
	resize: vertical;
	color: var(--fg-secondary);
	background: var(--panel-sunken, rgba(0, 0, 0, 0.25));
	border: 1px solid var(--line);
	border-radius: 4px;
	outline: none;
}
</style>
