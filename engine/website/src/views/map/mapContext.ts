import {computed, inject, provide, ref, type ComputedRef, type InjectionKey, type ShallowRef} from "vue";

/*
 * 地图页的**内建坐标系** —— 框架与图层之间唯一的契约。
 *
 * <h2>两层，互不干涉（沿用原地图页的结论，只是现在写成了框架的公共部分）</h2>
 * <ol>
 *   <li><b>图像坐标系</b>：一套自己的单位。{@link MAP_VIEW} 是初始取景窗边长，
 *       {@link UNITS_PER_BLOCK} 是**距离比例**（1 格 = 多少画布单位）。图层拿着
 *       {@link MapContext.project} 把世界坐标落进这个空间，落完就不再动。</li>
 *   <li><b>相机</b>：现成控件（svg-pan-zoom）只往那个唯一的 `<g>` 上写一个矩阵。
 *       滚轮/拖拽只改矩阵，**一个画布坐标都不动** —— 图层完全不用知道相机存在。</li>
 * </ol>
 *
 * <h2>锚点（anchor）为什么是"报到后冻结"</h2>
 * <p>世界坐标要先平移到画布上（否则一片负坐标只能靠相机去追）。锚点就是"世界里的哪一点落在
 * 画布中心"。它由**基础图层**在拿到自己的世界范围后报到一次，之后**冻结**：晚到的图层不许
 * 把画面挪走，否则每加一个图层地图就跳一下。</p>
 * <p>锚点变化时 {@link MapContext.anchor} 是响应式的，所以已经画好的图层会**一起**重算 ——
 * 不会出现"一半图层用旧锚点、一半用新锚点"的错位。</p>
 *
 * <h2>加一个图层</h2>
 * <ol>
 *   <li>在 `views/map/layers/` 新建组件；</li>
 *   <li>`const ctx = useMapContext()`，用 `ctx.project(x, z)` 把世界坐标落进画布；</li>
 *   <li>在 `views/MapView.vue` 的 `<MapFrame>` 里加一行 —— **顺序就是叠放顺序**（先写的在下面）。</li>
 * </ol>
 * <p>只有两点要守：尺寸也写在**画布单位**里（屏幕大小 = 画布尺寸 × 相机比例，与图层无关）；
 * 需要 `<defs>`（渐变等）时自己带、id 取不重名的（整个 SVG 共享一个 id 空间）。</p>
 */

/** 图像坐标系的初始取景窗边长（画布单位）。相机起步时的窗口就是它。 */
export const MAP_VIEW = 1000;

/**
 * **会变的数据的刷新节拍**（毫秒）—— 全页唯一的读口。
 *
 * <p>取的是道岔 / 信号灯 / 车辆 / 总区间这四路（见 `liveFeeds.ts`）：一个节拍里每路只取一次，
 * 图层只读结果。想整体快慢就改这一个数。</p>
 *
 * <h2>为什么现在不是 0.5 秒（2026-09-16 实测）</h2>
 * <p>用户要求过"所有可变的都需要 0.5s 一次变动"，试了 0.5 秒之后**游戏开始卡**：服务端日志出现
 * `Can't keep up! Running 18724ms / 32009ms behind`（落后十几到三十几秒）。实测每个活数据接口的
 * 响应时间是 **45–112 ms**（点 53 / 灯 100 / 车 56 / 总区间 112，静态文件才 10 ms）——
 * 说明这些接口的计算是**搭在服务端 tick 上**跑的，于是 3 路 × 2 Hz = 每秒 300–650 ms 的活，
 * 直接把 50 ms 的 tick 预算压垮。</p>
 *
 * <p>⇒ 先用 **2 秒**（改回能跑住的那一档，实测连跑 46 分钟无落后警告）。</p>
 *
 * <h2>2026-09-16 之后（notes/172）：搬走了，但还没到 0.5 秒</h2>
 * <p>现在引擎侧的只读接口走**快照发布**：够新的一份由 Jetty 线程直接答（对 tick 零开销）、
 * 过旧才让一个请求回模拟线程重算并共用，"世界没动"的拍还靠 304 连正文都不传；前端也改成
 * **切到后台停表 + 一拍只跑一次**。所以"多开几个标签"不再等于"多算几遍"。</p>
 *
 * <p><b>但节拍仍然是 2 秒</b>，因为**重活本身还没拆薄**：同一台机器上逐路量出来的构建耗时是
 * `trains 186 ms / signals 48 / total-sections 74 / points 5`（notes/172 的表）。
 * 按 0.5 秒刷＝每秒 6 次重建、其中两次是百毫秒级 —— 每个尖峰仍是两三个 tick。
 * 等那些"一遍请求里把同一份派生结论算好几遍"的路径合并之后，这一个数才是可以往下调的旋钮。</p>
 */
export const LIVE_REFRESH_MILLIS = 2000;

/**
 * 内建坐标系的**距离比例**：1 格（方块）距离 = 多少画布单位。
 *
 * <p>越大 → 同一窗口里看到的范围越小、元素之间越疏。它只决定布局，与缩放无关。</p>
 */
export const UNITS_PER_BLOCK = 2;

/** 世界坐标里的一个点（方块坐标；平面视图只用 x 与 z）。 */
export type WorldPoint = readonly [number, number];

/** 屏幕坐标换算的结果：画布坐标 + 当前比例（像素/画布单位）。 */
export interface CanvasPoint {
	readonly x: number;
	readonly y: number;
	/** 当前相机比例：屏幕像素 ÷ 画布单位。命中半径要按屏幕像素给，就得用它换算。 */
	readonly scale: number;
}

/** 框架交给图层的东西。 */
export interface MapContext {
	/**
	 * 框架的舞台元素（`100% × 100%` 那个 div），挂指针监听用它。
	 *
	 * <p>监听挂这里而不是画在 `<svg>` 里的元素上：点只有 1 格大（默认取景下 ≈1.25 px），
	 * 一个一个元素监听等于"拿鼠标去戳亚像素的点"；正确做法是**自己做命中判定**
	 * （见 {@link MapContext.toCanvas}）。挂载时机也要小心：图层是子组件，用 `watch` + `flush: "post"`
	 * 拿它，别假设自己 `onMounted` 时框架的 ref 已经落上。</p>
	 */
	readonly stage: ShallowRef<HTMLElement | null>;
	/**
	 * 屏幕坐标（`PointerEvent.clientX/Y`）→ 画布坐标 + 当前比例；拿不到相机时返回 null。
	 *
	 * <p><b>为什么必须由框架提供</b>：换算要用**带相机矩阵的那个 `<g>`** 的 CTM
	 * （`getScreenCTM()`）。用 `<svg>` 根的 CTM 会**丢掉相机**（矩阵在子元素上）——
	 * 于是指针位置被当成画布坐标，缩放越大错得越离谱（实测踩过：命中判定永远找不到节点）。
	 * 相机是框架的东西，所以这件事由框架做，图层不必知道 svg-pan-zoom 的内部结构。</p>
	 */
	readonly toCanvas: (clientX: number, clientY: number) => CanvasPoint | null;
	/** 锚点：世界坐标里的这一点落在画布中心。基础图层报到一次后冻结。 */
	readonly anchor: ComputedRef<WorldPoint>;
	/** 世界 `(x, z)` → 画布 `(x, y)`。等比（x/z 同一比例），除锚点外不做任何换算。 */
	readonly project: (worldX: number, worldZ: number) => WorldPoint;
	/**
	 * **基础图层**报到自己的世界范围中心。
	 *
	 * <p>只有第一次生效（之后冻结）。普通图层不要调它 —— 除非它就是这张图的基准数据。</p>
	 */
	readonly setAnchor: (worldX: number, worldZ: number) => void;
}

const MAP_CONTEXT: InjectionKey<MapContext> = Symbol("mmtr-map-context");

/** 由框架（`MapFrame.vue`）调用一次，把契约提供给下面的图层。 */
export function provideMapContext(svg: ShallowRef<SVGSVGElement | null>, stage: ShallowRef<HTMLElement | null>): MapContext {
	const anchor = ref<WorldPoint>([0, 0]);
	/** 冻结标记：只能由第一次报到落下。 */
	let locked = false;

	const project = (worldX: number, worldZ: number): WorldPoint => {
		const [anchorX, anchorZ] = anchor.value;
		return [
			MAP_VIEW / 2 + (worldX - anchorX) * UNITS_PER_BLOCK,
			MAP_VIEW / 2 + (worldZ - anchorZ) * UNITS_PER_BLOCK,
		];
	};

	const toCanvas = (clientX: number, clientY: number): CanvasPoint | null => {
		// 相机矩阵写在 viewport 那个 <g> 上（库的约定，见 MapFrame 头部注释），所以要取**它**的 CTM。
		const viewport = svg.value?.querySelector<SVGGElement>(".svg-pan-zoom_viewport");
		const ctm = viewport?.getScreenCTM();
		if (!viewport || !ctm) {
			return null;
		}
		const point = new DOMPoint(clientX, clientY).matrixTransform(ctm.inverse());
		return {x: point.x, y: point.y, scale: ctm.a};
	};

	const context: MapContext = {
		stage,
		toCanvas,
		anchor: computed(() => anchor.value),
		project,
		setAnchor: (worldX, worldZ) => {
			if (locked) {
				return;
			}
			locked = true;
			anchor.value = [worldX, worldZ];
		},
	};

	provide(MAP_CONTEXT, context);
	return context;
}

/** 由图层调用。挂在框架外面会直接报错（而不是画到别处去）。 */
export function useMapContext(): MapContext {
	const context = inject(MAP_CONTEXT);
	if (!context) {
		throw new Error("useMapContext() 只能在地图框架里用：图层必须挂在 <MapFrame> 下面");
	}
	return context;
}
