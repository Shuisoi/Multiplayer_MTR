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
  const byName = new Map(anchors.map(a => [a.name, a]));

  const groupsOf = new Map();
  const props = fs.existsSync(propertiesFile) ? JSON.parse(fs.readFileSync(propertiesFile, 'utf8')) : { parts: [] };
  (props.parts || []).forEach((part, index) => (part.names || []).forEach(name => groupsOf.set(name, index)));

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
    const fit = fitSector(sweepGroup, domain, vpos);
    if (fit.error) { fail(scope, fit.error); continue; }

    const values = windshieldConfig[glassName] || {};
    if (values.wiper !== true) fail(scope, 'its glass does not have wiper=true (got ' + JSON.stringify(values.wiper) + ')');
    const near = (a, b, tol) => a !== undefined && Math.abs(a - b) <= tol;
    if (!near(values.pivotU, fit.pivotU, FRACTION_TOL)) fail(scope, 'pivotU ' + values.pivotU + ' != re-derived ' + fit.pivotU.toFixed(4));
    if (!near(values.pivotV, fit.pivotV, FRACTION_TOL)) fail(scope, 'pivotV ' + values.pivotV + ' != re-derived ' + fit.pivotV.toFixed(4));
    if (!near(values.armM, fit.armM, ARM_TOL_M)) fail(scope, 'armM ' + values.armM + ' != re-derived ' + fit.armM.toFixed(4));
    if (!near(values.parkAngleDeg, fit.parkAngleDeg, ANGLE_TOL_DEG)) fail(scope, 'parkAngleDeg ' + values.parkAngleDeg + ' != re-derived ' + fit.parkAngleDeg.toFixed(3));
    if (!near(values.sweepDeg, fit.sweepDeg, ANGLE_TOL_DEG)) fail(scope, 'sweepDeg ' + values.sweepDeg + ' != re-derived ' + fit.sweepDeg.toFixed(3));
    if (fit.maxOffPlane > OFF_PLANE_TOL_M) fail(scope, 'a fan vertex sits ' + fit.maxOffPlane.toFixed(4) + ' m off the glass plane (tol ' + OFF_PLANE_TOL_M + ')');
    if (!fit.rimInside) fail(scope, 'a fan vertex falls outside the glass it sweeps');
    fittedGlasses.add(glassName);
    notes.push(scope + ' -> ' + glassName + ': pivot (' + fit.pivotU.toFixed(4) + ', ' + fit.pivotV.toFixed(4) + ') arm ' +
      fit.armM.toFixed(3) + ' m, park ' + fit.parkAngleDeg.toFixed(2) + ' deg, sweep ' + fit.sweepDeg.toFixed(2) + ' deg');
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
