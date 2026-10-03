#!/usr/bin/env node
/*
 * selftest.js - proves the anchor-check verifiers actually catch broken data.
 *
 * A verifier that always passes is worse than none: it launders a guess into "verified". So this takes
 * the data a pack just produced, breaks it in each way that matters, and asserts that the matching
 * verifier rejects every one of them - and for the RIGHT reason, by matching the expected message.
 *
 * Three suites, one entry point:
 *   - folded dashboard facets  -> verify_facets.js
 *   - wiper action sector      -> verify_windshield.js
 *   - dashboard in the client frame -> verify_panel_frame.js
 *   - glass frame in the client     -> verify_plane_frame.js
 *   - door leaves opening the right way -> verify_doors.js
 *
 * A suite whose fault lives in CLIENT CODE rather than in the packed data injects it there instead, with
 * `applyToSource` (see the last suite): the glass-frame formula cannot be broken from the anchors at all,
 * because the fixtures' windscreens are axis-aligned - on a glass with no tilt the wrong extraction builds
 * the same matrix as the right one. The restore is in a `finally`, so a failing injection still leaves the
 * checkout exactly as it was found.
 *
 * Usage:
 *   node mmtr/tools/obj-mtr-packager/pack_vehicle.js mmtr/tools/anchor-check/fixture/foldfix.json
 *   node mmtr/tools/obj-mtr-packager/pack_vehicle.js mmtr/tools/anchor-check/fixture/wipefix.json
 *   node mmtr/tools/obj-mtr-packager/pack_vehicle.js mmtr/tools/obj-mtr-packager/example/vehicle.saf420cab_a.json
 *   node mmtr/tools/anchor-check/selftest.js
 */

'use strict';

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

/** The client file a source-level injection edits by default, and its pristine text. */
const CLIENT_SOURCE = path.resolve(__dirname, '..', '..', 'game', 'fabric', 'src', 'main', 'java', 'org', 'mtr', 'mod', 'render', 'panel', 'MmtrWindshield.java');
/** The part-rendering file, for the injections that are about WHERE a part is drawn. */
const PART_SOURCE = path.resolve(__dirname, '..', '..', 'game', 'fabric', 'src', 'main', 'java', 'org', 'mtr', 'mod', 'resource', 'ModelPropertiesPart.java');

/** The hud anchor that carries facet data, or null when the fixture stopped being folded. */
function foldedHud(data) {
  return (data.anchors || []).find(a => a.kind === 'hud' && a.faces && a.faces.length > 1);
}

/**
 * The per-wiper block of a glass, however the pack wrote it: the "wipers" entry whose own wiperIndex
 * names `wiper`, or the glass's own flat block when the glass carries a single wiper.
 *
 * This exists because the fixture's pane 1 now carries TWO wipers: the packager then writes its fields
 * inside "wipers" and leaves nothing flat, so an injection that writes
 * `data.windshield.windshield_1_1.parkAngleDeg` would add a field the client never reads and the fault
 * would sail through the verifier untouched - a silently dead self-test rather than a caught one.
 * Going through this helper keeps every injection pointed at the block the CLIENT actually reads,
 * whichever shape the glass happens to be packed in.
 */
function wiperBlock(data, glassName, wiper = 1) {
  const glass = data.windshield[glassName];
  if (!glass) throw new Error('no ' + glassName + ' in the packed config');
  if (Array.isArray(glass.wipers)) {
    const index = glass.wipers.findIndex((one, i) => (one.wiperIndex === undefined ? i + 1 : one.wiperIndex) === wiper);
    if (index < 0) throw new Error(glassName + ' has no wiper ' + wiper + ' to inject into');
    return glass.wipers[index];
  }
  if (wiper !== 1) throw new Error(glassName + ' writes the flat single-wiper shape, so it has no wiper ' + wiper);
  return glass;
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
        apply: data => { wiperBlock(data, 'windshield_1_1').parkAngleDeg += 10; }
      },
      {
        name: 'sweep angle halved',
        expect: 'sweepDeg',
        apply: data => { wiperBlock(data, 'windshield_1_1').sweepDeg /= 2; }
      },
      {
        name: 'pivot shifted sideways',
        expect: 'pivotU',
        apply: data => { wiperBlock(data, 'windshield_1_1').pivotU += 0.05; }
      },
      {
        name: 'arm length wrong',
        expect: 'armM',
        apply: data => { wiperBlock(data, 'windshield_1_1').armM *= 0.7; }
      },
      {
        name: 'fitted block missing entirely (a sweep that clears nothing)',
        expect: 'wiper=true',
        apply: data => { data.windshield.windshield_1_1 = {}; }
      },
      {
        name: 'solid wiper present but the drawn blade left on',
        expect: 'drawBlade=false',
        apply: data => { wiperBlock(data, 'windshield_1_1').drawBlade = true; }
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
        apply: data => { const w = wiperBlock(data, 'windshield_1_1'); w.pivot2U = 0.5; w.pivot2V = 0.5; }
      },
      {
        name: 'sector radius too short to reach the blade',
        expect: 'does not reach the blade',
        apply: data => { wiperBlock(data, 'windshield_1_1').armM *= 0.6; }
      },
      {
        // The pins are the linkage's INPUTS and they are NOT the blade's ends: on windshield_1_4 the arm is
        // pinned to the blade's MIDDLE, so writing the arm pin at a blade end is exactly what the "pins are
        // the ends" fallback produces (and what this verifier itself used to do unconditionally). This is
        // the injection that proves the new cross-check against the arm mesh has teeth rather than merely
        // restating the fitted fields.
        name: 'the arm pin written at a blade END instead of the blade\'s MIDDLE',
        expect: 'the arm pin was written as',
        apply: data => {
          const w = data.windshield.windshield_1_4;
          w.pinAU = w.bladeAU;
          w.pinAV = w.bladeAV;
        }
      },
      {
        name: 'the two pins swapped (the rod pin put where the arm pin belongs)',
        expect: 'the rod pin was written as',
        apply: data => {
          const w = data.windshield.windshield_1_4;
          const u = w.pinBU, v = w.pinBV;
          w.pinBU = w.pinAU; w.pinBV = w.pinAV;
          w.pinAU = u; w.pinAV = v;
        }
      },
      {
        // ONE glass, TWO wipers (windshield_1_1 with wipersweep_1_1 and wipersweep_1_1_2). The client
        // reads a block WITHOUT "wipers" as exactly one wiper, so flattening the array here is what a
        // pack that dropped the second fit looks like: wiper 2's blade would never move, and wiper 1
        // would sweep with whatever sector the surviving flat block carries.
        name: 'two fans on one glass but the config flattened back to the single-wiper shape',
        expect: 'wiper 2 has no fitted block and would never be driven',
        apply: data => {
          const glass = data.windshield.windshield_1_1;
          const first = glass.wipers.find(w => w.wiperIndex === 1);
          delete glass.wipers;
          for (const key of Object.keys(first)) if (key !== 'wiperIndex') glass[key] = first[key];
        }
      },
      {
        // The narrower version of the same fault: the array is there, but the second wiper's entry was
        // dropped from it. Nothing about the shape looks wrong - and wiper 2's blade still never moves.
        name: 'the second wiper\'s block removed from the "wipers" array',
        expect: 'wiper 2 of windshield_1_1 has NO fitted block',
        apply: data => { data.windshield.windshield_1_1.wipers = data.windshield.windshield_1_1.wipers.filter(w => w.wiperIndex !== 2); }
      },
      {
        // The anchor's own "wiper" field is dropped, so the fan named wipersweep_1_1_2 declares itself
        // wiper 1: two fans then claim wiper 1 of the same glass, and wiper 2 has no fan at all. Only
        // the name/index agreement and the pairing check can see this.
        name: 'the second fan\'s wiper index dropped, so both fans claim wiper 1',
        expect: 'fans claiming wiper 1',
        apply: data => { delete data.anchors.find(a => a.name === 'wipersweep_1_1_2').wiper; }
      }
    ]
  },
  {
    // The client-frame checks are the only ones that can see a canvas that is CONTINUOUS, tiles the canvas
    // exactly, and still reads backwards - so the mirroring injection below is the point of this suite.
    name: 'dashboard placed in the client frame',
    config: path.join(__dirname, 'fixture', 'panelfix.json'),
    anchors: path.join(__dirname, 'fixture', 'stage-panelfix', 'assets', 'mtr', 'mmtr_anchors_panelfix.json'),
    verifier: path.join(__dirname, 'verify_panel_frame.js'),
    mutations: [
      {
        // u -> 1 - u with u0/u1 kept in order: still continuous, still tiles 0..1 exactly, still satisfies
        // the unfold relation. Only the ordering check can catch it.
        name: 'the whole canvas mirrored left-to-right (text reads backwards)',
        expect: 'reads BACKWARDS',
        apply: data => foldedHud(data).faces.forEach(f => {
          const u0 = 1 - f.u1, u1 = 1 - f.u0;
          f.u0 = u0; f.u1 = u1;
        })
      },
      {
        name: 'one facet uv rect shifted along the crease',
        expect: 'TEARS at the crease',
        apply: data => { const f = foldedHud(data).faces[1]; f.u0 += 0.02; f.u1 += 0.02; }
      },
      {
        name: 'canvas width lying about the unfolded extent',
        expect: 'not proportional to the unfolded length',
        apply: data => { foldedHud(data).canvasWidthM *= 1.05; }
      },
      {
        // Half the folded panel drawn from the front and half from the back. Note the frame itself is
        // unchanged by this (local +X = side * right is invariant when the normal flips), so only the
        // "one side per anchor" check can see it.
        name: 'one facet normal reversed (that facet is drawn from behind)',
        expect: 'different sides',
        apply: data => {
          const f = foldedHud(data).faces[1];
          f.normal = f.normal.map(n => -n);
        }
      },
      {
        name: 'canvas height dropped',
        expect: 'canvasWidthM/canvasHeightM',
        apply: data => { delete foldedHud(data).canvasHeightM; }
      }
    ]
  },
  {
    // Where the 2D fit meets the client. NOTE what this fixture can and cannot do: its windscreens are
    // AXIS-ALIGNED (normal -X, up +Y), and on a glass with no tilt the disproven extraction happens to
    // build the SAME matrix as the correct one (yaw = +-90 deg makes pitch and roll degenerate). So the
    // obvious mutation - tilting the glass - proves nothing here, and the tilt regression is injected into
    // the CLIENT SOURCE instead, which is where that formula lives. The JSON mutations below cover the
    // other half: a basis that is not the frame the packager wrote.
    name: 'glass frame in the client',
    config: path.join(__dirname, 'fixture', 'wipefix.json'),
    anchors: path.join(__dirname, 'fixture', 'stage-wipefix', 'assets', 'mtr', 'mmtr_anchors_wipefix.json'),
    verifier: path.join(__dirname, 'verify_plane_frame.js'),
    mutations: [
      {
        // up -> -up keeps the pane's tilt but reverses the sense of "up x normal", i.e. the blade fans
        // backwards. The fitted sector cannot see this: it is per-pane 2D and consistent with itself.
        name: 'the pane\'s up flipped, so right is no longer up x normal',
        expect: 'fan the wrong way round',
        apply: data => data.anchors.filter(a => a.kind === 'windshield').forEach(a => { a.up = a.up.map(x => -x); })
      },
      {
        name: 'up written at an angle to the normal (the glass has no frame)',
        expect: 'not perpendicular',
        apply: data => data.anchors.filter(a => a.kind === 'windshield').forEach(a => { a.up = [-0.5, 1, 0]; })
      },
      {
        // THE REGRESSION THAT MATTERS: the client's pitch extraction read off up.y() instead of the
        // normal. On this axis-aligned fixture that is invisible in the anchors, which is exactly why the
        // injection has to happen in the Java source.
        name: 'the client\'s pitch extraction reverted to up.y() (off by the glass tilt)',
        expect: 'disproven pitch extraction',
        applyToSource: source => source.replace('Math.asin(clamp(-normal.y()))', 'Math.asin(clamp(-up.y()))')
      },
      {
        // ...and the same for the pivot: translating by the raw plane coordinates instead of lifting the
        // pivot onto the glass first is what throws the rods into the sky.
        name: 'the pivot translated by raw plane coordinates again',
        expect: 'model-space pivot',
        applyToSource: source => source
          .replace('final Vector pivot = plane.pointAt(pivotX, pivotY);', '/* removed */')
          .replace('graphicsHolder.translate(pivot.x(), pivot.y(), pivot.z());', 'graphicsHolder.translate(pivotX, pivotY, 0);')
      },
      {
        // THE SIGN OF GRAVITY. The projection must be (right.y, up.y), because this module's space is
        // y-DOWN (the anchors are authored y-up and toModelSpace mirrors y). The mirrored pair was shipped
        // once and sent the rain up every windscreen, so it is injected back here: no anchor mutation can
        // catch this, because the projection is winding-independent by design.
        name: 'the fall direction mirrored again (down = -right.y, -up.y)',
        expect: 'mirrored fall projection',
        applyToSource: source => source.replace('final double alongUp = plane.up.y();', 'final double alongUp = -plane.up.y();')
      },
      {
        // ...and the correction that was supposed to cope with an upside-down anchor: a 180-degree rotation
        // of the down vector instead of a projection, which pins the answer to the canvas's -y whatever the
        // glass does.
        name: 'the "flip the basis when up.y < 0" fall correction is back',
        expect: 'flip the basis when up.y < 0',
        applyToSource: source => source.replace('final double sign = downY < 0 ? -1 : 1;', 'final double sign = plane.up.y() < 0 ? -1 : 1;')
      },
      {
        // The second static copy of a wiper: back into the doors-closed batch, which is the batch a vehicle
        // normally draws its body from. Parked it hides under the moving copy; the moment the wipers run,
        // the car looks like it grew another wiper.
        name: 'the mechanism part registered into the doors-closed optimised batch again',
        expect: 'STATIC COPY',
        sourcePath: PART_SOURCE,
        applyToSource: source => source.replace(
          'addObjModelPosition(objModels, new Object2ObjectOpenHashMap<>(), x, y, z, flipped, modelYOffset);',
          'addObjModelPosition(objModels, objModelsForPartConditionAndRenderStageDoorsClosed, x, y, z, flipped, modelYOffset);')
      },
      {
        // The other half of the same trap: not registering at all builds an EMPTY wrapper, because an OBJ
        // wrapper only materialises geometry from a recorded transformation.
        name: 'the mechanism part made to skip its position registration (empty wrapper)',
        expect: 'disappears',
        sourcePath: PART_SOURCE,
        applyToSource: source => source.replace(
          'addObjModelPosition(objModels, new Object2ObjectOpenHashMap<>(), x, y, z, flipped, modelYOffset);',
          '')
      }
    ]
  },
  {
    // The only suite whose injected file is NOT an anchors json: verify_doors.js reads the PACKED
    // PROPERTIES (that is where `doorZMultiplier` lives), so `anchors` below points at that file. The
    // machinery is the same - read it, break it, run the verifier, put it back.
    name: 'door leaf slide (two leaves per doorway)',
    config: path.join(__dirname, '..', 'obj-mtr-packager', 'example', 'vehicle.saf420cab_a.json'),
    anchors: path.join(__dirname, '..', '..', '..', 'assets', 'models', 'blender', 'saf420cab', '.pack_stage_saf420cab_a', 'assets', 'mtr', 'properties_saf420cab_a.json'),
    verifier: path.join(__dirname, 'verify_doors.js'),
    mutations: [
      {
        // Exactly the defect the real car was packed with (notes/279 §9): one sign for the whole side, so
        // one leaf of every pair travels INTO its own opening.
        name: 'a whole side given one slide sign (the packager default)',
        expect: 'exactly reversed',
        apply: props => {
          for (const part of props.parts) {
            if ((part.names || []).some(name => name.startsWith('door_l'))) part.doorZMultiplier = 14;
          }
        }
      },
      {
        name: 'one leaf not animated at all',
        // door_r_2 的正确符号是 +14（文件空间 −Z 侧取 +；OBJ 运行期空间 = 文件空间 z 取反，notes/354 §2）
        expect: 'must be 14',
        apply: props => {
          for (const part of props.parts) {
            if ((part.names || []).includes('door_r_2')) part.doorZMultiplier = 0;
          }
        }
      },
      {
        name: 'a leaf missing its own part (it would never be drawn)',
        expect: 'no part in the properties file',
        apply: props => {
          props.parts = props.parts.filter(part => !(part.names || []).includes('door_l_3'));
        }
      }
    ]
  }
];

function runVerifier(verifier, config) {
  const result = spawnSync(process.execPath, [verifier, '--config', config], { encoding: 'utf8' });
  return { status: result.status, output: (result.stdout || '') + (result.stderr || '') };
}

/**
 * Runs one injection and restores everything it touched.
 *
 * <p>A source-level injection edits REAL client code, so the restore is in a `finally`: a verifier that
 * throws, or a mutation that corrupts the file, must still leave the checkout exactly as it was found.
 * Anything else turns "run the self-test" into "break the build".</p>
 */
function runWithMutation(suite, mutation, pristineAnchors) {
  const sourcePath = mutation.sourcePath || CLIENT_SOURCE;
  let pristineSource = null;
  try {
    if (mutation.applyToSource) {
      pristineSource = fs.readFileSync(sourcePath, 'utf8');
      const mutated = mutation.applyToSource(pristineSource);
      if (mutated === pristineSource) {
        throw new Error('the source injection changed nothing - it no longer matches ' + path.basename(sourcePath));
      }
      fs.writeFileSync(sourcePath, mutated, 'utf8');
    }
    if (mutation.apply) {
      const data = JSON.parse(pristineAnchors);
      mutation.apply(data);
      fs.writeFileSync(suite.anchors, JSON.stringify(data), 'utf8');
    }
    return runVerifier(suite.verifier, suite.config);
  } finally {
    if (pristineSource !== null) fs.writeFileSync(sourcePath, pristineSource, 'utf8');
    fs.writeFileSync(suite.anchors, pristineAnchors, 'utf8');
  }
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
    const result = runWithMutation(suite, mutation, pristine);

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
