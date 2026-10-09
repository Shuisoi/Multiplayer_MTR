package org.mtr.mod.resource;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mod.Init;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ModelResourceLoader {

	/** 串行化解析：{@link #parseSource} 会动静态的 {@code OptimizedModel.ATLAS_MANAGER}（见那里的说明）。 */
	private static final Object PARSE_LOCK = new Object();

	public static boolean isSupportedModelResource(String modelResource) {
		return modelResource.endsWith(".obj") || modelResource.endsWith(".mqo") || modelResource.endsWith(".mqoz");
	}

	public static Object2ObjectAVLTreeMap<String, OptimizedModel.ObjModel> loadModel(
			String modelResource,
			Identifier textureId,
			boolean flipTextureV,
			ResourceProvider resourceProvider
	) {
		final ModelSource source = createSource(modelResource, textureId, flipTextureV, resourceProvider);
		return source == null ? loadMqoModel(modelResource, textureId, flipTextureV, resourceProvider) : parseSource(source);
	}

	/**
	 * "读"的那一半：把 OBJ 与它声明的 MTL 文本读进内存（notes/400 §7）。
	 *
	 * <p><b>必须在渲染线程</b>：{@code ResourceProvider} 背后是 {@code CustomResourceLoader}
	 * 那个没有同步的 {@code RESOURCE_CACHE}；好在这一步实测只要 3 ms（4.3 MB 的读盘 + UTF-8 解码）。
	 * 真正贵的、并且可以搬走的是 {@link #parseSource}。</p>
	 *
	 * <p>MTL 为什么要预读：OBJ 里用哪个材质文件只有解析器读到 {@code mtllib} 才知道，
	 * 而 {@link #parseSource} 是要挪到别的线程上去的 —— 让它再回头去碰资源就白搬了。
	 * 所以这里先把 {@code mtllib} 声明过的全部读进来，解析那一半只查表。</p>
	 *
	 * @return {@code null} = 这条路不做"读/解析"拆分（MQO/MQOZ 要先转换，暂不拆），调用方走原来的整条路
	 */
	@Nullable
	public static ModelSource createSource(String modelResource, Identifier textureId, boolean flipTextureV, ResourceProvider resourceProvider) {
		if (modelResource.endsWith(".mqo") || modelResource.endsWith(".mqoz")) {
			return null;
		}
		final String objContent = resourceProvider.get(CustomResourceTools.formatIdentifierWithDefault(modelResource, "obj"));
		final Object2ObjectAVLTreeMap<String, String> mtlContents = new Object2ObjectAVLTreeMap<>();
		for (final String mtlName : getMtlNames(objContent)) {
			mtlContents.put(mtlName, resourceProvider.get(CustomResourceTools.getResourceFromSamePath(modelResource, mtlName, "mtl")));
		}
		return new ModelSource(modelResource, textureId, flipTextureV, objContent, mtlContents);
	}

	/**
	 * "解析"的那一半：OBJ/MTL 文本 → 每组的 {@code ObjModel}。
	 *
	 * <p><b>纯 CPU、不碰 GL</b>，所以这一段可以放到别的线程上做 —— 它就是那 120–155 ms
	 * （离线量，见 notes/400 §7）。建 VBO（{@code RawModel.upload}）不在这里，那一步仍然必须在渲染线程。</p>
	 *
	 * <h2>为什么整段要加锁（notes/400 §7）</h2>
	 *
	 * <p>{@code OptimizedModel$ObjModel.loadModel} 会去动一个**静态**的
	 * {@code OptimizedModel.ATLAS_MANAGER}：字节码确认它先 {@code ATLAS_MANAGER.load(identifier)}
	 * （里面是 {@code ResourceManagerHelper.readResource} + Gson 解析），再把 AtlasManager 交给
	 * {@code ObjModelLoader.loadModel} 去 {@code applyToMesh}。而那个 AtlasManager 的两个容器是
	 * <b>普通 {@code HashMap}/{@code HashSet}</b> —— <b>两个解析同时跑就会把它写坏</b>，
	 * 而它是长生命周期静态对象，坏了之后每一次 {@code applyToMesh} 都可能抛异常或死循环。</p>
	 *
	 * <p>所以：后台解析（单线程，天然串行）与"后台失败后的同步兜底"（渲染线程）都必须走这把锁。
	 * 全工程只有 {@code ObjModel.loadModel} 碰得到那个 AtlasManager（{@code fromObjModels}/
	 * {@code upload} 的字节码里对它一次引用都没有），所以锁住这里就够了。</p>
	 */
	public static Object2ObjectAVLTreeMap<String, OptimizedModel.ObjModel> parseSource(ModelSource source) {
		synchronized (PARSE_LOCK) {
			final Object2ObjectAVLTreeMap<String, OptimizedModel.ObjModel> models = new Object2ObjectAVLTreeMap<>(OptimizedModel.ObjModel.loadModel(
					source.objContent,
					mtlString -> source.mtlContents.getOrDefault(mtlString, ""),
					textureString -> StringUtils.isEmpty(textureString) ? OptimizedModelWrapper.WHITE_TEXTURE : StringUtils.equals(textureString, "default.png") ? source.textureId : CustomResourceTools.getResourceFromSamePath(source.modelResource, textureString, "png"),
					null, true, source.flipTextureV
			));
			Init.LOGGER.info("[MMTR-DBG] loading model {}: obj={} chars, mtl={} files, groups={} (thread={})",
					source.modelResource, source.objContent.length(), source.mtlContents.size(), models.keySet(), Thread.currentThread().getName());
			return models;
		}
	}

	/**
	 * OBJ 里 {@code mtllib} 声明过的文件名（一行可以声明多个，按 OBJ 规范以空白分隔）。
	 */
	private static ObjectArrayList<String> getMtlNames(String objContent) {
		final ObjectArrayList<String> names = new ObjectArrayList<>();
		int index = 0;
		while (index < objContent.length()) {
			final int lineEnd = objContent.indexOf('\n', index);
			final int end = lineEnd < 0 ? objContent.length() : lineEnd;
			final String line = objContent.substring(index, end).trim();
			index = end + 1;
			if (!line.startsWith("mtllib")) {
				continue;
			}
			for (final String name : line.substring("mtllib".length()).trim().split("\\s+")) {
				if (!name.isEmpty()) {
					names.add(name);
				}
			}
		}
		return names;
	}

	private static Object2ObjectAVLTreeMap<String, OptimizedModel.ObjModel> loadMqoModel(String modelResource, Identifier textureId, boolean flipTextureV, ResourceProvider resourceProvider) {
		final MqoModelConverter.ConvertedModel convertedModel = MqoModelConverter.convert(getMqoContent(modelResource, resourceProvider));
		return new Object2ObjectAVLTreeMap<>(OptimizedModel.ObjModel.loadModel(
				convertedModel.getObjContent(),
				mtlString -> convertedModel.getMtlContent(),
				textureString -> StringUtils.isEmpty(textureString) ? OptimizedModelWrapper.WHITE_TEXTURE : StringUtils.equals(textureString, "default.png") ? textureId : CustomResourceTools.getResourceFromSamePath(modelResource, textureString, "png"),
				null, true, flipTextureV
		));
	}

	/** {@link #createSource} 的产物：已经读进内存、只等解析的输入。 */
	public static final class ModelSource {

		private final String modelResource;
		private final Identifier textureId;
		private final boolean flipTextureV;
		private final String objContent;
		private final Object2ObjectAVLTreeMap<String, String> mtlContents;

		private ModelSource(String modelResource, Identifier textureId, boolean flipTextureV, String objContent, Object2ObjectAVLTreeMap<String, String> mtlContents) {
			this.modelResource = modelResource;
			this.textureId = textureId;
			this.flipTextureV = flipTextureV;
			this.objContent = objContent;
			this.mtlContents = mtlContents;
		}
	}

	public static ObjectArrayList<String> getModelParts(String name, String content) {
		if (name.endsWith(".mqo")) {
			return MqoModelConverter.convert(content).getModelParts();
		} else if (name.endsWith(".mqoz")) {
			return getModelParts(name, content.getBytes(StandardCharsets.UTF_8));
		} else {
			return new ObjectArrayList<>(OptimizedModel.ObjModel.loadModel(content, mtlString -> "", textureString -> new Identifier(""), null, true, false).keySet());
		}
	}

	public static ObjectArrayList<String> getModelParts(String name, byte[] bytes) {
		if (name.endsWith(".mqoz")) {
			return MqoModelConverter.convert(extractMqoContentFromMqoz(name, bytes)).getModelParts();
		} else {
			return getModelParts(name, new String(bytes, StandardCharsets.UTF_8));
		}
	}

	public static String extractMqoContentFromMqoz(String modelResource, byte[] bytes) {
		final ObjectArrayList<MqozEntry> mqoEntries = new ObjectArrayList<>();
		try (final ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(bytes))) {
			ZipEntry zipEntry;
			while ((zipEntry = zipInputStream.getNextEntry()) != null) {
				if (!zipEntry.isDirectory() && zipEntry.getName().toLowerCase().endsWith(".mqo")) {
					mqoEntries.add(new MqozEntry(zipEntry.getName(), new String(IOUtils.toByteArray(zipInputStream), StandardCharsets.UTF_8)));
				}
				zipInputStream.closeEntry();
			}
		} catch (Exception e) {
			throw new IllegalArgumentException("Invalid MQOZ archive", e);
		}

		if (mqoEntries.isEmpty()) {
			throw new IllegalArgumentException("MQOZ archive does not contain an MQO file");
		}

		final String modelBaseName = getFileBaseName(modelResource);
		mqoEntries.sort(Comparator.comparing(entry -> entry.name));
		for (final MqozEntry entry : mqoEntries) {
			if (StringUtils.equals(getFileBaseName(entry.name), modelBaseName)) {
				return entry.content;
			}
		}
		return mqoEntries.get(0).content;
	}

	private static String getMqoContent(String modelResource, ResourceProvider resourceProvider) {
		if (modelResource.endsWith(".mqoz")) {
			return extractMqoContentFromMqoz(modelResource, resourceProvider.getBytes(CustomResourceTools.formatIdentifierWithDefault(modelResource, "mqoz")));
		} else {
			return resourceProvider.get(CustomResourceTools.formatIdentifierWithDefault(modelResource, "mqo"));
		}
	}

	private static String getFileBaseName(String path) {
		String normalizedPath = path.replace('\\', '/');
		final int namespaceIndex = normalizedPath.indexOf(':');
		if (namespaceIndex >= 0) {
			normalizedPath = normalizedPath.substring(namespaceIndex + 1);
		}
		final String[] pathSplit = normalizedPath.split("/");
		final String fileName = pathSplit[pathSplit.length - 1];
		final int extensionIndex = fileName.lastIndexOf('.');
		return extensionIndex < 0 ? fileName : fileName.substring(0, extensionIndex);
	}

	private ModelResourceLoader() {
	}

	private static final class MqozEntry {

		private final String name;
		private final String content;

		private MqozEntry(String name, String content) {
			this.name = name;
			this.content = content;
		}
	}
}
