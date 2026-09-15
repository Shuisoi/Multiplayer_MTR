/**
 * 摄像机：**全世界→屏幕的换算只有这一处**。
 *
 * <h3>为什么要有这个文件</h3>
 * 之前把映射交给 SVG 的 `viewBox`（世界坐标）+ `preserveAspectRatio`（等比缩放、居中），
 * 再把 HTML 浮层塞进 `foreignObject` 跟着一起缩放。结果是同一件事要在三处各算一遍：
 * `viewBox` 的取景公式、`meet` 的实际缩放系数、以及浮层反向缩放的 `1/zoom`。
 * 实测的翻车记录（每一次在屏幕上都是"什么都没有"，但原因各不相同）：
 *
 * <ul>
 *   <li>`fit()` 里 `scale = fill / max(contentAspect, containerAspect) / contentHeight`：
 *       对 80×1 的内容得 0.01125 —— 视口没放大反而缩到 1/89，viewBox 成了 0.9 × 0.01125，
 *       半径 1.3 世界单位的圆点比整个可见范围还大，圆心被推到屏幕外（实测 y = -366，容器高 554）。</li>
 *   <li>把 viewBox 拉成容器比例时乘了 `容器比例 / 内容比例` = 80，方向反了，是把宽度又放大 80 倍，
 *       节点缩成 1/80（实测直径 0.3px）。</li>
 *   <li>子元素要按屏幕尺寸画，于是把"每世界单位多少像素"从视口推到父组件再传给节点；
 *       按高度算成 `554 / 1.111 = 498.6`，而 `meet` 的真实系数是
 *       `min(1244/88.889, 554/1.111) = 13.99`，差 35 倍 —— 节点又变成 0.009 世界单位、0.3px。
 *       而且这个值只在"viewHeight 变了"时才推，改正公式后 viewHeight 恰好没变，于是推了个过期值。</li>
 * </ul>
 *
 * <p>结论：把映射收敛成一个纯函数。视图层不再有坐标算术——所有元素都问这里要屏幕坐标，
 * 屏幕像素尺寸的元素（节点、线宽、鼠标靶）永远不被缩放，尺寸不随映射变化。</p>
 *
 * <h3>约定</h3>
 * <ul>
 *   <li>世界坐标：引擎给的那套，平面图用 `(x, -z)`（沿用 C# 端画布约定，世界 z 越大越靠上）。</li>
 *   <li>屏幕坐标：**相对视口左上角**的 CSS 像素。</li>
 *   <li>缩放 `scale`：一个世界单位占多少屏幕像素。`scale = 1` 就是 1 世界单位 = 1 像素。</li>
 * </ul>
 */

/** 一个矩形，世界坐标或屏幕坐标都用它（单位由调用方决定）。 */
export interface Rect {
	readonly x: number;
	readonly y: number;
	readonly width: number;
	readonly height: number;
}

/**
 * 摄像机状态（纯数据，可被 Vue 追踪）。
 *
 * <p>刻意做成纯对象：这样它既能放在 `ref` 里整块替换，也能放进 `reactive` 里被 watch，
 * 而且任何组件都能独立算出屏幕坐标，不需要别人把结果喂给它。</p>
 */
export interface Camera {
	/** 世界坐标下，视口左上角对应的点。 */
	readonly originX: number;
	readonly originY: number;
	/** 一个世界单位占多少屏幕像素（必须 &gt; 0）。 */
	readonly scale: number;
}

/** 取景留白：四周各留多少**屏幕像素**（口径见 `fitView` 的注释）。 */
export const DEFAULT_PADDING_PX = 28;

/** 缩放范围（相对取景基准），防止缩到看不见或放到失控。 */
export const MIN_ZOOM = 0.02;
export const MAX_ZOOM = 400;

/** 把数字限制在闭区间内。 */
export function clamp(value: number, min: number, max: number): number {
	return value < min ? min : (value > max ? max : value);
}

/**
 * "相对取景基准"的缩放倍率：1 = 正好取景，2 = 放大一倍。
 *
 * <p><b>基准比例必须由持有摄像机的那一层传进来，不能在各处自己记。</b>
 * 理由是一次实测翻车：视图组件自己 latch 了一个基准（`if (baseScale === 0)` 只赋值一次），
 * 它 latch 到的却是**节点还没从接口取回来时**那次退化取景的比例（内容框是 1×1，比例约 1120 px/单位），
 * 之后真实比例的读数一直是 0.02×，而画面完全正常——"画面对、读数不对"。
 * 基准是"最后一次取景得到的比例"，只有取景的实现知道它是什么，所以它跟着摄像机走。</p>
 */
export function zoomRatio(camera: Camera, baseScale: number): number {
	return baseScale > 0 ? camera.scale / baseScale : 1;
}

/**
 * 取景：让整个内容框完整落进视口，四周留白，并居中。
 *
 * <p>这就是"世界→屏幕"的初始化，也是唯一一处把内容尺寸和视口尺寸放在一起算的地方。
 * 两个方向各算一个比例，取**较小**的那个——保证内容两个方向都装得下（与 SVG `meet` 同义），
 * 而不是让某一轴溢出。</p>
 *
 * <p>留白按**屏幕像素**算，不按世界单位、也不按内容尺寸的比例：
 * 它是"内容不要贴着边"这件事，而"贴着边"是屏幕上的观感。按世界单位或内容比例算，
 * 留白换算成像素要再乘当前比例，而比例又取决于世界有多大——实测撞过两次：
 * 写死 12 世界单位时留白不到 1 像素（32 条轨越界），改成内容 4% 后仍有 3 条轨越界 1.8px。</p>
 *
 * @param content 内容包围盒（世界坐标）
 * @param viewport 视口尺寸（CSS 像素）
 * @param paddingPx 四周留白（CSS 像素）
 * @returns 取景后的摄像机；视口或内容尺寸无效时返回一个不会崩的兜底值
 */
export function fitView(
	content: Rect,
	viewport: {width: number; height: number},
	paddingPx = DEFAULT_PADDING_PX,
): Camera {
	const contentWidth = Math.max(1e-6, content.width);
	const contentHeight = Math.max(1e-6, content.height);
	const viewWidth = viewport.width;
	const viewHeight = viewport.height;
	if (!(viewWidth > 0) || !(viewHeight > 0)) {
		// 容器还没量出尺寸（挂载首帧常见）。给一个"1 世界单位 = 1 像素"的诚实兜底，
		// 调用方拿到尺寸后应当重新调用本函数——不要在这里假装算出来了。
		return {originX: content.x, originY: content.y, scale: 1};
	}

	/*
	 * 可用区域 = 视口去掉四周留白。留白比可用区域还大时（极小窗口）缩到 0，
	 * 宁可贴边也不能得到负数尺寸。
	 */
	const usableWidth = Math.max(1, viewWidth - paddingPx * 2);
	const usableHeight = Math.max(1, viewHeight - paddingPx * 2);
	// 两个方向各能放多大，取小的那个：这就是"装得下"的定义。
	const scale = Math.min(usableWidth / contentWidth, usableHeight / contentHeight);

	return {
		originX: content.x + contentWidth / 2 - viewWidth / (2 * scale),
		originY: content.y + contentHeight / 2 - viewHeight / (2 * scale),
		scale,
	};
}

/** 世界坐标 → 屏幕坐标（相对视口左上角）。 */
export function worldToScreen(camera: Camera, worldX: number, worldY: number): {x: number; y: number} {
	return {
		x: (worldX - camera.originX) * camera.scale,
		y: (worldY - camera.originY) * camera.scale,
	};
}

/** 屏幕坐标（相对视口左上角）→ 世界坐标。 */
export function screenToWorld(camera: Camera, screenX: number, screenY: number): {x: number; y: number} {
	return {
		x: camera.originX + screenX / camera.scale,
		y: camera.originY + screenY / camera.scale,
	};
}

/**
 * 以某个屏幕点为锚点缩放：锚点下的世界坐标保持不变（滚轮缩放的手感来源）。
 *
 * <p>锚点已经是相对视口左上角的坐标，调用方负责减去 `getBoundingClientRect()` 的偏移——
 * 这是唯一需要调用方参与的换算，因为它只能用 DOM 量。</p>
 */
export function zoomAt(camera: Camera, screenX: number, screenY: number, factor: number, baseScale: number): Camera {
	const nextScale = clamp(camera.scale * factor, baseScale * MIN_ZOOM, baseScale * MAX_ZOOM);
	if (nextScale === camera.scale) {
		return camera;
	}
	const anchor = screenToWorld(camera, screenX, screenY);
	// 让锚点的世界坐标在缩放后仍落在同一个屏幕位置：origin = 世界锚点 - 屏幕锚点 / 新比例
	return {
		originX: anchor.x - screenX / nextScale,
		originY: anchor.y - screenY / nextScale,
		scale: nextScale,
	};
}

/** 平移：按屏幕像素位移移动视图（拖动时用）。 */
export function panBy(camera: Camera, dxPixels: number, dyPixels: number): Camera {
	return {
		originX: camera.originX - dxPixels / camera.scale,
		originY: camera.originY - dyPixels / camera.scale,
		scale: camera.scale,
	};
}

/** 把某个世界点移到视口正中（"居中到这里"）。 */
export function centerOn(
	camera: Camera,
	worldX: number,
	worldY: number,
	viewport: {width: number; height: number},
): Camera {
	return {
		originX: worldX - viewport.width / (2 * camera.scale),
		originY: worldY - viewport.height / (2 * camera.scale),
		scale: camera.scale,
	};
}

/** 由一组世界点求包围盒；点集为空时给一个 1×1 的退化解。 */
export function boundsOf(points: readonly {x: number; y: number}[]): Rect {
	if (points.length === 0) {
		return {x: 0, y: 0, width: 1, height: 1};
	}
	let minX = Infinity;
	let minY = Infinity;
	let maxX = -Infinity;
	let maxY = -Infinity;
	for (const point of points) {
		if (point.x < minX) {
			minX = point.x;
		}
		if (point.y < minY) {
			minY = point.y;
		}
		if (point.x > maxX) {
			maxX = point.x;
		}
		if (point.y > maxY) {
			maxY = point.y;
		}
	}
	// 退化成点或一条线时给一个最小尺寸，避免无穷缩放。
	const width = Math.max(maxX - minX, 1e-3);
	const height = Math.max(maxY - minY, 1e-3);
	return {x: minX, y: minY, width, height};
}

/*
 * 关于"给内容留边"：这里**故意没有** expand / expandBy 这类按世界单位外扩包围盒的工具。
 *
 * 留白是屏幕观感（"内容不要贴着边"），而世界单位或内容比例的留白换算成像素都要再乘当前比例，
 * 比例又取决于世界有多大——实测撞过两次：写死 12 世界单位时留白不到 1 像素（32 条轨越界），
 * 改成内容 4% 后仍有 3 条轨越界 1.8px。现在留白只由 `fitView` 的 `paddingPx` 一个地方加，
 * 口径是屏幕像素，没有第二种写法。
 */
