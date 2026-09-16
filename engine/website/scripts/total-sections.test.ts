/*
 * 总区间（`domain/TotalSection.ts`）的用例：**一处一条带**的状态判据。
 *
 * 跑法：`npm run test:total-sections`（= `node --experimental-strip-types --test scripts/total-sections.test.ts`）。
 *
 * 为什么值得单独一条：总区间是地图上"一条带"的唯一来源，而它的状态判据有三个"看着差不多就错"的地方：
 *   · 一处位置上挂着**多个方向**的区间，各自的红/黄/绿不一定相同 —— 一条带只能有一个颜色，必须**取最不利**；
 *   · 两侧都是**无灯大区间**时是"无信号"，不是"绿灯空闲"（"没有灯"不等于"可以走"）；
 *   · 车在里面时是**占用**，压过任何灯位（"红灯区间"与"有车的区间"要分得开 —— 现状实测 13 条红灯是空着的）。
 * 这里用构造数据把这些边界钉住（不依赖引擎，也不依赖世界长什么样）。
 */
import assert from "node:assert/strict";
import {test} from "node:test";
import {parseTotalSections, totalSectionState, type RawTotalSection} from "../src/domain/TotalSection.ts";

/** 一条 cover（默认是"灯守着的、空闲的"）。 */
function cover(entrySignal: string, aspect: string, occupied = false, uncovered = false): Record<string, unknown> {
	return {
		section: entrySignal, entrySignal, exitSignal: "", next: "", aspect, occupied, uncovered,
		length: 100, direction: {angle: 270, label: "东", dx: 1, dz: 0},
	};
}

/** 一条总区间。 */
function total(overrides: Partial<RawTotalSection> = {}): RawTotalSection {
	return {
		id: "T1", length: 100, occupied: false, directions: 2, staggered: false,
		covers: [cover("50,0,0", "GREEN"), cover("150,0,0", "GREEN")],
		spans: [{hex: "AB", from: 50, to: 150, points: [0, 0, 1, 0]}],
		...overrides,
	};
}

test("两条 cover 都绿且空着 ⇒ 空闲", () => {
	assert.equal(totalSectionState(parseTotalSections([total()])[0]!), "clear");
});

test("有车 ⇒ 占用（压过任何灯位：车就是车）", () => {
	const raw = total({
		occupied: true,
		covers: [cover("50,0,0", "GREEN", true), cover("150,0,0", "GREEN", true)],
	});
	assert.equal(totalSectionState(parseTotalSections([raw])[0]!), "occupied");
});

test("一盏红一盏绿 ⇒ 取最不利（红）：地图上一条带只能有一个颜色", () => {
	const raw = total({covers: [cover("50,0,0", "RED"), cover("150,0,0", "GREEN")]});
	assert.equal(totalSectionState(parseTotalSections([raw])[0]!), "red");
});

test("单黄比双黄更严：两盏都亮时取单黄", () => {
	const raw = total({covers: [cover("50,0,0", "SINGLE_YELLOW"), cover("150,0,0", "DOUBLE_YELLOW")]});
	assert.equal(totalSectionState(parseTotalSections([raw])[0]!), "singleYellow");
});

test("双黄 + 绿 ⇒ 双黄（黄灯那一路说了算）", () => {
	const raw = total({covers: [cover("50,0,0", "DOUBLE_YELLOW"), cover("150,0,0", "GREEN")]});
	assert.equal(totalSectionState(parseTotalSections([raw])[0]!), "doubleYellow");
});

test("两侧都是无灯大区间 ⇒ 无信号（绝不是绿灯）", () => {
	const raw = total({
		directions: 2,
		covers: [cover("", "", false, true), cover("", "", false, true)],
	});
	assert.equal(totalSectionState(parseTotalSections([raw])[0]!), "unsignalled");
});

test("一侧有灯、一侧无灯 ⇒ 按有灯那一侧画（「没灯」不是「红灯」）", () => {
	const raw = total({directions: 1, covers: [cover("150,0,0", "GREEN"), cover("", "", false, true)]});
	assert.equal(totalSectionState(parseTotalSections([raw])[0]!), "clear");
});

test("接口没给 covers（数据坏了）⇒ 无信号，不是默认绿灯", () => {
	assert.equal(totalSectionState(parseTotalSections([total({covers: []})])[0]!), "unsignalled");
});

test("解析：id / 长 / 方向数 / 错开 / cover 的灯与显示 / 无灯大区间带空入口灯", () => {
	const parsed = parseTotalSections([total({
		id: "T12", length: 100, directions: 2, staggered: true,
		covers: [cover("50,0,0", "RED"), cover("", "", false, true)],
	})])[0]!;
	assert.equal(parsed.id, "T12");
	assert.equal(parsed.lengthM, 100);
	assert.equal(parsed.directionCount, 2);
	assert.equal(parsed.staggered, true);
	assert.equal(parsed.covers.length, 2);
	assert.equal(parsed.covers[0]!.entrySignal, "50,0,0");
	assert.equal(parsed.covers[0]!.aspect, "RED");
	assert.equal(parsed.covers[0]!.uncovered, false);
	assert.equal(parsed.covers[1]!.uncovered, true, "入口灯为空 ⇒ 无灯大区间");
	assert.equal(parsed.covers[1]!.directionLabel, "东");
});

test("解析：spans 的扁平采样点要拆开（与 L1/L2 同一套坐标）", () => {
	const parsed = parseTotalSections([total({
		spans: [{hex: "AB", from: 50, to: 150, points: [0, 0, 1, 0, 2, 0]}],
	})])[0]!;
	assert.equal(parsed.spans.length, 1);
	assert.deepEqual(parsed.spans[0]!.points, [[0, 0], [1, 0], [2, 0]]);
	assert.equal(parsed.spans[0]!.railHex, "AB");
	assert.equal(parsed.spans[0]!.fromM, 50);
});
