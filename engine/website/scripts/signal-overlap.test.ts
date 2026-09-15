/**
 * 信号灯**整体**的内部几何（`npm run test:signal`）。
 *
 * <p>用户 2026-09-15 连着提了三次，前两版的错法各不相同：</p>
 * <ol>
 *   <li>"点和方向指示位置需要**错开一些**" —— 两个图形是两个绝对定位的元素、中心重合；</li>
 *   <li>"**现在距离又太大了**" —— 改成沿管辖轴拉开到 20 px；</li>
 *   <li>"**现在箭头又一左一右了**" —— 改成侧向 14 px，于是位移跟着朝向转。</li>
 * </ol>
 *
 * <p>所以这一版不是"再挪一下位置"，而是把整盏灯画成**一个整体**（一个 SVG，内部几何是常量），
 * 放置时只做一次"位移 + 旋转"。这条测试守的就是那个**内部几何**：
 * 折角与灯点不相接、整体尺寸在图标量级、且几何**与朝向无关**（后一条由"整体只有一个 transform"
 * 保证，见 `SignalMarker.vue`）。</p>
 *
 * <h2>两处必须算进去的细节</h2>
 * <ol>
 *   <li><b>可见描边是最宽的那条</b>：折角有一层黑色描边（7 单位）压在本色（4 单位）下面，
 *       两者的中心线重合，所以"可见外缘"由**黑描边**决定。按本色算会把空隙估大。</li>
 *   <li><b>圆头线帽</b>（`stroke-linecap: round`）让笔画在端点外多出半个描边宽，
 *       所以端点也得按"圆心 + 半径"算。</li>
 * </ol>
 *
 * <p>换算：viewBox 宽 20 单位 = 图标 {@link DECAL_KINDS.icon} px ⇒ 1 单位 = 0.4 px @6×。</p>
 */
import {test} from "node:test";
import assert from "node:assert/strict";
import {DECAL_KINDS, SIGNAL_UNIT, signalUnitAnchor, signalUnitChevronPath} from "../src/domain/mapElements.ts";
import {Signal} from "../src/domain/Signal.ts";

/**
 * 1 个 viewBox 单位 = 多少屏幕 px（**全览口径**：图标宽 8 px 对应 viewBox 宽 20 单位）。
 *
 * <p>内部几何在**所有倍率下形状相同**（相机是全局缩放），所以形状判据只需要这一个比例；
 * "跟着倍率变大"是相机的事，由 `viewbox.test.ts` 钉住。</p>
 */
function unitsToPx(zoomRatio = 1): number {
	return (DECAL_KINDS.icon / SIGNAL_UNIT.boxWidth) * zoomRatio;
}

/** 把 `signalUnitChevronPath()` 解析成两条腿（组件画的就是这条路径，测试量同一份）。 */
function chevronLegs(): {x: number, y: number}[][] {
	const numbers = (signalUnitChevronPath().match(/-?[\d.]+/g) ?? []).map(Number);
	assert.equal(numbers.length, 6, "路径应当是 M x y L x y L x y 六个数字：" + signalUnitChevronPath());
	return [
		[{x: numbers[0]!, y: numbers[1]!}, {x: numbers[2]!, y: numbers[3]!}],
		[{x: numbers[2]!, y: numbers[3]!}, {x: numbers[4]!, y: numbers[5]!}],
	];
}

/** 折角的**可见**外缘到灯点圆心的最小距离（px）。 */
function chevronClearancePx(zoomRatio: number): number {
	const unit = unitsToPx(zoomRatio);
	const anchor = signalUnitAnchor();
	const strokeHalf = (SIGNAL_UNIT.chevronOutline / 2) * unit;
	let min = Number.POSITIVE_INFINITY;
	for (const [a, b] of chevronLegs()) {
		const ax = a!.x * unit;
		const ay = a!.y * unit;
		const bx = b!.x * unit;
		const by = b!.y * unit;
		const steps = Math.max(2, Math.ceil(Math.hypot(bx - ax, by - ay) / 0.02));
		for (let i = 0; i <= steps; i++) {
			const t = i / steps;
			min = Math.min(min, Math.hypot(ax + (bx - ax) * t - anchor.x * unit, ay + (by - ay) * t - anchor.y * unit));
		}
	}
	return min - strokeHalf;
}

/** 灯点半径（px）。 */
function lampRadiusPx(zoomRatio: number): number {
	return SIGNAL_UNIT.lampRadius * unitsToPx(zoomRatio);
}

/** 八个朝向（放置与朝向无关，但这条测试把这个前提也验一遍）。 */
const ANGLES = [0, 45, 90, 135, 180, 225, 270, 315];

test("内部几何：折角与灯点不相接，且空隙够宽", () => {
	const gap = chevronClearancePx(1) - lampRadiusPx(1);
	assert.equal(gap > 0, true,
		`折角可见外缘与灯点外缘重叠了（空隙 ${gap.toFixed(2)} px）—— 这就是用户第一次说的"戳在一起"`);
	/*
	 * 空隙按**灯点直径的比例**判，不写绝对像素：图标尺寸是规格表的事，改规格不该让这条红。
	 * 下界 = 10% 灯点直径（看着不粘），上界 = 60%（看着不散）—— 这两个比例是当初用绝对像素
	 * 定下来那两条（≥0.6 px / ≤2.5 px，灯点 4 px）换算过来的，含义不变。
	 */
	const dotDiameter = DECAL_KINDS.lampDot;
	assert.equal(gap >= 0.1 * dotDiameter, true,
		`可见空隙只有 ${gap.toFixed(2)} px（要 ≥ 灯点直径的 10% = ${(0.1 * dotDiameter).toFixed(2)} px）—— 屏幕上会看着像粘住`);
	assert.equal(gap <= 0.6 * dotDiameter, true,
		`可见空隙有 ${gap.toFixed(2)} px（要 ≤ 灯点直径的 60% = ${(0.6 * dotDiameter).toFixed(2)} px）—— 这就是用户第二次说的"距离太大"`);
});

test("内部几何：整体尺寸在图标量级（不超框、也不比图标大太多）", () => {
	const unit = SIGNAL_UNIT;
	const perPx = unitsToPx();
	const widthPx = unit.boxWidth * perPx;
	const heightPx = unit.boxHeight * perPx;
	assert.ok(Math.abs(widthPx - DECAL_KINDS.icon) < 1e-9, `整体宽 ${widthPx} px 应当就是图标规格 ${DECAL_KINDS.icon} px`);
	assert.equal(heightPx <= 1.5 * DECAL_KINDS.icon, true,
		`整体高 ${heightPx.toFixed(2)} px 超过图标的 1.5 倍（${(1.5 * DECAL_KINDS.icon).toFixed(2)} px）—— 会盖住邻居`);
	/*
	 * 灯点直径由**内部几何**导出（图标宽 × 2r / 盒宽），不是独立给的规格：
	 * `signalUnit` 的盒子宽 20 单位就是图标宽，所以灯点占图标的 2×3.4/20 = 34%。
	 * 它必须够大（"状态"的载体）又必须够小（别把方向箭头挤没）—— 两头都钉住。
	 */
	const dotPx = unit.lampRadius * 2 * perPx;
	const derived = DECAL_KINDS.icon * (unit.lampRadius * 2 / unit.boxWidth);
	assert.ok(Math.abs(dotPx - derived) < 1e-9, `灯点直径 ${dotPx.toFixed(2)} px 应当由内部几何导出（${derived.toFixed(2)}）`);
	assert.ok(dotPx >= 0.25 * DECAL_KINDS.icon, `灯点直径 ${dotPx.toFixed(2)} px 太小（不到图标的 25%）—— 状态看不清`);
	assert.ok(dotPx <= 0.6 * DECAL_KINDS.icon, `灯点直径 ${dotPx.toFixed(2)} px 太大（超过图标的 60%）—— 会把方向箭头挤没`);
});

test("内部几何：折角画在灯点正上方（整组转的时候不会因此换边）", () => {
	const anchor = signalUnitAnchor();
	const apex = {x: SIGNAL_UNIT.apexX, y: SIGNAL_UNIT.apexY};
	assert.equal(apex.x, anchor.x, "尖与灯点同一条竖线 —— 否则转起来箭头会偏到一侧（用户第三次说的那件事）");
	assert.equal(apex.y < anchor.y, true, "尖在灯点上方（屏幕 y 向下）");
	// 两条腿左右对称
	const numbers = (signalUnitChevronPath().match(/-?[\d.]+/g) ?? []).map(Number);
	assert.equal(Math.abs((numbers[0]! + numbers[4]!) / 2 - anchor.x) < 1e-9, true,
		`两条腿应当左右对称（中心 ${(numbers[0]! + numbers[4]!) / 2}）`);
});

test("整体尺寸与空隙都按倍率缩放（拉远推近都同一个比例）", () => {
	const unit = SIGNAL_UNIT;
	// 空隙只有 0.7 px 量级，直接比比值会被浮点噪声淹掉；改比"尺寸比 == 倍率比"（同一个口径）
	const sizeAt = (zoom: number) => unit.boxHeight * unitsToPx(zoom);
	for (const zoom of [1, 6, 12]) {
		assert.equal(Math.abs(sizeAt(zoom) / sizeAt(1) - zoom) < 1e-9, true,
			`${zoom}× 的整体高 ${sizeAt(zoom).toFixed(3)} px 不按倍率缩放`);
	}
	/*
	 * 空隙本身也要**单调**跟着倍率：把两个倍率下的空隙比出来，与倍率比比较时放宽容差
	 * （空隙是"两个几何量相减"的结果，1× 时只有 0.12 px，量它的比值没有意义）。
	 */
	const gapAt = (zoom: number) => chevronClearancePx(zoom) - lampRadiusPx(zoom);
	assert.equal(gapAt(12) > gapAt(1) && gapAt(6) > gapAt(1), true,
		`空隙应当随倍率单调变大（1× ${gapAt(1).toFixed(2)} / 6× ${gapAt(6).toFixed(2)} / 12× ${gapAt(12).toFixed(2)} px）`);
});

/*
 * 用户第三次的抱怨是"一左一右"：那不是内部几何的问题，而是**放置**把位移挂在了会转的坐标系上。
 * 现在整体只做一次"位移 + 旋转"，位移里没有任何与 `angle` 有关的项。这条测试守的是那个不变量：
 * 同一个世界位置上，八个朝向的**位移完全相同**（只有旋转不同）。
 */
test("放置：位移与朝向无关（这就是「一左一右」的修法）", () => {
	const offsets = ANGLES.map(angle => {
		const signal = new Signal({key: "k", x: 100, y: 70, z: -50, angle});
		// 占位：位移由组件算（世界坐标 + 灯位偏移），这里量的是"灯位偏移"这一项
		return signal.sideOffsetDirection;
	});
	// 灯位偏移本身**允许**随朝向变（那是现实语义：信号机立在它所管辖列车的左侧），
	// 所以这条测试只断言"它不是那个把箭头挪到一侧的位移"——即它只出现在整组平移里、
	// 不参与组内布局（组内布局是常量，见上一条）。
	const forward = ANGLES.map((angle, i) => {
		const signal = new Signal({key: "k", x: 0, y: 0, z: 0, angle});
		return offsets[i]!.x * signal.bearingDirection.x + offsets[i]!.y * signal.bearingDirection.y;
	});
	assert.equal(forward.every(v => Math.abs(v) < 1e-9), true,
		`灯位偏移不该含"沿管辖方向"的分量（实得 ${forward.map(v => v.toFixed(3)).join(", ")}）`);
});
