#!/usr/bin/env node
'use strict';
/*
 * verify_pid.js - 水牌 / 下一站牌锚点（mmtr_pid_<cab>[_<n>] / mmtr_next_<cab>[_<n>]）在打包前后是不是还说得通。
 *
 * 两块牌看着一样（都是"一个四边形 + 一段文字"），但**读牌人在哪一侧**完全不同，判据也就不同：
 *
 *   水牌 pid    挂在车体外面（车头/车尾），读牌人是**站台上的旅客** ⇒ 法线必须朝车外（背离车心）。
 *   下一站牌 next 挂在车里面（车厢/墙板/顶棚），读牌人是**车里的乘客** ⇒ 法线必须朝车内（指向车心）。
 *
 * 判据用"法线 × 从车心指向牌面的径向"这个点积的正负 —— 与 verify_lights.js 的 L2 同一条。
 * **不要**改成"沿法线探一步看进不进车体包围盒"：第一版就是这么写的，实测被车头清障铲/车钩打败 ——
 * 它们在低处把包围盒的 z 拉到 9.90，而水牌在车顶 y=3.41、牌面 z=9.75 ⇒ 沿法线往车外走一步
 * 仍然"在包围盒里"，一块明明朝外的牌被判成朝里（假红）。径向点积不受这种"别处伸出来的几何"影响。
 *
 * 各条判据：
 *   P1 漏牌/多牌：一块源牌 = 一个锚点（kind/cab/index 三项都要对上）。
 *   P2 位置：锚点中心与源几何中心一致（容差 1.5 mm）。
 *   P3 绕序：锚点法线与源四边形绕序一致（flipAnchorNormalByGroup 用错的典型症状）。
 *   P4 朝向：pid 背离车心（≥ +0.2）、next 指向车心（≤ -0.2）；两侧都判不出来时才 SKIP。
 *   P5 文字方向：pid 的 up 必须立着（|up·Y| ≥ 0.5）；next 只报告不判 —— 顶棚长条屏的 up
 *            本来就是水平的，那不是错。
 *   P6 A/B 端不串号：牌自称的驾驶室必须和它该在的那一端对得上。
 *     · **单驾驶室车**（全车只有 cab1 的 hud/cabdoor/seat，现场 SAF420 控制车就是）：
 *       所有水牌都必须是那一个 cab —— 车侧中部那对牌也在同一节车上、显示同一份内容。
 *     · **两端都有驾驶室的车**（BR101）才用符号口径：文件 z>0 = cab 1（与 verify_lights.js 的 L4 同一条）。
 *     头尾两节车是同一个 OBJ 打两遍（B 端改 rotationDegY + groupRename 把 _1 改写成 _2），
 *     漏了水牌的通配符条目，B 车的牌就"自称驾驶室1、却长在车体另一端"。
 *     ★ 2026-10-02：旧写法无条件用 z 符号，把单驾驶室车**车尾那对侧牌**判成假红（见 P6 处注释）。
 *   P7 牌面尺寸：任一边短于 5 cm、或面积小于 0.005 m² 就是退化面（客户端会跳过它，牌是空的）。
 *
 * 用法（配置即权威：源 OBJ、旋转、recenter、groupRename 全部从它读，与打包器同源）：
 *   node mmtr/tools/anchor-check/verify_pid.js <carConfig.json>
 *   node mmtr/tools/anchor-check/verify_pid.js <carConfig.json> --anchors <mmtr_anchors_x.json>
 *   node mmtr/tools/anchor-check/verify_pid.js --obj <src.obj> --anchors <x.json> [--rotation 180] [--recenter]
 *
 * 退出码：0 = 全过（或该模型没有水牌锚点），1 = 有 FAIL。
 */

const fs = require('fs');
const path = require('path');
const L = require('./lib.js');

const args = process.argv.slice(2);
function flagOf(name) {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : null;
}
function has(name) {
  return args.indexOf(name) >= 0;
}

const MC_ROOT = process.env.MC_ROOT || path.resolve(__dirname, '..', '..', '..');
function resolveRoot(value) {
  return String(value || '').replace(/\$\{MC_ROOT\}/g, MC_ROOT);
}

// ---- 输入：一个 car config（走配置），或者显式的 --obj/--anchors ----
let config = null;
const positional = args.filter(a => !a.startsWith('--') && args[args.indexOf(a) - 1] !== '--obj' && args[args.indexOf(a) - 1] !== '--anchors');
if (!has('--obj') && positional.length) {
  config = JSON.parse(fs.readFileSync(positional[0], 'utf8'));
}

const sourceObj = resolveRoot(has('--obj') ? flagOf('--obj') : config && config.sourceObj);
if (!sourceObj || !fs.existsSync(sourceObj)) {
  console.error('找不到源 OBJ：' + sourceObj);
  process.exit(2);
}

const rotationDegY = has('--rotation') ? Number(flagOf('--rotation')) : (config ? (config.rotationDegY || 0) : 0);
const recenter = has('--recenter') ? true : (config ? config.recenter !== false : true);
const groupRename = (config && config.groupRename) || {};

let anchorPath = has('--anchors') ? resolveRoot(flagOf('--anchors')) : null;
if (!anchorPath && config) {
  const staging = resolveRoot(config.stagingDir);
  const candidates = [];
  if (staging) candidates.push(path.join(staging, 'assets', 'mtr', `mmtr_anchors_${config.id}.json`));
  candidates.push(path.join(path.dirname(sourceObj), `mmtr_anchors_${config.id}.json`));
  anchorPath = candidates.find(p => fs.existsSync(p)) || null;
}
if (!anchorPath || !fs.existsSync(anchorPath)) {
  console.error('找不到锚点 JSON（打包后才有）。用 --anchors 指定，或先跑 pack-vehicle.ps1。');
  process.exit(2);
}

// ---- groupRename：与 pack_vehicle.js 同一条规则（精确优先，其次通配符按"更具体的先匹配"）----
const wildcards = Object.keys(groupRename)
  .filter(k => k.indexOf('*') >= 0)
  .map(k => { const i = k.indexOf('*'); return { head: k.slice(0, i), tail: k.slice(i + 1), to: String(groupRename[k]) }; })
  .sort((a, b) => (b.head.length + b.tail.length) - (a.head.length + a.tail.length));
function renamed(name) {
  if (groupRename[name] !== undefined) return groupRename[name];
  for (const rule of wildcards) {
    if (name.length < rule.head.length + rule.tail.length) continue;
    if (!name.startsWith(rule.head) || !name.endsWith(rule.tail)) continue;
    return rule.to.replace('*', name.slice(rule.head.length, name.length - rule.tail.length));
  }
  return name;
}

// ---- 源 OBJ：牌 + 车体包围盒 ----
//
// ★ 角色判定必须在 **groupRename 之后**做（与打包器同序，pack_vehicle.js:197 renameGroup → roleOf）：
//   现场的 SAF420 就是这样 —— 模型里那块牌叫**可见组** `dest_board`，配置把它改名成 `mmtr_pid_1`；
//   只看"名字本来带 mmtr_ 的组"会得出"源里没有水牌"，而包里明明有 ⇒ 假红（第一版就踩了）。
const BOARD_RE = /^(pid|next)(?:_(\d+))?(?:_(\d+))?$/;
const obj = L.parseObj(sourceObj);
const source = [];
for (const group of obj.groups) {
  const pts = [];
  for (const face of group.faces) for (const i of face) pts.push(obj.vpos[i]);
  if (!pts.length) continue;
  const mapped = renamed(group.name);
  const stripped = mapped.replace(/^mmtr_/i, '');
  const m = /^mmtr_/i.test(mapped) ? BOARD_RE.exec(stripped) : null;
  if (m) {
    source.push({ raw: group.name, mapped: stripped, kind: m[1], cab: m[2] ? +m[2] : 1, index: m[3] ? +m[3] : 1, pts });
  }
}

const packed = JSON.parse(fs.readFileSync(anchorPath, 'utf8'));
const packedBoards = (packed.anchors || []).filter(a => a.kind === 'pid' || a.kind === 'next');

// 这节车**自己有**几个驾驶室？看 hud/seat/cabdoor/windshield 这类"端专属"锚点的 cab 值（水牌不算）。
//   · 现场 SAF420 控制车：全车只有 cab1 的锚点 ⇒ 它是【单驾驶室车】，
//     于是**所有**水牌都属于那一端（车侧中部那对牌也在同一节车上、显示同一份内容）。
//   · 真有两端驾驶室的车（BR101）才会同时出现 cab1 与 cab2 ⇒ 那时才用"文件 z>0 = cab1"这条符号口径。
const CAR_CABS = [...new Set((packed.anchors || [])
  .filter(x => x.kind !== 'pid' && x.kind !== 'next' && x.cab > 0)
  .map(x => x.cab))].sort((p, q) => p - q);

if (!source.length) {
  console.log(`[verify_pid] ${path.basename(sourceObj)}：源 OBJ 里没有 mmtr_pid_* / mmtr_next_*（该模型不挂水牌）`);
  console.log(`             打包结果里水牌类锚点 = ${packedBoards.length}（应为 0）`);
  console.log(packedBoards.length === 0 ? '[verify_pid] PASS' : '[verify_pid] FAIL 源里没有水牌，包里却有');
  process.exit(packedBoards.length === 0 ? 0 : 1);
}

const TOL_MM = 1.5;               // 位置容差（毫米）
const OUTWARD_MIN = 0.2;          // 朝向判据的下限（|法线·径向| 的点积）
const UP_VERTICAL_MIN = 0.5;      // 水牌 up 的竖直分量下限
const MIN_EDGE_M = 0.05;          // 牌任一边短于 5 cm 就是退化面
const MIN_AREA_M2 = 0.005;        // 牌面面积下限

let fails = 0;
function pass(msg) { console.log('  [PASS] ' + msg); }
function fail(msg) { console.log('  [FAIL] ' + msg); fails++; }

console.log('='.repeat(90));
console.log(`水牌锚点自检: ${path.basename(sourceObj)}  rotation=${rotationDegY} recenter=${recenter !== false}`);
console.log(`  锚点文件  : ${anchorPath}`);
console.log(`  源里的牌  : ${source.map(s => `${s.raw} -> ${s.kind} cab${s.cab} idx${s.index}`).join(', ')}`);
console.log(`  包里的牌  : ${packedBoards.map(a => `${a.name}(${a.kind} cab${a.cab} idx${a.index === undefined ? 1 : a.index})`).join(', ') || '（无）'}`);
console.log('-'.repeat(90));

// ---- P1：一块源牌 = 一个锚点（数量与编号都要对）----
const matched = new Set();
for (const s of source) {
  const hits = packedBoards.filter(a => BOARD_RE.exec(a.name)
    && BOARD_RE.exec(a.name)[1] === s.kind
    && (+(BOARD_RE.exec(a.name)[2] || 1)) === s.cab
    && (+(BOARD_RE.exec(a.name)[3] || 1)) === s.index);
  if (hits.length === 1) {
    s.anchor = hits[0];
    matched.add(hits[0].name);
    pass(`P1 ${s.raw} -> 锚点 ${s.anchor.name}（${s.kind} cab${s.cab} idx${s.index}）`);
  } else {
    fail(`P1 ${s.raw} 期望 1 个 ${s.kind} cab${s.cab} idx${s.index} 的锚点，实到 ${hits.length} 个 —— 检查 groupRename / mmtr_ anchor 前缀 / 打包器认不认这个 kind`);
  }
}
for (const a of packedBoards) if (!matched.has(a.name)) fail(`P1 包里多出水牌锚点 ${a.name}，源 OBJ 里没有对应四边形`);

// ---- P2..P7：逐块牌 ----
// ★ recenter 必须按【整车】包围盒算 —— `L.transformVertices` 居中的是"你传进去的那些点"的包围盒，
//   逐点调用（`transformVertices([p], …)`）等于把每个点自己减自己 = (0,0,0)：`recenter: true` 的
//   车（现场拖车 saf420car）P2/P3 会整片假红（实测：源中心读成 (0.000, 2.560, 0.000)）。
//   打包器是对整份 OBJ 顶点做一次旋转+居中，这里照同一条口径来。
let XFORM;
{
  const rad = (rotationDegY || 0) * Math.PI / 180;
  const cs = Math.cos(rad), sn = Math.sin(rad);
  const rot = p => [cs * p[0] + sn * p[2], p[1], -sn * p[0] + cs * p[2]];
  let offX = 0, offZ = 0;
  if (recenter !== false && obj.vpos.length) {
    const r = obj.vpos.map(rot);
    offX = (Math.min(...r.map(v => v[0])) + Math.max(...r.map(v => v[0]))) / 2;
    offZ = (Math.min(...r.map(v => v[2])) + Math.max(...r.map(v => v[2]))) / 2;
  }
  XFORM = p => { const q = rot(p); return [q[0] - offX, q[1], q[2] - offZ]; };
  console.log(`  源坐标变换: rotation=${rotationDegY} recenter=${recenter !== false}`
    + ` 整车居中量=(${offX.toFixed(4)}, ${offZ.toFixed(4)})`);
}
for (const s of source) {
  if (!s.anchor) continue;
  const a = s.anchor;
  const transformed = s.pts.map(XFORM);
  const centre = L.average(transformed);
  const normal = L.faceNormalArea(transformed).normal;

  const driftMm = L.len(L.sub(centre, [a.x, a.y, a.z])) * 1000;
  if (driftMm <= TOL_MM) pass(`P2 ${a.name} 位置与源几何一致（偏差 ${driftMm.toFixed(2)} mm）`);
  else fail(`P2 ${a.name} 位置偏了 ${driftMm.toFixed(2)} mm（锚点 ${a.x},${a.y},${a.z} vs 源 ${centre.map(v => v.toFixed(3))}）`);

  const normalDot = L.dot([a.normal[0], a.normal[1], a.normal[2]], normal);
  if (normalDot > 0.99) pass(`P3 ${a.name} 法线与源绕序一致（点积 ${normalDot.toFixed(4)}）`);
  else fail(`P3 ${a.name} 法线与源绕序不一致（点积 ${normalDot.toFixed(4)}）—— flipAnchorNormalByGroup 用错了？`);

  // P4 朝向：pid 背离车心（读牌人在车外）、next 指向车心（读牌人在车里）
  const anchorNormal = [a.normal[0], a.normal[1], a.normal[2]];
  const radial = L.dot(anchorNormal, L.norm([a.x, a.y, a.z]));
  if (a.kind === 'pid') {
    if (radial >= OUTWARD_MIN) {
      pass(`P4 ${a.name} 牌面朝车外（法线·径向 = ${radial.toFixed(3)}）—— 站台上看得到正面`);
    } else {
      fail(`P4 ${a.name} 牌面没有朝车外（法线·径向 = ${radial.toFixed(3)} < ${OUTWARD_MIN}）—— 站台上看到的会是牌背/黑板。注意 hud/windshield 的法线是朝司机的，别照抄那个绕序`);
    }
  } else {
    if (radial <= -OUTWARD_MIN) {
      pass(`P4 ${a.name} 牌面朝车内（法线·径向 = ${radial.toFixed(3)}）—— 下一站牌就该给车里的人看`);
    } else {
      fail(`P4 ${a.name} 牌面没有朝车内（法线·径向 = ${radial.toFixed(3)} > ${-OUTWARD_MIN}）—— next 是给车里乘客看的；要装到车外请改 kind=pid`);
    }
  }

  // P5 文字方向：水牌必须立着；下一站牌只报告（顶棚长条屏的 up 本来就是水平的）
  const upY = Math.abs(a.up[1]);
  if (a.kind === 'pid') {
    if (upY >= UP_VERTICAL_MIN) pass(`P5 ${a.name} 文字是立着的（|up·Y| = ${upY.toFixed(3)}）`);
    else fail(`P5 ${a.name} 文字的 up 几乎水平（|up·Y| = ${upY.toFixed(3)} < ${UP_VERTICAL_MIN}）—— 字会横着/倒着；把牌面在 Blender 里转正`);
  } else {
    console.log(`  [INFO] P5 ${a.name} 文字 up 的竖直分量 = ${upY.toFixed(3)}（下一站牌不判：顶棚屏天生可能是横的）`);
  }

  // P6 端号：这块牌该自称哪个驾驶室？
  //   ★ 2026-10-02 修正：旧写法**无条件**用"文件 z>0 = cab1"这条符号口径，于是单驾驶室车的
  //     **车侧中部水牌**（车尾那对，文件 z<0）被判成"自称 cab1 却长在另一端"——那是**假红**：
  //     这节车只有 cab1 一个驾驶室，中部那对牌也在同一节车上、显示同一份内容（notes/357 §7.1），
  //     它们就该是 cab1。判据改成：单车只有一个 cab ⇒ 所有牌都必须是那个 cab（这仍然抓得住
  //     "B 车的牌漏了 groupRename 通配符"：那时 hud/cabdoor 已改成 cab2，牌还自称 cab1）；
  //     两端都有驾驶室的车（BR101）才回落到 z 符号口径。
  const actualCab = a.cab <= 0 ? 1 : a.cab;
  const singleCab = CAR_CABS.length <= 1;
  const carCab = CAR_CABS[0] || 1;
  const expectedCab = singleCab ? carCab : (a.z > 0 ? 1 : 2);
  if (actualCab === expectedCab) {
    pass(singleCab
      ? `P6 ${a.name} 端号与该车唯一的驾驶室一致（文件 z=${a.z.toFixed(3)} -> cab${actualCab}；本车 cab 锚点只有 cab${carCab}）`
      : `P6 ${a.name} 驾驶室编号与实际端一致（文件 z=${a.z.toFixed(3)} -> cab${actualCab}）`);
  } else {
    fail(singleCab
      ? `P6 ${a.name} 自称 cab${actualCab}，但这节车只有 cab${carCab}（hud/cabdoor/seat 全是 cab${carCab}）—— 车侧中部的牌也在同一节车上、显示同一份内容；整批改名要靠 groupRename 的通配符条目 mmtr_pid_1_* -> mmtr_pid_2_*`
      : `P6 ${a.name} 自称 cab${actualCab} 却长在文件 z=${a.z.toFixed(3)}（应为 cab${expectedCab}）—— B/A 端的 rotationDegY 与 groupRename 配错了：水牌也要写通配符条目 mmtr_pid_1_* -> mmtr_pid_2_*`);
  }

  // P7 牌面尺寸：退化成一条缝就画不出字（客户端会直接跳过）
  const w = a.widthM === undefined ? 0 : a.widthM;
  const h = a.heightM === undefined ? 0 : a.heightM;
  if (w >= MIN_EDGE_M && h >= MIN_EDGE_M && w * h >= MIN_AREA_M2) {
    pass(`P7 ${a.name} 牌面尺寸 ${w.toFixed(3)} x ${h.toFixed(3)} m`);
  } else {
    fail(`P7 ${a.name} 牌面尺寸 ${w.toFixed(3)} x ${h.toFixed(3)} m 太小/退化（每边至少 ${MIN_EDGE_M} m、面积至少 ${MIN_AREA_M2} m²）—— 检查模型里的四边形有没有面积`);
  }
}

console.log('-'.repeat(90));
if (fails) {
  console.log(`[verify_pid] FAIL - ${fails} 项不通过`);
  process.exit(1);
}
console.log('[verify_pid] PASS');
