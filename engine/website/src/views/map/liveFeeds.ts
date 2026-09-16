/*
 * **会变的那几份数据**（道岔 / 信号灯 / 车辆 / 总区间）—— 全页共用一个刷新节拍。
 *
 * <h2>为什么要有这一层（用户 2026-09-16："所有可变的都需要 0.5s 一次变动"）</h2>
 * <p>原先每个图层自己取自己的数：地图页上 `RailNodesLayer` 与 `SignalLayer` 各取一次
 * `fetchMapNodes()`（= 拓扑 + 道岔 + 灯三个请求，**同一份数据发两遍**），车辆层再挂一个自己的定时器。
 * 要按 0.5 秒刷，这种写法会变成每 0.5 秒发 7 个请求，而且各层节拍不同步 —— 页面上会出现
 * "这一层的车是新的、那一层的灯是旧的"。</p>
 *
 * <p>所以把"会变的"收进一个 store：**一个节拍、每份数据每拍只取一次**，图层只读 ref。
 * 节拍取自 {@link LIVE_REFRESH_MILLIS}（唯一的读口，改一个数就全页一起变）。</p>
 *
 * <h2>谁算"会变"</h2>
 * <ul>
 *   <li>**道岔（`mmtr-points`）**：`prohibited` 决定哪根出口淡一半 —— 扳一次岔就变；</li>
 *   <li>**信号灯（`mmtr-signals`）**：显示随车变；</li>
 *   <li>**车辆（`mmtr-trains`）**：位置随车动；</li>
 *   <li>**总区间（`mmtr-total-sections`）**：带的颜色随占用/灯位变。</li>
 * </ul>
 * <p>**不取的**是静态的那几份：拓扑（轨 + 节点）、L1 切点、站台 —— 它们只在挂载时取一次
 * （拓扑也在这里取一次，因为它与"会变的"那份要拼成派生节点）。</p>
 *
 * <h2>服务端也有节拍（notes/172 改了这条）</h2>
 * <p>只读接口在引擎侧现在走**快照发布**（`WebFeed`）：够新的一份由 Jetty 线程直接发出去、
 * 对游戏 tick 零开销，过旧才有人回模拟线程重算并发布给所有请求共用。所以"多开几个标签"
 * 不再等于"多算几遍"，而"世界没动"的那些拍靠 `ETag` / 304 连正文都不传（见 `api/client.ts`）。</p>
 *
 * <p>这一层要做的三件事：**一拍只取一次**（`tickSafely`，慢响应不堆叠）、
 * **页面切到后台就停表**（`visibilitychange`）、卸载时把定时器与监听都收干净。</p>
 */
import {computed, inject, onBeforeUnmount, onMounted, provide, ref, type ComputedRef, type InjectionKey, type Ref} from "vue";
import {fetchPlatforms, fetchPoints, fetchSignals, fetchTopology, fetchTotalSections, fetchTrains} from "@/api/topology";
import type {Node} from "@/domain/Node";
import type {Platform} from "@/domain/Platform";
import type {Point} from "@/domain/Point";
import type {Rail} from "@/domain/Rail";
import type {Signal} from "@/domain/Signal";
import type {Siding} from "@/domain/Siding";
import type {Train} from "@/domain/Train";
import type {TotalSection} from "@/domain/TotalSection";
import {deriveMapNodes, type MapNode} from "@/domain/MapNode";
import {LIVE_REFRESH_MILLIS} from "./mapContext";

/** 会变的那几路的名字（页面按需要声明）。 */
export type LiveFeedName = "points" | "signals" | "trains" | "totalSections";

/** 框架交给图层的东西：静态的 + 会变的 + 派生出来的。 */
export interface LiveFeeds {
	/** 静态：拓扑节点（挂载取一次；派生节点要用）。 */
	readonly topologyNodes: Ref<readonly Node[]>;
	/** 静态：轨（挂载取一次）。 */
	readonly rails: Ref<readonly Rail[]>;
	/** 活：道岔（`prohibited` → 轨的淡出）。 */
	readonly points: Ref<readonly Point[]>;
	/** 活：信号灯（显示）。 */
	readonly signals: Ref<readonly Signal[]>;
	/** 活：车辆（位置）。 */
	readonly trains: Ref<readonly Train[]>;
	/** 活：股道清单（任务目标 `mission.targetSidingId` 换名字用；与车辆同一份响应）。 */
	readonly sidings: Ref<readonly Siding[]>;
	/**
	 * 静态：站台清单（挂载取一次）。
	 *
	 * <p>为什么车辆这一页要它：**客运任务的目标是"站台"，不是股道**
	 * （引擎那个 `mission.targetSidingId` 字段名骗人，见 `domain/MissionTarget.ts`）——
	 * 要把"这列车被派去哪"写清楚就得有站台清单。只有**声明了车辆**的那一页取它
	 * （区间地图的 `PlatformsLayer` 自己取一份，它要的是引擎原始形状）。</p>
	 */
	readonly platforms: Ref<readonly Platform[]>;
	/** 活：总区间（带的颜色）。 */
	readonly totalSections: Ref<readonly TotalSection[]>;
	/** 派生：拓扑 + 道岔 + 灯拼出来的节点（全页只算一次，地图页两个图层共用）。 */
	readonly mapNodes: ComputedRef<readonly MapNode[]>;
	/** 最后一轮**成功**刷新的时刻（毫秒时间戳）——探针与调试用。 */
	readonly lastTickAt: Ref<number>;
}

const LIVE_FEEDS: InjectionKey<LiveFeeds> = Symbol("mmtr-live-feeds");

/**
 * 由**页面**调用一次（`MapView` / `SectionMapView` 的 setup 里），声明这一页要哪几路活数据。
 * 一个节拍里只取声明过的那些（区间地图不需要车辆，就不发那个请求）。
 */
export function provideLiveFeeds(active: readonly LiveFeedName[]): LiveFeeds {
	const topologyNodes = ref<readonly Node[]>([]);
	const rails = ref<readonly Rail[]>([]);
	const points = ref<readonly Point[]>([]);
	const signals = ref<readonly Signal[]>([]);
	const trains = ref<readonly Train[]>([]);
	const sidings = ref<readonly Siding[]>([]);
	const platforms = ref<readonly Platform[]>([]);
	const totalSections = ref<readonly TotalSection[]>([]);
	const lastTickAt = ref(0);
	const mapNodes = computed<readonly MapNode[]>(() => deriveMapNodes(topologyNodes.value, points.value, signals.value).nodes);

	const wanted = new Set<LiveFeedName>(active);

	/** 一拍：把声明过的活数据**并行**取齐；某一路失败不影响其余（页面上不摆提示，落控制台）。 */
	async function tick(): Promise<void> {
		const jobs: Promise<void>[] = [];
		if (wanted.has("points")) {
			jobs.push(fetchPoints().then(value => {
				points.value = value;
			}).catch(caught => console.error("取道岔失败", caught)));
		}
		if (wanted.has("signals")) {
			jobs.push(fetchSignals().then(value => {
				signals.value = value;
			}).catch(caught => console.error("取信号灯失败", caught)));
		}
		if (wanted.has("trains")) {
			jobs.push(fetchTrains().then(feed => {
				trains.value = feed.trains;
				// 股道清单与车辆同一份响应（任务目标的 id 靠它换名字），顺手一起收下 —— 不多发请求
				sidings.value = feed.sidings;
			}).catch(caught => console.error("取车辆失败", caught)));
		}
		if (wanted.has("totalSections")) {
			jobs.push(fetchTotalSections().then(feed => {
				totalSections.value = feed.sections;
			}).catch(caught => console.error("取总区间失败", caught)));
		}
		await Promise.all(jobs);
		lastTickAt.value = Date.now();
	}

	let timer: ReturnType<typeof setInterval> | null = null;
	/** 上一拍还没回来就不再发下一拍：慢响应不该被自己堆成一队（那正是"网页把引擎问垮"的一半原因）。 */
	let ticking = false;
	/** 组件已经卸载：异步回来的那几拍不许再启动定时器（否则离开这一页之后还在打接口）。 */
	let disposed = false;

	/** 一拍：同一拍内重复调用只会跑一次。 */
	async function tickSafely(): Promise<void> {
		if (ticking || disposed) {
			return;
		}
		ticking = true;
		try {
			await tick();
		} finally {
			ticking = false;
		}
	}

	function start(): void {
		if (timer === null && !disposed) {
			timer = setInterval(() => {
				void tickSafely();
			}, LIVE_REFRESH_MILLIS);
		}
	}

	function stop(): void {
		if (timer !== null) {
			clearInterval(timer);
			timer = null;
		}
	}

	/**
	 * 页面被切到后台就**停表**（notes/172）。
	 *
	 * <p>控制台是常年挂着的页面：人走开了、标签切走了，它原来照样每拍打三个接口 ——
	 * 那些请求在引擎侧就是白花的 tick（而且每开一个这样的标签就多一份）。
	 * 回到前台立刻补一拍，再恢复定时器：切回来的第一眼必须是新的。</p>
	 */
	function onVisibilityChange(): void {
		if (document.hidden) {
			stop();
		} else {
			void tickSafely();
			start();
		}
	}

	onMounted(() => {
		// 监听先挂上（下面要 await，卸载可能发生在 await 中间 —— 那时再挂就没人摘了）
		document.addEventListener("visibilitychange", onVisibilityChange);
		void startInitial();
	});

	async function startInitial(): Promise<void> {
		// 静态的先取一次（拓扑与"会变的"那份要拼成派生节点）
		try {
			const topology = await fetchTopology();
			topologyNodes.value = topology.nodes;
			rails.value = topology.rails;
		} catch (caught) {
			console.error("取轨网失败", caught);
		}
		/*
		 * 站台（静态、挂载一次）：**只有会画车的那一页取**（任务目标可能是站台，见接口注释）。
		 * 放在这里而不是每拍取：它是静态的，而且 `/mmtr-platforms` 有自己的窗口与 ETag。
		 */
		if (wanted.has("trains")) {
			try {
				const platformFeed = await fetchPlatforms();
				platforms.value = platformFeed.platforms;
			} catch (caught) {
				console.error("取站台失败", caught);
			}
		}
		if (disposed) {
			return;
		}
		await tickSafely();
		if (disposed) {
			return;
		}
		if (!document.hidden) {
			start();
		}
	}

	onBeforeUnmount(() => {
		disposed = true;
		document.removeEventListener("visibilitychange", onVisibilityChange);
		stop();
	});

	const feeds: LiveFeeds = {topologyNodes, rails, points, signals, trains, sidings, platforms, totalSections, mapNodes, lastTickAt};
	provide(LIVE_FEEDS, feeds);
	return feeds;
}

/** 由图层调用。挂在框架外面会直接报错（而不是悄悄自己取数）。 */
export function useLiveFeeds(): LiveFeeds {
	const feeds = inject(LIVE_FEEDS);
	if (!feeds) {
		throw new Error("useLiveFeeds() 只能在地图页面里用：活数据由页面（provideLiveFeeds）提供");
	}
	return feeds;
}
