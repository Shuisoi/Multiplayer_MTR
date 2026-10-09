package org.mtr.mod.mmtr;

import org.mtr.mapping.holder.ClientWorld;
import org.mtr.mapping.holder.WorldChunk;
import org.mtr.mapping.registry.EventRegistryClient;
import org.mtr.mod.Init;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端**动态项加载管线**的探针 + 记账（notes/410）。
 *
 * <h2>为什么有它</h2>
 *
 * <p>现场症状：客户端列车经过带信号灯/道岔的节点时会**卡一下**（实机读数见下），而
 * {@code [MMTR-FRAME]} 里那些埋了点的段（钢轨、车辆、队列…）合计只有 ~6 ms/帧 ——
 * 也就是说卡顿**不在渲染段里**，而在"数据/区块到达客户端并被落地"这条路上，而这条路
 * 原来一行读数都没有：</p>
 *
 * <pre>
 *   [22:23:05] [MMTR-LAG] 客户端 tick 间隔 314ms（正常 50ms）
 *   [22:23:05] [MMTR-FRAME] 帧间隔 p50=16ms p95=31ms 最慢=163ms ｜ 已量段合计=5.31ms/帧
 * </pre>
 *
 * <p>本类只回答一个问题：<b>这一拍的时间被谁花掉了</b>。它把客户端 tick 里三条会一口气
 * 吃掉几百毫秒的路各记一笔：</p>
 *
 * <ol>
 *   <li><b>区块载入</b>（{@link EventRegistryClient#registerChunkLoad}）—— 只数个数：区块的
 *       解析/光照/建模在 vanilla 里，我们插不进计时点，但"这一拍新进了几个区块"是能数的，
 *       而"一 tick 进来七八个区块"本身就是噪声；</li>
 *   <li><b>包落地</b>（{@link org.mtr.mod.packet.PacketRequestResponseBase#runClient()}）——
 *       <b>解析</b>整份 JSON 与<b>写进客户端数据</b>分开计时。这一条是重的：一条
 *       {@code PacketRequestData} 落地就是 {@code DataResponse.write()} → {@code data.sync()}
 *       （全量重建 positionsToRail / 各 id 映射 / 站台与站关系），而搬运它的
 *       {@code Utilities.parseJson(content)} 也在同一个线程上；</li>
 *   <li><b>帧间隔</b> 由 {@code [MMTR-FRAME]} 给，本类不重复。</li>
 * </ol>
 *
 * <h2>怎么读（判据）</h2>
 *
 * <table>
 *   <tr><td>{@code 长 tick N ms：区块载入=a ｜ 包 b 次 X ms（…）｜ 其余≈Y ms}</td>
 *       <td>三块里哪一块吃掉了这 N ms 一眼可见。{@code 其余}大 ⇒ 时间在 vanilla 的区块落地、
 *       实体生成、GC 或别的线程上（那就不是本管线能修的，得换探针）。</td></tr>
 *   <tr><td>{@code 包应用=… 明细：PacketRequestData=3×110ms(max 80, 96KB)}</td>
 *       <td>次数 × 单次最大 + 字节数：字节大而次数少 ⇒ <b>突发</b>（该切片）；
 *       次数多而每次小 ⇒ 稳态成本（该减频次）。</td></tr>
 *   <tr><td>{@code 区块载入最长一 tick n 个}</td><td>n ≥ 4 就说明"区块是一串一串进来的"，
 *       与 {@code [MMTR-LAG]} 的 ~3 秒节拍对照即可确认它是不是卡顿的引信。</td></tr>
 * </table>
 *
 * <h2>纪律</h2>
 * <ul>
 *   <li><b>只记账，不改行为</b>：不参与任何判断，不改变任何调用顺序；</li>
 *   <li><b>关掉近似零成本</b>：{@code -Dmmtr.load=0}，之后每次调用只剩一次 volatile 读；</li>
 *   <li><b>输出有界</b>：每 5 秒一行汇总 + 每窗口最多 {@value #MAX_LONG_TICK_LINES} 行长 tick
 *       明细 + 每行最多 {@value #MAX_KINDS} 个包名。</li>
 * </ul>
 *
 * <p>线程：客户端 tick 与包落地都在客户端主线程（MC 的客户端 tick 就在渲染线程上），所以这里
 * 用普通静态字段即可 —— 与 {@code MmtrFrameProbe} 同一条纪律。</p>
 */
public final class MmtrLoadProbe {

	/** 关掉用 {@code -Dmmtr.load=0}（与其它客户端遥测同一条开关纪律）。 */
	private static final boolean ENABLED = !"0".equals(System.getProperty("mmtr.load", "1").trim());

	private static final long WINDOW_MILLIS = 5_000L;
	/** 超过这个毫秒数的 tick 进"长 tick"明细（现场{@code [MMTR-LAG]}的门槛是 150ms，这里更早一步）。 */
	private static final long LONG_TICK_MILLIS = 100L;
	private static final int MAX_LONG_TICK_LINES = 12;
	private static final int MAX_KINDS = 8;

	/** 一个包种类的账：{次数, 解析纳秒, 落地纳秒, 单次最坏纳秒, 字节}。 */
	private static final int KIND_COUNT = 0;
	private static final int KIND_PARSE = 1;
	private static final int KIND_APPLY = 2;
	private static final int KIND_MAX = 3;
	private static final int KIND_BYTES = 4;
	private static final int KIND_SLOTS = 5;

	// ---------------------------------------------------------------- 本 tick
	private static long tickStartNanos;
	private static long lastTickStartNanos;
	private static long currentTickIntervalMillis;
	private static int tickChunkLoads;
	private static int tickPackets;
	private static long tickPacketNanos;
	private static final Map<String, long[]> tickKinds = new HashMap<>();

	// ---------------------------------------------------------------- 本窗口
	private static long windowStartMillis;
	private static int windowTicks;
	private static long windowTickMaxMillis;
	private static int windowLongTicks;
	private static int windowChunkLoads;
	private static int windowChunkMaxInTick;
	private static int windowPackets;
	private static long windowPacketNanos;
	private static long windowPacketMaxNanos;
	private static long windowParseNanos;
	private static long windowParseMaxNanos;
	private static long windowBytes;
	private static int windowLongTickLines;
	private static long windowWorstTickMillis;
	private static String windowWorstTickDetail = "";
	private static final Map<String, long[]> windowKinds = new HashMap<>();
	/** 前视拉取（notes/410 §管线）：次数、前视距离之和、请求半径。 */
	private static int windowLookAheadCount;
	private static double windowLookAheadAheadSum;
	private static long windowLookAheadRadiusM;
	/**
	 * 客户端轨数的单次变动（notes/410 §事故）：落地一包数据前后各量一次。
	 *
	 * <p>{@code DataResponse.write()} 是**按窗口替换**（窗口外的轨全删），所以"客户端轨数掉了"就是
	 * "玩家眼里的轨没了"。修前那一版前视窗口圆心在车前方，这个数每次移动都会掉几十条；修好之后
	 * 窗口覆盖相机，它应当稳定为 0 掉。</p>
	 */
	private static int windowRailBefore = -1;
	private static int windowRailAfter = -1;
	private static int windowRailDropMax;
	private static int windowRailDropCount;

	private MmtrLoadProbe() {
	}

	public static boolean on() {
		return ENABLED;
	}

	/** 客户端事件挂载（在 {@code InitClient} 的注册块里调一次）。 */
	public static void register(EventRegistryClient registry) {
		if (!ENABLED) {
			return;
		}
		registry.registerChunkLoad((clientWorld, worldChunk) -> chunkLoad(clientWorld, worldChunk));
	}

	/** 客户端 tick 开始（{@code registerStartClientTick} 的第一行）。 */
	public static void tickStart() {
		if (!ENABLED) {
			return;
		}
		final long now = System.nanoTime();
		currentTickIntervalMillis = lastTickStartNanos == 0L ? 0L : (now - lastTickStartNanos) / 1_000_000L;
		lastTickStartNanos = now;
		tickStartNanos = now;
		tickChunkLoads = 0;
		tickPackets = 0;
		tickPacketNanos = 0L;
		tickKinds.clear();
	}

	/** 客户端 tick 结束（{@code registerEndClientTick} 的最后一行）。 */
	public static void tickEnd() {
		if (!ENABLED) {
			return;
		}
		final long millis = currentTickIntervalMillis;
		windowTicks++;
		if (millis > windowTickMaxMillis) {
			windowTickMaxMillis = millis;
		}
		if (tickChunkLoads > windowChunkMaxInTick) {
			windowChunkMaxInTick = tickChunkLoads;
		}
		windowPackets += tickPackets;
		windowPacketNanos += tickPacketNanos;
		mergeKinds(windowKinds, tickKinds);

		if (millis >= LONG_TICK_MILLIS) {
			windowLongTicks++;
			final String detail = describeTick(millis);
			if (millis > windowWorstTickMillis) {
				windowWorstTickMillis = millis;
				windowWorstTickDetail = detail;
			}
			if (windowLongTickLines < MAX_LONG_TICK_LINES) {
				windowLongTickLines++;
				Init.LOGGER.info("[MMTR-LOAD] {}", detail);
			}
		}

		reportIfDue();
	}

	/** 一个区块进了客户端（只数个数，见类注释）。 */
	public static void chunkLoad(ClientWorld clientWorld, WorldChunk worldChunk) {
		if (!ENABLED) {
			return;
		}
		tickChunkLoads++;
		windowChunkLoads++;
	}

	/**
	 * 一次**前视拉取**（{@link MmtrDynamicLoad}）：相机每走一段就主动向服务端要一次数据。
	 *
	 * <p>2026-10-09：圆心改成相机之后（见 {@code MmtrDynamicLoad} 类注释"圆心为什么必须是相机"），
	 * "提前量"不再是"圆心挪到车前方多远"，而是**这次请求窗口从相机往前覆盖多远** = 半径。记下来
	 * 是为了看清"提前量到底提前了多少"——数据在到达之前就落地，光看请求条数看不出这一点。</p>
	 *
	 * @param radiusM 这次请求的半径（米）＝窗口从相机往前覆盖的距离
	 */
	public static void lookAheadRequest(long radiusM) {
		if (!ENABLED) {
			return;
		}
		windowLookAheadCount++;
		windowLookAheadAheadSum += radiusM;
		windowLookAheadRadiusM = radiusM;
	}

	/**
	 * 一次数据包落地前后**客户端轨数**的变化（notes/410 §事故）。落地后比落地前少 = 有轨被这次响应
	 * 删掉了（{@code DataResponse} 是按窗口替换）。这是"玩家眼里的轨没了一条"的**唯一直接读数** ——
	 * 修前那版前视窗口每次移动都会让它掉几十条。
	 *
	 * @param before 落地前的客户端轨数
	 * @param after  落地后的客户端轨数
	 */
	public static void clientRailCount(int before, int after) {
		if (!ENABLED) {
			return;
		}
		windowRailBefore = before;
		windowRailAfter = after;
		if (after < before) {
			windowRailDropCount++;
			windowRailDropMax = Math.max(windowRailDropMax, before - after);
		}
	}

	/**
	 * 一条客户端包落地完了（在
	 * {@link org.mtr.mod.packet.PacketRequestResponseBase#runClient()} 里调）。
	 *
	 * @param kind        包类名（{@code PacketRequestData} / {@code PacketMmtrRoutes} …）
	 * @param bytes       线上 JSON 字节数（{@code content.length()}，字符数，够用）
	 * @param parseNanos  {@code Utilities.parseJson} 那一段
	 * @param applyNanos  {@code runClientInbound}（写进客户端数据）那一段
	 */
	public static void packetApplied(String kind, int bytes, long parseNanos, long applyNanos) {
		if (!ENABLED) {
			return;
		}
		final long total = parseNanos + applyNanos;
		tickPackets++;
		tickPacketNanos += total;
		if (total > windowPacketMaxNanos) {
			windowPacketMaxNanos = total;
		}
		windowParseNanos += parseNanos;
		if (parseNanos > windowParseMaxNanos) {
			windowParseMaxNanos = parseNanos;
		}
		windowBytes += bytes;
		final long[] tickKind = tickKinds.computeIfAbsent(kind, ignored -> new long[KIND_SLOTS]);
		addKind(tickKind, bytes, parseNanos, applyNanos, total);
	}

	/** 换世界（client join）时把窗口清掉，免得跨世界算平均。 */
	public static void resetWindow() {
		if (!ENABLED) {
			return;
		}
		windowStartMillis = 0L;
		windowTicks = 0;
		windowTickMaxMillis = 0L;
		windowLongTicks = 0;
		windowChunkLoads = 0;
		windowChunkMaxInTick = 0;
		windowPackets = 0;
		windowPacketNanos = 0L;
		windowPacketMaxNanos = 0L;
		windowParseNanos = 0L;
		windowParseMaxNanos = 0L;
		windowBytes = 0L;
		windowLongTickLines = 0;
		windowWorstTickMillis = 0L;
		windowWorstTickDetail = "";
		windowKinds.clear();
		windowLookAheadCount = 0;
		windowLookAheadAheadSum = 0;
		windowRailBefore = -1;
		windowRailAfter = -1;
		windowRailDropMax = 0;
		windowRailDropCount = 0;
		lastTickStartNanos = 0L;
	}

	/** 给调用方用的计时助手（关掉时返回 0，{@link #elapsed(long)} 见 0 直接返回 0）。 */
	public static long begin() {
		return ENABLED ? System.nanoTime() : 0L;
	}

	public static long elapsed(long startedAtNanos) {
		return startedAtNanos == 0L ? 0L : System.nanoTime() - startedAtNanos;
	}

	// ---------------------------------------------------------------- 格式化

	private static void addKind(long[] slots, int bytes, long parseNanos, long applyNanos, long total) {
		slots[KIND_COUNT]++;
		slots[KIND_PARSE] += parseNanos;
		slots[KIND_APPLY] += applyNanos;
		slots[KIND_BYTES] += bytes;
		if (total > slots[KIND_MAX]) {
			slots[KIND_MAX] = total;
		}
	}

	private static void mergeKinds(Map<String, long[]> target, Map<String, long[]> source) {
		source.forEach((kind, slots) -> addKind(target.computeIfAbsent(kind, ignored -> new long[KIND_SLOTS]),
				(int) slots[KIND_BYTES], slots[KIND_PARSE], slots[KIND_APPLY], slots[KIND_MAX]));
	}

	/** 本 tick 的账：区块载入几个、包几次共几毫秒、其余多少毫秒。 */
	private static String describeTick(long millis) {
		final StringBuilder builder = new StringBuilder(160);
		builder.append("长 tick ").append(millis).append("ms：区块载入=").append(tickChunkLoads);
		builder.append(" ｜ 包 ").append(tickPackets).append(" 次 ").append(ms(tickPacketNanos)).append("ms");
		if (tickPackets > 0) {
			builder.append("（").append(describeKinds(tickKinds, 4)).append("）");
			final long accounted = tickPacketNanos / 1_000_000L;
			builder.append(" 其余≈").append(Math.max(0L, millis - accounted)).append("ms");
		} else {
			builder.append(" 其余≈").append(millis).append("ms");
		}
		return builder.toString();
	}

	private static String describeKinds(Map<String, long[]> kinds, int limit) {
		final List<Map.Entry<String, long[]>> entries = new ArrayList<>(kinds.entrySet());
		entries.sort((left, right) -> Long.compare(cost(right.getValue()), cost(left.getValue())));
		final StringBuilder builder = new StringBuilder(96);
		for (int i = 0; i < Math.min(limit, entries.size()); i++) {
			if (i > 0) {
				builder.append(' ');
			}
			final String kind = entries.get(i).getKey();
			final long[] slots = entries.get(i).getValue();
			builder.append(kind).append('=').append(slots[KIND_COUNT]).append('×')
					.append(ms(cost(slots))).append("ms(max ").append(ms(slots[KIND_MAX]))
					.append(", 解析 ").append(ms(slots[KIND_PARSE]))
					.append(", ").append(kilobytes(slots[KIND_BYTES])).append(')');
		}
		if (entries.size() > limit) {
			builder.append(" …另 ").append(entries.size() - limit).append(" 种");
		}
		return builder.toString();
	}

	private static long cost(long[] slots) {
		return slots[KIND_PARSE] + slots[KIND_APPLY];
	}

	private static void reportIfDue() {
		final long nowMillis = System.currentTimeMillis();
		if (windowStartMillis == 0L) {
			windowStartMillis = nowMillis;
			return;
		}
		final long elapsed = nowMillis - windowStartMillis;
		if (elapsed < WINDOW_MILLIS) {
			return;
		}
		try {
			Init.LOGGER.info("[MMTR-LOAD] {}", report(elapsed));
		} catch (Exception exception) {
			Init.LOGGER.error("[MMTR-LOAD] 汇总行格式化失败", exception);
		}
		windowStartMillis = nowMillis;
		windowTicks = 0;
		windowTickMaxMillis = 0L;
		windowLongTicks = 0;
		windowChunkLoads = 0;
		windowChunkMaxInTick = 0;
		windowPackets = 0;
		windowPacketNanos = 0L;
		windowPacketMaxNanos = 0L;
		windowParseNanos = 0L;
		windowParseMaxNanos = 0L;
		windowBytes = 0L;
		windowLongTickLines = 0;
		windowWorstTickMillis = 0L;
		windowWorstTickDetail = "";
		windowKinds.clear();
		windowLookAheadCount = 0;
		windowLookAheadAheadSum = 0;
		windowRailBefore = -1;
		windowRailAfter = -1;
		windowRailDropMax = 0;
		windowRailDropCount = 0;
	}

	private static String report(long elapsedMillis) {
		final StringBuilder builder = new StringBuilder(512);
		builder.append(String.format("%.1fs 窗口：tick=%d 最长=%dms 超%dms=%d ｜ 区块载入=%d（最长一 tick %d 个）",
				elapsedMillis / 1000.0, windowTicks, windowTickMaxMillis, LONG_TICK_MILLIS, windowLongTicks,
				windowChunkLoads, windowChunkMaxInTick));
		builder.append(" ｜ 包落地=").append(windowPackets).append(" 次 共 ").append(ms(windowPacketNanos)).append("ms")
				.append("（最长 ").append(ms(windowPacketMaxNanos)).append("ms，解析 ").append(ms(windowParseNanos))
				.append("ms 最长 ").append(ms(windowParseMaxNanos)).append("ms，")
				.append(kilobytes(windowBytes)).append('）');
		builder.append(" ｜ 明细：");
		builder.append(windowKinds.isEmpty() ? "（本窗口没有包落地）" : describeKinds(windowKinds, MAX_KINDS));
		if (windowLookAheadCount > 0) {
			builder.append("\n  ｜ 前视拉取=").append(windowLookAheadCount).append(" 次（窗口半径 ").append(windowLookAheadRadiusM)
					.append("m，平均往前覆盖 ").append(String.format("%.0f", windowLookAheadAheadSum / windowLookAheadCount)).append("m）");
		}
		if (windowRailBefore >= 0) {
			builder.append("\n  ｜ 客户端轨数 ").append(windowRailBefore).append("→").append(windowRailAfter)
					.append("（本窗口跌 ").append(windowRailDropCount).append(" 次，最多 ")
					.append(windowRailDropMax == 0 ? "没掉" : "-" + windowRailDropMax + " 条").append("）");
		}
		if (windowWorstTickMillis >= LONG_TICK_MILLIS) {
			builder.append("\n  ｜ 本窗口最长的 tick：").append(windowWorstTickDetail);
		}
		return builder.toString();
	}

	private static long ms(long nanos) {
		return nanos / 1_000_000L;
	}

	private static String kilobytes(long bytes) {
		return String.format("%.1fKB", bytes / 1024.0);
	}
}
