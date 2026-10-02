/*
 * designer.mjs —— 工作室绘制器的**纯函数层**：几何换算、命中测试、吸附、微调、JSON 路径读写、条件构造器。
 *
 * 为什么单独一层、而且一个 DOM 都不碰：这一层的每条判据都要能被 `designer-selftest.mjs` 在 Node 里
 * 逐条断言（不需要浏览器、不需要服务器）。页面（app.mjs）只做"把事件变成这里的调用 + 把结果画出来"。
 *
 * ## 坐标口径（照 01-设计/车辆动态面-面文档格式.md §3，一个字都不改）
 *
 *   · 文档坐标是**比例**：`x`/`w` 乘牌宽，`y`/`h`/`size`/`radius` 乘牌高；
 *   · `y = 0` 是**牌底**、`y = 1` 是牌顶（**y 轴朝上**），而画布像素 y 朝下 —— 所以像素换算要翻过来；
 *   · 各类元素的锚点不一样（这是拖拽最容易错的一条）：
 *       rect / roundrect / image → `x,y` 是**左下角**，矩形 = x..x+w, y..y+h；
 *       text / circle / arc / gauge → `x,y` 是一个**点**（文本按字高估一个盒，竖直居中）；
 *       line → `x,y` 是**起点**（终点 x2,y2）。
 *
 * 这一层里的所有"盒"都用 `{x0, y0, x1, y1}`（比例空间、y 朝上、x0 ≤ x1、y0 ≤ y1）。
 *
 * ## 单位与 aspect
 *
 * `radius` 乘的是**牌高**，所以同一个半径在 x 方向占的比例是 `radius / aspect`
 * （`aspect = 牌宽 / 牌高`）。凡是圆形的东西（circle/arc/gauge）与线宽都需要 aspect 才判得准 ——
 * 于是 `elementBox`/`hitTest` 都接受 `{aspect}`，缺省 1（正方形，测试里用得上）。
 */

// 编辑器的过滤器清单（8 个内置）—— 属性面板的过滤器下拉与自检拿它判"这个过滤器名认不认识"。
// 这一层原本零依赖；引 text.mjs 只为一件事：FILTERS 是随包发行的最小集，不能在这里再抄一份
// （抄一份就会漂，而漂的表现是"内置过滤器被当成不认识"）。
import { FILTERS } from './text.mjs';

// ---- 常量 ---------------------------------------------------------------------------------------

/** 比例键的合法范围。 */
export const PROPORTION_MIN = 0;
export const PROPORTION_MAX = 1;
/** `size`（字高）的合法范围（格式文档 §5）。 */
export const SIZE_MIN = 0.02;
export const SIZE_MAX = 1.5;
/** 拖拽缩放时 w/h 的最小值：再小就不是"框"，是"点"，改不回来。 */
export const MIN_BOX_SIZE = 0.02;
/** 吸附阈值（比例）。 */
export const SNAP_THRESHOLD = 0.01;
/** 方向键微调步长：默认 0.005，按住 Shift 变细（0.001）。 */
export const NUDGE_STEP = 0.005;
export const NUDGE_FINE_STEP = 0.001;
/** 缩放把手：4 角 + 4 边。 */
export const HANDLES = ['nw', 'n', 'ne', 'e', 'se', 's', 'sw', 'w'];

/**
 * 行式条件构造器能生成的比较算子（**从 32 个算子清单里挑出来的子集**，
 * 见 `designer-selftest.mjs` 的断言：这里不许出现清单外的名字）。
 */
export const ROW_OPERATORS = ['==', '!=', '===', '!==', '>', '>=', '<', '<=', 'in'];
/** 两个"快捷模板"的伪算子名（不是 JSONLogic 算子，是本构造器的糖）。 */
export const PSEUDO_OPERATORS = ['has', 'empty'];
/** 行算子里哪些是"两边都是值"的比较（`in` 的右操作数是列表，构造器里当文本处理）。 */
export const ROW_OPERATOR_LABELS = {
	'==': '等于 ==',
	'!=': '不等 !=',
	'===': '严格等于 ===',
	'!==': '严格不等 !==',
	'>': '大于 >',
	'>=': '大于等于 >=',
	'<': '小于 <',
	'<=': '小于等于 <=',
	'in': '包含于 in',
	has: '有值（!! var）',
	empty: '为空（! !! var）',
};

/**
 * 32 个算子 —— **与 Java `MmtrFaceLogic.operators()` 逐项同序**的镜像。
 * 页面从 `logic.mjs` 的 `OPERATORS` 取清单（那份与 verify_face.js 逐项核对过）；
 * 这里留一份是为了让纯函数层能独立断言"构造器没造出清单外的算子"。
 */
export const LOGIC_OPERATORS = [
	'var', 'if', 'and', 'or', '!', '!!',
	'==', '!=', '===', '!==', '<', '<=', '>', '>=',
	'+', '-', '*', '/', '%', 'min', 'max',
	'cat', 'substr', 'in', 'missing', 'missing_some', '?:',
	'some', 'all', 'none', 'filter', 'map',
];

// ---- 小工具 -------------------------------------------------------------------------------------

/** 通用的"非数就回退 + 钳进区间"。 */
export function clampNumber(value, min, max, fallback) {
	const number = typeof value === 'string' ? Number(value.trim()) : Number(value);
	if (!Number.isFinite(number)) {
		return fallback;
	}
	return Math.max(min, Math.min(max, number));
}

/** 比例键：钳到 0..1（非数用 fallback）。 */
export function clampProportion(value, fallback = 0.5) {
	return clampNumber(value, PROPORTION_MIN, PROPORTION_MAX, fallback);
}

/** `size` 键：钳到 0.02..1.5（非数用 fallback）。 */
export function clampSize(value, fallback = 0.4) {
	return clampNumber(value, SIZE_MIN, SIZE_MAX, fallback);
}

/** JSON 深拷贝（撤销栈与"拖拽开始时的快照"都用它）。 */
export function cloneJson(value) {
	if (value === undefined) {
		return undefined;
	}
	return JSON.parse(JSON.stringify(value));
}

/** 两份 JSON 是不是逐字一样（判断"这一拖到底改了没有"）。 */
export function sameJson(left, right) {
	return JSON.stringify(left) === JSON.stringify(right);
}

/** 把一份 JSON 的内容**原地**换成另一份（保住对象身份：解析/路径都指着它）。 */
export function replaceRoot(target, source) {
	if (target === null || typeof target !== 'object' || Array.isArray(target)) {
		throw new Error('replaceRoot 只能用在普通对象上');
	}
	for (const key of Object.keys(target)) {
		delete target[key];
	}
	if (source !== null && typeof source === 'object' && !Array.isArray(source)) {
		for (const [key, value] of Object.entries(source)) {
			target[key] = cloneJson(value);
		}
	}
	return target;
}

/** 读一个数字键（与 document.mjs 的口径一致：数字直接用，数字串解析，其余用 fallback）。 */
export function readNumber(raw, key, fallback) {
	if (raw === null || typeof raw !== 'object' || !Object.prototype.hasOwnProperty.call(raw, key)) {
		return fallback;
	}
	const value = raw[key];
	if (typeof value === 'number' && Number.isFinite(value)) {
		return value;
	}
	if (typeof value === 'string' && /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(value.trim())) {
		return Number(value.trim());
	}
	return fallback;
}

/** 轮询一个值（页面里改完 JSON 后重新拿引用用得上）。 */
export function isPlainObject(value) {
	return value !== null && typeof value === 'object' && !Array.isArray(value);
}

// ---- 比例 ↔ 像素 --------------------------------------------------------------------------------

/** 比例 x → 画布像素 x。 */
export function proportionToPxX(x, widthPx) {
	return x * widthPx;
}

/** 比例 y → 画布像素 y（**y 轴翻过来**：比例 0 在牌底 = 像素最下一行）。 */
export function proportionToPxY(y, heightPx) {
	return (1 - y) * heightPx;
}

/** 画布像素 x → 比例 x。 */
export function pxToProportionX(px, widthPx) {
	if (!(widthPx > 0)) {
		return 0;
	}
	return px / widthPx;
}

/** 画布像素 y → 比例 y（**y 轴翻过来**）。 */
export function pxToProportionY(py, heightPx) {
	if (!(heightPx > 0)) {
		return 0;
	}
	return 1 - py / heightPx;
}

// ---- 元素几何 -----------------------------------------------------------------------------------

/** 各类型的"锚点"语义（见文件头）。 */
export function elementAnchor(type) {
	switch (String(type || '').toLowerCase()) {
		case 'rect':
		case 'roundrect':
		case 'image':
			return 'corner';
		case 'line':
			return 'start';
		default:
			return 'center';
	}
}

/** 各类型专属半径键的缺省值（格式文档 §5 的类型表）。 */
export function defaultRadius(type) {
	switch (String(type || '').toLowerCase()) {
		case 'roundrect':
			return 0.08;
		case 'circle':
			return 0.1;
		case 'arc':
			return 0.4;
		case 'gauge':
			return 0.44;
		default:
			return 0.05;
	}
}

/**
 * 文本盒的宽度（比例 x）估计：**按字高估一个盒**。
 * `aspect = 牌宽 / 牌高`；字高是牌高比例 `size`，每个字大约 0.6 个高那么宽。
 * （页面里能用 canvas 量出真宽度时会把真宽度传进来 —— 这里只是给没有 canvas 的地方兜底。）
 */
export function estimateTextWidth(text, size, aspect = 1) {
	const source = text === null || text === undefined ? '' : String(text);
	const count = Array.from(source).length;
	const safeAspect = aspect > 0 ? aspect : 1;
	return count * 0.6 * clampSize(size) / safeAspect;
}

/**
 * 一个元素的**命中盒**（比例空间、y 朝上）。
 *
 * @param {object} element  原始 JSON 元素（`type`/`x`/`y`/… 直接从 anchors JSON 里来）
 * @param {object} options  `{aspect, textWidth}`：aspect = 牌宽/牌高；textWidth = 文本真宽（比例 x，可省）
 * @returns {{x0:number,y0:number,x1:number,y1:number,anchor:string}}
 */
export function elementBox(element, options = {}) {
	const raw = element === null || typeof element === 'object' ? element : {};
	const type = String(raw.type || '').trim().toLowerCase();
	const aspect = Number.isFinite(options.aspect) && options.aspect > 0 ? options.aspect : 1;
	const x = readNumber(raw, 'x', 0.5);
	const y = readNumber(raw, 'y', 0.5);
	const anchor = elementAnchor(type);
	let x0;
	let y0;
	let x1;
	let y1;

	if (anchor === 'corner') {
		const w = Math.max(0, readNumber(raw, 'w', 0));
		const h = Math.max(0, readNumber(raw, 'h', 0));
		x0 = x;
		y0 = y;
		x1 = x + w;
		y1 = y + h;
	} else if (anchor === 'start') {
		const x2 = readNumber(raw, 'x2', x);
		const y2 = readNumber(raw, 'y2', y);
		// 线宽是牌高比例；x 方向要除 aspect 才是"占多少比例 x"
		const half = Math.max(readNumber(raw, 'width', 0.03), 0.001) / 2;
		x0 = Math.min(x, x2) - half / aspect;
		x1 = Math.max(x, x2) + half / aspect;
		y0 = Math.min(y, y2) - half;
		y1 = Math.max(y, y2) + half;
	} else if (type === 'text') {
		const size = clampSize(readNumber(raw, 'size', 0.4));
		const width = Number.isFinite(options.textWidth)
			? Math.abs(options.textWidth)
			: estimateTextWidth(raw.text, size, aspect);
		const align = String(raw.align === null || raw.align === undefined ? '' : raw.align).trim().toLowerCase();
		const left = align === 'left' ? x : (align === 'right' ? x - width : x - width / 2);
		x0 = left;
		x1 = left + width;
		y0 = y - size / 2;
		y1 = y + size / 2;
	} else {
		// circle / arc / gauge（以及任何认不出的类型都当"中心 + 半径"）
		const radius = readNumber(raw, 'radius', defaultRadius(type));
		const radiusX = radius / aspect;
		x0 = x - radiusX;
		x1 = x + radiusX;
		y0 = y - radius;
		y1 = y + radius;
	}
	return { x0: Math.min(x0, x1), y0: Math.min(y0, y1), x1: Math.max(x0, x1), y1: Math.max(y0, y1), anchor };
}

/** 盒中心（比例空间）。 */
export function boxCenter(box) {
	return { x: (box.x0 + box.x1) / 2, y: (box.y0 + box.y1) / 2 };
}

/** 点到线段的距离（高度归一化空间 —— 圆与该距离的比较才有意义）。 */
function distanceToSegment(px, py, x1, y1, x2, y2) {
	const dx = x2 - x1;
	const dy = y2 - y1;
	const lengthSquared = dx * dx + dy * dy;
	if (lengthSquared <= 0) {
		return Math.hypot(px - x1, py - y1);
	}
	let t = ((px - x1) * dx + (py - y1) * dy) / lengthSquared;
	t = Math.max(0, Math.min(1, t));
	return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
}

/**
 * 命中测试：这个点（比例空间、y 朝上）落在元素上吗？
 *
 * · rect/roundrect/image：左闭右开的矩形（**左下角是锚点**）；
 * · text/circle/arc/gauge：中心锚点的盒；
 * · line：到线段的距离 ≤ 线宽一半与容差的较大者（细线也要点得中）。
 */
export function hitTest(element, point, options = {}) {
	const raw = element === null || typeof element === 'object' ? element : {};
	const type = String(raw.type || '').trim().toLowerCase();
	const aspect = Number.isFinite(options.aspect) && options.aspect > 0 ? options.aspect : 1;
	const tolerance = Number.isFinite(options.tolerance) ? options.tolerance : 0.012;
	const px = Number(point && point.x);
	const py = Number(point && point.y);
	if (!Number.isFinite(px) || !Number.isFinite(py)) {
		return false;
	}
	if (type === 'line') {
		const x1 = readNumber(raw, 'x', 0.5);
		const y1 = readNumber(raw, 'y', 0.5);
		const x2 = readNumber(raw, 'x2', x1);
		const y2 = readNumber(raw, 'y2', y1);
		const half = Math.max(readNumber(raw, 'width', 0.03), 0.001) / 2;
		const limit = Math.max(half, tolerance);
		// 换成"高度归一化"空间再算距离：x 乘 aspect 就与 y 同单位了
		return distanceToSegment(px * aspect, py, x1 * aspect, y1, x2 * aspect, y2) <= limit;
	}
	const box = elementBox(raw, options);
	return px >= box.x0 && px <= box.x1 && py >= box.y0 && py <= box.y1;
}

/** 从**上往下**找第一个被点中的元素（数组顺序 = 绘制顺序 ⇒ 后面的盖前面的）。返回下标或 -1。 */
export function pickTopmost(elements, point, options = {}) {
	if (!Array.isArray(elements)) {
		return -1;
	}
	for (let index = elements.length - 1; index >= 0; index--) {
		if (hitTest(elements[index], point, options)) {
			return index;
		}
	}
	return -1;
}

// ---- 缩放把手 -----------------------------------------------------------------------------------

/** 8 个把手的位置（比例空间）。 */
export function handlePoints(box) {
	return {
		nw: [box.x0, box.y1],
		n: [(box.x0 + box.x1) / 2, box.y1],
		ne: [box.x1, box.y1],
		e: [box.x1, (box.y0 + box.y1) / 2],
		se: [box.x1, box.y0],
		s: [(box.x0 + box.x1) / 2, box.y0],
		sw: [box.x0, box.y0],
		w: [box.x0, (box.y0 + box.y1) / 2],
	};
}

/** 哪个把手在光标下（`radius` 是比例容差）。 */
export function handleAtPoint(box, point, radius = 0.02) {
	const points = handlePoints(box);
	for (const name of HANDLES) {
		const [hx, hy] = points[name];
		if (Math.abs(point.x - hx) <= radius && Math.abs(point.y - hy) <= radius) {
			return name;
		}
	}
	return null;
}

/**
 * 缩放：`rect` 语义（x,y = 左下角，w,h 向右上长），把手拖动 `dx`,`dy`（比例）。
 * w/h 有最小值 `MIN_BOX_SIZE`，x/y/w/h 一律钳在 0..1。
 */
export function resizeBox(box, handle, dx, dy, minSize = MIN_BOX_SIZE) {
	let left = box.x0;
	let bottom = box.y0;
	let right = box.x1;
	let top = box.y1;
	const name = String(handle || '');
	if (name.includes('w')) {
		left = clampProportion(box.x0 + dx, box.x0);
	}
	if (name.includes('e')) {
		right = clampProportion(box.x1 + dx, box.x1);
	}
	if (name.includes('s')) {
		bottom = clampProportion(box.y0 + dy, box.y0);
	}
	if (name.includes('n')) {
		top = clampProportion(box.y1 + dy, box.y1);
	}
	// 拖过头 ⇒ 用最小值顶回来（不要把框拖成负的）
	if (right - left < minSize) {
		if (name.includes('w')) {
			left = Math.max(PROPORTION_MIN, right - minSize);
			if (right - left < minSize) {
				right = Math.min(PROPORTION_MAX, left + minSize);
			}
		} else {
			right = Math.min(PROPORTION_MAX, left + minSize);
		}
	}
	if (top - bottom < minSize) {
		if (name.includes('s')) {
			bottom = Math.max(PROPORTION_MIN, top - minSize);
			if (top - bottom < minSize) {
				top = Math.min(PROPORTION_MAX, bottom + minSize);
			}
		} else {
			top = Math.min(PROPORTION_MAX, bottom + minSize);
		}
	}
	return { x: left, y: bottom, w: right - left, h: top - bottom };
}

// ---- 吸附 ---------------------------------------------------------------------------------------

/**
 * 吸附候选线：**0 / 0.5 / 1**（边缘与中线）+ 其他元素的同一条边（左/中/右、下/中/上）。
 * @param {Array} elements 当前页的元素（会跳过 `skipIndex` 那个自己）
 * @param {number} skipIndex 正在拖的元素下标
 * @param {object} options `{aspect, textWidthOf(element)}`
 */
export function snapLines(elements, skipIndex = -1, options = {}) {
	const xs = [0, 0.5, 1];
	const ys = [0, 0.5, 1];
	if (Array.isArray(elements)) {
		const measure = typeof options.measure === 'function' ? options.measure : () => null;
		elements.forEach((element, index) => {
			if (index === skipIndex) {
				return;
			}
			const box = elementBox(element, Object.assign({}, options, { textWidth: measure(element) }));
			xs.push(box.x0, (box.x0 + box.x1) / 2, box.x1);
			ys.push(box.y0, (box.y0 + box.y1) / 2, box.y1);
		});
	}
	return { x: xs.map(value => clampProportion(value)), y: ys.map(value => clampProportion(value)) };
}

/** 一个值最接近哪条候选线（阈值内才算）。返回 `{delta, target, distance}` 或 null。 */
export function snapValue(value, candidates, threshold = SNAP_THRESHOLD) {
	let best = null;
	for (const candidate of candidates) {
		const delta = candidate - value;
		const distance = Math.abs(delta);
		if (distance <= threshold && (best === null || distance < best.distance)) {
			best = { delta, target: candidate, distance };
		}
	}
	return best;
}

/**
 * 整个盒子的吸附：三条竖边各自去够 x 候选线、三条横边各自去够 y 候选线，取最近的那条。
 * 返回 `{dx, dy, hits}`：`hits[i] = {axis, edge, target, distance}`（页面拿它画提示线写提示字）。
 */
export function snapBox(box, targetsX, targetsY, threshold = SNAP_THRESHOLD) {
	const xEdges = [['left', box.x0], ['center', (box.x0 + box.x1) / 2], ['right', box.x1]];
	const yEdges = [['bottom', box.y0], ['middle', (box.y0 + box.y1) / 2], ['top', box.y1]];
	const hits = [];
	let dx = 0;
	let dy = 0;
	let bestX = null;
	for (const [edge, value] of xEdges) {
		const found = snapValue(value, targetsX, threshold);
		if (found !== null && (bestX === null || found.distance < bestX.distance)) {
			bestX = Object.assign({ edge }, found);
		}
	}
	if (bestX !== null) {
		dx = bestX.delta;
		hits.push({ axis: 'x', edge: bestX.edge, target: bestX.target, distance: bestX.distance });
	}
	let bestY = null;
	for (const [edge, value] of yEdges) {
		const found = snapValue(value, targetsY, threshold);
		if (found !== null && (bestY === null || found.distance < bestY.distance)) {
			bestY = Object.assign({ edge }, found);
		}
	}
	if (bestY !== null) {
		dy = bestY.delta;
		hits.push({ axis: 'y', edge: bestY.edge, target: bestY.target, distance: bestY.distance });
	}
	return { dx, dy, hits };
}

/** 吸附提示的一句话（状态栏与画布上的浮标共用）。 */
export function snapLabel(hit) {
	if (!hit) {
		return '';
	}
	const rounded = Math.round(hit.target * 1000) / 1000;
	return '吸附到 ' + rounded;
}

// ---- 拖拽与微调 ---------------------------------------------------------------------------------

/**
 * 按住 Shift 时"只改一个轴"：哪个轴动得多改哪个（打平算 x）。
 * @param {number} dx 比例位移
 * @param {number} dy 比例位移
 */
export function constrainDrag(dx, dy, shift) {
	if (!shift) {
		return { dx, dy };
	}
	return Math.abs(dx) >= Math.abs(dy) ? { dx, dy: 0 } : { dx: 0, dy };
}

/** 方向键的步长（Shift = 细调）。 */
export function nudgeStep(shift) {
	return shift ? NUDGE_FINE_STEP : NUDGE_STEP;
}

/** 方向键的 4 个键 → 轴与符号（y 朝上：↑ 是 +y）。 */
export const ARROW_KEYS = {
	ArrowLeft: { axis: 'x', direction: -1 },
	ArrowRight: { axis: 'x', direction: 1 },
	ArrowUp: { axis: 'y', direction: 1 },
	ArrowDown: { axis: 'y', direction: -1 },
};

/**
 * 微调一个比例键（x/y 钳 0..1；`size` 由调用方改用 `clampSize`）。
 * @returns {{value:number, changed:boolean}}
 */
export function nudgeProportion(value, direction, step) {
	const before = clampProportion(value, 0.5);
	const after = clampProportion(before + direction * step, before);
	return { value: after, changed: after !== before };
}

/**
 * 微调整个元素的一个轴：改 `x` 或 `y`（其余键不动）。
 * @returns {{key:string, before:number, after:number, changed:boolean}}
 */
export function nudgeElement(element, axis, direction, step) {
	const raw = isPlainObject(element) ? element : {};
	const key = axis === 'x' ? 'x' : 'y';
	const before = clampProportion(readNumber(raw, key, 0.5));
	const { value, changed } = nudgeProportion(before, direction, step);
	return { key, before, after: value, changed };
}

/** 副本偏移一点点（Alt+拖 = 复制），并保证还落在 0..1 里。 */
export function cloneWithOffset(element, offset = 0.02) {
	const copy = cloneJson(element);
	if (!isPlainObject(copy)) {
		return copy;
	}
	copy.x = clampProportion(readNumber(copy, 'x', 0.5) + offset, 0.5);
	copy.y = clampProportion(readNumber(copy, 'y', 0.5) - offset, 0.5);
	if (Object.prototype.hasOwnProperty.call(copy, 'x2')) {
		copy.x2 = clampProportion(readNumber(copy, 'x2', copy.x) + offset, 0.5);
	}
	if (Object.prototype.hasOwnProperty.call(copy, 'y2')) {
		copy.y2 = clampProportion(readNumber(copy, 'y2', copy.y) - offset, 0.5);
	}
	return copy;
}

// ---- JSON 路径读写 ------------------------------------------------------------------------------

/**
 * 把一条人话路径拆成段：`faces.pid_1.elements[2].x` → `['faces','pid_1','elements',2,'x']`。
 * 支持 `.键` 与 `[下标]` 两种写法（下标必须是整数）。
 */
export function parsePath(text) {
	const source = String(text === null || text === undefined ? '' : text).trim();
	if (source === '') {
		return [];
	}
	const parts = [];
	// 段之间允许 `.` 分隔（`[下标]` 自带分隔）；`\.?` 是为了让"分隔符"本身被消费掉，
	// 否则 `faces.pid_1` 会在 `.` 处报"路径写错了"（第一版就是这么错的）。
	const pattern = /\.?([^.[\]]+)|\[(\d+)\]/g;
	let match;
	let consumed = 0;
	while ((match = pattern.exec(source)) !== null) {
		if (match.index !== consumed) {
			throw new Error('路径写错了：「' + source + '」（第 ' + consumed + ' 个字符处）');
		}
		consumed = pattern.lastIndex;
		parts.push(match[1] !== undefined ? match[1] : Number(match[2]));
	}
	if (consumed !== source.length) {
		throw new Error('路径写错了：「' + source + '」');
	}
	return parts;
}

/** 段数组 → 人话路径。 */
export function formatPath(parts) {
	let out = '';
	for (const part of Array.isArray(parts) ? parts : []) {
		if (typeof part === 'number') {
			out += '[' + part + ']';
		} else {
			out += (out === '' ? '' : '.') + part;
		}
	}
	return out;
}

/** 按路径取值（取不到返回 undefined）。 */
export function getPath(root, path) {
	let node = root;
	for (const part of Array.isArray(path) ? path : []) {
		if (node === null || node === undefined || typeof node !== 'object') {
			return undefined;
		}
		node = node[part];
	}
	return node;
}

/** 按路径写值（中间缺的层自动建：数字段建数组，其余建对象）。返回根对象。 */
export function setPath(root, path, value) {
	const parts = Array.isArray(path) ? path : [];
	if (parts.length === 0) {
		throw new Error('setPath 的路径不能是空的');
	}
	let node = root;
	for (let i = 0; i < parts.length - 1; i++) {
		const part = parts[i];
		const nextPart = parts[i + 1];
		if (node[part] === null || typeof node[part] !== 'object') {
			node[part] = typeof nextPart === 'number' ? [] : {};
		}
		node = node[part];
	}
	node[parts[parts.length - 1]] = value;
	return root;
}

/**
 * 按路径删键：数组段用 splice（真删掉一项，不留洞），对象段用 delete。
 * @returns {boolean} 真删到了吗
 */
export function deletePath(root, path) {
	const parts = Array.isArray(path) ? path : [];
	if (parts.length === 0) {
		return false;
	}
	const parent = getPath(root, parts.slice(0, -1));
	const key = parts[parts.length - 1];
	if (Array.isArray(parent) && typeof key === 'number') {
		if (key < 0 || key >= parent.length) {
			return false;
		}
		parent.splice(key, 1);
		return true;
	}
	if (parent !== null && typeof parent === 'object' && Object.prototype.hasOwnProperty.call(parent, key)) {
		delete parent[key];
		return true;
	}
	return false;
}

// ---- 键表（schema.json）与未知键 -----------------------------------------------------------------

/** 键表里一条键的缺省值（**权威缺省就是 schema.json 的 default**，不许另写一份常量）。 */
export function defaultForKey(spec) {
	if (spec === null || typeof spec !== 'object' || !Object.prototype.hasOwnProperty.call(spec, 'default')) {
		return null;
	}
	return spec.default;
}

/** 一键的合法值表（enum 用）。 */
export function valuesForKey(spec) {
	return spec !== null && typeof spec === 'object' && Array.isArray(spec.values) ? spec.values.slice() : [];
}

/** 找出 raw 里"schema 不认识"的键（拼错键的防线）。 */
export function unknownKeys(raw, knownNames) {
	if (!isPlainObject(raw)) {
		return [];
	}
	const known = knownNames instanceof Set ? knownNames : new Set(knownNames || []);
	return Object.keys(raw).filter(key => !known.has(key));
}

/**
 * 只把**认得的键**写进目标（未知键只列出来给作者删，绝不偷偷写进文档）。
 * @returns {string[]} 被丢掉的键
 */
export function applyKnownPatch(target, patch, knownNames) {
	const known = knownNames instanceof Set ? knownNames : new Set(knownNames || []);
	const dropped = [];
	for (const [key, value] of Object.entries(patch || {})) {
		if (!known.has(key)) {
			dropped.push(key);
			continue;
		}
		if (value === undefined) {
			delete target[key];
		} else {
			target[key] = value;
		}
	}
	return dropped;
}

// ---- 条件构造器（可视化 JSONLogic） --------------------------------------------------------------

/** 一行：字段 + 算子 + 值。 */
export function newRow(field = '', operator = '==', value = '', valueKind = 'text') {
	return { kind: 'row', field: String(field || ''), operator: String(operator || '=='), value: value === undefined ? '' : value, valueKind };
}

/** 一组：与/或 + 若干行/组。 */
export function newGroup(operator = 'and', rows = null) {
	return { kind: 'group', operator: operator === 'or' ? 'or' : 'and', rows: Array.isArray(rows) ? rows : [newRow()] };
}

/** `{"!!":[{"var":"路径"}]}` —— "这个字段有值"。**这才是正确写法**（见格式文档 §6.2）。 */
export function hasValueExpr(path) {
	return { '!!': [{ var: String(path || '') }] };
}

/** `{"!":[{"!!":[{"var":"路径"}]}]}` —— "这个字段为空"。 */
export function emptyExpr(path) {
	return { '!': [hasValueExpr(path)] };
}

/** 这条表达式是不是"有值"？是就返回字段路径，否则 null。 */
export function isHasValueExpr(expression) {
	if (!isPlainObject(expression)) {
		return null;
	}
	const keys = Object.keys(expression);
	if (keys.length !== 1 || keys[0] !== '!!') {
		return null;
	}
	const args = expression['!!'];
	if (!Array.isArray(args) || args.length !== 1) {
		return null;
	}
	const inner = args[0];
	if (isPlainObject(inner) && Object.keys(inner).length === 1 && typeof inner.var === 'string') {
		return inner.var;
	}
	return null;
}

/** 这条表达式是不是"为空"！是就返回字段路径，否则 null。 */
export function isEmptyExpr(expression) {
	if (!isPlainObject(expression)) {
		return null;
	}
	const keys = Object.keys(expression);
	if (keys.length !== 1 || keys[0] !== '!') {
		return null;
	}
	const args = expression['!'];
	if (!Array.isArray(args) || args.length !== 1) {
		return null;
	}
	return isHasValueExpr(args[0]);
}

/** 一行的值对象：按 valueKind 收口成 JSON 里的那个字面量。 */
export function rowValue(row) {
	const kind = row && row.valueKind ? row.valueKind : 'text';
	if (kind === 'field') {
		return { var: String(row.value === undefined || row.value === null ? '' : row.value) };
	}
	if (kind === 'number') {
		const number = typeof row.value === 'number' ? row.value : Number(String(row.value).trim());
		return Number.isFinite(number) ? number : null;
	}
	if (kind === 'bool') {
		return row.value === true || String(row.value).trim().toLowerCase() === 'true';
	}
	if (kind === 'null') {
		return null;
	}
	return String(row.value === undefined || row.value === null ? '' : row.value);
}

/** 一行 → JSONLogic 子表达式。 */
export function buildRow(row) {
	const field = String((row && row.field) || '');
	const operator = String((row && row.operator) || '==');
	if (field === '') {
		return null;
	}
	if (operator === 'has') {
		return hasValueExpr(field);
	}
	if (operator === 'empty') {
		return emptyExpr(field);
	}
	if (!ROW_OPERATORS.includes(operator)) {
		return null;
	}
	return { [operator]: [{ var: field }, rowValue(row)] };
}

/**
 * 构造器树 → JSONLogic 表达式。
 * · 只有一行 ⇒ 直接是那一行（不套无谓的 `and`）；
 * · 多行/多组 ⇒ `{and:[...]}` / `{or:[...]}`；
 * · 空组 ⇒ null（页面据此提示"这一组还没有内容"）。
 */
export function buildLogic(node) {
	if (node === null || typeof node !== 'object') {
		return null;
	}
	if (node.kind === 'row') {
		return buildRow(node);
	}
	const rows = Array.isArray(node.rows) ? node.rows : [];
	const built = rows.map(buildLogic).filter(item => item !== null);
	if (built.length === 0) {
		return null;
	}
	if (built.length === 1) {
		return built[0];
	}
	return { [node.operator === 'or' ? 'or' : 'and']: built };
}

/** JSONLogic 里的一段 → 一行的"值"该怎么读。 */
function valueKindOf(value) {
	if (value === null) {
		return 'null';
	}
	if (typeof value === 'number') {
		return 'number';
	}
	if (typeof value === 'boolean') {
		return 'bool';
	}
	if (isPlainObject(value) && Object.keys(value).length === 1 && typeof value.var === 'string') {
		return 'field';
	}
	return 'text';
}

/** 一段 → 一行的值（构造器输入框里的那个东西）。 */
function valueToText(value, kind) {
	if (kind === 'field') {
		return value.var;
	}
	if (kind === 'null') {
		return '';
	}
	return value === null || value === undefined ? '' : String(value);
}

/** 一个"二元比较"，例如 `{"==":[{"var":"a"},1]}` → 一行。 */
function readCompareRow(operator, args) {
	if (!Array.isArray(args) || args.length < 2) {
		return null;
	}
	const left = args[0];
	if (!isPlainObject(left) || typeof left.var !== 'string') {
		return null;
	}
	const kind = valueKindOf(args[1]);
	return newRow(left.var, operator, valueToText(args[1], kind), kind);
}

/**
 * JSONLogic 表达式 → 构造器树（回读）。
 *
 * 认得出这些形状：`and`/`or` 数组、`!!` 有值、`!` + `!!` 为空、9 个比较算子 + `{"var":路径}`。
 * 认不出的（`if`/算术/`some`/多键对象…）返回 `{kind:'raw', source}` —— 页面据此提示"请用源码模式"。
 */
export function readLogic(expression) {
	if (!isPlainObject(expression)) {
		return { kind: 'raw', source: JSON.stringify(expression === undefined ? null : expression) };
	}
	const keys = Object.keys(expression);
	if (keys.length === 0) {
		return { kind: 'raw', source: '{}' };
	}
	const hasOperator = keys.every(key => LOGIC_OPERATORS.includes(key) || key.startsWith('fn:'));
	if (!hasOperator) {
		return { kind: 'raw', source: JSON.stringify(expression) };
	}
	// 多键 = and（格式文档 §6.1）
	if (keys.length > 1) {
		const rows = keys.map(key => readLogic({ [key]: expression[key] }));
		if (rows.some(row => row.kind === 'raw')) {
			return { kind: 'raw', source: JSON.stringify(expression) };
		}
		return { kind: 'group', operator: 'and', rows };
	}
	const operator = keys[0];
	const argument = expression[operator];
	if (operator === 'and' || operator === 'or') {
		if (!Array.isArray(argument)) {
			return { kind: 'raw', source: JSON.stringify(expression) };
		}
		const rows = argument.map(readLogic);
		if (rows.some(row => row.kind === 'raw')) {
			return { kind: 'raw', source: JSON.stringify(expression) };
		}
		return { kind: 'group', operator, rows };
	}
	const path = isHasValueExpr(expression);
	if (path !== null) {
		return newRow(path, 'has');
	}
	const emptyPath = isEmptyExpr(expression);
	if (emptyPath !== null) {
		return newRow(emptyPath, 'empty');
	}
	if (ROW_OPERATORS.includes(operator)) {
		const row = readCompareRow(operator, argument);
		if (row !== null) {
			return row;
		}
	}
	return { kind: 'raw', source: JSON.stringify(expression) };
}

/** 这条表达式构造器表达得了吗（表达得了 ⇒ 两个模式可以互相同步）。 */
export function isBuilderRepresentable(expression) {
	return readLogic(expression).kind !== 'raw';
}

/** 树有多深（根组算第 1 层）。 */
export function groupDepth(node, depth = 1) {
	if (node === null || typeof node !== 'object' || node.kind !== 'group') {
		return 0;
	}
	let deepest = depth;
	for (const row of Array.isArray(node.rows) ? node.rows : []) {
		if (row && row.kind === 'group') {
			deepest = Math.max(deepest, groupDepth(row, depth + 1));
		}
	}
	return deepest;
}

/** 这一组还能不能再套一层组（最多 3 层）。 */
export function canAddGroup(node, maxDepth = 3) {
	return groupDepth(node) < maxDepth;
}

/** 构造器里的字段下拉：字段表 + 本文档的 vars 名 + `item`/`index` 之类的循环变量。 */
export function logicFieldChoices(fieldNames, varNames, extra = []) {
	const out = [];
	for (const name of varNames || []) {
		out.push(name);
	}
	for (const name of fieldNames || []) {
		out.push(name);
	}
	for (const name of extra || []) {
		out.push(name);
	}
	return Array.from(new Set(out));
}

// ---- 图层列表用的一句话 -------------------------------------------------------------------------

/** 图层列表里显示的那一行（type + 关键文本）。 */
export function elementSummary(element) {
	const raw = isPlainObject(element) ? element : {};
	const type = String(raw.type || '?').trim().toLowerCase();
	if (type === 'text') {
		return 'text 「' + String(raw.text === undefined || raw.text === null ? '' : raw.text) + '」';
	}
	if (type === 'gauge') {
		const needle = isPlainObject(raw.needle) ? JSON.stringify(raw.needle) : '缺省（车速）';
		return 'gauge needle=' + needle;
	}
	if (type === 'image') {
		return 'image ' + String(raw.src === undefined || raw.src === null ? '(没写 src)' : raw.src);
	}
	if (type === 'foreach') {
		return 'foreach ' + String(raw.var || raw.of ? (raw.var || JSON.stringify(raw.of)) : '(没写数据)');
	}
	if (type === 'line') {
		return 'line → ' + short(raw.x2) + ', ' + short(raw.y2);
	}
	if (type === 'rect' || type === 'roundrect' || type === 'circle' || type === 'arc') {
		return type + ' ' + short(raw.w) + '×' + short(raw.h);
	}
	return type;
}

function short(value) {
	if (value === undefined || value === null) {
		return '缺';
	}
	const number = Number(value);
	if (!Number.isFinite(number)) {
		return String(value);
	}
	return String(Math.round(number * 1000) / 1000);
}

/** 状态栏要的那句人话（"移动 text 到 0.42, 0.50"）。 */
export function moveLabel(element, x, y) {
	return '移动 ' + elementSummary(element).split(' ')[0] + ' 到 ' + short(x) + ', ' + short(y);
}

/** 新元素的缺省（照 schema.json 的 common 缺省 + 类型专属键）。 */
export function newElement(type, defaults = {}) {
	const kind = String(type || 'text').trim().toLowerCase();
	const element = { type: kind };
	const common = Object.assign({ x: 0.5, y: 0.5, size: 0.4, color: '0' }, defaults.common || {});
	element.x = common.x;
	element.y = common.y;
	if (kind === 'text') {
		element.text = defaults.text === undefined ? '新文本' : defaults.text;
		element.size = common.size;
		element.align = defaults.align === undefined ? 'center' : defaults.align;
		element.color = common.color;
	} else if (kind === 'line') {
		element.x2 = 0.6;
		element.y2 = 0.6;
		element.width = 0.03;
		element.color = defaults.lineColor === undefined ? '#FF9FB3C8' : defaults.lineColor;
	} else if (kind === 'circle') {
		element.radius = 0.1;
		element.color = defaults.shapeColor === undefined ? '#FFFFB300' : defaults.shapeColor;
	} else if (kind === 'arc') {
		element.radius = 0.4;
		element.start = 0;
		element.end = 360;
		element.width = 0.01;
		element.color = defaults.shapeColor === undefined ? '#FFFFB300' : defaults.shapeColor;
	} else if (kind === 'gauge') {
		element.radius = 0.44;
		element.color = defaults.shapeColor === undefined ? '#FFFFB300' : defaults.shapeColor;
	} else if (kind === 'roundrect') {
		element.w = 0.3;
		element.h = 0.2;
		element.radius = 0.08;
		element.color = defaults.shapeColor === undefined ? '#FF101418' : defaults.shapeColor;
	} else if (kind === 'image') {
		element.w = 0.3;
		element.h = 0.3;
		element.src = '';
		element.fit = 'stretch';
	} else if (kind === 'foreach') {
		element.var = '';
		element.as = 'item';
		element.index = 'index';
		element.limit = 32;
		element.elements = [{
			type: 'text', text: '{item}', x: 0.5, y: 0.5, size: 0.3, align: 'center', color: '0',
		}];
	} else {
		// rect（以及任何别的一律按矩形建：w/h 是它的必要键）
		element.w = 0.3;
		element.h = 0.2;
		element.color = defaults.shapeColor === undefined ? '#FFFFB300' : defaults.shapeColor;
	}
	return element;
}

/**
 * 元素数组里插一项新元素的位置：放在**顶层**（数组末尾 = 画在最上面），与"数组顺序 = 从下往上画"一致。
 */
export function appendElement(elements, element) {
	const list = Array.isArray(elements) ? elements : [];
	list.push(element);
	return list.length - 1;
}

/** 把一项从数组里挪到另一个位置（图层上下移动/置顶置底用）。越界自动钳。 */
export function moveItem(list, from, to) {
	if (!Array.isArray(list) || from < 0 || from >= list.length) {
		return false;
	}
	const target = Math.max(0, Math.min(list.length - 1, to));
	if (target === from) {
		return false;
	}
	const [item] = list.splice(from, 1);
	list.splice(target, 0, item);
	return true;
}


/**
 * 模板过滤器那一段（`pad:7`）里的**过滤器名**与**参数**。
 *
 * <p>用"最长匹配已知名字"而不是"按第一个冒号切"：`pad:7` 的名字是 `pad`、参数是 `7`；
 * 名字对不上任何一个内置过滤器时，整段当名字返回（调用方按"不认识的过滤器"处理）。
 * 与 Java 侧 {@code MmtrFaceText.applyFilter} 同一口径。</p>
 *
 * @param text        过滤器那一段（`|` 之间、已经去掉空白的）
 * @returns {object}  { name, parameter }
 */
export function filterNameOf(text) {
	const source = text === null || text === undefined ? '' : String(text).trim();
	const lower = source.toLowerCase();
	const known = new Set(FILTERS);
	const colons = [];
	for (let i = 0; i < lower.length; i++) {
		if (lower[i] === ':') {
			colons.push(i);
		}
	}
	// 从最长往最短找：`pad:7` 先试 `pad:7`（不在清单里），再试 `pad`（命中）
	for (let i = colons.length - 1; i >= 0; i--) {
		const candidate = lower.substring(0, colons[i]);
		if (known.has(candidate)) {
			return { name: candidate, parameter: source.substring(colons[i] + 1) };
		}
	}
	return { name: lower, parameter: '' };
}
