'use strict';
/*
 * lib.js - shared OBJ parsing and vector helpers for the anchor-check tools.
 *
 * Both anchor_faces.js (does a group fold?) and verify_facets.js (do the emitted facets fit the
 * group?) must read the OBJ the SAME way, or the two tools can disagree about the very thing they
 * are used to cross-check. Kept deliberately small: OBJ in, vertices + named groups out.
 */

const fs = require('fs');

/** Parses an OBJ into {vpos, groups} with groups = [{name, faces:[[vertexIndex...]]}], 0-based. */
function parseObj(path) {
  const vpos = [];
  const groups = [];
  let current = null;
  for (const rawLine of fs.readFileSync(path, 'utf8').split('\n')) {
    const line = rawLine.trim();
    if (line.startsWith('v ')) {
      const p = line.split(/\s+/).slice(1, 4).map(Number);
      vpos.push([p[0], p[1], p[2]]);
    } else if (line.startsWith('o ') || line.startsWith('g ')) {
      // Blender de-duplicates object names with a ".001" suffix, and the packager strips it before
      // matching, so the same strip has to happen here.
      const name = line.slice(2).trim().replace(/\.\d+$/, '');
      current = { name, faces: [] };
      groups.push(current);
    } else if (line.startsWith('f ') && current) {
      const idx = line.split(/\s+/).slice(1).map(ref => {
        const i = parseInt(ref.split('/')[0], 10);
        return i > 0 ? i - 1 : vpos.length + i;   // OBJ allows negative (from-the-end) indices
      });
      if (idx.length >= 3) current.faces.push(idx);
    }
  }
  return { vpos, groups };
}

/** The mmtr_* groups only, in file order. */
function anchorsOf(obj) {
  return obj.groups.filter(g => /^mmtr_/i.test(g.name));
}

const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const scl = (a, s) => [a[0] * s, a[1] * s, a[2] * s];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const len = v => Math.hypot(v[0], v[1], v[2]);
const norm = v => { const l = len(v) || 1; return [v[0] / l, v[1] / l, v[2] / l]; };
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const average = pts => { const s = [0, 0, 0]; for (const p of pts) { s[0] += p[0]; s[1] += p[1]; s[2] += p[2]; } return scl(s, 1 / pts.length); };

/** Newell normal + area, measured about the polygon's own centroid. */
function faceNormalArea(points) {
  let nx = 0, ny = 0, nz = 0;
  for (let i = 0; i < points.length; i++) {
    const a = points[i], b = points[(i + 1) % points.length];
    nx += (a[1] - b[1]) * (a[2] + b[2]);
    ny += (a[2] - b[2]) * (a[0] + b[0]);
    nz += (a[0] - b[0]) * (a[1] + b[1]);
  }
  const area = Math.hypot(nx, ny, nz) / 2;
  return { normal: area > 0 ? norm([nx, ny, nz]) : [0, 0, 1], area };
}

/**
 * The transform pack_vehicle.js applies to vertex positions before writing anchors: rotate about Y
 * by rotationDegY, then subtract the centre of the resulting bounding box when recenter is on.
 * Verified against pack_vehicle.js:
 *   vpos.push([cs*p[0]+sn*p[2], p[1], -sn*p[0]+cs*p[2]])   then   rc = (x-cx, y, z-cz)
 */
function makeVertexTransform(config) {
  const deg = config.rotationDegY || 0;
  const rad = deg * Math.PI / 180;
  const cs = Math.cos(rad), sn = Math.sin(rad);
  return { cs, sn };
}

function transformVertices(vpos, config) {
  const { cs, sn } = makeVertexTransform(config);
  const rotated = vpos.map(p => [cs * p[0] + sn * p[2], p[1], -sn * p[0] + cs * p[2]]);
  let cx = 0, cz = 0;
  if (config.recenter !== false && rotated.length) {
    let mnX = Infinity, mxX = -Infinity, mnZ = Infinity, mxZ = -Infinity;
    for (const v of rotated) {
      if (v[0] < mnX) mnX = v[0];
      if (v[0] > mxX) mxX = v[0];
      if (v[2] < mnZ) mnZ = v[2];
      if (v[2] > mxZ) mxZ = v[2];
    }
    cx = (mnX + mxX) / 2;
    cz = (mnZ + mxZ) / 2;
  }
  return rotated.map(v => [v[0] - cx, v[1], v[2] - cz]);
}

module.exports = {
  parseObj, anchorsOf, transformVertices,
  sub, add, scl, cross, len, norm, dot, average, faceNormalArea
};
