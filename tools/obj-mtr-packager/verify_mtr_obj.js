#!/usr/bin/env node
/*
 * verify_mtr_obj.js - "will MTR actually read the model the author made?"
 *
 * MTR 4.0.5 does NOT read an OBJ the way a normal viewer does. Disassembled from
 * org.mtr.mapping.render.obj.ObjModelLoader (which uses de.javagl.obj):
 *
 *   for (int i = 0; i < obj.getNumVertices(); i++) {
 *       normal = i < obj.getNumNormals()   ? obj.getNormal(i)   : ZERO3;
 *       uv     = i < obj.getNumTexCoords() ? obj.getTexCoord(i) : ZERO2;   // (0,0)
 *   }
 *   for (each face) new Face(new int[]{ f.getVertexIndex(0), f.getVertexIndex(1), f.getVertexIndex(2) });
 *
 * The i-th vertex is force-welded to the i-th uv and the i-th normal - the face's own `v/vt/vn` triple is
 * never read - and only the first three corners of a face are read. A stock Blender export violates both,
 * and the symptom is NOT an error: it is "part of the model is invisible" (or samples a nonsense texel),
 * because every vertex past #vt quietly becomes uv (0,0) and quads lose their fourth corner.
 *
 * Checks the PACKED obj against the SOURCE obj:
 *
 *   L1 LAYOUT  #v == #vt == #vn; every face is a triangle; every corner ref is `i/i/i`.
 *   L2 PAIRS   every (position, uv) pair MTR will read also exists in the source model. This is the
 *              assertion with teeth: it fails when an index space collapses (the BR101 bug) even though
 *              the file is a perfectly valid OBJ.
 *   L3 AREA    per group, the packed triangles' total area equals the source polygons' total area - catches
 *              a corner dropped by the triangle-only read, and a bad triangulation.
 *   L4 BOUNDS  per group, the packed bounding box equals the source's.
 *
 * Usage: node mmtr/tools/obj-mtr-packager/verify_mtr_obj.js --config <vehicle.json>
 */

'use strict';

const fs = require('fs');
const path = require('path');
const L = require('../anchor-check/lib.js');

const args = process.argv.slice(2);
const configIndex = args.indexOf('--config');
const configPath = configIndex >= 0 ? args[configIndex + 1] : null;
if (!configPath || !fs.existsSync(configPath)) {
  console.error('usage: node verify_mtr_obj.js --config <vehicle.json>');
  process.exit(2);
}
const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
const resolveRoot = p => p.replace(/\$\{MC_ROOT\}/g, path.resolve(__dirname, '..', '..', '..'));
const sourceObj = resolveRoot(config.sourceObj);
// stagingDir may also carry ${MC_ROOT} (every anchor-check verifier resolves it; this one only resolved
// sourceObj, so a config written with the placeholder read as a literal path and the packed OBJ was
// "not found" although it was right there).
const stage = (config.stagingDir ? resolveRoot(config.stagingDir) : null) || path.join(path.dirname(sourceObj), '.pack_stage_' + config.id);
const srcBase = path.basename(sourceObj).toLowerCase();
const packedObj = path.join(stage, 'assets', 'mtr', config.id, srcBase);
if (!fs.existsSync(packedObj)) { console.error('packed obj not found: ' + packedObj + '  (run the packager first)'); process.exit(2); }

const failures = [], notes = [];
const fail = (scope, msg) => failures.push(scope + ': ' + msg);

const readV = line => line.trim().split(/\s+/).slice(1).map(Number);
const key3 = p => p.slice(0, 3).map(x => Number(x).toFixed(5)).join(',');   // for messages only
const key2 = p => p.slice(0, 2).map(x => Number(x).toFixed(5)).join(',');

// Corners are matched with a TOLERANCE, not by string equality: the packager writes positions/uvs with
// toFixed(6), so a value sitting on a rounding boundary rounds the other way here and a perfectly correct
// vertex would look "missing" (measured: 9 of 8148 corners on saf101, all of them 1e-6 apart). A grid with
// a 1 mm cell plus a +/-1 cell lookup gives a real tolerance with no boundary artefacts. A genuine defect
// (the BR101 index collapse) pairs a position with a uv from a completely different part of the atlas -
// tenths of a uv unit away - so it is still caught.
const CELL = 1000;                                     // 1 mm
const cellOf = p => p.slice(0, 3).map(x => Math.floor(Number(x) * CELL));
const cellKey = c => c.join(',');
const near = (a, b, tol) => a.slice(0, b.length).every((x, i) => Math.abs(Number(x) - Number(b[i])) <= tol);
const neighbourKeys = c => {
  const out = [];
  for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) for (let dz = -1; dz <= 1; dz++) {
    out.push((c[0] + dx) + ',' + (c[1] + dy) + ',' + (c[2] + dz));
  }
  return out;
};

/** One pass over an OBJ: vertex pools, and per group the faces as corner refs. */
function parseObj(file) {
  const v = [], vt = [], vn = [];
  const groups = [];
  let group = null;
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    if (/^v\s/.test(line)) v.push(readV(line).slice(0, 3));
    else if (/^vt\s/.test(line)) vt.push(readV(line).slice(0, 2));
    else if (/^vn\s/.test(line)) vn.push(readV(line).slice(0, 3));
    else if (/^(o|g)\s/.test(line)) { group = { name: line.slice(2).trim().replace(/\.\d+$/, ''), faces: [] }; groups.push(group); }
    else if (/^f\s/.test(line) && group) {
      group.faces.push(line.trim().split(/\s+/).slice(1).map(r => {
        const p = r.split('/');
        return { v: parseInt(p[0], 10), vt: p[1] ? parseInt(p[1], 10) : 0, vn: p[2] ? parseInt(p[2], 10) : 0 };
      }));
    }
  }
  return { v, vt, vn, groups };
}

const src = parseObj(sourceObj);
const pk = parseObj(packedObj);
notes.push('source  ' + sourceObj + '  (v=' + src.v.length + ' vt=' + src.vt.length + ' vn=' + src.vn.length + ' groups=' + src.groups.length + ')');
notes.push('packed  ' + packedObj + '  (v=' + pk.v.length + ' vt=' + pk.vt.length + ' vn=' + pk.vn.length + ' groups=' + pk.groups.length + ')');

// The packager transforms vertices at parse time (rotate about Y, then recentre on the whole-file bbox);
// apply the identical transform to the source so the two vertex spaces are comparable.
const srcV = L.transformVertices(src.v, config);

// ---------------------------------------------------------------- L1 layout
{
  let badArity = 0, badRefs = 0, faces = 0;
  for (const g of pk.groups) {
    for (const f of g.faces) {
      faces++;
      if (f.length !== 3) badArity++;
      for (const c of f) if (!(c.v && c.vt && c.vn && c.v === c.vt && c.vt === c.vn)) badRefs++;
    }
  }
  if (pk.v.length !== pk.vt.length || pk.vt.length !== pk.vn.length) {
    fail('L1 layout', 'v/vt/vn counts differ (' + pk.v.length + '/' + pk.vt.length + '/' + pk.vn.length + ') - MTR welds them by index, so every vertex past #vt silently samples uv (0,0)');
  }
  if (badArity) fail('L1 layout', badArity + ' face(s) are not triangles - MTR reads only the first 3 corners');
  if (badRefs) fail('L1 layout', badRefs + ' corner ref(s) are not `i/i/i` - MTR ignores the face triple');
  notes.push('L1 layout: v=vt=vn=' + pk.v.length + ', ' + faces + ' triangles, all refs i/i/i: ' + (!badArity && !badRefs));
  if (pk.v.length !== pk.vt.length || pk.vt.length !== pk.vn.length || badArity || badRefs) {
    console.log('-- notes --'); notes.forEach(n => console.log('  ' + n));
    console.log('\nFAIL (' + failures.length + ')'); failures.forEach(f => console.log('  ! ' + f));
    process.exit(1);
  }
}

// ---------------------------------------------------------------- roles (what the packager will call each group)
//
// Two ways a packed group gets its name, and both have to be covered:
//   · a ROLE from groupMap (body/interior/door_l/...), which may merge several source groups;
//   · a NAME-CONVENTION part the packager keeps as its own part - `wiper_/wiperarm_/wiperrod_/door_/`
//     `cabdoor_` (the `mmtr_` prefix stripped), see the naming table in the workflow doc.
// A face corner with no `vt` in the source is recorded as "no uv": the packager has to invent one
// (MTR always samples a uv), so any uv on such a corner is accepted.
const groupMap = config.groupMap || {};
const groupRename = config.groupRename || {};
const roleOf = name => {
  let best = null, bestLen = -1;
  for (const role of Object.keys(groupMap)) {
    for (const pat of groupMap[role]) if (name.indexOf(pat) >= 0 && pat.length > bestLen) { best = role; bestLen = pat.length; }
  }
  return best;
};
const srcCorners = new Map();   // role/part name -> Map(cell -> [{pos, uv}])
const srcPolys = new Map();
const addPoly = (key, corners) => {
  if (!srcCorners.has(key)) { srcCorners.set(key, new Map()); srcPolys.set(key, []); }
  srcPolys.get(key).push(corners);
  for (const c of corners) {
    if (!c.v) continue;
    const cell = cellKey(cellOf(c.v));
    const bucket = srcCorners.get(key);
    if (!bucket.has(cell)) bucket.set(cell, []);
    bucket.get(cell).push({ pos: c.v, uv: c.vt || null });
  }
};
/** Is (pos, uv) something the author actually wrote? uv === null in the source means "no uv written",
 *  in which case any uv is acceptable (the packager has to invent one - MTR always samples a uv). */
const sourceHasPair = (bucket, pos, uv) => {
  for (const key of neighbourKeys(cellOf(pos))) {
    const list = bucket.get(key);
    if (!list) continue;
    for (const c of list) {
      if (!near(c.pos, pos, 1e-3)) continue;
      if (c.uv === null) return 'no-uv';
      if (near(c.uv, uv, 1e-3)) return 'exact';
    }
  }
  return null;
};
for (const g of src.groups) {
  const renamed = groupRename[g.name] || g.name;
  const role = roleOf(renamed);
  const polys = g.faces.map(f => f.map(c => ({ v: srcV[c.v - 1], raw: c.v, vt: c.vt ? src.vt[c.vt - 1] : null })));
  if (role === 'anchor') {
    // `mmtr_*` anchor geometry is data-only and stripped from the OBJ - EXCEPT mmtr_cabdoor_*, which is
    // ALSO a visible part named without the prefix (see the naming table in the workflow doc), so its
    // corners still have to be registered under the stripped name.
    if (/^mmtr_cabdoor_/i.test(renamed)) for (const p of polys) addPoly(renamed.replace(/^mmtr_/, ''), p);
    continue;
  }
  if (role) for (const p of polys) addPoly(role, p);
  // ...and under its own name too, for the parts the packager keeps by name convention. Guard against
  // the case where the stripped name IS the role name, or the same polygons get counted twice.
  const alias = renamed.replace(/^mmtr_/, '');
  if (alias !== role) for (const p of polys) addPoly(alias, p);
}

// ---------------------------------------------------------------- L2 pairs, L3 area, L4 bounds
const triArea = (a, b, c) => L.len(L.cross(L.sub(b, a), L.sub(c, a))) / 2;
let pairs = 0, noUv = 0;
for (const g of pk.groups) {
  if (g.name === 'floor' || g.name.startsWith('doorway')) continue;   // generated sheets, not from the source
  const wanted = srcCorners.get(g.name);
  if (!wanted) { fail('L2 pairs', 'packed group "' + g.name + '" has no source group mapped to that role'); continue; }
  let missing = 0, invented = 0;
  const examples = [];
  for (const f of g.faces) {
    for (const c of f) {
      pairs++;
      const v = pk.v[c.v - 1], uv = pk.vt[c.vt - 1];   // exactly what MTR will pair up
      const verdict = sourceHasPair(wanted, v, uv);
      if (verdict === 'exact') continue;
      if (verdict === 'no-uv') { invented++; continue; }   // source had no uv here; the packager supplied one
      missing++;
      if (examples.length < 3) examples.push('pos ' + key3(v) + ' uv ' + key2(uv));
    }
  }
  if (missing) {
    fail('L2 pairs', g.name + ': ' + missing + ' of ' + (g.faces.length * 3) +
      ' corners carry a (vertex, uv) pair the source model never wrote - MTR would sample the wrong texel. e.g. ' + examples.join(' ; '));
  }
  if (invented) notes.push('L2 pairs: ' + g.name + ': ' + invented + " corners had no uv in the source and got the configured untexturedUv");
  // area + bounds
  let packedArea = 0, srcArea = 0;
  const pb = { mn: [Infinity, Infinity, Infinity], mx: [-Infinity, -Infinity, -Infinity] };
  const sb = { mn: [Infinity, Infinity, Infinity], mx: [-Infinity, -Infinity, -Infinity] };
  for (const f of g.faces) {
    const p = f.map(c => pk.v[c.v - 1]);
    packedArea += triArea(p[0], p[1], p[2]);
    for (const q of p) for (let k = 0; k < 3; k++) { if (q[k] < pb.mn[k]) pb.mn[k] = q[k]; if (q[k] > pb.mx[k]) pb.mx[k] = q[k]; }
  }
  for (const poly of (srcPolys.get(g.name) || [])) {
    const p = poly.map(c => c.v);
    if (p.some(q => !q)) {
      const bad = poly.findIndex(c => !c.v);
      fail('L3 area', g.name + ': source face references vertex #' + (poly[bad] && poly[bad].raw) + ' which is out of range 1..' + src.v.length);
      continue;
    }
    for (let i = 1; i < p.length - 1; i++) srcArea += triArea(p[0], p[i], p[i + 1]);
    for (const q of p) for (let k = 0; k < 3; k++) { if (q[k] < sb.mn[k]) sb.mn[k] = q[k]; if (q[k] > sb.mx[k]) sb.mx[k] = q[k]; }
  }
  const areaErr = Math.abs(packedArea - srcArea) / Math.max(1e-9, srcArea);
  if (areaErr > 0.005) {
    fail('L3 area', g.name + ': packed ' + packedArea.toFixed(4) + ' vs source ' + srcArea.toFixed(4) +
      ' (' + (areaErr * 100).toFixed(2) + '% off) - a corner was dropped or the triangulation is wrong');
  }
  if (![0, 1, 2].every(k => Math.abs(pb.mn[k] - sb.mn[k]) < 2e-3 && Math.abs(pb.mx[k] - sb.mx[k]) < 2e-3)) {
    fail('L4 bounds', g.name + ': packed bounds ' + key3(pb.mn) + '..' + key3(pb.mx) +
      ' != source ' + key3(sb.mn) + '..' + key3(sb.mx));
  }
  if (g.name === 'body') {
    notes.push('L3/L4 ' + g.name + ': area ' + packedArea.toFixed(3) + ' m^2 (source ' + srcArea.toFixed(3) + '), ' +
      g.faces.length + ' triangles');
  }
}
notes.push('L2 pairs: ' + pairs + ' packed corners cross-checked against the source model');
void noUv;

console.log('-- notes --');
notes.forEach(n => console.log('  ' + n));
console.log('');
if (failures.length) {
  console.log('FAIL (' + failures.length + ')');
  failures.forEach(f => console.log('  ! ' + f));
  process.exit(1);
}
console.log('PASS: the packed OBJ is in the layout MTR reads, and what MTR reads is what the model says');

