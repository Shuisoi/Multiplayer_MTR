package org.mtr.core.mmtr.probe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MMTR 服务端性能探针内核（notes/337）—— <b>只记账，不改行为</b>。
 *
 * <h2>这份东西要回答什么</h2>
 * <p>现场症状已经很清楚（notes/335 §4、notes/336 §3）：地图长到 280–294 根轨 / 232–250 盏灯之后，
 * 服务端每 30–60 s 落后 20–40 s，严重时被看门狗直接杀掉。但<b>"是谁吃掉的"</b>此前只能靠两样东西：
 * 崩溃栈（只有崩了才有）与 JFR 采样（要停机、要人来读）。两者都不是常态手段 —— 于是每加一座站，
 * 都只能"感觉更卡了"。</p>
 *
 * <p>这一层把"是谁"变成**常驻、可开关、可点名**的数字。三条设计约束，每一条都对应一个踩过的坑：</p>
 *
 * <ol>
 *   <li><b>只记账</b>：探针的返回值一律不参与判断，不缓存、不短路、不跳过计算 —— 唯一的例外是
 *       {@link #on()} 这个开关本身。notes/328 §R 已经把"允许做的优化"钉死为**逐位相同**，
 *       所以探针绝不能变成"顺手优化"的入口。它测的是**现在这份实现**，不是一份理想化的实现。</li>
 *   <li><b>关掉就近似零成本</b>：默认关闭，唯一的开销是每个测量点一次 {@code volatile} 读
 *       （{@link #on()}）。{@code System.nanoTime()} 只在开着的时候才调，而且计时点都埋在一段
 *       工作的边界（一次投影、一个 tick 阶段），不是埋在循环体里。</li>
 *   <li><b>不许淹没日志</b>：输出是**窗口汇总**（一行里给出每段的总量/次数/最坏），
 *       外加一个**有界**的最坏帧环形缓冲（默认 12 条）。notes/77 的教训：诊断输出一旦每 tick 一行，
 *       真正要看的行就被埋掉了。</li>
 * </ol>
 *
 * <h2>怎么用</h2>
 * <pre>
 *   -Dmmtr.probe=true                     启动就开（默认 false）
 *   -Dmmtr.probe.interval=100             每多少 tick 打一行汇总（默认 100 = 5 s）
 *   -Dmmtr.probe.warnMs=40                单帧超过这么多毫秒就进最坏帧榜（默认 40，0 = 全记）
 *   -Dmmtr.probe.file=logs\perf-probe.txt 明细追加（默认不写文件）
 *   运行中（网页指令栏 / 服务端控制台）：probe on | off | dump | reset
 * </pre>
 *
 * <p>属性名与 {@code MmtrTrace} 同一套口径（{@code -Dmmtr.trace=true} + {@code trace on}），
 * 免得又多记一套开关。</p>
 *
 * <h2>读数怎么看</h2>
 * <pre>
 *   [MMTR-PROBE] sim minecraft/overworld ticks=100 wall=100104ms busy=37ms avg=0.37ms p50=0 p95=1ms max=6ms over63ms=0
 *   [MMTR-PROBE] 分段累计毫秒/次数 —— projection.solve=41ms/8200 max=3ms | projection.hit=19200 ev=0 | sections.rebuild=31ms/3 | |
 *   [MMTR-PROBE] 最坏帧（tick#=耗时）—— #18422=40685ms(world.fill×1108)
 * </pre>
 * <p>判据三条：</p>
 * <ol>
 *   <li>{@code 累计毫秒} 最大的那一段就是嫌疑（同一窗口内的段可以直接横向比）；</li>
 *   <li>{@code 次数} 异常高说明是"每 tick 全量重算"式的**放大** —— 次数 ÷ tick 数 = 每 tick 调了几次，
 *       例如 {@code projection.solve} 每 tick 上万次、{@code sections.signatureCheck} 每 tick 几百次；</li>
 *   <li>{@code max} 与平均差得远 ⇒ **尖峰**（施工、区块生成、存档），不是稳态；
 *       {@code ev}（事件）与 {@code peak} 用来读缓存命中/清空、队列深度这类"次数本身就是结论"的量。</li>
 * </ol>
 *
 * <h2>多帧（一个服务端可以同时有两个时钟）</h2>
 * <p>嵌入式运行时有两个"帧"在同一个线程上交替：引擎的**一次模拟 tick**（50 ms 一步）与
 * MC 的**一次服务端 tick**（20 TPS）。两者不是一回事（一次服务端 tick 里可能跑 0…N 次模拟 tick，
 * 掉帧时还会追帧），混在一张表里两边都读不懂。所以帧预算按<b>帧槽</b>分开：
 * {@link #frameBegin(int)} / {@link #frameEnd(int)} 传槽号，{@code 0} = 引擎 tick，{@code 1} = 服务端 tick。
 * 分段计时**共用**一张表（同一批测量点，两边都想知道），只有帧统计按槽分开。</p>
 */
public final class MmtrProbe {

	/** 全局开关：只读这个字段的代价是一次 volatile 读。 */
	private static volatile boolean enabled = Boolean.getBoolean("mmtr.probe");

	/** 每多少 tick 打一行窗口汇总（{@code -Dmmtr.probe.interval}）。 */
	private static volatile int reportIntervalTicks = Integer.getInteger("mmtr.probe.interval", 100);

	/** 单帧超过这么多毫秒就进最坏帧榜（{@code -Dmmtr.probe.warnMs}；0 = 全记）。 */
	private static volatile long warnMillis = Long.getLong("mmtr.probe.warnMs", 40L);

	/** 明细落文件（{@code -Dmmtr.probe.file}）；空 = 不写文件。 */
	private static volatile String filePath = System.getProperty("mmtr.probe.file", "");

	/** 最坏帧榜的容量：有界，免得"诊断本身"变成内存泄漏。 */
	private static final int WORST_FRAMES = 12;

	/** 帧槽数：0 = 引擎模拟 tick，1 = MC 服务端 tick。 */
	public static final int FRAME_SIM = 0;
	public static final int FRAME_SERVER = 1;
	private static final int FRAME_SLOTS = 2;

	/** 每个段（subsystem）的累加器。段名 → 计数。 */
	private static final Map<String, Counter> counters = new ConcurrentHashMap<>();

	/** 帧槽状态。 */
	private static final FrameState[] frames = new FrameState[FRAME_SLOTS];

	static {
		for (int i = 0; i < FRAME_SLOTS; i++) {
			frames[i] = new FrameState(i == FRAME_SIM ? "sim" : "server");
		}
	}

	private MmtrProbe() {
	}

	// ================================================================= 开关

	/** 探针是否开着。热路径上的第一个判断就是它。 */
	public static boolean on() {
		return enabled;
	}

	public static void setEnabled(boolean value) {
		enabled = value;
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static void setReportIntervalTicks(int ticks) {
		reportIntervalTicks = Math.max(1, ticks);
	}

	public static int getReportIntervalTicks() {
		return reportIntervalTicks;
	}

	public static void setWarnMillis(long millis) {
		warnMillis = Math.max(0L, millis);
	}

	public static long getWarnMillis() {
		return warnMillis;
	}

	/** 明细落文件的目标。设 null/空 = 关掉文件输出。 */
	public static void setFile(@org.jspecify.annotations.Nullable String path) {
		filePath = path == null ? "" : path;
	}

	public static String getFile() {
		return filePath;
	}

	// ================================================================= 测量点

	/**
	 * 开始量一段。用法固定成 try/finally，异常路径也要记账（tick 里 catch 住异常之后，
	 * "这一 tick 花在哪"仍然必须可读）：
	 *
	 * <pre>
	 *   final long t = MmtrProbe.begin();
	 *   try {
	 *       work();
	 *   } finally {
	 *       MmtrProbe.end("sections.rebuild", t);
	 *   }
	 * </pre>
	 *
	 * <p>返回 {@code 0} 时 {@link #end} 直接返回。调用方**不许**拿返回值去决定要不要干活 ——
	 * 它只表示"要不要计时"，不是"要不要做"。</p>
	 */
	public static long begin() {
		return enabled ? System.nanoTime() : 0L;
	}

	/** 结束一段并记账；{@code startedAtNanos} 为 0（探针关着）时什么都不做。 */
	public static void end(String section, long startedAtNanos) {
		if (startedAtNanos == 0L) {
			return;
		}
		add(section, System.nanoTime() - startedAtNanos);
	}

	/** 直接记一笔耗时（纳秒）。 */
	public static void add(String section, long nanos) {
		if (!enabled) {
			return;
		}
		counters.computeIfAbsent(section, Counter::new).add(Math.max(0L, nanos));
	}

	/** 记一次事件（不耗时）：缓存命中/清空、重建次数、跳过次数这类"次数本身就是结论"的量。 */
	public static void hit(String section) {
		if (!enabled) {
			return;
		}
		counters.computeIfAbsent(section, Counter::new).hit();
	}

	/** 记一次事件并带上一个数值（例如"清掉了多少条"）。 */
	public static void event(String section, long value) {
		if (!enabled) {
			return;
		}
		counters.computeIfAbsent(section, Counter::new).event(value);
	}

	/** 记一个"瞬间值"：不累加，只留最大值（例如队列深度、缓存条目数）。 */
	public static void peak(String section, long value) {
		if (!enabled) {
			return;
		}
		counters.computeIfAbsent(section, Counter::new).peak(value);
	}

	// ================================================================= 帧（tick）

	/** 一帧开始（帧槽 {@link #FRAME_SIM}）。 */
	public static void frameBegin() {
		frameBegin(FRAME_SIM);
	}

	/** 一帧开始。{@code slot} 见 {@link #FRAME_SIM} / {@link #FRAME_SERVER}。 */
	public static void frameBegin(int slot) {
		if (enabled && slot >= 0 && slot < FRAME_SLOTS) {
			frames[slot].begin();
		}
	}

	/** 一帧结束（帧槽 {@link #FRAME_SIM}）。 */
	public static void frameEnd() {
		frameEnd(FRAME_SIM);
	}

	/** 一帧结束：把本帧耗时计入窗口与累计、更新直方图、必要时进最坏帧榜。 */
	public static void frameEnd(int slot) {
		if (enabled && slot >= 0 && slot < FRAME_SLOTS) {
			frames[slot].end();
		}
	}

	/** 某一帧槽这一窗口跑了多少帧（判断"该不该报"用）。 */
	public static long frameCount(int slot) {
		return slot >= 0 && slot < FRAME_SLOTS ? frames[slot].ticks.get() : 0L;
	}

	/**
	 * 给"下一次收帧"挂一个标签，例如 {@code "world.fill×1108"}、{@code "server.save"}。
	 *
	 * <p>用它的场合：**不是**引擎算出来的慢，而是外面塞进来的活（施工期上千条 fill、存档、
	 * 区块生成）。事后只看"哪个段慢"是查不出来的 —— 那一段根本没跑，是整个世界在忙。
	 * 于是最坏帧榜里带上标签，才能一眼分开"引擎的锅"与"施工/原版的锅"。</p>
	 */
	public static void hint(String label) {
		if (enabled && label != null && !label.isEmpty()) {
			frames[FRAME_SERVER].hint(label);
		}
	}

	// ================================================================= 输出

	/**
	 * 到点了吗（按引擎帧数）；到点就顺手把窗口汇总打出去并归零。
	 *
	 * <p>调用方在 tick 末尾，所以这里**不许**做重活：输出行数有界（每槽 3 行 + 最多 24 个段）。</p>
	 *
	 * @param label 哪个模拟器（一个服务端可以有多个维度），只用于输出
	 * @return 本次是否真的打了
	 */
	public static boolean reportIfDue(String label) {
		if (!enabled) {
			return false;
		}
		final FrameState sim = frames[FRAME_SIM];
		if (sim.ticks.get() < reportIntervalTicks) {
			return false;
		}
		report(label);
		return true;
	}

	/**
	 * 打窗口汇总并归零窗口（累计量不动）。{@link #reportIfDue} 与 {@code probe dump} 都走它。
	 *
	 * @return 打出去的那几行（原样，方便调用方转发到网页指令栏）
	 */
	public static String report(String label) {
		final List<String> lines;
		synchronized (MmtrProbe.class) {
			lines = summaryLines(label);
			resetWindowLocked();
		}
		for (final String line : lines) {
			System.out.println(line);
		}
		appendToFile(lines);
		return String.join("\n", lines);
	}

	/** 当前窗口的汇总（人读；不改状态）。 */
	public static synchronized List<String> summaryLines(String label) {
		final List<String> out = new ArrayList<>();
		for (final FrameState frame : frames) {
			final long ticks = frame.ticks.get();
			if (ticks == 0 && frame.busyNanos == 0L) {
				continue;
			}
			final long spanMillis = frame.lastNanos == 0L || frame.firstNanos == 0L ? 0L : (frame.lastNanos - frame.firstNanos) / 1_000_000L;
			/*
			 * 桶边界 = warnMillis（默认 40 ms ≈ 两个 tick）。为什么不写死 63：
			 * 桶边界就是判据 "overXXms" 的名字，而 warnMillis 是同一份口径 —— 写死会让"门槛 20 ms"
			 * 这种调整在输出里对不上号（第一版就是这样，`over63ms` 印在门槛 40 的行里）。
			 */
			final long warnBucket = Math.max(0L, Math.min(FrameState.HISTOGRAM_BUCKETS - 1L, warnMillis));
			/*
			 * **一律带微秒读数**（2026-09-27 现场实测的教训）。
			 *
			 * <p>第一版只印整毫秒，于是服务端帧那几行成了 `n=34 busy=0ms avg=0.00ms` ——
			 * 这不是"没有开销"，而是**每帧不足 0.5 ms 被取整抹平**。而它偏偏看起来像一条结论
			 * （"钩子不花时间"），当场就能把归因带偏。整毫秒只适合"一直很慢"的病；抓
			 * "每 tick 花几百微秒 × 几千 tick"这种**累积型**的病必须有微秒位。</p>
			 */
			out.add(String.format(Locale.ROOT,
				"[MMTR-PROBE] %s frame=%s n=%d wall=%dms busy=%d.%03dms avg=%.3fms p50=%dms p95=%dms p99=%dms max=%dms over%dms=%d",
				label, frame.name, ticks, Math.max(0L, spanMillis),
				frame.busyNanos / 1_000_000L, (frame.busyNanos / 1_000L) % 1_000L,
				ticks == 0 ? 0.0 : (double) frame.busyNanos / 1_000_000.0 / ticks,
				frame.percentileMillis(0.50), frame.percentileMillis(0.95), frame.percentileMillis(0.99),
				frame.maxNanos / 1_000_000L, warnBucket, frame.countOver(warnBucket)));
		}

		final List<Counter> active = new ArrayList<>(counters.values());
		active.removeIf(counter -> counter.totalNanos.get() == 0L && counter.calls.get() == 0L && counter.events.get() == 0L);
		active.sort(Comparator.comparingLong((Counter counter) -> counter.totalNanos.get()).reversed());
		if (!active.isEmpty()) {
			final StringBuilder body = new StringBuilder();
			int shown = 0;
			for (final Counter counter : active) {
				/*
				 * 现场 36 段，而这一行只列 24 —— 被折叠掉的恰恰可能是"均耗时不大但每 tick 都走"的那几段
				 * （也正是累积型开销的藏身处）。所以折叠时**把剩下的名字也列出来**（只列名字，不带数字）。
				 */
				if (shown++ >= 24) {
					final StringBuilder rest = new StringBuilder();
					int restCount = 0;
					for (int i = shown - 1; i < active.size(); i++) {
						rest.append(restCount++ == 0 ? "" : ",").append(active.get(i).name);
					}
					body.append(" …(还有 ").append(restCount).append(" 段未列数字：").append(rest).append(")");
					break;
				}
				body.append(' ').append(counter.describe()).append(" |");
			}
			out.add("[MMTR-PROBE] 分段（累计毫秒/次数，按累计耗时排序）——" + body);
		}

		synchronized (worstFrames) {
			if (!worstFrames.isEmpty()) {
				final List<FrameRecord> sorted = new ArrayList<>(worstFrames);
				sorted.sort(Comparator.comparingLong(FrameRecord::nanos).reversed());
				final StringBuilder worst = new StringBuilder();
				for (final FrameRecord record : sorted) {
					worst.append(" #").append(record.tick()).append('=').append(record.nanos() / 1_000_000L).append("ms");
					if (!record.hint().isEmpty()) {
						worst.append('(').append(record.hint()).append(')');
					}
				}
				out.add("[MMTR-PROBE] 最坏帧（tick#=耗时）——" + worst);
			}
		}
		return out;
	}

	/**
	 * 让游戏端补印自己的那几行。
	 *
	 * <p>为什么要这个钩子：{@code probe dump} 是从**网页指令栏**发出来的，它跑在引擎/游戏端的指令出口上，
	 * 而游戏端有一组自己的测量点（{@code server.*}、{@code world.*}）只有游戏端知道该怎么解释
	 * （例如"已加载区块"要遍历世界才数得出来）。这里留一个单槽回调，游戏端注册一次即可 ——
	 * 引擎侧不反过来依赖游戏端的类。</p>
	 */
	private static volatile java.util.function.@org.jspecify.annotations.Nullable Supplier<List<String>> extraReporter;

	public static void setExtraReporter(java.util.function.@org.jspecify.annotations.Nullable Supplier<List<String>> reporter) {
		extraReporter = reporter;
	}

	/** 当前窗口的汇总 + 累计 + 注册进来的补充行（人读；不改状态）。{@code probe dump} 用它。 */
	public static synchronized String fullReport(String label) {
		final List<String> lines = new ArrayList<>(summaryLines(label));
		lines.add(totalsLine(label));
		final java.util.function.Supplier<List<String>> reporter = extraReporter;
		if (reporter != null) {
			try {
				final List<String> extra = reporter.get();
				if (extra != null) {
					lines.addAll(extra);
				}
			} catch (Throwable e) {
				lines.add("[MMTR-PROBE] 补充读数失败: " + e);
			}
		}
		return String.join("\n", lines);
	}

	/** 累计总量（不归零）：回答"开机到现在一共花了多少"。 */
	public static synchronized String totalsLine(String label) {
		final StringBuilder text = new StringBuilder("[MMTR-PROBE] ").append(label).append(" 累计");
		for (final FrameState frame : frames) {
			if (frame.totalTicks.get() > 0) {
				text.append(' ').append(frame.name).append("Ticks=").append(frame.totalTicks.get())
					.append(' ').append(frame.name).append("Busy=").append(frame.totalBusyNanos.get() / 1_000_000L).append("ms")
					.append(' ').append(frame.name).append("Max=").append(frame.totalMaxNanos.get() / 1_000_000L).append("ms");
			}
		}
		return text.append(" 段数=").append(counters.size()).toString();
	}

	/** 归零窗口（累计不动）。 */
	public static synchronized void resetWindow() {
		resetWindowLocked();
	}

	private static void resetWindowLocked() {
		for (final FrameState frame : frames) {
			frame.resetWindow();
		}
		synchronized (worstFrames) {
			worstFrames.clear();
		}
	}

	/** 全清（窗口 + 累计 + 直方图 + 段表），换世界或做前后对照实验时用。 */
	public static synchronized void resetAll() {
		resetWindowLocked();
		for (final FrameState frame : frames) {
			frame.resetTotals();
		}
		counters.clear();
	}

	// ================================================================= 内部

	private static void appendToFile(List<String> lines) {
		final String path = filePath;
		if (path == null || path.isEmpty()) {
			return;
		}
		try {
			final Path target = Path.of(path);
			final Path parent = target.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			final StringBuilder text = new StringBuilder();
			for (final String line : lines) {
				text.append(line).append(System.lineSeparator());
			}
			Files.writeString(target, text.toString(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			// 写不进去不许影响 tick：把文件输出关掉，免得每窗口都抛一次。
			filePath = "";
			System.out.println("[MMTR-PROBE] 明细文件写不进去，已关闭文件输出: " + e.getMessage());
		}
	}

	/** 一个帧槽的窗口 + 累计统计。**只有它自己的线程碰它**（引擎 tick 在模拟线程、服务端 tick 在主线程）。 */
	private static final class FrameState {

		static final int HISTOGRAM_BUCKETS = 64;

		private final String name;
		private final AtomicLong ticks = new AtomicLong();
		private final AtomicLong totalTicks = new AtomicLong();
		private final AtomicLong totalBusyNanos = new AtomicLong();
		private final AtomicLong totalMaxNanos = new AtomicLong();
		private final int[] histogram = new int[HISTOGRAM_BUCKETS];
		private final List<String> pendingHints = new ArrayList<>(4);

		private long startNanos;
		private long firstNanos;
		private long lastNanos;
		private long busyNanos;
		private long maxNanos;

		private FrameState(String name) {
			this.name = name;
		}

		private void begin() {
			startNanos = System.nanoTime();
			if (firstNanos == 0L) {
				firstNanos = startNanos;
			}
		}

		private void end() {
			final long now = System.nanoTime();
			final long nanos = startNanos == 0L ? 0L : Math.max(0L, now - startNanos);
			startNanos = 0L;
			lastNanos = now;
			busyNanos += nanos;
			if (nanos > maxNanos) {
				maxNanos = nanos;
			}
			if (nanos > totalMaxNanos.get()) {
				totalMaxNanos.set(nanos);
			}
			totalBusyNanos.addAndGet(nanos);
			histogram[(int) Math.min(HISTOGRAM_BUCKETS - 1, nanos / 1_000_000L)]++;
			final long tick = totalTicks.incrementAndGet();
			ticks.incrementAndGet();
			if (nanos >= warnMillis * 1_000_000L) {
				recordWorstFrame(new FrameRecord(name, tick, nanos, takeHints()));
			}
		}

		private void hint(String label) {
			if (pendingHints.size() < 4) {
				pendingHints.add(label);
			}
		}

		private String takeHints() {
			if (pendingHints.isEmpty()) {
				return "";
			}
			final String joined = String.join(",", pendingHints);
			pendingHints.clear();
			return joined;
		}

		private void resetWindow() {
			ticks.set(0);
			firstNanos = 0L;
			lastNanos = 0L;
			busyNanos = 0L;
			maxNanos = 0L;
			Arrays.fill(histogram, 0);
			pendingHints.clear();
		}

		private void resetTotals() {
			totalTicks.set(0);
			totalBusyNanos.set(0);
			totalMaxNanos.set(0);
		}

		private long percentileMillis(double quantile) {
			final long total = ticks.get();
			if (total == 0) {
				return 0L;
			}
			final long target = Math.max(1L, (long) Math.ceil(quantile * total));
			long seen = 0;
			for (int bucket = 0; bucket < HISTOGRAM_BUCKETS; bucket++) {
				seen += histogram[bucket];
				if (seen >= target) {
					return bucket;
				}
			}
			return HISTOGRAM_BUCKETS - 1;
		}

		private long countOver(long millis) {
			long count = 0;
			for (int bucket = (int) Math.max(0L, Math.min(HISTOGRAM_BUCKETS - 1, millis)); bucket < HISTOGRAM_BUCKETS; bucket++) {
				count += histogram[bucket];
			}
			return count;
		}
	}

	private static final List<FrameRecord> worstFrames = new ArrayList<>();

	private static void recordWorstFrame(FrameRecord record) {
		synchronized (worstFrames) {
			worstFrames.add(record);
			if (worstFrames.size() > WORST_FRAMES) {
				worstFrames.sort(Comparator.comparingLong(FrameRecord::nanos));
				worstFrames.remove(0);
			}
		}
	}

	/** 一个测量段的累加器（线程安全：网页线程也会读）。 */
	private static final class Counter {

		private final String name;
		private final AtomicLong calls = new AtomicLong();
		private final AtomicLong totalNanos = new AtomicLong();
		private final AtomicLong maxMillis = new AtomicLong();
		private final AtomicLong events = new AtomicLong();
		private final AtomicLong eventSum = new AtomicLong();
		private final AtomicLong peak = new AtomicLong(Long.MIN_VALUE);

		private Counter(String name) {
			this.name = name;
		}

		private void add(long nanos) {
			calls.incrementAndGet();
			totalNanos.addAndGet(nanos);
			maxMillis.accumulateAndGet(nanos / 1_000_000L, Math::max);
		}

		private void hit() {
			events.incrementAndGet();
		}

		private void event(long value) {
			events.incrementAndGet();
			eventSum.addAndGet(value);
		}

		private void peak(long value) {
			peak.accumulateAndGet(value, Math::max);
		}

		private String describe() {
			/*
			 * 微秒位同样是**现场教出来的**：`server.mirror.sectionBands=2033ms/11031` 看着"才 2 秒"，
			 * 实际是 0.18 ms/次 —— 而它每 tick 都走一次，累积起来就是 2 秒。整毫秒会把
			 * 0.18 ms 印成 `0ms/11031`（看起来免费），于是"哪一段真贵"就判错了。
			 */
			final StringBuilder text = new StringBuilder(name)
				.append('=').append(totalNanos.get() / 1_000_000L).append('.').append(String.format(Locale.ROOT, "%03d", (totalNanos.get() / 1_000L) % 1_000L))
				.append("ms/").append(calls.get());
			final long avgMicros = calls.get() == 0 ? 0 : totalNanos.get() / 1_000L / calls.get();
			if (avgMicros > 0) {
				text.append(" 均").append(avgMicros).append("µs");
			}
			if (maxMillis.get() > 0) {
				text.append(" max=").append(maxMillis.get()).append("ms");
			}
			if (events.get() > 0) {
				text.append(" ev=").append(events.get());
				if (eventSum.get() != 0) {
					text.append("(Σ").append(eventSum.get()).append(')');
				}
			}
			if (peak.get() != Long.MIN_VALUE) {
				text.append(" peak=").append(peak.get());
			}
			return text.toString();
		}
	}

	/** 一帧的最坏记录。{@code hint} 是外面塞进来的活（施工/存档），用来分开"引擎的锅"与"外面的锅"。 */
	private record FrameRecord(String frame, long tick, long nanos, String hint) {
	}
}
