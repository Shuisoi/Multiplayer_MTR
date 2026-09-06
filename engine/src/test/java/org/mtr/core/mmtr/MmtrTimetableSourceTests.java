package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Timetable source: periodic task assignment maths. */
public final class MmtrTimetableSourceTests {

	@Test
	public void firstDispatchAtOrAfterHandlesBeforeFirstAndMidPeriod() {
		assertEquals(600_000L, MmtrTimetableSource.firstDispatchAtOrAfter(0L, 600_000L, 600_000L));
		assertEquals(600_000L, MmtrTimetableSource.firstDispatchAtOrAfter(600_000L, 600_000L, 600_000L));
		assertEquals(1_200_000L, MmtrTimetableSource.firstDispatchAtOrAfter(900_000L, 600_000L, 600_000L));
		assertEquals(1_200_000L, MmtrTimetableSource.firstDispatchAtOrAfter(1_200_000L, 600_000L, 600_000L));
	}

	@Test
	public void nextDispatchIsStrictlyAfter() {
		assertEquals(600_000L, MmtrTimetableSource.nextDispatchAfter(599_999L, 0L, 600_000L));
		assertEquals(1_200_000L, MmtrTimetableSource.nextDispatchAfter(600_000L, 0L, 600_000L));
	}

	@Test
	public void nonPositivePeriodFallsBackToFirst() {
		assertEquals(123L, MmtrTimetableSource.firstDispatchAtOrAfter(999_999L, 123L, 0L));
	}
}
