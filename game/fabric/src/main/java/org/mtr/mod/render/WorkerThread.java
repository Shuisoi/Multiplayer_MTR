package org.mtr.mod.render;

import com.logisticscraft.occlusionculling.DataProvider;
import com.logisticscraft.occlusionculling.OcclusionCullingInstance;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockView;
import org.mtr.mapping.holder.ClientWorld;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.mapper.MinecraftClientHelper;
import org.mtr.mod.CustomThread;
import org.mtr.mod.Init;

import java.util.function.Consumer;

/**
 * A background thread to perform intensive rendering tasks (e.g. Occlusion culling, generate dynamic textures etc.)
 */
public final class WorkerThread extends CustomThread {

	private static final int MAX_OCCLUSION_CHUNK_DISTANCE = 32;
	private static final int MAX_QUEUE_SIZE = 2;
	private int renderDistance;
	private OcclusionCullingInstance occlusionCullingInstance;
	private final ObjectArrayList<Consumer<OcclusionCullingInstance>> occlusionQueueVehicle = new ObjectArrayList<>();
	private final ObjectArrayList<Consumer<OcclusionCullingInstance>> occlusionQueueLift = new ObjectArrayList<>();
	private final ObjectArrayList<Consumer<OcclusionCullingInstance>> occlusionQueueMisc = new ObjectArrayList<>();
	private final ObjectArrayList<Consumer<OcclusionCullingInstance>> occlusionQueueRail = new ObjectArrayList<>();
	private final ObjectArrayList<Runnable> dynamicTextureQueue = new ObjectArrayList<>();

	@Override
	protected void runTick() {
		/*
		 * notes/177：这一轮循环原来是"**先睡 10 ms**，醒来如果队列非空就 resetCache() + 每个队列只跑**一个**任务"。
		 * 两条后果都很贵：
		 *
		 *   ① `resetCache()` 是 `Arrays.fill` 一个 **(2*reach)³/4** 字节的数组。渲染距离 32 区块时
		 *      reach = 512 ⇒ 那个数组是 **256 MB**，清一次 ~7 ms。而队列在每帧都有产出（车、轨各一个）
		 *      的情况下**长期是满的**，于是它每秒清约 100 次 —— 实测 jstack 连续两次都停在
		 *      `Arrays.fill ← ArrayOcclusionCache.resetCache ← WorkerThread.runTick`，两次之间
		 *      5.15 秒里烧掉 3.77 秒 CPU（**73% 的一个核**），同时把内存带宽和 CPU 缓存占干净，
		 *      渲染线程跟着几乎不出帧（现场表现就是"客户端卡死"）。
		 *      ⇒ 缓存换成 {@link MmtrSparseOcclusionCache}：只清这一轮碰过的几百个格子。
		 *
		 *   ② 每轮只跑一个任务、还要先睡 10 ms ⇒ 消费上限 100 个/秒，而产出（每帧一个 × 帧率）
		 *      在 120 FPS 时是 240 个/秒 ⇒ 队列**永远是满的**，于是调度方
		 *      （`scheduleVehicles` / `scheduleMTRRails` 的 `size() < MAX_QUEUE_SIZE`）**把任务丢掉**：
		 *      花了 73% 的核却几乎没做剔除。
		 *      ⇒ 有活就一口气干完（不再每轮睡），没活才睡。
		 */
		final boolean hasOcclusionTasks = !occlusionQueueVehicle.isEmpty() || !occlusionQueueLift.isEmpty() || !occlusionQueueMisc.isEmpty() || !occlusionQueueRail.isEmpty();

		if (hasOcclusionTasks) {
			updateInstance();
			occlusionCullingInstance.resetCache();
			// 用 `|` 而不是 `||`：四个队列每一轮都要清空，不能因为左边跑过就短路掉右边
			while (run(occlusionQueueVehicle, task -> task.accept(occlusionCullingInstance))
					| run(occlusionQueueLift, task -> task.accept(occlusionCullingInstance))
					| run(occlusionQueueRail, task -> task.accept(occlusionCullingInstance))
					| run(occlusionQueueMisc, task -> task.accept(occlusionCullingInstance))) {
				// 队列里的任务全部跑完为止（队列本身由调度方限制长度，所以这个循环是有界的）
			}
		} else {
			try {
				Thread.sleep(10); // Give the CPU a little break
			} catch (InterruptedException e) {
			}
		}

		run(dynamicTextureQueue, Runnable::run);
	}

	@Override
	protected boolean isRunning() {
		return MinecraftClient.getInstance().isRunning();
	}

	public void scheduleVehicles(Consumer<OcclusionCullingInstance> consumer) {
		if (occlusionQueueVehicle.size() < MAX_QUEUE_SIZE) {
			occlusionQueueVehicle.add(consumer);
		}
	}

	public void scheduleLifts(Consumer<OcclusionCullingInstance> consumer) {
		if (occlusionQueueLift.size() < MAX_QUEUE_SIZE) {
			occlusionQueueLift.add(consumer);
		}
	}

	@Deprecated
	public void scheduleRails(Consumer<OcclusionCullingInstance> consumer) {
		if (occlusionQueueMisc.size() < MAX_QUEUE_SIZE) {
			occlusionQueueMisc.add(consumer);
		}
	}

	public void scheduleMTRRails(Consumer<OcclusionCullingInstance> consumer) {
		if (occlusionQueueRail.size() < MAX_QUEUE_SIZE) {
			occlusionQueueRail.add(consumer);
		}
	}

	public void scheduleDynamicTextures(Runnable runnable) {
		dynamicTextureQueue.add(runnable);
	}

	private void updateInstance() {
		final int newRenderDistance = MinecraftClientHelper.getRenderDistance();
		if (renderDistance != newRenderDistance) {
			renderDistance = newRenderDistance;
			// notes/177：缓存用**只清碰过的格子**的那一份实现 —— 库自带的 ArrayOcclusionCache 在
			// reach=512（渲染距离 32 区块）时是 256 MB，每帧全清一次就吃掉 73% 的一个核。见那里。
			occlusionCullingInstance = new OcclusionCullingInstance(Math.min(renderDistance, MAX_OCCLUSION_CHUNK_DISTANCE) * 16, new CullingDataProvider(), new MmtrSparseOcclusionCache(Math.min(renderDistance, MAX_OCCLUSION_CHUNK_DISTANCE) * 16), 0.5);
		}
	}

	/** @return 是否真的跑了一个任务（{@link #runTick()} 用它判断队列有没有被清空） */
	private static <T> boolean run(ObjectArrayList<T> queue, Consumer<T> consumer) {
		if (queue.isEmpty()) {
			return false;
		}
		try {
			final T task = queue.remove(0);
			if (task != null) {
				consumer.accept(task);
			}
		} catch (Exception e) {
			Init.LOGGER.error("", e);
		}
		return true;
	}

	private static final class CullingDataProvider implements DataProvider {

		private final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		private ClientWorld clientWorld = null;

		@Override
		public boolean prepareChunk(int chunkX, int chunkZ) {
			clientWorld = minecraftClient.getWorldMapped();
			return clientWorld != null;
		}

		@Override
		public boolean isOpaqueFullCube(int x, int y, int z) {
			final BlockPos blockPos = new BlockPos(x, y, z);
			return clientWorld != null && clientWorld.getBlockState(blockPos).isOpaqueFullCube(new BlockView(clientWorld.data), blockPos);
		}

		@Override
		public void cleanup() {
			clientWorld = null;
		}
	}
}
