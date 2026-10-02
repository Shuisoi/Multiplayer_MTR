package org.mtr.mod.render.panel;

import org.apache.commons.io.IOUtils;
import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.ResourceManagerHelper;
import org.mtr.mod.Init;
import org.mtr.mod.mmtr.face.MmtrFaceDocument;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 面文档的**按车型缓存**（notes/359）：读一次 {@code mmtr_anchors_<车型>.json}，
 * 把里面的 {@code faces} 段解析成 {@link MmtrFaceDocument} 并按**锚点名**索引。
 *
 * <h2>面名 = 锚点名</h2>
 * <p>这一条是整个接口的核心约定：模型里那个 {@code mmtr_pid_1}（或任何锚点）叫什么，面文档就写在
 * {@code faces.pid_1} 下。于是：</p>
 * <ul>
 *   <li>再加一块牌**不用改客户端**（打包器给一个新锚点 + JSON 里写一段文档即可）；</li>
 *   <li>"哪块牌归谁画"有了唯一答案：**有文档的锚点归面系统，没文档的仍归老渲染器**
 *       （{@code MmtrPidBoard} 会跳过有文档的锚点）—— 所以 F1 把水牌切到面文档，
 *       只需要改资源包，不需要改一行代码。</li>
 * </ul>
 *
 * <p>资源重载时由 {@code CustomResourceLoader} 清缓存（与 {@code MmtrVehicleAnchors.clearCache()} 同时机）。</p>
 */
public final class MmtrFaceRegistry {

	private static final String NAMESPACE = "mtr";
	private static final String FILE_PREFIX = "mmtr_anchors_";
	private static final String FILE_SUFFIX = ".json";

	/** 车型 → 锚点名 → 文档（{@code null} 值用不上：没有文档的锚点根本不进这张表）。 */
	private static final Object2ObjectOpenHashMap<String, Object2ObjectOpenHashMap<String, MmtrFaceDocument>> CACHE = new Object2ObjectOpenHashMap<>();

	private MmtrFaceRegistry() {
	}

	/** 这块面有没有作者写的文档（没有 = 老渲染器管）。 */
	public static boolean hasDocument(String vehicleId, String faceName) {
		return document(vehicleId, faceName) != null;
	}

	/** 取这块面的文档；这个锚点没写 {@code faces} 条目就是 {@code null}。 */
	public static MmtrFaceDocument document(String vehicleId, String faceName) {
		if (vehicleId == null || vehicleId.isEmpty() || faceName == null || faceName.isEmpty()) {
			return null;
		}
		Object2ObjectOpenHashMap<String, MmtrFaceDocument> faces = CACHE.get(vehicleId);
		if (faces == null) {
			faces = readFaces(vehicleId);
			CACHE.put(vehicleId, faces);
		}
		return faces.get(faceName);
	}

	/** 资源重载时丢掉缓存。 */
	public static void clearCache() {
		CACHE.clear();
	}

	/** 读并解析一个车型的 {@code faces} 段（只做一次，之后按锚点名查表）。 */
	private static Object2ObjectOpenHashMap<String, MmtrFaceDocument> readFaces(String vehicleId) {
		final Object2ObjectOpenHashMap<String, MmtrFaceDocument> faces = new Object2ObjectOpenHashMap<>();
		final String text = readAnchorFile(vehicleId);
		if (text.isEmpty()) {
			return faces;
		}
		try {
			final JsonElement root = JsonParser.parseString(text);
			if (!root.isJsonObject()) {
				return faces;
			}
			final JsonElement section = root.getAsJsonObject().get("faces");
			if (section == null || !section.isJsonObject()) {
				return faces;
			}
			for (final var entry : section.getAsJsonObject().entrySet()) {
				if (!entry.getValue().isJsonObject()) {
					continue;
				}
				final MmtrFaceDocument document = parseOne(vehicleId, entry.getKey(), entry.getValue().getAsJsonObject());
				if (document != null) {
					faces.put(entry.getKey(), document);
				}
			}
		} catch (Exception e) {
			Init.LOGGER.error("[MMTR] 解析车型 {} 的 faces 段失败", vehicleId, e);
		}
		return faces;
	}

	/**
	 * 单个面对象的解析：走 {@link MmtrFaceDocument} 的同一条路（先序列化成文本再解析，
	 * 免得纯数据层为了"从一个 JsonObject 出发"多开一个入口 —— 面文档本来就只有这一个来源）。
	 */
	private static MmtrFaceDocument parseOne(String vehicleId, String faceName, JsonObject object) {
		final JsonObject wrapper = new JsonObject();
		final JsonObject faces = new JsonObject();
		faces.add(faceName, object);
		wrapper.add("faces", faces);
		return MmtrFaceDocument.fromAnchors(vehicleId, faceName, wrapper.toString());
	}

	/** 与 {@code MmtrPidLayout}/{@code MmtrHudLayout} 同一个读法（同一份文件被读了三次 —— 见 notes/359 的欠账）。 */
	private static String readAnchorFile(String vehicleId) {
		final String[] content = {""};
		try {
			ResourceManagerHelper.readResource(new Identifier(NAMESPACE, FILE_PREFIX + vehicleId + FILE_SUFFIX), inputStream -> {
				try (final InputStream stream = inputStream) {
					content[0] = IOUtils.toString(stream, StandardCharsets.UTF_8);
				} catch (IOException e) {
					Init.LOGGER.error("Failed to read MMTR anchors for {}", vehicleId, e);
				}
			});
		} catch (Exception e) {
			// 没有锚点文件是正常的（这个车型就没有面）—— 只有读到一半出错才值得记
			return "";
		}
		return content[0];
	}
}
