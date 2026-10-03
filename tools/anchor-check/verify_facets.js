#!/usr/bin/env node
/*
 * verify_facets.js - checks the multi-facet HUD data a pack emits against the geometry it came from.
 *
 * The packager's facet layout is arithmetic (unfold along the crease, accumulate the offsets), and
 * "the pack succeeded" says nothing about whether it is right. This tool re-derives everything from
 * the SOURCE OBJ and the emitted anchor JSON and fails loudly on:
 *
 *   G1  facet count matches the number of faces in the mmtr_hud* object
 *   G2  every face vertex lies ON its facet's plane and INSIDE its facet's rectangle
 *   G3  the facet rectangle and the face have the same area (the panel covers the face, no more)
 *   G4  the facet normal matches the face winding
 *   G5  UV CONTINUITY: a vertex shared by two facets gets the same (u, v) from both - this is the
 *       whole point of the unfold, and the one thing a screenshot cannot measure
 *   G6  the facets TILE the canvas: u and v span exactly 0..1 and the fold-axis intervals are
 *       contiguous (no gap, no overlap)
 *   G7  image orientation: v = 0 lands on the panel's TOP edge, not its bottom
 *   G8  a single-face hud anchor emits NO facet data, so the legacy single-quad path is untouched
 *
 * Usage:
 *   node mmtr/tools/anchor-check/verify_facets.js --config <vehicle config.json>
 *   node mmtr/tools/anchor-check/verify_facets.js --config <c.json> --report
 *
 * The config is the same file pack_vehicle.js takes; it supplies sourceObj / rotationDegY /
 * recenter / stagingDir / id, so the verifier transforms vertices exactly the way the packager did.
 */

'use strict';

const fs = require('fs');
const path = require('path');
const L = require('./lib.js');

const PLANE_TOL = 1.0E-3;   // blocks: how far a face vertex may sit off its facet's plane
const RECT_TOL = 1.0E-3;    // blocks: how far outside the facet rectangle a face vertex may sit
// blocks: how close to an edge counts as ON it. Only has to absorb the rounding of the packed anchors
// (6 decimals) - see outOfQuad, where an exact corner otherwise reads as a large miss.
const QUAD_EPS = 1.0E-6;
const AREA_TOL = 1.0E-3;    // relative
const UV_TOL = 1.0E-4;      // canvas fractions

const failures = [];
const notes = [];

function fail(scope, message) {
  failures.push(scope + ': ' + message);
}

function nearly(a, b, tol) {
  return Math.abs(a - b) <= tol;
}

function loadConfig(file) {
  // Parse FIRST, resolve ${MC_ROOT} afterwards: a Windows root contains backslashes, and splicing
  // it into the raw JSON text produces invalid escapes ("Bad escaped character in JSON").
  const root = path.resolve(__dirname, '..', '..', '..');
  const raw = JSON.parse(fs.readFileSync(file, 'utf8'));
  const resolve = value => typeof value === 'string' ? value.replace(/\$\{MC_ROOT\}/g, root) : value;
  for (const key of Object.keys(raw)) {
    if (typeof raw[key] === 'string') raw[key] = resolve(raw[key]);
  }
  return raw;
}

/** uv of a point according to one facet, using the SAME mapping the client draws with. */
function uvOf(facet, point) {
  const d = L.sub(point, facet.position);
  const dr = L.dot(d, facet.right);
  const dh = L.dot(d, facet.up);
  const u = facet.u0 + (dr + facet.widthM / 2) / facet.widthM * (facet.u1 - facet.u0);
  // v = 0 is the TOP edge, i.e. the +up side of the quad.
  const v = facet.v0 + (facet.heightM / 2 - dh) / facet.heightM * (facet.v1 - facet.v0);
  return [u, v];
}

function verifyAnchor(anchor, group, vpos) {
  const scope = 'anchor ' + anchor.name;
  const faces = anchor.faces;

  if (!faces || faces.length === 0) {
    if (group.faces.length > 1) {
      fail(scope, 'has ' + group.faces.length + ' faces but no facet data - the client would draw one flat quad over the fold');
    } else {
      notes.push(scope + ': single face, no facet data (legacy path preserved) - correct');
    }
    return;
  }

  // G1
  if (faces.length !== group.faces.length) {
    fail(scope, 'facet count ' + faces.length + ' != face count ' + group.faces.length);
    return;
  }

  const facets = faces.map(f => ({
    position: [f.x, f.y, f.z],
    normal: f.normal, up: f.up, right: f.right,
    widthM: f.widthM, heightM: f.heightM,
    corners: f.corners,
    u0: f.u0, v0: f.v0, u1: f.u1, v1: f.v1
  }));

  // Bail out here rather than later: the canvas size is reported in the notes, so a missing one used
  // to turn a clean "this is wrong" into a stack trace the self-test cannot match on.
  const canvasW = anchor.canvasWidthM, canvasH = anchor.canvasHeightM;
  if (!(canvasW > 0) || !(canvasH > 0)) {
    fail(scope, 'has facet data but no usable canvasWidthM/canvasHeightM');
    return;
  }

  /**
   * The facet's own quad in its (right, up) frame, as {a, b, u, v} corner records.
   *
   * <p>A facet is not always a rectangle: a dashboard wing that follows the desk's flowing line is a
   * SHEARED parallelogram, and widthM x heightM is then only its bounding box. When the pack emits
   * `corners` those are the facet's real shape, and each corner takes u from which half of `right` it
   * falls in and v from which half of `up` - the same classification MmtrPanelQuad draws with.</p>
   */
  function quadOf(facet) {
    if (facet.corners && facet.corners.length === 4) {
      return facet.corners.map(pair => {
        const a = pair[0], b = pair[1];
        return { a, b, u: a > 0 ? facet.u1 : facet.u0, v: b > 0 ? facet.v0 : facet.v1 };
      });
    }
    const hw = facet.widthM / 2, hh = facet.heightM / 2;
    return [
      { a: -hw, b: -hh, u: facet.u0, v: facet.v1 },
      { a: hw, b: -hh, u: facet.u1, v: facet.v1 },
      { a: hw, b: hh, u: facet.u1, v: facet.v0 },
      { a: -hw, b: hh, u: facet.u0, v: facet.v0 }
    ];
  }

  /** Corner order around the centroid, so the area and containment tests are well defined. */
  function orderQuad(quad) {
    const ca = quad.reduce((s, c) => s + c.a, 0) / quad.length;
    const cb = quad.reduce((s, c) => s + c.b, 0) / quad.length;
    return quad.slice().sort((p, q) => Math.atan2(p.b - cb, p.a - ca) - Math.atan2(q.b - cb, q.a - ca));
  }

  function quadArea(ordered) {
    let sum = 0;
    for (let i = 0; i < ordered.length; i++) {
      const p = ordered[i], q = ordered[(i + 1) % ordered.length];
      sum += p.a * q.b - q.a * p.b;
    }
    return Math.abs(sum) / 2;
  }

  /**
   * How far (a, b) lies OUTSIDE the convex quad: 0 when inside, else the largest distance past an edge.
   * Same test as containment, but it also quantifies the miss so the best-fitting face can be chosen and
   * the failure message can say how far out the vertex is.
   *
   * <p>The edge test is a DISTANCE, not a raw cross product. A vertex that lies exactly on a corner - the
   * normal case here, because the packager snapped the HUD's corners onto the desk - gives a cross of
   * ~1e-7 on the two edges meeting there, with OPPOSITE signs: the exact value is zero, so the sign is
   * rounding noise. Judged by an absolute epsilon on the cross product, that noise reads as "outside on
   * both sides at once" and reported a perfect corner as a whole quad-width outside. Comparing
   * |cross| / edgeLength against a tolerance in blocks also puts the skip threshold in the same unit as
   * the answer, which is what lets the caller's slack (`tol`) mean anything.</p>
   */
  function outOfQuad(ordered, a, b, tol = 0) {
    let sign = 0, worst = 0;
    const boundary = Math.max(tol, QUAD_EPS);
    for (let i = 0; i < ordered.length; i++) {
      const p = ordered[i], q = ordered[(i + 1) % ordered.length];
      const cross = (q.a - p.a) * (b - p.b) - (q.b - p.b) * (a - p.a);
      const edgeLength = Math.hypot(q.a - p.a, q.b - p.b) || 1;
      const distance = Math.abs(cross) / edgeLength;
      if (distance <= boundary) {
        continue;
      }
      const s = Math.sign(cross);
      if (sign === 0) {
        sign = s;
      } else if (s !== sign) {
        worst = Math.max(worst, distance);
      }
    }
    return worst;
  }

  /** @return true when (a, b) is inside the convex quad, allowing `tol` blocks of slack at the edges. */
  function inQuad(ordered, a, b, tol) {
    // The tolerance is passed THROUGH, not merely compared against: it is also what decides whether a
    // numerically-zero edge cross counts as a sign at all. Dropping it here left the noise threshold at
    // 1e-6 while the caller asked for 1e-3, so a corner whose noise happened to be 1.1e-6 still flipped
    // the sign and came back as "a whole width outside".
    return outOfQuad(ordered, a, b, tol) <= tol;
  }

  // G2/G3/G4, per facet against its own face.
  //
  // The face is CHOSEN, not indexed: the packager's facet order follows its own extraction (the fold
  // chain) while this file's `group.faces` follows the OBJ's declaration order, and the two stopped
  // agreeing once the HUD was re-exported. Nearest-centroid pairing is not enough either, because a
  // sheared facet's `position` is its bounding-box centre while a face is compared by vertex centroid -
  // so the face that actually FITS the facet is the one whose vertices all lie inside it.
  facets.forEach((facet, index) => {
    const scopeF = scope + ' facet ' + index;
    const sheared = !!(facet.corners && facet.corners.length === 4);
    const quad = orderQuad(quadOf(facet));
    let best = null;
    for (const candidate of group.faces) {
      const candidatePoints = candidate.map(i => vpos[i]);
      const centre = L.average(candidatePoints);
      // candidatePoints holds POINTS, not indices: mapping it again through vpos[i] indexes the vertex
      // list with an array and reads undefined (which is where this file used to crash).
      if (L.dot(L.faceNormalArea(candidatePoints.map(p => L.sub(p, centre))).normal, facet.normal) <= 0.9) {
        continue;
      }
      let excess = 0;
      for (const p of candidatePoints) {
        const d = L.sub(p, facet.position);
        excess = Math.max(excess, outOfQuad(quad, L.dot(d, facet.right), L.dot(d, facet.up)));
      }
      if (!best || excess < best.excess) {
        best = { face: candidate, points: candidatePoints, excess };
      }
    }
    if (!best) {
      fail(scopeF, 'no modelled face points the same way as this facet');
      return;
    }
    const faceVertices = best.face;
    const points = best.points;
    const facing = L.faceNormalArea(faceVertices.map(i => L.sub(vpos[i], L.average(points))));
    if (L.dot(facing.normal, facet.normal) < 0.9999) {
      fail(scopeF, 'normal ' + facet.normal.map(x => x.toFixed(4)).join(',') + ' disagrees with the face winding ' + facing.normal.map(x => x.toFixed(4)).join(','));
    }
    for (const p of points) {
      const d = L.sub(p, facet.position);
      const offPlane = Math.abs(L.dot(d, facet.normal));
      if (offPlane > PLANE_TOL) {
        fail(scopeF, 'a face vertex sits ' + offPlane.toFixed(5) + ' blocks off the facet plane (tol ' + PLANE_TOL + ')');
      }
      const a = L.dot(d, facet.right), b = L.dot(d, facet.up);
      if (sheared) {
        if (!inQuad(quad, a, b, RECT_TOL)) {
          fail(scopeF, 'a face vertex is outside the facet QUAD by ' + outOfQuad(quad, a, b).toFixed(5) + ' blocks (right=' + a.toFixed(5) + ', up=' + b.toFixed(5) + ')');
        }
      } else if (Math.abs(a) > facet.widthM / 2 + RECT_TOL || Math.abs(b) > facet.heightM / 2 + RECT_TOL) {
        fail(scopeF, 'a face vertex is outside the facet rectangle (|right|=' + Math.abs(a).toFixed(5) + ' vs half ' + (facet.widthM / 2).toFixed(5) + ', |up|=' + Math.abs(b).toFixed(5) + ' vs half ' + (facet.heightM / 2).toFixed(5) + ')');
      }
    }
    const coverArea = sheared ? quadArea(quad) : facet.widthM * facet.heightM;
    if (Math.abs(coverArea - facing.area) / Math.max(facing.area, 1e-9) > AREA_TOL) {
      fail(scopeF, (sheared ? 'corner quad area ' : 'rectangle area ') + coverArea.toFixed(4) + ' != face area ' + facing.area.toFixed(4) + ' (the panel would not match the modelled face)');
    }
  });

  /**
   * The facet's (u, v) for a point given in its (right, up) frame, using the SAME affine map the client
   * draws with: solved from three corner correspondences (a parallelogram's map is affine, so three are
   * exact). Falls back to the rectangle mapping for a pack without corners.
   */
  function uvFromQuad(facet, a, b) {
    const quad = quadOf(facet);
    if (!(facet.corners && facet.corners.length === 4)) {
      return [facet.u0 + (a + facet.widthM / 2) / facet.widthM * (facet.u1 - facet.u0),
        facet.v0 + (facet.heightM / 2 - b) / facet.heightM * (facet.v1 - facet.v0)];
    }
    const p0 = quad[0];
    const other = quad.slice(1);
    for (let i = 0; i < other.length; i++) {
      for (let j = i + 1; j < other.length; j++) {
        const p1 = other[i], p2 = other[j];
        const da1 = p1.a - p0.a, db1 = p1.b - p0.b, da2 = p2.a - p0.a, db2 = p2.b - p0.b;
        const det = da1 * db2 - da2 * db1;
        if (Math.abs(det) < 1e-9) {
          continue;
        }
        const du1 = p1.u - p0.u, dv1 = p1.v - p0.v, du2 = p2.u - p0.u, dv2 = p2.v - p0.v;
        const A = (du1 * db2 - du2 * db1) / det, B = (da1 * du2 - da2 * du1) / det;
        const C = (dv1 * db2 - dv2 * db1) / det, D = (da1 * dv2 - da2 * dv1) / det;
        return [p0.u + A * (a - p0.a) + B * (b - p0.b), p0.v + C * (a - p0.a) + D * (b - p0.b)];
      }
    }
    return [facet.u0, facet.v0];
  }

  // G5: uv continuity across every shared edge
  const vertexToFacets = new Map();
  group.faces.forEach((face, fi) => {
    for (const i of new Set(face)) {
      if (!vertexToFacets.has(i)) vertexToFacets.set(i, []);
      vertexToFacets.get(i).push(fi);
    }
  });
  let sharedChecked = 0;
  for (const [vertex, owners] of vertexToFacets) {
    if (owners.length < 2) continue;
    sharedChecked++;
    const [first, ...rest] = owners;
    // A point's uv comes from the facet's OWN quad when it declares one (a sheared facet's map is affine
    // over its corners); the rectangle mapping is only right for a rectangle.
    const uvOn = (facet, point) => {
      if (facet.corners && facet.corners.length === 4) {
        const d = L.sub(point, facet.position);
        return uvFromQuad(facet, L.dot(d, facet.right), L.dot(d, facet.up));
      }
      return uvOf(facet, point);
    };
    const uvFirst = uvOn(facets[first], vpos[vertex]);
    for (const other of rest) {
      const uvOther = uvOn(facets[other], vpos[vertex]);
      if (!nearly(uvFirst[0], uvOther[0], UV_TOL) || !nearly(uvFirst[1], uvOther[1], UV_TOL)) {
        fail(scope, 'vertex ' + (vertex + 1) + ' is shared by facets ' + first + ' and ' + other + ' but maps to uv (' + uvFirst.map(x => x.toFixed(5)).join(', ') + ') vs (' + uvOther.map(x => x.toFixed(5)).join(', ') + ') - the image would break at the crease');
      }
    }
  }
  if (sharedChecked === 0) {
    fail(scope, 'no shared vertices between facets, so the packager could not have found the crease either');
  }

  // G6: the facets tile the canvas
  const uMin = Math.min(...facets.map(f => Math.min(f.u0, f.u1)));
  const uMax = Math.max(...facets.map(f => Math.max(f.u0, f.u1)));
  const vMin = Math.min(...facets.map(f => Math.min(f.v0, f.v1)));
  const vMax = Math.max(...facets.map(f => Math.max(f.v0, f.v1)));
  if (!nearly(uMin, 0, UV_TOL) || !nearly(uMax, 1, UV_TOL)) {
    fail(scope, 'facets do not span u 0..1 (got ' + uMin.toFixed(5) + '..' + uMax.toFixed(5) + ')');
  }
  if (!nearly(vMin, 0, UV_TOL) || !nearly(vMax, 1, UV_TOL)) {
    fail(scope, 'facets do not span v 0..1 (got ' + vMin.toFixed(5) + '..' + vMax.toFixed(5) + ')');
  }
  // Along the folding axis the intervals must be contiguous. The folding axis is whichever of u/v
  // the facets actually differ in.
  const spansU = facets.some(f => !nearly(Math.abs(f.u1 - f.u0), 1, UV_TOL));
  const [fromKey, toKey] = spansU ? ['u0', 'u1'] : ['v0', 'v1'];
  const sorted = [...facets].sort((a, b) => a[fromKey] - b[fromKey]);
  for (let i = 1; i < sorted.length; i++) {
    const gap = sorted[i][fromKey] - sorted[i - 1][toKey];
    if (Math.abs(gap) > UV_TOL) {
      fail(scope, 'a ' + (gap > 0 ? 'GAP' : 'OVERLAP') + ' of ' + Math.abs(gap).toFixed(5) + ' in the canvas between consecutive facets along ' + (spansU ? 'u' : 'v'));
    }
  }

  // G7: image orientation. v = 0 must be the panel's TOP. The top is the facet corner furthest
  // along the facet's own "up", so the facet owning the extreme "up" position must own v = 0.
  const sharedAxis = spansU ? 'right' : 'up';   // the axis the facets share is the constant one
  const foldAxis = spansU ? 'up' : 'right';
  const highest = facets.reduce((best, f) => {
    const h = L.dot(f.position, facets[0][sharedAxis]) + (foldAxis === 'up' ? f.heightM / 2 : 0);
    return h > best.h ? { h, f } : best;
  }, { h: -Infinity, f: null });
  const topV = [highest.f.v0, highest.f.v1];
  if (!topV.some(v => nearly(v, 0, UV_TOL))) {
    fail(scope, 'the topmost facet owns v ' + topV.map(v => v.toFixed(3)).join('..') + ', not 0 - the dashboard would read upside down');
  }

  notes.push(scope + ': ' + facets.length + ' facets, canvas ' + canvasW.toFixed(3) + ' x ' + canvasH.toFixed(3)
    + ' m, legacy quad ' + anchor.widthM.toFixed(3) + ' x ' + anchor.heightM.toFixed(3)
    + ' m (unfolded ' + (canvasH / anchor.heightM).toFixed(2) + 'x taller), shared vertices checked ' + sharedChecked);
  if (spansU) notes.push(scope + ': fold runs along u (vertical crease)');
}

function main() {
  const args = process.argv.slice(2);
  const configPath = args[args.indexOf('--config') + 1];
  const report = args.includes('--report');
  if (!configPath || !fs.existsSync(configPath)) {
    console.error('usage: node verify_facets.js --config <vehicle config.json> [--report]');
    process.exit(2);
  }

  const config = loadConfig(configPath);
  const stage = (config.stagingDir || path.join(path.dirname(config.sourceObj), '.pack_stage_' + config.id))
    .replace(/\$\{MC_ROOT\}/g, path.resolve(__dirname, '..', '..', '..'));
  const anchorFile = path.join(stage, 'assets', 'mtr', 'mmtr_anchors_' + config.id + '.json');
  if (!fs.existsSync(anchorFile)) {
    console.error('anchor file not found: ' + anchorFile + '\n(run the packager on this config first)');
    process.exit(2);
  }

  const obj = L.parseObj(config.sourceObj);
  const vpos = L.transformVertices(obj.vpos, config);
  const anchors = JSON.parse(fs.readFileSync(anchorFile, 'utf8')).anchors || [];
  const groups = L.anchorsOf(obj);

  console.log('config   : ' + configPath);
  console.log('source   : ' + config.sourceObj);
  console.log('anchors  : ' + anchorFile);
  console.log('');

  let folded = 0;
  // ★ 反向车（B 端车）是"**同一个 OBJ + 打包时改名**"：config.groupRename 把 mmtr_hud_1 改成 mmtr_hud_2，
  //   所以**源 OBJ 里根本没有 mmtr_hud_2** —— 直接按锚点名找会把一辆完全正常的车报成
  //   "no matching mmtr_hud_2 object in the source OBJ"（实测 cab_b）。先按 groupRename **反查源组名**，
  //   支持通配符条目（如 mmtr_light_1_* -> mmtr_light_2_*）。
  const sourceNameFor = new Map();
  for (const [from, to] of Object.entries(config.groupRename || {})) {
    const escaped = String(from).split('*').map(s => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('.*');
    const re = new RegExp('^' + escaped + '$', 'i');
    for (const g of groups) {
      if (!re.test(g.name)) continue;
      let renamed = String(to);
      if (String(from).includes('*')) {
        const pre = String(from).split('*')[0];
        const post = String(from).split('*').slice(1).join('*');
        const middle = g.name.slice(pre.length, post ? g.name.length - post.length : undefined);
        renamed = String(to).replace('*', middle);
      }
      sourceNameFor.set(renamed.replace(/^mmtr_/i, ''), g.name);
    }
  }
  for (const anchor of anchors) {
    if (anchor.kind !== 'hud') continue;
    const renamedSource = sourceNameFor.get(anchor.name);
    const group = renamedSource
      ? groups.find(g => g.name === renamedSource)
      : groups.find(g => g.name.replace(/^mmtr_/i, '') === anchor.name);
    if (!group) {
      fail('anchor ' + anchor.name, 'no matching mmtr_' + anchor.name + ' object in the source OBJ');
      continue;
    }
    if (renamedSource) notes.push('anchor ' + anchor.name + ' maps to source group ' + renamedSource + ' via groupRename');
    if (anchor.faces && anchor.faces.length > 1) folded++;
    verifyAnchor(anchor, group, vpos);
  }

  if (report) {
    console.log('-- notes --');
    notes.forEach(n => console.log('  ' + n));
    console.log('');
  } else {
    notes.forEach(n => console.log('  ' + n));
    console.log('');
  }

  if (failures.length) {
    console.log('FAIL (' + failures.length + ')');
    failures.forEach(f => console.log('  ! ' + f));
    process.exit(1);
  }
  console.log('PASS: ' + anchors.filter(a => a.kind === 'hud').length + ' hud anchor(s) checked, ' + folded + ' with facet data');
}

main();
