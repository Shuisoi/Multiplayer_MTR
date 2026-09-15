/**
 * 方向带几何的单测（`npm run test:plan` 那条链，node --test）。
 *
 * <p>为什么值得单独测：方案 B 的整个"两个方向各占一条带"就靠这两个纯函数，而它们在组件里渲染不了、
 * 也断言不了。这里钉住两条最小的性质：**相对的方向必须落在相反的侧**（否则两条带会叠在一起），
 * 以及**偏移必须是法向的**（否则带子会斜着离轨、看起来像另一条线路）。</p>
 *
 * 用法：npm run test:plan
 */
import {test} from "node:test";
import assert from "node:assert/strict";
import {offsetPath, sideOfDirection} from "../src/domain/sectionBands.ts";

test("相对的方向落在相反的侧", () => {
	// 南(0) 与 北(180) 相反 → 必须不同侧
	assert.notEqual(sideOfDirection(0), sideOfDirection(180), "南行与北行不能在同一条带上");
	// 东(270) 与 西(90) 相反 → 必须不同侧
	assert.notEqual(sideOfDirection(270), sideOfDirection(90), "东行与西行不能在同一条带上");
});

test("同一方向（含浮点误差与非正方向）落在同一侧", () => {
	assert.equal(sideOfDirection(0), sideOfDirection(0.0001), "浮点误差不该翻侧");
	assert.equal(sideOfDirection(179.9), sideOfDirection(180), "179.9° 仍是北行");
	assert.equal(sideOfDirection(359), sideOfDirection(0), "359° 归到南行");
	// 负数与超过一圈的角也要能归位（引擎理论上只给 [0,360)，但这里不该炸）
	assert.equal(sideOfDirection(-90), sideOfDirection(270), "-90° 等于 270°");
	assert.equal(sideOfDirection(450), sideOfDirection(90), "450° 等于 90°");
});

test("偏移是法向的：水平线的带是垂直线偏移", () => {
	// 从左到右的水平折线；法向是竖直方向，所以 y 应该整体平移 offset，x 不动
	const path = offsetPath([{x: 0, y: 0}, {x: 10, y: 0}, {x: 20, y: 0}], 3);
	const numbers = [...path.matchAll(/-?\d+(?:\.\d+)?/g)].map(match => Number(match[0]));
	// M x y L x y L x y
	assert.equal(numbers.length, 6, "三个点各一对坐标：" + path);
	assert.deepEqual(numbers.filter((_, index) => index % 2 === 0), [0, 10, 20], "水平线的 x 不该被偏移：" + path);
	for (const y of numbers.filter((_, index) => index % 2 === 1)) {
		assert.equal(y, 3, "每个点的 y 都偏移了 3：" + path);
	}
});

test("偏移量与方向成比例，且正负分居两侧", () => {
	const line = [{x: 0, y: 0}, {x: 10, y: 0}];
	const positive = offsetPath(line, 5);
	const negative = offsetPath(line, -5);
	const yOf = (path: string) => Number([...path.matchAll(/-?\d+(?:\.\d+)?/g)][1]![0]);
	assert.equal(yOf(positive), 5);
	assert.equal(yOf(negative), -5);
	assert.notEqual(positive, negative, "两侧必须是两条不同的线");
});

test("退化输入不产生垃圾路径", () => {
	assert.equal(offsetPath([], 3), "", "空折线不画");
	assert.equal(offsetPath([{x: 1, y: 2}], 3), "", "单点不构成线");
	// 重复点（长度为零的段）不能让法向计算除零
	const duplicated = offsetPath([{x: 5, y: 5}, {x: 5, y: 5}, {x: 5, y: 15}], 2);
	assert.ok(duplicated.startsWith("M 5 5"), "重复点处退回原点而不是 NaN：" + duplicated);
	assert.ok(!duplicated.includes("NaN"), "不许出现 NaN：" + duplicated);
});
