package org.mtr.core.mmtr.manifest;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;

/** Rolling-stock manifest entry for one depot: which of its sidings generate what. */
public final class MmtrManifestDepot implements SerializedDataBase {

	public long depotId;
	public String name = "";
	public final ObjectArrayList<MmtrManifestSiding> sidings = new ObjectArrayList<>();

	public MmtrManifestDepot() {
	}

	public MmtrManifestDepot(ReaderBase readerBase) {
		updateData(readerBase);
	}

	@Override
	public void updateData(ReaderBase readerBase) {
		final String raw = readerBase.getString("depotId", "").trim();
		depotId = raw.isEmpty() ? readerBase.getLong("depotId", 0) : Long.parseLong(raw);
		name = readerBase.getString("name", "");
		readerBase.iterateReaderArray("sidings", sidings::clear, reader -> sidings.add(new MmtrManifestSiding(reader)));
	}

	@Override
	public void serializeData(WriterBase writerBase) {
		writerBase.writeString("depotId", String.valueOf(depotId));
		if (!name.isEmpty()) {
			writerBase.writeString("name", name);
		}
		writerBase.writeDataset(sidings, "sidings");
	}
}
