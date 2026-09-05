package org.mtr.core.mmtr;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads ConsistType definitions from a server-side JSON file:
 * <pre>
 * { "consistTypes": [ { "id": "emu_8", "controlMode": "NOTCHED", ... }, ... ] }
 * </pre>
 */
public final class ConsistTypeRegistry {

	private final Map<String, ConsistType> byId;

	private ConsistTypeRegistry(Map<String, ConsistType> byId) {
		this.byId = Collections.unmodifiableMap(byId);
	}

	public static ConsistTypeRegistry parse(String json) {
		final JsonElement root = JsonParser.parseString(json);
		final JsonArray array = root.isJsonObject() ? root.getAsJsonObject().getAsJsonArray("consistTypes") : root.getAsJsonArray();
		final Map<String, ConsistType> map = new LinkedHashMap<>();
		if (array != null) {
			for (JsonElement element : array) {
				final ConsistType type = ConsistType.fromJson(element.getAsJsonObject());
				map.put(type.getId(), type);
			}
		}
		return new ConsistTypeRegistry(map);
	}

	public static ConsistTypeRegistry fromFile(Path path) {
		try {
			return parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to load consist types from " + path, e);
		}
	}

	public ConsistType get(String id) { return byId.get(id); }

	public boolean contains(String id) { return byId.containsKey(id); }

	public Map<String, ConsistType> all() { return byId; }
}
