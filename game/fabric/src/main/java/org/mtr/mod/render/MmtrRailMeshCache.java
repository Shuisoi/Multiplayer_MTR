package org.mtr.mod.render;

import org.mtr.core.data.Rail;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.ClientWorld;
import org.mtr.mapping.holder.LightType;
import org.mtr.mapping.holder.LightmapTextureManager;
import org.mtr.mapping.holder.Matrix4f;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mapping.mapper.OptimizedRenderer;
import org.mtr.mapping.render.model.Face;
import org.mtr.mapping.render.model.RawMesh;
import org.mtr.mapping.render.model.RawModel;
import org.mtr.mapping.render.object.VertexArray;
import org.mtr.mapping.render.tool.GlStateTracker;
import org.mtr.mapping.render.vertex.Vertex;
import org.mtr.mapping.render.vertex.VertexAttributeMapping;
import org.mtr.mapping.render.vertex.VertexAttributeSource;
import org.mtr.mapping.render.vertex.VertexAttributeType;
import org.mtr.mod.Init;
import org.mtr.mod.resource.OptimizedModelWrapper;
import org.mtr.mod.resource.RailResource;
import org.joml.Quaternionf;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 钢轨的**合并烘焙缓存** —— 把"一根轨的全部模型实例"烘成一个模型、一帧只排一次队（notes/398）。
 *
 * <h2>要解决的问题</h2>
 *
 * <p>MTR 的优化渲染器是**按材质分批、但不做实例化**的：{@code BatchManager.queue(List<VertexArray>)}
 * 会给列表里**每一个** {@code VertexArray} 建一条 {@code RenderCall}，而
 * {@code RenderCall.draw()} 就是一次 {@code glDrawElements}（映射库字节码已确认，非 instanced）。</p>
 *
 * <p>钢轨没有持久网格：{@code RenderRails} 每帧沿 {@code railMath} 曲线重新生成实例，
 * 每 {@code repeatInterval} 米一个。100 m 的节点段 ≈ 200 个实例 ⇒ 200 次 draw。
 * 实测 {@code draws/frame ≈ 767} ⇒ 约 4 根长轨把 {@code submit} 顶到 5 ms。</p>
 *
 * <h2>做法</h2>
 *
 * <p>钢轨是**静态**的（只有玩家编辑线路、或世界光照变化才会变），所以可以像原版区块网格一样
 * <b>只建一次、之后每帧复用</b>：</p>
 * <ol>
 *   <li>从 {@link RailResource} 拿到**源几何**（{@code RawMesh}，见
 *       {@link org.mtr.mod.resource.StoredModelResourceBase#onObjModelsReady}）；</li>
 *   <li>沿曲线走一遍，把每个实例的变换**烘进顶点**（顶点存的是"相对本轨 AABB 原点"的坐标，
 *       避免大世界坐标落进 float 精度）；</li>
 *   <li>按材质合桶后 {@code upload} 成 VBO —— <b>一个材质一个 {@code VertexArray} = 一次 draw</b>；</li>
 *   <li>每帧只 {@code queue} 一次，ModelMat 退化成"把本轨原点搬到相机相对位置"的纯平移。</li>
 * </ol>
 *
 * <h2>光照为什么能逐顶点</h2>
 *
 * <p>{@code OptimizedModel.DEFAULT_MAPPING} 把 {@code UV_LIGHTMAP} 定成 {@code GLOBAL}（每 draw 一个常量），
 * 但那只是 MTR 的默认值 —— {@code VertexAttributeMapping.Builder} 是公有的，这里改成
 * {@code VERTEX_BUFFER}，把光照烘进顶点，<b>与原版区块网格完全同构</b>。</p>
 *
 * <p>格式也对得上：{@code VertexAttributeType.UV_LIGHTMAP} 是 location 4、{@code GL_SHORT}×2、
 * {@code iPointer=true}（整数属性，非归一化）—— 这正是原版 {@code rendertype_entity_*} 里
 * {@code in ivec2 UV2} 的槽位与类型。而 {@code PatchingResourceProvider} 只给原版 entity 着色器
 * 打 {@code ModelMat} 的补丁，<b>没有动 {@code UV2}</b> ⇒ 逐顶点光照本来就被着色器读。</p>
 *
 * <h2>失效与重建</h2>
 *
 * <ul>
 *   <li><b>几何</b>：键里带 {@code rail.getHexId()} + 本轨 AABB ⇒ 节点被改动就是另一个键，天然失效。</li>
 *   <li><b>光照</b>：每帧最多复检**一根**轨（轮转），重算光照哈希；变了才标脏、才重建。
 *       天空光/方块光都是**离散档位**，所以实际重建次数远低于"每帧"。</li>
 *   <li><b>预算</b>：每 pass 最多重建 {@link #MAX_BAKES_PER_PASS} 根，且标脏后**继续画旧的** ——
 *       宁可光照过时一帧，也不要钢轨闪掉。</li>
 * </ul>
 *
 * <h2>开关</h2>
 *
 * <p>{@code -Dmmtr.railbake=false} 可整条关掉（回退逐实例渲染，画面一致，只是性能回到改前）。
 * 拿不到源几何（{@code .bbmodel}、映射库字段改名）时也会自动回退。</p>
 */
public final class MmtrRailMeshCache {

	/** 与 {@code RenderRails.LIGHT_REFERENCE_OFFSET} 同一个值：取光照时把参考点抬高一点。 */
	private static final double LIGHT_REFERENCE_OFFSET = 0.1;

	/** 缓存最多留多少项（一项 = 一根轨的一个样式）。超了按"最久没用"淘汰并释放 GL 资源。 */
	private static final int MAX_ENTRIES = 96;
	/**
	 * 每个 pass 最多新建/重建几个 —— 防"第一次进世界 / 天亮"时一次卡住。
	 *
	 * <p>2026-10-08（notes/401）**试过降到 1，读数把它否掉了**：把烘焙里的纯 CPU 那半搬到
	 * {@code MMTR-RailBake} 之后，渲染线程每烘一根只剩 `取光 0 ms + upload 13–35 ms`
	 * （见 {@code [MMTR-RAILBAKE] 后台烘焙分段}），所以"每 pass 烘 2 根"的上界是 26–70 ms —— 不大。
	 * 而**真正贵的是"还没烘好的轨走逐实例渲染"那一段**：进世界那一瞬实测
	 * {@code 钢轨 draws=293.7(0.62 批)}（0.62 批 = 几乎全是逐实例）与
	 * {@code shadow.rails max=386 ms} —— 那是**冷缓存**的代价，跟烘焙本身无关。
	 * 把每 pass 的额度降到 1 只会让缓存热得更慢、逐实例那一段拖得更久（一个 pass 烘 2 根 = 一帧 4 根，
	 * 42 根约 11 帧热完；降到 1 就要 ~21 帧）。所以**保持 2**，同时把额度做成系统属性：
	 * 想再压单帧尖峰可以 {@code -Dmmtr.railbake.perpass=1}（代价就是上面那段更久）。</p>
	 */
	private static final int MAX_BAKES_PER_PASS = intProperty("mmtr.railbake.perpass", 2);
	/** 每个 pass 最多复检几根轨的光照。 */
	private static final int MAX_LIGHT_CHECKS_PER_PASS = 1;
	/**
	 * 同时最多有几份后台烘焙作业在飞。
	 *
	 * <p>为什么要这个数：作业在 build 阶段持有一份 {@code RawModel}（440 个实例 ≈ 4–5 MB 顶点对象），
	 * 进世界那一瞬实测一次要烘 42 根 ⇒ 不限并发就是 ~200 MB 的峰值。有界之后超出的一律"这一帧照旧"
	 * （继续逐实例渲染），下几帧再轮。</p>
	 */
	private static final int MAX_PENDING_JOBS = 3;
	/** 后台作业多久没人问就丢掉（轨离开视野后不该留着占内存）。 */
	private static final int PENDING_JOB_TIMEOUT_FRAMES = 120;
	/** 同一根轨两次光照复检之间至少隔多少帧。 */
	private static final int LIGHT_CHECK_MIN_FRAMES = 10;
	/** 每多少帧打一行汇总（主 pass 计数 ⇒ 60 fps 下约 5 秒）。 */
	private static final int LOG_FRAME_INTERVAL = 300;

	/**
	 * 一次烘焙内部的**分段读数**（notes/401 §7）。
	 *
	 * <p>为什么要它：`[MMTR-FRAME]` 只告诉我们 `shadow.rails max=255 ms`，而一次烘焙里混了三件性质完全不同的事 ——
	 * **走曲线 + 逐实例取世界光照**（`RailMath.render` + `clientWorld.getLightLevel`）、
	 * **逐实例矩阵 + 逐顶点深拷贝 + 按材质合桶**（纯 CPU、可以搬到工作线程）、
	 * **`upload()` 建 VBO**（必须在渲染线程）。不分开量就不知道该把哪一段搬走，
	 * 更不知道"取光照"要不要跟着搬（它碰世界数据，是这三段里唯一有线程安全问题的）。</p>
	 *
	 * <p>纪律：只在**这一段总耗时 ≥ {@link #BAKE_LOG_MIN_MILLIS}** 时打一条，总数封顶
	 * {@link #MAX_BAKE_SEGMENT_LOGS} 条 —— 烘焙本身很频繁（实测 300 帧里 42 次），逐条打会淹掉日志。</p>
	 */
	private static final long BAKE_LOG_MIN_MILLIS = 12;
	private static final int MAX_BAKE_SEGMENT_LOGS = 80;
	private static int bakeSegmentLogCount;

	/** `walk()` 里"查世界光照"那一段的累计纳秒：{@code walk} 累加，{@code bakeInner} 在走之前清零、走完取走。 */
	private static long walkLightNanos;

	/** 光照哈希的两个常量：{@code MmtrRailBakeJob} 在后台算同一套哈希，所以是包内可见的。 */
	static final long LIGHT_HASH_SEED = 1125899906842597L;
	static final long LIGHT_HASH_MULTIPLIER = 1099511628211L;
	private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("mmtr.railbake", "true"));

	/**
	 * 逐顶点光照的顶点映射：就是 {@code DEFAULT_MAPPING} 把 {@code UV_LIGHTMAP} 换成
	 * {@code VERTEX_BUFFER}，其余**一格不动**（颜色/overlay/ModelMat 仍然每 draw 一个常量）。
	 */
	private static final VertexAttributeMapping MAPPING = new VertexAttributeMapping.Builder()
			.set(VertexAttributeType.POSITION, VertexAttributeSource.VERTEX_BUFFER)
			.set(VertexAttributeType.COLOR, VertexAttributeSource.GLOBAL)
			.set(VertexAttributeType.UV_TEXTURE, VertexAttributeSource.VERTEX_BUFFER)
			.set(VertexAttributeType.UV_OVERLAY, VertexAttributeSource.GLOBAL)
			.set(VertexAttributeType.UV_LIGHTMAP, VertexAttributeSource.VERTEX_BUFFER)
			.set(VertexAttributeType.NORMAL, VertexAttributeSource.VERTEX_BUFFER)
			.set(VertexAttributeType.MATRIX_MODEL, VertexAttributeSource.GLOBAL)
			.build();

	/** 一张烘焙好的钢轨模型 + 摆放它需要的原点。 */
	public static final class Baked {

		/** 合并后的模型。 */
		@Nullable
		public final OptimizedModelWrapper model;
		/** 顶点坐标的参考点（本轨 AABB 最小角）。每帧 ModelMat = 纯平移 {@code origin - 相机位置}。 */
		public final double originX;
		public final double originY;
		public final double originZ;

		private final long lightHash;
		private long lastUsedFrame;
		private long lastLightCheckFrame;
		private boolean lightDirty;

		private Baked(@Nullable OptimizedModelWrapper model, double originX, double originY, double originZ, long lightHash) {
			this.model = model;
			this.originX = originX;
			this.originY = originY;
			this.originZ = originZ;
			this.lightHash = lightHash;
		}
	}

	/*
	 * 这里曾经有一个 UNBAKEABLE 哨兵（"这根轨这个样式烘不出来，别再重试"），2026-10-05 删掉了。
	 *
	 * 它会在**资源懒加载之前**就把键永久毒住：第一帧跑到这里时 RailResource 还没 load 完，
	 * getBakeSourceObjModels() 还是 null，于是这一帧就被记成"永远烘不了"，之后再也不试 ——
	 * 实机日志里"缓存 9/96 项却只有 5 次失败"就是这个坑的痕迹。
	 *
	 * 现在改成每帧做一次**廉价的**空检查（hasBakeSource）：没有源几何就这一帧回退，
	 * 下一帧资源到位了自然就能烘。而且这个检查在**消耗烘焙预算之前**，所以"烘不了的样式"
	 * 不会把预算吃光、把真正能烘的轨挤掉。
	 */

	/** 缓存键：一支资源 + 一根轨 + 一个样式。{@code resource} 按**身份**比（资源重载后是新对象）。 */
	private static final class Key {

		private final RailResource resource;
		private final String hexId;
		private final String style;
		private final double minX;
		private final double minY;
		private final double minZ;
		private final double maxX;
		private final double maxY;
		private final double maxZ;

		private Key(RailResource resource, String hexId, String style, Rail rail) {
			this.resource = resource;
			this.hexId = hexId;
			this.style = style;
			minX = rail.railMath.minX;
			minY = rail.railMath.minY;
			minZ = rail.railMath.minZ;
			maxX = rail.railMath.maxX;
			maxY = rail.railMath.maxY;
			maxZ = rail.railMath.maxZ;
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof Key)) {
				return false;
			}
			final Key key = (Key) other;
			return resource == key.resource
					&& hexId.equals(key.hexId)
					&& style.equals(key.style)
					&& minX == key.minX && minY == key.minY && minZ == key.minZ
					&& maxX == key.maxX && maxY == key.maxY && maxZ == key.maxZ;
		}

		@Override
		public int hashCode() {
			int result = System.identityHashCode(resource);
			result = 31 * result + hexId.hashCode();
			result = 31 * result + style.hashCode();
			result = 31 * result + Double.hashCode(minX);
			result = 31 * result + Double.hashCode(minY);
			result = 31 * result + Double.hashCode(minZ);
			result = 31 * result + Double.hashCode(maxX);
			result = 31 * result + Double.hashCode(maxY);
			result = 31 * result + Double.hashCode(maxZ);
			return result;
		}
	}

	@FunctionalInterface
	private interface InstanceSink {
		void accept(double x1, double z1, double x2, double z2, double x3, double z3, double x4, double z4, double y1, double y2, int light);
	}

	private static final Map<Key, Baked> ENTRIES = new HashMap<>();

	/** 在飞的后台烘焙作业（notes/401）：同一个键只允许一份，upload 掉或超时/失败才移除。 */
	private static final Map<Key, MmtrRailBakeJob> PENDING = new HashMap<>();

	/**
	 * 后台那条路出过不可恢复的错 ⇒ 整条切回渲染线程上同步烘（老路）。
	 *
	 * <p>与 {@code sourceUnavailable} 同一套纪律：绝不静默、绝不半成品，也绝不每帧重试同一件坏事。</p>
	 */
	private static boolean asyncUnavailable;
	private static int asyncUploadsInWindow;

	private static long frame;
	private static int bakesThisPass;
	private static int lightChecksThisPass;
	private static int bakesInWindow;
	private static int lightRebuildsInWindow;

	/**
	 * 最近一次烘焙的规模 —— 这是"合并真的生效了"的**直接证据**，不用只看 draw 总数去反推：
	 * "N 个实例 → M 个材质"，而 M 就是那次烘焙的 draw 数（映射库 {@code RawModel.upload}
	 * 是每个带面的 {@code RawMesh} 造一个 {@code VertexArray}，而 {@code BatchManager.queue}
	 * 给每个 {@code VertexArray} 建一条 {@code RenderCall}）。
	 */
	private static int lastInstanceCount = -1;
	private static int lastMaterialCount = -1;

	/** 整条路径不可用的原因（拿不到源几何 / 失败太多次）；一旦置位就再也不试，只回退。 */
	private static boolean sourceUnavailable;

	/** 攒够这么多次失败就自动停用整条合并烘焙（防止每帧刷日志 + 每帧白算）。 */
	private static final int MAX_BAKE_FAILURES = 5;
	private static int bakeFailures;

	private static Field rawModelField;
	private static boolean rawModelFieldResolved;

	/** 同理：{@code GlStateTracker.isStateProtected} 也是私有的，用它决定"该不该由我们收尾"。 */
	private static Field glStateProtectedField;
	private static boolean glStateProtectedFieldResolved;

	private MmtrRailMeshCache() {
	}

	/**
	 * 每个 pass 开头调一次（主 pass 与阴影 pass 都调）。
	 *
	 * <p>预算（本 pass 建几个、复检几根）**逐 pass 重置** —— 否则阴影 pass 会白蹭主 pass 用剩的额度。
	 * 帧号只在主 pass 递增，因为一个视觉帧恰好一个主 pass（见 {@code MmtrFrameProbe} 的 pass 口径）。</p>
	 *
	 * @param shadowPass 本 pass 是不是阴影 pass
	 */
	public static void beginPass(boolean shadowPass) {
		bakesThisPass = 0;
		lightChecksThisPass = 0;
		if (shadowPass) {
			return;
		}
		frame++;
		if (frame % LOG_FRAME_INTERVAL == 0) {
			Init.LOGGER.info("[MMTR-RAILBAKE] 窗口 {} 帧：新建烘焙 {} 次、其中光照重建 {} 次 ｜ 后台 upload {} 次 / 在飞 {} 项 ｜ 缓存 {}/{} 项 ｜ 最近一次烘焙：{} 个实例 → {} 个材质（= {} 次 draw）{}",
					LOG_FRAME_INTERVAL, bakesInWindow, lightRebuildsInWindow, asyncUploadsInWindow, PENDING.size(), ENTRIES.size(), MAX_ENTRIES,
					lastInstanceCount < 0 ? "—" : String.valueOf(lastInstanceCount),
					lastMaterialCount < 0 ? "—" : String.valueOf(lastMaterialCount),
					lastMaterialCount < 0 ? "—" : String.valueOf(lastMaterialCount),
					describeState());
			bakesInWindow = 0;
			lightRebuildsInWindow = 0;
			asyncUploadsInWindow = 0;
		}
		evictIfNeeded();
	}

	/**
	 * 取这根轨这个样式的烘焙模型；没有就按预算现烘一个。
	 *
	 * @return {@code null} = 烘不出来（或本 pass 预算用完且还没烘过）⇒ <b>调用方必须回退逐实例渲染</b>
	 */
	@Nullable
	public static Baked get(ClientWorld clientWorld, Rail rail, RailResource railResource, String style, boolean flip) {
		if (!ENABLED || sourceUnavailable || !OptimizedRenderer.hasOptimizedRendering()) {
			return null;
		}
		/*
		 * ★ 最外层兜底 —— notes/396 的教训。
		 *
		 * <p>这个方法跑在**渲染线程的主链**上（{@code MainRenderer.render → RenderRails.render → …}）。
		 * 异常一旦穿出去，{@code RenderRails} 之后的一切 —— 队列派发、{@code MmtrLightField.beginFrame}、
		 * 优化批次提交 —— **全都不会跑**。症状不是"掉几帧"，而是**钢轨乃至更多东西整片消失**
		 * （2026-10-05 已经因为这个踩过一次：一个面板签名的 NPE 让所有钢轨不见了）。</p>
		 *
		 * <p>所以这里的任何失败都必须退化成"返回 null = 回退逐实例渲染"，绝不向上抛。</p>
		 */
		try {
			return resolve(clientWorld, rail, railResource, style, flip);
		} catch (Exception exception) {
			reportFailure("解算钢轨烘焙缓存", exception);
			return null;
		}
	}

	@Nullable
	private static Baked resolve(ClientWorld clientWorld, Rail rail, RailResource railResource, String style, boolean flip) {
		final Key key = new Key(railResource, rail.getHexId(), style, rail);
		final Baked cached = ENTRIES.get(key);
		if (cached != null) {
			cached.lastUsedFrame = frame;
			checkLightIfDue(clientWorld, rail, railResource, cached);
			if (!cached.lightDirty) {
				return cached;
			}
		}

		/*
		 * ★ 先做一次**不消耗预算、不留永久标记**的廉价判断：这个资源现在到底有没有可烘的源几何。
		 *
		 * <p>两个理由，都会真的咬人：</p>
		 *
		 * <ol>
		 *   <li><b>不能记成"永久烘不了"。</b>RailResource 的源几何是**懒加载**的：第一帧跑到这里时
		 *       {@link RailResource#getBakeSourceObjModels()} 很可能还是 {@code null}（还没 load 完），
		 *       下一帧就有了。若此时把键记成"永远烘不了"，它就被**永久毒住**了。
		 *       2026-10-05 实机日志里「缓存 9/96 项」却只有 5 次失败，正是这个坑留下的痕迹
		 *       —— 另外 4 项就是被这样毒住、且一次异常都没抛过。</li>
		 *   <li><b>不能消耗预算。</b>每 pass 只允许烘 {@link #MAX_BAKES_PER_PASS} 根。若把预算花在
		 *       "根本没有源几何"的样式上（例如资源包里把钢轨做成了 {@code .bbmodel}），
		 *       同一帧里**真正能烘的轨就永远轮不到** —— 会退化成"优化时灵时不灵"。</li>
		 * </ol>
		 *
		 * <p>判断本身只是两个空检查（外加一次反射字段读），每帧做也不花钱。</p>
		 */
		if (!hasBakeSource(railResource)) {
			return null;
		}

		/*
		 * ============================================================
		 * 后台那条路（notes/401）：走曲线 + 矩阵/克隆/合桶在工作线程上做，
		 * 渲染线程只剩"逐实例取光"（实测 0 ms）与 upload()。
		 *
		 * 预算语义也跟着换了个位置：老路上"每 pass 最多烘 N 根"限的是**整次烘焙**；
		 * 现在作业在后台跑，渲染线程上真正贵的是最后的 upload()，所以预算限的是 **upload 的次数**
		 * —— 后台可以多跑几份，但一个 pass 只把 N 份搬上 GPU（否则就又把一帧顶住了）。
		 * ============================================================
		 */
		if (MmtrRailBakeJob.isEnabled() && !asyncUnavailable) {
			MmtrRailBakeJob job = PENDING.get(key);
			if (job == null) {
				if (PENDING.size() >= MAX_PENDING_JOBS) {
					// 在飞的够多了：这一帧不新开（内存与工作线程都要有界），继续画旧的/逐实例
					return cached;
				}
				job = new MmtrRailBakeJob(
						style + "@" + rail.getHexId(),
						rail.railMath,
						railResource.getRepeatInterval(),
						key.minX, key.minY, key.minZ,
						railResource.getModelYOffset(),
						flip,
						sourceMeshesOf(railResource),
						LIGHT_REFERENCE_OFFSET
				);
				PENDING.put(key, job);
				/*
				 * 新建的作业也要**立刻**打上"本帧刚被问过"。
				 *
				 * <p>2026-10-09 实机：这里漏了 markUsed，而 `lastUsedFrame` 的初值是 0，
				 * {@link #evictIfNeeded()} 的判据是 {@code frame - lastUsedFrame > 120} ——
				 * 于是进世界 2 秒之后（frame > 120），**每个新建作业都在下一个 beginPass 被 LRU 删掉**，
				 * 而删发生在任何一次 {@code resolve()} 之前 ⇒ {@code advance()} 永远轮不到它，
				 * 作业永远完不成。实测判据（342 条 [MMTR-RAILBAKE] 汇总行）：</p>
				 * <pre>
				 *   通行=1.00（无光影，只有一个主 pass）：新建=0 upload=0 在飞=2 缓存=61  ← 92 个窗口全是这样
				 *   所有 upload&gt;0 的窗口 100% 是 通行=2.00（阴影+主两个 pass）← 作业靠阴影 pass 里的 resolve 续命
				 *   后台线程 18656 条「走曲线」vs 只有 195 条「合桶」⇒ 99% 的作业死在 advance() 之前
				 * </pre>
				 * <p>后果不是"慢一点"，而是：缓存被冻在"开光影那段时间攒下的那 61 根"上，之后遇到的、
				 * 不在这个集合里的轨永远烘不出来，只能走逐实例回退 —— 而回退路里跑着另一套逐实例视锥剔除，
				 * 已烘的 61 根却完全不跑。画面里于是同时有两套可见性规则，边走边切换（"轨/车随移动不规律
				 * 消失"的观感）。上面 ENTRIES 那条注释（{@link #recordRebuild}）踩的是同一个坑的
				 * 另一半：**凡是有 lastUsedFrame 的东西，诞生时就得写上当前的 frame**。</p>
				 */
				job.markUsed(frame);
				return cached;
			}

			job.markUsed(frame);
			final boolean ready;
			try {
				ready = job.advance(clientWorld);
			} catch (Throwable throwable) {
				PENDING.remove(key);
				job.reportFailure("推进后台作业", throwable);
				asyncUnavailable = true;
				return cached;
			}
			if (!ready) {
				// 还没好：这一帧照旧（有旧的画旧的，没有就回退逐实例渲染）
				return cached;
			}
			if (bakesThisPass >= MAX_BAKES_PER_PASS) {
				// 预算用完：作业留着，下一帧再 upload（比老路还稳：后台那份已经算好了）
				return cached;
			}
			bakesThisPass++;
			PENDING.remove(key);

			final MmtrRailBakeJob.Result result = job.takeResult();
			if (MmtrRailBakeJob.shouldVerify()) {
				// 一次性等价性自检：同一根轨在老路上再算一遍几何（不上传），比摘要
				final SyncBuilt syncBuilt = buildSync(clientWorld, rail, railResource, key, flip);
				MmtrRailBakeJob.verifyAgainst(result.merged, syncBuilt == null ? null : syncBuilt.merged, style + "@" + rail.getHexId());
			}
			final long uploadStartNanos = System.nanoTime();
			final Baked rebuilt = upload(result.merged, key, result.lightHash, result.instanceCount, cached);
			final long uploadNanos = System.nanoTime() - uploadStartNanos;
			if (rebuilt == null) {
				return cached;
			}
			if (rebuilt == cached) {
				/*
				 * 后台这条路**必须**和同步老路一样挡住"产出了 0 个材质桶"：{@code upload()} 在
				 * {@code parts.isEmpty()} 时返回的就是传进去的 {@code previous}（= cached），
				 * 而 {@link #recordRebuild} 会 {@code ENTRIES.put(key, rebuilt)} 再把
				 * {@code cached.model.close()} —— 也就是**把刚登记进缓存的那个模型释放掉**。
				 * 之后 {@code get()} 仍返回这个非 null 的 Baked，{@code RenderRails} 走烘焙分支、
				 * 压掉逐实例回退 ⇒ **这一根轨永久不画**（直到键变化）。
				 *
				 * <p>同步老路在下面用同一行判断挡住了（{@code if (rebuilt == cached)}），
				 * 后台路 2026-10-09 由审计发现漏了 —— 两条路的收尾语义必须一致，
				 * 这类"两条路各写一遍"的漂移只在极端输入下才现形。</p>
				 */
				cached.lightDirty = false;
				asyncUploadsInWindow++;
				return cached;
			}
			reportBakeSegmentsAsync(result, uploadNanos);
			asyncUploadsInWindow++;
			return recordRebuild(key, rebuilt, cached);
		}

		/*
		 * ============================================================
		 * 同步老路（`-Dmmtr.railbake.async=false`，或后台出过不可恢复的错）
		 * ============================================================
		 */
		if (bakesThisPass >= MAX_BAKES_PER_PASS) {
			// 预算用完：有旧的就把旧的接着画（光照过时一帧，好过钢轨消失）；没有就这一帧不画。
			return cached;
		}
		bakesThisPass++;

		final Baked rebuilt = bake(clientWorld, rail, railResource, key, flip, cached);
		if (rebuilt == null) {
			// 这一帧烘不出来：回退逐实例渲染。**不记任何"永久"标记** —— 见上面那段注释。
			return cached;
		}
		if (rebuilt == cached) {
			// 现烘失败但旧的还在：清掉脏标记，避免每帧重试；继续用旧的。
			cached.lightDirty = false;
			return cached;
		}
		return recordRebuild(key, rebuilt, cached);
	}

	/** 烘焙成功之后的共同收尾：记账、登记、释放被换掉的旧模型（两条路共用，避免两处逻辑漂移）。 */
	private static Baked recordRebuild(Key key, Baked rebuilt, @Nullable Baked cached) {
		// 新烘出来的项必须立刻打上"本帧刚用过 / 刚查过光照"，否则它会以 lastUsedFrame=0
		// 排在 LRU 队首，可能下一帧就被淘汰掉一个正在用的模型。
		rebuilt.lastUsedFrame = frame;
		rebuilt.lastLightCheckFrame = frame;
		ENTRIES.put(key, rebuilt);
		bakesInWindow++;
		if (cached != null) {
			lightRebuildsInWindow++;
			if (cached.model != null) {
				cached.model.close();
			}
		}
		return rebuilt;
	}

	/**
	 * 后台那条路的最后一步（**渲染线程**）：把已经算好的 {@code RawModel} 搬上 GPU。
	 *
	 * @return {@code null} = 一个材质都没产出（没有面）⇒ 调用方继续用旧的
	 */
	@Nullable
	private static Baked upload(RawModel merged, Key key, long lightHash, int instanceCount, @Nullable Baked previous) {
		// ★ 建 VAO/VBO 必须在 GlStateTracker 的保护区内，见 withProtectedGlState 的长注释。
		final List<VertexArray> parts = withProtectedGlState(() -> merged.upload(MAPPING));
		if (parts.isEmpty()) {
			return previous;
		}
		lastInstanceCount = instanceCount;
		lastMaterialCount = parts.size();
		return new Baked(OptimizedModelWrapper.fromOptimizedModel(new OptimizedModel(parts)), key.minX, key.minY, key.minZ, lightHash);
	}

	/** 这个资源现在有没有可烘的源几何。{@code false} = 这一帧回退逐实例渲染，且**不消耗烘焙预算、不留永久标记**。 */
	private static boolean hasBakeSource(RailResource railResource) {
		final ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper> wrappers = railResource.getBakeSourceObjModels();
		return wrappers != null && !wrappers.isEmpty();
	}

	/** 这一帧的光照还配得上吗 —— 轮转复检：每个 pass 最多一根，同一根至少隔 {@link #LIGHT_CHECK_MIN_FRAMES} 帧。 */
	private static void checkLightIfDue(ClientWorld clientWorld, Rail rail, RailResource railResource, Baked baked) {
		if (frame - baked.lastLightCheckFrame < LIGHT_CHECK_MIN_FRAMES || lightChecksThisPass >= MAX_LIGHT_CHECKS_PER_PASS) {
			return;
		}
		baked.lastLightCheckFrame = frame;
		lightChecksThisPass++;
		final long[] hash = {LIGHT_HASH_SEED};
		walk(clientWorld, rail, railResource.getRepeatInterval(), (x1, z1, x2, z2, x3, z3, x4, z4, y1, y2, light) -> hash[0] = hash[0] * LIGHT_HASH_MULTIPLIER + light);
		if (hash[0] != baked.lightHash) {
			baked.lightDirty = true;
		}
	}

	/**
	 * 真正烘一次：沿曲线走一遍，把每个实例的变换烘进顶点，按材质合成一个模型。
	 *
	 * @return 新建的项；失败时返回 {@code previous}（还有旧的可用）或 {@code null}（本来就没有）
	 */
	@Nullable
	private static Baked bake(ClientWorld clientWorld, Rail rail, RailResource railResource, Key key, boolean flip, @Nullable Baked previous) {
		try {
			return bakeInner(clientWorld, rail, railResource, key, flip, previous);
		} catch (Exception exception) {
			reportFailure("烘焙钢轨模型", exception);
			return previous;
		}
	}

	/** 老路那一半的产物：合并好的 {@code RawModel} + 光照哈希 + 实例数 + 三段读数。 */
	private static final class SyncBuilt {

		private final RawModel merged;
		private final long lightHash;
		private final int instanceCount;
		private final long walkNanos;
		private final long lightNanos;
		private final long sinkNanos;

		private SyncBuilt(RawModel merged, long lightHash, int instanceCount, long walkNanos, long lightNanos, long sinkNanos) {
			this.merged = merged;
			this.lightHash = lightHash;
			this.instanceCount = instanceCount;
			this.walkNanos = walkNanos;
			this.lightNanos = lightNanos;
			this.sinkNanos = sinkNanos;
		}
	}

	@Nullable
	private static Baked bakeInner(ClientWorld clientWorld, Rail rail, RailResource railResource, Key key, boolean flip, @Nullable Baked previous) {
		final SyncBuilt built = buildSync(clientWorld, rail, railResource, key, flip);
		if (built == null) {
			return previous;
		}

		// ★ 建 VAO/VBO 必须在 GlStateTracker 的保护区内，否则 VertexArray 的构造会抛
		// 「GlStateTracker: Not protected」。详见 withProtectedGlState 的长注释。
		final long uploadStartNanos = System.nanoTime();
		final List<VertexArray> parts = withProtectedGlState(() -> built.merged.upload(MAPPING));
		final long uploadNanos = System.nanoTime() - uploadStartNanos;
		if (parts.isEmpty()) {
			return previous;
		}
		reportBakeSegments(built.instanceCount, built.walkNanos, built.lightNanos, built.sinkNanos, uploadNanos);
		lastInstanceCount = built.instanceCount;
		lastMaterialCount = parts.size();
		return new Baked(OptimizedModelWrapper.fromOptimizedModel(new OptimizedModel(parts)), key.minX, key.minY, key.minZ, built.lightHash);
	}

	/**
	 * 老路（**渲染线程**，一次做完）：走曲线 + 逐实例取光 + 矩阵/克隆/合桶 → 一个 {@code RawModel}。
	 *
	 * <p>它有三处调用者：{@code -Dmmtr.railbake.async=false} 的同步路、后台路出问题后的回退、
	 * 以及 {@code -Dmmtr.railbake.verify=true} 时的一次性等价性自检（自检只看几何，不上传）。</p>
	 *
	 * <p>后台那条路（{@link MmtrRailBakeJob}）是把这一段**照抄**成"走曲线在工作线程 / 取光在渲染线程 /
	 * 烘顶点在工作线程"三段的，所以这里任何一行改动都必须同时改那边 —— 自检开关就是为这件事留的。</p>
	 */
	@Nullable
	private static SyncBuilt buildSync(ClientWorld clientWorld, Rail rail, RailResource railResource, Key key, boolean flip) {
		final ObjectArrayList<RawMesh> sourceMeshes = sourceMeshesOf(railResource);
		if (sourceMeshes == null) {
			return null;
		}

		final double modelYOffset = railResource.getModelYOffset();
		final RawModel merged = new RawModel();
		final long[] lightHash = {LIGHT_HASH_SEED};
		final int[] instanceCount = {0};
		/*
		 * 烘焙分段（notes/401）：
		 *   走曲线+取光 = walk() 的总时间（含 RailMath.render 与逐实例 clientWorld.getLightLevel）
		 *   取光       = 其中查世界光照那一段（walkLightNanos，由 walk() 累加）
		 *   矩阵+克隆+合桶 = 回调体（把每个实例的变换烘进顶点、按材质 append）自己的总时间
		 *   upload     = merged.upload() 建 VBO
		 * 三段之和 ≈ 这一次烘焙的全部——这样"该搬哪一段"就是读数问题，不是意见问题。
		 */
		final long[] sinkNanos = {0};
		final long bakeStartNanos = System.nanoTime();

		walkLightNanos = 0;
		walk(clientWorld, rail, railResource.getRepeatInterval(), (x1, z1, x2, z2, x3, z3, x4, z4, y1, y2, light) -> {
			final long sinkStartNanos = System.nanoTime();
			lightHash[0] = lightHash[0] * LIGHT_HASH_MULTIPLIER + light;
			instanceCount[0]++;

			/*
			 * 下面这段与 RenderRails.renderRailStandard 的逐实例分支**逐字对应**（yaw/pitch/中点/三个旋转），
			 * 而且**故意用四元数**而不是 JOML 的 rotateX/rotateY/rotateZ 欧拉写法。原因（2026-10-05 实测）：
			 *
			 * <p>Minecraft 的 {@code MatrixStack.multiply(Quaternionf)} 就是
			 * {@code Matrix4f.rotate(quaternion)}，而 {@code RotationAxis.POSITIVE_X.rotation(f)} 的实现是
			 * {@code new Quaternionf().rotationX(f)}（反汇编 {@code net.minecraft.util.math.RotationAxis} 确认）。
			 * JOML 的轴专用四元数构造走 {@code cosFromSin}（{@code w = ±sqrt(1 − sin²)}），
			 * <b>在 θ≈π 附近会把 w 直接压成 0</b> —— 而钢轨恰好总是 θ≈π（{@code ax = π − pitch}，pitch 极小）。
			 * 实测：pitch ≤ ~5e-4 rad 时 w 归零（整段 pitch 被丢掉），pitch=0.01 时 w 有 ~7e-6 误差。</p>
			 *
			 * <p>换句话说 MTR 现在画的钢轨在近水平段是**没有俯仰**的。这不是我们要顺手"修好"的东西：
			 * 本次改动只换渲染结构，不换画面。用欧拉写法会更准（最多差 0.73 mm），但那就改了画面。
			 * 所以这里复刻 MC 的调用，保证**逐位一致**。要修 pitch 量化，另开一片。</p>
			 *
			 * <p>平移量先减掉本轨原点：世界坐标动辄几万，直接烘进 float 顶点会丢精度
			 * （float 在 30000 附近分辨率约 0.002 m）。矩阵语义对齐 MatrixStack —— JOML 的
			 * {@code translate}/{@code rotate} 也是右乘，与 {@code M = T · Ry · Rx · Rz} 一致。</p>
			 */
			final double differenceX = x3 - x1;
			final double differenceZ = z3 - z1;
			final double yaw = Math.atan2(differenceZ, differenceX);
			final double pitch = Math.atan2(y2 - y1, Math.sqrt(differenceX * differenceX + differenceZ * differenceZ));

			final org.joml.Matrix4f rawMatrix = new org.joml.Matrix4f();
			rawMatrix.translate((float) ((x1 + x3) / 2 - key.minX), (float) ((y1 + y2) / 2 + modelYOffset - key.minY), (float) ((z1 + z3) / 2 - key.minZ));
			rawMatrix.rotate(new Quaternionf().rotationY((float) (Math.PI / 2 - yaw + (flip ? Math.PI : 0))));
			rawMatrix.rotate(new Quaternionf().rotationX((float) (Math.PI - pitch * (flip ? -1 : 1))));
			// rotateZDegrees(f) 在 MC 里就是 rotation(f * 0.017453292f)，用的是那个 float 字面量
			rawMatrix.rotate(new Quaternionf().rotationZ(((float) ((x1 * z1) % 10) / 100) * 0.017453292F));
			final Matrix4f matrix = new Matrix4f(rawMatrix);

			for (final RawMesh sourceMesh : sourceMeshes) {
				// 逐顶点深拷贝（Vertex(Vertex) 会 Utilities.copy 位置与法线）—— 绝不能就地改源网格，
				// 那份 RawMesh 是 MTR 自己的模型数据，别的实例还要照着它生。
				final RawMesh clone = new RawMesh(sourceMesh.materialProperties);
				for (final Vertex vertex : sourceMesh.vertices) {
					final Vertex copy = new Vertex(vertex);
					copy.light = light;
					clone.vertices.add(copy);
				}
				for (final Face face : sourceMesh.faces) {
					clone.faces.add(new Face(face));
				}
				clone.applyMatrix(matrix);
				// RawModel.append 会按 MaterialProperties 合桶并正确平移面索引 ⇒ 一个材质一个 VertexArray。
				merged.append(clone);
			}
			sinkNanos[0] += System.nanoTime() - sinkStartNanos;
		});
		return new SyncBuilt(merged, lightHash[0], instanceCount[0], System.nanoTime() - bakeStartNanos, walkLightNanos, sinkNanos[0]);
	}

	/** 一次烘焙的分段读数（notes/401 §7）：只在总耗时够长时打一条，总数封顶。 */
	private static void reportBakeSegments(int instances, long walkNanos, long lightNanos, long sinkNanos, long uploadNanos) {
		final long totalMillis = (walkNanos + uploadNanos) / 1_000_000L;
		if (totalMillis < BAKE_LOG_MIN_MILLIS || bakeSegmentLogCount >= MAX_BAKE_SEGMENT_LOGS) {
			return;
		}
		bakeSegmentLogCount++;
		Init.LOGGER.info("[MMTR-RAILBAKE] 烘焙分段 {} 个实例 {} ms ｜ 走曲线+取光 {} ms（其中取光 {} ms）｜ 矩阵+克隆+合桶 {} ms ｜ upload {} ms",
				instances, totalMillis,
				walkNanos / 1_000_000L, lightNanos / 1_000_000L, sinkNanos / 1_000_000L, uploadNanos / 1_000_000L);
		if (bakeSegmentLogCount == MAX_BAKE_SEGMENT_LOGS) {
			Init.LOGGER.info("[MMTR-RAILBAKE] 烘焙分段读数已达 {} 条上限，后续不再输出", MAX_BAKE_SEGMENT_LOGS);
		}
	}

	/**
	 * 后台那条路的分段读数（notes/401）：**每一段标着它在哪条线程上**，这样"搬走了多少"是直接读出来的。
	 *
	 * <p>与同步那条的区别：同步版里"走曲线+取光"是混在一起的（同一个回调），后台版把它拆成
	 * 工作线程的走曲线与渲染线程的取光两段 —— 这正是这次改动的全部内容。</p>
	 */
	private static void reportBakeSegmentsAsync(MmtrRailBakeJob.Result result, long uploadNanos) {
		final long totalMillis = (result.walkNanos + result.lightNanos + result.buildNanos + uploadNanos) / 1_000_000L;
		if (totalMillis < BAKE_LOG_MIN_MILLIS || bakeSegmentLogCount >= MAX_BAKE_SEGMENT_LOGS) {
			return;
		}
		bakeSegmentLogCount++;
		Init.LOGGER.info("[MMTR-RAILBAKE] 后台烘焙分段 {} 个实例 {} ms ｜ 走曲线(后台) {} ms ｜ 取光(渲染线程) {} ms ｜ 合桶(后台) {} ms ｜ upload(渲染线程) {} ms",
				result.instanceCount, totalMillis,
				result.walkNanos / 1_000_000L, result.lightNanos / 1_000_000L, result.buildNanos / 1_000_000L, uploadNanos / 1_000_000L);
		if (bakeSegmentLogCount == MAX_BAKE_SEGMENT_LOGS) {
			Init.LOGGER.info("[MMTR-RAILBAKE] 烘焙分段读数已达 {} 条上限，后续不再输出", MAX_BAKE_SEGMENT_LOGS);
		}
	}

	/**
	 * 沿轨走一遍，对每个实例回调一次（坐标 + 该实例采样到的光照）。
	 *
	 * <p>端口映射与 {@code RenderRails.renderWithinRenderDistance} **逐字一致**，包括
	 * {@code y2 = cy3} 这一处（13 参回调到 10 参布局的移植）。光照参考点、pack 参数也一致 ——
	 * "烘进去的光"必须和"逐实例画时用的光"是同一个值。</p>
	 */
	private static void walk(ClientWorld clientWorld, Rail rail, double interval, InstanceSink sink) {
		rail.railMath.render((cx1, cy1, cz1, cx2, cy2, cz2, cx3, cy3, cz3, cx4, cy4, cz4, tiltAngle) -> {
			final double x1 = cx1;
			final double y1 = cy1;
			final double z1 = cz1;
			final double x2 = cx2;
			final double y2 = cy3;
			final double z2 = cz2;
			final double x3 = cx3;
			final double z3 = cz3;
			final double x4 = cx4;
			final double z4 = cz4;
			final BlockPos blockPos = Init.newBlockPos(x1, y1 + LIGHT_REFERENCE_OFFSET, z1);
			final long lightStartNanos = System.nanoTime();
			final int light = LightmapTextureManager.pack(
					clientWorld.getLightLevel(LightType.getBlockMapped(), blockPos),
					clientWorld.getLightLevel(LightType.getSkyMapped(), blockPos));
			walkLightNanos += System.nanoTime() - lightStartNanos;
			sink.accept(x1, z1, x2, z2, x3, z3, x4, z4, y1, y2, light);
		}, interval, 0, 0);
	}

	/** 从钢轨资源里取出可烘焙的源网格；{@code null} = 这条资源烘不了（调用方回退）。 */
	@Nullable
	private static ObjectArrayList<RawMesh> sourceMeshesOf(RailResource railResource) {
		final ObjectArrayList<OptimizedModelWrapper.ObjModelWrapper> wrappers = railResource.getBakeSourceObjModels();
		final Field field = rawModelField();
		if (wrappers == null || field == null) {
			return null;
		}
		final ObjectArrayList<RawMesh> meshes = new ObjectArrayList<>();
		try {
			for (final OptimizedModelWrapper.ObjModelWrapper wrapper : wrappers) {
				final RawModel rawModel = (RawModel) field.get(wrapper.objModel);
				if (rawModel != null) {
					// 走的是**同一批** RawMesh 对象：MTR 的 fromObjModels 已经对它们跑过
					// generateNormals() + distinct()，所以这里读到的正是被上传的那份几何。
					rawModel.iterateRawMeshList(meshes::add);
				}
			}
		} catch (Exception exception) {
			reportSourceUnavailable(exception);
			return null;
		}
		return meshes.isEmpty() ? null : meshes;
	}

	/**
	 * {@code OptimizedModel$ObjModel.rawModel} 是私有的，映射库也没给 getter —— 这是唯一一条
	 * 拿到"未上传的源顶点"的路。它属于预编译的映射库，所以<b>必须能失败</b>：
	 * 拿不到就整条停用、回退逐实例渲染，绝不让钢轨消失。
	 */
	@Nullable
	private static Field rawModelField() {
		if (!rawModelFieldResolved) {
			rawModelFieldResolved = true;
			try {
				final Field field = OptimizedModel.ObjModel.class.getDeclaredField("rawModel");
				field.setAccessible(true);
				rawModelField = field;
			} catch (Exception exception) {
				Init.LOGGER.warn("[MMTR-RAILBAKE] 映射库里取不到 OptimizedModel$ObjModel.rawModel —— 钢轨合并烘焙停用，全部回退逐实例渲染（画面一致，性能回到改前）", exception);
			}
		}
		return rawModelField;
	}

	private static void reportSourceUnavailable(Exception exception) {
		if (!sourceUnavailable) {
			sourceUnavailable = true;
			Init.LOGGER.warn("[MMTR-RAILBAKE] 取钢轨源几何时抛异常 —— 钢轨合并烘焙停用，全部回退逐实例渲染（画面一致，性能回到改前）", exception);
		}
	}

	/**
	 * 在"GL 状态已受保护"的窗口里跑一段会建 GL 对象的代码。
	 *
	 * <h2>为什么非有不可（2026-10-05 实机第一次跑就撞上）</h2>
	 *
	 * <p>{@code VertexArray} 的构造会走 {@code VertexBuffer.bind()} →
	 * {@code GlStateTracker.assertProtected()}，不在保护区里就抛
	 * {@code IllegalStateException: GlStateTracker: Not protected}。
	 * 实机日志里的堆栈正是：</p>
	 *
	 * <pre>
	 * GlStateTracker.assertProtected → VertexBuffer.bind → VertexAttributeMapping.setupAttributesToVao
	 *   → VertexArray.&lt;init&gt; → RawModel.upload → MmtrRailMeshCache.bakeInner
	 * </pre>
	 *
	 * <p>MTR 自己在 {@code OptimizedRenderer.beginReload()} 里做这件事 —— <b>但那个方法还会
	 * {@code ShaderManager.reloadShaders()}，而它会重新编译 3 个着色器程序</b>
	 * （字节码确认：{@code shaders.values().forEach(close)} → {@code clear()} → {@code loadShader} × 3）。
	 * 所以绝不能按需调 {@code beginReload()} —— 那正是 notes/395 里 400 ms 级卡顿的来源之一。
	 * 我们只要它的**另一半**：{@code capture()}。</p>
	 *
	 * <h2>为什么必须先问一句"已经受保护了吗"</h2>
	 *
	 * <p>{@code GlStateTracker} 用的是**一个布尔量，不是嵌套计数**（字节码确认）：
	 * {@code capture()} 在已保护时**直接 return**（幂等），但 {@code restore()} **无条件**清掉保护位。
	 * 所以如果我们在别人已经建立的保护区里盲目 {@code capture()+restore()}，收尾那一下会把**外层**
	 * 的保护也拆掉，害得 MTR 自己后面建 buffer 时抛异常 —— 那是更严重的破坏。</p>
	 *
	 * <p>因此判据是：<b>本来没保护 → 由我们 capture，也由我们 restore；本来就保护着 → 我们什么都不碰。</b></p>
	 *
	 * <p>{@code restore()} 还会 {@code enableCull()} + {@code depthMask(true)} + 还原 shader。
	 * 我们调用它的位置是 {@code RenderRails.render()} 的实体渲染阶段 —— 那里本来就是
	 * 开面剔除、depthMask=true 的默认状态，而且 capture 是紧挨着的，所以还原是精确的。</p>
	 */
	private static <T> T withProtectedGlState(Supplier<T> action) {
		final boolean alreadyProtected = isGlStateProtected();
		if (!alreadyProtected) {
			GlStateTracker.capture();
		}
		try {
			return action.get();
		} finally {
			if (!alreadyProtected) {
				GlStateTracker.restore();
			}
		}
	}

	/**
	 * {@code GlStateTracker.isStateProtected} 是私有的、映射库没给 getter，只能反射读。
	 *
	 * <p>拿不到就<b>直接把整条合并烘焙停用</b>（而不是"当作已保护"）：那样只会退化成逐实例渲染，
	 * 绝不会去动别人的保护区 —— 后者会连累 MTR 自己，代价大得多。宁可少一个优化，不要多一个隐患。</p>
	 */
	private static boolean isGlStateProtected() {
		if (!glStateProtectedFieldResolved) {
			glStateProtectedFieldResolved = true;
			try {
				final Field field = GlStateTracker.class.getDeclaredField("isStateProtected");
				field.setAccessible(true);
				glStateProtectedField = field;
			} catch (Exception exception) {
				sourceUnavailable = true;
				Init.LOGGER.warn("[MMTR-RAILBAKE] 取不到 GlStateTracker.isStateProtected —— 无法安全地建烘焙用的 VAO，合并烘焙停用（回退逐实例渲染，画面一致）", exception);
			}
		}
		if (glStateProtectedField == null) {
			return true;
		}
		try {
			return glStateProtectedField.getBoolean(null);
		} catch (Exception exception) {
			return true;
		}
	}
	/**
	 * 烘焙/解算过程中的失败计数。
	 *
	 * <p>为什么不"第一次就停用"：偶发失败（例如某一步碰上了没加载的区块）不该让整个优化永久失效。
	 * 但也不该每帧刷日志 + 每帧白算一遍 —— 所以**攒够 {@link #MAX_BAKE_FAILURES} 次就停用**，
	 * 只把第一次带上堆栈打出来。</p>
	 */
	private static void reportFailure(String what, Exception exception) {
		bakeFailures++;
		if (bakeFailures == 1) {
			Init.LOGGER.warn("[MMTR-RAILBAKE] {}时抛异常（第 1 次，共 {} 次后停用）—— 现阶段只是回退逐实例渲染，画面不受影响", what, MAX_BAKE_FAILURES, exception);
		}
		if (bakeFailures >= MAX_BAKE_FAILURES) {
			sourceUnavailable = true;
		}
	}

	/** 超上限就按"最久没用"淘汰，并**释放 GL 资源**（一个模型的 VBO 常驻显存，不能只管加不管还）。 */
	private static void evictIfNeeded() {
		while (ENTRIES.size() > MAX_ENTRIES) {
			Key oldestKey = null;
			long oldestFrame = Long.MAX_VALUE;
			for (final Map.Entry<Key, Baked> entry : ENTRIES.entrySet()) {
				if (entry.getValue().lastUsedFrame < oldestFrame) {
					oldestFrame = entry.getValue().lastUsedFrame;
					oldestKey = entry.getKey();
				}
			}
			if (oldestKey == null) {
				return;
			}
			final Baked removed = ENTRIES.remove(oldestKey);
			if (removed != null && removed.model != null) {
				removed.model.close();
			}
		}

		/*
		 * 后台作业也要淘汰（notes/401）：轨离开视野之后，那份作业没人会来 upload，
		 * 而它 build 阶段持有一份 4–5 MB 的 RawModel。按"多久没人问"清掉，别让它变成泄漏。
		 */
		if (!PENDING.isEmpty()) {
			PENDING.entrySet().removeIf(entry -> frame - entry.getValue().lastUsedFrame() > PENDING_JOB_TIMEOUT_FRAMES);
		}
	}

	/** 读一个整数系统属性，坏了就用默认值（**绝不在类初始化时抛** —— 那会让整个渲染链起不来）。 */
	private static int intProperty(String key, int defaultValue) {
		try {
			return Integer.parseInt(System.getProperty(key, String.valueOf(defaultValue)));
		} catch (NumberFormatException exception) {
			Init.LOGGER.warn("[MMTR-RAILBAKE] 系统属性 {} 不是整数，改用默认值 {}（{}）", key, defaultValue, exception.toString());
			return defaultValue;
		}
	}

	private static String describeState() {
		if (!ENABLED) {
			return " ｜ 状态：被 -Dmmtr.railbake=false 关掉了（逐实例渲染）";
		}
		if (sourceUnavailable) {
			return bakeFailures >= MAX_BAKE_FAILURES
					? " ｜ 状态：烘焙连续失败 " + bakeFailures + " 次，已自动停用（逐实例渲染）—— 去看第一条 WARN 的堆栈"
					: " ｜ 状态：拿不到源几何，已停用（逐实例渲染）";
		}
		if (asyncUnavailable) {
			return " ｜ 状态：后台烘焙出过不可恢复的错，已切回渲染线程上同步烘（画面一致）—— 去看那条 WARN";
		}
		if (MmtrRailBakeJob.isEnabled()) {
			return " ｜ 状态：走曲线+合桶在 MMTR-RailBake 线程，渲染线程只剩取光与 upload";
		}
		return " ｜ 状态：-Dmmtr.railbake.async=false ⇒ 全在渲染线程上烘";
	}
}
