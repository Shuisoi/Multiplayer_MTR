package org.mtr.core.servlet;

import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 只读接口的**快照发布器**（每个 Simulator 一份，notes/172）。
 *
 * <h2>它解决什么</h2>
 * <p>网页每刷新一次地图就要一份 JSON，而这一份 JSON 原来是在**请求里现算**的：请求被塞回模拟线程，
 * 于是"网页刷新"变成"游戏主线程在一次 tick 里多做几份重活"。64 人服务器上这是致命的 ——
 * tick 预算 50 ms，被吃掉 200 ms 就是四个 tick，全体玩家同时卡一下。</p>
 *
 * <p>现在改成：请求进来先看**已经发布的那一份**。够新就由 Jetty 线程直接写出去（对 tick 零开销）；
 * 过旧才让这一次请求回到模拟线程重算，算完的那一份**发布出来给后面所有请求共用**。</p>
 *
 * <h2>三条约定</h2>
 * <ol>
 *   <li><b>一份只算一次、所有标签共用</b>：同一路接口在 {@code maxAgeMillis} 窗口内的重复请求
 *       （多开几个控制台标签就是这种）读同一份，不再各算各的；</li>
 *   <li><b>没人看就不花钱</b>：没有任何请求时这里什么都不做 —— 没有定时重建，控制台关掉之后
 *       这一层对 tick 的开销是 0；</li>
 *   <li><b>过期也要能答</b>：已经有人去重算了，其余请求读"手上这一份"（最多旧一拍），
 *       而不是一起排队等 —— 所以 tick 越忙网页越旧，但**永远不会把 tick 拖得更慢**。</li>
 * </ol>
 *
 * <h2>一条发布/订阅语义的变化（写给调用方）</h2>
 * <p>快照接口的响应里 {@code currentTime} 是**这份数据的构建时刻**，不是响应时刻 ——
 * 因为它要能被多个请求复用（否则每条响应都不一样，"没变"就永远判不出来）。
 * 要精确的响应时刻看 HTTP 头 {@code X-MMTR-Snapshot-Age-Millis}（这份数据已经放了多久）。</p>
 *
 * <h2>线程约定</h2>
 * <ul>
 *   <li>{@link #publish(String, JsonObject)} —— **只能模拟线程**调（它读的是活的模拟状态）；</li>
 *   <li>{@link #serve(String, long)}、{@link Entry#responseBytes(Supplier)} —— **任意 Jetty 线程**；</li>
 *   <li>{@link #sweep()} —— 模拟线程（每若干 tick 一次）。</li>
 * </ul>
 */
public final class WebFeed {

	/** 已经有人去重算了，其余请求靠这份"旧一拍"顶着；超过这么久还没见新的，就当那次重算已经死了，自己再接手。 */
	private static final long PENDING_TIMEOUT_MILLIS = 2_000L;
	/** 这么久没有任何请求，就把已发布的快照丢掉（大 JSON 不该白占内存）。 */
	private static final long IDLE_EVICT_MILLIS = 300_000L;

	/** 接口名 → 当前发布的那一份。键只用接口名：这一层只有 `SystemMapServlet` 在用（见那里的 FEEDS 表）。 */
	private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
	/** 接口名 → "有人已经在重算"的起始时刻。 */
	private final ConcurrentHashMap<String, Long> pendingSince = new ConcurrentHashMap<>();
	/** 内容指纹的代次：**只有内容真的变了才 +1**，所以"世界没动"的时候网页能一路拿到 304。 */
	private final AtomicLong versionCounter = new AtomicLong();
	private final AtomicLong lastRequestAtMillis = new AtomicLong();
	private final AtomicLong servedFromSnapshot = new AtomicLong();
	private final AtomicLong published = new AtomicLong();

	/**
	 * 一次请求：能答就答，不能答就说"这一次由你去算"。
	 *
	 * @param endpoint      接口名
	 * @param maxAgeMillis  这份快照最多容忍多旧；超过就要有人重算（而重算只会有一个人去做）
	 * @return 可以立刻答出去的那一份；{@code null} = 调用方回落到模拟线程算这一次（算完请调 {@link #publish}）
	 */
	public @Nullable Entry serve(String endpoint, long maxAgeMillis) {
		final long currentMillis = System.currentTimeMillis();
		lastRequestAtMillis.set(currentMillis);

		final Entry entry = entries.get(endpoint);
		if (entry == null) {
			pendingSince.put(endpoint, currentMillis);
			return null;
		}
		if (currentMillis - entry.builtAtMillis <= maxAgeMillis) {
			servedFromSnapshot.incrementAndGet();
			return entry;
		}

		// 过期了：谁先把"我接手"立起来，谁就回落到模拟线程去重算；其余请求读手上这一份，不跟着排队。
		final Long since = pendingSince.get(endpoint);
		if (since == null || currentMillis - since > PENDING_TIMEOUT_MILLIS) {
			pendingSince.put(endpoint, currentMillis);
			return null;
		}
		servedFromSnapshot.incrementAndGet();
		return entry;
	}

	/**
	 * 发布一份新快照（**模拟线程**）。内容与上一份**逐字段相同**时沿用同一个代次 ——
	 * 于是带着 `If-None-Match` 来的客户端会拿到 304，连序列化都不必做。
	 *
	 * @return {@code body}（原样返回，方便调用方直接应答这一次请求）
	 */
	public JsonObject publish(String endpoint, JsonObject body) {
		final Entry previous = entries.get(endpoint);
		final long version = previous != null && previous.body.equals(body) ? previous.version : versionCounter.incrementAndGet();
		entries.put(endpoint, new Entry(body, System.currentTimeMillis(), version));
		pendingSince.remove(endpoint);
		published.incrementAndGet();
		// 发布也刷新"最近有人问"的时刻：发布本来就是被一次请求逼出来的，
		// 不记这一笔的话，清扫会把刚刚发布的那一份当成"没人看"（首次发布后立刻 sweep 就会踩到）。
		lastRequestAtMillis.set(System.currentTimeMillis());
		return body;
	}

	/** 模拟线程每若干 tick 调一次：长时间没人问就把快照全丢掉。 */
	public void sweep() {
		sweep(System.currentTimeMillis());
	}

	/** {@link #sweep()} 的可注入时刻版本（用例要能把时钟推到未来，不必真等五分钟）。 */
	void sweep(long currentMillis) {
		if (currentMillis - lastRequestAtMillis.get() > IDLE_EVICT_MILLIS) {
			entries.clear();
			pendingSince.clear();
		}
	}

	/** 已经从快照答出去的请求数（诊断：这个数远大于重建数才说明快照层真的在挡请求）。 */
	public long getServedFromSnapshot() {
		return servedFromSnapshot.get();
	}

	/** 发布次数（诊断）。 */
	public long getPublished() {
		return published.get();
	}

	/** 当前持有几份快照（清扫用例用）。 */
	public int size() {
		return entries.size();
	}

	/** 快照的 `ETag`：代次变了它就变，所以"内容没变"这件事能一路传到浏览器。 */
	public static String etagOf(long version) {
		return "\"" + Long.toHexString(version) + "\"";
	}

	/**
	 * 一份已发布的快照。
	 *
	 * <p>除了 {@code body} 之外全部是可以懒算并复用的：序列化与 UTF-8 编码都发生在 **Jetty 线程**上
	 * （这正是"把活从 tick 里搬出来"的那一半），同一代的多个请求共用同一个结果。</p>
	 */
	public static final class Entry {

		public final JsonObject body;
		public final long builtAtMillis;
		/** 内容代次：内容没变就不变（见 {@link #publish}）。 */
		public final long version;

		@Nullable
		private volatile String responseText;
		private volatile byte @Nullable [] responseBytes;

		private Entry(JsonObject body, long builtAtMillis, long version) {
			this.body = body;
			this.builtAtMillis = builtAtMillis;
			this.version = version;
		}

		public String etag() {
			return etagOf(version);
		}

		/** 响应体（含信封）的文本；同一代只构建一次（会调用 {@code responseTextSupplier}）。 */
		public String responseText(Supplier<String> responseTextSupplier) {
			String current = responseText;
			if (current == null) {
				current = responseTextSupplier.get();
				responseText = current;
			}
			return current;
		}

		/** 响应体的字节；同一代只编码一次。 */
		public byte[] responseBytes(Supplier<String> responseTextSupplier) {
			byte[] current = responseBytes;
			if (current == null) {
				current = responseText(responseTextSupplier).getBytes(StandardCharsets.UTF_8);
				responseBytes = current;
			}
			return current;
		}
	}
}
