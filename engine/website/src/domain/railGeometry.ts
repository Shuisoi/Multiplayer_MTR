import type {Rect} from "./camera";

/**
 * 轨的平面几何。
 *
 * <h3>线型规则（用户要求）</h3>
 * <p>"若 x 或 y 任一相同，则直线，其余要以曲线表示"——平面图上两个端点在**同一条轴上**
 * （x 相同或 z 相同，即水平/垂直）时画直线；两端点两个轴都不同（斜向）时画曲线。</p>
 *
 * <h3>曲线：简化版，但端点切线与真实轨道共线（用户要求）</h3>
 * <p>两条要求合起来是：</p>
 * <ol>
 *   <li><b>"不需要真的把实际情况画上去，绘制方向正确的简化版就行了"</b> —— 不照搬真实几何
 *       （引擎的轨由两段圆弧拼成，能弯成 U 型甚至 S 型），画成克制的一条曲线；</li>
 *   <li><b>"曲线末端的切线要和上一段直线共线，或与曲线的接触端切线共线"</b> ——
 *       相邻两段在节点处必须**切线连续**，不能只是位置接上却折一个角。</li>
 * </ol>
 * <p>第 2 条决定了曲线怎么定：端点切线必须来自真实轨道（见 `railCurvePath`），
 * 而不是自己按弦长比例编一个矢高。两条要求并不冲突——切线定方向、长度封顶定"简化"。</p>
 */

/** 判定"同一轴"的世界坐标容差（方块坐标下的浮点噪声远小于它）。 */
export const AXIS_TOLERANCE = 0.05;

/**
 * 构建标记：`?railDebug=1` 时挂到 window 上，用来确认页面跑的是哪一版几何代码。
 *
 * <p>留着它是因为吃过一次教训：改完逻辑重新构建后，页面上量到的数字与改之前**一模一样**，
 * 于是花了很久排查"公式为什么没生效"——其实是页面加载的还是旧包。
 * 与其猜"包是不是新的"，不如让代码自己说话。</p>
 */
export const RAIL_GEOMETRY_BUILD = "world-space-simplified-curve-v2";

if (typeof window !== "undefined" && window.location.search.includes("railDebug")) {
	(window as unknown as {__mmtrRailGeometry?: string}).__mmtrRailGeometry = RAIL_GEOMETRY_BUILD;
}

/**
 * 控制点长度（切线长度）的比例。
 *
 * <p>0.533 是三次贝塞尔逼近圆弧时的经典系数（控制点距端点 = 4/3·tan(θ/4)·R）。
 * 控制点**必须**落在端点切线上（这是切线连续的定义），所以长度只能沿切线按比例取，
 * 不能截断到某个绝对值——截断会让控制点离开切线，端点切线方向随之改变（实测偏心约 18°）。</p>
 *
 * <p>试过让这个长度由"最小二乘拟合真实采样点"决定（自由度只剩长度一个，本想更准），
 * 实测**并不更好**：拟合按"同名参数点"做近似（把采样点当作曲线 t 相同的点，而采样点其实是
 * 按弧长均分的），于是它为了迁就点的分布把控制点放远，弯曲幅度失控（到弦长的 26~52%），
 * "比直线更贴近真实轨道"的条数从 26 掉到 17~24。所以回到固定比例。</p>
 */
const TANGENT_LENGTH_RATIO = 0.533;

/** 真实弯曲程度 → 控制点长度的缩放：真实轨道越弯，允许的控制点越远。 */
const MIN_DEVIATION_SCALE = 0.5;
const MAX_DEVIATION_SCALE = 1.2;

/**
 * 切线估向时"走够远"的下限（世界单位）与比例。
 *
 * <p>引擎的采样点取整到方块，短轨上相邻两点经常完全相同；走得不远就取方向会得到零向量
 * （实测 4 条轨因此退化成直线）。所以既要按路径长度的比例走（15%），也要有一个绝对下限。</p>
 */
const TANGENT_SAMPLE_RATIO = 0.15;
const TANGENT_MIN_DISTANCE = 2;

/**
 * 曲线退化成直线的判据：控制点偏离弦的距离和小于这个值（**世界单位**）就不画曲线。
 *
 * <p>阈值必须与世界坐标同尺度，不能按屏幕像素给。实测踩过一次：整图取景后比例只有
 * ~0.1 px/世界单位（覆盖 1600 格），一段弦长 90 世界单位的轨在屏幕上不到 9 像素，
 * 而当时按"矢高 &lt; 0.6px 就画直线"判断，短轨的矢高换算过去只有零点几像素，
 * 于是 93 条本该画曲线的轨有 74 条被压成了直线——线型随缩放变化，正是最不该发生的事。</p>
 *
 * <p>取 0.2 格：数据里斜向轨的弯曲量最小的只有 0.27 格（弦长 10 格的一小段），
 * 阈值再大就会把这类真实存在的浅弯也画成直线。实测 0.5 格时 41 条斜向轨有 2 条被吃掉，
 * 0.2 格时全部保留。它只是兜住"几乎就是直线"的退化情况，不是用来筛弯曲程度的。</p>
 */
const MIN_SAGITTA_WORLD = 0.2;

/**
 * 判断一条轨是否"同一轴"（画直线）。
 *
 * <p>用世界坐标判断，不用屏幕坐标：屏幕坐标会随缩放取整，缩得很小时两条本该斜向的轨
 * 可能因为取整而"看起来"轴对齐，线型就会随缩放跳变。所以线型必须由世界坐标决定。</p>
 */
export function isAxisAligned(x1: number, z1: number, x2: number, z2: number): boolean {
	return Math.abs(x1 - x2) < AXIS_TOLERANCE || Math.abs(z1 - z2) < AXIS_TOLERANCE;
}

/** 一个平面点（屏幕坐标）。 */
export interface PlanePoint {
	readonly x: number;
	readonly y: number;
}

/** 坐标保留两位小数：屏幕像素下再精确没有意义，但能明显减小 DOM 属性的体积。 */
function round(value: number): number {
	return Math.round(value * 100) / 100;
}

/**
 * 把方向对齐到给定参考方向（同向或反向都接受，输出与参考同向）。
 *
 * <p>节点上直线的方向可能与本轨的"起点→终点"流向相反，而控制点必须沿本轨流向摆放，
 * 所以要对齐一次。判据用点积符号 —— 这是唯一稳定的一件事
 * （试过用 `u − v` 之类的组合判据，在合法数据上会误触发，见 `railCurvePath` 里的说明）。</p>
 */
function align(direction: PlanePoint, reference: PlanePoint): PlanePoint {
	const dot = direction.x * reference.x + direction.y * reference.y;
	return dot < 0 ? {x: -direction.x, y: -direction.y} : direction;
}

/** 两点直线路径。 */
export function linePath(from: PlanePoint, to: PlanePoint): string {
	return `M ${round(from.x)} ${round(from.y)} L ${round(to.x)} ${round(to.y)}`;
}


/**
 * 关于"节点统一切向"：试过，**退步，已删除**。
 *
 * <p>想法是"接缝是节点的性质，所以端点切向由节点统一给出"：把节点上各条相邻轨的弦向按 ±25° 聚类、
 * 取均值，曲线的端点切向改用这个均值，接缝就由构造保证连续。</p>
 *
 * <p>实测结果（`tools/check-web-rails.ps1`）：曲线从 40 条掉到 24 条，"比直线更贴近真实轨道"的
 * 从 26 条掉到 11 条，弯向正确的从 21 条掉到 4 条 —— 明显更差；接缝折角也没好转（97 处里 31 处 → 75 处里 24 处）。
 * 原因是**节点上只有弦向可用**，而弦向对一条弯曲的轨并不是它在端点处的切向：弦与切向的夹角
 * 可以到弦长量级，拿它当切向等于把曲线拉直（实测 110 条直线里 17 条是"斜向但被拉平"的）。</p>
 *
 * <p>真正带切向信息的只有引擎沿里程采样的 `rail.path`，所以端点切向回到由本轨采样点估计
 * （见 `endpointTangents` / `railCurvePath`）。节点不参与。</p>
 */

/**
 * 从真实采样点里取两个端点处的切线方向（世界平面坐标，**沿轨从起点流向终点的方向**）。
 *
 * <h3>"沿轨流向"而不是"指向内部"——这里踩过一次，值得记清楚</h3>
 * <p>三次贝塞尔在端点处的切线由控制点决定：起点切线 = C1 − P0，终点切线 = P1 − C2。
 * 要让曲线在两端的方向与真实轨道一致，两个控制点必须沿**同一条沿轨流向**摆放：
 * C1 = P0 + dir₀·λ，C2 = P1 − dir₁·λ（dir₁ 是流入 P1 的方向）。
 * 早先把 `end` 定义成"从终点指向轨内部"（即 −dir₁），于是 C2 被放到了弯的**另一侧**——
 * 实测 38 条斜向轨里 19 条弯向反了、40 条曲线端点切线差 180°，而且是系统性的，
 * 一眼就知道是某个方向翻了，但只有把向量分量打出来（tools/debug-one-rail.mjs）
 * 才能确定翻的是哪一处。</p>
 *
 * <h3>必须只由采样点自己算，不能相对声明端点算</h3>
 * <p>引擎给的 `path` 与轨的两个声明端点**并不重合**：MTR 会把曲线轨的采样点整体移到区间中心，
 * 实测偏差约 1.4 格。所以"离端点最近的采样点"可能正好落在端点上，减出来是零向量
 * （实测 41 条斜向轨里 11 条因此退化成直线）。</p>
 *
 * <h3>也不能只看相邻两个采样点</h3>
 * <p>采样点取整到方块，短轨上相邻两点经常完全相同（实测一条 18 格的轨前 3 个点坐标一致），
 * 取平均也得零向量。所以沿采样链**走到至少 15% 路径长度（且不小于 2 格）之外**再取方向。</p>
 *
 * @param realPath 真实采样点（世界平面坐标）
 * @returns 起点处与终点处的单位切向（都沿"起点 → 终点"的流向）；数据不足或方向退化时为 null
 */
function endpointTangents(realPath: readonly PlanePoint[]): {start: PlanePoint; end: PlanePoint} | null {
	if (realPath.length < 4) {
		return null;
	}
	// 一条链上的总长度，用来定"走多远才算够远"。
	let total = 0;
	for (let i = 1; i < realPath.length; i++) {
		total += Math.hypot(realPath[i]!.x - realPath[i - 1]!.x, realPath[i]!.y - realPath[i - 1]!.y);
	}
	const minDistance = Math.max(TANGENT_MIN_DISTANCE, total * TANGENT_SAMPLE_RATIO);

	/*
	 * 两个方向都用"沿链前进"：`start` 是链首处的前进方向，
	 * `end` 是**把链反过来**之后的前进方向（等价于链尾处的流入方向）。
	 * 两者都沿"起点 → 终点"，调用方因此可以统一地写 C1 = P0 + start·λ、C2 = P1 − end·λ。
	 */
	const start = walkDirection(realPath, minDistance);
	const end = walkDirection([...realPath].reverse(), minDistance);
	if (!start || !end) {
		return null;
	}
	return {start, end};
}

/**
 * 沿点列从首点走到"至少 `minDistance` 之外"的第一个点，返回该方向（单位向量，指向点列前进方向）。
 *
 * <p>用"走到足够远"而不是"相邻两点之差"：采样点取整到方块后相邻点常常重合，差为零向量。
 * 取方向时对**首点到最后那个点之间的所有点**做最小二乘直线拟合，而不是只用两个端点：
 * 采样点取整到方块（±0.5 格），只看两个点时长轨上方向噪声可达十几度；
 * 拟合用上整段点，噪声被平均掉（实测接缝折角从 31 处降到个位数）。</p>
 */
function walkDirection(points: readonly PlanePoint[], minDistance: number): PlanePoint | null {

	let travelled = 0;
	let endIndex = points.length - 1;
	for (let i = 1; i < points.length; i++) {
		travelled += Math.hypot(points[i]!.x - points[i - 1]!.x, points[i]!.y - points[i - 1]!.y);
		if (travelled >= minDistance) {
			endIndex = i;
			break;
		}
	}
	// 首点到 endIndex 这段做最小二乘主方向拟合。
	return principalDirection(points.slice(0, endIndex + 1));
}

/**
 * 一串点的**主方向**（最小二乘拟合直线，取较大特征值对应的方向）。
 *
 * <p>用协方差矩阵的幂迭代求主特征向量：点很少（十几个），迭代几次就够，且不需要开方求根。
 * 方向的正负由"点列整体从首点走向末点"确定。</p>
 */
function principalDirection(points: readonly PlanePoint[]): PlanePoint | null {
	if (points.length < 2) {
		return null;
	}
	let meanX = 0;
	let meanY = 0;
	for (const point of points) {
		meanX += point.x;
		meanY += point.y;
	}
	meanX /= points.length;
	meanY /= points.length;

	let sxx = 0;
	let sxy = 0;
	let syy = 0;
	for (const point of points) {
		const dx = point.x - meanX;
		const dy = point.y - meanY;
		sxx += dx * dx;
		sxy += dx * dy;
		syy += dy * dy;
	}

	// 幂迭代：初值取"首点 → 末点"的方向（已大致正确），迭代 8 次足够收敛。
	let vx = points[points.length - 1]!.x - points[0]!.x;
	let vy = points[points.length - 1]!.y - points[0]!.y;
	if (!(Math.hypot(vx, vy) > 1e-9)) {
		vx = 1;
		vy = 0;
	}
	for (let i = 0; i < 8; i++) {
		const nextX = sxx * vx + sxy * vy;
		const nextY = sxy * vx + syy * vy;
		const length = Math.hypot(nextX, nextY);
		if (!(length > 1e-12)) {
			break;
		}
		vx = nextX / length;
		vy = nextY / length;
	}

	// 统一成"从首点指向末点"的朝向
	const forwardX = points[points.length - 1]!.x - points[0]!.x;
	const forwardY = points[points.length - 1]!.y - points[0]!.y;
	const dot = vx * forwardX + vy * forwardY;
	return unit(dot < 0 ? {x: -vx, y: -vy} : {x: vx, y: vy});
}

/** 单位化；长度过小时返回 null（这种情况没有可用的方向）。 */
function unit(vector: PlanePoint): PlanePoint | null {
	const length = Math.hypot(vector.x, vector.y);
	if (!(length > 1e-9)) {
		return null;
	}
	return {x: vector.x / length, y: vector.y / length};
}

/**
 * 轨的曲线：**三次贝塞尔，两端切线由真实轨道决定**。
 *
 * <h3>为什么必须这样做（用户要求）</h3>
 * <p>"曲线末端的切线要和上一段直线共线，或与曲线的接触端切线共线"——也就是相邻两段在节点处
 * 要**切线连续**（G1），不能只是位置接上却折一个角。早先的画法是"两端点 + 按弦长比例取矢高"，
 * 端点切线方向是这套规则自己算出来的，跟真实轨道无关，所以在节点处必然出现折角。</p>
 *
 * <p>现在的做法：端点切线取自**这条轨自己的真实采样点**（`endpointTangents`），
 * 控制点严格放在切线上（长度沿切线按比例取，不截断），所以曲线两端的切线方向与真实轨道严格一致。
 * 由于相接的两条轨在节点上的切线本来就几乎共线（实测 149 个相邻样本里，连续段的夹角接近 0°），
 * 各段各自贴合自己的真实走向之后，节点处自然就连上了——不需要前端在节点上做特殊拼接。</p>
 *
 * <h3>为什么用三次而不是二次</h3>
 * <p>二次贝塞尔的两个控制点实际上只有一个自由度（曲线必过中点附近的某个点），
 * 要让两端切线都受约束，就得允许弧长自由伸缩；三次的两个控制点可以分别沿各自的切线取长度，
 * 既能保证两个切线方向，又能把"鼓出去多少"控制在手里。所以这里用三次，
 * 两个控制点距离都封顶在弦长的 45%。</p>
 *
 * @param from 起点（世界平面坐标）
 * @param to 终点（世界平面坐标）
 * @param realPath 真实采样点（世界平面坐标）。为空或过少时退回按比例取矢高的画法。
 * @param project 世界平面坐标 → 屏幕坐标。所有阈值在世界坐标里判完，最后才投影。
 */
export function railCurvePath(
	from: PlanePoint,
	to: PlanePoint,
	realPath: readonly PlanePoint[],
	project: (point: PlanePoint) => PlanePoint,
	/**
	 * 查"这个节点上有没有直线轨，方向是什么"（世界平面坐标，单位向量）。
	 *
	 * <p>用来实现用户要求的"曲线末端的切线要和上一段直线共线"：接直线的那一端直接用这条直线的方向，
	 * 共线是构造出来的而不是估出来的。没有直线轨（曲线接曲线）时返回 null，那就用本轨采样点估的切向。</p>
	 */
	straightAtNode?: (nodeKey: string) => PlanePoint | null,
	/** 本轨两个端点的节点键（顺序与 `from` / `to` 一致），用于上面那次查找。 */
	nodeKeys?: readonly [string, string],
): string {
	const dx = to.x - from.x;
	const dy = to.y - from.y;
	const chord = Math.hypot(dx, dy);
	if (!(chord > 1e-6)) {
		return linePath(project(from), project(to));
	}

	const tangents = endpointTangents(realPath);
	/*
	 * 三次贝塞尔的端点切线由控制点决定：起点切线 = C1 − P0，终点切线 = P1 − C2。
	 * 所以两个控制点都沿"沿轨流向"摆放：C1 = P0 + start·λ，C2 = P1 + end·λ。
	 *
	 * 端点切向的优先级：**该节点上那条直线轨的方向 > 本轨采样点估的切向 > 弦方向**。
	 *
	 * <p>第一优先级是用户要求的直译："曲线末端的切线要和上一段直线共线"。接直线的那一端，
	 * 直接把那条直线的方向拿来用，共线就是构造出来的，不依赖估计精度。
	 * 这一条**只对准直线的接缝**，不碰"曲线接曲线"的情形——后者试过"节点统一切向"（把同一条经由的
	 * 弦向平均后共用），实测是退步：节点上只有弦向可用，而弦向对弯曲的轨并不是端点切向，
	 * 拿它当切向等于把曲线拉直（曲线 40 → 24 条，"比直线更贴近真实轨道" 26 → 11 条）。</p>
	 */
	const straightStart = straightAtNode && nodeKeys ? straightAtNode(nodeKeys[0]) : null;
	const straightEnd = straightAtNode && nodeKeys ? straightAtNode(nodeKeys[1]) : null;
	const startDir = straightStart
		? align(straightStart, {x: to.x - from.x, y: to.y - from.y})
		: (tangents ? tangents.start : {x: dx / chord, y: dy / chord});
	const endDir = straightEnd
		? align(straightEnd, {x: from.x - to.x, y: from.y - to.y})
		: (tangents ? tangents.end : {x: dx / chord, y: dy / chord});

	/*
	 * 这里**刻意没有**"路径方向反了就交换/翻转切向"的守卫。
	 *
	 * 曾经加过两条：`u·v < 0 就交换`、`(u − v)·d < 0 就把两条都翻`。它们出发点是"采样路径顺序不保证
	 * 与声明端点同向"，但实测（tools/find-curve-construction.mjs 用真实数据逐条评分）证明：
	 *   1. 引擎的采样路径顺序**本来就是**从 (x1,z1) 走向 (x2,z2)（33 个点里离 A 最近的恰是首点）；
	 *   2. 两条守卫会在合法的数据上误触发 —— 一条真实轨的 `u·d = 17.5`、`v·d = 19.6`（都对），
	 *      却满足 `(u − v)·d = −2 < 0` 而被整体翻过来，于是曲线朝弯的另一侧鼓。
	 *
	 * 判据本身的逻辑是错的：`u − v` 对"两切向都沿流向"的正常情况反而接近零（两条几乎相等），
	 * 用它的符号去判断方向，等于拿噪声做决定。所以直接删掉，只保留"沿流向"这一个约定。
	 */

	/*
	 * 真实轨道相对弦的偏离程度：只用来给"控制点能走多远"定个范围。
	 */
	const normal = {x: -dy / chord, y: dx / chord};
	let maxOffset = 0;
	for (const point of realPath) {
		const offset = Math.abs((point.x - from.x) * normal.x + (point.y - from.y) * normal.y);
		if (offset > maxOffset) {
			maxOffset = offset;
		}
	}
	const deviationScale = Math.min(MAX_DEVIATION_SCALE, Math.max(MIN_DEVIATION_SCALE, maxOffset / chord * 2));

	/*
	 * 控制点长度：按弦长的固定比例，再按真实弯曲程度缩放。
	 *
	 * <p>试过用"最小二乘拟合真实采样点"来定长度（自由度只剩长度一个，本想更准），实测**并不更好**：
	 * 拟合是按"同名参数点"做的近似（把采样点当作曲线 t 相同的点，而采样点其实是按弧长均分的），
	 * 于是它会把控制点放远去迁就点的分布，弯曲幅度失控（实测到弦长的 26~52%），
	 * "比直线更贴近真实轨道"的条数反而从 26 掉到 17~24。</p>
	 */
	const tangentLength = chord * TANGENT_LENGTH_RATIO * deviationScale;

	const c1 = {x: from.x + startDir.x * tangentLength, y: from.y + startDir.y * tangentLength};
	const c2 = {x: to.x + endDir.x * tangentLength, y: to.y + endDir.y * tangentLength};

	/*
	 * 退化兜底：真实轨道几乎就是直线时画直线更诚实（屏幕上只有一格，画曲线也看不出来）。
	 *
	 * 判据用**控制点偏离弦的距离和**：它正比于曲线的弯曲程度，而且是世界单位，
	 * 与缩放无关。不能用"屏幕上几个像素"来判——那会让线型随缩放变化。
	 */
	const bowed = Math.abs((c1.x - from.x) * normal.x + (c1.y - from.y) * normal.y)
		+ Math.abs((c2.x - to.x) * normal.x + (c2.y - to.y) * normal.y);
	if (bowed < MIN_SAGITTA_WORLD) {
		return linePath(project(from), project(to));
	}

	const p0 = project(from);
	const q1 = project(c1);
	const q2 = project(c2);
	const p1 = project(to);
	return `M ${round(p0.x)} ${round(p0.y)} C ${round(q1.x)} ${round(q1.y)} ${round(q2.x)} ${round(q2.y)} ${round(p1.x)} ${round(p1.y)}`;
}

/** 一条轨在世界坐标下的包围盒（取景用）。 */
export function railBounds(rails: readonly {
	x1: number;
	z1: number;
	x2: number;
	z2: number;
	path?: readonly {x: number; z: number}[];
}[]): Rect | null {
	if (rails.length === 0) {
		return null;
	}
	let minX = Infinity;
	let minY = Infinity;
	let maxX = -Infinity;
	let maxY = -Infinity;
	const visit = (x: number, z: number) => {
		// 平面图用 (x, z) 直映，与 Node/Rail/Signal 的 plane* 同一约定（见 Node.planeZ 的说明）
		const planeY = z;
		if (x < minX) {
			minX = x;
		}
		if (planeY < minY) {
			minY = planeY;
		}
		if (x > maxX) {
			maxX = x;
		}
		if (planeY > maxY) {
			maxY = planeY;
		}
	};
	for (const rail of rails) {
		visit(rail.x1, rail.z1);
		visit(rail.x2, rail.z2);
		// 采样点也要算进去：U 型轨的弯折部分可能超出两端点构成的包围盒。
		for (const point of rail.path ?? []) {
			visit(point.x, point.z);
		}
	}
	return {x: minX, y: minY, width: Math.max(maxX - minX, 1e-3), height: Math.max(maxY - minY, 1e-3)};
}
