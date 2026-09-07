package org.mtr.core.mmtr.point;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrNodeRouter;
import org.mtr.core.mmtr.segment.MmtrNodeRouter.Continuation;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice-C real-yard flip verification on the actual Motion Core: load the dev world, find the
 * turnout(s) at the -96 yard node (-96,-60,76) and prove 搬0走直 / 搬1走岔 — flipping the operator
 * branch (BranchStore 0/1) changes which real rail the Motion Core elects at that node, and an
 * unset fork refuses to auto-decide. (Real -95/-96 yard, not a synthetic fork.)
 */
public final class DevWorldTurnoutFlipTests {
	private static final Path DEV_MTR_ROOT = Paths.get("C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/saves/新的世界/mtr");

	@Test
	public void flipAtMinus95NodeChangesElectedRealRail() {
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(DEV_MTR_ROOT), "dev world save not present - skipping");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, DEV_MTR_ROOT, false);
		final ObjectArrayList<MmtrSwitch> all = MmtrPointRegistry.discover(sim);
		final ObjectArrayList<MmtrSwitch> at95 = new ObjectArrayList<>();
		for (final MmtrSwitch s : all) {
			if (s.nodeX == -96 && s.nodeY == -60 && s.nodeZ == 76) {
				at95.add(s);
			}
		}
		System.out.println("[FLIP] total switches=" + all.size() + " at(-96,-60,76)=" + at95.size());
		assertTrue(!at95.isEmpty(), "expected turnouts at the -96/-95 yard node (-96,-60,76)");

		for (final MmtrSwitch s : at95) {
			final Continuation fork = new Continuation(s.branch0Hex, s.branch1Hex);
			assertTrue(!s.branch0Hex.equals(s.branch1Hex), "branch0 and branch1 must be distinct real rails");

			// No authority set and no task: the core must refuse to auto-pick a branch at this real fork.
			assertNull(MmtrNodeRouter.elect(fork, null, null), "unset real fork must not auto-decide: " + s.key());

			// Operator sets 0 -> elect the straight real rail (搬0走直).
			assertEquals(s.branch0Hex, MmtrNodeRouter.elect(fork, 0, null), "branch0 rail expected: " + s.key());
			// Operator sets 1 -> elect the diverging real rail (搬1走岔).
			assertEquals(s.branch1Hex, MmtrNodeRouter.elect(fork, 1, null), "branch1 rail expected: " + s.key());

			// Through the persisted BranchStore overload too.
			final BranchStore store = new BranchStore();
			store.set(s.nodeX, s.nodeY, s.nodeZ, s.viaRailHex, 1);
			assertEquals(s.branch1Hex, MmtrNodeRouter.electFromStore(fork, store, s.nodeX, s.nodeY, s.nodeZ, s.viaRailHex, null));
			store.set(s.nodeX, s.nodeY, s.nodeZ, s.viaRailHex, 0);
			assertEquals(s.branch0Hex, MmtrNodeRouter.electFromStore(fork, store, s.nodeX, s.nodeY, s.nodeZ, s.viaRailHex, null));
		}
	}
}