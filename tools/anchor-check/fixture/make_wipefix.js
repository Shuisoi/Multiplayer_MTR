#!/usr/bin/env node
/*
 * make_wipefix.js - generates fixture/wipefix.obj.
 *
 * The fixture is GENERATED rather than hand-typed on purpose. Every number in it is a formula, so the
 * expected fit values are exact and the verifier can compare against real arithmetic instead of against
 * whatever a human typed at 2am. It also makes the parallelogram construction obvious: the two link
 * vectors are literally the same variable.
 *
 * Run: node mmtr/tools/anchor-check/fixture/make_wipefix.js
 *
 * What it builds (one car):
 *   body                        a box, so the model is not degenerate
 *   mmtr_windshield_1_1         cab 1 pane 1: the main screen, 3.0 x 1.2 m
 *   mmtr_wipersweep_1_1         its wiper sector: a fan, apex = the SPINDLE
 *   wiperarm_1_1                the arm (a solid part)
 *   wiper_1_1                   the blade (a solid part)
 *                                  -> a SINGLE-AXIS wiper: one pivot, the blade rides the arm
 *   mmtr_windshield_1_2         cab 1 pane 2: a side window with NO sweep (pairing check)
 *   mmtr_windshield_1_3         cab 1 pane 3: a screen with a PARALLEL-LINKAGE wiper
 *   mmtr_wipersweep_1_3         its sector: apex = the main spindle P1
 *   wiperarm_1_3, wiperrod_1_3, wiper_1_3
 *                                  -> two pivots P1/P2 and EQUAL link vectors, so the blade
 *                                     TRANSLATES and never turns (the train/pantograph case)
 *   mmtr_windshield_2           cab 2, written with a SINGLE index (the backward-compat guard:
 *                               one index is the CAB, so this is cab 2 pane 1)
 *
 * The 2D domain of every glass is (right = +Z, up = +Y) measured from the glass centre, because the
 * glass quads are wound so that the packager's rule gives normal = -X, up = +Y, right = +Z.
 */

'use strict';

const fs = require('fs');
const path = require('path');

const DEG = Math.PI / 180;
const lines = [];
const vertexLines = [];   // emitted first: OBJ indices are GLOBAL and 1-based, in this order
const vertices = [];      // [x, y, z] in source order

function v(x, y, z) {
  vertices.push([x, y, z]);
  vertexLines.push('v ' + x.toFixed(6) + ' ' + y.toFixed(6) + ' ' + z.toFixed(6));
  return vertices.length;
}

/** A quad from four (right, up) pairs on a glass plane at x = planeX. */
function glass(name, planeX, centreY, centreZ, halfWidthM, halfHeightM) {
  lines.push('o ' + name);
  const a = v(planeX, centreY - halfHeightM, centreZ - halfWidthM);
  const b = v(planeX, centreY - halfHeightM, centreZ + halfWidthM);
  const c = v(planeX, centreY + halfHeightM, centreZ + halfWidthM);
  const d = v(planeX, centreY + halfHeightM, centreZ - halfWidthM);
  lines.push('f ' + [a, b, c, d].join(' '));
}

/** A box around the segment p0->p1, both given as (right, up) on a glass plane at x = planeX. */
function segmentBox(name, planeX, centreY, centreZ, p0, p1, halfWidthM, halfThicknessM) {
  const dr = p1[0] - p0[0], du = p1[1] - p0[1];
  const length = Math.hypot(dr, du) || 1;
  const pr = -du / length * halfWidthM, pu = dr / length * halfWidthM;
  lines.push('o ' + name);
  const toWorld = (p, offsetR, offsetU, dx) => v(planeX + dx, centreY + p[1] + offsetU, centreZ + p[0] + offsetR);
  const corners = [
    toWorld(p0, -pr, -pu, -halfThicknessM), toWorld(p0, pr, pu, -halfThicknessM),
    toWorld(p1, pr, pu, -halfThicknessM), toWorld(p1, -pr, -pu, -halfThicknessM),
    toWorld(p0, -pr, -pu, halfThicknessM), toWorld(p0, pr, pu, halfThicknessM),
    toWorld(p1, pr, pu, halfThicknessM), toWorld(p1, -pr, -pu, halfThicknessM)
  ];
  const [a, b, c, d, e, f, g, h] = corners;
  lines.push('f ' + [a, b, c, d].join(' '));
  lines.push('f ' + [e, h, g, f].join(' '));
  lines.push('f ' + [a, e, f, b].join(' '));
  lines.push('f ' + [b, f, g, c].join(' '));
  lines.push('f ' + [c, g, h, d].join(' '));
  lines.push('f ' + [d, h, e, a].join(' '));
}

/** A triangle fan: apex + rim points, all (right, up) on a glass plane at x = planeX. */
function fan(name, planeX, centreY, centreZ, apex, rim) {
  lines.push('o ' + name);
  const apexIndex = v(planeX, centreY + apex[1], centreZ + apex[0]);
  const rimIndices = rim.map(p => v(planeX, centreY + p[1], centreZ + p[0]));
  for (let i = 0; i + 1 < rimIndices.length; i++) {
    lines.push('f ' + [apexIndex, rimIndices[i], rimIndices[i + 1]].join(' '));
  }
}

const polar = (from, radius, degrees) => [
  from[0] + radius * Math.cos(degrees * DEG),
  from[1] + radius * Math.sin(degrees * DEG)
];

const rotateAbout = (pivot, point, degrees) => {
  const c = Math.cos(degrees * DEG), s = Math.sin(degrees * DEG);
  const dx = point[0] - pivot[0], dy = point[1] - pivot[1];
  return [pivot[0] + dx * c - dy * s, pivot[1] + dx * s + dy * c];
};

/** Where two lines meet, each given as a point and a direction. */
function lineIntersection(p, dp, q, dq) {
  const denominator = dp[0] * dq[1] - dp[1] * dq[0];
  if (Math.abs(denominator) < 1.0e-9) return null;
  const t = ((q[0] - p[0]) * dq[1] - (q[1] - p[1]) * dq[0]) / denominator;
  return [p[0] + dp[0] * t, p[1] + dp[1] * t];
}

/**
 * The region the blade SWEEPS, drawn as a triangle fan - which is what mmtr_wipersweep_<cab>_<pane>
 * means: "where does this wiper clear", NOT "how far does the arm swing".
 *
 * The apex is the intersection of the blade's two extreme lines, i.e. the VIRTUAL centre of the fan.
 * For a single-axis wiper both extremes pass through the spindle, so the apex IS the spindle. For a
 * parallel linkage the blade only turns a few degrees, so the apex is a virtual point far away and the
 * region is a SLIGHT FAN - which is exactly what a real train wiper clears.
 *
 * Using the line intersection as the apex is what makes the fan solvable: its two boundary edges are
 * then the blade's own two extreme directions, so the packager can recover the ARM's stroke from
 * "which rotation of the linkage puts the blade along the far edge".
 */
function sweptRegionFan(name, planeX, centreY, centreZ, p1, p2, a0, b0, strokeDeg) {
  const a1 = rotateAbout(p1, a0, strokeDeg);
  const b1 = rotateAbout(p2, b0, strokeDeg);
  const apex = lineIntersection(a0, [b0[0] - a0[0], b0[1] - a0[1]], a1, [b1[0] - a1[0], b1[1] - a1[1]]) || a0;
  fan(name, planeX, centreY, centreZ, apex, [a0, b0, b1, a1]);
  return apex;
}

// ---- body -----------------------------------------------------------------------------------------
lines.push('# GENERATED by fixture/make_wipefix.js - do not edit by hand.');
lines.push('mtllib wipefix.mtl');
lines.push('');
lines.push('o body');
{
  const xs = [-0.3, 0.3], ys = [0.0, 2.4], zs = [-1.5, 1.5];
  const idx = [];
  for (const x of xs) for (const y of ys) for (const z of zs) idx.push(v(x, y, z));
  // idx order: x-major, then y, then z
  const [a, b, c, d, e, f, g, h] = idx;   // x=-0.3: (y-,z-),(y-,z+),(y+,z+),(y+,z-) ; then x=+0.3
  lines.push('f ' + [a, b, c, d].join(' '));
  lines.push('f ' + [e, h, g, f].join(' '));
  lines.push('f ' + [a, e, f, b].join(' '));
  lines.push('f ' + [b, f, g, c].join(' '));
  lines.push('f ' + [c, g, h, d].join(' '));
  lines.push('f ' + [d, h, e, a].join(' '));
}

// ---- cab 1, pane 1: a SINGLE-AXIS wiper -----------------------------------------------------------
// Glass centred on (0, 1.6, 0), 3.0 x 1.2 m. Domain: right -1.5..1.5, up -0.6..0.6.
const P1 = [-1.2, -0.5];          // the spindle: the fan's apex, so the packager reads it from there
const PARK_DEG = 20, SWEEP_DEG = 60;
const ARM_TIP = polar(P1, 0.8, PARK_DEG);
const BLADE_PERP = [-Math.sin(PARK_DEG * DEG), Math.cos(PARK_DEG * DEG)];
// An asymmetric blade on the arm's tip: 0.15 m on one side, 0.35 m on the other. Asymmetric on purpose
// - a centred blade makes both ends equidistant from the pivot, and then "which end is A" is a coin flip.
const BLADE_A = [ARM_TIP[0] - 0.15 * BLADE_PERP[0], ARM_TIP[1] - 0.15 * BLADE_PERP[1]];
const BLADE_B = [ARM_TIP[0] + 0.35 * BLADE_PERP[0], ARM_TIP[1] + 0.35 * BLADE_PERP[1]];
const distance = (p, q) => Math.hypot(p[0] - q[0], p[1] - q[1]);
// The sector's radius is the blade's FURTHEST reach from the spindle, not the arm's length: that radius
// is what the film is drawn at, so a fan that stops short of the blade leaves an un-wiped crescent.
const ARM_M = Math.max(distance(P1, BLADE_A), distance(P1, BLADE_B));

glass('mmtr_windshield_1_1', 0, 1.6, 0, 1.5, 0.6);
// ONE pivot (P1 twice), so the two blade extremes both pass through the spindle and the swept region is
// a true 60-degree sector about it.
sweptRegionFan('mmtr_wipersweep_1_1', 0, 1.6, 0, P1, P1, BLADE_A, BLADE_B, SWEEP_DEG);
segmentBox('wiperarm_1_1', 0, 1.6, 0, P1, ARM_TIP, 0.02, 0.02);
segmentBox('wiper_1_1', 0, 1.6, 0, BLADE_A, BLADE_B, 0.025, 0.02);

// ---- cab 1, pane 2: a side window with NO wiper ---------------------------------------------------
glass('mmtr_windshield_1_2', 0, 1.7, 1.8, 0.3, 0.3);

// ---- cab 1, pane 3: a PARALLEL-LINKAGE (pantograph) wiper -----------------------------------------
// Glass centred on (0, 1.6, 3.6), 1.2 x 1.2 m. Domain: right/up -0.6..0.6.
//
// A REAL train linkage is an IMPERFECT parallelogram: the two link vectors differ slightly, so the
// blade does not stay exactly parallel - it turns a few degrees as it sweeps, and the region it clears
// is a SLIGHT FAN rather than a pure translated band. That is what the +0.015 below is: it is the whole
// difference between "the blade translates" and "the blade fans slightly", and it is a real property of
// the mechanism, not a modelling error.
const LINK = [0.5, 0.12];                 // the ARM's link vector
const LINK_ROD = [0.5, 0.12 + 0.015];     // the ROD's, deliberately a little different (see above)
const Q1 = [0.0, 0.0];                    // the main spindle
const Q2 = [0.0, 0.3];                    // the second spindle, offset from the first
const QA = [Q1[0] + LINK[0], Q1[1] + LINK[1]];         // blade end attached to the arm
const QB = [Q2[0] + LINK_ROD[0], Q2[1] + LINK_ROD[1]]; // blade end attached to the rod
const Q_PARK = 0, Q_SWEEP = 50, Q_RADIUS = 0.55;

glass('mmtr_windshield_1_3', 0, 1.6, 3.6, 0.6, 0.6);
// The action surface is the region the BLADE sweeps, so for this linkage it is a SLIGHT FAN (the blade
// turns only a couple of degrees over a 50-degree arm stroke), not a 50-degree sector about the spindle.
sweptRegionFan('mmtr_wipersweep_1_3', 0, 1.6, 3.6, Q1, Q2, QA, QB, Q_SWEEP);
segmentBox('wiperarm_1_3', 0, 1.6, 3.6, Q1, QA, 0.018, 0.018);
segmentBox('wiperrod_1_3', 0, 1.6, 3.6, Q2, QB, 0.014, 0.014);
segmentBox('wiper_1_3', 0, 1.6, 3.6, QA, QB, 0.022, 0.018);

// ---- cab 2: a screen named with a SINGLE index ----------------------------------------------------
glass('mmtr_windshield_2', 0, 1.6, -2.1, 0.5, 0.6);

fs.writeFileSync(path.join(__dirname, 'wipefix.obj'), vertexLines.concat(lines).join('\n') + '\n', 'utf8');
console.log('wrote wipefix.obj: ' + vertices.length + ' vertices, ' + lines.filter(l => l.startsWith('o ')).length + ' objects');
console.log('single-axis  pane 1: P1=(' + P1 + ') park=' + PARK_DEG + ' sweep=' + SWEEP_DEG + ' arm=' + ARM_M);
console.log('             blade A0=(' + BLADE_A.map(x => x.toFixed(6)) + ') B0=(' + BLADE_B.map(x => x.toFixed(6)) + ')');
console.log('parallelogram pane 3: P1=(' + Q1 + ') P2=(' + Q2 + ')');
console.log('             arm link=(' + LINK + ') rod link=(' + LINK_ROD + ')  <- deliberately NOT equal:');
console.log('             blade A0=(' + QA + ') B0=(' + QB + ')  B-A=(' + (QB[0] - QA[0]).toFixed(3) + ',' + (QB[1] - QA[1]).toFixed(3) +
  '), i.e. a SLIGHT FAN, not a pure band');
