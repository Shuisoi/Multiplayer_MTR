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

	/** Build the wire form from the engine's derived views. */
	public static String contentOf(Object2ObjectOpenHashMap<String, String> nextRails, ObjectOpenHashSet<String> pendingEntries) {
		final JsonObject json = new JsonObject();
		final JsonArray next = new JsonArray();
		nextRails.forEach((from, to) -> {
			next.add(from);
			next.add(to);
		});
		final JsonArray pending = new JsonArray();
		pendingEntries.forEach(pending::add);
		json.add("nextRails", next);
		json.add("pendingEntries", pending);
		return json.toString();
	}

	@Override
	protected void runClientInbound(JsonReader jsonReader) {
		final ObjectArrayList<String> flat = new ObjectArrayList<>();
		final ObjectOpenHashSet<String> pending = new ObjectOpenHashSet<>();
		jsonReader.iterateStringArray("nextRails", flat::clear, flat::add);
		jsonReader.iterateStringArray("pendingEntries", pending::clear, pending::add);
		final Map<String, String> next = new HashMap<>();
		for (int i = 0; i + 1 < flat.size(); i += 2) {
			next.put(flat.get(i), flat.get(i + 1));
		}
		MmtrClientRoutes.update(next, pending);
		org.mtr.core.mmtr.MmtrTrace.log("[MMTR-CL] routes mirror: " + next.size() + " locked rail(s), " + pending.size() + " pending entry rail(s)");
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
