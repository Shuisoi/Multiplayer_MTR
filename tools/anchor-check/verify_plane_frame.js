#!/usr/bin/env node
/*
 * verify_plane_frame.js - checks that the CLIENT can turn a modelled wiper part inside the GLASS.
 *
 * verify_windshield.js proves the fitted sector in the glass's own 2D domain: park angle, stroke, pins,
 * loop closure. All of that is arithmetic in (right, up) and says nothing about the step after it - the
 * client has to reproduce that same 2D rotation in MODEL space, where MTR's GraphicsHolder can only
 * rotate about X, Y and Z. That gap is where a whole class of "the wiper moves, but wrongly" lives: the
 * offline fit can be perfect while the part swings about an axis that is not the glass's normal, about a
 * pivot that is not on the glass, or by an angle with the wrong sense.
 *
 * Two independent things are checked, because they fail independently:
 *
 *   F1  THE CLIENT'S OWN SOURCE. The angles the client rotates by are extracted from the glass's basis
 *       vectors, and reading them off those vectors "by eye" is WRONG for a tilted face: with
 *       M = rotateY(yaw)*rotateX(pitch)*rotateZ(roll) and columns (right, up, normal), the entries are
 *       sin(pitch) = -normal.y() and roll = atan2(right.y(), up.y()). The formula this file used to carry
 *       (pitch = asin(-up.y()), roll = atan2(up.x(), up.y())) is off by the glass's own tilt - 68 deg on
 *       the BR101 windscreen (notes/200, notes/179 10). This part reads MmtrWindshield.java and fails if
 *       the wrong form is back, or if the pivot stops being lifted onto the glass before it is used.
 *   F2  THE FRAME CONTRACT, on the packed anchors of a real vehicle: the basis must be right-handed and
 *       consistent with the packager's own `right`, the extraction must reproduce the basis it came from,
 *       the conjugated rotation must be about the NORMAL by exactly theta, and it must move the parked
 *       arm pin the same way the verified 2D kinematics does. The last one is the bridge between the two
 *       files: if it holds, "the in-game blade travels the way verify_windshield.js proved" follows.
 *
 * Usage:
 *   node mmtr/tools/anchor-check/verify_plane_frame.js --config mmtr/tools/obj-mtr-packager/example/vehicle.br101.json
 *   node mmtr/tools/anchor-check/verify_plane_frame.js --anchors <mmtr_anchors_<id>.json>
 */

'use strict';

const fs = require('fs');
const path = require('path');
const L = require('./lib.js');

// The extraction must reproduce the basis it was taken from. Matched to double precision: these are
// algebraic identities, and the only rounding is the packager's own 6-decimal rounding of the anchors -
// which is why the tolerance is not 1e-15.
const FRAME_TOL = 1.0E-6;
// The conjugated rotation about the normal. Same argument, and it is the pivot that carries the geometry,
// so this one is compared in metres: the pivot must be ON the glass to well below a millimetre.
const PIVOT_TOL_M = 1.0E-6;
// A degree of slack on "the 3D rotation and the 2D kinematics agree", for the same 6-decimal reason.
const BRIDGE_TOL_M = 1.0E-5;

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

/** OBJ file space -> the space the client renders in, as MmtrPanelQuad/MmtrWindshield define it. */
const toModelSpace = v => [v[0], -v[1], -v[2]];

/** The 3x3 product rotateY(yaw) * rotateX(pitch) * rotateZ(roll), row-major. */
function basisFromEuler(yawDeg, pitchDeg, rollDeg) {
  const cy = Math.cos(yawDeg * Math.PI / 180), sy = Math.sin(yawDeg * Math.PI / 180);
  const cp = Math.cos(pitchDeg * Math.PI / 180), sp = Math.sin(pitchDeg * Math.PI / 180);
  const cr = Math.cos(rollDeg * Math.PI / 180), sr = Math.sin(rollDeg * Math.PI / 180);
  return [
    [cy * cr + sy * sp * sr, -cy * sr + sy * sp * cr, sy * cp],
    [cp * sr, cp * cr, -sp],
    [-sy * cr + cy * sp * sr, sy * sr + cy * sp * cr, cy * cp]
  ];
}

/** The CORRECT extraction - mirrored here so this file can state what the client must be doing. */
function eulerFromBasis(right, up, normal) {
  return {
    pitch: Math.asin(Math.max(-1, Math.min(1, -normal[1]))) * 180 / Math.PI,
    yaw: Math.atan2(normal[0], normal[2]) * 180 / Math.PI,
    roll: Math.atan2(right[1], up[1]) * 180 / Math.PI
  };
}

/** The FORMER extraction, kept only to measure how wrong it was on real geometry. */
function wrongEulerFromBasis(right, up, normal) {
  return {
    pitch: Math.asin(Math.max(-1, Math.min(1, -up[1]))) * 180 / Math.PI,
    yaw: Math.atan2(normal[0], normal[2]) * 180 / Math.PI,
    roll: Math.atan2(up[0], up[1]) * 180 / Math.PI
  };
}

const columnsOf = m => [[m[0][0], m[1][0], m[2][0]], [m[0][1], m[1][1], m[2][1]], [m[0][2], m[1][2], m[2][2]]];
const multiply = (a, b) => a.map((row, i) => [0, 1, 2].map(j => row[0] * b[0][j] + row[1] * b[1][j] + row[2] * b[2][j]));
const transpose = m => [0, 1, 2].map(i => [0, 1, 2].map(j => m[j][i]));
const apply = (m, v) => m.map(row => row[0] * v[0] + row[1] * v[1] + row[2] * v[2]);
const maxAbsDiff = (a, b) => Math.max(...a.map((row, i) => Math.max(...row.map((x, j) => Math.abs(x - b[i][j])))));
const cross = L.cross;

/**
 * Reads a client source file with its comments stripped - the files document the wrong forms on purpose,
 * so a check that cannot tell code from prose would have to be deleted the moment someone explains the
 * bug in it - and returns its code with runs of whitespace collapsed.
 */
function readJavaCode(root, relativePath, failFn) {
  const file = path.join(root, relativePath);
  if (!fs.existsSync(file)) {
    failFn('client source', relativePath + ' not found');
    return null;
  }
  return fs.readFileSync(file, 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\/\/[^\n]*/g, ' ')
    .replace(/\s+/g, ' ');
}

/**
 * F1a: the client's own source for the glass frame. A formula that has been proved right once can be
 * edited back without any pack-level symptom, so the expressions themselves are part of the contract.
 */
function checkClientSource(root, failFn) {
  const code = readJavaCode(root, path.join('mmtr', 'game', 'fabric', 'src', 'main', 'java', 'org', 'mtr', 'mod', 'render', 'panel', 'MmtrWindshield.java'), failFn);
  if (code === null) {
    return;
  }

  const required = [
    ['the pitch must come from the NORMAL (sin(pitch) = -normal.y()), not from up.y()',
      /this\.pitch = Math\.toDegrees\(Math\.asin\(clamp\(-normal\.y\(\)\)\)\)/],
    ['the yaw must be atan2(normal.x(), normal.z())',
      /this\.yaw = Math\.toDegrees\(Math\.atan2\(normal\.x\(\), normal\.z\(\)\)\)/],
    ['the roll must be atan2(right.y(), up.y()), not atan2(up.x(), up.y())',
      /this\.roll = Math\.toDegrees\(Math\.atan2\(right\.y\(\), up\.y\(\)\)\)/],
    ['the pivot must be lifted onto the glass before it is used (plane.pointAt)',
      /private static void applyRotation\([\s\S]*?final Vector pivot = plane\.pointAt\(pivotX, pivotY\);/],
    ['the modelled blade must rotate about its pin ON the glass (plane.pointAt)',
      /final Vector pin = plane\.pointAt\(m\[0\], m\[1\]\);/],
    ['the config fractions must be converted to centre-relative metres before pointAt sees them',
      // The holder is `config` for a glass's single wiper and `wiper` for one entry of its "wipers" array
      // (notes/279), so the guard names the CONVERSION rather than the variable: a fraction times the
      // glass's width minus half of it. Pinning the receiver made this fire on a correct refactor.
      /\.pivotU \* widthM - halfWidthM/],
    ['the rod must turn about its own pivot by the angle the loop closure gives it, not by the crank angle',
      /applyRotation\(graphicsHolder, plane, p2\[0\], p2\[1\], rodTurnDeg\)/],
    ['the fall direction must be the WORLD\'S DOWN projected in the plane\'s own basis. The anchors are ' +
      'authored y-UP (seat.up = [0,1,0]; the dashboard sits below its windscreen) and toModelSpace mirrors y, ' +
      'so in the space this module works in +y is DOWN and down is (right.y, up.y)',
      /final double alongUp = plane\.up\.y\(\);/]
  ];
  for (const [what, pattern] of required) {
    if (!pattern.test(code)) failFn('client source', 'missing: ' + what);
  }

  const forbidden = [
    ['the disproven pitch extraction is back (it reads the tilt off up.y())', /Math\.asin\(clamp\(-up\.y\(\)\)\)/],
    ['the disproven roll extraction is back (atan2(up.x(), up.y()))', /Math\.atan2\(up\.x\(\), up\.y\(\)\)/],
    ['the raw plane coordinates are being translated again instead of the model-space pivot',
      /translate\(pivotX, pivotY, 0\)/],
    ['the disproven "flip the basis when up.y < 0" fall correction is back - it forces the drops towards the ' +
      'canvas\'s -y whatever the anchor says, and the canvas\'s -y is the real UP on one of the two BR101 cabs, ' +
      'so the rain climbs the windscreen',
      /plane\.up\.y\(\) < 0 \? -1 : 1/],
    ['the mirrored fall projection is back (down = (-right.y, -up.y), i.e. "gravity has a negative y"). In this ' +
      'y-down space that sends the rain UP the glass, and its old guard was a tautology: that pair\'s y is ' +
      '-(right.y^2 + up.y^2), negative whatever the anchor says, so the guard could never fire',
      /final double alongUp = -plane\.up\.y\(\);/]
  ];
  for (const [what, pattern] of forbidden) {
    if (pattern.test(code)) failFn('client source', what);
  }

  if (!failures.some(m => m.startsWith('client source'))) {
    notes.push('client source: the glass frame is extracted from the basis (pitch from the normal) and every pivot is lifted onto the glass before it is used');
  }
}

/**
 * F1b: the wiper geometry must be drawn EXACTLY ONCE. The per-part transform can only move a copy that is
 * drawn by the per-part path, so a mechanism part left in either of the vehicle's two optimised batches is
 * a second, static copy: parked it hides underneath the moving one, and the moment the wipers run the car
 * looks like it grew another wiper. The doors-closed batch is the one that bites, because that is the batch
 * a vehicle normally draws its body from - not a "doors only" list.
 */
function checkPartDrawSource(root, failFn) {
  const code = readJavaCode(root, path.join('mmtr', 'game', 'fabric', 'src', 'main', 'java', 'org', 'mtr', 'mod', 'resource', 'ModelPropertiesPart.java'), failFn);
  if (code === null) {
    return;
  }
  // The mechanism branch registers its position into a THROWAWAY map (geometry only materialises from a
  // recorded transformation) and the other branch keeps the stock behaviour for doors and normal parts.
  const registersIntoScratch = /if \(mechanism\) \{ addObjModelPosition\(objModels, new Object2ObjectOpenHashMap<>\(\), x, y, z, flipped, modelYOffset\); \}/;
  const othersUnchanged = /else \{ if \(!isDoor\(\)\) \{ addObjModelPosition\(objModels, objModelsForPartConditionAndRenderStage, x, y, z, flipped, modelYOffset\); \} addObjModelPosition\(objModels, objModelsForPartConditionAndRenderStageDoorsClosed, x, y, z, flipped, modelYOffset\); \}/;
  const mechanismBranch = /if \(mechanism\) \{([\s\S]{0,400}?)\} else \{/.exec(code);
  if (!othersUnchanged.test(code)) {
    failFn('client source', 'doors and normal parts no longer go into both vehicle batches unchanged - a door would lose its closed copy or a body part would vanish');
  }
  if (!registersIntoScratch.test(code)) {
    if (mechanismBranch && /objModelsForPartConditionAndRenderStage/.test(mechanismBranch[1])) {
      failFn('client source', 'the mechanism part is registered into a vehicle-level optimised batch, so a SECOND STATIC COPY of the wiper is drawn on top of the moving one');
    } else {
      failFn('client source', 'the mechanism part does not register its position into a throwaway map, so its wrapper is empty and the wiper disappears (an OBJ wrapper materialises geometry only from a recorded transformation)');
    }
  }
  if (/if \(!isDoor\(\) && !mechanism\) \{/.test(code)) {
    failFn('client source', 'the mechanism part is added to the doors-closed batch again, so a second static copy of the wiper is drawn on top of the moving one');
  }
  if (!failures.some(m => m.startsWith('client source'))) {
    notes.push('client source: a mechanism part leaves both optimised batches, so it is drawn once - by the per-part kinematics');
  }
}

/** The plane's own frame, in the space the client renders in. */
function planeOf(anchor) {
  const normalRaw = toModelSpace(anchor.normal);
  const upRaw = toModelSpace(anchor.up);
  const normal = L.norm(normalRaw);
  // The client orthonormalises up against the normal, so a written basis that is not orthogonal still
  // produces a usable frame there - but it is not the frame the packager MEANT, and the tilt it silently
  // loses is exactly the quantity every angle below depends on. The raw vectors are therefore kept and
  // checked before any projection happens.
  const upUnit = L.norm(upRaw);
  const up = L.norm(L.sub(upUnit, L.scl(normal, L.dot(upUnit, normal))));
  const right = L.norm(cross(up, normal));
  return { normal, up, right, normalRaw, upRaw, upUnit };
}

/**
 * F2, per windscreen pane: does the client's rotation act on THIS glass the way the verified 2D sector
 * says it must?
 */
function checkPane(anchor, config, failFn, note) {
  const scope = 'pane ' + anchor.name;
  const frame = planeOf(anchor);
  const { normal, up, right, normalRaw, upRaw } = frame;

  // The WRITTEN basis must be usable as one. Checked on the raw values: measuring this after
  // orthonormalising (which the client does) would always report zero.
  const radius = Math.max(Math.hypot(...normalRaw), Math.hypot(...upRaw));
  const dotNU = L.dot(normalRaw, upRaw) / (radius * radius);
  if (Math.abs(dotNU) > FRAME_TOL) {
    failFn(scope, 'the anchor\'s up is not perpendicular to its normal (cos ' + dotNU.toFixed(6) + '), so the glass has no well-defined frame - the tilt the client would lose is what every angle here depends on');
  }

  // ...and RIGHT-HANDED, which is the check with teeth for the SWEEP DIRECTION: the packager writes
  // `right` itself, and if it disagrees with up x normal the in-plane rotation runs the other way, so
  // the blade fans backwards - a symptom the fitted sector cannot see, because that fit is per-pane 2D.
  if (anchor.right) {
    const stored = L.norm(toModelSpace(anchor.right));
    const fromBasis = cross(up, normal);
    const gap = Math.hypot(stored[0] - fromBasis[0], stored[1] - fromBasis[1], stored[2] - fromBasis[2]);
    if (gap > FRAME_TOL) {
      failFn(scope, 'the written right is not up x normal (off by ' + gap.toExponential(3) + '), so the blade would fan the wrong way round');
    }
  }

  // The extraction must reproduce the very basis it came from: that is what makes the conjugation below
  // a rotation about the normal rather than about some skew axis.
  const euler = eulerFromBasis(right, up, normal);
  const columns = columnsOf(basisFromEuler(euler.yaw, euler.pitch, euler.roll));
  const roundTrip = maxAbsDiff(columns, [right, up, normal]);
  if (roundTrip > FRAME_TOL) {
    failFn(scope, 'the extracted angles do not rebuild the glass basis (worst element off by ' + roundTrip.toExponential(3) + ')');
  }
  const wrong = wrongEulerFromBasis(right, up, normal);
  const wrongColumns = columnsOf(basisFromEuler(wrong.yaw, wrong.pitch, wrong.roll));
  const wrongGap = maxAbsDiff(wrongColumns, [right, up, normal]);
  const wrongPitchDeg = Math.abs(wrong.pitch - euler.pitch);
  note(scope + ': basis reproduced to ' + roundTrip.toExponential(1) + '; the disproven extraction (pitch ' +
    wrong.pitch.toFixed(2) + ' deg instead of ' + euler.pitch.toFixed(2) + ' deg) misses the basis by ' +
    wrongGap.toFixed(3) + ', i.e. ' + wrongPitchDeg.toFixed(2) + ' deg of tilt - the bug this file exists for');

  const values = config || {};
  // The conjugation the client performs, K = M * Rz(theta) * M^-1.
  const M = basisFromEuler(euler.yaw, euler.pitch, euler.roll);
  const thetaDeg = (values.sweepDeg === undefined ? 46 : values.sweepDeg) * (values.sweepSign === undefined ? 1 : values.sweepSign);
  const radians = thetaDeg * Math.PI / 180;
  const rz = [[Math.cos(radians), -Math.sin(radians), 0], [Math.sin(radians), Math.cos(radians), 0], [0, 0, 1]];
  const K = multiply(multiply(M, rz), transpose(M));

  // The axis of K, from its antisymmetric part, must be the glass normal.
  const sinTheta = Math.sin(radians);
  if (Math.abs(sinTheta) > 1.0E-6) {
    const axis = L.norm([(K[2][1] - K[1][2]) / (2 * sinTheta), (K[0][2] - K[2][0]) / (2 * sinTheta), (K[1][0] - K[0][1]) / (2 * sinTheta)]);
    const axisGap = Math.hypot(axis[0] - normal[0], axis[1] - normal[1], axis[2] - normal[2]);
    if (axisGap > FRAME_TOL) {
      failFn(scope, 'the rotation axis is ' + axisGap.toExponential(3) + ' off the glass normal - the part would swing out of the glass');
    }
    // ...and the angle must be exactly theta, not merely close.
    const angleDeg = Math.acos(Math.max(-1, Math.min(1, (K[0][0] + K[1][1] + K[2][2] - 1) / 2))) * 180 / Math.PI;
    if (Math.abs(angleDeg - Math.abs(thetaDeg)) > 0.01) {
      failFn(scope, 'the rotation turns by ' + angleDeg.toFixed(3) + ' deg where the stroke asked for ' + Math.abs(thetaDeg).toFixed(3));
    }
  }

  // THE BRIDGE: the client's 3D rotation must carry the parked arm pin to where the 2D kinematics (the
  // maths verify_windshield.js validates) puts it. If this holds, the in-game blade travels the verified
  // sector; if it does not, the fit is right and the part still goes somewhere else.
  if (values.pivotU !== undefined && values.pinAU !== undefined && anchor.widthM) {
    const halfW = anchor.widthM / 2, halfH = anchor.heightM / 2;
    const centre = toModelSpace([anchor.x, anchor.y, anchor.z]);
    const at = (u, v) => L.add(centre, L.add(L.scl(right, u * anchor.widthM - halfW), L.scl(up, v * anchor.heightM - halfH)));
    const pivot = at(values.pivotU, values.pivotV);
    const parked = at(values.pinAU, values.pinAV);
    const in3d = L.add(pivot, apply(K, L.sub(parked, pivot)));
    // The same point by rotating within the plane, which is the form the 2D verifier proves.
    const rel = L.sub(parked, pivot);
    const in2d = L.add(pivot, L.add(L.scl(rel, Math.cos(radians)), L.scl(cross(normal, rel), Math.sin(radians))));
    const bridgeGap = Math.hypot(in3d[0] - in2d[0], in3d[1] - in2d[1], in3d[2] - in2d[2]);
    if (bridgeGap > BRIDGE_TOL_M) {
      failFn(scope, 'the client\'s rotation moves the arm pin ' + (bridgeGap * 1000).toFixed(2) +
        ' mm away from where the verified in-plane kinematics puts it');
    }
    // Third assertion, cheap and independent: the pivot must be ON the glass.
    const offPlane = Math.abs(L.dot(L.sub(pivot, centre), normal));
    if (offPlane > PIVOT_TOL_M) {
      failFn(scope, 'the pivot is ' + (offPlane * 1000).toFixed(2) + ' mm off the glass plane');
    }
    note(scope + ': conjugation about the normal by ' + thetaDeg.toFixed(2) + ' deg agrees with the in-plane kinematics to ' +
      (bridgeGap * 1000).toExponential(1) + ' mm; pivot on the glass to ' + (offPlane * 1000).toExponential(1) + ' mm');
  }
}

/**
 * F2b, per windscreen pane: WHICH WAY DOES THE RAIN RUN?
 *
 * The physics sends every bead along +down, and the renderer maps +drop.y to +up, so the world direction a
 * bead travels in is down[0]*right + down[1]*up. That direction must have a NEGATIVE real-vertical
 * component, which in this y-down space means a POSITIVE module-space y.
 *
 * The oracle is the anchor file's own authorship, and both halves of it are statements about the real
 * vehicle rather than about the code: a SEAT's up is the sky (authored [0,1,0]), and a DASHBOARD sits
 * BELOW its windscreen. Either fixes the sign of up in stored coordinates; toModelSpace(x,y,z) =
 * (x,-y,-z) then fixes it in the space the rain lives in. Without one of those anchors the file cannot
 * prove its own up axis, and this check says so rather than guessing - the source-side pattern in
 * checkClientSource is what guards the formula itself.
 */
function checkRainDirection(anchors, fail, note) {
  const panes = anchors.filter(a => a.kind === 'windshield');
  if (panes.length === 0) {
    return;
  }
  const seats = anchors.filter(a => a.kind === 'seat');
  const huds = anchors.filter(a => a.kind === 'hud');
  const glassY = panes[0].y;
  const hudBelow = huds.length > 0 && huds.every(h => h.y < glassY);
  const seatUpIsPlusY = seats.length > 0 && seats.every(a => a.up && a.up[1] > 0.99);

  if (huds.length > 0 && !hudBelow) {
    fail('anchors', 'a dashboard anchor sits ABOVE its windscreen in stored coordinates (hud y ' +
      huds.map(h => h.y.toFixed(3)).join('/') + ' vs glass y ' + glassY.toFixed(3) + '), which is physically ' +
      'impossible - so this file is authored y-down and "which way is down" is being projected in the wrong space');
    return;
  }
  // THE DASHBOARD IS THE AUTHORITATIVE ORACLE, and the seat only a supporting one: saf101's seat anchors are
  // hand-authored with up = -Y (a seat pointing at the ground) while its dashboard sits a metre BELOW its
  // windscreen, so a seat's written `up` can be sloppy in a way a POSITION cannot. The module agrees - it
  // decides the water's side from the dashboard's normal, not from a seat.
  const authoredYUp = hudBelow || seatUpIsPlusY;
  if (!authoredYUp) {
    note('rain direction: this file has neither a dashboard nor a seat anchor to establish which way is up, so ' +
      'the projection is only checked against the client source');
    return;
  }

  for (const pane of panes) {
    const { up, right } = planeOf(pane);
    const magnitude = Math.hypot(right[1], up[1]);
    if (magnitude < 1.0E-4) {
      continue;   // edge-on to gravity: the client falls back to straight down the panel
    }
    const down = [right[1] / magnitude, up[1] / magnitude];
    const world = [
      down[0] * right[0] + down[1] * up[0],
      down[0] * right[1] + down[1] * up[1],
      down[0] * right[2] + down[1] * up[2]
    ];
    if (world[1] <= 0) {
      fail('pane ' + pane.name, 'the direction this pane sends its water along points ' +
        (-world[1]).toFixed(3) + ' UPWARDS (real-vertical), so the rain runs up the windscreen');
    } else {
      note('pane ' + pane.name + ': fall direction real-vertical ' + (-world[1]).toFixed(3) +
        ' (negative = downhill), canvas down=(' + down.map(v => v.toFixed(3)).join(', ') + '); the authored up ' +
        'points ' + (-up[1] > 0 ? 'up' : 'DOWN - the winding got it, and the projection does not care'));
    }
  }
}

function main() {
  const args = process.argv.slice(2);
  const configIndex = args.indexOf('--config');
  const anchorsIndex = args.indexOf('--anchors');
  const configPath = configIndex >= 0 ? args[configIndex + 1] : null;
  let anchorFile = anchorsIndex >= 0 ? args[anchorsIndex + 1] : null;
  let config = null;

  if (configPath) {
    if (!fs.existsSync(configPath)) {
      console.error('config not found: ' + configPath);
      process.exit(2);
    }
    config = loadConfig(configPath);
    const stage = config.stagingDir || path.join(path.dirname(config.sourceObj), '.pack_stage_' + config.id);
    anchorFile = path.join(stage, 'assets', 'mtr', 'mmtr_anchors_' + config.id + '.json');
  }
  if (!anchorFile || !fs.existsSync(anchorFile)) {
    console.error('usage: node verify_plane_frame.js --config <vehicle config.json> | --anchors <anchor json>');
    console.error('anchor file not found: ' + anchorFile);
    process.exit(2);
  }

  const data = JSON.parse(fs.readFileSync(anchorFile, 'utf8'));
  const anchors = data.anchors || [];
  const windshieldConfig = data.windshield || {};

  console.log('anchors  : ' + anchorFile);
  console.log('config   : ' + (configPath || '(none - source guard and frame contract only)'));
  console.log('');

  // F1 is about the client, so it needs the checkout; a bare anchor file still gets F2.
  const root = resolveRoot();
  if (fs.existsSync(path.join(root, 'mmtr', 'game', 'fabric', 'src', 'main', 'java', 'org', 'mtr', 'mod', 'render', 'panel', 'MmtrWindshield.java'))) {
    checkClientSource(root, fail);
    checkPartDrawSource(root, fail);
  }

  // The panes that actually have a wiper: a glass without one is never rotated, so it has nothing to say
  // about the frame.
  //
  // A pane has a wiper when EITHER the flat block says so (one wiper) OR one of its "wipers" entries does
  // (two or more wipers on one glass, notes/279). Reading only the flat field made every multi-wiper glass
  // invisible here - and a model whose only glass carries two wipers then failed with "no windscreen pane
  // has wiper=true", which reads like a broken model rather than like an outdated checker.
  const wiperEntries = glass => {
    const block = windshieldConfig[glass.name] || {};
    if (Array.isArray(block.wipers) && block.wipers.length > 0) {
      return block.wipers.filter(one => one && one.wiper !== false);
    }
    return block.wiper === true ? [block] : [];
  };
  const panes = anchors.filter(a => a.kind === 'windshield' && wiperEntries(a).length > 0);
  if (panes.length === 0) {
    fail('anchors', 'no windscreen pane has wiper=true, so the glass frame is untested');
  }
  let wiperCount = 0;
  for (const pane of panes) {
    // Once per WIPER, each with its OWN stroke and direction: the frame checks repeat (they are properties
    // of the glass) but the conjugation, the angle and the bridge are per blade, and checking only the
    // first one would leave the second blade's fit unproven.
    const entries = wiperEntries(pane);
    wiperCount += entries.length;
    for (const values of entries) {
      checkPane(pane, values, fail, message => notes.push(message));
    }
  }
  checkRainDirection(anchors, fail, message => notes.push(message));

  for (const note of notes) {
    console.log('  ' + note);
  }
  console.log('');
  for (const failure of failures) {
    console.log('FAIL ' + failure);
  }
  if (failures.length === 0) {
    console.log('PASS: ' + wiperCount + ' wiper(s) on ' + panes.length + ' pane(s) - the client rotates the part about the glass NORMAL, through the glass pivot, by the stroke the 2D fit proves, and projects gravity downhill in the glass plane');
    process.exit(0);
  }
  console.log('');
  console.log(failures.length + ' failure(s)');
  process.exit(1);
}

main();
