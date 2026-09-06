package org.mtr.core.mmtr.job;

import org.jspecify.annotations.Nullable;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;

/**
 * One ordered step of a consist job. Types:
 * <ul>
 *   <li>MOVE_TO - run the consist to {@code targetId} (a platform or siding); the step completes
 *       when the consist arrives (dueTimeOfDayMs = latest allowed arrival).</li>
 *   <li>SERVE - passenger work at the current platform/siding: open doors, dwell, close doors;
 *       completes when doors are closed (dueTimeOfDayMs = latest departure).</li>
 *   <li>COUPLE - attach the consist with id {@code targetId} standing on the current siding.</li>
 *   <li>UNCOUPLE - detach at the current siding, leaving car index {@code targetIndex}.</li>
 * </ul>
 * Each step must finish by its dueTimeOfDayMs (in-game time of day); the executor fails the job
 * when a step misses its deadline.
 */
public final class MmtrJobStep implements SerializedDataBase {

	public enum StepType { MOVE_TO, SERVE, COUPLE, UNCOUPLE }

	public String stepId = "";
	public StepType type = StepType.MOVE_TO;
	/**
	 * Reference to an in-game object: platform/siding id (MOVE_TO/SERVE/COUPLE) - world framing
	 * (station boxes / depot) stays in-game exactly as today.
	 */
	public long targetId;
	/** For UNCOUPLE: car index to cut after. */
	public int targetIndex = -1;
	/** Latest allowed completion time, milliseconds after in-game midnight. */
	public long dueTimeOfDayMs;
	public @Nullable String note;

	public MmtrJobStep() {
	}

	public MmtrJobStep(ReaderBase readerBase) {
		updateData(readerBase);
	}

	@Override
	public void updateData(ReaderBase readerBase) {
		stepId = readerBase.getString("stepId", "");
		final String typeString = readerBase.getString("type", "MOVE_TO");
		try {
			type = StepType.valueOf(typeString);
		} catch (IllegalArgumentException e) {
			type = StepType.MOVE_TO;
		}
		targetId = parseId(readerBase, "targetId");
		targetIndex = readerBase.getInt("targetIndex", -1);
		dueTimeOfDayMs = readerBase.getLong("dueTimeOfDayMs", 0);
		final String noteString = readerBase.getString("note", "");
		note = noteString.isEmpty() ? null : noteString;
	}

	@Override
	public void serializeData(WriterBase writerBase) {
		writerBase.writeString("stepId", stepId);
		writerBase.writeString("type", type.name());
		// Ids travel as strings so 64-bit in-game ids survive the web round trip.
		writerBase.writeString("targetId", String.valueOf(targetId));
		writerBase.writeInt("targetIndex", targetIndex);
		writerBase.writeLong("dueTimeOfDayMs", dueTimeOfDayMs);
		if (note != null && !note.isEmpty()) {
			writerBase.writeString("note", note);
		}
	}

	static long parseId(ReaderBase readerBase, String key) {
		final String raw = readerBase.getString(key, "");
		if (!raw.isEmpty()) {
			try {
				return Long.parseLong(raw.trim());
			} catch (NumberFormatException ignored) {
				// fall through to numeric read
			}
		}
		return readerBase.getLong(key, 0);
	}
}
