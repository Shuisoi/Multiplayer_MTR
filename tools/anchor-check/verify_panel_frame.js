#!/usr/bin/env node
/*
 * verify_panel_frame.js - checks a folded dashboard's facets in the frame the CLIENT draws them in.
 *
 * WHY THIS EXISTS
 * verify_facets.js validates the packager against its OWN unfolded layout: it unfolds along the crease,
 * accumulates the offsets, and confirms the numbers agree with themselves. That check is necessary but
 * it cannot see the last mile. The client does NOT use the packager's unfold frame. For each facet it
 * recomputes
 *     right = cross(up, normal)          (MmtrPanelQuad.drawFrame; the file's `right` is ignored)
 *     side  = facingSide(position, normal)   (the car's interior is towards the origin)
 *     local +X = side * right,  u0 at local -X, u1 at local +X
 * and it lifts the quad off the face along local +Z. So there are TWO frames in play, and a pack can
 * pass verify_facets and still come out MIRRORED or SEAMED in game. The seam case is not hypothetical:
 * the BR101 dashboard was sheared, and after the shear was fixed the canvas was discontinuous at both
 * creases (u jumped 1 -> 0.412 across a shared edge) until the HUD faces were re-wound in Blender.
 * Neither symptom is visible in the packager's own numbers, and a screenshot only says "it looks wrong".
 *
 * WHAT IT CHECKS (all in the client's frame, per multi-facet hud anchor)
 *   P1  frame sanity: every facet has a usable position/normal/up, widthM/heightM > 0, u0 <= u1,
 *       v0 <= v1, and non-degenerate canvas dimensions
 *   P2  CREASE CONTINUITY: two facet CORNERS that land on the same point in space (<= 0.02 m apart)
 *       must be handed the same (u, v). Any mismatch means the image tears at the crease, or is off by
 *       a whole facet. All four corners are tested, so a fold about `up` and a fold about `right` are
 *       both covered
 *   P3  ONE U DIRECTION: a folded panel's facets each get their OWN local +X (right = cross(up, normal)
 *       rotates with the normal), so demanding parallel axes would be wrong. What must hold is that no
 *       facet's local +X points the OPPOSITE way along the panel, because that means u runs backwards
 *       across the fold and part of the canvas is mirrored
 *   P4  NOT MIRRORED: u must increase towards the driver's right. The client guarantees local +X is the
 *       driver's right (see the long comment in MmtrPanelQuad.drawFrame), so sorting the facets along
 *       local +X must sort them by increasing u too. This is the check with real teeth: swapping u0/u1
 *       on EVERY facet is continuous and tiles the canvas perfectly, and only P4 sees it
 *   P5  UNFOLD EXTENT: the canvas really is the unfolded length. Along the axis the facets share (the
 *       one the crease runs down) every facet spans the full 0..1; along the other axis the facet's
 *       extent is its share of the canvas, |du| * canvasWidthM == widthM (and the v/heightM mirror)
 *   P6  the client must pick ONE side per anchor (facingSide); facets that disagree, or a panel that
 *       sits on the car's centre line (side 0, both copies drawn), are modelling errors
 *
 * Usage:
 *   node mmtr/tools/anchor-check/verify_panel_frame.js --config <vehicle config.json> [--report]
 *   node mmtr/tools/anchor-check/verify_panel_frame.js --anchors <mmtr_anchors_<id>.json> [--report]
 *
 * --anchors is there so the fault-injection self-test can point the same checks at a fixture.
 */

'use strict';

const fs = require('fs');
const path = require('path');

const CORNER_TOL = 0.02;    // blocks: how close two corners must be to count as the same point
const UV_TOL = 1.0E-3;      // canvas fractions
const EXTENT_TOL = 1.0E-3;  // blocks
const ORDER_TOL = 2.0E-3;   // blocks: below this two facets count as "in the same place"

const failures = [];
const notes = [];

function fail(scope, message) {
  failures.push(scope + ': ' + message);
}

function nearly(a, b, tol) {
  return Math.abs(a - b) <= tol;
}

// ---- vector helpers (the same arithmetic the crease tool used, kept explicit and dependency-free) ----
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const scl = (a, s) => [a[0] * s, a[1] * s, a[2] * s];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const len = a => Math.hypot(a[0], a[1], a[2]);
const norm = a => { const l = len(a) || 1; return [a[0] / l, a[1] / l, a[2] / l]; };
const dist = (a, b) => len(sub(a, b));
const fmt = v => '(' + v.map(x => x.toFixed(4)).join(', ') + ')';

/** OBJ file space -> the space MTR renders in (MmtrPanelQuad.toModelSpace). */
const toModelSpace = v => [v[0], -v[1], -v[2]];

/** MmtrPanelQuad.facingSide: the car is centred on the origin, so the interior is towards it. */
function facingSide(position, normal) {
  const horizontal = normal[0] * -position[0] + normal[2] * -position[2];
  if (Math.abs(horizontal) < 1.0E-4) return 0;
  return horizontal > 0 ? 1 : -1;
}

function loadConfig(file) {
  const root = path.resolve(__dirname, '..', '..', '..');
  const raw = JSON.parse(fs.readFileSync(file, 'utf8'));
  for (const key of Object.keys(raw)) {
    if (typeof raw[key] === 'string') raw[key] = raw[key].replace(/\$\{MC_ROOT\}/g, root);
  }
  return raw;
}

/**
 * Rebuilds one facet exactly the way the client will: the model-space frame, the side the client picks,
 * local +X, and the four corners with the (u, v) the client hands each of them.
 */
function clientFrame(facet) {
  const position = toModelSpace([facet.x, facet.y, facet.z]);
  const normal = norm(toModelSpace(facet.normal));
  const upRaw = norm(toModelSpace(facet.up));
  const up = norm(sub(upRaw, scl(normal, dot(upRaw, normal))));   // orthonormalise(up, normal)
  const right = norm(cross(up, normal));
  const side = facingSide(position, normal);
  const localX = scl(right, side);
  const halfW = facet.widthM / 2;
  const halfH = facet.heightM / 2;
  // v0 is the panel's TOP edge (drawFrame hands v0 to the top corners), so v0 goes towards +up.
  const corners = [
    { name: 'u0v0', u: facet.u0, v: facet.v0, p: add(sub(position, scl(localX, halfW)), scl(up, halfH)) },
    { name: 'u1v0', u: facet.u1, v: facet.v0, p: add(add(position, scl(localX, halfW)), scl(up, halfH)) },
    { name: 'u0v1', u: facet.u0, v: facet.v1, p: sub(sub(position, scl(localX, halfW)), scl(up, halfH)) },
    { name: 'u1v1', u: facet.u1, v: facet.v1, p: sub(add(position, scl(localX, halfW)), scl(up, halfH)) }
  ];
  // A facet that declares its own `corners` is NOT necessarily a rectangle: a dashboard wing that follows
  // the desk's flowing line is a sheared parallelogram, and the widthM x heightM rectangle is only its
  // bounding box. The client draws the corner quad, giving each corner u by which half of `right` it falls
  // in and v by which half of `up` - the same classification MmtrPanelQuad does, so the order the packager
  // emitted them in does not matter.
  if (facet.corners && facet.corners.length === 4) {
    corners.length = 0;
    for (const pair of facet.corners) {
      const a = pair[0], b = pair[1];
      corners.push({
        name: (b > 0 ? 'v0' : 'v1') + (a > 0 ? 'u1' : 'u0'),
        u: a > 0 ? facet.u1 : facet.u0,
        v: b > 0 ? facet.v0 : facet.v1,
        p: add(add(position, scl(localX, a)), scl(up, b))
      });
    }
  }
  return { facet, position, normal, up, right, side, localX, corners, sheared: !!(facet.corners && facet.corners.length === 4), uMid: (facet.u0 + facet.u1) / 2 };
}

function sameInterval(a0, a1, b0, b1, tol) {
  return nearly(a0, b0, tol) && nearly(a1, b1, tol);
}

function verifyAnchor(anchor) {
  const scope = 'anchor ' + anchor.name;
  const facets = anchor.faces || [];
  if (facets.length < 2) {
    notes.push(scope + ': ' + facets.length + ' facet(s) - not a folded panel, nothing to place');
    return;
  }

  // ---- P1 frame sanity ------------------------------------------------------------------------------
  for (const facet of facets) {
    const label = scope + ' facet u[' + facet.u0 + ',' + facet.u1 + ']';
    const numbers = [facet.x, facet.y, facet.z, facet.widthM, facet.heightM, facet.u0, facet.u1, facet.v0, facet.v1]
      .concat(facet.normal || []).concat(facet.up || []);
    if (numbers.some(n => typeof n !== 'number' || !isFinite(n))) {
      fail(label, 'has a non-finite coordinate/uv (the client would place it at NaN)');
      continue;
    }
    if (!(facet.widthM > 0) || !(facet.heightM > 0)) fail(label, 'widthM/heightM must be positive');
    if (facet.u0 > facet.u1) fail(label, 'u0 > u1 (the client would draw the rect inside out)');
    if (facet.v0 > facet.v1) fail(label, 'v0 > v1 (the client would draw the rect inside out)');
    if (!facet.normal || len(facet.normal) < 1.0E-6) fail(label, 'has no usable normal');
    if (!facet.up || len(facet.up) < 1.0E-6) fail(label, 'has no usable up');
  }
  if (!(anchor.canvasWidthM > 0) || !(anchor.canvasHeightM > 0)) {
    fail(scope, 'canvasWidthM/canvasHeightM must be positive and present for a folded panel');
    return;
  }
  const frames = facets.map(clientFrame);

  // ---- P2 crease continuity -------------------------------------------------------------------------
  let sharedChecked = 0;
  for (let i = 0; i < frames.length; i++) {
    for (let j = i + 1; j < frames.length; j++) {
      for (const a of frames[i].corners) {
        for (const b of frames[j].corners) {
          const d = dist(a.p, b.p);
          if (d > CORNER_TOL) continue;
          sharedChecked++;
          if (!nearly(a.u, b.u, UV_TOL) || !nearly(a.v, b.v, UV_TOL)) {
            const mirrored = nearly(a.u + b.u, 1, UV_TOL);
            fail(scope, 'the canvas TEARS at the crease: facet' + i + '.' + a.name + ' and facet' + j + '.' + b.name
              + ' are the same point ' + fmt(a.p) + ' but carry u,v (' + a.u.toFixed(4) + ', ' + a.v.toFixed(4)
              + ') vs (' + b.u.toFixed(4) + ', ' + b.v.toFixed(4) + ')'
              + (mirrored ? ' - u + u = 1, i.e. the two facets are mirrored about the canvas centre' : '')
              + ' - re-check the mmtr_hud face winding in the source model');
          }
        }
      }
    }
  }
  if (sharedChecked === 0) {
    fail(scope, 'no two facet corners coincide - the facets do not share a crease, so the canvas is not a fold');
  }

  // ---- P3 one u direction --------------------------------------------------------------------------
  // Each facet's local +X is right = cross(up, normal), which rotates with that facet's own normal, so
  // the vectors are NOT parallel across a fold (the BR101's 45-degree wings are 45 degrees apart). The
  // invariant that matters is that none of them points the other way: a flip would make the dot
  // product negative and run u backwards across that fold.
  const ref = frames[0];
  let axesAgree = true;
  for (let i = 1; i < frames.length; i++) {
    const alignment = dot(frames[i].localX, ref.localX);
    if (alignment <= 0) {
      axesAgree = false;
      fail(scope, 'facet' + i + ' runs its canvas u along ' + fmt(frames[i].localX) + ', the OPPOSITE way to facet0\'s '
        + fmt(ref.localX) + ' (dot = ' + alignment.toFixed(3) + ') - u runs backwards across that fold, so part of'
        + ' the panel is mirrored; check that facet\'s mmtr_hud face winding');
    }
  }

  // ---- P4 not mirrored -----------------------------------------------------------------------------
  // Sort by the physical position ALONG the shared u axis and require u to come out in the same order.
  // local +X is the driver's right (MmtrPanelQuad guarantees it), so ascending u means the image is the
  // right way round. A global u0/u1 swap keeps P2 and P5 green and is caught only here.
  let mirrored = 0;
  for (let i = 0; i < frames.length; i++) {
    for (let j = i + 1; j < frames.length; j++) {
      const physical = dot(sub(frames[j].position, frames[i].position), ref.localX);
      const du = frames[j].uMid - frames[i].uMid;
      if (Math.abs(physical) <= ORDER_TOL) continue;
      if (physical * du < 0) mirrored++;
    }
  }
  if (mirrored > 0) {
    fail(scope, mirrored + ' facet pair(s) place u in the opposite order to local +X (= the driver\'s right),'
      + ' so the dashboard reads BACKWARDS - the canvas is mirrored');
  }

  // ---- P5 unfold extent ----------------------------------------------------------------------------
  const uVaries = frames.some(f => !sameInterval(f.facet.u0, f.facet.u1, ref.facet.u0, ref.facet.u1, UV_TOL));
  const vVaries = frames.some(f => !sameInterval(f.facet.v0, f.facet.v1, ref.facet.v0, ref.facet.v1, UV_TOL));
  if (uVaries && vVaries) {
    fail(scope, 'both u and v vary across the facets - the packager only unfolds along one axis, so the'
      + ' canvas cannot be the unfolded extent of this panel');
  }
  const varying = uVaries ? 'u' : 'v';
  const canvas = uVaries ? anchor.canvasWidthM : anchor.canvasHeightM;
  for (let i = 0; i < frames.length; i++) {
    const facet = frames[i].facet;
    if (uVaries) {
      const span = (facet.u1 - facet.u0) * anchor.canvasWidthM;
      if (!nearly(span, facet.widthM, EXTENT_TOL)) {
        fail(scope, 'facet' + i + ': |du| * canvasWidthM = ' + span.toFixed(4) + ' m but the facet is '
          + facet.widthM.toFixed(4) + ' m wide - u is not proportional to the unfolded length');
      }
      if (!nearly(facet.v0, 0, UV_TOL) || !nearly(facet.v1, 1, UV_TOL)) {
        fail(scope, 'facet' + i + ': v spans ' + facet.v0 + '..' + facet.v1 + ', but u is the folding axis so'
          + ' every facet must span the full canvas height (v 0..1)');
      }
      if (!nearly(facet.heightM, anchor.canvasHeightM, EXTENT_TOL)) {
        fail(scope, 'facet' + i + ': heightM ' + facet.heightM.toFixed(4) + ' != canvasHeightM '
          + anchor.canvasHeightM.toFixed(4) + ' (the folding axis keeps the full height)');
      }
    } else {
      const span = (facet.v1 - facet.v0) * anchor.canvasHeightM;
      if (!nearly(span, facet.heightM, EXTENT_TOL)) {
        fail(scope, 'facet' + i + ': |dv| * canvasHeightM = ' + span.toFixed(4) + ' m but the facet is '
          + facet.heightM.toFixed(4) + ' m tall - v is not proportional to the unfolded length');
      }
      if (!nearly(facet.u0, 0, UV_TOL) || !nearly(facet.u1, 1, UV_TOL)) {
        fail(scope, 'facet' + i + ': u spans ' + facet.u0 + '..' + facet.u1 + ', but v is the folding axis so'
          + ' every facet must span the full canvas width (u 0..1)');
      }
      if (!nearly(facet.widthM, anchor.canvasWidthM, EXTENT_TOL)) {
        fail(scope, 'facet' + i + ': widthM ' + facet.widthM.toFixed(4) + ' != canvasWidthM '
          + anchor.canvasWidthM.toFixed(4) + ' (the folding axis keeps the full width)');
      }
    }
  }

  // ---- tiling in the client frame ------------------------------------------------------------------
  // The corners are the ground truth for WHERE each price of canvas lands, so tile the canvas with them.
  for (const axis of ['u', 'v']) {
    const intervals = frames.map(f => axis === 'u'
      ? [f.facet.u0, f.facet.u1].sort((a, b) => a - b)
      : [f.facet.v0, f.facet.v1].sort((a, b) => a - b));
    const sorted = intervals.slice().sort((a, b) => a[0] - b[0]);
    if (!nearly(sorted[0][0], 0, UV_TOL)) {
      fail(scope, axis + ' starts at ' + sorted[0][0].toFixed(4) + ' instead of 0 - the canvas is clipped');
    }
    if (!nearly(sorted[sorted.length - 1][1], 1, UV_TOL)) {
      fail(scope, axis + ' ends at ' + sorted[sorted.length - 1][1].toFixed(4) + ' instead of 1 - the canvas is clipped');
    }
    if (axis === varying) {
      for (let i = 1; i < sorted.length; i++) {
        if (sorted[i][0] < sorted[i - 1][1] - UV_TOL) {
          fail(scope, axis + ' intervals overlap: ' + sorted[i - 1].map(x => x.toFixed(4)).join('..') + ' and '
            + sorted[i].map(x => x.toFixed(4)).join('..'));
        } else if (sorted[i][0] > sorted[i - 1][1] + UV_TOL) {
          fail(scope, axis + ' has a GAP between ' + sorted[i - 1][1].toFixed(4) + ' and ' + sorted[i][0].toFixed(4)
            + ' - that strip of the canvas is never drawn');
        }
      }
    }
  }

  // ---- P6 one side per anchor ----------------------------------------------------------------------
  const sides = frames.map(f => f.side);
  const chosen = sides[0];
  if (sides.some(s => s !== chosen)) {
    fail(scope, 'the facets resolve to different sides (' + sides.join(', ') + ') - the client would draw part of'
      + ' the panel from behind; check the mmtr_hud face normals still point at the driver');
  } else if (chosen === 0) {
    fail(scope, 'every facet sits on the car\'s centre line (facingSide 0), so the client draws BOTH copies of the'
      + ' panel - move the dashboard off the centre plane');
  }

  notes.push(scope + ': ' + frames.length + ' facets, canvas ' + anchor.canvasWidthM.toFixed(4) + ' x '
    + anchor.canvasHeightM.toFixed(4) + ' m, folds about ' + varying + ', ' + sharedChecked
    + ' shared corner(s) continuous, u runs to the driver\'s '
    + (dot(frames[0].localX, frames[0].right) > 0 ? 'right' : 'left') + ' (side ' + chosen + ')');
  if (!axesAgree) notes.push(scope + ': facet frames were NOT consistent - see the failure above');
}

function main() {
  const args = process.argv.slice(2);
  const flag = name => {
    const i = args.indexOf(name);
    return i < 0 ? null : args[i + 1];
  };
  const report = args.includes('--report');

  let anchorFile = flag('--anchors');
  const configPath = flag('--config');
  if (!anchorFile && configPath) {
    if (!fs.existsSync(configPath)) {
      console.error('config not found: ' + configPath);
      process.exit(2);
    }
    const config = loadConfig(configPath);
    const stage = (config.stagingDir || path.join(path.dirname(config.sourceObj), '.pack_stage_' + config.id))
      .replace(/\$\{MC_ROOT\}/g, path.resolve(__dirname, '..', '..', '..'));
    anchorFile = path.join(stage, 'assets', 'mtr', 'mmtr_anchors_' + config.id + '.json');
  }
  if (!anchorFile || !fs.existsSync(anchorFile)) {
    console.error('usage: node verify_panel_frame.js --config <vehicle config.json> [--report]');
    console.error('       node verify_panel_frame.js --anchors <mmtr_anchors_<id>.json> [--report]');
    if (anchorFile) console.error('anchor file not found: ' + anchorFile + ' (run the packager on this config first)');
    process.exit(2);
  }

  const root = JSON.parse(fs.readFileSync(anchorFile, 'utf8'));
  const anchors = root.anchors || [];
  console.log('anchors : ' + anchorFile);
  console.log('');

  const huds = anchors.filter(a => a.kind === 'hud');
  for (const anchor of huds) verifyAnchor(anchor);

  notes.forEach(n => console.log('  ' + n));
  console.log('');

  if (failures.length) {
    console.log('FAIL (' + failures.length + ')');
    failures.forEach(f => console.log('  ! ' + f));
    process.exit(1);
  }
  const folded = huds.filter(a => (a.faces || []).length > 1).length;
  console.log('PASS: ' + huds.length + ' hud anchor(s), ' + folded + ' folded, place the canvas the way the client does');
  if (report) console.log('(client frame = OBJ file space (x, -y, -z); local +X = facingSide * (up x normal))');
}

main();
