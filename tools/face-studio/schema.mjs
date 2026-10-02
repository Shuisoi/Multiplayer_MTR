/*
 * schema.mjs —— 面文档 v2 的**键表**（哪些键、什么类型、缺省是什么），以及"缺省从哪儿来"这一条。
 *
 * 权威是 Java 的 MmtrFaceSchema：它导出 mmtr/tools/face-studio/schema.json，页面的属性面板按那份 JSON 生成。
 * 但**缺省值必须能在浏览器与 Node 里同步读到**（解析文档时就要用，不能等一个 await），所以这里有两层：
 *
 *   1. 内嵌兜底表 EMBEDDED —— 与 schema.json 逐字同源的一份拷贝（本文件的字面量）。
 *      没有它，file:// 打开页面、或者 schema.json 读不到时，整个工作室就瘫了；
 *   2. schema.json —— 运行时尽力加载（浏览器 fetch('schema.json')、Node 读文件）。
 *      加载成功就以它为准（缺省值的唯一事实是 Java 那张表），失败就退回内嵌表。
 *
 * ★ 两者**不许有差**：selftest.mjs 会逐节逐键比对（schema.json ↔ 本文件的内嵌表），差一条就红。
 *   有意的重复只允许存在于这一处 —— 页面与画法都从这里的 defaultNumber/defaultString 取数，
 *   于是"面板上显示的缺省"与"画的时候真的用的缺省"必然是同一个数（F3 之前是各写一份常量，迟早分家）。
 *
 * ★ 一条**统一规矩**（document.mjs 的 logicKey，与 Java 的 MmtrFaceDocument.logic() 同一条）：
 *   `require` / `when` / `pageExpr` / `of` / `needle` 上的**显式 JSON `null` 等于"这个键不在"**
 *   （`require: null` ⇒ 没有门、总是可见；`when: null` ⇒ 总是画）。所以 `logic` 类型的键在表里
 *   "缺省 = null"的意思就是"没写这个键"，不是"一个求值为 null 的条件"。
 *
 * ★ 颜色键的**哨兵值**：`background` / `color` / `labelColor` 的缺省在表里写成字符串 `"0"`，
 *   意思是"不铺底（让模型材质透出来）/ 用文档 textColor / 跟表盘同色"，**不是不透明黑**。
 *   `parseColor` 对 `"0"`（含 `#0` / `0x0` / 带空白）直接返回 0；六位全零 `000000` 才是 `0xFF000000`。
 *   所以表里的 `"0"` 与 `colorToHex(0)` 都得按哨兵读，不能当成"一个黑色字面量"。
 *
 * 与 Java 的一处**有意**差异：Java 的 JsonObject.getAsString 遇到对象/数组会抛，
 * 这里也会抛（由 document.mjs 翻成 FaceDocumentError）—— 两边都要"坏键不被静默吞掉"。
 */

/** 单个作用域里没查到这个键时用它的结果。 */
const MISSING = Symbol('mmtr.face.schema.missing');

/**
 * 键表里**一个键**的形状：{ name, type, default, values?, doc? }。
 * 字段名与 schema.json 一模一样 —— 于是比对可以逐字做，不需要一张翻译表。
 */
function key(name, type, defaultValue, doc, values) {
	const entry = { name, type, default: defaultValue, doc };
	if (values) {
		entry.values = values;
	}
	return entry;
}

/** 一个作用域：{ name, doc, keys }。 */
function section(name, doc, keys) {
	return { name, doc, keys };
}

const ELEMENT_TYPES = ['text', 'rect', 'roundrect', 'line', 'circle', 'arc', 'gauge', 'image', 'foreach'];
const ANIM_KINDS = ['blink', 'marquee', 'fade', 'spin'];

/**
 * 内嵌兜底键表：结构、节名、键序、缺省值全部与 schema.json 对齐。
 *
 * 为什么键序也要对齐：属性面板是按顺序生成的，键序变了作者看到的面板就跳了；
 * selftest 的比对是逐键的（不只看集合），于是"少一条"和"次序反了"都会红。
 */
export const EMBEDDED_SCHEMA = {
	version: 2,
	exportPath: 'mmtr/tools/face-studio/schema.json',
	elementTypes: ELEMENT_TYPES,
	animKinds: ANIM_KINDS,
	sections: [
		section('document', '整块面的键（faces[name] 这一层）', [
			key('background', 'color', '0', '整面底色；0 = 不铺底（让模型材质透出来）'),
			key('textColor', 'color', '#FFF2F4F6', '文本与 gauge 的缺省颜色'),
			key('pxPerMetre', 'int', 0, '像素密度；0 = 用引擎缺省 512'),
			key('side', 'enum', 'normal', '画在法线那侧 / 司机那侧 / 两侧', ['driver', 'normal', 'both']),
			key('require', 'logic', null, '整块面的门（不成立就一块都不画）'),
			key('vars', 'object', {}, '给条件起名字（值是一条表达式）'),
			key('fps', 'int', 8, '动画重画节拍 1..30（没有动画时用不到）'),
			key('elements', 'elements', [], '元素表；没写 pages 时它就是第 0 页'),
			key('pages', 'pages', null, '多页；写了它就忽略 elements'),
			key('pageSeconds', 'number', 6, '自动轮转周期（秒）；0 = 不自动转'),
			key('pageExpr', 'logic', null, '指定当前页（数 = 下标，字 = 页名）'),
			key('roll', 'number', 0, '整面绕自身法线滚转（度）'),
			key('tilt', 'number', 0, '整面绕自身水平轴抬起（度）'),
			key('drum', 'object', null, '翻牌机：把各页贴到 N 面棱柱上转'),
		]),
		section('page', '一页的键（pages[] 里的一项）', [
			key('name', 'text', '', '页名（给 pageExpr 用字名）'),
			key('require', 'logic', null, '这一页的门'),
			key('elements', 'elements', [], '这一页的元素表'),
		]),
		section('common', '每个元素都认的键', [
			key('type', 'enum', 'text', '元素类型', ELEMENT_TYPES),
			key('when', 'logic', null, '这个元素的显示条件'),
			key('x', 'number', 0.5, '水平位置（0..1 牌宽）'),
			key('y', 'number', 0.5, '竖直位置（0 = 牌底）'),
			key('w', 'number', 0, '宽（0..1 牌宽）'),
			key('h', 'number', 0, '高（0..1 牌高）'),
			key('size', 'number', 0.4, '字高（0..1 牌高）'),
			key('color', 'color', '0', '颜色；0 = 用文档 textColor（只对 text/gauge）'),
			key('align', 'enum', 'center', '文本水平对齐', ['left', 'center', 'right']),
			key('text', 'template', '', '文本模板：{字段} / {字段|过滤器}'),
			key('rotate', 'number', 0, '绕自身锚点在面内转（度，顺时针为正）'),
			key('opacity', 'number', 1, '整元素透明度 0..1'),
			key('anim', 'object', null, '逐帧动画（见 anim 段）'),
		]),
		section('drum', '翻牌机的键（drum 段）', [
			key('count', 'int', 2, '棱柱面数 2..8（第 i 面贴第 i 页）'),
			key('turnFraction', 'number', 0.25, '一个周期里用于翻转的比例'),
			key('radiusM', 'number', 0, '棱柱外接半径（米）；0 = 正棱柱'),
		]),
		section('element:text', '元素 text 的专属键', [
			key('shrinkToFit', 'number', 0.9, '超宽等比缩小；0 = 不缩'),
		]),
		section('element:rect', '元素 rect 的专属键', []),
		section('element:roundrect', '元素 roundrect 的专属键', [
			key('radius', 'number', 0.08, '圆角半径（牌高比例）'),
		]),
		section('element:line', '元素 line 的专属键', [
			// ★ 这两个的缺省是 **null**（不是 0.5）：文档格式 §5 写的是"x2,y2 缺省 = x,y"，
			// 也就是"跟着元素自己的锚点走"。画法读它时必须把元素自己的 x/y 当 fallback 传进去
			// （element.number('x2', element.x)），不能自己写一个 0.5 —— 那会把线画到牌中间去。
			key('x2', 'number', null, '终点水平位置（缺省 = 与 x 相同）'),
			key('y2', 'number', null, '终点竖直位置（缺省 = 与 y 相同）'),
			key('width', 'number', 0.03, '线宽（牌高比例）'),
		]),
		section('element:circle', '元素 circle 的专属键', [
			key('radius', 'number', 0.1, '半径（牌高比例）'),
		]),
		section('element:arc', '元素 arc 的专属键', [
			key('radius', 'number', 0.4, '半径（牌高比例）'),
			key('start', 'number', 0, '起始角（度，逆时针从 +X 起）'),
			key('end', 'number', 360, '结束角'),
			key('width', 'number', 0.01, '线宽（牌高比例）'),
		]),
		section('element:gauge', '元素 gauge 的专属键', [
			key('radius', 'number', 0.44, '表盘半径（牌高比例）'),
			key('start', 'number', 225, '刻度起始角'),
			key('end', 'number', -45, '刻度结束角'),
			key('width', 'number', 0, '表盘线宽（别名 lineWidth）'),
			key('lineWidth', 'number', 0.004, '表盘线宽'),
			key('max', 'number', 160, '指针满偏对应的值'),
			key('ticks', 'number', 0, '刻度格数；0 = 不画刻度'),
			key('tickLength', 'number', 0.12, '刻度长度（牌高比例）'),
			key('labelEvery', 'number', 0, '每隔几格写数字；0 = 不写'),
			key('labelSize', 'number', 0.13, '数字字高（牌高比例）'),
			key('labelColor', 'color', '0', '数字颜色；0 = 跟表盘同色'),
			key('needle', 'logic', null, '指针值表达式（缺省车速 km/h）'),
			key('needleColor', 'color', '#FFFF3B30', '指针颜色'),
			key('needleWidth', 'number', 0.05, '指针宽度（牌高比例）'),
			key('needleLength', 'number', 0.94, '指针长度（表盘半径比例）'),
		]),
		section('element:image', '元素 image 的专属键', [
			key('src', 'text', '', '图片标识：mmtr:vehicle/face/x.png'),
			key('fit', 'enum', 'stretch', '装进 w×h 的方式', ['stretch', 'contain', 'cover']),
			key('alpha', 'number', 1, '透明度 0..1'),
			key('tint', 'color', '#FFFFFF', '色调（乘到像素上）'),
		]),
		section('element:foreach', '元素 foreach 的专属键', [
			key('var', 'text', '', '数据路径（列表）'),
			key('of', 'logic', null, '数据表达式（与 var 二选一）'),
			key('as', 'text', 'item', '每一项绑到哪个名字'),
			key('index', 'text', 'index', '下标绑到哪个名字'),
			key('limit', 'int', 32, '最多画几项 0..256'),
			key('elements', 'elements', [], '每一项要画的元素'),
		]),
		section('anim:blink', '动画 blink 的键', [
			key('kind', 'enum', 'blink', '动画名', ['blink']),
			key('onMs', 'number', 600, '亮多久（毫秒）'),
			key('offMs', 'number', 600, '灭多久（毫秒）'),
		]),
		section('anim:marquee', '动画 marquee 的键', [
			key('kind', 'enum', 'marquee', '动画名', ['marquee']),
			key('spanMs', 'number', 4000, '走一趟多久（毫秒）'),
			key('direction', 'enum', 'left', '走向', ['left', 'right']),
			key('gap', 'number', 0.3, '接缝（牌高比例）'),
		]),
		section('anim:fade', '动画 fade 的键', [
			key('kind', 'enum', 'fade', '动画名', ['fade']),
			key('spanMs', 'number', 1500, '一个来回多久（毫秒）'),
			key('min', 'number', 0.25, '最暗到多少（0..1）'),
		]),
		section('anim:spin', '动画 spin 的键', [
			key('kind', 'enum', 'spin', '动画名', ['spin']),
			key('spanMs', 'number', 2000, '转一圈多久（毫秒）'),
		]),
	],
};

/** 作用域名 → 节（保留插入顺序，与 schema.json 的节序一致）。 */
const SECTIONS = new Map(EMBEDDED_SCHEMA.sections.map(entry => [entry.name, entry]));

/** 认得的元素类型（与 MmtrFaceSchema.ELEMENT_TYPES 逐项同序）。 */
export function elementTypes() {
	return EMBEDDED_SCHEMA.elementTypes.slice();
}

/** 认得的动画名（与 MmtrFaceSchema.animKinds() 逐项同序）。 */
export function animKinds() {
	return EMBEDDED_SCHEMA.animKinds.slice();
}

/** 一个作用域里的键表副本；没这个作用域返回空数组。 */
export function keysOf(scope) {
	const found = scopeKeys(scope);
	return found.map(entry => Object.assign({}, entry));
}

/**
 * 作用域 → 键表。`var` 这类**重名**键按 Java 的 elementKeys 口径处理：
 * 公共键在前、类型专属键在后，专属的会覆盖公共的（同名时专属说了算）。
 */
function scopeKeys(scope) {
	const name = String(scope === null || scope === undefined ? '' : scope).trim().toLowerCase();
	const direct = SECTIONS.get(name);
	if (direct) {
		return direct.keys;
	}
	// 元素类型：公共 + 专属（未知类型只有公共键 —— 与 Java elementKeys 的 null 分支一致）
	const common = SECTIONS.get('common').keys;
	const extra = SECTIONS.get('element:' + name);
	if (extra) {
		return common.concat(extra.keys);
	}
	return common;
}

/** 某个键的缺省（原样的 JSON 值：数/字/真假/null/对象）；没登记 ⇒ MISSING。 */
function defaultValueOf(scope, name) {
	for (const entry of scopeKeys(scope)) {
		if (entry.name === name) {
			return entry.default;
		}
	}
	return MISSING;
}

/**
 * 某个键的**数值**缺省（元素画法读的就是它）——没登记 / 不是数字 ⇒ fallback。
 *
 * 注：`null` 也是一个合法的"登记过的缺省"（如 `needle`），这里按"不是数字 ⇒ fallback"处理，
 * 与 Java 的 defaultNumberOf（Double.parseDouble("null") 抛 ⇒ fallback）同一条。
 */
export function defaultNumber(scope, name, fallback) {
	const value = defaultValueOf(scope, name);
	if (value === MISSING || typeof value === 'boolean' || value === null) {
		return fallback;
	}
	if (typeof value === 'number') {
		return value;
	}
	const text = String(value).trim();
	// 与 Java Double.parseDouble 的口径对齐：认十进制与科学计数，不认十六进制浮点（面文档里不会出现）
	if (!/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(text)) {
		return fallback;
	}
	return Number(text);
}

/** 同上，文本键（登记过就用登记的那个字符串，哪怕它是空串）。 */
export function defaultString(scope, name, fallback) {
	const value = defaultValueOf(scope, name);
	return value === MISSING || value === null ? fallback : String(value);
}

/** 某个键的**真假**缺省（登记过 true/false 才为真/假，其余 fallback）——Java 的 Element.flag 同口径。 */
export function defaultFlag(scope, name, fallback) {
	const value = defaultValueOf(scope, name);
	if (value === MISSING || value === null) {
		return fallback;
	}
	if (typeof value === 'boolean') {
		return value;
	}
	if (typeof value === 'string') {
		return value.trim().toLowerCase() === 'true';
	}
	return fallback;
}

/** 这个作用域认得的键名（打包校验与"拼错的键"提示用）。 */
export function knownKeys(scope) {
	return scopeKeys(scope).map(entry => entry.name);
}

// ---- schema.json 的加载（浏览器 fetch / Node fs；两条路都失败就退回内嵌表） ----------------------

/** 加载过的那份 schema.json（没加载成功 = null）。 */
let loaded = null;
/** 已经开始加载（避免同一次会话里重复请求）。 */
let loading = null;

/** 已经加载成功的 schema.json；没加载成功 = null（页面可以据此显示"用的是内嵌兜底表"）。 */
export function loadedSchema() {
	return loaded;
}

/**
 * 尽力加载 schema.json 并**校验形状**：节名与键名必须都在内嵌表里认得。
 *
 * 为什么校验而不是照单全收：schema.json 是**外部文件**（可能被手改、可能是半截的旧版本）。
 * 少一个键顶多是缺省回到内嵌值，但如果它整个形状都不对（比如被覆盖成了别的 JSON），
 * 直接拿来当缺省来源会让画出来的东西莫名其妙 —— 宁可退回内嵌表。
 *
 * @returns {Promise<object|null>} 加载成功返回那份 JSON，否则 null（调用方不需要处理异常）
 */
export async function loadSchema() {
	if (loading !== null) {
		return loading;
	}
	loading = (async () => {
		try {
			const text = await readSchemaText();
			if (text === null) {
				return null;
			}
			const parsed = JSON.parse(text);
			if (!isPlainObject(parsed) || !Array.isArray(parsed.sections)) {
				return null;
			}
			for (const entry of parsed.sections) {
				if (!isPlainObject(entry) || !SECTIONS.has(entry.name) || !Array.isArray(entry.keys)) {
					return null;
				}
				const known = new Set(SECTIONS.get(entry.name).keys.map(item => item.name));
				for (const item of entry.keys) {
					if (!isPlainObject(item) || !known.has(item.name)) {
						return null;
					}
				}
			}
			loaded = parsed;
			return parsed;
		} catch (e) {
			// 加载失败不是错误：内嵌表就是为这一刻准备的（file:// 打开页面时 fetch 必然失败）
			return null;
		}
	})();
	return loading;
}

/** 读 schema.json 的文本：浏览器 fetch，Node 读同目录的文件；都没有 = null。 */
async function readSchemaText() {
	if (typeof process !== 'undefined' && process !== null && process.versions && process.versions.node) {
		// Node：没有 fetch 的场合（或 file:// 权限）用 fs。
		// ★ 这里用**动态 import**（不是顶层 import）：浏览器解析这个模块时不该看到 node:fs。
		try {
			const fs = await import('node:fs');
			const path = await import('node:path');
			const url = await import('node:url');
			const here = path.dirname(url.fileURLToPath(import.meta.url));
			return fs.readFileSync(path.join(here, 'schema.json'), 'utf8');
		} catch (e) {
			return null;
		}
	}
	if (typeof fetch === 'function') {
		// 浏览器：相对路径，与 index.html 同目录
		try {
			const response = await fetch('schema.json', { cache: 'no-store' });
			return response.ok ? await response.text() : null;
		} catch (e) {
			return null;
		}
	}
	return null;
}

function isPlainObject(value) {
	return value !== null && typeof value === 'object' && !Array.isArray(value);
}
