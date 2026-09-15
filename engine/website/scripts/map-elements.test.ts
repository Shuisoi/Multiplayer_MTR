/**
 * 地图元素尺度模型的单测（`npm run test:elements`）。
 *
 * <p>为什么值得单独测：这个模块是"缩放时元素会不会变形/滑走"的**唯一判据来源**，
 * 而它埋在渲染里根本断言不了 —— 之前只能靠无头浏览器量像素，且量错了两次
 * （先做成随缩放、又改成固定偏移）。这里把规则抽出来，直接钉住：</p>
 * <ul>
 *   <li>贴片的尺寸是**常量**，不随缩放变；</li>
 *   <li>贴片的锚点位置**随相机变**，而偏移是**固定像素**（所以相对锚点不会滑走）；</li>
 *   <li>位移与旋转在**同一个 transform** 里（分开写会互相覆盖）。</li>
 * </ul>
 */
import {test} from "node:test";
import assert from "node:assert/strict";
import {DECAL_BASE_PX, DECAL_KINDS, decalPlacement, decalScale, decalTransform, pixelOffset} from "../src/domain/mapElements.ts";

/** 一台相机：`scale` = 一个世界单位占多少屏幕像素；`originX/Y` = 视口左上角对应的世界坐标。 */
function camera(scale: number, originX = 0, originY = 0) {
	return {originX, originY, scale};
}

test("贴片尺寸是常量，与相机无关", () => {
	// 这正是"图标在缩小时不跟着缩"那条规格：尺寸只由 DECAL_KINDS 决定，函数里没有 camera 这个入参
	assert.equal(DECAL_KINDS.icon, DECAL_BASE_PX, "图标 = 统一基准 8 px");
	assert.equal(DECAL_KINDS.lampDot < DECAL_KINDS.icon, true, "灯点必须小于图标，否则箭头被自己盖住");
	assert.equal(DECAL_KINDS.endpointDot < DECAL_KINDS.icon, true, "端点圆点要比图标小");
	// decalScale 只由规格决定，不看缩放
	assert.equal(decalScale(DECAL_BASE_PX, "icon"), 1, "基准与规格一致时比例是 1");
	assert.equal(decalScale(16, "icon"), 0.5, "16 px 的老值换算到 8 px 规格 = 0.5");
});

test("锚点位置随相机变，固定像素偏移不随缩放变", () => {
	const offset = pixelOffset({x: 0, y: -1}, DECAL_KINDS.signalSideOffset);
	assert.deepEqual(offset, {x: 0, y: -10}, "偏移 = 方向 × 10 px（屏幕像素，与世界无关）");

	const near = decalPlacement(100, 50, camera(1, 0, 0), offset);
	const far = decalPlacement(100, 50, camera(0.5, 0, 0), offset);
	// 缩放变小 ⇒ 锚点靠近原点，但偏移仍是 10 px
	assert.deepEqual(near, {x: 100, y: 40}, "scale=1 时锚点 (100,50) 偏移 (0,-10)");
	assert.deepEqual(far, {x: 50, y: 15}, "scale=0.5 时锚点变 (50,25)，偏移仍是 (0,-10)");

	const deltaNear = {x: near.x - 100, y: near.y - 50};
	const deltaFar = {x: far.x - 50, y: far.y - 25};
	assert.deepEqual(deltaNear, deltaFar, "两次的偏移完全相同 —— 这就是『相对锚点不滑走』");
});

test("位移与旋转写在同一个 transform 里", () => {
	assert.equal(decalTransform({x: 12, y: 34}), "translate(12px, 34px)", "不转时只有位移");
	assert.equal(decalTransform({x: 12, y: 34}, 90), "translate(12px, 34px) rotate(90deg)",
		"要转时必须与位移同一个 transform（分开写会互相覆盖）");
});

test("pixelOffset 只缩放距离、不改方向", () => {
	const direction = {x: 0.6, y: 0.8}; // 单位向量
	assert.deepEqual(pixelOffset(direction, 10), {x: 6, y: 8});
	assert.deepEqual(pixelOffset(direction, 0), {x: 0, y: 0}, "距离 0 = 不偏移");
	assert.deepEqual(pixelOffset(direction, 20), {x: 12, y: 16}, "距离翻倍 ⇒ 偏移翻倍，方向不变");
});

test("区间状态条的像素规格：两条 2 px 落在 6 px 线心里", () => {
	const {stripeWidth, stripeNear, stripeFar, sectionBaseWidth} = DECAL_KINDS;
	assert.equal(sectionBaseWidth, 6, "线心 6 px（用户规格）");
	assert.equal(stripeWidth, 2, "状态条 2 px（用户规格）");
	// 线心中心为 0，横跨 −3 … +3；两条 2px 条的中心在 ±2 与 ±5
	assert.equal(stripeNear, 2);
	assert.equal(stripeFar, 5);
	const near = [stripeNear - stripeWidth / 2, stripeNear + stripeWidth / 2];
	const far = [stripeFar - stripeWidth / 2, stripeFar + stripeWidth / 2];
	assert.deepEqual(near, [1, 3], "近心条覆盖 1–3 px");
	assert.deepEqual(far, [4, 6], "远心条覆盖 4–6 px");
	assert.equal(far[1] <= sectionBaseWidth, true, "远心条外缘正好是 6 px 线心的边缘（不溢出）");
	assert.equal(near[1] < far[0], true, "两条之间留 1 px 缝，不会糊成一条 4 px 宽带");
	// 这条是**反例**：1.5 / 4.5 会让外缘到 5.5 px 的外侧——超出线心半边 3 px
	assert.equal(4.5 + stripeWidth / 2 > sectionBaseWidth / 2, true,
		"偏移取 1.5/4.5 会溢出线心（所以规格是 2/5，不是 1.5/4.5）");
});
