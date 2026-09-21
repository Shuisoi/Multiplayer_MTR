package org.mtr.core.mmtr;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads ConsistType definitions from a server-side JSON file:
 * <pre>
 * { "consistTypes": [ { "id": "emu_8", "controlMode": "NOTCHED", ... }, ... ],
 *   "carTypeIds": { "br101": "br101_three_handle", ... },
 *   "defaultConsistTypeId": "emu_8" }
 * </pre>
 *
 * <p>{@code carTypeIds} 是"车型 → 车底"的映射：让**车自己**决定用哪套操纵规格（BR101 的三根手柄因此
 * 不必把整个维度改成三手柄车底）。解析见 {@link MmtrCarTypeResolver}。</p>
 */
public final class ConsistTypeRegistry {

	private final Map<String, ConsistType> byId;
	private final Map<String, String> typeIdByCarModel;
	private final String defaultId;

	private ConsistTypeRegistry(Map<String, ConsistType> byId, Map<String, String> typeIdByCarModel, String defaultId) {
		this.byId = Collections.unmodifiableMap(byId);
		this.typeIdByCarModel = Collections.unmodifiableMap(typeIdByCarModel);
		this.defaultId = defaultId != null && byId.containsKey(defaultId) ? defaultId : null;
	}

	public static ConsistTypeRegistry parse(String json) {
		final JsonElement root = JsonParser.parseString(json);
		final String defaultId = root.isJsonObject() && root.getAsJsonObject().has("defaultConsistTypeId")
			? root.getAsJsonObject().get("defaultConsistTypeId").getAsString() : null;
		final JsonArray array = root.isJsonObject() ? root.getAsJsonObject().getAsJsonArray("consistTypes") : root.getAsJsonArray();
		final Map<String, ConsistType> map = new LinkedHashMap<>();
		if (array != null) {
			for (JsonElement element : array) {
				final ConsistType type = ConsistType.fromJson(element.getAsJsonObject());
				map.put(type.getId(), type);
			}
		}
		final Map<String, String> carMap = new LinkedHashMap<>();
		if (root.isJsonObject() && root.getAsJsonObject().has("carTypeIds") && root.getAsJsonObject().get("carTypeIds").isJsonObject()) {
			for (final Map.Entry<String, JsonElement> entry : root.getAsJsonObject().getAsJsonObject("carTypeIds").entrySet()) {
				final String typeId = entry.getValue().isJsonNull() ? "" : entry.getValue().getAsString();
				// 指向不存在的车底 = 配置写错：忽略它，让这节车退回缺省，而不是让整份配置解析失败。
				if (map.containsKey(typeId)) {
					carMap.put(entry.getKey(), typeId);
				}
			}
		}
		return new ConsistTypeRegistry(map, carMap, defaultId);
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

	/** 车型（资源包里的 vehicle id）映射到的车底 id；没配过返回 null。 */
	public @Nullable String typeIdForCar(String carModelId) {
		return carModelId == null ? null : typeIdByCarModel.get(carModelId);
	}

	public String getDefaultId() { return defaultId; }
}
