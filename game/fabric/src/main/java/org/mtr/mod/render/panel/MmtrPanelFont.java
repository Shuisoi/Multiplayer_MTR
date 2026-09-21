package org.mtr.mod.render.panel;

import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.ResourceManagerHelper;
import org.mtr.mod.Init;
import org.mtr.mod.data.IGui;

import javax.annotation.Nullable;
import java.awt.Font;
import java.awt.GraphicsEnvironment;

/**
 * 驾驶台 2D 面板（{@code mmtr_hud_*}）栅格化时用的字体（notes/221）：
 * **西文与数字 = Alte DIN 1451 Mittelschrift，中文 = HarmonyOS Sans SC**（用户指定的那两个文件）。
 *
 * <h2>为什么仪表面板与屏幕 HUD 是两套机制</h2>
 *
 * <p>面板走 AWT 把整幅画面烘成一张贴图（{@link MmtrPanelCanvas}），字体由这里按资源路径直接读；
 * 屏幕 HUD 走 MC 的字体系统（{@code assets/mtr/font/ui.json}，见 {@code IDrawing.withUIFont}）。
 * 两边**用同一批 TTF 文件、同一套角色分工**，但一个要 {@code Font}、一个要字体 id，所以各有一处入口。
 * 两条都**不碰** {@code minecraft:default} 与上游的 {@code mtr:mtr}。</p>
 *
 * <h2>"覆盖不足就回退"是有意的</h2>
 *
 * <p>DIN 1451 只有 231 个字形（数字、拉丁字母、德文变音、常用标点），**没有箭头之类的符号**。
 * 面板这条链是 AWT 物理字体，**没有 MC 字体定义里那种 providers 兜底链**，所以在这里显式判一次：
 * 纯西文且 DIN 全覆盖 → DIN；否则（含中文，或含 DIN 画不出的字符）→ HarmonyOS Sans SC。
 * 判据用 {@link Font#canDisplayUpTo(String)}，也就是"这段文字里有没有 DIN 画不出的字符"。</p>
 *
 * <p>字体懒加载并缓存；资源包重载时由 {@code CustomResourceLoader} 调 {@link #reset()} 丢掉缓存。</p>
 */
public final class MmtrPanelFont {

	private MmtrPanelFont() {
	}

	/** 西文/数字：Alte DIN 1451 Mittelschrift（德式工程数字体，正合 BR101 仪表）。 */
	private static final String LATIN_PATH = "font/din1451alt.ttf";
	/** 中文：HarmonyOS Sans SC（2.9 万字，同时也兜住 DIN 画不出的西文符号）。 */
	private static final String CJK_PATH = "font/harmonyos-sans-sc-regular.ttf";

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

	/**
	 * Picks the font for a string: 中文 → HarmonyOS；纯西文 → DIN（**前提是 DIN 画得出每一个字符**，
	 * 否则交给 HarmonyOS —— 例如带箭头/特殊符号的读数）。
	 */
	public static Font get(String text) {
		if (text != null && IGui.isCjk(text)) {
			return get(true);
		}
		load();
		if (latin != null && (text == null || text.isEmpty() || latin.canDisplayUpTo(text) < 0)) {
			return latin;
		}
		return cjk != null ? cjk : get(false);
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
		if (cjk == null) {
			Init.LOGGER.warn("[MMTR] could not load {}, falling back to a system font", CJK_PATH);
		}
		if (latin != null || cjk != null) {
			// 一行"到底装上了什么"：否则"面板上的字不对"只能靠猜（本仓反复吃过这个亏，见 notes/216–221）。
			Init.LOGGER.info("[MMTR-UI] 仪表面板字体：西文={} 中文={}",
				latin == null ? "(缺失，用系统字体)" : latin.getFontName(),
				cjk == null ? "(缺失)" : cjk.getFontName());
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
