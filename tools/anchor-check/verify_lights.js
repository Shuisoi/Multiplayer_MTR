#!/usr/bin/env node
'use strict';
/*
 * verify_lights.js - 车灯锚点（mmtr_light_<cab>_<n>）在打包前后是不是还说得通。
 *
 * 为什么需要它（三个失败都"打包成功、进游戏才发现"）：
 *
 *   L2 法线朝里。车灯锚点的面法线 = 光射出的方向。这个模型的 **hud / windshield 的法线是朝车内（朝司机）的**
 *      —— 实测 SAF420：hud_1 法线 [0,0.5,0.866]、windshield_1_1 法线 [0,0,1]（都是 +Z，指向车尾）。
 *      灯要是照抄这个绕序，光就射进车厢、车头前面一点不亮。判据不能写死 "-Z"，必须用"背离车心"。
 *
 *   L4 A/B 端串号。头尾两节车是**同一个 OBJ 打两遍**（B 端 rotationDegY 180 + 一张 groupRename 把
 *      `_1` 改写成 `_2`）。漏了灯的通配符条目，B 车就会带着"自称驾驶室1、却长在车体 +Z 端"的灯锚点：
 *      几何没错、光也照出去了，只有驾驶室编号是错的 —— 而这种错**不会让任何东西报错**。
 *
 *   L1 漏灯 / 多灯。模型里加了第 3 盏灯但没重打包装（或反过来，打包了但 properties 里没这个组），
 *      锚点数量与源几何对不上。
 *
 * 用法（配置即权威：源 OBJ、旋转、recenter、groupRename 全部从它读，与打包器同源）：
 *   node mmtr/tools/anchor-check/verify_lights.js <carConfig.json>
 *   node mmtr/tools/anchor-check/verify_lights.js <carConfig.json> --anchors <mmtr_anchors_x.json>
 *   node mmtr/tools/anchor-check/verify_lights.js --obj <src.obj> --anchors <x.json> [--rotation 180] [--recenter]
 *
 * 退出码：0 = 全过（或该模型没有灯锚点），1 = 有 FAIL。
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

// ---- 源 OBJ 里的灯 ----
const obj = L.parseObj(sourceObj);
const source = [];
for (const group of L.anchorsOf(obj)) {
  const mapped = renamed(group.name).replace(/^mmtr_/i, '');
  const m = /^light(?:_(\d+))?(?:_(\d+))?$/.exec(mapped);
  if (!m) continue;
  const cab = m[1] ? +m[1] : 1;
  const index = m[2] ? +m[2] : 1;
  const pts = [];
  for (const face of group.faces) for (const i of face) pts.push(obj.vpos[i]);
  if (!pts.length) continue;
  source.push({ raw: group.name, mapped, cab, index, pts, faces: group.faces });
}

const packed = JSON.parse(fs.readFileSync(anchorPath, 'utf8'));
const packedLights = (packed.anchors || []).filter(a => a.kind === 'light');

if (!source.length) {
  console.log(`[verify_lights] ${path.basename(sourceObj)}：源 OBJ 里没有 mmtr_light_*（该模型不点灯）`);
  console.log(`                 打包结果里 light 锚点 = ${packedLights.length}（应为 0）`);
  console.log(packedLights.length === 0 ? '[verify_lights] PASS' : '[verify_lights] FAIL 源里没有灯，包里却有');
  process.exit(packedLights.length === 0 ? 0 : 1);
}

const TOL_MM = 1.5;               // 位置/尺寸容差（毫米）
const OUTWARD_MIN = 0.2;          // 法线"背离车心"的下限（点积）

let fails = 0;
function pass(msg) { console.log('  [PASS] ' + msg); }
function fail(msg) { console.log('  [FAIL] ' + msg); fails++; }

console.log('='.repeat(90));
console.log(`车灯锚点自检: ${path.basename(sourceObj)}  rotation=${rotationDegY} recenter=${recenter !== false}`);
console.log(`  锚点文件  : ${anchorPath}`);
console.log(`  源里的灯  : ${source.map(s => `${s.raw} -> cab${s.cab} idx${s.index}`).join(', ')}`);
console.log(`  包里的灯  : ${packedLights.map(a => `${a.name}(cab${a.cab} idx${a.index === undefined ? 1 : a.index})`).join(', ') || '（无）'}`);
console.log('-'.repeat(90));

// 顶点变换（与打包器同源：先按 Y 旋转，再按旋转后的包围盒中心平移）—— 用 lib 的同一份实现
const allVerts = L.transformVertices(obj.vpos, { rotationDegY, recenter });

// ---- L1：一盏源灯 = 一个锚点（数量与编号都要对）----
const matched = new Set();
for (const s of source) {
  const hits = packedLights.filter(a => /^light(?:_(\d+))?(?:_(\d+))?$/.exec(a.name)
    && (+(/^light(?:_(\d+))?(?:_(\d+))?$/.exec(a.name)[1] || 1)) === s.cab
    && (+(/^light(?:_(\d+))?(?:_(\d+))?$/.exec(a.name)[2] || 1)) === s.index);
  if (hits.length === 1) {
    s.anchor = hits[0];
    matched.add(hits[0].name);
    pass(`L1 ${s.raw} -> 锚点 ${s.anchor.name}（cab${s.cab} idx${s.index}）`);
  } else {
    fail(`L1 ${s.raw} 期望 1 个 cab${s.cab} idx${s.index} 的锚点，实到 ${hits.length} 个 —— 检查 groupRename / mmtr_ anchor 前缀`);
  }
}
for (const a of packedLights) if (!matched.has(a.name)) fail(`L1 包里多出灯锚点 ${a.name}，源 OBJ 里没有对应四边形`);

// ---- L2/L3/L4：逐灯 ----
for (const s of source) {
  if (!s.anchor) continue;
  const a = s.anchor;

  // 源四边形的中心/尺寸（同一套变换），用于 L3
  const transformed = s.pts.map(p => L.transformVertices([p], { rotationDegY, recenter })[0]);
  const centre = L.average(transformed);
  const normal = L.faceNormalArea(transformed).normal;
  const driftMm = L.len(L.sub(centre, [a.x, a.y, a.z])) * 1000;
  if (driftMm <= TOL_MM) pass(`L3 ${a.name} 位置与源几何一致（偏差 ${driftMm.toFixed(2)} mm）`);
  else fail(`L3 ${a.name} 位置偏了 ${driftMm.toFixed(2)} mm（锚点 ${a.x},${a.y},${a.z} vs 源 ${centre.map(v => v.toFixed(3))}）`);

  const normalDot = L.dot([a.normal[0], a.normal[1], a.normal[2]], normal);
  if (normalDot > 0.99) pass(`L3 ${a.name} 法线与源绕序一致（点积 ${normalDot.toFixed(4)}）`);
  else fail(`L3 ${a.name} 法线与源绕序不一致（点积 ${normalDot.toFixed(4)}）—— flipAnchorNormalByGroup 用错了？`);

  // L2 朝外：法线必须背离车心（车心 = 原点，锚点是 recenter 之后的坐标）
  const outward = L.dot([a.normal[0], a.normal[1], a.normal[2]], L.norm([a.x, a.y, a.z]));
  if (outward >= OUTWARD_MIN) pass(`L2 ${a.name} 朝车外（法线·径向 = ${outward.toFixed(3)}）`);
  else fail(`L2 ${a.name} 没有朝车外（法线·径向 = ${outward.toFixed(3)} < ${OUTWARD_MIN}）—— 灯会把光射进车厢。注意 hud/windshield 的法线是朝司机的，别照抄那个绕序`);

  // L4 驾驶室编号必须与它实际所在的那一端一致。
  //
  // ⚠️ 符号方向**反直觉**，2026-09-28 用现役包实测校准过：
  //   锚点 JSON 里的 x/y/z 是**文件空间**，而客户端把锚点转成**骑乘空间**用 `(x,y,z) -> (-x,y,-z)`
  //   （MmtrVehicleAnchors.toRidingSpace），"这是哪一端"由 `engineEndOfSeat(seatZ)` 按**骑乘空间** z 判：
  //   骑乘 z > 0 = 引擎 B 端 = 驾驶室 2。
  //   于是换到文件空间就是：**cab 1 ⇒ 文件 z > 0**，cab 2 ⇒ 文件 z < 0。
  //   实测证据（现役 SAF420_v4.zip，游戏里朝向是对的）：saf420cab_a 的 hud_1/seat_1 在文件 z=+9.3/+8.2，
  //   saf420cab_b 的 hud_2/seat_2 在 z=-9.3/-8.2。
  //   第一版这里写成了 z<0 ⇒ 把我自己那份**镜像**的包判成 PASS（判据与夹具同错，等于自己给自己放行）。
  const expectedCab = a.z > 0 ? 1 : 2;
  const actualCab = a.cab <= 0 ? 1 : a.cab;
  if (actualCab === expectedCab) pass(`L4 ${a.name} 驾驶室编号与实际端一致（文件 z=${a.z.toFixed(3)} -> cab ${actualCab}）`);
  else fail(`L4 ${a.name} 自称 cab${actualCab} 却长在文件 z=${a.z.toFixed(3)}（应为 cab${expectedCab}）—— B/A 端的 rotationDegY 与 groupRename 配错了：编号对的组合是【A 端车 rotationDegY=180 且不改名】+【B 端车 rotationDegY=0 且 _1->_2 改名】`);
}

console.log('-'.repeat(90));
if (fails) {
  console.log(`[verify_lights] FAIL - ${fails} 项不通过`);
  process.exit(1);
}
console.log('[verify_lights] PASS');
