/**
 * 地图元素尺度模型的单测（`npm run test:elements`）。
 *
 * <h2>三组口径（用户 2026-09-15 逐条定下来的）</h2>
 * <ol>
 *   <li><b>有实际坐标的东西按格</b>（1 格 = 1 世界单位）：轨道线 1 格、节点圆点 1 格 —— "我的世界的坐标
 *       永远都是 1x1x1，方块也是，就不能按 1x1x1 进行放置吗？"；</li>
 *   <li><b>绑在节点上的东西用固定屏幕像素</b>：灯 8 px、道岔菱形 8 px —— "信号灯，道岔是绑定在节点上的，
 *       直接固定显示在节点旁不行吗？不要掺活实际坐标进来"；</li>
 *   <li><b>尺寸只有两处换算</b>：{@link specToWorld}（格 → 世界单位）、{@link pxToWorld}
 *       （屏幕像素 → 世界单位）。</li>
 * </ol>
 */
import {test} from "node:test";
import assert from "node:assert/strict";
import {
	BLOCK,
	DECAL_KINDS,
	WORLD_UNIT,
	blockGridLines,
	decalPlacement,
	decalTransform,
	pixelOffset,
	pxToWorld,
	screenPxOfSpec,
	specToWorld,
} from "../src/domain/mapElements.ts";

test("① 有实际坐标的东西按格：1 格 = 1 世界单位", () => {
	assert.equal(WORLD_UNIT, 1, "一个世界单位就是一格（Minecraft 方块）");
	assert.equal(specToWorld(1), 1, "规格 1 格 = 1 世界单位");
	assert.equal(specToWorld(DECAL_KINDS.railWidth), DECAL_KINDS.railWidth, "轨道线宽 = 规格格数");
	// 轨宽、节点圆点都应当在"格"的量级上（不是几十格，也不是百分之一格）
	for (const [name, spec] of [["轨道线", DECAL_KINDS.railWidth], ["节点圆点", DECAL_KINDS.nodeDot]] as const) {
		assert.ok(spec >= 0.5 && spec <= 3, `${name} 的规格 ${spec} 格应当在 1 格量级（方块尺度）`);
	}
	assert.equal(specToWorld(DECAL_KINDS.railWidth), specToWorld(DECAL_KINDS.railWidth), "世界尺寸是常量");
});

test("① 方块网格：1×1 格，格线落在整数格上", () => {
	assert.equal(BLOCK.size, 1, "一格就是 1 世界单位");
	assert.equal(BLOCK.lineWidth > 0 && BLOCK.lineWidth < 0.1, true, "格线要细（0.02 格）");
	assert.equal(blockGridLines(1), "M 1 0 L 1 1 L 0 1", "一格 tile 画右下两条边");
	assert.equal(Number.isInteger(BLOCK.origin.x) && Number.isInteger(BLOCK.origin.y), true,
		"格线相位应当是整数格，否则网格与方块错位半格");
});

test("② 绑在节点上的东西：规格是屏幕像素，折成世界单位后屏幕大小恒定", () => {
	for (const viewScale of [0.4667, 1.2, 2.872]) {
		for (const [name, spec] of [["图标", DECAL_KINDS.icon], ["菱形", DECAL_KINDS.turnoutDiamond]] as const) {
			const world = pxToWorld(spec, viewScale);
			assert.ok(Math.abs(world * viewScale - spec) < 1e-9,
				`${name}：相机比例 ${viewScale} 时屏幕上应当是 ${spec} px（实得 ${world * viewScale}）`);
		}
	}
	assert.ok(pxToWorld(DECAL_KINDS.icon, 2.872) < pxToWorld(DECAL_KINDS.icon, 0.4667),
		"推近 ⇒ 同样的屏幕像素对应更小的世界尺寸");
});

test("③ 屏幕尺寸 = 世界尺寸 × 相机比例（相机的事，不是规格的事）", () => {
	const rail = specToWorld(DECAL_KINDS.railWidth);
	for (const zoom of [1, 3, 6, 12]) {
		assert.ok(Math.abs(screenPxOfSpec(rail, zoom) - rail * zoom) < 1e-9, `${zoom}× 的屏幕尺寸`);
	}
	assert.ok(screenPxOfSpec(rail, 12) > screenPxOfSpec(rail, 1), "推近 ⇒ 屏幕上更大");
});

test("锚点 = 世界坐标 + 偏移（相机由外层承担）", () => {
	const offset = pixelOffset({x: 0, y: -1}, DECAL_KINDS.turnoutOffset);
	const near = decalPlacement(100, 50, offset);
	assert.deepEqual(near, {x: 100, y: 50 - DECAL_KINDS.turnoutOffset}, "世界坐标 + 偏移 —— 不乘相机");
	assert.deepEqual(decalPlacement(100, 50), {x: 100, y: 50}, "没有偏移时锚点 = 世界坐标");
	assert.equal(DECAL_KINDS.signalSideOffset, 0, "灯直接显示在节点上（用户口径：不要掺实际坐标）");
});

test("位移与旋转写在同一个 transform 里", () => {
	assert.equal(decalTransform({x: 12, y: 34}), "translate(12px, 34px)", "不转时只有位移");
	assert.equal(decalTransform({x: 12, y: 34}, 90), "translate(12px, 34px) rotate(90deg)",
		"要转时必须与位移同一个 transform（分开写会互相覆盖）");
});

test("区间状态条：比例与用户那三个数同源", () => {
	const {stripeWidth, stripeNear, stripeFar, sectionBaseWidth} = DECAL_KINDS;
	// 用户规格："一根线，1-2、4-5 用来表示区间" ⇒ 条中心在线心的 1/4 与 3/4 处
	assert.equal(stripeNear, sectionBaseWidth / 4, "内侧条中心在线心的 1/4 处");
	assert.equal(stripeFar, sectionBaseWidth * 3 / 4, "外侧条中心在线心的 3/4 处");
	assert.equal(stripeWidth, sectionBaseWidth / 3, "条宽是线心的 1/3");
	const near = [stripeNear - stripeWidth / 2, stripeNear + stripeWidth / 2];
	const far = [stripeFar - stripeWidth / 2, stripeFar + stripeWidth / 2];
	assert.equal(far[1] <= sectionBaseWidth, true, "外侧条的最外缘仍落在线心内");
	assert.equal(near[1] < far[0], true, "两条之间留缝，不会糊成一条宽带");
});
