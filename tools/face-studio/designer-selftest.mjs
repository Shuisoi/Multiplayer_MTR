#!/usr/bin/env node
/*
 * designer-selftest.mjs —— 绘制器纯函数层的**离线自检**（Node 直接跑，零依赖、零构建）：
 *
 *   node mmtr/tools/face-studio/designer-selftest.mjs
 *
 * 断言的都是"画面上错了很难看出来、进游戏更看不出来"的那几条：
 *   · 几何换算（**y 轴朝上**，画布像素要翻过来）；
 *   · 命中测试（`rect` 的 x,y 是**左下角**、`circle` 的 x,y 是**圆心** —— 这两种写法点起来不一样）；
 *   · 吸附（阈值边界、候选线包含 0/0.5/1 与其他元素的同一条边）；
 *   · 方向键微调（0.005 / Shift 0.001、钳 0..1、到底了不算改动）；
 *   · JSON 路径读写（`faces.pid_1.elements[2].x`，含数组删项）；
 *   · 条件构造器（生成 / 回读 / 往返一致，**"有值"必须是 `{"!!":[{"var":…}]}`**，不是 `{"!=":[…,""]}`）；
 *   · 未知键不许被写进文档（拼错键的防线）。
 *
 * 退出码：0 = 全过；1 = 有断言失败。
 */

import { OPERATORS, evaluate as evaluateLogic } from './logic.mjs';
import {
	ARROW_KEYS,
	HANDLES,
	LOGIC_OPERATORS,
	ROW_OPERATORS,
	appendElement,
	applyKnownPatch,
	buildExtensionSkeleton,
	buildLogic,
	canAddGroup,
	clampProportion,
	clampSize,
	cloneJson,
	cloneWithOffset,
	constrainDrag,
	defaultForKey,
	deletePath,
	elementBox,
	elementSummary,
	emptyExpr,
	estimateTextWidth,
	extensionReportIsEmpty,
	filterNameOf,
	formatPath,
	getPath,
	groupDepth,
	handleAtPoint,
	handlePoints,
	hasValueExpr,
	hitTest,
	isBuilderRepresentable,
	isEmptyExpr,
	isHasValueExpr,
	isNamespacedName,
	javaClassFromType,
	moveItem,
	moveLabel,
	newElement,
	newGroup,
	newRow,
	nudgeElement,
	nudgeProportion,
	nudgeStep,
	parsePath,
	pickTopmost,
	proportionToPxX,
	proportionToPxY,
	pxToProportionX,
	pxToProportionY,
	readLogic,
	replaceRoot,
	resizeBox,
	scanFaceForExtensions,
	setPath,
	snapBox,
	snapLabel,
	snapLines,
	snapValue,
	unknownKeys,
} from './designer.mjs';

// ---- 迷你断言框架 -------------------------------------------------------------------------------

const results = [];
let failed = 0;

/** 一条断言：跑 `body`，抛异常或返回 false 都算失败。 */
function check(name, body) {
	try {
		const outcome = body();
		if (outcome === false) {
			results.push({ name, ok: false, note: '返回 false' });
			failed++;
			return;
		}
		results.push({ name, ok: true, note: outcome === undefined || outcome === true ? '' : String(outcome) });
	} catch (e) {
		results.push({ name, ok: false, note: e && e.message ? e.message : String(e) });
		failed++;
	}
}

/** 深比较（对象键序无关、数组顺序有关、数字按数值）。 */
function same(actual, expected) {
	if (expected === null) {
		return actual === null;
	}
	if (expected === undefined) {
		return actual === undefined;
	}
	if (typeof expected === 'number') {
		return typeof actual === 'number' && (actual === expected || (Number.isNaN(actual) && Number.isNaN(expected)));
	}
	if (typeof expected === 'string' || typeof expected === 'boolean') {
		return actual === expected;
	}
	if (Array.isArray(expected)) {
		return Array.isArray(actual) && actual.length === expected.length && expected.every((item, index) => same(actual[index], item));
	}
	if (typeof expected === 'object') {
		if (actual === null || typeof actual !== 'object' || Array.isArray(actual)) {
			return false;
		}
		const keys = Object.keys(expected);
		if (Object.keys(actual).length !== keys.length) {
			return false;
		}
		return keys.every(key => Object.prototype.hasOwnProperty.call(actual, key) && same(actual[key], expected[key]));
	}
	return false;
}

/** 断言相等，失败时把人话写清楚。 */
function eq(actual, expected, what = '') {
	if (!same(actual, expected)) {
		throw new Error((what ? what + '：' : '') + '得到 ' + show(actual) + '，期望 ' + show(expected));
	}
	return true;
}

function near(actual, expected, epsilon = 1e-9) {
	if (typeof actual !== 'number' || Math.abs(actual - expected) > epsilon) {
		throw new Error('得到 ' + show(actual) + '，期望约 ' + expected);
	}
	return true;
}

function show(value) {
	if (value === undefined) {
		return 'undefined';
	}
	try {
		return JSON.stringify(value);
	} catch (e) {
		return String(value);
	}
}

// ---- 1. 几何换算（y 轴朝上） ---------------------------------------------------------------------

check('比例→像素：y 轴翻过来（比例 0 在牌底 = 像素最下面一行）', () => {
	eq(proportionToPxX(0.25, 400), 100);
	eq(proportionToPxY(0.25, 400), 300, '比例 0.25 应落在像素 y=300');
	eq(proportionToPxY(0, 400), 400, '牌底 = 像素最下');
	eq(proportionToPxY(1, 400), 0, '牌顶 = 像素最上');
	return true;
});

check('像素→比例：与上一条互为反函数', () => {
	near(pxToProportionX(100, 400), 0.25);
	near(pxToProportionY(300, 400), 0.25, 1e-12);
	near(pxToProportionY(400, 400), 0, 1e-12);
	near(pxToProportionY(0, 400), 1, 1e-12);
	return true;
});

check('钳位：比例钳 0..1、size 钳 0.02..1.5，非数用缺省', () => {
	eq(clampProportion(-0.3), 0);
	eq(clampProportion(1.4), 1);
	eq(clampProportion('abc', 0.5), 0.5, '非数应回退到缺省');
	eq(clampProportion('0.42'), 0.42, '数字串应当能读');
	eq(clampSize(0.001), 0.02);
	eq(clampSize(9), 1.5);
	return true;
});

// ---- 2. 命中测试（锚点语义） ---------------------------------------------------------------------

check('rect：x,y 是**左下角**（盒 = x..x+w, y..y+h）', () => {
	const box = elementBox({ type: 'rect', x: 0.2, y: 0.3, w: 0.4, h: 0.2 });
	near(box.x0, 0.2, 1e-12);
	near(box.y0, 0.3, 1e-12);
	near(box.x1, 0.6, 1e-12);
	near(box.y1, 0.5, 1e-12);
	return true;
});

check('反例：rect 的左下角外面（0.19, 0.3）点不中，盒内（0.2, 0.3）点得中', () => {
	const rect = { type: 'rect', x: 0.2, y: 0.3, w: 0.4, h: 0.2 };
	eq(hitTest(rect, { x: 0.2, y: 0.3 }), true, '左下角本身');
	eq(hitTest(rect, { x: 0.6, y: 0.5 }), true, '右上角本身');
	eq(hitTest(rect, { x: 0.19, y: 0.3 }), false, '左边外一格');
	eq(hitTest(rect, { x: 0.5, y: 0.51 }), false, '上边外一格');
	return true;
});

check('★ 左下角矩形 vs 圆心圆：同一个 (0.5,0.5) 落点行为不同', () => {
	const rect = { type: 'rect', x: 0.5, y: 0.5, w: 0.2, h: 0.2 };
	const circle = { type: 'circle', x: 0.5, y: 0.5, radius: 0.2 };
	eq(hitTest(rect, { x: 0.5, y: 0.5 }), true, '矩形把 (0.5,0.5) 当**左下角**，点得中');
	eq(hitTest(rect, { x: 0.45, y: 0.45 }), false, '矩形不含左下方 —— 这正是"锚点写错"的现场表现');
	eq(hitTest(circle, { x: 0.5, y: 0.5 }), true, '圆心当然点得中');
	eq(hitTest(circle, { x: 0.45, y: 0.45 }), true, '圆的左下方在半径内');
	return true;
});

check('圆的半径按**牌高**算：aspect=2 时 x 方向只占一半比例', () => {
	const circle = { type: 'circle', x: 0.5, y: 0.5, radius: 0.2 };
	eq(hitTest(circle, { x: 0.6, y: 0.5 }, { aspect: 2 }), true, 'x 半径 = 0.2/2 = 0.1');
	eq(hitTest(circle, { x: 0.61, y: 0.5 }, { aspect: 2 }), false);
	eq(hitTest(circle, { x: 0.7, y: 0.5 }, { aspect: 1 }), true, 'aspect=1 时 x 半径 = 0.2');
	return true;
});

check('text：按**字高估一个盒**，竖直永远居中，align 决定左右', () => {
	const left = elementBox({ type: 'text', text: 'ABCD', x: 0.1, y: 0.5, size: 0.4, align: 'left' });
	near(left.x0, 0.1);
	near(left.x1, 0.1 + estimateTextWidth('ABCD', 0.4, 1));
	eq(left.y0, 0.3, '竖直居中：0.5 - 0.4/2');
	eq(left.y1, 0.7);
	const right = elementBox({ type: 'text', text: 'ABCD', x: 0.97, y: 0.5, size: 0.4, align: 'right' });
	near(right.x1, 0.97, 1e-12);
	eq(right.x0 < 0.97, true);
	const center = elementBox({ type: 'text', text: 'ABCD', x: 0.5, y: 0.5, size: 0.4, align: 'center' });
	near((center.x0 + center.x1) / 2, 0.5, 1e-12);
	return true;
});

check('line：x,y 是起点；x2/y2 缺省 = x,y（退化成点也点得中）', () => {
	const line = { type: 'line', x: 0.1, y: 0.1, x2: 0.9, y2: 0.9, width: 0.03 };
	eq(hitTest(line, { x: 0.5, y: 0.5 }), true);
	eq(hitTest(line, { x: 0.5, y: 0.6 }), false, '离线 0.0707，超过线宽与容差');
	eq(hitTest(line, { x: 0.5, y: 0.52 }), true, '贴着线仍算命中（容差 0.012 内）');
	const dot = { type: 'line', x: 0.4, y: 0.4 };
	eq(hitTest(dot, { x: 0.4, y: 0.4 }), true);
	eq(hitTest(dot, { x: 0.5, y: 0.4 }), false);
	return true;
});

check('pickTopmost：取**最后**一个命中的（数组顺序 = 从下往上画）', () => {
	const elements = [
		{ type: 'rect', x: 0, y: 0, w: 1, h: 1, color: '#FF000000' },
		{ type: 'circle', x: 0.5, y: 0.5, radius: 0.2 },
	];
	eq(pickTopmost(elements, { x: 0.5, y: 0.5 }), 1, '上面的圆应抢先');
	eq(pickTopmost(elements, { x: 0.05, y: 0.05 }), 0, '圆的半径外落到矩形上');
	eq(pickTopmost(elements, { x: 5, y: 5 }), -1, '什么都不命中 → -1');
	return true;
});

// ---- 3. 缩放把手 ---------------------------------------------------------------------------------

check('8 个把手：nw = 左上（x0, y1）、se = 右下（x1, y0）', () => {
	eq(HANDLES.length, 8);
	const points = handlePoints({ x0: 0.2, y0: 0.1, x1: 0.6, y1: 0.5 });
	eq(points.nw, [0.2, 0.5]);
	eq(points.se, [0.6, 0.1]);
	eq(points.n, [0.4, 0.5]);
	eq(points.w, [0.2, 0.3]);
	return true;
});

check('把手命中用比例容差，离远了不算', () => {
	const box = { x0: 0.2, y0: 0.1, x1: 0.6, y1: 0.5 };
	eq(handleAtPoint(box, { x: 0.205, y: 0.505 }, 0.02), 'nw');
	eq(handleAtPoint(box, { x: 0.2, y: 0.3 }, 0.02), 'w');
	eq(handleAtPoint(box, { x: 0.4, y: 0.3 }, 0.02), null, '框中间不该命中任何把手');
	return true;
});

check('缩放：拖 e 变宽、拖 nw 动左下角、最小尺寸顶回来、越界钳 0..1', () => {
	const grow = resizeBox({ x0: 0.2, y0: 0.2, x1: 0.5, y1: 0.5 }, 'e', 0.1, 0);
	near(grow.x, 0.2, 1e-12);
	near(grow.y, 0.2, 1e-12);
	near(grow.w, 0.4, 1e-12);
	near(grow.h, 0.3, 1e-12);
	const nw = resizeBox({ x0: 0.2, y0: 0.2, x1: 0.5, y1: 0.5 }, 'nw', 0.1, 0.1);
	near(nw.x, 0.3, 1e-12, '拖左上角：左缘动');
	near(nw.y, 0.2, 1e-12, '拖左上角：**下缘不动**（动的是上缘）');
	near(nw.w, 0.2, 1e-12);
	near(nw.h, 0.4, 1e-12);
	const sw = resizeBox({ x0: 0.2, y0: 0.2, x1: 0.5, y1: 0.5 }, 'sw', 0.1, -0.1);
	near(sw.x, 0.3, 1e-12, '拖左下角：左缘动');
	near(sw.y, 0.1, 1e-12, '拖左下角：下缘动');
	near(sw.h, 0.4, 1e-12);
	const tiny = resizeBox({ x0: 0.2, y0: 0.2, x1: 0.5, y1: 0.5 }, 'w', 5, 0);
	eq(tiny.w >= 0.02, true, '宽度不许被拖成 0 或负');
	eq(tiny.x >= 0, true);
	const over = resizeBox({ x0: 0.9, y0: 0.9, x1: 1, y1: 1 }, 'e', 0.5, 0.5);
	eq(over.w <= 1 && over.x + over.w <= 1.0000001, true, '右边不许越出牌面');
	return true;
});

// ---- 4. 吸附 -------------------------------------------------------------------------------------

check('吸附候选线：0 / 0.5 / 1 + 其他元素的左中右、下中上', () => {
	const elements = [
		{ type: 'rect', x: 0.2, y: 0.25, w: 0.2, h: 0.5 },
		{ type: 'rect', x: 0.5, y: 0.5, w: 0.2, h: 0.2 },
	];
	const lines = snapLines(elements, 1);
	eq(lines.x.includes(0) && lines.x.includes(0.5) && lines.x.includes(1), true, '牌边的 0/0.5/1 必须在');
	eq(lines.x.includes(0.2) && lines.x.includes(0.4), true, '另一个元素的左缘与右缘必须在');
	eq(lines.y.includes(0.25) && lines.y.includes(0.75), true, '另一个元素的下缘与上缘必须在');
	const self = snapLines(elements, 1).x.filter(value => value === 0.6).length;
	const withoutSelf = snapLines([elements[0]], 0).x.filter(value => value === 0.6).length;
	eq(self, 0, '不该把正在拖的那个元素自己也算成候选');
	eq(withoutSelf, 0);
	return true;
});

check('吸附阈值边界：0.0099 吸得上，0.0101 吸不上', () => {
	const near1 = snapValue(0.5099, [0.5], 0.01);
	eq(near1 !== null, true);
	near(near1.delta, -0.0099, 1e-9);
	const far = snapValue(0.5101, [0.5], 0.01);
	eq(far, null, '超过阈值必须不吸');
	eq(snapValue(0.5, [0.5], 0) !== null, true, '阈值为 0 时完全相等仍算吸上');
	eq(snapValue(0.51, [0.5], 0), null, '阈值为 0 时差一点就不吸');
	return true;
});

check('整盒吸附：左缘去够 0.5、下缘去够 0.25（并给出提示线该画在哪）', () => {
	const box = { x0: 0.495, y0: 0.245, x1: 0.805, y1: 0.445 };
	const snap = snapBox(box, [0.3, 0.5, 1], [0.25, 0.5], 0.01);
	near(snap.dx, 0.005, 1e-9, '左缘 0.495 离 0.5 最近');
	near(snap.dy, 0.005, 1e-9, '下缘 0.245 离 0.25 最近');
	eq(snap.hits.length, 2);
	eq(snap.hits[0].axis, 'x');
	eq(snap.hits[0].edge, 'left');
	near(snap.hits[0].target, 0.5, 1e-12);
	near(snap.hits[0].distance, 0.005, 1e-12);
	eq(snap.hits[1].axis, 'y');
	eq(snap.hits[1].edge, 'bottom');
	eq(snapLabel({ target: 0.5 }), '吸附到 0.5');
	eq(snapLabel(null), '');
	return true;
});

// ---- 5. 方向键微调与拖拽约束 ---------------------------------------------------------------------

check('方向键步长：默认 0.005、Shift 0.001；↑ 是 +y（y 朝上）', () => {
	eq(nudgeStep(false), 0.005);
	eq(nudgeStep(true), 0.001);
	eq(ARROW_KEYS.ArrowUp, { axis: 'y', direction: 1 });
	eq(ARROW_KEYS.ArrowDown, { axis: 'y', direction: -1 });
	eq(ARROW_KEYS.ArrowLeft, { axis: 'x', direction: -1 });
	eq(ARROW_KEYS.ArrowRight, { axis: 'x', direction: 1 });
	return true;
});

check('微调钳在 0..1：0.998 + 0.005 = 1，且到底了算"没改动"', () => {
	eq(nudgeProportion(0.998, 1, 0.005).value, 1);
	eq(nudgeProportion(1, 1, 0.005).changed, false, '已经在边上，不该再记一次改动');
	eq(nudgeProportion(0.002, -1, 0.005).value, 0);
	eq(nudgeProportion(0, -1, 0.005).changed, false);
	eq(nudgeProportion(0.5, 1, 0.001).value, 0.501);
	return true;
});

check('微调元素：只改一个轴，另一轴原样', () => {
	const element = { type: 'text', x: 0.3, y: 0.4, text: 'A' };
	const moved = nudgeElement(element, 'x', 1, 0.005);
	eq(moved.key, 'x');
	near(moved.after, 0.305, 1e-9);
	eq(nudgeElement(element, 'y', -1, 0.005).after, 0.395);
	eq(element.x, 0.3, '纯函数不许改原对象');
	return true;
});

check('Shift 拖拽：哪个轴动得多改哪个（打平算 x）', () => {
	eq(constrainDrag(0.3, -0.1, true), { dx: 0.3, dy: 0 });
	eq(constrainDrag(0.1, -0.4, true), { dx: 0, dy: -0.4 });
	eq(constrainDrag(0.2, -0.2, true), { dx: 0.2, dy: 0 }, '打平算 x');
	eq(constrainDrag(0.3, -0.1, false), { dx: 0.3, dy: -0.1 }, '不按 Shift 就自由拖');
	return true;
});

check('Alt+拖的副本：偏移一点点、钳 0..1、且**原元素不动**（深拷贝）', () => {
	const original = { type: 'text', x: 0.9, y: 0.1, text: 'A' };
	const copy = cloneWithOffset(original, 0.05);
	eq(original.x, 0.9, '原元素不许被改');
	near(copy.x, 0.95, 1e-9);
	near(copy.y, 0.05, 1e-9);
	const corner = cloneWithOffset({ type: 'rect', x: 1, y: 0 }, 0.05);
	eq(corner.x, 1);
	eq(corner.y, 0);
	return true;
});

// ---- 6. JSON 路径读写 ---------------------------------------------------------------------------

check('路径解析：`faces.pid_1.elements[2].x`', () => {
	eq(parsePath('faces.pid_1.elements[2].x'), ['faces', 'pid_1', 'elements', 2, 'x']);
	eq(parsePath('[0].x'), [0, 'x']);
	eq(formatPath(['faces', 'pid_1', 'elements', 2, 'x']), 'faces.pid_1.elements[2].x', '往返一致');
	eq(parsePath(''), []);
	let threw = false;
	try {
		parsePath('faces..x');
	} catch (e) {
		threw = true;
	}
	eq(threw, true, '坏路径要抛，不许静默当成别的路径');
	return true;
});

check('★ 按路径写 `faces.pid_1.elements[2].x`（中间层缺了要建出来）', () => {
	const root = {};
	setPath(root, ['faces', 'pid_1', 'elements', 2, 'x'], 0.42);
	eq(getPath(root, parsePath('faces.pid_1.elements[2].x')), 0.42);
	eq(Array.isArray(root.faces.pid_1.elements), true, '数字段要建成数组');
	eq(getPath(root, ['faces', 'pid_1', 'elements', 0]), undefined, '别把数组灌满洞');
	deletePath(root, parsePath('faces.pid_1.elements[2].x'));
	eq(Object.prototype.hasOwnProperty.call(root.faces.pid_1.elements[2], 'x'), false);
	eq(Object.prototype.hasOwnProperty.call(root.faces.pid_1.elements[2], 'type'), false);
	return true;
});

check('数组删项用 splice（真删掉一项，不留洞、下标不串位）', () => {
	const root = { faces: { pid_1: { elements: [{ type: 'a' }, { type: 'b' }, { type: 'c' }] } } };
	eq(deletePath(root, ['faces', 'pid_1', 'elements', 1]), true);
	eq(root.faces.pid_1.elements.map(item => item.type), ['a', 'c']);
	eq(root.faces.pid_1.elements.length, 2);
	eq(deletePath(root, ['faces', 'pid_1', 'elements', 9]), false, '越界返回 false，不抛');
	eq(deletePath(root, ['faces', 'nope', 'x']), false);
	return true;
});

check('replaceRoot：原地换内容、保住对象身份（路径/解析都指着它）', () => {
	const target = { keep: 1 };
	const identity = target;
	replaceRoot(target, { faces: { a: { elements: [] } } });
	eq(target === identity, true);
	eq(Object.prototype.hasOwnProperty.call(target, 'keep'), false, '旧键要清干净');
	eq(getPath(target, ['faces', 'a', 'elements']), []);
	const source = { faces: { a: { elements: [{ type: 'text' }] } } };
	replaceRoot(target, source);
	source.faces.a.elements[0].type = 'mutated';
	eq(getPath(target, ['faces', 'a', 'elements', 0, 'type']), 'text', '换进来的是深拷贝，不是同一个引用');
	return true;
});

// ---- 7. 键表与未知键 -----------------------------------------------------------------------------

check('缺省值只认 schema.json 的 default（不许另写一份常量）', () => {
	eq(defaultForKey({ name: 'x', type: 'number', default: 0.5 }), 0.5);
	eq(defaultForKey({ name: 'require', type: 'logic', default: null }), null);
	eq(defaultForKey({ name: 'w', type: 'number' }), null, '没写 default 就是 null');
	return true;
});

check('未知键区：`colour` 不是 `color`（拼错键的防线）', () => {
	const raw = { type: 'text', x: 0.5, colour: '#FFFFFF', siz: 0.4 };
	eq(unknownKeys(raw, ['type', 'x', 'y', 'size', 'color']), ['colour', 'siz']);
	eq(unknownKeys(raw, ['type', 'x', 'colour', 'siz']), []);
	return true;
});

check('★ 未知键不许被写进文档（补丁里的陌生键只报告、不落笔）', () => {
	const target = { type: 'text' };
	const dropped = applyKnownPatch(target, { x: 0.4, clour: '#FFF' }, ['type', 'x']);
	eq(dropped, ['clour']);
	eq(target, { type: 'text', x: 0.4 });
	eq(Object.prototype.hasOwnProperty.call(target, 'clour'), false);
	applyKnownPatch(target, { x: undefined }, ['type', 'x']);
	eq(Object.prototype.hasOwnProperty.call(target, 'x'), false, 'undefined = 重置为缺省（删键）');
	return true;
});

// ---- 8. 条件构造器 -------------------------------------------------------------------------------

check('构造器：一行直接是那个比较（不套无谓的 and）', () => {
	const expression = buildLogic(newGroup('and', [newRow('pid.service', '==', '00101')]));
	eq(expression, { '==': [{ var: 'pid.service' }, '00101'] });
	return true;
});

check('构造器：多行套 and / or，可切', () => {
	const rows = [newRow('pid.service', '==', '00101'), newRow('speedKmh', '>', 0, 'number')];
	eq(buildLogic(newGroup('and', rows)), {
		and: [{ '==': [{ var: 'pid.service' }, '00101'] }, { '>': [{ var: 'speedKmh' }, 0] }],
	});
	eq(buildLogic(newGroup('or', rows)), {
		or: [{ '==': [{ var: 'pid.service' }, '00101'] }, { '>': [{ var: 'speedKmh' }, 0] }],
	});
	return true;
});

check('★ "有值"必须生成 {"!!":[{"var":…}]}，**不是** {"!=":[…,""]}', () => {
	const expression = buildLogic(newGroup('and', [newRow('pid.service', 'has')]));
	eq(expression, { '!!': [{ var: 'pid.service' }] });
	eq(same(expression, { '!=': [{ var: 'pid.service' }, ''] }), false, '这两种写法在"字段缺失"时结果相反，绝不能混');
	eq(isHasValueExpr(expression), 'pid.service');
	eq(hasValueExpr('pid.terminus'), { '!!': [{ var: 'pid.terminus' }] });
	return true;
});

check('"为空"快捷模板 = 对"有值"取反（同样不写 != 空串）', () => {
	const expression = buildLogic(newGroup('and', [newRow('pid.terminus', 'empty')]));
	eq(expression, { '!': [{ '!!': [{ var: 'pid.terminus' }] }] });
	eq(isEmptyExpr(expression), 'pid.terminus');
	eq(emptyExpr('hold.reason'), { '!': [{ '!!': [{ var: 'hold.reason' }] }] });
	eq(isHasValueExpr(expression), null, '"为空"不该被认成"有值"');
	return true;
});

check('回读：从 JSONLogic 读回构造器行（含数字/真假/字段三种值）', () => {
	const row = readLogic({ '==': [{ var: 'pid.service' }, '00101'] });
	eq({ kind: row.kind, field: row.field, operator: row.operator, value: row.value, valueKind: row.valueKind },
		{ kind: 'row', field: 'pid.service', operator: '==', value: '00101', valueKind: 'text' });
	eq(readLogic({ '>': [{ var: 'lzb.targetM' }, 200] }).valueKind, 'number');
	eq(readLogic({ '===': [{ var: 'active' }, true] }).valueKind, 'bool');
	eq(readLogic({ '==': [{ var: 'cab.end' }, { var: 'cab.active' }] }).valueKind, 'field');
	eq(readLogic({ '!!': [{ var: 'pid.next' }] }).operator, 'has');
	return true;
});

check('★ 往返一致：buildLogic(readLogic(x)) 逐字等于 x', () => {
	const cases = [
		{ '!!': [{ var: 'pid.service' }] },
		{ '!': [{ '!!': [{ var: 'pid.terminus' }] }] },
		{ '==': [{ var: 'pid.service' }, '00101'] },
		{ and: [{ '!!': [{ var: 'pid.service' }] }, { '!=': [{ var: 'cab.end' }, 'B'] }] },
		{ or: [{ '>': [{ var: 'speedKmh' }, 0] }, { '<': [{ var: 'speedKmh' }, 0] }] },
		{ and: [{ or: [{ '==': [{ var: 'a' }, 1] }, { '==': [{ var: 'b' }, 2] }] }, { '!!': [{ var: 'c' }] }] },
	];
	for (const expression of cases) {
		if (!same(buildLogic(readLogic(expression)), expression)) {
			throw new Error('往返不一致：' + show(expression) + ' → ' + show(buildLogic(readLogic(expression))));
		}
	}
	return true;
});

check('构造器表达不了的形状退回源码模式（if/算术/数组算子）', () => {
	eq(readLogic({ if: [{ '!!': [{ var: 'a' }] }, 1, 0] }).kind, 'raw');
	eq(readLogic({ '+': [{ var: 'a' }, 1] }).kind, 'raw');
	eq(readLogic({ some: [{ var: 'calls' }, { '==': [{ var: 'item' }, '海山'] }] }).kind, 'raw');
	eq(isBuilderRepresentable({ if: [1, 2] }), false);
	eq(isBuilderRepresentable({ '!!': [{ var: 'a' }] }), true);
	return true;
});

check('多键对象 = and（格式文档 §6.1）：读回来是一组"与"，重建时归一成 {"and":[…]}', () => {
	const expression = { '!!': [{ var: 'a' }], '>': [{ var: 'b' }, 1] };
	const tree = readLogic(expression);
	eq(tree.kind, 'group');
	eq(tree.operator, 'and');
	eq(tree.rows.length, 2);
	// 多键对象与 {"and":[…]} 在 JSONLogic 里等价（多键 = and），重建一律用显式的 and：
	eq(buildLogic(tree), { and: [{ '!!': [{ var: 'a' }] }, { '>': [{ var: 'b' }, 1] }] });
	eq(evaluateLogic(expression, { a: true, b: 2 }), evaluateLogic(buildLogic(tree), { a: true, b: 2 }), '两种写法的求值结果必须一样');
	return true;
});

check('嵌套最多 3 层：第 3 层还能加组？不能', () => {
	const depth1 = newGroup('and', [newRow('a', 'has')]);
	eq(groupDepth(depth1), 1);
	eq(canAddGroup(depth1), true);
	const depth2 = newGroup('and', [newGroup('and', [newRow('a', 'has')])]);
	eq(groupDepth(depth2), 2);
	eq(canAddGroup(depth2), true);
	const depth3 = newGroup('and', [newGroup('and', [newGroup('and', [newRow('a', 'has')])])]);
	eq(groupDepth(depth3), 3);
	eq(canAddGroup(depth3), false, '第 3 层不许再套');
	eq(groupDepth(newGroup('and', [newRow('a', 'has')])), 1, '行不算层');
	return true;
});

check('构造器只生成清单里的算子（行算子 ⊆ 32 个算子）', () => {
	for (const operator of ROW_OPERATORS) {
		eq(LOGIC_OPERATORS.includes(operator), true, '行算子 ' + operator + ' 必须在 Java operators() 清单里');
	}
	eq(ROW_OPERATORS.includes('and'), false, 'and 是组运算，不是行算子');
	eq(ROW_OPERATORS.includes('has'), false, 'has 只是本构造器的糖，不是 JSONLogic 算子');
	return true;
});

check('★ 32 个算子清单与 logic.mjs 的 OPERATORS 逐项同序一致（= 与 Java operators() 一致）', () => {
	eq(OPERATORS.length, 32);
	eq(LOGIC_OPERATORS.length, 32);
	eq(LOGIC_OPERATORS.join(' '), OPERATORS.join(' '));
	return true;
});

// ---- 9. 图层与新建元素 ---------------------------------------------------------------------------

check('图层顺序：数组顺序 = 从下往上画，上下移动/置顶置底不丢项', () => {
	const list = [{ type: 'a' }, { type: 'b' }, { type: 'c' }];
	eq(moveItem(list, 0, 2), true);
	eq(list.map(item => item.type), ['b', 'c', 'a'], '挪到末尾 = 画在最上面');
	eq(moveItem(list, 2, 0), true);
	eq(list.map(item => item.type), ['a', 'b', 'c']);
	eq(moveItem(list, 1, 1), false, '原地不动返回 false');
	eq(moveItem(list, 9, 0), false);
	const appended = [];
	eq(appendElement(appended, { type: 'text' }), 0);
	eq(appended.length, 1);
	return true;
});

check('新建元素：各类型都带够"画得出来"的键', () => {
	eq(newElement('rect').w, 0.3);
	eq(newElement('rect').h, 0.2);
	eq(newElement('roundrect').radius, 0.08);
	eq(newElement('circle').radius, 0.1);
	eq(newElement('gauge').radius, 0.44);
	eq(newElement('line').x2, 0.6);
	eq(newElement('text').text.length > 0, true);
	eq(newElement('text').size, 0.4);
	eq(newElement('text').align, 'center');
	// image / foreach 是 F3 那一轮才进键表的（schema.json 的 elementTypes 有 9 种）
	eq(newElement('image').w, 0.3);
	eq(newElement('image').fit, 'stretch');
	eq(newElement('foreach').limit, 32);
	eq(Array.isArray(newElement('foreach').elements), true);
	eq(newElement('foreach').elements[0].type, 'text');
	return true;
});

check('图层列表那一行：text 显示模板、gauge 显示 needle', () => {
	eq(elementSummary({ type: 'text', text: '{pid.service}' }), 'text 「{pid.service}」');
	eq(elementSummary({ type: 'gauge', needle: { var: 'speedKmh' } }), 'gauge needle={"var":"speedKmh"}');
	eq(elementSummary({ type: 'gauge' }), 'gauge needle=缺省（车速）');
	eq(elementSummary({ type: 'image', src: 'mmtr:vehicle/face/x.png' }), 'image mmtr:vehicle/face/x.png');
	eq(moveLabel({ type: 'text', text: 'A' }, 0.42, 0.5), '移动 text 到 0.42, 0.5');
	return true;
});

// ---- 10. 深拷贝（撤销栈的地基） ------------------------------------------------------------------

check('cloneJson：改副本不影响原件（撤销栈靠这条）', () => {
	const root = { faces: { a: { elements: [{ type: 'text', x: 0.5 }] } } };
	const copy = cloneJson(root);
	copy.faces.a.elements[0].x = 0.9;
	eq(root.faces.a.elements[0].x, 0.5);
	eq(copy.faces.a.elements[0].x, 0.9);
	return true;
});

// ---- 11. 导出 Java 骨架（F4 代码面，"导出 Java 骨架"按钮的纯函数层） ----------------------------
/*
 * 这几条钉的是"按钮生成的东西对不对"，理由是这两类错在页面上都看不出来：
 *   · **漏捞一类**：作者照骨架写完，游戏里那块面还是不认识那个 type/字段；
 *   · **捞错**：把内置的东西也当扩展（生成一堆没用的桩）、或者把 vars/forEach 绑定名当缺字段。
 * ④ 那一层"真的能编译"由 sandbox/face-addon/export-probe/compile-probe.ps1 钉（真的跑 javac）。
 */

const EXPORT_SCHEMA = {
	elementTypes: ['text', 'rect', 'gauge', 'foreach'],
	sections: [
		{ name: 'common', keys: [{ name: 'type' }, { name: 'x' }, { name: 'y' }, { name: 'w' }, { name: 'h' }, { name: 'text' }] },
		{ name: 'element:text', keys: [] },
	],
};
const EXPORT_FIELDS = ['pid.service', 'pid.terminus', 'speedKmh', 'motor.forceN'];

check('★ 骨架：四类扩展各捞一个（元素/算子/过滤器/字段），且内置的一个都不生成', () => {
	const face = {
		vars: { fast: { '>': [{ var: 'speedKmh' }, 80] } },
		elements: [
			{ type: 'vendor:bar', x: 0.1, y: 0.1, w: 0.8, h: 0.2, value: { 'vendor:percent': [{ var: 'vendor:traction' }, 100] }, pulse: true },
			{ type: 'text', x: 0.5, y: 0.5, size: 0.3, text: '{vendor:traction|vendor:kmh}' },
			{ type: 'gauge', x: 0.5, y: 0.5, radius: 0.4, needle: { var: 'speedKmh' } },
			{ type: 'text', x: 0.5, y: 0.9, size: 0.2, text: '开往 {pid.terminus}' },
		],
	};
	const report = scanFaceForExtensions(face, EXPORT_SCHEMA, EXPORT_FIELDS, true);
	eq(report.elements.map(item => item.type), ['vendor:bar'], '非内置类型');
	eq(report.elements[0].keys, ['pulse', 'value'], '报给拼写守卫的键（common 的 x/y/w/h 不报）');
	eq(report.operators, ['vendor:percent'], '非内置算子（32 个之外的）');
	eq(report.filters, ['vendor:kmh'], '★ 非内置过滤器：名字自带冒号，必须整体捞出来（不能切成 vendor）');
	eq(report.fields, ['vendor:traction'], '不在 fields.json 里的字段名');
	eq(extensionReportIsEmpty(report), false);
	return true;
});

check('★ 骨架：vars / forEach 绑定名不算"缺字段"（不然会给内置数据路径刷一堆桩）', () => {
	const face = {
		vars: { arriving: { '<': [{ var: 'lzb.targetM' }, 200] } },
		elements: [{
			type: 'foreach',
			var: 'calls',
			as: 'call',
			index: 'i',
			elements: [
				{ type: 'text', x: 0.5, y: 0.5, size: 0.3, text: '{call.name} #{i}' },
				{ type: 'text', x: 0.5, y: 0.2, size: 0.2, text: '{pid.service}' },
			],
		}],
	};
	const report = scanFaceForExtensions(face, EXPORT_SCHEMA, ['pid.service', 'calls', 'lzb.targetM'], true);
	eq(report.fields, [], '绑定的名字、vars 的值、字段表里的名字都不该进"缺字段"');
	eq(report.elements, [], 'foreach 是内置类型，不该生成元素桩');
	return true;
});

check('★ 骨架：只用内置东西的一块面 = 空报告（页面要说"不需要扩展"，并给最小骨架）', () => {
	const face = { elements: [{ type: 'text', x: 0.5, y: 0.5, size: 0.3, text: '开往 {pid.terminus|upper}' }] };
	const report = scanFaceForExtensions(face, EXPORT_SCHEMA, EXPORT_FIELDS, true);
	eq(extensionReportIsEmpty(report), true);
	const skeleton = buildExtensionSkeleton(report, { className: 'PlainFaceExtension', packageName: 'vendor.demo', faceName: 'pid_1' });
	eq(skeleton.empty, true);
	eq(skeleton.total, 0);
	// 判"一个桩都没有"要**逐条**数，不能拿 `registrar.element(` 去 includes：
	// 最小骨架里那句"以后要加就在这里加一行"的 TODO 注释本身就写着 registrar.element(
	const generated = (skeleton.java.match(/^\t\tregistrar\.(element|function|filter|field)\(/gm) || []).length;
	eq(generated, 0, '没有扩展就不该出现任何桩（实际 ' + generated + ' 个）');
	eq(skeleton.java.includes('// 这个类是**最小可用骨架**'), true, '要说清这是最小可用骨架');
	eq(skeleton.java.includes('package vendor.demo;'), true);
	return true;
});

check('★ 骨架：生成的 Java 里四类桩都在，且声明了它认的键', () => {
	const face = {
		elements: [
			{ type: 'vendor:bar', x: 0.1, y: 0.1, w: 0.8, h: 0.2, value: { 'vendor:percent': [1, 2] }, pulse: true },
			{ type: 'text', x: 0.5, y: 0.5, size: 0.3, text: '{vendor:traction|vendor:kmh}' },
		],
	};
	const report = scanFaceForExtensions(face, EXPORT_SCHEMA, EXPORT_FIELDS, true);
	const skeleton = buildExtensionSkeleton(report, { className: 'VendorBarExtension', packageName: 'vendor.demo', faceName: 'pid_1' });
	const java = skeleton.java;
	eq(skeleton.total, 4);
	eq(java.includes('public final class VendorBarExtension implements MmtrFaceExtension {'), true);
	eq(java.includes('public void register(MmtrFaceRegistrar registrar) {'), true);
	eq(java.includes('registrar.element("vendor:bar", (canvas, document, element, paint) -> {'), true);
	eq(java.includes('}, "pulse", "value");'), true, '声明的键要报全（报少了 → 作者写那个键会被当拼错）');
	eq(java.includes('registrar.function("vendor:percent", (arguments, data) -> {'), true);
	eq(java.includes('registrar.filter("vendor:kmh", (value, parameter) -> {'), true);
	eq(java.includes('registrar.field(new MmtrFaceField() {'), true);
	eq(java.includes('return "vendor:traction";'), true);
	eq(java.includes('public Object value(Map<String, Object> values) {'), true);
	eq(java.includes('return null;'), true, '字段桩必须 return null（与"取不到 = 不放进去"同一条口径）');
	// 四类都缺注释（作者照着写要看得懂）
	for (const marker of ['// TODO 画「vendor:bar」', '// TODO 实现「vendor:percent」', '// TODO 实现「vendor:kmh」', '// TODO 实现「vendor:traction」']) {
		eq(java.includes(marker), true, '缺注释：' + marker);
	}
	// 服务声明：一行全限定名 + 该放哪
	eq(skeleton.serviceFileContent, 'vendor.demo.VendorBarExtension\n');
	eq(skeleton.serviceFileName, 'META-INF/services/org.mtr.mod.mmtr.face.MmtrFaceExtension');
	eq(java.includes('--allow-type vendor:bar'), true, '骨架里要写好自检那条命令（免得作者被 P4 吓一跳）');
	eq(java.includes('--extra-fields'), true);
	return true;
});

check('★ 骨架：不带命名空间的名字要警告（引擎会拒绝注册，作者得先改名字）', () => {
	const face = { elements: [{ type: 'bar', x: 0.1, y: 0.1, w: 0.5, h: 0.1 }] };
	const report = scanFaceForExtensions(face, EXPORT_SCHEMA, EXPORT_FIELDS, true);
	const skeleton = buildExtensionSkeleton(report, { faceName: 'pid_1' });
	eq(report.elements.map(item => item.type), ['bar'], '不带冒号也算"非内置类型"（要生成桩）');
	eq(skeleton.warnings.length, 1);
	eq(skeleton.warnings[0].includes('没有命名空间'), true);
	return true;
});

check('骨架：类名从名字洗成合法 Java 标识符；带点/怪字符一律清掉', () => {
	eq(javaClassFromType('mmtr_pid_1'), 'MmtrPid1');
	eq(javaClassFromType('vendor:dest-bar'), 'VendorDestBar');
	eq(javaClassFromType('——'), 'Vendor', '洗不出东西就回退');
	eq(isNamespacedName('vendor:bar'), true);
	eq(isNamespacedName('vendor:'), false);
	eq(isNamespacedName(':bar'), false);
	eq(isNamespacedName('bar'), false);
	return true;
});

check('★ 过滤器名最长匹配：`vendor:kmh:mph` 的名字是 `vendor:kmh`（不是 `vendor`）', () => {
	eq(filterNameOf('vendor:kmh:mph', ['vendor:kmh']).name, 'vendor:kmh');
	eq(filterNameOf('vendor:kmh:mph', ['vendor:kmh']).parameter, 'mph');
	eq(filterNameOf('pad:7').name, 'pad', '内置的照旧');
	eq(filterNameOf('pad:7').parameter, '7');
	eq(filterNameOf('upper').name, 'upper');
	eq(filterNameOf('upper').parameter, '');
	eq(filterNameOf('nope:x').name, 'nope:x', '不认识的整段当名字（由调用方报 P7）');
	return true;
});

// ---- 报账 ---------------------------------------------------------------------------------------

console.log('[designer-selftest] 绘制器纯函数层 · 逐条断言');
console.log('');
for (const result of results) {
	console.log((result.ok ? '通过  ' : '失败  ') + result.name + (result.ok || !result.note ? '' : ''));
	if (!result.ok) {
		console.log('      ' + result.note);
	}
}
console.log('');
console.log('[designer-selftest] 通过 ' + (results.length - failed) + ' / 失败 ' + failed + ' / 共 ' + results.length + ' 条');
process.exit(failed === 0 ? 0 : 1);
