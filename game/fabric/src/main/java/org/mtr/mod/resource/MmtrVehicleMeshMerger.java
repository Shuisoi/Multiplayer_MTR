package org.mtr.mod.resource;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mapping.mapper.OptimizedRenderer;
import org.mtr.mapping.render.model.RawModel;
import org.mtr.mapping.render.object.VertexArray;
import org.mtr.mapping.render.vertex.VertexAttributeMapping;
import org.mtr.mapping.render.vertex.VertexAttributeSource;
import org.mtr.mapping.render.vertex.VertexAttributeType;
import org.mtr.mod.Init;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.List;

/**
 * 把一批车辆部件的几何**按材质合并**成一个模型（notes/399），并把"合并之前那一半"
 * 拆成可以搬到别的线程上的纯 CPU 工作（notes/400 §8）。
 *
 * <h2>要解决什么</h2>
 *
 * <p>车辆一个部件（= 一个 OBJ group）一个 {@link OptimizedModelWrapper}。渲染时每个部件各排一次队，
 * 而映射库的 {@code BatchManager.queue} 给**每一个** {@code VertexArray} 建一条 {@code RenderCall}、
 * {@code draw()} 就是一次 {@code glDrawElements}（非 instanced）⇒ <b>一个部件一次 draw</b>。
 * 实测 2 辆车 196–284 次 draw/pass，批次却只有 7–11 个（= 材质数）。</p>
 *
 * <p>卡点在最后一步：{@code OptimizedModelWrapper(m1, m2)} 用
 * {@code new OptimizedModel(m1, m2)} 把各自的 {@code uploadedParts} <b>首尾相接</b>，
 * 而它**不按材质合桶**。所以本类的做法是把合并提到 {@code upload()} **之前**：
 * 先把各部件的 {@code RawMesh} 收进同一个 {@code RawModel}（{@code RawModel.append} 正是按
 * {@code MaterialProperties} 合桶并平移面索引），再 upload 一次 ⇒ 一个材质一个 {@code VertexArray}。</p>
 *
 * <h2>两段式（notes/400 §8）：为什么必须"先复制、再改副本"</h2>
 *
 * <p>映射库的 {@code OptimizedModel.fromObjModels} 是**就地**对每组几何跑
 * {@code rawModel.generateNormals() + distinct()} 的，而 {@code distinct()} 的实现是
 * {@code vertices.clear(); vertices.addAll(去重结果)} —— 也就是说它<b>一边清空一边重填一份别人也在看的列表</b>。
 * 留在渲染线程上没人看得出来；一旦搬到别的线程，就会和渲染线程的 {@code upload()}（它正在读同一份
 * {@code vertices}/{@code faces}）撞上，读到半个列表。</p>
 *
 * <p>所以本类不就地改源几何，而是先用 {@code RawModel.append} 把源几何<b>复制</b>成自己的一份
 * （顶点引用 + 面副本），再在副本上 {@code generateNormals() + distinct()}。
 * 这样"源几何自 {@code writeCache} 之后只读"成为一条可以检查的不变式，归一化与合桶整段都能搬走。</p>
 *
 * <p>等价性不是推理出来的，是<b>离线逐字节验过</b>的：
 * {@code sandbox/railbake-verify/ObjParseBench}（真实映射库类、无 Minecraft、无 GL）对同一份
 * {@code saf420car.obj} 分别跑"就地版"与"复制版"，把材质桶顺序、每个顶点
 * （位置/法线/uv/color/light）、每条面的索引全部进 MD5 —— 合并路径与逐部件路径都<b>一致</b>。</p>
 *
 * <h2>为什么碰不到门与雨刷</h2>
 *
 * <p>门/雨刷走的是另一条路：{@code ModelPropertiesPart} 从**源** ObjModel 列表自建
 * {@code optimizedModelDoor}，并**逐位置**排队（雨刷那次还夹在运动学旋转里）。
 * 它<b>不在</b>本类处理的 bundle 里（门只进 {@code …DoorsClosed}、雨刷一个批次都不进），
 * 所以这里的合并动不到它们。详见 notes/399 §4.4。</p>
 *
 * <p>{@code notes/400 §6} 的 A 路线是**另一件事**：它把"同一辆车同一个动画类的门"跨部件组合并成
 * 一次 draw，用的正是本类的 {@link #mergeOrNull}（合并逻辑只此一份）。
 * 本类在 bundle 那条路上的行为完全没有变化。</p>
 *
 * <h2>开关</h2>
 *
 * <p><b>按材质合并默认开启</b>（2026-10-06 起）。它先以默认关闭上线、经实机验证后才翻成默认开：
 * 实测 2 辆车 <b>非钢轨 draws 284 → 40（−86%）</b>、<code>submit</code> 2.72 → 0.85–1.04 ms/帧、
 * 稳态 57–66 FPS；且用户已肉眼确认<b>车门 / 雨刷 / 车灯</b>画面不变（notes/399 §5.2/§5.3/§5.5）。
 * 要回到改动前的行为：{@code -Dmmtr.vehiclemerge=false}（逐部件 upload，一个部件一次 draw）。</p>
 *
 * <p><b>"归一化 + 合桶"搬到工作线程默认开启</b>（notes/400 §8）。
 * 要回到"全在渲染线程上算"：{@code -Dmmtr.meshbakeasync=false}。</p>
 */
public final class MmtrVehicleMeshMerger {

	private static final boolean MERGE_ENABLED = Boolean.parseBoolean(System.getProperty("mmtr.vehiclemerge", "true"));

	/**
	 * 后台那一半（{@link #bake}）默认开启（notes/400 §8）。
	 *
	 * <p>它与 {@code mmtr.modelparseasync} 是两条独立的搬法：那条搬的是"OBJ 文本 → 源网格"，
	 * 这条搬的是"源网格（已烘位置）→ 归一化 + 合桶"。关掉这一条，几何那一段就回到渲染线程
	 * （也就是 notes/400 §7 之后剩下那 50–166 ms 里的一大部分）。</p>
	 */
	private static final boolean BAKE_ASYNC_ENABLED = Boolean.parseBoolean(System.getProperty("mmtr.meshbakeasync", "true"));

	/**
	 * 与 {@code OptimizedModel.DEFAULT_MAPPING} 逐条一致的顶点映射（车辆不需要逐顶点光照，
	 * 所以这里不像钢轨那样把 {@code UV_LIGHTMAP} 改成 {@code VERTEX_BUFFER}）。
	 */
	private static final VertexAttributeMapping MAPPING = new VertexAttributeMapping.Builder()
			.set(VertexAttributeType.POSITION, VertexAttributeSource.VERTEX_BUFFER)
			.set(VertexAttributeType.COLOR, VertexAttributeSource.GLOBAL)
			.set(VertexAttributeType.UV_TEXTURE, VertexAttributeSource.VERTEX_BUFFER)
			.set(VertexAttributeType.UV_OVERLAY, VertexAttributeSource.GLOBAL)
			.set(VertexAttributeType.UV_LIGHTMAP, VertexAttributeSource.GLOBAL)
			.set(VertexAttributeType.NORMAL, VertexAttributeSource.VERTEX_BUFFER)
			.set(VertexAttributeType.MATRIX_MODEL, VertexAttributeSource.GLOBAL)
			.build();

	/** {@code ObjModel.rawModel} 是私有的、映射库没给 getter（与钢轨那边同一条路）。 */
	private static Field rawModelField;
	private static boolean rawModelFieldResolved;
	private static boolean unavailable;
	private static boolean bakeFailureLogged;

	/** 合并日志的上限（见 {@code upload} 里的说明）；这是"输出有界"的探针纪律。 */
	private static final int MAX_MERGE_LOGS = 400;
	private static int mergedLogCount;

	private MmtrVehicleMeshMerger() {
	}

	public static boolean isEnabled() {
		return MERGE_ENABLED && !unavailable;
	}

	/** 后台那一半开没开（调用方决定要不要造 {@code MmtrAsyncModelParse}）。 */
	public static boolean isBakeAsyncEnabled() {
		return BAKE_ASYNC_ENABLED;
	}

	/** "复制 → 归一化副本"这条路走不走得通（映射库那个私有字段取得到）。 */
	public static boolean canBake() {
		return rawModelField() != null;
	}

	/**
	 * 后台那一半的产物：可以原样交给渲染线程去 upload 的一组 {@code RawModel}。
	 *
	 * <p>它<b>不是</b>源几何，而是复制出来的副本 —— 渲染线程拿到它之后，源几何仍然只读，
	 * 别的路径（门批次、逐部件门）可以随时再去读源几何。</p>
	 */
	public static final class Baked {

		/** 非合并模式：列表里每一项一份（与 {@code fromObjModels} 的"每项一次 upload"逐项对应）。 */
		@Nullable
		private final ObjectArrayList<RawModel> pieces;
		/** 合并模式：所有项 append 进同一个 RawModel（按材质合桶）。 */
		@Nullable
		private final RawModel merged;
		private final int sourceCount;

		private Baked(@Nullable ObjectArrayList<RawModel> pieces, @Nullable RawModel merged, int sourceCount) {
			this.pieces = pieces;
			this.merged = merged;
			this.sourceCount = sourceCount;
		}

		public int sourceCount() {
			return sourceCount;
		}
	}

	/**
	 * <b>后台那一半</b>（纯 CPU、无 GL、不改源几何）：逐项复制 → {@code generateNormals()} →
	 * {@code distinct()}；合并模式下再 append 进一个按材质合桶的 {@code RawModel}。
	 *
	 * <p>步骤与顺序与映射库 {@code fromObjModels} 里那两步逐条对齐（法线必须在去重<b>之前</b>：
	 * 去重是按"位置+法线+uv"判等的，反过来会改变去重结果）。</p>
	 *
	 * <p>这个方法**不抛**：它是交给工作线程跑的，失败了要在调用方那一侧变成"回退到渲染线程上同步算"，
	 * 而不是让一个异常穿过线程边界。</p>
	 *
	 * @return {@code null} = 这条路走不通（映射库字段取不到），调用方必须回退到渲染线程上的老路
	 */
	@Nullable
	public static Baked bake(@Nullable ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper> objModels) {
		if (objModels == null) {
			return null;
		}
		final Field field = rawModelField();
		if (field == null) {
			return null;
		}

		final boolean merge = MERGE_ENABLED;
		final ObjectArrayList<RawModel> pieces = merge ? null : new ObjectArrayList<>();
		final RawModel merged = merge ? new RawModel() : null;
		int sourceCount = 0;

		for (final OptimizedModelWrapper.ObjModelWrapper wrapper : objModels) {
			if (wrapper == null || wrapper.objModel == null) {
				continue;
			}
			final RawModel source;
			try {
				source = (RawModel) field.get(wrapper.objModel);
			} catch (IllegalAccessException exception) {
				/*
				 * setAccessible(true) 已经做过，这里失败说明映射库或安全策略变了 ⇒ 整条"复制"路停用，
				 * 调用方回退到映射库的原路（就地归一化，画面一致）。绝不静默。
				 */
				unavailable = true;
				if (!bakeFailureLogged) {
					bakeFailureLogged = true;
					Init.LOGGER.warn("[MMTR-VEHMERGE] 读不到 OptimizedModel$ObjModel.rawModel —— 车辆几何的\"复制后归一化\"停用，回退映射库原路（画面不受影响）", exception);
				}
				return null;
			}
			if (source == null) {
				continue;
			}
			sourceCount++;

			/*
			 * 复制：append 把源的每个材质桶搬进副本（顶点是引用，面是副本并平移索引）。
			 * 之后所有就地操作都落在副本上 —— 源几何从 writeCache 之后就只有人读、没有人写。
			 */
			final RawModel copy = new RawModel();
			source.iterateRawMeshList(copy::append);

			copy.generateNormals();
			copy.distinct();

			if (merge) {
				// merged 只做浅拷贝（顶点引用 + 面副本），所以之后没人再改 copy 也不影响它。
				copy.iterateRawMeshList(merged::append);
			} else {
				pieces.add(copy);
			}
		}

		return new Baked(pieces, merged, sourceCount);
	}

	/**
	 * <b>渲染线程那一半</b>：只做 {@code upload()}（建 GL buffer）与包装，不再碰任何源几何。
	 *
	 * @return {@code null} = 这一份拿不到结果（合并后 0 个材质 / 没开优化渲染），
	 *         调用方必须回退到 {@code OptimizedModelWrapper.fromObjModels}
	 */
	@Nullable
	public static OptimizedModelWrapper upload(Baked baked) {
		if (!OptimizedRenderer.hasOptimizedRendering()) {
			// 没开优化渲染时一条 GL 都不该碰（老路上 merge 会在这种配置下照旧 upload，是个疣；这里挡掉）。
			return null;
		}

		final List<VertexArray> parts;
		if (baked.merged != null) {
			parts = baked.merged.upload(MAPPING);
			if (parts.isEmpty()) {
				/*
				 * 这是**唯一一条静默回退**（notes/400）：返回 null 之后调用方会去 fromObjModels，
				 * 而"合并失败"那条 warn 只在抛异常时打 ⇒ 以前这条路走过去是没有日志的，
				 * 现场表现就是"没有任何 [MMTR-VEHMERGE] 行，但 draw 数换了一套几何"。
				 * 现在把它打出来（含部件数），否则判定性埋点会看到 draw 数翻转却查不到来源。
				 */
				Init.LOGGER.warn("[MMTR-VEHMERGE] 合并后 upload 得到 0 个材质（{} 个部件）—— 这一份回退逐部件 upload", baked.sourceCount);
				return null;
			}
			/*
			 * 日志有界（探针纪律）：notes/400 之后这条路**每个位置**都会走一次（门/雨刷），
			 * 一辆车就能出几十上百行，所以到上限后只留一条说明，不再逐条打。
			 */
			if (mergedLogCount < MAX_MERGE_LOGS) {
				mergedLogCount++;
				Init.LOGGER.info("[MMTR-VEHMERGE] 合并 {} 个部件 → {} 个材质（= {} 次 draw）", baked.sourceCount, parts.size(), parts.size());
				if (mergedLogCount == MAX_MERGE_LOGS) {
					Init.LOGGER.info("[MMTR-VEHMERGE] 合并日志已达 {} 条上限，后续不再逐条输出（合并仍在正常进行）", MAX_MERGE_LOGS);
				}
			}
		} else if (baked.pieces != null) {
			final ObjectArrayList<VertexArray> collected = new ObjectArrayList<>();
			for (final RawModel piece : baked.pieces) {
				collected.addAll(piece.upload(MAPPING));
			}
			parts = collected;
		} else {
			return null;
		}

		return OptimizedModelWrapper.fromOptimizedModel(new OptimizedModel(parts));
	}

	/**
	 * 合并；任何一步出问题就**回退**到原来的 {@code fromObjModels}，绝不向上抛
	 * （调用点在模型构建路径上，抛出去会让整辆车不渲染）。
	 */
	public static OptimizedModelWrapper mergeOrFallback(@Nullable ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper> objModels) {
		if (objModels == null) {
			return OptimizedModelWrapper.fromObjModels(null);
		}
		try {
			final OptimizedModelWrapper merged = mergeOrNull(objModels);
			if (merged != null) {
				return merged;
			}
		} catch (Exception exception) {
			unavailable = true;
			Init.LOGGER.warn("[MMTR-VEHMERGE] 车辆按材质合并失败 —— 停用合并、回退逐部件 upload（画面不受影响）", exception);
		}
		return OptimizedModelWrapper.fromObjModels(objModels);
	}

	/**
	 * 同步版的"复制 → 归一化副本 → upload"（合并那一段可以整体不要）。
	 *
	 * <p>门批次（{@code MmtrDoorBatch}）用它：它是在**渲染中**跑的，而那时可能正有一份后台烘焙
	 * 在读同一批源几何 —— 老实现就地改源几何，两者会撞上；这里只读源、只改副本。</p>
	 *
	 * @return {@code null} = 走不通（映射库字段取不到、没开优化渲染、合并后 0 个材质），
	 *         调用方必须回退逐部件排队
	 */
	@Nullable
	public static OptimizedModelWrapper mergeOrNull(@Nullable ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper> objModels) {
		if (objModels == null) {
			return null;
		}
		try {
			final Baked baked = bake(objModels);
			return baked == null ? null : upload(baked);
		} catch (Exception exception) {
			unavailable = true;
			Init.LOGGER.warn("[MMTR-VEHMERGE] 车辆按材质合并失败 —— 停用合并、回退逐部件 upload（画面不受影响）", exception);
			return null;
		}
	}

	/** {@code ObjModel.rawModel} —— 取不到就把整条"复制"路停掉（调用方回退到映射库原路）。 */
	@Nullable
	private static Field rawModelField() {
		if (!rawModelFieldResolved) {
			rawModelFieldResolved = true;
			try {
				final Field field = OptimizedModel.ObjModel.class.getDeclaredField("rawModel");
				field.setAccessible(true);
				rawModelField = field;
			} catch (Exception exception) {
				unavailable = true;
				Init.LOGGER.warn("[MMTR-VEHMERGE] 取不到 OptimizedModel$ObjModel.rawModel —— 车辆按材质合并停用（回退逐部件 upload）", exception);
			}
		}
		return rawModelField;
	}
}
