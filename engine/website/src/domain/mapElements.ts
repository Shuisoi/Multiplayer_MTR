/**
 * 地图元素的**统一尺度规则**（唯一真源）。
 *
 * <h2>世界不动，动的是摄像机</h2>
 * <p>用户 2026-09-15 定的口径（这一条定死了所有元素的行为）：
 * "<b>所谓的 8 px 是缩放为 6× 的时候大小是 8 px，这个应该跟随缩放变换大小 ——
 * 你可以理解成是摄像机在移动，地图大小和位置关系不动。</b>"</p>
 *
 * <p>也就是说：**没有"屏幕固定像素"的贴片**。所有元素都是**地图上的东西**，只是各自有一个
 * "在 6× 下多大"的规格；摄像机拉远拉近，它们跟着一起变 —— 因为变的是相机，不是地图。</p>
 *
 * <h2>于是只有一条规则</h2>
 * <p>{@link mapScale}：把"规格尺寸"换算成**当前**屏幕像素的倍率。元素的屏幕尺寸 =
 * 规格值 × {@code mapScale}。位置同理：锚点从世界坐标投影，偏移也是规格值 × {@code mapScale}。</p>
 *
 * <ul>
 *   <li>缩放倍率 {@code zoomRatio} 由相机层给（1 = 正好取景；{@link REFERENCE_ZOOM} = 规格的基准）；</li>
 *   <li>于是 6× 时 {@code mapScale = 1}，图标正好 8 px；12× 时是 2，图标 16 px；3× 时是 0.5，图标 4 px。</li>
 * </ul>
 *
 * <h2>为什么把"倍率"显式化</h2>
 * <p>以前每个元素各自判断"我该不该跟着缩放"，于是同一张图上出现了两种尺子
 * （灯的偏移按屏幕像素、尺寸按世界），写出来就是"缩放时灯相对节点滑走"。
 * 现在**所有**元素都乘同一个 {@code mapScale}，不可能再混。</p>
 */

import type {Camera} from "./camera";

/** 规格尺寸的基准缩放倍率：在这个倍率下，规格表里的像素值就是屏幕像素值。 */
export const REFERENCE_ZOOM = 6;

/**
 * 各类元素在**基准倍率（{@link REFERENCE_ZOOM}）**下的尺寸（px）。**集中在这里**，不许散回组件里写死。
 *
 * <p>改这里等于改全站观感，所以每个值都写清"为什么是这个数"。</p>
 */
export const DECAL_KINDS = {
	/** 图标类（信号灯、道岔菱形）：8 px @6×（用户规格）。 */
	icon: 8,
	/** 灯点（灯图标下面那个圆）：必须**小于**图标，否则方向箭头被自己盖住。 */
	lampDot: 4,
	/** 区间端点圆点：比图标小一点，免得盖住线心。 */
	endpointDot: 5,
	/** 轨道节点圆点（半径）。 */
	nodeDot: 3.6,
	/** 灯相对节点的横向偏移（用户规格："固定在节点左右的 10px"，同样以 6× 为基准）。 */
	signalSideOffset: 10,
	/** 道岔菱形相对节点的偏移：≥ 菱形外接框半宽 + 灯命中半径 + 余量，见 PointMarker 的推导。 */
	turnoutOffset: 16,
	/** 道岔引线长度。 */
	turnoutLeader: 16,
	/**
	 * 区间状态条的宽度与**距线心中心的**偏移（px）。
	 *
	 * <p>用户规格："在原来的路线图位置画 6px 线，线的第 1-2px、4-5px 用来表示区间"。
	 * 把线心中心设为 0、线心横跨 −3 … +3，则两条 2 px 条的中心在 ±2 与 ±5：
	 * 覆盖 1–3 与 4–6 px，中间留 1 px 缝。</p>
	 *
	 * <p>注意**不能取 1.5 / 4.5**：那样外侧那条的外缘会到 5.5 px，超出线心的半边（3 px）——
	 * 这条是单测里算出来的，不是估的。</p>
	 */
	stripeWidth: 2,
	stripeNear: 2,
	stripeFar: 5,
	/** 区间线心宽度（用户规格 6 px）。 */
	sectionBaseWidth: 6,
} as const;

/** 元素种类名（要在规格表里加新元素就在这里加一项）。 */
export type DecalKind = keyof typeof DECAL_KINDS;

/**
 * **规格尺寸 → 当前屏幕像素**的倍率。
 *
 * <p>= {@code zoomRatio / REFERENCE_ZOOM}。6× 时为 1（规格值即屏幕值），拉远则小、推近则大 ——
 * 因为变的是摄像机。</p>
 *
 * @param zoomRatio 相机层给的缩放倍率（1 = 正好取景）
 */
export function mapScale(zoomRatio: number): number {
	if (!(zoomRatio > 0)) {
		return 1;
	}
	return zoomRatio / REFERENCE_ZOOM;
}

/** 规格值 → 当前屏幕像素。 */
export function scaled(specPx: number, zoomRatio: number): number {
	return specPx * mapScale(zoomRatio);
}

/** 一个元素在屏幕上的位置：锚点的世界坐标 → 屏幕，再加深缩放后的方向偏移。 */
export interface DecalPlacement {
	readonly x: number;
	readonly y: number;
}

/**
 * 元素落在屏幕哪里。
 *
 * <p>世界→屏幕的算术与 `camera.ts#worldToScreen` **同一式**（`(world − origin) × scale`）：
 * {@code Camera.scale} 是"一个世界单位占多少屏幕像素"，`origin` 是视口左上角对应的世界坐标。
 * 两处必须一致，否则元素会整体偏一个常向量（单测里钉住）。</p>
 *
 * @param worldX 锚点世界坐标（平面 x）
 * @param worldY 锚点世界坐标（平面 y = 世界 z）
 * @param camera 当前相机
 * @param offsetPx 相对锚点的**已经乘过 mapScale** 的偏移（屏幕方向），可省
 */
export function decalPlacement(
	worldX: number,
	worldY: number,
	camera: Camera,
	offsetPx?: {x: number; y: number},
): DecalPlacement {
	return {
		x: (worldX - camera.originX) * camera.scale + (offsetPx?.x ?? 0),
		y: (worldY - camera.originY) * camera.scale + (offsetPx?.y ?? 0),
	};
}

/**
 * 贴片的 CSS `transform`：位移 + （可选）绕自身中心旋转。
 *
 * <p>**位移与旋转必须写在同一个 transform 里**：分开写两个会互相覆盖（CSS 后者覆盖前者），
 * 而 `translate(...) rotate(...)` 的顺序保证"先定位、再绕自身中心转"。写在一处的另一个好处是
 * 所有贴片的锚点语义统一为"元素中心"（配 `translate(-50%, -50%)`）。</p>
 */
export function decalTransform(placement: DecalPlacement, rotationDeg = 0): string {
	return rotationDeg === 0
		? `translate(${placement.x}px, ${placement.y}px)`
		: `translate(${placement.x}px, ${placement.y}px) rotate(${rotationDeg}deg)`;
}

/**
 * 单位向量（屏幕坐标，x 右 y 下）× 距离 = 固定像素偏移。
 *
 * <p>存在的理由：方向类偏移（灯的"节点左右"）要的是"**方向由世界语义定、距离是固定像素**"，
 * 两者绝不能混用一个尺度 —— 混了就会出现"缩放时灯相对节点滑走"那类缺陷。</p>
 */
export function pixelOffset(direction: {x: number; y: number}, distancePx: number): {x: number; y: number} {
	return {x: direction.x * distancePx, y: direction.y * distancePx};
}
