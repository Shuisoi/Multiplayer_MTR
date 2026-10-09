package org.mtr.mod.resource;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.annotation.MappedMethod;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.ModelPartExtension;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mapping.mapper.OptimizedRenderer;
import org.mtr.mod.Init;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class OptimizedModelWrapper {
	/**
	 * Used as a fallback texture if a face does not have a texture
	 */
	public static final Identifier WHITE_TEXTURE = new Identifier(Init.MOD_ID, "textures/block/white.png");

	@Nullable
	final OptimizedModel optimizedModel;

	/** {@code OptimizedModel.uploadedParts} 是包私有的，映射库没给 getter（判定性埋点用）。 */
	private static Field uploadedPartsField;
	private static boolean uploadedPartsFieldResolved;

	public static OptimizedModelWrapper fromMaterialGroups(@Nullable ObjectArrayList<MaterialGroupWrapper> materialGroupList) {
		return new OptimizedModelWrapper(OptimizedRenderer.hasOptimizedRendering() && materialGroupList != null ? OptimizedModel.fromMaterialGroups(materialGroupList.stream().map(materialGroup -> materialGroup.materialGroup).filter(Objects::nonNull).collect(Collectors.toList())) : null);
	}

	public static OptimizedModelWrapper fromObjModels(@Nullable ObjectArrayList<ObjModelWrapper> objModels) {
		return new OptimizedModelWrapper(OptimizedRenderer.hasOptimizedRendering() && objModels != null ? OptimizedModel.fromObjModels(objModels.stream().map(objModel -> objModel.objModel).filter(Objects::nonNull).collect(Collectors.toList())) : null);
	}

	private OptimizedModelWrapper(@Nullable OptimizedModel optimizedModel) {
		this.optimizedModel = optimizedModel;
	}

	/**
	 * 直接包装一个**已经烘焙好**的 {@link OptimizedModel}（钢轨合并烘焙用，见 {@code MmtrRailMeshCache}）。
	 *
	 * <p>{@link OptimizedModel} 的构造口在映射库里是公有的（{@code OptimizedModel(List<VertexArray>)}），
	 * 但 {@code OptimizedModelWrapper} 的构造函数是私有的 ⇒ 补一个公有工厂，别处不要另开第二条路。</p>
	 */
	public static OptimizedModelWrapper fromOptimizedModel(@Nullable OptimizedModel optimizedModel) {
		return new OptimizedModelWrapper(optimizedModel);
	}

	/**
	 * 释放这个包装持有的 GL 资源（顶点缓冲/VAO）。
	 *
	 * <p>给合并烘焙的**淘汰**用：一张烘焙好的钢轨模型常驻显存，缓存满了必须能还回去。</p>
	 */
	public void close() {
		if (optimizedModel != null) {
			optimizedModel.close();
		}
	}

	/**
	 * 这个模型会变成**几次 draw**（notes/400 的判定性埋点用）。
	 *
	 * <p>{@code BatchManager.queue} 给每一个 {@link org.mtr.mapping.render.object.VertexArray} 建一条
	 * {@code RenderCall}，{@code draw()} 就是一次 {@code glDrawElements}（非 instanced）
	 * ⇒ {@code uploadedParts} 的条数**就是** draw 数。这是把「合并生效 / 未生效」直接读出来的唯一办法：
	 * 光看 {@code [MMTR-VEHMERGE]} 日志只能看到**构建时**发生了什么，看不到渲染时用的是哪一个模型。</p>
	 *
	 * @return draw 数；模型为空返回 0；反射拿不到返回 {@code -1}（调用方必须把 -1 与 0 分开看待）
	 */
	public int partCount() {
		if (optimizedModel == null) {
			return 0;
		}
		if (!uploadedPartsFieldResolved) {
			uploadedPartsFieldResolved = true;
			try {
				final Field field = OptimizedModel.class.getDeclaredField("uploadedParts");
				field.setAccessible(true);
				uploadedPartsField = field;
			} catch (Exception exception) {
				Init.LOGGER.warn("[MMTR-VDRAW] 取不到 OptimizedModel.uploadedParts —— draw 数埋点退化为 -1", exception);
			}
		}
		if (uploadedPartsField == null) {
			return -1;
		}
		try {
			final Object value = uploadedPartsField.get(optimizedModel);
			return value instanceof List ? ((List<?>) value).size() : -1;
		} catch (Exception exception) {
			return -1;
		}
	}

	public OptimizedModelWrapper(@Nullable OptimizedModelWrapper optimizedModel1, @Nullable OptimizedModelWrapper optimizedModel2) {
		final boolean nonNull1 = optimizedModel1 != null && optimizedModel1.optimizedModel != null;
		final boolean nonNull2 = optimizedModel2 != null && optimizedModel2.optimizedModel != null;
		if (OptimizedRenderer.hasOptimizedRendering()) {
			if (nonNull1 && nonNull2) {
				optimizedModel = new OptimizedModel(optimizedModel1.optimizedModel, optimizedModel2.optimizedModel);
			} else if (nonNull1) {
				optimizedModel = optimizedModel1.optimizedModel;
			} else if (nonNull2) {
				optimizedModel = optimizedModel2.optimizedModel;
			} else {
				optimizedModel = null;
			}
		} else {
			optimizedModel = null;
		}
	}

	public static final class MaterialGroupWrapper {

		@Nullable
		private final OptimizedModel.MaterialGroup materialGroup;

		@MappedMethod
		public MaterialGroupWrapper(OptimizedModel.ShaderType shaderType, Identifier texture) {
			materialGroup = OptimizedRenderer.hasOptimizedRendering() ? new OptimizedModel.MaterialGroup(shaderType, texture) : null;
		}

		@MappedMethod
		public void addCube(ModelPartExtension modelPart, double x, double y, double z, boolean flipped, int light) {
			if (materialGroup != null) {
				materialGroup.addCube(modelPart, x, y, z, flipped, light);
			}
		}
	}

	public static final class ObjModelWrapper {

		public final OptimizedModel.ObjModel objModel;

		public ObjModelWrapper(OptimizedModel.ObjModel objModel) {
			this.objModel = objModel;
		}

		public void addTransformation(OptimizedModel.ShaderType shaderType, double x, double y, double z, boolean flipped) {
			objModel.addTransformation(shaderType, x, y, z, flipped);
		}
	}
}
