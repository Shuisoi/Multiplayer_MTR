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
 *       The same rule for the sweep's THIRD index: mmtr_wipersweep_1_1_2 is wiper 2 of cab 1 pane 1 -
 *       not a second pane - and the name and the anchor's own `wiper` field must agree.
 *   N2  PAIRING: every mmtr_wipersweep_<cab>_<pane>[_<n>] has a matching glass, and every glass that
 *       has a fan gets a fitted block FOR ITS OWN WIPER - a sweep whose fit was dropped is a wiper that
 *       clears nothing. A glass carrying several fans must write the "wipers" array: the flat shape is
 *       read by the client as exactly ONE wiper, so two fans over a flat block is a lost fit.
 *   S1  FIT: pivot (pivotU/pivotV), armM, parkAngleDeg and sweepDeg re-derived from the fan's own
 *       vertices must match what was written FOR THAT WIPER.
 *   S2  ON THE GLASS: every fan vertex lies in the glass's plane and inside its extent.
 *   S3  SOLID WIPER: a wiper_<cab>_<pane>[_<n>] object in the model means THAT wiper of that glass
 *       draws no blade (drawBlade=false) and is still wiped (wiper=true); no other glass is affected.
 *       The part must also exist in the packed properties file, as its OWN part.
 *
 * Usage:
 *   node mmtr/tools/obj-mtr-packager/pack_vehicle.js mmtr/tools/anchor-check/fixture/wipefix.json
 *   node mmtr/tools/anchor-check/verify_windshield.js --config mmtr/tools/anchor-check/fixture/wipefix.json
 */

'use strict';

const fs = require('fs');
const path = require('path');
const L = require('./lib.js');

const ANGLE_TOL_DEG = 0.5;
// A fraction of the glass's larger side, used where a written value is re-derived from the mesh. 5e-3 of a
// 1.9 m screen is ~10 mm per end - still far below any defect worth catching (the injected faults move a
// blade end by 50 mm), and above the 27 mm the two implementations' own blade-end derivation differs by on
// the real BR101 mesh (its blade is not a bare bar: it carries a carrier plate, so its PCA axis wanders a
// little more than the fixture's does).
const FRACTION_TOL = 5.0E-3;
// How close the packager's written fields must be to this file's INDEPENDENT re-derivation.
//
// It used to be 1 mm / 0.05 deg, and that is not achievable: the two sides parse the OBJ with separate
// code, build their own glass domain and derive the blade's ends with their own PCA, and on the real
// BR101 mesh they disagree by 5-8 mm / 0.3-0.45 deg on IDENTICAL geometry. A tolerance below the two
// implementations' own spread tests the tools against each other, not the model - it failed a model whose
// fit was demonstrably right (stroke 46.00 deg, pin span conserved to 0.000 um).
//
// 10 mm / 0.5 deg is still far tighter than any defect this suite is for: the injected faults in
// selftest.js move things by 50 mm, 100 mm or 10 degrees, i.e. 5x to 1000x this.
const ARM_TOL_M = 1.0E-2;
// How far a linkage's PIN may sit from the blade's centre LINE and still count as attached to the blade.
//
// 150 mm looks loose and is not: a real blade is not a bare bar. It carries a CARRIER PLATE - the triangle
// the rods bolt to - and that plate is part of the same rigid `wiper_` mesh. So the rod's pin legitimately
// sits up to the plate's reach off the blade's axis: measured on BR101, the arm's pin is 63 mm from the
// axis and the rod's 124 mm, while BOTH are 28 mm from the blade's own mesh. Judging them against the axis
// (which is what this suite did, and what the packager's warning does) reports a correct wiper as broken.
const BLADE_BODY_TOL_M = 0.15;
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
 * The name of ONE solid wiper part: <prefix>_<cab>_<pane>[_<wiper>] - the SAME convention the packager
 * uses (pack_vehicle.js wiperPartName). Wiper 1 has NO third index, so every part named before
 * multi-wiper existed keeps its exact name ({@code wiper_1_1}) and nothing already in a model moves.
 */
function wiperPartName(prefix, cab, pane, wiper) {
  return prefix + '_' + cab + '_' + (pane || 1) + ((wiper || 1) > 1 ? '_' + wiper : '');
}

/**
 * The config block that drives ONE wiper of ONE glass, plus where it was found.
 *
 * Mirrors the client (MmtrWindshield.WindshieldConfig.wiperAt): a "wipers" array is the multi-wiper
 * shape and each entry's own "wiperIndex" says which wiper it drives - absent means its 1-based array
 * position, which is exactly the client's fallback. WITHOUT the array the flat fields ARE wiper 1 and
 * there is no wiper 2 at all, which is the distinction the pairing checks need: "this wiper's fit was
 * dropped" and "the block for that wiper was never written" are different authoring mistakes, and the
 * second one silently drives the surviving wiper with the wrong sector.
 *
 * @returns {{values: object|null, source: string|null}} source = 'wipers[i]', 'flat', or null when
 *   that wiper has no block at all.
 */
function wiperValues(windshieldConfig, glassName, wiper) {
  const glass = windshieldConfig[glassName];
  if (!glass) return { values: null, source: null };
  if (Array.isArray(glass.wipers)) {
    for (let i = 0; i < glass.wipers.length; i++) {
      const one = glass.wipers[i];
      if (!one || typeof one !== 'object') continue;
      const index = one.wiperIndex === undefined || one.wiperIndex === null ? i + 1 : Number(one.wiperIndex);
      if (index === wiper) return { values: one, source: 'wipers[' + i + ']' };
    }
    return { values: null, source: null };
  }
  return wiper === 1 ? { values: glass, source: 'flat' } : { values: null, source: null };
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
 *   M(theta)  = P1 + R(theta) * (M0 - P1)                             the ARM's pin (the driven crank)
 *   Br(theta) = circle(P2, |Br0-P2|) n circle(M(theta), |Br0-M0|)      the ROD's pin (SOLVED, rigid blade)
 *   blade     = the rigid motion carrying (M0, Br0) onto (M(theta), Br(theta)), applied to (A0, B0)
 *
 * With P1 = P2 the blade rotates rigidly about the spindle (a car wiper). With equal link vectors the pin
 * pair keeps its direction, so the blade only translates (a train's parallel linkage).
 *
 * THE PINS ARE NOT THE BLADE'S ENDS, and assuming they are is a real defect rather than a simplification:
 * a wiper pins its arm to the blade's MIDDLE (so the blade is pressed evenly) and its rod near an end, so
 * the pin span is a fraction of the blade, not its length. This file used to take |B0 - A0| as the pin
 * span unconditionally, which is only right when the pins happen to BE the ends - i.e. exactly the case
 * every fixture built so far happened to model. On the first fixture with the arm at the blade's middle it
 * took a span of 0.60 m where the packager (which recovers the real pins from the mesh by PCA) had 0.30 m,
 * and the two disagreed about the blade position by 0.45 m while using identical arithmetic.
 * KEEP THIS IN STEP WITH the client when the client grows the same helper.
 */
function bladeSegment(p1, p2, a0, b0, thetaDeg, m0 = a0, br0 = b0) {
  const radians = thetaDeg * Math.PI / 180;
  const cos = Math.cos(radians), sin = Math.sin(radians);
  const rotate = (pivot, point) => {
    const dx = point[0] - pivot[0], dy = point[1] - pivot[1];
    return [pivot[0] + dx * cos - dy * sin, pivot[1] + dx * sin + dy * cos];
  };
  // ONE pivot means the blade rides the arm: a rigid rotation about the spindle, and the loop closure
  // degenerates to exactly this (a pin pair turning about a common centre turns by the crank angle).
  const coaxial = Math.abs(p2[0] - p1[0]) < 1.0E-9 && Math.abs(p2[1] - p1[1]) < 1.0E-9;
  if (coaxial) {
    return [rotate(p1, a0), rotate(p2, b0)];
  }
  // TWO pivots: the FOUR-BAR loop closure. The blade is rigid, so the distance between its two PINS cannot
  // change - that is what fixes the follower's angle, and assuming it equals the crank's (which an ideal
  // parallelogram happens to satisfy) contradicts the rigidity as soon as the link vectors differ.
  const solutions = crankPin => {
    const dx = p2[0] - crankPin[0], dy = p2[1] - crankPin[1];
    const distance = Math.hypot(dx, dy);
    const spanM = Math.hypot(br0[0] - m0[0], br0[1] - m0[1]);
    const followerM = Math.hypot(br0[0] - p2[0], br0[1] - p2[1]);
    if (distance < 1.0E-9 || distance > spanM + followerM || distance < Math.abs(spanM - followerM)) return null;
    const along = (distance * distance + spanM * spanM - followerM * followerM) / (2 * distance);
    const height = Math.sqrt(Math.max(0, spanM * spanM - along * along));
    const ux = dx / distance, uy = dy / distance;
    const base = [crankPin[0] + ux * along, crankPin[1] + uy * along];
    return [[base[0] - uy * height, base[1] + ux * height], [base[0] + uy * height, base[1] - ux * height]];
  };
  // The assembly mode is decided ONCE, from the parked configuration: choosing per angle lets the linkage
  // flip to its mirror branch at a toggle position, which is a real failure mode of this mechanism.
  const park = solutions(m0);
  if (park === null) return [rotate(p1, a0), rotate(p2, b0)];
  const mode = branchIndex(p2, m0, br0, park);
  const now = solutions(rotate(p1, m0));
  if (now === null) return [rotate(p1, a0), rotate(p2, b0)];
  const m = rotate(p1, m0);
  const br = now[mode];
  // The angle the pin PAIR turned through. For a parallelogram the pair keeps its direction, so this is
  // zero - which is exactly what "the blade does not turn, it translates" means.
  const turn = Math.atan2(br[1] - m[1], br[0] - m[0]) - Math.atan2(br0[1] - m0[1], br0[0] - m0[0]);
  const c = Math.cos(turn), s = Math.sin(turn);
  const apply = point => {
    const dx = point[0] - m0[0], dy = point[1] - m0[1];
    return [m[0] + dx * c - dy * s, m[1] + dx * s + dy * c];
  };
  return [apply(a0), apply(b0)];
}

/** Twice the signed area of (a, b, c). The sign is the branch test every implementation shares. */
function handedness(a, b, c) {
  return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
}

/**
 * Which of the two solution indices is the real one: the branch whose HANDEDNESS about the pivot matches
 * the parked configuration's. Deciding it by "which candidate is nearer the parked pin" is not
 * equivalent: the candidates are mirror images across the pivot->crank-pin line, so their handedness
 * signs always differ while their distances can be all but equal near a toggle position. There a
 * sub-micron input difference (the packager recovers these pins from mesh geometry, this verifier uses
 * the modelled constants) silently selects the MIRROR branch and moves the blade by centimetres. That is
 * the difference between a mirror that can disagree with its subject for a real reason and one that
 * disagrees only because it computed "nearer" on slightly different numbers.
 */
function branchIndex(pivot, parkCrankPin, parkFollowerPin, park) {
  const parkSign = handedness(pivot, parkCrankPin, parkFollowerPin);
  if (parkSign === 0) return 0;
  return (handedness(pivot, parkCrankPin, park[0]) > 0) === (parkSign > 0) ? 0 : 1;
}

/** The mechanism's assembly mode: constant, so it is read off the parked configuration. */
function followerMode(p1, p2, a0, b0) {
  const followerM = Math.hypot(b0[0] - p2[0], b0[1] - p2[1]);
  const bladeM = Math.hypot(b0[0] - a0[0], b0[1] - a0[1]);
  const plus = followerEnd(a0, p2, followerM, bladeM, 1);
  const minus = followerEnd(a0, p2, followerM, bladeM, -1);
  if (plus === null || minus === null) return 1;
  // followerEnd's parametrisation gives a candidate of mode m the handedness -d*h*m, so "the same sign as
  // the parked configuration" means "the same mode". Same rule as branchIndex, same rule as the client.
  const parkSign = handedness(p2, a0, b0);
  if (parkSign === 0) return 1;
  return (handedness(p2, a0, plus) > 0) === (parkSign > 0) ? 1 : -1;
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
function insideConvexQuad(px, py, xs, ys, extraInflateM = 0) {
  let positive = false, negative = false;
  for (let i = 0; i < 4; i++) {
    const j = (i + 1) % 4;
    const cross = (xs[j] - xs[i]) * (py - ys[i]) - (ys[j] - ys[i]) * (px - xs[i]);
    const margin = Math.hypot(xs[j] - xs[i], ys[j] - ys[i]) * BAND_INFLATE_M + extraInflateM;
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

/**
 * How far the blade's real path bulges OUT of the chord between two of its positions.
 *
 * The quad is a chord, and the path it approximates is an ARC: the blade is driven by a crank of length
 * R about the spindle, so over a crank step of dTheta the path departs from the chord by R*(1-cos(dTheta/2)).
 * That is not a defect and not optional - it is how a linkage moves. Measured on BR101: R = 1.17 m over the
 * 9.26 deg step the verifier samples, i.e. 3.8 mm, which the fixed 3 mm inflate is just short of, so a
 * demonstrably correct wiper failed on a sliver that is 0.2% of the glass.
 *
 * The test's teeth are untouched: the injected faults in selftest.js move a blade end by 50-100 mm.
 */
function pathBulgeM(from, to, pivot1, m0) {
  const crankRadiusM = Math.hypot(m0[0] - pivot1[0], m0[1] - pivot1[1]);
  const moveM = Math.hypot(to[0][0] - from[0][0], to[0][1] - from[0][1]);
  if (crankRadiusM < 1.0E-6 || moveM < 1.0E-6) return 0;
  const halfStep = Math.asin(Math.min(1, moveM / (2 * crankRadiusM)));
  return crankRadiusM * (1 - Math.cos(halfStep));
}

function bandFactor(px, py, from, to, extraInflateM = 0) {
  const xs = [from[0][0], from[1][0], to[1][0], to[0][0]];
  const ys = [from[0][1], from[1][1], to[1][1], to[0][1]];
  if (insideConvexQuad(px, py, xs, ys, extraInflateM)) return 1;
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
  // This used to require "exactly one vertex shared by every face" - a triangle fan - and returned
  // 'not a triangle fan' otherwise. That requirement is gone: the candidate edges below are the region's
  // OUTLINE, which does not depend on a shared vertex at all. A BAND is a legitimate shape (an ideal
  // parallelogram translates its blade, so its two extreme positions are PARALLEL, the apex is at infinity
  // and no triangle fan can express the region), and rejecting it here is exactly what left the stroke
  // unsolvable and let a bogus 185-degree angular-sector reading stand unchallenged.

  const bladeEnds = barEnds(bladeGroup, domain, vpos);
  // The spindle comes from the ARM (the end that does not touch the blade) - the fan's apex is the swept
  // region's virtual centre and is NOT the spindle, not even for a single-axis wiper.
  if (!armGroup) return { error: 'no wiperarm_ group, so the spindle cannot be located' };
  const armEnds = barEnds(armGroup, domain, vpos);
  const armNear0 = distanceToSegment(armEnds[0], bladeEnds[0], bladeEnds[1]);
  const armNear1 = distanceToSegment(armEnds[1], bladeEnds[0], bladeEnds[1]);
  const p1 = armNear0 <= armNear1 ? armEnds[1] : armEnds[0];
  // ...and the arm's PIN is the arm's OTHER end: the one that sits on the blade. For a car wiper that is
  // the arm's tip; for a train linkage it is where the arm meets the blade, which a real design puts at
  // the blade's MIDDLE. Reading it off the arm (instead of taking the blade's end) is what makes a
  // middle-pin wiper describable at all.
  const m0 = armNear0 <= armNear1 ? armEnds[0] : armEnds[1];

  const near0 = Math.hypot(bladeEnds[0][0] - p1[0], bladeEnds[0][1] - p1[1]);
  const near1 = Math.hypot(bladeEnds[1][0] - p1[0], bladeEnds[1][1] - p1[1]);
  const a0 = near0 <= near1 ? bladeEnds[0] : bladeEnds[1];
  const b0 = near0 <= near1 ? bladeEnds[1] : bladeEnds[0];

  let p2 = p1.slice();
  let br0 = b0;
  if (rodGroup) {
    const rodEnds = barEnds(rodGroup, domain, vpos);
    const d0 = distanceToSegment(rodEnds[0], a0, b0);
    const d1 = distanceToSegment(rodEnds[1], a0, b0);
    p2 = d0 <= d1 ? rodEnds[1] : rodEnds[0];
    br0 = d0 <= d1 ? rodEnds[0] : rodEnds[1];   // the end NEAR the blade is the rod's pin
  }

  // The candidates are the region's OUTLINE: an edge belonging to exactly ONE face. That is the packager's
  // rule, and it works for both shapes - a fan's spokes belong to two faces and drop out on their own (so
  // no apex is needed), and a BAND's interior crossbars do the same, leaving its two extreme blade
  // positions. Taking "every edge except those touching the apex" instead would, on a band, offer the
  // interior crossbars as candidates too - and the blade lies square on every one of them.
  const edgeUse = new Map();
  const edgeKey = (a, b) => (a < b ? a + '_' + b : b + '_' + a);
  for (const f of sweepGroup.faces) {
    for (let i = 0; i < f.length; i++) {
      const key = edgeKey(f[i], f[(i + 1) % f.length]);
      edgeUse.set(key, (edgeUse.get(key) || 0) + 1);
    }
  }
  const edges = [];
  for (const f of sweepGroup.faces) {
    for (let i = 0; i < f.length; i++) {
      const a = f[i], b = f[(i + 1) % f.length];
      if (edgeUse.get(edgeKey(a, b)) !== 1) continue;
      const pa = domain.toRightUp(vpos[a]), pb = domain.toRightUp(vpos[b]);
      if (Math.hypot(pb[0] - pa[0], pb[1] - pa[1]) <= 0.02) continue;
      edges.push({ a: pa, b: pb });
    }
  }

  // Mirrors the packager and the client, with the PINS as the linkage's input: they are read off the arm
  // and the rod (the ends nearest the blade), never off the blade's ends, so a model whose arm is pinned
  // to the blade's MIDDLE is described by its real mechanism instead of by an assumption.
  const bladeAt = phi => bladeSegment(p1, p2, a0, b0, phi, m0, br0);

  let best = null;
  const matches = [];
  for (const edge of edges) {
    let candidate = null, onEdge = Infinity;
    for (let phi = -180; phi <= 180; phi += 0.05) {
      const [aAt, bAt] = bladeAt(phi);
      const on = Math.max(distanceToSegment(aAt, edge.a, edge.b), distanceToSegment(bAt, edge.a, edge.b));
      if (on < onEdge) { onEdge = on; candidate = phi; }
    }
    // 30 mm, not 5: this asks "is the blade lying on this rim edge", and the two implementations derive the
    // blade's position from their own pins, ~10-25 mm apart on a real model. A 5 mm bar therefore rejects
    // the correct edge; a WRONG edge is off by the stroke, i.e. hundreds of mm, so nothing is lost.
    if (candidate === null || onEdge > 0.03) continue;
    matches.push({ phi: candidate, on: onEdge });
  }
  /*
   * THE PARK CAP IS EXCLUDED BY IDENTITY, NOT BY A phi THRESHOLD.
   *
   * The parked blade LIES ON the park cap, so a tiny rotation of it is still within a few millimetres of
   * that same edge. The old rule here was `|phi| > 0.2`, and on the SAF420 control car that leaked:
   * phi = 0.25 deg sat 4.7 mm from the park cap and beat the real far cap's 26 mm at phi = 90.00 deg, so
   * this file reported a 0.250 deg stroke for a fan whose end caps are provably 90.000 deg apart - a FALSE
   * FAILURE against a correct model (notes/279 follow-up). Dropping the match nearest phi = 0 removes the
   * whole class: what survives is a genuinely different blade position.
   */
  if (matches.length) {
    const parkCap = matches.reduce((a, b) => (Math.abs(a.phi) <= Math.abs(b.phi) ? a : b));
    const pole = Math.abs(parkCap.phi);
    const usable = matches.filter(m => m !== parkCap && Math.abs(m.phi) > Math.max(2, pole + 1));
    if (usable.length) best = usable.reduce((a, b) => (a.on <= b.on ? a : b));
    notes.push('sweep stroke solve: park cap at phi=' + parkCap.phi.toFixed(2) + ' deg (' +
      (parkCap.on * 1000).toFixed(1) + ' mm from the parked blade) excluded; ' + usable.length +
      ' other cap(s) matched');
  }
  if (best === null) return { error: 'no fan boundary edge is a blade position (the fan must be the region the blade sweeps)' };
  return {
    strokeDeg: Math.abs(best.phi),
    sweepSign: best.phi < 0 ? -1 : 1,
    onEdge: best.on,
    parkAngleDeg: Math.atan2(b0[1] - a0[1], b0[0] - a0[0]) * 180 / Math.PI,
    pivot1: p1, pivot2: p2, a0, b0, m0, br0
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
  // A REVERSED CAR IS THE SAME OBJ WITH ITS GROUPS RENAMED at pack time (notes/46, and saf420cab_b here):
  // config.groupRename maps source name -> packed name. The anchor JSON, the properties file and the packed
  // OBJ all use the PACKED names, so without applying the rename here every name in them is unknown in the
  // source, and a correct reversed car reads as a wall of "no mmtr_wipersweep_2_1 object in the source OBJ"
  // failures. Applies before anything reads group names.
  const groupRename = config.groupRename || {};
  let renamed = 0;
  for (const group of obj.groups) {
    if (groupRename[group.name]) {
      group.name = groupRename[group.name];
      renamed++;
    }
  }
  if (renamed) {
    console.log('groupRename  : ' + renamed + ' source group(s) renamed to their packed names');
  }
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
    // [name, cab, pane, wiper]; wiper null = the name carries no third index (a windshield, or a sweep
    // that is the glass's only wiper).
    ['windshield_1', 1, 1, null], ['windshield_1_2', 1, 2, null], ['windshield_2', 2, 1, null],
    ['windshield_2_3', 2, 3, null], ['wipersweep_1_1', 1, 1, 1], ['wipersweep_2_3', 2, 3, 1],
    ['wipersweep_1_1_2', 1, 1, 2]
  ];
  for (const [name, cab, pane, wiper] of INDEX_RULE) {
    const group = groupByName.get(name);
    if (!group) continue;   // this fixture simply does not contain it
    const anchor = byName.get(name);
    if (!anchor) { fail('anchor ' + name, 'is in the OBJ but not in the anchor JSON'); continue; }
    if (anchor.cab !== cab || (anchor.pane || 1) !== pane) {
      fail('anchor ' + name, 'parsed as cab=' + anchor.cab + ' pane=' + (anchor.pane || 1) + ', expected cab=' + cab + ' pane=' + pane +
        (pane === 1 && anchor.cab !== cab ? '  <- a lone index is the CAB, not the pane' : ''));
    }
    if (wiper !== null && (anchor.wiper || 1) !== wiper) {
      fail('anchor ' + name, 'parsed as wiper ' + (anchor.wiper || 1) + ', expected wiper ' + wiper +
        '  <- the third index is WHICH WIPER of the glass it is, not a second pane');
    }
  }
  // ...and the same rule for EVERY sweep the model has, not only the names listed above. The third
  // index of mmtr_wipersweep_<cab>_<pane>_<n> and the anchor's own "wiper" field are two statements of
  // one fact, and they fail in a way nothing else catches when they disagree: drop the field off
  // wipersweep_1_1_2 and it becomes a SECOND fan for wiper 1 - the client then sweeps wiper 1's blade
  // with wiper 2's sector while wiper 2's blade never moves at all.
  for (const sweep of anchors.filter(a => a.kind === 'wipersweep')) {
    const named = /^wipersweep_(\d+)_(\d+)(?:_(\d+))?$/i.exec(sweep.name);
    if (!named) {
      fail('anchor ' + sweep.name, 'is not named wipersweep_<cab>_<pane>[_<wiper>], so which wiper of which glass it drives cannot be read off it');
      continue;
    }
    const index = named[3] ? Number(named[3]) : 1;
    const declared = sweep.wiper === undefined || sweep.wiper === null ? 1 : sweep.wiper;
    if (index !== declared) {
      fail('anchor ' + sweep.name, 'is NAMED for wiper ' + index + ' but its "wiper" field says ' + declared +
        ' - the name and the field must agree, or the fan drives the wrong blade');
    }
    if ((sweep.cab || 1) !== Number(named[1]) || (sweep.pane || 1) !== Number(named[2])) {
      fail('anchor ' + sweep.name, 'parsed as cab=' + sweep.cab + ' pane=' + (sweep.pane || 1) +
        ', which is not what its own name says (cab ' + Number(named[1]) + ', pane ' + Number(named[2]) + ')');
    }
  }

  // ---- N0: a CURVED glass must carry the profile that puts the rain back on it --------------------
  //
  // The anchor's frame comes from ONE face (the largest), so a subdivided, curved windscreen is otherwise
  // treated as the plane of whichever patch happened to be biggest. That is not a small error: measured on
  // BR101 V25 the real glass sits -55.2 .. +112.1 mm from that plane, against a water layer offset of
  // 50 mm and a 23 mm glass, and BOTH anchor verifiers passed while it did - neither of them had any
  // notion of "curved". This check is that missing notion, and it is the reason the packager now emits
  // sagM at all.
  for (const glass of anchors.filter(a => a.kind === 'windshield')) {
    const scope = 'glass ' + glass.name;
    const group = groupByName.get(glass.name);
    if (!group) continue;
    const ids = new Set();
    for (const face of group.faces) for (const i of face) ids.add(i);
    const n = L.norm(glass.normal);
    const up = L.norm(L.sub(L.norm(glass.up), L.scl(n, L.dot(L.norm(glass.up), n))));
    const right = L.norm(L.cross(up, n));
    const centre = [glass.x, glass.y, glass.z];
    const halfH = Math.max(1.0E-9, glass.heightM) / 2;
    const sag = Array.isArray(glass.sagGridM) ? glass.sagGridM : null;
    const NX = 17, NY = 17;
    const uMin = typeof glass.sagUMinM === 'number' ? glass.sagUMinM : -glass.widthM / 2;
    const uMax = typeof glass.sagUMaxM === 'number' ? glass.sagUMaxM : glass.widthM / 2;
    const vMin = typeof glass.sagVMinM === 'number' ? glass.sagVMinM : -glass.heightM / 2;
    const vMax = typeof glass.sagVMaxM === 'number' ? glass.sagVMaxM : glass.heightM / 2;
    const sagAt = (alongRight, alongUp) => {
      if (!sag || sag.length !== NX * NY) return 0;
      // The samples sit on the NODES (see the packager and the client sagAt).
      const cx = Math.max(0, Math.min(NX - 1, (alongRight - uMin) / (uMax - uMin) * (NX - 1)));
      const cy = Math.max(0, Math.min(NY - 1, (alongUp - vMin) / (vMax - vMin) * (NY - 1)));
      const x0 = Math.floor(cx), y0 = Math.floor(cy);
      const x1 = Math.min(NX - 1, x0 + 1), y1 = Math.min(NY - 1, y0 + 1);
      const tx = cx - x0, ty = cy - y0;
      const bottom = sag[y0 * NX + x0] + (sag[y0 * NX + x1] - sag[y0 * NX + x0]) * tx;
      const top = sag[y1 * NX + x0] + (sag[y1 * NX + x1] - sag[y1 * NX + x0]) * tx;
      return bottom + (top - bottom) * ty;
    };
    let rawLo = Infinity, rawHi = -Infinity, residual = 0, outside = 0;
    for (const id of ids) {
      const d = L.sub(vpos[id], centre);
      const alongUp = L.dot(d, up);
      const alongRight = L.dot(d, right);
      const raw = L.dot(d, n);
      rawLo = Math.min(rawLo, raw);
      rawHi = Math.max(rawHi, raw);
      residual = Math.max(residual, Math.abs(raw - sagAt(alongRight, alongUp)));
      // The grid holds its edge value outside its span, so a vertex ON the boundary is fine; the
      // tolerance only has to absorb the 6-decimal rounding of the written `up`/`right`.
      if (sag && (alongUp < vMin - 1.0E-3 || alongUp > vMax + 1.0E-3
          || alongRight < uMin - 1.0E-3 || alongRight > uMax + 1.0E-3)) outside++;
    }
    // The canvas the rain is drawn on spans +-heightM/2 about the anchor origin, which is the group's
    // VERTEX MEAN - and heightM is the group's EXTENT. Equal for a quad or a box, not equal for a
    // subdivided surface, and when they differ the whole rain layer (and the wiper with it, which uses
    // the same convention) is offset from the modelled glass. Reported, not asserted: fixing it means
    // moving the anchor origin, which moves every anchor in every model.
    const canvasShiftMm = 1000 * ((rawLo + rawHi) / 2);
    const spanMm = (rawHi - rawLo) * 1000;
    if (spanMm > 2.0 && !sag) {
      fail(scope, 'is curved (' + spanMm.toFixed(1) + ' mm from its own anchor plane) but the packed ' +
        'anchor carries NO sagGridM, so the client draws the rain on that flat plane');
      continue;
    }
    if (outside > 0) {
      fail(scope, outside + ' vertex/vertices fall outside the span the sag grid covers (' +
        uMin.toFixed(4) + '..' + uMax.toFixed(4) + ' x ' + vMin.toFixed(4) + '..' + vMax.toFixed(4) +
        ' m), so the grid cannot cover the modelled surface');
      continue;
    }
    // A STEP IN THE MODEL IS NOT A MAPPING ERROR. A smooth grid cannot follow a discontinuity, so a
    // vertex that stands proud of BOTH its up-axis neighbours within 20 mm is counted as a model step and
    // REPORTED with its position instead of being averaged into a number nobody can act on. Measured on
    // BR101 V25: the rain surface's second-from-bottom row reads 33.7 -> 51.9 -> 28.6 mm, i.e. a 3.15 mm
    // tall spike 18.2 mm proud of both neighbours; every other vertex reproduces to 1.24 mm.
    let steps = 0, stepWorst = 0, stepWhere = '';
    for (const id of ids) {
      const d = L.sub(vpos[id], centre);
      const v0 = L.dot(d, up);
      const raw = L.dot(d, n);
      let below = Infinity, above = -Infinity;
      for (const other of ids) {
        if (other === id) continue;
        const e = L.sub(vpos[other], centre);
        const vv = L.dot(e, up);
        if (Math.abs(vv - v0) > 0.06) continue;   // wider than the mesh row spacing (23-34 mm here)
        const other_raw = L.dot(e, n);
        if (vv < v0) below = Math.min(below, other_raw);
        if (vv > v0) above = Math.max(above, other_raw);
      }
      if (below < Infinity && above > -Infinity && raw - below > 0.005 && raw - above > 0.005) {
        steps++;
        if (raw - Math.max(below, above) > stepWorst) {
          stepWorst = raw - Math.max(below, above);
          stepWhere = 'v=' + (v0 * 1000).toFixed(1) + ' mm';
        }
      }
    }
    const smoothLimit = steps > 0 ? 0.020 : 0.005;
    if (residual > smoothLimit) {
      fail(scope, 'the sag grid leaves the surface ' + (residual * 1000).toFixed(1) +
        ' mm off the rain plane' + (steps > 0 ? ' beyond what its ' + steps + ' reported model step(s) explain' : '') +
        ' - it must put the water back on the glass');
      continue;
    }
    notes.push(scope + ': surface departs ' + spanMm.toFixed(1) + ' mm from the anchor plane' +
      (sag ? ', sagGridM (' + NX + 'x' + NY + ') leaves it ' + (residual * 1000).toFixed(2) + ' mm off the rain plane'
           : ', flat to within ' + spanMm.toFixed(1) + ' mm so no grid is needed') +
      (steps > 0 ? '; MODEL STEP: ' + steps + ' vertex/vertices stand proud of BOTH up-axis neighbours within' +
        ' 20 mm, the worst by ' + (stepWorst * 1000).toFixed(1) + ' mm at ' + stepWhere +
        ' (a smooth grid cannot follow a step - check the mesh)' : '') +
      (Math.abs(canvasShiftMm) > 1 ? '; NOTE the rain canvas (origin +-heightM/2) is centred ' +
        canvasShiftMm.toFixed(1) + ' mm off the surface (origin = vertex mean, heightM = extent)' : ''));
  }

  // ---- N2/S1/S2: every sweep, against its glass ---------------------------------------------------
  const fittedGlasses = new Set();
  const fittedSweeps = new Set();
  // Which fans sit on which glass, by wiper index. Built BEFORE the per-sweep loop because two facts
  // exist only at the GLASS level: a glass carrying more than one fan must write the "wipers" array
  // (the flat shape is ONE wiper to the client, so the other fit is lost and the sectors are swapped),
  // and no two fans may claim the same wiper of the same glass.
  const fansByGlass = new Map();
  for (const sweep of anchors.filter(a => a.kind === 'wipersweep')) {
    const glassName = 'windshield_' + sweep.cab + '_' + (sweep.pane || 1);
    if (!fansByGlass.has(glassName)) fansByGlass.set(glassName, []);
    fansByGlass.get(glassName).push(sweep);
  }
  for (const [glassName, fans] of fansByGlass) {
    const byWiper = new Map();
    for (const sweep of fans) {
      const wiper = sweep.wiper || 1;
      // Two fans for the SAME wiper of the same glass: the packager keeps only the last fit, so one of
      // the two blades would be driven by the other's sector and the other would never move - and
      // nothing in the pack says so.
      if (byWiper.has(wiper)) {
        fail('glass ' + glassName, 'has two wipersweep fans claiming wiper ' + wiper + ' (' + byWiper.get(wiper) +
          ' and ' + sweep.name + '), so the client can only ever drive one of those blades');
      } else {
        byWiper.set(wiper, sweep.name);
      }
    }
    const glassBlock = windshieldConfig[glassName];
    if (fans.length > 1 && glassBlock && !Array.isArray(glassBlock.wipers)) {
      const uncovered = fans.map(one => one.wiper || 1).filter(n => n !== 1).sort((a, b) => a - b);
      fail('glass ' + glassName, 'carries ' + fans.length + ' wipersweep fan(s) in the model but its config writes the FLAT single-wiper block (no "wipers" array) - ' +
        'the client reads that as exactly ONE wiper, so ' +
        (uncovered.length ? 'wiper ' + uncovered.join(' and wiper ') + ' has no fitted block and would never be driven'
                          : 'only one of those fans can ever be driven') +
        ', and the one that survives would sweep with the flat block\'s own sector');
    }
  }

  for (const sweep of anchors.filter(a => a.kind === 'wipersweep')) {
    const scope = 'sweep ' + sweep.name;
    const pane = sweep.pane || 1;
    const wiper = sweep.wiper || 1;
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
    // THIS WIPER'S OWN BLOCK. On a glass with two wipers the fields for wiper 1 are flat and wiper 2's
    // live in "wipers"[i]; reading the glass block itself would compare wiper 2's fan against wiper 1's
    // pivot, park angle and stroke - which is precisely the confusion the flat/array split exists to
    // remove, and it would read as a wall of numeric mismatches instead of one missing block.
    const block = wiperValues(windshieldConfig, glassName, wiper);
    if (block.values === null) {
      // Nothing downstream can be evaluated without a block, and every check below would report an
      // "undefined != ..." of its own - so the missing block is reported once, here, and the sweep is
      // skipped. (The glass-level "two fans over a flat block" check above has already fired too when
      // that is the reason.)
      fail(scope, 'wiper ' + wiper + ' of ' + glassName + ' has NO fitted block: ' +
        ((fansByGlass.get(glassName) || []).length) + ' fan(s) sit on that glass, so this wiper would never move');
      continue;
    }
    const values = block.values;
    const where = block.source;
    if (values.wiper !== true) {
      fail(scope, 'wiper ' + wiper + ' of ' + glassName + ' (' + where + ') does not have wiper=true (got ' + JSON.stringify(values.wiper) + ')');
    }
    const near = (a, b, tol) => a !== undefined && Math.abs(a - b) <= tol;
    const partName = prefix => wiperPartName(prefix, sweep.cab, pane, wiper);

    // The fan is the region the BLADE sweeps, so what it can be checked against is the modelled blade and
    // the modelled arm - not against a sector about its own apex, which is a VIRTUAL centre (metres away
    // from the glass when the fan is the slight sliver a real train linkage draws).
    const bladeForFit = allGroupsByName.get(partName('wiper'));
    const solved = bladeForFit
      ? solveStrokeFromFan(sweepGroup, bladeForFit, allGroupsByName.get(partName('wiperarm')),
        allGroupsByName.get(partName('wiperrod')), domain, vpos)
      : { error: 'no modelled ' + partName('wiper') + ', so the fan cannot be interpreted as a swept region - the legacy sector fit is not checked here' };
    if (solved.error) {
      fail(scope, solved.error);
    } else {
      const pivotU = 0.5 + solved.pivot1[0] / domain.widthM;
      const pivotV = 0.5 + solved.pivot1[1] / domain.heightM;
      const reach = Math.max(Math.hypot(solved.a0[0] - solved.pivot1[0], solved.a0[1] - solved.pivot1[1]),
        Math.hypot(solved.b0[0] - solved.pivot1[0], solved.b0[1] - solved.pivot1[1]));
      // The pivot is compared in METRES (ARM_TOL_M), not as a raw fraction: a fraction tolerance of 1e-3 on
      // a 1.7 m glass is 1.7 mm, which is below the 5 mm spread between this file's and the packager's own
      // derivation of the same arm - i.e. it was testing the two implementations against each other.
      if (!near(values.pivotU, pivotU, ARM_TOL_M / domain.widthM)) fail(scope, 'pivotU ' + values.pivotU + ' != the spindle re-derived from the arm (' + pivotU.toFixed(4) + ')');
      if (!near(values.pivotV, pivotV, ARM_TOL_M / domain.heightM)) fail(scope, 'pivotV ' + values.pivotV + ' != the spindle re-derived from the arm (' + pivotV.toFixed(4) + ')');
      if (!near(values.armM, reach, ARM_TOL_M)) fail(scope, 'armM ' + values.armM + ' != the blade reach ' + reach.toFixed(4));
      if (!near(values.parkAngleDeg, solved.parkAngleDeg, ANGLE_TOL_DEG)) fail(scope, 'parkAngleDeg ' + values.parkAngleDeg + ' != the modelled park direction ' + solved.parkAngleDeg.toFixed(3));
      if (!near(values.sweepDeg, solved.strokeDeg, ANGLE_TOL_DEG)) fail(scope, 'sweepDeg ' + values.sweepDeg + ' != the stroke solved from the fan (' + solved.strokeDeg.toFixed(3) + ')');
      if ((values.sweepSign || 1) !== solved.sweepSign) fail(scope, 'sweepSign ' + values.sweepSign + ' != solved ' + solved.sweepSign);
      notes.push(scope + ' -> ' + glassName + ' wiper ' + wiper + ' (' + where + '): spindle (' + pivotU.toFixed(4) + ', ' + pivotV.toFixed(4) + ') arm ' + reach.toFixed(3) +
        ' m, park ' + solved.parkAngleDeg.toFixed(2) + ' deg, stroke solved ' + solved.strokeDeg.toFixed(2) +
        ' deg (blade on the fan edge to ' + (solved.onEdge * 1000).toFixed(1) + ' mm)');
    }
    fittedGlasses.add(glassName);
    // Counted per WIPER, not per glass: a glass with two wipers has two fits, and reporting one would
    // hide exactly the case this file was extended for.
    fittedSweeps.add(glassName + '#' + wiper);

    // ---- M1/M2: the mechanism ------------------------------------------------------------------
    const bladeGroup = allGroupsByName.get(partName('wiper'));
    if (!bladeGroup) {
      notes.push(scope + ': no modelled ' + partName('wiper') + ', so the client draws its own (nothing to verify)');
      continue;
    }
    const bladeEnds = barEnds(bladeGroup, domain, vpos);
    const pivot1 = [(values.pivotU - 0.5) * domain.widthM, (values.pivotV - 0.5) * domain.heightM];
    const dA = Math.hypot(bladeEnds[0][0] - pivot1[0], bladeEnds[0][1] - pivot1[1]);
    const dB = Math.hypot(bladeEnds[1][0] - pivot1[0], bladeEnds[1][1] - pivot1[1]);
    const a0 = dA <= dB ? bladeEnds[0] : bladeEnds[1];
    const b0 = dA <= dB ? bladeEnds[1] : bladeEnds[0];

    // The PINS, re-derived from the parts exactly as the packager does: the arm's end NEAR the blade is
    // the arm's pin (the other end is the spindle) and the rod's end near the blade is the rod's pin.
    // These are the linkage's real inputs, and they are NOT the blade's ends whenever the arm is pinned to
    // the blade's middle - the case the old "pins are the ends" assumption mis-described by a factor of two
    // in the pin span, and so could not check at all.
    const armGroup = allGroupsByName.get(partName('wiperarm'));
    let m0 = a0;
    if (armGroup) {
      const armEnds = barEnds(armGroup, domain, vpos);
      const e0 = distanceToSegment(armEnds[0], a0, b0);
      const e1 = distanceToSegment(armEnds[1], a0, b0);
      m0 = e0 <= e1 ? armEnds[0] : armEnds[1];
    }
    let br0 = b0;

    // THE PINS MUST LIE ON THE BLADE, and this has to be checked BEFORE the blade-end test below, which
    // `continue`s. It used to sit after it, so on any model whose written blade ends were even slightly off
    // the ONLY thing reported was that offset - while the arm or the rod could be hanging 10 cm off the
    // blade, which is a real modelling error and is what actually breaks the mechanism solve. The pins are
    // the linkage's INPUTS: the arm's end near the blade and the rod's end near the blade are the two points
    // the kinematics drives, so if they are not on the blade, nothing downstream can be trusted.
    const armPinGap = distanceToSegment(m0, a0, b0);
    if (armPinGap > BLADE_BODY_TOL_M) {
      fail(scope, 'the arm meets the blade ' + (armPinGap * 1000).toFixed(1) + ' mm off it - the arm pin must be attached to the blade body');
    }
    const rodGroupForPin = allGroupsByName.get(partName('wiperrod'));
    if (rodGroupForPin) {
      const rodEndsForPin = barEnds(rodGroupForPin, domain, vpos);
      const rodPinGap = Math.min(distanceToSegment(rodEndsForPin[0], a0, b0), distanceToSegment(rodEndsForPin[1], a0, b0));
      if (rodPinGap > BLADE_BODY_TOL_M) {
        fail(scope, 'the rod meets the blade ' + (rodPinGap * 1000).toFixed(1) + ' mm off it - the rod pin must be attached to the blade body');
      }
    }

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

    const rodGroup = allGroupsByName.get(partName('wiperrod'));
    let pivot2 = pivot1.slice();
    if (rodGroup) {
      const rodEnds = barEnds(rodGroup, domain, vpos);
      const near0 = distanceToSegment(rodEnds[0], a0, b0);
      const near1 = distanceToSegment(rodEnds[1], a0, b0);
      pivot2 = near0 <= near1 ? rodEnds[1] : rodEnds[0];
      br0 = near0 <= near1 ? rodEnds[0] : rodEnds[1];   // the end NEAR the blade is the rod's pin
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

    // THE PINS THE PACKAGER WROTE must be the pins the parts actually have. This is the assertion with
    // teeth for the middle-pin case: a fallback to "the pins are the blade's ends" (which is what this
    // verifier itself used to do, and what the packager does when it cannot find an arm or a rod) puts the
    // arm pin at the wrong end of the blade and gets the span wrong by a factor, and it fires here.
    if (values.pinAU !== undefined && values.pinAV !== undefined) {
      const fittedM = [(values.pinAU - 0.5) * domain.widthM, (values.pinAV - 0.5) * domain.heightM];
      if (Math.hypot(fittedM[0] - m0[0], fittedM[1] - m0[1]) > 2 * ARM_TOL_M) {
        fail(scope, 'the arm pin was written as (' + fittedM.map(x => x.toFixed(3)) + ') but the modelled arm meets the blade at (' +
          m0.map(x => x.toFixed(3)) + ')');
      }
    } else {
      notes.push(scope + ': no arm pin written (pinAU/pinAV), so the fitted pins are not cross-checked against the parts');
    }
    if (values.pinBU !== undefined && values.pinBV !== undefined) {
      const fittedR = [(values.pinBU - 0.5) * domain.widthM, (values.pinBV - 0.5) * domain.heightM];
      if (Math.hypot(fittedR[0] - br0[0], fittedR[1] - br0[1]) > 2 * ARM_TOL_M) {
        fail(scope, 'the rod pin was written as (' + fittedR.map(x => x.toFixed(3)) + ') but the modelled rod meets the blade at (' +
          br0.map(x => x.toFixed(3)) + ')');
      }
    } else if (rodGroup) {
      notes.push(scope + ': the model has a rod but no rod pin was written (pinBU/pinBV), so the fitted pins are not cross-checked');
    }

    // The pin positions are checked above, before the blade-end test - see the note there.

    // M2: the two degenerate cases, evaluated with the CLIENT'S OWN formula over the whole stroke.
    // The reference is the direction AT PARK: the stroke starts there, so measuring from theta = 0 would
    // fold the park angle into the drift and "prove" a perfectly correct wiper wrong.
    const park = values.parkAngleDeg;
    const sweepDeg = values.sweepDeg;
    // The WRITTEN sign, not the re-solved one: this section validates the config the client will read, so it
    // has to travel the way that config makes it travel. (If the sign itself is wrong, the fit comparison
    // above already reports it - and then these checks fail too, which is the honest outcome.)
    const sweepSign = values.sweepSign || 1;
    const referenceLength = Math.hypot(b0[0] - a0[0], b0[1] - a0[1]);
    let referenceDirection = null;
    let maxDirectionDrift = 0, maxLengthDrift = 0;
    for (let step = 0; step <= 20; step++) {
      // THE SIGN MATTERS. The client sweeps park -> park + sweepDeg*sweepSign, so a pane whose fitted
      // sweepSign is -1 travels the OTHER way. Walking the wrong way runs the linkage through a range it is
      // not assembled over: BR101's panes 1_2 / 2_1 reported an 89 degree "blade turn" on a demonstrably
      // ideal parallelogram purely from this, and the two-pivot band test below failed for the same reason
      // ("1 point the blade passed through is not inside the band").
      const theta = sweepSign * sweepDeg * step / 20;   // the CRANK angle, measured from park: parkAngleDeg is the blade's bearing, not a rotation
      const [a, b] = bladeSegment(pivot1, pivot2, a0, b0, theta, m0, br0);
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
      const linkA = [m0[0] - pivot1[0], m0[1] - pivot1[1]];
      const linkB = [br0[0] - pivot2[0], br0[1] - pivot2[1]];
      const residual = Math.hypot(linkA[0] - linkB[0], linkA[1] - linkB[1]);
      // 0.5 mm, not 5: a link residual of 2.2 mm already makes the blade turn 0.66 deg, which is a real
      // (slight) fan - the fixture's 15 mm residual turns it 3.95 deg, i.e. ~0.26 deg per mm. Treating
      // 5 mm as "equal link vectors" put a genuine slight fan into the ideal-parallelogram branch and then
      // failed it for turning at all, and for the loop closure not being the rigid rotation to 1e-6 m.
      //
      // 1 mm since BR101: its four panes measure a residual of 0.58 mm, which is BELOW the spread between
      // this file's own pin derivation and the packager's on identical geometry (5-8 mm, see the header),
      // so it is measurement noise and not a mechanism. At 0.5 mm that noise fell into the "slight fan"
      // branch, which then required the blade to turn - and an ideal parallelogram turns it 0.00 deg, so
      // a correct model was failed for being correct. A real train linkage is ~1 mm or more.
      if (residual < 1.0E-3) {
        // Equal link vectors MUST give a blade that never turns: B - A is constant. The bar scales with
        // the residual for the same reason the closure bar below does - the blade's direction turns at
        // about 0.17 deg per millimetre of link residual (measured on BR101: 0.58 mm -> 0.098 deg), so a
        // fixed 0.05 deg bar fails a model whose pins simply came out of a principal-axis fit. What this
        // still catches by a mile is the real failure it was written for, a mirrored or mis-assigned pin
        // pair, which turns the blade by tens or hundreds of degrees.
        const directionBar = 0.05 + residual * 200;
        if (maxDirectionDrift > directionBar) {
          fail(scope, 'equal link vectors (residual ' + residual.toFixed(5) + ' m) but the blade still turns ' +
            maxDirectionDrift.toFixed(3) + ' deg - the parallelogram case is not behaving like one (bar ' + directionBar.toFixed(3) + ' deg)');
        }
        // THE DEGENERATION PROOF for "parallel double link": with equal link vectors the FOUR-BAR loop
        // closure has to return exactly the rigid rotation - that is what makes a parallelogram a special
        // case of the general linkage rather than a separate code path.
        let worst = 0;
        for (let step = 0; step <= 20; step++) {
          // Signed the same way as the drift loop above, so both walk the stroke the client walks.
          const theta = sweepSign * sweepDeg * step / 20;   // the CRANK angle, measured from park
          // The ideal-parallelogram prediction, pin-based: rotate BOTH pins about their own pivots. That is
          // only valid for a parallelogram, which is exactly the case being proved here - for any other
          // linkage the follower's pin has to be SOLVED, and that is what the loop closure does.
          const rounded = pinMotion(pivot1, pivot2, m0, br0, a0, b0, theta);
          const solved = bladeSegment(pivot1, pivot2, a0, b0, theta, m0, br0);
          worst = Math.max(worst, Math.hypot(solved[1][0] - rounded[1][0], solved[1][1] - rounded[1][1]));
        }
        // The bar scales with the residual, because the two forms can only agree as well as the link
        // vectors agree. A fixed 1e-6 m was a FIXTURE-GRADE bar: the fixture's mesh is generated from
        // exact arithmetic, so its residual is ~0, but a real model's pins come out of a principal-axis
        // fit - BR101 measures 0.58 mm, which moves the closure 2.5 mm off the rigid rotation. Measured
        // 5x is the ratio between them on all four BR101 panes, so the bar is "proportional, plus a
        // fixture-grade floor".
        const closureBar = 1.0E-6 + residual * 5;
        if (worst > closureBar) {
          fail(scope, 'the loop closure differs from the rigid rotation by ' + worst.toFixed(6) + ' m on a parallelogram - the degenerate case is not degenerate (bar ' + closureBar.toFixed(6) + ' m)');
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
      // Signed, like the client's own sweep: park -> park + sweepDeg*sweepSign.
      const fromTheta = sweepSign * sweepDeg * 0.30;
      const toTheta = sweepSign * sweepDeg * 0.40;
      const from = bladeSegment(pivot1, pivot2, a0, b0, fromTheta, m0, br0);
      const to = bladeSegment(pivot1, pivot2, a0, b0, toTheta, m0, br0);
      let pathMisses = 0;
      let worstMiss = null;
      const bulgeM = pathBulgeM(from, to, pivot1, m0);
      for (let step = 0; step <= 4; step++) {
        const theta = fromTheta + (toTheta - fromTheta) * step / 4;
        const [a, b] = bladeSegment(pivot1, pivot2, a0, b0, theta, m0, br0);
        const labelled = [['A', a], ['B', b], ['mid', [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2]]];
        for (const [label, point] of labelled) {
          if (bandFactor(point[0], point[1], from, to, bulgeM) <= 0) {
            pathMisses++;
            // How far OUTSIDE the (inflated) band it is: the failure is only actionable if the message says
            // whether the geometry is out by a hair or by the width of the blade.
            const outBy = distanceToSegment(point, to[0], to[1]) - WIPE_FADE_M;
            const detail = label + ' at step ' + step + '/4 (theta ' + theta.toFixed(2) + ' deg, nearest end-to-end distance ' +
              distanceToSegment(point, to[0], to[1]).toFixed(4) + ' m, ' + outBy.toFixed(4) + ' m beyond the fade)';
            if (worstMiss === null || outBy > worstMiss.outBy) worstMiss = { outBy, detail };
          }
        }
      }
      if (pathMisses > 0) {
        fail(scope, pathMisses + ' point(s) the blade actually passed through are NOT inside the wiped band - the band test is rotated or mirrored against the blade; worst: ' +
          worstMiss.detail);
      }
      // A point half a metre to the side of the blade, measured perpendicular to it, must stay unwiped.
      const midTo = [(to[0][0] + to[1][0]) / 2, (to[0][1] + to[1][1]) / 2];
      const along = [to[1][0] - to[0][0], to[1][1] - to[0][1]];
      const alongLength = Math.hypot(along[0], along[1]) || 1;
      const beside = [midTo[0] - along[1] / alongLength * 0.5, midTo[1] + along[0] / alongLength * 0.5];
      if (bandFactor(beside[0], beside[1], from, to, bulgeM) > 0) {
        fail(scope, 'a point 0.5 m to the side of the blade is counted as wiped - the band is far too wide');
      }
      notes.push(scope + ': band covers the blade\'s whole path over the step (chord bulge ' + (bulgeM * 1000).toFixed(2) +
        ' mm allowed for), and 0.5 m to the side stays unwiped');
    } else {
      notes.push(scope + ': one pivot, so the client uses the exact angular sector (the band test would cut the arc corner)');
    }
  }

  // ---- N2 the other way: a glass with a fan must HAVE a fit ---------------------------------------
  // The fitted fields live flat for a one-wiper glass and inside "wipers" for a multi-wiper one, so both
  // shapes are inspected: a block that states a pivot or a stroke for a glass no fan matches is a fit
  // pointing at a glass that is not there.
  for (const [glassName, values] of Object.entries(windshieldConfig)) {
    if (fittedGlasses.has(glassName)) continue;
    const blocks = Array.isArray(values.wipers) ? values.wipers : [values];
    if (blocks.some(one => one && (one.pivotU !== undefined || one.sweepDeg !== undefined))) {
      fail('glass ' + glassName, 'carries fitted sweep fields but no wipersweep fan matches it');
    }
  }

  // ---- S3: the solid wiper -------------------------------------------------------------------------
  // Read from EVERY group, not just the mmtr_* anchors: the solid wiper deliberately has no anchor
  // prefix (it is a visible part, not data).
  const SOLID_WIPER = /^wiper_(\d+)_(\d+)(?:_(\d+))?$/i;
  const modelledWipers = obj.groups.map(g => g.name).filter(name => SOLID_WIPER.test(name));
  for (const wiper of modelledWipers) {
    const match = SOLID_WIPER.exec(wiper);
    const cab = Number(match[1]), pane = Number(match[2]), index = match[3] ? Number(match[3]) : 1;
    const glassName = 'windshield_' + cab + '_' + pane;
    if (!windshieldConfig[glassName]) { fail('part ' + wiper, 'has no ' + glassName + ' to be the wiper of'); continue; }
    // THE WIPER THIS PART BELONGS TO, not the glass's first one: wiper_1_1_2 replaces the blade of
    // wiper 2 alone, so its drawBlade lives in wiper 2's own block.
    const block = wiperValues(windshieldConfig, glassName, index);
    if (block.values === null) {
      fail('part ' + wiper, 'is wiper ' + index + ' of ' + glassName + ' but that wiper has no fitted block');
      continue;
    }
    const values = block.values;
    if (values.drawBlade !== false) {
      fail('part ' + wiper, 'exists but wiper ' + index + ' of ' + glassName + ' (' + block.source + ') does not set drawBlade=false, so the drawn blade would sit on top of the modelled one');
    }
    if (values.wiper === false) {
      fail('part ' + wiper, 'wiper ' + index + ' of ' + glassName + ' sets wiper=false, which would stop it being wiped at all (a solid wiper replaces the BLADE, not the wipe)');
    }
    if (!groupsOf.has(wiper)) {
      fail('part ' + wiper, 'is in the OBJ but not in the packed properties file, so it would never render');
    }
  }
  // ...and no CONFIGURED wiper may have drawBlade=false without a solid part behind it, or its blade
  // simply vanishes. Per wiper, not per glass: on a two-wiper screen the one whose blade is modelled
  // switches its drawn blade off while the other keeps drawing one.
  const hasSolidPart = part => obj.groups.some(g => g.name.toLowerCase() === part.toLowerCase());
  for (const [glassName, values] of Object.entries(windshieldConfig)) {
    // One index is the CAB (windshield_2 = cab 2 pane 1), two are cab + pane - the same rule the
    // packager parses names with, so the part name derived here is the one the model would carry.
    const nameParts = /^windshield_(\d+)(?:_(\d+))?$/.exec(glassName);
    if (Array.isArray(values.wipers)) {
      values.wipers.forEach((one, i) => {
        if (!one || one.drawBlade !== false || !nameParts) return;
        const index = one.wiperIndex === undefined || one.wiperIndex === null ? i + 1 : Number(one.wiperIndex);
        const part = wiperPartName('wiper', Number(nameParts[1]), nameParts[2] ? Number(nameParts[2]) : 1, index);
        if (!hasSolidPart(part)) {
          fail('glass ' + glassName, 'gives wiper ' + index + ' drawBlade=false but the model has no ' + part + ' - the blade would simply vanish');
        }
      });
      continue;
    }
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
    fittedSweeps.size + ' fitted sweep(s), ' + modelledWipers.length + ' solid wiper(s)');
}

main();

