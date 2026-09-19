#!/usr/bin/env node
/*
 * probe_pin_motion.js - WHY did the packager's stroke solve miss the fixture's far edge?
 *
 * Round 3 added a "arm pinned to the blade's MIDDLE" pane and the solve landed 0.45 m off. Everything the
 * two sides FEED the maths was checked by hand and agreed (pins, pin span, follower length, the assembly
 * mode being read off park), so the remaining suspect is the arithmetic itself.
 *
 * This probe therefore runs BOTH implementations on ONE set of inputs:
 *   - the fixture generator's:  far = carried(a0), carried(b0)
 *   - the packager's:           far = bladeAt(theta)
 * and prints the four endpoints side by side. If they agree here, the defect is in what each side is given
 * (so the next thing to print is the inputs); if they differ, it is in the formula, and the diff shows
 * exactly which term.
 *
 * Run: node mmtr/tools/anchor-check/probe_pin_motion.js
 */

'use strict';

// The pane-4 geometry, exactly as the generator declares it.
const DEG = Math.PI / 180;
const P1 = [0.0, 0.0];
const P2 = [0.0, 0.30];
const M0 = [0.5, 0.30];   // the arm's pin, at the blade's MIDDLE
const BR0 = [0.5, 0.60];  // the rod's pin, at the blade's end
const A0 = [0.5, 0.00];   // the blade's ends
const B0 = [0.5, 0.60];
const THETA = 40;

const rotate = (pivot, point, cos, sin) => {
  const dx = point[0] - pivot[0], dy = point[1] - pivot[1];
  return [pivot[0] + dx * cos - dy * sin, pivot[1] + dx * sin + dy * cos];
};
const hypot = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1]);

/** The two circle intersections, ordered as BOTH implementations build them. */
function solutions(crankPin, pivot, radiusAboutCrank, radiusAboutPivot) {
  const dx = pivot[0] - crankPin[0], dy = pivot[1] - crankPin[1];
  const distance = Math.hypot(dx, dy);
  if (distance < 1.0E-9 || distance > radiusAboutCrank + radiusAboutPivot || distance < Math.abs(radiusAboutCrank - radiusAboutPivot)) return null;
  const along = (distance * distance + radiusAboutCrank * radiusAboutCrank - radiusAboutPivot * radiusAboutPivot) / (2 * distance);
  const height = Math.sqrt(Math.max(0, radiusAboutCrank * radiusAboutCrank - along * along));
  const ux = dx / distance, uy = dy / distance;
  const base = [crankPin[0] + ux * along, crankPin[1] + uy * along];
  return [[base[0] - uy * height, base[1] + ux * height], [base[0] + uy * height, base[1] - ux * height]];
}

const spanM = hypot(BR0, M0);
const followerM = hypot(BR0, P2);
const parkSolutions = solutions(M0, P2, spanM, followerM);
// The branch is chosen by HANDEDNESS about the pivot (the sign of the parked triangle's area), exactly as
// the packager, the fixture and the client now do - not by "which candidate is nearer the parked pin",
// which is a proximity test that flips on a sub-micron input difference near a toggle position.
const handedness = (a, b, c) => (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
const parkSign = handedness(P2, M0, BR0);
const parkIndexPlus = parkSign === 0 ? 0 : (handedness(P2, M0, parkSolutions[0]) > 0) === (parkSign > 0) ? 0 : 1;
const parkIndexMinus = parkIndexPlus === 0 ? 1 : 0;

/** The rigid motion that puts the park pin pair onto the current one, applied to the blade's ends. */
function carriedBy(m, br, a0, b0) {
  const turn = Math.atan2(br[1] - m[1], br[0] - m[0]) - Math.atan2(BR0[1] - M0[1], BR0[0] - M0[0]);
  const c = Math.cos(turn), s = Math.sin(turn);
  const apply = point => {
    const dx = point[0] - M0[0], dy = point[1] - M0[1];
    return [m[0] + dx * c - dy * s, m[1] + dx * s + dy * c];
  };
  return { ends: [apply(a0), apply(b0)], turn: turn / DEG };
}

// ---- the GENERATOR's form: solve the follower once, with the mode taken from PARK ------------------
const generator = (() => {
  const radians = THETA * DEG, cos = Math.cos(radians), sin = Math.sin(radians);
  const m = rotate(P1, M0, cos, sin);
  const now = solutions(m, P2, spanM, followerM);
  const br = now === null ? null : now[parkIndexPlus];
  return br === null ? { error: 'not assemblable' } : carriedBy(m, br, A0, B0);
})();

// ---- the PACKAGER's form: same clock, but the pair is fetched inside bladeAt ------------------------
const packager = (() => {
  const radians = THETA * DEG, cos = Math.cos(radians), sin = Math.sin(radians);
  const pinPairAt = phi => {
    const r = phi * DEG, c = Math.cos(r), s = Math.sin(r);
    const m = rotate(P1, M0, c, s);
    const now = solutions(m, P2, spanM, followerM);
    return now === null ? null : [m, now[parkIndexPlus]];
  };
  const pair = pinPairAt(THETA);
  if (pair === null) return { error: 'not assemblable' };
  return carriedBy(pair[0], pair[1], A0, B0);
})();

const show = p => p.error ? p.error : p.ends.map(q => '(' + q.map(x => x.toFixed(6)) + ')').join(' ') + '   turn ' + p.turn.toFixed(3) + ' deg';
console.log('inputs: P1 ' + P1 + '  P2 ' + P2 + '  M0 ' + M0 + '  BR0 ' + BR0 + '  A0 ' + A0 + '  B0 ' + B0);
console.log('        pin span ' + spanM.toFixed(6) + ' m   follower ' + followerM.toFixed(6) + ' m   park branch index ' + parkIndexPlus + ' (the OTHER is ' + parkIndexMinus + ')');
console.log('');
console.log('generator: ' + show(generator));
console.log('packager : ' + show(packager));
console.log('');
if (generator.error || packager.error) {
  console.log('RESULT: one side cannot assemble - the geometry itself is the problem, not the arithmetic.');
} else {
  const worst = Math.max(...generator.ends.map((p, i) => hypot(p, packager.ends[i])));
  console.log('RESULT: the two forms differ by ' + worst.toFixed(6) + ' m -> ' +
    (worst < 1.0E-9 ? 'THE ARITHMETIC AGREES; the defect is in the INPUTS each side receives'
      : 'THE ARITHMETIC DIFFERS; fix the formula, not the inputs'));
}
console.log('');
console.log('For reference, the other assembly branch gives:');
const alt = (() => {
  const radians = THETA * DEG, cos = Math.cos(radians), sin = Math.sin(radians);
  const m = rotate(P1, M0, cos, sin);
  const now = solutions(m, P2, spanM, followerM);
  return now === null ? { error: 'not assemblable' } : carriedBy(m, now[parkIndexMinus], A0, B0);
})();
console.log('  ' + show(alt));
console.log('A solve that picked THIS branch would land exactly this far off: ' +
  (alt.error || !packager.error ? 'n/a' : Math.max(...alt.ends.map((p, i) => hypot(p, packager.ends[i]))).toFixed(6) + ' m'));
