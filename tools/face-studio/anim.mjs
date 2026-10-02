/*
 * anim.mjs —— 逐帧动画（notes/359 · F3）：一个元素随时间怎么变。
 *
 * 这是 MmtrFaceAnim.java 的逐条移植。这个类是**纯数学**（不碰画布、不碰游戏），
 * 所以离线用例能把它钉死，而 conformance/document.json 里有它那几条共享向量。
 * 三个量分开表达，画法按自己的需要取：
 *
 *   · visible(timeMs)          —— 该不该画（blink）；
 *   · phase / offsetFraction   —— 走到一个周期的哪儿（marquee 算位移、spin 算角度）；
 *   · alpha(timeMs)            —— 透明度（fade）。
 *
 * 时间轴是**墙钟毫秒**（不是游戏 tick）：tick 会随卡顿变慢，那样闪烁会跟着掉帧一起变慢。
 *
 * 缺省值从 schema.mjs 的键表读（`anim:<kind>` 那几节），于是"文档里写的缺省"与"这里兜底的缺省"
 * 是同一个数（selftest 会拿 schema.json 逐键比）。
 */

import { defaultNumber, defaultString } from './schema.mjs';

/** 没有动画的文档用的节拍（也就是"有动画时才用得上"的缺省值）。 */
export const DEFAULT_FPS = 8;
/** 允许的最大节拍。 */
export const MAX_FPS = 30;

/** 认得的动画名（与 MmtrFaceSchema.animKinds 逐项同序；selftest 会比对）。 */
const KIND_NAMES = ['blink', 'marquee', 'fade', 'spin'];

/** 认得的动画名（拷贝一份给工具/补全用）。 */
export function kinds() {
	return KIND_NAMES.slice();
}

/** `anim.kind` 的名字（小写）；不认得返回 null。 */
export function parseKind(value) {
	const name = String(value === null || value === undefined ? '' : value).trim().toLowerCase();
	return KIND_NAMES.includes(name) ? name : null;
}

/** 一个动画。字段名与 Java 的 record 一一对应（rightwards = direction 不是 right）。 */
export class Anim {
	constructor(init) {
		this.kind = init.kind;
		this.onMs = init.onMs;
		this.offMs = init.offMs;
		this.spanMs = init.spanMs;
		this.rightwards = init.rightwards;
		this.gapRatio = init.gapRatio;
		this.minAlpha = init.minAlpha;
	}

	/** 有没有"随时间变"这件事（没有的话文档根本不用带时间桶）。 */
	animates() {
		switch (this.kind) {
			case 'blink':
				return this.onMs + this.offMs > 0;
			default:
				// marquee / fade / spin：spanMs 已经钳到 >= 1，所以永远为真
				return this.spanMs > 0;
		}
	}

	/** blink：这一瞬间画不画。别的动画永远画（它们只改位移/透明度）。 */
	visible(timeMs) {
		if (this.kind !== 'blink') {
			return true;
		}
		const total = this.onMs + this.offMs;
		if (total <= 0) {
			// 两个都写 0 = "配了 blink 但没有任何时间"：当静态显示（比闪成"永远不画"更接近作者的本意）
			return true;
		}
		return modulo(timeMs, total) < this.onMs;
	}

	/** 在一个周期里走到哪儿（0..1）。blink 的周期是 onMs + offMs，其余是 spanMs。 */
	phase(timeMs) {
		const period = this.kind === 'blink' ? this.onMs + this.offMs : this.spanMs;
		if (period <= 0) {
			return 0;
		}
		// ★ modulo 给的是"周期里的余数"（毫秒），要除的是**周期**，不是余数
		// （这里少写一次除法曾让 phase 恒为 1：marquee 一上来就走完了、fade 也不闪了）
		return modulo(timeMs, period) / period;
	}

	/**
	 * marquee 的位移比例（0..1）：0 = 文本刚从视口右边进来，1 = 完全走出视口左边。
	 * 方向 right 时反过来（1 → 0），由画法决定怎么用。
	 */
	offsetFraction(timeMs) {
		if (this.kind !== 'marquee' || this.spanMs <= 0) {
			return 0;
		}
		const travelled = modulo(timeMs, this.spanMs) / this.spanMs;
		return this.rightwards ? 1 - travelled : travelled;
	}

	/** fade 的透明度；别的动画返回 1。 */
	alpha(timeMs) {
		if (this.kind !== 'fade') {
			return 1;
		}
		const p = this.phase(timeMs);
		// 三角波：0 → 1 → 0（比锯齿波少一次"啪"的突跳）。p 有极小的负数可能（时钟为负），所以先钳一下
		const clamped = p <= 0 ? 0 : (p >= 1 ? 1 : p);
		const triangle = clamped < 0.5 ? clamped * 2 : (1 - clamped) * 2;
		return this.minAlpha + (1 - this.minAlpha) * triangle;
	}

	/** spin 的角度（度，0..360）；别的动画返回 0。 */
	degrees(timeMs) {
		return this.kind === 'spin' ? this.phase(timeMs) * 360 : 0;
	}
}

/**
 * 读一个元素的 `anim` 段；没有 / 不认得 / 不是一个对象 ⇒ **null**（= 没有动画），不抛异常。
 *
 * ★ 为什么刻意不抛：资源包是不可信内容，一个拼错的 `"blnik"` 应该让这块牌**照常显示**（静态），
 * 而不是整块不画。这与"不认识的元素类型只跳过那一个元素"是同一条口径。
 */
export function parseAnim(raw) {
	if (raw === null || raw === undefined || typeof raw !== 'object' || Array.isArray(raw)) {
		return null;
	}
	const kind = parseKind(raw.kind);
	if (kind === null) {
		return null;
	}
	// 没写 spanMs 时每个动画各有一个缺省：blink 用 onMs + offMs 算周期，所以 1200 只是个占位
	const defaultSpan = kind === 'blink' ? 1200 : defaultNumber('anim:' + kind, 'spanMs', 4000);
	return new Anim({
		kind,
		onMs: Math.max(0, numberOr(raw, 'onMs', defaultNumber('anim:' + kind, 'onMs', 600))),
		offMs: Math.max(0, numberOr(raw, 'offMs', defaultNumber('anim:' + kind, 'offMs', 600))),
		spanMs: Math.max(1, numberOr(raw, 'spanMs', defaultSpan)),
		rightwards: rightwardsOf(raw),
		gapRatio: Math.max(0, numberOr(raw, 'gap', defaultNumber('anim:' + kind, 'gap', 0.3))),
		minAlpha: Math.min(1, Math.max(0, numberOr(raw, 'min', defaultNumber('anim:' + kind, 'min', 0.25)))),
	});
}

/** 没有动画的元素身上那个"没有"（用一个常量，方便画法比较 identity）。 */
export const NO_ANIM = null;

/**
 * 时间桶：把毫秒量化到 fps 分之一秒。
 *
 * 重画签名用它 —— 同一个桶里算出来的签名相同，于是不会重画。fps 超出 1..MAX_FPS 时先用缺省值
 * DEFAULT_FPS（作者写 fps: 0 的意思是"我没想那么多"，而不是"每秒重画 0 次"那么抠）。
 */
export function bucket(timeMs, fps) {
	const effective = Number.isFinite(fps) && fps >= 1 && fps <= MAX_FPS ? Math.trunc(fps) : DEFAULT_FPS;
	// Java 是 Math.floorDiv(Math.max(0, timeMs) * effective, 1000)：先乘后除，不先取整
	return Math.floor((Math.max(0, numberOr0(timeMs)) * effective) / 1000);
}

/** Java 的 % 对负数会给负余数，动画时间要的是"永远往前走"的那种取模。 */
function modulo(timeMs, period) {
	const value = numberOr0(timeMs) % period;
	return value < 0 ? value + period : value;
}

function numberOr0(value) {
	return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

/** 读一个数字键：写数字就用，别的一律用缺省（动画参数写坏了不该让整块面消失）。 */
function numberOr(object, name, fallback) {
	if (!Object.prototype.hasOwnProperty.call(object, name) || object[name] === null) {
		return fallback;
	}
	const value = object[name];
	if (typeof value === 'number' && Number.isFinite(value)) {
		return value;
	}
	if (typeof value === 'string') {
		const text = value.trim();
		if (/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(text)) {
			return Number(text);
		}
	}
	return fallback;
}

/**
 * 方向：只有**明确写了 right**（大小写/空白不敏感）才反过来走，其余一律按 leftwards 那套。
 * 缺省从键表读（`anim:marquee` 的 direction）—— 作者没写时用键表里那个 "left"。
 */
function rightwardsOf(raw) {
	const declared = Object.prototype.hasOwnProperty.call(raw, 'direction') && raw.direction !== null
		? String(raw.direction)
		: defaultString('anim:marquee', 'direction', 'left');
	return declared.trim().toLowerCase() === 'right';
}
