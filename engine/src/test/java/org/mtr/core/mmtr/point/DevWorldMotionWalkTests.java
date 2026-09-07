package org.mtr.core.mmtr.point;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionDriver;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice-4 real-yard driving: a consist is actually DRIVEN by Motion Core (MmtrMotionDriver over
 * MmtrMotionWalker) along the real via-rail into the -96 yard node (-96,-60,76). With the operator
 * branch set to 0 the consist crosses onto the straight real rail (搬0走直); set to 1 onto the
 * diverging real rail (搬1走岔); unset it halts at the node awaiting authority.
 */
public final class DevWorldMotionWalkTests {
	private static final Path DEV_MTR_ROOT = Paths.get("C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/saves/新的世界/mtr");
	private static final long NX = -96, NY = -60, NZ = 76;

	@Test
	public void motionCoreDrivesConsistThroughMinus95ByAuthority() {
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(DEV_MTR_ROOT), "dev world save not present - skipping");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, DEV_MTR_ROOT, false);
		final ObjectArrayList<MmtrSwitch> all = MmtrPointRegistry.discover(sim);
		MmtrSwitch sw = null;
		for (final MmtrSwitch s : all) { if (s.nodeX == NX && s.nodeY == NY && s.nodeZ == NZ) { sw = s; break; } }
		assertNotNull(sw, "expected a turnout at the -96 yard node");

		final Position node = new Position(NX, NY, NZ);
		final Rail via = findRailByHex(sim, sw.viaRailHex);
		assertNotNull(via, "via rail should exist in the graph");
		final Position start = otherEnd(sim, node, via);
		assertNotNull(start, "via rail must have a far endpoint to start from");
		assertTrue(!start.equals(node), "start endpoint must differ from the -96 node");

		// Unset fork: the driven consist halts at the node (authority required).
		final MmtrMotionDriver unset = MmtrMotionDriver.start(sim, via, start, new BranchStore(), null);
		final boolean unsetRest = unset.driveToRest(1000, 0.004, 200);
		assertEquals(true, unsetRest);
		assertEquals(true, unset.haltedAtAuthority(), "unset -96 fork must stop the driven consist");
		assertEquals(via.getHexId(), unset.walker.railHex(), "consist stopped on the via rail at the -96 node");
		assertEquals(via.railMath.getLength(), unset.walker.offsetM(), 1e-3, "stopped at the far end of the via rail");

		// Set branch 0: crosses onto the straight real rail.
		final BranchStore b0 = new BranchStore();
		b0.set(NX, NY, NZ, via.getHexId(), 0);
		final MmtrMotionDriver d0 = MmtrMotionDriver.start(sim, via, start, b0, sw.branch0Hex);
		final boolean rest0 = d0.driveToRest(1000, 0.004, 200);
		assertEquals(true, rest0);
		assertEquals(true, d0.atTarget());
		assertEquals(sw.branch0Hex, d0.walker.railHex(), "搬0走直 - consist must cross onto branch0 (straight) real rail");

		// Set branch 1: crosses onto the diverging real rail.
		final BranchStore b1 = new BranchStore();
		b1.set(NX, NY, NZ, via.getHexId(), 1);
		final MmtrMotionDriver d1 = MmtrMotionDriver.start(sim, via, start, b1, sw.branch1Hex);
		final boolean rest1 = d1.driveToRest(1000, 0.004, 200);
		assertEquals(true, rest1);
		assertEquals(true, d1.atTarget());
		assertEquals(sw.branch1Hex, d1.walker.railHex(), "搬1走岔 - consist must cross onto branch1 (diverging) real rail");
	}

	@Test
	public void drivenConsistIsObservableViaMotionSnapshot() {
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(DEV_MTR_ROOT), "dev world save not present - skipping");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, DEV_MTR_ROOT, false);
		final ObjectArrayList<MmtrSwitch> all = MmtrPointRegistry.discover(sim);
		MmtrSwitch sw = null;
		for (final MmtrSwitch s : all) { if (s.nodeX == NX && s.nodeY == NY && s.nodeZ == NZ) { sw = s; break; } }
		org.junit.jupiter.api.Assumptions.assumeTrue(sw != null, "no turnout at -96");
		final Position node = new Position(NX, NY, NZ);
		final Rail via = findRailByHex(sim, sw.viaRailHex);
		final Position startPos = otherEnd(sim, node, via);
		org.junit.jupiter.api.Assumptions.assumeTrue(startPos != null, "via rail has far end");
		final BranchStore b1 = new BranchStore();
		b1.set(NX, NY, NZ, via.getHexId(), 1);
		final MmtrMotionDriver d = MmtrMotionDriver.start(sim, via, startPos, b1, sw.branch1Hex);
		d.driveToRest(1000, 0.004, 200);
		assertTrue(d.atTarget(), "consist should have driven onto branch1");

		// The driven consist is observable in the decoupled motion representation, no MTR Vehicle path.
		final org.mtr.core.mmtr.MmtrMotionSnapshot snap = org.mtr.core.mmtr.MmtrMotionSnapshot.ofWalker(d.walker);
		assertEquals(NX, Math.round(snap.segStartX), "snapshot segment starts at the -96 node where branch1 was boarded");
		assertTrue(snap.segEndX != snap.segStartX || snap.segEndZ != snap.segStartZ, "snapshot must report a concrete rail segment");
		assertEquals(0.0, snap.segmentOffsetM, 1e-6, "just boarded branch1 -> offset 0");
		assertEquals(NX, Math.round(snap.headX), "head position at offset 0 equals the -96 boarding node");
	}

	private static Rail findRailByHex(Simulator sim, String hex) {
		final Rail[] found = {null};
		sim.positionsToRail.forEach((pos, neigh) -> neigh.forEach((q, rail) -> { if (found[0] == null && rail.getHexId().equals(hex)) { found[0] = rail; } }));
		return found[0];
	}

	private static Position otherEnd(Simulator sim, Position at, Rail rail) {
		final Object2ObjectOpenHashMap<Position, Rail> neighbors = sim.positionsToRail.get(at);
		if (neighbors == null) { return null; }
		for (final Object2ObjectOpenHashMap.Entry<Position, Rail> e : neighbors.object2ObjectEntrySet()) { if (e.getValue() == rail) { return e.getKey(); } }
		return null;
	}

	@Test
	public void motionCoreEmitsRunnableLegsForVehicle() {
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(DEV_MTR_ROOT), "dev world save not present - skipping");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, DEV_MTR_ROOT, false);
		final ObjectArrayList<MmtrSwitch> all = MmtrPointRegistry.discover(sim);
		MmtrSwitch sw = null;
		for (final MmtrSwitch s : all) { if (s.nodeX == NX && s.nodeY == NY && s.nodeZ == NZ) { sw = s; break; } }
		org.junit.jupiter.api.Assumptions.assumeTrue(sw != null, "no turnout at -96");
		final Position node = new Position(NX, NY, NZ);
		final Rail via = findRailByHex(sim, sw.viaRailHex);
		final Position startPos = otherEnd(sim, node, via);
		org.junit.jupiter.api.Assumptions.assumeTrue(startPos != null, "via rail has far end");
		final BranchStore b1 = new BranchStore();
		b1.set(NX, NY, NZ, via.getHexId(), 1);
		final MmtrMotionDriver d = MmtrMotionDriver.start(sim, via, startPos, b1, sw.branch1Hex);
		d.driveToRest(1000, 0.004, 200);
		assertTrue(d.atTarget(), "should have boarded branch1");

		// T1: Motion Core emits an ordered, runnable leg list (Vehicle path carrier).
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<org.mtr.core.data.PathData> legs = d.walker.buildLegs();
		assertTrue(legs.size() >= 2, "legs must cover via-rail + branch1, got " + legs.size());
		final org.mtr.core.data.PathData last = legs.get(legs.size() - 1);
		final double expected = via.railMath.getLength() + findRailByHex(sim, sw.branch1Hex).railMath.getLength();
		assertEquals(expected, last.getEndDistance(), 1e-3, "final leg end distance spans via + elected branch1 rails");
		assertTrue(last.getEndDistance() > legs.get(0).getEndDistance(), "legs must be cumulative/monotonic");
	}
}