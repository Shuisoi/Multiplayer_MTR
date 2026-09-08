package org.mtr.core.mmtr.consist;

import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.consist.MmtrCabState.Cab;
import org.mtr.core.mmtr.consist.MmtrCabState.End;

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
}
