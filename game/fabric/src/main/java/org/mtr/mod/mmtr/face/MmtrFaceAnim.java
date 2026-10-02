package org.mtr.mod.mmtr.face;

import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 逐帧动画（notes/359 · F3）：一个元素随时间怎么变。
 *
 * <p>这个类是**纯数学**（不碰画布、不碰游戏），所以离线用例能把它钉死，工作室里也有一份同口径的
 * 实现（{@code anim.mjs}）。三个量分开表达，画法按自己的需要取：</p>
 *
 * <ul>
 *   <li>{@link #visible(long)} —— 该不该画（{@code blink}）；</li>
 *   <li>{@link #phase(long)} / {@link #offsetFraction(long)} —— 走到一个周期的哪儿（{@code marquee} 用它算位移，
 *       {@code spin} 用它算角度）；</li>
 *   <li>{@link #alpha(long)} —— 透明度（{@code fade}）。</li>
 * </ul>
 *
 * <h2>时间从哪来</h2>
 * <p>调用方每帧给一个毫秒时钟（{@code MmtrFaceRuntime} 用本机时钟）。**不是游戏 tick** ——
 * tick 会随卡顿变慢，那样闪烁会跟着掉帧一起变慢；墙钟时间不会。代价是"暂停游戏时动画仍在走"，
 * 对水牌这种显示牌来说这是想要的（跟现实里的翻牌牌一致）。</p>
 *
 * <h2>为什么要有 {@link #bucket(long, int)}</h2>
 * <p>动画意味着"每帧都不一样"，而贴图重画是这套系统里最贵的一步。所以重画签名里带的是
 * **量化后的时间桶**（默认 8 fps），不是每帧的时间：8 fps 的走马灯/闪烁看起来已经连续，
 * 代价却是 60 fps 的七分之一。</p>
 */
public record MmtrFaceAnim(Kind kind, double onMs, double offMs, double spanMs, boolean rightwards, double gapRatio, double minAlpha) {

	/** 没有动画的文档用的节拍（也就是"有动画时才用得上"的缺省值）。 */
	public static final int DEFAULT_FPS = 8;
	public static final int MAX_FPS = 30;

	public enum Kind {
		/** 亮 {@code onMs}、灭 {@code offMs}。 */
		BLINK,
		/** 文本横向走马灯。 */
		MARQUEE,
		/** 透明度来回（三角波）。 */
		FADE,
		/** {@code rotate} 在一个周期里转一圈。 */
		SPIN;

		/** {@code anim.kind} 的名字（小写）；不认得返回 {@code null}。 */
		public static Kind parse(String name) {
			return switch (name == null ? "" : name.trim().toLowerCase(Locale.ROOT)) {
				case "blink" -> BLINK;
				case "marquee" -> MARQUEE;
				case "fade" -> FADE;
				case "spin" -> SPIN;
				default -> null;
			};
		}
	}

	/** 认得的动画名（打包校验与工作室的类型表都读它）。 */
	public static Set<String> kinds() {
		final Set<String> names = new LinkedHashSet<>();
		for (final Kind kind : Kind.values()) {
			names.add(kind.name().toLowerCase(Locale.ROOT));
		}
		return names;
	}

	/**
	 * 读一个元素的 {@code anim} 段；没有 / 不认得 / 不是一个对象 ⇒ {@code null}（= 没有动画）。
	 *
	 * <p>不认识的 {@code kind} 刻意**当没有动画**而不是抛异常：资源包是不可信内容，
	 * 一个拼错的 {@code "blnik"} 应该让这块牌**照常显示**（静态），而不是整块不画。</p>
	 */
	public static MmtrFaceAnim from(JsonElement element) {
		if (element == null || !element.isJsonObject()) {
			return null;
		}
		final JsonObject raw = element.getAsJsonObject();
		final Kind kind = Kind.parse(string(raw, "kind", ""));
		if (kind == null) {
			return null;
		}
		final double defaultSpan = switch (kind) {
			case MARQUEE -> 4000;
			case FADE -> 1500;
			case SPIN -> 2000;
			case BLINK -> 1200;
		};
		return new MmtrFaceAnim(
			kind,
			Math.max(0, number(raw, "onMs", 600)),
			Math.max(0, number(raw, "offMs", 600)),
			Math.max(1, number(raw, "spanMs", defaultSpan)),
			// 只有**明确写了 right**（大小写/空白不敏感）才反过来走，其余一律当 left ——
			// 与工作室的 anim.mjs 同一条口径（拼错成 "rihgt" 的后果是"照样往左走"，不是"随机换个方向"）
			"right".equalsIgnoreCase(string(raw, "direction", "left").trim()),
			Math.max(0, number(raw, "gap", 0.3)),
			Math.min(1, Math.max(0, number(raw, "min", 0.25)))
		);
	}

	/** 有没有"随时间变"这件事（没有的话文档根本不用带时间桶，见类注释）。 */
	public boolean animates() {
		return switch (kind) {
			case BLINK -> onMs + offMs > 0;
			case MARQUEE, FADE, SPIN -> spanMs > 0;
		};
	}

	/** {@code blink}：这一瞬间画不画。别的动画永远画（它们只改位移/透明度）。 */
	public boolean visible(long timeMs) {
		if (kind != Kind.BLINK) {
			return true;
		}
		final double total = onMs + offMs;
		if (total <= 0) {
			// 两个都写 0 = "配了 blink 但没有任何时间"：当静态显示（比闪成"永远不画"更接近作者的本意）
			return true;
		}
		return modulo(timeMs, total) < onMs;
	}

	/** 在一个周期里走到哪儿（0..1）。{@code blink} 的周期是 {@code onMs + offMs}，其余是 {@code spanMs}。 */
	public double phase(long timeMs) {
		final double period = kind == Kind.BLINK ? onMs + offMs : spanMs;
		if (period <= 0) {
			return 0;
		}
		return modulo(timeMs, period) / period;
	}

	/**
	 * {@code marquee} 的位移比例（0..1）：0 = 文本刚从视口右边进来，1 = 完全走出视口左边。
	 * 方向 {@code right} 时反过来（1 → 0），由调用方决定怎么用。
	 */
	public double offsetFraction(long timeMs) {
		if (kind != Kind.MARQUEE || spanMs <= 0) {
			return 0;
		}
		final double travelled = modulo(timeMs, spanMs) / spanMs;
		return rightwards ? 1 - travelled : travelled;
	}

	/** {@code fade} 的透明度；别的动画返回 1。 */
	public double alpha(long timeMs) {
		if (kind != Kind.FADE) {
			return 1;
		}
		final double p = phase(timeMs);
		// 三角波：0 → 1 → 0（比锯齿波少一次"啪"的突跳）
		final double triangle = p < 0.5 ? p * 2 : (1 - p) * 2;
		return minAlpha + (1 - minAlpha) * triangle;
	}

	/** {@code spin} 的角度（度，0..360）；别的动画返回 0。 */
	public double degrees(long timeMs) {
		return kind == Kind.SPIN ? phase(timeMs) * 360 : 0;
	}

	/**
	 * 时间桶：把毫秒量化到 {@code fps} 分之一秒。
	 *
	 * <p>重画签名用它 —— 同一个桶里算出来的签名相同，于是不会重画。{@code fps} 超出 1..{@link #MAX_FPS}
	 * 时先用缺省值 {@link #DEFAULT_FPS}（作者写 {@code fps: 0} 的意思是"我没想那么多"，
	 * 而不是"每秒重画 0 次"那么抠）。</p>
	 */
	public static long bucket(long timeMs, int fps) {
		final int effective = fps < 1 || fps > MAX_FPS ? DEFAULT_FPS : fps;
		return Math.floorDiv(Math.max(0, timeMs) * effective, 1000L);
	}

	/** Java 的 {@code %} 对负数会给负余数，动画时间要的是"永远往前走"的那种取模。 */
	private static double modulo(long timeMs, double period) {
		final double value = timeMs % period;
		return value < 0 ? value + period : value;
	}

	private static String string(JsonObject object, String key, String fallback) {
		final JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsString();
	}

	private static double number(JsonObject object, String key, double fallback) {
		final JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
	}
}
