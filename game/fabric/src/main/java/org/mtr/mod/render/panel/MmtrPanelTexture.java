package org.mtr.mod.render.panel;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.mtr.mapping.holder.AbstractTexture;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.NativeImage;
import org.mtr.mapping.holder.NativeImageBackedTexture;
import org.mtr.mapping.holder.TextureManager;
import org.mtr.mod.Init;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.MoreRenderLayers;

import javax.annotation.Nullable;
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
 * <p>Important: {@code NativeImageBackedTexture.upload()} is {@code glTexSubImage2D}, which can only
 * replace pixels of a texture that already has that exact size. Only the {@code NativeImage}
 * constructor allocates storage ({@code TextureUtil.prepareImage}). Creating the texture at a
 * placeholder size and uploading a differently sized image into it silently fails and leaves
 * uninitialised video memory, which renders as colourful noise. So the texture is created from the
 * FIRST real image and is recreated if the panel's pixel size ever changes.</p>
 *
 * <p>{@link #tick()} releases textures that have not been requested for {@link #EXPIRY_MILLIS}, which
 * is what stops the cache from growing while a player walks around the world.</p>
 */
public final class MmtrPanelTexture {

	private static final long EXPIRY_MILLIS = 10_000;
	private static final Map<String, MmtrPanelTexture> CACHE = new Object2ObjectOpenHashMap<>();
	/** B7.6e diagnostics: dump the first rasterised panel of each texture to run/mmtr-panel-debug. */
	private static final boolean DEBUG_DUMP = true;
	private static final ObjectOpenHashSet<String> DUMPED = new ObjectOpenHashSet<>();

	private final String key;
	@Nullable
	private Identifier identifier;
	@Nullable
	private NativeImageBackedTexture texture;
	private int widthPx;
	private int heightPx;
	private String signature = "";
	private long expiryTime;

	private MmtrPanelTexture(String key) {
		this.key = key;
	}

	/**
	 * @param key a stable identity for the panel, for example {@code vehicleId:carNumber:faceName}
	 * @return the texture slot for that panel; the texture itself is created by the first redraw
	 */
	public static MmtrPanelTexture get(String key) {
		final MmtrPanelTexture cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		final MmtrPanelTexture slot = new MmtrPanelTexture(key);
		CACHE.put(key, slot);
		return slot;
	}

	/** @return the texture to draw, or null before the panel has ever been painted */
	@Nullable
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

	/** Repaints the texture. Must run on the render thread. */
	public void redraw(MmtrPanelCanvas canvas, String signature) {
		final NativeImage image = canvas.toNativeImage();

		if (texture == null || widthPx != image.getWidth() || heightPx != image.getHeight()) {
			// First paint, or the panel changed size: the constructor is the only path that allocates
			// GPU storage (prepareImage) before uploading, so build a fresh texture and identifier.
			destroy();
			texture = new NativeImageBackedTexture(image);
			widthPx = image.getWidth();
			heightPx = image.getHeight();
			identifier = MinecraftClient.getInstance().getTextureManager().registerDynamicTexture("mmtr_panel_" + sanitise(key), texture);
		} else {
			texture.setImage(image);
			texture.upload();
		}

		if (DEBUG_DUMP && DUMPED.add(key)) {
			final java.io.File file = new java.io.File("mmtr-panel-debug/" + sanitise(key) + ".png");
			final boolean written = canvas.writePng(file);
			// Report what the render layer will actually bind: the texture registered under this
			// identifier and its GL id, versus the GL id of Minecraft's missing texture.
			final AbstractTexture registered = MinecraftClient.getInstance().getTextureManager().getTexture(identifier);
			Init.LOGGER.info("[MMTR-DBG] panel {} image={}x{} textureGl={} registered={} registeredGl={} missingGl={} png={} ({})",
					identifier, image.getWidth(), image.getHeight(), texture.getGlId(),
					registered == null ? "null" : registered.getClass().getSimpleName(),
					registered == null ? -1 : registered.getGlId(),
					MinecraftClient.getInstance().getTextureManager().getTexture(TextureManager.getMissingIdentifierMapped()).getGlId(),
					file.getAbsolutePath(), written ? "written" : "FAILED");
		}

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
				slot.destroy();
			}
		});
	}

	/** Drops every panel texture, for a resource reload. */
	public static void clear() {
		final ObjectArrayList<String> keys = new ObjectArrayList<>(CACHE.keySet());
		keys.forEach(key -> {
			final MmtrPanelTexture slot = CACHE.remove(key);
			if (slot != null) {
				slot.destroy();
			}
		});
		DUMPED.clear();
	}

	private void destroy() {
		if (identifier != null) {
			MainRenderer.cancelRender(identifier);
			MoreRenderLayers.removeFromCache(identifier);
			MinecraftClient.getInstance().getTextureManager().destroyTexture(identifier);
			identifier = null;
		}
		texture = null;
		widthPx = 0;
		heightPx = 0;
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
