package org.mtr.mod.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.mtr.mod.Init;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * **一次键盘事件只发给"撞键"里的一个绑定 —— 我们把它补发给全部**（用户口径 2026-09-29 的下半场）。
 *
 * <h2>原版是怎么把按键弄丢的</h2>
 *
 * <p>原版的按键状态有两条路：{@code KeyBinding.setKeyPressed(key, pressed)}（按住/抬起）与
 * {@code KeyBinding.onKeyPressed(key)}（点按计数 {@code timesPressed}）。这两条路都只查
 * {@code KeyBinding.KEY_TO_BINDINGS} —— 一个 {@code Map<InputUtil.Key, KeyBinding>}，
 * 也就是<b>一个物理键只能有一个绑定收得到事件</b>。谁是赢家由 {@code updateKeysByCode()} 遍历
 * {@code KEYS_BY_ID}（一个 HashMap）的顺序决定，而那个顺序**只由字符串哈希决定** —— 与"谁先注册"
 * 无关，逐键独立，而且每次启动都一样。</p>
 *
 * <p>于是 MMTR 的 A（{@code key.mmtr.drive.traction}）与原版 {@code key.left} 撞键时：</p>
 *
 * <ul>
 *   <li>赢家是 MMTR ⇒ <b>原版"向左走"在车里车外全部失效</b>（{@code pressed} 永远不被写）；</li>
 *   <li>赢家是原版 ⇒ MMTR 的油门键失效，而且点按键更糟：{@code timesPressed} 根本不会加，
 *       {@code wasPressed()} 永远 false（"按 L 弹进度、灯光却没换"就是这一种）。</li>
 * </ul>
 *
 * <p>2026-10-01 实机现象正是前者：<b>只有 A 走不动，D/W/S/空格正常</b> —— 因为赢家是逐键按哈希顺序定的，
 * A 输给了 MMTR，D 没输。这才是"按键解绑逻辑还是有问题"的真正原因：不是我们摘多了，
 * 是原版那边<b>根本收不到那几个键</b>。</p>
 *
 * <h2>我们怎么做</h2>
 *
 * <p>在 {@code setKeyPressed} 的 HEAD 上把这次状态<b>写给所有绑在这个键上的绑定</b>。
 * {@code KeyBinding.setPressed(boolean)} 是公开的普通 setter（只写一个字段），赢家随后被原版再写一次
 * 同样的值，幂等。键盘事件是低频的（每秒几十次），代价是遍历一次 {@code GameOptions.allKeys}
 * （约一百个绑定）。</p>
 *
 * <p>点按计数 {@code timesPressed} 是私有的，这里不碰它：{@code ClientInputDecoupleMixin} 在
 * {@code wasPressed()} 上用<b>已经被补正过的 {@code pressed}</b> 自己造一次点按（按一下只报一次）。</p>
 *
 * <p>副作用是好的那一半：原版与 MMTR 的键<b>都</b>能收到事件（这正是原版"按键冲突"对话框承诺的行为），
 * 于是"在车外按 A 向左走"与"在驾驶室按 A 推油门"重新变成由位置（{@link MmtrInputDecouple}）决定的事，
 * 而不是由哈希顺序决定的事。</p>
 */
public final class MmtrKeyEventFanout {

	/** MMTR 自己的绑定前缀（诊断用：撞键表里哪些行是我们）。 */
	public static final String OUR_KEY_PREFIX = "key.mmtr.";

	/** 没绑任何键时的占位（这种"撞键"不是撞键）。 */
	private static final String UNBOUND = "key.keyboard.unknown";

	/** 撞键表只说一次（第一次收到键盘事件时）。 */
	private static boolean loggedConflicts;

	private MmtrKeyEventFanout() {
	}

	/**
	 * 把一次按下/抬起补发给所有绑在同一个键上的绑定。
	 *
	 * <p>由 {@code ClientInputDecoupleMixin} 在 {@code KeyBinding.setKeyPressed} 的 HEAD 调用。</p>
	 */
	public static void fanOutPressed(InputUtil.Key key, boolean pressed) {
		try {
			final GameOptions options = options();
			if (options == null || options.allKeys == null || key == null) {
				return;
			}
			final String keyTranslationKey = key.getTranslationKey();
			for (final KeyBinding binding : options.allKeys) {
				if (keyTranslationKey.equals(binding.getBoundKeyTranslationKey())) {
					binding.setPressed(pressed);
				}
			}
			logConflictsOnce(options);
		} catch (Throwable throwable) {
			// 输入这条路绝不能被我们自己的异常打断（与 MmtrInputDecouple 同一条纪律）。
		}
	}

	/** 诊断：现在有多少组撞键（每组都包含至少一个 MMTR 的键）。 */
	public static int countConflicts() {
		try {
			final GameOptions options = options();
			return options == null || options.allKeys == null ? 0 : conflicts(options).size();
		} catch (Throwable throwable) {
			return 0;
		}
	}

	/**
	 * 把"同一个物理键上有几个绑定"的现场说一次。
	 *
	 * <p>为什么值得占几行日志：这件事从外面看只有一个症状 —— <b>某个键没反应</b>，而且车厢内外、
	 * 原版与我们的键会各丢一半，靠猜要来回好几轮（2026-10-01 实机就绕了很久）。</p>
	 */
	private static void logConflictsOnce(GameOptions options) {
		if (loggedConflicts) {
			return;
		}
		loggedConflicts = true;
		final Map<String, ArrayList<String>> conflicts = conflicts(options);
		conflicts.forEach((key, bindings) -> Init.LOGGER.info(
				"[MMTR-KEY] 撞键 {}：{} —— 原版只会把事件发给其中一个，现在由我们把事件补发给全部",
				key, String.join(" + ", bindings)));
		Init.LOGGER.info("[MMTR-KEY] 键盘事件补发已启用：涉及 MMTR 的撞键 {} 组（原版动作与 MMTR 动作都能收到按键）",
				conflicts.size());
	}

	/** 撞键表：物理键 → 绑在它上面的全部绑定（只留涉及 MMTR 的、且真有多个绑定的）。 */
	private static Map<String, ArrayList<String>> conflicts(GameOptions options) {
		final Map<String, ArrayList<String>> byKey = new LinkedHashMap<>();
		for (final KeyBinding binding : options.allKeys) {
			if (binding == null) {
				continue;
			}
			byKey.computeIfAbsent(binding.getBoundKeyTranslationKey(), key -> new ArrayList<>()).add(binding.getTranslationKey());
		}
		final Map<String, ArrayList<String>> result = new LinkedHashMap<>();
		byKey.forEach((key, bindings) -> {
			if (bindings.size() < 2 || UNBOUND.equals(key)) {
				return;
			}
			// 只看涉及我们的：原版与别的模组之间的撞键不是这一轮的事，说全了会刷屏。
			if (bindings.stream().anyMatch(binding -> binding.startsWith(OUR_KEY_PREFIX))) {
				result.put(key, bindings);
			}
		});
		return result;
	}

	private static GameOptions options() {
		final MinecraftClient client = MinecraftClient.getInstance();
		return client == null ? null : client.options;
	}
}
