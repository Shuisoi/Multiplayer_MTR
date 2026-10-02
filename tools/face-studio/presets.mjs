/*
 * presets.mjs —— 四个数据预设：跑着 / 停站开门 / 回库趟 / 没任务。
 *
 * 字段名与值**照抄** mmtr/tools/face-preview/FacePreview.java 的 preset()（离线 CLI 预览用的那四个），
 * 所以"工作室里看到的字"与"出图脚本看到的字"是同一条口径。字段表见 fields.json。
 *
 * 这里另有两层与 Java 对齐的加工，别漏：
 *   · FacePreview 是把这张表交给 MmtrFaceData.of(source)：**空字符串按"没有"丢掉**（回库趟的终点
 *     与下一站就是这么变成"不存在"的），并按登记表算派生量（speedKmh = speed × 3600，见 MmtrFaceData.derive）。
 *   · 面文档的求值走**点号路径**（lookup 一层层往下走），所以扁平的 "pid.service" 必须先嵌成
 *     { pid: { service: … } } 才取得到 —— 这就是 nest() 存在的理由（= MmtrFaceData.put 干的事）。
 */

import { asNumber, asString, truthy } from './logic.mjs';

/** 四个预设的名字（顺序 = 页面按钮的顺序）。 */
export const PRESET_NAMES = ['running', 'stopped', 'return', 'idle'];

/**
 * 取一个预设，返回**扁平**的 { "pid.service": "00109", … }（与 FacePreview.preset 逐字相同）。
 * 要拿去求值/画图的请用 presetData()。
 */
export function preset(name) {
	const values = {};
	// 公共：这是一趟 00109，开往海山，从鸥湾出发，司机在 A 端
	values['pid.service'] = '00109';
	values['pid.terminus'] = '海山';
	values['pid.next'] = '鸥湾';
	values['job.id'] = '00109';
	values['job.step'] = 3;
	values['job.steps'] = 12;
	values['job.note'] = '鸥湾站停车';
	values.mode = 'AUTO';
	values.driver = 'Shuisoi';
	values.active = true;
	values.pinned = false;
	values['cab.end'] = 'A';
	values['cab.carIndex'] = 0;
	values.limitKmh = 100;
	values['light.a'] = 2;
	values['light.b'] = 0;
	values.clock = '09:41';

	switch (String(name === null || name === undefined ? 'running' : name).toLowerCase()) {
		case 'stopped':
			values.speed = 0;
			values['lzb.supervising'] = true;
			values['lzb.ceilingKmh'] = 40;
			values['lzb.targetKmh'] = 0;
			values['lzb.targetM'] = 0;
			values['hold.reason'] = '';
			values['job.note'] = '站台作业：开门';
			break;
		case 'return':
			// ★ 回库趟：本趟没有站台目标 —— 终点与下一站都为空（"不谎报终点"那条口径）
			values['pid.terminus'] = '';
			values['pid.next'] = '';
			values.speed = 0.008;
			values['job.note'] = '回库';
			values.limitKmh = 25;
			break;
		case 'idle':
			values['pid.service'] = '';
			values['pid.terminus'] = '';
			values['pid.next'] = '';
			values['job.id'] = '';
			values['job.step'] = 0;
			values['job.steps'] = 0;
			values.mode = '';
			values.driver = '';
			values.active = false;
			values.pinned = true;
			values.speed = 0;
			break;
		default:
			values.speed = 0.0166;      // ≈ 60 km/h
			values['lzb.supervising'] = true;
			values['lzb.ceilingKmh'] = 100;
			values['lzb.targetKmh'] = 80;
			values['lzb.targetM'] = 1240;
			values['handle.throttle'] = 3;
			values['hold.reason'] = '';
			break;
	}
	return values;
}

/** 预设 + 与 Java 同一条加工（按字段类型收口 → 丢掉空的 → 嵌层 → 算派生量）。 */
export function presetData(name) {
	return buildData(preset(name), null);
}

/**
 * 把扁平的表做成**能求值的数据**：按字段类型收口、空值丢掉、点号路径嵌层、再算派生量。
 *
 * @param flat    扁平表（{ "pid.service": "00109" }）
 * @param typeOf  可选：字段名 → 'num' | 'str' | 'bool'（来自 fields.json）。不给就按值的 JS 类型推断 ——
 *                预设的值本来就与登记表的类型一致，所以两条路的结果一样。
 */
export function buildData(flat, typeOf) {
	const coerced = {};
	for (const key of Object.keys(flat)) {
		const type = typeOf ? typeOf(key) : inferType(flat[key]);
		const value = coerce(flat[key], type);
		if (value !== null) {
			coerced[key] = value;
		}
	}
	const nested = nest(coerced);
	derive(nested);
	return nested;
}

/** 与 MmtrFaceData.coerce 同一口径：NUM ⇒ asNumber，BOOL ⇒ 真假，STR ⇒ 文本且**空串算没有**。 */
function coerce(raw, type) {
	if (raw === null || raw === undefined) {
		return null;
	}
	switch (type) {
		case 'num': {
			const number = asNumber(raw);
			return number === null ? null : number;
		}
		case 'bool':
			if (typeof raw === 'boolean') {
				return raw;
			}
			// Java：不是布尔就按数字看，还不是数字就当**真**（Boolean.TRUE）
			return asNumber(raw) !== null ? truthy(raw) : true;
		default: {
			const text = asString(raw);
			return text === '' ? null : text;
		}
	}
}

function inferType(value) {
	if (typeof value === 'boolean') {
		return 'bool';
	}
	if (typeof value === 'number') {
		return 'num';
	}
	return 'str';
}

/** 按点号路径分层放进去（"pid.terminus" ⇒ { pid: { terminus: … } }），顺序与 Java 的 LinkedHashMap 一致。 */
export function nest(flat) {
	const root = {};
	for (const key of Object.keys(flat)) {
		const segments = key.split('.');
		let current = root;
		for (let i = 0; i + 1 < segments.length; i++) {
			const segment = segments[i];
			if (current[segment] === null || typeof current[segment] !== 'object' || Array.isArray(current[segment])) {
				current[segment] = {};
			}
			current = current[segment];
		}
		current[segments[segments.length - 1]] = flat[key];
	}
	return root;
}

/** 派生量：只由已就绪的原始量算；缺料就不放（与"取不到 = 不放进去"同一条口径）。 */
export function derive(values) {
	const speed = asNumber(values.speed);
	if (speed !== null) {
		values.speedKmh = speed * 3600;
	}
	return values;
}

/** 一句话描述这份数据（"字段=值"按登记顺序，嵌套的用点号）—— 与 MmtrFaceData.describe 同一形状。 */
export function describe(data) {
	const parts = [];
	flatten(parts, '', data);
	return parts.join(' ');
}

function flatten(parts, prefix, map) {
	if (map === null || typeof map !== 'object' || Array.isArray(map)) {
		return;
	}
	for (const key of Object.keys(map)) {
		const value = map[key];
		if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
			flatten(parts, prefix + key + '.', value);
		} else {
			parts.push(prefix + key + '=' + asString(value));
		}
	}
}
