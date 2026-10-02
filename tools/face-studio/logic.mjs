/*
 * logic.mjs —— 车辆动态面的**逻辑层**：JSONLogic 子集求值器（notes/359）。
 *
 * 这是 MmtrFaceLogic.java 的**逐条移植**，不是"重新设计"：口径（真值、宽松/严格相等、比较遇 null、
 * 类型不匹配的算术、除零、路径取值、多键对象 = and、节点/深度预算）全部照抄 Java 注释里那条判据面，
 * 并由 conformance/logic.json 的共享向量钉住 —— 两端跑同一份向量，谁漂了自检立刻红。
 *
 * 为什么零依赖、为什么不用现成的 npm 实现：见 MmtrFaceLogic 的类注释（资源包不可信 + 引擎不背 jar）。
 * 这里连一个 import 都没有，浏览器与 Node 都能直接加载。
 */

/** 一次求值的节点预算（防"资源包里一个刻意写深的表达式"）。 */
export const MAX_NODES = 512;
/** 一次求值的嵌套深度预算。 */
export const MAX_DEPTH = 24;

/** 表达式用了不认识的算子。消息与 Java 的 UnknownOperatorException 同一条。 */
export class UnknownOperatorError extends Error {
	constructor(operator) {
		super('面文档用了不认识的算子：' + operator);
		this.name = 'UnknownOperatorError';
		this.operator = operator;
	}
}

/** 表达式超出预算（节点数或深度）。 */
export class BudgetExceededError extends Error {
	constructor(message) {
		super(message);
		this.name = 'BudgetExceededError';
	}
}

/**
 * 这个子集支持的算子名 —— 与 Java `MmtrFaceLogic.operators()` **逐项同序**相同。
 * （selftest.mjs 会拿它和 tools/anchor-check/verify_face.js 里那份逐项核对。）
 */
export const OPERATORS = ['var', 'if', 'and', 'or', '!', '!!',
	'==', '!=', '===', '!==', '<', '<=', '>', '>=',
	'+', '-', '*', '/', '%', 'min', 'max',
	'cat', 'substr', 'in', 'missing', 'missing_some', '?:',
	'some', 'all', 'none', 'filter', 'map'];

/** 算子清单的副本（给工具/补全用；改这个清单请改上面的常量）。 */
export function operators() {
	return OPERATORS.slice();
}

/** 严格整数（Java 的 Integer.parseInt 口径：不认 "2.5"，认 "01" 与 "+2"）。 */
const INTEGER_PATTERN = /^[+-]?\d+$/;
/** Java Double.parseDouble 认的那些十进制/科学计数形状（十六进制浮点与 1f/1d 后缀没移植，见下）。 */
const DECIMAL_PATTERN = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/;

/**
 * 求值一条表达式。
 *
 * @param expression JSONLogic 表达式（可以是字面量；对象 = 算子调用，数组 = 逐项求值）
 * @param data       数据（普通对象/数组/标量）；{"var": "a.b"} 在其中按点号路径取值
 * @returns 求值结果：number/string/boolean/数组/对象/null
 */
export function evaluate(expression, data) {
	return evalNode(expression, data, 0, [0]);
}

function evalNode(node, data, depth, nodes) {
	if (node === null || node === undefined) {
		return null;
	}
	if (depth > MAX_DEPTH) {
		throw new BudgetExceededError('面文档的表达式嵌套超过 ' + MAX_DEPTH + ' 层');
	}
	if (++nodes[0] > MAX_NODES) {
		throw new BudgetExceededError('面文档的表达式超过 ' + MAX_NODES + ' 个节点');
	}
	if (Array.isArray(node)) {
		return node.map(child => evalNode(child, data, depth + 1, nodes));
	}
	if (typeof node === 'object') {
		return evalObject(node, data, depth, nodes);
	}
	return literal(node);
}

/**
 * 一个对象 = 一次算子调用。多键对象按 JSONLogic 的规矩视作 and：**依次求值，
 * 一旦上一个结果已经为假就停**（返回那个假值），全真返回最后一个。
 *
 * 注意这里不是"先看条件再决定要不要算"：假值那一个键本身仍然会被求值（Java 的循环就是这么写的），
 * 所以 {"!!": [x], ">": [y, 1]} 会算完 "!!"，发现是假之后才跳过 ">"。
 */
function evalObject(object, data, depth, nodes) {
	const keys = Object.keys(object);
	if (keys.length === 0) {
		return null;
	}
	let result = null;
	let first = true;
	for (const key of keys) {
		if (!first && !truthy(result)) {
			return result;
		}
		result = applyOperator(key, object[key], data, depth, nodes);
		first = false;
	}
	return result;
}

function applyOperator(operator, argument, data, depth, nodes) {
	switch (operator) {
		case 'var': {
			const values = args(argument, data, depth, nodes);
			const path = asString(values.length > 0 ? values[0] : null);
			const value = path === '' ? data : lookup(path, data);
			return value !== null && value !== undefined ? value : (values.length > 1 ? values[1] : null);
		}
		case 'if': {
			const values = args(argument, data, depth, nodes);
			let result = null;
			for (let i = 0; i + 1 < values.length; i += 2) {
				if (truthy(values[i])) {
					result = values[i + 1];
					break;
				}
				// 落到底：奇数个参数 ⇒ 最后一个是兜底
				result = i + 2 === values.length - 1 ? values[i + 2] : null;
			}
			return result;
		}
		case 'and': {
			let result = null;
			for (const value of args(argument, data, depth, nodes)) {
				result = value;
				if (!truthy(value)) {
					break;
				}
			}
			return result;
		}
		case 'or': {
			let result = null;
			for (const value of args(argument, data, depth, nodes)) {
				result = value;
				if (truthy(value)) {
					break;
				}
			}
			return result;
		}
		case '!':
			return !truthy(first(argument, data, depth, nodes));
		case '!!':
			return truthy(first(argument, data, depth, nodes));
		case '==':
			return chainEquals(argument, data, depth, nodes, true);
		case '!=':
			return !chainEquals(argument, data, depth, nodes, true);
		case '===':
			return chainEquals(argument, data, depth, nodes, false);
		case '!==':
			return !chainEquals(argument, data, depth, nodes, false);
		case '<':
			return chainCompare(argument, data, depth, nodes, -1, false);
		case '<=':
			return chainCompare(argument, data, depth, nodes, -1, true);
		case '>':
			return chainCompare(argument, data, depth, nodes, 1, false);
		case '>=':
			return chainCompare(argument, data, depth, nodes, 1, true);
		case '+':
			return arithmetic(argument, data, depth, nodes, 0);
		case '*':
			return arithmetic(argument, data, depth, nodes, 1);
		case '-': {
			const values = args(argument, data, depth, nodes);
			if (values.length === 1) {
				const one = asNumber(values[0]);
				return one === null ? null : -one;
			}
			return binaryArithmetic(values, 2);
		}
		case '/':
			return binaryArithmetic(args(argument, data, depth, nodes), 3);
		case '%':
			return binaryArithmetic(args(argument, data, depth, nodes), 4);
		case 'min':
		case 'max': {
			let result = null;
			for (const value of args(argument, data, depth, nodes)) {
				const number = asNumber(value);
				if (number !== null) {
					result = result === null ? number : (operator === 'min' ? Math.min(result, number) : Math.max(result, number));
				}
			}
			return result;
		}
		case 'cat': {
			let out = '';
			for (const value of args(argument, data, depth, nodes)) {
				out += asString(value);
			}
			return out;
		}
		case 'substr': {
			const values = args(argument, data, depth, nodes);
			const text = asString(values.length > 0 ? values[0] : null);
			const start = truncateToInt(numberOr(values.length > 1 ? values[1] : null, 0));
			// 负起点 = 从尾部数（与 JS 的 slice 同义，JSONLogic 的 substr 也是这么转发的）
			const from = start < 0 ? Math.max(0, text.length + start) : Math.min(start, text.length);
			const to = values.length > 2 && asNumber(values[2]) !== null
				? Math.min(text.length, from + truncateToInt(numberOr(values[2], 0)))
				: text.length;
			return text.substring(from, Math.max(from, to));
		}
		case 'in': {
			const values = args(argument, data, depth, nodes);
			return inOp(values.length > 0 ? values[0] : null, values.length > 1 ? values[1] : null);
		}
		case 'missing':
			return missingOp(args(argument, data, depth, nodes), data, -1);
		case 'missing_some': {
			const values = args(argument, data, depth, nodes);
			const required = truncateToInt(numberOr(values.length > 0 ? values[0] : null, 0));
			// 第二个参数是**一个数组**：args 会把它求值成一个数组，直接拿来当键表用
			const keys = values.length > 1 && Array.isArray(values[1]) ? values[1].slice() : [];
			return missingOp(keys, data, required);
		}
		case '?:': {
			const values = args(argument, data, depth, nodes);
			return truthy(values.length > 0 ? values[0] : null)
				? (values.length > 1 ? values[1] : null)
				: (values.length > 2 ? values[2] : null);
		}
		case 'some':
			return iterateArray(argument, data, depth, nodes, 1);
		case 'all':
			return iterateArray(argument, data, depth, nodes, 2);
		case 'none':
			return iterateArray(argument, data, depth, nodes, 3);
		case 'filter':
			return iterateArray(argument, data, depth, nodes, 4);
		case 'map':
			return iterateArray(argument, data, depth, nodes, 5);
		default:
			throw new UnknownOperatorError(operator);
	}
}

/** 取算子参数：数组 = 逐个求值（数组里每一项都是表达式），非数组 = 单参数。 */
function args(argument, data, depth, nodes) {
	if (Array.isArray(argument)) {
		return argument.map(element => evalNode(element, data, depth + 1, nodes));
	}
	return [evalNode(argument, data, depth + 1, nodes)];
}

function first(argument, data, depth, nodes) {
	const values = args(argument, data, depth, nodes);
	return values.length > 0 ? values[0] : null;
}

function chainEquals(argument, data, depth, nodes, loose) {
	const values = args(argument, data, depth, nodes);
	if (values.length < 2) {
		return values.length === 1 && (loose ? looseEquals(values[0], null) : values[0] === null);
	}
	for (let i = 0; i + 1 < values.length; i++) {
		if (!(loose ? looseEquals(values[i], values[i + 1]) : strictEquals(values[i], values[i + 1]))) {
			return false;
		}
	}
	return true;
}

function chainCompare(argument, data, depth, nodes, sign, orEqual) {
	const values = args(argument, data, depth, nodes);
	for (let i = 0; i + 1 < values.length; i++) {
		const comparison = compare(values[i], values[i + 1]);
		if (comparison === null || (comparison !== 0 && Math.sign(comparison) !== sign) || (comparison === 0 && !orEqual)) {
			return false;
		}
	}
	return values.length >= 2;
}

function arithmetic(argument, data, depth, nodes, identity) {
	const values = args(argument, data, depth, nodes);
	if (values.length === 0) {
		return identity;
	}
	let result = identity;
	for (const value of values) {
		const number = asNumber(value);
		if (number === null) {
			return null;
		}
		result = identity === 0 ? result + number : result * number;
	}
	return result;
}

function binaryArithmetic(values, kind) {
	if (values.length < 2) {
		return null;
	}
	const left = asNumber(values[0]);
	const right = asNumber(values[1]);
	if (left === null || right === null) {
		return null;
	}
	switch (kind) {
		case 2:
			return left - right;
		case 3:
			return right === 0 ? null : left / right;
		default:
			return right === 0 ? null : left % right;
	}
}

/**
 * 数组遍历算子：some(1)/all(2)/none(3) 返回布尔，filter(4)/map(5) 返回数组。
 * 谓词里的 var 相对于**当前元素**（JSONLogic 的口径），且谓词**只对元素求值**，不先对外层数据求一遍。
 */
function iterateArray(argument, data, depth, nodes, kind) {
	if (!Array.isArray(argument) || argument.length < 2) {
		return emptyIteration(kind);
	}
	const collection = evalNode(argument[0], data, depth + 1, nodes);
	if (!Array.isArray(collection)) {
		// 不是数组：some = 没命中（假）、all/none = 没有反例（真）、filter/map = 空数组
		return emptyIteration(kind);
	}
	const predicate = argument[1];
	const out = [];
	for (const item of collection) {
		const itemResult = evalNode(predicate, item, depth + 1, nodes);
		const matched = truthy(itemResult);
		if (kind === 1 && matched) {
			return true;
		}
		if (kind === 2 && !matched) {
			return false;
		}
		if (kind === 3 && matched) {
			return false;
		}
		if (kind === 5) {
			out.push(itemResult);
		} else if (kind === 4 && matched) {
			out.push(item);
		}
	}
	switch (kind) {
		case 1:
			return false;
		case 2:
		case 3:
			return true;
		default:
			return out;
	}
}

/** 数组遍历算子的"没有可遍历的东西"结果：some = 假、all/none = 真、filter/map = 空数组。 */
function emptyIteration(kind) {
	return kind === 4 || kind === 5 ? [] : (kind === 2 || kind === 3);
}

function missingOp(keys, data, required) {
	const absent = [];
	let present = 0;
	for (const key of keys) {
		const value = lookup(asString(key), data);
		if (value === null || value === undefined || asString(value) === '') {
			absent.push(asString(key));
		} else {
			present++;
		}
	}
	// missing_some：够 required 个就返回空数组（"缺的够不够用"而不是"缺哪些"）
	return required > 0 && present >= required ? [] : absent;
}

/** {"var": "a.b.c"} 的取值：点号路径，数组可按下标；取不到 = null。 */
export function lookup(path, data) {
	let current = data;
	for (const segment of splitPath(path)) {
		if (current === null || current === undefined) {
			return null;
		}
		current = child(current, segment);
	}
	return current === null || current === undefined ? null : current;
}

/**
 * 按点号切路径。用 Java `String.split("\\.")` 的口径：**尾部空串全去掉**（"a." ⇒ ["a"]），
 * 但空路径本身是 [""]（于是 lookup("") 去找名为 "" 的键，与 Java 一致）。
 */
function splitPath(path) {
	const parts = String(path).split('.');
	while (parts.length > 1 && parts[parts.length - 1] === '') {
		parts.pop();
	}
	return parts;
}

function child(container, segment) {
	if (container === null || container === undefined) {
		return null;
	}
	if (Array.isArray(container)) {
		if (!INTEGER_PATTERN.test(segment)) {
			return null;
		}
		const index = Number(segment);
		return index >= 0 && index < container.length ? container[index] : null;
	}
	if (typeof container === 'object') {
		const value = container[segment];
		return value === undefined ? null : value;
	}
	return null;
}

/**
 * 真值口径（与 JSONLogic 一致）：null / false / 0 / 空串 / 空数组 = 假。
 *
 * 刻意**不**把 "0"、"false" 当假 —— 它们是字符串，在 JS 与传统 JSONLogic 里都是真。
 * 这一条反直觉，所以 conformance 里有向量钉着。
 */
export function truthy(value) {
	if (value === null || value === undefined) {
		return false;
	}
	if (Array.isArray(value)) {
		return value.length > 0;
	}
	switch (typeof value) {
		case 'boolean':
			return value;
		case 'number':
			return value !== 0 && !Number.isNaN(value);
		case 'string':
			return value.length > 0;
		default:
			// 空对象在 JS/JSONLogic 里是真（与空数组不同）—— 照抄上游口径
			return true;
	}
}

/** 能当数字就是数字，否则 null（空串、文字、null、数组都不是数字）。 */
export function asNumber(value) {
	if (value === null || value === undefined) {
		return null;
	}
	if (typeof value === 'number') {
		return value;
	}
	if (typeof value === 'boolean') {
		return value ? 1 : 0;
	}
	if (typeof value === 'string') {
		const text = value.trim();
		if (text === '') {
			return null;
		}
		if (DECIMAL_PATTERN.test(text)) {
			return Number(text);
		}
		// Java 的 Double.parseDouble 还认 "NaN"/"Infinity"/十六进制浮点/"1d" 这类后缀；
		// 面文档里的数据是 JSON 数字或普通十进制串，前面三种照收，后缀那种没移植。
		if (text === 'NaN') {
			return NaN;
		}
		if (text === 'Infinity' || text === '+Infinity') {
			return Infinity;
		}
		if (text === '-Infinity') {
			return -Infinity;
		}
		return null;
	}
	return null;
}

function numberOr(value, fallback) {
	const number = asNumber(value);
	return number === null ? fallback : number;
}

/** Java 的 (int) 转换：向零截断；NaN ⇒ 0。 */
function truncateToInt(value) {
	if (Number.isNaN(value)) {
		return 0;
	}
	if (value >= 2147483647) {
		return 2147483647;
	}
	if (value <= -2147483648) {
		return -2147483648;
	}
	return Math.trunc(value);
}

/** 文本口径：整数不写成 1.0（cat 与模板出来的字要能直接上面）。 */
export function asString(value) {
	if (value === null || value === undefined) {
		return '';
	}
	switch (typeof value) {
		case 'number':
			return numberText(value);
		case 'boolean':
			return value ? 'true' : 'false';
		case 'string':
			return value;
		case 'object':
			// 容器：Java 那边 Map/List 的 toString 形状（JsonObject 会印成 JSON，这里是 Map 形状）。
			if (Array.isArray(value)) {
				return '[' + value.map(item => asString(item)).join(', ') + ']';
			}
			return '{' + Object.keys(value).map(key => key + '=' + asString(value[key])).join(', ') + '}';
		default:
			return String(value);
	}
}

function numberText(value) {
	if (!Number.isFinite(value)) {
		return '';
	}
	if (value === Math.trunc(value) && Math.abs(value) < 1.0E15) {
		return String(Math.trunc(value));
	}
	return String(value);
}

/**
 * == ：两边都能当数字 ⇒ 按数字比；否则按字符串比；null 只与 null 相等
 * （不把 null 当 0，也不当空串）。
 */
export function looseEquals(left, right) {
	if (left === null || left === undefined || right === null || right === undefined) {
		return (left === null || left === undefined) && (right === null || right === undefined);
	}
	const leftNumber = asNumber(left);
	const rightNumber = asNumber(right);
	if (leftNumber !== null && rightNumber !== null) {
		return leftNumber === rightNumber;
	}
	return asString(left) === asString(right);
}

/** === ：类型也得一样。 */
export function strictEquals(left, right) {
	if (left === null || left === undefined || right === null || right === undefined) {
		return (left === null || left === undefined) && (right === null || right === undefined);
	}
	const leftType = typeof left;
	const rightType = typeof right;
	if (leftType !== rightType) {
		return false;
	}
	if (leftType === 'number' || leftType === 'string' || leftType === 'boolean') {
		return left === right;
	}
	// 容器一律不等：Java 的 strictEquals 只认 Number/String/Boolean 三条，其余 return false
	return false;
}

/** -1 / 0 / +1；有一边 null（或不同类型且不可数字）时 null。 */
export function compare(left, right) {
	if (left === null || left === undefined || right === null || right === undefined) {
		return null;
	}
	const leftNumber = asNumber(left);
	const rightNumber = asNumber(right);
	if (leftNumber !== null && rightNumber !== null) {
		return doubleCompare(leftNumber, rightNumber);
	}
	if (typeof left === 'string' || typeof right === 'string') {
		const a = asString(left);
		const b = asString(right);
		// Java 的 String.compareTo 按 UTF-16 码元序；JS 的 < / > 也是同一套序
		return a < b ? -1 : (a > b ? 1 : 0);
	}
	return null;
}

/** Java Double.compare 的符号口径（NaN 比谁都大、+0 大于 -0）。 */
function doubleCompare(left, right) {
	if (left < right) {
		return -1;
	}
	if (left > right) {
		return 1;
	}
	if (left === right) {
		if (left === 0) {
			// +0 与 -0：Java 认为 +0 > -0
			return Object.is(left, right) ? 0 : (Object.is(left, 0) ? 1 : -1);
		}
		return 0;
	}
	// 走到这里必然是 NaN 掺在里面
	if (Number.isNaN(left)) {
		return Number.isNaN(right) ? 0 : 1;
	}
	return -1;
}

function inOp(needle, haystack) {
	if (Array.isArray(haystack)) {
		return haystack.some(item => looseEquals(needle, item));
	}
	if (haystack === null || haystack === undefined) {
		return false;
	}
	return asString(haystack).includes(asString(needle));
}

function literal(node) {
	const type = typeof node;
	if (type === 'boolean' || type === 'number' || type === 'string') {
		return node;
	}
	return null;
}
