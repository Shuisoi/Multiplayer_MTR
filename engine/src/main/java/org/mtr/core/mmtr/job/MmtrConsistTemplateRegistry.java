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
 * Server-side store of named consist templates ("编组代码") for job authoring:
 * {@code <root>/<dimension>/mmtr-consist-templates.json}.
 * A job with an empty {@code cars} list but a {@code consistId} gets its cars expanded from here.
 */
public final class MmtrConsistTemplateRegistry implements SerializedDataBase {

	public final ObjectArrayList<MmtrConsistTemplate> templates = new ObjectArrayList<>();

	public static MmtrConsistTemplateRegistry parse(String json) {
		final JsonElement root = JsonParser.parseString(json);
		final MmtrConsistTemplateRegistry registry = new MmtrConsistTemplateRegistry();
		if (root.isJsonObject()) {
			new JsonReader(root).iterateReaderArray("templates", registry.templates::clear, reader -> registry.templates.add(new MmtrConsistTemplate(reader)));
		}
		return registry;
	}

	public static MmtrConsistTemplateRegistry fromFile(Path path) {
		try {
			return parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to load mmtr consist templates from " + path, e);
		}
	}

	public MmtrConsistTemplate get(String id) {
		for (final MmtrConsistTemplate template : templates) {
			if (template.id.equals(id)) {
				return template;
			}
		}
		return null;
	}

	public void save(Path path) {
		try {
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			Files.write(path, Utilities.getJsonObjectFromData(this).toString().getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new IllegalStateException("Failed to save mmtr consist templates to " + path, e);
		}
	}

	@Override
	public void updateData(ReaderBase readerBase) {
		readerBase.iterateReaderArray("templates", templates::clear, reader -> templates.add(new MmtrConsistTemplate(reader)));
	}

	@Override
	public void serializeData(WriterBase writerBase) {
		writerBase.writeDataset(templates, "templates");
	}
}
