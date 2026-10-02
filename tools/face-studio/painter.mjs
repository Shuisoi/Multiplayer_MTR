/*
 * painter.mjs —— 把一份面文档画到 HTML <canvas> 的 2D context 上。
 *
 * 这是 MmtrFaceElements.java + MmtrPanelCanvas.java 的移植（F0 的七种画法 + F3 的 v2 画法）。规则照抄：
 *   · 画布坐标是**米**，文档坐标是**比例**：x/w 乘牌宽，y/h/size/radius 乘牌高，y **从下往上**；
 *   · 线宽与圆半径按 min(scaleX, scaleY) 缩放（Java 的 pxWidth / circle 就是这么做的）；
 *   · 弧的角度是"从 +X 起、逆时针为正"（画布 y 向下，所以点先在米空间里算好再翻 y）；
 *   · 谁用文档的 textColor 做缺省色：**只有 text 与 gauge**（rect/line/circle/arc 直接吃 element.color()，
 *     写 0 就是全透明 ⇒ 看不见 —— 这是作者指南里"元素没有颜色"的真相）；
 *   · ★ **颜色里的 0 是哨兵值，不是黑色**：`parseColor("0")` 直接给 0（不铺底 / 用 textColor / 透明），
 *     六位全零 `000000` 才是不透明黑 `0xFF000000`。所以下面每处 `color === 0` / `background !== 0`
 *     的分支都是在读那个哨兵，而不是在判"黑不黑"（2026-10-02 与 Java 的 parseColor 同步）；
 *   · shrinkToFit 缺省 0.9：超宽按牌宽等比缩小；取不到内容的文本行不画（不是画个空位）；
 *   · 一个元素画坏/类型不认识 ⇒ 跳过那一个并把原因记进返回值，其余照画。
 *
 * v2：必须按 plan(document, data, timeMs) 画（选页与 forEach 展开都在那份计划里，**画法不认识"重复"这件事**），
 * 并支持 opacity / rotate / anim（blink 灭着不画、marquee 走视口、fade 透明度）/ image（含装图方式与色调）。
 *
 * ★★ 三处**预览近似**（近似可以，但不许假装等价 —— 真几何在 Java 那边，这里只把"作者想确认的东西"摆出来）：
 *   1. roll / tilt（整面姿态）：真实现把整块四边形绕自身法线/水平轴转（MmtrPanelQuad 的四角），
 *      这里只做 2D 近似 —— roll = 整幅画布绕中心旋转、tilt = 绕牌底边竖向压缩 cos(tilt)；
 *   2. drum（翻牌机）：面的**角度**用的是真几何（geometry.mjs 的 drumAngle，与 Java 同一条），
 *      但把它画成 2D 的方式是近似的 —— 只在"这一帧正对的那一面"两侧画两条侧边压扁，
 *      不做棱柱的透视投影（真投影在 Java 的 N 个四边形里）；
 *   3. 字形：牌上的字在游戏里用 Java2D 的 DIN 1451 / HarmonyOS Sans 渲染，浏览器里只能用系统字体，
 *      所以"同一字号下的墨迹宽度"是近似 —— 版式（位置/对齐/字号比例）一致，字形的胖瘦会差一点。
 */

import { asNumber, evaluate, lookup, truthy } from './logic.mjs';
import { defaultNumber, defaultString } from './schema.mjs';
import { augment, parseColor, plan as planDrawables } from './document.mjs';
import { resolve as resolveTemplate } from './text.mjs';
// 纯几何从 geometry.mjs 来（它有一份自己的共享向量 conformance/geometry.json）：
// fitRect / marqueeLeft / drumAngle 只在这里**用**，不在这里**另外定义一份算术**。
import { FITS, drumAngle, fitRect, marqueeLeft } from './geometry.mjs';

export { FITS, drumAngle, fitRect, marqueeLeft };

// Node 的路径工具：**只在 Node 里**存在（浏览器解析本模块时这两个 import 会解析成 null）。
// 为什么要动态 import：静态 `import 'node:path'` 会让浏览器直接解析失败（整个工作室起不来）。
const nodeFs = await importAsOptional('node:fs');
const nodePath = await importAsOptional('node:path');
const nodeUrl = await importAsOptional('node:url');

/** 本模块所在的目录（Node 下用来往上找 run/resourcepacks；浏览器里 = null）。 */
const MODULE_DIR = nodePath !== null && nodeUrl !== null
	? nodePath.dirname(nodeUrl.fileURLToPath(import.meta.url))
	: null;

/** 动态 import 一个"可能不存在"的模块：浏览器里没有 node: 前缀的东西，拿不到就 null。 */
async function importAsOptional(specifier) {
	try {
		return await import(specifier);
	} catch (e) {
		return null;
	}
}

/**
 * 认得的元素类型（小写），与 schema.json 的 `elementTypes` **逐项同序**相同
 * （那是 Java MmtrFaceSchema.ELEMENT_TYPES 导出来的，也就是 MmtrFaceElements.PAINTERS 的登记表）。
 * selftest.mjs 会拿它和 schema.json 逐项核对。
 *
 * 注：`foreach` 在这里"认得"但**没有画法** —— 它由 plan() 展开掉，正常情况下永远到不了画法这一层；
 * 到得了（嵌套超过深度上限）就是一条要报出来的问题。
 */
export const ELEMENT_TYPES = ['text', 'rect', 'roundrect', 'line', 'circle', 'arc', 'gauge', 'image', 'foreach'];

/** 牌上没有字时用什么字体栈（游戏里是 DIN 1451 + HarmonyOS Sans，浏览器里退到系统里最接近的）。 */
const FONT_STACK = '"HarmonyOS Sans SC","Noto Sans SC","Source Han Sans SC","Microsoft YaHei","PingFang SC",sans-serif';

/** 0xAARRGGBB（有符号 int32 也行）→ CSS 颜色。 */
export function cssColor(argb) {
	const value = (Number(argb) | 0) >>> 0;
	const alpha = ((value >>> 24) & 0xFF) / 255;
	const red = (value >>> 16) & 0xFF;
	const green = (value >>> 8) & 0xFF;
	const blue = value & 0xFF;
	return 'rgba(' + red + ',' + green + ',' + blue + ',' + alpha.toFixed(3) + ')';
}

/** 0xAARRGGBB → 同样的颜色但换一个 alpha（opacity 与 fade 就乘在这一层上）。 */
export function cssColorWithAlpha(argb, alpha) {
	const value = (Number(argb) | 0) >>> 0;
	const base = ((value >>> 24) & 0xFF) / 255;
	const red = (value >>> 16) & 0xFF;
	const green = (value >>> 8) & 0xFF;
	const blue = value & 0xFF;
	return 'rgba(' + red + ',' + green + ',' + blue + ',' + (base * clamp01(alpha)).toFixed(4) + ')';
}

/**
 * 画一整块面：先铺底（background 为 **哨兵值 0** = 不铺，让模型的材质透出来），再按顺序画每个"该画"的元素。
 *
 * @param ctx         canvas 的 2D context（函数会按 widthM/heightM/pxPerMetre 重设 canvas 的像素尺寸）
 * @param document    document.mjs 的 FaceDocument
 * @param data        数据（扁平表要先 nesting，见 presets.mjs 的 buildData）
 * @param widthM      牌宽（米）
 * @param heightM     牌高（米）
 * @param pxPerMetre  像素密度；<= 0 时用 512（与 FacePreview 的 DEFAULT_PX_PER_METRE 一致）
 * @param timeMs      时钟（毫秒）；缺省 0（静态那一帧）
 * @returns {{widthPx:number, heightPx:number, painted:number, whenFalse:number, blank:number, problems:string[],
 *            pages:number, page:number, animated:boolean, rolling:boolean, thinned:number}}
 *          painted = 画法真的落笔的元素数；whenFalse = 条件不成立而没画；blank = 条件成立但这一笔画不出东西；
 *          pages/page = 当前页信息（多页时页面会显示）；rolling = 画了整面姿态近似；
 *          thinned = 图还没加载完、先画了占位（页面据此稍后再重画一次）；problems = 被跳过的原因。
 */
export function paint(ctx, document, data, widthM, heightM, pxPerMetre, timeMs = 0) {
	const problems = [];
	const report = {
		widthPx: 1, heightPx: 1, painted: 0, whenFalse: 0, blank: 0, problems,
		pages: 1, page: 0, animated: false, rolling: false, thinned: 0,
	};
	if (!ctx || !document) {
		return report;
	}
	const clock = numberOr(timeMs, 0);
	const ppm = Number.isFinite(pxPerMetre) && pxPerMetre > 0 ? pxPerMetre : 512;
	const safeWidthM = Math.max(Number(widthM) || 0, 1.0E-3);
	const safeHeightM = Math.max(Number(heightM) || 0, 1.0E-3);
	const widthPx = Math.max(1, Math.round(safeWidthM * ppm));
	const heightPx = Math.max(1, Math.round(safeHeightM * ppm));

	// 改 canvas 的尺寸会重置 context 状态，所以顺序是：先定尺寸，再设画笔
	if (ctx.canvas.width !== widthPx) {
		ctx.canvas.width = widthPx;
	}
	if (ctx.canvas.height !== heightPx) {
		ctx.canvas.height = heightPx;
	}
	ctx.setTransform(1, 0, 0, 1, 0, 0);
	ctx.clearRect(0, 0, widthPx, heightPx);

	const surface = {
		ctx,
		widthM: safeWidthM,
		heightM: safeHeightM,
		widthPx,
		heightPx,
		sx: widthPx / safeWidthM,
		sy: heightPx / safeHeightM,
		minScale: Math.min(widthPx / safeWidthM, heightPx / safeHeightM),
		sizeM: Math.max(safeWidthM, safeHeightM),
		thinned: 0,
	};
	report.widthPx = widthPx;
	report.heightPx = heightPx;

	// ---- 整面姿态（近似，见文件头注释） ---------------------------------------------------------
	const faceTransform = faceMatrix(surface, document);
	const rolling = faceTransform !== null;
	if (rolling) {
		ctx.save();
		ctx.setTransform(faceTransform[0], faceTransform[1], faceTransform[2], faceTransform[3], faceTransform[4], faceTransform[5]);
		report.rolling = true;
	}

	if (document.background !== 0) {
		fillRect(surface, 0, 0, safeWidthM, safeHeightM, document.background);
	}

	// ---- 计划（选页 + forEach 展开都在这里算完） -----------------------------------------------
	let drawables = [];
	try {
		drawables = planDrawables(document, data, clock);
	} catch (e) {
		// 整份计划算不出来（页的 require 求值抛异常等）：一块都不画，但要说出来
		problems.push('算"这一帧画什么"的时候失败（这一块没画）：' + (e && e.message ? e.message : e));
	}
	report.pages = pageCountOf(document);
	report.page = pageIndexOf(document, data, clock);

	const prepared = augmentOrRaw(document, data);
	const drum = drumSketch(surface, document, data, clock, report);
	let index = 0;
	for (const drawable of drawables) {
		index++;
		const element = drawable.element;
		const elementData = drawable.data === undefined ? prepared : drawable.data;
		// 注：when 不在这里判 —— plan() 展开时用的是**这个元素自己那份数据**（forEach 的 item/index），
		// 用外层的 prepared 再判一次不但重复，还会把绑了数据的元素判错。
		const type = String(element.type).toLowerCase();
		const painter = PAINTERS[type];
		if (!painter) {
			// foreach 走到这里 = 嵌套超过深度上限（正常的 foreach 已经被 plan 展开掉了）
			problems.push('第 ' + index + ' 个元素：不认识的元素类型「' + element.type + '」（认得的有 '
				+ ELEMENT_TYPES.filter(type => type !== 'foreach').join(' / ') + '；foreach 由计划展开，不再自己画）');
			continue;
		}
		try {
			// 画法返回 false = 这一笔什么都没画（取不到内容的文本 / 灭着的 blink）；其余返回 undefined，视为画了
			const painted = painter(surface, document, element, elementData, clock);
			if (painted === false) {
				report.blank++;
			} else {
				report.painted++;
			}
		} catch (e) {
			problems.push('第 ' + index + ' 个元素（' + type + '）被跳过：' + (e && e.message ? e.message : e));
		}
	}

	if (drum !== null) {
		drum();
	}
	if (rolling) {
		ctx.restore();
	}
	report.animated = typeof document.animated === 'function' ? document.animated() : false;
	report.thinned = surface.thinned;
	return report;
}

/**
 * vars 并进数据；并的时候出错（表达式写了不认识的算子等）⇒ 用原始数据接着画。
 * 一块牌里的一处坏表达式不该让整列车的水牌全黑（与"一个元素画坏只跳过它"同一条口径）。
 */
function augmentOrRaw(document, data) {
	try {
		return augment(document, data);
	} catch (e) {
		return data;
	}
}

/** 一份文档有几页（paint 里只用于报告，坏文档不该让画这一步抛异常）。 */function pageCountOf(document) {
	try {
		return document.allPages ? document.allPages().length : 1;
	} catch (e) {
		return 1;
	}
}

/** 现在画的是第几页（同上，只为报告）。 */
function pageIndexOf(document, data, timeMs) {
	try {
		return document.pageIndex ? document.pageIndex(data, timeMs) : 0;
	} catch (e) {
		return 0;
	}
}

/**
 * 整面姿态的近似矩阵（[a,b,c,d,e,f]，与 ctx.setTransform 同序）。
 *
 * ★ 这是**预览近似**：真几何由 Java 的四边形（MmtrPanelQuad）负责 —— 那里是真的 3D 旋转 + 投影，
 * 这里只是"让作者看出牌子装歪了/仰着"。两条都按**顺时针为正**（与元素 rotate 同一套口径）。
 */
function faceMatrix(surface, document) {
	const roll = numberOr(document.roll, 0);
	const tilt = numberOr(document.tilt, 0);
	if (roll === 0 && tilt === 0) {
		return null;
	}
	// tilt：绕**牌底边**竖向压缩 cos(tilt)（仰角越大看起来越扁；到 90° 会扁成一条线，这就是近似的边界）
	const cos = Math.cos(degreesToRadians(tilt));
	const tiltScale = Math.abs(cos) < 1.0E-3 ? 1.0E-3 : cos;
	// roll：绕整块牌的中心旋转
	const centerX = surface.widthPx / 2;
	const centerY = surface.heightPx / 2;
	const radians = degreesToRadians(roll);
	const cosR = Math.cos(radians);
	const sinR = Math.sin(radians);
	// 先"绕底边竖向压缩"，再"绕中心旋转"：两者合成一个矩阵，免得两次 setTransform 互相覆盖
	const a = cosR;
	const b = sinR;
	const c = -sinR * tiltScale;
	const d = cosR * tiltScale;
	const e = centerX - a * centerX - c * centerY;
	const f = (centerY - b * centerX - d * centerY) + c * surface.heightPx + d * surface.heightPx;
	return [a, b, c, d, e, f];
}

/**
 * drum（翻牌机）的**预览示意**：在"这一帧正对的那一面"两侧各画一条侧边压扁。
 *
 * ★ 角度用的是**真几何**（geometry.mjs 的 drumAngle，与 Java 同一条，共享向量钉着），
 *   把它画成 2D 是近似的：真实现把每一页贴到 N 面棱柱的一个面上做透视投影（Java 侧 N 个四边形），
 *   这里只示意"它是个多面板、两边还有页"。
 *
 * "正对的是哪一面"不再靠猜：逐面算 drumAngle，**角度最接近 0 的那一面就是正对的**。
 * 换页周期的**前 (1-turnFraction) 停住**（这时正面就是 pageIndex 那一面）、**最后 turnFraction 翻过去** ——
 * 于是"停住时正面 = 本页、翻的过程中转到下一页"，与"不用 drum 的多页"在周期末尾换页的时间线一致。
 *
 * @returns {Function|null} 一个在元素画完之后执行的收尾函数（盖在两侧，不遮住中间）
 */
function drumSketch(surface, document, data, clock, report) {
	const drum = document.drum;
	if (drum === null || drum === undefined || report.pages <= 1) {
		return null;
	}
	const page = report.page;
	const pages = report.pages;
	const count = drum.count;
	// pageExpr 指定页 / pageSeconds = 0 时 pageClockFraction 给 0 ⇒ 停在周期开头，指定的那一面就是正面。
	// ★ 宁可**明确写出来**（而不是 typeof 守卫后静默当 0）：这个函数曾经在 FaceDocument 上漏掉，
	// 静默兜底会让翻牌机预览永远停在"周期开头"而没人发现（drum 的预览是唯一会用到它的地方）。
	const fraction = typeof document.pageClockFraction === 'function'
		? document.pageClockFraction(clock)
		: (report.problems.push('这份文档对象上没有 pageClockFraction()（版本不匹配？）—— 翻牌机预览按 clockFraction = 0 画'), 0);
	// 每个面算一次角度（与 Java 的 N 个四边形同一份算术），取最接近 0 度的那一面 = 正对。
	// 而"第 i 面贴第 i 页"⇒ 正对的那一面贴的正是 pageIndex 这一页（停住段），所以正面就是本帧那一页。
	const faceOfPage = ((page % count) + count) % count;
	let facingFace = faceOfPage;
	let facingAngle = null;
	for (let face = 0; face < count; face++) {
		const angle = drumAngle(face, page, fraction, drum.turnFraction, count);
		if (facingAngle === null || Math.abs(angle) < Math.abs(facingAngle)) {
			facingFace = face;
			facingAngle = angle;
		}
	}
	// 正对的那一面贴哪一页：faceOfPage ≡ pageIndex（停住段正是如此；翻转段 faceOfPage 先转到一半）
	const facingPage = page;
	const previousPage = ((facingPage - 1) % pages + pages) % pages;
	const nextPage = (facingPage + 1) % pages;
	const stepDegrees = 360 / Math.max(2, count);
	// "翻过去多少"：只有周期末尾那 turnFraction 段在动（drumAngle 内部也这么算）
	const window = drum.turnFraction <= 0 ? 1 : Math.min(1, drum.turnFraction);
	const remaining = Math.max(0, 1 - window);
	const fractionClamped = clamp01(fraction);
	const turning = fractionClamped > remaining
		? clamp01((fractionClamped - remaining) / window)
		: 0;
	return () => {
		const ctx = surface.ctx;
		const top = surface.heightPx * 0.06;
		const height = surface.heightPx - top * 2;
		const strip = surface.widthPx * 0.07;
		// 翻的过程中两侧的"侧边"一宽一窄：示意正对的那一面正在转开
		const leftWidth = Math.max(1, strip * (0.4 + turning));
		const rightWidth = Math.max(1, strip * (1.2 - turning * 0.8));
		ctx.save();
		ctx.globalAlpha = 0.35;
		ctx.setLineDash([Math.max(2, surface.widthPx / 60), Math.max(2, surface.widthPx / 60)]);
		ctx.strokeStyle = 'rgba(255,255,255,0.55)';
		ctx.lineWidth = Math.max(1, surface.widthPx / 400);
		ctx.strokeRect(0, top, leftWidth, height);
		ctx.strokeRect(surface.widthPx - rightWidth, top, rightWidth, height);
		ctx.restore();
		report.rolling = true;
		report.problems.push('翻牌机（drum）在工作室里只画"正对的那一面 + 两侧相邻面"的示意：正对的是「'
			+ pageName(document, facingPage) + '」（第 ' + facingPage + ' 页；本帧 pageIndex = ' + page
			+ '，前 ' + formatNumber((1 - window) * 100) + '% 停住时正面就是这一页，最后 '
			+ formatNumber(window * 100) + '% 才翻过去），上一页「' + pageName(document, previousPage) + '」/ 下一页「'
			+ pageName(document, nextPage) + '」，每面 ' + formatNumber(stepDegrees) + '°，本帧正对面的角度 '
			+ formatNumber(facingAngle) + '° —— 真几何（棱柱与翻转角）由 Java 的 MmtrFaceGeometry 负责');
	};
}

function pageName(document, index) {
	const pages = document.allPages ? document.allPages() : [];
	const page = pages[index];
	const name = page && page.name ? page.name : '';
	return name === '' ? '第 ' + index + ' 页' : name;
}

// ---- 每个元素的公共包装（opacity / rotate / anim） -----------------------------------------------

/**
 * 元素公共包装：算好这一笔的透明度、面内角度，并把"绕元素自己锚点旋转"叠到画布上。
 *
 * 角度口径：文档里 `rotate` 是**顺时针为正**（与屏幕直觉一致）。画布的 rotate(θ) 在
 * "y 向下"的坐标系里也是顺时针为正，所以这里**直接**用 +θ，不做取反 ——
 * 这与 Java AWT 版的关系是：AWT 的 y 向上，Java 那边要写 `Math.toRadians(-rotate)` 才得到同样的画面。
 * （两处符号差异不是分歧，是两套坐标系；共享向量里不做像素比对，所以只有这条注释钉着它。）
 *
 * @returns {{save:boolean, alpha:number, applied:boolean, text:string}|null}
 *          null = 这一瞬间不该画（blink 灭着）
 */
function beginElement(surface, element, data, timeMs) {
	const anim = element.anim === null || element.anim === undefined ? null : element.anim;
	if (anim !== null && !anim.visible(timeMs)) {
		return null; // blink 灭着：整笔不画（与 Java 的 resolveText 同一条判据）
	}
	// 元素自己的 rotate + spin 叠上去；fade 的 alpha 与 opacity 相乘
	const span = elementSpanM(surface, element);
	const rotate = numberOr(element.number('rotate', defaultNumber(element.type, 'rotate', 0)), 0)
		+ (anim === null ? 0 : anim.degrees(timeMs));
	const alpha = clamp01(element.number('opacity', defaultNumber(element.type, 'opacity', 1)))
		* (anim === null ? 1 : clamp01(anim.alpha(timeMs)));
	const ctx = surface.ctx;
	let applied = false;
	if (rotate !== 0 && (span[2] !== 0 || span[3] !== 0)) {
		ctx.save();
		applyRotate(surface, rotate, span[0], span[1]);
		applied = true;
	}
	return { alpha, applied };
}

/** 收尾：把 beginElement 压进去的状态弹出来。 */
function endElement(surface, state) {
	if (state !== null && state.applied) {
		surface.ctx.restore();
	}
}

/**
 * 绕 (anchorX, anchorY)（米）按**顺时针为正**的度数旋转。
 *
 * 像素空间里 y 向下，所以"面内顺时针"就是普通旋转矩阵：
 *   p' = anchor + R(θ)·(p - anchor)，R 在 y 向下时按 CSS 的 rotate() 约定取 cos/sin 的那一套。
 * 直接把它写进 ctx.setTransform（而不是 translate/rotate/translate 三次调用），
 * 是因为一次矩阵不会漏掉 ctx.save/restore 的半途状态，也不会与整面姿态的矩阵打架。
 */
function applyRotate(surface, degrees, anchorX, anchorY) {
	const ctx = surface.ctx;
	// 当前矩阵（可能已经含整面姿态）—— 用 getTransform 拿不到时退回单位矩阵
	const base = typeof ctx.getTransform === 'function' ? ctx.getTransform() : null;
	const m = base === null
		? [1, 0, 0, 1, 0, 0]
		: [base.a, base.b, base.c, base.d, base.e, base.f];
	const radians = degreesToRadians(degrees);
	const cos = Math.cos(radians);
	const sin = Math.sin(radians);
	const anchorPxX = pxX(surface, anchorX);
	const anchorPxY = pxY(surface, anchorY);
	// 局部旋转矩阵（绕 anchor）
	const r = [cos, sin, -sin, cos, anchorPxX - cos * anchorPxX + sin * anchorPxY, anchorPxY - sin * anchorPxX - cos * anchorPxY];
	// 合成：先局部旋转，再走 base
	ctx.setTransform(
		r[0] * m[0] + r[2] * m[1],
		r[1] * m[0] + r[3] * m[1],
		r[0] * m[2] + r[2] * m[3],
		r[1] * m[2] + r[3] * m[3],
		r[0] * m[4] + r[2] * m[5] + r[4],
		r[1] * m[4] + r[3] * m[5] + r[5],
	);
}

/** 元素的"锚点"（米）—— 与文档 §3 的锚点表一致：rect/roundrect/image 是左下角，line 是起点，其余是 (x,y)。 */
function elementSpanM(surface, element) {
	const x = element.x * surface.widthM;
	const y = element.y * surface.heightM;
	const type = element.type;
	if (type === 'rect' || type === 'roundrect' || type === 'image') {
		// 左下角（w/h 向右上长）
		return [x, y, element.w * surface.widthM, element.h * surface.heightM];
	}
	return [x, y, 0, 0];
}

// ---- 元素画法 -----------------------------------------------------------------------------------

/** 一种元素怎么画（data 里已经并进了文档的 vars）。返回 false = 这一笔什么都没画。 */
const PAINTERS = {
	text: paintText,
	rect: paintRect,
	roundrect: paintRoundRect,
	line: paintLine,
	circle: paintCircle,
	arc: paintArc,
	gauge: paintGauge,
	image: paintImage,
};

function paintText(surface, document, element, data, timeMs) {
	const state = beginElement(surface, element, data, timeMs);
	if (state === null) {
		return false; // blink 灭着
	}
	try {
		const anim = element.anim;
		// 文本模板的解析要用**这个元素自己那份数据**（forEach 会把 item/index 绑进去），
		// 并且与 Java 的 Element.resolveText 一样：when 不成立 / blink 灭着 ⇒ 空串（整行不画）
		const rawText = resolveTextRaw(element, data, timeMs);
		if (rawText === '') {
			// 取不到内容 ⇒ 这一行不画（不是画个空位）
			return false;
		}
		const color = element.color === 0 ? document.textColor : element.color;
		const align = alignOf(element.align);
		if (anim !== null && anim.kind === 'marquee') {
			return paintMarquee(surface, element, rawText, color, align, anim, timeMs, state);
		}
		let sizeM = element.size * surface.heightM;
		const shrink = element.number('shrinkToFit', defaultNumber('text', 'shrinkToFit', 0.9));
		if (shrink > 0) {
			const maxWidthM = surface.widthM * shrink;
			const widthAtFullSize = textWidthM(surface, rawText, sizeM);
			if (widthAtFullSize > maxWidthM && widthAtFullSize > 0) {
				sizeM = sizeM * maxWidthM / widthAtFullSize;
			}
		}
		drawText(surface, rawText, element.x * surface.widthM, element.y * surface.heightM, sizeM,
			color, align, 'center', state.alpha);
		return true;
	} finally {
		endElement(surface, state);
	}
}

/**
 * 走马灯：文本在**视口**里从右往左走（direction: right 反过来），必须 clip 在视口里。
 *
 * 视口 = 元素的 w（w = 0 时 = 从 x 到牌右缘）。走多远、接缝多大、以及**首帧在哪**，
 * 与 MmtrFaceGeometry.marqueeLeft 同一条算术（geometry.mjs，共享向量钉着）。
 *
 * ★ 文本用**左边缘**口径：marqueeLeft 给的就是文本左边缘的 x，画的时候从那儿左对齐起画；
 * 元素的 align 在走马灯里**不参与**（作者想改走马灯的位置就改 x / w）。
 */
function paintMarquee(surface, element, text, color, align, anim, timeMs, state) {
	const viewportLeft = element.x * surface.widthM;
	const viewportWidth = element.w > 0
		? element.w * surface.widthM
		: Math.max(0, surface.widthM - viewportLeft);
	const gapM = anim.gapRatio * surface.heightM;
	const sizeM = element.size * surface.heightM;
	const textWidth = textWidthM(surface, text, sizeM);
	const phase = anim.offsetFraction(timeMs);
	const leftM = marqueeLeft(viewportLeft, viewportWidth, textWidth, gapM, phase);
	const ctx = surface.ctx;
	// 视口高度：h > 0 就用它，否则用字高的两倍（够把字装进去，又不至于盖住别的元素）
	const viewportHeight = element.h > 0 ? element.h * surface.heightM : sizeM * 2;
	const viewportBottom = element.y * surface.heightM - viewportHeight / 2;
	ctx.save();
	ctx.beginPath();
	ctx.rect(pxX(surface, viewportLeft), pxY(surface, viewportBottom + viewportHeight),
		Math.max(1, viewportWidth * surface.sx), Math.max(1, viewportHeight * surface.sy));
	ctx.clip();
	drawText(surface, text, leftM, element.y * surface.heightM, sizeM, color, 'left', 'center', state.alpha);
	ctx.restore();
	return true;
}

/**
 * 文本元素的字（原始模板 → 这个元素自己那份数据里的字）。
 *
 * 与 Java 的 Element.resolveText(data, timeMs) 逐条同口径：不是 text ⇒ 空串；when 不成立 ⇒ 空串
 * （没有 when = 总是画）；blink 灭着 ⇒ 空串；模板取不到的路径 ⇒ 空串。
 */
function resolveTextRaw(element, data, timeMs) {
	if (element.whenPresent && !truthy(evaluate(element.when, data))) {
		return '';
	}
	if (element.anim !== null && element.anim !== undefined && !element.anim.visible(timeMs)) {
		// blink 灭着：整行不画（与 beginElement 同一条判据；这里再来一次是因为文本还要先量宽度）
		return '';
	}
	try {
		return resolveTemplate(element.text, data);
	} catch (e) {
		return '';
	}
}

function paintRect(surface, document, element, data, timeMs) {
	const state = beginElement(surface, element, data, timeMs);
	if (state === null) {
		return false;
	}
	try {
		// ★ 不套 textColor：Java 的 paintRect 直接吃 element.color()，哨兵 0 = 全透明（看不见）
		fillRect(surface, element.x * surface.widthM, element.y * surface.heightM,
			element.w * surface.widthM, element.h * surface.heightM, element.color, state.alpha);
		return true;
	} finally {
		endElement(surface, state);
	}
}

function paintRoundRect(surface, document, element, data, timeMs) {
	const state = beginElement(surface, element, data, timeMs);
	if (state === null) {
		return false;
	}
	try {
		fillRoundRect(surface, element.x * surface.widthM, element.y * surface.heightM,
			element.w * surface.widthM, element.h * surface.heightM,
			element.number('radius', defaultNumber('roundrect', 'radius', 0.08)) * surface.heightM,
			element.color, state.alpha);
		return true;
	} finally {
		endElement(surface, state);
	}
}

function paintLine(surface, document, element, data, timeMs) {
	const state = beginElement(surface, element, data, timeMs);
	if (state === null) {
		return false;
	}
	try {
		strokeLine(surface, element.x * surface.widthM, element.y * surface.heightM,
			element.number('x2', element.x) * surface.widthM, element.number('y2', element.y) * surface.heightM,
			Math.max(element.number('width', defaultNumber('line', 'width', 0.03)), 0.001) * surface.heightM,
			element.color, state.alpha);
		return true;
	} finally {
		endElement(surface, state);
	}
}

function paintCircle(surface, document, element, data, timeMs) {
	const state = beginElement(surface, element, data, timeMs);
	if (state === null) {
		return false;
	}
	try {
		fillCircle(surface, element.x * surface.widthM, element.y * surface.heightM,
			element.number('radius', defaultNumber('circle', 'radius', 0.1)) * surface.heightM,
			element.color, state.alpha);
		return true;
	} finally {
		endElement(surface, state);
	}
}

function paintArc(surface, document, element, data, timeMs) {
	const state = beginElement(surface, element, data, timeMs);
	if (state === null) {
		return false;
	}
	try {
		strokeArc(surface, element.x * surface.widthM, element.y * surface.heightM,
			element.number('radius', defaultNumber('arc', 'radius', 0.4)) * surface.heightM,
			Math.max(element.number('width', defaultNumber('arc', 'width', 0.01)), 0.001) * surface.heightM,
			element.number('start', defaultNumber('arc', 'start', 0)),
			element.number('end', defaultNumber('arc', 'end', 360)), element.color, state.alpha);
		return true;
	} finally {
		endElement(surface, state);
	}
}

/**
 * 表盘 + 指针（与 MmtrHudLayout.paintGauge 的键与缺省值逐字相同）。唯一的新东西是**指针值来自表达式**
 * needle（默认 {"var":"speedKmh"}）。
 */
function paintGauge(surface, document, element, data, timeMs) {
	const state = beginElement(surface, element, data, timeMs);
	if (state === null) {
		return false;
	}
	try {
		const width = surface.widthM;
		const height = surface.heightM;
		const cx = element.x * width;
		const cy = element.y * height;
		const radius = element.number('radius', defaultNumber('gauge', 'radius', 0.44)) * height;
		if (radius <= 0) {
			return false;
		}
		const start = element.number('start', defaultNumber('gauge', 'start', 225));
		const end = element.number('end', defaultNumber('gauge', 'end', -45));
		const lineWidth = Math.max(element.number('lineWidth', element.number('width', 0)), 0.004) * height;
		const color = element.color === 0 ? document.textColor : element.color;
		const max = element.number('max', defaultNumber('gauge', 'max', 160));

		strokeArc(surface, cx, cy, radius, lineWidth, start, end, color, state.alpha);

		const ticks = element.number('ticks', defaultNumber('gauge', 'ticks', 0));
		if (ticks > 0) {
			const tickLength = element.number('tickLength', defaultNumber('gauge', 'tickLength', 0.12)) * height;
			const labelEvery = element.number('labelEvery', defaultNumber('gauge', 'labelEvery', 0));
			const labelSize = element.number('labelSize', defaultNumber('gauge', 'labelSize', 0.13));
			const labelColor = parseColor(element.string('labelColor', ''), 0);
			for (let i = 0; i <= Math.trunc(ticks); i++) {
				const fraction = i / ticks;
				const angle = (start + (end - start) * fraction) * Math.PI / 180;
				const cos = Math.cos(angle);
				const sin = Math.sin(angle);
				const major = ticks <= 12 || i % 5 === 0;
				const inner = radius - tickLength * (major ? 1 : 0.55);
				strokeLine(surface, cx + cos * inner, cy + sin * inner, cx + cos * radius, cy + sin * radius,
					Math.max(lineWidth * 0.7, 0.003 * height), color, state.alpha);
				if (labelEvery > 0 && i % Math.trunc(labelEvery) === 0 && labelSize > 0) {
					const labelRadius = radius - tickLength - labelSize * height * 0.7;
					drawText(surface, String(Math.round(max * fraction)), cx + cos * labelRadius, cy + sin * labelRadius,
						labelSize * height, labelColor === 0 ? color : labelColor, 'center', 'center', state.alpha);
				}
			}
		}

		const value = asNumber(needleValue(element, data));
		const fraction = value === null || max <= 0 ? 0 : Math.max(0, Math.min(1, value / max));
		const needleColor = parseColor(element.string('needleColor', ''), 0xFFFF3B30 | 0);
		const needleWidth = element.number('needleWidth', defaultNumber('gauge', 'needleWidth', 0.05)) * height;
		fillNeedle(surface, cx, cy, radius * element.number('needleLength', defaultNumber('gauge', 'needleLength', 0.94)),
			Math.max(needleWidth, 0.01 * height), start + (end - start) * fraction, needleColor, state.alpha);
		fillCircle(surface, cx, cy, Math.max(needleWidth, 0.02 * height), needleColor, state.alpha);
		return true;
	} finally {
		endElement(surface, state);
	}
}

/**
 * 指针值：`needle` 表达式（缺省 = 车速）。
 *
 * `needle: null` 与"没写 needle"一样 ⇒ 回到缺省车速（统一规矩：这几个逻辑键上的显式 null = 键不在）。
 */
function needleValue(element, data) {
	const needle = Object.prototype.hasOwnProperty.call(element.raw, 'needle') ? element.raw.needle : null;
	if (needle === null || needle === undefined) {
		return lookup('speedKmh', data);
	}
	return evaluate(needle, data);
}

/**
 * 图片：src / fit(stretch|contain|cover) / alpha / tint。
 *
 * 找不到图**不抛异常**：画一个虚线占位框 + 一行提示（作者指南里"图没了"是最常见的一类问题，
 * 而它不该让整块牌白屏）。图还没加载完就先画占位，加载完由 imageAssets 触发一次重画。
 */
function paintImage(surface, document, element, data, timeMs) {
	const state = beginElement(surface, element, data, timeMs);
	if (state === null) {
		return false;
	}
	try {
		const boxX = element.x * surface.widthM;
		const boxY = element.y * surface.heightM;
		const boxW = element.w * surface.widthM;
		const boxH = element.h * surface.heightM;
		const src = element.string('src', defaultString('image', 'src', ''));
		const fit = element.string('fit', defaultString('image', 'fit', 'stretch'));
		const alpha = clamp01(element.number('alpha', defaultNumber('image', 'alpha', 1))) * state.alpha;
		const tint = parseColor(element.string('tint', ''), 0xFFFFFFFF | 0);

		const asset = resolveImageAsset(src);
		if (asset === null) {
			placeholder(surface, boxX, boxY, boxW, boxH, '图「' + src + '」找不到');
			surface.thinned++;
			return false;
		}
		const frame = assetFrame(asset);
		if (frame === null) {
			// 还在加载：先画个浅色框，加载完再重画
			placeholder(surface, boxX, boxY, boxW, boxH, '图「' + src + '」加载中…', true);
			surface.thinned++;
			return false;
		}
		const aspect = frame.height > 0 ? frame.width / frame.height : 0;
		const rect = fitRect(fit, boxX, boxY, boxW, boxH, aspect);
		const image = tinted(asset, frame, tint);
		const ctx = surface.ctx;
		ctx.save();
		// cover 会超出框：裁掉（与 Java 的 clip 同一条）
		ctx.beginPath();
		ctx.rect(pxX(surface, boxX), pxY(surface, boxY + boxH),
			Math.max(1, boxW * surface.sx), Math.max(1, boxH * surface.sy));
		ctx.clip();
		ctx.globalAlpha = clamp01(alpha);
		ctx.drawImage(image, pxX(surface, rect[0]), pxY(surface, rect[1] + rect[3]),
			Math.max(1, rect[2] * surface.sx), Math.max(1, rect[3] * surface.sy));
		ctx.restore();
		// contain/cover 与框不重合是**正常的**（那就是这两个模式的意思），所以这里不记问题
		return true;
	} finally {
		endElement(surface, state);
	}
}

/** 找不到图/还没加载完时的占位：虚线框 + 一行提示（不抛异常、不白屏）。 */
function placeholder(surface, boxX, boxY, boxW, boxH, message, loading) {
	const width = boxW > 0 ? boxW : surface.widthM * 0.2;
	const height = boxH > 0 ? boxH : surface.heightM * 0.6;
	const left = boxX;
	const bottom = boxY - (boxH > 0 ? 0 : height / 2);
	const ctx = surface.ctx;
	ctx.save();
	ctx.globalAlpha = loading ? 0.35 : 0.8;
	ctx.setLineDash([Math.max(3, surface.minScale * 0.01), Math.max(3, surface.minScale * 0.008)]);
	ctx.strokeStyle = loading ? 'rgba(160,160,160,0.9)' : 'rgba(255,64,64,0.95)';
	ctx.lineWidth = Math.max(1, surface.minScale * 0.002);
	ctx.strokeRect(pxX(surface, left) + 0.5, pxY(surface, bottom + height) + 0.5,
		Math.max(1, width * surface.sx) - 1, Math.max(1, height * surface.sy) - 1);
	ctx.restore();
	const note = String(message || '');
	if (note !== '') {
		placeholderNote(surface, note, left + width / 2, bottom + height / 2, height);
	}
}

/** 占位框里那行提示：按框高/框宽缩到"能塞进去"的字号（与文本元素的 shrinkToFit 同一招）。 */
function placeholderNote(surface, text, centerX, centerY, boxHeight) {
	const sizeM = Math.max(surface.heightM * 0.08, Math.min(boxHeight * 0.22, surface.heightM * 0.2));
	const widthAtSize = textWidthM(surface, text, sizeM);
	const maxWidth = surface.widthM * 0.9;
	const scale = widthAtSize > maxWidth && widthAtSize > 0 ? maxWidth / widthAtSize : 1;
	drawText(surface, text, centerX, centerY, sizeM * scale, 0xFFFF4040 | 0, 'center', 'center', 1);
}

// ---- 图片资源的解析、加载与色调（工作室专有；游戏侧由资源管理器给图） ---------------------------

/**
 * 图片资源表：`src` 标识 → {url/path, image, tinted}。
 *
 * 工作室里 src 是 `mmtr:vehicle/face/logo.png` 这种**标识**，页面把它翻译成
 * `/data/<pack 根>/assets/<ns>/<path>`（FaceServe 起在 run 目录上）。先在
 * `mmtr/game/fabric/run/resourcepacks/` 下找；找不到画占位框 + 一行提示，**不抛异常**。
 */
const imageCache = new Map();
/** 加载完一张图时调一次（页面借此重画一帧）。 */
let imageListener = null;

/** 页面注册"图加载完了请重画"的回调。 */
export function setImageListener(listener) {
	imageListener = typeof listener === 'function' ? listener : null;
}

/** 缓存里有没有还没加载完的图（页面可以用它决定要不要挂个定时器兜底）。 */
export function hasPendingImages() {
	for (const entry of imageCache.values()) {
		if (entry.image === null) {
			return true;
		}
	}
	return false;
}

/**
 * `src` 标识 → 资源项。解析规则（与打包器认的"图片标识"同一条）：
 *   `mmtr:vehicle/face/logo.png` → 命名空间 `mmtr`、路径 `vehicle/face/logo.png`
 *   最终落在 `<资源包>/assets/<ns>/<path>`（路径不以 `textures/` 开头时还会再试 `<path>.png`
 *   与 `textures/<path>.png` —— 资源包里两种写法都有）。
 *
 * @returns {{key:string, url:string|null, path:string|null, pending:boolean, error:string|null}|null}
 *          null = src 是空的（不画，也不报问题）
 */
export function resolveImageAsset(src) {
	const id = String(src === null || src === undefined ? '' : src).trim();
	if (id === '') {
		return null;
	}
	const cached = imageCache.get(id);
	if (cached !== undefined) {
		return cached;
	}
	const entry = { key: id, url: null, path: null, pending: false, error: null, image: null, tinted: new Map() };
	imageCache.set(id, entry);
	startLoad(entry, id);
	return entry;
}

/** 开始加载（浏览器用 <img>；Node 下没有 Image，直接标成"加载不了"，由调用方画占位）。 */
function startLoad(entry, id) {
	const parsed = parseAssetId(id);
	const candidates = assetCandidates(parsed.namespace, parsed.path);
	const found = findAssetFile(candidates);
	entry.path = found === null ? null : found.relative;
	entry.url = entry.path === null ? null : assetUrl(entry.path);
	if (entry.url === null) {
		entry.error = '资源包里没有 assets/' + parsed.namespace + '/' + parsed.path
			+ '（找过 ' + candidates.join('、') + '；Node 侧读的是 mmtr/game/fabric/run/resourcepacks/，'
			+ '浏览器侧读的是 FaceServe 根下的 resourcepacks/）';
		return;
	}
	if (typeof Image !== 'function') {
		entry.error = '这个环境里没有 HTMLImageElement（Node 下跑自检时不画图，只校验标识能不能解析成路径）';
		return;
	}
	entry.pending = true;
	const element = new Image();
	element.onload = () => {
		entry.image = element;
		entry.pending = false;
		if (imageListener !== null) {
			imageListener(id);
		}
	};
	element.onerror = () => {
		entry.pending = false;
		entry.error = '图「' + id + '」在 ' + entry.url + ' 上加载失败（FaceServe 起的目录对不对？）';
		if (imageListener !== null) {
			imageListener(id);
		}
	};
	element.src = entry.url;
}

/** `ns:path` → { namespace, path }；没写命名空间时按 `mmtr`（与打包器的缺省同一条）。 */
function parseAssetId(id) {
	const colon = id.indexOf(':');
	if (colon < 0) {
		return { namespace: 'mmtr', path: id };
	}
	return { namespace: id.substring(0, colon).trim() || 'mmtr', path: id.substring(colon + 1).trim() };
}

/** 资源包里可能放这个资源的相对路径（`assets/<ns>/` 之后的那一段）。 */
function assetCandidates(namespace, path) {
	const clean = path.replace(/^\/+/, '');
	const hasExtension = /\.(png|jpg|jpeg|webp|gif)$/i.test(clean);
	const bases = [clean];
	if (!hasExtension) {
		bases.push(clean + '.png');
	}
	// 资源包里两种写法都有：直接写 `vehicle/face/x.png`，或者按原版约定放 `textures/` 下
	if (!clean.startsWith('textures/')) {
		bases.push('textures/' + clean);
		if (!hasExtension) {
			bases.push('textures/' + clean + '.png');
		}
	}
	return bases
		.filter((item, index) => bases.indexOf(item) === index)
		.map(item => 'assets/' + namespace + '/' + item);
}

/**
 * 在资源包里找第一个存在的文件。
 *
 * Node：扫 `run/resourcepacks/<包>/<候选>`（只扫第一层包目录 —— 够用，且不会掉进解压出来的深目录）；
 * 浏览器：交给 FaceServe，先按第一个候选给 URL（命中与否由 <img> 的 onerror 说话）。
 *
 * @returns {{relative:string}|null} relative = `<包名>/assets/...`（直接拼在 resourcepacks/ 后面）
 */
function findAssetFile(candidates) {
	if (!isNode() || nodeFs === null || nodePath === null) {
		return { relative: candidates[0] };
	}
	const root = resourcePackRoot();
	if (root === null) {
		return null;
	}
	try {
		for (const pack of nodeFs.readdirSync(root)) {
			const base = nodePath.join(root, pack);
			// 只看目录（resources 里可能有 zip，浏览器侧能读、Node 侧读不了 —— 那就当没找到）
			try {
				if (!nodeFs.statSync(base).isDirectory()) {
					continue;
				}
			} catch (e) {
				continue;
			}
			for (const candidate of candidates) {
				if (nodeFs.existsSync(nodePath.join(base, candidate))) {
					return { relative: pack + '/' + candidate };
				}
			}
		}
	} catch (e) {
		return null;
	}
	return null;
}

/** run/resourcepacks 的绝对路径（从本模块往上找 mmtr/game/fabric/run）。找不到 = null。 */
function resourcePackRoot() {
	if (resourcePackRootCache !== undefined) {
		return resourcePackRootCache;
	}
	resourcePackRootCache = null;
	if (nodeFs === null || nodePath === null || MODULE_DIR === null) {
		return null;
	}
	try {
		let directory = MODULE_DIR;
		for (let i = 0; i < 12; i++) {
			const candidate = nodePath.join(directory, 'game', 'fabric', 'run', 'resourcepacks');
			if (nodeFs.existsSync(candidate)) {
				resourcePackRootCache = candidate;
				return candidate;
			}
			const parent = nodePath.dirname(directory);
			if (parent === directory) {
				break;
			}
			directory = parent;
		}
	} catch (e) {
		resourcePackRootCache = null;
	}
	return resourcePackRootCache;
}

let resourcePackRootCache;

function isNode() {
	return typeof process !== 'undefined' && process !== null && process.versions !== undefined && process.versions.node !== undefined;
}

/**
 * 资源文件的 URL（页面把它给 <img>）。
 *
 * **一个已知限制**：FaceServe 起在 `run/` 上，所以这里给的是 `resourcepacks/<包>/assets/...`；
 * 如果资源包不在 run 下（打包器直接读工作区），浏览器这一侧会加载失败 —— 那时画的是
 * "加载失败"占位（不白屏），Node 侧仍然能解析出路径并说清楚找过哪些地方。
 */
function assetUrl(relativePath) {
	return 'resourcepacks/' + relativePath.split('\\').join('/');
}

/** 加载完的那张图（还没加载完 ⇒ null）。 */
function assetFrame(asset) {
	return asset !== undefined && asset !== null && asset.image !== null ? asset.image : null;
}

/**
 * 色调：tint 的 RGB **乘**到像素上（#FF4040 把 G/B 压掉），alpha 保持原样。
 * 白（默认）是等值变换，直接返回原图，不做一次离屏拷贝（也就没有一次离屏画布的开销）。
 */
function tinted(asset, image, tint) {
	const argb = (Number(tint) | 0) >>> 0;
	if ((argb & 0xFFFFFF) === 0xFFFFFF) {
		return image;
	}
	const key = String(argb);
	const cached = asset.tinted.get(key);
	if (cached !== undefined) {
		return cached;
	}
	// ★ 用 globalThis.document（不是 document）：本模块里 `document` 到处都是"面文档"这个参数，
	// 直接写 document.createElement 会去问面文档要 createElement。
	const dom = typeof globalThis !== 'undefined' ? globalThis.document : undefined;
	if (dom === undefined || dom === null || typeof dom.createElement !== 'function') {
		return image;
	}
	try {
		const canvas = dom.createElement('canvas');
		canvas.width = image.width;
		canvas.height = image.height;
		const ctx = canvas.getContext('2d');
		ctx.drawImage(image, 0, 0);
		ctx.globalCompositeOperation = 'multiply';
		ctx.fillStyle = rgbCss(argb);
		ctx.fillRect(0, 0, canvas.width, canvas.height);
		// multiply 会连 alpha 一起乘（透明区不再透明），所以再用 destination-in 把原图的 alpha 找回来
		ctx.globalCompositeOperation = 'destination-in';
		ctx.drawImage(image, 0, 0);
		asset.tinted.set(key, canvas);
		return canvas;
	} catch (e) {
		// 离屏画布拿不到（某些浏览器设置）：退回原图 —— 颜色不对总比不画好
		return image;
	}
}

function rgbCss(argb) {
	const value = (Number(argb) | 0) >>> 0;
	return 'rgb(' + ((value >>> 16) & 0xFF) + ',' + ((value >>> 8) & 0xFF) + ',' + (value & 0xFF) + ')';
}

// ---- 画布原语（米 → 像素） ----------------------------------------------------------------------

function pxX(surface, metres) {
	return metres * surface.sx;
}

/** y 从下往上 ⇒ 画布 y 要翻过来。 */
function pxY(surface, metres) {
	return (surface.heightM - metres) * surface.sy;
}

function lineWidthPx(surface, metres) {
	return Math.max(metres * surface.minScale, 1);
}

/** 元素的最终颜色：opacity 与 fade 都乘到 alpha 上，最后交给画布原语的 globalAlpha。 */
function colorWithAlpha(color, alpha) {
	const value = (Number(color) | 0) >>> 0;
	const baseAlpha = ((value >>> 24) & 0xFF) / 255;
	const combined = baseAlpha * clamp01(alpha);
	return (((value & 0x00FFFFFF) | (Math.round(combined * 255) << 24)) | 0) >>> 0;
}

function fillRect(surface, x, y, width, height, color, alpha = 1) {
	if (width <= 0 || height <= 0) {
		return;
	}
	const ctx = surface.ctx;
	ctx.globalAlpha = clamp01(alpha);
	ctx.fillStyle = cssColor(color);
	ctx.fillRect(pxX(surface, x), pxY(surface, y + height), width * surface.sx, height * surface.sy);
	ctx.globalAlpha = 1;
}

function fillRoundRect(surface, x, y, width, height, radiusM, color, alpha = 1) {
	if (width <= 0 || height <= 0) {
		return;
	}
	const radius = Math.max(0, Math.min(radiusM, Math.min(width, height) / 2)) * surface.minScale;
	const left = pxX(surface, x);
	const top = pxY(surface, y + height);
	const boxWidth = width * surface.sx;
	const boxHeight = height * surface.sy;
	const ctx = surface.ctx;
	ctx.globalAlpha = clamp01(alpha);
	ctx.fillStyle = cssColor(color);
	ctx.beginPath();
	if (typeof ctx.roundRect === 'function') {
		ctx.roundRect(left, top, boxWidth, boxHeight, radius);
	} else {
		roundRectPath(ctx, left, top, boxWidth, boxHeight, radius);
	}
	ctx.fill();
	ctx.globalAlpha = 1;
}

function roundRectPath(ctx, left, top, width, height, radius) {
	const r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
	ctx.moveTo(left + r, top);
	ctx.lineTo(left + width - r, top);
	ctx.arcTo(left + width, top, left + width, top + r, r);
	ctx.lineTo(left + width, top + height - r);
	ctx.arcTo(left + width, top + height, left + width - r, top + height, r);
	ctx.lineTo(left + r, top + height);
	ctx.arcTo(left, top + height, left, top + height - r, r);
	ctx.lineTo(left, top + r);
	ctx.arcTo(left, top, left + r, top, r);
	ctx.closePath();
}

function strokeLine(surface, x1, y1, x2, y2, widthM, color, alpha = 1) {
	const ctx = surface.ctx;
	ctx.globalAlpha = clamp01(alpha);
	ctx.strokeStyle = cssColor(color);
	ctx.lineWidth = lineWidthPx(surface, widthM);
	ctx.lineCap = 'round';
	ctx.lineJoin = 'round';
	ctx.beginPath();
	ctx.moveTo(pxX(surface, x1), pxY(surface, y1));
	ctx.lineTo(pxX(surface, x2), pxY(surface, y2));
	ctx.stroke();
	ctx.globalAlpha = 1;
}

function fillCircle(surface, cx, cy, radiusM, color, alpha = 1) {
	const radius = radiusM * surface.minScale;
	if (radius <= 0) {
		return;
	}
	const ctx = surface.ctx;
	ctx.globalAlpha = clamp01(alpha);
	ctx.fillStyle = cssColor(color);
	ctx.beginPath();
	ctx.ellipse(pxX(surface, cx), pxY(surface, cy), radius, radius, 0, 0, Math.PI * 2);
	ctx.fill();
	ctx.globalAlpha = 1;
}

/** 圆弧：start/end 是"从 +X 起、逆时针为正"的度数（米空间里算点，再翻 y）。 */
function strokeArc(surface, cx, cy, radius, widthM, startDegrees, endDegrees, color, alpha = 1) {
	const steps = Math.max(6, Math.min(256, Math.trunc(Math.abs(endDegrees - startDegrees) / 3)));
	const xs = [];
	const ys = [];
	for (let i = 0; i <= steps; i++) {
		const angle = (startDegrees + (endDegrees - startDegrees) * i / steps) * Math.PI / 180;
		xs.push(cx + radius * Math.cos(angle));
		ys.push(cy + radius * Math.sin(angle));
	}
	strokePolyline(surface, xs, ys, widthM, color, alpha);
}

function strokePolyline(surface, xs, ys, widthM, color, alpha = 1) {
	const count = Math.min(xs.length, ys.length);
	if (count < 2 || widthM <= 0) {
		return;
	}
	const ctx = surface.ctx;
	ctx.globalAlpha = clamp01(alpha);
	ctx.strokeStyle = cssColor(color);
	ctx.lineWidth = lineWidthPx(surface, widthM);
	ctx.lineCap = 'round';
	ctx.lineJoin = 'round';
	ctx.beginPath();
	for (let i = 0; i < count; i++) {
		if (i === 0) {
			ctx.moveTo(pxX(surface, xs[i]), pxY(surface, ys[i]));
		} else {
			ctx.lineTo(pxX(surface, xs[i]), pxY(surface, ys[i]));
		}
	}
	ctx.stroke();
	ctx.globalAlpha = 1;
}

function fillPolygon(surface, xs, ys, color, alpha = 1) {
	const count = Math.min(xs.length, ys.length);
	if (count < 3) {
		return;
	}
	const ctx = surface.ctx;
	ctx.globalAlpha = clamp01(alpha);
	ctx.fillStyle = cssColor(color);
	ctx.beginPath();
	for (let i = 0; i < count; i++) {
		if (i === 0) {
			ctx.moveTo(pxX(surface, xs[i]), pxY(surface, ys[i]));
		} else {
			ctx.lineTo(pxX(surface, xs[i]), pxY(surface, ys[i]));
		}
	}
	ctx.closePath();
	ctx.fill();
	ctx.globalAlpha = 1;
}

/** 表针：一块从支点朝 angleDegrees（逆时针从 +X 起）指出去的矩形。 */
function fillNeedle(surface, cx, cy, lengthM, widthM, angleDegrees, color, alpha = 1) {
	const angle = angleDegrees * Math.PI / 180;
	const dirX = Math.cos(angle);
	const dirY = Math.sin(angle);
	const halfWidth = widthM / 2;
	const perpX = -dirY * halfWidth;
	const perpY = dirX * halfWidth;
	return fillPolygon(surface, [
		cx + perpX, cx - perpX, cx - perpX + dirX * lengthM, cx + perpX + dirX * lengthM,
	], [
		cy + perpY, cy - perpY, cy - perpY + dirY * lengthM, cy + perpY + dirY * lengthM,
	], color, alpha);
}

// ---- 文字 ---------------------------------------------------------------------------------------

/**
 * 与 Java 同一套推导：文本的**墨迹框高度**（可见字形，不是行高）等于目标高度。
 * Java 先用 100pt 量一次、按比例推出字号，再量一次 —— 这里照做，于是"先量宽度再排版"不会与画出来的不一致。
 */
function inkMetrics(ctx, text, fontPx) {
	ctx.font = fontPx + 'px ' + FONT_STACK;
	const metrics = ctx.measureText(text);
	const ascent = Number.isFinite(metrics.actualBoundingBoxAscent) ? metrics.actualBoundingBoxAscent : fontPx * 0.80;
	const descent = Number.isFinite(metrics.actualBoundingBoxDescent) ? metrics.actualBoundingBoxDescent : fontPx * 0.20;
	const left = Number.isFinite(metrics.actualBoundingBoxLeft) ? metrics.actualBoundingBoxLeft : 0;
	const right = Number.isFinite(metrics.actualBoundingBoxRight) ? metrics.actualBoundingBoxRight : metrics.width;
	return { ascent, descent, inkHeight: ascent + descent, inkWidth: left + right, left };
}

/** 墨迹高度为目标像素数时的字号；量不出高度（空串/空白）⇒ null。 */
function fontSizeFor(surface, text, targetInkPx) {
	if (text === '' || targetInkPx <= 0) {
		return null;
	}
	const probe = inkMetrics(surface.ctx, text, 100);
	if (!(probe.inkHeight > 0)) {
		return null;
	}
	return 100 * targetInkPx / probe.inkHeight;
}

/** 一段文字在给定墨迹高度下的墨迹宽度（米）—— 与 drawText 同一套字号推导。 */
function textWidthM(surface, text, heightM) {
	const fontPx = fontSizeFor(surface, text, heightM * surface.sy);
	if (fontPx === null) {
		return 0;
	}
	return inkMetrics(surface.ctx, text, fontPx).inkWidth / surface.sx;
}

function horizontalOffset(align, width) {
	switch (align) {
		case 'left':
			return 0;
		case 'right':
			return -width;
		default:
			return -width / 2;
	}
}

function verticalOffset(align, height) {
	switch (align) {
		case 'top':
			return 0;
		case 'bottom':
			return -height;
		default:
			return -height / 2;
	}
}

/** left/center/right（其余一律 center）。 */
function alignOf(align) {
	switch (String(align === null || align === undefined ? '' : align).trim().toLowerCase()) {
		case 'left':
			return 'left';
		case 'right':
			return 'right';
		default:
			return 'center';
	}
}

/** 文本：盒子的锚点在 (x, y)（米），heightM 是**墨迹框**高度。 */
function drawText(surface, text, xM, yM, heightM, color, align, verticalAlign, alpha = 1) {
	if (text === '' || heightM <= 0) {
		return;
	}
	const fontPx = fontSizeFor(surface, text, heightM * surface.sy);
	if (fontPx === null) {
		return;
	}
	const metrics = inkMetrics(surface.ctx, text, fontPx);
	const boxLeft = pxX(surface, xM) + horizontalOffset(align, metrics.inkWidth);
	const boxTop = pxY(surface, yM) + verticalOffset(verticalAlign, metrics.inkHeight);
	const ctx = surface.ctx;
	ctx.globalAlpha = clamp01(alpha);
	ctx.fillStyle = cssColor(color);
	ctx.textAlign = 'left';
	ctx.textBaseline = 'alphabetic';
	// fillText 的落点是**笔的起点**（基线），所以要往回补上墨迹的左留白与上沿
	ctx.fillText(text, boxLeft + metrics.left, boxTop + metrics.ascent);
	ctx.globalAlpha = 1;
}

// ---- 小工具 -------------------------------------------------------------------------------------

function clamp01(value) {
	return Math.max(0, Math.min(1, Number.isFinite(value) ? value : 0));
}

function clamp(value, min, max) {
	return Math.max(min, Math.min(max, value));
}

function numberOr(value, fallback) {
	return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function degreesToRadians(degrees) {
	return degrees * Math.PI / 180;
}

function formatNumber(value) {
	return Math.abs(value - Math.round(value)) < 1.0E-9 ? String(Math.round(value)) : value.toFixed(2);
}
