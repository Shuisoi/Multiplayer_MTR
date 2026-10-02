#!/usr/bin/env node
/*
 * selftest.mjs —— web 工作室的**离线自检**（Node 直接跑，零依赖、零构建）：
 *
 *   node mmtr/tools/face-studio/selftest.mjs
 *
 * 四件事：
 *   1. 跑共享测试向量 conformance/logic.json（68 条）—— Java 端 MmtrFaceLogicTests 跑的是**同一份**文件；
 *   2. 跑共享测试向量 conformance/document.json —— 面文档 v2 的页/动画/重复/姿态，Java 端
 *      MmtrFaceDocumentVectorTests 跑同一份（"两个实现一致"从逻辑层扩到文档层）；
 *   3. 核对**三张清单**与 schema.json（Java 的 MmtrFaceSchema 导出的那张表）逐项一致：
 *      elementTypes ↔ painter.mjs 的 ELEMENT_TYPES、animKinds ↔ anim.mjs 的 kinds()、
 *      每条键的 default ↔ schema.mjs 的内嵌兜底缺省（逐节逐键）；
 *   4. 顺手核对 logic.mjs/text.mjs 的算子与过滤器清单仍与 tools/anchor-check/verify_face.js 一致
 *      （那两张表 F3 没动；元素类型那张以 schema.json 为准 —— verify_face.js 里那份是 F0 的 7 项，
 *      要等 F3 的打包校验跟上，这里以 Java 导出的键表为权威）。
 *
 * 退出码：0 = 全过；1 = 有用例失败或清单不一致；2 = 读不到向量/键表文件。
 *
 * 与 Java 的比对口径（vector 怎么读）：
 *   · face 包成 {"faces": {"f": <face>}} 再 parseDocument(anchor, 'f', 'vec')；
 *   · expect.page = pageIndex(document, augment(data), timeMs)（Java 的 plan 也是先 augment 再选页）；
 *   · expect.visible = visible(document, data)（只看文档级 require）；
 *   · expect.texts = texts(document, data, timeMs)；
 *   · expect.drawables = plan(...) 的有序摘要 {type,x,y,w,h,size,text,anim}，数字四舍五入到 4 位小数，
 *     text 是**原始模板**（不是解析后的），anim 是 kind 名或 null。
 */

import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

import { OPERATORS, evaluate } from './logic.mjs';
import { FILTERS } from './text.mjs';
import { ELEMENT_TYPES } from './painter.mjs';
import { kinds as animKinds } from './anim.mjs';
import { EMBEDDED_SCHEMA } from './schema.mjs';
import { augment, pageClockFraction, fps as documentFps, pageIndex, parseDocument, plan, texts, visible } from './document.mjs';
import { drumAngle, fitRect, marqueeLeft } from './geometry.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const logicPath = join(here, 'conformance', 'logic.json');
const documentPath = join(here, 'conformance', 'document.json');
const geometryPath = join(here, 'conformance', 'geometry.json');
const schemaPath = join(here, 'schema.json');
const verifyPath = join(here, '..', 'anchor-check', 'verify_face.js');

/** 少于这个条数就认为"读错了文件"（向量只会加不会减；真被砍到 60 条以下得是人改的）。 */
const MIN_CASES = 60;
/** 文档向量至少要有这么多条（F3 的覆盖面：v1/页/重复/动画/缺省/pages×elements）。 */
const MIN_DOCUMENT_CASES = 20;
/** 几何向量至少要有这么多条（fitRect 的各种 fit × 受限方向 + marqueeLeft 的相位两端与钳位）。 */
const MIN_GEOMETRY_CASES = 12;
/** geometry.json 只认这两个函数（drumAngle/prismRadius/smoothStep 不放进来，由 Java 侧自己钉）。 */
const GEOMETRY_FUNCTIONS = { fitRect, marqueeLeft };

let failed = 0;

// ---- 读文件 -------------------------------------------------------------------------------------

function readJson(path, label) {
	if (!existsSync(path)) {
		console.error('[selftest] 找不到' + label + '：' + path);
		console.error('[selftest] （它应当与工作室一起打包；删了它等于把"两个实现一致"这条保证删了）');
		process.exit(2);
	}
	try {
		return JSON.parse(readFileSync(path, 'utf8'));
	} catch (e) {
		console.error('[selftest] ' + label + '不是合法 JSON：' + e.message);
		process.exit(2);
	}
}

const logicVectors = readJson(logicPath, '测试向量 ');
const documentVectors = readJson(documentPath, '文档向量 ');
const geometryVectors = readJson(geometryPath, '几何向量 ');
const logicCases = Array.isArray(logicVectors.cases) ? logicVectors.cases : [];
const documentCases = Array.isArray(documentVectors.cases) ? documentVectors.cases : [];
const geometryCases = Array.isArray(geometryVectors.cases) ? geometryVectors.cases : [];
const data = logicVectors.data === undefined ? null : logicVectors.data;

console.log('[selftest] 逻辑向量： ' + logicPath);
console.log('[selftest] 版本=' + logicVectors.version + '  条数=' + logicCases.length + '  数据字段=' + describeKeys(data));
console.log('[selftest] 文档向量： ' + documentPath);
console.log('[selftest] 版本=' + documentVectors.version + '  条数=' + documentCases.length);
console.log('[selftest] 几何向量： ' + geometryPath);
console.log('[selftest] 版本=' + geometryVectors.version + '  条数=' + geometryCases.length);
console.log('');

if (logicCases.length < MIN_CASES) {
	console.error('[selftest] 只有 ' + logicCases.length + ' 条逻辑向量，少过 ' + MIN_CASES + ' 条 —— 这多半是**读错了文件**（不是实现坏了）。');
	process.exit(1);
}
if (documentCases.length < MIN_DOCUMENT_CASES) {
	console.error('[selftest] 只有 ' + documentCases.length + ' 条文档向量，少过 ' + MIN_DOCUMENT_CASES + ' 条 —— 覆盖面不够（页/动画/重复/缺省各要有）。');
	process.exit(1);
}
if (geometryCases.length < MIN_GEOMETRY_CASES) {
	console.error('[selftest] 只有 ' + geometryCases.length + ' 条几何向量，少过 ' + MIN_GEOMETRY_CASES + ' 条 —— 覆盖面不够（装图方式的各方向 + 走马灯相位两端与钳位）。');
	process.exit(1);
}

// ---- 1) 逻辑向量（68 条，与 Java 的 MmtrFaceLogicTests 同一份） ----------------------------------

let logicPassed = 0;
for (const testCase of logicCases) {
	const name = testCase && testCase.name ? testCase.name : '(无名用例)';
	let actual;
	let thrown = null;
	try {
		actual = evaluate(testCase.expr, data);
	} catch (e) {
		thrown = e;
	}
	if (thrown !== null) {
		failed++;
		console.log('失败  [逻辑] ' + name);
		console.log('      抛异常：' + (thrown && thrown.message ? thrown.message : thrown));
		continue;
	}
	if (sameValue(actual, testCase.expect)) {
		logicPassed++;
		console.log('通过  [逻辑] ' + name);
	} else {
		failed++;
		console.log('失败  [逻辑] ' + name);
		console.log('      得到 ' + show(actual) + '，期望 ' + show(testCase.expect));
	}
}
console.log('');
console.log('[selftest] 逻辑向量：通过 ' + logicPassed + ' / ' + logicCases.length);
console.log('');

// ---- 2) 文档向量（页 / pageExpr / forEach / anim / 缺省 / pages×elements） -------------------------

let documentPassed = 0;
for (const testCase of documentCases) {
	const name = testCase && testCase.name ? testCase.name : '(无名用例)';
	let actual;
	let thrown = null;
	try {
		actual = runDocumentCase(testCase);
	} catch (e) {
		thrown = e;
	}
	if (thrown !== null) {
		failed++;
		console.log('失败  [文档] ' + name);
		console.log('      抛异常：' + (thrown && thrown.message ? thrown.message : thrown));
		continue;
	}
	const comparison = compareDocumentCase(actual, testCase.expect);
	if (comparison === null) {
		documentPassed++;
		console.log('通过  [文档] ' + name);
	} else {
		failed++;
		console.log('失败  [文档] ' + name);
		console.log('      ' + comparison);
	}
}
console.log('');
console.log('[selftest] 文档向量：通过 ' + documentPassed + ' / ' + documentCases.length);
console.log('');

// ---- 2b) 几何向量（装图方式 / 走马灯位置；与 Java 的 MmtrFaceGeometryTests 同一份） ---------------

let geometryPassed = 0;
for (const testCase of geometryCases) {
	const name = testCase && testCase.name ? testCase.name : '(无名用例)';
	const fn = GEOMETRY_FUNCTIONS[testCase.fn];
	if (fn === undefined) {
		failed++;
		console.log('失败  [几何] ' + name);
		console.log('      geometry.json 里出现了不认得的 fn「' + testCase.fn + '」（只认 fitRect / marqueeLeft）');
		continue;
	}
	let actual;
	let thrown = null;
	try {
		actual = fn.apply(null, Array.isArray(testCase.args) ? testCase.args : []);
	} catch (e) {
		thrown = e;
	}
	if (thrown !== null) {
		failed++;
		console.log('失败  [几何] ' + name);
		console.log('      抛异常：' + (thrown && thrown.message ? thrown.message : thrown));
		continue;
	}
	const comparison = compareNumbers(actual, testCase.expect);
	if (comparison === null) {
		geometryPassed++;
		console.log('通过  [几何] ' + name);
	} else {
		failed++;
		console.log('失败  [几何] ' + name);
		console.log('      ' + comparison);
	}
}
console.log('');
console.log('[selftest] 几何向量：通过 ' + geometryPassed + ' / ' + geometryCases.length);
console.log('');

// ---- 3) 三张清单与 schema.json 逐项一致 ---------------------------------------------------------

const schema = loadSchema();
console.log('[selftest] 清单核对（对照 ' + schemaPath + '）：');
console.log('  版本=' + schema.version + '  节数=' + schema.sections.length
	+ '  元素类型=' + (schema.elementTypes || []).length + '  动画=' + (schema.animKinds || []).length);

checkList('elementTypes', 'schema.json', schema.elementTypes, 'painter.mjs ELEMENT_TYPES', ELEMENT_TYPES);
checkList('animKinds', 'schema.json', schema.animKinds, 'anim.mjs kinds()', animKinds());
checkDefaults(schema);
checkLogicLists();
console.log('');

// ---- 结论 ---------------------------------------------------------------------------------------

const ok = failed === 0;
console.log('[selftest] ' + (ok ? '全过' : '有问题')
	+ '：逻辑 ' + logicPassed + '/' + logicCases.length
	+ ' + 文档 ' + documentPassed + '/' + documentCases.length
	+ ' + 几何 ' + geometryPassed + '/' + geometryCases.length
	+ '；清单 ' + (failed === 0 ? '三张一致' : '有不一致')
	+ '；退出码 ' + (ok ? 0 : 1));
process.exit(ok ? 0 : 1);

// ---- 文档用例 -----------------------------------------------------------------------------------

/** 跑一条文档向量：把 face 包成 faces.f 再解析，然后算 page/visible/texts/drawables。 */
function runDocumentCase(testCase) {
	const anchor = JSON.stringify({ faces: { f: testCase.face } });
	const document = parseDocument(anchor, 'f', 'vector');
	const caseData = testCase.data === undefined ? {} : testCase.data;
	const timeMs = testCase.timeMs === undefined ? 0 : testCase.timeMs;
	const actual = {
		page: pageIndex(document, augment(document, caseData), timeMs),
		visible: visible(document, caseData),
		texts: texts(document, caseData, timeMs),
		drawables: plan(document, caseData, timeMs).map(summariseDrawable),
	};
	// 三个**可选**字段（写了才断言）：
	//   fps        —— 动画重画节拍（Java 的 (int) 窄化是饱和，所以 fps: 1e10 ⇒ 8）；
	//   background —— 整面底色（ARGB 有符号 int；"0" 是哨兵 = 不铺底，000000 才是不透明黑）；
	//   drumAngle  —— 按 pageClockFraction 采样的翻牌机角度（真几何在 geometry.mjs，与 Java 同一条），
	//                 每条给 {face, page, clockFraction, angle}。
	if (testCase.expect.fps !== undefined) {
		actual.fps = documentFps(document);
	}
	if (testCase.expect.background !== undefined) {
		actual.background = document.background;
	}
	if (testCase.expect.drumAngle !== undefined) {
		const drum = document.drum;
		if (drum === null) {
			throw new Error('这条向量写了 expect.drumAngle，但 face 里没有 drum 段');
		}
		const clockFraction = pageClockFraction(document, timeMs);
		actual.drumAngle = testCase.expect.drumAngle.map(entry => ({
			face: entry.face,
			page: actual.page,
			clockFraction: round4(clockFraction),
			angle: round4(drumAngle(entry.face, actual.page, clockFraction, drum.turnFraction, drum.count)),
		}));
	}
	return actual;
}

/** 一条 drawable 的摘要（与 Java 的 MmtrFaceDocumentVectorTests 逐字段同名同序）。 */
function summariseDrawable(drawable) {
	const element = drawable.element;
	return {
		type: element.type,
		x: round4(element.x),
		y: round4(element.y),
		w: round4(element.w),
		h: round4(element.h),
		size: round4(element.size),
		text: element.text,
		anim: element.anim === null || element.anim === undefined ? null : element.anim.kind,
	};
}

function round4(value) {
	return Math.round(Number(value) * 10000) / 10000;
}

/** 逐字段比；全对返回 null，否则返回一句"期望什么/实际什么"。 */
function compareDocumentCase(actual, expected) {
	for (const field of ['page', 'visible', 'texts', 'drawables', 'fps', 'background']) {
		if (expected[field] === undefined) {
			continue; // 可选字段（fps / drumAngle）没写就跳过
		}
		if (!sameValue(actual[field], expected[field])) {
			return field + ' 得到 ' + show(actual[field]) + '，期望 ' + show(expected[field]);
		}
	}
	if (expected.drumAngle !== undefined) {
		if (!Array.isArray(actual.drumAngle) || !Array.isArray(expected.drumAngle) || actual.drumAngle.length !== expected.drumAngle.length) {
			return 'drumAngle 得到 ' + show(actual.drumAngle) + '，期望 ' + show(expected.drumAngle);
		}
		for (let i = 0; i < expected.drumAngle.length; i++) {
			const got = actual.drumAngle[i];
			const want = expected.drumAngle[i];
			for (const field of ['face', 'page', 'clockFraction', 'angle']) {
				if (Math.abs(Number(got[field]) - Number(want[field])) > 1.0E-4) {
					return 'drumAngle[' + i + '].' + field + ' 得到 ' + show(got[field]) + '，期望 ' + show(want[field])
						+ '（整条 得到 ' + show(got) + '，期望 ' + show(want) + '）';
				}
			}
		}
	}
	return null;
}

/**
 * 数值数组 / 数：每条按 1e-4 容差比（与 Java 侧读同一份 geometry.json 的容差一致）。
 * 4 位小数写出来的期望值在这个容差下是精确的。
 */
function compareNumbers(actual, expected) {
	const tolerance = 1.0E-4;
	if (Array.isArray(expected)) {
		if (!Array.isArray(actual) || actual.length !== expected.length) {
			return '得到 ' + show(actual) + '，期望 ' + show(expected);
		}
		const bad = [];
		for (let i = 0; i < expected.length; i++) {
			if (!(Math.abs(Number(actual[i]) - Number(expected[i])) <= tolerance)) {
				bad.push('[' + i + '] 得到 ' + show(actual[i]) + '，期望 ' + show(expected[i]));
			}
		}
		return bad.length === 0 ? null : bad.join('；');
	}
	if (Math.abs(Number(actual) - Number(expected)) <= tolerance) {
		return null;
	}
	return '得到 ' + show(actual) + '，期望 ' + show(expected);
}

// ---- 清单核对 -----------------------------------------------------------------------------------

/** 读 schema.json（缺了就退出 —— 权威缺了，比对没有意义）。 */
function loadSchema() {
	if (!existsSync(schemaPath)) {
		console.error('[selftest] 找不到键表 schema.json：' + schemaPath);
		console.error('[selftest] （它由 Java 的 MmtrFaceSchema 导出 —— 缺了它，缺省值就只剩工作室内嵌那一份）');
		process.exit(2);
	}
	try {
		return JSON.parse(readFileSync(schemaPath, 'utf8'));
	} catch (e) {
		console.error('[selftest] schema.json 不是合法 JSON：' + e.message);
		process.exit(2);
	}
}

/** 一张清单 vs 另一张：逐项同序（多了少了次序反了都算不一致）。 */
function checkList(theirsName, theirsFile, theirs, oursName, ours) {
	if (!Array.isArray(theirs)) {
		failed++;
		console.log('  失败  ' + pad(theirsName, 14) + ' ' + theirsFile + ' 里没有这个清单');
		return;
	}
	if (sameList(ours, theirs)) {
		console.log('  通过  ' + pad(theirsName, 14) + ours.length + ' 项，逐项同序一致（' + oursName + '）');
		return;
	}
	failed++;
	console.log('  失败  ' + pad(theirsName, 14) + ' 与 ' + oursName + ' 不一致');
	console.log('        ' + theirsFile + '：' + theirs.join(', '));
	console.log('        ' + oursName + '：' + ours.join(', '));
}

/**
 * schema.json 每条键的 default ↔ schema.mjs 的内嵌兜底缺省（逐节逐键）。
 *
 * 这一条守的是"缺省只有一处事实"：工作室在浏览器/Node 里同步读的就是内嵌那一份，
 * 它一旦与 Java 导出的键表分家，"面板上显示的缺省"与"引擎真的用的缺省"就开始各说各话。
 */
function checkDefaults(schema) {
	const ours = new Map((EMBEDDED_SCHEMA.sections || []).map(section => [section.name, section]));
	let keysChecked = 0;
	const problems = [];
	for (const section of schema.sections || []) {
		const mine = ours.get(section.name);
		if (mine === undefined) {
			problems.push('内嵌表里没有节「' + section.name + '」');
			continue;
		}
		ours.delete(section.name);
		for (const key of section.keys || []) {
			const found = (mine.keys || []).find(candidate => candidate.name === key.name);
			if (found === undefined) {
				problems.push(section.name + ' 的键「' + key.name + '」在内嵌表里没有');
				continue;
			}
			keysChecked++;
			if (!sameValue(found.default, key.default)) {
				problems.push(section.name + ' 的键「' + key.name + '」缺省 得到 ' + show(found.default) + '，期望 ' + show(key.default));
			}
		}
		for (const key of mine.keys || []) {
			if (!(section.keys || []).some(candidate => candidate.name === key.name)) {
				problems.push(section.name + ' 的键「' + key.name + '」在 schema.json 里没有（内嵌表多了一条）');
			}
		}
	}
	for (const name of ours.keys()) {
		problems.push('内嵌表里多了一节「' + name + '」（schema.json 没有）');
	}
	if (problems.length === 0) {
		console.log('  通过  ' + pad('缺省值', 14) + keysChecked + ' 条键的 default 与 schema.mjs 的内嵌兜底逐字一致（'
			+ (schema.sections || []).length + ' 节）');
		return;
	}
	failed++;
	console.log('  失败  ' + pad('缺省值', 14) + problems.length + ' 处与 schema.mjs 的内嵌兜底缺省不一致');
	for (const problem of problems) {
		console.log('        ' + problem);
	}
}

/**
 * 算子与过滤器清单仍与 tools/anchor-check/verify_face.js 一致。
 * （F3 没动这两张表；元素类型那张改由 schema.json 说话，见文件头。）
 */
function checkLogicLists() {
	let source = null;
	if (existsSync(verifyPath)) {
		source = readFileSync(verifyPath, 'utf8');
	}
	for (const list of [
		{ name: 'OPERATORS', ours: OPERATORS },
		{ name: 'FILTERS', ours: FILTERS },
	]) {
		const theirs = source === null ? null : extractArray(source, list.name);
		if (theirs === null) {
			failed++;
			console.log('  失败  ' + pad(list.name, 14) + ' 没能在 verify_face.js 里找到 `const ' + list.name + ' = [...]`');
			continue;
		}
		if (sameList(list.ours, theirs)) {
			console.log('  通过  ' + pad(list.name, 14) + list.ours.length + ' 项，逐项同序一致（verify_face.js）');
		} else {
			failed++;
			console.log('  失败  ' + pad(list.name, 14) + ' 与 verify_face.js 不一致');
			console.log('        本文件：' + list.ours.join(', '));
			console.log('        那个文件：' + theirs.join(', '));
		}
	}
}

// ---- 小工具 -------------------------------------------------------------------------------------

/** 深比较：数字按数值（含 NaN）、null 必须 null、数组/对象递归。 */
function sameValue(actual, expected) {
	if (expected === null) {
		return actual === null;
	}
	if (typeof expected === 'number') {
		return typeof actual === 'number' && (actual === expected || (Number.isNaN(actual) && Number.isNaN(expected)));
	}
	if (typeof expected === 'string' || typeof expected === 'boolean') {
		return actual === expected;
	}
	if (Array.isArray(expected)) {
		if (!Array.isArray(actual) || actual.length !== expected.length) {
			return false;
		}
		return expected.every((item, index) => sameValue(actual[index], item));
	}
	if (typeof expected === 'object') {
		if (actual === null || typeof actual !== 'object' || Array.isArray(actual)) {
			return false;
		}
		const expectedKeys = Object.keys(expected);
		const actualKeys = Object.keys(actual);
		if (actualKeys.length !== expectedKeys.length) {
			return false;
		}
		return expectedKeys.every(key => Object.prototype.hasOwnProperty.call(actual, key) && sameValue(actual[key], expected[key]));
	}
	return false;
}

/** 从 verify_face.js 里抽一个 `const NAME = [...]` 字符串数组。 */
function extractArray(source, name) {
	const block = source.match(new RegExp('const\\s+' + name + '\\s*=\\s*\\[([\\s\\S]*?)\\]'));
	if (block === null) {
		return null;
	}
	const items = [];
	const itemPattern = /'([^']*)'|"([^"]*)"/g;
	let match;
	while ((match = itemPattern.exec(block[1])) !== null) {
		items.push(match[1] !== undefined ? match[1] : match[2]);
	}
	return items.length === 0 ? null : items;
}

function sameList(left, right) {
	return left.length === right.length && left.every((item, index) => item === right[index]);
}

function pad(text, width) {
	return text + ' '.repeat(Math.max(0, width - text.length));
}

function describeKeys(value) {
	return value !== null && typeof value === 'object' ? String(Object.keys(value).length) : '（没有）';
}

function show(value) {
	if (value === undefined) {
		return 'undefined';
	}
	if (typeof value === 'number' && Number.isNaN(value)) {
		return 'NaN';
	}
	try {
		return JSON.stringify(value);
	} catch (e) {
		return String(value);
	}
}
