/**
 * 地图元素的**统一尺度规则**（唯一真源）。
 *
 * <h2>为什么需要它</h2>
 * <p>原来每个元素各自算自己的尺寸与位置：轨道线自己做世界→屏幕投影，节点圆点写死 3.6 px，
 * 灯的图标先 20 px 再 8 px、还带一个"向上浮 5 px"的偏移，道岔菱形 16 px 配 34 px 硬编码偏移……
 * 结果是**加一种元素就要再抄一遍这套判断**，而且每抄一遍都可能抄错（"缩放时灯相对节点滑走"
 * 就是这么来的：尺寸/偏移用了两套尺子）。</p>
 *
 * <h2>规则只有两条</h2>
 * <ol>
 *   <li><b>几何元素</b>（轨道线、区间线心、状态条）：世界坐标 → 屏幕，随缩放变化。</li>
 *   <li><b>贴片元素</b>（节点、灯、道岔、端点圆点…）：位置由世界坐标定，**尺寸恒定屏幕像素**；
 *       相对锚点的偏移也**恒定屏幕像素**。</li>
 * </ol>
 * <p>于是"缩放时会不会变形/滑走"这件事不该再由各元素自己判断，而是由本模块的函数决定 ——
 * 新元素只要声明"我是贴片 + 用哪个尺寸"，就自动拿到正确行为。</p>
 *
 * <h2>尺寸规格</h2>
 * <p>用户 2026-09-15 定的规格：**贴片统一 {@link DECAL_BASE_PX} = 8 px**（个别可以更小，
 * 见 {@link DECAL_KINDS}）。{@link decalScale} 用来把老代码里写死的像素值换算到新规格下，
 * 迁移期不必手工重算一遍。</p>
 */

/*
 * 这里的 Camera 只作**类型**引入（`import type`，编译后整行消失）。
 *
 * <p>为什么不直接 import `worldToScreen`：本模块要进 `node --test` 那条链，而裸 Node 既不认
 * `@/` 别名、也不认无扩展名的相对 import（TS 的 `moduleResolution: bundler` 允许省略扩展名，
 * 但 Node 的 ESM 解析不允许）—— 实测两种写法都在测试里报 `ERR_MODULE_NOT_FOUND`。
 * 而 tsconfig 没开 `allowImportingTsExtensions`，加 `.ts` 后缀又会被构建拒绝。
 * 所以：**类型可以引，值不引**；投影那一步按 `worldToScreen` 的公式就地写，
 * 并由单测钉住"两处公式一致"（见 `scripts/map-elements.test.ts`）。</p>
 */
import type {Camera} from "./camera";

/** 贴片的统一基准直径（px，用户规格）。 */
export const DECAL_BASE_PX = 8;

/**
 * 各类贴片的尺寸（px）。**集中在这里**，不许散回组件里写死。
 *
 * <p>改这里等于改全站观感，所以每个值都写清"为什么是这个数"。</p>
 */
export const DECAL_KINDS = {
	/** 图标类贴片（信号灯、道岔菱形）：统一 8 px（用户规格）。 */
	icon: DECAL_BASE_PX,
	/** 灯点（灯图标下面那个圆）：必须**小于**图标，否则方向箭头被自己盖住。 */
	lampDot: 4,
	/** 区间端点圆点：比图标小一点，免得盖住线心。 */
	endpointDot: 5,
	/** 轨道节点圆点（半径，旧的 3/3.6/5 按度数分档先统一到最常见的那档）。 */
	nodeDot: 3.6,
	/** 灯相对节点的横向偏移（px，用户规格"固定在节点左右的 10px"）。 */
	signalSideOffset: 10,
	/** 道岔菱形相对节点的偏移（px）：≥ 菱形外接框半宽 + 灯命中半径 + 余量，见 PointMarker 的推导。 */
	turnoutOffset: 16,
	/** 道岔引线长度（px）：画到菱形中心。 */
	turnoutLeader: 16,
	/**
	 * 区间状态条的宽度与**距线心中心的**偏移（px）。
	 *
	 * <p>用户规格："在原来的路线图位置画 6px 线，线的第 1-2px、4-5px 用来表示区间"
	 * （按 1 起数，即第 0–1 与第 3–4 个像素）。把线心中心设为 0，线心横跨 −3 … +3，
	 * 则两条 2 px 条的中心在 ±2 与 ±5：覆盖 1–3 与 4–6 px（0 起数），
	 * 即 1 起数的"第 2–3 px 与第 5–6 px"——与规格整体对上（差一格的取整，且**中间留缝**）。</p>
	 *
	 * <p>注意**不能取 1.5 / 4.5**：那样外侧那条的外缘会到 5.5 px，超出线心的半边（3 px），
	 * 也就是状态条会溢出到自己那条 6 px 线的外面去（这条是单测里算出来的，不是估的）。</p>
	 */
	stripeWidth: 2,
	stripeNear: 2,
	stripeFar: 5,
	/** 区间线心宽度（px，用户规格）。 */
	sectionBaseWidth: 6,
} as const;

/** 贴片种类名（要加新贴片就在这里加一项）。 */
export type DecalKind = keyof typeof DECAL_KINDS;

/**
 * 把"老代码里写死的像素值"换算到当前规格下（按统一基准等比缩放）。
 *
 * <p>迁移期用：某处原来写 3.5 px、基准从 8 变到 8 时它仍是 3.5；以后基准若改，
 * 所有用它的地方一起变，不必逐个手改。</p>
 */
export function decalScale(oldBasePx: number, kind: DecalKind): number {
	return DECAL_KINDS[kind] / oldBasePx;
}

/** 一个贴片在屏幕上的位置：锚点的世界坐标 → 屏幕，再加一个**固定像素**的方向偏移。 */
export interface DecalPlacement {
	readonly x: number;
	readonly y: number;
}

/**
 * 贴片落在屏幕哪里。
 *
 * <p>世界→屏幕的算术与 `camera.ts#worldToScreen` **同一式**（`(world − origin) × scale`）：
 * {@code Camera.scale} 是"一个世界单位占多少屏幕像素"，`origin` 是视口左上角对应的世界坐标。
 * 两处必须一致，否则贴片会整体偏一个常向量（单测里钉住）。</p>
 *
 * @param worldX 锚点世界坐标（平面 x）
 * @param worldY 锚点世界坐标（平面 y = 世界 z）
 * @param camera 当前相机
 * @param offsetPx 相对锚点的**固定像素**偏移（屏幕方向），可省
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
