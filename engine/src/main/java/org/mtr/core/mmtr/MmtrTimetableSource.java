package org.mtr.core.mmtr;

/**
 * Pure timetable maths for the periodic task source (a passenger line assigns a recurring
 * service mission to a train every {@code period}; the mission is owned by the train and may be
 * executed by the autopilot, an AI or a player).
 */
public final class MmtrTimetableSource {

	private MmtrTimetableSource() {
	}

	/** First dispatch at or after {@code time} for a service repeating every {@code periodMillis} from {@code firstDispatch}. */
	public static long firstDispatchAtOrAfter(long time, long firstDispatch, long periodMillis) {
		if (periodMillis <= 0) {
			return firstDispatch;
		}
		final long delta = time - firstDispatch;
		if (delta <= 0) {
			return firstDispatch;
		}
		final long periods = (delta + periodMillis - 1) / periodMillis;
		return firstDispatch + periods * periodMillis;
	}

	/** Next dispatch strictly after {@code time} (i.e. the following occurrence). */
	public static long nextDispatchAfter(long time, long firstDispatch, long periodMillis) {
		return firstDispatchAtOrAfter(time + 1, firstDispatch, periodMillis);
	}
}
