// 判据对比：用"弯向是否与真实一致"当评分，比较几种"采样路径朝向"判定与切线符号约定。
//
// 背景：曲线由"端点 + 两个端点切线"决定，而引擎给的采样路径方向不定、且整体相对声明端点有偏移，
// 所以必须先把路径朝向对齐。靠感觉选判据会一直反复（实测换了三种判据、失败数 19→15 来回跳），
// 所以这里把每种组合都跑一遍，用同一个评分挑出正确的那个。
//
// 用法：node tools/compare-orientation-rules.mjs <topology.json>

import {readFileSync} from "node:fs";

const data = JSON.parse(readFileSync(process.argv[2], "utf8"));
const rails = data.data.rails;

const TANGENT_SAMPLE_RATIO = 0.15;
const TANGENT_MIN_DISTANCE = 2;
const TANGENT_LENGTH_RATIO = 0.533;
const MIN_DEVIATION_SCALE = 0.5;
const MAX_DEVIATION_SCALE = 1.2;

const isAxisAligned = (r) => Math.abs(r.x1 - r.x2) < 0.05 || Math.abs(r.z1 - r.z2) < 0.05;
const unit = v => { const l = Math.hypot(v.x, v.y); return l > 1e-9 ? {x: v.x / l, y: v.y / l} : null; };

function walkDirection(points, minDistance) {
	const first = points[0];
	let travelled = 0;
	for (let i = 1; i < points.length; i++) {
		travelled += Math.hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y);
		if (travelled >= minDistance) {
			const d = unit({x: points[i].x - first.x, y: points[i].y - first.y});
			if (d) return d;
		}
	}
	return unit({x: points[points.length - 1].x - first.x, y: points[points.length - 1].y - first.y});
}

/** 各种"路径朝向"判据：返回 true 表示链首应当对应 A（反之需要反向）。 */
const orientationRules = {
	// 链首离 A 更近（用"链首到 A + 链尾到 B"与"链首到 B + 链尾到 A"比，抵消整体偏移）
	"总和距离": (P, A, B) => {
		const headToA = Math.hypot(P[0].x - A.x, P[0].y - A.y) + Math.hypot(P[P.length-1].x - B.x, P[P.length-1].y - B.y);
		const headToB = Math.hypot(P[0].x - B.x, P[0].y - B.y) + Math.hypot(P[P.length-1].x - A.x, P[P.length-1].y - A.y);
		return headToA <= headToB;
	},
	"仅链首": (P, A, B) => Math.hypot(P[0].x - A.x, P[0].y - A.y) <= Math.hypot(P[0].x - B.x, P[0].y - B.y),
	"链中点投影": (P, A, B) => {
		// 中点应当离 A 比离 B 稍远（弧长前半段走了一半以上）
		const mid = P[Math.floor(P.length / 2)];
		return Math.hypot(mid.x - A.x, mid.y - A.y) <= Math.hypot(mid.x - B.x, mid.y - B.y);
	},
};

/** 各种"切线符号约定"。 */
const signRules = {
	// 不做任何纠正（对照组）
	"不纠正": (u, v) => ({u, v}),
	// 两条切向都必须与弦同向（沿 A→B），否则各自翻过来
	"各自与弦同向": (u, v, d) => ({
		u: u.x * d.x + u.y * d.y < 0 ? {x: -u.x, y: -u.y} : u,
		v: v.x * d.x + v.y * d.y < 0 ? {x: -v.x, y: -v.y} : v,
	}),
	// 先按"两点积为负就交换"，再各自与弦对齐
	"交换后与弦同向": (u, v, d) => {
		let a = u, b = v;
		if (a.x * b.x + a.y * b.y < 0) { const t = a; a = b; b = t; }
		return {
			u: a.x * d.x + a.y * d.y < 0 ? {x: -a.x, y: -a.y} : a,
			v: b.x * d.x + b.y * d.y < 0 ? {x: -b.x, y: -b.y} : b,
		};
	},
	// 只把终点切向翻过来（对应"C2 = B − v"）
	"仅终点取反": (u, v) => ({u, v: {x: -v.x, y: -v.y}}),
};

function evaluate(ruleName, signName) {
	const orientation = orientationRules[ruleName];
	const sign = signRules[signName];
	let curves = 0;
	let bendMismatch = 0;
	const examples = [];
	for (const rail of rails) {
		if (isAxisAligned(rail)) continue;
		if (!rail.path || rail.path.length < 5) continue;
		const A = {x: rail.x1, y: -rail.z1};
		const B = {x: rail.x2, y: -rail.z2};
		const dx = B.x - A.x, dy = B.y - A.y;
		const chord = Math.hypot(dx, dy);
		if (!(chord > 1e-6)) continue;
		const raw = rail.path.map(p => ({x: p[0], y: -p[2]}));
		const headIsA = orientation(raw, A, B);
		const P = headIsA ? raw : [...raw].reverse();

		let total = 0;
		for (let i = 1; i < P.length; i++) total += Math.hypot(P[i].x - P[i-1].x, P[i].y - P[i-1].y);
		const minDistance = Math.max(TANGENT_MIN_DISTANCE, total * TANGENT_SAMPLE_RATIO);
		const uRaw = walkDirection(P, minDistance);          // 链首处的前进方向
		const vRaw = walkDirection([...P].reverse(), minDistance);  // 链尾处的前进方向（即流入 B）
		if (!uRaw || !vRaw) continue;

		const {u, v} = sign(uRaw, vRaw, {x: dx / chord, y: dy / chord});
		const normal = {x: -dy / chord, y: dx / chord};
		let maxOffset = 0;
		for (const p of P) maxOffset = Math.max(maxOffset, Math.abs((p.x - A.x) * normal.x + (p.y - A.y) * normal.y));
		const scale = Math.min(MAX_DEVIATION_SCALE, Math.max(MIN_DEVIATION_SCALE, maxOffset / chord * 2));
		const len = chord * TANGENT_LENGTH_RATIO * scale;
		const c1 = {x: A.x + u.x * len, y: A.y + u.y * len};
		const c2 = {x: B.x + v.x * len, y: B.y + v.y * len};
		const bowed = Math.abs((c1.x - A.x) * normal.x + (c1.y - A.y) * normal.y)
			+ Math.abs((c2.x - B.x) * normal.x + (c2.y - B.y) * normal.y);
		if (bowed < 0.2) continue;
		curves++;

		// 评分：渲染曲线中点相对弦的带符号偏移 vs 真实轨道采样点相对弦的最大带符号偏移
		const mid = {x: (A.x + 3*c1.x + 3*c2.x + B.x) / 8, y: (A.y + 3*c1.y + 3*c2.y + B.y) / 8};
		const rendered = (mid.x - A.x) * normal.x + (mid.y - A.y) * normal.y;
		let realMax = 0;
		for (const p of P) {
			const s = (p.x - A.x) * normal.x + (p.y - A.y) * normal.y;
			if (Math.abs(s) > Math.abs(realMax)) realMax = s;
		}
		if (Math.sign(rendered) !== Math.sign(realMax)) {
			bendMismatch++;
			if (examples.length < 3) examples.push(`(${rail.x1},${rail.z1})→(${rail.x2},${rail.z2}) 真实 ${Math.sign(realMax)} 渲染 ${Math.sign(rendered)}`);
		}
	}
	return {ruleName, signName, curves, bendMismatch, examples};
}

const results = [];
for (const ruleName of Object.keys(orientationRules)) {
	for (const signName of Object.keys(signRules)) {
		results.push(evaluate(ruleName, signName));
	}
}
results.sort((a, b) => a.bendMismatch - b.bendMismatch);
console.log("按「弯向不符」从少到多排序（曲线总数应当约 40）：");
for (const r of results) {
	console.log(`  朝向判据「${r.ruleName}」+ 符号「${r.signName}」 → 曲线 ${r.curves} 条，弯向不符 ${r.bendMismatch} 条`);
	if (r.bendMismatch > 0 && r.bendMismatch <= 3) r.examples.forEach(e => console.log("      " + e));
}
