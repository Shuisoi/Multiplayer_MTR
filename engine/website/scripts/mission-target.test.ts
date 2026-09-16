/*
 * **任务与进路的解析**（`domain/Train.ts` 的解析 + 标签）与 **任务目标的换名字**
 * （`domain/MissionTarget.ts`）—— 用户 2026-09-16：「游戏内的列车都是通过任务驱动的，让列车图标能够点击，
 * 点击后高亮并提示**任务目标**还有路线图叠加层。」
 *
 * 跑法：`npm run test:mission-target`
 * （= `node --experimental-strip-types --test scripts/mission-target.test.ts`）。
 *
 * 为什么值得一条：这两件事都"错了也显示得出来"——
 *   · 进路的轨是 `rails[]` 里的一串 hex，**方向可能相反**，不规范化就会有条线画不出来；
 *   · 任务目标那个字段叫 `targetSidingId`，可它**装的经常是站台 id**（现场实测：客运任务的目标
 *     就是站台）。查错清单的结果是"目标 954364252968674217"这种没人看得懂的一行字 —— 全靠用例钉住。
 */
import assert from "node:assert/strict";
import {test} from "node:test";
import {missionTarget, platformLabel} from "../src/domain/MissionTarget.ts";
import {parsePlatforms, type RawPlatform} from "../src/domain/Platform.ts";
import {parseSidings, type Siding} from "../src/domain/Siding.ts";
import {describeMission, hasRoute, parseTrains, type Train} from "../src/domain/Train.ts";

/** 两条股道 + 两个站台（现场那份数据的形状：股道有车场名，站台有站名/站台号/方向）。 */
const SIDINGS: readonly Siding[] = parseSidings([
	{sidingId: "-4629294257679021237", sidingName: "1", depotName: "987654"},
	{sidingId: "-885857706830354876", sidingName: "2", depotName: ""},
]);
const PLATFORMS = parsePlatforms([
	{
		platformId: "954364252968674217", platformName: "1", stationId: "4244617445981648900", stationName: "3",
		railHex: "R1", x1: -170, z1: -478, x2: -170, z2: -458, lengthM: 20,
		direction: {dx: 0, dz: 1, angle: 0, label: "南行"},
	} as RawPlatform,
	{platformId: "1", platformName: "", stationId: "2", stationName: "", railHex: "R2", x1: 0, z1: 0, x2: 0, z2: 10, lengthM: 10} as RawPlatform,
]);

/** 两条真实形状的 hex（六段 `x1-y1-z1-x2-y2-z2`）：第二条是第一条**反着写**的同一根轨。 */
const RAIL_A = "1-64-1-2-64-2";
const RAIL_A_REVERSED = "2-64-2-1-64-1";
const RAIL_B = "3-64-3-4-64-4";

/** 一辆车（只给用例要用的字段），带任务与进路。 */
function train(raw: Record<string, unknown> = {}): Train {
	return parseTrains([{
		vehicleId: "v1",
		railHex: "H",
		railArcM: 10,
		railArcLengthM: 100,
		mission: {
			kind: "PASSENGER",
			state: "DISPATCHED",
			executor: "AUTOPILOT",
			startSidingId: "-4629294257679021237",
			targetSidingId: "954364252968674217",
		},
		route: {kind: "MAIN", state: "SET", railCount: 3, forkCount: 1, rails: [RAIL_A, RAIL_B, RAIL_A_REVERSED]},
		cars: [{index: 0, railHex: "H", railArcM: 10, railArcLengthM: 100, lengthM: 16, powered: true}],
		...raw,
	}])[0]!;
}

test("任务：kind / state / executor / 起点 / 目标都解析出来，标签是中文", () => {
	const parsed = train();
	assert.equal(parsed.missionKind, "PASSENGER");
	assert.equal(parsed.missionState, "DISPATCHED");
	assert.equal(parsed.missionExecutor, "AUTOPILOT");
	assert.equal(parsed.startSidingId, "-4629294257679021237");
	assert.equal(parsed.targetSidingId, "954364252968674217");
	assert.equal(describeMission(parsed), "客运任务 · 已派出 · 自动驾驶");
});

test("没有任务的车：`describeMission` 是空串（卡片上写「没有任务」是卡片的事）", () => {
	const parsed = train({mission: undefined});
	assert.equal(parsed.missionKind, "");
	assert.equal(describeMission(parsed), "");
});

test("任务失败时把原因带上（引擎只在 FAILED 时给这个字段）", () => {
	const parsed = train({mission: {kind: "MANEUVER", state: "FAILED", executor: "PLAYER", failureReason: "道岔没拿到"}});
	assert.equal(describeMission(parsed), "调车任务 · 失败 · 玩家驾驶 · 原因 道岔没拿到");
});

test("进路：`rails[]` 落成**规范键**并去重（方向反了的 hex 也要能对上图上的轨）", () => {
	const parsed = train();
	// 第二条与第一条是同一条轨（只是六段反着写，实测这张世界 159 根轨里有 10 根这样）
	// ⇒ 规范化后必须合成一个键，且顺序保持引擎给的先后
	assert.deepEqual(parsed.routeRailKeys, [RAIL_A, RAIL_B]);
	assert.equal(parsed.routeKind, "MAIN");
	assert.equal(parsed.routeState, "SET");
	assert.equal(parsed.routeForkCount, 1);
	assert.equal(hasRoute(parsed), true);
	assert.equal(train({route: {kind: "MAIN", state: "PENDING", rails: [RAIL_A, RAIL_B]}}).routeRailKeys.length, 2);
});

test("没有进路（引擎不给 `route` 这个键）：轨为空、`hasRoute` 为 false —— 叠加层什么也不画", () => {
	const parsed = train({route: undefined});
	assert.deepEqual(parsed.routeRailKeys, []);
	assert.equal(parsed.routeKind, "");
	assert.equal(hasRoute(parsed), false);
});

test("任务目标：**客运任务的目标是站台** —— 换成「站 3 · 站台 1（南行）」", () => {
	const resolved = missionTarget("954364252968674217", SIDINGS, PLATFORMS);
	assert.equal(resolved.kind, "platform");
	assert.equal(resolved.text, "站 3 · 站台 1（南行）");
});

test("任务目标：调车/回段的目标是**股道** —— 换成「车场 987654 · 股道 1」", () => {
	const resolved = missionTarget("-4629294257679021237", SIDINGS, PLATFORMS);
	assert.equal(resolved.kind, "siding");
	assert.equal(resolved.text, "车场 987654 · 股道 1");
});

test("两个清单都查不到 ⇒ 退回 id 原文（宁可难看也不要写「未知」：id 至少能拿去对数据）", () => {
	const resolved = missionTarget("123456789", SIDINGS, PLATFORMS);
	assert.equal(resolved.kind, "unknown");
	assert.equal(resolved.text, "目标 123456789");
	assert.equal(resolved.id, "123456789");
	// 没有任务的车：引擎给空串
	assert.deepEqual(missionTarget("", SIDINGS, PLATFORMS), {kind: "unknown", text: "—", id: ""});
});

test("站台名字缺一块时的退化：没站名只写站台号，没方向标签就不写括号", () => {
	assert.equal(platformLabel(PLATFORMS[1]!), "站台 1");
	const noDirection = parsePlatforms([{platformId: "p", platformName: "2", stationName: "5", railHex: "R", x1: 0, z1: 0, x2: 0, z2: 1, lengthM: 1} as RawPlatform])[0]!;
	assert.equal(platformLabel(noDirection), "站 5 · 站台 2");
});

test("股道没车场名时只写股道号（dev 世界里真有这样的股道）", () => {
	assert.equal(missionTarget("-885857706830354876", SIDINGS, PLATFORMS).text, "股道 2");
});
