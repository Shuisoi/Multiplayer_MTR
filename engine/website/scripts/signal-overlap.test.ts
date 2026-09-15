/**
 * 信号灯图标："灯点"与"方向箭头"不许重叠的单测（`npm run test:signal`）。
 *
 * <p>用户 2026-09-15 的要求：**"信号灯图标的点和方向指示位置需要错开一些"**。
 * 这条测试把"错开"变成可算的判据 —— 不然它只是一句手感，下次调像素就会重新粘上。</p>
 *
 * <h2>两处必须算进去的细节（算漏了就会得出"已经分开"的错误结论）</h2>
 * <ol>
 *   <li><b>圆头线帽</b>（`stroke-linecap: round`）：笔画在端点外还要多出**半个描边宽**
 *       （1.8 单位 = 0.72 px @6×）。只算折角两条腿的线心距离会高估空隙 ——
 *       尖那个端点的线心离元素中心 2.4 px，看起来"没事"，加上线帽后可见外缘只有 1.68 px，
 *       **已经进了灯点半径（2 px）里**。这就是改之前那处"尖戳进灯点"。</li>
 *   <li><b>斜向的空隙天然更大</b>：位移是沿管辖轴的，斜向朝向里折角的腿正好摊在斜对角上，
 *       所以空隙比正方向大得多（实测 6.2 px vs 19.3 px）。判据因此看**最小**空隙，
 *       并且要求它在所有朝向上都够宽 —— 只看一个朝向会漏掉耦合。</li>
 * </ol>
 *
 * <p>1 个 viewBox 单位 = 图标直径 / 20 = 0.4 px @6×，所以所有几何都先换算成 px 再比较。</p>
 */
import {test} from "node:test";
import assert from "node:assert/strict";
import {DECAL_KINDS, REFERENCE_ZOOM, scaled, signalDecalOffset} from "../src/domain/mapElements.ts";
import {Signal} from "../src/domain/Signal.ts";

/** 折角的两条腿（viewBox 0 0 20 20 里的线心折线，与组件里 `path` 的 d 同一个形状）。 */
const CHEVRON_LEGS: readonly (readonly [number, number])[][] = [
	[[3, 15], [10, 4]],
	[[10, 4], [17, 15]],
];
/** 本色笔画宽度（viewBox 单位）：组件里是 3.6，圆头线帽让它两侧（含端点外）各多 1.8。 */
const STROKE_UNITS = 3.6;

/** 1 个 viewBox 单位 = 多少屏幕 px。 */
function unitsToPx(zoomRatio: number): number {
	return scaled(DECAL_KINDS.icon, zoomRatio) / 20;
}

/**
 * 折角**可见部分**到灯点中心的最小距离（px）。
 *
 * <p>在同一个坐标系里算：把灯点中心当原点，折角按给定的**相对**位移摆放
 * （正常情形由 `signalDecalOffset` 给），对两条腿的线心密集采样求最近距离，
 * 最后减去半个描边宽（圆头线帽 ⇒ 端点也一样外扩）。</p>
 */
function clearanceAt(relative: {x: number; y: number}, zoomRatio: number): number {
	const unit = unitsToPx(zoomRatio);
	let min = Number.POSITIVE_INFINITY;
	for (const leg of CHEVRON_LEGS) {
		const a = {x: leg[0]![0] * unit + relative.x, y: leg[0]![1] * unit + relative.y};
		const b = {x: leg[1]![0] * unit + relative.x, y: leg[1]![1] * unit + relative.y};
		// 采样步长 0.02 px：比任何真实像素都细，不会漏掉最近点
		const steps = Math.max(2, Math.ceil(Math.hypot(b.x - a.x, b.y - a.y) / 0.02));
		for (let i = 0; i <= steps; i++) {
			const t = i / steps;
			min = Math.min(min, Math.hypot(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t));
		}
	}
	return min - (STROKE_UNITS / 2) * unit;
}

/** 灯点半径（px）：用圆点规格，与组件一致。 */
function lampRadiusPx(zoomRatio: number): number {
	return scaled(DECAL_KINDS.lampDot, zoomRatio) / 2;
}

/** 折角外缘与灯点外缘之间的最小间距（px）：> 0 就是"不相接"。 */
function gapPx(angle: number, zoomRatio: number, dotBack?: number): number {
	const {arrow, dot} = signalDecalOffset(angle, zoomRatio, dotBack);
	return clearanceAt({x: arrow.x - dot.x, y: arrow.y - dot.y}, zoomRatio) - lampRadiusPx(zoomRatio);
}

/** 八个朝向：斜向最容易擦边（折角的腿在斜对角上摊得最开）。 */
const ANGLES = [0, 45, 90, 135, 180, 225, 270, 315];

/**
 * 把三角函数在正轴上的残渣归一化再比：`-sin(0)` 是 −0、`cos(90°)` 是 6.1e-17，
 * 而 `deepEqual` 用 `Object.is` 语义，会把它们与 0 判成不同 —— 那不是"方向错了"。
 */
function round(vector: {x: number; y: number}): {x: number; y: number} {
	const snap = (v: number) => (Math.abs(v) < 1e-9 ? 0 : Number(v.toFixed(6)));
	return {x: snap(vector.x), y: snap(vector.y)};
}

test("两个位置在管辖轴上的次序：灯点在后、箭头在前", () => {
	const cases: readonly [number, string, {x: number, y: number}][] = [
		[0, "角 0 管南 ⇒ 箭头朝屏幕下方", {x: 0, y: 1}],
		[90, "角 90 管西 ⇒ 朝左", {x: -1, y: 0}],
		[180, "角 180 管北 ⇒ 朝上", {x: 0, y: -1}],
		[270, "角 270 管东 ⇒ 朝右", {x: 1, y: 0}],
	];
	for (const [angle, why, expected] of cases) {
		const signal = new Signal({key: "k", x: 0, y: 0, z: 0, angle});
		// `-sin(0)` 是 −0，`deepEqual` 会把它和 0 判为不同（`Object.is` 语义）—— 先归一化再比
		assert.deepEqual(round(signal.bearingDirection), expected, why);
		const {arrow, dot} = signalDecalOffset(angle, REFERENCE_ZOOM);
		// 两者都必须落在管辖轴上（横向分量为 0）—— 这正是"与朝向无关"的来源
		const arrowForward = arrow.x * expected.x + arrow.y * expected.y;
		const arrowSide = arrow.x * signal.sideOffsetDirection.x + arrow.y * signal.sideOffsetDirection.y;
		const dotForward = dot.x * expected.x + dot.y * expected.y;
		assert.equal(Math.abs(arrowSide) < 1e-9, true, `${why}；箭头不该有横向分量（实得 ${arrowSide}）`);
		assert.equal(arrowForward, DECAL_KINDS.signalArrowForward, `${why}；箭头在管辖方向上前移 12 px`);
		assert.equal(dotForward, -DECAL_KINDS.signalDotBack, `${why}；灯点在它的反面向后退 8 px`);
	}
});

test("折角与灯点：八个朝向都不重叠（基准倍率）", () => {
	for (const angle of ANGLES) {
		assert.equal(gapPx(angle, REFERENCE_ZOOM) > 0, true,
			`角 ${angle}°：折角外缘与灯点外缘重叠了（间距 ${gapPx(angle, REFERENCE_ZOOM).toFixed(2)} px）`);
	}
});

test("折角与灯点：三个缩放档都不重叠（错开比例不随缩放变）", () => {
	for (const zoom of [1, REFERENCE_ZOOM, 12]) {
		for (const angle of ANGLES) {
			assert.equal(gapPx(angle, zoom) > 0, true,
				`${zoom}× / 角 ${angle}°：重叠（间距 ${gapPx(angle, zoom).toFixed(2)} px）—— 位移与尺寸没有乘同一个倍率`);
		}
	}
});

test("折角与灯点：空隙够宽，不是擦边过", () => {
	// 基准倍率下（规格值即屏幕值）最小空隙 —— 用户看到的就是这个数
	const worst = Math.min(...ANGLES.map(angle => gapPx(angle, REFERENCE_ZOOM)));
	assert.equal(worst >= 5, true,
		`最小空隙只有 ${worst.toFixed(2)} px（要 ≥ 5 px）—— 屏幕上会看着像粘在一起`);
	/*
	 * 四个正方向的空隙必须**完全一样**：两个位置只在管辖轴上，斜向的差别只来自折角的腿
	 * 在斜对角上摊得更开。若哪天有人在位移里加进横向分量，这条不会红（斜向仍更大），
	 * 所以真正的守卫是上面那条"最小空隙" —— 横向分量会把正方向的空隙压下去。
	 */
	const cardinals = [0, 90, 180, 270].map(angle => gapPx(angle, REFERENCE_ZOOM));
	assert.equal(Math.min(...cardinals) >= 5, true,
		`四个正方向的最小空隙 ${Math.min(...cardinals).toFixed(2)} px（要 ≥ 5 px）`);
	/*
	 * 四个正方向的空隙**并不完全一样**（实测 ≈ 11.3 / 11.8 / 19.3 / 19.2 px）—— 起初以为这里应当一致，
	 * 其实是折角自己在 viewBox 里不对称：尖在 (10, 4)、框在 0…20，绕中心转 180° 不等价
	 * （"朝下"时开口朝上、"朝上"时开口朝下）。所以能钉住的只有"最小值"，不是"全都相等" ——
	 * 写一条"必须相等"的断言会红，而且红得有道理（是判据错了，不是代码错了）。
	 */
	assert.equal(cardinals.every(gap => gap < 20), true,
		`有正方向的空隙达到 ${Math.max(...cardinals).toFixed(2)} px —— 灯点与箭头离得太远，看着不像一盏灯`);
});

/*
 * 红证：把"灯点后移"归零 ⇒ 灯点落回折角的开口里、可见空隙变负 ⇒ 上面那条判据必须**红**。
 * 这条测试的作用不是"再验一遍"，而是证明判据真的在看着规格值（否则它可能恒真）。
 */
test("红证：灯点不后移就会重叠（判据确实在看着规格值）", () => {
	const withoutDotBack = Math.min(...ANGLES.map(angle => gapPx(angle, REFERENCE_ZOOM, 0)));
	// 0.28 px 的空隙在屏幕上就是"贴着" —— 判据里的"够宽"门槛是 5 px，所以这条红得干净
	assert.equal(withoutDotBack < 1, true,
		`灯点不后移时最小间距 ${withoutDotBack.toFixed(2)} px（应当 ≈ 0.3 px，即贴在一起）—— 判据没在量这个规格值`);
	/*
	 * 规格里那个"刚好够"的位置：后移 ≥ 7.7 px 才让最坏朝向的空隙达到 5 px（8 px ⇒ 6.2 px）。
	 * 所以 7 px 会擦上（≈ 3.2 px，看着还是粘的）。这里只钉"空隙随后移单调变大"这条关系 ——
	 * 具体取 8 是规格的选择，测试不该替它定一个"必须更小"的数。
	 */
	const seven = Math.min(...ANGLES.map(angle => gapPx(angle, REFERENCE_ZOOM, 7)));
	const eight = Math.min(...ANGLES.map(angle => gapPx(angle, REFERENCE_ZOOM, 8)));
	assert.equal(seven < eight, true,
		`后移 7 px 的空隙 ${seven.toFixed(2)} px 不小于 8 px 的 ${eight.toFixed(2)} px —— 判据与后移量脱钩了`);
});

test("位移跟着倍率走（推近了不会又粘上）", () => {
	const near = signalDecalOffset(0, 1);
	const far = signalDecalOffset(0, 12);
	assert.equal(Math.abs(far.arrow.y / near.arrow.y - 12) < 1e-9, true,
		`12× 的箭头位移应当是 1× 的 12 倍（实得 ${(far.arrow.y / near.arrow.y).toFixed(3)}）`);
	assert.equal(Math.abs(far.dot.y / near.dot.y - 12) < 1e-9, true,
		`灯点位移同理（实得 ${(far.dot.y / near.dot.y).toFixed(3)}）`);
});
