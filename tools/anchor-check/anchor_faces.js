#!/usr/bin/env node
/*
 * anchor_faces.js - 诊断一个车辆的 mmtr_* 锚点是不是"平面"，以及折面会让客户端画成什么样。
 *
 * 为什么需要它：打包器 pack_vehicle.js 的 buildAnchors() 把**一个** mmtr_* 组压成
 * **一个**锚点 —— 中心取组内所有顶点的平均，法线/up/right 只取面积最大的那个面，而
 * 宽高是**全部顶点**在那个坐标系上的投影范围。只要组内不是单一平面，客户端
 * MmtrPanelQuad 画出来的就是一块"包住整个折面的大平板"，一半埋进壳体、一半悬空。
 * 这个脚本把这件事量化出来，不用进游戏就能判断"能不能显示"。
 *
 * 用法:
 *   node mmtr/tools/anchor-check/anchor_faces.js <file.obj> [more.obj ...]
 *   node mmtr/tools/anchor-check/anchor_faces.js --all
 *   node mmtr/tools/anchor-check/anchor_faces.js --json <file.obj>
 *
 * 判据（三个都是实测出来的，不是拍脑袋）：
 *   1. 折角 = 其余面法线与"基准面"（面积最大）法线的最大夹角。
 *      客户端只用一个法线，所以折角 > ~5° 就已经开始歪。
 *   2. 非共面度 = 组内任一点到基准面平面的最大垂距（格）。
 *      这是最直观的"折了多少"：0.002 以内可以当平面；超过 0.05 就会明显悬空/埋入。
 *   3. 膨胀率 = 打包器算出的 widthM/heightM ÷ 基准面自身的宽高。
 *      > 1.15 说明面板被折面的其它部分撑大了，图像会被拉伸。
 *
 * 已知静默失败模式：
 *   - 表面在最大面是水平面（法线接近 ±Y）时，客户端 facingSide() 的水平分量≈0，
 *     会判成"无法决定朝向"并**把面板正反各画一遍**。脚本会标 [两面] 。
 */

'use strict';

const fs = require('fs');
const path = require('path');

const FLAT_ANGLE_DEG = 5.0;      // 超过这个就当折面
const FLAT_DEVIATION_M = 0.002;  // 非共面度阈值（格）
const INFLATION_WARN = 1.15;     // 膨胀率告警线

function fail(message) {
  console.error('ERROR: ' + message);
  process.exit(1);
}

function parseObj(text) {
  const vpos = [];
  const groups = [];   // {name, faces:[[idx,...]]}
  let current = null;
  for (const rawLine of text.split('\n')) {
    const line = rawLine.trim();
    if (line.startsWith('v ')) {
      const p = line.split(/\s+/).slice(1, 4).map(Number);
      vpos.push([p[0], p[1], p[2]]);
    } else if (line.startsWith('o ') || line.startsWith('g ')) {
      // Blender 用 ".001" 后缀去重，打包器会剥掉，这里保持一致
      const name = line.slice(2).trim().replace(/\.\d+$/, '');
      current = /^mmtr_/i.test(name) ? { name, faces: [] } : null;
      if (current) groups.push(current);
    } else if (line.startsWith('f ') && current) {
      const idx = line.split(/\s+/).slice(1).map(ref => {
        const i = parseInt(ref.split('/')[0], 10);
        return i > 0 ? i - 1 : vpos.length + i;   // OBJ 允许负索引（从末尾数）
      });
      if (idx.length >= 3) current.faces.push(idx);
    }
  }
  return { vpos, groups };
}

const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cr = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const len = v => Math.hypot(v[0], v[1], v[2]);
const nz = v => { const l = len(v) || 1; return [v[0] / l, v[1] / l, v[2] / l]; };

/** Newell 法向 + 面积，和打包器一致（对平移不变）。 */
function faceNormalArea(points) {
  let nx = 0, ny = 0, nz2 = 0;
  for (let i = 0; i < points.length; i++) {
    const a = points[i], b = points[(i + 1) % points.length];
    nx += (a[1] - b[1]) * (a[2] + b[2]);
    ny += (a[2] - b[2]) * (a[0] + b[0]);
    nz2 += (a[0] - b[0]) * (a[1] + b[1]);
  }
  const area = Math.hypot(nx, ny, nz2) / 2;
  return { normal: area > 0 ? nz([nx, ny, nz2]) : [0, 0, 0], area };
}

function analyseGroup(group, vpos) {
  // 打包器：ids = 组内出现过的所有顶点，去重且保持顺序
  const ids = [];
  const seen = new Set();
  for (const f of group.faces) for (const i of f) if (!seen.has(i)) { seen.add(i); ids.push(i); }
  if (ids.length < 3) return null;

  const p = ids.map(i => vpos[i]);
  const centre = [0, 0, 0];
  for (const v of p) { centre[0] += v[0]; centre[1] += v[1]; centre[2] += v[2]; }
  centre[0] /= p.length; centre[1] /= p.length; centre[2] /= p.length;
  const rc = v => sub(v, centre);

  // 基准面 = 面积最大
  let ref = null, refArea = -1;
  const faceInfo = [];
  for (const f of group.faces) {
    const pts = f.map(i => rc(vpos[i]));
    const info = faceNormalArea(pts);
    faceInfo.push(info);
    if (info.area > refArea) { refArea = info.area; ref = { face: f, ...info, points: pts }; }
  }
  if (!ref) return null;

  // 打包器的 up/right（与组内所有顶点无关，只由基准面决定）
  const e1 = sub(rc(vpos[ref.face[1]]), rc(vpos[ref.face[0]]));
  const e2 = sub(rc(vpos[ref.face[2]]), rc(vpos[ref.face[1]]));
  const upRaw = Math.abs(e1[1]) >= Math.abs(e2[1]) ? e1 : e2;
  const n = ref.normal;
  const up = nz(sub(upRaw, n.map(x => x * dot(upRaw, n))));
  const right = nz(cr(up, n));

  // 组内所有顶点在基准坐标系上的投影范围（= 打包器写进 JSON 的 widthM/heightM）
  let wMax = -1e9, wMin = 1e9, hMax = -1e9, hMin = 1e9, maxDev = 0;
  for (const v of p) {
    const d = rc(v);
    const dr = dot(d, right), dh = dot(d, up);
    if (dr > wMax) wMax = dr;
    if (dr < wMin) wMin = dr;
    if (dh > hMax) hMax = dh;
    if (dh < hMin) hMin = dh;
    maxDev = Math.max(maxDev, Math.abs(dot(d, n)));
  }
  const widthM = wMax - wMin, heightM = hMax - hMin;

  // 基准面自身在同一个坐标系上的宽高 —— 用来看打包器撑大了多少
  let refWMax = -1e9, refWMin = 1e9, refHMax = -1e9, refHMin = 1e9;
  for (const v of ref.points) {
    const dr = dot(v, right), dh = dot(v, up);
    if (dr > refWMax) refWMax = dr;
    if (dr < refWMin) refWMin = dr;
    if (dh > refHMax) refHMax = dh;
    if (dh < refHMin) refHMin = dh;
  }
  const refWidth = refWMax - refWMin, refHeight = refHMax - refHMin;

  // 最大折角
  let maxAngle = 0;
  const distinct = [];
  for (const info of faceInfo) {
    if (info.area <= 0) continue;
    const angle = Math.acos(Math.max(-1, Math.min(1, dot(info.normal, n)))) * 180 / Math.PI;
    maxAngle = Math.max(maxAngle, angle);
    if (!distinct.some(d => Math.acos(Math.max(-1, Math.min(1, dot(d, info.normal)))) * 180 / Math.PI < 1)) distinct.push(info.normal);
  }

  // 客户端 facingSide()：水平分量≈0 判不出来 → 正反各画一遍
  const modelPosition = [centre[0], -centre[1], -centre[2]];
  const modelNormal = [n[0], -n[1], -n[2]];
  const horizontal = modelNormal[0] * -modelPosition[0] + modelNormal[2] * -modelPosition[2];
  const twoSided = Math.abs(horizontal) < 1.0E-4;

  return {
    name: group.name, faceCount: group.faces.length, vertexCount: ids.length,
    distinctNormals: distinct.length, maxAngleDeg: maxAngle, maxDeviationM: maxDev,
    widthM, heightM, refWidth, refHeight, twoSided,
    refNormal: n, refUp: up, refRight: right, centre,
    refArea,
    inflationW: refWidth > 1e-6 ? widthM / refWidth : Infinity,
    inflationH: refHeight > 1e-6 ? heightM / refHeight : Infinity
  };
}

function formatReport(file, result) {
  const lines = [];
  lines.push('=== ' + file + ' ===');
  const panels = result.anchors.filter(a => /^mmtr_hud/i.test(a.name));
  const others = result.anchors.filter(a => !/^mmtr_hud/i.test(a.name));

  if (panels.length === 0) lines.push('  (没有 mmtr_hud* 仪表锚点)');

  for (const a of panels) {
    const folded = a.maxAngleDeg > FLAT_ANGLE_DEG || a.maxDeviationM > FLAT_DEVIATION_M;
    const tags = [];
    if (folded) tags.push('折面');
    if (a.inflationW > INFLATION_WARN || a.inflationH > INFLATION_WARN) tags.push('膨胀');
    if (a.twoSided) tags.push('两面');
    lines.push('  ' + a.name + (tags.length ? '  [' + tags.join(' ') + ']' : '  [平面]'));
    lines.push('    面=' + a.faceCount + ' 顶点=' + a.vertexCount + ' 不同法线=' + a.distinctNormals +
      ' 最大折角=' + a.maxAngleDeg.toFixed(2) + '° 非共面度=' + a.maxDeviationM.toFixed(4) + '格');
    lines.push('    打包器面板: ' + a.widthM.toFixed(3) + ' × ' + a.heightM.toFixed(3) + ' 格' +
      '   基准面自身: ' + a.refWidth.toFixed(3) + ' × ' + a.refHeight.toFixed(3) +
      '   膨胀 ' + a.inflationW.toFixed(2) + '× / ' + a.inflationH.toFixed(2) + '×');
    lines.push('    基准面法线=(' + a.refNormal.map(x => x.toFixed(4)).join(', ') + ')' +
      ' 中心=(' + a.centre.map(x => x.toFixed(4)).join(', ') + ')' +
      (a.twoSided ? '   ← facingSide 判不出朝向，客户端会正反各画一遍' : ''));
    if (folded) {
      lines.push('    → 单块平板无法贴合：客户端会在 ' + a.centre.map(x => x.toFixed(3)).join(', ') +
        ' 处画一块 ' + a.widthM.toFixed(2) + '×' + a.heightM.toFixed(2) + ' 的平板，' +
        '折面处最大偏差约 ' + (a.maxDeviationM + 0.03).toFixed(3) + ' 格（含 0.03 抬升）');
    }
  }

  for (const a of others) {
    const folded = a.maxAngleDeg > FLAT_ANGLE_DEG || a.maxDeviationM > FLAT_DEVIATION_M;
    lines.push('  ' + a.name + '  [kind=' + a.name.replace(/^mmtr_/, '').replace(/_\d+.*$/, '') + ']' +
      ' 面=' + a.faceCount + ' 折角=' + a.maxAngleDeg.toFixed(1) + '° ' +
      a.widthM.toFixed(3) + '×' + a.heightM.toFixed(3) +
      (folded ? '  [折面/立体：' + (a.name.startsWith('mmtr_cabdoor') ? '门叶是立体件，打包器取最大面是对的' : '注意') + ']' : ''));
  }
  return lines.join('\n');
}

function main() {
  const args = process.argv.slice(2);
  const asJson = args.includes('--json');
  let files = args.filter(a => !a.startsWith('--'));

  if (args.includes('--all')) {
    const root = path.join(__dirname, '..', '..', '..', 'assets', 'models', 'blender');
    files = [];
    const walk = dir => {
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) walk(full);
        else if (entry.name.toLowerCase().endsWith('.obj')) files.push(full);
      }
    };
    if (fs.existsSync(root)) walk(root);
  }
  if (files.length === 0) fail('用法: node anchor_faces.js <file.obj ...> | --all | --json <file.obj>');

  let foldedCount = 0, panelCount = 0;
  const jsonOut = [];
  for (const file of files) {
    if (!fs.existsSync(file)) { console.error('SKIP (不存在): ' + file); continue; }
    const { vpos, groups } = parseObj(fs.readFileSync(file, 'utf8'));
    const anchors = [];
    for (const g of groups) {
      const a = analyseGroup(g, vpos);
      if (a) anchors.push(a);
    }
    const result = { file, anchors };
    if (asJson) { jsonOut.push(result); continue; }
    console.log(formatReport(file, result));
    console.log('');
    for (const a of anchors.filter(x => /^mmtr_hud/i.test(x.name))) {
      panelCount++;
      if (a.maxAngleDeg > FLAT_ANGLE_DEG || a.maxDeviationM > FLAT_DEVIATION_M) foldedCount++;
    }
  }
  if (asJson) { console.log(JSON.stringify(jsonOut, null, 2)); return; }
  console.log('汇总: ' + panelCount + ' 个 mmtr_hud* 锚点，其中 ' + foldedCount + ' 个是折面（折角>' +
    FLAT_ANGLE_DEG + '° 或非共面度>' + FLAT_DEVIATION_M + '格）');
}

main();
