// 单条轨的中间量对账：把 railGeometry 的算法逐行复算，打印每个向量到底指哪边。
//
// 背景：浏览器里报"曲线端点切线差 180°、弯向反"，且是系统性的（40/40、19/38），
// 说明某处把方向整体翻了一次。这种东西必须打印向量分量才能定位，看角度只会来回猜。
//
// 用法：node tools/debug-one-rail.mjs <topology.json> <x1> <z1> <x2> <z2>

import {readFileSync} from "node:fs";

const [file, x1, z1, x2, z2] = process.argv.slice(2);
const data = JSON.parse(readFileSync(file, "utf8"));
const rail = data.data.rails.find(r =>
	Number(r.x1) === Number(x1) && Number(r.z1) === Number(z1) && Number(r.x2) === Number(x2) && Number(r.z2) === Number(z2));
if (!rail) {
	console.log("没找到这条轨");
	process.exit(1);
}

const A = {x: rail.x1, y: -rail.z1};
const B = {x: rail.x2, y: -rail.z2};
const dx = B.x - A.x, dy = B.y - A.y;
const chord = Math.hypot(dx, dy);
const P = rail.path.map(p => ({x: p[0], y: -p[2]}));

console.log(`轨 (${rail.x1},${rail.z1})→(${rail.x2},${rail.z2})   平面 A=(${A.x},${A.y}) B=(${B.x},${B.y})  弦长 ${chord.toFixed(1)}`);
console.log(`采样链 首=(${P[0].x},${P[0].y})  末=(${P[P.length-1].x},${P[P.length-1].y})`);
console.log(`链首到 A = ${Math.hypot(P[0].x-A.x, P[0].y-A.y).toFixed(2)}   链末到 B = ${Math.hypot(P[P.length-1].x-B.x, P[P.length-1].y-B.y).toFixed(2)}`);
console.log(`链首到 B = ${Math.hypot(P[0].x-B.x, P[0].y-B.y).toFixed(2)}   链末到 A = ${Math.hypot(P[P.length-1].x-A.x, P[P.length-1].y-A.y).toFixed(2)}`);
console.log("");

// 与实现相同的切线提取（walk far）
const unit = v => { const l = Math.hypot(v.x, v.y); return l > 1e-9 ? {x: v.x/l, y: v.y/l} : null; };
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
let total = 0;
for (let i = 1; i < P.length; i++) total += Math.hypot(P[i].x - P[i-1].x, P[i].y - P[i-1].y);
const minDistance = Math.max(2, total * 0.15);
const uRaw = walkDirection(P, minDistance);
const vRaw = walkDirection([...P].reverse(), minDistance);
console.log(`链总长 ${total.toFixed(1)}  取方向的最小距离 ${minDistance.toFixed(1)}`);
console.log(`u（沿链前进）= (${uRaw.x.toFixed(3)}, ${uRaw.y.toFixed(3)})`);
console.log(`v（沿链反向）= (${vRaw.x.toFixed(3)}, ${vRaw.y.toFixed(3)})`);
console.log(`弦方向 d/|d| = (${(dx/chord).toFixed(3)}, ${(dy/chord).toFixed(3)})`);
console.log("");
console.log(`u · d = ${(uRaw.x*dx + uRaw.y*dy).toFixed(1)}  （>0 说明链方向与 A→B 同向）`);
console.log(`v · d = ${(vRaw.x*dx + vRaw.y*dy).toFixed(1)}  （<0 说明"沿链反向"确实指回 A 侧）`);
console.log(`(u − v) · d = ${((uRaw.x-vRaw.x)*dx + (uRaw.y-vRaw.y)*dy).toFixed(1)}  （实现里 <0 就交换，作为方向对齐判据）`);
console.log("");

// 实现的后续步骤
let u = uRaw, v = vRaw;
const swapped = (u.x - v.x) * dx + (u.y - v.y) * dy < 0;
if (swapped) { const t = u; u = v; v = t; }
console.log(`是否交换：${swapped}`);
const normal = {x: -dy/chord, y: dx/chord};
let maxOffset = 0;
for (const p of P) maxOffset = Math.max(maxOffset, Math.abs((p.x-A.x)*normal.x + (p.y-A.y)*normal.y));
const scale = Math.min(1.2, Math.max(0.5, maxOffset/chord*2));
const tangentLength = chord * 0.533 * scale;
const c1 = {x: A.x + u.x*tangentLength, y: A.y + u.y*tangentLength};
const c2 = {x: B.x + v.x*tangentLength, y: B.y + v.y*tangentLength};
console.log(`真实偏离 ${maxOffset.toFixed(2)}  缩放 ${scale.toFixed(2)}  切线长 ${tangentLength.toFixed(2)}`);
console.log(`控制点 C1（A + u·len）= (${c1.x.toFixed(1)}, ${c1.y.toFixed(1)})   相对 A 的方向 = (${(c1.x-A.x).toFixed(2)}, ${(c1.y-A.y).toFixed(2)})`);
console.log(`控制点 C2（B + v·len）= (${c2.x.toFixed(1)}, ${c2.y.toFixed(1)})   相对 B 的方向 = (${(c2.x-B.x).toFixed(2)}, ${(c2.y-B.y).toFixed(2)})`);
console.log("");

// 真实轨道应该往哪边鼓？用采样点相对弦的带符号偏移判断
const signed = P.map(p => (p.x-A.x)*normal.x + (p.y-A.y)*normal.y);
const maxAbs = Math.max(...signed.map(Math.abs));
const signAtMax = signed[signed.findIndex(s => Math.abs(s) === maxAbs)];
console.log(`真实轨道相对弦的带符号偏移：最大 ${mathRound(signAtMax)} （法线方向 (${normal.x.toFixed(3)}, ${normal.y.toFixed(3)}) ）`);
// 渲染曲线（三次贝塞尔）中点相对弦的带符号偏移
const mid = {x: (A.x + 3*c1.x + 3*c2.x + B.x) / 8, y: (A.y + 3*c1.y + 3*c2.y + B.y) / 8};
const renderedSigned = (mid.x-A.x)*normal.x + (mid.y-A.y)*normal.y;
console.log(`渲染曲线中点相对弦的带符号偏移：${mathRound(renderedSigned)}`);
console.log(`→ ${Math.sign(signAtMax) === Math.sign(renderedSigned) ? "同侧（弯向一致）" : "**异侧（弯向反了）**"}`);

function mathRound(v) { return (Math.round(v * 100) / 100).toString(); }
