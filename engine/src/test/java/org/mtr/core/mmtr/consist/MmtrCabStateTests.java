package org.mtr.core.mmtr.consist;

import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.consist.MmtrCabState.Cab;
import org.mtr.core.mmtr.consist.MmtrCabState.End;
import org.mtr.core.mmtr.consist.MmtrCabState.KeyHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2: the change-ends state machine. The user model is a crew action — stop, pull the key, walk to
 * the other cab, insert the key — so these tests pin the preconditions and the direction mapping,
 * and document that the consist geometry is never involved.
 */
public final class MmtrCabStateTests {

	@Test
	public void insertKeyRequiresAStandingTrainAndTheDriverAtThatCab() {
		final MmtrCabState state = new MmtrCabState();
		assertFalse(state.insertKey(Cab.CAB_A, false, true), "cannot take a cab on a moving train");
		assertFalse(state.insertKey(Cab.CAB_A, true, false), "the driver must be standing at the cab");
		assertFalse(state.insertKey(Cab.NONE, true, true), "NONE is not a cab");
		assertFalse(state.isManned());
		assertTrue(state.insertKey(Cab.CAB_A, true, true));
		assertEquals(Cab.CAB_A, state.activeCab());
	}

	@Test
	public void aConsistHoldsExactlyOneKey() {
		final MmtrCabState state = new MmtrCabState();
		assertTrue(state.insertKey(Cab.CAB_A, true, true));
		assertFalse(state.insertKey(Cab.CAB_B, true, true), "a second key cannot be inserted");
		assertEquals(Cab.CAB_A, state.activeCab());
		assertTrue(state.removeKey());
		assertFalse(state.removeKey(), "nothing left to remove");
		assertTrue(state.insertKey(Cab.CAB_B, true, true));
	}

	@Test
	public void removeKeyIsLegalAtAnyTime() {
		// Pulling the key while rolling is allowed: it drops the driving authority immediately and the
		// caller brakes to a stand. This is why 换端 never needs reverse running.
		final MmtrCabState state = new MmtrCabState();
		assertTrue(state.insertKey(Cab.CAB_A, true, true));
		assertTrue(state.removeKey(), "key removal has no 'train stopped' precondition");
		assertFalse(state.isManned());
		assertNull(state.leadingEnd());
	}

	@Test
	public void changeEndsFlipsTheCabOnlyWhenStoppedAndManned() {
		final MmtrCabState state = new MmtrCabState();
		assertFalse(state.changeEnds(true), "unmanned: nobody to walk anywhere");
		assertTrue(state.insertKey(Cab.CAB_A, true, true));
		assertFalse(state.changeEnds(false), "the driver cannot change cabs while the train rolls");
		assertEquals(Cab.CAB_A, state.activeCab());
		assertTrue(state.changeEnds(true));
		assertEquals(Cab.CAB_B, state.activeCab());
		assertTrue(state.changeEnds(true));
		assertEquals(Cab.CAB_A, state.activeCab(), "changing ends twice returns to the original cab");
	}

	@Test
	public void theMannedCabDecidesWhichEndLeads() {
		final MmtrCabState state = new MmtrCabState();
		// CAB_A sits at the A end and its driver faces outward past that end, so A leads.
		assertTrue(state.insertKey(Cab.CAB_A, true, true));
		assertEquals(End.A, state.leadingEnd());
		assertEquals(End.B, state.trailingEnd());
		assertTrue(state.travelsToward(End.A));
		assertFalse(state.travelsToward(End.B));
		// After 换端 the other cab leads, and nothing else about the consist changed.
		assertTrue(state.changeEnds(true));
		assertEquals(End.B, state.leadingEnd());
		assertEquals(End.A, state.trailingEnd());
		assertTrue(state.travelsToward(End.B));
		assertEquals(Cab.CAB_A, MmtrCabState.cabFor(End.A));
		assertEquals(Cab.CAB_B, MmtrCabState.cabFor(End.B));
	}

	private static final java.util.UUID DRIVER_A = java.util.UUID.fromString("00000000-0000-0000-0000-00000000000a");
	private static final java.util.UUID DRIVER_B = java.util.UUID.fromString("00000000-0000-0000-0000-00000000000b");

	@Test
	public void theSystemKeyIsAPlaceholderTheCrewCanAlwaysTake() {
		// 实机缺陷 (2026-09-10): a consist staged from the yard manifest holds the engine's system key,
		// and the old insertKey() refused every player because the consist was "already manned" - the
		// driver could never get into the cab of a train they had just spawned.
		final MmtrCabState state = new MmtrCabState();
		assertTrue(state.insertSystemKey(Cab.CAB_A, true));
		assertEquals(KeyHolder.SYSTEM, state.keyHolder());
		assertNull(state.crewUuid());
		assertTrue(state.isSystemKey());
		assertTrue(state.insertKey(Cab.CAB_B, true, true, DRIVER_A), "the crew takes the cab from the system");
		assertEquals(Cab.CAB_B, state.activeCab());
		assertEquals(KeyHolder.CREW, state.keyHolder());
		assertEquals(DRIVER_A, state.crewUuid());
	}

	@Test
	public void theEngineNeverTakesACabFromACrew() {
		final MmtrCabState state = new MmtrCabState();
		assertTrue(state.insertKey(Cab.CAB_A, true, true, DRIVER_A));
		assertFalse(state.insertSystemKey(Cab.CAB_B, true), "a running crew always wins over the engine");
		assertEquals(Cab.CAB_A, state.activeCab());
		assertFalse(state.insertSystemKey(Cab.CAB_A, false), "and the system key still needs a standing train");
	}

	@Test
	public void oneConsistOneCrewKey() {
		final MmtrCabState state = new MmtrCabState();
		assertTrue(state.insertKey(Cab.CAB_A, true, true, DRIVER_A));
		assertFalse(state.insertKey(Cab.CAB_B, true, true, DRIVER_B), "another crew member cannot take the consist");
		assertFalse(state.insertKey(Cab.CAB_B, true, true), "and an anonymous insert cannot either");
		assertTrue(state.insertKey(Cab.CAB_A, true, true, DRIVER_A), "the holder may re-enter their own cab");
		assertFalse(state.removeKey(DRIVER_B), "another crew member cannot pull someone else's key");
		assertTrue(state.isManned());
		assertTrue(state.removeKey(DRIVER_A));
		assertFalse(state.isManned());
		assertEquals(KeyHolder.NONE, state.keyHolder());
		assertNull(state.crewUuid());
	}

	@Test
	public void aCrewMemberCannotPullTheSystemKey() {
		final MmtrCabState state = new MmtrCabState();
		assertTrue(state.insertSystemKey(Cab.CAB_A, true));
		assertFalse(state.removeKey(DRIVER_A), "the player never inserted this key");
		assertTrue(state.isSystemKey());
		assertTrue(state.removeKey(), "an operator command may release the system key");
		assertFalse(state.isManned());
	}

	@Test
	public void isHeldByChecksTheActualKeyHolder() {
		final MmtrCabState state = new MmtrCabState();
		assertTrue(state.insertSystemKey(Cab.CAB_A, true));
		assertFalse(state.isHeldBy(DRIVER_A), "the engine's placeholder key drives nobody");
		assertTrue(state.isHeldBy(null), "legacy callers ask 'is a key in the cab at all'");
		assertTrue(state.insertKey(Cab.CAB_A, true, true, DRIVER_A));
		assertTrue(state.isHeldBy(DRIVER_A));
		assertFalse(state.isHeldBy(DRIVER_B));
		assertTrue(state.isHeldBy(null));
	}
}
