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
	/**
	 * B7.6e diagnostics: dump the first rasterised panel of each texture to run/mmtr-panel-debug.
	 *
	 * <p><b>默认关闭</b>：它每个面板 key 首绘时同步走一次 {@code ImageIO.write}（PNG 压缩）**在渲染线程上**，
	 * 而这条路径一旦长开就是一路顿挫 —— 实测 run/mmtr-panel-debug 积到 4455 个 PNG / 40 MB
	 * （单日最多 2603 个）。排障要那份 PNG 时再临时改回 true。</p>
	 */
	private static final boolean DEBUG_DUMP = false;
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

	/**
	 * Repaints the texture. Must run on the render thread.
	 *
	 * <p><b>The texture object is STABLE and only its pixels are replaced.</b> Three approaches were
	 * tried on this, and the two failures are worth keeping because each looked right on paper:</p>
	 *
	 * <ol>
	 *   <li>{@code setImage(image); upload();} on a cached texture - the panel was painted once and then
	 *       never changed. The render layer caches the texture's GL id (see {@link #releaseTexture}), so
	 *       the layer kept binding the id the texture had when the layer was first built.</li>
	 *   <li>Destroying and re-registering the texture on every repaint - the layer then points at a
	 *       texture that no longer exists for part of every frame, which shows up in game as the
	 *       missing-texture colour FLASHING (purple/black) on the glass. It also churns GL ids at ~18 Hz
	 *       per pane.</li>
	 * </ol>
	 *
	 * <p>So neither the layer cache nor the texture identity may change. What changes is the pixel data,
	 * through the {@link NativeImage} the texture already owns: the canvas is copied into that image in
	 * place and the texture is uploaded. The GL id therefore never changes, and no layer has to be
	 * rebuilt - which is what makes the repaint reach the screen at all.</p>
	 */
	public void redraw(MmtrPanelCanvas canvas, String signature) {
		boolean uploaded = false;

		if (texture != null && widthPx == canvas.widthPx() && heightPx == canvas.heightPx()) {
			// The steady state: the texture keeps its identity and its GL id, and only its pixels change.
			// Writing straight into the image the texture already owns is what makes the repaint reach the
			// screen - no layer has to be rebuilt, because nothing the layer refers to has changed.
			final NativeImage target = texture.getImage();
			if (target != null && canvas.writeInto(target)) {
				texture.upload();
				uploaded = true;
			}
		}

		if (!uploaded) {
			// First paint, a size change, or a texture that lost its image: build a fresh one. This is the
			// path that changes the GL id, so it also has to drop the render layers cached around the old
			// one - see releaseTexture.
			releaseTexture();
			texture = new NativeImageBackedTexture(canvas.toNativeImage());
			widthPx = canvas.widthPx();
			heightPx = canvas.heightPx();
			identifier = MinecraftClient.getInstance().getTextureManager().registerDynamicTexture("mmtr_panel_" + sanitise(key), texture);
		}

		if (DEBUG_DUMP && DUMPED.add(key)) {
			final java.io.File file = new java.io.File("mmtr-panel-debug/" + sanitise(key) + ".png");
			final boolean written = canvas.writePng(file);
			// Report what the render layer will actually bind: the texture registered under this
			// identifier and its GL id, versus the GL id of Minecraft's missing texture.
			final AbstractTexture registered = MinecraftClient.getInstance().getTextureManager().getTexture(identifier);
			Init.LOGGER.info("[MMTR-DBG] panel {} image={}x{} textureGl={} registered={} registeredGl={} missingGl={} png={} ({})",
					identifier, widthPx, heightPx, texture.getGlId(),
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

	/**
	 * Frees the GL texture and the identifier that names it, and DROPS THE RENDER-LAYER CACHE for it.
	 *
	 * <p>The cache eviction is the load-bearing half, and it took three attempts to find. A
	 * {@code RenderLayer} built by {@code RenderLayer.getEntityTranslucent(texture)} binds the texture's
	 * GL id through a {@code RenderStateShard} that is RESOLVED ONCE, when the layer is built. The layer
	 * is then cached per identifier in {@link MoreRenderLayers}. So rebuilding the texture and
	 * re-registering it under the same identifier gives the queue a brand-new image while the cached
	 * layer keeps drawing the OLD GL id - the panel is repainted correctly every time
	 * ({@code rebuilds} climbs, and the offline harness proves the canvas changes frame to frame) and
	 * the glass still shows one frozen picture. Only dropping the cache entry makes the next lookup
	 * build a layer around the new texture.</p>
	 *
	 * <p>What this deliberately does NOT do is {@link #destroy()}'s {@code MainRenderer.cancelRender}:
	 * that would cancel the very draws about to use the fresh texture. Cancelling and cache-evicting are
	 * two different repairs for two different symptoms, and running them together made both look wrong.</p>
	 */
	private void releaseTexture() {
		if (identifier != null) {
			MoreRenderLayers.removeFromCache(identifier);
			MinecraftClient.getInstance().getTextureManager().destroyTexture(identifier);
			identifier = null;
		}
		texture = null;
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
