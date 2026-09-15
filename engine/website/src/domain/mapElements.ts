/**
 * 地图元素的**唯一样式与尺寸真源**。
 *
 * <h2>三条口径（用户 2026-09-15 逐条定下来的）</h2>
 *
 * <h3>① 世界坐标是 Minecraft 的方块坐标 —— 用它做几何</h3>
 * <p>"我的世界的坐标永远都是 1x1x1，方块也是，就不能按 1x1x1 进行放置吗？"
 * 所以轨道、节点这些**有实际坐标的东西**按 **1 格 = 1 世界单位**画：轨宽 1 格、节点 1 格。</p>
 *
 * <h3>② 信号灯与道岔是**绑在节点上的**，不掺实际坐标</h3>
 * <p>"信号灯，道岔是绑定在节点上的，直接固定显示在节点旁不行吗？不要掺活实际坐标进来。"
 * 所以它们的锚点是**节点**，标记本身是节点旁的**固定像素偏移**（与缩放无关）。
 * 实测依据：道岔的 key 就是节点键；灯离最近节点 0–4.5 格（它守的是那根轨，不是那个点）。</p>
 *
 * <h3>③ 尺寸只有一个常量</h3>
 * <p>{@link WORLD_UNIT} ＝ 一个规格单位对应多少世界单位；尺寸再由相机折算成屏幕像素。
 * 换算只有 {@link specToWorld} 一处。</p>
 */

/** 一个世界单位 = 一格（Minecraft 方块）。因此世界坐标就是方块坐标，不需要任何换算。 */
export const WORLD_UNIT = 1;

/**
 * **方块网格**（1 × 1 格）—— 用户 2026-09-15："给屏幕加一个 1x1x1 的半透明叠加底层。"
 *
 * <p>它既是一层半透明底衬，也是**标尺**：格线落在世界坐标的整格上，于是"元素有没有按格放"
 * 在屏幕上一眼能看出来。格子大小恒为 1 格（世界属性），随相机一起缩放。</p>
 */
export const BLOCK = {
	/** 一格 = 1 世界单位（Minecraft 的方块就是 1×1×1）。 */
	size: 1,
	/** 格线的**相位**：格线画在整数世界坐标上（plane 坐标即方块中心，所以这样对齐方块边界）。 */
	origin: {x: 0, y: 0},
	/** 格线宽度（世界单位）：0.02 格 ⇒ 推近时可见、全览时近乎消失（不糊住画面）。 */
	lineWidth: 0.02,
} as const;

/** 一格 tile 的两条格线（右下边）—— 与 `GridLayer.vue` 的 pattern 共用同一份几何。 */
export function blockGridLines(size: number): string {
	return `M ${size} 0 L ${size} ${size} L 0 ${size}`;
}

/**
 * 规格表。**格**的那些是"有实际坐标的东西"（1 格 = 1 方块）；
 * **像素**的那些是"绑在节点上的标记"的固定屏幕偏移（用户口径②，与缩放无关）。
 */
export const DECAL_KINDS = {
	/* ------------------------- 有实际坐标的东西：按格 ------------------------- */
	/** 轨道线宽：**1 格**（一根轨就是一条方块宽的线）。 */
	railWidth: 1,
	/** 轨道护套：比本体宽一点，用来在密集处"抠"出两条轨之间的缝。 */
	railShadowWidth: 1.8,
	/** 轨道的可点区（改绑定用）：比本体宽得多，只为好点中。 */
	railHitWidth: 3,
	/** 区间线心：**1.5 格**（比轨宽一点，让两侧的状态条站得住）。 */
	sectionBaseWidth: 1.5,
	/** 区间状态条：0.5 格。 */
	stripeWidth: 0.5,
	/** 状态条中心距线心中心：线心的 1/4 与 3/4（用户那三个数"1-2、4-5"的比例）。 */
	stripeNear: 0.375,
	stripeFar: 1.125,
	/** 区间端点圆点（半径）：0.5 格 ⇒ 直径 1 格。 */
	endpointDot: 0.5,
	/** 轨道节点圆点（直径）：**1 格**（一个方块）。 */
	nodeDot: 1,

	/* --------------------- 绑在节点上的东西：固定屏幕像素 --------------------- */
	/**
	 * 信号灯图标：**8 px**（屏幕固定，用户口径②）。
	 *
	 * <p>它挂在节点旁、不随缩放变 —— 因为它标的是"这个节点上有一盏灯"，
	 * 那是拓扑信息，不是几何尺寸。灯守的轨用**颜色**表达（区间色），位置由节点给。</p>
	 */
	icon: 8,
	/** 灯点（状态色圆点）：图标的一半。 */
	lampDot: 4,
	/** 灯相对节点的偏移：沿**管辖方向的侧向**（现实里信号机立在它所管列车的左侧），固定像素。 */
	signalSideOffset: 0,
	/** 方向箭头相对灯点的前移量（固定像素，指向管辖方向）。 */
	signalArrowForward: 4,
	/** 方向箭头再往侧向让开的量：与灯点错开，避免"戳在一起"。 */
	signalArrowSide: 2,
	/** 道岔菱形：边长 **8 px**（与信号灯同一个规格）。 */
	turnoutDiamond: 8,
	/** 道岔菱形相对节点的偏移（屏幕像素，右下方向）。 */
	turnoutOffset: 6,
	/** 道岔系回节点的引线长度（屏幕像素）。 */
	turnoutLeader: 10,
} as const;

/** 元素种类名（要在规格表里加新元素就在这里加一项）。 */
export type DecalKind = keyof typeof DECAL_KINDS;

/**
 * **规格 → 世界单位**：给"有实际坐标的东西"用的唯一一处换算。
 *
 * <p>绑在节点上的标记（灯、道岔）用**屏幕像素**，不经过这里 —— 它们标的是拓扑，不是几何。</p>
 *
 * @param spec 规格（**格**，见 {@link DECAL_KINDS}）
 */
export function specToWorld(spec: number): number {
	return spec * WORLD_UNIT;
}

/**
 * **屏幕像素 → 世界单位**：绑在节点上的标记（灯、道岔）用。
 *
 * <p>它们的规格是**屏幕像素**（"节点旁固定 8 px 的灯"），而标记层画在世界坐标里，
 * 所以要按当前相机比例折回去：{@code 世界单位 = 屏幕像素 / 相机比例}。于是缩放时它们的
 * 屏幕大小不变（那是拓扑标记该有的行为）。</p>
 *
 * @param px        屏幕像素（规格）
 * @param viewScale 当前相机比例（{@link Camera.scale}）
 */
export function pxToWorld(px: number, viewScale: number): number {
	return px / (viewScale > 0 ? viewScale : 1);
}

/** **世界尺寸 → 屏幕像素**：{@code 世界尺寸 × 倍率}（倍率由相机层给，1 = 正好取景）。 */
export function screenPxOfSpec(worldSize: number, zoomRatio: number): number {
	return worldSize * (zoomRatio > 0 ? zoomRatio : 1);
}

/**
 * 一个元素在屏幕上的位置：锚点（世界坐标）+ 固定像素偏移。
 *
 * <p>两种元素共用它：</p>
 * <ul>
 *   <li><b>有实际坐标的</b>（节点圆点）：偏移为 0，位置就是世界坐标；</li>
 *   <li><b>绑在节点上的</b>（灯、道岔）：锚点是**节点**的世界坐标，偏移是**屏幕像素**
 *       （`fixedOffsetPx`，与缩放无关）。</li>
 * </ul>
 */
export interface DecalPlacement {
	readonly x: number;
	readonly y: number;
}

/**
 * 锚点（世界坐标）+ 固定像素偏移 → 屏幕上的位置。
 *
 * <p><b>这里不乘相机</b>：相机由外层承担（SVG 的 `viewBox`、标记层的 `.layer`），
 * 所以"位置"只有一处换算 —— 世界坐标本身。</p>
 */
export function decalPlacement(
	worldX: number,
	worldY: number,
	fixedOffsetPx?: {x: number; y: number},
): DecalPlacement {
	return {
		x: worldX + (fixedOffsetPx?.x ?? 0),
		y: worldY + (fixedOffsetPx?.y ?? 0),
	};
}

/**
 * 贴片的 CSS `transform`：位移 +（可选）绕自身中心旋转。
 *
 * <p>**位移与旋转必须写在同一个 transform 里**：分开写两个会互相覆盖，
 * 而 `translate(...) rotate(...)` 的顺序保证"先定位、再转"。</p>
 */
export function decalTransform(placement: DecalPlacement, rotationDeg = 0): string {
	return rotationDeg === 0
		? `translate(${placement.x}px, ${placement.y}px)`
		: `translate(${placement.x}px, ${placement.y}px) rotate(${rotationDeg}deg)`;
}

/** 单位向量（屏幕坐标，x 右 y 下）× 距离 = 固定像素偏移。 */
export function pixelOffset(direction: {x: number; y: number}, distancePx: number): {x: number; y: number} {
	return {x: direction.x * distancePx, y: direction.y * distancePx};
}

/* ============================ 信号灯整盏灯的内部布局 ============================ */

/**
 * **信号灯整体的内部布局**（viewBox 单位；盒子宽 20 单位 = 图标 {@link DECAL_KINDS.icon} px）。
 *
 * <p>整盏灯画成**一个 SVG**（折角在上、灯点在下），内部几何是常量；
 * 放置时只做一次"位移 + 旋转"，位移不随朝向变 ⇒ 不会一左一右。
 * 这里的值由 `signal-overlap.test.ts` 用同一份几何钉住（空隙按灯点直径的比例判）。</p>
 */
export const SIGNAL_UNIT = {
	boxWidth: 20,
	boxHeight: 18,
	lampX: 10,
	lampY: 13.4,
	lampRadius: 3.4,
	apexX: 10,
	apexY: 2,
	legX: 4.2,
	legY: 9.5,
	chevronStroke: 2.6,
	chevronOutline: 5,
} as const;

/** 信号灯整体的锚点：**灯点圆心**在 viewBox 里的坐标（也是旋转中心）。 */
export function signalUnitAnchor(): {x: number; y: number} {
	return {x: SIGNAL_UNIT.lampX, y: SIGNAL_UNIT.lampY};
}

/** 折角的两条腿（viewBox 线心折线）：尖在上，两条腿左右对称铺开。 */
export function signalUnitChevronPath(): string {
	return `M ${SIGNAL_UNIT.legX} ${SIGNAL_UNIT.legY} L ${SIGNAL_UNIT.apexX} ${SIGNAL_UNIT.apexY} L ${SIGNAL_UNIT.boxWidth - SIGNAL_UNIT.legX} ${SIGNAL_UNIT.legY}`;
}
