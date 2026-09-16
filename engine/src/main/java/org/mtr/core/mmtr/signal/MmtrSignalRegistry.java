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
		/**
		 * 显式绑定（点选绑定）：这盏灯守的一条或多条轨（hex 列表）。
		 *
		 * <h3>为什么单开一个字段，不继续塞进 target</h3>
		 * <p>{@code target} 里已经有一层含义了（{@code x,y,z|hex} 表示"绑到某个节点的某根轨"），
		 * 再往里塞多条轨会与 {@code |} 的语义打架 —— 读取方只能靠"有没有 {@code |}"去分辨两种东西，
		 * 那是给自己埋坑（现有代码里就有一处 {@code !target.contains("|")} 这种判断）。</p>
		 *
		 * <p>这里单开一个列表，判断只有一条：**非空 = 人工指定，空 = 仍按几何推断**。
		 * 非空时这盏灯只守列出的轨（不再推断），一条轨对应一段区间，显示取**最不利**的那条 ——
		 * 这正是原版 MTR"一条轨落在我面朝方向的 90° 扇区里我就守它、可以一灯守多腿"的口径。</p>
		 */
		public final java.util.List<String> rails = new java.util.ArrayList<>();

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

	/**
	 * 删掉某一条登记（世界里的灯被拆掉时用）。
	 *
	 * <h3>为什么必须能删</h3>
	 * <p>世界扫描（{@code signals scan}）原来是**只加不删**的：把灯敲掉之后，登记表里那一条还在，
	 * 于是地图上永远画着一盏**已经不存在的灯**（用户实测就是这个："有几个信号灯我已经敲掉了"，
	 * 地图上仍然有）。扫描是唯一能看到世界里有没有那盏灯的地方（只有游戏端能枚举已加载区块），
	 * 所以删除也必须在扫描里做，登记表就得提供一个删除入口。</p>
	 *
	 * @return 是否真的删掉了一条
	 */
	public boolean remove(int x, int y, int z) {
		return signals.remove(key(x, y, z)) != null;
	}

	/**
	 * 在 {@code (x, y, z)} 找信号灯：先看该格，再看它**下面一格**。
	 *
	 * <h3>为什么不能只按精确格找</h3>
	 * <p>一盏 MTR 信号灯方块是**两格高**的：登记时（世界扫描 / 绑定工具）记下的是玩家放的那个方块，
	 * 而轨道按走行方向走出来的节点在下面那一格。于是"这一节点上有没有灯"这个问题，
	 * 用节点的 y 去精确查表会因为差一格而答"没有"——实测世界里同一盏灯同时登记在
	 * {@code (-69,-59,-203)} 与 {@code (-69,-60,-203)}，就是这两格。</p>
	 *
	 * <p>答错的后果不是少显示一盏灯，而是**区间走不到头**：走行在节点处没有看到灯，就继续往下走，
	 * 把本该属于下一架灯的区间吞进来，于是那架灯的前方永远"没有占用的下一个区间"，
	 * 一直显示绿灯（实测：{@code -38,-60,-235} 该双黄却显示绿）。</p>
	 *
	 * <p>只往下找一格，不往上找：违反这个方向的用法（把灯记在轨道上方）在几何上说不通，
	 * 而放宽到"y 附近"会让上下层立体交叉的线路互相认领对方的灯。</p>
	 */
	public @org.jspecify.annotations.Nullable SignalEntry getNear(int x, int y, int z) {
		final SignalEntry exact = signals.get(key(x, y, z));
		return exact != null ? exact : signals.get(key(x, y - 1, z));
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

	/**
	 * 改一盏灯的显式绑定（点选绑定用）：把它的守轨列表设成 {@code rails}。
	 *
	 * <p>整体替换而不是增删单条：界面上"点一根轨"的语义是"现在它守这几根"，
	 * 多腿就是把另一根也点亮。调用方（网页）持有完整列表，整体替换没有并发歧义，
	 * 也不会出现"点同一根两次变成重复项"这种脏数据。</p>
	 *
	 * @return 是否找到并改了这盏灯
	 */
	public boolean setBoundRails(int x, int y, int z, java.util.List<String> rails) {
		final SignalEntry entry = signals.get(key(x, y, z));
		if (entry == null) {
			return false;
		}
		entry.rails.clear();
		if (rails != null) {
			/*
			 * 按**规范 hex** 去重（见 MmtrSectionService.canonicalHex）。
			 *
			 * <p>同一根轨的两种端点写法是不同的字符串，用原始字符串判断"收过没有"会漏：
			 * 点选绑定传进来的可能是逆序写法，而列表里已经存着正序的那一份 —— 于是同一根轨被记两次，
			 * "解除绑定"只去掉一条、另一条还在（实测：同一条轨越点越多）。
			 * 存进去时保留调用方给的写法（便于对应），但重复项按规范形式判掉。</p>
			 */
			final java.util.Set<String> seen = new java.util.HashSet<>();
			for (final String hex : rails) {
				if (hex == null || hex.isEmpty()) {
					continue;
				}
				if (seen.add(org.mtr.core.mmtr.signal.MmtrSectionService.canonicalHex(hex))) {
					entry.rails.add(hex);
				}
			}
		}
		// 有显式绑定时把旧的单目标清掉：两套来源同时生效只会让"它到底守哪根"变得不可知
		if (!entry.rails.isEmpty()) {
			entry.target = "";
			entry.mode = "BOUND";
		} else {
			entry.mode = "AUTO";
		}
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
						// 显式绑定（点选绑定）单独存：旧存档没有这个字段，读不到就是"没有人工绑定"
						if (s.has("rails") && s.get("rails").isJsonArray()) {
							final SignalEntry entry = registry.get(s.get("x").getAsInt(), s.get("y").getAsInt(), s.get("z").getAsInt());
							if (entry != null) {
								for (final JsonElement hex : s.getAsJsonArray("rails")) {
									entry.rails.add(hex.getAsString());
								}
							}
						}
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
				if (!entry.rails.isEmpty()) {
					final JsonArray rails = new JsonArray();
					entry.rails.forEach(rails::add);
					o.add("rails", rails);
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
