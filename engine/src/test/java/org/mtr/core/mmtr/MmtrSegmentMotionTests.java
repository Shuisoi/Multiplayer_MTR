package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrNodeRouter;
import org.mtr.core.mmtr.segment.MmtrNodeRouter.Continuation;
import org.mtr.core.mmtr.segment.MmtrSegmentStep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic tests for the decoupled (segment + offset) motion primitives that slice A/B/C
 * (M2-Core 解耦运动层 + 道岔权威) are built on. The node-decision tests prove the core of the
 * "-95 搬 A 走 A、搬 B 走 B" turnaround behaviour without needing a real yard: flip the operator
 * branch (or a task target) and the elected continuation rail changes accordingly.
 */
public final class MmtrSegmentMotionTests {

	private static final String STRAIGHT = "rail-straight";
	private static final String DIVERGE = "rail-diverge";

	// --- MmtrNodeRouter: authority is required at a fork (never auto) --------------------------

	@Test
	public void forkWithoutAuthorityAndTaskRefusesToDecide() {
		// An unset fork with two continuations and no task must NOT auto-pick a branch.
		assertNull(MmtrNodeRouter.elect(new Continuation(STRAIGHT, DIVERGE), null, null), "must wait for operator/task");
	}

	@Test
	public void singleForwardIsNotAnAuthorityQuestion() {
		assertEquals(STRAIGHT, MmtrNodeRouter.elect(Continuation.single(STRAIGHT), null, null), "only one way on, just go");
		assertEquals(STRAIGHT, MmtrNodeRouter.elect(new Continuation(STRAIGHT, null), 1, null), "degenerate branch1 falls back to straight");
	}

	// --- The flip: 搬 A 走 A (branch 0 -> straight), 搬 B 走 B (branch 1 -> diverging) ----------

	@Test
	public void operatorBranch0ElectsStraight() {
		assertEquals(STRAIGHT, MmtrNodeRouter.elect(new Continuation(STRAIGHT, DIVERGE), 0, null));
	}

	@Test
	public void operatorBranch1ElectsDiverge() {
		assertEquals(DIVERGE, MmtrNodeRouter.elect(new Continuation(STRAIGHT, DIVERGE), 1, null));
	}

	@Test
	public void flippingOperatorBranchReversesElectedRail() {
		final Continuation fork = new Continuation(STRAIGHT, DIVERGE);
		assertEquals(STRAIGHT, MmtrNodeRouter.elect(fork, 0, null));
		assertEquals(DIVERGE, MmtrNodeRouter.elect(fork, 1, null));
	}

	// --- Persisted BranchStore (operator-set, distinct from default-0/unset) --------------------

	@Test
	public void persistedOperatorBranchIsHonoured() {
		final BranchStore store = new BranchStore();
		store.set(0, 64, 0, "via", 1);
		assertEquals(DIVERGE, MmtrNodeRouter.electFromStore(new Continuation(STRAIGHT, DIVERGE), store, 0, 64, 0, "via", null));
		store.set(0, 64, 0, "via", 0);
		assertEquals(STRAIGHT, MmtrNodeRouter.electFromStore(new Continuation(STRAIGHT, DIVERGE), store, 0, 64, 0, "via", null));
	}

	@Test
	public void unsetStoreForkRefusesToDecide() {
		final BranchStore store = new BranchStore();
		assertNull(MmtrNodeRouter.electFromStore(new Continuation(STRAIGHT, DIVERGE), store, 0, 64, 0, "via", null), "unset branch must not auto-pick");
	}

	// --- A live task directive overrides a stale operator branch --------------------------------

	@Test
	public void taskTargetOverridesOperatorBranch() {
		final Continuation fork = new Continuation(STRAIGHT, DIVERGE);
		// Operator still points to branch0 (straight), but the task sends the train to the diverging rail.
		assertEquals(DIVERGE, MmtrNodeRouter.elect(fork, 0, DIVERGE));
		assertEquals(STRAIGHT, MmtrNodeRouter.elect(fork, 1, STRAIGHT));
	}

	@Test
	public void taskTargetNotMatchingEitherBranchIgnoresTask() {
		// Task names an unreachable rail -> not honoured; operator branch (0) governs.
		assertEquals(STRAIGHT, MmtrNodeRouter.elect(new Continuation(STRAIGHT, DIVERGE), 0, "rail-nowhere"));
	}

	// --- MmtrSegmentStep: (segment, offset) running position + carry-over across a node --------

	@Test
	public void advanceCarriesRemainderOntoElectedNextSegment() {
		MmtrSegmentStep a = MmtrSegmentStep.atStart("segA", 10, false);
		// Move 8m along A: still mid-segment, 2m left, not yet at the node.
		a = a.advance(8);
		assertFalse(a.atEnd());
		assertEquals(2, a.remainingM(), 1e-9);

		// Move another 4m: overshoots the node by 2m.
		a = a.advance(4);
		assertTrue(a.atEnd());
		assertEquals(2, a.overshootM(), 1e-9);

		// Elect the diverging continuation (operator flips to branch 1) and carry the remainder on.
		final MmtrSegmentStep b = MmtrSegmentStep.atStart(DIVERGE, 20, false).advance(a.overshootM());
		assertEquals(2, b.offsetM, 1e-9);
		assertNotNull(MmtrNodeRouter.elect(new Continuation(STRAIGHT, DIVERGE), 1, null));
	}

	@Test
	public void reversedSegmentTracksDirectionIndependently() {
		final MmtrSegmentStep r = MmtrSegmentStep.atStart("segX", 30, true);
		assertTrue(r.reversed);
		assertEquals(30, r.remainingM(), 1e-9);
	}
}
