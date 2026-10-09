package org.mtr.mod.render;

import org.joml.Quaternionf;
import org.mtr.core.data.RailMath;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.ClientWorld;
import org.mtr.mapping.holder.LightType;
import org.mtr.mapping.holder.LightmapTextureManager;
import org.mtr.mapping.holder.Matrix4f;
import org.mtr.mapping.render.model.Face;
import org.mtr.mapping.render.model.RawMesh;
import org.mtr.mapping.render.model.RawModel;
import org.mtr.mapping.render.vertex.Vertex;
import org.mtr.mod.Init;
import org.mtr.mod.resource.MmtrAsyncModelParse;

import javax.annotation.Nullable;
import java.util.concurrent.ExecutorService;

/**
 * 一次钢轨烘焙的**两段式作业**（notes/401）。
 *
 * <h2>为什么要拆</h2>
 *
 * <p>{@code [MMTR-FRAME]} 实测一次烘焙 = `走曲线+取光` + `矩阵+克隆+合桶` + `upload`，
 * 而 `[MMTR-RAILBAKE] 烘焙分段` 把三段量出来之后（440 个实例：走曲线 5–22 ms ／
 * 其中**取光 0 ms** ／ 矩阵+克隆+合桶 5–13 ms ／ **upload 16–57 ms**）切分点就定了：</p>
 *
 * <ul>
 *   <li><b>走曲线</b>（{@code RailMath.render} 逐实例给坐标）—— 纯 CPU。
 *       {@code Rail.railMath} 是 {@code public final}、{@code RailMath} 的字段**全是 final**
 *       ⇒ 渲染线程取一次引用当快照交给工作线程，读的是不可变对象，**不是**在别的线程上读世界状态；</li>
 *   <li><b>矩阵 + 逐顶点深拷贝 + 按材质合桶</b> —— 纯 CPU，逐字照搬原来的回调体；</li>
 *   <li><b>取光</b>（{@code clientWorld.getLightLevel}）—— 要碰世界（区块/光照数据），
 *       <b>必须留在渲染线程</b>。数据显示它 0 ms ⇒ 留着的代价是零，不必为了它去冒险；</li>
 *   <li><b>{@code upload()}</b> —— 建 VBO，必须留在渲染线程（而且实测它才是大头）。</li>
 * </ul>
 *
 * <p>于是流程是四个阶段、两次跨线程交接：</p>
 *
 * <pre>
 *   ① 工作线程  走曲线（RailMath）            → 每个实例的 10 个坐标
 *   ② 渲染线程  逐实例取光照 + 光照哈希        ← 世界只能在渲染线程读
 *   ③ 工作线程  逐实例矩阵 → 顶点深拷贝 → 合桶  → 一个 RawModel
 *   ④ 渲染线程  upload()                      → VertexArray（调用方做）
 * </pre>
 *
 * <h2>等价性怎么保证</h2>
 *
 * <p>①③ 是把原来那一整段代码<b>逐字搬过来</b>（矩阵的四个步骤、四元数的写法、
 * {@code (x1*z1)%10} 那个 z 旋转、逐顶点深拷贝 + {@code copy.light} 的顺序都没动），
 * 搬的时候只改了"在哪里跑"。</p>
 *
 * <p>另外带一个一次性自检开关 {@code -Dmmtr.railbake.verify=true}：
 * 头几次烘焙会**同时**跑一遍渲染线程上的老路，把两份 {@code RawModel} 的实例数、材质桶数、
 * 顶点数、面数与顶点内容摘要比一遍，一致才继续用后台那条（判据见
 * {@code sandbox/railbake-verify} 里那套"摘要逐字节比"的做法）。</p>
 *
 * <h2>失败怎么办</h2>
 *
 * <p>任何一段抛异常 ⇒ 这一份作业作废、报告一次，并把
 * {@link MmtrRailMeshCache} 切回"全在渲染线程上烘"的老路（绝不静默、绝不半成品）。
 * 开关 {@code -Dmmtr.railbake.async=false} 直接走老路。</p>
 */
public final class MmtrRailBakeJob {

	/** 每个实例 10 个坐标：x1,z1,x2,z2,x3,z3,x4,z4,y1,y2 —— 与老代码那个 10 参回调逐位对应。 */
	private static final int STRIDE = 10;
	private static final int INITIAL_INSTANCES = 128;

	private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("mmtr.railbake.async", "true"));
	/** 一次性等价性自检（`-Dmmtr.railbake.verify=true`）：头几次烘焙同时跑老路并比摘要。 */
	private static final boolean VERIFY = Boolean.parseBoolean(System.getProperty("mmtr.railbake.verify", "false"));
	private static final int MAX_VERIFY_CHECKS = 3;

	private static final ExecutorService WORKER = MmtrAsyncModelParse.newDaemonWorker("MMTR-RailBake");

	private static final int MAX_LOG = 60;
	private static int logCount;
	private static int verifyChecks;

	private enum Phase {
		/** ① 工作线程正在走曲线。 */
		WALK,
		/** ③ 工作线程正在烘顶点。 */
		BUILD,
		/** ④ 可以 upload 了。 */
		READY
	}

	/** ① 的产物：实例坐标 + 个数 + 工作线程上那一段的耗时。 */
	private static final class Walked {

		private final double[] data;
		private final int count;
		private final long millis;

		private Walked(double[] data, int count, long millis) {
			this.data = data;
			this.count = count;
			this.millis = millis;
		}
	}

	/** ③ 的产物。 */
	public static final class Result {

		public final RawModel merged;
		public final long lightHash;
		public final int instanceCount;
		/** 分段读数（notes/401）：工作线程上的走曲线、渲染线程上的取光、工作线程上的烘顶点。 */
		public final long walkNanos;
		public final long lightNanos;
		public final long buildNanos;

		private Result(RawModel merged, long lightHash, int instanceCount, long walkNanos, long lightNanos, long buildNanos) {
			this.merged = merged;
			this.lightHash = lightHash;
			this.instanceCount = instanceCount;
			this.walkNanos = walkNanos;
			this.lightNanos = lightNanos;
			this.buildNanos = buildNanos;
		}
	}

	private final String label;
	private final RailMath railMath;
	private final double interval;
	private final double minX;
	private final double minY;
	private final double minZ;
	private final double modelYOffset;
	private final boolean flip;
	private final ObjectArrayList<RawMesh> sourceMeshes;
	private final double lightReferenceOffset;

	private final MmtrAsyncModelParse<Walked> walkJob;
	@Nullable
	private MmtrAsyncModelParse<RawModel> buildJob;
	private Phase phase = Phase.WALK;
	@Nullable
	private double[] instances;
	private int instanceCount;
	@Nullable
	private int[] lights;
	@Nullable
	private Result result;
	private long lightHash = MmtrRailMeshCache.LIGHT_HASH_SEED;
	/** 最后一帧有人来问过它（LRU 淘汰用）。 */
	private long lastUsedFrame;
	private boolean reported;

	public static boolean isEnabled() {
		return ENABLED;
	}

	public MmtrRailBakeJob(
			String label,
			RailMath railMath,
			double interval,
			double minX,
			double minY,
			double minZ,
			double modelYOffset,
			boolean flip,
			ObjectArrayList<RawMesh> sourceMeshes,
			double lightReferenceOffset
	) {
		this.label = label;
		this.railMath = railMath;
		this.interval = interval;
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.modelYOffset = modelYOffset;
		this.flip = flip;
		this.sourceMeshes = sourceMeshes;
		this.lightReferenceOffset = lightReferenceOffset;
		walkJob = new MmtrAsyncModelParse<>("后台钢轨走曲线 " + label, this::walkOffThread, WORKER);
		walkJob.start();
	}

	/**
	 * 推进这个作业（**渲染线程**调用）：
	 * 走完曲线就取光并交给工作线程烘顶点；烘完就返回 {@code true}（表示"可以 upload 了"）。
	 *
	 * @return {@code true} = 已经可以 {@link #takeResult()} 并 upload；{@code false} = 还没好，这一帧继续用旧的
	 * @throws Exception 任何一段失败 —— 调用方必须把钢轨烘焙切回同步老路（绝不静默）
	 */
	public boolean advance(ClientWorld clientWorld) throws Throwable {
		if (phase == Phase.WALK) {
			final Walked walked = walkJob.poll();
			if (walked == null) {
				return false;
			}
			walkNanos = walked.millis;
			instances = walked.data;
			instanceCount = walked.count;
			// ② 渲染线程：逐个实例取世界光照（老路里这一段在 walk 的回调里，值一模一样）
			final long lightStartNanos = System.nanoTime();
			lights = new int[instanceCount];
			long hash = MmtrRailMeshCache.LIGHT_HASH_SEED;
			for (int i = 0; i < instanceCount; i++) {
				final int base = i * STRIDE;
				final BlockPos blockPos = Init.newBlockPos(instances[base], instances[base + 8] + lightReferenceOffset, instances[base + 1]);
				final int light = LightmapTextureManager.pack(
						clientWorld.getLightLevel(LightType.getBlockMapped(), blockPos),
						clientWorld.getLightLevel(LightType.getSkyMapped(), blockPos));
				lights[i] = light;
				hash = hash * MmtrRailMeshCache.LIGHT_HASH_MULTIPLIER + light;
			}
			lightNanos = System.nanoTime() - lightStartNanos;
			lightHash = hash;
			// ③ 工作线程：逐实例烘顶点
			buildJob = new MmtrAsyncModelParse<>("后台钢轨合桶 " + label, this::buildOffThread, WORKER);
			buildJob.start();
			phase = Phase.BUILD;
			return false;
		}

		if (phase == Phase.BUILD) {
			final RawModel merged = buildJob == null ? null : buildJob.poll();
			if (merged == null) {
				return false;
			}
			buildNanos = buildJob.elapsedNanos();
			result = new Result(merged, lightHash, instanceCount, walkNanos, lightNanos, buildNanos);
			phase = Phase.READY;
			return true;
		}

		return phase == Phase.READY;
	}

	private long walkNanos = -1;
	private long lightNanos = -1;
	private long buildNanos = -1;

	public Result takeResult() {
		if (result == null) {
			throw new IllegalStateException("rail bake result not ready: " + label);
		}
		return result;
	}

	public void markUsed(long frame) {
		lastUsedFrame = frame;
	}

	public long lastUsedFrame() {
		return lastUsedFrame;
	}

	public int instanceCount() {
		return instanceCount;
	}

	public void reportFailure(String what, Throwable throwable) {
		if (!reported) {
			reported = true;
			Init.LOGGER.warn("[MMTR-RAILBAKE] {} 后台烘焙失败（{}）—— 钢轨烘焙切回渲染线程上同步烘（画面不受影响）", label, what, throwable);
		}
	}

	// ---------------------------------------------------------------- 工作线程那一半

	/** ① 走曲线：逐实例把 10 个坐标收进数组（**不碰世界、不碰 GL**）。 */
	private Walked walkOffThread() {
		final long startNanos = System.nanoTime();
		final double[][] buffer = {new double[INITIAL_INSTANCES * STRIDE]};
		final int[] count = {0};
		railMath.render((cx1, cy1, cz1, cx2, cy2, cz2, cx3, cy3, cz3, cx4, cy4, cz4, tiltAngle) -> {
			if ((count[0] + 1) * STRIDE > buffer[0].length) {
				final double[] grown = new double[buffer[0].length * 2];
				System.arraycopy(buffer[0], 0, grown, 0, buffer[0].length);
				buffer[0] = grown;
			}
			final int base = count[0] * STRIDE;
			buffer[0][base] = cx1;
			buffer[0][base + 1] = cz1;
			buffer[0][base + 2] = cx2;
			buffer[0][base + 3] = cz2;
			buffer[0][base + 4] = cx3;
			buffer[0][base + 5] = cz3;
			buffer[0][base + 6] = cx4;
			buffer[0][base + 7] = cz4;
			// y2 = cy3 —— 与 RenderRails.renderWithinRenderDistance 的移植**逐字一致**
			buffer[0][base + 8] = cy1;
			buffer[0][base + 9] = cy3;
			count[0]++;
		}, interval, 0, 0);
		return new Walked(buffer[0], count[0], System.nanoTime() - startNanos);
	}

	/** ③ 逐实例烘顶点：矩阵 + 深拷贝 + 按材质合桶。这一段是从老代码逐字搬过来的。 */
	private RawModel buildOffThread() {
		final RawModel merged = new RawModel();
		final int[] lightValues = lights == null ? new int[0] : lights;
		final double[] data = instances == null ? new double[0] : instances;
		for (int i = 0; i < instanceCount; i++) {
			final int base = i * STRIDE;
			final double x1 = data[base];
			final double z1 = data[base + 1];
			final double x3 = data[base + 4];
			final double z3 = data[base + 5];
			final double y1 = data[base + 8];
			final double y2 = data[base + 9];
			final int light = i < lightValues.length ? lightValues[i] : 0;

			/*
			 * 下面这段与老代码（原 MmtrRailMeshCache.bakeInner 的回调体）逐字对应，包括
			 * **故意用四元数**而不是 JOML 的 rotateX/rotateY/rotateZ 欧拉写法（MTR 的
			 * MatrixStack.multiply(Quaternionf) 就是这个语义，换成欧拉会改画面）。
			 * 平移量先减掉本轨原点：世界坐标动辄几万，直接烘进 float 顶点会丢精度。
			 */
			final double differenceX = x3 - x1;
			final double differenceZ = z3 - z1;
			final double yaw = Math.atan2(differenceZ, differenceX);
			final double pitch = Math.atan2(y2 - y1, Math.sqrt(differenceX * differenceX + differenceZ * differenceZ));

			final org.joml.Matrix4f rawMatrix = new org.joml.Matrix4f();
			rawMatrix.translate((float) ((x1 + x3) / 2 - minX), (float) ((y1 + y2) / 2 + modelYOffset - minY), (float) ((z1 + z3) / 2 - minZ));
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
		}
		return merged;
	}

	// ---------------------------------------------------------------- 自检

	/**
	 * 头几次烘焙做一次**等价性自检**（{@code -Dmmtr.railbake.verify=true}）：
	 * 在老路上再算一遍，把两份 {@code RawModel} 的摘要比一遍。
	 *
	 * <p>为什么要它：这段代码的价值全在"搬了但一个字节都没变"，而它是逐实例矩阵 + 逐顶点拷贝，
	 * 肉眼在游戏里看不出 1e-6 的差别。判据与 {@code sandbox/railbake-verify/ObjParseBench} 同一套：
	 * 材质桶顺序 + 每个顶点内容 + 每条面的索引。</p>
	 */
	static boolean shouldVerify() {
		return VERIFY && verifyChecks < MAX_VERIFY_CHECKS;
	}

	static void verifyAgainst(@Nullable RawModel asyncModel, @Nullable RawModel syncModel, String label) {
		if (!shouldVerify()) {
			return;
		}
		verifyChecks++;
		final boolean same = asyncModel != null && syncModel != null && digest(asyncModel).equals(digest(syncModel));
		Init.LOGGER.info("[MMTR-RAILBAKE] 等价性自检 {}/{} {}: {}", verifyChecks, MAX_VERIFY_CHECKS, label,
				same ? "两份模型摘要逐字节一致 ✓（后台版可以继续用）"
						: "**不一致** ✗ 后台版：" + (asyncModel == null ? "null" : digest(asyncModel)) + " ｜ 老路：" + (syncModel == null ? "null" : digest(syncModel)));
	}

	/**
	 * 一段几何的规范摘要：材质桶顺序、顶点（位置/法线/uv → {@code hashCode}）、面索引。
	 *
	 * <p><b>故意不含 color / light</b>：后台那条路的光照是在**另一帧**上取的（走完曲线的那一帧），
	 * 自检那条老路是当下取的 —— 同一根轨的光照通常一模一样，但"通常"不能当判据用。
	 * 这里要证的是**几何**搬得逐字节对（矩阵/克隆/合桶），光照本来就是同一段代码、同一个线程。</p>
	 */
	static String digest(RawModel rawModel) {
		final java.util.List<RawMesh> meshes = new java.util.ArrayList<>();
		rawModel.iterateRawMeshList(meshes::add);
		final StringBuilder builder = new StringBuilder("buckets=").append(meshes.size());
		int vertices = 0;
		int faces = 0;
		long hash = 1125899906842597L;
		for (final RawMesh mesh : meshes) {
			builder.append("[").append(mesh.materialProperties.hashCode()).append(":").append(mesh.vertices.size()).append(":");
			for (final Vertex vertex : mesh.vertices) {
				vertices++;
				// Vertex.hashCode() = Objects.hash(position, normal, u, v) —— 正是几何那几项
				hash = hash * 1099511628211L + vertex.hashCode();
			}
			builder.append(mesh.faces.size());
			for (final Face face : mesh.faces) {
				faces++;
				for (final int index : face.vertices) {
					hash = hash * 1099511628211L + index;
				}
			}
			builder.append("]");
		}
		return builder.append(" vertices=").append(vertices).append(" faces=").append(faces).append(" hash=").append(hash).toString();
	}
}
