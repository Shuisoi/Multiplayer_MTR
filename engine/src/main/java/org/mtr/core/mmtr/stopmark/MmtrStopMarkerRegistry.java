package org.mtr.core.mmtr.stopmark;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.simulation.Simulator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 停车对标牌子登记表（设计《停车对标-牌子方块与站台停车基准》§4.2 / §5.1，方案 B）。
 *
 * <h3>为什么是独立登记表，而不是塞进 {@code Platform}</h3>
 * <p>牌子是**自由摆放的方块**（用户口径：立在任意位置），而且拆装频繁（换位置、换辆数、批量重铺）。
 * 塞进站台数据里的话，"这块牌子还在不在"就得跟着站台方块的状态走 —— 拆一块牌子要脏回站台对象，
 * 而站台是"画在轨上的对象"，与牌子没有生命周期关系（§5.1 的取舍表）。独立登记表的生命周期与
 * 信号灯登记表同构：放置/扫描时写、拆掉时删、落盘一份 JSON，只读出口照抄现成套路。</p>
 *
 * <h3>一条记录 = 一个（辆数 + 方向）</h3>
 * <p>牌子上只写辆数，**方向由牌子位置反推**（驾驶室在列车前端、牌子立在车头后方 {@code datumM} 处，
 * 所以"牌子偏哪一头"就是"车从哪一头来"，见 {@link MmtrStopMarkerGeometry#deriveTowardEnd0}）。
 * 于是查询就是 (站台, 辆数, 方向) 三键命中 —— 这也正是自动车要用到的三个量（§5.2）。</p>
 *
 * <h3>冲突为什么不落盘</h3>
 * <p>同一三键出现多块牌子时只有"离站台中点最近"的那块算数（§4.3）。这个结论依赖**站台中点**
 * 与**同一组里其它牌子**，是记录之间的关系而不是记录自己的属性：存下来就得在每次登记/删除/站台
 * 改动时重算重写，而它只在读的时候有意义（只读出口 + {@code stop-marker list}）。
 * 所以 {@link #conflictFlags(Simulator)} 现算 —— 也顺带保证"站台被挪走之后，昨天的冲突结论不会
 * 被当成今天的事实"。</p>
 */
public final class MmtrStopMarkerRegistry {

	/**
	 * 一块牌子：牌子方块的位置 + 它声明的辆数 + 反解出来的归属/方向/车头停点。
	 *
	 * <p>字段与游戏端 block entity 一一对应（{@code BlockStopMarker.BlockEntity} 的 cars / platformId /
	 * label / datumM 是**人写**的，其余是引擎**算**出来的），所以游戏端扫描只需上行四个值。</p>
	 */
	public static final class StopMarkerEntry {
		public int x, y, z;            // 牌子方块的世界坐标
		public int cars;               // 编组辆数 2..10
		public boolean towardEnd0;     // 行车方向：true = 列车朝站台轨 mmtrOrderedPositions()[0] 那一端开
		public long platformId;
		public String platformHex = "";
		public String railHex = "";
		public double fraction;        // **车头端面**在这根轨上的比例 0..1
		public double datumM = 1.25;   // 参照点相对车头的偏移（默认驾驶室侧窗 1.25 m）
		public String label = "";      // "" = 默认按辆数显示
		public double headX, headY, headZ;   // 车头端面世界坐标（诊断/只读出口）
	}

	/** Ordered by position for stable feed/display. Key = "x,y,z". */
	public final TreeMap<String, StopMarkerEntry> markers = new TreeMap<>();

	public static String key(int x, int y, int z) {
		return x + "," + y + "," + z;
	}

	public StopMarkerEntry get(int x, int y, int z) {
		return markers.get(key(x, y, z));
	}

	/**
	 * 删掉某一条登记。
	 *
	 * <p>与信号灯同一个理由：世界扫描是唯一能看到"那一格还有没有牌子"的地方（只有游戏端能枚举已加载
	 * 区块），所以删除也必须由扫描驱动 —— 登记表就得提供一个删除入口，否则拆掉的牌子会永远显示在
	 * 只读出口上（信号灯那边实测过这个症状）。</p>
	 *
	 * @return 是否真的删掉了一条
	 */
	public boolean remove(int x, int y, int z) {
		return markers.remove(key(x, y, z)) != null;
	}

	/**
	 * 查停车基准：命中返回该条，否则 null。
	 *
	 * <p>同一 (站台, 辆数, 方向) 有多条牌子（冲突）时，登记表**没有站台几何**，判断不出"哪条离站台中点
	 * 最近" —— 这时按 {@link TreeMap} 顺序返回第一条，调用方若要按 §4.3 取最近，请改用
	 * {@link #find(long, int, boolean, double, double)}（只读出口与 {@code stop-marker list} 都走那条）。</p>
	 */
	public @org.jspecify.annotations.Nullable StopMarkerEntry find(long platformId, int cars, boolean towardEnd0) {
		return find(platformId, cars, towardEnd0, Double.NaN, Double.NaN);
	}

	/**
	 * 查停车基准（带站台中点）：冲突时取离站台中点最近的那一条（§4.3）。
	 *
	 * <h3>为什么中点要由调用方给</h3>
	 * <p>站台中点在 {@code Platform} 上，不在登记表里。让登记表反查站台就得把 {@link Simulator} 拖进
	 * 这一层，而"取最近"这条规则本身只是两个水平坐标的比较 —— 抽成参数之后，
	 * {@link #nearestToPlatformMid} 可以脱离世界单测，登记表也能在游戏端同一个进程里被安全地问。</p>
	 *
	 * <p>中点传 {@code NaN}（不知道）= 退回第一条，而不是"距离无穷远、整组丢掉"。</p>
	 */
	public @org.jspecify.annotations.Nullable StopMarkerEntry find(long platformId, int cars, boolean towardEnd0, double midX, double midZ) {
		final List<StopMarkerEntry> matches = new ArrayList<>();
		for (final StopMarkerEntry entry : markers.values()) {
			if (entry.platformId == platformId && entry.cars == cars && entry.towardEnd0 == towardEnd0) {
				matches.add(entry);
			}
		}
		if (matches.isEmpty()) {
			return null;
		}
		return matches.size() == 1 ? matches.get(0) : nearestToPlatformMid(matches, midX, midZ);
	}

	/** 某条站台上的全部牌子（诊断/只读出口的"这块站台有几块牌"用）。 */
	public java.util.List<StopMarkerEntry> byPlatform(long platformId) {
		final java.util.List<StopMarkerEntry> out = new java.util.ArrayList<>();
		for (final StopMarkerEntry entry : markers.values()) {
			if (entry.platformId == platformId) {
				out.add(entry);
			}
		}
		return out;
	}

	/**
	 * 写入/刷新一条，返回**内容是否真的变了**。
	 *
	 * <h3>为什么要"变了才算改"</h3>
	 * <p>牌子的核对是**常态轮转 + 自动刷新**（游戏端 {@code MmtrStopMarkerSync} 每 tick 分批扫，放置/改
	 * 辆数还立刻扫一次）：每次都当成本次改动去落盘，会把 {@code mmtr-stop-markers.json} 变成热文件，
	 * 而内容其实一个字都没变。比较的是**全部字段** —— fraction 与头点也要比，因为玩家把牌子挪一格时
	 * 只有它们会变（辆数/归属都没动）。</p>
	 *
	 * @return true = 新增，或内容有变化（调用方据此落盘）
	 */
	public boolean upsert(StopMarkerEntry entry) {
		final String k = key(entry.x, entry.y, entry.z);
		final StopMarkerEntry existing = markers.get(k);
		if (existing != null && sameContent(existing, entry)) {
			return false;
		}
		markers.put(k, entry);
		return true;
	}

	/**
	 * 同一 (站台, 辆数, 方向) 的多条牌里，取**头点离站台中点最近**的那条（§4.3"取最近"的落点）。
	 *
	 * <h3>为什么比头点、不比牌子方块</h3>
	 * <p>这一组牌子是同键的（同站台、同辆数、同方向），真正的差别只有"车按它停在哪儿"；
	 * 头点是车头端面要停的位置，离站台中心越近越可能是玩家想用的那一块。用牌子方块本身去比也可以，
	 * 但那还夹着 datumM 那一小段偏移，口径不如头点直接。</p>
	 *
	 * <p>纯坐标函数（可脱离 Simulator 单测）。中点传 {@code NaN} 时返回第一条：
	 * {@code NaN} 的任何比较都是 false，于是"第一条"自然胜出。</p>
	 *
	 * @param candidates 同一 (站台, 辆数, 方向) 的候选；为空返回 null
	 */
	public static @Nullable StopMarkerEntry nearestToPlatformMid(java.util.List<StopMarkerEntry> candidates, double midX, double midZ) {
		StopMarkerEntry best = null;
		double bestDistance = 0;
		for (final StopMarkerEntry candidate : candidates) {
			final double distance = Math.hypot(candidate.headX - midX, candidate.headZ - midZ);
			if (best == null || distance < bestDistance) {
				best = candidate;
				bestDistance = distance;
			}
		}
		return best;
	}

	/**
	 * 现算冲突标记：同一 (站台, 辆数, 方向) 有多条时，只有离站台中点最近的那条不冲突（§4.3）。
	 *
	 * <p>站台已经不存在时（牌子残留、站台被删）中点按 {@code NaN} 处理：该组退回"第一条为基准"，
	 * 其余的仍然标冲突 —— 不因为站台没了就静默地让多块牌子同时生效。</p>
	 *
	 * @return 牌子键 → 是否冲突；登记表里每一条都有值
	 */
	public static java.util.TreeMap<String, Boolean> conflictFlags(Simulator simulator) {
		final java.util.TreeMap<String, List<StopMarkerEntry>> groups = new java.util.TreeMap<>();
		simulator.mmtrStopMarkers.markers.forEach((k, entry) -> groups.computeIfAbsent(groupKey(entry), ignored -> new ArrayList<>()).add(entry));
		final java.util.TreeMap<String, Boolean> flags = new java.util.TreeMap<>();
		groups.forEach((groupKey, group) -> {
			final double[] mid = platformMid(simulator, group.get(0).platformId);
			final StopMarkerEntry datum = group.size() == 1 ? group.get(0) : nearestToPlatformMid(group, mid[0], mid[1]);
			for (final StopMarkerEntry entry : group) {
				flags.put(key(entry.x, entry.y, entry.z), entry != datum);
			}
		});
		return flags;
	}

	public static MmtrStopMarkerRegistry load(java.nio.file.Path path) {
		final MmtrStopMarkerRegistry registry = new MmtrStopMarkerRegistry();
		try {
			if (Files.exists(path)) {
				final JsonElement root = JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
				if (root.isJsonObject() && root.getAsJsonObject().has("markers") && root.getAsJsonObject().get("markers").isJsonArray()) {
					for (final JsonElement el : root.getAsJsonObject().getAsJsonArray("markers")) {
						final JsonObject o = el.getAsJsonObject();
						final StopMarkerEntry entry = new StopMarkerEntry();
						entry.x = o.get("x").getAsInt();
						entry.y = o.get("y").getAsInt();
						entry.z = o.get("z").getAsInt();
						// 其余字段一律**可缺省**：老文件（或半截写入）不该让整张表读不出来
						entry.cars = o.has("cars") ? o.get("cars").getAsInt() : 0;
						entry.towardEnd0 = o.has("towardEnd0") && o.get("towardEnd0").getAsBoolean();
						entry.platformId = o.has("platformId") ? o.get("platformId").getAsLong() : 0;
						entry.platformHex = o.has("platformHex") ? o.get("platformHex").getAsString() : "";
						entry.railHex = o.has("railHex") ? o.get("railHex").getAsString() : "";
						entry.fraction = o.has("fraction") ? o.get("fraction").getAsDouble() : 0;
						entry.datumM = o.has("datumM") ? o.get("datumM").getAsDouble() : 1.25;
						entry.label = o.has("label") ? o.get("label").getAsString() : "";
						entry.headX = o.has("headX") ? o.get("headX").getAsDouble() : 0;
						entry.headY = o.has("headY") ? o.get("headY").getAsDouble() : 0;
						entry.headZ = o.has("headZ") ? o.get("headZ").getAsDouble() : 0;
						registry.markers.put(key(entry.x, entry.y, entry.z), entry);
					}
				}
			}
		} catch (Exception e) {
			System.out.println("[MMTR-STOP] failed to load stop-marker registry: " + e.getMessage());
		}
		return registry;
	}

	public static void save(java.nio.file.Path path, TreeMap<String, StopMarkerEntry> markers) {
		try {
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			final JsonArray arr = new JsonArray();
			for (final StopMarkerEntry entry : markers.values()) {
				final JsonObject o = new JsonObject();
				o.addProperty("x", entry.x);
				o.addProperty("y", entry.y);
				o.addProperty("z", entry.z);
				o.addProperty("cars", entry.cars);
				o.addProperty("towardEnd0", entry.towardEnd0);
				o.addProperty("platformId", entry.platformId);
				o.addProperty("platformHex", entry.platformHex);
				o.addProperty("railHex", entry.railHex);
				o.addProperty("fraction", entry.fraction);
				o.addProperty("datumM", entry.datumM);
				// 头点是**算出来的**（牌子的位置一变就变），但存一份可以让"重启后还能对账"
				// 不必先把整张轨图读起来 —— 它只是诊断/只读出口用的值，不参与停车判定。
				o.addProperty("headX", entry.headX);
				o.addProperty("headY", entry.headY);
				o.addProperty("headZ", entry.headZ);
				if (!entry.label.isEmpty()) {
					o.addProperty("label", entry.label);
				}
				arr.add(o);
			}
			final JsonObject root = new JsonObject();
			root.add("markers", arr);
			Files.write(path, root.toString().getBytes(StandardCharsets.UTF_8));
		} catch (Exception ex) {
			System.out.println("[MMTR-STOP] failed to save stop-marker registry: " + ex.getMessage());
		}
	}

	public MmtrStopMarkerRegistry() {
	}

	/** 分组键：同一 (站台, 辆数, 方向) 的牌子互为冲突候选。 */
	private static String groupKey(StopMarkerEntry entry) {
		return entry.platformId + "|" + entry.cars + "|" + entry.towardEnd0;
	}

	/** 站台中点的水平坐标；站台不存在时 {NaN, NaN}（见 {@link #nearestToPlatformMid} 的 NaN 口径）。 */
	private static double[] platformMid(Simulator simulator, long platformId) {
		for (final Platform platform : simulator.platforms) {
			if (platform.getId() == platformId) {
				final Position mid = platform.getMidPosition();
				return new double[]{mid.getX(), mid.getZ()};
			}
		}
		return new double[]{Double.NaN, Double.NaN};
	}

	/** 逐字段比较（double 用 1e-9 容差：fraction 是除出来的，不该因为最后一位被当成"变了"）。 */
	private static boolean sameContent(StopMarkerEntry a, StopMarkerEntry b) {
		return a.x == b.x && a.y == b.y && a.z == b.z
			&& a.cars == b.cars && a.towardEnd0 == b.towardEnd0 && a.platformId == b.platformId
			&& a.platformHex.equals(b.platformHex) && a.railHex.equals(b.railHex)
			&& Math.abs(a.fraction - b.fraction) < 1e-9 && Math.abs(a.datumM - b.datumM) < 1e-9
			&& a.label.equals(b.label)
			&& Math.abs(a.headX - b.headX) < 1e-9 && Math.abs(a.headY - b.headY) < 1e-9 && Math.abs(a.headZ - b.headZ) < 1e-9;
	}
}
