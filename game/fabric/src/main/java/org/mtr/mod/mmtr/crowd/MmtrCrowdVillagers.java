package org.mtr.mod.mmtr.crowd;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import net.minecraft.village.VillagerType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * **客流村民实体**的创建 / 收编 / 回收 —— 整个客流功能里唯一碰原始 {@code net.minecraft} 类型的地方。
 *
 * <h3>为什么是实体，而不是方块</h3>
 * <p>用户口径（2026-10）："还是需要点动态的……fresh animations 那个"。Fresh Animations 是**资源包**，
 * 由 EMF（Entity Model Features）换掉实体模型、再按动画表达式驱动 —— 它只作用于**实体渲染管线**：
 * 我们自己烘焙的方块模型、或者方块实体里手画的模型，它一概不管。所以"要 FA 的村民"就等价于
 * "客流必须是真村民实体"，把渲染整条交给原版渲染器 + 资源包，模组这边不用写一行客户端渲染代码。</p>
 *
 * <h3>身份不放内存，放实体 tag</h3>
 * <p>每只村民带三个 tag：</p>
 * <ul>
 *   <li>{@link #TAG} —— "这是我铺的人"（枚举用）；</li>
 *   <li>{@code mmtr_crowd_p_<平台hex>} —— 属于哪个站台（站台拆了/客量归零时按它回收）；</li>
 *   <li>{@code mmtr_crowd_at_<x>_<y>_<z>} —— **它应该站在哪一格**（记忆格）。</li>
 * </ul>
 * <p>为什么要记"应该站哪"：实体是会被推动的（玩家挤、别的实体推、被 /tp 走）。有记忆格之后，
 * 下一个核对节拍可以把漂移的那只**拉回原位**（{@link #snap}），而不是"删掉再生成"——
 * 后者每次都要发一次生成包，玩家眼前的队伍会闪。tag 本身跟着存档走，所以服务端重启后照样认得
 * 这批人是我们的、各自该站哪（方块方案那个"重启后账本丢了、孤儿清不掉"的坑在这里自然不存在）。</p>
 *
 * <h3>为什么这些开关都要关</h3>
 * <ul>
 *   <li>{@code NoAI} —— 客流是装饰：不能让它们自己寻路（会走下站台掉到轨道上）；关掉之后
 *       Fresh Animations 的**待机动画**（呼吸、摆动、左右看）照常播，因为没有走路动作可播。</li>
 *   <li>{@code Silent} —— 一个站台两百多只村民的"哼哼"声。</li>
 *   <li>{@code Invulnerable} —— 打不死的装饰，免得玩家一刀砍掉一个"人"。</li>
 *   <li>{@code Persistent} —— 不参与生物消失逻辑。</li>
 *   <li>职业 {@code NONE}（无业）—— 右键**不开交易界面**；花色差异靠**群系类型**（7 种，见 {@link #TYPES}）。</li>
 * </ul>
 */
public final class MmtrCrowdVillagers {

	/** 基础 tag：世界里的村民是不是我们铺的。 */
	public static final String TAG = "mmtr_crowd";

	private static final String TAG_PLATFORM_PREFIX = "mmtr_crowd_p_";
	private static final String TAG_CELL_PREFIX = "mmtr_crowd_at_";

	/** 群系类型：决定肤色/袍色。用户口径"村民用原版模型"，花色差异就靠这个（配合 ETF 更花）。 */
	private static final VillagerType[] TYPES = {
		VillagerType.PLAINS,
		VillagerType.DESERT,
		VillagerType.JUNGLE,
		VillagerType.SAVANNA,
		VillagerType.SNOW,
		VillagerType.SWAMP,
		VillagerType.TAIGA
	};

	/** 类型数量（生成器按位置哈希取模选一个）。 */
	public static final int TYPE_COUNT = TYPES.length;

	private MmtrCrowdVillagers() {
	}

	public static String platformTag(long platformId) {
		return TAG_PLATFORM_PREFIX + Long.toHexString(platformId).toUpperCase(Locale.ENGLISH);
	}

	public static String cellTag(int x, int y, int z) {
		return TAG_CELL_PREFIX + x + "_" + y + "_" + z;
	}

	/** 这只村民算什么身份：是不是我们铺的、属于哪个站台、记忆格在哪。 */
	public static final class Crowd {

		public final Entity entity;
		/** 铺的时候记下的"应该站这一格"；老存档里没有这个 tag 时为 null。 */
		public final int[] memoryCell;
		public final String platformTag;

		private Crowd(Entity entity, int[] memoryCell, String platformTag) {
			this.entity = entity;
			this.memoryCell = memoryCell;
			this.platformTag = platformTag;
		}

		/** 它**实际**站在哪一格（脚下那一格）。 */
		public int[] actualCell() {
			return new int[]{
				(int) Math.floor(entity.getX()),
				(int) Math.floor(entity.getY()),
				(int) Math.floor(entity.getZ())
			};
		}

		public boolean isAt(int x, int y, int z) {
			final int[] actual = actualCell();
			return actual[0] == x && actual[1] == y && actual[2] == z;
		}
	}

	/** 世界里所有我们铺的村民（只在已加载区块里，这是 MC 的枚举语义）。 */
	public static List<Crowd> collect(ServerWorld world) {
		final List<Crowd> crowds = new ArrayList<>();
		for (final Entity entity : world.iterateEntities()) {
			if (!(entity instanceof VillagerEntity)) {
				continue;
			}
			final java.util.Set<String> tags = entity.getCommandTags();
			if (!tags.contains(TAG)) {
				continue;
			}
			crowds.add(new Crowd(entity, parseCell(tags), parsePlatform(tags)));
		}
		return crowds;
	}

	private static int[] parseCell(java.util.Set<String> tags) {
		for (final String tag : tags) {
			if (!tag.startsWith(TAG_CELL_PREFIX)) {
				continue;
			}
			final String[] parts = tag.substring(TAG_CELL_PREFIX.length()).split("_");
			if (parts.length != 3) {
				continue;
			}
			try {
				return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}

	private static String parsePlatform(java.util.Set<String> tags) {
		for (final String tag : tags) {
			if (tag.startsWith(TAG_PLATFORM_PREFIX)) {
				return tag;
			}
		}
		return "";
	}

	/**
	 * 生成一只站在 {@code (x, y, z)} 这一格中间的村民，面朝 {@code yaw}。
	 *
	 * @param typeIndex 群系类型下标（调用方按位置哈希给，保证同一个人每次重算都穿同一件衣服）
	 */
	public static void spawn(ServerWorld world, int x, int y, int z, float yaw, int typeIndex, long platformId) {
		final VillagerEntity villager = new VillagerEntity(EntityType.VILLAGER, world);
		villager.refreshPositionAndAngles(x + 0.5, y, z + 0.5, yaw, 0);
		villager.setHeadYaw(yaw);
		villager.setVillagerData(new VillagerData(TYPES[Math.floorMod(typeIndex, TYPE_COUNT)], VillagerProfession.NONE, 1));
		villager.setAiDisabled(true);
		villager.setSilent(true);
		villager.setInvulnerable(true);
		villager.setPersistent();
		villager.addCommandTag(TAG);
		villager.addCommandTag(platformTag(platformId));
		villager.addCommandTag(cellTag(x, y, z));
		world.spawnEntity(villager);
	}

	/** 把漂移的那只拉回记忆格（位置 + 朝向）。 */
	public static void snap(Entity entity, int x, int y, int z, float yaw) {
		entity.refreshPositionAndAngles(x + 0.5, y, z + 0.5, yaw, entity.getPitch());
		entity.setHeadYaw(yaw);
		entity.setVelocity(0, 0, 0);
	}

	/** 收走一只（不是"杀死"：不留掉落物、不触发死亡动画）。 */
	public static void discard(Entity entity) {
		entity.discard();
	}

	/** 按位置哈希选群系类型（同一个格坐标永远同一个类型）。 */
	public static int typeIndexFor(double hash01) {
		return (int) Math.min(TYPE_COUNT - 1, hash01 * TYPE_COUNT);
	}
}
