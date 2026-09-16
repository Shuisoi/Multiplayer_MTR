/*
 * 派生节点（`domain/MapNode.ts`）的用例：把节点 / 道岔 / 信号灯三份数据拼成"节点上的状态"。
 *
 * 跑法：`npm run test:map-nodes`（= `node --experimental-strip-types --test scripts/*.test.ts`）。
 *
 * 为什么值得单独一条：这套拼法是**图上"这个节点是什么状态"的唯一来源**，而且它的两条规则
 * 都有"看着差不多就错"的陷阱：
 *   · 道岔是**每个进向一行**（单开道岔三行说同一个位置）—— 随便挑一行显示，位置就可能说反；
 *   · 信号灯**本来就不在节点上**（立在轨旁，实测偏 2 格），所以必须带容差，而不能
 *     "离得最近的节点就是要它" —— 那样离节点 21 格的灯会硬长在一个错的节点上。
 * 这里用构造数据把这些边界钉住（不依赖引擎，也不依赖世界长什么样）。
 */
import assert from "node:assert/strict";
import {test} from "node:test";
import {deriveMapNodes, MapNode, prohibitedRailHexes, SIGNAL_NODE_MAX_DISTANCE, turnoutActions} from "../src/domain/MapNode.ts";
import {SIGNAL_SLOT_DIRECTION, signalSlot} from "../src/domain/SignalPlacement.ts";
import {parseFlatPoints, parseTrackSections, sectionCutPoints} from "../src/domain/TrackSection.ts";
import {bandOffsetDirection, parseBlockSections, sectionState, type BlockSection} from "../src/domain/BlockSection.ts";
import {Point, railEndpointsText, type RawPoint} from "../src/domain/Point.ts";
import {Node, type RawTopologyNode} from "../src/domain/Node.ts";
import {Signal, type RawSignal} from "../src/domain/Signal.ts";

function node(x: number, z: number, degree = 2, y = -60): Node {
	return new Node({x, y, z, degree, neighbors: []} as RawTopologyNode);
}

function signal(x: number, z: number, aspect = "GREEN", y = -60): Signal {
	return new Signal({key: `${x},${y},${z}`, x, y, z, angle: 0, aspect} as RawSignal);
}

/** 单开道岔的一行（`via` 与 `stem` 相同的才是根部那一行）。 */
function turnoutRow(x: number, z: number, via: string, position: number | null, prohibited = "", y = -60): Point {
	return new Point({
		x, y, z, via, form: "FORK", legs: [], manual: -1,
		...(position === null ? {} : {position}),
		...(prohibited === "" ? {} : {prohibited}),
		stem: "STEM", far: "FAR", branch: "BRANCH",
	} as RawPoint);
}

test("没有道岔也没有灯的节点：两样都是空，不是 undefined", () => {
	const derived = deriveMapNodes([node(0, 0)], [], []);
	const target = derived.nodes[0] as MapNode;
	assert.equal(target.hasTurnout, false);
	assert.equal(target.hasSignal, false);
	assert.equal(target.isPlain, true);
	assert.equal(target.turnoutState, null);
	assert.deepEqual([...target.signals], []);
	assert.equal(target.stateText, "无道岔、无信号灯");
});

test("道岔按节点键精确对上；多行时 turnoutState 取**根部那一行**（只有它能表达位置）", () => {
	const rows = [
		turnoutRow(10, 20, "VIA_A", 1),
		turnoutRow(10, 20, "STEM", 1),
		turnoutRow(10, 20, "VIA_B", 1),
	];
	const derived = deriveMapNodes([node(10, 20, 3)], rows, []);
	const target = derived.nodes[0] as MapNode;
	assert.equal(target.hasTurnout, true);
	assert.equal(target.turnouts.length, 3, "三行都要保留（逐进向的腿号视图）");
	assert.equal(target.turnoutState?.via, "STEM", "状态要取根部那一行");
	assert.equal(target.turnoutState?.turnoutPosition, 1);
	assert.equal(target.turnoutState?.positionText, "岔股开放");
});

test("节点键对不上的道岔行不会挂到任何节点上", () => {
	const derived = deriveMapNodes([node(0, 0)], [turnoutRow(99, 99, "STEM", 0)], []);
	assert.equal(derived.nodes[0]?.hasTurnout, false, "坐标不同的道岔行不该硬塞进来");
});

test("信号灯按**水平最近节点 + 容差**落位：轨旁 2 格的算它，远处的进 orphan 而不是硬塞", () => {
	const near = signal(2, 0, "RED");
	const far = signal(21, 0, "GREEN");
	const derived = deriveMapNodes([node(0, 0)], [], [near, far]);
	assert.deepEqual((derived.nodes[0] as MapNode).signals.map(s => s.state), ["red"], "2 格的灯属于这个节点");
	assert.deepEqual(derived.orphanSignals.map(s => s.key), [far.key], "21 格的灯必须留在 orphan 里");
});

test("容差是 5 格：边界两边分得清", () => {
	const inside = signal(SIGNAL_NODE_MAX_DISTANCE, 0);
	const outside = signal(SIGNAL_NODE_MAX_DISTANCE + 0.5, 0);
	const derived = deriveMapNodes([node(0, 0)], [], [inside, outside]);
	assert.equal((derived.nodes[0] as MapNode).signals.length, 1);
	assert.equal(derived.orphanSignals.length, 1);
});

test("一个节点上多盏灯都留下（两个方向各一盏是常态）", () => {
	const derived = deriveMapNodes([node(0, 0)], [], [signal(-2, 0, "RED"), signal(2, 0, "GREEN")]);
	const target = derived.nodes[0] as MapNode;
	assert.equal(target.signals.length, 2);
	assert.deepEqual([...target.signalStates], ["red", "green"]);
});

test("两个节点都够近时，灯归**更近**的那个", () => {
	const derived = deriveMapNodes([node(0, 0), node(10, 0)], [], [signal(7, 0)]);
	assert.equal((derived.nodes[0] as MapNode).hasSignal, false);
	assert.equal((derived.nodes[1] as MapNode).hasSignal, true);
});

test("灯比节点高一格不影响归属（只比水平距离）", () => {
	const derived = deriveMapNodes([node(0, 0, 2, -60)], [], [signal(2, 0, "GREEN", -59)]);
	assert.equal((derived.nodes[0] as MapNode).hasSignal, true, "y 差 1 格是常态，不该因此丢灯");
});

// ---------------------------------------------------------------- 道岔没指向的那根出口

test("道岔没指向的那根出口：按引擎的 prohibited 收集（不自己算几何）", () => {
	const derived = deriveMapNodes([node(0, 0, 3)], [turnoutRow(0, 0, "STEM", 1, "FAR")], []);
	assert.deepEqual([...prohibitedRailHexes(derived.nodes)], ["FAR"]);
});

test("同一位单开道岔的多行说的是同一根：集合自动去重", () => {
	const rows = [
		turnoutRow(0, 0, "VIA_A", 1, "FAR"),
		turnoutRow(0, 0, "STEM", 1, "FAR"),
		turnoutRow(0, 0, "VIA_B", 1, "FAR"),
	];
	const derived = deriveMapNodes([node(0, 0, 3)], rows, []);
	assert.deepEqual([...prohibitedRailHexes(derived.nodes)], ["FAR"], "三行一视同仁，去重后只有一根");
});

test("位置换了，被淡出的那根也跟着换", () => {
	const atBranch = deriveMapNodes([node(0, 0, 3)], [turnoutRow(0, 0, "STEM", 1, "FAR")], []);
	const atStraight = deriveMapNodes([node(0, 0, 3)], [turnoutRow(0, 0, "STEM", 0, "BRANCH")], []);
	assert.deepEqual([...prohibitedRailHexes(atBranch.nodes)], ["FAR"]);
	assert.deepEqual([...prohibitedRailHexes(atStraight.nodes)], ["BRANCH"]);
});

test("没有道岔、或道岔行没给 prohibited：空集合（不是 undefined）", () => {
	assert.equal(prohibitedRailHexes(deriveMapNodes([node(0, 0)], [], []).nodes).size, 0);
	assert.equal(prohibitedRailHexes(deriveMapNodes([node(0, 0, 3)], [turnoutRow(0, 0, "STEM", 1)], []).nodes).size, 0);
});

test("多个道岔各淡各的：并集", () => {
	const derived = deriveMapNodes(
		[node(0, 0, 3), node(50, 0, 3)],
		[turnoutRow(0, 0, "STEM", 0, "BRANCH"), turnoutRow(50, 0, "STEM", 1, "FAR")],
		[],
	);
	assert.deepEqual([...prohibitedRailHexes(derived.nodes)].sort(), ["BRANCH", "FAR"]);
});

// ---------------------------------------------------------------- 菜单里的扳岔动作

/** 单开道岔的根部那一行：两条腿分别是正线远端与岔股（腿序"直通→右"）。 */
function stemRowWithLegs(position: number): Point {
	return new Point({
		x: 0, y: -60, z: 0, via: "STEM", form: "FORK", manual: -1, position,
		stem: "STEM", far: "FAR", branch: "BRANCH",
		legs: [{hex: "FAR", kind: "STRAIGHT"}, {hex: "BRANCH", kind: "RIGHT"}],
	} as RawPoint);
}

test("单开道岔：菜单给位置 0/1，腿号按轨换算（不是直接发 0/1）", () => {
	const derived = deriveMapNodes([node(0, 0, 3)], [stemRowWithLegs(1)], []);
	const actions = turnoutActions(derived.nodes[0] as MapNode);
	assert.deepEqual(actions.map(a => [a.label, a.leg, a.current]), [
		["位置 0（正线贯通）", 0, false],
		["位置 1（岔股开放）", 1, true],
	]);
	assert.equal(actions[0]?.via, "STEM", "用根部那一行的 via 下发");
});

test("腿序与位置不一致时也不发错：按轨找腿", () => {
	// 腿序反过来（第一条腿是岔股）—— 位置 0 应该发**腿 1**
	const reversed = new Point({
		x: 0, y: -60, z: 0, via: "STEM", form: "FORK", manual: -1, position: 0,
		stem: "STEM", far: "FAR", branch: "BRANCH",
		legs: [{hex: "BRANCH", kind: "RIGHT"}, {hex: "FAR", kind: "STRAIGHT"}],
	} as RawPoint);
	const actions = turnoutActions(deriveMapNodes([node(0, 0, 3)], [reversed], []).nodes[0] as MapNode);
	assert.deepEqual(actions.map(a => [a.label, a.leg]), [
		["位置 0（正线贯通）", 1],
		["位置 1（岔股开放）", 0],
	]);
});

/*
 * 道岔卡片必须说清「两个位置各接哪两根轨」（用户 2026-09-16 现场问的正是这件事）。
 *
 * 背景：用户在 `-176,-60,-253` 那处道岔上问：「为什么像是在 `-176,-60,-222` 与 `-176,-60,-289`
 * 之间切？不应该是在 `-176,-60,-289` 与 `-170,-60,-289` 之间么？」
 * 引擎的模型是对的（位置 0 = 根部↔正线远端、位置 1 = 根部↔岔股）—— 问题在**卡片只说
 * "正线贯通 / 岔股开放"**，人没法把它与图上看到的轨对上；而那句提示从前用 `hex.slice(0,8)`，
 * 负坐标轨全是 `FFFFFFFF`，等于没说。下面用**这个世界里那处道岔的真实 hex** 钉住新说法。
 */
const STEM_HEX = "FFFFFFFFFFFFFF50-FFFFFFFFFFFFFFC4-FFFFFFFFFFFFFF03-FFFFFFFFFFFFFF50-FFFFFFFFFFFFFFC4-FFFFFFFFFFFFFF22";
const FAR_HEX = "FFFFFFFFFFFFFF50-FFFFFFFFFFFFFFC4-FFFFFFFFFFFFFEDF-FFFFFFFFFFFFFF50-FFFFFFFFFFFFFFC4-FFFFFFFFFFFFFF03";
const BRANCH_HEX = "FFFFFFFFFFFFFF50-FFFFFFFFFFFFFFC4-FFFFFFFFFFFFFF03-FFFFFFFFFFFFFF56-FFFFFFFFFFFFFFC4-FFFFFFFFFFFFFEDF";

test("轨 hex 解出两端坐标（负坐标是 64 位补码，不能按无符号读）", () => {
	assert.equal(railEndpointsText(STEM_HEX), "(-176,-253)↔(-176,-222)", "根部：北侧那根");
	assert.equal(railEndpointsText(FAR_HEX), "(-176,-289)↔(-176,-253)", "正线远端：南侧直的那根");
	assert.equal(railEndpointsText(BRANCH_HEX), "(-176,-253)↔(-170,-289)", "岔股：南侧斜的那根");
});

test("hex 不是六段 / 不是十六进制时原样返回（不许抛异常把整张卡片弄没）", () => {
	assert.equal(railEndpointsText("VIA_A"), "VIA_A");
	assert.equal(railEndpointsText("ZZ-ZZ-ZZ-ZZ-ZZ-ZZ"), "ZZ-ZZ-ZZ-ZZ-ZZ-ZZ");
});

test("位置 0/1 的说明写全三件事：根部、接谁、切谁（这就是「看不出来」的修法）", () => {
	const row = new Point({
		x: -176, y: -60, z: -253, via: STEM_HEX, form: "FORK", manual: -1, position: 0,
		stem: STEM_HEX, far: FAR_HEX, branch: BRANCH_HEX, prohibited: BRANCH_HEX,
		legs: [{hex: FAR_HEX, kind: "STRAIGHT"}, {hex: BRANCH_HEX, kind: "STRAIGHT"}],
	} as RawPoint);
	assert.equal(row.turnoutPositionText(0),
		"位置 0（正线贯通）：根部 (-176,-253)↔(-176,-222) 接正线远端 (-176,-289)↔(-176,-253)；岔股 (-176,-253)↔(-170,-289) 禁止通行");
	assert.equal(row.turnoutPositionText(1),
		"位置 1（岔股开放）：根部 (-176,-253)↔(-176,-222) 接岔股 (-176,-253)↔(-170,-289)；正线远端 (-176,-289)↔(-176,-253) 禁止通行");
	assert.equal(row.prohibitedText, "岔股 (-176,-253)↔(-170,-289)", "被切的那根也带坐标");
});

test("菜单动作带上位置号（卡片靠它问「这个位置接哪两根轨」）", () => {
	// 夹具行在 (0,-60,0)（见 stemRowWithLegs），所以节点也要给同一格 —— 派生是按**节点键精确匹配**的
	const actions = turnoutActions(deriveMapNodes([node(0, 0, 3)], [stemRowWithLegs(1)], []).nodes[0] as MapNode);
	assert.deepEqual(actions.map(a => [a.position, a.label]), [
		[0, "位置 0（正线贯通）"],
		[1, "位置 1（岔股开放）"],
	]);
	assert.equal(actions[0]?.via, "STEM", "仍然用根部那一行的 via 下发");
});

test("没有物理模型的岔口：逐行逐腿列出来", () => {
	const rows = [
		new Point({x: 0, y: -60, z: 0, via: "VIA_A", form: "FORK", manual: 0,
			legs: [{hex: "R1", kind: "STRAIGHT"}, {hex: "R2", kind: "LEFT"}]} as RawPoint),
		new Point({x: 0, y: -60, z: 0, via: "VIA_B", form: "TEE", manual: -1,
			legs: [{hex: "R3", kind: "LEFT"}, {hex: "R4", kind: "RIGHT"}]} as RawPoint),
	];
	const actions = turnoutActions(deriveMapNodes([node(0, 0, 4)], rows, []).nodes[0] as MapNode);
	assert.equal(actions.length, 4);
	assert.deepEqual(actions.map(a => [a.via, a.leg]), [["VIA_A", 0], ["VIA_A", 1], ["VIA_B", 0], ["VIA_B", 1]]);
	// 第一行设过 manual=0（= 腿 0 当前位），第二行没设过（默认也是腿 0）
	assert.deepEqual(actions.map(a => a.current), [true, false, true, false]);
});

test("普通节点没有任何扳岔动作（菜单上只会出现「复制坐标」）", () => {
	assert.deepEqual(turnoutActions(deriveMapNodes([node(0, 0)], [], []).nodes[0] as MapNode), []);
});

test("换算不出腿号时不列这个按钮（而不是列一个点了会静默失败的）", () => {
	const broken = new Point({
		x: 0, y: -60, z: 0, via: "STEM", form: "FORK", manual: -1, position: 0,
		stem: "STEM", far: "FAR", branch: "BRANCH",
		legs: [{hex: "SOMETHING_ELSE", kind: "STRAIGHT"}],
	} as RawPoint);
	assert.deepEqual(turnoutActions(deriveMapNodes([node(0, 0, 3)], [broken], []).nodes[0] as MapNode), []);
});

// ---------------------------------------------------------------- 信号灯的方位槽

test("方位槽取主轴：实测的 dx=±2 全落在左右，dz=±3 落在上下", () => {
	assert.equal(signalSlot(0, 0, -2, 0), "left");
	assert.equal(signalSlot(0, 0, 2, 0), "right");
	assert.equal(signalSlot(0, 0, 3, 0), "right");
	assert.equal(signalSlot(0, 0, -3, 0), "left");
	assert.equal(signalSlot(0, 0, 0, 3), "down");
	assert.equal(signalSlot(0, 0, 0, -3), "up");
});

test("主轴优先：斜着的偏移按绝对值大的那一轴归类", () => {
	assert.equal(signalSlot(0, 0, 4, 1), "right", "dx 更大 → 右");
	assert.equal(signalSlot(0, 0, 1, 4), "down", "dz 更大 → 下");
	assert.equal(signalSlot(0, 0, -1, -4), "up");
	assert.equal(signalSlot(0, 0, -4, -1), "left");
});

test(" |dx| = |dz| 时算左右（45° 也不含糊）", () => {
	assert.equal(signalSlot(0, 0, 2, 2), "right");
	assert.equal(signalSlot(0, 0, -2, 2), "left");
});

test("灯正好落在节点上：给确定的「上」，不靠比较顺序碰运气", () => {
	assert.equal(signalSlot(7, -3, 7, -3), "up");
});

test("方向向量与槽名一致（画布 y 向下，所以「上」是 −z）", () => {
	assert.deepEqual(SIGNAL_SLOT_DIRECTION.up, [0, -1]);
	assert.deepEqual(SIGNAL_SLOT_DIRECTION.down, [0, 1]);
	assert.deepEqual(SIGNAL_SLOT_DIRECTION.left, [-1, 0]);
	assert.deepEqual(SIGNAL_SLOT_DIRECTION.right, [1, 0]);
	assert.equal(signalSlot(0, 0, 0, 5) === "down" && SIGNAL_SLOT_DIRECTION.down[1] > 0, true, "z 变大是往下");
});

// ---------------------------------------------------------------- L1 轨道区间（区间切点）

test("接口的 points 是**扁平**数组：18 个数 = 9 个点，不是 18 个点", () => {
	const points = parseFlatPoints([1.5, 6.5, 1.5, 10.5, 1.5, 14.5]);
	assert.equal(points.length, 3, "6 个数拆成 3 个点");
	assert.deepEqual(points[0], [1.5, 6.5]);
	assert.deepEqual(points[2], [1.5, 14.5]);
});

test("扁平数组是奇数个（数据坏了）时，最后那个孤零零的数丢掉，不是补 0", () => {
	assert.deepEqual(parseFlatPoints([1, 2, 3]), [[1, 2]]);
	assert.deepEqual(parseFlatPoints([]), []);
	assert.deepEqual(parseFlatPoints(undefined), []);
});

test("区间切点 = 每个 span 的两端，去重后按首次出现排序", () => {
	const sections = parseTrackSections([
		{id: "T1", length: 32, occupied: false, spans: [{hex: "R1", from: 0, to: 32, points: [1.5, 6.5, 1.5, 38.5]}]},
		// 第二条与第一条共用 (1.5,38.5) 这个切点，另一端是新的
		{id: "T2", length: 20, occupied: true, spans: [{hex: "R2", from: 0, to: 20, points: [1.5, 38.5, 1.5, 58.5]}]},
	]);
	const cut = sectionCutPoints(sections);
	assert.deepEqual(cut.map(p => p.key), ["1.5,6.5", "1.5,38.5", "1.5,58.5"], "共用端点只出现一次");
	assert.equal(sections[1]?.occupied, true, "occupied 透传");
});

test("一条区间多个 span：每条都贡献两端（这就是「区间图」的节点）", () => {
	const sections = parseTrackSections([{
		id: "T3", length: 40, occupied: false,
		spans: [
			{hex: "R1", from: 0, to: 20, points: [0.5, 0.5, 0.5, 20.5]},
			{hex: "R2", from: 0, to: 20, points: [0.5, 20.5, 0.5, 40.5]},
		],
	}]);
	assert.deepEqual(sectionCutPoints(sections).map(p => p.key), ["0.5,0.5", "0.5,20.5", "0.5,40.5"]);
});

test("没有 span（或 span 没给点）时不给切点，也不炸", () => {
	assert.deepEqual(sectionCutPoints(parseTrackSections([{id: "T4", length: 0}])), []);
	assert.deepEqual(sectionCutPoints(parseTrackSections([{id: "T5", length: 0, spans: [{hex: "R", from: 0, to: 0, points: []}]}])), []);
});

// ---------------------------------------------------------------- L2 行车区间（显示状态与带的方向）

/** 造一条行车区间（只关心状态判据时要的字段）。 */
function blockSection(aspect: string, occupied: boolean, dx = 0, dz = 1): BlockSection {
	return parseBlockSections([{id: "S", aspect, occupied, direction: {dx, dz}}])[0] as BlockSection;
}

test("状态判据：占用最优先（有车就是有车）", () => {
	assert.equal(sectionState(blockSection("GREEN", true)), "occupied");
	assert.equal(sectionState(blockSection("RED", true)), "occupied");
	assert.equal(sectionState(blockSection("SINGLE_YELLOW", true)), "occupied", "单黄 + 有车也算占用");
});

test("状态判据：单黄 / 双黄各一档（用户点名的两档）", () => {
	assert.equal(sectionState(blockSection("SINGLE_YELLOW", false)), "singleYellow");
	assert.equal(sectionState(blockSection("DOUBLE_YELLOW", false)), "doubleYellow");
});

test("状态判据：**红灯但空着**是单独一档（实测 13 条：前方占用导致的）", () => {
	assert.equal(sectionState(blockSection("RED", false)), "red", "不能因为空着就当绿灯画");
});

test("状态判据：绿灯且空着 = clear；灯位没给也算 clear（接口没给结论，按最不吓人的画）", () => {
	assert.equal(sectionState(blockSection("GREEN", false)), "clear");
	assert.equal(sectionState(blockSection("", false)), "clear");
});

test("带往哪边让：垂直于行车方向，两个相反方向各自让到一侧（与实测的 ±1 一致）", () => {
	assert.deepEqual(bandOffsetDirection(blockSection("GREEN", false, 0, 1)), [-1, 0], "南行 (0,1) → 让到 (−1,0)");
	assert.deepEqual(bandOffsetDirection(blockSection("GREEN", false, 0, -1)), [1, 0], "北行 (0,−1) → 让到 (1,0)");
	assert.deepEqual(bandOffsetDirection(blockSection("GREEN", false, 1, 0)), [0, 1], "东行 (1,0) → 让到 (0,1)");
	assert.deepEqual(bandOffsetDirection(blockSection("GREEN", false, 0, 0)), [0, 0], "方向没给就不让（不猜）");
});

test("带的方向是**单位向量**（斜行车向也归一，不让出去的距离忽大忽小）", () => {
	const offset = bandOffsetDirection(blockSection("GREEN", false, 0.6, 0.8));
	assert.equal(Math.round(Math.hypot(offset[0], offset[1]) * 1e6) / 1e6, 1);
});

test("L2 解析：spans 的扁平点要拆开，dirOfTravel/reachable 缺失时按「是」处理", () => {
	const parsed = parseBlockSections([{
		id: "S2", aspect: "GREEN", occupied: false,
		spans: [{hex: "R", from: 0, to: 10, points: [1.5, 2.5, 1.5, 6.5]}],
	}])[0] as BlockSection;
	assert.deepEqual(parsed.spans[0]?.points, [[1.5, 2.5], [1.5, 6.5]]);
	assert.equal(parsed.spans[0]?.alongDirection, true);
	assert.equal(parsed.spans[0]?.reachable, true);
});
