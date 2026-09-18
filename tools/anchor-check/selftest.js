#!/usr/bin/env node
/*
 * selftest.js - proves verify_facets.js actually catches broken facet data.
 *
 * A verifier that always passes is worse than none: it launders a guess into "verified". So this
 * takes the facet data the packager just emitted, breaks it in each way that matters, and asserts
 * that verify_facets.js rejects every one of them - and for the RIGHT reason, by matching the
 * expected check.
 *
 * Usage:
 *   node mmtr/tools/obj-mtr-packager/pack_vehicle.js mmtr/tools/anchor-check/fixture/foldfix.json
 *   node mmtr/tools/anchor-check/selftest.js
 */

'use strict';

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const CONFIG = path.join(__dirname, 'fixture', 'foldfix.json');
const ANCHORS = path.join(__dirname, 'fixture', 'stage', 'assets', 'mtr', 'mmtr_anchors_foldfix.json');

// Each mutation breaks ONE property, and names the text verify_facets.js must complain about.
const MUTATIONS = [
  {
    name: 'v axis not flipped (dashboard reads upside down)',
    expect: 'upside down',
    apply: a => a.faces.forEach(f => {
      const v0 = 1 - f.v1, v1 = 1 - f.v0;
      f.v0 = v0; f.v1 = v1;
    })
  },
  {
    name: 'a facet uv rect shifted, breaking the crease',
    expect: 'break at the crease',
    apply: a => { a.faces[1].v0 += 0.02; a.faces[1].v1 += 0.02; }
  },
  {
    name: 'a facet nudged off its plane',
    expect: 'off the facet plane',
    apply: a => {
      const f = a.faces[1];
      f.x += f.normal[0] * 0.05;
      f.y += f.normal[1] * 0.05;
      f.z += f.normal[2] * 0.05;
    }
  },
  {
    name: 'a facet shrunk, so it no longer covers its face',
    expect: 'outside the facet rectangle',
    apply: a => { a.faces[1].heightM *= 0.8; }
  },
  {
    name: 'facet data dropped entirely',
    expect: 'no facet data',
    apply: a => { delete a.faces; delete a.canvasWidthM; delete a.canvasHeightM; }
  },
  {
    name: 'canvas size lying about the unfolded extent',
    expect: 'canvasWidthM/canvasHeightM',
    apply: a => { delete a.canvasHeightM; }
  }
];

function runVerifier() {
  const result = spawnSync(process.execPath, [path.join(__dirname, 'verify_facets.js'), '--config', CONFIG], { encoding: 'utf8' });
  return { status: result.status, output: (result.stdout || '') + (result.stderr || '') };
}

function main() {
  if (!fs.existsSync(ANCHORS)) {
    console.error('no anchor file at ' + ANCHORS + '\nrun: node mmtr/tools/obj-mtr-packager/pack_vehicle.js ' + CONFIG);
    process.exit(2);
  }
  const pristine = fs.readFileSync(ANCHORS, 'utf8');

  const baseline = runVerifier();
  if (baseline.status !== 0) {
    console.error('BASELINE FAILS - fix the packager before trusting this self-test:');
    console.error(baseline.output);
    process.exit(1);
  }
  console.log('baseline: PASS (the unmodified pack output is accepted)');

  let broken = 0;
  for (const mutation of MUTATIONS) {
    const data = JSON.parse(pristine);
    const anchor = (data.anchors || []).find(a => a.kind === 'hud' && a.faces && a.faces.length > 1);
    if (!anchor) {
      console.error('no multi-facet hud anchor to mutate; is the fixture still folded?');
      process.exit(2);
    }
    mutation.apply(anchor);
    fs.writeFileSync(ANCHORS, JSON.stringify(data), 'utf8');
    const result = runVerifier();
    fs.writeFileSync(ANCHORS, pristine, 'utf8');

    const detected = result.status !== 0 && result.output.includes(mutation.expect);
    if (detected) {
      broken++;
      console.log('caught  : ' + mutation.name + '  ->  "' + mutation.expect + '"');
    } else {
      console.log('MISSED  : ' + mutation.name + '  (exit ' + result.status + ', expected "' + mutation.expect + '")');
      console.log(result.output.split('\n').map(l => '          ' + l).join('\n'));
    }
  }

  console.log('');
  if (broken === MUTATIONS.length) {
    console.log('PASS: verify_facets.js caught all ' + MUTATIONS.length + ' injected faults');
  } else {
    console.log('FAIL: only ' + broken + '/' + MUTATIONS.length + ' injected faults were caught');
    process.exit(1);
  }
}

main();
