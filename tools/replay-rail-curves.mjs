// 离线复算：把 RailLayer/railGeometry 的曲线算法按同样的输入跑一遍，打印每条斜向轨的中间量。
//
// 为什么要有这个脚本：线型/切线这类问题在浏览器里只能看到结果（"本该是曲线却是直线"），
// 看不到是哪一个中间量把结果改掉的。这里用真实的引擎数据复算，把 chord / 切向量 / 控制点 /
// 弯曲量 / 是否退化成直线 全部打出来，一眼就能定位是阈值吃掉了它，还是切线取错了。
//
// 用法：node tools/replay-rail-curves.mjs <topology.json>
//   topology.json = GET /mtr/api/map/mmtr-topology 的原样响应

import {readFileSync} from "node:fs";

const AXIS_TOLERANCE = 0.05;
const TANGENT_LENGTH_RATIO = 0.533;
const MIN_DEVIATION_SCALE = 0.5;
const MAX_DEVIATION_SCALE = 1.2;
const TANGENT_SAMPLE_RATIO = 0.15;
const TANGENT_MIN_DISTANCE = 2;
const MIN_SAGITTA_WORLD = 0.2;

const isAxisAligned = (x1, z1, x2, z2) => Math.abs(x1 - x2) < AXIS_TOLERANCE || Math.abs(z1 - z2) < AXIS_TOLERANCE;

function unit(v) {
	const l = Math.hypot(v.x, v.y);
	return l > 1e-9 ? {x: v.x / l, y: v.y / l} : null;
}

function endpointTangents(realPath) {
	if (realPath.length < 4) return {tangents: null, why: "采样点不足"};
	let total = 0;
	for (let i = 1; i < realPath.length; i++) total += Math.hypot(realPath[i].x - realPath[i-1].x, realPath[i].y - realPath[i-1].y);
	const minDistance = Math.max(TANGENT_MIN_DISTANCE, total * TANGENT_SAMPLE_RATIO);
	const start = walkDirection(realPath, minDistance);
	const end = walkDirection([...realPath].reverse(), minDistance);
	return start && end ? {tangents: {start, end}, why: null} : {tangents: null, why: "方向退化"};
}

function walkDirection(points, minDistance) {
	const first = points[0];
	let travelled = 0;
	for (let i = 1; i < points.length; i++) {
		travelled += Math.hypot(points[i].x - points[i-1].x, points[i].y - points[i-1].y);
		if (travelled >= minDistance) {
			const d = unit({x: points[i].x - first.x, y: points[i].y - first.y});
			if (d) return d;
		}
	}
	return unit({x: points[points.length-1].x - first.x, y: points[points.length-1].y - first.y});
}

function analyse(rail) {
	const from = {x: rail.x1, y: -rail.z1};
	const to = {x: rail.x2, y: -rail.z2};
	const realPath = (rail.path ?? []).map(p => ({x: p[0], y: -p[2]}));
	const dx = to.x - from.x, dy = to.y - from.y;
	const chord = Math.hypot(dx, dy);
	if (!(chord > 1e-6)) return {kind: "degenerate"};
	if (isAxisAligned(rail.x1, rail.z1, rail.x2, rail.z2)) return {kind: "axis", chord};

	const tangentResult = endpointTangents(realPath);
	const tangents = tangentResult.tangents;
	let u = tangents ? tangents.start : {x: dx / chord, y: dy / chord};
	let v = tangents ? tangents.end : {x: -dx / chord, y: -dy / chord};
	// 采样路径顺序可能与声明端点反向，反了就交换两条切线（与 railGeometry.ts 同一处理）
	if (tangents && (u.x - v.x) * dx + (u.y - v.y) * dy < 0) {
		const swap = u;
		u = v;
		v = swap;
	}
	const normal = {x: -dy / chord, y: dx / chord};
	let maxOffset = 0;
	for (const p of realPath) {
		maxOffset = Math.max(maxOffset, Math.abs((p.x - from.x) * normal.x + (p.y - from.y) * normal.y));
	}
	const deviationScale = Math.min(MAX_DEVIATION_SCALE, Math.max(MIN_DEVIATION_SCALE, maxOffset / chord * 2));
	const tangentLength = chord * TANGENT_LENGTH_RATIO * deviationScale;
	const c1 = {x: from.x + u.x * tangentLength, y: from.y + u.y * tangentLength};
	const c2 = {x: to.x + v.x * tangentLength, y: to.y + v.y * tangentLength};
	const bowed = Math.abs((c1.x - from.x) * normal.x + (c1.y - from.y) * normal.y)
		+ Math.abs((c2.x - to.x) * normal.x + (c2.y - to.y) * normal.y);
	return {
		kind: bowed < MIN_SAGITTA_WORLD ? "straightened" : "curve",
		chord: Math.round(chord * 10) / 10,
		points: realPath.length,
		hasTangents: !!tangents,
		why: tangentResult.why,
		debug: tangentResult.debug,
		tangentLength: Math.round(tangentLength * 10) / 10,
		maxOffset: Math.round(maxOffset * 10) / 10,
		deviationScale: Math.round(deviationScale * 100) / 100,
		bowed: Math.round(bowed * 100) / 100,
	};
}

const file = process.argv[2];
const data = JSON.parse(readFileSync(file, "utf8"));
const rails = data.data.rails;

const buckets = {};
const diagonals = [];
for (const rail of rails) {
	const r = analyse(rail);
	buckets[r.kind] = (buckets[r.kind] ?? 0) + 1;
	if (!isAxisAligned(rail.x1, rail.z1, rail.x2, rail.z2)) diagonals.push({rail, r});
}
console.log("轨总数 =", rails.length);
console.log("分类：", JSON.stringify(buckets));
console.log("");
console.log("斜向轨（按规则应画曲线）= " + diagonals.length + " 条，其中：");
const curved = diagonals.filter(d => d.r.kind === "curve");
const straightened = diagonals.filter(d => d.r.kind === "straightened");
console.log("  画成曲线 " + curved.length + " 条");
console.log("  退化成直线 " + straightened.length + " 条");
if (straightened.length > 0) {
	console.log("");
	console.log("退化成直线的那些（看 bowed 为什么小于 " + MIN_SAGITTA_WORLD + "）：");
	straightened.slice(0, 12).forEach(({rail, r}) => {
		console.log(`  (${rail.x1},${rail.z1})→(${rail.x2},${rail.z2})  弦长 ${r.chord}  切线可用 ${r.hasTangents}` +
			`  原因 ${r.why ?? "-"}  起点切向量 ${JSON.stringify(r.debug?.startVector)} (长 ${r.debug?.startLength})` +
			`  终点切向量 ${JSON.stringify(r.debug?.endVector)} (长 ${r.debug?.endLength})  bowed ${r.bowed}`);
	});
}
console.log("");
console.log("画成曲线的那些（前 12 条）：");
curved.slice(0, 12).forEach(({rail, r}) => {
	console.log(`  (${rail.x1},${rail.z1})→(${rail.x2},${rail.z2})  弦长 ${r.chord}  切线长 ${r.tangentLength}  真实偏离 ${r.maxOffset}  bowed ${r.bowed}`);
});
const ratios = curved.map(d => d.r.bowed / d.r.chord);
if (ratios.length > 0) {
	console.log("");
	console.log("曲线的 bowed/弦长 比例：" + (Math.min(...ratios) * 100).toFixed(1) + "% .. " + (Math.max(...ratios) * 100).toFixed(1) + "%");
}


