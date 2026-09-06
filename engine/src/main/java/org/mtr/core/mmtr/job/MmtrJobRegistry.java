package org.mtr.core.mmtr.job;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.core.tool.Utilities;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server-side store of consist jobs (the web editor's document). Persisted as
 * {@code <root>/<dimension>/mmtr-jobs.json}:
 * <pre>{ "jobs": [ { "jobId": "...", "depotId": "...", ... } ] }</pre>
 * Replaces the MTR depot frequency/departure timetable for MMTR-managed stock.
 */
public final class MmtrJobRegistry implements SerializedDataBase {

	public final ObjectArrayList<MmtrConsistJob> jobs = new ObjectArrayList<>();

	public static MmtrJobRegistry parse(String json) {
		final JsonElement root = JsonParser.parseString(json);
		final MmtrJobRegistry registry = new MmtrJobRegistry();
		if (root.isJsonObject()) {
			new JsonReader(root).iterateReaderArray("jobs", registry.jobs::clear, reader -> registry.jobs.add(new MmtrConsistJob(reader)));
		}
		return registry;
	}

	public static MmtrJobRegistry fromFile(Path path) {
		try {
			return parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to load mmtr jobs from " + path, e);
		}
	}

	public void save(Path path) {
		try {
			if (path.getParent() != null) {
				java.nio.file.Files.createDirectories(path.getParent());
			}
			Files.write(path, Utilities.getJsonObjectFromData(this).toString().getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new IllegalStateException("Failed to save mmtr jobs to " + path, e);
		}
	}

	public MmtrConsistJob get(String jobId) {
		for (final MmtrConsistJob job : jobs) {
			if (job.jobId.equals(jobId)) {
				return job;
			}
		}
		return null;
	}

	/** Replaces an existing job (same jobId) or appends it. */
	public void put(MmtrConsistJob job) {
		for (int i = 0; i < jobs.size(); i++) {
			if (jobs.get(i).jobId.equals(job.jobId)) {
				jobs.set(i, job);
				return;
			}
		}
		jobs.add(job);
	}

	public boolean remove(String jobId) {
		return jobs.removeIf(job -> job.jobId.equals(jobId));
	}

	@Override
	public void updateData(ReaderBase readerBase) {
		readerBase.iterateReaderArray("jobs", jobs::clear, reader -> jobs.add(new MmtrConsistJob(reader)));
	}

	@Override
	public void serializeData(WriterBase writerBase) {
		writerBase.writeDataset(jobs, "jobs");
	}
}