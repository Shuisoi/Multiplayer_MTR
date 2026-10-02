package org.mtr.mod.render.panel;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.mtr.mod.Init;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 面文档 {@code image} 元素的**图片加载与缓存**（notes/359 · F3）。
 *
 * <h2>三条路，一条比一条兜底</h2>
 * <ol>
 *   <li><b>资源包</b>（游戏里）：{@code src = "mmtr:vehicle/face/logo.png"} 走原版
 *       {@code ResourceManager.getResource}，于是资源包重载、多包覆盖、内置包全按原版规矩来；</li>
 *   <li><b>磁盘</b>（离线工具）：{@link #setFallbackRoots} 给一组资源包目录，按
 *       {@code <包>/assets/<命名空间>/<路径>} 找 —— {@code face-preview} 与出图工具就是这么用的，
 *       不然"没有游戏进程"就成了"图片元素永远看不见"；</li>
 *   <li><b>放弃</b>：都没找到就记一次并**返回 null**（画法画一个占位框，不是抛异常、也不是每帧重试一次
 *       IO —— 后者的症状是"一块牌让游戏一顿一顿的"，很难联想到是一个写错的图片路径）。</li>
 * </ol>
 *
 * <h2>色调为什么在这里做</h2>
 * <p>{@code tint} 是对每个像素的乘法，属于"做一次就该缓存"的那类事：它不随帧变，只随
 * {@code src + tint} 变。所以缓存键是 {@code src#tint}，每帧只是把已经调好色的那张贴上去。</p>
 */
public final class MmtrFaceImages {

	/** {@code src} → 原图（未调色）。 */
	private static final Map<String, BufferedImage> IMAGES = new Object2ObjectOpenHashMap<>();
	/** {@code src#tint} → 调好色的副本。 */
	private static final Map<String, BufferedImage> TINTED = new Object2ObjectOpenHashMap<>();
	/** 找过但没找到的（别每帧再找一遍）。 */
	private static final ObjectOpenHashSet<String> MISSING = new ObjectOpenHashSet<>();
	/** 离线工具的兜底包目录（每个是一个**资源包根**，里面应当有 {@code assets/}）。 */
	private static final List<Path> FALLBACK_ROOTS = new ArrayList<>();

	private MmtrFaceImages() {
	}

	/**
	 * ★ {@code ImageIO.setUseCache(false)}：{@code ImageIO.read(InputStream)} 默认会先写一个
	 * **临时缓存文件**，临时目录不可写时它直接抛 {@code IIOException: Can't create cache file!} ——
	 * 症状是"图全变成洋红占位框"，而错误信息跟图片、跟资源包都没有半点关系（notes/360 §4 记了这个坑：
	 * 离线出图工具是靠给 java 一个可写的 {@code java.io.tmpdir} 绕过去的）。
	 * 我们读的是几十 KB 的贴图，"不落盘"没有任何代价，所以直接在类加载时关掉。
	 */
	static {
		ImageIO.setUseCache(false);
	}

	/** 离线工具用：给一组资源包目录当兜底（游戏里不需要，留空即可）。 */
	public static void setFallbackRoots(List<Path> roots) {
		FALLBACK_ROOTS.clear();
		if (roots != null) {
			FALLBACK_ROOTS.addAll(roots);
		}
		clear();
	}

	/** 资源重载时清空（与各版式缓存的清理同一个时机）。 */
	public static void clear() {
		IMAGES.clear();
		TINTED.clear();
		MISSING.clear();
	}

	/** 原图；找不到返回 {@code null}（只记一次日志）。 */
	@Nullable
	public static BufferedImage get(String src) {
		if (src == null || src.isEmpty()) {
			return null;
		}
		final BufferedImage cached = IMAGES.get(src);
		if (cached != null) {
			return cached;
		}
		if (MISSING.contains(src)) {
			return null;
		}
		final BufferedImage loaded = load(src);
		if (loaded == null) {
			if (MISSING.add(src)) {
				Init.LOGGER.warn("[MMTR] 面文档的 image 找不到图片「{}」—— 先按资源包找，再按离线兜底目录找；这一块画成占位框", src);
			}
			return null;
		}
		IMAGES.put(src, loaded);
		return loaded;
	}

	/**
	 * 调过色的图；{@code tint} 是 {@code #RRGGBB}（{@code 0xFFFFFF} = 原色，直接返回原图）。
	 * 调色只做一次并缓存 —— 每帧对每个像素乘一遍是没必要的开销。
	 */
	@Nullable
	public static BufferedImage get(String src, int tint) {
		final BufferedImage source = get(src);
		if (source == null) {
			return null;
		}
		if ((tint & 0xFFFFFF) == 0xFFFFFF) {
			return source;
		}
		final String key = src + "#" + Integer.toHexString(tint);
		final BufferedImage cached = TINTED.get(key);
		if (cached != null) {
			return cached;
		}
		final BufferedImage tinted = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
		final int red = (tint >> 16) & 0xFF;
		final int green = (tint >> 8) & 0xFF;
		final int blue = tint & 0xFF;
		for (int y = 0; y < source.getHeight(); y++) {
			for (int x = 0; x < source.getWidth(); x++) {
				final int argb = source.getRGB(x, y);
				final int alpha = (argb >>> 24) & 0xFF;
				tinted.setRGB(x, y, (alpha << 24)
					| ((((argb >> 16) & 0xFF) * red / 255) << 16)
					| ((((argb >> 8) & 0xFF) * green / 255) << 8)
					| ((argb & 0xFF) * blue / 255));
			}
		}
		TINTED.put(key, tinted);
		return tinted;
	}

	/** 宽高比（{@code 宽/高}）；取不到图返回 0（装图时当 {@code stretch}）。 */
	public static double aspect(String src) {
		final BufferedImage image = get(src);
		return image == null || image.getHeight() <= 0 ? 0 : (double) image.getWidth() / image.getHeight();
	}

	@Nullable
	private static BufferedImage load(String src) {
		final int colon = src.indexOf(':');
		final String namespace = colon < 0 ? "minecraft" : src.substring(0, colon).trim().toLowerCase(Locale.ROOT);
		final String path = colon < 0 ? src.trim() : src.substring(colon + 1).trim();
		if (namespace.isEmpty() || path.isEmpty()) {
			return null;
		}

		// ① 资源包（原版那套：多包覆盖、重载都跟着走）
		try {
			final net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
			if (client != null && client.getResourceManager() != null) {
				final var resource = client.getResourceManager().getResource(new net.minecraft.util.Identifier(namespace, path));
				if (resource.isPresent()) {
					// Resource 是 AutoCloseable：不关会把包的文件句柄攥住（Windows 上表现为"资源包删不掉"）
					try (final var stream = resource.get().getInputStream()) {
						final BufferedImage image = ImageIO.read(stream);
						if (image != null) {
							return image;
						}
					}
				}
			}
		} catch (Throwable e) {
			// 离线工具里连客户端都没有（getInstance() 可能抛）；这里刻意吞掉，落到磁盘那条路
		}

		// ② 磁盘兜底（离线工具）
		for (final Path root : FALLBACK_ROOTS) {
			for (final Path candidate : List.of(root.resolve("assets").resolve(namespace).resolve(path), root.resolve(namespace).resolve(path))) {
				try {
					if (Files.isRegularFile(candidate)) {
						try (final InputStream stream = Files.newInputStream(candidate)) {
							final BufferedImage image = ImageIO.read(stream);
							if (image != null) {
								return image;
							}
						}
					}
				} catch (Exception e) {
					// 这一个候选读不了就试下一个（坏图不该让整块面不画）
				}
			}
		}
		return null;
	}
}
