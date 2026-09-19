#!/usr/bin/env node
/*
 * selftest.js - proves the anchor-check verifiers actually catch broken data.
 *
 * A verifier that always passes is worse than none: it launders a guess into "verified". So this takes
 * the data a pack just produced, breaks it in each way that matters, and asserts that the matching
 * verifier rejects every one of them - and for the RIGHT reason, by matching the expected message.
 *
 * Two suites, one entry point:
 *   - folded dashboard facets  -> verify_facets.js
 *   - wiper action sector      -> verify_windshield.js
 *
 * Usage:
 *   node mmtr/tools/obj-mtr-packager/pack_vehicle.js mmtr/tools/anchor-check/fixture/foldfix.json
 *   node mmtr/tools/obj-mtr-packager/pack_vehicle.js mmtr/tools/anchor-check/fixture/wipefix.json
 *   node mmtr/tools/anchor-check/selftest.js
 */

'use strict';

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

/** The hud anchor that carries facet data, or null when the fixture stopped being folded. */
function foldedHud(data) {
  return (data.anchors || []).find(a => a.kind === 'hud' && a.faces && a.faces.length > 1);
}

const SUITES = [
  {
    name: 'folded dashboard facets',
    config: path.join(__dirname, 'fixture', 'foldfix.json'),
    anchors: path.join(__dirname, 'fixture', 'stage', 'assets', 'mtr', 'mmtr_anchors_foldfix.json'),
    verifier: path.join(__dirname, 'verify_facets.js'),
    mutations: [
      {
        name: 'v axis not flipped (dashboard reads upside down)',
        expect: 'upside down',
        apply: data => foldedHud(data).faces.forEach(f => { const v0 = 1 - f.v1, v1 = 1 - f.v0; f.v0 = v0; f.v1 = v1; })
      },
      {
        name: 'a facet uv rect shifted, breaking the crease',
        expect: 'break at the crease',
        apply: data => { const hud = foldedHud(data); hud.faces[1].v0 += 0.02; hud.faces[1].v1 += 0.02; }
      },
      {
        name: 'a facet nudged off its plane',
        expect: 'off the facet plane',
        apply: data => {
          const f = foldedHud(data).faces[1];
          f.x += f.normal[0] * 0.05; f.y += f.normal[1] * 0.05; f.z += f.normal[2] * 0.05;
        }
      },
      {
        name: 'a facet shrunk, so it no longer covers its face',
        expect: 'outside the facet rectangle',
        apply: data => { foldedHud(data).faces[1].heightM *= 0.8; }
      },
      {
        name: 'facet data dropped entirely',
        expect: 'no facet data',
        apply: data => { const hud = foldedHud(data); delete hud.faces; delete hud.canvasWidthM; delete hud.canvasHeightM; }
      },
      {
        name: 'canvas size lying about the unfolded extent',
        expect: 'canvasWidthM/canvasHeightM',
        apply: data => { delete foldedHud(data).canvasHeightM; }
      }
    ]
  },
  {
    name: 'wiper action sector',
    config: path.join(__dirname, 'fixture', 'wipefix.json'),
    anchors: path.join(__dirname, 'fixture', 'stage-wipefix', 'assets', 'mtr', 'mmtr_anchors_wipefix.json'),
    verifier: path.join(__dirname, 'verify_windshield.js'),
    mutations: [
      {
        name: 'park angle moved 10 degrees off the modelled fan',
        expect: 'parkAngleDeg',
        apply: data => { data.windshield.windshield_1_1.parkAngleDeg += 10; }
      },
      {
        name: 'sweep angle halved',
        expect: 'sweepDeg',
        apply: data => { data.windshield.windshield_1_1.sweepDeg /= 2; }
      },
      {
        name: 'pivot shifted sideways',
        expect: 'pivotU',
        apply: data => { data.windshield.windshield_1_1.pivotU += 0.05; }
      },
      {
        name: 'arm length wrong',
        expect: 'armM',
        apply: data => { data.windshield.windshield_1_1.armM *= 0.7; }
      },
      {
        name: 'fitted block missing entirely (a sweep that clears nothing)',
        expect: 'wiper=true',
        apply: data => { data.windshield.windshield_1_1 = {}; }
      },
      {
        name: 'solid wiper present but the drawn blade left on',
        expect: 'drawBlade=false',
        apply: data => { data.windshield.windshield_1_1.drawBlade = true; }
      },
      {
        name: 'sweep pointing at a glass that does not exist',
        expect: 'no matching windshield_1_9',
        apply: data => {
          const sweep = data.anchors.find(a => a.kind === 'wipersweep');
          sweep.name = 'wipersweep_1_9';
          sweep.pane = 9;
        }
      },
      {
        name: 'a lone index read as a PANE instead of the cab',
        expect: 'lone index is the CAB',
        apply: data => { data.anchors.find(a => a.name === 'windshield_2').cab = 1; }
      },
      {
        name: 'the second pivot of a parallel linkage moved',
        expect: 'pivot2',
        apply: data => { data.windshield.windshield_1_3.pivot2U += 0.06; }
      },
      {
        name: 'a blade end moved (the linkage would sit on the wrong glass)',
        expect: 'blade ends were written',
        apply: data => { data.windshield.windshield_1_3.bladeBU -= 0.05; }
      },
      {
        name: 'parallel linkage present but the second pivot dropped',
        expect: 'no pivot2 was written',
        apply: data => { delete data.windshield.windshield_1_3.pivot2U; delete data.windshield.windshield_1_3.pivot2V; }
      },
      {
        name: 'a second pivot invented for a single-axis wiper',
        expect: 'no wiperrod_',
        apply: data => { data.windshield.windshield_1_1.pivot2U = 0.5; data.windshield.windshield_1_1.pivot2V = 0.5; }
      },
      {
        name: 'sector radius too short to reach the blade',
        expect: 'does not reach the blade',
        apply: data => { data.windshield.windshield_1_1.armM *= 0.6; }
      }
    ]
  }
];

function runVerifier(verifier, config) {
  const result = spawnSync(process.execPath, [verifier, '--config', config], { encoding: 'utf8' });
  return { status: result.status, output: (result.stdout || '') + (result.stderr || '') };
}

let totalMutations = 0, totalCaught = 0;

for (const suite of SUITES) {
  console.log('== ' + suite.name + ' (' + path.basename(suite.verifier) + ') ==');
  if (!fs.existsSync(suite.anchors)) {
    console.error('  no anchor file at ' + suite.anchors);
    console.error('  run: node mmtr/tools/obj-mtr-packager/pack_vehicle.js ' + suite.config);
    process.exit(2);
  }
  const pristine = fs.readFileSync(suite.anchors, 'utf8');

  const baseline = runVerifier(suite.verifier, suite.config);
  if (baseline.status !== 0) {
    console.error('  BASELINE FAILS - fix the packager before trusting this self-test:');
    console.error(baseline.output);
    process.exit(1);
  }
  console.log('  baseline: PASS (the unmodified pack output is accepted)');

  let caught = 0;
  for (const mutation of suite.mutations) {
    totalMutations++;
    const data = JSON.parse(pristine);
    mutation.apply(data);
    fs.writeFileSync(suite.anchors, JSON.stringify(data), 'utf8');
    const result = runVerifier(suite.verifier, suite.config);
    fs.writeFileSync(suite.anchors, pristine, 'utf8');

    if (result.status !== 0 && result.output.includes(mutation.expect)) {
      caught++; totalCaught++;
      console.log('  caught  : ' + mutation.name + '  ->  "' + mutation.expect + '"');
    } else {
      console.log('  MISSED  : ' + mutation.name + '  (exit ' + result.status + ', expected "' + mutation.expect + '")');
      console.log(result.output.split('\n').map(l => '            ' + l).join('\n'));
    }
  }
  console.log('  ' + caught + '/' + suite.mutations.length + ' caught');
  console.log('');
}

if (totalCaught === totalMutations) {
  console.log('PASS: the verifiers caught all ' + totalMutations + ' injected faults');
} else {
  console.log('FAIL: only ' + totalCaught + '/' + totalMutations + ' injected faults were caught');
  process.exit(1);
}
