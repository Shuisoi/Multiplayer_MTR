package org.mtr.mod.render;

import org.mtr.mapping.render.tool.GlStateTracker;
import org.mtr.mod.Init;

import java.lang.reflect.Field;
import java.util.function.Supplier;

/**
 * "在 GL 状态已受保护的前提下干活" —— 建/绑 VAO、VBO 必须如此（notes/398 §10）。
 *
 * <h2>为什么需要它（而不是直接用 <code>OptimizedRenderer.beginReload()</code>）</h2>
 *
 * <p>{@code OptimizedRenderer.beginReload()} 做两件事：{@code ShaderManager.reloadShaders()} + {@code GlStateTracker.capture()}。
 * 而 {@code reloadShaders()} 会 <b>关掉并重新编译 3 个着色器程序</b>（字节码：{@code shaders.values().forEach(close)}
 * → {@code clear()} → {@code loadShader} × 3）。</p>
 *
 * <p>问题在于 MTR 把这两件事**绑在了模型构建路径上**：一次车辆重建会调 {@code beginReload()} 五次左右
 * （4 个 {@code writeToOptimizedModels} + 1 个 {@code createModel}），于是同一个着色器被重编译 15 次。
 * 2026-10-05 实机实测：这五次调用让**渲染线程卡住 562 ms**（日志 `[MMTR-LAG] 帧间隔 562ms`，
 * 与 5 条 {@code ShaderManager.reloadShaders()} 同一毫秒）。</p>
 *
 * <p>而建 VBO **只需要 {@code capture()} 那一半**。所以本类只做那一半，
 * 着色器的重编译时机交给 {@code OptimizedRendererWrapper} 用一个"该重载了吗"的标记去管。</p>
 *
 * <h2>为什么必须先问一句"已经受保护了吗"</h2>
 *
 * <p>{@code GlStateTracker} 用的是**一个布尔量，不是嵌套计数**（字节码确认）：
 * {@code capture()} 在已保护时**直接 return**（幂等），但 {@code restore()} **无条件**清掉保护位。
 * 所以盲目 {@code capture()+restore()} 会把**外层**的保护区也拆掉，害得 MTR 自己后面建 buffer 抛异常。
 * 判据是：<b>本来没保护 → 由我们 capture，也由我们 restore；本来就保护着 → 我们什么都不碰。</b></p>
 */
public final class MmtrGlStateGuard {

	private static Field protectedField;
	private static boolean protectedFieldResolved;

	private MmtrGlStateGuard() {
	}

	/**
	 * 开一段保护区。
	 *
	 * @return {@code true} = 这次是**我们**开的，调用方必须在 {@code finally} 里 {@link #end(boolean)}；
	 *         {@code false} = 外层已经开着，我们没碰任何东西，也**不要**去收尾
	 */
	public static boolean begin() {
		if (isProtected()) {
			return false;
		}
		GlStateTracker.capture();
		return true;
	}

	/** 收尾。{@code weCaptured} 必须是 {@link #begin()} 的返回值 —— 不是我们开的就不许关。 */
	public static void end(boolean weCaptured) {
		if (weCaptured) {
			GlStateTracker.restore();
		}
	}

	/** 跑一段需要保护区的代码并返回结果（{@link #begin()}/{@link #end(boolean)} 的包裹形式）。 */
	public static <T> T with(Supplier<T> action) {
		final boolean weCaptured = begin();
		try {
			return action.get();
		} finally {
			end(weCaptured);
		}
	}

	/**
	 * {@code GlStateTracker.isStateProtected} 是私有的、映射库没给 getter，只能反射读。
	 *
	 * <p>拿不到就当作"已受保护"（于是我们**不**capture、也**不**restore）：
	 * 猜错的后果不对称 —— 当作 true 最多是这次建对象失败（有调用方的兜底接住）；
	 * 当作 false 会把别人的保护区拆掉，连累 MTR 自己，代价大得多。</p>
	 */
	private static boolean isProtected() {
		if (!protectedFieldResolved) {
			protectedFieldResolved = true;
			try {
				final Field field = GlStateTracker.class.getDeclaredField("isStateProtected");
				field.setAccessible(true);
				protectedField = field;
			} catch (Exception exception) {
				Init.LOGGER.warn("[MMTR-GLSTATE] 取不到 GlStateTracker.isStateProtected —— 保护区一律按「已受保护」处理（不去动 GL 状态）", exception);
			}
		}
		if (protectedField == null) {
			return true;
		}
		try {
			return protectedField.getBoolean(null);
		} catch (Exception exception) {
			return true;
		}
	}
}
