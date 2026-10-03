package org.mtr.mod.render;

import net.minecraft.client.MinecraftClient;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.RenderLayer;
import org.mtr.mod.Init;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;
import java.util.function.Supplier;

public class MoreRenderLayers {

	private static final Object2ObjectOpenHashMap<Identifier, RenderLayer> LIGHT_CACHE = new Object2ObjectOpenHashMap<>();
	private static final Object2ObjectOpenHashMap<Identifier, RenderLayer> LIGHT_TRANSLUCENT_CACHE = new Object2ObjectOpenHashMap<>();
	private static final Object2ObjectOpenHashMap<Identifier, RenderLayer> LIGHT_2_CACHE = new Object2ObjectOpenHashMap<>();
	private static final Object2ObjectOpenHashMap<Identifier, RenderLayer> INTERIOR_CACHE = new Object2ObjectOpenHashMap<>();
	private static final Object2ObjectOpenHashMap<Identifier, RenderLayer> INTERIOR_TRANSLUCENT_CACHE = new Object2ObjectOpenHashMap<>();
	private static final Object2ObjectOpenHashMap<Identifier, RenderLayer> EXTERIOR_CACHE = new Object2ObjectOpenHashMap<>();
	private static final Object2ObjectOpenHashMap<Identifier, RenderLayer> EXTERIOR_TRANSLUCENT_CACHE = new Object2ObjectOpenHashMap<>();
	private static final Object2ObjectOpenHashMap<Identifier, RenderLayer> EXTERIOR_TRANSLUCENT_DOUBLE_CACHE = new Object2ObjectOpenHashMap<>();

	// ------------------------------------------------------------------ 半透明件的图层模式（热重载）

	private static final String PROPERTIES_FILE = "mmtr-lightfield.properties";
	private static final String TRANSLUCENT_MODE_KEY = "translucentLayer";
	private static final String TRANSLUCENT_DEPTH_WRITE_KEY = "translucentDepthWrite";
	private static final String DEFAULT_TRANSLUCENT_MODE = "nooutline";
	private static final long MODE_REFRESH_INTERVAL_MILLIS = 2000L;

	private static long lastModeRefreshMillis;
	private static String translucentMode = DEFAULT_TRANSLUCENT_MODE;
	private static boolean translucentDepthWrite;
	private static boolean loggedMode;

	public static void removeFromCache(Identifier identifier) {
		LIGHT_CACHE.remove(identifier);
		LIGHT_TRANSLUCENT_CACHE.remove(identifier);
		LIGHT_2_CACHE.remove(identifier);
		INTERIOR_CACHE.remove(identifier);
		INTERIOR_TRANSLUCENT_CACHE.remove(identifier);
		EXTERIOR_CACHE.remove(identifier);
		EXTERIOR_TRANSLUCENT_CACHE.remove(identifier);
		EXTERIOR_TRANSLUCENT_DOUBLE_CACHE.remove(identifier);
	}

	public static RenderLayer getLight(Identifier texture, boolean isTranslucent) {
		return checkCache(texture, () -> RenderLayer.getBeaconBeam(texture, isTranslucent), isTranslucent ? LIGHT_TRANSLUCENT_CACHE : LIGHT_CACHE);
	}

	public static RenderLayer getLight2(Identifier texture) {
		return checkCache(texture, () -> RenderLayer.getText(texture), LIGHT_2_CACHE);
	}

	public static RenderLayer getInterior(Identifier texture) {
		return checkCache(texture, () -> RenderLayer.getEntityCutout(texture), INTERIOR_CACHE);
	}

	public static RenderLayer getInteriorTranslucent(Identifier texture) {
		refreshTranslucentMode();
		return checkCache(texture, () -> createTranslucent(texture, false), INTERIOR_TRANSLUCENT_CACHE);
	}

	public static RenderLayer getExterior(Identifier texture) {
		return checkCache(texture, () -> RenderLayer.getEntityCutout(texture), EXTERIOR_CACHE);
	}

	public static RenderLayer getExteriorTranslucent(Identifier texture) {
		refreshTranslucentMode();
		return checkCache(texture, () -> createTranslucent(texture, false), EXTERIOR_TRANSLUCENT_CACHE);
	}

	/** Translucent with NO back-face culling, so one quad is visible from both sides. */
	public static RenderLayer getExteriorTranslucentDoubleSided(Identifier texture) {
		refreshTranslucentMode();
		return checkCache(texture, () -> createTranslucent(texture, true), EXTERIOR_TRANSLUCENT_DOUBLE_CACHE);
	}

	/**
	 * 半透明件（车窗 / 水膜 / 雨滴）到底走哪条原版图层。
	 *
	 * <p><b>为什么默认是 {@code nooutline}</b>：原版 {@code renderTypeEntityTranslucent}（含 …Cull 那个）的
	 * 写掩码是 <b>ALL_MASK</b>（颜色 + 深度），也就是**半透明玻璃会写深度缓冲**；而它的顶点着色器带
	 * {@code if (color.a < 0.1) discard;}。玻璃写深度的后果：**在它之后画的、且更远的几何会被深度测试整体剔掉**
	 * —— 实体（猪牛羊）、方块实体、水面/冰/玻璃方块都会"透过玻璃看就消失"。
	 * 车厢与所有实体都在实体批次里（{@code VertexConsumerProvider} 的延迟缓冲，按图层顺序统一 flush），
	 * 谁先谁后不由我们决定 ⇒ 只要玻璃写深度，就必然有一类东西被它挡掉。</p>
	 *
	 * <p>{@code renderTypeEntityNoOutline} 是原版里唯一同时满足**同样顶点格式
	 * （POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL）+ 真混合 + 不剔背面 + 写掩码 COLOR_MASK（不写深度）**
	 * 的实体图层，着色器同样用光照贴图（vsh 里把 lightmap 乘进顶点色），且**没有 α&lt;0.1 丢弃**，
	 * 对玻璃更合适。（2026-09-30 用 javap 读 1.20.4 yarn 客户端里 RenderLayer 的字节码逐条确认。）</p>
	 *
	 * <p>开关：{@code run/mmtr-lightfield.properties} 的 {@code translucentLayer}，2 秒热重载，不用重启：</p>
	 * <ul>
	 *   <li>{@code nooutline}（默认）= 不写深度、不剔背面、真混合 ⇒ {@code getEntityNoOutline}</li>
	 *   <li>{@code double} = **改之前的行为**（INTERIOR/EXTERIOR_TRANSLUCENT 剔背面 + 写深度，
	 *       EXTERIOR_TRANSLUCENT_DOUBLE 不剔背面但写深度）—— 留作 A/B 对照</li>
	 *   <li>{@code cull} = 三条都走 {@code getEntityTranslucentCull}（写深度 + 剔背面）</li>
	 * </ul>
	 */
	private static RenderLayer createTranslucent(Identifier texture, boolean doubleSided) {
		switch (translucentMode) {
			case "double":
				return doubleSided ? RenderLayer.getEntityTranslucent(texture) : RenderLayer.getEntityTranslucentCull(texture);
			case "cull":
				return RenderLayer.getEntityTranslucentCull(texture);
			default:
				return RenderLayer.getEntityNoOutline(texture);
		}
	}

	private static void refreshTranslucentMode() {
		final long now = System.currentTimeMillis();
		if (now - lastModeRefreshMillis < MODE_REFRESH_INTERVAL_MILLIS) {
			return;
		}
		lastModeRefreshMillis = now;

		final File file = new File(MinecraftClient.getInstance().runDirectory, PROPERTIES_FILE);
		if (!file.isFile()) {
			return;
		}

		try (InputStream inputStream = new FileInputStream(file)) {
			final Properties properties = new Properties();
			properties.load(inputStream);
			final String mode = properties.getProperty(TRANSLUCENT_MODE_KEY, DEFAULT_TRANSLUCENT_MODE).trim().toLowerCase();
			if (!mode.equals(translucentMode)) {
				translucentMode = mode;
				// 图层换了 ⇒ 三个半透明缓存必须作废，否则还捧着旧图层的 RenderLayer。
				INTERIOR_TRANSLUCENT_CACHE.clear();
				EXTERIOR_TRANSLUCENT_CACHE.clear();
				EXTERIOR_TRANSLUCENT_DOUBLE_CACHE.clear();
				Init.LOGGER.info("[MMTR-GLASS] 半透明件图层切换为 {}（{}）", translucentMode, describeTranslucentMode());
			}
			// 优化渲染器那条路：半透明材质写不写深度（MaterialPropertiesMixin 读它）。
			final boolean depthWrite = Boolean.parseBoolean(properties.getProperty(TRANSLUCENT_DEPTH_WRITE_KEY, "false").trim());
			if (depthWrite != translucentDepthWrite) {
				translucentDepthWrite = depthWrite;
				Init.LOGGER.info("[MMTR-GLASS] 优化渲染器半透明材质写深度={}（{}）", translucentDepthWrite, translucentDepthWrite ? "旧行为：玻璃会挡住身后的实体/方块" : "修法：不写深度");
			}
			if (!loggedMode) {
				loggedMode = true;
				Init.LOGGER.info("[MMTR-GLASS] 半透明件图层={}（{}）；优化渲染器半透明写深度={}（来自 run/{}）", translucentMode, describeTranslucentMode(), translucentDepthWrite, PROPERTIES_FILE);
			}
		} catch (Exception exception) {
			Init.LOGGER.error("[MMTR-GLASS] 读取 {} 的 {} 失败", PROPERTIES_FILE, TRANSLUCENT_MODE_KEY, exception);
		}
	}

	/**
	 * 优化渲染器里半透明材质（车窗等）是否允许写深度缓冲。
	 *
	 * <p>默认 <b>false</b>：MTR 的 {@code MaterialProperties} 给 {@code TRANSLUCENT_BRIGHT}
	 * （= {@code RenderStage.INTERIOR_TRANSLUCENT}，车窗那一档）设的是 {@code writeDepthBuf=true}，
	 * 于是车厢玻璃的深度在实体批次 flush 之前就落进深度缓冲，把更远的猪牛羊/水/方块实体全剔掉了
	 * —— 见 {@link org.mtr.mixin.MaterialPropertiesMixin} 与 notes/345 §7.19。
	 * 开关：{@code run/mmtr-lightfield.properties} 的 {@code translucentDepthWrite}=true 可切回旧行为做 A/B。</p>
	 */
	public static boolean allowTranslucentDepthWrite() {
		refreshTranslucentMode();
		return translucentDepthWrite;
	}

	private static String describeTranslucentMode() {
		switch (translucentMode) {
			case "double":
				return "写深度：外侧不剔背面 / 内侧剔背面（旧行为）";
			case "cull":
				return "写深度 + 剔背面";
			default:
				return "不写深度 + 不剔背面 + 真混合（玻璃不再挡住身后的实体/方块）";
		}
	}

	private static RenderLayer checkCache(Identifier identifier, Supplier<RenderLayer> supplier, Object2ObjectOpenHashMap<Identifier, RenderLayer> cache) {
		if (cache.containsKey(identifier)) {
			return cache.get(identifier);
		} else {
			final RenderLayer renderLayer = supplier.get();
			cache.put(identifier, renderLayer);
			return renderLayer;
		}
	}
}
