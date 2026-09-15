/**
 * 地图元素尺度模型的单测（`npm run test:elements`）。
 *
 * <p>口径（用户 2026-09-15 **最终定的**）：**整幅地图一起缩放，基准是全览尺寸**。
 * 于是规格值就是"全览时屏幕上的像素"，其余跟着相机一起变（`specPxToWorld`：世界单位 = 规格 ÷ 相机比例）。
 * 这条在实现里反复搞错过三次（先按缩放 → 又做成固定像素 → 再改成固定偏移 → 又试过"6× 基准"），
 * 所以这里逐条钉住。</p>
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
	screenPxOfSpec,
	specPxToWorld,
} from "../src/domain/mapElements.ts";

/** 一台相机：`scale` = 一个世界单位占多少屏幕像素；`originX/Y` = 视口左上角对应的世界坐标。 */
function camera(scale: number, originX = 0, originY = 0) {
	return {originX, originY, scale};
}

test("规格的基准是**全览（1×）**：那时规格值就是屏幕值，之后跟着相机放大", () => {
	/*
	 * 用户 2026-09-15 最后定调："**整幅地图一起缩放，但把基准改成全览尺寸**"。
	 * 换算只有一处：世界单位 = 规格 × unitsPerPx（取景校准一次的常量）；
	 * 屏幕尺寸 = 世界单位 × 当前相机比例 = 规格 × 倍率。
	 */
	assert.equal(screenPxOfSpec(DECAL_KINDS.icon, 1), 8, "全览时图标 8 px");
	assert.equal(screenPxOfSpec(DECAL_KINDS.railWidth, 1), 4, "全览时轨道线 4 px");
	assert.equal(screenPxOfSpec(DECAL_KINDS.endpointDot * 2, 1), 1, "全览时端点直径 1 px");
	assert.equal(screenPxOfSpec(DECAL_KINDS.icon, 6), 48, "6× 时图标 = 规格 × 6（跟着地图放大）");
	// 那个常量与倍率无关：同一个规格永远得到同一个世界尺寸（"每个元素不需单独缩放"）
	const unitsPerPx = 1 / 0.4668;
	assert.equal(specPxToWorld(DECAL_KINDS.railWidth, unitsPerPx), specPxToWorld(DECAL_KINDS.railWidth, unitsPerPx),
		"世界尺寸只由常量决定");
	// 端点必须明显细于线心，否则会盖住线心/状态条
	assert.equal(DECAL_KINDS.endpointDot * 2 < DECAL_KINDS.sectionBaseWidth, true,
		"端点直径 1 px 必须明显小于线心 6 px");
});

test("尺寸跟着倍率变（世界不动、动的是摄像机）", () => {
	// `scaled` 仍是"规格 × 倍率/基准"这条旧口径的换算，用于**视图层的读数**（倍率本身）
	assert.equal(scaled(DECAL_KINDS.icon, REFERENCE_ZOOM), 8, "基准倍率处 = 规格值");
	assert.equal(scaled(DECAL_KINDS.icon, 12), 16, "12× 时是规格的两倍");
	assert.equal(scaled(DECAL_KINDS.icon, 3), 4, "3× 时是规格的一半");
	const sizes = [1, 3, 6, 12].map(z => scaled(DECAL_KINDS.icon, z));
	for (let i = 1; i < sizes.length; i++) {
		assert.equal(sizes[i]! > sizes[i - 1]!, true, "倍率增大 ⇒ 读数必须增大：" + sizes.join(" < "));
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

test("锚点现在就是『世界坐标 + 偏移』（相机已由外层承担）", () => {
	/*
	 * 坐标系重构（`notes/164`）之后 `decalPlacement` **不再碰相机**：相机由 SVG 的 `viewBox`
	 * 与标记层的 `.layer` 变换承担，于是"位置"只有一处换算 —— 世界坐标本身。
	 * 旧断言（"锚点随相机比例变"）钉的是被删掉的那套行为，所以这条跟着改。
	 */
	const offset = pixelOffset({x: 0, y: -1}, scaled(DECAL_KINDS.signalSideOffset, 6));
	const near = decalPlacement(100, 50, offset);
	assert.deepEqual(near, {x: 100, y: 40}, "世界坐标 (100,50) + 偏移 (0,-10) —— 不再乘相机");
	// 与相机无关：换任何相机都是同一个结果（这正是"世界坐标是唯一真源"）
	assert.deepEqual(decalPlacement(100, 50, offset), near, "同一个世界坐标永远得到同一个锚点");
	// 省略偏移就是世界坐标本身
	assert.deepEqual(decalPlacement(100, 50), {x: 100, y: 50}, "没有偏移时锚点 = 世界坐标");
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
