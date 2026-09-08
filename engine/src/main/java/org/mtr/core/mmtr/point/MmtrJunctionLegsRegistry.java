package org.mtr.core.mmtr.point;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * MMTR junction leg tables (进向表): the authoritative, world-persisted definition of "which rails
 * a train may continue onto when arriving at a node on a given via rail". One entry = one
 * (node, via rail) approach; its value = the ordered list of allowed continuation rails.
 *
 * <p>Unlike the geometric auto-detection in {@link MmtrPoint#computeOrderedLegs}, a table entry is
 * authored by a human/tool and wins over geometry: the listed rails are the continuations in table
 * order, even when they fold back sharply (terminal balloon turnbacks / 掉头 leads) or when a
 * crossing hides extra arms. Junctions without an entry keep the geometric ordering, so the table
 * only needs to exist where drawing cannot express the intended movements.</p>
 */
public final class MmtrJunctionLegsRegistry {

	public static final class LegsStore {

		/** key = "x,y,z|viaHex" -> declared continuation hexes, table order = leg order. */
		public final Map<String, ObjectArrayList<String>> legs = new HashMap<>();

		public void set(long x, long y, long z, String viaRailHex, @Nullable ObjectArrayList<String> legHexes) {
			if (legHexes == null || legHexes.isEmpty()) {
				legs.remove(key(x, y, z, viaRailHex));
			} else {
				legs.put(key(x, y, z, viaRailHex), new ObjectArrayList<>(legHexes));
			}
		}

		public boolean contains(long x, long y, long z, String viaRailHex) {
			return legs.containsKey(key(x, y, z, viaRailHex));
		}

		/** The declared leg list for (node, via), or {@code null} when the approach is not tabled. */
		public @Nullable ObjectArrayList<String> get(long x, long y, long z, String viaRailHex) {
			return legs.get(key(x, y, z, viaRailHex));
		}

		public int size() {
			return legs.size();
		}

		private static String key(long x, long y, long z, String viaRailHex) {
			return x + "," + y + "," + z + "|" + viaRailHex;
		}
	}

	public static LegsStore load(Path path) {
		final LegsStore store = new LegsStore();
		try {
			if (Files.exists(path)) {
				final JsonElement root = JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
				if (root.isJsonObject() && root.getAsJsonObject().has("legs") && root.getAsJsonObject().get("legs").isJsonArray()) {
					for (final JsonElement el : root.getAsJsonObject().getAsJsonArray("legs")) {
						final JsonObject s = el.getAsJsonObject();
						final ObjectArrayList<String> list = new ObjectArrayList<>();
						for (final JsonElement leg : s.getAsJsonArray("legs")) {
							list.add(leg.getAsString());
						}
						store.set(s.get("x").getAsLong(), s.get("y").getAsLong(), s.get("z").getAsLong(),
							s.get("via").getAsString(), list);
					}
				}
			}
		} catch (Exception e) {
			System.out.println("[MMTR-JL] failed to load junction leg tables: " + e.getMessage());
		}
		return store;
	}

	public static void save(Path path, Map<String, ObjectArrayList<String>> legs) {
		try {
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			final JsonArray arr = new JsonArray();
			for (final Map.Entry<String, ObjectArrayList<String>> e : legs.entrySet()) {
				final String[] p = e.getKey().split("\\|");
				if (p.length != 2) {
					continue;
				}
				final String[] c = p[0].split(",");
				if (c.length != 3) {
					continue;
				}
				final JsonObject o = new JsonObject();
				o.addProperty("x", Long.parseLong(c[0]));
				o.addProperty("y", Long.parseLong(c[1]));
				o.addProperty("z", Long.parseLong(c[2]));
				o.addProperty("via", p[1]);
				final JsonArray list = new JsonArray();
				for (final String hex : e.getValue()) {
					list.add(hex);
				}
				o.add("legs", list);
				arr.add(o);
			}
			final JsonObject root = new JsonObject();
			root.add("legs", arr);
			Files.write(path, root.toString().getBytes(StandardCharsets.UTF_8));
		} catch (Exception ex) {
			System.out.println("[MMTR-JL] failed to save junction leg tables: " + ex.getMessage());
		}
	}

	private MmtrJunctionLegsRegistry() {
	}
}
