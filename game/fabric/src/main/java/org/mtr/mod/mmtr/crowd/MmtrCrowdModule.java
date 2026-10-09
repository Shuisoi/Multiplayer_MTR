package org.mtr.mod.mmtr.crowd;

import org.mtr.core.Main;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.mmtr.crowd.MmtrCrowd;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.mtr.core.tool.Vector;
import org.mtr.mapping.holder.*;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockCrowdVillager;
import org.mtr.mod.block.PlatformHelper;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * **站台客流生成器**：把引擎里的站台客量（{@code Platform.crowdLevel}，0–100%）变成站台上站着的**村民实体**。
 *
 * <h3>口径（用户 2026-10 定的三条）</h3>
 * <ol>
 *   <li><b>站台边挤</b>：人只站**紧靠轨道的那一排**站台方块上（不是在站台面上乱撒），朝向轨道。</li>
 *   <li><b>1 格一人 = 100%</b>：客量百分比直接就是那一排的占用率 —— 100% 时每格一个人，
 *       60% 就是六成的格子有人（挑哪几格由站台 id 的哈希决定，看上去是自然的空档）。</li>
 *   <li><b>实体而不是方块</b>：用户口径"需要点动态的……fresh animations 那个"。Fresh Animations 由
 *       EMF/ETF 从**实体渲染管线**换模型加动画，方块与方块实体都不吃 —— 所以人必须是真村民实体。
 *       渲染整条交给原版渲染器 + 资源包，模组这边不写客户端渲染代码。</li>
 * </ol>
 *
 * <h3>怎么找到"站台边缘那一排"</h3>
 * <p>站台实体只给了两个轨道端点（{@code position1/position2}），没有"站台面在哪、多宽"的信息。
 * 所以沿站台轨每 1 格取一个点，在**法线两侧**从近到远找第一块 MTR 站台方块
 * （{@code PlatformHelper}，含 full/slab/indented 各种）—— 找到的那块就是站台边缘，
 * 人放在它**上面那一格**。岛式站台两侧都有站台面时按位置哈希挑一侧。</p>
 *
 * <h3>三件必须守住的事</h3>
 * <ul>
 *   <li><b>不加载区块</b>：读任何方块/生成任何实体前先 {@link #isLoaded}。未加载的区块里
 *       {@code getBlockState} 会把区块从磁盘拉起来（甚至生成地形），一个 200 格站台能把服务端卡住。</li>
 *   <li><b>摊在多个 tick 里</b>：客量 0 → 100% 是两百多只实体，照 {@code RailAction} 的做法给每 tick
 *       2 ms 预算，做不完下一拍接着做。</li>
 *   <li><b>世界是唯一账本</b>：谁是"我们的人"写在实体 tag 里（见 {@link MmtrCrowdVillagers}），
 *       不在内存里记账 —— 服务端重启、区块卸载重载、玩家把人推走，下一拍都能从世界里重新读出来并收敛。</li>
 * </ul>
 */
public final class MmtrCrowdModule {

	/** 每隔这么久核对一遍所有站台（自愈节拍）。 */
	private static final long PASS_INTERVAL_MILLIS = 5_000L;
	/** 站台几何（轨/端点/站台方块）至少这么久重扫一次：玩家改站台面、重铺站台轨都能被发现。 */
	private static final long RESCAN_INTERVAL_MILLIS = 60_000L;
	/** 一个 tick 最多重扫几何花多少毫秒（摊到多拍；见 {@link #scanNext}）。 */
	private static final long SCAN_BUDGET_MILLIS = 8L;
	/** 一个 tick 里最多花多少毫秒生成/回收/纠正村民。 */
	private static final long APPLY_BUDGET_MILLIS = 2L;
	/** 单 tick 实体操作数的兜底上限。 */
	private static final int MAX_OPS_PER_TICK = 400;
	/** 沿站台轨取点的步长（格）：1 格一人 = 100%。 */
	private static final double SAMPLE_STEP = 1.0;
	/** 从轨道往两侧找站台方块的最远距离（格）。 */
	private static final int SIDE_SEARCH_BLOCKS = 5;
	/** 找站台面时试的高度（相对轨面）：站台方块一般和轨节点同一层，上下各留一格余量。 */
	private static final int[] Y_OFFSETS = {0, -1, 1, -2, 2};

	private static final Map<String, MmtrCrowdModule> MODULES = new HashMap<>();

	/** 服务端停机时清掉每世界状态（世界对象本身会在重开时重建）。 */
	public static void clear() {
		MODULES.clear();
	}

	/**
	 * 每个服务端世界 tick 调一次（挂在 {@code Init} 的 END_WORLD_TICK 上）。
	 */
	public static void tick(ServerWorld serverWorld) {
		if (serverWorld == null) {
			return;
		}
		MODULES.computeIfAbsent(dimensionOf(serverWorld), dimension -> new MmtrCrowdModule(serverWorld, dimension)).tickInternal();
	}

	/**
	 * 按**维度名**认世界，不按 {@code ServerWorld} 包装对象认。
	 *
	 * <p>为什么：映射层每次给的都是一个新的 {@code HolderBase} 包装（它没有自己的 equals/hashCode），
	 * 拿包装对象当 Map 键会每 tick 生成一个新模块，还永远查不到上一次那份状态。
	 * 维度名（{@code minecraft/overworld}）在整个服务端生命周期里是稳定且唯一的。</p>
	 */
	private static String dimensionOf(ServerWorld serverWorld) {
		return Init.getWorldId(new World(serverWorld.data));
	}

	// ------------------------------------------------------------------ 实例

	private final ServerWorld world;
	private final String dimension;
	private final Map<Long, Plan> plans = new HashMap<>();
	/** 该格该有一个人（生成/拉回）。 */
	private final ArrayDeque<EntityOp> entityQueue = new ArrayDeque<>();
	/** 遗留的客流**方块**（上一步用方块实现时铺下的）：换成实体之后由这里清掉。 */
	private final ArrayDeque<BlockOp> blockQueue = new ArrayDeque<>();
	private long lastPassMillis;
	private long lastRevision = Long.MIN_VALUE;
	private int scanCursor;

	private MmtrCrowdModule(ServerWorld world, String dimension) {
		this.world = world;
		this.dimension = dimension;
	}

	private void tickInternal() {
		drainQueues();
		mirrorConfigIfChanged();

		/*
		 * 为什么**不**按"有没有玩家在线"开闸：映射 API 这一层没有"列玩家"的方法，
		 * 而按玩家数开闸要拿原始 net.minecraft 类型，等于把一个纯服务端逻辑绑死在加载器上。
		 * 代价本来也只在"有东西要改"的时候才产生：核对一拍是几百次读方块 + 一次实体枚举，5 秒一次。
		 */
		final long now = System.currentTimeMillis();
		final long revision = MmtrCrowd.revision();
		final boolean revisionChanged = revision != lastRevision;
		if (!revisionChanged && now - lastPassMillis < PASS_INTERVAL_MILLIS) {
			return;
		}
		lastPassMillis = now;
		lastRevision = revision;

		final Simulator simulator = simulator();
		if (simulator == null) {
			return;
		}
		final List<Platform> platforms = snapshotPlatforms(simulator);
		prunePlans(platforms);
		scanNext(platforms, now);

		// 一次枚举世界，按站台分好组，后面每个站台都用这一份（别每个站台各扫一遍实体表）。
		final Map<String, List<MmtrCrowdVillagers.Crowd>> crowdsByPlatform = collectCrowds();

		for (final Platform platform : platforms) {
			final Plan plan = plans.get(platform.getId());
			if (plan != null && plan.spots != null) {
				enqueueDiff(platform, plan, crowdsByPlatform.getOrDefault(MmtrCrowdVillagers.platformTag(platform.getId()), new ArrayList<>()));
			}
		}
	}

	private Simulator simulator() {
		final Main main = Init.getMain();
		return main == null ? null : main.getSimulator(dimension);
	}

	/** 引擎数据在另一个线程上可能正在变，取一份快照再遍历（照 MMTR 其它读引擎的地方的做法）。 */
	private List<Platform> snapshotPlatforms(Simulator simulator) {
		try {
			return new ArrayList<>(simulator.platforms);
		} catch (Exception e) {
			return new ArrayList<>();
		}
	}

	/** 世界里我们铺的村民，按平台 tag 分组。 */
	private Map<String, List<MmtrCrowdVillagers.Crowd>> collectCrowds() {
		final Map<String, List<MmtrCrowdVillagers.Crowd>> grouped = new HashMap<>();
		for (final MmtrCrowdVillagers.Crowd crowd : MmtrCrowdVillagers.collect(world.data)) {
			grouped.computeIfAbsent(crowd.platformTag, tag -> new ArrayList<>()).add(crowd);
		}
		return grouped;
	}

	/** 站台没了（拆站台轨 / 删站台）⇒ 把它的人一起收走。 */
	private void prunePlans(List<Platform> platforms) {
		final Set<Long> alive = new HashSet<>();
		for (final Platform platform : platforms) {
			alive.add(platform.getId());
		}
		final List<Long> removed = new ArrayList<>();
		for (final Long id : plans.keySet()) {
			if (!alive.contains(id)) {
				removed.add(id);
			}
		}
		for (final Long id : removed) {
			plans.remove(id);
			// 人由 tag 认出来：把带这个平台 tag 的全部收走（记忆格靠 tag 里的坐标还原）。
			for (final MmtrCrowdVillagers.Crowd crowd : MmtrCrowdVillagers.collect(world.data)) {
				if (crowd.platformTag.equals(MmtrCrowdVillagers.platformTag(id))) {
					MmtrCrowdVillagers.discard(crowd.entity);
				}
			}
		}
	}

	// ------------------------------------------------------------------ 几何扫描

	/**
	 * 把需要重扫的站台扫一遍（几何变了 / 从没扫过 / **上次扫的时候区块没加载** / 太久没扫）。
	 *
	 * <p>为什么要按**时间预算**而不是"每拍扫 N 个"：站台上的人只有在区块加载后才找得到站台方块，
	 * 而"哪些区块加载了"随时在变（玩家走动、forceload）。实测踩过：服务端刚起时上水村的区块还没加载，
	 * 那次扫描把 {@code 候选=0} 记进缓存；等区块加载后再看，同一个车站的另一条站台（几何一模一样）
	 * 有 207 个候选，而这一条要等 60 秒的重扫定时器才恢复。现在扫描摊在一个 8 ms 的预算里，
	 * 且"上次没看全"的站台每拍都排在被重扫的第一位 —— 区块一加载，下一拍就补齐。</p>
	 */
	private void scanNext(List<Platform> platforms, long now) {
		if (platforms.isEmpty()) {
			return;
		}
		final long deadline = System.currentTimeMillis() + SCAN_BUDGET_MILLIS;
		int index = scanCursor;
		for (int examined = 0; examined < platforms.size(); examined++) {
			if (System.currentTimeMillis() >= deadline) {
				break;
			}
			final Platform platform = platforms.get(index % platforms.size());
			index++;
			final String key = geometryKey(platform);
			final Plan existing = plans.get(platform.getId());
			if (existing == null || !key.equals(existing.geometryKey) || !existing.complete
				|| now - existing.scannedMillis >= RESCAN_INTERVAL_MILLIS) {
				plans.put(platform.getId(), scan(platform, key, now));
			}
		}
		scanCursor = index % platforms.size();
	}

	/**
	 * 站台几何的指纹：轨 hex + 两个端点。
	 *
	 * <p>为什么不用"站台 id 没变就复用"：站台轨被拆了重铺（端点相同但轨换了）或站台被整体挪走时，
	 * id 不变而位置变了 —— 那是最需要重扫的情况。</p>
	 */
	private static String geometryKey(Platform platform) {
		final Rail rail = platform.mmtrGraphRail();
		final Position[] ends = platform.mmtrOrderedPositions();
		return (rail == null ? "-" : rail.getHexId())
			+ "|" + ends[0].getX() + "," + ends[0].getY() + "," + ends[0].getZ()
			+ "|" + ends[1].getX() + "," + ends[1].getY() + "," + ends[1].getZ();
	}

	private Plan scan(Platform platform, String key, long now) {
		final Plan plan = new Plan(platform.getId(), key, now);
		final Rail rail = platform.mmtrGraphRail();
		if (rail == null) {
			// 站台还没有对应的图轨（刚建、或在别的维度）：没有可走的路径，等下一轮。
			return plan;
		}
		final double length = rail.railMath.getLength();
		if (length <= 0) {
			return plan;
		}
		final Map<BlockPos, Spot> spots = new LinkedHashMap<>();
		final Set<BlockPos> legacyBlocks = new LinkedHashSet<>();
		boolean complete = true;
		for (double distance = 0; distance <= length; distance += SAMPLE_STEP) {
			final Vector point = rail.railMath.getPosition(distance, false);
			final Vector ahead = rail.railMath.getPosition(Math.min(length, distance + 0.5), false);
			if (!isLoaded(Init.newBlockPos(point.x(), point.y(), point.z()))) {
				// 这一格所在的区块没加载：这次扫描看不到它，整份几何只能算"没看全"。
				complete = false;
				continue;
			}
			final double tangentX = ahead.x() - point.x();
			final double tangentZ = ahead.z() - point.z();
			final double tangentLength = Math.hypot(tangentX, tangentZ);
			if (tangentLength < 1.0E-6) {
				continue;
			}
			// 轨的切向转 90° = 站台方向（谁在左谁在右由下面的两侧搜索一起试）
			final double normalX = -tangentZ / tangentLength;
			final double normalZ = tangentX / tangentLength;
			final Spot spot = findSpot(platform, point, normalX, normalZ, legacyBlocks);
			if (spot != null) {
				spots.put(spot.villagerPos, spot);
			}
		}
		plan.spots = new ArrayList<>(spots.values());
		plan.legacyBlocks = new ArrayList<>(legacyBlocks);
		plan.complete = complete;
		return plan;
	}

	/** 在轨道两侧从近到远找第一块站台方块；两侧都有（岛式站台）时按位置哈希定一侧。 */
	private Spot findSpot(Platform platform, Vector point, double normalX, double normalZ, Set<BlockPos> legacyBlocks) {
		final int railY = (int) Math.floor(point.y());
		for (int k = 1; k <= SIDE_SEARCH_BLOCKS; k++) {
			final int positiveX = (int) Math.floor(point.x() + normalX * k);
			final int positiveZ = (int) Math.floor(point.z() + normalZ * k);
			final int negativeX = (int) Math.floor(point.x() - normalX * k);
			final int negativeZ = (int) Math.floor(point.z() - normalZ * k);

			final Spot positive = probeColumn(platform, positiveX, railY, positiveZ, point, legacyBlocks);
			final Spot negative = probeColumn(platform, negativeX, railY, negativeZ, point, legacyBlocks);
			if (positive != null && negative != null) {
				return hash01(platform.getId(), positive.villagerPos) < 0.5 ? positive : negative;
			}
			if (positive != null) {
				return positive;
			}
			if (negative != null) {
				return negative;
			}
		}
		return null;
	}

	/**
	 * 一根柱子上（几个高度里）找站台面，人放在它上面那一格。
	 *
	 * <p>顺带登记**遗留的客流方块**：上一版客流是"铺方块"，世界里可能还留着那些方块（换了实体之后没人认它们）。
	 * 扫到就登记，当拍清掉，不留下"上一版的幽灵"。</p>
	 */
	private Spot probeColumn(Platform platform, int x, int railY, int z, Vector point, Set<BlockPos> legacyBlocks) {
		for (final int yOffset : Y_OFFSETS) {
			final BlockPos below = Init.newBlockPos(x, railY + yOffset, z);
			if (!isLoaded(below)) {
				continue;
			}
			final BlockPos villagerPos = below.up();
			if (!isLoaded(villagerPos)) {
				continue;
			}
			final BlockState belowState = world.getBlockState(below);
			if (belowState.getBlock().data instanceof PlatformHelper) {
				// 站台面那一格：人站在它上面。人**不是方块**了，所以这里不再需要"上面必须是空的" ——
				// 实体与方块可以共存（柱子旁边站个人也正常）。
				// 但上一版方块方案铺的客流方块正好也在这一格上，必须顺手登记清理（否则它们会一直留在站台上）。
				if (world.getBlockState(villagerPos).getBlock().data instanceof BlockCrowdVillager) {
					legacyBlocks.add(villagerPos);
				}
				final Direction facing = facingOf((int) Math.floor(point.x()) - x, (int) Math.floor(point.z()) - z);
				// 群系类型（花色）：同一个格坐标永远同一个类型，重算不会换衣服。
				final int typeIndex = MmtrCrowdVillagers.typeIndexFor(hash01(platform.getId() * 31L + 7L, villagerPos));
				return new Spot(villagerPos, facing, typeIndex);
			}
			// 上一步方块方案留下的客流方块：登记待清。
			final BlockState aboveState = world.getBlockState(villagerPos);
			if (aboveState.getBlock().data instanceof BlockCrowdVillager) {
				legacyBlocks.add(villagerPos);
			}
		}
		return null;
	}

	private static Direction facingOf(int dx, int dz) {
		if (Math.abs(dx) >= Math.abs(dz)) {
			return dx >= 0 ? Direction.EAST : Direction.WEST;
		}
		return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
	}

	// ------------------------------------------------------------------ 客量 → 目标集合 → 生成/回收

	private void enqueueDiff(Platform platform, Plan plan, List<MmtrCrowdVillagers.Crowd> existing) {
		final long level = platform.getEffectiveCrowdLevel();
		final int cap = MmtrCrowd.maxPerPlatform();
		final double radius = MmtrCrowd.playerRadius();

		/*
		 * 可用候选 = 已加载区块里的站台边缘格。
		 *
		 * 为什么先数一遍再算阈值：上限要折成一个"哈希阈值"才能既守住人数、又保持随机散列的样子。
		 *   fraction = min(客量%, 上限 / 候选数)
		 * 期望人数 = fraction × 候选数 = min(客量% × 候选数, 上限) —— 正好是"客量比例，但不超过上限"。
		 * 若改成"选满 N 个再砍掉尾巴"，人就会挤在站台一端，看起来像排队而不是人群。
		 */
		final List<Spot> candidates = new ArrayList<>();
		for (final Spot spot : plan.spots) {
			if (isLoaded(spot.villagerPos)) {
				candidates.add(spot);
			}
		}
		final double fraction = candidates.isEmpty() ? 0 : Math.min(level / 100.0, cap / (double) candidates.size());

		/*
		 * 半径闸门**按站台**判（整条站台要么有人、要么没人），但判据是"站台上**任意一格**在半径内"，
		 * 不是"站台中心在半径内"：220 格长的站台站在一端时，中心离玩家 110 格 —— 按中心判就会整条空掉，
		 * 玩家看到的是"这条站台一个人都没有"，而实际上他就站在站台上。按"任意一格"判，站台有人站的地方
		 * 就有客流，且不会出现"半条站台有人、半条空着"的半截画面。
		 */
		final boolean populate = candidates.stream().anyMatch(spot -> hasPlayerNear(spot.villagerPos, radius));

		final Map<BlockPos, Spot> target = new LinkedHashMap<>();
		if (populate) {
			for (final Spot spot : candidates) {
				if (hash01(platform.getId(), spot.villagerPos) < fraction) {
					target.put(spot.villagerPos, spot);
				}
			}
		}

		// 世界上现在站着谁：实际格 → 那只（用来判断"这一格已经有了"）。
		final Map<BlockPos, MmtrCrowdVillagers.Crowd> byActual = new HashMap<>();
		for (final MmtrCrowdVillagers.Crowd crowd : existing) {
			final int[] actual = crowd.actualCell();
			byActual.put(Init.newBlockPos(actual[0], actual[1], actual[2]), crowd);
		}

		// 该有的：目标格里没有人的（人漂移了的话，下一拍由 applyEntityOp 按记忆格拉回来）。
		for (final Map.Entry<BlockPos, Spot> entry : target.entrySet()) {
			if (!byActual.containsKey(entry.getKey())) {
				entityQueue.add(new EntityOp(entry.getKey(), entry.getValue(), platform.getId()));
			}
		}

		// 不该有的：记忆格不在目标里的（客量降了 / 离玩家太远 / 站台面被改 / 平台没了），以及同一记忆格重复的人。
		final Set<BlockPos> seenMemories = new HashSet<>();
		for (final MmtrCrowdVillagers.Crowd crowd : existing) {
			final int[] cell = crowd.memoryCell == null ? crowd.actualCell() : crowd.memoryCell;
			final BlockPos memory = Init.newBlockPos(cell[0], cell[1], cell[2]);
			if (!target.containsKey(memory) || !seenMemories.add(memory)) {
				MmtrCrowdVillagers.discard(crowd.entity);
			}
		}

		// 遗留方块（上一版方块方案铺的）：清掉。
		//   (1) 候选格上还压着方块 —— 每拍都查（不依赖 60 秒的重扫），所以升级后第一拍就能清干净；
		//   (2) 扫描登记下来的（站台面被改过、格子已经不是候选的那些）。
		for (final Spot spot : candidates) {
			if (world.getBlockState(spot.villagerPos).getBlock().data instanceof BlockCrowdVillager) {
				blockQueue.add(new BlockOp(spot.villagerPos));
			}
		}
		if (plan.legacyBlocks != null) {
			for (final BlockPos pos : plan.legacyBlocks) {
				if (isLoaded(pos) && world.getBlockState(pos).getBlock().data instanceof BlockCrowdVillager) {
					blockQueue.add(new BlockOp(pos));
				}
			}
			plan.legacyBlocks = null;
		}
	}

	/** 这一格附近（{@code radius} 格内）有没有玩家。没有就不站人 —— 看不见的人纯属白花性能。 */
	private boolean hasPlayerNear(BlockPos pos, double radius) {
		return world.getClosestPlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, radius, false) != null;
	}

	private void drainQueues() {
		final long deadline = System.currentTimeMillis() + APPLY_BUDGET_MILLIS;
		int ops = 0;

		// 一次 drain 复用同一份"世界上戴 tag 的人"索引：每条 op 各扫一遍实体表太贵（一拍可能几十条）。
		Map<String, MmtrCrowdVillagers.Crowd> index = null;
		if (!entityQueue.isEmpty()) {
			index = new HashMap<>();
			for (final MmtrCrowdVillagers.Crowd crowd : MmtrCrowdVillagers.collect(world.data)) {
				if (crowd.memoryCell != null) {
					index.put(MmtrCrowdVillagers.cellTag(crowd.memoryCell[0], crowd.memoryCell[1], crowd.memoryCell[2]), crowd);
				}
			}
		}

		// 方块（遗留清理）：一次一条就够，别和实体抢预算。
		while (!blockQueue.isEmpty() && ops < MAX_OPS_PER_TICK && System.currentTimeMillis() < deadline) {
			final BlockOp op = blockQueue.poll();
			ops++;
			if (!isLoaded(op.pos)) {
				continue;
			}
			if (world.getBlockState(op.pos).getBlock().data instanceof BlockCrowdVillager) {
				world.setBlockState(op.pos, Blocks.getAirMapped().getDefaultState());
			}
		}

		while (!entityQueue.isEmpty() && ops < MAX_OPS_PER_TICK && System.currentTimeMillis() < deadline) {
			final EntityOp op = entityQueue.poll();
			ops++;
			if (!isLoaded(op.cell)) {
				// 区块卸了：丢掉这一条。下一轮核对会重新入队。
				continue;
			}
			applyEntityOp(op, index);
		}
	}

	/**
	 * 一条实体操作：这一格该有一个人，而世界上还没有（或那个人漂移了）。
	 *
	 * <p>有记忆格的把人**拉回来**、没有的才新生成：拉回省一次生成包，玩家眼前的队伍不会闪。</p>
	 */
	private void applyEntityOp(EntityOp op, Map<String, MmtrCrowdVillagers.Crowd> index) {
		final String cellTag = MmtrCrowdVillagers.cellTag(op.cell.getX(), op.cell.getY(), op.cell.getZ());
		final MmtrCrowdVillagers.Crowd existing = index == null ? null : index.get(cellTag);
		if (existing != null) {
			if (existing.isAt(op.cell.getX(), op.cell.getY(), op.cell.getZ())) {
				// 已经在位：只把朝向纠回来（被推转过的）。
				existing.entity.setYaw(yawOf(op.spot));
				existing.entity.setHeadYaw(yawOf(op.spot));
			} else {
				MmtrCrowdVillagers.snap(existing.entity, op.cell.getX(), op.cell.getY(), op.cell.getZ(), yawOf(op.spot));
			}
			return;
		}
		MmtrCrowdVillagers.spawn(world.data, op.cell.getX(), op.cell.getY(), op.cell.getZ(), yawOf(op.spot), op.spot.typeIndex, op.platformId);
	}

	/** 朝向轨道：MTR 的 {@code Direction.asRotation()} 与实体 yaw 同一约定（南=0、西=90、北=180、东=270）。 */
	private static float yawOf(Spot spot) {
		return spot.facing.asRotation();
	}

	// ------------------------------------------------------------------ 人数闸门的持久化

	/** 闸门镜像文件名（放在世界存档的 mtr 目录下，与引擎的 mmtr-*.json 同级）。 */
	private static final String CONFIG_FILE = "mmtr-crowd.properties";

	private static Path configPath;
	private static int mirroredCap = -1;
	private static int mirroredRadius = -1;

	/**
	 * 服务端启动时调一次：把上次调好的闸门读回来。
	 *
	 * <p>为什么需要它：闸门的权威值在引擎侧（操作员要能从中控指令栏改、改完立刻生效），
	 * 而引擎的静态值在服务端重启后会回默认。所以游戏侧把**当前值镜像一份**到存档目录里，
	 * 启动时读回来再灌回引擎 —— 单一权威、又活得比进程长。</p>
	 */
	public static void loadConfig(Path mtrRoot) {
		configPath = mtrRoot == null ? null : mtrRoot.resolve(CONFIG_FILE);
		if (configPath == null || !Files.isRegularFile(configPath)) {
			return;
		}
		final Properties properties = new Properties();
		try (InputStream inputStream = Files.newInputStream(configPath)) {
			properties.load(inputStream);
			MmtrCrowd.setMaxPerPlatform(Integer.parseInt(properties.getProperty("maxPerPlatform", String.valueOf(MmtrCrowd.DEFAULT_MAX_PER_PLATFORM))));
			MmtrCrowd.setPlayerRadius(Integer.parseInt(properties.getProperty("playerRadius", String.valueOf(MmtrCrowd.DEFAULT_PLAYER_RADIUS))));
			mirroredCap = MmtrCrowd.maxPerPlatform();
			mirroredRadius = MmtrCrowd.playerRadius();
			Init.LOGGER.info("[crowd] 人数闸门读回：单站台上限 {} 人、玩家半径 {} 格（{}）", mirroredCap, mirroredRadius, configPath);
		} catch (Exception e) {
			Init.LOGGER.warn("[crowd] 读不回人数闸门 {}：{}", configPath, e);
		}
	}

	/** 引擎侧的闸门被改过就写回镜像（每拍比一次两个整数，代价可忽略）。 */
	private static void mirrorConfigIfChanged() {
		if (configPath == null) {
			return;
		}
		final int cap = MmtrCrowd.maxPerPlatform();
		final int radius = MmtrCrowd.playerRadius();
		if (cap == mirroredCap && radius == mirroredRadius) {
			return;
		}
		mirroredCap = cap;
		mirroredRadius = radius;
		final Properties properties = new Properties();
		properties.setProperty("maxPerPlatform", String.valueOf(cap));
		properties.setProperty("playerRadius", String.valueOf(radius));
		try {
			Files.createDirectories(configPath.getParent());
			try (OutputStream outputStream = Files.newOutputStream(configPath)) {
				properties.store(outputStream, "MMTR platform crowd gates (platform cap / platform radius)");
			}
			Init.LOGGER.info("[crowd] 人数闸门已存：单站台上限 {} 人、玩家半径 {} 格", cap, radius);
		} catch (Exception e) {
			Init.LOGGER.warn("[crowd] 写不回人数闸门 {}：{}", configPath, e);
		}
	}

	// ------------------------------------------------------------------ 核对读数

	/**
	 * {@code crowd scan}（引擎指令 {@code platform scan} 下发）的读数：**逐站台报"应有 / 实有"**。
	 *
	 * <p>名词解释（报告里就这么写，避免看到数字猜口径）：</p>
	 * <ul>
	 *   <li>{@code 候选} —— 已加载区块里找到的站台边缘格数（= 100% 时的人数上限）；</li>
	 *   <li>{@code 应有} —— 按当前有效客量从候选中选中的格数（= 1 格一人的口径数，含上限截断）；</li>
	 *   <li>{@code 实有} —— 这些格里**真的站着我们的人**的格数（差值 = 还没铺完 / 被杀 / 被推走）；</li>
	 *   <li>{@code 站位漂移} —— 人还在、但不在自己记忆格上的只数（下一拍会被拉回）。</li>
	 * </ul>
	 */
	public static List<String> report(ServerWorld serverWorld, Simulator simulator) {
		final List<String> lines = new ArrayList<>();
		final MmtrCrowdModule module = MODULES.get(dimensionOf(serverWorld));
		if (module == null) {
			lines.add("[crowd] 这个维度还没跑过客流模块（服务端每 tick 会跑一次，稍后再试）");
			return lines;
		}
		final Map<String, List<MmtrCrowdVillagers.Crowd>> crowdsByPlatform = module.collectCrowds();
		final List<Platform> platforms = module.snapshotPlatforms(simulator);
		int totalExpected = 0;
		int totalActual = 0;
		int scanned = 0;
		int unscanned = 0;
		for (final Platform platform : platforms) {
			final long length = approximateLengthBlocks(platform);
			final Plan plan = module.plans.get(platform.getId());
			final String stationName = platform.area == null ? "（未归站）" : Utilities.formatName(platform.area.getName());
			if (plan == null || plan.spots == null) {
				unscanned++;
				lines.add(String.format("[crowd] %s 站名=%s 站台=%s 长=%d格 客量=%d%% —— 还没扫（等下一拍）",
					platform.getHexId(), stationName, platform.getName(), length, platform.getEffectiveCrowdLevel()));
				continue;
			}

			final List<MmtrCrowdVillagers.Crowd> existing = crowdsByPlatform.getOrDefault(MmtrCrowdVillagers.platformTag(platform.getId()), new ArrayList<>());
			final Set<BlockPos> occupied = new HashSet<>();
			int drifted = 0;
			for (final MmtrCrowdVillagers.Crowd crowd : existing) {
				final int[] actual = crowd.actualCell();
				occupied.add(Init.newBlockPos(actual[0], actual[1], actual[2]));
				if (crowd.memoryCell != null && !crowd.isAt(crowd.memoryCell[0], crowd.memoryCell[1], crowd.memoryCell[2])) {
					drifted++;
				}
			}

			// 与 enqueueDiff 同一套闸门：候选 = 已加载；fraction = min(客量%, 上限/候选数)；
			// 半径按**整条站台**判（要么整条有人、要么整条没人）。
			final List<Spot> candidates = new ArrayList<>();
			for (final Spot spot : plan.spots) {
				if (module.isLoaded(spot.villagerPos)) {
					candidates.add(spot);
				}
			}
			final int cap = MmtrCrowd.maxPerPlatform();
			final double fraction = candidates.isEmpty() ? 0
				: Math.min(platform.getEffectiveCrowdLevel() / 100.0, cap / (double) candidates.size());
			final boolean populate = candidates.stream().anyMatch(spot -> module.hasPlayerNear(spot.villagerPos, MmtrCrowd.playerRadius()));

			int expected = 0;
			int actual = 0;
			for (final Spot spot : candidates) {
				if (hash01(platform.getId(), spot.villagerPos) < fraction) {
					expected++;
					if (populate && occupied.contains(spot.villagerPos)) {
						actual++;
					}
				}
			}
			int leftovers = Math.max(0, existing.size() - actual);
			final int uncapped = (int) Math.round(platform.getEffectiveCrowdLevel() / 100.0 * candidates.size());
			final String gate = uncapped > cap ? "（客量要 " + uncapped + " 人，被上限 " + cap + " 截断）" : "";

			scanned++;
			totalExpected += expected;
			totalActual += actual;
			lines.add(String.format("[crowd] %s 站名=%s 站台=%s 客量=%d%% 候选=%d 应有=%d 实有=%d 多出=%d 站位漂移=%d 排队=%d%s%s",
				platform.getHexId(), stationName, platform.getName(), platform.getEffectiveCrowdLevel(),
				candidates.size(), expected, actual, leftovers, drifted,
				module.entityQueue.size() + module.blockQueue.size(), gate,
				populate ? "" : "（本拍半径 " + MmtrCrowd.playerRadius() + " 格内没玩家 ⇒ 整条站台收走；不是没铺出来）"));
		}
		lines.add(String.format("[crowd] 合计 应有=%d 实有=%d（已扫站台 %d / 未扫 %d；单站台上限 %d 人、玩家半径 %d 格）",
			totalExpected, totalActual, scanned, unscanned, MmtrCrowd.maxPerPlatform(), MmtrCrowd.playerRadius()));
		return lines;
	}

	private static long approximateLengthBlocks(Platform platform) {
		final Position[] ends = platform.mmtrOrderedPositions();
		return Math.round(Math.hypot(ends[1].getX() - ends[0].getX(), ends[1].getZ() - ends[0].getZ()));
	}

	// ------------------------------------------------------------------ 小工具

	/**
	 * 位置所在区块是否已加载。
	 *
	 * <p>为什么必须问：{@code ServerWorld.getBlockState} 对未加载的区块会**从磁盘加载它**
	 * （甚至生成地形）。200 格站台一条扫下来就是十几个区块 —— 一次扫描能把服务端卡住几秒。</p>
	 */
	private boolean isLoaded(BlockPos pos) {
		return world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
	}

	/**
	 * 位置哈希 → [0,1)：挑"这一格站不站人"与群系类型都用它，保证每次重算结果一致。
	 *
	 * <p>用 splitmix64 的收尾函数（两次乘法 + 三次 xor-shift）。为什么不用"一次乘法 + 位移"那种更短的写法：
	 * 实测（用真实站台 id 与 220 格连续 x 试）短写法的均值偏到 0.478，于是"设 60%"会铺出 62–64% 的人 ——
	 * 而"客量 60% 就是六成格子有人"正是这个功能对外承诺的口径，偏差会直接在
	 * {@code platform scan} 的"应有"里露出来。换 splitmix64 后均值为 0.500、各档偏差回到采样噪声内。</p>
	 */
	private static double hash01(long salt, BlockPos pos) {
		long h = salt
			^ pos.getX() * 0x9E3779B97F4A7C15L
			^ pos.getY() * 0xC2B2AE3D27D4EB4FL
			^ pos.getZ() * 0x165667B19E3779F9L;
		h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
		h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
		h ^= h >>> 31;
		return (h >>> 11) * 0x1.0p-53;
	}

	/** 一个站台缓存下来的几何。 */
	private static final class Plan {

		private final long platformId;
		private final String geometryKey;
		private final long scannedMillis;
		/** 候选站位（已加载区块里找到的站台边缘格）；null = 还没扫出来。 */
		private List<Spot> spots;
		/** 这次扫描有没有"没看全"（有样本落在未加载区块里）—— 没看全的下一拍重扫。 */
		private boolean complete;
		/** 扫描时发现的遗留客流方块（上一版方块方案铺的）：当拍清掉，清完置 null。 */
		private List<BlockPos> legacyBlocks;

		private Plan(long platformId, String geometryKey, long scannedMillis) {
			this.platformId = platformId;
			this.geometryKey = geometryKey;
			this.scannedMillis = scannedMillis;
		}
	}

	/** 一个站位：人站哪一格、朝哪、穿哪件"群系衣服"。 */
	private record Spot(BlockPos villagerPos, Direction facing, int typeIndex) {
	}

	/** 该格该有一个人。 */
	private record EntityOp(BlockPos cell, Spot spot, long platformId) {
	}

	/** 该格遗留了一个客流方块，要清掉。 */
	private record BlockOp(BlockPos pos) {
	}
}
