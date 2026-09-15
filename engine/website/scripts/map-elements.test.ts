/**
 * 地图元素尺度模型的单测（`npm run test:elements`）。
 *
 * <p>口径（用户 2026-09-15 定死）：**世界不动，动的是摄像机**。所以元素尺寸不是"屏幕上的常数"，
 * 而是"有一个 6× 下的规格值，然后跟着倍率一起变"。这条在实现里反复搞错过三次
 * （先按缩放→又做成固定像素→再改成固定偏移），所以这里逐条钉住。</p>
 */
import {test} from "node:test";
import assert from "node:assert/strict";
import {
	DECAL_KINDS,
	REFERENCE_ZOOM,
	decalPlacement,
	decalTransform,
	mapScale,
	pixelOffset,
	scaled,
} from "../src/domain/mapElements.ts";

/** 一台相机：`scale` = 一个世界单位占多少屏幕像素；`originX/Y` = 视口左上角对应的世界坐标。 */
function camera(scale: number, originX = 0, originY = 0) {
	return {originX, originY, scale};
}

test("规格尺寸的基准是 6×：那里规格值就是屏幕值", () => {
	assert.equal(REFERENCE_ZOOM, 6, "基准倍率 6×");
	assert.equal(mapScale(REFERENCE_ZOOM), 1, "6× ⇒ 倍率换算为 1");
	assert.equal(scaled(DECAL_KINDS.icon, REFERENCE_ZOOM), 8, "6× 时图标正好 8 px（用户规格）");
	/*
	 * 区间端点圆点：用户 2026-09-15 的规格是"点的大小也改为 1px"（屏幕上直径）。
	 * `endpointDot` 是**半径**、单位是画布坐标（这一层 6× 时画布坐标 = 屏幕像素），
	 * 所以 6× 时直径 = 2 × 0.5 = 1 px。
	 */
	assert.equal(DECAL_KINDS.endpointDot, 0.5, "端点圆点半径 0.5 ⇒ 6× 时直径 1 px");
	assert.equal(DECAL_KINDS.endpointDot * 2 * mapScale(REFERENCE_ZOOM), 1, "6× 时端点直径正好 1 px");
	// 它必须比线心细得多，否则会盖住线心/状态条
	assert.equal(DECAL_KINDS.endpointDot * 2 < DECAL_KINDS.sectionBaseWidth, true,
		"端点直径 1 px 必须明显小于线心 6 px");
});

test("尺寸跟着倍率变（世界不动、动的是摄像机）", () => {
	// 推近一倍 ⇒ 尺寸翻倍；拉远一半 ⇒ 尺寸减半
	assert.equal(scaled(DECAL_KINDS.icon, 12), 16, "12× 时图标 16 px");
	assert.equal(scaled(DECAL_KINDS.icon, 3), 4, "3× 时图标 4 px");
	assert.equal(scaled(DECAL_KINDS.icon, 1), 8 / 6, "取景（1×）时按比例缩小");
	// 单调性：倍率越大、屏幕尺寸越大
	const sizes = [1, 3, 6, 12].map(z => scaled(DECAL_KINDS.icon, z));
	for (let i = 1; i < sizes.length; i++) {
		assert.equal(sizes[i]! > sizes[i - 1]!, true, "倍率增大 ⇒ 尺寸必须增大：" + sizes.join(" < "));
	}
	// 退化输入不炸
	assert.equal(mapScale(0), 1, "倍率 0（异常）回退到 1，不产生 NaN/Infinity");
	assert.equal(mapScale(-3), 1, "负倍率同理");
});

test("偏移的方向来自世界语义、距离同样跟着倍率", () => {
	// 灯：方向 = 管辖方向的垂直侧（单位向量），距离 = 规格 10 px × 倍率
	const direction = {x: 1, y: 0};
	assert.deepEqual(pixelOffset(direction, scaled(DECAL_KINDS.signalSideOffset, 6)), {x: 10, y: 0},
		"6× 时偏移 10 px");
	assert.deepEqual(pixelOffset(direction, scaled(DECAL_KINDS.signalSideOffset, 12)), {x: 20, y: 0},
		"12× 时偏移 20 px —— 与尺寸同步变化，所以相对位置不会漂");
});

test("锚点随相机变；同一倍率下『世界坐标 + 缩放后偏移』是自洽的", () => {
	const offset = pixelOffset({x: 0, y: -1}, scaled(DECAL_KINDS.signalSideOffset, 6));
	const near = decalPlacement(100, 50, camera(1, 0, 0), offset);
	const far = decalPlacement(100, 50, camera(0.5, 0, 0), offset);
	// 相机比例变了 ⇒ 锚点位置变；偏移是同一个值（因为倍率没变）——
	// 所以"位置"和"偏移"是两件独立的事，这正是不必再把它们混起来算的原因
	assert.deepEqual(near, {x: 100, y: 40}, "scale=1 时锚点 (100,50) + 偏移 (0,-10)");
	assert.deepEqual(far, {x: 50, y: 15}, "scale=0.5 时锚点 (50,25) + 同一个偏移 (0,-10)");
	assert.deepEqual({x: near.x - 100, y: near.y - 50}, {x: far.x - 50, y: far.y - 25},
		"两次偏移相同（倍率相同）；相机比例只影响锚点");
});

test("位移与旋转写在同一个 transform 里", () => {
	assert.equal(decalTransform({x: 12, y: 34}), "translate(12px, 34px)", "不转时只有位移");
	assert.equal(decalTransform({x: 12, y: 34}, 90), "translate(12px, 34px) rotate(90deg)",
		"要转时必须与位移同一个 transform（分开写会互相覆盖）");
});

test("区间状态条的像素规格：两条 2 px 落在 6 px 线心中（第 1–2 / 第 4–5 px）", () => {
	const {stripeWidth, stripeNear, stripeFar, sectionBaseWidth} = DECAL_KINDS;
	assert.equal(sectionBaseWidth, 6, "线心 6 px（用户规格）");
	assert.equal(stripeWidth, 2, "状态条 2 px（用户规格）");
	/*
	 * 用户规格（2026-09-15 最后一次纠正）："一根 6px 的线，1-2、4-5 是用于显示轨道区间的，
	 * 也就是说绘图只有三根线"。要让 2 px 的条**占**第 1–2 px 与第 4–5 px，
	 * 条中心就落在 1.5 与 4.5（条宽 2 ⇒ 覆盖 0.5–2.5 与 3.5–5.5）。
	 */
	assert.equal(stripeNear, 1.5);
	assert.equal(stripeFar, 4.5);
	const near = [stripeNear - stripeWidth / 2, stripeNear + stripeWidth / 2];
	const far = [stripeFar - stripeWidth / 2, stripeFar + stripeWidth / 2];
	assert.deepEqual(near, [0.5, 2.5], "内侧条覆盖 0.5–2.5 px");
	assert.deepEqual(far, [3.5, 5.5], "外侧条覆盖 3.5–5.5 px");
	assert.equal(far[1] <= sectionBaseWidth, true, "外侧条的最外缘仍落在 6 px 线心内（4.5 + 1 = 5.5 ≤ 6）");
	assert.equal(near[1] < far[0], true, "两条之间留 1 px 缝（2.5 → 3.5），不会糊成一条 4 px 宽带");
	// 反例：取 2 / 5（覆盖 1–3 与 4–6）时外侧那条的外缘**正好压在线心边缘**上（没有余量）
	assert.equal(5 + stripeWidth / 2 <= sectionBaseWidth, true,
		"偏移取 5 时外缘正好到 6 px（压在线心边缘）—— 所以规格是 1.5/4.5，留 0.5 px 余量");
	assert.equal(4.5 + stripeWidth / 2 <= sectionBaseWidth, true, "规格 4.5 时外缘 5.5 px，仍在线心内");
});
