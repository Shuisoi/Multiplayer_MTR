#!/usr/bin/env node
/*
 * make_panelfix.js - writes the fixture verify_panel_frame.js is fault-injected against.
 *
 * verify_panel_frame.js checks the frame the CLIENT draws a folded dashboard in, so its fixture has to be
 * a dashboard that a client would actually place: off the car's centre line (otherwise facingSide() cannot
 * choose a side at all), folded, and with the canvas tiling exactly. The existing foldfix fixture fails
 * the first of those on purpose - it is a 4 m stub centred on the origin, built to exercise the packager's
 * unfold arithmetic - so it cannot stand in here.
 *
 * Rather than hand-computing a second synthetic panel, this copies the mmtr_hud anchors out of a real
 * packed BR101 (the 45-degree wings, both cabs) and writes them to fixture/stage-panelfix. The result is
 * small (a few kB of JSON, no OBJ) because these checks are pure anchor arithmetic.
 *
 *   node mmtr/tools/anchor-check/fixture/make_panelfix.js <mmtr_anchors_br101.json>
 *
 * The staged file is checked in: the self-test must not depend on the Blender model being present.
 */

'use strict';

const fs = require('fs');
const path = require('path');

const source = process.argv[2];
if (!source || !fs.existsSync(source)) {
  console.error('usage: node make_panelfix.js <mmtr_anchors_br101.json>');
  console.error('  (pack the BR101 first: node mmtr/tools/obj-mtr-packager/pack_vehicle.js mmtr/tools/obj-mtr-packager/example/vehicle.br101.json)');
  process.exit(2);
}

const root = JSON.parse(fs.readFileSync(source, 'utf8'));
const huds = (root.anchors || []).filter(a => a.kind === 'hud' && (a.faces || []).length > 1);
if (huds.length < 2) {
  console.error('expected the two folded mmtr_hud anchors of a double-ended loco, found ' + huds.length);
  process.exit(1);
}

const stage = path.join(__dirname, 'stage-panelfix');
fs.mkdirSync(path.join(stage, 'assets', 'mtr'), { recursive: true });

const out = {
  vehicleId: 'panelfix',
  hud: root.hud,
  anchors: huds.map(a => Object.assign({}, a, { name: a.name }))
};
fs.writeFileSync(path.join(stage, 'assets', 'mtr', 'mmtr_anchors_panelfix.json'), JSON.stringify(out), 'utf8');

// The config only exists so the verifier's --config path (and the self-test's runner) behaves like it does
// for a real vehicle. verify_panel_frame.js is pure anchor arithmetic and never reads the model, so this
// fixture deliberately ships no OBJ: sourceObj points at the existing foldfix stub only so that no config
// in here carries a path that does not resolve.
fs.writeFileSync(path.join(__dirname, 'panelfix.json'), JSON.stringify({
  id: 'panelfix',
  name: 'Client-frame panel fixture (anchors only - see make_panelfix.js)',
  color: '7FA8CC',
  transportMode: 'TRAIN',
  carLengthBlocks: 32.3729,
  carWidthBlocks: 5,
  carIndex: 0,
  bogieCount: 2,
  bogieOffsetBlocks: -9.2797,
  bogie2OffsetBlocks: 9.2797,
  couplingPadding1: 0,
  couplingPadding2: 0,
  sourceObj: '${MC_ROOT}/mmtr/tools/anchor-check/fixture/foldfix.obj',
  textureDir: '${MC_ROOT}/mmtr/tools/anchor-check/fixture',
  rotationDegY: 0,
  recenter: false,
  flipTextureV: true,
  groupMap: { body: ['body'], interior: [], anchor: ['mmtr_'] },
  doorAnimationType: 'STANDARD',
  doorSlidePx: 14,
  outputPackName: 'panelfix_v1',
  outputDir: '${MC_ROOT}/mmtr/tools/anchor-check/fixture/out',
  stagingDir: '${MC_ROOT}/mmtr/tools/anchor-check/fixture/stage-panelfix',
  packFormat: 18
}, null, 2) + '\n', 'utf8');

console.log('wrote stage-panelfix/assets/mtr/mmtr_anchors_panelfix.json (' + huds.length + ' hud anchors, '
  + huds.reduce((n, a) => n + a.faces.length, 0) + ' facets from ' + path.basename(source) + ')');
console.log('wrote panelfix.json');
