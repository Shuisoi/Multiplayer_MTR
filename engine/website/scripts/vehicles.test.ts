/*
 * `views/map/railAlignment.ts` 与 `domain/Train.ts` 的用例：**对齐规则**、**按弧长取点**、
 * **车在轨上的比例**。
 *
 * 跑法：`npm run test:vehicles`（= `node --experimental-strip-types --test scripts/vehicles.test.ts`）。
 *
 * 为什么值得单独一条：这三件事都是"错了也画得出来"的那种 —— 对齐算错车就偏半格（用户抓过一次同类）、
 * 按点号插值会让车在长段上跑偏、比例不夹取会让车跑出轨。用构造数据钉死。
 */
import assert from "node:assert/strict";
import {test} from "node:test";
import {Rail, type RawRail} from "../src/domain/Rail.ts";
import {canPlaceTrain, parseTrains, trainRailFraction, describeTrain} from "../src/domain/Train.ts";
import {alignRail, pointAtLengthFraction} from "../src/views/map/railAlignment.ts";

/** 一根轨：端点 (0,0)-(10,0)，采样走方块中心（首点 (0.5,0.5)、末点 (9.5,0.5)）。 */
function centredRail(): Rail {
	return new Rail({
		hex: "H", x1: 0, y1: 0, z1: 0, x2: 10, y2: 0, z2: 0,
		path: [[0.5, 0, 0.5], [5.5, 0, 0.5], [9.5, 0, 0.5]],
	} as RawRail);
}

test("对齐：采样走方块中心 ⇒ 首末点被拉回端点，中间按 t 分摊（校正量是量出来的）", () => {
	const aligned = alignRail(centredRail());
	assert.deepEqual(aligned.points[0], [0, 0]);
	assert.deepEqual(aligned.points[2], [10, 0]);
	/*
	 * 中间点：t = 0.5 ⇒ 两端校正量 (−0.5,−0.5) 与 (+0.5,−0.5) 各一半 ⇒ x 的校正**抵消**、
	 * z 减 0.5。所以对齐不只是"整体平移"：它把采样没覆盖到的半格**拉伸**到两端点上，
	 * 于是画出来的轨正好从端点走到端点（轨长 10 格，而采样只跨 9 格）。
	 */
	assert.deepEqual(aligned.points[1], [5.5, 0]);
	assert.equal(aligned.fixX1, -0.5);
	assert.equal(aligned.fixZ1, -0.5);
	assert.equal(aligned.sampled, true);
});

test("没有采样形状 ⇒ 直接用两端点、校正量为 0（老引擎的退路）", () => {
	const aligned = alignRail(new Rail({hex: "H", x1: 0, y1: 0, z1: 0, x2: 10, y2: 0, z2: 0} as RawRail));
	assert.deepEqual(aligned.points, [[0, 0], [10, 0]]);
	assert.equal(aligned.sampled, false);
	assert.equal(aligned.fixX1, 0);
});

test("按弧长比例取点：一半在正中；两端夹取", () => {
	const points = [[0, 0], [4, 0], [10, 0]] as const;
	assert.deepEqual(pointAtLengthFraction(points, 0), [0, 0]);
	assert.deepEqual(pointAtLengthFraction(points, 1), [10, 0]);
	assert.deepEqual(pointAtLengthFraction(points, 0.5), [5, 0]);
	// 超出范围要夹住（不然车会跑出轨）
	assert.deepEqual(pointAtLengthFraction(points, -1), [0, 0]);
	assert.deepEqual(pointAtLengthFraction(points, 2), [10, 0]);
});

test("按**弧长**插值，而不是按「第几个点」（采样点疏密不均时两者不同）", () => {
	// 折线 (0,0)→(1,0)→(10,0)：弧长中点不在"第 2 个点"上，而在 (5,0)
	const points = [[0, 0], [1, 0], [10, 0]] as const;
	const mid = pointAtLengthFraction(points, 0.5);
	assert.ok(Math.abs(mid[0] - 5) < 1e-9, `中点应当在 (5,0)，实际 (${mid.join(",")})`);
});

test("车在轨上的比例 = 弧长 ÷ 轨长，并且夹在 0..1", () => {
	const [train] = parseTrains([{vehicleId: "v", railHex: "H", railArcM: 25, railArcLengthM: 100}]);
	assert.equal(trainRailFraction(train!), 0.25);
	const [over] = parseTrains([{vehicleId: "v", railHex: "H", railArcM: 250, railArcLengthM: 100}]);
	assert.equal(trainRailFraction(over!), 1);
	assert.equal(canPlaceTrain(train!), true);
	// 没有轨长就没法落点（引擎还没说清在哪根轨上）
	const [unknown] = parseTrains([{vehicleId: "v"}]);
	assert.equal(canPlaceTrain(unknown!), false);
});

test("解析：hex 规范化键跟着算好（引擎两处写法可能相反），方向缺省为「弧增」", () => {
	const [train] = parseTrains([{vehicleId: "v", railHex: "B-A", railArcM: 1, railArcLengthM: 2}]);
	assert.equal(train!.arcIncreasing, true);
	const [reverse] = parseTrains([{vehicleId: "v", railHex: "FFFFFFFFFFFFFFED-FFFFFFFFFFFFFFC4-0000000000000033-0000000000000001-FFFFFFFFFFFFFFC4-0000000000000040", railArcM: 1, railArcLengthM: 2}]);
	// 规范化后两端顺序固定：小的在前
	assert.ok(reverse!.railKey.startsWith("0000000000000001-"), `规范化键 = ${reverse!.railKey}`);
});

test("悬浮提示：把速度/在不在运行/线路/车门/下一区间都写进去", () => {
	const [train] = parseTrains([{
		vehicleId: "v", railHex: "H", railArcM: 1, railArcLengthM: 2, speedKmh: 42.5,
		onRoute: true, doorsOpen: true, routeNumber: "3", nextSection: "S9", depotName: "车场",
	}]);
	const text = describeTrain(train!);
	for (const piece of ["v", "42.5 km/h", "在运行", "线路 3", "车门开着", "下一区间 S9", "车场"]) {
		assert.ok(text.includes(piece), `提示里应当有「${piece}」：${text}`);
	}
});
