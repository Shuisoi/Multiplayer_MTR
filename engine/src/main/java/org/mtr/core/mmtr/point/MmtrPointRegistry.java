package org.mtr.core.mmtr.point;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.mtr.core.data.Data;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Automated turnout (道岔) analysis + operator 0/1 control.
 * Discovery is coordinate/approach based: a switch = (node, approach rail) with branch0 = straightest
 * continuation and branch1 = nearest diverging continuation. A deg-4 coordinate that hides two
 * running lines therefore yields two switches (one per approach), resolving the ambiguity. Branch
 * state is only ever what the operator sets (0/1), never auto.
 */
public final class MmtrPointRegistry {

	public static final class BranchStore {
		public final Map<String, Integer> branches = new HashMap<>();

		public void set(long x, long y, long z, String viaRailHex, int branch) {
			branches.put(key(x, y, z, viaRailHex), branch & 1);
		}

		public int get(long x, long y, long z, String viaRailHex) {
			return branches.getOrDefault(key(x, y, z, viaRailHex), 0);
		}

		private static String key(long x, long y, long z, String viaRailHex) {
			return x + "," + y + "," + z + "|" + viaRailHex;
		}
	}

	/** Discover every switch (node + approach rail -> straightest/diverging pair) on the rail graph. */
	public static ObjectArrayList<MmtrSwitch> discover(Data data) {
		final ObjectArrayList<MmtrSwitch> out = new ObjectArrayList<>();
		data.positionsToRail.forEach((node, neighMap) -> {
			if (neighMap.size() < 3) {
				return; // need an approach plus at least two continuations
			}
			final ObjectOpenHashSet<Rail> seenVia = new ObjectOpenHashSet<>();
			neighMap.forEach((qPos, viaRail) -> {
				if (!seenVia.add(viaRail)) {
					return;
				}
				final ObjectArrayList<Rail> forward = new ObjectArrayList<>();
				final ObjectArrayList<Position> forwardEnd = new ObjectArrayList<>();
				neighMap.forEach((rPos, rail) -> {
					if (rail != viaRail) {
						forward.add(rail);
						forwardEnd.add(rPos);
					}
				});
				if (forward.size() < 2) {
					return;
				}
				final double ax = node.getX() - qPos.getX();
				final double az = node.getZ() - qPos.getZ();
				final double[] cos = new double[forward.size()];
				for (int i = 0; i < forward.size(); i++) {
					final double bx = forwardEnd.get(i).getX() - node.getX();
					final double bz = forwardEnd.get(i).getZ() - node.getZ();
					final double la = Math.sqrt(ax * ax + az * az);
					final double lb = Math.sqrt(bx * bx + bz * bz);
					cos[i] = la == 0 || lb == 0 ? -2 : (ax * bx + az * bz) / (la * lb);
				}
				int b0 = 0;
				for (int i = 1; i < cos.length; i++) {
					if (cos[i] > cos[b0]) {
						b0 = i;
					}
				}
				int b1 = b0 == 0 ? 1 : 0;
				for (int i = 0; i < cos.length; i++) {
					if (i != b0 && cos[i] > cos[b1]) {
						b1 = i;
					}
				}
				out.add(new MmtrSwitch(node.getX(), node.getY(), node.getZ(), viaRail.getHexId(), forward.get(b0).getHexId(), forward.get(b1).getHexId()));
			});
		});
		return out;
	}

	public static BranchStore loadBranches(Path path) {
		final BranchStore store = new BranchStore();
		try {
			if (Files.exists(path)) {
				final JsonElement root = JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
				if (root.isJsonObject() && root.getAsJsonObject().has("switches") && root.getAsJsonObject().get("switches").isJsonArray()) {
					for (JsonElement el : root.getAsJsonObject().getAsJsonArray("switches")) {
						final JsonObject s = el.getAsJsonObject();
						store.set(s.get("x").getAsLong(), s.get("y").getAsLong(), s.get("z").getAsLong(),
							s.get("via").getAsString(), s.get("branch").getAsInt());
					}
				}
			}
		} catch (Exception e) {
			System.out.println("[MMTR-PT] failed to load point branches: " + e.getMessage());
		}
		return store;
	}

	public static void saveBranches(Path path, Map<String, Integer> branches) {
		try {
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			final JsonArray arr = new JsonArray();
			for (Map.Entry<String, Integer> e : branches.entrySet()) {
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
				o.addProperty("branch", e.getValue());
				arr.add(o);
			}
			final JsonObject root = new JsonObject();
			root.add("switches", arr);
			Files.write(path, root.toString().getBytes(StandardCharsets.UTF_8));
		} catch (Exception ex) {
			System.out.println("[MMTR-PT] failed to save point branches: " + ex.getMessage());
		}
	}
}
