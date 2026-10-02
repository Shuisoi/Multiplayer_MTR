/*
 * document.mjs —— 车辆动态面的**面文档**：faces[面名] 的解析、require 门、vars、多页、forEach 展开，
 * 以及旧的 pid/next 段翻译。
 *
 * 这是 MmtrFaceDocument.java 的移植（F0 的 v1 形态 + F3 的 v2 形态）。与新面文档无关的东西照样做了：
 *   · 坏键按缺省值处理（不是报错），坏元素丢掉；
 *   · 缺省底色是**透明**（0）—— 牌底通常由模型材质给出，什么都不写就铺一层黑会把图案盖掉；
 *   · 旧 pid/next 段是"糖"，由 builtinPid 翻成等价的面文档，于是老包的水牌在工作室里也看得到。
 *
 * v2 加上的：pages/pageSeconds/pageExpr（多页）、每元素 anim、forEach（按列表重复）、
 * roll/tilt/drum（整面姿态）、fps（动画节拍）。**v1 文档读起来逐字不变**（conformance/document.json 钉着）。
 *
 * 缺省值**从键表读**（schema.mjs 的 defaultNumber/defaultString，权威是 Java 的 MmtrFaceSchema 导出的
 * schema.json），所以"面板上显示的缺省"与"引擎真的用的缺省"是同一个数。
 *
 * 与 Java 的一处**有意**差异：Java 的 fromAnchors 解析失败时返回 null（"坏文档不该让车画不出来"），
 * 工作室则要**把原因说出来**（作者指南第 10 节：这类问题在游戏里是静默的）。所以这里解析失败会抛
 * FaceDocumentError，由页面显示；游戏侧那条"退回 null"的策略不受影响。
 */

import { asString, evaluate, lookup, truthy } from './logic.mjs';
import { resolve as resolveTemplate } from './text.mjs';
import { parseAnim } from './anim.mjs';
import { defaultNumber, defaultString } from './schema.mjs';

// 颜色一律按 Java 的 int（**有符号 int32**）表示，所以字面量后面都 | 0 —— 这样
// "#FF101418"（parseColor 出来的）与 LEGACY_DEFAULT_BACKGROUND 是同一个数，比较起来不会不一致。
/** 新面文档的缺省底色：透明（= 不铺底，让模型的材质透出来）。 */
export const FACE_DEFAULT_BACKGROUND = 0;
/** 旧 pid/next 段的缺省底色（= notes/357 那套写死的深色牌底）。 */
export const LEGACY_DEFAULT_BACKGROUND = 0xFF101418 | 0;
/** 文本元素的缺省颜色。 */
export const DEFAULT_TEXT = 0xFFF2F4F6 | 0;
/**
 * 文字最多占牌宽的多少（超了等比缩小）；shrinkToFit 写 0 = 不缩。
 * 值从键表读（`element:text` 那节），这里再导出一份老名字，免得既有引用断掉。
 */
export const DEFAULT_SHRINK_TO_FIT = defaultNumber('text', 'shrinkToFit', 0.90);
/** 文档没写 pxPerMetre 时，调用方的默认像素密度（与 FacePreview 的 DEFAULT_PX_PER_METRE 相同）。 */
export const DEFAULT_PX_PER_METRE = 512;

/** forEach 的类型名。 */
export const FOREACH_TYPE = 'foreach';
/** forEach 一层最多展开几项（作者写 limit:0 = 一项都不画）。 */
export const MAX_FOREACH_ITEMS = 256;
/** forEach 嵌套层数上限（不可信内容：套娃式重复会指数爆炸）。 */
export const MAX_FOREACH_DEPTH = 4;
/** 一页最多画几个元素（展开之后；超了截断 —— "别让一份坏文档把一帧拖死"）。 */
export const MAX_DRAWABLES = 512;

/** 旧版式的两个字号比例（notes/357 那套写死的排版）。 */
const LEGACY_MAIN_SIZE = 0.52;
const LEGACY_SUB_SIZE = 0.24;
/** 旧 next 牌的固定标签（= MmtrPidText.NEXT_STATION_LABEL）。 */
export const NEXT_STATION_LABEL = '下一站';

/** 面文档解析失败（JSON 坏了、faces 里没有这块面、某个键的类型用不了）。 */
export class FaceDocumentError extends Error {
	constructor(message) {
		super(message);
		this.name = 'FaceDocumentError';
	}
}

// ---- JSON 取值的小工具（照 Java 的 JsonObject 那套"缺省值 + 类型不许乱"的口径） ----------------

function isPlainObject(value) {
	return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function has(object, key) {
	return Object.prototype.hasOwnProperty.call(object, key);
}

/**
 * Java 的 JsonObject.getAsDouble：数字直接用，数字串解析，别的一律抛。
 * 布尔/对象上写几何量在游戏里会让**整块面**退回 null（parseObject 抛异常），工作室把这句话说出来。
 */
function numberValue(object, key, fallback) {
	if (!has(object, key) || object[key] === null) {
		return fallback;
	}
	const value = object[key];
	if (typeof value === 'number') {
		return value;
	}
	if (typeof value === 'string') {
		const trimmed = value.trim();
		if (/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(trimmed)) {
			return Number(trimmed);
		}
	}
	throw new FaceDocumentError('键「' + key + '」要的是数字，写的是 ' + JSON.stringify(value));
}

/** Java 的 JsonObject.getAsString：字符串原样，数字/布尔转文本，对象/数组抛。 */
function stringValue(object, key, fallback) {
	if (!has(object, key) || object[key] === null) {
		return fallback;
	}
	const value = object[key];
	if (typeof value === 'string') {
		return value;
	}
	if (typeof value === 'number' || typeof value === 'boolean') {
		return asString(value);
	}
	throw new FaceDocumentError('键「' + key + '」要的是文本，写的是 ' + JSON.stringify(value));
}

function clamp01(value) {
	return Math.max(0, Math.min(1, value));
}

function clamp(value, min, max) {
	return Math.max(min, Math.min(max, value));
}

/**
 * Java 的 `(int) double` 窄化：向零截断，超出 int32 范围时**饱和**到 Integer.MAX_VALUE / MIN_VALUE。
 *
 * ★ 是饱和，不是两补码回绕 —— JLS 5.1.3 对"浮点 → 整数"就是这么规定的（NaN ⇒ 0、太大 ⇒ 饱和）。
 * 这一条看得见：`fps` 写 1e10 时 Java 得到 Integer.MAX_VALUE（> 30 ⇒ 回落缺省 8）；
 * 若按回绕算会得到一个"看起来合法"的数（1e10 回绕成 1410065408，碰巧也 > 30，但 2^32+5 就会
 * 回绕成 5 ⇒ 与 Java 的"饱和 ⇒ 回落缺省"不同）。面文档里不该出现这种数，但两份实现在怪输入上
 * 也要走同一条路。
 */
function truncateToInt(value) {
	if (typeof value !== 'number' || Number.isNaN(value)) {
		return 0; // Java 的 (int) NaN 也是 0
	}
	if (value >= 2147483647) {
		return 2147483647;
	}
	if (value <= -2147483648) {
		return -2147483648;
	}
	return Math.trunc(value);
}

/** 这段是给"键在不在"用的：文档级的布尔判据（require / when）一律走 `logicKey()`。 */
const PAGES_KEY_PRESENT = Symbol('mmtr.face.pages.present');

/**
 * ★ 一条**统一规矩**（2026-10-02 与 Java 的 MmtrFaceDocument.logic() 同步）：
 * `require` / `when` / `pageExpr` / `of` / `needle` 上的**显式 JSON `null` 等于"这个键不在"**。
 *
 * 为什么：JSON 里本来就没有"有值但是 null"这种状态；而"作者写了 `require: null` 就静默不画"
 * 是最坏的一类失败 —— 牌在游戏里消失、工作室预览也不画，但没人知道为什么。
 * 于是：`require: null` ⇒ 没有门（总是可见）；`when: null` ⇒ 总是画；`pageExpr: null` ⇒ 没指定页。
 */
function logicKey(object, key) {
	if (object === null || object === undefined || !has(object, key)) {
		return null;
	}
	const value = object[key];
	return value === null ? null : value;
}

/**
 * #RRGGBB / #AARRGGBB / 0xRRGGBB；认不出来用 fallback。返回**有符号 int32**（与 Java 的 int 一致）。
 *
 * ★ **`0` 是哨兵值，不是黑色**（2026-10-02 与 Java 同步）：文档与键表都写着
 * `background`/`color` 的 `0` = "不铺底 / 用 textColor / 全透明"，而按十六进制算 `"0"` 会得到
 * `0xFF000000`（**不透明黑**）—— 作者写 `"background": 0` 会得到一块黑板，与文档说的相反。
 * 所以 `"0"`（以及 `#0` / `0x0` / `" 0 "`，也就是"去掉前缀与空白之后只剩一个 0"）一律直接返回 `0`。
 *
 * 六位全零 `000000` 仍然是**不透明黑**（`0xFF000000`），`#00000000` 仍然是全透明 —— 这条边界
 * 正是"0 与 000000 不是一回事"的判据（共享向量里有）。
 */
export function parseColor(value, fallback) {
	if (value === null || value === undefined || value === '') {
		return fallback;
	}
	let hex = String(value).trim();
	if (hex.startsWith('#')) {
		hex = hex.substring(1);
	} else if (hex.startsWith('0x') || hex.startsWith('0X')) {
		hex = hex.substring(2);
	}
	if (!/^[0-9a-fA-F]+$/.test(hex) || hex.length > 16) {
		return fallback;
	}
	if (hex === '0') {
		return 0;
	}
	const parsed = parseInt(hex, 16);
	// | 0 就是 Java 的 (int) 截断：8 位色 #FFFFB300 落到 int32 里是个负数
	return (hex.length <= 6 ? (0xFF000000 | parsed) : parsed) | 0;
}

/** 0xAARRGGBB（有符号 int32 也行）→ "#AARRGGBB"（页面显示用；透明度为 FF 时给 #RRGGBB）。 */
export function colorToHex(argb) {
	const value = (Number(argb) | 0) >>> 0;
	const hex = value.toString(16).toUpperCase().padStart(8, '0');
	return hex.startsWith('FF') ? '#' + hex.substring(2) : '#' + hex;
}

/** 一块面画在哪一侧（映射到 MmtrPanelQuad.Side；面不带游戏依赖，所以这里只存名字）。 */
export function parseSide(value) {
	switch (String(value === null || value === undefined ? '' : value).trim().toLowerCase()) {
		case 'driver':
			return 'driver';
		case 'both':
			return 'both';
		default:
			return 'normal';
	}
}

// ---- 元素 ---------------------------------------------------------------------------------------

/** 一个元素：几何与文本是通用的，类型专属的键留在 raw 里由画法自己读。 */
export class FaceElement {
	constructor(init) {
		this.type = init.type;
		this.when = init.when;
		/** when 这个键**在不在**（Java 里"没有 when"与"when: null"是两件事：前者总是画，后者永不画）。 */
		this.whenPresent = init.whenPresent;
		this.x = init.x;
		this.y = init.y;
		this.w = init.w;
		this.h = init.h;
		this.size = init.size;
		this.color = init.color;
		this.align = init.align;
		this.text = init.text;
		this.raw = init.raw;
		/** 逐帧动画（null = 不动；见 anim.mjs）。 */
		this.anim = init.anim === undefined ? null : init.anim;
		/** forEach 的子元素表（其余类型是空数组）。 */
		this.children = init.children === undefined ? [] : init.children;
	}

	/**
	 * 读一个数字键：**先看 raw，再问键表**，键表里也没有才用 fallback。
	 *
	 * 这一条与 Java 的 Element.number 逐字相同，也是"缺省只有一处事实"的落点：
	 * 画法里写 `element.number('radius', 0.44)` 时，0.44 只在该键没登记时才生效（它登记了，所以以表为准）。
	 */
	number(key, fallback) {
		const value = numberValue(this.raw, key, undefined);
		return value === undefined ? defaultNumber(this.type, key, fallback) : value;
	}

	/** 读一个文本键（缺省同样先走键表）。 */
	string(key, fallback) {
		const value = stringValue(this.raw, key, undefined);
		return value === undefined ? defaultString(this.type, key, fallback) : value;
	}

	/** 读一个布尔键（缺省 = 键表里的 true/false）。 */
	flag(key, fallback) {
		if (!has(this.raw, key) || this.raw[key] === null) {
			const declared = defaultString(this.type, key, null);
			return declared === null ? fallback : declared.trim().toLowerCase() === 'true';
		}
		const value = this.raw[key];
		if (typeof value === 'boolean') {
			return value;
		}
		if (typeof value === 'number') {
			return value !== 0;
		}
		if (typeof value === 'string') {
			return value.trim().toLowerCase() === 'true';
		}
		throw new FaceDocumentError('键「' + key + '」要的是真假值，写的是 ' + JSON.stringify(value));
	}

	/** 这个元素要画的话，会画出来的字（不是文本元素 / 条件不成立 / 解析为空 ⇒ 空串）。 */
	resolveText(data) {
		return this.resolveTextAt(data, 0);
	}

	/** 同上，带时钟（blink 灭着的这一瞬间算"不画"）。 */
	resolveTextAt(data, timeMs) {
		if (this.type !== 'text') {
			return '';
		}
		// 没有 when = 总是画（★ 不能把"没有条件"当成"条件不成立"）
		if (this.whenPresent && !truthy(evaluate(this.when, data))) {
			return '';
		}
		if (this.anim !== null && !this.anim.visible(timeMs)) {
			return '';
		}
		return resolveTemplate(this.text, data);
	}
}

// ---- 页与文档 -----------------------------------------------------------------------------------

/** 一页：一个可选的页名、一个可选的门、一串元素。 */
export class FacePage {
	constructor(name, require, requirePresent, elements) {
		this.name = name;
		this.require = require;
		this.requirePresent = requirePresent;
		this.elements = elements;
	}
}

/** 一块面的文档。几何一律是**比例**（相对牌面宽高），所以同一份文档能贴到任何尺寸的牌上。 */
export class FaceDocument {
	constructor(init) {
		this.id = init.id;
		this.name = init.name;
		this.background = init.background;
		this.textColor = init.textColor;
		this.pxPerMetre = init.pxPerMetre;
		this.side = init.side;
		this.require = init.require;
		/** require 这个键**在不在**（"没有 require" = 总是可见；"require: null" = 求值得 null ⇒ 不可见）。 */
		this.requirePresent = init.requirePresent;
		this.vars = init.vars;
		/** 顶层元素表。v1 文档的唯一形态；写了 pages 时它被忽略（ignoredTopLevelElements）。 */
		this.elements = init.elements;
		/** 各页；null = 没写 pages（于是只有"元素表那一页"）。 */
		this.pages = init.pages === undefined ? null : init.pages;
		this.pageSeconds = init.pageSeconds === undefined ? defaultNumber('document', 'pageSeconds', 6) : init.pageSeconds;
		this.pageExpr = init.pageExpr === undefined ? null : init.pageExpr;
		/** pageExpr 这个键**在不在**（自动轮转要问它：写了 pageExpr 就压过 pageSeconds）。 */
		this.pageExprPresent = init.pageExprPresent === true;
		this.fps = init.fps === undefined ? defaultNumber('document', 'fps', 8) : init.fps;
		this.roll = init.roll === undefined ? 0 : init.roll;
		this.tilt = init.tilt === undefined ? 0 : init.tilt;
		/** 翻牌机参数；null = 不是翻牌机。 */
		this.drum = init.drum === undefined ? null : init.drum;
		/** true = 由旧 pid/next 段翻译来的（页面会标出来）。 */
		this.builtin = init.builtin === true;
	}

	/** 这块面现在该不该画（文档级 require 门）。 */
	visible(data) {
		return visible(this, data);
	}

	/** 这块面现在会画出来的字（自上而下，取不到内容的元素已剔除）。 */
	texts(data) {
		return texts(this, data);
	}

	/** 把 vars 求值后并进数据（数据不是对象时原样返回）。 */
	augment(data) {
		return augment(this, data);
	}

	/** 各页（没写 pages 时 = 唯一的一页，内容就是 elements）。 */
	allPages() {
		return allPages(this);
	}

	/** 现在该画哪一页（下标）。 */
	pageIndex(data, timeMs) {
		return pageIndex(this, data, timeMs);
	}

	/**
	 * 当前这一页走到哪儿了（0..1）—— 翻牌机用它算翻转角度。
	 *
	 * ★ 这个方法原来漏了（painter 的 typeof 守卫把它当成"没有"⇒ 一律按 0 走），
	 * 于是 drum 的预览永远停在"周期开头" —— 与 Java 的 FaceDocument.pageClockFraction 对齐后补上。
	 */
	pageClockFraction(timeMs) {
		return pageClockFraction(this, timeMs);
	}

	/** 这一帧到底要画哪些元素（选页 + forEach 展开都算完）。 */
	plan(data, timeMs) {
		return plan(this, data, timeMs);
	}
}

/**
 * 从锚点 JSON 文本里取一块面。锚点文件没有 faces 段、或没有这个面名 ⇒ 抛 FaceDocumentError。
 *
 * @param anchorFileText 锚点 JSON 的**文本**（不是路径）
 * @param faceName       faces 里的键（= 锚点名）
 * @param id             文档身份（进"只提示一次"的记忆；页面用不到就留空）
 */
export function parseDocument(anchorFileText, faceName, id) {
	const root = parseAnchorFile(anchorFileText);
	const faces = isPlainObject(root.faces) ? root.faces : null;
	if (faces === null) {
		throw new FaceDocumentError('这份锚点文件里没有 faces 段（说明它是老包：只有 pid/next 段）');
	}
	if (!has(faces, faceName)) {
		const names = Object.keys(faces);
		throw new FaceDocumentError('faces 里没有「' + faceName + '」这块面' + (names.length === 0 ? '（faces 是空的）' : '；有这些：' + names.join('、')));
	}
	const face = faces[faceName];
	if (!isPlainObject(face)) {
		throw new FaceDocumentError('faces.' + faceName + ' 不是对象，写的是 ' + JSON.stringify(face));
	}
	return parseFaceObject(id || ('face-' + faceName), faceName, face);
}

/**
 * 解析一个面对象（faces[name] 的内容）。坏的键按缺省值处理，坏的元素丢掉。
 *
 * 顺序与 Java 的 parseObject 一致：写 pages 时先看 pages，**elements 照样解析**（两者都写时
 * ignoredTopLevelElements 会说一句，但 elements 仍要能读出来 —— 那是"作者以为自己在改哪一份"的线索）。
 */
function parseFaceObject(id, name, object) {
	const background = parseColor(stringValue(object, 'background', ''), FACE_DEFAULT_BACKGROUND);
	const textColor = parseColor(stringValue(object, 'textColor', ''), DEFAULT_TEXT);
	const pxPerMetre = Math.max(0, truncateToInt(numberValue(object, 'pxPerMetre', 0)));
	const side = parseSide(stringValue(object, 'side', 'normal'));
	// 显式 null = 没有门（见 logicKey 的注释）
	const require = logicKey(object, 'require');
	const requirePresent = require !== null;
	const vars = isPlainObject(object.vars) ? object.vars : null;

	// v1：顶层 elements。写坏了（不是数组）在 Java 那边会让整块面退回 null，这里也当解析失败。
	const elements = has(object, 'elements') && object.elements !== null
		? parseElements(object.elements, 'elements')
		: [];
	// v2：pages。**注意 pages 为空数组时 Java 存成 null**（pages.isEmpty() ? null : pages），
	// 于是"pages: []"与"没写 pages"在引擎里是同一件事：没有多页，那就画 elements 那一页。
	let pages = null;
	if (has(object, 'pages') && object.pages !== null) {
		if (!Array.isArray(object.pages)) {
			throw new FaceDocumentError('pages 要的是一个数组，写的是 ' + JSON.stringify(object.pages));
		}
		const parsed = [];
		for (const raw of object.pages) {
			if (!isPlainObject(raw)) {
				continue;
			}
			const pageRequire = logicKey(raw, 'require');
			parsed.push(new FacePage(
				stringValue(raw, 'name', defaultString('page', 'name', '')),
				pageRequire,
				pageRequire !== null,
				has(raw, 'elements') && raw.elements !== null ? parseElements(raw.elements, 'pages[].elements') : [],
			));
		}
		pages = parsed.length === 0 ? null : parsed;
	}

	const pageSeconds = Math.max(0, numberValue(object, 'pageSeconds', defaultNumber('document', 'pageSeconds', 6)));
	const fps = truncateToInt(numberValue(object, 'fps', defaultNumber('document', 'fps', 8)));
	const roll = numberValue(object, 'roll', 0);
	const tilt = numberValue(object, 'tilt', 0);
	// pageExpr: null ⇒ 没有指定页（自动轮转照常生效，见 logicKey）
	const pageExpr = logicKey(object, 'pageExpr');
	return new FaceDocument({
		id,
		name,
		background,
		textColor,
		pxPerMetre,
		side,
		require,
		requirePresent,
		vars,
		elements,
		pages,
		pageSeconds,
		pageExpr,
		pageExprPresent: pageExpr !== null,
		fps,
		roll,
		tilt,
		drum: parseDrum(object.drum),
		builtin: false,
	});
}

/**
 * drum 段（null = 不是翻牌机）。面数钳 2..8 —— 一面两块牌不是翻牌机，九面看不清。
 * 坏值（字符串、对象）与 Java 的 `!element.isJsonObject()` 一样当"不是翻牌机"。
 */
function parseDrum(element) {
	if (element === null || element === undefined || !isPlainObject(element)) {
		return null;
	}
	const count = clamp(truncateToInt(numberValue(element, 'count', defaultNumber('drum', 'count', 2))), 2, 8);
	const turnFraction = clamp(numberValue(element, 'turnFraction', defaultNumber('drum', 'turnFraction', 0.25)), 0, 1);
	const radiusM = Math.max(0, numberValue(element, 'radiusM', defaultNumber('drum', 'radiusM', 0)));
	return { count, turnFraction, radiusM };
}

/**
 * 一串元素（顶层、页里、forEach 的子表共用这一条路）。
 *
 * @param array    期望是数组；不是数组时**与 Java 一样**让整块面解析失败（getAsJsonArray 抛）
 * @param where    出错信息里说的位置
 */
function parseElements(array, where) {
	if (!Array.isArray(array)) {
		throw new FaceDocumentError(where + ' 要的是一个数组，写的是 ' + JSON.stringify(array));
	}
	const elements = [];
	for (const raw of array) {
		if (!isPlainObject(raw)) {
			continue;
		}
		const parsed = parseElement(raw);
		if (parsed !== null) {
			elements.push(parsed);
		}
	}
	return elements;
}

/**
 * 一个元素对象 → FaceElement；没有 type（或空）⇒ null（丢掉）。
 *
 * 几何与颜色的缺省**从键表读**（schema.mjs，权威是 Java 的 MmtrFaceSchema）——
 * 于是"面板上显示的缺省"与"引擎真的用的缺省"是同一个数。
 */
function parseElement(raw) {
	const type = stringValue(raw, 'type', defaultString('common', 'type', '')).trim().toLowerCase();
	if (type === '') {
		return null;
	}
	// forEach 的子元素表：其余类型不解析（Java 只在 FOREACH_TYPE 时递归）
	const children = type === FOREACH_TYPE
		? (has(raw, 'elements') && raw.elements !== null ? parseElements(raw.elements, 'forEach.elements') : [])
		: [];
	// when: null ⇒ 总是画（见 logicKey）；没有 when 也是总是画
	const when = logicKey(raw, 'when');
	return new FaceElement({
		type,
		when,
		whenPresent: when !== null,
		x: clamp01(numberValue(raw, 'x', defaultNumber(type, 'x', 0.5))),
		y: clamp01(numberValue(raw, 'y', defaultNumber(type, 'y', 0.5))),
		w: clamp01(numberValue(raw, 'w', defaultNumber(type, 'w', 0))),
		h: clamp01(numberValue(raw, 'h', defaultNumber(type, 'h', 0))),
		size: clamp(numberValue(raw, 'size', defaultNumber(type, 'size', 0.4)), 0.02, 1.5),
		color: parseColor(stringValue(raw, 'color', ''), 0),
		align: stringValue(raw, 'align', defaultString(type, 'align', 'center')),
		text: stringValue(raw, 'text', ''),
		raw,
		anim: parseAnim(raw.anim),
		children,
	});
}

// ---- 锚点文件 -----------------------------------------------------------------------------------

/** 解析锚点 JSON 文本（失败抛 FaceDocumentError，带原话）。 */
export function parseAnchorFile(anchorFileText) {
	if (anchorFileText === null || anchorFileText === undefined || String(anchorFileText).trim() === '') {
		throw new FaceDocumentError('锚点 JSON 是空的');
	}
	let root;
	try {
		root = JSON.parse(anchorFileText);
	} catch (e) {
		throw new FaceDocumentError('锚点 JSON 解析失败：' + e.message);
	}
	if (!isPlainObject(root)) {
		throw new FaceDocumentError('锚点 JSON 的根不是对象');
	}
	return root;
}

/** 这份锚点文件里所有面的名字（faces 的键，按文件顺序）。解析不了就返回空数组。 */
export function faceNames(anchorFileText) {
	try {
		const root = parseAnchorFile(anchorFileText);
		return isPlainObject(root.faces) ? Object.keys(root.faces) : [];
	} catch (e) {
		return [];
	}
}

/** 这份锚点文件里所有锚点（name/kind/widthM/heightM/…，按文件顺序）。 */
export function anchors(anchorFileText) {
	const root = parseAnchorFile(anchorFileText);
	return Array.isArray(root.anchors) ? root.anchors.filter(isPlainObject) : [];
}

/** 有没有这个段（老包只有 pid/next，没有 faces）。 */
export function hasSection(anchorFileText, key) {
	try {
		const root = parseAnchorFile(anchorFileText);
		return isPlainObject(root[key]);
	} catch (e) {
		return false;
	}
}

// ---- 文档级的判据 -------------------------------------------------------------------------------

/**
 * 这块面现在该不该画（文档级 require 门）。
 *
 * 注：Java 的 visible() **不**并 vars（vars 是给元素用的），这里照抄 —— require 里直接写 vars 名会取到 null。
 * 也**不**判页的 require：那是"这一页现在该不该出现"，在 plan() 里判（两件事，别混）。
 */
export function visible(document, data) {
	if (!document.requirePresent) {
		return true;
	}
	return truthy(evaluate(document.require, data));
}

/** 各页。没写 pages 时 = **唯一的一页**，内容就是 elements（v1 文档的形态）。 */
export function allPages(document) {
	return document.pages !== null && document.pages.length > 0
		? document.pages
		: [new FacePage('', null, false, document.elements)];
}

/** 页数。 */
export function pageCount(document) {
	return allPages(document).length;
}

/**
 * 同时写了 pages 与顶层 elements ⇒ **后者被忽略**（pages 说了算）。
 *
 * 不报错也不合并：合并会让"这一页到底画了什么"变得要靠心算。画法与工作室各提示一次。
 */
export function ignoredTopLevelElements(document) {
	return document.pages !== null && document.pages.length > 0 && document.elements.length > 0;
}

/**
 * 当前这一页走到哪儿了（0..1）—— 翻牌机用它算翻转角度。
 *
 * 自动轮转 = 在本页周期里的比例；pageExpr 指定页（或根本不自动转）⇒ **0**
 * （= 周期刚开头、不翻滚，于是"指定的那一面就是正面"）。
 *
 * ★ 2026-10-02 与 Java 同步改：这里原来返回 1（"已经停稳"），但翻牌机的翻转窗口在**周期末尾**，
 * clockFraction = 1 的意思是"正好翻到下一页的位置" —— 那会让 pageExpr 指定页的水牌永远显示下一页。
 */
export function pageClockFraction(document, timeMs) {
	if (!autoRotating(document)) {
		return 0;
	}
	const seconds = Math.max(0, timeMsOrZero(timeMs)) / 1000.0;
	return (seconds % document.pageSeconds) / document.pageSeconds;
}

/** 会不会自己换页（多页 + 有时长 + 没有 pageExpr 指定）。 */
export function autoRotating(document) {
	return allPages(document).length > 1 && document.pageExpr === null && document.pageSeconds > 0;
}

/**
 * 现在该画哪一页（下标）。
 *
 * 优先级：pageExpr 有值 → 用它（数 = 下标，**回绕**；字 = 页名）；否则多页 + pageSeconds > 0
 * → 按时钟轮转；否则第 0 页。求值失败 / 找不到页名 ⇒ 第 0 页（资源包里的错不该让整块牌消失）。
 *
 * @param data   数据；**本函数自己不做 augment**（与 Java 的 pageIndex 一样只做求值）——
 *               从 plan() 进来的数据已经并过 vars 了
 */
export function pageIndex(document, data, timeMs) {
	const pages = allPages(document);
	if (pages.length <= 1) {
		return 0;
	}
	if (document.pageExpr !== null && document.pageExpr !== undefined) {
		let value;
		try {
			value = evaluate(document.pageExpr, data);
		} catch (e) {
			return 0;
		}
		if (typeof value === 'number') {
			return wrapIndex(truncateToInt(value), pages.length);
		}
		if (typeof value === 'string' && value !== '') {
			for (let i = 0; i < pages.length; i++) {
				if (value === pages[i].name) {
					return i;
				}
			}
			// 页名找不到：再当数字解析一次（"1" 这种页名与下标写成同一个字）
			const text = value.trim();
			if (/^[+-]?\d+$/.test(text)) {
				return wrapIndex(Number.parseInt(text, 10), pages.length);
			}
			return 0;
		}
		return 0;
	}
	if (document.pageSeconds > 0) {
		// 与 Java 的 Math.floor(seconds / pageSeconds) % pages.size() 同一条算术（含负数由 Math.max(0,…) 挡掉）
		const seconds = Math.max(0, timeMsOrZero(timeMs)) / 1000.0;
		const index = Math.floor(seconds / document.pageSeconds) % pages.length;
		return index < 0 ? index + pages.length : index;
	}
	return 0;
}

/**
 * 这份文档有没有**随时间变**的东西（动画 / 自动轮转）。
 *
 * 重画签名按它决定要不要带时间桶（anim.mjs 的 bucket）：没有就用 F0 那套"数据不变就不重画"。
 */
export function animated(document) {
	if (autoRotating(document)) {
		return true;
	}
	for (const page of allPages(document)) {
		if (hasAnim(page.elements)) {
			return true;
		}
	}
	return false;
}

function hasAnim(elements) {
	for (const element of elements) {
		if (element.anim !== null && element.anim.animates()) {
			return true;
		}
		if (hasAnim(element.children)) {
			return true;
		}
	}
	return false;
}

/**
 * 动画重画节拍（1..30；越界用缺省 anim.mjs 的 DEFAULT_FPS）。
 * 注：Java 的 fps() 也不钳位着存，而是在读的时候给缺省 —— 这里照抄，所以 document.fps 可能是 0 或 99。
 */
export function fps(document) {
	const value = truncateToInt(document.fps);
	return value < 1 || value > 30 ? 8 : value;
}

// ---- 计划（选页 + forEach 展开） ----------------------------------------------------------------

/**
 * **这一帧到底要画哪些元素**（把 pages 选页与 forEach 展开都算完）。
 *
 * 画法拿到的每一条都带着**自己那份数据** —— forEach 把每一项链进数据的副本（as/index），
 * 于是嵌套元素里的 {"var":"call"} 直接能用，而画法本身完全不知道"重复"这件事。
 *
 * 整块面的 require **不在这里**判（那是"挂不挂牌"的运行期决定，由调用方先问 visible(document, data)）；
 * 页自己的 require 在这里判 —— 前者是"这列车有没有这块牌"，后者是"这一页现在该不该出现"。
 *
 * @returns {Array<{element: FaceElement, data: object, index: number}>}
 */
export function plan(document, data, timeMs) {
	const prepared = augment(document, data);
	const pages = allPages(document);
	const index = pageIndex(document, prepared, timeMs);
	if (index < 0 || index >= pages.length) {
		return [];
	}
	const page = pages[index];
	if (page.requirePresent && !truthy(evaluate(page.require, prepared))) {
		return [];
	}
	const out = [];
	expand(page.elements, prepared, out, 0);
	return out;
}

/**
 * forEach 展开：深度上限 MAX_FOREACH_DEPTH、总元素上限 MAX_DRAWABLES。
 *
 * ★ 深度到底时 forEach **自己**作为一个 drawable 落进去（与 Java 一样）：画法不认识这种类型，
 * 会记一条"不认识的元素类型" —— 这比静默少画一整块更容易查。
 */
export function expand(source, data, out, depth) {
	for (const element of source) {
		if (out.length >= MAX_DRAWABLES) {
			return;
		}
		if (element.type === FOREACH_TYPE && depth < MAX_FOREACH_DEPTH) {
			const items = itemsOf(element, data);
			// limit 的缺省**走键表**（32），所以这里传的 fallback 只在表里没登记 limit 时才生效
			const limit = truncateToInt(clamp(element.number('limit', MAX_FOREACH_ITEMS), 0, MAX_FOREACH_ITEMS));
			for (let i = 0; i < items.length && i < limit && out.length < MAX_DRAWABLES; i++) {
				expand(element.children, bind(data, element, items[i], i), out, depth + 1);
			}
			continue;
		}
		out.push({ element, data, index: out.length });
	}
}

/**
 * forEach 的数据：of 表达式优先，其次 var 路径；**不是列表就画 0 个**。
 *
 * `of: null` 与"没写 of"一样 ⇒ 退回 var（统一规矩，见 logicKey）。
 */
export function itemsOf(element, data) {
	const of = logicKey(element.raw, 'of');
	const value = of !== null
		? evaluate(of, data)
		: lookup(element.string('var', ''), data);
	if (Array.isArray(value)) {
		return value.slice();
	}
	// 不是列表（字段缺失 / 写成了标量）⇒ 一项都不画。这与"取不到内容的行不画"是同一条口径：
	// 缺失画成 0 项，而不是画一项空的。
	return [];
}

/**
 * 把一项绑进数据的副本（as / index）。
 *
 * `index` 绑的是**下标**（数字）；Java 那边是 (long)，asString/JSONLogic 看起来都是整数，
 * 这里用 JS 的 number（同一量级下逐字相同）。
 */
export function bind(data, element, item, index) {
	if (data === null || typeof data !== 'object' || Array.isArray(data)) {
		return data;
	}
	const copy = Object.assign({}, data);
	copy[element.string('as', 'item')] = item;
	copy[element.string('index', 'index')] = index;
	return copy;
}

/** Java 的 `((i % n) + n) % n`：下标回绕（负数也从尾部数）。 */
function wrapIndex(index, count) {
	return ((index % count) + count) % count;
}

/** 时钟：非数字（undefined/null/NaN）当 0 —— 缺省参数 timeMs = 0 的那条路要能直接进来。 */
function timeMsOrZero(timeMs) {
	return typeof timeMs === 'number' && Number.isFinite(timeMs) ? timeMs : 0;
}

/**
 * 这块面现在**会画出来的字**（自上而下，取不到内容的元素已剔除）—— 用例与日志读它。
 *
 * 与 Java 的 texts(data, timeMs) 同一条路：先 plan（选页 + 展开），再逐个 resolveText
 * （blink 灭着的这一瞬间算"不画"）。
 */
export function texts(document, data, timeMs) {
	const out = [];
	for (const drawable of plan(document, data, timeMs === undefined ? 0 : timeMs)) {
		const text = drawable.element.resolveTextAt(drawable.data, timeMs === undefined ? 0 : timeMs);
		if (text !== '') {
			out.push(text);
		}
	}
	return out;
}

/**
 * 把 vars 求值后并进数据：之后元素里的 {"var": "arriving"} 就能用。
 * 数据不是普通对象（或没有 vars）时原样返回 —— 不为了一个便利功能把数据拷来拷去。
 */
export function augment(document, data) {
	if (document.vars === null || Object.keys(document.vars).length === 0 || !isPlainObject(data)) {
		return data;
	}
	const copy = Object.assign({}, data);
	for (const key of Object.keys(document.vars)) {
		copy[key] = evaluate(document.vars[key], data);
	}
	return copy;
}

// ---- 旧 pid/next 段 → 内置面文档 -----------------------------------------------------------------

/**
 * 旧 pid/next 段 → 内置面文档（逐字等价于 MmtrPidLayout）。
 *
 * @param board           'pid'（= 终点牌）/ 'next'（= 下一站牌）；其余值按 'pid' 算
 * @param anchorFileText  锚点 JSON 文本
 *
 * 行为逐条照搬：段缺失 ⇒ 默认两行版式；既没 field 也没 text 的行丢掉；x/y 钳到 0..1、
 * size 钳到 0.02..1.5；prefix/suffix 只在字段**有值**时才写出来（否则回库趟会挂一个孤零零的"开往"）。
 *
 * v2 的新键一律按"旧段没有"处理：没有 pages（只有第 0 页）、pageSeconds = 0（不自动转）、
 * pageExpr = null、fps = 缺省、roll/tilt = 0、drum = null —— 行为与 F0 逐字不变。
 */
export function builtinPid(board, anchorFileText) {
	const isNext = /^next/i.test(String(board === null || board === undefined ? '' : board).trim());
	const sectionKey = isNext ? 'next' : 'pid';
	const section = objectSection(anchorFileText, sectionKey);
	const id = 'builtin-' + sectionKey + '@' + hashText(anchorFileText);

	if (section === null) {
		return new FaceDocument({
			id, name: sectionKey, background: LEGACY_DEFAULT_BACKGROUND, textColor: DEFAULT_TEXT,
			pxPerMetre: 0, side: 'normal', require: gateFor(isNext), requirePresent: true,
			vars: null, elements: defaultElements(isNext), builtin: true,
		});
	}

	const background = parseColor(stringValue(section, 'background', ''), LEGACY_DEFAULT_BACKGROUND);
	const textColor = parseColor(stringValue(section, 'textColor', ''), DEFAULT_TEXT);
	const pxPerMetre = Math.max(0, truncateToInt(numberValue(section, 'pxPerMetre', 0)));
	const elements = [];
	if (Array.isArray(section.rows)) {
		for (const row of section.rows) {
			if (!isPlainObject(row)) {
				continue;
			}
			const element = legacyRow(row);
			if (element !== null) {
				elements.push(element);
			}
		}
	}
	if (elements.length === 0) {
		// 空段 = 没配（与 MmtrPidLayout 一致：用默认版式，而不是画一块空牌）。
		// ★ 照搬 Java：这一支连 background/textColor 也回默认值，不保留段里写的颜色。
		return new FaceDocument({
			id, name: sectionKey, background: LEGACY_DEFAULT_BACKGROUND, textColor: DEFAULT_TEXT,
			pxPerMetre: 0, side: 'normal', require: gateFor(isNext), requirePresent: true,
			vars: null, elements: defaultElements(isNext), builtin: true,
		});
	}
	return new FaceDocument({
		id, name: sectionKey, background, textColor, pxPerMetre, side: 'normal',
		require: gateFor(isNext), requirePresent: true, vars: null, elements, builtin: true,
		pageSeconds: 0, pageExpr: null, fps: defaultNumber('document', 'fps', 8), roll: 0, tilt: 0, drum: null,
	});
}

/** 一行的旧版式 → 一个 text 元素。丢掉（返回 null）= 既没字段也没字面量。 */
function legacyRow(row) {
	const field = stringValue(row, 'field', '').trim().toLowerCase();
	const literal = stringValue(row, 'text', '');
	const prefix = stringValue(row, 'prefix', '');
	const suffix = stringValue(row, 'suffix', '');
	const path = legacyPath(field);

	let template;
	let when = null;
	let whenPresent = false;
	if (path !== '') {
		template = prefix + '{' + path + '}' + suffix;
		// ★ 只在字段有值时整行才画：否则 prefix（"开往 "）会在没终点时单独留在牌上
		if (template !== '{' + path + '}') {
			when = whenPresentExpr(path);
			whenPresent = true;
		}
	} else if (field !== '') {
		// 认不出的 field 名：老版式**保留**这一行（只是取不到字，因而不会画）。
		// 这里也保留（空模板），于是两份的"行数"逐行对得上 —— 少一行会让作者以为版式变了。
		template = '';
	} else if (literal !== '') {
		template = literal;
	} else {
		return null;
	}

	return new FaceElement({
		type: 'text',
		when,
		whenPresent,
		x: clamp01(numberValue(row, 'x', 0.5)),
		y: clamp01(numberValue(row, 'y', 0.5)),
		w: 0,
		h: 0,
		size: clamp(numberValue(row, 'size', 0.4), 0.02, 1.5),
		color: parseColor(stringValue(row, 'color', ''), 0),
		align: stringValue(row, 'align', 'center'),
		text: template,
		raw: row,
	});
}

/** 旧字段名 → 面文档里的路径（与 MmtrPidText.field 认的别名同一套）。 */
function legacyPath(field) {
	switch (field) {
		case 'service':
		case 'number':
			return 'pid.service';
		case 'terminus':
		case 'destination':
		case 'dest':
			return 'pid.terminus';
		case 'next':
		case 'nextstation':
			return 'pid.next';
		default:
			return '';
	}
}

/**
 * {"!!": [{"var": path}]} —— "这个字段有值"。
 *
 * ★ 为什么不是 {"!=": [{"var": path}, ""]}：快照里"取不到"是**不放进去**（不是放一个 null），
 * 于是 var 得 null，而 null != "" 是**真** —— 那会让"不在作业单上"的车也挂牌。
 */
function whenPresentExpr(path) {
	return { '!!': [{ var: path }] };
}

/** 整块面的门：不在作业单上 ⇒ 不挂；下一站牌还要有站名。 */
function gateFor(isNext) {
	const service = whenPresentExpr('pid.service');
	if (!isNext) {
		return service;
	}
	return { and: [service, whenPresentExpr('pid.next')] };
}

/** 段没配时的默认两行版式（= notes/357 那套写死的排版）。 */
function defaultElements(isNext) {
	if (!isNext) {
		return [
			defaultElement('{pid.service}', 0.21, LEGACY_SUB_SIZE),
			defaultElement('{pid.terminus}', 0.65, LEGACY_MAIN_SIZE),
		];
	}
	return [
		defaultElement(NEXT_STATION_LABEL, 0.21, LEGACY_SUB_SIZE),
		defaultElement('{pid.next}', 0.65, LEGACY_MAIN_SIZE),
	];
}

function defaultElement(template, y, size) {
	const raw = { type: 'text', text: template, x: 0.5, y, size, align: 'center' };
	return new FaceElement({
		type: 'text', when: null, whenPresent: false, x: 0.5, y, w: 0, h: 0, size,
		color: 0, align: 'center', text: template, raw,
	});
}

function objectSection(anchorFileText, key) {
	if (anchorFileText === null || anchorFileText === undefined || anchorFileText === '') {
		return null;
	}
	try {
		const root = JSON.parse(anchorFileText);
		if (!isPlainObject(root)) {
			return null;
		}
		return isPlainObject(root[key]) ? root[key] : null;
	} catch (e) {
		return null;
	}
}

/** 文档身份里要的那个"文本指纹"（Java 用 hashCode；这里只要稳定、够散）。 */
function hashText(text) {
	const source = text === null || text === undefined ? '' : String(text);
	let hash = 0;
	for (let i = 0; i < source.length; i++) {
		hash = (hash * 31 + source.charCodeAt(i)) | 0;
	}
	return hash;
}

/**
 * 这个字段路径在字段表（或本文档的 vars）里认不认得 —— 判据与 verify_face.js 的 P3 逐字一致：
 * 空路径（= 整个数据）、vars 里的名字、字段表里的名字，或者**是某个字段名的前缀**（"pid" 之于 "pid.service"）。
 */
export function pathIsKnown(path, knownFields, varNames) {
	const text = asString(path);
	if (text === '' || varNames.has(text) || knownFields.has(text)) {
		return true;
	}
	for (const name of knownFields) {
		if (name.startsWith(text + '.')) {
			return true;
		}
	}
	return false;
}
