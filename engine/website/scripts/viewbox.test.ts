/**
 * 坐标系（`npm run test:viewbox`）：**viewBox 当相机**之后的两个纯函数。
 *
 * <p>这一版把"每个元素自己把世界坐标投影成屏幕坐标"换成了"一个 viewBox 承担相机"
 * （见 `notes/164-坐标系重构-viewBox当相机.md`）。换掉的东西必须由断言钉住，否则
 * "屏幕上的尺寸对不对"又会退回肉眼判断 —— 这条路上已经翻车过四次。</p>
 *
 * <p>三组判据：</p>
 * <ol>
 *   <li>{@link viewBoxOf} 与 {@link worldToScreen} 是**同一个映射**（一个是给 SVG 的声明式说法，
 *       一个是视图层算指针用的），两者不一致会让"画出来的"和"点得到的"错位；</li>
 *   <li>{@link specPxToWorld} 的口径：**全览（1×）时屏幕上正好是规格值**，其余按倍率放大（见 notes/165）
 *       （用户 2026-09-15 定的；见 commit `fa18932`）；</li>
 *   <li>换算与 SVG 的映射**自洽**：{@code specPxToWorld(规格, 取景基准) × 相机比例} 必须等于
 *       {@link screenPxOfSpec}（同一件事的两种写法，差一点就是"线宽与坐标脱节"）。</li>
 * </ol>
 */
import {test} from "node:test";
import assert from "node:assert/strict";
import {REFERENCE_ZOOM, specPxToWorld, screenPxOfSpec, DECAL_KINDS} from "../src/domain/mapElements.ts";
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
	// viewBox 覆盖的世界范围 × SVG 自己的比例 = 视口尺寸 ⇒ SVG 的比例就是相机比例
	assert.ok(Math.abs(host.width / vw - cam.scale) < 1e-9, `SVG 的比例 ${host.width / vw} 应当等于相机比例 ${cam.scale}`);
	assert.ok(Math.abs(host.height / vh - cam.scale) < 1e-9, "两个方向的比例必须一致（否则图会被拉伸）");
	// 抽三个世界点：viewBox 映射出来的屏幕位置 与 worldToScreen 必须逐点相同
	for (const [wx, wy] of [[0, 0], [-500, 300], [1200, -900]] as const) {
		const viaViewBox = {x: (wx - vx) * (host.width / vw), y: (wy - vy) * (host.height / vh)};
		const viaFunction = worldToScreen(cam, wx, wy);
		assert.ok(Math.abs(viaViewBox.x - viaFunction.x) < 1e-6, `x：viewBox ${viaViewBox.x} ≠ worldToScreen ${viaFunction.x}`);
		assert.ok(Math.abs(viaViewBox.y - viaFunction.y) < 1e-6, `y：viewBox ${viaViewBox.y} ≠ worldToScreen ${viaFunction.y}`);
	}
});

test("viewBox 退化输入不产生空串或 NaN", () => {
	// 容器还没量出尺寸：给 1×1 的退化解，而不是空 viewBox（空 viewBox 会让 SVG 退回 1:1 并跳变）
	const degenerate = viewBoxOf(camera(10, 20, 1), {width: 0, height: 0});
	assert.equal(degenerate.split(" ").every(part => Number.isFinite(Number(part))), true, `退化输入给出了非法 viewBox：${degenerate}`);
	// 比例非法（0 / 负）：按 1 处理，不能出现 0 宽或负宽
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

test("尺寸只有一处换算：世界单位 = 规格 × 一个常量，之后跟着相机缩放", () => {
	/*
	 * 用户 2026-09-15 的追问："每个元素都需要进行单独缩放吗？" —— 不需要。
	 * 这套系统里**只有一个尺寸常量**（unitsPerPx = 1 屏幕像素等于多少世界单位，取景时校准一次），
	 * 所有元素都用它换算一次；"跟着相机缩放"由相机承担，是**自动的**（见 notes/165）。
	 *
	 * 这条测试就是钉住这一点：
	 *   ① 同一个常量给出的世界尺寸是**常量**（与当前倍率、当前相机比例无关）；
	 *   ② 屏幕尺寸 = 规格 × 倍率（相机缩放的结果，不是又一处输入）。
	 */
	const unitsPerPx = 1 / 0.4668;                     // 取景校准出来的那个常量
	for (const zoom of [1, 3, 6, 12]) {
		const cameraScale = 0.4668 * zoom;             // 相机比例 = 基准 × 倍率
		for (const spec of [DECAL_KINDS.icon, DECAL_KINDS.railWidth, DECAL_KINDS.endpointDot * 2]) {
			// ① 常量 → 世界单位：与倍率无关（同一个规格永远得到同一个世界尺寸）
			const world = specPxToWorld(spec, unitsPerPx);
			assert.equal(world, spec * unitsPerPx, `规格 ${spec} 的世界尺寸应当只由常量决定`);
			// ② 再乘当前相机比例 = 屏幕上应当看到的像素 = 规格 × 倍率
			const onScreen = world * cameraScale;
			const expected = screenPxOfSpec(spec, zoom);
			assert.ok(Math.abs(onScreen - expected) < 1e-9,
				`规格 ${spec} @ ${zoom}×：世界 ${world} × ${cameraScale} = ${onScreen}，期望 ${expected}`);
			// 全览（1×）时屏幕尺寸正好是规格值 —— 这就是"基准是全览"
			if (zoom === 1) {
				assert.ok(Math.abs(onScreen - spec) < 1e-9, `全览时 ${spec} 应当是屏幕上正好 ${spec} px`);
			}
		}
	}
});

test("规格口径：**全览时屏幕上正好是规格值**，其余跟着相机一起缩放", () => {
	/*
	 * 用户 2026-09-15 最后定调："整幅地图一起缩放，但把基准改成全览尺寸"。
	 * 换算里没有倍率、也没有 6：世界单位 = 规格 ÷ 相机比例，屏幕上恒为规格值。
	 */
	assert.equal(screenPxOfSpec(DECAL_KINDS.railWidth, 1), DECAL_KINDS.railWidth, "全览时轨道线 = 规格 4 px");
	assert.equal(screenPxOfSpec(DECAL_KINDS.icon, 1), DECAL_KINDS.icon, "全览时图标 = 规格 8 px");
	// 推近 ⇒ 按倍率变大（这就是"地图在缩放"）
	assert.equal(screenPxOfSpec(DECAL_KINDS.icon, 6), DECAL_KINDS.icon * 6, "6× 时图标 = 规格 × 6");
	assert.ok(screenPxOfSpec(DECAL_KINDS.icon, 12) > screenPxOfSpec(DECAL_KINDS.icon, 3), "倍率越大屏幕尺寸越大");
});
test("换算与 SVG 的映射自洽：specPxToWorld × 相机比例 = 屏幕像素", () => {
	/*
	 * 左边是"写进 SVG 的世界单位"，右边是"应该看到的屏幕像素"。
	 * 差一点就说明线宽与坐标用的是两套尺度 —— 那正是"线与图标脱层"那类缺陷的成因。
	 */
	const baseScale = 0.4668;
	for (const zoom of [1, 3, REFERENCE_ZOOM, 12]) {
		{
			const cameraScale = baseScale * zoom;
			for (const spec of [DECAL_KINDS.railWidth, DECAL_KINDS.sectionBaseWidth, DECAL_KINDS.endpointDot]) {
				const world = specPxToWorld(spec, 1 / baseScale);
				const onScreen = world * cameraScale;
				const expected = screenPxOfSpec(spec, zoom);
				assert.ok(Math.abs(onScreen - expected) < 1e-9,
					`规格 ${spec} @ ${zoom}×：世界 ${world} × ${cameraScale} = ${onScreen}，期望 ${expected}`);
			}
		}
	}
});

test("换算的退化输入不产生 NaN 或负值", () => {
	for (const spec of [0, 4]) {
		for (const zoom of [0, -1]) {
			for (const scale of [0, -2]) {
				const value = specPxToWorld(spec, zoom, scale);
				assert.ok(Number.isFinite(value) && value >= 0, `spec=${spec} scale=${scale} 得到 ${value}`);
			}
		}
	}
});
