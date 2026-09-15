/**
 * 信号灯图标："灯点"与"方向箭头"的错开量（`npm run test:signal`）。
 *
 * <p>用户 2026-09-15 对同一件事提了两次要求，**两条都要满足**：</p>
 * <ol>
 *   <li>"信号灯图标的点和方向指示位置需要**错开一些**"（原来两者中心重合）；</li>
 *   <li>"**现在距离又太大了**"（第一版把轴距拉到了 20 px）。</li>
 * </ol>
 *
 * <p>所以这里不是只验"不重叠"，而是**两头都钉住**：错开要够（可见空隙 ≥ 3 px），
 * 距离要小（中心距 ≤ 15 px，也就是图标直径的 1.9 倍以内）。只验一头的话，
 * 随便把间距放大或缩小都能"通过"，而两次要求里各有一条会被违反。</p>
 *
 * <h2>三处必须算进去的细节（算漏了就会得出错误结论）</h2>
 * <ol>
 *   <li><b>可见描边是那条 7 单位宽的黑色描边**</b>，不是 3.6 单位的本色笔画 ——
 *       两者中心线重合，黑色那条更宽，所以"可见外缘"由它决定（3.5 单位 ≈ 1.4 px @6×）。</li>
 *   <li><b>圆头线帽</b>（`stroke-linecap: round`）：笔画在端点外还要多出半个描边宽，
 *       所以端点也要按"圆心 + 半径"算，不能只算线段。</li>
 *   <li><b>折角是敞开的 V</b>：两条腿的可见外缘离图标中心只有 ≈3.4 px，
 *       所以"把灯点沿管辖轴往后推"并不能拉开距离 —— 灯点会落在 V 的开口里。</li>
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
/** **可见**描边宽度（viewBox 单位）：组件里黑色那条是 7，本色那条是 3.6、被它包在里面。 */
const STROKE_UNITS = 7;

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

/** 两个位置的中心距（px）。 */
function centerDistancePx(angle: number, zoomRatio: number): number {
	const {arrow, dot} = signalDecalOffset(angle, zoomRatio);
	return Math.hypot(arrow.x - dot.x, arrow.y - dot.y);
}

/** 折角外缘与灯点外缘之间的可见空隙（px）：> 0 就是"不相接"。 */
function gapPx(angle: number, zoomRatio: number): number {
	const {arrow, dot} = signalDecalOffset(angle, zoomRatio);
	return clearanceAt({x: arrow.x - dot.x, y: arrow.y - dot.y}, zoomRatio) - lampRadiusPx(zoomRatio);
}

/**
 * 把三角函数在正轴上的残渣归一化再比：`-sin(0)` 是 −0、`cos(90°)` 是 6.1e-17，
 * 而 `deepEqual` 用 `Object.is` 语义，会把它们与 0 判成不同 —— 那不是"方向错了"。
 */
function round(vector: {x: number; y: number}): {x: number; y: number} {
	const snap = (v: number) => (Math.abs(v) < 1e-9 ? 0 : Number(v.toFixed(6)));
	return {x: snap(vector.x), y: snap(vector.y)};
}

/** 八个朝向：折角是 V 形，斜向与正方向要分别验（腿与灯点的相对角度会变）。 */
const ANGLES = [0, 45, 90, 135, 180, 225, 270, 315];

test("箭头位移的方向 = 管辖方向 + 侧向（四个朝向都对得上）", () => {
	/*
	 * 侧向的符号：`signalDecalOffset` 用"管辖方向在屏幕里转 90°"，也就是 (dx,dy) → (−dy,dx)。
	 * 屏幕 y 向下，所以这是**顺时针**旋转的结果：角 0（南，朝下）的侧向是 −x（屏幕左）。
	 * 换句话说是"管辖方向那一侧的反面"，与"信号机立在它所管辖列车的左侧"这条现实做法一致
	 * （`Signal.sideOffsetDirection` 的注释里有实测依据：南行灯要往西挪）。
	 */
	const cases: readonly [number, string, {forward: {x: number, y: number}}][] = [
		[0, "角 0 管南", {forward: {x: 0, y: 1}}],
		[90, "角 90 管西", {forward: {x: -1, y: 0}}],
		[180, "角 180 管北", {forward: {x: 0, y: -1}}],
		[270, "角 270 管东", {forward: {x: 1, y: 0}}],
	];
	/*
	 * 侧向不写死四个坐标，而是按它的**定义**验："管辖方向在屏幕里转 90°，即 (dx,dy) → (−dy,dx)"。
	 * 手写四个期望值试过两次都写反（屏幕 y 向下，顺时针/逆时针一不留神就错），
	 * 而定义本身是硬的：既垂直、又是那个转向、又是单位长度。
	 */
	for (const [angle, why, expected] of cases) {
		const signal = new Signal({key: "k", x: 0, y: 0, z: 0, angle});
		assert.deepEqual(round(signal.bearingDirection), expected.forward, why + "：管辖方向");
		const side = signal.sideOffsetDirection;
		const {x: fx, y: fy} = expected.forward;
		assert.deepEqual(round(side), {x: Number((-fy).toFixed(6)), y: Number(fx.toFixed(6))}, why + "：侧向 = 管辖方向转 90°");
		assert.equal(Math.abs(side.x * fx + side.y * fy) < 1e-9, true, why + "：侧向与管辖方向垂直");
		assert.equal(Math.abs(Math.hypot(side.x, side.y) - 1) < 1e-9, true, why + "：侧向是单位向量");
		const {arrow, dot} = signalDecalOffset(angle, REFERENCE_ZOOM);
		const forward = arrow.x * expected.forward.x + arrow.y * expected.forward.y;
		assert.equal(Math.abs(forward - DECAL_KINDS.signalArrowForward) < 1e-9, true,
			`${why}：前移分量应为 ${DECAL_KINDS.signalArrowForward}（实得 ${forward.toFixed(3)}）`);
		const sideComponent = arrow.x * side.x + arrow.y * side.y;
		assert.equal(Math.abs(sideComponent - DECAL_KINDS.signalArrowSide) < 1e-9, true,
			`${why}：侧向分量应为 ${DECAL_KINDS.signalArrowSide}（实得 ${sideComponent.toFixed(3)}）`);
		// 灯点留在锚点上（规格 signalDotBack = 0）：它的世界坐标就是"信号机立在哪"
		assert.deepEqual(round(dot), {x: 0, y: 0}, why + "：灯点在锚点上");
	}
});

test("错开要够：八个朝向 × 三个缩放档都不重叠", () => {
	for (const zoom of [1, REFERENCE_ZOOM, 12]) {
		for (const angle of ANGLES) {
			assert.equal(gapPx(angle, zoom) > 0, true,
				`${zoom}× / 角 ${angle}°：折角外缘与灯点外缘重叠了（空隙 ${gapPx(angle, zoom).toFixed(2)} px）`);
		}
	}
});

test("错开要够：可见空隙 ≥ 3 px（八个朝向都验）", () => {
	const gaps = ANGLES.map(angle => gapPx(angle, REFERENCE_ZOOM));
	const worst = Math.min(...gaps);
	assert.equal(worst >= 3, true,
		`最小可见空隙只有 ${worst.toFixed(2)} px（要 ≥ 3 px）—— 屏幕上会看着像粘在一起`);
	/*
	 * 空隙在"正方向"上恒定、在斜向上更大：斜向时灯点落在折角的**斜对角**上，
	 * 离两条腿都远。所以能钉住的是"最小空隙够宽"，不是"全都相等" ——
	 * 写一条"必须相等"的断言会红，而且红得有道理（是判据错了，不是代码错了）。
	 * 真正要挡的是"与朝向耦合到最坏朝向贴上去"，那由上面那条兜住。
	 */
});

test("距离要小：中心距 ≤ 16 px（用户第二次要求）", () => {
	// 中心距 = √(前移² + 侧向²)，与朝向无关
	const expected = Math.hypot(DECAL_KINDS.signalArrowForward, DECAL_KINDS.signalArrowSide);
	for (const angle of ANGLES) {
		const actual = centerDistancePx(angle, REFERENCE_ZOOM);
		assert.equal(Math.abs(actual - expected) < 1e-9, true,
			`角 ${angle}°：中心距 ${actual.toFixed(2)} px 与规格算出的 ${expected.toFixed(2)} px 不一致`);
	}
	/*
	 * 两头都要卡住：上面那条要求空隙 ≥ 3 px，而空隙只能靠**更大的侧向**换；
	 * 所以 16 px 是"空隙够 + 距离小"这两条同时成立的位置（第一版是轴距 20 px，
	 * 用户就是嫌它大 —— 而图标直径只有 8 px）。
	 */
	assert.equal(expected <= 16, true,
		`中心距 ${expected.toFixed(2)} px > 16 px —— 第一版是 20 px，用户嫌大的就是它`);
	assert.equal(expected >= 8, true,
		`中心距 ${expected.toFixed(2)} px < 8 px —— 比一个图标直径还小，两个图形会挤在一起`);
	// 顺带把"它确实比第一版小"记在断言里：第一版是 12 + 8 = 20
	assert.equal(expected < 20, true, "必须比第一版的轴距 20 px 小（用户第二次要求的来历）");
});

/*
 * 红证：把"侧向让开"归零 ⇒ 灯点落回折角的 V 里 ⇒ 上面"错开要够"必须**红**。
 * 这条的作用不是"再验一遍"，而是证明判据真的在看着那个规格值（否则它可能恒真）。
 */
test("红证：归零会让判据变红（判据确实在看着规格值）", () => {
	// ① 侧向归零（只前移）：这就是第一版的失败形态
	const alongOnly = ANGLES.map(angle => {
		const {arrow, dot} = signalDecalOffset(angle, REFERENCE_ZOOM);
		// 手工去掉侧向分量
		const forward = {x: arrow.x, y: arrow.y};
		const signal = new Signal({key: "k", x: 0, y: 0, z: 0, angle});
		const side = signal.sideOffsetDirection;
		const along = forward.x * side.x + forward.y * side.y;
		const pure = {x: forward.x - side.x * along, y: forward.y - side.y * along};
		return clearanceAt({x: pure.x - dot.x, y: pure.y - dot.y}, REFERENCE_ZOOM) - lampRadiusPx(REFERENCE_ZOOM);
	});
	assert.equal(Math.min(...alongOnly) < 0, true,
		`只沿管辖方向前移时最小空隙 ${Math.min(...alongOnly).toFixed(2)} px —— 判据没在量侧向那个规格值`);
	// ② 全部归零（= 改之前的样子：两个图形中心重合）：不再是"3 px 可见空隙"
	const centered = clearanceAt({x: 0, y: 0}, REFERENCE_ZOOM) - lampRadiusPx(REFERENCE_ZOOM);
	/*
	 * 注意这里**不能**断言 "≤ 0"：中心重合时折角两条腿的**线心**离中心还有 3.5 px，
	 * 减掉半个描边（1.4）与灯点半径（2）之后还剩 ≈ 0.83 px —— 几何上"没接触"，
	 * 但那是"折角的尖正好落在灯点边上"：0.83 px 的空隙在屏幕上就是贴着，
	 * 而判据要的是 ≥ 3 px。所以红证的判据取"小于门槛"，与判据本身同一条尺子。
	 */
	assert.equal(centered < 3, true,
		`中心重合时空隙 ${centered.toFixed(2)} px（应当远小于门槛 3 px）—— 判据没在量错开量`);
});

test("位移跟着倍率走（推近了不会又粘上）", () => {
	const near = signalDecalOffset(0, 1);
	const far = signalDecalOffset(0, 12);
	assert.equal(Math.abs(far.arrow.y / near.arrow.y - 12) < 1e-9, true,
		`12× 的箭头位移应当是 1× 的 12 倍（实得 ${(far.arrow.y / near.arrow.y).toFixed(3)}）`);
	// 中心距同理（两个分量都乘同一个倍率）
	assert.equal(Math.abs(centerDistancePx(0, 12) / centerDistancePx(0, 1) - 12) < 1e-9, true,
		"中心距也必须按倍率缩放");
});
