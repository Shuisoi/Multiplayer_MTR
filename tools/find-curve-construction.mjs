// 找出正确的曲线构造：用"同参数位置垂距吻合"评分，逐一试各种切向方案。
//
// 为什么重写：前一版评分拿"渲染曲线中点偏移"和"真实轨道最大偏移"比，两者不是同一位置，
// 于是所有方案的得分都在 21~24 条之间乱跳 —— 那不是判据差异，是噪声。
// 现在两边都在**同一个弧长参数**上取点（1/4、1/2、3/4），比较垂距，并且逐条判断
// "渲染曲线是否比直线更接近真实轨道"（这是"方向正确"的真正含义）。
//
// 用法：node tools/find-curve-construction.mjs <topology.json>

import {readFileSync} from "node:fs";

const data = JSON.parse(readFileSync(process.argv[2], "utf8"));
const rails = data.data.rails;

const TANGENT_SAMPLE_RATIO = 0.15;
const TANGENT_MIN_DISTANCE = 2;
const TANGENT_LENGTH_RATIO = 0.533;

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

/** 沿采样链按弧长比例取点（线性插值）。 */
function sampleChain(P, fraction) {
	let total = 0;
	const segs = [];
	for (let i = 1; i < P.length; i++) {
		const l = Math.hypot(P[i].x - P[i-1].x, P[i].y - P[i-1].y);
		segs.push(l);
		total += l;
	}
	let want = total * fraction, acc = 0;
	for (let i = 1; i < P.length; i++) {
		if (acc + segs[i-1] >= want) {
			const t = segs[i-1] > 1e-9 ? (want - acc) / segs[i-1] : 0;
			return {x: P[i-1].x + (P[i].x - P[i-1].x) * t, y: P[i-1].y + (P[i].y - P[i-1].y) * t};
		}
		acc += segs[i-1];
	}
	return P[P.length-1];
}

/** 三次贝塞尔取点。 */
function bezier(A, C1, C2, B, t) {
	const mt = 1 - t;
	return {
		x: mt*mt*mt*A.x + 3*mt*mt*t*C1.x + 3*mt*t*t*C2.x + t*t*t*B.x,
		y: mt*mt*mt*A.y + 3*mt*mt*t*C1.y + 3*mt*t*t*C2.y + t*t*t*B.y,
	};
}

/** 候选构造方案：给定两条切向后如何摆放控制点。 */
const constructions = {
	"不翻转": (u, v) => ({u, v}),
	"起点切向翻": (u, v) => ({u: {x:-u.x, y:-u.y}, v}),
	"终点切向翻": (u, v) => ({u, v: {x:-v.x, y:-v.y}}),
	"两条都翻": (u, v) => ({u: {x:-u.x, y:-u.y}, v: {x:-v.x, y:-v.y}}),
	"交换": (u, v) => ({u: v, v: u}),
	"交换+起点翻": (u, v) => ({u: {x:-v.x, y:-v.y}, v: u}),
	"交换+终点翻": (u, v) => ({u: v, v: {x:-u.x, y:-u.y}}),
	"交换+都翻": (u, v) => ({u: {x:-v.x, y:-v.y}, v: {x:-u.x, y:-u.y}}),
};

function evaluate(name, construction) {
	let curves = 0;
	let betterThanChord = 0;
	let worseThanChord = 0;
	let exact = 0;
	const examples = [];
	for (const rail of rails) {
		if (isAxisAligned(rail)) continue;
		if (!rail.path || rail.path.length < 5) continue;
		const A = {x: rail.x1, y: -rail.z1};
		const B = {x: rail.x2, y: -rail.z2};
		const dx = B.x - A.x, dy = B.y - A.y;
		const chord = Math.hypot(dx, dy);
		if (chord < 8) continue;
		const P = rail.path.map(p => ({x: p[0], y: -p[2]}));

		let total = 0;
		for (let i = 1; i < P.length; i++) total += Math.hypot(P[i].x - P[i-1].x, P[i].y - P[i-1].y);
		const minDistance = Math.max(TANGENT_MIN_DISTANCE, total * TANGENT_SAMPLE_RATIO);
		const uRaw = walkDirection(P, minDistance);
		const vRaw = walkDirection([...P].reverse(), minDistance);
		if (!uRaw || !vRaw) continue;

		const {u, v} = construction(uRaw, vRaw);
		const normal = {x: -dy / chord, y: dx / chord};
		let maxOffset = 0;
		for (const p of P) maxOffset = Math.max(maxOffset, Math.abs((p.x - A.x) * normal.x + (p.y - A.y) * normal.y));
		const scale = Math.min(1.2, Math.max(0.5, maxOffset / chord * 2));
		const len = chord * TANGENT_LENGTH_RATIO * scale;
		const C1 = {x: A.x + u.x * len, y: A.y + u.y * len};
		const C2 = {x: B.x + v.x * len, y: B.y + v.y * len};
		curves++;

		/*
		 * 评分：在弧长参数 1/4、1/2、3/4 三处，比较"渲染曲线"与"真实轨道"相对弦的带符号垂距。
		 * 判据用**符号 + 大小**：符号一致说明弯向对；再加上"渲染曲线比直线更接近真实轨道"的计数。
		 */
		let signOk = 0, signChecked = 0, errorSum = 0, chordErrorSum = 0;
		for (const f of [0.25, 0.5, 0.75]) {
			const real = sampleChain(P, f);
			const realOffset = (real.x - A.x) * normal.x + (real.y - A.y) * normal.y;
			const curve = bezier(A, C1, C2, B, f);
			const curveOffset = (curve.x - A.x) * normal.x + (curve.y - A.y) * normal.y;
			if (Math.abs(realOffset) > 0.3) {
				signChecked++;
				if (Math.sign(realOffset) === Math.sign(curveOffset)) signOk++;
			}
			errorSum += Math.abs(realOffset - curveOffset);
			chordErrorSum += Math.abs(realOffset);   // 直线（弦）在同一点的误差
		}
		if (errorSum <= chordErrorSum) betterThanChord++; else worseThanChord++;
		if (signChecked > 0 && signOk === signChecked) exact++;
		else if (examples.length < 3) examples.push(`(${rail.x1},${rail.z1})→(${rail.x2},${rail.z2}) 符号 ${signOk}/${signChecked}`);
	}
	return {name, curves, exact, betterThanChord, worseThanChord, examples};
}

const results = Object.entries(constructions).map(([name, fn]) => evaluate(name, fn));
results.sort((a, b) => b.exact - a.exact || b.betterThanChord - a.betterThanChord);
console.log("曲线总数约 40；「符号全对」越多越好，「比直线更接近真实轨道」越多越好：");
for (const r of results) {
	console.log(`  ${r.name.padEnd(12)} 曲线 ${r.curves}  符号全对 ${r.exact}  比直线更准 ${r.betterThanChord}  比直线更差 ${r.worseThanChord}`);
}
console.log("");
console.log("最好的方案里仍不吻合的例子：");
results[0].examples.forEach(e => console.log("  " + e));
