#!/usr/bin/env node
/*
 * verify_face.js —— **动态面（notes/359）的离线自检**：锚点 ↔ 面文档 ↔ 字段表/键表，三份必须对得上。
 *
 * 为什么需要它：面文档里写错一个锚点名、一个字段名、一个算子名，游戏里的表现是
 * **那块面永远不出现**或者**少画一样东西** —— 打包成功、日志干净、什么都不报。这类"静默不画"
 * 只能靠静态检查抓：
 *
 *   P1 faces 的键必须是这块车里真的存在的**锚点名**（差一个字符 = 整块面不画）
 *   P2 kind=face 的锚点必须有文档（模型里做了屏、JSON 里忘了写 = 白做一块屏）
 *   P3 模板与 var 里用到的字段名必须在 fields.json 里（或者同文档的 vars 里 / 所在 forEach 绑的名字里）
 *   P4 元素类型必须是键表认得的那几种（schema.json 的 elementTypes）
 *   P5 表达式里的算子必须是 MmtrFaceLogic 子集里的，且 var 的参数形状对
 *   P6 几何与结构必须在范围内（比例 0..1、字号 0.02..1.5；rect/roundRect 少了 w/h 就是画不出来；
 *      image 要有 src、forEach 要有 elements、每页要有 elements、drum.count 2..8）
 *   P7 模板过滤器必须是 MmtrFaceText 认得的
 *   P8 动画（F3 新）：anim.kind 必须是 schema.json 的 animKinds 之一；anim 不是对象要报；
 *      marquee 只对 text 有效（写在别的元素上不生效）
 *   P9 未知键（F3 新）：按 schema.json 的 sections[] 逐元素类型比 —— 元素上写了该类型不认的键
 *      （colour/siz/filt 这种拼错）、文档级未知键、drum 段未知键都要报出来
 *
 * ★ 遍历范围（F3 修的那个大坑）：以前只看 `face.elements`，于是**写了 `pages` 的文档会被整份跳过** ——
 *   自检报 PASS、游戏里什么都不对。现在 traversal 走 `elements` + `pages[].elements`，并递归进
 *   `forEach.elements`（`as`/`index` 绑的名字会加进"已知 var 名"，所以 {"var":"call.name"} 不会被 P3 误报）。
 *   每一条问题都带**页名/页下标**（例如 faces.pid_1.pages[1].elements[0]），作者不用去猜是哪一页。
 *
 * ★ 扩展作者的三个放宽开关（F4，都是**显式**的，不给就与从前逐字相同）：
 *   --allow-type <名字>      可重复，也可用逗号分隔。把这些类型当成"认得"（P4 不再报），并算作"元素类型里的一种"。
 *                            报告里会单列一行 `扩展类型：vendor:bar（--allow-type 给的）`。
 *   --extra-fields <json>    一个 JSON **文件**：数组 ["vendor:traction"] 或对象 {"vendor:traction":"牵引状态"}。
 *                            P3 查字段名时把这些名字算作存在。
 *   --allow-filter <名字>    同上，给 P7 用：`{v|vendor:kmh}` 这种扩展过滤器（名字自带冒号）。
 *                            ★ 这一个不在原始派单里，是**补出来的**：P7 与 P4/P3 是同一类假警告
 *                            （离线看不到那个模组），不给它，用扩展过滤器的面就永远拿不到全 PASS。
 *   三个开关都做了什么，报告结尾的「这次放宽了什么」一节会逐条写清 —— **不许悄悄放行**。
 *
 * 用法：
 *   node mmtr/tools/anchor-check/verify_face.js --anchors <mmtr_anchors_x.json> [--fields <fields.json>] [--schema <schema.json>]
 *     [--allow-type vendor:bar[,…]] [--extra-fields <extra-fields.json>] [--allow-filter vendor:kmh[,…]]
 *   --anchors 收两种东西：打包出来的 mmtr_anchors_*.json，或者车辆配置（consist）里那份锚点 JSON。
 *
 * 退出码：0 = 全过；1 = 有 FAIL；2 = 输入缺失/读不了。
 * ★ schema.json 缺失**不算通过**：P8/P9 报「没跑」（退出码不变，但末尾会写清"键表检查没跑"）。
 *
 * ⚠ 本文件里的两张清单（算子 / 过滤器）**必须**与 Java 侧一致 ——
 *   `MmtrFaceToolingTests` 会读这个文件并逐项核对，漂了就红。
 *   元素类型清单**不再抄一份**：它是 schema.json 的作者侧事实（Java 侧由 MmtrFaceSchema 导出）。
 */
'use strict';
const fs = require('fs');
const path = require('path');

/** ★ 与 MmtrFaceLogic.operators() 一致（不含 fn:* 那种自定义前缀规则）。
 *  这张清单受 MmtrFaceToolingTests 逐字核对 —— 不要在这里加"文档里写了但 Java 没实现"的算子。 */
const OPERATORS = ['var', 'if', 'and', 'or', '!', '!!', '==', '!=', '===', '!==', '<', '<=', '>', '>=',
	'+', '-', '*', '/', '%', 'min', 'max', 'cat', 'substr', 'in', 'missing', 'missing_some', '?:',
	'some', 'all', 'none', 'filter', 'map'];
/** ★ 与 MmtrFaceText.filters() 一致。 */
const FILTERS = ['upper', 'lower', 'trim', 'int', 'num', 'pad', 'len', 'default'];

/**
 * 过滤器名的**最长匹配**（与引擎同一条口径）：`vendor:kmh:mph` 的名字是 `vendor:kmh`、参数是 `mph`。
 *
 * @param text     `|` 之间的那一段（已去空白，例如 `pad:7` / `vendor:kmh:mph`）
 * @param known    认得的过滤器名（小写比对）
 * @returns {string} 过滤器名（小写）；找不到就把整段当名字（调用方按"不认识"报 P7）
 */
function filterNameOf(text, known) {
	const source = String(text === undefined || text === null ? '' : text).trim().toLowerCase();
	const indexes = [];
	for (let i = 0; i < source.length; i++) {
		if (source[i] === ':') indexes.push(i);
	}
	for (let i = indexes.length - 1; i >= 0; i--) {
		const candidate = source.substring(0, indexes[i]);
		if (known.includes(candidate)) return candidate;
	}
	return source;
}

/**
 * P7 认得的过滤器 = 内置 8 个 + `--allow-filter` 显式声明的扩展名。
 * 为什么要这个开关：扩展过滤器随模组来（名字自带冒号，如 `vendor:kmh`），离线看不到装没装
 * —— 与元素类型 / 字段完全同一类假警告（见文件头）。
 *
 * ★ 为什么在这里赋值而不是在 FILTERS 旁边：`allowFilters` 要等命令行解析完才有值。
 *   声明与赋值分开写，就是为了不在**暂时性死区**里读它（第一版踩过：ReferenceError）。
 */
let KNOWN_FILTERS = FILTERS.slice();

/** `foreach` 的嵌套深度上限（跟引擎的 MmtrFaceDocument.MAX_FOREACH_DEPTH 一致：超了那一层根本不画）。 */
const MAX_FOREACH_DEPTH = 4;

// ---- 参数 ------------------------------------------------------------------------------------------
const argv = process.argv.slice(2);
let anchorsPath = null;
let fieldsPath = path.join(__dirname, '..', 'face-studio', 'fields.json');
// 与 fields.json 走同一条路（同目录、同一个 --x 覆盖口径），因为两份都是 Java 导出的机读表
let schemaPath = path.join(__dirname, '..', 'face-studio', 'schema.json');
/** --allow-type 累积到的原始值（可重复给，也可以一次给逗号分隔的一串）。 */
const allowTypeRaw = [];
/** --allow-filter 累积到的原始值（同上）。 */
const allowFilterRaw = [];
let extraFieldsPath = null;
for (let i = 0; i < argv.length; i++) {
	if (argv[i] === '--anchors') anchorsPath = argv[++i];
	else if (argv[i] === '--fields') fieldsPath = argv[++i];
	else if (argv[i] === '--schema') schemaPath = argv[++i];
	else if (argv[i] === '--allow-type') allowTypeRaw.push(argv[++i]);
	else if (argv[i] === '--allow-filter') allowFilterRaw.push(argv[++i]);
	else if (argv[i] === '--extra-fields') extraFieldsPath = argv[++i];
	else if (!argv[i].startsWith('--')) anchorsPath = argv[i];
}
if (!anchorsPath) {
	console.error('用法: node verify_face.js --anchors <mmtr_anchors_x.json> [--fields <fields.json>] [--schema <schema.json>]'
		+ ' [--allow-type vendor:bar[,…]] [--extra-fields <extra-fields.json>] [--allow-filter vendor:kmh[,…]]');
	process.exit(2);
}
if (!fs.existsSync(anchorsPath)) { console.error('[verify_face] 找不到锚点文件：' + anchorsPath); process.exit(2); }
if (!fs.existsSync(fieldsPath)) { console.error('[verify_face] 找不到字段表：' + fieldsPath); process.exit(2); }

// ---- F4 的两个放宽开关：只影响"认得什么"，不影响任何别的判据 ------------------------------------------
/**
 * --allow-type 给的扩展元素类型（去重、保序）。
 * 为什么要这个开关：装了扩展的客户端才认得那些 `type`，而自检是**离线**的、看不到那个模组，
 * 于是 P4 会把文档里每一个 `vendor:bar` 报成"不认识的元素类型" —— 整天给扩展作者刷假警告。
 * 给了它 ⇒ 这些类型算"认得"，于是 P9（未知键）也**不再**拿内置键表去比它们（那是扩展自己的键）。
 */
const allowTypes = [];
for (const chunk of allowTypeRaw) {
	for (const piece of String(chunk === undefined ? '' : chunk).split(',')) {
		const name = piece.trim();
		if (name !== '' && !allowTypes.includes(name)) allowTypes.push(name);
	}
}
/**
 * --allow-filter 给的扩展**过滤器**名（去重、保序、小写）。
 * 与 --allow-type 同一个理由：装了那个模组客户端才认得 `{v|vendor:kmh}`，离线自检看不到它，
 * 于是 P7 会把文档里每一个扩展过滤器报成"不认识的过滤器"。
 */
const allowFilters = [];
for (const chunk of allowFilterRaw) {
	for (const piece of String(chunk === undefined ? '' : chunk).split(',')) {
		const name = piece.trim().toLowerCase();
		if (name !== '' && !allowFilters.includes(name)) allowFilters.push(name);
	}
}
// 到这里命令行已经解析完，KNOWN_FILTERS 才并进扩展过滤器名单（见它上面那段注释）
KNOWN_FILTERS = FILTERS.concat(allowFilters);
/**
 * --extra-fields 给的扩展字段名（去重、保序）。值是人话说明，只用来打一行提示。
 * 同一个开关也能收一个对象（{"vendor:traction":"牵引状态"}），键才是字段名。
 */
const extraFields = new Map();
if (extraFieldsPath !== null) {
	if (!fs.existsSync(extraFieldsPath)) { console.error('[verify_face] 找不到扩展字段表：' + extraFieldsPath); process.exit(2); }
	let parsedExtra = null;
	try {
		parsedExtra = JSON.parse(fs.readFileSync(extraFieldsPath, 'utf8'));
	} catch (e) {
		console.error('[verify_face] 读不了扩展字段表 ' + extraFieldsPath + '：' + e.message);
		process.exit(2);
	}
	if (Array.isArray(parsedExtra)) {
		for (const item of parsedExtra) {
			if (typeof item !== 'string' || item.trim() === '') {
				console.error('[verify_face] --extra-fields 的数组里只能放字段名字符串，收到 ' + JSON.stringify(item));
				process.exit(2);
			}
			if (!extraFields.has(item.trim())) extraFields.set(item.trim(), '');
		}
	} else if (parsedExtra !== null && typeof parsedExtra === 'object') {
		for (const [name, doc] of Object.entries(parsedExtra)) {
			if (!extraFields.has(name)) extraFields.set(name, typeof doc === 'string' ? doc : '');
		}
	} else {
		console.error('[verify_face] --extra-fields 要一个 JSON 数组（["vendor:traction"]）或对象（{"vendor:traction":"牵引状态"}）');
		process.exit(2);
	}
	// 名字里带点的会被引擎拒掉（MmtrFaceField.Registry.register：扩展字段必须是一段）—— 早点说，别让作者白高兴
	for (const name of extraFields.keys()) {
		if (name.includes('.')) {
			console.error('[verify_face] 扩展字段名不能带点（引擎会拒绝注册，那它永远不会进快照）：' + name);
			process.exit(2);
		}
	}
}

const anchorFile = JSON.parse(fs.readFileSync(anchorsPath, 'utf8'));
const fieldList = (JSON.parse(fs.readFileSync(fieldsPath, 'utf8')).fields || []).map(f => f.name);
const anchors = anchorFile.anchors || [];
const faces = anchorFile.faces || {};

// ---- 键表（schema.json）：P4 的清单 + P8 / P9 的判据都从它来 -----------------------------------------
/** 读 schema.json；读不到 / 读坏了 ⇒ null，P8/P9 记「没跑」（**不算通过**）。 */
let schema = null;
let schemaSkip = '';
if (!fs.existsSync(schemaPath)) {
	schemaSkip = '找不到 ' + schemaPath;
} else {
	try {
		const parsed = JSON.parse(fs.readFileSync(schemaPath, 'utf8'));
		if (!Array.isArray(parsed.sections)) throw new Error('没有 sections[]');
		schema = parsed;
	} catch (e) {
		schemaSkip = '读不了 ' + schemaPath + '（' + e.message + '）';
	}
}

/** sections[] → scope → 键名数组；同一 scope 出现两次就合并（不覆盖）。 */
const SECTION_KEYS = {};
if (schema) {
	for (const section of schema.sections) {
		const list = SECTION_KEYS[section.name] || (SECTION_KEYS[section.name] = []);
		for (const key of (section.keys || [])) {
			if (!list.includes(key.name)) list.push(key.name);
		}
	}
}
const keysOf = scope => new Set(SECTION_KEYS[scope] || []);
const COMMON_KEYS = keysOf('common');
const DOCUMENT_KEYS = keysOf('document');
const PAGE_KEYS = keysOf('page');
const DRUM_KEYS = keysOf('drum');
// 兜底清单与 MmtrFaceSchema.ELEMENT_TYPES 一致，只在没有 schema.json 时用（否则 P4 也得跟着瞎）
const ELEMENT_TYPES = schema && Array.isArray(schema.elementTypes) && schema.elementTypes.length
	? schema.elementTypes.slice()
	: ['text', 'rect', 'roundrect', 'line', 'circle', 'arc', 'gauge', 'image', 'foreach'];
// ★ --allow-type 给的扩展类型算"认得"，所以它们也是"元素类型里的一种"（报告里的类型数会跟着变）
const ALLOW_TYPE_SET = new Set(allowTypes);
for (const type of allowTypes) {
	if (!ELEMENT_TYPES.includes(type)) ELEMENT_TYPES.push(type);
}
const ANIM_KINDS = schema && Array.isArray(schema.animKinds) ? schema.animKinds.slice() : [];

/** 一个元素类型认得的全部键 = common + element:<type>。 */
function elementKeys(type) {
	const keys = new Set(COMMON_KEYS);
	for (const key of keysOf('element:' + type)) keys.add(key);
	return keys;
}

/**
 * 这个类型认得的键；`null` = **不知道**（别报未知键）。
 *
 * <p>两种"不知道"：① schema.json 里根本没有这个类型的段（键表缺一段）；② 这是 --allow-type 给的
 * 扩展类型 —— 它的键由那个扩展在 `registrar.element(type, …, declaredKeys)` 里自己报，离线看不到，
 * 那些键**不进 schema.json**（那是随包发行的最小集）。两种都不该变成"整份文档全是未知键"。</p>
 */
function elementKeysOrNull(type) {
	if (ALLOW_TYPE_SET.has(type) && keysOf('element:' + type).size === 0) return null;
	return elementKeys(type);
}

/** 认不认这个键：schema 里没登记 ⇒ 一律不报（别让"键表缺一段"变成"整份文档全是未知键"）。 */
function unknownKeysOf(object, known) {
	const list = [];
	for (const key of Object.keys(object)) {
		// 下划线开头的是文档里的注释键（`_pidNote` 这类），作者与打包器都在用 —— 不是拼错
		if (key.startsWith('_')) continue;
		if (known.size && !known.has(key)) list.push(key);
	}
	return list;
}

/** 每一类问题一个桶（P1..P9），最后逐类报 PASS/FAIL/没跑 —— 不把"能查的都算过"。 */
const problems = { P1: [], P2: [], P3: [], P4: [], P5: [], P6: [], P7: [], P8: [], P9: [] };
const notes = [];
const usedFields = new Set();
let seenOperators = 0;
let elementCount = 0;

console.log('[verify_face] ' + anchorsPath);
console.log('  anchors=' + anchors.length + '  faces=[' + Object.keys(faces).join(', ') + ']  fields=' + fieldList.length
	+ '  schema=' + (schema ? 'v' + schema.version + '（' + Object.keys(SECTION_KEYS).length + ' 段）' : '（没有）'));

// ★ 喂错文件的空 PASS 最容易骗人：车辆配置（consist）把 faces 嵌在 cars[] 里，顶层没有 faces 段，
//   直接拿它当 --anchors 会得到"零个面全 PASS"。这里说清楚该喂哪一份（不改退出码：这只是用法提醒）。
if (!Object.keys(faces).length && !anchors.length && Array.isArray(anchorFile.cars)) {
	console.log('  ⚠ 这份看起来是**车辆配置**（顶层是 cars[]，faces 嵌在 cars[i].faces 里）—— 自检读的是');
	console.log('    打包出来的锚点 JSON（assets/mtr/mmtr_anchors_*.json，那里 faces 在顶层）。');
	console.log('    拿车辆配置当 --anchors 只会得到一份"零个面的空 PASS"，什么错都抓不到。');
}

// ---- P1: faces 的键必须是锚点名 ------------------------------------------------------------------
const anchorNames = new Set(anchors.map(a => a.name));
for (const key of Object.keys(faces)) {
	if (!anchorNames.has(key)) problems.P1.push('faces.' + key + '：anchors 里没有叫这个名字的锚点 ⇒ 这块面永远不会被画（锚点名见打包日志的 anchors: 那行）');
}

// ---- P2: face 类锚点必须有文档 ------------------------------------------------------------------
for (const anchor of anchors) {
	if (anchor.kind === 'face' && !faces[anchor.name]) {
		problems.P2.push(anchor.name + '：是 mmtr_face_* 锚点但没有 faces 文档（那块屏是空白的；只想要一块静态牌就别叫 face）');
	}
}

// ---- 路径与表达式 --------------------------------------------------------------------------------
function pathOk(p, varNames) {
	if (p === '' || varNames.has(p)) return true;
	if (fieldList.includes(p)) return true;
	// ★ --extra-fields 给的扩展字段名（客户端算出来的那些）：P3 也算它"存在"。
	//   只认两种写法：整个路径就是它，或者它是个分层前缀（vendor:x.a）—— 与"字段表里查前缀"同一口径。
	if (extraFields.has(p)) return true;
	for (const name of extraFields.keys()) {
		if (p.startsWith(name + '.')) return true;
	}
	// forEach 绑的名字本身是个"数据根"：{"var":"call.name"} 的第一段就是它
	// （引擎按路径走：先取到 call 那一项，再从里面取 name）—— 所以看第一段就够了。
	const first = p.split('.')[0];
	if (varNames.has(first)) return true;
	return fieldList.some(f => f.startsWith(p + '.'));
}

/** 字段名在不在"字段表 / 同一个 forEach 链上绑的名字"里（与 pathOk 同一口径，但带一条好读的说明）。 */
function checkPath(p, label, varNames, bucket) {
	usedFields.add(p);
	if (!pathOk(p, varNames)) {
		bucket.P3.push(label + '：字段「' + p + '」不在字段表里（也不在 vars / forEach 的 as、index 里）');
	}
}

function walkExpression(node, label, varNames, bucket) {
	if (node === null || typeof node !== 'object') return;
	if (Array.isArray(node)) { node.forEach((item, i) => walkExpression(item, label + '[' + i + ']', varNames, bucket)); return; }
	for (const [key, value] of Object.entries(node)) {
		if (key.startsWith('fn:')) { notes.push('  自定义算子 ' + key + '（' + label + '）—— 静态检查管不了，得靠注册它的那个模组'); continue; }
		if (!OPERATORS.includes(key)) { bucket.P5.push(label + '：不认识的算子「' + key + '」'); continue; }
		seenOperators++;
		if (key === 'var') {
			const first = Array.isArray(value) ? value[0] : value;
			if (typeof first !== 'string') bucket.P5.push(label + '：var 的第一个参数必须是字段名字符串，收到 ' + JSON.stringify(first));
			else checkPath(first, label, varNames, bucket);
			continue;   // var 的第二个参数是"缺省值"，不是表达式
		}
		walkExpression(value, label + '.' + key, varNames, bucket);
	}
}

function walkTemplate(text, label, varNames, bucket) {
	let index = 0;
	while (index < text.length) {
		const open = text.indexOf('{', index);
		if (open < 0) break;
		if (text.startsWith('{{', open)) { index = open + 2; continue; }
		const close = text.indexOf('}', open + 1);
		if (close < 0) { bucket.P3.push(label + '：模板里有没闭合的 {（' + text.slice(open) + '）'); break; }
		const parts = text.slice(open + 1, close).split('|');
		const p = parts[0].trim();
		checkPath(p, label, varNames, bucket);
		for (const filter of parts.slice(1)) {
			// ★ F4 修的一个**解析错**：这里原来按第一个冒号切名字（`filter.trim().split(':')[0]`）。
			//   可扩展过滤器名自带冒号（`{v|vendor:kmh:mph}`），按第一个冒号切会把名字切成 `vendor`
			//   —— 于是"装没装那个模组"根本判不出来，还会给扩展作者刷一条**假**的 P7。
			//   最长匹配才与引擎一致（MmtrFaceExtensionTests 钉着："参数是 kmh 之后那一段，
			//   不是第一个冒号之后那一段"）。清单里只有内置 8 个 + 用户显式声明的扩展名 ⇒ 不新增误报。
			const name = filterNameOf(filter, KNOWN_FILTERS);
			if (!KNOWN_FILTERS.includes(name)) bucket.P7.push(label + '：不认识的过滤器「' + name + '」');
		}
		index = close + 1;
	}
}

/** 表达式要检查的位置：var 路径得走字段表（forEach 的 of、gauge 的 needle、pageExpr 都算）。 */
function walkLogic(node, label, varNames) {
	if (node !== undefined && node !== null) walkExpression(node, label, varNames, problems);
}

/** 逻辑键的形状：{} / [] / 字面量都是合法的 JSONLogic，但 null 与"根本不是这个类型"要说一句。 */
function checkLogicShape(value, label, bucket) {
	if (value === null || value === undefined) return;
	if (typeof value === 'object') return;
	bucket.P6.push(label + '：逻辑键应当写表达式对象（如 {"!!":[{"var":"pid.service"}]}），收到 ' + JSON.stringify(value));
}

// ---- 页 / 元素 ------------------------------------------------------------------------------------
/** 一页的标签前缀：faces.pid_1.pages[1]（带页名更好认：pages[1]「回程」）。 */
function pageLabel(faceName, index, page) {
	const name = typeof page.name === 'string' && page.name ? '「' + page.name + '」' : '';
	return 'faces.' + faceName + '.pages[' + index + ']' + name;
}

/**
 * 元素表的递归遍历。
 *
 * @param elements  这一层的元素数组（顶层 elements / pages[i].elements / forEach.elements 共用）
 * @param prefix    标签前缀（不含 .elements[i]）
 * @param varNames  这一层能直接用的名字（文档 vars + 各层 forEach 的 as/index）
 * @param depth     forEach 嵌套层数（超 MAX_FOREACH_DEPTH 的元素引擎根本不会画，只提示一句）
 */
function walkElements(elements, prefix, varNames, depth) {
	if (!Array.isArray(elements)) return;
	elements.forEach((element, i) => {
		if (element === null || typeof element !== 'object' || Array.isArray(element)) {
			problems.P6.push(prefix + '.elements[' + i + ']：不是一个对象（元素必须是 {"type": …} 这样的对象）');
			return;
		}
		elementCount++;
		const type = String(element.type || '').toLowerCase();
		const label = prefix + '.elements[' + i + '](' + (element.type || '?') + ')';
		if (!ELEMENT_TYPES.includes(type)) {
			problems.P4.push(label + '：不认识的元素类型「' + element.type + '」（认得 ' + ELEMENT_TYPES.join('/') + '）');
			return;   // 类型都不认得，下面按类型比的键就别报了（免得一屏全是"未知键"）
		}
		// P9：这个类型不认的键（colour/siz/filt 这种拼错；引擎会照画但记一次账）
		// 扩展类型（--allow-type）不知道它认哪些键 ⇒ null ⇒ 这一条对它不跑（见 elementKeysOrNull）
		const knownForType = elementKeysOrNull(type);
		if (knownForType !== null) {
			for (const key of unknownKeysOf(element, knownForType)) {
				problems.P9.push(label + '：这个类型不认的键「' + key + '」（认得的：' + [...knownForType].join('/') + '）');
			}
		}
		// ★ "这一层能直接用哪些名字"：foreach 的 as/index 绑的名字对**它自己的子元素**可见
		//   （引擎在 MmtrFaceDocument.bind 里就是把这两项并进子元素的数据副本）。
		//   所以模板、when、子元素都要用 inner 这一套，不然 {"var":"call.name"} 会被 P3 误报。
		let inner = varNames;
		if (type === 'foreach') {
			inner = new Set(varNames);
			const asName = element.as === undefined ? 'item' : element.as;
			const indexName = element.index === undefined ? 'index' : element.index;
			for (const binding of [asName, indexName]) {
				if (typeof binding === 'string' && /^[A-Za-z_][A-Za-z0-9_]*$/.test(binding)) inner.add(binding);
			}
		}
		// P8：动画
		walkAnim(element.anim, label, type);
		// P6：几何与结构
		if (type === 'text') {
			if (typeof element.text !== 'string') problems.P4.push(label + '：文本元素必须有 text');
			else walkTemplate(element.text, label + '.text', inner, problems);
		}
		if (['rect', 'roundrect'].includes(type)) {
			if (!(element.w > 0)) problems.P6.push(label + '：w 缺失或为 0（画不出来）');
			if (!(element.h > 0)) problems.P6.push(label + '：h 缺失或为 0（画不出来）');
		}
		if (type === 'line' && (element.x2 === undefined || element.y2 === undefined)) problems.P6.push(label + '：少了 x2/y2（画不出来）');
		if (type === 'circle' && !(element.radius > 0)) problems.P6.push(label + '：少了 radius');
		if (type === 'arc' && !(element.radius > 0)) problems.P6.push(label + '：少了 radius');
		if (type === 'gauge' && !(element.radius > 0)) problems.P6.push(label + '：少了 radius');
		if (type === 'image') {
			// src 必须写，而且必须是 命名空间:路径（少了冒号就会被当 minecraft 命名空间去找，永远找不到）
			if (typeof element.src !== 'string' || !element.src.trim()) {
				problems.P6.push(label + '：image 必须写 src（如 "mmtr:vehicle/face/logo.png"）；没有 src 只会画一个洋红占位框');
			} else if (!/^[a-z0-9_.-]+:[^\s]+$/i.test(element.src.trim())) {
				problems.P6.push(label + '：src=' + JSON.stringify(element.src) + ' 不像"命名空间:路径"（例如 "mmtr:vehicle/face/logo.png"）');
			}
			if (element.w !== undefined && !(element.w > 0)) problems.P6.push(label + '：w=0 ⇒ 图片没有框可装（画不出来）');
			if (element.h !== undefined && !(element.h > 0)) problems.P6.push(label + '：h=0 ⇒ 图片没有框可装（画不出来）');
		}
		if (type === 'foreach') {
			const hasVar = typeof element.var === 'string' && element.var.trim() !== '';
			const hasOf = element.of !== undefined && element.of !== null;
			if (!hasVar && !hasOf) problems.P6.push(label + '：var 与 of 至少要写一样（两样都没写 ⇒ 一项都画不出来）');
			if (element.elements === undefined) problems.P6.push(label + '：foreach 必须写 elements（每一项画什么）');
			else if (!Array.isArray(element.elements)) problems.P6.push(label + '：elements 必须是数组');
			else if (!element.elements.length) problems.P6.push(label + '：elements 是空的 ⇒ 每一项都不画（等于白写）');
			if (hasVar) checkPath(element.var.trim(), label + '.var', varNames, problems);
			if (hasOf) walkLogic(element.of, label + '.of', varNames);
			if (element.limit !== undefined && (typeof element.limit !== 'number' || element.limit < 0 || element.limit > 256)) {
				problems.P6.push(label + '：limit=' + JSON.stringify(element.limit) + ' 超出 0..256（引擎按 0..256 钳）');
			}
			if (depth >= MAX_FOREACH_DEPTH) {
				notes.push('  ' + label + '：forEach 嵌套第 ' + (depth + 1) + ' 层 —— 引擎只展开 ' + MAX_FOREACH_DEPTH + ' 层，这一层不会被画');
			}
			for (const [binding, key] of [[element.as, 'as'], [element.index, 'index']]) {
				if (binding !== undefined && (typeof binding !== 'string' || !/^[A-Za-z_][A-Za-z0-9_]*$/.test(binding))) {
					problems.P6.push(label + '：' + key + ' 必须是名字（收到 ' + JSON.stringify(binding) + '）—— 引擎会把数据绑到一个取不出来的名字上');
				}
			}
			// 递归进去：子元素用 inner（含 as/index 绑的名字）
			walkElements(element.elements, label, inner, depth + 1);
		}
		for (const key of ['x', 'y', 'w', 'h']) {
			const value = element[key];
			if (value !== undefined && (typeof value !== 'number' || value < 0 || value > 1)) problems.P6.push(label + '：' + key + '=' + JSON.stringify(value) + ' 不是 0..1 的比例');
		}
		if (element.size !== undefined && (typeof element.size !== 'number' || element.size < 0.02 || element.size > 1.5)) problems.P6.push(label + '：size=' + JSON.stringify(element.size) + ' 超出 0.02..1.5');
		if (element.opacity !== undefined && (typeof element.opacity !== 'number' || element.opacity < 0 || element.opacity > 1)) problems.P6.push(label + '：opacity=' + JSON.stringify(element.opacity) + ' 不是 0..1');
		if (element.rotate !== undefined && typeof element.rotate !== 'number') problems.P6.push(label + '：rotate=' + JSON.stringify(element.rotate) + ' 不是数字（度）');
		if (element.when !== undefined) checkLogicShape(element.when, label + '.when', problems);
		walkLogic(element.when, label + '.when', inner);
		walkLogic(element.needle, label + '.needle', inner);
		if (type === 'gauge' && element.needle === undefined) notes.push('  ' + prefix + '.elements[' + i + '] 的 gauge 没写 needle ⇒ 指针按默认字段（speedKmh）走');
	});
}

/** P8：动画（kind 白名单 / anim 得是对象 / marquee 只对 text 有效）。 */
function walkAnim(anim, label, type) {
	if (anim === undefined || anim === null) return;
	if (typeof anim !== 'object' || Array.isArray(anim)) {
		problems.P8.push(label + '.anim：必须是对象（如 {"kind":"blink","onMs":600,"offMs":600}），收到 ' + JSON.stringify(anim) + ' ⇒ 引擎当没有动画');
		return;
	}
	const kind = typeof anim.kind === 'string' ? anim.kind.trim().toLowerCase() : '';
	if (!ANIM_KINDS.length) {
		// 没有 schema.json 时连白名单都没有：只报"kind 没写"，白名单检查留给 P8 的「没跑」
		if (!kind) problems.P8.push(label + '.anim：没写 kind ⇒ 引擎当没有动画（认得的是 blink/marquee/fade/spin）');
	} else if (!ANIM_KINDS.includes(kind)) {
		problems.P8.push(label + '.anim.kind=' + JSON.stringify(anim.kind) + ' 不在动画表里（认得 ' + ANIM_KINDS.join('/') + '）⇒ 引擎当没有动画，这块牌是静的');
	}
	for (const key of unknownKeysOf(anim, keysOf('anim:' + kind))) {
		problems.P8.push(label + '.anim：这个动画不认的键「' + key + '」');
	}
	if (kind === 'marquee' && type !== 'text') {
		problems.P8.push(label + '.anim：marquee 只对 text 元素有效 —— 写在 ' + type + ' 上等于没写（要滚的是文字，不是形状）');
	}
}

/** 一页：P6 结构 + P9 未知键 + 表达式 + 递归元素。 */
function walkPage(faceName, index, page, documentVarNames) {
	const label = pageLabel(faceName, index, page);
	if (page === null || typeof page !== 'object' || Array.isArray(page)) {
		problems.P6.push(label + '：不是一个对象（页必须是 {"name":…,"elements":[…]}）');
		return;
	}
	for (const key of unknownKeysOf(page, PAGE_KEYS)) {
		problems.P9.push(label + '：页不认的键「' + key + '」（认得的：' + [...PAGE_KEYS].join('/') + '）');
	}
	if (page.elements === undefined) problems.P6.push(label + '：这一页没有 elements ⇒ 这一页什么都不画（pages 里每一页都要有 elements）');
	else if (!Array.isArray(page.elements)) problems.P6.push(label + '：elements 必须是数组');
	// 页里的元素用文档 vars + 页自己的名字（页名可以给 pageExpr 用字名，但页名不是数据字段，不加进 varNames）
	const varNames = new Set(documentVarNames);
	walkLogic(page.require, label + '.require', varNames);
	walkElements(page.elements, label, varNames, 0);
}

// ---- 逐块面 --------------------------------------------------------------------------------------
for (const [faceName, face] of Object.entries(faces)) {
	const faceLabel = 'faces.' + faceName;
	if (face === null || typeof face !== 'object' || Array.isArray(face)) {
		problems.P6.push(faceLabel + '：不是一个对象（面文档必须是 {"elements":[…]} 或 {"pages":[…]}）');
		continue;
	}
	const varNames = new Set(Object.keys(face.vars || {}));
	const hasPages = face.pages !== undefined && face.pages !== null;
	const elements = Array.isArray(face.elements) ? face.elements : [];
	const pages = Array.isArray(face.pages) ? face.pages : [];

	// P9：文档级未知键
	for (const key of unknownKeysOf(face, DOCUMENT_KEYS)) {
		problems.P9.push(faceLabel + '：文档不认的键「' + key + '」（认得的：' + [...DOCUMENT_KEYS].join('/') + '）');
	}
	// P6：多页时同时写了顶层 elements ⇒ 后者被忽略（引擎与绘制器都只提示一次，图上看着"东西少了"）
	if (hasPages && !Array.isArray(face.pages)) {
		problems.P6.push(faceLabel + '.pages：必须是数组（每一项是一页）');
	} else if (pages.length && elements.length) {
		problems.P6.push(faceLabel + '：同时写了 pages 与顶层 elements ⇒ 顶层 elements（' + elements.length + ' 项）被**整段忽略**（pages 说了算）；要留就把它们移进某一页');
	}
	// P4：一块面总得画点东西（v1 看 elements，v2 看 pages 非空）
	if (!hasPages && !elements.length) {
		problems.P4.push(faceLabel + '：既没有 elements 也没有 pages（这块面什么都不画）');
	} else if (hasPages && Array.isArray(face.pages) && !pages.length) {
		problems.P6.push(faceLabel + '.pages：是空数组 ⇒ 一块面都不会画（要一页就干脆用 elements）');
	}

	if (face.require !== undefined) {
		checkLogicShape(face.require, faceLabel + '.require', problems);
		walkLogic(face.require, faceLabel + '.require', varNames);
	}
	if (face.pageExpr !== undefined && face.pageExpr !== null) {
		// ★ F3：pageExpr 也是表达式，里面的 var 同样要走字段表（以前只查 when/require/vars）
		checkLogicShape(face.pageExpr, faceLabel + '.pageExpr', problems);
		walkLogic(face.pageExpr, faceLabel + '.pageExpr', varNames);
	}
	if (face.pageSeconds !== undefined && (typeof face.pageSeconds !== 'number' || face.pageSeconds < 0)) {
		problems.P6.push(faceLabel + '.pageSeconds=' + JSON.stringify(face.pageSeconds) + ' 不是 ≥0 的数（秒）');
	}
	if (face.fps !== undefined && (typeof face.fps !== 'number' || face.fps < 1 || face.fps > 30)) {
		problems.P6.push(faceLabel + '.fps=' + JSON.stringify(face.fps) + ' 超出 1..30 ⇒ 引擎用缺省 8');
	}
	for (const key of ['roll', 'tilt']) {
		if (face[key] !== undefined && typeof face[key] !== 'number') problems.P6.push(faceLabel + '.' + key + '=' + JSON.stringify(face[key]) + ' 不是数字（度）');
	}
	if (face.drum !== undefined && face.drum !== null) {
		const drum = face.drum;
		if (typeof drum !== 'object' || Array.isArray(drum)) {
			problems.P6.push(faceLabel + '.drum：必须是对象（{"count":2,"turnFraction":0.25,"radiusM":0}）');
		} else {
			for (const key of unknownKeysOf(drum, DRUM_KEYS)) {
				problems.P9.push(faceLabel + '.drum：不认的键「' + key + '」（认得的：' + [...DRUM_KEYS].join('/') + '）');
			}
			if (drum.count !== undefined) {
				if (typeof drum.count !== 'number' || !Number.isInteger(drum.count) || drum.count < 2 || drum.count > 8) {
					problems.P6.push(faceLabel + '.drum.count=' + JSON.stringify(drum.count) + ' 超出 2..8（棱柱面数；引擎会钳回范围，牌面就不是你算的样子了）');
				}
			}
			if (drum.turnFraction !== undefined && (typeof drum.turnFraction !== 'number' || drum.turnFraction < 0 || drum.turnFraction > 1)) {
				problems.P6.push(faceLabel + '.drum.turnFraction=' + JSON.stringify(drum.turnFraction) + ' 不是 0..1');
			}
			if (drum.radiusM !== undefined && (typeof drum.radiusM !== 'number' || drum.radiusM < 0)) {
				problems.P6.push(faceLabel + '.drum.radiusM=' + JSON.stringify(drum.radiusM) + ' 不是 ≥0 的数（米）');
			}
			if (!pages.length) notes.push('  ' + faceLabel + '：写了 drum 但没有 pages ⇒ 没有面可贴（翻牌机没内容）');
		}
	}
	for (const [varName, expr] of Object.entries(face.vars || {})) walkExpression(expr, faceLabel + '.vars.' + varName, varNames, problems);

	// v1 形态（elements = 第 0 页）与 v2 形态（pages[]）都要遍历 —— 以前只看 elements，写了 pages 的文档整份被跳过
	walkElements(elements, faceLabel, varNames, 0);
	if (Array.isArray(face.pages)) face.pages.forEach((page, i) => walkPage(faceName, i, page, varNames));
}

// ---- 报九行 --------------------------------------------------------------------------------------
/** @param ran false = 这一段判据没跑（缺 schema.json）；**不算通过**，也不算 FAIL。 */
function report(id, title, detail, ran) {
	const list = problems[id];
	const status = ran === false ? '没跑' : (list.length ? 'FAIL' : 'PASS');
	console.log('  ' + id + '  ' + title.padEnd(20) + status + '   ' + detail);
	if (list.length) list.slice(0, 8).forEach(text => console.log('        ✗ ' + text));
	if (list.length > 8) console.log('        ✗ …还有 ' + (list.length - 8) + ' 处');
}
report('P1', 'faces 键 = 锚点名', Object.keys(faces).length + ' 个键');
report('P2', 'face 锚点有文档', anchors.filter(a => a.kind === 'face').length + ' 个 face 锚点');
report('P3', '字段名存在', [...usedFields].filter(Boolean).length + ' 个字段被用到'
	+ (extraFields.size ? ' + ' + extraFields.size + ' 个扩展字段（--extra-fields 给的）' : ''));
report('P4', '元素类型与必需键', elementCount + ' 个元素 / ' + ELEMENT_TYPES.length + ' 种类型'
	+ (allowTypes.length ? '，其中 ' + allowTypes.length + ' 种是 --allow-type 给的扩展类型' : ''));
report('P5', '算子与 var 形状', seenOperators + ' 处算子 / 清单 ' + OPERATORS.length + ' 个');
report('P6', '几何与结构范围', '比例 0..1、字号 0.02..1.5、image.src、foreach.elements、drum.count 2..8');
report('P7', '模板过滤器', FILTERS.length + ' 个：' + FILTERS.join('/')
	+ (allowFilters.length ? ' + ' + allowFilters.length + ' 个扩展过滤器（--allow-filter 给的）' : ''));
report('P8', '动画（anim）', schema ? ANIM_KINDS.length + ' 种：' + ANIM_KINDS.join('/') : '需要 schema.json', !!schema);
report('P9', '未知键（按键表比）', schema ? Object.keys(SECTION_KEYS).length + ' 段键表' : '需要 schema.json', !!schema);

console.log('  用到的字段：' + ([...usedFields].filter(Boolean).sort().join(', ') || '（没有）'));
// ★ 放宽要看得见：这两行只在给了开关时出现（不给 ⇒ 输出与从前逐字相同）
if (allowTypes.length) {
	const knownBySchema = new Set(schema && Array.isArray(schema.elementTypes) ? schema.elementTypes : []);
	for (const type of allowTypes) {
		console.log('  扩展类型：' + type + (knownBySchema.has(type) ? '（--allow-type 给的；键表里本来就有这个类型）' : '（--allow-type 给的）'));
	}
}
if (extraFields.size) {
	for (const [name, doc] of extraFields) {
		console.log('  扩展字段：' + name + '（--extra-fields 给的' + (doc ? '：' + doc : '') + '）');
	}
}
if (allowFilters.length) {
	for (const name of allowFilters) {
		console.log('  扩展过滤器：' + name + '（--allow-filter 给的）');
	}
}
notes.forEach(text => console.log(text));

/** 给了放宽开关就逐条写清"放宽了什么" —— 不允许悄悄放行。 */
function reportRelaxations() {
	if (!allowTypes.length && !extraFields.size && !allowFilters.length) return;
	console.log('');
	console.log('[verify_face] 这次放宽了什么（不给开关时这些判据照旧）：');
	if (allowTypes.length) {
		console.log('  · P4：把 ' + allowTypes.join('、') + ' 当成"认得"的元素类型（扩展类型；离线看不到装没装那个模组）');
		console.log('  · P4：它们算作"元素类型里的一种"（所以上面那行的类型数含它们；它们**不进** schema.json）');
		console.log('  · P9：不再拿内置键表去比这些类型上的键（它们认哪些键由扩展的 registrar.element(…, declaredKeys) 自己报）');
	}
	if (extraFields.size) {
		console.log('  · P3：把 ' + [...extraFields.keys()].join('、') + ' 当成"存在"的字段名（客户端算出来的扩展字段 MmtrFaceField；它们**不进** fields.json）');
	}
	if (allowFilters.length) {
		console.log('  · P7：把 ' + allowFilters.join('、') + ' 当成"认得"的模板过滤器（扩展过滤器 MmtrFaceFilter；它们**不进** MmtrFaceText.filters() 那 8 个）');
	}
	console.log('  · 没放宽的：元素几何/结构（P6）、算子与 var 形状（P5）、动画（P8）、未知键（P9，只对内置类型）照旧按内置清单判');
}

// ★ 放宽说明写在"判据全跑完、结论出来之前" —— PASS 与 FAIL 两种结局下都看得见这次放宽了什么
reportRelaxations();

if (!schema) {
	// ★ 缺键表 ⇒ P8/P9 是"没跑"，不是"通过"：作者至少知道这两条判据这次没帮他把关
	console.log('\n[verify_face] 没有 schema.json ⇒ 跳过 P8/P9（键表检查没跑）—— ' + schemaSkip);
	console.log('  补上它就跑：schema.json 由 Java 的 MmtrFaceSchema.exportJson() 导出（mmtr/tools/face-studio/schema.json）');
}

const total = Object.values(problems).reduce((sum, list) => sum + list.length, 0);
if (total) {
	console.log('\n[verify_face] FAIL（' + total + ' 项）—— 这些错进游戏以后都不报错，只是"那块面不出现/少画一样"');
	process.exit(1);
}
if (!schema) {
	console.log('\n[verify_face] PASS（仅 P1..P7；P8/P9 没跑）');
	process.exit(0);
}
console.log('\n[verify_face] PASS —— 锚点 / 面文档 / 字段表 / 键表四份对得上');
