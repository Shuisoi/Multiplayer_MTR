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

	public PacketMmtrRoutes(PacketBufferReceiver packetBufferReceiver) {
		super(packetBufferReceiver);
	}

	public PacketMmtrRoutes(String content) {
		super(content);
	}

	/** Build the wire form from the engine's derived views (flattened pairs; a rail may repeat). */
	public static String contentOf(Object2ObjectOpenHashMap<String, ObjectArrayList<String>> nextRails, ObjectOpenHashSet<String> pendingEntries) {
		return contentOf(nextRails, pendingEntries, new Object2ObjectOpenHashMap<>(), new ObjectOpenHashSet<>());
	}

	/**
	 * B3b: the same payload plus the block sections of every SPLIT rail, flattened as
	 * {@code [railHex, fromM, toM, color] * n}. Unsplit rails are omitted, so a world without a
	 * mid-rail light sends nothing extra.
	 *
	 * <p>④: {@code restrictedNodes} carries the {@code x,y,z} keys of junctions the engine cannot clear
	 * (undecided points or a fouled clearance zone); the client chain treats a step through them as
	 * occupied, so the lights agree with the motion rules.</p>
	 */
	public static String contentOf(Object2ObjectOpenHashMap<String, ObjectArrayList<String>> nextRails, ObjectOpenHashSet<String> pendingEntries, Object2ObjectOpenHashMap<String, ObjectArrayList<org.mtr.core.mmtr.signal.MmtrBlockService.Block>> splitRails, ObjectOpenHashSet<String> restrictedNodes) {
		return contentOf(nextRails, pendingEntries, splitRails, restrictedNodes, new Object2ObjectOpenHashMap<>());
	}

	/**
	 * S4: the same payload plus every lamp's <strong>v2 display</strong>, flattened as
	 * {@code [lampKey, aspectName] * n} where the key is the lamp's {@code x,y,z} registry key.
	 *
	 * <p>The engine hands the client its own conclusion (闭塞区间 v2 computes the aspect per LAMP - what one
	 * lamp protects, walked lamp to lamp - while the client renderer works per rail block, so it looks its
	 * own key up). That way the two sides cannot disagree, and the client needs no copy of the section walk.</p>
	 */
	public static String contentOf(Object2ObjectOpenHashMap<String, ObjectArrayList<String>> nextRails, ObjectOpenHashSet<String> pendingEntries, Object2ObjectOpenHashMap<String, ObjectArrayList<org.mtr.core.mmtr.signal.MmtrBlockService.Block>> splitRails, ObjectOpenHashSet<String> restrictedNodes, Object2ObjectOpenHashMap<String, String> lampAspects) {
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
		final JsonArray sections = new JsonArray();
		splitRails.forEach((railHex, blocks) -> blocks.forEach(block -> {
			sections.add(railHex);
			sections.add(String.valueOf(block.arcFromM));
			sections.add(String.valueOf(block.arcToM));
			sections.add(String.valueOf(block.signalColor));
		}));
		json.add("sections", sections);
		final JsonArray restricted = new JsonArray();
		restrictedNodes.forEach(restricted::add);
		json.add("restrictedNodes", restricted);
		final JsonArray lamps = new JsonArray();
		lampAspects.forEach((key, aspect) -> {
			lamps.add(key);
			lamps.add(aspect);
		});
		json.add("lamps", lamps);
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
		// B3b sections: flattened 4-tuples [railHex, fromM, toM, color].
		final ObjectArrayList<String> sectionHexes = new ObjectArrayList<>();
		jsonReader.iterateStringArray("sections", sectionHexes::clear, sectionHexes::add);
		final Map<String, java.util.List<org.mtr.mod.mmtr.MmtrSignalChain.Section>> sections = new HashMap<>();
		for (int i = 0; i + 3 < sectionHexes.size(); i += 4) {
			sections.computeIfAbsent(sectionHexes.get(i), key -> new java.util.ArrayList<>()).add(
				new org.mtr.mod.mmtr.MmtrSignalChain.Section(
					Double.parseDouble(sectionHexes.get(i + 1)),
					Double.parseDouble(sectionHexes.get(i + 2)),
					Long.parseLong(sectionHexes.get(i + 3))
				));
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
		MmtrClientRoutes.update(next, pending, sections, restrictedNodes, lampAspects);
		org.mtr.core.mmtr.MmtrTrace.log("[MMTR-CL] routes mirror: " + next.size() + " locked rail(s), " + pending.size() + " pending entry rail(s), " + sections.size() + " split rail(s), " + restrictedNodes.size() + " restricted junction(s), " + lampAspects.size() + " lamp aspect(s)");
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
