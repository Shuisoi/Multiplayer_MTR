package org.mtr.core.mmtr.job;

import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;

/**
 * One named consist template ("编组代码"): an ops-maintained rolling-stock pattern referenced by
 * jobs through {@link MmtrConsistJob#consistId} instead of hand-authoring every car. The template
 * keeps the same stable {@link MmtrCarSpec} schema the executor already consumes, so spawning and
 * COUPLE/UNCOUPLE machinery is unchanged - templates only remove the authoring boilerplate.
 */
public final class MmtrConsistTemplate implements SerializedDataBase {

	public String id = "";
	public String name = "";
	public final it.unimi.dsi.fastutil.objects.ObjectArrayList<MmtrCarSpec> cars = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();

	public MmtrConsistTemplate() {
	}

	public MmtrConsistTemplate(ReaderBase readerBase) {
		updateData(readerBase);
	}

	@Override
	public void updateData(ReaderBase readerBase) {
		id = readerBase.getString("id", "");
		name = readerBase.getString("name", "");
		readerBase.iterateReaderArray("cars", cars::clear, reader -> cars.add(new MmtrCarSpec(reader)));
	}

	@Override
	public void serializeData(WriterBase writerBase) {
		writerBase.writeString("id", id);
		if (!name.isEmpty()) {
			writerBase.writeString("name", name);
		}
		writerBase.writeDataset(cars, "cars");
	}
}
