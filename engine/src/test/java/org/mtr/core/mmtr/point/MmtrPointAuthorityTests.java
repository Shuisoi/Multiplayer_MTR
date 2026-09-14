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

	/**
	 * T1b ①：**循环等待**用例 —— A 要 [P,Q]、B 要 [Q,P]（同一对岔，顺序相反）。
	 *
	 * <p>修前 {@code requestForkOps} 逐个岔申请、失败**不回滚**，于是 A 持 P 等 Q、B 持 Q 等 P；
	 * 又因为 armed 的任务每 tick 续期（授权永不超时），这是一个**永久**死锁。
	 * 原子申请把 hold-and-wait 从构造上消灭：拿不到整组，手里就是空的 —— 环也就无从形成。</p>
	 */
	@Test
	public void atomicAcquisitionLeavesNoPartialHoldingSoACircularWaitCannotForm() {
		final AtomicLong clock = new AtomicLong(1000);
		final MmtrPointAuthority a = authority(clock);
		final String p = "FFFF0000";
		final String q = "FFFF1000";

		final ObjectArrayList<String[]> setForward = new ObjectArrayList<>();
		setForward.add(new String[]{"0", "0", "0", p, "0"});
		setForward.add(new String[]{"10", "0", "0", q, "0"});
		assertEquals(MmtrPointAuthority.Result.GRANTED, a.requestAtomically(setForward, "v1", 5000), "整组都空着 → 一次全拿到");
		assertTrue(a.isGrantedTo(0, 0, 0, p, "v1"), "p 归 v1");
		assertTrue(a.isGrantedTo(10, 0, 0, q, "v1"), "q 归 v1");

		final ObjectArrayList<String[]> setReversed = new ObjectArrayList<>();
		setReversed.add(new String[]{"10", "0", "0", q, "0"});
		setReversed.add(new String[]{"0", "0", "0", p, "0"});
		assertEquals(MmtrPointAuthority.Result.QUEUED, a.requestAtomically(setReversed, "v2", 5000), "整组拿不到 → 一处都不拿");

		assertFalse(a.isGrantedTo(10, 0, 0, q, "v2"), "**部分持有必须为空**：q 没有给 v2（修前它会先拿到这一处）");
		assertFalse(a.isGrantedTo(0, 0, 0, p, "v2"), "p 也没有给 v2");
		assertEquals("v1", a.holder(0, 0, 0, p), "p 仍在前车手里");
		assertEquals("v1", a.holder(10, 0, 0, q), "q 也仍在前车手里");

		a.passed(0, 0, 0, p, "v1");
		a.passed(10, 0, 0, q, "v1");
		assertEquals(MmtrPointAuthority.Result.GRANTED, a.requestAtomically(setReversed, "v2", 9000), "前车让空后整组到手");
		assertTrue(a.isGrantedTo(0, 0, 0, p, "v2") && a.isGrantedTo(10, 0, 0, q, "v2"), "v2 整组都在手里");
	}

	/** T1b：原子组的"全无"包括**让出自己已经拿着的那一处**（部分持有正是死锁的原料）。 */
	@Test
	public void atomicFailureGivesUpAMemberThisOwnerAlreadyHeld() {
		final AtomicLong clock = new AtomicLong(1000);
		final MmtrPointAuthority a = authority(clock);
		final String p = "FFFF0000";
		final String q = "FFFF1000";

		assertEquals(MmtrPointAuthority.Result.GRANTED, a.request(0, 0, 0, p, "v1", 0, 5000), "v1 先单独拿到 p");
		assertEquals(MmtrPointAuthority.Result.GRANTED, a.request(10, 0, 0, q, "v2", 0, 5000), "v2 拿走 q");

		final ObjectArrayList<String[]> set = new ObjectArrayList<>();
		set.add(new String[]{"0", "0", "0", p, "0"});
		set.add(new String[]{"10", "0", "0", q, "0"});
		assertEquals(MmtrPointAuthority.Result.QUEUED, a.requestAtomically(set, "v1", 5000), "q 拿不到 → 整组不成立");
		assertFalse(a.isGrantedTo(0, 0, 0, p, "v1"), "整组不成立时，连已经拿着的 p 也要让出去");
	}

	/**
	 * T1b：原子等待者**跨自己的重试**不许留半个组。
	 *
	 * <p>诚实边界：逐进向队列的递补（{@code promote}）是按**单点**发生的，所以前车只让出一处时，
	 * 等待者可能被临时提上来拿到那一处。这不是永久的：armed 的任务每 tick 重试，一问整组拿不到就
	 * 把已有的让出去，所以部分持有被限制在一个 tick 之内，"永久循环等待"不成立。
	 * 本用例把这个不变量钉住：重试之后**要么全有、要么全无**。</p>
	 */
	@Test
	public void anAtomicWaiterNeverKeepsAHalfSetAcrossItsOwnRetry() {
		final AtomicLong clock = new AtomicLong(1000);
		final MmtrPointAuthority a = authority(clock);
		final String p = "FFFF0000";
		final String q = "FFFF1000";

		final ObjectArrayList<String[]> forward = new ObjectArrayList<>();
		forward.add(new String[]{"0", "0", "0", p, "0"});
		forward.add(new String[]{"10", "0", "0", q, "0"});
		final ObjectArrayList<String[]> reversed = new ObjectArrayList<>();
		reversed.add(new String[]{"10", "0", "0", q, "0"});
		reversed.add(new String[]{"0", "0", "0", p, "0"});

		assertEquals(MmtrPointAuthority.Result.GRANTED, a.requestAtomically(forward, "v1", 5000));
		assertEquals(MmtrPointAuthority.Result.QUEUED, a.requestAtomically(reversed, "v2", 5000), "v2 整组排队");

		a.passed(0, 0, 0, p, "v1");   // 前车只让出一处（q 还占着）

		assertEquals(MmtrPointAuthority.Result.QUEUED, a.requestAtomically(reversed, "v2", 9000), "整组仍未成立");
		final boolean holdsP = a.isGrantedTo(0, 0, 0, p, "v2");
		final boolean holdsQ = a.isGrantedTo(10, 0, 0, q, "v2");
		assertEquals(holdsP, holdsQ, "重试之后整组要么全有、要么全无（p=" + holdsP + " q=" + holdsQ + "）");
	}

	/**
	 * T1b 裁决链：**没有计划时刻时必须是纯 FIFO**。
	 *
	 * <p>这一条是"基线不动"的守卫：排序机制换了（从"取队首"变成"选最优"），但只要没人有计划时刻、
	 * 也没人等超时，比较键就退化成入队时刻 —— 与修前逐位一致。</p>
	 */
	@Test
	public void withoutAPlanTheQueueStaysPureFifo() {
		final AtomicLong clock = new AtomicLong(1000);
		final MmtrPointAuthority a = authority(clock);
		final String p = "FFFF0000";
		assertEquals(MmtrPointAuthority.Result.GRANTED, a.request(0, 0, 0, p, "holder", 0, 600000));

		final ObjectArrayList<String[]> ops = new ObjectArrayList<>();
		ops.add(new String[]{"0", "0", "0", p, "0"});
		clock.set(2000);
		assertEquals(MmtrPointAuthority.Result.QUEUED, a.requestAtomically(ops, "first", 600000));
		clock.set(3000);
		assertEquals(MmtrPointAuthority.Result.QUEUED, a.requestAtomically(ops, "second", 600000));
		clock.set(4000);
		assertEquals(MmtrPointAuthority.Result.QUEUED, a.requestAtomically(ops, "third", 600000));

		a.passed(0, 0, 0, p, "holder");
		assertEquals("first", a.holder(0, 0, 0, p), "先到的先走");
		a.passed(0, 0, 0, p, "first");
		assertEquals("second", a.holder(0, 0, 0, p), "仍然按到达序");
	}

	/**
	 * T1b 裁决链第一档：**计划时刻优先** —— 晚到但计划更早的列车先走。
	 * （T5 的时刻表预排就是靠这一档把"谁先进咽喉"按计划定下来。）
	 */
	@Test
	public void anEarlierPlannedTrainIsServedFirstEvenThoughItAskedLater() {
		final AtomicLong clock = new AtomicLong(1000);
		final MmtrPointAuthority a = authority(clock);
		final String p = "FFFF0000";
		assertEquals(MmtrPointAuthority.Result.GRANTED, a.request(0, 0, 0, p, "holder", 0, 600000));

		final ObjectArrayList<String[]> ops = new ObjectArrayList<>();
		ops.add(new String[]{"0", "0", "0", p, "0"});
		assertEquals(MmtrPointAuthority.Result.QUEUED, a.requestAtomically(ops, "vNoPlan", 600000), "无计划：按到达序排队");
		assertEquals(MmtrPointAuthority.Result.QUEUED, a.requestAtomically(ops, "vPlanned", 600000, 500L), "有计划且更早，但排在后边");

		a.passed(0, 0, 0, p, "holder");
		assertEquals("vPlanned", a.holder(0, 0, 0, p), "计划时刻更早的列车先得到道岔");
	}

	/**
	 * T1b 裁决链最后一档：**防饿死**。
	 *
	 * <p>用户裁定"玩家只有司机、没有调度员"，所以不能靠人来解开一个永远轮不到的等待。
	 * 等过 {@link MmtrPointAuthority#MMTR_STARVATION_MILLIS} 的列车提到上一档，
	 * 越过新来的高优先级列车。</p>
	 */
	@Test
	public void aStarvedWaiterOvertakesANewerHigherPriorityTrain() {
		final AtomicLong clock = new AtomicLong(1000);
		final MmtrPointAuthority a = authority(clock);
		final String p = "FFFF0000";
		assertEquals(MmtrPointAuthority.Result.GRANTED, a.request(0, 0, 0, p, "holder", 0, 9_000_000));

		final ObjectArrayList<String[]> ops = new ObjectArrayList<>();
		ops.add(new String[]{"0", "0", "0", p, "0"});
		assertEquals(MmtrPointAuthority.Result.QUEUED, a.requestAtomically(ops, "vOld", 9_000_000), "vOld 排队");

		// 它已经等过了防饿死阈值，这时来了一列计划更早的新车
		clock.set(1000 + MmtrPointAuthority.MMTR_STARVATION_MILLIS + 1);
		assertEquals(MmtrPointAuthority.Result.QUEUED, a.requestAtomically(ops, "vNew", 9_000_000, 1L), "vNew 计划更早但刚来");

		a.passed(0, 0, 0, p, "holder");
		assertEquals("vOld", a.holder(0, 0, 0, p), "等久了的列车越过新来的高优先级列车 —— 不会被饿死");
	}
}
