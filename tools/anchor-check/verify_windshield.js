#!/usr/bin/env node
/*
 * verify_windshield.js - checks the multi-pane windscreen naming and the FITTED WIPER ACTION SECTOR.
 *
 * The packager turns a modelled triangle fan into the pivot/angles the client sweeps with, and every
 * part of that is arithmetic on the source geometry. "The pack succeeded" says nothing about whether it
 * is right, so this re-derives the whole fit from the SOURCE OBJ - independently of the packager's code
 * - and fails loudly on:
 *
 *   N1  INDEX RULE: mmtr_windshield_1 -> cab 1 pane 1, mmtr_windshield_1_2 -> cab 1 pane 2, and
 *       mmtr_windshield_2 -> CAB 2 pane 1. The last one is the backward-compatibility guard: reading a
 *       lone index as a pane would silently turn SAF101v2's one-screen-per-cab into two panes of cab 1.
 *   N2  PAIRING: every mmtr_wipersweep_<cab>_<pane> has a matching glass, and every glass that has a
 *       fan gets a fitted block - a sweep whose fit was dropped is a wiper that clears nothing.
 *   S1  FIT: pivot (pivotU/pivotV), armM, parkAngleDeg and sweepDeg re-derived from the fan's own
 *       vertices must match what was written.
 *   S2  ON THE GLASS: every fan vertex lies in the glass's plane and inside its extent.
 *   S3  SOLID WIPER: a wiper_<cab>_<pane> object in the model means that glass draws no blade
 *       (drawBlade=false) and is still wiped (wiper=true); no other glass is affected. The part must
 *       also exist in the packed properties file, as its OWN part.
 *
 * Usage:
 *   node mmtr/tools/obj-mtr-packager/pack_vehicle.js mmtr/tools/anchor-check/fixture/wipefix.json
 *   node mmtr/tools/anchor-check/verify_windshield.js --config mmtr/tools/anchor-check/fixture/wipefix.json
 */

'use strict';

const fs = require('fs');
const path = require('path');
const L = require('./lib.js');

const ANGLE_TOL_DEG = 0.05;
const FRACTION_TOL = 1.0E-3;
const ARM_TOL_M = 1.0E-3;
const OFF_PLANE_TOL_M = 1.0E-3;

const failures = [];
const notes = [];

function fail(scope, message) {
  failures.push(scope + ': ' + message);
}

function resolveRoot() {
  return path.resolve(__dirname, '..', '..', '..');
}

function loadConfig(file) {
  const root = resolveRoot();
  const raw = JSON.parse(fs.readFileSync(file, 'utf8'));
  for (const key of Object.keys(raw)) {
    if (typeof raw[key] === 'string') raw[key] = raw[key].replace(/\$\{MC_ROOT\}/g, root);
  }
  return raw;
}

/**
 * The glass's 2D domain, re-derived from the SOURCE geometry the way the packager's own rule works:
 * the reference (largest) face gives the normal by its winding; "up" is the face edge most aligned with
 * world +Y; right = up x normal. Returns metres from the face centre along right/up.
 */
function glassDomain(group, vpos) {
  const ids = [...new Set(group.faces.flat())];
  const points = ids.map(i => vpos[i]);
  const centre = L.average(points);
  let face = group.faces[0], bestArea = -1, bestNormal = null;
  for (const f of group.faces) {
    const info = L.faceNormalArea(f.map(i => L.sub(vpos[i], centre)));
    if (info.area > bestArea) { bestArea = info.area; face = f; bestNormal = info.normal; }
  }
  const e1 = L.sub(vpos[face[1]], vpos[face[0]]);
  const e2 = L.sub(vpos[face[2]], vpos[face[1]]);
  const upRaw = Math.abs(e1[1]) >= Math.abs(e2[1]) ? e1 : e2;
  const up = L.norm(L.sub(upRaw, L.scl(bestNormal, L.dot(upRaw, bestNormal))));
  const right = L.norm(L.cross(up, bestNormal));
  const extents = { wMin: Infinity, wMax: -Infinity, hMin: Infinity, hMax: -Infinity };
  for (const p of points) {
    const d = L.sub(p, centre);
    const dr = L.dot(d, right), dh = L.dot(d, up);
    extents.wMin = Math.min(extents.wMin, dr); extents.wMax = Math.max(extents.wMax, dr);
    extents.hMin = Math.min(extents.hMin, dh); extents.hMax = Math.max(extents.hMax, dh);
  }
  return {
    centre, normal: bestNormal, up, right,
    widthM: extents.wMax - extents.wMin,
    heightM: extents.hMax - extents.hMin,
    toRightUp: p => { const d = L.sub(p, centre); return [L.dot(d, right), L.dot(d, up)]; },
    offPlane: p => Math.abs(L.dot(L.sub(p, centre), bestNormal))
  };
}

/**
 * The two ends of a bar-shaped part, as (right, up) in the glass domain. Principal-axis fit, NOT "the
 * two furthest-apart vertices" - for a box that pair is a diagonal.
 */
function barEnds(group, domain, vpos) {
  const ids = [...new Set(group.faces.flat())];
  const points = ids.map(i => domain.toRightUp(vpos[i]));
  const centre = L.average(points.map(p => [p[0], p[1], 0]));
  const cx = centre[0], cy = centre[1];
  let srr = 0, sru = 0, suu = 0;
  for (const p of points) {
    const dx = p[0] - cx, dy = p[1] - cy;
    srr += dx * dx; sru += dx * dy; suu += dy * dy;
  }
  const theta = 0.5 * Math.atan2(2 * sru, srr - suu);
  const axis = [Math.cos(theta), Math.sin(theta)];
  let min = Infinity, max = -Infinity;
  for (const p of points) {
    const t = (p[0] - cx) * axis[0] + (p[1] - cy) * axis[1];
    if (t < min) min = t;
    if (t > max) max = t;
  }
  return [
    [cx + axis[0] * min, cy + axis[1] * min],
    [cx + axis[0] * max, cy + axis[1] * max]
  ];
}

function distanceToSegment(p, a, b) {
  const dx = b[0] - a[0], dy = b[1] - a[1];
  const lengthSquared = dx * dx + dy * dy;
  const t = lengthSquared < 1e-12 ? 0 : Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / lengthSquared));
  return Math.hypot(p[0] - (a[0] + dx * t), p[1] - (a[1] + dy * t));
}

/**
 * THE CLIENT'S KINEMATICS, re-implemented. Both wiper families are this pair of expressions; the
 * difference between them falls out of the geometry rather than out of a branch:
 *
 *   A(theta) = P1 + R(theta) * (A0 - P1)
 *   B(theta) = P2 + R(theta) * (B0 - P2)
 *
 * With P1 = P2 the blade rotates rigidly about the spindle (a car wiper). With equal link vectors the
 * difference B - A is constant, so the blade only translates (a train's parallel linkage).
 * KEEP THIS IN STEP WITH the client when the client grows the same helper.
 */
function bladeSegment(p1, p2, a0, b0, thetaDeg) {
  const radians = thetaDeg * Math.PI / 180;
  const cos = Math.cos(radians), sin = Math.sin(radians);
  const rotate = (pivot, point) => {
    const dx = point[0] - pivot[0], dy = point[1] - pivot[1];
    return [pivot[0] + dx * cos - dy * sin, pivot[1] + dx * sin + dy * cos];
  };
  // Mirrors the CLIENT, which currently uses the PARALLELOGRAM form (both ends rotate by the same angle).
  // The general four-bar loop closure is designed but not enabled - see notes/187.
  return [rotate(p1, a0), rotate(p2, b0)];
}

/** The mechanism's assembly mode: constant, so it is read off the parked configuration. */
function followerMode(p1, p2, a0, b0) {
  const followerM = Math.hypot(b0[0] - p2[0], b0[1] - p2[1]);
  const bladeM = Math.hypot(b0[0] - a0[0], b0[1] - a0[1]);
  const plus = followerEnd(a0, p2, followerM, bladeM, 1);
  const minus = followerEnd(a0, p2, followerM, bladeM, -1);
  if (plus === null || minus === null) return 1;
  return Math.hypot(plus[0] - b0[0], plus[1] - b0[1]) <= Math.hypot(minus[0] - b0[0], minus[1] - b0[1]) ? 1 : -1;
}

function followerEnd(a, pivot, followerM, bladeM, mode) {
  const dx = pivot[0] - a[0], dy = pivot[1] - a[1];
  const distance = Math.hypot(dx, dy);
  if (distance < 1.0E-9 || distance > followerM + bladeM || distance < Math.abs(followerM - bladeM)) return null;
  const along = (distance * distance + followerM * followerM - bladeM * bladeM) / (2 * distance);
  const height = Math.sqrt(Math.max(0, followerM * followerM - along * along));
  const ux = dx / distance, uy = dy / distance;
  return [a[0] + ux * along - mode * uy * height, a[1] + uy * along + mode * ux * height];
}

/** Point-in-convex-quad, mirroring MmtrWindshield.insideConvexQuad. */
function insideConvexQuad(px, py, xs, ys) {
  let positive = false, negative = false;
  for (let i = 0; i < 4; i++) {
    const j = (i + 1) % 4;
    const cross = (xs[j] - xs[i]) * (py - ys[i]) - (ys[j] - ys[i]) * (px - xs[i]);
    const margin = Math.hypot(xs[j] - xs[i], ys[j] - ys[i]) * BAND_INFLATE_M;
    if (cross > margin) positive = true;
    if (cross < -margin) negative = true;
  }
  return !(positive && negative);
}

/**
 * THE BAND WIPE, mirroring MmtrWindshield.wipeFactorBand: the region a MODELLED blade cleared is the
 * quadrilateral between where it was and where it is. This is the only formulation that works for a
 * parallel linkage, whose blade never passes through a pivot.
 */
const WIPE_FADE_M = 0.03;
const BAND_INFLATE_M = 0.003;

function bandFactor(px, py, from, to) {
  const xs = [from[0][0], from[1][0], to[1][0], to[0][0]];
  const ys = [from[0][1], from[1][1], to[1][1], to[0][1]];
  if (insideConvexQuad(px, py, xs, ys)) return 1;
  const d = distanceToSegment([px, py], to[0], to[1]);
  return d >= WIPE_FADE_M ? 0 : 1 - d / WIPE_FADE_M;
}

/**
 * THE STROKE, re-derived from the source geometry the way the packager does it - independently.
 *
 * The fan is the region the BLADE sweeps, so its boundary edges are the blade's own extreme positions.
 * One of them is the parked blade; the other says which rotation puts the blade there, which is how the
 * ARM's stroke is recovered (a real linkage opens only a couple of degrees, so the fan's own opening is
 * NOT the stroke). Matching is by POSITION: a line angle repeats every 180 degrees, and a linkage's blade
 * direction barely moves, so direction alone cannot tell the real stroke from its aliases.
 *
 * @returns {strokeDeg, pivotU, pivotV, armM, parkAngleDeg, onEdge} or {error}
 */
function solveStrokeFromFan(sweepGroup, bladeGroup, armGroup, rodGroup, domain, vpos) {
  const counts = new Map();
  for (const f of sweepGroup.faces) for (const i of new Set(f)) counts.set(i, (counts.get(i) || 0) + 1);
  let apexes = [...counts.entries()].filter(e => e[1] === sweepGroup.faces.length).map(e => e[0]);
  if (apexes.length > 1) {
    const firsts = new Set(sweepGroup.faces.map(f => f[0]));
    if (firsts.size === 1 && apexes.includes([...firsts][0])) apexes = [[...firsts][0]];
  }
  if (apexes.length !== 1) return { error: 'not a triangle fan' };
  const apex = apexes[0];

  const bladeEnds = barEnds(bladeGroup, domain, vpos);
  // The spindle comes from the ARM (the end that does not touch the blade) - the fan's apex is the swept
  // region's virtual centre and is NOT the spindle, not even for a single-axis wiper.
  if (!armGroup) return { error: 'no wiperarm_ group, so the spindle cannot be located' };
  const armEnds = barEnds(armGroup, domain, vpos);
  const armNear0 = distanceToSegment(armEnds[0], bladeEnds[0], bladeEnds[1]);
  const armNear1 = distanceToSegment(armEnds[1], bladeEnds[0], bladeEnds[1]);
  const p1 = armNear0 <= armNear1 ? armEnds[1] : armEnds[0];

  const near0 = Math.hypot(bladeEnds[0][0] - p1[0], bladeEnds[0][1] - p1[1]);
  const near1 = Math.hypot(bladeEnds[1][0] - p1[0], bladeEnds[1][1] - p1[1]);
  const a0 = near0 <= near1 ? bladeEnds[0] : bladeEnds[1];
  const b0 = near0 <= near1 ? bladeEnds[1] : bladeEnds[0];

  let p2 = p1.slice();
  if (rodGroup) {
    const rodEnds = barEnds(rodGroup, domain, vpos);
    const d0 = distanceToSegment(rodEnds[0], a0, b0);
    const d1 = distanceToSegment(rodEnds[1], a0, b0);
    p2 = d0 <= d1 ? rodEnds[1] : rodEnds[0];
  }

  // Every rim edge that is not incident to the apex is a boundary candidate.
  const edges = [];
  for (const f of sweepGroup.faces) {
    for (let i = 0; i < f.length; i++) {
      const a = f[i], b = f[(i + 1) % f.length];
      if (a === apex || b === apex) continue;
      const pa = domain.toRightUp(vpos[a]), pb = domain.toRightUp(vpos[b]);
      if (Math.hypot(pb[0] - pa[0], pb[1] - pa[1]) <= 0.02) continue;
      edges.push({ a: pa, b: pb });
    }
  }

  const bladeAt = phi => {
    const radians = phi * Math.PI / 180, cos = Math.cos(radians), sin = Math.sin(radians);
    const rotate = (pivot, point) => {
      const dx = point[0] - pivot[0], dy = point[1] - pivot[1];
      return [pivot[0] + dx * cos - dy * sin, pivot[1] + dx * sin + dy * cos];
    };
    return [rotate(p1, a0), rotate(p2, b0)];
  };

  let best = null;
  for (const edge of edges) {
    for (let phi = -180; phi <= 180; phi += 0.05) {
      const [aAt, bAt] = bladeAt(phi);
      const on = Math.max(distanceToSegment(aAt, edge.a, edge.b), distanceToSegment(bAt, edge.a, edge.b));
      if (on <= 0.005 && Math.abs(phi) > 0.2 && (best === null || on < best.on)) best = { phi, on };
    }
  }
  if (best === null) return { error: 'no fan boundary edge is a blade position (the fan must be the region the blade sweeps)' };
  return {
    strokeDeg: Math.abs(best.phi),
    sweepSign: best.phi < 0 ? -1 : 1,
    onEdge: best.on,
    parkAngleDeg: Math.atan2(b0[1] - a0[1], b0[0] - a0[0]) * 180 / Math.PI,
    pivot1: p1, pivot2: p2, a0, b0
  };
}

/**
 * THE PIN-BASED KINEMATICS - the target form, and the reason the current one is wrong for a real arm.
 *
 * A real wiper pins its arm to the blade's MIDDLE (so the blade is pressed evenly) and its rod near an
 * end. The two POINTS a linkage actually drives are therefore the pins, not the blade's ends:
 *
 *   M(theta)  = P1 + R(theta)(M0  - P1)       the arm's pin
 *   Br(theta) = P2 + R(theta)(Br0 - P2)       the rod's pin
 *   T         = the rigid motion taking M0->M(theta) and Br0->Br(theta)
 *   blade ends = T(A0), T(B0)
 *
 * The current code instead rotates the BLADE'S END nearest the spindle about P1, i.e. it assumes a link of
 * length |A0-P1| where the real link is |M0-P1|. Harmless for a single-axis wiper (a rigid body rotating
 * about P1 is described equally well by any of its points), but it scales a linkage's blade translation
 * wrongly.
 */
function pinMotion(p1, p2, m0, br0, a0, b0, thetaDeg) {
  const radians = thetaDeg * Math.PI / 180;
  const cos = Math.cos(radians), sin = Math.sin(radians);
  const rotate = (pivot, point) => {
    const dx = point[0] - pivot[0], dy = point[1] - pivot[1];
    return [pivot[0] + dx * cos - dy * sin, pivot[1] + dx * sin + dy * cos];
  };
  const m = rotate(p1, m0);
  const br = rotate(p2, br0);
  // A rotation by the angle the pin PAIR turned through, plus the translation that puts M0 on M(theta).
  // For a parallelogram the pin pair keeps its direction, so this is a pure translation - which is
  // exactly what "the blade does not turn" means.
  const phi = Math.atan2(br[1] - m[1], br[0] - m[0]) - Math.atan2(br0[1] - m0[1], br0[0] - m0[0]);
  const c = Math.cos(phi), s = Math.sin(phi);
  const apply = point => {
    const dx = point[0] - m0[0], dy = point[1] - m0[1];
    return [m[0] + dx * c - dy * s, m[1] + dx * s + dy * c];
  };
  return [apply(a0), apply(b0)];
}

/**
 * A unit check of the pin kinematics on SYNTHETIC, exactly-known geometry - an ideal parallelogram with
 * the arm pinned at the blade's MIDDLE, which no fixture covers yet.
 *
 * The second assertion is the point of the whole exercise: it fails if the kinematics is switched back to
 * rotating the blade's END, so this is a regression test for the bug rather than a description of it.
 */
function checkPinKinematics(fail, note) {
  const DEG = Math.PI / 180;
  const P1 = [0, 0], P2 = [0, 0.3];
  const LINK = [0.5, 0.12];                        // the ARM's link vector
  const M0 = [P1[0] + LINK[0], P1[1] + LINK[1]];   // the arm's pin, at the blade's middle
  const BR0 = [P2[0] + LINK[0], P2[1] + LINK[1]];  // the rod's pin: the SAME vector => ideal parallelogram
  const ALONG = [(BR0[0] - M0[0]) / 0.3, (BR0[1] - M0[1]) / 0.3];
  const A0 = [M0[0] - ALONG[0] * 0.15, M0[1] - ALONG[1] * 0.15];   // the pin is 0.15 m from this end
  const B0 = [BR0[0] + ALONG[0] * 0.05, BR0[1] + ALONG[1] * 0.05];

  // (1) An ideal parallelogram moves the blade by pure translation: its direction must not change.
  const parkDirection = Math.atan2(B0[1] - A0[1], B0[0] - A0[0]) * 180 / Math.PI;
  let worstTurn = 0;
  for (let step = 0; step <= 20; step++) {
    const [a, b] = pinMotion(P1, P2, M0, BR0, A0, B0, 50 * step / 20);
    let turn = Math.atan2(b[1] - a[1], b[0] - a[0]) * 180 / Math.PI - parkDirection;
    while (turn > 90) turn -= 180;
    while (turn <= -90) turn += 180;
    worstTurn = Math.max(worstTurn, Math.abs(turn));
  }
  if (worstTurn > 0.05) {
    fail('pin kinematics', 'the pin-driven blade turns ' + worstTurn.toFixed(3) + ' deg on an IDEAL parallelogram - it must only translate');
  }

  // (2) ...and that translation must equal the PIN's displacement. THIS is what the end-based form fails.
  const theta = 50;
  const [pinAt] = pinMotion(P1, P2, M0, BR0, M0, BR0, theta);
  const pinShift = Math.hypot(pinAt[0] - M0[0], pinAt[1] - M0[1]);
  const [bladeAt] = pinMotion(P1, P2, M0, BR0, A0, B0, theta);
  const bladeShift = Math.hypot(bladeAt[0] - A0[0], bladeAt[1] - A0[1]);
  if (Math.abs(bladeShift - pinShift) > 1.0E-9) {
    fail('pin kinematics', 'the blade moves ' + bladeShift.toFixed(4) + ' m while its pin moves ' + pinShift.toFixed(4) + ' m - a rigid translation moves every point equally');
  }
  const cos = Math.cos(theta * DEG), sin = Math.sin(theta * DEG);
  const oldA = [P1[0] + (A0[0] - P1[0]) * cos - (A0[1] - P1[1]) * sin, P1[1] + (A0[0] - P1[0]) * sin + (A0[1] - P1[1]) * cos];
  const error = Math.abs(Math.hypot(oldA[0] - A0[0], oldA[1] - A0[1]) - pinShift);
  if (error < 0.01) {
    fail('pin kinematics', 'the end-based and pin-based forms agree to ' + (error * 1000).toFixed(1) +
      ' mm on this geometry, so nothing can tell them apart - move the pin further from the blade end');
  }
  note('pin kinematics: an ideal parallelogram translates the blade by the pin displacement (' + bladeShift.toFixed(4) +
    ' m); the end-based form would move it ' + Math.hypot(oldA[0] - A0[0], oldA[1] - A0[1]).toFixed(4) +
    ' m instead - a ' + (error * 1000).toFixed(0) + ' mm error, which is the bug this covers');

  // (3) Degeneration: with the pin AT the blade end the two forms must agree exactly - which is why every
  // existing fixture (pin == end) passes either way.
  let worstGap = 0;
  for (let step = 0; step <= 10; step++) {
    const rad = 50 * step / 10 * DEG, c = Math.cos(rad), s = Math.sin(rad);
    const [pinned] = pinMotion(P1, P2, A0, BR0, A0, B0, 50 * step / 10);
    const rotated = [P1[0] + (A0[0] - P1[0]) * c - (A0[1] - P1[1]) * s, P1[1] + (A0[0] - P1[0]) * s + (A0[1] - P1[1]) * c];
    worstGap = Math.max(worstGap, Math.hypot(pinned[0] - rotated[0], pinned[1] - rotated[1]));
  }
  if (worstGap > 1.0E-9) {
    fail('pin kinematics', 'with the pin at the blade end the two forms differ by ' + worstGap.toFixed(9) + ' m - the pin form must be a strict generalisation');
  }
  note('pin kinematics: with the pin at the blade end the pin form equals the rotation form to ' + (worstGap * 1e9).toFixed(3) + ' nm (strict generalisation)');
}

/** A clean, dependency-free re-derivation of the fitted sector, from the fan's own vertices. */
function fitSector(group, domain, vpos) {
  const counts = new Map();
  for (const f of group.faces) for (const i of new Set(f)) counts.set(i, (counts.get(i) || 0) + 1);
  let apexes = [...counts.entries()].filter(e => e[1] === group.faces.length).map(e => e[0]);
  if (apexes.length > 1) {
    const firsts = new Set(group.faces.map(f => f[0]));
    const conventional = firsts.size === 1 ? [...firsts][0] : null;
    if (conventional !== null && apexes.includes(conventional)) apexes = [conventional];
  }
  if (apexes.length !== 1) return { error: 'not a triangle fan (' + apexes.length + ' vertices shared by every face)' };

  const apex = apexes[0];
  const pivot = domain.toRightUp(vpos[apex]);
  const rim = [...new Set(group.faces.flat())].filter(i => i !== apex);
  if (rim.length < 2) return { error: 'fewer than two rim vertices' };

  let armM = 0, maxOffPlane = 0;
  const angles = [];
  for (const i of rim) {
    const point = vpos[i];
    const uv = domain.toRightUp(point);
    const dr = uv[0] - pivot[0], dh = uv[1] - pivot[1];
    armM = Math.max(armM, Math.hypot(dr, dh));
    maxOffPlane = Math.max(maxOffPlane, domain.offPlane(point));
    angles.push(Math.atan2(dh, dr) * 180 / Math.PI);
  }
  const sorted = [...angles].sort((a, b) => a - b);
  let gapSize = -Infinity, gapIndex = 0;
  for (let i = 0; i < sorted.length; i++) {
    const previous = i === 0 ? sorted[sorted.length - 1] - 360 : sorted[i - 1];
    const gap = sorted[i] - previous;
    if (gap > gapSize) { gapSize = gap; gapIndex = i; }
  }
  const parkAngleDeg = sorted[gapIndex];
  let sweepDeg = sorted[(gapIndex - 1 + sorted.length) % sorted.length] - parkAngleDeg;
  while (sweepDeg < 0) sweepDeg += 360;

  return {
    pivotU: 0.5 + pivot[0] / domain.widthM,
    pivotV: 0.5 + pivot[1] / domain.heightM,
    armM, parkAngleDeg, sweepDeg, maxOffPlane,
    rimInside: rim.every(i => {
      const uv = domain.toRightUp(vpos[i]);
      return Math.abs(uv[0]) <= domain.widthM / 2 + OFF_PLANE_TOL_M && Math.abs(uv[1]) <= domain.heightM / 2 + OFF_PLANE_TOL_M;
    })
  };
}

function main() {
  const args = process.argv.slice(2);
  const configIndex = args.indexOf('--config');
  const configPath = configIndex >= 0 ? args[configIndex + 1] : null;
  if (!configPath || !fs.existsSync(configPath)) {
    console.error('usage: node verify_windshield.js --config <vehicle config.json>');
    process.exit(2);
  }

  const config = loadConfig(configPath);
  const stage = config.stagingDir || path.join(path.dirname(config.sourceObj), '.pack_stage_' + config.id);
  const anchorFile = path.join(stage, 'assets', 'mtr', 'mmtr_anchors_' + config.id + '.json');
  const propertiesFile = path.join(stage, 'assets', 'mtr', 'properties_' + config.id + '.json');
  if (!fs.existsSync(anchorFile)) {
    console.error('anchor file not found: ' + anchorFile + '\n(run the packager on this config first)');
    process.exit(2);
  }

  const obj = L.parseObj(config.sourceObj);
  const vpos = L.transformVertices(obj.vpos, config);
  const data = JSON.parse(fs.readFileSync(anchorFile, 'utf8'));
  const anchors = data.anchors || [];
  const windshieldConfig = data.windshield || {};
  const groups = L.anchorsOf(obj);
  const groupByName = new Map(groups.map(g => [g.name.replace(/^mmtr_/i, ''), g]));
  // The solid wiper parts deliberately have NO anchor prefix (they are visible parts), so they must be
  // looked up in every group, not just the mmtr_* ones.
  const allGroupsByName = new Map(obj.groups.map(g => [g.name, g]));
  const byName = new Map(anchors.map(a => [a.name, a]));

  const groupsOf = new Map();
  const props = fs.existsSync(propertiesFile) ? JSON.parse(fs.readFileSync(propertiesFile, 'utf8')) : { parts: [] };
  (props.parts || []).forEach((part, index) => (part.names || []).forEach(name => groupsOf.set(name, index)));

  // Unit-level: the pin kinematics, on synthetic geometry no fixture covers yet.
  checkPinKinematics(fail, message => notes.push(message));

  console.log('config   : ' + configPath);
  console.log('source   : ' + config.sourceObj);
  console.log('anchors  : ' + anchorFile);
  console.log('');

  // ---- N1: the index rule ------------------------------------------------------------------------
  for (const anchor of anchors) {
    if (anchor.kind !== 'windshield' && anchor.kind !== 'wipersweep') continue;
    if (anchor.cab === null || anchor.cab === undefined) {
      fail('anchor ' + anchor.name, 'has no cab number');
    }
  }
  const INDEX_RULE = [
    ['windshield_1', 1, 1], ['windshield_1_2', 1, 2], ['windshield_2', 2, 1],
    ['windshield_2_3', 2, 3], ['wipersweep_1_1', 1, 1], ['wipersweep_2_3', 2, 3]
  ];
  for (const [name, cab, pane] of INDEX_RULE) {
    const group = groupByName.get(name);
    if (!group) continue;   // this fixture simply does not contain it
    const anchor = byName.get(name);
    if (!anchor) { fail('anchor ' + name, 'is in the OBJ but not in the anchor JSON'); continue; }
    if (anchor.cab !== cab || (anchor.pane || 1) !== pane) {
      fail('anchor ' + name, 'parsed as cab=' + anchor.cab + ' pane=' + (anchor.pane || 1) + ', expected cab=' + cab + ' pane=' + pane +
        (pane === 1 && anchor.cab !== cab ? '  <- a lone index is the CAB, not the pane' : ''));
    }
  }

  // ---- N2/S1/S2: every sweep, against its glass ---------------------------------------------------
  const fittedGlasses = new Set();
  for (const sweep of anchors.filter(a => a.kind === 'wipersweep')) {
    const scope = 'sweep ' + sweep.name;
    const pane = sweep.pane || 1;
    const glassName = 'windshield_' + sweep.cab + '_' + pane;
    const glass = byName.get(glassName);
    if (!glass) { fail(scope, 'has no matching ' + glassName); continue; }
    const sweepGroup = groupByName.get(sweep.name);
    const glassGroup = groupByName.get(glassName);
    if (!sweepGroup) { fail(scope, 'no mmtr_' + sweep.name + ' object in the source OBJ'); continue; }
    if (!glassGroup) { fail(scope, 'no mmtr_' + glassName + ' object in the source OBJ'); continue; }
    if (glass.faces && glass.faces.length > 1) {
      notes.push(scope + ': skipped - a CURVED/folded glass needs the unrolled domain (W3); the fit for those is not verified yet');
      continue;
    }

    const domain = glassDomain(glassGroup, vpos);
    const values = windshieldConfig[glassName] || {};
    if (values.wiper !== true) fail(scope, 'its glass does not have wiper=true (got ' + JSON.stringify(values.wiper) + ')');
    const near = (a, b, tol) => a !== undefined && Math.abs(a - b) <= tol;

    // The fan is the region the BLADE sweeps, so what it can be checked against is the modelled blade and
    // the modelled arm - not against a sector about its own apex, which is a VIRTUAL centre (metres away
    // from the glass when the fan is the slight sliver a real train linkage draws).
    const bladeForFit = allGroupsByName.get('wiper_' + sweep.cab + '_' + pane);
    const solved = bladeForFit
      ? solveStrokeFromFan(sweepGroup, bladeForFit, allGroupsByName.get('wiperarm_' + sweep.cab + '_' + pane),
        allGroupsByName.get('wiperrod_' + sweep.cab + '_' + pane), domain, vpos)
      : { error: 'no modelled blade, so the fan cannot be interpreted as a swept region - the legacy sector fit is not checked here' };
    if (solved.error) {
      fail(scope, solved.error);
    } else {
      const pivotU = 0.5 + solved.pivot1[0] / domain.widthM;
      const pivotV = 0.5 + solved.pivot1[1] / domain.heightM;
      const reach = Math.max(Math.hypot(solved.a0[0] - solved.pivot1[0], solved.a0[1] - solved.pivot1[1]),
        Math.hypot(solved.b0[0] - solved.pivot1[0], solved.b0[1] - solved.pivot1[1]));
      if (!near(values.pivotU, pivotU, FRACTION_TOL)) fail(scope, 'pivotU ' + values.pivotU + ' != the spindle re-derived from the arm (' + pivotU.toFixed(4) + ')');
      if (!near(values.pivotV, pivotV, FRACTION_TOL)) fail(scope, 'pivotV ' + values.pivotV + ' != the spindle re-derived from the arm (' + pivotV.toFixed(4) + ')');
      if (!near(values.armM, reach, ARM_TOL_M)) fail(scope, 'armM ' + values.armM + ' != the blade reach ' + reach.toFixed(4));
      if (!near(values.parkAngleDeg, solved.parkAngleDeg, ANGLE_TOL_DEG)) fail(scope, 'parkAngleDeg ' + values.parkAngleDeg + ' != the modelled park direction ' + solved.parkAngleDeg.toFixed(3));
      if (!near(values.sweepDeg, solved.strokeDeg, ANGLE_TOL_DEG)) fail(scope, 'sweepDeg ' + values.sweepDeg + ' != the stroke solved from the fan (' + solved.strokeDeg.toFixed(3) + ')');
      if ((values.sweepSign || 1) !== solved.sweepSign) fail(scope, 'sweepSign ' + values.sweepSign + ' != solved ' + solved.sweepSign);
      notes.push(scope + ' -> ' + glassName + ': spindle (' + pivotU.toFixed(4) + ', ' + pivotV.toFixed(4) + ') arm ' + reach.toFixed(3) +
        ' m, park ' + solved.parkAngleDeg.toFixed(2) + ' deg, stroke solved ' + solved.strokeDeg.toFixed(2) +
        ' deg (blade on the fan edge to ' + (solved.onEdge * 1000).toFixed(1) + ' mm)');
    }
    fittedGlasses.add(glassName);

    // ---- M1/M2: the mechanism ------------------------------------------------------------------
    const bladeGroup = allGroupsByName.get('wiper_' + sweep.cab + '_' + pane);
    if (!bladeGroup) {
      notes.push(scope + ': no modelled blade, so the client draws its own (nothing to verify)');
      continue;
    }
    const bladeEnds = barEnds(bladeGroup, domain, vpos);
    const pivot1 = [(values.pivotU - 0.5) * domain.widthM, (values.pivotV - 0.5) * domain.heightM];
    const dA = Math.hypot(bladeEnds[0][0] - pivot1[0], bladeEnds[0][1] - pivot1[1]);
    const dB = Math.hypot(bladeEnds[1][0] - pivot1[0], bladeEnds[1][1] - pivot1[1]);
    const a0 = dA <= dB ? bladeEnds[0] : bladeEnds[1];
    const b0 = dA <= dB ? bladeEnds[1] : bladeEnds[0];

    const fittedA = [(values.bladeAU - 0.5) * domain.widthM, (values.bladeAV - 0.5) * domain.heightM];
    const fittedB = [(values.bladeBU - 0.5) * domain.widthM, (values.bladeBV - 0.5) * domain.heightM];
    const pairGap = Math.min(
      Math.hypot(fittedA[0] - a0[0], fittedA[1] - a0[1]) + Math.hypot(fittedB[0] - b0[0], fittedB[1] - b0[1]),
      Math.hypot(fittedA[0] - b0[0], fittedA[1] - b0[1]) + Math.hypot(fittedB[0] - a0[0], fittedB[1] - a0[1]));
    if (!(pairGap <= 2 * FRACTION_TOL * Math.max(domain.widthM, domain.heightM) + ARM_TOL_M)) {
      fail(scope, 'blade ends were written as (' + fittedA.map(x => x.toFixed(3)) + ')/(' + fittedB.map(x => x.toFixed(3)) +
        ') but the modelled blade is (' + a0.map(x => x.toFixed(3)) + ')/(' + b0.map(x => x.toFixed(3)) + ')');
      continue;
    }

    const rodGroup = allGroupsByName.get('wiperrod_' + sweep.cab + '_' + pane);
    let pivot2 = pivot1.slice();
    if (rodGroup) {
      const rodEnds = barEnds(rodGroup, domain, vpos);
      const near0 = distanceToSegment(rodEnds[0], a0, b0);
      const near1 = distanceToSegment(rodEnds[1], a0, b0);
      pivot2 = near0 <= near1 ? rodEnds[1] : rodEnds[0];
      if (values.pivot2U === undefined || values.pivot2V === undefined) {
        fail(scope, 'the model has a ' + rodGroup.name + ' but no pivot2 was written, so the blade would be rotated as if single-axis');
      } else {
        const fitted2 = [(values.pivot2U - 0.5) * domain.widthM, (values.pivot2V - 0.5) * domain.heightM];
        if (Math.hypot(fitted2[0] - pivot2[0], fitted2[1] - pivot2[1]) > ARM_TOL_M) {
          fail(scope, 'pivot2 ' + fitted2.map(x => x.toFixed(3)) + ' != re-derived ' + pivot2.map(x => x.toFixed(3)));
        }
      }
    } else if (values.pivot2U !== undefined) {
      fail(scope, 'wrote a pivot2 but the model has no wiperrod_, so the second pivot is unexplained');
    }

    // M2: the two degenerate cases, evaluated with the CLIENT'S OWN formula over the whole stroke.
    // The reference is the direction AT PARK: the stroke starts there, so measuring from theta = 0 would
    // fold the park angle into the drift and "prove" a perfectly correct wiper wrong.
    const park = values.parkAngleDeg;
    const sweepDeg = values.sweepDeg;
    const referenceLength = Math.hypot(b0[0] - a0[0], b0[1] - a0[1]);
    let referenceDirection = null;
    let maxDirectionDrift = 0, maxLengthDrift = 0;
    for (let step = 0; step <= 20; step++) {
      const theta = park + sweepDeg * step / 20;
      const [a, b] = bladeSegment(pivot1, pivot2, a0, b0, theta);
      const direction = Math.atan2(b[1] - a[1], b[0] - a[0]) * 180 / Math.PI;
      if (referenceDirection === null) referenceDirection = direction;
      // A blade is a LINE: 180 degrees apart is the same direction, so unwrap into (-90, 90].
      let drift = direction - referenceDirection;
      while (drift > 90) drift -= 180;
      while (drift <= -90) drift += 180;
      maxDirectionDrift = Math.max(maxDirectionDrift, Math.abs(drift));
      maxLengthDrift = Math.max(maxLengthDrift, Math.abs(Math.hypot(b[0] - a[0], b[1] - a[1]) - referenceLength));
    }
    const coaxial = Math.hypot(pivot2[0] - pivot1[0], pivot2[1] - pivot1[1]) < 1.0E-6;
    if (coaxial) {
      // P1 == P2 must reproduce a rigid rotation: the blade's direction turns by exactly theta, and its
      // length never changes. If this drifts, the formula is not the degenerate case it claims to be.
      if (Math.abs(maxDirectionDrift - sweepDeg) > 0.05) {
        fail(scope, 'single-axis (one pivot) but the blade direction drifts ' + maxDirectionDrift.toFixed(3) +
          ' deg over a ' + sweepDeg.toFixed(3) + ' deg stroke - it must turn by exactly the stroke');
      }
      if (maxLengthDrift > 1.0E-6) fail(scope, 'single-axis but the blade length changes by ' + maxLengthDrift.toFixed(6) + ' m');
      const reach = Math.max(Math.hypot(a0[0] - pivot1[0], a0[1] - pivot1[1]), Math.hypot(b0[0] - pivot1[0], b0[1] - pivot1[1]));
      if (Math.abs(reach - values.armM) > 0.03) {
        fail(scope, 'sector radius armM ' + values.armM + ' does not reach the blade (' + reach.toFixed(3) +
          ' m), so the wiped sector would stop short of what the blade actually touches');
      }
      notes.push(scope + ': single-axis, blade turns by exactly the stroke (' + maxDirectionDrift.toFixed(2) + ' deg), reach ' + reach.toFixed(3) + ' m');
    } else {
      const linkA = [a0[0] - pivot1[0], a0[1] - pivot1[1]];
      const linkB = [b0[0] - pivot2[0], b0[1] - pivot2[1]];
      const residual = Math.hypot(linkA[0] - linkB[0], linkA[1] - linkB[1]);
      if (residual < 0.005) {
        // Equal link vectors MUST give a blade that never turns: B - A is constant.
        if (maxDirectionDrift > 0.05) {
          fail(scope, 'equal link vectors (residual ' + residual.toFixed(5) + ' m) but the blade still turns ' +
            maxDirectionDrift.toFixed(3) + ' deg - the parallelogram case is not behaving like one');
        }
        // THE DEGENERATION PROOF for "parallel double link": with equal link vectors the FOUR-BAR loop
        // closure has to return exactly the rigid rotation - that is what makes a parallelogram a special
        // case of the general linkage rather than a separate code path.
        let worst = 0;
        for (let step = 0; step <= 20; step++) {
          const theta = park + sweepDeg * step / 20;
          const rounded = [
            [pivot1[0] + Math.cos(theta * Math.PI / 180) * (a0[0] - pivot1[0]) - Math.sin(theta * Math.PI / 180) * (a0[1] - pivot1[1]),
             pivot1[1] + Math.sin(theta * Math.PI / 180) * (a0[0] - pivot1[0]) + Math.cos(theta * Math.PI / 180) * (a0[1] - pivot1[1])],
            [pivot2[0] + Math.cos(theta * Math.PI / 180) * (b0[0] - pivot2[0]) - Math.sin(theta * Math.PI / 180) * (b0[1] - pivot2[1]),
             pivot2[1] + Math.sin(theta * Math.PI / 180) * (b0[0] - pivot2[0]) + Math.cos(theta * Math.PI / 180) * (b0[1] - pivot2[1])]
          ];
          const solved = bladeSegment(pivot1, pivot2, a0, b0, theta);
          worst = Math.max(worst, Math.hypot(solved[1][0] - rounded[1][0], solved[1][1] - rounded[1][1]));
        }
        if (worst > 1.0E-6) {
          fail(scope, 'the loop closure differs from the rigid rotation by ' + worst.toFixed(6) + ' m on a parallelogram - the degenerate case is not degenerate');
        }
        notes.push(scope + ': ideal parallelogram (link residual ' + residual.toFixed(5) + ' m), blade direction constant to ' +
          maxDirectionDrift.toFixed(3) + ' deg, loop closure = rigid rotation to ' + (worst * 1e6).toFixed(2) + ' um');
      } else {
        // A REAL train linkage is an imperfect parallelogram: it is a SLIGHT FAN. The blade must turn a
        // little (that is the fan) and must still turn far less than the arm, otherwise "parallel
        // linkage" is the wrong description of the mechanism and it wants re-modelling or a single-axis
        // sector instead.
        if (maxDirectionDrift < 0.2) {
          fail(scope, 'link vectors differ by ' + residual.toFixed(4) + ' m but the blade does not turn at all - ' +
            'the linkage is not doing anything, check the rod attachment');
        }
        if (maxDirectionDrift > sweepDeg * 0.5) {
          fail(scope, 'the blade turns ' + maxDirectionDrift.toFixed(2) + ' deg over a ' + sweepDeg.toFixed(2) +
            ' deg stroke, i.e. it is not a linkage at all - model it as a single-axis wiper (drop the rod)');
        }
        notes.push(scope + ': slight fan - the blade turns ' + maxDirectionDrift.toFixed(2) + ' deg while the arm sweeps ' +
          sweepDeg.toFixed(2) + ' deg (link residual ' + residual.toFixed(4) + ' m), so the cleared region opens like a narrow sector');
      }
    }

    // M3: the WIPED BAND, only where the client actually uses it. Two properties, either of which a
    // rotated or mirrored quad test would break:
    //   (a) every position the blade passes through during the step is inside the band;
    //   (b) a point well off to the side of the blade's travel is not.
    //
    // Only the TWO-PIVOT case is checked here, and that is not laziness: a single-pivot blade turns, so
    // the straight edges of the quad cut the corner off the arc it really sweeps (by R*(1-cos) over the
    // step). The client therefore uses the exact angular sector for one pivot and this band for two, and
    // asserting the band on a rotating blade would demand something the client deliberately does not do.
    if (!coaxial) {
      const fromTheta = park + sweepDeg * 0.30;
      const toTheta = park + sweepDeg * 0.40;
      const from = bladeSegment(pivot1, pivot2, a0, b0, fromTheta);
      const to = bladeSegment(pivot1, pivot2, a0, b0, toTheta);
      let pathMisses = 0;
      for (let step = 0; step <= 4; step++) {
        const theta = fromTheta + (toTheta - fromTheta) * step / 4;
        const [a, b] = bladeSegment(pivot1, pivot2, a0, b0, theta);
        for (const point of [a, b, [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2]]) {
          if (bandFactor(point[0], point[1], from, to) <= 0) pathMisses++;
        }
      }
      if (pathMisses > 0) {
        fail(scope, pathMisses + ' point(s) the blade actually passed through are NOT inside the wiped band - the band test is rotated or mirrored against the blade');
      }
      // A point half a metre to the side of the blade, measured perpendicular to it, must stay unwiped.
      const midTo = [(to[0][0] + to[1][0]) / 2, (to[0][1] + to[1][1]) / 2];
      const along = [to[1][0] - to[0][0], to[1][1] - to[0][1]];
      const alongLength = Math.hypot(along[0], along[1]) || 1;
      const beside = [midTo[0] - along[1] / alongLength * 0.5, midTo[1] + along[0] / alongLength * 0.5];
      if (bandFactor(beside[0], beside[1], from, to) > 0) {
        fail(scope, 'a point 0.5 m to the side of the blade is counted as wiped - the band is far too wide');
      }
      notes.push(scope + ': band covers the blade\'s whole path over the step, and 0.5 m to the side stays unwiped');
    } else {
      notes.push(scope + ': one pivot, so the client uses the exact angular sector (the band test would cut the arc corner)');
    }
  }

  // ---- N2 the other way: a glass with a fan must HAVE a fit ---------------------------------------
  for (const [glassName, values] of Object.entries(windshieldConfig)) {
    if (fittedGlasses.has(glassName)) continue;
    if (values.pivotU !== undefined || values.sweepDeg !== undefined) {
      fail('glass ' + glassName, 'carries fitted sweep fields but no wipersweep fan matches it');
    }
  }

  // ---- S3: the solid wiper -------------------------------------------------------------------------
  // Read from EVERY group, not just the mmtr_* anchors: the solid wiper deliberately has no anchor
  // prefix (it is a visible part, not data).
  const modelledWipers = obj.groups.map(g => g.name).filter(name => /^wiper_\d+_\d+$/i.test(name));
  for (const wiper of modelledWipers) {
    const match = /^wiper_(\d+)_(\d+)$/i.exec(wiper);
    const glassName = 'windshield_' + Number(match[1]) + '_' + Number(match[2]);
    const values = windshieldConfig[glassName];
    if (!values) { fail('part ' + wiper, 'has no ' + glassName + ' to be the wiper of'); continue; }
    if (values.drawBlade !== false) {
      fail('part ' + wiper, 'exists but ' + glassName + ' does not set drawBlade=false, so the drawn blade would sit on top of the modelled one');
    }
    if (values.wiper === false) {
      fail('part ' + wiper, glassName + ' sets wiper=false, which would stop the glass being wiped at all (a solid wiper replaces the BLADE, not the wipe)');
    }
    if (!groupsOf.has(wiper)) {
      fail('part ' + wiper, 'is in the OBJ but not in the packed properties file, so it would never render');
    }
  }
  // ...and no glass other than those may have drawBlade=false.
  for (const [glassName, values] of Object.entries(windshieldConfig)) {
    if (values.drawBlade === false && !modelledWipers.some(w => ('windshield_' + w.split('_')[1] + '_' + w.split('_')[2]) === glassName)) {
      fail('glass ' + glassName, 'sets drawBlade=false but the model has no solid wiper for it - the blade would simply vanish');
    }
  }

  console.log('-- notes --');
  notes.forEach(n => console.log('  ' + n));
  console.log('');

  if (failures.length) {
    console.log('FAIL (' + failures.length + ')');
    failures.forEach(f => console.log('  ! ' + f));
    process.exit(1);
  }
  console.log('PASS: ' + anchors.filter(a => a.kind === 'windshield').length + ' glass(es), ' +
    fittedGlasses.size + ' fitted sweep(s), ' + modelledWipers.length + ' solid wiper(s)');
}

main();
