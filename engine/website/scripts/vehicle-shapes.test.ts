/*
 * `views/map/vehicleMarkerShapes.ts` 的用例：**三种记号形状**与**判定规则**
 * （用户 2026-09-16："列车箭头以圆角箭头画，无动力车厢用矩形，货车用中空四边形"；
 * 追加裁定："只有无动力挂车中空、机车画箭头"）。
 *
 * 跑法：`npm run test:vehicle-shapes`
 * （= `node --experimental-strip-types --test scripts/vehicle-shapes.test.ts`）。
 *
 * 为什么值得单独一条：世界数据里现在**没有无动力车厢、也没有货运任务**，所以矩形与中空四边形
 * 在活页面上根本画不出来 —— 只能靠这里把形状与判定钉死；等用户造出无动力/货运编组时，
 * 页面上画出来的就该是这几条路径。
 */
import assert from "node:assert/strict";
import {test} from "node:test";
import {parseTrains, describeCar, type Train, type TrainCar} from "../src/domain/Train.ts";
import {
	carMarkerKind,
	markerFilled,
	markerKindLabel,
	markerPath,
	type CarMarkerKind,
} from "../src/views/map/vehicleMarkerShapes.ts";

/** 记号宽度取活页面上的那个值（1.2 格 = 2.4 画布单位），车长 16 米 ⇒ 32 画布单位（1 格 = 1 米 = 2 单位）。 */
const WIDTH = 2.4;
const LENGTH = 32;

/** 一辆带若干节车的车（只给用例要用的字段）。 */
function trainWith(kind: string, cars: readonly Partial<TrainCar>[]): Train {
	const raw = {
		vehicleId: "v1",
		railHex: "H",
		railArcM: 10,
		railArcLengthM: 100,
		mission: {kind},
		cars: cars.map((car, index) => ({
			index,
			railHex: "H",
			railArcM: 10 + index * 16,
			railArcLengthM: 100,
			forward: true,
			lengthM: 16,
			stockId: "saf101",
			capacity: 0,
			...car,
		})),
	};
	return parseTrains([raw])[0]!;
}

/**
 * 把 `d` 里**落在路径上**的点取出来（`M`/`L` 的点、`Q` 的**终点**；控制点不算 —— 它们不在形状上）。
 * 只认这三种命令，够用且能把"角是圆的还是方的"这种判断做实。
 */
function pathPoints(d: string): [number, number][] {
	const points: [number, number][] = [];
	for (const [, command, rawArgs] of d.matchAll(/([MLQZ])([^MLQZ]*)/g)) {
		const numbers = (rawArgs!.match(/-?\d+(?:\.\d+)?/g) ?? []).map(Number);
		// `Q` 的参数是"控制点 + 终点"，终点在后两个数字上；`Z` 没有参数
		const offset = command === "Q" ? 2 : 0;
		if (command !== "Z") {
			points.push([numbers[offset]!, numbers[offset + 1]!]);
		}
	}
	return points;
}

/** 记号在局部坐标里的包围盒（半长、半宽各是多少）。 */
function extents(d: string): {minX: number; maxX: number; minY: number; maxY: number} {
	const points = pathPoints(d);
	return {
		minX: Math.min(...points.map(point => point[0])),
		maxX: Math.max(...points.map(point => point[0])),
		minY: Math.min(...points.map(point => point[1])),
		maxY: Math.max(...points.map(point => point[1])),
	};
}

test("判定：有动力 ⇒ 圆角箭头（货运机车也是）；无动力 + 货运 ⇒ 中空；无动力 + 其它 ⇒ 矩形", () => {
	const passenger = trainWith("PASSENGER", [{powered: true}, {powered: false}]);
	assert.equal(carMarkerKind(passenger, passenger.cars[0]!), "arrow");
	assert.equal(carMarkerKind(passenger, passenger.cars[1]!), "rectangle");
	/*
	 * 货运：**机车画箭头、只有挂车中空**（用户 2026-09-16 追加裁定）。
	 * 判定顺序是"先看这节车有没有动力" —— "货运"是**任务级**判据（引擎里车本身不带"我拉货"这个字段），
	 * 而"机车"在数据上就是"有动力的那节"，所以只有无动力的那几节才用得上任务种类。
	 */
	const freight = trainWith("FREIGHT", [{powered: true}, {powered: false}, {powered: false}]);
	assert.equal(carMarkerKind(freight, freight.cars[0]!), "arrow", "货运机车必须还是箭头");
	assert.equal(carMarkerKind(freight, freight.cars[1]!), "hollow");
	assert.equal(carMarkerKind(freight, freight.cars[2]!), "hollow");
	// 调车任务与客运同一套规则：无动力 ⇒ 矩形（不是中空）
	const maneuver = trainWith("MANEUVER", [{powered: false}, {powered: true}]);
	assert.equal(carMarkerKind(maneuver, maneuver.cars[0]!), "rectangle");
	assert.equal(carMarkerKind(maneuver, maneuver.cars[1]!), "arrow");
	// 引擎没说是无动力时（缺省 powered = true）⇒ 箭头，不会凭空多出一堆矩形
	const unknown = trainWith("PASSENGER", [{}]);
	assert.equal(unknown.cars[0]!.powered, true);
	assert.equal(carMarkerKind(unknown, unknown.cars[0]!), "arrow");
});

test("解析：每节车的动力/方向/车长都取到；引擎没说的按「有动力、弧增」算", () => {
	const train = trainWith("PASSENGER", [
		{powered: false, forward: false, lengthM: 20, stockId: "trailer", capacity: 120},
		{},
	]);
	assert.equal(train.cars.length, 2);
	assert.deepEqual(
		train.cars.map(car => [car.index, car.powered, car.forward, car.lengthM, car.stockId, car.capacity]),
		[[0, false, false, 20, "trailer", 120], [1, true, true, 16, "saf101", 0]],
	);
	assert.equal(train.missionKind, "PASSENGER");
});

test("圆角箭头：有曲线、四周圆角、车头在 +x 且收成一点", () => {
	const d = markerPath("arrow", LENGTH, WIDTH);
	assert.ok(d.includes("Q"), `箭头必须有二次曲线（圆角），实际：${d}`);
	const box = extents(d);
	assert.equal(box.maxX, LENGTH / 2);
	assert.equal(box.minX, -LENGTH / 2);
	assert.equal(box.maxY, WIDTH / 2);
	assert.equal(box.minY, -WIDTH / 2);
	// 车头：最前面那个点在正中（y = 0）—— 尖是"收"出来的，不是一条平边
	const nose = pathPoints(d).filter(point => point[0] === LENGTH / 2);
	assert.equal(nose.length, 1);
	assert.equal(nose[0]![1], 0);
	// 车尾两角是圆的：|y| 到半宽的那些点都不在 x = −半长 上（直角矩形会正好重合）
	for (const point of pathPoints(d)) {
		if (Math.abs(point[1]) === WIDTH / 2) {
			assert.ok(point[0] > -LENGTH / 2, `车尾角应当是圆的，实际有点 (${point.join(",")})`);
		}
	}
});

test("箭头收尖段不能只有半宽那么长（否则真车长宽比下画出来是根棍子）", () => {
	// 车宽 1.2 格，车长 16 格：收尖段至少占车长的 30%
	const body = pathPoints(markerPath("arrow", LENGTH, WIDTH))
		.filter(point => point[1] === WIDTH / 2)
		.map(point => point[0]);
	const taperStart = Math.min(...body);
	assert.ok(taperStart <= LENGTH / 2 - LENGTH * 0.3, `收尖段太短：从 x=${taperStart} 才开始收（车头在 ${LENGTH / 2}）`);
});

test("矩形：四条直线、四角见棱、正好是车长 × 记号宽", () => {
	const d = markerPath("rectangle", LENGTH, WIDTH);
	assert.ok(!d.includes("Q"), `矩形不该有曲线：${d}`);
	const box = extents(d);
	assert.deepEqual(box, {minX: -LENGTH / 2, maxX: LENGTH / 2, minY: -WIDTH / 2, maxY: WIDTH / 2});
	// 直角：四个角点都在包围盒的角上（无动力车厢"四角见棱"，与圆角箭头形成对照）
	for (const corner of [[-1, -1], [1, -1], [1, 1], [-1, 1]]) {
		assert.ok(
			pathPoints(d).some(point => point[0] === corner[0]! * LENGTH / 2 && point[1] === corner[1]! * WIDTH / 2),
			`矩形缺角点 (${corner.join(",")})`,
		);
	}
});

test("中空四边形：四个点、后端满宽、前端收窄，并且不填充", () => {
	const d = markerPath("hollow", LENGTH, WIDTH);
	assert.ok(!d.includes("Q"), `中空四边形不该有曲线：${d}`);
	const points = pathPoints(d);
	assert.equal(points.length, 4, `四边形应当只有四个点：${d}`);
	assert.equal(markerFilled("hollow"), false);
	// 后端（−x 侧）满宽：两个点就在 ±半宽
	assert.deepEqual(points.filter(point => point[0] === -LENGTH / 2).map(point => Math.abs(point[1])), [WIDTH / 2, WIDTH / 2]);
	// 前端（+x 侧）收窄 ⇒ 与矩形一眼分得开
	const frontWidth = Math.max(...points.filter(point => point[0] > 0).map(point => Math.abs(point[1])));
	assert.ok(frontWidth < WIDTH / 2, `前端应当比后端窄：${frontWidth} vs ${WIDTH / 2}`);
});

test("三种形状互不相同，且只有中空四边形不填充", () => {
	const kinds: CarMarkerKind[] = ["arrow", "rectangle", "hollow"];
	const paths = kinds.map(kind => markerPath(kind, LENGTH, WIDTH));
	assert.equal(new Set(paths).size, 3, "三种形状的路径必须互不相同");
	assert.deepEqual(kinds.map(markerFilled), [true, true, false]);
});

test("车牌与车长的边界：短车不会画成负长度，浮点尾巴按 0.01 修约", () => {
	// 车长比记号宽度还短 ⇒ 按宽度取（不至于画出一条负长度的边）
	const short = markerPath("rectangle", 1, WIDTH);
	assert.deepEqual(extents(short), {minX: -WIDTH / 2, maxX: WIDTH / 2, minY: -WIDTH / 2, maxY: WIDTH / 2});
	// 修约：车长 16.666… 米 ⇒ 每个数字最多两位小数
	for (const number of markerPath("arrow", 33.333333, WIDTH).match(/-?\d+(?:\.\d+)?/g) ?? []) {
		assert.ok(number.split(".")[1] === undefined || number.split(".")[1]!.length <= 2, `数字没修约：${number}`);
	}
});

test("记号说法与形状一一对应（悬浮提示里写给用户看）", () => {
	assert.equal(markerKindLabel("arrow"), "动车");
	assert.equal(markerKindLabel("rectangle"), "无动力车厢");
	assert.equal(markerKindLabel("hollow"), "货车");
});

test("一节车的悬浮提示：写清第几节、什么车、多长、有没有动力，并带上整车那行", () => {
	const train = trainWith("PASSENGER", [{powered: false, lengthM: 20.5, stockId: "trailer", capacity: 120}]);
	const text = describeCar(train, train.cars[0]!, markerKindLabel("rectangle"));
	for (const piece of ["车 v1", "第 1/1 节", "无动力车厢", "trailer", "20.5 米", "无动力", "载客 120"]) {
		assert.ok(text.includes(piece), `提示里应当有「${piece}」：${text}`);
	}
});
