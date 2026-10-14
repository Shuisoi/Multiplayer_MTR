package org.mtr.mod.mmtr.stopmark;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.mtr.core.Main;
import org.mtr.core.data.Platform;
import org.mtr.core.mmtr.stopmark.MmtrStopMarkerGeometry;
import org.mtr.core.mmtr.stopmark.MmtrStopMarkerRegistry;
import org.mtr.core.simulation.Simulator;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.Init;
import org.mtr.mod.Items;
import org.mtr.mod.block.BlockPlatform;
import org.mtr.mod.block.BlockStopMarker;
import org.mtr.mod.mmtr.MmtrChunkTracker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 停车对标牌子 ↔ 引擎登记表 的同步层（扫世界 + 放/拆即登记 + 刷子取归属）。
 *
 * <h3>为什么这条同步比信号灯那条简单</h3>
 * <p>灯是**区间的切点**：少登记一盏，区间立刻算错，所以 {@code MmtrSignalSync} 要在区块加载、
 * 敲灯、每秒轮转三条路上抢时间。牌子只影响"停在哪儿"，晚几十秒登记不会让世界错乱 ——
 * 所以这里只留两条**必要**的路（放/拆事件、以及一圈一分钟的兜底轮转），不做"区块加载即刷"。
 * 但入口与结论仍然只有一份实现（{@link #syncChunk}）：手动 {@code stop-markers scan} 与自动轮转
 * 共用它 —— 两条路各写一份就会出现"手动扫好了、自动又改回去"。</p>
 *
 * <h3>删除的安全边界（与信号灯同一条）</h3>
 * <p>只能删**所在区块已加载、但区块里找不到它**的条目。玩家走远区块卸载之后，"找不到"只说明
 * 没加载，不代表牌子拆了。</p>
 *
 * <h3>牌子的属性是谁写的</h3>
 * <p>批量铺牌子走的是 {@code /setblock} + {@code /data merge block}（生成器），**不会**触发方块
 * 的 {@code onPlaced2}；所以那一圈轮转扫是必需的，不能只靠放置事件。</p>
 */
public final class MmtrStopMarkerSync {

	/** 一次（区块）同步的结果。坐标文本留一份是为了日志能直接指出是哪块牌子。 */
	public static final class Result {
		public int found;
		public int added;
		public int removed;
		public int skipped;
		public final List<String> addedKeys = new ArrayList<>();
		public final List<String> removedKeys = new ArrayList<>();

		public void merge(Result other) {
			found += other.found;
			added += other.added;
			removed += other.removed;
			skipped += other.skipped;
			addedKeys.addAll(other.addedKeys);
			removedKeys.addAll(other.removedKeys);
		}

		public boolean changed() {
			return added > 0 || removed > 0;
		}
	}

	/** 刷子点过的站台（"先记 ID、再写到牌子上"的第一步）。只存 id/hex/名字，不持有站台对象。 */
	public record BoundPlatform(long platformId, String platformHex, String name) {
	}

	private static final Map<UUID, BoundPlatform> PENDING_PLATFORMS = new HashMap<>();
	/** 刚加载、还等着核对的区块（按加载顺序）。 */
	private static final Map<ServerWorld, Set<ChunkPos>> PENDING = new HashMap<>();
	/** 常态轮转的游标。 */
	private static final Map<ServerWorld, Integer> SWEEP_CURSOR = new HashMap<>();

	private static final int PENDING_PER_TICK = 4;
	/** 轮转扫一轮的目标秒数（每秒 20 tick，每 20 tick 扫一批）。 */
	private static final int SWEEP_TARGET_SECONDS = 60;
	private static final int SWEEP_MIN_PER_PASS = 2;
	private static final int SWEEP_MAX_PER_PASS = 64;

	private MmtrStopMarkerSync() {
	}

	public static void register() {
		ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
			synchronized (PENDING) {
				PENDING.computeIfAbsent(world, ignored -> new LinkedHashSet<>()).add(chunk.getPos());
			}
		});
		/*
		 * 拆牌子：登记表里立刻删。往下多查两格与信号灯同一个理由 —— 牌子可能挂在杆顶或贴墙上，
		 * 玩家瞄的那一格未必就是方块实体所在的那一格；把垫在下面的方块敲了牌子会掉，
		 * 那一下不查就要等到轮转扫到才消失。
		 */
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (world instanceof ServerWorld) {
				onBlockBroken((ServerWorld) world, pos);
			}
		});
		/*
		 * 刷子点站台方块 = "记下归属"（牌子的 ID）。
		 *
		 * <p>为什么走事件而不是给站台方块加 onUse2：站台方块是 MTR 自己的类（{@code BlockPlatform}
		 * 没有任何交互），动它就会牵到上游代码。这里只认"手持刷子 + 点到站台方块"，其余点击一律
		 * {@code PASS} 放行 —— 不吞别人的交互。</p>
		 */
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			/*
			 * 事件用的是**原生**类型（Fabric 的回调签名是 MC 的类），不是映射层的包装类：
			 * 返回值必须是 net.minecraft.util.ActionResult。混用会让 lambda 的返回类型对不上。
			 */
			if (!(world instanceof ServerWorld)) {
				return net.minecraft.util.ActionResult.PASS;
			}
			final PlayerEntity mappingPlayer = new PlayerEntity(player);
			if (!mappingPlayer.isHolding(Items.BRUSH.get().asItem())) {
				return net.minecraft.util.ActionResult.PASS;
			}
			final BlockPos pos = hitResult.getBlockPos();
			if (!(world.getBlockState(pos).getBlock() instanceof BlockPlatform)) {
				return net.minecraft.util.ActionResult.PASS;
			}
			final Simulator simulator = simulatorOf((ServerWorld) world);
			if (simulator == null) {
				return net.minecraft.util.ActionResult.PASS;
			}
			final ServerPlayerEntity serverPlayer = ServerPlayerEntity.cast(mappingPlayer);
			final Platform platform = MmtrStopMarkerGeometry.nearbyPlatform(simulator, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
			if (platform == null) {
				feedback(serverPlayer, "这块站台方块找不到归属站台（站台数据里没有它）");
				return net.minecraft.util.ActionResult.SUCCESS;
			}
			PENDING_PLATFORMS.put(player.getUuid(), new BoundPlatform(platform.getId(), platform.getHexId(),
				platform.getStationName() + " " + platform.getName()));
			feedback(serverPlayer, "已记下站台 " + platform.getHexId() + "（" + platform.getStationName() + " "
				+ platform.getName() + "）→ 右键牌子写入归属");
			return net.minecraft.util.ActionResult.SUCCESS;
		});
		ServerTickEvents.END_SERVER_TICK.register(MmtrStopMarkerSync::tick);
	}

	/**
	 * 取走待绑站台（取走即清）；没有就返回 null。
	 *
	 * <p>键用 uuid 而不是玩家对象：待绑是"这一步操作"的状态，玩家下线/重连都不该带着走；
	 * 而"取走即清"避免"绑错一次，后面每块牌子都跟着绑错"。</p>
	 */
	public static BoundPlatform takePendingPlatform(UUID playerUuid) {
		return PENDING_PLATFORMS.remove(playerUuid);
	}

	public static void feedback(ServerPlayerEntity player, String text) {
		player.sendMessage(new Text(TextHelper.literal(text).data), true);
	}

	/** 把登记表里的一条牌子描述成一行（日志与指令共用）。 */
	public static String describe(MmtrStopMarkerRegistry.StopMarkerEntry entry) {
		return entry.x + "," + entry.y + "," + entry.z
			+ " 站台=" + (entry.platformHex.isEmpty() ? String.valueOf(entry.platformId) : entry.platformHex)
			+ " " + entry.cars + "辆 方向=" + (entry.towardEnd0 ? "朝轨端0" : "朝轨端1")
			+ " fraction=" + String.format("%.4f", entry.fraction);
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
				final Result result = sweepOnePass(simulator, serverWorld);
				if (result.changed()) {
					System.out.println("[MMTR-STOP] 自动刷新：轮转核对 新增 " + result.added + " 块"
						+ (result.removed > 0 ? "、清理 " + result.removed + " 块（世界里的牌子已经没了）" : ""));
				}
			}
		}
	}

	private static void drainPending(Simulator simulator, ServerWorld serverWorld) {
		final Set<ChunkPos> queue;
		synchronized (PENDING) {
			queue = PENDING.get(serverWorld);
		}
		if (queue == null || queue.isEmpty()) {
			return;
		}
		final List<ChunkPos> done = new ArrayList<>(PENDING_PER_TICK);
		synchronized (PENDING) {
			final Iterator<ChunkPos> iterator = queue.iterator();
			while (iterator.hasNext() && done.size() < PENDING_PER_TICK) {
				done.add(iterator.next());
				iterator.remove();
			}
		}
		for (final ChunkPos pos : done) {
			// 排队的区块可能已经卸载了（加载事件先来、卸载随后）：跳过，它再加载时会重新入队
			final WorldChunk chunk = serverWorld.getChunkManager().getWorldChunk(pos.x, pos.z);
			if (chunk != null) {
				syncChunk(simulator, serverWorld, chunk);
			}
		}
	}

	private static Result sweepOnePass(Simulator simulator, ServerWorld serverWorld) {
		final List<WorldChunk> chunks = MmtrChunkTracker.loadedChunks(serverWorld);
		final Result total = new Result();
		if (chunks.isEmpty()) {
			SWEEP_CURSOR.remove(serverWorld);
			return total;
		}
		final int perPass = Math.max(SWEEP_MIN_PER_PASS,
			Math.min(SWEEP_MAX_PER_PASS, (chunks.size() + SWEEP_TARGET_SECONDS - 1) / SWEEP_TARGET_SECONDS));
		int cursor = SWEEP_CURSOR.getOrDefault(serverWorld, 0);
		if (cursor < 0 || cursor >= chunks.size()) {
			cursor = 0;
		}
		for (int i = 0; i < perPass; i++) {
			total.merge(syncChunk(simulator, serverWorld, chunks.get((cursor + i) % chunks.size())));
		}
		SWEEP_CURSOR.put(serverWorld, (cursor + perPass) % chunks.size());
		return total;
	}

	/** 手动全量扫描（引擎指令 {@code world scan-stop-markers} → 游戏端 {@code stop-markers scan}）。 */
	public static Result scanAll(Simulator simulator, ServerWorld serverWorld) {
		final Result total = new Result();
		for (final WorldChunk chunk : MmtrChunkTracker.loadedChunks(serverWorld)) {
			total.merge(syncChunk(simulator, serverWorld, chunk));
		}
		return total;
	}

	/**
	 * 核对**一个区块**：区块里真的存在的牌子登记/刷新一遍，再清掉这个区块里登记过、世界里却没有的条目。
	 *
	 * <p>与 {@code MmtrSignalSync.syncChunk} 同一形状：这是手动扫描与自动轮转共用的唯一实现。</p>
	 */
	public static Result syncChunk(Simulator simulator, ServerWorld serverWorld, WorldChunk chunk) {
		final Result result = new Result();
		final long chunkKey = chunkKey(chunk.getPos().x, chunk.getPos().z);
		final Set<String> seen = new HashSet<>();
		// 先把这一块的方块实体抄一份再遍历：下面会在遍历中改登记表，直接迭代原集合时序上更脆
		final List<BlockEntity> blockEntities = new ArrayList<>(chunk.getBlockEntities().values());
		for (final BlockEntity blockEntity : blockEntities) {
			if (!(blockEntity instanceof BlockStopMarker.BlockEntity)) {
				continue;
			}
			final BlockPos pos = blockEntity.getPos();
			final BlockStopMarker.BlockEntity marker = (BlockStopMarker.BlockEntity) blockEntity;
			seen.add(MmtrStopMarkerRegistry.key(pos.getX(), pos.getY(), pos.getZ()));
			result.found++;
			if (marker.getCars() < BlockStopMarker.CARS_MIN || marker.getCars() > BlockStopMarker.CARS_MAX) {
				/*
				 * 辆数没设定（或越界）：这块牌子不参与停车基准。
				 *
				 * **留着不删**：它可能只是"铺了一半"（生成器写到一半被杀）或者玩家刚放下还没拨辆数，
				 * 删掉登记会让现场少一条线索 —— 而它本来也没进登记表，留着没有任何副作用。
				 */
				result.skipped++;
				continue;
			}
			final boolean isNew = simulator.mmtrStopMarkers.get(pos.getX(), pos.getY(), pos.getZ()) == null;
			if (simulator.mmtrStopMarkerRegister(pos.getX(), pos.getY(), pos.getZ(), marker.getCars(),
				marker.getPlatformId(), marker.getLabel(), marker.getDatumM())) {
				result.added++;
				if (isNew) {
					result.addedKeys.add(MmtrStopMarkerRegistry.key(pos.getX(), pos.getY(), pos.getZ()));
				}
			}
		}
		// 清掉"这个区块已加载、却不在世界里"的登记：这正是被玩家拆掉（或被 /setblock air 抹掉）的牌子
		final List<MmtrStopMarkerRegistry.StopMarkerEntry> stale = new ArrayList<>();
		for (final MmtrStopMarkerRegistry.StopMarkerEntry entry : simulator.mmtrStopMarkers.markers.values()) {
			if (chunkKey(entry.x >> 4, entry.z >> 4) != chunkKey
				|| seen.contains(MmtrStopMarkerRegistry.key(entry.x, entry.y, entry.z))) {
				continue;
			}
			stale.add(entry);
		}
		for (final MmtrStopMarkerRegistry.StopMarkerEntry entry : stale) {
			if (simulator.mmtrStopMarkerRemove(entry.x, entry.y, entry.z)) {
				result.removed++;
				result.removedKeys.add(MmtrStopMarkerRegistry.key(entry.x, entry.y, entry.z));
			}
		}
		return result;
	}

	/**
	 * 单块牌子立刻登记（放下 / 刷子改完辆数或归属 / 换样式后调用）。
	 *
	 * <p>只服务"人手动动过的那一块"：批量铺牌子的路径（{@code /setblock} + {@code /data merge}）
	 * 没有事件钩子，由轮转扫兜底。</p>
	 */
	public static void syncOne(World world, org.mtr.mapping.holder.BlockPos pos) {
		if (world == null || world.isClient() || !(world.data instanceof ServerWorld)) {
			return;
		}
		final ServerWorld serverWorld = (ServerWorld) world.data;
		final Simulator simulator = simulatorOf(serverWorld);
		if (simulator == null) {
			return;
		}
		final BlockEntity raw = serverWorld.getBlockEntity(new BlockPos(pos.getX(), pos.getY(), pos.getZ()));
		if (!(raw instanceof BlockStopMarker.BlockEntity)) {
			simulator.mmtrStopMarkerRemove(pos.getX(), pos.getY(), pos.getZ());
			return;
		}
		final BlockStopMarker.BlockEntity marker = (BlockStopMarker.BlockEntity) raw;
		if (marker.getCars() < BlockStopMarker.CARS_MIN || marker.getCars() > BlockStopMarker.CARS_MAX) {
			simulator.mmtrStopMarkerRemove(pos.getX(), pos.getY(), pos.getZ());
			System.out.println("[MMTR-STOP] " + pos.getX() + "," + pos.getY() + "," + pos.getZ() + " 辆数未设定，暂不计入停车基准");
			return;
		}
		if (simulator.mmtrStopMarkerRegister(pos.getX(), pos.getY(), pos.getZ(), marker.getCars(),
			marker.getPlatformId(), marker.getLabel(), marker.getDatumM())) {
			final MmtrStopMarkerRegistry.StopMarkerEntry entry = simulator.mmtrStopMarkers.get(pos.getX(), pos.getY(), pos.getZ());
			System.out.println("[MMTR-STOP] 登记 " + (entry == null
				? MmtrStopMarkerRegistry.key(pos.getX(), pos.getY(), pos.getZ()) : describe(entry)));
		}
	}

	private static void onBlockBroken(ServerWorld serverWorld, BlockPos pos) {
		final Simulator simulator = simulatorOf(serverWorld);
		if (simulator == null) {
			return;
		}
		for (int dy = -1; dy <= 2; dy++) {
			final int y = pos.getY() + dy;
			if (simulator.mmtrStopMarkers.get(pos.getX(), y, pos.getZ()) != null
				&& simulator.mmtrStopMarkerRemove(pos.getX(), y, pos.getZ())) {
				System.out.println("[MMTR-STOP] 牌子被拆，删除登记 " + pos.getX() + "," + y + "," + pos.getZ());
			}
		}
	}

	private static Simulator simulatorOf(ServerWorld serverWorld) {
		final Main main = Init.getMain();
		return main == null ? null : main.getSimulator(Init.getWorldId(new World(serverWorld)));
	}

	private static long chunkKey(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
	}
}
