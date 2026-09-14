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

		/**
		 * **节点级道岔位置**（一处物理道岔只有一个位置，0 = 正线贯通 / 1 = 岔股开放）。
		 *
		 * <h3>为什么必须有这一层</h3>
		 * <p>{@link #branches} 是**方向视图**（键 = 节点 + 进向轨），它回答的是"从这条进向看该走第几条腿"。
		 * 但一处物理道岔只有一个可动件：正线贯通与岔股开放**互斥**（同时开放会让列车在尖轨处脱轨，用户
		 * 2026-09-13 原话）。实测世界里三行视图各自被自动补成 0，其中"从岔股进来"那一行的 0 却表示
		 * "岔股通往正线远端" —— 与"0 = 正线贯通"直接矛盾。所以位置存在这里（按节点），
		 * {@code branches} 只是它的**派生视图**，由 {@code Simulator} 统一写。</p>
		 */
		public final Map<String, Integer> nodePositions = new HashMap<>();

		/** 存原始的操作员腿号（0..legs-1，两腿岔口就是 0/1）；负数是"取消设置"。 */
		public void set(long x, long y, long z, String viaRailHex, int branch) {
			if (branch < 0) {
				branches.remove(key(x, y, z, viaRailHex));
			} else {
				branches.put(key(x, y, z, viaRailHex), branch);
			}
		}

		/** 由节点位置派生出来的行（不参与"有没有人工设置"的判断，见 {@link #contains}）。 */
		void putDerived(long x, long y, long z, String viaRailHex, int branch) {
			if (branch < 0) {
				branches.remove(key(x, y, z, viaRailHex));
			} else {
				branches.put(key(x, y, z, viaRailHex), branch);
			}
		}

		/** True when an operator explicitly set this turnout branch (distinguishes "0" from unset). */
		public boolean contains(long x, long y, long z, String viaRailHex) {
			return branches.containsKey(key(x, y, z, viaRailHex));
		}

		public int get(long x, long y, long z, String viaRailHex) {
			return branches.getOrDefault(key(x, y, z, viaRailHex), 0);
		}

		// ---------------------------------------------------------------- 节点级位置

		public boolean containsNode(long x, long y, long z) {
			return nodePositions.containsKey(x + "," + y + "," + z);
		}

		public void setNode(long x, long y, long z, int position) {
			nodePositions.put(x + "," + y + "," + z, position);
		}

		/** 节点位置；没设过按 0（正线贯通 = 安全侧）。 */
		public int nodePosition(long x, long y, long z) {
			return nodePositions.getOrDefault(x + "," + y + "," + z, 0);
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
				// 节点级位置（一处道岔一个位置）：新格式才有这一节；老存档没有就留空，由 Simulator
				// 从行视图反推（见 refreshMmtrTurnouts），这样"人工扳到 1"不会因为升级而丢失。
				if (root.isJsonObject() && root.getAsJsonObject().has("positions") && root.getAsJsonObject().get("positions").isJsonArray()) {
					for (final JsonElement el : root.getAsJsonObject().getAsJsonArray("positions")) {
						final JsonObject o = el.getAsJsonObject();
						store.setNode(o.get("x").getAsLong(), o.get("y").getAsLong(), o.get("z").getAsLong(), o.get("position").getAsInt());
					}
				}
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
		saveBranches(path, branches, Map.of());
	}

	/** 落盘：行视图 + **节点级位置**（位置是权威、行视图是派生；两者都写便于人工核对与排错）。 */
	public static void saveBranches(Path path, Map<String, Integer> branches, Map<String, Integer> nodePositions) {
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
			final JsonArray positions = new JsonArray();
			for (Map.Entry<String, Integer> e : nodePositions.entrySet()) {
				final String[] c = e.getKey().split(",");
				if (c.length != 3) {
					continue;
				}
				final JsonObject o = new JsonObject();
				o.addProperty("x", Long.parseLong(c[0]));
				o.addProperty("y", Long.parseLong(c[1]));
				o.addProperty("z", Long.parseLong(c[2]));
				o.addProperty("position", e.getValue());
				positions.add(o);
			}
			final JsonObject root = new JsonObject();
			root.add("positions", positions);
			root.add("switches", arr);
			Files.write(path, root.toString().getBytes(StandardCharsets.UTF_8));
		} catch (Exception ex) {
			System.out.println("[MMTR-PT] failed to save point branches: " + ex.getMessage());
		}
	}
}
