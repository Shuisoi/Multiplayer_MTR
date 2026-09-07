package org.mtr.core.mmtr.manifest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.core.mmtr.job.MmtrCarSpec;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.core.tool.Utilities;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Rolling-stock manifest ("车辆生成表"): the operator-declared plan of which depot siding should
 * carry which train on every server restart. The server runs an explicit vehicle reset at startup
 * (all generated trains cleared) and then spawns exactly what this table declares. The table only
 * fixes the model/consist per siding; every generated train still gets its own world-unique
 * vehicle id and can be operated by id afterwards (delete / task assignment).
 * File: {@code <root>/<dimension>/mmtr-rolling-stock.json}.
 */
public final class MmtrRollingStockManifest implements SerializedDataBase {

	public final ObjectArrayList<MmtrManifestDepot> depots = new ObjectArrayList<>();

	public static MmtrRollingStockManifest parse(String json) {
		final JsonElement root = JsonParser.parseString(json);
		final MmtrRollingStockManifest manifest = new MmtrRollingStockManifest();
		if (root.isJsonObject()) {
			new JsonReader(root).iterateReaderArray("depots", manifest.depots::clear, reader -> manifest.depots.add(new MmtrManifestDepot(reader)));
		}
		return manifest;
	}

	public static MmtrRollingStockManifest fromFile(Path path) {
		try {
			return parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to load mmtr rolling-stock manifest from " + path, e);
		}
	}

	public void save(Path path) {
		try {
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			Files.write(path, Utilities.getJsonObjectFromData(this).toString().getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new IllegalStateException("Failed to save mmtr rolling-stock manifest to " + path, e);
		}
	}

	public boolean isEmpty() {
		return depots.isEmpty();
	}

	public int sidingEntryCount() {
		int count = 0;
		for (final MmtrManifestDepot depot : depots) {
			count += depot.sidings.size();
		}
		return count;
	}

	@Override
	public void updateData(ReaderBase readerBase) {
		readerBase.iterateReaderArray("depots", depots::clear, reader -> depots.add(new MmtrManifestDepot(reader)));
	}

	@Override
	public void serializeData(WriterBase writerBase) {
		writerBase.writeDataset(depots, "depots");
	}
}
