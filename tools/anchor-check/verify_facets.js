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
    u0: f.u0, v0: f.v0, u1: f.u1, v1: f.v1
  }));

  // Bail out here rather than later: the canvas size is reported in the notes, so a missing one used
  // to turn a clean "this is wrong" into a stack trace the self-test cannot match on.
  const canvasW = anchor.canvasWidthM, canvasH = anchor.canvasHeightM;
  if (!(canvasW > 0) || !(canvasH > 0)) {
    fail(scope, 'has facet data but no usable canvasWidthM/canvasHeightM');
    return;
  }

  // G2/G3/G4, per facet against its own face
  facets.forEach((facet, index) => {
    const scopeF = scope + ' facet ' + index;
    const face = group.faces[index];
    const points = face.map(i => vpos[i]);
    const facing = L.faceNormalArea(face.map(i => L.sub(vpos[i], L.average(points))));
    if (L.dot(facing.normal, facet.normal) < 0.9999) {
      fail(scopeF, 'normal ' + facet.normal.map(x => x.toFixed(4)).join(',') + ' disagrees with the face winding ' + facing.normal.map(x => x.toFixed(4)).join(','));
    }
    for (const p of points) {
      const d = L.sub(p, facet.position);
      const offPlane = Math.abs(L.dot(d, facet.normal));
      if (offPlane > PLANE_TOL) {
        fail(scopeF, 'a face vertex sits ' + offPlane.toFixed(5) + ' blocks off the facet plane (tol ' + PLANE_TOL + ')');
      }
      const dr = Math.abs(L.dot(d, facet.right));
      const dh = Math.abs(L.dot(d, facet.up));
      if (dr > facet.widthM / 2 + RECT_TOL || dh > facet.heightM / 2 + RECT_TOL) {
        fail(scopeF, 'a face vertex is outside the facet rectangle (|right|=' + dr.toFixed(5) + ' vs half ' + (facet.widthM / 2).toFixed(5) + ', |up|=' + dh.toFixed(5) + ' vs half ' + (facet.heightM / 2).toFixed(5) + ')');
      }
    }
    const rectArea = facet.widthM * facet.heightM;
    if (Math.abs(rectArea - facing.area) / Math.max(facing.area, 1e-9) > AREA_TOL) {
      fail(scopeF, 'rectangle area ' + rectArea.toFixed(4) + ' != face area ' + facing.area.toFixed(4) + ' (the panel would not match the modelled face)');
    }
  });

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
    const uvFirst = uvOf(facets[first], vpos[vertex]);
    for (const other of rest) {
      const uvOther = uvOf(facets[other], vpos[vertex]);
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
  for (const anchor of anchors) {
    if (anchor.kind !== 'hud') continue;
    const group = groups.find(g => g.name.replace(/^mmtr_/i, '') === anchor.name);
    if (!group) {
      fail('anchor ' + anchor.name, 'no matching mmtr_' + anchor.name + ' object in the source OBJ');
      continue;
    }
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
