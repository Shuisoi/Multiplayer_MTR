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


/** 规格尺寸的基准缩放倍率：在这个倍率下，规格表里的像素值就是屏幕像素值。 */
export const REFERENCE_ZOOM = 6;

/**
 * 各类元素在**基准倍率（{@link REFERENCE_ZOOM}）**下的尺寸（px）。**集中在这里**，不许散回组件里写死。
 *
 * <p>改这里等于改全站观感，所以每个值都写清"为什么是这个数"。</p>
 */
/**
 * **一个规格单位 = 多少世界单位**（整套尺寸体系的唯一标定常量）。
 *
 * <p>规格值是"设计单位"（像 Minecraft 里的格），这个常量把它标成世界单位。
 * 取 0.25 的依据是现场的最小间距：**轨道之间最近 6 格、灯之间最近 6 格**，
 * 于是"一格轨宽"（`railWidth = 2` 规格单位 = 0.5 世界格）在最近的两根轨之间留出 12 倍余量，
 * 图标（`icon = 8` ⇒ 2 格）是最近轨道间距的 1/3 —— 全览时图标会重叠（必然，靠放大解决），
 * 但**比例**在任何地图尺寸下都一样。要让整张图的元素整体变粗/变细，只改这一个数。</p>
 */
export const WORLD_UNIT = 0.25;

export const DECAL_KINDS = {
	/**
	 * 图标类（信号灯、道岔菱形）：**8 px @全览**。
	 *
	 * <p>上限由现场拥挤度定：全览时**灯与灯的最近间距只有 2.8 px**（p50 = 3.7 px）、
	 * 轨道线最近间距 2.8 px、节点最近 2.4 px —— 所以任何"看得清"的图标在全览时都会重叠，
	 * 这是必然的（靠放大解决）。8 px 是"全览能认出是个图标"与"别全糊在一起"之间的折中。</p>
	 */
	icon: 8,
	/** 灯点（状态色圆点）：图标的一半 —— 再大就把方向箭头挤没了。 */
	lampDot: 2.72,
	/**
	 * 区间端点圆点（**半径** 0.25 ⇒ 屏幕上直径 0.5 px @全览）。
	 *
	 * <p>用户 2026-09-15 定的规格："端点无需颜色"、"点需要落在区间线细线上不是基础线上"、
	 * "点的大小也改为 1px"。基准改成全览之后，它按同一口径折半（1 px @6× ⇒ 约 0.17 px @全览，
	 * 那样根本看不见），所以取 0.5 px：仍然只是"区间在这里断"的记号，不抢线心与状态条。</p>
	 */
	endpointDot: 0.25,
	/** 轨道节点圆点（**直径**）：必须小于图标，否则节点比立在它上面的灯还显眼。 */
	nodeDot: 1.6,
	/** 灯相对节点的横向偏移（用户规格："节点左右的 10px"，按全览口径取小一号）。 */
	signalSideOffset: 6,
	/**
	 * **信号灯整体的内部布局**（viewBox 单位；1 px = 2.5 单位 @6×，因为 viewBox 宽 20 单位 = 图标 8 px）。
	 *
	 * <p>用户 2026-09-15 连着提了三次，前两版的错法各不相同：
	 * ① "信号灯图标的点和方向指示位置需要**错开一些**"（原来两个图形是两个绝对定位的元素、中心重合，
	 * 折角尖的可见外缘离中心只 1.68 px 而灯点半径 2 px）；
	 * ② "**现在距离又太大了**"（改成"箭头前移 12 + 灯点后退 8"= 轴距 20 px，而图标直径只有 8 px）；
	 * ③ "**现在箭头又一左一右了**"（改成"侧向 14 px"后，位移跟着朝向转 ⇒ 南行灯在左、北行灯在右）。
	 * 三次的**共同根因**是"两个图形各自算各自的位置"，而且位移挂在一个**会转**的坐标系上。</p>
	 *
	 * <p>现在整盏灯画成**一个 SVG**：折角在上、灯点在下，内部几何是常量；放置时只做一次
	 * "位移 + 旋转"，位移不随朝向变 ⇒ 不会一左一右；灯点到折角的间距是**布局常量**，
	 * 只在一处可出错，并由 `signal-overlap.test.ts` 用同一份几何钉住。</p>
	 */
	signalUnit: {
		/** viewBox 宽（单位）。与 {@link icon} 一起定出"1 px = 2.5 单位"的换算。 */
		boxWidth: 20,
		/**
		 * viewBox 高（单位）：20.5 单位 = **8.2 px @6×** —— 也就是"和图标同宽、比它略高一点"。
		 *
		 * <p>这一组值是**搜出来的**（`sandbox/signal-unit-search.mjs` 在参数空间里按
		 * "可见空隙 ≈ 0.8 px / 灯点 = 4 px / 整体高 ≤ 1.4×图标 / 折角可见高 ≥ 4.6 px" 筛）。</p>
		 */
		boxHeight: 18,
		/** 灯点圆心的 viewBox 坐标 —— **它就是世界坐标的落点**，也是旋转中心。 */
		lampX: 10,
		lampY: 13.4,
		/** 灯点半径（单位）：直径 10 单位 = **4 px @6×**（与 {@link lampDot} 同值）。 */
		lampRadius: 3.4,
		/** 折角的尖（单位）：在灯点正上方，整组旋转后指向管辖方向。 */
		apexX: 10,
		apexY: 2,
		/**
		 * 折角两条腿的下端（单位）。
		 *
		 * <p>腿要铺得够开（`legX` 小）：两条腿的**下端**是离灯点最近的地方，可见空隙由它决定。
		 * 实测 `legX = 2.5` 时空隙 0.63 px，`legX = 2` 时 0.73 px —— 所以取 2。</p>
		 */
		legX: 4.2,
		legY: 9.5,
		/** 折角本色笔画宽（单位）：2.6 单位 = **1.04 px @6×**（黑描边里面那一条彩色的）。 */
		chevronStroke: 2.6,
		/** 折角的黑色描边宽（单位）：5 单位 = **2 px @6×**，比本色宽一圈（暗底上做描边用）。 */
		chevronOutline: 5
	},
	/** 道岔菱形相对节点的偏移：≥ 菱形外接框半宽 + 灯命中半径 + 余量，见 PointMarker 的推导。 */
	turnoutOffset: 8,
	/** 道岔引线长度。 */
	turnoutLeader: 10,
	/**
	 * 区间状态条的宽度与**条中心距线心中心的**偏移（px）。
	 *
	 * <p>用户规格（2026-09-15，纠正过两次，这里以最后一次为准）：
	 * "<b>区间图是一根 6px 的线，1-2、4-5 是用于显示轨道区间的，也就是说绘图只有三根线</b>"。</p>
	 *
	 * <p>把线心中心设为 0、线心横跨 −3 … +3，要"条占第 1–2 px 与第 4–5 px"就是
	 * **条中心落在 1.5 与 4.5**（条宽 2 ⇒ 覆盖 0.5–2.5 与 3.5–5.5）。于是：</p>
	 * <ul>
	 *   <li>两个条都**落在线心内**（4.5 + 1 = 5.5 ≤ 6，这正是"条长在线心里"的字面意思）；</li>
	 *   <li>两条之间留 1 px 缝（2.5 → 3.5），不会糊成一条 4 px 宽带；</li>
	 *   <li>线心的两侧各一条 ⇒ 一眼就是**三根线**（每侧的区间用哪一条由方向定，见 `SectionLayer`）。</li>
	 * </ul>
	 *
	 * <p>曾经取 2 / 5（覆盖 1–3 与 4–6）：外侧那条的外缘正好压在线心边缘上，而内侧那条
	 * 与线心的白边叠了 2 px —— 加上"每个方向画两条"那处缺陷，屏幕上就成了 **5 根线**。</p>
	 */
	stripeWidth: 1,
	stripeNear: 0.75,
	stripeFar: 2.25,
	/** 区间线心宽度（用户规格 6 px）。 */
	sectionBaseWidth: 3,
	/**
	 * **轨道线（路线图）的宽度**：4 px 本体 + 7.2 px 护套。
	 *
	 * <p>护套比本体宽 1.6 px/侧，用来在密集站场里"抠"出两条轨之间的缝。</p>
	 *
	 * <p>与图标同一个基准（6×）：它们是同一张图上的东西，**必须乘同一个因子** ——
	 * 否则缩放时会"脱层"（线条不动、图标在动，看起来图标像贴在屏幕上的 HUD）。</p>
	 */
	railWidth: 2,
	railShadowWidth: 3.6,
	/** 改绑定时的透明命中区：比本体宽得多，只为好点中。 */
	railHitWidth: 6,
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
 * **规格 → 世界单位**：整套系统里**唯一**的一处尺寸换算。
 *
 * <h2>尺寸是世界属性，不是屏幕属性（用户 2026-09-15 点出的要害）</h2>
 * <p>原话："**为什么全览 2px 写死？那么以后特别大的地图的话，岂不是线越来越粗？**"</p>
 * <p>把尺寸挂在"取景（视口）比例"上就是错的：窗口一变、地图一大，同一个规格就换成不同的世界尺寸。
 * 地图元素的尺寸应当像 Minecraft 的"一格 = 一米"那样，是**世界本身**的属性：</p>
 * <pre>
 *   世界单位 = 规格 × WORLD_UNIT
 * </pre>
 * <ul>
 *   <li>地图变大 ⇒ 元素的世界尺寸不变（相对整图自然更细，像真实地图）；</li>
 *   <li>视口变大 ⇒ 什么都不变，只是看得更多；</li>
 *   <li>相机缩放 ⇒ 由外层（`viewBox` / `.layer`）承担，与尺寸无关。</li>
 * </ul>
 *
 * @param spec 规格（**世界单位**，见 {@link DECAL_KINDS}）
 */
export function specToWorld(spec: number): number {
	return spec * WORLD_UNIT;
}

/**
 * **世界尺寸 → 屏幕像素**：{@code 世界尺寸 × 倍率}。
 *
 * <p>注意输入是**世界尺寸**（`specToWorld` 的结果），不是规格值 —— 倍率由相机层给（1 = 正好取景）。
 * 屏幕大小 = 世界尺寸 × 当前相机比例，而"当前相机比例 = 取景基准 × 倍率"，
 * 所以这里乘倍率即可（取景基准那一项在调用方与世界尺寸相除时已经用掉）。</p>
 */
export function screenPxOfSpec(worldSize: number, zoomRatio: number): number {
	return worldSize * (zoomRatio > 0 ? zoomRatio : 1);
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
	offset?: {x: number; y: number},
): DecalPlacement {
	/*
	 * **现在只是"世界坐标 + 偏移"，不再乘相机**。相机由外层承担（SVG 的 `viewBox`、
	 * 标记层的 `.layer` 变换），于是"位置"这件事只有一处换算 —— 世界坐标本身。
	 * 这是坐标系重构（notes/164）里最关键的一刀：以前这里乘相机，等于每个元素各自投影一次。
	 */
	return {
		x: worldX + (offset?.x ?? 0),
		y: worldY + (offset?.y ?? 0),
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
 * 信号灯整体在 viewBox 里的锚点：**灯点圆心**的坐标。
 *
 * <p>组件用它做两件事：① 把整组平移，使灯点落在世界坐标上；② 绕它旋转（旋转中心 = 灯点）。
 * 所以"灯点 = 信号机的世界坐标"这条规格与朝向无关 —— 八个朝向都一样。</p>
 */
export function signalUnitAnchor(): {x: number; y: number} {
	const unit = DECAL_KINDS.signalUnit;
	return {x: unit.lampX, y: unit.lampY};
}

/**
 * 折角的两条腿（viewBox 线心折线）：尖在上，两条腿左右对称铺开。
 *
 * <p>组件与单测**共用这一个来源** —— 否则单测量的是"我以为画的是什么"，而不是"画的是什么"
 * （上一版就是这么把可见外缘估错、得出"已经分开"的结论的）。</p>
 */
export function signalUnitChevronPath(): string {
	const unit = DECAL_KINDS.signalUnit;
	return `M ${unit.legX} ${unit.legY} L ${unit.apexX} ${unit.apexY} L ${unit.boxWidth - unit.legX} ${unit.legY}`;
}

