#!/usr/bin/env node
/*
 * verify_doors.js - do this model's door leaves actually OPEN?
 *
 * The failure this exists for is not an error message anywhere: the packager's default slide direction is
 * decided by WHICH SIDE OF THE CAR a leaf is on (`door_l* -> +slide`, `door_r* -> -slide`), which is right
 * for the one-leaf-per-doorway models MTR ships. This car has TWO leaves per doorway (a 両開き commuter
 * door), so a single sign for the whole side sends one leaf of each pair INTO its own opening and the pair
 * ends up stacked on half the doorway. In game that reads as "the door opens wrong / only half opens".
 *
 * What it checks, per car:
 *   D1  every `door_l*`/`door_r*` group in the PACKED obj is its own part in the properties file;
 *   D2  leaves are grouped into doorways by proximity, and every doorway holds 2 leaves (1 = a
 *       single-leaf door, which the default sign handles; 0 or 3+ is a grouping problem, reported);
 *   D3  each leaf's `doorZMultiplier` sends it AWAY from its doorway's centre. The sign convention is the
 *       packed model's own Z: MTR adds the offset to the part's packed Z (`ModelPropertiesPart:399`) and
 *       this workspace's models put the car's B end at +Z (the anchors prove it - see the cab's `hud_1`
 *       at z=-9.31 and `hud_2` at +9.31), so "away from the centre" is -1 on the -Z side, +1 on the +Z
 *       side, times the slide distance in 1/16 blocks.
 *
 * Usage: node mmtr/tools/anchor-check/verify_doors.js --config <vehicle config.json>
 * Exit: 0 = every leaf opens away from its doorway, 1 = at least one does not, 2 = could not run.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const L = require('./lib.js');

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

/** Per-group z span, from the faces' own vertices (a packed OBJ writes every `v` before any `g`). */
function groupSpans(obj) {
  const spans = new Map();
  for (const group of obj.groups) {
    const span = { min: Infinity, max: -Infinity };
    for (const face of group.faces) {
      for (const index of face) {
        const z = obj.vpos[index][2];
        if (z < span.min) span.min = z;
        if (z > span.max) span.max = z;
      }
    }
    if (span.min <= span.max) spans.set(group.name, span);
  }
  return spans;
}

function main() {
  const args = process.argv.slice(2);
  const configPath = args[args.indexOf('--config') + 1];
  if (!configPath || !fs.existsSync(configPath)) {
    console.error('usage: node verify_doors.js --config <vehicle config.json>');
    process.exit(2);
  }
  const config = loadConfig(configPath);
  const stage = config.stagingDir || path.join(path.dirname(config.sourceObj), '.pack_stage_' + config.id);
  const propsFile = path.join(stage, 'assets', 'mtr', 'properties_' + config.id + '.json');
  const objFile = path.join(stage, 'assets', 'mtr', config.id, path.basename(config.sourceObj));
  if (!fs.existsSync(propsFile)) {
    console.error('properties file not found: ' + propsFile + '\n(run the packager on this config first)');
    process.exit(2);
  }
  if (!fs.existsSync(objFile)) {
    console.error('packed obj not found: ' + objFile);
    process.exit(2);
  }

  const source = L.parseObj(objFile);
  // ★★ 坐标口径（2026-10-01 实机修正）：门偏移的**运行期空间 = OBJ 文件空间把 z 取反**。
  //
  //   证据三条：
  //     ① **实机**：按"文件空间里远离门洞中心"写符号（v30–v32）之后，用户看到的就是「开门还是反的」；
  //        而在此之前（v9–v28：文件空间 −Z 侧取 −）没人报过门向反 —— 用户当时只报了"门玻璃不跟着动"。
  //     ② MTR 自己对 OBJ 模型的 `doorZMultiplier` **取反**：`LegacyVehicleResource.java:398`
  //        `partsObject.addProperty("doorZMultiplier", (isObj ? -1 : 1) * doorZMultiplier);`
  //     ③ 同一处 OBJ 部件的包围盒也是**三个轴全取反**：`ModelPropertiesPart.java:191`
  //        `new Box(-objModel.getMinX(), -objModel.getMinY(), -objModel.getMinZ(), …)`。
  //   ⇒ 文件空间里位于 **+Z 侧**的叶取 **−**、**−Z 侧**的取 **+**（即 D3 的 expected 与"看起来该给的那个"相反）。
  //   我上一版错在"offset 与几何落在同一个空间"这个前提上：offset 走的是 OBJ 那套被取反的 z。
  //   也绝不要再"把坐标转回模型空间"再判 —— 那是把两套空间混起来，会把真缺陷判成 PASS。
  const spans = groupSpans(source);
  const props = JSON.parse(fs.readFileSync(propsFile, 'utf8'));
  const multiplierOf = new Map();
  for (const part of (props.parts || [])) {
    for (const name of (part.names || [])) {
      multiplierOf.set(name, part.doorZMultiplier === undefined ? 0 : part.doorZMultiplier);
      multiplierOf.set(name + '#type', part.type || 'NORMAL');
    }
  }

  const failures = [];
  const notes = [];
  const slide = config.doorSlidePx === undefined ? 14 : config.doorSlidePx;
  let doorways = 0;
  let leaves = 0;

  /*
   * D4: THE SLABS THE CREW ACTUALLY BOARD THROUGH.
   *
   * The packager generates one DOORWAY box per door group (auto for `door_l*`/`door_r*`, plus whatever
   * `extraDoorways` names) and one FLOOR slab. A leaf with no doorway box is a door that opens onto a
   * solid wall: the panel moves, the opening does nothing, and nothing anywhere says so. So each door
   * group must have a `doorway_<group>` whose box CONTAINS that group's own z span, and a car with
   * doorways must have a floor.
   */
  const slugs = [...spans.keys()].filter(name => name.startsWith('doorway_'));
  if (!slugs.length) {
    failures.push('D4 no doorway_* group in the packed OBJ - no doorway boxes were generated, so nobody can board');
  }
  if (!spans.has('floor')) {
    failures.push('D4 no floor slab in the packed OBJ - riders would be clamped onto MTR\'s synthetic slab instead');
  }

  for (const side of ['door_l', 'door_r']) {
    const side_leaves = [...spans.entries()]
      // ★ 2026-09-30：`<门叶>_glass` 是门的**玻璃伴随组**（跟门叶同步滑、走 INTERIOR_TRANSLUCENT），
      //   不是第三片门叶 —— 排除掉，D1~D4 仍然只审真正的门叶。
      .filter(([name]) => name.startsWith(side + '_') && !name.endsWith('_glass'))
      .map(([name, span]) => ({ name, span, mid: (span.min + span.max) / 2 }))
      .sort((a, b) => a.mid - b.mid);

    if (!side_leaves.length) {
      notes.push(side + ': no leaves (this car does not have that side)');
      continue;
    }

    // D1: its own part, so it can have its own slide.
    for (const leaf of side_leaves) {
      const partType = multiplierOf.get(leaf.name + '#type');
      if (partType === undefined) {
        failures.push('D1 ' + leaf.name + ' is in the packed OBJ but has no part in the properties file - it would never be drawn');
      } else if (partType === 'DOORWAY' || partType === 'FLOOR') {
        failures.push('D1 ' + leaf.name + ' was packed as ' + partType + ' instead of a door part');
      }
    }

    // D2: doorway grouping. The gap between two leaves of one opening is ~0.1 m; the next opening is a
    // whole pitch away (6 m on this stock), so 0.15 m separates them unambiguously.
    const groups = [];
    for (const leaf of side_leaves) {
      const last = groups[groups.length - 1];
      if (last && leaf.span.min - last[last.length - 1].span.max < 0.15) {
        last.push(leaf);
      } else {
        groups.push([leaf]);
      }
    }

    for (const group of groups) {
      doorways++;
      const centre = (group[0].span.min + group[group.length - 1].span.max) / 2;
      // D3: away from the centre. D4: this leaf has its own doorway box, and that box covers it.
      // (One doorway box per LEAF, not per opening - that is what the packager generates, and it is what
      // MTR's boarding test uses.)
      for (const leaf of group) {
        leaves++;
        // D4: its own doorway box, covering it.
        const slab = spans.get('doorway_' + leaf.name);
        if (!slab) {
          failures.push('D4 ' + leaf.name + ' has no doorway_' + leaf.name + ' box - the leaf would swing but ' +
            'there would be no opening to board through (check the group name, and `extraDoorways` for a cab door)');
        } else if (slab.min > leaf.span.min + 0.02 || slab.max < leaf.span.max - 0.02) {
          failures.push('D4 doorway_' + leaf.name + ' spans z ' + slab.min.toFixed(3) + '..' + slab.max.toFixed(3) +
            ' but its leaf spans ' + leaf.span.min.toFixed(3) + '..' + leaf.span.max.toFixed(3) + ' - the box does not cover the opening');
        }
        // D3: OBJ 模型的偏移走"文件空间 z 取反"那一套 ⇒ 文件空间 +Z 侧的叶取 −、−Z 侧取 +（见文件头的三条证据）。
        const expected = leaf.mid > centre ? -slide : slide;
        const actual = multiplierOf.get(leaf.name);
        const ok = actual === expected;
        if (!ok) {
          failures.push('D3 ' + leaf.name + ' (z ' + leaf.span.min.toFixed(3) + '..' + leaf.span.max.toFixed(3) +
            ', doorway centre ' + centre.toFixed(3) + ') has doorZMultiplier ' + actual +
            ' but must be ' + expected + ' to open AWAY from its doorway centre (OBJ space = file z negated)' +
            (actual === -expected ? ' (it is exactly reversed: it would close across the opening)' : ''));
        }
      }
      if (group.length > 2) {
        notes.push(side + ' doorway at z=' + centre.toFixed(3) + ' grouped ' + group.length +
          ' leaves - check the 0.15 m split (a pocket leaf and an opening leaf can be close together)');
      }
      notes.push(side + ' doorway z=' + centre.toFixed(3) + ': ' + group.map(l => l.name + '=' +
        (multiplierOf.get(l.name))).join(', '));
    }
  }

  console.log('config   : ' + configPath);
  console.log('packed   : ' + path.basename(objFile));
  console.log('');
  for (const note of notes) {
    console.log('  ' + note);
  }
  console.log('');
  for (const failure of failures) {
    console.log('FAIL ' + failure);
  }
  if (failures.length === 0) {
    console.log('PASS: ' + leaves + ' leaf/leaves in ' + doorways + ' doorway(s) - every one opens away from its doorway centre');
    process.exit(0);
  }
  console.log('');
  console.log(failures.length + ' failure(s)');
  process.exit(1);
}

main();
