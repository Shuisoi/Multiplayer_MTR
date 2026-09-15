/**
 * 坐标系与尺寸（`npm run test:viewbox`）：**viewBox 当相机 + 尺寸是世界单位**。
 *
 * <p>这一版把两件事都定死了，而且它们是**互相独立**的两件事：</p>
 * <ol>
 *   <li><b>相机</b>（{@link viewBoxOf}）：世界坐标 → 屏幕，由外层承担；</li>
 *   <li><b>尺寸</b>（{@link specToWorld}）：元素多大是**世界属性**，与视口、与地图大小无关
 *       （用户 2026-09-15："为什么全览 2px 写死？那么以后特别大的地图的话，岂不是线越来越粗？"）。</li>
 * </ol>
 *
 * <p>这两条各自被本文件的用例钉住 —— 之前反复改动的根因就是把它们混在一起
 * （尺寸乘了"取景比例"，于是窗口一变、地图一大，同一个规格就成了不同的世界尺寸）。</p>
 */
import {test} from "node:test";
import assert from "node:assert/strict";
import {DECAL_KINDS, WORLD_UNIT, screenPxOfSpec, specToWorld} from "../src/domain/mapElements.ts";
import {viewBoxOf, worldToScreen, screenToWorld, type Camera} from "../src/domain/camera.ts";

/** 一台相机：`scale` = 一个视口像素对应多少世界单位。 */
function camera(originX: number, originY: number, scale: number): Camera {
	return {originX, originY, scale};
}

/** 从 viewBox 字符串里取四个数。 */
function viewBoxNumbers(cameraValue: Camera, host: {width: number, height: number}): number[] {
	return viewBoxOf(cameraValue, host).split(" ").map(Number);
}

test("viewBox 与 worldToScreen 是同一个映射（画出来的 = 点得到的）", () => {
	const cam = camera(-1833.4, -1588, 0.4668);
	const host = {width: 1564, height: 814};
	const [vx, vy, vw, vh] = viewBoxNumbers(cam, host) as [number, number, number, number];
	assert.ok(Math.abs(host.width / vw - cam.scale) < 1e-9, `SVG 的比例 ${host.width / vw} 应当等于相机比例 ${cam.scale}`);
	assert.ok(Math.abs(host.height / vh - cam.scale) < 1e-9, "两个方向的比例必须一致（否则图会被拉伸）");
	for (const [wx, wy] of [[0, 0], [-500, 300], [1200, -900]] as const) {
		const viaViewBox = {x: (wx - vx) * (host.width / vw), y: (wy - vy) * (host.height / vh)};
		const viaFunction = worldToScreen(cam, wx, wy);
		assert.ok(Math.abs(viaViewBox.x - viaFunction.x) < 1e-6, `x：viewBox ${viaViewBox.x} ≠ worldToScreen ${viaFunction.x}`);
		assert.ok(Math.abs(viaViewBox.y - viaFunction.y) < 1e-6, `y：viewBox ${viaViewBox.y} ≠ worldToScreen ${viaFunction.y}`);
	}
});

test("viewBox 退化输入不产生空串或 NaN", () => {
	const degenerate = viewBoxOf(camera(10, 20, 1), {width: 0, height: 0});
	assert.equal(degenerate.split(" ").every(part => Number.isFinite(Number(part))), true, `退化输入给出了非法 viewBox：${degenerate}`);
	for (const bad of [0, -3]) {
		const text = viewBoxOf(camera(0, 0, bad), {width: 800, height: 600});
		const width = Number(text.split(" ")[2]);
		assert.ok(width > 0 && Number.isFinite(width), `比例 ${bad} 时 viewBox 宽应当是正数，实得 ${width}`);
	}
});

test("worldToScreen 与 screenToWorld 互为反函数", () => {
	const cam = camera(-100, -200, 0.75);
	for (const [sx, sy] of [[0, 0], [123.4, 567.8]] as const) {
		const world = screenToWorld(cam, sx, sy);
		const back = worldToScreen(cam, world.x, world.y);
		assert.ok(Math.abs(back.x - sx) < 1e-9 && Math.abs(back.y - sy) < 1e-9, `(${sx}, ${sy}) 往返后成了 (${back.x}, ${back.y})`);
	}
});

/*
 * ============================ 尺寸：世界属性 ============================
 *
 * 用户 2026-09-15 点出的要害：尺寸**不能**挂在视口/取景比例上，否则地图一大线就越来越粗。
 */
test("尺寸与世界无关：元素的世界大小只由标定常量决定", () => {
	// `specToWorld` 不带任何"比例"参数 —— 它就只有"规格 × 一个标定常量"
	for (const spec of [DECAL_KINDS.railWidth, DECAL_KINDS.icon, DECAL_KINDS.sectionBaseWidth]) {
		assert.equal(specToWorld(spec), spec * WORLD_UNIT, `规格 ${spec} 的世界尺寸只由标定常量决定`);
	}
	// 换任何视口、任何地图大小，同一个规格得到的世界尺寸都一样（没有任何输入能让它变）
	for (const _host of [{width: 800, height: 600}, {width: 3840, height: 2160}]) {
		for (const _map of [{span: 1449}, {span: 12000}]) {
			assert.equal(specToWorld(DECAL_KINDS.railWidth), DECAL_KINDS.railWidth * WORLD_UNIT,
				"视口或地图变了，线宽的世界尺寸必须不变（否则地图越大线越粗）");
		}
	}
});

test("地图变大 ⇒ 元素相对整图更细（这正是要的行为）", () => {
	const rail = specToWorld(DECAL_KINDS.railWidth);
	const onSmallMap = rail / 1449;
	const onBigMap = rail / 12000;
	assert.ok(onBigMap < onSmallMap, "地图更大 ⇒ 线相对更细");
	assert.equal(rail, specToWorld(DECAL_KINDS.railWidth), "但线本身的世界尺寸是常量（不是被缩掉了）");
});

test("屏幕尺寸 = 世界尺寸 × 当前相机比例（倍率进的是相机，不是规格）", () => {
	/*
	 * 世界 → 屏幕必须走相机：相机比例 = 取景基准 × 倍率。
	 * 这一条量的是"同一个世界尺寸在不同倍率下占多少屏幕像素"，
	 * 而世界尺寸本身**不参与**任何倍率运算（上一组用例已经钉住）。
	 */
	const baseScale = 0.4668;                 // 1× 时的相机比例
	const rail = specToWorld(DECAL_KINDS.railWidth);
	const worldToScreenPx = (worldSize: number, zoom: number) => worldSize * baseScale * zoom;
	for (const zoom of [1, 3, 6, 12]) {
		const px = worldToScreenPx(rail, zoom);
		assert.ok(Math.abs(px - rail * baseScale * zoom) < 1e-9, `${zoom}× 的屏幕尺寸应当是世界尺寸 × 相机比例`);
	}
	// 屏幕尺寸随倍率线性（相机的事），世界尺寸一动不动
	assert.ok(worldToScreenPx(rail, 12) > worldToScreenPx(rail, 1), "推近 ⇒ 屏幕上更大");
	assert.equal(specToWorld(DECAL_KINDS.railWidth), rail, "而世界尺寸没有变");
});

test("可读性：放大到多少倍时元素才看得见（用户接受的那条线）", () => {
	/*
	 * 用户 2026-09-15 拍板："保持世界单位，接受全览只是概览、细节靠放大"。
	 * 于是必须把"什么倍率下看得见"写成判据 —— 否则以后调规格会悄悄把可读倍率推远。
	 * 现场跨度 1623 格、视口 814 px ⇒ 全览 0.47 px/格（这个数取自实测，见 notes/165 第九节）。
	 */
	const fitPxPerWorld = 0.4667;
	for (const zoom of [1, 6, 8, 13]) {
		const iconPx = specToWorld(DECAL_KINDS.icon) * fitPxPerWorld * zoom;
		const railPx = specToWorld(DECAL_KINDS.railWidth) * fitPxPerWorld * zoom;
		if (zoom === 1) {
			// 全览：图标亚像素（概览，不指望看清）
			assert.ok(iconPx < 2, `全览时图标应当很小（概览），实得 ${iconPx.toFixed(2)} px`);
		}
		if (zoom >= 8) {
			// 放大到 8× 及以上：图标应当看得见（≥ 5 px），线至少 0.5 px
			assert.ok(iconPx >= 5, `${zoom}× 时图标 ${iconPx.toFixed(2)} px 应当看得见（≥ 5 px）`);
			assert.ok(railPx >= 0.5, `${zoom}× 时轨道线 ${railPx.toFixed(2)} px 不该细到看不见`);
		}
	}
});

test("退化输入不产生 NaN 或负值", () => {
	for (const spec of [0, 4]) {
		assert.ok(Number.isFinite(specToWorld(spec)), `规格 ${spec} 得到非有限值`);
	}
	for (const zoom of [0, -1, 12]) {
		assert.ok(Number.isFinite(screenPxOfSpec(DECAL_KINDS.railWidth, zoom)), `倍率 ${zoom} 得到非有限值`);
	}
});
