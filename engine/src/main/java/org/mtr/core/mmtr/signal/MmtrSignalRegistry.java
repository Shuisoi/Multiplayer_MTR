package org.mtr.core.mmtr.signal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * MMTR wayside signal registry (信号机登记表): one entry per placed MTR signal-light block that
 * participates in the block/section signalling model. Entries carry the light's world position
 * and its MTR facing angle (derived from the block state - FACING/IS_22_5/IS_45, same rule the
 * renderer uses), the aspect count of the light (2/3/4), and - for covered/bound signals - the
 * rail or node/approach the light READS (覆盖式绑定: the light displays that object's signal,
 * which lets junction lights sit outside the 2-block rule / on crowded or overhanging spots).
 *
 * <p>Bound entries fully replace automatic inference; unbound (auto) entries wait for the
 * geometry/scan layer. Persisted to {@code mmtr-signals.json} in the world folder.</p>
 */
public final class MmtrSignalRegistry {

	public static final class SignalEntry {
		public int x;
		public int y;
		public int z;
		public float angle;         // MTR facing degrees (0 = east, clockwise)
		public int aspects = 2;     // 2 / 3 / 4
		/** "AUTO" = infer from placement; "BOUND" = display the bound target's signal. */
		public String mode = "AUTO";
		/** Bound read target: rail hex, or node "x,y,z|viaHex" (future approach binding). */
		public String target = "";

		public SignalEntry(int x, int y, int z, float angle, int aspects) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.angle = angle;
			this.aspects = aspects;
		}
	}

	/** Ordered by position for stable feed/display. */
	public final TreeMap<String, SignalEntry> signals = new TreeMap<>();

	public static String key(int x, int y, int z) {
		return x + "," + y + "," + z;
	}

	public SignalEntry get(int x, int y, int z) {
		return signals.get(key(x, y, z));
	}

	/** @return whether the entry changed (bind/remove semantics of an empty/absent target). */
	public boolean put(int x, int y, int z, float angle, int aspects, String mode, String target) {
		final String k = key(x, y, z);
		if (mode == null || mode.isEmpty() || mode.equals("REMOVE")) {
			return signals.remove(k) != null;
		}
		final SignalEntry entry = new SignalEntry(x, y, z, angle, aspects);
		entry.mode = mode.equals("BOUND") ? "BOUND" : "AUTO";
		entry.target = target == null ? "" : target;
		signals.put(k, entry);
		return true;
	}

	public static MmtrSignalRegistry load(Path path) {
		final MmtrSignalRegistry registry = new MmtrSignalRegistry();
		try {
			if (Files.exists(path)) {
				final JsonElement root = JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
				if (root.isJsonObject() && root.getAsJsonObject().has("signals") && root.getAsJsonObject().get("signals").isJsonArray()) {
					for (final JsonElement el : root.getAsJsonObject().getAsJsonArray("signals")) {
						final JsonObject s = el.getAsJsonObject();
						registry.put(
							s.get("x").getAsInt(), s.get("y").getAsInt(), s.get("z").getAsInt(),
							s.get("angle").getAsFloat(), s.get("aspects").getAsInt(),
							s.has("mode") ? s.get("mode").getAsString() : "AUTO",
							s.has("target") ? s.get("target").getAsString() : ""
						);
					}
				}
			}
		} catch (Exception e) {
			System.out.println("[MMTR-SIG] failed to load signal registry: " + e.getMessage());
		}
		return registry;
	}

	public static void save(Path path, TreeMap<String, SignalEntry> signals) {
		try {
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			final JsonArray arr = new JsonArray();
			for (final SignalEntry entry : signals.values()) {
				final JsonObject o = new JsonObject();
				o.addProperty("x", entry.x);
				o.addProperty("y", entry.y);
				o.addProperty("z", entry.z);
				o.addProperty("angle", entry.angle);
				o.addProperty("aspects", entry.aspects);
				o.addProperty("mode", entry.mode);
				if (!entry.target.isEmpty()) {
					o.addProperty("target", entry.target);
				}
				arr.add(o);
			}
			final JsonObject root = new JsonObject();
			root.add("signals", arr);
			Files.write(path, root.toString().getBytes(StandardCharsets.UTF_8));
		} catch (Exception ex) {
			System.out.println("[MMTR-SIG] failed to save signal registry: " + ex.getMessage());
		}
	}

	public MmtrSignalRegistry() {
	}
}
