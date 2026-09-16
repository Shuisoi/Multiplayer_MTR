/*
 * `domain/Platform.ts` 的用例：**站台轴 → 文字旋转角**、**外侧方向**、**范围与中点**。
 *
 * 跑法：`npm run test:platforms`（= `node --experimental-strip-types --test scripts/platforms.test.ts`）。
 *
 * 为什么值得单独一条：这三件事都是"错了也画得出来"的那种 —— 旋转角差 180° 站名就倒着写、
 * 外侧方向挑反了 I 就压在轨上、中点算错文字就跑到站台外面去。用构造数据把它们钉死。
 */
import assert from "node:assert/strict";
import {test} from "node:test";
import {
	extentAxis,
	extentMidpoint,
	parsePlatforms,
	platformExtent,
	platformSideDirection,
	uprightRotation,
} from "../src/domain/Platform.ts";

test("文字旋转角：轴指向右/下/左/上 ⇒ 0 / 90 / 0 / −90（永远是「能正着读」的那一半）", () => {
	assert.equal(uprightRotation(1, 0), 0);
	assert.equal(uprightRotation(0, 1), 90);
	// 左（−1, 0）：raw = 180 ⇒ 减 180 ⇒ 0（文字仍从左往右写）
	assert.equal(uprightRotation(-1, 0), 0);
	// 上（0, −1）：raw = −90 ⇒ +180 ⇒ 90
	assert.equal(uprightRotation(0, -1), 90);
});

test("文字旋转角恒落在 (−90, 90]：任意方向扫一圈都不倒着写", () => {
	for (let degrees = -180; degrees <= 180; degrees += 5) {
		const radians = degrees * Math.PI / 180;
		const rotation = uprightRotation(Math.cos(radians), Math.sin(radians));
		assert.ok(rotation > -90.0001 && rotation <= 90.0001, `${degrees}° → ${rotation}°`);
	}
});

test("外侧方向：没有别的站台时用轴的顺时针法向", () => {
	// 轴朝东 (1,0) ⇒ 法向 (0,1)（屏幕里向下 = 世界 +z）
	assert.deepEqual(platformSideDirection([1, 0], null), [0, 1]);
});

test("外侧方向：背离本站其他站台（两侧各站一个 ⇒ 天然画在两根轨的外面）", () => {
	// 轴朝东，另一个站台在本站台**上方**（−z 方向）⇒ 外侧应当朝下 (+z)
	assert.deepEqual(platformSideDirection([1, 0], [0, -10]), [0, 1]);
	// 另一个站台在下方 ⇒ 外侧朝上
	assert.deepEqual(platformSideDirection([1, 0], [0, 10]), [0, -1]);
	// 其他站台方向为 0（重合）⇒ 退回固定的一侧
	assert.deepEqual(platformSideDirection([1, 0], [0, 0]), [0, 1]);
});

test("范围：有轨的采样点就用轨（贴着一页画出来的线），没有就退两端", () => {
	const platform = parsePlatforms([{x1: 0, z1: 0, x2: 10, z2: 0}])[0]!;
	const railPath = [[0.5, 0.5], [5.5, 0.5], [10.5, 0.5]] as const;
	assert.equal(platformExtent(platform, railPath).length, 3);
	assert.deepEqual(platformExtent(platform, null), [[0, 0], [10, 0]]);
});

test("轴与中点：按弦方向、按弧长取中点", () => {
	assert.deepEqual(extentAxis([[0, 0], [10, 0]]), [1, 0]);
	assert.deepEqual(extentAxis([[0, 0], [0, -3]]), [0, -1]);
	// 折线 (0,0)→(2,0)→(2,10)：总长 12，中点在 6 处 ⇒ (2, 4)
	assert.deepEqual(extentMidpoint([[0, 0], [2, 0], [2, 10]]), [2, 4]);
});

test("解析：缺字段不炸，方向缺省成 (0,1)、名字原样带出来", () => {
	const [platform] = parsePlatforms([{platformName: "2", stationName: "Central||C"}]);
	assert.equal(platform!.platformName, "2");
	assert.equal(platform!.stationName, "Central||C");
	assert.deepEqual(platform!.axis, [0, 1]);
	assert.equal(platform!.railHex, "");
});
