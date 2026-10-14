package org.mtr.mod.mmtr.crowd;

import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.Direction;
import org.mtr.mod.Init;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * **流动人流**：站在「流动标记」走道上、沿走道来回走的那批村民。
 *
 * <h3>为什么单独一个类</h3>
 * <p>站定的客流（一级/二级）由 {@code MmtrCrowdModule} 每 5 秒核对一次"这一格该不该有人"就够了；
 * 走动人流要**每 tick 挪一步**，而且它的台账口径不一样：</p>
 * <ul>
 *   <li>站定的人：身份 = 记忆格 tag（{@code mmtr_crowd_at_…}），漂移了拉回来；</li>
 *   <li>走动人流：身份 = 走道 + 平台 tag（{@code mmtr_crowd_flow} + {@code mmtr_crowd_p_…}），
 *       **人数**由核对节拍收敛，"这一步往哪走"是易失状态（重启后按格坐标重新哈希一个方向即可）。</li>
 * </ul>
 *
 * <h3>怎么走</h3>
 * <p>每 tick 沿自己所在那格的轴向（{@link FlowCell#facing()}，就是放方块时玩家的朝向）挪
 * {@link #STEP} 格；下一格不在走道里就**掉头**。位置用 {@code setPosition}（相对移动包）——
 * 客户端插值着走，原版那套走路动画（也就是 Fresh Animations 的走路动画）自动生效，
 * 见 {@link MmtrCrowdVillagers#stepTo}。</p>
 *
 * <h3>走道按"柱"记，不按"格"记</h3>
 * <p>键是 (x, z) 两列，不是完整的三维格。为什么：半砖/内缩站台上人脚下会比方块坐标矮一点点
 * （重力把他落到真实地面上），拿三维格去比会判成"这人不在走道上"，于是每 tick 把他拽回格心 ——
 * 看上去就是原地抽搐、永远走不动。按柱记之后，高度只影响视觉，走动判定稳如平地。</p>
 *
 * <h3>掉了怎么办</h3>
 * <p>三条兜底：人被推离走道 → 拉回最近的走道格；走道被拆/站台没了 → 目标集合空了，
 * 下一拍核对把人收走；服务端重启 → 状态表是空的，按"世界上带着 tag 的人"重新登记，
 * 起点取离它最近的走道格。</p>
 */
public final class MmtrCrowdWalkers {

	/**
	 * 一步走多远（格/tick）：0.09 ≈ **1.8 格/秒**，和原版村民正常走路一个量级
	 * （原版村民闲逛约 0.1 格/tick，玩家走路 0.216 格/tick）。
	 *
	 * <p>为什么不是更慢的 0.045（0.9 格/秒）：原版算腿摆幅的是
	 * {@code LivingEntity.updateLimbs(位移)} → {@code limbAnimator.updateLimbs(min(4 × 位移, 1), 0.4)}。
	 * 0.045 格/tick 只给出 **0.18** 的摆幅，而"正常走路"的区间是 **~0.4** ——
	 * 慢到那个程度，Fresh Animations 的走路动画几乎不摆腿，人看着像**贴着地面滑行**而不是走。
	 * 0.09 给出 0.36，刚好落在正常走路的区间里（实测：用户第一眼就说"看着不能移动"，
	 * 除了位置，腿不摆也是"没在走"的一部分）。</p>
	 */
	private static final double STEP = 0.09;

	/** 每维度一份状态（键是维度名，与 {@code MmtrCrowdModule} 同一口径）。 */
	private static final Map<String, State> STATES = new HashMap<>();

	private MmtrCrowdWalkers() {
	}

	/** 服务端停机时清掉每世界状态。 */
	public static void clear() {
		STATES.clear();
	}

	/** 走道上的一格：人站的位置 + 走道轴向。 */
	public record FlowCell(BlockPos cell, Direction facing) {
	}

	private static final class Walker {

		private final Entity entity;
		private final long platformId;
		/** +1 = 沿所在格的朝向走；-1 = 反向。到走道尽头掉头。 */
		private int sign;
		/** 生成时占的那一格（用来判断"哪个走道格还没人"）。 */
		private BlockPos homeCell;

		/*
		 * 下面四个数是**为了能自答"为什么人不动"**（踩过两次：走道被当成落点、标记朝向与走道不同轴）。
		 * 报告里直接打出来：tick 在涨而位移不涨 ⇒ 是"判定不让走"；tick 都不涨 ⇒ 是"根本没被驱动"。
		 */
		private int ticks;
		private double moved;
		private int turns;
		private int pulls;

		private Walker(Entity entity, long platformId, int sign, BlockPos homeCell) {
			this.entity = entity;
			this.platformId = platformId;
			this.sign = sign;
			this.homeCell = homeCell;
		}
	}

	private static final class State {

		private final Map<UUID, Walker> walkers = new LinkedHashMap<>();
		/** 站台 → (x,z) 列 → 走道格（每拍核对时刷新）。 */
		private final Map<Long, Map<Long, FlowCell>> cells = new HashMap<>();
	}

	// ------------------------------------------------------------------ 每 tick 走一步

	public static void tick(ServerWorld world, String dimension) {
		final State state = STATES.get(dimension);
		if (state == null || state.walkers.isEmpty()) {
			return;
		}
		final Iterator<Map.Entry<UUID, Walker>> iterator = state.walkers.entrySet().iterator();
		while (iterator.hasNext()) {
			final Walker walker = iterator.next().getValue();
			// 人没了（被杀/被 /kill/被别的逻辑收走）：状态表跟着清。
			if (walker.entity == null || !walker.entity.isAlive()) {
				iterator.remove();
				continue;
			}
			final Map<Long, FlowCell> cells = state.cells.get(walker.platformId);
			if (cells != null && !cells.isEmpty()) {
				walker.ticks++;
				step(walker, cells);
			}
		}
	}

	private static void step(Walker walker, Map<Long, FlowCell> cells) {
		final Entity entity = walker.entity;
		final FlowCell current = cells.get(columnKey(floor(entity.getX()), floor(entity.getZ())));
		if (current == null) {
			// 被推离走道（玩家挤、别的实体撞、走道被改）：拉到最近的走道格，下一 tick 接着走。
			final FlowCell nearest = nearest(cells, entity.getX(), entity.getZ());
			if (nearest != null) {
				walker.pulls++;
				MmtrCrowdVillagers.stepTo(entity, nearest.cell().getX() + 0.5, nearest.cell().getZ() + 0.5, yawOf(walker, nearest.facing()));
			}
			return;
		}

		final Direction facing = current.facing();
		final int offsetX = facing.getOffsetX() * walker.sign;
		final int offsetZ = facing.getOffsetZ() * walker.sign;
		final double nextX = entity.getX() + offsetX * STEP;
		final double nextZ = entity.getZ() + offsetZ * STEP;
		if (!cells.containsKey(columnKey(floor(nextX), floor(nextZ)))) {
			// 到走道尽头：掉头（这一 tick 不挪，下一 tick 往反方向走）。
			walker.sign = -walker.sign;
			walker.turns++;
			MmtrCrowdVillagers.stepTo(entity, entity.getX(), entity.getZ(), yawOf(walker, facing));
			return;
		}
		walker.moved += STEP;
		MmtrCrowdVillagers.stepTo(entity, nextX, nextZ, yawOf(walker, facing));
	}

	private static float yawOf(Walker walker, Direction facing) {
		return (walker.sign > 0 ? facing : facing.getOpposite()).asRotation();
	}

	// ------------------------------------------------------------------ 核对（每拍一次）

	/**
	 * 把某条站台的走动人流收敛到 {@code targets} 这一组格上（人数多了收走、少了生成）。
	 *
	 * @param corridor 这条站台**整条走道**的格（流动标记铺出来的全部格子）—— 走动用它，不是用 targets：
	 *                 targets 只是"这一拍该有几个人"的落点（受客量与上限裁过），
	 *                 拿它当走道的话，两个落点之间不相邻，人一步都迈不出去（实测踩过：人站着不动）。
	 * @param targets  这一拍该有走动人流的落点（按客量选出来的格；空集 = 这条站台不该有走动人流）
	 * @param crowds   本拍**一次枚举**出来的世界里所有我们铺的村民（避免每条站台各枚举一遍实体表）
	 */
	public static void reconcile(ServerWorld world, String dimension, long platformId, List<FlowCell> corridor, List<FlowCell> targets, List<MmtrCrowdVillagers.Crowd> crowds) {
		final State state = STATES.computeIfAbsent(dimension, key -> new State());
		final Map<Long, FlowCell> cellMap = new HashMap<>();
		for (final FlowCell cell : corridor) {
			cellMap.put(columnKey(cell.cell().getX(), cell.cell().getZ()), cell);
		}
		state.cells.put(platformId, cellMap);

		// 世界上真实存在的走动人流（按平台 tag 认）。
		final Map<UUID, MmtrCrowdVillagers.Crowd> actual = new LinkedHashMap<>();
		for (final MmtrCrowdVillagers.Crowd crowd : crowds) {
			if (crowd.flow && crowd.platformTag.equals(MmtrCrowdVillagers.platformTag(platformId))) {
				actual.put(crowd.entity.getUuid(), crowd);
			}
		}

		// 状态表对齐：世界里没有的删掉；世界里有、状态表里没有的补登记（服务端重启后的第一次核对）。
		//
		// 只清**本平台**的（踩过：这里原来对所有平台做 removeIf，而 actual 只含本平台的走动人流 ——
		// 于是每核对一条站台就把别的站台的走动人流从状态表里删掉，别的站台的人再也不走）。
		state.walkers.entrySet().removeIf(entry -> entry.getValue().platformId == platformId && !actual.containsKey(entry.getKey()));
		for (final Map.Entry<UUID, MmtrCrowdVillagers.Crowd> entry : actual.entrySet()) {
			if (state.walkers.containsKey(entry.getKey())) {
				continue;
			}
			final Entity entity = entry.getValue().entity;
			final FlowCell home = nearest(cellMap, entity.getX(), entity.getZ());
			/*
			 * 老存档里铺下的走动人流（升级前生成的）补一次"削成贴片"**并把职业补上**：
			 * 升级前是 profession = NONE，Human Era 的衣服层因此被整层跳过（裸体 + 一顶帽子）。
			 * typeIndex 用与生成时**同一个公式**（见下面"少了：在走道格上生成"那一段），
			 * 所以补出来的职业与雕像的 variant（= typeIndex % 4）一致。找不到格子时退回 0。
			 */
			MmtrCrowdVillagers.ensureStyled(entity, home == null ? 0
				: MmtrCrowdVillagers.typeIndexFor(MmtrCrowdModule.hash01(platformId * 131L + 17L, home.cell())));
			state.walkers.put(entry.getKey(), new Walker(entity, platformId, signFor(home == null ? null : home.cell()), home == null ? null : home.cell()));
		}

		// 这一条站台的走动人流（状态表里的）。
		final List<Walker> mine = new ArrayList<>();
		for (final Walker walker : state.walkers.values()) {
			if (walker.platformId == platformId) {
				mine.add(walker);
			}
		}

		// 多了：先收走"已经不在走道上"的，再收走剩下的（顺序无所谓，人数收敛即可）。
		if (mine.size() > targets.size()) {
			mine.sort((left, right) -> Boolean.compare(onTrack(cellMap, left), onTrack(cellMap, right)));
			for (int index = 0; index < mine.size() - targets.size(); index++) {
				final Walker victim = mine.get(index);
				MmtrCrowdVillagers.discard(victim.entity);
				state.walkers.remove(victim.entity.getUuid());
			}
			return;
		}

		// 少了：在"还没人占"的走道格上生成。
		if (mine.size() < targets.size()) {
			final Set<BlockPos> taken = new HashSet<>();
			for (final Walker walker : mine) {
				if (walker.homeCell != null) {
					taken.add(walker.homeCell);
				}
			}
			for (final FlowCell target : targets) {
				if (mine.size() >= targets.size()) {
					break;
				}
				final BlockPos cell = target.cell();
				if (taken.contains(cell) || !world.getChunkManager().isChunkLoaded(cell.getX() >> 4, cell.getZ() >> 4)) {
					continue;
				}
				final int sign = signFor(cell);
				final float yaw = (sign > 0 ? target.facing() : target.facing().getOpposite()).asRotation();
				final Entity spawned = MmtrCrowdVillagers.spawnFlow(world, cell.getX(), cell.getY(), cell.getZ(), yaw,
					MmtrCrowdVillagers.typeIndexFor(MmtrCrowdModule.hash01(platformId * 131L + 17L, cell)), platformId);
				taken.add(cell);
				final Walker walker = new Walker(spawned, platformId, sign, cell);
				state.walkers.put(spawned.getUuid(), walker);
				mine.add(walker);
			}
		}
	}

	// ------------------------------------------------------------------ 小工具

	private static boolean onTrack(Map<Long, FlowCell> cells, Walker walker) {
		if (walker.entity == null) {
			return false;
		}
		if (cells.containsKey(columnKey(floor(walker.entity.getX()), floor(walker.entity.getZ())))) {
			return true;
		}
		return walker.homeCell != null && cells.containsKey(columnKey(walker.homeCell.getX(), walker.homeCell.getZ()));
	}

	private static int floor(double value) {
		return (int) Math.floor(value);
	}

	/** 走道按 (x,z) 两列索引（见类注释：为什么不用完整三维格）。 */
	private static long columnKey(int x, int z) {
		return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
	}

	/** 离这一点最近的走道格（没有走道时返回 null）。 */
	private static FlowCell nearest(Map<Long, FlowCell> cells, double x, double z) {
		FlowCell best = null;
		double bestDistance = Double.MAX_VALUE;
		for (final FlowCell cell : cells.values()) {
			final double dx = cell.cell().getX() + 0.5 - x;
			final double dz = cell.cell().getZ() + 0.5 - z;
			final double distance = dx * dx + dz * dz;
			if (distance < bestDistance) {
				bestDistance = distance;
				best = cell;
			}
		}
		return best;
	}

	/** 初始方向：按格坐标哈希定 —— 同一条走道上两个方向都有人（真实的地下通道/站台走道就是双向的）。 */
	private static int signFor(BlockPos cell) {
		return cell == null || MmtrCrowdModule.hash01(0x5EEDL, cell) < 0.5 ? 1 : -1;
	}

	/** 调试/报告用：当前登记着多少走动人流。 */
	public static int count(String dimension) {
		final State state = STATES.get(dimension);
		return state == null ? 0 : state.walkers.size();
	}

	/**
	 * 一条站台走动人流的**自答读数**（报告里打出来）：登记几只、被驱动了多少 tick、实际走了多远、
	 * 掉头几次、被拉回走道几次。
	 *
	 * <p>为什么值得常驻：走动人流"不动"有两种完全不同的原因，光看位置分不出来 ——</p>
	 * <ul>
	 *   <li>{@code tick} 在涨而 {@code moved} 不涨 ⇒ 驱动在跑，是**判定不让走**（走道格不含下一格）；</li>
	 *   <li>{@code tick} 是 0 ⇒ 根本没被驱动（没登记上 / 实体不 alive / 这一拍走道集合是空的）。</li>
	 * </ul>
	 */
	public record Summary(int walkers, int ticks, double moved, int turns, int pulls) {
	}

	public static Summary summary(String dimension, long platformId) {
		final State state = STATES.get(dimension);
		if (state == null) {
			return new Summary(0, 0, 0, 0, 0);
		}
		int walkers = 0;
		int ticks = 0;
		double moved = 0;
		int turns = 0;
		int pulls = 0;
		for (final Walker walker : state.walkers.values()) {
			if (walker.platformId == platformId) {
				walkers++;
				ticks += walker.ticks;
				moved += walker.moved;
				turns += walker.turns;
				pulls += walker.pulls;
			}
		}
		return new Summary(walkers, ticks, moved, turns, pulls);
	}

	/** 一只走动人流的现场（`crowd dump` 用）：状态表里认到没有、活着没有、在哪、被驱动了多少。 */
	public record WalkerInfo(String uuid, boolean alive, String position, int ticks, double moved, int turns, int pulls) {
	}

	public static List<WalkerInfo> walkers(String dimension, long platformId) {
		final List<WalkerInfo> infos = new ArrayList<>();
		final State state = STATES.get(dimension);
		if (state == null) {
			return infos;
		}
		for (final Walker walker : state.walkers.values()) {
			if (walker.platformId != platformId) {
				continue;
			}
			final Entity entity = walker.entity;
			infos.add(new WalkerInfo(
				entity == null ? "?" : entity.getUuid().toString().substring(0, 8),
				entity != null && entity.isAlive(),
				entity == null ? "?" : String.format(Locale.ENGLISH, "%.2f,%.2f,%.2f", entity.getX(), entity.getY(), entity.getZ()),
				walker.ticks, walker.moved, walker.turns, walker.pulls
			));
		}
		return infos;
	}

	/** 走道格（`crowd dump` 用）：这一拍这条站台认到的走道有多少格、轴向是什么。 */
	public static List<String> corridor(String dimension, long platformId) {
		final List<String> lines = new ArrayList<>();
		final State state = STATES.get(dimension);
		final Map<Long, FlowCell> cells = state == null ? null : state.cells.get(platformId);
		if (cells == null) {
			return lines;
		}
		for (final FlowCell cell : cells.values()) {
			lines.add(String.format(Locale.ENGLISH, "%d,%d,%d→%s", cell.cell().getX(), cell.cell().getY(), cell.cell().getZ(), cell.facing().data.getName()));
		}
		return lines;
	}
}
