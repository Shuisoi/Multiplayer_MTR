/*
 * app.mjs —— 工作室的页面逻辑：选文件 → 点面 → 改数据 → 看牌 → **绘制** → 保存 → 跑自检。
 *
 * 结构上没有框架、没有状态库：一个 state 对象 + 几个 render 函数 + change 事件重画。
 * 为什么这么朴素：这是给作者看排版用的工具，不是展示页；页面越薄，越不容易遮住"面文档到底画了什么"。
 *
 * ## 这一层的三条硬边界（别越）
 *
 * 1. **事实来源是原始 anchors JSON 对象**（`state.root`），不是解析后的 `FaceDocument`。
 *    编辑的是 `faces[面名]` 那一层（F3 是 `pages[i].elements`）；每次改动都走同一个入口
 *    `commit(label, mutate)`：改 JSON → `parseDocument(JSON.stringify(...), 面名, id)` 重新解析
 *    → `painter.paint(...)` 重画 → 跑一遍静态检查。撤销栈存的是 JSON 深拷贝。
 * 2. **几何/命中/吸附/微调/路径/条件构造器都在 `designer.mjs` 里**（纯函数、有 Node 自检），
 *    页面只负责"把事件变成调用、把结果画出来"。别在这里再写一遍。
 * 3. **失败要看得见**：解析失败、schema/fields/向量读不到、保存被拒 —— 都写在页面上（红字），
 *    不许静默白屏（游戏里那几类是静默跳过，工作室的存在意义就是把它们说出来）。
 *
 * `pages` 的处理：document.mjs 目前只认顶层的 `elements`，所以选了某一页之后，本页把**当前页的
 * elements** 拼成一份投影 JSON 再交给**同一个** `parseDocument` 解析（不另起一套解析）。
 */

import { OPERATORS, evaluate, lookup } from './logic.mjs';
import { FILTERS } from './text.mjs';
import {
	DEFAULT_PX_PER_METRE,
	anchors as readAnchors,
	builtinPid,
	colorToHex,
	hasSection,
	parseAnchorFile,
	parseDocument,
	pathIsKnown,
	texts,
	visible,
} from './document.mjs';
// 名字空间导入：页支持（allPages/plan/pageIndex/animated/fps）是 F3 那一轮加进 document.mjs 的，
// 这里**有就用**（指令就是这么要求的），没有就退回"按 JSON 结构自己数页"。用 typeof 守卫是为了
// 两份文件同时被人改的时候，页面至少还能起来。
import * as faceDoc from './document.mjs';
import { PRESET_NAMES, buildData, describe, preset } from './presets.mjs';
import { ELEMENT_TYPES, paint } from './painter.mjs';
import {
	ARROW_KEYS,
	LOGIC_OPERATORS,
	ROW_OPERATORS,
	ROW_OPERATOR_LABELS,
	appendElement,
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
	extensionReportIsEmpty,
	formatPath,
	getPath,
	groupDepth,
	handleAtPoint,
	isBuilderRepresentable,
	isNamespacedName,
	isPlainObject,
	javaClassFromType,
	logicFieldChoices,
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
	readNumber,
	replaceRoot,
	resizeBox,
	sameJson,
	scanFaceForExtensions,
	setPath,
	snapBox,
	snapLabel,
	snapLines,
	unknownKeys,
	valuesForKey,
} from './designer.mjs';

/** 画布的像素上限（长边）：作者要的是看清，不是渲一张 4000 px 的图。 */
const MAX_CANVAS_PIXELS = 2048;
/** 找不到锚点尺寸时的缺省牌面（与 FacePreview 的缺省一致）。 */
const DEFAULT_WIDTH_M = 1.24;
const DEFAULT_HEIGHT_M = 0.22;
/** 撤销栈上限（步）。 */
const UNDO_LIMIT = 100;
/** 拖动时的吸附阈值（比例）—— 与 designer.mjs 的缺省同值，这里只是显式写出来。 */
const SNAP_LIMIT = 0.01;

const PRESET_LABELS = { running: '跑着', stopped: '停站开门', return: '回库趟', idle: '没任务' };

const state = {
	anchorText: null,
	sourceLabel: '（还没打开文件）',
	/** 从哪儿打开的（工作区相对路径，如 mmtr/tools/.../saf420.json）；文件选择器打开时为 null。 */
	sourcePath: null,
	/** 解析后的锚点 JSON **对象**（事实来源；编辑的就是它）。 */
	root: null,
	/** faces 对象在 root 里的路径（锚点 JSON 是 ['faces']；车辆配置是 ['cars', i, 'faces']）。 */
	facesPath: null,
	facesNote: '',
	faceNames: [],
	anchorList: [],
	fieldSpecs: [],
	fieldTypes: {},
	raw: {},
	data: {},
	doc: null,
	selection: null,
	widthM: DEFAULT_WIDTH_M,
	heightM: DEFAULT_HEIGHT_M,
	sizeNote: '文件里没有同名锚点，用缺省 ' + DEFAULT_WIDTH_M + '×' + DEFAULT_HEIGHT_M + ' m',
	/** 引擎视角的解析结果（整块面，含 pages）：回答"引擎此刻会选哪一页"。 */
	engineDoc: null,
	/** 键表（schema.json）。 */
	schema: null,
	schemaError: null,
	/** 当前页下标（没有 pages 时恒为 0，且不写进文档）。 */
	pageIndex: 0,
	/** 当前选中的元素下标（原始数组下标；-1 = 没选）。 */
	selectedIndex: -1,
	undo: [],
	redo: [],
	dirty: false,
	grid: true,
	ruler: true,
	snapGuides: [],
	/** 条件构造器的中间树：key = 路径字符串（含未上屏的空行）。 */
	logicTrees: {},
	logicModes: {},
	logicSources: {},
	/** 源码编辑器（elements/pages/object 用）。 */
	sourceEditor: null,
	status: '',
	droppedValues: [],
	/** 上次读盘/写盘时的 JSON（判"有没有未保存的改动"）。 */
	savedJson: null,
	/** 「导出 Java 骨架」的最近一次产物（下载/保存都用它；null = 还没生成过）。 */
	exportResult: null,
	/** 导出时扫整块面（所有页 + 文档级 vars/require/pageExpr）还是只扫当前页。 */
	exportWholeFace: true,
	/** 类名是不是用户手改过（改过就不再跟着面名自动变）。 */
	exportClassNameEdited: false,
	/** `?export=1` 要求过"读完锚点就生成一次"（见 openExportFromQuery）。 */
	exportPending: false,
};

const $ = id => document.getElementById(id);

// index.html 里那段经典脚本靠这个标记判断"module 到底跑没跑起来"（file:// 下它跑不起来）
window.__mmtrStudioBooted = true;

// ---- DOM 小工具 ---------------------------------------------------------------------------------

function tag(name, className, text) {
	const node = document.createElement(name);
	if (className) {
		node.className = className;
	}
	if (text !== undefined && text !== null) {
		node.textContent = text;
	}
	return node;
}

function miniButton(label, onClick, title) {
	const button = tag('button', 'mini', label);
	if (title) {
		button.title = title;
	}
	button.addEventListener('click', onClick);
	return button;
}

function note(className, text) {
	return tag('div', className, text);
}

// ---- 启动 ---------------------------------------------------------------------------------------

for (const name of PRESET_NAMES) {
	const button = document.createElement('button');
	button.className = 'preset';
	button.dataset.preset = name;
	button.textContent = PRESET_LABELS[name] + '（' + name + '）';
	button.addEventListener('click', () => applyPreset(name));
	$('presets').appendChild(button);
}

for (const type of elementTypesForMenu()) {
	const option = tag('option', null, type);
	option.value = type;
	$('newType').appendChild(option);
}

/** 新建元素菜单里的类型：以 schema.json 的 elementTypes 为准（缺了就用 painter 认得的那 7 种）。 */
function elementTypesForMenu() {
	const fromSchema = state.schema && Array.isArray(state.schema.elementTypes) ? state.schema.elementTypes : null;
	const list = fromSchema && fromSchema.length > 0 ? fromSchema : ELEMENT_TYPES;
	state.unsupportedTypes = list.filter(type => !ELEMENT_TYPES.includes(type));
	return list;
}

/** schema.json 读完（或读不到）之后重建一次类型菜单：读得到就用键表里那份（含 F3 的 image/foreach）。 */
function renderTypeMenu() {
	const select = $('newType');
	const previous = select.value;
	select.textContent = '';
	for (const type of elementTypesForMenu()) {
		const option = tag('option', null, type + (ELEMENT_TYPES.includes(type) ? '' : '（painter 还没实现）'));
		option.value = type;
		select.appendChild(option);
	}
	if (previous) {
		select.value = previous;
	}
}

$('file').addEventListener('change', event => {
	const file = event.target.files && event.target.files[0];
	if (!file) {
		return;
	}
	const reader = new FileReader();
	reader.onload = () => {
		state.sourcePath = null;
		loadAnchors(String(reader.result), '选的文件 ' + file.name);
	};
	reader.onerror = () => setBanner('读这个文件失败：' + (reader.error ? reader.error.message : '未知原因'), false);
	reader.readAsText(file, 'utf-8');
});

$('selftest').addEventListener('click', runSelfTest);
$('btnUndo').addEventListener('click', undo);
$('btnRedo').addEventListener('click', redo);
$('btnAddElement').addEventListener('click', addElementFromMenu);
$('chkGrid').addEventListener('change', event => {
	state.grid = event.target.checked;
	drawOverlay();
});
$('chkRuler').addEventListener('change', event => {
	state.ruler = event.target.checked;
	drawOverlay();
});
$('btnSave').addEventListener('click', saveCurrent);
$('btnSaveAs').addEventListener('click', () => {
	const row = $('saveAsRow');
	row.hidden = !row.hidden;
	if (!row.hidden) {
		$('saveAsPath').value = state.sourcePath || defaultSaveAsPath();
		$('saveAsPath').focus();
	}
});
$('btnSaveAsGo').addEventListener('click', () => {
	const path = $('saveAsPath').value.trim();
	if (path === '') {
		setBanner('另存为要先写一个工作区相对路径（例如 sandbox\\face-studio\\a.json）', false);
		return;
	}
	saveTo(path);
});
$('btnSaveAsCancel').addEventListener('click', () => {
	$('saveAsRow').hidden = true;
});

// ---- 导出 Java 骨架（F4）的接线 ------------------------------------------------------------------
$('exportWholeFace').checked = state.exportWholeFace;
$('exportPackage').value = 'vendor.faceaddon';
$('btnExportJava').addEventListener('click', () => {
	showExportPanel();
	generateExportSkeleton();
});
$('btnExportGo').addEventListener('click', () => {
	state.exportClassNameEdited = true;
	generateExportSkeleton();
});
$('exportWholeFace').addEventListener('change', generateExportSkeleton);
$('btnExportDownload').addEventListener('click', downloadExportJava);
$('btnExportSave').addEventListener('click', saveExportJava);
$('btnExportHide').addEventListener('click', () => {
	$('exportPanel').hidden = true;
});

bindCanvas();
bindKeyboard();
window.addEventListener('beforeunload', event => {
	if (state.dirty) {
		event.preventDefault();
		event.returnValue = '';
	}
});

loadSchema()
	.then(() => {
		renderTypeMenu();
		return loadFields();
	})
	.then(() => {
		applyPreset('running');
		renderProperties();
		const fromQuery = new URLSearchParams(location.search).get('anchors');
		if (fromQuery) {
			state.sourcePath = workspacePathFromQuery(fromQuery);
			// `&export=1`：打开就把「导出 Java 骨架」展开并生成一次 —— 给扩展作者一条直接可分享的链接
			// （例：?anchors=/data/sandbox/.../anchors.json&export=1），也顺便让"这条链子到底通不通"
			// 不必靠人手点一遍（无头截图与自动化都读它）。
			openExportFromQuery(new URLSearchParams(location.search).get('export'));
			loadFromQuery(fromQuery);
		} else {
			openExportFromQuery(new URLSearchParams(location.search).get('export'));
			setBanner('选一份锚点 JSON 开始（也可以用 face-studio.ps1 -Anchors <工作区相对路径> 直接把文件喂进来）。'
				+ '\n打开之后：点一块面 → 在牌面上拖元素 / 拉把手 / 方向键微调 → 右边改属性 → 顶部「保存」写回磁盘。', true);
		}
	})
	.catch(e => {
		// 走到这里多半是同步代码炸了：把话说出来，别白屏
		setBanner('工作室启动失败：' + (e && e.message ? e.message : e), false);
	});

// ---- 键表（schema.json） -------------------------------------------------------------------------

async function loadSchema() {
	try {
		const response = await fetch('schema.json', { cache: 'no-store' });
		if (!response.ok) {
			throw new Error('HTTP ' + response.status);
		}
		const parsed = await response.json();
		if (!Array.isArray(parsed.sections) || parsed.sections.length === 0) {
			throw new Error('sections 是空的');
		}
		state.schema = parsed;
		state.schemaError = null;
	} catch (e) {
		state.schema = null;
		state.schemaError = '读不到 schema.json（' + e.message + '）—— 属性面板是按键表生成的，没有它就只剩"用源码编辑"。'
			+ '请用 face-studio.ps1 起的服务器打开这个页面（http://127.0.0.1:8910/）。';
	}
}

function section(name) {
	if (!state.schema) {
		return null;
	}
	return state.schema.sections.find(item => item.name === name) || null;
}

function sectionKeys(name) {
	const found = section(name);
	return found && Array.isArray(found.keys) ? found.keys : [];
}

function specOf(sectionName, key) {
	return sectionKeys(sectionName).find(spec => spec.name === key) || null;
}

/** 元素的全部认得键（common + 该 type 的专属键）。 */
function elementKnownNames(type) {
	const names = sectionKeys('common').map(spec => spec.name);
	for (const spec of sectionKeys('element:' + String(type || '').toLowerCase())) {
		names.push(spec.name);
	}
	return names;
}

function animKnownNames(kind) {
	return sectionKeys('anim:' + String(kind || '').toLowerCase()).map(spec => spec.name);
}

// ---- fields.json（数据字段） ---------------------------------------------------------------------

async function loadFields() {
	try {
		const response = await fetch('fields.json', { cache: 'no-store' });
		if (!response.ok) {
			throw new Error('HTTP ' + response.status);
		}
		const parsed = await response.json();
		state.fieldSpecs = (Array.isArray(parsed.fields) ? parsed.fields : []).map(field => ({
			name: String(field.name),
			type: String(field.type || 'str'),
			doc: field.doc ? String(field.doc) : '',
		}));
		if (state.fieldSpecs.length === 0) {
			throw new Error('fields 是空的');
		}
		setBanner('字段表已就绪：fields.json，' + state.fieldSpecs.length + ' 个字段（' + state.fieldSpecs.filter(f => f.type === 'num').length + ' 个数字量）。', true);
	} catch (e) {
		// file:// 下浏览器不给 fetch 本地文件：退到"四个预设里出现过的字段"，并把这件事说出来
		state.fieldSpecs = fallbackFields();
		setBanner('读不到 fields.json（' + e.message + '）。字段表暂用四个预设里出现过的字段（另外补上派生量 speedKmh）；'
			+ '要完整字段表请用 face-studio.ps1 起的服务器打开这个页面。', false);
	}
	state.fieldTypes = {};
	for (const spec of state.fieldSpecs) {
		state.fieldTypes[spec.name] = spec.type;
	}
	renderFieldInputs();
}

/** fields.json 读不到时的兜底字段表（来源：四个预设 + 派生量），顺序稳定。 */
function fallbackFields() {
	const types = new Map();
	for (const name of PRESET_NAMES) {
		for (const [key, value] of Object.entries(preset(name))) {
			const type = typeof value === 'boolean' ? 'bool' : (typeof value === 'number' ? 'num' : 'str');
			if (!types.has(key) || types.get(key) === 'str') {
				types.set(key, type);
			}
		}
	}
	types.set('speedKmh', 'num');
	return Array.from(types, ([name, type]) => ({ name, type, doc: '（来自预设的兜底字段表）' }));
}

function loadFromQuery(value) {
	fetch(value, { cache: 'no-store' })
		.then(response => {
			if (!response.ok) {
				throw new Error('HTTP ' + response.status);
			}
			return response.text();
		})
		.then(text => loadAnchors(text, '服务器喂的 ' + value))
		.catch(e => setBanner('读不到 ?anchors=' + value + '：' + e.message
			+ '\n（用 face-studio.ps1 -Anchors <工作区相对路径> 时，路径会变成 /data/<相对路径>；手写的话就是 ?anchors=/data/sandbox/…）', false));
}

/** `?anchors=/data/<工作区相对路径>` → 工作区相对路径（保存时用的是它）。 */
function workspacePathFromQuery(value) {
	const match = /^\/data\/(.+)$/.exec(String(value || ''));
	return match === null ? null : match[1];
}

// ---- 锚点文件 -----------------------------------------------------------------------------------

function loadAnchors(text, label) {
	let root;
	try {
		root = parseAnchorFile(text);
	} catch (e) {
		state.anchorText = null;
		state.root = null;
		state.doc = null;
		state.faceNames = [];
		state.anchorList = [];
		state.selection = null;
		$('faces').textContent = '（解析失败）';
		$('anchors').textContent = '（解析失败）';
		$('faceInfo').textContent = '（解析失败）';
		$('problems').textContent = '';
		$('layers').textContent = '（解析失败）';
		setBanner('这份锚点 JSON 用不了：' + e.message, false);
		return;
	}
	state.anchorText = text;
	state.root = root;
	state.sourceLabel = label;
	state.undo = [];
	state.redo = [];
	state.logicTrees = {};
	state.logicModes = {};
	state.logicSources = {};
	state.sourceEditor = null;
	state.selectedIndex = -1;
	state.pageIndex = 0;
	state.savedJson = JSON.stringify(root);
	refreshDirty();

	const found = resolveFaces(root);
	state.facesPath = found === null ? null : found.path;
	state.facesNote = found === null ? '' : found.note;
	state.faceNames = found === null ? [] : Object.keys(found.faces);
	state.anchorList = readAnchors(text);
	state.selection = null;
	renderFaceList();
	renderAnchorList();
	renderPageTabs();
	renderLayers();
	renderUndoButtons();

	const notes = [];
	if (found !== null && found.path[0] === 'cars') {
		notes.push('面文档写在**车辆配置**的 ' + formatPath(found.path) + '（面文档的事实来源就在这儿：改完要重新打包才会进游戏）。');
	}
	if (state.faceNames.length === 0) {
		notes.push('这份文件里没有 faces 段（老包），下面用 pid/next 段翻译出来的两块内置水牌。');
	}
	if (hasSection(text, 'pid') || hasSection(text, 'next')) {
		notes.push('文件里有旧的 pid/next 段：内置水牌就是它们翻译出来的面文档。');
	}
	setBanner('已打开 ' + label + '：' + state.faceNames.length + ' 块面、' + state.anchorList.length + ' 个锚点。'
		+ (notes.length ? '\n' + notes.join('\n') : ''), true);
	updateSavePathLabel();

	const first = state.faceNames.length > 0 ? { kind: 'face', name: state.faceNames[0] } : { kind: 'builtin', board: 'pid' };
	select(first);
	runPendingExport();
}

/**
 * 面文档住在哪：优先 `faces`（锚点 JSON），其次车辆配置的 `cars[i].faces`。
 * 返回 `{faces, path, note}` —— path 是"编辑原始 JSON"要用的路径。
 */
function resolveFaces(root) {
	if (isPlainObject(root.faces)) {
		return { faces: root.faces, path: ['faces'], note: '锚点 JSON 的 faces 段' };
	}
	if (Array.isArray(root.cars)) {
		for (let index = 0; index < root.cars.length; index++) {
			const car = root.cars[index];
			if (isPlainObject(car) && isPlainObject(car.faces)) {
				const label = car.id || car.name || ('#' + index);
				return {
					faces: car.faces,
					path: ['cars', index, 'faces'],
					note: '车辆配置 cars[' + index + ']（' + label + '）的 faces 段',
				};
			}
		}
	}
	return null;
}

function renderFaceList() {
	const box = $('faces');
	box.textContent = '';
	const entries = state.faceNames.map(name => ({ kind: 'face', name, label: name, note: '面文档 ' + formatPath([...(state.facesPath || []), name]) }));
	// 老包的 pid/next 段：翻成等价的面文档，于是工作室也能看老水牌
	entries.push({ kind: 'builtin', board: 'pid', label: 'pid（终点牌，内置翻译）', note: '旧 pid 段 → 面文档（只读）' });
	entries.push({ kind: 'builtin', board: 'next', label: 'next（下一站牌，内置翻译）', note: '旧 next 段 → 面文档（只读）' });

	for (const entry of entries) {
		const button = document.createElement('button');
		button.className = 'faceItem';
		button.textContent = entry.label;
		button.title = entry.note;
		button.dataset.key = entry.kind === 'face' ? 'face:' + entry.name : 'builtin:' + entry.board;
		button.addEventListener('click', () => select(entry));
		box.appendChild(button);
	}
	$('facesNote').textContent = state.facesNote ? ('面文档位置：' + state.facesNote) : '';
}

function renderAnchorList() {
	const box = $('anchors');
	box.textContent = '';
	if (state.anchorList.length === 0) {
		box.textContent = '（没有 anchors 段）';
		return;
	}
	for (const anchor of state.anchorList) {
		const button = document.createElement('button');
		button.className = 'anchorItem';
		const size = Number(anchor.widthM) > 0 && Number(anchor.heightM) > 0
			? shortNumber(anchor.widthM) + '×' + shortNumber(anchor.heightM) + ' m'
			: '没写尺寸';
		button.textContent = anchor.name + ' · ' + (anchor.kind || '?') + ' · ' + size;
		button.dataset.key = 'anchor:' + anchor.name;
		button.addEventListener('click', () => {
			if (state.faceNames.includes(anchor.name)) {
				select({ kind: 'face', name: anchor.name });
			} else {
				setBanner('锚点「' + anchor.name + '」（' + (anchor.kind || '?') + '）在 faces 里没有同名的键 —— '
					+ 'kind=face 的锚点必须有面文档，否则模型里做的那块屏是白的（verify_face.js 的 P1 就是查这个）。', false);
			}
		});
		box.appendChild(button);
	}
}

function select(entry) {
	if (!state.root) {
		return;
	}
	state.selection = entry;
	state.faceName = entry.kind === 'face' ? entry.name : entry.board;
	state.pageIndex = 0;
	state.selectedIndex = -1;
	state.sourceEditor = null;
	state.logicTrees = {};
	reparse();
	// 默认选中第一个元素：一进来就能拖，属性面板/条件构造器也看得见。
	// 有 when 写法的元素优先 —— 作者最常改的就是"这个元素什么时候画"，条件构造器直接摊开。
	if (entry.kind === 'face') {
		const list = rawElements();
		if (Array.isArray(list) && list.length > 0) {
			const conditional = list.findIndex(item => isPlainObject(item) && Object.prototype.hasOwnProperty.call(item, 'when'));
			state.selectedIndex = conditional >= 0 ? conditional : 0;
		}
	}
	// 牌面尺寸：优先同名锚点，其次 kind 相同的锚点，最后缺省
	const anchor = findAnchor(entry);
	if (anchor !== null) {
		state.widthM = Number(anchor.widthM) > 0 ? Number(anchor.widthM) : DEFAULT_WIDTH_M;
		state.heightM = Number(anchor.heightM) > 0 ? Number(anchor.heightM) : DEFAULT_HEIGHT_M;
		state.sizeNote = '锚点 ' + anchor.name + '（' + (anchor.kind || '?') + '）';
	} else {
		state.widthM = DEFAULT_WIDTH_M;
		state.heightM = DEFAULT_HEIGHT_M;
		state.sizeNote = entry.kind === 'builtin'
			? '内置水牌按缺省 ' + DEFAULT_WIDTH_M + '×' + DEFAULT_HEIGHT_M + ' m（pid/next 段不带尺寸，游戏里用锚点的）'
			: '文件里没有同名锚点，用缺省 ' + DEFAULT_WIDTH_M + '×' + DEFAULT_HEIGHT_M + ' m';
	}
	markSelected(entry);
	renderAll();
	renderPageTabs();
	renderLayers();
	renderProperties();
	renderUndoButtons();
	// 换了一块面：类名跟着新面名走（手改过就不动），面板开着就顺手重扫一遍 ——
	// 免得作者看着上一块面的骨架以为这就是当前这块的。
	state.exportClassNameEdited = false;
	state.exportResult = null;
	if (!$('exportPanel').hidden) {
		generateExportSkeleton();
	}
	setStatus('当前：' + (entry.label || entry.name || entry.board) + (state.selection.kind === 'builtin' ? '（内置翻译，只读）' : ''));
}

function findAnchor(entry) {
	if (entry.kind === 'face') {
		return state.anchorList.find(anchor => anchor.name === entry.name) || null;
	}
	return state.anchorList.find(anchor => anchor.kind === entry.board)
		|| state.anchorList.find(anchor => String(anchor.name).startsWith(entry.board + '_'))
		|| null;
}

function markSelected(entry) {
	const key = entry.kind === 'face' ? 'face:' + entry.name : 'builtin:' + entry.board;
	for (const button of document.querySelectorAll('.faceItem')) {
		button.classList.toggle('selected', button.dataset.key === key);
	}
	const anchor = findAnchor(entry);
	for (const button of document.querySelectorAll('.anchorItem')) {
		button.classList.toggle('selected', anchor !== null && button.dataset.key === 'anchor:' + anchor.name);
	}
}

// ---- 页 / 元素 / 路径（事实来源是 JSON 对象） ----------------------------------------------------

function faceObject() {
	if (!state.root || !state.facesPath || !state.faceName) {
		return null;
	}
	return getPath(state.root, [...state.facesPath, state.faceName]);
}

/** 文档（faces[面名]）的路径。 */
function documentPath() {
	return [...(state.facesPath || []), state.faceName];
}

function pagesArray() {
	const face = faceObject();
	if (!isPlainObject(face) || !Array.isArray(face.pages)) {
		return null;
	}
	// ★ `"pages": []` 在引擎里等于"没写 pages"（Java 存成 null）：编辑也必须按那条口径走，
	//   否则页签会指向一个不存在的 pages[0].elements。
	return face.pages.length > 0 ? face.pages : null;
}

function currentPageObject() {
	const pages = pagesArray();
	return pages === null ? null : pages[state.pageIndex];
}

/** 当前页的元素数组（**原始 JSON 数组**，编辑的就是它）。 */
function rawElements() {
	const path = elementsPath();
	if (path === null) {
		return null;
	}
	const list = getPath(state.root, path);
	return Array.isArray(list) ? list : null;
}

/** 当前页 elements 的路径（没写 pages 时是顶层的 elements —— v1 形态，**不**自动改写成 pages）。 */
function elementsPath() {
	const pages = pagesArray();
	if (pages === null) {
		return [...documentPath(), 'elements'];
	}
	const page = pages[state.pageIndex];
	if (!isPlainObject(page)) {
		return null;
	}
	return [...documentPath(), 'pages', state.pageIndex, 'elements'];
}

function elementPath(index) {
	const base = elementsPath();
	return base === null ? null : [...base, index];
}

/**
 * 交给 parseDocument 的文本 —— **编辑视角**：只有你正在编辑的那一页。
 *
 * `parseDocument(text, 面名, id)` 只认**根上的 `faces`**，而面文档可能住在车辆配置的
 * `cars[i].faces` 里（作者指南 §2：车辆配置就是面文档的事实来源）。所以这里拼一份
 * `{"faces":{"<面名>": <那一块面>}}` —— 面本来就在根上时，这份投影与整份 JSON **逐字等价**
 * （parseDocument 读的就是这一层），改写过的只有"从哪个位置取"。
 */
function documentTextForParse() {
	const face = faceObject();
	if (!isPlainObject(face)) {
		return JSON.stringify(state.root);
	}
	const projected = cloneJson(face);
	if (Array.isArray(projected.pages) && projected.pages.length > 0) {
		const page = projected.pages[state.pageIndex];
		delete projected.pages;
		projected.elements = isPlainObject(page) && Array.isArray(page.elements) ? page.elements : [];
	}
	return JSON.stringify({ faces: { [state.faceName]: projected } });
}

/** 交给 parseDocument 的文本 —— **引擎视角**：整块面（pages 全留着，谁被选中由 pageExpr/pageSeconds 决定）。 */
function documentTextForEngine() {
	const face = faceObject();
	if (!isPlainObject(face)) {
		return JSON.stringify(state.root);
	}
	return JSON.stringify({ faces: { [state.faceName]: cloneJson(face) } });
}

/** 当前编辑页在**解析后**的元素表（静态检查用它；forEach 展开后的计划另说）。 */
function editingPageElements() {
	if (!state.doc) {
		return [];
	}
	const pages = Array.isArray(state.doc.pages) ? state.doc.pages : null;
	if (pages !== null && pages.length > 0) {
		const page = pages[Math.min(state.pageIndex, pages.length - 1)];
		return page && Array.isArray(page.elements) ? page.elements : [];
	}
	return Array.isArray(state.doc.elements) ? state.doc.elements : [];
}

/** 原始 JSON 里的各页（没写 pages 时 = "唯一的一页"，内容就是顶层 elements）。 */
function allPagesRaw() {
	const pages = pagesArray();
	if (pages !== null) {
		return pages;
	}
	const face = faceObject();
	return [isPlainObject(face) ? { name: '', elements: Array.isArray(face.elements) ? face.elements : [] } : { name: '', elements: [] }];
}

/** 引擎此刻会选哪一页（有 document.mjs 的 pageIndex 就用它；用的是"整块面"那份解析）。 */
function enginePageIndex() {
	if (!state.engineDoc || typeof faceDoc.pageIndex !== 'function') {
		return 0;
	}
	try {
		return faceDoc.pageIndex(state.engineDoc, state.data, 0);
	} catch (e) {
		return 0;
	}
}

/** 这份文档有几页（引擎口径：没写 pages = 1 页）。 */
function enginePageCount() {
	if (!state.engineDoc) {
		return 0;
	}
	if (typeof faceDoc.allPages === 'function') {
		try {
			return faceDoc.allPages(state.engineDoc).length;
		} catch (e) {
			return 1;
		}
	}
	return Array.isArray(state.engineDoc.pages) && state.engineDoc.pages.length > 0 ? state.engineDoc.pages.length : 1;
}

/** 你在编辑的那一页自己的 require 现在成不成立（不成立 ⇒ 游戏里这一页不画）。 */
function editingPageRequireHolds() {
	const pages = pagesArray();
	if (pages === null) {
		return true;
	}
	const page = pages[state.pageIndex];
	if (!isPlainObject(page) || !Object.prototype.hasOwnProperty.call(page, 'require')) {
		return true;
	}
	try {
		const source = state.engineDoc || state.doc;
		const prepared = typeof faceDoc.augment === 'function' ? faceDoc.augment(source, state.data) : state.data;
		return Boolean(evaluate(page.require, prepared));
	} catch (e) {
		return false;
	}
}

/** 写 pages 时又留了顶层 elements ⇒ 后者被忽略（引擎口径）：要在页面上说一句。 */
function topLevelElementsIgnored() {
	if (typeof faceDoc.ignoredTopLevelElements === 'function' && state.engineDoc) {
		try {
			return faceDoc.ignoredTopLevelElements(state.engineDoc);
		} catch (e) {
			return false;
		}
	}
	const face = faceObject();
	return isPlainObject(face) && Array.isArray(face.pages) && face.pages.length > 0
		&& Array.isArray(face.elements) && face.elements.length > 0;
}

function clampPageIndex() {
	const pages = pagesArray();
	if (pages === null) {
		state.pageIndex = 0;
		return;
	}
	if (state.pageIndex >= pages.length) {
		state.pageIndex = Math.max(0, pages.length - 1);
	}
}

/** 改完 JSON 之后重新解析（唯一的解析入口）。 */
function reparse() {
	state.anchorText = JSON.stringify(state.root);
	clampPageIndex();
	if (!state.selection) {
		state.doc = null;
		state.engineDoc = null;
		return;
	}
	try {
		state.doc = state.selection.kind === 'face'
			? parseDocument(documentTextForParse(), state.faceName, 'face-' + state.faceName)
			: builtinPid(state.selection.board, state.anchorText);
		// 第二份解析：整块面（保留 pages）—— 回答"引擎此刻会选哪一页/会不会自动轮转"，与编辑视角分开
		state.engineDoc = state.selection.kind === 'face'
			? parseDocument(documentTextForEngine(), state.faceName, 'engine-' + state.faceName)
			: state.doc;
	} catch (e) {
		state.doc = null;
		state.engineDoc = null;
		$('faceInfo').textContent = '';
		$('problems').textContent = '';
		setBanner('这块面解析不了：' + e.message, false);
		return;
	}
	const list = rawElements();
	if (list === null || state.selectedIndex >= list.length) {
		state.selectedIndex = list !== null && list.length > 0 && state.selectedIndex >= list.length ? list.length - 1 : state.selectedIndex;
	}
}

/** 只有"面文档"能编辑：内置翻译（pid/next）是糖，改它没有落点。 */
function editable() {
	if (!state.root || !state.selection) {
		setBanner('先打开一份锚点 JSON、选一块面，再动手。', false);
		return false;
	}
	if (state.selection.kind !== 'face') {
		setBanner('内置翻译的水牌（pid/next 段）是只读的：要改版式请给锚点写 faces 文档（面文档才是可编辑的事实来源）。', false);
		return false;
	}
	if (!state.schema) {
		setBanner(state.schemaError || 'schema.json 读不到，属性面板用不了。', false);
		return false;
	}
	return true;
}

// ---- 撤销 / 重做 / 提交（唯一改动入口） ----------------------------------------------------------

/**
 * **所有改动都从这儿过**：先存一份 JSON 深拷贝 → 应用 mutate → 入撤销栈 → 重解析/重画/重渲染面板。
 * @param {string} label 一句人话（状态栏显示，如"移动 text 到 0.42, 0.50"）
 * @param {(root:object)=>void} mutate 直接改 JSON 对象
 */
function commit(label, mutate) {
	if (!state.root) {
		return false;
	}
	const before = cloneJson(state.root);
	try {
		mutate(state.root);
	} catch (e) {
		replaceRoot(state.root, before);
		setBanner('这一步没做成：' + (e && e.message ? e.message : e), false);
		return false;
	}
	if (sameJson(before, state.root)) {
		setStatus('（没有变化）' + (label ? '：' + label : ''));
		return false;
	}
	state.undo.push({
		root: before,
		label: label || '改动',
		face: state.selection ? state.selection.name || state.selection.board : null,
		entry: state.selection,
		page: state.pageIndex,
		selected: state.selectedIndex,
	});
	if (state.undo.length > UNDO_LIMIT) {
		state.undo.shift();
	}
	state.redo.length = 0;
	afterChange(label);
	return true;
}

function afterChange(label) {
	reparse();
	refreshDirty();
	renderAll();
	renderPageTabs();
	renderLayers();
	renderProperties();
	renderUndoButtons();
	setStatus((label || '改动') + ' · 撤销栈 ' + state.undo.length + ' 步');
}

function undo() {
	if (state.undo.length === 0) {
		setStatus('没有可撤销的步骤');
		return;
	}
	const entry = state.undo.pop();
	state.redo.push({ root: cloneJson(state.root), label: entry.label, entry: state.selection, page: state.pageIndex, selected: state.selectedIndex });
	restoreSnapshot(entry);
	setStatus('撤销：' + entry.label + ' · 还能撤销 ' + state.undo.length + ' 步');
}

function redo() {
	if (state.redo.length === 0) {
		setStatus('没有可重做的步骤');
		return;
	}
	const entry = state.redo.pop();
	state.undo.push({ root: cloneJson(state.root), label: entry.label, entry: state.selection, page: state.pageIndex, selected: state.selectedIndex });
	restoreSnapshot(entry);
	setStatus('重做：' + entry.label);
}

function restoreSnapshot(entry) {
	state.root = entry.root;
	if (entry.entry) {
		state.selection = entry.entry;
		state.faceName = entry.entry.kind === 'face' ? entry.entry.name : entry.entry.board;
		markSelected(entry.entry);
	}
	state.pageIndex = entry.page === undefined ? 0 : entry.page;
	state.selectedIndex = entry.selected === undefined ? -1 : entry.selected;
	state.anchorText = JSON.stringify(state.root);
	state.sourceEditor = null;
	reparse();
	refreshDirty();
	renderAll();
	renderPageTabs();
	renderLayers();
	renderProperties();
	renderUndoButtons();
}

function renderUndoButtons() {
	$('btnUndo').disabled = state.undo.length === 0;
	$('btnRedo').disabled = state.redo.length === 0;
	$('btnUndo').title = state.undo.length > 0 ? ('Ctrl+Z 撤销：' + state.undo[state.undo.length - 1].label) : 'Ctrl+Z';
	$('btnRedo').title = state.redo.length > 0 ? ('Ctrl+Shift+Z 重做：' + state.redo[state.redo.length - 1].label) : 'Ctrl+Shift+Z';
}

// ---- 页签（F3 多页） ------------------------------------------------------------------------------

function renderPageTabs() {
	const box = $('pages');
	const actions = $('pageActions');
	box.textContent = '';
	actions.textContent = '';
	const pages = pagesArray();
	if (!state.root || !state.selection || state.selection.kind !== 'face') {
		box.className = 'muted';
		box.textContent = '（没打开 / 这份文档没写 pages）';
		return;
	}
	box.className = '';
	if (pages === null) {
		const face = faceObject();
		const emptyPages = isPlainObject(face) && Array.isArray(face.pages) && face.pages.length === 0;
		box.textContent = (emptyPages
			? '文档里的 pages 是**空数组** —— 引擎把它当成"没写 pages"（Java 存成 null），所以现在编辑的是顶层 elements。'
			: '这份文档没写 pages ⇒ 编辑的是顶层的 elements（v1 形态，不会自动改写成 pages）。');
		actions.appendChild(miniButton('把顶层 elements 变成第 1 页（加 pages）', addFirstPage, '会新建 pages:[{name:"页 1", elements: 原来的 elements}]，并删掉顶层 elements'));
		return;
	}
	pages.forEach((page, index) => {
		const name = isPlainObject(page) && typeof page.name === 'string' && page.name !== '' ? page.name : '第 ' + (index + 1) + ' 页';
		const button = tag('button', 'pageTab' + (index === state.pageIndex ? ' selected' : ''), name + (index === state.pageIndex ? ' ●' : ''));
		button.title = 'pages[' + index + ']' + (isPlainObject(page) && page.require ? ' · require ' + JSON.stringify(page.require) : '');
		button.addEventListener('click', () => {
			state.pageIndex = index;
			state.selectedIndex = rawElements() && rawElements().length > 0 ? 0 : -1;
			state.sourceEditor = null;
			reparse();
			renderAll();
			renderPageTabs();
			renderLayers();
			renderProperties();
			setStatus('切到第 ' + (index + 1) + ' 页（' + name + '）');
		});
		box.appendChild(button);
	});
	actions.appendChild(miniButton('＋ 加一页', addPage));
	const nameInput = tag('input');
	nameInput.type = 'text';
	nameInput.spellcheck = false;
	nameInput.size = 10;
	nameInput.placeholder = '页名';
	nameInput.value = pageNameOf(pages[state.pageIndex]);
	nameInput.id = 'pageNameInput';
	actions.appendChild(nameInput);
	actions.appendChild(miniButton('改页名', () => renamePage(nameInput.value)));
	actions.appendChild(miniButton('删这一页', deletePage));
}

function pageNameOf(page) {
	return isPlainObject(page) && typeof page.name === 'string' ? page.name : '';
}

function addPage() {
	if (!editable()) {
		return;
	}
	commit('加一页', root => {
		const face = getPath(root, documentPath());
		if (!Array.isArray(face.pages)) {
			face.pages = [];
		}
		face.pages.push({ name: '页 ' + (face.pages.length + 1), elements: [] });
		state.pageIndex = face.pages.length - 1;
		state.selectedIndex = -1;
	});
}

function addFirstPage() {
	if (!editable()) {
		return;
	}
	commit('把顶层 elements 变成第 1 页', root => {
		const face = getPath(root, documentPath());
		const elements = Array.isArray(face.elements) ? face.elements : [];
		face.pages = [{ name: '页 1', elements }];
		delete face.elements;
		state.pageIndex = 0;
		state.selectedIndex = elements.length > 0 ? 0 : -1;
	});
}

function renamePage(name) {
	if (!editable()) {
		return;
	}
	const trimmed = String(name || '').trim();
	if (trimmed === '') {
		setBanner('页名不能是空的（空名 = 只能靠下标找这一页）。', false);
		return;
	}
	commit('页名改成「' + trimmed + '」', root => {
		const page = getPath(root, [...documentPath(), 'pages', state.pageIndex]);
		if (!isPlainObject(page)) {
			throw new Error('这一页不是对象');
		}
		page.name = trimmed;
	});
}

function deletePage() {
	if (!editable()) {
		return;
	}
	const pages = pagesArray();
	if (pages === null || pages.length <= 1) {
		setBanner('至少要留一页（pages 空数组在引擎里等于没写）。', false);
		return;
	}
	const name = pageNameOf(pages[state.pageIndex]) || ('第 ' + (state.pageIndex + 1) + ' 页');
	commit('删掉「' + name + '」这一页', root => {
		deletePath(root, [...documentPath(), 'pages', state.pageIndex]);
		state.pageIndex = Math.max(0, state.pageIndex - 1);
		state.selectedIndex = -1;
	});
}

// ---- 图层面板 -----------------------------------------------------------------------------------

function renderLayers() {
	const box = $('layers');
	const actions = $('layerActions');
	box.textContent = '';
	actions.textContent = '';
	const list = rawElements();
	if (!state.root) {
		box.className = 'muted';
		box.textContent = '（还没打开文件）';
		return;
	}
	if (!state.selection || state.selection.kind !== 'face' || list === null) {
		box.className = 'muted';
		box.textContent = '（内置翻译的水牌没有元素表）';
		return;
	}
	box.className = '';
	if (list.length === 0) {
		box.textContent = '（这一页还没有元素）';
	}
	list.forEach((element, index) => {
		const row = tag('div', 'layerItem' + (index === state.selectedIndex ? ' selected' : ''));
		row.dataset.index = String(index);
		row.title = '数组下标 ' + index + '（从下往上画：下标大的盖住小的）\n' + safeJson(element);
		row.appendChild(tag('span', 'muted', (index + 1) + '. '));
		row.appendChild(tag('span', null, elementSummary(element)));
		row.addEventListener('click', () => selectElement(index));
		box.appendChild(row);
	});
	if (state.selection.kind === 'face') {
		actions.appendChild(miniButton('↑ 上移', () => moveSelected(-1), '和它上面那个元素换位置（画得晚 = 盖住）'));
		actions.appendChild(miniButton('↓ 下移', () => moveSelected(1)));
		actions.appendChild(miniButton('置顶', () => moveSelected(Number.MAX_SAFE_INTEGER)));
		actions.appendChild(miniButton('置底', () => moveSelected(-Number.MAX_SAFE_INTEGER)));
		actions.appendChild(miniButton('复制', duplicateSelected));
		actions.appendChild(miniButton('删除', deleteSelected));
	}
}

function selectElement(index) {
	state.selectedIndex = index;
	state.sourceEditor = null;
	renderLayers();
	renderProperties();
	drawOverlay();
	setStatus('选中第 ' + (index + 1) + ' 个元素：' + elementSummary(rawElements()[index]));
}

function moveSelected(offset) {
	if (!editable()) {
		return;
	}
	const list = rawElements();
	const from = state.selectedIndex;
	if (list === null || from < 0 || from >= list.length) {
		setStatus('先在图层里选一个元素');
		return;
	}
	const to = offset === Number.MAX_SAFE_INTEGER ? list.length - 1 : (offset === -Number.MAX_SAFE_INTEGER ? 0 : from + offset);
	const summary = elementSummary(list[from]);
	commit((offset < 0 ? '上移/置底 ' : '下移/置顶 ') + summary, root => {
		const target = getPath(root, elementsPath());
		moveItem(target, state.selectedIndex, to);
		state.selectedIndex = Math.max(0, Math.min(target.length - 1, to));
	});
}

function duplicateSelected() {
	if (!editable()) {
		return;
	}
	const list = rawElements();
	const index = state.selectedIndex;
	if (list === null || index < 0 || index >= list.length) {
		setStatus('先在图层里选一个元素');
		return;
	}
	const copy = cloneWithOffset(list[index], 0.02);
	commit('复制 ' + elementSummary(list[index]), root => {
		const target = getPath(root, elementsPath());
		target.splice(index + 1, 0, copy);
		state.selectedIndex = index + 1;
	});
}

function deleteSelected() {
	if (!editable()) {
		return;
	}
	const list = rawElements();
	const index = state.selectedIndex;
	if (list === null || index < 0 || index >= list.length) {
		setStatus('先在图层里选一个元素');
		return;
	}
	const summary = elementSummary(list[index]);
	commit('删除 ' + summary, root => {
		deletePath(root, elementPath(index));
		state.selectedIndex = -1;
	});
}

function addElementFromMenu() {
	if (!editable()) {
		return;
	}
	const type = $('newType').value;
	const element = newElement(type, { align: 'center' });
	const unsupported = Array.isArray(state.unsupportedTypes) && state.unsupportedTypes.includes(type);
	commit('加一个 ' + type + ' 元素', root => {
		let target = getPath(root, elementsPath());
		if (!Array.isArray(target)) {
			setPath(root, elementsPath(), []);
			target = getPath(root, elementsPath());
		}
		target.push(element);
		state.selectedIndex = target.length - 1;
	});
	if (unsupported) {
		setBanner('「' + type + '」在 schema.json 里有，但 painter.mjs 还没实现（属 F3）—— 文档里写得出来，工作室现在画不出来（画布上会报"不认识的元素类型"）。', false);
	}
}

// ---- 数据面板 -----------------------------------------------------------------------------------

function renderFieldInputs() {
	const box = $('fields');
	box.textContent = '';
	for (const spec of state.fieldSpecs) {
		const row = document.createElement('div');
		row.className = 'fieldRow';
		row.dataset.field = spec.name;

		const label = document.createElement('label');
		label.textContent = spec.name;
		label.title = (spec.type === 'num' ? '数字' : spec.type === 'bool' ? '真假' : '文本') + (spec.doc ? '：' + spec.doc : '');
		row.appendChild(label);

		let input;
		if (spec.type === 'bool') {
			input = document.createElement('select');
			for (const [value, text] of [['', '（缺）'], ['true', 'true'], ['false', 'false']]) {
				const option = document.createElement('option');
				option.value = value;
				option.textContent = text;
				input.appendChild(option);
			}
		} else {
			input = document.createElement('input');
			input.type = 'text';
			input.spellcheck = false;
			input.placeholder = '（缺）';
		}
		input.dataset.field = spec.name;
		input.addEventListener('input', onFieldInput);
		input.addEventListener('change', onFieldInput);
		row.appendChild(input);
		box.appendChild(row);
	}
	syncInputsFromRaw();
}

function onFieldInput(event) {
	const input = event.target;
	const name = input.dataset.field;
	if (input.tagName === 'SELECT') {
		// 真假量在 raw 里就存**真的真假值**：Java 的 coerce(BOOL, "false") 会得到 true
		// （字符串当不了数字 ⇒ Boolean.TRUE），所以这里不能把 'false' 当字符串传下去。
		if (input.value === '') {
			delete state.raw[name];
		} else {
			state.raw[name] = input.value === 'true';
		}
	} else if (input.value === '') {
		delete state.raw[name];
	} else {
		state.raw[name] = input.value;
	}
	recompute();
}

function syncInputsFromRaw() {
	for (const row of document.querySelectorAll('.fieldRow')) {
		const name = row.dataset.field;
		const input = row.querySelector('input, select');
		if (!input) {
			continue;
		}
		const value = state.raw[name];
		input.value = value === undefined || value === null ? '' : String(value);
	}
}

/** 一个预设 → 输入框里的原始值（空 = 没有这个字段，与 MmtrFaceData 同一条口径）。 */
function applyPreset(name) {
	state.raw = {};
	const flat = preset(name);
	for (const [key, value] of Object.entries(flat)) {
		if (value === '' || value === null || value === undefined) {
			continue;
		}
		// 布尔值原样留着（别转成字符串：coerce(BOOL, "false") 会得到 true，见 onFieldInput）
		state.raw[key] = value;
	}
	for (const button of document.querySelectorAll('button.preset')) {
		button.classList.toggle('on', button.dataset.preset === name);
	}
	syncInputsFromRaw();
	recompute();
}

/** 把输入框里的字按 fields.json 的类型收口 → 嵌层 → 算派生量（= MmtrFaceData.of(source) 干的事）。 */
function recompute() {
	const dropped = [];
	state.data = buildData(state.raw, name => state.fieldTypes[name] || 'str');
	for (const [name, rawValue] of Object.entries(state.raw)) {
		if (rawValue === '' || rawValue === undefined) {
			continue;
		}
		if (lookup(name, state.data) === null) {
			dropped.push('字段「' + name + '」写的「' + rawValue + '」不是' + typeLabel(state.fieldTypes[name]) + '，按"没有这个字段"处理了');
		}
	}
	state.droppedValues = dropped;
	for (const row of document.querySelectorAll('.fieldRow')) {
		const name = row.dataset.field;
		row.classList.toggle('hasValue', lookup(name, state.data) !== null);
	}
	$('describe').textContent = describe(state.data) || '（没有任何字段有值）';
	renderAll();
}

function typeLabel(type) {
	return type === 'num' ? '数字' : (type === 'bool' ? '真假值' : '文本');
}

// ---- 画与报 -------------------------------------------------------------------------------------

function renderAll() {
	if (!state.doc) {
		drawOverlay();
		return;
	}
	const doc = state.doc;
	const problems = [];

	let isVisible = null;
	let visibleError = null;
	try {
		isVisible = visible(doc, state.data);
	} catch (e) {
		visibleError = e && e.message ? e.message : String(e);
	}
	let drawnTexts = [];
	let textsError = null;
	try {
		// "会画出来的字"按**引擎视角**算（选了哪一页、有没有 blink 灭着，都以引擎为准）
		drawnTexts = texts(state.engineDoc || doc, state.data, 0);
	} catch (e) {
		textsError = e && e.message ? e.message : String(e);
	}

	const docPpm = doc.pxPerMetre > 0 ? doc.pxPerMetre : DEFAULT_PX_PER_METRE;
	const longestM = Math.max(state.widthM, state.heightM);
	let ppm = docPpm;
	let ppmNote = doc.pxPerMetre > 0 ? '文档 pxPerMetre=' + doc.pxPerMetre : '文档没写 pxPerMetre，用缺省 ' + DEFAULT_PX_PER_METRE;
	if (longestM * ppm > MAX_CANVAS_PIXELS) {
		ppm = Math.max(64, Math.floor(MAX_CANVAS_PIXELS / longestM));
		ppmNote += '；牌太长，画布按 ' + ppm + ' px/m 栅格化（只为看得清，不影响版式）';
	}

	let report = { widthPx: 0, heightPx: 0, painted: 0, whenFalse: 0, blank: 0, problems: [] };
	try {
		// timeMs = 0：工作室画静止的第一帧（动画/轮转只在 faceInfo 里说明，不在这里跑时钟）
		report = paint($('canvas').getContext('2d'), doc, state.data, state.widthM, state.heightM, ppm, 0);
	} catch (e) {
		problems.push('画的时候抛异常：' + (e && e.message ? e.message : e));
	}
	problems.push(...report.problems);

	// 静态检查：字段名 / 算子 / 过滤器（游戏里这几类是"静默跳过"，只能静态抓）
	problems.push(...staticChecks(doc));
	problems.push(...(state.droppedValues || []));

	renderFaceInfo(doc, { isVisible, visibleError, drawnTexts, textsError, ppm, ppmNote, report });
	renderProblems(problems);
	drawOverlay();
}

function renderFaceInfo(doc, info) {
	const box = $('faceInfo');
	box.textContent = '';
	const line = (label, value, className) => {
		const div = document.createElement('div');
		const tag_ = document.createElement('span');
		tag_.className = 'tag';
		tag_.textContent = label + ' ';
		div.appendChild(tag_);
		const content = document.createElement('span');
		if (className) {
			content.className = className;
		}
		content.textContent = value;
		div.appendChild(content);
		box.appendChild(div);
	};

	const pages = pagesArray();
	const pageCount = Math.max(1, enginePageCount());
	const pageNote = pages === null
		? 'v1（顶层 elements）'
		: ('你在编辑第 ' + (state.pageIndex + 1) + '/' + pages.length + ' 页' + (pageNameOf(pages[state.pageIndex]) ? '「' + pageNameOf(pages[state.pageIndex]) + '」' : ''));
	line('文档', (doc.builtin ? '内置翻译 ' : 'faces.') + (doc.name || doc.id) + ' · ' + pageNote
		+ ' · 侧 ' + doc.side + ' · 底色 ' + (doc.background === 0 ? '透明（不铺底）' : colorToHex(doc.background))
		+ ' · 文字色 ' + colorToHex(doc.textColor));
	line('牌面', shortNumber(state.widthM) + '×' + shortNumber(state.heightM) + ' m（' + state.sizeNote + '）'
		+ ' · 画布 ' + info.report.widthPx + '×' + info.report.heightPx + ' px（' + info.ppmNote + '）');
	if (pageCount > 1) {
		const engineIndex = enginePageIndex();
		const engineName = pageNameOf(allPagesRaw()[engineIndex]);
		const same = engineIndex === state.pageIndex;
		line('引擎此刻选哪一页', '第 ' + (engineIndex + 1) + '/' + pageCount + ' 页' + (engineName ? '「' + engineName + '」' : '')
			+ (same ? ' —— 就是你正在编辑的这一页' : ' —— **不是**你在编辑的那一页（pageExpr/pageSeconds 说了算）'),
		same ? null : 'warn');
		if (!editingPageRequireHolds()) {
			line('这一页的 require', '不成立 —— 游戏里这一页不画（画布仍画出来给你看版式）', 'bad');
		}
	}
	if (state.engineDoc && typeof faceDoc.animated === 'function' && faceDoc.animated(state.engineDoc)) {
		const fpsValue = typeof faceDoc.fps === 'function' ? faceDoc.fps(state.engineDoc) : 8;
		line('动画', '这份文档有随时间变的东西（动画/自动轮转）：游戏里按 ' + fpsValue + ' fps 重画；'
			+ '工作室画的是 timeMs=0 那一帧（静止画面）。');
	}

	if (info.visibleError !== null) {
		line('require', '求值失败：' + info.visibleError, 'bad');
	} else if (info.isVisible) {
		line('require', '成立 —— 游戏里这块面会画');
	} else {
		line('require', '不成立 —— 游戏里这块面不画（工作室仍画出来给你看版式）', 'bad');
	}

	if (info.textsError !== null) {
		line('会画出来的字', '取不到（' + info.textsError + '）', 'bad');
	} else {
		line('会画出来的字', info.drawnTexts.length === 0 ? '（没有）' : info.drawnTexts.join(' / '));
	}
	const parts = ['画了 ' + info.report.painted + ' 个'];
	if (info.report.whenFalse > 0) {
		parts.push('条件不成立 ' + info.report.whenFalse + ' 个');
	}
	if (info.report.blank > 0) {
		parts.push('取不到内容的文本 ' + info.report.blank + ' 个（这一行不画）');
	}
	if (info.report.problems.length > 0) {
		parts.push('有问题 ' + info.report.problems.length + ' 个（见下）');
	}
	const raw = rawElements();
	line('元素', (raw ? collectRaw(raw, []).length : editingPageElements().length) + ' 个（当前页）：' + parts.join('、'));
}

function renderProblems(problems) {
	const box = $('problems');
	box.textContent = '';
	const unique = Array.from(new Set(problems));
	if (unique.length === 0) {
		const div = document.createElement('div');
		div.className = 'good';
		div.textContent = '没有问题：字段名、算子、元素类型、画法都过。';
		box.appendChild(div);
		return;
	}
	for (const problem of unique) {
		const div = document.createElement('div');
		div.textContent = '· ' + problem;
		box.appendChild(div);
	}
}

/**
 * 静态检查（不画也能查的那几类）：
 *   · 模板里的字段路径与过滤器；· 表达式里的 var 路径；· 表达式里的算子名。
 * 判据与 tools/anchor-check/verify_face.js 的 P3/P5/P7 一致 —— 这几类在游戏里都是静默跳过。
 */
function staticChecks(doc) {
	const problems = [];
	const knownFields = new Set(state.fieldSpecs.map(spec => spec.name));
	const varNames = new Set(doc.vars ? Object.keys(doc.vars) : []);
	const paths = [];
	const filters = new Set();

	// 检查的是"你在编辑的这一页"（写了 pages 时顶层 elements 被引擎忽略，检查它只会误导作者）
	const pageElements = flattenElements(editingPageElements());

	for (const element of pageElements) {
		if (element.type === 'text') {
			const parts = templateParts(element.text);
			paths.push(...parts.paths);
			for (const filter of parts.filters) {
				filters.add(filter);
			}
		}
	}

	const expressions = [];
	if (doc.requirePresent) {
		expressions.push(doc.require);
	}
	if (doc.vars) {
		for (const value of Object.values(doc.vars)) {
			expressions.push(value);
		}
	}
	// 页自己的 require / pageExpr 也是表达式，同样要过一遍字段名与算子
	const pages = pagesArray();
	if (pages !== null && isPlainObject(pages[state.pageIndex]) && Object.prototype.hasOwnProperty.call(pages[state.pageIndex], 'require')) {
		expressions.push(pages[state.pageIndex].require);
	}
	if (isPlainObject(faceObject()) && Object.prototype.hasOwnProperty.call(faceObject(), 'pageExpr')) {
		expressions.push(faceObject().pageExpr);
	}
	for (const element of pageElements) {
		if (element.whenPresent) {
			expressions.push(element.when);
		}
		if (Object.prototype.hasOwnProperty.call(element.raw, 'needle')) {
			expressions.push(element.raw.needle);
		}
		if (Object.prototype.hasOwnProperty.call(element.raw, 'of')) {
			expressions.push(element.raw.of);
		}
	}
	for (const expression of expressions) {
		collectExpressionPaths(expression, paths);
		collectOperators(expression, problems);
	}
	const rawCount = countRawElements();
	if (rawCount !== null && rawCount !== pageElements.length) {
		problems.push('这一页有 ' + rawCount + ' 项，但引擎只认 ' + pageElements.length + ' 项 —— '
			+ '被丢掉的多半是少了 type 或类型不认识（游戏里静默跳过，verify_face.js 的 P4 也查这个）。');
	}
	if (topLevelElementsIgnored()) {
		problems.push('文档同时写了 pages 与顶层 elements —— **顶层 elements 被忽略**（引擎口径）：'
			+ '你以为在改的那一份可能根本没画。要留哪一份自己删掉，工作室不替你决定。');
	}
	const flat = [];
	collectRaw(pageElements, flat);
	for (let index = 0; index < flat.length; index++) {
		geometryChecks(flat[index], index + 1, problems);
	}

	for (const path of new Set(paths)) {
		if (!pathIsKnown(path, knownFields, varNames)) {
			problems.push('字段名「' + path + '」不在 fields.json 里，也不在本文档的 vars 里 —— 游戏里会取到 null（模板渲染成空串，条件当假）');
		}
	}
	for (const filter of filters) {
		if (!FILTERS.includes(filter)) {
			problems.push('模板过滤器「' + filter + '」不在 MmtrFaceText 的过滤器表里（' + FILTERS.join(' / ') + '）—— 原样返回，字不会按你想的格式化');
		}
	}
	return problems;
}

/** 把 forEach 的子元素也算进来（解析后的元素带 children）。 */
function flattenElements(elements) {
	const out = [];
	const walk = list => {
		for (const element of Array.isArray(list) ? list : []) {
			out.push(element);
			if (Array.isArray(element.children)) {
				walk(element.children);
			}
		}
	};
	walk(elements);
	return out;
}

/** 把原始 JSON 元素也摊平（几何检查要落到每一项上）。 */
function collectRaw(elements, out) {
	for (const element of Array.isArray(elements) ? elements : []) {
		if (isPlainObject(element)) {
			out.push(element);
			if (Array.isArray(element.elements)) {
				collectRaw(element.elements, out);
			}
			if (Array.isArray(element.children)) {
				collectRaw(element.children, out);
			}
		}
	}
	return out;
}

/** 当前页原始 JSON 里的元素总数（含 forEach 的子元素）。 */
function countRawElements() {
	const list = rawElements();
	if (list === null) {
		return null;
	}
	return collectRaw(list, []).length;
}

/** 模板里用到的东西：字段路径 + 过滤器名（与 text.mjs 的扫描同一套转义口径）。 */
function templateParts(text) {
	const paths = [];
	const filters = [];
	const source = text === null || text === undefined ? '' : String(text);
	let index = 0;
	while (index < source.length) {
		const character = source[index];
		if (character === '{') {
			if (source.startsWith('{{', index)) {
				index += 2;
				continue;
			}
			const close = source.indexOf('}', index + 1);
			if (close < 0) {
				break;
			}
			const placeholder = source.substring(index + 1, close).split('|');
			const path = placeholder[0].trim();
			if (path !== '') {
				paths.push(path);
			}
			for (let i = 1; i < placeholder.length; i++) {
				const filter = placeholder[i].trim();
				if (filter !== '') {
					filters.push(filter.split(':')[0].toLowerCase());
				}
			}
			index = close + 1;
		} else if (character === '}' && source.startsWith('}}', index)) {
			index += 2;
		} else {
			index++;
		}
	}
	return { paths, filters };
}

/** 表达式里的 var 路径（missing / missing_some 的字符串字面量也是字段名）。 */
function collectExpressionPaths(node, out) {
	if (node === null || typeof node !== 'object') {
		return;
	}
	if (Array.isArray(node)) {
		for (const item of node) {
			collectExpressionPaths(item, out);
		}
		return;
	}
	for (const [operator, argument] of Object.entries(node)) {
		if (operator === 'var') {
			const value = Array.isArray(argument) ? argument[0] : argument;
			if (typeof value === 'string' && value !== '') {
				out.push(value);
			}
		} else if (operator === 'missing' || operator === 'missing_some') {
			const items = Array.isArray(argument) ? argument : [argument];
			const from = operator === 'missing_some' ? 1 : 0;
			for (let i = from; i < items.length; i++) {
				if (typeof items[i] === 'string' && items[i] !== '') {
					out.push(items[i]);
				}
			}
		}
		collectExpressionPaths(argument, out);
	}
}

/** 表达式里的算子名必须都在子集里（JSONLogic 里一个对象就是一次算子调用）。 */
function collectOperators(node, problems) {
	if (node === null || typeof node !== 'object') {
		return;
	}
	if (Array.isArray(node)) {
		for (const item of node) {
			collectOperators(item, problems);
		}
		return;
	}
	for (const [operator, argument] of Object.entries(node)) {
		if (operator.startsWith('fn:')) {
			// 附属模组注册的自定义算子：静态检查管不了（与 verify_face.js 的处理一致，只当提示）
			continue;
		}
		if (!OPERATORS.includes(operator)) {
			problems.push('表达式里用了不认识的算子「' + operator + '」—— 游戏里这个元素会被跳过（认得的有 ' + OPERATORS.join(' ') + '）');
		}
		collectOperators(argument, problems);
	}
}

/**
 * 几何范围检查（判据对齐 verify_face.js 的 P6，但只说**真会出问题**的那几条）：
 * rect/roundRect 没有 w/h、line 没有 x2/y2 ⇒ 画不出来；x/y/w/h、size 越界 ⇒ 会被钳回。
 */
function geometryChecks(element, index, problems) {
	const where = '第 ' + index + ' 个元素（' + element.type + '）';
	if (element.type === 'rect' || element.type === 'roundrect') {
		if (!(element.w > 0) || !(element.h > 0)) {
			problems.push(where + '：w/h 缺失或为 0 —— 这个元素画不出来（离线自检 P6 也会报）');
		}
	}
	if (element.type === 'line' && (element.raw.x2 === undefined || element.raw.y2 === undefined)) {
		problems.push(where + '：少了 x2/y2 —— 线退化成一个点（离线自检 P6 也会报）');
	}
	for (const key of ['x', 'y', 'w', 'h']) {
		const value = element.raw[key];
		if (typeof value === 'number' && (value < 0 || value > 1)) {
			problems.push(where + '：' + key + '=' + value + ' 不在 0..1（画的时候会被钳回）');
		}
	}
	if (typeof element.raw.size === 'number' && (element.raw.size < 0.02 || element.raw.size > 1.5)) {
		problems.push(where + '：size=' + element.raw.size + ' 不在 0.02..1.5（画的时候会被钳回）');
	}
}

// ---- 覆盖层（网格 / 标尺 / 选中框 / 吸附提示） ----------------------------------------------------

function drawOverlay() {
	const overlay = $('overlay');
	const canvas = $('canvas');
	const width = canvas.width;
	const height = canvas.height;
	if (overlay.width !== width) {
		overlay.width = width;
	}
	if (overlay.height !== height) {
		overlay.height = height;
	}
	const ctx = overlay.getContext('2d');
	if (!ctx) {
		return;
	}
	ctx.setTransform(1, 0, 0, 1, 0, 0);
	ctx.clearRect(0, 0, width, height);
	if (state.grid) {
		drawGrid(ctx, width, height);
	}
	if (state.ruler) {
		drawRuler(ctx, width, height);
	}
	drawSnapGuides(ctx, width, height);
	drawSelection(ctx, width, height);
}

function drawGrid(ctx, width, height) {
	for (let i = 0; i <= 20; i++) {
		const x = i / 20;
		const px = Math.round(proportionToPxX(x, width)) + 0.5;
		const coarse = i % 5 === 0;
		ctx.strokeStyle = coarse ? 'rgba(255,255,255,0.18)' : 'rgba(255,255,255,0.07)';
		ctx.lineWidth = coarse ? 1 : 1;
		ctx.beginPath();
		ctx.moveTo(px, 0);
		ctx.lineTo(px, height);
		ctx.stroke();
	}
	for (let j = 0; j <= 20; j++) {
		const y = j / 20;
		const py = Math.round(proportionToPxY(y, height)) + 0.5;
		const coarse = j % 5 === 0;
		ctx.strokeStyle = coarse ? 'rgba(255,255,255,0.18)' : 'rgba(255,255,255,0.07)';
		ctx.beginPath();
		ctx.moveTo(0, py);
		ctx.lineTo(width, py);
		ctx.stroke();
	}
	// 中线单独挑出来（吸附的主力目标）
	ctx.strokeStyle = 'rgba(255,179,0,0.28)';
	ctx.beginPath();
	ctx.moveTo(Math.round(width / 2) + 0.5, 0);
	ctx.lineTo(Math.round(width / 2) + 0.5, height);
	ctx.moveTo(0, Math.round(height / 2) + 0.5);
	ctx.lineTo(width, Math.round(height / 2) + 0.5);
	ctx.stroke();
}

function drawRuler(ctx, width, height) {
	// 底边与左边的刻度（每 0.1 比例一格）
	ctx.strokeStyle = 'rgba(230,233,238,0.45)';
	ctx.fillStyle = 'rgba(230,233,238,0.75)';
	ctx.font = '11px Consolas, monospace';
	ctx.lineWidth = 1;
	for (let i = 0; i <= 10; i++) {
		const x = Math.round(proportionToPxX(i / 10, width)) + 0.5;
		ctx.beginPath();
		ctx.moveTo(x, height - (i % 5 === 0 ? 9 : 5));
		ctx.lineTo(x, height - 0.5);
		ctx.stroke();
		const y = Math.round(proportionToPxY(i / 10, height)) + 0.5;
		ctx.beginPath();
		ctx.moveTo(0, y);
		ctx.lineTo(i % 5 === 0 ? 9 : 5, y);
		ctx.stroke();
	}
	const ppm = state.widthM > 0 ? width / state.widthM : 0;
	const label = shortNumber(state.widthM) + '×' + shortNumber(state.heightM) + ' m · ' + Math.round(ppm) + ' px/m'
		+ (state.cursor ? ' · 光标 ' + shortNumber(state.cursor.x) + ', ' + shortNumber(state.cursor.y) : '');
	const textWidth = ctx.measureText(label).width;
	ctx.fillStyle = 'rgba(16,18,20,0.72)';
	ctx.fillRect(3, 3, textWidth + 8, 16);
	ctx.fillStyle = 'rgba(230,233,238,0.9)';
	ctx.fillText(label, 7, 15);
}

function drawSnapGuides(ctx, width, height) {
	if (!Array.isArray(state.snapGuides) || state.snapGuides.length === 0) {
		return;
	}
	ctx.save();
	ctx.strokeStyle = 'rgba(255,179,0,0.95)';
	ctx.lineWidth = 1;
	for (const hit of state.snapGuides) {
		ctx.beginPath();
		if (hit.axis === 'x') {
			const px = Math.round(proportionToPxX(hit.target, width)) + 0.5;
			ctx.moveTo(px, 0);
			ctx.lineTo(px, height);
		} else {
			const py = Math.round(proportionToPxY(hit.target, height)) + 0.5;
			ctx.moveTo(0, py);
			ctx.lineTo(width, py);
		}
		ctx.stroke();
	}
	const first = state.snapGuides[0];
	ctx.font = '12px Consolas, monospace';
	const text = snapLabel(first);
	const px = first.axis === 'x' ? proportionToPxX(first.target, width) : 8;
	const py = first.axis === 'y' ? proportionToPxY(first.target, height) : 20;
	ctx.fillStyle = 'rgba(16,18,20,0.8)';
	ctx.fillRect(Math.min(px + 4, width - 90), Math.max(py - 16, 2), 86, 16);
	ctx.fillStyle = 'rgba(255,179,0,1)';
	ctx.fillText(text, Math.min(px + 8, width - 86), Math.max(py - 4, 14));
	ctx.restore();
}

function currentBox() {
	const list = rawElements();
	if (!Array.isArray(list) || state.selectedIndex < 0 || state.selectedIndex >= list.length) {
		return null;
	}
	return elementBox(list[state.selectedIndex], { aspect: aspect() });
}

function drawSelection(ctx, width, height) {
	const box = currentBox();
	if (box === null) {
		return;
	}
	const left = proportionToPxX(box.x0, width);
	const right = proportionToPxX(box.x1, width);
	const top = proportionToPxY(box.y1, height);
	const bottom = proportionToPxY(box.y0, height);

	ctx.save();
	ctx.strokeStyle = 'rgba(255,179,0,0.95)';
	ctx.lineWidth = 1;
	ctx.setLineDash([5, 3]);
	ctx.strokeRect(Math.round(left) + 0.5, Math.round(top) + 0.5, Math.round(right - left), Math.round(bottom - top));
	ctx.setLineDash([]);
	// 8 个把手
	const points = {
		nw: [left, top], n: [(left + right) / 2, top], ne: [right, top], e: [right, (top + bottom) / 2],
		se: [right, bottom], s: [(left + right) / 2, bottom], sw: [left, bottom], w: [left, (top + bottom) / 2],
	};
	ctx.fillStyle = '#ffb300';
	ctx.strokeStyle = '#101214';
	for (const [hx, hy] of Object.values(points)) {
		ctx.fillRect(Math.round(hx) - 3, Math.round(hy) - 3, 6, 6);
		ctx.strokeRect(Math.round(hx) - 3.5, Math.round(hy) - 3.5, 7, 7);
	}
	// 锚点本身（教学用：矩形是左下角、圆是圆心、文本是那个点）
	const list = rawElements();
	const element = list[state.selectedIndex];
	const ax = proportionToPxX(readNumber(element, 'x', 0.5), width);
	const ay = proportionToPxY(readNumber(element, 'y', 0.5), height);
	ctx.strokeStyle = '#6bcf7f';
	ctx.lineWidth = 1.5;
	ctx.beginPath();
	ctx.moveTo(ax - 6, ay);
	ctx.lineTo(ax + 6, ay);
	ctx.moveTo(ax, ay - 6);
	ctx.lineTo(ax, ay + 6);
	ctx.stroke();
	ctx.restore();
}

// ---- 画布交互（拖 / 缩放 / 吸附 / 复制） ---------------------------------------------------------

function aspect() {
	return state.heightM > 0 ? state.widthM / state.heightM : 1;
}

function boxOptions() {
	return { aspect: aspect() };
}

function overlayPoint(event) {
	const rect = $('overlay').getBoundingClientRect();
	if (rect.width <= 0 || rect.height <= 0) {
		return { x: 0, y: 0 };
	}
	return {
		x: pxToProportionX(event.clientX - rect.left, rect.width),
		y: pxToProportionY(event.clientY - rect.top, rect.height),
	};
}

/** 把比例位移换成"画布上的手感"：小牌上用像素阈值判把手，免得把手永远点不中。 */
function handleRadius() {
	const widthPx = $('canvas').width || 1;
	return Math.max(0.01, 7 / widthPx);
}

function bindCanvas() {
	const overlay = $('overlay');
	overlay.addEventListener('pointerdown', onPointerDown);
	overlay.addEventListener('pointermove', onPointerMove);
	overlay.addEventListener('pointerup', onPointerUp);
	overlay.addEventListener('pointercancel', onPointerUp);
	overlay.addEventListener('pointerleave', () => {
		state.cursor = null;
		if (state.ruler) {
			drawOverlay();
		}
	});
}

let drag = null;

/** 指针捕获失败不该让拖拽半途死掉（合成事件、老浏览器都可能没有合法的 pointerId）。 */
function capturePointer(element, pointerId) {
	try {
		element.setPointerCapture(pointerId);
	} catch (e) {
		// 忽略：没有捕获也一样能拖，只是指针离开画布时会丢
	}
}

function onPointerDown(event) {
	if (!editable()) {
		return;
	}
	const list = rawElements();
	if (!Array.isArray(list) || list.length === 0) {
		setStatus('这一页没有元素：先用工具栏的「加一个元素」。');
		return;
	}
	const point = overlayPoint(event);
	const box = currentBox();
	if (box !== null) {
		const handle = handleAtPoint(box, point, handleRadius());
		if (handle !== null) {
			drag = { mode: 'resize', index: state.selectedIndex, handle, start: point, original: cloneJson(list[state.selectedIndex]), before: cloneJson(state.root), moved: false };
			capturePointer($('overlay'), event.pointerId);
			setStatus('缩放：拖把手 ' + handle + '（Shift 不参与，Ctrl 关吸附）');
			return;
		}
	}
	const hit = pickTopmost(list, point, boxOptions());
	if (hit < 0) {
		state.selectedIndex = -1;
		renderLayers();
		renderProperties();
		drawOverlay();
		setStatus('点空了 —— 现在是"没选中"状态');
		return;
	}
	const before = cloneJson(state.root);
	let index = hit;
	let label = null;
	if (event.altKey) {
		// Alt+拖 = 复制出一个副本，然后拖的是副本（复制 + 移动合成**一步**撤销）
		const copy = cloneWithOffset(list[hit], 0.02);
		const target = getPath(state.root, elementsPath());
		target.push(copy);
		index = target.length - 1;
		label = '复制并移动 ' + elementSummary(copy);
	} else {
		label = '移动 ' + elementSummary(list[hit]);
	}
	state.selectedIndex = index;
	renderLayers();
	renderProperties();
	drag = {
		mode: 'move',
		index,
		start: point,
		original: cloneJson(getPath(state.root, elementPath(index))),
		before,
		label,
		moved: false,
	};
	capturePointer($('overlay'), event.pointerId);
	setStatus(label);
}

function onPointerMove(event) {
	const point = overlayPoint(event);
	state.cursor = { x: Math.max(0, Math.min(1, point.x)), y: Math.max(0, Math.min(1, point.y)) };
	if (drag === null) {
		if (state.ruler) {
			drawOverlay();
		}
		return;
	}
	const list = rawElements();
	if (!Array.isArray(list) || drag.index < 0 || drag.index >= list.length) {
		return;
	}
	const dx = point.x - drag.start.x;
	const dy = point.y - drag.start.y;
	if (drag.mode === 'move') {
		const constrained = constrainDrag(dx, dy, event.shiftKey);
		const moved = cloneJson(drag.original);
		moved.x = clampProportion(readNumber(drag.original, 'x', 0.5) + constrained.dx, 0.5);
		moved.y = clampProportion(readNumber(drag.original, 'y', 0.5) + constrained.dy, 0.5);
		if (String(moved.type).toLowerCase() === 'line') {
			if (Object.prototype.hasOwnProperty.call(drag.original, 'x2')) {
				moved.x2 = clampProportion(readNumber(drag.original, 'x2', 0.5) + constrained.dx, 0.5);
			}
			if (Object.prototype.hasOwnProperty.call(drag.original, 'y2')) {
				moved.y2 = clampProportion(readNumber(drag.original, 'y2', 0.5) + constrained.dy, 0.5);
			}
		}
		if (event.ctrlKey) {
			state.snapGuides = [];
		} else {
			const lines = snapLines(list, drag.index, boxOptions());
			const snap = snapBox(elementBox(moved, boxOptions()), lines.x, lines.y, SNAP_LIMIT);
			moved.x = clampProportion(moved.x + snap.dx, 0.5);
			moved.y = clampProportion(moved.y + snap.dy, 0.5);
			if (String(moved.type).toLowerCase() === 'line') {
				if (Object.prototype.hasOwnProperty.call(moved, 'x2')) {
					moved.x2 = clampProportion(moved.x2 + snap.dx, 0.5);
				}
				if (Object.prototype.hasOwnProperty.call(moved, 'y2')) {
					moved.y2 = clampProportion(moved.y2 + snap.dy, 0.5);
				}
			}
			state.snapGuides = snap.hits;
		}
		replaceElement(drag.index, moved);
		drag.moved = true;
		// 状态栏里的坐标读**改完之后**的那个（之前读的是拖拽开始前抓的引用，会慢一次事件）
		setStatus((drag.label || '移动') + ' → ' + shortNumber(readNumber(moved, 'x', 0.5)) + ', ' + shortNumber(readNumber(moved, 'y', 0.5))
			+ (state.snapGuides.length > 0 ? ' · ' + snapLabel(state.snapGuides[0]) : ''));
	} else if (drag.mode === 'resize') {
		applyResize(drag, dx, dy);
		drag.moved = true;
	}
	renderAll();
}

function onPointerUp(event) {
	if (drag === null) {
		return;
	}
	try {
		$('overlay').releasePointerCapture(event.pointerId);
	} catch (e) {
		// 指针已经没了：忽略
	}
	const finished = drag;
	drag = null;
	state.snapGuides = [];
	if (!finished.moved) {
		drawOverlay();
		return;
	}
	const finalRoot = cloneJson(state.root);
	const before = finished.before;
	if (sameJson(before, finalRoot)) {
		drawOverlay();
		setStatus('（没改动）');
		return;
	}
	// 回到拖之前，再走**同一个 commit 入口**把最终结果落下来 —— 一步拖 = 一步撤销
	state.root = before;
	commit(finished.label || '拖拽', root => replaceRoot(root, finalRoot));
}

/** 缩放：按元素"盒子的来源"改对应的键（见 index.html 里那段提示）。 */
function applyResize(activeDrag, dx, dy) {
	if (!isPlainObject(getPath(state.root, elementPath(activeDrag.index)))) {
		return;
	}
	const raw = getPath(state.root, elementPath(activeDrag.index));
	if (!isPlainObject(raw)) {
		return;
	}
	const type = String(raw.type || '').toLowerCase();
	const box = elementBox(raw, boxOptions());
	const box0 = elementBox(activeDrag.original, boxOptions());
	if (type === 'rect' || type === 'roundrect' || type === 'image') {
		const next = resizeBox(box0, activeDrag.handle, dx, dy);
		raw.x = next.x;
		raw.y = next.y;
		raw.w = next.w;
		raw.h = next.h;
	} else if (type === 'text') {
		// 文本没有 w/h 可拉：竖直把手改 size（字高），水平把手不做事（如实说）
		if (activeDrag.handle.includes('n') || activeDrag.handle.includes('s')) {
			const height = box0.y1 - box0.y0;
			const nextHeight = Math.max(0.02, height + (activeDrag.handle.includes('n') ? dy : -dy));
			raw.size = clampSize(nextHeight, 0.4);
		} else {
			setStatus('文本的宽度由 size 与 shrinkToFit 决定（水平把手不动它）—— 要改字高请拖上下把手，或直接改 size 属性。');
		}
	} else if (type === 'circle' || type === 'arc' || type === 'gauge') {
		// 半径乘的是**牌高**，所以水平方向要把像素位移换回"高度单位"（乘 aspect）
		const radius0 = readNumber(activeDrag.original, 'radius', (box0.y1 - box0.y0) / 2);
		const grow = activeDrag.handle.includes('n') ? dy
			: (activeDrag.handle.includes('s') ? -dy
				: (activeDrag.handle.includes('e') ? dx * aspect() : -dx * aspect()));
		raw.radius = Math.max(0.005, radius0 + grow);
	} else if (type === 'line') {
		const next2 = resizeBox(box0, activeDrag.handle, dx, dy);
		raw.x = next2.x;
		raw.y = next2.y;
		raw.x2 = next2.x + next2.w;
		raw.y2 = next2.y + next2.h;
	} else {
		const next = resizeBox(box0, activeDrag.handle, dx, dy);
		raw.x = next.x;
		raw.y = next.y;
		raw.w = next.w;
		raw.h = next.h;
	}
}

/** 拖动过程中直接改 JSON（不记撤销；松手时才由 commit 记一步）。 */
function replaceElement(index, element) {
	const target = getPath(state.root, elementsPath());
	if (!Array.isArray(target) || index < 0 || index >= target.length) {
		return;
	}
	target[index] = element;
	state.anchorText = JSON.stringify(state.root);
}

// ---- 键盘：撤销/重做/方向键微调 ------------------------------------------------------------------

function bindKeyboard() {
	document.addEventListener('keydown', event => {
		const target = event.target;
		if (target && /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName)) {
			return;
		}
		const modifier = event.ctrlKey || event.metaKey;
		const key = String(event.key || '').toLowerCase();
		if (modifier && key === 'z') {
			event.preventDefault();
			if (event.shiftKey) {
				redo();
			} else {
				undo();
			}
			return;
		}
		if (modifier && key === 'y') {
			event.preventDefault();
			redo();
			return;
		}
		if (modifier && key === 's') {
			event.preventDefault();
			saveCurrent();
			return;
		}
		const arrow = ARROW_KEYS[event.key];
		if (arrow) {
			event.preventDefault();
			nudgeSelected(arrow, event.shiftKey);
		}
	});
}

function nudgeSelected(arrow, shift) {
	if (!editable()) {
		return;
	}
	const list = rawElements();
	const index = state.selectedIndex;
	if (!Array.isArray(list) || index < 0 || index >= list.length) {
		setStatus('先用方向键微调要先选一个元素（点画布或图层）');
		return;
	}
	const step = nudgeStep(shift);
	const element = list[index];
	const nudge = nudgeElement(element, arrow.axis, arrow.direction, step);
	if (!nudge.changed) {
		setStatus('已经在牌边上了（' + nudge.key + ' = ' + shortNumber(nudge.after) + '）');
		return;
	}
	const summary = elementSummary(element);
	commit('移动 ' + summary.split(' ')[0] + ' 到 ' + nudge.key + '=' + shortNumber(nudge.after), root => {
		const raw = getPath(root, elementPath(index));
		const delta = nudge.after - nudge.before;
		raw[nudge.key] = nudge.after;
		if (String(raw.type).toLowerCase() === 'line') {
			const other = nudge.key === 'x' ? 'x2' : 'y2';
			if (Object.prototype.hasOwnProperty.call(raw, other)) {
				raw[other] = clampProportion(readNumber(raw, other, 0.5) + delta, 0.5);
			}
		}
	});
}

// ---- 属性面板（按 schema.json 生成） --------------------------------------------------------------

function renderProperties() {
	const box = $('props');
	box.textContent = '';
	box.className = '';
	if (!state.root) {
		box.className = 'muted';
		box.textContent = '（还没打开文件）';
		return;
	}
	if (!state.selection) {
		box.className = 'muted';
		box.textContent = '（先选一块面）';
		return;
	}
	if (state.selection.kind !== 'face') {
		box.appendChild(note('warn', '内置翻译的水牌（pid/next 段）是只读的：它由 document.mjs 的 builtinPid 翻出来，'
			+ '要改版式请给锚点写 faces 文档。'));
		return;
	}
	if (!state.schema) {
		box.appendChild(note('bad', state.schemaError || 'schema.json 读不到：属性面板是按键表生成的。'));
		box.appendChild(sourceRowFallback());
		return;
	}
	// ① 选中的元素（放最上面：一选中就能改，条件构造器也不用往下翻）
	renderElementBlock(box);
	// ② 页（写了 pages 才有）
	if (pagesArray() !== null) {
		renderPageBlock(box);
	}
	// ③ 文档
	renderDocumentBlock(box);
}

function sourceRowFallback() {
	const wrap = tag('div', 'propBlock');
	wrap.appendChild(note('muted', '没有键表时仍然可以整块编辑：'));
	const button = miniButton('用源码编辑 faces.' + state.faceName, () => openSource(documentPath(), 'faces.' + state.faceName));
	wrap.appendChild(button);
	return wrap;
}

function renderDocumentBlock(box) {
	const block = tag('div', 'propBlock');
	block.appendChild(tag('h3', null, '文档 faces.' + state.faceName + '（' + state.facesNote + '）'));
	const object = faceObject();
	if (!isPlainObject(object)) {
		block.appendChild(note('bad', 'faces.' + state.faceName + ' 不是对象 —— 解析会整块失败。'));
		box.appendChild(block);
		return;
	}
	for (const spec of sectionKeys('document')) {
		block.appendChild(buildPropRow(spec, documentPath(), 'document', object));
	}
	const known = sectionKeys('document').map(spec => spec.name);
	appendUnknownBlock(block, object, known, documentPath(), '文档');
	box.appendChild(block);
}

function renderPageBlock(box) {
	const block = tag('div', 'propBlock');
	block.appendChild(tag('h3', null, '第 ' + (state.pageIndex + 1) + ' 页 pages[' + state.pageIndex + ']'));
	const page = currentPageObject();
	if (!isPlainObject(page)) {
		block.appendChild(note('bad', '这一页不是对象。'));
		box.appendChild(block);
		return;
	}
	for (const spec of sectionKeys('page')) {
		block.appendChild(buildPropRow(spec, [...documentPath(), 'pages', state.pageIndex], 'page', page));
	}
	appendUnknownBlock(block, page, sectionKeys('page').map(spec => spec.name), [...documentPath(), 'pages', state.pageIndex], '页');
	box.appendChild(block);
}

function renderElementBlock(box) {
	const block = tag('div', 'propBlock');
	const list = rawElements();
	if (!Array.isArray(list) || state.selectedIndex < 0 || state.selectedIndex >= list.length) {
		block.appendChild(tag('h3', null, '元素'));
		block.appendChild(note('muted', '没选中元素：在牌面上点一下，或在左边"图层"里点一行。'));
		box.appendChild(block);
		return;
	}
	const element = list[state.selectedIndex];
	const type = String(element.type || '').toLowerCase();
	block.appendChild(tag('h3', null, '元素 #' + (state.selectedIndex + 1) + '：' + elementSummary(element)));
	if (!ELEMENT_TYPES.includes(type)) {
		block.appendChild(note('warn', '「' + type + '」不在 painter.mjs 认得的那 ' + ELEMENT_TYPES.length + ' 种里'
			+ '（' + ELEMENT_TYPES.join(' / ') + '）—— 游戏与工作室都画不出来，文档里留着只会被静默跳过。'));
	}
	if (type === 'image' && !String(element.src || '').trim()) {
		block.appendChild(note('warn', 'image 少写了 src（必填）—— 画不出来。'));
	}
	for (const spec of sectionKeys('common')) {
		// anim 不在这里画：它由下面的"动画块"按 kind 展开（kind 一换，键表也跟着换）
		if (spec.name === 'anim') {
			continue;
		}
		block.appendChild(buildPropRow(spec, elementPath(state.selectedIndex), 'common', element));
	}
	for (const spec of sectionKeys('element:' + type)) {
		block.appendChild(buildPropRow(spec, elementPath(state.selectedIndex), 'element:' + type, element));
	}
	if (isPlainObject(element.anim)) {
		block.appendChild(buildAnimBlock(element, elementPath(state.selectedIndex)));
	} else {
		const add = tag('div', 'propRow');
		add.appendChild(tag('label', null, 'anim'));
		const select = tag('select');
		for (const kind of (state.schema.animKinds || [])) {
			const option = tag('option', null, kind);
			option.value = kind;
			select.appendChild(option);
		}
		add.appendChild(select);
		add.appendChild(miniButton('加动画', () => {
			const kind = select.value || 'blink';
			commit('给元素加动画 ' + kind, root => {
				const raw = getPath(root, elementPath(state.selectedIndex));
				const defaults = {};
				for (const spec of sectionKeys('anim:' + kind)) {
					if (spec.name !== 'kind' && spec.default !== null) {
						defaults[spec.name] = spec.default;
					}
				}
				raw.anim = Object.assign({ kind }, defaults);
			});
		}));
		block.appendChild(add);
	}
	appendUnknownBlock(block, element, elementKnownNames(type), elementPath(state.selectedIndex), '元素');
	box.appendChild(block);
}

function buildAnimBlock(element, path) {
	const block = tag('div', 'propBlock');
	const kind = String(element.anim.kind || 'blink').toLowerCase();
	block.appendChild(tag('h3', null, '动画 anim（kind = ' + kind + '）'));
	const known = [];
	const kinds = state.schema.animKinds || [];
	const row = tag('div', 'propRow');
	row.appendChild(tag('label', null, 'kind'));
	const select = tag('select');
	for (const item of kinds) {
		const option = tag('option', null, item);
		option.value = item;
		option.selected = item === kind;
		select.appendChild(option);
	}
	row.appendChild(select);
	row.appendChild(miniButton('换 kind', () => {
		const next = select.value;
		commit('动画 kind 换成 ' + next, root => {
			const raw = getPath(root, [...path, 'anim']);
			const defaults = {};
			for (const spec of sectionKeys('anim:' + next)) {
				if (spec.name !== 'kind' && spec.default !== null) {
					defaults[spec.name] = spec.default;
				}
			}
			raw.kind = next;
			for (const spec of sectionKeys('anim:' + next)) {
				if (!Object.prototype.hasOwnProperty.call(raw, spec.name) && spec.name !== 'kind') {
					raw[spec.name] = spec.default;
				}
			}
			void defaults;
		});
	}));
	row.appendChild(miniButton('删掉动画', () => {
		commit('删掉动画', root => deletePath(root, [...path, 'anim']));
	}));
	block.appendChild(row);
	for (const spec of sectionKeys('anim:' + kind)) {
		block.appendChild(buildPropRow(spec, [...path, 'anim'], 'anim:' + kind, element.anim));
		known.push(spec.name);
	}
	appendUnknownBlock(block, element.anim, known, [...path, 'anim'], '动画');
	return block;
}

// ---- 属性行 -------------------------------------------------------------------------------------

/** 这个键在 JSON 里存在吗（= 作者显式写过；没写就是"用缺省"）。 */
function keyPresent(object, key) {
	return isPlainObject(object) && Object.prototype.hasOwnProperty.call(object, key);
}

function buildPropRow(spec, containerPath, sectionName, container) {
	const row = tag('div', 'propRow');
	row.dataset.key = sectionName + ':' + spec.name;
	const present = keyPresent(container, spec.name);
	if (present) {
		row.classList.add('hasValue');
	}
	const label = tag('label', null, spec.name);
	label.title = '类型 ' + spec.type + (spec.doc ? '：' + spec.doc : '') + '\n缺省 ' + showValue(defaultForKey(spec));
	row.appendChild(label);

	const control = buildControl(spec, containerPath, sectionName, container);
	row.appendChild(control);

	const defaults = tag('span', 'default', present ? '' : '缺省 ' + shortValue(defaultForKey(spec)));
	defaults.title = 'schema.json 里的缺省：' + showValue(defaultForKey(spec));
	row.appendChild(defaults);

	if (present) {
		row.appendChild(miniButton('重置', () => resetKey(containerPath, spec), '从键表里删掉这个键 = 回到 schema.json 的缺省'));
	}
	return row;
}

function resetKey(containerPath, spec) {
	commit('把 ' + spec.name + ' 重置为缺省（删键）', root => {
		deletePath(root, [...containerPath, spec.name]);
	});
}

function buildControl(spec, containerPath, sectionName, container) {
	const key = spec.name;
	const path = [...containerPath, key];
	const raw = container[key];
	switch (spec.type) {
		case 'number':
		case 'int':
			return buildNumberControl(spec, path, raw);
		case 'color':
			return buildColorControl(spec, path, raw);
		case 'logic':
			return buildLogicControl(spec, path, container, containerPath, sectionName);
		case 'template':
			return buildTemplateControl(spec, path, raw);
		case 'enum':
			return buildEnumControl(spec, path, raw);
		case 'elements':
		case 'pages':
			return buildContainerControl(spec, path, raw, '数组');
		case 'object':
			return buildObjectControl(spec, path, raw, container, containerPath);
		default:
			return buildTextControl(spec, path, raw);
	}
}

function writeKey(path, value, label) {
	commit(label, root => {
		if (value === undefined) {
			deletePath(root, path);
		} else {
			setPath(root, path, value);
		}
	});
}

function buildNumberControl(spec, path, raw) {
	const input = tag('input');
	input.type = 'number';
	input.step = spec.type === 'int' ? '1' : '0.01';
	input.spellcheck = false;
	input.value = raw === undefined || raw === null ? '' : String(raw);
	input.placeholder = String(defaultForKey(spec));
	input.dataset.propKey = formatPath(path);
	input.addEventListener('change', () => {
		const text = input.value.trim();
		if (text === '') {
			writeKey(path, undefined, '清掉 ' + spec.name + '（回缺省）');
			return;
		}
		const number = Number(text);
		if (!Number.isFinite(number)) {
			input.value = raw === undefined || raw === null ? '' : String(raw);
			setBanner('「' + spec.name + '」要一个数字，写的是「' + text + '」。', false);
			return;
		}
		let value = number;
		if (spec.type === 'int') {
			value = Math.trunc(number);
		}
		if (['x', 'y', 'w', 'h'].includes(spec.name)) {
			value = clampProportion(value, defaultForKey(spec) === null ? 0.5 : Number(defaultForKey(spec)));
		}
		if (spec.name === 'size') {
			value = clampSize(value, 0.4);
		}
		writeKey(path, value, '把 ' + spec.name + ' 改成 ' + shortNumber(value));
	});
	return input;
}

function buildTextControl(spec, path, raw) {
	const input = tag('input');
	input.type = 'text';
	input.spellcheck = false;
	input.value = raw === undefined || raw === null ? '' : String(raw);
	input.placeholder = String(defaultForKey(spec));
	input.addEventListener('change', () => {
		const text = input.value;
		if (text === '' && spec.default === '') {
			writeKey(path, undefined, '清掉 ' + spec.name + '（回缺省）');
			return;
		}
		writeKey(path, text, '把 ' + spec.name + ' 改成「' + text + '」');
	});
	return input;
}

/** 颜色：色板 + 十六进制文本框。0 = 缺省（删键）—— 见下面那段注释。 */
function buildColorControl(spec, path, raw) {
	const wrap = tag('div');
	wrap.style.display = 'flex';
	wrap.style.gap = '4px';
	wrap.style.flex = '1 1 auto';
	wrap.style.minWidth = '0';

	const currentText = raw === undefined || raw === null ? '' : String(raw);
	const picker = tag('input');
	picker.type = 'color';
	picker.style.flex = '0 0 42px';
	picker.title = '只给 #RRGGBB；透明/跟随时用右边的文本框写 #AARRGGBB';
	picker.value = hexForPicker(currentText);
	picker.addEventListener('change', () => {
		const value = '#FF' + picker.value.replace('#', '').toUpperCase();
		writeKey(path, value, '把 ' + spec.name + ' 改成 ' + value);
	});

	const text = tag('input');
	text.type = 'text';
	text.spellcheck = false;
	text.style.flex = '1 1 auto';
	text.style.minWidth = '0';
	text.value = currentText;
	text.placeholder = '0 = 缺省（' + String(defaultForKey(spec)) + '）／#AARRGGBB';
	text.title = '写 0 = 用缺省（删掉这个键）；写 #RRGGBB / #AARRGGBB 是显式颜色';
	text.addEventListener('change', () => {
		const value = text.value.trim();
		// ★ 这里刻意把 0 当成"缺省"（删键），而不是写进文档：
		//   document.mjs 的 parseColor("0") 会得到 0xFF000000（不透明黑），
		//   而格式文档说的 0 是"不铺底 / 跟随 textColor"。删键才是那个语义的唯一正确落点。
		if (value === '' || value === '0') {
			writeKey(path, undefined, '把 ' + spec.name + ' 设成缺省（删键；0 = 透明 / 跟随 textColor）');
			return;
		}
		writeKey(path, value, '把 ' + spec.name + ' 改成 ' + value);
	});

	wrap.appendChild(picker);
	wrap.appendChild(text);
	return wrap;
}

function hexForPicker(text) {
	const match = /^#?([0-9a-fA-F]{6})$/.exec(String(text || '').trim());
	if (match !== null) {
		return '#' + match[1].toUpperCase();
	}
	const eight = /^#?([0-9a-fA-F]{8})$/.exec(String(text || '').trim());
	if (eight !== null) {
		return '#' + eight[1].substring(2).toUpperCase();
	}
	return '#ffffff';
}

function buildEnumControl(spec, path, raw) {
	const select = tag('select');
	const missing = tag('option', null, '（缺省：' + String(defaultForKey(spec)) + '）');
	missing.value = '__default__';
	select.appendChild(missing);
	for (const value of valuesForKey(spec)) {
		const option = tag('option', null, value);
		option.value = value;
		select.appendChild(option);
	}
	select.value = raw === undefined || raw === null ? '__default__' : String(raw);
	if (!valuesForKey(spec).includes(select.value)) {
		select.value = '__default__';
	}
	select.addEventListener('change', () => {
		if (select.value === '__default__') {
			writeKey(path, undefined, '把 ' + spec.name + ' 设成缺省（删键）');
		} else {
			writeKey(path, select.value, '把 ' + spec.name + ' 改成 ' + select.value);
		}
	});
	return select;
}

/** 模板：文本框 + 插入字段（fields.json）+ 插入过滤器。 */
function buildTemplateControl(spec, path, raw) {
	const wrap = tag('div');
	wrap.style.display = 'flex';
	wrap.style.gap = '4px';
	wrap.style.flex = '1 1 auto';
	wrap.style.minWidth = '0';
	wrap.style.flexWrap = 'wrap';

	const input = tag('input');
	input.type = 'text';
	input.spellcheck = false;
	input.style.flex = '1 1 140px';
	input.value = raw === undefined || raw === null ? '' : String(raw);
	input.placeholder = '例如 开往 {pid.terminus}';
	input.addEventListener('change', () => {
		writeKey(path, input.value, '把 ' + spec.name + ' 改成「' + input.value + '」');
	});
	wrap.appendChild(input);

	const insert = text => {
		const start = input.selectionStart === null ? input.value.length : input.selectionStart;
		const end = input.selectionEnd === null ? start : input.selectionEnd;
		input.value = input.value.slice(0, start) + text + input.value.slice(end);
		input.focus();
		input.selectionStart = input.selectionEnd = start + text.length;
	};

	const fieldPick = tag('select');
	fieldPick.title = '插入一个字段：{路径}';
	const fieldHint = tag('option', null, '插入字段…');
	fieldHint.value = '';
	fieldPick.appendChild(fieldHint);
	for (const field of logicFieldChoices(state.fieldSpecs.map(item => item.name), Object.keys(faceObject() && isPlainObject(faceObject().vars) ? faceObject().vars : {}))) {
		const option = tag('option', null, field);
		option.value = field;
		fieldPick.appendChild(option);
	}
	fieldPick.addEventListener('change', () => {
		if (fieldPick.value !== '') {
			insert('{' + fieldPick.value + '}');
			fieldPick.value = '';
		}
	});
	wrap.appendChild(fieldPick);

	const filterPick = tag('select');
	filterPick.title = '插入一个过滤器：写在竖线后面，如 {speedKmh|int}';
	const filterHint = tag('option', null, '插入过滤器…');
	filterHint.value = '';
	filterPick.appendChild(filterHint);
	for (const filter of ['upper', 'lower', 'trim', 'int', 'num:1', 'pad:5', 'len', 'default:文本']) {
		const option = tag('option', null, filter);
		option.value = filter;
		filterPick.appendChild(option);
	}
	filterPick.addEventListener('change', () => {
		if (filterPick.value !== '') {
			insert('|' + filterPick.value);
			filterPick.value = '';
		}
	});
	wrap.appendChild(filterPick);
	return wrap;
}

/** elements / pages：只读摘要 + 用源码编辑。 */
function buildContainerControl(spec, path, raw, kindLabel) {
	const wrap = tag('div');
	wrap.style.display = 'flex';
	wrap.style.gap = '4px';
	wrap.style.flex = '1 1 auto';
	wrap.style.minWidth = '0';
	const count = Array.isArray(raw) ? raw.length : (raw === undefined ? 0 : '不是数组');
	const summary = kindLabel + '：' + count + (Array.isArray(raw) ? ' 项' : '');
	const text = tag('span', 'muted', summary);
	text.title = showValue(raw);
	wrap.appendChild(text);
	wrap.appendChild(miniButton('用源码编辑', () => openSource(path, spec.name)));
	return wrap;
}

/** object：drum 有专属块；vars 逐条给条件构造器；其余只读摘要 + 源码编辑。 */
function buildObjectControl(spec, path, raw, container, containerPath) {
	if (spec.name === 'vars') {
		return buildVarsControl(path, raw);
	}
	if (spec.name === 'drum') {
		return buildDrumControl(path, raw);
	}
	const wrap = tag('div');
	wrap.style.display = 'flex';
	wrap.style.gap = '4px';
	wrap.style.flex = '1 1 auto';
	wrap.style.minWidth = '0';
	const summary = isPlainObject(raw) ? ('对象：' + Object.keys(raw).length + ' 个键') : (raw === undefined ? '（缺省）' : '不是对象');
	const text = tag('span', 'muted', summary);
	text.title = showValue(raw);
	wrap.appendChild(text);
	wrap.appendChild(miniButton('用源码编辑', () => openSource(path, spec.name)));
	return wrap;
}

function buildVarsControl(path, raw) {
	const wrap = tag('div');
	wrap.style.flex = '1 1 auto';
	wrap.style.minWidth = '0';
	if (!isPlainObject(raw)) {
		wrap.appendChild(tag('span', 'muted', '（没有 vars）'));
		const nameInput = tag('input');
		nameInput.type = 'text';
		nameInput.placeholder = '变量名';
		nameInput.size = 8;
		wrap.appendChild(nameInput);
		wrap.appendChild(miniButton('加一个变量', () => addVar(nameInput.value)));
		return wrap;
	}
	for (const [name, expression] of Object.entries(raw)) {
		const row = tag('div', 'logicRow');
		row.appendChild(tag('span', 'muted', name));
		row.appendChild(buildLogicEditor(expression, [...path, name], 'var:' + name));
		row.appendChild(miniButton('删', () => commit('删掉 vars.' + name, root => deletePath(root, [...path, name]))));
		wrap.appendChild(row);
	}
	const nameInput = tag('input');
	nameInput.type = 'text';
	nameInput.placeholder = '新变量名';
	nameInput.size = 8;
	wrap.appendChild(nameInput);
	wrap.appendChild(miniButton('加一个变量', () => addVar(nameInput.value)));
	return wrap;
}

function addVar(name) {
	const trimmed = String(name || '').trim();
	if (trimmed === '') {
		setBanner('变量名不能空。', false);
		return;
	}
	commit('加变量 vars.' + trimmed, root => {
		const face = getPath(root, documentPath());
		if (!isPlainObject(face.vars)) {
			face.vars = {};
		}
		face.vars[trimmed] = { '!!': [{ var: 'pid.service' }] };
	});
}

function buildDrumControl(path, raw) {
	const wrap = tag('div');
	wrap.style.flex = '1 1 auto';
	wrap.style.minWidth = '0';
	if (!isPlainObject(raw)) {
		wrap.appendChild(tag('span', 'muted', '（没写 drum = 不是翻牌机）'));
		wrap.appendChild(miniButton('加翻牌机', () => {
			commit('加翻牌机 drum', root => setPath(root, path, { count: 2, turnFraction: 0.25, radiusM: 0 }));
		}));
		return wrap;
	}
	for (const spec of sectionKeys('drum')) {
		wrap.appendChild(buildPropRow(spec, path, 'drum', raw));
	}
	wrap.appendChild(miniButton('删掉翻牌机', () => commit('删掉翻牌机', root => deletePath(root, path))));
	return wrap;
}

// ---- 条件构造器（可视化 JSONLogic） --------------------------------------------------------------

/**
 * `spec` 是键表里那条 logic 键；路径 = containerPath + key。
 * 两个模式：构造器（行式）与源码（JSON 文本），两边同步；源码里的坏 JSON **不覆盖**文档、给红字。
 */
function buildLogicControl(spec, path, container, containerPath, sectionName) {
	const wrap = tag('div');
	wrap.style.flex = '1 1 auto';
	wrap.style.minWidth = '0';
	const expression = container[spec.name];
	wrap.appendChild(buildLogicEditor(expression, path, sectionName + ':' + spec.name));
	return wrap;
}

function buildLogicEditor(expression, path, keyId) {
	const key = formatPath(path) + '|' + keyId;
	const box = tag('div', 'logicBox');
	const present = expression !== undefined;
	const representable = present && isBuilderRepresentable(expression);
	const mode = state.logicModes[key] || (representable ? 'builder' : (present ? 'source' : 'builder'));

	const tabs = tag('div', 'logicRow');
	const builderTab = miniButton('构造器', () => {
		state.logicModes[key] = 'builder';
		renderProperties();
	});
	const sourceTab = miniButton('源码', () => {
		state.logicModes[key] = 'source';
		renderProperties();
	});
	if (mode === 'builder') {
		builderTab.classList.add('on');
	} else {
		sourceTab.classList.add('on');
	}
	tabs.appendChild(builderTab);
	tabs.appendChild(sourceTab);
	tabs.appendChild(tag('span', 'muted', present ? '' : '（还没写 = 总是画/总是可见）'));
	box.appendChild(tabs);

	if (mode === 'builder' && !present) {
		const add = tag('div', 'logicRow');
		add.appendChild(miniButton('加一个条件', () => {
			commit('给 ' + path[path.length - 1] + ' 加条件', root => setPath(root, path, { '!!': [{ var: firstFieldName() }] }));
		}));
		box.appendChild(add);
		box.appendChild(tag('div', 'muted', '不写条件 = 总是画（"没有 when" ≠ "条件不成立"，这条坑过 F0）。'));
		return box;
	}
	if (mode === 'builder' && !representable) {
		box.appendChild(note('logicRaw', '这条表达式构造器表达不了：' + JSON.stringify(expression)));
		box.appendChild(note('muted', '它照样有效（游戏里按原样求值）—— 要改它请切到「源码」。'));
		box.appendChild(miniButton('切到源码', () => {
			state.logicModes[key] = 'source';
			renderProperties();
		}));
		return box;
	}
	if (mode === 'source') {
		const stored = state.logicSources[key];
		const text = stored && stored.text !== undefined ? stored.text : JSON.stringify(present ? expression : { '!!': [{ var: firstFieldName() }] }, null, 2);
		const area = tag('textarea', 'sourceBox');
		area.value = text;
		area.spellcheck = false;
		area.addEventListener('input', () => {
			state.logicSources[key] = { text: area.value, error: null };
			errorLine.textContent = '';
		});
		const errorLine = note('bad', stored && stored.error ? stored.error : '');
		const actions = tag('div', 'logicRow');
		actions.appendChild(miniButton('应用', () => {
			let parsed;
			try {
				parsed = JSON.parse(area.value);
			} catch (e) {
				state.logicSources[key] = { text: area.value, error: 'JSON 坏了：' + e.message + '（文档没有被改动）' };
				renderProperties();
				return;
			}
			state.logicSources[key] = { text: area.value, error: null };
			writeKey(path, parsed, '把 ' + path[path.length - 1] + ' 换成源码里那条表达式');
		}));
		actions.appendChild(miniButton('取消改动', () => {
			delete state.logicSources[key];
			renderProperties();
		}));
		actions.appendChild(tag('span', 'muted', '算子清单（' + LOGIC_OPERATORS.length + ' 个，与 Java operators() 同序）：' + LOGIC_OPERATORS.join(' ')));
		box.appendChild(area);
		box.appendChild(errorLine);
		box.appendChild(actions);
		return box;
	}

	// ---- 构造器模式 ----
	const tree = treeFor(key, expression);
	renderLogicGroup(box, tree, path, key, 1);
	return box;
}

function firstFieldName() {
	const vars = faceObject() && isPlainObject(faceObject().vars) ? Object.keys(faceObject().vars) : [];
	if (vars.length > 0) {
		return vars[0];
	}
	const names = state.fieldSpecs.map(spec => spec.name);
	// 默认挑最常用的那个（班次号）：新行的字段下拉一打开就是"能干活的"值
	for (const preferred of ['pid.service', 'pid.next', 'pid.terminus']) {
		if (names.includes(preferred)) {
			return preferred;
		}
	}
	return names.length > 0 ? names[0] : 'pid.service';
}

/** 构造器的中间树：从 JSON 读一次，之后靠"上次产出的表达式"判断要不要重建（保住空行）。 */
function treeFor(key, expression) {
	const stored = state.logicTrees[key];
	if (stored && sameJson(stored.built, expression)) {
		return stored.tree;
	}
	const read = readLogic(expression);
	const tree = read.kind === 'row'
		? newGroup('and', [read])
		: (read.kind === 'group' ? read : newGroup('and', [newRow(firstFieldName(), 'has')]));
	state.logicTrees[key] = { tree, built: expression };
	return tree;
}

function applyTree(key, tree, path) {
	const expression = buildLogic(tree);
	if (expression === null) {
		setBanner('这个条件还是空的：至少填一行"字段 + 算子"（空行不会被写进文档）。', false);
		return;
	}
	state.logicTrees[key] = { tree, built: expression };
	writeKey(path, cloneJson(expression), '改 ' + path[path.length - 1] + ' 条件');
}

function renderLogicGroup(box, group, path, key, depth) {
	const wrap = tag('div', 'logicGroup');
	const head = tag('div', 'logicRow');
	const opSelect = tag('select');
	for (const [value, text] of [['and', '并且（and）'], ['or', '或者（or）']]) {
		const option = tag('option', null, text);
		option.value = value;
		option.selected = group.operator === value;
		opSelect.appendChild(option);
	}
	opSelect.addEventListener('change', () => {
		group.operator = opSelect.value;
		applyTree(key, group, path);
	});
	head.appendChild(tag('span', 'muted', '第 ' + depth + ' 层'));
	head.appendChild(opSelect);
	head.appendChild(miniButton('＋行', () => {
		group.rows.push(newRow(firstFieldName(), 'has'));
		applyTree(key, group, path);
	}));
	head.appendChild(miniButton('＋"有值"行', () => {
		group.rows.push(newRow(firstFieldName(), 'has'));
		applyTree(key, group, path);
	}, '写成 {"!!":[{"var":"路径"}]} —— 判"字段有值"的**正确**写法（不是 != 空串）'));
	head.appendChild(miniButton('＋"为空"行', () => {
		group.rows.push(newRow(firstFieldName(), 'empty'));
		applyTree(key, group, path);
	}, '写成 {"!":[{"!!":[{"var":"路径"}]}]}'));
	if (canAddGroup(group)) {
		head.appendChild(miniButton('＋组', () => {
			group.rows.push(newGroup('and', [newRow(firstFieldName(), 'has')]));
			applyTree(key, group, path);
		}, '最多套 3 层'));
	} else {
		head.appendChild(tag('span', 'muted', '（已经 3 层，不能再套）'));
	}
	wrap.appendChild(head);

	group.rows.forEach((row, index) => {
		if (row && row.kind === 'group') {
			// 嵌套的一组：给一个容器（不是 flex 行，不然里面那一组会挤成一条）
			const child = tag('div');
			renderLogicGroup(child, row, path, key, depth + 1);
			child.appendChild(miniButton('删这一组', () => {
				group.rows.splice(index, 1);
				if (group.rows.length === 0) {
					group.rows.push(newRow(firstFieldName(), 'has'));
				}
				applyTree(key, group, path);
			}));
			wrap.appendChild(child);
			return;
		}
		wrap.appendChild(renderLogicRow(group, row, index, path, key));
	});
	box.appendChild(wrap);
}

function renderLogicRow(group, row, index, path, key) {
	const wrap = tag('div', 'logicRow');
	const fields = logicFieldChoices(state.fieldSpecs.map(spec => spec.name),
		faceObject() && isPlainObject(faceObject().vars) ? Object.keys(faceObject().vars) : [], ['item', 'index']);

	const fieldPick = tag('select', 'fieldPick');
	fieldPick.title = '字段（来自 fields.json）或本文档的 vars 名';
	for (const name of fields) {
		const option = tag('option', null, name);
		option.value = name;
		option.selected = name === row.field;
		fieldPick.appendChild(option);
	}
	// 行里已经写了一个不在清单里的路径（比如 item.xxx）：也列出来，别把人家的值吃掉
	if (row.field !== '' && !fields.includes(row.field)) {
		const option = tag('option', null, row.field + '（不在字段表里）');
		option.value = row.field;
		option.selected = true;
		fieldPick.appendChild(option);
	}
	fieldPick.addEventListener('change', () => {
		row.field = fieldPick.value;
		applyTree(key, group, path);
	});
	wrap.appendChild(fieldPick);

	const opPick = tag('select', 'opPick');
	opPick.title = '行算子：比较算子取自 Java operators() 的 32 个算子清单，另加"有值/为空"两个快捷模板';
	const rowOps = ['has', 'empty'].concat(ROW_OPERATORS);
	for (const operator of rowOps) {
		const option = tag('option', null, ROW_OPERATOR_LABELS[operator] || operator);
		option.value = operator;
		option.selected = operator === row.operator;
		opPick.appendChild(option);
	}
	for (const operator of LOGIC_OPERATORS) {
		if (rowOps.includes(operator)) {
			continue;
		}
		const option = tag('option', null, operator + '（要源码模式）');
		option.value = 'src:' + operator;
		opPick.appendChild(option);
	}
	opPick.addEventListener('change', () => {
		if (opPick.value.startsWith('src:')) {
			const operator = opPick.value.substring(4);
			state.logicModes[key] = 'source';
			state.logicSources[key] = { text: '{\n  "' + operator + '": []\n}', error: null };
			setBanner('「' + operator + '」不是"行式"算子（它的参数形态不是"字段 + 值"）—— 已切到源码模式，'
				+ '填好参数再点「应用」；不点应用就不会改文档。', false);
			renderProperties();
			return;
		}
		row.operator = opPick.value;
		applyTree(key, group, path);
	});
	wrap.appendChild(opPick);

	if (row.operator !== 'has' && row.operator !== 'empty') {
		const kindPick = tag('select');
		for (const [value, text] of [['text', '文本'], ['number', '数字'], ['bool', '真假'], ['field', '字段'], ['null', 'null']]) {
			const option = tag('option', null, text);
			option.value = value;
			option.selected = (row.valueKind || 'text') === value;
			kindPick.appendChild(option);
		}
		kindPick.addEventListener('change', () => {
			row.valueKind = kindPick.value;
			applyTree(key, group, path);
		});
		wrap.appendChild(kindPick);

		let valueControl;
		if (row.valueKind === 'bool') {
			valueControl = tag('select', 'valueInput');
			for (const value of ['true', 'false']) {
				const option = tag('option', null, value);
				option.value = value;
				option.selected = String(row.value) === value;
				valueControl.appendChild(option);
			}
			valueControl.addEventListener('change', () => {
				row.value = valueControl.value === 'true';
				applyTree(key, group, path);
			});
		} else if (row.valueKind === 'field') {
			valueControl = tag('select', 'valueInput');
			for (const name of fields) {
				const option = tag('option', null, name);
				option.value = name;
				option.selected = String(row.value) === name;
				valueControl.appendChild(option);
			}
			valueControl.addEventListener('change', () => {
				row.value = valueControl.value;
				applyTree(key, group, path);
			});
		} else {
			valueControl = tag('input', 'valueInput');
			valueControl.type = row.valueKind === 'number' ? 'number' : 'text';
			valueControl.spellcheck = false;
			valueControl.value = row.value === undefined || row.value === null ? '' : String(row.value);
			valueControl.addEventListener('change', () => {
				row.value = valueControl.value;
				applyTree(key, group, path);
			});
		}
		wrap.appendChild(valueControl);
	} else {
		wrap.appendChild(tag('span', 'muted', row.operator === 'has'
			? '{"!!":[{"var":"' + row.field + '"}]}'
			: '{"!":[{"!!":[{"var":"' + row.field + '"}]}]}'));
	}

	wrap.appendChild(miniButton('删', () => {
		group.rows.splice(index, 1);
		if (group.rows.length === 0) {
			group.rows.push(newRow(firstFieldName(), 'has'));
		}
		applyTree(key, group, path);
	}));
	return wrap;
}

// ---- 源码编辑（elements / pages / object） --------------------------------------------------------

function openSource(path, label) {
	const current = getPath(state.root, path);
	state.sourceEditor = {
		key: formatPath(path),
		path,
		label,
		text: JSON.stringify(current === undefined ? null : current, null, 2),
		error: null,
	};
	renderProperties();
}

function renderSourceEditor(container, editor) {
	const wrap = tag('div', 'propBlock');
	wrap.appendChild(tag('h3', null, '源码编辑：' + editor.label + '（' + editor.key + '）'));
	const area = tag('textarea', 'sourceBox');
	area.value = editor.text;
	area.spellcheck = false;
	area.style.minHeight = '180px';
	area.addEventListener('input', () => {
		editor.text = area.value;
		editor.error = null;
	});
	wrap.appendChild(area);
	if (editor.error) {
		wrap.appendChild(note('bad', editor.error));
	}
	const actions = tag('div', 'logicRow');
	actions.appendChild(miniButton('应用', () => {
		let parsed;
		try {
			parsed = JSON.parse(area.value);
		} catch (e) {
			editor.error = 'JSON 坏了：' + e.message + '（文档没有被改动）';
			renderProperties();
			return;
		}
		commit('改 ' + editor.label + ' 源码', root => setPath(root, editor.path, parsed));
		state.sourceEditor = null;
		renderProperties();
	}));
	actions.appendChild(miniButton('取消', () => {
		state.sourceEditor = null;
		renderProperties();
	}));
	wrap.appendChild(actions);
	container.appendChild(wrap);
}

/** 未知键区（黄）：JSON 里有、schema.json 里没有 —— 拼错键的防线，允许删除。 */
function appendUnknownBlock(container, object, known, path, where) {
	if (state.sourceEditor && sameJson(state.sourceEditor.path, path)) {
		renderSourceEditor(container, state.sourceEditor);
		return;
	}
	const unknown = unknownKeys(object, known);
	if (unknown.length === 0) {
		return;
	}
	const block = tag('div', 'propBlock unknownKeys');
	block.appendChild(tag('h3', null, where + '上的未知键（' + unknown.length + ' 个，schema.json 里没有 —— 多半是拼错了）'));
	for (const key of unknown) {
		const row = tag('div', 'propRow');
		const label = tag('label', null, key);
		label.title = 'JSON 里有这个键，但键表里没有：引擎遇到不认识的键会"照画 + 记一次"，拼错的键（colour / siz）就是靠这块看出来的';
		row.appendChild(label);
		row.appendChild(tag('span', 'default', shortValue(object[key])));
		try {
			row.appendChild(tag('span', 'muted', JSON.stringify(object[key]).substring(0, 60)));
		} catch (e) {
			row.appendChild(tag('span', 'muted', String(object[key])));
		}
		row.appendChild(miniButton('删掉这个键', () => {
			commit('删掉未知键 ' + key, root => deletePath(root, [...path, key]));
		}));
		block.appendChild(row);
	}
	container.appendChild(block);
}

// ---- 保存（POST /save） --------------------------------------------------------------------------

/**
 * 未保存的改动 = 当前 JSON 与"上次读盘/写盘的那份"不一样。
 * 为什么不用"有没有撤销栈"来判：一路撤销回到打开时的状态，其实就没有改动了。
 */
function refreshDirty() {
	const dirty = state.savedJson !== null && JSON.stringify(state.root) !== state.savedJson;
	setDirty(dirty);
}

function setDirty(value) {
	if (state.dirty === value) {
		updateTitle();
		return;
	}
	state.dirty = value;
	updateTitle();
}

function updateTitle() {
	const path = state.sourcePath ? ' — ' + state.sourcePath : '';
	document.title = 'MMTR 车辆动态面工作室' + (state.dirty ? ' *' : '') + path;
}

function updateSavePathLabel() {
	const label = $('savePath');
	if (state.sourcePath) {
		label.textContent = '工作区相对路径：' + state.sourcePath;
		label.className = 'muted';
	} else if (state.root) {
		label.textContent = '（这份是用文件选择器打开的，没有工作区路径 —— 用「另存为…」）';
		label.className = 'warn';
	} else {
		label.textContent = '（没有可写路径）';
		label.className = 'muted';
	}
	updateTitle();
}

function defaultSaveAsPath() {
	const name = state.faceName ? state.faceName : 'face';
	return 'sandbox\\face-studio\\' + name + '.json';
}

function serializeRoot() {
	return JSON.stringify(state.root, null, 2) + '\n';
}

function saveCurrent() {
	if (!state.root) {
		setBanner('还没打开文件，没东西可保存。', false);
		return;
	}
	if (!state.sourcePath) {
		const row = $('saveAsRow');
		row.hidden = false;
		$('saveAsPath').value = defaultSaveAsPath();
		$('saveAsPath').focus();
		setBanner('这份文件没有工作区路径（是"选文件"打开的）—— 在「另存为」里写一个白名单内的相对路径再写盘。', false);
		return;
	}
	saveTo(state.sourcePath);
}

async function saveTo(path) {
	if (!state.root) {
		return;
	}
	const content = serializeRoot();
	const result = await saveTextTo(path, content, '已保存');
	if (result !== null) {
		state.sourcePath = path;
		state.savedJson = JSON.stringify(state.root);
		refreshDirty();
		updateSavePathLabel();
		$('saveAsRow').hidden = true;
	}
}

/**
 * 往写端点（POST /save）写**一份文本** —— 面文档与扩展骨架共用这一条路。
 *
 * 为什么要分成两个函数：面文档写成功之后要更新 state.sourcePath / savedJson（那是"这份文档现在
 * 存哪、有没有未保存改动"），而扩展骨架与 state.root 无关（它是一份新的 .java）。
 * 共用的部分（请求、错误话术、返回 {"saved","backup"}）留在这里，两边的差别只有"写完之后做什么"。
 *
 * @returns {object|null} 成功 = 服务端的返回；失败 = null（原因已经写在 banner 上）
 */
async function saveTextTo(path, content, okWord) {
	setStatus('正在保存到 ' + path + ' …');
	let response;
	try {
		response = await fetch('/save', {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ path, content }),
		});
	} catch (e) {
		setBanner('保存失败：连不上工作室服务器（' + (e && e.message ? e.message : e) + '）。'
			+ '\n请用 face-studio.ps1 起的服务器打开这个页面；直接 file:// 打开时没有 /save。', false);
		setStatus('保存失败');
		return null;
	}
	const text = await response.text();
	let parsed = null;
	try {
		parsed = JSON.parse(text);
	} catch (e) {
		parsed = null;
	}
	if (!response.ok) {
		const message = parsed && parsed.error ? parsed.error : (text || ('HTTP ' + response.status));
		setBanner('保存被拒（HTTP ' + response.status + '）：' + message, false);
		setStatus('保存被拒');
		return null;
	}
	const backup = parsed && parsed.backup ? parsed.backup : '';
	const saved = (parsed && parsed.saved) || path;
	setBanner(okWord + ' ' + saved + (backup ? '（备份 ' + backup + '）' : '（新建，没有旧文件要备份）'), true);
	setStatus(okWord + ' · ' + path);
	return parsed || { saved, backup };
}

// ---- 导出 Java 骨架（F4 代码面） -----------------------------------------------------------------
/*
 * 这一块只做三件事：把"当前正在编辑的这块面"交给 designer.mjs 的纯函数扫一遍、把生成的 Java
 * 放进文本框、把下载/保存接上。**扫描与生成一个字都不在这里写**（那些都在纯函数层，有 Node 自检）。
 *
 * 为什么值得在作者手边：一块面里写了 `vendor:bar`，作者下一步要干的是"在模组里注册它"。
 * 让他自己从零写一个 MmtrFaceExtension 实现，最容易错的不是画法，而是
 * ① 类名/包名对不对、② 要不要带命名空间、③ ServiceLoader 那个声明文件放哪 ——
 * 这三件事骨架里都直接写好了。
 */

/** 打开面板（没生成也要能打开：先让作者看见"这块面用了哪些扩展"）。 */
function showExportPanel() {
	$('exportPanel').hidden = false;
}

/**
 * `?export=1`：请求里带了就展开导出面板。
 *
 * <p>为什么要有它：面板默认是收着的（页面上那一排按钮已经有"保存/另存为"，再摊开一大块会挤掉牌面），
 * 但"我要看这块面的骨架"这件事得能**一条链接直达** —— 否则扩展作者每次都要先点一次按钮，
 * 而无头截图/自动化连"点一次"都做不到。</p>
 *
 * <p>这里**只展开、不生成**：真正的生成要等锚点读完（没读到面就生成只会得到一句"要一块面文档"）。</p>
 */
function openExportFromQuery(value) {
	if (value === null || value === undefined || value === '' || value === '0') {
		return;
	}
	showExportPanel();
	state.exportPending = true;
}

/** 锚点读完、也选好了面：如果请求里要过导出，就在这里生成一次（见 openExportFromQuery）。 */
function runPendingExport() {
	if (!state.exportPending) {
		return;
	}
	state.exportPending = false;
	generateExportSkeleton();
}

/** 生成一次骨架：读当前面 → 扫 → 造 Java → 上屏。 */
function generateExportSkeleton() {
	const face = faceObject();
	if (!isPlainObject(face)) {
		setBanner('「导出 Java 骨架」要一块**面文档**：先在左边点一块面（或一份内置水牌）。', false);
		showExportPanel();
		return;
	}
	const wholeFace = $('exportWholeFace').checked;
	state.exportWholeFace = wholeFace;
	// 只扫"这一页"时，交给扫描器的就是那一页（它只看 elements / require）；整块面就直接给面对象
	const scanned = wholeFace ? face : (currentPageObject() || face);
	const report = scanFaceForExtensions(scanned, state.schema, (state.fieldSpecs || []).map(spec => spec.name), wholeFace);

	// 类名：没被手改过就跟着面名走（面名可能有非标识符字符，用 javaClassFromType 洗一遍）
	const suggested = javaClassFromType(state.faceName || 'face', 'Vendor') + 'FaceExtension';
	if (!state.exportClassNameEdited || !$('exportClass').value.trim()) {
		$('exportClass').value = suggested;
	}
	const className = $('exportClass').value.trim();
	const packageName = $('exportPackage').value.trim() || 'vendor.faceaddon';

	const result = buildExtensionSkeleton(report, {
		className,
		packageName,
		faceName: state.faceName,
		sourcePath: state.sourcePath,
		wholeFace,
	});
	result.report = report;
	state.exportResult = result;

	$('exportJava').value = result.java;
	$('exportJava').scrollTop = 0;
	$('exportService').textContent = '放这里：' + result.serviceFileDir
		+ '\n文件名：' + result.serviceFileName
		+ '\n内容（就一行）：' + result.serviceFileContent.trim();

	const summary = [];
	summary.push('面：' + (state.faceName || '（未命名）') + '　扫描范围：' + (wholeFace ? '整块面（所有页 + 文档级 vars/require/pageExpr）' : '只当前这一页'));
	if (result.empty) {
		summary.push('★ 这块面只用内置的东西 —— 不需要扩展。下面是一份**最小可用骨架**（一个空 register），');
		summary.push('  留着它以后加东西，或者干脆不装。');
	} else {
		summary.push('① 非内置元素类型 ' + report.elements.length + ' 个：'
			+ (report.elements.map(item => item.type + '[' + item.keys.join(',') + ']').join('　') || '（没有）'));
		summary.push('② 非内置算子 ' + report.operators.length + ' 个：' + (report.operators.join('　') || '（没有）'));
		summary.push('③ 非内置过滤器 ' + report.filters.length + ' 个：' + (report.filters.join('　') || '（没有）'));
		summary.push('④ 不在 fields.json 里的字段 ' + report.fields.length + ' 个：' + (report.fields.join('　') || '（没有）'));
	}
	if (report.localNames.length) {
		summary.push('（vars / forEach 绑定名，不算缺字段：' + report.localNames.join('、') + '）');
	}
	for (const warning of result.warnings) {
		summary.push('⚠ ' + warning);
	}
	$('exportSummary').textContent = summary.join('\n');
	$('exportPanel').className = result.warnings.length ? 'warn' : '';

	if (result.empty) {
		setBanner('这块面只用内置的东西，不需要扩展 —— 已经给你生成了一份「最小可用骨架」（一个空 register）。', 'warn');
	} else {
		setBanner('已按面「' + (state.faceName || '未命名') + '」生成扩展骨架：四类桩共 ' + result.total + ' 个'
			+ '（元素 ' + report.elements.length + ' / 算子 ' + report.operators.length
			+ ' / 过滤器 ' + report.filters.length + ' / 字段 ' + report.fields.length + '）。'
			+ (result.warnings.length ? '\n⚠ 有 ' + result.warnings.length + ' 个名字会被引擎拒绝 —— 见面板里的黄字。' : ''), true);
	}
}

/** 默认落点：白名单里的 sandbox\face-studio\export\（写端点认 .java，见 FaceServe 的类注释）。 */
function defaultExportPath() {
	const name = state.exportResult ? state.exportResult.className : 'VendorFaceExtension';
	return 'sandbox\\face-studio\\export\\' + name + '.java';
}

/** 「下载 .java」：走 Blob + 一次性链接，不依赖服务器。 */
function downloadExportJava() {
	if (!state.exportResult) {
		setBanner('先点「导出 Java 骨架」生成一份，再下载。', false);
		return;
	}
	const blob = new Blob([state.exportResult.java], { type: 'text/x-java-source;charset=utf-8' });
	const url = URL.createObjectURL(blob);
	const link = document.createElement('a');
	link.href = url;
	link.download = state.exportResult.className + '.java';
	document.body.appendChild(link);
	link.click();
	document.body.removeChild(link);
	// 立刻回收：blob 留着不放会把整份源码常驻内存（这个页面开一整天是常态）
	URL.revokeObjectURL(url);
	setStatus('已下载 ' + state.exportResult.className + '.java');
}

/** 「保存到工作区」：与面文档同一个写端点（POST /save），落点必须在白名单目录里。 */
function saveExportJava() {
	if (!state.exportResult) {
		setBanner('先点「导出 Java 骨架」生成一份，再保存。', false);
		return;
	}
	saveTextTo(defaultExportPath(), state.exportResult.java, '已写出扩展骨架');
}

// ---- 自检（浏览器里跑同一份向量） ----------------------------------------------------------------

async function runSelfTest() {
	const box = $('selftestOut');
	box.textContent = '';
	let vectors;
	try {
		const response = await fetch('conformance/logic.json', { cache: 'no-store' });
		if (!response.ok) {
			throw new Error('HTTP ' + response.status);
		}
		vectors = await response.json();
	} catch (e) {
		appendLine(box, '读不到 conformance/logic.json：' + e.message, 'bad');
		appendLine(box, '（file:// 下浏览器不给 fetch 本地文件：请起服务器，或在命令行跑 node mmtr/tools/face-studio/selftest.mjs）');
		return;
	}
	const cases = Array.isArray(vectors.cases) ? vectors.cases : [];
	appendLine(box, '向量 ' + cases.length + ' 条（版本 ' + vectors.version + '）；实现：本页的 logic.mjs。');
	let passed = 0;
	let failed = 0;
	for (const testCase of cases) {
		let actual;
		let thrown = null;
		try {
			actual = evaluate(testCase.expr, vectors.data === undefined ? null : vectors.data);
		} catch (e) {
			thrown = e;
		}
		if (thrown !== null) {
			failed++;
			appendLine(box, '失败  ' + testCase.name + ' —— 抛异常：' + (thrown.message || thrown), 'bad');
		} else if (sameValue(actual, testCase.expect)) {
			passed++;
			appendLine(box, '通过  ' + testCase.name, 'good');
		} else {
			failed++;
			appendLine(box, '失败  ' + testCase.name + ' —— 得到 ' + show(actual) + '，期望 ' + show(testCase.expect), 'bad');
		}
	}
	appendLine(box, '', null);
	appendLine(box, '通过 ' + passed + ' / 失败 ' + failed + '（条数 ' + cases.length + '）' + (failed === 0 ? ' —— 与 Java 端跑的是同一份向量' : ''),
		failed === 0 ? 'good' : 'bad');
	appendLine(box, '清单：算子 ' + OPERATORS.length + ' 项、过滤器 ' + FILTERS.length + ' 项、元素类型 ' + ELEMENT_TYPES.length + ' 项'
		+ '（与 verify_face.js / Java 的逐项核对在 node selftest.mjs 里）；绘制器纯函数层在 node designer-selftest.mjs 里');
	if (failed > 0) {
		setBanner('自检有 ' + failed + ' 条不通过 —— 这份实现与向量漂了，别拿它当判据。', false);
	}
}

function appendLine(box, text, className) {
	const div = document.createElement('div');
	if (className) {
		div.className = className;
	}
	div.textContent = text;
	box.appendChild(div);
}

/** 与 selftest.mjs 同一口径的深比较（那边是 Node 脚本，带 process.exit，不能在浏览器里 import）。 */
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
		return Array.isArray(actual) && actual.length === expected.length && expected.every((item, index) => sameValue(actual[index], item));
	}
	if (typeof expected === 'object') {
		if (actual === null || typeof actual !== 'object' || Array.isArray(actual)) {
			return false;
		}
		const keys = Object.keys(expected);
		return Object.keys(actual).length === keys.length
			&& keys.every(key => Object.prototype.hasOwnProperty.call(actual, key) && sameValue(actual[key], expected[key]));
	}
	return false;
}

// ---- 杂 -----------------------------------------------------------------------------------------

function setBanner(message, good) {
	const box = $('banner');
	box.hidden = false;
	box.className = good === true ? 'good' : (good === 'warn' ? 'warn' : '');
	box.textContent = message;
}

function setStatus(message) {
	state.status = message;
	$('status').textContent = message;
}

function shortNumber(value) {
	const number = Number(value);
	if (!Number.isFinite(number)) {
		return String(value);
	}
	return Number.isInteger(number) ? String(number) : String(Math.round(number * 10000) / 10000);
}

function shortValue(value) {
	if (value === undefined) {
		return '（没写）';
	}
	const text = showValue(value);
	return text.length > 18 ? text.substring(0, 18) + '…' : text;
}

function showValue(value) {
	try {
		return JSON.stringify(value);
	} catch (e) {
		return String(value);
	}
}

function safeJson(value) {
	return showValue(value) || '';
}
