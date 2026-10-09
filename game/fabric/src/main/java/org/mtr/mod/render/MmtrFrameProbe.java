package org.mtr.mod.render;

import org.mtr.core.data.PathData;
import org.mtr.core.data.Vehicle;
import org.mtr.mod.Init;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端**渲染帧**的性能探针 —— 回答"这一帧的时间花在哪一段"。
 *
 * <h2>为什么需要它（这个仓里原本没有的东西）</h2>
 *
 * <p>服务端那一侧早就有 {@code MmtrTickProbe} + 引擎的 {@code MmtrProbe}（notes/337，成体系的
 * 帧/段/计数三层 + 分位 + 最坏帧榜）。而**客户端只剩一个总帧率**：
 * {@code [MMTR-PERF]} 给"帧=…（… FPS）"，{@code [MMTR-LIGHT] 性能} 给"平均帧时=… ms" ——
 * 两条互相印证的数字，但**都不告诉你时间花在哪一段**。设计文档自己也把这条写在"量不到的"里
 * （{@code 01-设计/性能探针与侦测项-设计.md} §5）。</p>
 *
 * <p>于是现场只能靠"关掉一个特性看帧率动不动"去二分。实测一轮 32 分钟的会话要试 6 个变量、
 * 每试一次重启客户端 —— 而其中一半的猜测在读数面前本来是一眼就能排除的。这个类补的就是那一步。</p>
 *
 * <h2>★ 两个 pass 必须分开算（这是本探针存在的主要理由）</h2>
 *
 * <p>开光影时 {@code MainRenderer.render} 一个视觉帧会**被调用两次**：阴影 pass 与主 pass。
 * 而 {@code MainRenderer} 里的早期返回只挡掉了"模拟 + 每帧钩子"那一半
 * （{@code if (OptimizedRenderer.renderingShadows())} 的分支），**后面那一大半照跑**：
 * {@code RenderVehicles} / {@code RenderLifts} / {@code RenderRails} / 队列派发 /
 * {@code MmtrLightField.beginFrame} / 优化批次提交 —— 全部各跑两遍。</p>
 *
 * <p>阴影 pass 与**屏幕分辨率无关**（阴影贴图的分辨率与视距由包自己定，实测
 * {@code maxShadowRenderDistance=32}）—— 但这**不是**"分辨率拉到极低、帧率却几乎不动"的主因。
 * 2026-10-05 实测纠正：主因是**逐 draw 的 CPU 成本**（随 draw 数走、与像素数无关），
 * 证据是 `[MMTR-LIGHT]` 的 `draws/frame` 与 FPS 强负相关（308 窗口 r = −0.78）。
 * 详见 `docs/02-运行与作业/性能探针-运行手册.md` §8.5 与 notes/394、notes/395。</p>
 *
 * <p>段名因此带 pass 前缀：{@code main.vehicles} / {@code shadow.vehicles}。
 * 前缀不是装饰 —— 同一段代码在两边跑的是同一份 Java，只有前缀能告诉你是谁付的钱。</p>
 *
 * <h2>三条设计约束（照抄服务端探针的口径，理由见 01-设计 §1）</h2>
 *
 * <ul>
 *   <li><b>只记账，不改行为。</b>探针的返回值只表示"要不要计时"，绝不参与任何判断。
 *       所有调用点的顺序与副作用一概不动。</li>
 *   <li><b>关掉就近似零成本。</b>默认开（现场正需要它，与既有的 {@code [MMTR-PERF]} /
 *       {@code [MMTR-LIGHT] 性能} 同口径：都是一直开着的客户端遥测），
 *       但 {@code -Dmmtr.frame=0} 关掉之后每次调用只剩一次 {@code volatile} 读，
 *       {@code System.nanoTime()} 一次都不调。</li>
 *   <li><b>不许淹没日志。</b>输出是**窗口汇总**（默认每 5 秒一行，与 {@code [MMTR-PERF]} 同拍）
 *       + 一个**有界**的最坏帧榜（8 条）。段数也有上限（只打累计最重的 12 段）。</li>
 * </ul>
 *
 * <h2>怎么读（判据）</h2>
 *
 * <table>
 *   <tr><td>{@code 通行=2.0/帧}</td><td>一个视觉帧被渲染了两次 ⇒ 阴影 pass 开着（光影包）⇒ 帧预算按两遍算。
 *       注意"降分辨率救不了"与阴影距离**没有主因关系**（见类注释），别据此去调阴影距离。</td></tr>
 *   <tr><td>各段 {@code =累计ms/次数}</td><td>先看累计最大的那几段。**次数比毫秒更早暴露放大**：
 *       次数 ÷ 帧数 &gt; 1 就说明这段每帧跑了不止一遍。</td></tr>
 *   <tr><td>各段 {@code max=}</td><td>均值不大而 max 很大 ⇒ 尖峰（某一次特别贵），不是稳态慢。</td></tr>
 *   <tr><td>帧间隔 {@code p50 / p95 / 最慢}</td><td>p50 小 + 最慢很大 ⇒ 尖峰型卡顿（notes/177 那种
 *       "tick 还在跑、画面不出帧"）。整条分布都抬起来 ⇒ 稳态慢。</td></tr>
 *   <tr><td>最坏帧榜</td><td>{@code #帧号=耗时ms(最重段)}。帧号是与 {@code [MMTR-PERF]} 对齐用的。</td></tr>
 * </table>
 *
 * <p><b>诚实边界</b>：本探针量的是**渲染线程上的 Java 侧耗时**。它量不到
 * ①GPU/驱动侧真正的绘制时间（要 GPU timer query，见 notes/340 §8.1③）；
 * ②别的线程（区块构建、遮挡剔除 WorkerThread）占的核;
 * ③GC 停顿落在帧中间的那一段（那会表现为"所有段都正常但帧间隔很大"——这种形状本身就是结论）。</p>
 */
public final class MmtrFrameProbe {

	/**
	 * 段名（不带 pass 前缀）。顺序无所谓，但名字是**稳定接口**：改结构之后这行读数应当还在，
	 * 数值应当按预期变化（与服务端探针同一条纪律，见 01-设计 §5.3）。
	 */
	private static final String[] BASE_SECTIONS = {
			"simulate",   // 客户端车辆/电梯模拟（只在主 pass）
			"tick",       // 每帧钩子链（面板过期、面快照、交互键、输入…只在主 pass）
			"vehicles",   // RenderVehicles
			"lifts",      // RenderLifts
			"rails",      // RenderRails（含手持轨道工具的 33³ 节点扫描，那段另有 nodes 段）
			"queue",      // 渲染队列拷贝 + 派发
			"lightfield", // MmtrLightField.beginFrame（采集切片 + LUT + 上传 + 采样器绑定）
			"submit",     // 优化渲染器真正提交（OPTIMIZED_RENDERER_WRAPPER.render）
			"wiper",      // 挡风玻璃雨滴模拟 + 绘制（在 RenderVehicles 内部逐车调用）
			"faces",      // 车辆动态面/仪表画布（Java2D 软件光栅 + 纹理上传）
			"nodes",      // RenderRails 里那个 33³ 的节点扫描
	};

	/** 段数上限：`base` 与 `base + BASE_SECTIONS.length` 分别是 main / shadow 两套。 */
	private static final int SLOT_COUNT = BASE_SECTIONS.length * 2;

	private static final String[] SLOT_NAMES = new String[SLOT_COUNT];
	private static final Map<String, Integer> BASE_INDEX = new HashMap<>();

	static {
		for (int i = 0; i < BASE_SECTIONS.length; i++) {
			BASE_INDEX.put(BASE_SECTIONS[i], i);
			SLOT_NAMES[i] = "main." + BASE_SECTIONS[i];
			SLOT_NAMES[i + BASE_SECTIONS.length] = "shadow." + BASE_SECTIONS[i];
		}
	}

	/** 累计纳秒 / 次数 / 单次最坏纳秒。固定数组，**每帧零分配**。 */
	private static final long[] slotTotalNanos = new long[SLOT_COUNT];
	private static final int[] slotCount = new int[SLOT_COUNT];
	private static final long[] slotMaxNanos = new long[SLOT_COUNT];

	/** 帧间隔环形缓冲（报告时排序取分位；只在报告那一拍排序，不在每帧）。 */
	private static final int FRAME_RING = 1024;
	private static final long[] frameIntervals = new long[FRAME_RING];
	private static int frameIntervalWrite;
	private static int frameIntervalCount;
	private static long lastFrameStartNanos;
	private static long currentFrameStartNanos;
	private static long maxFrameIntervalMillis;

	/** 本窗口最后结束的那一帧的最重段（最坏帧榜的一行）。 */
	@Nullable
	private static String lastFrameHeaviest;

	private static final int WORST_FRAMES = 8;
	private static final long[] worstMillis = new long[WORST_FRAMES];
	private static final long[] worstFrameNumber = new long[WORST_FRAMES];
	private static final String[] worstWhere = new String[WORST_FRAMES];

	private static long frameNumber;
	private static long windowStartMillis;
	private static long windowFrames;

	/** 本窗口的 pass 计数与各自总耗时。 */
	private static long windowMainPasses;
	private static long windowShadowPasses;
	private static long windowMainPassNanos;
	private static long windowShadowPassNanos;

	/** 当前 pass 是不是阴影 pass；以及这一 pass 的开始时刻（供 passEnd 记账）。 */
	private static boolean inShadowPass;
	/** 本 pass 内各段的起点，用来在 passEnd 里挑出"最重的那一段"。 */
	/**
	 * 本**视觉帧**累计的段耗时（跨阴影 + 主两遍）。
	 *
	 * <p>⚠️ 它**不**在 {@link #passBegin} 里清零 —— 只在 {@link #frameBoundary} 清。
	 * 早先它叫 {@code currentPassSectionNanos} 且每次 passBegin 都清，结果是：主 pass 入口调
	 * {@code frameBoundary()} 时，数组里只剩下**本帧阴影 pass** 的段（主 pass 的段是上一次 passBegin
	 * 之后才攒的，还没轮到被读就被下一个 passBegin 清了）⇒ **最坏帧榜的"最重段"永远报不出 {@code main.*}**。
	 * 2026-10-05 修（notes/395）。</p>
	 */
	private static long[] currentFrameSectionNanos = new long[SLOT_COUNT];

	private static final boolean ENABLED = !"0".equals(System.getProperty("mmtr.frame", "1").trim());
	private static final long REPORT_INTERVAL_MILLIS = 5000L;
	private static final int REPORT_TOP_SECTIONS = 12;

	private MmtrFrameProbe() {
	}

	/** @return 探针是否开着。关掉时唯一开销就是这一次 volatile 读。 */
	public static boolean on() {
		return ENABLED;
	}

	/**
	 * 开始计一段。**埋在"一段工作的边界"，绝不埋在循环体里** ——
	 * 一次 {@code nanoTime()} 在几千次/帧的循环里就是灾难（01-设计 §6）。
	 *
	 * @return 开始时刻（纳秒）；探针关着时返回 0，{@link #end(String, long)} 见 0 直接返回
	 */
	public static long begin() {
		return ENABLED ? System.nanoTime() : 0L;
	}

	/** 结束计一段。{@code startedAtNanos == 0}（探针关着或调用方拿到 0）时是空操作。 */
	public static void end(String section, long startedAtNanos) {
		if (startedAtNanos == 0L) {
			return;
		}
		endNoCheck(section, System.nanoTime() - startedAtNanos);
	}

	/**
	 * 记一段**已知耗时**的工作（调用方自己量了）。给"时间不是在调用点量的"那种情形用
	 * （例如 {@code lastFrameDuration} 折出来的毫秒）。
	 */
	public static void add(String section, long nanos) {
		if (!ENABLED) {
			return;
		}
		endNoCheck(section, nanos);
	}

	private static void endNoCheck(String section, long nanos) {
		final Integer base = BASE_INDEX.get(section);
		if (base == null) {
			return;
		}
		final int slot = base + (inShadowPass ? BASE_SECTIONS.length : 0);
		slotTotalNanos[slot] += nanos;
		slotCount[slot]++;
		if (nanos > slotMaxNanos[slot]) {
			slotMaxNanos[slot] = nanos;
		}
		currentFrameSectionNanos[slot] += nanos;
	}

	/**
	 * **一个视觉帧的边界。** 必须在主 pass 的入口调（阴影 pass 不算新的一帧），
	 * 这样"帧间隔"就是真正的 1/FPS，而不是"半个视觉帧"。
	 */
	public static void frameBoundary() {
		if (!ENABLED) {
			return;
		}
		final long now = System.nanoTime();

		// 上一帧收尾：耗时 = 从它开始到这一帧开始（**含两遍 pass**，所以它才是 1/FPS）。
		if (currentFrameStartNanos != 0L) {
			final long intervalNanos = now - currentFrameStartNanos;
			final long intervalMillis = intervalNanos / 1_000_000L;
			lastFrameHeaviest = heaviestSectionName();
			if (intervalMillis > maxFrameIntervalMillis) {
				maxFrameIntervalMillis = intervalMillis;
			}
			frameIntervals[frameIntervalWrite] = intervalMillis;
			frameIntervalWrite = (frameIntervalWrite + 1) % FRAME_RING;
			if (frameIntervalCount < FRAME_RING) {
				frameIntervalCount++;
			}
			rememberWorstFrame(frameNumber, intervalMillis, lastFrameHeaviest);

			// 上一帧的段账清掉（最重段是按"整个视觉帧"算的，含两遍 pass）
			java.util.Arrays.fill(currentFrameSectionNanos, 0L);
		}

		currentFrameStartNanos = now;
		frameNumber++;
		windowFrames++;

		reportIfDue(now);
	}

	/**
	 * 开始一个渲染 pass。{@code shadow} 必须来自 {@code OptimizedRenderer.renderingShadows()}。
	 *
	 * <p>调用点要放在"阴影 pass 可能直接 return"的那些判断**之后** —— 否则一次什么都没画的
	 * 早退也会被记成一个 pass，把 {@code 通行/帧} 读高。</p>
	 */
	public static void passBegin(boolean shadow) {
		if (!ENABLED) {
			return;
		}
		inShadowPass = shadow;
		// ★ 这里**故意不清** currentFrameSectionNanos：它按整个视觉帧累计，由 frameBoundary 清。
		//   清了就会让"最重段"只看得到最后一个 pass（见该字段的说明）。
	}

	/** 结束一个渲染 pass。{@code startedAtNanos} 是 {@link #begin()} 在 pass 开头取的时刻。 */
	public static void passEnd(long startedAtNanos) {
		if (!ENABLED || startedAtNanos == 0L) {
			return;
		}
		final long nanos = System.nanoTime() - startedAtNanos;
		if (inShadowPass) {
			windowShadowPasses++;
			windowShadowPassNanos += nanos;
		} else {
			windowMainPasses++;
			windowMainPassNanos += nanos;
		}
	}

	/** 本帧累计最重的那一段（给最坏帧榜用）。 */
	@Nullable
	private static String heaviestSectionName() {
		int best = -1;
		for (int i = 0; i < SLOT_COUNT; i++) {
			if (currentFrameSectionNanos[i] > 0 && (best < 0 || currentFrameSectionNanos[i] > currentFrameSectionNanos[best])) {
				best = i;
			}
		}
		return best < 0 ? null : SLOT_NAMES[best];
	}

	private static void rememberWorstFrame(long frame, long millis, @Nullable String where) {
		// 榜是有界的：只留最坏的 8 条（插入排序，8 条不值得更聪明）
		for (int i = 0; i < WORST_FRAMES; i++) {
			if (millis > worstMillis[i]) {
				for (int j = WORST_FRAMES - 1; j > i; j--) {
					worstMillis[j] = worstMillis[j - 1];
					worstFrameNumber[j] = worstFrameNumber[j - 1];
					worstWhere[j] = worstWhere[j - 1];
				}
				worstMillis[i] = millis;
				worstFrameNumber[i] = frame;
				worstWhere[i] = where;
				return;
			}
		}
	}

	private static void reportIfDue(long nowNanos) {
		final long nowMillis = System.currentTimeMillis();
		if (windowStartMillis == 0L) {
			windowStartMillis = nowMillis;
			return;
		}
		if (nowMillis - windowStartMillis < REPORT_INTERVAL_MILLIS) {
			return;
		}
		final long elapsed = nowMillis - windowStartMillis;
		try {
			Init.LOGGER.info(buildReport(elapsed));
		} catch (Exception exception) {
			// 探针自己的格式化错误绝不该中断渲染线程
			Init.LOGGER.error("[MMTR-FRAME] 汇总行格式化失败", exception);
		}
		resetWindow(nowMillis);
	}

	private static String buildReport(long elapsedMillis) {
		final StringBuilder builder = new StringBuilder(1024);
		final double elapsedSeconds = elapsedMillis / 1000.0;
		final double fps = windowFrames <= 0 ? 0 : windowFrames / elapsedSeconds;
		/*
		 * **不是**"帧时" —— 它是**本探针埋了点的那些段**在一个视觉帧里的合计。
		 * 与 p50 帧间隔一比就是覆盖率：≈1 ⇒ 帧时基本被这些段解释完了；
		 * 明显偏小 ⇒ 时间花在没埋点的代码、别的线程、或 GC/呈现上（那就该转 F3+L 的分析器）。
		 */
		final double measuredPerFrameMillis = windowFrames <= 0 ? 0 : (windowMainPassNanos + windowShadowPassNanos) / 1_000_000.0 / windowFrames;

		builder.append(String.format("[MMTR-FRAME] %.1fs 窗口：帧=%d（%.1f FPS）帧间隔 p50=%dms p95=%dms 最慢=%dms ｜ 已量段合计=%.2fms/帧",
				elapsedSeconds, windowFrames, fps, percentile(50), percentile(95), maxFrameIntervalMillis, measuredPerFrameMillis));

		// ★ 通行/帧：>1 就说明阴影 pass 也在跑（见类注释）。
		final double passesPerFrame = windowFrames <= 0 ? 0 : (windowMainPasses + windowShadowPasses) / (double) windowFrames;
		builder.append(String.format(" ｜ 通行=%.2f/帧（阴影 %d + 主 %d）", passesPerFrame, windowShadowPasses, windowMainPasses));
		if (windowMainPasses > 0) {
			builder.append(String.format(" ｜ 阴影pass avg=%.2fms ｜ 主pass avg=%.2fms",
					windowShadowPassNanos / 1_000_000.0 / Math.max(1, windowShadowPasses),
					windowMainPassNanos / 1_000_000.0 / Math.max(1, windowMainPasses)));
		}

		builder.append("\n  ｜ 分段（累计ms/次数 max=单次最坏ms，按累计耗时排序）—— ");
		builder.append(describeSections());

		// 引擎侧"兜底近似轨"计数（-Dmmtr.pathdata.probe=true 才有值）。这一行解释的是**没被本探针埋点
		// 的引擎内部成本**：车辆每帧每转向架都要求一次位置，而客户端 positionsToRail 里没有该路径的轨。
		final String pathProbe = PathData.mmtrPathProbeReportAndReset();
		if (pathProbe != null) {
			builder.append("\n  ｜ ").append(pathProbe);
		}

		// "算过多少次整列车的位置"（引擎计数，一直在数）。它是**与场景无关**的那一格：只要还有调用点
		// 需要位置就会涨；把"只要车节 id"的调用点摘出去（notes/405）之后这一格会明显掉下来。
		builder.append(String.format("\n  ｜ 车辆位置查询：%d 次（Vehicle.getVehicleCarsAndPositions）", Vehicle.mmtrCarPositionCallsAndReset()));

		final String worst = describeWorstFrames();
		if (!worst.isEmpty()) {
			builder.append("\n  ｜ 最坏帧（#帧号=耗时ms(最重段)）—— ").append(worst);
		}

		builder.append("\n  ｜ 说明：通行=2 表示一个视觉帧被渲染了两次（光影包的阴影 pass）。")
				.append("**阴影 pass 与分辨率无关，但这不是「分辨率调低也没用」的主因** —— ")
				.append("主因是逐 draw 的 CPU 成本（与 draw 数走、与像素数无关）：对照 [MMTR-LIGHT] 的 ")
				.append("draws/frame ÷ batches/frame。**别据此去调阴影距离**（2026-10-05 走过这个弯路）。")
				.append("｜ 已量段合计 ÷ 帧间隔 p50 ≈ 1 ⇒ 帧时基本被表中的段解释完了；")
				.append("明显偏小 ⇒ 时间在没埋点的代码、别的线程（区块构建/遮挡剔除）或 GC/呈现上 —— 这时才该转 F3+L 的分析器。");
		return builder.toString();
	}

	/** 只打累计最重的 N 段（有界输出，见类注释的第三条约束）。 */
	private static String describeSections() {
		final List<Integer> order = new ArrayList<>(SLOT_COUNT);
		for (int i = 0; i < SLOT_COUNT; i++) {
			if (slotCount[i] > 0) {
				order.add(i);
			}
		}
		order.sort((a, b) -> Long.compare(slotTotalNanos[b], slotTotalNanos[a]));

		final StringBuilder builder = new StringBuilder(512);
		final int limit = Math.min(REPORT_TOP_SECTIONS, order.size());
		for (int i = 0; i < limit; i++) {
			final int slot = order.get(i);
			if (i > 0) {
				builder.append(" | ");
			}
			builder.append(SLOT_NAMES[slot])
					.append('=').append(String.format("%.0f", slotTotalNanos[slot] / 1_000_000.0)).append("ms/")
					.append(slotCount[slot])
					.append(" max=").append(String.format("%.2f", slotMaxNanos[slot] / 1_000_000.0));
		}
		if (order.size() > limit) {
			builder.append(" | …其余 ").append(order.size() - limit).append(" 段（累计更小，已折叠）");
		}
		if (order.isEmpty()) {
			builder.append("（本窗口没有量到任何段 —— 探针埋点是否被重构掉了？）");
		}
		return builder.toString();
	}

	private static String describeWorstFrames() {
		final StringBuilder builder = new StringBuilder(160);
		boolean any = false;
		for (int i = 0; i < WORST_FRAMES; i++) {
			if (worstMillis[i] <= 0) {
				continue;
			}
			if (any) {
				builder.append(' ');
			}
			builder.append('#').append(worstFrameNumber[i]).append('=').append(worstMillis[i]).append("ms");
			if (worstWhere[i] != null) {
				builder.append('(').append(worstWhere[i]).append(')');
			}
			any = true;
		}
		return builder.toString();
	}

	/** 帧间隔分位：只在报告那一拍排序（窗口内最多 {@link #FRAME_RING} 个样本）。 */
	private static long percentile(int percent) {
		if (frameIntervalCount <= 0) {
			return 0;
		}
		final long[] sorted = new long[frameIntervalCount];
		System.arraycopy(frameIntervals, 0, sorted, 0, frameIntervalCount);
		java.util.Arrays.sort(sorted);
		final int index = Math.min(frameIntervalCount - 1, Math.max(0, (int) Math.round(percent / 100.0 * (frameIntervalCount - 1))));
		return sorted[index];
	}

	private static void resetWindow(long nowMillis) {
		java.util.Arrays.fill(slotTotalNanos, 0L);
		java.util.Arrays.fill(slotCount, 0);
		java.util.Arrays.fill(slotMaxNanos, 0L);
		java.util.Arrays.fill(currentFrameSectionNanos, 0L);
		frameIntervalWrite = 0;
		frameIntervalCount = 0;
		maxFrameIntervalMillis = 0L;
		windowFrames = 0;
		windowMainPasses = 0;
		windowShadowPasses = 0;
		windowMainPassNanos = 0L;
		windowShadowPassNanos = 0L;
		for (int i = 0; i < WORST_FRAMES; i++) {
			worstMillis[i] = 0L;
			worstFrameNumber[i] = 0L;
			worstWhere[i] = null;
		}
		windowStartMillis = nowMillis;
	}
}
