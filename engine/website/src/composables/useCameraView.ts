import {computed, onBeforeUnmount, onMounted, ref, watch, type Ref} from "vue";
import {
	boundsOf,
	centerOn,
	clamp,
	DEFAULT_FILL,
	expand,
	fitView,
	MAX_ZOOM,
	MIN_ZOOM,
	panBy,
	screenToWorld,
	worldToScreen,
	zoomAt,
	zoomRatio,
	type Camera,
	type Rect,
} from "@/domain/camera";

/**
 * 视口交互（测量容器 + 平移 + 缩放 + 取景）。
 *
 * <p>把"容器量多大、摄像机在哪、怎么拖怎么缩"收在一个 composable 里，视图组件只负责画。
 * 之前这些散在 `Viewport.vue` 与父组件之间，还额外把比例推来推去，成了三次翻车的来源
 * （细节见 `domain/camera.ts` 顶部）。</p>
 *
 * <p>关键约定：摄像机状态的**唯一持有者**是调用方（这里是 `MapCanvas`），
 * 它经过 `v-model` 双向绑定传给我的 `camera` ref。这样任何一层想算屏幕坐标，
 * 直接读同一份 `camera` 即可，不存在"某一层拿到的是过期值"。</p>
 */
export function useCameraView(options: {
	/**
	 * 视口容器（用来测量尺寸、挂事件）。
	 * 类型写成 `HTMLElement | null`：`useTemplateRef()` 给的就是这个（未挂载时是 null），
	 * 写成 `undefined` 会让调用方每次都得多写一次类型断言。
	 */
	host: Ref<HTMLElement | null>;
	/** 摄像机状态（双向）。 */
	camera: Ref<Camera>;
	/** 内容包围盒（世界坐标）：取景用。 */
	content: Ref<Rect>;
	/** 取景留白比例。 */
	fill?: number;
}) {
	const width = ref(0);
	const height = ref(0);
	const dragging = ref(false);
	/** 取景基准比例：缩放上下限相对它算，避免内容很小时缩到 1e-9。 */
	const baseScale = ref(1);
	/** 用户是否手动调过视图：调过之后容器尺寸变化不再自动重新取景。 */
	let touched = false;
	let dragStart = {clientX: 0, clientY: 0, camera: options.camera.value};

	/** 视口尺寸（CSS 像素）。 */
	function viewport(): {width: number; height: number} {
		return {width: width.value, height: height.value};
	}

	/** 取景：把整个内容框放进视口并居中。 */
	function fit() {
		const view = viewport();
		const next = fitView(options.content.value, view, options.fill ?? DEFAULT_FILL);
		/*
		 * 顺序很重要：**先更新基准比例，再写摄像机**。
		 *
		 * 摄像机一变就会被上层 watch 到并上报（`MapCanvas` → HUD），而 HUD 的倍率是
		 * `camera.scale / baseScale`。写成先写摄像机、后写基准，就会用**上一版**的基准去算这一次的倍率。
		 * 实测撞过一次：首次取景发生在节点还没取回来的时候（内容框是退化的 1×1，基准算成 ~15 px/单位），
		 * 真实节点到了之后取景比例是 0.31，HUD 却一直显示 0.02× ——刚好是 0.31/15。
		 * 画面是对的、读数不对，这种"只错一处"的 bug 最难看出来，所以把顺序写死并留这段注释。
		 */
		if (view.width > 0 && view.height > 0) {
			baseScale.value = next.scale;
		}
		options.camera.value = next;
		touched = false;
	}

	/** 量容器尺寸；首次量到真实尺寸、或用户没动过视图时尺寸变化，都重新取景。 */
	function measure() {
		const element = options.host.value;
		if (!element) {
			return;
		}
		const rect = element.getBoundingClientRect();
		const changed = Math.abs(rect.width - width.value) > 0.5 || Math.abs(rect.height - height.value) > 0.5;
		width.value = rect.width;
		height.value = rect.height;
		if (changed && rect.width > 0 && rect.height > 0 && !touched) {
			fit();
		}
	}

	/**
	 * 把"用户已经手动调过视图"的状态清掉。
	 *
	 * <p>数据重新取回来时要用：新数据应当重新取景，而不是沿用上一次的视角——
	 * 否则用户拖到一边之后再点"重新读取"，新节点会落在视野外，看起来像"没数据"。</p>
	 */
	function resetTouched() {
		touched = false;
	}

	/** 手动设置内容包围盒（路径数据到了以后调用，会按当前策略决定是否重新取景）。 */
	function setContent(rect: Rect) {
		options.content.value = rect;
		if (!touched) {
			fit();
		}
	}

	/** 平移一个屏幕像素位移。 */
	function pan(dx: number, dy: number) {
		options.camera.value = panBy(options.camera.value, dx, dy);
		touched = true;
	}

	/** 以视口内的屏幕点（相对视口左上角）为锚点缩放。注意与下面返回的 `zoomRatio` 不是一个东西。 */
	function zoom(screenX: number, screenY: number, factor: number) {
		options.camera.value = zoomAt(options.camera.value, screenX, screenY, factor, baseScale.value || options.camera.value.scale);
		touched = true;
	}

	/** 把某个世界点移到视口正中。 */
	function centerOnWorld(worldX: number, worldY: number) {
		options.camera.value = centerOn(options.camera.value, worldX, worldY, viewport());
		touched = true;
	}

	function onPointerDown(event: PointerEvent) {
		if (event.button !== 0 && event.button !== 1) {
			return;
		}
		dragging.value = true;
		dragStart = {clientX: event.clientX, clientY: event.clientY, camera: options.camera.value};
		options.host.value?.setPointerCapture(event.pointerId);
	}

	function onPointerMove(event: PointerEvent) {
		if (!dragging.value) {
			return;
		}
		// 拖动过程中按"起点摄像机 + 总位移"算，而不是逐帧累加：累加会因取整漂移。
		const base = dragStart.camera;
		options.camera.value = panBy(base, event.clientX - dragStart.clientX, event.clientY - dragStart.clientY);
		touched = true;
	}

	function onPointerUp(event: PointerEvent) {
		dragging.value = false;
		if (options.host.value?.hasPointerCapture(event.pointerId)) {
			options.host.value.releasePointerCapture(event.pointerId);
		}
	}

	function onWheel(event: WheelEvent) {
		event.preventDefault();
		const rect = options.host.value?.getBoundingClientRect();
		if (!rect) {
			return;
		}
		// 滚轮是按"屏幕点"缩放的，所以这里必须减掉容器偏移——这是唯一需要 DOM 参与的换算。
		const screenX = event.clientX - rect.left;
		const screenY = event.clientY - rect.top;
		const factor = event.deltaY < 0 ? 1.15 : 1 / 1.15;
		// 诊断：`?cameraDebug=1` 时记录每一次缩放的输入与结果（锚点缩放最容易"看着对其实差一点"）。
		if (typeof window !== "undefined" && window.location.search.includes("cameraDebug")) {
			const w = window as unknown as {__zooms?: unknown[]};
			w.__zooms ??= [];
			const before = options.camera.value;
			const anchor = screenToWorld(before, screenX, screenY);
			w.__zooms.push({screenX, screenY, factor, beforeScale: before.scale, anchor});
			zoom(screenX, screenY, factor);
			const after = options.camera.value;
			const back = worldToScreen(after, anchor.x, anchor.y);
			w.__zooms[w.__zooms.length - 1] = {
				...w.__zooms[w.__zooms.length - 1] as object,
				afterScale: after.scale,
				anchorScreenAfter: back,
			};
			return;
		}
		zoom(screenX, screenY, factor);
	}

	let observer: ResizeObserver | undefined;

	/*
	 * 诊断：`?cameraDebug=1` 时把取景与水位的内部状态挂到 window 上。
	 * "画面对、读数不对"这类问题光看屏幕猜不出来（倍率是 camera.scale / baseScale 两个值的商），
	 * 必须能看到两个值各自是多少。
	 */
	if (typeof window !== "undefined" && window.location.search.includes("cameraDebug")) {
		(window as unknown as {__mmtrView: unknown}).__mmtrView = () => ({
			viewport: {width: width.value, height: height.value},
			camera: options.camera.value,
			baseScale: baseScale.value,
			zoom: baseScale.value > 0 ? options.camera.value.scale / baseScale.value : 0,
			touched,
		});
	}

	onMounted(() => {
		// 挂载当帧容器可能还没有尺寸（父层定位、字体加载都可能晚一拍），
		// 所以先量一次、取一次景，再挂 ResizeObserver 等真实尺寸出现后补一次。
		measure();
		fit();
		if (typeof ResizeObserver !== "undefined" && options.host.value) {
			observer = new ResizeObserver(() => measure());
			observer.observe(options.host.value);
		}
		void document.fonts?.ready.then(() => measure());
	});

	onBeforeUnmount(() => {
		observer?.disconnect();
		observer = undefined;
	});

	// 内容变化（数据接入 / 筛选）时，没动过视图就重新取景。
	watch(options.content, () => {
		if (!touched) {
			fit();
		}
	});

	return {
		width,
		height,
		dragging,
		/**
		 * 相对取景基准的倍率（1 = 正好取景）。
		 *
		 * <p>名字带 Ratio 是为了和上面的 `zoom()` 命令区分开——那两个是不同的东西：
		 * 一个是"缩放到某处"的动作，一个是"现在多大"的读数。</p>
		 */
		zoomRatio: computed(() => zoomRatio(options.camera.value, baseScale.value)),
		viewport,
		fit,
		measure,
		resetTouched,
		setContent,
		pan,
		zoom,
		centerOnWorld,
		onPointerDown,
		onPointerMove,
		onPointerUp,
		onWheel,
	};
}

/** 便捷：由一组世界点算出包围盒（带外扩）。 */
export function contentBounds(points: readonly {x: number; y: number}[], margin = 8): Rect {
	return expand(boundsOf(points), margin);
}

export {clamp, MAX_ZOOM, MIN_ZOOM};
