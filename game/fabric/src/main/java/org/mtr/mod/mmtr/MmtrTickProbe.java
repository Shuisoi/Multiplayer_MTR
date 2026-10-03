package org.mtr.mod.mmtr;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;
import org.mtr.core.mmtr.probe.MmtrProbe;
import org.mtr.mod.block.BlockSignalBase;

/**
 * MC 服务端 tick 的性能探针（notes/337）。
 *
 * <h2>它补的是哪一段</h2>
 * <p>引擎侧的 {@link MmtrProbe} 只看得见**模拟线程自己**花的时间。而"服务端落后 40 s"这件事
 * 记在 MC 的 tick 上（{@code Can't keep up! Running 40685ms / 813 ticks behind}），那 40 s 里
 * 至少混着四份来源，此前分不开：</p>
 *
 * <ol>
 *   <li><b>原版</b>：区块生成/加载/光照、实体、方块实体（施工期上千条 {@code fill} 全在这里）；</li>
 *   <li><b>MMTR 的每-tick 钩子</b>：{@code END_SERVER_TICK} 上挂着的
 *       {@link MmtrRouteMirror}（每 tick 全量重算，notes/335 §4 的看门狗栈就指向它）、
 *       {@link MmtrCommandExecutor}、{@link MmtrSignalSync}；</li>
 *   <li><b>引擎模拟</b>：{@code Simulator.tick()}，在嵌入式运行时就是**这个线程**上跑的；</li>
 *   <li><b>外面塞进来的活</b>：存档、RCON 施工批次、网页任务。</li>
 * </ol>
 *
 * <h2>三个量，一次说清"谁在拖谁"</h2>
 * <p>只用 {@code START_SERVER_TICK} → {@code END_SERVER_TICK} 量"整帧"是不够的：钩子挂在
 * {@code END} 上，{@code END} 事件本身在整帧**之后**才发，所以整帧那个数里既没有钩子、也没有
 * 引擎 tick（引擎由 {@code Init} 在 START 之前驱动）—— 单看它会把结论引向"我们没花时间"。
 * 所以这里量三段，语义各自独立：</p>
 *
 * <ul>
 *   <li>{@code server.tickSpan} = 上一次收帧 → 这一次 START：**完整帧间隔**（≈ 50 ms 就是没掉帧，
 *       4000 ms 就是那一次卡了 4 秒）；</li>
 *   <li>{@code server.vanillaAndEngine} = START → END：原版那一段（含落在里面的引擎 tick）；</li>
 *   <li>{@code server.mmtrHooks} = END → 本钩子（本类**最后**注册，见 {@link #register()}）：
 *       三个 MMTR 每-tick 钩子合计；</li>
 * </ul>
 *
 * <p>再减去 {@code server.routeMirror} / {@code server.signalSync} 两个单点，剩下就是
 * {@code MmtrCommandExecutor} 与本事自己的开销（应当接近 0）—— 于是"是不是每-tick 钩子在拖"是一个减法就能得到的结论。</p>
 */
public final class MmtrTickProbe {

	private static long lastTickEndNanos;
	private static long serverTickStartNanos;

	private MmtrTickProbe() {
	}

	/**
	 * 注册。
	 *
	 * <p><b>顺序是有意的</b>：本类只挂 {@code START_SERVER_TICK}，收帧由 {@link MmtrRouteMirror} 的包裹
	 * 在三个 MMTR 钩子都跑完之后调 {@link #onHooksFinished}。这样无论 {@code MTR.init()} 里谁先谁后，
	 * 读数都不变（靠 {@code END_SERVER_TICK} 注册顺序排出来的读数是会随人改文件而静默错的）。</p>
	 */
	public static void register() {
		ServerTickEvents.START_SERVER_TICK.register(MmtrTickProbe::onTickStart);
		// 网页/控制台那条 `probe dump` 的分段表由引擎侧拼，这里补上只有游戏端数得出来的那几行。
		MmtrProbe.setExtraReporter(MmtrTickProbe::gameSideLines);
	}

	/** 只有游戏端能回答的那几行（世界规模），接在引擎的分段表后面。 */
	private static java.util.List<String> gameSideLines() {
		final java.util.List<String> lines = new java.util.ArrayList<>();
		lines.add("[MMTR-PROBE] 游戏端：服务端 tick 槽 n=" + MmtrProbe.frameCount(MmtrProbe.FRAME_SERVER)
			+ "，引擎 tick 槽 n=" + MmtrProbe.frameCount(MmtrProbe.FRAME_SIM)
			+ "（两个时钟不是一回事：一次服务端 tick 里可能跑 0…N 次模拟 tick）");
		return lines;
	}

	private static void onTickStart(MinecraftServer minecraftServer) {
		if (!MmtrProbe.on()) {
			return;
		}
		final long now = System.nanoTime();
		if (lastTickEndNanos != 0L) {
			/*
			 * 帧间隔：这是唯一能看见"这一次真的卡了多久"的量。
			 *
			 * <p>它与 {p50/p95/p99} 直方图一起给出"偶发尖峰还是稳态慢"：notes/336 §3 的现场是
			 * 每 30–60 s 一记 20–40 s 的尖峰（{@code Can't keep up}），而稳态 tick 只有几毫秒 ——
			 * 两类问题要修的完全是不同的东西，必须能分开。</p>
			 */
			MmtrProbe.add("server.tickSpan", now - lastTickEndNanos);
		}
		serverTickStartNanos = now;
		/*
		 * **帧槽必须在这里开**（2026-09-27 现场实测的缺陷：第一版漏了这一行）。
		 *
		 * <p>漏了它的症状极具误导性：{@code server.mmtrHooks} 照常有数（那是直接量的一段），
		 * 但 {@code frame=server} 永远是 {@code n>0 busy=0.000ms} —— 因为收帧时读到的
		 * {@code startNanos} 是 0，于是"这一帧多长"被记成 0。而 {@code n>0 且 busy=0} 本身
		 * 就是自相矛盾的输出，第一眼却像是"钩子不花时间"这种**结论**。</p>
		 */
		MmtrProbe.frameBegin(MmtrProbe.FRAME_SERVER);
	}

	private static void onTickEnd(MinecraftServer minecraftServer) {
		if (!MmtrProbe.on()) {
			return;
		}
		lastTickEndNanos = System.nanoTime();
		if (serverTickStartNanos != 0L) {
			MmtrProbe.add("server.vanillaAndEngine", lastTickEndNanos - serverTickStartNanos);
		}
		serverTickStartNanos = 0L;
	}

	/** 三个 MMTR 每-tick 钩子全部跑完（由 {@link MmtrRouteMirror} 的包裹在最后调用）。 */
	static void onHooksFinished(MinecraftServer minecraftServer) {
		if (!MmtrProbe.on()) {
			return;
		}
		final long now = System.nanoTime();
		if (lastTickEndNanos != 0L) {
			MmtrProbe.add("server.mmtrHooks", now - lastTickEndNanos);
		}
		sampleWorldCounters(minecraftServer);
		MmtrProbe.frameEnd(MmtrProbe.FRAME_SERVER);
	}

	/**
	 * 世界侧计数器：已加载区块数、世界里真的放着几盏信号灯。
	 *
	 * <p>为什么是这两个：它们分别是"原版那一段"与"我们的每-tick 钩子"的**规模变量**。
	 * 区间层与镜像是按灯×轨算的，所以"服务器上有几盏灯"必须能随时读出来 ——
	 * 现场只有 {@code mmtr-lamps} 那条指令（要人手敲），而卡顿是连续发生的。</p>
	 */
	private static void sampleWorldCounters(MinecraftServer minecraftServer) {
		int loadedChunks = 0;
		int signalBlocks = 0;
		for (final ServerWorld serverWorld : minecraftServer.getWorlds()) {
			for (final WorldChunk chunk : MmtrChunkTracker.loadedChunks(serverWorld)) {
				loadedChunks++;
				for (final BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					if (blockEntity.getCachedState().getBlock() instanceof BlockSignalBase) {
						signalBlocks++;
					}
				}
			}
		}
		MmtrProbe.peak("world.loadedChunks", loadedChunks);
		MmtrProbe.peak("world.signalBlocks", signalBlocks);
	}

	/**
	 * 把一个外部动作（RCON 施工批次、网页指令、存档）标到**本 tick** 上。
	 *
	 * <p>用途：{@code #18422=40685ms(world.fill×1108)} 这种最坏帧记录 —— 没有这个标签，
	 * 事后只看分段表会把 40 s 全算到 tick 循环头上，而真正的原因是几千条 {@code fill} 把世界改了。</p>
	 */
	public static void markExternalWork(String label) {
		MmtrProbe.hint(label);
	}
}
