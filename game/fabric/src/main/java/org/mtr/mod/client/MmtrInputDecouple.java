package org.mtr.mod.client;

import org.mtr.mapping.holder.KeyBinding;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mod.KeyBindings;

import javax.annotation.Nullable;

/**
 * **驾驶室里的按键与游戏内操作解耦**：坐在司机位上时，凡是与 MMTR 键**撞键**的绑定都当作没被按过
 * （用户口径 2026-09-29：「进入驾驶室后，按键需要和游戏内操作解耦」）。
 *
 * <h2>为什么按"撞键"判，而不是点名几个原版键</h2>
 *
 * <p>第一版是点名法（只摘原版的 {@code leftKey}/{@code rightKey}/{@code dropKey}/{@code swapHandsKey}），
 * 实机当场就漏了：<b>按 L 弹出"进度"页面</b> —— 原版的 {@code key.advancements} 默认就是 <b>L</b>。
 * 顺着这条线把玩家自己的 {@code options.txt} 与 MMTR 的键位对了一遍，漏的还有一整排
 * <b>JourneyMap</b> 的默认键（它撞了 B 建路径点 / J 全屏地图 / N 全屏路径点 / H / ←→）。
 * 点名法**永远会漏**：原版有几十个绑定、外加每个模组自己的，而玩家的 {@code options.txt} 还能把任何一个
 * 挪到我们的键上。</p>
 *
 * <p>所以判据换成一条**结构性的**规则：</p>
 *
 * <blockquote>
 * 坐在司机位上时，一个绑定被摘掉 ⇔ ①它不是 MMTR 自己的绑定（{@code key.mmtr.*}）<b>且</b>
 * ②它**当前**绑定的键位与 MMTR 某个键**撞了**。
 * </blockquote>
 *
 * <p>三个好处：不用维护名单（原版/模组/玩家改键自动跟上）；只影响真正撞键的那些键
 * （背包 E、聊天 T、F5、Esc 一律照旧 —— 驾驶室不该变成"游戏没反应"的地方）；MMTR 自己的键
 * 永远不被摘（前缀判定），所以"推油门"不会被自己的解耦吃掉。</p>
 *
 * <h2>为什么判据要缓存</h2>
 *
 * <p>{@code isPressed()} 每一 tick 会被原版问几十次（它遍历 {@code allKeys}），而"我在不在司机位上"
 * 要遍历车辆镜像。100 ms 的缓存对键盘手感毫无影响（按键状态的上升沿另有 20 tick/s 的读取点），
 * 却把这条路变成一次字段读。窗口本身搬到了 {@link MmtrDecoupleCache}（纯函数、可离线测）。</p>
 *
 * <h2>⚠️ 这个缓存曾经让整套解耦一次都没跑过（2026-10-01）</h2>
 *
 * <p>第一版把"缓存还新鲜吗"写成 {@code now - cachedAtMillis < CACHE_MILLIS}，而 {@code cachedAtMillis}
 * 的初值是 {@code Long.MIN_VALUE}：{@code now - Long.MIN_VALUE} 在 long 上溢出成负数，条件<b>永远成立</b>，
 * 于是函数第一次就返回初值 {@code false} 且再也不重算 —— 判据永远不跑，日志里一行 {@code [MMTR-KEY]}
 * 都没有。现在哨兵只参与等式比较（{@link MmtrDecoupleCache#fresh}），并有可执行的离线用例钉住。</p>
 */
public final class MmtrInputDecouple {

	/** MMTR 自己的绑定的前缀（它们永远不被摘）。 */
	public static final String OUR_KEY_PREFIX = "key.mmtr.";
	/** 没绑任何键时的占位（原版对这个值返回它）—— 不能把它当成"撞键"。 */
	private static final String UNBOUND = "key.keyboard.unknown";

	/** 判据缓存（窗口与"新鲜吗"的算法在 {@link MmtrDecoupleCache}；哨兵不能参与减法，见那里的类注释）。 */
	private static long cachedAtMillis = MmtrDecoupleCache.NEVER;
	private static boolean cachedDecoupled;
	/** 用例/探针把判据钉成某个值（{@code null} = 走真实判据）。 */
	private static @Nullable Boolean forcedForTesting;
	/** MMTR 全部键**当前**绑定的键位（{@code "key.keyboard.a"} 这种）；懒加载 + 反射读 {@link KeyBindings}。 */
	private static @Nullable java.util.Set<String> ourBoundKeys;

	private MmtrInputDecouple() {
	}

	/**
	 * 这个绑定要不要在驾驶室里被摘掉。
	 *
	 * @param translationKey 绑定自己的 id（{@code "key.mmtr.lights"} / {@code "key.advancements"} / {@code "journeymap.map_toggle_alt"}）
	 * @param boundKey       它**当前**绑在哪个键位（{@code "key.keyboard.l"} 这种，见 {@code KeyBinding#getBoundKeyTranslationKey}）
	 */
	public static boolean blocks(@Nullable String translationKey, @Nullable String boundKey) {
		// 先问便宜的那个（缓存过的布尔）：不在司机位上时，后面的字符串活儿一次都不做。
		if (!isDecoupled()) {
			return false;
		}
		if (translationKey != null && translationKey.startsWith(OUR_KEY_PREFIX)) {
			return false;
		}
		/*
		 * 被 {@link MmtrKeyNeutraliser} 挪走的绑定：它现在绑的是 UNKNOWN，下面那条"撞键"判据已经不成立，
		 * 但它的按键状态仍要摘掉 —— 有的模组读 isPressed()，有的读原始键码，两条路都得安静。
		 */
		if (MmtrKeyNeutraliser.isNeutralised(translationKey)) {
			return true;
		}
		if (!collidesWithOurKeys(boundKey)) {
			return false;
		}
		logSuppressedOnce(translationKey, boundKey);
		return true;
	}

	/** 这个键位是不是 MMTR 的某个键也在用（"撞键"判据**只有这一份**：{@link #blocks} 与 {@link MmtrKeyNeutraliser} 都用它）。 */
	public static boolean collidesWithOurKeys(@Nullable String boundKey) {
		return boundKey != null && !UNBOUND.equals(boundKey) && ourBoundKeys().contains(boundKey);
	}

	/**
	 * 摘掉某个绑定时说一次（**每种绑定最多一行**，总量有上限）。
	 *
	 * <p>为什么要说话：解耦的现场表现是"某个键在驾驶室里没反应"（例如原版的 L 不再弹进度页面）——
	 * 那是**预期行为**，但从外面看和"按键坏了"一模一样。留一行日志，这类问题就不必靠猜
	 * （2026-09-29 实机：按 L 弹出进度页面，就是这一行没写、机制也没按撞键判造成的）。</p>
	 */
	private static void logSuppressedOnce(@Nullable String translationKey, String boundKey) {
		if (suppressedLogged.size() >= SUPPRESSED_LOG_LIMIT || translationKey == null || suppressedLogged.contains(translationKey)) {
			return;
		}
		suppressedLogged.add(translationKey);
		if (suppressedLogged.size() == 1) {
			org.mtr.mod.Init.LOGGER.info("[MMTR-KEY] 驾驶室按键解耦生效：MMTR 键位 = {}（坐在司机位上时，撞了这些键的原版/模组绑定一律不触发）", describeOurKeys());
		}
		org.mtr.mod.Init.LOGGER.info("[MMTR-KEY] 解耦：摘掉 {}（它绑在 {}，MMTR 有个键也是这个键位）", translationKey, boundKey);
	}

	/** 已经说过的那几种绑定（每样一行，防止刷屏）。 */
	private static final java.util.Set<String> suppressedLogged = new java.util.HashSet<>();
	/** 日志上限（正常只有 A/D/Q/F/L + JourneyMap 那几行）。 */
	private static final int SUPPRESSED_LOG_LIMIT = 24;

	/**
	 * MMTR 自己的键现在都绑在哪几个键位上（懒加载一次；反射读 {@code KeyBindings} 的公开静态字段）。
	 *
	 * <p>为什么用反射而不是手写一张表：手写的表会在"加一根新键 / 在控制菜单里改键"时悄悄过期，
	 * 过期的那一次就是"某个原版动作又开始在驾驶室里跟着触发"。</p>
	 */
	private static java.util.Set<String> ourBoundKeys() {
		if (ourBoundKeys != null) {
			return ourBoundKeys;
		}
		final java.util.HashSet<String> keys = new java.util.HashSet<>();
		try {
			for (final java.lang.reflect.Field field : KeyBindings.class.getFields()) {
				if (field.getType() == KeyBinding.class && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
					final Object value = field.get(null);
					if (value instanceof final KeyBinding binding && !binding.isUnbound()) {
						final String boundKey = binding.getBoundKeyTranslationKey();
						if (boundKey != null && !UNBOUND.equals(boundKey)) {
							keys.add(boundKey);
						}
					}
				}
			}
		} catch (Throwable throwable) {
			// 读不到就退回"什么都不挡"：解耦失效比"把输入搞坏"轻得多。
		}
		ourBoundKeys = keys;
		return keys;
	}

	/** 诊断用：现在认下来的 MMTR 键位（"key.keyboard.a" 这种；日志/探针读它）。 */
	public static String describeOurKeys() {
		final java.util.ArrayList<String> sorted = new java.util.ArrayList<>(ourBoundKeys());
		java.util.Collections.sort(sorted);
		return String.join(",", sorted);
	}

	/**
	 * 现在要不要把与 MMTR 键撞键的绑定摘下来。
	 *
	 * <p>界面开着时一律**不摘**：那时候原版根本不跑移动/丢弃/进度那几条路（打字优先），
	 * 而 MMTR 自己的键在 {@code MmtrDriveInput} 里另有"界面开着不读键"的守卫。</p>
	 */
	public static boolean isDecoupled() {
		if (forcedForTesting != null) {
			return forcedForTesting;
		}
		final long now = System.currentTimeMillis();
		if (MmtrDecoupleCache.fresh(now, cachedAtMillis)) {
			return cachedDecoupled;
		}
		cachedAtMillis = now;
		cachedDecoupled = compute();
		return cachedDecoupled;
	}

	private static boolean compute() {
		try {
			final MinecraftClient client = MinecraftClient.getInstance();
			if (client == null || client.getCurrentScreenMapped() != null) {
				return false;
			}
			// 便宜的先判：没骑任何车 ⇒ 绝不可能坐在司机位上（省掉一次车辆镜像遍历）。
			if (VehicleRidingMovement.getRidingVehicleId() == 0) {
				return false;
			}
			return MmtrDriverSeat.isAtControls();
		} catch (Throwable throwable) {
			// 输入这条路绝不能被我们自己的异常打断：判不出来就当"没坐在司机位上"。
			return false;
		}
	}

	/** 诊断/用例用：把这列车在不在司机位上的判断强制成某个值（{@code null} = 回到自动判断）。 */
	public static void overrideForTesting(@Nullable Boolean value) {
		forcedForTesting = value;
	}
}
