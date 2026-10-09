package org.mtr.core.mmtr.crowd;

import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Data;
import org.mtr.core.data.Platform;

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
	/** 单站台人数上限的硬顶（防手滑写成几千）。 */
	public static final int MAX_PER_PLATFORM_LIMIT = 400;
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
