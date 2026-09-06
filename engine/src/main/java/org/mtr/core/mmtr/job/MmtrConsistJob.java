package org.mtr.core.mmtr.job;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;

/**
 * A consist job (车底作业单): one consist for one day, started at {@code startTimeOfDayMs}
 * (in-game time of day) by spawning the configured rolling stock on {@code sidingId} inside
 * {@code depotId}, then running its ordered steps. Each step must finish by its own
 * {@link MmtrJobStep#dueTimeOfDayMs}. Replaces the MTR depot frequency/departure timetable for
 * MMTR-managed stock; the web dashboard is the editor.
 */
public final class MmtrConsistJob implements SerializedDataBase {

	public String jobId = "";
	/** 编组代码 (named consist template id): when set and {@link #cars} is empty the scheduler
	 * expands the cars from the server-side template registry - authoring = 车场/股道 + 车辆代码. */
	public String consistId = "";
	/** In-game depot id (world framing stays in game). */
	public long depotId;
	/** Siding inside the depot where the consist is spawned. */
	public long sidingId;
	/** Spawn time, ms after in-game midnight (e.g. 07:00 = 7 * 3_600_000). */
	public long startTimeOfDayMs;
	/** Repeat the job every in-game day. */
	public boolean repeatDaily = true;
	/** Loop automation: restart this job loopEveryMs after it finishes (demo/drill cycles). */
	public boolean loop;
	/** Loop period in ms; &lt;=0 falls back to the scheduler default. */
	public long loopEveryMs;
	public final ObjectArrayList<MmtrCarSpec> cars = new ObjectArrayList<>();
	public final ObjectArrayList<MmtrJobStep> steps = new ObjectArrayList<>();

	public MmtrConsistJob() {
	}

	public MmtrConsistJob(ReaderBase readerBase) {
		updateData(readerBase);
	}

	@Override
	public void updateData(ReaderBase readerBase) {
		jobId = readerBase.getString("jobId", "");
		consistId = readerBase.getString("consistId", "");
		depotId = parseNumericId(readerBase, "depotId");
		sidingId = parseNumericId(readerBase, "sidingId");
		startTimeOfDayMs = readerBase.getLong("startTimeOfDayMs", 0);
		repeatDaily = readerBase.getBoolean("repeatDaily", true);
		loop = readerBase.getBoolean("loop", false);
		loopEveryMs = readerBase.getLong("loopEveryMs", 0);
		readerBase.iterateReaderArray("cars", cars::clear, reader -> cars.add(new MmtrCarSpec(reader)));
		readerBase.iterateReaderArray("steps", steps::clear, reader -> steps.add(new MmtrJobStep(reader)));
	}

	private static long parseNumericId(ReaderBase readerBase, String key) {
		final String raw = readerBase.getString(key, "").trim();
		return raw.isEmpty() ? readerBase.getLong(key, 0) : MmtrJobStep.parseRaw(raw);
	}

	@Override
	public void serializeData(WriterBase writerBase) {
		writerBase.writeString("jobId", jobId);
		if (!consistId.isEmpty()) {
			writerBase.writeString("consistId", consistId);
		}
		writerBase.writeString("depotId", String.valueOf(depotId));
		writerBase.writeString("sidingId", String.valueOf(sidingId));
		writerBase.writeLong("startTimeOfDayMs", startTimeOfDayMs);
		writerBase.writeBoolean("repeatDaily", repeatDaily);
		if (loop) {
			writerBase.writeBoolean("loop", true);
			if (loopEveryMs > 0) {
				writerBase.writeLong("loopEveryMs", loopEveryMs);
			}
		}
		writerBase.writeDataset(cars, "cars");
		writerBase.writeDataset(steps, "steps");
	}
}