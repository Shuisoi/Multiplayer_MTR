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
	/** 灯点（信号灯那个表示状态的圆点）：必须**小于**图标，否则方向箭头被自己盖住。 */
	lampDot: 4,
	/** 区间端点圆点：比图标小一点，免得盖住线心。 */
	endpointDot: 5,
	/** 轨道节点圆点（半径）。 */
	nodeDot: 3.6,
	/** 灯相对节点的横向偏移（用户规格："固定在节点左右的 10px"，同样以 6× 为基准）。 */
	signalSideOffset: 10,
	/**
	 * **方向箭头相对锚点的前移量**（规格 6 px @6×，方向 = 管辖方向）。
	 *
	 * <p>用户 2026-09-15 对灯点与箭头连着提了两次要求，**两条都要满足**：
	 * 先"**位置需要错开一些**"，隔一轮又"**现在距离又太大了**"。
	 * 这个值不动"错开够不够"（那由 {@link signalArrowSide} 定），只把箭头沿管辖方向
	 * 往前挪一点，让"箭头在灯的哪一侧"这件事仍然读得出来。</p>
	 *
	 * <h2>为什么光靠"沿管辖轴拉开"不行</h2>
	 * <p>第一版就是纯轴距：箭头前移 12 px、灯点后退 8 px（轴距 20 px）。它确实不重叠，
	 * 但**灯点正好落在折角的 V 开口里** —— 眼睛量的是"灯点到折角两条腿的距离"，
	 * 不是中心距，于是只能把轴距继续拉大才看得过去，而 20 px 就是用户说的"太大"
	 * （图标直径只有 8 px）。折角的两条腿铺得很开：笔画宽 7 单位 = 2.8 px @6×，
	 * 可见外缘离图标中心只有 ≈3.4 px。</p>
	 */
	signalArrowForward: 6,
	/**
	 * 方向箭头往**侧向**（管辖方向在屏幕里转 90° 的那一侧）让开的量（规格 14 px @6×）。
	 *
	 * <p>这才是"错开"真正起作用的那一半：它直接把灯点挪出折角的 V，于是**不用**再把轴距拉大。
	 * 这个 14 px 也是算出来的，不是手感 —— 灯点到折角**腿的线心**的垂直距离由它决定，
	 * 而腿的线心并不与管辖轴平行（viewBox 里是斜的），实测该垂距 ≈ 侧向量 / √15 × 4
	 * ⇒ 侧向 14 px 时可见空隙 ≈ 3.4 px。取 12 px 只剩 2.65 px（`signal-overlap.test.ts`
	 * 的门槛是 3 px），取 14 px 刚好过线 ⇒ 规格取 14。</p>
	 *
	 * <p>与朝向的关系：中心距恒为 √(6² + 14²) ≈ 15.2 px（无论朝哪边），
	 * 页面上实测"所有灯的错开横向分量 ≤ 0.005 px"（浮点残渣）—— 没有与朝向耦合的分量。</p>
	 */
	signalArrowSide: 14,
	/**
	 * **灯点相对锚点的后移量**（规格 0 px @6× —— 灯点就画在锚点上）。
	 *
	 * <p>留成参数是为了让"灯点要不要跟着让位"这件事可以被测试直接问到（`signal-overlap.test.ts`
	 * 会传别的值来**红证**几何判断真的在量它）。之所以取 0：灯点的世界坐标就是"信号机立在哪"，
	 * 让它留在锚点上最直白；而"错开"由箭头一侧完成，锚点的位置也就不必跟着挪，
	 * 于是整盏灯仍然落在"节点旁 `signalSideOffset`"那个位置上。</p>
	 */
	signalDotBack: 0,
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
	/**
	 * **轨道线（路线图）的宽度**：4 px 本体 + 7.2 px 护套。
	 *
	 * <p>护套比本体宽 1.6 px/侧，用来在密集站场里"抠"出两条轨之间的缝。</p>
	 *
	 * <p>与图标同一个基准（6×）：它们是同一张图上的东西，**必须乘同一个因子** ——
	 * 否则缩放时会"脱层"（线条不动、图标在动，看起来图标像贴在屏幕上的 HUD）。</p>
	 */
	railWidth: 4,
	railShadowWidth: 7.2,
	/** 改绑定时的透明命中区：比本体宽得多，只为好点中。 */
	railHitWidth: 12,
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

/**
 * **每一层都要用的那个因子**：把"图上任何一个像素量"从规格换算到当前屏幕。
 *
 * <p>存在的理由（用户 2026-09-15 的要求："这个是需要所有地图元素都有类似效果的，不是单单几个图标"）：
 * 效果要**全图一致** —— 轨道线宽、节点圆点、灯的图标、道岔菱形、区间线心与状态条，全都乘同一个因子。
 * 只要有一个元素漏乘，缩放时它就会与周围"脱层"（看起来像贴在屏幕上的 HUD）。</p>
 *
 * <p>所以各图层不要自己写 `scaled(..., ratio)`，直接用这个 computed 出来的因子乘 ——
 * 这样"哪些元素跟着缩放"在代码里一眼看全，也不会漏。</p>
 */

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

/**
 * 信号灯这一**组贴片**的位移（屏幕向量，x 向右 y 向下）：**灯点**与**方向箭头**各一个。
 *
 * <h2>为什么两个位置都由这一个函数给</h2>
 * <p>用户 2026-09-15 提了两次要求："信号灯图标的点和方向指示位置需要**错开一些**"，
 * 随后又"**现在距离又太大了**"。两条都要满足，判据（两个图形不许相接、且中心距要小）
 * 只能在同一坐标系里量 —— 组件算一点、测试再算一遍，两边必然各漂各的。
 * 所以两个偏移成对返回，组件照抄、测试直接量。</p>
 *
 * <h2>为什么是"斜着摆"（前移 + 侧向），而不是纯沿管辖轴拉长</h2>
 * <p>折角是**敞开的 V**，两条腿铺得很开（可见外缘离图标中心只有 ≈3.4 px）。
 * 纯沿轴把灯点往后推，灯点正好落在 V 的开口里，"看着像夹在折角中间" ——
 * 要摆脱这个观感就只能把轴距拉到 **20 px**（第一版就是），那正是用户说的"距离太大"。
 * 斜着摆把灯点直接挪出 V：中心距 √(6² + 14²) ≈ 15.2 px（图标直径的 1.9 倍），
 * 侧向那 14 px 决定"灯点到折角腿的距离" ⇒ 可见空隙 ≈ 3.4 px。</p>
 *
 * <h2>顺管辖方向看是"灯点在锚点、箭头在它的斜前方"</h2>
 * <p>灯点留在锚点上（= 信号机的世界坐标，最直白），箭头朝管辖方向偏出去一点 ——
 * 与"信号机立在它所管区间的人口处、司机迎着它开"同向。</p>
 *
 * @param angle     信号灯朝向角（MTR 约定：南=0、西=90、北=180、东=270）
 * @param zoomRatio 当前缩放倍率（{@link REFERENCE_ZOOM} 下规格值即屏幕值）
 * @param dotBack   灯点后移量（px @基准倍率）；默认取规格表的值（现为 0）。
 *                  测试要能传别的值来**红证**几何判断真的在量它，所以这个参数不能砍掉
 */
export function signalDecalOffset(
	angle: number,
	zoomRatio: number,
	dotBack: number = DECAL_KINDS.signalDotBack,
): {arrow: {x: number; y: number}; dot: {x: number; y: number}} {
	const radians = (angle * Math.PI) / 180;
	// 管辖方向的屏幕向量（与引擎 headingOf 同式，屏幕 y 就是世界 z）
	const fx = -Math.sin(radians);
	const fy = Math.cos(radians);
	// 横向 = 管辖方向在屏幕里转 90°
	const sx = -fy;
	const sy = fx;
	const forward = scaled(DECAL_KINDS.signalArrowForward, zoomRatio);
	const side = scaled(DECAL_KINDS.signalArrowSide, zoomRatio);
	const back = scaled(dotBack, zoomRatio);
	return {
		arrow: {x: fx * forward + sx * side, y: fy * forward + sy * side},
		dot: {x: -fx * back, y: -fy * back},
	};
}

