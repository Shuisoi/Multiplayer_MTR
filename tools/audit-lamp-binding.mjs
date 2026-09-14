/*
 * 离线的"灯 → 轨"绑定体检（只读，不碰世界）。
 *
 * <h3>为什么要有它</h3>
 * 一盏灯显示不对，可能只是这一盏放错了；也可能是**规则**把一批灯都绑错了。
 * 这两种情况的处理完全不同，而在线一条条查 `signal why` 太慢。所以这里用世界当前的
 * 拓扑与信号登记表，把引擎里那条绑定规则**照抄一遍**（投影、朝向点积、打分、兜底），
 * 然后统计：
 *   · 有多少灯的绑定"前方余长"几乎为零 —— 这种灯的区间只有几米，永远读不到前方占用，只能是绿；
 *   · 有多少灯离它绑定的轨很远 —— 说明绑定靠的是兜底路径，而不是"它旁边的那条轨"。
 *
 * <h3>它不做什么</h3>
 * 不模仿圆形弧几何：轨的朝向只在两端附近与弦向有明显差别，用于**分段统计**足够；
 * 真正的判定永远以引擎的 `signal why` 为准，这里只用来决定"值不值得去改规则"。
 *
 * 用法：node tools/audit-lamp-binding.mjs
 */

const BASE = "http://127.0.0.1:8888/mtr/api/map/";

const headingOf = angle => {
	const rad = (angle * Math.PI) / 180;
	return [-Math.cos(rad), -Math.sin(rad)];
};

const projectArc = (rail, px, pz) => {
	// 按轨的折线采样投影（拓扑里每条轨带 33 个采样点）
	const points = rail.path && rail.path.length >= 2 ? rail.path : [[rail.x1, rail.y1, rail.z1], [rail.x2, rail.y2, rail.z2]];
	let best = { distanceSq: Infinity, arc: 0 };
	let travelled = 0;
	for (let i = 0; i < points.length - 1; i++) {
		const [ax, , az] = points[i];
		const [bx, , bz] = points[i + 1];
		const dx = bx - ax;
		const dz = bz - az;
		const lengthSq = dx * dx + dz * dz;
		if (lengthSq < 1e-9) {
			continue;
		}
		const t = Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / lengthSq));
		const cx = ax + t * dx;
		const cz = az + t * dz;
		const dSq = (px - cx) ** 2 + (pz - cz) ** 2;
		if (dSq < best.distanceSq) {
			best = { distanceSq: dSq, arc: travelled + t * Math.sqrt(lengthSq) };
		}
		travelled += Math.sqrt(lengthSq);
	}
	return best;
};

const main = async () => {
	const topology = (await (await fetch(`${BASE}mmtr-topology`)).json()).data;
	const signals = (await (await fetch(`${BASE}mmtr-signals`)).json()).data.signals;

	// 轨的坐标空间：topology 的 rails 用 (x1,z1,x2,z2) 世界格
	const rails = topology.rails.map(rail => {
		const length = rail.path && rail.path.length >= 2
			? rail.path.reduce((sum, point, index) => index === 0 ? 0 : sum + Math.hypot(point[0] - rail.path[index - 1][0], point[2] - rail.path[index - 1][2]), 0)
			: Math.hypot(rail.x2 - rail.x1, rail.z2 - rail.z1);
		return {rail, length};
	});

	const report = [];
	const stats = {total: signals.length, unbound: 0, degenerate: 0, farOff: 0, healthy: 0};

	for (const signal of signals) {
		const [hx, hz] = headingOf(signal.angle);
		const lampX = signal.x + 0.5;
		const lampZ = signal.z + 0.5;

		let best = null;
		for (const entry of rails) {
			const projection = projectArc(entry.rail, lampX, lampZ);
			if (projection.distanceSq > 36) {
				// 只看离得近的候选：引擎虽然有兜底，但"旁边那条轨"不会在 6 格外
				continue;
			}
			const aheadM = entry.length - projection.arc;
			// 该点附近的轨向：用投影点前后 2 米的两点连线近似
			const [ax, , az] = entry.rail.path[0];
			const [bx, , bz] = entry.rail.path[entry.rail.path.length - 1];
			const chord = Math.hypot(bx - ax, bz - az) || 1;
			const dot = ((bx - ax) / chord) * hx + ((bz - az) / chord) * hz;
			const score = (1 - dot) * 1000 + projection.distanceSq;
			if (!best || score < best.score) {
				best = {score, dot, aheadM, distance: Math.sqrt(projection.distanceSq), length: entry.length, rail: entry.rail};
			}
		}

		if (!best) {
			stats.unbound++;
			report.push({key: signal.key, angle: signal.angle, aspect: signal.aspect, verdict: "找不到候选轨（6 格内）"});
			continue;
		}
		const verdict = best.aheadM < 4
			? `绑到了轨的末端：前方只剩 ${best.aheadM.toFixed(1)} m`
			: best.distance > 6
				? `离绑定的轨 ${best.distance.toFixed(1)} 格`
				: "正常";
		if (best.aheadM < 4) {
			stats.degenerate++;
		} else if (best.distance > 6) {
			stats.farOff++;
		} else {
			stats.healthy++;
		}
		if (verdict !== "正常") {
			report.push({
				key: signal.key, angle: signal.angle, aspect: signal.aspect, verdict,
				chordDot: Number(best.dot.toFixed(3)), distance: Number(best.distance.toFixed(2)), aheadM: Number(best.aheadM.toFixed(1)),
			});
		}
	}

	console.log(`灯 ${stats.total} 盏：正常 ${stats.healthy}，绑到末端（前方余长 <4 m）${stats.degenerate}，离轨过远 ${stats.farOff}，无候选 ${stats.unbound}`);
	console.log("");
	if (report.length === 0) {
		console.log("没有可疑的绑定。");
		return;
	}
	console.log("可疑绑定：");
	for (const row of report) {
		console.log(`  ${row.key}  角=${row.angle}  状态=${row.aspect}  ${row.verdict}` + (row.chordDot === undefined ? "" : `  弦向点积=${row.chordDot}`));
	}
};

await main();
