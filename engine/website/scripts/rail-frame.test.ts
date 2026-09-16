/*
 * `domain/Rail.ts` 的 `railSampleShift()` 用例：**采样坐标系相对节点坐标系的系统偏移**。
 *
 * 跑法：`npm run test:rail-frame`（= `node --experimental-strip-types --test scripts/rail-frame.test.ts`）。
 *
 * 为什么值得单独一条：它是"把灯锚到这一页的点上"唯一的依据。写死 0.5 格会在引擎换约定时**反向偏**
 * （与「地图」页那条"不要把校正量写成减 0.5 格"同一个教训），所以只认**量出来的众数**；
 * 这里把四种边界钉住：众数怎么取、没有 path 的轨怎么办、一条都算不出来时怎么办、以及
 * "偏移按 0.01 格取整"这条（浮点噪声不该把同一套写法拆成两个众数）。
 */
import assert from "node:assert/strict";
import {test} from "node:test";
import {Rail, railSampleShift, type RawRail} from "../src/domain/Rail.ts";

/** 一根轨：端点 + 可选的采样路径。 */
function rail(x1: number, z1: number, x2: number, z2: number, path?: readonly (readonly number[])[]): Rail {
	return new Rail({hex: `${x1},${z1}-${x2},${z2}`, x1, y1: 0, z1, x2, y2: 0, z2, path} as RawRail);
}

/** 一根"采样走方块中心"的轨：首点比端点1 多 `d`。 */
function shifted(dx: number, dz: number): Rail {
	return rail(0, 0, 10, 0, [[0 + dx, 0, 0 + dz], [5 + dx, 0, 0 + dz], [10 + dx, 0, 0 + dz]]);
}

test("采样走方块中心 ⇒ 偏移 = (0.5, 0.5)", () => {
	assert.deepEqual(railSampleShift([shifted(0.5, 0.5)]), [0.5, 0.5]);
});

test("众数：3 根一致的 + 1 根异常 ⇒ 取一致的那个（异常那根不把整页带偏）", () => {
	const rails = [shifted(0.5, 0.5), shifted(0.5, 0.5), shifted(0.5, 0.5), shifted(-0.5, 0.5)];
	assert.deepEqual(railSampleShift(rails), [0.5, 0.5]);
});

test("没有 path 的轨不参与（老引擎不带形状 ⇒ 不能拿它当依据）", () => {
	const rails = [rail(0, 0, 10, 0), rail(0, 0, 10, 0), shifted(0.5, 0.5)];
	assert.deepEqual(railSampleShift(rails), [0.5, 0.5]);
});

test("一条都算不出来 ⇒ (0, 0)：两套写法一致，不做任何搬移", () => {
	assert.deepEqual(railSampleShift([]), [0, 0]);
	assert.deepEqual(railSampleShift([rail(0, 0, 10, 0)]), [0, 0]);
});

test("偏移按 0.01 格取整：浮点噪声不把同一套写法拆成两个众数", () => {
	const noisy = [shifted(0.5, 0.5), shifted(0.5 + 1e-9, 0.5 - 1e-9), shifted(0.500001, 0.499999)];
	assert.deepEqual(railSampleShift(noisy), [0.5, 0.5]);
});

test("与「地图」页的校正量同源：端点 − 首采样点 = 负的偏移", () => {
	const one = shifted(0.5, 0.5);
	const [dx, dz] = railSampleShift([one]);
	assert.deepEqual([one.planeX1 - one.path[0]!.x, one.planeY1 - one.path[0]!.z], [-dx, -dz]);
});
