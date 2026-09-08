package org.mtr.mod.render.panel;

import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.ResourceManagerHelper;
import org.mtr.mod.Init;
import org.mtr.mod.data.IGui;

import javax.annotation.Nullable;
import java.awt.Font;
import java.awt.GraphicsEnvironment;

/**
 * The fonts the 2D cab panels are rasterised with. MTR's own signs and displays use the two fonts
 * shipped in {@code assets/mtr/font}, so the panel uses the same ones and looks like the rest of the
 * mod. They are loaded lazily and cached for the lifetime of the client.
 */
public final class MmtrPanelFont {

	private MmtrPanelFont() {
	}

	private static final String LATIN_PATH = "font/noto-sans-semibold.ttf";
	private static final String CJK_PATH = "font/noto-serif-cjk-tc-semibold.ttf";

	@Nullable
	private static Font latin;
	@Nullable
	private static Font cjk;
	private static boolean loaded;

	/**
	 * @param forCjk true when the text contains CJK characters, so the CJK font is preferred
	 * @return a base font (size 1) that can be derived to any size, never null
	 */
	public static synchronized Font get(boolean forCjk) {
		load();
		if (forCjk && cjk != null) {
			return cjk;
		}
		return latin == null ? fallback() : latin;
	}

	/** Picks the font for a string based on whether it contains CJK characters. */
	public static Font get(String text) {
		return get(text != null && IGui.isCjk(text));
	}

	/** Drops the cached fonts so a reloaded resource pack is picked up. */
	public static synchronized void reset() {
		latin = null;
		cjk = null;
		loaded = false;
	}

	private static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		latin = read(LATIN_PATH);
		cjk = read(CJK_PATH);
		if (latin == null) {
			Init.LOGGER.warn("[MMTR] could not load {}, falling back to a system font", LATIN_PATH);
		}
	}

	@Nullable
	private static Font read(String path) {
		final Font[] result = {null};
		try {
			ResourceManagerHelper.readResource(new Identifier(Init.MOD_ID, path), inputStream -> {
				try {
					result[0] = Font.createFont(Font.TRUETYPE_FONT, inputStream).deriveFont(Font.PLAIN, 1F);
				} catch (Exception e) {
					Init.LOGGER.error("[MMTR] failed to parse font {}", path, e);
				}
			});
		} catch (Exception e) {
			Init.LOGGER.error("[MMTR] failed to read font {}", path, e);
		}
		return result[0];
	}

	private static Font fallback() {
		for (final String name : new String[]{"SansSerif", "Dialog", "Arial"}) {
			final Font font = new Font(name, Font.PLAIN, 1);
			if (font.canDisplay('0') || GraphicsEnvironment.isHeadless()) {
				return font;
			}
		}
		return new Font(null, Font.PLAIN, 1);
	}
}
