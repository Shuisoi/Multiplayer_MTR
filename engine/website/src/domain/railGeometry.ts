import type {Rect} from "./camera";

/**
 * 轨的平面几何。
 *
 * <h3>线型规则（用户要求）</h3>
 * <p>"若 x 或 y 任一相同，则直线，其余要以曲线表示"——平面图上两个端点在**同一条轴上**
 * （x 相同或 z 相同，即水平/垂直）时画直线；两端点两个轴都不同（斜向）时画曲线。</p>
 *
 * <h3>曲线是"方向正确的简化版"，不照搬真实几何（用户要求）</h3>
 * <p>引擎的轨由两段圆弧拼成，可以弯成 U 型甚至 S 型。曾经把引擎沿里程采样的折线直接画出来，
 * 结果与用户的意图不符：<b>"不需要真的把实际情况画上去，我们绘制的是方向正确的简化版就行了"</b>。
 * 所以曲线现在是一条简化的二次贝塞尔：</p>
 * <ul>
 *   <li><b>弯向</b>取真实轨道的弯向（由引擎采样点相对弦的偏移符号决定），所以方向是对的；</li>
 *   <li><b>弯曲程度</b>按弦长的固定比例（并设上限），不跟着真实几何放大到 25~30% 那种幅度；</li>
 *   <li>U 型 / S 型都简化成一条同向的弧——这正是"简化"的含义。</li>
 * </ul>
 *
 * <p>于是这个文件需要真实采样点，但只用它来决定**弯向**，不用它画线。
 * 前端仍然不"编"轨道走向：走向来自引擎，只是画法简化。</p>
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

/** 简化曲线的矢高相对弦长的比例，以及上限。 */
const CURVE_SAGITTA_RATIO = 0.14;
const MAX_CURVE_SAGITTA_RATIO = 0.18;

/**
 * 矢高小于这个值（**世界单位**）时直接画直线。
 *
 * <p><b>阈值必须与世界坐标同尺度，不能按屏幕像素给。</b>实测踩过一次：整图取景后比例只有
 * ~0.1 px/世界单位（覆盖 1600 格的线路），一段弦长 90 世界单位的轨在屏幕上不到 9 像素，
 * 而当时按"矢高 &lt; 0.6px 就画直线"判断，短轨的矢高换算过去只有零点几像素，
 * 于是 93 条本该画曲线的轨有 74 条被压成了直线——线型随缩放变化，正是最不该发生的事。</p>
 *
 * <p>取值 0.5 格：实测 41 条斜向轨的矢高最小的也有 0.64 格，所以这个阈值不会吃掉任何一条；
 * 留它的作用只是兜住"端点几乎重合"的退化情况（那种轨在屏幕上本来也看不出弯）。
 * 曾经取 2 格，结果 41 条斜向轨里有 26 条被改成直线——阈值比数据里最小的矢高还大，
 * 规则就形同虚设。</p>
 */
const MIN_SAGITTA_WORLD = 0.5;

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

/** 两点直线路径。 */
export function linePath(from: PlanePoint, to: PlanePoint): string {
	return `M ${round(from.x)} ${round(from.y)} L ${round(to.x)} ${round(to.y)}`;
}

/**
 * 简化曲线：一条二次贝塞尔，弯向与真实轨道一致。**在世界坐标里算，再投影到屏幕。**
 *
 * <p>为什么用二次贝塞尔而不是 SVG 的圆弧指令（`A`）：`A` 的弯向由 sweep 标志决定，
 * 要"弯到左边"就得反算 sweep 与半径的组合，读代码的人无法一眼确认方向对不对；
 * 而二次贝塞尔直接把控制点放在中点的垂直偏移位置上，**弯向就是控制点所在的侧**，
 * 自解释。而且曲线必过"中点 + 矢高"那个点，矢高说什么就是什么，不用再解半径。
 * （二次贝塞尔在中点的位置 = 两端点与控制点的平均，所以控制点偏移量取矢高的 2 倍。）</p>
 *
 * @param from 起点（世界**平面**坐标：x 与 -z）
 * @param to 终点（世界平面坐标）
 * @param realPath 真实轨道的采样点（世界平面坐标）。只用它决定弯向；为空则默认向一侧弯。
 * @param project 世界平面坐标 → 屏幕坐标。所有阈值在世界坐标里判完，最后才投影。
 */
export function simplifiedCurvePath(
	from: PlanePoint,
	to: PlanePoint,
	realPath: readonly PlanePoint[],
	project: (point: PlanePoint) => PlanePoint,
): string {
	const dx = to.x - from.x;
	const dy = to.y - from.y;
	const chord = Math.hypot(dx, dy);
	if (!(chord > AXIS_TOLERANCE)) {
		// 两端点几乎重合：投影后退化成一点，交给调用方按直线处理（屏幕上就是一格）。
		return linePath(project(from), project(to));
	}

	// 弦的单位法线（世界平面坐标里定义，弯向的"侧"在世界里是稳定的）。
	const nx = -dy / chord;
	const ny = dx / chord;
	const mid = {x: (from.x + to.x) / 2, y: (from.y + to.y) / 2};

	// 真实采样点相对弦的偏移：取绝对值最大的那一侧的符号作为弯向。
	let side = 0;
	let maxOffset = 0;
	for (const point of realPath) {
		const offset = (point.x - from.x) * nx + (point.y - from.y) * ny;
		if (Math.abs(offset) > maxOffset) {
			maxOffset = Math.abs(offset);
			side = Math.sign(offset);
		}
	}
	if (side === 0) {
		// 采样点缺失或完全落在弦上（真实轨道是直的）：给一个默认弯向，
		// 保证"斜向就走曲线"这条规则统一，不因数据缺失而整体变直。
		side = 1;
	}

	// 弯曲程度：按弦长的固定比例，再按真实弯曲程度收敛，并设上限——简化，但不能看不出来。
	const ratio = Math.min(
		CURVE_SAGITTA_RATIO,
		MAX_CURVE_SAGITTA_RATIO,
		Math.max(0.04, maxOffset / chord * 0.6),
	);
	const sagitta = chord * ratio;
	if (sagitta < MIN_SAGITTA_WORLD) {
		return linePath(project(from), project(to));
	}

	// 二次贝塞尔中点 = (P0 + 2C + P2) / 4，所以控制点到中点的距离取矢高的 2 倍。
	const control = {x: mid.x + nx * side * sagitta * 2, y: mid.y + ny * side * sagitta * 2};
	const p0 = project(from);
	const c = project(control);
	const p1 = project(to);
	return `M ${round(p0.x)} ${round(p0.y)} Q ${round(c.x)} ${round(c.y)} ${round(p1.x)} ${round(p1.y)}`;
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
		// 平面图用 (x, -z)，与世界 z 越大越靠上的约定一致。
		const planeY = -z;
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
