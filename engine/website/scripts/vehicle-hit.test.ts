/*
 * **点得动列车记号**的用例：`views/map/vehicleHit.ts`（旋转矩形的命中判定）。
 *
 * 跑法：`npm run test:vehicle-hit`
 * （= `node --experimental-strip-types --test scripts/vehicle-hit.test.ts`）。
 *
 * 为什么值得单独一条：这一层全是"错了也点得动"的东西 —— 旋转方向反了、余量加错边、
 * 重叠时选了远的那一节，肉眼都看不出来（只有点上去才发现"点的是旁边那辆车"）。
 * 而它又是**真鼠标事件**唯一进得来的地方（图层自己不做元素监听，见该文件头部）。
 */
import assert from "node:assert/strict";
import {test} from "node:test";
import {hitMarker, hitsMarker, toMarkerLocal, type HitMarker} from "../src/views/map/vehicleHit.ts";

/** 一节 16 米的车（画布单位：长 32 = 16 格、宽 2.4 = 1.2 格），中心在 (100, 50)。 */
function marker(overrides: Partial<HitMarker<string>> = {}): HitMarker<string> {
	return {
		item: "car",
		x: 100,
		y: 50,
		rotation: 0,
		lengthUnits: 32,
		widthUnits: 2.4,
		...overrides,
	};
}

test("局部坐标：把点换算进记号的坐标系（平移 + 反向旋转）", () => {
	assert.deepEqual(toMarkerLocal(marker(), 110, 51), [10, 1]);
	// 记号转了 90°（车头朝 +y）⇒ 世界 +y 方向上的点在局部里是 +x
	const turned = toMarkerLocal(marker({rotation: 90}), 100, 60);
	assert.ok(Math.abs(turned[0] - 10) < 1e-9, `应当落在 +x 上，实际 ${turned.join(",")}`);
	assert.ok(Math.abs(turned[1]) < 1e-9, `另一个分量应当为 0，实际 ${turned.join(",")}`);
	// 180°：世界 −x 方向上的点落在局部 +x 上
	const reversed = toMarkerLocal(marker({rotation: 180}), 90, 50);
	assert.ok(Math.abs(reversed[0] - 10) < 1e-9 && Math.abs(reversed[1]) < 1e-9, `实际 ${reversed.join(",")}`);
});

test("命中：**整条车身**都点得动（不是只有车心那一小块）", () => {
	const car = marker();
	// 车头、车尾、车心、车身侧面（半宽之内）都算命中
	assert.equal(hitsMarker(car, 116, 50, 0), true, "车头");
	assert.equal(hitsMarker(car, 84, 50, 0), true, "车尾");
	assert.equal(hitsMarker(car, 100, 51, 0), true, "车心偏一点（半宽之内）");
	// 车身之外：长度方向超出、宽度方向超出，都不算
	assert.equal(hitsMarker(car, 120, 50, 0), false, "车头外 4 单位");
	assert.equal(hitsMarker(car, 100, 53, 0), false, "车侧外 1.8 单位");
});

test("余量：按**画布单位**外扩（调用方按屏幕像素 ÷ 相机比例算好）", () => {
	const car = marker();
	// 宽 1.2 格的车在默认取景下约 1 px：没有余量就等于点不到（用户手一抖就偏一格）
	assert.equal(hitsMarker(car, 100, 52, 1), true, "余量 1 单位 ⇒ 车侧 2 单位处仍算命中（半宽 1.2 + 1）");
	assert.equal(hitsMarker(car, 100, 53.5, 1), false, "3.5 单位已经超出余量");
	// 余量在长度方向同样生效
	assert.equal(hitsMarker(car, 118, 50, 3), true, "车头外 2 单位（半长 16 + 3）");
	assert.equal(hitsMarker(car, 121, 50, 3), false, "车头外 5 单位");
});

test("命中：转动过的记号按它**自己的方向**判（不是按轴对齐的框）", () => {
	// 车头朝 +y：沿 +y 走 14 单位仍算命中，沿 +x 走 14 单位不算（那已经出了车身）
	const turned = marker({rotation: 90});
	assert.equal(hitsMarker(turned, 100, 64, 0), true, "沿车长方向");
	assert.equal(hitsMarker(turned, 114, 50, 0), false, "垂直于车长方向 14 单位 = 车身之外");
	assert.equal(hitsMarker(turned, 101.1, 64, 0), true, "车宽之内");
	assert.equal(hitsMarker(turned, 102, 64, 0), false, "车宽之外");
});

test("重叠：取**中心离指针最近**的那一节（编组里相邻两节挨着，点哪节点哪节）", () => {
	const cars: readonly HitMarker<string>[] = [
		{...marker(), item: "front", x: 80, y: 50},
		{...marker(), item: "rear", x: 112, y: 50},
	];
	// 两个记号的矩形都能盖住 (110,50)（每个半长 16、中心相距 32 ⇒ 正好首尾相接）
	assert.equal(hitMarker(cars, 110, 50, 1), "rear", "靠近后一节");
	assert.equal(hitMarker(cars, 82, 50, 1), "front", "靠近前一节");
	// 谁都不沾就返回 null（探针与图层都靠这个"点空白 = 取消选中"）
	assert.equal(hitMarker(cars, 100, 80, 1), null);
	assert.equal(hitMarker([], 100, 50, 12), null);
});
