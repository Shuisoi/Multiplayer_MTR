package org.mtr.core.mmtr.crowd;

import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Data;
import org.mtr.core.data.Platform;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 客流（站台客量）在引擎侧的公共面：**调制钩子** + **重算版本号**。
 *
 * <h3>为什么是"钩子"而不是"现在就接事件"</h3>
 * <p>用户口径（2026-10）：站台客量先做成一个**能设、能存、能读**的 0–100% 属性，
 * 自动起伏（临时高峰事件 / 早晚高峰曲线 / 真实候车人数）**留接口**，以后接进来时
 * 只往这里注册一个 {@link Modulator}，不必再碰 {@link Platform} 的存取与落盘。</p>
 *
 * <p>三个可能的调制源，接口都能表达：</p>
 * <ul>
 *   <li>{@code MmtrEvent.PEAK_SURGE}（车站级、带时间窗）→ 读 {@code platform.area} 与 {@code data} 的时钟；</li>
 *   <li>时段曲线 → 只读 {@code data}（{@link Data#getCurrentMillis()}）即可；</li>
 *   <li>真实候车人数 → 用 {@code platform} 查引擎里等待上车的乘客。</li>
 * </ul>
 *
 * <h3>为什么还要版本号</h3>
 * <p>铺方块的是**游戏侧**（只有它碰得到世界）。它每个节拍自己会重算一遍，
 * 但"改完客量想立刻看到人变多"不该等下一拍：设客量与
 * {@code platform crowd refresh} 都调 {@link #bumpRevision()}，
 * 游戏侧比对 {@link #revision()} 就知道"这一份客量是新改的"，可以马上重铺。</p>
 *
 * <p>版本号是**全局**的（不分站台不分世界）：它只用来回答"要不要现在重算"，
 * 真正的目标人数永远由各站台自己的客量算出来，所以多算一次不会算错。</p>
 */
public final class MmtrCrowd {

	/** 客量下限（%）。 */
	public static final long MIN_LEVEL = 0;

	/** 客量上限（%）。 */
	public static final long MAX_LEVEL = 100;

	private MmtrCrowd() {
	}

	// ------------------------------------------------------------------ 调制钩子（留接口）

	/**
	 * 客量调制器。实现必须是**纯函数**（同一时刻同一站台算出来的客量必须一样），
	 * 因为游戏侧可能在任何节拍重算；抛异常时会被当作"不调制"。
	 */
	public interface Modulator {

		/**
		 * @param platform  站台
		 * @param baseLevel 存在站台上的基础客量（已夹到 0–100）
		 * @param data      所属 {@link Data}（可取时钟）
		 * @return 调制后的客量（会再夹一次到 0–100）
		 */
		long modulate(Platform platform, long baseLevel, Data data);
	}

	@Nullable
	private static volatile Modulator modulator;

	/**
	 * 安装调制器（{@code null} = 取消）。默认没有调制器，即客量就是存下来的那个数。
	 */
	public static void setModulator(@Nullable Modulator newModulator) {
		modulator = newModulator;
	}

	@Nullable
	public static Modulator getModulator() {
		return modulator;
	}

	/**
	 * 跑一遍调制并夹到 0–100。
	 *
	 * <p>调制器抛异常时**退回基础客量**而不是 0：客流是装饰层，
	 * 调制器的 bug 不该把站台上的人全变没（那会让人以为是自己设置丢了）。</p>
	 */
	public static long modulate(Platform platform, long baseLevel, Data data) {
		final long clampedBase = clamp(baseLevel);
		final Modulator current = modulator;
		if (current == null) {
			return clampedBase;
		}
		try {
			return clamp(current.modulate(platform, clampedBase, data));
		} catch (Exception e) {
			return clampedBase;
		}
	}

	/** 把客量夹到 [0,100]。 */
	public static long clamp(long level) {
		return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
	}

	// ------------------------------------------------------------------ 人数闸门（客户端性能）

	/** 单站台同时存在的人数上限的默认值。 */
	public static final int DEFAULT_MAX_PER_PLATFORM = 48;
	/**
	 * 单站台人数上限的硬顶（防手滑写成几千）。
	 *
	 * <p>2026-10-10 从 400 放宽到 4000：用户口径"**无需限制站台村民数，塞满**" ——
	 * 客量 100% 要能把**每一个候选格**都站上人，而一条 220 格站台的候选是
	 * 一级 180 + 二级 460 + 流动 ~190 ≈ **820–834 格**，400 会把"塞满"拦腰截断。
	 * 放宽之后 {@code cap ≥ 候选数} 时，{@code 人数 = 满员 × 客量%} 就退化为
	 * "100% = 每格一人"，与旧口径完全等价（见设计文档 §13 与 MmtrCrowdModule#select）。</p>
	 */
	public static final int MAX_PER_PLATFORM_LIMIT = 4000;
	/** 玩家半径的默认值（格）。 */
	public static final int DEFAULT_PLAYER_RADIUS = 96;
	/** 玩家半径的硬顶（格）。 */
	public static final int PLAYER_RADIUS_LIMIT = 512;

	/**
	 * 单站台同屏人数上限。
	 *
	 * <p>用户口径（2026-10）："我需要减少实体数量防止客户端卡顿，不然村民起步就是大概 2-300 只在一个站台上"。
	 * Fresh Animations 换过模型之后每只村民都要跑动画表达式，200+ 只同屏是客户端帧率的直接杀手；
	 * 而客量口径又要求"人多有人多的样子"。两者用这个上限折中：</p>
	 * <ul>
	 *   <li>客量 100% 且站台很长时，实际上限生效 —— 表现为"沿站台每隔几格一个人"的散列，而不是满格；</li>
	 *   <li>客量低时按比例，不触上限。</li>
	 * </ul>
	 * <p>数字由 {@code platform cap <n>} 改，并会镜像进存档目录的 {@code mmtr-crowd.json}（重启后仍在）。</p>
	 */
	private static final AtomicInteger MAX_PER_PLATFORM = new AtomicInteger(DEFAULT_MAX_PER_PLATFORM);

	/**
	 * 只在**有玩家的这个半径内**的站台上站人（格）。
	 *
	 * <p>为什么还要这一道：上限只管"一个站台"，一个服务端可以同时加载好几个站台
	 * （forceload / 几个玩家各站一站）。半径之外的人是纯白花的客户端与网络开销。</p>
	 */
	private static final AtomicInteger PLAYER_RADIUS = new AtomicInteger(DEFAULT_PLAYER_RADIUS);

	public static int maxPerPlatform() {
		return MAX_PER_PLATFORM.get();
	}

	public static void setMaxPerPlatform(int value) {
		MAX_PER_PLATFORM.set(Math.max(1, Math.min(MAX_PER_PLATFORM_LIMIT, value)));
	}

	public static int playerRadius() {
		return PLAYER_RADIUS.get();
	}

	public static void setPlayerRadius(int value) {
		PLAYER_RADIUS.set(Math.max(16, Math.min(PLAYER_RADIUS_LIMIT, value)));
	}

	/** 自动区宽度（格）的默认值：没打标记时，从站台方块往站台里数几格算人流区。 */
	public static final int DEFAULT_AUTO_ROWS = 2;
	/** 自动区宽度的硬顶（格）。 */
	public static final int AUTO_ROWS_LIMIT = 8;

	/**
	 * **自动区宽度**：没打标记方块时，游戏侧从站台方块（MTR 站台方块那一排）**往站台里**数几格。
	 *
	 * <p>用户口径（2026-10）："站台方块本身不能站人，所以外扩二格即可" —— 现场量过（上水村 x=900）：
	 * 站台方块那一排（z=1798）紧贴轨道，人站的那一层地面在它**后面**（z=1797、1796，同为 y=65 的
	 * 平滑石台面），站台方块自己那一格上不站人。所以默认 2：站台方块后面那两排是人流区。</p>
	 *
	 * <p>0 = **关掉自动区**（站台上只认手动放的标记方块）。站台比两格宽得多时，
	 * 就把自动区关掉、用标记方块精确画（一级/二级/流动见 {@code MmtrCrowdModule}）。</p>
	 */
	private static final AtomicInteger AUTO_ROWS = new AtomicInteger(DEFAULT_AUTO_ROWS);

	public static int autoRows() {
		return AUTO_ROWS.get();
	}

	public static void setAutoRows(int value) {
		AUTO_ROWS.set(Math.max(0, Math.min(AUTO_ROWS_LIMIT, value)));
	}

	/**
	 * **勘测模式**：打开后，玩家半径那道闸门不再生效 —— 只要区块加载着，站台就按客量铺出人来。
	 *
	 * <p>为什么要有这个开关：</p>
	 * <ul>
	 *   <li>布景/截图：人不在场（或者人在站厅、在调试别的站台）时也想看这条站台铺出来什么样；</li>
	 *   <li>核对：{@code platform scan} 报"半径内没玩家 ⇒ 整条站台收走"时，用它区分
	 *       "是闸门收走的"还是"根本没铺出来"。</li>
	 * </ul>
	 * <p><b>代价</b>：所有**已加载区块**里的站台都会铺人（forceload 的站台也算），
	 * 所以它默认是关的 —— 平时靠玩家半径把看不见的人省掉。用完记得关。</p>
	 */
	private static final AtomicBoolean FORCE_POPULATE = new AtomicBoolean(false);

	public static boolean forcePopulate() {
		return FORCE_POPULATE.get();
	}

	public static void setForcePopulate(boolean value) {
		FORCE_POPULATE.set(value);
	}

	// ------------------------------------------------------------------ 重算版本号

	private static final AtomicLong REVISION = new AtomicLong();

	/** 当前版本号（游戏侧只做"与我上次处理过的那一份比"）。 */
	public static long revision() {
		return REVISION.get();
	}

	/**
	 * 记一次"客量变了/要求立刻重铺"。
	 *
	 * @return 新的版本号
	 */
	public static long bumpRevision() {
		return REVISION.incrementAndGet();
	}
}
