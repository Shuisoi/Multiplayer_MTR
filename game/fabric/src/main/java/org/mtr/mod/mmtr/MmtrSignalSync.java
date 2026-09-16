package org.mtr.mod.mmtr;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.mtr.core.Main;
import org.mtr.core.mmtr.signal.MmtrSignalRegistry;
import org.mtr.core.mmtr.signal.MmtrSignalRegistry.SignalEntry;
import org.mtr.core.simulation.Simulator;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.World;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockSignalBase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 信号灯登记表 ↔ 世界 的同步层（自动刷新 + 手动扫描共用一份实现）。
 *
 * <h3>为什么要自动刷新</h3>
 * <p>登记表是引擎的一部分（{@code mmtr-signals.json}），而"世界里到底还有没有这盏灯"只有游戏端答得出来
 * —— 只有游戏端能枚举区块里的方块实体。原来这件事**只有一个入口**：操作员在中控敲一句
 * {@code world scan-signals}。于是用户把灯敲掉之后，登记表里那一条会一直留着，地图上照样画着一盏
 * 已经不存在的灯，而且**没有任何东西会告诉他需要去敲那句指令**（实测反馈："灯确实敲掉了、但登记表
 * 没跟上啊"）。手动刷新要保留（它是全量重建、可解释、可审计），但"世界改了登记表就跟着改"不能靠人记得。</p>
 *
 * <h3>三条同步路径，各管一段</h3>
 * <ol>
 *   <li><b>区块加载</b>（{@link ServerChunkEvents#CHUNK_LOAD}）：新区块进内存就同步一次。世界启动时
 *       会一口气加载成百上千个区块，所以这里只**入队**，真正干活的量在 tick 里按预算放。</li>
 *   <li><b>灯被敲掉</b>（{@link PlayerBlockBreakEvents#AFTER}）：玩家敲掉的那一格（以及它上下相邻格）
 *       如果登记表里有条目，立刻删 —— 这是"敲掉就马上生效"的那条路，不用等轮转扫到。</li>
 *   <li><b>常态轮转</b>：每秒挑几个已加载区块重新核对一遍。这是**兜底**：爆炸、活塞、方块自己消失、
 *       或者别的模组动的世界，事件收不到的那种改动由它收敛；灯被放下的那种情况也主要靠它（放灯没有
 *       对应的事件钩子，而放下的灯所在区块早就加载好了，不会触发第 1 条）。</li>
 * </ol>
 *
 * <h3>删除的安全边界（不能放宽）</h3>
 * <p>只能删**所在区块已加载、但区块里找不到它**的条目。玩家走远区块卸载之后，"找不到"只说明没加载，
 * 不代表灯没了 —— 按"没找到就删"会把远处的灯全部清掉。所以同步永远**按区块**做，绝不"全表对全世界"。</p>
 *
 * <h3>开销</h3>
 * <p>一个区块的核对 = 遍历该区块的方块实体（通常个位数）+ 扫一遍登记表（百来条），都跟世界规模无关；
 * 轮转预算按"一分钟走完一圈"算（见 {@link #SWEEP_TARGET_SECONDS}），区块再多也不会在某一 tick 里爆掉。
 * 每 tick 的上限是为了让加载期不卡 tick，而不是因为单块贵。</p>
 */
public final class MmtrSignalSync {

	/** 一次（区块）同步的结果。坐标文本留一份是为了日志能直接指出是哪盏灯。 */
	public static final class Result {
		public int found;
		public int added;
		public int skippedBound;
		public int removed;
		public int removedBound;
		public final List<String> addedKeys = new ArrayList<>();
		public final List<String> removedKeys = new ArrayList<>();

		public void merge(Result other) {
			found += other.found;
			added += other.added;
			skippedBound += other.skippedBound;
			removed += other.removed;
			removedBound += other.removedBound;
			addedKeys.addAll(other.addedKeys);
			removedKeys.addAll(other.removedKeys);
		}

		public boolean changed() {
			return added > 0 || removed > 0;
		}
	}

	/** 刚加载、还等着核对的区块（按加载顺序，先加载先扫）。 */
	private static final Map<ServerWorld, Set<ChunkPos>> PENDING = new HashMap<>();
	/** 常态轮转的游标（索引进当前已加载区块列表）。 */
	private static final Map<ServerWorld, Integer> SWEEP_CURSOR = new HashMap<>();

	/** 队列长过这个数就说明在批量加载（开服/传送），这时的每 tick 预算放大。 */
	private static final int PENDING_BURST_QUEUE = 64;
	private static final int PENDING_PER_TICK = 2;
	private static final int PENDING_PER_TICK_BURST = 12;
	/** 轮转扫一轮的目标秒数：预算按它摊（区块越多，每秒扫的越多，但一轮始终大约这么久）。 */
	private static final int SWEEP_TARGET_SECONDS = 60;
	private static final int SWEEP_MIN_PER_PASS = 4;
	private static final int SWEEP_MAX_PER_PASS = 128;
	/** 一次日志最多列几个坐标（变化多的时候只报数量，不然刷屏）。 */
	private static final int LOG_KEY_LIMIT = 6;

	private MmtrSignalSync() {
	}

	public static void register() {
		ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
			synchronized (PENDING) {
				PENDING.computeIfAbsent(world, ignored -> new LinkedHashSet<>()).add(chunk.getPos());
			}
		});
		/*
		 * 敲灯这条路走的事件是"方块已经被敲掉之后"（AFTER），不是 BEFORE：BEFORE 时世界还没改，
		 * 我们看到的还是那盏灯，判断不出"它没了"。
		 */
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (world instanceof ServerWorld) {
				onBlockBroken((ServerWorld) world, pos);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(MmtrSignalSync::tick);
	}

	/**
	 * 玩家敲掉 {@code pos} 这一格之后的即时同步。
	 *
	 * <h3>为什么查 {@code y-1 .. y+2} 四格，而不是只查被敲的那一格</h3>
	 * <p>一盏 MTR 信号灯是**两格高**的，登记时记的可能是上面那格也可能是下面那格（历史上两种情况都出现过：
	 * 同一盏灯同时登记在 {@code -69,-59,-203} 与 {@code -69,-60,-203}）。玩家敲哪一格取决于他瞄的是哪儿，
	 * 而"灯被敲掉"这件事在两格上都会发生。往下多查两格还有一个用处：把灯**脚下垫的那块**敲了，
	 * 灯自己会掉 —— 那一下不查，条目就要等到轮转扫到才消失。</p>
	 *
	 * <p>查不到条目的格子上 {@code remove} 只是返回 false，没有副作用；真的判断错了（比如垫块掉了灯却还在），
	 * 轮转扫描会把它重新登记回来 —— 这一层兜底正是敢在这里放宽的原因。</p>
	 */
	private static void onBlockBroken(ServerWorld serverWorld, BlockPos pos) {
		final Simulator simulator = simulatorOf(serverWorld);
		if (simulator == null) {
			return;
		}
		for (int dy = -1; dy <= 2; dy++) {
			final int y = pos.getY() + dy;
			if (simulator.mmtrSignals.get(pos.getX(), y, pos.getZ()) == null) {
				continue;
			}
			if (simulator.mmtrSignalRemove(pos.getX(), y, pos.getZ())) {
				logLine("[MMTR-SIG] 自动刷新：灯被敲掉，删除登记 " + pos.getX() + "," + y + "," + pos.getZ());
			}
		}
	}

	private static void tick(MinecraftServer minecraftServer) {
		for (final ServerWorld serverWorld : minecraftServer.getWorlds()) {
			final Simulator simulator = simulatorOf(serverWorld);
			if (simulator == null) {
				// 引擎还没挂上（世界刚起、或者不是 MMTR 世界的维度）：排队的区块留着，下一 tick 再试
				continue;
			}
			drainPending(simulator, serverWorld);
			if (minecraftServer.getTicks() % 20 == 0) {
				sweepOnePass(simulator, serverWorld);
			}
		}
	}

	/** 把刚加载的区块按预算核对掉（批量加载时预算放大，见 {@link #PENDING_BURST_QUEUE}）。 */
	private static void drainPending(Simulator simulator, ServerWorld serverWorld) {
		final Set<ChunkPos> queue;
		synchronized (PENDING) {
			queue = PENDING.get(serverWorld);
		}
		if (queue == null) {
			return;
		}
		final int budget = pendingBudget(queue.size());
		final List<ChunkPos> done = new ArrayList<>(budget);
		synchronized (PENDING) {
			final Iterator<ChunkPos> iterator = queue.iterator();
			while (iterator.hasNext() && done.size() < budget) {
				done.add(iterator.next());
				iterator.remove();
			}
		}
		for (final ChunkPos pos : done) {
			// 排队的区块可能已经卸载了（加载事件先来、卸载随后）：这时跳过，它再加载时会重新入队
			final WorldChunk chunk = serverWorld.getChunkManager().getWorldChunk(pos.x, pos.z);
			if (chunk == null) {
				continue;
			}
			report(syncChunk(simulator, serverWorld, chunk), false);
		}
	}

	private static int pendingBudget(int queueSize) {
		return queueSize >= PENDING_BURST_QUEUE ? PENDING_PER_TICK_BURST : PENDING_PER_TICK;
	}

	/**
	 * 常态轮转：从游标处挑几个已加载区块核对一遍。
	 *
	 * <p>游标是"上次扫到哪"，不是"扫过的都安全" —— 区块列表每 tick 都在变（加载/卸载都会改变顺序），
	 * 所以游标越界或者指向别的区块都不影响正确性：一轮下来会把当前所有已加载区块都覆盖到。</p>
	 */
	private static void sweepOnePass(Simulator simulator, ServerWorld serverWorld) {
		final List<WorldChunk> chunks = MmtrChunkTracker.loadedChunks(serverWorld);
		if (chunks.isEmpty()) {
			SWEEP_CURSOR.remove(serverWorld);
			return;
		}
		final int perPass = Math.max(SWEEP_MIN_PER_PASS,
			Math.min(SWEEP_MAX_PER_PASS, (chunks.size() + SWEEP_TARGET_SECONDS - 1) / SWEEP_TARGET_SECONDS));
		int cursor = SWEEP_CURSOR.getOrDefault(serverWorld, 0);
		if (cursor < 0 || cursor >= chunks.size()) {
			cursor = 0;
		}
		final Result total = new Result();
		for (int i = 0; i < perPass; i++) {
			total.merge(syncChunk(simulator, serverWorld, chunks.get((cursor + i) % chunks.size())));
		}
		SWEEP_CURSOR.put(serverWorld, (cursor + perPass) % chunks.size());
		report(total, true);
	}

	/**
	 * 核对**一个区块**：把这一块里真的存在的灯登记/刷新一遍，再清掉这一块里登记过、世界里却没有的条目。
	 *
	 * <p>这是手动扫描（{@code world scan-signals}）和自动刷新共用的那一份实现 —— 两条路必须完全一致，
	 * 否则"手动扫出来的结果"和"自动收敛的结果"会不一样，那就没法用它来解释任何事。</p>
	 */
	public static Result syncChunk(Simulator simulator, ServerWorld serverWorld, WorldChunk chunk) {
		final Result result = new Result();
		final long chunkKey = chunkKey(chunk.getPos().x, chunk.getPos().z);
		final World world = new World(serverWorld);
		final Set<String> seen = new HashSet<>();
		// 先把这一块的方块实体抄一份再遍历：下面会在遍历中改登记表，直接迭代原集合时序上更脆
		final List<BlockEntity> blockEntities = new ArrayList<>(chunk.getBlockEntities().values());
		for (final BlockEntity blockEntity : blockEntities) {
			final BlockPos pos = blockEntity.getPos();
			final BlockState blockState = world.getBlockState(
				new org.mtr.mapping.holder.BlockPos(pos.getX(), pos.getY(), pos.getZ()));
			final Object block = blockState.getBlock().data;
			if (!MmtrSignalBlocks.isSignalLight(block)) {
				continue;
			}
			result.found++;
			// 一盏灯方块两格高，扫描会把两格都记下 —— 两格都算"看到过"，
			// 否则删除那一步会把另一格误判成"灯没了"。
			seen.add(MmtrSignalRegistry.key(pos.getX(), pos.getY(), pos.getZ()));
			seen.add(MmtrSignalRegistry.key(pos.getX(), pos.getY() - 1, pos.getZ()));
			final SignalEntry existing = simulator.mmtrSignals.get(pos.getX(), pos.getY(), pos.getZ());
			if (existing != null && "BOUND".equals(existing.mode)) {
				// 人工绑定过的灯：朝向/灯位数还是照世界刷新（原地改，不动绑定），但只计数不重建
				result.skippedBound++;
			}
			if (simulator.mmtrSignalRefresh(pos.getX(), pos.getY(), pos.getZ(),
				BlockSignalBase.getAngle(blockState), MmtrSignalBlocks.aspectsOf(block))) {
				result.added++;
				result.addedKeys.add(pos.getX() + "," + pos.getY() + "," + pos.getZ());
			}
		}
		// 清掉"这个区块已加载、却不在世界里"的登记：这正是被玩家敲掉的灯
		final List<SignalEntry> stale = new ArrayList<>();
		for (final SignalEntry entry : simulator.mmtrSignals.signals.values()) {
			if (chunkKey(entry.x >> 4, entry.z >> 4) != chunkKey) {
				continue;
			}
			if (seen.contains(MmtrSignalRegistry.key(entry.x, entry.y, entry.z))) {
				continue;
			}
			stale.add(entry);
		}
		for (final SignalEntry entry : stale) {
			final boolean bound = "BOUND".equals(entry.mode);
			if (simulator.mmtrSignalRemove(entry.x, entry.y, entry.z)) {
				result.removed++;
				result.removedKeys.add(entry.x + "," + entry.y + "," + entry.z);
				if (bound) {
					result.removedBound++;
				}
			}
		}
		return result;
	}

	/** 没变化就不出声：轮转每秒都在跑，只有真的改了才值得写一行日志。 */
	public static void report(Result result, boolean sweep) {
		if (!result.changed()) {
			return;
		}
		final StringBuilder builder = new StringBuilder("[MMTR-SIG] ");
		builder.append(sweep ? "自动刷新：轮转核对" : "自动刷新：区块加载");
		if (result.added > 0) {
			builder.append(" 新增 ").append(result.added).append(" 盏 ").append(keys(result.addedKeys));
		}
		if (result.removed > 0) {
			builder.append(" 清理 ").append(result.removed).append(" 盏（世界里的灯已经没了）").append(keys(result.removedKeys));
			if (result.removedBound > 0) {
				builder.append("（其中 ").append(result.removedBound).append(" 个是人工绑定）");
			}
		}
		logLine(builder.toString());
	}

	private static String keys(List<String> keys) {
		if (keys.size() <= LOG_KEY_LIMIT) {
			return keys.toString();
		}
		return keys.subList(0, LOG_KEY_LIMIT) + " 等 " + keys.size() + " 处";
	}

	private static void logLine(String message) {
		System.out.println(message);
	}

	private static Simulator simulatorOf(ServerWorld serverWorld) {
		final Main main = Init.getMain();
		return main == null ? null : main.getSimulator(Init.getWorldId(new World(serverWorld)));
	}

	private static long chunkKey(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
	}
}
