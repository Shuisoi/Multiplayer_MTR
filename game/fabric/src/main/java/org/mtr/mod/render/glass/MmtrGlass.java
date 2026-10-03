package org.mtr.mod.render.glass;

import net.minecraft.client.MinecraftClient;
import org.mtr.mapping.holder.Direction;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.Init;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MmtrVehicleAnchors;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.QueuedRenderLayer;
import org.mtr.mod.render.StoredMatrixTransformations;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * MMTR：玻璃。**完全由 Blender 导出的 OBJ 与它那张贴图决定**（notes/345 §7.17）。
 *
 * <p>几何与贴图来自 {@link MmtrVehicleAnchors#getGlass(String)}（打包器从 OBJ 的 {@code glass} 组导出）。
 * 客户端只做一件事：用一条**真混合、无背面剔除**的层把那些四边形画出来 —— 于是玻璃的颜色/透明度/图案
 * **就是作者画在贴图上的样子**，mod 侧没有任何自己的颜色配置。</p>
 *
 * <p><b>为什么玻璃必须由 mod 画</b>：MTR 的 OBJ 优化渲染路径实际只做 cutout（贴图 α&gt;0.1 ⇒ 实心、
 * α&lt;0.1 ⇒ 丢弃），模型自己的玻璃材质拿不到 alpha 混合（notes/345 §7.14 实测）。</p>
 *
 * <p>兜底（{@code run/mmtr-lightfield.properties}，2 秒热重载）：{@code glass} / {@code glassArgb} /
 * {@code glassOffsetM} —— 只在模型**没有**自带玻璃几何时，才在挡风锚点上画一块纯色玻璃
 * （见 {@code MmtrWindshield.drawGlassTint}）。</p>
 */
public class MmtrGlass {

	private static final String PROPERTIES_FILE = "mmtr-lightfield.properties";
	private static final long REFRESH_INTERVAL_MILLIS = 2000L;
	private static final int DEFAULT_ARGB = 0x28C8E0F0;
	private static final double DEFAULT_OFFSET_M = 0.0;

	private static long lastRefreshMillis;
	private static boolean enabled = true;
	private static int argb = DEFAULT_ARGB;
	private static double offsetM = DEFAULT_OFFSET_M;
	private static boolean loggedOnce;
	private static boolean loggedModelOnce;

	/** @return 该模型是否自带玻璃几何（true 时不再用兜底纯色玻璃）。 */
	public static boolean hasModelGlass(String vehicleId) {
		return MmtrVehicleAnchors.getGlass(vehicleId) != null;
	}

	/**
	 * 把该模型自带的玻璃画出来：真混合 + 无背面剔除，颜色/透明度完全来自贴图。
	 *
	 * <p>必须与天气/雨刷无关地每帧画（{@code MmtrWindshield.render} 在"干燥且雨刷归位"时会 continue）。</p>
	 */
	public static void draw(StoredMatrixTransformations carTransform, String vehicleId) {
		final MmtrVehicleAnchors.GlassModel model = MmtrVehicleAnchors.getGlass(vehicleId);
		if (model == null || model.positions.length == 0) {
			return;
		}
		if (!loggedModelOnce) {
			loggedModelOnce = true;
			Init.LOGGER.info("[MMTR-GLASS] {} 自带玻璃：{} 个四边形，贴图 {}",
					vehicleId, model.positions.length, model.texture);
		}
		// EXTERIOR_TRANSLUCENT_DOUBLE = getEntityTranslucent（半透明 + 无背面剔除）⇒ 两面都看得见 ✓
		MainRenderer.scheduleRender(model.texture, false, QueuedRenderLayer.EXTERIOR_TRANSLUCENT_DOUBLE, (graphicsHolder, offset) -> {
			carTransform.transform(graphicsHolder, offset);
			for (int index = 0; index < model.positions.length; index++) {
				final double[] p = model.positions[index];
				final float[] uv = model.uvs[index];
				// UV 矩形退化成一点（四角同一个纹素）时，按"**整张贴图铺满这块玻璃**"来画：
				// 这样作者在贴图上画的边框/渐变色/污渍就能直接生效（现实感的黑边就是这么做的），
				// 而均匀色块贴图下它与"取一个纹素"的结果完全一致（贴图均匀 ⇒ 视觉零变化）。
				final boolean degenerate = Math.abs(uv[2] - uv[0]) < 1.0E-4F && Math.abs(uv[3] - uv[1]) < 1.0E-4F;
				final float u0 = degenerate ? 0.0F : uv[0];
				final float v0 = degenerate ? 0.0F : uv[1];
				final float u1 = degenerate ? 1.0F : uv[2];
				final float v1 = degenerate ? 1.0F : uv[3];
				IDrawing.drawTexture(
						graphicsHolder,
						(float) p[0], (float) p[1], (float) p[2],
						(float) p[3], (float) p[4], (float) p[5],
						(float) p[6], (float) p[7], (float) p[8],
						(float) p[9], (float) p[10], (float) p[11],
						new Vector3d(0, 0, 0),
						u0, u1, v0, v1,
						Direction.UP, 0xFFFFFFFF, GraphicsHolder.getDefaultLight()
				);
			}
			graphicsHolder.pop();
		});
	}

	// ---------------------------------------------------------------- 兜底纯色玻璃（属性）

	private static void refresh() {
		final long now = System.currentTimeMillis();
		if (now - lastRefreshMillis < REFRESH_INTERVAL_MILLIS) {
			return;
		}
		lastRefreshMillis = now;

		final File file = new File(MinecraftClient.getInstance().runDirectory, PROPERTIES_FILE);
		if (!file.isFile()) {
			return;
		}

		try (InputStream inputStream = new FileInputStream(file)) {
			final Properties properties = new Properties();
			properties.load(inputStream);
			enabled = Boolean.parseBoolean(properties.getProperty("glass", "true").trim());
			argb = parseArgb(properties.getProperty("glassArgb", Integer.toHexString(DEFAULT_ARGB)).trim());
			offsetM = Math.max(-0.5, Math.min(0.5, Double.parseDouble(properties.getProperty("glassOffsetM", String.valueOf(DEFAULT_OFFSET_M)).trim())));
			if (!loggedOnce) {
				loggedOnce = true;
				Init.LOGGER.info("[MMTR-GLASS] 兜底玻璃：enabled={} argb=0x{} offsetM={}（来自 run/{}）", enabled, Integer.toHexString(argb), offsetM, PROPERTIES_FILE);
			}
		} catch (Exception exception) {
			Init.LOGGER.error("[MMTR-GLASS] 读取 {} 失败", PROPERTIES_FILE, exception);
		}
	}

	private static int parseArgb(String text) {
		try {
			final String cleaned = text.startsWith("0x") || text.startsWith("0X") ? text.substring(2) : text;
			return (int) (Long.parseLong(cleaned, 16) & 0xFFFFFFFFL);
		} catch (Exception exception) {
			return DEFAULT_ARGB;
		}
	}

	public static boolean isEnabled() {
		refresh();
		return enabled;
	}

	public static int argb() {
		refresh();
		return argb;
	}

	public static double offsetM() {
		refresh();
		return offsetM;
	}
}
