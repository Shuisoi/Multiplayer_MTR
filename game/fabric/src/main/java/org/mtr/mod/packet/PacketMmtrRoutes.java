package org.mtr.mod.packet;

import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mod.client.MmtrClientRoutes;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;

/**
 * MMTR server -&gt; client route mirror (A2). The engine owns the interlocking, so it also owns the
 * display rule: the server sends the SET main route's locked path (rail -&gt; next rail, flattened
 * pairs) and the entry rails of routes still PENDING, and the client renderer applies them to the
 * signal heads. Sent on change only (see {@code MmtrRouteMirror}), so an idle world sends nothing.
 */
public final class PacketMmtrRoutes extends PacketRequestResponseBase {

	/** 区间叠加层载荷（{@code sectionBands}）每条带的字段数：轨hex / 区间id / 色号 / 方向x‰ / 方向z‰ / 弧起cm / 弧止cm。 */
	public static final int SECTION_BAND_STRIDE = 7;

	public PacketMmtrRoutes(PacketBufferReceiver packetBufferReceiver) {
		super(packetBufferReceiver);
	}

	public PacketMmtrRoutes(String content) {
		super(content);
	}

	/** Build the wire form from the engine's derived views (flattened pairs; a rail may repeat). */
	public static String contentOf(Object2ObjectOpenHashMap<String, ObjectArrayList<String>> nextRails, ObjectOpenHashSet<String> pendingEntries) {
		return contentOf(nextRails, pendingEntries, new ObjectOpenHashSet<>(), new Object2ObjectOpenHashMap<String, String>());
	}

	/**
	 * 同一份载荷 + ④ 的"清不掉的岔口"节点键（{@code restrictedNodes}）。
	 *
	 * <p>B3b 那条"每根被切分轨的弧窗 + 预留色"的 `sections` 载荷**已删除**（notes/166 R4）：
	 * 它随 v1 的预留信号色通道一起消失 —— 客户端不再自己数区间，区间与显示的结论都由引擎给
	 * （{@code lamps} / {@code lampRails}）。</p>
	 *
	 * <p>④: {@code restrictedNodes} carries the {@code x,y,z} keys of junctions the engine cannot clear
	 * (undecided points or a fouled clearance zone); the client chain treats a step through them as
	 * occupied, so the lights agree with the motion rules.</p>
	 */
	public static String contentOf(Object2ObjectOpenHashMap<String, ObjectArrayList<String>> nextRails, ObjectOpenHashSet<String> pendingEntries, ObjectOpenHashSet<String> restrictedNodes) {
		return contentOf(nextRails, pendingEntries, restrictedNodes, new Object2ObjectOpenHashMap<String, String>());
	}

	/**
	 * S4: the same payload plus every lamp's <strong>v2 display</strong> and its <strong>守轨</strong>,
	 * flattened as {@code [lampKey, aspectName] * n} and {@code [lampKey, railHex] * n}.
	 *
	 * <p>The engine hands the client its own conclusion (闭塞区间 v2 computes the aspect per LAMP - what one
	 * lamp protects, walked lamp to lamp - while the client renderer works per rail block, so it looks its
	 * own key up). That way the two sides cannot disagree, and the client needs no copy of the section walk.</p>
	 *
	 * <p><b>守轨（{@code lampRails}）是给"绑定工具"的叠加层用的</b>：拿着绑定工具时要在世界里画出
	 * "这盏灯守哪几根轨"。这份关系由**引擎**算（它才持有节点、朝向、人工绑定、区间那一整套），
	 * 客户端只显示 —— 客户端自己按几何推一遍必然与引擎分叉，而"灯到底守哪根轨"正是要看的那个东西。</p>
	 */
	public static String contentOf(Object2ObjectOpenHashMap<String, ObjectArrayList<String>> nextRails, ObjectOpenHashSet<String> pendingEntries, ObjectOpenHashSet<String> restrictedNodes, Object2ObjectOpenHashMap<String, String> lampAspects) {
		return contentOf(nextRails, pendingEntries, restrictedNodes, lampAspects, new Object2ObjectOpenHashMap<>());
	}

	/** As above, carrying each lamp's guarded rails ({@code [lampKey, railHex] * n}). */
	public static String contentOf(Object2ObjectOpenHashMap<String, ObjectArrayList<String>> nextRails, ObjectOpenHashSet<String> pendingEntries, ObjectOpenHashSet<String> restrictedNodes, Object2ObjectOpenHashMap<String, String> lampAspects, Object2ObjectOpenHashMap<String, ObjectArrayList<String>> lampRails) {
		return contentOf(nextRails, pendingEntries, restrictedNodes, lampAspects, lampRails, new ObjectArrayList<>());
	}

	/**
	 * As above, plus the **区间叠加层**的载荷 {@code sectionBands}（notes/291）。
	 *
	 * <p>扁平步长 7：{@code [轨hex, 区间id, 色号, 方向x‰, 方向z‰, 弧起cm, 弧止cm]}。拿着信号灯建轨时，
	 * 客户端按这份数据把"哪一段是哪个区间、往哪个方向走"画到轨面上 —— 颜色由**引擎**贪心分配
	 * （相连的两段异色），客户端只显示。</p>
	 *
	 * <p>数值一律走整数（千分位方向 / 厘米弧长）：区域设置若用逗号作小数点，{@code Double.parseDouble}
	 * 会静默失败，而弧长算错的表现是"带子画到别的轨上"，那种错排查起来极贵。</p>
	 */
	public static String contentOf(Object2ObjectOpenHashMap<String, ObjectArrayList<String>> nextRails, ObjectOpenHashSet<String> pendingEntries, ObjectOpenHashSet<String> restrictedNodes, Object2ObjectOpenHashMap<String, String> lampAspects, Object2ObjectOpenHashMap<String, ObjectArrayList<String>> lampRails, ObjectArrayList<String> sectionBands) {
		final JsonObject json = new JsonObject();
		final JsonArray next = new JsonArray();
		nextRails.forEach((from, tos) -> tos.forEach(to -> {
			next.add(from);
			next.add(to);
		}));
		final JsonArray pending = new JsonArray();
		pendingEntries.forEach(pending::add);
		json.add("nextRails", next);
		json.add("pendingEntries", pending);
		final JsonArray restricted = new JsonArray();
		restrictedNodes.forEach(restricted::add);
		json.add("restrictedNodes", restricted);
		final JsonArray lamps = new JsonArray();
		lampAspects.forEach((key, aspect) -> {
			lamps.add(key);
			lamps.add(aspect);
		});
		json.add("lamps", lamps);
		final JsonArray lampRailsFlat = new JsonArray();
		lampRails.forEach((key, railHexes) -> railHexes.forEach(railHex -> {
			lampRailsFlat.add(key);
			lampRailsFlat.add(railHex);
		}));
		json.add("lampRails", lampRailsFlat);
		final JsonArray sectionBandsFlat = new JsonArray();
		sectionBands.forEach(sectionBandsFlat::add);
		json.add("sectionBands", sectionBandsFlat);
		return json.toString();
	}

	@Override
	protected void runClientInbound(JsonReader jsonReader) {
		final ObjectArrayList<String> flat = new ObjectArrayList<>();
		final ObjectOpenHashSet<String> pending = new ObjectOpenHashSet<>();
		jsonReader.iterateStringArray("nextRails", flat::clear, flat::add);
		jsonReader.iterateStringArray("pendingEntries", pending::clear, pending::add);
		final Map<String, java.util.List<String>> next = new HashMap<>();
		for (int i = 0; i + 1 < flat.size(); i += 2) {
			next.computeIfAbsent(flat.get(i), key -> new java.util.ArrayList<>()).add(flat.get(i + 1));
		}
		// ④: the junctions the engine cannot clear, as x,y,z keys.
		final ObjectOpenHashSet<String> restrictedNodes = new ObjectOpenHashSet<>();
		jsonReader.iterateStringArray("restrictedNodes", restrictedNodes::clear, restrictedNodes::add);
		// S4: every lamp's v2 display, as [lampKey, aspectName] pairs.
		final ObjectArrayList<String> lampEntries = new ObjectArrayList<>();
		jsonReader.iterateStringArray("lamps", lampEntries::clear, lampEntries::add);
		final Map<String, String> lampAspects = new HashMap<>();
		for (int i = 0; i + 1 < lampEntries.size(); i += 2) {
			lampAspects.put(lampEntries.get(i), lampEntries.get(i + 1));
		}
		// 守轨（绑定工具叠加层用）: [lampKey, railHex] pairs.
		final ObjectArrayList<String> lampRailEntries = new ObjectArrayList<>();
		jsonReader.iterateStringArray("lampRails", lampRailEntries::clear, lampRailEntries::add);
		final Map<String, java.util.List<String>> lampRails = new HashMap<>();
		for (int i = 0; i + 1 < lampRailEntries.size(); i += 2) {
			lampRails.computeIfAbsent(lampRailEntries.get(i), key -> new java.util.ArrayList<>()).add(lampRailEntries.get(i + 1));
		}
		// 区间叠加层（拿信号灯时画带）: 扁平步长 7，见 contentOf 的说明。旧服务端不发这个字段 -> 空表。
		final ObjectArrayList<String> sectionBands = new ObjectArrayList<>();
		jsonReader.iterateStringArray("sectionBands", sectionBands::clear, sectionBands::add);
		MmtrClientRoutes.update(next, pending, restrictedNodes, lampAspects, lampRails, sectionBands);
		org.mtr.core.mmtr.MmtrTrace.log("[MMTR-CL] routes mirror: " + next.size() + " locked rail(s), " + pending.size() + " pending entry rail(s), " + restrictedNodes.size() + " restricted junction(s), " + lampAspects.size() + " lamp aspect(s), " + lampRails.size() + " lamp binding(s), " + (sectionBands.size() / SECTION_BAND_STRIDE) + " section band(s)");
	}

	@Override
	protected PacketRequestResponseBase getInstance(String content) {
		return new PacketMmtrRoutes(content);
	}

	@Override
	protected SerializedDataBase getDataInstance(JsonReader jsonReader) {
		return new SerializedDataBase() {
			@Override
			public void updateData(ReaderBase readerBase) {
			}

			@Override
			public void serializeData(WriterBase writerBase) {
			}
		};
	}

	@Nonnull
	@Override
	protected String getKey() {
		return OperationProcessor.UPDATE_DATA;
	}

	@Override
	protected PacketRequestResponseBase.ResponseType responseType() {
		return PacketRequestResponseBase.ResponseType.NONE;
	}
}
