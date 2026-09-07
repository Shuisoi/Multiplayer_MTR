package org.mtr.core.mmtr.manifest;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.core.mmtr.job.MmtrCarSpec;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;

/**
 * Rolling-stock manifest entry for ONE depot siding: which consist (list of car specs) the server
 * must generate there on every restart (after the explicit vehicle reset). The vehicle id fields
 * inside each car spec are model ids (e.g. "hst"); the generated trains themselves receive fresh
 * world-unique vehicle ids at spawn time.
 */
public final class MmtrManifestSiding implements SerializedDataBase {

	public long sidingId;
	public String name = "";
	public final ObjectArrayList<MmtrCarSpec> cars = new ObjectArrayList<>();

	public MmtrManifestSiding() {
	}

	public MmtrManifestSiding(ReaderBase readerBase) {
		updateData(readerBase);
	}

	@Override
	public void updateData(ReaderBase readerBase) {
		final String raw = readerBase.getString("sidingId", "").trim();
		sidingId = raw.isEmpty() ? readerBase.getLong("sidingId", 0) : Long.parseLong(raw);
		name = readerBase.getString("name", "");
		readerBase.iterateReaderArray("cars", cars::clear, reader -> cars.add(new MmtrCarSpec(reader)));
	}

	@Override
	public void serializeData(WriterBase writerBase) {
		writerBase.writeString("sidingId", String.valueOf(sidingId));
		if (!name.isEmpty()) {
			writerBase.writeString("name", name);
		}
		writerBase.writeDataset(cars, "cars");
	}
}
