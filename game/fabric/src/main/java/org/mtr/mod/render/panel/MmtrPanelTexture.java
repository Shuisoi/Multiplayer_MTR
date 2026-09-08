package org.mtr.mod.render.panel;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.NativeImage;
import org.mtr.mapping.holder.NativeImageBackedTexture;
import org.mtr.mod.Init;

import java.util.Map;

/**
 * One panel texture per drawn panel, keyed by the panel's identity (vehicle instance + car + face).
 *
 * <p>This is deliberately NOT MTR's {@code DynamicTextureCache}: that cache keys textures by a string
 * built from the content, so a value that changes often (a speed readout) creates a new texture every
 * time and the old ones only expire 20 seconds later. A panel instead owns a single texture that is
 * re-uploaded in place whenever the drawn content changes, which keeps the draw to exactly one quad
 * and one render layer, and keeps the memory flat.</p>
 *
 * <p>{@link #tick()} releases textures that have not been requested for {@link #EXPIRY_MILLIS}, which
 * is what stops the cache from growing while a player walks around the world.</p>
 */
public final class MmtrPanelTexture {

	private static final long EXPIRY_MILLIS = 10_000;
	private static final Map<String, MmtrPanelTexture> CACHE = new Object2ObjectOpenHashMap<>();

	private final Identifier identifier;
	private final NativeImageBackedTexture texture;
	private String signature = "";
	private long expiryTime;

	private MmtrPanelTexture(Identifier identifier, NativeImageBackedTexture texture) {
		this.identifier = identifier;
		this.texture = texture;
	}

	/**
	 * @param key a stable identity for the panel, for example {@code vehicleId:carNumber:faceName}
	 * @return the texture slot for that panel, created empty on first use
	 */
	public static MmtrPanelTexture get(String key) {
		final MmtrPanelTexture cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		final NativeImageBackedTexture texture = new NativeImageBackedTexture(16, 16, false);
		final Identifier identifier = MinecraftClient.getInstance().getTextureManager().registerDynamicTexture("mmtr_panel_" + sanitise(key), texture);
		final MmtrPanelTexture slot = new MmtrPanelTexture(identifier, texture);
		CACHE.put(key, slot);
		return slot;
	}

	public Identifier identifier() {
		return identifier;
	}

	/**
	 * @param signature every value the panel displays, so the caller can skip the redraw when nothing changed
	 * @return true when the panel must be repainted
	 */
	public boolean needsRedraw(String signature) {
		expiryTime = System.currentTimeMillis() + EXPIRY_MILLIS;
		return !signature.equals(this.signature);
	}

	/** Repaints the texture in place. Must run on the render thread. */
	public void redraw(MmtrPanelCanvas canvas, String signature) {
		final NativeImage image = canvas.toNativeImage();
		texture.setImage(image);
		texture.upload();
		canvas.dispose();
		this.signature = signature;
		expiryTime = System.currentTimeMillis() + EXPIRY_MILLIS;
	}

	/** Releases panels that stopped being drawn. Called once per frame from the main renderer. */
	public static void tick() {
		final long now = System.currentTimeMillis();
		final ObjectArrayList<String> expired = new ObjectArrayList<>();
		CACHE.forEach((key, slot) -> {
			if (slot.expiryTime < now) {
				expired.add(key);
			}
		});
		expired.forEach(key -> {
			final MmtrPanelTexture slot = CACHE.remove(key);
			if (slot != null) {
				MinecraftClient.getInstance().getTextureManager().destroyTexture(slot.identifier);
			}
		});
	}

	/** Drops every panel texture, for a resource reload. */
	public static void clear() {
		final ObjectArrayList<String> keys = new ObjectArrayList<>(CACHE.keySet());
		keys.forEach(key -> {
			final MmtrPanelTexture slot = CACHE.remove(key);
			if (slot != null) {
				MinecraftClient.getInstance().getTextureManager().destroyTexture(slot.identifier);
			}
		});
	}

	private static String sanitise(String key) {
		final StringBuilder builder = new StringBuilder();
		for (final char character : key.toCharArray()) {
			builder.append(Character.isLetterOrDigit(character) ? Character.toLowerCase(character) : '_');
		}
		if (builder.length() == 0) {
			builder.append(Init.randomString());
		}
		return builder.toString();
	}
}
