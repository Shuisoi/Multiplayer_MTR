package org.mtr.mod.mmtr.crowd;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
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
 *
 * <h3>削成"没有碰撞箱的人形贴片"（用户 2026-10 口径）</h3>
 * <p>用户口径："<i>村民导致帧数暴跌，我们也不需要这个村民有什么碰撞箱，能不能简化一下</i>"。
 * 于是客流村民只保留"站着、呼吸、能被看见"这三件事，其余全部关掉，见 {@link #simplify}：
 * 没有碰撞箱（{@code isCollidable}/{@code canHit}/{@code isPushable} 全 false，由
 * {@code MmtrCrowdEntityMixin} 在两侧生效）、没有 AI、没有声音、打不着、不会消失、不被推动。</p>
 *
 * <p><b>为什么偏偏留着碰撞箱里最贵的那一项不做</b>：{@code noClip = true}（"自己移动时完全不做碰撞求解"）
 * 看着最省，但它会连**方块**碰撞一起关掉 —— 客流是靠"站在地板上"定位的，关了之后重力会把几百个人
 * 直接穿过站台面掉下去（{@code onGround} 永远不会置位）。所以这里只关**实体之间**那一半：
 * 站台上几百只挤在一格宽的地方，原版每只每 tick 都要跟周围上百只做一次两两相交检测（每次都新建候选列表），
 * 而两侧的 {@code isCollidable}/{@code canHit}/{@code isPushable} 一律返回 false 之后，
 * 那份候选列表是**空的** —— 最贵的那条路径自然没了，同时人还是稳稳站在地板上。</p>
 */
public final class MmtrCrowdVillagers {

	/** 基础 tag：世界里的村民是不是我们铺的。 */
	public static final String TAG = "mmtr_crowd";

	/**
	 * 走动人流（流动标记上的人）的 tag。
	 *
	 * <p>为什么要和站定的客流分开认：站定的人有**记忆格**（{@code mmtr_crowd_at_x_y_z}），
	 * 核对节拍会把漂移的那只拉回原位；而走动的人**本来就该离开那一格**，拿记忆格去纠它
	 * 等于把它钉死。所以走动人流只记"属于哪条站台"，位置由
	 * {@link MmtrCrowdWalkers} 沿走道驱动。</p>
	 */
	public static final String TAG_FLOW = "mmtr_crowd_flow";

	/**
	 * **客户端也认得出"这是我们铺的人"**的记号：实体名字。
	 *
	 * <p>为什么不能用命令 tag 当客户端的判据：{@code getCommandTags()} 只在服务端有值 ——
	 * 命令 tag 不进实体数据、不随生成包发给客户端，客户端手里那一份永远是空的。
	 * 而**自定义名字**是实体数据（{@code CUSTOM_NAME}），生成/开始追踪时一定会同步过去，
	 * 所以客户端能靠它认出客流（见 {@code MmtrCrowdEntityMixin}）。</p>
	 *
	 * <p>这个名字**不显示**（{@link #simplify} 里 {@code setCustomNameVisible(false)}）：
	 * 它一个字都不画，唯一用途是给客户端一个判据。</p>
	 */
	public static final String CLIENT_MARKER = "mmtr_crowd";

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
		/** 是不是**走动人流**（流动标记上的人；这种人不做记忆格纠正）。 */
		public final boolean flow;

		private Crowd(Entity entity, int[] memoryCell, String platformTag, boolean flow) {
			this.entity = entity;
			this.memoryCell = memoryCell;
			this.platformTag = platformTag;
			this.flow = flow;
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
			// 高度只要求"半格以内"：内缩站台（13/16）、半砖站台（8/16）上人脚下会矮一点点，
			// 按整数格判会把整条站台报成"人不在格上"，然后每拍把人往上拽一次 —— 上下抖。
			return actual[0] == x && actual[2] == z && Math.abs(entity.getY() - y) < 0.5;
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
			crowds.add(new Crowd(entity, parseCell(tags), parsePlatform(tags), tags.contains(TAG_FLOW)));
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
	 * **把一只村民削成"人形贴片"**：没有碰撞箱、没有 AI、没有声音、没有重力折腾、打不着、不会没。
	 *
	 * <p>逐条：</p>
	 * <ul>
	 *   <li>{@code NoAI} —— 不寻路、不选目标、不 tick 大脑（否则会自己走下站台掉到轨道上）；</li>
	 *   <li>{@code Silent} —— 一个站台两百多只村民的"哼哼"声；</li>
	 *   <li>{@code Invulnerable} —— 打不死的装饰，免得一刀砍掉一个"人"；</li>
	 *   <li>{@code PersistenceRequired} —— 不参与生物消失逻辑；</li>
	 *   <li>{@code setVelocity(0,0,0)} —— 不被任何陈年冲量推着走；</li>
	 *   <li>{@link #CLIENT_MARKER} —— 客户端靠它认出客流（名字不显示）。</li>
	 * </ul>
	 *
	 * <p><b>"没有碰撞箱"这一半不在这个类里</b>：{@code isCollidable()} / {@code canHit()} /
	 * {@code isPushable()} 是原版方法，只能由 mixin 改（{@code MmtrCrowdEntityMixin} /
	 * {@code MmtrCrowdLivingEntityMixin}，按 {@link #CLIENT_MARKER} 判身份、两侧同时生效）。
	 * 这么切的原因见类注释："自己不被挡"（noClip）和"别人撞不到我"（这三个方法）是两件事，
	 * 而客流只需要后者 —— 前者会把"站在地板上"一起关掉。</p>
	 */
	public static void simplify(Entity entity) {
		entity.setSilent(true);
		entity.setInvulnerable(true);
		entity.setVelocity(0, 0, 0);
		entity.setCustomName(Text.literal(CLIENT_MARKER));
		entity.setCustomNameVisible(false);
		if (entity instanceof VillagerEntity villager) {
			villager.setAiDisabled(true);
			villager.setPersistent();
		}
	}

	/**
	 * 老存档里铺下的客流补一次：削成贴片 **+ 把职业补上**。
	 *
	 * <p>判据必须是"有没有 {@link #CLIENT_MARKER} 这个名字"，不能是 {@link #isCrowd} ——
	 * 老实体带着命令 tag，{@code isCrowd} 服务端会答"是"，于是它们永远拿不到那个名字，
	 * 客户端的 mixin 也就永远认不出它们（踩过一次，写在这里当护栏）。</p>
	 *
	 * <p>职业那一半是 2026-10-10 加的：升级前铺下的村民 {@code profession == NONE}，
	 * 于是 Human Era 的衣服层被渲染器整层跳过（裸体 + 一顶帽子）。这里按**所在走道格**重算
	 * {@code typeIndex}（与 {@link MmtrCrowdWalkers} 生成时同一个公式），把职业补齐 —— 只在
	 * 首次登记时跑一次，之后 {@code VillagerData} 已经对得上就不再写。</p>
	 */
	public static void ensureStyled(Entity entity, int typeIndex) {
		if (!(entity instanceof VillagerEntity villager)) {
			return;
		}
		if (!hasClientMarker(entity)) {
			simplify(entity);
		}
		final VillagerData want = new VillagerData(TYPES[Math.floorMod(typeIndex, TYPE_COUNT)],
			PROFESSIONS[Math.floorMod(typeIndex, PROFESSIONS.length)], 1);
		if (!want.equals(villager.getVillagerData())) {
			villager.setVillagerData(want);
		}
	}

	/** {@link #CLIENT_MARKER} 这个（不显示的）名字在不在这只实体上。 */
	private static boolean hasClientMarker(Entity entity) {
		final Text name = entity.getCustomName();
		return name != null && CLIENT_MARKER.equals(name.getString());
	}

	/**
	 * 这一只（**客户端也算**）是不是我们铺的客流。
	 *
	 * <p>判据两级：服务端先看命令 tag（一次 HashSet 查表）；客户端 tag 是空的，退回看
	 * {@link #CLIENT_MARKER} 那个同步过来的名字。先判 {@code instanceof VillagerEntity}
	 * 是为了让"世界上绝大多数实体"在这里就短路掉 —— 这个方法会在碰撞求解里被逐只问到。</p>
	 */
	public static boolean isCrowd(Entity entity) {
		if (!(entity instanceof VillagerEntity villager)) {
			return false;
		}
		if (entity.getCommandTags().contains(TAG)) {
			return true;
		}
		if (hasClientMarker(entity)) {
			return true;
		}
		/*
		 * 兜底判据（**只靠两条会同步的实体数据**）：NoAI + Silent 的村民就是"我们铺的人"。
		 *
		 * 为什么需要它：碰撞/推挤的判据在**客户端**跑，命令 tag 不同步；而"隐藏名字"这一路实测会漏
		 * （用户："客流依旧存在碰撞箱，会把我推到一边"），所以原来用的是 `无业 + NoAI`。
		 *
		 * ★ 2026-10-10 改：客流现在**必须有职业**（Human Era 的衣服在 profession 层，无业 = 裸体 + 一顶帽子，
		 * 见 PROFESSIONS 的注释），所以那条判据失效了。换成同样两条**数据追踪**的量：
		 *   - {@code MobEntity.isAiDisabled()}（MOB_FLAGS 位，数据追踪）；
		 *   - {@code Entity.isSilent()}（SILENT 位，数据追踪；simplify() 里设的）。
		 * 真实村民会哼哼（不 Silent），所以不会误判；而且这两条与"有没有职业"解耦。
		 */
		return villager.isAiDisabled() && villager.isSilent();
	}

	/**
	 * 村民的**职业**表（顺序 = {@code typeIndex % 4}）：走动人流与（老版本的）雕像共用同一张表。
	 *
	 * <p><b>为什么必须给职业</b>：Human Era 的底板人皮是**裸体**的（外套区 (16,32)-(40,48) 全透明），
	 * 群系层基本只画一顶帽子，衣服全在 {@code profession/<职业>.png}（armorer 外套区 0.66、mason 0.61…）。
	 * 而反编译出来的 {@code VillagerClothingFeatureRenderer.render} 里那一层是**有条件**的：
	 * {@code if (villagerProfession != VillagerProfession.NONE && !isBaby())} —— 无业就整层不画，
	 * 于是村民变成"裸体 + 一顶帽子"。给职业就是把衣服放回来。</p>
	 *
	 * <p>雕像那边现在直接按格子哈希选**四个贴图块**（离线烘的图集里 73×7×14×5 全在），
	 * 所以这里这张表只服务走动人流实体；本表的顺序仍与雕像的哈希位段对齐
	 * （{@code professionTiles[hash % 14]} ↔ {@code PROFESSIONS[typeIndex % 4]} 是同一族衣服）。</p>
	 */
	private static final VillagerProfession[] PROFESSIONS = {
		VillagerProfession.LIBRARIAN,
		VillagerProfession.FISHERMAN,
		VillagerProfession.ARMORER,
		VillagerProfession.MASON
	};

	/**
	 * 生成一只站在 {@code (x, y, z)} 这一格中间的村民，面朝 {@code yaw}。
	 *
	 * @param typeIndex 群系类型下标（调用方按位置哈希给，保证同一个人每次重算都穿同一件衣服）
	 */
	public static void spawn(ServerWorld world, int x, int y, int z, float yaw, int typeIndex, long platformId) {
		final VillagerEntity villager = new VillagerEntity(EntityType.VILLAGER, world);
		villager.refreshPositionAndAngles(x + 0.5, y, z + 0.5, yaw, 0);
		villager.setHeadYaw(yaw);
		villager.setVillagerData(new VillagerData(TYPES[Math.floorMod(typeIndex, TYPE_COUNT)],
			PROFESSIONS[Math.floorMod(typeIndex, PROFESSIONS.length)], 1));
		simplify(villager);
		villager.addCommandTag(TAG);
		villager.addCommandTag(platformTag(platformId));
		villager.addCommandTag(cellTag(x, y, z));
		world.spawnEntity(villager);
	}

	/**
	 * 生成一只**走动人流**：站在流动标记那一格上，沿标记的朝向来回走。
	 *
	 * <p>与 {@link #spawn} 的差别只有 tag：走动人流不写记忆格（{@code mmtr_crowd_at_…}），
	 * 位置由 {@link MmtrCrowdWalkers} 每 tick 驱动，核对节拍不会把它拉回起点。</p>
	 */
	public static Entity spawnFlow(ServerWorld world, int x, int y, int z, float yaw, int typeIndex, long platformId) {
		final VillagerEntity villager = new VillagerEntity(EntityType.VILLAGER, world);
		villager.refreshPositionAndAngles(x + 0.5, y, z + 0.5, yaw, 0);
		villager.setHeadYaw(yaw);
		villager.setVillagerData(new VillagerData(TYPES[Math.floorMod(typeIndex, TYPE_COUNT)],
			PROFESSIONS[Math.floorMod(typeIndex, PROFESSIONS.length)], 1));
		simplify(villager);
		villager.addCommandTag(TAG);
		villager.addCommandTag(TAG_FLOW);
		villager.addCommandTag(platformTag(platformId));
		world.spawnEntity(villager);
		return villager;
	}

	/**
	 * 走动人流走一步：**只改水平位置与朝向**，脚下那一层由调用方保证（走道是平的）。
	 *
	 * <p>为什么用 {@code setPosition}（相对移动包）而不是 {@code refreshPositionAndAngles}（传送）：
	 * 前者客户端会**插值**着走（原版生物就是这么被服务端推动的，走路的腿部摆动也是客户端按这个
	 * 位移算出来的 —— Fresh Animations 的走路动画因此自动生效）；后者是硬传送，一格一跳会抖。</p>
	 */
	public static void stepTo(Entity entity, double x, double z, float yaw) {
		entity.setPosition(x, entity.getY(), z);
		entity.setYaw(yaw);
		entity.setHeadYaw(yaw);
		entity.setVelocity(0, 0, 0);
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
