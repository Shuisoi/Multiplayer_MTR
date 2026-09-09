package org.mtr.core.mmtr.point;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.MmtrRunPlanner;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 (multi-level turnout control, acceptance 7-9 primitives): MmtrPointAuthority - one grant per
 * (node, via) point, competing auto requests queue FIFO, an operator lock parks the point for
 * manual use (auto requests queue until unlocked), grants die with their window so a stale holder
 * cannot wedge the network, and passing the point consumes the holder's hold and advances the
 * queue. The manual-operator-outranks-auto ordering itself lives in the walker elect (covered by
 * the walker-level P3 tests) - this class only owns the state machine.
 */
public final class MmtrPointAuthorityTests {

	private static final String VIA = "FFFFFFFFFFFFFFEC-0000000000000000";

	private static MmtrPointAuthority authority(AtomicLong clock) {
		return new MmtrPointAuthority(clock::get);
	}

	@Test
	public void lockParksPointForManualUseAndUnlockGrantsFifoHead() {
		final AtomicLong clock = new AtomicLong(1000);
		final MmtrPointAuthority a = authority(clock);
		a.lock(0, 0, 0, VIA);
		assertTrue(a.isLocked(0, 0, 0, VIA), "point parked by the operator");
		assertEquals(MmtrPointAuthority.Result.QUEUED, a.request(0, 0, 0, VIA, "task1", 0, 2000), "auto request queues behind the operator lock");
		assertEquals(MmtrPointAuthority.Result.QUEUED, a.request(0, 0, 0, VIA, "task2", 1, 2000), "second auto request queues too");
		assertNull(a.holder(0, 0, 0, VIA), "no grant while the point is operator-locked");
		a.unlock(0, 0, 0, VIA);
		assertFalse(a.isLocked(0, 0, 0, VIA), "unlock releases the park");
		assertEquals("task1", a.holder(0, 0, 0, VIA), "the longest-waiting auto request takes the point after unlock");
		assertEquals(0, a.grantedLeg(0, 0, 0, VIA), "granted the leg the queued head asked for");
		a.passed(0, 0, 0, VIA, "task1");
		assertEquals("task2", a.holder(0, 0, 0, VIA), "queue advances FIFO after the holder crossed");
	}

	@Test
	public void reRequestsRefreshTheWindowInsteadOfDuplicating() {
		final AtomicLong clock = new AtomicLong(1000);
		final MmtrPointAuthority a = authority(clock);
		a.lock(0, 0, 0, VIA);
		a.request(0, 0, 0, VIA, "task1", 0, 2000);
		a.request(0, 0, 0, VIA, "task1", 0, 9000); // every tick refresh while locked
		a.request(0, 0, 0, VIA, "task2", 1, 9000);
		clock.set(5000); // past task1's ORIGINAL 2000 window - the refresh must have kept it alive
		a.unlock(0, 0, 0, VIA);
		assertEquals("task1", a.holder(0, 0, 0, VIA), "refresh kept the first request alive past its original window");
		a.passed(0, 0, 0, VIA, "task1");
		assertEquals("task2", a.holder(0, 0, 0, VIA), "task2 still queued exactly once");
		a.passed(0, 0, 0, VIA, "task2");
		assertNull(a.holder(0, 0, 0, VIA), "queue drained after both crossed");
	}

	@Test
	public void expiredHolderCannotWedgeTheNetwork() {
		final AtomicLong clock = new AtomicLong(1000);
		final MmtrPointAuthority a = authority(clock);
		assertEquals(MmtrPointAuthority.Result.GRANTED, a.request(0, 0, 0, VIA, "stale", 0, 1500));
		clock.set(1600); // stale holder's window passed without crossing
		assertEquals(MmtrPointAuthority.Result.GRANTED, a.request(0, 0, 0, VIA, "fresh", 1, 3000), "expired holder frees the point for the next request");
		assertEquals("fresh", a.holder(0, 0, 0, VIA));
		clock.set(2000);
		assertEquals("fresh", a.holder(0, 0, 0, VIA), "fresh window still valid");
	}

	@Test
	public void expiredQueuedEntriesAreSkippedOnPromotion() {
		final AtomicLong clock = new AtomicLong(1000);
		final MmtrPointAuthority a = authority(clock);
		assertEquals(MmtrPointAuthority.Result.GRANTED, a.request(0, 0, 0, VIA, "first", 0, 5000));
		a.request(0, 0, 0, VIA, "sleepy", 0, 1200); // queues with a tiny window
		a.request(0, 0, 0, VIA, "third", 1, 9000);
		clock.set(6000); // "first" expired; "sleepy" expired while queued
		assertEquals("third", a.holder(0, 0, 0, VIA), "expired queue entries are skipped, the live one takes the point");
	}

	@Test
	public void passedReleasesOnlyTheOwnerAndReleaseAllDropsEverything() {
		final AtomicLong clock = new AtomicLong(1000);
		final MmtrPointAuthority a = authority(clock);
		assertEquals(MmtrPointAuthority.Result.GRANTED, a.request(0, 0, 0, VIA, "v1", 0, 5000));
		a.passed(0, 0, 0, VIA, "other");
		assertEquals("v1", a.holder(0, 0, 0, VIA), "a stranger's release is a no-op");
		a.passed(0, 0, 0, VIA, "v1");
		assertNull(a.holder(0, 0, 0, VIA), "the holder's crossing consumes the hold");

		a.request(0, 0, 0, VIA, "v1", 0, 5000);
		a.request(5, 0, 0, VIA, "v1", 1, 5000);
		a.request(5, 0, 0, VIA, "waiter", 0, 9000);
		a.releaseAll("v1");
		assertNull(a.holder(0, 0, 0, VIA), "terminal owner dropped its first hold");
		assertEquals("waiter", a.holder(5, 0, 0, VIA), "the queued waiter took the freed point immediately");
		assertTrue(a.isGrantedTo(5, 0, 0, VIA, "waiter"));
	}

	/**
	 * The self-arm of a mission retries every tick and never gives up (an operator may unlock the point
	 * at any time), so the throttled wait message is the only trace of a stuck job - it must name the
	 * blocking point and why (operator park / other holder), not just the target rail.
	 */
	@Test
	public void describeForkWaitNamesTheBlockingPointAndItsHolder() {
		final AtomicLong clock = new AtomicLong(1000);
		final MmtrPointAuthority a = authority(clock);
		final ObjectArrayList<String[]> ops = new ObjectArrayList<>();
		ops.add(new String[]{"0", "0", "0", VIA, "1"});

		assertEquals("no fork inside the approach window", MmtrRunPlanner.describeForkWait(new ObjectArrayList<>(), a, "v1"), "nothing to wait on");

		a.lock(0, 0, 0, VIA);
		assertEquals(MmtrPointAuthority.Result.QUEUED, a.request(0, 0, 0, VIA, "v1", 1, 5000));
		final String locked = MmtrRunPlanner.describeForkWait(ops, a, "v1");
		assertTrue(locked.contains("lock=true"), "an operator park is named as the reason: " + locked);
		assertTrue(locked.contains("wantLeg=1"), "the wait names the leg the plan asked for: " + locked);

		a.unlock(0, 0, 0, VIA);
		assertEquals("all requested forks granted", MmtrRunPlanner.describeForkWait(ops, a, "v1"), "own grant is not a wait");

		assertEquals(MmtrPointAuthority.Result.QUEUED, a.request(0, 0, 0, VIA, "v2", 1, 5000));
		final String held = MmtrRunPlanner.describeForkWait(ops, a, "v2");
		assertTrue(held.contains("holder=v1@1"), "another train's hold is named: " + held);
	}
}
