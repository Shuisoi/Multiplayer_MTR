/*
 * text.mjs —— 车辆动态面的**文本层**：模板 `{字段|过滤器}` 的解析与过滤器（notes/359）。
 *
 * 这是 MmtrFaceText.java 的逐条移植。两条口径照抄：
 *   1. **取不到 = 空串**。模板不报错、不画 null；"这一行整行不画"由元素上的 when / shrink 之外那层
 *      （hideWhenEmpty 语义，当前落在 MmtrFaceDocument.texts 里）表达。
 *   2. **数字默认不写小数点后缀**：1.0 写成 "1"（与 cat、与 Java 的 asString 同一口径）。
 */

import { asNumber, asString, lookup } from './logic.mjs';

/** 花括号转义：{{ }} 写出一个字面量花括号。 */
export const ESCAPE_OPEN = '{{';
export const ESCAPE_CLOSE = '}}';

/**
 * 支持的过滤器名 —— 与 Java `MmtrFaceText.filters()` 逐项同序相同。
 * （selftest.mjs 会拿它和 tools/anchor-check/verify_face.js 里那份逐项核对。）
 */
export const FILTERS = ['upper', 'lower', 'trim', 'int', 'num', 'pad', 'len', 'default'];

/** 过滤器清单的副本（给工具/补全用）。 */
export function filters() {
	return FILTERS.slice();
}

/**
 * 把模板里的 `{路径}` / `{路径|过滤器}` 换成数据里的值。
 *
 * @param template 模板（null/空 ⇒ 空串）
 * @param data     数据（与 {"var": …} 同一套路径，见 logic.mjs 的 lookup）
 */
export function resolve(template, data) {
	if (template === null || template === undefined || template === '') {
		return '';
	}
	const text = String(template);
	let out = '';
	let index = 0;
	while (index < text.length) {
		const character = text[index];
		if (character === '{') {
			if (text.startsWith(ESCAPE_OPEN, index)) {
				out += '{';
				index += 2;
				continue;
			}
			const close = text.indexOf('}', index + 1);
			if (close < 0) {
				// 没有闭合花括号：当作普通文本（不吞掉后半句）
				out += text.substring(index);
				break;
			}
			out += resolvePlaceholder(text.substring(index + 1, close), data);
			index = close + 1;
		} else if (character === '}' && text.startsWith(ESCAPE_CLOSE, index)) {
			out += '}';
			index += 2;
		} else {
			out += character;
			index++;
		}
	}
	return out;
}

/** 一个占位符：路径 或 路径|过滤器|过滤器。 */
function resolvePlaceholder(placeholder, data) {
	// Java 用 String.split("\\|")：尾部的空段会被丢掉，而 applyFilter("") 原样返回值 ——
	// JS 的 split 会留下尾部空串，但过滤器名空 ⇒ 空转，结果一样。
	const parts = placeholder.split('|');
	const path = parts[0].trim();
	let value = path === '' ? data : lookup(path, data);
	for (let i = 1; i < parts.length; i++) {
		value = applyFilter(value, parts[i].trim());
	}
	return asString(value);
}

/**
 * 单个过滤器。不认识的过滤器**原样返回**（Java 那边会记一条日志）——比静默换成空串好：
 * 作者看到字还在，只是没按他想的格式化，配合日志就能定位。
 */
export function applyFilter(value, filter) {
	if (filter === null || filter === undefined || filter === '') {
		return value;
	}
	const colon = filter.indexOf(':');
	const name = (colon < 0 ? filter : filter.substring(0, colon)).toLowerCase();
	const parameter = colon < 0 ? '' : filter.substring(colon + 1);

	switch (name) {
		case 'upper':
			return asString(value).toUpperCase();
		case 'lower':
			return asString(value).toLowerCase();
		case 'trim':
			return asString(value).trim();
		case 'int':
			return numberFilter(value, 0, true);
		case 'num':
			return numberFilter(value, parseDigits(parameter, 1), false);
		case 'pad':
			// ★ 位宽与 num 的小数位**分开解析**（2026-10-02 与 Java 同步修）：共用那个 0..4 的钳位
			// 会让 `pad:8` 只补 4 位、`{x|pad:6}` 补不到 6 位。
			return pad(asString(value), parseWidth(parameter));
		case 'len':
			// Java 的 String.length 与 JS 的 .length 都是 UTF-16 码元数
			return String(asString(value).length);
		case 'default':
			return asString(value) === '' ? parameter : value;
		default:
			return value;
	}
}

function numberFilter(value, digits, round) {
	const number = asNumber(value);
	if (number === null) {
		return value;
	}
	return round ? roundedText(number) : decimalText(number, digits);
}

/** 保留小数的文本；digits <= 0 时退回"整数不写 .0"的通用口径。 */
export function decimalText(value, digits) {
	if (digits <= 0) {
		// ★ 2026-10-02 与 Java 同步修：`num:0` 是**取整**，不是"退回通用文本"
		// （原来这里 return asString(value)，于是 {x|num:0} 把 12.345 原样写出来）。
		return value.toFixed(0);
	}
	const places = Math.max(0, Math.min(4, Math.trunc(digits)));
	// Java 用 String.format("%.Nf")。toFixed 与它在十进制上同源；差异只在"恰好半个最低位"
	// 那种二进制里本来就表示不出来的数（如 1.005），两边都按自己那份二进制取近似。
	return value.toFixed(places);
}

/** Java 的 Math.round(double)（floor(x + 0.5)）然后 String.valueOf(long)。 */
function roundedText(value) {
	if (Number.isNaN(value)) {
		// Java: Math.round(NaN) == 0
		return '0';
	}
	if (value >= 9223372036854775807) {
		return '9223372036854775807';
	}
	if (value <= -9223372036854775808) {
		return '-9223372036854775808';
	}
	const rounded = Math.floor(value + 0.5);
	return String(rounded === 0 ? 0 : rounded);
}

function pad(text, width) {
	if (width <= text.length) {
		return text;
	}
	return '0'.repeat(width - text.length) + text;
}

/** Java 的 Integer.parseInt(parameter.trim())，失败用 fallback；成功后再钳到 0..4。 */
/** `pad:N` 的**位宽**（1..16，与 num 的小数位分开 —— 见上面 case 'pad' 的注释）。 */
function parseWidth(parameter) {
	const width = Number.parseInt(String(parameter ?? '').trim(), 10);
	return Number.isNaN(width) ? 0 : Math.max(0, Math.min(16, width));
}

function parseDigits(parameter, fallback) {	const text = String(parameter).trim();
	if (!/^[+-]?\d+$/.test(text)) {
		return fallback;
	}
	const parsed = Number(text);
	return Math.max(0, Math.min(4, Math.trunc(parsed)));
}
