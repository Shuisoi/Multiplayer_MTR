package org.mtr.mod.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.mtr.mod.Init;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * **在驾驶室里把"别的模组的撞键绑定"临时挪走**（用户口径 2026-09-29 的下半场；2026-10-01 实机补课）。
 *
 * <h2>为什么"摘状态"不够</h2>
 *
 * <p>{@link MmtrInputDecouple} 的做法是让撞键的绑定在 {@code isPressed()/wasPressed()} 上回答"没按" ——
 * 这对<b>通过 KeyBinding 读键</b>的读者（原版动作、MMTR 自己）完全够用。但有些模组<b>不这么读</b>：
 * JourneyMap 的 {@code KeyBindingAction.isActive(int keyCode, …)} 拿的是<b>原始键码</b>，再和它自己那条
 * 绑定的 {@code getKey().getCode()} 比 —— 它根本不会问 {@code isPressed()}。实机现象就是：
 * 日志里明明写着"解耦：摘掉 key.journeymap.map_toggle_alt"，按 J 依旧弹出全屏地图（2026-10-01）。</p>
 *
 * <p>对这种读者只剩一招：把它的绑定<b>临时挪到"未绑定"</b>（{@code InputUtil.UNKNOWN_KEY}，键码 −1）。
 * JourneyMap 的 {@code isActiveAndMatches} 第一句就是"键是 UNKNOWN ⇒ false"，比较键码那条也自然不成立
 * —— 而且它的 {@code UpdateAwareKeyBinding.setBoundKey} 只调 {@code super} 加一个重排标志，
 * <b>不写它的配置文件</b>（反编译核对过），所以放回去就是原样。</p>
 *
 * <h2>只挪"别的模组"的绑定</h2>
 *
 * <p>原版的绑定（{@code key.left}/{@code key.drop}/…）<b>不挪</b>：它们靠摘状态就已经安静了（实机已验），
 * 而原版的绑定是玩家最不能丢的东西 —— 少碰一次就少一次"把 options.txt 写坏"的机会。
 * 判据用原版自己的分类常量（{@link KeyBinding#MOVEMENT_CATEGORY} 等），不是我们手写的名单：
 * 原版绑定都在这些分类里，模组的绑定用自己的分类（{@code journeymap} / {@code iris} / …）。</p>
 *
 * <h2>什么时候放回去（关键的安全网）</h2>
 *
 * <ul>
 *   <li>人一离开操纵位（{@code isDecoupled()} 转 false）：每 tick 检查一次，界面一开就恢复（聊天里 J 必须
 *       还能当 J 用）；</li>
 *   <li><b>任何一次写 {@code options.txt} 之前</b>：{@code GameOptionsWriteMixin} 在 {@code GameOptions.write()}
 *       的 HEAD 调 {@link #restoreAll()}。没有这一条，坐在司机位上直接关窗口退出，就会把"未绑定"
 *       永久写进玩家的 options.txt。</li>
 * </ul>
 */
public final class MmtrKeyNeutraliser {

	/** 被挪走的绑定 → 它原来的键位。用 identity 语义：这里比的是<b>实例</b>，不是键位。 */
	private static final Map<KeyBinding, InputUtil.Key> saved = new IdentityHashMap<>();
	/** 被挪走的绑定的翻译键（给 {@link MmtrInputDecouple#blocks} 用：挪走之后它绑的是 UNKNOWN，撞键判据已经不成立）。 */
	private static final Set<String> neutralisedTranslations = new LinkedHashSet<>();
	/** 已经说过一次的绑定（每种一行，总量有上限）。 */
	private static final List<String> logged = new ArrayList<>();
	private static final int LOG_LIMIT = 16;
	/** "已经放回去"那句话只说一次（否则每开一次界面就说一遍）。 */
	private static boolean restoreLogged;

	private MmtrKeyNeutraliser() {
	}

	/** 每客户端 tick 一次（{@code MainRenderer} 里紧跟 {@code MmtrDriveInput.tick()}）。 */
	public static void tick() {
		try {
			if (MmtrInputDecouple.isDecoupled()) {
				neutralise();
			} else {
				restoreAll();
			}
		} catch (Throwable throwable) {
			// 出任何事都选"把键位放回去"这一侧：宁可解耦失效，也不能让玩家的键位留在未绑定上。
			restoreAll();
		}
	}

	/** 现在被挪走的绑定数（诊断/用例用）。 */
	public static int neutralisedCount() {
		return saved.size();
	}

	/** 这个绑定是不是被我们挪走了（挪走后它与 MMTR 的"撞键"关系已经不成立，判据要另认）。 */
	public static boolean isNeutralised(String translationKey) {
		return translationKey != null && neutralisedTranslations.contains(translationKey);
	}

	/** 把全部挪走的绑定原样放回（幂等；离开操纵位、打开界面、写 options.txt 之前都走它）。 */
	public static void restoreAll() {
		if (saved.isEmpty()) {
			return;
		}
		int restored = 0;
		for (final Map.Entry<KeyBinding, InputUtil.Key> entry : new ArrayList<>(saved.entrySet())) {
			try {
				entry.getKey().setBoundKey(entry.getValue());
				restored++;
			} catch (Throwable throwable) {
				// 一个放不回去也不能挡住其它的（下一次 tick/write 还会再试）。
			}
		}
		if (restored == saved.size()) {
			saved.clear();
			neutralisedTranslations.clear();
		} else {
			// 有放不回去的：只把成功的那些从账本里划掉，下一轮继续试。
			saved.entrySet().removeIf(entry -> {
				try {
					entry.getKey().setBoundKey(entry.getValue());
					return true;
				} catch (Throwable throwable) {
					return false;
				}
			});
			neutralisedTranslations.clear();
			saved.keySet().forEach(binding -> neutralisedTranslations.add(binding.getTranslationKey()));
		}
		if (!restoreLogged && restored > 0) {
			restoreLogged = true;
			Init.LOGGER.info("[MMTR-KEY] 临时挪开的绑定已放回 {} 个（离开驾驶室 / 打开界面 / 写 options.txt 之前）", restored);
		}
	}

	private static void neutralise() {
		final GameOptions options = options();
		if (options == null || options.allKeys == null) {
			return;
		}
		for (final KeyBinding binding : options.allKeys) {
			if (binding == null || saved.containsKey(binding)) {
				continue;
			}
			final String translationKey = binding.getTranslationKey();
			// 我们自己的键永远不动；原版的绑定也不动（见类注释）。
			if (translationKey == null || translationKey.startsWith(MmtrKeyEventFanout.OUR_KEY_PREFIX) || isVanillaCategory(binding.getCategory())) {
				continue;
			}
			final String boundKey = binding.getBoundKeyTranslationKey();
			if (!MmtrInputDecouple.collidesWithOurKeys(boundKey)) {
				continue;
			}
			try {
				final InputUtil.Key original = InputUtil.fromTranslationKey(boundKey);
				binding.setBoundKey(InputUtil.UNKNOWN_KEY);
				saved.put(binding, original);
				neutralisedTranslations.add(translationKey);
				logNeutralisedOnce(translationKey, boundKey);
			} catch (Throwable throwable) {
				// 挪不动就拉倒：它至少还被"摘状态"挡着。
			}
		}
	}

	private static void logNeutralisedOnce(String translationKey, String boundKey) {
		if (logged.size() >= LOG_LIMIT || logged.contains(translationKey)) {
			return;
		}
		logged.add(translationKey);
		Init.LOGGER.info("[MMTR-KEY] 临时挪开 {}：{} → 未绑定（这种读者拿的是原始键码，摘状态挡不住；离开驾驶室立刻放回）",
				translationKey, boundKey);
	}

	/**
	 * 原版自己的按键分类（用游戏自己的常量，不写名单）——原版绑定都落在这些分类里。
	 */
	private static boolean isVanillaCategory(String category) {
		return KeyBinding.MOVEMENT_CATEGORY.equals(category)
				|| KeyBinding.MISC_CATEGORY.equals(category)
				|| KeyBinding.MULTIPLAYER_CATEGORY.equals(category)
				|| KeyBinding.GAMEPLAY_CATEGORY.equals(category)
				|| KeyBinding.INVENTORY_CATEGORY.equals(category)
				|| KeyBinding.UI_CATEGORY.equals(category)
				|| KeyBinding.CREATIVE_CATEGORY.equals(category);
	}

	private static GameOptions options() {
		final MinecraftClient client = MinecraftClient.getInstance();
		return client == null ? null : client.options;
	}
}
